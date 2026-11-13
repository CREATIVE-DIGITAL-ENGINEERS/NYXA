package com.axelor.apps.aiaccountant.azuremonitor;

import com.axelor.apps.aiaccountant.azuremonitor.AiAzureMonitorUnavailable.Reason;
import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads metrics for the Foundry resource from the Azure Monitor REST API.
 *
 * <p>Plain {@code java.net.http.HttpClient} + Jackson, consistent with every other outbound call in
 * this module and for the same reason: no Azure SDK, so azure-core/Reactor/Netty stay out of the
 * Axelor runtime.
 *
 * <h2>Resource metrics, not Application Insights</h2>
 *
 * These figures come from the Cognitive Services resource itself, which needs no instrumentation
 * key, no connected workspace and no tracing — only {@code Monitoring Reader} on the resource. The
 * richer per-agent tracing that Application Insights offers is a separate thing entirely and is not
 * used here.
 *
 * <h2>What it will not do</h2>
 *
 * The response carries a top-level {@code cost} field. It is the cost of running the <em>query</em>
 * in Azure's own quota terms and has nothing to do with money; it is read by nothing in this class.
 * Nothing here converts tokens into currency.
 */
@Singleton
public class AiAzureMonitorClient {

  private static final Logger LOG = LoggerFactory.getLogger(AiAzureMonitorClient.class);

  protected static final int CONNECT_TIMEOUT_SECONDS = 15;
  protected static final int REQUEST_TIMEOUT_SECONDS = 60;

  /** The dimensions worth splitting by, when a metric declares support for them. */
  protected static final String[] WANTED_DIMENSIONS = {
    AiAzureMetricPoint.DIMENSION_DEPLOYMENT, AiAzureMetricPoint.DIMENSION_MODEL
  };

  private final AiAccountantConfig config;
  private final AiArmTokenProvider tokenProvider;
  private final ObjectMapper mapper = new ObjectMapper();

  @Inject
  public AiAzureMonitorClient(AiAccountantConfig config, AiArmTokenProvider tokenProvider) {
    this.config = config;
    this.tokenProvider = tokenProvider;
  }

  /**
   * What the resource declares it can report.
   *
   * <p>Called before any metric query so the poller asks only for names, aggregations and
   * dimensions that exist. A guessed name is worse than a missing chart: it produces an empty one.
   */
  public Map<String, AiAzureMetricDefinition> metricDefinitions() {
    String url =
        base()
            + "/providers/Microsoft.Insights/metricDefinitions?api-version="
            + enc(config.azureMonitorApiVersion());
    JsonNode root = get(url);
    Map<String, AiAzureMetricDefinition> byName = new LinkedHashMap<>();
    for (JsonNode metric : root.path("value")) {
      String name = metric.path("name").path("value").asText(null);
      if (name == null || name.isEmpty()) {
        continue;
      }
      Set<String> dimensions = new LinkedHashSet<>();
      for (JsonNode dim : metric.path("dimensions")) {
        String d = dim.path("value").asText(null);
        if (d != null && !d.isEmpty()) {
          dimensions.add(d);
        }
      }
      byName.put(
          name,
          new AiAzureMetricDefinition(
              name,
              metric.path("unit").asText("Count"),
              metric.path("primaryAggregationType").asText("Total"),
              dimensions));
    }
    return byName;
  }

  /**
   * One metric, one grain, one window.
   *
   * <p>Queried per metric rather than batched: metrics do not share a supported aggregation, and
   * one unsupported combination in a batch fails the whole request. Eight small requests a quarter
   * of an hour is not a cost worth optimising against that.
   *
   * @param grain ISO 8601 duration — {@code PT1H} or {@code P1D}
   */
  public List<AiAzureMetricPoint> metrics(
      AiAzureMetricDefinition definition, String grain, Instant from, Instant to) {
    StringBuilder url = new StringBuilder(base());
    url.append("/providers/Microsoft.Insights/metrics?api-version=")
        .append(enc(config.azureMonitorApiVersion()))
        .append("&metricnames=")
        .append(enc(definition.getName()))
        .append("&aggregation=")
        .append(enc(definition.getPrimaryAggregation()))
        .append("&interval=")
        .append(enc(grain))
        .append("&timespan=")
        .append(enc(iso(from) + "/" + iso(to)));

    // Splitting by dimension requires an explicit wildcard filter; without it Azure returns the
    // rolled-up series only, and the per-deployment breakdown the page is for would be missing.
    String filter = dimensionFilter(definition);
    if (!filter.isEmpty()) {
      url.append("&$filter=").append(enc(filter));
    }

    JsonNode root = get(url.toString());
    List<AiAzureMetricPoint> points = new ArrayList<>();
    for (JsonNode metric : root.path("value")) {
      String name = metric.path("name").path("value").asText(definition.getName());
      String unit = metric.path("unit").asText(definition.getUnit());
      for (JsonNode series : metric.path("timeseries")) {
        Map<String, String> dimensions = new LinkedHashMap<>();
        for (JsonNode meta : series.path("metadatavalues")) {
          String key = meta.path("name").path("value").asText(null);
          String value = meta.path("value").asText(null);
          if (key != null && !key.isEmpty()) {
            dimensions.put(canonicalDimension(key), value == null ? "" : value);
          }
        }
        for (JsonNode data : series.path("data")) {
          LocalDateTime bucket = bucket(data.path("timeStamp").asText(null));
          if (bucket == null) {
            continue;
          }
          BigDecimal value = value(data, definition.getPrimaryAggregation());
          if (value == null) {
            // Azure omits the field for a bucket it has no data for. A gap is not a zero: writing
            // one would invent a measurement, and the chart would claim the assistant was idle in
            // an
            // hour Azure simply has not finished aggregating.
            continue;
          }
          points.add(
              new AiAzureMetricPoint(
                  name,
                  definition.getPrimaryAggregation(),
                  grain,
                  bucket,
                  unit,
                  value,
                  dimensions));
        }
      }
    }
    return points;
  }

  /** {@code ModelDeploymentName eq '*' and ModelName eq '*'}, for whichever are supported. */
  protected String dimensionFilter(AiAzureMetricDefinition definition) {
    StringBuilder filter = new StringBuilder();
    for (String wanted : WANTED_DIMENSIONS) {
      if (definition.supportsDimension(wanted)) {
        if (filter.length() > 0) {
          filter.append(" and ");
        }
        filter.append(wanted).append(" eq '*'");
      }
    }
    return filter.toString();
  }

  /**
   * Azure reports dimension names in different cases on different endpoints — {@code
   * modeldeploymentname} in metadata, {@code ModelDeploymentName} in definitions. Normalised so the
   * dimension key stays stable, since the key is part of a row's identity.
   */
  protected static String canonicalDimension(String reported) {
    for (String wanted : WANTED_DIMENSIONS) {
      if (wanted.equalsIgnoreCase(reported)) {
        return wanted;
      }
    }
    return reported;
  }

  /** Reads whichever aggregation field was requested; null when the bucket has no value. */
  protected static BigDecimal value(JsonNode data, String aggregation) {
    String field = aggregation == null ? "total" : aggregation.toLowerCase();
    JsonNode node = data.path(field);
    if (node.isMissingNode() || node.isNull()) {
      return null;
    }
    return new BigDecimal(node.asText("0"));
  }

  protected static LocalDateTime bucket(String timestamp) {
    if (timestamp == null || timestamp.isEmpty()) {
      return null;
    }
    try {
      return LocalDateTime.ofInstant(Instant.parse(timestamp), ZoneOffset.UTC);
    } catch (RuntimeException e) {
      return null;
    }
  }

  protected String base() {
    String resourceId = config.azureMonitorResourceId();
    if (resourceId.isEmpty()) {
      throw new AiAzureMonitorUnavailable(
          Reason.NOT_CONFIGURED,
          "aiaccountant.azure.monitor.resource.id is not set, so there is no resource to read"
              + " metrics from.");
    }
    String trimmed = resourceId.startsWith("/") ? resourceId : "/" + resourceId;
    if (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return "https://management.azure.com" + trimmed;
  }

  /** One GET, with every failure mapped to a reason the Stats page can explain. */
  protected JsonNode get(String url) {
    HttpResult res;
    try {
      res = httpGet(url, tokenProvider.bearerToken());
    } catch (java.net.http.HttpTimeoutException e) {
      throw new AiAzureMonitorUnavailable(Reason.TIMEOUT, "Azure Monitor timed out.", e);
    } catch (IOException e) {
      throw new AiAzureMonitorUnavailable(
          Reason.UNREACHABLE, "Cannot reach Azure Monitor: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AiAzureMonitorUnavailable(
          Reason.UNREACHABLE, "Interrupted while reading Azure Monitor.", e);
    }

    if (res.status == 401 || res.status == 403) {
      throw new AiAzureMonitorUnavailable(
          Reason.FORBIDDEN,
          "Azure refused the metrics request (HTTP "
              + res.status
              + "). The app registration needs the 'Monitoring Reader' role on the Foundry"
              + " resource.");
    }
    if (res.status == 429) {
      throw new AiAzureMonitorUnavailable(
          Reason.THROTTLED, "Azure Monitor is throttling the metrics request (HTTP 429).");
    }
    if (res.status != 200) {
      throw new AiAzureMonitorUnavailable(
          Reason.REJECTED, "Azure Monitor returned HTTP " + res.status + ".");
    }
    try {
      return mapper.readTree(res.body);
    } catch (IOException e) {
      throw new AiAzureMonitorUnavailable(Reason.MALFORMED, "Malformed Azure Monitor response.", e);
    }
  }

  protected HttpResult httpGet(String url, String bearer) throws IOException, InterruptedException {
    LOG.debug("AI Accountant: reading Azure Monitor: {}", url);
    HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .build();
    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Authorization", "Bearer " + bearer)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return new HttpResult(resp.statusCode(), resp.body());
  }

  protected static String iso(Instant instant) {
    return DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(ChronoUnit.SECONDS));
  }

  protected static String enc(String v) {
    try {
      return URLEncoder.encode(v, StandardCharsets.UTF_8.name());
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e); // UTF-8 always present
    }
  }

  /** Minimal transport result, so the HTTP call can be stubbed in tests. */
  protected static final class HttpResult {
    final int status;
    final String body;

    HttpResult(int status, String body) {
      this.status = status;
      this.body = body;
    }
  }
}

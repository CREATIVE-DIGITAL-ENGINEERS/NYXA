package com.axelor.apps.aiaccountant.azuremonitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.azuremonitor.AiAzureMonitorUnavailable.Reason;
import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Parsing and failure mapping, against fixtures only. Nothing here reaches Azure.
 *
 * <p>The failure cases carry as much weight as the parsing ones: a 403 has to be distinguishable
 * from an idle resource, because the fix for one is granting a role and the fix for the other is
 * nothing at all.
 */
class AiAzureMonitorClientTest {

  private static final String RESOURCE_ID =
      "/subscriptions/0000/resourceGroups/rg/providers/Microsoft.CognitiveServices/accounts/"
          + "example-foundry";

  private static AiAccountantConfig config(String resourceId) {
    return new AiAccountantConfig() {
      @Override
      public String azureMonitorResourceId() {
        return resourceId;
      }

      @Override
      public String azureMonitorApiVersion() {
        return "2018-01-01";
      }
    };
  }

  /** A token provider that never leaves the test. */
  private static AiArmTokenProvider tokens(AiAccountantConfig config) {
    return new AiArmTokenProvider(config) {
      @Override
      public String bearerToken() {
        return "test-token";
      }
    };
  }

  /** Client whose transport is a canned status and body, capturing the URL it would have called. */
  private static class StubClient extends AiAzureMonitorClient {
    private final int status;
    private final String body;
    private final IOException failure;
    String lastUrl;

    StubClient(String resourceId, int status, String body, IOException failure) {
      super(config(resourceId), tokens(config(resourceId)));
      this.status = status;
      this.body = body;
      this.failure = failure;
    }

    @Override
    protected HttpResult httpGet(String url, String bearer) throws IOException {
      lastUrl = url;
      if (failure != null) {
        throw failure;
      }
      return new HttpResult(status, body);
    }
  }

  private static StubClient ok(String body) {
    return new StubClient(RESOURCE_ID, 200, body, null);
  }

  @Test
  void readsMetricDefinitionsIncludingDimensions() {
    String body =
        "{\"value\":[{\"name\":{\"value\":\"ProcessedPromptTokens\"},\"unit\":\"Count\","
            + "\"primaryAggregationType\":\"Total\","
            + "\"dimensions\":[{\"value\":\"ModelDeploymentName\"},{\"value\":\"ModelName\"}]},"
            + "{\"name\":{\"value\":\"AzureOpenAIRequests\"},\"unit\":\"Count\","
            + "\"primaryAggregationType\":\"Total\"}]}";

    Map<String, AiAzureMetricDefinition> defs = ok(body).metricDefinitions();

    assertEquals(2, defs.size());
    AiAzureMetricDefinition tokens = defs.get("ProcessedPromptTokens");
    assertNotNull(tokens);
    assertEquals("Total", tokens.getPrimaryAggregation());
    assertTrue(tokens.supportsDimension("ModelDeploymentName"));
    assertTrue(
        tokens.supportsDimension("modelname"), "dimension names are matched case-insensitively");
    assertTrue(defs.get("AzureOpenAIRequests").getDimensions().isEmpty());
  }

  @Test
  void readsDatapointsAndBuildsAStableDimensionKey() {
    String body =
        "{\"cost\":0,\"interval\":\"PT1H\",\"value\":[{\"name\":{\"value\":"
            + "\"ProcessedPromptTokens\"},\"unit\":\"Count\",\"timeseries\":[{\"metadatavalues\":["
            + "{\"name\":{\"value\":\"modelname\"},\"value\":\"gpt-4o-2024-08-06\"},"
            + "{\"name\":{\"value\":\"modeldeploymentname\"},\"value\":\"gpt-4o\"}],"
            + "\"data\":[{\"timeStamp\":\"2026-09-08T10:00:00Z\",\"total\":1234.5},"
            + "{\"timeStamp\":\"2026-09-08T11:00:00Z\"}]}]}]}";

    AiAzureMetricDefinition definition =
        new AiAzureMetricDefinition("ProcessedPromptTokens", "Count", "Total", null);
    List<AiAzureMetricPoint> points =
        ok(body).metrics(definition, AiAzureGrain.HOURLY, Instant.EPOCH, Instant.EPOCH);

    // The bucket with no `total` is skipped: Azure omits the field for an interval it has not
    // finished aggregating, and writing a zero would claim the resource served nothing.
    assertEquals(1, points.size());
    AiAzureMetricPoint point = points.get(0);
    assertEquals(new BigDecimal("1234.5"), point.getValue());
    assertEquals("gpt-4o", point.getModelDeploymentName());
    assertEquals("gpt-4o-2024-08-06", point.getModelName());
    // Name-sorted and canonically cased, so the same tuple always yields the same identity even
    // though Azure returned the two dimensions in the other order and in lower case.
    assertEquals("ModelDeploymentName=gpt-4o|ModelName=gpt-4o-2024-08-06", point.getDimensionKey());
    assertEquals(AiAzureGrain.HOURLY, point.getIntervalGrain());
    assertEquals(10, point.getBucketTimestamp().getHour());
  }

  @Test
  void fractionalValuesSurvive() {
    // Latency and every Average aggregation are fractional; a long would have truncated this.
    String body =
        "{\"value\":[{\"name\":{\"value\":\"Latency\"},\"unit\":\"Milliseconds\","
            + "\"timeseries\":[{\"metadatavalues\":[],\"data\":[{\"timeStamp\":"
            + "\"2026-09-08T00:00:00Z\",\"average\":812.375}]}]}]}";
    AiAzureMetricDefinition definition =
        new AiAzureMetricDefinition("Latency", "Milliseconds", "Average", null);

    List<AiAzureMetricPoint> points =
        ok(body).metrics(definition, AiAzureGrain.HOURLY, Instant.EPOCH, Instant.EPOCH);

    assertEquals(new BigDecimal("812.375"), points.get(0).getValue());
    assertEquals("Average", points.get(0).getAggregation());
  }

  @Test
  void metricWithoutDimensionsGetsAnEmptyKeyNotNull() {
    String body =
        "{\"value\":[{\"name\":{\"value\":\"AzureOpenAIRequests\"},\"unit\":\"Count\","
            + "\"timeseries\":[{\"metadatavalues\":[],\"data\":[{\"timeStamp\":"
            + "\"2026-09-08T00:00:00Z\",\"total\":7}]}]}]}";
    AiAzureMetricDefinition definition =
        new AiAzureMetricDefinition("AzureOpenAIRequests", "Count", "Total", null);

    List<AiAzureMetricPoint> points =
        ok(body).metrics(definition, AiAzureGrain.DAILY, Instant.EPOCH, Instant.EPOCH);

    // Empty string, never null: two SQL nulls do not conflict, so a null here would quietly disable
    // the unique constraint that makes the upsert idempotent.
    assertEquals("", points.get(0).getDimensionKey());
  }

  @Test
  void requestsDimensionSplitOnlyForSupportedDimensions() {
    AiAzureMetricDefinition both =
        new AiAzureMetricDefinition(
            "ProcessedPromptTokens",
            "Count",
            "Total",
            new LinkedHashSet<>(List.of("ModelDeploymentName", "ModelName")));
    StubClient split = ok("{\"value\":[]}");
    split.metrics(both, AiAzureGrain.HOURLY, Instant.EPOCH, Instant.EPOCH);
    // Without the wildcard filter Azure returns only the rolled-up series, and the per-deployment
    // breakdown the page exists for would silently be missing.
    assertTrue(split.lastUrl.contains("filter"), "a dimension filter must be sent");

    AiAzureMetricDefinition none =
        new AiAzureMetricDefinition("AzureOpenAIRequests", "Count", "Total", null);
    StubClient plain = ok("{\"value\":[]}");
    plain.metrics(none, AiAzureGrain.HOURLY, Instant.EPOCH, Instant.EPOCH);
    assertFalse(
        plain.lastUrl.contains("filter"),
        "a metric with no dimensions must not be sent a filter it would reject");
  }

  @Test
  void missingResourceIdIsReportedAsNotConfigured() {
    AiAzureMonitorUnavailable e =
        assertThrows(
            AiAzureMonitorUnavailable.class,
            () -> new StubClient("", 200, "{}", null).metricDefinitions());
    assertEquals(Reason.NOT_CONFIGURED, e.getReason());
  }

  @Test
  void forbiddenIsReportedAsAMissingRoleRatherThanAsNoData() {
    for (int status : new int[] {401, 403}) {
      AiAzureMonitorUnavailable e =
          assertThrows(
              AiAzureMonitorUnavailable.class,
              () -> new StubClient(RESOURCE_ID, status, "{}", null).metricDefinitions());
      assertEquals(Reason.FORBIDDEN, e.getReason());
      assertTrue(e.getMessage().contains("Monitoring Reader"), "the message must name the fix");
    }
  }

  @Test
  void throttlingAndRejectionAndMalformedAreDistinguished() {
    assertEquals(
        Reason.THROTTLED,
        assertThrows(
                AiAzureMonitorUnavailable.class,
                () -> new StubClient(RESOURCE_ID, 429, "{}", null).metricDefinitions())
            .getReason());
    assertEquals(
        Reason.REJECTED,
        assertThrows(
                AiAzureMonitorUnavailable.class,
                () -> new StubClient(RESOURCE_ID, 500, "{}", null).metricDefinitions())
            .getReason());
    assertEquals(
        Reason.MALFORMED,
        assertThrows(
                AiAzureMonitorUnavailable.class,
                () -> new StubClient(RESOURCE_ID, 200, "not json", null).metricDefinitions())
            .getReason());
  }

  @Test
  void timeoutAndUnreachableAreDistinguished() {
    assertEquals(
        Reason.TIMEOUT,
        assertThrows(
                AiAzureMonitorUnavailable.class,
                () ->
                    new StubClient(
                            RESOURCE_ID, 0, null, new java.net.http.HttpTimeoutException("timeout"))
                        .metricDefinitions())
            .getReason());
    assertEquals(
        Reason.UNREACHABLE,
        assertThrows(
                AiAzureMonitorUnavailable.class,
                () ->
                    new StubClient(RESOURCE_ID, 0, null, new IOException("connection refused"))
                        .metricDefinitions())
            .getReason());
  }
}

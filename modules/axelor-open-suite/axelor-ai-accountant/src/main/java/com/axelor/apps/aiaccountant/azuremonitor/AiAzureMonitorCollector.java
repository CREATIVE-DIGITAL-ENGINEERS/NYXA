package com.axelor.apps.aiaccountant.azuremonitor;

import com.axelor.apps.aiaccountant.azuremonitor.AiAzureMonitorUnavailable.Reason;
import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.google.inject.Singleton;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects one grain's worth of Azure metrics into the snapshot table.
 *
 * <p>The whole of the Azure side of the Stats page runs through here: validate what the resource
 * actually offers, read a rolling window of it, and upsert. Everything it can go wrong with —
 * missing role, throttling, an unset resource id — leaves through {@link AiAzureMonitorStatus} as a
 * stated reason rather than as an exception nobody sees, because the page has to be able to say why
 * a panel is empty.
 */
@Singleton
public class AiAzureMonitorCollector {

  private static final Logger LOG = LoggerFactory.getLogger(AiAzureMonitorCollector.class);

  private final AiAccountantConfig config;
  private final AiAzureMonitorClient client;
  private final AiAzureMetricUpsertService upsertService;
  private final AiAzureMonitorStatus status;

  @Inject
  public AiAzureMonitorCollector(
      AiAccountantConfig config,
      AiAzureMonitorClient client,
      AiAzureMetricUpsertService upsertService,
      AiAzureMonitorStatus status) {
    this.config = config;
    this.client = client;
    this.upsertService = upsertService;
    this.status = status;
  }

  /**
   * Reads the hourly series over its rolling lookback. Feeds the 24h view.
   *
   * <p>The lookback is deliberately much longer than the poll interval: re-reading only the last
   * hour would freeze each bucket at whatever Azure had aggregated by the minute it was asked.
   */
  public int collectHourly() {
    int hours = Math.max(1, config.azureMonitorHourlyLookbackHours());
    Instant to = Instant.now().truncatedTo(ChronoUnit.MINUTES);
    return collect(AiAzureGrain.HOURLY, to.minus(hours, ChronoUnit.HOURS), to);
  }

  /** Reads the daily series over its rolling lookback. Feeds the 7d and 30d views. */
  public int collectDaily() {
    int days = Math.max(1, config.azureMonitorDailyLookbackDays());
    Instant to = Instant.now().truncatedTo(ChronoUnit.MINUTES);
    return collect(AiAzureGrain.DAILY, to.minus(days, ChronoUnit.DAYS), to);
  }

  protected int collect(String grain, Instant from, Instant to) {
    if (!config.isAppActive()) {
      LOG.debug("AI Accountant: application is not installed/active; skipping Azure collection.");
      status.recordFailure(
          Reason.NOT_CONFIGURED, "The AI Accountant application is not installed or is inactive.");
      return 0;
    }
    if (!config.isAzureMonitorEnabled()) {
      LOG.debug("AI Accountant: Azure Monitor collection is disabled.");
      status.recordFailure(
          Reason.NOT_CONFIGURED,
          "Azure metrics are switched off (aiaccountant.azure.monitor.enabled=false).");
      return 0;
    }
    String resourceId = config.azureMonitorResourceId();
    try {
      Map<String, AiAzureMetricDefinition> wanted = supported();
      if (wanted.isEmpty()) {
        status.recordFailure(
            Reason.REJECTED,
            "The resource reports none of the configured metrics"
                + " (aiaccountant.azure.monitor.metrics).");
        return 0;
      }
      int written = 0;
      for (AiAzureMetricDefinition definition : wanted.values()) {
        List<AiAzureMetricPoint> points = client.metrics(definition, grain, from, to);
        written += upsertService.upsertAll(resourceId, points);
      }
      status.recordSuccess(written);
      LOG.info(
          "AI Accountant: collected {} Azure metric datapoint(s) at {} grain over {} metric(s).",
          written,
          grain,
          wanted.size());
      return written;
    } catch (AiAzureMonitorUnavailable e) {
      // Expected operational states, not defects: say which one and leave the stored series alone.
      status.recordFailure(e.getReason(), e.getMessage());
      LOG.warn("AI Accountant: Azure Monitor unavailable ({}): {}", e.getReason(), e.getMessage());
      return 0;
    } catch (RuntimeException e) {
      status.recordFailure(Reason.REJECTED, e.getMessage());
      LOG.error("AI Accountant: Azure metric collection failed.", e);
      return 0;
    }
  }

  /**
   * The configured metrics, narrowed to those the resource declares.
   *
   * <p>Validated rather than assumed: a name that does not exist on this resource would either be
   * rejected outright or answer with nothing, and an empty chart with no explanation is the worst
   * of the available outcomes. What was dropped is logged, once per collection, so a typo in the
   * configuration is visible rather than mysterious.
   */
  protected Map<String, AiAzureMetricDefinition> supported() {
    Map<String, AiAzureMetricDefinition> available = client.metricDefinitions();
    Map<String, AiAzureMetricDefinition> wanted = new LinkedHashMap<>();
    List<String> unknown = new ArrayList<>();
    for (String name : config.azureMonitorMetrics()) {
      AiAzureMetricDefinition definition = matching(available, name);
      if (definition == null) {
        unknown.add(name);
      } else {
        wanted.put(definition.getName(), definition);
      }
    }
    if (!unknown.isEmpty()) {
      Set<String> offered = new TreeSet<>(available.keySet());
      LOG.warn(
          "AI Accountant: the resource does not report {}; skipping. It offers: {}.",
          String.join(", ", unknown),
          String.join(", ", offered));
    }
    return wanted;
  }

  /** Case-insensitive, because a configured name is typed by a person. */
  protected static AiAzureMetricDefinition matching(
      Map<String, AiAzureMetricDefinition> available, String name) {
    AiAzureMetricDefinition exact = available.get(name);
    if (exact != null) {
      return exact;
    }
    for (Map.Entry<String, AiAzureMetricDefinition> e : available.entrySet()) {
      if (e.getKey().equalsIgnoreCase(name)) {
        return e.getValue();
      }
    }
    return null;
  }
}

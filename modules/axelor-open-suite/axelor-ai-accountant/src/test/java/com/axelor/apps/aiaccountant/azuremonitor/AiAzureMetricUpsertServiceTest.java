package com.axelor.apps.aiaccountant.azuremonitor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.aiaccountant.db.AiAzureMetricSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The idempotency the whole snapshot design rests on.
 *
 * <p>Every poll re-reads its entire window, so if a repeated datapoint inserted a second row, each
 * bucket would be multiplied by the number of polls that ever saw it and every Azure chart would
 * read high. These tests are the guard on that.
 */
class AiAzureMetricUpsertServiceTest {

  private static final String RESOURCE = "/subscriptions/0000/resourceGroups/rg/x";

  /** In-memory stand-in for the table, keyed exactly as the unique constraint is. */
  private static class InMemoryUpsert extends AiAzureMetricUpsertService {
    final Map<String, AiAzureMetricSnapshot> rows = new LinkedHashMap<>();
    final List<AiAzureMetricSnapshot> saves = new ArrayList<>();

    @Override
    protected AiAzureMetricSnapshot find(String resourceId, AiAzureMetricPoint point) {
      return rows.get(key(resourceId, point));
    }

    @Override
    protected AiAzureMetricSnapshot save(AiAzureMetricSnapshot row) {
      saves.add(row);
      rows.put(
          String.join(
              "|",
              row.getResourceId(),
              row.getMetricName(),
              row.getAggregation(),
              row.getIntervalGrain(),
              String.valueOf(row.getBucketTimestamp()),
              row.getDimensionKey()),
          row);
      return row;
    }

    static String key(String resourceId, AiAzureMetricPoint p) {
      return String.join(
          "|",
          resourceId,
          p.getMetricName(),
          p.getAggregation(),
          p.getIntervalGrain(),
          String.valueOf(p.getBucketTimestamp()),
          p.getDimensionKey());
    }
  }

  private static AiAzureMetricPoint point(
      String metric, String grain, LocalDateTime bucket, String value, Map<String, String> dims) {
    return new AiAzureMetricPoint(
        metric, "Total", grain, bucket, "Count", new BigDecimal(value), dims);
  }

  private static final LocalDateTime BUCKET = LocalDateTime.of(2026, 9, 8, 10, 0);

  @Test
  void pollingTheSameWindowTwiceLeavesOneRowWithTheLatestValue() {
    InMemoryUpsert service = new InMemoryUpsert();
    Map<String, String> dims = Collections.singletonMap("ModelDeploymentName", "gpt-4o");

    service.upsertAll(
        RESOURCE,
        Collections.singletonList(
            point("ProcessedPromptTokens", AiAzureGrain.HOURLY, BUCKET, "100", dims)));
    // The second poll sees Azure's revised figure for the same hour.
    service.upsertAll(
        RESOURCE,
        Collections.singletonList(
            point("ProcessedPromptTokens", AiAzureGrain.HOURLY, BUCKET, "175", dims)));

    assertEquals(1, service.rows.size(), "the same identity must not produce a second row");
    assertEquals(
        new BigDecimal("175"),
        service.rows.values().iterator().next().getMetricValue(),
        "the corrected value wins, which is the point of re-reading the window");
  }

  @Test
  void grainIsPartOfTheIdentitySoTheTwoSeriesDoNotOverwriteEachOther() {
    InMemoryUpsert service = new InMemoryUpsert();

    service.upsertAll(
        RESOURCE,
        List.of(
            point("ProcessedPromptTokens", AiAzureGrain.HOURLY, BUCKET, "100", Map.of()),
            point("ProcessedPromptTokens", AiAzureGrain.DAILY, BUCKET, "2400", Map.of())));

    assertEquals(2, service.rows.size(), "hourly and daily are separate series");
  }

  @Test
  void dimensionsArePartOfTheIdentity() {
    InMemoryUpsert service = new InMemoryUpsert();

    service.upsertAll(
        RESOURCE,
        List.of(
            point(
                "ProcessedPromptTokens",
                AiAzureGrain.DAILY,
                BUCKET,
                "10",
                Collections.singletonMap("ModelDeploymentName", "gpt-4o")),
            point(
                "ProcessedPromptTokens",
                AiAzureGrain.DAILY,
                BUCKET,
                "20",
                Collections.singletonMap("ModelDeploymentName", "gpt-4o-mini"))));

    // Two deployments in the same bucket are two figures. Without the dimension in the key, one
    // would overwrite the other and the chart would show whichever arrived last.
    assertEquals(2, service.rows.size());
  }

  @Test
  void metricAndResourceArePartOfTheIdentity() {
    InMemoryUpsert service = new InMemoryUpsert();

    service.upsertAll(
        RESOURCE,
        List.of(
            point("ProcessedPromptTokens", AiAzureGrain.DAILY, BUCKET, "10", Map.of()),
            point("GeneratedTokens", AiAzureGrain.DAILY, BUCKET, "20", Map.of())));
    service.upsertAll(
        "/subscriptions/0000/resourceGroups/rg/other",
        Collections.singletonList(
            point("ProcessedPromptTokens", AiAzureGrain.DAILY, BUCKET, "30", Map.of())));

    assertEquals(3, service.rows.size());
  }

  @Test
  void emptyBatchWritesNothing() {
    InMemoryUpsert service = new InMemoryUpsert();
    assertEquals(0, service.upsertAll(RESOURCE, Collections.emptyList()));
    assertEquals(0, service.upsertAll(RESOURCE, null));
    assertEquals(0, service.saves.size());
  }
}

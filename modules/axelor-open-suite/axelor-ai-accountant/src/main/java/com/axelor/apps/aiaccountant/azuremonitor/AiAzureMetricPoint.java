package com.axelor.apps.aiaccountant.azuremonitor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One datapoint as Azure reported it, before it becomes a row.
 *
 * <p>{@link #getDimensionKey()} is the canonical encoding of the whole dimension tuple and is what
 * makes a snapshot row's identity complete: the poll re-reads its window every time, so without an
 * identity that includes the dimensions, two deployments' figures for the same hour would overwrite
 * one another and the chart would show whichever arrived last instead of both.
 */
public final class AiAzureMetricPoint {

  public static final String DIMENSION_DEPLOYMENT = "ModelDeploymentName";
  public static final String DIMENSION_MODEL = "ModelName";

  private final String metricName;
  private final String aggregation;
  private final String intervalGrain;
  private final LocalDateTime bucketTimestamp;
  private final String unit;
  private final BigDecimal value;
  private final Map<String, String> dimensions;

  public AiAzureMetricPoint(
      String metricName,
      String aggregation,
      String intervalGrain,
      LocalDateTime bucketTimestamp,
      String unit,
      BigDecimal value,
      Map<String, String> dimensions) {
    this.metricName = metricName;
    this.aggregation = aggregation;
    this.intervalGrain = intervalGrain;
    this.bucketTimestamp = bucketTimestamp;
    this.unit = unit;
    this.value = value == null ? BigDecimal.ZERO : value;
    this.dimensions = dimensions == null ? new LinkedHashMap<>() : new LinkedHashMap<>(dimensions);
  }

  public String getMetricName() {
    return metricName;
  }

  public String getAggregation() {
    return aggregation;
  }

  public String getIntervalGrain() {
    return intervalGrain;
  }

  public LocalDateTime getBucketTimestamp() {
    return bucketTimestamp;
  }

  public String getUnit() {
    return unit;
  }

  public BigDecimal getValue() {
    return value;
  }

  public String getModelDeploymentName() {
    return find(DIMENSION_DEPLOYMENT);
  }

  public String getModelName() {
    return find(DIMENSION_MODEL);
  }

  /**
   * Deterministic encoding of every dimension, name-sorted, so the same tuple always produces the
   * same key. Empty string — never null — when the metric has no dimensions, because two SQL nulls
   * do not conflict and the unique constraint would stop constraining.
   */
  public String getDimensionKey() {
    if (dimensions.isEmpty()) {
      return "";
    }
    return dimensions.entrySet().stream()
        .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
        .map(e -> e.getKey() + "=" + e.getValue())
        .reduce((a, b) -> a + "|" + b)
        .orElse("");
  }

  private String find(String name) {
    for (Map.Entry<String, String> e : dimensions.entrySet()) {
      if (name.equalsIgnoreCase(e.getKey())) {
        return e.getValue();
      }
    }
    return null;
  }
}

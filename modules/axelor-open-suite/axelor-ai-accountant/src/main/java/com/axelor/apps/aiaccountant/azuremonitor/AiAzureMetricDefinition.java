package com.axelor.apps.aiaccountant.azuremonitor;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * What the resource says one of its metrics actually supports.
 *
 * <h2>Why this is read instead of assumed</h2>
 *
 * A metric name, an aggregation and a dimension are three things Azure will reject or silently
 * return nothing for if they are wrong, and "returns nothing" is indistinguishable on a chart from
 * "the assistant was idle". Rather than hard-coding a list that was true of one resource on one
 * day, the collector reads {@code metricDefinitions} and keeps only what the resource itself
 * declares — so pointing the module at a different deployment degrades to fewer charts instead of
 * to empty ones.
 */
public final class AiAzureMetricDefinition {

  private final String name;
  private final String unit;
  private final String primaryAggregation;
  private final Set<String> dimensions;

  public AiAzureMetricDefinition(
      String name, String unit, String primaryAggregation, Set<String> dimensions) {
    this.name = name;
    this.unit = unit == null || unit.trim().isEmpty() ? "Count" : unit.trim();
    this.primaryAggregation =
        primaryAggregation == null || primaryAggregation.trim().isEmpty()
            ? "Total"
            : primaryAggregation.trim();
    this.dimensions =
        dimensions == null
            ? Collections.emptySet()
            : Collections.unmodifiableSet(new LinkedHashSet<>(dimensions));
  }

  public String getName() {
    return name;
  }

  public String getUnit() {
    return unit;
  }

  public String getPrimaryAggregation() {
    return primaryAggregation;
  }

  public Set<String> getDimensions() {
    return dimensions;
  }

  /** Case-insensitive: Azure reports dimension names inconsistently cased between endpoints. */
  public boolean supportsDimension(String dimension) {
    if (dimension == null) {
      return false;
    }
    for (String d : dimensions) {
      if (dimension.equalsIgnoreCase(d)) {
        return true;
      }
    }
    return false;
  }
}

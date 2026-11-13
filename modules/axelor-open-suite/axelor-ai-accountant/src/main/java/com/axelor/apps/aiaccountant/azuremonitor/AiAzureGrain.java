package com.axelor.apps.aiaccountant.azuremonitor;

/**
 * The two grains the snapshot table holds, as ISO 8601 durations.
 *
 * <h2>Why exactly two, and why they are never mixed</h2>
 *
 * A dashboard wants 24 hours, 7 days and 30 days. Storing a series per window would store the same
 * hour three times over and every chart summing across them would read high — so instead there are
 * two canonical series and a window is a {@code WHERE} clause over one of them. The hourly series
 * answers "today" and the daily series answers everything longer.
 *
 * <p>Because both live in one table, every query against it <b>must</b> constrain {@code
 * intervalGrain}. Selecting without it sums the same hour twice — once from its own bucket and once
 * from the day that contains it — which is the one way to make these charts silently wrong.
 */
public final class AiAzureGrain {

  public static final String HOURLY = "PT1H";
  public static final String DAILY = "P1D";

  private AiAzureGrain() {}
}

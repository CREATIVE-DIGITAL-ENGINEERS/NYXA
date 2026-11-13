package com.axelor.apps.aiaccountant.job;

import com.axelor.apps.aiaccountant.azuremonitor.AiAzureMonitorCollector;
import com.axelor.apps.base.job.ThreadedJob;
import com.axelor.inject.Beans;
import org.quartz.JobExecutionContext;

/**
 * Refreshes the daily Azure series, which is what the 7-day and 30-day views are drawn from.
 *
 * <p>Its own job rather than a branch inside the hourly one because it runs on its own cadence and
 * over its own window: re-reading ninety days of daily buckets every quarter of an hour would be
 * ninety times the work for a series that changes once a day.
 */
public class AiAzureMonitorDailyJob extends ThreadedJob {

  @Override
  public void executeInThread(JobExecutionContext context) {
    Beans.get(AiAzureMonitorCollector.class).collectDaily();
  }
}

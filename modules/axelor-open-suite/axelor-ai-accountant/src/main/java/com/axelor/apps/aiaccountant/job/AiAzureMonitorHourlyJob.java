package com.axelor.apps.aiaccountant.job;

import com.axelor.apps.aiaccountant.azuremonitor.AiAzureMonitorCollector;
import com.axelor.apps.base.job.ThreadedJob;
import com.axelor.inject.Beans;
import org.quartz.JobExecutionContext;

/**
 * Refreshes the hourly Azure series. Seeded via {@code data-init/input/meta_schedule.csv}.
 *
 * <p>Scheduled rather than hand-threaded so Quartz owns the lifecycle: the schedule is visible and
 * editable in the admin UI, overlapping runs are suppressed by {@link ThreadedJob}, and a redeploy
 * cannot leave a thread of ours behind.
 */
public class AiAzureMonitorHourlyJob extends ThreadedJob {

  @Override
  public void executeInThread(JobExecutionContext context) {
    Beans.get(AiAzureMonitorCollector.class).collectHourly();
  }
}

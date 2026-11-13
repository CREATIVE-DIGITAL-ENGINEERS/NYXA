package com.axelor.apps.aiaccountant.job;

import com.axelor.apps.aiaccountant.service.AiAccountantRetentionService;
import com.axelor.apps.base.job.ThreadedJob;
import com.axelor.inject.Beans;
import org.quartz.JobExecutionContext;

/**
 * Prunes local usage rows and both Azure snapshot series, each by its own window.
 *
 * <p>One job for all three because they are the same decision made three times, and a single daily
 * pass is easier to reason about than three schedules that could drift apart.
 */
public class AiAccountantRetentionJob extends ThreadedJob {

  @Override
  public void executeInThread(JobExecutionContext context) {
    Beans.get(AiAccountantRetentionService.class).pruneAll();
  }
}

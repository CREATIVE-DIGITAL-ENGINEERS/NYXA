package com.axelor.apps.aiocr.job;

import com.axelor.apps.aiocr.service.OcrFileQuarantineService;
import com.axelor.apps.base.job.ThreadedJob;
import com.axelor.inject.Beans;
import java.time.Instant;
import org.quartz.JobExecutionContext;

/**
 * Reclaims leftover quarantined OCR blobs — the durable retry for post-commit permanent deletions
 * that failed. A blob whose commit-time deletion failed simply stays in the quarantine directory;
 * this periodic sweep removes any older than the retention window ({@code
 * aiocr.file.quarantine.retention.hours}). Seeded via {@code data-init/input/meta_schedule.csv}.
 *
 * <p>In-flight quarantined blobs (deleted at commit, or restored at rollback, within seconds) are
 * far younger than the retention window, so the sweep never races an active delete.
 */
public class OcrFileQuarantineSweepJob extends ThreadedJob {

  @Override
  public void executeInThread(JobExecutionContext context) {
    Beans.get(OcrFileQuarantineService.class).sweep(Instant.now().toEpochMilli());
  }
}

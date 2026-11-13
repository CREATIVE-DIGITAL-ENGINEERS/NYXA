package com.axelor.apps.aiocr.job;

import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.base.job.ThreadedJob;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.inject.Beans;
import java.util.List;
import org.quartz.JobExecutionContext;

/**
 * Scheduled poller: picks up QUEUED {@link AiOcrUpload} jobs and processes each one (extract →
 * build DRAFT supplier invoice, or FAIL/DUPLICATE). Seeded via {@code
 * data-init/input/meta_schedule.csv}.
 *
 * <p>Concurrency-safe: {@link BgOcrIntakeService#process(Long)} claims each job atomically, so
 * overlapping poller runs never double-process. One bad job never stops the batch. This job builds
 * no accounting entries itself — it only drives the intake service.
 */
public class AiOcrPollJob extends ThreadedJob {

  /** Max jobs processed per tick (keeps a run bounded; the next tick picks up the rest). */
  protected static final int BATCH = 50;

  @Override
  public void executeInThread(JobExecutionContext context) {
    AiOcrUploadRepository uploadRepo = Beans.get(AiOcrUploadRepository.class);
    BgOcrIntakeService intake = Beans.get(BgOcrIntakeService.class);

    // Only real documents (a file to OCR). Archive-carrier records hold a ZIP in archiveFile and no
    // originalFile — they are expanded by the "Extract archive" action, never processed here.
    List<AiOcrUpload> queued =
        uploadRepo
            .all()
            .filter("self.statusSelect = :queued AND self.originalFile IS NOT NULL")
            .bind("queued", AiOcrUploadStatus.QUEUED)
            .order("id")
            .fetch(BATCH, 0);

    runBatch(queued, intake);
  }

  /**
   * Process each queued job. When {@link BgOcrIntakeService#process(Long)} throws, its transaction
   * has rolled back (reverting the claim), so the terminal FAILED status is recorded here via
   * {@link BgOcrIntakeService#markFailed(Long, String)} — a fresh transaction. Without this the job
   * would revert to QUEUED and the poller would retry it forever. One bad job never stops the
   * batch.
   *
   * <p>Package-visible and taking the service as a parameter so it can be unit-tested without the
   * Guice/JPA runtime.
   */
  protected void runBatch(List<AiOcrUpload> queued, BgOcrIntakeService intake) {
    for (AiOcrUpload upload : queued) {
      Long id = upload.getId();
      try {
        intake.process(id);
      } catch (Exception e) {
        trace(e);
        try {
          intake.markFailed(id, rootMessage(e));
        } catch (Exception marking) {
          trace(marking);
        }
      }
    }
  }

  /** Overridable logging seam (kept out of {@link #runBatch} so tests need no static mocking). */
  protected void trace(Throwable t) {
    TraceBackService.trace(t);
  }

  /** Deepest non-blank message in the cause chain — the actionable reason for the failure. */
  protected static String rootMessage(Throwable t) {
    String message = null;
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c.getMessage() != null && !c.getMessage().trim().isEmpty()) {
        message = c.getMessage().trim();
      }
    }
    return message != null ? message : t.toString();
  }
}

package com.axelor.apps.aiocr.service.storage;

import com.axelor.apps.aiocr.service.OcrUploadFileCleanupService;
import com.axelor.apps.aiocr.service.storage.OcrUploadStorageDiagnosticService.ScanOutcome;
import com.axelor.apps.aiocr.service.storage.OcrUploadStorageDiagnosticService.State;
import com.axelor.apps.aiocr.service.storage.OcrUploadStorageDiagnosticService.UnreferencedFile;
import com.axelor.db.JPA;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.db.repo.MetaFileRepository;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Temporary, admin-only, one-time cleanup of the orphan {@code MetaFile} rows already proven by
 * {@link OcrUploadStorageDiagnosticService} ("Storage check"). Meant to be removed once the proven
 * orphans are cleaned up.
 *
 * <h2>Trust model</h2>
 *
 * A previous "Storage check" result is never reused: {@link #cleanup()} runs a brand-new {@link
 * OcrUploadStorageDiagnosticService#computeScan()} and only ever acts on that fresh result. If the
 * fresh scan is not {@link State#COMPLETE} — capped, a relation query failed, JSON references are
 * unprovable, or the upload root could not be resolved — cleanup aborts and deletes nothing.
 *
 * <p>Every candidate is re-checked, individually, immediately before it is deleted: deletion goes
 * through {@link OcrUploadFileCleanupService#quarantineIfUnreferenced(MetaFile)}, the same
 * production guard used by the "Delete from Upload Invoices" path, which fails closed (a newly
 * referenced or unprovable file is left completely alone) and moves the blob into quarantine before
 * removing the row — a rollback restores it, nothing is ever deleted from disk inline. No SQL
 * DELETE, no repository bypass, no direct filesystem call is used here.
 *
 * <p>Each candidate runs in its own transaction ({@link JPA#withTransaction}), so one failure is
 * reported accurately without risking the safety of any other candidate.
 */
@Singleton
public class OcrOrphanFileCleanupService {

  private static final Logger LOG = LoggerFactory.getLogger(OcrOrphanFileCleanupService.class);

  /**
   * In-JVM guard against a double-click / concurrent run of this temporary maintenance action.
   * Package-visible (not private) only so the concurrency-guard unit test can drive it directly.
   */
  static final AtomicBoolean RUNNING = new AtomicBoolean(false);

  protected final OcrUploadStorageDiagnosticService diagnosticService;
  protected final MetaFileRepository metaFileRepo;
  protected final OcrUploadFileCleanupService fileCleanupService;

  @Inject
  public OcrOrphanFileCleanupService(
      OcrUploadStorageDiagnosticService diagnosticService,
      MetaFileRepository metaFileRepo,
      OcrUploadFileCleanupService fileCleanupService) {
    this.diagnosticService = diagnosticService;
    this.metaFileRepo = metaFileRepo;
    this.fileCleanupService = fileCleanupService;
  }

  /** One candidate's outcome, decided at deletion time — never assumed from the scan alone. */
  enum Outcome {
    DELETED_WITH_BLOB,
    DELETED_ROW_ONLY,
    SKIPPED,
    ALREADY_GONE
  }

  /** Concise, count/byte-only result — mirrors the shape of {@code storageCheck}'s report. */
  public static final class Result {

    private final boolean aborted;
    private final String reason;
    private final int candidateCount;
    private final int deletedCount;
    private final int blobsReclaimedCount;
    private final long bytesReclaimed;
    private final int skippedCount;
    private final int failureCount;

    private Result(
        boolean aborted,
        String reason,
        int candidateCount,
        int deletedCount,
        int blobsReclaimedCount,
        long bytesReclaimed,
        int skippedCount,
        int failureCount) {
      this.aborted = aborted;
      this.reason = reason;
      this.candidateCount = candidateCount;
      this.deletedCount = deletedCount;
      this.blobsReclaimedCount = blobsReclaimedCount;
      this.bytesReclaimed = bytesReclaimed;
      this.skippedCount = skippedCount;
      this.failureCount = failureCount;
    }

    static Result aborted(String reason) {
      return new Result(true, reason, 0, 0, 0, 0L, 0, 0);
    }

    public boolean isAborted() {
      return aborted;
    }

    public String getReason() {
      return reason;
    }

    public int getCandidateCount() {
      return candidateCount;
    }

    public int getDeletedCount() {
      return deletedCount;
    }

    public int getBlobsReclaimedCount() {
      return blobsReclaimedCount;
    }

    public long getBytesReclaimed() {
      return bytesReclaimed;
    }

    public int getSkippedCount() {
      return skippedCount;
    }

    public int getFailureCount() {
      return failureCount;
    }
  }

  /**
   * Run one cleanup pass. Rejects a concurrent call in the same JVM instead of queuing it — the
   * caller (the controller) reports the rejection back to the admin, who can simply try again.
   */
  public Result cleanup() {
    if (!RUNNING.compareAndSet(false, true)) {
      return Result.aborted(
          "a cleanup is already running in this application instance; nothing was deleted."
              + " Wait for it to finish, then try again");
    }
    try {
      return doCleanup();
    } finally {
      RUNNING.set(false);
    }
  }

  /** Overridable seam: by default runs {@link #deleteOne} in its own transaction. */
  protected Outcome runOne(UnreferencedFile candidate) {
    return JPA.withTransaction(() -> deleteOne(candidate));
  }

  private Result doCleanup() {
    ScanOutcome outcome = diagnosticService.computeScan();
    if (outcome.result.getState() != State.COMPLETE) {
      String why =
          outcome.result.getReason() != null
              ? outcome.result.getReason()
              : "the scan did not complete";
      LOG.warn("Orphan cleanup aborted: fresh scan was not complete ({}).", why);
      return Result.aborted("the fresh scan was not complete, so nothing was deleted: " + why);
    }

    List<UnreferencedFile> candidates = outcome.unreferencedFiles;
    int deleted = 0;
    int blobsReclaimed = 0;
    long bytesReclaimed = 0;
    int skipped = 0;
    int failed = 0;

    for (UnreferencedFile candidate : candidates) {
      try {
        Outcome result = runOne(candidate);
        switch (result) {
          case DELETED_WITH_BLOB:
            deleted++;
            blobsReclaimed++;
            bytesReclaimed += candidate.getSize();
            break;
          case DELETED_ROW_ONLY:
            deleted++;
            break;
          case SKIPPED:
            skipped++;
            break;
          case ALREADY_GONE:
          default:
            // Already removed by an earlier run, or another path in the meantime — idempotent,
            // not a failure and not counted as deleted here.
            break;
        }
      } catch (RuntimeException e) {
        LOG.warn(
            "Orphan cleanup: failed to remove MetaFile #{}: {}", candidate.getId(), e.getMessage());
        failed++;
      }
    }

    LOG.info(
        "OCR orphan cleanup: {} candidate(s), {} deleted ({} blob(s) reclaimed, {} byte(s)), {}"
            + " skipped, {} failure(s).",
        candidates.size(),
        deleted,
        blobsReclaimed,
        bytesReclaimed,
        skipped,
        failed);

    return new Result(
        false, null, candidates.size(), deleted, blobsReclaimed, bytesReclaimed, skipped, failed);
  }

  /**
   * One candidate, re-read and re-checked right now. {@link
   * OcrUploadFileCleanupService#quarantineIfUnreferenced} re-runs the full reference proof
   * (including the DMS/JSON caveats) before doing anything, so a candidate that became referenced —
   * or became unprovable — since the fresh scan a moment ago is left untouched, not deleted.
   */
  private Outcome deleteOne(UnreferencedFile candidate) {
    MetaFile mf = metaFileRepo.find(candidate.getId());
    if (mf == null) {
      return Outcome.ALREADY_GONE; // a second run, or a race, already reclaimed it
    }
    boolean removed = fileCleanupService.quarantineIfUnreferenced(mf);
    if (!removed) {
      return Outcome.SKIPPED;
    }
    return candidate.isBlobMissing() ? Outcome.DELETED_ROW_ONLY : Outcome.DELETED_WITH_BLOB;
  }
}

package com.axelor.apps.aiocr.service.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.storage.OcrOrphanFileCleanupService.Outcome;
import com.axelor.apps.aiocr.service.storage.OcrOrphanFileCleanupService.Result;
import com.axelor.apps.aiocr.service.storage.OcrUploadStorageDiagnosticService.ScanOutcome;
import com.axelor.apps.aiocr.service.storage.OcrUploadStorageDiagnosticService.State;
import com.axelor.apps.aiocr.service.storage.OcrUploadStorageDiagnosticService.UnreferencedFile;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Pure tests for {@link OcrOrphanFileCleanupService}'s decision logic: the diagnostic scan is
 * stubbed (via an anonymous {@link OcrUploadStorageDiagnosticService} overriding {@code
 * computeScan}) and per-candidate deletion is stubbed via the {@link
 * OcrOrphanFileCleanupService#runOne} seam, so these exercise the abort/count/skip/idempotency
 * rules without a database or filesystem — the guarded deletion path itself ({@code
 * OcrUploadFileCleanupService#quarantineIfUnreferenced}) is exercised by its own tests.
 */
class OcrOrphanFileCleanupServiceTest {

  @AfterEach
  void resetConcurrencyGuard() {
    OcrOrphanFileCleanupService.RUNNING.set(false);
  }

  private static OcrUploadStorageDiagnosticService diagnosticServiceReturning(ScanOutcome outcome) {
    return new OcrUploadStorageDiagnosticService(null) {
      @Override
      ScanOutcome computeScan() {
        return outcome;
      }
    };
  }

  private static UnreferencedFile candidate(long id, long size, boolean blobMissing) {
    return new UnreferencedFile(id, size, blobMissing);
  }

  @Test
  void incompleteScanAbortsAndDeletesNothing() {
    ScanOutcome incomplete =
        new ScanOutcome(
            OcrUploadStorageDiagnosticService.Result.failed(
                "the upload directory could not be" + " resolved"),
            List.of());
    OcrOrphanFileCleanupService service =
        new OcrOrphanFileCleanupService(diagnosticServiceReturning(incomplete), null, null);

    Result result = service.cleanup();

    assertTrue(result.isAborted());
    assertEquals(0, result.getCandidateCount());
    assertEquals(0, result.getDeletedCount());
  }

  @Test
  void mixedOutcomesAreCountedIndependently() {
    UnreferencedFile becameReferenced = candidate(1L, 100L, false);
    UnreferencedFile orphanWithBlob = candidate(2L, 200L, false);
    UnreferencedFile orphanMissingBlob = candidate(3L, 300L, true);
    UnreferencedFile alreadyGone = candidate(4L, 400L, false);
    UnreferencedFile faulty = candidate(5L, 500L, false);

    ScanOutcome complete =
        new ScanOutcome(
            resultOf(State.COMPLETE, null),
            List.of(becameReferenced, orphanWithBlob, orphanMissingBlob, alreadyGone, faulty));

    OcrOrphanFileCleanupService service =
        new OcrOrphanFileCleanupService(diagnosticServiceReturning(complete), null, null) {
          @Override
          protected Outcome runOne(UnreferencedFile c) {
            if (c == becameReferenced) {
              return Outcome.SKIPPED; // re-checked at delete time and found referenced/unprovable
            }
            if (c == orphanWithBlob) {
              return Outcome.DELETED_WITH_BLOB;
            }
            if (c == orphanMissingBlob) {
              return Outcome.DELETED_ROW_ONLY;
            }
            if (c == alreadyGone) {
              return Outcome
                  .ALREADY_GONE; // idempotent: a prior/concurrent run already reclaimed it
            }
            throw new RuntimeException("boom"); // one candidate's failure...
          }
        };

    Result result = service.cleanup();

    assertFalse(result.isAborted());
    assertEquals(5, result.getCandidateCount());
    assertEquals(2, result.getDeletedCount()); // blob-present + blob-missing orphans
    assertEquals(1, result.getBlobsReclaimedCount()); // only the blob-present one
    assertEquals(200L, result.getBytesReclaimed());
    assertEquals(1, result.getSkippedCount());
    assertEquals(1, result.getFailureCount()); // ...never blocks the rest of the batch
  }

  @Test
  void secondRunWithNoCandidatesIsANoOp() {
    ScanOutcome nothingLeft = new ScanOutcome(resultOf(State.COMPLETE, null), List.of());
    OcrOrphanFileCleanupService service =
        new OcrOrphanFileCleanupService(diagnosticServiceReturning(nothingLeft), null, null);

    Result result = service.cleanup();

    assertFalse(result.isAborted());
    assertEquals(0, result.getCandidateCount());
    assertEquals(0, result.getDeletedCount());
  }

  @Test
  void concurrentRunIsRejected() {
    OcrOrphanFileCleanupService.RUNNING.set(true);
    ScanOutcome complete = new ScanOutcome(resultOf(State.COMPLETE, null), List.of());
    OcrOrphanFileCleanupService service =
        new OcrOrphanFileCleanupService(diagnosticServiceReturning(complete), null, null);

    Result result = service.cleanup();

    assertTrue(result.isAborted());
    assertEquals(0, result.getCandidateCount());
  }

  /** Builds a minimal {@code Result} with only state/reason set — the fields this service reads. */
  private static OcrUploadStorageDiagnosticService.Result resultOf(State state, String reason) {
    return new OcrUploadStorageDiagnosticService.Result(
        state, reason, 0L, 0L, 0L, 0L, true, 0L, 0L, "n/a", 0L, 0L, 0L, 0L, 0L, 0L);
  }
}

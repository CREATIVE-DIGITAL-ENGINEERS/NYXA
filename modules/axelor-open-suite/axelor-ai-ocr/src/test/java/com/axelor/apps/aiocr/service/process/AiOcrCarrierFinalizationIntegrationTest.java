package com.axelor.apps.aiocr.service.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * DB-backed coverage for a ZIP carrier that must not get stuck.
 *
 * <p>The rollup only ever runs as a reaction to a child finishing. When nested PDF carriers settle
 * within milliseconds of each other, every racing rollup of the ZIP above them can read at least
 * one sibling as still non-terminal, and so each writes {@code processing} — after which no event
 * is left to correct it. The ZIP would stay at {@code processing} indefinitely, and nothing could
 * reach it: {@code resetStaleProcessing} and the poll job both filter on {@code originalFile IS NOT
 * NULL}, while an archive carrier keeps its ZIP in {@code archiveFile} and has a NULL {@code
 * originalFile}.
 *
 * <p>These tests pin both halves: the rollup settles the whole tree when the children are terminal,
 * and a carrier that was already left behind is reconciled WITHOUT reprocessing it.
 */
class AiOcrCarrierFinalizationIntegrationTest extends AiOcrTest {

  private AiOcrCarrierService svc() {
    return Beans.get(AiOcrCarrierService.class);
  }

  private AiOcrProcessingExecutor executor() {
    return Beans.get(AiOcrProcessingExecutor.class);
  }

  private AiOcrUploadRepository uploads() {
    return Beans.get(AiOcrUploadRepository.class);
  }

  /** A carrier whose extraction has finished (children already created). */
  private AiOcrUpload carrier(AiOcrUpload parent, String carrierKind) {
    AiOcrUpload c = new AiOcrUpload();
    c.setImportArchive(parent);
    c.setCarrierKind(carrierKind);
    c.setStatusSelect(AiOcrUploadStatus.EXTRACTING);
    c.setExtractionFinishedOn(LocalDateTime.now());
    return JPA.save(c);
  }

  private AiOcrUpload child(AiOcrUpload parent, String status) {
    AiOcrUpload c = new AiOcrUpload();
    c.setImportArchive(parent);
    c.setStatusSelect(status);
    return JPA.save(c);
  }

  private String statusOf(Long id) {
    JPA.em().clear(); // recompute persists via bulk UPDATE; drop the stale L1 copy
    return uploads().find(id).getStatusSelect();
  }

  // ── the tree settles when everything below it is terminal ───────────────────

  @Test
  void aZipOfOrdinarySingleInvoicePdfsFinalizesWhenEveryChildIsTerminal() {
    inTransaction(
        () -> {
          AiOcrUpload zip = carrier(null, null);
          child(zip, AiOcrUploadStatus.COMPLETED);
          child(zip, AiOcrUploadStatus.COMPLETED);
          child(zip, AiOcrUploadStatus.COMPLETED);

          svc().recompute(zip.getId());

          assertEquals(AiOcrUploadStatus.ARCHIVE, statusOf(zip.getId()));
          assertEquals(Integer.valueOf(100), uploads().find(zip.getId()).getProgressPercent());
        });
  }

  /** The exact #96 shape: ZIP → three pdfSplit carriers → their invoice children. */
  @Test
  void aZipOfNestedMultiInvoiceCarriersFinalizesOnceEveryNestedCarrierSettles() {
    inTransaction(
        () -> {
          AiOcrUpload zip = carrier(null, null);
          List<Long> nestedIds = new ArrayList<>();
          for (int i = 0; i < 3; i++) {
            AiOcrUpload nested = carrier(zip, AiOcrUploadStatus.CARRIER_KIND_PDF_SPLIT);
            child(nested, AiOcrUploadStatus.COMPLETED);
            child(nested, AiOcrUploadStatus.COMPLETED);
            nestedIds.add(nested.getId());
          }

          // Each nested carrier settles in turn, exactly as the split service drives it.
          for (Long nestedId : nestedIds) {
            svc().recompute(nestedId);
          }

          for (Long nestedId : nestedIds) {
            assertEquals(AiOcrUploadStatus.ARCHIVE, statusOf(nestedId), "nested carrier settles");
          }
          assertEquals(
              AiOcrUploadStatus.ARCHIVE,
              statusOf(zip.getId()),
              "the ZIP must finalize once all three nested carriers are ARCHIVE");
        });
  }

  // ── recovery of an already-stuck carrier ────────────────────────────────────

  /**
   * The #96 repro: every descendant is terminal, but the carrier was left at {@code processing}/66
   * by the race. Reconciliation must finalize it and must not touch a single child.
   */
  @Test
  void aCarrierLeftProcessingIsReconciledWithoutReprocessingAnyChild() {
    inTransaction(
        () -> {
          AiOcrUpload zip = carrier(null, null);
          List<Long> childIds = new ArrayList<>();
          for (int i = 0; i < 3; i++) {
            AiOcrUpload nested = carrier(zip, AiOcrUploadStatus.CARRIER_KIND_PDF_SPLIT);
            nested.setStatusSelect(AiOcrUploadStatus.ARCHIVE);
            JPA.save(nested);
            childIds.add(nested.getId());
            childIds.add(child(nested, AiOcrUploadStatus.COMPLETED).getId());
          }
          // Freeze the carrier in the state the race left it in.
          zip.setStatusSelect(AiOcrUploadStatus.PROCESSING);
          zip.setProgressPercent(66);
          JPA.save(zip);
          // Read the PERSISTED stamp, not the in-memory one: HSQLDB stores microseconds, so the
          // reloaded value legitimately differs from the nanosecond value we constructed.
          JPA.em().clear();
          LocalDateTime extractedAt = uploads().find(zip.getId()).getExtractionFinishedOn();

          int reconciled = executor().reconcileUnfinalizedCarriers();

          assertTrue(reconciled >= 1, "the stuck carrier must be picked up");
          assertEquals(
              AiOcrUploadStatus.ARCHIVE,
              statusOf(zip.getId()),
              "a carrier whose descendants are all terminal must finalize on reconciliation");
          assertEquals(
              extractedAt,
              uploads().find(zip.getId()).getExtractionFinishedOn(),
              "reconciliation must not re-extract: the extraction stamp is untouched");
          for (Long id : childIds) {
            String status = uploads().find(id).getStatusSelect();
            assertTrue(
                AiOcrUploadStatus.ARCHIVE.equals(status)
                    || AiOcrUploadStatus.COMPLETED.equals(status),
                "no child may be reopened or reprocessed by reconciliation (id " + id + ")");
          }
        });
  }

  @Test
  void reconciliationIsIdempotentAndLeavesGenuinelyUnfinishedCarriersAlone() {
    inTransaction(
        () -> {
          AiOcrUpload done = carrier(null, null);
          child(done, AiOcrUploadStatus.COMPLETED);
          done.setStatusSelect(AiOcrUploadStatus.PROCESSING);
          JPA.save(done);

          AiOcrUpload busy = carrier(null, null);
          child(busy, AiOcrUploadStatus.COMPLETED);
          child(busy, AiOcrUploadStatus.QUEUED); // still genuinely working
          busy.setStatusSelect(AiOcrUploadStatus.PROCESSING);
          JPA.save(busy);

          executor().reconcileUnfinalizedCarriers();
          assertEquals(AiOcrUploadStatus.ARCHIVE, statusOf(done.getId()));
          assertEquals(
              AiOcrUploadStatus.PROCESSING,
              statusOf(busy.getId()),
              "a carrier with an unfinished child must NOT be force-finalized");

          // Running it again changes nothing — finalization is idempotent.
          executor().reconcileUnfinalizedCarriers();
          assertEquals(AiOcrUploadStatus.ARCHIVE, statusOf(done.getId()));
          assertEquals(AiOcrUploadStatus.PROCESSING, statusOf(busy.getId()));

          // And recomputing an already-finalized carrier keeps it finalized.
          svc().recompute(done.getId());
          assertEquals(AiOcrUploadStatus.ARCHIVE, statusOf(done.getId()));
        });
  }

  @Test
  void reconciliationPreservesNestedNeedsReviewAsCompletedWithIssues() {
    inTransaction(
        () -> {
          AiOcrUpload zip = carrier(null, null);
          AiOcrUpload nested = carrier(zip, AiOcrUploadStatus.CARRIER_KIND_PDF_SPLIT);
          nested.setStatusSelect(AiOcrUploadStatus.ARCHIVE);
          JPA.save(nested);
          child(nested, AiOcrUploadStatus.COMPLETED);
          child(nested, AiOcrUploadStatus.NEEDS_REVIEW);
          zip.setStatusSelect(AiOcrUploadStatus.PROCESSING);
          JPA.save(zip);

          executor().reconcileUnfinalizedCarriers();

          assertEquals(
              AiOcrUploadStatus.COMPLETED_WITH_ISSUES,
              statusOf(zip.getId()),
              "an issue buried in a nested carrier must survive reconciliation, not collapse to"
                  + " a clean archive");
        });
  }

  // ── the rollup takes a row lock at every level it touches ───────────────────

  /**
   * The race is only fixable if the rollup serializes on the carrier row it is about to write. The
   * parent recursion goes through the Guice singleton rather than this probe, so only the directly
   * recomputed carrier is observable here — that is the row three siblings were fighting over at
   * each level.
   */
  @Test
  void theRollupTakesARowLockOnTheCarrierItWrites() {
    inTransaction(
        () -> {
          AiOcrUpload zip = carrier(null, null);
          AiOcrUpload nested = carrier(zip, AiOcrUploadStatus.CARRIER_KIND_PDF_SPLIT);
          child(nested, AiOcrUploadStatus.COMPLETED);

          List<Long> locked = new ArrayList<>();
          AiOcrCarrierService probe =
              new AiOcrCarrierService(uploads()) {
                @Override
                protected AiOcrUpload lockForRollup(Long carrierId) {
                  locked.add(carrierId);
                  return super.lockForRollup(carrierId);
                }
              };
          probe.recompute(nested.getId());

          assertTrue(locked.contains(nested.getId()), "the carrier being written is locked");
          assertEquals(
              AiOcrUploadStatus.ARCHIVE,
              statusOf(nested.getId()),
              "locking must not change the computed outcome");
        });
  }

  /**
   * A lock that cannot be taken must FAIL the rollup, never silently fall back to an unlocked read
   * — that fallback would compute the status from the same half-committed picture of the siblings
   * that stranded #96 in the first place. The carrier is left non-terminal for reconciliation.
   */
  @Test
  void aLockThatNeverSucceedsFailsTheRollupInsteadOfReadingUnlocked() {
    inTransaction(
        () -> {
          AiOcrUpload zip = carrier(null, null);
          child(zip, AiOcrUploadStatus.COMPLETED);
          zip.setStatusSelect(AiOcrUploadStatus.PROCESSING);
          JPA.save(zip);

          int[] attempts = {0};
          AiOcrCarrierService alwaysTimingOut =
              new AiOcrCarrierService(uploads()) {
                @Override
                protected AiOcrUpload findLocked(Long carrierId) {
                  attempts[0]++;
                  throw new javax.persistence.LockTimeoutException("simulated lock timeout");
                }

                @Override
                protected void pauseBeforeLockRetry(int attempt) {
                  // never sleep in a test
                }
              };

          assertThrows(
              IllegalStateException.class,
              () -> alwaysTimingOut.recompute(zip.getId()),
              "a failed lock must fail the rollup, not degrade to an unlocked read");
          assertEquals(
              AiOcrCarrierService.LOCK_ATTEMPTS,
              attempts[0],
              "the lock is retried a bounded number of times before giving up");
          assertEquals(
              AiOcrUploadStatus.PROCESSING,
              statusOf(zip.getId()),
              "nothing may be written from an unlocked read; the carrier stays for reconciliation");

          // ...and that carrier is exactly what startup reconciliation then picks up and settles.
          assertTrue(executor().findUnfinalizedCarrierIds().contains(zip.getId()));
          executor().reconcileUnfinalizedCarriers();
          assertEquals(AiOcrUploadStatus.ARCHIVE, statusOf(zip.getId()));
        });
  }

  /** The bounded retry: a timeout that clears on a later attempt still completes the rollup. */
  @Test
  void aLockTimeoutIsRetriedAndSucceedsOnALaterAttempt() {
    inTransaction(
        () -> {
          AiOcrUpload zip = carrier(null, null);
          child(zip, AiOcrUploadStatus.COMPLETED);

          int[] calls = {0};
          AiOcrCarrierService flaky =
              new AiOcrCarrierService(uploads()) {
                @Override
                protected AiOcrUpload findLocked(Long carrierId) {
                  if (++calls[0] < AiOcrCarrierService.LOCK_ATTEMPTS) {
                    throw new javax.persistence.LockTimeoutException("busy");
                  }
                  return super.findLocked(carrierId);
                }

                @Override
                protected void pauseBeforeLockRetry(int attempt) {
                  // never sleep in a test
                }
              };
          flaky.recompute(zip.getId());

          assertEquals(
              AiOcrCarrierService.LOCK_ATTEMPTS,
              calls[0],
              "it kept retrying until the lock was free");
          assertEquals(
              AiOcrUploadStatus.ARCHIVE,
              statusOf(zip.getId()),
              "a transient lock timeout must not lose the rollup");
        });
  }
}

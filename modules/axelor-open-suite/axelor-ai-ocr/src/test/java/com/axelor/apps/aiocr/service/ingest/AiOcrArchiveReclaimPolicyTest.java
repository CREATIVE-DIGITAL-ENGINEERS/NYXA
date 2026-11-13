package com.axelor.apps.aiocr.service.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ingest.AiOcrArchiveReclaimPolicy.Decision;
import com.axelor.apps.aiocr.service.ingest.AiOcrArchiveReclaimPolicy.Tally;
import org.junit.jupiter.api.Test;

/**
 * The reclaim decision is the whole safety argument for dropping the original ZIP, so it is pure
 * and tested directly: no Guice, no JPA, no filesystem.
 */
class AiOcrArchiveReclaimPolicyTest {

  /** Three supported documents in, three children each holding their own blob. */
  private static Tally cleanPass(int documents) {
    Tally t = new Tally();
    for (int i = 0; i < documents; i++) {
      t.recordDocument();
    }
    return t;
  }

  @Test
  void fullySuccessfulExtractionReclaimsTheArchive() {
    Decision d = AiOcrArchiveReclaimPolicy.decide(true, cleanPass(3), 3, 3, null);
    assertTrue(d.isReclaim());
    assertNull(d.getReason(), "a reclaim carries no retention reason");
  }

  @Test
  void anUnsupportedEntryRetainsTheArchive() {
    // .xlsx inside the ZIP: onUnsupported creates no record and stores no bytes, so the archive is
    // the ONLY copy of it. This is the case that must never reclaim.
    Tally t = cleanPass(2);
    t.recordUnsupported("folder/report.xlsx");

    Decision d = AiOcrArchiveReclaimPolicy.decide(true, t, 2, 2, null);
    assertFalse(d.isReclaim());
    assertTrue(d.getReason().contains("report.xlsx"), d.getReason());
  }

  @Test
  void aCorruptNestedArchiveRetainsTheArchive() {
    Tally t = cleanPass(1);
    t.recordCorruptNested("inner/broken.zip");

    Decision d = AiOcrArchiveReclaimPolicy.decide(true, t, 2, 2, null);
    assertFalse(d.isReclaim());
    assertTrue(d.getReason().contains("broken.zip"), d.getReason());
  }

  @Test
  void aControlledStopRetainsTheArchive() {
    // A tripped ZIP guard / unsafe path / IO failure: entries past the stop were never enumerated,
    // so nothing can be proven about them even though what WAS read looks complete.
    Tally t = cleanPass(2);
    t.recordStop("Archive entry count exceeds the limit of 5.");

    Decision d = AiOcrArchiveReclaimPolicy.decide(true, t, 2, 2, null);
    assertFalse(d.isReclaim());
    assertTrue(d.getReason().contains("did not run to completion"), d.getReason());
  }

  @Test
  void aDocumentThatNeverReachedTheDatabaseRetainsTheArchive() {
    Decision d = AiOcrArchiveReclaimPolicy.decide(true, cleanPass(5), 4, 4, null);
    assertFalse(d.isReclaim());
    assertTrue(d.getReason().contains("4 of 5"), d.getReason());
  }

  @Test
  void aDuplicateChildHoldingNoFileRetainsTheArchive() {
    // A duplicate child is a row without an originalFile - it stores no bytes of its own, so the
    // archive is still the only copy of that entry as far as this carrier is concerned.
    String failure =
        AiOcrArchiveReclaimPolicy.childStorageFailure("b.pdf", true, false, false, false, 0L);
    assertNotNull(failure);

    Decision d = AiOcrArchiveReclaimPolicy.decide(true, cleanPass(3), 3, 2, failure);
    assertFalse(d.isReclaim());
    assertTrue(d.getReason().contains("could not be confirmed as durably stored"), d.getReason());
    assertTrue(d.getReason().contains("b.pdf"), d.getReason());
  }

  @Test
  void aChildWithAMetaFileRecordButNoBlobOnDiskRetainsTheArchive() {
    // THE case a row-existence check cannot catch: the MetaFile row is there, the storage layer
    // resolves a location for it, but the physical file is gone. Reclaiming the ZIP here would
    // destroy the only remaining copy of that document.
    String failure =
        AiOcrArchiveReclaimPolicy.childStorageFailure(
            "invoice-7.pdf",
            /* childPresent */ true,
            /* metaFilePresent */ true,
            /* pathResolved */ true,
            /* regularFileExists */ false,
            /* sizeBytes */ 0L);
    assertNotNull(failure);
    assertTrue(failure.contains("missing from storage"), failure);
    assertTrue(failure.contains("invoice-7.pdf"), failure);

    Decision d = AiOcrArchiveReclaimPolicy.decide(true, cleanPass(3), 3, 2, failure);
    assertFalse(d.isReclaim());
    assertTrue(d.getReason().contains("missing from storage"), d.getReason());

    // A present row with an EMPTY blob is not a stored document either.
    String empty =
        AiOcrArchiveReclaimPolicy.childStorageFailure("invoice-8.pdf", true, true, true, true, 0L);
    assertNotNull(empty);
    assertTrue(empty.contains("is empty"), empty);

    // And the happy path stays clean: row + MetaFile + resolved + regular + non-empty.
    assertNull(
        AiOcrArchiveReclaimPolicy.childStorageFailure("invoice-9.pdf", true, true, true, true, 1L));
  }

  @Test
  void aVerificationProblemBlocksEvenWhenTheCountsLookComplete() {
    // An unreadable share can leave the counts intact while proving nothing.
    Decision d =
        AiOcrArchiveReclaimPolicy.decide(
            true, cleanPass(2), 2, 2, "the stored documents could not be verified (share offline)");
    assertFalse(d.isReclaim());
    assertTrue(d.getReason().contains("storage could not be verified"), d.getReason());
  }

  @Test
  void anArchiveThatProducedNothingIsKept() {
    Decision d = AiOcrArchiveReclaimPolicy.decide(true, cleanPass(0), 0, 0, null);
    assertFalse(d.isReclaim());
    assertTrue(d.getReason().contains("no document"), d.getReason());
  }

  @Test
  void repeatedCompletionIsSafe() {
    // Second pass over an already-reclaimed carrier: a no-op with a reason, never an error and
    // never a second deletion attempt.
    Decision again = AiOcrArchiveReclaimPolicy.decide(false, cleanPass(3), 3, 3, null);
    assertFalse(again.isReclaim());
    assertTrue(again.getReason().contains("already reclaimed"), again.getReason());

    // And the decision is pure: re-deciding on unchanged state gives the identical answer, so a
    // retry of a still-complete carrier cannot flip-flop.
    Tally t = cleanPass(3);
    assertTrue(AiOcrArchiveReclaimPolicy.decide(true, t, 3, 3, null).isReclaim());
    assertTrue(AiOcrArchiveReclaimPolicy.decide(true, t, 3, 3, null).isReclaim());
  }

  @Test
  void anUnprovableOutcomeRetainsTheArchive() {
    Decision d = AiOcrArchiveReclaimPolicy.decide(true, null, 3, 3, null);
    assertFalse(d.isReclaim());
    assertNotNull(d.getReason());
  }

  @Test
  void tallyKeepsTheFirstOffendingPathAndCounts() {
    Tally t = new Tally();
    t.recordUnsupported("a.xlsx");
    t.recordUnsupported("b.docx");
    t.recordCorruptNested("one.zip");
    t.recordStop("first");
    t.recordStop("second");

    assertEquals(2, t.getUnsupportedEntries());
    assertEquals("a.xlsx", t.getFirstUnsupportedPath());
    assertEquals(1, t.getCorruptNestedEntries());
    assertEquals("one.zip", t.getFirstCorruptPath());
    assertEquals("first", t.getStopReason(), "the first stop is the one that ended the pass");
  }
}

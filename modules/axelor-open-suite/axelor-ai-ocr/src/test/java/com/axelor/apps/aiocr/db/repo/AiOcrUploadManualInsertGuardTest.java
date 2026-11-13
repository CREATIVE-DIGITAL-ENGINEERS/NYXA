package com.axelor.apps.aiocr.db.repo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure tests for the two decisions {@code AiOcrUploadManagementRepository.save} makes. The context
 * facts (is there a caller, is a system scope open, what state is the row in) are passed as plain
 * booleans precisely so this needs no Guice, no Shiro and no JPA.
 */
class AiOcrUploadManualInsertGuardTest {

  /** id == null: a real insert. Nothing on the row is trusted, so the flags cannot change it. */
  private static boolean insert(boolean hasCaller, boolean system) {
    return AiOcrUploadManagementRepository.isManualUpload(
        null, hasCaller, system, true, true, true);
  }

  /** An already-persisted row — which is what the web layer always hands the repository. */
  private static boolean persisted(
      boolean hasCaller, boolean system, boolean markers, boolean started, boolean owned) {
    return AiOcrUploadManagementRepository.isManualUpload(
        7L, hasCaller, system, markers, started, owned);
  }

  @Test
  void anInsertByAnAuthenticatedCallerOutsideASystemContextIsManual() {
    assertTrue(insert(true, false));

    // No caller at all — the background worker pools.
    assertFalse(insert(false, false));
  }

  @Test
  void aSystemContextMakesASaveNonManualRegardlessOfTheRowsFields() {
    // This is the invariant AiOcrCarrierService.recompute depends on: no child is ever 'uploaded'
    // (that status is in neither its terminal nor its problematic list, so it would stall the
    // carrier at 'processing' forever). The pipeline creation sites therefore declare their trust
    // with AiExecutionContext.runAsSystem — the classification does NOT come from any field.
    assertFalse(insert(true, true));
    assertFalse(persisted(true, true, false, false, true));
  }

  @Test
  void clientPostedCarrierMarkersDoNotMakeAnInsertSystemCreated() {
    // importArchive / extractionKey / sourcePath — and processedOn — are ordinary writable columns,
    // so a crafted form POST could set them. On an INSERT the predicate takes no field at all, so
    // such a row is still manual and still gets the authoritative company + status (and has those
    // markers cleared).
    assertTrue(insert(true, false));
  }

  @Test
  void aRecordCreatedThroughTheWebLayerIsStillRepairedAlthoughItAlreadyHasAnId() {
    // Resource.save persists (and flushes) via JPA.manage before calling the repository, so the id
    // is already assigned. This is the case the old `id == null` guard missed entirely, leaving
    // company-null rows the grid hides and the dispatcher drains.
    assertTrue(persisted(true, false, false, false, true));
  }

  @Test
  void aPersistedRowIsLeftAloneOnceItBelongsToSomeoneElseOrHasRun() {
    // Another company's or another user's record: repairing it would be adoption.
    assertFalse(persisted(true, false, false, false, false));
    // Started or finished: it keeps its status and its results.
    assertFalse(persisted(true, false, false, true, true));
    // A pipeline child (importArchive / extractionKey / sourcePath / carrierKind / segmentType).
    assertFalse(persisted(true, false, true, false, true));
  }

  @Test
  void aRowMayNotHoldBothADocumentAndAnArchive() {
    assertTrue(AiOcrUploadManagementRepository.holdsTwoFiles(true, true));

    // The legitimate shapes: a ZIP carrier (archive only) and a document / pdfSplit carrier
    // (document only).
    assertFalse(AiOcrUploadManagementRepository.holdsTwoFiles(true, false));
    assertFalse(AiOcrUploadManagementRepository.holdsTwoFiles(false, true));
    assertFalse(AiOcrUploadManagementRepository.holdsTwoFiles(false, false));
  }
}

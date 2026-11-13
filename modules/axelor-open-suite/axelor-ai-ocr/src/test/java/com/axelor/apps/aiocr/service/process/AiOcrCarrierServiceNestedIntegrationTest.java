package com.axelor.apps.aiocr.service.process;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * DB-backed proof of the nested-carrier rollup: ZIP carrier -> pdfSplit carrier -> split children.
 * A nested carrier's own issues must still surface on its ZIP parent instead of being silently
 * collapsed by the single ARCHIVE status both levels would otherwise share, and the ZIP parent must
 * reach a terminal state once the nested carrier settles instead of staying stuck.
 */
class AiOcrCarrierServiceNestedIntegrationTest extends AiOcrTest {

  private AiOcrCarrierService svc() {
    return Beans.get(AiOcrCarrierService.class);
  }

  private AiOcrUploadRepository uploads() {
    return Beans.get(AiOcrUploadRepository.class);
  }

  /** A carrier row (ZIP when {@code parent} is null and {@code carrierKind} is null). */
  private AiOcrUpload carrier(AiOcrUpload parent, String carrierKind) {
    AiOcrUpload c = new AiOcrUpload();
    c.setImportArchive(parent);
    c.setCarrierKind(carrierKind);
    c.setStatusSelect(AiOcrUploadStatus.EXTRACTING);
    // Extraction (creating the child rows) is done in every scenario here; whether the carrier's
    // rollup is itself terminal still depends on whether ITS children are.
    c.setExtractionFinishedOn(LocalDateTime.now());
    return JPA.save(c);
  }

  private void child(AiOcrUpload parent, String status) {
    AiOcrUpload c = new AiOcrUpload();
    c.setImportArchive(parent);
    c.setStatusSelect(status);
    JPA.save(c);
  }

  @Test
  void nestedCarrierAllCleanChildrenFinishesZipCleanly() {
    inTransaction(
        () -> {
          AiOcrUpload zip = carrier(null, null);
          AiOcrUpload nested = carrier(zip, AiOcrUploadStatus.CARRIER_KIND_PDF_SPLIT);
          child(nested, AiOcrUploadStatus.COMPLETED);
          child(nested, AiOcrUploadStatus.COMPLETED);

          svc().recompute(nested.getId());
          // recompute() persists via bulk JPQL UPDATEs, which do not refresh already-loaded
          // entities in this session's L1 cache — clear it so find() below re-reads from the DB.
          JPA.em().clear();

          assertEquals(
              AiOcrUploadStatus.ARCHIVE,
              uploads().find(nested.getId()).getStatusSelect(),
              "nested carrier itself is clean");
          assertEquals(
              AiOcrUploadStatus.ARCHIVE,
              uploads().find(zip.getId()).getStatusSelect(),
              "a clean nested carrier must not stop the ZIP from finishing cleanly");
        });
  }

  @Test
  void nestedCarrierWithNeedsReviewChildFlagsZipWithIssues() {
    inTransaction(
        () -> {
          AiOcrUpload zip = carrier(null, null);
          AiOcrUpload nested = carrier(zip, AiOcrUploadStatus.CARRIER_KIND_PDF_SPLIT);
          child(nested, AiOcrUploadStatus.COMPLETED);
          child(nested, AiOcrUploadStatus.COMPLETED);
          child(nested, AiOcrUploadStatus.NEEDS_REVIEW);

          svc().recompute(nested.getId());
          JPA.em().clear();

          // The nested carrier's OWN status is unaffected: NEEDS_REVIEW is not "problematic" at
          // its own flat level (its invoice/move already exists and is independently actionable).
          assertEquals(
              AiOcrUploadStatus.ARCHIVE,
              uploads().find(nested.getId()).getStatusSelect(),
              "flat carrier semantics are unchanged: needsReview alone is not problematic here");
          // But that needsReview child is now hidden one level deeper from the ZIP's own child
          // list — it must still surface as an issue there, not a silent clean "archive".
          assertEquals(
              AiOcrUploadStatus.COMPLETED_WITH_ISSUES,
              uploads().find(zip.getId()).getStatusSelect(),
              "an issue buried inside a nested carrier must propagate to the ZIP parent");
        });
  }

  @Test
  void nestedCarrierCountsAsTerminalAndNeverLeavesZipStuckProcessing() {
    inTransaction(
        () -> {
          AiOcrUpload zip = carrier(null, null);
          AiOcrUpload nested = carrier(zip, AiOcrUploadStatus.CARRIER_KIND_PDF_SPLIT);
          child(nested, AiOcrUploadStatus.COMPLETED);

          // Before the nested carrier's own children finish, the ZIP must not be terminal yet.
          svc().recompute(zip.getId());
          JPA.em().clear();
          assertEquals(
              AiOcrUploadStatus.PROCESSING,
              uploads().find(zip.getId()).getStatusSelect(),
              "zip extraction is done but its nested carrier hasn't settled yet");

          // Once the nested carrier's own children finish, recomputing IT must propagate upward
          // and settle the ZIP too — never leaving it stuck at PROCESSING/EXTRACTING.
          svc().recompute(nested.getId());
          JPA.em().clear();
          assertEquals(
              AiOcrUploadStatus.ARCHIVE,
              uploads().find(zip.getId()).getStatusSelect(),
              "a settled nested carrier (ARCHIVE) must count as terminal for its ZIP parent");
        });
  }
}

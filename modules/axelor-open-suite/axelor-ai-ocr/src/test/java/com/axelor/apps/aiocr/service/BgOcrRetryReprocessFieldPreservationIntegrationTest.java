package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.TaxRepository;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.aiocr.service.ocr.OcrExtractorRouter;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import javax.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * DB-backed proof that {@link BgOcrIntakeService#retry} and {@link BgOcrIntakeService#reprocess}
 * clear ONLY attempt/result state and never touch structural or reusable identity: the uploaded
 * file(s), the carrier/segment relationships, the extraction idempotency key, the cached OCR
 * payloads, or company/user ownership. A retry that lost any of these would either point at the
 * wrong file, duplicate a child row, or re-call Azure for something already cached.
 */
class BgOcrRetryReprocessFieldPreservationIntegrationTest extends AiOcrTest {

  /**
   * {@code retry}/{@code reprocess} start with an {@code isInternalUser()} gate backed by {@link
   * com.axelor.auth.AuthUtils#getUser()}, which is null in this DB-only test harness (no Shiro
   * subject/session). Overriding the gate is the only thing this subclass changes — every field
   * cleared or preserved is still the real production method body.
   */
  static class TestableIntakeService extends BgOcrIntakeService {
    @Inject
    TestableIntakeService(
        OcrExtractorRouter extractor,
        AiOcrUploadRepository uploadRepo,
        InvoiceRepository invoiceRepo,
        PartnerRepository partnerRepo,
        TaxRepository taxRepo) {
      super(extractor, uploadRepo, invoiceRepo, partnerRepo, taxRepo);
    }

    @Override
    protected boolean isInternalUser() {
      return true;
    }
  }

  private BgOcrIntakeService svc() {
    return Beans.get(TestableIntakeService.class);
  }

  private AiOcrUploadRepository uploads() {
    return Beans.get(AiOcrUploadRepository.class);
  }

  private Company company(String code) {
    Company c =
        Beans.get(CompanyRepository.class)
            .all()
            .filter("self.code = :c")
            .bind("c", code)
            .fetchOne();
    if (c == null) {
      c = new Company();
      c.setName(code);
      c.setCode(code);
      c = JPA.save(c);
    }
    return c;
  }

  private User anyUser(String code) {
    User u = new User();
    u.setCode(code);
    u.setName(code);
    return JPA.save(u);
  }

  /** A MetaFile row good enough to prove FK identity is preserved — its content is never read. */
  private MetaFile metaFile(String name) {
    MetaFile mf = new MetaFile();
    mf.setFileName(name);
    mf.setFilePath("test/" + name);
    return JPA.save(mf);
  }

  private Invoice invoice(Company c) {
    Invoice inv = new Invoice();
    inv.setStatusSelect(InvoiceRepository.STATUS_DRAFT);
    inv.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    inv.setCompany(c);
    inv.setInvoiceId("FP-" + System.nanoTime());
    inv.setInvoiceLineList(new java.util.ArrayList<>());
    return JPA.save(inv);
  }

  /**
   * Every structural/attempt field this test cares about, set to a distinctive, checkable value.
   */
  private AiOcrUpload fullyPopulatedUpload(
      Company c, User u, AiOcrUpload archiveCarrier, String status) {
    AiOcrUpload up = new AiOcrUpload();
    up.setCompany(c);
    up.setUploadedBy(u);
    up.setStatusSelect(status);
    // Structural / reusable — must survive retry() and reprocess().
    up.setOriginalFile(metaFile("original.pdf"));
    up.setArchiveFile(metaFile("archive.zip"));
    up.setSourcePath("carrier/entry-1.pdf");
    up.setExtractionKey("EK-" + System.nanoTime());
    up.setImportArchive(archiveCarrier);
    up.setSegmentType(AiOcrUploadStatus.SEGMENT_TYPE_INVOICE);
    up.setCarrierKind(null); // a leaf, not a carrier itself
    up.setExtractionAttemptId("ATTEMPT-1");
    up.setCachedOcrJson("{\"cached\":true}");
    up.setSplitSourceJson("{\"splitSource\":true}");
    // Attempt/result state — must be CLEARED by retry()/reprocess().
    up.setExtractionErrors("Azure poll kept returning HTTP 429.");
    up.setExtractionInformation("AI Accountant:\n- still unresolved: totalBase");
    up.setRawExtractedJson("{\"raw\":true}");
    up.setBlobUrl("https://example/blob");
    up.setConfidence(new BigDecimal("0.42"));
    up.setEngineUsed("azure-prebuilt-invoice");
    up.setExtractionStartedOn(LocalDateTime.now().minusMinutes(10));
    up.setExtractionFinishedOn(LocalDateTime.now().minusMinutes(9));
    up.setProcessedOn(LocalDateTime.now().minusMinutes(9));
    up.setProgressPercent(40);
    up.setRetryCount(2);
    return JPA.save(up);
  }

  private void assertStructuralFieldsPreserved(AiOcrUpload before, AiOcrUpload after) {
    assertEquals(before.getOriginalFile().getId(), after.getOriginalFile().getId(), "originalFile");
    assertEquals(before.getArchiveFile().getId(), after.getArchiveFile().getId(), "archiveFile");
    assertEquals(before.getSourcePath(), after.getSourcePath(), "sourcePath");
    assertEquals(before.getExtractionKey(), after.getExtractionKey(), "extractionKey");
    assertEquals(
        before.getImportArchive().getId(), after.getImportArchive().getId(), "importArchive");
    assertEquals(before.getSegmentType(), after.getSegmentType(), "segmentType");
    assertEquals(before.getCarrierKind(), after.getCarrierKind(), "carrierKind");
    assertEquals(
        before.getExtractionAttemptId(), after.getExtractionAttemptId(), "extractionAttemptId");
    assertEquals(before.getCachedOcrJson(), after.getCachedOcrJson(), "cachedOcrJson");
    assertEquals(before.getSplitSourceJson(), after.getSplitSourceJson(), "splitSourceJson");
    assertEquals(before.getCompany().getId(), after.getCompany().getId(), "company");
    assertEquals(before.getUploadedBy().getId(), after.getUploadedBy().getId(), "uploadedBy");
  }

  @Test
  void retryClearsOnlyAttemptStateAndKeepsStructuralFieldsIntact() {
    inTransaction(
        () -> {
          Company c = company("FP-RETRY");
          User u = anyUser("FP-RETRY-USER");
          AiOcrUpload archiveCarrier = new AiOcrUpload();
          archiveCarrier.setCompany(c);
          archiveCarrier = JPA.save(archiveCarrier);

          AiOcrUpload before = fullyPopulatedUpload(c, u, archiveCarrier, AiOcrUploadStatus.FAILED);
          long id = before.getId();

          svc().retry(id);

          AiOcrUpload after = uploads().find(id);
          assertStructuralFieldsPreserved(before, after);

          // Attempt/result state must be reset, not left stale.
          assertEquals(AiOcrUploadStatus.QUEUED, after.getStatusSelect());
          assertNull(after.getExtractionErrors());
          assertNull(after.getExtractionInformation());
          assertNull(after.getRawExtractedJson());
          assertNull(after.getBlobUrl());
          // Generated numeric getters never return null (Axelor convention: they substitute
          // zero) — the underlying stored value is what's actually cleared, so compare against
          // zero here rather than asserting null on these two.
          assertEquals(0, BigDecimal.ZERO.compareTo(after.getConfidence()), "confidence reset");
          assertEquals(0, after.getProgressPercent().intValue(), "progressPercent reset");
          assertNull(after.getEngineUsed());
          assertNull(after.getExtractionStartedOn());
          assertNull(after.getExtractionFinishedOn());
          assertNull(after.getProcessedOn());
          assertNull(after.getLockedOn());
          assertEquals(3, after.getRetryCount(), "retryCount is bumped, not reset");
        });
  }

  @Test
  void reprocessClearsOnlyAttemptStateAndKeepsStructuralFieldsIntact() {
    inTransaction(
        () -> {
          try {
            Company c = company("FP-REPROCESS");
            User u = anyUser("FP-REPROCESS-USER");
            AiOcrUpload archiveCarrier = new AiOcrUpload();
            archiveCarrier.setCompany(c);
            archiveCarrier = JPA.save(archiveCarrier);

            // reprocess() requires the preserved source file to still exist on disk, resolved via
            // MetaFiles.getPath(MetaFile) — which resolves filePath relative to the configured
            // upload directory (and rejects anything outside it), so the file must live there too,
            // not just anywhere on disk.
            String relName = "bg-ocr-reprocess-test-" + System.nanoTime() + ".pdf";
            Path tmp = MetaFiles.getPath(relName);
            Files.createDirectories(tmp.getParent());
            Files.write(tmp, new byte[] {1, 2, 3});
            MetaFile realSource = new MetaFile();
            realSource.setFileName(relName);
            realSource.setFilePath(relName);
            realSource = JPA.save(realSource);

            AiOcrUpload before =
                fullyPopulatedUpload(
                    c, u, archiveCarrier, AiOcrUploadStatus.DELETED_FROM_SUPPL_INVOICES);
            before.setOriginalFile(realSource);
            before.setInvoice(invoice(c));
            before = JPA.save(before);
            long id = before.getId();

            svc().reprocess(id);

            AiOcrUpload after = uploads().find(id);
            // invoice/move are the two fields reprocess() DOES clear (a deletedFrom* row
            // legitimately
            // had them and must lose them) — everything else structural must still match.
            assertEquals(before.getArchiveFile().getId(), after.getArchiveFile().getId());
            assertEquals(before.getSourcePath(), after.getSourcePath());
            assertEquals(before.getExtractionKey(), after.getExtractionKey());
            assertEquals(before.getImportArchive().getId(), after.getImportArchive().getId());
            assertEquals(before.getSegmentType(), after.getSegmentType());
            assertEquals(before.getCarrierKind(), after.getCarrierKind());
            assertEquals(before.getExtractionAttemptId(), after.getExtractionAttemptId());
            assertEquals(before.getCachedOcrJson(), after.getCachedOcrJson());
            assertEquals(before.getSplitSourceJson(), after.getSplitSourceJson());
            assertEquals(before.getCompany().getId(), after.getCompany().getId());
            assertEquals(before.getUploadedBy().getId(), after.getUploadedBy().getId());
            assertEquals(
                realSource.getId(), after.getOriginalFile().getId(), "preserved source file");

            assertNull(after.getInvoice(), "reprocess() legitimately clears invoice");
            assertEquals(AiOcrUploadStatus.QUEUED, after.getStatusSelect());
            assertNull(after.getExtractionErrors());
            assertNull(after.getExtractionInformation());
            assertNull(after.getRawExtractedJson());
            assertNull(after.getBlobUrl());
            // Generated numeric getters never return null (Axelor convention: they substitute
            // zero) — the underlying stored value is what's actually cleared, so compare against
            // zero here rather than asserting null on these two.
            assertEquals(0, BigDecimal.ZERO.compareTo(after.getConfidence()), "confidence reset");
            assertEquals(0, after.getProgressPercent().intValue(), "progressPercent reset");
            assertNull(after.getEngineUsed());
            assertNull(after.getExtractionStartedOn());
            assertNull(after.getExtractionFinishedOn());
            assertNull(after.getProcessedOn());
            assertNull(after.getLockedOn());
            assertEquals(3, after.getRetryCount());

            assertTrue(
                Files.exists(tmp), "reprocess() must never delete the preserved source file");
            Files.deleteIfExists(tmp);
          } catch (java.io.IOException e) {
            throw new RuntimeException(e);
          }
        });
  }
}

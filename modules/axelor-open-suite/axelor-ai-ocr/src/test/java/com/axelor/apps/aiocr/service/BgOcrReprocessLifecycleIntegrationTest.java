package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import org.junit.jupiter.api.Test;

/**
 * DB-backed proof of the duplicate reprocess lifecycle:
 *
 * <ul>
 *   <li>Deleting a Supplier Invoice linked by one CANONICAL upload and one DUPLICATE upload leaves
 *       only the canonical as {@code deletedFromSupplInvoices} (reprocessable); the duplicate stays
 *       {@code duplicate} (not reprocessable). Both FKs are cleared so the delete succeeds.
 *   <li>The semantic recheck ({@link BgOcrIntakeService#findExistingInvoiceForKey}) — run under the
 *       docKey advisory lock during (re)processing — returns the invoice a sibling already
 *       recreated, so a second reprocess links to it and never creates a second invoice
 *       (idempotent).
 *   <li>When no sibling links a live invoice, the recheck returns null → a genuine re-import
 *       proceeds.
 * </ul>
 *
 * <p>Method-level (not full {@code process()}): the advisory lock uses Postgres {@code
 * pg_advisory_xact_lock}, absent on the HSQLDB test DB, and a full extract needs journal/account
 * config. These tests exercise the exact decision methods that run inside the lock instead.
 */
class BgOcrReprocessLifecycleIntegrationTest extends AiOcrTest {

  private BgOcrIntakeService svc() {
    return Beans.get(BgOcrIntakeService.class);
  }

  private OcrUploadFileCleanupService cleanup() {
    return Beans.get(OcrUploadFileCleanupService.class);
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

  private Invoice supplierInvoice(Company company) {
    Invoice inv = new Invoice();
    inv.setStatusSelect(InvoiceRepository.STATUS_DRAFT);
    inv.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    inv.setCompany(company);
    inv.setInvoiceId("IT-" + System.nanoTime());
    inv.setInvoiceLineList(new java.util.ArrayList<>());
    return JPA.save(inv);
  }

  private AiOcrUpload upload(Company c, Invoice inv, String status, String key) {
    AiOcrUpload up = new AiOcrUpload();
    up.setCompany(c);
    up.setInvoice(inv);
    up.setStatusSelect(status);
    up.setDocKey(key);
    return JPA.save(up);
  }

  private static OcrExtractResult result() {
    OcrExtractResult r = new OcrExtractResult();
    r.setSupplierEik("831641791");
    r.setInvoiceNumber("0000000065");
    return r;
  }

  @Test
  void deleteMakesOnlyTheCanonicalUploadReprocessable() {
    inTransaction(
        () -> {
          Company a = company("RPL-A");
          String key = a.getId() + "|831641791|INVOICE|0000000065";
          Invoice inv = supplierInvoice(a);
          long canonicalId = upload(a, inv, AiOcrUploadStatus.COMPLETED, key).getId();
          long duplicateId = upload(a, inv, AiOcrUploadStatus.DUPLICATE, key).getId();

          cleanup().unlinkUploads(inv);

          AiOcrUpload canonical = uploads().find(canonicalId);
          AiOcrUpload duplicate = uploads().find(duplicateId);

          // Only the original that created the invoice becomes reprocessable.
          assertEquals(AiOcrUploadStatus.DELETED_FROM_SUPPL_INVOICES, canonical.getStatusSelect());
          assertNull(canonical.getInvoice(), "FK cleared so the invoice delete is not blocked");

          // The duplicate stays Duplicate (not reprocessable); its stale link is cleared.
          assertEquals(AiOcrUploadStatus.DUPLICATE, duplicate.getStatusSelect());
          assertNull(duplicate.getInvoice());
        });
  }

  @Test
  void secondReprocessLinksToRecreatedInvoiceInsteadOfCreatingAnother() {
    inTransaction(
        () -> {
          Company a = company("RPL-B");
          String key = a.getId() + "|831641791|INVOICE|0000000065";

          // The canonical upload was reprocessed first and recreated the invoice.
          Invoice recreated = supplierInvoice(a);
          upload(a, recreated, AiOcrUploadStatus.COMPLETED, key);

          // The second record (was a duplicate, invoice cleared by the earlier delete) is
          // reprocessed.
          AiOcrUpload second = upload(a, null, AiOcrUploadStatus.DUPLICATE, key);

          // Under the lock, the recheck finds the sibling's recreated invoice (partner args
          // unused).
          Invoice found =
              svc().findExistingInvoiceForKey(key, second.getId(), a.getId(), null, null);
          assertNotNull(found, "the recreated invoice is found via the sibling upload");
          assertEquals(recreated.getId(), found.getId());

          // → mark the second record Duplicate, linked, WITHOUT creating another invoice.
          svc().markSemanticDuplicate(second, found, result());
          AiOcrUpload reloaded = uploads().find(second.getId());
          assertEquals(AiOcrUploadStatus.DUPLICATE, reloaded.getStatusSelect());
          assertNotNull(reloaded.getInvoice());
          assertEquals(recreated.getId(), reloaded.getInvoice().getId());
        });
  }

  @Test
  void recheckReturnsNullWhenNoSiblingLinksLiveInvoiceSoReimportProceeds() {
    inTransaction(
        () -> {
          Company a = company("RPL-C");
          String key = a.getId() + "|831641791|INVOICE|0000000065";

          // Post-delete state: the original is deletedFromSupplInvoices and a former duplicate is
          // still Duplicate — NEITHER links a live invoice.
          AiOcrUpload original =
              upload(a, null, AiOcrUploadStatus.DELETED_FROM_SUPPL_INVOICES, key);
          upload(a, null, AiOcrUploadStatus.DUPLICATE, key);

          // Reprocessing the original must NOT be blocked by its own/sibling deleted history.
          // (partner=null keeps the invoice-table lookup out of HSQLDB; the sibling branch
          // decides.)
          Invoice found =
              svc().findExistingInvoiceForKey(key, original.getId(), a.getId(), null, null);
          assertNull(found, "no live invoice for the key → a genuine re-import proceeds");
        });
  }
}

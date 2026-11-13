package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * DB-backed proof that deleting a DUPLICATE upload is FK-safe and never touches the Supplier
 * Invoice:
 *
 * <ul>
 *   <li>deleting a duplicate while its linked invoice exists removes only the upload — the invoice
 *       and the canonical upload survive, and no row is created;
 *   <li>deleting a duplicate whose linked invoice was already deleted (dangling FK) does NOT throw
 *       {@code EntityNotFoundException} — the stale link is never loaded;
 *   <li>{@code producedInvoices} excludes duplicate-links and stale ids, so the ventilation/cancel
 *       guards never dereference a missing invoice.
 * </ul>
 */
class BgOcrDuplicateUploadDeleteIntegrationTest extends AiOcrTest {

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

  private AiOcrUpload upload(Company c, Invoice inv, String status) {
    AiOcrUpload up = new AiOcrUpload();
    up.setCompany(c);
    up.setInvoice(inv);
    up.setStatusSelect(status);
    return JPA.save(up);
  }

  private long countUpload(long id) {
    return JPA.em()
        .createQuery("SELECT COUNT(u) FROM AiOcrUpload u WHERE u.id = :id", Long.class)
        .setParameter("id", id)
        .getSingleResult();
  }

  private long countInvoice(long id) {
    return JPA.em()
        .createQuery("SELECT COUNT(i) FROM Invoice i WHERE i.id = :id", Long.class)
        .setParameter("id", id)
        .getSingleResult();
  }

  @Test
  void deletingDuplicateKeepsInvoiceAndCanonicalAndCreatesNothing() {
    inTransaction(
        () -> {
          Company a = company("DUPDEL-A");
          Invoice x = supplierInvoice(a);
          long xId = x.getId();
          long canonicalId = upload(a, x, AiOcrUploadStatus.COMPLETED).getId();
          long dupId = upload(a, x, AiOcrUploadStatus.DUPLICATE).getId();
          JPA.flush();
          long uploadsBefore = uploads().all().count();

          cleanup().fullyDeleteUploads(uploads().find(dupId), u -> JPA.em().remove(u));
          JPA.flush();

          assertEquals(0, countUpload(dupId), "the duplicate upload is removed");
          assertEquals(1, countInvoice(xId), "the Supplier Invoice is NOT deleted");
          assertEquals(1, countUpload(canonicalId), "the canonical upload is untouched");
          assertEquals(
              uploadsBefore - 1,
              uploads().all().count(),
              "exactly one upload removed; no upload/invoice created");
        });
  }

  @Test
  void deletingDuplicateAfterInvoiceDeletedDoesNotThrow() {
    inTransaction(
        () -> {
          Company a = company("DUPDEL-B");
          Invoice x = supplierInvoice(a);
          long xId = x.getId();
          long dupId = upload(a, x, AiOcrUploadStatus.DUPLICATE).getId();
          JPA.flush();

          // Delete the invoice row out from under the FK → the duplicate now holds a stale link.
          JPA.em().createNativeQuery("SET DATABASE REFERENTIAL INTEGRITY FALSE").executeUpdate();
          JPA.em()
              .createNativeQuery("DELETE FROM ACCOUNT_INVOICE WHERE id = " + xId)
              .executeUpdate();
          JPA.em().createNativeQuery("SET DATABASE REFERENTIAL INTEGRITY TRUE").executeUpdate();
          JPA.em().clear(); // drop the stale managed invoice/upload from the context

          AiOcrUpload dup = uploads().find(dupId); // dup.invoice → a now-deleted id
          assertDoesNotThrow(
              () -> {
                cleanup().fullyDeleteUploads(dup, u -> JPA.em().remove(u));
                JPA.flush();
              },
              "a stale invoice link must not cause EntityNotFoundException");

          assertEquals(0, countUpload(dupId), "the duplicate upload is removed");
          assertEquals(0, countInvoice(xId), "the invoice stays deleted; nothing is recreated");
        });
  }

  @Test
  void producedInvoicesExcludesDuplicateLinksAndStaleIds() {
    inTransaction(
        () -> {
          Company a = company("DUPDEL-C");
          Invoice x = supplierInvoice(a);
          AiOcrUpload canonical = upload(a, x, AiOcrUploadStatus.COMPLETED);
          AiOcrUpload dup = upload(a, x, AiOcrUploadStatus.DUPLICATE);
          JPA.flush();

          Set<Invoice> produced = cleanup().producedInvoices(List.of(canonical, dup));
          assertEquals(1, produced.size(), "only the canonical upload's invoice is 'produced'");
          assertTrue(produced.contains(x));

          // Stale-id detection reads a COUNT, never loads the entity.
          assertFalse(cleanup().invoiceExists(999_000_001L));
          assertTrue(cleanup().invoiceExists(x.getId()));
        });
  }
}

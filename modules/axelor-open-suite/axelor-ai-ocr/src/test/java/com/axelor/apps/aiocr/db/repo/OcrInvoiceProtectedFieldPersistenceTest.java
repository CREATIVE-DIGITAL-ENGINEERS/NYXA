package com.axelor.apps.aiocr.db.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.db.JPA;
import java.math.BigDecimal;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/**
 * DB-backed proof that the OCR document-total columns are immutable against EVERY persistence path
 * — not just {@code repository.save}. Each case mutates a managed invoice and flushes through a
 * real transaction; the flush-layer listener ({@link OcrInvoiceImmutabilityListener}) is the only
 * thing that can see a managed dirty flush, a direct {@code JPA.save} merge, or an insert.
 *
 * <p>The stock {@code Invoice} carries a Postgres-only {@code @Formula} ({@code ocrUploadIds}) that
 * HSQLDB cannot parse, so — exactly as {@code OcrInvoiceDeleteIntegrationTest} notes — the invoice
 * is never re-SELECTed: the managed entity created in the fixture is kept and mutated in place, and
 * protected values are read back with scalar projections (which do not materialise the formula).
 */
class OcrInvoiceProtectedFieldPersistenceTest extends AiOcrTest {

  private static int seq = 0;

  /**
   * Creates a draft OCR invoice with extracted + corrected figures via the trusted path, and
   * returns the still-managed entity (never re-loaded — see the class note on the HSQLDB formula).
   */
  private Invoice createCorrectedInvoice() {
    Invoice[] holder = new Invoice[1];
    inTransaction(
        () -> {
          Invoice inv = new Invoice();
          inv.setStatusSelect(InvoiceRepository.STATUS_DRAFT);
          inv.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
          inv.setAiGenerated(Boolean.TRUE);
          inv.setInvoiceId("ITP-" + (++seq));
          inv.setInvoiceLineList(new ArrayList<>());
          inv.setOcrDocumentTotal(bd("80.75")); // extracted (wrong) — immutable evidence
          inv.setOcrDocumentBreakdownAvailable(Boolean.TRUE);
          inv.setOcrDocumentTotalCorrected(bd("86.73")); // the audited correction
          inv.setOcrDocumentNetCorrected(bd("72.27"));
          inv.setOcrDocumentVatCorrected(bd("14.46"));
          OcrInvoiceWriteGuard.runTrusted(
              () -> {
                JPA.save(inv);
                JPA.em().flush();
              });
          holder[0] = inv;
        });
    return holder[0];
  }

  private BigDecimal stored(long id, String field) {
    return JPA.em()
        .createQuery("SELECT i." + field + " FROM Invoice i WHERE i.id = :id", BigDecimal.class)
        .setParameter("id", id)
        .getSingleResult();
  }

  @Test
  void theTrustedCreateActuallyPersistedTheProtectedFigures() {
    long id = createCorrectedInvoice().getId();
    assertEquals(0, stored(id, "ocrDocumentTotal").compareTo(bd("80.75")));
    assertEquals(0, stored(id, "ocrDocumentTotalCorrected").compareTo(bd("86.73")));
  }

  @Test
  void aManagedDirtyFlushWithoutRepositorySaveCannotChangeAProtectedField() {
    Invoice inv = createCorrectedInvoice();
    long id = inv.getId();
    inTransaction(
        () -> {
          inv.setOcrDocumentTotalCorrected(bd("999.99"));
          inv.setOcrDocumentTotal(bd("999.99"));
          JPA.em().flush(); // NO repository.save, NO JPA.save, NO trusted marker
        });
    assertEquals(
        0, stored(id, "ocrDocumentTotalCorrected").compareTo(bd("86.73")), "correction immutable");
    assertEquals(0, stored(id, "ocrDocumentTotal").compareTo(bd("80.75")), "extracted immutable");
  }

  @Test
  void anUntrustedJpaSaveCannotChangeAProtectedField() {
    Invoice inv = createCorrectedInvoice();
    long id = inv.getId();
    inTransaction(
        () -> {
          inv.setOcrDocumentTotalCorrected(bd("111.11"));
          JPA.save(inv); // untrusted merge
          JPA.em().flush();
        });
    assertEquals(0, stored(id, "ocrDocumentTotalCorrected").compareTo(bd("86.73")));
  }

  @Test
  void anUntrustedInsertCannotCarryAProtectedField() {
    Invoice[] holder = new Invoice[1];
    inTransaction(
        () -> {
          Invoice inv = new Invoice();
          inv.setStatusSelect(InvoiceRepository.STATUS_DRAFT);
          inv.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
          inv.setAiGenerated(Boolean.TRUE);
          inv.setInvoiceId("ITP-" + (++seq));
          inv.setInvoiceLineList(new ArrayList<>());
          inv.setOcrDocumentTotalCorrected(bd("500.00")); // forged on an untrusted insert
          JPA.save(inv);
          JPA.em().flush();
          holder[0] = inv;
        });
    BigDecimal corrected = stored(holder[0].getId(), "ocrDocumentTotalCorrected");
    assertTrue(corrected == null || corrected.signum() == 0, "forged insert value cleared");
  }

  @Test
  void aTrustedWriteDoesChangeTheProtectedField() {
    Invoice inv = createCorrectedInvoice();
    long id = inv.getId();
    inTransaction(
        () ->
            OcrInvoiceWriteGuard.runTrusted(
                () -> {
                  inv.setOcrDocumentTotalCorrected(bd("90.00"));
                  JPA.em().flush();
                }));
    assertEquals(0, stored(id, "ocrDocumentTotalCorrected").compareTo(bd("90.00")));
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }
}

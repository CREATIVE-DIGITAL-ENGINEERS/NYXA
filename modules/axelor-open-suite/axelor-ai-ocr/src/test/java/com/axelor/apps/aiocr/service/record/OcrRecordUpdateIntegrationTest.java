package com.axelor.apps.aiocr.service.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.PaymentCondition;
import com.axelor.apps.account.db.PaymentConditionLine;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.PaymentConditionLineRepository;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** DB-backed proof of the write path: whitelist, validation, audit trail and refusals. */
class OcrRecordUpdateIntegrationTest extends AiOcrTest {

  private OcrRecordUpdateService svc() {
    return Beans.get(OcrRecordUpdateService.class);
  }

  /**
   * Explicit caller identity for the harness; see the search test for the rationale. The scope must
   * name a company — one is created here and handed to the body, so fixtures and scope cannot drift
   * apart.
   */
  private void asSystem(String companyCode, Consumer<Company> body) {
    inTransaction(
        () -> {
          Company scope = company(companyCode);
          AiExecutionContext.runAsSystem(scope.getId(), "test", () -> body.accept(scope));
        });
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

  private Partner partner(String name) {
    Partner p = new Partner();
    p.setName(name);
    p.setFullName(name);
    p.setRegistrationCode("831919536");
    p.setIsSupplier(true);
    return JPA.save(p);
  }

  private Invoice invoice(Company c, Partner p, int status) {
    Invoice i = new Invoice();
    i.setCompany(c);
    i.setPartner(p);
    i.setSupplierInvoiceNb("OLD-1");
    i.setInvoiceDate(LocalDate.now().minusDays(3));
    i.setAiGenerated(Boolean.TRUE);
    i.setStatusSelect(status);
    i.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    return JPA.save(i);
  }

  private AiOcrUpload upload(Company c, Invoice inv) {
    AiOcrUpload u = new AiOcrUpload();
    u.setFileName("upd.pdf");
    u.setCompany(c);
    u.setInvoice(inv);
    u.setStatusSelect(AiOcrUploadStatus.NEEDS_REVIEW);
    return JPA.save(u);
  }

  private static Map<String, String> values(String... kv) {
    Map<String, String> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put(kv[i], kv[i + 1]);
    }
    return m;
  }

  @Test
  void appliesWhitelistedFieldsAndRecordsBeforeAfter() {
    asSystem(
        "UPD1",
        c -> {
          Invoice inv = invoice(c, partner("Demo EOOD"), InvoiceRepository.STATUS_DRAFT);
          AiOcrUpload u = upload(c, inv);

          OcrRecordUpdateService.UpdateResult r =
              svc()
                  .apply(
                      u.getId(),
                      values("supplierInvoiceNb", "NEW-42", "externalReference", "ref/9"));

          assertTrue(r.isChanged());
          assertEquals(2, r.getChanged().size());
          assertTrue(r.getRejected().isEmpty());

          OcrRecordUpdateService.Change first = r.getChanged().get(0);
          assertEquals("supplierInvoiceNb", first.getField());
          assertEquals("OLD-1", first.getOldValue());
          assertEquals("NEW-42", first.getNewValue());

          Invoice reloaded = Beans.get(InvoiceRepository.class).find(inv.getId());
          assertEquals("NEW-42", reloaded.getSupplierInvoiceNb());
          assertEquals("ref/9", reloaded.getExternalReference());
        });
  }

  @Test
  void changingTheInvoiceDateRefreshesTheDerivedDueDate() {
    asSystem(
        "UPD1B",
        c -> {
          Invoice inv = invoice(c, partner("Demo EOOD"), InvoiceRepository.STATUS_DRAFT);
          inv.setNextDueDate(LocalDate.now().minusYears(1)); // stale on purpose
          JPA.save(inv);
          AiOcrUpload u = upload(c, inv);

          LocalDate newDate = LocalDate.now().minusDays(2);
          OcrRecordUpdateService.UpdateResult r =
              svc().apply(u.getId(), values("invoiceDate", newDate.toString()));

          assertTrue(r.isChanged());
          Invoice reloaded = Beans.get(InvoiceRepository.class).find(inv.getId());
          assertEquals(newDate, reloaded.getInvoiceDate());
          // InvoiceManagementRepository.save would have done this; we call the same service
          // directly instead of dragging in the chain's amount recalculations.
          assertNotEquals(
              LocalDate.now().minusYears(1),
              reloaded.getNextDueDate(),
              "nextDueDate must not stay stale after the invoice date moves");
        });
  }

  private static void assertNotEquals(Object unexpected, Object actual, String message) {
    org.junit.jupiter.api.Assertions.assertNotEquals(unexpected, actual, message);
  }

  @Test
  void doesNotTouchAmountsOrLines() {
    asSystem(
        "UPD1C",
        c -> {
          Invoice inv = invoice(c, partner("Demo EOOD"), InvoiceRepository.STATUS_DRAFT);
          inv.setInTaxTotal(new java.math.BigDecimal("120.00"));
          inv.setExTaxTotal(new java.math.BigDecimal("100.00"));
          JPA.save(inv);
          AiOcrUpload u = upload(c, inv);

          svc().apply(u.getId(), values("supplierInvoiceNb", "AMT-1"));

          Invoice reloaded = Beans.get(InvoiceRepository.class).find(inv.getId());
          // The persistence path must never recompute totals as a side effect of a text edit.
          assertEquals(0, new java.math.BigDecimal("120.00").compareTo(reloaded.getInTaxTotal()));
          assertEquals(0, new java.math.BigDecimal("100.00").compareTo(reloaded.getExTaxTotal()));
        });
  }

  @Test
  void writesAnAuditTrailNamingTheChange() {
    asSystem(
        "UPD2",
        c -> {
          Invoice inv = invoice(c, partner("Demo EOOD"), InvoiceRepository.STATUS_DRAFT);
          AiOcrUpload u = upload(c, inv);

          svc().apply(u.getId(), values("supplierInvoiceNb", "AUDIT-1"));

          String notes =
              Beans.get(InvoiceRepository.class).find(inv.getId()).getAiValidationNotes();
          assertNotNull(notes);
          assertTrue(notes.contains("supplierInvoiceNb"));
          assertTrue(notes.contains("OLD-1"));
          assertTrue(notes.contains("AUDIT-1"));
          assertTrue(notes.contains("system:test"), "the caller is named in the audit trail");
        });
  }

  @Test
  void rejectsNonWhitelistedFieldsWithoutTouchingTheRest() {
    asSystem(
        "UPD3",
        c -> {
          Invoice inv = invoice(c, partner("Demo EOOD"), InvoiceRepository.STATUS_DRAFT);
          AiOcrUpload u = upload(c, inv);

          OcrRecordUpdateService.UpdateResult r =
              svc()
                  .apply(
                      u.getId(),
                      values(
                          "supplierInvoiceNb", "OK-1",
                          "partnerId", "999",
                          "inTaxTotal", "1000",
                          "statusSelect", "3"));

          assertEquals(1, r.getChanged().size());
          assertEquals(3, r.getRejected().size());
          for (OcrRecordUpdateService.Rejection rej : r.getRejected()) {
            assertTrue(rej.getReason().contains("not a writable field"));
          }
          assertEquals(
              "OK-1", Beans.get(InvoiceRepository.class).find(inv.getId()).getSupplierInvoiceNb());
        });
  }

  @Test
  void rejectsInvalidValuesPerField() {
    asSystem(
        "UPD4",
        c -> {
          Invoice inv = invoice(c, partner("Demo EOOD"), InvoiceRepository.STATUS_DRAFT);
          AiOcrUpload u = upload(c, inv);

          OcrRecordUpdateService.UpdateResult r =
              svc()
                  .apply(
                      u.getId(),
                      values(
                          "invoiceDate",
                          LocalDate.now().plusDays(5).toString(),
                          "supplierInvoiceNb",
                          ""));

          assertFalse(r.isChanged());
          assertEquals(2, r.getRejected().size());
          assertTrue(r.getRejected().stream().anyMatch(x -> x.getReason().contains("future")));
          assertTrue(
              r.getRejected().stream().anyMatch(x -> x.getReason().contains("cannot be cleared")));

          assertEquals(
              "OLD-1",
              Beans.get(InvoiceRepository.class).find(inv.getId()).getSupplierInvoiceNb(),
              "nothing must have changed");
        });
  }

  @Test
  void rejectsAMalformedDate() {
    asSystem(
        "UPD5",
        c -> {
          Invoice inv = invoice(c, partner("Demo EOOD"), InvoiceRepository.STATUS_DRAFT);
          AiOcrUpload u = upload(c, inv);

          OcrRecordUpdateService.UpdateResult r =
              svc().apply(u.getId(), values("invoiceDate", "17/08/2026"));
          assertFalse(r.isChanged());
          assertTrue(r.getRejected().get(0).getReason().contains("ISO date"));
        });
  }

  @Test
  void refusesNonDraftInvoices() {
    asSystem(
        "UPD6",
        c -> {
          Invoice inv = invoice(c, partner("Demo EOOD"), InvoiceRepository.STATUS_VALIDATED);
          AiOcrUpload u = upload(c, inv);

          OcrRecordUpdateService.UpdateRefusedException e =
              assertThrows(
                  OcrRecordUpdateService.UpdateRefusedException.class,
                  () -> svc().apply(u.getId(), values("supplierInvoiceNb", "X")));
          assertTrue(e.getMessage().contains("Draft"));
        });
  }

  @Test
  void refusesInvoicesNotProducedByOcr() {
    asSystem(
        "UPD7",
        c -> {
          Invoice inv = invoice(c, partner("Demo EOOD"), InvoiceRepository.STATUS_DRAFT);
          inv.setAiGenerated(Boolean.FALSE);
          JPA.save(inv);
          AiOcrUpload u = upload(c, inv);

          assertThrows(
              OcrRecordUpdateService.UpdateRefusedException.class,
              () -> svc().apply(u.getId(), values("supplierInvoiceNb", "X")));
        });
  }

  @Test
  void refusesMissingRecordAndRecordWithoutInvoice() {
    asSystem(
        "UPD8",
        c -> {
          AiOcrUpload orphan = upload(c, null);

          assertThrows(
              OcrRecordUpdateService.UpdateRefusedException.class,
              () -> svc().apply(999_999L, values("supplierInvoiceNb", "X")));
          assertThrows(
              OcrRecordUpdateService.UpdateRefusedException.class,
              () -> svc().apply(orphan.getId(), values("supplierInvoiceNb", "X")));
          assertThrows(
              OcrRecordUpdateService.UpdateRefusedException.class,
              () -> svc().apply(null, values("supplierInvoiceNb", "X")));
        });
  }

  @Test
  void refusesAnEmptyFieldSet() {
    asSystem(
        "UPD9",
        c -> {
          Invoice inv = invoice(c, partner("Demo EOOD"), InvoiceRepository.STATUS_DRAFT);
          AiOcrUpload u = upload(c, inv);

          assertThrows(
              OcrRecordUpdateService.UpdateRefusedException.class,
              () -> svc().apply(u.getId(), new LinkedHashMap<>()));
        });
  }

  @Test
  void refusesWhenThereIsNoCallerIdentityAtAll() {
    // No Shiro subject and no system context: nothing may be written.
    assertNull(com.axelor.auth.AuthUtils.getUser());
    OcrRecordUpdateService.UpdateRefusedException e =
        assertThrows(
            OcrRecordUpdateService.UpdateRefusedException.class,
            () -> svc().apply(1L, values("supplierInvoiceNb", "X")));
    assertTrue(e.getMessage().contains("No authenticated user"));
  }

  @Test
  void bankDetailsMustBelongToTheInvoicePartner() {
    asSystem(
        "UPD10",
        c -> {
          Partner supplier = partner("Demo EOOD");
          Partner other = partner("Other Ltd");
          Invoice inv = invoice(c, supplier, InvoiceRepository.STATUS_DRAFT);
          AiOcrUpload u = upload(c, inv);

          BankDetails foreign = new BankDetails();
          foreign.setIban("BG80BNBG96611020345678");
          foreign.setPartner(other);
          BankDetails savedForeign = JPA.save(foreign);

          OcrRecordUpdateService.UpdateResult r =
              svc().apply(u.getId(), values("bankDetailsId", String.valueOf(savedForeign.getId())));

          assertFalse(r.isChanged());
          assertTrue(
              r.getRejected()
                  .get(0)
                  .getReason()
                  .contains("does not belong to the invoice supplier"),
              r.getRejected().get(0).getReason());

          BankDetails own = new BankDetails();
          own.setIban("BG18RZBB91550123456789");
          own.setPartner(supplier);
          BankDetails savedOwn = JPA.save(own);

          OcrRecordUpdateService.UpdateResult ok =
              svc().apply(u.getId(), values("bankDetailsId", String.valueOf(savedOwn.getId())));
          assertTrue(ok.isChanged());
          assertEquals(
              savedOwn.getId(),
              Beans.get(InvoiceRepository.class).find(inv.getId()).getBankDetails().getId());
        });
  }

  @Test
  void anOwnerlessBankAccountIsNotTheSuppliersEither() {
    // An account with no partner must not pass just because it does not belong to a *different*
    // partner. A company account — possibly another company's — has no partner at
    // all, so "not somebody else's" was never the same as "the supplier's".
    asSystem(
        "UPD10B",
        c -> {
          Partner supplier = partner("Demo EOOD");
          Invoice inv = invoice(c, supplier, InvoiceRepository.STATUS_DRAFT);
          AiOcrUpload u = upload(c, inv);

          BankDetails ownerless = new BankDetails();
          ownerless.setIban("BG80BNBG96611020345679");
          BankDetails saved = JPA.save(ownerless);

          OcrRecordUpdateService.UpdateResult r =
              svc().apply(u.getId(), values("bankDetailsId", String.valueOf(saved.getId())));

          assertFalse(r.isChanged());
          assertTrue(
              r.getRejected()
                  .get(0)
                  .getReason()
                  .contains("does not belong to the invoice supplier"),
              r.getRejected().get(0).getReason());
          assertNull(Beans.get(InvoiceRepository.class).find(inv.getId()).getBankDetails());
        });
  }

  @Test
  void rejectsUnknownBankDetailsId() {
    asSystem(
        "UPD11",
        c -> {
          Invoice inv = invoice(c, partner("Demo EOOD"), InvoiceRepository.STATUS_DRAFT);
          AiOcrUpload u = upload(c, inv);

          OcrRecordUpdateService.UpdateResult r =
              svc().apply(u.getId(), values("bankDetailsId", "888888"));
          assertFalse(r.isChanged());
          assertTrue(r.getRejected().get(0).getReason().contains("no bank account"));
        });
  }

  @Test
  void reportsResultingReviewStateAndSyncsTheUpload() {
    asSystem(
        "UPD12",
        c -> {
          Invoice inv = invoice(c, partner("Demo EOOD"), InvoiceRepository.STATUS_DRAFT);
          AiOcrUpload u = upload(c, inv);

          OcrRecordUpdateService.UpdateResult r =
              svc().apply(u.getId(), values("supplierInvoiceNb", "STATE-1"));

          assertNotNull(r.getOcrReviewStatus());
          assertFalse(r.getMissingReviewFields().isEmpty());
          assertEquals(AiOcrUploadStatus.NEEDS_REVIEW, r.getUploadStatus());
        });
  }

  @Test
  void refusesARecordOutsideTheCallersCompanyScope() {
    asSystem(
        "UPD14A",
        mine -> {
          Company theirs = company("UPD14B");
          Invoice inv = invoice(theirs, partner("Demo EOOD"), InvoiceRepository.STATUS_DRAFT);
          AiOcrUpload u = upload(theirs, inv);

          OcrRecordUpdateService scoped =
              new OcrRecordUpdateService(
                  Beans.get(AiOcrUploadRepository.class), Beans.get(InvoiceRepository.class)) {
                @Override
                protected Long allowedCompanyId() {
                  return mine.getId();
                }
              };

          OcrRecordUpdateService.UpdateRefusedException e =
              assertThrows(
                  OcrRecordUpdateService.UpdateRefusedException.class,
                  () -> scoped.apply(u.getId(), values("supplierInvoiceNb", "X")));
          // The refusal must be indistinguishable from "no such record": telling the caller the
          // record exists but belongs elsewhere is itself the leak.
          assertTrue(
              e.getMessage().contains("No OCR record with id"),
              "a cross-company refusal must read as not-found: " + e.getMessage());
          assertFalse(
              e.getMessage().contains("another company"),
              "the refusal must not confirm the record exists elsewhere");

          assertEquals(
              "OLD-1",
              Beans.get(InvoiceRepository.class).find(inv.getId()).getSupplierInvoiceNb(),
              "a cross-company attempt must change nothing");
        });
  }

  @Test
  void noOpUpdateChangesNothing() {
    asSystem(
        "UPD13",
        c -> {
          Invoice inv = invoice(c, partner("Demo EOOD"), InvoiceRepository.STATUS_DRAFT);
          AiOcrUpload u = upload(c, inv);

          OcrRecordUpdateService.UpdateResult r =
              svc().apply(u.getId(), values("supplierInvoiceNb", "OLD-1"));

          assertFalse(r.isChanged(), "setting the same value is not a change");
          assertTrue(r.getRejected().isEmpty());
        });
  }

  // ── Header dates ──────────────────────────────────────────────────────────
  // Merged in from OcrRecordDateUpdateTest, which tested the same service through the same fixture
  // shape and paid a second full Guice + HSQLDB bootstrap to do it. Its one pure case (the
  // unattended guard on originDate) moved to OcrRecordToolContractTest, and its non-draft refusal
  // duplicated refusesNonDraftInvoices above.
  //
  // Both dates were blank on invoice 576, which is why nothing about it could be ventilated. The
  // point of these: for a purchase invoice, InvoiceToolService.getDueDate/getNextDueDate read
  // originDate and ignore invoiceDate, so the due date follows the ORIGIN date.

  /** "Net 30 days", so the due date is visibly derived rather than copied. */
  private PaymentCondition net30() {
    PaymentCondition condition = new PaymentCondition();
    condition.setName("Net 30");
    condition.setCode("NET30");
    PaymentConditionLine line = new PaymentConditionLine();
    line.setSequence(1);
    line.setPaymentTime(30);
    line.setTypeSelect(PaymentConditionLineRepository.TYPE_NET);
    line.setPeriodTypeSelect(PaymentConditionLineRepository.PERIOD_TYPE_DAYS);
    line.setPaymentCondition(condition);
    condition.setPaymentConditionLineList(
        new java.util.ArrayList<>(java.util.Collections.singletonList(line)));
    return JPA.save(condition);
  }

  /** A draft with both dates missing, exactly like invoice 576. */
  private AiOcrUpload uploadWithNoDates(Company c, boolean withPaymentCondition) {
    Invoice inv = new Invoice();
    inv.setCompany(c);
    inv.setPartner(partner("ЕКО БЪЛГАРИЯ ЕАД"));
    inv.setSupplierInvoiceNb("№1084628785");
    inv.setAiGenerated(Boolean.TRUE);
    inv.setStatusSelect(InvoiceRepository.STATUS_DRAFT);
    inv.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    if (withPaymentCondition) {
      inv.setPaymentCondition(net30());
    }
    return upload(c, JPA.save(inv));
  }

  @Test
  void bothDatesCanBeSetInOneCallWhichIsHowTheOcrDateIsApplied() {
    // One call, one approval: the user agrees to "set both dates to 2026-03-03" once.
    asSystem(
        "UPD14",
        scope -> {
          AiOcrUpload u = uploadWithNoDates(scope, false);
          OcrRecordUpdateService.UpdateResult r =
              svc()
                  .apply(
                      u.getId(),
                      values(
                          OcrRecordUpdateService.F_INVOICE_DATE, "2026-03-03",
                          OcrRecordUpdateService.F_ORIGIN_DATE, "2026-03-03"));

          assertTrue(r.getRejected().isEmpty(), r.getRejected().toString());
          Invoice reloaded = JPA.find(Invoice.class, u.getInvoice().getId());
          assertEquals(LocalDate.of(2026, 3, 3), reloaded.getInvoiceDate());
          assertEquals(LocalDate.of(2026, 3, 3), reloaded.getOriginDate());
          assertTrue(
              r.getChanged().stream()
                  .anyMatch(c -> OcrRecordUpdateService.F_ORIGIN_DATE.equals(c.getField())));
        });
  }

  @Test
  void changingTheOriginDateRefreshesTheDueDateFromThePaymentCondition() {
    // The due date of a purchase invoice follows originDate, so refreshing it only when
    // invoiceDate moves would mean it never moves at all.
    asSystem(
        "UPD15",
        scope -> {
          AiOcrUpload u = uploadWithNoDates(scope, true);
          OcrRecordUpdateService.UpdateResult r =
              svc().apply(u.getId(), values(OcrRecordUpdateService.F_ORIGIN_DATE, "2026-03-03"));

          Invoice reloaded = JPA.find(Invoice.class, u.getInvoice().getId());
          assertEquals(LocalDate.of(2026, 4, 2), reloaded.getDueDate(), "2026-03-03 + 30 days");
          assertNotNull(reloaded.getNextDueDate());
          assertEquals(Integer.valueOf(30), reloaded.getPaymentDelay());
          assertTrue(
              r.getChanged().stream().anyMatch(c -> "dueDate".equals(c.getField())),
              "and the refresh is reported: " + r.getChanged());
        });
  }

  @Test
  void aFutureOriginDateIsRefusedLikeTheFormRefusesIt() {
    // Mirrors action-invoice-validate-check-origin-date.
    asSystem(
        "UPD16",
        c -> {
          AiOcrUpload u = uploadWithNoDates(c, false);
          OcrRecordUpdateService.UpdateResult r =
              svc()
                  .apply(
                      u.getId(),
                      values(
                          OcrRecordUpdateService.F_ORIGIN_DATE,
                          LocalDate.now().plusDays(1).toString()));

          assertEquals(1, r.getRejected().size());
          assertTrue(r.getRejected().get(0).getReason().contains("future"));
          assertNull(JPA.find(Invoice.class, u.getInvoice().getId()).getOriginDate());
        });
  }

  @Test
  void anUnparseableOrClearedOriginDateIsRefused() {
    asSystem(
        "UPD17",
        c -> {
          AiOcrUpload u = uploadWithNoDates(c, false);
          for (String bad : new String[] {"03/03/2026", "3 March 2026", "", "   "}) {
            OcrRecordUpdateService.UpdateResult r =
                svc().apply(u.getId(), values(OcrRecordUpdateService.F_ORIGIN_DATE, bad));
            assertEquals(1, r.getRejected().size(), "accepted '" + bad + "'");
          }
          assertNull(JPA.find(Invoice.class, u.getInvoice().getId()).getOriginDate());
        });
  }
}

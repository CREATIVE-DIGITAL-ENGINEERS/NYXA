package com.axelor.apps.aiocr.service.readiness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.readiness.OcrSupplierInvoiceReadinessService.Signals;
import org.junit.jupiter.api.Test;

/** Unit tests for the pure readiness rules (no Guice/DB). */
class OcrSupplierInvoiceReadinessServiceTest {

  private final OcrSupplierInvoiceReadinessService svc = new OcrSupplierInvoiceReadinessService();

  private Signals fullyConfigured() {
    Signals s = new Signals();
    s.supplierPurchaseJournalConfigured = true;
    s.partnerAccountResolvable = true;
    s.partnerAccountReconcilable = true;
    s.suppInvSequenceConfigured = true;
    s.paymentModePresent = true;
    s.paymentConditionPresent = true;
    s.currencyPresent = true;
    s.invoiceDatePresent = true;
    s.originDatePresent = true;
    s.lineCount = 2;
    s.linesMissingAccount = 0;
    s.taxSummaryRowCount = 2;
    return s;
  }

  @Test
  void fullyConfiguredIsValidateAndVentilateReady() {
    OcrInvoiceReadiness r = svc.evaluate(fullyConfigured());
    assertTrue(r.isValidateReady());
    assertTrue(r.isVentilateReady());
    assertTrue(r.getValidateBlockers().isEmpty());
    assertTrue(r.getVentilateBlockers().isEmpty());
  }

  @Test
  void missingSupplierJournalBlocksValidate() {
    Signals s = fullyConfigured();
    s.supplierPurchaseJournalConfigured = false;
    OcrInvoiceReadiness r = svc.evaluate(s);
    assertFalse(r.isValidateReady());
    assertTrue(r.getValidateBlockers().stream().anyMatch(b -> b.contains("journal")));
  }

  @Test
  void unresolvablePartnerAccountBlocksValidate() {
    Signals s = fullyConfigured();
    s.partnerAccountResolvable = false;
    OcrInvoiceReadiness r = svc.evaluate(s);
    assertFalse(r.isValidateReady());
    assertTrue(r.getValidateBlockers().stream().anyMatch(b -> b.contains("payable account")));
  }

  @Test
  void nonReconcilableAccountBlocksValidate() {
    Signals s = fullyConfigured();
    s.partnerAccountReconcilable = false;
    OcrInvoiceReadiness r = svc.evaluate(s);
    assertFalse(r.isValidateReady());
    assertTrue(r.getValidateBlockers().stream().anyMatch(b -> b.contains("reconcileOk")));
  }

  @Test
  void missingPaymentTermsBlockVentilateButNotValidate() {
    Signals s = fullyConfigured();
    s.paymentModePresent = false;
    s.paymentConditionPresent = false;
    OcrInvoiceReadiness r = svc.evaluate(s);
    assertTrue(r.isValidateReady()); // payment terms are a ventilate precondition, not validate
    assertFalse(r.isVentilateReady());
    assertEquals(2, r.getVentilateBlockers().stream().filter(b -> b.contains("Payment")).count());
  }

  @Test
  void linesWithoutAccountBlockVentilate() {
    Signals s = fullyConfigured();
    s.linesMissingAccount = 1;
    OcrInvoiceReadiness r = svc.evaluate(s);
    assertTrue(r.isValidateReady());
    assertFalse(r.isVentilateReady());
    assertTrue(
        r.getVentilateBlockers().stream().anyMatch(b -> b.contains("no expense/purchase account")));
  }

  @Test
  void missingSequenceAndOriginDateBlockVentilate() {
    Signals s = fullyConfigured();
    s.suppInvSequenceConfigured = false;
    s.originDatePresent = false;
    OcrInvoiceReadiness r = svc.evaluate(s);
    assertFalse(r.isVentilateReady());
    assertTrue(r.getVentilateBlockers().stream().anyMatch(b -> b.contains("sequence")));
    assertTrue(r.getVentilateBlockers().stream().anyMatch(b -> b.contains("origin date")));
  }

  @Test
  void warningsAndDiscrepanciesAreRoutedIntoNotes() {
    Signals s = fullyConfigured();
    s.warnings.add("Totals do not reconcile: taxable + VAT differs from the invoice total.");
    s.warnings.add("Low overall confidence: 55%.");
    OcrInvoiceReadiness r = svc.evaluate(s);
    assertEquals(2, r.getWarnings().size());
    String notes = r.toNotes();
    assertTrue(notes.contains("do not reconcile"));
    assertTrue(notes.contains("ready to Validate"));
  }

  @Test
  void notesListBlockersWhenNotReady() {
    Signals s = new Signals(); // nothing configured
    OcrInvoiceReadiness r = svc.evaluate(s);
    assertFalse(r.isValidateReady());
    String notes = r.toNotes();
    assertTrue(notes.contains("NOT ready to Validate"));
    assertTrue(notes.contains("Blocks Validate"));
  }
}

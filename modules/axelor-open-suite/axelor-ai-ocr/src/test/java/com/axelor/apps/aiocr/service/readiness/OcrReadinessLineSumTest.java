package com.axelor.apps.aiocr.service.readiness;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The blocker that would have caught invoice 576.
 *
 * <p>Every one of its lines had a product, an account and a tax, so every existing check passed and
 * the invoice read {@code ocrReviewStatus=complete}. What was wrong was arithmetic no rule looked
 * at: the two lines summed to 50.87 against a printed ex-tax total of 19.97. The header total is
 * the only independent evidence about the lines, so a disagreement has to be a blocker.
 *
 * <p>Pure — {@code evaluate(Signals)} needs no Guice or JPA.
 */
class OcrReadinessLineSumTest {

  private OcrSupplierInvoiceReadinessService service() {
    return new OcrSupplierInvoiceReadinessService();
  }

  /** Signals for an otherwise healthy invoice, so only the line-sum rule is under test. */
  private OcrSupplierInvoiceReadinessService.Signals healthy() {
    OcrSupplierInvoiceReadinessService.Signals s = new OcrSupplierInvoiceReadinessService.Signals();
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
    return s;
  }

  @Test
  void linesThatDoNotAddUpToTheHeaderBlockVentilation() {
    OcrSupplierInvoiceReadinessService.Signals s = healthy();
    s.lineSumMatchesHeader = Boolean.FALSE;
    s.lineSumDelta = new BigDecimal("30.90");

    OcrInvoiceReadiness r = service().evaluate(s);
    assertTrue(
        r.getVentilateBlockers().stream().anyMatch(b -> b.contains("do not add up")),
        r.getVentilateBlockers().toString());
    assertTrue(
        r.getVentilateBlockers().stream().anyMatch(b -> b.contains("30.90")),
        "and it must say by how much: " + r.getVentilateBlockers());
  }

  @Test
  void linesThatDoAddUpAreReportedAsPopulatedAndBlockNothing() {
    OcrSupplierInvoiceReadinessService.Signals s = healthy();
    s.lineSumMatchesHeader = Boolean.TRUE;
    s.lineSumDelta = BigDecimal.ZERO;

    OcrInvoiceReadiness r = service().evaluate(s);
    assertTrue(r.getVentilateBlockers().isEmpty(), r.getVentilateBlockers().toString());
    assertTrue(r.getPopulated().stream().anyMatch(p -> p.contains("add up to the invoice total")));
  }

  @Test
  void notCheckedIsNotTreatedAsCheckedOut() {
    // A tri-state on purpose: an invoice with no header total to compare against must neither be
    // blocked nor reported as reconciling. "Unknown" reading as "fine" is how a silent defect
    // becomes a shipped one.
    OcrSupplierInvoiceReadinessService.Signals s = healthy();
    s.lineSumMatchesHeader = null;

    OcrInvoiceReadiness r = service().evaluate(s);
    assertTrue(r.getVentilateBlockers().isEmpty());
    assertFalse(r.getPopulated().stream().anyMatch(p -> p.contains("add up to the invoice total")));
  }

  @Test
  void theLineSumRuleIsIndependentOfTheOtherLineRules() {
    // 576 again: accounts and taxes all present, and still not ventilatable.
    OcrSupplierInvoiceReadinessService.Signals s = healthy();
    s.linesMissingAccount = 0;
    s.linesMissingTax = 0;
    s.lineSumMatchesHeader = Boolean.FALSE;
    s.lineSumDelta = new BigDecimal("30.90");

    OcrInvoiceReadiness r = service().evaluate(s);
    assertTrue(r.getPopulated().stream().anyMatch(p -> p.contains("line(s) with an account")));
    assertTrue(r.getPopulated().stream().anyMatch(p -> p.contains("line(s) with a tax")));
    assertFalse(r.getVentilateBlockers().isEmpty(), "and yet: " + r.getPopulated());
  }
}

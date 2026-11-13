package com.axelor.apps.aiocr.service.readiness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.readiness.OcrSupplierInvoiceReadinessService.LineIssue;
import com.axelor.apps.aiocr.service.readiness.OcrSupplierInvoiceReadinessService.Signals;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The line-level readiness rules, exercised through the pure {@code evaluate} method.
 *
 * <p>The missing-tax blocker is the interesting one: before it existed, a line whose VAT rate the
 * BG mapping did not recognise was built with no tax at all and nothing anywhere reported it, so
 * the invoice could read "complete" and then fail at ventilation.
 */
class OcrReadinessLineIssuesTest {

  private final OcrSupplierInvoiceReadinessService service =
      new OcrSupplierInvoiceReadinessService();

  /** A fully configured company/header, so only line rules can produce blockers. */
  private Signals healthy() {
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
    return s;
  }

  private static boolean mentions(java.util.List<String> items, String fragment) {
    return items.stream().anyMatch(i -> i.contains(fragment));
  }

  @Test
  void aLineWithNoTaxBlocksVentilation() {
    Signals s = healthy();
    s.linesMissingTax = 1;

    OcrInvoiceReadiness r = service.evaluate(s);

    assertTrue(mentions(r.getVentilateBlockers(), "no tax"));
    assertFalse(r.isVentilateReady());
    assertTrue(r.isValidateReady(), "a missing tax does not stop Validate, only Ventilate");
  }

  @Test
  void aFullyMappedInvoiceReportsTaxAsPopulated() {
    OcrInvoiceReadiness r = service.evaluate(healthy());

    assertTrue(r.isVentilateReady());
    assertTrue(mentions(r.getPopulated(), "with a tax"));
    assertTrue(mentions(r.getPopulated(), "with an account"));
  }

  @Test
  void missingProductAccountAndTaxAreCountedIndependently() {
    Signals s = healthy();
    s.lineCount = 3;
    s.linesMissingProduct = 3;
    s.linesMissingAccount = 2;
    s.linesMissingTax = 1;

    OcrInvoiceReadiness r = service.evaluate(s);

    assertTrue(mentions(r.getVentilateBlockers(), "2 of 3"), "accounts");
    assertTrue(mentions(r.getVentilateBlockers(), "1 of 3"), "taxes");
  }

  @Test
  void noLinesAtAllStillReportsTheOldBlockerAndNoTaxBlocker() {
    Signals s = healthy();
    s.lineCount = 0;

    OcrInvoiceReadiness r = service.evaluate(s);

    assertTrue(mentions(r.getVentilateBlockers(), "No invoice lines"));
    assertFalse(mentions(r.getVentilateBlockers(), "no tax"), "zero lines cannot lack a tax");
  }

  @Test
  void lineIssuesAreCarriedThroughForACallerToActOn() {
    Signals s = healthy();
    s.linesMissingProduct = 1;
    s.lineIssues.add(
        new LineIssue(0, 41L, "Услуга по договор", new BigDecimal("20"), true, true, false));

    OcrInvoiceReadiness r = service.evaluate(s);

    assertEquals(1, r.getLineIssues().size());
    LineIssue issue = r.getLineIssues().get(0);
    assertEquals(41L, issue.getLineId());
    assertEquals(0, issue.getIndex());
    assertTrue(issue.isMissingProduct());
    assertTrue(issue.isMissingAccount());
    assertFalse(issue.isMissingTax());
    assertFalse(issue.isEmpty());
  }

  @Test
  void aCleanInvoiceCarriesNoLineIssues() {
    assertTrue(service.evaluate(healthy()).getLineIssues().isEmpty());
  }
}

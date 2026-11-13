package com.axelor.apps.aiocr.service.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractLine;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The trigger logic, verified without any AI. This is what decides whether an invoice costs a GPT
 * call at all, so it is tested on its own.
 */
class BgOcrProblemFieldsTest {

  /** A complete, internally consistent extraction — the case that must NEVER reach the model. */
  private static OcrExtractResult clean() {
    OcrExtractResult r = new OcrExtractResult();
    r.setSupplierName("Демо ЕООД");
    r.setSupplierEik("831919536");
    r.setSupplierVat("BG831919536");
    r.setSupplierAddress("гр. София, ул. Витоша 1");
    r.setInvoiceNumber("0000001234");
    r.setInvoiceDate(LocalDate.now().minusDays(3));
    r.setCurrency("BGN");
    r.setTotalBase(new BigDecimal("100.00"));
    r.setTotalVat(new BigDecimal("20.00"));
    r.setTotalGross(new BigDecimal("120.00"));
    r.setIban("BG80BNBG96611020345678");
    return r;
  }

  @Test
  void cleanResolvedInvoiceProducesNoProblems() {
    Set<String> problems = BgOcrProblemFields.compute(clean(), true, "202323100");
    assertTrue(problems.isEmpty(), "clean invoice must not be sent to the AI, got: " + problems);
  }

  @Test
  void unmatchedSupplierAlwaysFlagsEik() {
    // The line-908 case: no Partner means no Invoice at all, so this must reach the AI.
    Set<String> problems = BgOcrProblemFields.compute(clean(), false, "202323100");
    assertTrue(problems.contains(BgOcrProblemFields.SUPPLIER_EIK));
  }

  @Test
  void missingFieldsAreFlagged() {
    OcrExtractResult r = clean();
    r.setIban(null);
    r.setInvoiceNumber(null);
    Set<String> problems = BgOcrProblemFields.compute(r, true, null);
    assertTrue(problems.contains(BgOcrProblemFields.IBAN));
    assertTrue(problems.contains(BgOcrProblemFields.INVOICE_NO));
  }

  @Test
  void presentButInconsistentTotalsAreFlagged() {
    OcrExtractResult r = clean();
    r.setTotalGross(new BigDecimal("130.00")); // 100 + 20 != 130
    Set<String> problems = BgOcrProblemFields.compute(r, true, null);
    assertTrue(problems.contains(BgOcrProblemFields.TOTAL_GROSS));
    assertTrue(problems.contains(BgOcrProblemFields.TOTAL_BASE));
    assertTrue(problems.contains(BgOcrProblemFields.TOTAL_VAT));
  }

  @Test
  void presentButInvalidIbanIsFlagged() {
    OcrExtractResult r = clean();
    // Structurally fine, checksum broken — invisible to BgOcrBankDetails.isValidIban, which is a
    // structural check only. This is precisely the "present but wrong" case AI exists to catch.
    r.setIban("BG80BNBG96611020345679");
    Set<String> problems = BgOcrProblemFields.compute(r, true, null);
    assertTrue(problems.contains(BgOcrProblemFields.IBAN));
  }

  @Test
  void ibanChecksumDistinguishesValidFromStructurallyPlausible() {
    assertTrue(BgOcrProblemFields.isIbanChecksumValid("BG80BNBG96611020345678"));
    assertTrue(
        BgOcrProblemFields.isIbanChecksumValid("BG80 BNBG 9661 1020 3456 78"), "spaces tolerated");
    assertFalse(BgOcrProblemFields.isIbanChecksumValid("BG80BNBG96611020345679"));
    assertFalse(BgOcrProblemFields.isIbanChecksumValid("not-an-iban"));
    assertFalse(BgOcrProblemFields.isIbanChecksumValid(null));
  }

  @Test
  void selfInvoiceIsFlagged() {
    OcrExtractResult r = clean();
    Set<String> problems = BgOcrProblemFields.compute(r, true, "831919536");
    assertTrue(problems.contains(BgOcrProblemFields.SUPPLIER_EIK));
    assertTrue(problems.contains(BgOcrProblemFields.SUPPLIER_NAME));
  }

  @Test
  void futureInvoiceDateIsFlagged() {
    OcrExtractResult r = clean();
    r.setInvoiceDate(LocalDate.now().plusDays(5));
    assertTrue(BgOcrProblemFields.compute(r, true, null).contains(BgOcrProblemFields.INVOICE_DATE));
  }

  @Test
  void lowFieldConfidenceIsFlagged() {
    OcrExtractResult r = clean();
    r.getFieldConfidence().put("VendorTaxId", 0.42);
    assertTrue(BgOcrProblemFields.compute(r, true, null).contains(BgOcrProblemFields.SUPPLIER_EIK));
  }

  @Test
  void cosmeticWarningAloneDoesNotTriggerAi() {
    OcrExtractResult r = clean();
    r.getWarnings().add("Some informational note");
    assertTrue(
        BgOcrProblemFields.compute(r, true, null).isEmpty(),
        "a warning on an otherwise clean invoice must not cost a GPT call");
  }

  @Test
  void totalsReconcileToleratesRounding() {
    OcrExtractResult r = clean();
    r.setTotalGross(new BigDecimal("120.01"));
    assertTrue(BgOcrProblemFields.totalsReconcile(r));
    r.setTotalGross(new BigDecimal("120.50"));
    assertFalse(BgOcrProblemFields.totalsReconcile(r));
  }

  @Test
  void lineSumMismatchIsFlagged() {
    OcrExtractResult r = clean();
    List<OcrExtractLine> lines = new ArrayList<>();
    OcrExtractLine l = new OcrExtractLine();
    l.setNetAmount(new BigDecimal("10.00")); // total base says 100.00
    lines.add(l);
    r.setLines(lines);
    assertFalse(BgOcrProblemFields.linesReconcile(r));
    assertTrue(BgOcrProblemFields.compute(r, true, null).contains(BgOcrProblemFields.TOTAL_BASE));
  }

  @Test
  void incompleteLineDataIsNotTreatedAsContradiction() {
    OcrExtractResult r = clean();
    List<OcrExtractLine> lines = new ArrayList<>();
    lines.add(new OcrExtractLine()); // no amounts at all
    r.setLines(lines);
    assertTrue(BgOcrProblemFields.linesReconcile(r));
  }
}

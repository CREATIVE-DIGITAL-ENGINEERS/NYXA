package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Pure tests for the draft-creation gate: a draft is created only when the gate passes. */
class BgOcrGateTest {

  private static final BigDecimal FLOOR = new BigDecimal("40");

  private OcrExtractResult good() {
    OcrExtractResult r = new OcrExtractResult();
    r.setSupplierName("DoStavchik EOOD");
    r.setSupplierEik("831641791");
    r.setInvoiceNumber("0000012345");
    r.setTotalGross(new BigDecimal("1200.00"));
    r.setConfidence(new BigDecimal("92.5"));
    return r;
  }

  @Test
  void passesOnGoodResult() {
    assertTrue(BgOcrGate.passes(good(), FLOOR));
  }

  @Test
  void failsWhenExtractionFailed() {
    OcrExtractResult r = good();
    r.setExtractionFailed(true);
    r.getErrors().add("no text");
    assertFalse(BgOcrGate.passes(r, FLOOR));
  }

  @Test
  void failsWhenInvoiceNumberMissing() {
    OcrExtractResult r = good();
    r.setInvoiceNumber("  ");
    assertFalse(BgOcrGate.passes(r, FLOOR));
  }

  @Test
  void failsWhenGrossMissingOrZero() {
    OcrExtractResult r = good();
    r.setTotalGross(BigDecimal.ZERO);
    assertFalse(BgOcrGate.passes(r, FLOOR));
    r.setTotalGross(null);
    assertFalse(BgOcrGate.passes(r, FLOOR));
  }

  @Test
  void failsWhenConfidenceBelowFloor() {
    OcrExtractResult r = good();
    r.setConfidence(new BigDecimal("12.0"));
    assertFalse(BgOcrGate.passes(r, FLOOR));
  }

  @Test
  void failsWhenSupplierMissing_butEikAloneIsEnough() {
    OcrExtractResult r = good();
    r.setSupplierName(null);
    r.setSupplierEik(null);
    assertFalse(BgOcrGate.passes(r, FLOOR));

    r.setSupplierEik("831641791"); // EIK alone satisfies the supplier requirement
    assertTrue(BgOcrGate.passes(r, FLOOR));
  }
}

package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractLine;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Pure tests for the single draft-move NET total used by the OCR intake (no Guice/DB). */
class BgOcrDocumentNetTest {

  private OcrExtractLine line(String net) {
    OcrExtractLine l = new OcrExtractLine();
    if (net != null) {
      l.setNetAmount(new BigDecimal(net));
    }
    return l;
  }

  @Test
  void prefersExtractedSubTotal() {
    OcrExtractResult r = new OcrExtractResult();
    r.setTotalBase(new BigDecimal("123.45"));
    r.getLines().add(line("10"));
    r.getLines().add(line("20"));
    assertEquals(new BigDecimal("123.45"), BgOcrIntakeService.documentNet(r));
  }

  @Test
  void fallsBackToSumOfLineNetsWhenNoSubTotal() {
    OcrExtractResult r = new OcrExtractResult();
    r.getLines().add(line("10.00"));
    r.getLines().add(line("20.50"));
    r.getLines().add(line(null)); // ignored
    assertEquals(new BigDecimal("30.50"), BgOcrIntakeService.documentNet(r));
  }

  @Test
  void nullWhenNoReliableAmount() {
    OcrExtractResult r = new OcrExtractResult();
    assertNull(BgOcrIntakeService.documentNet(r));
    r.setTotalBase(BigDecimal.ZERO); // non-positive is not "usable"
    assertNull(BgOcrIntakeService.documentNet(r));
  }

  @Test
  void zeroSubTotalFallsBackToPositiveLineSum() {
    OcrExtractResult r = new OcrExtractResult();
    r.setTotalBase(BigDecimal.ZERO);
    r.getLines().add(line("42"));
    assertEquals(new BigDecimal("42"), BgOcrIntakeService.documentNet(r));
  }
}

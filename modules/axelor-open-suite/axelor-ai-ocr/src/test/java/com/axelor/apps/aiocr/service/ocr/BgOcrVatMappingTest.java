package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Pure tests for the VAT-rate → BG purchase tax-code mapping. */
class BgOcrVatMappingTest {

  @Test
  void mapsCommonRates() {
    assertEquals(BgOcrVatMapping.BG_STD_20, BgOcrVatMapping.taxCodeForRate(new BigDecimal("20")));
    assertEquals(
        BgOcrVatMapping.BG_STD_20, BgOcrVatMapping.taxCodeForRate(new BigDecimal("20.00")));
    assertEquals(BgOcrVatMapping.BG_REDUCED_9, BgOcrVatMapping.taxCodeForRate(new BigDecimal("9")));
    assertEquals(BgOcrVatMapping.BG_ZERO, BgOcrVatMapping.taxCodeForRate(BigDecimal.ZERO));
  }

  @Test
  void unmappedRateReturnsNull() {
    assertNull(BgOcrVatMapping.taxCodeForRate(new BigDecimal("7")));
    assertNull(BgOcrVatMapping.taxCodeForRate(null));
  }
}

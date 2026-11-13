package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The closed allowlist of purchase tax codes.
 *
 * <p>It is what stops an output-VAT code — or a typo — from being attached to a supplier invoice
 * line, which would misstate the VAT ledger in a way that is hard to notice after the fact.
 */
class BgOcrVatMappingPurchaseCodesTest {

  @Test
  void onlyTheThreeBgPurchaseCodesAreAllowed() {
    assertEquals(3, BgOcrVatMapping.purchaseTaxCodes().size());
    assertTrue(BgOcrVatMapping.purchaseTaxCodes().contains("BG_SRP_20"));
    assertTrue(BgOcrVatMapping.purchaseTaxCodes().contains("BG_LRP_9"));
    assertTrue(BgOcrVatMapping.purchaseTaxCodes().contains("BG_ZRP"));
  }

  @Test
  void salesAndUnknownCodesAreRejected() {
    // A sales code on a purchase line is the mistake worth designing against.
    assertFalse(BgOcrVatMapping.purchaseTaxCodes().contains("BG_SSP_20"));
    assertFalse(BgOcrVatMapping.purchaseTaxCodes().contains("BG_SRP_20 "));
    assertFalse(BgOcrVatMapping.purchaseTaxCodes().contains("bg_srp_20"));
    assertFalse(BgOcrVatMapping.purchaseTaxCodes().contains(""));
  }

  @Test
  void theAllowlistCannotBeMutatedByACaller() {
    assertThrows(
        UnsupportedOperationException.class, () -> BgOcrVatMapping.purchaseTaxCodes().add("X"));
  }

  @Test
  void rateMappingIsUnchangedAndStillRefusesToGuess() {
    // Deliberate: an unmapped rate is usually a special regime, so it stays unmapped. C1 makes the
    // loss visible as a discrepancy instead of guessing a code.
    assertEquals("BG_SRP_20", BgOcrVatMapping.taxCodeForRate(new BigDecimal("20")));
    assertEquals("BG_LRP_9", BgOcrVatMapping.taxCodeForRate(new BigDecimal("9")));
    assertEquals("BG_ZRP", BgOcrVatMapping.taxCodeForRate(BigDecimal.ZERO));
    assertNull(BgOcrVatMapping.taxCodeForRate(new BigDecimal("7")));
    assertNull(BgOcrVatMapping.taxCodeForRate(null));
  }

  @Test
  void everyMappedRateProducesAnAllowlistedCode() {
    // The two must not drift: a rate that maps to a code the write path rejects would be a
    // pipeline that contradicts itself.
    for (String rate : new String[] {"20", "9", "0"}) {
      String code = BgOcrVatMapping.taxCodeForRate(new BigDecimal(rate));
      assertTrue(
          BgOcrVatMapping.purchaseTaxCodes().contains(code),
          "rate " + rate + " mapped to " + code + ", which is not allowlisted");
    }
  }
}

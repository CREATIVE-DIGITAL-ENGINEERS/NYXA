package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import org.junit.jupiter.api.Test;

/**
 * Supplier identity is the ЕИК only. A BG VAT number is {@code BG} + ЕИК, so it normalizes to the
 * same key; supplier name is never an identity key. OCR never creates a Partner — a missing/
 * ambiguous ЕИК leaves the draft for manual supplier selection (verified in the service).
 */
class BgOcrSupplierMatchTest {

  private static OcrExtractResult with(String eik, String vat, String name) {
    OcrExtractResult r = new OcrExtractResult();
    r.setSupplierEik(eik);
    r.setSupplierVat(vat);
    r.setSupplierName(name);
    return r;
  }

  @Test
  void usesExtractedEik() {
    assertEquals(
        "100000072", BgOcrIntakeService.eikKey(with("100000072", null, "ДЕМОВ БРАНД ЕООД")));
  }

  @Test
  void derivesEikFromBgVatWhenEikMissing() {
    // VAT is BG + ЕИК → normalize to the ЕИК key.
    assertEquals("100000072", BgOcrIntakeService.eikKey(with(null, "BG100000072", "ДУКОВ")));
    assertEquals("100000040", BgOcrIntakeService.eikKey(with("  ", "BG100000040", null)));
  }

  @Test
  void stripsBgPrefixFromEikField() {
    assertEquals("100000072", BgOcrIntakeService.eikKey(with("BG100000072", null, null)));
  }

  @Test
  void acceptsNineAndThirteenDigitEik() {
    assertEquals("1234567890123", BgOcrIntakeService.eikKey(with("1234567890123", null, null)));
    assertEquals("123456789", BgOcrIntakeService.eikKey(with("123456789", null, null)));
  }

  @Test
  void nullWhenNoReadableEik() {
    // No ЕИК and no VAT — a name must NEVER become the identity key.
    assertNull(BgOcrIntakeService.eikKey(with(null, null, "Some Supplier Ltd")));
    // A non-numeric / implausible token is not an ЕИК.
    assertNull(BgOcrIntakeService.eikKey(with("N/A", null, "Supplier")));
    assertNull(BgOcrIntakeService.eikKey(with("12345", null, null))); // too short
    assertNull(BgOcrIntakeService.eikKey(null));
  }
}

package com.axelor.apps.aiocr.service.record;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The amount rules a correction must satisfy, independent of any invoice or runtime. A correction
 * is a positive total, optionally with a coherent net/VAT breakdown — never a half-breakdown and
 * never an impossible VAT rate (which is how upload 70's garbage extraction slipped through in the
 * first place).
 */
class OcrDocumentTotalCorrectionAmountsTest {

  private static BigDecimal bd(String v) {
    return v == null ? null : new BigDecimal(v);
  }

  private static boolean check(String gross, String net, String vat) {
    return OcrDocumentTotalCorrectionService.checkAmounts(bd(gross), bd(net), bd(vat));
  }

  @Test
  void aTotalOnlyCorrectionIsAccepted() {
    assertFalse(check("86.73", null, null));
  }

  @Test
  void aCoherentBreakdownIsAccepted() {
    assertTrue(check("86.73", "72.27", "14.46"));
  }

  @Test
  void aMissingOrZeroTotalIsRefused() {
    assertThrows(OcrBlocked.class, () -> check(null, null, null));
    assertThrows(OcrBlocked.class, () -> check("0.00", null, null));
  }

  @Test
  void aHalfBreakdownIsRefused() {
    assertThrows(OcrBlocked.class, () -> check("86.73", "72.27", null));
    assertThrows(OcrBlocked.class, () -> check("86.73", null, "14.46"));
  }

  @Test
  void aBreakdownThatDoesNotAddUpIsRefused() {
    assertThrows(OcrBlocked.class, () -> check("86.73", "72.27", "10.00"));
  }

  @Test
  void anImpossibleVatRateIsRefused() {
    // upload 70's extracted breakdown — it balances, but VAT is ~1521% of net.
    assertThrows(OcrBlocked.class, () -> check("80.75", "4.98", "75.77"));
  }
}

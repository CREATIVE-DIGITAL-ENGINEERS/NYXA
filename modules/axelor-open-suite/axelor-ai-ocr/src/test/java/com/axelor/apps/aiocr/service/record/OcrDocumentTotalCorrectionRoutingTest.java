package com.axelor.apps.aiocr.service.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.account.db.Invoice;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * An accountant's correction must reach the validation gate and the VAT reconciler in place of the
 * extracted total, while leaving the extracted values untouched as evidence. These read the three
 * "trusted" accessors directly (same package), with no runtime — they only read invoice getters.
 *
 * <p>The case: extracted total 80.75 (a subtotal), corrected to the printed 86.73 with the real
 * breakdown 72.27 / 14.46.
 */
class OcrDocumentTotalCorrectionRoutingTest {

  private final OcrRoundingLineService service = new OcrRoundingLineService();

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  @Test
  void withNoCorrectionTheExtractedTotalIsUsed() {
    Invoice invoice = new Invoice();
    invoice.setOcrDocumentTotal(bd("80.75"));
    invoice.setOcrDocumentNetTotal(bd("72.27"));
    invoice.setOcrDocumentVatTotal(bd("14.46"));
    invoice.setOcrDocumentBreakdownAvailable(true);

    assertEquals(0, service.trustedTotal(invoice).compareTo(bd("80.75")));
    assertEquals(0, service.trustedNet(invoice).compareTo(bd("72.27")));
    assertEquals(0, service.trustedVat(invoice).compareTo(bd("14.46")));
  }

  @Test
  void aCorrectionOverridesTheExtractedTotalAndBreakdown() {
    Invoice invoice = new Invoice();
    // The garbage extraction stays in place as evidence...
    invoice.setOcrDocumentTotal(bd("80.75"));
    invoice.setOcrDocumentNetTotal(bd("4.98"));
    invoice.setOcrDocumentVatTotal(bd("75.77"));
    invoice.setOcrDocumentBreakdownAvailable(true);
    // ...but the correction is what validation now reconciles against.
    invoice.setOcrDocumentTotalCorrected(bd("86.73"));
    invoice.setOcrDocumentNetCorrected(bd("72.27"));
    invoice.setOcrDocumentVatCorrected(bd("14.46"));

    assertEquals(0, service.trustedTotal(invoice).compareTo(bd("86.73")));
    assertEquals(0, service.trustedNet(invoice).compareTo(bd("72.27")));
    assertEquals(0, service.trustedVat(invoice).compareTo(bd("14.46")));
  }

  @Test
  void aGrossOnlyCorrectionIgnoresTheExtractedBreakdown() {
    Invoice invoice = new Invoice();
    invoice.setOcrDocumentTotal(bd("80.75"));
    invoice.setOcrDocumentNetTotal(bd("4.98"));
    invoice.setOcrDocumentVatTotal(bd("75.77"));
    invoice.setOcrDocumentBreakdownAvailable(true);
    invoice.setOcrDocumentTotalCorrected(bd("86.73")); // no corrected net/VAT

    assertEquals(0, service.trustedTotal(invoice).compareTo(bd("86.73")));
    // The stale extracted breakdown must NOT leak back in once a correction exists.
    assertNull(service.trustedNet(invoice));
    assertNull(service.trustedVat(invoice));
  }
}

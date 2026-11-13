package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Recovering a VAT rate the document proves, and refusing every case where it does not. Pure — no
 * Guice, no JPA.
 */
class BgOcrVatRateInferenceTest {

  private static BgOcrVatRateInference infer(
      String net, String vat, String gross, String lineNetSum) {
    return BgOcrVatRateInference.infer(
        net == null ? null : new BigDecimal(net),
        vat == null ? null : new BigDecimal(vat),
        gross == null ? null : new BigDecimal(gross),
        lineNetSum == null ? null : new BigDecimal(lineNetSum));
  }

  @Test
  void invoice12_documentSummaryProvesTwentyPercent() {
    // Upload #41: document net 146.57, VAT 29.31, gross 175.88; extracted line nets sum to 146.56.
    // 146.57 x 20% = 29.3140 -> 29.31 and 146.56 x 20% = 29.3120 -> 29.31; 9% gives 13.19.
    BgOcrVatRateInference inference = infer("146.57", "29.31", "175.88", "146.56");

    assertTrue(inference.isInferred());
    assertEquals(new BigDecimal("20"), inference.getRate());
  }

  @Test
  void aGenuinelyZeroRatedDocumentRecoversNothing() {
    BgOcrVatRateInference inference = infer("146.56", "0.00", "146.56", "146.56");

    assertFalse(inference.isInferred());
    assertNull(inference.getRate());
    assertTrue(inference.getReason().contains("no VAT"), inference.getReason());
  }

  @Test
  void aDocumentWithNoVatTotalRecoversNothing() {
    assertFalse(infer("146.57", null, "175.88", "146.56").isInferred());
  }

  @Test
  void contradictoryDocumentTotalsFailClosed() {
    // net + VAT = 175.88 but the document prints 181.00 as its gross.
    BgOcrVatRateInference inference = infer("146.57", "29.31", "181.00", "146.56");

    assertFalse(inference.isInferred());
    assertTrue(inference.getReason().contains("contradict"), inference.getReason());
  }

  @Test
  void aLineSectionThatDoesNotMatchTheDocumentNetFailsClosed() {
    // The lines are 10.00 short of the document's net — far beyond the 0.02 rounding allowance.
    BgOcrVatRateInference inference = infer("146.57", "29.31", "175.88", "136.57");

    assertFalse(inference.isInferred());
    assertTrue(inference.getReason().contains("further apart"), inference.getReason());
  }

  @Test
  void aVatNoSupportedRateReproducesFailsClosed() {
    // 146.57 at 5% would be 7.33 — 5% is not a supported BG purchase rate, so nothing matches.
    BgOcrVatRateInference inference = infer("146.57", "7.33", "153.90", "146.57");

    assertFalse(inference.isInferred());
    assertTrue(inference.getReason().contains("no supported VAT rate"), inference.getReason());
  }

  @Test
  void aMixedRateDocumentIsNeverFlattenedOntoOneRate() {
    // 100.00 at 20% plus 50.00 at 9% = 24.50 VAT on a net of 150.00. No single supported rate
    // reproduces 24.50 (20% -> 30.00, 9% -> 13.50), so nothing is inferred.
    BgOcrVatRateInference inference = infer("150.00", "24.50", "174.50", "150.00");

    assertFalse(inference.isInferred());
    assertNull(inference.getRate());
  }

  @Test
  void theRefusalNeverSilentlyMeansZero() {
    // Every refusal must be a stated reason, so nothing downstream can read "no rate" as "0%".
    for (BgOcrVatRateInference inference :
        new BgOcrVatRateInference[] {
          infer("146.57", "29.31", "181.00", "146.56"),
          infer("146.57", "7.33", "153.90", "146.57"),
          infer("150.00", "24.50", "174.50", "150.00")
        }) {
      assertNull(inference.getRate());
      assertTrue(inference.getReason().startsWith("The VAT rate could not be recovered"));
    }
  }
}

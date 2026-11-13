package com.axelor.apps.aiocr.service.readiness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The boundary between "the supplier rounded" and "this invoice is wrong": one or two stotinki in
 * either direction is corrected, anything else is refused. Pure — no Guice, no JPA.
 */
class OcrTotalAdjustmentTest {

  private static OcrTotalAdjustment of(String trusted, String calculated) {
    return OcrTotalAdjustment.of(new BigDecimal(trusted), new BigDecimal(calculated));
  }

  @Test
  void aCentShortIsCorrectedUpwards() {
    OcrTotalAdjustment verdict = of("175.88", "175.87");

    assertEquals(OcrTotalAdjustment.Verdict.ADJUSTABLE, verdict.getVerdict());
    assertEquals(new BigDecimal("0.01"), verdict.getDifference());
  }

  @Test
  void twoCentsShortIsStillCorrected() {
    OcrTotalAdjustment verdict = of("175.88", "175.86");

    assertEquals(OcrTotalAdjustment.Verdict.ADJUSTABLE, verdict.getVerdict());
    assertEquals(new BigDecimal("0.02"), verdict.getDifference());
  }

  @Test
  void axelorBeingACentHigherGivesANegativeCorrection() {
    OcrTotalAdjustment verdict = of("175.88", "175.89");

    assertEquals(OcrTotalAdjustment.Verdict.ADJUSTABLE, verdict.getVerdict());
    assertEquals(new BigDecimal("-0.01"), verdict.getDifference());
  }

  @Test
  void axelorBeingTwoCentsHigherIsAlsoCorrected() {
    OcrTotalAdjustment verdict = of("175.88", "175.90");

    assertEquals(OcrTotalAdjustment.Verdict.ADJUSTABLE, verdict.getVerdict());
    assertEquals(new BigDecimal("-0.02"), verdict.getDifference());
  }

  @Test
  void threeCentsIsNoLongerRoundingAndIsRefused() {
    OcrTotalAdjustment verdict = of("175.88", "175.85");

    assertEquals(OcrTotalAdjustment.Verdict.TOO_LARGE, verdict.getVerdict());
    String message = verdict.describeRefusal();
    assertNotNull(message);
    // The three figures the accountant needs in order to act, all present.
    assertEquals(true, message.contains("175.88"), message);
    assertEquals(true, message.contains("175.85"), message);
    assertEquals(true, message.contains("0.03"), message);
  }

  @Test
  void upload70WrongSubtotalIsFarTooLargeAndMustBlock() {
    // Azure returned the fuel subtotal (80.75) as InvoiceTotal; the lines total the true gross
    // (86.72). 5.97 is an extraction error, never a rounding correction — it has to block.
    OcrTotalAdjustment verdict = of("80.75", "86.72");

    assertEquals(OcrTotalAdjustment.Verdict.TOO_LARGE, verdict.getVerdict());
    String message = verdict.describeRefusal();
    assertNotNull(message);
    assertEquals(true, message.contains("80.75"), message);
    assertEquals(true, message.contains("86.72"), message);
    assertEquals(true, message.contains("5.97"), message);
  }

  @Test
  void upload70CorrectedTotalLeavesExactlyOneCentToAdjust() {
    // Once the document total is corrected to the printed grand total (86.73), the one-cent gap
    // against the line-derived gross (86.72) is ordinary per-line rounding and follows the policy.
    OcrTotalAdjustment verdict = of("86.73", "86.72");

    assertEquals(OcrTotalAdjustment.Verdict.ADJUSTABLE, verdict.getVerdict());
    assertEquals(new BigDecimal("0.01"), verdict.getDifference());
  }

  @Test
  void equalTotalsNeedNothing() {
    assertEquals(OcrTotalAdjustment.Verdict.BALANCED, of("175.88", "175.88").getVerdict());
  }

  @Test
  void anInvoiceWithNoDocumentTotalIsNeverJudged() {
    OcrTotalAdjustment verdict = OcrTotalAdjustment.of(BigDecimal.ZERO, new BigDecimal("175.85"));

    assertEquals(OcrTotalAdjustment.Verdict.NO_TRUSTED_TOTAL, verdict.getVerdict());
    assertNull(verdict.getDifference());
    assertNull(verdict.describeRefusal());
  }
}

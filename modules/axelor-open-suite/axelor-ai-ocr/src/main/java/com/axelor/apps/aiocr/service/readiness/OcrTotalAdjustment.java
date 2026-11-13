package com.axelor.apps.aiocr.service.readiness;

import java.math.BigDecimal;

/**
 * The verdict on one supplier invoice's total: the gross the document printed against the gross
 * Axelor derives from the lines, and what may be done about the difference.
 *
 * <h2>Why a separate class from {@link OcrVatRoundingReconciler}</h2>
 *
 * They close different gaps and they are not alternatives. The reconciler closes the gap <em>inside
 * the VAT</em> — the supplier rounds VAT per line, Axelor rounds once per rate — by keeping the
 * supplier's amount on the summary row, which only works when the tax carries {@code
 * manageByAmount}. It runs on the draft, before validation, and it leaves the line section alone.
 *
 * <p>This one is the last resort for whatever is still missing once the invoice has actually been
 * computed: a rounding step that no VAT row can absorb (no {@code manageByAmount}, a reverse-charge
 * document, a per-line difference that never was VAT). It is booked as an ordinary expense line on
 * account 609 rather than hidden in the VAT, because that is what it is — a difference that is not
 * deductible input VAT and must not be declared as one.
 *
 * <p>Order matters and is enforced by where each is called: the reconciler runs when the OCR draft
 * is assembled, this one at Validate, on the already-recomputed totals. So a gap the reconciler
 * closed is simply not here any more, and there is no second path competing to fix the same cent.
 *
 * <h2>The rules</h2>
 *
 * <ul>
 *   <li>No trusted total, no verdict. Only a document carries an authoritative gross; a manually
 *       keyed invoice has nothing to be checked against, and inventing one from its own lines would
 *       make it agree with itself.
 *   <li>0.01 or 0.02, either sign — correctable. That is one or two rounding steps, which is what
 *       per-line rounding on a real document produces.
 *   <li>Anything larger is an extraction or data-entry error. It is never "corrected": a 3.40
 *       difference booked to 609 is a wrong invoice with a plug in it.
 * </ul>
 *
 * <p>Pure: no JPA, no Guice, no I/O.
 */
public final class OcrTotalAdjustment {

  /** The largest difference that may be booked as a rounding correction — two rounding steps. */
  public static final BigDecimal MAX_ADJUSTMENT = new BigDecimal("0.02");

  /** What may be done with the difference. */
  public enum Verdict {
    /** The invoice carries no authoritative document total, so nothing was compared. */
    NO_TRUSTED_TOTAL,
    /** The calculated total already equals the document's. */
    BALANCED,
    /** One or two rounding steps apart: a correction line settles it. */
    ADJUSTABLE,
    /** Too far apart to be rounding. The invoice itself has to be corrected. */
    TOO_LARGE
  }

  private final Verdict verdict;
  private final BigDecimal trusted;
  private final BigDecimal calculated;
  private final BigDecimal difference;

  private OcrTotalAdjustment(
      Verdict verdict, BigDecimal trusted, BigDecimal calculated, BigDecimal difference) {
    this.verdict = verdict;
    this.trusted = trusted;
    this.calculated = calculated;
    this.difference = difference;
  }

  /**
   * Compares the two totals.
   *
   * @param trusted the gross printed on the document, or null/zero when there is none
   * @param calculated the gross Axelor derives from the lines, <em>excluding</em> any correction
   *     line already present — otherwise a second pass would measure its own previous correction
   *     and cancel it
   */
  public static OcrTotalAdjustment of(BigDecimal trusted, BigDecimal calculated) {
    if (trusted == null || trusted.signum() == 0) {
      return new OcrTotalAdjustment(Verdict.NO_TRUSTED_TOTAL, trusted, calculated, null);
    }
    BigDecimal calc = calculated == null ? BigDecimal.ZERO : calculated;
    // Signed, and in this direction on purpose: it is the amount a line must carry for the total to
    // become the document's. Axelor being HIGHER gives a negative correction, which is a perfectly
    // ordinary credit on 609 and not a case that needs separate handling.
    BigDecimal difference = trusted.subtract(calc);
    if (difference.signum() == 0) {
      return new OcrTotalAdjustment(Verdict.BALANCED, trusted, calc, difference);
    }
    if (difference.abs().compareTo(MAX_ADJUSTMENT) <= 0) {
      return new OcrTotalAdjustment(Verdict.ADJUSTABLE, trusted, calc, difference);
    }
    return new OcrTotalAdjustment(Verdict.TOO_LARGE, trusted, calc, difference);
  }

  public Verdict getVerdict() {
    return verdict;
  }

  /** The document's own gross total. */
  public BigDecimal getTrusted() {
    return trusted;
  }

  /** What Axelor's lines add up to, without any correction line. */
  public BigDecimal getCalculated() {
    return calculated;
  }

  /**
   * Signed {@code trusted - calculated}: what a correction line must carry. Null when unchecked.
   */
  public BigDecimal getDifference() {
    return difference;
  }

  public boolean isAdjustable() {
    return verdict == Verdict.ADJUSTABLE;
  }

  public boolean isTooLarge() {
    return verdict == Verdict.TOO_LARGE;
  }

  /** The sentence shown when the difference is too large to correct. Null otherwise. */
  public String describeRefusal() {
    if (verdict != Verdict.TOO_LARGE) {
      return null;
    }
    return "The total printed on the document ("
        + trusted
        + ") and the total Axelor calculates from the lines ("
        + calculated
        + ") differ by "
        + difference.abs()
        + ". Only a rounding difference of up to "
        + MAX_ADJUSTMENT
        + " is corrected automatically. Correct the invoice lines, their quantities/prices or their"
        + " taxes so the calculated total matches the document, then validate again.";
  }
}

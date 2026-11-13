package com.axelor.apps.aiocr.service.ocr;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Recovers the VAT rate of a supplier invoice whose LINES carry no rate, from the totals its
 * SUMMARY prints — and refuses to do so on anything less than proof.
 *
 * <h2>The case</h2>
 *
 * Invoice #12 / upload #41. Azure returned ten items with amounts but no {@code TaxRate} and no
 * per-line {@code Tax}; the document summary printed net 146.57, VAT 29.31, gross 175.88. With no
 * rate, the normalisation derived a per-line VAT of zero, {@code deriveVatRate} read that zero as a
 * genuine 0% supply, and every line was mapped to {@code BG_ZRP}. The invoice was then booked at
 * 146.56 with no VAT — 29.31 short of what the supplier billed — and the only way out was to retype
 * the tax on ten lines by hand.
 *
 * <p>But the document proves the rate. 146.57 × 20% = 29.3140 → 29.31, and 146.56 × 20% = 29.3120 →
 * 29.31: the printed VAT is reproduced at 20% and at no other supported rate (9% would be 13.19).
 * That is not a guess, it is arithmetic with one solution.
 *
 * <h2>What counts as proof</h2>
 *
 * Every one of these, or nothing happens:
 *
 * <ul>
 *   <li>The document states net, VAT and gross, and the VAT is positive. A document that declares
 *       no VAT is a zero-rated document and keeps {@code BG_ZRP} — there is nothing to recover.
 *   <li>The document reconciles with itself: {@code net + VAT = gross}, within one rounding step
 *       per figure.
 *   <li>The extracted line nets sum to the document net within {@link #NET_TOLERANCE} (0.02) — the
 *       same small discrepancy the 609 reconciliation is allowed to close later. Further apart and
 *       the line section is not a faithful copy of this document, so its summary proves nothing
 *       about it.
 *   <li>Exactly ONE supported rate ({@link BgOcrVatMapping#supportedRates()}) reproduces the
 *       printed VAT, rounded the way a currency rounds, from the document net or from the line-net
 *       sum. Two candidates, or none, and the answer is unknown.
 * </ul>
 *
 * <p>No ratio thresholds, no "closest match", no reading rate words out of the text. A candidate
 * either reproduces the printed figure exactly after rounding or it is not a candidate. Everything
 * that is not a single unambiguous solution fails closed: the lines are left exactly as extracted
 * and a note says why, so a mixed-rate, reverse-charge or contradictory document is never flattened
 * to one rate — and never silently zero-rated either.
 *
 * <p>Pure: no JPA, no Guice, no I/O.
 */
public final class BgOcrVatRateInference {

  /** How far the line-net sum may sit from the document's own net — two rounding steps. */
  public static final BigDecimal NET_TOLERANCE = new BigDecimal("0.02");

  /** How far {@code net + VAT} may sit from the printed gross — one step per printed figure. */
  private static final BigDecimal HEADER_TOLERANCE = new BigDecimal("0.02");

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  private final BigDecimal rate;
  private final String reason;
  private final boolean documentZeroRated;

  private BgOcrVatRateInference(BigDecimal rate, String reason, boolean documentZeroRated) {
    this.rate = rate;
    this.reason = reason;
    this.documentZeroRated = documentZeroRated;
  }

  /**
   * True when the document itself states a VAT of zero — a positive fact, not a missing one.
   *
   * <p>The caller needs the distinction: this is the one refusal where mapping the lines to {@code
   * BG_ZRP} is the right answer rather than a fallback. Every other refusal leaves the rate unknown
   * so the invoice stays to review, because silently zero-rating an unknown is the bug this whole
   * class exists to end.
   */
  public boolean isDocumentZeroRated() {
    return documentZeroRated;
  }

  /** The recovered rate as a percentage, or null when nothing could be proved. */
  public BigDecimal getRate() {
    return rate;
  }

  /** True when exactly one supported rate reproduces the document's VAT. */
  public boolean isInferred() {
    return rate != null;
  }

  /** One sentence for the review notes — why a rate was recovered, or why none was. */
  public String getReason() {
    return reason;
  }

  /**
   * Works out the single rate the document's summary proves, if there is one.
   *
   * @param documentNet the net the document prints
   * @param documentVat the VAT the document prints
   * @param documentGross the gross the document prints
   * @param lineNetSum the sum of the extracted line nets
   */
  public static BgOcrVatRateInference infer(
      BigDecimal documentNet,
      BigDecimal documentVat,
      BigDecimal documentGross,
      BigDecimal lineNetSum) {

    if (documentVat == null) {
      return refuse("the document does not state a VAT total");
    }
    if (documentVat.signum() <= 0) {
      return new BgOcrVatRateInference(
          null, "The document states no VAT, so its lines are genuinely zero-rated.", true);
    }
    if (documentNet == null || documentGross == null) {
      return refuse("the document does not state both a net and a gross total");
    }
    if (documentNet.add(documentVat).subtract(documentGross).abs().compareTo(HEADER_TOLERANCE)
        > 0) {
      return refuse(
          "the document's own totals contradict each other (net "
              + documentNet
              + " + VAT "
              + documentVat
              + " is not gross "
              + documentGross
              + ")");
    }
    if (lineNetSum == null) {
      return refuse("the extracted lines carry no net amounts");
    }
    if (lineNetSum.subtract(documentNet).abs().compareTo(NET_TOLERANCE) > 0) {
      return refuse(
          "the extracted lines total "
              + lineNetSum
              + " but the document's net is "
              + documentNet
              + ", which is further apart than rounding explains");
    }

    List<BigDecimal> matches = new ArrayList<>();
    for (BigDecimal candidate : BgOcrVatMapping.supportedRates()) {
      if (candidate.signum() <= 0) {
        continue; // a zero rate cannot produce the positive VAT this document prints
      }
      if (reproducesVat(documentNet, candidate, documentVat)
          || reproducesVat(lineNetSum, candidate, documentVat)) {
        matches.add(candidate);
      }
    }

    if (matches.isEmpty()) {
      return refuse(
          "no supported VAT rate reproduces the document's VAT of "
              + documentVat
              + " from its net of "
              + documentNet);
    }
    if (matches.size() > 1) {
      return refuse(
          "more than one VAT rate reproduces the document's VAT of "
              + documentVat
              + ", so the rate is ambiguous");
    }

    BigDecimal only = matches.get(0);
    return new BgOcrVatRateInference(
        only,
        "The document states VAT of "
            + documentVat
            + " on a net of "
            + documentNet
            + " but prints no rate on its lines. "
            + only
            + "% is the only supported rate that reproduces that VAT, so every line was set to it;"
            + " check it against the document before validating.",
        false);
  }

  /** {@code round(base × rate%)} against the printed VAT — currency rounding, exact comparison. */
  private static boolean reproducesVat(BigDecimal base, BigDecimal rate, BigDecimal documentVat) {
    if (base == null || base.signum() <= 0) {
      return false;
    }
    BigDecimal derived =
        base.multiply(rate)
            .divide(HUNDRED, 2, RoundingMode.HALF_UP)
            .setScale(2, RoundingMode.HALF_UP);
    return derived.compareTo(documentVat.setScale(2, RoundingMode.HALF_UP)) == 0;
  }

  private static BgOcrVatRateInference refuse(String reason) {
    return new BgOcrVatRateInference(
        null,
        "The VAT rate could not be recovered from the document totals: " + reason + ".",
        false);
  }
}

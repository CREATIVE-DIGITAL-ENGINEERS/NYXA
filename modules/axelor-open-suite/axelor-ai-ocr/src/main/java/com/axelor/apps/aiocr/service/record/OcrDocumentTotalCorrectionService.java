package com.axelor.apps.aiocr.service.record;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiocr.db.repo.OcrInvoiceWriteGuard;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Lets an accountant correct a mistaken OCR document total from the original document, so a draft
 * the extraction got wrong can be validated for the right amount.
 *
 * <h2>Why this exists</h2>
 *
 * Azure sometimes returns a subtotal, or a stray line amount, as the invoice total. The measured
 * case is a ТЕСТЕКС fuel receipt — upload 95 / invoice 41 in the current database, the same
 * document the earlier upload 70 / invoice 28 note described before that pair was re-imported. Its
 * text prints {@code ОБЩА СУМА ЕВРО … 86.73} with {@code ВСИЧКО 80.75} as the fuel subtotal above
 * it, and Azure returned the subtotal: {@code InvoiceTotal=80.75}. The validation gate ({@link
 * OcrRoundingLineService}) then rightly refuses — the document total (80.75) and the line-derived
 * total (86.72) differ by far more than rounding — but there was no way to tell it the real total
 * without rewriting the lines to agree with a wrong figure, which is exactly what must never
 * happen.
 *
 * <h2>What it does, and does not do</h2>
 *
 * <ul>
 *   <li><b>Preserves the extraction.</b> The extracted {@code ocrDocumentTotal/Net/Vat} are left
 *       untouched as evidence. The correction lives in its own columns and only <em>overrides</em>
 *       them where a trusted total is read.
 *   <li><b>Reaches validation and the VAT reconciler automatically.</b> {@link
 *       OcrRoundingLineService#trustedTotal}/{@code trustedNet}/{@code trustedVat} prefer the
 *       correction, so the next Validate reconciles against the corrected figures with no further
 *       wiring — the one-cent gap left on that receipt (86.73 printed against the 86.72 its lines
 *       calculate) then follows the ordinary adjustment policy.
 *   <li><b>Never touches the lines or the header totals.</b> Those remain the independent check on
 *       the line section; this only corrects the document figure they are checked against.
 *   <li><b>Is gated.</b> Company scope, OCR provenance, draft-only status and Axelor's own write
 *       permission are all enforced by {@link OcrDraftInvoiceGuard}.
 *   <li><b>Is audited.</b> Who corrected it, when, and the note they left are stored beside the
 *       corrected amounts and written only together with them.
 * </ul>
 */
@Singleton
public class OcrDocumentTotalCorrectionService {

  /** Two rounding steps: the most a coherent net + VAT may differ from the gross. */
  private static final BigDecimal COHERENCE_TOLERANCE = new BigDecimal("0.02");

  /** The largest VAT-to-net ratio a real breakdown can show (BG rates top out at 20%). */
  private static final BigDecimal MAX_PLAUSIBLE_VAT_RATE = new BigDecimal("0.30");

  /**
   * What a correction changed, for the caller's confirmation message.
   *
   * <p>Carries the whole before/after pair and the audit, not just the new total. A caller that has
   * to report "80.75 became 86.73, read from ОБЩА СУМА ЕВРО, by X at T" would otherwise have to
   * re-read the invoice to find out what it had just written — and the extracted figures it needs
   * for the "before" half are precisely the ones the correction is about to stop being used. The
   * constructor is public so a caller's test can state an expected outcome without a database.
   */
  public static final class Result {
    private final BigDecimal previousExtractedTotal;
    private final BigDecimal previousExtractedNet;
    private final BigDecimal previousExtractedVat;
    private final BigDecimal correctedTotal;
    private final BigDecimal correctedNet;
    private final BigDecimal correctedVat;
    private final boolean breakdownSupplied;
    private final String correctedBy;
    private final LocalDateTime correctedOn;
    private final String reason;

    public Result(
        BigDecimal previousExtractedTotal,
        BigDecimal previousExtractedNet,
        BigDecimal previousExtractedVat,
        BigDecimal correctedTotal,
        BigDecimal correctedNet,
        BigDecimal correctedVat,
        boolean breakdownSupplied,
        String correctedBy,
        LocalDateTime correctedOn,
        String reason) {
      this.previousExtractedTotal = previousExtractedTotal;
      this.previousExtractedNet = previousExtractedNet;
      this.previousExtractedVat = previousExtractedVat;
      this.correctedTotal = correctedTotal;
      this.correctedNet = correctedNet;
      this.correctedVat = correctedVat;
      this.breakdownSupplied = breakdownSupplied;
      this.correctedBy = correctedBy;
      this.correctedOn = correctedOn;
      this.reason = reason;
    }

    public BigDecimal getPreviousExtractedTotal() {
      return previousExtractedTotal;
    }

    /** The extracted net, untouched by the correction and kept as evidence. */
    public BigDecimal getPreviousExtractedNet() {
      return previousExtractedNet;
    }

    /** The extracted VAT, untouched by the correction and kept as evidence. */
    public BigDecimal getPreviousExtractedVat() {
      return previousExtractedVat;
    }

    public BigDecimal getCorrectedTotal() {
      return correctedTotal;
    }

    /** The corrected net, or null on the gross-only path. */
    public BigDecimal getCorrectedNet() {
      return correctedNet;
    }

    /** The corrected VAT, or null on the gross-only path. */
    public BigDecimal getCorrectedVat() {
      return correctedVat;
    }

    public boolean isBreakdownSupplied() {
      return breakdownSupplied;
    }

    /** Who made the correction, or null for a system-scoped caller with no user. */
    public String getCorrectedBy() {
      return correctedBy;
    }

    public LocalDateTime getCorrectedOn() {
      return correctedOn;
    }

    /** The note saying where the figure was read, exactly as it was stored. */
    public String getReason() {
      return reason;
    }
  }

  /**
   * Records a corrected document total (and, optionally, a corrected net/VAT breakdown) on a draft
   * OCR invoice.
   *
   * @param invoiceId the draft invoice to correct
   * @param gross the correct printed grand total; required and positive
   * @param net the correct printed net, or null for the gross-only path
   * @param vat the correct printed VAT, or null for the gross-only path
   * @param reason a short note saying where on the document the figure was read
   * @throws OcrBlocked when the caller is out of scope, the invoice is not a correctable OCR draft,
   *     the caller lacks write permission, or the amounts are not a coherent correction
   */
  @Transactional
  public Result correct(
      long invoiceId, BigDecimal gross, BigDecimal net, BigDecimal vat, String reason) {
    Invoice invoice = OcrDraftInvoiceGuard.resolveDraftInvoiceForCorrection(invoiceId);

    BigDecimal correctedGross = scale(gross);
    BigDecimal correctedNet = scale(net);
    BigDecimal correctedVat = scale(vat);
    boolean breakdown = checkAmounts(correctedGross, correctedNet, correctedVat);

    BigDecimal previousExtracted = invoice.getOcrDocumentTotal();
    BigDecimal previousExtractedNet = invoice.getOcrDocumentNetTotal();
    BigDecimal previousExtractedVat = invoice.getOcrDocumentVatTotal();
    User actor = AuthUtils.getUser();
    LocalDateTime when = LocalDateTime.now();
    String note = trimToNull(reason);
    invoice.setOcrDocumentTotalCorrected(correctedGross);
    invoice.setOcrDocumentNetCorrected(breakdown ? correctedNet : null);
    invoice.setOcrDocumentVatCorrected(breakdown ? correctedVat : null);
    invoice.setOcrDocumentTotalCorrectedBy(actor);
    invoice.setOcrDocumentTotalCorrectedOn(when);
    invoice.setOcrDocumentTotalCorrectionReason(note);

    // JPA.save, not InvoiceRepository.save: the repository chain recomputes line amounts and pack
    // totals on save (see OcrInvoiceLineEditService), which has nothing to do here — only metadata
    // columns changed — and recomputing could disturb the line section this correction must leave
    // exactly as it is.
    //
    // The flush MUST happen inside the trusted scope: OcrInvoiceImmutabilityListener reverts any
    // change to these columns flushed OUTSIDE a trusted write, and this method's own @Transactional
    // commit would otherwise flush the correction after the scope closed — the guard would silently
    // undo it. Flushing here fires the Hibernate PreUpdate while the marker is set.
    OcrInvoiceWriteGuard.runTrusted(
        () -> {
          JPA.save(invoice);
          JPA.em().flush();
        });
    return new Result(
        previousExtracted,
        previousExtractedNet,
        previousExtractedVat,
        correctedGross,
        breakdown ? correctedNet : null,
        breakdown ? correctedVat : null,
        breakdown,
        actor == null ? null : actor.getCode(),
        when,
        note);
  }

  /**
   * Validates the corrected amounts, independently of any invoice so it can be tested on its own.
   *
   * @return true when a coherent net/VAT breakdown was supplied, false for the gross-only case
   * @throws OcrBlocked when the gross is missing or non-positive, only one of net/VAT is given, or
   *     a supplied breakdown does not add up to the gross or implies an impossible VAT rate
   */
  public static boolean checkAmounts(BigDecimal gross, BigDecimal net, BigDecimal vat) {
    if (gross == null || gross.signum() <= 0) {
      throw new OcrBlocked(
          AiBlockerCode.BAD_ARGUMENTS,
          "A positive corrected document total is required, read from the original document.");
    }
    boolean netGiven = net != null && net.signum() != 0;
    boolean vatGiven = vat != null && vat.signum() != 0;
    if (!netGiven && !vatGiven) {
      return false; // gross-only correction
    }
    if (!netGiven || !vatGiven || net.signum() <= 0 || vat.signum() < 0) {
      throw new OcrBlocked(
          AiBlockerCode.BAD_ARGUMENTS,
          "Give both a positive net and its VAT, or leave both empty to correct only the total.");
    }
    if (net.add(vat).subtract(gross).abs().compareTo(COHERENCE_TOLERANCE) > 0) {
      throw new OcrBlocked(
          AiBlockerCode.AMOUNTS_DO_NOT_RECONCILE,
          "The corrected net ("
              + net
              + ") plus VAT ("
              + vat
              + ") must equal the corrected total ("
              + gross
              + ").");
    }
    if (vat.compareTo(net.multiply(MAX_PLAUSIBLE_VAT_RATE)) > 0) {
      throw new OcrBlocked(
          AiBlockerCode.AMOUNTS_DO_NOT_RECONCILE,
          "A VAT of "
              + vat
              + " on a net of "
              + net
              + " is not a real VAT rate; re-read the net and VAT from the document.");
    }
    return true;
  }

  private static BigDecimal scale(BigDecimal v) {
    return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
  }

  private static String trimToNull(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }
}

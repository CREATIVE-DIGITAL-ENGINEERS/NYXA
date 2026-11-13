package com.axelor.apps.aiocr.service.readiness;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoiceLineTax;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import java.math.BigDecimal;
import java.util.List;

/**
 * Closes the one-cent gap between a supplier's per-line VAT and Axelor's per-rate VAT, so an
 * otherwise correct OCR draft can actually be posted for the amount the supplier billed.
 *
 * <h2>The problem</h2>
 *
 * Bulgarian suppliers routinely round VAT <em>per line</em> and print a grand total that is the sum
 * of the per-line gross amounts. Axelor rounds <em>once per rate</em>: {@code
 * TaxInvoiceLine.computeAndAddInvoiceLineTax} computes {@code round(Σ line.exTaxTotal × rate)} in a
 * single multiplication. Whenever the per-line rounding accumulates a cent, the two disagree.
 *
 * <p>Worked example — invoice 0900092440, ten lines at 20%:
 *
 * <pre>
 *   Σ line.exTaxTotal          146.56
 *   Σ line.inTaxTotal          175.88   == the printed invoice total
 *   per-line VAT                29.32   (Σ inTax − Σ exTax)
 *   round(146.56 × 20%)         29.31   ← what TaxInvoiceLine produces
 * </pre>
 *
 * The payable credit is the printed gross (175.88) while the debits are 146.56 + 29.31 = 175.87, so
 * {@link OcrAccountingTotals#postingDelta()} is −0.01 and ventilation is blocked. Left alone,
 * validation would recompute the header from the lines and post 175.87 — a cent less than the
 * supplier billed, silently.
 *
 * <p>{@code InvoiceLine} has no per-line VAT field at all (only {@code exTaxTotal}, {@code
 * inTaxTotal} and {@code taxLineSet}), so the generator has nothing better it could have used.
 *
 * <h2>The mechanism used here</h2>
 *
 * Axelor supports a manual VAT amount on a summary row, gated on the tax's {@code manageByAmount}
 * flag:
 *
 * <ul>
 *   <li>{@code InvoiceGenerator.getUpdatedInvoiceLineTax} keeps rows where {@code
 *       isManageByAmount(row) && taxTotal != percentageTaxTotal}, discarding and regenerating every
 *       other row.
 *   <li>{@code TaxInvoiceLine.computeAndAddInvoiceLineTax} then reuses such a kept row verbatim,
 *       provided its {@code percentageTaxTotal} still equals the freshly derived figure and its
 *       {@code exTaxBase}, {@code vatSystemSelect} and {@code imputedAccount} match.
 * </ul>
 *
 * So the override goes on {@code taxTotal} while {@code percentageTaxTotal} keeps the derived
 * value. That pair is what makes the correction survive {@code InvoiceService.validate}, which is
 * the whole point: without it the blocker would clear on the draft and the cent would be lost at
 * posting anyway.
 *
 * <h2>What it refuses to do</h2>
 *
 * The guard that separates "the supplier rounded per line" from "the extraction is wrong" is the
 * size of the gap between what Axelor would actually post ({@code Σ line.exTaxTotal + Σ
 * row.taxTotal}) and the printed OCR total: exactly one cent, in either direction. That total is
 * the OCR-authoritative gross ({@link Invoice#getInTaxTotal()} as populated from Azure's structured
 * {@code InvoiceTotal}), not the sum of Azure's independently-reported per-line gross amounts —
 * those can themselves be a cent off the document total, so requiring them to match it first (as an
 * earlier version of this class did) missed genuine one-cent cases. Any gap other than exactly one
 * cent is left to the existing mismatch blocker — this class never makes a wrong line section
 * reconcile with itself.
 *
 * <p>Pure: no JPA, no Guice, no I/O beyond reading and writing the entities handed to it.
 */
public final class OcrVatRoundingReconciler {

  private OcrVatRoundingReconciler() {}

  /** What {@link #apply(Invoice)} did, or why it declined to do anything. */
  public static final class Outcome {
    private final boolean applied;
    private final BigDecimal delta;
    private final BigDecimal companyDelta;
    private final String targetTaxCode;
    private final String refusalReason;

    private Outcome(
        boolean applied,
        BigDecimal delta,
        BigDecimal companyDelta,
        String targetTaxCode,
        String refusalReason) {
      this.applied = applied;
      this.delta = delta;
      this.companyDelta = companyDelta;
      this.targetTaxCode = targetTaxCode;
      this.refusalReason = refusalReason;
    }

    static Outcome nothingToDo() {
      return new Outcome(false, BigDecimal.ZERO, BigDecimal.ZERO, null, null);
    }

    static Outcome refused(String reason) {
      return new Outcome(false, BigDecimal.ZERO, BigDecimal.ZERO, null, reason);
    }

    static Outcome refused(String reason, BigDecimal delta, String taxCode) {
      return new Outcome(false, delta, BigDecimal.ZERO, taxCode, reason);
    }

    static Outcome applied(BigDecimal delta, BigDecimal companyDelta, String taxCode) {
      return new Outcome(true, delta, companyDelta, taxCode, null);
    }

    /** True when a summary row's VAT was adjusted. */
    public boolean isApplied() {
      return applied;
    }

    /** The invoice-currency correction that was (or would have been) applied. */
    public BigDecimal getDelta() {
      return delta;
    }

    /** The company-currency correction, derived independently of {@link #getDelta()}. */
    public BigDecimal getCompanyDelta() {
      return companyDelta;
    }

    /** Code of the tax whose row was adjusted, or which blocked the adjustment. */
    public String getTargetTaxCode() {
      return targetTaxCode;
    }

    /**
     * Null when nothing needed doing or the adjustment was applied; otherwise why it was declined.
     */
    public String getRefusalReason() {
      return refusalReason;
    }

    /** True when a rounding gap exists that only a missing {@code manageByAmount} flag prevents. */
    public boolean isBlockedByTaxConfig() {
      return refusalReason != null && targetTaxCode != null;
    }

    /** One readable sentence for an audit note; null when there is nothing to say. */
    public String describe() {
      if (applied) {
        return "VAT rounding: adjusted the "
            + targetTaxCode
            + " summary row by "
            + delta
            + " so the debits match the invoice total the supplier printed (the supplier rounds VAT"
            + " per line; Axelor rounds once per rate).";
      }
      return refusalReason;
    }
  }

  /**
   * Adjusts one {@code InvoiceLineTax} row so that {@code Σ line.exTaxTotal + Σ row.taxTotal}
   * equals the printed (OCR-authoritative) gross, when and only when the gap between the two is
   * exactly one rounding step.
   *
   * <p>Must run after the summary has been generated and after the lines carry their
   * company-currency amounts. Idempotent: a second call re-derives a delta of zero.
   */
  public static Outcome apply(Invoice invoice) {
    return analyse(
        invoice,
        invoice == null ? null : invoice.getInTaxTotal(),
        invoice == null ? null : invoice.getCompanyInTaxTotal(),
        true);
  }

  /**
   * The same one-step correction, aimed at the VAT the document PRINTS rather than at its gross.
   *
   * <h2>Why the gross is not always the right target</h2>
   *
   * Driving this from the printed gross assumes the line net is right and the VAT absorbs the cent.
   * That held while the gross was the only durable figure — but when the document's own net and VAT
   * are known, the assumption can be exactly backwards. Invoice #14: lines summing to 146.56
   * against a printed net of 146.57 and VAT of 29.31. Aimed at the gross, this class pushed the 20%
   * row from 29.31 to 29.32 so that 146.56 + 29.32 reached the printed 175.88 — the right total
   * built from the wrong two halves, with a cent of net declared as VAT.
   *
   * <p>Given the printed VAT, the target becomes {@code Σ line.exTaxTotal + printedVat}, so the
   * same machinery moves the summary row ONTO the declared VAT (29.32 back down to 29.31) and
   * leaves the net cent to be booked where it belongs — an expense line on 609. Same guard, same
   * {@code manageByAmount} mechanism, same one-step limit; only the target differs.
   *
   * <p>Idempotent: once the summary carries the printed VAT the derived delta is zero.
   */
  public static Outcome alignToPrintedVat(
      Invoice invoice, BigDecimal printedVat, BigDecimal printedVatInCompanyCurrency) {
    if (invoice == null || printedVat == null || printedVatInCompanyCurrency == null) {
      return Outcome.nothingToDo();
    }
    List<InvoiceLine> lines = invoice.getInvoiceLineList();
    if (lines == null || lines.isEmpty()) {
      return Outcome.nothingToDo();
    }
    BigDecimal lineNetSum = BigDecimal.ZERO;
    BigDecimal companyNetSum = BigDecimal.ZERO;
    for (InvoiceLine line : lines) {
      if (line.getExTaxTotal() == null) {
        return Outcome.nothingToDo();
      }
      lineNetSum = lineNetSum.add(line.getExTaxTotal());
      companyNetSum = companyNetSum.add(nz(line.getCompanyExTaxTotal()));
    }
    // Both targets are built the same way — net plus the declared VAT — so the company side moves
    // by its own figure rather than inheriting a converted cent, exactly as the gross-aimed path
    // already derives its company delta independently.
    return analyse(
        invoice, lineNetSum.add(printedVat), companyNetSum.add(printedVatInCompanyCurrency), true);
  }

  /**
   * The same analysis as {@link #apply(Invoice)}, without writing anything. Lets a readiness check
   * say <em>why</em> a posting gap exists — in particular that it is per-line VAT rounding waiting
   * on a {@code manageByAmount} flag — without correcting it as a side effect of reporting.
   */
  public static Outcome diagnose(Invoice invoice) {
    return analyse(
        invoice,
        invoice == null ? null : invoice.getInTaxTotal(),
        invoice == null ? null : invoice.getCompanyInTaxTotal(),
        false);
  }

  private static Outcome analyse(
      Invoice invoice, BigDecimal targetGross, BigDecimal targetCompanyGross, boolean write) {
    if (invoice == null
        || invoice.getStatusSelect() == null
        || invoice.getStatusSelect() != InvoiceRepository.STATUS_DRAFT) {
      return Outcome.nothingToDo();
    }
    List<InvoiceLine> lines = invoice.getInvoiceLineList();
    List<InvoiceLineTax> rows = invoice.getInvoiceLineTaxList();
    if (lines == null || lines.isEmpty() || rows == null || rows.isEmpty()) {
      return Outcome.nothingToDo();
    }

    BigDecimal printedGross = targetGross;
    if (printedGross == null || printedGross.signum() == 0) {
      return Outcome.nothingToDo();
    }

    // A reverse-charge or non-deductible row is not a plain base x rate, so the arithmetic below
    // would not describe it. Those documents keep the existing blocker.
    for (InvoiceLineTax row : rows) {
      if (Boolean.TRUE.equals(row.getReverseCharged())) {
        return Outcome.refused(
            "VAT rounding was not adjusted: the invoice carries a reverse-charge VAT row.");
      }
      Tax tax = taxOf(row);
      if (tax != null && Boolean.TRUE.equals(tax.getIsNonDeductibleTax())) {
        return Outcome.refused(
            "VAT rounding was not adjusted: the invoice carries a non-deductible VAT row.");
      }
    }

    BigDecimal lineNetSum = BigDecimal.ZERO;
    for (InvoiceLine line : lines) {
      if (line.getExTaxTotal() == null) {
        return Outcome.nothingToDo();
      }
      lineNetSum = lineNetSum.add(line.getExTaxTotal());
    }

    BigDecimal summaryVat = BigDecimal.ZERO;
    for (InvoiceLineTax row : rows) {
      summaryVat = summaryVat.add(nz(row.getTaxTotal()));
    }
    // What Axelor will actually post — Σ line.exTaxTotal + Σ row.taxTotal — against the printed
    // (OCR-authoritative) gross. Earlier this method first demanded that Σ line.inTaxTotal
    // reproduce the printed gross exactly before looking at this delta at all; Azure's own
    // per-line gross figures can themselves be a cent off the document total it reports, which
    // made that guard bail on genuine one-cent cases instead of fixing them. The delta below is
    // the only thing that determines whether this is "genuine rounding" or "bad data".
    BigDecimal delta = printedGross.subtract(lineNetSum).subtract(summaryVat);
    if (delta.signum() == 0) {
      return Outcome.nothingToDo();
    }

    // The OCR total is authoritative only for a genuine rounding delta: exactly one cent, in
    // either direction. Anything else is treated as an extraction error and left to the existing
    // mismatch blocker rather than silently changed.
    if (delta.abs().compareTo(OcrAmountReconciliation.ROUNDING_STEP) != 0) {
      return Outcome.refused(
          "VAT rounding was not adjusted: the difference of "
              + delta
              + " is larger than a single rounding step, so the invoice total is not treated as"
              + " authoritative.");
    }

    InvoiceLineTax target = largestBaseRow(rows);
    Tax targetTax = taxOf(target);
    String taxCode = targetTax == null ? null : targetTax.getCode();
    if (targetTax == null || !Boolean.TRUE.equals(targetTax.getManageByAmount())) {
      return Outcome.refused(
          "The supplier rounds VAT per line, so the debits are "
              + delta.negate()
              + " away from the invoice total. Axelor can only keep a supplier-declared VAT amount"
              + " on a tax configured with \"Manage by amount\", and "
              + (taxCode == null ? "this row carries no tax" : taxCode + " is not")
              + ". Set that flag on the tax to let this correct itself.",
          delta,
          taxCode);
    }

    // The company-currency correction is derived from the company-currency figures, never from
    // delta: the two currencies can differ, and a converted cent is not necessarily a cent.
    BigDecimal companyGross = targetCompanyGross;
    if (companyGross == null || companyGross.signum() == 0) {
      return Outcome.refused(
          "VAT rounding was not adjusted: the invoice has no company-currency total to correct"
              + " against.");
    }
    BigDecimal companyNetSum = BigDecimal.ZERO;
    for (InvoiceLine line : lines) {
      if (line.getCompanyExTaxTotal() == null && line.getExTaxTotal().signum() != 0) {
        return Outcome.refused(
            "VAT rounding was not adjusted: a line has no company-currency amount, so the"
                + " correction cannot be expressed in the company currency.");
      }
      companyNetSum = companyNetSum.add(nz(line.getCompanyExTaxTotal()));
    }
    BigDecimal companySummaryVat = BigDecimal.ZERO;
    for (InvoiceLineTax row : rows) {
      companySummaryVat = companySummaryVat.add(nz(row.getCompanyTaxTotal()));
    }
    BigDecimal companyDelta = companyGross.subtract(companyNetSum).subtract(companySummaryVat);

    if (!write) {
      return Outcome.applied(delta, companyDelta, taxCode);
    }
    // percentageTaxTotal deliberately keeps the derived figure: it is what InvoiceGenerator and
    // TaxInvoiceLine compare against to recognise this row as a manual override and preserve it.
    target.setTaxTotal(nz(target.getTaxTotal()).add(delta));
    target.setInTaxTotal(nz(target.getExTaxBase()).add(target.getTaxTotal()));
    target.setCompanyTaxTotal(nz(target.getCompanyTaxTotal()).add(companyDelta));
    target.setCompanyInTaxTotal(nz(target.getCompanyExTaxBase()).add(target.getCompanyTaxTotal()));
    return Outcome.applied(delta, companyDelta, taxCode);
  }

  /** The rate carrying the most base absorbs the cent — the smallest relative distortion. */
  private static InvoiceLineTax largestBaseRow(List<InvoiceLineTax> rows) {
    InvoiceLineTax best = rows.get(0);
    for (InvoiceLineTax row : rows) {
      if (nz(row.getExTaxBase()).abs().compareTo(nz(best.getExTaxBase()).abs()) > 0) {
        best = row;
      }
    }
    return best;
  }

  private static Tax taxOf(InvoiceLineTax row) {
    TaxLine taxLine = row == null ? null : row.getTaxLine();
    return taxLine == null ? null : taxLine.getTax();
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}

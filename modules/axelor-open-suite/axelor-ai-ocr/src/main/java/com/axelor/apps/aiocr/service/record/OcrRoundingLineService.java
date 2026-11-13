package com.axelor.apps.aiocr.service.record;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoiceLineTax;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.account.db.repo.AccountTypeRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.TaxRepository;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.account.service.invoice.InvoiceLineService;
import com.axelor.apps.account.service.invoice.InvoiceService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceLineUnitDefaultService;
import com.axelor.apps.aiocr.service.ocr.BgOcrVatMapping;
import com.axelor.apps.aiocr.service.readiness.OcrTotalAdjustment;
import com.axelor.apps.aiocr.service.readiness.OcrVatRoundingReconciler;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.base.service.tax.TaxService;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Makes a supplier invoice post for the amount the supplier actually billed, or refuses to let it
 * post at all.
 *
 * <h2>What it does</h2>
 *
 * The work happens at DRAFT time ({@link #prepareDraft}), so the accountant sees the final totals
 * and the correction line before pressing Validate; validation itself only re-checks. What is
 * compared is the document's own figures ({@code Invoice.ocrDocumentTotal} and, when the extraction
 * supplied a coherent breakdown, {@code ocrDocumentNetTotal} / {@code ocrDocumentVatTotal}) against
 * what Axelor computes from the lines.
 *
 * <ul>
 *   <li>Equal — nothing happens.
 *   <li>0.01 or 0.02 apart, either direction — exactly one correction line is created or updated,
 *       on the rounding account of <em>this invoice's company</em>, carrying the signed amount that
 *       makes the totals agree.
 *   <li>Further apart — validation is refused with both figures and the difference. The invoice
 *       stays Draft and stays editable; nothing is written.
 * </ul>
 *
 * <h2>Why a line, and which account</h2>
 *
 * The difference is not input VAT and must not be declared as one, so it cannot live on a VAT
 * summary row (that mechanism exists for the narrower per-line VAT rounding case — see {@code
 * OcrVatRoundingReconciler} — and needs a {@code manageByAmount} tax). The account is the company's
 * own setting, never a code: {@code AccountConfig.cashPositionVariationDebitAccount} ("Cashier
 * Debit Regulation account" — in the Bulgarian chart 60909 «Други разходи»), the account Axelor
 * already writes an immaterial difference off to when it reconciles a payment. A hardcoded 609 was
 * a group (view) account in the Bulgarian chart, so the correction line could never be posted. The
 * credit twin (709, income) is not used for a negative correction: the purchase journal does not
 * accept income accounts, and a negative line on the expense account is the same cost correction. A
 * missing, group or inactive account refuses with a message saying where to set it.
 *
 * <p>The correction carries the SAME tax as the lines it was derived from, resolved by {@link
 * #resolveUniqueBucket}. The cent is part of the document's taxable base, so putting it on a zero
 * rate would both understate that base and add a second summary row that reads as a duplicate — see
 * invoice #15. Inside the bucket the base simply grows by the cent and Axelor derives the VAT from
 * it once, leaving exactly one Tax row. When no single bucket is provable the correction is refused
 * rather than placed somewhere plausible.
 *
 * <h2>Idempotence</h2>
 *
 * The correction line is marked with {@code InvoiceLine.ocrRoundingAdjustment}. Every pass finds
 * that one line, subtracts its amount back out before comparing, and then updates it, or deletes it
 * when it is no longer needed. So validating twice, retrying after a failure, or reprocessing the
 * document can never leave two of them — and an ordinary line an accountant typed on the same
 * account is not marked, so it is never mistaken for the automatic one and never touched.
 *
 * <h2>What it refuses to guess</h2>
 *
 * A missing rounding account, a mixed-rate line section, or lines that disagree about their VAT
 * credit are answers nobody here has. The invoice is left in Draft with a message naming exactly
 * what is ambiguous; no account is invented, no bucket is picked "because it is the bigger one",
 * and the difference is never quietly dropped onto a zero rate.
 */
@Singleton
public class OcrRoundingLineService {

  private static final String LINE_NAME = "Закръгляване (автоматична корекция)";

  /**
   * The shared, idempotent draft preparation — the ONE place the reconciliation actually happens.
   *
   * <h2>Why draft-first</h2>
   *
   * Everything here is safe to do while the invoice is still a draft, and doing it here is what
   * lets the accountant SEE the final figures and the rounding line before pressing Validate.
   * Validation then has nothing left to change, which is both less surprising and the fix for the
   * duplicate Tax rows: the rebuilds all happen once, at draft time, instead of three times inside
   * the validation transaction.
   *
   * <p>Best-effort by design. A missing rounding account, an ambiguous VAT bucket or a mismatch too
   * large to be rounding leaves the draft exactly as it was and returns the reason for the review
   * notes — the hard refusal stays with {@link #reconcileBeforeValidation}, which is the gate that
   * must not be passed. Callers on the edit paths therefore never have to handle an exception to
   * save an edit.
   *
   * @return null when the draft is prepared, otherwise why it could not be
   */
  public String prepareDraft(Invoice invoice) {
    return prepareDraft(invoice, true);
  }

  /**
   * As {@link #prepareDraft(Invoice)}, for callers that have just rebuilt the summary themselves.
   *
   * @param buildSummary false when the caller already ran {@code OcrInvoiceTaxSummaryService.sync}
   *     and holds its {@code Result} — running it twice is exactly the redundant rebuild that
   *     produced duplicate Tax rows
   */
  public String prepareDraft(Invoice invoice, boolean buildSummary) {
    try {
      reconcile(invoice, buildSummary);
      return null;
    } catch (AxelorException e) {
      return e.getMessage();
    } catch (RuntimeException e) {
      return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
  }

  /**
   * The validation gate: a cheap, idempotent safety check around the stock flow.
   *
   * <p>On a draft that {@link #prepareDraft} already settled this reads four figures and returns —
   * no {@code syncTaxSummary}, no {@code TaxInvoiceLine.creates()}, no custom {@code compute}.
   * Stock validation then runs its own single {@code compute} and regenerates exactly the same
   * summary, so nothing the user could see changes and no second Tax row can appear.
   *
   * <p>It only does work when the invoice is NOT already reconciled — a draft edited after OCR, or
   * one created before this existed. Then it runs the same single preparation, strictly, so a
   * problem refuses validation instead of being carried into the posting.
   */
  public void reconcileBeforeValidation(Invoice invoice) throws AxelorException {
    if (!isSupplierPurchase(invoice) || trustedTotal(invoice) == null) {
      return;
    }
    if (isAlreadyReconciled(invoice)) {
      return;
    }
    reconcile(invoice, true);
  }

  /**
   * Whether the saved draft already matches the document, read straight off the invoice.
   *
   * <p>Deliberately rebuilds nothing: that is the whole point of running preparation at draft time.
   * With a trusted breakdown all three figures must match; without one, the gross alone, which is
   * the only thing those older invoices ever promised.
   */
  protected boolean isAlreadyReconciled(Invoice invoice) {
    BigDecimal printedGross = trustedTotal(invoice);
    if (printedGross == null || nz(invoice.getInTaxTotal()).compareTo(printedGross) != 0) {
      return false;
    }
    BigDecimal printedNet = trustedNet(invoice);
    BigDecimal printedVat = trustedVat(invoice);
    if (printedNet == null || printedVat == null) {
      return true; // gross-only path: the gross matches, which is all it ever checked
    }
    return nz(invoice.getExTaxTotal()).compareTo(printedNet) == 0
        && summaryVat(invoice).compareTo(printedVat) == 0;
  }

  protected void reconcile(Invoice invoice, boolean buildSummary) throws AxelorException {
    if (!isSupplierPurchase(invoice)) {
      return;
    }
    BigDecimal trusted = trustedTotal(invoice);
    if (trusted == null) {
      return;
    }
    // The document's own VAT, read BEFORE anything recomputes the header, so a VAT that silently
    // fails to materialise can still be named in the blocker.
    BigDecimal documentVat = nz(invoice.getTaxTotal());

    // 1. The draft's real net, VAT and GROSS, by Axelor's own arithmetic. No validation, no
    //    posting, no invoice number. When the document's own breakdown is known, this also aims
    //    the existing VAT rounding mechanism at the PRINTED VAT rather than at the printed gross.
    if (buildSummary) {
      buildTaxSummary(invoice);
    }
    alignVatToDocument(invoice);
    BigDecimal calculated = calculateGross(invoice, documentVat);

    InvoiceLine existing = findAdjustmentLine(invoice);
    // The correction carries zero VAT, so its whole amount is its contribution to the gross.
    BigDecimal alreadyAdjusted = existing == null ? BigDecimal.ZERO : nz(existing.getExTaxTotal());

    // 2. The delta. With a trusted breakdown the comparison is NET against NET: the VAT half has
    //    already been settled against the declared VAT in step 1, so whatever remains is net, and
    //    net is exactly what an expense line on the rounding account can carry. Comparing gross
    // here is what let
    //    invoice #14 through — 146.56 + 29.32 hits the printed 175.88 while both halves are a
    //    stotinka out. Without a breakdown, nothing changes: gross against gross as before.
    BigDecimal printedNet = trustedNet(invoice);
    BigDecimal target = printedNet != null ? printedNet : trusted;
    BigDecimal against =
        printedNet != null
            ? nz(invoice.getExTaxTotal()).subtract(alreadyAdjusted)
            : calculated.subtract(alreadyAdjusted);
    OcrTotalAdjustment verdict = OcrTotalAdjustment.of(target, against);
    switch (verdict.getVerdict()) {
      case TOO_LARGE:
        throw new OcrDocumentRefusal(
            invoice,
            TraceBackRepository.CATEGORY_INCONSISTENCY,
            refusalMessage(invoice, verdict),
            tooLargeKind(invoice, verdict),
            trustedTotal(invoice),
            nz(invoice.getInTaxTotal()));
      case BALANCED:
        if (existing == null) {
          return;
        }
        invoice.removeInvoiceLineListItem(existing);
        break;
      case ADJUSTABLE:
        // 3. Exactly one marked line on the rounding account, carrying the signed difference.
        writeAdjustmentLine(invoice, existing, verdict.getDifference());
        break;
      default:
        return;
    }

    // 4. Recompute so the header totals, the per-rate VAT summary and the payment terms all
    //    reflect the corrected amount. The terms are dropped first because
    //    InvoiceGenerator.computeInvoice only regenerates them when the list is empty — otherwise
    //    they would stay on the pre-correction gross. Safe here: the invoice is still a draft, so
    //    nothing has been paid or reconciled against them (and the mapping is orphanRemoval=true,
    //    so the replaced rows are deleted rather than orphaned).
    // ⚠️ rebuildSummary = FALSE. The summary was built and aligned in step 1 and the lines have
    //    only gained the correction since; Axelor's own compute regenerates the per-rate rows from
    //    them anyway. Re-running syncTaxSummary here is what produced invoice #15's duplicate Tax
    //    rows — five clear/rebuild cycles in one transaction, two of which survived the flushes.
    clearInvoiceTerms(invoice);
    BigDecimal corrected = calculateGross(invoice, documentVat);

    // 5. Verify. Gross equality alone is no longer proof when the document's components are known:
    //    all three figures have to agree at currency scale, or nothing is posted.
    verifyAgainstDocument(invoice, corrected, verdict);
  }

  /**
   * Refuses unless every figure the document states matches what Axelor will post.
   *
   * <p>With no trusted breakdown this is the gross check it always was. With one, it is all three —
   * net, VAT and gross — because invoice #14 posted a correct gross built from a net a stotinka low
   * and a VAT a stotinka high, and nothing downstream would ever have noticed.
   */
  protected void verifyAgainstDocument(
      Invoice invoice, BigDecimal correctedGross, OcrTotalAdjustment verdict)
      throws AxelorException {
    BigDecimal printedGross = trustedTotal(invoice);
    BigDecimal printedNet = trustedNet(invoice);
    BigDecimal printedVat = trustedVat(invoice);

    List<String> wrong = new ArrayList<>();
    if (printedNet != null && nz(invoice.getExTaxTotal()).compareTo(printedNet) != 0) {
      wrong.add("net " + invoice.getExTaxTotal() + " against the document's " + printedNet);
    }
    if (printedVat != null && summaryVat(invoice).compareTo(printedVat) != 0) {
      wrong.add("VAT " + summaryVat(invoice) + " against the document's " + printedVat);
    }
    if (printedGross != null && correctedGross.compareTo(printedGross) != 0) {
      wrong.add("total " + correctedGross + " against the document's " + printedGross);
    }
    if (wrong.isEmpty()) {
      return;
    }
    throw new OcrDocumentRefusal(
        invoice,
        TraceBackRepository.CATEGORY_INCONSISTENCY,
        "The invoice still does not match the document after booking "
            + verdict.getDifference()
            + " to the rounding account: "
            + String.join("; ", wrong)
            + ". A difference of this size is not rounding and must never be moved to the rounding"
            + " account — correct the invoice lines or their taxes.",
        OcrDocumentRefusal.Kind.MISMATCH,
        trustedTotal(invoice),
        nz(invoice.getInTaxTotal()));
  }

  /**
   * The net the document printed, or null when this invoice carries no trusted breakdown.
   *
   * <p>When an accountant has corrected the extracted total (see {@link #correctedGross}), the
   * extracted breakdown is ignored entirely: the correction speaks for the whole document, so a
   * stale or garbage extracted net/VAT must not leak back in. The corrected breakdown is used only
   * when BOTH halves were supplied — a half-breakdown drops to the gross-only path.
   */
  protected BigDecimal trustedNet(Invoice invoice) {
    if (correctedGross(invoice) != null) {
      return hasCorrectedBreakdown(invoice) ? nzOrNull(invoice.getOcrDocumentNetCorrected()) : null;
    }
    return hasTrustedBreakdown(invoice) ? invoice.getOcrDocumentNetTotal() : null;
  }

  /** The VAT the document printed, or null when this invoice carries no trusted breakdown. */
  protected BigDecimal trustedVat(Invoice invoice) {
    if (correctedGross(invoice) != null) {
      return hasCorrectedBreakdown(invoice) ? nzOrNull(invoice.getOcrDocumentVatCorrected()) : null;
    }
    return hasTrustedBreakdown(invoice) ? invoice.getOcrDocumentVatTotal() : null;
  }

  /**
   * The accountant's corrected gross, or null when none was entered. Preferred over the extracted
   * total everywhere a trusted total is read, so the correction reaches both the validation gate
   * and the VAT reconciler without any caller knowing it is a correction rather than the
   * extraction.
   */
  protected BigDecimal correctedGross(Invoice invoice) {
    return invoice == null ? null : nzOrNull(invoice.getOcrDocumentTotalCorrected());
  }

  /** True when the correction supplied BOTH a net and a VAT, i.e. a full corrected breakdown. */
  protected boolean hasCorrectedBreakdown(Invoice invoice) {
    return invoice != null
        && nzOrNull(invoice.getOcrDocumentNetCorrected()) != null
        && nzOrNull(invoice.getOcrDocumentVatCorrected()) != null;
  }

  private static BigDecimal nzOrNull(BigDecimal v) {
    return v == null || v.signum() == 0 ? null : v;
  }

  /**
   * Whether the document's own net and VAT may be trusted.
   *
   * <p>The boolean, never the decimals: a generated decimal getter returns ZERO for a NULL column
   * (BgDefaultedGetterTest), so every invoice created before these fields existed would otherwise
   * claim to declare a net and VAT of 0.00 and be refused for a component mismatch it never had. No
   * marker means the gross-only path, which is what those invoices were validated under.
   */
  protected boolean hasTrustedBreakdown(Invoice invoice) {
    return invoice != null
        && Boolean.TRUE.equals(invoice.getOcrDocumentBreakdownAvailable())
        && invoice.getOcrDocumentTotal() != null
        && invoice.getOcrDocumentTotal().signum() != 0;
  }

  /**
   * The draft's gross total, as Axelor's validation will calculate it — and nothing weaker.
   *
   * <h2>Why a bare compute is not enough</h2>
   *
   * A bare {@code InvoiceService.compute} followed by reading {@code getInTaxTotal()} is wrong on a
   * draft whose {@code invoiceLineTaxList} comes back empty: {@code
   * InvoiceTaxComputeService.recomputeInvoiceTaxAmounts} sets {@code inTaxTotal = exTaxTotal + Σ
   * row.taxTotal} — which with no rows is simply the NET. The printed gross would then be compared
   * against a net-only figure: a 146.56 + 29.31 = 175.87 invoice with a printed 175.88 would report
   * a "difference" of 29.32 instead of 0.01, i.e. the whole VAT.
   *
   * <p>So the VAT is built before it is read, and the result is checked rather than trusted:
   *
   * <ol>
   *   <li>{@link OcrInvoiceTaxSummaryService#sync} rebuilds {@code invoiceLineTaxList} with
   *       Axelor's own {@code TaxInvoiceLine} generator — every line, each line's OWN {@code
   *       taxLineSet}, so several different rates on one invoice are all included — and then runs
   *       {@link com.axelor.apps.aiocr.service.readiness.OcrVatRoundingReconciler}, whose {@code
   *       manageByAmount} override has to be present in the list BEFORE compute runs, because
   *       {@code InvoiceGenerator.getUpdatedInvoiceLineTax} is what preserves it.
   *   <li>{@code InvoiceService.compute} then runs the very generator validation runs — {@code
   *       TaxInvoiceLine} again, then {@code InvoiceTaxComputeService} — so the figure read here is
   *       the figure validation will produce. Nothing is recomputed by hand from net × rate.
   *   <li>The result is verified: {@code gross == net + VAT}, and a zero VAT is refused when the
   *       lines or the document say there should be one.
   * </ol>
   *
   * <p>Compute-only: it neither validates, posts, nor takes an invoice number.
   */
  /** Rebuilds the per-rate summary, refusing when it cannot be built. One call per preparation. */
  protected void buildTaxSummary(Invoice invoice) throws AxelorException {
    OcrInvoiceTaxSummaryService.Result summary = syncTaxSummary(invoice);
    if (summary != null && summary.isFailed()) {
      throw new OcrDocumentRefusal(
          invoice,
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          "The per-rate VAT summary could not be built ("
              + summary.getFailure()
              + "), so the total Axelor would post cannot be compared with the total on the"
              + " document. Fix that first, then validate again.",
          OcrDocumentRefusal.Kind.VAT_BREAKDOWN,
          trustedTotal(invoice),
          nz(invoice.getInTaxTotal()));
    }
  }

  /**
   * Puts the per-rate VAT onto the figure the document declares.
   *
   * <p>Reuses {@link OcrVatRoundingReconciler} — the SAME {@code manageByAmount} mechanism and the
   * same one-step limit — only aimed at the printed VAT instead of the printed gross, which is the
   * whole difference between #14's 29.32 and the correct 29.31. Must run after the summary exists
   * and before the invoice is computed, because {@code InvoiceGenerator.getUpdatedInvoiceLineTax}
   * is what carries an override through compute. A no-op once the summary already agrees.
   */
  protected void alignVatToDocument(Invoice invoice) throws AxelorException {
    BigDecimal printedVat = trustedVat(invoice);
    if (printedVat != null) {
      OcrVatRoundingReconciler.alignToPrintedVat(
          invoice, printedVat, toCompanyAmount(invoice, printedVat));
    }
  }

  protected BigDecimal calculateGross(Invoice invoice, BigDecimal documentVat)
      throws AxelorException {
    recompute(invoice);

    BigDecimal net = nz(invoice.getExTaxTotal());
    BigDecimal vat = summaryVat(invoice);
    BigDecimal gross = nz(invoice.getInTaxTotal());

    if (gross.compareTo(net.add(vat)) != 0) {
      throw new OcrDocumentRefusal(
          invoice,
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          "The invoice does not add up after computation: net "
              + net
              + " + VAT "
              + vat
              + " is not the calculated total "
              + gross
              + ". Nothing was corrected.",
          OcrDocumentRefusal.Kind.VAT_BREAKDOWN,
          trustedTotal(invoice),
          nz(invoice.getInTaxTotal()));
    }
    if (vat.signum() == 0 && (linesDeclareVat(invoice) || documentVat.signum() != 0)) {
      throw new OcrDocumentRefusal(
          invoice,
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          "The calculated VAT is 0.00 although "
              + (linesDeclareVat(invoice)
                  ? "the invoice lines carry a VAT rate"
                  : "the document declares VAT of " + documentVat)
              + ", so the per-rate VAT summary is empty and the calculated total ("
              + gross
              + ") is only the net. Give every line its tax and make sure the company's VAT"
              + " account configuration is complete, then validate again — the document total is"
              + " not compared against a net-only figure.",
          OcrDocumentRefusal.Kind.NO_VAT,
          trustedTotal(invoice),
          nz(invoice.getInTaxTotal()));
    }
    return gross;
  }

  /** The kind of a TOO_LARGE refusal: the same split {@link #refusalMessage} makes. */
  protected OcrDocumentRefusal.Kind tooLargeKind(Invoice invoice, OcrTotalAdjustment verdict) {
    return summaryVat(invoice).signum() == 0 && verdict.getDifference().signum() > 0
        ? OcrDocumentRefusal.Kind.NO_VAT
        : OcrDocumentRefusal.Kind.MISMATCH;
  }

  /**
   * Why the totals disagree, in the words of the actual cause.
   *
   * <h2>The case this exists for</h2>
   *
   * Supplier invoice #12: ten lines summing to 146.56, every one of them mapped to the zero-rate
   * purchase tax {@code BG_ZRP} because the extraction found no per-line VAT column, against a
   * document total of 175.88. Axelor's computation is then correct and complete — the per-rate
   * summary exists and says 0.00 — and the refusal is correct too, but the generic sentence reads
   * as if the rounding arithmetic had gone wrong ("differ by 29.32") when what is actually missing
   * is the 20% on the lines. 29.32 is not a rounding difference; it IS the VAT.
   *
   * <p>The two signals that would normally name this are both already flattened to zero by the time
   * anyone can look: the line rates are 0%, and {@code Invoice.taxTotal} — which the intake sets
   * from the document — is overwritten by the first compute, exactly the durability problem {@code
   * ocrDocumentTotal} exists to solve for the gross. So the diagnosis is made from what IS still
   * true: the computed VAT is zero while the trusted gross sits above the computed net, and a gross
   * above a net with no VAT anywhere can only mean the lines lost their rate.
   */
  protected String refusalMessage(Invoice invoice, OcrTotalAdjustment verdict) {
    BigDecimal vat = summaryVat(invoice);
    if (vat.signum() != 0 || verdict.getDifference().signum() <= 0) {
      return verdict.describeRefusal();
    }
    return "The invoice lines carry no VAT: all "
        + zeroRatedLineCount(invoice)
        + " of them are at a zero rate, so Axelor would post "
        + verdict.getCalculated()
        + " against a document total of "
        + verdict.getTrusted()
        + ". The difference of "
        + verdict.getDifference()
        + " is not a rounding difference — it is the missing VAT. Set the correct VAT rate on the"
        + " invoice lines (the extraction could not read a per-line VAT rate and defaulted them to"
        + " "
        + BgOcrVatMapping.BG_ZERO
        + "), then validate again.";
  }

  /** How many normal lines sit on a zero rate — the sentence above names the number. */
  protected int zeroRatedLineCount(Invoice invoice) {
    List<InvoiceLine> lines = invoice.getInvoiceLineList();
    if (lines == null) {
      return 0;
    }
    int count = 0;
    for (InvoiceLine line : lines) {
      if (!Boolean.TRUE.equals(line.getOcrRoundingAdjustment())) {
        count++;
      }
    }
    return count;
  }

  /** The VAT Axelor will actually post: the sum of the per-rate summary rows. */
  protected BigDecimal summaryVat(Invoice invoice) {
    List<InvoiceLineTax> rows = invoice.getInvoiceLineTaxList();
    BigDecimal total = BigDecimal.ZERO;
    if (rows != null) {
      for (InvoiceLineTax row : rows) {
        total = total.add(nz(row.getTaxTotal()));
      }
    }
    return total;
  }

  /** True when any line carries a tax whose rate is not zero — several rates is the normal case. */
  protected boolean linesDeclareVat(Invoice invoice) {
    List<InvoiceLine> lines = invoice.getInvoiceLineList();
    if (lines == null) {
      return false;
    }
    for (InvoiceLine line : lines) {
      Set<TaxLine> taxLineSet = line.getTaxLineSet();
      if (taxLineSet == null) {
        continue;
      }
      for (TaxLine taxLine : taxLineSet) {
        if (taxLine != null && nz(taxLine.getValue()).signum() != 0) {
          return true;
        }
      }
    }
    return false;
  }

  /** Rebuilds the per-rate VAT summary through the OCR seam. Overridable for tests. */
  protected OcrInvoiceTaxSummaryService.Result syncTaxSummary(Invoice invoice) {
    return Beans.get(OcrInvoiceTaxSummaryService.class).sync(invoice);
  }

  protected void clearInvoiceTerms(Invoice invoice) {
    if (invoice.getInvoiceTermList() != null) {
      invoice.getInvoiceTermList().clear();
    }
  }

  /**
   * The same comparison, without writing anything, for the ventilation gate.
   *
   * <p>A validated invoice has already been through {@link #reconcileBeforeValidation}, so this can
   * only fire on one validated before this check existed, or one whose lines were changed after
   * validation. Ventilation posts the move; refusing here keeps the invoice Validated and editable
   * instead of posting a total nobody agreed to.
   */
  public void checkBeforeVentilation(Invoice invoice) throws AxelorException {
    if (!isSupplierPurchase(invoice)) {
      return;
    }
    BigDecimal trusted = trustedTotal(invoice);
    if (trusted == null) {
      return;
    }
    // Nothing is subtracted back out here: at this point the correction line, if any, is part of
    // the invoice and the question is only whether the invoice as it stands posts the right total.
    OcrTotalAdjustment verdict = OcrTotalAdjustment.of(trusted, nz(invoice.getInTaxTotal()));
    if (verdict.isTooLarge()) {
      throw new OcrDocumentRefusal(
          invoice,
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          verdict.describeRefusal(),
          OcrDocumentRefusal.Kind.MISMATCH,
          trustedTotal(invoice),
          nz(invoice.getInTaxTotal()));
    }
  }

  /** The gross the document printed, or null when this invoice has no document behind it. */
  protected BigDecimal trustedTotal(Invoice invoice) {
    // An accountant's correction overrides the extracted total. The extracted ocrDocumentTotal is
    // kept as evidence but no longer drives validation once a correction exists.
    BigDecimal corrected = correctedGross(invoice);
    if (corrected != null) {
      return corrected;
    }
    // ⚠️ The generated getter for a decimal returns ZERO, never null (BgDefaultedGetterTest), so
    // "unset" and "zero" are the same value here — and both mean "no trusted total".
    BigDecimal total = invoice.getOcrDocumentTotal();
    return total == null || total.signum() == 0 ? null : total;
  }

  protected boolean isSupplierPurchase(Invoice invoice) {
    return invoice != null
        && invoice.getOperationTypeSelect() == InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE;
  }

  /** Axelor's own recomputation. Overridable so the rules can be exercised without a runtime. */
  protected void recompute(Invoice invoice) throws AxelorException {
    Beans.get(InvoiceService.class).compute(invoice);
  }

  /** The one line this service owns, or null. Never an ordinary line somebody else created. */
  protected InvoiceLine findAdjustmentLine(Invoice invoice) {
    List<InvoiceLine> lines = invoice.getInvoiceLineList();
    if (lines == null) {
      return null;
    }
    for (InvoiceLine line : lines) {
      if (Boolean.TRUE.equals(line.getOcrRoundingAdjustment())) {
        return line;
      }
    }
    return null;
  }

  /** Creates the correction line, or re-points an existing one at the amount now required. */
  protected void writeAdjustmentLine(Invoice invoice, InvoiceLine existing, BigDecimal difference)
      throws AxelorException {
    Account account = resolveRoundingAccount(invoice);
    String problem = roundingAccountProblem(account);
    if (problem != null) {
      throw new OcrDocumentRefusal(
          invoice,
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          "The invoice total on the document ("
              + trustedTotal(invoice)
              + ") is "
              + difference.abs()
              + " away from the total Axelor calculates, which is a rounding difference — but "
              + problem
              + " of company "
              + companyName(invoice)
              + ", so it cannot be booked. Set a posting expense account (60909 in the Bulgarian"
              + " chart) as the Cashier Debit Regulation account in the company's Accounting"
              + " configuration (or correct the invoice lines so no correction is needed), then"
              + " validate again.",
          account == null
              ? OcrDocumentRefusal.Kind.ROUNDING_ACCOUNT_MISSING
              : OcrDocumentRefusal.Kind.ROUNDING_ACCOUNT_UNUSABLE,
          trustedTotal(invoice),
          nz(invoice.getInTaxTotal()),
          account == null ? null : account.getCode());
    }
    // WHICH INSTRUMENT — and the branch is not cosmetic, it is arithmetic.
    //
    // With a trusted breakdown the correction targets the NET and the VAT is independently pinned
    // to the printed figure, so the cent belongs in the SAME VAT bucket as the lines it came from:
    // the base grows to 146.57, Axelor derives 29.31 from it, and the Tax section keeps exactly one
    // row. That is what the document says, and a zero-rated cent would understate the taxable base
    // while adding a second summary row that reads as a duplicate (invoice #15).
    //
    // Without a breakdown there is only a printed GROSS to aim at, and inside a taxed bucket a cent
    // of net moves the gross by a cent plus its VAT — so it could not land on the gross at all.
    // Those invoices keep the zero-rate instrument they were always corrected with.
    boolean intoBucket = hasTrustedBreakdown(invoice);
    TaxLine correctionTax;
    String correctionTaxCode;
    List<InvoiceLine> bucketLines;
    if (intoBucket) {
      TaxBucket bucket = resolveUniqueBucket(invoice);
      if (bucket.getRefusal() != null) {
        throw new OcrDocumentRefusal(
            invoice,
            TraceBackRepository.CATEGORY_INCONSISTENCY,
            "The rounding correction of "
                + difference
                + " cannot be placed automatically: "
                + bucket.getRefusal()
                + " The correction must belong to the same VAT treatment as the lines it comes"
                + " from, so it is left for review rather than guessed.",
            OcrDocumentRefusal.Kind.VAT_BREAKDOWN,
            trustedTotal(invoice),
            nz(invoice.getInTaxTotal()));
      }
      correctionTax = bucket.getTaxLine();
      correctionTaxCode = bucket.getTaxCode();
      bucketLines = bucket.getLines();
    } else {
      correctionTax = resolveZeroRateTaxLine(invoice);
      // "Zero rate" is a claim about chart data, so it is checked rather than trusted: a BG_ZRP
      // configured with a rate would put VAT on the correction and miss the gross anyway.
      if (correctionTax != null && nz(correctionTax.getValue()).signum() != 0) {
        correctionTax = null;
      }
      if (correctionTax == null) {
        throw new OcrDocumentRefusal(
            invoice,
            TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
            "The rounding correction of "
                + difference
                + " cannot be booked without VAT: the zero-rate purchase tax "
                + BgOcrVatMapping.BG_ZERO
                + " is missing, has no version covering "
                + taxDate(invoice)
                + ", or is not actually at a zero rate. Configure it, then validate again.",
            OcrDocumentRefusal.Kind.ZERO_TAX_MISSING,
            trustedTotal(invoice),
            nz(invoice.getInTaxTotal()));
      }
      correctionTaxCode = BgOcrVatMapping.BG_ZERO;
      bucketLines = new ArrayList<>();
    }

    InvoiceLine line = existing;
    if (line == null) {
      line = new InvoiceLine();
      line.setInvoice(invoice);
      line.setOcrRoundingAdjustment(true);
      line.setProductName(LINE_NAME);
      line.setDescription(LINE_NAME);
      line.setQty(BigDecimal.ONE);
      invoice.addInvoiceLineListItem(line);
    }
    BigDecimal rate = nz(correctionTax.getValue());
    // The line's own VAT, at the currency scale, so the purchase diary's per-line base/VAT stays
    // self-consistent. The bucket's real VAT is derived once per rate from the aggregated base by
    // TaxInvoiceLine — this figure never feeds the header.
    BigDecimal lineVat =
        difference.multiply(rate).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    line.setAccount(account);
    line.setPrice(difference);
    line.setPriceDiscounted(difference);
    line.setInTaxPrice(difference.add(lineVat));
    line.setExTaxTotal(difference);
    line.setInTaxTotal(difference.add(lineVat));
    line.setTaxRate(rate);
    line.setTaxCode(correctionTaxCode);
    Set<TaxLine> taxLineSet = new HashSet<>();
    taxLineSet.add(correctionTax);
    line.setTaxLineSet(taxLineSet);
    BigDecimal companyAmount = toCompanyAmount(invoice, difference);
    line.setCompanyExTaxTotal(companyAmount);
    line.setCompanyInTaxTotal(companyAmount.add(toCompanyAmount(invoice, lineVat)));
    // Закръглението е ОБИКНОВЕН ред (typeSelect 0) без продукт, тоест по правилото на локализацията
    // иска мерна единица както всеки друг. Доказано, че досега не я е получавало: върху жива
    // фактура
    // №17 единайсетият ред — този — е записан с unit = null точно като десетте OCR реда.
    unitDefaults().applyTo(line);

    // The VAT-credit treatment is Bulgarian tax law and lives in bg-localization; see
    // OcrRoundingLineCreditType for why it cannot be set from this module.
    String creditRefusal = applyCreditType(line, bucketLines);
    if (creditRefusal != null) {
      throw new OcrDocumentRefusal(
          invoice,
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          creditRefusal,
          OcrDocumentRefusal.Kind.VAT_BREAKDOWN,
          trustedTotal(invoice),
          nz(invoice.getInTaxTotal()));
    }
    // No header arithmetic here on purpose: the caller recomputes the whole invoice through
    // Axelor, so the totals, the VAT summary and the payment terms all come from one place.
  }

  /** Seam: the production-path tests drive the real reconciliation with a stub here. */
  protected InvoiceLineUnitDefaultService unitDefaults() {
    return Beans.get(InvoiceLineUnitDefaultService.class);
  }

  /** Copies the bucket's VAT-credit treatment onto the correction, when a backend is installed. */
  protected String applyCreditType(InvoiceLine roundingLine, List<InvoiceLine> bucketLines) {
    OcrRoundingLineCreditType backend;
    try {
      backend = Beans.get(OcrRoundingLineCreditType.class);
    } catch (RuntimeException e) {
      return null; // ai-ocr without bg-localization: nothing to classify against
    }
    return backend.applyFromBucket(roundingLine, bucketLines);
  }

  /**
   * The one VAT bucket every ordinary line of this invoice belongs to, or why there is not one.
   *
   * <p>"Uniquely provable" is taken literally: every ordinary monetary line must carry exactly ONE
   * tax line, and all of them must be the same tax at the same rate. A mixed-rate invoice, a line
   * with several taxes, or a line with none leaves no single correct home for the cent — and a cent
   * put in the wrong bucket is a misdeclared taxable base, so it is refused rather than guessed.
   */
  protected TaxBucket resolveUniqueBucket(Invoice invoice) {
    List<InvoiceLine> lines = invoice.getInvoiceLineList();
    if (lines == null || lines.isEmpty()) {
      return TaxBucket.refused("the invoice has no lines.");
    }
    List<InvoiceLine> bucketLines = new ArrayList<>();
    TaxLine chosen = null;
    String chosenCode = null;
    for (InvoiceLine line : lines) {
      if (Boolean.TRUE.equals(line.getOcrRoundingAdjustment())) {
        continue; // the correction itself is not evidence of where the correction belongs
      }
      if (nz(line.getExTaxTotal()).signum() == 0) {
        continue;
      }
      Set<TaxLine> taxLineSet = line.getTaxLineSet();
      if (taxLineSet == null || taxLineSet.size() != 1) {
        return TaxBucket.refused(
            taxLineSet == null || taxLineSet.isEmpty()
                ? "a line carries no tax at all."
                : "a line carries more than one tax.");
      }
      TaxLine candidate = taxLineSet.iterator().next();
      String code = candidate.getTax() == null ? null : candidate.getTax().getCode();
      if (chosen == null) {
        chosen = candidate;
        chosenCode = code;
        bucketLines.add(line);
        continue;
      }
      if (nz(candidate.getValue()).compareTo(nz(chosen.getValue())) != 0
          || (code == null ? chosenCode != null : !code.equals(chosenCode))) {
        return TaxBucket.refused(
            "the invoice mixes VAT treatments ("
                + chosenCode
                + " at "
                + chosen.getValue()
                + "% and "
                + code
                + " at "
                + candidate.getValue()
                + "%), so there is no single bucket the rounding belongs to.");
      }
      bucketLines.add(line);
    }
    if (chosen == null) {
      return TaxBucket.refused("no ordinary line carries an amount and a tax.");
    }
    return TaxBucket.of(chosen, chosenCode, bucketLines);
  }

  /** The unique VAT treatment the rounding line must join, or the reason there is not one. */
  public static final class TaxBucket {
    private final TaxLine taxLine;
    private final String taxCode;
    private final List<InvoiceLine> lines;
    private final String refusal;

    private TaxBucket(TaxLine taxLine, String taxCode, List<InvoiceLine> lines, String refusal) {
      this.taxLine = taxLine;
      this.taxCode = taxCode;
      this.lines = lines;
      this.refusal = refusal;
    }

    static TaxBucket of(TaxLine taxLine, String taxCode, List<InvoiceLine> lines) {
      return new TaxBucket(taxLine, taxCode, lines, null);
    }

    static TaxBucket refused(String reason) {
      return new TaxBucket(null, null, new ArrayList<>(), reason);
    }

    public TaxLine getTaxLine() {
      return taxLine;
    }

    public String getTaxCode() {
      return taxCode;
    }

    /** Every ordinary line of the bucket — what the credit type is inherited from. */
    public List<InvoiceLine> getLines() {
      return lines;
    }

    /** Null when the bucket is unique; otherwise why it is not. */
    public String getRefusal() {
      return refusal;
    }
  }

  /**
   * The correction in the company currency. Identity in the usual same-currency case; otherwise the
   * suite's own conversion, so this cannot disagree with how the lines beside it were converted.
   */
  protected BigDecimal toCompanyAmount(Invoice invoice, BigDecimal amount) throws AxelorException {
    return Beans.get(InvoiceLineService.class).getCompanyExTaxTotal(amount, invoice);
  }

  /**
   * The rounding account of THIS invoice's company: its Cashier Debit Regulation account ({@code
   * AccountConfig.cashPositionVariationDebitAccount}). Null when the company has no accounting
   * configuration or the field is empty; whether the account can carry a line is {@link
   * #roundingAccountProblem}'s question.
   */
  protected Account resolveRoundingAccount(Invoice invoice) {
    Company company = invoice.getCompany();
    if (company == null) {
      return null;
    }
    try {
      return roundingAccountOf(Beans.get(AccountConfigService.class).getAccountConfig(company));
    } catch (AxelorException e) {
      return null;
    }
  }

  /**
   * The debit one of the two regulation accounts, for both signs: a negative correction is a
   * negative expense line, and the credit twin (an income account) is not accepted by the purchase
   * journal.
   */
  protected static Account roundingAccountOf(AccountConfig config) {
    return config == null ? null : config.getCashPositionVariationDebitAccount();
  }

  /**
   * Why the rounding account cannot carry the correction line, as the middle of a sentence; null
   * when it can. A group (view) account takes no lines and an inactive one is refused at posting,
   * so either would only move the failure from Validate to Ventilate.
   */
  protected static String roundingAccountProblem(Account account) {
    if (account == null) {
      return "no rounding account is set (Cashier Debit Regulation account) in the Accounting"
          + " configuration";
    }
    if (account.getAccountType() != null
        && AccountTypeRepository.TYPE_VIEW.equals(
            account.getAccountType().getTechnicalTypeSelect())) {
      return "the rounding account " + account.getCode() + " is a group (view) account";
    }
    // MoveValidateServiceImpl's own test: anything but active is refused at posting.
    if (account.getStatusSelect() != null
        && account.getStatusSelect() != AccountRepository.STATUS_ACTIVE) {
      return "the rounding account " + account.getCode() + " is inactive";
    }
    return null;
  }

  /**
   * The active version of the zero-rate BG purchase tax — the instrument the gross-only (pre
   * breakdown) invoices have always been corrected with. Null when it is not configured.
   */
  protected TaxLine resolveZeroRateTaxLine(Invoice invoice) {
    Tax tax =
        Beans.get(TaxRepository.class)
            .all()
            .filter("self.code = :code")
            .bind("code", BgOcrVatMapping.BG_ZERO)
            .fetchOne();
    if (tax == null) {
      return null;
    }
    try {
      // Axelor's own version lookup — it throws (missing date, no version covering the date) where
      // this method reports "not configured", so the caller can say what to fix in one sentence
      // instead of surfacing a generic tax error from inside a rounding correction.
      return Beans.get(TaxService.class).getTaxLine(tax, taxDate(invoice));
    } catch (AxelorException e) {
      return null;
    }
  }

  private static LocalDate taxDate(Invoice invoice) {
    return invoice.getOriginDate() != null ? invoice.getOriginDate() : invoice.getInvoiceDate();
  }

  private static String companyName(Invoice invoice) {
    Company company = invoice.getCompany();
    return company == null || company.getName() == null ? "(none set)" : company.getName();
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}

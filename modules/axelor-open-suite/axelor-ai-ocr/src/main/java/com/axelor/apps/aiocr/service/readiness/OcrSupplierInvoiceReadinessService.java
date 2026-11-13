package com.axelor.apps.aiocr.service.readiness;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.PaymentCondition;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.service.PaymentConditionService;
import com.axelor.apps.account.service.accountingsituation.AccountingSituationService;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.aiocr.service.accounting.PartnerAccountService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.db.repo.SequenceVersionRepository;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reusable readiness checker for OCR-generated DRAFT supplier invoices. It inspects the assembled
 * invoice + the company's accounting configuration and reports exactly what still blocks the
 * standard Validate/Ventilate, plus the OCR warnings/discrepancies. It NEVER validates, ventilates,
 * changes the invoice, or mutates configuration — it only describes readiness.
 *
 * <p>The rules live in the pure {@link #evaluate(Signals)} method so they can be unit-tested with
 * no Guice/JPA runtime; {@link #check(Invoice, List)} gathers the signals from the real Axelor
 * services (AccountConfigService, AccountingSituationService) defensively.
 */
@Singleton
public class OcrSupplierInvoiceReadinessService {

  // There is deliberately no AMOUNT_TOLERANCE here any more. A 0.02 slack was what let invoice 576
  // report as reconciling while its VAT was out: "close enough" and "correct" are different answers
  // and this service must not conflate them. Differences are classified by
  // OcrAmountReconciliation — exact, rounding, or mismatch — and each is reported as itself.

  /** Pure input to the readiness rules — decouples logic from entity/service/DB access. */
  public static class Signals {
    public boolean supplierPurchaseJournalConfigured;
    public boolean partnerAccountResolvable;
    public boolean partnerAccountReconcilable;

    /**
     * The payable account this invoice resolves to, as {@code 401 Доставчици}; null when none.
     *
     * <p>Carried so the blocker can name the account instead of describing a category. "Supplier
     * payable account is not reconcilable" told a reader neither which account nor which flag, and
     * both are needed before anything can be corrected — there are nine payable accounts in a
     * Bulgarian chart of accounts and only the one this invoice resolves to matters.
     */
    public String partnerAccountLabel;

    /** Which of the two flags are already on, so the blocker names the missing one exactly. */
    public boolean partnerAccountReconcileOk;

    public boolean partnerAccountUseForPartnerBalance;
    public boolean suppInvSequenceConfigured;
    public boolean paymentModePresent;
    public boolean paymentConditionPresent;

    /** The payment condition's name, so a blocker can name the record that has to be corrected. */
    public String paymentConditionLabel;

    /**
     * Why Axelor considers the payment condition unusable, or null when it is fine.
     *
     * <p>A present payment condition is not a usable one. {@code PaymentConditionServiceImpl}
     * requires it to carry at least one line and for those lines' percentages to total exactly 100,
     * and it is checked in {@code MoveCreateFromInvoiceServiceImpl} — i.e. <em>inside</em>
     * ventilation, after the move has already been built. Everything up to that point succeeds:
     * validation passes, the invoice simply gets zero invoice terms, and the failure only appears
     * when someone presses Ventilate and the whole transaction rolls back.
     *
     * <p>The message is Axelor's own, produced by calling its service rather than by reimplementing
     * the rule here, so this can never drift from what ventilation will actually enforce.
     */
    public String paymentConditionProblem;

    /**
     * The invoice-number sequence's name, when one is configured; null otherwise.
     *
     * <p>Only used to name the record in a blocker.
     */
    public String invoiceSequenceLabel;

    /**
     * True when no {@code SequenceVersion} of the invoice-number sequence covers the invoice date.
     *
     * <p>This is the condition behind "The generated sequence 0000000001 already exists". {@code
     * SequenceService.getVersion} looks the version up with {@code startDate <= refDate AND
     * (endDate IS NULL OR endDate >= refDate)}, the reference date being {@code
     * invoice.getInvoiceDate()}. When nothing matches, {@code SequenceVersionGeneratorServiceImpl}
     * mints a fresh version — and for a sequence with no yearly or monthly reset, {@code
     * findStartDateFromPreviousVersion} falls back to <em>today</em> with {@code nextNum = 1}.
     * Every back-dated document therefore restarts the series at 1: the first one to post takes the
     * number, and the next one collides with it.
     *
     * <p>Checked with Axelor's own repository query rather than by recomputing anything. Nothing
     * here predicts the number — only whether the series is about to restart, which is both
     * sufficient to refuse and impossible to get wrong.
     */
    public boolean invoiceSequenceVersionMissing;

    /** The reference date the version was looked up with, for the message. */
    public String invoiceSequenceRefDate;

    /** How many payment terms the invoice carries. */
    public int invoiceTermCount;

    /**
     * Whether the terms should already exist, i.e. the invoice is past DRAFT and has a gross
     * amount.
     *
     * <p>A draft with no terms is normal — validation generates them. A <em>validated</em> invoice
     * with none is not, and it is fatal in a way nothing else reports: the payable side of the move
     * is built by {@code MoveLineCreateServiceImpl.addInvoiceTermMoveLines}, which iterates {@code
     * invoiceTermList}, so an empty list produces <b>no credit line at all</b>. The move is then
     * every debit and nothing else, and Axelor rejects it with a message about special and
     * commitment accounts that names none of this.
     *
     * <p>Axelor cannot catch it earlier: {@code InvoiceServiceImpl.checkPreconditions} verifies the
     * payment <em>condition</em> and the multi-term policy, never that terms were produced. And
     * they silently are not whenever the condition had no lines at the moment of validation —
     * {@code InvoiceTermServiceImpl.computeInvoiceTerms} returns immediately in that case, without
     * an error. Repairing the condition afterwards does not backfill the invoices already validated
     * against it.
     */
    public boolean invoiceTermsExpectedNow;

    public boolean currencyPresent;
    public boolean invoiceDatePresent;
    public boolean originDatePresent;
    public int lineCount;
    public int linesMissingAccount;
    public int linesMissingProduct;
    public int linesMissingTax;

    /**
     * How many rows the invoice's VAT summary ({@code invoiceLineTaxList}) carries — the "Tax"
     * panel on the form, and what ventilation posts the VAT from.
     *
     * <p>This was invisible, and its absence is what produced Axelor's "There is no taxes on this
     * invoice. Do you want to proceed?" on every OCR draft: the alert in {@code Invoice.xml} tests
     * exactly this list, on the saved record, before validation runs. Validation then rebuilds it,
     * so the prompt was never a sign that the taxes were wrong — but a draft in that state is not
     * the state a draft prepared in the form is in, and the difference has to be reportable.
     */
    public int taxSummaryRowCount;

    /**
     * Whether the lines add up <em>exactly</em> to the header's ex-tax total; {@code null} when
     * there is nothing to compare (no lines, or no header base). Kept as a tri-state rather than a
     * boolean so "not checked" never reads as "checks out".
     *
     * <p>Exact, not "within a tolerance": see {@link OcrAmountReconciliation}. A difference that
     * rounding can explain sets this to {@code FALSE} and {@link #lineSumRoundingOnly} to true, and
     * is reported as a warning rather than a blocker.
     */
    public Boolean lineSumMatchesHeader;

    /** Signed difference {@code lineSum - headerBase}, for the message; null when unchecked. */
    public BigDecimal lineSumDelta;

    /** True when {@link #lineSumMatchesHeader} is false only by rounding. */
    public boolean lineSumRoundingOnly;

    /**
     * Whether the VAT the lines produce equals the VAT printed on the document. This is the check
     * invoice 576 was missing: its net matched to the stotinka while its VAT was 0.01 out, and
     * nothing looked.
     */
    public Boolean taxMatchesHeader;

    /** Signed difference {@code lineDerivedTax - headerTaxTotal}; null when unchecked. */
    public BigDecimal taxDelta;

    /** True when {@link #taxMatchesHeader} is false only by rounding. */
    public boolean taxRoundingOnly;

    /**
     * {@code (expense debits + VAT debits) − payable credit}, from {@link OcrAccountingTotals}.
     *
     * <p>Deliberately not one of the reconciliation figures above. Those compare the extraction
     * against itself and a difference there is a diagnostic; this compares the invoice's stored
     * accounting structures against each other, and a difference here is the move failing to
     * balance. Null when not computed.
     */
    public BigDecimal postingDelta;

    /** The imbalance in words, from {@link OcrAccountingTotals#postingDifferences()}. */
    public final List<String> postingDifferences = new ArrayList<>();

    /**
     * Set when the imbalance is per-line VAT rounding that only a missing {@code manageByAmount}
     * flag prevents {@link OcrVatRoundingReconciler} from correcting — see its refusal reason. Null
     * when the imbalance is anything else.
     */
    public String postingRoundingHint;

    public final List<String> warnings = new ArrayList<>();

    /** Per-line detail, so a caller can act on a specific line rather than parse the prose. */
    public final List<LineIssue> lineIssues = new ArrayList<>();
  }

  /**
   * What is wrong with one invoice line.
   *
   * <p>Deliberately addressed by {@code index} (position in {@code invoiceLineList}) rather than by
   * id, so {@link #evaluate(Signals)} stays pure and unit-testable with no JPA in sight. Callers
   * that need the entity resolve the index against the same list.
   */
  public static final class LineIssue {
    private final int index;
    private final Long lineId;
    private final String description;
    private final BigDecimal taxRate;
    private final boolean missingProduct;
    private final boolean missingAccount;
    private final boolean missingTax;

    public LineIssue(
        int index,
        Long lineId,
        String description,
        BigDecimal taxRate,
        boolean missingProduct,
        boolean missingAccount,
        boolean missingTax) {
      this.index = index;
      this.lineId = lineId;
      this.description = description;
      this.taxRate = taxRate;
      this.missingProduct = missingProduct;
      this.missingAccount = missingAccount;
      this.missingTax = missingTax;
    }

    public int getIndex() {
      return index;
    }

    public Long getLineId() {
      return lineId;
    }

    public String getDescription() {
      return description;
    }

    public BigDecimal getTaxRate() {
      return taxRate;
    }

    public boolean isMissingProduct() {
      return missingProduct;
    }

    public boolean isMissingAccount() {
      return missingAccount;
    }

    public boolean isMissingTax() {
      return missingTax;
    }

    public boolean isEmpty() {
      return !missingProduct && !missingAccount && !missingTax;
    }
  }

  /** Pure readiness evaluation — no side effects, no I/O. */
  public OcrInvoiceReadiness evaluate(Signals s) {
    OcrInvoiceReadiness r = new OcrInvoiceReadiness();
    r.getWarnings().addAll(s.warnings);

    // ── Validate blockers (company config + partner account + currency) ─────────
    if (s.supplierPurchaseJournalConfigured) {
      r.getPopulated().add("Supplier purchase journal configured.");
    } else {
      r.getValidateBlockers()
          .add("Supplier purchase journal not configured (Company → AccountConfig).");
    }
    if (s.partnerAccountResolvable) {
      r.getPopulated()
          .add(
              "Supplier payable account resolvable"
                  + (s.partnerAccountLabel == null ? "" : ": " + s.partnerAccountLabel)
                  + ".");
      if (!s.partnerAccountReconcilable) {
        // Name the account and the flag that is actually off. This is the one blocker whose fix is
        // a single switch on a single record, so a message that stops at "not reconcilable" turns a
        // one-click repair into a search through the chart of accounts.
        StringBuilder missing = new StringBuilder();
        if (!s.partnerAccountReconcileOk) {
          missing.append("reconcileOk");
        }
        if (!s.partnerAccountUseForPartnerBalance) {
          missing.append(missing.length() == 0 ? "" : " and ").append("useForPartnerBalance");
        }
        r.getValidateBlockers()
            .add(
                "Supplier payable account "
                    + (s.partnerAccountLabel == null ? "(unknown)" : s.partnerAccountLabel)
                    + " has "
                    + (missing.length() == 0 ? "a missing flag" : missing.toString())
                    + " off, so Axelor will refuse to validate or ventilate this invoice. Both"
                    + " reconcileOk and useForPartnerBalance must be on.");
      }
    } else {
      r.getValidateBlockers()
          .add(
              "Supplier payable account not resolvable "
                  + "(AccountingSituation or AccountConfig.supplierAccount).");
    }
    if (!s.currencyPresent) {
      r.getValidateBlockers().add("Currency missing.");
    }

    // ── Ventilate blockers (payment terms, dates, sequence, line accounts) ──────
    if (s.paymentModePresent) {
      r.getPopulated().add("Payment mode set.");
    } else {
      r.getVentilateBlockers().add("Payment mode missing (partner or AccountConfig default).");
    }
    if (!s.paymentConditionPresent) {
      r.getVentilateBlockers().add("Payment condition missing (partner or AccountConfig default).");
    } else if (s.paymentConditionProblem != null) {
      // Present but not usable. Named as a configuration record rather than as a document problem,
      // because nothing about this invoice can fix it and every invoice using the same condition
      // will fail identically.
      r.getVentilateBlockers()
          .add(
              "Payment condition "
                  + (s.paymentConditionLabel == null ? "(unnamed)" : s.paymentConditionLabel)
                  + " is not correctly configured, so Axelor will refuse to post: "
                  + s.paymentConditionProblem
                  + " It needs at least one payment-condition line whose percentages total 100"
                  + " (Accounting → Configuration → Financial → Payment conditions). This is company"
                  + " configuration, not a problem with this document — no invoice using this"
                  + " condition can be ventilated, and it also explains an invoice with no payment"
                  + " terms.");
    } else {
      r.getPopulated().add("Payment condition set.");
    }
    // Terms are the payable side of the move, not paperwork. Checked after the payment condition
    // because a broken condition is the cause and this is its consequence: reported together, the
    // two say what to fix and what to fix afterwards.
    if (s.invoiceTermsExpectedNow && s.invoiceTermCount == 0) {
      // The repair regenerates the terms from this same condition, so it is only offered when the
      // condition itself is usable now — otherwise the blocker above is the one to act on.
      boolean repairable = s.paymentConditionPresent && s.paymentConditionProblem == null;
      r.getVentilateBlockers()
          .add(
              "MISSING_INVOICE_TERMS: this validated invoice has no payment terms, and Axelor"
                  + " builds the supplier credit line from them — posting it would produce an entry"
                  + " with debits and no credit, which is refused with a confusing message about"
                  + " special/commitment accounts. They were not generated because the payment"
                  + " condition had no lines when the invoice was validated, and fixing the"
                  + " condition afterwards does not backfill it."
                  + (repairable
                      ? " Repaired with refresh_supplier_invoice_terms, which regenerates them from"
                          + " the condition through Axelor's own term services; then post."
                      : " The payment condition has to be corrected before they can be"
                          + " regenerated."));
    } else if (s.invoiceTermCount > 0) {
      r.getPopulated().add(s.invoiceTermCount + " payment term(s).");
    }
    if (!s.invoiceDatePresent) {
      r.getVentilateBlockers().add("Invoice date missing.");
    }
    if (!s.originDatePresent) {
      r.getVentilateBlockers().add("Supplier origin date missing.");
    }
    if (!s.suppInvSequenceConfigured) {
      r.getVentilateBlockers()
          .add("Supplier invoice sequence not configured (AccountConfig.suppInvSequence).");
    } else if (s.invoiceSequenceVersionMissing) {
      // Configuration, not a document problem: every back-dated invoice in this company hits it,
      // and the number it would be given is already on the first one that posted.
      r.getVentilateBlockers()
          .add(
              "Numbering sequence "
                  + (s.invoiceSequenceLabel == null ? "(unnamed)" : s.invoiceSequenceLabel)
                  + " has no version covering "
                  + (s.invoiceSequenceRefDate == null
                      ? "the invoice date"
                      : s.invoiceSequenceRefDate)
                  + ", so posting would start a new numbering series at 1 and Axelor would refuse"
                  + " with \"The generated sequence ... already exists\" as soon as that number is"
                  + " taken. Its versions start on the day the company was set up, which is after"
                  + " this document's date. Fix the sequence in Administration → Sequences: give it"
                  + " one version whose start date precedes the oldest document you post (and whose"
                  + " next number is above the highest already issued). Company configuration — no"
                  + " change to this invoice will help.");
    }
    if (s.lineCount == 0) {
      r.getVentilateBlockers().add("No invoice lines.");
    } else if (s.linesMissingAccount > 0) {
      r.getVentilateBlockers()
          .add(
              s.linesMissingAccount
                  + " of "
                  + s.lineCount
                  + " line(s) have no expense/purchase account (no product mapping) — map a "
                  + "product or set the account before ventilation.");
    } else {
      r.getPopulated().add(s.lineCount + " line(s) with an account.");
    }
    // Missing tax was previously invisible: BgOcrVatMapping returns null for any rate other than
    // 20/9/0 and the line was then built with no taxLineSet at all, with nothing reporting it.
    if (s.linesMissingTax > 0) {
      r.getVentilateBlockers()
          .add(
              s.linesMissingTax
                  + " of "
                  + s.lineCount
                  + " line(s) have no tax — the VAT rate was not recognised or the tax has no"
                  + " version covering the invoice date. Set the tax before ventilation.");
    } else if (s.lineCount > 0) {
      r.getPopulated().add(s.lineCount + " line(s) with a tax.");
    }
    // The VAT summary is a separate thing from the lines' taxes: the lines say which rate applies,
    // invoiceLineTaxList is the per-rate aggregation Axelor actually posts. A warning rather than a
    // blocker, because validation rebuilds it — but it is the exact condition behind Axelor's
    // "There is no taxes on this invoice" prompt, and a reader deserves to know before clicking.
    if (s.lineCount > 0 && s.linesMissingTax < s.lineCount && s.taxSummaryRowCount == 0) {
      r.getWarnings()
          .add(
              "The VAT summary (the invoice's Tax section) is empty although "
                  + (s.lineCount - s.linesMissingTax)
                  + " line(s) carry a tax. Axelor will ask \"There is no taxes on this invoice. Do"
                  + " you want to proceed?\" on Validate and rebuild the summary itself. If the"
                  + " rebuild fails there, the company's VAT account configuration is the cause.");
    } else if (s.taxSummaryRowCount > 0) {
      r.getPopulated().add("VAT summary: " + s.taxSummaryRowCount + " row(s).");
    }
    // Lines that do not add up to the printed total are the signature of a mis-reconstructed line
    // section: every line can have a product, an account and a tax and the document still be wrong.
    // Invoice 576 read "complete" with two payment/summary rows as its lines. The header total is
    // the independent check on the lines, so a disagreement is a blocker, never a silent note.
    if (Boolean.FALSE.equals(s.lineSumMatchesHeader)) {
      if (s.lineSumRoundingOnly) {
        // Rounding-sized: a real difference, so it is stated, but not something a person can fix
        // and
        // therefore not a reason to refuse ventilation.
        r.getWarnings()
            .add(
                "Invoice lines differ from the printed ex-tax total by "
                    + s.lineSumDelta
                    + " — a rounding difference. The supplier's total is kept as printed.");
      } else {
        r.getVentilateBlockers()
            .add(
                "Invoice lines do not add up to the invoice total: lines differ from the ex-tax"
                    + " total by "
                    + s.lineSumDelta
                    + ". The extracted lines are probably wrong — check them against the document.");
      }
    } else if (Boolean.TRUE.equals(s.lineSumMatchesHeader)) {
      r.getPopulated().add("Lines add up to the invoice total.");
    }
    // The VAT is a second, independent check on the same lines, and it fails in cases the net sum
    // passes: 19.97 net at 20% is 3.99 of VAT, so a document declaring 4.00 does not agree with its
    // own lines even though every net figure matches. Reported either way, never silently accepted.
    if (Boolean.FALSE.equals(s.taxMatchesHeader)) {
      if (s.taxRoundingOnly) {
        // Corrected: this used to say the calculated figure was what would be posted. It is not.
        // Axelor derives the VAT once per rate from the aggregated base, so the invoice's own
        // taxTotal is what is debited — the reconstruction difference has no accounting effect.
        r.getWarnings()
            .add(
                "Re-adding the individually rounded per-line VAT gives "
                    + s.taxDelta
                    + " less/more than the invoice's VAT total — supplier rounding, with no"
                    + " accounting effect. The invoice's own totals are what get posted and"
                    + " settled (see accountingTotals); quote those, not this reconstruction.");
      } else {
        r.getVentilateBlockers()
            .add(
                "The VAT calculated from the lines differs from the VAT on the document by "
                    + s.taxDelta
                    + ", which rounding cannot explain. Either a line's VAT rate is wrong or the"
                    + " document's VAT was misread — check both before ventilation.");
      }
    } else if (Boolean.TRUE.equals(s.taxMatchesHeader)) {
      r.getPopulated().add("Line VAT matches the VAT on the document.");
    }
    // The one piece of arithmetic that can actually stop a posting, kept separate from every
    // reconstruction check above. Expense debits come from the lines' company ex-tax totals, VAT
    // debits from the per-rate summary, and the payable credit is the invoice's company gross; if
    // those three disagree the Move cannot balance, whatever the extraction says. Decided in Java
    // and stated as a blocker, so nobody has to derive it from the diagnostic figures.
    if (s.postingDelta != null && s.postingDelta.signum() != 0) {
      // A supplier that rounds VAT per line leaves exactly this gap, and it is fixable by
      // configuration rather than by editing the lines — so say that instead of sending the reader
      // to hunt through a line section that is actually correct.
      if (s.postingRoundingHint != null) {
        r.getVentilateBlockers()
            .add(
                "Axelor cannot post this invoice yet: "
                    + String.join(" ", s.postingDifferences)
                    + " "
                    + s.postingRoundingHint);
      } else {
        r.getVentilateBlockers()
            .add(
                "The invoice's own accounting figures do not add up, so Axelor cannot post it: "
                    + String.join(" ", s.postingDifferences)
                    + " This is an internal inconsistency between the stored totals and the VAT"
                    + " summary or the lines, not a rounding difference in the extraction. Re-check"
                    + " the lines and the Tax section before posting.");
      }
    }
    r.getLineIssues().addAll(s.lineIssues);
    return r;
  }

  /**
   * Whether the invoice-number sequence has a version covering this invoice's date.
   *
   * <p>Mirrors {@code VentilateState.setInvoiceId}: the same sequence it would pick for this
   * operation type, and the same reference date it would pass ({@code invoice.getInvoiceDate()}),
   * looked up through Axelor's own {@code SequenceVersionRepository.findByDate}. Nothing about the
   * number itself is computed — only whether Axelor would have to create a version, which is what
   * restarts the series at 1.
   *
   * <p>Silent for anything it cannot establish. A readiness signal that guesses would be worse than
   * one that says nothing, because this one refuses a posting.
   */
  protected void checkInvoiceSequenceVersion(
      Invoice invoice, AccountConfig accountConfig, Signals s) {
    LocalDate refDate = invoice.getInvoiceDate();
    if (refDate == null) {
      return; // already a blocker of its own
    }
    Integer type = invoice.getOperationTypeSelect();
    Sequence invoiceSequence =
        type != null && type == InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND
            ? accountConfig.getSuppRefSequence()
            : accountConfig.getSuppInvSequence();

    // The move's reference comes from the journal's sequence with the same kind of lookup
    // (MoveSequenceService: journal.getSequence(), move.getDate(), Move.class, "reference"), so it
    // carries the identical trap. Posting fails on the invoice number first only because
    // setInvoiceId runs before setMove — fixing one and not the other just moves the failure.
    Journal journal = invoice.getJournal();
    if (journal == null) {
      try {
        journal = Beans.get(AccountConfigService.class).getSupplierPurchaseJournal(accountConfig);
      } catch (Exception e) {
        journal = null;
      }
    }
    Sequence moveSequence = journal == null ? null : journal.getSequence();

    List<String> misaligned = new ArrayList<>();
    for (Sequence sequence : new Sequence[] {invoiceSequence, moveSequence}) {
      if (sequence == null) {
        continue; // absence is reported by its own signal, or is not this method's business
      }
      try {
        if (Beans.get(SequenceVersionRepository.class).findByDate(sequence, refDate) == null) {
          misaligned.add(label(sequence));
        }
      } catch (Exception e) {
        // Unknown, so say nothing. Never refuse a posting on a check that could not run.
      }
    }
    if (!misaligned.isEmpty()) {
      s.invoiceSequenceVersionMissing = true;
      s.invoiceSequenceLabel = String.join(" and ", misaligned);
      s.invoiceSequenceRefDate = refDate.toString();
    }
  }

  private static String label(Sequence sequence) {
    String name = sequence.getFullName() == null ? sequence.getName() : sequence.getFullName();
    return name == null ? "(unnamed)" : name;
  }

  /**
   * Whether the per-rate VAT summary exists yet.
   *
   * <p>The posting identity is only checkable once validation has built {@code invoiceLineTaxList};
   * before that the VAT side is legitimately zero and the check would report every draft as
   * inconsistent. The empty-summary case has its own warning above.
   */
  private static boolean s2TaxSummaryPresent(Invoice invoice) {
    return invoice.getInvoiceLineTaxList() != null && !invoice.getInvoiceLineTaxList().isEmpty();
  }

  /** Gather signals from the real invoice + config services, then evaluate. Read-only. */
  public OcrInvoiceReadiness check(Invoice invoice, List<String> ocrWarnings) {
    Signals s = new Signals();
    if (ocrWarnings != null) {
      s.warnings.addAll(ocrWarnings);
    }
    s.currencyPresent = invoice.getCurrency() != null;
    s.paymentModePresent = invoice.getPaymentMode() != null;
    OcrAccountingTotals totals = OcrAccountingTotals.of(invoice);
    // Only meaningful once there is something to post: a draft with no VAT summary yet would
    // otherwise look inconsistent purely because validation has not run.
    if (s2TaxSummaryPresent(invoice) && totals.getCompanyInTaxTotal().signum() != 0) {
      s.postingDelta = totals.postingDelta();
      s.postingDifferences.addAll(totals.postingDifferences());
      // Read-only: names the cause when the imbalance is per-line VAT rounding waiting on a tax
      // flag, so the blocker does not send the reader to a line section that is actually correct.
      OcrVatRoundingReconciler.Outcome rounding = OcrVatRoundingReconciler.diagnose(invoice);
      if (rounding.isBlockedByTaxConfig()) {
        s.postingRoundingHint = rounding.getRefusalReason();
      }
    }
    s.invoiceTermCount =
        invoice.getInvoiceTermList() == null ? 0 : invoice.getInvoiceTermList().size();
    // Only past DRAFT, and only when there is something to settle: a zero-gross invoice produces no
    // move at all, so it needs no terms either.
    s.invoiceTermsExpectedNow =
        invoice.getStatusSelect() != null
            && invoice.getStatusSelect() > InvoiceRepository.STATUS_DRAFT
            && invoice.getInTaxTotal() != null
            && invoice.getInTaxTotal().signum() != 0;
    s.paymentConditionPresent = invoice.getPaymentCondition() != null;
    if (s.paymentConditionPresent) {
      PaymentCondition condition = invoice.getPaymentCondition();
      s.paymentConditionLabel = condition.getName();
      try {
        // Axelor's own rule, called rather than copied: whatever it enforces at ventilation is
        // exactly what is reported here.
        Beans.get(PaymentConditionService.class).checkPaymentCondition(condition);
      } catch (AxelorException e) {
        s.paymentConditionProblem =
            e.getMessage() == null || e.getMessage().trim().isEmpty()
                ? "it has no usable payment-condition lines."
                : e.getMessage().trim();
      } catch (Exception e) {
        // Anything else — no binding, no session — means the rule could not be evaluated. That is
        // not evidence of a broken condition, and reporting it as one would invent a blocker.
      }
    }
    s.invoiceDatePresent = invoice.getInvoiceDate() != null;
    s.originDatePresent = invoice.getOriginDate() != null;
    s.taxSummaryRowCount =
        invoice.getInvoiceLineTaxList() == null ? 0 : invoice.getInvoiceLineTaxList().size();

    List<InvoiceLine> lines = invoice.getInvoiceLineList();
    if (lines != null) {
      s.lineCount = lines.size();
      // One shared computation for the net sum, the derived VAT and the rounding classification, so
      // readiness, the record projection and the line editor cannot disagree about the same
      // figures.
      OcrAmountReconciliation amounts = OcrAmountReconciliation.of(invoice);
      BigDecimal headerBase = invoice.getExTaxTotal();
      if (!lines.isEmpty() && headerBase != null && headerBase.signum() != 0) {
        OcrAmountReconciliation.Comparison exTax = amounts.getExTax();
        s.lineSumDelta = scaled(exTax.getDelta());
        s.lineSumMatchesHeader = exTax.isExact();
        s.lineSumRoundingOnly = exTax.isRounding();
      }
      OcrAmountReconciliation.Comparison tax = amounts.getTax();
      if (tax.getVerdict() != OcrAmountReconciliation.Verdict.NOT_CHECKED) {
        s.taxDelta = scaled(tax.getDelta());
        s.taxMatchesHeader = tax.isExact();
        s.taxRoundingOnly = tax.isRounding();
      }
      for (int i = 0; i < lines.size(); i++) {
        InvoiceLine line = lines.get(i);
        boolean noProduct = line.getProduct() == null;
        boolean noAccount = line.getAccount() == null;
        boolean noTax = line.getTaxLineSet() == null || line.getTaxLineSet().isEmpty();
        if (noAccount) {
          s.linesMissingAccount++;
        }
        if (noProduct) {
          s.linesMissingProduct++;
        }
        if (noTax) {
          s.linesMissingTax++;
        }
        if (noProduct || noAccount || noTax) {
          s.lineIssues.add(
              new LineIssue(
                  i,
                  line.getId(),
                  firstNonBlank(line.getProductName(), line.getDescription()),
                  line.getTaxRate(),
                  noProduct,
                  noAccount,
                  noTax));
        }
      }
    }

    Company company = invoice.getCompany();
    AccountConfigService accountConfigService = Beans.get(AccountConfigService.class);
    if (company != null) {
      AccountConfig accountConfig = null;
      try {
        accountConfig = accountConfigService.getAccountConfig(company);
      } catch (Exception e) {
        // no AccountConfig for this company
      }
      if (accountConfig != null) {
        try {
          s.supplierPurchaseJournalConfigured =
              accountConfigService.getSupplierPurchaseJournal(accountConfig) != null;
        } catch (Exception e) {
          // journal not configured
        }
        s.suppInvSequenceConfigured = accountConfig.getSuppInvSequence() != null;
        checkInvoiceSequenceVersion(invoice, accountConfig, s);
      }
    }

    try {
      Account payable =
          Beans.get(AccountingSituationService.class).getPartnerAccount(invoice, false);
      if (payable != null) {
        s.partnerAccountResolvable = true;
        s.partnerAccountLabel = PartnerAccountService.label(payable);
        s.partnerAccountReconcileOk = Boolean.TRUE.equals(payable.getReconcileOk());
        s.partnerAccountUseForPartnerBalance =
            Boolean.TRUE.equals(payable.getUseForPartnerBalance());
        s.partnerAccountReconcilable =
            s.partnerAccountReconcileOk && s.partnerAccountUseForPartnerBalance;
      }
    } catch (Exception e) {
      // payable account not resolvable from config
    }

    return evaluate(s);
  }

  /** Keeps a delta readable in a message: 0E-8 is technically zero but tells a reader nothing. */
  private static BigDecimal scaled(BigDecimal delta) {
    if (delta == null) {
      return null;
    }
    return delta.stripTrailingZeros().scale() < 0 ? delta.setScale(2) : delta;
  }

  private static String firstNonBlank(String first, String second) {
    return first != null && !first.trim().isEmpty() ? first.trim() : second;
  }
}

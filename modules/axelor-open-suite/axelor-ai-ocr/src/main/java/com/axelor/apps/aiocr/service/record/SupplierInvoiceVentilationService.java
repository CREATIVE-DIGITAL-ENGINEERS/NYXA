package com.axelor.apps.aiocr.service.record;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLineTax;
import com.axelor.apps.account.db.InvoiceTerm;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.db.repo.PaymentModeRepository;
import com.axelor.apps.account.service.PaymentConditionService;
import com.axelor.apps.account.service.app.AppAccountService;
import com.axelor.apps.account.service.invoice.InvoiceControlService;
import com.axelor.apps.account.service.invoice.InvoiceLineAnalyticService;
import com.axelor.apps.account.service.invoice.InvoiceService;
import com.axelor.apps.account.service.invoice.InvoiceTermService;
import com.axelor.apps.aiaccountant.security.AiDraftOnlyRefusal;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.apps.aiocr.service.readiness.OcrAmountReconciliation;
import com.axelor.apps.aiocr.service.readiness.OcrInvoiceReadiness;
import com.axelor.apps.aiocr.service.readiness.OcrSupplierInvoiceReadinessService;
import com.axelor.apps.base.AxelorException;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ventilates — posts — a validated OCR supplier invoice through Axelor's own lifecycle.
 *
 * <h2>What the Ventilate button actually does</h2>
 *
 * {@code Invoice.xml:1321-1324}. The button carries a confirm prompt, then {@code
 * action-invoice-group-ventilate}:
 *
 * <ol>
 *   <li>{@code set-supplier-invoice-number-and-origin-date} — only when a supplier document is
 *       missing either; it opens a wizard for a person to fill in. Not reachable here: both are
 *       readiness blockers long before this point;
 *   <li>{@code action-invoice-group-ventilate-button} → {@code
 *       action-invoice-group-before-ventilate} ({@code Invoice.xml:1768-1777}), then {@code save},
 *       then {@code action-invoice-method-ventilate}.
 * </ol>
 *
 * <p>{@code before-ventilate} is seven controls: the two invoice-term <b>errors</b>, the PFP
 * validator <b>alert</b>, unlettered advance payments and unimputed refunds (both {@code
 * response.setInfo} — <b>advisory</b>), the cut-off-date <b>error</b>, the analytic-axis
 * <b>error</b>, and a recompute of the term due dates. The {@code save} that follows runs the
 * form's {@code action-invoice-onsave-group}, which is where the duplicate supplier-invoice-number
 * <b>alert</b> lives. All of them are performed below; see {@link #runAxelorPreVentilationControls}
 * for which become refusals and why.
 *
 * <p>The final step is {@code InvoiceController.ventilate} → {@code InvoiceService.ventilate}, and
 * that is what this class calls. Everything that follows is Axelor's: {@code
 * InvoiceServiceImpl.ventilateProcess} runs {@code checkPreconditions} (status VALIDATED, payment
 * condition, payment mode, multi-term policy, an account on every line, analytic authorisation),
 * then {@code VentilateFactory.getVentilator(invoice).process()} — {@code VentilateState}, which in
 * order: checks the invoice and origin dates are not in the future, recomputes the term due dates
 * and the invoice due date, resolves the journal, resolves the partner account and re-runs {@code
 * checkUseForPartnerBalanceAndReconcileOk}, draws the supplier-invoice number from {@code
 * AccountConfig.suppInvSequence}, and then {@code setMove()} → {@code
 * MoveCreateFromInvoiceService.createMove}, which builds the {@code Move} and its {@code
 * MoveLine}s, saves it, attaches it to the invoice and calls {@code MoveValidateService.accounting}
 * to post it. Only after that does {@code setStatus()} set VENTILATED.
 *
 * <p>No status is written here and no {@code Move} or {@code MoveLine} is constructed here. This
 * class decides <em>whether</em> to call, and verifies afterwards what came back.
 *
 * <h2>Nothing is repaired on the way</h2>
 *
 * Unlike {@link SupplierInvoiceValidationService}, which refreshes derived state before validating,
 * this class only ever <b>reads</b> before posting. The figures on a validated invoice are the ones
 * a person approved; rebuilding the VAT summary or the invoice terms here would silently change
 * what gets posted relative to what was approved. So each of those is checked and, if it is wrong,
 * refused with one blocker for the user to deal with — never quietly fixed.
 *
 * <h2>Atomicity</h2>
 *
 * {@code ventilateProcess} is itself {@code @Transactional(rollbackOn = Exception.class)} and this
 * method joins that same transaction, so the post-conditions checked afterwards ({@link
 * #verifyPosting}) can still roll the whole posting back — invoice status, sequence number, {@code
 * Move} and {@code MoveLine}s together. There is no state in which a half-posted invoice survives.
 */
@Singleton
public class SupplierInvoiceVentilationService {

  private static final Logger LOG =
      LoggerFactory.getLogger(SupplierInvoiceVentilationService.class);

  /** A move must balance exactly; this only guards against a scale artefact in the comparison. */
  private static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.005");

  /** The invoice total and the sum of its terms must agree to the currency's last decimal. */
  private static final BigDecimal TERM_TOLERANCE = new BigDecimal("0.01");

  private static final int MAX_BLOCKERS = 20;

  /**
   * Posts the validated invoice behind an OCR record.
   *
   * @param acknowledgeDuplicate the user's answer to Axelor's duplicate-number alert, and nothing
   *     else
   * @throws IllegalArgumentException / IllegalStateException on any refusal — both roll the
   *     transaction back, so nothing was posted
   */
  @Transactional(rollbackOn = Exception.class)
  public Map<String, Object> ventilate(long uploadId, boolean acknowledgeDuplicate) {
    // Re-read from the database now. Whatever an earlier turn reported about this record is not
    // evidence about its state at the moment of posting.
    OcrDraftInvoiceGuard.Target target = OcrDraftInvoiceGuard.resolveValidated(uploadId);
    AiOcrUpload upload = target.getUpload();
    Invoice invoice = target.getInvoice();

    requireSupplierInvoice(invoice);
    requireReadyToVentilate(invoice);
    requirePartnerAccount(invoice);
    requireCurrentTaxSummary(invoice);
    requireValidInvoiceTerms(invoice);
    List<String> notices = runAxelorPreVentilationControls(invoice, acknowledgeDuplicate);

    try {
      // The real thing — the same call InvoiceController.ventilate makes, on the same entity.
      Beans.get(InvoiceService.class).ventilate(invoice);
    } catch (Exception e) {
      throw new OcrBlocked(
          AiBlockerCode.AXELOR_REFUSED_POSTING,
          AiDraftOnlyRefusal.find(e) != null
              ? AiDraftOnlyRefusal.MESSAGE
              : "Axelor refused to post invoice "
                  + invoice.getId()
                  + ": "
                  + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().trim()),
          e);
    }

    Move move = verifyPosting(invoice);

    note(invoice, move);
    upload.setProcessedOn(LocalDateTime.now());
    JPA.save(upload);
    JPA.flush();

    AiWriteEffects.touched(Invoice.class.getName(), invoice.getId());
    AiWriteEffects.touched(AiOcrUpload.class.getName(), upload.getId());
    if (move != null) {
      AiWriteEffects.touched(Move.class.getName(), move.getId());
    }

    LOG.info(
        "OCR record {}: invoice {} ventilated by {} — move {}.",
        uploadId,
        invoice.getId(),
        AiExecutionContext.callerDescription(),
        move == null ? "(none)" : move.getReference());

    return result(upload, invoice, move, notices);
  }

  // ---------------------------------------------------------------------------
  // Hard preconditions
  // ---------------------------------------------------------------------------

  /** Supplier documents only — the operation type decides accounts, dates and payment direction. */
  protected void requireSupplierInvoice(Invoice invoice) {
    Integer type = invoice.getOperationTypeSelect();
    if (type == null
        || (type != InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE
            && type != InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND)) {
      throw new OcrBlocked(
          AiBlockerCode.NOT_A_SUPPLIER_INVOICE,
          "Invoice " + invoice.getId() + " is not a supplier invoice; refusing to post it.");
    }
  }

  /**
   * Readiness, recomputed from the record at this instant: no missing review field, no validate or
   * ventilate blocker, and the amounts not a mismatch.
   *
   * <p>The mismatch check is a residual one by this point — validation recomputed the header totals
   * from the lines, so the header and the lines now agree by construction and the real bar was the
   * one {@code validate_supplier_invoice} applied. It stays because a document whose figures
   * disagree must not be posted whatever the reason, and because an invoice can be sent back to
   * draft, edited and re-validated between the two steps.
   */
  protected void requireReadyToVentilate(Invoice invoice) {
    List<String> missing = BgOcrIntakeService.missingReviewFields(invoice);
    OcrInvoiceReadiness readiness =
        Beans.get(OcrSupplierInvoiceReadinessService.class).check(invoice, new ArrayList<>());

    List<String> reasons = new ArrayList<>();
    if (!missing.isEmpty()) {
      reasons.add("review fields still missing: " + String.join(", ", missing));
    }
    reasons.addAll(readiness.getValidateBlockers());
    reasons.addAll(readiness.getVentilateBlockers());
    if (!reasons.isEmpty()) {
      throw new OcrBlocked(
          AiBlockerCode.NOT_READY_TO_POST,
          "Invoice "
              + invoice.getId()
              + " cannot be posted:\n- "
              + String.join("\n- ", cap(reasons))
              + "\nNothing was posted.");
    }

    OcrAmountReconciliation amounts = OcrAmountReconciliation.of(invoice);
    if (amounts.isMismatch()) {
      throw new OcrBlocked(
          AiBlockerCode.AMOUNTS_DO_NOT_RECONCILE,
          "Invoice "
              + invoice.getId()
              + " cannot be posted because its amounts do not agree:\n- "
              + String.join("\n- ", amounts.differences())
              + "\nNothing was posted.");
    }
  }

  /**
   * The account the payable side of the move will be written to.
   *
   * <p>Validation already resolved and stored it, so this checks the <em>stored</em> account rather
   * than re-resolving one: {@code VentilateState.setPartnerAccount} keeps whatever is on the
   * invoice and only re-runs the flag check on it. Reporting it here means the user is told which
   * account is wrong before a posting attempt fails on it.
   */
  protected void requirePartnerAccount(Invoice invoice) {
    Account account = invoice.getPartnerAccount();
    if (account == null) {
      throw new OcrBlocked(
          AiBlockerCode.MISSING_PARTNER_ACCOUNT,
          "Invoice "
              + invoice.getId()
              + " has no partner account, so there is nothing to post the supplier side to."
              + " Nothing was posted.");
    }
    if (!Boolean.TRUE.equals(account.getReconcileOk())
        || !Boolean.TRUE.equals(account.getUseForPartnerBalance())) {
      throw new OcrBlocked(
          AiBlockerCode.PARTNER_ACCOUNT_MISCONFIGURED,
          "fix_partner_account",
          "The partner account of invoice "
              + invoice.getId()
              + " ("
              + account.getCode()
              + " "
              + account.getName()
              + ") does not have both reconcileOk and useForPartnerBalance on, so Axelor will"
              + " refuse to post it. Correct the account first; nothing was posted.");
    }
    if (account.getCompany() != null
        && invoice.getCompany() != null
        && !account.getCompany().getId().equals(invoice.getCompany().getId())) {
      throw new OcrBlocked(
          AiBlockerCode.PARTNER_ACCOUNT_WRONG_COMPANY,
          "The partner account of invoice "
              + invoice.getId()
              + " belongs to another company; refusing to post.");
    }
  }

  /**
   * The VAT summary must already be there and must match the lines.
   *
   * <p>Checked, never rebuilt. {@code invoiceLineTaxList} is what the tax move lines are generated
   * from, and it was computed at validation from the lines a person approved. Rebuilding it now
   * could change the posted VAT without anyone seeing it.
   */
  protected void requireCurrentTaxSummary(Invoice invoice) {
    boolean anyLineTaxed =
        invoice.getInvoiceLineList() != null
            && invoice.getInvoiceLineList().stream()
                .anyMatch(l -> l.getTaxLineSet() != null && !l.getTaxLineSet().isEmpty());
    List<InvoiceLineTax> rows = invoice.getInvoiceLineTaxList();
    if (anyLineTaxed && (rows == null || rows.isEmpty())) {
      throw new OcrBlocked(
          AiBlockerCode.MISSING_TAX_SUMMARY,
          "Invoice "
              + invoice.getId()
              + " has taxed lines but an empty VAT summary, so the VAT would not be posted."
              + " Re-validate it so Axelor rebuilds the summary; nothing was posted.");
    }
    if (rows == null) {
      return;
    }
    BigDecimal summaryTax = BigDecimal.ZERO;
    for (InvoiceLineTax row : rows) {
      summaryTax = summaryTax.add(row.getTaxTotal() == null ? BigDecimal.ZERO : row.getTaxTotal());
      if (row.getTaxLine() == null) {
        throw new OcrBlocked(
            AiBlockerCode.TAX_SUMMARY_ROW_WITHOUT_TAX_LINE,
            "A VAT summary row of invoice "
                + invoice.getId()
                + " carries no tax line, so its account cannot be resolved. Nothing was posted.");
      }
    }
    BigDecimal headerTax = invoice.getTaxTotal() == null ? BigDecimal.ZERO : invoice.getTaxTotal();
    if (summaryTax.subtract(headerTax).abs().compareTo(TERM_TOLERANCE) > 0) {
      throw new OcrBlocked(
          AiBlockerCode.STALE_TAX_SUMMARY,
          "The VAT summary of invoice "
              + invoice.getId()
              + " totals "
              + summaryTax
              + " but the invoice VAT is "
              + headerTax
              + ", so the summary is stale. Re-validate it; nothing was posted.");
    }
  }

  /**
   * Terms exist, each has a due date, and together they account for the whole invoice.
   *
   * <p>The terms are what the payable move line's due dates and open items are built from, so a
   * missing or short term set posts a debt nobody can settle correctly. Checked only — {@code
   * VentilateState.setDate} recomputes the due dates itself.
   */
  protected void requireValidInvoiceTerms(Invoice invoice) {
    List<InvoiceTerm> terms = invoice.getInvoiceTermList();
    BigDecimal gross = invoice.getInTaxTotal() == null ? BigDecimal.ZERO : invoice.getInTaxTotal();
    if (terms == null || terms.isEmpty()) {
      if (gross.signum() == 0) {
        return; // nothing to settle; Axelor creates no move either
      }
      // Deliberately not "re-validate it": that is not a thing the user can do to a VALIDATED
      // invoice. Nor "re-select the payment condition in the form" any more — the terms come from
      // the condition's lines, and when there were none at validation computeInvoiceTerms returned
      // silently, which SupplierInvoiceTermsRefreshService now repairs through Axelor's own term
      // services. Sending a person to the UI for an operation this server can perform is obsolete
      // advice, so the refusal names the repair instead, and names it in a field rather than only
      // in prose.
      boolean repairable = paymentConditionUsable(invoice);
      throw new OcrBlocked(
          AiBlockerCode.MISSING_INVOICE_TERMS,
          repairable ? "refresh_supplier_invoice_terms" : null,
          "Invoice "
              + invoice.getId()
              + " has no payment terms. Axelor builds the supplier credit line from them, so"
              + " posting would produce an entry with debits and no credit at all"
              + (invoice.getPaymentCondition() == null
                  ? ", and this invoice has no payment condition to generate them from."
                  : repairable
                      ? ". They were not generated because payment condition "
                          + invoice.getPaymentCondition().getName()
                          + " had no lines when the invoice was validated, and correcting the"
                          + " condition afterwards does not backfill them. The condition is usable"
                          + " now, so refresh_supplier_invoice_terms regenerates them from it and"
                          + " posting can then proceed."
                      : ". Payment condition "
                          + invoice.getPaymentCondition().getName()
                          + " is itself not correctly configured, so the terms cannot be"
                          + " regenerated from it — that has to be fixed first (Accounting →"
                          + " Configuration → Financial → Payment conditions).")
              + " Nothing was posted.");
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (InvoiceTerm term : terms) {
      if (term.getDueDate() == null) {
        throw new OcrBlocked(
            AiBlockerCode.INVOICE_TERM_WITHOUT_DUE_DATE,
            "refresh_supplier_invoice_terms",
            "A payment term of invoice "
                + invoice.getId()
                + " has no due date. Nothing was posted.");
      }
      sum = sum.add(term.getAmount() == null ? BigDecimal.ZERO : term.getAmount());
    }
    if (sum.subtract(gross).abs().compareTo(TERM_TOLERANCE) > 0) {
      throw new OcrBlocked(
          AiBlockerCode.INVOICE_TERMS_DO_NOT_MATCH_TOTAL,
          "The payment terms of invoice "
              + invoice.getId()
              + " total "
              + sum
              + " but the invoice is "
              + gross
              + ". Correct the payment condition first; nothing was posted.");
    }
  }

  /**
   * Whether the invoice's payment condition would survive Axelor's own check.
   *
   * <p>Read-only, and asked before offering a repair: {@code refresh_supplier_invoice_terms}
   * regenerates the terms <em>from this condition</em>, so with a broken condition it would refuse
   * too. Only {@link AxelorException} means "not correctly configured"; anything else means the
   * check could not be made, and an unanswerable question is not evidence of a fault.
   */
  protected boolean paymentConditionUsable(Invoice invoice) {
    if (invoice.getPaymentCondition() == null) {
      return false;
    }
    try {
      Beans.get(PaymentConditionService.class).checkPaymentCondition(invoice.getPaymentCondition());
      return true;
    } catch (AxelorException e) {
      return false;
    } catch (RuntimeException e) {
      LOG.warn(
          "Could not check the payment condition of invoice {}: {}",
          invoice.getId(),
          e.getMessage());
      return false;
    }
  }

  /**
   * The controls the Ventilate action performs before the business call.
   *
   * <p>How each kind is treated:
   *
   * <ul>
   *   <li><b>errors</b> — invoice-term creation/holdback, cut-off dates, analytic axes — become
   *       refusals, because Axelor would have stopped there too;
   *   <li><b>the duplicate alert</b> — a genuine question with two defensible answers, so it is a
   *       refusal until the user answers it through {@code acknowledgeDuplicate};
   *   <li><b>the PFP alert</b> — "at least one pfp validator is missing". Deliberately <em>not</em>
   *       acknowledgeable: passed-for-payment is an approval workflow with named approvers, and a
   *       tool clicking through it would defeat the only thing it exists for. It refuses and says
   *       to assign the validator;
   *   <li><b>the advisory notices</b> — unlettered advance payments and unimputed refunds are
   *       {@code response.setInfo} in the UI, i.e. shown but not blocking. They are returned with
   *       the result so the model reports them rather than swallowing them.
   * </ul>
   *
   * @return the advisory notices, if any
   */
  protected List<String> runAxelorPreVentilationControls(
      Invoice invoice, boolean acknowledgeDuplicate) {
    InvoiceService invoiceService = Beans.get(InvoiceService.class);
    InvoiceTermService invoiceTermService = Beans.get(InvoiceTermService.class);

    // Payment data Axelor requires. checkPreconditions repeats these, but failing here means the
    // message names the missing field instead of surfacing a generic refusal from deeper down.
    if (invoice.getPaymentCondition() == null || invoice.getPaymentMode() == null) {
      throw new IllegalArgumentException(
          "Invoice "
              + invoice.getId()
              + " is missing its payment "
              + (invoice.getPaymentCondition() == null ? "condition" : "mode")
              + ", which Axelor requires before posting. Nothing was posted.");
    }
    if (invoice.getBankDetails() == null
        && invoice.getPaymentMode().getTypeSelect() != null
        && invoice.getPaymentMode().getTypeSelect() == PaymentModeRepository.TYPE_TRANSFER) {
      throw new IllegalArgumentException(
          "Invoice "
              + invoice.getId()
              + " is paid by bank transfer but carries no supplier bank account. Set the supplier's"
              + " IBAN first; nothing was posted.");
    }

    // The payment condition has to be usable, not merely present. Axelor checks this inside
    // MoveCreateFromInvoiceServiceImpl — after the move has been built — so without this the
    // refusal would arrive from deep inside ventilation with the move already half-constructed and
    // rolled back. Its own service is called so the rule cannot drift.
    try {
      Beans.get(PaymentConditionService.class).checkPaymentCondition(invoice.getPaymentCondition());
    } catch (AxelorException e) {
      throw new IllegalArgumentException(
          "Axelor will not post invoice "
              + invoice.getId()
              + " because its payment condition is not correctly configured: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().trim())
              + " The condition needs at least one line whose payment percentages total 100"
              + " (Accounting → Configuration → Financial → Payment conditions). This is company"
              + " configuration and cannot be fixed from this invoice. Nothing was posted.");
    }

    if (invoiceTermService.checkInvoiceTermCreationConditions(invoice)) {
      throw new IllegalArgumentException(
          "The payment terms of invoice "
              + invoice.getId()
              + " cannot be created as they are. Nothing was posted.");
    }
    if (invoiceTermService.checkIfThereIsDeletedHoldbackInvoiceTerms(invoice)) {
      throw new IllegalArgumentException(
          "A holdback payment term of invoice "
              + invoice.getId()
              + " has been deleted; Axelor does not allow posting in that state.");
    }

    // PFP — the alert in action-invoice-group-before-ventilate.
    try {
      if (invoiceTermService.getPfpValidatorUserCondition(invoice, null)
          && invoice.getInvoiceTermList() != null
          && invoice.getInvoiceTermList().stream().anyMatch(t -> t.getPfpValidatorUser() == null)) {
        throw new IllegalArgumentException(
            "At least one payment term of invoice "
                + invoice.getId()
                + " has no PFP validator. Passed-for-payment approval is a person's decision and"
                + " cannot be given here — assign the validator on the invoice, then post."
                + " Nothing was posted.");
      }
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      LOG.info(
          "Could not evaluate the PFP condition for invoice {}: {}",
          invoice.getId(),
          e.getMessage());
    }

    // Cut-off dates — an error, and only when the app manages cut-off periods.
    try {
      if (Beans.get(AppAccountService.class).getAppAccount().getManageCutOffPeriod()
          && !invoiceService.checkInvoiceLinesCutOffDates(invoice)) {
        throw new IllegalArgumentException(
            "Invoice "
                + invoice.getId()
                + " has lines without cut-off dates, which this company's configuration requires."
                + " Nothing was posted.");
      }
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      LOG.info("Could not check cut-off dates for invoice {}: {}", invoice.getId(), e.getMessage());
    }

    // Analytic axes — an error.
    try {
      Beans.get(InvoiceLineAnalyticService.class).checkAnalyticAxisByCompany(invoice);
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Analytic configuration refuses invoice "
              + invoice.getId()
              + ": "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().trim()));
    }

    // Duplicate supplier invoice number — the alert in the form's onSave chain, which the
    // ventilate action group also runs. Asked again even if it was answered at validation,
    // because Axelor asks again too.
    if (!acknowledgeDuplicate && Beans.get(InvoiceControlService.class).isDuplicate(invoice)) {
      throw new IllegalArgumentException(
          "Another invoice already exists with number "
              + invoice.getSupplierInvoiceNb()
              + " for "
              + (invoice.getPartner() == null
                  ? "this supplier"
                  : invoice.getPartner().getFullName())
              + " in "
              + (invoice.getOriginDate() == null
                  ? "the same year"
                  : invoice.getOriginDate().getYear())
              + ". Axelor asks the user to confirm this case. Show them the duplicate and post only"
              + " if they say so. Nothing was posted.");
    }

    // Advisory in the UI, advisory here — but reported.
    List<String> notices = new ArrayList<>();
    try {
      String refunds = invoiceService.checkNotImputedRefunds(invoice);
      if (refunds != null && !refunds.trim().isEmpty()) {
        notices.add(refunds.trim());
      }
      String advances = invoiceService.checkNotLetteredAdvancePaymentMoveLines(invoice);
      if (advances != null && !advances.trim().isEmpty()) {
        notices.add(advances.trim());
      }
    } catch (Exception e) {
      LOG.info(
          "Could not gather the advisory ventilation notices for invoice {}: {}",
          invoice.getId(),
          e.getMessage());
    }
    return notices;
  }

  // ---------------------------------------------------------------------------
  // After the call
  // ---------------------------------------------------------------------------

  /**
   * Confirms that what came back is actually a posted document, and rolls the whole thing back if
   * it is not.
   *
   * <p>Three things are asserted, each of which would otherwise be a silently broken posting: the
   * status really is VENTILATED, a {@code Move} exists (unless the invoice totals zero, the one
   * case where {@code VentilateState.setMove} deliberately creates none), and the move balances.
   * Being inside {@code ventilateProcess}'s transaction is what makes throwing here safe: the
   * invoice, the sequence number, the move and its lines all disappear together.
   */
  protected Move verifyPosting(Invoice invoice) {
    if (invoice.getStatusSelect() == null
        || invoice.getStatusSelect() != InvoiceRepository.STATUS_VENTILATED) {
      throw new IllegalStateException(
          "Invoice "
              + invoice.getId()
              + " did not reach the ventilated status; rolling the posting back.");
    }
    BigDecimal gross = invoice.getInTaxTotal() == null ? BigDecimal.ZERO : invoice.getInTaxTotal();
    Move move = invoice.getMove();
    if (move == null) {
      if (gross.signum() == 0) {
        return null; // Axelor creates no move for a zero-total invoice.
      }
      throw new IllegalStateException(
          "Invoice "
              + invoice.getId()
              + " was ventilated but no accounting move was created; rolling the posting back.");
    }
    BigDecimal debit = BigDecimal.ZERO;
    BigDecimal credit = BigDecimal.ZERO;
    if (move.getMoveLineList() != null) {
      for (MoveLine line : move.getMoveLineList()) {
        debit = debit.add(line.getDebit() == null ? BigDecimal.ZERO : line.getDebit());
        credit = credit.add(line.getCredit() == null ? BigDecimal.ZERO : line.getCredit());
      }
    }
    if (debit.subtract(credit).abs().compareTo(BALANCE_TOLERANCE) > 0) {
      throw new IllegalStateException(
          "The accounting move generated for invoice "
              + invoice.getId()
              + " does not balance (debit "
              + debit
              + ", credit "
              + credit
              + "); rolling the posting back.");
    }
    return move;
  }

  /** Server-derived audit: who posted it, when, and what came out. No caller-supplied prose. */
  protected void note(Invoice invoice, Move move) {
    StringBuilder note =
        new StringBuilder("Ventilated by ")
            .append(AiExecutionContext.callerDescription())
            .append('.');
    if (move == null) {
      note.append("\n  No accounting move: the invoice totals zero.");
    } else {
      note.append("\n  Move ")
          .append(move.getReference())
          .append(" in journal ")
          .append(move.getJournal() == null ? "(none)" : move.getJournal().getCode())
          .append(", accounting date ")
          .append(move.getDate() == null ? "(none)" : move.getDate().toString())
          .append('.');
    }
    if (invoice.getInvoiceId() != null) {
      note.append("\n  Invoice number ").append(invoice.getInvoiceId()).append('.');
    }
    String existing = invoice.getAiValidationNotes();
    invoice.setAiValidationNotes(
        existing == null || existing.trim().isEmpty() ? note.toString() : existing + "\n\n" + note);
  }

  /**
   * The compact answer.
   *
   * <p>Move lines are summarised, not listed: a posted move is between two and a dozen lines and
   * the caller needs to know that it balanced and which accounts it touched, not every row. The
   * rows themselves stay one {@code get_ocr_record} away.
   */
  /**
   * The invoice's lifecycle status, read through the same scoping the write path uses.
   *
   * <p>The baseline a lost ventilation is judged against: this tool refuses anything that is not
   * VALIDATED, so the reading afterwards is unambiguous only because the reading before it is
   * known.
   */
  public Integer scopedStatus(long uploadId) {
    OcrDraftInvoiceGuard.Target target = OcrDraftInvoiceGuard.resolveAnyStatus(uploadId);
    return target == null || target.getInvoice() == null
        ? null
        : target.getInvoice().getStatusSelect();
  }

  /**
   * The same answer {@link #ventilate} returns, rebuilt by re-reading the record.
   *
   * <h2>Why this one matters most</h2>
   *
   * Ventilation is the operation that puts a supplier invoice in the books and creates its
   * accounting move. A lost answer here used to leave the worst possible pair of options: retry,
   * and risk a second posting, or report uncertainty about an invoice that is already posted.
   * Neither is necessary — the invoice's status and the move behind it are both readable, and
   * reading them is what this does.
   *
   * <p>The move is taken from the invoice itself ({@code invoice.getMove()}), which is the link
   * ventilation writes; it is never searched for. Company-scoped through the same guard as the
   * write, read only, and null when nothing can be resolved.
   */
  public Map<String, Object> confirmedResult(long uploadId) {
    try {
      OcrDraftInvoiceGuard.Target target = OcrDraftInvoiceGuard.resolveAnyStatus(uploadId);
      if (target == null || target.getInvoice() == null) {
        return null;
      }
      Invoice invoice = target.getInvoice();
      return result(target.getUpload(), invoice, invoice.getMove(), null);
    } catch (RuntimeException e) {
      return null;
    }
  }

  protected Map<String, Object> result(
      AiOcrUpload upload, Invoice invoice, Move move, List<String> notices) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("status", "ventilated");
    m.put("uploadId", upload.getId());
    m.put("invoiceId", invoice.getId());
    m.put("invoiceNumber", invoice.getInvoiceId());
    m.put("supplierInvoiceNb", invoice.getSupplierInvoiceNb());
    m.put("supplier", invoice.getPartner() == null ? null : invoice.getPartner().getFullName());
    m.put("statusSelect", invoice.getStatusSelect());
    m.put("statusLabel", "Ventilated");

    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("exTaxTotal", invoice.getExTaxTotal());
    totals.put("taxTotal", invoice.getTaxTotal());
    totals.put("inTaxTotal", invoice.getInTaxTotal());
    totals.put("currency", invoice.getCurrency() == null ? null : invoice.getCurrency().getCode());
    m.put("totals", totals);

    m.put(
        "partnerAccount",
        invoice.getPartnerAccount() == null
            ? null
            : invoice.getPartnerAccount().getCode() + " " + invoice.getPartnerAccount().getName());
    m.put("vatAccounts", vatAccounts(invoice));
    m.put("dueDate", invoice.getDueDate() == null ? null : invoice.getDueDate().toString());

    if (move == null) {
      m.put("move", null);
      m.put("moveCreated", false);
      m.put("moveNote", "No accounting move: the invoice totals zero.");
    } else {
      BigDecimal debit = BigDecimal.ZERO;
      BigDecimal credit = BigDecimal.ZERO;
      int lineCount = 0;
      if (move.getMoveLineList() != null) {
        lineCount = move.getMoveLineList().size();
        for (MoveLine line : move.getMoveLineList()) {
          debit = debit.add(line.getDebit() == null ? BigDecimal.ZERO : line.getDebit());
          credit = credit.add(line.getCredit() == null ? BigDecimal.ZERO : line.getCredit());
        }
      }
      Map<String, Object> mv = new LinkedHashMap<>();
      mv.put("moveId", move.getId());
      mv.put("reference", move.getReference());
      mv.put("journal", move.getJournal() == null ? null : move.getJournal().getCode());
      mv.put("date", move.getDate() == null ? null : move.getDate().toString());
      mv.put(
          "accountingDate",
          move.getAccountingDate() == null ? null : move.getAccountingDate().toString());
      mv.put("statusSelect", move.getStatusSelect());
      mv.put("statusLabel", moveStatusLabel(move.getStatusSelect()));
      mv.put("lineCount", lineCount);
      mv.put("totalDebit", debit);
      mv.put("totalCredit", credit);
      mv.put("balanced", debit.subtract(credit).abs().compareTo(BALANCE_TOLERANCE) <= 0);
      m.put("move", mv);
      m.put("moveCreated", true);
    }

    if (notices != null && !notices.isEmpty()) {
      m.put("notices", cap(notices));
    }

    Map<String, Object> refresh = new LinkedHashMap<>();
    refresh.put("model", Invoice.class.getName());
    refresh.put("id", invoice.getId());
    m.put("refresh", refresh);
    return m;
  }

  /** The VAT accounts the tax lines were imputed to, deduplicated and in order. */
  protected List<String> vatAccounts(Invoice invoice) {
    Set<String> codes = new LinkedHashSet<>();
    if (invoice.getInvoiceLineTaxList() != null) {
      for (InvoiceLineTax row : invoice.getInvoiceLineTaxList()) {
        Account account = row.getImputedAccount();
        if (account != null) {
          codes.add(account.getCode() + (account.getName() == null ? "" : " " + account.getName()));
        }
      }
    }
    return new ArrayList<>(codes);
  }

  private static String moveStatusLabel(Integer status) {
    if (status == null) {
      return null;
    }
    switch (status) {
      case MoveRepository.STATUS_NEW:
        return "New";
      case MoveRepository.STATUS_DAYBOOK:
        return "Daybook";
      case MoveRepository.STATUS_ACCOUNTED:
        return "Accounted";
      case MoveRepository.STATUS_CANCELED:
        return "Canceled";
      case MoveRepository.STATUS_SIMULATED:
        return "Simulated";
      default:
        return String.valueOf(status);
    }
  }

  private static List<String> cap(List<String> values) {
    if (values == null || values.size() <= MAX_BLOCKERS) {
      return values == null ? new ArrayList<>() : values;
    }
    List<String> out = new ArrayList<>(values.subList(0, MAX_BLOCKERS));
    out.add("… and " + (values.size() - MAX_BLOCKERS) + " more");
    return out;
  }
}

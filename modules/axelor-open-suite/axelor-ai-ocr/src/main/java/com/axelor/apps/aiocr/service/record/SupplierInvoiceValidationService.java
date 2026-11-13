package com.axelor.apps.aiocr.service.record;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceTerm;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.PaymentModeRepository;
import com.axelor.apps.account.service.invoice.InvoiceControlService;
import com.axelor.apps.account.service.invoice.InvoiceLineAnalyticService;
import com.axelor.apps.account.service.invoice.InvoiceService;
import com.axelor.apps.account.service.invoice.InvoiceTermService;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.apps.aiocr.service.readiness.OcrAmountReconciliation;
import com.axelor.apps.aiocr.service.readiness.OcrInvoiceReadiness;
import com.axelor.apps.aiocr.service.readiness.OcrSupplierInvoiceReadinessService;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates an OCR draft supplier invoice through Axelor's own validation lifecycle.
 *
 * <h2>What the Validate button actually does</h2>
 *
 * {@code Invoice.xml:1319-1320} — the button's {@code onClick} is five actions, in this order:
 *
 * <ol>
 *   <li>{@code action-invoice-validate-check-bank-details} — an <b>error</b>: a supplier document
 *       paid by bank transfer ({@code paymentMode.typeSelect == 9}) must carry bank details;
 *   <li>{@code action-invoice-method-validate-invoice-terms-before-save} → {@code
 *       InvoiceController.validateInvoiceTermsBeforeSave}, two <b>errors</b> from {@link
 *       InvoiceTermService}: term creation prohibited, and deleted holdback terms;
 *   <li>{@code save} — which runs the form's own {@code action-invoice-onsave-group}: the duplicate
 *       <b>alert</b> ({@code InvoiceControlService.isDuplicate}), the analytic-axis <b>error</b>,
 *       and — when the invoice is a draft with lines, a payment condition and no terms yet —
 *       generation of the invoice terms and their due dates;
 *   <li>{@code action-validate-invoice-ckeck-invoice-line-tax-list} — an <b>alert</b> when {@code
 *       invoiceLineTaxList} is empty (see {@link OcrInvoiceTaxSummaryService});
 *   <li>{@code action-invoice-method-validate} → {@code InvoiceController.validate} → {@code
 *       InvoiceService.validate(invoice)}.
 * </ol>
 *
 * <p>The last step is the business logic and it is called here unchanged: {@code
 * InvoiceServiceImpl.validateProcess} runs {@code compute(invoice)} (which rebuilds {@code
 * invoiceLineTaxList} via {@code TaxInvoiceLine}, recomputes the header totals from the lines and
 * creates the invoice terms if there are none), then {@code ValidateFactory.getValidator(invoice)
 * .process()} — {@code ValidateState}, which checks the payment-mode direction and partner
 * blocking, sets the status, the validating user and timestamp, resolves {@code partnerAccount}
 * through {@code AccountingSituationService} and enforces {@code
 * InvoiceToolService.checkUseForPartnerBalanceAndReconcileOk}, assigns the journal, and calls
 * {@code WorkflowValidationService.afterValidation}. Nothing about that is reimplemented here and
 * no status field is written by hand.
 *
 * <h2>What this class adds</h2>
 *
 * The four UI steps before the business call are the part an API caller would otherwise skip, so
 * they are performed rather than bypassed — the errors as refusals, and the two <b>alerts</b> as
 * refusals too, because an alert is Axelor asking a person to decide and a tool must not answer on
 * their behalf. The duplicate alert can be answered, but only by the user, and only in a later
 * turn: {@code acknowledgeDuplicate} exists for exactly the case where they were shown the
 * duplicate and said to proceed.
 *
 * <p>On top of that come this module's own bars, which are stricter than Axelor's: the record must
 * be an {@code aiGenerated} DRAFT supplier invoice of the caller's active company ({@link
 * OcrDraftInvoiceGuard}), it must be {@code usable} — no missing review fields and no validate or
 * ventilate blockers — and its amounts must not be a {@link
 * OcrAmountReconciliation.Verdict#MISMATCH MISMATCH}. Readiness is recomputed from the record
 * immediately before the call, never taken from something an earlier turn reported.
 *
 * <p>Everything runs in one transaction that rolls back on any refusal, so a refused validation
 * leaves the record byte-identical — including the derived state refreshed in {@link #prepare}.
 */
@Singleton
public class SupplierInvoiceValidationService {

  private static final Logger LOG = LoggerFactory.getLogger(SupplierInvoiceValidationService.class);

  /** Most invoices have one term; a payment schedule can have several. Enough to see the shape. */
  private static final int MAX_TERMS = 10;

  private static final int MAX_BLOCKERS = 20;

  private final OcrRecordViewService viewService;

  @Inject
  public SupplierInvoiceValidationService(OcrRecordViewService viewService) {
    this.viewService = viewService;
  }

  /**
   * Runs the Validate lifecycle for the draft invoice behind an OCR record.
   *
   * @param acknowledgeDuplicate the user's answer to Axelor's duplicate alert, and nothing else. It
   *     may only be true when they were shown the duplicate and told this one to be validated
   *     anyway.
   * @throws IllegalArgumentException / IllegalStateException on any refusal — both roll the
   *     transaction back, so nothing was changed
   */
  @Transactional(rollbackOn = Exception.class)
  public Map<String, Object> validate(long uploadId, boolean acknowledgeDuplicate, String reason) {
    OcrDraftInvoiceGuard.Target target = OcrDraftInvoiceGuard.resolve(uploadId);
    AiOcrUpload upload = target.getUpload();
    Invoice invoice = target.getInvoice();

    requireSupplierInvoice(invoice);
    prepare(invoice);
    requireReady(invoice);
    runAxelorPreValidationControls(invoice, acknowledgeDuplicate);

    // What the button's `save` step persists before the validation call is made.
    JPA.save(invoice);
    JPA.flush();

    try {
      // The real thing — the same call InvoiceController.validate makes, on the same entity.
      Beans.get(InvoiceService.class).validate(invoice);
    } catch (Exception e) {
      // Axelor's own refusal. Surfaced verbatim: it names the actual accounting reason, and a
      // paraphrase would send the user looking for the wrong problem. Runtime, so this rolls back.
      throw new OcrBlocked(
          AiBlockerCode.AXELOR_REFUSED_VALIDATION,
          "Axelor refused to validate invoice "
              + invoice.getId()
              + ": "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().trim()),
          e);
    }

    note(invoice, reason, acknowledgeDuplicate);
    upload.setProcessedOn(LocalDateTime.now());
    JPA.save(upload);
    JPA.flush();

    AiWriteEffects.touched(Invoice.class.getName(), invoice.getId());
    AiWriteEffects.touched(AiOcrUpload.class.getName(), upload.getId());

    LOG.info(
        "OCR record {}: invoice {} validated by {}.",
        uploadId,
        invoice.getId(),
        AiExecutionContext.callerDescription());

    return result(upload, invoice);
  }

  // ---------------------------------------------------------------------------
  // Preconditions
  // ---------------------------------------------------------------------------

  /**
   * Supplier documents only.
   *
   * <p>{@link OcrDraftInvoiceGuard} already limits this to invoices OCR created, and intake only
   * ever builds a supplier purchase — but the operation type decides which accounts, dates and
   * payment direction the whole lifecycle uses, so it is asserted rather than assumed.
   */
  protected void requireSupplierInvoice(Invoice invoice) {
    Integer type = invoice.getOperationTypeSelect();
    if (type == null
        || (type != InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE
            && type != InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND)) {
      throw new OcrBlocked(
          AiBlockerCode.NOT_A_SUPPLIER_INVOICE,
          "Invoice " + invoice.getId() + " is not a supplier invoice; refusing to validate it.");
    }
  }

  /**
   * Brings the derived state up to date the way the form's own save chain would, so validation sees
   * current figures rather than whatever the last write left behind.
   *
   * <ul>
   *   <li><b>company-currency line amounts</b> — {@code computeInvoice} sets the invoice's company
   *       totals to the sum of the lines', so a line that never had them would validate to 0.00;
   *   <li><b>VAT summary</b> — {@code invoiceLineTaxList}, which is also what the fourth button
   *       action tests;
   *   <li><b>invoice terms and their due dates</b> — generated under the same condition the form
   *       uses ({@code action-invoice-onsave-group}: draft, lines present, payment condition set,
   *       no terms yet), through {@link InvoiceTermService} rather than by hand.
   * </ul>
   */
  protected void prepare(Invoice invoice) {
    Beans.get(BgOcrIntakeService.class).applyCompanyLineAmounts(invoice, new ArrayList<>());
    Beans.get(OcrInvoiceTaxSummaryService.class).sync(invoice);
    // Finish the draft through the same shared entry point before the stock validation call below
    // (false: the summary was rebuilt immediately above). On a draft the intake already prepared
    // this re-derives the same figures and changes nothing.
    Beans.get(OcrRoundingLineService.class).prepareDraft(invoice, false);

    InvoiceTermService invoiceTermService = Beans.get(InvoiceTermService.class);
    try {
      boolean noTerms =
          invoice.getInvoiceTermList() == null || invoice.getInvoiceTermList().isEmpty();
      boolean hasLines =
          invoice.getInvoiceLineList() != null && !invoice.getInvoiceLineList().isEmpty();
      if (noTerms && hasLines && invoice.getPaymentCondition() != null) {
        invoiceTermService.checkAndComputeInvoiceTerms(invoice);
      }
      invoiceTermService.computeInvoiceTermsDueDates(invoice);
    } catch (Exception e) {
      // Not fatal here: InvoiceServiceImpl.computeInvoice creates the terms itself when the list is
      // empty, so this is a refresh, not the only chance. A genuine problem resurfaces below.
      LOG.info(
          "Could not pre-compute invoice terms for invoice {}: {}",
          invoice.getId(),
          e.getMessage());
    }
  }

  /**
   * This module's own bar, recomputed from the saved record right before validation.
   *
   * <p>Deliberately stricter than Axelor: {@code usable} includes the <em>ventilate</em> blockers,
   * so an invoice that would validate cleanly and then be stuck — a line with no expense account,
   * no supplier invoice sequence — is refused here instead of at the next step.
   */
  protected OcrInvoiceReadiness requireReady(Invoice invoice) {
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
          AiBlockerCode.NOT_READY_TO_VALIDATE,
          "Invoice "
              + invoice.getId()
              + " is not ready to be validated:\n- "
              + String.join("\n- ", cap(reasons))
              + "\nFix these first; nothing was changed.");
    }

    OcrAmountReconciliation amounts = OcrAmountReconciliation.of(invoice);
    if (amounts.isMismatch()) {
      throw new OcrBlocked(
          AiBlockerCode.AMOUNTS_DO_NOT_RECONCILE,
          "Invoice "
              + invoice.getId()
              + " cannot be validated because its amounts do not agree:\n- "
              + String.join("\n- ", amounts.differences())
              + "\nValidation would post the figures the lines produce, so the lines have to be"
              + " right first. Nothing was changed.");
    }
    return readiness;
  }

  /**
   * The controls the Validate button performs before it calls the service — the errors as refusals,
   * and the alerts as refusals too.
   *
   * <p>An {@code <alert>} is Axelor stopping to ask a person a question it cannot answer itself.
   * Clicking through one is a decision; a tool that clicked through automatically would be making
   * that decision silently, on a document that gets posted. So each one becomes a refusal carrying
   * the same fact the dialog would have shown.
   */
  protected void runAxelorPreValidationControls(Invoice invoice, boolean acknowledgeDuplicate) {
    // 1. Bank details — Invoice.xml:2302-2305. An error, not an alert.
    if (invoice.getBankDetails() == null
        && invoice.getPaymentMode() != null
        && invoice.getPaymentMode().getTypeSelect() != null
        && invoice.getPaymentMode().getTypeSelect() == PaymentModeRepository.TYPE_TRANSFER) {
      throw new OcrBlocked(
          AiBlockerCode.MISSING_BANK_DETAILS,
          "Bank details are required before this invoice can be validated: its payment mode is a"
              + " bank transfer and no supplier bank account is set. Set the supplier's IBAN on the"
              + " invoice first.");
    }

    // 2. Invoice terms — InvoiceController.validateInvoiceTermsBeforeSave.
    InvoiceTermService invoiceTermService = Beans.get(InvoiceTermService.class);
    if (invoiceTermService.checkInvoiceTermCreationConditions(invoice)) {
      throw new OcrBlocked(
          AiBlockerCode.INVOICE_TERMS_NOT_CREATABLE,
          "The invoice terms of invoice "
              + invoice.getId()
              + " cannot be created as they are: their amounts or percentages do not add up to the"
              + " invoice total. Correct the payment condition or the terms first.");
    }
    if (invoiceTermService.checkIfThereIsDeletedHoldbackInvoiceTerms(invoice)) {
      throw new OcrBlocked(
          AiBlockerCode.DELETED_HOLDBACK_TERM,
          "A holdback invoice term of invoice "
              + invoice.getId()
              + " has been deleted; Axelor does not allow validation in that state.");
    }

    // 3. Analytic axes — InvoiceController.checkAnalyticAxis, part of the save chain.
    try {
      Beans.get(InvoiceLineAnalyticService.class).checkAnalyticAxisByCompany(invoice);
    } catch (Exception e) {
      throw new OcrBlocked(
          AiBlockerCode.ANALYTIC_CONFIGURATION_REFUSED,
          "Analytic configuration refuses this invoice: "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().trim()));
    }

    // 4. Duplicate supplier invoice number — the alert in action-invoice-onsave-group. A genuine
    // user decision: the same number can legitimately reappear, and it can equally be the same
    // document scanned twice. Reported, never assumed either way.
    if (!acknowledgeDuplicate && Beans.get(InvoiceControlService.class).isDuplicate(invoice)) {
      throw new OcrBlocked(
          AiBlockerCode.DUPLICATE_INVOICE_NUMBER,
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
              + ". Axelor asks the user to confirm this case. Show them the duplicate and validate"
              + " only if they say so. Nothing was changed.");
    }

    // 5. VAT summary — the alert on an empty invoiceLineTaxList. prepare() has just rebuilt it, so
    // still being empty means the rebuild failed, which validation would hit too.
    boolean anyLineTaxed =
        invoice.getInvoiceLineList() != null
            && invoice.getInvoiceLineList().stream()
                .anyMatch(l -> l.getTaxLineSet() != null && !l.getTaxLineSet().isEmpty());
    if (anyLineTaxed
        && (invoice.getInvoiceLineTaxList() == null || invoice.getInvoiceLineTaxList().isEmpty())) {
      throw new OcrBlocked(
          AiBlockerCode.VAT_SUMMARY_NOT_BUILT,
          "The VAT summary of invoice "
              + invoice.getId()
              + " could not be built even though its lines carry a tax, so Axelor would warn that"
              + " the invoice has no taxes. This is a company VAT account configuration problem;"
              + " nothing was changed.");
    }
  }

  // ---------------------------------------------------------------------------
  // After the call
  // ---------------------------------------------------------------------------

  /** Who validated it, on what evidence, and whether a duplicate was knowingly accepted. */
  protected void note(Invoice invoice, String reason, boolean acknowledgeDuplicate) {
    StringBuilder note =
        new StringBuilder("Validated by ")
            .append(AiExecutionContext.callerDescription())
            .append('.');
    if (acknowledgeDuplicate) {
      note.append(
          "\n  NOTE: validated despite an existing invoice with the same number, year and supplier,"
              + " on the user's explicit instruction.");
    }
    if (reason != null && !reason.trim().isEmpty()) {
      note.append("\n  Evidence: ").append(reason.trim());
    }
    for (String difference : OcrAmountReconciliation.of(invoice).differences()) {
      note.append("\n  NOTE: ").append(difference);
    }
    String existing = invoice.getAiValidationNotes();
    invoice.setAiValidationNotes(
        existing == null || existing.trim().isEmpty() ? note.toString() : existing + "\n\n" + note);
  }

  /**
   * The compact answer: what the record is now, read back from it rather than assembled from what
   * was asked for.
   */
  /**
   * The same answer {@link #validate} returns, rebuilt by re-reading the record.
   *
   * <h2>Why it exists</h2>
   *
   * For the one case where the validation ran and its answer was lost on the way back. The result a
   * tool returns is a published shape, and it must not depend on whether the network held up — so
   * the confirmation path builds it from the same method, on the same records, rather than
   * inventing a smaller "it seems to have worked" object.
   *
   * <p>Company-scoped through {@code OcrDraftInvoiceGuard}, exactly as the write path is, and read
   * only. Null when the record cannot be resolved in the active company, which the caller treats as
   * "cannot say" rather than as a failure.
   */
  public Map<String, Object> confirmedResult(long uploadId) {
    try {
      OcrDraftInvoiceGuard.Target target = OcrDraftInvoiceGuard.resolveAnyStatus(uploadId);
      return target == null ? null : result(target.getUpload(), target.getInvoice());
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * The invoice's lifecycle status, read through the same scoping the write path uses.
   *
   * <p>The baseline a lost validation is judged against, and the only thing that has to be known
   * before the call for that judgement to be exact.
   */
  public Integer scopedStatus(long uploadId) {
    OcrDraftInvoiceGuard.Target target = OcrDraftInvoiceGuard.resolveAnyStatus(uploadId);
    return target == null || target.getInvoice() == null
        ? null
        : target.getInvoice().getStatusSelect();
  }

  protected Map<String, Object> result(AiOcrUpload upload, Invoice invoice) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("status", "validated");
    m.put("uploadId", upload.getId());
    m.put("invoiceId", invoice.getId());
    m.put("invoiceNumber", invoice.getInvoiceId());
    m.put("supplierInvoiceNb", invoice.getSupplierInvoiceNb());
    m.put("supplier", invoice.getPartner() == null ? null : invoice.getPartner().getFullName());
    m.put("statusSelect", invoice.getStatusSelect());
    m.put("statusLabel", statusLabel(invoice.getStatusSelect()));

    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("exTaxTotal", invoice.getExTaxTotal());
    totals.put("taxTotal", invoice.getTaxTotal());
    totals.put("inTaxTotal", invoice.getInTaxTotal());
    totals.put("currency", invoice.getCurrency() == null ? null : invoice.getCurrency().getCode());
    totals.put("companyExTaxTotal", invoice.getCompanyExTaxTotal());
    totals.put("companyTaxTotal", invoice.getCompanyTaxTotal());
    totals.put("companyInTaxTotal", invoice.getCompanyInTaxTotal());
    m.put("totals", totals);
    // Recomputed by validation from the lines, so these are the figures that will actually be
    // posted — and the place a supplier's rounding difference finally becomes visible.
    m.put("vatSummary", viewService.vatSummary(invoice));

    m.put("dueDate", invoice.getDueDate() == null ? null : invoice.getDueDate().toString());
    m.put("invoiceTerms", terms(invoice));
    m.put(
        "partnerAccount",
        invoice.getPartnerAccount() == null
            ? null
            : invoice.getPartnerAccount().getCode() + " " + invoice.getPartnerAccount().getName());
    m.put("journal", invoice.getJournal() == null ? null : invoice.getJournal().getCode());

    OcrInvoiceReadiness after =
        Beans.get(OcrSupplierInvoiceReadinessService.class).check(invoice, new ArrayList<>());
    Map<String, Object> ready = new LinkedHashMap<>();
    ready.put("validateBlockers", cap(after.getValidateBlockers()));
    ready.put("ventilateBlockers", cap(after.getVentilateBlockers()));
    ready.put("warnings", cap(after.getWarnings()));
    m.put("readiness", ready);
    m.put("readyToVentilate", after.getVentilateBlockers().isEmpty());
    m.put("amountsReconcile", OcrAmountReconciliation.of(invoice).toMap());

    Map<String, Object> refresh = new LinkedHashMap<>();
    refresh.put("model", Invoice.class.getName());
    refresh.put("id", invoice.getId());
    m.put("refresh", refresh);
    return m;
  }

  protected List<Map<String, Object>> terms(Invoice invoice) {
    List<Map<String, Object>> out = new ArrayList<>();
    List<InvoiceTerm> terms = invoice.getInvoiceTermList();
    if (terms == null) {
      return out;
    }
    for (InvoiceTerm term : terms) {
      if (out.size() >= MAX_TERMS) {
        break;
      }
      Map<String, Object> t = new LinkedHashMap<>();
      t.put("dueDate", term.getDueDate() == null ? null : term.getDueDate().toString());
      t.put("amount", term.getAmount());
      t.put("percentage", term.getPercentage());
      t.put("holdback", Boolean.TRUE.equals(term.getIsHoldBack()));
      out.add(t);
    }
    return out;
  }

  private static String statusLabel(Integer status) {
    if (status == null) {
      return null;
    }
    switch (status) {
      case InvoiceRepository.STATUS_DRAFT:
        return "Draft";
      case InvoiceRepository.STATUS_VALIDATED:
        return "Validated";
      case InvoiceRepository.STATUS_VENTILATED:
        return "Ventilated";
      case InvoiceRepository.STATUS_CANCELED:
        return "Canceled";
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

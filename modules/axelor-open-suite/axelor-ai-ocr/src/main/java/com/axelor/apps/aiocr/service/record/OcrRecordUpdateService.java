package com.axelor.apps.aiocr.service.record;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.service.invoice.InvoiceToolService;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.apps.aiocr.service.ocr.BgOcrBankDetails;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.repo.BankDetailsRepository;
import com.axelor.auth.AuthUtils;
import com.axelor.db.JPA;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies explicit, whitelisted corrections to an OCR record.
 *
 * <p>This is NOT entity patching. Only the fields in {@link #WRITABLE_FIELDS} may change, each has
 * its own typed validation, and nothing is written through reflection or dynamic property names.
 *
 * <p>What it deliberately does NOT do, and why:
 *
 * <ul>
 *   <li><b>partner</b> — changing the supplier cascades into address, bank details, payment mode,
 *       payment condition and tax/account defaults, which Axelor drives through form onChange
 *       action chains. Replaying that from a service would either duplicate or silently skip
 *       accounting logic, so assigning a supplier stays a UI/dedicated-capability job.
 *   <li><b>currency and any total</b> — totals are derived from invoice lines and the dual-currency
 *       reconciliation in the intake; hand-setting them would desynchronise the document.
 *   <li><b>ocrReviewStatus / ocrMissingFields</b> — derived values. They are recomputed here from
 *       the real fields (the same call the form's onSave hook makes), never set directly.
 *   <li><b>AiOcrUpload.statusSelect</b> — lifecycle state owned by the processing state machine. It
 *       is synchronised as a consequence of the invoice becoming complete, via the existing {@link
 *       BgOcrIntakeService#syncUploadReview}.
 * </ul>
 *
 * <p>Reusable: an AI tool, a controller or a future UI button can all call {@link #apply}.
 *
 * <h2>Persistence path: why JPA.save and not invoiceRepo.save</h2>
 *
 * The bound Invoice repository chain is {@code OcrInvoiceRepository → BudgetInvoiceRepository →
 * InvoiceSupplychainRepository → InvoiceManagementRepository → JpaRepository}. Auditing what each
 * {@code save} would execute for the four whitelisted scalar corrections:
 *
 * <ul>
 *   <li><b>OcrInvoiceRepository</b> — overrides only {@code remove}. No save behaviour. <i>N/A.</i>
 *   <li><b>BudgetInvoiceRepository</b> — when the budget app is installed and lines exist, runs
 *       {@code checkAmountForInvoiceLine} and, for refunds/advances, {@code negateAmount}, which
 *       MUTATES line amounts. We never touch lines. <i>Unwanted</i> — an amount mutation triggered
 *       by editing an invoice number is exactly the implicit accounting side effect to avoid.
 *   <li><b>InvoiceSupplychainRepository</b> — {@code computePackTotal}/{@code resetPackTotal},
 *       recomputing totals from lines. <i>Unwanted</i> for the same reason. (It also NPEs when the
 *       AppSale record is absent, which is how this was first noticed.)
 *   <li><b>InvoiceManagementRepository</b> — three things:
 *       <ul>
 *         <li>{@code paymentDate} from validated payments: an OCR draft has none. <i>Harmless.</i>
 *         <li>{@code nextDueDate = InvoiceToolService.getNextDueDate(invoice)}: derived from the
 *             invoice/origin date and invoice terms. Because {@code invoiceDate} is writable here,
 *             this is <i>REQUIRED</i> — skipping it leaves a stale due date. It is therefore called
 *             explicitly in {@link #refreshDerivedDateFields}.
 *         <li>{@code InvoiceService.setDraftSequence}: assigns a draft sequence to a new invoice.
 *             Our target already exists and already has one. <i>Harmless</i>, not needed.
 *       </ul>
 *   <li><b>JpaRepository/JPA</b> — bean validation, persist, and Axelor's audit/tracking (which
 *       live in the JPA layer, not the repositories). <i>Required</i>, and retained by JPA.save.
 * </ul>
 *
 * Conclusion: the only required hook is {@code nextDueDate}, so it is invoked directly rather than
 * dragging in two layers of amount recalculation. This is deliberately narrower than the full chain
 * and is protected by tests; if a future writable field depends on another hook, add that hook
 * explicitly here rather than restoring the chain wholesale.
 */
@Singleton
public class OcrRecordUpdateService {

  private static final Logger LOG = LoggerFactory.getLogger(OcrRecordUpdateService.class);

  public static final String F_SUPPLIER_INVOICE_NB = "supplierInvoiceNb";
  public static final String F_INVOICE_DATE = "invoiceDate";
  public static final String F_ORIGIN_DATE = "originDate";
  public static final String F_EXTERNAL_REFERENCE = "externalReference";
  public static final String F_BANK_DETAILS_ID = "bankDetailsId";
  public static final String F_BANK_IBAN = "bankIban";

  /**
   * The complete set of writable fields. All of them live on the generated draft Invoice: the
   * AiOcrUpload is a job record and duplicates no accounting value, so nothing here needs a dual
   * write. The upload's review status is updated as a derived consequence, not as a field.
   */
  public static final Set<String> WRITABLE_FIELDS =
      new LinkedHashSet<>(
          Arrays.asList(
              F_SUPPLIER_INVOICE_NB,
              F_INVOICE_DATE,
              F_ORIGIN_DATE,
              F_EXTERNAL_REFERENCE,
              F_BANK_DETAILS_ID,
              F_BANK_IBAN));

  private final AiOcrUploadRepository uploadRepo;
  private final InvoiceRepository invoiceRepo;

  @Inject
  public OcrRecordUpdateService(AiOcrUploadRepository uploadRepo, InvoiceRepository invoiceRepo) {
    this.uploadRepo = uploadRepo;
    this.invoiceRepo = invoiceRepo;
  }

  /** One rejected field and why, so the caller can explain itself rather than fail opaquely. */
  public static final class Rejection {
    private final String field;
    private final String reason;

    Rejection(String field, String reason) {
      this.field = field;
      this.reason = reason;
    }

    public String getField() {
      return field;
    }

    public String getReason() {
      return reason;
    }
  }

  /** One accepted change, with before/after for the audit trail. */
  public static final class Change {
    private final String field;
    private final String oldValue;
    private final String newValue;

    Change(String field, String oldValue, String newValue) {
      this.field = field;
      this.oldValue = oldValue;
      this.newValue = newValue;
    }

    public String getField() {
      return field;
    }

    public String getOldValue() {
      return oldValue;
    }

    public String getNewValue() {
      return newValue;
    }
  }

  /** Outcome of an update attempt. Never partially reported: everything is listed. */
  public static final class UpdateResult {
    private final List<String> requested = new ArrayList<>();
    private final List<Change> changed = new ArrayList<>();
    private final List<Rejection> rejected = new ArrayList<>();
    private Long uploadId;
    private Long invoiceId;
    private String ocrReviewStatus;
    private List<String> missingReviewFields = new ArrayList<>();
    private String uploadStatus;

    public List<String> getRequested() {
      return requested;
    }

    public List<Change> getChanged() {
      return changed;
    }

    public List<Rejection> getRejected() {
      return rejected;
    }

    public Long getUploadId() {
      return uploadId;
    }

    public Long getInvoiceId() {
      return invoiceId;
    }

    public String getOcrReviewStatus() {
      return ocrReviewStatus;
    }

    public List<String> getMissingReviewFields() {
      return missingReviewFields;
    }

    public String getUploadStatus() {
      return uploadStatus;
    }

    public boolean isChanged() {
      return !changed.isEmpty();
    }
  }

  /** Raised when the record cannot be updated at all (missing, wrong state, not permitted). */
  public static class UpdateRefusedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UpdateRefusedException(String message) {
      super(message);
    }
  }

  /**
   * Apply corrections to the draft invoice behind an OCR upload.
   *
   * @param uploadId the OCR upload; its linked invoice is the write target
   * @param values field name (from {@link #WRITABLE_FIELDS}) to raw string value; a null/blank
   *     value clears a clearable field
   */
  @Transactional(rollbackOn = Exception.class)
  public UpdateResult apply(Long uploadId, Map<String, String> values) {
    UpdateResult result = new UpdateResult();
    if (!AiExecutionContext.isAuthorisedCaller()) {
      throw new UpdateRefusedException(
          "No authenticated user; OCR records cannot be modified without a caller identity.");
    }
    if (uploadId == null) {
      throw new UpdateRefusedException("uploadId is required.");
    }
    if (values == null || values.isEmpty()) {
      throw new UpdateRefusedException("No fields were supplied.");
    }
    // Two ways of naming the same field, and they can disagree. Applying both would silently let
    // whichever came last in the map decide which account an invoice is paid to.
    if (values.containsKey(F_BANK_IBAN) && values.containsKey(F_BANK_DETAILS_ID)) {
      throw new UpdateRefusedException(
          "Give either " + F_BANK_IBAN + " or " + F_BANK_DETAILS_ID + ", not both.");
    }
    result.requested.addAll(values.keySet());

    AiOcrUpload upload = uploadRepo.find(uploadId);
    if (upload == null) {
      throw new UpdateRefusedException("No OCR record with id " + uploadId + ".");
    }
    result.uploadId = upload.getId();

    Invoice invoice = upload.getInvoice();
    if (invoice == null) {
      throw new UpdateRefusedException(
          "OCR record " + uploadId + " has no supplier invoice yet; there is nothing to correct.");
    }
    result.invoiceId = invoice.getId();

    assertCompanyScope(upload, invoice);
    assertWritable(invoice);

    for (Map.Entry<String, String> e : values.entrySet()) {
      String field = e.getKey();
      String raw = e.getValue();
      if (!WRITABLE_FIELDS.contains(field)) {
        result.rejected.add(
            new Rejection(
                field, "not a writable field; allowed: " + String.join(", ", WRITABLE_FIELDS)));
        continue;
      }
      try {
        applyOne(invoice, field, raw, result);
      } catch (IllegalArgumentException ex) {
        result.rejected.add(new Rejection(field, ex.getMessage()));
      }
    }

    if (result.isChanged()) {
      // Same deterministic post-processing the invoice form's onSave hook performs, reused rather
      // than reimplemented: owner check on bank details, recompute review flags, sync the upload.
      BgOcrIntakeService intake = Beans.get(BgOcrIntakeService.class);
      if (invoice.getBankDetails() != null
          && invoice.getBankDetails().getId() != null
          && invoice.getPartner() != null
          && invoice.getPartner().getId() != null) {
        intake.ensureBankDetailsPartner(
            invoice.getBankDetails().getId(), invoice.getPartner().getId());
      }

      List<String> missing = BgOcrIntakeService.missingReviewFields(invoice);
      boolean complete = missing.isEmpty();
      invoice.setOcrReviewStatus(
          complete
              ? BgOcrIntakeService.OCR_REVIEW_COMPLETE
              : BgOcrIntakeService.OCR_REVIEW_TO_REVIEW);
      invoice.setOcrMissingFields(BgOcrIntakeService.missingFieldsLabel(missing));
      refreshDerivedDateFields(invoice, result);
      // The VAT summary is derived from the lines and their (date-versioned) tax lines, so a date
      // change can make it stale — and a draft that never had one gets it here. Header totals are
      // untouched; see OcrInvoiceTaxSummaryService.
      OcrInvoiceTaxSummaryService.Result taxes =
          Beans.get(OcrInvoiceTaxSummaryService.class).sync(invoice);
      // A date change can re-version the taxes, so the draft is finished again on the rebuilt
      // summary (false: it was rebuilt immediately above).
      Beans.get(OcrRoundingLineService.class).prepareDraft(invoice, false);
      if (taxes.isFailed()) {
        result.changed.add(
            new Change("vatSummary", null, "could not be rebuilt: " + taxes.getFailure()));
      } else if (taxes.isChanged()) {
        result.changed.add(new Change("vatSummary", null, String.join("; ", taxes.getRows())));
      }
      invoice.setAiValidationNotes(auditNote(invoice.getAiValidationNotes(), result));
      // See the class javadoc for the full audit of invoiceRepo.save() vs JPA.save(): the required
      // hook (nextDueDate) is invoked explicitly above, and JPA.save keeps bean validation and
      // Axelor's audit/tracking while avoiding the chain's amount recalculations.
      JPA.save(invoice);

      intake.syncUploadReview(invoice.getId(), complete);
      AiWriteEffects.touched(Invoice.class.getName(), invoice.getId());
      AiWriteEffects.touched(AiOcrUpload.class.getName(), upload.getId());
      LOG.info(
          "OCR record {} updated: {} field(s) changed by {}.",
          uploadId,
          result.changed.size(),
          currentUserCode());
    }

    List<String> missing = BgOcrIntakeService.missingReviewFields(invoice);
    result.ocrReviewStatus = invoice.getOcrReviewStatus();
    result.missingReviewFields = missing;
    result.uploadStatus = uploadRepo.find(uploadId).getStatusSelect();
    return result;
  }

  /**
   * Re-derives what a date change makes stale, reusing Axelor's own logic for each.
   *
   * <p>Three things depend on these dates, and the form refreshes all three through its {@code
   * onChange} chain ({@code action-invoice-group-payment-condition-onchange} for the terms, {@code
   * InvoiceManagementRepository.save} for {@code nextDueDate}):
   *
   * <ol>
   *   <li><b>dueDate / nextDueDate</b> — via {@link InvoiceToolService}. Note which date drives
   *       them: {@code getDueDate} and {@code getNextDueDate} both read {@code originDate} for a
   *       purchase invoice and {@code invoiceDate} only for a sale. So on a supplier invoice —
   *       every invoice this service touches — the due date follows the <i>origin</i> date. The
   *       previous version refreshed only on {@code invoiceDate}, which meant the due date of a
   *       supplier invoice never actually moved.
   *   <li><b>Line tax versions</b> — {@code TaxLine} is date-versioned, so a line whose tax was
   *       resolved for the old date may now be carrying the wrong version. Re-resolved from the
   *       same {@code Tax}, so the tax itself never changes: only its version, and only when a
   *       different one applies.
   *   <li><b>paymentDelay</b> — the displayed gap between the document date and the due date.
   * </ol>
   *
   * <p>A failure in any of these is logged, not fatal: the correction itself is valid, and the same
   * values are recomputed on the next normal save.
   */
  protected void refreshDerivedDateFields(Invoice invoice, UpdateResult result) {
    boolean invoiceDateChanged =
        result.getChanged().stream().anyMatch(c -> F_INVOICE_DATE.equals(c.getField()));
    boolean originDateChanged =
        result.getChanged().stream().anyMatch(c -> F_ORIGIN_DATE.equals(c.getField()));
    if (!invoiceDateChanged && !originDateChanged) {
      return;
    }
    try {
      LocalDate previousDue = invoice.getDueDate();
      invoice.setDueDate(InvoiceToolService.getDueDate(invoice));
      invoice.setNextDueDate(InvoiceToolService.getNextDueDate(invoice));
      if (!java.util.Objects.equals(previousDue, invoice.getDueDate())) {
        result.changed.add(
            new Change(
                "dueDate",
                previousDue == null ? null : previousDue.toString(),
                invoice.getDueDate() == null ? null : invoice.getDueDate().toString()));
      }
    } catch (Exception e) {
      LOG.warn(
          "Could not recompute the due date for invoice {}: {}", invoice.getId(), e.getMessage());
    }

    LocalDate documentDate =
        invoice.getOriginDate() != null ? invoice.getOriginDate() : invoice.getInvoiceDate();
    if (documentDate != null && invoice.getDueDate() != null) {
      invoice.setPaymentDelay(
          invoice.getDueDate().isBefore(documentDate)
              ? 0
              : Math.toIntExact(ChronoUnit.DAYS.between(documentDate, invoice.getDueDate())));
    }

    try {
      List<String> retaxed =
          Beans.get(OcrInvoiceLineEditService.class).refreshLineTaxVersions(invoice);
      for (String note : retaxed) {
        result.changed.add(new Change("lineTaxVersion", null, note));
      }
    } catch (Exception e) {
      LOG.warn(
          "Could not re-resolve line tax versions for invoice {}: {}",
          invoice.getId(),
          e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Guards
  // ---------------------------------------------------------------------------

  /**
   * Only DRAFT, AI-generated invoices may be corrected here.
   *
   * <p>Document-state rules are unconditional. The Axelor row permission is checked only when a
   * user is actually in context: authentication is guaranteed a layer up (the REST endpoint is
   * behind Shiro and {@code AiToolDispatcher} refuses to run any tool without a user), so a null
   * user here means an internal/system caller such as a background job, not an anonymous request.
   */
  protected void assertWritable(Invoice invoice) {
    if (!Boolean.TRUE.equals(invoice.getAiGenerated())) {
      throw new UpdateRefusedException(
          "Invoice " + invoice.getId() + " was not produced by OCR; refusing to edit it here.");
    }
    Integer status = invoice.getStatusSelect();
    if (status == null || status != InvoiceRepository.STATUS_DRAFT) {
      throw new UpdateRefusedException(
          "Invoice "
              + invoice.getId()
              + " is not in Draft status; validated or ventilated documents are never edited here.");
    }
    if (AuthUtils.getUser() == null) {
      return;
    }
    JpaSecurity security = Beans.get(JpaSecurity.class);
    if (!security.isPermitted(JpaSecurity.CAN_WRITE, Invoice.class, invoice.getId())) {
      throw new UpdateRefusedException(
          "You do not have permission to modify invoice " + invoice.getId() + ".");
    }
  }

  /**
   * The record must belong to the caller's active company — and so must its invoice.
   *
   * <p>Both refusals repeat the not-found wording on purpose: an id from another company must tell
   * the caller nothing more than an id that was never issued.
   */
  protected void assertCompanyScope(AiOcrUpload upload, Invoice invoice) {
    Long allowed = allowedCompanyId();
    if (!matches(allowed, upload.getCompany() == null ? null : upload.getCompany().getId())
        || !matches(allowed, invoice.getCompany() == null ? null : invoice.getCompany().getId())) {
      throw new UpdateRefusedException("No OCR record with id " + upload.getId() + ".");
    }
  }

  private static boolean matches(Long allowed, Long recordCompanyId) {
    return allowed != null && allowed.equals(recordCompanyId);
  }

  /**
   * The single company this caller may touch. Overridable so the scope rule can be exercised
   * without a live Shiro subject; there is no return value meaning "any company".
   */
  protected Long allowedCompanyId() {
    return AiCompanyScope.requireCompanyId();
  }

  // ---------------------------------------------------------------------------
  // Field handlers — explicit, one per writable field. No reflection.
  // ---------------------------------------------------------------------------

  private void applyOne(Invoice invoice, String field, String raw, UpdateResult result) {
    String value = raw == null ? null : raw.trim();
    switch (field) {
      case F_SUPPLIER_INVOICE_NB:
        {
          if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("the supplier invoice number cannot be cleared");
          }
          if (value.length() > 255) {
            throw new IllegalArgumentException("value is longer than 255 characters");
          }
          String old = invoice.getSupplierInvoiceNb();
          if (!equal(old, value)) {
            invoice.setSupplierInvoiceNb(value);
            result.changed.add(new Change(field, old, value));
          }
          return;
        }
      case F_INVOICE_DATE:
        {
          if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("the invoice date cannot be cleared");
          }
          LocalDate date;
          try {
            date = LocalDate.parse(value);
          } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("not an ISO date (yyyy-MM-dd)");
          }
          if (date.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("the invoice date is in the future");
          }
          LocalDate old = invoice.getInvoiceDate();
          if (!equal(old == null ? null : old.toString(), date.toString())) {
            invoice.setInvoiceDate(date);
            result.changed.add(
                new Change(field, old == null ? null : old.toString(), date.toString()));
          }
          return;
        }
      case F_ORIGIN_DATE:
        {
          // The supplier's own document date. On a purchase invoice this — not invoiceDate — is
          // what Axelor derives the due date and the currency conversion date from (see
          // InvoiceToolService.getDueDate / InvoiceLineService.getCompanyExTaxTotal), and it is
          // also the year in the supplier-invoice-number duplicate key.
          if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("the supplier origin date cannot be cleared");
          }
          LocalDate date;
          try {
            date = LocalDate.parse(value);
          } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("not an ISO date (yyyy-MM-dd)");
          }
          // Mirrors the form's own action-invoice-validate-check-origin-date.
          if (date.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("the date of origin cannot be in the future");
          }
          LocalDate old = invoice.getOriginDate();
          if (!equal(old == null ? null : old.toString(), date.toString())) {
            invoice.setOriginDate(date);
            result.changed.add(
                new Change(field, old == null ? null : old.toString(), date.toString()));
          }
          return;
        }
      case F_EXTERNAL_REFERENCE:
        {
          if (value != null && value.length() > 255) {
            throw new IllegalArgumentException("value is longer than 255 characters");
          }
          String old = invoice.getExternalReference();
          String next = (value == null || value.isEmpty()) ? null : value;
          if (!equal(old, next)) {
            invoice.setExternalReference(next);
            result.changed.add(new Change(field, old, next));
          }
          return;
        }
      case F_BANK_DETAILS_ID:
        {
          String old =
              invoice.getBankDetails() == null
                  ? null
                  : String.valueOf(invoice.getBankDetails().getId());
          if (value == null || value.isEmpty()) {
            if (invoice.getBankDetails() != null) {
              invoice.setBankDetails(null);
              result.changed.add(new Change(field, old, null));
            }
            return;
          }
          long id;
          try {
            id = Long.parseLong(value);
          } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("not a numeric id");
          }
          if (id <= 0) {
            throw new IllegalArgumentException("not a positive id");
          }
          BankDetails bd = Beans.get(BankDetailsRepository.class).find(id);
          if (bd == null) {
            throw new IllegalArgumentException("no bank account with id " + id);
          }
          // The account must be the supplier's: an invoice paid to someone else's IBAN is exactly
          // the failure this whole feature exists to prevent.
          if (invoice.getPartner() == null) {
            throw new IllegalArgumentException(
                "the invoice has no supplier yet, so a bank account cannot be attached");
          }
          // Must be the supplier's own account, positively. An account with no partner at all is a
          // company account — possibly another company's — so "not somebody else's" is not enough;
          // it has to be this partner's.
          if (bd.getPartner() == null
              || !invoice.getPartner().getId().equals(bd.getPartner().getId())) {
            throw new IllegalArgumentException(
                "bank account " + id + " does not belong to the invoice supplier");
          }
          if (!equal(old, String.valueOf(id))) {
            invoice.setBankDetails(bd);
            result.changed.add(new Change(field, old, String.valueOf(id)));
          }
          return;
        }
      case F_BANK_IBAN:
        {
          BankDetails bd = resolveSupplierAccountByIban(invoice, value);
          String old =
              invoice.getBankDetails() == null
                  ? null
                  : String.valueOf(invoice.getBankDetails().getId());
          String next = String.valueOf(bd.getId());
          if (!equal(old, next)) {
            invoice.setBankDetails(bd);
            // Recorded as bankDetailsId with the IBAN alongside: the field that actually changed is
            // the account link, and an auditor reading the note needs to see which account it is.
            result.changed.add(
                new Change(
                    F_BANK_DETAILS_ID,
                    old,
                    next + " (" + BgOcrBankDetails.normalizeIban(bd.getIban()) + ")"));
          }
          return;
        }
      default:
        throw new IllegalArgumentException("not a writable field");
    }
  }

  /**
   * The supplier's own bank account, named by IBAN instead of by id.
   *
   * <p>An id is not always known: creating the supplier (with its bank account) and pointing the
   * invoice at that account are two halves of one request, and at the moment the invoice call is
   * composed the {@code BankDetails} row may not exist yet. The IBAN does exist — it is printed on
   * the document — and it is resolved when this runs, by which time {@code manage_supplier} has
   * created the account.
   *
   * <p>Resolution is deliberately narrow, and every failure mode is a refusal rather than a
   * substitution:
   *
   * <ul>
   *   <li>the account must be <b>this supplier's</b>. An IBAN found on another partner, or on no
   *       partner at all (a company account), is refused — paying a supplier into somebody else's
   *       account is the exact failure the OCR bank path exists to prevent, and it names which case
   *       it is so the caller can act;
   *   <li><b>inactive</b> accounts are refused: the invoice form's own bank-details domain filters
   *       them out, so selecting one would write a value the form cannot show;
   *   <li><b>several</b> active accounts with one IBAN on one partner is a data problem, not
   *       something to choose from.
   * </ul>
   */
  protected BankDetails resolveSupplierAccountByIban(Invoice invoice, String value) {
    if (value == null || value.isEmpty()) {
      throw new IllegalArgumentException(
          "an IBAN cannot be blank; to clear the account send " + F_BANK_DETAILS_ID + " empty");
    }
    String iban = BgOcrBankDetails.normalizeIban(value);
    if (iban == null || !BgOcrBankDetails.isValidIban(iban)) {
      throw new IllegalArgumentException("'" + value + "' is not a readable IBAN");
    }
    if (invoice.getPartner() == null) {
      throw new IllegalArgumentException(
          "the invoice has no supplier yet, so a bank account cannot be attached");
    }
    List<BankDetails> owned =
        Beans.get(BankDetailsRepository.class)
            .all()
            .filter("REPLACE(UPPER(self.iban), ' ', '') = :iban AND self.partner = :partner")
            .bind("iban", iban)
            .bind("partner", invoice.getPartner())
            .fetch(5);
    List<BankDetails> active = new ArrayList<>();
    for (BankDetails bd : owned) {
      if (!Boolean.FALSE.equals(bd.getActive())) {
        active.add(bd);
      }
    }
    if (active.size() == 1) {
      return active.get(0);
    }
    if (active.size() > 1) {
      throw new IllegalArgumentException(
          "supplier "
              + invoice.getPartner().getName()
              + " has "
              + active.size()
              + " active bank accounts with IBAN "
              + iban
              + "; resolve the duplicate in Nyxa, or pass "
              + F_BANK_DETAILS_ID);
    }
    if (!owned.isEmpty()) {
      throw new IllegalArgumentException(
          "IBAN "
              + iban
              + " is registered on "
              + invoice.getPartner().getName()
              + " but the account is inactive; activate it in Nyxa first");
    }
    long elsewhere =
        Beans.get(BankDetailsRepository.class)
            .all()
            .filter(
                "REPLACE(UPPER(self.iban), ' ', '') = :iban"
                    + " AND (self.partner IS NULL OR self.partner != :partner)")
            .bind("iban", iban)
            .bind("partner", invoice.getPartner())
            .count();
    if (elsewhere > 0) {
      throw new IllegalArgumentException(
          "IBAN "
              + iban
              + " exists in Nyxa but does not belong to "
              + invoice.getPartner().getName()
              + "; refusing to pay this supplier into another party's account");
    }
    throw new IllegalArgumentException(
        "no bank account with IBAN "
            + iban
            + " is registered on "
            + invoice.getPartner().getName()
            + "; register it on the supplier first (manage_supplier, bankAccount.iban) — it can be"
            + " part of the same change");
  }

  /** Appends a who/what/when line so a corrected draft always says how it got that way. */
  protected String auditNote(String existing, UpdateResult result) {
    StringBuilder sb =
        new StringBuilder("Manual/AI correction by ").append(currentUserCode()).append(":");
    for (Change c : result.changed) {
      sb.append("\n- ")
          .append(c.getField())
          .append(": ")
          .append(c.getOldValue() == null ? "(empty)" : c.getOldValue())
          .append(" → ")
          .append(c.getNewValue() == null ? "(empty)" : c.getNewValue());
    }
    String note = sb.toString();
    if (existing == null || existing.trim().isEmpty()) {
      return note;
    }
    return existing + "\n\n" + note;
  }

  private static String currentUserCode() {
    return AiExecutionContext.callerDescription();
  }

  private static boolean equal(String a, String b) {
    return a == null ? b == null : a.equals(b);
  }

  /** Field list + per-field documentation, so tools and UIs describe the same contract. */
  public static Map<String, String> writableFieldDocs() {
    Map<String, String> docs = new LinkedHashMap<>();
    docs.put(
        F_SUPPLIER_INVOICE_NB, "The supplier's own invoice number, as printed. Required, max 255.");
    docs.put(F_INVOICE_DATE, "Invoice issue date, ISO yyyy-MM-dd. Cannot be in the future.");
    docs.put(
        F_ORIGIN_DATE,
        "Date printed on the supplier's own document, ISO yyyy-MM-dd. Required before ventilation"
            + " and the date the due date is calculated from. Not in the future.");
    docs.put(F_EXTERNAL_REFERENCE, "Free-text external reference. Pass empty to clear.");
    docs.put(
        F_BANK_DETAILS_ID, "Id of an EXISTING bank account of this supplier. Empty clears it.");
    docs.put(
        F_BANK_IBAN,
        "The supplier's IBAN as printed, instead of bankDetailsId. Must already belong to this"
            + " supplier — anything else is refused, never substituted. Not both.");
    return docs;
  }
}

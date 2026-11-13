package com.axelor.apps.aiocr.service.record;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.PaymentCondition;
import com.axelor.apps.account.db.PaymentMode;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.service.invoice.BankDetailsServiceAccount;
import com.axelor.apps.account.service.invoice.InvoiceToolService;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.service.PartnerService;
import com.axelor.apps.base.service.address.AddressService;
import com.axelor.auth.AuthUtils;
import com.axelor.db.JPA;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Changes the supplier on an OCR-created DRAFT supplier invoice, keeping every supplier-dependent
 * field consistent.
 *
 * <h2>The UI chain this reproduces</h2>
 *
 * The Invoice form runs {@code action-invoice-group-partner-onchange} when {@code partner} changes.
 * Its supplier-relevant steps, and the service each one calls, are reused here rather than
 * approximated:
 *
 * <ul>
 *   <li>{@code action-invoice-record-partner-change} (action-record) sets, straight from the
 *       partner: {@code fiscalPosition}, {@code address} (via {@code
 *       PartnerService.getInvoicingAddress}), {@code currency} (only when there are no invoice
 *       lines), {@code contactPartner} (only when the partner has exactly one), {@code
 *       partnerTaxNbr}, {@code invoicesCopySelect}, {@code proformaComments}, {@code
 *       headOfficeAddress}.
 *   <li>{@code action-method-invoice-address-str} → {@code AddressService.computeAddressStr}.
 *   <li>{@code action-invoice-method-payment-mode-condition} → {@code
 *       InvoiceToolService.getPaymentMode} / {@code getPaymentCondition}.
 *   <li>{@code action-invoice-method-get-default-bank-details} → {@code
 *       BankDetailsServiceAccount.getDefaultBankDetails(partner, company, paymentMode)}.
 *   <li>{@code action-invoice-record-due-dates} → {@code InvoiceToolService.getNextDueDate}.
 * </ul>
 *
 * <h2>Deliberately NOT reproduced</h2>
 *
 * <ul>
 *   <li><b>Invoice term generation</b> ({@code action-invoice-method-generate-invoice-terms},
 *       {@code compute-invoice-terms-due-dates}). Terms are payment-schedule records; generating
 *       them from a correction would create accounting artefacts nobody asked for. {@code
 *       getNextDueDate} degrades cleanly to the invoice date when no terms exist, and the terms are
 *       generated normally when a user next edits the invoice in the UI.
 *   <li><b>Price lists, discounts, interco, advance payments, PFP validators, e-mail defaults.</b>
 *       These are sales/ordering concerns or workflow routing, not identity, and several would
 *       change amounts.
 *   <li>Anything that validates, posts or ventilates. Nothing here leaves Draft.
 * </ul>
 *
 * Reusable by a future UI button: {@link #changeSupplier} takes ids and returns a structured
 * result.
 */
@Singleton
public class OcrSupplierCorrectionService {

  private static final Logger LOG = LoggerFactory.getLogger(OcrSupplierCorrectionService.class);

  private final AiOcrUploadRepository uploadRepo;
  private final PartnerRepository partnerRepo;

  @Inject
  public OcrSupplierCorrectionService(
      AiOcrUploadRepository uploadRepo, PartnerRepository partnerRepo) {
    this.uploadRepo = uploadRepo;
    this.partnerRepo = partnerRepo;
  }

  /** Raised when the change cannot be made at all. */
  public static class SupplierChangeRefusedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SupplierChangeRefusedException(String message) {
      super(message);
    }
  }

  /** What actually happened, field by field. */
  public static final class SupplierChangeResult {
    private Long uploadId;
    private Long invoiceId;
    private Long previousPartnerId;
    private String previousPartnerName;
    private Long newPartnerId;
    private String newPartnerName;
    private boolean changed;
    private final Map<String, String> refreshedFields = new LinkedHashMap<>();
    private final List<String> skipped = new ArrayList<>();
    private String ocrReviewStatus;
    private List<String> missingReviewFields = new ArrayList<>();
    private String uploadStatus;

    public Long getUploadId() {
      return uploadId;
    }

    public Long getInvoiceId() {
      return invoiceId;
    }

    public Long getPreviousPartnerId() {
      return previousPartnerId;
    }

    public String getPreviousPartnerName() {
      return previousPartnerName;
    }

    public Long getNewPartnerId() {
      return newPartnerId;
    }

    public String getNewPartnerName() {
      return newPartnerName;
    }

    public boolean isChanged() {
      return changed;
    }

    /** Dependent field -> the value it now holds, for the audit trail. */
    public Map<String, String> getRefreshedFields() {
      return refreshedFields;
    }

    /** Dependent fields deliberately left alone, with the reason. */
    public List<String> getSkipped() {
      return skipped;
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
  }

  /**
   * Point an OCR draft invoice at a different, existing supplier.
   *
   * @param uploadId the OCR record whose draft invoice is corrected
   * @param partnerId an EXISTING partner; never created here
   */
  @Transactional(rollbackOn = Exception.class)
  public SupplierChangeResult changeSupplier(Long uploadId, Long partnerId) {
    if (!AiExecutionContext.isAuthorisedCaller()) {
      throw new SupplierChangeRefusedException(
          "No authenticated user; the supplier cannot be changed without a caller identity.");
    }
    if (uploadId == null) {
      throw new SupplierChangeRefusedException("uploadId is required.");
    }
    if (partnerId == null) {
      throw new SupplierChangeRefusedException("partnerId is required.");
    }

    AiOcrUpload upload = uploadRepo.find(uploadId);
    if (upload == null) {
      throw new SupplierChangeRefusedException("No OCR record with id " + uploadId + ".");
    }
    Invoice invoice = upload.getInvoice();
    if (invoice == null) {
      throw new SupplierChangeRefusedException(
          "OCR record " + uploadId + " has no supplier invoice yet.");
    }

    Partner partner = requireSupplierInScope(partnerId);

    assertScope(upload, invoice, partner);
    assertWritable(invoice);

    SupplierChangeResult result = new SupplierChangeResult();
    result.uploadId = upload.getId();
    result.invoiceId = invoice.getId();
    Partner previous = invoice.getPartner();
    result.previousPartnerId = previous == null ? null : previous.getId();
    result.previousPartnerName = previous == null ? null : previous.getName();
    result.newPartnerId = partner.getId();
    result.newPartnerName = partner.getName();

    if (previous != null && previous.getId().equals(partner.getId())) {
      // No-op: do not churn dependent fields or write an audit line for a non-change.
      result.changed = false;
      finishReadOnly(invoice, upload, result);
      return result;
    }

    invoice.setPartner(partner);
    applyPartnerDependentFields(invoice, partner, result);
    result.changed = true;

    invoice.setAiValidationNotes(auditNote(invoice.getAiValidationNotes(), result));

    // Review flags are derived — recomputed from the real fields, exactly as the form's onSave hook
    // does, never set directly.
    List<String> missing = BgOcrIntakeService.missingReviewFields(invoice);
    boolean complete = missing.isEmpty();
    invoice.setOcrReviewStatus(
        complete
            ? BgOcrIntakeService.OCR_REVIEW_COMPLETE
            : BgOcrIntakeService.OCR_REVIEW_TO_REVIEW);
    invoice.setOcrMissingFields(BgOcrIntakeService.missingFieldsLabel(missing));

    // The supplier carries the fiscal position, and the VAT summary's imputed account and VAT
    // system are resolved from the partner — so an identity change makes the existing summary
    // stale. Rebuilt from the lines; header totals untouched. See OcrInvoiceTaxSummaryService.
    OcrInvoiceTaxSummaryService.Result taxes =
        Beans.get(OcrInvoiceTaxSummaryService.class).sync(invoice);
    // A different supplier can change the VAT system and the imputed account, so the draft is
    // finished again on the rebuilt summary (false: it was rebuilt immediately above).
    Beans.get(OcrRoundingLineService.class).prepareDraft(invoice, false);
    if (taxes.isFailed()) {
      LOG.warn(
          "Invoice {}: the VAT summary could not be rebuilt after the supplier change: {}",
          invoice.getId(),
          taxes.getFailure());
    }

    // Same narrow persistence decision as OcrRecordUpdateService: the required derived value
    // (nextDueDate) is set explicitly above; the repository chain's amount recalculations are not
    // wanted for an identity correction.
    JPA.save(invoice);

    Beans.get(BgOcrIntakeService.class).syncUploadReview(invoice.getId(), complete);
    AiWriteEffects.touched(Invoice.class.getName(), invoice.getId());
    AiWriteEffects.touched(AiOcrUpload.class.getName(), upload.getId());

    LOG.info(
        "OCR record {}: supplier changed from {} to {} by {}.",
        uploadId,
        result.previousPartnerId,
        result.newPartnerId,
        AiExecutionContext.callerDescription());

    result.ocrReviewStatus = invoice.getOcrReviewStatus();
    result.missingReviewFields = missing;
    result.uploadStatus = uploadRepo.find(uploadId).getStatusSelect();
    return result;
  }

  private void finishReadOnly(Invoice invoice, AiOcrUpload upload, SupplierChangeResult result) {
    List<String> missing = BgOcrIntakeService.missingReviewFields(invoice);
    result.ocrReviewStatus = invoice.getOcrReviewStatus();
    result.missingReviewFields = missing;
    result.uploadStatus = upload.getStatusSelect();
  }

  /**
   * Re-derives every supplier-dependent field, calling the same services the form's onChange chain
   * calls. Each step records what it set, and a failure in one derived field never aborts the
   * identity change itself.
   */
  protected void applyPartnerDependentFields(
      Invoice invoice, Partner partner, SupplierChangeResult result) {

    // --- action-invoice-record-partner-change -------------------------------
    invoice.setFiscalPosition(partner.getFiscalPosition());
    result.refreshedFields.put(
        "fiscalPosition",
        partner.getFiscalPosition() == null ? null : partner.getFiscalPosition().getName());

    Address address = null;
    try {
      address = Beans.get(PartnerService.class).getInvoicingAddress(partner);
    } catch (Exception e) {
      LOG.warn(
          "Could not resolve invoicing address for partner {}: {}",
          partner.getId(),
          e.getMessage());
    }
    invoice.setAddress(address);
    result.refreshedFields.put("address", address == null ? null : String.valueOf(address.getId()));

    try {
      invoice.setAddressStr(Beans.get(AddressService.class).computeAddressStr(address));
      result.refreshedFields.put("addressStr", invoice.getAddressStr());
    } catch (Exception e) {
      LOG.warn("Could not compute address string: {}", e.getMessage());
    }

    // Currency only when there are no lines — the UI guards this the same way, because changing
    // currency under existing lines would desynchronise the amounts.
    if (invoice.getInvoiceLineList() == null || invoice.getInvoiceLineList().isEmpty()) {
      if (partner.getCurrency() != null) {
        invoice.setCurrency(partner.getCurrency());
        result.refreshedFields.put("currency", partner.getCurrency().getCode());
      }
    } else {
      result.skipped.add("currency (invoice already has lines)");
    }

    if (partner.getContactPartnerSet() != null && partner.getContactPartnerSet().size() == 1) {
      invoice.setContactPartner(partner.getContactPartnerSet().iterator().next());
      result.refreshedFields.put("contactPartner", invoice.getContactPartner().getName());
    } else {
      invoice.setContactPartner(null);
      result.refreshedFields.put("contactPartner", null);
    }

    invoice.setPartnerTaxNbr(partner.getTaxNbr());
    result.refreshedFields.put("partnerTaxNbr", partner.getTaxNbr());

    invoice.setInvoicesCopySelect(
        partner.getInvoicesCopySelect() != null && partner.getInvoicesCopySelect() != 0
            ? partner.getInvoicesCopySelect()
            : 1);
    result.refreshedFields.put(
        "invoicesCopySelect", String.valueOf(invoice.getInvoicesCopySelect()));

    invoice.setProformaComments(partner.getProformaComments());
    // Both sides are plain text on this model, not an Address relation.
    invoice.setHeadOfficeAddress(partner.getHeadOfficeAddress());
    result.refreshedFields.put("headOfficeAddress", partner.getHeadOfficeAddress());

    // --- action-invoice-method-payment-mode-condition -----------------------
    try {
      PaymentMode paymentMode = InvoiceToolService.getPaymentMode(invoice);
      invoice.setPaymentMode(paymentMode);
      result.refreshedFields.put("paymentMode", paymentMode == null ? null : paymentMode.getName());
    } catch (Exception e) {
      LOG.warn("Could not resolve payment mode: {}", e.getMessage());
      result.skipped.add("paymentMode (" + e.getMessage() + ")");
    }
    try {
      PaymentCondition condition = InvoiceToolService.getPaymentCondition(invoice);
      invoice.setPaymentCondition(condition);
      result.refreshedFields.put(
          "paymentCondition", condition == null ? null : condition.getName());
    } catch (Exception e) {
      LOG.warn("Could not resolve payment condition: {}", e.getMessage());
      result.skipped.add("paymentCondition (" + e.getMessage() + ")");
    }

    // --- action-invoice-method-get-default-bank-details ---------------------
    // The previous supplier's bank account must never survive an identity change: paying the wrong
    // IBAN is the worst outcome this whole feature exists to prevent.
    try {
      BankDetails bankDetails =
          Beans.get(BankDetailsServiceAccount.class)
              .getDefaultBankDetails(partner, invoice.getCompany(), invoice.getPaymentMode());
      invoice.setBankDetails(bankDetails);
      result.refreshedFields.put("bankDetails", bankDetails == null ? null : bankDetails.getIban());
    } catch (Exception e) {
      LOG.warn("Could not resolve default bank details: {}", e.getMessage());
      invoice.setBankDetails(null);
      result.refreshedFields.put("bankDetails", null);
    }

    // --- action-invoice-record-due-dates ------------------------------------
    try {
      invoice.setNextDueDate(InvoiceToolService.getNextDueDate(invoice));
      result.refreshedFields.put(
          "nextDueDate",
          invoice.getNextDueDate() == null ? null : invoice.getNextDueDate().toString());
    } catch (Exception e) {
      LOG.warn("Could not recompute nextDueDate: {}", e.getMessage());
    }

    result.skipped.add("invoiceTermList (payment terms are not generated by a correction)");
  }

  // ---------------------------------------------------------------------------
  // Guards
  // ---------------------------------------------------------------------------

  /**
   * The partner with this id, if the caller's active company may see it and it really is a
   * supplier.
   *
   * <p>Probes existence with a count before loading the entity: it gives a precise refusal without
   * materialising a Partner, and it avoids {@code em.find()} having to evaluate Partner's computed
   * {@code @Formula} columns just to discover the row is not there. The company scope is part of
   * the probe, in SQL, so a partner belonging to another company reads as "no such partner" here
   * rather than being loaded and rejected later with a different message.
   *
   * <p>Public because creating an invoice for a supplier needs exactly this check before there is
   * any invoice to change — {@code SupplierInvoiceEditService} calls it on the createIfMissing path
   * so that both routes admit the same partners for the same reasons.
   */
  public Partner requireSupplierInScope(long partnerId) {
    boolean exists =
        partnerRepo
                .all()
                .filter("self.id = :id AND " + AiCompanyScope.partnerFilter("self"))
                .bind("id", partnerId)
                .bind(AiCompanyScope.COMPANY_PARAM, AiCompanyScope.requireCompanyId())
                .count()
            > 0;
    if (!exists) {
      throw new SupplierChangeRefusedException(
          "No partner with id " + partnerId + ". Suppliers are never created automatically.");
    }
    Partner partner = partnerRepo.find(partnerId);
    if (partner == null) {
      throw new SupplierChangeRefusedException(
          "No partner with id " + partnerId + ". Suppliers are never created automatically.");
    }
    if (!Boolean.TRUE.equals(partner.getIsSupplier())) {
      throw new SupplierChangeRefusedException(
          "Partner " + partnerId + " is not marked as a supplier.");
    }
    return partner;
  }

  protected void assertWritable(Invoice invoice) {
    if (!Boolean.TRUE.equals(invoice.getAiGenerated())) {
      throw new SupplierChangeRefusedException(
          "Invoice " + invoice.getId() + " was not produced by OCR; refusing to edit it here.");
    }
    Integer status = invoice.getStatusSelect();
    if (status == null || status != InvoiceRepository.STATUS_DRAFT) {
      throw new SupplierChangeRefusedException(
          "Invoice "
              + invoice.getId()
              + " is not in Draft status; the supplier cannot be changed.");
    }
    if (AuthUtils.getUser() != null
        && !Beans.get(JpaSecurity.class)
            .isPermitted(JpaSecurity.CAN_WRITE, Invoice.class, invoice.getId())) {
      throw new SupplierChangeRefusedException(
          "You do not have permission to modify invoice " + invoice.getId() + ".");
    }
  }

  /**
   * Record and target partner must both sit inside the caller's active company.
   *
   * <p>Two different questions, both answered here:
   *
   * <ul>
   *   <li>the <b>document</b> must be this company's — refused with the not-found wording, because
   *       an upload id from another company must not be distinguishable from an id that does not
   *       exist;
   *   <li>the <b>partner</b> must be usable by this company — refused by name, because the caller
   *       already found that partner through a lookup tool that only returns in-scope partners, so
   *       there is nothing left to leak and a precise reason is genuinely useful.
   * </ul>
   */
  protected void assertScope(AiOcrUpload upload, Invoice invoice, Partner partner) {
    Long allowed = allowedCompanyId();
    Long uploadCompany = upload.getCompany() == null ? null : upload.getCompany().getId();
    Long invoiceCompany = invoice.getCompany() == null ? null : invoice.getCompany().getId();
    if (allowed == null || !allowed.equals(uploadCompany) || !allowed.equals(invoiceCompany)) {
      throw new SupplierChangeRefusedException("No OCR record with id " + upload.getId() + ".");
    }
    // Axelor shares partners across companies through companySet: empty means "every company", a
    // non-empty set restricts the partner to those companies. Either way it must cover this one.
    if (!AiCompanyScope.partnerInScope(partner.getCompanySet())) {
      throw new SupplierChangeRefusedException(
          "Partner " + partner.getId() + " is not available for this company.");
    }
  }

  /**
   * The single company this caller may touch. Overridable so the scope rule can be exercised
   * without a live Shiro subject; there is no return value meaning "any company".
   */
  protected Long allowedCompanyId() {
    return AiCompanyScope.requireCompanyId();
  }

  protected String auditNote(String existing, SupplierChangeResult result) {
    StringBuilder sb =
        new StringBuilder("Supplier corrected by ")
            .append(AiExecutionContext.callerDescription())
            .append(":")
            .append("\n- partner: ")
            .append(
                result.getPreviousPartnerName() == null
                    ? "(empty)"
                    : result.getPreviousPartnerName() + " #" + result.getPreviousPartnerId())
            .append(" → ")
            .append(result.getNewPartnerName())
            .append(" #")
            .append(result.getNewPartnerId());
    result
        .getRefreshedFields()
        .forEach(
            (field, value) ->
                sb.append("\n- ")
                    .append(field)
                    .append(" refreshed to ")
                    .append(value == null ? "(empty)" : value));
    String note = sb.toString();
    if (existing == null || existing.trim().isEmpty()) {
      return note;
    }
    return existing + "\n\n" + note;
  }
}

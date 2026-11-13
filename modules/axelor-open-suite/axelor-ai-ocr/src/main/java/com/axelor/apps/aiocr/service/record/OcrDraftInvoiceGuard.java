package com.axelor.apps.aiocr.service.record;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.auth.AuthUtils;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import java.util.List;

/**
 * The one place that decides whether an OCR draft invoice may be modified at all.
 *
 * <p>Every correction service in this package needs the same five answers — is there a caller, does
 * the record exist, did OCR create it, is it still a draft, and is this caller allowed near it —
 * and having each of them answer separately is how one of them eventually ends up answering
 * differently. So the checks live here and the services call in.
 *
 * <p>The rules are unconditional and in this order:
 *
 * <ol>
 *   <li>a caller identity must exist — either an authenticated user or an explicit {@link
 *       AiExecutionContext} system scope. "No user" is never implicit trust;
 *   <li>the upload and its invoice must exist;
 *   <li>the invoice must be {@code aiGenerated} — this package never edits invoices a person
 *       created by hand;
 *   <li>the invoice must be in the state the operation requires — DRAFT for every correction in
 *       this package, and VALIDATED for the single operation that advances the lifecycle rather
 *       than editing it ({@link #resolveValidated}, used by ventilation). A ventilated document is
 *       never a target of either;
 *   <li>both the upload and the invoice must belong to the caller's active company ({@link
 *       AiCompanyScope}), and a real user must hold Axelor's own write permission on the row.
 * </ol>
 *
 * <p>The company refusal deliberately reuses the wording of the "no such record" refusal. Saying
 * "that one is another company's" would confirm the record exists, which is exactly what an id from
 * another company must not be able to establish.
 */
public final class OcrDraftInvoiceGuard {

  private OcrDraftInvoiceGuard() {}

  /** An authorised target: the upload and the draft invoice behind it. */
  public static final class Target {
    private final AiOcrUpload upload;
    private final Invoice invoice;

    Target(AiOcrUpload upload, Invoice invoice) {
      this.upload = upload;
      this.invoice = invoice;
    }

    public AiOcrUpload getUpload() {
      return upload;
    }

    public Invoice getInvoice() {
      return invoice;
    }

    /**
     * The line with this id on this invoice.
     *
     * @throws IllegalArgumentException when the invoice has no such line — which is also what stops
     *     a line id belonging to somebody else's invoice from being reachable
     */
    public InvoiceLine requireLine(long lineId) {
      List<InvoiceLine> lines = invoice.getInvoiceLineList();
      if (lines != null) {
        for (InvoiceLine line : lines) {
          if (line.getId() != null && line.getId() == lineId) {
            return line;
          }
        }
      }
      throw new OcrBlocked(
          AiBlockerCode.RECORD_NOT_FOUND,
          "Invoice " + invoice.getId() + " has no line with id " + lineId + ".");
    }
  }

  /**
   * Resolves and authorises an OCR upload that is about to have its invoice <b>created</b>.
   *
   * <p>{@link #resolve} cannot serve this case: it requires an invoice to exist, which is precisely
   * what is missing when intake found no Partner for the document's ЕИК. The checks that still
   * apply are applied — a caller identity, the record exists, and it belongs to the caller's active
   * company, worded identically to the not-found case — plus Axelor's own create permission on
   * {@code Invoice} instead of write permission on a row that does not exist yet.
   *
   * <p>Returns {@code null} — rather than throwing — when the upload already has an invoice,
   * because the caller's request is "create it if it is missing" and an existing draft simply means
   * there is nothing to create. An upload booked as a <b>move</b> is a refusal, not a no-op: that
   * document is not a supplier invoice at all, and quietly building one for it would contradict the
   * routing decision intake already made.
   *
   * @return the upload to create an invoice for, or {@code null} when it already has one
   * @throws IllegalStateException when there is no caller identity at all
   * @throws IllegalArgumentException for every other refusal, with a message safe to show a caller
   */
  public static AiOcrUpload resolveForCreationIfNeeded(long uploadId) {
    if (!AiExecutionContext.isAuthorisedCaller()) {
      throw new IllegalStateException(
          "No authenticated user; an invoice cannot be created without a caller identity.");
    }
    AiOcrUpload upload = Beans.get(AiOcrUploadRepository.class).find(uploadId);
    if (upload == null) {
      throw new OcrBlocked(
          AiBlockerCode.RECORD_NOT_FOUND, "No OCR record with id " + uploadId + ".");
    }
    if (!AiCompanyScope.owns(upload.getCompany())) {
      throw new OcrBlocked(
          AiBlockerCode.RECORD_NOT_FOUND, "No OCR record with id " + uploadId + ".");
    }
    if (upload.getInvoice() != null) {
      return null; // already has one — nothing to create
    }
    if (upload.getMove() != null) {
      throw new OcrBlocked(
          AiBlockerCode.NOT_AN_OCR_DRAFT,
          "OCR record " + uploadId + " was booked as an accounting Move, not a supplier invoice.");
    }
    if (AuthUtils.getUser() != null
        && !Beans.get(JpaSecurity.class).isPermitted(JpaSecurity.CAN_CREATE, Invoice.class)) {
      throw new OcrBlocked(
          AiBlockerCode.PERMISSION_DENIED, "You do not have permission to create invoices.");
    }
    return upload;
  }

  /**
   * Resolves and authorises the draft invoice behind an OCR upload.
   *
   * @throws IllegalStateException when there is no caller identity at all
   * @throws IllegalArgumentException for every other refusal, with a message safe to show a caller
   */
  public static Target resolve(long uploadId) {
    return resolve(
        uploadId, InvoiceRepository.STATUS_DRAFT, "is not a draft; only drafts may be corrected");
  }

  /**
   * As {@link #resolve}, but for the one operation whose target must already be <b>validated</b>:
   * ventilation.
   *
   * <p>The draft-only rule above exists to stop this package editing a document that has been
   * committed to. Ventilation is not an edit — it is the next step of the same lifecycle, and
   * Axelor itself refuses it on anything but a validated invoice ({@code
   * InvoiceServiceImpl.checkPreconditions}). Every other check is unchanged, which is the reason
   * this is a parameter of the existing guard rather than a second guard that could drift from it.
   */
  public static Target resolveValidated(long uploadId) {
    Target target =
        resolve(
            uploadId,
            InvoiceRepository.STATUS_VALIDATED,
            "is not validated; only a validated invoice can be ventilated");
    // A status of VALIDATED that no validation ever produced means something wrote the field
    // directly. Refuse: the whole point of posting only validated documents is that the validation
    // checks actually ran.
    if (target.getInvoice().getValidatedDateTime() == null) {
      throw new OcrBlocked(
          AiBlockerCode.INVALID_STATUS,
          "Invoice "
              + target.getInvoice().getId()
              + " is marked validated but carries no validation timestamp, so it was not validated"
              + " through Axelor. Refusing to post it.");
    }
    return target;
  }

  /**
   * The same record, whatever lifecycle status it is now in.
   *
   * <h2>For reading after a write, and nothing else</h2>
   *
   * The status rule the two resolvers above enforce is about what may be <em>changed</em>. There is
   * one situation that has to read a record without it: a validation or a ventilation whose answer
   * was lost, where the whole question is whether the status moved. Asking {@link #resolve} would
   * refuse precisely when the operation had succeeded, which turns the confirmation path into a
   * check that can only ever confirm failure.
   *
   * <p>Every other guard is unchanged and still applies — the caller identity, the company on both
   * the upload and the invoice, the OCR provenance and the write permission. So this widens what
   * may be looked at by exactly one field, and nothing about what may be written.
   *
   * @return the record, or null when it cannot be resolved in the active company; never throws,
   *     because its callers are already handling a failure and a second exception would replace a
   *     knowable answer with none
   */
  public static Target resolveAnyStatus(long uploadId) {
    try {
      return resolve(uploadId, null, null);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Resolves and authorises a DRAFT OCR invoice addressed by its own id, for a correction made from
   * the invoice form rather than from an upload.
   *
   * <p>The upload-keyed {@link #resolve} cannot serve the invoice form: an invoice can have several
   * uploads (a re-scan, a duplicate re-import), so there is no single upload id to key on, and the
   * correction is about the invoice anyway. Every other check is the same and in the same order —
   * caller identity, the record exists in the caller's active company (worded identically to
   * not-found), OCR provenance, DRAFT status, and Axelor's own write permission on the row — so
   * this cannot be a looser door to the same action.
   *
   * @throws IllegalStateException when there is no caller identity at all
   * @throws OcrBlocked for every other refusal, with a message safe to show a caller
   */
  public static Invoice resolveDraftInvoiceForCorrection(long invoiceId) {
    if (!AiExecutionContext.isAuthorisedCaller()) {
      throw new IllegalStateException(
          "No authenticated user; an invoice cannot be changed without a caller identity.");
    }
    Invoice invoice = Beans.get(InvoiceRepository.class).find(invoiceId);
    if (invoice == null || !AiCompanyScope.owns(invoice.getCompany())) {
      throw new OcrBlocked(AiBlockerCode.RECORD_NOT_FOUND, "No invoice with id " + invoiceId + ".");
    }
    if (!Boolean.TRUE.equals(invoice.getAiGenerated())) {
      throw new OcrBlocked(
          AiBlockerCode.NOT_AN_OCR_DRAFT,
          "Invoice " + invoiceId + " was not created by OCR; refusing to change it.");
    }
    if (invoice.getStatusSelect() == null
        || invoice.getStatusSelect().intValue() != InvoiceRepository.STATUS_DRAFT) {
      throw new OcrBlocked(
          AiBlockerCode.INVALID_STATUS,
          "Invoice " + invoiceId + " is not a draft; only drafts may be corrected.");
    }
    if (AuthUtils.getUser() != null
        && !Beans.get(JpaSecurity.class)
            .isPermitted(JpaSecurity.CAN_WRITE, Invoice.class, invoice.getId())) {
      throw new OcrBlocked(
          AiBlockerCode.PERMISSION_DENIED,
          "You do not have permission to change invoice " + invoiceId + ".");
    }
    return invoice;
  }

  private static Target resolve(long uploadId, Integer requiredStatus, String statusRefusal) {
    if (!AiExecutionContext.isAuthorisedCaller()) {
      throw new IllegalStateException(
          "No authenticated user; an invoice cannot be changed without a caller identity.");
    }
    AiOcrUpload upload = Beans.get(AiOcrUploadRepository.class).find(uploadId);
    if (upload == null) {
      throw new OcrBlocked(
          AiBlockerCode.RECORD_NOT_FOUND, "No OCR record with id " + uploadId + ".");
    }
    // Checked before anything else is revealed about the row, and worded identically to the
    // not-found case above.
    if (!AiCompanyScope.owns(upload.getCompany())) {
      throw new OcrBlocked(
          AiBlockerCode.RECORD_NOT_FOUND, "No OCR record with id " + uploadId + ".");
    }
    Invoice invoice = upload.getInvoice();
    if (invoice == null) {
      throw new OcrBlocked(
          AiBlockerCode.NOT_AN_OCR_DRAFT,
          "OCR record " + uploadId + " has no invoice, so there is nothing to act on.");
    }
    if (!Boolean.TRUE.equals(invoice.getAiGenerated())) {
      throw new OcrBlocked(
          AiBlockerCode.NOT_AN_OCR_DRAFT,
          "Invoice " + invoice.getId() + " was not created by OCR; refusing to change it.");
    }
    // Null means "any status", which only the read-only resolveAnyStatus passes. Every write path
    // names the status it requires, so a forgotten argument cannot silently widen one.
    if (requiredStatus != null
        && (invoice.getStatusSelect() == null
            || invoice.getStatusSelect().intValue() != requiredStatus.intValue())) {
      throw new OcrBlocked(
          AiBlockerCode.INVALID_STATUS, "Invoice " + invoice.getId() + " " + statusRefusal + ".");
    }
    // The upload and its invoice normally carry the same company, but they are separate columns and
    // only one of them was set by intake — so both are checked rather than one trusted.
    if (!AiCompanyScope.owns(invoice.getCompany())) {
      throw new OcrBlocked(
          AiBlockerCode.RECORD_NOT_FOUND, "No OCR record with id " + uploadId + ".");
    }
    if (AuthUtils.getUser() != null
        && !Beans.get(JpaSecurity.class)
            .isPermitted(JpaSecurity.CAN_WRITE, Invoice.class, invoice.getId())) {
      throw new OcrBlocked(
          AiBlockerCode.PERMISSION_DENIED,
          "You do not have permission to change invoice " + invoice.getId() + ".");
    }
    return new Target(upload, invoice);
  }
}

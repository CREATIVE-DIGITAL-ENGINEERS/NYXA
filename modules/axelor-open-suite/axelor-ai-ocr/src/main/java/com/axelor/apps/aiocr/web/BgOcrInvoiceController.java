package com.axelor.apps.aiocr.web;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.apps.aiocr.service.readiness.OcrReviewMessages;
import com.axelor.apps.aiocr.service.readiness.SupplierInvoiceButtonCheck;
import com.axelor.apps.aiocr.service.record.OcrDocumentTotalCorrectionService;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Invoice-form hooks for AI-OCR drafts (kept separate from the upload controller).
 *
 * <p>Both hooks compute display values from the posted form, which is harmless. What is not
 * harmless is the two id-driven side effects they trigger — {@code ensureBankDetailsPartner}
 * re-owns a bank account, {@code syncUploadReview} writes an upload's status — because those ids
 * come from the client's context and an action call is not filtered by {@code Permission.condition}
 * the way a grid or a record open is. {@link #assertOwnInvoice} gates them.
 */
@Singleton
public class BgOcrInvoiceController {

  /**
   * Confirms the invoice being saved belongs to the active company.
   *
   * <p>For a persisted invoice the company is read from the database, never from the posted
   * context: the context is the client's claim about the record, and trusting it would make the
   * check decorative. A brand-new invoice has no row yet, so the posted company is all there is —
   * and it still has to be the active one.
   *
   * @throws IllegalArgumentException with the not-found wording used across this package
   */
  protected void assertOwnInvoice(Invoice posted) {
    Company company;
    if (posted.getId() == null) {
      company = posted.getCompany();
    } else {
      Invoice stored = Beans.get(InvoiceRepository.class).find(posted.getId());
      if (stored == null) {
        throw new IllegalArgumentException(
            String.format(I18n.get("No invoice with id %s."), posted.getId()));
      }
      company = stored.getCompany();
    }
    if (!AiCompanyScope.owns(company)) {
      throw new IllegalArgumentException(
          String.format(I18n.get("No invoice with id %s."), posted.getId()));
    }
  }

  /**
   * onLoad hook: (re)compute the OCR review flag for an AI-generated invoice from its current
   * fields, so even invoices created before this feature (e.g. #206) show "To review" / "Complete"
   * correctly on open — WITHOUT re-running OCR or creating anything. The stock statusSelect is left
   * untouched. Display-only; it persists when the user saves.
   *
   * <p>Also resolves which {@code AiOcrUpload} record(s) produced this invoice — a fact the human
   * could not otherwise see: {@code AiOcrUpload.invoice} points here, but nothing on {@code
   * Invoice} points back, so an accountant reading "invoice 611" while the assistant mentions
   * "upload 932" had no way to confirm those are the same file. There is no uniqueness constraint
   * on that relationship — a re-scan or a duplicate re-import can legitimately leave more than one
   * upload linked to the same invoice — so every match is shown, never just one.
   */
  public void recalcReviewOnLoad(ActionRequest request, ActionResponse response) {
    try {
      Invoice invoice = request.getContext().asType(Invoice.class);
      if (!Boolean.TRUE.equals(invoice.getAiGenerated())) {
        return;
      }
      putReviewValues(invoice, response);
      response.setValue("$aiOcrUploadIds", linkedUploadIds(invoice));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * onChange hook of every field the review flag depends on (partner, address, currency, the lines,
   * bank details, payment condition): recompute the flag from the form as it is now, so Validate
   * unlocks the moment the last missing field is filled — without a Save first. Display only, like
   * onLoad; the upload is synchronized on save.
   */
  public void recalcReviewOnChange(ActionRequest request, ActionResponse response) {
    try {
      Invoice invoice = request.getContext().asType(Invoice.class);
      if (!Boolean.TRUE.equals(invoice.getAiGenerated())) {
        return;
      }
      putReviewValues(invoice, response);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * The review flag and everything the form says about it: the stored flag and key list, and the
   * three texts the user reads — what is missing, why Validate is locked, what will be needed to
   * pay. Returns whether the invoice is complete.
   */
  protected boolean putReviewValues(Invoice invoice, ActionResponse response) {
    List<String> missing = BgOcrIntakeService.missingReviewFields(invoice);
    List<String> warnings = BgOcrIntakeService.reviewWarnings(invoice);
    response.setValue(
        "ocrReviewStatus",
        missing.isEmpty()
            ? BgOcrIntakeService.OCR_REVIEW_COMPLETE
            : BgOcrIntakeService.OCR_REVIEW_TO_REVIEW);
    response.setValue("ocrMissingFields", BgOcrIntakeService.missingFieldsLabel(missing));
    response.setValue("$ocrMissingText", OcrReviewMessages.missingText(missing));
    response.setValue("$ocrValidateLockHint", OcrReviewMessages.lockHint(missing));
    response.setValue("$ocrPaymentWarnings", OcrReviewMessages.warningsText(warnings));
    return missing.isEmpty();
  }

  /**
   * The ids of every {@code AiOcrUpload} linked to this invoice, as {@code "#932, #940"}, or null
   * when there is none (a plain-text field with no value is what {@code showIf} on the dummy field
   * hides on).
   *
   * <p>Explicitly company-scoped rather than trusted from the relationship alone: bound to the
   * invoice's OWN company, not to whatever company happens to be active in the session, and skipped
   * entirely — returning null — when the loaded invoice is not one {@link AiCompanyScope} says this
   * session may see. A display-only read, so failing toward "show nothing" is the safe direction.
   */
  protected String linkedUploadIds(Invoice invoice) {
    if (invoice.getId() == null
        || invoice.getCompany() == null
        || !AiCompanyScope.owns(invoice.getCompany())) {
      return null;
    }
    List<AiOcrUpload> uploads =
        Beans.get(AiOcrUploadRepository.class)
            .all()
            .filter("self.invoice.id = :invoiceId AND self.company.id = :companyId")
            .bind("invoiceId", invoice.getId())
            .bind("companyId", invoice.getCompany().getId())
            .order("id")
            .fetch();
    if (uploads.isEmpty()) {
      return null;
    }
    return uploads.stream().map(u -> "#" + u.getId()).collect(Collectors.joining(", "));
  }

  /**
   * onSave hook: recompute the OCR review flag from the edited fields (partner, structured address,
   * currency, positive total), ensure a bank account created via "+ Create" is owned by the invoice
   * partner, and synchronize the linked upload (Needs review ↔ Completed). The stock invoice status
   * stays Draft either way.
   */
  public void recalcReviewOnSave(ActionRequest request, ActionResponse response) {
    try {
      Invoice invoice = request.getContext().asType(Invoice.class);
      if (!Boolean.TRUE.equals(invoice.getAiGenerated())) {
        return;
      }
      // Gate BEFORE the two writes below, not after: both take an id straight from the posted
      // context.
      assertOwnInvoice(invoice);
      BgOcrIntakeService service = Beans.get(BgOcrIntakeService.class);

      // A bank account picked/created for the supplier must belong to the supplier partner.
      if (invoice.getBankDetails() != null
          && invoice.getBankDetails().getId() != null
          && invoice.getPartner() != null
          && invoice.getPartner().getId() != null) {
        service.ensureBankDetailsPartner(
            invoice.getBankDetails().getId(), invoice.getPartner().getId());
      }

      boolean complete = putReviewValues(invoice, response);
      if (invoice.getId() != null) {
        service.syncUploadReview(invoice.getId(), complete);
      }
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Applies an accountant's correction of a mistaken OCR document total, entered in the form's
   * correction fields ($ocrCorrectedTotal and, optionally, $ocrCorrectedNet / $ocrCorrectedVat with
   * a note). The id comes from the saved record, never the posted context; company scope, OCR
   * provenance, draft status and write permission are all enforced in {@link
   * OcrDocumentTotalCorrectionService} / {@code OcrDraftInvoiceGuard}, so the posted context is
   * only a source of the typed amounts here.
   */
  public void applyDocumentTotalCorrection(ActionRequest request, ActionResponse response) {
    try {
      Invoice invoice = request.getContext().asType(Invoice.class);
      if (invoice.getId() == null) {
        response.setError(I18n.get("Save the invoice before correcting its document total."));
        return;
      }
      BigDecimal gross = decimalFromContext(request, "$ocrCorrectedTotal");
      BigDecimal net = decimalFromContext(request, "$ocrCorrectedNet");
      BigDecimal vat = decimalFromContext(request, "$ocrCorrectedVat");
      Object reason = request.getContext().get("$ocrCorrectionNote");
      Beans.get(OcrDocumentTotalCorrectionService.class)
          .correct(invoice.getId(), gross, net, vat, reason == null ? null : reason.toString());
      response.setReload(true);
      response.setNotify(I18n.get("Document total correction applied."));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** Reads a decimal from a dummy ($) context field, tolerating String/Number/BigDecimal. */
  protected BigDecimal decimalFromContext(ActionRequest request, String name) {
    Object raw = request.getContext().get(name);
    if (raw == null) {
      return null;
    }
    if (raw instanceof BigDecimal) {
      return (BigDecimal) raw;
    }
    if (raw instanceof Number) {
      return BigDecimal.valueOf(((Number) raw).doubleValue());
    }
    String text = raw.toString().trim();
    if (text.isEmpty()) {
      return null;
    }
    return new BigDecimal(text.replace(",", "."));
  }

  /**
   * First action of Validate on the invoice form: refuses, in the user's language and naming the
   * field or setting to fix, what Validate itself would refuse further down — and only that. Any
   * supplier invoice, not only OCR ones; does nothing for sales.
   */
  public void checkBeforeValidate(ActionRequest request, ActionResponse response) {
    refuseIfBlocked(request, response, false, SupplierInvoiceButtonCheck.Step.VALIDATE);
  }

  /** First action of Ventilate: as {@link #checkBeforeValidate}, for posting. */
  public void checkBeforeVentilate(ActionRequest request, ActionResponse response) {
    refuseIfBlocked(request, response, true, SupplierInvoiceButtonCheck.Step.VENTILATE);
  }

  /** First action of the single Validate button when ventilation is skipped: both checks. */
  public void checkBeforeValidateAndVentilate(ActionRequest request, ActionResponse response) {
    refuseIfBlocked(
        request,
        response,
        false,
        SupplierInvoiceButtonCheck.Step.VALIDATE,
        SupplierInvoiceButtonCheck.Step.VENTILATE);
  }

  /**
   * @param popupFollows true for Ventilate, whose action group asks for the supplier number and
   *     origin date in a pop-up when either is empty (action-invoice-group-ventilate)
   */
  protected void refuseIfBlocked(
      ActionRequest request,
      ActionResponse response,
      boolean popupFollows,
      SupplierInvoiceButtonCheck.Step... steps) {
    try {
      Invoice invoice = request.getContext().asType(Invoice.class);
      SupplierInvoiceButtonCheck check = Beans.get(SupplierInvoiceButtonCheck.class);
      SupplierInvoiceButtonCheck.Facts facts = check.gather(invoice);
      facts.popupAsksOriginDate =
          popupFollows
              && (invoice.getSupplierInvoiceNb() == null
                  || invoice.getSupplierInvoiceNb().isEmpty()
                  || invoice.getOriginDate() == null);
      java.util.LinkedHashSet<String> blockers = new java.util.LinkedHashSet<>();
      for (SupplierInvoiceButtonCheck.Step step : steps) {
        blockers.addAll(check.blockers(facts, step, I18n::get));
      }
      if (!blockers.isEmpty()) {
        // An error stops the button's action chain before anything is saved or validated.
        response.setError(String.join("<br>", blockers));
      }
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }
}

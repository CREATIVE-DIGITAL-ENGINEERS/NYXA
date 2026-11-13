package com.axelor.apps.aiocr.service.ai;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiaccountant.context.AiCompletionResolver;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.readiness.OcrInvoiceReadiness;
import com.axelor.apps.aiocr.service.readiness.OcrSupplierInvoiceReadinessService;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.util.ArrayList;

/**
 * Whether an OCR supplier invoice is actually finished with, for a job that has no destination.
 *
 * <h2>What "repaired" means, in a way that can be checked</h2>
 *
 * A repair job asks for a document to be put right; it names no lifecycle stage, so nothing about
 * the invoice's status settles it. What does settle it is the readiness report — the same one the
 * intake pipeline writes to {@code aiValidationNotes} and the same one {@code get_ocr_record}
 * shows: a structured list of what still stops this invoice being validated and ventilated.
 *
 * <p>So a repair target is complete when the <em>validation</em> half of that list is empty. Not "a
 * tool succeeded", not "the model said so": the document itself no longer reports a blocker on the
 * transition its own fields feed. The posting half is deliberately not required — those blockers
 * are the company's accounting configuration rather than anything wrong with this document, and a
 * repair job cannot be finished by fixing something it was never about.
 *
 * <p>A creation target is complete when the draft exists at all, which is what was asked for.
 *
 * <p>Read from the invoice as it stands now, so a record put right by hand in another tab counts —
 * the job is about the document reaching a state, not about who got it there.
 *
 * <h2>Deliberately narrow: repair and creation jobs, addressed by upload</h2>
 *
 * A validation or posting job has a destination and is settled by the lifecycle, which is a
 * stronger and simpler test; answering here as well would put two verdicts on one record with no
 * rule for which wins.
 *
 * <p>And only {@code Upload#} references. It used to answer for {@code Invoice#} too, so that a job
 * which recorded either name got the same verdict — right at the time, because every invoice was an
 * OCR supplier invoice. Now {@code InvoiceCompletion} in {@code axelor-ai-accounting} owns {@code
 * Invoice#}: it reads the invoice's own readiness and picks up these OCR blockers through {@code
 * AiInvoiceReadinessContributor}, so both names still agree — but exactly one resolver answers
 * each, instead of two racing on classpath order.
 *
 * <p>What only this class can still answer is an upload that has produced no invoice yet, which is
 * the state a creation job starts in. Anything else — another domain, another kind of reference, an
 * invoice this company cannot see — is {@code null}, and the caller leaves the target active.
 */
@Singleton
public class OcrRepairCompletion implements AiCompletionResolver {

  @Override
  public Boolean isComplete(String ref, String task) {
    if (!"REPAIR_SUPPLIER_INVOICE".equals(task) && !"CREATE_SUPPLIER_INVOICE".equals(task)) {
      return null;
    }
    AiOcrUpload upload = upload(ref);
    if (upload == null) {
      return null;
    }
    Invoice invoice = upload.getInvoice();
    if (invoice == null) {
      // A creation job is done when the draft exists; a repair job has nothing to have repaired.
      return "CREATE_SUPPLIER_INVOICE".equals(task) ? Boolean.FALSE : null;
    }
    if ("CREATE_SUPPLIER_INVOICE".equals(task)) {
      return Boolean.TRUE;
    }
    if (invoice.getStatusSelect() != null
        && invoice.getStatusSelect() == InvoiceRepository.STATUS_CANCELED) {
      // Cancelled is an ending, but not the one that was asked for. Left to the user rather than
      // reported as a repair that succeeded.
      return Boolean.FALSE;
    }
    OcrInvoiceReadiness readiness =
        Beans.get(OcrSupplierInvoiceReadinessService.class).check(invoice, new ArrayList<>());
    // Validation readiness only, and the same rule InvoiceCompletion applies to Invoice# — so the
    // two names for one document still agree. A repair is about the document; the extra posting
    // blockers are the company's accounting configuration (a journal for the type, a sequence for
    // the year, accounts on the partner), which the user did not ask about and this job was never
    // authorised to change. Holding the repair open on them reports finished work as unfinished.
    return readiness.isValidateReady();
  }

  /** The upload a {@code Upload#}/{@code AiOcrUpload#} reference names, company-scoped, or null. */
  protected AiOcrUpload upload(String ref) {
    if (ref == null) {
      return null;
    }
    int hash = ref.lastIndexOf('#');
    if (hash <= 0) {
      return null;
    }
    long id;
    try {
      id = Long.parseLong(ref.substring(hash + 1).trim());
    } catch (NumberFormatException e) {
      return null;
    }
    AiOcrUpload upload;
    switch (ref.substring(0, hash)) {
      case "Upload":
      case "AiOcrUpload":
        upload = Beans.get(AiOcrUploadRepository.class).find(id);
        break;
      default:
        // Invoice# included: InvoiceCompletion owns it, and answering here as well would be two
        // resolvers racing for one verdict.
        return null;
    }
    return upload == null || !AiCompanyScope.owns(upload.getCompany()) ? null : upload;
  }
}

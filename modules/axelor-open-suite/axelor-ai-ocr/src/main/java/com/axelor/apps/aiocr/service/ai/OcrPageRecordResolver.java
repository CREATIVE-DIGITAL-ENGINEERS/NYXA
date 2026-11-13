package com.axelor.apps.aiocr.service.ai;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiaccountant.context.AiPageRecordResolver;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.service.AiWorkflowIntent;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.apps.aiocr.service.readiness.OcrInvoiceReadiness;
import com.axelor.apps.aiocr.service.readiness.OcrSupplierInvoiceReadinessService;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers "which OCR record is this?" for the records an accountant actually has open.
 *
 * <p>The OCR Inbox is a filtered Invoice view, so the page context that reaches the model names an
 * {@code Invoice} id while every OCR tool takes an {@code uploadId}. Before this, the only bridge
 * was {@code search_ocr_records} — one extra model request and one tool round trip per turn, to
 * rediscover a record the server could join to in a single indexed lookup. The same applies to a
 * {@code Move} produced by OCR intake.
 *
 * <p>Scoped like everything else: the upload has to belong to the caller's active company, and a
 * record that does not is treated as not found rather than described. Never throws — a failure here
 * simply means the model does what it did before.
 */
@Singleton
public class OcrPageRecordResolver implements AiPageRecordResolver {

  private static final Logger LOG = LoggerFactory.getLogger(OcrPageRecordResolver.class);

  static final String INVOICE_MODEL = "com.axelor.apps.account.db.Invoice";
  static final String UPLOAD_MODEL = "com.axelor.apps.aiocr.db.AiOcrUpload";
  static final String MOVE_MODEL = "com.axelor.apps.account.db.Move";

  @Override
  public String describe(String model, long recordId) {
    if (INVOICE_MODEL.equals(model)) {
      return describeUpload(
          find("self.invoice.id = :id", recordId), "This invoice", "invoice " + recordId);
    }
    if (MOVE_MODEL.equals(model)) {
      return describeUpload(find("self.move.id = :id", recordId), "This move", "move " + recordId);
    }
    return null;
  }

  /**
   * One upload, chosen deterministically.
   *
   * <p>{@code self.invoice.id} and {@code self.move.id} are both non-unique — an invoice can carry
   * several uploads (a re-scan, a duplicate, a page split out of a ZIP), and so can a move. An
   * unordered {@code fetchOne} over those would describe the page differently on different days, so
   * the newest is taken: {@code AiOcrUpload} is {@code sequential}, and the newest upload is the
   * one intake last worked on.
   */
  protected AiOcrUpload find(String filter, long recordId) {
    List<AiOcrUpload> found =
        Beans.get(AiOcrUploadRepository.class)
            .all()
            .filter(filter)
            .bind("id", recordId)
            .order("-id")
            .fetch();
    if (found == null) {
      return null;
    }
    for (AiOcrUpload upload : found) {
      if (AiCompanyScope.owns(upload.getCompany())) {
        return upload;
      }
    }
    return null;
  }

  /**
   * The sentence itself. It states the consequence, not just the fact: left implicit, the model
   * still opened with a search — being told an id is not the same as being told it does not need to
   * look one up.
   */
  protected String describeUpload(AiOcrUpload upload, String subject, String fallbackLabel) {
    if (upload == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    sb.append(subject)
        .append(" was created by OCR from record uploadId=")
        .append(upload.getId())
        .append(" (")
        .append(upload.getFileName() == null ? fallbackLabel : upload.getFileName())
        .append("). Use uploadId=")
        .append(upload.getId())
        .append(" directly and do NOT call search_ocr_records.");
    Invoice invoice = upload.getInvoice();
    if (invoice != null && invoice.getStatusSelect() != null) {
      // The lifecycle stage decides which tools are even applicable, so saying it here stops a
      // ventilate attempt on a draft and a correction attempt on a posted document.
      sb.append(" Its invoice is currently ").append(statusWord(invoice.getStatusSelect()));
      sb.append(" (invoiceId=").append(invoice.getId()).append(").");
      appendReadiness(sb, invoice);
    }
    return sb.toString();
  }

  /**
   * Six deterministic flags, about forty tokens, so "is it ready?" needs no tool call.
   *
   * <p>Measured: a read-only readiness question costs a whole extra model request today — the model
   * has to call {@code get_ocr_record}, wait, and be asked again over the result, which is 2,500 ms
   * at the observed floor. Java already knows every one of these answers before the turn starts.
   * The counts are the whole point of the shape: they say <em>whether</em> something blocks, never
   * <em>what</em>, so a "why?" still goes to the read tool and gets the real blocker text. Anything
   * larger here would be paid on every single turn, cached or not, to answer a question that may
   * never be asked.
   *
   * <p>Only for DRAFT and VALIDATED. On a VENTILATED or CANCELLED invoice readiness is meaningless
   * — the status sentence above has already said the document is closed.
   *
   * <p>Never throws: readiness is an optimisation, and a failure here must leave the model doing
   * exactly what it did before rather than break the turn.
   */
  protected void appendReadiness(StringBuilder sb, Invoice invoice) {
    int status = invoice.getStatusSelect();
    if (status != InvoiceRepository.STATUS_DRAFT && status != InvoiceRepository.STATUS_VALIDATED) {
      return;
    }
    try {
      List<String> missing = BgOcrIntakeService.missingReviewFields(invoice);
      OcrInvoiceReadiness readiness =
          Beans.get(OcrSupplierInvoiceReadinessService.class).check(invoice, new ArrayList<>());
      int validateBlockers = readiness.getValidateBlockers().size();
      int ventilateBlockers = readiness.getVentilateBlockers().size();
      int terms = invoice.getInvoiceTermList() == null ? 0 : invoice.getInvoiceTermList().size();

      sb.append(" Readiness now: usable=")
          .append(yesNo(missing.isEmpty() && validateBlockers == 0 && ventilateBlockers == 0))
          .append(" readyToValidate=")
          .append(yesNo(validateBlockers == 0))
          .append(" readyToVentilate=")
          .append(yesNo(ventilateBlockers == 0))
          .append(" blockers=")
          .append(validateBlockers + ventilateBlockers)
          .append(" invoiceTerms=")
          .append(terms)
          .append(
              ". Answer readiness questions from these; call get_ocr_record only when asked WHY"
                  + " something is blocked, or before writing.");
    } catch (RuntimeException e) {
      LOG.debug("Could not add readiness to page context: {}", e.getMessage());
    }
  }

  private static String yesNo(boolean value) {
    return value ? "yes" : "no";
  }

  /**
   * The record's lifecycle in one clause, for a truncation summary.
   *
   * <p>Short and absolute — "invoice 594 is validated but not posted" — because it is read by
   * someone who has just been told the assistant ran out of room, and its whole job is to replace a
   * count of tool calls with a fact about the ledger.
   */
  @Override
  public String state(String model, long recordId) {
    Invoice invoice = ownInvoiceFor(model, recordId);
    if (invoice == null || invoice.getStatusSelect() == null) {
      return null;
    }
    switch (invoice.getStatusSelect()) {
      case InvoiceRepository.STATUS_DRAFT:
        return "invoice " + invoice.getId() + " is still a DRAFT (not validated, not posted)";
      case InvoiceRepository.STATUS_VALIDATED:
        return "invoice " + invoice.getId() + " is VALIDATED but NOT posted";
      case InvoiceRepository.STATUS_VENTILATED:
        return "invoice " + invoice.getId() + " is POSTED";
      case InvoiceRepository.STATUS_CANCELED:
        return "invoice " + invoice.getId() + " is CANCELLED";
      default:
        return null;
    }
  }

  /**
   * The same status as {@link #state}, as the closed token the server compares against what the
   * user authorised.
   *
   * <p>Read from the same {@code statusSelect} in the same lookup, so the sentence the user sees
   * and the decision the server makes can never disagree with each other.
   */
  @Override
  public String lifecycle(String model, long recordId) {
    Invoice invoice = ownInvoiceFor(model, recordId);
    if (invoice == null || invoice.getStatusSelect() == null) {
      return null;
    }
    switch (invoice.getStatusSelect()) {
      case InvoiceRepository.STATUS_DRAFT:
        return AiWorkflowIntent.DRAFT;
      case InvoiceRepository.STATUS_VALIDATED:
        return AiWorkflowIntent.VALIDATED;
      case InvoiceRepository.STATUS_VENTILATED:
        return AiWorkflowIntent.POSTED;
      case InvoiceRepository.STATUS_CANCELED:
        return AiWorkflowIntent.CANCELLED;
      default:
        return null;
    }
  }

  /** The invoice behind an Invoice id or an upload id, company-scoped, or null. */
  protected Invoice invoiceFor(String model, long recordId) {
    if (INVOICE_MODEL.equals(model)) {
      AiOcrUpload upload = find("self.invoice.id = :id", recordId);
      return upload == null ? null : upload.getInvoice();
    }
    if (UPLOAD_MODEL.equals(model)) {
      AiOcrUpload upload = find("self.id = :id", recordId);
      return upload == null ? null : upload.getInvoice();
    }
    return null;
  }

  /**
   * The invoice behind an <b>upload</b> id only — for the two answers that must have one owner.
   *
   * <h2>Why {@code state} and {@code lifecycle} stop at the upload</h2>
   *
   * {@code describe} is a multi-contributor method by design: an invoice can be both an accounting
   * document and an OCR record, and both facts are worth saying, so this class keeps contributing
   * its half for the Invoice page too. {@code state} and {@code lifecycle} are first-answer-wins,
   * and the first answer is whichever implementation the classpath scan reached first.
   *
   * <p>While every invoice was a supplier invoice that was harmless — this class and the invoice's
   * own resolver would have said the same thing. It stopped being harmless when a customer invoice
   * could be on screen: this class reads the status through an OCR upload, finds none, and answers
   * null, which is correct but only by accident. {@code InvoicePageRecordResolver} owns both
   * answers for the Invoice model now, reads the status from the invoice itself, and gets the
   * direction right. This class answers them for its own model, which nothing else can describe.
   */
  protected Invoice ownInvoiceFor(String model, long recordId) {
    if (!UPLOAD_MODEL.equals(model)) {
      return null;
    }
    AiOcrUpload upload = find("self.id = :id", recordId);
    return upload == null ? null : upload.getInvoice();
  }

  private static String statusWord(int status) {
    switch (status) {
      case 1:
        return "a DRAFT";
      case 2:
        return "VALIDATED (not yet posted)";
      case 3:
        return "VENTILATED (posted; it can no longer be corrected)";
      case 4:
        return "CANCELLED";
      default:
        return "in status " + status;
    }
  }
}

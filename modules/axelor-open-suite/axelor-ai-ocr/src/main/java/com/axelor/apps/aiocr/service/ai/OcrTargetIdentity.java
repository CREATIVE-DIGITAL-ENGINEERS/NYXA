package com.axelor.apps.aiocr.service.ai;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.aiaccountant.context.AiTargetIdentity;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;

/**
 * Says which record an OCR reference really names.
 *
 * <h2>Uploads only</h2>
 *
 * It used to answer for moves as well, from a time when this module owned the move tools, and then
 * for invoices, from a time when every invoice was a supplier invoice. It answers for neither now:
 * {@code MoveTargetIdentity} owns {@code Move#id} and {@code InvoiceRecordResolver} owns {@code
 * Invoice#id}, both in {@code axelor-ai-accounting}. A reference is answered by the module that
 * owns the document.
 *
 * <p>Giving up {@code Invoice#} was not tidying. A customer invoice is the same entity as a
 * supplier one, told apart only by {@code operationTypeSelect}, and this class could not see the
 * difference — so it would have classified a customer invoice as an OCR supplier document, in a
 * registry whose dispatch takes the first non-null answer in classpath order. One owner per
 * reference kind is what removes that case entirely.
 *
 * <h2>The two names of one invoice</h2>
 *
 * Every OCR tool addresses its work by upload id, so the workflow sees {@code Upload#946}; what
 * gets written is an invoice, so it also sees {@code Invoice#606}. They are one document, and until
 * something could say so the authorisation check had to either refuse legitimate work or wave
 * through anything whose kind it did not recognise. It waved things through.
 *
 * <p>That still works, from this side: an upload resolves to the invoice it produced, so both names
 * collapse to the same canonical one. What changed is who answers when the caller starts from the
 * invoice — {@code Invoice#606} now goes to the resolver that can read the invoice's own direction,
 * and reaches the same canonical form from there. An upload that has not produced an invoice yet
 * resolves to itself; it is still a real, company-scoped record.
 *
 * <h2>Company scoping is part of the answer</h2>
 *
 * A record of another company resolves to null, exactly as though it did not exist. The canonical
 * form is used to decide whether a write may proceed, so a reference the caller cannot read must
 * never satisfy an authorisation — and answering "not found" rather than "not yours" is the same
 * rule every read tool here follows.
 */
@Singleton
public class OcrTargetIdentity implements AiTargetIdentity {

  @Override
  public String canonical(String ref) {
    if (ref == null) {
      return null;
    }
    int hash = ref.lastIndexOf('#');
    if (hash <= 0) {
      return null;
    }
    String kind = ref.substring(0, hash);
    Long id = idOf(ref.substring(hash + 1));
    if (id == null) {
      return null;
    }
    switch (kind) {
      case "Upload":
      case "AiOcrUpload":
        return fromUpload(id);
      default:
        // Not this module's to answer — including Invoice#, which InvoiceRecordResolver owns. The
        // registry asks the others and, if none of them owns it, refuses; that is the intended
        // outcome for a reference nobody can identify.
        return null;
    }
  }

  /**
   * Whether this user could change the record by hand.
   *
   * <p>Asked of Axelor's own permission layer, on the entity the reference really names, so a
   * record the accountant has no write access to cannot enter an authorised set. An upload is
   * checked through the document it became: the OCR tools write the invoice, not the queue row.
   */
  @Override
  public Boolean writable(String ref) {
    String canonical = canonical(ref);
    if (canonical == null) {
      // Either not ours, or not identifiable in this company. Both are the registry's to turn into
      // a refusal; saying "false" here would claim ownership of a reference we may not own.
      return null;
    }
    int hash = canonical.lastIndexOf('#');
    Long id = idOf(canonical.substring(hash + 1));
    if (id == null) {
      return false;
    }
    JpaSecurity security = Beans.get(JpaSecurity.class);
    switch (canonical.substring(0, hash)) {
      case "Invoice":
        return security.isPermitted(JpaSecurity.CAN_WRITE, Invoice.class, id);
      case "Move":
        return security.isPermitted(JpaSecurity.CAN_WRITE, Move.class, id);
      case "AiOcrUpload":
        return security.isPermitted(JpaSecurity.CAN_WRITE, AiOcrUpload.class, id);
      default:
        return false;
    }
  }

  /** The invoice an upload became, or the upload itself while it is still only an upload. */
  protected String fromUpload(long id) {
    AiOcrUpload upload = Beans.get(AiOcrUploadRepository.class).find(id);
    if (upload == null || !AiCompanyScope.owns(upload.getCompany())) {
      return null;
    }
    if (upload.getInvoice() != null && upload.getInvoice().getId() != null) {
      return "Invoice#" + upload.getInvoice().getId();
    }
    if (upload.getMove() != null && upload.getMove().getId() != null) {
      return "Move#" + upload.getMove().getId();
    }
    return "AiOcrUpload#" + id;
  }

  private static Long idOf(String text) {
    try {
      return Long.parseLong(text.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}

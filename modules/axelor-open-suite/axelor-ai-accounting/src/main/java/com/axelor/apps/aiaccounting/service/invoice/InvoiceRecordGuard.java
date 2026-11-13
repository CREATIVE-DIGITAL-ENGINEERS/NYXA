package com.axelor.apps.aiaccounting.service.invoice;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.auth.AuthUtils;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.util.List;

/**
 * The one place that decides whether an invoice may be acted on at all.
 *
 * <p>Every service in this package needs the same five answers — is there a caller, does the record
 * exist, is it the right kind of invoice, is it in the state this operation needs, and is this
 * caller allowed near it — and having each of them answer separately is how one of them eventually
 * answers differently.
 *
 * <p>The rules are unconditional and in this order:
 *
 * <ol>
 *   <li>a caller identity must exist — either an authenticated user or an explicit {@link
 *       AiExecutionContext} system scope. "No user" is never implicit trust;
 *   <li>the invoice must exist;
 *   <li>it must belong to the caller's active company ({@link AiCompanyScope});
 *   <li>it must be on the {@link InvoiceSide} the calling tool is for — a supplier tool never
 *       reaches a customer invoice, and the reverse;
 *   <li>it must be in the status the operation requires;
 *   <li>a real user must hold Axelor's own write permission on the row.
 * </ol>
 *
 * <h2>Two refusals are worded identically, on purpose</h2>
 *
 * "No such invoice" and "that one is another company's" say the same thing, because the second
 * confirms the record exists — which is exactly what an id from another company must not be able to
 * establish. The wrong-side refusal is worded differently and deliberately so: both invoices are
 * this company's, the caller can see them, and being told to use the other tool is useful rather
 * than leaky.
 *
 * <h2>Why this does not check {@code aiGenerated}</h2>
 *
 * {@code OcrDraftInvoiceGuard} does, because that package only ever edits invoices OCR created.
 * This one exists for the opposite reason: an invoice typed in by hand is a perfectly ordinary
 * invoice and there was no way to reach it at all. Provenance is not a permission.
 */
@Singleton
public class InvoiceRecordGuard {

  /** An authorised target: the invoice, and the side it was resolved for. */
  public static final class Target {
    private final Invoice invoice;
    private final InvoiceSide side;

    Target(Invoice invoice, InvoiceSide side) {
      this.invoice = invoice;
      this.side = side;
    }

    public Invoice getInvoice() {
      return invoice;
    }

    public InvoiceSide getSide() {
      return side;
    }

    /**
     * The line with this id on this invoice.
     *
     * @throws InvoiceBlocked when the invoice has no such line — which is also what stops a line id
     *     belonging to somebody else's invoice from being reachable
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
      throw new InvoiceBlocked(
          AiBlockerCode.RECORD_NOT_FOUND,
          "Invoice " + invoice.getId() + " has no line with id " + lineId + ".");
    }
  }

  /** The invoice, required to be an editable DRAFT. */
  public Target resolveDraft(long invoiceId, InvoiceSide side) {
    return resolve(
        invoiceId,
        side,
        InvoiceRepository.STATUS_DRAFT,
        "is not a draft; only drafts may be edited");
  }

  /** The invoice, required to be VALIDATED — what ventilation and a terms refresh need. */
  public Target resolveValidated(long invoiceId, InvoiceSide side) {
    Target target =
        resolve(
            invoiceId,
            side,
            InvoiceRepository.STATUS_VALIDATED,
            "is not validated; only a validated invoice can be posted");
    // A status of VALIDATED that no validation ever produced means something wrote the field
    // directly. Refuse: the whole point of posting only validated documents is that the validation
    // checks actually ran.
    if (target.getInvoice().getValidatedDateTime() == null) {
      throw new InvoiceBlocked(
          AiBlockerCode.INVALID_STATUS,
          "Invoice "
              + target.getInvoice().getId()
              + " is marked validated but carries no validation timestamp, so it was not validated"
              + " through Axelor. Refusing to post it.");
    }
    return target;
  }

  /**
   * The invoice in whatever status it is now in.
   *
   * <p>For reading — a detail view, a readiness report, or the confirmation path of a write whose
   * answer was lost, where the whole question is whether the status moved. Every other guard is
   * unchanged and still applies.
   */
  public Target resolveAnyStatus(long invoiceId, InvoiceSide side) {
    return resolve(invoiceId, side, null, null);
  }

  /**
   * As {@link #resolveAnyStatus}, but null instead of a refusal.
   *
   * <p>For callers already handling a failure, where a second exception would replace a knowable
   * answer with none.
   */
  public Target findAnyStatus(long invoiceId, InvoiceSide side) {
    try {
      return resolveAnyStatus(invoiceId, side);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private Target resolve(
      long invoiceId, InvoiceSide side, Integer requiredStatus, String statusRefusal) {
    if (!AiExecutionContext.isAuthorisedCaller()) {
      throw new IllegalStateException(
          "No authenticated user; an invoice cannot be reached without a caller identity.");
    }
    Invoice invoice = Beans.get(InvoiceRepository.class).find(invoiceId);
    if (invoice == null) {
      throw notFound(invoiceId);
    }
    // Checked before anything else is revealed about the row, and worded identically to not-found.
    if (!AiCompanyScope.owns(invoice.getCompany())) {
      throw notFound(invoiceId);
    }
    if (!side.covers(invoice)) {
      throw new InvoiceBlocked(
          side == InvoiceSide.CUSTOMER
              ? AiBlockerCode.NOT_A_CUSTOMER_INVOICE
              : AiBlockerCode.NOT_A_SUPPLIER_INVOICE,
          "Invoice "
              + invoiceId
              + " is not a "
              + side.label()
              + " (operationTypeSelect "
              + invoice.getOperationTypeSelect()
              + "). Use the tools for the other direction; an invoice never changes side.");
    }
    // Null means "any status", which only the read paths pass. Every write path names the status it
    // requires, so a forgotten argument cannot silently widen one.
    if (requiredStatus != null
        && (invoice.getStatusSelect() == null
            || invoice.getStatusSelect().intValue() != requiredStatus.intValue())) {
      throw new InvoiceBlocked(
          AiBlockerCode.INVALID_STATUS, "Invoice " + invoiceId + " " + statusRefusal + ".");
    }
    if (AuthUtils.getUser() != null
        && !Beans.get(JpaSecurity.class)
            .isPermitted(JpaSecurity.CAN_WRITE, Invoice.class, invoice.getId())) {
      throw new InvoiceBlocked(
          AiBlockerCode.PERMISSION_DENIED,
          "You do not have permission to change invoice " + invoiceId + ".");
    }
    return new Target(invoice, side);
  }

  private static InvoiceBlocked notFound(long invoiceId) {
    return new InvoiceBlocked(
        AiBlockerCode.RECORD_NOT_FOUND, "No invoice with id " + invoiceId + ".");
  }
}

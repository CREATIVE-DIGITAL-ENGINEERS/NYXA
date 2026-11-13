package com.axelor.apps.aiaccounting.service.invoice;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.service.invoice.InvoiceLineService;
import com.axelor.apps.account.service.invoice.InvoiceService;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldChange;
import com.axelor.apps.base.db.Partner;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One correction to a DRAFT invoice: the partner, the header and the lines, in one transaction.
 *
 * <h2>Why it is one call and one transaction</h2>
 *
 * A correction is rarely one field. Changing a line's product and the invoice's payment condition
 * are one instruction from the user's point of view, and doing them as two calls means the second
 * can fail after the first has landed — leaving a half-corrected invoice that neither the user nor
 * the next turn can reason about. So everything the caller asked for is applied inside one
 * transaction and either all of it holds or none of it does.
 *
 * <h2>Order is deliberate</h2>
 *
 * <ol>
 *   <li><b>Partner</b> first, because the header defaults and the line accounts both depend on who
 *       the invoice is for.
 *   <li><b>Header</b> next, because the fiscal position and the currency change what the lines
 *       mean.
 *   <li><b>Lines</b> after that, each through Axelor's own fill-and-compute chain.
 *   <li><b>Totals</b> last, once — {@code InvoiceService.compute} rebuilt after every individual
 *       change would be wasted work and, worse, would report intermediate totals as if they were
 *       the answer.
 * </ol>
 */
@Singleton
public class InvoiceEditService {

  private static final Logger LOG = LoggerFactory.getLogger(InvoiceEditService.class);

  /** One line operation the caller asked for. */
  public static final class LineOperation {
    private final String operation;
    private final Long lineId;
    private final JsonNode spec;

    public LineOperation(String operation, Long lineId, JsonNode spec) {
      this.operation = operation;
      this.lineId = lineId;
      this.spec = spec;
    }

    public String getOperation() {
      return operation;
    }

    public Long getLineId() {
      return lineId;
    }

    public JsonNode getSpec() {
      return spec;
    }
  }

  /** Everything the caller asked to change. */
  public static final class EditRequest {
    private Partner partner;
    private JsonNode header;
    private final List<LineOperation> lineOperations = new ArrayList<>();
    private String reason;

    public EditRequest setPartner(Partner partner) {
      this.partner = partner;
      return this;
    }

    public EditRequest setHeader(JsonNode header) {
      this.header = header;
      return this;
    }

    public EditRequest addLineOperation(LineOperation operation) {
      this.lineOperations.add(operation);
      return this;
    }

    public EditRequest setReason(String reason) {
      this.reason = reason;
      return this;
    }

    public boolean isEmpty() {
      return partner == null && (header == null || header.size() == 0) && lineOperations.isEmpty();
    }
  }

  /** What happened, in full. */
  public static final class EditResult {
    private final Invoice invoice;
    private final List<AiInvoiceFieldChange> changes = new ArrayList<>();
    private final List<String> rejected = new ArrayList<>();
    private final List<String> steps = new ArrayList<>();

    EditResult(Invoice invoice) {
      this.invoice = invoice;
    }

    public Invoice getInvoice() {
      return invoice;
    }

    public List<AiInvoiceFieldChange> getChanges() {
      return changes;
    }

    /**
     * Always empty on a returned result: a field that cannot be applied refuses the whole edit
     * instead. Kept so existing callers that still report it compile unchanged.
     */
    public List<String> getRejected() {
      return rejected;
    }

    /** Which stages actually did something, for the reply. */
    public List<String> getSteps() {
      return steps;
    }

    public boolean isChanged() {
      return !changes.isEmpty();
    }
  }

  /**
   * Applies the whole correction, or refuses it and changes nothing.
   *
   * <h2>Why the transaction is one call down</h2>
   *
   * A refusal rolls the transaction back, but the invoice is a managed entity: the fields set
   * before the refusal are still on it in the persistence context, and the next write in the same
   * session would flush them with its own commit — a refused edit landing by side effect. So when
   * the transaction has ended in a refusal, the invoice (and, by cascade, its lines) is detached
   * here, outside it; the next read loads it from the database as it really is. When a caller's own
   * transaction is still open, the session is left to it.
   */
  public EditResult apply(long invoiceId, InvoiceSide side, EditRequest request) {
    try {
      // Through the injector: @Transactional is a method interceptor, and a self-call would bypass
      // it.
      return intercepted().applyInTransaction(invoiceId, side, request);
    } catch (RuntimeException e) {
      discardRolledBackState(invoiceId);
      throw e;
    }
  }

  /** The correction itself. Call {@link #apply}, which also cleans up after a refusal. */
  @Transactional(rollbackOn = Exception.class)
  public EditResult applyInTransaction(long invoiceId, InvoiceSide side, EditRequest request) {
    if (request == null || request.isEmpty()) {
      throw new InvoiceBlocked(
          AiBlockerCode.BAD_ARGUMENTS,
          "Nothing was asked for: give a header field, a line operation or a partner to move the"
              + " invoice to.");
    }
    InvoiceRecordGuard.Target target = resolveDraft(invoiceId, side);
    Invoice invoice = target.getInvoice();
    EditResult result = new EditResult(invoice);
    boolean linesNeedRefresh = false;

    if (request.partner != null) {
      InvoicePartnerReassignService.Result reassigned =
          Beans.get(InvoicePartnerReassignService.class).reassign(invoice, side, request.partner);
      if (reassigned.isChanged()) {
        result.changes.addAll(reassigned.getChanges());
        result.steps.add("partner");
      }
    }

    if (request.header != null && request.header.size() > 0) {
      InvoiceHeaderEditService.Result header = headerEditor().apply(invoice, side, request.header);
      // Atomic, as the tools promise: one refused field refuses the whole correction, before any
      // line is touched and before anything is saved. The throw rolls back the partner and the
      // fields already applied in memory; apply() then clears them from the session.
      header.refuseIfAnyRejected("Nothing was changed.");
      result.changes.addAll(header.getChanges());
      if (header.isChanged()) {
        result.steps.add("header");
      }
      linesNeedRefresh = header.isLinesNeedRecompute();
    }

    if (!request.lineOperations.isEmpty()) {
      InvoiceLineEditService lines = Beans.get(InvoiceLineEditService.class);
      for (LineOperation operation : ordered(request.lineOperations)) {
        switch (operation.getOperation()) {
          case "add":
            result.changes.addAll(lines.addLine(invoice, side, operation.getSpec()).getChanges());
            break;
          case "update":
            {
              InvoiceLine line = target.requireLine(requireLineId(operation));
              result.changes.addAll(
                  lines.updateLine(invoice, side, line, operation.getSpec()).getChanges());
              break;
            }
          case "delete":
            {
              InvoiceLine line = target.requireLine(requireLineId(operation));
              result.changes.addAll(lines.deleteLine(invoice, line).getChanges());
              break;
            }
          default:
            throw new InvoiceBlocked(
                AiBlockerCode.BAD_ARGUMENTS,
                "Unknown line operation '"
                    + operation.getOperation()
                    + "'; use add, update or delete.");
        }
      }
      result.steps.add("lines");
    }

    // A fiscal position change rewrites the lines' taxes and accounts, and only Axelor knows how.
    if (linesNeedRefresh) {
      try {
        Beans.get(InvoiceLineService.class).updateLinesAfterFiscalPositionChange(invoice);
        result.steps.add("lineTaxes");
      } catch (Exception e) {
        throw new InvoiceBlocked(
            AiBlockerCode.TOOL_FAILED,
            "The fiscal position changed but Axelor could not update the lines for it: "
                + message(e),
            e);
      }
    }

    if (!result.isChanged()) {
      // Nothing to save, and nothing to report as done. Said plainly rather than reported as a
      // successful edit that changed nothing.
      return result;
    }

    computeAndSave(invoice);
    AiWriteEffects.touched(Invoice.class.getName(), invoice.getId());
    LOG.info(
        "AI Accountant: invoice {} edited — {} change(s) in {}.",
        invoiceId,
        result.changes.size(),
        String.join(", ", result.steps));
    return result;
  }

  /** The one write: Axelor's totals, then the save. Reached only when nothing was refused. */
  protected void computeAndSave(Invoice invoice) {
    try {
      // Once, at the end: the header totals follow from whatever the lines now are.
      Beans.get(InvoiceService.class).compute(invoice);
    } catch (Exception e) {
      throw new InvoiceBlocked(
          AiBlockerCode.TOOL_FAILED,
          "Axelor could not recompute the invoice totals: " + message(e),
          e);
    }
    JPA.save(invoice);
    JPA.flush();
  }

  /** The DRAFT this edit is for, company-scoped and side-checked. A seam for tests. */
  protected InvoiceRecordGuard.Target resolveDraft(long invoiceId, InvoiceSide side) {
    return Beans.get(InvoiceRecordGuard.class).resolveDraft(invoiceId, side);
  }

  /** The header editor. A seam for tests. */
  protected InvoiceHeaderEditService headerEditor() {
    return Beans.get(InvoiceHeaderEditService.class);
  }

  /** This service through the injector, so {@code @Transactional} applies. A seam for tests. */
  protected InvoiceEditService intercepted() {
    return Beans.get(InvoiceEditService.class);
  }

  /**
   * Drops what a rolled-back correction left on the invoice in the persistence context.
   *
   * <p>Only the invoice — detaching it cascades to its lines — never the whole session: a chat turn
   * may hold other managed entities that must stay attached. And only once the transaction is over:
   * inside a caller's still-open transaction, whether its work is kept is its decision. Best effort
   * — a session that cannot be inspected is left as it is, and the refusal is still the answer.
   */
  protected void discardRolledBackState(long invoiceId) {
    try {
      if (!JPA.em().getTransaction().isActive()) {
        Invoice managed = JPA.em().find(Invoice.class, invoiceId);
        if (managed != null) {
          JPA.em().detach(managed);
        }
      }
    } catch (RuntimeException e) {
      LOG.debug(
          "AI Accountant: could not clear the session after a refused edit: {}", e.getMessage());
    }
  }

  /**
   * Updates, then deletes, then adds.
   *
   * <p>Deleting before adding keeps line ids stable while the updates run, and adding last means a
   * new line is never renumbered by a removal that came after it. The caller's own order within
   * each group is preserved.
   */
  protected List<LineOperation> ordered(List<LineOperation> operations) {
    List<LineOperation> sorted = new ArrayList<>();
    for (String phase : new String[] {"update", "delete", "add"}) {
      for (LineOperation operation : operations) {
        if (phase.equals(operation.getOperation())) {
          sorted.add(operation);
        }
      }
    }
    // Anything with an unrecognised operation is kept so the switch above can refuse it by name,
    // rather than being silently dropped here.
    for (LineOperation operation : operations) {
      if (!sorted.contains(operation)) {
        sorted.add(operation);
      }
    }
    return sorted;
  }

  private static long requireLineId(LineOperation operation) {
    if (operation.getLineId() == null) {
      throw new InvoiceBlocked(
          AiBlockerCode.BAD_ARGUMENTS,
          "A line " + operation.getOperation() + " needs the lineId it applies to.");
    }
    return operation.getLineId();
  }

  private static String message(Exception e) {
    String text = e.getMessage();
    if (text == null || text.trim().isEmpty()) {
      return e.getClass().getSimpleName();
    }
    return text.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
  }
}

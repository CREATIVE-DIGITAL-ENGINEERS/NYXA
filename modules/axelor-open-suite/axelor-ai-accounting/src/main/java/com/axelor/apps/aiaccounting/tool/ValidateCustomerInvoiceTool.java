package com.axelor.apps.aiaccounting.tool;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiaccountant.resilience.AiWriteOutcome;
import com.axelor.apps.aiaccountant.resilience.AiWriteProbe;
import com.axelor.apps.aiaccountant.resilience.AiWriteVerifier;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccountant.tool.AiWriteMode;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceLifecycleService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceSide;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceViewService;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * WRITE tool: run Axelor's Validate on a DRAFT customer invoice.
 *
 * <p>Takes no accounting decision of its own: it checks readiness, then calls {@code
 * InvoiceService.validate} — the same call the Validate button makes.
 */
@Singleton
public class ValidateCustomerInvoiceTool implements AiTool, AiWriteVerifier {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "validate_customer_invoice";
  }

  @Override
  public String description() {
    return "Validates a DRAFT customer invoice through Axelor's real lifecycle "
        + "(InvoiceService.validate): recomputes the totals and the per-rate tax summary from the "
        + "lines, creates the invoice terms and due dates, resolves the receivable account and "
        + "assigns the customer journal, moving Draft → Validated. Refused with the reason and "
        + "nothing changed when the invoice is not a DRAFT or a blocker remains — get_customer_"
        + "invoice lists those blockers, so read them rather than discovering them here. Does NOT "
        + "post: no accounting move, no invoice number yet. Say 'validated', never 'posted'.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props
        .putObject("invoiceId")
        .put("type", "integer")
        .put("description", "The DRAFT customer invoice to validate.");
    schema.putArray("required").add("invoiceId");
    schema.put("additionalProperties", false);
    return schema;
  }

  @Override
  public AiCapability capability() {
    return AiCapability.CUSTOMER_INVOICE;
  }

  @Override
  public AiToolKind kind() {
    return AiToolKind.WRITE;
  }

  /** Never unattended: a validated document is an accounting commitment, not a data correction. */
  @Override
  public Set<AiWriteMode> writeModes() {
    return EnumSet.of(AiWriteMode.CONFIRMED);
  }

  /** After edits (20), before posting (40). */
  @Override
  public int writeOrder() {
    return 30;
  }

  @Override
  public String targetKey(JsonNode args) {
    Long invoiceId = AiToolArgs.optId(args, "invoiceId");
    return invoiceId == null ? null : "Invoice#" + invoiceId;
  }

  /** The status before the call — the baseline the postcondition is read against. */
  @Override
  public Object beforeExecute(JsonNode args) {
    try {
      return Beans.get(InvoiceLifecycleService.class)
          .scopedStatus(AiToolArgs.requireId(args, "invoiceId"), InvoiceSide.CUSTOMER);
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * After a lost answer: was the invoice validated?
   *
   * <p>One field settles it. This tool refuses anything that is not a draft, so the status before
   * the call was DRAFT: still DRAFT afterwards proves the transaction rolled back and nothing
   * happened, which is retryable; VALIDATED proves it landed and is reported as the ordinary
   * success it is. Anything else — VENTILATED, CANCELED, unreadable — is something this call did
   * not do, and interpreting it is not this method's business.
   */
  @Override
  public AiWriteOutcome verifyAfterAmbiguousFailure(JsonNode args, AiWriteProbe probe) {
    Integer before = probe == null ? null : (Integer) probe.toolState();
    if (before == null || before != InvoiceRepository.STATUS_DRAFT) {
      return AiWriteOutcome.unknown();
    }
    long invoiceId;
    try {
      invoiceId = AiToolArgs.requireId(args, "invoiceId");
    } catch (RuntimeException e) {
      return AiWriteOutcome.unknown();
    }
    InvoiceLifecycleService service = Beans.get(InvoiceLifecycleService.class);
    Integer now = service.scopedStatus(invoiceId, InvoiceSide.CUSTOMER);
    if (now == null) {
      return AiWriteOutcome.unknown();
    }
    if (now == InvoiceRepository.STATUS_DRAFT) {
      return AiWriteOutcome.notWritten();
    }
    if (now != InvoiceRepository.STATUS_VALIDATED) {
      return AiWriteOutcome.unknown();
    }
    Map<String, Object> result = service.confirmedResult(invoiceId, InvoiceSide.CUSTOMER);
    return result == null ? AiWriteOutcome.unknown() : AiWriteOutcome.completed(result);
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    long invoiceId = AiToolArgs.requireId(args, "invoiceId");
    try {
      Invoice invoice =
          Beans.get(InvoiceLifecycleService.class).validate(invoiceId, InvoiceSide.CUSTOMER);
      Map<String, Object> out =
          Beans.get(InvoiceViewService.class)
              .writeSummary(invoice, java.util.Collections.singletonList("validated"));
      out.put(
          "nextStep",
          "Validated, not posted. ventilate_customer_invoice is what puts it in the books and"
              + " assigns its number.");
      return out;
    } catch (RuntimeException e) {
      throw InvoiceToolSupport.asToolException(e);
    }
  }
}

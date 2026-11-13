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
 * WRITE tool: post a VALIDATED customer invoice — the irreversible one.
 *
 * <p>Calls {@code InvoiceService.ventilate}, which assigns the invoice number from the company's
 * customer sequence and creates the accounting move. There is no undo from the chat, and a
 * correction afterwards means a credit note.
 */
@Singleton
public class VentilateCustomerInvoiceTool implements AiTool, AiWriteVerifier {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "ventilate_customer_invoice";
  }

  @Override
  public String description() {
    return "Posts a VALIDATED customer invoice through Axelor's real lifecycle "
        + "(InvoiceService.ventilate): assigns the invoice number from the company's customer "
        + "sequence, creates the accounting move, and moves Validated → Ventilated. This CANNOT be "
        + "undone from the chat — a correction afterwards means a credit note raised in Axelor "
        + "itself. Refused with the reason and nothing changed when the "
        + "invoice is not VALIDATED or a blocker remains. When the same turn was asked to validate "
        + "and post, call validate_customer_invoice and this together in one response.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props
        .putObject("invoiceId")
        .put("type", "integer")
        .put("description", "The VALIDATED customer invoice to post.");
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

  /** Never unattended. The highest-consequence call in this module. */
  @Override
  public Set<AiWriteMode> writeModes() {
    return EnumSet.of(AiWriteMode.CONFIRMED);
  }

  /** Last (40): everything else has to have landed before the books are touched. */
  @Override
  public int writeOrder() {
    return 40;
  }

  @Override
  public String targetKey(JsonNode args) {
    Long invoiceId = AiToolArgs.optId(args, "invoiceId");
    return invoiceId == null ? null : "Invoice#" + invoiceId;
  }

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
   * After a lost answer: was the invoice posted?
   *
   * <p>The tool refuses anything that is not VALIDATED, so the baseline is VALIDATED. Still
   * VALIDATED means the transaction rolled back — nothing was posted, no number was taken, no move
   * exists — which is provable and retryable. VENTILATED means it landed. Anything else is not
   * something posting produces, so it is unknown, and this is the one operation where guessing
   * would either double-post or tell somebody their books are missing an entry that is in them.
   */
  @Override
  public AiWriteOutcome verifyAfterAmbiguousFailure(JsonNode args, AiWriteProbe probe) {
    Integer before = probe == null ? null : (Integer) probe.toolState();
    if (before == null || before != InvoiceRepository.STATUS_VALIDATED) {
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
    if (now == InvoiceRepository.STATUS_VALIDATED) {
      return AiWriteOutcome.notWritten();
    }
    if (now != InvoiceRepository.STATUS_VENTILATED) {
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
          Beans.get(InvoiceLifecycleService.class).ventilate(invoiceId, InvoiceSide.CUSTOMER);
      Map<String, Object> out =
          Beans.get(InvoiceViewService.class)
              .writeSummary(invoice, java.util.Collections.singletonList("posted"));
      out.put("posted", true);
      out.put(
          "nextStep",
          "In the books as "
              + invoice.getInvoiceId()
              + ". Report that number and the move; a correction now needs a credit note.");
      return out;
    } catch (RuntimeException e) {
      throw InvoiceToolSupport.asToolException(e);
    }
  }
}

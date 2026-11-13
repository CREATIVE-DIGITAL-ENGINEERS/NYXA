package com.axelor.apps.aiaccounting.tool;

import com.axelor.apps.account.db.Invoice;
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
 * WRITE tool: rebuild a validated customer invoice's payment terms and due dates.
 *
 * <p>The same two {@code InvoiceTermService} calls the form makes when the payment condition is
 * re-picked, so nothing about the amounts, the status or the invoice date moves as a side effect.
 */
@Singleton
public class RefreshCustomerInvoiceTermsTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "refresh_customer_invoice_terms";
  }

  @Override
  public String description() {
    return "Rebuilds the payment terms and due dates of a VALIDATED customer invoice through "
        + "Axelor's own InvoiceTermService — the same calls the form makes when the payment "
        + "condition is re-picked. Changes no amount, no date on the invoice itself and no status. "
        + "Use it when the terms are stale after the payment condition or the dates were corrected. "
        + "Refused with the reason and nothing changed when the invoice is not VALIDATED or its "
        + "payment condition cannot be used.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props
        .putObject("invoiceId")
        .put("type", "integer")
        .put("description", "The VALIDATED customer invoice whose terms are rebuilt.");
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

  @Override
  public Set<AiWriteMode> writeModes() {
    return EnumSet.of(AiWriteMode.CONFIRMED);
  }

  /** Between validation (30) and posting (40), which is where a stale-terms fix belongs. */
  @Override
  public int writeOrder() {
    return 35;
  }

  @Override
  public String targetKey(JsonNode args) {
    Long invoiceId = AiToolArgs.optId(args, "invoiceId");
    return invoiceId == null ? null : "Invoice#" + invoiceId;
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    long invoiceId = AiToolArgs.requireId(args, "invoiceId");
    try {
      Invoice invoice =
          Beans.get(InvoiceLifecycleService.class).refreshTerms(invoiceId, InvoiceSide.CUSTOMER);
      Map<String, Object> out =
          Beans.get(InvoiceViewService.class)
              .writeSummary(invoice, java.util.Collections.singletonList("termsRefreshed"));
      out.put(
          "termCount",
          invoice.getInvoiceTermList() == null ? 0 : invoice.getInvoiceTermList().size());
      return out;
    } catch (RuntimeException e) {
      throw InvoiceToolSupport.asToolException(e);
    }
  }
}

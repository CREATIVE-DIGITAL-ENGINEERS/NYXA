package com.axelor.apps.aiaccounting.tool;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceReadiness;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceReadinessService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceSearchService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceSide;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceStructuredBlockers;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceViewService;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;

/** READ tool: one customer invoice in full — header, lines, totals, status and what blocks it. */
@Singleton
public class GetCustomerInvoiceTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "get_customer_invoice";
  }

  @Override
  public String description() {
    return "Returns one customer invoice in full: header, every line, the per-rate tax summary, the "
        + "computed totals, its status, and the structured blockers that say why it cannot yet be "
        + "validated or posted. This is how to answer 'what is on it' and 'why will it not go "
        + "through' — the blockers are read from the record and the company's accounting "
        + "configuration, so do not guess them from the lines. One call returns everything; do not "
        + "follow it with a search. Refuses a supplier invoice: those have their own tools.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props
        .putObject("invoiceId")
        .put("type", "integer")
        .put("description", "The invoice id, as search_customer_invoices reported it.");
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
    return AiToolKind.READ;
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    long invoiceId = AiToolArgs.requireId(args, "invoiceId");
    // findScoped answers not-found, another company's and the other direction identically for the
    // first two, on purpose: knowing an id from another company must not confirm it exists.
    Invoice invoice =
        Beans.get(InvoiceSearchService.class).findScoped(invoiceId, InvoiceSide.CUSTOMER);
    if (invoice == null) {
      Invoice anySide = Beans.get(InvoiceSearchService.class).findScoped(invoiceId, null);
      if (anySide != null) {
        // It exists in this company but is a supplier document. Worth saying plainly: the caller
        // reached for the wrong tool, and nothing about another company is revealed by admitting
        // it.
        throw new AiToolException(
            "Invoice "
                + invoiceId
                + " is a supplier invoice, not a customer one. Use the supplier-invoice tools for"
                + " it; an invoice never changes direction.");
      }
      throw new AiToolException("No customer invoice with id " + invoiceId + ".");
    }
    if (!Beans.get(JpaSecurity.class)
        .isPermitted(JpaSecurity.CAN_READ, Invoice.class, invoice.getId())) {
      throw new AiToolException("You do not have permission to read invoice " + invoiceId + ".");
    }

    Map<String, Object> out =
        new LinkedHashMap<>(Beans.get(InvoiceViewService.class).detail(invoice));
    InvoiceReadiness readiness = Beans.get(InvoiceReadinessService.class).check(invoice, null);
    out.put("validateBlockers", readiness.getValidateBlockers());
    out.put("postBlockers", readiness.getVentilateBlockers());
    out.put("warnings", readiness.getWarnings());
    out.put("validateReady", readiness.isValidateReady());
    // Same alias the supplier Get tool carries, so a caller keyed on either name reads the same
    // answer whichever side the invoice is.
    out.put("readyToValidate", readiness.isValidateReady());
    out.put("postReady", readiness.isVentilateReady());
    // Field-addressable findings — "lines[2].account: missing" — beside the prose blockers above,
    // exactly as get_supplier_invoice returns them and as this tool's own domain prompt promises.
    // InvoiceStructuredBlockers withholds the purchase-only vatCreditType finding on a sale, so no
    // supplier/BG concept leaks onto a customer invoice.
    out.put("structuredBlockers", InvoiceStructuredBlockers.build(invoice));
    return out;
  }
}

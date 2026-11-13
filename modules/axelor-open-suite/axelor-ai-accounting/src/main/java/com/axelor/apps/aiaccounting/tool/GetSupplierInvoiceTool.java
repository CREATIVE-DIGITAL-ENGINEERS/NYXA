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

/**
 * READ tool: one supplier invoice in full, by its own id.
 *
 * <p>Beside {@code get_ocr_record}, which is anchored on the upload and returns the extraction with
 * it. This one answers for any supplier invoice, including the ones that never went through OCR and
 * that the upload-anchored tool cannot see at all.
 */
@Singleton
public class GetSupplierInvoiceTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "get_supplier_invoice";
  }

  @Override
  public String description() {
    return "Returns one supplier invoice in full by invoiceId: header, every line, the per-rate tax "
        + "summary, the computed totals, its status, and the structured blockers that say why it "
        + "cannot yet be validated or posted. Works for ANY supplier invoice of the active company, "
        + "including ones typed in by hand. For an OCR-created invoice, get_ocr_record additionally "
        + "returns what the scanned document said — prefer it when the question is about the "
        + "extraction. Refuses a customer invoice: those have their own tools.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props
        .putObject("invoiceId")
        .put("type", "integer")
        .put("description", "The invoice id, as search_supplier_invoices reported it.");
    schema.putArray("required").add("invoiceId");
    schema.put("additionalProperties", false);
    return schema;
  }

  @Override
  public AiCapability capability() {
    return AiCapability.SUPPLIER_INVOICE;
  }

  @Override
  public AiToolKind kind() {
    return AiToolKind.READ;
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    long invoiceId = AiToolArgs.requireId(args, "invoiceId");
    Invoice invoice =
        Beans.get(InvoiceSearchService.class).findScoped(invoiceId, InvoiceSide.SUPPLIER);
    if (invoice == null) {
      Invoice anySide = Beans.get(InvoiceSearchService.class).findScoped(invoiceId, null);
      if (anySide != null) {
        throw new AiToolException(
            "Invoice "
                + invoiceId
                + " is a customer invoice, not a supplier one. Use the customer-invoice tools for"
                + " it; an invoice never changes direction.");
      }
      throw new AiToolException("No supplier invoice with id " + invoiceId + ".");
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
    out.put("readyToValidate", readiness.isValidateReady());
    out.put("postReady", readiness.isVentilateReady());
    // Field-addressable findings — "lines[2].account: missing" — alongside the prose blockers
    // above; see InvoiceStructuredBlockers for what each entry means and why it never blocks
    // anything on its own.
    out.put("structuredBlockers", InvoiceStructuredBlockers.build(invoice));
    // Whether this invoice came from OCR is deliberately not reported here. The field that says so
    // is contributed by axelor-ai-ocr, which this module does not depend on and must not — and any
    // OCR blocker that matters has already arrived through AiInvoiceReadinessContributor. When the
    // question is about the scanned document, search_ocr_records and get_ocr_record are the tools.
    return out;
  }
}

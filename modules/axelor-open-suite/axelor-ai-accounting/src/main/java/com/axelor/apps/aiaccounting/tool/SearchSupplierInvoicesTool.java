package com.axelor.apps.aiaccounting.tool;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiReadGuard;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceSearchService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceSide;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceViewService;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * READ tool: find this company's supplier invoices — all of them, not only the OCR ones.
 *
 * <h2>Why this exists beside search_ocr_records</h2>
 *
 * {@code search_ocr_records} searches the intake queue: uploads, and the drafts they became. That
 * is the right tool for "what is waiting in the inbox" and the wrong one for "what did we book from
 * this supplier in March", because an invoice somebody typed in by hand has no upload row and is
 * therefore invisible to it. This searches the invoices themselves.
 */
@Singleton
public class SearchSupplierInvoicesTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "search_supplier_invoices";
  }

  @Override
  public String description() {
    return "Finds supplier invoices (purchases and supplier credit notes) of the active company — "
        + "every one of them, whether OCR created it or somebody typed it in. All filters are "
        + "optional and ANDed: supplier, status, invoice or due date range, amount range, currency, "
        + "number (ours or the supplier's), unpaidOnly. Returns one page with totalMatches — when "
        + "that is larger than what came back, say so or fetch the rest with offset. Use "
        + "search_ocr_records instead for what is waiting in the OCR intake queue; use "
        + "get_supplier_invoice for the full contents of one. Customer invoices are never returned.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props.putObject("supplierId").put("type", "integer").put("description", "Exact supplier id.");
    props
        .putObject("supplierName")
        .put("type", "string")
        .put("description", "Partial supplier name.");
    props
        .putObject("supplierCode")
        .put("type", "string")
        .put("description", "The supplier's ЕИК or VAT number, matched exactly.");
    props
        .putObject("invoiceNumber")
        .put("type", "string")
        .put(
            "description",
            "Partial invoice number — matches both ours and the supplier's own number.");
    props
        .putObject("status")
        .put("type", "string")
        .put("description", "DRAFT, VALIDATED, VENTILATED or CANCELED.")
        .putArray("enum")
        .add("DRAFT")
        .add("VALIDATED")
        .add("VENTILATED")
        .add("CANCELED");
    props
        .putObject("operationTypeSelect")
        .put("type", "integer")
        .put("description", "Narrow to 1 (supplier purchase) or 2 (supplier credit note).")
        .putArray("enum")
        .add(InvoiceSide.SUPPLIER.standardType())
        .add(InvoiceSide.SUPPLIER.refundType());
    props.putObject("invoiceDateFrom").put("type", "string").put("description", "ISO date.");
    props.putObject("invoiceDateTo").put("type", "string").put("description", "ISO date.");
    props.putObject("dueDateFrom").put("type", "string").put("description", "ISO date.");
    props.putObject("dueDateTo").put("type", "string").put("description", "ISO date.");
    props
        .putObject("minInTaxTotal")
        .put("type", "number")
        .put("description", "Lowest gross total to include.");
    props
        .putObject("maxInTaxTotal")
        .put("type", "number")
        .put("description", "Highest gross total to include.");
    props.putObject("currencyCode").put("type", "string").put("description", "e.g. BGN, EUR.");
    props
        .putObject("unpaidOnly")
        .put("type", "boolean")
        .put("description", "Only invoices with an amount still remaining.");
    props
        .putObject("limit")
        .put("type", "integer")
        .put("description", "Page size (default 20, max 50).");
    props.putObject("offset").put("type", "integer").put("description", "Rows to skip.");
    schema.putArray("required");
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
    AiReadGuard.requireRead(com.axelor.apps.account.db.Invoice.class, "invoices");
    InvoiceSearchService.InvoiceQuery query = new InvoiceSearchService.InvoiceQuery();
    query.side = InvoiceSide.SUPPLIER;
    query.partnerId = AiToolArgs.optId(args, "supplierId");
    query.partnerName = AiToolArgs.optText(args, "supplierName");
    query.partnerRegistrationCode = AiToolArgs.optText(args, "supplierCode");
    query.invoiceNumber = AiToolArgs.optText(args, "invoiceNumber");
    query.statusSelect = InvoiceToolSupport.statusValue(AiToolArgs.optText(args, "status"));
    query.operationTypeSelect =
        args != null && args.hasNonNull("operationTypeSelect")
            ? args.get("operationTypeSelect").asInt()
            : null;
    query.invoiceDateFrom = AiToolArgs.optDate(args, "invoiceDateFrom");
    query.invoiceDateTo = AiToolArgs.optDate(args, "invoiceDateTo");
    query.dueDateFrom = AiToolArgs.optDate(args, "dueDateFrom");
    query.dueDateTo = AiToolArgs.optDate(args, "dueDateTo");
    query.minInTaxTotal = AiToolArgs.optDecimal(args, "minInTaxTotal");
    query.maxInTaxTotal = AiToolArgs.optDecimal(args, "maxInTaxTotal");
    query.currencyCode = AiToolArgs.optText(args, "currencyCode");
    query.unpaidOnly = AiToolArgs.optBool(args, "unpaidOnly");
    query.limit =
        AiToolArgs.limit(
            args, "limit", InvoiceSearchService.DEFAULT_LIMIT, InvoiceSearchService.MAX_LIMIT);
    query.offset =
        args != null && args.hasNonNull("offset") ? Math.max(0, args.get("offset").asInt()) : 0;

    InvoiceSearchService.Page page;
    try {
      page = Beans.get(InvoiceSearchService.class).search(query);
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new AiToolException(e.getMessage());
    }

    InvoiceViewService view = Beans.get(InvoiceViewService.class);
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Invoice invoice : page.getInvoices()) {
      rows.add(view.row(invoice));
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("totalMatches", page.getTotalMatches());
    out.put("returned", rows.size());
    out.put("offset", query.offset);
    out.put("invoices", rows);
    long shown = (long) query.offset + rows.size();
    if (shown < page.getTotalMatches()) {
      out.put("nextOffset", shown);
      out.put(
          "nextStep",
          "This is one page of "
              + page.getTotalMatches()
              + ". Fetch the rest with offset="
              + shown
              + ", or tell the user how many there are in total.");
    }
    return out;
  }
}

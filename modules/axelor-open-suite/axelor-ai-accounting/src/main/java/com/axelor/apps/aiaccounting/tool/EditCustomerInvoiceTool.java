package com.axelor.apps.aiaccounting.tool;

import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccountant.tool.AiWriteMode;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceEditService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceHeaderEditService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceLineEditService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceSide;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceViewService;
import com.axelor.apps.aiaccounting.service.invoice.PartnerSearchService;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WRITE tool: correct a DRAFT customer invoice — header, lines and customer — in one call.
 *
 * <p>One call and one transaction, because a correction is rarely one field: two calls means the
 * second can fail after the first has landed, and a half-corrected invoice is worse than an
 * uncorrected one. DRAFT only, exactly as the form allows.
 */
@Singleton
public class EditCustomerInvoiceTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "edit_customer_invoice";
  }

  @Override
  public String description() {
    return "Corrects a DRAFT customer invoice: header fields, line add/update/delete, and the "
        + "customer itself — all in ONE atomic call, so make the whole correction at once. Omitted "
        + "fields are left unchanged; an explicit null clears a field that is allowed to be empty. "
        + "Header totals are NOT editable and no argument accepts them: if a total is wrong, the "
        + "lines are wrong. The invoice's direction can never be changed. Refuses with the reason "
        + "and changes nothing when the invoice is not a DRAFT, an id or code does not resolve, or "
        + "any requested field is refused — no part of the call is applied then.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props
        .putObject("invoiceId")
        .put("type", "integer")
        .put("description", "The DRAFT customer invoice to correct.");
    props.set(
        "header",
        InvoiceToolSupport.fieldObjectSchema(
            InvoiceHeaderEditService.fieldSpecs(InvoiceSide.CUSTOMER),
            "Header fields to change. Omit what should stay as it is."));
    ObjectNode operations = props.putObject("lineOperations");
    operations.put("type", "array");
    operations.put(
        "description",
        "Line changes, applied as updates, then deletes, then adds — whatever order they are given"
            + " in.");
    ObjectNode item = operations.putObject("items");
    item.put("type", "object");
    ObjectNode itemProps = item.putObject("properties");
    itemProps
        .putObject("operation")
        .put("type", "string")
        .put("description", "add, update or delete.")
        .putArray("enum")
        .add("add")
        .add("update")
        .add("delete");
    itemProps
        .putObject("lineId")
        .put("type", "integer")
        .put("description", "Required for update and delete; ignored for add.");
    itemProps.set(
        "line",
        InvoiceToolSupport.fieldObjectSchema(
            InvoiceLineEditService.fieldSpecs(InvoiceSide.CUSTOMER),
            "The line's fields. For update, only the ones that change. Not used by delete."));
    item.putArray("required").add("operation");
    item.put("additionalProperties", false);
    props
        .putObject("customerId")
        .put("type", "integer")
        .put(
            "description",
            "Move the invoice to a different customer, by id. Re-resolves the address, bank"
                + " account and payment defaults with it, so only do this when asked.");
    props
        .putObject("customerCode")
        .put("type", "string")
        .put("description", "As customerId, by ЕИК or VAT number. Give one of the two, not both.");
    props
        .putObject("reason")
        .put("type", "string")
        .put("description", "Why the change is being made. One short sentence.");
    schema.putArray("required").add("invoiceId");
    // Moving the customer is optional; naming them twice is not allowed either way.
    InvoiceToolSupport.declareAtMostOne(schema, "customerId", "customerCode");
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

  /** Before validation (30) and posting (40): every correction has to land first. */
  @Override
  public int writeOrder() {
    return 20;
  }

  @Override
  public String targetKey(JsonNode args) {
    Long invoiceId = AiToolArgs.optId(args, "invoiceId");
    return invoiceId == null ? null : "Invoice#" + invoiceId;
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    long invoiceId = AiToolArgs.requireId(args, "invoiceId");
    InvoiceEditService.EditRequest request = new InvoiceEditService.EditRequest();
    request.setHeader(args.get("header"));
    request.setReason(AiToolArgs.optText(args, "reason"));

    boolean movesCustomer = args.hasNonNull("customerId") || args.hasNonNull("customerCode");
    if (movesCustomer) {
      InvoiceToolSupport.requireExactlyOne(args, "customerId", "customerCode");
      request.setPartner(
          InvoiceToolSupport.resolvePartner(
              args,
              Beans.get(PartnerSearchService.class),
              PartnerSearchService.Role.CUSTOMER,
              "customerId",
              "customerCode"));
    }

    JsonNode operations = args.get("lineOperations");
    if (operations != null && operations.isArray()) {
      for (JsonNode operation : operations) {
        String verb = AiToolArgs.optText(operation, "operation");
        if (verb == null) {
          throw new AiToolException(
              "Each line operation needs an operation: add, update or delete.");
        }
        request.addLineOperation(
            new InvoiceEditService.LineOperation(
                verb.trim().toLowerCase(java.util.Locale.ROOT),
                AiToolArgs.optId(operation, "lineId"),
                operation.get("line")));
      }
    }

    InvoiceEditService.EditResult result;
    try {
      result = Beans.get(InvoiceEditService.class).apply(invoiceId, InvoiceSide.CUSTOMER, request);
    } catch (RuntimeException e) {
      throw InvoiceToolSupport.asToolException(e);
    }

    Map<String, Object> out =
        new LinkedHashMap<>(
            Beans.get(InvoiceViewService.class)
                .writeSummary(result.getInvoice(), result.getSteps()));
    List<Map<String, String>> changes = new ArrayList<>();
    result
        .getChanges()
        .forEach(
            change -> {
              Map<String, String> row = new LinkedHashMap<>();
              row.put("field", change.getField());
              row.put("from", change.getOldValue());
              row.put("to", change.getNewValue());
              changes.add(row);
            });
    out.put("changed", changes);
    // No "rejected" list: a field that cannot be applied refuses the whole call before anything is
    // saved, so a returned result is always the complete correction.
    if (changes.isEmpty()) {
      out.put(
          "nextStep",
          "Nothing changed — every value given already matched the invoice. Say so rather than"
              + " reporting an edit.");
    }
    return out;
  }
}

package com.axelor.apps.aiaccounting.tool;

import com.axelor.apps.aiaccountant.context.AiCreationTarget;
import com.axelor.apps.aiaccountant.resilience.AiWriteOutcome;
import com.axelor.apps.aiaccountant.resilience.AiWriteProbe;
import com.axelor.apps.aiaccountant.resilience.AiWriteVerifier;
import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccountant.tool.AiWriteMode;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceCreationService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceHeaderEditService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceLineEditService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceSide;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceViewService;
import com.axelor.apps.aiaccounting.service.invoice.PartnerSearchService;
import com.axelor.apps.base.db.Partner;
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
 * WRITE tool: raise a new DRAFT customer invoice.
 *
 * <p>Builds it through {@code InvoiceGenerator.createInvoiceHeader} — Axelor's own header assembly,
 * the same one its order-to-invoice conversions use — so the payment mode, payment condition,
 * invoicing address, currency, in-ATI flag and bank details all come from the customer and the
 * company's configuration rather than from anything decided here.
 *
 * <p>Always a customer sale. There is no argument for the operation type, because the tool's own
 * name is what fixes it: a credit note is a different document with a different origin, and no tool
 * here raises one.
 */
@Singleton
public class CreateCustomerInvoiceTool implements AiTool, AiWriteVerifier {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "create_customer_invoice";
  }

  @Override
  public String description() {
    return "Creates a new DRAFT customer invoice (a sale) for one customer, optionally with its "
        + "lines, through Axelor's own invoice generator. Give exactly one of customerId or "
        + "customerCode (ЕИК/VAT). Header fields and lines are optional and can be set later with "
        + "edit_customer_invoice. Totals are computed from the lines — you cannot set them. No "
        + "journal is chosen by default (Axelor assigns it at validation); set header.journalId or "
        + "header.journalCode only when the user names a journal of this company. If any header "
        + "field is refused, nothing is created. The number is a draft "
        + "placeholder until the invoice is posted, so do not present it as the invoice number. "
        + "Creates a sale, never a credit note. "
        + "Invent no price, quantity or product: this is a document the company will send out.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props
        .putObject("customerId")
        .put("type", "integer")
        .put("description", "The customer's Nyxa id. Give this or customerCode, not both.");
    props
        .putObject("customerCode")
        .put("type", "string")
        .put(
            "description",
            "The customer's ЕИК or VAT number, matched exactly. Alternative to customerId; exactly"
                + " one of the two is required. A name is never accepted here — use find_customer"
                + " first.");
    props.set(
        "header",
        InvoiceToolSupport.fieldObjectSchema(
            InvoiceHeaderEditService.fieldSpecs(InvoiceSide.CUSTOMER),
            "Optional header fields to set on the new invoice, overriding the resolved defaults."));
    ObjectNode lines = props.putObject("lines");
    lines.put("type", "array");
    lines.put(
        "description",
        "Optional initial lines. Each needs a product (productId or productCode) or a"
            + " productName/description, plus qty and a price in the form this invoice uses.");
    lines.set(
        "items",
        InvoiceToolSupport.fieldObjectSchema(
            InvoiceLineEditService.fieldSpecs(InvoiceSide.CUSTOMER), "One invoice line."));
    props
        .putObject("reason")
        .put("type", "string")
        .put("description", "Why it is being raised. One short sentence.");
    schema.putArray("required");
    // Exactly one of the customer's two names, said in the schema and not only in the description.
    InvoiceToolSupport.declareExactlyOne(schema, "customerId", "customerCode");
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

  /** Never unattended: an invoice this company issues is a document somebody will receive. */
  @Override
  public Set<AiWriteMode> writeModes() {
    return EnumSet.of(AiWriteMode.CONFIRMED);
  }

  /** Early (10): everything else in a turn about this invoice needs it to exist first. */
  @Override
  public int writeOrder() {
    return 10;
  }

  /**
   * The creation destination: the kind of document and the company, never a record.
   *
   * <p>Null used to be the honest answer, and it made the tool unusable: {@code
   * AiWriteAuthorisation.checkTargets} refuses a null target once a job has a closed set, and
   * refuses outright when it has none, so every explicit «издай фактура на …» was refused before
   * the tool ran. The job now freezes this same reference from the route's own task — see {@link
   * AiCreationTarget} — so the two match and no existing record does.
   */
  @Override
  public String targetKey(JsonNode args) {
    return AiCreationTarget.ref(AiDomain.CUSTOMER_INVOICE);
  }

  /**
   * After a lost answer: is the invoice this call was creating actually in the database?
   *
   * <p>The same existence test on the id the attempt itself saved as the supplier-side tool, and
   * for the same reason: without it every timed-out creation ends "unknown" and the model is left
   * able to create a second invoice for a document that may already be in the books. See {@code
   * InvoiceToolSupport.verifyCreated}.
   */
  @Override
  public AiWriteOutcome verifyAfterAmbiguousFailure(JsonNode args, AiWriteProbe probe) {
    return InvoiceToolSupport.verifyCreated(probe, InvoiceSide.CUSTOMER);
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    InvoiceToolSupport.requireExactlyOne(args, "customerId", "customerCode");
    PartnerSearchService partners = Beans.get(PartnerSearchService.class);
    Partner customer =
        InvoiceToolSupport.resolvePartner(
            args, partners, PartnerSearchService.Role.CUSTOMER, "customerId", "customerCode");

    JsonNode header = args == null ? null : args.get("header");
    JsonNode lines = args == null ? null : args.get("lines");

    InvoiceCreationService.Result created;
    try {
      created =
          Beans.get(InvoiceCreationService.class)
              .create(InvoiceSide.CUSTOMER, customer, header, lines);
    } catch (RuntimeException e) {
      throw InvoiceToolSupport.asToolException(e);
    }

    Map<String, Object> out =
        new LinkedHashMap<>(
            Beans.get(InvoiceViewService.class)
                .writeSummary(
                    created.getInvoice(), java.util.Collections.singletonList("created")));
    out.put("created", true);
    List<String> changes = new ArrayList<>();
    created.getChanges().forEach(change -> changes.add(change.getField()));
    out.put("fieldsSet", changes);
    if (!created.getWarnings().isEmpty()) {
      out.put("warnings", created.getWarnings());
    }
    String reason = AiToolArgs.optText(args, "reason");
    if (reason != null) {
      out.put("reason", reason);
    }
    out.put(
        "nextStep",
        "The invoice is a DRAFT. Add or correct lines with edit_customer_invoice, then"
            + " validate_customer_invoice when it is right.");
    return out;
  }
}

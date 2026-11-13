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
 * WRITE tool: raise a new DRAFT supplier invoice by hand.
 *
 * <h2>Why this is not the OCR path</h2>
 *
 * {@code edit_supplier_invoice} with {@code createIfMissing} materialises the invoice an OCR upload
 * implies, from what was extracted from the scan. This one starts from nothing: a supplier the user
 * named, and lines they gave. Both are legitimate and neither can stand in for the other — an
 * upload's invoice must reflect the document, and a hand-entered one has no document to reflect.
 *
 * <p>Always a supplier purchase. A credit note against a supplier invoice is raised in Axelor
 * itself; no tool here does it.
 */
@Singleton
public class CreateSupplierInvoiceTool implements AiTool, AiWriteVerifier {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "create_supplier_invoice";
  }

  @Override
  public String description() {
    return "Creates a new DRAFT supplier invoice (a purchase) for one supplier, optionally with its "
        + "lines, through Axelor's own invoice generator. For an invoice being entered by hand — for "
        + "one that arrived as a scan, the OCR intake creates the draft and edit_supplier_invoice "
        + "with createIfMissing materialises it. Give exactly one of supplierId or supplierCode "
        + "(ЕИК/VAT). Totals are computed from the lines — you cannot set them. No journal is "
        + "chosen by default (Axelor assigns it at validation); set header.journalId or "
        + "header.journalCode only when the user names a journal of this company. If any header "
        + "field is refused, nothing is created. Remember supplierInvoiceNb and "
        + "originDate: validation requires the supplier's own number and date. Creates a purchase, "
        + "never a credit note.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props
        .putObject("supplierId")
        .put("type", "integer")
        .put("description", "The supplier's Nyxa id. Give this or supplierCode, not both.");
    props
        .putObject("supplierCode")
        .put("type", "string")
        .put(
            "description",
            "The supplier's ЕИК or VAT number, matched exactly. Alternative to supplierId; exactly"
                + " one of the two is required. A name is never accepted here — use find_supplier"
                + " first.");
    props.set(
        "header",
        InvoiceToolSupport.fieldObjectSchema(
            InvoiceHeaderEditService.fieldSpecs(InvoiceSide.SUPPLIER),
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
            InvoiceLineEditService.fieldSpecs(InvoiceSide.SUPPLIER), "One invoice line."));
    props
        .putObject("reason")
        .put("type", "string")
        .put("description", "Why it is being raised. One short sentence.");
    schema.putArray("required");
    // Exactly one of the supplier's two names, said in the schema and not only in the description.
    InvoiceToolSupport.declareExactlyOne(schema, "supplierId", "supplierCode");
    schema.put("additionalProperties", false);
    return schema;
  }

  @Override
  public AiCapability capability() {
    return AiCapability.SUPPLIER_INVOICE;
  }

  @Override
  public AiToolKind kind() {
    return AiToolKind.WRITE;
  }

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
   * <p>It used to be null, on the reasoning that a record which does not exist cannot be named. The
   * consequence was that {@code AiWriteAuthorisation.checkTargets} refused every call — a null
   * target is refused once a job has a closed set, and a job with no set is refused outright — so
   * «създай доставна фактура …» could not be carried out however explicitly it was asked for. See
   * {@link AiCreationTarget}: the job freezes this same reference from the route's own task, so the
   * two match and nothing else does.
   */
  @Override
  public String targetKey(JsonNode args) {
    return AiCreationTarget.ref(AiDomain.SUPPLIER_INVOICE);
  }

  /**
   * After a lost answer: is the invoice this call was creating actually in the database?
   *
   * <h2>Why a creation needs this and a fingerprint cannot give it</h2>
   *
   * There was no record before the call, so there is nothing to compare and the generic
   * reconciliation can only say "unknown" — every timed-out creation would end uncertain and the
   * user would be sent looking for a document nobody can name. The same reasoning, and the same
   * shape, as {@code CreateMoveTool.verifyAfterAmbiguousFailure}.
   *
   * <p>An identity does exist by then: {@code InvoiceCreationService} saves through the repository
   * and flushes inside its transaction, reporting the saved id through {@code AiWriteEffects}
   * before that transaction commits. So the primary key is known while only the <em>commit's</em>
   * outcome is in doubt, and asking whether that exact row is there is an existence test on a
   * server-generated key — never a search for "an invoice that looks like this one", which would
   * confirm a duplicate as readily as the real thing.
   *
   * <p>Read company-scoped and side-checked through {@code InvoiceSearchService.findScoped}, so a
   * row this company cannot see, or a customer invoice, never confirms a supplier-side write.
   */
  @Override
  public AiWriteOutcome verifyAfterAmbiguousFailure(JsonNode args, AiWriteProbe probe) {
    return InvoiceToolSupport.verifyCreated(probe, InvoiceSide.SUPPLIER);
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    InvoiceToolSupport.requireExactlyOne(args, "supplierId", "supplierCode");
    PartnerSearchService partners = Beans.get(PartnerSearchService.class);
    Partner supplier =
        InvoiceToolSupport.resolvePartner(
            args, partners, PartnerSearchService.Role.SUPPLIER, "supplierId", "supplierCode");

    InvoiceCreationService.Result created;
    try {
      created =
          Beans.get(InvoiceCreationService.class)
              .create(InvoiceSide.SUPPLIER, supplier, args.get("header"), args.get("lines"));
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
        "The invoice is a DRAFT. Validation will require the supplier's own invoice number and"
            + " origin date, so set those with edit_supplier_invoice if they were not given.");
    return out;
  }
}

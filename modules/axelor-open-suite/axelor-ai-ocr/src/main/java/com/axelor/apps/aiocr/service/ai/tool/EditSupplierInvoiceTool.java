package com.axelor.apps.aiocr.service.ai.tool;

import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccountant.tool.AiWriteMode;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceBlocked;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceEditService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceHeaderEditService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceLineEditService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceReadiness;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceReadinessService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceRecordGuard;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceSide;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceStructuredBlockers;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceViewService;
import com.axelor.apps.aiaccounting.service.invoice.PartnerSearchService;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldRegistry;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldSpec;
import com.axelor.apps.aiocr.service.ocr.BgOcrVatMapping;
import com.axelor.apps.aiocr.service.record.OcrBlocked;
import com.axelor.apps.aiocr.service.record.OcrInvoiceLineEditService;
import com.axelor.apps.aiocr.service.record.OcrRecordUpdateService;
import com.axelor.apps.aiocr.service.record.SupplierInvoiceEditService;
import com.axelor.apps.base.db.Partner;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WRITE tool: correct a whole OCR draft supplier invoice in one call.
 *
 * <p>Replaces {@code update_ocr_record}, {@code change_ocr_invoice_supplier}, {@code
 * replace_ocr_invoice_lines}, {@code edit_ocr_invoice_line}, {@code set_invoice_line_product} and
 * {@code set_invoice_line_tax}. Those six made one ordinary correction cost six tool calls and six
 * approvals; this is one call and one transaction — either the whole correction lands or none of it
 * does.
 *
 * <p>Chat-only and never unattended. All the existing guards still apply, because the work is done
 * by the same services: the record must be an {@code aiGenerated} DRAFT invoice in the caller's
 * active company, on which the caller holds write permission.
 */
@Singleton
public class EditSupplierInvoiceTool implements AiTool {

  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(EditSupplierInvoiceTool.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "edit_supplier_invoice";
  }

  @Override
  public String description() {
    return "Corrects a DRAFT supplier invoice in ONE call: supplier, header fields, a wholesale line "
        + "replacement, and any number of per-line add/update/delete operations. Address it by "
        + "uploadId (an OCR intake record) or by invoiceId (any supplier invoice of this company, "
        + "including one entered by hand) — give one, not both. Writable line fields on both paths: "
        + "description, productId, qty, unitPrice, taxCode (selects the line's tax), unitId, "
        + "discountTypeSelect/discountAmount, accountId/accountCode, fixedAssets, "
        + "fixedAssetCategoryId, cutOffStartDate/cutOffEndDate, coefficient, "
        + "analyticDistributionTemplateId, and the Bulgarian bgVatCreditType/bgIncomeTypeId/"
        + "bgIncomeTypeCode. Path differences: on uploadId, unitPrice is NET and the line's net "
        + "amount exTaxTotal may also be given; on invoiceId, unitPrice is in the invoice's own tax "
        + "mode (including tax when inAti) and exTaxTotal is refused. An unknown or path-invalid "
        + "line field refuses the whole call before anything changes. createIfMissing and "
        + "replaceLines apply to the uploadId path only, where there is a scanned document behind "
        + "the invoice. Header fields cover the payment mode, payment condition, journal, partner "
        + "account, fiscal position, dates, references and the Bulgarian NAP fields; omit what you "
        + "are not changing and send null to clear what may be empty. Put everything one request "
        + "needs into a single call: it is applied in dependency order as ONE transaction, so "
        + "either every part lands or none does. The invoice's header totals, the computed line "
        + "totals and tax rate, and the invoice tax summary are Axelor's own computation and can "
        + "never be set directly. The result always carries structuredBlockers (field-addressable, "
        + "e.g. \"lines[2].account: missing\") and readyToValidate; this never validates, "
        + "ventilates or posts anything by itself.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");

    SupplierInvoiceTarget.describeIds(props, "corrected");
    props
        .putObject("supplierId")
        .put("type", "integer")
        .put(
            "description",
            "Point the invoice at this EXISTING supplier (from find_supplier). Re-derives address,"
                + " payment mode/condition, bank details, fiscal position and due date.");
    props
        .putObject("supplierEik")
        .put("type", "string")
        .put(
            "description",
            "The supplier's ЕИК instead of supplierId, for when manage_supplier created it in this"
                + " same request and you have no id yet. Finds only; never creates.");
    props
        .putObject("createIfMissing")
        .put("type", "boolean")
        .put(
            "description",
            "True when the record has NO invoice because its ЕИК matched no supplier at intake."
                + " Builds the draft from the stored OCR result — no re-scan — then applies the"
                + " rest of this call. Needs supplierId or supplierEik. Harmless if one exists.");

    ObjectNode header = props.putObject("header");
    header.put("type", "object");
    header.put(
        "description",
        "Header fields to set. Omit any you are not changing; send null to clear one that is"
            + " allowed to be empty.");
    ObjectNode headerProps = header.putObject("properties");
    // The six fields this module owns, documented by the service that applies them — each has OCR
    // consequences (due dates, tax versions, the review status) that only it runs.
    for (Map.Entry<String, String> doc : OcrRecordUpdateService.writableFieldDocs().entrySet()) {
      ObjectNode field = headerProps.putObject(doc.getKey());
      field.put(
          "type",
          OcrRecordUpdateService.F_BANK_DETAILS_ID.equals(doc.getKey()) ? "integer" : "string");
      field.put("description", doc.getValue());
    }
    // Everything else a supplier invoice header holds — payment mode, journal, fiscal position, the
    // NAP document type — declared from the shared field specs, so this schema gains a field the
    // moment a module contributes one and cannot drift from what the services accept. The six above
    // win a name collision: their OCR-aware path is the one that must run.
    for (AiInvoiceFieldSpec spec : InvoiceHeaderEditService.fieldSpecs(InvoiceSide.SUPPLIER)) {
      if (OcrRecordUpdateService.WRITABLE_FIELDS.contains(spec.getName())) {
        continue;
      }
      ObjectNode field = headerProps.putObject(spec.getName());
      if (spec.isClearable()) {
        // A field the apply code empties on null has to say so in its type, or a validating client
        // refuses the very null the description tells the model to send.
        field.putArray("type").add(spec.getType().json()).add("null");
      } else {
        field.put("type", spec.getType().json());
      }
      String text = spec.getDescription() == null ? "" : spec.getDescription();
      if (spec.isClearable() && !text.contains("Send null to clear")) {
        text = text.isEmpty() ? "Send null to clear." : text + " Send null to clear.";
      }
      if (!text.isEmpty()) {
        field.put("description", text);
      }
      if (!spec.getAllowedValues().isEmpty()) {
        com.fasterxml.jackson.databind.node.ArrayNode allowed = field.putArray("enum");
        spec.getAllowedValues().forEach(allowed::add);
        if (spec.isClearable()) {
          allowed.addNull();
        }
      }
    }
    declareAtMostOnePerPair(header, headerProps);
    header.put("additionalProperties", false);

    ObjectNode replace = props.putObject("replaceLines");
    replace.put("type", "object");
    replace.put(
        "description",
        "Replace lines wholesale, for when the extracted line section is nonsense. Removes"
            + " replaceLineIds (or every line when omitted) and writes 'lines' instead.");
    ObjectNode replaceProps = replace.putObject("properties");
    replaceProps.set("replaceLineIds", arrayOf("integer", "Line ids to remove; omit for all."));
    replaceProps.set("lines", arrayOf(lineSpecSchema(), "The replacement lines."));
    replace.put("additionalProperties", false);

    ObjectNode lineOps = props.putObject("lineOperations");
    lineOps.put("type", "array");
    lineOps.put(
        "description",
        "Per-line changes, batched. Applied updates, then deletes, then adds, so a lineId stays"
            + " valid for the whole request. Same field meanings as replaceLines.lines.");
    ObjectNode item = (ObjectNode) lineSpecSchema();
    ObjectNode itemProps = (ObjectNode) item.path("properties");
    ObjectNode operation = itemProps.putObject("operation");
    operation.put("type", "string");
    operation.putArray("enum").add("add").add("update").add("delete");
    operation.put("description", "add | update | delete.");
    ObjectNode lineId = itemProps.putObject("lineId");
    lineId.put("type", "integer");
    lineId.put("description", "Required for update and delete.");
    item.putArray("required").add("operation");
    lineOps.set("items", item);

    props
        .putObject("reason")
        .put("type", "string")
        .put(
            "description",
            "Which words on the document each value came from. Stored on the record.");

    // Exactly one of uploadId/invoiceId, enforced in Java by SupplierInvoiceTarget.of and declared
    // here by the same class, so the four tools that take the pair all say it the same way.
    schema.putArray("required");
    SupplierInvoiceTarget.declareExactlyOneId(schema);
    schema.put("additionalProperties", false);
    return schema;
  }

  /**
   * At most one of each {@code …Id}/{@code …Code} pair the header declares.
   *
   * <p>The apply code refuses both halves of a pair — they name one reference two ways — so the
   * schema says so rather than leaving the model to find out from a rejection. {@code allOf} of
   * {@code not: {required: [both]}} makes neither of them required, which is what a header object
   * whose fields are all optional needs.
   */
  private static void declareAtMostOnePerPair(ObjectNode header, ObjectNode headerProps) {
    ArrayNode exclusions = MAPPER.createArrayNode();
    java.util.Iterator<String> names = headerProps.fieldNames();
    while (names.hasNext()) {
      String name = names.next();
      if (!name.endsWith("Id")) {
        continue;
      }
      String alternative =
          InvoiceHeaderEditService.F_BANK_DETAILS_ID.equals(name)
              ? InvoiceHeaderEditService.F_BANK_IBAN
              : name.substring(0, name.length() - 2) + "Code";
      if (headerProps.has(alternative)) {
        ArrayNode both = exclusions.addObject().putObject("not").putArray("required");
        both.add(name);
        both.add(alternative);
      }
    }
    if (exclusions.size() > 0) {
      header.set("allOf", exclusions);
    }
  }

  /**
   * The line fields, documented once and shared by replaceLines.lines and lineOperations.
   *
   * <p>Shared in Java, but the JSON is still emitted twice — once under each parent — because a
   * {@code $defs}/{@code $ref} pair is not something this schema is allowed to gamble on. The
   * Responses API dereferences {@code $ref} reliably only for a schema published with {@code
   * strict: true}, and these are published with {@code strict: null}; a reference the model does
   * not resolve would leave it guessing the argument shape of the one tool that rewrites invoice
   * lines. The duplication was therefore attacked by making each copy small — see {@code
   * OcrInvoiceLineEditService.lineFieldDocs} — rather than by referencing.
   */
  private JsonNode lineSpecSchema() {
    ObjectNode spec = MAPPER.createObjectNode();
    spec.put("type", "object");
    ObjectNode props = spec.putObject("properties");
    Map<String, String> docs = OcrInvoiceLineEditService.lineFieldDocs();
    props
        .putObject("description")
        .put("type", "string")
        .put("description", docs.get("description"));
    props.putObject("productId").put("type", "integer").put("description", docs.get("productId"));
    props.putObject("qty").put("type", "number").put("description", docs.get("qty"));
    props
        .putObject("unitPrice")
        .put("type", "number")
        .put(
            "description",
            "uploadId: "
                + docs.get("unitPrice")
                + " invoiceId: the unit price in the invoice's own tax mode — excluding tax, or"
                + " INCLUDING tax when the invoice is inAti.");
    props
        .putObject("exTaxTotal")
        .put("type", "number")
        .put(
            "description",
            "uploadId only: "
                + docs.get("exTaxTotal")
                + " Refused on invoiceId, where Axelor computes the line total.");
    ObjectNode taxCode = props.putObject("taxCode");
    taxCode.putArray("type").add("string").add("null");
    // No enum here: this one field name serves both paths, and they enforce two different rules
    // in Java. On an OCR-anchored invoice (uploadId) it is restricted server-side to the closed
    // Bulgarian purchase list named below; on a plain invoice (invoiceId) any valid tax code of
    // this company is accepted. A hard schema enum would block the second case, so the service
    // that actually applies the value is what refuses an invalid one, with a clear message either
    // way. Null clears the tax (exempt line) on the invoiceId path only — InvoiceLineEditService
    // empties taxLineSet on null; the OCR line service reads null as "leave the tax as it is".
    taxCode.put(
        "description",
        "Tax by its exact code — overrides what the product resolved; Axelor then recomputes the"
            + " line's tax rate, VAT and gross. On an OCR-anchored invoice (uploadId), restricted"
            + " to: "
            + String.join(", ", BgOcrVatMapping.purchaseTaxCodes())
            + "; null there leaves the tax unchanged. On a plain invoice (invoiceId), any valid"
            + " tax code of this company; null clears the tax (exempt line).");
    props.putObject("unitId").put("type", "integer").put("description", docs.get("unitId"));
    props
        .putObject("discountTypeSelect")
        .put("type", "integer")
        .put("description", docs.get("discountTypeSelect"));
    props
        .putObject("discountAmount")
        .put("type", "number")
        .put("description", docs.get("discountAmount"));
    props.putObject("accountId").put("type", "integer").put("description", docs.get("accountId"));
    props
        .putObject("accountCode")
        .put("type", "string")
        .put("description", docs.get("accountCode"));
    props
        .putObject("fixedAssets")
        .put("type", "boolean")
        .put("description", docs.get("fixedAssets"));
    props
        .putObject("fixedAssetCategoryId")
        .put("type", "integer")
        .put("description", docs.get("fixedAssetCategoryId"));
    props
        .putObject("cutOffStartDate")
        .put("type", "string")
        .put("description", docs.get("cutOffStartDate"));
    props
        .putObject("cutOffEndDate")
        .put("type", "string")
        .put("description", docs.get("cutOffEndDate"));
    props
        .putObject("coefficient")
        .put("type", "number")
        .put("description", docs.get("coefficient"));
    props
        .putObject("analyticDistributionTemplateId")
        .put("type", "integer")
        .put("description", docs.get("analyticDistributionTemplateId"));
    // Bulgarian VAT-credit type / ZДДФЛ income type — the same contributed field names and
    // selections the plain-invoice line editor schema offers, so this path and that one never
    // disagree about what a caller may send. Schema DECLARATION is resilient the same way
    // InvoiceLineEditService.fieldSpecs already is (no injector at schema-build time means fewer
    // optional properties advertised, not a broken tool) — contributedLineFieldSpecs() itself no
    // longer hides a genuine registry failure from any OTHER caller that needs to know about one.
    List<AiInvoiceFieldSpec> contributedSpecs;
    try {
      contributedSpecs =
          OcrInvoiceLineEditService.contributedLineFieldSpecs(
              Beans.get(AiInvoiceFieldRegistry.class));
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: contributed line fields unavailable: {}", e.getMessage());
      contributedSpecs = new ArrayList<>();
    }
    for (AiInvoiceFieldSpec contributed : contributedSpecs) {
      ObjectNode field = props.putObject(contributed.getName());
      // Same rule as the header fields above: a field its contributor empties on null has to say
      // so in its type and its enum, or a validating client refuses the very null that clears it.
      if (contributed.isClearable()) {
        field.putArray("type").add(contributed.getType().json()).add("null");
      } else {
        field.put("type", contributed.getType().json());
      }
      String text = contributed.getDescription() == null ? "" : contributed.getDescription();
      if (contributed.isClearable() && !text.contains("Send null to clear")) {
        text = text.isEmpty() ? "Send null to clear." : text + " Send null to clear.";
      }
      if (!text.isEmpty()) {
        field.put("description", text);
      }
      if (!contributed.getAllowedValues().isEmpty()) {
        com.fasterxml.jackson.databind.node.ArrayNode allowed = field.putArray("enum");
        contributed.getAllowedValues().forEach(allowed::add);
        if (contributed.isClearable()) {
          allowed.addNull();
        }
      }
    }
    spec.put("additionalProperties", false);
    return spec;
  }

  private JsonNode arrayOf(String itemType, String description) {
    ObjectNode array = MAPPER.createObjectNode();
    array.put("type", "array");
    array.put("description", description);
    array.putObject("items").put("type", itemType);
    return array;
  }

  private JsonNode arrayOf(JsonNode itemSchema, String description) {
    ObjectNode array = MAPPER.createObjectNode();
    array.put("type", "array");
    array.put("description", description);
    array.set("items", itemSchema);
    return array;
  }

  @Override
  public AiCapability capability() {
    return AiCapability.SUPPLIER_INVOICE;
  }

  @Override
  public AiToolKind kind() {
    return AiToolKind.WRITE;
  }

  /**
   * Chat only: every part of this changes an amount, a date or an identity that gets posted, so it
   * needs a person in the conversation and must never run during automatic processing.
   */
  @Override
  public Set<AiWriteMode> writeModes() {
    return EnumSet.of(AiWriteMode.CONFIRMED);
  }

  /**
   * After {@code manage_supplier} (5), because a correction may point the invoice at a supplier the
   * same request creates. Ordering inside this tool is the service's business, not the proposal's.
   */
  @Override
  public int writeOrder() {
    return 20;
  }

  /**
   * Whichever record the call names.
   *
   * <p>Overridden now that there are two ways to name one: the default implementation reads {@code
   * uploadId} and would answer null for an {@code invoiceId} call, leaving the repeat guard and the
   * workflow with no target for a write that has one. {@code Upload#946} on the OCR path —
   * including when the caller gave the invoice id of an OCR invoice, since that is the same
   * document and must fingerprint as one — and {@code Invoice#606} otherwise.
   */
  @Override
  public String targetKey(JsonNode args) {
    SupplierInvoiceTarget target = SupplierInvoiceTarget.ofOrNull(args);
    return target == null ? null : target.targetKey();
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    SupplierInvoiceTarget target = SupplierInvoiceTarget.of(args);
    String reason = AiToolArgs.optText(args, "reason");
    if (!target.isOcr()) {
      return editPlainInvoice(target.invoiceId(), args, reason);
    }
    long uploadId = target.uploadId();

    SupplierInvoiceEditService.EditRequest request = new SupplierInvoiceEditService.EditRequest();
    request.setSupplierId(AiToolArgs.optId(args, "supplierId"));
    request.setSupplierEik(AiToolArgs.optText(args, "supplierEik"));
    request.setCreateIfMissing(
        args.hasNonNull("createIfMissing") && args.get("createIfMissing").asBoolean(false));

    JsonNode header = args.get("header");
    if (header != null && header.isObject()) {
      // Split by owner: the six fields with OCR consequences go to the service that runs them, the
      // rest to the shared header service. One request, one transaction, either way.
      for (String field : OcrRecordUpdateService.WRITABLE_FIELDS) {
        if (header.has(field)) {
          JsonNode value = header.get(field);
          request.header(field, value == null || value.isNull() ? null : value.asText(""));
        }
      }
      ObjectNode extended = MAPPER.createObjectNode();
      java.util.Iterator<String> names = header.fieldNames();
      while (names.hasNext()) {
        String field = names.next();
        if (!OcrRecordUpdateService.WRITABLE_FIELDS.contains(field)) {
          extended.set(field, header.get(field));
        }
      }
      if (extended.size() > 0) {
        request.setExtendedHeader(extended);
      }
    }

    JsonNode replace = args.get("replaceLines");
    if (replace != null && replace.isObject()) {
      JsonNode lines = replace.get("lines");
      if (lines == null || !lines.isArray() || lines.size() == 0) {
        throw new AiToolException(
            "replaceLines needs at least one line in 'lines'. To remove a line without replacing"
                + " it, use lineOperations with operation=delete.");
      }
      List<Long> ids = new ArrayList<>();
      JsonNode replaceIds = replace.get("replaceLineIds");
      if (replaceIds != null && replaceIds.isArray()) {
        for (JsonNode n : replaceIds) {
          if (n != null && !n.isNull()) {
            ids.add(n.asLong());
          }
        }
      }
      List<OcrInvoiceLineEditService.LineSpec> specs = new ArrayList<>();
      for (JsonNode n : lines) {
        specs.add(OcrLineSpecs.fromJson(n));
      }
      request.replaceLines(ids, specs);
    }

    JsonNode operations = args.get("lineOperations");
    if (operations != null && operations.isArray()) {
      for (JsonNode n : operations) {
        if (n == null || !n.isObject()) {
          continue;
        }
        request.addOperation(
            new SupplierInvoiceEditService.LineOperation(
                AiToolArgs.optText(n, "operation"),
                AiToolArgs.optId(n, "lineId"),
                OcrLineSpecs.fromJson(n)));
      }
    }

    SupplierInvoiceEditService.EditResult result;
    try {
      result = Beans.get(SupplierInvoiceEditService.class).apply(uploadId, request, reason);
    } catch (OcrBlocked e) {
      // The refusal already says what kind it is; relaying only its sentence is what forced the
      // workflow to work that out again from the words.
      throw e.asToolException();
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new AiToolException(e.getMessage());
    }

    // The compact outcome, recomputed from what was saved: status, ids, what changed, line counts,
    // totals, readiness and what to refresh. Deliberately NOT the whole record — see
    // OcrRecordViewService.writeSummary for why a fat write result cost the caller its own outcome.
    Map<String, Object> out = new LinkedHashMap<>(result.getRecord());
    if (reason != null && !reason.trim().isEmpty()) {
      out.put("reason", reason.trim());
    }
    return out;
  }

  /**
   * A supplier invoice with no OCR upload behind it — one somebody entered by hand.
   *
   * <h2>Why this path exists and what it does not do</h2>
   *
   * There was no way to reach such an invoice at all: every argument this tool took named an
   * upload. So it goes to the shared invoice services, which is the whole of the work for a record
   * with no extraction to reconcile against and no review status to keep in step.
   *
   * <p>{@code createIfMissing} is refused here rather than ignored. It means "build the invoice
   * this upload implies, from what was scanned", and there is no upload and nothing scanned — a
   * caller that sent it has misunderstood which record it is working on, and silently dropping the
   * flag would let it believe an invoice was created.
   *
   * <p>{@code replaceLines} is likewise not offered: it exists because an extracted line section
   * can be nonsense in its entirety, which is a statement about OCR. Individual line operations do
   * the same work here, one line at a time, and say what they changed.
   */
  protected Object editPlainInvoice(long invoiceId, JsonNode args, String reason)
      throws AiToolException {
    if (args.hasNonNull("createIfMissing") && args.get("createIfMissing").asBoolean(false)) {
      throw new AiToolException(
          "createIfMissing only applies to an OCR upload, and invoiceId names an invoice that"
              + " already exists. To raise a new invoice, use create_supplier_invoice.");
    }
    if (args.hasNonNull("replaceLines")) {
      throw new AiToolException(
          "replaceLines only applies on the OCR path, where a whole extracted line section can be"
              + " wrong. Use lineOperations to change these lines individually.");
    }

    InvoiceEditService.EditRequest request = new InvoiceEditService.EditRequest();
    request.setHeader(args.get("header"));
    request.setReason(reason);
    if (args.hasNonNull("supplierId") || args.hasNonNull("supplierEik")) {
      request.setPartner(resolvePlainSupplier(args));
    }

    for (InvoiceEditService.LineOperation operation :
        plainLineOperations(invoiceId, args.get("lineOperations"))) {
      request.addLineOperation(operation);
    }

    InvoiceEditService.EditResult result;
    try {
      result = Beans.get(InvoiceEditService.class).apply(invoiceId, InvoiceSide.SUPPLIER, request);
    } catch (InvoiceBlocked e) {
      throw e.asToolException();
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new AiToolException(e.getMessage());
    }

    Map<String, Object> out =
        new LinkedHashMap<>(
            Beans.get(InvoiceViewService.class)
                .writeSummary(result.getInvoice(), result.getSteps()));
    InvoiceReadiness readiness =
        Beans.get(InvoiceReadinessService.class).check(result.getInvoice(), null);
    out.put("validateBlockers", readiness.getValidateBlockers());
    out.put("postBlockers", readiness.getVentilateBlockers());
    out.put("warnings", readiness.getWarnings());
    out.put("readyToValidate", readiness.isValidateReady());
    out.put("postReady", readiness.isVentilateReady());
    out.put("structuredBlockers", InvoiceStructuredBlockers.build(result.getInvoice()));
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
    if (!result.getRejected().isEmpty()) {
      out.put("rejected", result.getRejected());
    }
    if (reason != null && !reason.trim().isEmpty()) {
      out.put("reason", reason.trim());
    }
    return out;
  }

  /** Line fields this tool's schema declares that the plain path passes through unchanged. */
  static final Set<String> PLAIN_PASS_THROUGH_LINE_FIELDS =
      new java.util.LinkedHashSet<>(
          java.util.Arrays.asList(
              InvoiceLineEditService.F_DESCRIPTION,
              InvoiceLineEditService.F_PRODUCT_ID,
              InvoiceLineEditService.F_QTY,
              InvoiceLineEditService.F_TAX_CODE,
              InvoiceLineEditService.F_UNIT_ID,
              InvoiceLineEditService.F_DISCOUNT_TYPE_SELECT,
              InvoiceLineEditService.F_DISCOUNT_AMOUNT,
              InvoiceLineEditService.F_ACCOUNT_ID,
              InvoiceLineEditService.F_ACCOUNT_CODE,
              InvoiceLineEditService.F_FIXED_ASSETS,
              InvoiceLineEditService.F_FIXED_ASSET_CATEGORY_ID,
              InvoiceLineEditService.F_CUT_OFF_START_DATE,
              InvoiceLineEditService.F_CUT_OFF_END_DATE,
              InvoiceLineEditService.F_COEFFICIENT,
              InvoiceLineEditService.F_ANALYTIC_DISTRIBUTION_TEMPLATE_ID));

  /** This tool's unit price, mapped on the plain path to the price field of the invoice's mode. */
  static final String F_UNIT_PRICE = "unitPrice";

  /** Writable on the OCR path only: on a plain invoice Axelor computes the line total. */
  static final String F_EX_TAX_TOTAL = "exTaxTotal";

  /**
   * The plain path's line operations, every one of them checked before any of them is applied.
   *
   * <h2>Why this tool, and not the shared service, translates them</h2>
   *
   * This tool's line schema is shared with the OCR path, whose service takes {@code unitPrice} and
   * {@code exTaxTotal}. The shared {@code InvoiceLineEditService} takes {@code price} or {@code
   * inTaxPrice} instead, and has no line-total field at all — Axelor computes it. Passing the OCR
   * names straight through made every unit-price edit on a hand-entered invoice fail, part-way
   * through a request that may already have changed the header. So:
   *
   * <ul>
   *   <li>{@code unitPrice} becomes {@code price} on an invoice expressed excluding tax and {@code
   *       inTaxPrice} on one expressed including tax ({@code Invoice.inAti}) — the field Axelor's
   *       own {@code InvoiceLineService.computeDiscount} reads the unit price from for that mode;
   *   <li>{@code exTaxTotal}, and any field this tool does not declare, is refused, naming every
   *       one, before the invoice is touched.
   * </ul>
   */
  protected List<InvoiceEditService.LineOperation> plainLineOperations(
      long invoiceId, JsonNode operations) throws AiToolException {
    List<InvoiceEditService.LineOperation> out = new ArrayList<>();
    if (operations == null || operations.isNull()) {
      return out;
    }
    if (!operations.isArray()) {
      throw plainBadArguments("lineOperations must be an array of line operations.");
    }
    Set<String> contributed = contributedLineFieldNames();
    List<String> problems = new ArrayList<>();
    boolean needsTaxMode = false;
    int index = 0;
    for (JsonNode operation : operations) {
      String at = "lineOperations[" + index++ + "]";
      if (operation == null || !operation.isObject()) {
        problems.add(at + " must be an object");
        continue;
      }
      String verb = AiToolArgs.optText(operation, "operation");
      if (verb == null
          || !OcrInvoiceLineEditService.OPERATIONS.contains(
              verb.trim().toLowerCase(java.util.Locale.ROOT))) {
        problems.add(at + ".operation must be add, update or delete");
      }
      java.util.Iterator<String> fields = operation.fieldNames();
      while (fields.hasNext()) {
        String field = fields.next();
        if ("operation".equals(field) || "lineId".equals(field)) {
          continue;
        }
        if (F_EX_TAX_TOTAL.equals(field)) {
          problems.add(
              at
                  + ".exTaxTotal cannot be set on an invoiceId invoice — Axelor computes the line"
                  + " total from qty × unitPrice; send unitPrice instead");
        } else if (F_UNIT_PRICE.equals(field)) {
          needsTaxMode = true;
        } else if (!PLAIN_PASS_THROUGH_LINE_FIELDS.contains(field)
            && !contributed.contains(field)) {
          problems.add(at + "." + field + " is not a line field of edit_supplier_invoice");
        }
      }
    }
    if (!problems.isEmpty()) {
      throw plainBadArguments(
          "Nothing was changed. Fix these line operations and call again: "
              + String.join("; ", problems)
              + ".");
    }

    // Only now, and only when it matters: the invoice's own tax mode, read through the same
    // scoped, permission-checked guard the edit itself uses.
    String priceField = null;
    if (needsTaxMode) {
      priceField =
          invoiceInAti(invoiceId)
              ? InvoiceLineEditService.F_IN_TAX_PRICE
              : InvoiceLineEditService.F_PRICE;
    }
    for (JsonNode operation : operations) {
      // The line's fields are the operation object itself on this tool's schema, minus the two
      // that say which operation it is — the shared service takes them as their own object.
      ObjectNode line = MAPPER.createObjectNode();
      java.util.Iterator<String> fields = operation.fieldNames();
      while (fields.hasNext()) {
        String field = fields.next();
        if ("operation".equals(field) || "lineId".equals(field)) {
          continue;
        }
        line.set(F_UNIT_PRICE.equals(field) ? priceField : field, operation.get(field));
      }
      out.add(
          new InvoiceEditService.LineOperation(
              AiToolArgs.optText(operation, "operation").trim().toLowerCase(java.util.Locale.ROOT),
              AiToolArgs.optId(operation, "lineId"),
              line));
    }
    return out;
  }

  /**
   * Whether the invoice is expressed including tax. Resolved through {@link InvoiceRecordGuard}, so
   * an id outside this company, not a draft, or not readable answers exactly as the edit itself
   * would — never with anything about the record.
   */
  protected boolean invoiceInAti(long invoiceId) throws AiToolException {
    try {
      return Boolean.TRUE.equals(
          Beans.get(InvoiceRecordGuard.class)
              .resolveDraft(invoiceId, InvoiceSide.SUPPLIER)
              .getInvoice()
              .getInAti());
    } catch (InvoiceBlocked e) {
      throw e.asToolException();
    }
  }

  /**
   * Contributed line field names (BG VAT-credit/income type); a seam so a test needs no injector.
   */
  protected Set<String> contributedLineFieldNames() {
    Set<String> names = new java.util.LinkedHashSet<>();
    try {
      for (AiInvoiceFieldSpec spec :
          OcrInvoiceLineEditService.contributedLineFieldSpecs(
              Beans.get(AiInvoiceFieldRegistry.class))) {
        names.add(spec.getName());
      }
    } catch (RuntimeException e) {
      // Without the registry no contributed field can be applied either; refusing it up front is
      // the honest answer.
      LOG.debug("AI Accountant: contributed line fields unavailable: {}", e.getMessage());
    }
    return names;
  }

  private static AiToolException plainBadArguments(String message) {
    return new OcrBlocked(AiBlockerCode.BAD_ARGUMENTS, message).asToolException();
  }

  /** The supplier a plain-invoice edit is being pointed at, by id or by ЕИК. */
  protected Partner resolvePlainSupplier(JsonNode args) {
    Long supplierId = AiToolArgs.optId(args, "supplierId");
    PartnerSearchService partners = Beans.get(PartnerSearchService.class);
    if (supplierId != null) {
      Partner supplier = partners.findScoped(supplierId);
      if (supplier == null) {
        throw new AiToolException("No partner with id " + supplierId + " in this company.");
      }
      if (!partners.hasRole(supplier, PartnerSearchService.Role.SUPPLIER)) {
        throw new AiToolException(
            "Partner "
                + supplierId
                + " ("
                + supplier.getFullName()
                + ") is not marked as a supplier. Say so and ask before moving the invoice to it.");
      }
      return supplier;
    }
    String eik = AiToolArgs.requireText(args, "supplierEik");
    PartnerSearchService.Criteria criteria = new PartnerSearchService.Criteria();
    criteria.role = PartnerSearchService.Role.SUPPLIER;
    criteria.registrationCode = eik;
    criteria.limit = 2;
    PartnerSearchService.SearchResult found;
    try {
      found = partners.search(criteria);
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new AiToolException(e.getMessage());
    }
    if (found.getMatches().isEmpty()) {
      throw new AiToolException("No partner has the ЕИК '" + eik + "'.");
    }
    if (!found.isUnique()) {
      throw new AiToolException(
          "More than one partner has the ЕИК '" + eik + "'; give the supplierId instead.");
    }
    Partner supplier = found.sole();
    if (!partners.hasRole(supplier, PartnerSearchService.Role.SUPPLIER)) {
      throw new AiToolException(
          "Partner "
              + supplier.getId()
              + " ("
              + supplier.getFullName()
              + ") is not marked as a supplier. Say so and ask before moving the invoice to it.");
    }
    return supplier;
  }
}

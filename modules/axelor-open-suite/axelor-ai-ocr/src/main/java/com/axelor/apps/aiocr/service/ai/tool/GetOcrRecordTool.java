package com.axelor.apps.aiocr.service.ai.tool;

import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.ai.MissingSupplierProposals;
import com.axelor.apps.aiocr.service.record.OcrRecordViewService;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.util.Map;

/**
 * Reads one OCR record in detail. Thin adapter over {@link OcrRecordViewService}.
 *
 * <p>Raw Azure OCR JSON is opt-in ({@code includeRawOcr}) and is read from what processing already
 * stored — this tool never calls Azure Document Intelligence and never touches the source PDF.
 */
@Singleton
public class GetOcrRecordTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Ceiling on an embedded raw payload; over it a bounded descriptor is returned instead. */
  static final int MAX_RAW_BYTES = OcrRecordViewService.DEFAULT_MAX_RAW_BYTES;

  @Override
  public String name() {
    return "get_ocr_record";
  }

  @Override
  public String description() {
    return "Returns the full detail of one OCR record: upload metadata, the linked draft supplier "
        + "invoice (with its lifecycle status and label), partner, extracted accounting fields, "
        + "totals and whether they reconcile, and the structured readiness that answers 'why can it "
        + "not be validated or posted' — validateBlockers, ventilateBlockers, warnings, "
        + "readyToValidate, readyToVentilate and a field-addressable structuredBlockers list. This is "
        + "the tool for a supplier invoice that came from OCR; read those blockers rather than "
        + "guessing or re-routing to get_supplier_invoice. Set includeRawOcr=true only when you "
        + "actually need the stored Azure OCR JSON for this record — it is large and is not needed to "
        + "answer most questions.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode p = schema.putObject("properties");

    ObjectNode id = p.putObject("uploadId");
    id.put("type", "integer");
    id.put("description", "The OCR upload id, as returned by search_ocr_records.");

    ObjectNode raw = p.putObject("includeRawOcr");
    raw.put("type", "boolean");
    raw.put(
        "description",
        "When true, also return the stored Azure OCR JSON for THIS record (the per-segment result "
            + "for a split child, or the carrier's analyzeResult for a carrier). Default false.");

    schema.putArray("required").add("uploadId");
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
    long uploadId = AiToolArgs.requireId(args, "uploadId");

    AiOcrUpload upload = Beans.get(AiOcrUploadRepository.class).find(uploadId);
    if (upload == null) {
      throw new AiToolException("No OCR record with id " + uploadId + ".");
    }
    // Company isolation, checked before any field of the record is read. Same message as not-found:
    // knowing an id from another company must not confirm that the record exists.
    if (!AiCompanyScope.owns(upload.getCompany())) {
      throw new AiToolException("No OCR record with id " + uploadId + ".");
    }
    if (!Beans.get(JpaSecurity.class)
        .isPermitted(JpaSecurity.CAN_READ, AiOcrUpload.class, upload.getId())) {
      throw new AiToolException("You do not have permission to read OCR record " + uploadId + ".");
    }

    // Trusted read, stored-evidence-only: offers a following bare confirmation something specific
    // to confirm, without itself authorising anything. See MissingSupplierProposals.
    MissingSupplierProposals.recordIfMissing(upload);

    OcrRecordViewService view = Beans.get(OcrRecordViewService.class);
    Map<String, Object> out = view.detail(upload);

    if (bool(args, "includeRawOcr")) {
      out.put("rawOcr", view.rawOcr(upload, MAX_RAW_BYTES));
    }
    return out;
  }

  private static boolean bool(JsonNode args, String field) {
    if (args == null) {
      return false;
    }
    JsonNode n = args.get(field);
    if (n == null || n.isNull()) {
      return false;
    }
    return n.isBoolean() ? n.asBoolean() : "true".equalsIgnoreCase(n.asText("").trim());
  }
}

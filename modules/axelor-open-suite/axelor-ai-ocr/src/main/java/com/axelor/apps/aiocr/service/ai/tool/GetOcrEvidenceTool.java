package com.axelor.apps.aiocr.service.ai.tool;

import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.ai.MissingSupplierProposals;
import com.axelor.apps.aiocr.service.record.OcrEvidenceService;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;

/**
 * READ tool: the facts printed on the document, without the OCR payload.
 *
 * <p>Thin adapter over {@link OcrEvidenceService}. Use this instead of {@code get_ocr_record
 * includeRawOcr=true} whenever the question is "what does the document say?" — it answers from the
 * same stored extraction in a few hundred bytes rather than the whole {@code analyzeResult}.
 */
@Singleton
public class GetOcrEvidenceTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "get_ocr_evidence";
  }

  @Override
  public String description() {
    return "What the scanned document actually says, compactly: supplier name, ЕИК, VAT number, the "
        + "printed address, the extracted IBAN plus every full Bulgarian (BG) IBAN found in the text "
        + "(ibanCandidates; a foreign IBAN appears only if it was the extracted one), the BIC "
        + "(returned as 'swift' — pass it to manage_supplier as bankAccount.bic), invoice number, "
        + "dates, payment term, currency and totals. Add find=<text> to search the document text "
        + "and get the matching "
        + "lines — for anything the fields do not carry. The tool for evidence questions: prefer it "
        + "over get_ocr_record includeRawOcr=true, which returns the entire Azure OCR JSON.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode p = schema.putObject("properties");

    p.putObject("uploadId")
        .put("type", "integer")
        .put("description", "The OCR upload id whose document you want to read.");
    p.putObject("find")
        .put("type", "string")
        .put(
            "description",
            "Optional text to look for in the document text (case-insensitive). Returns the"
                + " matching lines. Search a short distinctive fragment, not a whole sentence.");
    p.putObject("includeLines")
        .put("type", "boolean")
        .put(
            "description",
            "When true, also return the line items as the document listed them (description, code,"
                + " qty, unit price, amount, VAT rate). Use it before correcting invoice lines."
                + " Default false.");

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
    String find = AiToolArgs.optText(args, "find");
    boolean includeLines =
        args.hasNonNull("includeLines")
            && (args.get("includeLines").isBoolean()
                ? args.get("includeLines").asBoolean()
                : "true".equalsIgnoreCase(args.get("includeLines").asText("").trim()));
    try {
      Object out = Beans.get(OcrEvidenceService.class).evidence(uploadId, find, includeLines);
      // Trusted read, stored-evidence-only: offers a following bare confirmation something
      // specific to confirm, without itself authorising anything. See MissingSupplierProposals.
      // Best-effort and company-scoped inside recordIfMissing itself, so a second, cheap lookup
      // here (the evidence call above already proved this company owns the upload) costs nothing
      // beyond what it saves in duplicating that scoping logic.
      MissingSupplierProposals.recordIfMissing(
          Beans.get(AiOcrUploadRepository.class).find(uploadId));
      return out;
    } catch (OcrEvidenceService.EvidenceUnavailableException e) {
      throw new AiToolException(e.getMessage());
    }
  }
}

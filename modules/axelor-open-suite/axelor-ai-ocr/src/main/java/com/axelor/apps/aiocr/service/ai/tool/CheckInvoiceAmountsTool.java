package com.axelor.apps.aiocr.service.ai.tool;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.ocr.BgOcrVatMapping;
import com.axelor.apps.aiocr.service.record.OcrRecordViewService;
import com.axelor.db.JpaSecurity;
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
 * READ tool: the arithmetic and tax verdict on one OCR draft invoice.
 *
 * <p>Exists because the numbers can be internally wrong in ways nothing else reports: the header
 * may not add up, the lines may not sum to the header base, and a line may carry no tax because its
 * VAT rate is not one the Bulgarian purchase mapping knows. None of those set {@code
 * ocrReviewStatus}, so all three can pass review and then fail at ventilation.
 *
 * <p>Diagnosis only — it changes nothing.
 */
@Singleton
public class CheckInvoiceAmountsTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Spelled out in the result because the three-way verdict is the whole point of the check, and a
   * model that treats ROUNDING as "fine" reproduces the invoice 576 failure exactly.
   */
  private static final String VERDICT_MEANING =
      "EXACT = every figure agrees to the last decimal. ROUNDING = the document and the lines differ,"
          + " but by no more than one rounding step per line; this is legitimate supplier rounding,"
          + " it cannot be repaired, and it must still be reported — say both figures and say that"
          + " the calculated one is what Axelor posts at validation. MISMATCH = a real disagreement:"
          + " a line or a header figure is wrong. NOT_CHECKED = a figure was missing. Never describe"
          + " anything other than EXACT as reconciling, and never change the header totals to make"
          + " them agree.";

  @Override
  public String name() {
    return "check_invoice_amounts";
  }

  @Override
  public String description() {
    return "Diagnoses the amounts and taxes of an OCR draft: base + VAT vs gross, lines vs base, "
        + "line VAT vs printed VAT, and for each untaxed line whether its rate maps to a Bulgarian "
        + "purchase code — with the code to use, or the special regime to explain. Answers EXACT / "
        + "ROUNDING / MISMATCH; rounding is not a match. Read-only. get_ocr_record already returns "
        + "the reconciliation, so use this when you have the uploadId but have not read the record, "
        + "or when a line needs a tax code.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode id = schema.putObject("properties").putObject("uploadId");
    id.put("type", "integer");
    id.put("description", "The OCR upload id.");
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
    // Company isolation, before any amount is read; deliberately worded as not-found.
    if (!AiCompanyScope.owns(upload.getCompany())) {
      throw new AiToolException("No OCR record with id " + uploadId + ".");
    }
    if (!Beans.get(JpaSecurity.class)
        .isPermitted(JpaSecurity.CAN_READ, AiOcrUpload.class, upload.getId())) {
      throw new AiToolException("You do not have permission to read OCR record " + uploadId + ".");
    }
    Invoice invoice = upload.getInvoice();
    if (invoice == null) {
      throw new AiToolException(
          "OCR record " + uploadId + " has no invoice, so there are no amounts to check.");
    }

    OcrRecordViewService view = Beans.get(OcrRecordViewService.class);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("uploadId", uploadId);
    out.put("invoiceId", invoice.getId());
    out.put("currency", invoice.getCurrency() == null ? null : invoice.getCurrency().getCode());
    out.put("exTaxTotal", invoice.getExTaxTotal());
    out.put("taxTotal", invoice.getTaxTotal());
    out.put("inTaxTotal", invoice.getInTaxTotal());
    Map<String, Object> reconciliation = view.amountsReconcile(invoice);
    out.put("reconciliation", reconciliation);
    out.put("verdictMeaning", VERDICT_MEANING);

    List<Map<String, Object>> problems = new ArrayList<>();
    List<InvoiceLine> lines = invoice.getInvoiceLineList();
    if (lines != null) {
      for (int i = 0; i < lines.size(); i++) {
        InvoiceLine line = lines.get(i);
        boolean noTax = line.getTaxLineSet() == null || line.getTaxLineSet().isEmpty();
        if (!noTax) {
          continue;
        }
        String mapped = BgOcrVatMapping.taxCodeForRate(line.getTaxRate());
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("index", i);
        p.put("lineId", line.getId());
        p.put("description", line.getProductName());
        p.put("taxRate", line.getTaxRate());
        p.put("exTaxTotal", line.getExTaxTotal());
        p.put("rateIsMapped", mapped != null);
        p.put("suggestedTaxCode", mapped);
        p.put(
            "reason",
            mapped == null
                ? "The VAT rate is not one of the mapped Bulgarian purchase rates (20/9/0). This is"
                    + " often a special regime — reverse charge, ВОП, import or exempt — so it must"
                    + " NOT be defaulted to 20%. Explain the options and let the accountant decide."
                : "The rate maps to "
                    + mapped
                    + " but no tax line was attached — the tax is probably missing a version"
                    + " covering the invoice date, or the code is not configured.");
        problems.add(p);
      }
    }
    out.put("linesWithoutTax", problems);
    out.put("allowedPurchaseTaxCodes", BgOcrVatMapping.purchaseTaxCodes());
    if (!problems.isEmpty()) {
      out.put(
          "nextStep",
          "For a line whose rate IS mapped, edit_supplier_invoice with a lineOperations update "
              + "carrying lineId and suggestedTaxCode fixes it. For an unmapped rate, do not guess "
              + "a code — explain the likely regime and ask.");
    }
    return out;
  }
}

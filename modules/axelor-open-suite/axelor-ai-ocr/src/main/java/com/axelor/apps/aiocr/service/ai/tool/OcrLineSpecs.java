package com.axelor.apps.aiocr.service.ai.tool;

import com.axelor.apps.aiocr.service.record.OcrInvoiceLineEditService;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Shared JSON ↔ {@link OcrInvoiceLineEditService.LineSpec} plumbing for the two line-editing tools.
 *
 * <p>Kept out of the tools themselves so both parse a line the same way. Note what it does
 * <em>not</em> do: it never fills in a default, never guesses a tax code, and never accepts a field
 * the service does not name. A missing value stays missing, and the service decides what that means
 * for the operation at hand.
 */
final class OcrLineSpecs {

  private OcrLineSpecs() {}

  /**
   * Every field this class itself reads off a line object, plus the two the tool schema puts
   * alongside the line's own fields inside a {@code lineOperations} entry ({@code operation}, its
   * {@code lineId}). Anything NOT in this set is a field some {@code AiInvoiceFieldContributor}
   * owns (the Bulgarian VAT-credit type, ZДДФЛ income type) and is collected instead of parsed here
   * — see {@link OcrInvoiceLineEditService.LineSpec#putContributedField}.
   */
  private static final Set<String> KNOWN_FIELDS =
      new HashSet<>(
          Arrays.asList(
              "description",
              "productId",
              "qty",
              "unitPrice",
              "exTaxTotal",
              "taxCode",
              "unitId",
              "discountTypeSelect",
              "discountAmount",
              "accountId",
              "accountCode",
              "fixedAssets",
              "fixedAssetCategoryId",
              "cutOffStartDate",
              "cutOffEndDate",
              "coefficient",
              "analyticDistributionTemplateId",
              "operation",
              "lineId"));

  /** Reads one line object. Amounts come through as BigDecimal, never double. */
  static OcrInvoiceLineEditService.LineSpec fromJson(JsonNode node) {
    OcrInvoiceLineEditService.LineSpec spec = new OcrInvoiceLineEditService.LineSpec();
    if (node == null || !node.isObject()) {
      return spec;
    }
    spec.setDescription(text(node, "description"));
    spec.setTaxCode(text(node, "taxCode"));
    if (node.hasNonNull("productId")) {
      spec.setProductId(node.get("productId").asLong());
    }
    spec.setQty(decimal(node, "qty"));
    spec.setUnitPrice(decimal(node, "unitPrice"));
    spec.setExTaxTotal(decimal(node, "exTaxTotal"));
    if (node.hasNonNull("unitId")) {
      spec.setUnitId(node.get("unitId").asLong());
    }
    if (node.hasNonNull("discountTypeSelect")) {
      spec.setDiscountTypeSelect(node.get("discountTypeSelect").asInt());
    }
    spec.setDiscountAmount(decimal(node, "discountAmount"));
    if (node.hasNonNull("accountId")) {
      spec.setAccountId(node.get("accountId").asLong());
    }
    spec.setAccountCode(text(node, "accountCode"));
    if (node.hasNonNull("fixedAssets")) {
      spec.setFixedAssets(node.get("fixedAssets").asBoolean());
    }
    if (node.hasNonNull("fixedAssetCategoryId")) {
      spec.setFixedAssetCategoryId(node.get("fixedAssetCategoryId").asLong());
    }
    spec.setCutOffStartDate(localDate(node, "cutOffStartDate"));
    spec.setCutOffEndDate(localDate(node, "cutOffEndDate"));
    spec.setCoefficient(decimal(node, "coefficient"));
    if (node.hasNonNull("analyticDistributionTemplateId")) {
      spec.setAnalyticDistributionTemplateId(node.get("analyticDistributionTemplateId").asLong());
    }
    Iterator<String> names = node.fieldNames();
    while (names.hasNext()) {
      String field = names.next();
      if (!KNOWN_FIELDS.contains(field)) {
        spec.putContributedField(field, node.get(field));
      }
    }
    return spec;
  }

  /**
   * Shapes an edit result for the model.
   *
   * <p>Everything reported here is recomputed from the saved record, not echoed back from the
   * request — including {@code usable}, which is why the model cannot declare its own change a
   * success.
   *
   * <p>{@code amountsReconcile.verdict} is the honest answer about the numbers, and it has three
   * values, not two. {@code EXACT} means every figure agrees. {@code ROUNDING} means the document
   * and the lines differ by no more than per-line rounding — real, unfixable, and something the
   * user must still be told, because Axelor posts the calculated figure and not the printed one.
   * {@code MISMATCH} means something is actually wrong. None of the three may be reported as
   * "reconciled".
   */
  static Map<String, Object> toResult(
      OcrInvoiceLineEditService.LineEditResult result, String reason) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("uploadId", result.getUploadId());
    out.put("invoiceId", result.getInvoiceId());
    out.put("changes", result.getAudit());
    out.put("removedLines", result.getRemoved());
    out.put("lines", result.getLines());
    out.put("amountsReconcile", result.getAmountsReconcile());
    out.put("ocrReviewStatus", result.getOcrReviewStatus());
    out.put("missingReviewFields", result.getMissingReviewFields());
    out.put("validateBlockers", result.getValidateBlockers());
    out.put("ventilateBlockers", result.getVentilateBlockers());
    out.put("warnings", result.getWarnings());
    out.put("usable", result.isUsable());
    if (reason != null && !reason.trim().isEmpty()) {
      out.put("reason", reason.trim());
    }
    String verdict = String.valueOf(result.getAmountsReconcile().get("verdict"));
    if ("MISMATCH".equals(verdict)) {
      out.put(
          "warning",
          "The amounts still do not agree beyond what rounding explains — see"
              + " amountsReconcile.differences. Tell the user plainly: either a line is still wrong,"
              + " or a header figure was misread. Do not describe the invoice as fixed.");
    } else if ("ROUNDING".equals(verdict)) {
      out.put(
          "warning",
          "The lines are now correct, but the document and the lines differ by rounding — see"
              + " amountsReconcile.roundingDifference. Say so explicitly, name both figures, and say"
              + " that the calculated one is what will be posted. Do not report an exact match and"
              + " do not try to change the header totals.");
    }
    return out;
  }

  private static LocalDate localDate(JsonNode node, String field) {
    JsonNode n = node.get(field);
    if (n == null || n.isNull()) {
      return null;
    }
    String raw = n.asText("").trim();
    if (raw.isEmpty()) {
      return null;
    }
    try {
      return LocalDate.parse(raw);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException("'" + raw + "' is not a date (field " + field + ").");
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode n = node.get(field);
    if (n == null || n.isNull()) {
      return null;
    }
    String v = n.asText("").trim();
    return v.isEmpty() ? null : v;
  }

  /**
   * A model emits amounts as JSON numbers, which Jackson would hand over as a double. Going through
   * the text form keeps 19.97 as 19.97 rather than 19.969999999999998.
   */
  private static BigDecimal decimal(JsonNode node, String field) {
    JsonNode n = node.get(field);
    if (n == null || n.isNull()) {
      return null;
    }
    if (n.isBigDecimal() || n.isInt() || n.isLong() || n.isBigInteger()) {
      return n.decimalValue();
    }
    String raw = n.asText("").trim();
    if (raw.isEmpty()) {
      return null;
    }
    try {
      return new BigDecimal(raw);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("'" + raw + "' is not a number (field " + field + ").");
    }
  }
}

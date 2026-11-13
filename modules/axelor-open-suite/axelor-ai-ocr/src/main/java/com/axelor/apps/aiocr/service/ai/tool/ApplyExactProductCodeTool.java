package com.axelor.apps.aiocr.service.ai.tool;

import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccountant.tool.AiWriteMode;
import com.axelor.apps.aiocr.service.record.OcrBlocked;
import com.axelor.apps.aiocr.service.record.OcrInvoiceLineUpdateService;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * WRITE tool: sets a line's product from a product code printed on the document.
 *
 * <p><b>Unattended-eligible</b>, and it is the split-out safe half of the product story. The
 * decision is not the model's: the server looks the code up and applies it only when exactly one
 * non-archived product carries it. Zero or several matches is a refusal, never a choice. That is
 * why this needs no human — there is no judgement in it, only a lookup.
 *
 * <p>Its counterpart, an {@code edit_supplier_invoice} line update, takes a productId the model
 * picked from search results and is chat-only. Keeping them apart is what lets one be automatic
 * without making the other automatic too.
 */
@Singleton
public class ApplyExactProductCodeTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "apply_exact_product_code";
  }

  @Override
  public String description() {
    return "Sets an invoice line's product from a product code printed on the document, but only "
        + "when exactly one product in the catalogue has that code. Also resolves the line's "
        + "purchase account. Refuses if the code is unknown or ambiguous — it never picks between "
        + "candidates. Amounts are never changed.";
  }

  /**
   * Not declared to the model, and nothing is lost by that.
   *
   * <p>No flow ever asked for it. Intake already resolves an exact {@code Product.code} on its own
   * — {@code BgOcrIntakeService.resolveLineProduct} tier 1 — before a draft exists, so by the time
   * a conversation starts every line whose printed code matches exactly is already resolved. The
   * two AI flows that touch line products reach it differently: the background repair pass
   * validates the model's suggestion and applies it in Java, and the chat sets a product through
   * {@code edit_supplier_invoice} with a {@code productId} from {@code find_product}. Neither names
   * this tool, and its own prompt list never offered it, being a WRITE.
   *
   * <p>So it was 814 bytes of description and schema on every model request for a capability that
   * was already deterministic and unreachable. The class, its guards and {@code
   * OcrInvoiceLineUpdateService.applyExactProductCode} all stay: a future unattended repair pass
   * that wants code-based resolution flips this to true and republishes.
   *
   * <p>AI-5 re-examined this and left it internal. Nothing had changed in its favour: AI-4 removed
   * the repair playbook's mention of it — the one place that offered it to a model that could not
   * call it — so today no prompt names it and no flow needs it. It is the only tool in the registry
   * that is {@link AiWriteMode#UNATTENDED}-eligible, which is exactly what the eventual unattended
   * repair pass will want, and being internal costs that pass nothing: an unpublished tool is still
   * dispatchable by name from Java. Publishing it would buy the model a choice it has never needed
   * and charge every request 814 bytes for the offer. Existing is not a reason.
   */
  @Override
  public boolean published() {
    return false;
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode p = schema.putObject("properties");

    ObjectNode uploadId = p.putObject("uploadId");
    uploadId.put("type", "integer");
    uploadId.put("description", "The OCR upload id whose draft invoice holds the line.");

    ObjectNode lineId = p.putObject("lineId");
    lineId.put("type", "integer");
    lineId.put("description", "The invoice line id, as returned by get_ocr_record.");

    ObjectNode productCode = p.putObject("productCode");
    productCode.put("type", "string");
    productCode.put(
        "description",
        "The product code exactly as printed on the document. Do not invent or normalise it.");

    schema.putArray("required").add("uploadId").add("lineId").add("productCode");
    schema.put("additionalProperties", false);
    return schema;
  }

  @Override
  public AiCapability capability() {
    return AiCapability.ACCOUNTING;
  }

  @Override
  public AiToolKind kind() {
    return AiToolKind.WRITE;
  }

  @Override
  public Set<AiWriteMode> writeModes() {
    return EnumSet.of(AiWriteMode.UNATTENDED, AiWriteMode.CONFIRMED);
  }

  /** Same rank as the other per-line product assignment: after the lines are settled. */
  @Override
  public int writeOrder() {
    return 50;
  }

  /**
   * Nothing further to gate: the service refuses anything but a unique code match, so every
   * accepted call is a deterministic lookup with exactly one answer.
   */
  @Override
  public String unattendedGuard(JsonNode args) {
    return null;
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    long uploadId = AiToolArgs.requireId(args, "uploadId");
    long lineId = AiToolArgs.requireId(args, "lineId");
    String productCode = AiToolArgs.requireText(args, "productCode");

    OcrInvoiceLineUpdateService.LineChange change;
    try {
      change =
          Beans.get(OcrInvoiceLineUpdateService.class)
              .applyExactProductCode(uploadId, lineId, productCode);
    } catch (OcrBlocked e) {
      // The refusal already says what kind it is; relaying only its sentence is what forced the
      // workflow to work that out again from the words.
      throw e.asToolException();
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new AiToolException(e.getMessage());
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("updated", !change.isEmpty());
    out.put("changes", change.getDetails());
    out.put("lineId", lineId);
    return out;
  }
}

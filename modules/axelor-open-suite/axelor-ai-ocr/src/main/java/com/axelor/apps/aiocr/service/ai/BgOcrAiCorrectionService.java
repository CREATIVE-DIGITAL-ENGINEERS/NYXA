package com.axelor.apps.aiocr.service.ai;

import com.axelor.app.AppSettings;
import com.axelor.apps.aiaccountant.dto.ChatReply;
import com.axelor.apps.aiaccountant.dto.ChatRequest;
import com.axelor.apps.aiaccountant.prompt.AiPromptResource;
import com.axelor.apps.aiaccountant.service.AiAccountantService;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccountant.tool.AiToolRegistry;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.service.ocr.BgOcrBankDetails;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractLine;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.base.db.Company;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Post-OCR correction/reconciliation. Runs AFTER all deterministic parsing and matching, and only
 * when that work left something missing, suspicious, inconsistent or unmatched.
 *
 * <p>Design rules this class enforces:
 *
 * <ul>
 *   <li>A clean extraction never reaches the model.
 *   <li>The model may only correct fields that were actually reported as problems.
 *   <li>Every suggestion is re-checked in Java against the same deterministic rules the pipeline
 *       already owns; the model's confidence alone never authorises a change.
 *   <li>Any failure returns the ORIGINAL extraction, so behaviour degrades to today's pipeline.
 *   <li>Nothing here creates a Partner, changes a status, validates or ventilates anything.
 * </ul>
 */
@Singleton
public class BgOcrAiCorrectionService {

  private static final Logger LOG = LoggerFactory.getLogger(BgOcrAiCorrectionService.class);

  /** Marker the mock client keys off, and a clear signal in logs/traces. */
  public static final String MARKER = "[NYXA-OCR-CORRECTION]";

  static final String INSTRUCTIONS_RESOURCE = "com/axelor/apps/aiocr/prompt/intake-correction.txt";

  private final ObjectMapper mapper = new ObjectMapper();

  public boolean isEnabled() {
    return "true".equalsIgnoreCase(cfg("aiaccountant.ocr.enabled", "false").trim());
  }

  protected double minConfidence() {
    try {
      return Double.parseDouble(cfg("aiaccountant.ocr.min.confidence", "0.90").trim());
    } catch (NumberFormatException e) {
      return 0.90d;
    }
  }

  protected int maxRequestBytes() {
    try {
      return Integer.parseInt(cfg("aiaccountant.ocr.max.request.bytes", "131072").trim());
    } catch (NumberFormatException e) {
      return 131_072;
    }
  }

  /**
   * @param upload the upload being processed (for id/company context); may be null in tests
   * @param result the extraction after deterministic recovery — mutated in place only for
   *     corrections that pass validation
   * @param supplierResolved whether matchSupplier already found exactly one Partner
   * @return never null; {@link BgOcrAiCorrection#none} when AI was not used or did not help
   */
  public BgOcrAiCorrection correct(
      AiOcrUpload upload, OcrExtractResult result, boolean supplierResolved) {
    if (result == null) {
      return BgOcrAiCorrection.none(null);
    }
    if (!isEnabled()) {
      return BgOcrAiCorrection.none(result);
    }
    if (result.isExtractionFailed()) {
      // Nothing to reconcile: OCR itself failed, which is not a problem AI can fix.
      return BgOcrAiCorrection.none(result);
    }

    Company company = upload != null ? upload.getCompany() : null;
    String companyEik = companyEik(company);

    Set<String> problems = BgOcrProblemFields.compute(result, supplierResolved, companyEik);
    if (problems.isEmpty()) {
      LOG.debug("AI correction skipped: extraction is clean and consistent.");
      return BgOcrAiCorrection.none(result);
    }

    try {
      String payload = buildPayload(upload, result, problems, company);
      ChatRequest request = new ChatRequest();
      request.setMessage(payload);

      ChatReply reply = Beans.get(AiAccountantService.class).chat(request);
      if (reply == null || reply.getError() != null) {
        String note = reply == null ? "no reply" : reply.getError();
        LOG.warn("AI correction not applied: {}", note);
        return BgOcrAiCorrection.failed(result, note);
      }

      JsonNode root = parseStrict(reply.getReply());
      if (root == null) {
        LOG.warn("AI correction not applied: the reply was not a usable JSON document.");
        return BgOcrAiCorrection.failed(result, "the AI reply was not valid JSON");
      }
      return applyValidated(result, root, problems, reply.getToolsUsed());
    } catch (Exception e) {
      // Never let AI break intake: fall through to the deterministic outcome.
      LOG.error("AI correction failed; keeping the deterministic extraction.", e);
      return BgOcrAiCorrection.failed(result, "AI call failed: " + e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Request
  // ---------------------------------------------------------------------------

  /**
   * Compact, purpose-built payload for exactly one invoice slice.
   *
   * <p>The full slice text is included on purpose: IBAN, bank name and payment terms routinely sit
   * on a different page from the supplier header, and a header-only excerpt is precisely how the
   * deterministic pass loses them. Only {@code rawText} is trimmed if the payload is too large, and
   * from the middle, because the head and tail carry the identity and payment blocks.
   */
  protected String buildPayload(
      AiOcrUpload upload, OcrExtractResult r, Set<String> problems, Company company) {
    ObjectNode root = mapper.createObjectNode();
    root.put("uploadId", upload != null ? upload.getId() : null);
    root.put("companyId", company != null ? company.getId() : null);

    ArrayNode probs = root.putArray("problemFields");
    problems.forEach(probs::add);

    ObjectNode ex = root.putObject("extracted");
    ex.put("supplierName", r.getSupplierName());
    ex.put("supplierEik", r.getSupplierEik());
    ex.put("supplierVat", r.getSupplierVat());
    ex.put("supplierAddress", r.getSupplierAddress());
    ex.put("supplierAddrCity", r.getSupplierAddrCity());
    ex.put("supplierAddrStreet", r.getSupplierAddrStreet());
    ex.put("supplierAddrZip", r.getSupplierAddrZip());
    ex.put("customerName", r.getCustomerName());
    ex.put("customerEik", r.getCustomerEik());
    ex.put("customerVat", r.getCustomerVat());
    ex.put("invoiceNo", r.getInvoiceNumber());
    ex.put("invoiceDate", r.getInvoiceDate() == null ? null : r.getInvoiceDate().toString());
    ex.put("dueDate", r.getDueDate() == null ? null : r.getDueDate().toString());
    ex.put("currency", r.getCurrency());
    ex.put("totalBase", r.getTotalBase());
    ex.put("totalVat", r.getTotalVat());
    ex.put("totalGross", r.getTotalGross());
    ex.put("iban", r.getIban());
    ex.put("swift", r.getSwift());
    ex.put("paymentTerm", r.getPaymentTerm());

    ArrayNode lines = root.putArray("lines");
    if (r.getLines() != null) {
      for (OcrExtractLine l : r.getLines()) {
        if (l == null) {
          continue;
        }
        ObjectNode ln = lines.addObject();
        ln.put("description", l.getDescription());
        ln.put("qty", l.getQty());
        ln.put("netAmount", l.getNetAmount());
        ln.put("vatRate", l.getVatRate());
        ln.put("vatAmount", l.getVatAmount());
      }
    }

    if (r.getFieldConfidence() != null && !r.getFieldConfidence().isEmpty()) {
      ObjectNode conf = root.putObject("fieldConfidence");
      r.getFieldConfidence().forEach(conf::put);
    }
    ArrayNode warn = root.putArray("warnings");
    if (r.getWarnings() != null) {
      r.getWarnings().forEach(warn::add);
    }
    if (r.getDiscrepancies() != null) {
      r.getDiscrepancies().forEach(warn::add);
    }

    ObjectNode ctx = root.putObject("companyContext");
    // Explicit String locals: ObjectNode.put(String, null) is ambiguous between overloads.
    String ctxEik = companyEik(company);
    String ctxName = company == null ? null : company.getName();
    ctx.put("companyEik", ctxEik);
    ctx.put("companyName", ctxName);

    // Reserve room for the instructions and structure before deciding how much text fits.
    String head = MARKER + "\n" + instructions() + "\n\nDATA:\n";
    int budget = maxRequestBytes() - head.length() - root.toString().length() - 256;
    String rawText = r.getRawText() == null ? "" : r.getRawText();
    boolean truncated = false;
    if (budget > 0 && rawText.length() > budget) {
      rawText = trimMiddle(rawText, budget);
      truncated = true;
    } else if (budget <= 0) {
      rawText = "";
      truncated = true;
    }
    root.put("rawText", rawText);
    root.put("rawTextTruncated", truncated);

    return head + root.toString();
  }

  /** Keeps the head and tail — headers carry identity, footers carry payment details. */
  protected static String trimMiddle(String text, int budget) {
    if (budget <= 40 || text.length() <= budget) {
      return text;
    }
    int half = (budget - 30) / 2;
    int cut = text.length() - budget + 30;
    return text.substring(0, half)
        + "\n…[truncated "
        + cut
        + " chars]…\n"
        + text.substring(text.length() - half);
  }

  /**
   * The read tools actually available, taken from the registry rather than written out by hand.
   *
   * <p>The hardcoded list previously named {@code lookup_bg_city}, which has never existed: the
   * model dutifully called it, got {@code unknown_tool}, and burned one of its five tool iterations
   * every time. Deriving the list means the prompt cannot drift from reality again.
   */
  protected String readToolNames() {
    try {
      // Published only: an unpublished tool is not declared on the agent, so naming it here would
      // recreate exactly the lookup_bg_city problem — a prompt promising a tool the model cannot
      // call, and one wasted iteration per attempt.
      return Beans.get(AiToolRegistry.class).published().stream()
          .filter(t -> t.kind() == AiToolKind.READ)
          .map(AiTool::name)
          .collect(Collectors.joining(", "));
    } catch (Exception e) {
      LOG.warn("Could not list read tools for the correction prompt: {}", e.getMessage());
      return "";
    }
  }

  /**
   * The intake prompt, from a resource rather than concatenated here.
   *
   * <p>It is mostly a JSON reply schema, and a JSON schema written as Java string literals is
   * escaped quotes inside escaped quotes — the one format in which a wrong brace is invisible in
   * review and shows up as a parse failure on a document weeks later. The only dynamic part is the
   * tool list, which is derived from the registry and substituted for {@code {tools}}.
   */
  protected String instructions() {
    String tools = readToolNames();
    return AiPromptResource.load(INSTRUCTIONS_RESOURCE)
        .replace("{tools}", tools.isEmpty() ? "" : " (available: " + tools + ")");
  }

  // ---------------------------------------------------------------------------
  // Response
  // ---------------------------------------------------------------------------

  /**
   * Extracts the JSON document from the reply. Tolerates a markdown fence or surrounding prose,
   * because that is a formatting slip rather than a reason to discard a good answer — but the
   * result must still parse as a JSON object.
   */
  protected JsonNode parseStrict(String reply) {
    if (reply == null || reply.trim().isEmpty()) {
      return null;
    }
    String text = reply.trim();
    int start = text.indexOf('{');
    int end = text.lastIndexOf('}');
    if (start < 0 || end <= start) {
      return null;
    }
    try {
      JsonNode node = mapper.readTree(text.substring(start, end + 1));
      return node != null && node.isObject() ? node : null;
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Applies only what survives every deterministic check. Anything else is recorded as rejected and
   * the field is left exactly as the pipeline produced it.
   */
  protected BgOcrAiCorrection applyValidated(
      OcrExtractResult r, JsonNode root, Set<String> problems, List<String> toolsUsed) {
    List<BgOcrAiCorrection.Applied> applied = new ArrayList<>();
    List<String> rejected = new ArrayList<>();
    List<String> unresolved = new ArrayList<>();

    for (JsonNode u : root.path("unresolved")) {
      unresolved.add(u.asText());
    }

    for (JsonNode c : root.path("corrections")) {
      String field = c.path("field").asText(null);
      String action = c.path("action").asText("SET");
      String suggested =
          c.path("suggestedValue").isNull() ? null : c.path("suggestedValue").asText(null);
      double confidence = c.path("confidence").asDouble(0d);
      String reason = c.path("reason").asText("");

      if (field == null || !BgOcrProblemFields.allowedFields().contains(field)) {
        rejected.add("unknown field '" + field + "'");
        continue;
      }
      if (!problems.contains(field)) {
        // The model may not rewrite a field nobody flagged.
        rejected.add(field + " (was not reported as a problem)");
        continue;
      }
      if (!"SET".equalsIgnoreCase(action)) {
        // CLEAR/NONE deliberately do nothing: emptying a field never helps a reviewer.
        rejected.add(field + " (action " + action + " is not applied)");
        continue;
      }
      if (confidence < minConfidence()) {
        rejected.add(field + " (confidence " + confidence + " < " + minConfidence() + ")");
        continue;
      }
      if (suggested == null || suggested.trim().isEmpty()) {
        rejected.add(field + " (empty suggestion)");
        continue;
      }

      String before = currentValue(r, field);
      String problem = validate(field, suggested.trim());
      if (problem != null) {
        rejected.add(field + " (" + problem + ")");
        continue;
      }
      if (!set(r, field, suggested.trim())) {
        rejected.add(field + " (could not be applied)");
        continue;
      }
      applied.add(
          new BgOcrAiCorrection.Applied(field, before, suggested.trim(), confidence, reason));
      LOG.info("AI correction applied: {} '{}' -> '{}'", field, before, suggested.trim());
    }

    return BgOcrAiCorrection.of(r, applied, rejected, unresolved, toolsUsed);
  }

  /**
   * The same deterministic rules the pipeline already trusts. Returns null when acceptable, or a
   * short reason why not.
   */
  protected String validate(String field, String value) {
    switch (field) {
      case BgOcrProblemFields.SUPPLIER_EIK:
        return value.replaceAll("\\D", "").matches("\\d{9,13}")
            ? null
            : "not a plausible EIK (9-13 digits)";
      case BgOcrProblemFields.SUPPLIER_VAT:
        return value.replaceAll("[^0-9A-Za-z]", "").matches("(?i)(BG)?\\d{9,13}")
            ? null
            : "not a plausible VAT number";
      case BgOcrProblemFields.IBAN:
        // Stricter than the pipeline's structural check on purpose: a hallucinated IBAN is
        // structurally plausible by construction, so the mod-97 arithmetic has to be the gate.
        return BgOcrProblemFields.isIbanChecksumValid(value) ? null : "IBAN checksum failed";
      case BgOcrProblemFields.SWIFT:
        return value.trim().matches("(?i)[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?")
            ? null
            : "not a plausible BIC/SWIFT";
      case BgOcrProblemFields.INVOICE_DATE:
        try {
          LocalDate d = LocalDate.parse(value.trim());
          return d.isAfter(LocalDate.now()) ? "date is in the future" : null;
        } catch (DateTimeParseException e) {
          return "not an ISO date (yyyy-MM-dd)";
        }
      case BgOcrProblemFields.CURRENCY:
        return value.trim().matches("[A-Za-z]{3}") ? null : "not a 3-letter currency code";
      case BgOcrProblemFields.TOTAL_BASE:
      case BgOcrProblemFields.TOTAL_VAT:
      case BgOcrProblemFields.TOTAL_GROSS:
        try {
          BigDecimal v = new BigDecimal(value.trim().replace(",", "."));
          return v.signum() < 0 ? "negative amount" : null;
        } catch (NumberFormatException e) {
          return "not a number";
        }
      case BgOcrProblemFields.SUPPLIER_NAME:
      case BgOcrProblemFields.SUPPLIER_ADDRESS:
      case BgOcrProblemFields.INVOICE_NO:
        return value.trim().length() <= 255 ? null : "too long";
      default:
        return "field is not correctable";
    }
  }

  protected String currentValue(OcrExtractResult r, String field) {
    switch (field) {
      case BgOcrProblemFields.SUPPLIER_EIK:
        return r.getSupplierEik();
      case BgOcrProblemFields.SUPPLIER_VAT:
        return r.getSupplierVat();
      case BgOcrProblemFields.SUPPLIER_NAME:
        return r.getSupplierName();
      case BgOcrProblemFields.SUPPLIER_ADDRESS:
        return r.getSupplierAddress();
      case BgOcrProblemFields.INVOICE_NO:
        return r.getInvoiceNumber();
      case BgOcrProblemFields.INVOICE_DATE:
        return r.getInvoiceDate() == null ? null : r.getInvoiceDate().toString();
      case BgOcrProblemFields.CURRENCY:
        return r.getCurrency();
      case BgOcrProblemFields.TOTAL_BASE:
        return str(r.getTotalBase());
      case BgOcrProblemFields.TOTAL_VAT:
        return str(r.getTotalVat());
      case BgOcrProblemFields.TOTAL_GROSS:
        return str(r.getTotalGross());
      case BgOcrProblemFields.IBAN:
        return r.getIban();
      case BgOcrProblemFields.SWIFT:
        return r.getSwift();
      default:
        return null;
    }
  }

  /** Writes the validated value onto the extraction. Returns false if the field is not writable. */
  protected boolean set(OcrExtractResult r, String field, String value) {
    switch (field) {
      case BgOcrProblemFields.SUPPLIER_EIK:
        r.setSupplierEik(value);
        return true;
      case BgOcrProblemFields.SUPPLIER_VAT:
        r.setSupplierVat(value);
        return true;
      case BgOcrProblemFields.SUPPLIER_NAME:
        r.setSupplierName(value);
        return true;
      case BgOcrProblemFields.SUPPLIER_ADDRESS:
        r.setSupplierAddress(value);
        return true;
      case BgOcrProblemFields.INVOICE_NO:
        r.setInvoiceNumber(value);
        return true;
      case BgOcrProblemFields.INVOICE_DATE:
        r.setInvoiceDate(LocalDate.parse(value.trim()));
        return true;
      case BgOcrProblemFields.CURRENCY:
        r.setCurrency(value.trim().toUpperCase());
        return true;
      case BgOcrProblemFields.TOTAL_BASE:
        r.setTotalBase(new BigDecimal(value.trim().replace(",", ".")));
        return true;
      case BgOcrProblemFields.TOTAL_VAT:
        r.setTotalVat(new BigDecimal(value.trim().replace(",", ".")));
        return true;
      case BgOcrProblemFields.TOTAL_GROSS:
        r.setTotalGross(new BigDecimal(value.trim().replace(",", ".")));
        return true;
      case BgOcrProblemFields.IBAN:
        r.setIban(BgOcrBankDetails.normalizeIban(value));
        return true;
      case BgOcrProblemFields.SWIFT:
        r.setSwift(value.trim().toUpperCase());
        return true;
      default:
        return false;
    }
  }

  /** A Company's own EIK lives on its Partner, matching how the intake reads it elsewhere. */
  protected static String companyEik(Company company) {
    if (company == null || company.getPartner() == null) {
      return null;
    }
    return company.getPartner().getRegistrationCode();
  }

  private static String str(BigDecimal v) {
    return v == null ? null : v.toPlainString();
  }

  protected String cfg(String key, String def) {
    String v = AppSettings.get().get(key, def);
    return v == null ? def : v;
  }
}

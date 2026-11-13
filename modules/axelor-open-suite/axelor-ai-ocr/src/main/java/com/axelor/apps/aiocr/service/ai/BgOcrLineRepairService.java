package com.axelor.apps.aiocr.service.ai;

import com.axelor.app.AppSettings;
import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.service.AccountManagementAccountService;
import com.axelor.apps.aiaccountant.dto.ChatReply;
import com.axelor.apps.aiaccountant.dto.ChatRequest;
import com.axelor.apps.aiaccountant.prompt.AiPromptResource;
import com.axelor.apps.aiaccountant.service.AiAccountantService;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.aiocr.service.readiness.OcrInvoiceReadiness;
import com.axelor.apps.aiocr.service.readiness.OcrSupplierInvoiceReadinessService.LineIssue;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Second AI pass, after the draft exists: repairs invoice lines that have no product/account.
 *
 * <p>Why a second pass. The pre-draft pass ({@link BgOcrAiCorrectionService}) works on the
 * extraction, whose vocabulary is entirely header and identity — at that point no invoice lines
 * exist yet, so it structurally cannot address the line problems that stop an invoice being
 * ventilated. This one runs on the saved draft and is driven by the readiness report's per-line
 * issues rather than by anything the model asked for.
 *
 * <p>What it may change: a line's {@code product} and, from that product, its purchase {@code
 * account}. Nothing else. In particular:
 *
 * <ul>
 *   <li><b>Never an amount.</b> Quantity, price, {@code exTaxTotal} and {@code inTaxTotal} are what
 *       the supplier printed and are not the model's business.
 *   <li><b>Never a tax.</b> An unmapped VAT rate is usually a real special regime, so guessing a
 *       code would misstate VAT rather than fix it. Those lines are reported, and a person decides
 *       through {@code set_invoice_line_tax}.
 * </ul>
 *
 * <p>The model returns product ids only, and each one is re-validated in Java: the product must
 * exist, not be archived, and yield a purchase account for <em>this</em> company. Its confidence is
 * a necessary condition, never a sufficient one.
 *
 * <p>Applies changes directly in Java from the reply, not through WRITE tools. That keeps this pass
 * independent of the unattended tool allowlist, which stays a separate, narrower decision.
 */
@Singleton
public class BgOcrLineRepairService {

  private static final Logger LOG = LoggerFactory.getLogger(BgOcrLineRepairService.class);

  /** Marker for logs and for the mock transport to key off. */
  public static final String MARKER = "[NYXA-LINE-REPAIR]";

  static final String INSTRUCTIONS_RESOURCE = "com/axelor/apps/aiocr/prompt/line-repair.txt";

  private final ObjectMapper mapper = new ObjectMapper();

  /**
   * Repairs what it can and returns the audit trail. Never throws; on any problem the carrier is
   * empty and the draft is left exactly as the deterministic pipeline built it.
   */
  public BgOcrAiCorrection repair(
      Invoice invoice, OcrExtractResult result, OcrInvoiceReadiness readiness) {
    if (!isEnabled()) {
      return BgOcrAiCorrection.none(result);
    }
    List<LineIssue> repairable = repairable(readiness);
    if (repairable.isEmpty()) {
      return BgOcrAiCorrection.none(result);
    }
    try {
      ChatRequest request = new ChatRequest();
      request.setMessage(MARKER + "\n" + buildPayload(invoice, repairable).toString());
      ChatReply reply = Beans.get(AiAccountantService.class).chat(request);
      if (reply == null || reply.getError() != null) {
        return BgOcrAiCorrection.failed(
            result, "Line repair unavailable: " + (reply == null ? "no reply" : reply.getError()));
      }
      JsonNode parsed = parseStrict(reply.getReply());
      if (parsed == null) {
        return BgOcrAiCorrection.failed(result, "Line repair returned no usable JSON.");
      }
      return apply(invoice, result, repairable, parsed, reply.getToolsUsed());
    } catch (Exception e) {
      LOG.error("Line repair failed; leaving the draft as built.", e);
      return BgOcrAiCorrection.failed(result, "Line repair failed: " + e.getMessage());
    }
  }

  /**
   * Only lines missing a product or account are worth asking about. A line missing just a tax is
   * excluded on purpose — see the class javadoc.
   */
  protected List<LineIssue> repairable(OcrInvoiceReadiness readiness) {
    List<LineIssue> out = new ArrayList<>();
    if (readiness == null) {
      return out;
    }
    for (LineIssue issue : readiness.getLineIssues()) {
      if (issue.isMissingProduct() || issue.isMissingAccount()) {
        out.add(issue);
      }
    }
    return out;
  }

  protected ObjectNode buildPayload(Invoice invoice, List<LineIssue> issues) {
    ObjectNode root = mapper.createObjectNode();
    root.put("invoiceId", invoice.getId());
    root.put("companyId", invoice.getCompany() == null ? null : invoice.getCompany().getId());
    root.put("supplier", invoice.getPartner() == null ? null : invoice.getPartner().getName());
    root.put("currency", invoice.getCurrency() == null ? null : invoice.getCurrency().getCode());

    ArrayNode lines = root.putArray("linesNeedingProduct");
    List<InvoiceLine> all = invoice.getInvoiceLineList();
    for (LineIssue issue : issues) {
      ObjectNode l = lines.addObject();
      l.put("index", issue.getIndex());
      l.put("lineId", issue.getLineId());
      l.put("description", issue.getDescription());
      l.put("missingProduct", issue.isMissingProduct());
      l.put("missingAccount", issue.isMissingAccount());
      if (all != null && issue.getIndex() < all.size()) {
        InvoiceLine line = all.get(issue.getIndex());
        l.put("qty", line.getQty());
        l.put("exTaxTotal", line.getExTaxTotal());
      }
    }
    root.put("instructions", instructions());
    return root;
  }

  /**
   * The repair prompt, from a resource for the same reason as the intake one: it is mostly a JSON
   * reply schema, and a schema built from escaped Java string literals is the format in which a
   * wrong brace survives review and fails on a document weeks later.
   */
  protected String instructions() {
    return AiPromptResource.load(INSTRUCTIONS_RESOURCE);
  }

  /** Validates every suggestion in Java, then applies the ones that survive. */
  protected BgOcrAiCorrection apply(
      Invoice invoice,
      OcrExtractResult result,
      List<LineIssue> issues,
      JsonNode reply,
      List<String> toolsUsed) {

    List<BgOcrAiCorrection.Applied> applied = new ArrayList<>();
    List<String> rejected = new ArrayList<>();
    List<String> unresolved = new ArrayList<>();

    for (JsonNode node : reply.path("unresolved")) {
      unresolved.add(node.asText(""));
    }

    double minConfidence = minConfidence();
    List<InvoiceLine> lines = invoice.getInvoiceLineList();

    for (JsonNode node : reply.path("repairs")) {
      long lineId = node.path("lineId").asLong(0L);
      long productId = node.path("productId").asLong(0L);
      double confidence = node.path("confidence").asDouble(0d);
      String reason = node.path("reason").asText("");

      LineIssue issue = findIssue(issues, lineId);
      if (issue == null) {
        // The model may only touch lines that were actually reported as broken.
        rejected.add("line " + lineId + " (not one of the lines reported as needing a product)");
        continue;
      }
      if (confidence < minConfidence) {
        rejected.add("line " + lineId + " (confidence " + confidence + " < " + minConfidence + ")");
        continue;
      }
      InvoiceLine line = lineById(lines, lineId);
      if (line == null) {
        rejected.add("line " + lineId + " (no longer on the invoice)");
        continue;
      }
      Product product = productId <= 0 ? null : Beans.get(ProductRepository.class).find(productId);
      if (product == null) {
        rejected.add("line " + lineId + " (product " + productId + " does not exist)");
        continue;
      }
      if (Boolean.TRUE.equals(product.getArchived())) {
        rejected.add("line " + lineId + " (product " + productId + " is archived)");
        continue;
      }
      Account account = purchaseAccount(product, invoice);
      if (account == null) {
        rejected.add(
            "line "
                + lineId
                + " (product "
                + describe(product)
                + " has no purchase account for this company, so it would not fix the line)");
        continue;
      }
      Company company = invoice.getCompany();
      if (account.getCompany() != null
          && company != null
          && !account.getCompany().getId().equals(company.getId())) {
        rejected.add("line " + lineId + " (resolved account belongs to another company)");
        continue;
      }

      String before = line.getProduct() == null ? null : describe(line.getProduct());
      line.setProduct(product);
      line.setAccount(account);
      if (line.getUnit() == null && product.getUnit() != null) {
        line.setUnit(product.getUnit());
      }
      applied.add(
          new BgOcrAiCorrection.Applied(
              "line[" + issue.getIndex() + "].product",
              before,
              describe(product) + " → account " + account.getCode(),
              confidence,
              reason));
    }

    if (applied.isEmpty() && rejected.isEmpty() && unresolved.isEmpty()) {
      return BgOcrAiCorrection.none(result);
    }
    return BgOcrAiCorrection.of(result, applied, rejected, unresolved, toolsUsed);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private Account purchaseAccount(Product product, Invoice invoice) {
    try {
      return Beans.get(AccountManagementAccountService.class)
          .getProductAccount(
              product, invoice.getCompany(), invoice.getFiscalPosition(), true, false);
    } catch (Exception e) {
      return null;
    }
  }

  private static LineIssue findIssue(List<LineIssue> issues, long lineId) {
    for (LineIssue issue : issues) {
      if (issue.getLineId() != null && issue.getLineId() == lineId) {
        return issue;
      }
    }
    return null;
  }

  private static InvoiceLine lineById(List<InvoiceLine> lines, long lineId) {
    if (lines == null) {
      return null;
    }
    for (InvoiceLine line : lines) {
      if (line.getId() != null && line.getId() == lineId) {
        return line;
      }
    }
    return null;
  }

  private static String describe(Product product) {
    return product.getCode() == null ? product.getName() : product.getCode();
  }

  /** Strict parse: one JSON object, tolerating a markdown fence but nothing else. */
  protected JsonNode parseStrict(String text) {
    if (text == null || text.trim().isEmpty()) {
      return null;
    }
    String body = text.trim();
    int start = body.indexOf('{');
    int end = body.lastIndexOf('}');
    if (start < 0 || end <= start) {
      return null;
    }
    try {
      JsonNode node = mapper.readTree(body.substring(start, end + 1));
      return node != null && node.isObject() ? node : null;
    } catch (Exception e) {
      return null;
    }
  }

  protected boolean isEnabled() {
    return "true".equalsIgnoreCase(cfg("aiaccountant.ocr.enabled", "false").trim())
        && "true".equalsIgnoreCase(cfg("aiaccountant.ocr.repair.enabled", "false").trim());
  }

  protected double minConfidence() {
    try {
      return Double.parseDouble(cfg("aiaccountant.ocr.repair.min.confidence", "0.90").trim());
    } catch (NumberFormatException e) {
      return 0.90d;
    }
  }

  protected String cfg(String key, String def) {
    String v = AppSettings.get().get(key, def);
    return v == null ? def : v;
  }
}

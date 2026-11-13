package com.axelor.apps.aiocr.service.ai.tool;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.service.AccountManagementAccountService;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiReadGuard;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.db.repo.ProductRepository;
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
 * READ tool: finds products by code or name, so a line with no product can be given one.
 *
 * <p>Reports {@code hasPurchaseAccount} per candidate. That matters more than the name match: a
 * product with no purchase account configured for the company would leave the line exactly as
 * unventilatable as it is now, so the caller needs to know before choosing.
 *
 * <h2>Why the catalogue itself is not filtered by company</h2>
 *
 * Because in Axelor it genuinely is not company-owned. {@code Product} has no company; the
 * per-company parts live in separate rows — {@code ProductCompany} for field overrides and {@code
 * AccountManagement} for accounts. Inventing an ownership rule here would be modelling something
 * the ERP does not model, and would hide products the accountant can legitimately use.
 *
 * <p>What IS scoped is the company the accounts are resolved for. It comes from {@link
 * AiCompanyScope} rather than from an argument, so {@code hasPurchaseAccount} always answers "for
 * the company you are working in" — and the model cannot use this tool to probe another company's
 * account configuration.
 */
@Singleton
public class FindProductTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  static final int DEFAULT_LIMIT = 10;
  static final int MAX_LIMIT = 25;

  @Override
  public String name() {
    return "find_product";
  }

  @Override
  public String description() {
    return "Searches the product catalogue by code or name fragment, for a line that needs a "
        + "specific catalogue product. hasPurchaseAccount is evaluated for the active company; "
        + "prefer a candidate where it is true, as one without it would not fix the line.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode p = schema.putObject("properties");

    ObjectNode query = p.putObject("query");
    query.put("type", "string");
    query.put("description", "Code or name fragment to search for (case-insensitive).");

    // No companyId parameter: accounts are always resolved for the active company.

    ObjectNode limit = p.putObject("limit");
    limit.put("type", "integer");
    limit.put("description", "Maximum candidates (default 10, max 25).");

    schema.putArray("required").add("query");
    schema.put("additionalProperties", false);
    return schema;
  }

  @Override
  public AiCapability capability() {
    return AiCapability.ACCOUNTING;
  }

  @Override
  public AiToolKind kind() {
    return AiToolKind.READ;
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    // Listing products needs read access to Product at all, as for every list-style read.
    // Products carry no company here, so there is no company filter to add.
    AiReadGuard.requireRead(Product.class, "products");
    String query = AiToolArgs.requireText(args, "query");
    if (query.trim().length() < 2) {
      throw new AiToolException("Give at least two characters to search for.");
    }
    int limit = AiToolArgs.limit(args, "limit", DEFAULT_LIMIT, MAX_LIMIT);

    String like = "%" + query.trim().toLowerCase() + "%";
    List<Product> matches =
        Beans.get(ProductRepository.class)
            .all()
            .filter(
                "(LOWER(self.code) LIKE :q OR LOWER(self.name) LIKE :q)"
                    + " AND (self.archived IS NULL OR self.archived = FALSE)")
            .bind("q", like)
            .order("code")
            .fetch(limit + 1);

    boolean truncated = matches.size() > limit;
    Company company = Beans.get(CompanyRepository.class).find(AiCompanyScope.requireCompanyId());

    List<Map<String, Object>> items = new ArrayList<>();
    for (int i = 0; i < matches.size() && i < limit; i++) {
      Product product = matches.get(i);
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("productId", product.getId());
      m.put("code", product.getCode());
      m.put("name", product.getName());
      m.put("hasPurchaseAccount", company == null ? null : hasPurchaseAccount(product, company));
      items.add(m);
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("returned", items.size());
    out.put("truncated", truncated);
    out.put("products", items);
    if (items.isEmpty()) {
      out.put(
          "hint",
          "No product matched. Do not repeat the same query — try a shorter fragment, or tell the "
              + "user the catalogue has nothing suitable so they can create the product.");
    } else {
      out.put(
          "nextStep",
          "To attach one, call edit_supplier_invoice with a lineOperations update carrying the "
              + "lineId and this productId. A line with no suitable catalogue product needs none: "
              + "omit productId and the company's generic OCR purchase product is used.");
    }
    return out;
  }

  /** Defensive: an unconfigured AccountManagement throws rather than returning null. */
  private boolean hasPurchaseAccount(Product product, Company company) {
    try {
      Account account =
          Beans.get(AccountManagementAccountService.class)
              .getProductAccount(product, company, null, true, false);
      return account != null;
    } catch (Exception e) {
      return false;
    }
  }
}

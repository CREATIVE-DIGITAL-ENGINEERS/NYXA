package com.axelor.apps.aiaccounting.tool;

import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiReadGuard;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccounting.service.invoice.PartnerSearchService;
import com.axelor.apps.base.db.Partner;
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
 * READ tool: find the customer an invoice should be made out to.
 *
 * <p>The counterpart of {@code find_supplier}, on the same {@code Partner} table and with the same
 * rules — one match identifies somebody, several identify nobody, and a partner that exists without
 * the customer flag is reported rather than hidden, so nobody creates a duplicate of a company that
 * is already there.
 */
@Singleton
public class FindCustomerTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "find_customer";
  }

  @Override
  public String description() {
    return "Finds a customer to invoice. Combine customerId, eik, vatNumber, name and iban — they "
        + "are ANDed, so eik+name confirms identity in one call. A single match returns the full "
        + "partner; only exactly one match identifies a customer. Also returns partners that are "
        + "NOT yet marked as customers — say so rather than creating a duplicate. eik and a BG "
        + "vatNumber are the same key and match either stored field. Archived partners and any "
        + "company's own identity are never returned. Prefer the ЕИК over the name: two companies "
        + "share a name more often than an identifier.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props.putObject("customerId").put("type", "integer").put("description", "Known Nyxa id.");
    props
        .putObject("eik")
        .put("type", "string")
        .put("description", "Bulgarian ЕИК/БУЛСТАТ, digits or BG-prefixed.");
    props
        .putObject("vatNumber")
        .put("type", "string")
        .put("description", "VAT number; a BG one is BG + ЕИК.");
    props
        .putObject("name")
        .put("type", "string")
        .put("description", "Partial company name, min 3 chars. Never proof of identity alone.");
    props.putObject("iban").put("type", "string").put("description", "IBAN, to find its owner.");
    props
        .putObject("limit")
        .put("type", "integer")
        .put("description", "Max rows (default 10, max 20).");
    schema.putArray("required");
    schema.put("additionalProperties", false);
    return schema;
  }

  @Override
  public AiCapability capability() {
    // The partner area, not the invoice one: this is a lookup on master data that the supplier side
    // reaches through find_supplier for exactly the same reason.
    return AiCapability.PARTNER;
  }

  @Override
  public AiToolKind kind() {
    return AiToolKind.READ;
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    AiReadGuard.requireRead(com.axelor.apps.base.db.Partner.class, "partners");
    PartnerSearchService service = Beans.get(PartnerSearchService.class);
    PartnerSearchService.Criteria criteria = new PartnerSearchService.Criteria();
    criteria.role = PartnerSearchService.Role.CUSTOMER;
    criteria.partnerId = AiToolArgs.optId(args, "customerId");
    criteria.registrationCode = AiToolArgs.optText(args, "eik");
    criteria.taxNumber = AiToolArgs.optText(args, "vatNumber");
    criteria.name = AiToolArgs.optText(args, "name");
    criteria.iban = AiToolArgs.optText(args, "iban");
    criteria.limit =
        AiToolArgs.limit(
            args, "limit", PartnerSearchService.DEFAULT_LIMIT, PartnerSearchService.MAX_MATCHES);

    PartnerSearchService.SearchResult found;
    try {
      found = service.search(criteria);
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new AiToolException(e.getMessage());
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("searchedOn", found.getApplied());
    out.put("matchCount", found.getMatches().size());
    out.put("truncated", found.isTruncated());
    out.put("identifiesOneCustomer", found.isUnique());

    if (found.isUnique()) {
      Partner sole = found.sole();
      out.put("customer", detail(sole, service));
      if (!service.hasRole(sole, PartnerSearchService.Role.CUSTOMER)) {
        out.put(
            "action",
            "This partner exists but is not marked as a customer. Say so and ask whether to use it"
                + " anyway — do NOT create a second partner for the same company.");
      }
    } else {
      List<Map<String, Object>> rows = new ArrayList<>();
      for (Partner partner : found.getMatches()) {
        rows.add(row(partner, service));
      }
      out.put("customers", rows);
      out.put(
          "action",
          found.getMatches().isEmpty()
              ? "No partner matches. Ask the user for the ЕИК or the exact name before assuming the"
                  + " customer does not exist; creating partners is not something these tools do."
              : "Several partners match, so none is identified. Show them and ask which one, or"
                  + " narrow the search with the ЕИК.");
    }
    return out;
  }

  private static Map<String, Object> row(Partner partner, PartnerSearchService service) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("customerId", partner.getId());
    out.put("name", partner.getFullName());
    // The schema's own names, as find_supplier reports them: eik is Partner.registrationCode and
    // vatNumber is Partner.taxNbr.
    out.put("eik", partner.getRegistrationCode());
    out.put("vatNumber", partner.getTaxNbr());
    out.put("isCustomer", service.hasRole(partner, PartnerSearchService.Role.CUSTOMER));
    return out;
  }

  private static Map<String, Object> detail(Partner partner, PartnerSearchService service) {
    Map<String, Object> out = new LinkedHashMap<>(row(partner, service));
    out.put("isSupplier", service.hasRole(partner, PartnerSearchService.Role.SUPPLIER));
    out.put("currency", partner.getCurrency() == null ? null : partner.getCurrency().getCode());
    out.put(
        "paymentCondition",
        partner.getPaymentCondition() == null ? null : partner.getPaymentCondition().getCode());
    out.put(
        "inPaymentMode",
        partner.getInPaymentMode() == null ? null : partner.getInPaymentMode().getCode());
    List<Map<String, Object>> banks = new ArrayList<>();
    if (partner.getBankDetailsList() != null) {
      partner
          .getBankDetailsList()
          .forEach(
              bank -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("bankDetailsId", bank.getId());
                row.put("iban", bank.getIban());
                row.put("active", bank.getActive());
                row.put("isDefault", bank.getIsDefault());
                banks.add(row);
              });
    }
    out.put("bankAccounts", banks);
    return out;
  }
}

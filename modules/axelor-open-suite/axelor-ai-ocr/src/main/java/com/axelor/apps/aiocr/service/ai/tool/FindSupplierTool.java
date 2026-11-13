package com.axelor.apps.aiocr.service.ai.tool;

import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiReadGuard;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiocr.service.supplier.SupplierSearchService;
import com.axelor.apps.aiocr.service.supplier.SupplierView;
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
 * READ tool: find a supplier by any combination of identity, name and bank account.
 *
 * <p>Replaces {@code find_partner_by_eik}, {@code find_partner_by_name}, {@code
 * find_partner_by_iban} and {@code get_partner_bank_accounts}. Those four cost two to four round
 * trips to answer one ordinary question — "is this supplier in Nyxa, and is the IBAN on the invoice
 * theirs?" — and each of them returned a different slice of the same partner. Here the filters
 * combine in one query, and a lookup that resolves to exactly one supplier returns the full record
 * straight away, so the follow-up call is not needed.
 */
@Singleton
public class FindSupplierTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "find_supplier";
  }

  @Override
  public String description() {
    return "Finds a supplier ACCESSIBLE IN THE ACTIVE COMPANY ONLY — companies are isolated, and "
        + "this never searches, lists or compares across companies. Combine supplierId, eik, "
        + "vatNumber, name and iban — they are ANDed, so eik+name confirms identity in one call. A "
        + "single match returns the full supplier (addresses, bank accounts, payment and accounting "
        + "settings, contacts), so no second lookup is needed. Only exactly one match in this "
        + "company identifies a supplier — never say 'exactly one in the system' or similar, only "
        + "'in this company'. Also returns partners that are NOT yet marked as suppliers — mark "
        + "those with manage_supplier rather than creating a duplicate. Never returns this company's "
        + "own identity (its Company.partner). Zero matches means only that nothing in THIS company "
        + "matches ALL the filters given; it says nothing about other companies, which may hold "
        + "their own separate supplier for the same ЕИК and are never a reason to refuse this "
        + "company's own. Before creating after a zero-match search that combined the ЕИК with "
        + "name/iban, search the ЕИК alone.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props.putObject("supplierId").put("type", "integer").put("description", "Known Nyxa id.");
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
    return AiCapability.PARTNER;
  }

  @Override
  public AiToolKind kind() {
    return AiToolKind.READ;
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    // Same G9 rule find_customer applies: listing partners needs read access to Partner at all.
    requireReadPermission();
    SupplierSearchService.Criteria criteria =
        new SupplierSearchService.Criteria()
            .setSupplierId(AiToolArgs.optId(args, "supplierId"))
            .setEik(AiToolArgs.optText(args, "eik"))
            .setVatNumber(AiToolArgs.optText(args, "vatNumber"))
            .setName(AiToolArgs.optText(args, "name"))
            .setIban(AiToolArgs.optText(args, "iban"))
            .setLimit(AiToolArgs.limit(args, "limit", 10, SupplierView.MAX_MATCHES));

    SupplierSearchService.SearchResult found;
    try {
      found = searchService().search(criteria);
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new AiToolException(e.getMessage());
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("searchedOn", found.getApplied());
    out.put("matchCount", found.getMatches().size());
    out.put("truncated", found.isTruncated());
    out.put("identifiesOneSupplier", found.isUnique());

    if (found.isUnique()) {
      // The whole point of merging the old tools: one call, one complete answer.
      Partner sole = found.sole();
      out.put("supplier", SupplierView.detail(sole));
      boolean shared = found.isSharedBeyondActiveCompany(sole);
      out.put("editableHere", !shared);
      if (shared) {
        // manage_supplier refuses to change a partner that is not this company's alone, so it must
        // not be offered as the next step.
        out.put(
            "action",
            "This partner is shared master data, not this company's alone, so manage_supplier will"
                + " not change it"
                + (Boolean.TRUE.equals(sole.getIsSupplier())
                    ? ""
                    : " (not even to mark it as a supplier)")
                + ". It can be used as it is; anything else is an accountant's decision in Nyxa.");
      } else if (!Boolean.TRUE.equals(sole.getIsSupplier())) {
        out.put(
            "action",
            "This partner exists but is not marked as a supplier. Use manage_supplier with"
                + " operation=update and this supplierId to mark it — do NOT create a new one.");
      }
    } else {
      List<Map<String, Object>> rows = new ArrayList<>();
      for (Partner p : found.getMatches()) {
        rows.add(SupplierView.row(p));
      }
      out.put("suppliers", rows);
      if (found.getMatches().isEmpty()) {
        // A statement about THIS company's records only — never about whether the code exists
        // anywhere else, which is neither knowable nor disclosable from here.
        out.put("action", zeroMatchAction(found.getApplied()));
      } else {
        out.put(
            "action",
            "Several partners in this company match, so none is identified. Narrow the search with"
                + " the ЕИК; if the matches still cannot be told apart, describe them to the user"
                + " and ask which one is meant.");
      }
    }
    List<String> notes = found.getNotes();
    if (notes != null && !notes.isEmpty()) {
      // The loop this replaced wrote "note" once per note into the same map, so what the model
      // actually received was the LAST one. That is the observed contract, and the prompts were
      // written against it, so it is kept exactly — changing which note survives would silently
      // change what every existing turn is told. The whole list is added beside it instead, so
      // nothing is lost any more.
      out.put("note", notes.get(notes.size() - 1));
      out.put("notes", new ArrayList<>(notes));
    }
    return out;
  }

  /**
   * What to do after nothing in this company matched.
   *
   * <p>Filters are ANDed, so an ЕИК combined with a name or IBAN that does not match finds nothing
   * even when this company already has a partner with that ЕИК. Creating straight away would then
   * hit manage_supplier's duplicate refusal, so the ЕИК alone is searched first. Otherwise a create
   * is the next step whenever the user asked for one and the name and ЕИК are known — no further
   * agreement is asked for.
   */
  static String zeroMatchAction(List<String> applied) {
    boolean identity = applied.contains("eik") || applied.contains("vatNumber");
    boolean combined =
        applied.contains("name") || applied.contains("iban") || applied.contains("supplierId");
    if (identity && combined) {
      return "No partner in this company matches ALL of these filters together. Search again with"
          + " the ЕИК alone before creating anything: a partner with this ЕИК may exist here under"
          + " a different name or without this IBAN.";
    }
    return "No partner in this company matches. If the user asked to create or register this"
        + " supplier and its name and ЕИК are known (read from the document or given by the user),"
        + " create it with manage_supplier (operation=create) — ask only for what is genuinely"
        + " missing. Never use an unreadable or guessed ЕИК.";
  }

  /**
   * G9: the user must be allowed to read partners at all. A seam so a test with no injector can
   * skip the permission engine; system-context work passes inside {@link AiReadGuard} itself.
   */
  protected void requireReadPermission() throws AiToolException {
    AiReadGuard.requireRead(Partner.class, "partners");
  }

  /**
   * The seam a test overrides to supply a fixed {@link SupplierSearchService} instead of needing a
   * running Guice injector and a database — the same idiom used throughout this module.
   */
  protected SupplierSearchService searchService() {
    return Beans.get(SupplierSearchService.class);
  }
}

package com.axelor.apps.aiocr.service.supplier;

import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiocr.service.ocr.BgOcrBankDetails;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.BankDetailsRepository;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One typed supplier lookup, replacing four single-purpose partner tools.
 *
 * <p>The old set — by EIK, by name, by IBAN, plus a separate call for bank accounts — cost the
 * model two to four round trips to answer "who is this supplier and is that IBAN theirs?". Every
 * one of them also re-derived company scope for itself. Here the filters combine in one query and
 * the answer arrives in one call, with the scope applied once.
 *
 * <h2>What each filter means</h2>
 *
 * <ul>
 *   <li><b>supplierId</b> — exact. Out of scope reads as not found, never as "another company's".
 *   <li><b>eik / vatNumber</b> — the canonical Bulgarian rule from {@link SupplierEik}: digits
 *       only, {@code BG} prefix and labels stripped, matched against both {@code registrationCode}
 *       and {@code taxNbr}. A non-Bulgarian VAT number is not an ЕИК, so it falls back to an exact
 *       {@code taxNbr} comparison rather than being silently discarded.
 *   <li><b>name</b> — contains, case-insensitive, minimum three characters. Identity evidence only
 *       in combination with something else; on its own it confirms nothing.
 *   <li><b>iban</b> — resolved through the bank accounts, so "whose account is this?" is answerable
 *       without a second tool.
 * </ul>
 *
 * <p>Filters are ANDed. That is what makes "EIK 203970390 <em>and</em> a name like Елтрейд" a
 * single confirmation step instead of two lookups and a judgement call.
 *
 * <h2>Suppliers, but not only partners already marked as suppliers</h2>
 *
 * The search deliberately does <b>not</b> require {@code isSupplier}. A partner that exists as a
 * customer with the invoice's ЕИК is the single most important thing to surface: the correct action
 * there is to mark it as a supplier, and hiding it would lead straight to a duplicate partner for
 * the same company. The flag is reported instead, and {@code SupplierMaintenanceService} can set
 * it.
 *
 * <p>Archived partners are never returned — the accountant retired them on purpose.
 */
@Singleton
public class SupplierSearchService {

  /** Minimum name fragment; below this a search matches half the table. */
  public static final int MIN_NAME_LENGTH = 3;

  /** What a lookup found, and whether it identifies one supplier. */
  public static final class SearchResult {
    private final List<Partner> matches;
    private final boolean truncated;
    private final List<String> applied;
    private final List<String> notes = new ArrayList<>();
    private Long companyId;

    SearchResult(List<Partner> matches, boolean truncated, List<String> applied) {
      this.matches = matches;
      this.truncated = truncated;
      this.applied = applied;
    }

    /**
     * Whether this match is shared beyond the active company — see {@link
     * SupplierMaintenanceService#isSharedBeyondCompany}. Such a partner is readable and usable here
     * but {@code manage_supplier} refuses to change it, so a caller must not be pointed at an
     * update of it.
     */
    public boolean isSharedBeyondActiveCompany(Partner partner) {
      return SupplierMaintenanceService.isSharedBeyondCompany(partner, companyId);
    }

    public List<Partner> getMatches() {
      return matches;
    }

    public boolean isTruncated() {
      return truncated;
    }

    /** Which filters were actually used, so a caller can say what it searched on. */
    public List<String> getApplied() {
      return applied;
    }

    public List<String> getNotes() {
      return notes;
    }

    /** Exactly one match — the only case in which a supplier is identified. */
    public boolean isUnique() {
      return matches.size() == 1;
    }

    public Partner sole() {
      return isUnique() ? matches.get(0) : null;
    }
  }

  /** Typed criteria. Everything is optional individually; at least one must be present. */
  public static final class Criteria {
    private Long supplierId;
    private String eik;
    private String vatNumber;
    private String name;
    private String iban;
    private int limit = 10;

    public Criteria setSupplierId(Long supplierId) {
      this.supplierId = supplierId;
      return this;
    }

    public Criteria setEik(String eik) {
      this.eik = blankToNull(eik);
      return this;
    }

    public Criteria setVatNumber(String vatNumber) {
      this.vatNumber = blankToNull(vatNumber);
      return this;
    }

    public Criteria setName(String name) {
      this.name = blankToNull(name);
      return this;
    }

    public Criteria setIban(String iban) {
      this.iban = blankToNull(iban);
      return this;
    }

    public Criteria setLimit(int limit) {
      this.limit = Math.max(1, Math.min(limit, SupplierView.MAX_MATCHES));
      return this;
    }

    public boolean isEmpty() {
      return supplierId == null && eik == null && vatNumber == null && name == null && iban == null;
    }
  }

  /**
   * Runs the lookup.
   *
   * @throws IllegalArgumentException when no filter was given, or a given one is unusable
   */
  public SearchResult search(Criteria criteria) {
    if (criteria == null || criteria.isEmpty()) {
      throw new IllegalArgumentException(
          "Give at least one of supplierId, eik, vatNumber, name or iban.");
    }
    Long companyId = companyId();
    List<String> applied = new ArrayList<>();
    List<String> conditions = new ArrayList<>();
    Map<String, Object> binds = new LinkedHashMap<>();

    if (criteria.supplierId != null) {
      conditions.add("self.id = :supplierId");
      binds.put("supplierId", criteria.supplierId);
      applied.add("supplierId");
    }

    // One condition for both identity numbers: a BG VAT number and an ЕИК are the same key, so
    // searching either must find a partner that stored it in the other field.
    Set<String> eikKeys = new LinkedHashSet<>();
    String foreignVat = null;
    if (criteria.eik != null) {
      String canonical = SupplierEik.canonical(criteria.eik);
      if (canonical == null) {
        throw new IllegalArgumentException(
            "'"
                + criteria.eik
                + "' is not a plausible ЕИК (expected 9-13 digits, optionally BG-prefixed).");
      }
      eikKeys.add(canonical);
      applied.add("eik");
    }
    if (criteria.vatNumber != null) {
      String canonical = SupplierEik.canonical(criteria.vatNumber);
      if (canonical != null) {
        eikKeys.add(canonical);
      } else {
        // A foreign VAT number is not an ЕИК; compare it as stored instead of refusing outright.
        foreignVat = criteria.vatNumber.replaceAll("\\s+", "").toUpperCase();
      }
      applied.add("vatNumber");
    }
    for (String key : eikKeys) {
      String param = "eik" + binds.size();
      conditions.add(SupplierEik.identityCondition("self", param));
      binds.put(param, key);
    }
    if (foreignVat != null) {
      conditions.add("REPLACE(UPPER(COALESCE(self.taxNbr, '')), ' ', '') = :foreignVat");
      binds.put("foreignVat", foreignVat);
    }

    if (criteria.name != null) {
      if (criteria.name.length() < MIN_NAME_LENGTH) {
        throw new IllegalArgumentException(
            "Provide at least " + MIN_NAME_LENGTH + " characters to search by name.");
      }
      conditions.add(
          "(LOWER(self.name) LIKE :namePattern OR LOWER(self.fullName) LIKE :namePattern)");
      binds.put("namePattern", "%" + criteria.name.toLowerCase() + "%");
      applied.add("name");
    }

    if (criteria.iban != null) {
      String iban = BgOcrBankDetails.normalizeIban(criteria.iban);
      if (iban == null || !BgOcrBankDetails.isValidIban(iban)) {
        throw new IllegalArgumentException(
            "'" + criteria.iban + "' is not a valid IBAN (checksum failed).");
      }
      applied.add("iban");
      List<Long> owners = ibanOwnerIds(iban, companyId);
      if (owners.isEmpty()) {
        // An IBAN nobody owns is a finding in itself, not an error: it is how "this account is not
        // on
        // any supplier yet" is reported.
        return emptyResult(
            applied, "No bank account with this IBAN is registered on any supplier.");
      }
      conditions.add("self.id IN :ibanOwners");
      binds.put("ibanOwners", owners);
    }

    // Never archived, never a company's own identity — the same Partner row a form shows as
    // "the company" rather than as anybody's supplier — and always inside the company scope. All
    // three in SQL: paging over rows the caller may not see would silently shrink the page and
    // hide real matches behind invisible ones.
    conditions.add("(self.archived IS NULL OR self.archived = FALSE)");
    conditions.add("NOT EXISTS (SELECT 1 FROM Company c WHERE c.partner = self)");
    conditions.add(AiCompanyScope.partnerFilter("self"));

    binds.put(AiCompanyScope.COMPANY_PARAM, companyId);
    int limit = criteria.supplierId != null ? 1 : criteria.limit;
    List<Partner> matches = runQuery(conditions, binds, limit + 1);
    boolean truncated = matches.size() > limit;
    if (truncated) {
      matches = new ArrayList<>(matches.subList(0, limit));
    }

    // Active-company semantics only. There used to be a second, unscoped query here ("does this
    // ЕИК exist anywhere at all?") feeding an existsOutsideCompanyScope flag. It kept only the
    // ЕИК/VAT conditions and dropped the company filter, so an ЕИК + mismatched name/IBAN search
    // found THIS company's own partner and reported it as "in another company" — and even when
    // right, it disclosed the existence of another company's record. Zero matches now means
    // exactly that: nothing in this company matches all of the given filters.
    SearchResult result = new SearchResult(matches, truncated, applied);
    result.companyId = companyId;
    return result;
  }

  /**
   * The active company, or an exception outside one. A seam so a test can supply a fixed company id
   * instead of needing a running request scope — the same idiom {@code
   * SupplierMessageTargetResolver#companyId} uses.
   */
  protected Long companyId() {
    return AiCompanyScope.requireCompanyId();
  }

  /**
   * Runs the one company-scoped {@code Partner} query, ordered by name. A seam so a test can supply
   * fixed rows instead of needing a running Guice injector and a database.
   */
  protected List<Partner> runQuery(List<String> conditions, Map<String, Object> binds, int limit) {
    com.axelor.db.Query<Partner> query =
        Beans.get(PartnerRepository.class).all().filter(String.join(" AND ", conditions));
    for (Map.Entry<String, Object> bind : binds.entrySet()) {
      query = query.bind(bind.getKey(), bind.getValue());
    }
    return query.order("name").fetch(limit);
  }

  /**
   * Partner ids owning this IBAN, scoped.
   *
   * <p>A bank account reaches the caller only when it is this company's own account or a partner
   * account whose partner this company may use, so an IBAN cannot be used to enumerate another
   * company's suppliers.
   */
  private List<Long> ibanOwnerIds(String iban, Long companyId) {
    List<BankDetails> accounts =
        Beans.get(BankDetailsRepository.class)
            .all()
            .filter(
                "REPLACE(UPPER(self.iban), ' ', '') = :iban AND self.partner IS NOT NULL AND ("
                    + AiCompanyScope.companyFilter("self.company")
                    + " OR "
                    + AiCompanyScope.partnerFilter("self.partner")
                    + ")")
            .bind("iban", iban)
            .bind(AiCompanyScope.COMPANY_PARAM, companyId)
            .fetch(SupplierView.MAX_MATCHES);
    List<Long> ids = new ArrayList<>();
    for (BankDetails bd : accounts) {
      if (bd.getPartner() != null && bd.getPartner().getId() != null) {
        ids.add(bd.getPartner().getId());
      }
    }
    return ids;
  }

  private static SearchResult emptyResult(List<String> applied, String note) {
    SearchResult result = new SearchResult(new ArrayList<>(), false, applied);
    result.getNotes().add(note);
    return result;
  }

  private static String blankToNull(String v) {
    return v == null || v.trim().isEmpty() ? null : v.trim();
  }
}

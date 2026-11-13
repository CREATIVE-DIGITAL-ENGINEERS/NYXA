package com.axelor.apps.aiaccounting.service.invoice;

import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.security.AiPartnerIdentity;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.db.Query;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds a partner by identity, name or bank account, in one role or the other.
 *
 * <h2>Role is a filter, not an identity</h2>
 *
 * The same {@code Partner} can be both a customer and a supplier — a company you buy from and sell
 * to is one record with two flags. So the role narrows the search rather than defining it, and a
 * partner that exists but does not carry the flag is <b>still returned</b>, with the fact reported.
 * Hiding it would send the caller off to create a duplicate of a company that is already there,
 * which is the expensive mistake in partner data.
 *
 * <h2>One match identifies; several identify nobody</h2>
 *
 * A name is never proof of identity — two companies share a name often enough, and a partial match
 * more often still. The caller is told how many matched and whether that is one, and choosing
 * between several is a question for a person rather than a coin flip.
 */
@Singleton
public class PartnerSearchService {

  public static final int MAX_MATCHES = 20;
  public static final int DEFAULT_LIMIT = 10;

  /** Which flag the search should prefer. */
  public enum Role {
    CUSTOMER("isCustomer", "customer", "customerId"),
    SUPPLIER("isSupplier", "supplier", "supplierId");

    private final String field;
    private final String label;
    private final String idArgument;

    Role(String field, String label, String idArgument) {
      this.field = field;
      this.label = label;
      this.idArgument = idArgument;
    }

    public String field() {
      return field;
    }

    /** The id argument's name in this role's tool schemas, so messages use the caller's names. */
    public String idArgument() {
      return idArgument;
    }

    public String label() {
      return label;
    }
  }

  /** What the model may filter on. Every field is optional, but at least one is required. */
  public static class Criteria {
    public Role role = Role.CUSTOMER;
    public Long partnerId;

    /**
     * The ЕИК, digits or BG-prefixed; normalised and matched against registrationCode and taxNbr.
     * An implausible ЕИК is refused rather than compared literally.
     */
    public String registrationCode;

    /**
     * A VAT number. A BG one is normalised to its ЕИК and matched as {@link #registrationCode}; a
     * foreign one is compared against taxNbr as stored.
     */
    public String taxNumber;

    public String name;
    public String iban;
    public int limit = DEFAULT_LIMIT;
  }

  /** The matches, and what the caller should understand about them. */
  public static class SearchResult {
    private final List<Partner> matches;
    private final List<String> applied;
    private final boolean truncated;
    private final Role role;

    SearchResult(List<Partner> matches, List<String> applied, boolean truncated, Role role) {
      this.matches = matches;
      this.applied = applied;
      this.truncated = truncated;
      this.role = role;
    }

    public List<Partner> getMatches() {
      return matches;
    }

    /** The filters that were actually used, so the caller can say what it searched on. */
    public List<String> getApplied() {
      return applied;
    }

    public boolean isTruncated() {
      return truncated;
    }

    public Role getRole() {
      return role;
    }

    public boolean isUnique() {
      return matches.size() == 1;
    }

    public Partner sole() {
      return matches.get(0);
    }
  }

  public SearchResult search(Criteria criteria) {
    Role role = criteria.role == null ? Role.CUSTOMER : criteria.role;
    List<String> where = new ArrayList<>();
    List<String> applied = new ArrayList<>();
    Map<String, Object> params = new LinkedHashMap<>();

    // Partners are shared: an empty companySet means every company may use them, a populated one
    // lists which. Same rule every partner read here follows.
    where.add(AiCompanyScope.partnerFilter("self"));
    params.put(AiCompanyScope.COMPANY_PARAM, AiCompanyScope.requireCompanyId());
    // Contacts are people attached to a partner, not partners to invoice.
    where.add("(self.isContact IS NULL OR self.isContact = FALSE)");
    // Never archived, never a company's own identity — the same exclusions the supplier lookup
    // applies, and in SQL, so paging never runs over rows the caller is not offered.
    where.add(AiPartnerIdentity.notArchived("self"));
    where.add(AiPartnerIdentity.notCompanyIdentity("self"));

    if (criteria.partnerId != null) {
      where.add("self.id = :partnerId");
      params.put("partnerId", criteria.partnerId);
      applied.add(role.idArgument());
    }
    // One normalised key for both identity numbers: a BG VAT number is BG + ЕИК, so either input
    // must find a partner that stored it in registrationCode or in taxNbr.
    Set<String> eikKeys = new LinkedHashSet<>();
    if (notBlank(criteria.registrationCode)) {
      String canonical = AiPartnerIdentity.canonicalEik(criteria.registrationCode);
      if (canonical == null) {
        throw new IllegalArgumentException(
            "'"
                + criteria.registrationCode.trim()
                + "' is not a plausible ЕИК (expected 9-13 digits, optionally BG-prefixed).");
      }
      eikKeys.add(canonical);
      applied.add("eik");
    }
    if (notBlank(criteria.taxNumber)) {
      String canonical = AiPartnerIdentity.canonicalEik(criteria.taxNumber);
      if (canonical != null) {
        eikKeys.add(canonical);
      } else {
        // A foreign VAT number is not an ЕИК; it is compared as stored instead of refused.
        where.add(AiPartnerIdentity.foreignVatCondition("self", "foreignVat"));
        params.put("foreignVat", AiPartnerIdentity.foreignVatKey(criteria.taxNumber));
      }
      applied.add("vatNumber");
    }
    int eikParam = 0;
    for (String key : eikKeys) {
      String param = "eik" + eikParam++;
      where.add(AiPartnerIdentity.eikCondition("self", param));
      params.put(param, key);
    }
    if (notBlank(criteria.name)) {
      if (criteria.name.trim().length() < 3) {
        throw new IllegalArgumentException(
            "A name search needs at least three characters; anything shorter matches too much to"
                + " identify anybody.");
      }
      where.add(
          "(LOWER(self.fullName) LIKE :name OR LOWER(self.name) LIKE :name"
              + " OR LOWER(self.simpleFullName) LIKE :name)");
      params.put("name", "%" + criteria.name.trim().toLowerCase(Locale.ROOT) + "%");
      applied.add("name");
    }
    if (notBlank(criteria.iban)) {
      where.add(
          "EXISTS (SELECT 1 FROM BankDetails b WHERE b.partner = self"
              + " AND LOWER(REPLACE(b.iban, ' ', '')) = :iban)");
      params.put("iban", criteria.iban.trim().replace(" ", "").toLowerCase(Locale.ROOT));
      applied.add("iban");
    }

    if (applied.isEmpty()) {
      throw new IllegalArgumentException(
          "Give at least one of " + role.idArgument() + ", eik, vatNumber, name or iban.");
    }

    int limit = Math.max(1, Math.min(criteria.limit, MAX_MATCHES));
    List<Partner> matches = fetch(String.join(" AND ", where), params, role, limit + 1);
    boolean truncated = matches.size() > limit;
    if (truncated) {
      matches = matches.subList(0, limit);
    }
    return new SearchResult(matches, applied, truncated, role);
  }

  /**
   * Runs the built filter. A seam so a test can see exactly what was asked of the database without
   * needing one.
   */
  protected List<Partner> fetch(String filter, Map<String, Object> params, Role role, int max) {
    Query<Partner> query = Beans.get(PartnerRepository.class).all().filter(filter).bind(params);
    // The role's own flag first, so when several match, the one that already IS a customer (or a
    // supplier) is the one the caller sees first.
    query.order("-" + role.field()).order("fullName").order("id");
    return query.fetch(max);
  }

  /**
   * The one partner an id names, in this company's scope, or null.
   *
   * <p>Used by the creation and reassignment paths, where the caller already has an id and the
   * question is only whether it is reachable and carries the right role.
   */
  public Partner findScoped(long partnerId) {
    Partner partner = Beans.get(PartnerRepository.class).find(partnerId);
    if (partner == null || !AiCompanyScope.partnerInScope(partner.getCompanySet())) {
      return null;
    }
    return partner;
  }

  /** Whether the partner carries the role's own flag. */
  public boolean hasRole(Partner partner, Role role) {
    if (partner == null) {
      return false;
    }
    return role == Role.CUSTOMER
        ? Boolean.TRUE.equals(partner.getIsCustomer())
        : Boolean.TRUE.equals(partner.getIsSupplier());
  }

  private static boolean notBlank(String value) {
    return value != null && !value.trim().isEmpty();
  }
}

package com.axelor.apps.aiaccountant.security;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The single company every AI-driven read and write is confined to.
 *
 * <p>Nyxa is one database holding many companies, and until this class existed the assistant's own
 * tools decided company scope for themselves — some by the user's whole {@code companySet}, some by
 * a {@code companyId} the model was allowed to pass, most not at all. That is three different
 * answers to one question, and the loosest of them wins whenever a new tool copies the wrong
 * neighbour. So the question is answered once, here.
 *
 * <h2>The rule</h2>
 *
 * <ol>
 *   <li><b>A system context</b> ({@link AiExecutionContext#callAsSystem}) is confined to exactly
 *       the company it declared. A system context that declared <em>no</em> company is not "trusted
 *       everywhere" — it is refused. Background work that cannot name its company must not reach
 *       the ERP through the assistant at all.
 *   <li><b>An authenticated user</b> is confined to their <em>active</em> company — not to their
 *       company set. The active company is what the rest of Nyxa already means by "the company I am
 *       working in" ({@code User.activeCompany}, chosen in Preferences and restricted there to the
 *       user's own {@code companySet}), and it is what defaults onto every record they create. When
 *       it is unset the resolution falls back exactly the way {@code PartnerController} and {@code
 *       BgOcrIntakeService} already fall back: a single permitted company, then a single company in
 *       the database, then refusal.
 *   <li><b>Neither</b> — refused.
 * </ol>
 *
 * <p>Nothing here is reachable from tool arguments. There is no parameter, field or JSON path that
 * can widen the scope; {@link #stripCompanyArguments(JsonNode)} exists precisely so a
 * model-supplied {@code companyId} is removed before a tool ever sees it.
 *
 * <h2>What "belongs to the company" means per entity</h2>
 *
 * <ul>
 *   <li><b>Records with their own {@code company}</b> (AiOcrUpload, Invoice, Move…) — that company
 *       must be the scope company. A record with <em>no</em> company is out of scope: it is not
 *       this company's data, and guessing that it is would be the same bypass in a different shape.
 *   <li><b>Partner</b> — Axelor shares partner master data across companies through the
 *       many-to-many {@code companySet}: an <em>empty</em> set means "usable by every company", a
 *       non-empty one restricts the partner to those companies. That is the semantic the stock
 *       views use ({@code self.companySet IS EMPTY OR …}) and it is the semantic applied here.
 *       Partners are never invented or re-homed by this class.
 *   <li><b>Product</b> — genuinely global. Axelor puts the per-company part in separate rows
 *       ({@code ProductCompany} for field overrides, {@code AccountManagement} for accounts), so a
 *       product has no owning company to filter on. What is scoped is the <em>company the accounts
 *       are resolved for</em>, which comes from here rather than from the model.
 *   <li><b>BankDetails</b> — either a company account ({@code company} set) or a partner account
 *       ({@code partner} set). In scope when the company is the scope company, or when the partner
 *       is in scope by the rule above.
 * </ul>
 *
 * <h2>Non-leaky refusals</h2>
 *
 * This class deliberately returns booleans instead of throwing "belongs to another company". Each
 * caller raises its own <em>not found</em> message, identical to the one a genuinely missing id
 * produces, so an id from another company cannot be used to probe whether that record exists.
 */
public final class AiCompanyScope {

  /** Bind name used by every JPQL fragment this class hands out. */
  public static final String COMPANY_PARAM = "aiScopeCompanyId";

  /**
   * Argument names the model is never allowed to decide. Stripped before execution rather than
   * rejected: a refusal would end an otherwise valid turn, while removal simply makes the attempt
   * have no effect.
   */
  private static final List<String> COMPANY_ARGUMENTS =
      Arrays.asList("companyId", "company", "companyCode", "companySet", "companyIds");

  private AiCompanyScope() {}

  /** Raised when no company can be resolved for the caller. Never names a record. */
  public static class ScopeUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ScopeUnavailableException(String message) {
      super(message);
    }
  }

  // ---------------------------------------------------------------------------
  // Resolution
  // ---------------------------------------------------------------------------

  /**
   * The company this thread is confined to.
   *
   * @throws ScopeUnavailableException when there is no caller, or a caller whose company cannot be
   *     determined. Refusing is the point: an unresolved scope must never mean "all companies".
   */
  public static Long requireCompanyId() {
    AiExecutionContext.SystemScope system = AiExecutionContext.systemScope();
    if (system != null) {
      if (system.getCompanyId() == null) {
        throw new ScopeUnavailableException(
            "This internal operation did not declare a company, so it has no access to company"
                + " data.");
      }
      return system.getCompanyId();
    }

    User user = AuthUtils.getUser();
    if (user == null) {
      throw new ScopeUnavailableException("There is no caller identity, so nothing can be read.");
    }
    if (user.getActiveCompany() != null && user.getActiveCompany().getId() != null) {
      return user.getActiveCompany().getId();
    }
    // Same fallback ladder the rest of Nyxa uses when activeCompany is unset (see
    // PartnerController.getActiveCompany and BgOcrIntakeService.resolveCompany): one permitted
    // company is unambiguous, and so is a single-company database. Anything else is a real choice
    // the user has to make, and guessing it would put the assistant in the wrong books.
    Set<Company> permitted = user.getCompanySet();
    if (permitted != null && permitted.size() == 1) {
      Company only = permitted.iterator().next();
      if (only != null && only.getId() != null) {
        return only.getId();
      }
    }
    Long sole = soleCompanyId();
    if (sole != null) {
      return sole;
    }
    throw new ScopeUnavailableException(
        "No active company is selected for your user, so there is no company to work in. Choose one"
            + " in Preferences → Active company and try again.");
  }

  /** The scope company as an entity, for callers that need its configuration. */
  public static Company requireCompany() {
    Long id = requireCompanyId();
    Company company = Beans.get(CompanyRepository.class).find(id);
    if (company == null) {
      throw new ScopeUnavailableException("The active company is no longer available.");
    }
    return company;
  }

  /**
   * The scope company as an entity, or {@code null} when it cannot be resolved.
   *
   * <p>For callers that want to <em>mention</em> the company rather than act on it — the chat's
   * page context, for instance, where "no company" is a thing to leave out of a sentence, not an
   * error to raise. Nothing is widened: a null answer never means "all companies".
   */
  public static Company currentCompanyOrNull() {
    try {
      return requireCompany();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** The scope company id, or {@code null} when it cannot be resolved. Never widens anything. */
  public static Long companyIdOrNull() {
    try {
      return requireCompanyId();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** {@code null} when the database does not hold exactly one company. */
  private static Long soleCompanyId() {
    try {
      CompanyRepository repo = Beans.get(CompanyRepository.class);
      if (repo.all().count() != 1L) {
        return null;
      }
      Company only = repo.all().fetchOne();
      return only == null ? null : only.getId();
    } catch (RuntimeException e) {
      // No injector or no persistence in this context: unresolved, which means refused.
      return null;
    }
  }

  // ---------------------------------------------------------------------------
  // Membership tests — booleans, so the caller can answer "not found" itself
  // ---------------------------------------------------------------------------

  /** True when this record's own company is the scope company. A null company is never in scope. */
  public static boolean owns(Company recordCompany) {
    return recordCompany != null && ownsId(recordCompany.getId());
  }

  /** True when this company id is the scope company. A null id is never in scope. */
  public static boolean ownsId(Long recordCompanyId) {
    if (recordCompanyId == null) {
      return false;
    }
    Long scope = companyIdOrNull();
    return scope != null && scope.equals(recordCompanyId);
  }

  /**
   * True when a partner may be used by the scope company: either shared across all companies (an
   * empty {@code companySet}) or explicitly attached to this one.
   */
  public static boolean partnerInScope(Collection<Company> partnerCompanySet) {
    Long scope = companyIdOrNull();
    if (scope == null) {
      return false;
    }
    if (partnerCompanySet == null || partnerCompanySet.isEmpty()) {
      return true;
    }
    for (Company c : partnerCompanySet) {
      if (c != null && scope.equals(c.getId())) {
        return true;
      }
    }
    return false;
  }

  /**
   * True when a bank account is reachable from the scope company: a company account belonging to
   * it, or a partner account whose partner is in scope. An account attached to neither is out of
   * scope.
   */
  public static boolean bankDetailsInScope(
      Company accountCompany, Collection<Company> partnerCompanySet, boolean hasPartner) {
    if (accountCompany != null) {
      return owns(accountCompany);
    }
    return hasPartner && partnerInScope(partnerCompanySet);
  }

  // ---------------------------------------------------------------------------
  // JPQL fragments — so the filtering happens in the query, not after it
  // ---------------------------------------------------------------------------

  /**
   * Condition restricting a query to records whose own {@code company} is the scope company. Bind
   * {@link #COMPANY_PARAM}.
   *
   * @param path the company-valued path, e.g. {@code "self.company"} or {@code
   *     "self.invoice.company"}
   */
  public static String companyFilter(String path) {
    return "(" + path + ".id = :" + COMPANY_PARAM + ")";
  }

  /**
   * Condition restricting a Partner-valued path to partners usable by the scope company. Bind
   * {@link #COMPANY_PARAM}.
   *
   * <p>The explicit-alias subquery shape is the one the stock Nyxa views already use for exactly
   * this question, so it is known to translate; an implicit join through {@code
   * self.partner.companySet} is not worth the risk.
   *
   * @param path the partner-valued path, e.g. {@code "self"} on a Partner query or {@code
   *     "self.partner"} on a BankDetails one
   */
  public static String partnerFilter(String path) {
    return "("
        + path
        + " IN (SELECT p FROM Partner p WHERE p.companySet IS EMPTY"
        + " OR EXISTS (SELECT 1 FROM Company c WHERE c MEMBER OF p.companySet"
        + " AND c.id = :"
        + COMPANY_PARAM
        + ")))";
  }

  // ---------------------------------------------------------------------------
  // Argument hygiene
  // ---------------------------------------------------------------------------

  /**
   * Removes every company-identifying argument from a model-supplied argument object.
   *
   * <p>Defence in depth, not the primary control: no tool reads a company from its arguments any
   * more. It is applied on both entry paths — the dispatcher and the approved-change replay — so a
   * tool added later cannot quietly reintroduce one.
   *
   * @return the names that were removed, for the log; empty when there was nothing to remove
   */
  public static List<String> stripCompanyArguments(JsonNode args) {
    if (!(args instanceof ObjectNode)) {
      return java.util.Collections.emptyList();
    }
    ObjectNode object = (ObjectNode) args;
    List<String> removed = new java.util.ArrayList<>();
    for (String field : COMPANY_ARGUMENTS) {
      if (object.has(field)) {
        object.remove(field);
        removed.add(field);
      }
    }
    return removed;
  }
}

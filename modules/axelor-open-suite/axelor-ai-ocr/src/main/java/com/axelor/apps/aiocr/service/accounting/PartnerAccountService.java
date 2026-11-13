package com.axelor.apps.aiocr.service.accounting;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.AccountingSituation;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.account.db.repo.AccountTypeRepository;
import com.axelor.apps.account.db.repo.AccountingSituationRepository;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.base.db.Company;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The two flags a partner account must carry, inspected and corrected.
 *
 * <h2>The rule this exists for</h2>
 *
 * Axelor refuses to validate or ventilate an invoice whose partner account is not both reconcilable
 * and usable for the partner balance. The check is {@code
 * InvoiceToolService.checkUseForPartnerBalanceAndReconcileOk}, called from {@code
 * ValidateState.setPartnerAccount} and {@code VentilateState}, and it throws "Please make sure that
 * the account %s being used can be reconciled and used for partner balance." Both flags are plain
 * booleans on {@code Account}: {@code reconcileOk} ("Reconcilable") and {@code
 * useForPartnerBalance} ("Use for partner balance").
 *
 * <p>The account being checked is the one {@code AccountingSituationService.getPartnerAccount}
 * resolves — the partner's own {@code AccountingSituation.supplierAccount} /{@code customerAccount}
 * for that company when it has one, otherwise the company's {@code AccountConfig.supplierAccount}
 * /{@code customerAccount}. So "which account is wrong" is a question about a specific company's
 * configuration, not about the chart of accounts in general.
 *
 * <p>It is a real, shipped defect rather than a local mistake: the Bulgarian chart-of-accounts
 * template ({@code l10n_bgr/BG_COA/account_account.csv}) marks 401 Доставчици with {@code
 * useForPartnerBalance = false} while marking 411 Клиенти true. Every company created from that
 * template therefore starts with a supplier account that cannot post a supplier invoice, and the
 * failure only shows up at Validate — long after the invoice looks complete.
 *
 * <h2>What may be changed, and what may not</h2>
 *
 * These flags decide how money is attributed to a partner, so this is deliberately not a field
 * editor with an account picker. Five conditions, all enforced here, and all of them narrowing:
 *
 * <ol>
 *   <li><b>The active company owns it.</b> {@code Account.company} must equal the caller's active
 *       company. Axelor's chart of accounts is per company, so this alone excludes every other
 *       company's books.
 *   <li><b>It is a partner account by type</b> — {@code payable} or {@code receivable}. A general
 *       ledger account (expense, income, immobilisation, tax…) is refused outright: partner-balance
 *       tracking on one would change how it aggregates, which is not a repair.
 *   <li><b>It is actually in use as a partner account here</b> — named by this company's {@code
 *       AccountConfig} or by one of its {@code AccountingSituation} rows. An unused payable account
 *       from the template is left exactly as the template shipped it.
 *   <li><b>Flags are only ever turned on.</b> Turning one off cannot unblock anything and would
 *       silently change reconciliation and reporting for every move already posted to it.
 *   <li><b>One account per call.</b> There is no bulk mode: nine payable accounts are wrong in a
 *       typical BG install, and eight of them are wrong <em>correctly</em> (advances, factoring,
 *       other creditors) because they are not used as partner accounts.
 * </ol>
 *
 * <p>Saving goes through {@link AccountRepository} — the bound chain, ending in {@code
 * AccountAccountRepository.save}, which propagates {@code reconcileOk} across {@code
 * compatibleAccountSet}. That is business logic, so it is reused rather than bypassed; this is the
 * opposite of the {@code JPA.save} decision taken for Invoice, where the chain would recompute
 * amounts we must not touch.
 */
@Singleton
public class PartnerAccountService {

  private static final Logger LOG = LoggerFactory.getLogger(PartnerAccountService.class);

  /** The two flags, named as the Account form names them. */
  public static final String F_RECONCILE_OK = "reconcileOk";

  public static final String F_USE_FOR_PARTNER_BALANCE = "useForPartnerBalance";

  /**
   * Ceiling on inspected accounts, so a company with many partners cannot produce a huge result.
   */
  public static final int MAX_ACCOUNTS = 25;

  private final AccountRepository accountRepo;

  @Inject
  public PartnerAccountService(AccountRepository accountRepo) {
    this.accountRepo = accountRepo;
  }

  /** Raised when an account cannot be inspected or corrected; the message is safe to show. */
  public static class PartnerAccountRefusedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PartnerAccountRefusedException(String message) {
      super(message);
    }
  }

  // ---------------------------------------------------------------------------
  // Inspect
  // ---------------------------------------------------------------------------

  /**
   * Every account this company actually uses as a partner account, with its flags and a verdict.
   *
   * <p>Read-only, and scoped to the active company throughout: the {@code AccountConfig} is that
   * company's, the {@code AccountingSituation} rows are filtered by it, and each account is checked
   * to belong to it before it is reported.
   */
  public Map<String, Object> inspect() {
    Company company = AiCompanyScope.requireCompany();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("companyId", company.getId());
    out.put("company", company.getName());

    List<Usage> usages = partnerAccountUsages(company);
    List<Map<String, Object>> accounts = new ArrayList<>();
    List<String> misconfigured = new ArrayList<>();
    for (Usage usage : usages) {
      Map<String, Object> row = describe(usage);
      accounts.add(row);
      if (!Boolean.TRUE.equals(row.get("usable"))) {
        misconfigured.add(label(usage.account) + " — " + row.get("problem"));
      }
      if (accounts.size() >= MAX_ACCOUNTS) {
        break;
      }
    }
    out.put("accounts", accounts);
    out.put("misconfigured", misconfigured);
    out.put("allUsable", misconfigured.isEmpty());
    out.put(
        "rule",
        "A partner account must have both reconcileOk and useForPartnerBalance, or Axelor refuses"
            + " to validate and ventilate any invoice that uses it.");
    return out;
  }

  /** One place an account is used as a partner account, so a report can say why it matters. */
  public static final class Usage {
    private final Account account;
    private final String role;
    private final String where;

    Usage(Account account, String role, String where) {
      this.account = account;
      this.role = role;
      this.where = where;
    }

    public Account getAccount() {
      return account;
    }

    /** {@code supplier} or {@code customer}. */
    public String getRole() {
      return role;
    }

    /** Where it is configured, e.g. {@code AccountConfig} or {@code AccountingSituation: ACME}. */
    public String getWhere() {
      return where;
    }
  }

  /**
   * The accounts this company uses as partner accounts: config defaults first, then per-partner
   * overrides.
   *
   * <p>Config first on purpose — it is the one that applies to every partner with no situation of
   * its own, so it is the one most likely to be the answer. Deduplicated by account id, keeping the
   * first (most general) reason it is in use.
   */
  protected List<Usage> partnerAccountUsages(Company company) {
    List<Usage> usages = new ArrayList<>();
    Set<Long> seen = new LinkedHashSet<>();

    AccountConfig config = null;
    try {
      config = Beans.get(AccountConfigService.class).getAccountConfig(company);
    } catch (Exception e) {
      LOG.info("No AccountConfig for company {}: {}", company.getId(), e.getMessage());
    }
    if (config != null) {
      add(usages, seen, config.getSupplierAccount(), "supplier", "AccountConfig.supplierAccount");
      add(usages, seen, config.getCustomerAccount(), "customer", "AccountConfig.customerAccount");
      add(
          usages,
          seen,
          config.getHoldBackSupplierAccount(),
          "supplier",
          "AccountConfig.holdBackSupplierAccount");
      add(
          usages,
          seen,
          config.getHoldBackCustomerAccount(),
          "customer",
          "AccountConfig.holdBackCustomerAccount");
    }

    for (AccountingSituation situation :
        Beans.get(AccountingSituationRepository.class)
            .all()
            .filter("self.company = :company")
            .bind("company", company)
            .fetch(MAX_ACCOUNTS * 2)) {
      String partner =
          situation.getPartner() == null ? "?" : String.valueOf(situation.getPartner().getName());
      add(
          usages,
          seen,
          situation.getSupplierAccount(),
          "supplier",
          "AccountingSituation: " + partner);
      add(
          usages,
          seen,
          situation.getCustomerAccount(),
          "customer",
          "AccountingSituation: " + partner);
      add(
          usages,
          seen,
          situation.getHoldBackSupplierAccount(),
          "supplier",
          "AccountingSituation (hold back): " + partner);
      add(
          usages,
          seen,
          situation.getHoldBackCustomerAccount(),
          "customer",
          "AccountingSituation (hold back): " + partner);
    }
    return usages;
  }

  private void add(List<Usage> usages, Set<Long> seen, Account account, String role, String where) {
    if (account == null || account.getId() == null || !seen.add(account.getId())) {
      return;
    }
    // Belt and braces: an AccountConfig or AccountingSituation should only ever reference its own
    // company's accounts, but reporting one that does not would be reporting another company's
    // book.
    if (!ownedByActiveCompany(account)) {
      LOG.warn(
          "Account {} referenced by {} does not belong to the active company; not reported.",
          account.getId(),
          where);
      return;
    }
    usages.add(new Usage(account, role, where));
  }

  /** One account's flags and what is wrong with them, if anything. */
  protected Map<String, Object> describe(Usage usage) {
    Account account = usage.account;
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("accountId", account.getId());
    m.put("code", account.getCode());
    m.put("name", account.getName());
    m.put("role", usage.role);
    m.put("usedAs", usage.where);
    m.put("accountType", technicalType(account));
    boolean reconcile = Boolean.TRUE.equals(account.getReconcileOk());
    boolean partnerBalance = Boolean.TRUE.equals(account.getUseForPartnerBalance());
    m.put(F_RECONCILE_OK, reconcile);
    m.put(F_USE_FOR_PARTNER_BALANCE, partnerBalance);
    m.put("usable", reconcile && partnerBalance);
    if (!reconcile || !partnerBalance) {
      List<String> missing = new ArrayList<>();
      if (!reconcile) {
        missing.add(F_RECONCILE_OK);
      }
      if (!partnerBalance) {
        missing.add(F_USE_FOR_PARTNER_BALANCE);
      }
      m.put("problem", String.join(" and ", missing) + " is off");
    }
    return m;
  }

  // ---------------------------------------------------------------------------
  // Correct
  // ---------------------------------------------------------------------------

  /**
   * After a write whose answer was lost: are the two flags on, or not?
   *
   * <h2>An exact postcondition, and a rare one</h2>
   *
   * This operation only ever turns flags ON, so its entire intent is a state rather than a
   * transition: "reconcileOk and useForPartnerBalance are true on this company's account with this
   * code". That state is readable, it is the whole of what was asked for, and it cannot have been
   * reached halfway — so a lost answer is fully settled by one read, with no baseline needed and no
   * ambiguity about who caused it. If the flags are on the invoices can be validated, which is what
   * the accountant actually wanted; if they are off nothing was written and the call may run again.
   *
   * <p>Read-only and company-scoped through the same {@code requirePartnerAccount} the write path
   * uses, so an account this company does not own is never inspected.
   *
   * @return {@link Boolean#TRUE} when both flags are on, {@link Boolean#FALSE} when at least one is
   *     off, and null when the account cannot be resolved at all — which is "cannot say", not "no"
   */
  public Boolean isEnabledForPartnerAccounting(String accountCode) {
    try {
      Account account = requirePartnerAccount(accountCode, AiCompanyScope.requireCompany()).account;
      return Boolean.TRUE.equals(account.getReconcileOk())
          && Boolean.TRUE.equals(account.getUseForPartnerBalance());
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * The "already configured" answer, rebuilt by re-reading the account.
   *
   * <p>The same shape {@link #enablePartnerAccounting} returns when it finds nothing to change,
   * because that is exactly what the confirmation path has established: the flags are on. Reusing
   * the shape rather than inventing one keeps a published result independent of whether the network
   * held up.
   */
  public Map<String, Object> confirmedResult(String accountCode) {
    try {
      Account account = requirePartnerAccount(accountCode, AiCompanyScope.requireCompany()).account;
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("status", "unchanged");
      out.put("accountId", account.getId());
      out.put("account", label(account));
      out.put(
          "message",
          "Account "
              + label(account)
              + " is correctly configured. The connection was lost while it was being changed, so"
              + " this was confirmed by re-reading the account rather than reported by the write"
              + " itself.");
      return out;
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * Turns the two flags on for one partner account of the active company.
   *
   * @param accountCode the account code as the chart of accounts shows it, e.g. {@code 401}
   * @param reason why, for the log; the entity itself carries Axelor's own change tracking
   * @throws PartnerAccountRefusedException when any of the five conditions in the class javadoc
   *     fails — always before anything is written
   */
  @Transactional(rollbackOn = Exception.class)
  public Map<String, Object> enablePartnerAccounting(String accountCode, String reason) {
    if (!AiExecutionContext.isAuthorisedCaller()) {
      throw new PartnerAccountRefusedException(
          "No caller identity; accounting configuration cannot be changed anonymously.");
    }
    Company company = AiCompanyScope.requireCompany();
    Usage usage = requirePartnerAccount(accountCode, company);
    Account account = usage.account;

    if (!Beans.get(JpaSecurity.class)
        .isPermitted(JpaSecurity.CAN_WRITE, Account.class, account.getId())) {
      throw new PartnerAccountRefusedException(
          "You do not have permission to change account " + label(account) + ".");
    }

    List<String> changed = new ArrayList<>();
    if (!Boolean.TRUE.equals(account.getReconcileOk())) {
      account.setReconcileOk(Boolean.TRUE);
      changed.add(F_RECONCILE_OK);
    }
    if (!Boolean.TRUE.equals(account.getUseForPartnerBalance())) {
      account.setUseForPartnerBalance(Boolean.TRUE);
      changed.add(F_USE_FOR_PARTNER_BALANCE);
    }

    Map<String, Object> out = new LinkedHashMap<>();
    if (changed.isEmpty()) {
      out.put("status", "unchanged");
      out.put("accountId", account.getId());
      out.put("account", label(account));
      out.put("message", "Account " + label(account) + " was already correctly configured.");
      return out;
    }

    // The bound repository chain, not JPA.save: AccountAccountRepository.save propagates
    // reconcileOk across compatibleAccountSet, which is business logic this must not skip.
    Account saved = accountRepo.save(account);
    LOG.info(
        "Company {}: account {} enabled for partner accounting ({}) by {}{}",
        company.getId(),
        label(saved),
        String.join(", ", changed),
        AiExecutionContext.callerDescription(),
        reason == null || reason.trim().isEmpty() ? "" : " — " + reason.trim());

    out.put("status", "applied");
    out.put("accountId", saved.getId());
    out.put("account", label(saved));
    out.put("companyId", company.getId());
    out.put("enabled", changed);
    out.put("usedAs", usage.where);
    out.put(F_RECONCILE_OK, Boolean.TRUE.equals(saved.getReconcileOk()));
    out.put(F_USE_FOR_PARTNER_BALANCE, Boolean.TRUE.equals(saved.getUseForPartnerBalance()));
    out.put(
        "effect",
        "Invoices using this account can now be validated and ventilated. Existing documents are"
            + " unchanged; nothing was posted.");
    return out;
  }

  /**
   * The account with this code, provided it is one this company uses as a partner account.
   *
   * <p>The three refusals are distinct on purpose: "no such account here", "not a partner account
   * type" and "not used as a partner account" call for three different next steps, and collapsing
   * them into one message would send a caller looking in the wrong place.
   */
  protected Usage requirePartnerAccount(String accountCode, Company company) {
    String code = accountCode == null ? "" : accountCode.trim();
    if (code.isEmpty()) {
      throw new PartnerAccountRefusedException("An account code is required.");
    }
    for (Usage usage : partnerAccountUsages(company)) {
      if (code.equalsIgnoreCase(usage.account.getCode())) {
        // Referenced as a partner account is not the same as being one: a configuration can point
        // at a general-ledger account by mistake, and the type rule must hold on this path too.
        if (!isPartnerAccountType(usage.account)) {
          throw notPartnerAccountType(usage.account);
        }
        return usage;
      }
    }
    // Not in use as a partner account. Say which of the two reasons it is, from this company's own
    // chart of accounts — an account that does not exist here and one that exists but is a general
    // ledger account are different problems.
    Account existing =
        accountRepo
            .all()
            .filter("UPPER(self.code) = :code AND self.company = :company")
            .bind("code", code.toUpperCase())
            .bind("company", company)
            .fetchOne();
    if (existing == null) {
      throw new PartnerAccountRefusedException(
          "No account with code "
              + code
              + " exists in "
              + company.getName()
              + "'s chart of accounts.");
    }
    if (!isPartnerAccountType(existing)) {
      throw notPartnerAccountType(existing);
    }
    throw new PartnerAccountRefusedException(
        "Account "
            + label(existing)
            + " exists but is not used as a partner account by "
            + company.getName()
            + " (neither its AccountConfig nor any of its accounting situations reference it), so"
            + " changing its flags would not affect any invoice. Check which account the invoice"
            + " actually resolves to first.");
  }

  private PartnerAccountRefusedException notPartnerAccountType(Account account) {
    return new PartnerAccountRefusedException(
        "Account "
            + label(account)
            + " is a "
            + technicalType(account)
            + " account, not a payable or receivable one. Partner-balance and reconciliation"
            + " flags are only meaningful on partner accounts, so this is refused.");
  }

  /** Payable or receivable — the only two types on which these flags mean anything. */
  protected boolean isPartnerAccountType(Account account) {
    String type = technicalType(account);
    return AccountTypeRepository.TYPE_PAYABLE.equals(type)
        || AccountTypeRepository.TYPE_RECEIVABLE.equals(type);
  }

  protected boolean ownedByActiveCompany(Account account) {
    Company company = AiCompanyScope.requireCompany();
    return account.getCompany() != null
        && company.getId() != null
        && company.getId().equals(account.getCompany().getId());
  }

  private static String technicalType(Account account) {
    return account.getAccountType() == null
        ? null
        : account.getAccountType().getTechnicalTypeSelect();
  }

  /** {@code 401 Доставчици} — the way an accountant refers to an account. */
  public static String label(Account account) {
    if (account == null) {
      return "(none)";
    }
    return account.getCode() + (account.getName() == null ? "" : " " + account.getName());
  }
}

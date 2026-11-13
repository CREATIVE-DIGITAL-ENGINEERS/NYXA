package com.axelor.apps.bglocalization.service.accounting;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountType;
import com.axelor.apps.account.db.AccountingReport;
import com.axelor.apps.account.db.AccountingReportConfigLine;
import com.axelor.apps.account.db.AccountingReportValue;
import com.axelor.apps.account.db.AnalyticAccount;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.account.db.repo.AccountingReportConfigLineRepository;
import com.axelor.apps.account.db.repo.AccountingReportValueRepository;
import com.axelor.apps.account.db.repo.AnalyticAccountRepository;
import com.axelor.apps.account.db.repo.MoveLineRepository;
import com.axelor.apps.account.service.custom.AccountingReportValueMoveLineServiceImpl;
import com.axelor.apps.account.service.move.MoveToolService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.service.DateService;
import com.axelor.common.StringUtils;
import com.axelor.db.JPA;
import com.google.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.commons.collections.CollectionUtils;

/**
 * СМЕТКИТЕ НА РЕДА СЕ СЕКАТ ПО КОД — И САМО В ДРУЖЕСТВОТО НА СПРАВКАТА.
 *
 * <p>AOS ({@code getAccountSet} → {@code mergeWithAccountCode} → {@code mergeSets}) сече {@code
 * accountSet} на реда с намерените по {@code LIKE} на {@code accountCode} — по ОБЕКТ. В заварена
 * база с няколко дружества {@code accountSet} може да сочи сметките на първото (вносът на AOS ги
 * търси без дружество, виж {@link BgImportAccountingReportConfigLine}), сечението оставя само тях,
 * а заявката за статиите е по дружеството на справката — баланс и ОПР на всяко следващо дружество
 * излизат нули.
 *
 * <p>Тук:
 *
 * <ol>
 *   <li>сечението е по КОД: от намерените по {@code LIKE} остават сметките, чийто код е сред
 *       кодовете на {@code accountSet};
 *   <li>наборът изрично съдържа само сметки на дружествата на справката ({@code companySet}). Не се
 *       разчита на филтъра по дружество в заявката за статиите — така сума от сметка на друго
 *       дружество не може да влезе в справката по никой път.
 * </ol>
 *
 * <p>В база с едно дружество резултатът е същият като на AOS: кодът на сметката е единствен в
 * дружеството, тоест сечението по код и по обект съвпадат, а филтърът не маха нищо.
 *
 * <p>{@code companySet} не стига до {@code getAccountSet} като параметър, затова се държи за
 * времето на {@link #createValueFromMoveLines} в {@link ThreadLocal} и се връща след него.
 *
 * <p><b>Подсметките и складовите статии</b> ({@link BgReportAccountMap}): подсметка без свой ред
 * влиза в реда на най-близкия изброен предшественик (5042 → редът на 504), а токен {@code 702@30}
 * разделя сметката по статии — себестойността на продадените стоки (статиите със 30x) от прихода.
 */
public class BgAccountingReportValueMoveLineServiceImpl
    extends AccountingReportValueMoveLineServiceImpl {

  private static final ThreadLocal<Set<Long>> REPORT_COMPANY_IDS = new ThreadLocal<>();

  /**
   * Съответствието по справка и дружества — за времето на едно изчисление ({@link #openCache}). Без
   * отворен кеш се смята при всяко извикване.
   */
  private static final ThreadLocal<Map<String, Map<Long, BgReportAccountMap.Resolved>>> CACHE =
      new ThreadLocal<>();

  @Inject
  public BgAccountingReportValueMoveLineServiceImpl(
      AccountRepository accountRepository,
      AccountingReportValueRepository accountingReportValueRepo,
      AnalyticAccountRepository analyticAccountRepo,
      MoveLineRepository moveLineRepo,
      DateService dateService,
      MoveToolService moveToolService) {
    super(
        accountRepository,
        accountingReportValueRepo,
        analyticAccountRepo,
        moveLineRepo,
        dateService,
        moveToolService);
  }

  @Override
  public void createValueFromMoveLines(
      AccountingReport accountingReport,
      AccountingReportConfigLine groupColumn,
      AccountingReportConfigLine column,
      AccountingReportConfigLine line,
      Map<String, Map<String, AccountingReportValue>> valuesMapByColumn,
      Map<String, Map<String, AccountingReportValue>> valuesMapByLine,
      Account groupAccount,
      Set<Company> companySet,
      AnalyticAccount configAnalyticAccount,
      String parentTitle,
      LocalDate startDate,
      LocalDate endDate,
      int analyticCounter)
      throws AxelorException {
    Set<Long> previous = enter(companySet);
    try {
      super.createValueFromMoveLines(
          accountingReport,
          groupColumn,
          column,
          line,
          valuesMapByColumn,
          valuesMapByLine,
          groupAccount,
          companySet,
          configAnalyticAccount,
          parentTitle,
          startDate,
          endDate,
          analyticCounter);
    } finally {
      leave(previous);
    }
  }

  /**
   * Сметките, по които справката за {@code companySet} смята реда — същият път като изчислението.
   */
  public Set<Account> getReportAccountSet(
      AccountingReportConfigLine line, Set<Company> companySet) {
    Set<Long> previous = enter(companySet);
    try {
      return this.getAccountSet(line, line.getAccountTypeSet());
    } finally {
      leave(previous);
    }
  }

  private static Set<Long> enter(Set<Company> companySet) {
    Set<Long> previous = REPORT_COMPANY_IDS.get();
    REPORT_COMPANY_IDS.set(companyIds(companySet));
    return previous;
  }

  private static void leave(Set<Long> previous) {
    if (previous == null) {
      REPORT_COMPANY_IDS.remove();
    } else {
      REPORT_COMPANY_IDS.set(previous);
    }
  }

  @Override
  protected Set<Account> getAccountSet(
      AccountingReportConfigLine configLine, Set<AccountType> accountTypeSet) {
    Set<Account> accountSet = configLine.getAccountSet();
    accountSet = this.mergeWithAccountTypes(accountSet, accountTypeSet);
    accountSet = this.mergeWithAccountCode(accountSet, configLine.getAccountCode());
    accountSet = onlyCompanies(accountSet, REPORT_COMPANY_IDS.get());
    BgReportAccountMap.Resolved resolved = resolved(configLine);
    if (resolved != null && !resolved.extra.isEmpty()) {
      Set<Account> withExtra = accountSet == null ? new HashSet<>() : new HashSet<>(accountSet);
      for (Long id : resolved.extra) {
        withExtra.add(accountRepo.find(id));
      }
      accountSet = withExtra;
    }
    return this.sortSet(accountSet, Comparator.comparing(Account::getLabel));
  }

  /** Само статиите със/без сметка 30x за сметките, които ред {@code @30} дели (виж класа). */
  @Override
  protected String getMoveLineQuery(
      AccountingReport accountingReport,
      Set<Long> accountIdSet,
      Set<AnalyticAccount> analyticAccountSet,
      Set<Company> companySet,
      AccountingReportConfigLine groupColumn,
      AccountingReportConfigLine column,
      AccountingReportConfigLine line) {
    String query =
        super.getMoveLineQuery(
            accountingReport,
            accountIdSet,
            analyticAccountSet,
            companySet,
            groupColumn,
            column,
            line);
    Set<Long> previous = REPORT_COMPANY_IDS.get();
    if (previous == null) {
      REPORT_COMPANY_IDS.set(companyIds(companySet));
    }
    try {
      return query + stockScope(resolved(line));
    } finally {
      if (previous == null) {
        REPORT_COMPANY_IDS.remove();
      }
    }
  }

  static String stockScope(BgReportAccountMap.Resolved resolved) {
    if (resolved == null) {
      return "";
    }
    String stock =
        "EXISTS (SELECT 1 FROM MoveLine bgStock WHERE bgStock.move = self.move"
            + " AND bgStock.account.code LIKE '"
            + BgReportAccountMap.STOCK_ACCOUNT_PREFIX
            + "%')";
    StringBuilder sb = new StringBuilder();
    if (!resolved.withStock.isEmpty()) {
      sb.append(" AND (self.account.id NOT IN ")
          .append(ids(resolved.withStock))
          .append(" OR ")
          .append(stock)
          .append(")");
    }
    if (!resolved.withoutStock.isEmpty()) {
      sb.append(" AND (self.account.id NOT IN ")
          .append(ids(resolved.withoutStock))
          .append(" OR NOT ")
          .append(stock)
          .append(")");
    }
    return sb.toString();
  }

  private static String ids(Set<Long> ids) {
    return new TreeSet<>(ids)
        .stream().map(String::valueOf).collect(Collectors.joining(",", "(", ")"));
  }

  /**
   * Отваря кеша на съответствието за едно изчисление на справка; {@link #closeCache} го затваря.
   */
  public static boolean openCache() {
    if (CACHE.get() != null) {
      return false;
    }
    CACHE.set(new HashMap<>());
    return true;
  }

  public static void closeCache() {
    CACHE.remove();
  }

  /** Съответствието за реда — по справката му и дружествата на изчислението; null за колона. */
  protected BgReportAccountMap.Resolved resolved(AccountingReportConfigLine line) {
    if (line == null
        || line.getReportType() == null
        || line.getReportType().getId() == null
        || line.getId() == null) {
      return null;
    }
    Long reportTypeId = line.getReportType().getId();
    Set<Long> companyIds = REPORT_COMPANY_IDS.get();
    String key = reportTypeId + "|" + (companyIds == null ? "*" : new TreeSet<>(companyIds));
    Map<String, Map<Long, BgReportAccountMap.Resolved>> cache = CACHE.get();
    Map<Long, BgReportAccountMap.Resolved> map = cache == null ? null : cache.get(key);
    if (map == null) {
      map = BgReportAccountMap.resolve(reportLines(reportTypeId), accounts(companyIds));
      if (cache != null) {
        cache.put(key, map);
      }
    }
    return map.get(line.getId());
  }

  protected List<BgReportAccountMap.Line> reportLines(Long reportTypeId) {
    List<BgReportAccountMap.Line> out = new ArrayList<>();
    for (Object[] r :
        JPA.em()
            .createQuery(
                "SELECT l.id, l.accountCode FROM AccountingReportConfigLine l"
                    + " WHERE l.reportType.id = :rt AND l.ruleTypeSelect = :sum",
                Object[].class)
            .setParameter("rt", reportTypeId)
            .setParameter("sum", AccountingReportConfigLineRepository.RULE_TYPE_SUM_OF_ACCOUNTS)
            .getResultList()) {
      out.add(new BgReportAccountMap.Line((Long) r[0], (String) r[1]));
    }
    return out;
  }

  protected List<BgReportAccountMap.Acc> accounts(Set<Long> companyIds) {
    boolean byCompany = companyIds != null && !companyIds.isEmpty();
    javax.persistence.TypedQuery<Object[]> q =
        JPA.em()
            .createQuery(
                "SELECT a.id, a.code, p.id FROM Account a LEFT JOIN a.parentAccount p"
                    + (byCompany ? " WHERE a.company.id IN :companies" : ""),
                Object[].class);
    if (byCompany) {
      q.setParameter("companies", companyIds);
    }
    List<BgReportAccountMap.Acc> out = new ArrayList<>();
    for (Object[] r : q.getResultList()) {
      out.add(new BgReportAccountMap.Acc((Long) r[0], (String) r[1], (Long) r[2]));
    }
    return out;
  }

  /**
   * Сечение по КОД: от намерените по {@code LIKE} — тези с код сред кодовете на {@code accountSet}.
   */
  @Override
  protected Set<Account> mergeWithAccountCode(Set<Account> accountSet, String accountCode) {
    if (StringUtils.isEmpty(accountCode)) {
      return accountSet;
    }
    Set<Account> found =
        this.getSetFromStream(
            Arrays.stream(accountCode.split(",")),
            token ->
                accountRepo
                    .all()
                    .filter("self.code LIKE ?", BgReportAccountMap.pattern(token))
                    .fetch());
    if (CollectionUtils.isEmpty(accountSet)) {
      return found;
    }
    if (CollectionUtils.isEmpty(found)) {
      return accountSet;
    }
    Set<String> codes =
        accountSet.stream()
            .map(Account::getCode)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    return found.stream().filter(a -> codes.contains(a.getCode())).collect(Collectors.toSet());
  }

  /**
   * Само сметките на дружествата на справката. Без {@code companySet} (извикване извън {@link
   * #createValueFromMoveLines}) наборът остава — тогава и заявката на AOS не е по дружество.
   */
  static Set<Account> onlyCompanies(Set<Account> accountSet, Set<Long> companyIds) {
    if (accountSet == null || companyIds == null || companyIds.isEmpty()) {
      return accountSet;
    }
    return accountSet.stream()
        .filter(a -> a.getCompany() != null && companyIds.contains(a.getCompany().getId()))
        .collect(Collectors.toCollection(HashSet::new));
  }

  static Set<Long> companyIds(Set<Company> companySet) {
    if (companySet == null) {
      return null;
    }
    return companySet.stream()
        .filter(Objects::nonNull)
        .map(Company::getId)
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }
}

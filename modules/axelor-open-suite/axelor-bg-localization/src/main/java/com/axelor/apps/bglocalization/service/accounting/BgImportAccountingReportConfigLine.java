package com.axelor.apps.bglocalization.service.accounting;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountingReportConfigLine;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.account.db.repo.AccountTypeRepository;
import com.axelor.apps.account.db.repo.AccountingReportConfigLineRepository;
import com.axelor.csv.script.ImportAccountingReportConfigLine;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ВНОСЪТ НА РЕДОВЕТЕ НА СПРАВКИТЕ СВЪРЗВА СМЕТКИТЕ НА СВОЕТО ДРУЖЕСТВО.
 *
 * <p>AOS ({@link ImportAccountingReportConfigLine#setAccounts}) търси сметката на реда по {@code
 * self.code = :code} БЕЗ дружество и взема първата намерена. Във втората компания от една база
 * {@code accountSet} на всеки ред сочи сметките на първата, а изчислението сече с тях — баланс и
 * ОПР по BG_COA дават нули.
 *
 * <p>Вносът от шаблон носи дружеството в {@code _companyId} ({@code
 * AccountingConfigTemplateService}); тогава сметките (и резервното търсене без крайни нули) се
 * търсят само в него. Без {@code _companyId} — демо данните на AOS — поведението е старото.
 * Заварените бази не зависят от това: изчислението и без него сече по код в сметките на дружеството
 * на справката ({@link BgAccountingReportValueMoveLineServiceImpl}).
 */
public class BgImportAccountingReportConfigLine extends ImportAccountingReportConfigLine {

  static final String COMPANY_ID = "_companyId";
  static final String ACCOUNT_CODE = "accountCode";

  protected final AccountRepository accountRepository;

  @Inject
  public BgImportAccountingReportConfigLine(
      AccountRepository accountRepo,
      AccountTypeRepository accountTypeRepo,
      AccountingReportConfigLineRepository accountingReportConfigLineRepo) {
    super(accountRepo, accountTypeRepo, accountingReportConfigLineRepo);
    this.accountRepository = accountRepo;
  }

  @Override
  @Transactional
  public Object setAccounts(Object bean, Map<String, Object> values) {
    List<Long> companyIds = companyIds(values.get(COMPANY_ID));
    String accountValues = (String) values.get(ACCOUNT_CODE);
    AccountingReportConfigLine configLine = (AccountingReportConfigLine) bean;
    if (companyIds.isEmpty()
        || accountValues == null
        || accountValues.isEmpty()
        || configLine.getRuleTypeSelect()
            != AccountingReportConfigLineRepository.RULE_TYPE_SUM_OF_ACCOUNTS) {
      return super.setAccounts(bean, values);
    }
    configLine.setAccountSet(accountsOfCompany(accountValues, companyIds));
    // Видовете сметки и записът — от AOS; кодовете вече са свързани по дружество.
    Map<String, Object> withoutCodes = new HashMap<>(values);
    withoutCodes.remove(ACCOUNT_CODE);
    return super.setAccounts(bean, withoutCodes);
  }

  protected Set<Account> accountsOfCompany(String accountValues, List<Long> companyIds) {
    Set<Account> accounts = new HashSet<>();
    List<Account> companyAccounts = null;
    for (String code : accountValues.split("\\|")) {
      Account account =
          accountRepository
              .all()
              .filter("self.code = :code AND self.company.id IN :companyIds")
              .bind("code", code)
              .bind("companyIds", companyIds)
              .fetchOne();
      if (account == null) {
        if (companyAccounts == null) {
          companyAccounts =
              accountRepository
                  .all()
                  .filter("self.company.id IN :companyIds")
                  .bind("companyIds", companyIds)
                  .fetch();
        }
        account =
            companyAccounts.stream()
                .filter(a -> a.getCode() != null && compareCodes(a.getCode(), code))
                .findFirst()
                .orElse(null);
      }
      if (account != null) {
        accounts.add(account);
      }
    }
    return accounts;
  }

  /** {@code _companyId} е число от вноса на шаблон или „1|2“ при справка за няколко дружества. */
  static List<Long> companyIds(Object value) {
    List<Long> ids = new ArrayList<>();
    if (value instanceof Number) {
      ids.add(((Number) value).longValue());
    } else if (value != null) {
      for (String token : value.toString().split("\\|")) {
        String id = token.trim();
        if (!id.isEmpty()) {
          ids.add(Long.valueOf(id));
        }
      }
    }
    return ids;
  }
}

package com.axelor.apps.bglocalization.service.accounting;

import com.axelor.apps.account.db.AccountingReport;
import com.axelor.apps.account.db.AccountingReportConfigLine;
import com.axelor.apps.account.db.AccountingReportValue;
import com.axelor.apps.account.db.AnalyticAccount;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.account.db.repo.AccountingReportConfigLineRepository;
import com.axelor.apps.account.db.repo.AccountingReportValueRepository;
import com.axelor.apps.account.db.repo.AnalyticAccountRepository;
import com.axelor.apps.account.service.custom.AccountingReportValueCustomRuleService;
import com.axelor.apps.account.service.custom.AccountingReportValueMoveLineService;
import com.axelor.apps.account.service.custom.AccountingReportValuePercentageService;
import com.axelor.apps.account.service.custom.AccountingReportValueServiceImpl;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.base.service.DateService;
import com.axelor.apps.base.service.app.AppBaseService;
import com.google.inject.Inject;
import java.util.Map;

/**
 * РЕД „X“ НЕ Е „ВЕЧЕ ИЗЧИСЛЕН“ САМО ЗАЩОТО ИМА РЕДОВЕ „X_701“.
 *
 * <p>AOS (`AccountingReportValueServiceImpl#isValueAlreadyComputed`) смята ред X за изчислен, ако
 * всички ключове {@code X_<цифри>} в колоната имат стойност — това е именуването на ДЕТАЙЛНИТЕ
 * редове при разбивка по сметка или по вид сметка ({@code detailBySelect}). BG_COA обаче има
 * обикновени скрити редове с такива кодове: {@code R_INC_701} … и {@code R_EXP_601} … в баланса
 * (28, 46), {@code MO_TAX_124} в ОПР по МСС (45). Щом те се изчислят, родителите {@code R_INC},
 * {@code R_EXP} и {@code MO_TAX} никога не се смятат, остават без стойност и справката спира с
 * „couldn't be computed (timeout)“.
 *
 * <p>Кодовете не се преименуват: миграциите за заварените бази ги търсят по име (N2, SAF-T
 * референтните таблици). Затова правилото се стеснява: шаблонът {@code X_<цифри>} значи детайли
 * само когато редът X ИМА разбивка. Без разбивка — редът се смята сам, както всеки друг.
 */
public class BgAccountingReportValueServiceImpl extends AccountingReportValueServiceImpl {

  @Inject
  public BgAccountingReportValueServiceImpl(
      AccountRepository accountRepo,
      AccountingReportValueRepository accountingReportValueRepo,
      AccountingReportValueCustomRuleService accountingReportValueCustomRuleService,
      AccountingReportValueMoveLineService accountingReportValueMoveLineService,
      AccountingReportValuePercentageService accountingReportValuePercentageService,
      AppBaseService appBaseService,
      AnalyticAccountRepository analyticAccountRepo,
      DateService dateService,
      TraceBackRepository traceBackRepository) {
    super(
        accountRepo,
        accountingReportValueRepo,
        accountingReportValueCustomRuleService,
        accountingReportValueMoveLineService,
        accountingReportValuePercentageService,
        appBaseService,
        analyticAccountRepo,
        dateService,
        traceBackRepository);
  }

  /**
   * Съответствието сметка → ред ({@link BgReportAccountMap}) се смята веднъж за изчислението, не за
   * всеки ред и колона.
   */
  @Override
  public void computeReportValues(AccountingReport accountingReport) throws AxelorException {
    boolean opened = BgAccountingReportValueMoveLineServiceImpl.openCache();
    try {
      super.computeReportValues(accountingReport);
    } finally {
      if (opened) {
        BgAccountingReportValueMoveLineServiceImpl.closeCache();
      }
    }
  }

  @Override
  protected boolean isValueAlreadyComputed(
      AccountingReportConfigLine groupColumn,
      AccountingReportConfigLine column,
      AccountingReportConfigLine line,
      Map<String, Map<String, AccountingReportValue>> valuesMapByColumn,
      AnalyticAccount configAnalyticAccount,
      String parentTitle) {
    if (line.getDetailBySelect() == AccountingReportConfigLineRepository.DETAIL_BY_NOTHING) {
      String columnCode =
          this.getColumnCode(column.getCode(), parentTitle, groupColumn, configAnalyticAccount);
      return valuesMapByColumn.get(columnCode).get(line.getCode()) != null;
    }
    return super.isValueAlreadyComputed(
        groupColumn, column, line, valuesMapByColumn, configAnalyticAccount, parentTitle);
  }
}

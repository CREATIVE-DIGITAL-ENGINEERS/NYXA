package com.axelor.apps.bglocalization.service.demo;

import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.CurrencyConversionLine;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.studio.db.AppBase;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * КУРСОВЕТЕ НА ЕЦБ ЗА ПЕРИОДА — {@code rates.csv}: {@code yearShift;date;currency;rate}.
 *
 * <p>Курсът е единици валута за 1 евро (референтният курс на ЕЦБ), датата е „ММ-ДД“, а {@code
 * yearShift = -1} е за 31.12 на предходната година (встъпителните салда). Числата са публикуваните
 * от ЕЦБ курсове за USD и CHF за 2026 г.; в друга година датите се преместват, а числата остават
 * същите — наборът е демонстрационен.
 *
 * <p>⚠️ КУРСОВЕТЕ СА ОБЩИ ЗА ВСИЧКИ ДРУЖЕСТВА. Затова стъпката прави само това, което прави и
 * задачата за курсовете на ЕЦБ ({@code ECBCurrencyConversionService}): за ден без собствен ред
 * затваря отворения ред на двойката и добавя нов. Ден, който вече има курс (ред от тази дата или
 * затворен период, който го покрива), не се пипа — заварената история е по-силна от набора.
 */
final class BgDemoRatesStep {

  private BgDemoRatesStep() {}

  static void run(BgDemoContext ctx) throws Exception {
    BgDemoTx.run(
        () -> {
          AppBase appBase = Beans.get(AppBaseService.class).getAppBase();
          Currency eur = BgDemoCompanyStep.currency("EUR");
          int added = 0;
          for (BgDemoCsv.Row r : ctx.rows("rates")) {
            LocalDate date = ctx.dates().of(r.get("date"));
            if (r.integer("yearShift") != 0) {
              date = date.plusYears(r.integer("yearShift"));
            }
            Currency to = BgDemoCompanyStep.currency(r.get("currency"));
            if (add(appBase, eur, to, date, r)) {
              added++;
            }
          }
          ctx.log("Курсове на ЕЦБ: добавени " + added);
        });
  }

  private static boolean add(
      AppBase appBase, Currency from, Currency to, LocalDate date, BgDemoCsv.Row r) {
    List<CurrencyConversionLine> covering =
        JPA.all(CurrencyConversionLine.class)
            .filter(
                "self.startCurrency = ?1 AND self.endCurrency = ?2 AND self.fromDate <= ?3"
                    + " AND (self.toDate IS NULL OR self.toDate >= ?3)",
                from,
                to,
                date)
            .fetch();
    List<CurrencyConversionLine> open = new ArrayList<>();
    for (CurrencyConversionLine l : covering) {
      if (l.getFromDate().equals(date) || l.getToDate() != null) {
        return false; // денят вече има курс
      }
      open.add(l);
    }
    for (CurrencyConversionLine l : open) {
      l.setToDate(date.minusDays(1));
      JPA.save(l);
    }
    // Следващ ред по дата (ако ЕЦБ задачата вече е добавила по-нов) ограничава новия.
    CurrencyConversionLine next =
        JPA.all(CurrencyConversionLine.class)
            .filter(
                "self.startCurrency = ?1 AND self.endCurrency = ?2 AND self.fromDate > ?3",
                from,
                to,
                date)
            .order("fromDate")
            .fetchOne();
    CurrencyConversionLine ccl = new CurrencyConversionLine();
    ccl.setStartCurrency(from);
    ccl.setEndCurrency(to);
    ccl.setFromDate(date);
    ccl.setToDate(next == null ? null : next.getFromDate().minusDays(1));
    ccl.setExchangeRate(r.decimal("rate"));
    ccl.setAppBase(appBase);
    JPA.save(ccl);
    return true;
  }
}

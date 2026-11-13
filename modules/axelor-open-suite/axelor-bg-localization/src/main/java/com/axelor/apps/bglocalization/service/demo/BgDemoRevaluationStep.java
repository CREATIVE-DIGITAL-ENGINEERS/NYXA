package com.axelor.apps.bglocalization.service.demo;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.move.MoveCreateService;
import com.axelor.apps.account.service.move.MoveValidateService;
import com.axelor.apps.account.service.moveline.MoveLineCreateService;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.service.CurrencyService;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * ПРЕОЦЕНКА НА ВАЛУТНАТА БАНКОВА СМЕТКА КЪМ КРАЯ НА ТРИМЕСЕЧИЕТО — {@code revaluation.csv}: {@code
 * date;account;currency}.
 *
 * <p>Nyxa и Axelor нямат услуга за преоценка на валутни салда (няма такъв batch). Затова тя е ръчна
 * статия в дневник „Корекции и преоценки“ (ADJ), както я пуска счетоводителят: салдото на сметката
 * във валута (сборът на сумите във валута на редовете от статиите в тази валута) по курса на ЕЦБ
 * към датата минус балансовата му стойност в евро (редовете в тази валута и предишните преоценки на
 * същата валута — по 504 може да има салда в няколко валути); положителната разлика — Дт сметката /
 * Кт 724, отрицателната — Дт 624 / Кт сметката. Статията е в евро и не мени салдото във валута.
 *
 * <p>Преоценява се само банковата сметка (504). Вземанията и задълженията във валута НЕ: Axelor
 * смята курсовата разлика при плащането спрямо курса на фактурата, а преоценка на 401/411 без
 * сторно би я удвоила. Годишната преоценка на вземанията и задълженията към 31.12 е извън периода
 * 01–10.
 */
final class BgDemoRevaluationStep {

  private BgDemoRevaluationStep() {}

  static List<BgDemoEvent> events(BgDemoContext ctx) {
    List<BgDemoEvent> out = new ArrayList<>();
    for (BgDemoCsv.Row r : ctx.rows("revaluation")) {
      LocalDate date = ctx.dates().of(r.get("date"));
      out.add(
          new BgDemoEvent(
              date,
              BgDemoEvent.RANK_MONTH_END + 5,
              "преоценка на " + r.get("account") + " " + r.get("currency"),
              () -> post(ctx, r, date)));
    }
    return out;
  }

  /**
   * Произходът на статията — по него преоценките на една валута се броят в балансовата ѝ стойност
   * (по 504 може да има салда в няколко валути).
   */
  static String originPrefix(Account account, Currency foreign) {
    return "ПРЕОЦ-" + account.getCode() + "-" + foreign.getCodeISO() + "-";
  }

  static void post(BgDemoContext ctx, BgDemoCsv.Row r, LocalDate date) throws Exception {
    Long id =
        BgDemoTx.call(
            () -> {
              Company company = ctx.company();
              Account account = BgDemoPartnerStep.account(company, r.get("account"));
              Currency foreign = BgDemoCompanyStep.currency(r.get("currency"));
              Currency eur = company.getCurrency();
              Object[] balance =
                  JPA.em()
                      .createQuery(
                          "SELECT COALESCE(SUM(ml.debit - ml.credit), 0),"
                              + " COALESCE(SUM(CASE WHEN ml.move.currency = :foreign"
                              + " THEN ml.currencyAmount ELSE 0 END), 0)"
                              + " FROM MoveLine ml WHERE ml.move.company = :company"
                              + " AND ml.account = :account AND ml.date <= :date"
                              + " AND (ml.move.currency = :foreign OR ml.origin LIKE :revalued)"
                              + " AND ml.move.statusSelect IN (:statuses)",
                          Object[].class)
                      .setParameter("foreign", foreign)
                      .setParameter("company", company)
                      .setParameter("account", account)
                      .setParameter("date", date)
                      .setParameter("revalued", originPrefix(account, foreign) + "%")
                      .setParameter(
                          "statuses",
                          List.of(MoveRepository.STATUS_ACCOUNTED, MoveRepository.STATUS_DAYBOOK))
                      .getSingleResult();
              BigDecimal book = (BigDecimal) balance[0];
              BigDecimal inForeign = (BigDecimal) balance[1];
              BigDecimal target =
                  Beans.get(CurrencyService.class)
                      .getAmountCurrencyConvertedAtDate(foreign, eur, inForeign, date)
                      .setScale(2, RoundingMode.HALF_UP);
              BigDecimal diff = target.subtract(book).setScale(2, RoundingMode.HALF_UP);
              ctx.log(
                  "Преоценка "
                      + account.getCode()
                      + " към "
                      + date
                      + ": "
                      + inForeign.setScale(2, RoundingMode.HALF_UP)
                      + " "
                      + foreign.getCodeISO()
                      + ", балансово "
                      + book
                      + " EUR, по курса "
                      + target
                      + " EUR, разлика "
                      + diff);
              if (diff.signum() == 0) {
                return null;
              }
              Journal journal =
                  JPA.all(Journal.class)
                      .filter("self.company = ?1 AND self.code = 'ADJ'", company)
                      .fetchOne();
              String origin = originPrefix(account, foreign) + date;
              String text =
                  "Преоценка на "
                      + account.getCode()
                      + " ("
                      + foreign.getCodeISO()
                      + ") към "
                      + date;
              Move move =
                  Beans.get(MoveCreateService.class)
                      .createMove(
                          journal,
                          company,
                          eur,
                          null,
                          date,
                          date,
                          null,
                          null,
                          MoveRepository.TECHNICAL_ORIGIN_ENTRY,
                          0,
                          origin,
                          text,
                          null);
              boolean gain = diff.signum() > 0;
              BigDecimal amount = diff.abs();
              Account result = BgDemoPartnerStep.account(company, gain ? "724" : "624");
              MoveLineCreateService lines = Beans.get(MoveLineCreateService.class);
              move.addMoveLineListItem(
                  lines.createMoveLine(
                      move,
                      null,
                      account,
                      amount,
                      amount,
                      BigDecimal.ONE,
                      gain,
                      date,
                      date,
                      date,
                      1,
                      origin,
                      text));
              move.addMoveLineListItem(
                  lines.createMoveLine(
                      move,
                      null,
                      result,
                      amount,
                      amount,
                      BigDecimal.ONE,
                      !gain,
                      date,
                      date,
                      date,
                      2,
                      origin,
                      text));
              return Beans.get(MoveRepository.class).save(move).getId();
            });
    if (id != null) {
      BgDemoTx.run(() -> Beans.get(MoveValidateService.class).accounting(JPA.find(Move.class, id)));
    }
  }
}

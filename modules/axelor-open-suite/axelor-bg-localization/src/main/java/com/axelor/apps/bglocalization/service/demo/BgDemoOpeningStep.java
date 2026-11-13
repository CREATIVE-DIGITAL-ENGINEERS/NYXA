package com.axelor.apps.bglocalization.service.demo;

import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.move.MoveCreateService;
import com.axelor.apps.account.service.move.MoveValidateService;
import com.axelor.apps.account.service.moveline.MoveLineCreateService;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Partner;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ВСТЪПИТЕЛНАТА СТАТИЯ КЪМ 01.01 — {@code opening.csv}: {@code
 * move;currency;rate;account;partner;debit;credit;label}.
 *
 * <p>Една статия на група {@code move} — в Axelor статията е в една валута, затова салдата в USD
 * (банката в USD и задължението към доставчика от САЩ) са в отделна статия със същия дневник.
 * Сумите са във валутата на статията; {@code rate} е курсът на ЕЦБ (единици валута за 1 евро) към
 * 31.12 на предходната година — сумата в евро е сума / курс. Дневникът е „Начален баланс“ (RPB),
 * произходът — „откриване“, и статията се осчетоводява, както ръчно въведена.
 */
final class BgDemoOpeningStep {

  private BgDemoOpeningStep() {}

  static List<BgDemoEvent> events(BgDemoContext ctx) {
    Map<String, List<BgDemoCsv.Row>> moves = new LinkedHashMap<>();
    for (BgDemoCsv.Row r : ctx.rows("opening")) {
      moves.computeIfAbsent(r.get("move"), k -> new ArrayList<>()).add(r);
    }
    List<BgDemoEvent> out = new ArrayList<>();
    for (Map.Entry<String, List<BgDemoCsv.Row>> e : moves.entrySet()) {
      out.add(
          new BgDemoEvent(
              ctx.dates().first(),
              BgDemoEvent.RANK_RATES + 1,
              "встъпителна статия " + e.getKey(),
              () -> post(ctx, e.getKey(), e.getValue())));
    }
    return out;
  }

  static void post(BgDemoContext ctx, String key, List<BgDemoCsv.Row> rows) throws Exception {
    Long id =
        BgDemoTx.call(
            () -> {
              Company company = ctx.company();
              BgDemoCsv.Row head = rows.get(0);
              Currency currency = BgDemoCompanyStep.currency(head.get("currency"));
              BigDecimal rate = head.has("rate") ? head.decimal("rate") : BigDecimal.ONE;
              Journal journal =
                  JPA.all(Journal.class)
                      .filter("self.company = ?1 AND self.code = 'RPB'", company)
                      .fetchOne();
              LocalDate date = ctx.dates().first();
              Move move =
                  Beans.get(MoveCreateService.class)
                      .createMove(
                          journal,
                          company,
                          currency,
                          null,
                          date,
                          date,
                          null,
                          null,
                          MoveRepository.TECHNICAL_ORIGIN_ENTRY,
                          MoveRepository.FUNCTIONAL_ORIGIN_OPENING,
                          "НАЧ-" + key,
                          "Встъпителни салда към " + date,
                          null);
              int counter = 1;
              for (BgDemoCsv.Row r : rows) {
                Partner partner =
                    r.has("partner") ? ctx.find(Partner.class, "partner", r.get("partner")) : null;
                boolean debit = r.decimal("debit").signum() != 0;
                BigDecimal amount = debit ? r.decimal("debit") : r.decimal("credit");
                BigDecimal companyAmount = amount.divide(rate, 2, RoundingMode.HALF_UP);
                MoveLine line =
                    Beans.get(MoveLineCreateService.class)
                        .createMoveLine(
                            move,
                            partner,
                            BgDemoPartnerStep.account(company, r.get("account")),
                            amount,
                            companyAmount,
                            BigDecimal.ONE.divide(rate, 6, RoundingMode.HALF_UP),
                            debit,
                            date,
                            date,
                            date,
                            counter++,
                            "НАЧ-" + key,
                            r.get("label"));
                move.addMoveLineListItem(line);
              }
              return Beans.get(MoveRepository.class).save(move).getId();
            });
    BgDemoTx.run(() -> Beans.get(MoveValidateService.class).accounting(JPA.find(Move.class, id)));
    ctx.log("Встъпителна статия " + key + ": " + rows.size() + " реда");
  }
}

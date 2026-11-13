package com.axelor.apps.bglocalization.service.nsi;

import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.service.accounting.BgAccountingCurrencyPolicy;
import com.axelor.db.JPA;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.persistence.TypedQuery;

/**
 * САЛДАТА И ОБОРОТИТЕ ПО СМЕТКИ ЗА ГОД — САМО ЗА ЕДНО ДРУЖЕСТВО.
 *
 * <p><b>Кои статии.</b> Предикатът на SAF-T ({@code BgSaftMoveScope}): осчетоводени (без DAYBOOK и
 * без черновите), без „да не се отчита“, само на дружеството на справката. {@code
 * functionalOriginSelect} и {@code ignoreInAccountingOk} могат да са NULL в базата — оттам
 * изричните {@code IS NULL}.
 *
 * <p><b>Салдо към 31.12.Г</b> — всички статии до датата БЕЗ закриващите статии на година Г:
 * закриването нулира сметките и би изтрило баланса. Закриването на Г−1 и откриването на Г остават —
 * те се неутрализират взаимно.
 *
 * <p><b>Оборот за Г</b> — без откриващите и без закриващите статии: откриването е начално салдо, а
 * закриването прехвърля 6xx/7xx към резултата и би нулирало ОПР.
 *
 * <p><b>Валутата е по датата</b> ({@link BgAccountingCurrencyPolicy#expectedCurrency}): до
 * 31.12.2025 — левове, от 01.01.2026 — евро. Салдото се връща на две части, превалутирането е на
 * сбора, не на редовете.
 */
public class BgNsiGodLedger {

  private static final String SCOPE =
      "self.move.company.id = :companyId"
          + " AND self.move.statusSelect = :posted"
          + " AND (self.move.ignoreInAccountingOk = false OR self.move.ignoreInAccountingOk IS NULL)";

  /** Сметка 30x в същата статия — изписване на запас (продажба, брак, вътрешно потребление). */
  private static final String HAS_STOCK_LINE =
      " AND EXISTS (SELECT o.id FROM MoveLine o WHERE o.move = self.move"
          + " AND o.account.code LIKE '30%')";

  /**
   * Салдо (дебит − кредит) по сметка и по ключ „партньор|банкова сметка|дневник“ на реда, на части:
   * [левове до 31.12.2025, евро след това]. Кое от трите разделя салдото, решава образецът
   * (BgNsiGodForm#splitMode) — тук се връщат и трите, за да не се пита базата по три пъти.
   */
  public Map<String, Map<String, BigDecimal[]>> balances(
      Company company, LocalDate to, int closedYear) {
    Map<String, Map<String, BigDecimal[]>> out = new HashMap<>();
    LocalDate lastBgn = BgAccountingCurrencyPolicy.TRANSITION.minusDays(1);
    collectBalance(out, 0, company, LocalDate.of(1, 1, 1), min(to, lastBgn), closedYear);
    collectBalance(out, 1, company, BgAccountingCurrencyPolicy.TRANSITION, to, closedYear);
    return out;
  }

  /** Ключът за разделяне на един ред от заявката. */
  public static String splitKey(Object partnerId, Object bankDetailsId, Object journalId) {
    return (partnerId == null ? "" : partnerId)
        + "|"
        + (bankDetailsId == null ? "" : bankDetailsId)
        + "|"
        + (journalId == null ? "" : journalId);
  }

  private void collectBalance(
      Map<String, Map<String, BigDecimal[]>> out,
      int part,
      Company company,
      LocalDate from,
      LocalDate to,
      int closedYear) {
    if (from.isAfter(to)) {
      return;
    }
    TypedQuery<Object[]> q =
        JPA.em()
            .createQuery(
                "SELECT a.code, p.id, bd.id, j.id, SUM(self.debit), SUM(self.credit)"
                    + " FROM MoveLine self JOIN self.account a JOIN self.move m JOIN m.journal j"
                    + " LEFT JOIN self.partner p LEFT JOIN m.companyBankDetails bd"
                    + " WHERE "
                    + SCOPE
                    + " AND self.date >= :from AND self.date <= :to"
                    + " AND (self.move.functionalOriginSelect IS NULL"
                    + " OR self.move.functionalOriginSelect <> :closure"
                    + " OR self.date < :closedFrom OR self.date > :closedTo)"
                    + " GROUP BY a.code, p.id, bd.id, j.id",
                Object[].class);
    bindScope(q, company);
    q.setParameter("from", from);
    q.setParameter("to", to);
    q.setParameter("closure", MoveRepository.FUNCTIONAL_ORIGIN_CLOSURE);
    q.setParameter("closedFrom", LocalDate.of(closedYear, 1, 1));
    q.setParameter("closedTo", LocalDate.of(closedYear, 12, 31));
    for (Object[] r : q.getResultList()) {
      BigDecimal[] parts =
          out.computeIfAbsent((String) r[0], k -> new HashMap<>())
              .computeIfAbsent(
                  splitKey(r[1], r[2], r[3]),
                  k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
      parts[part] = parts[part].add(nz(r[4]).subtract(nz(r[5])));
    }
  }

  /**
   * За СПБ-6А/6Б на БНБ: салдото към {@code at} по ключ „сметка|партньор|валута на статията“
   * (празна валута — статия без валута), само по редовете с партньор и само по сметки 4xx и 15x.
   * Стойността е [сума във валута до 31.12.2025, след това, отчетна сума до 31.12.2025, след това]
   * — сумата във валута е {@code currencyAmount} със знака на дебита. Закриващите статии на
   * годината на {@code at} са вън, както в {@link #balances}.
   */
  public Map<String, BigDecimal[]> partnerCurrencyBalances(Company company, LocalDate at) {
    Map<String, BigDecimal[]> out = new HashMap<>();
    LocalDate lastBgn = BgAccountingCurrencyPolicy.TRANSITION.minusDays(1);
    collectPartnerCurrency(out, 0, company, LocalDate.of(1, 1, 1), min(at, lastBgn), at.getYear());
    collectPartnerCurrency(
        out, 1, company, BgAccountingCurrencyPolicy.TRANSITION, at, at.getYear());
    return out;
  }

  private void collectPartnerCurrency(
      Map<String, BigDecimal[]> out,
      int part,
      Company company,
      LocalDate from,
      LocalDate to,
      int closedYear) {
    if (from.isAfter(to)) {
      return;
    }
    TypedQuery<Object[]> q =
        JPA.em()
            .createQuery(
                "SELECT a.code, p.id, c.codeISO, SUM(self.currencyAmount), SUM(self.debit),"
                    + " SUM(self.credit)"
                    + " FROM MoveLine self JOIN self.account a JOIN self.partner p JOIN self.move m"
                    + " LEFT JOIN m.currency c"
                    + " WHERE "
                    + SCOPE
                    + " AND (a.code LIKE '4%' OR a.code LIKE '15%')"
                    + " AND self.date >= :from AND self.date <= :to"
                    + " AND (self.move.functionalOriginSelect IS NULL"
                    + " OR self.move.functionalOriginSelect <> :closure"
                    + " OR self.date < :closedFrom OR self.date > :closedTo)"
                    + " GROUP BY a.code, p.id, c.codeISO",
                Object[].class);
    bindScope(q, company);
    q.setParameter("from", from);
    q.setParameter("to", to);
    q.setParameter("closure", MoveRepository.FUNCTIONAL_ORIGIN_CLOSURE);
    q.setParameter("closedFrom", LocalDate.of(closedYear, 1, 1));
    q.setParameter("closedTo", LocalDate.of(closedYear, 12, 31));
    for (Object[] r : q.getResultList()) {
      BigDecimal[] v =
          out.computeIfAbsent(
              r[0] + "|" + r[1] + "|" + (r[2] == null ? "" : r[2]),
              k ->
                  new BigDecimal[] {
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
                  });
      v[part] = v[part].add(nz(r[3]));
      v[2 + part] = v[2 + part].add(nz(r[4]).subtract(nz(r[5])));
    }
  }

  /**
   * Дебитен и кредитен оборот по сметка за календарната година, без откриващите и закриващите
   * статии. {@code stockOnly} — само статиите със сметка 30x.
   */
  public Map<String, BigDecimal[]> turnovers(Company company, int year, boolean stockOnly) {
    TypedQuery<Object[]> q =
        JPA.em()
            .createQuery(
                "SELECT self.account.code, SUM(self.debit), SUM(self.credit) FROM MoveLine self"
                    + " WHERE "
                    + SCOPE
                    + " AND self.date >= :from AND self.date <= :to"
                    + " AND (self.move.functionalOriginSelect IS NULL"
                    + " OR self.move.functionalOriginSelect NOT IN (:opening, :closure))"
                    + (stockOnly ? " AND self.account.code LIKE '7%'" + HAS_STOCK_LINE : "")
                    + " GROUP BY self.account.code",
                Object[].class);
    bindScope(q, company);
    q.setParameter("from", LocalDate.of(year, 1, 1));
    q.setParameter("to", LocalDate.of(year, 12, 31));
    q.setParameter("opening", MoveRepository.FUNCTIONAL_ORIGIN_OPENING);
    q.setParameter("closure", MoveRepository.FUNCTIONAL_ORIGIN_CLOSURE);
    Map<String, BigDecimal[]> out = new HashMap<>();
    List<Object[]> rows = q.getResultList();
    for (Object[] r : rows) {
      out.put((String) r[0], new BigDecimal[] {nz(r[1]), nz(r[2])});
    }
    return out;
  }

  /**
   * Оборотът по сметка за годината само в статиите, в които има ред по ДРУГА сметка с някой от
   * префиксите — „по насрещна сметка“. Например Кт 4521 срещу 123/69x е данъкът за годината, а Дт
   * 122 / Кт 4521 (корекция за минали години) остава вън; Дт 613 срещу 60x/61x е придобиване по
   * стопански начин, а Дт 613 / Кт 401 (купено от доставчик) — не. Същият обхват като {@link
   * #turnovers}.
   */
  public Map<String, BigDecimal[]> turnoversAgainst(
      Company company, int year, List<String> prefixes) {
    StringBuilder against = new StringBuilder();
    for (int i = 0; i < prefixes.size(); i++) {
      against.append(i == 0 ? "" : " OR ").append("o.account.code LIKE :p").append(i);
    }
    TypedQuery<Object[]> q =
        JPA.em()
            .createQuery(
                "SELECT self.account.code, SUM(self.debit), SUM(self.credit) FROM MoveLine self"
                    + " WHERE "
                    + SCOPE
                    + " AND self.date >= :from AND self.date <= :to"
                    + " AND (self.move.functionalOriginSelect IS NULL"
                    + " OR self.move.functionalOriginSelect NOT IN (:opening, :closure))"
                    + " AND EXISTS (SELECT o.id FROM MoveLine o WHERE o.move = self.move"
                    + " AND o.account.code <> self.account.code AND ("
                    + against
                    + "))"
                    + " GROUP BY self.account.code",
                Object[].class);
    bindScope(q, company);
    q.setParameter("from", LocalDate.of(year, 1, 1));
    q.setParameter("to", LocalDate.of(year, 12, 31));
    q.setParameter("opening", MoveRepository.FUNCTIONAL_ORIGIN_OPENING);
    q.setParameter("closure", MoveRepository.FUNCTIONAL_ORIGIN_CLOSURE);
    for (int i = 0; i < prefixes.size(); i++) {
      q.setParameter("p" + i, prefixes.get(i) + "%");
    }
    Map<String, BigDecimal[]> out = new HashMap<>();
    for (Object[] r : q.getResultList()) {
      out.put((String) r[0], new BigDecimal[] {nz(r[1]), nz(r[2])});
    }
    return out;
  }

  /**
   * Банковите сметки на дружеството: [id, id на дневника, код на счетоводната сметка] — за
   * разделянето по банкова сметка на редовете без {@code companyBankDetails} (откриване,
   * преоценка).
   */
  public List<Object[]> companyBanks(Company company) {
    return JPA.em()
        .createQuery(
            "SELECT bd.id, j.id, a.code FROM BankDetails bd LEFT JOIN bd.journal j"
                + " LEFT JOIN bd.bankAccount a"
                + " WHERE bd.company.id = :companyId OR bd.partner = :partner",
            Object[].class)
        .setParameter("companyId", company.getId())
        .setParameter("partner", company.getPartner())
        .getResultList();
  }

  private static void bindScope(TypedQuery<Object[]> q, Company company) {
    q.setParameter("companyId", company.getId());
    q.setParameter("posted", MoveRepository.STATUS_ACCOUNTED);
  }

  private static LocalDate min(LocalDate a, LocalDate b) {
    return a.isBefore(b) ? a : b;
  }

  private static BigDecimal nz(Object o) {
    return o == null ? BigDecimal.ZERO : (BigDecimal) o;
  }
}

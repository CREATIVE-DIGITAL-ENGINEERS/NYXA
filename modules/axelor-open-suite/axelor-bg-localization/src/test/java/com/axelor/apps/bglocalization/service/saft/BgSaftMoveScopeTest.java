package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.repo.SaftReportRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРЕДИКАТЪТ НА СТАТИИТЕ — СВЕРЕН СТРУКТУРНО.
 *
 * <p>⚠️ Модулът няма mock-подпора за JPA, тоест заявката не се пуска тук. Затова тестът твърди
 * ФОРМАТА ѝ: кои условия носи, кои НЕ носи, че всеки параметър има стойност и че OR никога не стои
 * на най-горното ниво — грешно поставена скоба би пуснала чужди статии в салдата, без да счупи нищо
 * видимо. Числата се проверяват в {@link BgSaftGlAccountsAssemblerTest}.
 */
class BgSaftMoveScopeTest {

  private static final List<String> QUERIES =
      List.of(
          BgSaftMoveScope.OPENING_BY_ACCOUNT,
          BgSaftMoveScope.TURNOVER_BY_ACCOUNT,
          BgSaftMoveScope.POSTED_BEFORE_YEAR,
          BgSaftMoveScope.OPENING_MOVES_IN_YEAR,
          BgSaftMoveScope.TAXES_ON_LINES,
          BgSaftMoveScope.UNTAXED_LINES,
          BgSaftMoveScope.TAXES_ON_INVOICE_LINES,
          BgSaftGlEntriesReader.LINE_IDS,
          BgSaftMasterDataReader.INVOICED_PRODUCTS,
          BgSaftMasterDataReader.STOCKED_PRODUCTS);

  /** Заявките на контрагентите — носят и {@code :accountIds}, който обхватът не познава. */
  private static final List<String> PARTY_QUERIES =
      List.of(
          BgSaftMoveScope.OPENING_BY_ACCOUNT_AND_PARTNER,
          BgSaftMoveScope.TURNOVER_BY_ACCOUNT_AND_PARTNER);

  private static Company company(long id) {
    Company company = new Company();
    company.setId(id);
    return company;
  }

  private static SaftReport april() {
    Company company = company(7L);
    Year year = new Year();
    year.setId(26L);
    year.setCompany(company);
    year.setTypeSelect(YearRepository.TYPE_FISCAL);
    year.setFromDate(LocalDate.of(2026, 1, 1));
    year.setToDate(LocalDate.of(2026, 12, 31));
    Period period = new Period();
    period.setYear(year);
    period.setFromDate(LocalDate.of(2026, 4, 1));
    period.setToDate(LocalDate.of(2026, 4, 30));
    SaftReport report = new SaftReport();
    report.setCompany(company);
    report.setPeriod(period);
    report.setReportTypeSelect(SaftReportRepository.REPORT_TYPE_MONTHLY);
    return report;
  }

  @Test
  @DisplayName("Влизат само осчетоводените статии на дружеството — без DAYBOOK и без игнорираните")
  void само_осчетоводените() {
    assertEquals(MoveRepository.STATUS_ACCOUNTED, BgSaftMoveScope.POSTED);
    String posted = BgSaftMoveScope.POSTED_LINES;
    assertTrue(posted.contains("self.move.company.id = :companyId"));
    assertTrue(posted.contains("self.move.statusSelect = :posted"));
    assertTrue(
        posted.contains(
            "(self.move.ignoreInAccountingOk = false OR self.move.ignoreInAccountingOk IS NULL)"));
    for (String query : QUERIES) {
      assertFalse(query.contains(" IN ("), "статусът е ЕДИН, не списък: " + query);
      assertFalse(query.toLowerCase().contains("daybook"), query);
    }
    assertEquals(
        MoveRepository.STATUS_ACCOUNTED,
        BgSaftMoveScope.of(april()).parameters(BgSaftMoveScope.POSTED_LINES).get("posted"));
  }

  @Test
  @DisplayName("Салдата и бъдещият дневник стъпват на ЕДИН И СЪЩ фрагмент")
  void един_предикат() {
    assertTrue(BgSaftMoveScope.OPENING_LINES.startsWith(BgSaftMoveScope.POSTED_LINES));
    assertTrue(BgSaftMoveScope.TURNOVER_LINES.startsWith(BgSaftMoveScope.POSTED_LINES));
    assertTrue(BgSaftMoveScope.OPENING_BY_ACCOUNT.contains(BgSaftMoveScope.OPENING_LINES));
    assertTrue(BgSaftMoveScope.TURNOVER_BY_ACCOUNT.contains(BgSaftMoveScope.TURNOVER_LINES));
  }

  @Test
  @DisplayName("Данъците и продуктите на периода стъпват на СЪЩИТЕ статии като оборота")
  void статиите_на_периода() {
    String period = BgSaftMoveScope.PERIOD_MOVES;
    // Същият предикат, само с корен Move вместо MoveLine.
    assertEquals(BgSaftMoveScope.TURNOVER_LINES.replace("self.move.", "self."), period);
    assertTrue(period.startsWith(BgSaftMoveScope.POSTED_MOVES));
    assertTrue(BgSaftMoveScope.TAXES_ON_LINES.endsWith(" WHERE " + BgSaftMoveScope.TURNOVER_LINES));
    assertTrue(
        BgSaftMoveScope.UNTAXED_LINES.endsWith(
            " WHERE " + BgSaftMoveScope.TURNOVER_LINES + " AND self.taxLineSet IS EMPTY"));
    for (String documents :
        List.of(BgSaftMoveScope.TAXES_ON_INVOICE_LINES, BgSaftMasterDataReader.INVOICED_PRODUCTS)) {
      assertTrue(documents.contains(" JOIN invoice.move self"), documents);
      assertTrue(documents.endsWith(" WHERE " + period), documents);
    }
    assertEquals(
        Set.of("companyId"),
        BgSaftMoveScope.of(april()).parameters(BgSaftMasterDataReader.STOCKED_PRODUCTS).keySet());
  }

  @Test
  @DisplayName("Началното салдо: годината преди периода ПЛЮС откриващите статии до края му")
  void началното_салдо() {
    String opening = BgSaftMoveScope.OPENING_LINES;
    assertEquals(MoveRepository.FUNCTIONAL_ORIGIN_OPENING, BgSaftMoveScope.OPENING_ORIGIN);
    assertTrue(opening.contains("self.move.period.year.id = :yearId"));
    assertTrue(
        opening.contains(
            "(self.move.date < :fromDate OR (self.move.functionalOriginSelect = :openingOrigin"
                + " AND self.move.date <= :toDate))"));
    assertTrue(
        BgSaftMoveScope.OPENING_BY_ACCOUNT.startsWith(
            "SELECT self.account.id, SUM(self.debit - self.credit) FROM MoveLine self WHERE "));
    assertTrue(BgSaftMoveScope.OPENING_BY_ACCOUNT.endsWith(" GROUP BY self.account.id"));
  }

  @Test
  @DisplayName(
      "Оборотът: датите на периода, без откриващите — и без да губи статиите с NULL произход")
  void оборотът() {
    String turnover = BgSaftMoveScope.TURNOVER_LINES;
    assertTrue(turnover.contains("self.move.date >= :fromDate AND self.move.date <= :toDate"));
    // ⚠️ Без IS NULL условието „<> откриваща“ изхвърля всяка статия без функционален произход.
    assertTrue(
        turnover.contains(
            "(self.move.functionalOriginSelect IS NULL"
                + " OR self.move.functionalOriginSelect <> :openingOrigin)"));
    assertFalse(turnover.contains("period.year"), "оборотът е по датите на периода");
    assertTrue(
        BgSaftMoveScope.TURNOVER_BY_ACCOUNT.startsWith(
            "SELECT self.account.id, SUM(self.debit), SUM(self.credit) FROM MoveLine self WHERE "));
    assertTrue(BgSaftMoveScope.TURNOVER_BY_ACCOUNT.endsWith(" GROUP BY self.account.id"));
  }

  @Test
  @DisplayName(
      "Контрагентите: същият фрагмент, GROUP BY сметка и партньор, LEFT JOIN и само техните сметки")
  void контрагентите() {
    String opening = BgSaftMoveScope.OPENING_BY_ACCOUNT_AND_PARTNER;
    String turnover = BgSaftMoveScope.TURNOVER_BY_ACCOUNT_AND_PARTNER;
    assertTrue(opening.contains(BgSaftMoveScope.OPENING_LINES));
    assertTrue(turnover.contains(BgSaftMoveScope.TURNOVER_LINES));
    for (String query : PARTY_QUERIES) {
      // ⚠️ LEFT JOIN: ред без контрагент остава с NULL, вместо да изчезне от сумата.
      assertTrue(
          query.contains(" FROM MoveLine self LEFT JOIN self.partner partner WHERE "), query);
      assertTrue(query.contains(" AND self.account.id IN (:accountIds)"), query);
      assertTrue(query.endsWith(" GROUP BY self.account.id, partner.id"), query);
    }
    assertTrue(
        opening.startsWith("SELECT self.account.id, partner.id, SUM(self.debit - self.credit) "));
    assertTrue(
        turnover.startsWith(
            "SELECT self.account.id, partner.id, SUM(self.debit), SUM(self.credit) "));
  }

  @Test
  @DisplayName("Списъкът сметки се подава отвън; обхватът не позволява да бъде подменен")
  void параметрите_на_контрагентите() {
    BgSaftMoveScope scope = BgSaftMoveScope.of(april());
    List<Long> accounts = List.of(1L, 2L);
    for (String query : PARTY_QUERIES) {
      assertThrows(IllegalStateException.class, () -> scope.parameters(query));
      Map<String, Object> parameters = scope.parameters(query, Map.of("accountIds", accounts));
      parameters.forEach((name, value) -> assertNotNull(value, ":" + name + " в " + query));
      assertEquals(accounts, parameters.get("accountIds"));
      assertEquals(7L, parameters.get("companyId"));
    }
    assertThrows(
        IllegalStateException.class,
        () ->
            scope.parameters(
                BgSaftMoveScope.TURNOVER_BY_ACCOUNT_AND_PARTNER, Map.of("companyId", 8L)));
  }

  @Test
  @DisplayName("OR никога не стои на най-горното ниво на заявката")
  void скобите() {
    List<String> all = new ArrayList<>(QUERIES);
    all.addAll(PARTY_QUERIES);
    for (String query : all) {
      int depth = 0;
      for (int i = 0; i < query.length(); i++) {
        char c = query.charAt(i);
        if (c == '(') {
          depth++;
        } else if (c == ')') {
          depth--;
          assertTrue(depth >= 0, "затваряща скоба без отваряща: " + query);
        } else if (depth == 0 && query.startsWith(" OR ", i)) {
          throw new AssertionError("OR на най-горното ниво: " + query);
        }
      }
      assertEquals(0, depth, "незатворена скоба: " + query);
    }
  }

  @Test
  @DisplayName("Всеки параметър на всяка заявка получава стойност от отчета")
  void параметрите() {
    BgSaftMoveScope scope = BgSaftMoveScope.of(april());
    for (String query : QUERIES) {
      Map<String, Object> parameters = scope.parameters(query);
      assertFalse(parameters.isEmpty(), query);
      parameters.forEach((name, value) -> assertNotNull(value, ":" + name + " в " + query));
    }
    Map<String, Object> opening = scope.parameters(BgSaftMoveScope.OPENING_BY_ACCOUNT);
    assertEquals(
        Set.of("companyId", "posted", "yearId", "fromDate", "openingOrigin", "toDate"),
        opening.keySet());
    assertEquals(7L, opening.get("companyId"));
    assertEquals(26L, opening.get("yearId"));
    assertEquals(LocalDate.of(2026, 4, 1), opening.get("fromDate"));
    assertEquals(LocalDate.of(2026, 4, 30), opening.get("toDate"));
    assertEquals(
        LocalDate.of(2026, 1, 1),
        scope.parameters(BgSaftMoveScope.POSTED_BEFORE_YEAR).get("yearFrom"));
    assertThrows(IllegalStateException.class, () -> scope.parameters("self.x = :unknown"));
  }

  @Test
  @DisplayName("Отказва без отчет, дружество, месечен вид, период или финансова година")
  void отказите() {
    assertThrows(IllegalArgumentException.class, () -> BgSaftMoveScope.of(null));

    SaftReport noCompany = april();
    noCompany.setCompany(null);
    assertThrows(IllegalArgumentException.class, () -> BgSaftMoveScope.of(noCompany));

    SaftReport annual = april();
    annual.setReportTypeSelect(SaftReportRepository.REPORT_TYPE_ANNUAL);
    assertThrows(IllegalArgumentException.class, () -> BgSaftMoveScope.of(annual));

    SaftReport noPeriod = april();
    noPeriod.setPeriod(null);
    assertThrows(IllegalArgumentException.class, () -> BgSaftMoveScope.of(noPeriod));

    SaftReport noEnd = april();
    noEnd.getPeriod().setToDate(null);
    assertThrows(IllegalArgumentException.class, () -> BgSaftMoveScope.of(noEnd));

    SaftReport noYear = april();
    noYear.getPeriod().setYear(null);
    assertThrows(IllegalArgumentException.class, () -> BgSaftMoveScope.of(noYear));

    SaftReport payroll = april();
    payroll.getPeriod().getYear().setTypeSelect(YearRepository.TYPE_PAYROLL);
    assertThrows(IllegalArgumentException.class, () -> BgSaftMoveScope.of(payroll));

    SaftReport foreign = april();
    foreign.getPeriod().getYear().setCompany(company(8L));
    assertThrows(IllegalArgumentException.class, () -> BgSaftMoveScope.of(foreign));

    assertNotNull(BgSaftMoveScope.of(april()));
  }
}

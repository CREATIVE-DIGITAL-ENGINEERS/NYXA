package com.axelor.apps.bglocalization.service.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЗЛАТНАТА СТАТИЯ: един пълен фиш → балансирана счетоводна статия с верните сметки по перо.
 *
 * <p><b>⚠️ ДВЕТЕ ТВЪРДЕНИЯ, КОИТО ПРАВЯТ ТОЗИ ТЕСТ НЕЩО ПОВЕЧЕ ОТ ПРЕПИСВАНЕ НА ОЧАКВАНИЯ.</b>
 * Списък с очаквани числа проверява само, че кодът прави каквото прави днес — сменѝ и модела, и
 * списъка, и тестът пак е зелен. Затова тук стоят и два независими свидетеля, изведени от
 * АРИТМЕТИКАТА НА ФИША, която този модул смята другаде и от други правила:
 *
 * <ul>
 *   <li><b>Салдото на 4211 Е НЕТОТО.</b> {@code BgPaySlipResolver#netTotal} го смята от знаците на
 *       перата, без да знае за сметки. Ако постинг моделът сбърка страна на едно перо — вноската на
 *       лицето да дебитира вместо да кредитира, — статията ще ОСТАНЕ балансирана и само това
 *       равенство ще падне.
 *   <li><b>Дебитът на разходните сметки Е РАЗХОДЪТ ЗА РАБОТОДАТЕЛЯ.</b> {@code
 *       BgPaySlipResolver#employerCost} също го смята сам. Ако вноската на лицето попадне по грешка
 *       в 605x, балансът пак се затваря, а този сбор скача точно с нея.
 * </ul>
 *
 * <p>Тече без Postgres, без Guice и без Hibernate — целият постинг модел е чиста функция.
 */
class BgPayrollPostingModelTest {

  /** Вид осигурен 01 — нает по трудово правоотношение. Данъкът му е по чл. 42 → сметка 4541. */
  private static final String EMPLOYED = "01";

  // ── фишът ────────────────────────────────────────────────────────────────

  /**
   * ПЪЛЕН ФИШ ЗА МЕСЕЦ: заплата, добавка, десет вноски по фондове, данък, запор и аванс.
   *
   * <p>⚠️ Числата са тези на осигурителните ставки за 2026 г. върху 1590,00 — не за да се провери
   * изчислението (то е на {@code BgContributionResolver} и си има свои тестове), а за да е
   * реалистичен разрезът по ФОНДОВЕ: точно той решава сметките. Кръгли числа биха слели 4551 и 4552
   * в едно и също салдо и разместването им нямаше да се види.
   */
  private static List<BgPaySlipLine> fullSlip() {
    List<BgPaySlipLine> lines = new ArrayList<>();

    lines.add(wage(100, "Основно възнаграждение", "1500.00"));
    lines.add(wage(110, "Допълнително за трудов стаж", "90.00"));

    // Вноски ЗА СМЕТКА НА РАБОТОДАТЕЛЯ (CREDIT) — разход плюс задължение.
    lines.add(contribution(500, "Пенсии · осигурител", "PENSION", "CREDIT", "173.63"));
    lines.add(contribution(504, "ОЗМ · осигурител", "OZM", "CREDIT", "33.39"));
    lines.add(contribution(506, "Безработица · осигурител", "UNEMPLOYMENT", "CREDIT", "9.54"));
    lines.add(contribution(508, "ТЗПБ · осигурител", "TZPB", "CREDIT", "7.95"));
    lines.add(contribution(510, "УПФ · осигурител", "UPF", "CREDIT", "44.52"));
    lines.add(contribution(516, "Здравно · осигурител", "HEALTH", "CREDIT", "76.32"));
    // ⚠️ ГВРС е с нулева ставка за 2026 г. и перото се генерира с нула. То НЕ е отказ и НЕ е ред в
    // статията — виж чистия изход в началото на BgPayrollPostingPlan#of.
    lines.add(contribution(518, "ГВРС · осигурител", "GVRS", "CREDIT", "0.00"));

    // Вноски ЗА СМЕТКА НА ЛИЦЕТО (DEBIT) — удръжка, НЕ разход.
    lines.add(contribution(501, "Пенсии · лице", "PENSION", "DEBIT", "133.24"));
    lines.add(contribution(505, "ОЗМ · лице", "OZM", "DEBIT", "22.26"));
    lines.add(contribution(507, "Безработица · лице", "UNEMPLOYMENT", "DEBIT", "6.36"));
    lines.add(contribution(511, "УПФ · лице", "UPF", "DEBIT", "34.98"));
    lines.add(contribution(517, "Здравно · лице", "HEALTH", "DEBIT", "50.88"));

    lines.add(line(700, "Данък по чл. 42 ЗДДФЛ", "DEBIT", "134.23"));
    lines.add(line(820, "Запор върху възнаграждението", "DEBIT", "120.00"));
    lines.add(line(600, "Аванс", "DEBIT", "300.00"));

    return lines;
  }

  // ── 1 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("1 · пълен фиш дава балансирана статия и всяко перо е разпознато")
  void aFullPaySlipYieldsABalancedArticle() {
    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(fullSlip(), EMPLOYED);

    assertEquals(List.of(), plan.getRefusals(), "нито едно перо не бива да остане без сметка");
    assertTrue(plan.isPostable());
    assertTrue(plan.isBalanced(), "Dr трябва да е равен на Cr");
    assertEquals(new BigDecimal("2737.30"), plan.totalDebit());
    assertEquals(new BigDecimal("2737.30"), plan.totalCredit());
  }

  // ── 2 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("2 · статията има точно очакваните сметки със сборовете си")
  void theArticleHasTheExpectedAccounts() {
    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(fullSlip(), EMPLOYED);

    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("Dr 4211", "801.95"); // вноски на лицето + данък + запор + аванс
    expected.put("Cr 4211", "1590.00"); // брутото
    expected.put("Cr 4212", "300.00"); // приключеният аванс
    expected.put("Cr 4214", "120.00"); // запорът
    expected.put("Cr 4541", "134.23"); // ДДФЛ · трудови правоотношения
    expected.put("Cr 4551", "362.52"); // ДОО: Пенсии + ОЗМ, двете страни
    expected.put("Cr 4552", "127.20"); // здравно, двете страни
    expected.put("Cr 4553", "79.50"); // ДЗПО (УПФ), двете страни
    expected.put("Cr 4554", "15.90"); // безработица, двете страни
    expected.put("Cr 4555", "7.95"); // ТЗПБ — само осигурител
    expected.put("Dr 6041", "1500.00"); // основно възнаграждение
    expected.put("Dr 6042", "90.00"); // добавката
    expected.put("Dr 6051", "207.02"); // ДОО за сметка на работодателя
    expected.put("Dr 6052", "76.32");
    expected.put("Dr 6053", "44.52");
    expected.put("Dr 6054", "9.54");
    expected.put("Dr 6055", "7.95");

    Map<String, String> actual = new LinkedHashMap<>();
    for (BgPayrollPosting p : plan.getPostings()) {
      actual.put((p.isDebit() ? "Dr " : "Cr ") + p.getAccountCode(), p.getAmount().toPlainString());
    }
    assertEquals(expected, actual);
  }

  // ── 3 · първият независим свидетел ──────────────────────────────────────

  @Test
  @DisplayName("3 · салдото на 4211 е НЕТОТО, сметнато от знаците на перата")
  void theBalanceOfPersonnelAccountIsTheNetPay() {
    List<BgPaySlipLine> lines = fullSlip();
    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

    BigDecimal credit = amountOn(plan, BgPayrollAccount.PERSONNEL_NET_WAGE, false);
    BigDecimal debit = amountOn(plan, BgPayrollAccount.PERSONNEL_NET_WAGE, true);

    // ⚠️ netTotal НЕ знае за сметки: смята credits(без 5xx) минус всички debits.
    assertEquals(new BigDecimal("788.05"), BgPaySlipResolver.netTotal(lines), "нетото по фиша");
    assertEquals(
        BgPaySlipResolver.netTotal(lines),
        credit.subtract(debit),
        "салдото на 4211 трябва да е точно нетото — инак някое перо е от грешната страна");
  }

  // ── 4 · вторият независим свидетел ──────────────────────────────────────

  @Test
  @DisplayName("4 · дебитът на 604x/605x е РАЗХОДЪТ ЗА РАБОТОДАТЕЛЯ")
  void theExpenseSideIsTheEmployerCost() {
    List<BgPaySlipLine> lines = fullSlip();
    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

    BigDecimal expenses = BigDecimal.ZERO;
    for (BgPayrollPosting p : plan.getPostings()) {
      if (p.isDebit() && EMPLOYER_COST_ACCOUNTS.contains(p.getAccountCode())) {
        expenses = expenses.add(p.getAmount());
      }
    }

    assertEquals(
        new BigDecimal("1935.35"), BgPaySlipResolver.employerCost(lines), "разходът по фиша");
    assertEquals(
        BgPaySlipResolver.employerCost(lines),
        expenses,
        "вноска на лицето, попаднала в 605x, не разваля баланса — само този сбор я лови");
  }

  // ── 5 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("5 · всяко перо дава ДВОЙКА, тоест балансът е по устройство")
  void everyLineYieldsAPair() {
    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(fullSlip(), EMPLOYED);

    // 17 пера, от които едно с нула → 16 двойки.
    assertEquals(16, plan.getEntries().size());
    for (BgPayrollPostingEntry e : plan.getEntries()) {
      assertTrue(e.getAmount().signum() > 0, "перо " + e.getLineNumber() + " с нулева сума");
      assertTrue(
          !e.getDebitAccount().equals(e.getCreditAccount()),
          "перо " + e.getLineNumber() + " сочи една и съща сметка от двете страни");
    }
  }

  // ── 6 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("6 · граждански договор (код 14) праща данъка в 4542, не в 4541")
  void aCivilContractSendsTheTaxToTheNonEmploymentAccount() {
    List<BgPaySlipLine> lines =
        List.of(
            wage(100, "Възнаграждение", "2000.00"),
            line(700, "Данък по чл. 43 ЗДДФЛ", "DEBIT", "150.00"));

    BgPayrollPostingPlan.Plan employed = BgPayrollPostingPlan.of(lines, EMPLOYED);
    BgPayrollPostingPlan.Plan civil = BgPayrollPostingPlan.of(lines, "14");

    assertEquals(BgPayrollAccount.TAX_EMPLOYMENT, creditAccountOf(employed, 700), "чл. 42 → 4541");
    assertEquals(BgPayrollAccount.TAX_NON_EMPLOYMENT, creditAccountOf(civil, 700), "чл. 43 → 4542");
  }

  // ── 7 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("7 · обезщетение по КТ отива в 6043/4213, отделно от заплатата в 6041/4211")
  void aLabourCodeCompensationUsesItsOwnPair() {
    List<BgPaySlipLine> lines =
        List.of(
            wage(100, "Основно възнаграждение", "1500.00"),
            line(900, "Обезщетение за оставане без работа", "CREDIT", "1450.00"));

    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

    assertEquals(List.of(), plan.getRefusals());
    assertTrue(plan.isBalanced());
    // ⚠️ Двете НЕ се сливат: 6043 „Разходи за обезщетения по КТ“ и 4213 „Персонал –
    // обезщетения“ съществуват в BG_COA точно за да се четат отделно от заплатата.
    assertEquals(new BigDecimal("1500.00"), amountOn(plan, "6041", true));
    assertEquals(new BigDecimal("1450.00"), amountOn(plan, "6043", true));
    assertEquals(new BigDecimal("1500.00"), amountOn(plan, "4211", false));
    assertEquals(new BigDecimal("1450.00"), amountOn(plan, "4213", false));
    // И брутото по фиша обхваща и двете — 1xx и 9xx заедно.
    assertEquals(new BigDecimal("2950.00"), BgPaySlipResolver.grossTotal(lines));
    assertEquals(BgPaySlipResolver.grossTotal(lines), plan.totalDebit());
  }

  // ── 8 · третият независим свидетел ──────────────────────────────────────

  @Test
  @DisplayName("8 · дебитът на 6094 е сборът на 2xx, а НЕ част от employerCost")
  void theSocialExpenseAccountCarriesExactlyTheSocialLines() {
    List<BgPaySlipLine> lines = new ArrayList<>(fullSlip());
    lines.add(line(230, "Работно облекло", "CREDIT", "60.00"));
    lines.add(line(240, "Безплатна храна по КТ чл. 285", "CREDIT", "40.00"));

    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

    assertEquals(List.of(), plan.getRefusals());
    assertTrue(plan.isBalanced());
    assertEquals(new BigDecimal("100.00"), amountOn(plan, "6094", true), "двете социални пера");
    assertEquals(
        BigDecimal.ZERO, amountOn(plan, "609", true), "609 „Други разходи“ вече не участва");
    // ⚠️ И ЕТО ЗАЩО СВИДЕТЕЛЯТ ОТГОРЕ Е ПО СПИСЪК, А НЕ ПО ПРЕФИКС „60“. Социалният разход НЕ е в
    // employerCost (той е бруто + осигуровки за сметка на работодателя, а 2xx не е нито едното),
    // тоест сбор по префикс щеше да падне тук — и то не защото моделът греши.
    BigDecimal employerSide = BigDecimal.ZERO;
    for (BgPayrollPosting p : plan.getPostings()) {
      if (p.isDebit() && EMPLOYER_COST_ACCOUNTS.contains(p.getAccountCode())) {
        employerSide = employerSide.add(p.getAmount());
      }
    }
    assertEquals(BgPaySlipResolver.employerCost(lines), employerSide);
    // Придобивката е начислена НА ЛИЦЕТО: 4211 расте със същите 100,00.
    assertEquals(
        BgPaySlipResolver.netTotal(lines),
        amountOn(plan, "4211", false).subtract(amountOn(plan, "4211", true)));
  }

  // ── помощници ───────────────────────────────────────────────────────────

  /**
   * РАЗХОДНИТЕ СМЕТКИ, КОИТО СЪСТАВЯТ {@code employerCost} — изброени, не по префикс.
   *
   * <p>⚠️ 6094 НЕ Е ТУК нарочно: социалният разход по 2xx не влиза в „бруто + осигуровки за сметка
   * на работодателя“. Оставен префиксът „60“, свидетелят щеше да пада за всеки фиш със социално
   * перо и да сочи постинг модела вместо разликата в определенията.
   */
  private static final java.util.Set<String> EMPLOYER_COST_ACCOUNTS =
      java.util.Set.of(
          "604", "6041", "6042", "6043", "6051", "6052", "6053", "6054", "6055", "6056");

  private static BigDecimal amountOn(BgPayrollPostingPlan.Plan plan, String code, boolean debit) {
    for (BgPayrollPosting p : plan.getPostings()) {
      if (p.getAccountCode().equals(code) && p.isDebit() == debit) {
        return p.getAmount();
      }
    }
    return BigDecimal.ZERO;
  }

  private static String creditAccountOf(BgPayrollPostingPlan.Plan plan, int lineNumber) {
    for (BgPayrollPostingEntry e : plan.getEntries()) {
      if (e.getLineNumber() == lineNumber) {
        return e.getCreditAccount();
      }
    }
    return null;
  }

  private static BgPaySlipLine wage(int number, String name, String amount) {
    BgPaySlipLine line = line(number, name, "CREDIT", amount);
    line.setBgFormsGrossWage(true);
    return line;
  }

  private static BgPaySlipLine contribution(
      int number, String name, String fundCode, String sign, String amount) {
    BgPaySlipLine line = line(number, name, sign, amount);
    BgContributionFund fund = new BgContributionFund();
    fund.setCode(fundCode);
    fund.setName(fundCode);
    line.setBgContributionFund(fund);
    return line;
  }

  private static BgPaySlipLine line(int number, String name, String sign, String amount) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(number);
    line.setName(name);
    line.setBgSign(sign);
    line.setBgAmount(new BigDecimal(amount));
    return line;
  }
}

package com.axelor.apps.bglocalization.service.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * МАРТ-РЕЖИМЪТ: корекция за МАРТ, изплатена през МАЙ. Три документа, нула двойно броене.
 *
 * <p>Разказът е един и същ във всички твърдения отдолу:
 *
 * <ol>
 *   <li><b>Мартенският оригинал</b> е потвърден, изплатен и осчетоводен — статия M1 с пълните числа
 *       за март.
 *   <li><b>Коригиращият мартенски фиш</b> носи ПЪЛНИТЕ верни числа за март (той храни коригираща
 *       Д1/Д6 за март) и НЕ се изплаща. Неговата статия е M2 = M1' − M1, тоест само разликата, и
 *       сяда в МАРТ, защото датата ѝ е краят на неговия период.
 *   <li><b>Майският фиш</b> носи перо 620 с нетната делта — парите излизат с майското нето, — но то
 *       е ИЗКЛЮЧЕНО от майската статия: разходът му е вече в M2.
 * </ol>
 *
 * <p><b>⚠️ И ТОЧКАТА, В КОЯТО ТРИТЕ ДОКУМЕНТА СЕ СРЕЩАТ, Е 4211.</b> M2 оставя там кредитно салдо,
 * равно на делтата; майската статия оставя майското нето. Преводът по майската ведомост закрива и
 * двете, защото е точно за сбора им. Затова тестът сверява ТРИ числа, а не едно: делтата в M2,
 * салдото на 4211 в майската статия, и сборът им срещу майското нето по перата.
 *
 * <p><b>⚠️ ЗАЩО ДВОЙНОТО БРОЕНЕ Е ГЛАВНИЯТ ВРАГ ТУК.</b> Всеки от трите документа поотделно
 * балансира — и при вярна, и при сгрешена схема. Ако M2 носеше пълните числа, март щеше да е
 * отчетен два пъти; ако 620 влизаше и в майската статия, делтата щеше да е два пъти. И в двата
 * случая всичко изглежда сметнато. Затова свидетелят е РАВЕНСТВО МЕЖДУ ДОКУМЕНТИ: M1 + M2 трябва да
 * дава точно M1' — верния март, — сметка по сметка.
 */
class BgPayrollRetroPostingTest {

  private static final String EMPLOYED = "01";

  private static final Period MARCH = period("03/2026", LocalDate.of(2026, 3, 1), 31);
  private static final Period MAY = period("05/2026", LocalDate.of(2026, 5, 1), 31);
  private static final Period LAST_DECEMBER = period("12/2025", LocalDate.of(2025, 12, 1), 31);

  // ── тримата ─────────────────────────────────────────────────────────────

  /** МАРТ, както е бил подаден и осчетоводен: заплата 1500,00. */
  private static List<BgPaySlipLine> marchAsFiled() {
    return month("1500.00", "173.63", "133.24", "76.32", "50.88", "134.23");
  }

  /** МАРТ, както трябваше да бъде: заплата 1700,00 (пропусната добавка). */
  private static List<BgPaySlipLine> marchAsCorrected() {
    return month("1700.00", "196.78", "151.00", "86.49", "57.66", "149.13");
  }

  /** МАЙ: собствените си числа (същите като мартенските) плюс перо 620 с делтата. */
  private static List<BgPaySlipLine> mayWithRetroLine(String delta) {
    List<BgPaySlipLine> lines = new ArrayList<>(marchAsFiled());
    BgPaySlipLine retro = line(620, "Доплащане за 03/2026", "CREDIT", delta);
    retro.setBgCorrectsPeriod(MARCH);
    lines.add(retro);
    return lines;
  }

  // ── 1 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("1 · ретро-статията носи САМО делтата, и тя балансира")
  void theRetroArticleCarriesOnlyTheDifference() {
    BgPayrollRetroDelta delta = delta();

    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("Cr 4211", "160.56"); // нетната делта — още се дължи на лицето
    expected.put("Cr 4541", "14.90"); // допълнителният данък
    expected.put("Cr 4551", "40.91"); // ДОО, двете страни
    expected.put("Cr 4552", "16.95"); // здравно, двете страни
    expected.put("Dr 6041", "200.00"); // пропуснатата заплата
    expected.put("Dr 6051", "23.15");
    expected.put("Dr 6052", "10.17");

    Map<String, String> actual = new LinkedHashMap<>();
    for (BgPayrollPosting p : delta.getPostings()) {
      actual.put((p.isDebit() ? "Dr " : "Cr ") + p.getAccountCode(), p.getAmount().toPlainString());
    }
    assertEquals(expected, actual);
    assertTrue(delta.isBalanced(), "разликата на две балансирани статии също балансира");
    assertEquals(new BigDecimal("233.32"), delta.totalDebit());
    assertEquals(new BigDecimal("233.32"), delta.totalCredit());
  }

  // ── 2 · свидетелят срещу двойното броене ────────────────────────────────

  @Test
  @DisplayName("2 · M1 + M2 дава ТОЧНО верния март — сметка по сметка")
  void theOriginalPlusTheDeltaEqualsTheTruth() {
    BgPayrollPostingPlan.Plan filed = BgPayrollPostingPlan.of(marchAsFiled(), EMPLOYED);
    BgPayrollPostingPlan.Plan corrected = BgPayrollPostingPlan.of(marchAsCorrected(), EMPLOYED);
    BgPayrollRetroDelta delta = BgPayrollRetroDelta.of(corrected, filed);

    Map<String, BigDecimal> sum = signed(filed.getPostings());
    for (Map.Entry<String, BigDecimal> entry : signed(delta.getPostings()).entrySet()) {
      sum.merge(entry.getKey(), entry.getValue(), BigDecimal::add);
    }

    // ⚠️ ТОВА Е ЦЯЛОТО ТВЪРДЕНИЕ НА РЕТРО-РЕЖИМА. Ако ретро-статията носеше пълните числа,
    // сборът тук щеше да е ДВА ПЪТИ верният март и пак нямаше да има нито един небалансиран
    // документ.
    assertEquals(signed(corrected.getPostings()), sum, "книгите за март трябва да са верният март");
  }

  // ── 3 · мостът през 4211 ────────────────────────────────────────────────

  @Test
  @DisplayName("3 · делтата в 4211 е точно перото 620 в майския фиш")
  void thePersonnelBridgeEqualsTheRetroLine() {
    BgPayrollRetroDelta delta = delta();
    BigDecimal bridge = delta.personnelBridge();

    assertEquals(new BigDecimal("160.56"), bridge, "кредитното салдо, което M2 оставя в 4211");

    // ⚠️ СВЕРЕНО СРЕЩУ АРИТМЕТИКАТА НА ФИША, а не срещу друга сметка: retroDelta е нетната разлика,
    // сметната от нетата на двата фиша, и НЕ знае нищо за сметки.
    BigDecimal net = BgPaySlipResolver.netTotal(marchAsCorrected());
    BigDecimal netFiled = BgPaySlipResolver.netTotal(marchAsFiled());
    assertEquals(new BigDecimal("1342.21"), net);
    assertEquals(new BigDecimal("1181.65"), netFiled);
    assertEquals(
        BgPaySlipResolver.retroDelta(false, net, netFiled),
        bridge,
        "мостът в 4211 трябва да е нетната делта — инак парите и осчетоводяването се разминават");
  }

  // ── 4 · майската статия ─────────────────────────────────────────────────

  @Test
  @DisplayName("4 · майската статия НЕ съдържа 620 — перото е отложено, не отказано")
  void theCurrentArticleExcludesTheRetroLine() {
    List<BgPaySlipLine> may = mayWithRetroLine("160.56");
    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(may, EMPLOYED);

    assertEquals(List.of(), plan.getRefusals(), "620 с маркер НЕ е отказ");
    assertEquals(1, plan.getDeferred().size(), "точно едно отложено перо");
    assertEquals(620, plan.getDeferred().get(0).getLineNumber());
    assertEquals(MARCH, plan.getDeferred().get(0).getPeriod());
    assertEquals(new BigDecimal("160.56"), plan.deferredNet());

    for (BgPayrollPostingEntry e : plan.getEntries()) {
      assertFalse(e.getLineNumber() == 620, "перо 620 не бива да дава двойка в текущата статия");
    }
    assertTrue(plan.isBalanced());
    // Майската статия е точно мартенската по числа — 620 е единствената разлика и той е вън.
    assertEquals(
        signed(BgPayrollPostingPlan.of(marchAsFiled(), EMPLOYED).getPostings()),
        signed(plan.getPostings()));
  }

  @Test
  @DisplayName("5 · салдото на 4211 в майската статия е нетото МИНУС отложеното")
  void theWitnessOnThePersonnelAccountAccountsForTheDeferredLine() {
    List<BgPaySlipLine> may = mayWithRetroLine("160.56");
    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(may, EMPLOYED);

    BigDecimal balance =
        amountOn(plan, BgPayrollAccount.PERSONNEL_NET_WAGE, false)
            .subtract(amountOn(plan, BgPayrollAccount.PERSONNEL_NET_WAGE, true));

    // ⚠️ СТАРИЯТ СВИДЕТЕЛ („салдото на 4211 Е нетото“) НЕ ВАЖИ ДОСЛОВНО ЗА ФИШ С РЕТРО-ПЕРО, и
    // това не е разхлабване, а самата механика: нетото е ПАРИТЕ на този месец (620 включен), а
    // статията е СЧЕТОВОДНИТЕ ФАКТИ на този месец (620 изключен). Разликата е точно отложеното.
    assertEquals(new BigDecimal("1342.21"), BgPaySlipResolver.netTotal(may), "майското нето");
    assertEquals(
        BgPaySlipResolver.netTotal(may).subtract(plan.deferredNet()),
        balance,
        "4211 в текущата статия носи нетото БЕЗ делтата");
    assertEquals(new BigDecimal("1181.65"), balance);

    // ⚠️ И СБОРЪТ НА ДВЕТЕ СТАТИИ ПО 4211 Е МАЙСКОТО НЕТО — тоест точно преводът по ведомостта.
    assertEquals(
        BgPaySlipResolver.netTotal(may),
        balance.add(delta().personnelBridge()),
        "M2 + майската статия трябва да закрият точно изплатеното");
  }

  // ── 6 · удръжката (обратната посока) ────────────────────────────────────

  @Test
  @DisplayName("6 · корекция НАДОЛУ дава удръжка: 620 е DEBIT и делтата обръща посоките")
  void aDownwardCorrectionReversesEveryDirection() {
    // Ролите са разменени: подаденото е ПО-ГОЛЯМО от верното.
    BgPayrollPostingPlan.Plan filed = BgPayrollPostingPlan.of(marchAsCorrected(), EMPLOYED);
    BgPayrollPostingPlan.Plan corrected = BgPayrollPostingPlan.of(marchAsFiled(), EMPLOYED);
    BgPayrollRetroDelta delta = BgPayrollRetroDelta.of(corrected, filed);

    assertTrue(delta.isBalanced());
    // Всеки ред е с обърната посока спрямо тест 1 и с една и същата сума.
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("Cr 6041", "200.00");
    expected.put("Cr 6051", "23.15");
    expected.put("Cr 6052", "10.17");
    expected.put("Dr 4211", "160.56");
    expected.put("Dr 4541", "14.90");
    expected.put("Dr 4551", "40.91");
    expected.put("Dr 4552", "16.95");
    Map<String, String> actual = new LinkedHashMap<>();
    for (BgPayrollPosting p : delta.getPostings()) {
      actual.put((p.isDebit() ? "Dr " : "Cr ") + p.getAccountCode(), p.getAmount().toPlainString());
    }
    assertEquals(expected, actual);
    // Мостът е ОТРИЦАТЕЛЕН: лицето дължи обратно, а 620 в текущия фиш е удръжка.
    assertEquals(new BigDecimal("-160.56"), delta.personnelBridge());

    List<BgPaySlipLine> may = new ArrayList<>(marchAsFiled());
    BgPaySlipLine retro = line(620, "Удръжка за 03/2026", "DEBIT", "160.56");
    retro.setBgCorrectsPeriod(MARCH);
    may.add(retro);
    BgPayrollPostingPlan.Plan mayPlan = BgPayrollPostingPlan.of(may, EMPLOYED);
    assertEquals(new BigDecimal("-160.56"), mayPlan.deferredNet(), "удръжката е минус");
  }

  // ── 7 · заличаването ────────────────────────────────────────────────────

  @Test
  @DisplayName("7 · заличаването е минус ЦЕЛИЯТ оригинал, а не нулева делта")
  void aDeletionReversesTheWholeOriginal() {
    BgPayrollPostingPlan.Plan filed = BgPayrollPostingPlan.of(marchAsFiled(), EMPLOYED);
    // ⚠️ ВТОРИЯТ АРГУМЕНТ Е null НАРОЧНО. Заличаващият фиш носи числата на ОРИГИНАЛА
    // (refuseAlteredDeletion го изисква), тоест делта срещу самия него е нула — а верният отговор
    // е „записът изчезва“, тоест сторно на всичко.
    BgPayrollRetroDelta delta = BgPayrollRetroDelta.of(null, filed);

    assertTrue(delta.isBalanced());
    // ⚠️ НЕ СЕ СВЕРЯВА С totalDebit НА ОРИГИНАЛА, И ТОВА НЕ Е ОТСТЪПКА. Сметка 4211 стои от двете
    // страни на редовната статия (Cr брутото, Dr удръжките) и НЕ се нетира там нарочно — оборотът
    // ѝ е онова, по което се сверява ведомостта. В ДЕЛТАТА нетирането е неизбежно: разликата на
    // едно салдо е ЕДНО число със знак. Затова свидетелят е „сборът на двете статии по всяка
    // сметка е нула“, а не равенство на оборотите.
    Map<String, BigDecimal> after = signed(filed.getPostings());
    for (Map.Entry<String, BigDecimal> entry : signed(delta.getPostings()).entrySet()) {
      after.merge(entry.getKey(), entry.getValue(), BigDecimal::add);
    }
    after.entrySet().removeIf(e -> e.getValue().signum() == 0);
    assertEquals(
        Map.of(), after, "след заличаването НИТО ЕДНА сметка не бива да носи салдо от март");
    // И мостът е точно минус оригиналното нето — толкова трябва да върне лицето.
    assertEquals(
        BgPaySlipResolver.netTotal(marchAsFiled()).negate(),
        delta.personnelBridge(),
        "заличаването праща МИНУС цялото нето в текущия фиш");

    // Контролният опит: сгрешена схема, при която заличаването се смята като „делта срещу себе си“.
    assertTrue(
        BgPayrollRetroDelta.of(filed, filed).isEmpty(),
        "точно това щеше да излезе при подаден план на заличаващото копие — НИЩО");
  }

  // ── 8 · нулевата делта ──────────────────────────────────────────────────

  @Test
  @DisplayName("8 · корекция, която не мени салдо, дава ПРАЗНА ретро-статия, не нулеви редове")
  void aCorrectionThatMovesNoBalanceYieldsNoArticle() {
    // Разместване между 100 и 110: и двете формират БРЗ, но 100 отива в 6041, а 110 — в 6042.
    List<BgPaySlipLine> before = List.of(wage(100, "Основно", "1500.00"));
    List<BgPaySlipLine> after =
        List.of(wage(100, "Основно", "1400.00"), wage(110, "За стаж", "100.00"));

    BgPayrollRetroDelta moved =
        BgPayrollRetroDelta.of(
            BgPayrollPostingPlan.of(after, EMPLOYED), BgPayrollPostingPlan.of(before, EMPLOYED));

    // Салдата се менят (6041 надолу, 6042 нагоре), тоест статия ТРЯБВА да има — при непроменено
    // нето и без перо 620 в текущия фиш.
    assertFalse(moved.isEmpty());
    assertEquals(new BigDecimal("100.00"), moved.totalDebit());
    assertEquals(
        BigDecimal.ZERO.setScale(2), moved.personnelBridge(), "нищо не се дължи на лицето");

    // А истински нулевата корекция (същите числа) не дава нито един ред.
    BgPayrollRetroDelta same =
        BgPayrollRetroDelta.of(
            BgPayrollPostingPlan.of(before, EMPLOYED), BgPayrollPostingPlan.of(before, EMPLOYED));
    assertTrue(same.isEmpty());
  }

  // ── 9 · корекцията през година ──────────────────────────────────────────

  @Test
  @DisplayName("9 · перо за период от ДРУГА година пак се отлага — отказът е на услугата")
  void aCrossYearRetroLineIsStillDeferredByThePurePlan() {
    List<BgPaySlipLine> may = new ArrayList<>(marchAsFiled());
    BgPaySlipLine retro = line(620, "Доплащане за 12/2025", "CREDIT", "80.00");
    retro.setBgCorrectsPeriod(LAST_DECEMBER);
    may.add(retro);

    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(may, EMPLOYED);

    // ⚠️ ЧИСТАТА ФУНКЦИЯ НЕ ЗНАЕ ПЕРИОДА НА ФИША и затова не може да реши дали е през година —
    // тя вижда само маркера на реда. Отказът за cross-year живее в BgPaySlipMoveService, където
    // и двата периода са налице. Тестът заковава разделението: тук отлагане, там отказ.
    assertEquals(List.of(), plan.getRefusals());
    assertEquals(1, plan.getDeferred().size());
    assertEquals(LAST_DECEMBER, plan.getDeferred().get(0).getPeriod());
  }

  // ── помощници ───────────────────────────────────────────────────────────

  private static BgPayrollRetroDelta delta() {
    return BgPayrollRetroDelta.of(
        BgPayrollPostingPlan.of(marchAsCorrected(), EMPLOYED),
        BgPayrollPostingPlan.of(marchAsFiled(), EMPLOYED));
  }

  /** „Dr/Cr сметка → сума СЪС ЗНАК“ — за да се сравняват статии, а не низове. */
  private static Map<String, BigDecimal> signed(List<BgPayrollPosting> postings) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    for (BgPayrollPosting p : postings) {
      BigDecimal value = p.isDebit() ? p.getAmount() : p.getAmount().negate();
      out.merge(p.getAccountCode(), value, BigDecimal::add);
    }
    out.entrySet().removeIf(e -> e.getValue().signum() == 0);
    return out;
  }

  private static BigDecimal amountOn(BgPayrollPostingPlan.Plan plan, String code, boolean debit) {
    BigDecimal sum = BigDecimal.ZERO;
    for (BgPayrollPosting p : plan.getPostings()) {
      if (p.getAccountCode().equals(code) && p.isDebit() == debit) {
        sum = sum.add(p.getAmount());
      }
    }
    return sum;
  }

  private static List<BgPaySlipLine> month(
      String gross,
      String pensionEmployer,
      String pensionEmployee,
      String healthEmployer,
      String healthEmployee,
      String tax) {
    List<BgPaySlipLine> lines = new ArrayList<>();
    lines.add(wage(100, "Основно възнаграждение", gross));
    lines.add(contribution(500, "Пенсии · осигурител", "PENSION", "CREDIT", pensionEmployer));
    lines.add(contribution(501, "Пенсии · лице", "PENSION", "DEBIT", pensionEmployee));
    lines.add(contribution(516, "Здравно · осигурител", "HEALTH", "CREDIT", healthEmployer));
    lines.add(contribution(517, "Здравно · лице", "HEALTH", "DEBIT", healthEmployee));
    lines.add(line(700, "Данък по чл. 42 ЗДДФЛ", "DEBIT", tax));
    return lines;
  }

  private static Period period(String name, LocalDate from, int days) {
    Period period = new Period();
    period.setName(name);
    period.setFromDate(from);
    period.setToDate(from.plusDays(days - 1));
    return period;
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

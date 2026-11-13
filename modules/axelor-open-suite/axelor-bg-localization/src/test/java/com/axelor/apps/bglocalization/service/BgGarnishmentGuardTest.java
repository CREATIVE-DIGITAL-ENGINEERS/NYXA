package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgGarnishmentScale;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПАЗАЧЪТ НА НЕСЕКВЕСТИРУЕМИЯ ДОХОД — петте правила по реда, в който важат.
 *
 * <p><b>⚠️ ПОДМЕНЕНИ СА САМО ЧЕТЦИТЕ.</b> {@code BgPaySlipService#garnishmentRefusal} и {@code
 * BgGarnishmentResolver#assess} се викат ИСТИНСКИ. Подменени са трите шева към базата — МРЗ,
 * скалата и децата — и първите два се пълнят ОТ SEED ФАЙЛОВЕТЕ, не с числа в теста.
 *
 * <p><b>Защо пазач, а не генератор.</b> Перата от 8xx са РЪЧНИ: запорът идва от изпълнителен лист,
 * заемът от договор, издръжката от съдебно решение. Няма какво да се сглоби — има какво да се
 * сВЕРИ.
 *
 * <p>Тече без Postgres, без Guice и без Hibernate.
 *
 * <p><b>⚠️ ЧЕТИРИ РЕЖИМА В 8xx, НЕ ТРИ.</b> Перото 830 (ТЕКУЩА издръжка) и перото 831 (издръжка за
 * МИНАЛО ВРЕМЕ) се четат ПООТДЕЛНО: първото намалява основата, второто се бори за място вътре в
 * тавана, по ал. 1. Златните числа на разграничението са в {@code BgMaintenancePastDueTest}; тук се
 * пази онова, което е работа на пазача — прочитът на перата, задействането и текстът на отказа.
 *
 * <p>Основание: ГПК чл. 446 (ред. ДВ бр. 86/2017; ал. 3 нова, ДВ бр. 55/2025) и чл. 447 — сверени
 * срещу официалния ГПК (justice.government.bg) на 22.08.2026 г.; дословният текст на ал. 4 и на чл.
 * 447 стои в javadoc-а на {@code BgPaySlipService#garnishmentRefusal}. ⚠️ Текстът на КТ чл. 272 НЕ
 * е сверен в тази среда и затова никъде не се цитира — само се сочи като основание.
 */
class BgGarnishmentGuardTest {

  /** МРЗ 2026 г. — 620,20 EUR. Двете МРЗ са 1240,40, четирите — 2480,80. */
  private static final BigDecimal MIN_WAGE = new BigDecimal("620.20");

  /**
   * ⚠️ НУЛА ЗА ПРОСРОЧЕНАТА ИЗДРЪЖКА, НАЗОВАНА. Тези твърдения са за фиш БЕЗ перо 831 — тоест за
   * случая, в който целият вход за издръжка е ТЕКУЩ. Разграничението текуща/просрочена си има свои
   * твърдения ({@code BgMaintenancePastDueTest}); гола {@code BigDecimal.ZERO} тук би оставила
   * читателя да гадае кой от двата входа е нулиран.
   */
  private static final BigDecimal NO_PAST_DUE = BigDecimal.ZERO;

  // ═══ УСЛУГАТА С ПОДМЕНЕНИ ЧЕТЦИ ═══════════════════════════════════════════

  private static BigDecimal decimalOrNull(String value) {
    return value == null || value.trim().isEmpty() ? null : new BigDecimal(value.trim());
  }

  private static Integer integerOrNull(String value) {
    return value == null || value.trim().isEmpty() ? null : Integer.valueOf(value.trim());
  }

  /** Шестте реда на скалата — ОТ ФАЙЛА. */
  static List<BgGarnishmentScale> scaleFromSeed() {
    List<String> header = BgSeed.quotedHeader("bg_garnishment_scale.csv");
    List<BgGarnishmentScale> out = new ArrayList<>();
    for (String[] row : BgSeed.quotedRows("bg_garnishment_scale.csv")) {
      BgGarnishmentScale band = new BgGarnishmentScale();
      band.setBgThreshold(Integer.valueOf(BgSeed.col(header, row, "bgThreshold").trim()));
      band.setBgWithChildren(
          "true".equalsIgnoreCase(BgSeed.col(header, row, "bgWithChildren").trim()));
      band.setBgFromMultiple(decimalOrNull(BgSeed.col(header, row, "bgFromMultiple")));
      band.setBgToMultiple(decimalOrNull(BgSeed.col(header, row, "bgToMultiple")));
      band.setBgNumerator(integerOrNull(BgSeed.col(header, row, "bgNumerator")));
      band.setBgDenominator(integerOrNull(BgSeed.col(header, row, "bgDenominator")));
      band.setBgKeepMultiple(decimalOrNull(BgSeed.col(header, row, "bgKeepMultiple")));
      out.add(band);
    }
    return out;
  }

  private static final class Service extends BgPaySlipService {

    BgGarnishmentService.Status children = BgGarnishmentService.Status.WITHOUT_CHILDREN;
    boolean scaleLoaded = true;
    boolean minWageLoaded = true;

    @Override
    protected BgGarnishmentService.Status childrenStatus(BgPaySlip slip, LocalDate date) {
      return children;
    }

    @Override
    protected BigDecimal payrollParameter(String code, LocalDate date) {
      if (!minWageLoaded || !PARAM_MIN_WAGE.equals(code)) {
        return null;
      }
      return MIN_WAGE;
    }

    @Override
    protected List<BgGarnishmentScale> garnishmentScale(LocalDate date) {
      return scaleLoaded ? scaleFromSeed() : Collections.emptyList();
    }

    /** Врата към защитения пазач — тестът е в същия пакет, но методът е шев на услугата. */
    String refusalFor(BgPaySlip slip) {
      return garnishmentRefusal(slip);
    }
  }

  // ═══ ФИКСТУРИТЕ ═══════════════════════════════════════════════════════════

  private static BgPaySlipLine line(int number, String sign, String amount) {
    BgPaySlipLineType type = new BgPaySlipLineType();
    type.setNumber(number);
    type.setBgSign(sign);
    BgPaySlipLine l = new BgPaySlipLine();
    l.setBgPaySlipLineType(type);
    l.setBgLineNumber(number);
    l.setBgSign(sign);
    l.setBgAmount(new BigDecimal(amount));
    l.setBgIsTaxable(false);
    l.setBgIsInsuranceIncome(false);
    return l;
  }

  /** Фиш с начисление и произволен набор удръжки. */
  private static BgPaySlip slip(String salary, BgPaySlipLine... deductions) {
    BgPaySlip s = new BgPaySlip();
    s.setStatusSelect(BgPaySlipResolver.DRAFT);
    Period p = new Period();
    p.setFromDate(LocalDate.of(2026, 3, 1));
    p.setToDate(LocalDate.of(2026, 3, 31));
    s.setPeriod(p);
    List<BgPaySlipLine> lines = new ArrayList<>();
    lines.add(line(100, BgPaySlipResolver.CREDIT, salary));
    Collections.addAll(lines, deductions);
    s.setBgPaySlipLineList(lines);
    return s;
  }

  private static BgPaySlipLine maintenance(String amount) {
    return line(BgPaySlipResolver.LINE_MAINTENANCE, BgPaySlipResolver.DEBIT, amount);
  }

  private static BgPaySlipLine pastDueMaintenance(String amount) {
    return line(BgPaySlipResolver.LINE_MAINTENANCE_PAST_DUE, BgPaySlipResolver.DEBIT, amount);
  }

  private static BgPaySlipLine garnishment(String amount) {
    return line(BgPaySlipResolver.LINE_GARNISHMENT, BgPaySlipResolver.DEBIT, amount);
  }

  private static BgPaySlipLine bankLoan(String amount) {
    return line(BgPaySlipResolver.LINE_LOAN_BANK, BgPaySlipResolver.DEBIT, amount);
  }

  // ═══ 1 · ЧЕТЦИТЕ НА ПЕРАТА ════════════════════════════════════════════════

  /**
   * ⚠️ ЧЛ. 446 РАБОТИ ВЪРХУ НЕТОТО ПРЕДИ УДРЪЖКИТЕ, не върху това, което остава накрая. Питан върху
   * вече намаленото число, таванът щеше да пада с всяка удръжка и втори проход да дава трети
   * отговор.
   */
  @Test
  @DisplayName("⚠️ netBeforeSeizure връща нетото ПРЕДИ 8xx: 1431,00, а не 1131,00")
  void theBaseIsTheNetBeforeTheDeductions() {
    BgPaySlip s = slip("1431.00", garnishment("200.00"), bankLoan("100.00"));

    assertEquals(
        0,
        new BigDecimal("1131.00").compareTo(BgPaySlipResolver.netTotal(s.getBgPaySlipLineList())),
        "netTotal вади всяка удръжка — това е каквото лицето получава накрая");
    assertEquals(
        0,
        new BigDecimal("1431.00")
            .compareTo(BgPaySlipResolver.netBeforeSeizure(s.getBgPaySlipLineList())),
        "⚠️ чл. 446 пита колко може да се ВЗЕМЕ от дохода, не какво е останало");
  }

  @Test
  @DisplayName("трите режима в 8xx се броят поотделно: издръжка · запор · по съгласие")
  void theThreeKindsOfDeductionAreCountedApart() {
    BgPaySlip s =
        slip(
            "2000.00",
            maintenance("150.00"),
            garnishment("300.00"),
            bankLoan("80.00"),
            line(BgPaySlipResolver.LINE_FINE, BgPaySlipResolver.DEBIT, "20.00"));
    List<BgPaySlipLine> lines = s.getBgPaySlipLineList();

    assertEquals(
        0, new BigDecimal("150.00").compareTo(BgPaySlipResolver.maintenanceWithheld(lines)));
    assertEquals(
        0, new BigDecimal("300.00").compareTo(BgPaySlipResolver.garnishmentWithheld(lines)));
    assertEquals(
        0,
        new BigDecimal("100.00").compareTo(BgPaySlipResolver.consentDeductions(lines)),
        "глоба 20,00 + банков заем 80,00");
  }

  // ═══ 2 · ИЗДРЪЖКАТА ИЗЛИЗА ПЪРВА И ЦЯЛА (АЛ. 4) ═══════════════════════════

  /**
   * ⚠️ ИЗДРЪЖКАТА НЕ СЕ ОГРАНИЧАВА И МОЖЕ ДА СВАЛИ ПОД МРЗ. Закрилата на несеквестируемия доход не
   * се отнася до вземания за издръжка. Ограничена от същия таван, тя би отстъпила на банков заем —
   * тоест дете би чакало, за да се плати потребителски кредит.
   */
  @Test
  @DisplayName(
      "⚠️ издръжка 400,00 при нето 800,00 сваля основата до 400,00 — ПОД МРЗ, и това е вярно")
  void maintenanceIsWithheldWholeAndMayGoBelowTheMinimumWage() {
    BgGarnishmentOutcome outcome =
        BgGarnishmentResolver.assess(
            new BigDecimal("800.00"),
            new BigDecimal("400.00"),
            NO_PAST_DUE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            MIN_WAGE,
            false,
            scaleFromSeed());

    assertEquals(
        0,
        new BigDecimal("400.00").compareTo(outcome.base),
        "⚠️ издръжката се вади ЦЯЛА, преди чл. 446 да е питан за каквото и да е");
    assertTrue(
        outcome.base.compareTo(MIN_WAGE) < 0,
        "и това я оставя ПОД МРЗ — закрилата не се отнася до издръжка");
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(outcome.limit),
        "⚠️ след издръжката за запора не остава нищо: остатъкът е под МРЗ");
  }

  @Test
  @DisplayName("⚠️ запор при остатък под МРЗ → отказ, който назовава тавана 0,00")
  void aGarnishmentOnTopOfMaintenanceThatEatsTheFloorIsRefused() {
    Service s = new Service();
    BgPaySlip slip = slip("800.00", maintenance("400.00"), garnishment("100.00"));

    String refusal = s.refusalFor(slip);

    assertNotNull(refusal, "⚠️ мълчание тук значи 100,00, удържани под несеквестируемия минимум");
    assertTrue(refusal.contains("0.00"), refusal);
    assertTrue(refusal.contains("821"), "отказът трябва да назове перото, в което влиза лимитът");
  }

  // ═══ 3 · ЗАПОРЪТ СЕ КЛАМПВА, НЕ ОТКАЗВА ПО ПРИНЦИП ════════════════════════

  @Test
  @DisplayName("запор в рамките на тавана → без отказ")
  void aGarnishmentWithinTheLimitPasses() {
    Service s = new Service();

    assertNull(s.refusalFor(slip("1431.00", garnishment("700.00"))));
  }

  /**
   * ⚠️ КЛАМПАТА СЕ СМЯТА И СЕ НАЗОВАВА, НО НЕ СЕ ЗАПИСВА. Отказът казва точното число за перо 821,
   * вместо ведомостта да пренапише реда на оператора: {@code netTotal} вади ВСЯКА удръжка, тоест
   * 820 и 821 са алтернативи, не слагаеми, и автоматичният запис би трябвало да изтрие 820 — а с
   * него изчезва единственият запис колко е поискал изпълнителният лист.
   */
  @Test
  @DisplayName("⚠️ запор 900,00 при таван 715,50 → отказ с точното число за 821 и остатъка 184,50")
  void aGarnishmentAboveTheLimitIsClampedAndNamed() {
    Service s = new Service();

    String refusal = s.refusalFor(slip("1431.00", garnishment("900.00")));

    assertNotNull(refusal);
    assertTrue(refusal.contains("715.50"), "таванът: 1/2 от 1431,00. " + refusal);
    assertTrue(refusal.contains("184.50"), "несъбраното: 900,00 − 715,50. " + refusal);
    assertTrue(
        refusal.contains("остава изискуема"),
        "⚠️ надвишеното НЕ отпада и това трябва да го пише — инак операторът ще реши, че е простено");
  }

  /** ⚠️ С деца таванът е ПО-НИСЪК (1/3 вместо 1/2) — същият фиш отказва на друго число. */
  @Test
  @DisplayName("⚠️ същият запор с деца: таванът пада на 477,00, защото скалата е друга")
  void theLimitIsLowerForADebtorWithChildren() {
    Service s = new Service();
    s.children = BgGarnishmentService.Status.WITH_CHILDREN;

    String refusal = s.refusalFor(slip("1431.00", garnishment("900.00")));

    assertNotNull(refusal);
    assertTrue(refusal.contains("477.00"), "1/3 от 1431,00. " + refusal);
  }

  // ═══ 4 · КТ ЧЛ. 272 АЛ. 2 — СБОРЪТ, И ДОБРОВОЛНОТО НЕ СЕ КЛАМПВА ══════════

  /**
   * ⚠️ УДРЪЖКА ПО СЪГЛАСИЕ НЕ СЕ НАМАЛЯВА МЪЛЧАЛИВО. Колко да се погаси по кредит е решение на
   * лицето и на кредитора, не на ведомостта. Затова сборът над тавана е ОТКАЗ, а не тиха клампа —
   * човек решава кое да падне.
   */
  @Test
  @DisplayName("⚠️ запор 700,00 + заем 100,00 = 800,00 над таван 715,50 → ОТКАЗ, не тиха клампа")
  void consentDeductionsPushingOverTheCeilingAreRefusedNotClamped() {
    Service s = new Service();

    String refusal = s.refusalFor(slip("1431.00", garnishment("700.00"), bankLoan("100.00")));

    assertNotNull(refusal);
    assertTrue(refusal.contains("272"), "основанието е КТ чл. 272 ал. 2. " + refusal);
    assertTrue(refusal.contains("800.00"), "сборът. " + refusal);
    assertTrue(refusal.contains("715.50"), "таванът. " + refusal);
    assertTrue(
        refusal.contains("100.00"),
        "⚠️ отказът трябва да каже КОЛКО идва от доброволното — инак не се знае какво да падне");
  }

  /**
   * ⚠️ ЧЛ. 447: ОТКАЗЪТ ОТ ЗАКРИЛАТА Е НЕДЕЙСТВИТЕЛЕН. Оттам следва, че „лицето се съгласи“ НЕ е
   * вход на тази сметка — доброволните удръжки се броят СРЕЩУ същия таван, а не над него. Проверява
   * се точно това: сборът с доброволна удръжка никога не разрешава повече от тавана без нея.
   */
  @Test
  @DisplayName(
      "⚠️ чл. 447 · съгласието НЕ вдига тавана — същият лимит със и без доброволна удръжка")
  void consentNeverRaisesTheCeiling() {
    BgGarnishmentOutcome withoutConsent =
        BgGarnishmentResolver.assess(
            new BigDecimal("1431.00"),
            BigDecimal.ZERO,
            NO_PAST_DUE,
            new BigDecimal("300.00"),
            BigDecimal.ZERO,
            MIN_WAGE,
            false,
            scaleFromSeed());
    BgGarnishmentOutcome withConsent =
        BgGarnishmentResolver.assess(
            new BigDecimal("1431.00"),
            BigDecimal.ZERO,
            NO_PAST_DUE,
            new BigDecimal("300.00"),
            new BigDecimal("400.00"),
            MIN_WAGE,
            false,
            scaleFromSeed());

    assertEquals(
        0,
        withoutConsent.limit.compareTo(withConsent.limit),
        "⚠️ таванът е свойство на дохода, не на това какво страните са уговорили");
    assertNull(withConsent.refusal, "300 + 400 = 700 още се побира под 715,50");
  }

  // ═══ 5 · НЕУСТАНОВИМИ ДЕЦА ════════════════════════════════════════════════

  /**
   * ⚠️ „НЕ СЕ ЗНАЕ“ НЕ Е „НЯМА ДЕЦА“. Прочетено така, то вдига тавана от 1/3 на 1/2 и удържа ПОВЕЧЕ
   * от родител, чийто картон просто не е попълнен — а това не изглежда като грешка на нито един
   * фиш.
   */
  @Test
  @DisplayName("⚠️ запор при НЕУСТАНОВИМИ деца → ОТКАЗ, не мълчаливо „без деца“")
  void aGarnishmentWithUndeterminableChildrenIsRefused() {
    Service s = new Service();
    s.children = BgGarnishmentService.Status.UNDETERMINABLE;

    String refusal = s.refusalFor(slip("1431.00", garnishment("300.00")));

    assertNotNull(
        refusal,
        "⚠️ 300,00 се побират под всеки от двата тавана — и точно затова"
            + " мълчанието тук би минало незабелязано, докато доходът не се вдигне");
    assertTrue(refusal.contains("деца"), refusal);
    assertTrue(
        refusal.contains("ТЕЛК") || refusal.contains("дата на раждане"),
        "отказът трябва да каже КОЕ да се попълни. " + refusal);
  }

  /**
   * ⚠️ БЕЗ ЗАПОР неустановимото НЕ блокира — чете се КОНСЕРВАТИВНО, като „с деца“, тоест по-нисък
   * таван. Блокиране на всеки фиш със заем заради непопълнен картон би спирало верните случаи
   * заради данни, които никой не е поискал за тях.
   */
  @Test
  @DisplayName("⚠️ БЕЗ запор неустановимото не блокира, а взима ПО-НИСКИЯ таван (477,00)")
  void withoutAGarnishmentTheUndeterminableCaseTakesTheLowerCeiling() {
    Service passing = new Service();
    passing.children = BgGarnishmentService.Status.UNDETERMINABLE;
    assertNull(
        passing.refusalFor(slip("1431.00", bankLoan("200.00"))),
        "200,00 се побират под по-ниския таван 477,00");

    Service failing = new Service();
    failing.children = BgGarnishmentService.Status.UNDETERMINABLE;
    String refusal = failing.refusalFor(slip("1431.00", bankLoan("500.00")));
    assertNotNull(refusal, "500,00 надхвърлят по-ниския таван");
    assertTrue(
        refusal.contains("477.00"),
        "⚠️ при съмнение се взима 1/3, не 1/2 — тоест удържа се ПО-МАЛКО. " + refusal);
  }

  // ═══ 6 · ПАЗАЧЪТ МЪЛЧИ, КОГАТО НЯМА КАКВО ДА ПАЗИ ═════════════════════════

  /**
   * ⚠️ САМО ИЗДРЪЖКА НЕ ЗАДЕЙСТВА ПАЗАЧА. Тя е ИЗВЪН закрилата и няма таван, срещу който да се
   * мери. Иначе всеки фиш с издръжка щеше да иска заредена скала, за да се потвърди.
   */
  @Test
  @DisplayName("фиш само с издръжка → пазачът мълчи, дори скалата да не е заредена")
  void aSlipWithOnlyMaintenanceDoesNotEngageTheGuard() {
    Service s = new Service();
    s.scaleLoaded = false;

    assertNull(s.refusalFor(slip("800.00", maintenance("400.00"))));
  }

  @Test
  @DisplayName("фиш без нито една удръжка от 8xx → пазачът мълчи")
  void aSlipWithoutDeductionsDoesNotEngageTheGuard() {
    assertNull(new Service().refusalFor(slip("1431.00")));
  }

  // ═══ 7 · ЛИПСВАЩИТЕ ДАННИ СА ОТКАЗ, НЕ „УДРЪЖАЙ ВСИЧКО“ ═══════════════════

  @Test
  @DisplayName("⚠️ незаредена скала при наличен запор → ОТКАЗ с назована причина")
  void aMissingScaleIsRefused() {
    Service s = new Service();
    s.scaleLoaded = false;

    String refusal = s.refusalFor(slip("1431.00", garnishment("300.00")));

    assertNotNull(refusal, "⚠️ мълчание тук значи удържане без нито една проверена граница");
    assertTrue(refusal.contains("446"), refusal);
    assertTrue(refusal.contains("447"), "чл. 447 прави закрилата неотменяема — отказът го казва");
  }

  @Test
  @DisplayName("⚠️ незаредена МРЗ при наличен запор → ОТКАЗ")
  void aMissingMinimumWageIsRefused() {
    Service s = new Service();
    s.minWageLoaded = false;

    assertNotNull(s.refusalFor(slip("1431.00", garnishment("300.00"))));
  }

  // ═══ 8 · ИЗДРЪЖКА ЗА МИНАЛО ВРЕМЕ (831) — ЧЕТЕ СЕ ОТДЕЛНО ОТ 830 ══════════

  /**
   * ⚠️ ДВЕТЕ ПЕРА НА ИЗДРЪЖКАТА СЕ БРОЯТ ПООТДЕЛНО. Събрани в едно число, просрочената щеше да се
   * вади от нетото преди тавана — тоест да мине под закрилата, която ал. 4 дава само на текущата.
   */
  @Test
  @DisplayName("⚠️ 830 и 831 се броят ПООТДЕЛНО: текуща 150,00 срещу просрочена 250,00")
  void theCurrentAndThePastDueMaintenanceAreCountedApart() {
    BgPaySlip s =
        slip(
            "2000.00",
            maintenance("150.00"),
            pastDueMaintenance("250.00"),
            garnishment("300.00"),
            bankLoan("80.00"),
            line(BgPaySlipResolver.LINE_FINE, BgPaySlipResolver.DEBIT, "20.00"));
    List<BgPaySlipLine> lines = s.getBgPaySlipLineList();

    assertEquals(
        0,
        new BigDecimal("150.00").compareTo(BgPaySlipResolver.maintenanceWithheld(lines)),
        "⚠️ САМО перо 830 — просрочената НЕ се прибавя тук");
    assertEquals(
        0,
        new BigDecimal("250.00").compareTo(BgPaySlipResolver.pastDueMaintenanceWithheld(lines)),
        "перо 831, отделно число");
    assertEquals(
        0, new BigDecimal("300.00").compareTo(BgPaySlipResolver.garnishmentWithheld(lines)));
    assertEquals(
        0,
        new BigDecimal("100.00").compareTo(BgPaySlipResolver.consentDeductions(lines)),
        "глоба 20,00 + банков заем 80,00 — 831 НЕ е доброволна удръжка");
    assertEquals(
        0,
        new BigDecimal("2000.00").compareTo(BgPaySlipResolver.netBeforeSeizure(lines)),
        "⚠️ и 831 се връща обратно в нетото, върху което чл. 446 работи — то е удръжка от 8xx");
  }

  /**
   * ⚠️ САМО ПРОСРОЧЕНА ИЗДРЪЖКА ЗАДЕЙСТВА ПАЗАЧА — за разлика от само текуща. Тя се удържа „върху
   * остатъка“, по ал. 1, тоест ИМА таван и може да го надхвърли сама, без нито един запор на фиша.
   * Пропусната, тя щеше да мине без нито една проверена граница.
   */
  @Test
  @DisplayName("⚠️ фиш САМО с перо 831 над тавана → ОТКАЗ, който назовава 831 и клампата 650,00")
  void aSlipWithOnlyPastDueMaintenanceEngagesTheGuard() {
    Service s = new Service();

    String refusal =
        s.refusalFor(slip("1500.00", maintenance("200.00"), pastDueMaintenance("700.00")));

    assertNotNull(
        refusal,
        "⚠️ мълчание тук значи 700,00 удържани по ал. 1 без нито една проверена граница —"
            + " и лицето остава под МРЗ");
    assertTrue(refusal.contains("831"), "отказът трябва да назове перото. " + refusal);
    assertTrue(refusal.contains("650.00"), "клампата: 1/2 от 1300,00. " + refusal);
    assertTrue(refusal.contains("50.00"), "несъбраното: 700,00 − 650,00. " + refusal);
    assertTrue(
        refusal.contains("остава изискуема"),
        "⚠️ надвишеното НЕ отпада и това трябва да го пише — както при запора");
  }

  @Test
  @DisplayName("фиш с перо 831 в рамките на тавана → без отказ")
  void aPastDueMaintenanceWithinTheLimitPasses() {
    Service s = new Service();

    assertNull(
        s.refusalFor(slip("1500.00", maintenance("200.00"), pastDueMaintenance("300.00"))),
        "300,00 се побират под таван 650,00");
  }

  /**
   * ⚠️ САМО ТЕКУЩА ИЗДРЪЖКА ПРОДЪЛЖАВА ДА НЕ ЗАДЕЙСТВА ПАЗАЧА. Границата между двете пера се вижда
   * най-добре тук: същият фиш с 830 минава без заредена скала, а с 831 иска граница.
   */
  @Test
  @DisplayName("⚠️ границата: фиш само с 830 минава без скала, същият с 831 — ОТКАЗ")
  void onlyTheCurrentMaintenanceLeavesTheGuardSilent() {
    Service withCurrent = new Service();
    withCurrent.scaleLoaded = false;
    assertNull(
        withCurrent.refusalFor(slip("800.00", maintenance("400.00"))),
        "текущата издръжка е ИЗВЪН закрилата — няма таван, срещу който да се мери");

    Service withPastDue = new Service();
    withPastDue.scaleLoaded = false;
    String refusal = withPastDue.refusalFor(slip("800.00", pastDueMaintenance("400.00")));
    assertNotNull(
        refusal, "⚠️ просрочената се удържа ПО АЛ. 1 — без скала тя е отказ, не удържане");
    assertTrue(refusal.contains("446"), refusal);
  }

  /**
   * ⚠️ РЕДЪТ СЕ НАЗОВАВА, КОГАТО МЕСТИ ПАРИ. Просрочена 700,00 изяжда целия таван и за запора
   * остава нула — операторът трябва да прочете ЗАЩО, инак ще реши, че запорът е забравен.
   */
  @Test
  @DisplayName("⚠️ 831 + 820 над тавана → отказът назовава двете пера и реда (ЗЗД чл. 136)")
  void theRefusalNamesBothLinesAndTheOrderBetweenThem() {
    Service s = new Service();

    String refusal =
        s.refusalFor(
            slip(
                "1500.00",
                maintenance("200.00"),
                pastDueMaintenance("700.00"),
                garnishment("500.00")));

    assertNotNull(refusal);
    assertTrue(refusal.contains("831"), "перото на просрочената. " + refusal);
    assertTrue(refusal.contains("821"), "перото на ограничения запор. " + refusal);
    assertTrue(refusal.contains("136"), "основанието за реда — ЗЗД чл. 136 ал. 1 т. 5. " + refusal);
    assertTrue(
        refusal.contains("сверена") || refusal.contains("caselaw"),
        "⚠️ и disclaimer-а: точката НЕ е сверена срещу официален ЗЗД. " + refusal);
  }

  /**
   * ⚠️ ПРОСРОЧЕНАТА ИЗДРЪЖКА ПИТА ЗА ДЕЦАТА СЪС СЪЩАТА ТЕЖЕСТ КАТО ЗАПОРА, защото и тя се удържа по
   * ал. 1, а признакът избира между двете скали. 300,00 се побират под всеки от двата тавана —
   * точно затова мълчанието тук би минало незабелязано.
   */
  @Test
  @DisplayName("⚠️ перо 831 при НЕУСТАНОВИМИ деца → ОТКАЗ, не мълчаливо „без деца“")
  void aPastDueMaintenanceWithUndeterminableChildrenIsRefused() {
    Service s = new Service();
    s.children = BgGarnishmentService.Status.UNDETERMINABLE;

    String refusal = s.refusalFor(slip("1500.00", pastDueMaintenance("300.00")));

    assertNotNull(refusal, "⚠️ признакът избира МЕЖДУ ДВЕ СКАЛИ и за това перо");
    assertTrue(refusal.contains("деца"), refusal);
    assertTrue(refusal.contains("831"), "отказът трябва да каже КОЕ перо го задейства. " + refusal);
  }
}

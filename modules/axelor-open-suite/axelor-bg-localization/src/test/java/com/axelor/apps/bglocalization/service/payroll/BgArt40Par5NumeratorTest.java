package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.BgInsurableIncomeResult;
import com.axelor.apps.bglocalization.service.BgInsurableIncomeService;
import com.axelor.apps.bglocalization.service.BgMinIncomeResult;
import com.axelor.apps.bglocalization.service.BgWorkTimeFacts;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ДНИТЕ ПО ЧЛ. 40 АЛ. 5 КСО ВЛИЗАТ В ЧИСЛИТЕЛЯ НА НЕВД ЧЛ. 1 АЛ. 3 — СЪС ЧИСЛА.
 *
 * <p><b>Грешката, която този тест заковава.</b> Ако {@code BgPayrollWorkTime.decide} подаде {@code
 * notSupplied("art40Par5Days")} — тоест {@code null}, тоест НУЛА — за третия аргумент на {@code
 * BgWorkTimeFacts.byDays(...)}, докато {@code BgPaySlip.bgSickDaysEmployer} вече е попълнено от
 * болничния енджин на ВСЕКИ фиш, числителят губи дните по чл. 40 ал. 5. Нормата дава числителя като
 * „отработените дни И ДНИТЕ ПО ЧЛ. 40, АЛ. 5 от КСО“; подаден без вторите, той е по-малък,
 * коефициентът — по-малък, МОД прагът — по-нисък, и осигурителната база — по-ниска. ЗАНИЖАВАНЕ, и
 * то тихо: и двете числа изглеждат пресметнати.
 *
 * <p><b>Защо чист тест, а не фиш.</b> Модулът няма mock-подпора и правило, което иска свидетел с
 * ЧИСЛА, отива в чиста функция — тук {@code decide} → {@code BgWorkTimeFacts} → {@code
 * BgInsurableIncomeService.compute}. Навързването към фиша (кой чете {@code bgSickDaysEmployer} и в
 * какъв ред) е ИНВАРИАНТ върху сорса в края на този файл, по образеца на {@code
 * BgWorkTimeFactsReachTest} — поведенчески тест на аритметиката минава зелен и когато никой не я
 * вика.
 *
 * <p><b>⚠️ ЗАЩО ЗЛАТНИЯТ ФИШ НЕ ГО ХВАНА.</b> {@code BgSickLeaveGoldenTest} е лице, болно ЦЕЛИЯ
 * месец: отработени нула, прагът пораства от 0,00 до 93,65 и си остава ПОД болничния доход 109,87,
 * а базата е {@code max(доход, праг)}. Тоест точно пълният болничен месец е случаят, в който
 * грешката НЕ се вижда. Вижда се при ЧАСТИЧНО отработен месец — този тест.
 */
class BgArt40Par5NumeratorTest {

  /** Работните дни в месеца — знаменателят по НЕВД чл. 1 ал. 3. */
  private static final int WORKING_DAYS = 21;

  /** Отработените дни — първата част на числителя. */
  private static final int WORKED_DAYS = 9;

  /** Дните за сметка на осигурителя по чл. 40 ал. 5 КСО — ВТОРАТА част на числителя. */
  private static final int EMPLOYER_SICK_DAYS = 2;

  /** МОД за периода — представителният ред, същият като в златния фиш. */
  private static final BigDecimal MOD = new BigDecimal("1077.00");

  /** ЗБДОО чл. 9 т. 1 б. „в“ — максималният осигурителен доход. */
  private static final BigDecimal CAP = new BigDecimal("2111.64");

  private static final LocalDate DATE = LocalDate.of(2026, 7, 1);

  /**
   * Прагът БЕЗ числителя по чл. 40 ал. 5 — заварената, ЗАНИЖЕНА стойност. 1077,00 × 9/21.
   *
   * <p>Изписан като литерал, а не сметнат в теста: сметка в свидетеля повтаря сметката на кода и
   * минава зелена и когато и двете са грешни.
   */
  private static final BigDecimal UNDERSTATED_FLOOR = new BigDecimal("461.57");

  /** Прагът С числителя — ВЯРНАТА стойност. 1077,00 × 11/21. */
  private static final BigDecimal CORRECT_FLOOR = new BigDecimal("564.14");

  /** Пълно работно време, ЧАСТИЧНО отработен месец — фикстурата, в която числителят се вижда. */
  private static BgPayrollInput partlyWorkedMonth() {
    return BgPayrollInput.builder()
        .contractHoursPerDay(new BigDecimal("8.00"))
        .workingDaysInPeriod(WORKING_DAYS)
        .workedDays(WORKED_DAYS)
        .build();
  }

  /** Пълно работно време, ЦЯЛ отработен месец — фикстурата „нормален фиш“. */
  private static BgPayrollInput fullyWorkedMonth() {
    return BgPayrollInput.builder()
        .contractHoursPerDay(new BigDecimal("8.00"))
        .workingDaysInPeriod(WORKING_DAYS)
        .workedDays(WORKING_DAYS)
        .build();
  }

  private static BgInsurableIncomeResult baseFor(BgWorkTimeFacts facts, BigDecimal gross) {
    return BgInsurableIncomeService.compute(
        gross,
        BgMinIncomeResult.resolved(MOD, "МОД-представителен", 1),
        null,
        true,
        CAP,
        DATE,
        facts);
  }

  // ═══ 1 · ЧИСЛИТЕЛЯТ — ПОСОКАТА И РАЗМЕРЪТ ════════════════════════════════

  @Test
  @DisplayName("⚠️ СВИДЕТЕЛ · 2 дни по чл. 40 ал. 5 вдигат коефициента от 9/21 на 11/21")
  void theEmployerPaidSickDaysEnterTheNumerator() {
    BgWorkTimeFacts without = BgPayrollWorkTime.decide(partlyWorkedMonth()).facts;
    BgWorkTimeFacts with = BgPayrollWorkTime.decide(partlyWorkedMonth(), EMPLOYER_SICK_DAYS).facts;

    // Котвата: и двата пътя наистина стигат до ДНЕВНИЯ ред, не до отказ и не до „без пропорция“.
    assertNotNull(without, "котвата: очаквах факти, не отказ");
    assertNotNull(with, "котвата: очаквах факти, не отказ");
    assertTrue(
        without.hasDayData() && with.hasDayData(), "котвата: НЕВД чл. 1 ал. 3, дневният ред");
    assertEquals(
        0,
        BigDecimal.valueOf(WORKING_DAYS).compareTo(with.getWorkingDaysInMonth()),
        "котвата: знаменателят е РАБОТНИТЕ ДНИ в месеца");

    assertNull(
        without.getArt40Par5Days(),
        "⚠️ ЗАВАРЕНОТО ПОВЕДЕНИЕ, ЗАПАЗЕНО: едноарговият decide НЕ подава дните — той е входът на"
            + " диагностичния екран, който фиш не вижда и няма откъде да знае разделянето.");
    assertEquals(
        0,
        BigDecimal.valueOf(EMPLOYER_SICK_DAYS).compareTo(with.getArt40Par5Days()),
        "⚠️ ПОПРАВКАТА: двуарговият decide подава ТОЧНО дните за сметка на осигурителя.");

    // ⚠️ Отработените дни НЕ се пипат — дните по чл. 40 ал. 5 са ОТДЕЛЕН носач.
    assertEquals(
        0,
        BigDecimal.valueOf(WORKED_DAYS).compareTo(with.getWorkedDays()),
        "⚠️ БЕЗ ДВОЙНО БРОЕНЕ, ИЗМЕРЕНО ОТ ТАЗИ СТРАНА: болничните дни влизат като СВОЙ носач и не"
            + " се добавят към отработените. Слети, те щяха да са неразличими от отработени дни, а"
            + " ал. 3 ги брои поименно и отделно.");
  }

  @Test
  @DisplayName("⚠️ СВИДЕТЕЛ · базата е 564,14, а не занижените 461,57")
  void theBaseIsTheHigherCorrectFloorNotTheUnderstatedOne() {
    // Бруто ПОД двата прага, тоест прагът е това, което стига до базата.
    BigDecimal gross = new BigDecimal("300.00");

    BigDecimal understated =
        baseFor(BgPayrollWorkTime.decide(partlyWorkedMonth()).facts, gross).getAmount();
    BigDecimal correct =
        baseFor(BgPayrollWorkTime.decide(partlyWorkedMonth(), EMPLOYER_SICK_DAYS).facts, gross)
            .getAmount();

    assertEquals(
        0,
        UNDERSTATED_FLOOR.compareTo(understated),
        "котвата: заварената сметка дава точно 461,57 — 1077,00 × 9/21");
    assertEquals(
        0,
        CORRECT_FLOOR.compareTo(correct),
        "⚠️ ВЯРНАТА СТОЙНОСТ. НЕВД чл. 1 ал. 3: числителят е „отработените дни И дните по чл. 40,"
            + " ал. 5 от КСО“ — 11 от 21, тоест 1077,00 × 11/21 = 564,14.");

    assertTrue(
        correct.compareTo(understated) > 0,
        "⚠️ ПОСОКАТА, ЗАКОВАНА: числителят ВДИГА базата. Ако това някога стане обратното, грешката"
            + " се е върнала с обърнат знак.");
    assertEquals(
        0,
        new BigDecimal("102.57").compareTo(correct.subtract(understated)),
        "⚠️ РАЗМЕРЪТ на тихото занижаване на ЕДИН фиш: 102,57 евро осигурителна база.");
  }

  @Test
  @DisplayName("⚠️ СВИДЕТЕЛ · занижаването боли и когато брутото е НАД занижения праг")
  void theDefectBitesEvenWhenTheGrossClearsTheUnderstatedFloor() {
    // Бруто МЕЖДУ двата прага: завареният праг вече не хваща, верният още хваща.
    BigDecimal gross = new BigDecimal("500.00");

    BigDecimal understated =
        baseFor(BgPayrollWorkTime.decide(partlyWorkedMonth()).facts, gross).getAmount();
    BigDecimal correct =
        baseFor(BgPayrollWorkTime.decide(partlyWorkedMonth(), EMPLOYER_SICK_DAYS).facts, gross)
            .getAmount();

    assertEquals(
        0,
        gross.compareTo(understated),
        "⚠️ ТОЧНО ТУК ДЕФЕКТЪТ Е НАЙ-НЕВИДИМ: занижениият праг 461,57 е ПОД брутото, тоест базата"
            + " става самото бруто и на екрана няма нито праг, нито закръгляне, което да изглежда"
            + " странно.");
    assertEquals(
        0,
        CORRECT_FLOOR.compareTo(correct),
        "⚠️ А верният праг 564,14 е НАД брутото и го надвишава");
  }

  // ═══ 2 · НОРМАЛНИЯТ ФИШ — ИДЕНТИЧЕН ИЗХОД ════════════════════════════════

  @Test
  @DisplayName("⚠️ ГЕЙТ · фиш БЕЗ болничен минава ИДЕНТИЧНО през двата входа")
  void aPaySlipWithoutSickLeaveIsUntouched() {
    // ⚠️ И НУЛАТА, И null: генерираният getter за Integer връща 0 за непопълнена колона
    // (BgDefaultedGetterTest), тоест „няма болничен“ пристига като 0, не като null.
    BgWorkTimeFacts oneArg = BgPayrollWorkTime.decide(fullyWorkedMonth()).facts;
    BgWorkTimeFacts zero = BgPayrollWorkTime.decide(fullyWorkedMonth(), 0).facts;
    BgWorkTimeFacts nullDays = BgPayrollWorkTime.decide(fullyWorkedMonth(), null).facts;

    for (BgWorkTimeFacts facts : new BgWorkTimeFacts[] {zero, nullDays}) {
      assertNull(
          facts.getArt40Par5Days(),
          "⚠️ Нулата НЕ Е ТВЪРДЕНИЕ и не се подава като ZERO: подадена, тя щеше да смени"
              + " диагностичното изречение на всеки фиш без болничен, без да смени нито едно"
              + " число.");
      assertEquals(oneArg.toString(), facts.toString(), "наборът факти е идентичен");
    }

    BigDecimal gross = new BigDecimal("300.00");
    assertEquals(
        0,
        MOD.compareTo(baseFor(zero, gross).getAmount()),
        "⚠️ ЦЯЛ отработен месец → коефициент 1 → прагът е целият МОД, точно както преди"
            + " поправката");
    assertEquals(
        baseFor(oneArg, gross).getAmount(),
        baseFor(zero, gross).getAmount(),
        "⚠️ BLAST RADIUS, ИЗМЕРЕН: нормалният фиш дава СЪЩОТО число през новия вход");
    assertEquals(
        BgPayrollWorkTime.describe(BgPayrollWorkTime.decide(fullyWorkedMonth()), true),
        BgPayrollWorkTime.describe(BgPayrollWorkTime.decide(fullyWorkedMonth(), 0), true),
        "⚠️ И ДИАГНОСТИЧНОТО ИЗРЕЧЕНИЕ е буква по буква същото — то е това, което ТРЗ чете");
  }

  @Test
  @DisplayName("⚠️ СВИДЕТЕЛ · диагностиката ИЗПИСВА числителя, който е ползвала")
  void theDiagnosticSentenceNamesTheArtFortyParFiveDays() {
    String withDays =
        BgPayrollWorkTime.describe(
            BgPayrollWorkTime.decide(partlyWorkedMonth(), EMPLOYER_SICK_DAYS), true);
    String withoutDays =
        BgPayrollWorkTime.describe(BgPayrollWorkTime.decide(partlyWorkedMonth()), true);

    assertTrue(withDays.contains("чл. 40 ал. 5"), withDays);
    assertTrue(
        withDays.contains("+ 2 дни"),
        "⚠️ ДИАГНОСТИКАТА НЕ БИВА ДА ЛЪЖЕ ЗА ВХОДА СИ. Изречение „отработени 9 от 21“ при"
            + " коефициент от 11/21 би накарало странното число да се проследи до грешна СМЕТКА"
            + " вместо до недоизписан числител. Намерено: "
            + withDays);
    assertTrue(
        !withoutDays.contains("чл. 40 ал. 5"),
        "⚠️ И клаузата се появява САМО когато дните са подадени — инак фиш без болничен получава"
            + " текста без клаузата: "
            + withoutDays);
  }

  // ═══ 3 · МУТАЦИОННО · НАВЪРЗАНОСТТА КЪМ ФИША ═════════════════════════════

  private static final Path WORK_TIME =
      Paths.get(
          "src/main/java/com/axelor/apps/bglocalization/service/payroll/BgPayrollWorkTime.java");

  private static final Path PAY_SLIP_SERVICE =
      Paths.get("src/main/java/com/axelor/apps/bglocalization/service/BgPaySlipService.java");

  /**
   * ⚠️ ИНВАРИАНТ: фишът вика ДВУАРГОВИЯ {@code decide} и му подава {@code bgSickDaysEmployer}.
   *
   * <p><b>Защо тест върху сорса.</b> Твърдението е за СВЪРЗАНОСТ, не за резултат — точно това,
   * което тук може да се наруши: аритметиката на числителя може да е вярна и покрита, а
   * производственият път да подава {@code null}. Роднини са {@code BgWorkTimeFactsReachTest} и
   * {@code BgWorkTimeGateTest.noCallerReadsTheRefusalWithoutTheProratesCondition}.
   */
  @Test
  @DisplayName("⚠️ ИНВАРИАНТ · BgPaySlipService подава bgSickDaysEmployer на decide")
  void thePaySlipPathSuppliesTheEmployerSickDays() throws IOException {
    String code = new String(Files.readAllBytes(PAY_SLIP_SERVICE), StandardCharsets.UTF_8);
    assertTrue(
        code.contains("BgPayrollWorkTime.decide(input, slip.getBgSickDaysEmployer())"),
        "⚠️ РЕГРЕСИЯ: производственият път вече не подава дните по чл. 40 ал. 5 на пропорцията."
            + " Резултатът е по-нисък коефициент, по-нисък МОД праг и по-ниска осигурителна база —"
            + " и нищо на екрана не изглежда сбъркано.");
  }

  /**
   * ⚠️ ИНВАРИАНТ: РЕДЪТ. Болничният енджин пълни полето ПРЕДИ вноските да го прочетат.
   *
   * <p>Разменени, {@code decide} щеше да получи нулата на непопълнено поле и числителят щеше да е
   * мъртъв — тихо, защото нулата и обявената липса дават ЕДНО И СЪЩО число.
   */
  @Test
  @DisplayName("⚠️ ИНВАРИАНТ · generateSickLeave се вика ПРЕДИ generateContributions")
  void theSickLeaveEngineRunsBeforeTheContributions() throws IOException {
    String code = new String(Files.readAllBytes(PAY_SLIP_SERVICE), StandardCharsets.UTF_8);
    int sick = code.indexOf("    generateSickLeave(slip);");
    int contributions = code.indexOf("    generateContributions(slip);");
    assertTrue(sick > 0, "котвата: не намирам извикването на generateSickLeave в prepare(...)");
    assertTrue(
        contributions > 0,
        "котвата: не намирам извикването на generateContributions в prepare(...)");
    assertTrue(
        sick < contributions,
        "⚠️ РЕГРЕСИЯ В РЕДА: bgSickDaysEmployer се пълни от generateSickLeave и се ЧЕТЕ от"
            + " generateContributions. Разменени, числителят получава нула и поправката умира"
            + " тихо.");
  }

  /**
   * ⚠️ МУТАЦИОННО: третият аргумент на {@code byDays} не е ЗАКОВАН на обявена липса.
   *
   * <p>Мутацията, която този тест лови, е точно грешката: {@code notSupplied("art40Par5Days")}
   * върнат директно в извикването на {@code byDays}. Тя е ЗЕЛЕНА за всеки поведенчески тест, който
   * не подава дни — тоест за всичките заварени.
   */
  @Test
  @DisplayName("⚠️ МУТАЦИОННО · byDays НЕ получава notSupplied(...) на място")
  void theByDaysCallDoesNotHardCodeTheDeclaredAbsence() throws IOException {
    String code = new String(Files.readAllBytes(WORK_TIME), StandardCharsets.UTF_8);
    assertTrue(
        code.contains("BgWorkTimeFacts.byDays("), "котвата: не намирам извикването на byDays");
    assertTrue(
        !code.contains(
            "workingDays, new BigDecimal(input.workedDays), notSupplied(\"art40Par5Days\")"),
        "⚠️ ДЕФЕКТЪТ СЕ Е ВЪРНАЛ: третият аргумент на byDays пак е заковано „не се подава“. Дните"
            + " по чл. 40 ал. 5 КСО влизат в числителя по НЕВД чл. 1 ал. 3 и фишът вече ги носи в"
            + " bgSickDaysEmployer.");
    assertTrue(
        code.contains("art40Par5DaysOf(art40Par5Days)"),
        "⚠️ числителят минава през гейта art40Par5DaysOf(...), който пази нормалния фиш"
            + " непроменен");
  }

  /**
   * ⚠️ ОСТАВАЩАТА ЛИПСА, ОБЯВЕНА И ИЗМЕРЕНА: ЧАСОВЕТЕ по ал. 4 още НЕ се подават.
   *
   * <p>Поправката е за ДНЕВНИЯ ред. Часовият остава занижен по същия начин и при НЕПЪЛНО работно
   * време, защото фишът носи разделението в РАБОТНИ ДНИ, а часове за тези дни няма никъде. Записано
   * тук, за да не мине за свършено.
   */
  @Test
  @DisplayName("⚠️ ОБЯВЕНА ЛИПСА · часовете по чл. 40 ал. 5 (ал. 4) остават неподадени")
  void theHourlyRuleStillDoesNotSupplyTheArtFortyParFiveHours() {
    BgPayrollInput partTime =
        BgPayrollInput.builder()
            .contractHoursPerDay(new BigDecimal("4.00"))
            .workingDaysInPeriod(WORKING_DAYS)
            .workedHours(new BigDecimal("84.00"))
            .build();

    BgWorkTimeFacts facts = BgPayrollWorkTime.decide(partTime, EMPLOYER_SICK_DAYS).facts;
    assertNotNull(facts, "котвата: непълното работно време стига до ал. 4, не до отказ");
    assertTrue(facts.hasHourData(), "котвата: НЕВД чл. 1 ал. 4, часовият ред");
    assertNull(
        facts.getArt40Par5Hours(),
        "⚠️ ОБЯВЕНА ЛИПСА: часовете НЕ се подават и това е записано с причина в"
            + " BgPayrollWorkTime.NOT_SUPPLIED. ⚠️ И ДНИТЕ НЕ СЕ ПРЕЛИВАТ В ЧАСОВИЯ РЕД: подадени"
            + " като часове, 2 работни дни щяха да са ДОПУСКАНЕ за дневна норма — а при непълно"
            + " работно време дневната норма е точно това, което НЕ е осем часа.");

    assertTrue(
        BgPayrollWorkTime.NOT_SUPPLIED.containsKey("art40Par5Hours"),
        "⚠️ липсата остава ОБЯВЕНА, а не премълчана");
    assertTrue(
        BgPayrollWorkTime.NOT_SUPPLIED.get("art40Par5Hours").contains("ЗАНИЖАВАНЕ"),
        "⚠️ и причината казва ПОСОКАТА, за да не се чете като безобидна: "
            + BgPayrollWorkTime.NOT_SUPPLIED.get("art40Par5Hours"));
  }
}

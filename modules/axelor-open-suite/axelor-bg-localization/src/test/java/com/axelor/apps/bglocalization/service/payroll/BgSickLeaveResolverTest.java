package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЧИСТАТА ПОЛОВИНА: аритметиката на ДНИТЕ и на СУМИТЕ, без база, без Guice, без Hibernate.
 *
 * <p>Всеки свидетел е чупен преднамерено, преди да остане зелен. Числата не са измислени: юли 2026
 * г. е реален месец — 1 юли е СРЯДА, месецът има 23 работни дни и нито един официален празник, — а
 * заплатата 1805,00 и разделянето 2 + 21 идват от сверения фиш на Микроинвест за 07.2026 г.
 *
 * <p><b>Какво този файл НЕ проверява.</b> Че перото се записва на фиша, че осигуровките се смятат
 * върху болничния и че {@code prepare()} вика енджина — това е базовата половина ({@code
 * BgSickLeaveServiceTest}, {@code BgSickLeaveGoldenTest}). Разделянето е нарочно: правило, написано
 * до заявка към базата, спира да е проверимо без база.
 */
class BgSickLeaveResolverTest {

  private static final LocalDate JULY_FROM = LocalDate.of(2026, 7, 1);
  private static final LocalDate JULY_TO = LocalDate.of(2026, 7, 31);

  /** Юли 2026: 1 юли е сряда, 23 работни дни, нула официални празници. */
  private static final int JULY_WORKING_DAYS = 23;

  private static final BigDecimal SALARY = new BigDecimal("1805.00");
  private static final BigDecimal PCT_70 = new BigDecimal("70.00");

  /**
   * {@code HEALTH_ABSENCE_BASE} за 01.01–31.07.2026 г. — пълният минимум за самоосигуряващите се.
   */
  private static final BigDecimal HEALTH_BASE_H1 = new BigDecimal("550.6600");

  /** {@code HEALTH_ABSENCE_BASE} от 01.08.2026 г. — ЗБДОО 2026, ДВ бр. 68/28.07.2026. */
  private static final BigDecimal HEALTH_BASE_H2 = new BigDecimal("620.2000");

  /** Ставката на фонд HEALTH за платеца РАБОТОДАТЕЛ, вид 01, трета категория, 2026 г. */
  private static final BigDecimal HEALTH_EMPLOYER_RATE = new BigDecimal("4.8000");

  // ═══ ФИКСТУРИ ═════════════════════════════════════════════════════════════

  private static BgSickLeaveEpisode episode(
      String from, String to, boolean continuation, String code, String payer, String pct) {
    return new BgSickLeaveEpisode(
        LocalDate.parse(from),
        LocalDate.parse(to),
        continuation,
        code,
        payer,
        pct == null ? null : new BigDecimal(pct),
        null);
  }

  /** Обикновен болничен за общо заболяване: платец осигурителят, 70 на сто — както в речника. */
  private static BgSickLeaveEpisode sickEr(String from, String to, boolean continuation) {
    return episode(from, to, continuation, "sick_er", "employer", "70.00");
  }

  private static final Set<LocalDate> NO_HOLIDAYS = Collections.emptySet();

  /** Разделяне при достатъчен стаж и пълнолетно лице — базовият случай. */
  private static BgSickLeaveDays split(List<BgSickLeaveEpisode> episodes) {
    return BgSickLeaveResolver.split(
        episodes, NO_HOLIDAYS, JULY_FROM, JULY_TO, 120, LocalDate.of(1985, 4, 12));
  }

  private static BgSickLeaveFacts facts(BigDecimal base, Integer norm, BigDecimal pct) {
    return new BgSickLeaveFacts(base, base, norm, pct, HEALTH_BASE_H1, HEALTH_EMPLOYER_RATE);
  }

  /** Фиктите на златния фиш: 1805,00 основна, 23 работни дни, 70 на сто. */
  private static BgSickLeaveFacts goldenFacts() {
    return facts(SALARY, JULY_WORKING_DAYS, PCT_70);
  }

  // ═══ 1 · ДНИТЕ ════════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ЗЛАТНИЯТ СПЛИТ · болен целия юли 2026 → 2 дни осигурител + 21 дни ДОО")
  void theWholeMonthSplitsIntoTwoAndTwentyOne() {
    BgSickLeaveDays days = split(Arrays.asList(sickEr("2026-07-01", "2026-07-31", false)));

    assertTrue(days.isResolved(), String.valueOf(days.problem));
    assertEquals(
        2,
        days.employerDays.intValue(),
        "първите ДВА работни дни на непрекъснатия период са за сметка на осигурителя — 1 и 2 юли,"
            + " сряда и четвъртък");
    assertEquals(
        21,
        days.dooDays.intValue(),
        "останалите работни дни на юли: 23 − 2. Точно те са базата на здравната вноска по ЗЗО чл. 40"
            + " ал. 1 т. 5, защото НЕ носят осигурителен доход");
    assertEquals(JULY_WORKING_DAYS, days.totalDays(), "двата броя трябва да изчерпват месеца");
  }

  @Test
  @DisplayName("A2 · период, който започва с ПРОДЪЛЖЕНИЕ (скъсана верига) → ОТКАЗ, не нови 2 дни")
  void aSpellStartingWithAContinuationIsRefused() {
    BgSickLeaveDays days =
        split(
            Arrays.asList(
                episode("2026-07-01", "2026-07-10", true, "sick_er", "employer", "70.00")));

    assertFalse(
        days.isResolved(),
        "⚠️ Продължението не открива нов период, иначе осигурителят би платил първите два дни ВТОРИ"
            + " път — при продължение от 1-во число първият лист е в предходния месец.");
    assertTrue(days.problem.contains("ПРОДЪЛЖЕНИЕ"), days.problem);
  }

  @Test
  @DisplayName("A2 · верига от предходния месец: началото е там, в юли осигурителят не дължи нищо")
  void aChainFromThePreviousMonthOwesNothingInJuly() {
    BgSickLeaveDays days =
        split(
            Arrays.asList(
                sickEr("2026-06-22", "2026-06-30", false),
                episode("2026-07-01", "2026-07-10", true, "sick_er", "employer", "70.00")));

    assertTrue(days.isResolved(), String.valueOf(days.problem));
    assertEquals(0, days.employerDays.intValue(), "първите два дни са 22–23 юни");
    assertEquals(8, days.dooDays.intValue(), "1–10 юли: 8 работни дни, всичките за ДОО");
  }

  @Test
  @DisplayName("⚠️ ПРОДЪЛЖЕНИЕТО НЕ РЕСТАРТИРА БРОЕНЕТО · два листа за един период → пак 2 дни")
  void aContinuationDoesNotRestartTheCount() {
    BgSickLeaveDays days =
        split(
            Arrays.asList(
                sickEr("2026-07-01", "2026-07-15", false),
                sickEr("2026-07-16", "2026-07-31", true)));

    assertTrue(days.isResolved(), String.valueOf(days.problem));
    assertEquals(
        2,
        days.employerDays.intValue(),
        "⚠️ Два листа с вдигнат флаг „продължение“ са ЕДИН непрекъснат период. Броени поотделно, те"
            + " дават 4 дни за сметка на работодателя — двойно на дължимото, и то с вид на пресметнато.");
    assertEquals(21, days.dooDays.intValue());
  }

  @Test
  @DisplayName("СЪСЕДНИТЕ ДАТИ слепват периода и БЕЗ вдигнат флаг — решение № 3")
  void adjacentDatesContinueTheSpellWithoutTheFlag() {
    BgSickLeaveDays days =
        split(
            Arrays.asList(
                sickEr("2026-07-01", "2026-07-15", false),
                sickEr("2026-07-16", "2026-07-31", false)));

    assertEquals(
        2,
        days.employerDays.intValue(),
        "вторият лист започва в ДЕНЯ СЛЕД края на първия — непрекъснатостта е факт, не отметка");
  }

  @Test
  @DisplayName("⚠️ ДВА НАИСТИНА ОТДЕЛНИ болничен → 2 + 2 дни, а не 2")
  void twoSeparateSpellsEachGetTwoDays() {
    BgSickLeaveDays days =
        split(
            Arrays.asList(
                sickEr("2026-07-01", "2026-07-03", false),
                sickEr("2026-07-20", "2026-07-24", false)));

    assertEquals(
        4,
        days.employerDays.intValue(),
        "два отделни периода неработоспособност дължат по два дни всеки — слепени, вторият би останал"
            + " неплатен от работодателя");
    assertEquals(3 + 5 - 4, days.dooDays.intValue(), "8 работни дни общо минус 4 за осигурителя");
  }

  @Test
  @DisplayName(
      "⚠️ УИКЕНД между двата листа НЕ ги слепва без флаг — обявената посока на решение № 3")
  void aWeekendGapDoesNotMergeWithoutTheFlag() {
    // 3 юли е петък, 6 юли е понеделник: между тях няма РАБОТЕН ден, но има календарни.
    BgSickLeaveDays days =
        split(
            Arrays.asList(
                sickEr("2026-07-01", "2026-07-03", false),
                sickEr("2026-07-06", "2026-07-10", false)));

    assertEquals(
        4,
        days.employerDays.intValue(),
        "⚠️ ПОСОКАТА Е ИЗБРАНА НАРОЧНО: слепването намалява дните за сметка на работодателя, тоест"
            + " греши в посока „по-малко пари за лицето“ и то без нищо на екрана. При наистина един"
            + " епизод листът носи флага „продължение“ — за това е.");
  }

  @Test
  @DisplayName("⚠️ ПОЛОВИН ЕПИЗОД · болничен от 30.06 → в юли пада само ЕДИН от двата дни")
  void anEpisodeStraddlingTheMonthBoundarySplitsTheOpeningDays() {
    // 30 юни е вторник: първите два работни дни на периода са 30.06 и 01.07.
    BgSickLeaveDays days = split(Arrays.asList(sickEr("2026-06-30", "2026-07-10", false)));

    assertTrue(days.isResolved(), String.valueOf(days.problem));
    assertEquals(
        1,
        days.employerDays.intValue(),
        "⚠️ Дните по чл. 40 ал. 5 се броят от НАЧАЛОТО НА ЕПИЗОДА, не от началото на месеца: 30.06 е"
            + " първият и се плаща в ЮНСКИЯ фиш, 01.07 е вторият и се плаща тук. Броени наново всеки"
            + " месец, лицето щеше да получи два дни в юни и още два в юли.");
    assertEquals(
        7,
        days.dooDays.intValue(),
        "работните дни 01–10 юли са осем (1,2,3,6,7,8,9,10), минус единия за сметка на осигурителя");
  }

  @Test
  @DisplayName("Епизод ИЗЦЯЛО в друг месец не дава нито ден в този период")
  void anEpisodeEntirelyOutsideThePeriodGivesNothing() {
    BgSickLeaveDays days = split(Arrays.asList(sickEr("2026-06-01", "2026-06-10", false)));

    assertTrue(days.isResolved(), String.valueOf(days.problem));
    assertEquals(0, days.employerDays.intValue());
    assertEquals(0, days.dooDays.intValue());
  }

  // ═══ 2 · ПРАГЪТ И ИЗКЛЮЧЕНИЯТА МУ ═════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ПОД ШЕСТ МЕСЕЦА СТАЖ · осигурителят не дължи първите дни — нула, но ОБЯСНЕНА")
  void underSixMonthsOfServiceTheEmployerOwesNothing() {
    BgSickLeaveDays days =
        BgSickLeaveResolver.split(
            Arrays.asList(sickEr("2026-07-01", "2026-07-31", false)),
            NO_HOLIDAYS,
            JULY_FROM,
            JULY_TO,
            3,
            LocalDate.of(1985, 4, 12));

    assertTrue(days.isResolved(), String.valueOf(days.problem));
    assertEquals(0, days.employerDays.intValue(), "прагът по чл. 40 ал. 1 КСО е шест месеца");
    assertEquals(
        23,
        days.dooDays.intValue(),
        "⚠️ И ЦЕЛИТЕ 23 ДНИ СА БЕЗ ОСИГУРИТЕЛЕН ДОХОД. Тоест здравната вноска по ЗЗО чл. 40 ал. 1"
            + " т. 5 се дължи за всичките, а не за 21 — прагът мени и нея, не само болничния.");
    assertTrue(
        days.witnessAsText().contains("под изискваните 6"),
        "нулата трябва да носи ПРИЧИНАТА си на фиша, инак е неразличима от „няма болничен“: "
            + days.witnessAsText());
  }

  @Test
  @DisplayName("⚠️ ТРУДОВА ЗЛОПОЛУКА · прагът НЕ важи — пълните два дни при три месеца стаж")
  void aWorkAccidentIsExemptFromTheServiceThreshold() {
    BgSickLeaveDays days =
        BgSickLeaveResolver.split(
            Arrays.asList(
                episode(
                    "2026-07-01", "2026-07-31", false, "work_accident_er", "employer", "70.00")),
            NO_HOLIDAYS,
            JULY_FROM,
            JULY_TO,
            3,
            LocalDate.of(1985, 4, 12));

    assertTrue(days.isResolved(), String.valueOf(days.problem));
    assertEquals(
        2,
        days.employerDays.intValue(),
        "⚠️ КОДЪТ, НЕ КАТЕГОРИЯТА, освобождава от праг: BgAbsenceType.category = 3 носи И БИТОВАТА"
            + " злополука (home_accident_er), а на нея прагът важи. Филтър по категория щеше да я"
            + " освободи също — по-щедро от закона, и то тихо.");
  }

  @Test
  @DisplayName("БИТОВАТА злополука НЕ е освободена от прага — същата категория, различен код")
  void aHomeAccidentIsNotExemptFromTheThreshold() {
    BgSickLeaveDays days =
        BgSickLeaveResolver.split(
            Arrays.asList(
                episode(
                    "2026-07-01", "2026-07-31", false, "home_accident_er", "employer", "70.00")),
            NO_HOLIDAYS,
            JULY_FROM,
            JULY_TO,
            3,
            LocalDate.of(1985, 4, 12));

    assertEquals(
        0, days.employerDays.intValue(), "битовата злополука минава през прага като болест");
  }

  @Test
  @DisplayName("⚠️ ПОД 18 ГОДИНИ · прагът НЕ важи — по-широкото четене, обявено като несверено")
  void aMinorIsExemptFromTheServiceThreshold() {
    BgSickLeaveDays days =
        BgSickLeaveResolver.split(
            Arrays.asList(sickEr("2026-07-01", "2026-07-31", false)),
            NO_HOLIDAYS,
            JULY_FROM,
            JULY_TO,
            3,
            LocalDate.of(2009, 5, 20));

    assertEquals(
        2,
        days.employerDays.intValue(),
        "⚠️ ПРАВИЛОТО Е НЕСВЕРЕНО и javadoc-ът на BgPayrollInput#birthDate го казва: източникът е"
            + " двусмислен дали изключението важи изобщо, или само при трудова злополука. Тук е"
            + " закодирано ПО-ШИРОКОТО четене — по-тясното отказва обезщетение на непълнолетно лице,"
            + " тоест грешката му се плаща от лицето. Обръща се с един ред:"
            + " AGE_WITHOUT_SERVICE_REQUIREMENT.");
  }

  @Test
  @DisplayName("Навършените 18 години връщат прага в сила")
  void anAdultIsSubjectToTheThreshold() {
    // Рожден ден 20.05.2008 → на 01.07.2026 лицето е на 18 години и месец.
    BgSickLeaveDays days =
        BgSickLeaveResolver.split(
            Arrays.asList(sickEr("2026-07-01", "2026-07-31", false)),
            NO_HOLIDAYS,
            JULY_FROM,
            JULY_TO,
            3,
            LocalDate.of(2008, 5, 20));

    assertEquals(0, days.employerDays.intValue(), "възрастта се мери към НАЧАЛОТО на епизода");
  }

  // ═══ 3 · ВИДОВЕТЕ, ПРИ КОИТО ОСИГУРИТЕЛЯТ НЕ ПЛАЩА ════════════════════════

  @Test
  @DisplayName("⚠️ МАЙЧИНСТВО · осигурителят плаща НУЛА, но дните ДОО пак се броят")
  void maternityLeaveGivesTheEmployerNoDays() {
    BgSickLeaveDays days =
        split(
            Arrays.asList(
                episode("2026-07-01", "2026-07-31", false, "maternity_birth", "doo", "90.00")));

    assertTrue(days.isResolved(), String.valueOf(days.problem));
    assertEquals(
        0,
        days.employerDays.intValue(),
        "⚠️ Бременност и раждане е ЕДНО ОТ ДВЕТЕ ИЗКЛЮЧЕНИЯ, заради които епизодът носи ВИД. Без вида"
            + " случаят е неразличим от общото заболяване и работодателят получава разход, който"
            + " законът не му възлага.");
    assertEquals(
        23,
        days.dooDays.intValue(),
        "здравната вноска по ЗЗО чл. 40 ал. 1 т. 5 обхваща изрично и бременността и раждането");
    assertTrue(
        days.witnessAsText().contains("платец"),
        "нулата носи причината си: " + days.witnessAsText());
  }

  @Test
  @DisplayName(
      "Вид „без право“ при стаж под 6 месеца — присъдата съвпада: т. 16.4, нула за осигурителя")
  void aSickNoteWithoutBenefitAgreesWithTheVerdict() {
    BgSickLeaveDays days =
        BgSickLeaveResolver.split(
            Arrays.asList(
                episode("2026-07-01", "2026-07-31", false, "sick_no_benefit_er", "none", "0")),
            NO_HOLIDAYS,
            JULY_FROM,
            JULY_TO,
            3,
            LocalDate.of(1985, 4, 12));

    assertTrue(days.isResolved(), String.valueOf(days.problem));
    assertEquals(0, days.employerDays.intValue());
    assertEquals(23, days.dooDays.intValue());
    assertEquals(23, days.withoutBenefitDays.intValue(), "всичките дни са без право — т. 16.4");
  }

  @Test
  @DisplayName("Вид „без право“ при стаж 10 години — ОТКАЗ: видът противоречи на присъдата")
  void aSickNoteWithoutBenefitContradictingTheVerdictIsRefused() {
    BgSickLeaveDays days =
        split(
            Arrays.asList(
                episode("2026-07-01", "2026-07-31", false, "sick_no_benefit_er", "none", "0")));

    assertFalse(days.isResolved(), "тихо предпочитане на вида отнемаше 70-те процента на лицето");
    assertTrue(days.problem.contains("sick_no_benefit_er"), days.problem);
    assertTrue(days.problem.contains("С ПРАВО"), days.problem);
  }

  @Test
  @DisplayName("Продължение „без право“ след лист с право — пак отказ: всеки лист се сверява")
  void aContinuationClaimingNoBenefitIsRefusedToo() {
    BgSickLeaveDays days =
        split(
            Arrays.asList(
                sickEr("2026-07-01", "2026-07-10", false),
                episode("2026-07-11", "2026-07-31", true, "sick_no_benefit_doo", "none", "0")));

    assertFalse(days.isResolved());
    assertTrue(days.problem.contains("sick_no_benefit_doo"), days.problem);
  }

  @Test
  @DisplayName("Самостоятелен лист ДОО под 6 месеца — БЕЗ право, т. 16.4, а не т. 16.2")
  void aStandaloneDooSheetUnderTheThresholdHasNoRight() {
    BgSickLeaveDays days =
        BgSickLeaveResolver.split(
            Arrays.asList(episode("2026-07-01", "2026-07-31", false, "sick_doo", "doo", "80.00")),
            NO_HOLIDAYS,
            JULY_FROM,
            JULY_TO,
            3,
            LocalDate.of(1985, 4, 12));

    assertTrue(days.isResolved(), String.valueOf(days.problem));
    assertEquals(0, days.employerDays.intValue());
    assertEquals(
        23,
        days.withoutBenefitDays.intValue(),
        "⚠️ ПРАВОТО Е ПО РИСКА, НЕ ПО ПЛАТЕЦА. Платецът „doo“ не връща „не“ без праг, иначе"
            + " дните биха отишли в т. 16.2 като дни С право на обезщетение");
  }

  @Test
  @DisplayName("Гледане на болно дете под 6 месеца — БЕЗ право (същият риск, същият праг)")
  void caringForASickChildUnderTheThresholdHasNoRight() {
    BgSickLeaveDays days =
        BgSickLeaveResolver.split(
            Arrays.asList(
                episode("2026-07-01", "2026-07-31", false, "care_sick_child_u18", "doo", "80.00")),
            NO_HOLIDAYS,
            JULY_FROM,
            JULY_TO,
            3,
            LocalDate.of(1985, 4, 12));

    assertTrue(days.isResolved(), String.valueOf(days.problem));
    assertEquals(23, days.withoutBenefitDays.intValue());
  }

  @Test
  @DisplayName("Лист ДОО без деклариран стаж — ОТКАЗ, не тихо „с право“")
  void aDooSheetWithoutDeclaredServiceIsRefused() {
    BgSickLeaveDays days =
        BgSickLeaveResolver.split(
            Arrays.asList(episode("2026-07-01", "2026-07-31", false, "sick_doo", "doo", "80.00")),
            NO_HOLIDAYS,
            JULY_FROM,
            JULY_TO,
            null,
            LocalDate.of(1985, 4, 12));

    assertFalse(days.isResolved(), "листът ДОО не минава без стаж — като дни С право");
    assertTrue(days.problem.contains("не е деклариран"), days.problem);
  }

  @Test
  @DisplayName("Лист ДОО с право — осигурителят пак не плаща първите дни, т. 16.2")
  void aDooSheetWithTheRightStillPaysTheEmployerNothing() {
    BgSickLeaveDays days =
        split(
            Arrays.asList(episode("2026-07-01", "2026-07-31", false, "sick_doo", "doo", "80.00")));

    assertTrue(days.isResolved(), String.valueOf(days.problem));
    assertEquals(0, days.employerDays.intValue(), "платецът решава кой плаща първите дни");
    assertEquals(0, days.withoutBenefitDays.intValue(), "правото е налице — т. 16.2");
    assertEquals(23, days.dooDays.intValue());
  }

  @Test
  @DisplayName("Майчинството НЕ минава през шестмесечния праг — без стаж пак се смята")
  void maternityIsOutsideTheSixMonthVerdict() {
    BgSickLeaveDays days =
        BgSickLeaveResolver.split(
            Arrays.asList(
                new BgSickLeaveEpisode(
                    LocalDate.parse("2026-07-01"),
                    LocalDate.parse("2026-07-31"),
                    false,
                    "maternity_birth",
                    "doo",
                    new BigDecimal("90.00"),
                    null,
                    null,
                    BgSickLeaveResolver.RISK_MATERNITY_CATEGORY)),
            NO_HOLIDAYS,
            JULY_FROM,
            JULY_TO,
            null,
            LocalDate.of(1985, 4, 12));

    assertTrue(
        days.isResolved(),
        "майчинството има свой праг (чл. 48а) — шестте месеца не са негов въпрос: " + days.problem);
    assertEquals(0, days.employerDays.intValue());
    assertEquals(0, days.withoutBenefitDays.intValue());
  }

  @Test
  @DisplayName("⚠️ ВИДЪТ НА ПЪРВИЯ лист решава — продължението не го преобръща")
  void theFirstEpisodeOfTheSpellDecidesTheRight() {
    BgSickLeaveDays days =
        split(
            Arrays.asList(
                episode("2026-07-01", "2026-07-15", false, "maternity_birth", "doo", "90.00"),
                sickEr("2026-07-16", "2026-07-31", true)));

    assertEquals(
        0,
        days.employerDays.intValue(),
        "първите дни са дни на ПЕРИОДА, а той започва с майчинство. Прочетен видът на продължението,"
            + " работодателят щеше да плати два дни в средата на майчинството.");
  }

  // ═══ 4 · ОТКАЗИТЕ · „0 НЕ Е РЕШЕНИЕ“ ═════════════════════════════════════

  @Test
  @DisplayName("⚠️ ПРАЗНИ ЕПИЗОДИ = НОРМАЛНО, а обяснението сочи СТАТУСА на листа")
  void noEpisodesIsALawfulZeroNotARefusal() {
    BgSickLeaveDays days = split(Collections.<BgSickLeaveEpisode>emptyList());

    assertTrue(days.isResolved(), "празен списък НЕ е отказ — този месец лицето не е боледувало");
    assertEquals(0, days.employerDays.intValue());
    assertEquals(0, days.dooDays.intValue());
    assertTrue(
        days.witnessAsText().contains("Валидиран"),
        "⚠️ Обяснението не казва, че списъкът е „ВИНАГИ празен, защото болничният лист няма"
            + " запис в модела“, и не сочи BgPayrollInputBuilder.DECLARED_ABSENT: BgSickLeave има"
            + " entity и екран, епизодите се четат от ВАЛИДИРАНИТЕ листове"
            + " (BgPayrollInputBuilder#sickLeaveEpisodesOf), а DECLARED_ABSENT съдържа само"
            + " manualLines и двете полета на ръчното надписване на осигурителния доход."
            + " Обяснението трябва да сочи това, което операторът МОЖЕ да провери — статуса на"
            + " листа, — а не липса, която не съществува: "
            + days.witnessAsText());
  }

  @Test
  @DisplayName("⚠️ ЛИПСВАЩ ОСИГУРИТЕЛЕН СТАЖ · ОТКАЗ с причина, НЕ нула дни")
  void anUnknownInsuranceServiceRefuses() {
    BgSickLeaveDays days =
        BgSickLeaveResolver.split(
            Arrays.asList(sickEr("2026-07-01", "2026-07-31", false)),
            NO_HOLIDAYS,
            JULY_FROM,
            JULY_TO,
            null,
            LocalDate.of(1985, 4, 12));

    assertNull(days.employerDays, "при отказ броят е НЕИЗВЕСТЕН, а не нула");
    assertNotNull(days.problem);
    assertTrue(
        days.problem.contains("bgInsuranceServiceMonths"),
        "отказът трябва да назове полето, което липсва: " + days.problem);
    assertTrue(
        days.problem.contains("Осигурителният стаж е деклариран"),
        "⚠️ Полето има източник, затова насоката е с думите от формата на служителя: "
            + days.problem);
  }

  @Test
  @DisplayName("Стаж под прага + липсваща дата на раждане → ОТКАЗ, не нула")
  void aMissingBirthDateUnderTheThresholdRefuses() {
    BgSickLeaveDays days =
        BgSickLeaveResolver.split(
            Arrays.asList(sickEr("2026-07-01", "2026-07-31", false)),
            NO_HOLIDAYS,
            JULY_FROM,
            JULY_TO,
            3,
            null);

    assertNull(days.employerDays);
    assertTrue(
        days.problem.contains("18"),
        "не се знае дали важи изключението за ненавършили 18 години: " + days.problem);
  }

  @Test
  @DisplayName(
      "⚠️ Достатъчният стаж НЕ пита за дата на раждане — вход, от който отговорът не зависи")
  void enoughServiceDoesNotNeedTheBirthDate() {
    BgSickLeaveDays days =
        BgSickLeaveResolver.split(
            Arrays.asList(sickEr("2026-07-01", "2026-07-31", false)),
            NO_HOLIDAYS,
            JULY_FROM,
            JULY_TO,
            120,
            null);

    assertTrue(days.isResolved(), String.valueOf(days.problem));
    assertEquals(2, days.employerDays.intValue());
  }

  @Test
  @DisplayName("⚠️ НЕПОЗНАТ ВИД ОТСЪСТВИЕ · ОТКАЗ — липсващ ред в речника не е „платец няма“")
  void anUnknownAbsenceTypeRefuses() {
    BgSickLeaveDays days =
        split(Arrays.asList(episode("2026-07-01", "2026-07-31", false, "sick_er", null, null)));

    assertNull(days.employerDays);
    assertTrue(
        days.problem.contains("bg_absence_type.csv"),
        "отказът трябва да прати читателя на seed-а: " + days.problem);
  }

  @Test
  @DisplayName("Епизод без дати и епизод с край преди началото → ОТКАЗ")
  void malformedEpisodesRefuse() {
    BgSickLeaveDays noDates =
        split(
            Arrays.asList(
                new BgSickLeaveEpisode(null, JULY_TO, false, "sick_er", "employer", PCT_70, null)));
    assertNull(noDates.employerDays);
    assertTrue(noDates.problem.contains("дата"), noDates.problem);

    BgSickLeaveDays reversed = split(Arrays.asList(sickEr("2026-07-20", "2026-07-10", false)));
    assertNull(reversed.employerDays);
    assertTrue(reversed.problem.contains("край преди началото"), reversed.problem);
  }

  @Test
  @DisplayName("Липсващ период на фиша → ОТКАЗ, не „целият болничен е тук“")
  void aMissingPeriodRefuses() {
    BgSickLeaveDays days =
        BgSickLeaveResolver.split(
            Arrays.asList(sickEr("2026-07-01", "2026-07-31", false)),
            NO_HOLIDAYS,
            null,
            JULY_TO,
            120,
            LocalDate.of(1985, 4, 12));

    assertNull(days.employerDays);
    assertTrue(days.problem.contains("периодът"), days.problem);
  }

  // ═══ 5 · СУМАТА НА БОЛНИЧНИЯ ═════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ЗЛАТНАТА СУМА · 1805,00 / 23 × 70% × 2 = 109,87")
  void theGoldenAmountIsOneHundredAndNineEightySeven() {
    BgSickLeaveDays days = split(Arrays.asList(sickEr("2026-07-01", "2026-07-31", false)));
    BgSickLeaveOutcome out = BgSickLeaveResolver.sickPayLine(goldenFacts(), days);

    assertEquals(BgSickLeaveOutcome.Status.COMPUTED, out.status, String.valueOf(out.detail));
    assertEquals(
        0,
        new BigDecimal("109.87").compareTo(out.amount),
        "сверено срещу реален фиш на Микроинвест за 07.2026 г. Намерено: " + out.amount);
    assertEquals(
        0,
        new BigDecimal("2").compareTo(out.quantity),
        "количеството документира сумата: работните дни за сметка на осигурителя");
    assertEquals(
        0,
        new BigDecimal("78.4783").compareTo(out.rate),
        "ставката документира сумата: среднодневната база 1805,00 / 23. Намерено: " + out.rate);
  }

  @Test
  @DisplayName("⚠️ ЕДНО ЗАКРЪГЛЯНЕ НАКРАЯ (§ 5.3) — верига от закръглени междинни дава друго число")
  void theRoundingHappensOnceAtTheEnd() {
    BigDecimal once =
        BgSickLeaveResolver.sickPayAmount(SALARY, SALARY, PCT_70, 2, JULY_WORKING_DAYS);
    assertEquals(0, new BigDecimal("109.87").compareTo(once), String.valueOf(once));

    // Същата сметка със закръглена МЕЖДИННА среднодневна база.
    BigDecimal daily = SALARY.divide(new BigDecimal("23"), 2, RoundingMode.HALF_UP); // 78,48
    BigDecimal chained =
        daily
            .multiply(PCT_70)
            .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("2"))
            .setScale(2, RoundingMode.HALF_UP);
    assertEquals(
        0,
        new BigDecimal("109.88").compareTo(chained),
        "котвата на свидетеля: закръглената междинна база дава 109,88, тоест ЕДНА СТОТИНКА разлика."
            + " Намерено: "
            + chained);
    assertTrue(
        once.compareTo(chained) != 0,
        "⚠️ Ако двете съвпаднат, този свидетел спира да пази каквото и да е и трябва да се избере"
            + " друг месец: числата тук са избрани точно защото се разминават.");
  }

  @Test
  @DisplayName("⚠️ ДОЛНИЯТ ПРАГ ПО АЛ. 5 · 70% от среднодневното УГОВОРЕНО е под, не украса")
  void theStatutoryFloorOfParagraphFiveApplies() {
    // База по-НИСКА от уговореното: подът трябва да надделее.
    BigDecimal amount =
        BgSickLeaveResolver.sickPayAmount(
            new BigDecimal("900.00"), SALARY, PCT_70, 2, JULY_WORKING_DAYS);

    assertEquals(
        0,
        new BigDecimal("109.87").compareTo(amount),
        "⚠️ Чл. 40 ал. 5 дължи „не по-малко от 70 на сто от среднодневното УГОВОРЕНО“. При база 900,00"
            + " формулата дава 54,78; подът от уговореното 1805,00 дава 109,87 и печели. Днес двете"
            + " съвпадат по решение № 1 — този свидетел е причината подът да НЕ се изтрие като"
            + " излишен. Намерено: "
            + amount);

    // И обратното: база НАД уговореното печели над пода.
    BigDecimal higher =
        BgSickLeaveResolver.sickPayAmount(
            new BigDecimal("2000.00"), SALARY, PCT_70, 2, JULY_WORKING_DAYS);
    assertEquals(
        0,
        new BigDecimal("121.74").compareTo(higher),
        "подът е ПОД, не таван: 2000,00 × 70% × 2 / 23 = 121,74. Намерено: " + higher);
  }

  @Test
  @DisplayName("Пълен месец дни (норма = дни) дава ТОЧНО процента от заплатата")
  void aFullMonthOfSickDaysGivesExactlyThePercentage() {
    BigDecimal amount =
        BgSickLeaveResolver.sickPayAmount(
            new BigDecimal("1000.00"), null, PCT_70, 23, JULY_WORKING_DAYS);
    assertEquals(
        0,
        new BigDecimal("700.00").compareTo(amount),
        "единственото деление е последно, тоест 1000 × 70 × 23 / (100 × 23) е точно 700,00, не 699,99");
  }

  @Test
  @DisplayName("⚠️ НУЛА ДНИ за осигурителя → ПЕРО НЕ СЕ СЪЗДАВА, и това не е отказ")
  void zeroEmployerDaysProducesNoLine() {
    BgSickLeaveDays days =
        split(
            Arrays.asList(
                episode("2026-07-01", "2026-07-31", false, "maternity_birth", "doo", "90.00")));
    BgSickLeaveOutcome out = BgSickLeaveResolver.sickPayLine(goldenFacts(), days);

    assertEquals(BgSickLeaveOutcome.Status.NO_EMPLOYER_DAYS, out.status);
    assertNull(out.amount, "перо със сума 0,00 щеше да твърди, че болничен ИМА и е нулев");
    assertTrue(out.detail.contains("платец"), "причината пътува с изхода: " + out.detail);
  }

  @Test
  @DisplayName("⚠️ ЛИПСВАЩА ЗАПЛАТА, НОРМА или ПРОЦЕНТ · ОТКАЗ, не сума 0,00")
  void missingMoneyInputsRefuseInsteadOfComputingZero() {
    BgSickLeaveDays days = split(Arrays.asList(sickEr("2026-07-01", "2026-07-31", false)));

    BgSickLeaveOutcome noSalary =
        BgSickLeaveResolver.sickPayLine(facts(BigDecimal.ZERO, JULY_WORKING_DAYS, PCT_70), days);
    assertEquals(BgSickLeaveOutcome.Status.REFUSED, noSalary.status);
    assertTrue(noSalary.detail.contains("основната заплата"), noSalary.detail);

    BgSickLeaveOutcome noNorm = BgSickLeaveResolver.sickPayLine(facts(SALARY, 0, PCT_70), days);
    assertEquals(BgSickLeaveOutcome.Status.REFUSED, noNorm.status);
    assertTrue(noNorm.detail.contains("работните дни в периода"), noNorm.detail);

    BgSickLeaveOutcome noPct =
        BgSickLeaveResolver.sickPayLine(facts(SALARY, JULY_WORKING_DAYS, null), days);
    assertEquals(BgSickLeaveOutcome.Status.REFUSED, noPct.status);
    assertTrue(noPct.detail.contains("compensationPct"), noPct.detail);
  }

  @Test
  @DisplayName("Процент НУЛА в речника е ЗАКОНЕН нула — вид без обезщетение, не отказ")
  void aZeroPercentageIsALawfulZero() {
    BgSickLeaveDays days = split(Arrays.asList(sickEr("2026-07-01", "2026-07-31", false)));
    BgSickLeaveOutcome out =
        BgSickLeaveResolver.sickPayLine(facts(SALARY, JULY_WORKING_DAYS, BigDecimal.ZERO), days);

    assertEquals(
        BgSickLeaveOutcome.Status.NO_EMPLOYER_DAYS,
        out.status,
        "нула на сто е УСТАНОВЕН факт за вида, а празна клетка е липсващ ред — двете не се сливат");
  }

  @Test
  @DisplayName("Отказаното разделяне се ПРЕНАСЯ до перото, а не се превръща в нула")
  void aRefusedSplitPropagatesToTheLine() {
    BgSickLeaveDays blocked =
        BgSickLeaveResolver.split(
            Arrays.asList(sickEr("2026-07-01", "2026-07-31", false)),
            NO_HOLIDAYS,
            JULY_FROM,
            JULY_TO,
            null,
            null);
    BgSickLeaveOutcome out = BgSickLeaveResolver.sickPayLine(goldenFacts(), blocked);

    assertEquals(BgSickLeaveOutcome.Status.REFUSED, out.status);
    assertTrue(out.isRefusal(), "отказът иска някой да поправи нещо СЕГА");
    assertEquals(blocked.problem, out.detail, "причината не се преразказва по пътя");
  }

  // ═══ 6 · ЗДРАВНАТА ВНОСКА ЗА ДНИТЕ ВНР (ЗЗО чл. 40 ал. 1 т. 5) ════════════

  @Test
  @DisplayName("⚠️ ЗЛАТНАТА ЗДРАВНА ВНОСКА · 550,66 × 21/23 × 4,8% = 24,13")
  void theGoldenHealthContributionIsTwentyFourThirteen() {
    BgSickLeaveDays days = split(Arrays.asList(sickEr("2026-07-01", "2026-07-31", false)));
    BgSickLeaveOutcome out = BgSickLeaveResolver.healthAbsenceLine(goldenFacts(), days);

    assertEquals(BgSickLeaveOutcome.Status.COMPUTED, out.status, String.valueOf(out.detail));
    assertEquals(
        0,
        new BigDecimal("24.13").compareTo(out.amount),
        "сверено срещу реален фиш на Микроинвест за 07.2026 г. Намерено: " + out.amount);
    assertEquals(
        0,
        new BigDecimal("21").compareTo(out.quantity),
        "количеството са дните БЕЗ осигурителен доход");
    assertEquals(
        0,
        HEALTH_EMPLOYER_RATE.compareTo(out.rate),
        "ставката документира сумата и идва от таблицата, не от кода");
  }

  @Test
  @DisplayName("⚠️ РЕШЕНИЕ № 2 · ставката 4,8% възпроизвежда фиша; целите 8% дават 40,22 и НЕ го")
  void theEmployerShareIsWhatReproducesTheRealPaySlip() {
    BigDecimal atEmployerShare =
        BgSickLeaveResolver.healthAbsenceAmount(
            HEALTH_BASE_H1, 21, JULY_WORKING_DAYS, HEALTH_EMPLOYER_RATE);
    BigDecimal atFullRate =
        BgSickLeaveResolver.healthAbsenceAmount(
            HEALTH_BASE_H1, 21, JULY_WORKING_DAYS, new BigDecimal("8.0000"));

    assertEquals(
        0, new BigDecimal("24.13").compareTo(atEmployerShare), String.valueOf(atEmployerShare));
    assertEquals(
        0,
        new BigDecimal("40.22").compareTo(atFullRate),
        "⚠️ „За сметка на работодателя“ може да се прочете и като „целите 8 на сто, платени от него“."
            + " Числото на фиша изключва това четене: 40,22 срещу 24,13. Затова ставката е ЧАСТТА НА"
            + " РАБОТОДАТЕЛЯ. Намерено: "
            + atFullRate);
  }

  @Test
  @DisplayName("Втората осигурителна половина на 2026 г. сменя БАЗАТА, не ставката")
  void theSecondHalfOfTwentyTwentySixChangesTheBase() {
    BigDecimal august =
        BgSickLeaveResolver.healthAbsenceAmount(
            HEALTH_BASE_H2, 21, JULY_WORKING_DAYS, HEALTH_EMPLOYER_RATE);
    assertEquals(
        0,
        new BigDecimal("27.18").compareTo(august),
        "620,20 × 21 / 23 × 4,8% = 27,18. Параметърът е темпорален точно за да не се пише в кода."
            + " Намерено: "
            + august);
  }

  @Test
  @DisplayName("⚠️ ЦЯЛ МЕСЕЦ ДНИ ДОО · 4,8% × 550,66 = 26,43 (H1) и × 620,20 = 29,77 (H2)")
  void aWholeMonthOfDooDaysGivesTheUnproratedContribution() {
    // Дни = норма, тоест пропорцията отпада и остава чистото „ставка върху базата“. Точно това
    // число се сверява срещу таблиците на НАП и точно то се губи, когато свидетелят е само
    // пропорционален: 24,13 (21/23) съвпада и при леко сгрешена база.
    BigDecimal h1 =
        BgSickLeaveResolver.healthAbsenceAmount(
            HEALTH_BASE_H1, JULY_WORKING_DAYS, JULY_WORKING_DAYS, HEALTH_EMPLOYER_RATE);
    BigDecimal h2 =
        BgSickLeaveResolver.healthAbsenceAmount(
            HEALTH_BASE_H2, JULY_WORKING_DAYS, JULY_WORKING_DAYS, HEALTH_EMPLOYER_RATE);

    assertEquals(
        0,
        new BigDecimal("26.43").compareTo(h1),
        "4,8% × 550,66 = 26,4317 → 26,43. Намерено: " + h1);
    assertEquals(
        0,
        new BigDecimal("29.77").compareTo(h2),
        "4,8% × 620,20 = 29,7696 → 29,77. Намерено: " + h2);
  }

  @Test
  @DisplayName("⚠️ ВИДОВЕ 05 и 24 · 80/20 от 01.08.2026 → 6,4% × 620,20 = 39,69, а не 29,77")
  void thePublicServantSplitRaisesTheEmployerShare() {
    // Ставката идва от bg_insurance_contribution (IC-029 и IC-061), не от кода — това е целият
    // довод на решение № 2. Свидетелят стои тук, защото число в кода би сметнало 4,8 и за тях.
    BigDecimal atEightyTwenty =
        BgSickLeaveResolver.healthAbsenceAmount(
            HEALTH_BASE_H2, JULY_WORKING_DAYS, JULY_WORKING_DAYS, new BigDecimal("6.4000"));

    assertEquals(
        0,
        new BigDecimal("39.69").compareTo(atEightyTwenty),
        "6,4% × 620,20 = 39,6928 → 39,69, срещу 29,77 при 4,8%. Разликата е 9,92 на месец на лице."
            + " Намерено: "
            + atEightyTwenty);
  }

  @Test
  @DisplayName("⚠️ ЛИПСВАЩ HEALTH_ABSENCE_BASE или ставка · ОТКАЗ — недекларирана вноска, не нула")
  void aMissingHealthBaseOrRateRefuses() {
    BgSickLeaveDays days = split(Arrays.asList(sickEr("2026-07-01", "2026-07-31", false)));

    BgSickLeaveOutcome noBase =
        BgSickLeaveResolver.healthAbsenceLine(
            new BgSickLeaveFacts(
                SALARY, SALARY, JULY_WORKING_DAYS, PCT_70, null, HEALTH_EMPLOYER_RATE),
            days);
    assertEquals(BgSickLeaveOutcome.Status.REFUSED, noBase.status);
    assertTrue(noBase.detail.contains("HEALTH_ABSENCE_BASE"), noBase.detail);

    BgSickLeaveOutcome noRate =
        BgSickLeaveResolver.healthAbsenceLine(
            new BgSickLeaveFacts(SALARY, SALARY, JULY_WORKING_DAYS, PCT_70, HEALTH_BASE_H1, null),
            days);
    assertEquals(BgSickLeaveOutcome.Status.REFUSED, noRate.status);
    assertTrue(noRate.detail.contains("bg_insurance_contribution"), noRate.detail);
  }

  @Test
  @DisplayName("Нула дни без осигурителен доход → здравна вноска НЕ се дължи")
  void zeroDaysWithoutInsurableIncomeOwesNoHealthContribution() {
    // Болничен САМО за двата дни на осигурителя: те носят осигурителен доход.
    BgSickLeaveDays days = split(Arrays.asList(sickEr("2026-07-01", "2026-07-02", false)));
    assertEquals(2, days.employerDays.intValue());
    assertEquals(0, days.dooDays.intValue());

    BgSickLeaveOutcome out = BgSickLeaveResolver.healthAbsenceLine(goldenFacts(), days);
    assertEquals(BgSickLeaveOutcome.Status.NO_EMPLOYER_DAYS, out.status);
    assertNull(out.amount);
  }

  // ═══ 7 · ОФИЦИАЛНИТЕ ПРАЗНИЦИ ════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Официалният празник НЕ е работен ден — първите два дни го прескачат")
  void aPublicHolidayIsNotAWorkingDay() {
    // Хипотетичен празник на 1 юли: първите два работни дни стават 2 и 3 юли.
    Set<LocalDate> holidays = new HashSet<>(Arrays.asList(LocalDate.of(2026, 7, 1)));
    BgSickLeaveDays days =
        BgSickLeaveResolver.split(
            Arrays.asList(sickEr("2026-06-30", "2026-07-10", false)),
            holidays,
            JULY_FROM,
            JULY_TO,
            120,
            LocalDate.of(1985, 4, 12));

    assertEquals(
        1,
        days.employerDays.intValue(),
        "първите работни дни на епизода са 30.06 и 02.07, защото 01.07 е обявен за празник; в юли пада"
            + " само вторият");
    assertEquals(
        6,
        days.dooDays.intValue(),
        "работните дни 02–10 юли са седем (2,3,6,7,8,9,10), минус единия за сметка на осигурителя");
  }

  // ═══ A5 · МЕСЕЦЪТ НА НАСТЪПВАНЕ ═══════════════════════════════════════════

  private static final LocalDate OCT_FROM = LocalDate.of(2026, 10, 1);
  private static final LocalDate OCT_TO = LocalDate.of(2026, 10, 31);
  private static final YearMonth SEP = YearMonth.of(2026, 9);
  private static final YearMonth OCT = YearMonth.of(2026, 10);

  private static BgSickLeaveDays splitOctober(List<BgSickLeaveEpisode> episodes) {
    return BgSickLeaveResolver.split(
        episodes, NO_HOLIDAYS, OCT_FROM, OCT_TO, 120, LocalDate.of(1985, 4, 12));
  }

  @Test
  @DisplayName("A5 · лист от сряда 30.09 — денят 01.10 на октомври е с месец на настъпване 09.2026")
  void theOctoberDayCarriesTheSeptemberOnset() {
    BgSickLeaveDays days = splitOctober(Arrays.asList(sickEr("2026-09-30", "2026-10-02", false)));

    assertEquals(1, days.employerDays.intValue(), "01.10 — вторият от двата дни");
    assertEquals(
        Collections.singletonMap(SEP, 1),
        days.employerDaysByOnset,
        "базата е на септември, не на октомври");
    assertTrue(days.witnessAsText().contains("по базата на 09.2026"), days.witnessAsText());
  }

  @Test
  @DisplayName("A5 · два месеца на настъпване на един фиш — всеки по своята база, едно закръгляне")
  void twoOnsetMonthsEachOnItsOwnBase() {
    BgSickLeaveDays days =
        splitOctober(
            Arrays.asList(
                sickEr("2026-09-30", "2026-10-02", false),
                sickEr("2026-10-12", "2026-10-16", false)));
    Map<YearMonth, BgSickLeaveOnsetBase> bases = new HashMap<>();
    bases.put(SEP, BgSickLeaveOnsetBase.of(SEP, SALARY, SALARY, 20));
    bases.put(OCT, BgSickLeaveOnsetBase.of(OCT, SALARY, SALARY, 22));

    BgSickLeaveOutcome line = BgSickLeaveResolver.sickPayLine(goldenFacts(), days, bases);

    assertTrue(line.isComputed(), line.detail);
    assertEquals(3, line.quantity.intValue(), "01.10 + 12–13.10");
    // 1805 × 70 % × 1 / 20 = 63,175 и 1805 × 70 % × 2 / 22 = 114,8636… → 178,0386… → 178,04.
    assertEquals(new BigDecimal("178.04"), line.amount);
    assertNull(line.rate, "при два месеца едно среднодневно не обяснява сумата");
  }

  @Test
  @DisplayName("A5 · месец на настъпване без база — отказ с причината му, не базата на фиша")
  void anOnsetMonthWithoutABaseRefusesTheLine() {
    BgSickLeaveDays days = splitOctober(Arrays.asList(sickEr("2026-09-30", "2026-10-02", false)));

    BgSickLeaveOutcome line =
        BgSickLeaveResolver.sickPayLine(
            goldenFacts(),
            days,
            Collections.singletonMap(SEP, BgSickLeaveOnsetBase.refused(SEP, "няма фиш за 09")));

    assertEquals(BgSickLeaveOutcome.Status.REFUSED, line.status);
    assertEquals("няма фиш за 09", line.detail);
  }
}

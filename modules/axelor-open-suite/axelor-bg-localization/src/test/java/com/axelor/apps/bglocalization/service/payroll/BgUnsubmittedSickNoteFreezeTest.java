package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.service.d1.BgD1DayBuckets;
import com.axelor.apps.hr.db.Employee;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * НЕПРЕДСТАВЕНИЯТ БОЛНИЧЕН МИНАВА ПРЕЗ ЗАМРАЗЯВАНЕТО — и пристигналият лист не се сблъсква с него
 * мълчаливо.
 *
 * <p><b>КАКВО ЗАКОВАВА ТОЗИ ФАЙЛ.</b> {@code BgPaySlipService#freezeLeaveDays} сверява „Σкофи на т.
 * 16 + дни извън осигуряването НЕ надхвърля преброените дни отсъствие“ и ХВЪРЛЯ при надвишаване.
 * Петте вида в {@code BgD1DayBuckets.UNBUCKETED} тежат от ЛЯВАТА страна; четири от тях тежат и от
 * дясната, защото имат кофа по категория — неплатеният над 30 дни е категория 7 („неплатен“),
 * самоотлъчката и неприсъствието са категория 5 („други“). Непредставеният болничен е категория 2 и
 * няма кофа по категория. Останал САМО отляво, три дни биха дали 3 срещу 0 и {@code prepare()} би
 * паднал с {@code IllegalStateException} на ВСЕКИ фиш с такава заявка — тоест сценарият, заради
 * който видът изобщо съществува („болничният още не е дошъл“), би бил непостижим по нормалния път.
 *
 * <p><b>⚠️ РЕШЕНИЕТО Е В БРОЕНЕТО, НЕ В ПАЗАЧА.</b> Проверката стои непокътната, дума по дума:
 * разхлабена вместо това, тя щеше да замълчи и за истинските разминавания, заради които съществува.
 * Решава се ЛЕВИЯТ вход — денят извън осигуряването без своя кофа се повишава в „други“, тоест
 * инвариантът „всеки ден от outside е и в някоя кофа“ важи ПО ПОСТРОЕНИЕ.
 *
 * <p><b>⚠️ И ВТОРАТА ПОЛОВИНА, БЕЗ КОЯТО ПЪРВАТА Е ОПАСНА.</b> Повишаването прави достижимо
 * състояние, което иначе би паднало преди да навреди: заявка „Непредставен болничен“, ОСТАНАЛА на
 * място, след като болничният лист е пристигнал. Тогава дните се свалят ДВА пъти — веднъж от {@code
 * BgSickLeaveService#applyDays} (свива {@code bgWorkedDays}) и веднъж от карва — и основната
 * заплата излиза 872,73 вместо 1036,36 с вид на пресметната. Затова съжителството е ОТКАЗ, който
 * назовава и двата записа.
 */
class BgUnsubmittedSickNoteFreezeTest {

  private static final LocalDate DEC_FROM = LocalDate.of(2026, 12, 1);
  private static final LocalDate DEC_TO = LocalDate.of(2026, 12, 31);

  /** Декември 2026: 23 делнични дни минус 24 и 25.12 (официални празници) = 21 работни. */
  private static final Set<LocalDate> HOLIDAYS =
      new HashSet<>(Arrays.asList(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 25)));

  /** Числата на заданието: основна 1200,00 при норма 22 работни дни. */
  private static final BigDecimal SALARY = new BigDecimal("1200.00");

  private static final int NORM = 22;

  // ═══ ПЕТТЕ БЕЗКОФЕНИ ВИДА, ПООТДЕЛНО, ПРЕЗ ЗАМРАЗЯВАНЕТО ═════════════════

  /**
   * ⚠️ РЕДОВЕТЕ ИДВАТ ОТ SEED-А, А НЕ СЕ ПИШАТ НА РЪКА, И ТОВА Е САМИЯТ СМИСЪЛ НА ТОЗИ СВИДЕТЕЛ.
   *
   * <p>Пет @Test метода с ръчно преписани категории и признаци биха дали ТРИ различни фикстури:
   * {@code unpaid_prod_gt30} и {@code unpaid_own_gt30} са БАЙТ ПО БАЙТ един и същ ред (и двата са
   * категория 7), същото и {@code non_attendance} с {@code awol} (категория 5). Такъв тест би
   * твърдял покритие на пет вида, а би мерил три структури, и нямаше как да падне, ако някой смени
   * категорията на един ред в речника.
   *
   * <p>Затова входът е {@link BgD1DayBuckets#UNBUCKETED} × реалните колони от {@code
   * bg_absence_type.csv}. Сменена категория, махнат признак или попълнена кофа на който и да е от
   * петте вали ТОЧНО неговия случай, с неговото име.
   */
  @ParameterizedTest(name = "⚠️ {0} минава през freezeLeaveDays и се брои в кофа")
  @MethodSource("unbucketedSeedRows")
  void everyUnbucketedSeedTypeFreezes(String code, int category, boolean paid) {
    BgPayrollInputBuilder.LeaveDays days =
        leaveDaysOf(row("2026-12-07", "2026-12-09", "3", category, paid, 1L, null, Boolean.FALSE));

    assertEquals(
        0,
        new BigDecimal("3").compareTo(days.outsideInsurance),
        "котвата: „" + code + "“ трябва да е ИЗВЪН осигуряването, инак свидетелят мери друго");

    BigDecimal buckets = days.paid.add(days.unpaid).add(days.other);
    assertEquals(
        0,
        new BigDecimal("3").compareTo(buckets),
        "⚠️ ВСЕКИ ден извън осигуряването трябва да е и в НЯКОЯ кофа — това е условието пазачът на"
            + " freezeLeaveDays да е верен по построение. Вид „"
            + code
            + "“ (категория "
            + category
            + ") даде кофи "
            + buckets);

    BgPaySlip slip = freeze(days);
    assertNotNull(
        slip.getBgLeaveDaysFrozenAt(),
        "⚠️ „"
            + code
            + "“ трябва да мине ДО КРАЯ. Без кофа за категория 2 sicknote_unsubmitted би паднал"
            + " тук с IllegalStateException („дните по кофи (3) надхвърлят преброените дни"
            + " отсъствие (0)“) — а другите четири биха минали и това би скрило асиметрията.");
    assertEquals(
        0,
        new BigDecimal("3").compareTo(slip.getBgLeaveDaysOutside()),
        "и замразеното изваждаемо на т. 16.1 е същото за петте");
  }

  /**
   * Петте кода от {@link BgD1DayBuckets#UNBUCKETED}, с КАТЕГОРИЯТА И ПРИЗНАКА ОТ SEED-А.
   *
   * <p>⚠️ Пада, ако някой от петте изчезне от речника или получи кофа — тоест списъкът в кода и
   * данните се сверяват в ДВЕТЕ посоки, а не само в едната.
   */
  static Stream<Arguments> unbucketedSeedRows() throws IOException {
    Map<String, String[]> seed = absenceTypeSeed();
    List<Arguments> out = new ArrayList<>();
    for (String code : BgD1DayBuckets.UNBUCKETED) {
      String[] cols = seed.get(code);
      assertNotNull(
          cols,
          "⚠️ КОДЪТ „"
              + code
              + "“ е в BgD1DayBuckets.UNBUCKETED, но го НЯМА в bg_absence_type.csv. Списъкът в кода"
              + " и речникът трябва да казват едно — инак кодът пази вид, който вече не съществува.");
      assertEquals(
          "",
          cols[1].trim(),
          "⚠️ „"
              + code
              + "“ е обявен за БЕЗ кофа, а seed-ът му дава d1Code — двете си противоречат");
      assertEquals(
          "false",
          cols[2].trim(),
          "⚠️ „"
              + code
              + "“ е обявен за извън осигуряването, а seed-ът твърди, че се зачита за стаж");
      out.add(Arguments.of(code, Integer.parseInt(cols[0].trim()), "true".equals(cols[3].trim())));
    }
    assertEquals(5, out.size(), "петте вида са пет — нито четири, нито шест");
    return out.stream();
  }

  /** {@code code → {category, d1Code, countsAsInsuranceService, isPaid}} от en/ seed-а. */
  private static Map<String, String[]> absenceTypeSeed() throws IOException {
    // ⚠️ СЪС СВОЕ ЦЕПЕНЕ: bg_absence_type.csv е със ЗАПЕТАЯ, а BgSeed.rows() чете файловете с
    // точка и запетая. Същият избор и по същата причина като в BgD1DayBucketSeedTest.
    Path csv =
        Paths.get("src/main/resources/apps/init-data/en/bg_absence_type.csv").toAbsolutePath();
    assertTrue(
        Files.exists(csv),
        "не намирам речника с отсъствия: " + csv + " — пътят е спрямо директорията на модула");
    List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
    List<String> header = Arrays.asList(lines.get(0).split(",", -1));
    Map<String, String[]> out = new LinkedHashMap<>();
    for (int i = 1; i < lines.size(); i++) {
      if (lines.get(i).trim().isEmpty()) {
        continue;
      }
      String[] r = lines.get(i).split(",", -1);
      out.put(
          r[header.indexOf("code")],
          new String[] {
            r[header.indexOf("category")],
            r[header.indexOf("d1Code")],
            r[header.indexOf("countsAsInsuranceService")],
            r[header.indexOf("isPaid")]
          });
    }
    return out;
  }

  // ═══ ОБХВАТЪТ НА ПОВИШАВАНЕТО ════════════════════════════════════════════

  /**
   * ⚠️ ПОВИШАВАНЕТО Е СТРУКТУРНО, НЕ ПО КОД — и днес засяга ТОЧНО ЕДИН ред от речника.
   *
   * <p>Условието е „няма кофа по категория (2, 3, 4) И е извън осигуряването“. Двайсетте други реда
   * от категории 2/3/4 носят кофа И се зачитат за стаж, тоест излизат през {@code continue} —
   * карвът и чл. 177 за тях не се пипат. Свидетелят пази точно това: смени ли някой ред от
   * майчинство или злополука на „празна кофа + не се зачита“, той ЩЕ бъде повишен, и тогава този
   * тест го казва вместо да го премълчи.
   */
  @Test
  @DisplayName("⚠️ ОБХВАТ · само един seed ред днес отговаря на условието за повишаване")
  void exactlyOneSeedRowIsPromotedToday() throws IOException {
    List<String> promoted = new ArrayList<>();
    for (Map.Entry<String, String[]> e : absenceTypeSeed().entrySet()) {
      String category = e.getValue()[0].trim();
      boolean noBucketByCategory =
          category.isEmpty()
              || "2".equals(category)
              || "3".equals(category)
              || "4".equals(category);
      boolean outside =
          BgD1DayBuckets.outsideInsurance(e.getValue()[1], "true".equals(e.getValue()[2].trim()));
      if (noBucketByCategory && outside) {
        promoted.add(e.getKey());
      }
    }
    assertEquals(
        Collections.singletonList("sicknote_unsubmitted"),
        promoted,
        "⚠️ Разшири ли се множеството, повишаването започва да мести И карва по чл. 177 за нов вид —"
            + " виж BgArt177LeaveService#absenceDaysOf. Това е решение, не следствие, и минава през"
            + " този тест.");
  }

  // ═══ САМООТЛЪЧКАТА · БИТ ПО БИТ ══════════════════════════════════════════

  @Test
  @DisplayName("⚠️ КОНТРОЛА · самоотлъчка 3 дни — нито едно число не мърда от повишаването")
  void awolIsBitForBitUnchanged() {
    BgPayrollInputBuilder.LeaveDays days =
        leaveDaysOf(disciplinary("2026-12-07", "2026-12-09", "3"));

    assertEquals(0, BigDecimal.ZERO.compareTo(days.paid), "платената кофа е нула, както винаги");
    assertEquals(0, BigDecimal.ZERO.compareTo(days.unpaid), "неплатената също");
    assertEquals(
        0,
        new BigDecimal("3").compareTo(days.other),
        "⚠️ САМООТЛЪЧКАТА Е В „ДРУГИ“ ПО КАТЕГОРИЯ — тя е категория 5 и минава през leaveBucketOf,"
            + " не през повишаването. Свидетелят стои, за да се види, че повишаването НЕ я пипа:"
            + " ако някой ден повишаването започне да я хваща, числото ще остане същото, но пътят"
            + " ще е друг — и този тест е мястото, където разликата се обявява. Намерено: "
            + days.other);
    assertEquals(
        0, new BigDecimal("3").compareTo(days.outsideInsurance), "и карвът ѝ е непокътнат");
    assertNull(days.refusal, "и никакъв отказ: болничен лист няма, значи няма и съжителство");
  }

  // ═══ ПАРИТЕ ══════════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ПАРИТЕ · 3 дни непредставен, основна 1200,00, норма 22 → 1036,36")
  void threeUnsubmittedDaysCarveExactlyThreeDays() {
    BgPayrollInputBuilder.LeaveDays days =
        leaveDaysOf(unsubmittedSickNote("2026-12-07", "2026-12-09", "3"));

    BgEarningsOutcome base =
        BgEarningsResolver.baseSalaryLine(SALARY, NORM, NORM, null, days.outsideInsurance);

    assertTrue(base.isComputed(), "перото се смята — няма отказ: " + base.detail);
    assertEquals(
        0,
        new BigDecimal("19").compareTo(base.quantity),
        "платимите дни са 22 − 3 = 19, точно дните, които Д1 оставя в т. 16.1");
    assertEquals(
        new BigDecimal("1036.36"),
        base.amount,
        "⚠️ 1200,00 × 19 ÷ 22. Числото е СЪЩОТО като без повишаването — карвът не се мени, мени се"
            + " само кофата. Ако това число мръдне, повишаването е пипнало пари.");
  }

  @Test
  @DisplayName(
      "⚠️ СМЕСЕНО · 2 дни платен отпуск + 3 непредставен — кофите сходят, замразяването минава")
  void paidLeaveAndUnsubmittedSickNoteReconcile() {
    BgPayrollInputBuilder.LeaveDays days =
        leaveDaysOf(
            paid("2026-12-01", "2026-12-02", "2"),
            unsubmittedSickNote("2026-12-07", "2026-12-09", "3"));

    assertEquals(0, new BigDecimal("2").compareTo(days.paid), "двата дни платен отпуск са платени");
    assertEquals(0, new BigDecimal("3").compareTo(days.other), "трите непредставени са в „други“");
    assertEquals(0, new BigDecimal("3").compareTo(days.outsideInsurance), "и само те са в карва");

    BgPaySlip slip = freeze(days);
    assertNotNull(
        slip.getBgLeaveDaysFrozenAt(),
        "⚠️ БЕЗ ПОВИШАВАНЕТО ТОЗИ СЛУЧАЙ БИ ХВЪРЛИЛ: кофите биха дали 2 (т. 16.1) + 3 (outside) = 5"
            + " срещу преброени 2 дни отсъствие. С него отсъствията са 5 и двете страни сходят.");
    assertEquals(
        0,
        new BigDecimal("2").compareTo(slip.getBgLeaveDays161()),
        "платеният отпуск си остава в т. 16.1 — повишаването не го е докоснало");
  }

  // ═══ ЧЛ. 177 · ПОСЛЕДИЦАТА, И ТЯ Е В ДВЕТЕ ПОСОКИ ════════════════════════

  /**
   * ⚠️ ПОВИШАВАНЕТО В „ДРУГИ“ СТИГА ДО {@code BgArt177LeaveService#absenceDaysOf}, КОЕТО СУМИРА
   * платен + неплатен + други за базовия месец по КТ чл. 177 ал. 1 — тоест оттук нататък
   * непредставеният болничен СЕ ВАДИ от отработените дни там.
   *
   * <p>Верният прочит на НСОРЗ чл. 18 ал. 1: „среднодневното … се дели на ОТРАБОТЕНИТЕ дни“, а ден
   * без труд и без заплащане не е отработен ден. Премълчан, той би НАДУЛ знаменателя.
   */
  @Test
  @DisplayName("⚠️ ЧЛ. 177 · при КВАЛИФИЦИРАЩ базов месец среднодневното се вдига: 57,14 → 66,17")
  void theDailyRateRisesWhenTheBaseMonthStillQualifies() {
    // Базов месец: дек. 2026, норма 22, начислено по чл. 17 = 1200,00. Ползване: ян. 2027, норма
    // 21.
    assertEquals(
        new BigDecimal("57.14"),
        art177DailyRate("1200.00", 22, "22", 21),
        "БЕЗ ПОВИШАВАНЕТО: трите дни непредставен не са в нито една кофа, тоест absenceDaysOf ги"
            + " пропуска и знаменателят остава 22 отработени дни");
    assertEquals(
        new BigDecimal("66.17"),
        art177DailyRate("1200.00", 22, "19", 21),
        "С ПОВИШАВАНЕТО: 22 − 3 = 19 отработени. По-малък знаменател → ПО-ГОЛЯМО среднодневно,"
            + " тоест за квалифициращ месец повишаването вдига дължимото по чл. 177 с 9,03"
            + " на ден.");
  }

  /**
   * <b>⚠️ И ОБРАТНАТА ПОСОКА.</b> Твърдението, че числото „се движи САМО нагоре, тоест повишаването
   * не може да намали дължимото по чл. 177“, е ВЯРНО за месец, който още квалифицира, и НЕВЯРНО
   * като общо правило.
   *
   * <p>{@code BgArt177LeaveService#findBaseMonth} приема за база само месец с поне <b>10</b>
   * отработени дни ({@code BgArt177Resolver#QUALIFYING_WORKED_DAYS}, КТ чл. 177 ал. 1). Свалянето
   * на трите дни може да бутне кандидата ПОД прага — и тогава той се прескача, а за база става
   * по-РАНЕН месец, чието среднодневно е свободно да е по-ниско. Законово изходът пак е верен
   * (месец с 9 отработени дни НЕ Е законна база), но парите отиват НАДОЛУ и това трябва да е
   * казано, а не изведено.
   */
  @Test
  @DisplayName("⚠️ ЧЛ. 177 · паднал под прага от 10 дни базов месец се ПРЕСКАЧА: 57,14 → 42,86")
  void theDailyRateCanAlsoFallWhenTheBaseMonthDropsBelowTheThreshold() {
    // Декември: норма 22, отработени 12, начислено 654,55 — квалифицира с 12 ≥ 10.
    assertTrue(
        BgArt177Resolver.qualifiesAsBaseMonth(new BigDecimal("12")),
        "котвата: без повишаването декември Е законна база");
    assertEquals(
        new BigDecimal("57.14"),
        art177DailyRate("654.55", 22, "12", 21),
        "БЕЗ ПОВИШАВАНЕТО: декември е базата и дава 57,14 на ден");

    // Същият месец с повишаването: 12 − 3 = 9 отработени.
    assertFalse(
        BgArt177Resolver.qualifiesAsBaseMonth(new BigDecimal("9")),
        "⚠️ 9 < 10 — декември НЕ Е законна база по чл. 177 ал. 1 и се прескача");

    // Ноември: норма 20, отработени 20, начислено 900,00 (по-ниска заплата тогава).
    assertEquals(
        new BigDecimal("42.86"),
        art177DailyRate("900.00", 20, "20", 21),
        "⚠️ И ТУК ПАРИТЕ ОТИВАТ НАДОЛУ — 42,86 срещу 57,14, тоест 14,28 на ден по-малко. Изходът е"
            + " законово ВЕРЕН (месец с 9 отработени дни не е база), но твърдението „повишаването"
            + " не може да намали дължимото“ е ОПРОВЕРГАНО и този свидетел е мястото, където се"
            + " вижда.");
  }

  /** Среднодневното за ЕДИН ден по чл. 177, през истинския резолвер. */
  private static BigDecimal art177DailyRate(
      String accruedArt17Base, int baseNorm, String workedInBaseMonth, int usageNorm) {
    BgArt177Resolver.DailyRate rate =
        BgArt177Resolver.accruedDailyRate(
            new BigDecimal(accruedArt17Base),
            new BigDecimal(workedInBaseMonth),
            baseNorm,
            usageNorm);
    assertNotNull(rate, "входът трябва да е използваем, инак свидетелят не мери нищо");
    return BgArt177Resolver.entitlementAmount(rate, BigDecimal.ONE);
  }

  // ═══ СЪЖИТЕЛСТВОТО · ОТКАЗЪТ, КОЙТО НАЗОВАВА ДВАТА ЗАПИСА ════════════════

  @Test
  @DisplayName("⚠️ Пристигнал болничен + ОСТАНАЛА заявка за същите дни → ОТКАЗ, не 872,73")
  void anArrivedSickNoteClashingWithTheLeaveRequestIsRefused() {
    BgPayrollInputBuilder.LeaveDays days =
        leaveDaysOf(
            Collections.singletonList(episode("2026-12-07", "2026-12-09", "БЛ-77/2026")),
            unsubmittedSickNote("2026-12-07", "2026-12-09", "3"));

    assertTrue(days.isRefused(), "съжителството на двата записа е ОТКАЗ, не мълчалив избор");
    assertNull(
        days.outsideInsurance,
        "⚠️ И ЧИСЛАТА СА null, А НЕ НУЛА. Върнати като нула, те щяха да платят ПЪЛЕН месец на човек,"
            + " който е бил болен — точно обратната грешка на двойния карв.");
    assertTrue(
        days.refusal.contains("БЛ-77/2026"), "отказът НАЗОВАВА болничния лист: " + days.refusal);
    assertTrue(
        days.refusal.contains("07.12.2026"),
        "и датите на заявката, за да се намери и тя: " + days.refusal);
    assertTrue(
        days.refusal.contains("872,73") && days.refusal.contains("1036,36"),
        "⚠️ И КАЗВА КОЛКО СТРУВА, а не само че е сгрешено: " + days.refusal);

    BgEarningsOutcome base = BgEarningsResolver.baseSalaryLine(SALARY, NORM - 3, NORM, null, null);
    assertEquals(
        new BigDecimal("1036.36"),
        base.amount,
        "⚠️ КОНТРОЛА: махне ли се заявката, остава САМО болничният — 22 − 3 = 19 платими дни и"
            + " същите 1036,36. Двойният карв би дал 872,73, тоест 163,63 по-малко.");

    BgEarningsOutcome doubleCarved =
        BgEarningsResolver.baseSalaryLine(SALARY, NORM - 3, NORM, null, new BigDecimal("3"));
    assertEquals(
        new BigDecimal("872.73"),
        doubleCarved.amount,
        "⚠️ И ЧИСЛОТО, КОЕТО ОТКАЗЪТ СПИРА, е заковано тук: 1200,00 × 16 ÷ 22. Без отказа фишът"
            + " щеше да го покаже като напълно обикновен.");
  }

  @Test
  @DisplayName(
      "⚠️ Болничен БЕЗ застъпване не отказва нищо — проверката е за противоречие, не за съседство")
  void anonOverlappingSickNoteIsNotAClash() {
    BgPayrollInputBuilder.LeaveDays days =
        leaveDaysOf(
            Collections.singletonList(episode("2026-12-14", "2026-12-16", "БЛ-78/2026")),
            unsubmittedSickNote("2026-12-07", "2026-12-09", "3"));

    assertNull(days.refusal, "два различни отрязъка са два различни случая: " + days.refusal);
    assertEquals(
        0,
        new BigDecimal("3").compareTo(days.outsideInsurance),
        "и непредставените дни се карвират както обикновено");
  }

  @Test
  @DisplayName(
      "⚠️ ИСТИНСКИЯТ болничен като заявка НЕ се сблъсква — той изобщо не стига до проверката")
  void agenuineSickLeaveRequestIsNotAClash() {
    BgPayrollInputBuilder.LeaveDays days =
        leaveDaysOf(
            Collections.singletonList(episode("2026-12-07", "2026-12-09", "БЛ-79/2026")),
            sickCategory("2026-12-07", "2026-12-09", "3", true));

    assertNull(
        days.refusal,
        "⚠️ Заявка от вид, който СЕ ЗАЧИТА за осигурителен стаж, излиза през continue-то преди"
            + " повишаването — тя не твърди „болничният не е представен“ и не противоречи на листа."
            + " Намерено: "
            + days.refusal);
  }

  @Test
  @DisplayName("Чистото правило се проверява без база: празен списък от която и да е страна → null")
  void thepureRuleIsSilentWithoutBothSides() {
    assertNull(
        BgPayrollInputBuilder.sickNoteArrivalClash(
            Collections.<LocalDate[]>emptyList(),
            Collections.singletonList(episode("2026-12-07", "2026-12-09", "БЛ-80/2026"))),
        "болничен без заявка не е противоречие");
    assertNull(
        BgPayrollInputBuilder.sickNoteArrivalClash(
            Collections.singletonList(
                new LocalDate[] {LocalDate.of(2026, 12, 7), LocalDate.of(2026, 12, 9)}),
            Collections.<BgPayrollInput.SickLeaveEpisode>emptyList()),
        "заявка без болничен е нормалният случай — точно за него видът съществува");
  }

  @Test
  @DisplayName("⚠️ Всички засечени двойки се изброяват, не само първата")
  void everyClashingPairIsNamed() {
    String clash =
        BgPayrollInputBuilder.sickNoteArrivalClash(
            Arrays.asList(
                new LocalDate[] {LocalDate.of(2026, 12, 7), LocalDate.of(2026, 12, 9)},
                new LocalDate[] {LocalDate.of(2026, 12, 14), LocalDate.of(2026, 12, 16)}),
            Arrays.asList(
                episode("2026-12-07", "2026-12-09", "БЛ-81/2026"),
                episode("2026-12-14", "2026-12-16", "БЛ-82/2026")));

    assertNotNull(clash);
    assertTrue(clash.contains("БЛ-81/2026") && clash.contains("БЛ-82/2026"), clash);
    assertTrue(
        clash.contains("(2)"),
        "⚠️ И БРОЯТ Е В СЪОБЩЕНИЕТО: операторът трябва да знае колко записа да поправи, преди да"
            + " натисне пак. Намерено: "
            + clash);
  }

  // ═══ ФИКСТУРАТА ══════════════════════════════════════════════════════════

  /** Замразява и връща фиша; пада с причината на пазача, ако той хвърли. */
  private static BgPaySlip assertFreezes(Object[] row, BigDecimal expectedOutside) {
    BgPayrollInputBuilder.LeaveDays days = leaveDaysOf(row);
    assertEquals(
        0,
        expectedOutside.compareTo(days.outsideInsurance),
        "котвата: видът трябва да е ИЗВЪН осигуряването, инак свидетелят мери друго");
    BgPaySlip slip = freeze(days);
    assertNotNull(
        slip.getBgLeaveDaysFrozenAt(),
        "замразяването трябва да е стигнало до края — датата е доказателството");
    return slip;
  }

  private static BgPaySlip freeze(BgPayrollInputBuilder.LeaveDays days) {
    BgPaySlip slip = new BgPaySlip();
    slip.setReference("BGPS-2026-12-001");
    slip.setBgWorkingDaysInPeriod(NORM);
    slip.setBgWorkedDays(NORM);
    new Freezer().freeze(slip, inputOf(days));
    return slip;
  }

  /** Входът, сглобен точно както {@code BgPayrollInputBuilder#build} го сглобява от кофите. */
  private static BgPayrollInput inputOf(BgPayrollInputBuilder.LeaveDays leave) {
    return BgPayrollInput.builder()
        .periodFrom(DEC_FROM)
        .periodTo(DEC_TO)
        .workingDaysInPeriod(NORM)
        .workedDays(NORM)
        .baseSalary(SALARY)
        .paidLeaveDays(leave.paid)
        .art177LeaveDays(leave.paidUnderArt177)
        .unpaidLeaveDays(leave.unpaid)
        .unpaidLeaveDaysInD1(leave.unpaidInD1)
        .unpaidLeaveDaysUnderZzo(leave.unpaidUnderZzo)
        .daysOutsideInsurance(leave.outsideInsurance)
        .leaveDaysByD1Code(leave.byD1Code)
        .otherAbsenceDays(leave.other)
        .leaveDaysRefusal(leave.refusal)
        .build();
  }

  /** Шевът към замразяването — същият идиом като в BgD1LeaveDaysFreezeTest. */
  private static final class Freezer
      extends com.axelor.apps.bglocalization.service.BgPaySlipService {
    void freeze(BgPaySlip slip, BgPayrollInput input) {
      freezeLeaveDays(slip, input);
    }
  }

  private static BgPayrollInput.SickLeaveEpisode episode(String from, String to, String number) {
    return new BgPayrollInput.SickLeaveEpisode(
        LocalDate.parse(from), LocalDate.parse(to), false, number, "sick_er", 1L);
  }

  private static BgPayrollInputBuilder.LeaveDays leaveDaysOf(Object[]... rows) {
    return leaveDaysOf(Collections.<BgPayrollInput.SickLeaveEpisode>emptyList(), rows);
  }

  private static BgPayrollInputBuilder.LeaveDays leaveDaysOf(
      List<BgPayrollInput.SickLeaveEpisode> episodes, Object[]... rows) {
    Builder builder = new Builder(Arrays.asList(rows));
    Employee employee = new Employee();
    employee.setId(7L);
    return builder.leaveDaysOf(employee, DEC_FROM, DEC_TO, new BigDecimal("8.00"), episodes);
  }

  /** Ред на {@code BgPayrollInputBuilder#leaveRows} — единайсет колони, заявка в ДНИ. */
  private static Object[] row(
      String from,
      String to,
      String days,
      Integer category,
      Boolean paid,
      Long absenceTypeId,
      String d1Code,
      Boolean credited) {
    return new Object[] {
      LocalDate.parse(from).atStartOfDay(),
      LocalDate.parse(to).atTime(23, 59, 59),
      new BigDecimal(days),
      1,
      category,
      paid,
      absenceTypeId,
      Boolean.FALSE,
      d1Code,
      credited,
      BgUnpaidLeaveHealthResolver.RULE_UNPAID_EMPLOYEE
    };
  }

  private static Object[] paid(String from, String to, String days) {
    return row(from, to, days, 1, Boolean.TRUE, 1L, "16.1", Boolean.TRUE);
  }

  private static Object[] unpaid(String from, String to, String days, String d1Code) {
    boolean bucketed = d1Code != null && !d1Code.trim().isEmpty();
    return row(from, to, days, 7, Boolean.FALSE, 12L, d1Code, bucketed);
  }

  private static Object[] disciplinary(String from, String to, String days) {
    return row(from, to, days, 5, Boolean.FALSE, 25L, null, Boolean.FALSE);
  }

  private static Object[] sickCategory(String from, String to, String days, boolean credited) {
    return row(from, to, days, 2, Boolean.FALSE, 21L, credited ? "16.2" : null, credited);
  }

  /** Непредставеният болничен: категория 2, празна кофа, не се зачита за осигурителен стаж. */
  private static Object[] unsubmittedSickNote(String from, String to, String days) {
    return sickCategory(from, to, days, false);
  }

  /** Сглобител с подменени шевове — редовете на заявките и календарът. Без база и без Guice. */
  private static final class Builder extends BgPayrollInputBuilder {

    private final List<Object[]> rows;

    private Builder(List<Object[]> rows) {
      this.rows = rows;
    }

    @Override
    protected List<Object[]> leaveRows(Employee employee, LocalDate from, LocalDate to) {
      return rows;
    }

    @Override
    protected Set<LocalDate> officialHolidays(LocalDate from, LocalDate to) {
      Set<LocalDate> in = new HashSet<>();
      for (LocalDate day : HOLIDAYS) {
        if (!day.isBefore(from) && !day.isAfter(to)) {
          in.add(day);
        }
      }
      return in;
    }

    @Override
    protected Map<LocalDate, BigDecimal> workedHoursByDate(
        Employee employee, LocalDate from, LocalDate to) {
      return Collections.emptyMap();
    }
  }
}

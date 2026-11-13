package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ГЕЙТЪТ НА РАЗРЕЗА: ПРЕКРОЯВАНЕТО НА ДНИТЕ НЕ МЕСТИ НИТО ЕДНО ЧИСЛО НА ПАРИТЕ.
 *
 * <p><b>Защо това е опасно.</b> Разбивът осигурител/ДОО се пази като два цели агрегата върху фиша и
 * като редове по (лист × месец × платец) — разрезът, който т. 9 на Приложение № 9 иска. Редовете са
 * ФОРМА на съхранение, не правило. Но разрезът се строи в същия обход, в който се брои и сметката,
 * а промяна в този обход може да почне да брои другояче — и то тихо, защото сумите на фиша идват от
 * друг път.
 *
 * <p><b>Затова тук се пита ДВЕТЕ неща заедно, а не поотделно.</b>
 *
 * <ol>
 *   <li><b>Числата на парите са същите.</b> Перо 195 = 133,33 и разбивът 2/1 за закования случай,
 *       плюс основното 1714,29, което зависи от СБОРА на дните. Числото на перото е сметнато от
 *       {@code employerDays}, а основното — от {@code totalDays()}; сгрешен разрез, който случайно
 *       запази единия, ще размести другия.
 *   <li><b>Двата изгледа към едни и същи дни се събират до едно и също</b> — {@link
 *       BgSickLeaveDays#linesReconcile()}. Това е ИНВАРИАНТЪТ: сгрешен разрез, който все пак дава
 *       верни суми, е точно дефектът, който първата половина не лови.
 * </ol>
 *
 * <p><b>⚠️ ЧИСТ ТЕСТ, БЕЗ ПОДМЯНА.</b> Извикват се {@code BgSickLeaveResolver.split}, {@code
 * sickPayLine} и {@code BgEarningsResolver.baseSalaryAmount} — истинските. Модулът няма
 * mock-подпора и точно затова гейтът живее върху чисти функции: падне ли, паднала е сметката, а не
 * средата.
 *
 * <p>⚠️ Празниците са ПРАЗЕН списък нарочно. Тестът пита за прекрояването, не за производствения
 * календар; зареден календар би вкарал трети източник на числа в тест, който твърди, че числата не
 * се менят.
 */
class BgSickLeavePeriodLineInvariantTest {

  // ═══ ЗАКОВАНИЯТ СЛУЧАЙ ════════════════════════════════════════════════════

  /** Ноември 2026: 1-ви е неделя, 21 работни дни, нула празници в този тест. */
  private static final LocalDate NOV_FROM = LocalDate.of(2026, 11, 1);

  private static final LocalDate NOV_TO = LocalDate.of(2026, 11, 30);

  private static final int WORKING_DAYS = 21;

  private static final BigDecimal SALARY = new BigDecimal("2000.00");

  private static final BigDecimal PCT_70 = new BigDecimal("70.00");

  /** Стойностите на здравната част не участват в този тест — стоят, за да е пълен контрактът. */
  private static final BigDecimal HEALTH_BASE = new BigDecimal("620.2000");

  private static final BigDecimal HEALTH_RATE = new BigDecimal("4.8000");

  private static final Set<LocalDate> NO_HOLIDAYS = Collections.emptySet();

  private static final LocalDate ADULT = LocalDate.of(1985, 4, 12);

  /** Понеделник, вторник, сряда — трите дни на закования случай. */
  private static final String MON = "2026-11-02";

  private static final String WED = "2026-11-04";

  // ═══ 1 · ПАРИТЕ НЕ СА МРЪДНАЛИ ════════════════════════════════════════════

  @Test
  @DisplayName(
      "⚠️ ГЕЙТ · база 2000, болничен пон–ср → перо 195 = 133,33 и дни 2/1, разрезът не ги мести")
  void theSickPayAndTheDaySplitAreUnchanged() {
    BgSickLeaveDays days = split(Arrays.asList(sickEr(MON, WED, false, 700L)));

    assertTrue(days.isResolved(), String.valueOf(days.problem));
    assertEquals(2, days.employerDays.intValue(), "първите ДВА работни дни са на осигурителя");
    assertEquals(1, days.dooDays.intValue(), "третият работен ден остава за ДОО");

    BgSickLeaveOutcome line = BgSickLeaveResolver.sickPayLine(goldenFacts(), days);

    assertEquals(BgSickLeaveOutcome.Status.COMPUTED, line.status, line.detail);
    assertEquals(
        new BigDecimal("133.33"),
        line.amount,
        "чл. 40 ал. 5 КСО: 2000,00 × 70 % × 2 дни / 21 работни дни. Прекрояването на разреза по т. 9"
            + " не докосва тази формула — ако това число е мръднало, разрезът е започнал да храни"
            + " сметката, вместо да я оглежда.");
    assertEquals(
        new BigDecimal("2"), line.quantity, "количеството на перото са ДНИТЕ на осигурителя");
  }

  @Test
  @DisplayName("⚠️ ГЕЙТ · основното пада на 1714,29 — тоест СБОРЪТ на дните също е непроменен")
  void theBaseSalaryStillSeesThreeAbsentDays() {
    BgSickLeaveDays days = split(Arrays.asList(sickEr(MON, WED, false, 700L)));

    assertEquals(
        3, days.totalDays(), "три работни дни неработоспособност, независимо кой ги плаща");
    assertEquals(
        new BigDecimal("1714.29"),
        BgEarningsResolver.baseSalaryAmount(SALARY, WORKING_DAYS - days.totalDays(), WORKING_DAYS),
        "2000,00 × 18 / 21. Числото зависи от СБОРА на дните, не от разбива им — затова се пита"
            + " отделно от перото: разрез, който размести двата платеца, но запази сбора, щеше да"
            + " мине само през горния тест.");
  }

  // ═══ 2 · ИНВАРИАНТЪТ ══════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ИНВАРИАНТ · срезовете по т. 9 се събират ТОЧНО до агрегатите")
  void theLineViewReconcilesWithTheAggregates() {
    BgSickLeaveDays days = split(Arrays.asList(sickEr(MON, WED, false, 700L)));

    assertTrue(
        days.linesReconcile(),
        "Σ(срезове) + непокрити трябва да дава агрегатите. Разминаване значи, че разрезът е втора"
            + " СМЕТКА, а не втори ИЗГЛЕД — виж BgSickLeaveDays#linesReconcile. Обяснението: "
            + days.witnessAsText());
    assertEquals(2, days.employerLineDays(), "двата дни на осигурителя са и в срезовете");
    assertEquals(1, days.dooLineDays(), "денят на ДОО също");
    assertEquals(0, days.uncoveredEmployerDays, "всеки ден е покрит от лист — няма пролука");
    assertEquals(0, days.uncoveredDooDays);
  }

  @Test
  @DisplayName("Един лист в един месец → два реда: календарни граници и на двата, дни по платец")
  void oneNoteInOneMonthGivesOneRowPerPayer() {
    BgSickLeaveDays days = split(Arrays.asList(sickEr(MON, WED, false, 700L)));

    assertEquals(2, days.periodLines.size(), "един лист × един месец × два платеца");
    BgSickLeavePeriodSlice employer = onlyLine(days, 700L, BgSickLeavePeriodSlice.PAYER_EMPLOYER);
    BgSickLeavePeriodSlice noi = onlyLine(days, 700L, BgSickLeavePeriodSlice.PAYER_NOI);

    assertEquals(2, employer.workDays);
    assertEquals(1, noi.workDays);
    assertEquals(2026, employer.year());
    assertEquals(11, employer.month());
    assertEquals(
        LocalDate.parse(MON),
        employer.fromDay,
        "указанието към т. 9 иска ПЪРВИЯ КАЛЕНДАРЕН ден на листа за месеца");
    assertEquals(LocalDate.parse(WED), employer.toDay);
    assertEquals(
        employer.fromDay,
        noi.fromDay,
        "двата реда на един месец носят ЕДНИ И СЪЩИ граници — официалният ред на т. 9 за месеца е"
            + " сборът им, а граници не се сумират");
    assertEquals(employer.toDay, noi.toDay);
  }

  // ═══ 3 · ДВА БОЛНИЧНИ В ЕДИН МЕСЕЦ НЕ СЕ СЛИВАТ ═══════════════════════════

  @Test
  @DisplayName(
      "⚠️ ДУПКАТА, КОЯТО РАЗРЕЗЪТ ЗАТВАРЯ · два ОТДЕЛНИ болнични в един месец → редове за всеки")
  void twoSeparateNotesInOneMonthDoNotMerge() {
    BgSickLeaveDays days =
        split(
            Arrays.asList(
                sickEr(MON, WED, false, 700L), sickEr("2026-11-16", "2026-11-18", false, 701L)));

    assertEquals(4, days.employerDays.intValue(), "два отделни периода → по два дни всеки");
    assertEquals(2, days.dooDays.intValue());
    assertTrue(days.linesReconcile(), days.witnessAsText());

    assertEquals(4, days.periodLines.size(), "два листа × два платеца — БЕЗ сливане");
    assertEquals(2, onlyLine(days, 700L, BgSickLeavePeriodSlice.PAYER_EMPLOYER).workDays);
    assertEquals(1, onlyLine(days, 700L, BgSickLeavePeriodSlice.PAYER_NOI).workDays);
    assertEquals(2, onlyLine(days, 701L, BgSickLeavePeriodSlice.PAYER_EMPLOYER).workDays);
    assertEquals(1, onlyLine(days, 701L, BgSickLeavePeriodSlice.PAYER_NOI).workDays);
    assertEquals(
        LocalDate.parse("2026-11-16"),
        onlyLine(days, 701L, BgSickLeavePeriodSlice.PAYER_NOI).fromDay,
        "вторият лист носи СВОИТЕ граници. Точно това губеха двата агрегата на фиша: 4 и 2 не"
            + " казват кой лист кои дни е дал, а Приложение № 9 се подава ЗА ЛИСТ.");
  }

  @Test
  @DisplayName("⚠️ Продължение · парите го сливат в един период, а т. 9 пак вижда ДВАТА листа")
  void aContinuationIsOneSpellForMoneyButTwoRowsForTheForm() {
    BgSickLeaveDays days =
        split(
            Arrays.asList(
                sickEr(MON, WED, false, 700L), sickEr("2026-11-05", "2026-11-06", true, 701L)));

    assertEquals(
        2,
        days.employerDays.intValue(),
        "продължението НЕ открива нов период — два дни за целия непрекъснат отрязък, не четири");
    assertEquals(3, days.dooDays.intValue(), "остават сряда, четвъртък и петък");
    assertTrue(days.linesReconcile(), days.witnessAsText());

    assertEquals(
        2,
        onlyLine(days, 700L, BgSickLeavePeriodSlice.PAYER_EMPLOYER).workDays,
        "първите дни са на ПЪРВИЯ лист — той е открил периода");
    assertEquals(1, onlyLine(days, 700L, BgSickLeavePeriodSlice.PAYER_NOI).workDays);
    assertNull(
        line(days, 701L, BgSickLeavePeriodSlice.PAYER_EMPLOYER),
        "продължението няма дни за сметка на осигурителя — ред с нула дни НЕ се създава");
    assertEquals(
        2,
        onlyLine(days, 701L, BgSickLeavePeriodSlice.PAYER_NOI).workDays,
        "и двата му дни са на НОИ");
  }

  // ═══ 4 · ЕПИЗОД БЕЗ ЛИСТ ══════════════════════════════════════════════════

  @Test
  @DisplayName("Епизод без запис зад себе си се смята както винаги, но не дава ред за записване")
  void anEpisodeWithoutADocumentStillCounts() {
    BgSickLeaveDays days = split(Arrays.asList(sickEr(MON, WED, false, null)));

    assertEquals(2, days.employerDays.intValue(), "дните не зависят от това дали листът е записан");
    assertEquals(1, days.dooDays.intValue());
    assertTrue(days.linesReconcile(), days.witnessAsText());
    for (BgSickLeavePeriodSlice slice : days.periodLines) {
      assertTrue(
          !slice.isPersistable(),
          "срез без ид няма къде да виси: Приложение № 9 се подава за конкретен лист");
    }
  }

  // ═══ ПОМОЩНИ ══════════════════════════════════════════════════════════════

  private static BgSickLeaveDays split(List<BgSickLeaveEpisode> episodes) {
    return BgSickLeaveResolver.split(episodes, NO_HOLIDAYS, NOV_FROM, NOV_TO, 120, ADULT);
  }

  private static BgSickLeaveFacts goldenFacts() {
    return new BgSickLeaveFacts(SALARY, SALARY, WORKING_DAYS, PCT_70, HEALTH_BASE, HEALTH_RATE);
  }

  /** Обикновен болничен за общо заболяване, с ид на листа зад себе си. */
  private static BgSickLeaveEpisode sickEr(String from, String to, boolean cont, Long id) {
    return new BgSickLeaveEpisode(
        LocalDate.parse(from),
        LocalDate.parse(to),
        cont,
        "sick_er",
        "employer",
        PCT_70,
        "БЛ-" + id,
        id);
  }

  private static BgSickLeavePeriodSlice line(BgSickLeaveDays days, Long id, String payer) {
    List<BgSickLeavePeriodSlice> found = new ArrayList<>();
    for (BgSickLeavePeriodSlice slice : days.periodLines) {
      if (Objects.equals(id, slice.sickLeaveId) && payer.equals(slice.payer)) {
        found.add(slice);
      }
    }
    assertTrue(
        found.size() <= 1, "един ключ (лист, месец, платец) дава най-много един ред: " + found);
    return found.isEmpty() ? null : found.get(0);
  }

  private static BgSickLeavePeriodSlice onlyLine(BgSickLeaveDays days, Long id, String payer) {
    BgSickLeavePeriodSlice slice = line(days, id, payer);
    assertNotNull(slice, "очакваше се ред за лист " + id + " и платец " + payer);
    return slice;
  }
}

package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.service.noi.BgApp9Facts;
import com.axelor.apps.bglocalization.service.noi.BgApp9ObstoyatelstvaResolver;
import com.axelor.apps.bglocalization.service.noi.BgApp9Resolution;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРОФЕСИОНАЛНАТА БОЛЕСТ Е ВИД ОТСЪСТВИЕ — и минава по механизма на ТРУДОВАТА ЗЛОПОЛУКА.
 *
 * <p><b>Защо е отделен вид.</b> Без свой ред в {@code bg_absence_type.csv} професионалната болест
 * се въвежда като общо заболяване ({@code sick_er}) и оттам ѝ се искат шест месеца осигурителен
 * стаж, които чл. 40 ал. 2 КСО НЕ изисква — тоест на лице с по-малко стаж се отказват дните за
 * сметка на осигурителя, и то мълчаливо: изходът е законна нула с правдоподобна причина („под
 * изискваните 6“), а не отказ.
 *
 * <p><b>Защо СЪЩИЯТ механизъм, а не нов.</b> Чл. 41 ал. 1 КСО дава 90 на сто „при трудова злополука
 * ИЛИ професионална болест“ — едно изречение, една ставка; чл. 40 ал. 5 дава първите два работни
 * дни на осигурителя при 70 на сто без оглед на риска; чл. 40 ал. 2 не иска шестте месеца нито при
 * едната, нито при другата. Тоест сметката е ЕДНА, а различно е само името на риска. Затова
 * редовете в речника са ОГЛЕДАЛНИ на злополуката, а в кода влизат само двата кода в {@code
 * BgSickLeaveResolver.CODES_WITHOUT_SERVICE_REQUIREMENT}.
 *
 * <p><b>Какво се пита тук, в този ред.</b>
 *
 * <ol>
 *   <li><b>Речникът</b> — двата реда на професионалната болест са огледални на {@code
 *       work_accident_*} по ВСЯКА колона освен {@code importId}, {@code code}, {@code sequence} и
 *       {@code name}. Огледалността е твърдението „същият механизъм“, изразено в данни: разминала
 *       се колона мени сметката.
 *   <li><b>Характеризиращият</b> — болничен по професионална болест при стаж ПОД шест месеца пак
 *       дава двата дни на осигурителя, и то с точно същите числа, като при злополука.
 *   <li><b>Регресията</b> — общото заболяване и БИТОВАТА злополука са НЕПРОМЕНЕНИ: прагът им важи.
 *       Второто е важното: битовата злополука е в СЪЩАТА категория 3 и ако прагът беше паднал по
 *       категория, тя щеше да се освободи заедно с професионалната болест — по-щедро от закона.
 *   <li><b>Приложение № 9, т. 2</b> — удостоверението казва „или професионална болест“ и отменя
 *       прага по същия набор, по който се смята и перото. Второ мнение там щеше да плати
 *       обезщетение на фиша и да декларира липса на стаж пред НОИ.
 * </ol>
 *
 * <p>⚠️ ЧИСТИ ФУНКЦИИ, БЕЗ БАЗА. Модулът няма mock-подпора ({@code BgSickLeaveResolver} се тества
 * по стойности, речникът се чете като ФАЙЛ), затова падне ли нещо тук, паднала е сметката, а не
 * средата. Числата не са измислени: юли 2026 г. е реален месец — 1 юли е сряда, 23 работни дни,
 * нула официални празници, — а заплатата 1805,00 и разбивът 2 + 21 идват от сверения фиш на
 * Микроинвест за 07.2026 г.
 */
class BgOccupationalDiseaseTest {

  // ═══ ФИКСТУРИ ═════════════════════════════════════════════════════════════

  private static final LocalDate JULY_FROM = LocalDate.of(2026, 7, 1);
  private static final LocalDate JULY_TO = LocalDate.of(2026, 7, 31);

  /** Юли 2026: 1 юли е сряда, 23 работни дни, нула официални празници. */
  private static final int JULY_WORKING_DAYS = 23;

  private static final BigDecimal SALARY = new BigDecimal("1805.00");
  private static final BigDecimal PCT_70 = new BigDecimal("70.00");
  private static final BigDecimal HEALTH_BASE_H1 = new BigDecimal("550.6600");
  private static final BigDecimal HEALTH_EMPLOYER_RATE = new BigDecimal("4.8000");

  private static final Set<LocalDate> NO_HOLIDAYS = Collections.emptySet();

  /** ⚠️ ТРИ МЕСЕЦА — ПОД прага по чл. 40 ал. 1 КСО. Целият въпрос на този файл виси на това. */
  private static final int SERVICE_BELOW_THRESHOLD = 3;

  private static final LocalDate ADULT_BORN = LocalDate.of(1985, 4, 12);

  private static BgSickLeaveEpisode episode(String code, String payer, String pct) {
    return new BgSickLeaveEpisode(
        JULY_FROM, JULY_TO, false, code, payer, new BigDecimal(pct), null);
  }

  /** Разделяне при стаж ПОД прага и пълнолетно лице — случаят, който разграничава видовете. */
  private static BgSickLeaveDays splitBelowThreshold(BgSickLeaveEpisode e) {
    return BgSickLeaveResolver.split(
        Arrays.asList(e), NO_HOLIDAYS, JULY_FROM, JULY_TO, SERVICE_BELOW_THRESHOLD, ADULT_BORN);
  }

  private static BgSickLeaveFacts goldenFacts() {
    return new BgSickLeaveFacts(
        SALARY, SALARY, JULY_WORKING_DAYS, PCT_70, HEALTH_BASE_H1, HEALTH_EMPLOYER_RATE);
  }

  // ═══ 1 · РЕЧНИКЪТ — ОГЛЕДАЛНИ РЕДОВЕ ══════════════════════════════════════

  /**
   * Колоните, по които двата вида ЗАКОННО се различават — идентичността на реда, не поведението му.
   */
  private static final List<String> IDENTITY_COLUMNS =
      Arrays.asList("importId", "code", "sequence", "name");

  /**
   * Редовете на {@code bg_absence_type.csv} като „колона → стойност“, цепени по ЗАПЕТАЯ.
   *
   * <p>⚠️ СВОЕ ЦЕПЕНЕ, А НЕ {@code BgSeed.rows}: този файл е запетая-разделен (виж {@code
   * bg-localization-config.xml}: {@code separator=","}), докато {@code BgSeed} цепи по {@code ;} —
   * прекаран през него, целият ред би станал ЕДНА колона и всяка проверка тук щеше да мине зелена
   * върху нищо. Същият довод е записан и в {@code BgD1DayBucketSeedTest}.
   */
  private static Map<String, Map<String, String>> dictionary() {
    Path en = Paths.get("src/main/resources/apps/init-data/en/bg_absence_type.csv");
    Path fr = Paths.get("src/main/resources/apps/init-data/fr/bg_absence_type.csv");
    try {
      // ⚠️ ОГРАНИЧЕНИЕ НА МОДУЛА: всяка промяна влиза И в двете, байт по байт.
      if (!Arrays.equals(Files.readAllBytes(en), Files.readAllBytes(fr))) {
        fail(
            "en/ и fr/ се разминават за bg_absence_type.csv — новите редове трябва да влязат в"
                + " ДВАТА файла, байт по байт");
      }
      List<String> lines = Files.readAllLines(en, StandardCharsets.UTF_8);
      List<String> header = Arrays.asList(lines.get(0).split(",", -1));
      Map<String, Map<String, String>> out = new TreeMap<>();
      for (int i = 1; i < lines.size(); i++) {
        if (lines.get(i).trim().isEmpty()) {
          continue;
        }
        String[] cells = lines.get(i).split(",", -1);
        assertEquals(
            header.size(),
            cells.length,
            "ред "
                + (i + 1)
                + " има "
                + cells.length
                + " колони при "
                + header.size()
                + " в заглавието — най-вероятно стойност носи ЗАПЕТАЯ, а разделителят е точно тя");
        Map<String, String> row = new LinkedHashMap<>();
        for (int c = 0; c < header.size(); c++) {
          row.put(header.get(c), cells[c]);
        }
        out.put(row.get("code"), row);
      }
      return out;
    } catch (IOException e) {
      fail("не се чете bg_absence_type.csv: " + e.getMessage());
      return null;
    }
  }

  @Test
  @DisplayName("речникът · двата нови реда са ОГЛЕДАЛНИ на злополуката по всяка колона освен името")
  void theTwoNewRowsMirrorTheWorkAccidentRows() {
    Map<String, Map<String, String>> byCode = dictionary();

    for (String[] pair :
        Arrays.asList(
            new String[] {"work_accident_er", "occupational_disease_er"},
            new String[] {"work_accident_doo", "occupational_disease_doo"})) {
      Map<String, String> accident = byCode.get(pair[0]);
      Map<String, String> disease = byCode.get(pair[1]);
      assertTrue(accident != null, "липсва редът " + pair[0] + " — базата на сравнението");
      assertTrue(
          disease != null,
          "липсва редът "
              + pair[1]
              + " в bg_absence_type.csv. Без него професионалната болест се въвежда като ОБЩО"
              + " заболяване и ѝ се иска стаж, който чл. 40 ал. 2 КСО не изисква.");

      List<String> drifted = new ArrayList<>();
      for (String column : accident.keySet()) {
        if (IDENTITY_COLUMNS.contains(column)) {
          continue;
        }
        if (!accident.get(column).equals(disease.get(column))) {
          drifted.add(
              column
                  + ": "
                  + pair[0]
                  + " = „"
                  + accident.get(column)
                  + "“, "
                  + pair[1]
                  + " = „"
                  + disease.get(column)
                  + "“");
        }
      }
      if (!drifted.isEmpty()) {
        fail(
            "„същият механизъм“ е твърдение за ДАННИТЕ, не за кода — разминала се колона го"
                + " опровергава:\n  "
                + String.join("\n  ", drifted));
      }
      assertNotEquals(
          accident.get("name"),
          disease.get("name"),
          "имената трябва да СЕ РАЗЛИЧАВАТ — ТРЗ избира вида по име на екрана, а рискът, който се"
              + " декларира пред НОИ, е различен");
    }
  }

  @Test
  @DisplayName("речникът · 70 на сто от осигурителя и 90 на сто от ДОО — чл. 40 ал. 5 и чл. 41 КСО")
  void theTwoNewRowsCarryTheirRatesAndPayers() {
    Map<String, Map<String, String>> byCode = dictionary();
    Map<String, String> er = byCode.get("occupational_disease_er");
    Map<String, String> doo = byCode.get("occupational_disease_doo");

    assertEquals("employer", er.get("payer"), "чл. 40 ал. 5 КСО: първите дни са на осигурителя");
    assertEquals("70", er.get("compensationPct"), "чл. 40 ал. 5 КСО: 70 на сто");
    assertEquals("doo", doo.get("payer"), "останалите дни се обезщетяват от ДОО");
    assertEquals(
        "90",
        doo.get("compensationPct"),
        "чл. 41 ал. 1 КСО: 90 на сто при трудова злополука ИЛИ професионална болест — срещу 80 на"
            + " сто при общо заболяване, и точно затова редът не може да е sick_doo");
    assertEquals(
        "80",
        byCode.get("sick_doo").get("compensationPct"),
        "контролата: общото заболяване Е на 80 на сто и НЕ е мръднало");
  }

  // ═══ 2 · ХАРАКТЕРИЗИРАЩИЯТ — СТАЖЪТ НЕ СЕ ИЗИСКВА ═════════════════════════

  @Test
  @DisplayName("⚠️ ПРОФ. БОЛЕСТ · стаж 3 месеца → пак 2 дни осигурител + 21 ДОО, чл. 40 ал. 2 КСО")
  void anOccupationalDiseaseIsExemptFromTheServiceThreshold() {
    BgSickLeaveDays days =
        splitBelowThreshold(episode("occupational_disease_er", "employer", "70.00"));

    assertTrue(days.isResolved(), String.valueOf(days.problem));
    assertEquals(
        2,
        days.employerDays.intValue(),
        "чл. 40 ал. 2 КСО не иска шестте месеца при професионална болест — прагът по ал. 1 е за"
            + " осигурените за ОБЩО ЗАБОЛЯВАНЕ И МАЙЧИНСТВО, а това е рискът ТЗПБ. Нула тук значи,"
            + " че кодът не е влязъл в CODES_WITHOUT_SERVICE_REQUIREMENT.");
    assertEquals(21, days.dooDays.intValue(), "останалите 21 работни дни на юли остават за ДОО");
    assertTrue(
        !days.witnessAsText().contains("под изискваните"),
        "⚠️ И СВИДЕТЕЛЯТ НЕ БИВА ДА ГОВОРИ ЗА ПРАГ. Точно това изречение прикриваше дефекта:"
            + " законна нула с правдоподобна причина е неразличима от вярна нула. Свидетел: "
            + days.witnessAsText());
  }

  @Test
  @DisplayName("⚠️ ПРОФ. БОЛЕСТ · перо 195 = 109,87 при 2 дни — точно числото на злополуката")
  void theSickPayLineIsIdenticalToTheWorkAccidentOne() {
    BgSickLeaveDays disease =
        splitBelowThreshold(episode("occupational_disease_er", "employer", "70.00"));
    BgSickLeaveDays accident =
        splitBelowThreshold(episode("work_accident_er", "employer", "70.00"));

    BgSickLeaveOutcome diseaseLine = BgSickLeaveResolver.sickPayLine(goldenFacts(), disease);
    BgSickLeaveOutcome accidentLine = BgSickLeaveResolver.sickPayLine(goldenFacts(), accident);

    assertEquals(BgSickLeaveOutcome.Status.COMPUTED, diseaseLine.status, diseaseLine.detail);
    assertEquals(
        0,
        new BigDecimal("109.87").compareTo(diseaseLine.amount),
        "чл. 40 ал. 5 КСО: 1805,00 × 70 % × 2 / 23 = 109,87 — същата формула, същият делител,"
            + " същото ЕДНО закръгляне. Друго число значи, че професионалната болест е получила"
            + " СВОЯ сметка, а тя няма своя.");
    assertEquals(
        0,
        accidentLine.amount.compareTo(diseaseLine.amount),
        "⚠️ ТОВА Е ТВЪРДЕНИЕТО „СЪЩИЯТ МЕХАНИЗЪМ“, изразено в пари: злополука "
            + accidentLine.amount
            + " срещу професионална болест "
            + diseaseLine.amount);
    assertEquals(
        accident.employerDays.intValue(),
        disease.employerDays.intValue(),
        "и в дните — двата вида делят един и същ разбив");
    assertEquals(new BigDecimal("2"), diseaseLine.quantity, "количеството на перото са ДНИТЕ");
  }

  @Test
  @DisplayName("проф. болест ДОО · платецът е ДОО, тоест перо от осигурителя НЕ се създава")
  void theDooRowGivesTheEmployerNoOpeningDays() {
    BgSickLeaveDays days = splitBelowThreshold(episode("occupational_disease_doo", "doo", "90.00"));

    assertTrue(days.isResolved(), String.valueOf(days.problem));
    assertEquals(
        0,
        days.employerDays.intValue(),
        "редът с платец ДОО не дава първите дни на осигурителя — както и work_accident_doo");
    assertEquals(23, days.dooDays.intValue(), "всичките 23 работни дни са за сметка на ДОО");
    assertEquals(
        BgSickLeaveOutcome.Status.NO_EMPLOYER_DAYS,
        BgSickLeaveResolver.sickPayLine(goldenFacts(), days).status,
        "⚠️ НЕ Е ОТКАЗ И НЕ Е НУЛА НА ФИША: сума 0,00 щеше да твърди, че болничен от осигурителя"
            + " ИМА и е нулев");
    assertTrue(
        !days.witnessAsText().contains("под изискваните"),
        "и тук причината НЕ е прагът, а платецът: " + days.witnessAsText());
  }

  // ═══ 3 · РЕГРЕСИЯТА — ЗАВАРЕНОТО НЕ Е МРЪДНАЛО ════════════════════════════

  @Test
  @DisplayName("РЕГРЕСИЯ · общото заболяване пак ИСКА шестте месеца — прагът му е непроменен")
  void aGeneralIllnessStillRequiresTheSixMonths() {
    BgSickLeaveDays days = splitBelowThreshold(episode("sick_er", "employer", "70.00"));

    assertTrue(days.isResolved(), String.valueOf(days.problem));
    assertEquals(
        0,
        days.employerDays.intValue(),
        "чл. 40 ал. 1 КСО: при общо заболяване прагът важи и осигурителят не дължи първите дни."
            + " Разширяването на набора НЕ бива да го е отменило — това е обратната посока, в която"
            + " фиксът „сложи всички кодове“ би минал незабелязано.");
    assertEquals(
        23,
        days.dooDays.intValue(),
        "⚠️ и ЦЕЛИТЕ 23 дни са без осигурителен доход — прагът мени и здравната вноска по ЗЗО чл."
            + " 40 ал. 1 т. 5, не само болничния");
    assertTrue(
        days.witnessAsText().contains("под изискваните 6"),
        "нулата трябва да носи ПРИЧИНАТА си на фиша: " + days.witnessAsText());
  }

  @Test
  @DisplayName("РЕГРЕСИЯ · БИТОВАТА злополука пак иска стаж — същата категория 3, различен код")
  void aHomeAccidentStillRequiresTheSixMonths() {
    BgSickLeaveDays days = splitBelowThreshold(episode("home_accident_er", "employer", "70.00"));

    assertEquals(
        0,
        days.employerDays.intValue(),
        "⚠️ КОДЪТ, НЕ КАТЕГОРИЯТА, освобождава от праг. home_accident_er носи СЪЩАТА категория 3"
            + " („Злополука“), с която влязоха и двата нови реда — филтър по категория щеше да"
            + " освободи и битовата злополука, по-щедро от закона и напълно тихо.");
    assertEquals(
        "3",
        dictionary().get("home_accident_er").get("category"),
        "самата предпоставка на горното твърдение, прочетена от речника");
    assertEquals(
        "3",
        dictionary().get("occupational_disease_er").get("category"),
        "и другата ѝ половина: новият ред Е в същата категория");
  }

  @Test
  @DisplayName("РЕГРЕСИЯ · трудовата злополука пак е освободена — заварената освободеност е цяла")
  void aWorkAccidentIsStillExempt() {
    BgSickLeaveDays days = splitBelowThreshold(episode("work_accident_er", "employer", "70.00"));

    assertEquals(
        2,
        days.employerDays.intValue(),
        "заварената освободеност НЕ бива да е заменена, а разширена — набор, пренаписан на новите"
            + " два кода, би паднал точно тук");
    assertTrue(
        BgSickLeaveResolver.CODES_WITHOUT_SERVICE_REQUIREMENT.containsAll(
            Arrays.asList(
                "work_accident_er",
                "work_accident_doo",
                "occupational_disease_er",
                "occupational_disease_doo")),
        "и четирите кода стоят в ЕДИН набор — втори списък някъде другаде е второ мнение по въпрос"
            + " с един отговор: "
            + BgSickLeaveResolver.CODES_WITHOUT_SERVICE_REQUIREMENT);
    assertEquals(
        4,
        BgSickLeaveResolver.CODES_WITHOUT_SERVICE_REQUIREMENT.size(),
        "точно четири — нищо друго не е влязло покрай тях: "
            + BgSickLeaveResolver.CODES_WITHOUT_SERVICE_REQUIREMENT);
  }

  // ═══ 4 · ПРИЛОЖЕНИЕ № 9, Т. 2 — ЕДИН НАБОР, ЕДИН ОТГОВОР ══════════════════

  private static BgApp9Facts.Builder healthy() {
    return BgApp9Facts.builder()
        .incapacityStartDate(JULY_FROM)
        .sickPeriod(JULY_FROM, JULY_TO)
        .absenceType(2, "sick_er", "Болнични Р-л (общо заболяване)")
        .contractType("67")
        .contractDates(LocalDate.of(2026, 4, 1), null)
        .insuredTypeCode("01")
        .insuranceService(true, 12)
        .birthDate(ADULT_BORN)
        .relationships(1, 1)
        .employerReceived("125", LocalDate.of(2026, 8, 3))
        .periodLines(2, 3)
        .benefitIban("BG18RZBB91550123456789")
        .earlierApp9Submitted(Boolean.FALSE);
  }

  @Test
  @DisplayName("Прил. № 9 т. 2 · при проф. болест прагът не се прилага, и свидетелят я НАЗОВАВА")
  void clause2IsWaivedForOccupationalDiseaseToo() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(
            healthy()
                .absenceType(3, "occupational_disease_doo", "Професионална болест ДОО")
                .insuranceService(true, SERVICE_BELOW_THRESHOLD)
                .build());

    assertEquals(
        BgApp9ObstoyatelstvaResolver.STAY_MET,
        result.p2SixMonthStay,
        "удостоверението чете СЪЩИЯ набор, по който се смята и перото — иначе фишът плаща"
            + " обезщетение, а пред НОИ се декларира, че стаж няма");
    String witness = result.outcomeOf("т. 2").witness;
    assertTrue(witness.contains("ТЗПБ"), "рискът се назовава по фонда си: " + witness);
    assertTrue(
        witness.contains("професионална болест"),
        "⚠️ И СВИДЕТЕЛЯТ ТРЯБВА ДА Е ВЕРЕН, НЕ САМО ПРИСЪДАТА. Изречението „рискът е трудова"
            + " злополука“ при професионална болест това е НЕВЯРНО твърдение върху"
            + " официален документ, а присъдата под него е вярна и го прикрива. Свидетел: "
            + witness);
  }

  @Test
  @DisplayName("Прил. № 9 т. 2 · КОНТРОЛАТА · при общо заболяване стаж 3 месеца пак НЕ е налице")
  void clause2IsNotWaivedForAGeneralIllness() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(
            healthy().insuranceService(true, SERVICE_BELOW_THRESHOLD).build());

    assertEquals(
        BgApp9ObstoyatelstvaResolver.STAY_NOT_MET,
        result.p2SixMonthStay,
        "без тази половина горното твърдение е вакуумно: набор, който освобождава ВСИЧКО, също би"
            + " минал зелен");
  }
}

package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СВИДЕТЕЛСКИТЕ ТЕСТОВЕ НА ОБСТОЯТЕЛСТВАТА ПО ПРИЛОЖЕНИЕ № 9.
 *
 * <p><b>Какво пита този клас и защо точно това.</b> Всяко изведено правило има ДВА изхода, които
 * трябва да се проверяват заедно: СТОЙНОСТТА и ПРИСЪДАТА. Проверена само стойността, правило, което
 * върне вярно число по грешен довод, минава за зелено; проверена само присъдата, минава правило,
 * което обяснява добре и записва грешно. Оттам всеки тест по-долу пита и двете.
 *
 * <p><b>⚠️ И ТРЕТОТО, КОЕТО СЕ ПИТА: ЧЕ ОТКАЗЪТ НЕ Е ТИХА НУЛА.</b> При липсващ вход правилото
 * ТРЯБВА да върне {@code REFUSED} с назована причина, а НЕ „не е налице“. Разликата е в парите и в
 * подаването: „лицето няма 6 месеца стаж“ е обстоятелство, което НОИ приема и по което отказва
 * обезщетение, а „стажът не е попълнен при служителя“ е дефект в данните. Свити до едно, второто се
 * подава като първото.
 *
 * <p><b>⚠️ ЧИСТ ТЕСТ, БЕЗ ПОДМЯНА И БЕЗ СРЕДА.</b> Извиква се {@link
 * BgApp9ObstoyatelstvaResolver#resolve} — истинският. Модулът няма mock-подпора и точно затова
 * правилата живеят в чиста функция над {@link BgApp9Facts}: падне ли тест, паднало е правило, а не
 * средата.
 */
class BgApp9ObstoyatelstvaResolverTest {

  // ═══ ЗАКОВАНИЯТ СЛУЧАЙ ════════════════════════════════════════════════════

  /** Денят на настъпване на риска. Понеделник. */
  private static final LocalDate RISK_DAY = LocalDate.of(2026, 11, 2);

  private static final LocalDate SICK_FROM = LocalDate.of(2026, 11, 2);

  private static final LocalDate SICK_TO = LocalDate.of(2026, 11, 4);

  private static final LocalDate HIRED = LocalDate.of(2024, 1, 15);

  /** Пълнолетен към деня на настъпване — за да НЕ важи изключението по чл. 40 ал. 1 изр. 2. */
  private static final LocalDate ADULT_BORN = LocalDate.of(1985, 4, 12);

  /** Ненавършил 18 към 2026-11-02: точно 17 години и 7 месеца. */
  private static final LocalDate MINOR_BORN = LocalDate.of(2009, 4, 12);

  /** Категория „Болнични“ по {@code bg.absence.category.select}. */
  private static final int CATEGORY_SICK = 2;

  /** Категория „Майчинство-Бащинство“. */
  private static final int CATEGORY_MATERNITY = 4;

  /**
   * ПЪЛНИЯТ ЗДРАВ СЛУЧАЙ — всичко налично, за да може всеки тест да маха ПО ЕДНО нещо.
   *
   * <p>⚠️ Тестовете тръгват оттук нарочно: правило, проверено върху почти празен вход, се проверява
   * срещу отказа си, а не срещу извеждането си.
   */
  private static BgApp9Facts.Builder healthy() {
    return BgApp9Facts.builder()
        .incapacityStartDate(RISK_DAY)
        .sickPeriod(SICK_FROM, SICK_TO)
        .absenceType(CATEGORY_SICK, "sick_er", "Болнични Р-л (общо заболяване)")
        .contractType("67")
        .contractDates(HIRED, null)
        .insuredTypeCode("01")
        .insuranceService(true, 12)
        .birthDate(ADULT_BORN)
        .relationships(1, 1)
        .employerReceived("125", LocalDate.of(2026, 11, 5))
        .periodLines(2, 3)
        .benefitIban("BG18RZBB91550123456789")
        .earlierApp9Submitted(Boolean.FALSE);
  }

  // ═══ 0 · Т. 2 ЧЕТЕ ЗАМРАЗЕНАТА ПРИСЪДА ═══════════════════════════════════

  @Test
  @DisplayName("т. 2 ЧЕТЕ замразената присъда — „без право“ печели над днешните 12 месеца")
  void clause2ReadsTheFrozenVerdict() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(
            healthy()
                .insuranceService(true, 12)
                .benefitVerdict("NO_RIGHT", "стаж към началото: 5 месеца < 6", null)
                .build());

    assertEquals(
        BgApp9ObstoyatelstvaResolver.STAY_NOT_MET,
        result.p2SixMonthStay,
        "удостоверението казва същото право като фиша — присъдата към началото на листа");
  }

  @Test
  @DisplayName("т. 2 при отказана присъда — отказ със същия текст, не смятане по друг път")
  void clause2RefusesWhenTheVerdictIsRefused() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(
            healthy()
                .insuranceService(true, 12)
                .benefitVerdict(null, null, "липсва датата, към която е деклариран стажът")
                .build());

    assertEquals(null, result.p2SixMonthStay, "без присъда стойност няма");
  }

  // ═══ 1 · Т. 2 — СТАЖЪТ, И ТО В ТРИТЕ МУ ИЗХОДА ════════════════════════════

  @Test
  @DisplayName("т. 2 при стаж 12 → изискуемият стаж Е налице, със свидетел, който казва 12 ≥ 6")
  void clause2WithTwelveMonthsIsMetAndWitnessed() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(healthy().insuranceService(true, 12).build());

    // СТОЙНОСТТА
    assertEquals(BgApp9ObstoyatelstvaResolver.STAY_MET, result.p2SixMonthStay);

    // ПРИСЪДАТА — и то с числата в нея, а не само със статуса
    BgApp9Outcome outcome = result.outcomeOf("т. 2");
    assertNotNull(outcome, "т. 2 трябва да има присъда винаги");
    assertEquals(BgApp9Outcome.Status.DERIVED, outcome.status);
    assertTrue(
        outcome.witness.contains("12 месеца") && outcome.witness.contains("≥ 6"),
        "свидетелят трябва да носи сравнението, а не само извода: " + outcome.witness);
    assertTrue(
        outcome.witness.contains("КСО чл. 40 ал. 1"),
        "свидетелят трябва да сочи нормата: " + outcome.witness);
  }

  @Test
  @DisplayName("т. 2 при стаж 3 → изискуемият стаж НЕ е налице, с причина, и това НЕ е отказ")
  void clause2WithThreeMonthsIsNotMetWithReason() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(healthy().insuranceService(true, 3).build());

    assertEquals(BgApp9ObstoyatelstvaResolver.STAY_NOT_MET, result.p2SixMonthStay);

    BgApp9Outcome outcome = result.outcomeOf("т. 2");
    assertEquals(
        BgApp9Outcome.Status.DERIVED,
        outcome.status,
        "«няма 6 месеца стаж» Е ОБСТОЯТЕЛСТВО, което НОИ приема — то е ИЗВЕДЕНО, а не отказ");
    assertTrue(
        outcome.witness.contains("3 месеца") && outcome.witness.contains("< 6"),
        "свидетелят трябва да носи сравнението: " + outcome.witness);
    // ⚠️ И полярността: правото не е налице → образецът СЕ ОТМЯТА.
    assertTrue(
        outcome.witness.contains("СЕ ОТМЯТА"),
        "свидетелят трябва да каже какво става на ОБРАЗЕЦА, не само в модела: " + outcome.witness);
  }

  @Test
  @DisplayName("т. 2 при НЕдеклариран стаж → ОТКАЗ с причина, а НЕ тихо «не е налице»")
  void clause2WithUndeclaredStayRefusesInsteadOfSilentZero() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(healthy().insuranceService(false, null).build());

    // ⚠️ ТОВА Е ЯДРОТО НА ТЕСТА: стойност НЯМА, и то не е STAY_NOT_MET.
    assertNull(
        result.p2SixMonthStay,
        "непопълненият стаж НЕ бива да се записва като «не е налице» — това е различно"
            + " обстоятелство с различна поправка");

    BgApp9Outcome outcome = result.outcomeOf("т. 2");
    assertEquals(BgApp9Outcome.Status.REFUSED, outcome.status);
    assertTrue(
        outcome.witness.contains("bgInsuranceServiceDeclared"),
        "отказът трябва да КАЖЕ КОЕ ПОЛЕ да се попълни: " + outcome.witness);
    assertTrue(result.refusalCount() >= 1, "отказът трябва да се брои");
  }

  @Test
  @DisplayName("т. 2 при НУЛА декларирани месеца → «не е налице», не отказ: нулата е отговор")
  void clause2WithDeclaredZeroIsAnAnswerNotARefusal() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(healthy().insuranceService(true, 0).build());

    assertEquals(
        BgApp9ObstoyatelstvaResolver.STAY_NOT_MET,
        result.p2SixMonthStay,
        "нула ДЕКЛАРИРАНИ месеца е верният отговор за новопостъпил и по КСО чл. 40 ал. 1 значи"
            + " «обезщетение не се дължи» — различно от «непопълнено»");
    assertEquals(BgApp9Outcome.Status.DERIVED, result.outcomeOf("т. 2").status);
  }

  // ═══ 1б · ДВЕТЕ ИЗКЛЮЧЕНИЯ НА ЧЛ. 40 АЛ. 1 — ПРАГЪТ НЕ ВИНАГИ СЕ ПРИЛАГА ══

  @Test
  @DisplayName("т. 2: непълнолетен със стаж 0 пак ИМА правото — чл. 40 ал. 1 изр. 2 КСО")
  void clause2WaivedForMinor() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(
            healthy().insuranceService(true, 0).birthDate(MINOR_BORN).build());

    assertEquals(
        BgApp9ObstoyatelstvaResolver.STAY_MET,
        result.p2SixMonthStay,
        "«Изискването за 6 месеца осигурителен стаж НЕ СЕ ОТНАСЯ за лицата, ненавършили"
            + " 18-годишна възраст» — отметната, точката би отказала обезщетение на непълнолетен,"
            + " който има право на него");
    BgApp9Outcome outcome = result.outcomeOf("т. 2");
    assertEquals(BgApp9Outcome.Status.DERIVED, outcome.status);
    assertTrue(
        outcome.witness.contains("НЕ Е НАВЪРШИЛО")
            && outcome.witness.contains("чл. 40 ал. 1 изр. 2"),
        "свидетелят трябва да назове изключението: " + outcome.witness);
  }

  @Test
  @DisplayName("т. 2: възрастта се мери към ДЕНЯ НА НАСТЪПВАНЕ — навършените 18 връщат прага")
  void clause2MinorWaiverMeasuredAtRiskDay() {
    // Родѐн 2008-11-01 → на 2026-11-02 е навършил 18 (с един ден).
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(
            healthy().insuranceService(true, 0).birthDate(LocalDate.of(2008, 11, 1)).build());

    assertEquals(
        BgApp9ObstoyatelstvaResolver.STAY_NOT_MET,
        result.p2SixMonthStay,
        "изключението пада в деня на навършването — границата се мери към деня на настъпване,"
            + " както т. 2 е формулирана");
  }

  @Test
  @DisplayName("т. 2: при трудова злополука прагът не се прилага — друг риск, друг фонд")
  void clause2WaivedForWorkAccident() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(
            healthy()
                .absenceType(3, "work_accident_doo", "Трудова злополука ДОО")
                .insuranceService(true, 2)
                .build());

    assertEquals(BgApp9ObstoyatelstvaResolver.STAY_MET, result.p2SixMonthStay);
    assertTrue(
        result.outcomeOf("т. 2").witness.contains("ТЗПБ"),
        "шестте месеца се искат за ОЗМ, а трудовата злополука е друг риск: "
            + result.outcomeOf("т. 2").witness);
  }

  @Test
  @DisplayName("т. 2: стаж под прага БЕЗ дата на раждане → ОТКАЗ, а не «не е налице»")
  void clause2RefusesWhenMinorExceptionCannotBeChecked() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(
            healthy().insuranceService(true, 3).birthDate(null).build());

    assertNull(
        result.p2SixMonthStay,
        "без датата на раждане отговорът е НЕИЗВЕСТЕН, не отрицателен — изключението за"
            + " непълнолетни може да важи");
    BgApp9Outcome outcome = result.outcomeOf("т. 2");
    assertEquals(BgApp9Outcome.Status.REFUSED, outcome.status);
    assertTrue(outcome.witness.contains("birthDate"), outcome.witness);
  }

  @Test
  @DisplayName("т. 3 НЕ получава двете изключения — чл. 48а иска 12 месеца без уговорка")
  void clause3HasNoWaiver() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(
            healthy()
                .absenceType(CATEGORY_MATERNITY, "maternity_birth", "Бременност и раждане")
                .insuranceService(true, 0)
                .birthDate(MINOR_BORN)
                .build());

    assertEquals(
        BgApp9ObstoyatelstvaResolver.STAY_NOT_MET,
        result.p3TwelveMonthStay,
        "изключението за непълнолетни е в чл. 40 ал. 1, не в чл. 48а — пренесено върху т. 3, то"
            + " щеше да декларира стаж, който лицето няма");
    // И без дата на раждане т. 3 пак е «не е налице», а не отказ: тя не зависи от възрастта.
    BgApp9Resolution noBirthDate =
        BgApp9ObstoyatelstvaResolver.resolve(
            healthy()
                .absenceType(CATEGORY_MATERNITY, "maternity_birth", "Бременност и раждане")
                .insuranceService(true, 0)
                .birthDate(null)
                .build());
    assertEquals(BgApp9ObstoyatelstvaResolver.STAY_NOT_MET, noBirthDate.p3TwelveMonthStay);
  }

  // ═══ 2 · Т. 2 СРЕЩУ Т. 3 — РИСКЪТ ГИ РАЗДЕЛЯ ══════════════════════════════

  @Test
  @DisplayName("при майчинство т. 3 мери 12 месеца, а т. 2 е обявена за НЕОТНОСИМА")
  void maternitySwitchesFromClause2ToClause3() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(
            healthy()
                .absenceType(CATEGORY_MATERNITY, "maternity_birth", "Бременност и раждане 90% ДОО")
                .insuranceService(true, 12)
                .build());

    assertEquals(BgApp9ObstoyatelstvaResolver.STAY_NOT_APPLICABLE, result.p2SixMonthStay);
    assertEquals(BgApp9ObstoyatelstvaResolver.STAY_MET, result.p3TwelveMonthStay);

    assertEquals(BgApp9Outcome.Status.NOT_APPLICABLE, result.outcomeOf("т. 2").status);
    BgApp9Outcome clause3 = result.outcomeOf("т. 3");
    assertEquals(BgApp9Outcome.Status.DERIVED, clause3.status);
    assertTrue(
        clause3.witness.contains("КСО чл. 48а") && clause3.witness.contains("≥ 12"),
        "т. 3 иска СВОЯ праг и СВОЯТА норма: " + clause3.witness);
  }

  @Test
  @DisplayName("при майчинство стаж 11 не стига за т. 3, макар да стига за т. 2")
  void elevenMonthsFailsMaternityThreshold() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(
            healthy()
                .absenceType(CATEGORY_MATERNITY, "maternity_birth", "Бременност и раждане")
                .insuranceService(true, 11)
                .build());

    assertEquals(
        BgApp9ObstoyatelstvaResolver.STAY_NOT_MET,
        result.p3TwelveMonthStay,
        "единайсет месеца минават шестте на т. 2 и НЕ минават дванайсетте на т. 3 — точно"
            + " случаят, който слепените прагове биха объркали");
  }

  @Test
  @DisplayName("при болничен т. 3 е неотносима и свидетелят ѝ насочва към т. 2")
  void clause3NotApplicableForSickness() {
    BgApp9Resolution result = BgApp9ObstoyatelstvaResolver.resolve(healthy().build());

    assertEquals(BgApp9ObstoyatelstvaResolver.STAY_NOT_APPLICABLE, result.p3TwelveMonthStay);
    BgApp9Outcome outcome = result.outcomeOf("т. 3");
    assertEquals(BgApp9Outcome.Status.NOT_APPLICABLE, outcome.status);
    assertTrue(
        outcome.witness.contains("т. 2"),
        "неотносимостта трябва да сочи КОЯ точка отговаря вместо нея: " + outcome.witness);
  }

  // ═══ 3 · Т. 4 — КОДЪТ ИДВА ОТ ДОГОВОРА, НО САМО ОТ ДОПУСТИМИТЕ ═══════════

  @Test
  @DisplayName("т. 4 е кодът вид осигурен ОТ ДОГОВОРА, без преизчисляване")
  void clause4CopiesTheContractCode() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(healthy().insuredTypeCode("04").build());

    assertEquals("04", result.p4InsuredTypeCode);
    BgApp9Outcome outcome = result.outcomeOf("т. 4");
    assertEquals(BgApp9Outcome.Status.DERIVED, outcome.status);
    assertEquals("04", outcome.value);
    assertTrue(
        outcome.witness.contains("от договора"),
        "свидетелят трябва да каже ОТКЪДЕ е кодът: " + outcome.witness);
  }

  @Test
  @DisplayName("т. 4 ОТКАЗВА код 14: образецът не го допуска, макар договорът да е валиден")
  void clause4RefusesCodeOutsideTheApp9Set() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(healthy().insuredTypeCode("14").build());

    assertNull(result.p4InsuredTypeCode, "недопустим код НЕ се преписва мълчаливо");
    BgApp9Outcome outcome = result.outcomeOf("т. 4");
    assertEquals(BgApp9Outcome.Status.REFUSED, outcome.status);
    assertTrue(outcome.witness.contains("14"), "отказът трябва да НАЗОВЕ кода: " + outcome.witness);
    assertTrue(
        outcome.witness.contains("без трудово правоотношение"),
        "отказът трябва да каже ЗАЩО кодът е недопустим по същество, а не само че не е в списък: "
            + outcome.witness);
  }

  @Test
  @DisplayName("наборът на т. 4 е този на ОБРАЗЕЦА, не на номенклатурата на модула")
  void clause4SetIsTheFormsOwn() {
    // Образецът ги допуска, а bg.insured.type.select ги НЯМА.
    for (String code : Arrays.asList("07", "21", "93")) {
      assertTrue(
          BgApp9ObstoyatelstvaResolver.APP9_INSURED_TYPE_CODES.contains(code),
          "образецът допуска " + code);
    }
    // Модулът ги носи, а образецът НЕ ги допуска.
    for (String code : Arrays.asList("14", "18", "22", "27", "86", "91", "97")) {
      assertFalse(
          BgApp9ObstoyatelstvaResolver.APP9_INSURED_TYPE_CODES.contains(code),
          "образецът НЕ допуска " + code);
    }
    assertEquals(27, BgApp9ObstoyatelstvaResolver.APP9_INSURED_TYPE_CODES.size());
  }

  @Test
  @DisplayName("т. 4 при празен код на договора → ОТКАЗ, а не подразбиране 01")
  void clause4RefusesEmptyCode() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(healthy().insuredTypeCode(null).build());

    assertNull(result.p4InsuredTypeCode);
    assertEquals(BgApp9Outcome.Status.REFUSED, result.outcomeOf("т. 4").status);
  }

  // ═══ 4 · Т. 9 — САМО РЕФЕРЕНЦИЯ КЪМ Ф0, БЕЗ ПРЕИЗЧИСЛЯВАНЕ ═══════════════

  @Test
  @DisplayName("т. 9 СОЧИ редовете на Ф0 и обявява, че не преизчислява")
  void clause9OnlyReferencesTheF0Lines() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(healthy().periodLines(2, 3).build());

    BgApp9Outcome outcome = result.outcomeOf("т. 9");
    assertEquals(BgApp9Outcome.Status.DERIVED, outcome.status);
    assertTrue(
        outcome.value.contains("2 реда") && outcome.value.contains("3 работни дни"),
        "присъдата трябва да каже КАКВО е видяла: " + outcome.value);
    assertTrue(
        outcome.witness.contains("BgSickLeavePeriodLine")
            && outcome.witness.contains("не се преизчислява"),
        "присъдата трябва да обяви, че числата са на Ф0: " + outcome.witness);

    // ⚠️ И НАЙ-ВАЖНОТО: резолюцията НЕ носи поле за т. 9 — няма къде да се препише число.
    // Ако някой добави такова, този тест няма да падне, но домейнът също няма колона за него.
  }

  @Test
  @DisplayName("т. 9 без редове → ОТКАЗ, който сочи пресмятането на фиша, не себе си")
  void clause9RefusesWhenF0HasNotRunYet() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(healthy().periodLines(0, 0).build());

    BgApp9Outcome outcome = result.outcomeOf("т. 9");
    assertEquals(BgApp9Outcome.Status.REFUSED, outcome.status);
    assertTrue(
        outcome.witness.contains("пресмятане на фиша")
            || outcome.witness.contains("ПРЕСМЯТАНЕ НА ФИША"),
        "отказът трябва да сочи КЪДЕ се произвеждат редовете: " + outcome.witness);
  }

  // ═══ 5 · Т. 1 — ДЕНЯТ НА НАСТЪПВАНЕ, А НЕ „ОТ ДАТА“ ═════════════════════

  @Test
  @DisplayName("т. 1 = ДА за действащ договор към деня на настъпването")
  void clause1YesForRunningContract() {
    BgApp9Resolution result = BgApp9ObstoyatelstvaResolver.resolve(healthy().build());

    assertEquals(BgApp9ObstoyatelstvaResolver.YES, result.p1Insured);
    BgApp9Outcome outcome = result.outcomeOf("т. 1");
    assertEquals(BgApp9Outcome.Status.DERIVED, outcome.status);
    assertTrue(
        outcome.witness.contains(RISK_DAY.toString()),
        "свидетелят трябва да носи ДЕНЯ, по който е мерил: " + outcome.witness);
  }

  @Test
  @DisplayName("т. 1 = НЕ при прекратен преди настъпването договор, и сочи т. 7/т. 8")
  void clause1NoWhenTerminatedBeforeRisk() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(
            healthy().terminationDate(LocalDate.of(2026, 10, 20)).build());

    assertEquals(BgApp9ObstoyatelstvaResolver.NO, result.p1Insured);
    assertTrue(
        result.outcomeOf("т. 1").witness.contains("т. 7"),
        "указанието към т. 1 иска дата в т. 7 или т. 8 при «не е осигурено»");
  }

  @Test
  @DisplayName("т. 1 ОТКАЗВА при празен ден на настъпване — НЕ го заема от «От дата»")
  void clause1RefusesWithoutIncapacityStartDate() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(
            healthy().incapacityStartDate(null).sickPeriod(SICK_FROM, SICK_TO).build());

    assertNull(result.p1Insured);
    BgApp9Outcome outcome = result.outcomeOf("т. 1");
    assertEquals(BgApp9Outcome.Status.REFUSED, outcome.status);
    assertTrue(
        outcome.witness.contains("bgIncapacityStartDate"),
        "отказът трябва да назове полето, което Ф0 е въвел точно за това: " + outcome.witness);
  }

  // ═══ 6 · Т. 7 — ПЛЮС ЕДИН ДЕН, НЕ ДАТАТА НА ПРЕКРАТЯВАНЕ ═════════════════

  @Test
  @DisplayName("т. 7 е последният осигурен ден + 1, а не датата на прекратяване")
  void clause7IsTheDayAfterTheLastInsuredDay() {
    LocalDate terminated = LocalDate.of(2026, 11, 20);
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(healthy().terminationDate(terminated).build());

    assertEquals(
        LocalDate.of(2026, 11, 21),
        result.p7TerminatedFrom,
        "указанието: «календарният ден НЕПОСРЕДСТВЕНО СЛЕД последния календарен ден, през който"
            + " лицето е осигурено»");
    BgApp9Outcome outcome = result.outcomeOf("т. 7");
    assertEquals(BgApp9Outcome.Status.DERIVED, outcome.status);
    assertTrue(
        outcome.witness.contains("НЕПОСРЕДСТВЕНО СЛЕД"),
        "свидетелят трябва да носи самото правило: " + outcome.witness);
  }

  @Test
  @DisplayName("т. 7 е НЕОТНОСИМА при непрекратен договор — празно е верният отговор")
  void clause7NotApplicableWhenNotTerminated() {
    BgApp9Resolution result = BgApp9ObstoyatelstvaResolver.resolve(healthy().build());

    assertNull(result.p7TerminatedFrom);
    assertEquals(BgApp9Outcome.Status.NOT_APPLICABLE, result.outcomeOf("т. 7").status);
  }

  // ═══ 7 · Т. 5 И Т. 6 — ОСТА Е ЕИК, А НОМЕРЪТ Е ВЪТРЕ В БРОЯ ══════════════

  @Test
  @DisplayName("т. 6 е пореден номер В ГРАНИЦИТЕ на т. 5, а не брояч по лице")
  void clause6IsOrdinalWithinClause5() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(healthy().relationships(2, 2).build());

    assertEquals(Integer.valueOf(2), result.p5RelationshipCount);
    assertEquals(Integer.valueOf(2), result.certificateSeqNo);
    assertTrue(
        result.outcomeOf("т. 6").witness.contains("НЕ Е брояч по лице"),
        "присъдата трябва да отрече точно грешния прочит: " + result.outcomeOf("т. 6").witness);
  }

  @Test
  @DisplayName("т. 6 > т. 5 е противоречив вход → ОТКАЗ, и НИТО ЕДНОТО не се записва")
  void clause6RefusesWhenBeyondClause5() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(healthy().relationships(1, 3).build());

    assertNull(result.certificateSeqNo, "противоречивите числа не се записват");
    assertEquals(BgApp9Outcome.Status.REFUSED, result.outcomeOf("т. 6").status);
  }

  @Test
  @DisplayName("т. 5 НЕ добавя «осигурен на друго основание» и го КАЗВА")
  void clause5ExcludesOtherGroundAndSaysSo() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(
            healthy().insuredOnOtherGround(true).relationships(1, 1).build());

    assertEquals(
        Integer.valueOf(1),
        result.p5RelationshipCount,
        "другото основание е при ДРУГ ЕИК — т. 5 брои само при този осигурител");
    assertTrue(
        result.outcomeOf("т. 5").witness.contains("НЕ Е ДОБАВЕНО"),
        "изключването трябва да е ОБЯВЕНО, не премълчано: " + result.outcomeOf("т. 5").witness);
  }

  // ═══ 8 · Т. 1.1 — bgContractType РЕШАВА САМО ДВЕ ОТ СЕДЕМ СТОЙНОСТИ ══════

  @Test
  @DisplayName("т. 1.1: чл. 67 → безсрочно; чл. 68 → срочно с уговорения край")
  void clause11FromContractType() {
    BgApp9Resolution indefinite =
        BgApp9ObstoyatelstvaResolver.resolve(healthy().contractType("67").build());
    assertEquals(BgApp9ObstoyatelstvaResolver.TERM_INDEFINITE, indefinite.p1TermType);
    assertNull(indefinite.p1TermEndDate, "безсрочният договор няма срок за попълване");

    LocalDate agreedEnd = LocalDate.of(2027, 6, 30);
    BgApp9Resolution fixed =
        BgApp9ObstoyatelstvaResolver.resolve(
            healthy().contractType("68").contractDates(HIRED, agreedEnd).build());
    assertEquals(BgApp9ObstoyatelstvaResolver.TERM_FIXED, fixed.p1TermType);
    assertEquals(agreedEnd, fixed.p1TermEndDate);
  }

  @Test
  @DisplayName("т. 1.1 ОТКАЗВА за чл. 111, ДУК и ГД — видът договор не решава срочността")
  void clause11RefusesWhereContractTypeCannotDecide() {
    for (String type : Arrays.asList("111", "121a", "duk", "gd", null)) {
      BgApp9Resolution result =
          BgApp9ObstoyatelstvaResolver.resolve(healthy().contractType(type).build());
      assertNull(result.p1TermType, "срочността НЕ се гадае за вид «" + type + "»");
      assertEquals(
          BgApp9Outcome.Status.REFUSED,
          result.outcomeOf("т. 1.1").status,
          "вид «" + type + "» трябва да дава ОТКАЗ с причина");
    }
  }

  @Test
  @DisplayName("т. 1.1 при чл. 68 без уговорен край → ОТКАЗ: указанието иска ДАТАТА")
  void clause11RefusesFixedTermWithoutEndDate() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(
            healthy().contractType("68").contractDates(HIRED, null).build());

    assertNull(result.p1TermEndDate);
    assertEquals(BgApp9Outcome.Status.REFUSED, result.outcomeOf("т. 1.1").status);
  }

  // ═══ 9 · Т. 13–23 — ГЕЙТЪТ Е БЕЛЕЖКА ** НА ОБРАЗЕЦА ═════════════════════

  @Test
  @DisplayName("т. 13–23 са НЕОТНОСИМИ при обикновен болничен, с цитат на бележка **")
  void clause13NotApplicableForOrdinarySickLeave() {
    BgApp9Resolution result = BgApp9ObstoyatelstvaResolver.resolve(healthy().build());

    assertNull(result.p13ChildId);
    BgApp9Outcome outcome = result.outcomeOf("т. 13–23");
    assertNotNull(outcome, "неотносимостта трябва да е ОБЯВЕНА, не мълчалива");
    assertEquals(BgApp9Outcome.Status.NOT_APPLICABLE, outcome.status);
    assertTrue(
        outcome.witness.contains("42") && outcome.witness.contains("чл. 167 КТ"),
        "гейтът е бележка ** на образеца и свидетелят трябва да я носи: " + outcome.witness);
  }

  @Test
  @DisplayName("т. 13 се извежда при майчинство и ТОЧНО едно дете")
  void clause13DerivedForSingleChild() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(
            healthy()
                .absenceType(CATEGORY_MATERNITY, "maternity_birth", "Бременност и раждане")
                .children(1, 7701L, "Ирина Петрова Иванова")
                .build());

    assertEquals(Long.valueOf(7701L), result.p13ChildId);
    BgApp9Outcome outcome = result.outcomeOf("т. 13");
    assertEquals(BgApp9Outcome.Status.DERIVED, outcome.status);
    assertEquals("Ирина Петрова Иванова", outcome.value);
  }

  @Test
  @DisplayName("т. 13 ОТКАЗВА при две деца — точката удостоверява ЕДНО, изборът не е изводим")
  void clause13RefusesWithSeveralChildren() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(
            healthy()
                .absenceType(CATEGORY_MATERNITY, "maternity_birth", "Бременност и раждане")
                .children(2, null, null)
                .build());

    assertNull(result.p13ChildId, "при две деца НЕ се избира наслука");
    BgApp9Outcome outcome = result.outcomeOf("т. 13");
    assertEquals(BgApp9Outcome.Status.REFUSED, outcome.status);
    assertTrue(
        outcome.witness.contains("2"), "отказът трябва да каже КОЛКО са: " + outcome.witness);
  }

  // ═══ 10 · Т. 25 И Т. 26 — ДВЕТЕ ПРОТИВОПОЛОЖНИ РЕШЕНИЯ ЗА КОПИРАНЕТО ════

  @Test
  @DisplayName("т. 25 се РЕФЕРИРА през листа и НЕ се копира в удостоверението")
  void clause25IsReferencedNotCopied() {
    BgApp9Resolution result = BgApp9ObstoyatelstvaResolver.resolve(healthy().build());

    BgApp9Outcome outcome = result.outcomeOf("т. 25");
    assertEquals(BgApp9Outcome.Status.DERIVED, outcome.status);
    assertTrue(outcome.value.contains("125"), "присъдата носи стойността: " + outcome.value);
    assertTrue(
        outcome.witness.contains("НЕ СЕ") && outcome.witness.contains("КОПИРА"),
        "решението «без копие» трябва да е обявено: " + outcome.witness);
  }

  @Test
  @DisplayName("т. 25 е НЕОТНОСИМА за самоосигуряващо се лице — указанието го забранява изрично")
  void clause25SuppressedForSelfInsured() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(healthy().selfInsured(true).build());

    BgApp9Outcome outcome = result.outcomeOf("т. 25");
    assertEquals(
        BgApp9Outcome.Status.NOT_APPLICABLE,
        outcome.status,
        "«В удостоверението за болнични листове, издадени на самоосигуряващи се лица, НЕ се"
            + " попълват данни на този ред»");
  }

  @Test
  @DisplayName("т. 26 СЕ КОПИРА — точката удостоверява сметката, каквато е при това подаване")
  void clause26IsCachedOnPurpose() {
    BgApp9Resolution result = BgApp9ObstoyatelstvaResolver.resolve(healthy().build());

    assertEquals("BG18RZBB91550123456789", result.benefitIban);
    assertEquals(BgApp9ObstoyatelstvaResolver.YES, result.p26FirstSubmission);
    assertTrue(
        result.outcomeOf("т. 26").witness.contains("ПЪРВОТО представяне"),
        "бележка *** е причината точката да съществува: " + result.outcomeOf("т. 26").witness);
  }

  @Test
  @DisplayName("т. 26 · първо представяне = НЕ, когато вече има подадено удостоверение")
  void clause26NotFirstWhenEarlierSubmissionExists() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(healthy().earlierApp9Submitted(Boolean.TRUE).build());

    assertEquals(BgApp9ObstoyatelstvaResolver.NO, result.p26FirstSubmission);
    assertTrue(
        result.outcomeOf("т. 26 · първо представяне").witness.contains("раздел II"),
        "свидетелят трябва да сочи КЪДЕ се удостоверява промяната");
  }

  @Test
  @DisplayName("т. 26 ОТКАЗВА при празен IBAN и КАЗВА защо не взима bankDetails")
  void clause26RefusesEmptyIbanAndExplainsWhyNotBankDetails() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(healthy().benefitIban(null).build());

    assertNull(result.benefitIban);
    BgApp9Outcome outcome = result.outcomeOf("т. 26");
    assertEquals(BgApp9Outcome.Status.REFUSED, outcome.status);
    assertTrue(
        outcome.witness.contains("bankDetails"),
        "отказът трябва да отрече очевидния грешен източник: " + outcome.witness);
  }

  // ═══ 11 · РЪЧНИТЕ ТОЧКИ — ОБЯВЕНИ, С ПРЕДЛОЖЕНИЕ, БЕЗ СТОЙНОСТ ══════════

  @Test
  @DisplayName("т. 12 предлага от припокриване с отпуск, но НЕ потвърждава")
  void clause12ProposesWithoutConfirming() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(
            healthy()
                .overlappingPaidLeave(
                    Collections.singletonList("Платен годишен отпуск (2026-11-03 – 2026-11-06)"))
                .build());

    BgApp9Outcome outcome = result.outcomeOf("т. 12");
    assertEquals(
        BgApp9Outcome.Status.MANUAL,
        outcome.status,
        "припокриване по календар НЕ е «разрешен отпуск съгласно заповед»");
    assertNull(outcome.value, "предложението НЕ е стойност");
    assertTrue(
        outcome.witness.contains("ПРЕДЛОЖЕНИЕ") && outcome.witness.contains("2026-11-03"),
        "предложението трябва да носи ТОЧНИТЕ дати, за да е ползваемо: " + outcome.witness);
    assertTrue(
        outcome.witness.contains("ОТМЕНЕН"),
        "свидетелят трябва да назове контрапримера, заради който точката е ръчна: "
            + outcome.witness);
  }

  @Test
  @DisplayName("т. 12 без припокриване казва, че няма какво да предложи")
  void clause12SaysNothingToProposeWhenNoOverlap() {
    BgApp9Resolution result = BgApp9ObstoyatelstvaResolver.resolve(healthy().build());

    BgApp9Outcome outcome = result.outcomeOf("т. 12");
    assertEquals(BgApp9Outcome.Status.MANUAL, outcome.status);
    assertFalse(
        outcome.witness.contains("ПРЕДЛОЖЕНИЕ"),
        "без припокриване не бива да има предложение: " + outcome.witness);
  }

  @Test
  @DisplayName("т. 8 предлага при чл. 114 — указанието го изброява като повод за прекъсване")
  void clause8ProposesForArt114() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(healthy().contractType("114").build());

    BgApp9Outcome outcome = result.outcomeOf("т. 8");
    assertEquals(BgApp9Outcome.Status.MANUAL, outcome.status);
    assertTrue(
        outcome.witness.contains("чл. 114") && outcome.witness.contains("ПРЕДЛОЖЕНИЕ"),
        "чл. 114 е дословно в указанието към т. 8: " + outcome.witness);
  }

  @Test
  @DisplayName("т. 8 предлага и при отпуск, който не се зачита за осигурителен стаж")
  void clause8ProposesForLeaveNotCountedAsStay() {
    BgApp9Resolution result =
        BgApp9ObstoyatelstvaResolver.resolve(
            healthy()
                .leaveNotCountedAsInsuranceService(
                    Collections.singletonList("Неплатен отпуск (2026-10-01 – 2026-10-31)"))
                .build());

    assertTrue(
        result.outcomeOf("т. 8").witness.contains("НЕ СЕ ЗАЧИТА"),
        "предложението трябва да каже по кой повод от указанието е: "
            + result.outcomeOf("т. 8").witness);
  }

  @Test
  @DisplayName("ръчните точки нямат поле в резолюцията — резолверът НЕ МОЖЕ да ги напише")
  void manualClausesHaveNoValueChannel() {
    BgApp9Resolution result = BgApp9ObstoyatelstvaResolver.resolve(healthy().build());

    // Т. 10, 11, 12, 24 и блокът 14–23 съществуват САМО като присъди със статус MANUAL.
    for (String clause : Arrays.asList("т. 10", "т. 10.1/10.2", "т. 11", "т. 12", "т. 24")) {
      BgApp9Outcome outcome = result.outcomeOf(clause);
      assertNotNull(outcome, clause + " трябва да е обявена, не пропусната");
      assertEquals(BgApp9Outcome.Status.MANUAL, outcome.status, clause + " е входна");
      assertNull(outcome.value, clause + " не бива да носи стойност");
    }
  }

  // ═══ 12 · ЦЯЛОСТ — НИТО ЕДНА ТОЧКА БЕЗ ПРИСЪДА ══════════════════════════

  @Test
  @DisplayName("всяка присъда носи непразен свидетел — включително изведените")
  void everyOutcomeCarriesAWitness() {
    BgApp9Resolution result = BgApp9ObstoyatelstvaResolver.resolve(healthy().build());

    assertFalse(result.outcomes.isEmpty(), "резолверът никога не връща празен списък присъди");
    for (BgApp9Outcome outcome : result.outcomes) {
      assertNotNull(outcome.witness, outcome.clause + " е без свидетел");
      assertFalse(
          outcome.witness.trim().isEmpty(),
          outcome.clause + " е с празен свидетел — тиха присъда е липсваща присъда");
      assertFalse(outcome.clause.trim().isEmpty(), "присъда без номер на точка е неадресируема");
      if (outcome.status == BgApp9Outcome.Status.DERIVED) {
        assertNotNull(outcome.value, outcome.clause + " е изведена без стойност");
      } else {
        assertNull(outcome.value, outcome.clause + " не е изведена, но носи стойност");
      }
    }
  }

  @Test
  @DisplayName("празният вход дава САМО откази и неотносимости — нула тихи нули")
  void emptyFactsProduceRefusalsNotSilentValues() {
    BgApp9Resolution result = BgApp9ObstoyatelstvaResolver.resolve(BgApp9Facts.builder().build());

    // Нито една стойност не се измисля.
    assertNull(result.p1Insured);
    assertNull(result.p2SixMonthStay);
    assertNull(result.p4InsuredTypeCode);
    assertNull(result.p5RelationshipCount);
    assertNull(result.certificateSeqNo);
    assertNull(result.p7TerminatedFrom);
    assertNull(result.benefitIban);
    assertNull(result.p13ChildId);

    // И всяка липса е ОБЯВЕНА.
    assertTrue(
        result.refusalCount() >= 6,
        "празният вход трябва да произведе откази, а не тишина: " + result.refusalCount());
    assertEquals(0, result.derivedCount(), "нищо не бива да се извежда от нищо");
  }
}

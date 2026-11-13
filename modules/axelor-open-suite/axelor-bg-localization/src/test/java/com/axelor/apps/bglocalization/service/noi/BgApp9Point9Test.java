package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Т. 9 — СБОРЪТ НА РЕДОВЕТЕ НА Ф0, И ТОЧНО ТОЗИ СБОР.
 *
 * <p><b>⚠️ ЗАДАНИЕТО НА Ф2 ИСКАШЕ ДРУГО, И ПЪРВОИЗТОЧНИКЪТ РЕШАВА.</b> Заданието гласи „агрегация …
 * в редовете-платец, които XSD-то очаква (осигурител / НОИ разбивка)“. В {@code Pril9.xsd} такива
 * редове НЯМА: {@code BSickLeave} носи ЕДНО число {@code Days} — „брой работни дни в месеца с право
 * на парично обезщетение И възнаграждение“. Ф0 го е записал в домейна си още тогава: „Официалният
 * ред НЕ дели по платец; нашият е едно ниво по-фин и се агрегира до неговия със СБОР“.
 *
 * <p><b>И сборът не е удобство.</b> Контролът към т. 9 допуска два комплекта данни за един месец
 * САМО „в случаите на попълнена дата на прекратяване в т. 7“. Пренесени едно към едно, двата реда
 * на Ф0 щяха да дават два комплекта за всеки месец с първи два дни по чл. 40 ал. 5 — тоест за почти
 * всеки болничен.
 *
 * <p><b>⚠️ И НИТО ЕДИН СВИДЕТЕЛ ТУК НЕ ДОКОСВА ПАРИ.</b> Броевете идват от Ф0 и се СЪБИРАТ; нищо не
 * се преизчислява. Перо 195 се пази от {@code BgSickLeavePeriodLineInvariantTest} и остава негова
 * работа.
 */
class BgApp9Point9Test {

  private static final BigDecimal EIGHT = new BigDecimal("8.00");

  @Test
  @DisplayName("Двата реда на един месец (осигурител + НОИ) стават ЕДИН комплект със СБОРА")
  void twoPayerLinesBecomeOneMonthWithTheSum() {
    BgSickLeave sick =
        BgApp9Fixture.sickLeave(
            "E", 2026, "0001234", LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 12));
    BgApp9Fixture.standardLines(sick); // 2 дни осигурител + 4 дни НОИ

    List<BgApp9Point9.Month> months =
        BgApp9Point9.aggregate(sick.getBgPeriodLineList(), null, "01");

    assertEquals(1, months.size(), "един календарен месец дава ЕДИН комплект");
    assertEquals(6, months.get(0).days, "6 = 2 (осигурител) + 4 (НОИ)");
    assertEquals(2, months.get(0).sourceLineCount, "сборът трябва да помни от колко реда идва");
    assertEquals(1, months.get(0).sickNumber);
    assertEquals(5, months.get(0).fromDay);
    assertEquals(12, months.get(0).toDay);
  }

  @Test
  @DisplayName("Болничен през два месеца дава ДВА комплекта, номерирани хронологично")
  void aLeaveSpanningTwoMonthsGivesTwoSets() {
    BgSickLeave sick =
        BgApp9Fixture.sickLeave(
            "E", 2026, "0001234", LocalDate.of(2026, 6, 25), LocalDate.of(2026, 7, 8));
    // ⚠️ Редовете нарочно се подават В ОБРАТЕН РЕД — o2m няма order-by, а контролът иска
    // „в хронология“. Ако подредбата зависеше от входа, номерата щяха да са разменени.
    BgApp9Fixture.line(
        sick,
        LocalDate.of(2026, 7, 1),
        LocalDate.of(2026, 7, 8),
        6,
        "NOI",
        EIGHT,
        new BigDecimal("48.00"));
    BgApp9Fixture.line(
        sick,
        LocalDate.of(2026, 6, 25),
        LocalDate.of(2026, 6, 30),
        2,
        "EMPLOYER",
        EIGHT,
        new BigDecimal("16.00"));
    BgApp9Fixture.line(
        sick,
        LocalDate.of(2026, 6, 25),
        LocalDate.of(2026, 6, 30),
        2,
        "NOI",
        EIGHT,
        new BigDecimal("16.00"));

    List<BgApp9Point9.Month> months =
        BgApp9Point9.aggregate(sick.getBgPeriodLineList(), null, "01");

    assertEquals(2, months.size());
    assertEquals(1, months.get(0).sickNumber);
    assertEquals(6, months.get(0).month);
    assertEquals(4, months.get(0).days, "юни: 2 + 2");
    assertEquals(2, months.get(1).sickNumber);
    assertEquals(7, months.get(1).month);
    assertEquals(6, months.get(1).days, "юли: 6");
  }

  @Test
  @DisplayName("⚠️ ДВА БОЛНИЧНИ В ЕДИН МЕСЕЦ ДАВАТ ДВА ПРИНОСА — без сливане")
  void twoSickLeavesInOneMonthDoNotMerge() {
    // Точно агрегатът, който Ф0 съществува, за да разгради: bgSickDaysEmployer/bgSickDaysDoo бяха
    // по (договор × месец) и сливаха двата листа в едно число, без обратен път до документа.
    BgSickLeave first =
        BgApp9Fixture.sickLeave(
            "E", 2026, "0001111", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5));
    BgApp9Fixture.line(
        first,
        LocalDate.of(2026, 6, 1),
        LocalDate.of(2026, 6, 5),
        3,
        "EMPLOYER",
        EIGHT,
        new BigDecimal("24.00"));

    BgSickLeave second =
        BgApp9Fixture.sickLeave(
            "E", 2026, "0002222", LocalDate.of(2026, 6, 20), LocalDate.of(2026, 6, 26));
    BgApp9Fixture.line(
        second,
        LocalDate.of(2026, 6, 20),
        LocalDate.of(2026, 6, 26),
        5,
        "NOI",
        EIGHT,
        new BigDecimal("40.00"));

    List<BgApp9Point9.Month> firstMonths =
        BgApp9Point9.aggregate(first.getBgPeriodLineList(), null, "01");
    List<BgApp9Point9.Month> secondMonths =
        BgApp9Point9.aggregate(second.getBgPeriodLineList(), null, "01");

    // ЕДИН месец, но ДВА отделни приноса — по един за всеки лист, всеки със своите граници.
    assertEquals(1, firstMonths.size());
    assertEquals(3, firstMonths.get(0).days);
    assertEquals(1, firstMonths.get(0).fromDay);
    assertEquals(5, firstMonths.get(0).toDay);

    assertEquals(1, secondMonths.size());
    assertEquals(5, secondMonths.get(0).days);
    assertEquals(20, secondMonths.get(0).fromDay);
    assertEquals(26, secondMonths.get(0).toDay);

    // И най-важното: сборът НЕ е 8 дни в един комплект за юни.
    assertFalse(
        firstMonths.get(0).days == 8 || secondMonths.get(0).days == 8,
        "двата листа се сляха — точно това Ф0 разгради");
  }

  @Test
  @DisplayName("⚠️ Всеки лист вижда САМО своите редове — емитерът чете през листа")
  void eachDeclarationSeesOnlyItsOwnLines() {
    BgSickLeave first =
        BgApp9Fixture.sickLeave(
            "E", 2026, "0001111", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5));
    BgApp9Fixture.line(
        first,
        LocalDate.of(2026, 6, 1),
        LocalDate.of(2026, 6, 5),
        3,
        "EMPLOYER",
        EIGHT,
        new BigDecimal("24.00"));
    BgApp9Declaration d = BgApp9Fixture.declarationFor(first);

    List<BgApp9Point9.Month> months = BgApp9Emitter.monthsOf(d);
    assertEquals(1, months.size());
    assertEquals(3, months.get(0).days);
  }

  @Test
  @DisplayName("⚠️ ЧАСОВЕТЕ СА ВИНАГИ ПРАЗНИ във файла, а липсата им се ОБЯВЯВА")
  void hoursAreAlwaysEmptyAndTheGapIsDeclared() {
    // Ф0 пише workHours САМО при подневно отчитане; указанието иска часове САМО при СИРВ. Тоест
    // наличните часове са за режима, в който образецът ги ЗАБРАНЯВА.
    BgSickLeave daily =
        BgApp9Fixture.sickLeave(
            "E", 2026, "0001234", LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 12));
    BgApp9Fixture.standardLines(daily);
    List<BgApp9Point9.Month> dailyMonths =
        BgApp9Point9.aggregate(daily.getBgPeriodLineList(), null, "01");
    assertFalse(
        dailyMonths.get(0).hoursWantedButMissing, "при подневно отчитане часове не се искат");

    // При СИРВ Ф0 оставя часовете празни — значи графикът липсва и това се ФЛАГВА.
    BgSickLeave sirv =
        BgApp9Fixture.sickLeave(
            "E", 2026, "0001235", LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 12));
    BgApp9Fixture.line(
        sirv, LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 12), 6, "NOI", EIGHT, null);
    List<BgApp9Point9.Month> sirvMonths =
        BgApp9Point9.aggregate(sirv.getBgPeriodLineList(), null, "01");
    assertTrue(sirvMonths.get(0).hoursWantedButMissing, "липсата на график трябва да е обявена");

    // И в двата случая файлът подава ДНИ, а Hours остава празен.
    BgApp9Declaration d = BgApp9Fixture.declarationFor(daily);
    String xml = BgApp9Emitter.emit(BgApp9Fixture.context(), BgApp9Fixture.one(d), 1).getXml();
    assertTrue(xml.contains("<Days>6</Days>"));
    assertTrue(xml.contains("<Hours></Hours>"), "часовете трябва да са ПРАЗНИ, не нула");
  }

  @Test
  @DisplayName("Дневната норма се ЗАКРЪГЛЯ по правилото „остатък под 30 минути към пълния час“")
  void legalHoursAreRoundedByTheOrdinancesRule() {
    assertEquals(7, BgApp9Point9.roundLegalHours(new BigDecimal("7.49")));
    assertEquals(8, BgApp9Point9.roundLegalHours(new BigDecimal("7.50")));
    assertEquals(4, BgApp9Point9.roundLegalHours(new BigDecimal("4.00")));

    BgSickLeave sick =
        BgApp9Fixture.sickLeave(
            "E", 2026, "0001234", LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 12));
    BgApp9Fixture.line(
        sick,
        LocalDate.of(2026, 6, 5),
        LocalDate.of(2026, 6, 12),
        6,
        "NOI",
        new BigDecimal("7.50"),
        new BigDecimal("45.00"));

    BgApp9Point9.Month month =
        BgApp9Point9.aggregate(sick.getBgPeriodLineList(), null, "01").get(0);
    assertEquals(Integer.valueOf(8), month.legalWorkHours);
    assertTrue(month.legalHoursRounded, "закръглянето трябва да остави следа");
  }

  @Test
  @DisplayName("⚠️ Липсваща норма дава ЗАМЕСТВАЩИТЕ 8 часа — обявено, не мълчаливо")
  void missingDailyHoursUseTheSubstituteAndSaySo() {
    BgSickLeave sick =
        BgApp9Fixture.sickLeave(
            "E", 2026, "0001234", LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 12));
    BgApp9Fixture.line(
        sick, LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 12), 6, "NOI", null, null);

    BgApp9Point9.Month month =
        BgApp9Point9.aggregate(sick.getBgPeriodLineList(), null, "01").get(0);

    // Ф0 отказа да запише осемте като ФАКТ („записани като факт, стават неразличими от действително
    // уговорени осем“); правилото е на ОБРАЗЕЦА и се прилага тук, със следа.
    assertEquals(Integer.valueOf(8), month.legalWorkHours);
    assertTrue(month.legalHoursSubstituted);
  }

  @Test
  @DisplayName("Месец изцяло СЛЕД прекратяване няма норма — контролът я освобождава")
  void monthsAfterTerminationCarryNoLegalHours() {
    BgSickLeave sick =
        BgApp9Fixture.sickLeave(
            "E", 2026, "0001234", LocalDate.of(2026, 6, 25), LocalDate.of(2026, 7, 8));
    BgApp9Fixture.line(
        sick,
        LocalDate.of(2026, 6, 25),
        LocalDate.of(2026, 6, 30),
        4,
        "NOI",
        EIGHT,
        new BigDecimal("32.00"));
    BgApp9Fixture.line(
        sick,
        LocalDate.of(2026, 7, 1),
        LocalDate.of(2026, 7, 8),
        6,
        "NOI",
        EIGHT,
        new BigDecimal("48.00"));

    List<BgApp9Point9.Month> months =
        BgApp9Point9.aggregate(sick.getBgPeriodLineList(), LocalDate.of(2026, 7, 1), "01");

    assertEquals(Integer.valueOf(8), months.get(0).legalWorkHours, "юни е ПРЕДИ прекратяването");
    assertNull(months.get(1).legalWorkHours, "юли е изцяло след него");
  }

  @Test
  @DisplayName("Празен вход дава празен резултат, а не изключение")
  void emptyInputGivesEmptyOutput() {
    assertTrue(BgApp9Point9.aggregate(null, null, "01").isEmpty());
    assertTrue(BgApp9Point9.aggregate(new java.util.ArrayList<>(), null, "01").isEmpty());
  }
}

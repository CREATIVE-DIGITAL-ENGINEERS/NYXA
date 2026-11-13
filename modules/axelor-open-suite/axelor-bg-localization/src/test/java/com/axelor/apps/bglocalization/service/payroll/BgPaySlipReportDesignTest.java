package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПЕЧАТНИЯТ ФИШ — <b>структурата, проверена без BIRT и без база</b>, по образеца на {@code
 * BgD6ReportDesignTest}.
 *
 * <p><b>⚠️ КАКВО ДОКАЗВА И КАКВО НЕ.</b> Доказва, че отчетът е СЪГЛАСЕН СЪС САМИЯ СЕБЕ СИ: всяка
 * стойност, която образецът чете, има ред в набора данни, връзка и място, където се показва. НЕ
 * доказва, че PDF-ът излиза красив — това се вижда само с очи.
 *
 * <p><b>⚠️ И ЗАЩО НЕ РЕНДИРА.</b> Рендирането иска работещ BIRT engine И жива база (наборите данни
 * са JDBC заявки). Тест, който ги иска, се ПРОПУСКА на всяка машина без база — тоест твърдението му
 * би съществувало само на хартия. Че ИМЕНАТА на колоните съществуват, се сверява в {@code
 * BgReportQueryColumnTest}; че заявките се ИЗПЪЛНЯВАТ — в {@code BgReportQueryDbTest}. Че ЧИСЛАТА
 * са верни — в {@link BgPaySlipPrintSummaryTest}, върху златния фиш.
 */
class BgPaySlipReportDesignTest {

  private static final String DESIGN = "src/main/resources/reports/BgPaySlip.rptdesign";

  /** Колоните на реда с перата — псевдонимите, с които връзките на BIRT работят. */
  private static final List<String> LINE_COLUMNS =
      Arrays.asList(
          "line_number",
          "line_name",
          "sign",
          "quantity",
          "rate",
          "amount",
          "is_taxable",
          "is_insurance_income",
          "corrects_period");

  /** Заглавните стойности на фиша, четени от собствения му ред. */
  private static final List<String> SLIP_COLUMNS =
      Arrays.asList(
          "reference",
          "working_days",
          "worked_days",
          "base_salary",
          "gross_total",
          "insurance_income",
          "tax_base",
          "tax_amount",
          "net_total",
          "employer_cost",
          "paid_date");

  /** Параметрите, които услугата подава и образецът е длъжен да покаже. */
  private static final List<String> PARAMS =
      Arrays.asList(
          "PayerName",
          "PayerIdNumber",
          "EmployeeNames",
          "EmployeePersonalId",
          "JobTitle",
          "InsuredTypeCode",
          "PeriodLabel",
          "StatusLabel",
          "SlipKind",
          "EmployeeContributions",
          "EmployerContributions",
          "ReconciliationReport",
          "GenDate");

  private static String design() throws IOException {
    Path path = Paths.get("").toAbsolutePath().resolve(DESIGN);
    assertTrue(Files.isRegularFile(path), "липсва самият образец: " + path);
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("всяка колона на ПЕРОТО има ред, връзка и място за показване")
  void everyLineColumnIsWiredEndToEnd() throws IOException {
    String xml = design();
    List<String> problems = new ArrayList<>();

    for (String column : LINE_COLUMNS) {
      if (!xml.contains("<property name=\"nativeName\">" + column + "</property>")) {
        problems.add(column + ": няма ред в resultSet на набора „Lines“");
      }
      if (!xml.contains("dataSetRow[\"" + column + "\"]")) {
        problems.add(column + ": няма връзка (boundDataColumns) — стойността няма откъде да дойде");
      }
    }
    assertTrue(
        problems.isEmpty(),
        "⚠️ Вързана и непоказана стойност е най-тихият дефект в BIRT: отчетът се рендира БЕЗ грешка"
            + " и просто няма полето.\n  "
            + String.join("\n  ", problems));
  }

  @Test
  @DisplayName("всяка ЗАГЛАВНА сума има ред, връзка и място за показване")
  void everySlipColumnIsWiredEndToEnd() throws IOException {
    String xml = design();
    List<String> problems = new ArrayList<>();

    for (String column : SLIP_COLUMNS) {
      if (!xml.contains("<property name=\"nativeName\">" + column + "</property>")) {
        problems.add(column + ": няма ред в resultSet на набора „Slip“");
      }
      if (!xml.contains("dataSetRow[\"" + column + "\"]")) {
        problems.add(column + ": няма връзка (boundDataColumns)");
      }
      if (!xml.contains("<property name=\"resultSetColumn\">" + column + "</property>")) {
        problems.add(
            column + ": няма елемент, който да я ПОКАЗВА — стойността е вързана и невидима");
      }
    }
    assertTrue(problems.isEmpty(), String.join("\n  ", problems));
  }

  @Test
  @DisplayName("⚠️ всеки параметър, който услугата подава, СЕ ПОЛЗВА върху листа")
  void everyParameterIsDeclaredAndUsed() throws IOException {
    String xml = design();
    List<String> problems = new ArrayList<>();

    for (String param : PARAMS) {
      if (!xml.contains("<scalar-parameter name=\"" + param + "\"")) {
        problems.add(param + ": не е обявен като параметър");
      }
      if (!xml.contains("params[\"" + param + "\"]")) {
        problems.add(
            param + ": обявен е и НЕ се ползва — тоест услугата го смята, а листът го няма");
      }
    }
    assertTrue(
        problems.isEmpty(),
        "⚠️ Параметър без употреба не пада никъде: BIRT приема стойността и я изхвърля.\n  "
            + String.join("\n  ", problems));
  }

  @Test
  @DisplayName("⚠️ ДВА набора данни: фишът е ЕДИН ред, перата са МНОГО — и двата по ЕДИН фиш")
  void theTwoDataSetsReadOneSlipEach() throws IOException {
    String xml = design();

    assertTrue(
        xml.contains("from bglocalization_bg_pay_slip\nwhere id = ?"),
        "заглавната част е самият фиш, четен по идентификатор");
    assertTrue(
        xml.contains("from bglocalization_bg_pay_slip_line\nwhere pay_slip = ?"),
        "⚠️ ПО РОДИТЕЛ, а не по идентификатор на перо. Перата са колекция — това е разликата от Д6,"
            + " който е формуляр без дъщерни записи.");
    assertTrue(
        xml.contains("order by bg_line_number"),
        "⚠️ ПОДРЕДБАТА Е ЧАСТ ОТ ЧЕТИМОСТТА: групите трябва да излизат 1xx, 5xx, 6xx, 7xx, 8xx, 9xx,"
            + " за да се чете фишът отгоре надолу като фиш. Без order by редът е този на вмъкването"
            + " — тоест произволен.");
    assertTrue(
        xml.contains("<table "),
        "⚠️ ТАБЛИЦА, А НЕ ФОРМУЛЯР — точно обратното на Д6. Перата са колекция с неизвестен брой:"
            + " формуляр би поискал по едно място за всяко и би отпечатал само толкова, колкото са"
            + " били нарисувани.");
  }

  @Test
  @DisplayName("⚠️ ЕДИН ФИШ, А НЕ ВЕДОМОСТ — заявките не могат да върнат второ лице")
  void theDesignCannotPrintASecondPerson() throws IOException {
    String xml = design();

    assertTrue(
        xml.contains("<scalar-parameter name=\"BgPaySlipId\""),
        "входът е ЕДИН фиш, подаден по идентификатор");
    assertFalse(
        xml.contains("where period ") || xml.contains("where employment_contract "),
        "⚠️ Заявка по ПЕРИОД или по ДРУЖЕСТВО вместо по фиш прави ведомост от този отчет — а фишът е"
            + " документът НА ЛИЦЕТО и носи перата му поотделно. Ведомостта е друг документ и е"
            + " извън обхвата на този образец.");
    assertFalse(
        xml.contains("<group "),
        "⚠️ Групиране по лице значи МНОГО лица на един лист. Един фиш няма какво да групира.");
  }

  @Test
  @DisplayName("⚠️ листът НАЗОВАВА трите неща, които мълчаливо го правят неверен")
  void theThreeSilentTrapsAreOnThePaper() throws IOException {
    String xml = design();

    assertTrue(
        xml.contains("params[\"StatusLabel\"]"),
        "⚠️ СЪСТОЯНИЕТО. Разпечатка на ЧЕРНОВА изглежда точно като разпечатка на потвърден фиш, а"
            + " черновата се мени — листът от понеделник спори с екрана от вторник.");
    assertTrue(
        xml.contains("params[\"SlipKind\"]"),
        "⚠️ ВИДЪТ. Коригиращият и заличаващият фиш носят пълните верни числа за периода, но НЕ се"
            + " изплащат — без надпис лицето чете нето, което няма да получи.");
    assertTrue(
        xml.contains("params[\"ReconciliationReport\"]"),
        "⚠️ СВЕРКАТА. Бруто, нето и разходът за работодателя са ПО УСТРОЙСТВО сборове на перата;"
            + " разминаване значи, че перата са пипани след последното преизчисляване. Разпечатка"
            + " само на заглавните суми би скрила точно този случай.");
    assertTrue(
        xml.contains("за минал период"),
        "⚠️ И РЕТРО-РЕДЪТ СЕ МАРКИРА НА СВОЯ РЕД: перо с попълнен bgCorrectsPeriod носи пари за ДРУГ"
            + " месец. То влиза в нетото, но не и в осигурителния доход и данъка на този фиш —"
            + " немаркирано, то прави лист, на който числата не се обясняват.");
  }

  @Test
  @DisplayName("⚠️ вноските излизат на ДВЕ страни — лице и работодател, поотделно")
  void bothContributionSidesAreShown() throws IOException {
    String xml = design();

    assertTrue(
        xml.contains("params[\"EmployeeContributions\"]"),
        "удържаните от лицето вноски намаляват нетото му и трябва да се виждат");
    assertTrue(
        xml.contains("params[\"EmployerContributions\"]"),
        "⚠️ И ТЕЗИ НА РАБОТОДАТЕЛЯ, но ОТДЕЛНО и под собствено заглавие: те НЕ са доход и НЕ са"
            + " удръжка на лицето. Слети с личните, фишът показва удръжка, каквато не е правена.");
    assertTrue(
        xml.contains("НЕ е доход и НЕ е удръжка на лицето"),
        "⚠️ Разликата е НАПИСАНА, не само оформена: човек, който чете фиша си за пръв път, няма как"
            + " да я изведе от разположението на два реда.");
  }
}

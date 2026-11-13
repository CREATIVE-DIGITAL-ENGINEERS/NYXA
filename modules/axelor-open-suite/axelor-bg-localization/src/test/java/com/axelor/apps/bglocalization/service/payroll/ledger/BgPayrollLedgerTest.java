package com.axelor.apps.bglocalization.service.payroll.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgSelfInsuredRegistration;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.payroll.BgPaySlipPrintSummary;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ВЕДОМОСТТА — свидетелят за ТРИТЕ ѝ твърдения, без база, без BIRT и без Guice.
 *
 * <p><b>⚠️ КАКВО ТОЗИ ТЕСТ НЕ ПРОВЕРЯВА, ЗА ДА НЕ СЕ СЧЕТЕ ЗА ПРОВЕРЕНО.</b> Той НЕ проверява колко
 * е вноската на едно лице — това е работа на {@code BgPaySlipLinesGoldenTest}, върху фиш, сглобен
 * от {@code prepare()}, където числата идват от генераторите. Тук фишовете са построени НА РЪКА
 * нарочно и сбор на ръчно сложени пера, сверен срещу себе си, не доказва нищо. Затова редовото
 * твърдение по-долу е СТРУКТУРНО: „редът на ведомостта носи СЪЩИТЕ числа, които носи и печатният
 * фиш“ — проверимо без нито едно закована число, защото и двете минават през един източник.
 *
 * <p>Трите твърдения:
 *
 * <ol>
 *   <li><b>Разбиването е пълно и непресичащо се</b> — всеки фиш влиза в точно един разрез, и сборът
 *       на петте е целият период. Това е твърдението, зад което стои „филтърът не изпуска никого“.
 *   <li><b>Редът „Общо“ е сборът на колоната</b> — не приблизително, а по устройство.
 *   <li><b>Редът носи числата на фиша</b> — ведомостта е друг ЛИСТ, а не друга СМЕТКА.
 * </ol>
 */
class BgPayrollLedgerTest {

  // ── 1. разбиването ──────────────────────────────────────────────────────

  /**
   * ⚠️ ГЛАВНОТО ТВЪРДЕНИЕ НА ЦЕЛИЯ КЛОН. Ведомост, която пита базата „дай ми трудовите договори“,
   * не знае кого НЕ е видяла: лице без попълнен вид осигурен просто не се връща, а листът излиза
   * пълен и подписуем. Тук периодът се строи ЦЯЛ и разбиването се БРОИ — тоест „всички ли влязоха“
   * е събиране, а не доверие.
   */
  @Test
  @DisplayName("⚠️ РАЗБИВАНЕ · всеки фиш е в ТОЧНО ЕДИН разрез, и петте дават целия период")
  void theBreakdownIsCompleteAndDisjoint() {
    BgPayrollLedger ledger = BgPayrollLedger.of(theWholeZoo());

    Map<BgPayrollLedgerVariant, Integer> census = ledger.census();
    int summed = census.values().stream().mapToInt(Integer::intValue).sum();
    assertEquals(
        ledger.getRows().size(),
        summed,
        "⚠️ Разминаване тук значи, че някой фиш е бил преброен два пъти или нито веднъж — и в двата"
            + " случая ведомостта лъже за брой хора: "
            + census);

    // ⚠️ И ОБРАТНАТА ПОСОКА, ПОИМЕННО: всеки ред влиза в точно една от ПЕТТЕ ведомости на
    // разбиването. Само сборът на броячите не стига — две грешки в различни посоки се компенсират.
    List<BgPayrollLedgerSelection> partition =
        Arrays.asList(
            BgPayrollLedgerSelection.TD,
            BgPayrollLedgerSelection.GD,
            BgPayrollLedgerSelection.DUK,
            BgPayrollLedgerSelection.SOL,
            BgPayrollLedgerSelection.OTHER);

    for (BgPayrollLedgerRow row : ledger.getRows()) {
      long hits = partition.stream().filter(s -> s.accepts(row)).count();
      assertEquals(
          1,
          hits,
          "фиш „"
              + row.getReference()
              + "“ (вид осигурен „"
              + row.getInsuredTypeCode()
              + "“) влиза в "
              + hits
              + " от петте ведомости, а трябва в точно една");
    }

    int inPartition = partition.stream().mapToInt(selection -> ledger.rows(selection).size()).sum();
    assertEquals(
        ledger.getRows().size(),
        inPartition,
        "⚠️ Петте листа заедно са ЦЕЛИЯТ период. По-малко значи, че някой не се печата никъде.");
    assertEquals(
        ledger.getRows().size(),
        ledger.rows(BgPayrollLedgerSelection.ALL).size(),
        "„Всички“ е целият период, не подмножество");
  }

  /** ⚠️ Всеки от четирите разреза на образеца се познава по онова, което го ОПРЕДЕЛЯ. */
  @Test
  @DisplayName("⚠️ РАЗБИВАНЕ · носителят решава преди кода, а кодът — преди подразбирането")
  void eachVariantIsRecognisedByWhatDefinesIt() {
    assertEquals(
        BgPayrollLedgerVariant.EMPLOYMENT, BgPayrollLedgerVariant.of(employed("Тестов", "01")));
    assertEquals(BgPayrollLedgerVariant.CIVIL, BgPayrollLedgerVariant.of(employed("Тестов", "14")));
    assertEquals(
        BgPayrollLedgerVariant.MANAGEMENT, BgPayrollLedgerVariant.of(employed("Тестов", "10")));
    assertEquals(
        BgPayrollLedgerVariant.UNCLASSIFIED,
        BgPayrollLedgerVariant.of(employed("Тестов", null)),
        "⚠️ Празният вид осигурен НЕ Е трудов договор по подразбиране. Прочетен така, той влиза при"
            + " наетите мълчаливо — ведомост с ред, за който никой не е казал, че е трудов.");
    assertEquals(
        BgPayrollLedgerVariant.UNCLASSIFIED,
        BgPayrollLedgerVariant.of(employed("Тестов", "   ")),
        "празен низ и празна колона са едно и също за BgD1Carrier");
    assertEquals(
        BgPayrollLedgerVariant.UNCLASSIFIED,
        BgPayrollLedgerVariant.of(new BgPaySlip()),
        "фиш без нито един носител е „неопределен“, а не трудов договор");

    // ⚠️ НОСИТЕЛЯТ ПОБЕЖДАВА КОДА, И ТОВА Е ИЗМЕРЕНО, А НЕ ПОДРЕДБА ПО ВКУС: регистрация с код 14
    // върху себе си е самоосигуряващо се лице, а не граждански договор.
    assertEquals(
        BgPayrollLedgerVariant.SELF_INSURED,
        BgPayrollLedgerVariant.of(selfInsured("Солев", "14")),
        "⚠️ Обратният ред би пратил самоосигуряващо се лице на ведомостта на гражданските договори —"
            + " правдоподобен ред на грешния лист.");
  }

  /**
   * ⚠️ „АВАНСИ“ НЕ Е ШЕСТИ РАЗРЕЗ, А НАПРЕЧЕН СРЕЗ. Ако беше разрез, наетият с аванс щеше да ИЗЛЕЗЕ
   * от ведомостта на трудовите договори — тоест да липсва от листа, на който се сверява, точно
   * защото е получил аванс.
   */
  @Test
  @DisplayName("⚠️ АВАНСИ · режат напречно: същото лице е и на своята ведомост, и на тази")
  void theAdvanceSelectionCutsAcross() {
    BgPaySlip withAdvance = employed("Авансов", "01");
    add(withAdvance, 100, BgPaySlipResolver.CREDIT, "1000.00");
    add(withAdvance, BgPayrollLedgerRow.LINE_ADVANCE, BgPaySlipResolver.DEBIT, "400.00");

    BgPaySlip management = employed("Управителев", "10");
    add(management, 100, BgPaySlipResolver.CREDIT, "2000.00");
    add(management, BgPayrollLedgerRow.LINE_PARTIAL_PAYMENT, BgPaySlipResolver.DEBIT, "300.00");

    BgPaySlip plain = employed("Обикновенов", "01");
    add(plain, 100, BgPaySlipResolver.CREDIT, "1000.00");

    BgPayrollLedger ledger = BgPayrollLedger.of(Arrays.asList(withAdvance, management, plain));

    List<String> onAdvance = names(ledger.rows(BgPayrollLedgerSelection.ADVANCE));
    assertEquals(Arrays.asList("Авансов", "Управителев"), onAdvance);

    assertTrue(
        names(ledger.rows(BgPayrollLedgerSelection.TD)).contains("Авансов"),
        "⚠️ Лицето с аванс ОСТАВА на своята ведомост. Изчезне ли, месечната ведомост започва да губи"
            + " точно хората, чиито пари вече са частично изплатени.");
    assertTrue(names(ledger.rows(BgPayrollLedgerSelection.DUK)).contains("Управителев"));
  }

  /**
   * ⚠️ САМО 600 И 610. Перо 630 („предадено в натура“) е в същата група 6xx и също е DEBIT, но не е
   * стотинка в ръката на лицето: то е контра-перото на ваучера. Събрано с аванса, ведомостта би
   * показала „изплатено предварително“ число, което банката никога не е нареждала.
   */
  @Test
  @DisplayName("⚠️ АВАНС · натурата (630) НЕ е аванс, макар да е в същата група и със същия знак")
  void paymentInKindIsNotAnAdvance() {
    BgPaySlip slip = employed("Ваучеров", "01");
    add(slip, 100, BgPaySlipResolver.CREDIT, "1000.00");
    add(slip, BgPayrollLedgerRow.LINE_ADVANCE, BgPaySlipResolver.DEBIT, "200.00");
    add(slip, 630, BgPaySlipResolver.DEBIT, "60.00");

    BgPayrollLedgerRow row = BgPayrollLedgerRow.of(slip);
    assertEquals(
        0,
        new BigDecimal("200.00").compareTo(row.getAdvance()),
        "⚠️ 260,00 тук значи, че ваучерът е сметнат за изплатен аванс");

    // ⚠️ И РЕТРО-РЕДЪТ НЕ Е АВАНС НА ТОЗИ МЕСЕЦ: перо, маркирано за ДРУГ период, носи пари на друга
    // ведомост. Същият прочит като на BgPaySlipResolver#isOwnPeriod.
    BgPaySlipLine retro =
        add(slip, BgPayrollLedgerRow.LINE_ADVANCE, BgPaySlipResolver.DEBIT, "500.00");
    retro.setBgCorrectsPeriod(new com.axelor.apps.base.db.Period());
    assertEquals(
        0,
        new BigDecimal("200.00").compareTo(BgPayrollLedgerRow.of(slip).getAdvance()),
        "аванс с маркер за чужд период не е аванс на ТОЗИ лист");
  }

  // ── 2. редът „Общо“ ─────────────────────────────────────────────────────

  /**
   * ⚠️ САМОПРОВЕРКАТА, КОЯТО ЗАДАНИЕТО ИСКА. Редът „Общо“ е сборът на колоната — не приблизително,
   * а по устройство: и двете страни на равенството се смятат от ЕДИН И СЪЩ списък редове.
   */
  @Test
  @DisplayName("⚠️ ОБЩО · всяка сума на реда „Общо“ е сборът на своята колона, и за всеки избор")
  void everyTotalIsTheSumOfItsColumn() {
    BgPayrollLedger ledger = BgPayrollLedger.of(theWholeZoo());

    for (BgPayrollLedgerSelection selection : BgPayrollLedgerSelection.values()) {
      List<BgPayrollLedgerRow> rows = ledger.rows(selection);
      BgPayrollLedgerTotals totals = ledger.totals(selection);

      assertEquals(rows.size(), totals.getRowCount(), selection.name() + " · брой редове");
      assertEquals(
          0,
          sum(rows, BgPayrollLedgerRow::getGross).compareTo(totals.getGross()),
          selection.name() + " · начисления");
      assertEquals(
          0,
          sum(rows, BgPayrollLedgerRow::getEmployeeContributions)
              .compareTo(totals.getEmployeeContributions()),
          selection.name() + " · осигуровки на лицето");
      assertEquals(
          0,
          sum(rows, BgPayrollLedgerRow::getTax).compareTo(totals.getTax()),
          selection.name() + " · данък");
      assertEquals(
          0,
          sum(rows, BgPayrollLedgerRow::getAdvance).compareTo(totals.getAdvance()),
          selection.name() + " · аванс");
      assertEquals(
          0,
          sum(rows, BgPayrollLedgerRow::getDeductions).compareTo(totals.getDeductions()),
          selection.name() + " · удръжки");
      assertEquals(
          0,
          sum(rows, BgPayrollLedgerRow::getNet).compareTo(totals.getNet()),
          selection.name() + " · нето");
      assertEquals(
          0,
          sum(rows, BgPayrollLedgerRow::getEmployerContributions)
              .compareTo(totals.getEmployerContributions()),
          selection.name() + " · осигуровки на работодателя");
      assertEquals(
          0,
          sum(rows, BgPayrollLedgerRow::getEmployerCost).compareTo(totals.getEmployerCost()),
          selection.name() + " · разход за работодателя");
    }

    // ⚠️ И СБОРЪТ НА ПЕТТЕ ЛИСТА Е СБОРЪТ НА ЦЕЛИЯ ПЕРИОД — второто лице на разбиването, този път в
    // пари. Разминаване тук значи, че някой ред се брои на два листа или на нито един.
    BigDecimal acrossPartition =
        Arrays.asList(
                BgPayrollLedgerSelection.TD,
                BgPayrollLedgerSelection.GD,
                BgPayrollLedgerSelection.DUK,
                BgPayrollLedgerSelection.SOL,
                BgPayrollLedgerSelection.OTHER)
            .stream()
            .map(selection -> ledger.totals(selection).getNet())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    assertEquals(
        0,
        acrossPartition.compareTo(ledger.totals(BgPayrollLedgerSelection.ALL).getNet()),
        "сборът на нетата по петте ведомости = нетото на периода");
  }

  /** ⚠️ Празният период и празният разрез РЕНДИРАТ — нули, а не изключение и не липсващ лист. */
  @Test
  @DisplayName("⚠️ РЪБ · празен период и разрез без лица дават нули, не срив")
  void theEmptyPeriodIsZeroesNotACrash() {
    BgPayrollLedger empty = BgPayrollLedger.of(Collections.emptyList());
    assertEquals(0, empty.getRows().size());
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(empty.totals(BgPayrollLedgerSelection.TD).getNet()),
        "празната ведомост е нула, а не липсваща стойност");
    assertTrue(empty.boundaryReport(BgPayrollLedgerSelection.TD).contains("0 фиша общо"));

    BgPayrollLedger noCivil =
        BgPayrollLedger.of(Collections.singletonList(employed("Тестов", "01")));
    assertTrue(
        noCivil.rows(BgPayrollLedgerSelection.GD).isEmpty(),
        "месец без граждански договори е законно състояние");
    assertEquals(0, noCivil.totals(BgPayrollLedgerSelection.GD).getRowCount());

    assertEquals(0, BgPayrollLedger.of(null).getRows().size(), "липсващ списък не хвърля");
  }

  // ── 3. числата са числата на фиша ───────────────────────────────────────

  /**
   * ⚠️ НИТО ЕДНО ЗАКОВАНО ЧИСЛО — и точно затова твърдението важи. Проверява се, че редът чете
   * СЪЩИЯ източник като печатния фиш, а не че случайно съвпада с преписана стойност.
   */
  @Test
  @DisplayName("⚠️ ЧИСЛА · редът носи същите суми, които носи и печатният фиш на същото лице")
  void theRowCarriesTheSameNumbersAsThePrintedSlip() {
    BgPaySlip slip = employed("Сверков", "01");
    slip.setBgGrossTotal(new BigDecimal("1500.00"));
    slip.setBgTaxAmount(new BigDecimal("129.33"));
    slip.setBgNetTotal(new BigDecimal("1163.97"));
    slip.setBgEmployerCost(new BigDecimal("1785.30"));
    add(slip, 100, BgPaySlipResolver.CREDIT, "1500.00");
    add(slip, 501, BgPaySlipResolver.DEBIT, "206.70");
    add(slip, 500, BgPaySlipResolver.CREDIT, "285.30");
    add(slip, 700, BgPaySlipResolver.DEBIT, "129.33");

    BgPayrollLedgerRow row = BgPayrollLedgerRow.of(slip);
    BgPaySlipPrintSummary printed = BgPaySlipPrintSummary.of(slip);

    assertEquals(
        0,
        printed.getEmployeeContributions().compareTo(row.getEmployeeContributions()),
        "⚠️ Разминаване тук значи ДВА източника за едно число — фишът на лицето и ведомостта на"
            + " предприятието биха казвали различни неща за един и същи месец.");
    assertEquals(0, printed.getEmployerContributions().compareTo(row.getEmployerContributions()));
    assertEquals(0, slip.getBgGrossTotal().compareTo(row.getGross()));
    assertEquals(0, slip.getBgTaxAmount().compareTo(row.getTax()));
    assertEquals(0, slip.getBgNetTotal().compareTo(row.getNet()));
    assertEquals(0, slip.getBgEmployerCost().compareTo(row.getEmployerCost()));
    assertEquals(
        printed.report(), row.getReconciliation(), "и докладът за сверката е СЪЩИЯТ, дума по дума");
    assertFalse(
        row.hasReconciliationFindings(),
        "сверен фиш няма какво да каже: " + row.getReconciliation());
  }

  /**
   * ⚠️ УДРЪЖКИТЕ СЕ ИЗВЕЖДАТ ОТ ВЕЧЕ СЪЩЕСТВУВАЩО ПРАВИЛО, а не се смятат втори път. Групата 8xx
   * има точно едно определение и то живее там, където чл. 446 ГПК се прилага.
   */
  @Test
  @DisplayName("⚠️ УДРЪЖКИ · колоната е разликата между двете нета на BgPaySlipResolver")
  void theDeductionColumnReusesTheExistingRule() {
    BgPaySlip slip = employed("Запоров", "01");
    add(slip, 100, BgPaySlipResolver.CREDIT, "1000.00");
    add(slip, BgPaySlipResolver.LINE_GARNISHMENT, BgPaySlipResolver.DEBIT, "150.00");
    add(slip, BgPaySlipResolver.LINE_MAINTENANCE, BgPaySlipResolver.DEBIT, "80.00");
    add(slip, 700, BgPaySlipResolver.DEBIT, "70.00");

    List<BgPaySlipLine> lines = slip.getBgPaySlipLineList();
    assertEquals(
        0,
        BgPaySlipResolver.netBeforeSeizure(lines)
            .subtract(BgPaySlipResolver.netTotal(lines))
            .compareTo(BgPayrollLedgerRow.of(slip).getDeductions()));
    assertEquals(
        0,
        new BigDecimal("230.00").compareTo(BgPayrollLedgerRow.of(slip).getDeductions()),
        "⚠️ 300,00 тук значи, че данъкът (7xx) е броен за удръжка — той има своя колона");
  }

  /**
   * ⚠️ НЕСВЕРЕНИЯТ РЕД СЕ НАЗОВАВА НА ВЕДОМОСТТА, А НЕ САМО НА СВОЯ ФИШ. Ведомостта е листът, на
   * който се гледа цялото предприятие наведнъж — и е най-евтиното място да се види, че един фиш е
   * пипан след последното преизчисляване.
   */
  @Test
  @DisplayName("⚠️ ДОКЛАД · ред, чиито пера не се събират до сумите му, влиза ПОИМЕННО")
  void theUnreconciledRowIsNamedInTheReport() {
    BgPaySlip broken = employed("Разминатов", "01");
    broken.setBgGrossTotal(new BigDecimal("1200.00"));
    broken.setBgNetTotal(new BigDecimal("1200.00"));
    broken.setReference("FISH-2026-0099");
    add(broken, 100, BgPaySlipResolver.CREDIT, "1000.00");

    BgPayrollLedger ledger = BgPayrollLedger.of(Arrays.asList(broken, employed("Чистов", "01")));

    assertEquals(1, ledger.unreconciled().size());
    String report = ledger.boundaryReport(BgPayrollLedgerSelection.TD);
    assertTrue(report.contains("FISH-2026-0099"), "докладът НАЗОВАВА реда:\n" + report);
    assertTrue(report.contains("Разминатов"), report);
    assertTrue(report.contains("не се сверяват"), report);
  }

  /**
   * ⚠️ НУЛАТА ОТ ФИЛТЪР ИЗГЛЕЖДА ТОЧНО КАТО НУЛАТА ОТ ЛИПСА — и точно затова се пише с изречение.
   *
   * <p>Филтърът по дружество минава през договора, а самоосигуряващото се лице няма договор: с
   * избрано дружество разрезът „Самоосигуряващи се“ винаги показва нула. Прочетена като отговор
   * („такива лица нямаме“), тази нула е грешна — лицата ги има, просто не на този лист.
   */
  @Test
  @DisplayName("⚠️ ДОКЛАД · изрязаните от филтъра самоосигуряващи се се БРОЯТ, а не изчезват")
  void selfInsuredCutOffByTheCompanyFilterAreCounted() {
    List<BgPaySlip> inScope = Collections.singletonList(employed("Тестов", "01"));

    String silent = BgPayrollLedger.of(inScope).boundaryReport(BgPayrollLedgerSelection.TD);
    assertFalse(
        silent.contains("ИЗВЪН този обхват"),
        "без изрязване няма какво да се обяснява:\n" + silent);

    String spoken = BgPayrollLedger.of(inScope, 3).boundaryReport(BgPayrollLedgerSelection.TD);
    assertTrue(spoken.contains("3 фиша на самоосигуряващи се"), spoken);
    assertTrue(
        spoken.contains("няма договор"),
        "⚠️ Казва се и ЗАЩО, а не само колко: причината е устройствена и поправката е на ДРУГ екран"
            + " (полето „Дружество“), а не на този.\n"
            + spoken);
  }

  /**
   * ⚠️ ПРЕБРОЯВАНЕТО СТОИ И НА ЛИСТА, КОЙТО НЕ ГО ОБЯСНЯВА. Ведомостта на трудовите договори,
   * отпечатана сама, не казва нищо за гражданските договори, които не са на нея — а точно тя отива
   * за подпис.
   */
  @Test
  @DisplayName("⚠️ ДОКЛАД · листът казва и кого НЕ показва, включително неопределените")
  void theReportSaysWhoIsNotOnTheSheet() {
    BgPayrollLedger ledger = BgPayrollLedger.of(theWholeZoo());
    String report = ledger.boundaryReport(BgPayrollLedgerSelection.TD);

    assertTrue(report.contains("Граждански договори"), report);
    assertTrue(report.contains("Самоосигуряващи се"), report);
    assertTrue(
        report.contains("нямат попълнен вид осигурен"),
        "⚠️ Неопределените са единственият разрез, който човек не очаква — и точно затова за тях има"
            + " СВОЕ изречение, а не само брояч в редицата:\n"
            + report);
  }

  /**
   * ⚠️ РЕДЪТ НА ХАРТИЯТА СЕ РЕШАВА В JAVA, С БЪЛГАРСКИ Collator. В SQL той би зависел от колацията
   * на базата — свойство на машината, не на данните — и един и същ билд би давал различен ред на
   * две инсталации.
   */
  @Test
  @DisplayName("⚠️ РЕД · подрежда се по име на лицето, а не по номер на фиш")
  void rowsAreOrderedByName() {
    BgPaySlip last = employed("Янев", "01");
    last.setReference("FISH-0001");
    BgPaySlip first = employed("Ангелов", "01");
    first.setReference("FISH-0002");
    BgPaySlip middle = employed("Иванов", "01");
    middle.setReference("FISH-0003");

    List<String> ordered = names(BgPayrollLedger.of(Arrays.asList(last, first, middle)).getRows());
    assertEquals(
        Arrays.asList("Ангелов", "Иванов", "Янев"),
        ordered,
        "⚠️ Ред по номер на фиш тук значи, че подредбата е останала на базата — а тя я решава по"
            + " колация, тоест различно на различните машини.");
  }

  // ── помощници ───────────────────────────────────────────────────────────

  /**
   * ЕДИН ПЕРИОД С ПО ЕДИН ОТ ВСЕКИ ВИД — включително двата, които образецът не познава: фиш без
   * попълнен вид осигурен и фиш, чийто договор липсва.
   */
  private static List<BgPaySlip> theWholeZoo() {
    BgPaySlip employed = employed("Тестов", "01");
    add(employed, 100, BgPaySlipResolver.CREDIT, "2000.00");
    add(employed, 501, BgPaySlipResolver.DEBIT, "275.60");
    add(employed, 500, BgPaySlipResolver.CREDIT, "384.40");
    add(employed, 700, BgPaySlipResolver.DEBIT, "172.44");
    employed.setBgGrossTotal(new BigDecimal("2000.00"));
    employed.setBgTaxAmount(new BigDecimal("172.44"));
    employed.setBgNetTotal(new BigDecimal("1551.96"));
    employed.setBgEmployerCost(new BigDecimal("2384.40"));

    BgPaySlip civil = employed("Гражданов", "14");
    add(civil, 100, BgPaySlipResolver.CREDIT, "1500.00");
    civil.setBgGrossTotal(new BigDecimal("1500.00"));
    civil.setBgNetTotal(new BigDecimal("1500.00"));
    civil.setBgEmployerCost(new BigDecimal("1500.00"));

    BgPaySlip management = employed("Управителев", "10");
    add(management, 100, BgPaySlipResolver.CREDIT, "3000.00");
    add(management, BgPayrollLedgerRow.LINE_ADVANCE, BgPaySlipResolver.DEBIT, "1000.00");
    management.setBgGrossTotal(new BigDecimal("3000.00"));
    management.setBgNetTotal(new BigDecimal("2000.00"));
    management.setBgEmployerCost(new BigDecimal("3000.00"));

    BgPaySlip sol = selfInsured("Солев", "12");
    add(sol, 100, BgPaySlipResolver.CREDIT, "1000.00");
    sol.setBgGrossTotal(new BigDecimal("1000.00"));
    sol.setBgNetTotal(new BigDecimal("1000.00"));
    sol.setBgEmployerCost(new BigDecimal("1000.00"));

    BgPaySlip unclassified = employed("Безвидов", null);
    add(unclassified, 100, BgPaySlipResolver.CREDIT, "800.00");
    unclassified.setBgGrossTotal(new BigDecimal("800.00"));
    unclassified.setBgNetTotal(new BigDecimal("800.00"));
    unclassified.setBgEmployerCost(new BigDecimal("800.00"));

    BgPaySlip orphan = new BgPaySlip();
    orphan.setReference("FISH-БЕЗ-НОСИТЕЛ");
    orphan.setStatusSelect(BgPaySlipResolver.DRAFT);
    orphan.setBgPaySlipLineList(new ArrayList<>());

    return Arrays.asList(employed, civil, management, sol, unclassified, orphan);
  }

  private static BgPaySlip employed(String surname, String insuredTypeCode) {
    BgPaySlip slip = blank(surname);
    EmploymentContract contract = new EmploymentContract();
    contract.setEmployee(employee(surname));
    contract.setBgInsuredTypeCode(insuredTypeCode);
    slip.setEmploymentContract(contract);
    return slip;
  }

  private static BgPaySlip selfInsured(String surname, String insuredTypeCode) {
    BgPaySlip slip = blank(surname);
    BgSelfInsuredRegistration registration = new BgSelfInsuredRegistration();
    registration.setEmployee(employee(surname));
    registration.setBgInsuredTypeCode(insuredTypeCode);
    slip.setBgSelfInsuredRegistration(registration);
    return slip;
  }

  private static BgPaySlip blank(String surname) {
    BgPaySlip slip = new BgPaySlip();
    slip.setReference("FISH-" + surname);
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setBgPaySlipLineList(new ArrayList<>());
    return slip;
  }

  private static Employee employee(String surname) {
    Partner person = new Partner();
    person.setName(surname);
    Employee employee = new Employee();
    employee.setContactPartner(person);
    return employee;
  }

  private static BgPaySlipLine add(BgPaySlip slip, int number, String sign, String amount) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(number);
    line.setName("перо " + number);
    line.setBgSign(sign);
    line.setBgAmount(new BigDecimal(amount));
    slip.getBgPaySlipLineList().add(line);
    return line;
  }

  private static List<String> names(List<BgPayrollLedgerRow> rows) {
    return rows.stream().map(BgPayrollLedgerRow::getEmployeeNames).collect(Collectors.toList());
  }

  private static BigDecimal sum(
      List<BgPayrollLedgerRow> rows,
      java.util.function.Function<BgPayrollLedgerRow, BigDecimal> column) {
    return rows.stream().map(column).reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}

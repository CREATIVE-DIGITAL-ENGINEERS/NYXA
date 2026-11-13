package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgGarnishmentScale;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Деветте задължителни случая на фиша.
 *
 * <p>Без база и без Guice: правилата са в {@link BgPaySlipResolver}, а шевовете към базата се
 * подменят в наследник. Ако някога за тези тестове потрябва Hibernate, значи правило е изтекло от
 * резолвера надолу.
 *
 * <p><b>Първият тест е и проверката на границата на стъпка 4:</b> фиш само с РЪЧНО въведени пера
 * трябва да дава верни суми. Ако това спре да е вярно, свързаността е сбъркана.
 */
class BgPaySlipServiceTest {

  private static BigDecimal decimalOrNull(String value) {
    return value == null || value.trim().isEmpty() ? null : new BigDecimal(value.trim());
  }

  private static Integer integerOrNull(String value) {
    return value == null || value.trim().isEmpty() ? null : Integer.valueOf(value.trim());
  }

  // ── услугата без база ───────────────────────────────────────────────────

  private static class Service extends BgPaySlipService {
    BgWorkingDayService.Count calendar;
    BgContributionResult contributions;
    BgMinIncomeResult minIncome;
    String otherSlip;

    /**
     * ⚠️ Броено, не занулено. Генераторът на начисления пипа речника и каталога с параметри, тоест
     * без подмяна тези тестове биха искали Guice. Но подменен на тихо „нищо“, шевът може да се
     * РАЗКАЧИ от prepare() и нито един тест да не се обади — затова се брои и се проверява.
     */
    int earningsCalls;

    int sickLeaveCalls;
    int compensationCalls;
    int contributionCalls;
    int incomeTaxCalls;
    int annualSettlementCalls;

    /**
     * Колко пъти е минала стъпката по чл. 177, и колко пера е ВИДЯЛА — за реда спрямо начисленията.
     */
    int art177Calls;

    int art177SawLines;
    int settlementSawIncomeTaxCalls = -1;
    int earningsSawSickDays = -1;

    /**
     * ⚠️ КОЛКО ПЕРА Е ВИДЯЛ ГЕНЕРАТОРЪТ НА ОБЕЗЩЕТЕНИЯ. Само броячът не различава реда спрямо
     * начисленията: и при двете подредби той е 1. Числото, което обезщетенията ЧЕТАТ, го различава.
     */
    int compensationsSawLines = -1;

    /**
     * ⚠️ КАКЪВ ОСИГУРИТЕЛЕН ДОХОД Е ВИДЯЛ ГЕНЕРАТОРЪТ НА ОСИГУРОВКИ. Точно това число решава дали
     * редът е верен: вноските се смятат върху сбора на ПЕРАТА, тоест всяко начисление трябва вече
     * да е ред. Брояч сам по себе си е 1 при всяка подредба.
     */
    BigDecimal contributionsSawInsuranceIncome;

    /**
     * ⚠️ КАКВА ДАНЪЧНА ОСНОВА Е ВИДЯЛ ГЕНЕРАТОРЪТ НА ДОД. Тя е облагаемите начисления МИНУС личните
     * вноски, тоест числото пада, ЩОМ перата от 5xx DEBIT вече ги има. Точно това различава реда.
     */
    BigDecimal incomeTaxSawTaxBase;

    final List<BgCompensation> saved = new ArrayList<>();

    @Override
    protected BgWorkingDayService.Count periodWorkingDays(BgPaySlip slip) {
      return calendar;
    }

    @Override
    protected BgContributionResult contributions(BgPaySlip slip) {
      return contributions;
    }

    @Override
    protected BgMinIncomeResult minIncome(BgPaySlip slip) {
      return minIncome;
    }

    @Override
    protected String otherSlipCarrying(BgCompensation doc, BgPaySlip slip) {
      return otherSlip;
    }

    @Override
    protected BigDecimal incomeTaxRate(BgPaySlip slip) {
      return new BigDecimal("10.0000"); // INCOME_TAX_RATE от seed-а
    }

    // ── шевовете на пазача по чл. 446 ГПК ──────────────────────────────────
    //
    // ⚠️ ЗАЩО ЗАВАРЕНИТЕ ТЕСТОВЕ СЕ ПРОМЕНЯТ. confirm() получи пазач за несеквестируемия доход, а
    // той чете базата на ТРИ места: МРЗ за периода, скалата по чл. 446 и децата на служителя.
    // Фишът в „1б“ носи запор от 120,00, тоест пазачът наистина се задейства и без тези подмени
    // тестът пада с „Guice is not initialized“. Нов шев в confirm() винаги идва с промяна в
    // заварените тестове — това е цената му и тя се плаща тук, а не се крие с тих fallback.

    /** Видът на децата — по подразбиране „без деца“, тоест по-високият таван по чл. 446. */
    BgGarnishmentService.Status children = BgGarnishmentService.Status.WITHOUT_CHILDREN;

    @Override
    protected BgGarnishmentService.Status childrenStatus(BgPaySlip slip, LocalDate date) {
      return children;
    }

    /** МРЗ и данъчната ставка — ОТ SEED ФАЙЛА, не измислени числа. */
    @Override
    protected BigDecimal payrollParameter(String code, LocalDate date) {
      List<String> header = BgSeed.quotedHeader("bg_payroll_parameter.csv");
      for (String[] row : BgSeed.quotedRows("bg_payroll_parameter.csv")) {
        if (code.equals(BgSeed.col(header, row, "code"))) {
          return new BigDecimal(BgSeed.col(header, row, "paramValue").trim());
        }
      }
      return null;
    }

    /** Скалата по чл. 446 — ШЕСТТЕ реда ОТ SEED ФАЙЛА, с истинските си граници и дялове. */
    @Override
    protected List<BgGarnishmentScale> garnishmentScale(LocalDate date) {
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

    @Override
    protected void generateEarnings(BgPaySlip slip) {
      earningsCalls++;
      // ⚠️ ЗАПИСВА СЕ КОЛКО ДНИ БОЛНИЧЕН Е ВИДЯЛ ГЕНЕРАТОРЪТ. Само броячът не различава реда:
      // и при двете подредби той е 1. Числото, което начисленията ЧЕТАТ, го различава.
      earningsSawSickDays = slip.getBgSickDaysEmployer() + slip.getBgSickDaysDoo();
    }

    /**
     * Шевът на болничните — БРОИ СЕ и той, за да е проверим и редът спрямо начисленията.
     *
     * <p>⚠️ Тук не е празен нарочно: записва дни болничен на фиша точно както истинската услуга
     * прави, за да може {@code prepareRunsSickLeaveBeforeEarnings} да провери КОЙ Е ВИДЯЛ КАКВО.
     */
    @Override
    protected void generateSickLeave(BgPaySlip slip) {
      sickLeaveCalls++;
      slip.setBgSickDaysEmployer(2);
      slip.setBgSickDaysDoo(3);
    }

    /**
     * Шевът на обезщетенията — БРОИ СЕ и той, за да е проверим и редът спрямо начисленията.
     *
     * <p>⚠️ Записва колко пера вече стоят на фиша: така се вижда дали е минал СЛЕД начисленията.
     */
    /**
     * Шевът на възнаграждението за платен отпуск по чл. 177 — БРОИ СЕ и записва какво е ВИДЯЛ.
     *
     * <p>⚠️ Числото, а не само броячът: перото по чл. 177 чете карва като разлика между двата
     * изхода на енджина за начисления, тоест пуснато ПРЕДИ него, то би върнало пари, каквито перата
     * още не са свалили. Броячът е 1 и при двете подредби; видяното — не.
     */
    @Override
    protected void generateArt177Leave(BgPaySlip slip) {
      art177Calls++;
      art177SawLines = slip.getBgPaySlipLineList() == null ? 0 : slip.getBgPaySlipLineList().size();
    }

    @Override
    protected void generateCompensations(BgPaySlip slip) {
      compensationCalls++;
      compensationsSawLines =
          slip.getBgPaySlipLineList() == null ? 0 : slip.getBgPaySlipLineList().size();
    }

    /**
     * Шевът на осигуровките — БРОИ СЕ и той, и записва какво е ВИДЯЛ.
     *
     * <p>⚠️ Не вика {@code contributions(slip)}: тук се мери РЕДЪТ, а не веригата.
     */
    /**
     * ⚠️ Свидетелите на РЕДА искат перото 501 по-долу; тестовете на пазачите при потвърждаване
     * минават през calculate() и го изключват, за да не мени то данъка, който те сверяват.
     */
    boolean writesContributionLine = true;

    @Override
    protected void generateContributions(BgPaySlip slip) {
      contributionCalls++;
      contributionsSawInsuranceIncome =
          BgPaySlipResolver.insuranceIncomeBase(slip.getBgPaySlipLineList());
      if (!writesContributionLine) {
        return;
      }
      // ⚠️ Записва лична вноска ТОЧНО както истинският генератор прави: без нея редът спрямо данъка
      // не е измерим, защото данъчната основа не би се променила между двете подредби.
      addLine(
          slip,
          lineFrom(type(501, "Пенсии — лице", "DEBIT", false, false), new BigDecimal("100.00")));
    }

    /**
     * Шевът на данъка — БРОИ СЕ и той, и записва основата, която е ВИДЯЛ.
     *
     * <p>⚠️ Записва и едно перо от 5xx DEBIT, точно както истинският генератор на осигуровки прави,
     * за да може свидетелят на реда да провери КОЙ Е ВИДЯЛ КАКВО.
     */
    @Override
    protected void generateIncomeTax(BgPaySlip slip) {
      incomeTaxCalls++;
      incomeTaxSawTaxBase = BgPaySlipResolver.taxBase(slip.getBgPaySlipLineList());
    }

    /**
     * Шевът на годишното изравняване по чл. 49 — БРОИ СЕ и записва КОЛКО пъти данъкът е минал преди
     * него.
     *
     * <p>⚠️ Само броячът не различава реда: и при двете подредби той е 1. Числото, което тази
     * стъпка ВИЖДА, го различава — точно същият идиом като при начисленията и болничните.
     */
    @Override
    protected void generateAnnualSettlement(BgPaySlip slip) {
      annualSettlementCalls++;
      settlementSawIncomeTaxCalls = incomeTaxCalls;
    }

    @Override
    protected void save(BgPaySlip slip) {}

    @Override
    protected void saveCompensation(BgCompensation doc) {
      saved.add(doc);
    }
  }

  private static Service service() {
    return new Service();
  }

  // ── фикстури ────────────────────────────────────────────────────────────

  private static BgPaySlipLineType type(
      int number, String name, String sign, boolean taxable, boolean insurable) {
    BgPaySlipLineType t = new BgPaySlipLineType();
    t.setNumber(number);
    t.setName(name);
    t.setBgSign(sign);
    t.setBgIsTaxable(taxable);
    t.setBgIsInsuranceIncome(insurable);
    t.setBgIsPermanentCharacter(number == 100);
    return t;
  }

  /**
   * Фиш върху период от ЗАПЛАТНА година.
   *
   * <p>⚠️ Годината е задължителна част от фикстурата: потвърждаването проверява, че периодът
   * принадлежи на година с typeSelect = 2. Фикстура с период без година би скрила липсата на тази
   * проверка.
   */
  private static BgPaySlip slip(LocalDate from, LocalDate to) {
    BgPaySlip s = new BgPaySlip();
    s.setStatusSelect(BgPaySlipResolver.DRAFT);
    Year year = new Year();
    year.setName("Заплатна " + from.getYear());
    year.setTypeSelect(2);
    Period p = new Period();
    p.setName(String.format("%02d", from.getMonthValue()) + "/PY" + from.getYear());
    p.setFromDate(from);
    p.setToDate(to);
    p.setYear(year);
    s.setPeriod(p);
    s.setBgPaySlipLineList(new ArrayList<>());
    return s;
  }

  /**
   * Добавя перото за данъка според заглавната сума.
   *
   * <p>⚠️ Нужно е на ВСЕКИ тест, който потвърждава: заглавният данък и перата от 7xx трябва да
   * съвпадат. Без него трите теста по-долу падат — те биха потвърждавали фишове, показващи данък,
   * който не е удържан, и точно това проверката спира.
   */
  private static void addTaxLine(Service s, BgPaySlip slip) {
    s.recompute(slip);
    s.addLine(
        slip,
        s.lineFrom(
            type(700, "Данък по чл. 42 ЗДДФЛ", "DEBIT", false, false), slip.getBgTaxAmount()));
    s.recompute(slip);
  }

  /** Фиш само с ръчни пера: заплата, добавка и една удръжка. */
  private static BgPaySlip manualSlip(Service s) {
    BgPaySlip slip = slip(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
    s.addLine(
        slip,
        s.lineFrom(
            type(100, "Основно възнаграждение", "CREDIT", true, true), new BigDecimal("1500.00")));
    s.addLine(
        slip,
        s.lineFrom(
            type(110, "Допълнително за стаж", "CREDIT", true, true), new BigDecimal("90.00")));
    s.addLine(
        slip, s.lineFrom(type(800, "Запор", "DEBIT", false, false), new BigDecimal("120.00")));
    return slip;
  }

  // ── 1 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("1 · фиш само с РЪЧНИ пера дава верни суми, а DEBIT ред намалява нетото")
  void aManuallyEnteredPaySlipAddsUp() {
    Service s = service();
    BgPaySlip slip = manualSlip(s);
    s.recompute(slip);

    assertEquals(new BigDecimal("1590.00"), slip.getBgGrossTotal(), "1500 + 90");
    assertEquals(new BigDecimal("1590.00"), slip.getBgInsuranceIncome());
    assertEquals(new BigDecimal("1590.00"), slip.getBgTaxBase(), "няма осигуровки на лицето още");
    assertEquals(new BigDecimal("159.00"), slip.getBgTaxAmount(), "10 на сто");
    assertEquals(new BigDecimal("1470.00"), slip.getBgNetTotal(), "1590 − 120 запор");

    // и знакът наистина работи: без удръжката нетото е с точно 120 повече
    slip.getBgPaySlipLineList().removeIf(l -> l.getBgLineNumber() == 800);
    s.recompute(slip);
    assertEquals(new BigDecimal("1590.00"), slip.getBgNetTotal());
  }

  @Test
  @DisplayName("1б · ДАНЪКЪТ трябва да е и перо: без ред 7xx потвърждаването се блокира")
  void theHeaderTaxMustAlsoBeALine() throws Exception {
    Service s = service();
    s.writesContributionLine = false;
    BgPaySlip slip = manualSlip(s);
    s.calculate(slip);

    // ⚠️ Заглавната част казва 159,00, а нетото е 1470,00 — данъкът НЕ е удържан. Всяко от двете
    // числа е вярно за себе си, а фишът е грешен. Единствената заглавна сума, която не идва от
    // перата, е точно данъкът, и затова само тя може да се размине с тях.
    assertEquals(new BigDecimal("159.00"), slip.getBgTaxAmount());
    assertEquals(new BigDecimal("1470.00"), slip.getBgNetTotal());
    assertEquals(
        BigDecimal.ZERO.setScale(2), BgPaySlipResolver.taxFromLines(slip.getBgPaySlipLineList()));

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(slip));
    assertTrue(e.getMessage().contains("159.00"), e.getMessage());
    assertTrue(e.getMessage().contains("0.00"), e.getMessage());

    // С перото за данъка всичко се връзва: нетото пада точно с данъка.
    s.addLine(
        slip,
        s.lineFrom(
            type(700, "Данък по чл. 42 ЗДДФЛ", "DEBIT", false, false), new BigDecimal("159.00")));
    s.calculate(slip);

    assertEquals(new BigDecimal("159.00"), slip.getBgTaxAmount());
    assertEquals(new BigDecimal("1311.00"), slip.getBgNetTotal(), "1590 − 120 запор − 159 данък");
    s.confirm(slip);
    assertEquals(BgPaySlipResolver.CONFIRMED, slip.getStatusSelect());
  }

  @Test
  @DisplayName("1в · петте други заглавни суми идват ОТ ПЕРАТА и не могат да им противоречат")
  void everyOtherHeaderTotalIsASumOfLines() {
    Service s = service();
    BgPaySlip slip = manualSlip(s);
    s.recompute(slip);
    List<BgPaySlipLine> lines = slip.getBgPaySlipLineList();

    assertEquals(BgPaySlipResolver.grossTotal(lines), slip.getBgGrossTotal());
    assertEquals(BgPaySlipResolver.insuranceIncomeBase(lines), slip.getBgInsuranceIncome());
    assertEquals(BgPaySlipResolver.taxBase(lines), slip.getBgTaxBase());
    assertEquals(BgPaySlipResolver.netTotal(lines), slip.getBgNetTotal());
    assertEquals(BgPaySlipResolver.employerCost(lines), slip.getBgEmployerCost());

    // Добавяне на перо мени всяка от тях — тоест наистина се четат от перата, а не се пазят
    // отделно.
    s.addLine(slip, s.lineFrom(type(180, "Бонус", "CREDIT", true, true), new BigDecimal("200.00")));
    s.recompute(slip);
    assertEquals(new BigDecimal("1790.00"), slip.getBgGrossTotal());
    assertEquals(new BigDecimal("1790.00"), slip.getBgInsuranceIncome());
    assertEquals(new BigDecimal("1670.00"), slip.getBgNetTotal());
  }

  // ── 2 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("2 · ЗАМРАЗЯВАНЕ: смяна на речника след потвърждаване не мърда фиша")
  void confirmingFreezesTheLines() throws Exception {
    Service s = service();
    s.writesContributionLine = false;
    BgPaySlipLineType salary = type(100, "Основно възнаграждение", "CREDIT", true, true);
    BgPaySlip slip = slip(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
    s.addLine(slip, s.lineFrom(salary, new BigDecimal("1500.00")));
    addTaxLine(s, slip);
    s.calculate(slip);
    s.confirm(slip);

    assertEquals(BgPaySlipResolver.CONFIRMED, slip.getStatusSelect());

    // речникът се променя СЛЕД потвърждаването
    salary.setName("Друго име");
    salary.setBgSign("DEBIT");
    salary.setBgIsTaxable(false);
    salary.setBgIsInsuranceIncome(false);
    s.recompute(slip);

    BgPaySlipLine line = slip.getBgPaySlipLineList().get(0);
    assertEquals("Основно възнаграждение", line.getName(), "името е копие, не препратка");
    assertEquals("CREDIT", line.getBgSign());
    assertTrue(line.getBgIsTaxable());
    assertTrue(line.getBgIsInsuranceIncome());
    assertEquals(new BigDecimal("1500.00"), slip.getBgGrossTotal(), "сумите не се преизчисляват");
  }

  // ── 3 ───────────────────────────────────────────────────────────────────

  private static BgCompensation compensation(String reference, String amount) {
    BgCompensation c = new BgCompensation();
    c.setReference(reference);
    c.setStatusSelect(BgCompensationResolver.CONFIRMED);
    c.setBgFinalAmount(new BigDecimal(amount));
    c.setBgIsTaxable(true);
    c.setBgSocialContributionsDue(false);
    return c;
  }

  @Test
  @DisplayName("3 · потвърдено обезщетение става ред 9xx и след плащане документът е PAID")
  void aConfirmedCompensationBecomesALineAndIsThenPaid() throws Exception {
    Service s = service();
    s.writesContributionLine = false;
    BgPaySlip slip = manualSlip(s);

    BgCompensation doc = compensation("OBEZ-2026-0001", "1450.00");
    BgPaySlipLine line =
        s.lineFrom(
            type(900, "Обезщетение за оставане без работа", "CREDIT", true, false),
            doc.getBgFinalAmount());
    line.setBgCompensation(doc);
    s.addLine(slip, line);
    addTaxLine(s, slip);

    assertEquals(new BigDecimal("3040.00"), slip.getBgGrossTotal(), "1590 + 1450 обезщетение");
    assertEquals(
        new BigDecimal("1590.00"), slip.getBgInsuranceIncome(), "обезщетението не е осиг. доход");

    s.calculate(slip);
    s.confirm(slip);
    slip.setBgPaidDate(LocalDate.of(2026, 4, 10));
    s.pay(slip);

    assertEquals(BgPaySlipResolver.PAID, slip.getStatusSelect());
    assertEquals(BgCompensationResolver.PAID, doc.getStatusSelect(), "документът минава в PAID");
    assertEquals(LocalDate.of(2026, 4, 10), doc.getBgPaidDate(), "с датата на фиша");
    assertTrue(s.saved.contains(doc), "и е записан");
  }

  // ── 4 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("4 · същото обезщетение на втори фиш се отказва, съобщението сочи първия")
  void thesameCompensationCannotBePaidTwice() {
    Service s = service();
    s.otherSlip = "FISH-2026-0007"; // вече го носи друг потвърден фиш

    BgPaySlip slip = manualSlip(s);
    BgCompensation doc = compensation("OBEZ-2026-0001", "1450.00");
    doc.setId(42L);
    BgPaySlipLine line =
        s.lineFrom(type(900, "Обезщетение", "CREDIT", true, false), doc.getBgFinalAmount());
    line.setBgCompensation(doc);
    s.addLine(slip, line);
    s.recompute(slip);

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(slip));
    assertTrue(e.getMessage().contains("FISH-2026-0007"), e.getMessage());
    assertTrue(e.getMessage().contains("OBEZ-2026-0001"), e.getMessage());
    assertEquals(BgPaySlipResolver.DRAFT, slip.getStatusSelect(), "остава чернова");
  }

  // ── 5 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("5 · задействан предпазител на осигуровките блокира потвърждаването")
  void aTrippedContributionGuardBlocksConfirmation() {
    for (BgContributionResult.Status status :
        new BgContributionResult.Status[] {
          BgContributionResult.Status.DUPLICATE_FUND_ROWS,
          BgContributionResult.Status.MISSING_MANDATORY_FUND,
          BgContributionResult.Status.NO_CONTRIBUTIONS_FOUND
        }) {
      Service s = service();
      s.contributions = BgContributionResult.failure(status, "подробност");
      BgPaySlip slip = manualSlip(s);
      s.recompute(slip);

      AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(slip));
      assertTrue(e.getMessage().contains(status.name()), status + " → " + e.getMessage());
    }

    // RESOLVED_INCOMPLETE също спира: липсващите пет пункта на УПФ са тихи, ако мине.
    Service s = service();
    s.contributions =
        BgContributionResult.resolvedIncomplete(Collections.emptyList(), "липсва УПФ ред");
    BgPaySlip slip = manualSlip(s);
    s.recompute(slip);
    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(slip));
    assertTrue(e.getMessage().contains("RESOLVED_INCOMPLETE"), e.getMessage());

    // а чистото RESOLVED минава
    Service ok = service();
    ok.contributions = BgContributionResult.resolved(Collections.emptyList());
    BgPaySlip good = manualSlip(ok);
    ok.recompute(good);
    assertNull(ok.contributionRefusal(good));
  }

  // ── 6 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("6 · период в непокрита календарна година блокира, ръчните дни отпушват")
  void anUncoveredCalendarYearBlocksConfirmation() throws Exception {
    Service s = service();
    s.writesContributionLine = false;
    // Календарът от seed-а покрива 2026 и 2027, но не и 2028 — без база, за да е тестът чист.
    BgWorkingDayService calendar =
        new BgWorkingDayService() {
          @Override
          public java.util.Set<Integer> coveredYears() {
            return new java.util.HashSet<>(java.util.Arrays.asList(2026, 2027));
          }

          @Override
          protected java.util.Set<LocalDate> holidaysIn(LocalDate from, LocalDate to) {
            return Collections.emptySet();
          }
        };
    s.calendar = calendar.count(LocalDate.of(2028, 3, 1), 31);
    BgPaySlip slip = slip(LocalDate.of(2028, 3, 1), LocalDate.of(2028, 3, 31));
    s.addLine(
        slip, s.lineFrom(type(100, "Заплата", "CREDIT", true, true), new BigDecimal("1000.00")));
    s.prepare(slip);
    addTaxLine(s, slip);

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(slip));
    assertTrue(e.getMessage().contains("2028"), e.getMessage());
    assertTrue(e.getMessage().contains("bg_eventsPlanningLine.csv"), e.getMessage());

    // ръчно надписаните дни са нечие решение и минават
    slip.setBgWorkingDaysInPeriod(20);
    s.calculate(slip);
    s.confirm(slip);
    assertEquals(BgPaySlipResolver.CONFIRMED, slip.getStatusSelect());
  }

  // ── 7 ───────────────────────────────────────────────────────────────────

  private static final Path MOD_ROWS =
      Paths.get("src/main/resources/apps/init-data/en/bg_min_insurance_income.csv");
  private static final Path MOD_AMOUNTS =
      Paths.get("src/main/resources/apps/init-data/en/bg_min_insurance_income_amount.csv");

  /** МОД за ред и квалификационна група, за периода, в който попада датата — от самия seed. */
  private static BigDecimal modFor(String row, int group, LocalDate date) throws IOException {
    String parent = null;
    for (String line : Files.readAllLines(MOD_ROWS, StandardCharsets.UTF_8)) {
      if (line.startsWith("importId")) {
        continue;
      }
      String[] f = line.split(";");
      String[] tail =
          line.substring(line.lastIndexOf(';', line.lastIndexOf(';') - 1) + 1).split(";", -1);
      if (!f[1].equals(row)) {
        continue;
      }
      LocalDate from = LocalDate.parse(tail[0]);
      LocalDate to = tail.length > 1 && !tail[1].isEmpty() ? LocalDate.parse(tail[1]) : null;
      if (!date.isBefore(from) && (to == null || !date.isAfter(to))) {
        parent = f[0];
      }
    }
    assertNotNull(parent, "няма МОД ред " + row + " за " + date);
    for (String line : Files.readAllLines(MOD_AMOUNTS, StandardCharsets.UTF_8)) {
      String[] f = line.split(";");
      if (f.length == 4 && f[1].equals(parent) && f[2].equals(String.valueOf(group))) {
        return new BigDecimal(f[3]);
      }
    }
    return null;
  }

  @Test
  @DisplayName("7 · МАРТ 2026 ползва приложение № 1, АВГУСТ 2026 — № 1А, и числата се различават")
  void theMinimumInsuranceIncomeDependsOnWhichHalfOfTheYear() throws Exception {
    // Ред 58 (търговия), квалификационна група 1 — избран, защото се различава ясно между двете.
    BigDecimal march = modFor("58", 1, LocalDate.of(2026, 3, 15));
    BigDecimal august = modFor("58", 1, LocalDate.of(2026, 8, 15));

    assertEquals(new BigDecimal("664.17"), march, "приложение № 1, до 31.07.2026");
    assertEquals(new BigDecimal("1129.00"), august, "приложение № 1А, от 01.08.2026");
    assertNotEquals(march, august, "ако двете съвпадат, полугодието не се различава");

    // и прагът на плоското приложение № 1 срещу стълбицата на № 1А
    assertEquals(new BigDecimal("550.66"), modFor("58", 5, LocalDate.of(2026, 3, 15)));
    assertEquals(new BigDecimal("620.20"), modFor("58", 5, LocalDate.of(2026, 8, 15)));

    // липсващият МОД блокира потвърждаването и казва, че липсва — не че е нула
    Service s = service();
    s.minIncome = BgMinIncomeResult.failure(BgMinIncomeResult.Status.MOD_ROW_NOT_FOUND, "ред 58");
    BgPaySlip slip = manualSlip(s);
    s.recompute(slip);
    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(slip));
    assertTrue(e.getMessage().contains("MOD_ROW_NOT_FOUND"), e.getMessage());
    assertTrue(e.getMessage().contains("НЕ значи нула"), e.getMessage());
  }

  // ── 8 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("8 · CONFIRMED → DRAFT се отказва")
  void thereIsNoWayBackFromConfirmed() {
    String refusal =
        BgPaySlipResolver.refuseTransition(BgPaySlipResolver.CONFIRMED, BgPaySlipResolver.DRAFT);
    assertNotNull(refusal);
    assertTrue(refusal.contains("откажете го и направете нов"), refusal);
    assertNull(
        BgPaySlipResolver.refuseTransition(
            BgPaySlipResolver.CONFIRMED, BgPaySlipResolver.CANCELLED));
    assertNotNull(
        BgPaySlipResolver.refuseTransition(BgPaySlipResolver.CANCELLED, BgPaySlipResolver.DRAFT));
  }

  // ── 9 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("9 · перата излизат по номер: 1xx, 5xx, 6xx, 7xx, 8xx, 9xx")
  void linesComeOutInNumberOrder() {
    Service s = service();
    BgPaySlip slip = slip(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
    // нарочно разбъркани при въвеждане
    s.addLine(slip, s.lineFrom(type(900, "Обезщетение", "CREDIT", true, false), BigDecimal.ONE));
    s.addLine(slip, s.lineFrom(type(700, "Данък", "DEBIT", false, false), BigDecimal.ONE));
    s.addLine(slip, s.lineFrom(type(100, "Заплата", "CREDIT", true, true), BigDecimal.ONE));
    s.addLine(slip, s.lineFrom(type(801, "Запор", "DEBIT", false, false), BigDecimal.ONE));
    s.addLine(slip, s.lineFrom(type(501, "Пенсии — лице", "DEBIT", false, false), BigDecimal.ONE));
    s.addLine(slip, s.lineFrom(type(600, "Аванс", "DEBIT", false, false), BigDecimal.ONE));

    List<Integer> numbers =
        BgPaySlipResolver.ordered(slip.getBgPaySlipLineList()).stream()
            .map(BgPaySlipLine::getBgLineNumber)
            .collect(Collectors.toList());

    assertEquals(java.util.Arrays.asList(100, 501, 600, 700, 801, 900), numbers);
    assertEquals(1, BgPaySlipResolver.groupOf(slip.getBgPaySlipLineList().get(2)), "100 → група 1");
    assertEquals(5, BgPaySlipResolver.groupOf(slip.getBgPaySlipLineList().get(4)), "501 → група 5");
    assertEquals(9, BgPaySlipResolver.groupOf(slip.getBgPaySlipLineList().get(0)), "900 → група 9");
  }

  // ── инвариантът от § 5.3 ────────────────────────────────────────────────

  @Test
  @DisplayName("§ 5.3 · сборът на перата дава точно нетото, без разминаване от закръгляне")
  void theLinesAddUpToTheNetExactly() {
    Service s = service();
    BgPaySlip slip = slip(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
    // суми, които се закръглят поотделно
    s.addLine(
        slip, s.lineFrom(type(100, "Заплата", "CREDIT", true, true), new BigDecimal("1333.33")));
    s.addLine(slip, s.lineFrom(type(110, "Стаж", "CREDIT", true, true), new BigDecimal("79.99")));
    s.addLine(
        slip,
        s.lineFrom(type(501, "Пенсии — лице", "DEBIT", false, false), new BigDecimal("113.03")));
    s.addLine(
        slip, s.lineFrom(type(700, "Данък", "DEBIT", false, false), new BigDecimal("130.03")));
    s.recompute(slip);

    BigDecimal byHand =
        new BigDecimal("1333.33")
            .add(new BigDecimal("79.99"))
            .subtract(new BigDecimal("113.03"))
            .subtract(new BigDecimal("130.03"));
    assertEquals(byHand, slip.getBgNetTotal(), "сборът на перата е нетото, знак по знак");
  }

  // ── 8 · шевът на начисленията ────────────────────────────────────────────

  /**
   * ⚠️ ЧЕ {@code prepare()} НАИСТИНА ВИКА ГЕНЕРАТОРА — иначе енджинът за начисления е написан и не
   * се изпълнява нито веднъж в производство. Цената на инертен шев (виж {@code
   * BgPaySlipInertGuardsTest}) е зелен билд над непроверена верига.
   *
   * <p>FAIL-FIRST: извикването на {@code generateEarnings} беше махнато от {@code prepare()} → 0 ≠
   * 1, тестът падна.
   */
  @Test
  @DisplayName("⚠️ prepare() вика генератора на начисления — и то СЛЕД замразяването на входовете")
  void prepareCallsTheEarningsGenerator() {
    Service s = new Service();
    s.calendar = new BgWorkingDayService.Count(21, Collections.emptyList());
    BgPaySlip slip = slip(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

    s.prepare(slip);

    assertEquals(
        1, s.earningsCalls, "генераторът не е извикан — енджинът за начисления е РАЗКАЧЕН");
    assertEquals(
        21, slip.getBgWorkingDaysInPeriod(), "нормата е замразена ПРЕДИ генератора да я чете");
    assertEquals(21, slip.getBgWorkedDays());
  }

  /**
   * ⚠️ РЕДЪТ: БОЛНИЧНИТЕ ПРЕДИ НАЧИСЛЕНИЯТА, И ТОВА НЕ Е ПОДРЕДБА, А ПРАВИЛО.
   *
   * <p>Енджинът за болнични НАМАЛЯВА отработените дни с дните болничен, а начисленията четат точно
   * отработените дни от фиша. Разменени, лице болно целия месец получава ПЪЛНА заплата И болничен
   * за същите дни — двойно плащане, което изглежда точно като верен фиш.
   *
   * <p>⚠️ Само броячите НЕ пазят реда: и при двете подредби всеки е 1. Затова тестът пита какво
   * начисленията са ВИДЕЛИ — дните болничен, записани от предходната стъпка.
   *
   * <p>FAIL-FIRST: двете извиквания бяха разменени в {@code prepare()} → начисленията виждат 0 дни
   * вместо 5, тестът падна.
   */
  @Test
  @DisplayName("⚠️ prepare() вика болничните ПРЕДИ начисленията — те четат намалените дни")
  void prepareRunsSickLeaveBeforeEarnings() {
    Service s = new Service();
    s.calendar = new BgWorkingDayService.Count(21, Collections.emptyList());
    BgPaySlip slip = slip(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));

    s.prepare(slip);

    assertEquals(1, s.sickLeaveCalls, "шевът на болничните не е извикан — енджинът е РАЗКАЧЕН");
    assertEquals(
        5,
        s.earningsSawSickDays,
        "⚠️ Начисленията ТРЯБВА да видят вече записаните дни болничен. Нула значи, че болничните са"
            + " минали СЛЕД тях — тогава основната заплата остава сметната за цял месец и лицето"
            + " получава и заплата, и болничен за същите дни.");
  }

  /**
   * ⚠️ ОБЕЗЩЕТЕНИЯТА — СЛЕД НАЧИСЛЕНИЯТА, И РЕДЪТ Е ПРАВИЛО, НЕ ПОДРЕДБА.
   *
   * <p>Четири от двайсет и петте обезщетения СА осигурителен доход (чл. 222 ал. 1, чл. 225 ал. 1
   * КТ, чл. 106 ал. 2 ЗДСл, чл. 58 ал. 3 ЗВО). Осигуровките и данъкът по-долу сумират ПЕРАТА на
   * фиша, тоест обезщетението трябва вече да е перо, когато те тръгнат. Пуснато след тях, то остава
   * неосигурено и необложено — а фишът изглежда напълно нормален.
   *
   * <p>⚠️ Само броячът НЕ пази реда: и при двете подредби е 1. Затова тестът пита какво генераторът
   * е ВИДЯЛ — перата, записани от предходните стъпки.
   *
   * <p>FAIL-FIRST: извикването беше махнато от {@code prepare()} → 0 ≠ 1, тестът падна. После двете
   * извиквания бяха разменени → обезщетенията виждат 0 пера вместо 1, тестът падна пак.
   */
  /**
   * ВЪЗНАГРАЖДЕНИЕТО ПО ЧЛ. 177 МИНАВА СЛЕД НАЧИСЛЕНИЯТА И ПРЕДИ ОБЕЗЩЕТЕНИЯТА.
   *
   * <p>Сумата на перото е „карв + надбавка“, а карвът е РАЗЛИКАТА между двата изхода на енджина за
   * начисления — със и без дните отпуск. Пуснато преди него, перото би върнало пари, каквито перата
   * още не са свалили: отпускът щеше да се плати ДВА пъти.
   *
   * <p>⚠️ И преди обезщетенията и вноските: възнаграждението за платен отпуск Е осигурителен доход
   * и Е облагаемо (НСОРЗ чл. 16 ал. 2 т. 7 го брои и в брутната работна заплата), тоест долните
   * стъпки трябва да го видят вече като перо.
   *
   * <p>⚠️ Само броячът НЕ пази реда — той е 1 и при двете подредби. Затова се мери какво стъпката е
   * ВИДЯЛА, по същия идиом като при обезщетенията.
   */
  @Test
  @DisplayName("⚠️ prepare() вика чл. 177 СЛЕД начисленията — карвът е разлика в техните изходи")
  void prepareRunsArt177LeaveAfterEarningsAndBeforeCompensations() {
    Service s = new Service();
    s.calendar = new BgWorkingDayService.Count(21, Collections.emptyList());
    BgPaySlip slip = slip(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
    s.addLine(
        slip,
        s.lineFrom(
            type(100, "Основно възнаграждение", "CREDIT", true, true), new BigDecimal("1500.00")));

    s.prepare(slip);

    assertEquals(1, s.art177Calls, "шевът по чл. 177 не е извикан — генераторът е РАЗКАЧЕН");
    assertEquals(
        1,
        s.art177SawLines,
        "⚠️ Стъпката по чл. 177 ТРЯБВА да види вече записаните начисления. Нула значи, че е минала"
            + " ПРЕДИ тях — тогава карвът се чете от енджин, който още не е карвирал, и отпускът"
            + " се плаща и през основната, и през перото.");
    assertEquals(
        1,
        s.compensationsSawLines,
        "⚠️ И обезщетенията остават СЛЕД нея: перото по чл. 177 е осигурителен доход и трябва да е"
            + " на фиша, преди вноските да съберат базата.");
  }

  @Test
  @DisplayName("⚠️ prepare() вика обезщетенията СЛЕД начисленията — те четат вече записаните пера")
  void prepareRunsCompensationsAfterEarnings() {
    Service s = new Service();
    s.calendar = new BgWorkingDayService.Count(21, Collections.emptyList());
    BgPaySlip slip = slip(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
    // Едно перо, сложено от „начисленията“: с него се мери КОЙ Е ВИДЯЛ КАКВО.
    s.addLine(
        slip,
        s.lineFrom(
            type(100, "Основно възнаграждение", "CREDIT", true, true), new BigDecimal("1500.00")));

    s.prepare(slip);

    assertEquals(
        1, s.compensationCalls, "шевът на обезщетенията не е извикан — генераторът е РАЗКАЧЕН");
    assertEquals(
        1,
        s.compensationsSawLines,
        "⚠️ Обезщетенията ТРЯБВА да видят вече записаните пера. Нула значи, че са минали ПРЕДИ"
            + " начисленията — тогава осигуровките и данъкът долу смятат върху непълен фиш.");
  }

  /**
   * ⚠️ ОСИГУРОВКИТЕ — ПОСЛЕДНИ, И РЕДЪТ Е ПРАВИЛО, НЕ ПОДРЕДБА.
   *
   * <p>Вноските се смятат върху ПЪЛНИЯ осигурителен доход, а той е сбор на ПЕРАТА ({@code
   * BgPaySlipResolver#insuranceIncomeBase}). Пуснат преди начисленията, болничния или
   * обезщетенията, генераторът осигурява непълен фиш — вноските излизат по-малки, декларацията с
   * тях, и нищо на екрана не изглежда сбъркано.
   *
   * <p>⚠️ Само броячът НЕ пази реда: и при всяка подредба е 1. Затова тестът пита какъв
   * осигурителен доход генераторът е ВИДЯЛ.
   *
   * <p>FAIL-FIRST: извикването беше вдигнато преди {@code generateEarnings} в {@code prepare()} →
   * генераторът вижда 0,00 вместо 1500,00, тестът падна.
   */
  @Test
  @DisplayName("⚠️ prepare() вика осигуровките ПОСЛЕДНИ — те четат целия осигурителен доход")
  void prepareRunsContributionsLast() {
    Service s = new Service();
    s.calendar = new BgWorkingDayService.Count(21, Collections.emptyList());
    BgPaySlip slip = slip(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
    // Едно осигурително перо, сложено от „начисленията“: с него се мери КОЙ Е ВИДЯЛ КАКВО.
    s.addLine(
        slip,
        s.lineFrom(
            type(100, "Основно възнаграждение", "CREDIT", true, true), new BigDecimal("1500.00")));

    s.prepare(slip);

    assertEquals(
        1, s.contributionCalls, "шевът на осигуровките не е извикан — генераторът е РАЗКАЧЕН");
    assertEquals(
        0,
        new BigDecimal("1500.00").compareTo(s.contributionsSawInsuranceIncome),
        "⚠️ Осигуровките ТРЯБВА да видят целия осигурителен доход. Нула значи, че са минали ПРЕДИ"
            + " начисленията — тогава фишът се осигурява върху непълна база и вноските излизат"
            + " по-малки, а декларацията с тях. Намерено: "
            + s.contributionsSawInsuranceIncome);
  }

  /**
   * ⚠️ ДАНЪКЪТ — СЛЕД ОСИГУРОВКИТЕ, И РЕДЪТ Е ПРАВИЛО, НЕ ПОДРЕДБА.
   *
   * <p>Данъчната основа е облагаемите начисления МИНУС личните осигуровки, а те са перата от 5xx
   * със знак DEBIT. Пуснат преди тях, генераторът смята данък върху ЦЕЛИЯ облагаем доход и удържа
   * повече, отколкото се дължи — при това мълчаливо, защото {@code refuseTaxMismatch} сверява
   * перото със заглавната сума, а тя се смята по СЪЩАТА завишена основа.
   *
   * <p>⚠️ Само броячът НЕ пази реда. Затова тестът пита каква ОСНОВА данъкът е видял: 1400,00,
   * когато осигуровките вече са минали, срещу 1500,00, когато не са.
   *
   * <p>FAIL-FIRST: двете извиквания бяха разменени в {@code prepare()} → данъкът вижда 1500,00
   * вместо 1400,00, тестът падна.
   */
  @Test
  @DisplayName(
      "⚠️ prepare() вика данъка СЛЕД осигуровките — основата е вече намалена с личните вноски")
  void prepareRunsIncomeTaxAfterContributions() {
    Service s = new Service();
    s.calendar = new BgWorkingDayService.Count(21, Collections.emptyList());
    BgPaySlip slip = slip(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
    s.addLine(
        slip,
        s.lineFrom(
            type(100, "Основно възнаграждение", "CREDIT", true, true), new BigDecimal("1500.00")));

    s.prepare(slip);

    assertEquals(1, s.incomeTaxCalls, "шевът на данъка не е извикан — генераторът е РАЗКАЧЕН");
    assertEquals(
        0,
        new BigDecimal("1400.00").compareTo(s.incomeTaxSawTaxBase),
        "⚠️ Данъкът ТРЯБВА да види основата СЛЕД личните вноски: 1500,00 − 100,00. Числото 1500,00"
            + " значи, че е минал ПРЕДИ осигуровките — тогава удържа 10 лв. повече, и то мълчаливо,"
            + " защото заглавната сума се смята по същата завишена основа. Намерено: "
            + s.incomeTaxSawTaxBase);
  }

  /**
   * ⚠️ {@code prepare()} вика годишното изравняване СЛЕД данъка — шестият шев е навързан.
   *
   * <p>Двете стъпки не си четат числата (изравняването е за МИНАЛА година и идва от справката),
   * тоест редът не мени нито едно число. Той обаче мени ПОДРЕДБАТА на група 7xx на фиша: разменен,
   * приключването на 2026 г. би застанало НАД удържането за януари 2027 г.
   *
   * <p>⚠️ И по-важното, което този свидетел пази: шевът да е ИЗВИКАН. Разкачен, генераторът мълчи —
   * лицето остава без изравняване, а фишът изглежда напълно сметнат.
   */
  @Test
  @DisplayName("⚠️ prepare() вика годишното изравняване по чл. 49 — СЛЕД данъка и точно веднъж")
  void prepareRunsAnnualSettlementAfterIncomeTax() {
    Service s = new Service();
    s.calendar = new BgWorkingDayService.Count(21, Collections.emptyList());
    BgPaySlip slip = slip(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31));

    s.prepare(slip);

    assertEquals(
        1, s.annualSettlementCalls, "шевът на чл. 49 не е извикан — генераторът е РАЗКАЧЕН");
    assertEquals(
        1,
        s.settlementSawIncomeTaxCalls,
        "⚠️ Изравняването трябва да мине СЛЕД данъка. Намерено: данъкът е бил викан "
            + s.settlementSawIncomeTaxCalls
            + " пъти, когато изравняването е тръгнало.");
  }

  /**
   * Замразеният фиш не се пипа — генераторът спазва същата граница като цялото {@code prepare()}.
   */
  @Test
  @DisplayName("потвърден фиш не стига до генератора")
  void aConfirmedSlipNeverReachesTheGenerator() {
    Service s = new Service();
    s.calendar = new BgWorkingDayService.Count(21, Collections.emptyList());
    BgPaySlip slip = slip(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31));
    slip.setStatusSelect(BgPaySlipResolver.CONFIRMED);

    s.prepare(slip);

    assertEquals(0, s.earningsCalls);
  }
}

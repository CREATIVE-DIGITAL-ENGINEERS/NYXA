package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.accounting.BgPaySlipMoveService;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Предпазителите на фиша, които не се виждат, докато всичко е наред.
 *
 * <p>Всичките три случая са от един и същ вид: <b>проверка, която сравнява две числа, минава
 * винаги, когато и двете са нула</b> — или изобщо не съществува, макар да е обявена.
 */
class BgPaySlipGuardsTest {

  private static class Service extends BgPaySlipService {
    /**
     * Генераторът на начисления пипа речника и каталога с параметри; тук твърдението е за пазачите
     * на фиша, не за перата. Че prepare() наистина вика шева, се проверява в {@code
     * BgPaySlipServiceTest}.
     */
    @Override
    protected void generateEarnings(BgPaySlip slip) {}

    /**
     * Същото за болничните: генераторът пипа четири речника, а тук твърдението е за друго.
     *
     * <p>⚠️ Върви заедно с шева в {@code prepare()}. Без него всеки тест, който вика {@code
     * prepare()}, пада с „Guice is not initialized“ — тоест новият шев не може да се промъкне
     * незабелязано.
     */
    @Override
    protected void generateSickLeave(BgPaySlip slip) {}

    /**
     * ⚠️ ЗАМРАЗЯВАНЕТО НА ДНИТЕ ОТПУСК — заглушено по същия довод като съседите: то пита базата за
     * заявките за отпуск през {@code payrollInput}. Декларация обр. 1 ги чете оттам вместо да ги
     * смята наново; този свидетел не мери нито едното.
     */
    @Override
    protected void freezeLeaveDays(BgPaySlip slip) {}

    /** Стъпката по чл. 177 — заглушена: този свидетел не мери отпуск. */
    @Override
    protected void generateArt177Leave(BgPaySlip slip) {}

    /**
     * Същото за обезщетенията: генераторът чете документите и речника, а тук твърдението е за
     * друго.
     *
     * <p>⚠️ Добавен заедно с шева в {@code prepare()}. Без него всеки тест, който вика {@code
     * prepare()}, пада с „Guice is not initialized“ — тоест новият шев не може да се промъкне
     * незабелязано.
     */
    @Override
    protected void generateCompensations(BgPaySlip slip) {}

    /**
     * Същото за осигуровките: генераторът чете речника с типове пера, а тук твърдението е за друго.
     *
     * <p>⚠️ Добавен заедно с шева в {@code prepare()}. Без него всеки тест, който вика {@code
     * prepare()}, пада с „Guice is not initialized“ — тоест новият шев не може да се промъкне
     * незабелязано.
     */
    @Override
    protected void generateContributions(BgPaySlip slip) {}

    /**
     * Същото за данъка: генераторът чете речника с типове пера, а тук твърдението е за друго.
     *
     * <p>⚠️ Добавен заедно с шева в {@code prepare()}. Без него всеки тест, който вика {@code
     * prepare()}, пада с „Guice is not initialized“.
     */
    @Override
    protected void generateIncomeTax(BgPaySlip slip) {}

    @Override
    protected void generateAnnualSettlement(BgPaySlip slip) {}

    @Override
    protected BgWorkingDayService.Count periodWorkingDays(BgPaySlip slip) {
      return new BgWorkingDayService() {
        @Override
        public java.util.Set<Integer> coveredYears() {
          return new java.util.HashSet<>(java.util.Arrays.asList(2026, 2027));
        }

        @Override
        protected java.util.Set<LocalDate> holidaysIn(LocalDate from, LocalDate to) {
          return java.util.Collections.emptySet();
        }
      }.count(LocalDate.of(2026, 3, 1), 31);
    }

    /**
     * ⚠️ ОСИГУРОВКИТЕ СЕ ОБЯВЯВАТ ЗА НАРЕД — ИЗРИЧНО.
     *
     * <p><b>Фикстурите на тези пет теста нямат трудов договор</b> ({@code
     * BgPaySlip.employmentContract} се пълни само там, където е нужен). С навързан шев те падат с
     * „вход за осигуровки не може да се сглоби“, тоест без тази подмяна биха зависели от
     * осигуровките, без това да личи.
     *
     * <p>Подмяната е ИЗРИЧНА, а не връщане към {@code null}: този клас изолира ДРУГИ предпазители
     * (фискален период, нулево бруто, непокрит календар, дата на изплащане) и за тях осигуровките
     * са нарочно неутрални. Празен {@code RESOLVED} казва точно това — „сметнати са, няма какво да
     * се откаже“ — вместо да пресъздава инертен шев на местно ниво и да скрие точно това, което е
     * навързано.
     */
    @Override
    protected BgContributionResult contributions(BgPaySlip slip) {
      return BgContributionResult.resolved(java.util.Collections.emptyList());
    }

    /** Същото за МОД: {@code NOT_APPLICABLE} не е отказ и не поражда съобщение. */
    @Override
    protected BgMinIncomeResult minIncome(BgPaySlip slip) {
      return BgMinIncomeResult.notApplicable("неутрално за тези тестове");
    }

    @Override
    protected BigDecimal incomeTaxRate(BgPaySlip slip) {
      return new BigDecimal("10.0000");
    }

    @Override
    protected String otherSlipCarrying(BgCompensation doc, BgPaySlip slip) {
      return null;
    }

    @Override
    protected void save(BgPaySlip slip) {}

    @Override
    protected void saveCompensation(BgCompensation doc) {}
  }

  private static BgPaySlip slip(int yearType) {
    BgPaySlip s = new BgPaySlip();
    s.setStatusSelect(BgPaySlipResolver.DRAFT);
    Year year = new Year();
    year.setName(yearType == 2 ? "Заплатна 2026" : "fiscalYear.code 2026");
    year.setTypeSelect(yearType);
    Period p = new Period();
    p.setName(yearType == 2 ? "03/PY2026" : "07/fiscalYear.code2026");
    p.setFromDate(LocalDate.of(2026, 3, 1));
    p.setToDate(LocalDate.of(2026, 3, 31));
    p.setYear(year);
    s.setPeriod(p);
    s.setBgPaySlipLineList(new ArrayList<>());
    return s;
  }

  private static BgPaySlipLineType type(int number, String name, String sign, boolean taxable) {
    BgPaySlipLineType t = new BgPaySlipLineType();
    t.setNumber(number);
    t.setName(name);
    t.setBgSign(sign);
    t.setBgIsTaxable(taxable);
    t.setBgIsInsuranceIncome(taxable);
    t.setBgIsPermanentCharacter(false);
    return t;
  }

  // ── 1а · празният фиш ───────────────────────────────────────────────────

  @Test
  @DisplayName("1а · ФИШ БЕЗ ПЕРА не се потвърждава — трите нули си съвпадаха идеално")
  void anEmptyPaySlipIsRefused() {
    Service s = new Service();
    BgPaySlip empty = slip(2);
    s.recompute(empty);

    // Ето защо всичко останало мълчеше: заглавният данък е 0, перата от 7xx са 0, съвпадат.
    assertEquals(0, empty.getBgTaxAmount().signum());
    assertNull(
        BgPaySlipResolver.refuseTaxMismatch(empty.getBgTaxAmount(), empty.getBgPaySlipLineList()),
        "проверката за данъка минава ПРАЗНА при нули — това е диагнозата, не дефект в нея");

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(empty));
    assertTrue(e.getMessage().contains("нито едно перо"), e.getMessage());
    assertTrue(e.getMessage().contains("ПЕРО, а не с празнота"), e.getMessage());
    assertEquals(BgPaySlipResolver.DRAFT, empty.getStatusSelect(), "остава чернова");
  }

  // ── 1б · нулево бруто С пера ────────────────────────────────────────────

  @Test
  @DisplayName("1б · нулево бруто С ПЕРА е предупреждение, не отказ — цял месец неплатен отпуск")
  void aZeroGrossPaySlipWithLinesOnlyWarns() throws Exception {
    Service s = new Service();
    BgPaySlip d = slip(2);
    // Перо със сума нула: месецът е изкаран в неплатен отпуск, но това е ЗАПИСАНО, не празно.
    s.addLine(d, s.lineFrom(type(100, "Основно възнаграждение", "CREDIT", true), BigDecimal.ZERO));
    s.calculate(d);

    assertEquals(0, d.getBgGrossTotal().signum());
    String warning = BgPaySlipResolver.zeroGrossWarning(d.getBgPaySlipLineList());
    assertNotNull(warning, "трябва да предупреди");
    assertTrue(warning.contains("неплатен отпуск"), warning);

    s.confirm(d);
    assertEquals(BgPaySlipResolver.CONFIRMED, d.getStatusSelect(), "но НЕ спира");

    // а при нормален фиш няма какво да се каже
    Service s2 = new Service();
    BgPaySlip full = slip(2);
    s2.addLine(full, s2.lineFrom(type(100, "Заплата", "CREDIT", true), new BigDecimal("1000.00")));
    s2.recompute(full);
    assertNull(BgPaySlipResolver.zeroGrossWarning(full.getBgPaySlipLineList()));
  }

  // ── 3 · заплатната година ───────────────────────────────────────────────

  @Test
  @DisplayName("3 · фиш върху ФИСКАЛЕН период се отказва, върху заплатен — минава")
  void aPaySlipOnAFiscalPeriodIsRefused() throws Exception {
    Service s = new Service();
    BgPaySlip fiscal = slip(1); // typeSelect = 1 → фискална
    s.addLine(fiscal, s.lineFrom(type(100, "Заплата", "CREDIT", true), new BigDecimal("1000.00")));
    s.recompute(fiscal);
    s.addLine(fiscal, s.lineFrom(type(700, "Данък", "DEBIT", false), fiscal.getBgTaxAmount()));
    s.recompute(fiscal);

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(fiscal));
    assertTrue(e.getMessage().contains("НЕ е заплатна"), e.getMessage());
    assertTrue(
        e.getMessage().contains("07/fiscalYear.code2026"), "казва кой период: " + e.getMessage());
    assertTrue(e.getMessage().contains("тип 1 вместо 2"), e.getMessage());

    // същият фиш върху заплатна година минава
    Service ok = new Service();
    BgPaySlip payroll = slip(2);
    ok.addLine(
        payroll, ok.lineFrom(type(100, "Заплата", "CREDIT", true), new BigDecimal("1000.00")));
    ok.recompute(payroll);
    ok.addLine(payroll, ok.lineFrom(type(700, "Данък", "DEBIT", false), payroll.getBgTaxAmount()));
    ok.calculate(payroll);
    ok.confirm(payroll);
    assertEquals(BgPaySlipResolver.CONFIRMED, payroll.getStatusSelect());
  }

  @Test
  @DisplayName("3б · фиш без период или без година също се отказва, а не минава мълчаливо")
  void aPaySlipWithoutAYearIsRefused() {
    Service s = new Service();
    BgPaySlip d = slip(2);
    d.getPeriod().setYear(null);
    assertNotNull(s.payrollYearRefusal(d));
    assertTrue(s.payrollYearRefusal(d).contains("не принадлежи на година"));
  }

  // ── 2 · основната заплата ───────────────────────────────────────────────

  @Test
  @DisplayName("2 · заплатата се копира от договора — проверка за null би била МЪРТЪВ КОД")
  void theBaseSalaryIsCopiedFromTheContract() {
    Service s = new Service();
    BgPaySlip d = slip(2);
    EmploymentContract contract = new EmploymentContract();
    contract.setBgBaseSalary(new BigDecimal("1500.00"));
    d.setEmploymentContract(contract);

    // ⚠️ Генерираният getter връща ZERO, не null: новият фиш идва с 0,00, а не с празно. Оттам
    // условие „== null“ е винаги невярно и заплатата не би се копирала НИКОГА.
    assertEquals(0, d.getBgBaseSalary().signum(), "новият фиш идва с нула, не с null");

    s.prepare(d);
    assertEquals(new BigDecimal("1500.00"), d.getBgBaseSalary(), "копирана от договора");

    // въведена на ръка стойност НЕ се презаписва
    // ⚠️ РЪЧНОТО СЕ ОБЯВЯВА С ПРИЗНАК, А НЕ СЕ ИЗВЕЖДА ОТ ТОВА, ЧЕ ЧИСЛОТО Е НЕНУЛЕВО.
    // „Ненулево значи ръчно“ би отказало мълчаливо и ПРЕИЗЧИСЛЯВАНЕТО: заплатата зависи от
    // ПЕРИОДА (допълнителните споразумения по чл. 119 имат дата на действие), тоест смяна на
    // периода трябва да върне друго число, а не първото. В екрана признакът пада сам при редакция
    // на полето
    // (action-bg-payslip-record-manual-base-salary); тук се слага изрично, защото сглобен наум
    // обект няма onChange.
    BgPaySlip manual = slip(2);
    manual.setEmploymentContract(contract);
    manual.setBgBaseSalary(new BigDecimal("1800.00"));
    manual.setBgAutoFillBaseSalary(false);
    s.prepare(manual);
    assertEquals(new BigDecimal("1800.00"), manual.getBgBaseSalary(), "ръчното решение оцелява");

    // ⚠️ И ОБРАТНОТО, ЗАЩОТО ТО Е НОВОТО: при ВДИГНАТ признак числото се извежда наново. Без този
    // ред тестът щеше да заковава само половината от правилото и смяната на периода пак щеше да
    // носи чуждата заплата, без нищо да почервенее.
    BgPaySlip refreshed = slip(2);
    refreshed.setEmploymentContract(contract);
    refreshed.setBgBaseSalary(new BigDecimal("1800.00"));
    s.prepare(refreshed);
    assertEquals(
        new BigDecimal("1500.00"),
        refreshed.getBgBaseSalary(),
        "авто-попълненото се преизчислява от договора, а не се замразява при първото число");
  }

  // ── 6 · изплащането иска дата ───────────────────────────────────────────

  @Test
  @DisplayName("6 · без дата на изплащане фишът не става ИЗПЛАТЕН")
  void payingRequiresTheDate() throws Exception {
    Service s = new Service();
    BgPaySlip d = slip(2);
    s.addLine(d, s.lineFrom(type(100, "Заплата", "CREDIT", true), new BigDecimal("1000.00")));
    s.recompute(d);
    s.addLine(d, s.lineFrom(type(700, "Данък", "DEBIT", false), d.getBgTaxAmount()));
    s.calculate(d);
    s.confirm(d);

    AxelorException e = assertThrows(AxelorException.class, () -> s.pay(d));
    assertTrue(e.getMessage().contains("дата"), e.getMessage());
    assertEquals(BgPaySlipResolver.CONFIRMED, d.getStatusSelect(), "остава потвърден");

    d.setBgPaidDate(LocalDate.of(2026, 4, 10));
    s.pay(d);
    assertEquals(BgPaySlipResolver.PAID, d.getStatusSelect());
  }

  /**
   * ⚠️ ОТКАЗЪТ ЗА ЖУРНАЛА СТИГА ДО {@code pay()}. Шевът към статията се вика ПРЕДИ смяната на
   * статуса — тоест изключението оставя фиша потвърден, а транзакцията на {@code pay()} връща и
   * обезщетенията. Тук шевът вика истинската врата, но без контейнер.
   */
  @Test
  @DisplayName("6а · без заплатен журнал фишът НЕ става ИЗПЛАТЕН, а отказът назовава полето")
  void payingWithoutAPayrollJournalIsRefused() throws Exception {
    Service s =
        new Service() {
          @Override
          protected void generatePayrollMove(BgPaySlip slip) throws AxelorException {
            new BgPaySlipMoveService(null, null, null, null, null, null, null).generate(slip);
          }
        };
    BgPaySlip d = slip(2);
    s.addLine(d, s.lineFrom(type(100, "Заплата", "CREDIT", true), new BigDecimal("1000.00")));
    s.recompute(d);
    s.addLine(d, s.lineFrom(type(700, "Данък", "DEBIT", false), d.getBgTaxAmount()));
    s.calculate(d);
    s.confirm(d);

    Company company = new Company();
    company.setName("Тест ЕООД");
    Currency euro = new Currency();
    euro.setCode("EUR");
    company.setCurrency(euro);
    company.setAccountConfig(new AccountConfig());
    EmploymentContract contract = new EmploymentContract();
    contract.setPayCompany(company);
    d.setEmploymentContract(contract);
    d.setBgPaidDate(LocalDate.of(2026, 4, 10));

    AxelorException e = assertThrows(AxelorException.class, () -> s.pay(d));
    assertTrue(e.getMessage().contains("bgPayrollJournal"), e.getMessage());
    assertEquals(BgPaySlipResolver.CONFIRMED, d.getStatusSelect(), "остава потвърден");
  }

  // ── 7 · календарът при НЕпопълнени дни ──────────────────────────────────

  /** Услуга, чийто календар НЕ покрива 2026 г. — тоест преброените дни са ненадеждни. */
  private static class UncoveredService extends Service {
    @Override
    protected BgWorkingDayService.Count periodWorkingDays(BgPaySlip slip) {
      return new BgWorkingDayService() {
        @Override
        public java.util.Set<Integer> coveredYears() {
          return new java.util.HashSet<>(java.util.Collections.singletonList(2027));
        }

        @Override
        protected java.util.Set<LocalDate> holidaysIn(LocalDate from, LocalDate to) {
          return java.util.Collections.emptySet();
        }
      }.count(LocalDate.of(2026, 3, 1), 31);
    }
  }

  @Test
  @DisplayName("7 · НЕЗАРЕДЕНА година спира фиша, докато дните не са надписани НА РЪКА")
  void anUncoveredCalendarYearRefusesUntilTheDaysAreEnteredByHand() throws Exception {
    UncoveredService s = new UncoveredService();
    BgPaySlip d = slip(2);
    s.addLine(d, s.lineFrom(type(100, "Заплата", "CREDIT", true), new BigDecimal("1000.00")));
    s.recompute(d);
    s.addLine(d, s.lineFrom(type(700, "Данък", "DEBIT", false), d.getBgTaxAmount()));
    s.recompute(d);

    // ⚠️ ТОВА Е СЛУЧАЯТ, КОЙТО МИНАВАШЕ. Полето „работни дни“ е непопълнено, тоест генерираният
    // getter връща 0. Старото условие питаше дали НЕ е null — вярно за нулата — и после дали 0 се
    // различава от преброените дни, също вярно. Тоест отказът се прескачаше точно тогава, когато
    // трябва да се задейства.
    assertEquals(0, d.getBgWorkingDaysInPeriod().intValue(), "нищо не е надписано");
    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(d));
    assertTrue(e.getMessage().contains("НЯМА зареден производствен календар"), e.getMessage());
    assertTrue(e.getMessage().contains("2026"), e.getMessage());

    // Надписаните на ръка дни минават: числото вече е нечие решение, а не мълчалива аритметика.
    d.setBgWorkingDaysInPeriod(20);
    s.calculate(d);
    s.confirm(d);
    assertEquals(BgPaySlipResolver.CONFIRMED, d.getStatusSelect());
  }

  // ── диагнозата, закована ────────────────────────────────────────────────

  @Test
  @DisplayName("⚠️ ДИАГНОЗАТА: кои проверки минават ПРАЗНИ при нулеви стойности")
  void whichGuardsPassVacuouslyOnZeroes() {
    Service s = new Service();
    BgPaySlip empty = slip(2);
    s.recompute(empty);
    List<com.axelor.apps.bglocalization.db.BgPaySlipLine> none = empty.getBgPaySlipLineList();

    // 1. данъкът: 0 срещу 0 — минава празна. ПО УСТРОЙСТВО, не по дефект: тя сравнява две числа.
    assertNull(BgPaySlipResolver.refuseTaxMismatch(empty.getBgTaxAmount(), none));
    // 2. двойното обезщетение: няма пера → няма какво да е двойно. Правилно.
    assertNull(s.doubleCompensationRefusal(empty));
    // 3. календарът: покрита година → мълчи. Правилно, не зависи от перата.
    assertNull(s.calendarRefusal(empty));
    // 4 и 5. осигуровките и МОД: ИНЕРТНИ ШЕВОВЕ — виж отделния тест.
    assertNull(s.contributionRefusal(empty));
    assertNull(s.minIncomeRefusal(empty));

    // Оттам: единствената проверка, която различава „нула“ от „нищо“, е тази, която брои РЕДОВЕ.
    assertNotNull(BgPaySlipResolver.refuseEmptyPaySlip(none));
  }
}

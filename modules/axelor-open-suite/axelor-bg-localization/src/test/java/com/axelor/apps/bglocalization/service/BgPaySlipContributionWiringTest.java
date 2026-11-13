package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgInsuranceContribution;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgContributionLine.Payer;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInput;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInputBuilder;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollWorkTime;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ШЕВЪТ Е НАВЪРЗАН — числата на един истински фиш, ред по ред.
 *
 * <p><b>Какво точно се проверява тук.</b> Че {@code BgPaySlipService#contributions} и {@code
 * #minIncome} СМЯТАТ, а не връщат {@code null}: осигурителната база се съставя от бруто, под и
 * таван, редовете се избират по ключа и резолверът произвежда суми.
 *
 * <p><b>⚠️ ПОДМЕНЕНИ СА САМО РЕПОЗИТОРИИТЕ, НЕ ПРАВИЛАТА.</b> Това е разликата между тест, който
 * доказва нещо, и тест, който проверява собствените си фикстури:
 *
 * <ul>
 *   <li>{@link BgContributionResolver#resolve} се вика ИСТИНСКИ, върху редове, прочетени от {@code
 *       bg_insurance_contribution.csv} — тоест ставките са СЕДМИТЕ реални за 2026 г., а не
 *       измислени числа. Смени ли се seed-ът, числата тук се разминават и тестът пада.
 *   <li>{@link BgInsurableIncomeService#compute} се вика ИСТИНСКИ — подът, таванът и пропорцията са
 *       неговата аритметика.
 *   <li>{@link com.axelor.apps.bglocalization.service.payroll.BgPayrollWorkTime#decide} се вика
 *       ИСТИНСКИ — режимът и отказите му са негови.
 *   <li>Подменени са само четците: {@code minIncomeFor}, {@code tzpbRate} и зареждането на
 *       редовете.
 * </ul>
 *
 * <p>Тече без Postgres, без Guice и без Hibernate.
 */
class BgPaySlipContributionWiringTest {

  private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
  private static final LocalDate TO = LocalDate.of(2026, 3, 31);

  /** 03.03.2026 е вторник; с него март 2026 има 21 работни дни. */
  private static final int WORKING_DAYS = 21;

  /** ЗБДОО чл. 9 т. 1 б. „в“ — максималният осигурителен доход до 31.07.2026 г. */
  private static final BigDecimal CAP = new BigDecimal("2111.6400");

  // ═══ РЕДОВЕТЕ ОТ SEED-А ═══════════════════════════════════════════════════

  /**
   * Редовете за един вид осигурен и една кохорта, ПРОЧЕТЕНИ ОТ SEED-А.
   *
   * <p>⚠️ Филтърът е ДОСЛОВНО този на {@code BgInsuranceCalculationService}: позволителният
   * заместител {@code ANY} по трите оси (кохорта, СОЛ обхват, учителство) и ТОЧНО съвпадение по
   * основанието. Преписан е тук нарочно и това е записано: така ключът, по който се избират
   * редовете, е ВИДИМ в теста, вместо да се крие зад JPQL, който тестът без база не може да пусне.
   * Разминат ли се двата филтъра, числата отдолу спират да отговарят на производството — затова
   * всяка ос е с назован коментар, а не с гол низ.
   */
  private static List<BgInsuranceContribution> seedRows(
      String insuredType, String laborCategory, String cohort, String solScope, String teacher) {

    List<String> h = BgSeed.quotedHeader("bg_insurance_contribution.csv");
    List<BgInsuranceContribution> out = new ArrayList<>();
    for (String[] r : BgSeed.quotedRows("bg_insurance_contribution.csv")) {
      if (!insuredType.equals(BgSeed.col(h, r, "bgInsuredTypeCode"))
          || !laborCategory.equals(BgSeed.col(h, r, "laborCategory"))) {
        continue;
      }
      String upf = BgSeed.col(h, r, "bgUpfRegime");
      String sol = BgSeed.col(h, r, "bgSolScope");
      String tea = BgSeed.col(h, r, "bgTeacherScope");
      if (!"ANY".equals(upf) && !upf.equals(cohort)) {
        continue;
      }
      if (!"ANY".equals(sol) && !sol.equals(solScope)) {
        continue;
      }
      if (!"ANY".equals(tea) && !tea.equals(teacher)) {
        continue;
      }
      if (!"STANDARD".equals(BgSeed.col(h, r, "bgCompensationTypeCode"))) {
        continue;
      }
      BgInsuranceContribution c = new BgInsuranceContribution();
      c.setImportId(BgSeed.col(h, r, "importId"));
      c.setFundCode(BgSeed.col(h, r, "fundCode"));
      c.setEmployerRate(new BigDecimal(BgSeed.col(h, r, "employerRate")));
      c.setEmployeeRate(new BigDecimal(BgSeed.col(h, r, "employeeRate")));
      c.setBgEmployerRateKind(BgSeed.col(h, r, "bgEmployerRateKind"));
      c.setBgEmployeeRateKind(BgSeed.col(h, r, "bgEmployeeRateKind"));
      out.add(c);
    }
    return out;
  }

  // ═══ УСЛУГАТА С ПОДМЕНЕНИ ЧЕТЦИ ═══════════════════════════════════════════

  private static class Service extends BgPaySlipService {

    /**
     * Генераторът на начисления пипа речника и каталога с параметри; тук твърдението е за
     * ретро-механизма, не за перата. Че {@code prepare()} наистина вика шева, се проверява в {@code
     * BgPaySlipServiceTest}, а какво пише той — в {@code BgEarningsGeneratorTest}.
     */
    @Override
    protected void generateEarnings(BgPaySlip slip) {}

    BgPayrollInput input;
    BgMinIncomeResult mod = BgMinIncomeResult.notApplicable("вид осигурен: 01");
    BigDecimal statutoryFloor;
    BigDecimal cap = CAP;

    /**
     * Огледало на {@code BgInsuredType.bgZeroWorkTime} — истинската услуга го чете от реда.
     *
     * <p>{@code false} значи „това правоотношение НЯМА работно време, на което прагът да е част“ —
     * вярно за ДУК (10), за всички самоосигуряващи се и за морското лице (92). Тогава месечният
     * праг се дължи ЦЯЛ. Държи се като поле, а не като константа, защото тестът за код 92 щеше да
     * пропорционира праг, който по норма не се пропорционира.
     */
    boolean prorates = true;

    BigDecimal tzpb;
    List<BgInsuranceContribution> rows = Collections.emptyList();

    /**
     * Заявката, с която резолверът наистина е бил питан — за да се провери КЛЮЧЪТ, не само сумите.
     */
    BgContributionRequest seenRequest;

    /** Аргументите, с които базата е била поискана — insuredTypeCode не бива да се губи по пътя. */
    String seenInsuredTypeCode;

    BgInsurableIncomeResult seenIncome;

    @Override
    protected BgPayrollInput payrollInput(BgPaySlip slip) {
      return input;
    }

    @Override
    protected BgMinIncomeResult minIncomeFor(BgPayrollInput in, BgPaySlip slip) {
      return mod;
    }

    /**
     * Същото огледало на {@code bgZeroWorkTime}, което подава и на {@code compute} по-долу.
     *
     * <p>⚠️ ЕДНО поле за двете места нарочно: истинската услуга чете флага ЕДИН път от {@code
     * BgInsuredType} и го ползва и за гейта, и за сметката. Две тестови полета щяха да позволят
     * състояние, което производството не може да произведе — гейт, който вярва че се пропорционира,
     * срещу сметка, която не пропорционира — и тестът щеше да пази несъществуващ случай.
     */
    @Override
    protected boolean proratesWithWorkTime(BgPayrollInput in, BgPaySlip slip) {
      return prorates;
    }

    // ⚠️ ИСТИНСКАТА аритметика — само зареждането на прага и тавана е подменено. Тоест
    // min(max(бруто, МОД, законов праг), таван) и пропорцията са на услугата, не на теста.
    @Override
    protected BgInsurableIncomeResult insurableIncome(
        BigDecimal gross, BgMinIncomeResult modResult, BgPayrollInput in, BgWorkTimeFacts facts) {
      seenInsuredTypeCode = in.insuredTypeCode;
      seenIncome =
          BgInsurableIncomeService.compute(
              gross, modResult, statutoryFloor, prorates, cap, FROM, facts);
      return seenIncome;
    }

    @Override
    protected BigDecimal tzpbRate(BgPaySlip slip, LocalDate date) {
      return tzpb;
    }

    // ⚠️ ИСТИНСКИЯТ резолвер — подменено е само зареждането на редовете.
    @Override
    protected BgContributionResult calculate(BgContributionRequest request) {
      seenRequest = request;
      return BgContributionResolver.resolve(rows, request);
    }

    @Override
    protected BgWorkingDayService.Count periodWorkingDays(BgPaySlip slip) {
      return new BgWorkingDayService.Count(WORKING_DAYS, Collections.emptyList());
    }

    @Override
    protected String otherSlipCarrying(BgCompensation doc, BgPaySlip slip) {
      return null;
    }

    @Override
    protected BigDecimal incomeTaxRate(BgPaySlip slip) {
      return new BigDecimal("10.0000");
    }

    @Override
    protected void save(BgPaySlip slip) {}
  }

  // ═══ ФИКСТУРИТЕ ═══════════════════════════════════════════════════════════

  /**
   * Сглобител без база: календарът, отчетът и обезщетенията се подават.
   *
   * <p>⚠️ Отчетът се строи от РАЗБИВКА ПО ДАТИ и минава през {@code WorkedTime.of(...)}, а не се
   * подава готов. Така агрегацията — включително правилото „нула отчетени реда НЕ е нула часа“ —
   * остава истинска; подаден готов {@code WorkedTime} щеше да я заобиколи и тестът щеше да
   * проверява фикстурата си.
   */
  private static final class Builder extends BgPayrollInputBuilder {
    /** Часове на ден по отчета; {@code null} значи ОТЧЕТ НЯМА (не нула часа). */
    String hoursPerDay = "8.00";

    int days = WORKING_DAYS;

    @Override
    protected BgWorkingDayService.Count workingDays(LocalDate from, LocalDate to) {
      return new BgWorkingDayService.Count(WORKING_DAYS, Collections.emptyList());
    }

    // ⚠️ ПОДМЕНЯ СЕ РАЗБИВКАТА ПО ДАТИ, А НЕ СБОРЪТ. workedTime на сглобителя се ИЗВЕЖДА от
    // workedHoursByDate — една заявка в производството, два прочита — тоест подменен тук, единият
    // шев храни и двата. Override на workedTime би работил за СБОРА, но НЕ покрива часовете на
    // официален празник: те четат разбивката, а сборът вече я е загубил, и заявката им би стигнала
    // до истинската база.
    @Override
    protected Map<LocalDate, BigDecimal> workedHoursByDate(
        Employee employee, LocalDate from, LocalDate to) {
      if (hoursPerDay == null) {
        return Collections.emptyMap();
      }
      Map<LocalDate, BigDecimal> out = new LinkedHashMap<>();
      for (int i = 0; i < days; i++) {
        out.put(FROM.plusDays(i), new BigDecimal(hoursPerDay));
      }
      return out;
    }

    @Override
    protected Set<LocalDate> officialHolidays(LocalDate from, LocalDate to) {
      return Collections.emptySet();
    }

    @Override
    protected List<Long> compensationDocumentIds(
        EmploymentContract contract, LocalDate from, LocalDate to) {
      return Collections.emptyList();
    }

    // ═══ ШЕВОВЕТЕ, ДОБАВЕНИ ОТ WS-5 ══════════════════════════════════════════
    // ⚠️ ВСИЧКИТЕ ЧЕТИРИ СА ЗАДЪЛЖИТЕЛНИ ТУК, макар този тест да не твърди нищо за тях: build()
    // ги вика БЕЗУСЛОВНО, а подразбиращите им се тела минават през JPA.em(). Непокрити, те валят
    // целия клас с „JPA context not initialized“ — тоест деветте твърдения за осигуровките падат
    // заради вход, който изобщо не ги засяга.
    //
    // Празното тук значи „този тест не подава такива данни“ и точно това е верният фон: липсващ
    // отчет за отпуски/извънреден труд дава null („не знам“), а не нула.

    @Override
    protected List<BgPayrollInput.SickLeaveEpisode> sickLeaveEpisodesOf(
        EmploymentContract contract, LocalDate from, LocalDate to) {
      return Collections.emptyList();
    }

    @Override
    protected List<Object[]> leaveRows(Employee employee, LocalDate from, LocalDate to) {
      return Collections.emptyList();
    }

    @Override
    protected List<Object[]> extraHoursRows(Employee employee, LocalDate from, LocalDate to) {
      return Collections.emptyList();
    }

    @Override
    protected DeclaredHours declaredHoursOf(
        EmploymentContract contract, LocalDate from, LocalDate to) {
      return DeclaredHours.unknown();
    }
  }

  /**
   * Обикновен нает по чл. 4 ал. 1 т. 1 КСО: вид 01, трета категория, роден след 1959, пълно време.
   */
  private static EmploymentContract contract() {
    Employee e = new Employee();
    e.setId(7L);
    e.setBirthDate(LocalDate.of(1985, 4, 12));
    e.setBgLaborCategory("3");
    e.setBgBornAfter1959(true);
    e.setBgIsPensioner(false);

    EmploymentContract c = new EmploymentContract();
    c.setId(42L);
    c.setEmployee(e);
    c.setStartDate(LocalDate.of(2019, 6, 1));
    c.setBgBaseSalary(new BigDecimal("1500.00"));
    c.setBgHoursPerDay(new BigDecimal("8.00"));
    c.setBgWorkTimeMode("1");
    c.setBgIsTeacherPosition(false);
    c.setBgInsuredTypeCode("01");
    return c;
  }

  private static Period march() {
    Year year = new Year();
    year.setName("Заплатна 2026");
    year.setTypeSelect(2);
    Period p = new Period();
    p.setName("03/PY2026");
    p.setFromDate(FROM);
    p.setToDate(TO);
    p.setYear(year);
    return p;
  }

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

  /** Фиш с една заплата от 1500,00, обявена за осигурителен доход. */
  private static BgPaySlip slip(Service s, EmploymentContract contract, String salary) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setEmploymentContract(contract);
    slip.setPeriod(march());
    slip.setBgPaySlipLineList(new ArrayList<>());
    s.addLine(
        slip,
        s.lineFrom(
            type(100, "Основно възнаграждение", "CREDIT", true, true), new BigDecimal(salary)));
    s.recompute(slip);
    return slip;
  }

  private static Service wired(EmploymentContract contract, Builder builder) {
    Service s = new Service();
    s.input = builder.build(contract, march());
    s.rows = seedRows("01", "3", "WITH_UPF", "ANY", "NON_TEACHER");
    return s;
  }

  private static Map<String, BigDecimal> byFundAndPayer(BgContributionResult r) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    for (BgContributionLine l : r.getLines()) {
      out.put(
          l.getFundCode() + "/" + (l.getPayer() == Payer.EMPLOYER ? "РАБ" : "ЛИЦЕ"), l.getAmount());
    }
    return out;
  }

  // ═══ 1 · ИСТИНСКИ ФИШ, ТОЧНИ ЧИСЛА ════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Вид 01, пълно време, отчет → осигурителни редове с ТОЧНИ суми")
  void aRealContractProducesExactContributionLines() {
    Service s = wired(contract(), new Builder());
    BgPaySlip slip = slip(s, contract(), "1500.00");

    // Котвата: седемте реда за код 01 наистина са прочетени от seed-а.
    assertEquals(
        6,
        s.rows.size(),
        "очаквах шест реда за код 01 при кохорта WITH_UPF (PENSION, UPF, OZM, UNEMPLOYMENT, HEALTH,"
            + " GVRS) — вторият пенсионен ред е WITHOUT_UPF и не бива да минава филтъра. Намерени: "
            + s.rows.size());

    BgContributionResult r = s.contributions(slip);
    assertEquals(
        BgContributionResult.Status.RESOLVED, r.getStatus(), String.valueOf(r.getDetail()));

    // ── базата ──────────────────────────────────────────────────────────────
    assertEquals("01", s.seenInsuredTypeCode, "видът осигурен стига до базата");
    assertEquals(
        0,
        new BigDecimal("1500.00").compareTo(s.seenRequest.getInsurableIncome()),
        "пълен месец, без под над брутото: базата е самото бруто");

    // ── редовете, ред по ред ────────────────────────────────────────────────
    Map<String, BigDecimal> lines = byFundAndPayer(r);
    assertEquals(
        Arrays.asList(
            "PENSION/РАБ", "PENSION/ЛИЦЕ",
            "UPF/РАБ", "UPF/ЛИЦЕ",
            "OZM/РАБ", "OZM/ЛИЦЕ",
            "UNEMPLOYMENT/РАБ", "UNEMPLOYMENT/ЛИЦЕ",
            "HEALTH/РАБ", "HEALTH/ЛИЦЕ"),
        new ArrayList<>(lines.keySet()),
        "⚠️ ГВРС НЯМА ред: и двете му ставки са 0,0000 със вид LEGAL_ZERO (ЗБДОО чл. 15 ал. 1 спира"
            + " вноската за 2026 г.), а резолверът не строи ред за нулева ставка. Липсата му ТУК е"
            + " вярна; би било грешно да се появи с нула.");

    assertEquals(0, new BigDecimal("123.30").compareTo(lines.get("PENSION/РАБ")), "8,22% от 1500");
    assertEquals(0, new BigDecimal("98.70").compareTo(lines.get("PENSION/ЛИЦЕ")), "6,58% от 1500");
    assertEquals(0, new BigDecimal("42.00").compareTo(lines.get("UPF/РАБ")), "2,80% от 1500");
    assertEquals(0, new BigDecimal("33.00").compareTo(lines.get("UPF/ЛИЦЕ")), "2,20% от 1500");
    assertEquals(0, new BigDecimal("31.50").compareTo(lines.get("OZM/РАБ")), "2,10% от 1500");
    assertEquals(0, new BigDecimal("21.00").compareTo(lines.get("OZM/ЛИЦЕ")), "1,40% от 1500");
    assertEquals(0, new BigDecimal("9.00").compareTo(lines.get("UNEMPLOYMENT/РАБ")), "0,60%");
    assertEquals(0, new BigDecimal("6.00").compareTo(lines.get("UNEMPLOYMENT/ЛИЦЕ")), "0,40%");
    assertEquals(0, new BigDecimal("72.00").compareTo(lines.get("HEALTH/РАБ")), "4,80% от 1500");
    assertEquals(0, new BigDecimal("48.00").compareTo(lines.get("HEALTH/ЛИЦЕ")), "3,20% от 1500");

    // ── сборовете ───────────────────────────────────────────────────────────
    assertEquals(
        0, new BigDecimal("277.80").compareTo(r.totalEmployer()), "18,52% от 1500 за работодателя");
    assertEquals(
        0, new BigDecimal("206.70").compareTo(r.totalEmployee()), "13,78% от 1500 за лицето");
    assertEquals(0, new BigDecimal("484.50").compareTo(r.total()), "общо 32,30% от 1500");

    // ── и ОТКАЗЪТ мълчи, защото няма за какво да говори ─────────────────────
    assertNull(s.contributionRefusal(slip), "чист RESOLVED не поражда отказ");
  }

  @Test
  @DisplayName("ТЗПБ идва от заявката, не от редовете — вид 01 няма собствен ред")
  void theTzpbRateArrivesThroughTheRequest() {
    Service s = wired(contract(), new Builder());
    s.tzpb = new BigDecimal("0.5000");
    BgPaySlip slip = slip(s, contract(), "1500.00");

    Map<String, BigDecimal> lines = byFundAndPayer(s.contributions(slip));
    assertEquals(0, new BigDecimal("7.50").compareTo(lines.get("TZPB/РАБ")), "0,50% от 1500");
    assertNull(lines.get("TZPB/ЛИЦЕ"), "ТЗПБ е ИЗЦЯЛО за сметка на работодателя (чл. 6 ал. 3 КСО)");
  }

  // ═══ 2 · ПОДЪТ И ТАВАНЪТ СЕ ПРИЛАГАТ ══════════════════════════════════════

  @Test
  @DisplayName("⚠️ Бруто ПОД МОД → базата се вдига до МОД; бруто НАД тавана → базата е таванът")
  void theFloorAndTheCapAreBothApplied() {
    // ── подът: бруто 500,00 при МОД 933,00 ──────────────────────────────────
    Service low = wired(contract(), new Builder());
    low.mod = BgMinIncomeResult.resolved(new BigDecimal("933.00"), "1", 3);
    BgPaySlip lowSlip = slip(low, contract(), "500.00");

    BgContributionResult lowResult = low.contributions(lowSlip);
    assertEquals(BgContributionResult.Status.RESOLVED, lowResult.getStatus());
    assertEquals(
        0,
        new BigDecimal("933.00").compareTo(low.seenRequest.getInsurableIncome()),
        "⚠️ Базата е МОД, не брутото. Осигуряване върху 500,00 при МОД 933,00 е точно тихото"
            + " занижаване, което подът съществува да спре — и то не изглежда като грешка никъде.");
    assertEquals(
        0,
        new BigDecimal("76.69").compareTo(byFundAndPayer(lowResult).get("PENSION/РАБ")),
        "8,22% от 933,00 = 76,6926 → 76,69");

    // ── таванът: бруто 5000,00 ─────────────────────────────────────────────
    Service high = wired(contract(), new Builder());
    BgPaySlip highSlip = slip(high, contract(), "5000.00");

    BgContributionResult highResult = high.contributions(highSlip);
    assertEquals(BgContributionResult.Status.RESOLVED, highResult.getStatus());
    assertEquals(
        0,
        CAP.compareTo(high.seenRequest.getInsurableIncome()),
        "базата е ограничена до MAX_INSURABLE_INCOME, а таванът НЕ се пропорционира");
    assertEquals(
        0,
        new BigDecimal("173.58").compareTo(byFundAndPayer(highResult).get("PENSION/РАБ")),
        "8,22% от 2111,64 = 173,5768 → 173,58");

    // ── и законовият праг (МРЗ / мин. за СОЛ) минава по същия път ──────────
    Service statutory = wired(contract(), new Builder());
    statutory.mod = BgMinIncomeResult.notApplicable("вид осигурен: 05");
    statutory.statutoryFloor = new BigDecimal("620.20");
    BgPaySlip statutorySlip = slip(statutory, contract(), "400.00");

    statutory.contributions(statutorySlip);
    assertEquals(
        0,
        new BigDecimal("620.20").compareTo(statutory.seenRequest.getInsurableIncome()),
        "⚠️ NOT_APPLICABLE НЕ Е отказ: за кодове 05, 06, 12, 22, 24 и 92 МОД не важи и подът идва"
            + " от bgFloorType. Блокиране тук би спряло всеки фиш на държавен служител.");
  }

  // ═══ 3 · ОТКАЗЪТ СЕ ЗАДЕЙСТВА ═════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ЧЕРВЕНО · договор без вид осигурен → ОТКАЗ, и confirm() НЕ потвърждава фиша")
  void aContractWithoutTheInsuredTypeRefusesAndBlocksConfirm() {
    EmploymentContract bare = contract();
    bare.setBgInsuredTypeCode(null);

    Service s = wired(bare, new Builder());
    BgPaySlip slip = slip(s, bare, "1500.00");

    BgContributionResult r = s.contributions(slip);
    assertEquals(
        BgContributionResult.Status.INPUT_NOT_ASSEMBLED,
        r.getStatus(),
        "⚠️ НЕ NO_CONTRIBUTIONS_FOUND: каталогът изобщо не е питан. Второто би пратило читателя да"
            + " поправя seed-а вместо договора.");
    assertNull(s.seenRequest, "резолверът НЕ бива да е питан изобщо");
    assertEquals(
        BgPayrollInputBuilder.REFUSALS.get("insuredTypeCode"),
        r.getDetail(),
        "причината идва ДОСЛОВНО от REFUSALS, не преразказана");

    String refusal = s.contributionRefusal(slip);
    assertNotNull(refusal, "отказът трябва да проговори");
    assertTrue(refusal.contains("01 НЕ Е ПОДРАЗБИРАНЕ"), refusal);

    // ── и потвърждаването СПИРА ────────────────────────────────────────────
    // Режимът — както „Преизчисли“ би го замразил; без него confirm() спира по-рано и по друга
    // причина (BgPayrollRegime#unfrozenRefusal).
    s.freezeRegime(slip);
    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(slip));
    assertTrue(e.getMessage().contains("Осигуровките не са изчислени докрай"), e.getMessage());
    assertTrue(e.getMessage().contains("EmploymentContract.bgInsuredTypeCode"), e.getMessage());
    assertEquals(
        BgPaySlipResolver.DRAFT,
        slip.getStatusSelect(),
        "⚠️ Фишът остава ЧЕРНОВА. Потвърден фиш без осигуровки е декларирано число, което никой не"
            + " е сметнал — точно затова отказът е ШУМЕН, а не подразбиране на нула.");
  }

  @Test
  @DisplayName("⚠️ ЧЕРВЕНО · непълно време БЕЗ отчет → ОТКАЗ по пропорцията, не сметната база")
  void partTimeWithoutATimesheetRefuses() {
    EmploymentContract partTime = contract();
    partTime.setBgHoursPerDay(new BigDecimal("4.00"));

    Builder noTimesheet = new Builder();
    noTimesheet.hoursPerDay = null; // ОТЧЕТ НЯМА — не „нула часа“

    Service s = wired(partTime, noTimesheet);
    BgPaySlip slip = slip(s, partTime, "800.00");

    BgContributionResult r = s.contributions(slip);
    assertEquals(BgContributionResult.Status.INPUT_NOT_ASSEMBLED, r.getStatus());
    assertNull(s.seenRequest, "резолверът НЕ бива да е питан без разпознат режим");
    assertTrue(r.getDetail().contains("работното време не е определено"), r.getDetail());
    assertTrue(r.getDetail().contains("ОТРАБОТЕНИ ЧАСОВЕ"), r.getDetail());

    // ⚠️ КОНТРОЛАТА: същият непълен договор С отчет минава. Без нея тестът щеше да мине и с
    // услуга, която отказва на ВСЕКИ непълен договор.
    Builder withTimesheet = new Builder();
    withTimesheet.hoursPerDay = "4.00"; // 21 дни × 4 ч = 84 от 168 законоустановени
    Service ok = wired(partTime, withTimesheet);
    BgPaySlip okSlip = slip(ok, partTime, "800.00");
    assertEquals(BgContributionResult.Status.RESOLVED, ok.contributions(okSlip).getStatus());
    assertEquals(
        0,
        new BigDecimal("800.00").compareTo(ok.seenRequest.getInsurableIncome()),
        "84 от 168 законоустановени часа — базата е брутото, защото то е над свития праг");
  }

  // ═══ ДУПКАТА В SEED-А, КОЯТО НАВЪРЗВАНЕТО ИЗВАДИ НАЯВЕ — ВЕЧЕ ЗАТВОРЕНА ══════════════════════

  @Test
  @DisplayName("⚠️ СВИДЕТЕЛ · морско лице (92) излиза СЪС здравна вноска по ЗЗО чл. 40 ал. 1 т. 2а")
  void theSeafarerSlipComesOutWithItsHealthContribution() {
    // ⚠️ Здравната вноска при код 92 се дължи по ЗЗО чл. 40 ал. 1 т. 2а. Чл. 4а ал. 1 КСО наистина
    // изброява само фондовете на ДОО и не урежда здравното — но тъкмо защото КСО не го урежда, то
    // се урежда от ЗЗО. Вж. manifest/seafarers-2026.yml, раздел поправено_по_код_92.
    EmploymentContract seafarer = contract();
    seafarer.setBgInsuredTypeCode("92");

    Service s = wired(seafarer, new Builder());
    s.rows = seedRows("92", "3", "WITH_UPF", "ANY", "NON_TEACHER");
    // Чл. 4а ал. 1 КСО: доходът е ИЗБРАН между минималния и максималния за самоосигуряващите се.
    s.mod = BgMinIncomeResult.notApplicable("вид осигурен: 92");
    s.statutoryFloor = new BigDecimal("620.20"); // MIN_INCOME_SOL от 01.08.2026
    s.prorates = false; // 92 носи bgZeroWorkTime = true
    s.tzpb = new BigDecimal("0.5000"); // ставката по КИД, която НЕ бива да се приложи

    BgPaySlip slip = slip(s, seafarer, "400.00");
    BgContributionResult r = s.contributions(slip);

    assertEquals(
        BgContributionResult.Status.RESOLVED, r.getStatus(), String.valueOf(r.getDetail()));
    assertEquals(
        0,
        new BigDecimal("620.20").compareTo(s.seenRequest.getInsurableIncome()),
        "прагът за СОЛ се дължи ЦЯЛ — морското лице няма работно време, на което да е част");

    Map<String, BigDecimal> lines = byFundAndPayer(r);

    // ── ДУПКАТА, ЗАТВОРЕНА ────────────────────────────────────────────────
    assertEquals(
        0,
        new BigDecimal("49.62").compareTo(lines.get("HEALTH/ЛИЦЕ")),
        "⚠️ 8,00% от 620,20 по ЗЗО чл. 40 ал. 1 т. 2а. Seed-ът носи реда: IC-147-2026 за трета"
            + " категория, плюс IC-148/149 за първа и втора.");
    assertNull(
        lines.get("HEALTH/РАБ"),
        "⚠️ Работодателят не плаща ЗОВ: ставката му на реда е 0,0000 с вид"
            + " STRUCTURAL_ZERO, защото ЗЗО чл. 40 ал. 1 т. 2а праща вноската ИЗЦЯЛО за сметка на"
            + " морското лице, а чл. 4а ал. 7 КСО дава на работодателя само УДЪРЖАНЕТО.");

    // ── и второто следствие: работодателят не плаща НИЩО ───────────────────
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(r.totalEmployer()),
        "чл. 4а ал. 1 КСО — вноските са ИЗЦЯЛО за сметка на лицето; чл. 4а ал. 7 казва само че"
            + " работодателят ги УДЪРЖА и ВНАСЯ, а удържането не е разход за него");

    assertEquals(
        Arrays.asList("PENSION/ЛИЦЕ", "UPF/ЛИЦЕ", "OZM/ЛИЦЕ", "TZPB/ЛИЦЕ", "HEALTH/ЛИЦЕ"),
        new ArrayList<>(lines.keySet()),
        "пет реда, всичките на лицето. ЗОВ е ПОСЛЕДЕН, защото редът му е последен в seed файла и"
            + " редът на редовете се пази — не защото е по-малко дължим. ⚠️ БЕЗРАБОТИЦА все още"
            + " няма: редът ѝ носи обхват ART4A_AL2 и излиза от подразбирането, защото изборът по"
            + " чл. 4а ал. 2 не е упражнен — това е ВЯРНО.");

    assertEquals(
        0, new BigDecimal("91.79").compareTo(lines.get("PENSION/ЛИЦЕ")), "14,80% от 620,20");
    assertEquals(0, new BigDecimal("31.01").compareTo(lines.get("UPF/ЛИЦЕ")), "5,00% от 620,20");
    assertEquals(0, new BigDecimal("21.71").compareTo(lines.get("OZM/ЛИЦЕ")), "3,50% от 620,20");
    assertEquals(
        0,
        new BigDecimal("6.82").compareTo(lines.get("TZPB/ЛИЦЕ")),
        "⚠️ 1,10% ПЛОСКО от собствения ред на 92, а НЕ 0,50% по КИД от заявката: ред в набора"
            + " ПЕЧЕЛИ пред ставката в заявката, инак ТЗПБ щеше да се начисли два пъти");
    assertEquals(
        0,
        new BigDecimal("200.95").compareTo(r.totalEmployee()),
        "32,40% от 620,20: 14,80 + 5,00 + 3,50 + 1,10 + 8,00 = 32,40, тоест 91,79 + 31,01 + 21,71"
            + " + 6,82 + 49,62 = 200,95");
  }

  // ═══ 3а · РАБОТНО-ВРЕМЕВИЯТ ГЕЙТ Е УСЛОВЕН, НЕ БЕЗУСЛОВЕН ═════════════════

  @Test
  @DisplayName(
      "⚠️ СВИДЕТЕЛ · морско лице (92) с НЕПОПЪЛНЕНИ часове по договора → фишът пак се смята")
  void theSeafarerSlipIsNotBlockedByAnUnpopulatedContractWorkTime() {
    // ⚠️ РАЗЛИКАТА С ТЕСТА ОТГОРЕ Е ЕДНО ПОЛЕ. Горният минава зелен, защото фикстурата contract()
    // носи 8,00 часа на ден — режимът се разпознава и отказ няма. ВСЕКИ ЗАСЕТ ДОГОВОР обаче има
    // bg_hours_per_day = 0.00, което сглобителят чете като „не знам“: режимът става UNKNOWN и
    // decide() отказва. Голо if (workTime.isRefused()) би спряло веригата преди резолвера, тоест
    // реален фиш на морско лице би бил блокиран, докато тестът за код 92 свети зелено.
    EmploymentContract seafarer = contract();
    seafarer.setBgInsuredTypeCode("92");
    seafarer.setBgHoursPerDay(new BigDecimal("0.00")); // както е в базата: непопълнено
    seafarer.setBgDaysPerWeek(new BigDecimal("0.00"));

    Service s = wired(seafarer, new Builder());
    s.rows = seedRows("92", "3", "WITH_UPF", "ANY", "NON_TEACHER");
    s.mod = BgMinIncomeResult.notApplicable("вид осигурен: 92");
    s.statutoryFloor = new BigDecimal("620.20"); // MIN_INCOME_SOL от 01.08.2026
    s.prorates = false; // 92 носи bgZeroWorkTime = true
    s.tzpb = new BigDecimal("0.5000");

    // Котвата: режимът НАИСТИНА е неразпознат за този договор — инак тестът щеше да проверява
    // фикстура с попълнени часове и да мълчи за блокирания фиш.
    BgPayrollWorkTime.Decision decision =
        BgPayrollWorkTime.decide(new Builder().build(seafarer, FROM, TO));
    assertEquals(BgPayrollWorkTime.Regime.UNKNOWN, decision.regime);
    assertTrue(decision.isRefused(), "котвата: 0,00 часа на ден трябва да е ОТКАЗ");

    BgPaySlip slip = slip(s, seafarer, "400.00");
    BgContributionResult r = s.contributions(slip);

    assertEquals(
        BgContributionResult.Status.RESOLVED,
        r.getStatus(),
        "⚠️ веригата не бива да спира на INPUT_NOT_ASSEMBLED заради непопълнено bgHoursPerDay — при"
            + " вид осигурен, чийто праг НЕ се пропорционира. Отказът пази избор между ал. 3 и"
            + " ал. 4 по НЕВД чл. 1, който за bgZeroWorkTime изобщо не се прави. Детайл: "
            + r.getDetail());
    assertNotNull(s.seenRequest, "резолверът трябва да Е питан");
    assertEquals(
        0,
        new BigDecimal("620.20").compareTo(s.seenRequest.getInsurableIncome()),
        "прагът за СОЛ се дължи ЦЯЛ — работното време не влиза в сметката с нито едно число, тоест"
            + " неразпознатият режим не може да промени това число");

    Map<String, BigDecimal> lines = byFundAndPayer(r);
    assertEquals(
        Arrays.asList("PENSION/ЛИЦЕ", "UPF/ЛИЦЕ", "OZM/ЛИЦЕ", "TZPB/ЛИЦЕ", "HEALTH/ЛИЦЕ"),
        new ArrayList<>(lines.keySet()),
        "същите пет реда като при попълнен договор — гейтът не бива да мени НИЩО освен това дали се"
            + " стига до тях");
    assertEquals(
        0, new BigDecimal("91.79").compareTo(lines.get("PENSION/ЛИЦЕ")), "14,80% от 620,20");
    assertEquals(0, new BigDecimal("31.01").compareTo(lines.get("UPF/ЛИЦЕ")), "5,00% от 620,20");
    assertEquals(0, new BigDecimal("21.71").compareTo(lines.get("OZM/ЛИЦЕ")), "3,50% от 620,20");
    assertEquals(0, new BigDecimal("6.82").compareTo(lines.get("TZPB/ЛИЦЕ")), "1,10% от 620,20");
    assertEquals(
        0,
        new BigDecimal("49.62").compareTo(lines.get("HEALTH/ЛИЦЕ")),
        "8,00% от 620,20 по ЗЗО чл. 40 ал. 1 т. 2а");
    assertNull(
        lines.get("HEALTH/РАБ"),
        "⚠️ ЗОВ на работодателя е STRUCTURAL_ZERO — ЗЗО чл. 40 ал. 1 т. 2а праща вноската ИЗЦЯЛО за"
            + " сметка на морското лице");
    assertEquals(0, new BigDecimal("200.95").compareTo(r.totalEmployee()), "32,40% от 620,20");
    assertEquals(0, BigDecimal.ZERO.compareTo(r.totalEmployer()), "чл. 4а ал. 1 КСО");

    // ── и потвърждаването МИНАВА: няма отказ, който да го спре ──────────────
    assertNull(
        s.contributionRefusal(slip),
        "⚠️ Отказът е и пазач на confirm(): блокиран фиш на морско лице не би могъл да се потвърди"
            + " изобщо, тоест блокирането стига до края на процеса, не само до екрана.");
  }

  @Test
  @DisplayName("⚠️ РЕГРЕСИЯ · вид 01 със СЪЩИТЕ непопълнени часове → ВСЕ ОЩЕ отказва")
  void aProratingTypeWithTheSameUnpopulatedHoursStillRefuses() {
    // Единствената разлика с теста отгоре: видът осигурен пропорционира. Без това твърдение
    // гейтът би могъл да е махнат, а не условен.
    EmploymentContract employee = contract();
    employee.setBgHoursPerDay(new BigDecimal("0.00"));
    employee.setBgDaysPerWeek(new BigDecimal("0.00"));

    Service s = wired(employee, new Builder());
    s.rows = seedRows("01", "3", "WITH_UPF", "ANY", "NON_TEACHER");
    s.prorates = true; // 01 е трудово правоотношение — bgZeroWorkTime = false

    BgPaySlip slip = slip(s, employee, "1500.00");
    BgContributionResult r = s.contributions(slip);

    assertEquals(
        BgContributionResult.Status.INPUT_NOT_ASSEMBLED,
        r.getStatus(),
        "⚠️ За трудово правоотношение липсващият режим си е ВАЛИДЕН отказ и трябва да спира: ал. 4"
            + " (часове) и ал. 3 (дни) дават РАЗЛИЧНО число за един и същ месец, тоест избор по"
            + " подразбиране е праг наужким.");
    assertNull(s.seenRequest, "резолверът НЕ бива да е питан без разпознат режим");
    assertTrue(r.getDetail().contains("работното време не е определено"), r.getDetail());
    assertTrue(
        r.getDetail().contains(BgPayrollWorkTime.REFUSALS.get("unknownRegime")),
        "причината идва ДОСЛОВНО от REFUSALS: " + r.getDetail());
    assertTrue(r.getDetail().contains("bgHoursPerDay"), r.getDetail());

    assertNotNull(
        s.contributionRefusal(slip), "и confirm() трябва да си остане спрян за такъв фиш");
  }

  // ═══ 4 · МОД ШЕВЪТ ════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ minIncome() вече връща истински резултат — и блокиращият МОД спира confirm()")
  void theMinIncomeSeamReturnsARealResultAndBlocks() {
    Service s = wired(contract(), new Builder());
    BgPaySlip slip = slip(s, contract(), "1500.00");

    assertNotNull(s.minIncome(slip), "шевът за МОД пак е инертен");
    assertNull(s.minIncomeRefusal(slip), "NOT_APPLICABLE не е блокиращо и не поражда отказ");

    s.mod =
        BgMinIncomeResult.failure(
            BgMinIncomeResult.Status.MOD_ROW_NOT_FOUND, "ред 1 няма версия за 03.2026");
    String refusal = s.minIncomeRefusal(slip);
    assertNotNull(refusal, "блокиращ МОД трябва да проговори");
    assertTrue(refusal.contains("Няма минимален осигурителен доход за периода"), refusal);
    assertTrue(refusal.contains("MOD_ROW_NOT_FOUND"), refusal);

    // ⚠️ ПРИЧИНАТА, НЕ СЛЕДСТВИЕТО. Блокиращият МОД спира и осигуровките (базата не може да се
    // състави без него), тоест ДВА отказа са готови да се обадят. confirm() пита МОД пръв нарочно:
    // „няма МОД за периода“ праща оператора на реда по Приложение № 1, а „осигуровките не са
    // изчислени докрай“ би го пратило да гледа ставки, които са наред.
    s.freezeRegime(slip);
    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(slip));
    assertTrue(e.getMessage().contains("Липсващ МОД НЕ значи нула"), e.getMessage());
    assertTrue(
        e.getMessage().contains("MOD_ROW_NOT_FOUND"),
        "отказът трябва да назове СЪСТОЯНИЕТО, не само че нещо липсва: " + e.getMessage());
    assertEquals(BgPaySlipResolver.DRAFT, slip.getStatusSelect());
  }
}

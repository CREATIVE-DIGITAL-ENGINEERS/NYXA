package com.axelor.apps.bglocalization.service;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.bglocalization.db.BgAbsenceType;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgInsuranceContribution;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.service.payroll.BgArt177LeaveService;
import com.axelor.apps.bglocalization.service.payroll.BgArt49SettlementService;
import com.axelor.apps.bglocalization.service.payroll.BgCompensationLineService;
import com.axelor.apps.bglocalization.service.payroll.BgContributionLineService;
import com.axelor.apps.bglocalization.service.payroll.BgEarningsService;
import com.axelor.apps.bglocalization.service.payroll.BgIncomeTaxService;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInput;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInputBuilder;
import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveService;
import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveServiceOnDeclaredStage;
import com.axelor.apps.bglocalization.service.payroll.BgUnpaidLeaveHealthService;
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

/**
 * ИСТИНСКИЯТ {@link BgPaySlipService} С ИСТИНСКИТЕ ГЕНЕРАТОРИ — подменено е само четенето от
 * базата.
 *
 * <p>Речникът с пера, ставките на вноските, параметрите (данък, таван, процент за клас) и видът
 * осигурен идват от СИЙДА ({@link BgSeed}), не от заявка. Всичко останало — {@code prepare()},
 * шестте генератора, карвът на ръчните постоянни пера, резолверът на вноските, сборовете и
 * потвърждаването — е кодът от производството. Образецът е {@code BgPaySlipContributionWiringTest},
 * само че там генераторът на начисленията е изключен, а тук е пуснат: точно неговият карв се
 * проверява.
 *
 * <p>⚠️ Подменени са ЕДИНСТВЕНО шевовете, които отиват до базата (repository, {@code JPA.em()},
 * {@code Beans.get}) — изброени поименно по-долу, всеки със своя причина. Генератор, чиято логика е
 * подменена, тук няма.
 */
class BgSeededPaySlipService extends BgPaySlipService {

  static final LocalDate FROM = LocalDate.of(2026, 3, 1);
  static final LocalDate TO = LocalDate.of(2026, 3, 31);

  /** 03.03.2026 е вторник; с него март 2026 има 21 работни дни. */
  static final int WORKING_DAYS = 21;

  // ═══ СИЙДЪТ ═══════════════════════════════════════════════════════════════

  private static final List<String> TYPE_FILES =
      Arrays.asList(
          "bg_pay_slip_line_type.csv",
          "bg_pay_slip_line_type_contribution.csv",
          "bg_pay_slip_line_type_compensation.csv",
          "bg_pay_slip_line_type_social.csv");

  /** Всички типове пера от четирите seed файла, по код. */
  static final Map<String, BgPaySlipLineType> TYPES = loadTypes();

  private static Map<String, BgPaySlipLineType> loadTypes() {
    Map<String, BgPaySlipLineType> out = new LinkedHashMap<>();
    Map<String, BgContributionFund> funds = new LinkedHashMap<>();
    for (String file : TYPE_FILES) {
      List<String> h = BgSeed.quotedHeader(file);
      for (String[] r : BgSeed.quotedRows(file)) {
        if (h.contains("active") && "false".equals(BgSeed.col(h, r, "active"))) {
          continue;
        }
        BgPaySlipLineType t = new BgPaySlipLineType();
        t.setCode(BgSeed.col(h, r, "code"));
        t.setNumber(Integer.valueOf(BgSeed.col(h, r, "number")));
        t.setName(BgSeed.col(h, r, "name"));
        t.setBgSign(BgSeed.col(h, r, "bgSign"));
        t.setBgIsPermanentCharacter(flag(h, r, "bgIsPermanentCharacter"));
        t.setBgIsTaxable(flag(h, r, "bgIsTaxable"));
        t.setBgIsInsuranceIncome(flag(h, r, "bgIsInsuranceIncome"));
        t.setBgIsArt17Base(flag(h, r, "bgIsArt17Base"));
        t.setBgFormsGrossWage(flag(h, r, "bgFormsGrossWage"));
        t.setBgCalculationBase(BgSeed.col(h, r, "bgCalculationBase"));
        t.setActive(true);
        if (h.contains("bgContributionFund")) {
          String code = BgSeed.col(h, r, "bgContributionFund");
          if (!code.isEmpty()) {
            t.setBgContributionFund(
                funds.computeIfAbsent(
                    code,
                    c -> {
                      BgContributionFund f = new BgContributionFund();
                      f.setCode(c);
                      return f;
                    }));
          }
        }
        out.put(t.getCode(), t);
      }
    }
    return out;
  }

  private static boolean flag(List<String> h, String[] r, String name) {
    return h.contains(name) && "true".equals(BgSeed.col(h, r, name));
  }

  /** Типът по код — фикстурата пада шумно, ако сийдът го няма. */
  static BgPaySlipLineType type(String code) {
    BgPaySlipLineType t = TYPES.get(code);
    if (t == null) {
      throw new IllegalStateException("сийдът няма тип перо „" + code + "“");
    }
    return t;
  }

  /** Стойността на параметъра от {@code bg_payroll_parameter.csv}, в сила за датата. */
  static BigDecimal param(String code, LocalDate date) {
    if (date == null) {
      return null;
    }
    List<String> h = BgSeed.quotedHeader("bg_payroll_parameter.csv");
    for (String[] r : BgSeed.quotedRows("bg_payroll_parameter.csv")) {
      if (!code.equals(BgSeed.col(h, r, "code"))) {
        continue;
      }
      LocalDate from = LocalDate.parse(BgSeed.col(h, r, "validFrom"));
      String to = BgSeed.col(h, r, "validTo");
      if (!from.isAfter(date) && (to.isEmpty() || !LocalDate.parse(to).isBefore(date))) {
        return new BigDecimal(BgSeed.col(h, r, "paramValue"));
      }
    }
    return null;
  }

  /** Видът осигурен от {@code bg_insured_type.csv}, в сила за датата. */
  static BgInsuredType insuredType(String code, LocalDate date) {
    List<String> h = BgSeed.quotedHeader("bg_insured_type.csv");
    for (String[] r : BgSeed.quotedRows("bg_insured_type.csv")) {
      if (!code.equals(BgSeed.col(h, r, "code"))) {
        continue;
      }
      LocalDate from = LocalDate.parse(BgSeed.col(h, r, "validFrom"));
      String to = h.contains("validTo") ? BgSeed.col(h, r, "validTo") : "";
      if (from.isAfter(date) || (!to.isEmpty() && LocalDate.parse(to).isBefore(date))) {
        continue;
      }
      BgInsuredType t = new BgInsuredType();
      t.setCode(code);
      t.setBgIsSeafarer(flag(h, r, "bgIsSeafarer"));
      t.setBgZeroWorkTime(flag(h, r, "bgZeroWorkTime"));
      t.setBgEntitledToLengthOfServiceBonus(flag(h, r, "bgEntitledToLengthOfServiceBonus"));
      return t;
    }
    return null;
  }

  /** Официалните празници от {@code bg_eventsPlanningLine.csv} — производственият календар. */
  static final Set<LocalDate> HOLIDAYS = loadHolidays();

  private static Set<LocalDate> loadHolidays() {
    Set<LocalDate> out = new java.util.TreeSet<>();
    List<String> h = BgSeed.quotedHeader("bg_eventsPlanningLine.csv");
    for (String[] r : BgSeed.quotedRows("bg_eventsPlanningLine.csv")) {
      out.add(LocalDate.parse(BgSeed.col(h, r, "date")));
    }
    return out;
  }

  /**
   * РАБОТНИТЕ ДНИ ПО КАЛЕНДАРА — понеделник до петък без официалните празници от сийда. Март 2026 е
   * 21 (03.03), юли — 23, септември — 20 (07.09 и 22.09).
   */
  static int workingDays(LocalDate from, LocalDate to) {
    int n = 0;
    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
      if (isWorkingDay(d)) {
        n++;
      }
    }
    return n;
  }

  static boolean isWorkingDay(LocalDate d) {
    return d.getDayOfWeek() != java.time.DayOfWeek.SATURDAY
        && d.getDayOfWeek() != java.time.DayOfWeek.SUNDAY
        && !HOLIDAYS.contains(d);
  }

  /**
   * Видът отсъствие от {@code bg_absence_type.csv} — платецът и процентът, по които разбивът по чл.
   * 40 ал. 5 КСО смята. ⚠️ Файлът е със запетаи, а не с {@code ;}: чете се само редът с кода и само
   * ако броят колони съвпада със заглавния — тоест кавичка със запетая в него пада шумно.
   */
  static BgAbsenceType absenceTypeOf(String code) {
    try {
      List<String> lines =
          java.nio.file.Files.readAllLines(
              BgSeed.path("bg_absence_type.csv", "en"), java.nio.charset.StandardCharsets.UTF_8);
      List<String> h = Arrays.asList(lines.get(0).split(",", -1));
      for (String line : lines.subList(1, lines.size())) {
        String[] r = line.split(",", -1);
        if (r.length != h.size() || !code.equals(r[h.indexOf("code")])) {
          continue;
        }
        BgAbsenceType t = new BgAbsenceType();
        t.setCode(code);
        t.setPayer(r[h.indexOf("payer")]);
        t.setCompensationPct(new BigDecimal(r[h.indexOf("compensationPct")]));
        // A4: допуска ли се видът на болничен лист — от сийда, като платеца и процента.
        t.setRequiresSickNote(Boolean.valueOf(r[h.indexOf("requiresSickNote")].trim()));
        t.setCountsAsInsuranceService(
            Boolean.valueOf(r[h.indexOf("countsAsInsuranceService")].trim()));
        return t;
      }
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
    throw new IllegalStateException("сийдът няма вид отсъствие „" + code + "“");
  }

  /**
   * Редовете на вноските за един вид осигурен и една кохорта, от сийда — дословно филтърът на
   * {@code BgPaySlipContributionWiringTest#seedRows}.
   */
  static List<BgInsuranceContribution> contributionRows(String cohort) {
    List<String> h = BgSeed.quotedHeader("bg_insurance_contribution.csv");
    List<BgInsuranceContribution> out = new ArrayList<>();
    for (String[] r : BgSeed.quotedRows("bg_insurance_contribution.csv")) {
      if (!"01".equals(BgSeed.col(h, r, "bgInsuredTypeCode"))
          || !"3".equals(BgSeed.col(h, r, "laborCategory"))) {
        continue;
      }
      String upf = BgSeed.col(h, r, "bgUpfRegime");
      String sol = BgSeed.col(h, r, "bgSolScope");
      String tea = BgSeed.col(h, r, "bgTeacherScope");
      if (!"ANY".equals(upf) && !upf.equals(cohort)) {
        continue;
      }
      if (!"ANY".equals(sol) || (!"ANY".equals(tea) && !"NON_TEACHER".equals(tea))) {
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

  // ═══ ФИКСТУРИТЕ ═══════════════════════════════════════════════════════════

  /**
   * Нает по чл. 4 ал. 1 т. 1 КСО: вид 01, трета категория, роден след 1959, пълно време, 1500,00.
   *
   * <p>⚠️ Договорът е от 01.09.2025: признатият стаж в март 2026 е под година, тоест класът е
   * законна нула и брутото е самата заплата. Така ръчно сметнатите числа в свидетелите остават
   * четими — основна плюс бонус, без трети член.
   */
  static EmploymentContract contract() {
    Employee e = new Employee();
    e.setId(7L);
    e.setBirthDate(LocalDate.of(1985, 4, 12));
    e.setBgLaborCategory("3");
    e.setBgBornAfter1959(true);
    e.setBgIsPensioner(false);

    EmploymentContract c = new EmploymentContract();
    c.setId(42L);
    c.setEmployee(e);
    c.setStartDate(LocalDate.of(2025, 9, 1));
    c.setBgBaseSalary(new BigDecimal("1500.00"));
    c.setBgHoursPerDay(new BigDecimal("8.00"));
    c.setBgWorkTimeMode("1");
    c.setBgIsTeacherPosition(false);
    c.setBgInsuredTypeCode("01");
    return c;
  }

  static Period march() {
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

  /** Празна чернова за март с договора — както я оставя формата преди първото пресмятане. */
  static BgPaySlip draft(EmploymentContract contract) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setEmploymentContract(contract);
    slip.setPeriod(march());
    slip.setBgPaySlipLineList(new ArrayList<>());
    return slip;
  }

  /** Сглобител без база — дословно този на {@code BgPaySlipContributionWiringTest}. */
  static final class Builder extends BgPayrollInputBuilder {
    /** Заявките за отпуск — редовете на LEAVE_ROWS_JPQL, подредени като в заявката. */
    final List<Object[]> leave = new ArrayList<>();

    /** Болничните епизоди — както ги дава заявката към листовете. */
    final List<BgPayrollInput.SickLeaveEpisode> episodes = new ArrayList<>();

    @Override
    protected BgWorkingDayService.Count workingDays(LocalDate from, LocalDate to) {
      return new BgWorkingDayService.Count(
          BgSeededPaySlipService.workingDays(from, to), Collections.emptyList());
    }

    /** Осем часа за всеки работен ден по календара — пълно време, без отсъствие. */
    @Override
    protected Map<LocalDate, BigDecimal> workedHoursByDate(
        Employee employee, LocalDate from, LocalDate to) {
      Map<LocalDate, BigDecimal> out = new LinkedHashMap<>();
      for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
        if (isWorkingDay(d)) {
          out.put(d, new BigDecimal("8.00"));
        }
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

    @Override
    protected List<BgPayrollInput.SickLeaveEpisode> sickLeaveEpisodesOf(
        EmploymentContract contract, LocalDate from, LocalDate to) {
      return new ArrayList<>(episodes);
    }

    @Override
    protected List<Object[]> leaveRows(Employee employee, LocalDate from, LocalDate to) {
      return new ArrayList<>(leave);
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

  // ═══ УСЛУГАТА ═════════════════════════════════════════════════════════════

  List<BgInsuranceContribution> rows = contributionRows("WITH_UPF");

  /** Заявката, с която резолверът на вноските наистина е бил питан — за да се види РЕЖИМЪТ ѝ. */
  BgContributionRequest seenRequest;

  /** Колко пъти е записан фишът — свидетелите на „confirm() не записва нищо преди проверките“. */
  int saves;

  final BgEarningsService earnings = new SeededEarnings(this);
  final BgContributionLineService contributionLines = new SeededContributionLines(this);
  final BgIncomeTaxService incomeTax = new SeededIncomeTax(this);
  final SeededSickLeave sickLeave = new SeededSickLeave(this);
  final BgCompensationLineService compensationLines = new SeededCompensationLines(this);
  final BgArt177LeaveService art177 = new SeededArt177(this);
  final SeededUnpaidHealth unpaidHealth = new SeededUnpaidHealth(this);
  final BgArt49SettlementService art49 = new SeededArt49(this);

  static BgSeededPaySlipService wired(EmploymentContract contract) {
    return new BgSeededPaySlipService();
  }

  // ── шевовете към базата: вход, параметри, речник ─────────────────────────

  /**
   * ⚠️ ИСТИНСКИЯТ payrollInput — подменен е само сглобителят (без база) и прочитът на договора.
   * Така входът минава през замразения режим на фиша точно както в производството, а не е подаден
   * готов отстрани.
   */
  @Override
  protected BgPayrollInputBuilder inputBuilder() {
    return builder;
  }

  /** Сглобителят на харнеса — ЕДИН, за да могат свидетелите да му подадат отпуски и епизоди. */
  final Builder builder = new Builder();

  @Override
  protected EmploymentContract managedContract(BgPaySlip slip) {
    return slip.getEmploymentContract();
  }

  @Override
  protected BigDecimal payrollParameter(String code, LocalDate date) {
    return param(code, date);
  }

  @Override
  protected BgInsuredType insuredTypeFor(String insuredTypeCode, LocalDate date) {
    return insuredTypeCode == null ? null : insuredType(insuredTypeCode, date);
  }

  /** Огледало на BgInsurableIncomeService#proratesWithWorkTime — флагът на вида осигурен. */
  @Override
  protected boolean proratesWithWorkTime(BgPayrollInput in, BgPaySlip slip) {
    BgInsuredType t = insuredType(in.insuredTypeCode, FROM);
    return t != null && !Boolean.TRUE.equals(t.getBgZeroWorkTime());
  }

  /** МОД-ът е таблица по НКИД; фикстурата е над всеки под, тоест подът не участва. */
  @Override
  protected BgMinIncomeResult minIncomeFor(BgPayrollInput in, BgPaySlip slip) {
    return BgMinIncomeResult.notApplicable("фикстурата е над всеки под");
  }

  @Override
  protected BgMinIncomeResult minIncome(BgPaySlip slip) {
    return BgMinIncomeResult.notApplicable("фикстурата е над всеки под");
  }

  /**
   * ИСТИНСКАТА формула min(max(...), таван) — таванът е от сийда за ДАТАТА, която производственият
   * {@code insurableIncome} подава: копие за 07/2026 се осигурява до 2111,64, а не до днешния.
   */
  @Override
  protected BgInsurableIncomeResult insurableIncomeOn(
      BigDecimal gross,
      BgMinIncomeResult mod,
      BgPayrollInput in,
      BgWorkTimeFacts facts,
      LocalDate date) {
    return BgInsurableIncomeService.compute(
        gross, mod, null, true, param("MAX_INSURABLE_INCOME", date), date, facts);
  }

  @Override
  protected BigDecimal tzpbRate(BgPaySlip slip, LocalDate date) {
    return null;
  }

  /** ИСТИНСКИЯТ резолвер — подменено е само зареждането на редовете. */
  @Override
  protected BgContributionResult calculate(BgContributionRequest request) {
    seenRequest = request;
    return BgContributionResolver.resolve(rows, request);
  }

  @Override
  protected BgWorkingDayService.Count periodWorkingDays(BgPaySlip slip) {
    return new BgWorkingDayService.Count(
        workingDays(slip.getPeriod().getFromDate(), slip.getPeriod().getToDate()),
        Collections.emptyList());
  }

  @Override
  protected String otherSlipCarrying(BgCompensation doc, BgPaySlip slip) {
    return null;
  }

  @Override
  protected boolean declaredToNap(Period period) {
    return false;
  }

  @Override
  protected void save(BgPaySlip slip) {
    saves++;
  }

  @Override
  protected void saveCompensation(BgCompensation doc) {}

  /** Записите на периода и следата на корекцията — броят се, за да се види преходът. */
  int periodSaves;

  int adjustStarts;
  int adjustEnds;

  @Override
  protected void savePeriod(Period period) {
    periodSaves++;
  }

  @Override
  protected void logAdjustStart(Period period) {
    adjustStarts++;
  }

  @Override
  protected void logAdjustEnd(Period period) {
    adjustEnds++;
  }

  // ── генераторите: истински класове, на които е подменена само базата ────

  @Override
  protected BgEarningsService earningsService() {
    return earnings;
  }

  @Override
  protected BgContributionLineService contributionLineService() {
    return contributionLines;
  }

  @Override
  protected BgIncomeTaxService incomeTaxService() {
    return incomeTax;
  }

  @Override
  protected BgSickLeaveService sickLeaveService() {
    return sickLeave;
  }

  @Override
  protected BgCompensationLineService compensationLineService() {
    return compensationLines;
  }

  @Override
  protected BgArt177LeaveService art177LeaveService() {
    return art177;
  }

  @Override
  protected BgUnpaidLeaveHealthService unpaidLeaveHealthService() {
    return unpaidHealth;
  }

  @Override
  protected BgArt49SettlementService art49SettlementService() {
    return art49;
  }

  /** Начисленията: речникът и параметрите от сийда, извънредният труд по договор — няма. */
  static final class SeededEarnings extends BgEarningsService {
    private final BgSeededPaySlipService owner;

    SeededEarnings(BgSeededPaySlipService owner) {
      this.owner = owner;
    }

    @Override
    protected List<BgPaySlipLineType> lineTypes() {
      List<BgPaySlipLineType> out = new ArrayList<>();
      List<String> h = BgSeed.quotedHeader("bg_pay_slip_line_type.csv");
      for (String[] r : BgSeed.quotedRows("bg_pay_slip_line_type.csv")) {
        BgPaySlipLineType t = TYPES.get(BgSeed.col(h, r, "code"));
        if (t != null) {
          out.add(t);
        }
      }
      return out;
    }

    @Override
    protected BgPaySlipLineType lineType(String code) {
      return TYPES.get(code);
    }

    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
      return owner.lineFrom(type, amount);
    }

    @Override
    protected BigDecimal payrollParameter(String code, LocalDate date) {
      return param(code, date);
    }

    @Override
    protected Map<String, BigDecimal> agreedOvertimeUplift(BgPaySlip slip, BgPayrollInput in) {
      return Collections.emptyMap();
    }

    @Override
    protected Boolean seniorityEntitlement(BgPaySlip slip) {
      return owner.seniorityBonusEntitlement(slip);
    }
  }

  /** Вноските: типът перо по фонд и знак от сийда. */
  static final class SeededContributionLines extends BgContributionLineService {
    private final BgSeededPaySlipService owner;

    SeededContributionLines(BgSeededPaySlipService owner) {
      this.owner = owner;
    }

    @Override
    protected BgPaySlipLineType lineTypeFor(String fundCode, String sign) {
      for (BgPaySlipLineType t : TYPES.values()) {
        if (t.getBgContributionFund() != null
            && fundCode.equals(t.getBgContributionFund().getCode())
            && sign.equals(t.getBgSign())) {
          return t;
        }
      }
      return null;
    }

    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
      return owner.lineFrom(type, amount);
    }
  }

  /** Данъкът: типът перо от сийда. */
  static final class SeededIncomeTax extends BgIncomeTaxService {
    private final BgSeededPaySlipService owner;

    SeededIncomeTax(BgSeededPaySlipService owner) {
      this.owner = owner;
    }

    @Override
    protected BgPaySlipLineType lineType(String code) {
      return TYPES.get(code);
    }

    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
      return owner.lineFrom(type, amount);
    }
  }

  /** Болничният: във фикстурата няма лист; речникът е от сийда. */
  static final class SeededSickLeave extends BgSickLeaveServiceOnDeclaredStage {

    private final BgSeededPaySlipService owner;

    /** Болничните листове на фикстурата — по id, вместо от базата; там отиват редовете по т. 9. */
    final Map<Long, BgSickLeave> sheets = new LinkedHashMap<>();

    int sheetSaves;

    SeededSickLeave(BgSeededPaySlipService owner) {
      this.owner = owner;
    }

    @Override
    protected BgSickLeave sickLeaveById(Long id) {
      return id == null ? null : sheets.get(id);
    }

    @Override
    protected void saveSickLeave(BgSickLeave sickLeave) {
      sheetSaves++;
    }

    @Override
    protected BgPaySlipLineType lineType(String code) {
      return TYPES.get(code);
    }

    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
      return owner.lineFrom(type, amount);
    }

    @Override
    protected BigDecimal healthAbsenceBase(LocalDate date) {
      return param(PARAM_HEALTH_ABSENCE_BASE, date);
    }

    /** Базата на 195 — от СЪЩИЯ енджин на начисленията, с параметрите от сийда. */
    @Override
    protected BigDecimal monthlyPermanentPay(BgPaySlip slip, BgPayrollInput input) {
      return owner.earningsService().monthlyPermanentPay(slip, input);
    }

    /** Ставката на осигурителя за ЗО — от същите редове на сийда, по които се смятат вноските. */
    @Override
    protected BigDecimal healthEmployerRatePct(BgPayrollInput in, LocalDate date) {
      for (BgInsuranceContribution c : owner.rows) {
        if ("HEALTH".equals(c.getFundCode())) {
          return c.getEmployerRate();
        }
      }
      return null;
    }

    @Override
    protected Set<LocalDate> holidaysIn(LocalDate from, LocalDate to) {
      Set<LocalDate> out = new java.util.TreeSet<>();
      for (LocalDate d : HOLIDAYS) {
        if (!d.isBefore(from) && !d.isAfter(to)) {
          out.add(d);
        }
      }
      return out;
    }

    @Override
    protected BgAbsenceType absenceType(String code) {
      return code == null ? null : absenceTypeOf(code);
    }

    /** Перото 516 по ЗЗО чл. 40 ал. 1 т. 5 — през СЪЩИЯ генератор на 5xx на харнеса. */
    @Override
    protected void writeSpecialHealthBase(
        BgPaySlip slip, BigDecimal base, BigDecimal ratePercent, BigDecimal amount) {
      owner.contributionLines.upsertSpecialHealthBase(
          slip,
          com.axelor.apps.bglocalization.service.d1.BgD1ContributionAggregator.BASE_ART40_1_5,
          true,
          base,
          ratePercent,
          amount);
    }
  }

  /** Обезщетенията: във фикстурата няма документ; речникът е от сийда. */
  static final class SeededCompensationLines extends BgCompensationLineService {
    private final BgSeededPaySlipService owner;

    SeededCompensationLines(BgSeededPaySlipService owner) {
      this.owner = owner;
    }

    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
      return owner.lineFrom(type, amount);
    }
  }

  /** Перото по чл. 177: без дни отпуск; енджинът за начисления е същият като на фиша. */
  static final class SeededArt177 extends BgArt177LeaveService {
    private final BgSeededPaySlipService owner;

    SeededArt177(BgSeededPaySlipService owner) {
      this.owner = owner;
    }

    @Override
    protected BgEarningsService earningsService() {
      return owner.earnings;
    }

    @Override
    protected BgPaySlipService paySlipService() {
      return owner;
    }

    @Override
    protected BgPaySlipLineType lineType() {
      return TYPES.get("pl_otpusk_177");
    }
  }

  /** Неплатеният отпуск: във фикстурата няма заявка, тоест и правило за платеца. */
  static final class SeededUnpaidHealth extends BgUnpaidLeaveHealthService {
    private final BgSeededPaySlipService owner;

    /** Правилата на ползваните неплатени отпуски — празно по подразбиране, тоест без вноска. */
    final List<Object> rules = new ArrayList<>();

    SeededUnpaidHealth(BgSeededPaySlipService owner) {
      this.owner = owner;
    }

    @Override
    protected List<Object> unpaidLeaveRules(Employee employee, LocalDate from, LocalDate to) {
      return new ArrayList<>(rules);
    }

    @Override
    protected BigDecimal baseFor(LocalDate date) {
      return param(PARAM_BASE, date);
    }

    /** ЦЯЛАТА ставка на ЗОВ — осигурител плюс лице, от същите редове на сийда. */
    @Override
    protected BigDecimal totalHealthRate(BgPayrollInput input, LocalDate date) {
      for (BgInsuranceContribution c : owner.rows) {
        if ("HEALTH".equals(c.getFundCode())) {
          return c.getEmployerRate().add(c.getEmployeeRate());
        }
      }
      return null;
    }

    /** Перото „само здравно“ — през СЪЩИЯ генератор на 5xx на харнеса. */
    @Override
    protected void writeHealthLine(
        BgPaySlip slip,
        boolean employerBorne,
        BigDecimal proratedBase,
        BigDecimal ratePercent,
        BigDecimal amount) {
      owner.contributionLines.upsertSpecialHealthBase(
          slip,
          com.axelor.apps.bglocalization.service.d1.BgD1ContributionAggregator.BASE_HEALTH_ONLY,
          employerBorne,
          proratedBase,
          ratePercent,
          amount);
    }
  }

  /** Изравняването по чл. 49 е само на януарския фиш; речникът е от сийда. */
  static final class SeededArt49 extends BgArt49SettlementService {
    private final BgSeededPaySlipService owner;

    SeededArt49(BgSeededPaySlipService owner) {
      this.owner = owner;
    }

    @Override
    protected BgPaySlipLineType lineType(String code) {
      return TYPES.get(code);
    }

    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
      return owner.lineFrom(type, amount);
    }
  }
}

package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgInsuranceContribution;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgContributionRequest;
import com.axelor.apps.bglocalization.service.BgContributionResolver;
import com.axelor.apps.bglocalization.service.BgContributionResult;
import com.axelor.apps.bglocalization.service.BgInsurableIncomeResult;
import com.axelor.apps.bglocalization.service.BgInsurableIncomeService;
import com.axelor.apps.bglocalization.service.BgMinIncomeResult;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
import com.axelor.apps.bglocalization.service.BgWorkTimeFacts;
import com.axelor.apps.bglocalization.service.BgWorkingDayService;
import com.axelor.apps.bglocalization.service.d1.BgD1DayBuckets;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
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
 * ТРИТЕ РЕЖИМА НА КТ ЧЛ. 157 ДО ПАРИТЕ — от реда в речника до нетото на фиша.
 *
 * <p><b>Какво твърди този файл.</b> Ал. 3 на чл. 157 разпределя точките на ал. 1 по ТРИ режима на
 * заплащане. Речник с ЕДИН ред за всичките би им дал ЕДИН режим — заплатата на текущия месец, и два
 * от трите режима биха получили грешни пари. Този файл ги мери:
 *
 * <ul>
 *   <li><b>Режим 1</b> (ал. 3 т. 1 → ал. 1 т. 1–3, „според предвиденото в колективния трудов
 *       договор или по споразумение“) — заплатата на текущия месец е ЗАКОНЕН дефолт. Нула пари, и
 *       свидетелят го мери като БИТ ПО БИТ същия фиш.
 *   <li><b>Режим 2</b> (ал. 3 т. 2 → ал. 1 т. 5а и т. 6, плюс ал. 2, „от работодателя В РАЗМЕРА ПО
 *       ЧЛ. 177“) — дължимото е среднодневното от ПРЕДХОДНИЯ месец. При вдигната заплата текущата
 *       заплата НАДПЛАЩА.
 *   <li><b>Режим 3</b> (ал. 3 т. 3 → ал. 1 т. 4, 5 и 7, „според предвиденото в специалните закони“)
 *       — работодателят не дължи размера изобщо. ГПК чл. 168 дословно: „Свидетелят има право на
 *       възнаграждение и на разноски за явяването в съда … Възнаграждението и разноските се
 *       изплащат от ВНЕСЕНИЯ ДЕПОЗИТ“. Пълната дневна ставка е надплащане.
 * </ul>
 *
 * <p><b>⚠️ ФЛАГОВЕТЕ НЕ СЕ ПРЕПИСВАТ ТУК — ЧЕТАТ СЕ ОТ {@code bg_absence_type.csv}.</b> Заявката за
 * отпуск се сглобява от СИЙД РЕДА ({@link #leaveFromSeed}), тоест веригата е една: ред в речника →
 * {@code BgPayrollInputBuilder} → {@code prepare()} → пера, вноски, данък, нето. Преписани,
 * флаговете щяха да направят файла свидетел на себе си: сийдът можеше да се смени и тестът да
 * остане зелен.
 *
 * <p><b>⚠️ КЪДЕ Е РЪЧНАТА ПРЕДАВКА И ЗАЩО.</b> Сглобителят се вика ИСТИНСКИ, но само за отсъствията
 * — останалата му половина (вид осигурен, признат стаж, отчетено време, обезщетения) иска Postgres.
 * Числата на отпуска се пренасят в входа на фиша едно по едно в {@link #inputWithLeave}, без нито
 * една корекция. Същият компромис, който прави и {@code BgLeaveShapesMoneyTest}.
 *
 * <p>Тече без Postgres, без Guice и без Hibernate.
 */
class BgPaid157RegimeMoneyTest {

  // ═══ ПЕРИОДИТЕ ════════════════════════════════════════════════════════════

  private static final LocalDate NOV_FROM = LocalDate.of(2026, 11, 1);
  private static final LocalDate NOV_TO = LocalDate.of(2026, 11, 30);
  private static final LocalDate DEC_FROM = LocalDate.of(2026, 12, 1);
  private static final LocalDate DEC_TO = LocalDate.of(2026, 12, 31);

  /** Ноември 2026 — базовият месец по чл. 177 ал. 1. */
  private static final int NOV_WORKING_DAYS = 21;

  /** Декември 2026: 23 делнични дни, от които 24 и 25.12 са празници → 21 работни. */
  private static final int DEC_WORKING_DAYS = 22;

  private static final Set<LocalDate> HOLIDAYS = Collections.emptySet();

  // ═══ ЛИЦЕТО ═══════════════════════════════════════════════════════════════

  /**
   * ⚠️ ЗАПЛАТАТА Е ВДИГНАТА СПРЯМО БАЗОВИЯ МЕСЕЦ, И ТОВА НЕ Е ДЕКОР. Точно това прави режим 2
   * измерим: при непроменен състав на възнаграждението перото по чл. 177 е нула-сума ({@code
   * BgArt177LeaveMoneyTest}) и разликата между двата режима НЕ СЕ ВИЖДА от числото.
   */
  private static final BigDecimal SALARY = new BigDecimal("1200.00");

  /** 10 навършени години × 0,6 на сто = 6 на сто = 72,00 €. */
  private static final int SENIORITY_YEARS = 10;

  /** Начисленото по чл. 17 в базовия месец: 1000,00 основна + 60,00 клас, ПРЕДИ вдигането. */
  private static final BigDecimal BASE_MONTH_ART17 = new BigDecimal("1060.00");

  /** МОД за длъжността — под брутото във всеки от свидетелите, тоест не обвързва. */
  private static final BigDecimal MOD = new BigDecimal("1077.00");

  /** ЗБДОО, от 01.08.2026 г. — далеч над брутото, тоест таванът не обвързва. */
  private static final BigDecimal CAP = new BigDecimal("2300.0000");

  /** ТЗПБ по раздела на икономическата дейност. */
  private static final BigDecimal TZPB = new BigDecimal("0.5000");

  // ═══ СИЙДЪТ КАТО ИЗТОЧНИК ═════════════════════════════════════════════════

  /** Редът от {@code bg_absence_type.csv} като „колона → стойност“. Цепи се по ЗАПЕТАЯ. */
  private static Map<String, String> seedRow(String code) {
    try {
      List<String> lines =
          Files.readAllLines(
              Paths.get("src/main/resources/apps/init-data/en/bg_absence_type.csv"),
              StandardCharsets.UTF_8);
      List<String> header = Arrays.asList(lines.get(0).split(",", -1));
      for (int i = 1; i < lines.size(); i++) {
        String[] cells = lines.get(i).split(",", -1);
        if (cells.length == header.size() && code.equals(cells[header.indexOf("code")])) {
          Map<String, String> row = new LinkedHashMap<>();
          for (int c = 0; c < header.size(); c++) {
            row.put(header.get(c), cells[c]);
          }
          return row;
        }
      }
      fail("речникът няма ред „" + code + "“");
      return null;
    } catch (IOException e) {
      fail("не се чете bg_absence_type.csv: " + e.getMessage());
      return null;
    }
  }

  /**
   * Един ред на {@code BgPayrollInputBuilder#leaveRows}, СГЛОБЕН ОТ СИЙДА.
   *
   * <p>⚠️ Единайсетте колони идват от {@code LEAVE_ROWS_JPQL} и всяка от тях, освен датите и
   * количеството, се чете от реда в речника. Тоест смени ли се флаг в CSV-то, числата по-долу
   * мърдат и тестът пада — точно каквото искаме от свидетел на РАЗЦЕПВАНЕ.
   */
  private static Object[] leaveFromSeed(String code, String from, String to, String qty, int unit) {
    Map<String, String> r = seedRow(code);
    return new Object[] {
      LocalDate.parse(from).atStartOfDay(),
      LocalDate.parse(to).atTime(23, 59, 59),
      new BigDecimal(qty),
      unit,
      Integer.valueOf(r.get("category")),
      Boolean.valueOf(r.get("isPaid")),
      Long.valueOf(r.get("importId")),
      Boolean.valueOf(r.get("bgPaidUnderArt177")),
      r.get("d1Code"),
      Boolean.valueOf(r.get("countsAsInsuranceService")),
      r.get("healthContributionRule")
    };
  }

  // ═══ СГЛОБИТЕЛЯТ, С ПОДМЕНЕНИ САМО ЗАЯВКИТЕ КЪМ БАЗАТА ═══════════════════

  private static final class Builder extends BgPayrollInputBuilder {

    private List<Object[]> leaves = Collections.emptyList();

    @Override
    protected List<Object[]> leaveRows(Employee employee, LocalDate from, LocalDate to) {
      return leaves;
    }

    @Override
    protected Set<LocalDate> officialHolidays(LocalDate from, LocalDate to) {
      return HOLIDAYS;
    }

    @Override
    protected BgWorkingDayService.Count workingDays(LocalDate from, LocalDate to) {
      return null;
    }

    @Override
    protected WorkedTime workedTime(Employee employee, LocalDate from, LocalDate to) {
      return WorkedTime.unknown();
    }

    @Override
    protected Map<LocalDate, BigDecimal> workedHoursByDate(
        Employee employee, LocalDate from, LocalDate to) {
      return Collections.emptyMap();
    }

    @Override
    protected List<Long> compensationDocumentIds(
        EmploymentContract contract, LocalDate from, LocalDate to) {
      return Collections.emptyList();
    }

    @Override
    protected List<BgPayrollInput.SickLeaveEpisode> sickLeaveEpisodesOf(
        EmploymentContract contract, LocalDate from, LocalDate to) {
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

  /** Дните отпуск, преброени от ИСТИНСКИЯ сглобител върху сийд-редовете. */
  private static BgPayrollInput leaveOnly(String hoursPerDay, Object[]... rows) {
    Builder b = new Builder();
    b.leaves = Arrays.asList(rows);
    return b.build(contract(hoursPerDay), DEC_FROM, DEC_TO);
  }

  /**
   * Входът на фиша: отсъствията са ПРЕНЕСЕНИ от сглобителя, останалото се задава тук.
   *
   * <p>⚠️ Нито едно число на отпуска не се въвежда на ръка — пренася се това, което сглобителят е
   * преброил. Въведено тук, то щеше да заобиколи точно слоя „часове ÷ договорен ден“, който този
   * файл проверява.
   */
  private static BgPayrollInput inputWithLeave(BgPayrollInput leave) {
    return BgPayrollInput.builder()
        .insuredTypeCode("01")
        .laborCategory("3")
        .bornAfter1959(true)
        .periodFrom(DEC_FROM)
        .periodTo(DEC_TO)
        .parameterReferenceDate(DEC_FROM)
        .workingDaysInPeriod(DEC_WORKING_DAYS)
        .workedDays(DEC_WORKING_DAYS)
        .baseSalary(SALARY)
        .recognisedServiceStartDate(DEC_FROM.minusYears(SENIORITY_YEARS))
        .paidLeaveDays(leave.paidLeaveDays)
        .art177LeaveDays(leave.art177LeaveDays)
        .unpaidLeaveDays(leave.unpaidLeaveDays)
        .unpaidLeaveDaysInD1(leave.unpaidLeaveDaysInD1)
        .unpaidLeaveDaysUnderZzo(leave.unpaidLeaveDaysUnderZzo)
        .daysOutsideInsurance(leave.daysOutsideInsurance)
        .leaveDaysByD1Code(leave.leaveDaysByD1Code)
        .otherAbsenceDays(leave.otherAbsenceDays)
        .leaveDaysRefusal(leave.leaveDaysRefusal)
        .build();
  }

  // ═══ РЕЧНИЦИТЕ ОТ SEED-А ══════════════════════════════════════════════════

  private static List<BgPaySlipLineType> dictionary() {
    List<BgPaySlipLineType> out = new ArrayList<>();
    out.addAll(typesFrom("bg_pay_slip_line_type.csv", null));
    out.addAll(typesFrom("bg_pay_slip_line_type_contribution.csv", "bgContributionFund"));
    out.addAll(typesFrom("bg_pay_slip_line_type_compensation.csv", null));
    out.sort((a, b) -> Integer.compare(a.getNumber(), b.getNumber()));
    return out;
  }

  private static List<BgPaySlipLineType> typesFrom(String file, String refColumn) {
    List<String> h = BgSeed.quotedHeader(file);
    List<BgPaySlipLineType> out = new ArrayList<>();
    for (String[] r : BgSeed.quotedRows(file)) {
      BgPaySlipLineType t = new BgPaySlipLineType();
      t.setCode(BgSeed.col(h, r, "code"));
      t.setNumber(Integer.parseInt(BgSeed.col(h, r, "number").trim()));
      t.setName(BgSeed.col(h, r, "name"));
      t.setBgGroup(BgSeed.col(h, r, "bgGroup"));
      t.setBgSign(BgSeed.col(h, r, "bgSign"));
      t.setBgIsTaxable("true".equalsIgnoreCase(BgSeed.col(h, r, "bgIsTaxable")));
      t.setBgIsInsuranceIncome("true".equalsIgnoreCase(BgSeed.col(h, r, "bgIsInsuranceIncome")));
      t.setBgIsPermanentCharacter(
          "true".equalsIgnoreCase(BgSeed.col(h, r, "bgIsPermanentCharacter")));
      t.setBgCalculationBase(BgSeed.col(h, r, "bgCalculationBase"));
      t.setBgLegalBasis(BgSeed.col(h, r, "bgLegalBasis"));
      t.setSequence(Integer.parseInt(BgSeed.col(h, r, "sequence").trim()));
      t.setActive("true".equalsIgnoreCase(BgSeed.col(h, r, "active")));
      if (refColumn != null) {
        BgContributionFund fund = new BgContributionFund();
        fund.setCode(BgSeed.col(h, r, refColumn));
        fund.setName(fund.getCode());
        t.setBgContributionFund(fund);
      }
      out.add(t);
    }
    return out;
  }

  private static BigDecimal parameter(String code, LocalDate on) {
    List<String> h = BgSeed.quotedHeader("bg_payroll_parameter.csv");
    BigDecimal best = null;
    LocalDate bestFrom = null;
    for (String[] r : BgSeed.quotedRows("bg_payroll_parameter.csv")) {
      if (!code.equals(BgSeed.col(h, r, "code"))) {
        continue;
      }
      LocalDate from = LocalDate.parse(BgSeed.col(h, r, "validFrom"));
      String to = BgSeed.col(h, r, "validTo");
      if (from.isAfter(on) || (!to.isEmpty() && LocalDate.parse(to).isBefore(on))) {
        continue;
      }
      if (bestFrom == null || from.isAfter(bestFrom)) {
        bestFrom = from;
        best = new BigDecimal(BgSeed.col(h, r, "paramValue"));
      }
    }
    assertNotNull(best, "параметър „" + code + "“ липсва за " + on);
    return best;
  }

  private static List<BgInsuranceContribution> seedRates() {
    List<String> h = BgSeed.quotedHeader("bg_insurance_contribution.csv");
    List<BgInsuranceContribution> out = new ArrayList<>();
    for (String[] r : BgSeed.quotedRows("bg_insurance_contribution.csv")) {
      if (!"01".equals(BgSeed.col(h, r, "bgInsuredTypeCode"))
          || !"3".equals(BgSeed.col(h, r, "laborCategory"))
          || !"STANDARD".equals(BgSeed.col(h, r, "bgCompensationTypeCode"))) {
        continue;
      }
      String upf = BgSeed.col(h, r, "bgUpfRegime");
      if (!"ANY".equals(upf) && !"WITH_UPF".equals(upf)) {
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

  // ═══ ВЕДОМОСТТА · ИСТИНСКИ prepare(), ПОДМЕНЕНИ САМО ЧЕТЦИТЕ ═════════════

  private static final class Payroll extends BgPaySlipService {

    private final List<BgPaySlipLineType> types = dictionary();
    private final BgPayrollInput input;

    private final BgEarningsService earnings;
    private final BgContributionLineService contributionLines;
    private final BgIncomeTaxService incomeTax;
    private final BgArt177LeaveService art177;

    Payroll(BgPayrollInput input) {
      this.input = input;
      final Payroll self = this;
      this.earnings =
          new BgEarningsService() {
            @Override
            protected BigDecimal statutoryMinPct(LocalDate date) {
              return parameter("SENIORITY_MIN_PCT", date);
            }

            @Override
            protected BigDecimal payrollParameter(String code, LocalDate date) {
              return null;
            }

            @Override
            protected Map<String, BigDecimal> agreedOvertimeUplift(
                BgPaySlip slip, BgPayrollInput in) {
              return Collections.emptyMap();
            }

            @Override
            protected List<BgPaySlipLineType> lineTypes() {
              return self.activeTypes();
            }

            @Override
            protected BgPaySlipLineType lineType(String code) {
              return self.byCode(code);
            }

            @Override
            protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
              return self.lineFrom(type, amount);
            }

            @Override
            protected Boolean seniorityEntitlement(BgPaySlip slip) {
              return Boolean.TRUE;
            }
          };
      final BgEarningsService earningsRef = this.earnings;
      this.art177 =
          new BgArt177LeaveService() {
            @Override
            protected BgEarningsService earningsService() {
              return earningsRef;
            }

            @Override
            protected BgPaySlipLineType lineType() {
              return self.byCode(LINE_ART177_LEAVE);
            }

            @Override
            protected BigDecimal minWage(LocalDate date) {
              return parameter("MIN_WAGE", date);
            }

            @Override
            protected List<BgPaySlip> earlierSlips(EmploymentContract contract, LocalDate before) {
              return Collections.singletonList(baseMonthSlip());
            }

            @Override
            protected BigDecimal absenceDaysOf(EmploymentContract contract, Period period) {
              return BigDecimal.ZERO;
            }

            @Override
            protected Integer workingDaysOf(LocalDate from, LocalDate to) {
              // Един шев, два въпроса: годишният интервал се различава от месечния по дължината си.
              if (from.getMonthValue() == 1 && to.getMonthValue() == 12) {
                return 249;
              }
              return NOV_WORKING_DAYS;
            }

            @Override
            protected BgPaySlipService paySlipService() {
              return new BgPaySlipService() {
                @Override
                public BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
                  return self.lineFrom(type, amount);
                }

                @Override
                public void addLine(BgPaySlip slip, BgPaySlipLine line) {
                  slip.getBgPaySlipLineList().add(line);
                }
              };
            }
          };
      this.contributionLines =
          new BgContributionLineService() {
            @Override
            protected BgPaySlipLineType lineTypeFor(String fundCode, String sign) {
              for (BgPaySlipLineType t : self.activeTypes()) {
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
              return self.lineFrom(type, amount);
            }
          };
      this.incomeTax =
          new BgIncomeTaxService() {
            @Override
            protected BgPaySlipLineType lineType(String code) {
              return self.byCode(code);
            }

            @Override
            protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
              return self.lineFrom(type, amount);
            }
          };
    }

    List<BgPaySlipLineType> activeTypes() {
      List<BgPaySlipLineType> out = new ArrayList<>();
      for (BgPaySlipLineType t : types) {
        if (Boolean.TRUE.equals(t.getActive())) {
          out.add(t);
        }
      }
      return out;
    }

    BgPaySlipLineType byCode(String code) {
      for (BgPaySlipLineType t : types) {
        if (code.equals(t.getCode())) {
          return t;
        }
      }
      return null;
    }

    /** Базовият месец по чл. 177 ал. 1 — ноември, напълно отработен, ПРЕДИ вдигането. */
    private BgPaySlip baseMonthSlip() {
      BgPaySlip slip = new BgPaySlip();
      slip.setStatusSelect(BgPaySlipResolver.CONFIRMED);
      slip.setPeriod(period(NOV_FROM, NOV_TO));
      slip.setBgWorkedDays(NOV_WORKING_DAYS);
      slip.setBgWorkingDaysInPeriod(NOV_WORKING_DAYS);
      slip.setBgPaySlipLineList(new ArrayList<>());
      BgPaySlipLineType base = byCode(BgEarningsResolver.LINE_BASE_SALARY);
      BgPaySlipLine line = lineFrom(base, BASE_MONTH_ART17);
      // ⚠️ ЕДИН РЕД, НОСЕЩ ЦЕЛИЯ НАЧИСЛЕН СБОР ПО ЧЛ. 17. Флаговете се задават изрично: базата се
      // чете през bgIsArt17Base, а карвът — през bgIsPermanentCharacter.
      line.setBgIsPermanentCharacter(true);
      line.setBgIsArt17Base(true);
      slip.getBgPaySlipLineList().add(line);
      return slip;
    }

    /**
     * ⚠️ {@code null}, А НЕ ПОСТРОЕН {@code Count}: конструкторът му е пакетно-видим, а работните
     * дни вече са на черновата ({@link #prepared}). {@code prepare()} чете брояча САМО когато
     * полето е нула, тоест подредбата остава негова.
     */
    @Override
    protected BgWorkingDayService.Count periodWorkingDays(BgPaySlip slip) {
      return null;
    }

    @Override
    protected BgPayrollInput payrollInput(BgPaySlip slip) {
      return input;
    }

    @Override
    protected BigDecimal incomeTaxRate(BgPaySlip slip) {
      return parameter("INCOME_TAX_RATE", DEC_FROM);
    }

    @Override
    protected BgContributionResult contributions(BgPaySlip slip) {
      BgInsurableIncomeResult income =
          BgInsurableIncomeService.compute(
              insuranceGross(slip),
              BgMinIncomeResult.resolved(MOD, "МОД-представителен", 1),
              null,
              true,
              CAP,
              slip.getPeriod().getFromDate(),
              BgWorkTimeFacts.byDays(
                  BigDecimal.valueOf(slip.getBgWorkingDaysInPeriod()),
                  BigDecimal.valueOf(slip.getBgWorkedDays()),
                  null));
      if (!income.isResolved()) {
        return BgContributionResult.failure(
            BgContributionResult.Status.INPUT_NOT_ASSEMBLED, String.valueOf(income.getDetail()));
      }
      return BgContributionResolver.resolve(
          seedRates(),
          new BgContributionRequest(
              income.getAmount(),
              input.insuredTypeCode,
              input.laborCategory,
              input.bornAfter1959,
              null,
              null,
              slip.getPeriod().getFromDate(),
              TZPB));
    }

    @Override
    protected void generateEarnings(BgPaySlip slip) {
      earnings.generate(slip, payrollInput(slip));
    }

    /** Болничен в тези свидетели няма — заявката иска Hibernate. */
    @Override
    protected void generateSickLeave(BgPaySlip slip) {}

    @Override
    protected void generateArt177Leave(BgPaySlip slip) {
      new BgPaySlipVerdicts()
          .addArt177Leave(BgPaySlipVerdicts.STEP_ART177_LEAVE, art177.generate(slip, input))
          .appendInto(slip);
    }

    @Override
    protected void generateCompensations(BgPaySlip slip) {}

    @Override
    protected void generateContributions(BgPaySlip slip) {
      contributionLines.generate(slip, contributions(slip));
    }

    @Override
    protected void generateIncomeTax(BgPaySlip slip) {
      // ⚠️ НУЛА, НЕ null: null значи „лицето носи решение на ТЕЛК, а облекчението по чл. 18 ал. 2
      // не е заредено“ и е ОТКАЗ — тоест перото за данъка изобщо не се създава.
      incomeTax.generate(slip, incomeTaxRate(slip), BigDecimal.ZERO);
    }

    @Override
    protected void generateAnnualSettlement(BgPaySlip slip) {}

    @Override
    protected void save(BgPaySlip slip) {}
  }

  // ═══ ОБЩИ ПОМОЩНИЦИ ══════════════════════════════════════════════════════

  private static Period period(LocalDate from, LocalDate to) {
    Period p = new Period();
    p.setName(String.format("%02d", from.getMonthValue()) + "/PY" + from.getYear());
    p.setFromDate(from);
    p.setToDate(to);
    return p;
  }

  private static EmploymentContract contract(String hoursPerDay) {
    Employee employee = new Employee();
    employee.setId(7L);
    EmploymentContract c = new EmploymentContract();
    c.setId(42L);
    c.setEmployee(employee);
    c.setBgHoursPerDay(new BigDecimal(hoursPerDay));
    c.setBgInsuredTypeCode("01");
    c.setBgBaseSalary(SALARY);
    return c;
  }

  /** Фишът, минал САМО през {@code prepare()}. Нито едно перо не се добавя от теста. */
  private static BgPaySlip prepared(BgPayrollInput input) {
    Payroll payroll = new Payroll(input);
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setPeriod(period(DEC_FROM, DEC_TO));
    slip.setBgBaseSalary(SALARY);
    slip.setBgWorkingDaysInPeriod(DEC_WORKING_DAYS);
    slip.setBgWorkedDays(DEC_WORKING_DAYS);
    slip.setEmploymentContract(contract("8.00"));
    slip.setBgPaySlipLineList(new ArrayList<>());
    payroll.prepare(slip);
    payroll.recompute(slip);
    return slip;
  }

  private static BigDecimal amountOf(BgPaySlip slip, String code) {
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (line.getBgPaySlipLineType() != null
          && code.equals(line.getBgPaySlipLineType().getCode())) {
        return line.getBgAmount();
      }
    }
    return null;
  }

  private static BigDecimal gross(BgPaySlip slip) {
    return slip.getBgGrossTotal().setScale(2, java.math.RoundingMode.HALF_UP);
  }

  private static BigDecimal net(BgPaySlip slip) {
    return slip.getBgNetTotal().setScale(2, java.math.RoundingMode.HALF_UP);
  }

  private static BigDecimal contributionsOfSign(BgPaySlip slip, String sign) {
    BigDecimal total = BigDecimal.ZERO;
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (BgPaySlipResolver.groupOf(line) == BgPaySlipResolver.GROUP_CONTRIBUTIONS
          && sign.equals(line.getBgSign())) {
        total = total.add(line.getBgAmount());
      }
    }
    return total.setScale(2, java.math.RoundingMode.HALF_UP);
  }

  private static BigDecimal taxOf(BgPaySlip slip) {
    BigDecimal tax = amountOf(slip, BgIncomeTaxService.LINE_INCOME_TAX);
    return tax == null ? BigDecimal.ZERO : tax.setScale(2, java.math.RoundingMode.HALF_UP);
  }

  // ═══ РЕЖИМ 1 · ПО КТД — НУЛА ПАРИ ════════════════════════════════════════

  @Test
  @DisplayName("⚠️ РЕЖИМ 1 (ал. 3 т. 1) · два дни брак → фишът е БИТ ПО БИТ днешният")
  void theKtdRegimeIsBitForBitTodaysPaySlip() {
    BgPaySlip without = prepared(inputWithLeave(leaveOnly("8.00")));
    BgPaySlip with =
        prepared(
            inputWithLeave(
                leaveOnly("8.00", leaveFromSeed("paid_157", "2026-12-07", "2026-12-08", "2", 1))));

    // ⚠️ ТОВА Е ПОЛОВИНАТА ОТ МИГРАЦИЯТА, ИЗМЕРЕНА В ПАРИ. paid_157 запазва кода и всичките си
    // флагове, тоест всяка заварена заявка продължава да сочи ред с днешното третиране.
    assertEquals(new BigDecimal("1200.00"), amountOf(with, BgEarningsResolver.LINE_BASE_SALARY));
    assertEquals(new BigDecimal("72.00"), amountOf(with, BgEarningsResolver.LINE_SENIORITY));
    assertNull(
        amountOf(with, BgArt177LeaveService.LINE_ART177_LEAVE),
        "⚠️ ал. 3 т. 1 праща размера в КТД и НЕ препраща към чл. 177 — перо 165 не бива да се"
            + " пали");
    assertEquals(gross(without), gross(with), "нула пари: режим 1 се заплаща като отработено");
    assertEquals(net(without), net(with));
    assertEquals(
        contributionsOfSign(without, BgPaySlipResolver.CREDIT),
        contributionsOfSign(with, BgPaySlipResolver.CREDIT));
    assertEquals(taxOf(without), taxOf(with));

    // И дните ВСЕ ПАК са преброени — те са осигурителен стаж и пълнят т. 16.1.
    BgPayrollInput in =
        leaveOnly("8.00", leaveFromSeed("paid_157", "2026-12-07", "2026-12-08", "2", 1));
    assertEquals(0, new BigDecimal("2.00").compareTo(in.paidLeaveDays), "два работни дни");
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(in.art177LeaveDays),
        "⚠️ И НУЛА от тях по чл. 177 — точно това разграничение носеше разцепването");
    assertEquals(
        0,
        new BigDecimal("2.00").compareTo(in.leaveDaysByD1Code.get(BgD1DayBuckets.B_16_1)),
        "кофа 16.1: дни с осигурителни вноски");
  }

  // ═══ РЕЖИМ 2 · В РАЗМЕРА ПО ЧЛ. 177 — ПАРИТЕ МЪРДАТ ═════════════════════

  @Test
  @DisplayName("⚠️ РЕЖИМ 2 (ал. 3 т. 2) · два дни по т. 5а → надплащането е ИЗМЕРИМО")
  void theArticle177RegimePaysTheBaseMonthRateNotTodaysSalary() {
    BgPaySlip today =
        prepared(
            inputWithLeave(
                leaveOnly("8.00", leaveFromSeed("paid_157", "2026-12-07", "2026-12-08", "2", 1))));
    BgPaySlip lawful =
        prepared(
            inputWithLeave(
                leaveOnly(
                    "8.00", leaveFromSeed("paid_157_art177", "2026-12-07", "2026-12-08", "2", 1))));

    // ── КАРВЪТ: перата с постоянен характер губят своите два дни ──
    // 1200 × 20/22 = 1090,9090… → 1090,91 и 72 × 20/22 = 65,4545… → 65,45
    assertEquals(new BigDecimal("1090.91"), amountOf(lawful, BgEarningsResolver.LINE_BASE_SALARY));
    assertEquals(new BigDecimal("65.45"), amountOf(lawful, BgEarningsResolver.LINE_SENIORITY));

    // ── ПЕРО 165: карв 115,64 + надбавка (−19,27) ──
    // Среднодневното по чл. 177 ал. 1 + НСОРЗ чл. 18 ал. 2:
    //   1060,00 × 21 / (21 × 22) = 48,1818… €/ден, тоест правото за 2 дни е 96,36 €.
    // ⚠️ НАДБАВКАТА Е ОТРИЦАТЕЛНА И ТОВА Е ЗАКОННО: чл. 177 плаща по БАЗОВИЯ месец, а текущият е
    // по-богат (заплатата е вдигната). Сборът карв + надбавка си остава неотрицателен.
    assertEquals(
        new BigDecimal("96.37"),
        amountOf(lawful, BgArt177LeaveService.LINE_ART177_LEAVE),
        "карв 115,64 + надбавка −19,27. ⚠️ Стотинката разлика спрямо правото 96,36 е от РЕДА на"
            + " закръгляне (окр(a)+окр(b) ≠ окр(a+b)) и е същият ръб, който даде 227,28 срещу"
            + " 227,27 при чл. 177 — карвът се чете от ДВА изхода на енджина, всеки закръглен"
            + " сам.");

    // ── ПАРИТЕ ──
    assertEquals(new BigDecimal("1272.00"), gross(today), "днес: пълната заплата за цял месец");
    assertEquals(new BigDecimal("1252.73"), gross(lawful), "1090,91 + 65,45 + 96,37");
    assertEquals(
        new BigDecimal("19.27"),
        gross(today).subtract(gross(lawful)),
        "⚠️ НАДПЛАЩАНЕТО В БРУТО. Отпуск по т. 5а, платен по дневната ставка на ТЕКУЩИЯ месец"
            + " (1272,00 / 22 = 57,8182 €/ден) вместо по среднодневното от предходния"
            + " (48,1818 €/ден), надплаща 9,64 € на ден, при това с вид на пресметнато.");

    BigDecimal deltaEmployee =
        contributionsOfSign(today, BgPaySlipResolver.DEBIT)
            .subtract(contributionsOfSign(lawful, BgPaySlipResolver.DEBIT));
    BigDecimal deltaEmployer =
        contributionsOfSign(today, BgPaySlipResolver.CREDIT)
            .subtract(contributionsOfSign(lawful, BgPaySlipResolver.CREDIT));
    assertEquals(
        new BigDecimal("2.65"),
        deltaEmployee,
        "вноски за сметка на ЛИЦЕТО. ⚠️ 2,65, а НЕ 19,27 × 13,78 % = 2,66: всяко от петте пера"
            + " се закръгля САМО ЗА СЕБЕ СИ, тоест разликата на сборовете не е сборът на"
            + " разликите (175,28 срещу 172,63).");
    assertEquals(
        new BigDecimal("3.67"),
        deltaEmployer,
        "вноски за сметка на РАБОТОДАТЕЛЯ — 241,94 срещу 238,27, при 19,02 на сто с ТЗПБ 0,50");
    assertEquals(
        new BigDecimal("1.66"),
        taxOf(today).subtract(taxOf(lawful)),
        "данък 10 на сто върху основа 1096,72 срещу 1080,10 → 109,67 срещу 108,01");
    assertEquals(
        new BigDecimal("14.96"),
        net(today).subtract(net(lawful)),
        "⚠️ НЕТОТО, което лицето е получавало в повече: 19,27 − 2,65 − 1,66 = 14,96 (987,05"
            + " срещу 972,09)");

    // ── И Д1 НЕ МЪРДА: и двата режима са дни с осигурителни вноски ──
    assertEquals(
        0,
        new BigDecimal("2.00")
            .compareTo(lawful.getBgLeaveDays161().setScale(2, java.math.RoundingMode.HALF_UP)),
        "⚠️ т. 16.1 е СЪЩАТА и това е вярно: работодателят плаща, значи вноски има, значи дните са"
            + " дни в осигуряване. Мърда само ОСНОВАТА (т. 21), не броят дни.");
  }

  @Test
  @DisplayName("⚠️ РЕЖИМ 2 В ЧАСОВЕ (ал. 1 т. 6 / ал. 2) · часове, карв и Д1 в един свидетел")
  void theArticle177RegimeInHoursCrossesAllThreeLayers() {
    // ЦИТАТ, ал. 1 т. 6: „по 1 ЧАС дневно за дните на предизвестието“. Четири такива часа.
    BgPayrollInput in =
        leaveOnly("8.00", leaveFromSeed("paid_157_art177", "2026-12-07", "2026-12-10", "4", 2));

    // ── СЛОЙ 1 · часове ÷ ДОГОВОРЕНИЯ ден ──
    assertEquals(
        0,
        new BigDecimal("0.50").compareTo(in.art177LeaveDays),
        "4 ÷ 8 = 0,50 дни. ⚠️ Закръглен до цял ден на входа, часът щеше да се трие ТИХО — а чл. 157"
            + " ал. 1 т. 6 не съществува в дни.");
    assertEquals(
        0,
        new BigDecimal("0.50").compareTo(in.paidLeaveDays),
        "подмножество, не съседна кофа: часовете СА платен отпуск");

    // ── СЛОЙ 2 · карв + перо 165 за ДРОБНАТА част ──
    BgPaySlip lawful = prepared(inputWithLeave(in));
    // 1200 × 21,5/22 = 1172,7272… → 1172,73 и 72 × 21,5/22 = 70,3636… → 70,36
    assertEquals(new BigDecimal("1172.73"), amountOf(lawful, BgEarningsResolver.LINE_BASE_SALARY));
    assertEquals(new BigDecimal("70.36"), amountOf(lawful, BgEarningsResolver.LINE_SENIORITY));
    assertEquals(
        new BigDecimal("24.09"),
        amountOf(lawful, BgArt177LeaveService.LINE_ART177_LEAVE),
        "карв 28,91 + надбавка −4,82 = 24,09 = 48,1818 €/ден × 0,5 дни");
    assertEquals(new BigDecimal("1267.18"), gross(lawful), "1172,73 + 70,36 + 24,09");

    BgPaySlip today =
        prepared(
            inputWithLeave(
                leaveOnly("8.00", leaveFromSeed("paid_157", "2026-12-07", "2026-12-10", "4", 2))));
    assertEquals(new BigDecimal("1272.00"), gross(today), "днес часовете не мърдат нищо");
    assertEquals(
        new BigDecimal("4.82"),
        gross(today).subtract(gross(lawful)),
        "⚠️ НАДПЛАЩАНЕТО за ЧЕТИРИ ЧАСА. Малко в евро и точно затова невидимо — а при 30-дневно"
            + " предизвестие часовете са 30 и числото е тридесеткратно.");

    // ── СЛОЙ 3 · Д1 т. 16 от ЗАМРАЗЕНИТЕ дни, не от огледална сметка ──
    assertEquals(
        0,
        new BigDecimal("0.50")
            .compareTo(lawful.getBgLeaveDays161().setScale(2, java.math.RoundingMode.HALF_UP)),
        "⚠️ ЗАМРАЗЕНОТО ЧИСЛО Е ДРОБНО. Декларацията закръгля при СЕРИАЛИЗАЦИЯ, не тук — закръглено"
            + " на замразяването, два получаса в един месец щяха да дадат 1 + 1 вместо 1.");
    assertNotNull(lawful.getBgLeaveDaysFrozenAt(), "датата е дискриминаторът на замразяването");

    // ── И ДЕЛИТЕЛЯТ Е ДОГОВОРЕНИЯТ ДЕН, НЕ ЗАКОНОУСТАНОВЕНИЯТ ──
    BgPayrollInput halfTime =
        leaveOnly("4.00", leaveFromSeed("paid_157_art177", "2026-12-07", "2026-12-10", "4", 2));
    assertEquals(
        0,
        new BigDecimal("1.00").compareTo(halfTime.art177LeaveDays),
        "⚠️ СЪЩИТЕ ЧЕТИРИ ЧАСА ПРИ ПОЛОВИН ЩАТ СА ЦЯЛ ДЕН. През законоустановените 8 часа вместо"
            + " договорените 4 правото щеше да се плати наполовина — при пълен щат неразличимо,"
            + " защото там двете числа съвпадат.");
  }

  // ═══ РЕЖИМ 3 · ПО СПЕЦИАЛНИТЕ ЗАКОНИ — РАБОТОДАТЕЛЯТ НЕ ПЛАЩА ═══════════

  @Test
  @DisplayName("⚠️ РЕЖИМ 3 (ал. 3 т. 3) · три дни свидетел в съд → работодателят не дължи размера")
  void theSpecialLawRegimeIsNotPaidByTheEmployer() {
    BgPaySlip today =
        prepared(
            inputWithLeave(
                leaveOnly("8.00", leaveFromSeed("paid_157", "2026-12-07", "2026-12-09", "3", 1))));
    BgPayrollInput in =
        leaveOnly("8.00", leaveFromSeed("paid_157_special", "2026-12-07", "2026-12-09", "3", 1));
    BgPaySlip lawful = prepared(inputWithLeave(in));

    // ── ВХОДЪТ: дните са в НЕПЛАТЕНАТА кофа, с кофа 16.4 в Д1 ──
    assertEquals(
        0,
        new BigDecimal("3.00").compareTo(in.unpaidLeaveDays),
        "⚠️ „Неплатен“ тук значи „не се плаща ОТ НАС“ — възнаграждение има, но от съда"
            + " (ГПК чл. 168)");
    assertEquals(
        0,
        new BigDecimal("3.00").compareTo(in.unpaidLeaveDaysInD1),
        "карвиращото подмножество: Д1 вади точно тези дни от т. 16.1");
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(in.daysOutsideInsurance),
        "⚠️ И НЕ Е „ИЗВЪН ОСИГУРЯВАНЕТО“: денят се зачита за осигурителен стаж, тоест КСО чл. 10"
            + " ал. 2 не прекъсва осигуряването и т. 14/15 не раждат двойка");
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(in.unpaidLeaveDaysUnderZzo),
        "⚠️ И НУЛА ПО ЗЗО ЧЛ. 40 АЛ. 1 Т. 1 Б. „Б“ — хипотезата иска НЕПЛАТЕН ОТПУСК, а това не е"
            + " той. Прочетено от цялата неплатена кофа, от лицето щеше да се удържи здравна вноска"
            + " за дни, в които то подлежи на осигуряване по т. 1.");

    // ── ПАРИТЕ ──
    // 1200 × 19/22 = 1036,3636… → 1036,36 и 72 × 19/22 = 62,1818… → 62,18
    assertEquals(new BigDecimal("1036.36"), amountOf(lawful, BgEarningsResolver.LINE_BASE_SALARY));
    assertEquals(new BigDecimal("62.18"), amountOf(lawful, BgEarningsResolver.LINE_SENIORITY));
    assertNull(
        amountOf(lawful, BgArt177LeaveService.LINE_ART177_LEAVE),
        "⚠️ И перо 165 НЕ се пали: карвът за режим 3 НЕ се връща никъде — КТ чл. 128 т. 2 дължи"
            + " възнаграждение „за извършената работа“, а размера по т. 3 го дължи друг платец");
    assertEquals(new BigDecimal("1272.00"), gross(today), "днес: трите дни се плащат цели");
    assertEquals(new BigDecimal("1098.54"), gross(lawful), "1036,36 + 62,18");
    assertEquals(
        new BigDecimal("173.46"),
        gross(today).subtract(gross(lawful)),
        "⚠️ НАДПЛАЩАНЕТО В БРУТО за три дни в съд — 57,82 €/ден, които КТ не възлага на"
            + " работодателя. И то не е само неговите пари: сумата е и осигурителен доход, и"
            + " данъчна основа, и три дни в т. 16.1 на декларация.");

    assertEquals(
        new BigDecimal("23.91"),
        contributionsOfSign(today, BgPaySlipResolver.DEBIT)
            .subtract(contributionsOfSign(lawful, BgPaySlipResolver.DEBIT)),
        "вноски за сметка на лицето");
    assertEquals(
        new BigDecimal("33.00"),
        contributionsOfSign(today, BgPaySlipResolver.CREDIT)
            .subtract(contributionsOfSign(lawful, BgPaySlipResolver.CREDIT)),
        "вноски за сметка на работодателя");
    assertEquals(new BigDecimal("14.95"), taxOf(today).subtract(taxOf(lawful)), "данък 10 на сто");
    assertEquals(
        new BigDecimal("134.60"),
        net(today).subtract(net(lawful)),
        "⚠️ НЕТОТО в повече: 173,46 − 23,91 − 14,95");

    // ── И ДЕКЛАРАЦИЯТА: три дни в т. 16.4, извадени от т. 16.1 ──
    assertEquals(
        0,
        new BigDecimal("3.00")
            .compareTo(lawful.getBgLeaveDays164().setScale(2, java.math.RoundingMode.HALF_UP)),
        "указание т. 16.4 дословно: „други дни в осигуряване без осигурителни вноски“");
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(lawful.getBgLeaveDays161()),
        "⚠️ И НУЛА в т. 16.1 от заявката — остатъкът на точката се смята като bgWorkedDays минус"
            + " кофите 16.3/16.4/16.5 в BgD1RecordBuilder, тоест 22 − 3 = 19 дни");
    assertEquals(
        0,
        new BigDecimal("3.00")
            .compareTo(today.getBgLeaveDays161().setScale(2, java.math.RoundingMode.HALF_UP)),
        "⚠️ А ДНЕС СЪЩИТЕ ТРИ ДНИ СА В Т. 16.1 — дни с осигурителни вноски, за които вноски няма");
  }

  // ═══ ЗАВАРЕНОТО · НУЛА ДВИЖЕНИЕ ══════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ЗДРАВНАТА ВНОСКА ПРИ НЕПЛАТЕН ОТПУСК · заварената остава, режим 3 НЕ се добавя")
  void theZzoContributionCountsOnlyRealUnpaidLeave() {
    BigDecimal base = parameter("HEALTH_UNPAID_LEAVE_BASE", DEC_FROM);
    BigDecimal rate = new BigDecimal("8.0000");

    // ── Заварено: САМО неплатен по желание. Числото не мърда. ──
    BgPayrollInput alone = leaveOnly("8.00", unpaidLeave("2026-12-07", "2026-12-11", "5"));
    assertEquals(
        0,
        new BigDecimal("5.00").compareTo(alone.unpaidLeaveDaysUnderZzo),
        "⚠️ ЗА ВСИЧКИТЕ ЗАВАРЕНИ ВИДОВЕ ПОДМНОЖЕСТВОТО Е ЦЯЛОТО МНОЖЕСТВО: петте вида от категория"
            + " 7 всеки носи платец по б. „б“, тоест нито един заварен фиш не мърда.");
    assertEquals(0, alone.unpaidLeaveDays.compareTo(alone.unpaidLeaveDaysUnderZzo));
    assertEquals(
        0,
        new BigDecimal("5.64")
            .compareTo(
                BgUnpaidLeaveHealthResolver.amount(
                    base, rate, alone.unpaidLeaveDaysUnderZzo, DEC_WORKING_DAYS)),
        "310,10 × 8 × 5 / (100 × 22) = 5,6381… → 5,64");

    // ── Смесеният месец: 5 дни неплатен + 3 дни в съд ──
    BgPayrollInput mixed =
        leaveOnly(
            "8.00",
            unpaidLeave("2026-12-07", "2026-12-11", "5"),
            leaveFromSeed("paid_157_special", "2026-12-14", "2026-12-16", "3", 1));
    assertEquals(
        0,
        new BigDecimal("8.00").compareTo(mixed.unpaidLeaveDays),
        "кофата събира и осемте дни — и двата вида не се заплащат от работодателя");
    assertEquals(
        0,
        new BigDecimal("5.00").compareTo(mixed.unpaidLeaveDaysUnderZzo),
        "⚠️ НО ПО Б. „Б“ СА САМО ПЕТ. Това е цялата разлика, заради която числото е второ.");
    assertEquals(
        0,
        new BigDecimal("5.64")
            .compareTo(
                BgUnpaidLeaveHealthResolver.amount(
                    base, rate, mixed.unpaidLeaveDaysUnderZzo, DEC_WORKING_DAYS)),
        "дължимото не се мени от съдебните дни");
    assertEquals(
        0,
        new BigDecimal("9.02")
            .compareTo(
                BgUnpaidLeaveHealthResolver.amount(
                    base, rate, mixed.unpaidLeaveDays, DEC_WORKING_DAYS)),
        "⚠️ И ТОВА Е ЦЕНАТА НА СГРЕШЕНИЯ ЧИТАТЕЛ, ИЗМЕРЕНА: прочетена върху цялата неплатена кофа,"
            + " вноската щеше да е 9,02 € — 3,38 € в повече, изцяло за сметка на ЛИЦЕТО.");

    // ── И ПРЕЗ ИСТИНСКАТА УСЛУГА, не само през чистата функция ──
    Health health = new Health(base, rate);
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setPeriod(period(DEC_FROM, DEC_TO));
    slip.setBgWorkingDaysInPeriod(DEC_WORKING_DAYS);
    slip.setEmploymentContract(contract("8.00"));
    slip.setBgPaySlipLineList(new ArrayList<>());
    BgUnpaidLeaveHealthResolver.Outcome out =
        health.generate(slip, mixed, BgUnpaidLeaveHealthResolver.RULE_UNPAID_EMPLOYEE);
    assertEquals(BgUnpaidLeaveHealthResolver.Status.COMPUTED, out.status);
    assertEquals(
        0,
        new BigDecimal("5.64").compareTo(out.amount),
        "⚠️ УСЛУГАТА ЧЕТЕ ПОДМНОЖЕСТВОТО. Намерено: " + out.amount);
    assertEquals(BgUnpaidLeaveHealthResolver.Payer.INSURED_PERSON, out.payer);

    // ── А САМО режим 3 не открива хипотезата изобщо ──
    BgPayrollInput special =
        leaveOnly("8.00", leaveFromSeed("paid_157_special", "2026-12-14", "2026-12-16", "3", 1));
    BgUnpaidLeaveHealthResolver.Outcome none =
        health.generate(slip, special, BgUnpaidLeaveHealthResolver.RULE_UNPAID_EMPLOYEE);
    assertEquals(
        BgUnpaidLeaveHealthResolver.Status.NOT_APPLICABLE,
        none.status,
        "нула дни неплатен отпуск → б. „б“ не се открива, и това е СТОЙНОСТ, не отказ");
  }

  /** Услугата с подменен САМО записа на перото — по образеца на {@code writeSpecialHealthBase}. */
  private static final class Health extends BgUnpaidLeaveHealthService {

    private final BigDecimal base;
    private final BigDecimal rate;

    Health(BigDecimal base, BigDecimal rate) {
      this.base = base;
      this.rate = rate;
    }

    @Override
    protected BigDecimal baseFor(LocalDate on) {
      return base;
    }

    @Override
    protected BigDecimal totalHealthRate(BgPayrollInput input, LocalDate on) {
      return rate;
    }

    @Override
    protected void writeHealthLine(
        BgPaySlip slip,
        boolean employerBorne,
        BigDecimal proratedBase,
        BigDecimal ratePercent,
        BigDecimal amount) {}
  }

  /** Неплатен отпуск по желание на лицето — {@code unpaid_own_le30}, ОТ СИЙДА. */
  private static Object[] unpaidLeave(String from, String to, String days) {
    return leaveFromSeed("unpaid_own_le30", from, to, days, 1);
  }

  // ═══ КОТВА · РЕЖИМИТЕ СА РАЗЛИЧНИ ТРОЙКИ, НЕ ЕДНА ═══════════════════════

  @Test
  @DisplayName("⚠️ КОТВА · трите режима дават ТРИ различни брутни суми за ЕДИН И СЪЩИ дни")
  void theThreeRegimesProduceThreeDifferentGrossAmounts() {
    List<BigDecimal> grosses = new ArrayList<>();
    for (String code : Arrays.asList("paid_157", "paid_157_art177", "paid_157_special")) {
      grosses.add(
          gross(
              prepared(
                  inputWithLeave(
                      leaveOnly(
                          "8.00", leaveFromSeed(code, "2026-12-07", "2026-12-08", "2", 1))))));
    }
    assertEquals(
        Arrays.asList(
            new BigDecimal("1272.00"), new BigDecimal("1252.73"), new BigDecimal("1156.36")),
        grosses,
        "⚠️ ЕДИН РЕД ЗА ТРИТЕ ЗНАЧЕШЕ ЕДНО ЧИСЛО ЗА ТРИТЕ — 1272,00 за всеки. Разликата между"
            + " крайните два режима е 115,64 € на ДВА дни отпуск.");
    assertTrue(
        grosses.get(0).compareTo(grosses.get(1)) > 0
            && grosses.get(1).compareTo(grosses.get(2)) > 0,
        "и подредбата е монотонна: по КТД ≥ по чл. 177 ≥ по специален закон");
  }
}

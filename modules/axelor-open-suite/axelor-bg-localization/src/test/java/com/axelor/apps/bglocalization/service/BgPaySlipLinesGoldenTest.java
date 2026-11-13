package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgAbsenceType;
import com.axelor.apps.bglocalization.db.BgArt73Para6Report;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgInsuranceContribution;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.d1.BgD1ContributionAggregator;
import com.axelor.apps.bglocalization.service.payroll.BgArt177LeaveService;
import com.axelor.apps.bglocalization.service.payroll.BgArt49SettlementService;
import com.axelor.apps.bglocalization.service.payroll.BgCompensationLineService;
import com.axelor.apps.bglocalization.service.payroll.BgContributionLineService;
import com.axelor.apps.bglocalization.service.payroll.BgEarningsService;
import com.axelor.apps.bglocalization.service.payroll.BgIncomeTaxService;
import com.axelor.apps.bglocalization.service.payroll.BgPaySlipPrintSummary;
import com.axelor.apps.bglocalization.service.payroll.BgPaySlipVerdicts;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInput;
import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveService;
import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveServiceOnDeclaredStage;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
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
 * ⚠️ ЗЛАТНИЯТ ФИШ, СГЛОБЕН ОТ САМИЯ ФИШ — край до край, през {@code prepare()}.
 *
 * <p><b>Каква е разликата от {@code BgSickLeaveGoldenTest}.</b> Онзи тест сверява числата на Асен
 * ЧИСЛО ПО ЧИСЛО, но осигурителните пера ги сглобява САМИЯТ ТЕСТ — с помощник {@code
 * withContributionLines}, „точно както бъдещият им генератор ще ги сглоби“. Тогава генератор
 * нямаше. Тук го има: единственото, което този тест прави, е да извика {@code prepare()} и после да
 * ЧЕТЕ фиша. Нито едно перо не се добавя от теста.
 *
 * <p>Оттам следва и че този тест пази нещо, което другият по устройство не можеше — РЕДА. Ако
 * осигуровките минат преди начисленията, базата пада; ако данъкът мине преди осигуровките, той
 * излиза завишен. И двете са тихи: всяко число си остава вярно за себе си.
 *
 * <p><b>⚠️ ПОДМЕНЕНИ СА САМО ЧЕТЦИТЕ, НЕ ПРАВИЛАТА И НЕ ПОДРЕДБАТА.</b>
 *
 * <ul>
 *   <li>{@code BgPaySlipService#prepare} се вика ИСТИНСКИ — редът на петте генератора е негов, не
 *       нареден от теста.
 *   <li>{@code BgPaySlipService#lineFrom} се вика ИСТИНСКИ — замразяването по копиране е негово, и
 *       петте генератора минават през него.
 *   <li>{@code BgEarningsResolver}, {@code BgSickLeaveResolver}, {@code BgContributionResolver},
 *       {@code BgInsurableIncomeService} и {@code BgPaySlipResolver} се викат ИСТИНСКИ.
 *   <li><b>⚠️ ЦЕЛИЯТ РЕЧНИК С ТИПОВЕ ПЕРА СЕ ЧЕТЕ ОТ ТРИТЕ CSV-та</b> — 75 реда с истинските си
 *       кодове, номера, знаци, флагове, начини на изчисление и препратки. Тоест типовете тук НЕ са
 *       измислени: смени ли се seed-ът, числата се разминават и тестът пада.
 *   <li>Ставките идват от {@code bg_insurance_contribution.csv}, а данъчната ставка и минимумът за
 *       клас — от {@code bg_payroll_parameter.csv}.
 *   <li>Подменени са само заявките към базата и сглобяването на входа по § 13.1.
 * </ul>
 *
 * <p>Тече без Postgres, без Guice и без Hibernate.
 */
class BgPaySlipLinesGoldenTest {

  /** ЗБДОО чл. 9 т. 1 — максималният осигурителен доход до 31.07.2026 г. */
  private static final BigDecimal CAP = new BigDecimal("2111.6400");

  /** ТЗПБ по раздела на икономическата дейност — от {@code bg_tzpb_rate}, подава се на заявката. */
  private static final BigDecimal TZPB = new BigDecimal("0.5000");

  // ═══ РЕЧНИЦИТЕ, ПРОЧЕТЕНИ ОТ SEED-А ══════════════════════════════════════

  /**
   * ЦЕЛИЯТ речник с типове пера — от трите файла, които го съставят.
   *
   * <p>⚠️ И трите, не само нужните: {@code BgEarningsService} обхожда ВСИЧКИ активни типове, а не
   * своите два. Подаден непълен речник, обходът щеше да мълчи точно за перата, които този тест
   * проверява — и зеленото щеше да значи „не бяха питани“.
   */
  private static List<BgPaySlipLineType> dictionary() {
    List<BgPaySlipLineType> out = new ArrayList<>();
    out.addAll(typesFrom("bg_pay_slip_line_type.csv", null));
    out.addAll(typesFrom("bg_pay_slip_line_type_contribution.csv", "bgContributionFund"));
    out.addAll(typesFrom("bg_pay_slip_line_type_compensation.csv", "bgCompensationType"));
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
      if ("bgContributionFund".equals(refColumn)) {
        BgContributionFund fund = new BgContributionFund();
        fund.setCode(BgSeed.col(h, r, refColumn));
        fund.setName(fund.getCode());
        t.setBgContributionFund(fund);
      }
      out.add(t);
    }
    return out;
  }

  /** Стойността на един параметър за 2026 г. — ОТ ФАЙЛА, не число в теста. */
  private static BigDecimal parameter(String code) {
    List<String> h = BgSeed.quotedHeader("bg_payroll_parameter.csv");
    for (String[] r : BgSeed.quotedRows("bg_payroll_parameter.csv")) {
      if (code.equals(BgSeed.col(h, r, "code"))) {
        return new BigDecimal(BgSeed.col(h, r, "paramValue"));
      }
    }
    throw new IllegalStateException("параметър „" + code + "“ липсва в bg_payroll_parameter.csv");
  }

  /**
   * Редовете за вид осигурен 01, трета категория, кохорта {@code WITH_UPF} — ОТ ФАЙЛА.
   *
   * <p>⚠️ Филтърът е ДОСЛОВНО този на {@code BgInsuranceCalculationService}, преписан нарочно: така
   * ключът, по който се избират редовете, е ВИДИМ, вместо да се крие зад JPQL, който тест без база
   * не може да пусне.
   */
  private static List<BgInsuranceContribution> seedRows() {
    List<String> h = BgSeed.quotedHeader("bg_insurance_contribution.csv");
    List<BgInsuranceContribution> out = new ArrayList<>();
    for (String[] r : BgSeed.quotedRows("bg_insurance_contribution.csv")) {
      if (!"01".equals(BgSeed.col(h, r, "bgInsuredTypeCode"))
          || !"3".equals(BgSeed.col(h, r, "laborCategory"))) {
        continue;
      }
      String upf = BgSeed.col(h, r, "bgUpfRegime");
      if (!"ANY".equals(upf) && !"WITH_UPF".equals(upf)) {
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

  // ═══ ВЕДОМОСТТА С ПОДМЕНЕНИ ЧЕТЦИ, НО ИСТИНСКА ПОДРЕДБА ══════════════════

  /**
   * {@code BgPaySlipService} с подменени заявки — и с петте генератора, навързани към ЛОКАЛНИ
   * инстанции вместо през Guice.
   *
   * <p>⚠️ Подредбата в {@code prepare()} НЕ се пипа: тя е онова, което този тест проверява. Всеки
   * от петте шева прави точно каквото прави в производството, само че върху услуга, чиито четци са
   * подменени.
   */
  private static final class Payroll extends BgPaySlipService {

    private final List<BgPaySlipLineType> types = dictionary();
    private final BgPayrollInput input;
    private final int workingDays;
    private final BigDecimal mod;

    /** Осигурителната база, както я е видял генераторът на осигуровки — за свидетелите. */
    BigDecimal insurableIncomeSeen;

    private final BgEarningsService earnings;
    private final BgSickLeaveService sickLeave;
    private final BgCompensationLineService compensations;
    private final BgContributionLineService contributionLines;
    private final BgIncomeTaxService incomeTax;

    Payroll(BgPayrollInput input, int workingDays, BigDecimal mod) {
      this.input = input;
      this.workingDays = workingDays;
      this.mod = mod;

      final Payroll self = this;
      this.earnings =
          new BgEarningsService() {
            @Override
            protected BigDecimal statutoryMinPct(LocalDate date) {
              return parameter("SENIORITY_MIN_PCT");
            }

            /**
             * ⚠️ ЧАСОВИТЕ СТАВКИ НЕ СЕ ЗАРЕЖДАТ, И ТОВА Е ОТГОВОР, А НЕ ПРОПУСК. Този свидетел не
             * гледа часови пера; без подмяна обаче четецът иска Postgres, защото {@code factsOf}
             * вади и седемте параметъра НАВЕДНЪЖ. Липсващият параметър е ОТКАЗ на съответното перо
             * — точно каквото трябва да получи фикстура, която не е обявила нито една часова
             * ставка.
             */
            @Override
            protected BigDecimal payrollParameter(String code, LocalDate date) {
              return null;
            }

            /**
             * Няма валидирани допълнителни часове → няма уговорено увеличение. Инак иска Hibernate.
             */
            @Override
            protected java.util.Map<String, BigDecimal> agreedOvertimeUplift(
                com.axelor.apps.bglocalization.db.BgPaySlip slip,
                com.axelor.apps.bglocalization.service.payroll.BgPayrollInput input) {
              return java.util.Collections.emptyMap();
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

            // ⚠️ ЧЕТВЪРТИЯТ ЧЕТЕЦ, ПОДМЕНЕН ЗАРАДИ НОВАТА ОС: правото на клас (НСОРЗ чл. 12 ал. 1)
            // се вади от вида осигурен през BgPaySlipService, тоест иска Guice и Postgres. Фишовете
            // тук са на НАЕТИ по трудов договор — за тях отговорът е „да“, и подменен, той оставя
            // сметката на BgEarningsResolver, както досега.
            @Override
            protected Boolean seniorityEntitlement(BgPaySlip slip) {
              return Boolean.TRUE;
            }
          };
      this.sickLeave =
          new BgSickLeaveServiceOnDeclaredStage() {
            /** Базата на 195 — от СЪЩИЯ енджин на начисленията (M, както за чл. 177). */
            @Override
            protected BigDecimal monthlyPermanentPay(BgPaySlip slip, BgPayrollInput in) {
              return self.earnings.monthlyPermanentPay(slip, in);
            }

            @Override
            protected BgAbsenceType absenceType(String code) {
              if (!"sick_er".equals(code)) {
                return null;
              }
              // Редът от bg_absence_type.csv: платец осигурителят, 70 на сто.
              BgAbsenceType t = new BgAbsenceType();
              t.setCode("sick_er");
              t.setPayer("employer");
              t.setCompensationPct(new BigDecimal("70"));
              t.setRequiresSickNote(true); // A4: вид на болничен лист, като в сийда
              t.setCountsAsInsuranceService(true);
              return t;
            }

            @Override
            protected Set<LocalDate> holidaysIn(LocalDate from, LocalDate to) {
              return Collections.emptySet();
            }

            @Override
            protected BigDecimal healthAbsenceBase(LocalDate date) {
              return parameter("HEALTH_ABSENCE_BASE");
            }

            @Override
            protected BigDecimal healthEmployerRatePct(BgPayrollInput in, LocalDate date) {
              for (BgInsuranceContribution row : seedRows()) {
                if ("HEALTH".equals(row.getFundCode())) {
                  return row.getEmployerRate();
                }
              }
              return null;
            }

            @Override
            protected BgPaySlipLineType lineType(String code) {
              return self.byCode(code);
            }

            @Override
            protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
              return self.lineFrom(type, amount);
            }

            /**
             * ⚠️ ШЕВЪТ КЪМ ГЕНЕРАТОРА НА 5xx — тук той се ЗАВЪРЗВА, а не се заглушава.
             *
             * <p>Здравната вноска за дните ДОО (24,13) става перо благодарение на
             * базата-дискриминатор — и влиза в златните числа: разходът за работодателя е 154,90, а
             * не 130,77. Разликата е описана в javadoc-а на BgSickLeaveService#healthAbsence.
             */
            @Override
            protected void writeSpecialHealthBase(
                com.axelor.apps.bglocalization.db.BgPaySlip slip,
                BigDecimal base,
                BigDecimal ratePercent,
                BigDecimal amount) {
              self.contributionLines.upsertSpecialHealthBase(
                  slip, BgD1ContributionAggregator.BASE_ART40_1_5, true, base, ratePercent, amount);
            }
          };
      this.compensations =
          new BgCompensationLineService() {
            @Override
            protected List<com.axelor.apps.bglocalization.db.BgCompensation> documents(
                BgPayrollInput in) {
              // Този фиш няма обезщетения — входът им е празен. Заявката се подменя, защото иначе
              // празният списък пак би минал през JPA.
              return Collections.emptyList();
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

    // ── четците ──────────────────────────────────────────────────────────

    @Override
    protected BgWorkingDayService.Count periodWorkingDays(BgPaySlip slip) {
      return new BgWorkingDayService.Count(workingDays, Collections.emptyList());
    }

    @Override
    protected BgPayrollInput payrollInput(BgPaySlip slip) {
      return input;
    }

    @Override
    protected BigDecimal incomeTaxRate(BgPaySlip slip) {
      return parameter("INCOME_TAX_RATE");
    }

    /**
     * ЦЯЛАТА ВЕРИГА НА ОСИГУРОВКИТЕ, с истинска аритметика.
     *
     * <p>⚠️ Базата се чете от ПЕРАТА през {@code insuranceGross}, точно както в производството — и
     * точно затова редът в {@code prepare()} е измерим оттук.
     */
    @Override
    protected BgContributionResult contributions(BgPaySlip slip) {
      BgInsurableIncomeResult income =
          BgInsurableIncomeService.compute(
              insuranceGross(slip),
              BgMinIncomeResult.resolved(mod, "МОД-представителен", 1),
              null,
              true,
              CAP,
              slip.getPeriod().getFromDate(),
              // НЕВД чл. 1 ал. 3: делител работните дни в месеца, числител отработените.
              BgWorkTimeFacts.byDays(
                  BigDecimal.valueOf(slip.getBgWorkingDaysInPeriod()),
                  BigDecimal.valueOf(slip.getBgWorkedDays()),
                  null));
      insurableIncomeSeen = income.getAmount();
      if (!income.isResolved()) {
        return BgContributionResult.failure(
            BgContributionResult.Status.INPUT_NOT_ASSEMBLED, String.valueOf(income.getDetail()));
      }
      return BgContributionResolver.resolve(
          seedRows(),
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

    /**
     * Годишното изравняване по чл. 49 — БЕЗ справка в базата.
     *
     * <p>⚠️ {@code report()} връща {@code null} нарочно: върху януарски фиш това би било ОТКАЗ, а
     * върху останалите единайсет месеца изобщо не се стига дотам. Златните фишове са от вторите.
     */
    /** Истинската услуга по чл. 177 — виж {@link #generateArt177Leave} защо не е заглушена. */
    private final BgArt177LeaveService art177Leave = new BgArt177LeaveService();

    private final BgArt49SettlementService art49Settlement =
        new BgArt49SettlementService() {
          @Override
          protected BgArt73Para6Report report(Company company, int taxYear) {
            return null;
          }
        };

    // ── петте шева, навързани локално. ⚠️ Редът остава на prepare(). ──────

    @Override
    protected void generateEarnings(BgPaySlip slip) {
      earnings.generate(slip, payrollInput(slip));
    }

    @Override
    protected void generateSickLeave(BgPaySlip slip) {
      sickLeave.generate(slip, payrollInput(slip));
    }

    /**
     * Седмият шев — възнаграждението за платен отпуск по чл. 177, навързан с ИСТИНСКАТА услуга.
     *
     * <p>⚠️ НАВЪРЗАН, А НЕ ЗАГЛУШЕН, и точно затова пази нещо. Златните фишове не носят отпуск по
     * чл. 177, тоест услугата излиза с {@code NOT_APPLICABLE} още преди да поиска база. Изключена,
     * тя нямаше да пази нищо; навързана, тя пази че ЗЛАТНИТЕ ЧИСЛА НЕ СЕ МЕНЯТ от появата на седма
     * стъпка в {@code prepare()} — същият довод като при годишното изравняване по чл. 49.
     */
    @Override
    protected void generateArt177Leave(BgPaySlip slip) {
      new BgPaySlipVerdicts()
          .addArt177Leave(
              BgPaySlipVerdicts.STEP_ART177_LEAVE, art177Leave.generate(slip, payrollInput(slip)))
          .appendInto(slip);
    }

    @Override
    protected void generateCompensations(BgPaySlip slip) {
      compensations.generate(slip, payrollInput(slip));
    }

    @Override
    protected void generateContributions(BgPaySlip slip) {
      contributionLines.generate(slip, contributions(slip));
    }

    @Override
    protected void generateIncomeTax(BgPaySlip slip) {
      // ⚠️ СЪЩИТЕ ДВЕ ЧИСЛА като производството: ставката И облекчението идват от услугата,
      // а не се измислят тук — инак перото и заглавната сума биха могли да се разминат.
      incomeTax.generate(slip, incomeTaxRate(slip), disabilityRelief(slip));
    }

    /**
     * Шестият шев — годишното изравняване по чл. 49, навързано с ИСТИНСКАТА услуга.
     *
     * <p>⚠️ Върху златните фишове то не пише НИЩО и точно затова се навързва, вместо да се изключи:
     * всички те са за месеци извън януари, тоест генераторът излиза преди да поиска справка.
     * Изключен, шевът нямаше да пази нищо; навързан, той пази, че златните числа НЕ СЕ МЕНЯТ от
     * появата на шеста стъпка в {@code prepare()}.
     */
    @Override
    protected void generateAnnualSettlement(BgPaySlip slip) {
      new BgPaySlipVerdicts()
          .addLines(BgPaySlipVerdicts.STEP_ART49_SETTLEMENT, art49Settlement.generate(slip))
          .appendInto(slip);
    }

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

  private static BgPaySlip draft(LocalDate from, LocalDate to, BigDecimal salary) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setPeriod(period(from, to));
    slip.setBgBaseSalary(salary);
    slip.setBgPaySlipLineList(new ArrayList<>());
    return slip;
  }

  private static BgPaySlipLine lineOf(BgPaySlip slip, int number) {
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (line.getBgLineNumber() == number) {
        return line;
      }
    }
    return null;
  }

  /** Перата от 5xx, по номер → сума. Празната карта значи, че генераторът не е записал нищо. */
  private static Map<Integer, BigDecimal> contributionLines(BgPaySlip slip) {
    Map<Integer, BigDecimal> out = new LinkedHashMap<>();
    for (BgPaySlipLine line : BgPaySlipResolver.ordered(slip.getBgPaySlipLineList())) {
      if (BgPaySlipResolver.groupOf(line) == BgPaySlipResolver.GROUP_CONTRIBUTIONS) {
        out.put(line.getBgLineNumber(), line.getBgAmount());
      }
    }
    return out;
  }

  private static BigDecimal sumOfSign(BgPaySlip slip, String sign) {
    BigDecimal total = BigDecimal.ZERO;
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (BgPaySlipResolver.groupOf(line) == BgPaySlipResolver.GROUP_CONTRIBUTIONS
          && sign.equals(line.getBgSign())) {
        total = total.add(line.getBgAmount());
      }
    }
    return total.setScale(2, java.math.RoundingMode.HALF_UP);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // СЦЕНАРИЙ 1 · АСЕН СЪБЕВ, ЮЛИ 2026 — БОЛЕН ЦЕЛИЯ МЕСЕЦ
  // ═══════════════════════════════════════════════════════════════════════════

  private static final LocalDate JULY_FROM = LocalDate.of(2026, 7, 1);
  private static final LocalDate JULY_TO = LocalDate.of(2026, 7, 31);

  /** Юли 2026: 1 юли е сряда, 23 работни дни, нула официални празници. */
  private static final int JULY_WORKING_DAYS = 23;

  private static final BigDecimal ASEN_SALARY = new BigDecimal("1805.00");

  /**
   * МОД за длъжността. ⚠️ ПРЕДСТАВИТЕЛНО число, не снето от фиша — ролята му е една: да е ПО-ВИСОК
   * от болничния, за да се види, че осигурителният доход НЕ се вдига до него.
   */
  private static final BigDecimal ASEN_MOD = new BigDecimal("1077.00");

  private static BgPayrollInput asenInput() {
    return BgPayrollInput.builder()
        .insuredTypeCode("01")
        .laborCategory("3")
        .bornAfter1959(true)
        .birthDate(LocalDate.of(1985, 4, 12))
        .insuranceServiceMonths(120)
        .periodFrom(JULY_FROM)
        .periodTo(JULY_TO)
        .parameterReferenceDate(JULY_FROM)
        .workingDaysInPeriod(JULY_WORKING_DAYS)
        .workedDays(0)
        .baseSalary(ASEN_SALARY)
        .sickLeaveEpisode(
            new BgPayrollInput.SickLeaveEpisode(JULY_FROM, JULY_TO, false, "БЛ-2026-07", "sick_er"))
        .build();
  }

  /** ⚠️ ЕДНО ИЗВИКВАНЕ. Нищо друго не се добавя към фиша. */
  private static BgPaySlip asenPrepared(Payroll[] out) {
    Payroll payroll = new Payroll(asenInput(), JULY_WORKING_DAYS, ASEN_MOD);
    BgPaySlip slip = draft(JULY_FROM, JULY_TO, ASEN_SALARY);
    payroll.prepare(slip);
    payroll.recompute(slip);
    if (out != null) {
      out[0] = payroll;
    }
    return slip;
  }

  /**
   * ⚠️ ТОВА Е ЦЕЛИЯТ ТЕСТ, СВИТ В ЕДИН СВИДЕТЕЛ: фишът, минал само през {@code prepare()}, носи
   * тринайсет пера — и НИТО ЕДНО от тях не е сложено от теста.
   *
   * <p>Сравнява се ЦЕЛИЯТ списък, а не отделни редове: така се лови и перо, което НЕ бива да го
   * има. Проверка ред по ред минава и когато на фиша е излязло тринайсето, четиринайсето и
   * петнайсето перо, за които никой не пита.
   */
  @Test
  @DisplayName(
      "⚠️ ЗЛАТЕН край-до-край · prepare() сам дава 14 пера — целият списък, число по число")
  void thePrepareCallAloneProducesTheWholeSlip() {
    BgPaySlip slip = asenPrepared(null);

    List<String> got = new ArrayList<>();
    for (BgPaySlipLine line : BgPaySlipResolver.ordered(slip.getBgPaySlipLineList())) {
      got.add(
          line.getBgLineNumber()
              + " "
              + line.getBgPaySlipLineType().getCode()
              + " "
              + line.getBgSign()
              + " "
              + line.getBgAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
    }

    assertEquals(
        Arrays.asList(
            // ⚠️ 0,00, И ТОВА Е РЕШЕНИЕ НА РЕЗОЛВЕРА, НЕ ДЕФЕКТ. Нула отработени дни при
            // положителна
            // норма е ЗАКОНЕН случай (цял месец отсъствие) и дава ИЗЧИСЛЕНА нула — виж
            // BgEarningsResolver#baseSalaryLine. Перото документира точно това: заплата за нула
            // отработени дни. Липсата му, обратно, би значела „никой не е питал“.
            "100 pl_osnovna CREDIT 0.00",
            "195 pl_bolnichen_rabotodatel CREDIT 109.87",
            "500 pl_vn_pension_osig CREDIT 9.03",
            "501 pl_vn_pension_lice DEBIT 7.23",
            "504 pl_vn_ozm_osig CREDIT 2.31",
            "505 pl_vn_ozm_lice DEBIT 1.54",
            "506 pl_vn_unemployment_osig CREDIT 0.66",
            "507 pl_vn_unemployment_lice DEBIT 0.44",
            "508 pl_vn_tzpb_osig CREDIT 0.55",
            "510 pl_vn_upf_osig CREDIT 3.08",
            "511 pl_vn_upf_lice DEBIT 2.42",
            // ⚠️ ДВА РЕДА С НОМЕР 516, И РЕДЪТ ИМ Е ТАКЪВ, ЗАЩОТО БОЛНИЧНИЯТ СЕ ВИКА ПЪРВИ.
            // Този с базата по ЗЗО чл. 40 ал. 1 т. 5 (24,13) се създава в generateSickLeave, а
            // нормалният (5,27) — в generateContributions, който е ПОСЛЕДЕН по правило. Двата носят
            // еднакъв номер, тоест BgPaySlipResolver#ordered не ги подрежда помежду им — редът идва
            // от вмъкването. Заковано е тук нарочно: то е НАБЛЮДЕНИЕ, а не изискване, и ако някой
            // ден се смени, свидетелят ще каже, че се е сменило.
            "516 pl_vn_health_osig CREDIT 24.13",
            "516 pl_vn_health_osig CREDIT 5.27",
            "517 pl_vn_health_lice DEBIT 3.52"),
        got,
        "⚠️ Точно тези пера и никакви други, в реда, в който човек чете фиша. Нито едно не е сложено"
            + " от теста — всичките идват от prepare(). Липсват: класът прослужено време (котвата на"
            + " стажа не е във входа → ОТКАЗ, не нула), ГВРС (нулева ставка → LEGAL_ZERO) и ДОД"
            + " (основата е под нула → LEGAL_ZERO).");
  }

  @Test
  @DisplayName(
      "⚠️ ЗЛАТЕН · перото на болничния е 1805,00 / 23 × 70% × 2 = 109,87, и е САМО той в брутото")
  void theSickPayLineIsGenerated() {
    BgPaySlip slip = asenPrepared(null);
    BgPaySlipLine line = lineOf(slip, 195);

    assertNotNull(line, "перото трябва да е на фиша след prepare()");
    assertEquals(
        0,
        new BigDecimal("109.87").compareTo(line.getBgAmount()),
        "сверено срещу реален фиш на Микроинвест за 07.2026 г. Намерено: " + line.getBgAmount());
    assertTrue(line.getBgIsInsuranceIncome(), "осигурителен доход по чл. 40 ал. 5 КСО");
    assertTrue(!line.getBgIsTaxable(), "необлагаем по ЗДДФЛ чл. 24 ал. 2 т. 14");

    assertEquals(2, slip.getBgSickDaysEmployer().intValue(), "чл. 40 ал. 5 КСО — 1 и 2 юли");
    assertEquals(21, slip.getBgSickDaysDoo().intValue(), "останалите 21 работни дни");
    assertEquals(
        0,
        slip.getBgWorkedDays().intValue(),
        "⚠️ болен целия месец значи НУЛА отработени дни — оттам основната заплата няма перо");
    // ⚠️ И ТОВА Е СЪЩЕСТВЕНОТО: перото на основната заплата е 0,00, а не 1805,00.
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(lineOf(slip, 100).getBgAmount()),
        "⚠️ 1805,00 тук значи, че болничните са минали СЛЕД начисленията — тогава лицето получава и"
            + " ПЪЛНА заплата, и болничен за същите дни, а фишът изглежда напълно нормален.");
    assertEquals(
        0,
        new BigDecimal("109.87").compareTo(slip.getBgGrossTotal()),
        "брутото е само болничният — перото с 0,00 не мени сбора, но документира нула отработени дни");
  }

  /**
   * ⚠️ НАЙ-ЛЕСНАТА ГРЕШКА НА ЦЕЛИЯ ФИШ — измерена през {@code prepare()}, не наредена от теста.
   *
   * <p>МОД за длъжността е почти десет пъти болничния. Приложен ЦЯЛ, той щеше да вдигне
   * осигурителния доход до 1077,00 и вноските с него — за месец, в който лицето не е работило нито
   * ден. Пази го пропорцията по НЕВД чл. 1 ал. 3.
   */
  @Test
  @DisplayName("⚠️ ЗЛАТЕН · осигурителният доход е 109,87 и НЕ СЕ ВДИГА до МОД 1077,00")
  void theInsurableIncomeIsNotLiftedToTheMinimum() {
    Payroll[] out = new Payroll[1];
    BgPaySlip slip = asenPrepared(out);

    assertEquals(
        0,
        new BigDecimal("109.87").compareTo(out[0].insurableIncomeSeen),
        "⚠️ базата, върху която генераторът НАИСТИНА е сметнал вноските. Намерено: "
            + out[0].insurableIncomeSeen);
    assertEquals(
        0,
        new BigDecimal("109.87").compareTo(slip.getBgInsuranceIncome()),
        "и заглавната сума е същата — тя е сбор на перата");
  }

  @Test
  @DisplayName("⚠️ ЗЛАТЕН · вноски на ЛИЦЕТО = 15,15, фонд по фонд, АВТОМАТИЧНО генерирани")
  void theEmployeeContributionLinesAreFifteenFifteen() {
    BgPaySlip slip = asenPrepared(null);
    Map<Integer, BigDecimal> lines = contributionLines(slip);

    assertEquals(0, new BigDecimal("7.23").compareTo(lines.get(501)), "Пенсии 6,58%");
    assertEquals(0, new BigDecimal("1.54").compareTo(lines.get(505)), "Общо заболяване 1,4%");
    assertEquals(0, new BigDecimal("0.44").compareTo(lines.get(507)), "Безработица 0,4%");
    assertEquals(0, new BigDecimal("2.42").compareTo(lines.get(511)), "ДЗПО 2,2%");
    assertEquals(0, new BigDecimal("3.52").compareTo(lines.get(517)), "ЗО 3,2%");

    assertEquals(
        0,
        new BigDecimal("15.15").compareTo(sumOfSign(slip, BgPaySlipResolver.DEBIT)),
        "⚠️ 15,15, А НЕ 15,14. Сборът на ставките е 13,78 на сто и 109,87 × 13,78% = 15,140086,"
            + " което закръглено на веднъж дава 15,14. Фишът показва 15,15, защото по чл. 2 ал. 4"
            + " НЕВД ВСЯКА вноска се закръгля ПООТДЕЛНО — и точно затова генераторът само КОПИРА"
            + " закръглените суми, вместо да смята сам.");
  }

  @Test
  @DisplayName("⚠️ ЗЛАТЕН · вноски на РАБОТОДАТЕЛЯ = 20,90, АВТОМАТИЧНО генерирани")
  void theEmployerContributionLinesAreTwentyNinety() {
    BgPaySlip slip = asenPrepared(null);
    Map<Integer, BigDecimal> lines = contributionLines(slip);

    assertEquals(0, new BigDecimal("9.03").compareTo(lines.get(500)), "Пенсии 8,22%");
    assertEquals(0, new BigDecimal("2.31").compareTo(lines.get(504)), "Общо заболяване 2,1%");
    assertEquals(0, new BigDecimal("0.66").compareTo(lines.get(506)), "Безработица 0,6%");
    assertEquals(0, new BigDecimal("0.55").compareTo(lines.get(508)), "ТЗПБ 0,5%");
    assertEquals(0, new BigDecimal("3.08").compareTo(lines.get(510)), "ДЗПО 2,8%");
    assertEquals(0, new BigDecimal("5.27").compareTo(lines.get(516)), "ЗО 4,8%");

    assertEquals(
        0,
        new BigDecimal("12.00").compareTo(lines.get(500).add(lines.get(504)).add(lines.get(506))),
        "ДОО на фиша е 12,00 — сборът на трите фонда, всеки закръглен поотделно");
    assertEquals(
        0,
        new BigDecimal("45.03").compareTo(sumOfSign(slip, BgPaySlipResolver.CREDIT)),
        "⚠️ 12,00 + 3,08 + 0,55 + 5,27 = 20,90 върху ОСИГУРИТЕЛНИЯ ДОХОД, ПЛЮС 24,13 върху базата по"
            + " ЗЗО чл. 40 ал. 1 т. 5 = 45,03. Второто перо влезе с базата-дискриминатор: то е от"
            + " СЪЩИЯ тип 516, но носи bgHealthBaseKind = ART40_1_5, тоест две здравни вноски върху"
            + " РАЗЛИЧНИ бази съществуват едновременно, без да се слеят в едно число.");
  }

  @Test
  @DisplayName("⚠️ ГВРС НЯМА перо — нулевата ставка не прави ред, и липсата ТУК е вярна")
  void theGvrsFundGetsNoLine() {
    BgPaySlip slip = asenPrepared(null);
    Map<Integer, BigDecimal> lines = contributionLines(slip);

    assertNull(lines.get(518), "ГВРС осигурител");
    assertNull(lines.get(519), "ГВРС лице");
    assertEquals(
        11,
        lines.size(),
        "⚠️ ЕДИНАЙСЕТ вноски, не двайсет: пет на лицето, шест на работодателя. И двете ставки на"
            + " ГВРС са 0,0000 с вид LEGAL_ZERO (ЗБДОО чл. 15 ал. 1 спира вноската за 2026 г.), а"
            + " останалите фондове нямат ред за този вид осигурен.");
  }

  /**
   * ⚠️ ДВЕ ПРИЧИНИ, ЕДНА НУЛА, и те не се сливат. ПЪРВО: болничният е {@code bgIsTaxable = false},
   * тоест облагаемите начисления са 0,00. ВТОРО: вноските на лицето (15,15) се ВАДЯТ от основата,
   * тоест тя е −15,15 и резолверът я ограничава до нула.
   */
  @Test
  @DisplayName("⚠️ ЗЛАТЕН · ДОД = 0,00 и перо от 7xx НЯМА — основата е под нула")
  void theIncomeTaxIsZeroAndHasNoLine() {
    BgPaySlip slip = asenPrepared(null);

    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(slip.getBgTaxBase()),
        "вдигнат погрешно флагът, основата щеше да е 109,87 − 15,15 = 94,72 и данъкът 9,47");
    assertEquals(0, BigDecimal.ZERO.compareTo(slip.getBgTaxAmount()), "10 на сто от нула");
    assertNull(
        lineOf(slip, 700),
        "⚠️ Перо с 0,00 в 7xx би твърдяло, че данък е удържан. Липсата му е РЕШЕНИЕ (LEGAL_ZERO).");
    assertNull(
        BgPaySlipResolver.refuseTaxMismatch(slip.getBgTaxAmount(), slip.getBgPaySlipLineList()),
        "и пазачът мълчи: 0,00 = 0,00");
  }

  @Test
  @DisplayName("⚠️ ЗЛАТЕН · нето 94,72 · разход за работодателя 130,77 — от самите пера")
  void theNetAndEmployerCostComeFromTheLines() {
    BgPaySlip slip = asenPrepared(null);

    assertEquals(
        0,
        new BigDecimal("94.72").compareTo(slip.getBgNetTotal()),
        "⚠️ 109,87 − 15,15. Вноските на РАБОТОДАТЕЛЯ (20,90) НЕ участват: те не са доход на лицето."
            + " Влезли в нетото, числото щеше да е 115,62 — по-голямо от самия болничен.");
    assertEquals(
        0,
        new BigDecimal("154.90").compareTo(slip.getBgEmployerCost()),
        "⚠️ 109,87 + 20,90 + 24,13. Без базата-дискриминатор числото би било 130,77, а 24,13 би"
            + " стояло ИЗВЪН фиша — виж свидетеля по-долу.");
  }

  /**
   * ⚠️ ЗДРАВНАТА ВНОСКА ЗА ДНИТЕ ДОО Е ПЕРО — И ТОВА Е СЪЩИНАТА НА ЕТАП 2.
   *
   * <p>Здравната вноска за 21 дни ДОО по ЗЗО чл. 40 ал. 1 т. 5 (24,13) не може просто да се впише в
   * {@code pl_vn_health_osig} (516): група 5xx е огледална на речника на фондовете — по един ред за
   * фонд и платец, точно 20 — и едно перо би слепило две вноски върху РАЗЛИЧНИ бази в едно число:
   * 5,27 върху 109,87 и 24,13 върху 502,78.
   *
   * <p>Затова решението не е двайсет и първи ред в речника, а БАЗА-ДИСКРИМИНАТОР на перото ({@code
   * BgPaySlipLine.bgHealthBaseKind}): двете вноски са ДВА РЕДА от един и същи тип 516, всеки със
   * своята база в {@code bgQuantity} и своята ставка в {@code bgRate}. Слепването е невъзможно по
   * устройство, инвариантът „двайсет типа“ е цял, а фишът вече носи пълния разход за работодателя.
   *
   * <p>⚠️ И ДВЕТЕ ЧИСЛА СЕ ПРОВЕРЯВАТ ПООТДЕЛНО. Свидетел само за сбора (45,03) би минал и при едно
   * перо с 29,40 върху нищо — тоест точно при слепването, срещу което всичко това съществува.
   */
  @Test
  @DisplayName("⚠️ здравната вноска за дните ДОО (24,13) Е ПЕРО, до своята")
  void theHealthContributionForDooDaysIsNowItsOwnLine() {
    BgPaySlip slip = asenPrepared(null);

    List<BgPaySlipLine> health = new ArrayList<>();
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      // ⚠️ БЕЗ ПРОВЕРКА ЗА null: генерираният getter за Integer връща 0, тоест условието би било
      // мъртво (BgDefaultedGetterTest пада точно на такова). Питаме СТОЙНОСТТА.
      if (line.getBgLineNumber() == 516) {
        health.add(line);
      }
    }
    assertEquals(
        2,
        health.size(),
        "⚠️ ДВА реда от тип 516 на един фиш — това е новото и е нарочно. Един ред би значел, че"
            + " двете бази са слепени. Намерени: "
            + health.size());

    BgPaySlipLine standard = null;
    BgPaySlipLine art40 = null;
    for (BgPaySlipLine line : health) {
      if (BgD1ContributionAggregator.BASE_ART40_1_5.equals(line.getBgHealthBaseKind())) {
        art40 = line;
      } else {
        standard = line;
      }
    }
    assertNotNull(standard, "перото върху осигурителния доход трябва да носи ПРАЗНА база");
    assertNotNull(art40, "перото върху базата по чл. 40 ал. 1 т. 5 трябва да носи ART40_1_5");

    assertEquals(
        0,
        new BigDecimal("5.27").compareTo(standard.getBgAmount()),
        "нормалното перо носи САМО вноската върху болничния — 4,8 % от 109,87");
    assertEquals(
        0,
        new BigDecimal("109.87").compareTo(standard.getBgQuantity()),
        "и количеството му Е базата, тоест осигурителният доход");

    assertEquals(
        0,
        new BigDecimal("24.13").compareTo(art40.getBgAmount()),
        "550,66 × 21/23 × 4,8 % = 24,13 — сверено срещу реален фиш на Микроинвест за 07.2026 г.");
    assertEquals(
        0,
        new BigDecimal("502.78").compareTo(art40.getBgQuantity()),
        "⚠️ БАЗАТА, не дните: 550,66 × 21/23. Оттам т. 17 на Декларация обр. 1 чете сумата си, без"
            + " да дели сума на ставка — 24,13 × 100 / 4,80 дава 502,7083…, друго число.");
    assertEquals(
        0,
        new BigDecimal("4.80").compareTo(art40.getBgRate()),
        "ПОЛОВИН вноска: „вноските са за сметка на работодателя … и са РАВНИ НА ДЪЛЖИМАТА ОТ НЕГО"
            + " ЧАСТ от вноската“");

    assertEquals(
        0,
        new BigDecimal("154.90").compareTo(slip.getBgEmployerCost()),
        "и пълният разход за работодателя вече Е на фиша: 109,87 + 20,90 + 24,13");
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // СЦЕНАРИЙ 2 · НОРМАЛНА ЗАПЛАТА, БЕЗ ОТСЪСТВИЯ — МАРТ 2026
  // ═══════════════════════════════════════════════════════════════════════════

  private static final LocalDate MARCH_FROM = LocalDate.of(2026, 3, 1);
  private static final LocalDate MARCH_TO = LocalDate.of(2026, 3, 31);

  /** Март 2026 без официални празници в стъба: 21 работни дни. */
  private static final int MARCH_WORKING_DAYS = 21;

  private static final BigDecimal SALARY = new BigDecimal("1500.00");

  /** МОД, представителен и ПО-НИСЪК от заплатата — тук той не мени базата. */
  private static final BigDecimal MARCH_MOD = new BigDecimal("1077.00");

  private static BgPayrollInput normalInput() {
    return BgPayrollInput.builder()
        .insuredTypeCode("01")
        .laborCategory("3")
        .bornAfter1959(true)
        .birthDate(LocalDate.of(1985, 4, 12))
        .periodFrom(MARCH_FROM)
        .periodTo(MARCH_TO)
        .parameterReferenceDate(MARCH_FROM)
        .workingDaysInPeriod(MARCH_WORKING_DAYS)
        .workedDays(MARCH_WORKING_DAYS)
        .baseSalary(SALARY)
        .build();
  }

  private static BgPaySlip normalPrepared() {
    Payroll payroll = new Payroll(normalInput(), MARCH_WORKING_DAYS, MARCH_MOD);
    BgPaySlip slip = draft(MARCH_FROM, MARCH_TO, SALARY);
    payroll.prepare(slip);
    payroll.recompute(slip);
    return slip;
  }

  /**
   * ⚠️ ПРОСТИЯТ СЛУЧАЙ: заплата без отсъствия.
   *
   * <p>Едно {@code prepare()} дава и трите групи: основната заплата от {@code BgEarningsService},
   * вноските и данъка.
   */
  @Test
  @DisplayName("⚠️ ЗЛАТЕН · нормална заплата 1500,00 → осн. перо + 11 вноски + ДОД, от prepare()")
  void aNormalSalarySlipCarriesEverything() {
    BgPaySlip slip = normalPrepared();

    BgPaySlipLine salary = lineOf(slip, 100);
    assertNotNull(salary, "основното възнаграждение трябва да е перо");
    assertEquals(
        0, SALARY.compareTo(salary.getBgAmount()), "21 от 21 отработени дни = цяла заплата");
    assertNull(
        lineOf(slip, 110),
        "⚠️ Класът прослужено време НЕ е перо: входът няма котва за стажа"
            + " (recognisedServiceStartDate), тоест енджинът се ОТКАЗВА, вместо да сложи нула.");
    assertNull(lineOf(slip, 195), "няма болничен — епизоди в входа няма");

    assertEquals(11, contributionLines(slip).size(), "същите единайсет фонда × платеца");
    assertNotNull(lineOf(slip, 700), "⚠️ и ДОД вече Е перо");
    assertEquals(
        13,
        slip.getBgPaySlipLineList().size(),
        "⚠️ ЦЕЛИЯТ фиш: 1 заплата + 11 вноски + 1 данък. Нито едно перо не е сложено от теста.");
  }

  @Test
  @DisplayName("⚠️ ЗЛАТЕН · вноски: лице 206,70, работодател 285,30 — върху база 1500,00")
  void theNormalSlipContributions() {
    BgPaySlip slip = normalPrepared();
    Map<Integer, BigDecimal> lines = contributionLines(slip);

    assertEquals(
        0,
        new BigDecimal("1500.00").compareTo(slip.getBgInsuranceIncome()),
        "заплатата е под тавана (2111,64) и над МОД — базата е самата тя");

    assertEquals(0, new BigDecimal("98.70").compareTo(lines.get(501)), "Пенсии лице 6,58%");
    assertEquals(0, new BigDecimal("21.00").compareTo(lines.get(505)), "ОЗМ лице 1,4%");
    assertEquals(0, new BigDecimal("6.00").compareTo(lines.get(507)), "Безработица лице 0,4%");
    assertEquals(0, new BigDecimal("33.00").compareTo(lines.get(511)), "ДЗПО лице 2,2%");
    assertEquals(0, new BigDecimal("48.00").compareTo(lines.get(517)), "ЗО лице 3,2%");
    assertEquals(0, new BigDecimal("206.70").compareTo(sumOfSign(slip, BgPaySlipResolver.DEBIT)));

    assertEquals(0, new BigDecimal("123.30").compareTo(lines.get(500)), "Пенсии осиг. 8,22%");
    assertEquals(0, new BigDecimal("31.50").compareTo(lines.get(504)), "ОЗМ осиг. 2,1%");
    assertEquals(0, new BigDecimal("9.00").compareTo(lines.get(506)), "Безработица осиг. 0,6%");
    assertEquals(0, new BigDecimal("7.50").compareTo(lines.get(508)), "ТЗПБ 0,5%");
    assertEquals(0, new BigDecimal("42.00").compareTo(lines.get(510)), "ДЗПО осиг. 2,8%");
    assertEquals(0, new BigDecimal("72.00").compareTo(lines.get(516)), "ЗО осиг. 4,8%");
    assertEquals(0, new BigDecimal("285.30").compareTo(sumOfSign(slip, BgPaySlipResolver.CREDIT)));
  }

  /**
   * ⚠️ ДАНЪКЪТ Е 10 НА СТО ВЪРХУ 1293,30, НЕ ВЪРХУ 1500,00 — и разликата от 20,67 е точно това,
   * което редът в {@code prepare()} пази.
   */
  @Test
  @DisplayName("⚠️ ЗЛАТЕН · ДОД = 10% × (1500,00 − 206,70) = 129,33, перо 700 DEBIT")
  void theNormalSlipIncomeTax() {
    BgPaySlip slip = normalPrepared();
    BgPaySlipLine tax = lineOf(slip, 700);

    assertNotNull(tax, "перото на данъка трябва да е на фиша");
    assertEquals("pl_dod", tax.getBgPaySlipLineType().getCode());
    assertEquals("DEBIT", tax.getBgSign(), "данъкът се УДЪРЖА");
    assertEquals(
        0,
        new BigDecimal("1293.30").compareTo(slip.getBgTaxBase()),
        "1500,00 облагаеми − 206,70 лични вноски");
    assertEquals(
        0,
        new BigDecimal("129.33").compareTo(tax.getBgAmount()),
        "⚠️ 10 на сто по чл. 42 ЗДДФЛ. Числото 150,00 тук значи, че данъкът е минал ПРЕДИ"
            + " осигуровките и е сметнат върху цялата заплата — 20,67 повече, удържани мълчаливо.");
    assertEquals(
        0,
        new BigDecimal("1293.30").compareTo(tax.getBgQuantity()),
        "количеството е основата — документацията на сумата");
    assertEquals(
        0,
        parameter("INCOME_TAX_RATE").compareTo(tax.getBgRate()),
        "⚠️ ставката е ОТ SEED-А, не число в теста");

    assertNull(
        BgPaySlipResolver.refuseTaxMismatch(slip.getBgTaxAmount(), slip.getBgPaySlipLineList()),
        "⚠️ И ТОВА Е НОВОТО: пазачът мълчи БЕЗ ръчно перо за данъка.");
  }

  @Test
  @DisplayName("⚠️ ЗЛАТЕН · нето 1163,97 · разход за работодателя 1785,30")
  void theNormalSlipNetAndCost() {
    BgPaySlip slip = normalPrepared();

    assertEquals(0, new BigDecimal("1500.00").compareTo(slip.getBgGrossTotal()));
    assertEquals(
        0,
        new BigDecimal("1163.97").compareTo(slip.getBgNetTotal()),
        "1500,00 − 206,70 (лични вноски) − 129,33 (данък)");
    assertEquals(
        0, new BigDecimal("1785.30").compareTo(slip.getBgEmployerCost()), "1500,00 + 285,30");
  }

  /**
   * ⚠️ ПЕЧАТНИЯТ ФИШ ЧЕТЕ ТОЧНО ТЕЗИ ЧИСЛА — свидетелят на разпечатката, върху ЗЛАТНИЯ фиш.
   *
   * <p><b>Защо е ТУК, а не в теста на самата услуга.</b> Разделянето на вноските на две страни е
   * единственото, което печатният образец добавя към записаното на фиша — и то трябва да се сверява
   * срещу фиш, СГЛОБЕН ОТ {@code prepare()}, а не срещу пера, сложени от теста. Върху ръчно
   * построен фиш сборът по знак винаги излиза: тестът щеше да сверява себе си.
   *
   * <p>206,70 + 285,30 = 492,00 — и нито едно от двете не е ново число: те са същите, които {@code
   * theNormalSlipContributions} брои перо по перо.
   */
  @Test
  @DisplayName("⚠️ ЗЛАТЕН · печатният фиш: лице 206,70 · работодател 285,30 · сверката е чиста")
  void thePrintedSlipSplitsTheTwoContributionSides() {
    BgPaySlip slip = normalPrepared();
    BgPaySlipPrintSummary summary = BgPaySlipPrintSummary.of(slip);

    assertEquals(
        0,
        new BigDecimal("206.70").compareTo(summary.getEmployeeContributions()),
        "⚠️ УДЪРЖАНОТО от лицето — петте DEBIT пера от 5xx. Число, различно от това, значи, че"
            + " разпечатката показва удръжка, каквато фишът не носи.");
    assertEquals(
        0,
        new BigDecimal("285.30").compareTo(summary.getEmployerContributions()),
        "⚠️ ЗА СМЕТКА НА РАБОТОДАТЕЛЯ — шестте CREDIT пера от 5xx. 492,00 тук значи, че двете страни"
            + " са слети, тоест че листът твърди удръжка от 492,00 при нето 1163,97.");
    assertEquals(
        0,
        new BigDecimal("129.33").compareTo(summary.getTaxFromLines()),
        "данъкът, както го носят ПЕРАТА — същото число, което пълни заглавната част");
    assertTrue(
        summary.getFindings().isEmpty(),
        "⚠️ Върху фиш, излязъл от prepare(), сверката е ПРАЗНА по устройство: заглавните суми са"
            + " сборове на същите пера. Находка тук значи, че recompute() и отчетът броят по"
            + " различни правила.\n  "
            + String.join("\n  ", summary.getFindings()));

    assertEquals(
        0,
        summary
            .getEmployeeContributions()
            .add(new BigDecimal("129.33"))
            .compareTo(new BigDecimal("1500.00").subtract(slip.getBgNetTotal())),
        "⚠️ И ЛИСТЪТ СЕ СЪБИРА: бруто − (лични вноски + данък) = нето. Ако тази проверка падне,"
            + " разпечатката показва три числа, които не се обясняват едно с друго.");
  }

  /**
   * ⚠️ ЦЕЛИЯТ ФИШ, ПРЕМИНАЛ ДВА ПЪТИ ПРЕЗ {@code prepare()} — нито едно перо повече, нито едно
   * число различно.
   *
   * <p>Идемпотентността на всеки генератор е закована поотделно; тук се проверява цялата верига
   * наведнъж, защото точно това прави операторът: отваря фиша, гледа го и го отваря пак.
   */
  @Test
  @DisplayName("⚠️ ЗЛАТЕН · второ prepare() върху същия фиш не мени НИТО едно число")
  void theWholeChainIsIdempotent() {
    Payroll payroll = new Payroll(normalInput(), MARCH_WORKING_DAYS, MARCH_MOD);
    BgPaySlip slip = draft(MARCH_FROM, MARCH_TO, SALARY);

    payroll.prepare(slip);
    payroll.recompute(slip);
    int lines = slip.getBgPaySlipLineList().size();
    BigDecimal net = slip.getBgNetTotal();
    BigDecimal cost = slip.getBgEmployerCost();

    payroll.prepare(slip);
    payroll.recompute(slip);

    assertEquals(lines, slip.getBgPaySlipLineList().size(), "⚠️ дублирани пера при второ отваряне");
    assertEquals(0, net.compareTo(slip.getBgNetTotal()), "нетото се промени");
    assertEquals(0, cost.compareTo(slip.getBgEmployerCost()), "разходът се промени");
    assertEquals(
        0,
        new BigDecimal("129.33").compareTo(lineOf(slip, 700).getBgAmount()),
        "⚠️ данъкът НЕ се натрупва и не се смята върху себе си");
  }
}

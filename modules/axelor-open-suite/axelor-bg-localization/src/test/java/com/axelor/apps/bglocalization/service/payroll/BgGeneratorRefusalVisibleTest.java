package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgAbsenceType;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgCompensationResolver;
import com.axelor.apps.bglocalization.service.BgContributionLine;
import com.axelor.apps.bglocalization.service.BgContributionLine.Payer;
import com.axelor.apps.bglocalization.service.BgContributionResult;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
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
 * ⚠️ ОТКАЗЪТ НА ВСЕКИ ГЕНЕРАТОР СТИГА ДО ФИША.
 *
 * <p><b>Какво се мери.</b> Четирите генератора смятат по една присъда за всяко перо и {@code
 * BgPaySlipService} не бива да изхвърля върнатия {@code List<Outcome>}. Отвън отказът иначе е
 * неразличим от нормалното състояние — перо няма и в двата случая. Тоест „болничният не е начислен,
 * защото осигурителният стаж не е подаден“ изглежда точно като „нямаше болничен“, и това е тихо
 * недоплащане.
 *
 * <p><b>⚠️ ВСЕКИ СВИДЕТЕЛ ПИТА ДВЕТЕ ПОЛОВИНИ ЗАЕДНО, А НЕ ПООТДЕЛНО.</b>
 *
 * <ol>
 *   <li><b>Парите са същите.</b> Перо НЕ се създава при отказ. Тази половина е регресия-пазач:
 *       докладът е за ВИДИМОСТ, не за преизчисляване, и генератор, който при отказ започне да пише
 *       перо с нула, би бил по-лош от мълчанието.
 *   <li><b>Причината СЕ ВИЖДА.</b> Същият обход оставя върху фиша ред в „Какво липсва“, който
 *       НАЗОВАВА и стъпката, и липсата.
 * </ol>
 *
 * <p><b>⚠️ ПОДМЕНЕНИ СА САМО ЧЕТЦИТЕ, НЕ ПРАВИЛАТА</b> — същият идиом като в четирите заварени
 * теста на генераторите. Резолверите се викат ИСТИНСКИ, тоест сумите и отказите тук са техни, не
 * измислени. Оттам тестът тече без база, без Guice и без Hibernate — и НЕ се пропуска.
 */
class BgGeneratorRefusalVisibleTest {

  // ═══ ОБЩИ ПОМОЩНИЦИ ═══════════════════════════════════════════════════════

  /** Присъдите на една стъпка, изкарани върху фиша точно както прави {@code prepare()}. */
  private static BgPaySlip reportOnto(BgPaySlip slip, BgPaySlipVerdicts verdicts) {
    BgPaySlipVerdicts.reset(slip);
    verdicts.appendInto(slip);
    BgPaySlipVerdicts.seal(slip);
    return slip;
  }

  private static BgPaySlipLine lineWithCode(BgPaySlip slip, String code) {
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      BgPaySlipLineType type = line.getBgPaySlipLineType();
      if (type != null && code.equals(type.getCode())) {
        return line;
      }
    }
    return null;
  }

  private static BgPaySlipLineType type(
      int number, String code, String name, String sign, String calcBase) {
    BgPaySlipLineType t = new BgPaySlipLineType();
    t.setId((long) number);
    t.setCode(code);
    t.setNumber(number);
    t.setName(name);
    t.setBgSign(sign);
    t.setBgIsTaxable(false);
    t.setBgIsInsuranceIncome(true);
    t.setBgIsPermanentCharacter(false);
    t.setBgCalculationBase(calcBase);
    return t;
  }

  /** ДОСЛОВНО копие на {@code BgPaySlipService#lineFrom} — замразяване чрез копиране. */
  private static BgPaySlipLine copyFrom(BgPaySlipLineType type, BigDecimal amount) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgPaySlipLineType(type);
    line.setBgLineNumber(type.getNumber());
    line.setName(type.getName());
    line.setBgSign(type.getBgSign());
    line.setBgIsTaxable(type.getBgIsTaxable());
    line.setBgIsInsuranceIncome(type.getBgIsInsuranceIncome());
    line.setBgIsPermanentCharacter(type.getBgIsPermanentCharacter());
    line.setBgAmount(amount);
    return line;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // 1 · НАЧИСЛЕНИЯТА — непопълнена основна заплата
  // ═══════════════════════════════════════════════════════════════════════════

  private static final LocalDate MAR_FROM = LocalDate.of(2026, 3, 1);
  private static final LocalDate MAR_TO = LocalDate.of(2026, 3, 31);

  private static List<BgPaySlipLineType> earningsCatalogue() {
    return Arrays.asList(
        type(
            100,
            BgEarningsResolver.LINE_BASE_SALARY,
            "Основно възнаграждение",
            "CREDIT",
            BgEarningsResolver.CALC_CONTRACT_BASE_SALARY),
        type(
            110,
            BgEarningsResolver.LINE_SENIORITY,
            "Клас прослужено време",
            "CREDIT",
            BgEarningsResolver.CALC_CONTRACT_BASE_SALARY));
  }

  private static BgEarningsService earnings() {
    return new BgEarningsService() {
      @Override
      protected BigDecimal statutoryMinPct(LocalDate date) {
        return new BigDecimal("0.6000");
      }

      /**
       * ⚠️ ЧАСОВИТЕ СТАВКИ НЕ СЕ ЗАРЕЖДАТ, И ТОВА Е ОТГОВОР, А НЕ ПРОПУСК. Този свидетел не гледа
       * часови пера; без подмяна обаче четецът иска Postgres, защото {@code factsOf} вади и седемте
       * параметъра НАВЕДНЪЖ. Липсващият параметър е ОТКАЗ на съответното перо — точно каквото
       * трябва да получи фикстура, която не е обявила нито една часова ставка.
       */
      @Override
      protected BigDecimal payrollParameter(String code, LocalDate date) {
        return null;
      }

      /** Няма валидирани допълнителни часове → няма уговорено увеличение. Инак иска Hibernate. */
      @Override
      protected java.util.Map<String, BigDecimal> agreedOvertimeUplift(
          com.axelor.apps.bglocalization.db.BgPaySlip slip,
          com.axelor.apps.bglocalization.service.payroll.BgPayrollInput input) {
        return java.util.Collections.emptyMap();
      }

      @Override
      protected List<BgPaySlipLineType> lineTypes() {
        return earningsCatalogue();
      }

      @Override
      protected BgPaySlipLineType lineType(String code) {
        for (BgPaySlipLineType t : earningsCatalogue()) {
          if (t.getCode().equals(code)) {
            return t;
          }
        }
        return null;
      }

      @Override
      protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
        return copyFrom(type, amount);
      }

      @Override
      protected Boolean seniorityEntitlement(BgPaySlip slip) {
        return Boolean.TRUE;
      }
    };
  }

  private static BgPaySlip marchSlip(String baseSalary) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setBgBaseSalary(new BigDecimal(baseSalary));
    slip.setBgWorkedDays(21);
    slip.setBgWorkingDaysInPeriod(21);
    slip.setBgPaySlipLineList(new ArrayList<>());
    Period period = new Period();
    period.setFromDate(MAR_FROM);
    period.setToDate(MAR_TO);
    slip.setPeriod(period);
    EmploymentContract contract = new EmploymentContract();
    contract.setPayCompany(new Company());
    slip.setEmploymentContract(contract);
    return slip;
  }

  private static BgPayrollInput marchInput() {
    return BgPayrollInput.builder()
        .periodFrom(MAR_FROM)
        .periodTo(MAR_TO)
        .parameterReferenceDate(MAR_FROM)
        .recognisedServiceStartDate(LocalDate.of(2016, 3, 1))
        .build();
  }

  /**
   * FAIL-FIRST: изхвърлят ли се присъдите → {@code bgGeneratorGaps} остава празно и тестът пада,
   * докато перото си остава липсващо.
   */
  @Test
  @DisplayName("⚠️ НАЧИСЛЕНИЯ · непопълнена заплата → перо НЯМА, но причината СЕ ЧЕТЕ на фиша")
  void anUnsetBaseSalaryIsRefusedOutLoud() {
    BgPaySlip slip = marchSlip("0.00");

    List<BgEarningsOutcome> outcomes = earnings().generate(slip, marchInput());
    reportOnto(
        slip, new BgPaySlipVerdicts().addEarnings(BgPaySlipVerdicts.STEP_EARNINGS, outcomes));

    // ── половина 1: парите са същите — перо със сума нула би значело „заплата 0,00 лв.“
    assertTrue(slip.getBgPaySlipLineList().isEmpty(), "нито едно перо при отказ, както и преди");

    // ── половина 2: отказът се вижда
    assertEquals(2, slip.getBgGeneratorRefusalCount().intValue(), "основната И класът се отказват");
    assertTrue(
        slip.getBgGeneratorGaps().contains(BgPaySlipVerdicts.STEP_EARNINGS),
        slip.getBgGeneratorGaps());
    assertTrue(
        slip.getBgGeneratorGaps().contains("основната заплата"),
        "⚠️ отказът трябва да КАЗВА какво липсва: " + slip.getBgGeneratorGaps());
    assertNotNull(slip.getBgGeneratedAt(), "докладът има възраст");
  }

  @Test
  @DisplayName("РЕГРЕСИЯ · попълнена заплата → перо 1500,00 непроменено и НУЛА откази")
  void aCompleteInputIsUnchangedAndSilent() {
    BgPaySlip slip = marchSlip("1500.00");

    List<BgEarningsOutcome> outcomes = earnings().generate(slip, marchInput());
    reportOnto(
        slip, new BgPaySlipVerdicts().addEarnings(BgPaySlipVerdicts.STEP_EARNINGS, outcomes));

    BgPaySlipLine base = lineWithCode(slip, BgEarningsResolver.LINE_BASE_SALARY);
    assertNotNull(base, "нормалният случай пише перото си");
    assertEquals(new BigDecimal("1500.00"), base.getBgAmount(), "числото не е мръднало");
    assertEquals(0, slip.getBgGeneratorRefusalCount().intValue(), "няма какво да се поправя");
    assertTrue(
        slip.getBgGeneratorGaps() == null || slip.getBgGeneratorGaps().isEmpty(),
        "празен списък „какво липсва“ е ВЕРНИЯТ изход, а не празен доклад");
    assertTrue(
        slip.getBgGeneratorNotes().contains("1500.00"),
        "⚠️ пълният доклад носи и сметнатото — иначе не отговаря на „защо е това число“: "
            + slip.getBgGeneratorNotes());
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // 2 · БОЛНИЧНИЯТ — неподаден осигурителен стаж (чл. 40 ал. 1 КСО)
  // ═══════════════════════════════════════════════════════════════════════════

  private static final LocalDate NOV_FROM = LocalDate.of(2026, 11, 1);
  private static final LocalDate NOV_TO = LocalDate.of(2026, 11, 30);
  private static final int NOV_WORKING_DAYS = 21;
  private static final BigDecimal NOV_SALARY = new BigDecimal("2000.00");

  private static BgAbsenceType absence(String code, String payer, String pct) {
    BgAbsenceType t = new BgAbsenceType();
    t.setCode(code);
    t.setPayer(payer);
    t.setCompensationPct(new BigDecimal(pct));
    t.setRequiresSickNote(true); // вид на болничен лист, като в сийда
    t.setCountsAsInsuranceService(true);
    return t;
  }

  private static Map<String, BgAbsenceType> absenceDictionary() {
    Map<String, BgAbsenceType> out = new LinkedHashMap<>();
    out.put("sick_er", absence("sick_er", "employer", "70"));
    out.put("sick_doo", absence("sick_doo", "doo", "80"));
    return out;
  }

  /** Услугата с подменени четци — правилата ѝ остават нейни. */
  private static final class SickLeave extends BgSickLeaveServiceOnDeclaredStage {

    /** Базата на 195 — от СЪЩИЯ енджин на начисленията (M, както за чл. 177). */
    @Override
    protected BigDecimal monthlyPermanentPay(BgPaySlip slip, BgPayrollInput input) {
      return earnings().monthlyPermanentPay(slip, input);
    }

    final BgPaySlipLineType sickPayType =
        type(
            195,
            BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY,
            "Болничен от осигурител",
            "CREDIT",
            "DERIVED");

    @Override
    protected BgAbsenceType absenceType(String code) {
      return absenceDictionary().get(code);
    }

    @Override
    protected Set<LocalDate> holidaysIn(LocalDate from, LocalDate to) {
      return Collections.emptySet();
    }

    @Override
    protected BigDecimal healthAbsenceBase(LocalDate date) {
      return new BigDecimal("620.2000");
    }

    @Override
    protected BigDecimal healthEmployerRatePct(BgPayrollInput input, LocalDate date) {
      return new BigDecimal("4.8000");
    }

    @Override
    protected void writeSpecialHealthBase(
        BgPaySlip slip, BigDecimal base, BigDecimal ratePercent, BigDecimal amount) {
      // Заглушен: това перо е на генератора на 5xx и не е предмет на този тест.
    }

    @Override
    protected BgPaySlipLineType lineType(String code) {
      return BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY.equals(code) ? sickPayType : null;
    }

    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
      return copyFrom(type, amount);
    }
  }

  private static BgPaySlip novemberSlip() {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    Period p = new Period();
    p.setName("11/PY2026");
    p.setFromDate(NOV_FROM);
    p.setToDate(NOV_TO);
    slip.setPeriod(p);
    slip.setBgBaseSalary(NOV_SALARY);
    slip.setBgWorkingDaysInPeriod(NOV_WORKING_DAYS);
    slip.setBgWorkedDays(NOV_WORKING_DAYS);
    slip.setBgPaySlipLineList(new ArrayList<>());
    return slip;
  }

  /** Понеделник–сряда, ноември 2026 — трите дни на закования случай на Ф0. */
  private static BgPayrollInput.SickLeaveEpisode monToWed() {
    return new BgPayrollInput.SickLeaveEpisode(
        LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 4), false, "БЛ-700", "sick_er");
  }

  private static BgPayrollInput novemberInput(Integer serviceMonths) {
    BgPayrollInput.Builder b =
        BgPayrollInput.builder()
            .insuredTypeCode("01")
            .laborCategory("3")
            .birthDate(LocalDate.of(1985, 4, 12))
            .periodFrom(NOV_FROM)
            .periodTo(NOV_TO)
            .parameterReferenceDate(NOV_FROM)
            .workingDaysInPeriod(NOV_WORKING_DAYS)
            .baseSalary(NOV_SALARY)
            .sickLeaveEpisode(monToWed());
    if (serviceMonths != null) {
      b.insuranceServiceMonths(serviceMonths);
    }
    return b.build();
  }

  /**
   * ⚠️ ТОЧНО ПРИМЕРЪТ ОТ ЗАДАНИЕТО: неподаден осигурителен стаж. Обезщетението по чл. 40 ал. 1 КСО
   * иска шест месеца стаж; при неизвестен стаж енджинът ОТКАЗВА, а не смята нула.
   *
   * <p>FAIL-FIRST: изхвърлят ли се присъдите → фишът излиза с нула болничен, нула дни и празен
   * доклад.
   */
  @Test
  @DisplayName("⚠️ БОЛНИЧЕН · неподаден стаж → нищо не се пипа, но отказът СЕ ЧЕТЕ на фиша")
  void anUnknownServiceLengthIsRefusedOutLoud() {
    SickLeave service = new SickLeave();
    BgPaySlip slip = novemberSlip();

    List<BgSickLeaveOutcome> outcomes = service.generate(slip, novemberInput(null));
    reportOnto(
        slip, new BgPaySlipVerdicts().addSickLeave(BgPaySlipVerdicts.STEP_SICK_LEAVE, outcomes));

    // ── половина 1: нищо не е мръднало
    assertTrue(slip.getBgPaySlipLineList().isEmpty(), "перо НЕ се създава — както и преди");
    assertEquals(
        0,
        slip.getBgSickDaysEmployer().intValue(),
        "⚠️ записана нула при ОТКАЗ изглежда като изчислена и махва основанието от екрана");
    assertEquals(
        NOV_WORKING_DAYS,
        slip.getBgWorkedDays().intValue(),
        "отработените дни също не се пипат — намалени, те биха обрязали заплатата без насрещно"
            + " обезщетение");

    // ── половина 2: отказът се вижда
    assertTrue(slip.getBgGeneratorRefusalCount().intValue() >= 1, slip.getBgGeneratorNotes());
    assertTrue(
        slip.getBgGeneratorGaps().contains(BgPaySlipVerdicts.STEP_SICK_LEAVE),
        slip.getBgGeneratorGaps());
    assertTrue(
        slip.getBgGeneratorGaps().toLowerCase().contains("стаж"),
        "⚠️ отказът трябва да НАЗОВАВА стажа, инак праща човека да търси: "
            + slip.getBgGeneratorGaps());
  }

  /**
   * ⚠️ ГЕЙТЪТ НА Ф0, МИНАТ ПРЕЗ ЦЕЛИЯ ГЕНЕРАТОР: перо 195 = 133,33 и разбивът 2/1.
   *
   * <p>Същият закован случай като {@code BgSickLeavePeriodLineInvariantTest}, но питан през {@code
   * generate()}, а не през чистия резолвер — тоест мери и това, че докладът, добавен около обхода,
   * не е мръднал нито едно число по пътя.
   */
  @Test
  @DisplayName("⚠️ ИНВАРИАНТ Ф0 · перо 195 = 133,33 и дни 2/1 през целия генератор, НУЛА откази")
  void theF0InvariantSurvivesTheReport() {
    SickLeave service = new SickLeave();
    BgPaySlip slip = novemberSlip();

    List<BgSickLeaveOutcome> outcomes = service.generate(slip, novemberInput(120));
    reportOnto(
        slip, new BgPaySlipVerdicts().addSickLeave(BgPaySlipVerdicts.STEP_SICK_LEAVE, outcomes));

    BgPaySlipLine line = lineWithCode(slip, BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY);
    assertNotNull(line, "перото на болничния трябва да го има");
    assertEquals(
        new BigDecimal("133.33"),
        line.getBgAmount(),
        "чл. 40 ал. 5 КСО: 2000,00 × 70 % × 2 дни / 21 работни дни. Мръднало ли е това число,"
            + " докладът е започнал да храни сметката, вместо да я оглежда.");
    assertEquals(195, line.getBgLineNumber().intValue(), "перо 195, както преди");
    assertEquals(2, slip.getBgSickDaysEmployer().intValue(), "първите ДВА работни дни");
    assertEquals(1, slip.getBgSickDaysDoo().intValue(), "третият остава за ДОО");
    assertEquals(
        NOV_WORKING_DAYS - 3,
        slip.getBgWorkedDays().intValue(),
        "и сборът на дните е непроменен — основното се смята от него");

    assertEquals(0, slip.getBgGeneratorRefusalCount().intValue(), slip.getBgGeneratorNotes());
    assertTrue(
        slip.getBgGeneratorGaps() == null || slip.getBgGeneratorGaps().isEmpty(),
        "нормалният случай не иска поправка");
    assertTrue(slip.getBgGeneratorNotes().contains("133.33"), slip.getBgGeneratorNotes());
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // 3 · ОБЕЗЩЕТЕНИЯТА — документ без вид
  // ═══════════════════════════════════════════════════════════════════════════

  private static final class Compensations extends BgCompensationLineService {

    final Map<String, BgPaySlipLineType> catalogue = new LinkedHashMap<>();
    List<BgCompensation> docs = new ArrayList<>();

    @Override
    protected List<BgCompensation> documents(BgPayrollInput input) {
      return docs;
    }

    @Override
    protected BgPaySlipLineType lineTypeFor(BgCompensationType kind) {
      return kind == null ? null : catalogue.get(kind.getCode());
    }

    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
      return copyFrom(type, amount);
    }
  }

  private static BgCompensationType kind(String code) {
    BgCompensationType k = new BgCompensationType();
    k.setId((long) code.hashCode());
    k.setCode(code);
    k.setName(code);
    k.setIsTaxable(true);
    k.setSocialContributionsDue(true);
    return k;
  }

  private static BgCompensation doc(BgCompensationType kind, String amount) {
    BgCompensation d = new BgCompensation();
    d.setId(11L);
    d.setReference("OBEZ-2026-0001");
    d.setBgCompensationType(kind);
    d.setBgFinalAmount(amount == null ? null : new BigDecimal(amount));
    d.setStatusSelect(BgCompensationResolver.CONFIRMED);
    if (kind != null) {
      d.setBgIsTaxable(kind.getIsTaxable());
      d.setBgSocialContributionsDue(kind.getSocialContributionsDue());
    }
    return d;
  }

  @Test
  @DisplayName("⚠️ ОБЕЗЩЕТЕНИЯ · документ без вид → перо НЯМА, а отказът НАЗОВАВА номера му")
  void aCompensationWithoutAKindIsRefusedOutLoud() {
    Compensations service = new Compensations();
    service.catalogue.put(
        "comp_kt_222_1", type(900, "pl_obez_kt_222_1", "Обезщетение", "CREDIT", "DERIVED"));
    BgCompensation broken = doc(kind("comp_kt_222_1"), "100.00");
    broken.setBgCompensationType(null);
    service.docs = Arrays.asList(broken);
    BgPaySlip slip = marchSlip("1500.00");
    slip.getBgPaySlipLineList().clear();

    List<BgPaySlipLineOutcome> outcomes = service.generate(slip, marchInput());
    reportOnto(
        slip, new BgPaySlipVerdicts().addLines(BgPaySlipVerdicts.STEP_COMPENSATIONS, outcomes));

    assertTrue(slip.getBgPaySlipLineList().isEmpty(), "безтипов ред НЕ се създава — както и преди");
    assertEquals(1, slip.getBgGeneratorRefusalCount().intValue());
    assertTrue(
        slip.getBgGeneratorGaps().contains("OBEZ-2026-0001"),
        "⚠️ фишът може да носи няколко документа — отказът трябва да казва КОЙ: "
            + slip.getBgGeneratorGaps());
    assertTrue(
        slip.getBgGeneratorGaps().contains(BgPaySlipVerdicts.STEP_COMPENSATIONS),
        slip.getBgGeneratorGaps());
  }

  @Test
  @DisplayName("РЕГРЕСИЯ · редовен документ → перо 100,00 непроменено и НУЛА откази")
  void aValidCompensationIsUnchangedAndSilent() {
    Compensations service = new Compensations();
    service.catalogue.put(
        "comp_kt_222_1", type(900, "pl_obez_kt_222_1", "Обезщетение", "CREDIT", "DERIVED"));
    service.docs = Arrays.asList(doc(kind("comp_kt_222_1"), "100.00"));
    BgPaySlip slip = marchSlip("1500.00");
    slip.getBgPaySlipLineList().clear();

    List<BgPaySlipLineOutcome> outcomes = service.generate(slip, marchInput());
    reportOnto(
        slip, new BgPaySlipVerdicts().addLines(BgPaySlipVerdicts.STEP_COMPENSATIONS, outcomes));

    BgPaySlipLine line = lineWithCode(slip, "pl_obez_kt_222_1");
    assertNotNull(line, "перото на обезщетението трябва да го има");
    assertEquals(new BigDecimal("100.00"), line.getBgAmount(), "замразената сума не се пипа");
    assertEquals(0, slip.getBgGeneratorRefusalCount().intValue(), slip.getBgGeneratorNotes());
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // 4 · ОСИГУРОВКИТЕ — незавършена верига
  // ═══════════════════════════════════════════════════════════════════════════

  private static final class Contributions extends BgContributionLineService {

    final Map<String, BgPaySlipLineType> catalogue = new LinkedHashMap<>();

    @Override
    protected BgPaySlipLineType lineTypeFor(String fundCode, String sign) {
      return catalogue.get(fundCode + "/" + sign);
    }

    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
      return copyFrom(type, amount);
    }
  }

  private static Contributions contributions() {
    Contributions service = new Contributions();
    service.catalogue.put(
        "PENSION/CREDIT",
        type(500, "pl_vn_pension_rab", "Пенсии · осигурител", "CREDIT", "DERIVED"));
    service.catalogue.put(
        "PENSION/DEBIT", type(501, "pl_vn_pension_lice", "Пенсии · лице", "DEBIT", "DERIVED"));
    return service;
  }

  /**
   * ⚠️ ЕДИН ОТКАЗ ЗА ЦЯЛАТА ГРУПА, НЕ ПО ЕДИН НА ФОНД: веригата изобщо не е стигнала до фондовете.
   * Изхвърли ли се тази присъда, фишът излиза БЕЗ НИТО ЕДНА ОСИГУРОВКА, мълчаливо.
   */
  @Test
  @DisplayName("⚠️ ОСИГУРОВКИ · незавършена верига → нито едно перо, а причината СЕ ЧЕТЕ на фиша")
  void anUnresolvedContributionChainIsRefusedOutLoud() {
    BgPaySlip slip = marchSlip("1500.00");
    slip.getBgPaySlipLineList().clear();

    List<BgPaySlipLineOutcome> outcomes =
        contributions()
            .generate(
                slip,
                BgContributionResult.failure(
                    BgContributionResult.Status.NO_CONTRIBUTIONS_FOUND, "видът осигурен е празен"));
    reportOnto(
        slip, new BgPaySlipVerdicts().addLines(BgPaySlipVerdicts.STEP_CONTRIBUTIONS, outcomes));

    assertTrue(slip.getBgPaySlipLineList().isEmpty(), "нито едно перо — както и преди");
    assertEquals(1, slip.getBgGeneratorRefusalCount().intValue(), "един отказ за цялата група");
    assertTrue(
        slip.getBgGeneratorGaps().contains("NO_CONTRIBUTIONS_FOUND"),
        "⚠️ статусът трябва да е В причината — той различава „не е сглобен входът“ от „няма"
            + " намерени вноски“, а това са два различни файла за поправяне: "
            + slip.getBgGeneratorGaps());
    assertTrue(
        slip.getBgGeneratorGaps().contains("видът осигурен е празен"), slip.getBgGeneratorGaps());
  }

  @Test
  @DisplayName("РЕГРЕСИЯ · разрешена верига → двете пера 82,20 и 65,80 непроменени и НУЛА откази")
  void aResolvedContributionChainIsUnchangedAndSilent() {
    BgPaySlip slip = marchSlip("1500.00");
    slip.getBgPaySlipLineList().clear();

    List<BgPaySlipLineOutcome> outcomes =
        contributions()
            .generate(
                slip,
                BgContributionResult.resolved(
                    Arrays.asList(
                        new BgContributionLine(
                            "PENSION",
                            Payer.EMPLOYER,
                            new BigDecimal("1000.00"),
                            new BigDecimal("8.2200"),
                            new BigDecimal("82.20")),
                        new BgContributionLine(
                            "PENSION",
                            Payer.INSURED,
                            new BigDecimal("1000.00"),
                            new BigDecimal("6.5800"),
                            new BigDecimal("65.80")))));
    reportOnto(
        slip, new BgPaySlipVerdicts().addLines(BgPaySlipVerdicts.STEP_CONTRIBUTIONS, outcomes));

    assertEquals(2, slip.getBgPaySlipLineList().size(), "две пера, както преди");
    assertEquals(new BigDecimal("82.20"), lineWithCode(slip, "pl_vn_pension_rab").getBgAmount());
    assertEquals(new BigDecimal("65.80"), lineWithCode(slip, "pl_vn_pension_lice").getBgAmount());
    assertEquals(0, slip.getBgGeneratorRefusalCount().intValue(), slip.getBgGeneratorNotes());
    assertFalse(
        slip.getBgGeneratorNotes().isEmpty(),
        "⚠️ и при нула откази докладът НЕ е празен: той обяснява и сметнатото");
  }
}

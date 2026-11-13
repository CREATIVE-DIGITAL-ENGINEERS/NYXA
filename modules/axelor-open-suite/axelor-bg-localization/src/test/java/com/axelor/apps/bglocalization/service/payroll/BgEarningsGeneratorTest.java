package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ГЕНЕРАТОРЪТ — двете пера върху истински фиш: идемпотентност, адитивност и границата на стъпката.
 *
 * <p><b>⚠️ ПОДМЕНЕНИ СА САМО ЧЕТЦИТЕ, НЕ ПРАВИЛАТА.</b> Това е разликата между тест, който доказва
 * нещо, и тест, който проверява собствените си фикстури. {@link BgEarningsResolver} се вика
 * ИСТИНСКИ — сумите тук са негови, не измислени. Подменени са само {@code statutoryMinPct} (иначе
 * иска Postgres), {@code lineType} (речникът) и {@code lineFrom} (същото копиране, което {@code
 * BgPaySlipService} прави). Оттам тестът тече без база, без Guice и без Hibernate.
 *
 * <p>Всеки свидетел е чупен преднамерено, преди да остане зелен.
 */
class BgEarningsGeneratorTest {

  private static final BigDecimal MIN_PCT = new BigDecimal("0.6000");

  /**
   * КАТАЛОГЪТ СЪС СТАВКИ, както {@code bg_payroll_parameter.csv} го зарежда за 2026 г.
   *
   * <p>⚠️ Числата са ПРЕПИСАНИ от seed-а, не измислени: това е фикстура на ЧЕТЕЦА, не на правилото.
   * Самите правила (по-високият от двата минимума по чл. 8, непроратираната степен по чл. 11) се
   * проверяват с истинските си свидетели в {@code BgEarningsHourEngineTest}.
   */
  private static final Map<String, BigDecimal> RATES = new HashMap<>();

  static {
    RATES.put(BgEarningsService.PARAM_SENIORITY_MIN_PCT, MIN_PCT);
    RATES.put(BgEarningsService.PARAM_MIN_WAGE, new BigDecimal("620.2000"));
    RATES.put(BgEarningsService.PARAM_NIGHT_PCT_OF_MIN_WAGE, new BigDecimal("0.1500"));
    RATES.put(BgEarningsService.PARAM_NIGHT_FLOOR_EUR, new BigDecimal("0.5100"));
    RATES.put(BgEarningsService.PARAM_ON_CALL_EUR_PER_HOUR, new BigDecimal("0.0500"));
    RATES.put(BgEarningsService.PARAM_DEGREE_DOCTOR_EUR, new BigDecimal("7.6700"));
    RATES.put(BgEarningsService.PARAM_DEGREE_DSC_EUR, new BigDecimal("25.5600"));
  }

  private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
  private static final LocalDate TO = LocalDate.of(2026, 3, 31);

  /** Ред на речника — с флаговете и начина на изчисление от bg_pay_slip_line_type.csv. */
  private static BgPaySlipLineType type(String code, int number, String name, String calcBase) {
    BgPaySlipLineType type = new BgPaySlipLineType();
    type.setCode(code);
    type.setNumber(number);
    type.setName(name);
    type.setBgSign("CREDIT");
    type.setBgIsTaxable(true);
    type.setBgIsInsuranceIncome(true);
    // ⚠️ ПОСТОЯНЕН ХАРАКТЕР САМО ЗА 1xx, КАКТО В РЕЧНИКА. Закован на {@code true} за всичко,
    // нощният труд щеше да влиза в базата по НСОРЗ чл. 7 при ВТОРОТО prepare() — тоест сумите щяха
    // да зависят от това колко пъти е натиснат бутонът. Фикстура, по-широка от данните, крие точно
    // такива обратни връзки.
    type.setBgIsPermanentCharacter(number < 130);
    type.setBgCalculationBase(calcBase);
    return type;
  }

  /**
   * Речник, който ПРИЛИЧА на истинския: двата реда с наличен източник плюс по един от всяка друга
   * база.
   *
   * <p>⚠️ Другите редове НЕ са украса. Без тях тестът щеше да минава и ако енджинът мълчи за тях, а
   * точно това мълчание е дефектът, който дисчпачът съществува да махне.
   */
  private static List<BgPaySlipLineType> catalogue() {
    return Arrays.asList(
        type(
            BgEarningsResolver.LINE_BASE_SALARY,
            100,
            "Основно възнаграждение",
            BgEarningsResolver.CALC_CONTRACT_BASE_SALARY),
        type(
            BgEarningsResolver.LINE_SENIORITY,
            110,
            "Допълнително възнаграждение за трудов стаж и професионален опит",
            BgEarningsResolver.CALC_CONTRACT_BASE_SALARY),
        // ⚠️ РЕАЛНИТЕ КОДОВЕ ОТ РЕЧНИКА, а не измислени. Енджинът различава подформула по код,
        // тоест код, който {@code bg_pay_slip_line_type.csv} не носи, получава ОТКАЗ вместо сметка
        // и фикстурата спира да описва каквото и да е.
        type(
            BgEarningsResolver.LINE_ACADEMIC_DEGREE,
            120,
            "Възнаграждение за научна степен",
            BgEarningsResolver.CALC_MONTHLY_FIXED),
        type(
            BgEarningsResolver.LINE_NIGHT_WORK,
            130,
            "Нощен труд",
            BgEarningsResolver.CALC_HOURLY_FIXED),
        type(
            BgEarningsResolver.LINE_OVERTIME_WORKDAY,
            150,
            "Извънреден труд — работен ден",
            BgEarningsResolver.CALC_HOURLY_RATE_UPLIFT),
        type("pl_rachno", 200, "Ръчно перо", BgEarningsResolver.CALC_MANUAL_AMOUNT),
        type("pl_doo_lice", 500, "ДОО за сметка на лицето", BgEarningsResolver.CALC_DERIVED));
  }

  /** Услуга с подменени четци — правилата ѝ остават нейни. */
  private static BgEarningsService service() {
    return new BgEarningsService() {
      @Override
      protected BigDecimal statutoryMinPct(LocalDate date) {
        return MIN_PCT;
      }

      /**
       * ⚠️ ЧАСОВИТЕ СТАВКИ НЕ СЕ ЗАРЕЖДАТ, И ТОВА Е ОТГОВОР, А НЕ ПРОПУСК. Този свидетел не гледа
       * часови пера; без подмяна обаче четецът иска Postgres, защото {@code factsOf} вади и седемте
       * параметъра НАВЕДНЪЖ. Липсващият параметър е ОТКАЗ на съответното перо — точно каквото
       * трябва да получи фикстура, която не е обявила нито една часова ставка.
       */
      @Override
      protected BigDecimal payrollParameter(String code, LocalDate date) {
        return RATES.get(code);
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
        return catalogue();
      }

      @Override
      protected BgPaySlipLineType lineType(String code) {
        for (BgPaySlipLineType t : catalogue()) {
          if (t.getCode().equals(code)) {
            return t;
          }
        }
        return null;
      }

      @Override
      protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
        // Същото КОПИРАНЕ, което BgPaySlipService прави — речникът се чете веднъж, при създаване.
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

      // ⚠️ ЧЕТВЪРТИЯТ ЧЕТЕЦ, ПОДМЕНЕН ЗАРАДИ НОВАТА ОС: правото на клас (НСОРЗ чл. 12 ал. 1) се
      // вади
      // от вида осигурен през BgPaySlipService, тоест иска Guice и Postgres. Фишовете тук са на
      // НАЕТ по трудов договор — за него отговорът е „да“. Свидетелите за неТПО подават своя.
      @Override
      protected Boolean seniorityEntitlement(BgPaySlip slip) {
        return Boolean.TRUE;
      }
    };
  }

  private static BgPaySlip slip(String baseSalary, int worked, int norm) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setBgBaseSalary(new BigDecimal(baseSalary));
    slip.setBgWorkedDays(worked);
    slip.setBgWorkingDaysInPeriod(norm);
    slip.setBgPaySlipLineList(new ArrayList<>());

    Period period = new Period();
    period.setFromDate(FROM);
    period.setToDate(TO);
    slip.setPeriod(period);

    EmploymentContract contract = new EmploymentContract();
    contract.setPayCompany(new Company());
    slip.setEmploymentContract(contract);
    return slip;
  }

  /**
   * Вход по § 13.1 С ЧАСОВЕ — 24 нощни и 10 извънредни в работен ден, при 8 договорени часа на ден.
   *
   * <p>⚠️ Режимът на отчитане Е попълнен („1“, подневно): без него перото за работа на официален
   * празник отказва, а фикстурата не гледа този случай.
   */
  private static BgPayrollInput.Builder hourInputBuilder() {
    return BgPayrollInput.builder()
        .periodFrom(FROM)
        .periodTo(TO)
        .parameterReferenceDate(FROM)
        .recognisedServiceStartDate(FROM)
        .contractHoursPerDay(new BigDecimal("8.00"))
        .workTimeMode("1")
        .nightHours(new BigDecimal("24"))
        .overtimeWorkdayHours(new BigDecimal("10"));
  }

  private static BgPayrollInput hourInput() {
    return hourInputBuilder().build();
  }

  /** Вход по § 13.1 с КОТВАТА на стажа — единственият източник за годините. */
  private static BgPayrollInput input(LocalDate anchor) {
    return BgPayrollInput.builder()
        .periodFrom(FROM)
        .periodTo(TO)
        .parameterReferenceDate(FROM)
        .recognisedServiceStartDate(anchor)
        .build();
  }

  private static BgEarningsOutcome outcomeFor(List<BgEarningsOutcome> outcomes, String code) {
    for (BgEarningsOutcome o : outcomes) {
      if (code.equals(o.lineCode)) {
        return o;
      }
    }
    throw new AssertionError("енджинът МЪЛЧИ за „" + code + "“ — дисчпачът не е изчерпателен");
  }

  private static BgPaySlipLine lineOf(BgPaySlip slip, String code) {
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (line.getBgPaySlipLineType() != null
          && code.equals(line.getBgPaySlipLineType().getCode())) {
        return line;
      }
    }
    return null;
  }

  // ── числата на един истински фиш ────────────────────────────────────────

  /**
   * Пълен месец, база 2000, котва отпреди 10 години: заплата 2000,00 + клас 120,00.
   *
   * <p>FAIL-FIRST: {@code generate} беше пренаписан да не вика {@code upsert} → нула пера, тестът
   * падна на {@code assertNotNull} за основното.
   */
  @Test
  @DisplayName("пълен месец · 2000,00 заплата и 120,00 клас, с обяснението си")
  void aFullMonthProducesBothLines() {
    BgPaySlip slip = slip("2000.00", 21, 21);

    List<BgEarningsOutcome> outcomes = service().generate(slip, input(LocalDate.of(2016, 3, 1)));

    assertEquals(2, slip.getBgPaySlipLineList().size(), "точно двете пера, нищо повече");
    BgPaySlipLine base = lineOf(slip, BgEarningsResolver.LINE_BASE_SALARY);
    BgPaySlipLine seniority = lineOf(slip, BgEarningsResolver.LINE_SENIORITY);

    assertNotNull(base);
    assertEquals(0, base.getBgAmount().compareTo(new BigDecimal("2000.00")));
    assertEquals("Основно възнаграждение", base.getName(), "речникът се копира при създаване");
    assertEquals("CREDIT", base.getBgSign());

    assertNotNull(seniority);
    assertEquals(0, seniority.getBgAmount().compareTo(new BigDecimal("120.00")));
    assertEquals(
        0, seniority.getBgQuantity().compareTo(BigDecimal.TEN), "количеството са годините");
    assertEquals(0, seniority.getBgRate().compareTo(MIN_PCT), "ставката е процентът за година");

    assertTrue(outcomeFor(outcomes, BgEarningsResolver.LINE_BASE_SALARY).isComputed());
    assertTrue(outcomeFor(outcomes, BgEarningsResolver.LINE_SENIORITY).isComputed());
  }

  /**
   * ⚠️ КЛАСЪТ СЕ СМЯТА ВЪРХУ ОСНОВНАТА, НЕ ВЪРХУ БРУТОТО — на истински фиш, не само в аритметиката.
   *
   * <p>Фишът носи РЪЧНО въведена добавка от 1200,00, тоест след генерирането брутото му е 3200,00 —
   * над основната 2000,00. Класът остава 120,00, сметнат върху основната.
   *
   * <p>⚠️ Проверява се и че фикстурата различава двете бази: без изричното твърдение „брутото Е над
   * основната“ тестът щеше да е зелен и когато двете съвпадат, тоест нямаше да доказва нищо.
   *
   * <p>FAIL-FIRST: {@code outcomes} беше пренаписан да подава {@code
   * BgPaySlipResolver.grossTotal(...)} на {@code seniorityLine} вместо основната → класът стана
   * 72,00 и тестът падна.
   *
   * <p>⚠️ 72,00, а не очакваните 192,00 — и разликата е самата поука. Брутото се СЪСТАВЯ от перата,
   * тоест в мига, в който енджинът смята класа, заплатата още не е вписана и „брутото“ е само
   * ръчните 1200,00. Перо върху бруто зависи от РЕДА, в който перата се вписват; перо върху
   * основната — не. Това е второ, независимо основание базата да е основната, отделно от правното.
   */
  @Test
  @DisplayName("ТСПО · база, НЕ бруто — ръчна добавка от 1200 не мени класа")
  void seniorityIsComputedOnTheBaseNotOnTheGross() {
    BgPaySlip slip = slip("2000.00", 21, 21);
    slip.getBgPaySlipLineList().add(manualLine(130, "Нощен труд", "1200.00"));

    service().generate(slip, input(LocalDate.of(2016, 3, 1)));

    BgPaySlipLine seniority = lineOf(slip, BgEarningsResolver.LINE_SENIORITY);
    assertNotNull(seniority, "класът изобщо не е вписан — сметнат е върху друга база");
    assertEquals(
        0,
        seniority.getBgAmount().compareTo(new BigDecimal("120.00")),
        "класът трябва да е върху основната 2000,00; намерено: " + seniority.getBgAmount());

    BigDecimal gross = BgPaySlipResolver.grossTotal(slip.getBgPaySlipLineList());
    assertTrue(
        gross.compareTo(slip.getBgBaseSalary()) > 0,
        "фикстурата иска бруто НАД основната, инак свидетелят не различава двете бази; бруто: "
            + gross);
  }

  /**
   * ⚠️ ГОДИНИТЕ СЕ БРОЯТ ОТ КОТВАТА, не от {@code recognisedServiceYears}. Входът тук носи ИЗРИЧНО
   * нула предходен стаж и отдавнашна котва — ако енджинът ползваше сбора, класът щеше да е 0,00.
   *
   * <p>FAIL-FIRST: {@code outcomes} беше пренаписан да чете {@code input.recognisedServiceYears} →
   * 0,00 ≠ 120,00, тестът падна.
   */
  @Test
  @DisplayName("ТСПО · брои от ДАТАТА — 0 предходен стаж и стара котва пак дават клас")
  void yearsComeFromTheAnchorNotFromThePriorSum() {
    BgPaySlip slip = slip("2000.00", 21, 21);
    BgPayrollInput in =
        BgPayrollInput.builder()
            .periodFrom(FROM)
            .periodTo(TO)
            .parameterReferenceDate(FROM)
            .recognisedServiceStartDate(LocalDate.of(2016, 3, 1))
            .recognisedServiceYears(0) // ⚠️ ИЗРИЧНО нула — при разминаване печели датата
            .build();

    service().generate(slip, in);

    assertEquals(
        0,
        lineOf(slip, BgEarningsResolver.LINE_SENIORITY)
            .getBgAmount()
            .compareTo(new BigDecimal("120.00")));
  }

  /** РЕШЕНИЕ № 1 върху истински фиш: половин месец → половин заплата И половин клас. */
  @Test
  @DisplayName("непълен месец · РЕШЕНИЕ № 1 — и заплатата, и класът се проратират")
  void aHalfMonthProratesBothLines() {
    BgPaySlip slip = slip("2000.00", 10, 20);

    service().generate(slip, input(LocalDate.of(2016, 3, 1)));

    assertEquals(
        0,
        lineOf(slip, BgEarningsResolver.LINE_BASE_SALARY)
            .getBgAmount()
            .compareTo(new BigDecimal("1000.00")));
    assertEquals(
        0,
        lineOf(slip, BgEarningsResolver.LINE_SENIORITY)
            .getBgAmount()
            .compareTo(new BigDecimal("60.00")));
  }

  /**
   * ⚠️ НЕОТЧЕТЕНИЯТ ЧАС НЕ СЛАГА РЕД — на истински фиш, не само в дисчпача.
   *
   * <p>Речникът на фикстурата носи СЕДЕМ типа: двата от договорната база плюс по един от всяка
   * друга. Входът тук НЕ носи нито един час и нито една ставка, тоест след генерирането на фиша
   * стоят ДВА реда, а за останалите пет има ЯВЕН отговор — три пъти „източникът липсва“ и два пъти
   * „не е моя работа“. Нула реда със сума 0,00.
   *
   * <p><b>⚠️ ПО ПРАВИЛНАТА ПРИЧИНА.</b> Трите часови бази смятат; празни остават, защото ТОЗИ вход
   * не носи часове — и това е разликата, която този тест трябва да пази. Затова кодовете във
   * фикстурата са РЕАЛНИТЕ от речника, инак перото би получило отказ „няма подформула за този код“
   * и щеше да мине зелено по грешна причина.
   *
   * <p>⚠️ Броят на отговорите се сверява с броя на типовете. Без това твърдение енджинът можеше да
   * МЪЛЧИ за петте и тестът щеше да е зелен — а мълчанието изглежда точно като „нямаше какво да се
   * начисли“.
   *
   * <p>FAIL-FIRST: клаузите за MONTHLY_FIXED и HOURLY_* бяха заменени с {@code computed(..., ZERO,
   * ZERO, ZERO)} → на фиша станаха 5 реда вместо 2, три от тях със сума 0,00, и тестът падна и на
   * броя на редовете, и на статуса.
   */
  @Test
  @DisplayName("⚠️ неотчетените часове НЕ слагат ред с 0 — казват „източникът липсва“")
  void theWaitingTypesWriteNoLineAtAll() {
    BgPaySlip slip = slip("2000.00", 21, 21);

    List<BgEarningsOutcome> outcomes = service().generate(slip, input(LocalDate.of(2016, 3, 1)));

    assertEquals(
        catalogue().size(),
        outcomes.size(),
        "енджинът МЪЛЧИ за част от речника — мълчанието е неразличимо от „нямаше какво да се начисли“");
    assertEquals(2, slip.getBgPaySlipLineList().size(), "само двете с наличен източник се вписват");

    for (String code :
        Arrays.asList(
            BgEarningsResolver.LINE_ACADEMIC_DEGREE,
            BgEarningsResolver.LINE_NIGHT_WORK,
            BgEarningsResolver.LINE_OVERTIME_WORKDAY)) {
      assertNull(lineOf(slip, code), "перо със сума за „" + code + "“, чийто източник липсва");
      BgEarningsOutcome out = outcomeFor(outcomes, code);
      assertEquals(BgEarningsOutcome.Status.SOURCE_MISSING, out.status, code + ": " + out);
      assertNull(out.amount, code + " носи сума — нула тук е твърдение, което никой не е правил");
      assertTrue(out.detail.contains("източникът липсва"), out.detail);
    }

    for (String code : Arrays.asList("pl_rachno", "pl_doo_lice")) {
      assertNull(lineOf(slip, code));
      assertEquals(
          BgEarningsOutcome.Status.NOT_THIS_ENGINE, outcomeFor(outcomes, code).status, code);
    }
  }

  /**
   * ⚠️ ОТЧЕТЕНИЯТ ЧАС СЛАГА РЕД — и то със СВОИТЕ количество и ставка до сумата.
   *
   * <p>Огледалото на горния свидетел: същият фиш, същият речник, но входът носи 24 нощни часа и 10
   * часа извънреден труд, а каталогът — ставките. От двата реда стават ЧЕТИРИ, и двата нови носят
   * числата, които обясняват сумата си.
   *
   * <p>⚠️ Ставката и количеството се сверяват ЗАЕДНО със сумата. Перо с вярна сума и празни
   * количество и ставка е число без произход — а точно това {@code upsert} пише в {@code
   * bgQuantity} и {@code bgRate}, за да може човек да провери сметката от самия фиш.
   *
   * <p>FAIL-FIRST: {@code upsert} беше пренаписан да не пише {@code bgRate} → тестът падна на
   * ставката на нощния труд, при вярна сума.
   */
  @Test
  @DisplayName("⚠️ отчетеният час слага ред — със свои количество и ставка до сумата")
  void measuredHoursWriteTheirOwnLines() {
    BgPaySlip slip = slip("1847.53", 21, 21);

    List<BgEarningsOutcome> outcomes = service().generate(slip, hourInput());

    assertEquals(catalogue().size(), outcomes.size(), "енджинът мълчи за част от речника");
    assertEquals(
        4, slip.getBgPaySlipLineList().size(), "нощният труд и извънредният не стигнаха до фиша");

    BgPaySlipLine night = lineOf(slip, BgEarningsResolver.LINE_NIGHT_WORK);
    assertNotNull(night, "перото за нощен труд липсва въпреки декларираните часове");
    assertEquals(0, night.getBgAmount().compareTo(new BigDecimal("22.33")), "нощен труд · сума");
    assertEquals(0, night.getBgQuantity().compareTo(new BigDecimal("24")), "нощен труд · часове");
    assertEquals(
        0, night.getBgRate().compareTo(new BigDecimal("0.9303")), "нощен труд · ставка на час");

    BgPaySlipLine overtime = lineOf(slip, BgEarningsResolver.LINE_OVERTIME_WORKDAY);
    assertNotNull(overtime, "перото за извънреден труд липсва въпреки валидираните часове");
    assertEquals(
        0, overtime.getBgAmount().compareTo(new BigDecimal("164.96")), "извънреден труд · сума");
    assertEquals(
        0,
        overtime.getBgRate().compareTo(new BigDecimal("10.9972")),
        "извънреден труд · часова ставка по чл. 7");
  }

  /**
   * ⚠️ НЕНОРМИРАНИЯТ ДЕН МАХА ЗАВАРЕНИЯ РЕД, А НЕ ГО ПРЕСКАЧА.
   *
   * <p>Същият довод като при класа на ДУК: фишовете, подготвени преди отмятането на флага, вече
   * носят перо за извънреден труд в работен ден. Прескочени, те щяха да го запазят завинаги —
   * правилото щеше да важи само за новите фишове, а точно старите са сгрешените.
   *
   * <p>FAIL-FIRST: изходът при ненормиран ден беше сменен от {@code notEntitled} на {@code
   * sourceMissing} → заварениият ред оцеля и тестът падна на четвъртото перо.
   */
  @Test
  @DisplayName("⚠️ ненормиран ден · второто prepare() МАХА заварения ред за извънреден труд")
  void theUnregulatedDayRemovesAnExistingOvertimeLine() {
    BgPaySlip slip = slip("1847.53", 21, 21);
    BgEarningsService service = service();

    service.generate(slip, hourInput());
    assertNotNull(lineOf(slip, BgEarningsResolver.LINE_OVERTIME_WORKDAY), "подготовката е грешна");

    service.generate(slip, hourInputBuilder().unregulatedWorkDay(true).build());

    assertNull(
        lineOf(slip, BgEarningsResolver.LINE_OVERTIME_WORKDAY),
        "перото за извънреден труд в работен ден оцеля отмятането на ненормирания ден");
    assertNotNull(
        lineOf(slip, BgEarningsResolver.LINE_NIGHT_WORK), "нощният труд не бива да се маха");
  }

  // ── идемпотентност и адитивност ─────────────────────────────────────────

  /**
   * FAIL-FIRST: {@code upsert} беше пренаписан да добавя винаги нов ред → 4 пера ≠ 2, тестът падна.
   */
  @Test
  @DisplayName("идемпотентно · трето prepare() не дублира — обновява по стабилния код")
  void repeatedGenerationDoesNotDuplicate() {
    BgPaySlip slip = slip("2000.00", 21, 21);
    BgEarningsService service = service();

    service.generate(slip, input(LocalDate.of(2016, 3, 1)));
    service.generate(slip, input(LocalDate.of(2016, 3, 1)));
    service.generate(slip, input(LocalDate.of(2016, 3, 1)));

    assertEquals(
        2, slip.getBgPaySlipLineList().size(), "по едно перо на код, независимо от повторенията");
  }

  /** Промяна на входа се ОТРАЗЯВА — идемпотентност не значи „пише само първия път“. */
  @Test
  @DisplayName("идемпотентно · обновява СЪЩЕСТВУВАЩИЯ ред, вместо да го оставя остарял")
  void regenerationUpdatesTheExistingLine() {
    BgPaySlip slip = slip("2000.00", 21, 21);
    BgEarningsService service = service();
    service.generate(slip, input(LocalDate.of(2016, 3, 1)));

    slip.setBgWorkedDays(10);
    slip.setBgWorkingDaysInPeriod(20);
    service.generate(slip, input(LocalDate.of(2016, 3, 1)));

    assertEquals(2, slip.getBgPaySlipLineList().size());
    assertEquals(
        0,
        lineOf(slip, BgEarningsResolver.LINE_BASE_SALARY)
            .getBgAmount()
            .compareTo(new BigDecimal("1000.00")),
        "остарял ред е по-лош от липсващ: изглежда сметнат");
  }

  /**
   * ⚠️ АДИТИВНО. Операторът въвежда на ръка всичко без източник до WS-5 и тези редове трябва да
   * преживеят всяко {@code prepare()}.
   *
   * <p>FAIL-FIRST: {@code generate} беше пренаписан да чисти списъка преди upsert → ръчният ред
   * изчезна и тестът падна.
   */
  @Test
  @DisplayName("адитивно · ръчно въведените пера НЕ се трият и НЕ се пипат")
  void manualLinesSurviveGeneration() {
    BgPaySlip slip = slip("2000.00", 21, 21);
    BgPaySlipLine manual = manualLine(150, "Извънреден труд", "150.00");
    slip.getBgPaySlipLineList().add(manual);

    BgEarningsService service = service();
    service.generate(slip, input(LocalDate.of(2016, 3, 1)));
    service.generate(slip, input(LocalDate.of(2016, 3, 1)));

    assertEquals(3, slip.getBgPaySlipLineList().size(), "двете сметнати плюс ръчното");
    assertTrue(
        slip.getBgPaySlipLineList().contains(manual), "ръчният ред е СЪЩИЯТ обект, не копие");
    assertEquals(
        0, manual.getBgAmount().compareTo(new BigDecimal("150.00")), "сумата му не е пипана");
  }

  // ── границите ───────────────────────────────────────────────────────────

  /**
   * ⚠️ Замразените входове на ретро-копието идват от ОРИГИНАЛА. Пресметнато наново перо би
   * пренаписало историята точно там, където ретро-механизмът съществува, за да не я пренаписва.
   *
   * <p>FAIL-FIRST: проверката за двата флага беше махната → 2 пера ≠ 0, тестът падна.
   */
  @Test
  @DisplayName("граница · коригиращ и заличаващ фиш НЕ се пипат")
  void retroSlipsAreLeftAlone() {
    BgPaySlip correction = slip("2000.00", 21, 21);
    correction.setBgIsCorrection(true);
    assertTrue(service().generate(correction, input(LocalDate.of(2016, 3, 1))).isEmpty());
    assertTrue(correction.getBgPaySlipLineList().isEmpty());

    BgPaySlip deletion = slip("2000.00", 21, 21);
    deletion.setBgIsDeletion(true);
    assertTrue(service().generate(deletion, input(LocalDate.of(2016, 3, 1))).isEmpty());
    assertTrue(deletion.getBgPaySlipLineList().isEmpty());
  }

  /** Потвърденият фиш е ЗАМРАЗЕН — генераторът спазва същата граница като {@code recompute}. */
  @Test
  @DisplayName("граница · потвърден фиш не се пипа")
  void aConfirmedSlipIsFrozen() {
    BgPaySlip slip = slip("2000.00", 21, 21);
    slip.setStatusSelect(BgPaySlipResolver.CONFIRMED);

    assertTrue(service().generate(slip, input(LocalDate.of(2016, 3, 1))).isEmpty());
    assertTrue(slip.getBgPaySlipLineList().isEmpty());
  }

  // ── „0 не е решение“ на ниво фиш ────────────────────────────────────────

  /**
   * FAIL-FIRST: {@code upsert} беше извикан и за отказите → появи се перо със сума нула и тестът
   * падна на празния списък.
   */
  @Test
  @DisplayName("0 не е решение · непопълнена основна не създава перо, а обявява какво липсва")
  void anUnsetBaseSalaryCreatesNoLine() {
    BgPaySlip slip = slip("0.00", 21, 21);

    List<BgEarningsOutcome> outcomes = service().generate(slip, input(LocalDate.of(2016, 3, 1)));

    assertTrue(
        slip.getBgPaySlipLineList().isEmpty(), "перо със сума нула тук би значело „заплата 0 лв.“");
    assertEquals(
        catalogue().size(),
        outcomes.size(),
        "всеки тип получава отговор — мълчание би било неразличимо");
    assertTrue(outcomeFor(outcomes, BgEarningsResolver.LINE_BASE_SALARY).isRefusal());
    assertTrue(outcomeFor(outcomes, BgEarningsResolver.LINE_SENIORITY).isRefusal());
    assertTrue(
        outcomeFor(outcomes, BgEarningsResolver.LINE_BASE_SALARY)
            .detail
            .contains("основната заплата"));
  }

  /**
   * Липсващата котва спира САМО класа. Заплатата се смята — отказът на едното перо не бива да
   * поваля другото.
   */
  @Test
  @DisplayName("0 не е решение · липсваща котва спира САМО класа, заплатата минава")
  void aMissingAnchorStopsOnlyTheSeniorityLine() {
    BgPaySlip slip = slip("2000.00", 21, 21);

    List<BgEarningsOutcome> outcomes = service().generate(slip, input(null));

    assertEquals(1, slip.getBgPaySlipLineList().size());
    assertNotNull(lineOf(slip, BgEarningsResolver.LINE_BASE_SALARY));
    assertNull(lineOf(slip, BgEarningsResolver.LINE_SENIORITY));
    BgEarningsOutcome seniority = outcomeFor(outcomes, BgEarningsResolver.LINE_SENIORITY);
    assertTrue(outcomeFor(outcomes, BgEarningsResolver.LINE_BASE_SALARY).isComputed());
    assertTrue(seniority.isRefusal());
    assertTrue(seniority.detail.contains("bgPriorExperience"), seniority.detail);
  }

  /** Липсващият параметър спира класа с причина, която сочи seed-а — не с нула. */
  @Test
  @DisplayName("0 не е решение · липсващ SENIORITY_MIN_PCT спира класа и сочи App Install")
  void aMissingParameterStopsTheSeniorityLine() {
    BgEarningsService service =
        new BgEarningsService() {
          @Override
          protected BigDecimal statutoryMinPct(LocalDate date) {
            return null; // параметърът не е зареден
          }

          /**
           * ⚠️ ЧАСОВИТЕ СТАВКИ НЕ СЕ ЗАРЕЖДАТ, И ТОВА Е ОТГОВОР, А НЕ ПРОПУСК. Този свидетел не
           * гледа часови пера; без подмяна обаче четецът иска Postgres, защото {@code factsOf} вади
           * и седемте параметъра НАВЕДНЪЖ. Липсващият параметър е ОТКАЗ на съответното перо — точно
           * каквото трябва да получи фикстура, която не е обявила нито една часова ставка.
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
            return catalogue();
          }

          @Override
          protected BgPaySlipLineType lineType(String code) {
            return type(
                code, 100, "Основно възнаграждение", BgEarningsResolver.CALC_CONTRACT_BASE_SALARY);
          }

          @Override
          protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
            BgPaySlipLine line = new BgPaySlipLine();
            line.setBgPaySlipLineType(type);
            line.setBgAmount(amount);
            return line;
          }

          // Нает по трудов договор — правото на клас е налице; спира го ЛИПСВАЩИЯТ параметър, а
          // това е точно разликата, която този свидетел пази.
          @Override
          protected Boolean seniorityEntitlement(BgPaySlip slip) {
            return Boolean.TRUE;
          }
        };

    BgPaySlip slip = slip("2000.00", 21, 21);
    List<BgEarningsOutcome> outcomes = service.generate(slip, input(LocalDate.of(2016, 3, 1)));

    assertNull(lineOf(slip, BgEarningsResolver.LINE_SENIORITY));
    BgEarningsOutcome seniority = outcomeFor(outcomes, BgEarningsResolver.LINE_SENIORITY);
    assertTrue(seniority.isRefusal());
    assertTrue(seniority.detail.contains("SENIORITY_MIN_PCT"), seniority.detail);
  }

  /**
   * Ръчно въведено перо — каквото ТРЗ вписва до WS-5.
   *
   * <p>⚠️ НОМЕРЪТ НЕ Е УКРАСА. {@code BgPaySlipResolver} извежда ГРУПАТА от него, а групата решава
   * дали перото влиза в брутото. Ред без номер пада в група нула, тоест не се брои никъде — и
   * тогава свидетелят „брутото е над основната“ би минавал, без ръчното перо изобщо да участва.
   */
  private static BgPaySlipLine manualLine(int number, String name, String amount) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(number);
    line.setName(name);
    line.setBgSign("CREDIT");
    line.setBgIsTaxable(true);
    line.setBgIsInsuranceIncome(true);
    line.setBgAmount(new BigDecimal(amount));
    return line;
  }
}

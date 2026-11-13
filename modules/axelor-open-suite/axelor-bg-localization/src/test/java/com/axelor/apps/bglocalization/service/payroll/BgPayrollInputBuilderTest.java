package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.service.BgInsurableIncomeResult;
import com.axelor.apps.bglocalization.service.BgInsurableIncomeService;
import com.axelor.apps.bglocalization.service.BgWorkingDayService;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СГЛОБИТЕЛЯТ И ПРОПОРЦИЯТА, без база, без Guice и без Hibernate.
 *
 * <p>Шевовете към базата се подменят, точно както при {@code BgPaySlipServiceTest}. Твърденията са
 * за ДВЕТЕ неща, които сглобителят решава: какво препраща и кое правило по НЕВД чл. 1 избира.
 *
 * <p><b>Числата не са произволни.</b> 21 работни дни × 8 часа = 168 законоустановени часа за месеца
 * — точно знаменателят, който {@code BgWorkTimeFacts} изписва в javadoc-а си, и точно случаят, в
 * който дневното съотношение 2/8 = 0,2500 се разминава с верните 40/168 = 0,2381 с около 5 на сто
 * от базата. Прагът 550,66 е МОД за самоосигуряващите се до 31.07.2026 и се среща в същите
 * стойности и в {@code BgInsurableIncomeServiceTest} — за да се чете, че двата теста говорят за
 * едно и също.
 */
class BgPayrollInputBuilderTest {

  private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
  private static final LocalDate TO = LocalDate.of(2026, 3, 31);

  /** 03.03.2026 е вторник; с него март 2026 има 21 работни дни, а без него — 22. */
  private static final LocalDate LIBERATION_DAY = LocalDate.of(2026, 3, 3);

  // ═══ СРЕДАТА ══════════════════════════════════════════════════════════════

  /**
   * Производствен календар с ЗАДАДЕНО покритие и зададени празници.
   *
   * <p>Празниците се подават изрично, за да е числото 21 предмет на теста, а не следствие от това
   * какво има в базата.
   */
  private static BgWorkingDayService calendar(Set<Integer> covered, Set<LocalDate> holidays) {
    return new BgWorkingDayService() {
      @Override
      public Set<Integer> coveredYears() {
        return covered;
      }

      @Override
      protected Set<LocalDate> holidaysIn(LocalDate from, LocalDate to) {
        Set<LocalDate> inRange = new HashSet<>();
        for (LocalDate day : holidays) {
          if (!day.isBefore(from) && !day.isAfter(to)) {
            inRange.add(day);
          }
        }
        return inRange;
      }
    };
  }

  /** Сглобител с подменени шевове: календар, отработено време и обезщетения. */
  private static final class Builder extends BgPayrollInputBuilder {

    private BgWorkingDayService workingDayService =
        calendar(new HashSet<>(Arrays.asList(2026, 2027)), Collections.singleton(LIBERATION_DAY));
    private WorkedTime worked = WorkedTime.unknown();
    private Map<LocalDate, BigDecimal> workedByDate = Collections.emptyMap();
    private List<Long> compensations = Collections.emptyList();
    private List<BgPayrollInput.SickLeaveEpisode> episodes = Collections.emptyList();
    private List<Object[]> leaves = Collections.emptyList();
    private List<Object[]> extraHours = Collections.emptyList();
    private List<Object[]> declaredHours = Collections.emptyList();

    @Override
    protected BgWorkingDayService.Count workingDays(LocalDate from, LocalDate to) {
      if (from == null) {
        return null;
      }
      LocalDate end = to != null ? to : from;
      int calendarDays = (int) (end.toEpochDay() - from.toEpochDay()) + 1;
      return workingDayService.count(from, calendarDays);
    }

    @Override
    protected WorkedTime workedTime(Employee employee, LocalDate from, LocalDate to) {
      return worked;
    }

    // ⚠️ ВТОРИЯТ ШЕВ КЪМ ОТЧЕТА. Заварените тестове подменят само workedTime и с това казват КОЛКО
    // е сборът; часовете на празник искат да знаят НА КОИ ДАТИ, а сборът вече го е загубил.
    // Подразбирането тук е ПРАЗНА разбивка, тоест „няма отчет“ — така нито един заварен тест не
    // получава мълчаливо нула часа на празник.
    @Override
    protected Map<LocalDate, BigDecimal> workedHoursByDate(
        Employee employee, LocalDate from, LocalDate to) {
      return workedByDate;
    }

    @Override
    protected Set<LocalDate> officialHolidays(LocalDate from, LocalDate to) {
      return workingDayService.officialHolidaysIn(from, to);
    }

    @Override
    protected List<Long> compensationDocumentIds(
        EmploymentContract contract, LocalDate from, LocalDate to) {
      return compensations;
    }

    @Override
    protected List<BgPayrollInput.SickLeaveEpisode> sickLeaveEpisodesOf(
        EmploymentContract contract, LocalDate from, LocalDate to) {
      return episodes;
    }

    // ⚠️ Подменя се РЕДЪТ, а не решението: така разпределянето по кофи, четирите отказа и
    // прочитът на трите състояния се изпълняват в теста ТАКИВА, каквито са в производството.
    // Подменено едно ниво по-нагоре (leaveDaysOf), от тях нямаше да се тества нищо.
    @Override
    protected List<Object[]> leaveRows(Employee employee, LocalDate from, LocalDate to) {
      return leaves;
    }

    @Override
    protected List<Object[]> extraHoursRows(Employee employee, LocalDate from, LocalDate to) {
      return extraHours;
    }

    @Override
    protected DeclaredHours declaredHoursOf(
        EmploymentContract contract, LocalDate from, LocalDate to) {
      return declaredHoursFrom(declaredHours);
    }
  }

  private static Period march() {
    Period period = new Period();
    period.setFromDate(FROM);
    period.setToDate(TO);
    return period;
  }

  /** Пълен договор на пълно работно време, с лице зад него. */
  private static EmploymentContract contract() {
    Employee employee = new Employee();
    employee.setId(7L);
    employee.setBirthDate(LocalDate.of(1985, 4, 12));
    employee.setBgLaborCategory("3");
    employee.setBgBornAfter1959(true);
    employee.setBgPensionChoice("ART4B");
    employee.setBgIsPensioner(false);

    EmploymentContract contract = new EmploymentContract();
    contract.setId(42L);
    contract.setEmployee(employee);
    contract.setStartDate(LocalDate.of(2019, 6, 1));
    contract.setBgBaseSalary(new BigDecimal("1500.00"));
    contract.setBgHoursPerDay(new BigDecimal("8.00"));
    contract.setBgWorkTimeMode("1");
    contract.setBgPriorExperienceYears(3);
    contract.setBgPriorExperienceMonths(2);
    contract.setBgPriorExperienceDays(10);
    contract.setBgIsTeacherPosition(false);
    // ⚠️ Видът осигурен е ЗАДЪЛЖИТЕЛЕН вход: без него сглобителят ОТКАЗВА.
    // Тук стои 01, защото това е обикновеният нает по чл. 4 ал. 1 т. 1 КСО — а какво прави
    // сглобителят БЕЗ него, е предмет на отделен тест, не мълчалив фон на всички останали.
    contract.setBgInsuredTypeCode("01");
    return contract;
  }

  /**
   * Същият договор, но на ПОЛОВИН работно време — четири часа дневно.
   *
   * <p>Отделен помощник, защото режимът е това, което избира реда по НЕВД чл. 1, и тестът трябва да
   * го задава ИЗРИЧНО. Единствената разлика с {@link #contract()} са договорените часове на ден:
   * ако тя не стигаше, за да смени правилото, дискриминаторът щеше да е нещо друго.
   */
  private static EmploymentContract partTimeContract() {
    EmploymentContract contract = contract();
    contract.setBgHoursPerDay(new BigDecimal("4.00"));
    return contract;
  }

  // ═══ КАКВО ПРЕПРАЩА ═══════════════════════════════════════════════════════

  @Test
  @DisplayName("Сглобителят препраща всяко поле, за което има източник")
  void theAssemblerForwardsEveryFieldItHasASourceFor() {
    Builder builder = new Builder();
    builder.compensations = Arrays.asList(11L, 12L);
    BgPayrollInput input = builder.build(contract(), march());

    assertEquals(Long.valueOf(42L), input.employmentContractId, "договорът");
    assertEquals(Long.valueOf(7L), input.employeeId, "лицето");
    assertEquals("3", input.laborCategory, "категория труд — от ЛИЦЕТО, не от договора");
    assertEquals(LocalDate.of(1985, 4, 12), input.birthDate, "котвата за всеки въпрос за възраст");
    assertTrue(input.bornAfter1959);
    assertEquals("ART4B", input.pensionChoice);
    assertFalse(input.pensioner);
    assertEquals("NON_TEACHER", input.teacherScope, "липса на учителска длъжност, не подразбиране");
    assertEquals("01", input.insuredTypeCode, "видът осигурен идва от ДОГОВОРА");
    assertEquals(
        "ANY", input.solScope, "без упражнен избор по чл. 4а ал. 2 — само безусловните редове");
    assertEquals(new BigDecimal("1500.00"), input.baseSalary);
    assertEquals(new BigDecimal("8.00"), input.contractHoursPerDay);
    assertEquals("1", input.workTimeMode);
    assertEquals(FROM, input.periodFrom);
    assertEquals(TO, input.periodTo);
    assertEquals(FROM, input.parameterReferenceDate, "версията на параметрите е ЮЛСКА за юли");
    assertEquals(21, input.workingDaysInPeriod, "22 понеделник–петък минус 03.03");
    assertEquals(Arrays.asList(11L, 12L), input.compensationDocumentIds);
  }

  @Test
  @DisplayName("⚠️ Котвата на признатия стаж е началото на договора, изместено НАЗАД")
  void theRecognisedServiceAnchorMovesBackByThePriorService() {
    BgPayrollInput input = new Builder().build(contract(), march());

    // 01.06.2019 − 3 години − 2 месеца − 10 дни = 22.03.2016
    assertEquals(LocalDate.of(2016, 3, 22), input.recognisedServiceStartDate, "котвата");
    assertEquals(3, input.recognisedServiceYears);
    assertEquals(2, input.recognisedServiceMonths);
    assertEquals(10, input.recognisedServiceDays);
  }

  @Test
  @DisplayName("⚠️ Нулата на договора значи НЕПОПЪЛНЕНО, защото getter-ът никога не връща null")
  void anEmptyDecimalOnTheContractIsReadAsUnknownNotAsZero() {
    EmploymentContract bare = new EmploymentContract();
    bare.setId(1L);

    // ⚠️ signum(), не сравнение с null: точно това е капанът при заплатата на фиша.
    assertEquals(0, bare.getBgBaseSalary().signum(), "новият договор идва с 0,00, не с празно");

    BgPayrollInput input = new Builder().build(bare, march());
    assertNull(input.baseSalary, "непопълнената заплата е ЛИПСА, не нула");
    assertNull(input.contractHoursPerDay, "непопълнените часове са ЛИПСА, не нула");
  }

  @Test
  @DisplayName("Всяко поле с обявена липса наистина излиза празно")
  void everyDeclaredAbsenceComesOutEmpty() {
    BgPayrollInput input = new Builder().build(contract(), march());

    // ⚠️ Списъкът тук е ТРЕТО копие на DECLARED_ABSENT: поле, което получи източник, излиза и
    // оттук, иначе остарялото копие твърди „поле без източник“, след като източник има. Затова
    // тестът пада при всяко такова навързване и това е верният му отговор. insuredTypeCode и
    // solScope (EmploymentContract.bgInsuredTypeCode и .bgSeafarerUnemploymentChoice) се мерят в
    // theAssemblerForwardsEveryFieldItHasASourceFor и в двата теста отдолу, а
    // otherEmployerInsurableIncome (BgPaySlip.bgOtherPayerInsurableIncome) — в
    // theDeclaredOtherPayerIncomeIsForwarded.
    assertNull(input.insurableIncomeOverride);
    assertNull(input.insurableIncomeOverrideReason);
    assertTrue(input.manualLines.isEmpty(), "празен списък, не null");
  }

  // ═══ ДЕКЛАРИРАНИЯТ ЧУЖД ОСИГУРИТЕЛЕН ДОХОД (КСО чл. 6 ал. 11) ════════════

  @Test
  @DisplayName("⚠️ Декларираният чужд осигурителен доход се ПРЕПИСВА, не се тълкува")
  void theDeclaredOtherPayerIncomeIsForwarded() {
    // ⚠️ ТОВА Е ШЕВЪТ ОТ КОЛОНАТА ДО ВХОДА. Числото е МЕСЕЧЕН факт на ФИША, тоест влиза през
    // третия аргумент, а не се чете от договора: сглобителят тръгва от двойката (договор, период)
    // и фиш не вижда. Без този свидетел веригата „поле на фиша → координирана база“ има непокрито
    // звено точно в средата, а базовата половина (BgOtherPayerCoordinationGoldenTest) подава
    // входа готов и не може да го покрие.
    BgPayrollInput declared = new Builder().build(contract(), march(), new BigDecimal("1500.00"));
    assertEquals(
        new BigDecimal("1500.00"),
        declared.otherEmployerInsurableIncome,
        "сумата минава непокътната — дали активира координация е правило на WS-3");

    // ⚠️ И ДВЕТЕ ГРАНИЦИ. Нула се препраща като нула (не се превръща в null и обратно): решението
    // „нула и празно значат едно“ е на четеца на правилото, не на сглобителя, и точно затова тук
    // не се взима.
    assertEquals(
        BigDecimal.ZERO,
        new Builder().build(contract(), march(), BigDecimal.ZERO).otherEmployerInsurableIncome,
        "нулата се препраща, не се преобразува");
    assertNull(
        new Builder().build(contract(), march()).otherEmployerInsurableIncome,
        "⚠️ Двуаргументният вход дава ЛИПСА, а не нула — повикващ без фиш (екранът „Преглед на"
            + " осигуровки“) няма месец, за който да твърди декларация");
  }

  // ═══ ИЗВЪНРЕДНИЯТ ТРУД ПО ВИДОВЕ ═════════════════════════════════════════

  /** Ред с допълнителни часове, точно както го връща {@code extraHoursRows}. */
  private static Object[] extra(String qty, String kind) {
    return new Object[] {new BigDecimal(qty), kind};
  }

  @Test
  @DisplayName("Часовете падат в четирите кофи по класификацията на вида си")
  void extraHoursFallIntoTheFourKinds() {
    Builder builder = new Builder();
    builder.extraHours =
        Arrays.asList(
            extra("3.00", "OVERTIME_WORKDAY"),
            extra("2.50", "OVERTIME_WORKDAY"),
            extra("8.00", "OVERTIME_REST_DAY"),
            extra("4.00", "OVERTIME_HOLIDAY"),
            extra("6.00", "OVERTIME_SIRV"));

    BgPayrollInput input = builder.build(contract(), march());

    assertEquals(
        0, new BigDecimal("5.50").compareTo(input.overtimeWorkdayHours), "два реда се сумират");
    assertEquals(0, new BigDecimal("8.00").compareTo(input.overtimeRestDayHours));
    assertEquals(0, new BigDecimal("4.00").compareTo(input.overtimeHolidayHours));
    assertEquals(0, new BigDecimal("6.00").compareTo(input.overtimeSirvHours));
  }

  @Test
  @DisplayName("⚠️ ПРАЗНА КОФА ПРИ НАЛИЧНИ РЕДОВЕ Е НУЛА — измерена, не липсваща")
  void anEmptyOvertimeKindAmongRealRowsIsAMeasuredZero() {
    Builder builder = new Builder();
    builder.extraHours = Collections.singletonList(extra("3.00", "OVERTIME_WORKDAY"));

    BgPayrollInput input = builder.build(contract(), march());
    assertNotNull(input.overtimeHolidayHours, "редове има; „нула празнични часа“ е ОТГОВОР");
    assertEquals(0, input.overtimeHolidayHours.signum());
  }

  @Test
  @DisplayName("⚠️ НУЛА РЕДА НЕ Е НУЛА ЧАСА — инак неводеният извънреден труд изчезва тихо")
  void noExtraHoursRowsIsNotZeroOvertime() {
    BgPayrollInput input = new Builder().build(contract(), march());
    assertNull(input.overtimeWorkdayHours);
    assertNull(input.overtimeRestDayHours);
    assertNull(input.overtimeHolidayHours);
    assertNull(input.overtimeSirvHours);
  }

  @Test
  @DisplayName("⚠️ ОТКАЗ · некласифициран вид обезсилва И ЧЕТИРИТЕ кофи")
  void anUnclassifiedExtraHoursTypeRefusesAllFour() {
    Builder builder = new Builder();
    builder.extraHours = Arrays.asList(extra("3.00", "OVERTIME_WORKDAY"), extra("5.00", null));

    BgPayrollInput input = builder.build(contract(), march());

    assertNull(input.overtimeWorkdayHours, "известната кофа също отпада — инак твърди пълнота");
    assertNull(input.overtimeRestDayHours);
    assertNull(input.overtimeHolidayHours);
    assertNull(input.overtimeSirvHours);

    BgPayrollInputBuilder.OvertimeHours hours =
        builder.overtimeHoursOf(contract().getEmployee(), FROM, TO);
    assertTrue(hours.isRefused(), "източникът съществува и е НЕПОПЪЛНЕН — това е ОТКАЗ");
    assertTrue(hours.refusal.contains("bgExtraHoursKind"), hours.refusal);
  }

  @Test
  @DisplayName("⚠️ Непозната стойност в класификацията също ОТКАЗВА, а не се брои в „работен ден“")
  void anUnknownKindValueRefusesRatherThanFallingBack() {
    Builder builder = new Builder();
    builder.extraHours = Collections.singletonList(extra("3.00", "NIGHT"));

    // ⚠️ NIGHT е нарочно избрана: тя изглежда правдоподобно и НЕ е в bg.extra.hours.kind.select,
    // защото нощният труд не е извънреден. Приета мълчаливо, тя щеше да влезе в чужда кофа.
    BgPayrollInput input = builder.build(contract(), march());
    assertNull(input.overtimeWorkdayHours);
  }

  // ═══ ДЕКЛАРИРАНИТЕ ЧАСОВЕ: НОЩЕН ТРУД И РАЗПОЛОЖЕНИЕ ═════════════════════

  @Test
  @DisplayName("Декларираните нощни часове и разположение стигат до контракта")
  void theDeclaredNightAndOnCallHoursReachTheContract() {
    Builder builder = new Builder();
    builder.declaredHours =
        Collections.singletonList(new Object[] {new BigDecimal("42.00"), new BigDecimal("16.00")});

    BgPayrollInput input = builder.build(contract(), march());
    assertEquals(0, new BigDecimal("42.00").compareTo(input.nightHours));
    assertEquals(0, new BigDecimal("16.00").compareTo(input.onCallHours));
  }

  @Test
  @DisplayName("⚠️ БЕЗ ДЕКЛАРАЦИЯ двете са „не знам“ — недекларираният нощен труд не е нулев")
  void withoutADeclarationBothAreUnknown() {
    BgPayrollInput input = new Builder().build(contract(), march());
    assertNull(
        input.nightHours,
        "нула тук би отнела добавката по НСОРЗ чл. 8 на всекиго, който не е декларирал");
    assertNull(input.onCallHours);
  }

  @Test
  @DisplayName("⚠️ САМИЯТ РЕД Е ДЕКЛАРАЦИЯТА: празната клетка при наличен ред значи НУЛА")
  void anExistingRowMakesTheEmptyCellAZero() {
    Builder builder = new Builder();
    // Декларирано е само разположението; нощните часове са празна клетка.
    builder.declaredHours = Collections.singletonList(new Object[] {null, new BigDecimal("16.00")});

    BgPayrollInput input = builder.build(contract(), march());

    // ⚠️ Тук нулата е СТОЙНОСТ, защото редът вече е обявил периода за деклариран. Прочетена като
    // „не знам“, тя щеше да направи декларацията безполезна точно за случая, който тя описва:
    // „разположение имаше, нощен труд НЯМАШЕ“.
    assertNotNull(input.nightHours, "редът съществува — значи периодът Е деклариран");
    assertEquals(0, input.nightHours.signum());
    assertEquals(0, new BigDecimal("16.00").compareTo(input.onCallHours));
  }

  // ═══ ОТПУСКИТЕ ПО КОФИ ═══════════════════════════════════════════════════

  /** Ред от заявка за отпуск, точно както го връща {@code leaveRows}. */
  private static Object[] leave(
      String from, String to, String days, int unit, Integer category, Boolean paid, Long typeId) {
    return leave(from, to, days, unit, category, paid, typeId, Boolean.FALSE);
  }

  /**
   * Както горното, но с признака „заплаща се по чл. 177“ — ОСМАТА колона на {@code leaveRows}.
   *
   * <p>⚠️ Осма, а не заместваща: „платен отпуск“ и „платен ПО ЧЛ. 177“ са различни множества.
   * Категория 1 с {@code isPaid} събира шест вида по три режима на заплащане (чл. 177 ал. 1, чл.
   * 159 ал. 2, чл. 161 по КТД, чл. 169–171 и трите режима вътре в чл. 157).
   */
  private static Object[] leave(
      String from,
      String to,
      String days,
      int unit,
      Integer category,
      Boolean paid,
      Long typeId,
      Boolean paidUnderArt177) {
    return leave(
        from, to, days, unit, category, paid, typeId, paidUnderArt177, defaultBucket(category));
  }

  /**
   * КОФАТА ПО ПОДРАЗБИРАНЕ ЗА КАТЕГОРИЯТА — за фикстурите, които не се интересуват от нея.
   *
   * <p><b>⚠️ НЕ Е {@code null}, И ТОВА Е ЗАЩИТА, А НЕ УДОБСТВО.</b> Празна кофа значи „извън
   * осигуряването“ (КСО чл. 10 ал. 2) и води до КАРВ на заплатата, а празна кофа при признат стаж е
   * противоречие и води до ОТКАЗ. Фикстура с {@code null} по невнимание щеше да мери един от двата
   * случая, без да го е искала.
   *
   * <p>Категории 5 („дисциплинарно“) и 6 („друго“) наистина НЯМАТ кофа в речника — {@code
   * non_attendance} и {@code awol} са точно те. Останалите носят своята.
   */
  private static String defaultBucket(Integer category) {
    if (category == null) {
      return "16.1";
    }
    switch (category) {
      case 5:
      case 6:
        return null;
      case 2:
      case 3:
      case 4:
        return "16.2";
      case 7:
        return "16.5";
      default:
        return "16.1";
    }
  }

  /**
   * Както горното, но и с КОФАТА В ДЕКЛАРАЦИЯ ОБР. 1 — ДЕВЕТАТА колона на {@code leaveRows}.
   *
   * <p>⚠️ Девета, а не изведена от категорията: {@code BgAbsenceType.d1Code} е онова, от което Д1
   * наистина строи кофите си, а категория 7 („неплатен“) се разцепва на ДВЕ — до 30 работни дни в
   * годината носи т. 16.5 (КТ чл. 160 ал. 3), над 30 няма кофа изобщо.
   *
   * <p>⚠️ {@code null} тук значи „вид БЕЗ кофа“ и е стойност, не пропуск — точно такива са петте
   * вида в {@code BgD1DayBuckets.UNBUCKETED}.
   */
  private static Object[] leave(
      String from,
      String to,
      String days,
      int unit,
      Integer category,
      Boolean paid,
      Long typeId,
      Boolean paidUnderArt177,
      String d1Code) {
    return new Object[] {
      LocalDate.parse(from).atStartOfDay(),
      LocalDate.parse(to).atTime(23, 59, 59),
      new BigDecimal(days),
      unit,
      category,
      paid,
      typeId,
      paidUnderArt177,
      d1Code,
      // ⚠️ ДЕСЕТАТА колона: countsAsInsuranceService. Следва кофата, защото речникът го изисква —
      // непразна кофа значи „зачита се за осигурителен стаж“, празна значи „не се зачита“.
      // Разминат, редът е противоречие и BgPayrollInputBuilder го ОТКАЗВА назовано.
      d1Code != null && !d1Code.trim().isEmpty(),
      // ⚠️ ЕДИНАЙСЕТАТА колона: healthContributionRule. Фикстурата е на ПЛАТЕН или НЕПЛАТЕН отпуск
      // по чл. 160 — платецът по ЗЗО чл. 40 ал. 1 т. 1 б. „б“ е лицето. Виж
      // BgPayrollInput#unpaidLeaveDaysUnderZzo защо колоната пътува заедно с дните.
      BgUnpaidLeaveHealthResolver.RULE_UNPAID_EMPLOYEE
    };
  }

  /** Платен годишен отпуск: категория 1 „Отпуск“, платено, И по чл. 177. */
  private static Object[] paidLeave(String from, String to, String days) {
    return leave(from, to, days, 1, 1, Boolean.TRUE, 2L, Boolean.TRUE, "16.1");
  }

  /** Дните като {@link BigDecimal} — кофите са дробни. */
  private static BigDecimal days(String v) {
    return new BigDecimal(v);
  }

  private static void assertDays(String expected, BigDecimal actual, String why) {
    assertNotNull(actual, why);
    assertEquals(0, days(expected).compareTo(actual), why + " Намерено: " + actual);
  }

  @Test
  @DisplayName("Отпуските падат в трите кофи по вида си от речника")
  void leavesFallIntoTheThreeBucketsByTheirDictionaryKind() {
    Builder builder = new Builder();
    builder.leaves =
        Arrays.asList(
            paidLeave("2026-03-02", "2026-03-06", "5"),
            leave("2026-03-09", "2026-03-10", "2", 1, 7, Boolean.FALSE, 12L),
            leave("2026-03-16", "2026-03-16", "1", 1, 5, Boolean.FALSE, 25L));

    BgPayrollInput input = builder.build(contract(), march());

    assertDays("5", input.paidLeaveDays, "категория 1 + платено → платен.");
    assertDays("2", input.unpaidLeaveDays, "категория 7 → неплатен.");
    assertDays("1", input.otherAbsenceDays, "категория 5 (дисциплинарно) → друго.");
  }

  @Test
  @DisplayName("⚠️ Категория 1 БЕЗ „платено“ е НЕПЛАТЕН отпуск, не платен")
  void anUnpaidLeaveInTheLeaveCategoryGoesToTheUnpaidBucket() {
    Builder builder = new Builder();
    builder.leaves =
        Collections.singletonList(leave("2026-03-02", "2026-03-06", "5", 1, 1, Boolean.FALSE, 4L));

    BgPayrollInput input = builder.build(contract(), march());
    assertDays("0", input.paidLeaveDays, "нула платени дни.");
    assertDays("5", input.unpaidLeaveDays, "признакът „платено“ решава, не категорията сама.");
  }

  @Test
  @DisplayName("⚠️ ПРАЗНА КОФА ПРИ НАЛИЧНИ ЗАЯВКИ Е НУЛА — тя е измерена, а не липсваща")
  void anEmptyBucketAmongRealRequestsIsAMeasuredZero() {
    Builder builder = new Builder();
    builder.leaves = Collections.singletonList(paidLeave("2026-03-02", "2026-03-06", "5"));

    BgPayrollInput input = builder.build(contract(), march());
    assertDays("5", input.paidLeaveDays, "петте платени дни.");
    assertNotNull(input.unpaidLeaveDays, "заявки има; „нула неплатени дни“ е ОТГОВОР");
    assertDays("0", input.unpaidLeaveDays, "измерена нула.");
    assertDays("0", input.otherAbsenceDays, "измерена нула.");
  }

  @Test
  @DisplayName("⚠️ НУЛА ЗАЯВКИ НЕ Е НУЛА ДНИ ОТПУСК — инак неводените отпуски вдигат отработеното")
  void noLeaveRequestsIsNotZeroLeaveDays() {
    BgPayrollInput input = new Builder().build(contract(), march());
    assertNull(
        input.paidLeaveDays, "инсталация, която не води отпуски, не е инсталация без отпуски");
    assertNull(input.unpaidLeaveDays);
    assertNull(input.otherAbsenceDays);
  }

  @Test
  @DisplayName("⚠️ ОТКАЗ · несъпоставен вид отпуск обезсилва И ТРИТЕ кофи, не само своята")
  void anUnmappedLeaveReasonRefusesAllThreeBuckets() {
    Builder builder = new Builder();
    builder.leaves =
        Arrays.asList(
            paidLeave("2026-03-02", "2026-03-06", "5"),
            // ⚠️ null на мястото на вида: LeaveReason без bgAbsenceType.
            leave("2026-03-09", "2026-03-10", "2", 1, null, null, null));

    BgPayrollInput input = builder.build(contract(), march());

    // ⚠️ Петте платени дни СА известни — и въпреки това не се подават. Причината е, че не се знае
    // дали несъпоставените два дни не са били СЪЩО платени: подадена сама, петицата твърди „това е
    // целият платен отпуск за месеца“, а това вече не е известно.
    assertNull(
        input.paidLeaveDays, "известната кофа също отпада — инак тя твърди пълнота, каквато няма");
    assertNull(input.unpaidLeaveDays);
    assertNull(input.otherAbsenceDays);

    BgPayrollInputBuilder.LeaveDays days = builder.leaveDaysOf(contract().getEmployee(), FROM, TO);
    assertTrue(days.isRefused(), "това е ОТКАЗ, не обявена липса: източникът съществува");
    assertTrue(days.refusal.contains("LeaveReason.bgAbsenceType"), days.refusal);
  }

  // ═══ ФОРМА 1 · ЗАЯВКА ПРЕЗ ГРАНИЦАТА НА ПЕРИОДА ══════════════════════════

  @Test
  @DisplayName("⚠️ ЗЛАТЕН STRADDLE · 28.12–08.01 с 9 дни дава 4 на декември и 5 на януари")
  void theGoldenStraddleSplitsFourAndFiveAcrossTheYearBoundary() {
    // 28–31.12.2026 са понеделник–четвъртък: 4 работни дни. 01.01.2027 е ПРАЗНИК (петък), 02 и 03
    // са събота и неделя, 04–08.01 са понеделник–петък: 5 работни дни. Общо 9 — точно duration.
    Set<LocalDate> holidays =
        new HashSet<>(Arrays.asList(LocalDate.of(2027, 1, 1), LocalDate.of(2026, 12, 25)));

    Builder december = new Builder();
    december.workingDayService = calendar(new HashSet<>(Arrays.asList(2026, 2027)), holidays);
    december.leaves = Collections.singletonList(paidLeave("2026-12-28", "2027-01-08", "9"));

    Builder january = new Builder();
    january.workingDayService = calendar(new HashSet<>(Arrays.asList(2026, 2027)), holidays);
    january.leaves = Collections.singletonList(paidLeave("2026-12-28", "2027-01-08", "9"));

    BgPayrollInput dec =
        december.build(contract(), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31));
    BgPayrollInput jan =
        january.build(contract(), LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31));

    assertDays("4", dec.paidLeaveDays, "28–31.12 са четири работни дни.");
    assertDays("5", jan.paidLeaveDays, "04–08.01 са пет работни дни; 01.01 е празник.");
    assertEquals(
        0,
        days("9").compareTo(dec.paidLeaveDays.add(jan.paidLeaveDays)),
        "⚠️ СБОРЪТ РЕКОНСИЛИРА. Отказ тук би оставил двата месеца без НИЩО —"
            + " а по НСОРЗ чл. 18 ал. 2 всеки месец има СВОЙ коефициент (работните дни на"
            + " „съответния месец, през който се ползва отпускът“), тоест дните трябва да са в"
            + " месеца, в който са взети. Намерено: "
            + dec.paidLeaveDays
            + " + "
            + jan.paidLeaveDays);
  }

  @Test
  @DisplayName("Заявка, която точно опира границата, се брои — тя е ЦЯЛА в периода")
  void aLeaveTouchingTheBoundaryExactlyIsCounted() {
    Builder builder = new Builder();
    // ⚠️ ДВАДЕСЕТ И ДВЕ, А НЕ ДВАДЕСЕТ И ЕДНО, макар календарът на теста да брои 21 работни дни за
    // март 2026 (03.03 е празник). Това НЕ е противоречие, а самото правило: при заявка, ЦЯЛА в
    // периода, duration се връща НЕПОКЪТНАТО — сглобителят не пребройва отпуските, той само реже
    // онези, които излизат навън. Иначе всяко разминаване между заявката и производствения
    // календар щеше да мени тихо дните на всеки фиш.
    builder.leaves = Collections.singletonList(paidLeave("2026-03-01", "2026-03-31", "22"));

    BgPayrollInput input = builder.build(contract(), march());
    assertDays("22", input.paidLeaveDays, "цяла в периода → duration, без нито едно деление.");
  }

  @Test
  @DisplayName("⚠️ Дробна заявка през границата се дели по РАБОТНИ дни, не по календарни")
  void aFractionalStraddleIsSplitByWorkingDaysNotCalendarDays() {
    Set<LocalDate> holidays = Collections.singleton(LocalDate.of(2027, 1, 1));
    Builder december = new Builder();
    december.workingDayService = calendar(new HashSet<>(Arrays.asList(2026, 2027)), holidays);
    // 30–31.12 (ср–чт) = 2 работни дни; 04–05.01 (пн–вт) = 2. Общо 4, duration 3,5 (половин ден
    // някъде вътре). Дялът на декември е 3,5 × 2 / 4 = 1,75.
    december.leaves = Collections.singletonList(paidLeave("2026-12-30", "2027-01-05", "3.5"));

    BgPayrollInput dec =
        december.build(contract(), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31));
    assertDays(
        "1.75",
        dec.paidLeaveDays,
        "⚠️ По КАЛЕНДАРНИ дни делителят щеше да е 7 и декември щеше да получи дял за 1, 2 и 3"
            + " януари — празник и два почивни, тоест дни, които НЕ са отпуск.");
  }

  @Test
  @DisplayName("⚠️ ОТКАЗ · заявка с дни, но без НИТО ЕДИН работен ден — делителят е нула")
  void aLeaveWithoutASingleWorkingDayRefuses() {
    Builder builder = new Builder();
    // 07–08.03.2026 са събота и неделя. Заявка с положителна продължителност върху нула работни
    // дни значи, че заявката и календарът твърдят различни неща за едни и същи дати.
    builder.leaves = Collections.singletonList(paidLeave("2026-03-07", "2026-03-08", "2"));

    BgPayrollInput input = builder.build(contract(), march());
    assertNull(input.paidLeaveDays);

    BgPayrollInputBuilder.LeaveDays d = builder.leaveDaysOf(contract().getEmployee(), FROM, TO);
    assertTrue(d.isRefused(), "това е назован отказ, не тиха нула");
    assertTrue(d.refusal.contains("НЕ ПОКРИВА"), d.refusal);
  }

  // ═══ ФОРМА 2 · ЗАЯВКА В ЧАСОВЕ ═══════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ЧАСОВЕ · 4 часа при 8-часов ден са 0,5 дни")
  void fourHoursOnAnEightHourDayAreHalfADay() {
    Builder builder = new Builder();
    builder.leaves =
        Collections.singletonList(leave("2026-03-02", "2026-03-02", "4", 2, 1, Boolean.TRUE, 2L));

    BgPayrollInput input = builder.build(contract(), march());
    assertDays("0.5", input.paidLeaveDays, "4 ÷ 8 = 0,5.");
  }

  @Test
  @DisplayName("⚠️ ЕДИН ЧАС по КТ чл. 166 е 0,125 дни — и НЕ е нула")
  void oneHourOfBreastfeedingLeaveIsAnEighthOfADay() {
    Builder builder = new Builder();
    builder.leaves =
        Collections.singletonList(leave("2026-03-02", "2026-03-02", "1", 2, 1, Boolean.TRUE, 2L));

    BgPayrollInput input = builder.build(contract(), march());
    assertDays(
        "0.13",
        input.paidLeaveDays,
        "⚠️ 1 ÷ 8 = 0,125 → 0,13 при две знака. Закръглен до ЦЯЛ ден, този час щеше да стане 0 и"
            + " правото по КТ чл. 166 (платено от работодателя, ал. 4) щеше да изчезне тихо.");
  }

  @Test
  @DisplayName("⚠️ ДЕЛИТЕЛЯТ Е ДОГОВОРЕНИЯТ ДЕН · при половин щат 4 часа са ЦЯЛ ден")
  void theDivisorIsTheContractedDayNotTheStatutoryOne() {
    Builder builder = new Builder();
    builder.leaves =
        Collections.singletonList(leave("2026-03-02", "2026-03-02", "4", 2, 1, Boolean.TRUE, 2L));

    BgPayrollInput input = builder.build(partTimeContract(), march());
    assertDays(
        "1",
        input.paidLeaveDays,
        "⚠️ При половин щат по КТ чл. 138 (4 ч) четири часа отпуск са ЦЯЛ ден отсъствие. През"
            + " законоустановените осем (bgLegalHoursPerDay) те щяха да дадат 0,5 — половината"
            + " отсъствие изчезва, а при пълен щат двете числа СЪВПАДАТ, тоест грешката не се"
            + " вижда никъде освен при онези, заради които полето съществува.");
  }

  @Test
  @DisplayName("⚠️ ОТКАЗ · часове без договорен ден — 8 не е подразбиране")
  void hoursWithoutAContractedDayRefuse() {
    Builder builder = new Builder();
    builder.leaves =
        Collections.singletonList(leave("2026-03-02", "2026-03-02", "4", 2, 1, Boolean.TRUE, 2L));
    EmploymentContract noHours = contract();
    noHours.setBgHoursPerDay(BigDecimal.ZERO);

    BgPayrollInput input = builder.build(noHours, march());
    assertNull(input.paidLeaveDays, "четири ЧАСА не стават дни без делител");

    BgPayrollInputBuilder.LeaveDays d = builder.leaveDaysOf(noHours.getEmployee(), FROM, TO, null);
    assertTrue(d.isRefused());
    assertTrue(d.refusal.contains("bgHoursPerDay"), d.refusal);
  }

  @Test
  @DisplayName("⚠️ Часовете се РЕЖАТ по периоди ПРЕДИ да станат дни, а не след това")
  void hoursAreSlicedByPeriodBeforeTheyBecomeDays() {
    Set<LocalDate> holidays = Collections.singleton(LocalDate.of(2027, 1, 1));
    Builder december = new Builder();
    december.workingDayService = calendar(new HashSet<>(Arrays.asList(2026, 2027)), holidays);
    // 30–31.12 = 2 работни дни, 04–05.01 = 2. Общо 4. 16 часа кърмене: на декември 8 → 1 ден.
    december.leaves =
        Collections.singletonList(leave("2026-12-30", "2027-01-05", "16", 2, 1, Boolean.TRUE, 2L));

    BgPayrollInput dec =
        december.build(contract(), LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 31));
    assertDays("1", dec.paidLeaveDays, "16 ч × 2/4 = 8 ч → 8 ÷ 8 = 1 ден.");
  }

  // ═══ ФОРМА 3 · ПОЛОВИН И ДРОБЕН ДЕН ══════════════════════════════════════

  @Test
  @DisplayName("⚠️ ПОЛОВИН ДЕН · 0,5 се носи какъвто е, вместо да се закръгля")
  void aHalfDayIsCarriedRatherThanRounded() {
    Builder builder = new Builder();
    builder.leaves = Collections.singletonList(paidLeave("2026-03-02", "2026-03-02", "0.5"));

    BgPayrollInput input = builder.build(contract(), march());
    assertDays(
        "0.5",
        input.paidLeaveDays,
        "⚠️ Закръглен нагоре, половиният ден става цял; надолу — изчезва. И двете менят"
            + " осигурителния доход без нито един признак, затова закръглява КОНСУМАТОРЪТ, който"
            + " иска цял ден (Декларация обр. 1, т. 16), а не входът.");
  }

  @Test
  @DisplayName("⚠️ Половин ден и часове в един месец се СЪБИРАТ, вече в една единица")
  void halfDaysAndHoursAddUpInOneUnit() {
    Builder builder = new Builder();
    builder.leaves =
        Arrays.asList(
            paidLeave("2026-03-02", "2026-03-02", "0.5"),
            leave("2026-03-04", "2026-03-04", "2", 2, 1, Boolean.TRUE, 2L));

    BgPayrollInput input = builder.build(contract(), march());
    assertDays(
        "0.75",
        input.paidLeaveDays,
        "0,5 дни + 2 ÷ 8 дни = 0,75. ДВЕТЕ заявки се събират, а не се отказват.");
  }

  @Test
  @DisplayName("⚠️ Болничният НЕ се брои като ден отпуск — инак се отчита ДВА ПЪТИ")
  void aSickLeaveReasonIsNotCountedAsLeaveDays() {
    Builder builder = new Builder();
    builder.leaves =
        Arrays.asList(
            paidLeave("2026-03-02", "2026-03-06", "5"),
            // категория 2 „Болнични“ — пътува като епизод, не като ден
            leave("2026-03-09", "2026-03-13", "5", 1, 2, Boolean.TRUE, 15L));
    builder.episodes =
        Collections.singletonList(episode("2026-03-09", "2026-03-13", false, "sick_doo"));

    BgPayrollInput input = builder.build(contract(), march());

    assertDays("5", input.paidLeaveDays, "петте платени дни.");
    assertDays(
        "0",
        input.otherAbsenceDays,
        "болничните дни вече пътуват като епизод — преброени и тук, те се отчитат два пъти.");
    assertEquals(1, input.sickLeaveEpisodes.size());
  }

  // ═══ БОЛНИЧНИТЕ ЕПИЗОДИ ══════════════════════════════════════════════════

  private static BgPayrollInput.SickLeaveEpisode episode(
      String from, String to, boolean continuation, String code) {
    return new BgPayrollInput.SickLeaveEpisode(
        LocalDate.parse(from), LocalDate.parse(to), continuation, "BL-" + from, code);
  }

  @Test
  @DisplayName("Епизодите за периода стигат до контракта цели — с датите, вида и номера си")
  void theEpisodesReachTheContractWhole() {
    Builder builder = new Builder();
    builder.episodes =
        Arrays.asList(
            episode("2026-03-04", "2026-03-08", false, "sick_er"),
            episode("2026-03-20", "2026-03-24", true, "sick_doo"));

    BgPayrollInput input = builder.build(contract(), march());

    assertEquals(2, input.sickLeaveEpisodes.size());
    assertEquals(LocalDate.of(2026, 3, 4), input.sickLeaveEpisodes.get(0).fromDate);
    assertEquals(LocalDate.of(2026, 3, 8), input.sickLeaveEpisodes.get(0).toDate);
    assertEquals("sick_er", input.sickLeaveEpisodes.get(0).absenceTypeCode);
    assertEquals("BL-2026-03-04", input.sickLeaveEpisodes.get(0).documentNumber);
  }

  @Test
  @DisplayName("⚠️ ПРОДЪЛЖЕНИЕТО ПЪТУВА — то е разликата между два платени дни и нула")
  void theContinuationFlagSurvivesTheAssembly() {
    Builder builder = new Builder();
    builder.episodes =
        Arrays.asList(
            episode("2026-03-04", "2026-03-08", false, "sick_er"),
            episode("2026-03-09", "2026-03-13", true, "sick_er"));

    BgPayrollInput input = builder.build(contract(), march());

    // ⚠️ Ако този признак се загуби по пътя, вторият лист открива НОВ епизод и работодателят плаща
    // втори път първите два работни дни по чл. 40 ал. 5 КСО. Гол сбор „10 дни болничен“ не може да
    // каже кое от двете е вярно — точно затова контрактът иска епизоди, а не число.
    assertFalse(input.sickLeaveEpisodes.get(0).continuation, "първият лист открива епизода");
    assertTrue(input.sickLeaveEpisodes.get(1).continuation, "вторият е ПРОДЪЛЖЕНИЕ, не нов епизод");
  }

  @Test
  @DisplayName("⚠️ Два епизода по 5 дни НЕ СА един епизод от 10 — сборът губи точно това")
  void twoEpisodesAreNotOneLongerEpisode() {
    Builder builder = new Builder();
    builder.episodes =
        Arrays.asList(
            episode("2026-03-04", "2026-03-08", false, "sick_er"),
            episode("2026-03-20", "2026-03-24", false, "sick_er"));

    BgPayrollInput input = builder.build(contract(), march());

    // И двата са НОВИ епизоди, тоест работодателят плаща по два работни дни за ВСЕКИ — общо
    // четири. Един запис „10 дни“ би дал два. Разликата е в парите и не се вижда от сбора.
    assertEquals(2, input.sickLeaveEpisodes.size(), "два отделни епизода остават два");
    assertFalse(input.sickLeaveEpisodes.get(0).continuation);
    assertFalse(input.sickLeaveEpisodes.get(1).continuation);
  }

  @Test
  @DisplayName("Без въведен болничен списъкът е ПРАЗЕН, а не null")
  void withoutAnySickLeaveTheListIsEmpty() {
    BgPayrollInput input = new Builder().build(contract(), march());
    assertNotNull(input.sickLeaveEpisodes);
    assertTrue(
        input.sickLeaveEpisodes.isEmpty(),
        "болничният е ДОКУМЕНТ: невъведен значи несъществуващ, за разлика от отработения час");
  }

  // ═══ ЧАСОВЕТЕ НА ОФИЦИАЛЕН ПРАЗНИК ══════════════════════════════════════

  private static Map<LocalDate, BigDecimal> hours(Object... datesAndHours) {
    Map<LocalDate, BigDecimal> out = new LinkedHashMap<>();
    for (int i = 0; i < datesAndHours.length; i += 2) {
      out.put((LocalDate) datesAndHours[i], new BigDecimal((String) datesAndHours[i + 1]));
    }
    return out;
  }

  @Test
  @DisplayName("Часовете, отчетени на официален празник, се сумират — и само те")
  void onlyTheHoursLoggedOnAnOfficialHolidayAreSummed() {
    Builder builder = new Builder();
    builder.workedByDate =
        hours(
            LIBERATION_DAY,
            "9.00",
            LocalDate.of(2026, 3, 4),
            "8.00",
            LocalDate.of(2026, 3, 5),
            "8.00");

    BgPayrollInput input = builder.build(contract(), march());

    // 03.03.2026 е единственият празник в подадения календар — тоест 9 от общо 25 часа.
    assertEquals(
        0,
        new BigDecimal("9.00").compareTo(input.holidayWorkHours),
        "получено: " + input.holidayWorkHours);
  }

  @Test
  @DisplayName("Един празник, отчетен на ДВА реда, дава сбора на двата")
  void twoLinesOnTheSameHolidayAreSummed() {
    Builder builder = new Builder();
    builder.workedByDate =
        BgPayrollInputBuilder.hoursByDate(
            Arrays.asList(
                new Object[] {LIBERATION_DAY, new BigDecimal("4.00")},
                new Object[] {LIBERATION_DAY, new BigDecimal("3.50")}));

    BgPayrollInput input = builder.build(contract(), march());
    assertEquals(
        0,
        new BigDecimal("7.50").compareTo(input.holidayWorkHours),
        "два реда на един празник са седем часа и половина, не три и половина");
  }

  @Test
  @DisplayName("⚠️ Отчет има, празници има, на тях не е работено → НУЛА е стойност, не липса")
  void aCoveredHolidayWithNoWorkIsAZeroNotAnAbsence() {
    Builder builder = new Builder();
    builder.workedByDate = hours(LocalDate.of(2026, 3, 4), "8.00");

    BgPayrollInput input = builder.build(contract(), march());
    assertNotNull(input.holidayWorkHours, "и трите въпроса имат отговор — това не е „не знам“");
    assertEquals(0, input.holidayWorkHours.signum());
  }

  @Test
  @DisplayName("⚠️ НУЛА ОТЧЕТЕНИ РЕДА НЕ Е НУЛА ПРАЗНИЧНИ ЧАСА")
  void noTimesheetIsNotZeroHolidayHours() {
    BgPayrollInput input = new Builder().build(contract(), march());
    assertNull(
        input.holidayWorkHours,
        "без отчет сборът е нула — същото число, което дава и „почивал е на празника“");
  }

  @Test
  @DisplayName("⚠️ НЕПОКРИТА ГОДИНА МАХА ЧИСЛОТО — инак празният списък празници дава тихо нула")
  void anUncoveredCalendarYearDropsTheHolidayHours() {
    Builder builder = new Builder();
    // ⚠️ ТАКА ИЗГЛЕЖДА НЕПОКРИТАТА ГОДИНА В ПРОИЗВОДСТВОТО: няма нито един ред в таблицата с
    // празници, тоест списъкът е ПРАЗЕН — не „друг“, а празен. Затова и календарът тук се подава
    // без празници: тестът трябва да види точно числото, което истинската база би дала.
    builder.workingDayService =
        calendar(new HashSet<>(Arrays.asList(2027)), Collections.<LocalDate>emptySet());
    builder.workedByDate = hours(LIBERATION_DAY, "9.00");

    BgPayrollInput input = builder.build(contract(), march());
    assertNull(
        input.holidayWorkHours,
        "непокритата година прави празниците НЕИЗВЕСТНИ. Без този пазач тук щеше да излезе 0,00 —"
            + " „не е работено на празник“ при 9 отчетени часа тъкмо на 03.03 — и увеличението по"
            + " чл. 264 КТ нямаше да се начисли, без нищо да изглежда като грешка");
  }

  @Test
  @DisplayName("⚠️ Покритието се пита ОТДЕЛНО от списъка — покрита година с празници дава число")
  void aCoveredYearStillYieldsTheNumber() {
    Builder builder = new Builder();
    builder.workedByDate = hours(LIBERATION_DAY, "9.00");

    // Двойникът на теста отгоре: СЪЩИЯТ отчет, същият празник, само покритието е друго. Ако
    // числото не се смени, пазачът горе не е доказал нищо за покритието.
    BgPayrollInput input = builder.build(contract(), march());
    assertNotNull(input.holidayWorkHours);
    assertEquals(0, new BigDecimal("9.00").compareTo(input.holidayWorkHours));
  }

  @Test
  @DisplayName("Сглобителят дава ЧАСОВЕТЕ, без да ги дели на нормални и извънредни")
  void theAssemblerYieldsHoursWithoutSplittingThem() {
    Builder builder = new Builder();
    builder.workedByDate = hours(LIBERATION_DAY, "12.00");

    BgPayrollInput input = builder.build(contract(), march());

    // 12 часа на празник при 8-часов договор: разделянето 8 нормални + 4 извънредни по чл. 264 КТ
    // е ПРАВИЛО и иска графика. Сглобителят дава числителя цял и не гадае къде е границата.
    assertEquals(0, new BigDecimal("12.00").compareTo(input.holidayWorkHours));
    assertNull(input.overtimeHolidayHours, "разделянето не е работа на входния слой");
  }

  // ═══ ОСИГУРИТЕЛНИЯТ СТАЖ НА ЛИЦЕТО ═══════════════════════════════════════

  @Test
  @DisplayName("Деклариран осигурителен стаж се препраща такъв, какъвто е")
  void aDeclaredInsuranceServiceIsForwarded() {
    EmploymentContract contract = contract();
    contract.getEmployee().setBgInsuranceServiceDeclared(true);
    contract.getEmployee().setBgInsuranceServiceMonths(84);

    BgPayrollInput input = new Builder().build(contract, march());
    assertEquals(Integer.valueOf(84), input.insuranceServiceMonths);
  }

  @Test
  @DisplayName("⚠️ НУЛА ДЕКЛАРИРАНИ МЕСЕЦА Е СТОЙНОСТ — новопостъпилият няма право по чл. 40 ал. 1")
  void zeroDeclaredMonthsIsAValueNotAnAbsence() {
    EmploymentContract contract = contract();
    contract.getEmployee().setBgInsuranceServiceDeclared(true);
    contract.getEmployee().setBgInsuranceServiceMonths(0);

    BgPayrollInput input = new Builder().build(contract, march());

    // ⚠️ ТУК Е РАЗЛИКАТА С baseSalaryOf. Там нулата значи „непопълнено“, защото заплата нула не е
    // договор. Тук нулата е ЗАКОНЕН отговор: човек на първата си работа има нула месеца стаж и
    // тъкмо затова НЯМА право на парично обезщетение. Прочетена като липса, тя би станала
    // „не знам“ — а „не знам“ и „няма право“ искат различна поправка.
    assertEquals(
        Integer.valueOf(0),
        input.insuranceServiceMonths,
        "декларираната нула е СТОЙНОСТ, не липса — инак signum()==0 щеше да е верният прочит и тук");
  }

  @Test
  @DisplayName("⚠️ Непопълненият стаж е ЛИПСА, макар getter-ът да връща 0")
  void anUndeclaredInsuranceServiceIsAbsentNotZero() {
    Employee bare = new Employee();

    // Котвата: генерираният getter НАИСТИНА не връща null, тоест проверка за null тук би била
    // мъртъв код и трети flag не е украса.
    assertEquals(
        Integer.valueOf(0),
        bare.getBgInsuranceServiceMonths(),
        "котвата: getter-ът за Integer връща 0 за непопълнена колона — ако това се смени, целият"
            + " довод за отделния флаг отпада");
    assertFalse(
        bare.getBgInsuranceServiceDeclared(),
        "котвата: getter-ът за Boolean връща false за непопълнена колона — заварените редове се"
            + " четат като „не е декларирано“");

    BgPayrollInput input = new Builder().build(contract(), march());
    assertNull(
        input.insuranceServiceMonths,
        "недекларираният стаж е ЛИПСА — нулата на getter-а не бива да мине за отговор");
  }

  @Test
  @DisplayName("⚠️ Стажът се чете от ЛИЦЕТО, не от признатия трудов стаж по договора")
  void theInsuranceServiceIsReadFromThePersonNotFromTheContract() {
    EmploymentContract contract = contract();
    // Договорът носи 3 г. 2 м. 10 д. признат ТРУДОВ стаж (виж contract()), а лицето не е
    // декларирало осигурителен. Двата стажа се разминават по закон и не се извеждат един от друг.
    assertEquals(
        Integer.valueOf(2),
        contract.getBgPriorExperienceMonths(),
        "котвата: договорът НОСИ трудов стаж");

    BgPayrollInput input = new Builder().build(contract, march());
    assertNull(
        input.insuranceServiceMonths,
        "трудовият стаж по договора НЕ бива да се преоблече като осигурителен стаж на лицето");
  }

  // ═══ ТРИТЕ СЪСТОЯНИЯ НА ОТРАБОТЕНОТО ═════════════════════════════════════

  @Test
  @DisplayName("⚠️ НУЛА ОТЧЕТЕНИ РЕДА НЕ Е НУЛА ЧАСА — иначе липсата на отчет би смъкнала базата")
  void noTimesheetIsNotZeroHours() {
    BgPayrollInputBuilder.WorkedTime none = BgPayrollInputBuilder.WorkedTime.of(new ArrayList<>());
    assertNull(none.hours, "няма отчет → не знам, НЕ нула");
    assertNull(none.days, "същото за дните");

    Builder builder = new Builder();
    builder.worked = none;
    BgPayrollInput input = builder.build(contract(), march());
    assertNull(input.workedHours);
    assertNull(input.workedDays);

    // и следствието при ПЪЛНО работно време: цял праг, а не нулев коефициент. Договорът в
    // contract()
    // е 8 часа дневно, тоест ал. 3 — а тя се прилага само при НЕотработени всички дни.
    BgPayrollWorkTime.Decision decision = BgPayrollWorkTime.decide(input);
    assertFalse(decision.isRefused(), String.valueOf(decision.refusal));
    assertFalse(decision.facts.hasHourData(), "нула часа не тръгва като числител");
    assertFalse(decision.facts.hasDayData());
  }

  @Test
  @DisplayName("⚠️ Отчетени дни с НУЛА часа: часовете са „не знам“, дните се знаят")
  void loggedDatesWithZeroHoursLeaveTheHoursUnknown() {
    // Три дати, всяка с непопълнено hoursDuration — генерираният getter дава ZERO, тоест сборът е
    // нула ПРИ НАЛИЧНИ РЕДОВЕ. Това е данна грешка, не отработена нула.
    List<Object[]> rows =
        Arrays.asList(
            new Object[] {LocalDate.of(2026, 3, 2), BigDecimal.ZERO},
            new Object[] {LocalDate.of(2026, 3, 3), BigDecimal.ZERO},
            new Object[] {LocalDate.of(2026, 3, 4), BigDecimal.ZERO});
    BgPayrollInputBuilder.WorkedTime worked = BgPayrollInputBuilder.WorkedTime.of(rows);
    assertNull(worked.hours, "часовият числител не е използваем");
    assertEquals(3, worked.days, "дните обаче се знаят");

    Builder builder = new Builder();
    builder.worked = worked;
    // ⚠️ Договорът в contract() е ПЪЛНО работно време, тоест редът е ал. 3 и дните стигат. Кой ред
    // важи НЕ зависи от това дали часовете са използваеми — това реши BgWorkTimeRegimeTest. Тук се
    // проверява само че сглобителят различава „нула при налични редове“ от „нула отработени часа“.
    BgPayrollWorkTime.Decision decision =
        BgPayrollWorkTime.decide(builder.build(contract(), march()));
    assertTrue(decision.facts.hasDayData(), "НЕВД чл. 1 ал. 3");
    assertEquals(new BigDecimal("21"), decision.facts.getWorkingDaysInMonth());
    assertEquals(new BigDecimal("3"), decision.facts.getWorkedDays());
  }

  @Test
  @DisplayName("Един ден, отчетен на два реда, е ЕДИН отработен ден")
  void twoLinesOnTheSameDayAreOneWorkedDay() {
    List<Object[]> rows =
        Arrays.asList(
            new Object[] {LocalDate.of(2026, 3, 2), new BigDecimal("4.00")},
            new Object[] {LocalDate.of(2026, 3, 2), new BigDecimal("4.00")});
    BgPayrollInputBuilder.WorkedTime worked = BgPayrollInputBuilder.WorkedTime.of(rows);
    assertEquals(1, worked.days, "различни ДАТИ, не редове");
    assertEquals(new BigDecimal("8.00"), worked.hours, "часовете обаче се сумират");
  }

  // ═══ КАКВО ПОДАВА СГЛОБИТЕЛЯТ НА ПРОПОРЦИЯТА ═════════════════════════════

  @Test
  @DisplayName("⚠️ ПОДАДЕН НЕПЪЛЕН МЕСЕЦ НЕ ИЗЛИЗА КАТО ПЪЛЕН — по своя ред за всеки режим")
  void aPartialMonthNeverComesOutAsAFullMonth() {
    Builder builder = new Builder();

    // (а) ПЪЛНО работно време, 10 от 21 отработени дни → ал. 3
    builder.worked = new BgPayrollInputBuilder.WorkedTime(new BigDecimal("80.00"), 10);
    BgPayrollWorkTime.Decision full = BgPayrollWorkTime.decide(builder.build(contract(), march()));
    assertTrue(
        full.facts != null && full.facts.hasDayData(),
        "СГЛОБИТЕЛЯТ ВЪРНА ПЪЛЕН МЕСЕЦ ПРИ ПОДАДЕН НЕПЪЛЕН: 10 от 21 работни дни е половин месец, а"
            + " наборът не носи нито ден. Тогава прагът се сравнява ЦЯЛ и осигурителната база излиза"
            + " завишена — тихо, защото на екрана няма нищо, което да го каже.");
    assertEquals(
        "BgWorkTimeFacts[days 10+null/21]",
        full.facts.toString(),
        "наборът казва точно кое правило носи");

    // (б) НЕПЪЛНО работно време, 84 от 168 законоустановени часа → ал. 4
    builder.worked = new BgPayrollInputBuilder.WorkedTime(new BigDecimal("84.00"), 11);
    BgPayrollWorkTime.Decision part =
        BgPayrollWorkTime.decide(builder.build(partTimeContract(), march()));
    assertTrue(
        part.facts != null && part.facts.hasHourData(),
        "същото за непълното работно време: 84 от 168 часа е половин месец");
    assertEquals("BgWorkTimeFacts[hours 84.00+null/168]", part.facts.toString());
  }

  @Test
  @DisplayName("⚠️ Непокрита календарна година маха ЗНАМЕНАТЕЛЯ, а не го подава завишен")
  void anUncoveredCalendarYearDropsTheDenominator() {
    Builder builder = new Builder();
    builder.workingDayService =
        calendar(new HashSet<>(Arrays.asList(2026, 2027)), Collections.emptySet());
    builder.worked = new BgPayrollInputBuilder.WorkedTime(new BigDecimal("84.00"), 11);

    Period twentyEight = new Period();
    twentyEight.setFromDate(LocalDate.of(2028, 3, 1));
    twentyEight.setToDate(LocalDate.of(2028, 3, 31));

    BgPayrollInput input = builder.build(contract(), twentyEight);
    assertNull(
        input.workingDaysInPeriod,
        "ненадеждното число не се предава: преброено само по понеделник–петък, БЕЗ празниците, то е"
            + " ЗАВИШЕН знаменател, тоест по-нисък праг и по-ниска база");

    BgPayrollWorkTime.Decision decision = BgPayrollWorkTime.decide(input);
    assertFalse(decision.isRefused(), "режимът СЕ знае — не е отказ");
    assertFalse(decision.facts.hasHourData(), "без знаменател не се пропорционира");
    assertFalse(decision.facts.hasDayData());
    assertTrue(
        BgPayrollWorkTime.describe(decision).contains("Причината е обявена"),
        BgPayrollWorkTime.describe(decision));
  }

  @Test
  @DisplayName("⚠️ Договор без попълнени часове на ден → ОТКАЗ, не изчисление по подразбиране")
  void aContractWithoutContractedHoursRefuses() {
    // ⚠️ Заварените договори могат да носят bg_hours_per_day = 0,00, тоест непопълнено. Отказът
    // прави липсата видима, вместо да сравнява праг наужким по единия от двата реда.
    EmploymentContract bare = new EmploymentContract();
    bare.setId(1L);
    Builder builder = new Builder();
    builder.worked = new BgPayrollInputBuilder.WorkedTime(new BigDecimal("84.00"), 11);

    BgPayrollWorkTime.Decision decision = BgPayrollWorkTime.decide(builder.build(bare, march()));
    assertTrue(decision.isRefused(), "непопълнено договорено работно време е НЕЗНАНИЕ");
    assertEquals(BgPayrollWorkTime.Regime.UNKNOWN, decision.regime);
    assertNull(decision.facts);
  }

  @Test
  @DisplayName("Без договор и без период сглобителят не хвърля, а решението е ОТКАЗ")
  void anEmptyInputYieldsARefusalRatherThanAnException() {
    BgPayrollInput input = new Builder().build(null, (Period) null);

    assertNull(input.employmentContractId);
    assertNull(input.periodFrom);
    assertNull(input.workingDaysInPeriod);
    assertEquals("NON_TEACHER", input.teacherScope, "липса на учителска длъжност е ОТГОВОР");
    assertTrue(
        BgPayrollWorkTime.decide(input).isRefused(), "нищо не се знае — нищо не се предполага");
  }

  // ═══ ЦЯЛАТА ВЕРИГА: ОТ ДОГОВОРА ДО ПРАГА ═════════════════════════════════

  @Test
  @DisplayName("⚠️ ВЕРИГАТА: непълно работно време наполовява законовия праг по ал. 4")
  void theProrationReachesTheStatutoryFloor() {
    BigDecimal floor = new BigDecimal("550.66");
    BigDecimal cap = new BigDecimal("2111.64");

    Builder builder = new Builder();
    builder.worked = new BgPayrollInputBuilder.WorkedTime(new BigDecimal("84.00"), 11);
    BgPayrollWorkTime.Decision half =
        BgPayrollWorkTime.decide(builder.build(partTimeContract(), march()));

    BgInsurableIncomeResult prorated =
        BgInsurableIncomeService.compute(BigDecimal.ZERO, null, floor, true, cap, null, half.facts);
    assertTrue(prorated.isResolved(), prorated.getDetail());
    assertEquals(
        new BigDecimal("275.33"),
        prorated.getAmount(),
        "84/168 = 0,5 → 550,66 наполовина. ⚠️ Път, който вика fullMonth(), никога не стига дотук:"
            + " прагът се сравнява цял.");

    // Същият договор, БЕЗ отчет: при непълно работно време това е ОТКАЗ, а не цял праг — ал. 4 иска
    // часове и не допуска дневния ред като замяна.
    builder.worked = BgPayrollInputBuilder.WorkedTime.unknown();
    assertTrue(
        BgPayrollWorkTime.decide(builder.build(partTimeContract(), march())).isRefused(),
        "липсващият отчет при непълно работно време е обявена липса, не цял праг");

    // А при ПЪЛНО работно време без отчет прагът е цял — и това е прочит на нормата, не
    // подразбиране.
    BgPayrollWorkTime.Decision whole = BgPayrollWorkTime.decide(builder.build(contract(), march()));
    assertFalse(whole.isRefused());
    BgInsurableIncomeResult fullMonth =
        BgInsurableIncomeService.compute(
            BigDecimal.ZERO, null, floor, true, cap, null, whole.facts);
    assertEquals(floor, fullMonth.getAmount(), "ал. 3 важи само при НЕотработени всички дни");
  }

  @Test
  @DisplayName("Изречението за човек казва КОЕ правило се е приложило")
  void theDescriptionNamesTheRuleThatApplied() {
    Builder builder = new Builder();
    builder.worked = new BgPayrollInputBuilder.WorkedTime(new BigDecimal("84.00"), 11);

    String hours =
        BgPayrollWorkTime.describe(
            BgPayrollWorkTime.decide(builder.build(partTimeContract(), march())));
    assertTrue(hours.contains("ал. 4"), hours);
    assertTrue(hours.contains("168"), hours);

    String days =
        BgPayrollWorkTime.describe(BgPayrollWorkTime.decide(builder.build(contract(), march())));
    assertTrue(days.contains("ал. 3"), days);
    assertTrue(days.contains("21"), days);
  }

  // ═══ ВИДЪТ ОСИГУРЕН И ОБХВАТЪТ ПО ЧЛ. 4а АЛ. 2 ════════════════════════════

  @Test
  @DisplayName("⚠️ ЧЕРВЕНО · договор без вид осигурен дава ОТКАЗ с причина, НЕ подразбиране")
  void aContractWithoutTheInsuredTypeIsRefusedRatherThanDefaulted() {
    EmploymentContract without = contract();
    without.setBgInsuredTypeCode(null);

    Builder builder = new Builder();
    BgPayrollInputBuilder.InsuredType decision = builder.insuredTypeOf(without);

    assertTrue(decision.isRefused(), "празният вид осигурен трябва да е ОТКАЗ, не мълчание");
    assertNull(decision.code, "⚠️ НЕ „01“: най-честият код е и най-опасното подразбиране");
    assertEquals(
        BgPayrollInputBuilder.REFUSALS.get("insuredTypeCode"),
        decision.refusal,
        "текстът на отказа идва от REFUSALS, а не от мястото на извикването");
    assertTrue(decision.refusal.contains("BgContributionResolver"), decision.refusal);
    assertTrue(decision.refusal.contains("01 НЕ Е ПОДРАЗБИРАНЕ"), decision.refusal);

    // Празният низ се чете като липса — форма след изчистване на селекцията записва точно него.
    EmploymentContract blank = contract();
    blank.setBgInsuredTypeCode("   ");
    assertTrue(builder.insuredTypeOf(blank).isRefused(), "празният низ НЕ е валиден вид осигурен");

    // И през целия сглобител: полето остава null, а не подразбрана стойност.
    assertNull(
        builder.build(without, march()).insuredTypeCode,
        "⚠️ Отказът НЕ хвърля — той пътува като липсваща стойност, за да не счупи диагностичния"
            + " екран за заварен договор. Веригата надолу няма какво да сметне и това е верният"
            + " изход, а не подразбран код.");

    // Отказ без записана причина ПАДА — същият механизъм като при обявената липса.
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> BgPayrollInputBuilder.refuse("nyamaTakuvOtkaz"));
    assertTrue(e.getMessage().contains("REFUSALS"), e.getMessage());
  }

  @Test
  @DisplayName("Морско лице с вдигнат избор дава ART4A_AL2; без него — ANY")
  void theSeafarerChoiceMapsToTheArt4aAl2Scope() {
    Builder builder = new Builder();

    EmploymentContract chosen = contract();
    chosen.setBgInsuredTypeCode("92");
    chosen.setBgSeafarerUnemploymentChoice(true);
    assertEquals("ART4A_AL2", builder.build(chosen, march()).solScope, "упражненият избор");

    EmploymentContract notChosen = contract();
    notChosen.setBgInsuredTypeCode("92");
    notChosen.setBgSeafarerUnemploymentChoice(false);
    assertEquals(
        "ANY",
        builder.build(notChosen, march()).solScope,
        "⚠️ Неупражнената опция е неупражнена: чл. 4а ал. 2 КСО дава избора „по свой избор и"
            + " желание“, тоест без изричен избор безработица НЕ се дължи. Обратната посока би"
            + " начислила 1 на сто на всяко морско лице.");

    // Непопълненият флаг чете се като „не“ — генерираният getter за Boolean връща FALSE и за null.
    EmploymentContract untouched = contract();
    untouched.setBgInsuredTypeCode("92");
    assertEquals("ANY", builder.build(untouched, march()).solScope, "непопълнено = неупражнено");

    // Без договор пак ANY, не null: празният обхват би минал през филтъра случайно.
    assertEquals("ANY", builder.build((EmploymentContract) null, march()).solScope, "без договор");
  }
}

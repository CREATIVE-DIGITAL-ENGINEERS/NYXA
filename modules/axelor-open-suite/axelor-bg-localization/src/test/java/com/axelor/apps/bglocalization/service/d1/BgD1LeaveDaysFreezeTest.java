package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInput;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInputBuilder;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ДНИТЕ НА Т. 16 СЕ ЧЕТАТ ОТ ФИША, А НЕ СЕ СМЯТАТ НАНОВО.
 *
 * <p><b>ЗАЩО.</b> Декларация обр. 1, която смята дните отпуск САМА, с „огледална“ заявка над същите
 * заявки, които плащат и фиша, държи две сметки на едно число. Те съвпадат само докато никой не
 * пипне едната половина: {@code BgPayrollInputBuilder} не отказва часова заявка, заявка през
 * границата на периода и дробен ден, а огледална заявка с тези три отказа би казвала за едни и същи
 * дни друго, различно от фиша.
 *
 * <p><b>⚠️ ОСТРИЯТ СЛУЧАЙ Е STRADDLE-ЪТ, не часовете.</b> Заявка 28.12–08.01 за 9 работни дни
 * неплатен отпуск сваля 4 дни от декемврийската заплата; огледалната сметка би свалила НУЛА от
 * декемврийската т. 16.5 — декларация, която обявява 22 дни с осигурителни вноски там, където
 * вноски има за 18.
 */
class BgD1LeaveDaysFreezeTest {

  private static final LocalDate DEC_FROM = LocalDate.of(2026, 12, 1);
  private static final LocalDate DEC_TO = LocalDate.of(2026, 12, 31);
  private static final LocalDate JAN_FROM = LocalDate.of(2027, 1, 1);
  private static final LocalDate JAN_TO = LocalDate.of(2027, 1, 31);

  /** 24 и 25.12.2026 са официални празници; 1.01.2027 — Нова година. */
  private static final Set<LocalDate> HOLIDAYS =
      new HashSet<>(
          Arrays.asList(
              LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 25), LocalDate.of(2027, 1, 1)));

  // ═══ СГЛОБИТЕЛЯТ РАЗПРЕДЕЛЯ ПО КОФИ ══════════════════════════════════════

  @Test
  @DisplayName("⚠️ STRADDLE · 28.12–08.01, 9 дни → декември 4 в т. 16.5, януари 5")
  void theStraddlingRequestIsSplitAcrossBothPeriods() {
    Object[] request = unpaidRow("2026-12-28", "2027-01-08", "9", "16.5");

    BgPayrollInputBuilder.LeaveDays december = leaveDaysOf(DEC_FROM, DEC_TO, request);
    BgPayrollInputBuilder.LeaveDays january = leaveDaysOf(JAN_FROM, JAN_TO, request);

    // Декември: 28, 29, 30, 31.12 = 4 работни дни. Януари: 4–8.01 = 5 (1.01 е празник). Сборът е 9.
    assertEquals(
        0,
        new BigDecimal("4.00").compareTo(december.byD1Code.get(BgD1DayBuckets.B_16_5)),
        "⚠️ Д1 НЕ БИВА ДА ВРЪЩА НУЛА ЗА ТОЗИ МЕСЕЦ. duration описва ЦЯЛАТА заявка, но фишът я реже"
            + " по работни дни и сваля 4 дни заплата. Намерено: "
            + december.byD1Code);
    assertEquals(
        0,
        new BigDecimal("5.00").compareTo(january.byD1Code.get(BgD1DayBuckets.B_16_5)),
        "и януарската част: " + january.byD1Code);

    assertEquals(
        0,
        new BigDecimal("9.00")
            .compareTo(
                december
                    .byD1Code
                    .get(BgD1DayBuckets.B_16_5)
                    .add(january.byD1Code.get(BgD1DayBuckets.B_16_5))),
        "⚠️ И ДВЕТЕ ЧАСТИ ЗАЕДНО ДАВАТ ЦЯЛАТА ЗАЯВКА. Реконсилирането е самото твърдение: срязана"
            + " по два различни начина, заявката щеше да изгуби или да удвои ден на границата.");
  }

  @Test
  @DisplayName("⚠️ ЕДИН ОБХОД, ДВА ИЗХОДА · кофите и парите излизат от едни и същи дни")
  void theBucketsAndTheMoneyComeFromTheSameWalk() {
    BgPayrollInputBuilder.LeaveDays days =
        leaveDaysOf(DEC_FROM, DEC_TO, unpaidRow("2026-12-07", "2026-12-11", "5", "16.5"));

    assertEquals(
        0,
        days.unpaidInD1.compareTo(days.byD1Code.get(BgD1DayBuckets.B_16_5)),
        "⚠️ ТОВА Е ЦЯЛОТО ПРАВИЛО В ЕДИН РЕД: числото, което СВАЛЯ заплатата (unpaidInD1), и"
            + " числото, което Д1 вади от т. 16.1 (кофата), са ЕДНО И СЪЩО, а не две сметки.");
  }

  @Test
  @DisplayName("⚠️ ЧАСОВЕ · 1 ч при 8-часов ден → 0,13 дни в кофата, БЕЗ отказ")
  void anHourlyRequestIsConvertedNotRefused() {
    BgPayrollInputBuilder.LeaveDays days =
        leaveDaysOf(DEC_FROM, DEC_TO, hourlyPaidRow("2026-12-07", "2026-12-07", "1", "16.1"));

    assertEquals(
        0,
        new BigDecimal("0.13").compareTo(days.byD1Code.get(BgD1DayBuckets.B_16_1)),
        "1 ÷ 8 = 0,125 → 0,13 при две знака. Делителят е ДОГОВОРЕНИЯТ ден — същият, който заб. 2"
            + " към т. 16 нарича „дневното договорено работно време“. Намерено: "
            + days.byD1Code);
  }

  // ═══ Д1 ЧЕТЕ ЗАМРАЗЕНОТО ═════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Д1 чете ЗАМРАЗЕНИТЕ дни и НЕ вдига находка за straddle")
  void theDeclarationReadsTheFrozenDaysWithoutComplaining() {
    BgPaySlip slip = frozenSlip(new BigDecimal("4.00"));
    BgD1DaySource.Outcome out = new Source(straddleRow()).read(slip, period());

    assertEquals(
        Collections.singletonList("16.5:4"),
        describe(out),
        "четирите дни идват от фиша, не от заявката: " + describe(out));
    assertEquals(
        new ArrayList<String>(),
        out.problems,
        "⚠️ И НУЛА НАХОДКИ. Сглобителят няма тези три отказа: заявка през границата се реже по"
            + " работни дни. Отказ тук би вдигнал WARNING върху число, което фишът смята вярно."
            + " Намерено: "
            + out.problems);
  }

  @Test
  @DisplayName(
      "⚠️ МИГРАЦИЯ · фиш ОТПРЕДИ замразяването пада на старата сметка — историята не мърда")
  void aPreFreezeSlipFallsBackToTheMirrorQuery() {
    BgPaySlip slip = frozenSlip(new BigDecimal("4.00"));
    slip.setBgLeaveDaysFrozenAt(null); // фиш отпреди колоната

    BgD1DaySource.Outcome out = new Source(straddleRow()).read(slip, period());

    assertEquals(
        new ArrayList<String>(),
        describe(out),
        "⚠️ СТАРОТО ПОВЕДЕНИЕ, ДУМА ПО ДУМА: straddle-ът се отказва и дните НЕ влизат. Това е"
            + " грешно — но за вече подадена декларация то е ИСТОРИЯ, а коригираща Д1 за онзи"
            + " месец трябва да носи същите числа. Намерено: "
            + describe(out));
    assertTrue(
        out.problems.size() == 1 && out.problems.get(0).contains("пресича границата"),
        "и находката остава, за да се види защо: " + out.problems);

    // ⚠️ ДИСКРИМИНАТОРЪТ Е ДАТАТА, А НЕ ЧИСЛОТО. Генерираният getter за decimal връща НУЛА, не
    // null — тоест „нула дни отпуск“ и „фиш отпреди замразяването“ са неразличими по кофите.
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(frozenSlip(BigDecimal.ZERO).getBgLeaveDays165()),
        "замразена нула изглежда точно като незамразено поле — оттам и отделната дата");
  }

  @Test
  @DisplayName("⚠️ ФОРМАТ · дробните дни се закръглят HALF_UP на ЧЕТЕНЕ, не при замразяването")
  void theFractionalDaysAreRoundedOnRead() {
    assertEquals(5, BgD1DaySource.wholeDays(new BigDecimal("4.50")), "половината отива нагоре");
    assertEquals(4, BgD1DaySource.wholeDays(new BigDecimal("4.49")), "под половината — надолу");
    assertEquals(
        0,
        BgD1DaySource.wholeDays(new BigDecimal("0.13")),
        "⚠️ И ЕДИН ЧАС ОТПУСК НЕ ВАДИ ДЕН ОТ Т. 16.1. Лицето е отработило 7 от 8 часа, вноски се"
            + " дължат, денят Е ден в осигуряване. Указанието дава правило за превръщане на часове"
            + " в дни само при СУМИРАНО изчисляване (заб. 2 към т. 16) — извън него частичният ден"
            + " не се дроби.");

    BgPaySlip slip = frozenSlip(new BigDecimal("4.50"));
    BgD1DaySource.Outcome out = new Source(Collections.emptyList()).read(slip, period());
    assertEquals(
        Collections.singletonList("16.5:5"),
        describe(out),
        "⚠️ ЗАМРАЗЕНОТО ОСТАВА СУРОВО: 4,50 на фиша, 5 в декларацията. Закръглено при"
            + " замразяването, две заявки по половин ден в един месец щяха да дадат 1 + 1 вместо 1.");
    assertEquals(
        0,
        new BigDecimal("4.50").compareTo(slip.getBgLeaveDays165()),
        "и фишът пази точното число");
  }

  // ═══ ПАЗАЧЪТ И ПАРИТЕ ════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ FAIL-LOUD · кофи, надхвърлящи преброените отсъствия, НЕ се замразяват")
  void theGuardRefusesWhenTheBucketsExceedTheAbsences() {
    BgPaySlip slip = new BgPaySlip();
    slip.setReference("BGPS-2026-12-001");

    // Изкуствено разминаване: кофите твърдят 7 дни, отсъствията — 5. Двете излизат от ЕДИН обход,
    // тоест това е невъзможно, докато обходът е един. Стане ли възможно, значи някой е върнал
    // втора сметка — и точно тогава числото НЕ бива да стигне до декларация.
    BgPayrollInput input =
        BgPayrollInput.builder()
            .unpaidLeaveDays(new BigDecimal("5.00"))
            .leaveDaysByD1Code(
                Collections.singletonMap(BgD1DayBuckets.B_16_5, new BigDecimal("7.00")))
            .build();

    IllegalStateException boom =
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, () -> new Freezer().freeze(slip, input));

    assertTrue(
        boom.getMessage().contains("надхвърлят преброените дни отсъствие"),
        "причината назовава ОБЕТЕ числа, а не просто „не мога“: " + boom.getMessage());
    assertTrue(
        slip.getBgLeaveDaysFrozenAt() == null,
        "⚠️ И ДАТАТА НЕ СЕ ВДИГА. Вдигната преди проверката, тя щеше да обяви за замразени числа,"
            + " които току-що са отказани — а Д1 чете точно нея, за да реши дали да им вярва.");
  }

  @Test
  @DisplayName("⚠️ ПАРИТЕ · замразяването не пипа нито едно число на фиша")
  void theFreezeTouchesNothingButItsOwnFields() {
    BgPaySlip slip = new BgPaySlip();
    slip.setBgBaseSalary(new BigDecimal("1000.00"));
    slip.setBgWorkedDays(22);
    slip.setBgWorkingDaysInPeriod(22);
    slip.setBgSickDaysEmployer(2);
    slip.setBgSickDaysDoo(3);
    slip.setBgPaySlipLineList(new ArrayList<>());

    BgPayrollInput input =
        BgPayrollInput.builder()
            .unpaidLeaveDays(new BigDecimal("4.00"))
            .leaveDaysByD1Code(
                Collections.singletonMap(BgD1DayBuckets.B_16_5, new BigDecimal("4.00")))
            .build();
    new Freezer().freeze(slip, input);

    // ⚠️ СТРУКТУРНОТО ТВЪРДЕНИЕ, ПРОВЕРЕНО ЧИСЛЕНО. Замразяването само ПИШЕ нови полета; парите се
    // смятат от BgPayrollInput.unpaidLeaveDaysInD1 и art177LeaveDays — същите полета, от същия
    // вход,
    // както преди. Нула нови читателя по паричния път.
    assertEquals(new BigDecimal("1000.00"), slip.getBgBaseSalary(), "основната не мърда");
    assertEquals(22, slip.getBgWorkedDays(), "отработените дни не мърдат");
    assertEquals(22, slip.getBgWorkingDaysInPeriod(), "нормата не мърда");
    assertEquals(2, slip.getBgSickDaysEmployer(), "замразените болнични не мърдат");
    assertEquals(3, slip.getBgSickDaysDoo(), "и другата им половина");
    assertTrue(slip.getBgPaySlipLineList().isEmpty(), "нито едно перо не е добавено или пипнато");

    assertEquals(
        0,
        new BigDecimal("4.00").compareTo(slip.getBgLeaveDays165()),
        "а собственото му поле Е записано");
    assertTrue(slip.getBgLeaveDaysFrozenAt() != null, "и датата е вдигната");
  }

  /** {@code BgPaySlipService} без база — вика се САМО двуарговата врата, която не пита нищо. */
  private static final class Freezer
      extends com.axelor.apps.bglocalization.service.BgPaySlipService {
    void freeze(BgPaySlip slip, BgPayrollInput input) {
      freezeLeaveDays(slip, input);
    }
  }

  // ═══ ПОМОЩНИЦИ ═══════════════════════════════════════════════════════════

  private static List<String> describe(BgD1DaySource.Outcome out) {
    List<String> seen = new ArrayList<>();
    for (BgD1DayBuckets.Entry entry : out.entries) {
      seen.add(entry.toString());
    }
    return seen;
  }

  private static Period period() {
    Period p = new Period();
    p.setFromDate(DEC_FROM);
    p.setToDate(DEC_TO);
    return p;
  }

  /** Фиш със замразени дни в т. 16.5 и вдигната дата. */
  private static BgPaySlip frozenSlip(BigDecimal unpaidDays) {
    BgPaySlip slip = new BgPaySlip();
    slip.setBgLeaveDays165(unpaidDays);
    slip.setBgLeaveDaysFrozenAt(LocalDateTime.of(2026, 12, 31, 12, 0));
    EmploymentContract contract = new EmploymentContract();
    Employee employee = new Employee();
    employee.setId(7L);
    contract.setEmployee(employee);
    slip.setEmploymentContract(contract);
    return slip;
  }

  /** Редът, както Д1 го чете от базата: 7 колони. */
  private static List<Object[]> straddleRow() {
    return Collections.singletonList(
        new Object[] {
          LocalDate.parse("2026-12-28").atStartOfDay(),
          LocalDate.parse("2027-01-08").atTime(23, 59, 59),
          new BigDecimal("9"),
          1,
          "16.5",
          "Неплатен по желание",
          Boolean.TRUE
        });
  }

  private static Object[] unpaidRow(String from, String to, String days, String d1Code) {
    return builderRow(from, to, days, 1, 7, Boolean.FALSE, Boolean.FALSE, d1Code);
  }

  private static Object[] hourlyPaidRow(String from, String to, String hours, String d1Code) {
    return builderRow(from, to, hours, 2, 1, Boolean.TRUE, Boolean.FALSE, d1Code);
  }

  private static Object[] builderRow(
      String from,
      String to,
      String amount,
      int unit,
      int category,
      Boolean paid,
      Boolean art177,
      String d1Code) {
    return new Object[] {
      LocalDate.parse(from).atStartOfDay(),
      LocalDate.parse(to).atTime(23, 59, 59),
      new BigDecimal(amount),
      unit,
      category,
      paid,
      12L,
      art177,
      d1Code,
      Boolean.TRUE,
      // ⚠️ ЕДИНАЙСЕТАТА колона: healthContributionRule. Фикстурите тук са на неплатен отпуск по
      // желание и на платен в часове — първият носи платец по ЗЗО чл. 40 ал. 1 т. 1 б. „б“, вторият
      // изобщо не е в неплатената кофа. Виж BgPayrollInput#unpaidLeaveDaysUnderZzo.
      com.axelor.apps.bglocalization.service.payroll.BgUnpaidLeaveHealthResolver
          .RULE_UNPAID_EMPLOYEE
    };
  }

  private static BgPayrollInputBuilder.LeaveDays leaveDaysOf(
      LocalDate from, LocalDate to, Object[]... rows) {
    Employee employee = new Employee();
    employee.setId(7L);
    return new StubBuilder(Arrays.asList(rows)).days(employee, from, to);
  }

  private static final class StubBuilder extends BgPayrollInputBuilder {

    private final List<Object[]> rows;

    private StubBuilder(List<Object[]> rows) {
      this.rows = rows;
    }

    /**
     * ⚠️ ПРЕХОДНИК, ЗАЩОТО {@code leaveDaysOf} Е {@code protected}, А ТОЗИ ТЕСТ Е В ДРУГ ПАКЕТ.
     * Подкласът си го вика сам. Разширяването на видимостта в самия сглобител би било промяна в
     * ПРОДУКЦИОНЕН шев заради тест — а шевът е тесен нарочно.
     */
    BgPayrollInputBuilder.LeaveDays days(Employee employee, LocalDate from, LocalDate to) {
      return leaveDaysOf(employee, from, to, new BigDecimal("8.00"));
    }

    @Override
    protected List<Object[]> leaveRows(Employee employee, LocalDate from, LocalDate to) {
      return rows;
    }

    @Override
    protected Set<LocalDate> officialHolidays(LocalDate from, LocalDate to) {
      Set<LocalDate> in = new HashSet<>();
      for (LocalDate day : HOLIDAYS) {
        if (!day.isBefore(from) && !day.isAfter(to)) {
          in.add(day);
        }
      }
      return in;
    }

    @Override
    protected Map<LocalDate, BigDecimal> workedHoursByDate(
        Employee employee, LocalDate from, LocalDate to) {
      return Collections.emptyMap();
    }
  }

  /** {@code BgD1DaySource} без база — заявката е подменена. */
  private static final class Source extends BgD1DaySource {

    private final List<Object[]> rows;

    private Source(List<Object[]> rows) {
      this.rows = rows;
    }

    @Override
    protected List<Object[]> leaveRows(Employee employee, LocalDate from, LocalDate to) {
      return rows;
    }
  }
}

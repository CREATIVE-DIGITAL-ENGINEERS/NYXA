package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgSelfInsuredRegistration;
import com.axelor.apps.bglocalization.service.d1.BgD1DayBuckets;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ВХОДЪТ НА КОРИГИРАЩОТО КОПИЕ — сглобен САМО от замразеното на фиша, без нито една заявка.
 *
 * <p><b>Защо отделно от {@link BgPayrollInputBuilder}.</b> Сглобителят ЧЕТЕ: договора, отчетите за
 * време, заявките за отпуск, болничните листове. Копието за март, пресметнато през октомври, не
 * бива да чете нито едно от тях — коригира се ОНЗИ месец, а живите данни оттогава са други. Тук
 * входът се пише от полетата на самия фиш: режимът на осигуряване, дванайсетте часови полета, дните
 * по кофи, дните на карва и осигурителният доход при друг платец. Заявка няма по устройство, не по
 * договорка — класът няма достъп до база.
 *
 * <p><b>⚠️ КАКВОТО НЕ Е ЗАМРАЗЕНО, ОСТАВА ПРАЗНО — и това е твърдение, не пропуск.</b> Болничните
 * епизоди, ръчните редове, документите на обезщетенията, признатият стаж, датата на раждане,
 * научната степен — техните генератори (начисления, болничен, обезщетения, чл. 177) НЕ вървят върху
 * копие: числата им са замразени като ПЕРА. Генераторите, които вървят (5xx, 516 по ЗЗО чл. 40 ал.
 * 1 т. 5, 700 и карвът на 180/181), четат само полетата, попълнени тук.
 *
 * <p><b>⚠️ ПАРАМЕТРИТЕ СА ТЕЗИ ЗА ПЕРИОДА.</b> {@code parameterReferenceDate} е началото на периода
 * на фиша — таванът за 07/2026 е 2111,64, за 09/2026 — 2300,00, колкото и късно да се пресмята
 * копието.
 */
public final class BgFrozenPayrollInput {

  private BgFrozenPayrollInput() {}

  /**
   * Входът на копието, или {@code null} без период или без замразен режим — тогава пресмятането
   * вече е отказало ({@code BgPayrollRegime#missingRefusal}).
   *
   * @param otherEmployerInsurableIncome осигурителният доход при друг платец, вече разрешен от фиша
   *     ({@code BgPaySlipService#effectiveOtherPayerIncome}) — и той е замразен
   */
  public static BgPayrollInput of(
      BgPaySlip slip, BgPayrollRegime regime, BigDecimal otherEmployerInsurableIncome) {
    if (slip == null || slip.getPeriod() == null || regime == null) {
      return null;
    }
    LocalDate from = slip.getPeriod().getFromDate();
    LocalDate to = slip.getPeriod().getToDate() != null ? slip.getPeriod().getToDate() : from;
    BgSelfInsuredRegistration registration = slip.getBgSelfInsuredRegistration();
    EmploymentContract contract = registration != null ? null : slip.getEmploymentContract();
    Employee employee =
        registration != null
            ? registration.getEmployee()
            : contract != null ? contract.getEmployee() : null;

    BgPayrollInput.Builder b = BgPayrollInput.builder();
    b.employmentContractId(contract != null ? contract.getId() : null)
        .selfInsuredRegistrationId(registration != null ? registration.getId() : null)
        .employeeId(employee != null ? employee.getId() : null)
        // ── режимът — дословно замразеният ──────────────────────────────────
        .insuredTypeCode(regime.insuredTypeCode)
        .laborCategory(regime.laborCategory)
        .bornAfter1959(regime.bornAfter1959)
        .pensionChoice(regime.pensionChoice)
        .solScope(regime.solScope)
        .teacherScope(regime.teacherScope)
        .nkpdCode(regime.nkpdCode)
        .nkidCode(regime.nkidCode)
        .contractHoursPerDay(regime.hoursPerDay)
        .contractDaysPerWeek(regime.daysPerWeek)
        .workTimeMode(regime.workTimeMode)
        .expenseRate(regime.expenseRate)
        .insuredOnOtherGround(regime.insuredOnOtherGround)
        .selfInsuredSelectedIncome(registration != null ? regime.selfInsuredIncome : null)
        .otherEmployerInsurableIncome(otherEmployerInsurableIncome)
        // ── заплатата и периодът ────────────────────────────────────────────
        .baseSalary(slip.getBgBaseSalary())
        .periodFrom(from)
        .periodTo(to)
        .parameterReferenceDate(from)
        .workingDaysInPeriod(positive(slip.getBgWorkingDaysInPeriod()))
        // ── отработеното — дванайсетте замразени полета, всяко със своето „не знам“ ──
        // ⚠️ ДНИТЕ С ОТЧЕТ СА workedDays: сглобителят пише в двете полета един и същ размер на
        // картата по дати, а празната карта дава null и в двете. Нула тук значи „без отчет“.
        .workedDays(positive(slip.getBgTimesheetDays()))
        .timesheetDays(positive(slip.getBgTimesheetDays()))
        .workedHours(known(slip.getBgWorkedHoursKnown(), slip.getBgWorkedHours()))
        .nightHours(known(slip.getBgDeclaredHoursKnown(), slip.getBgNightHours()))
        .onCallHours(known(slip.getBgDeclaredHoursKnown(), slip.getBgOnCallHours()))
        .holidayWorkHours(known(slip.getBgHolidayHoursKnown(), slip.getBgHolidayWorkHours()))
        .overtimeWorkdayHours(
            known(slip.getBgOvertimeHoursKnown(), slip.getBgOvertimeWorkdayHours()))
        .overtimeRestDayHours(
            known(slip.getBgOvertimeHoursKnown(), slip.getBgOvertimeRestDayHours()))
        .overtimeHolidayHours(
            known(slip.getBgOvertimeHoursKnown(), slip.getBgOvertimeHolidayHours()))
        .overtimeSirvHours(known(slip.getBgOvertimeHoursKnown(), slip.getBgOvertimeSirvHours()));

    // ── отсъствията — кофите и дните на карва ─────────────────────────────
    if (slip.getBgLeaveDaysFrozenAt() != null) {
      b.leaveDaysByD1Code(bucketsOf(slip)).daysOutsideInsurance(slip.getBgLeaveDaysOutside());
    }
    if (carveDaysFrozen(slip)) {
      b.art177LeaveDays(slip.getBgArt177LeaveDays())
          .unpaidLeaveDaysInD1(slip.getBgUnpaidLeaveDaysInD1());
    } else if (carveDaysAreZero(slip)) {
      b.art177LeaveDays(BigDecimal.ZERO).unpaidLeaveDaysInD1(BigDecimal.ZERO);
    }
    return b.build();
  }

  /** Замразени ли са дните на карва — датата, а не числата, по довода в домейна. */
  public static boolean carveDaysFrozen(BgPaySlip slip) {
    return slip != null && slip.getBgCarveDaysFrozenAt() != null;
  }

  /**
   * ЗАМРАЗЕНИТЕ КОФИ СА ПРАЗНИ — тогава дните на карва са нула ТОЧНО, и без да са замразени.
   *
   * <p>⚠️ ПО УСТРОЙСТВО, А НЕ ПО СИЙДА. Дните по чл. 177 и неплатените дни в Д1 са подмножества на
   * дните С КОФА: всеки ден отсъствие в осигуряване носи непразен d1Code (празна кофа без {@code
   * countsAsInsuranceService = false} е назован отказ в сглобителя). Сбор нула по кофите значи нула
   * и в двете — без да се пита кой вид в коя кофа живее.
   *
   * <p>⚠️ ДНИТЕ ИЗВЪН ОСИГУРЯВАНЕТО НЕ СЕ БРОЯТ ТУК: те не са част от нито едно от двете числа, а
   * карвът ги чете отделно ({@code daysOutsideInsurance}). Затова копие от „Отрази пристигнал
   * болничен“, чийто единствен отсъстващ период е бил непредставеният болничен, не иска нищо.
   */
  public static boolean carveDaysAreZero(BgPaySlip slip) {
    if (slip == null || slip.getBgLeaveDaysFrozenAt() == null) {
      return false;
    }
    for (BigDecimal days : bucketsOf(slip).values()) {
      if (days.signum() != 0) {
        return false;
      }
    }
    return true;
  }

  private static Map<String, BigDecimal> bucketsOf(BgPaySlip slip) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    put(out, BgD1DayBuckets.B_16_1, slip.getBgLeaveDays161());
    put(out, BgD1DayBuckets.B_16_2, slip.getBgLeaveDays162());
    put(out, BgD1DayBuckets.B_16_3, slip.getBgLeaveDays163());
    put(out, BgD1DayBuckets.B_16_4, slip.getBgLeaveDays164());
    put(out, BgD1DayBuckets.B_16_5, slip.getBgLeaveDays165());
    put(out, BgD1DayBuckets.B_16_A, slip.getBgLeaveDays16A());
    return out;
  }

  /** Сглобителят пише в картата само кофи с дни — празната кофа отсъства, не стои с нула. */
  private static void put(Map<String, BigDecimal> out, String bucket, BigDecimal days) {
    if (days.signum() != 0) {
      out.put(bucket, days);
    }
  }

  private static Integer positive(Integer value) {
    return value != null && value > 0 ? value : null;
  }

  private static BigDecimal known(Boolean known, BigDecimal value) {
    return Boolean.TRUE.equals(known) ? value : null;
  }
}

package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * НЕПРЕДСТАВЕНИЯТ БОЛНИЧЕН В ДЕКЛАРАЦИЯ ОБР. 1 — т. 16.1 и двойката т. 14/15.
 *
 * <p><b>Защо този файл съществува отделно.</b> Денят без своя кофа се повишава в кофата „други“ на
 * ФИША, за да е инвариантът на {@code BgPaySlipService#freezeLeaveDays} верен по построение.
 * Повишаването е в БРОЕНЕТО на отсъствията; декларацията НЕ бива да го усети. Тук се мери точно
 * това: трите дни продължават да се ВАДЯТ от т. 16.1 (а не да пълнят точка) и продължават да носят
 * прекъсване по <b>КСО чл. 10 ал. 2</b> в т. 14/15.
 *
 * <p><b>⚠️ ДВЕТЕ ТВЪРДЕНИЯ СА НЕЗАВИСИМИ И ДВЕТЕ СА НУЖНИ.</b> Вписан в кофа на т. 16, денят щеше
 * да влезе в сбора по заб. 1 и да се върне обратно в „дни в осигуряване — общо“; изгубен от
 * прекъсванията, той щеше да остави т. 15 да твърди непрекъснато осигуряване за дати, за които т.
 * 16.1 вече е казала обратното.
 */
class BgD1UnsubmittedSickNoteDaysTest {

  private static final LocalDate DEC_FROM = LocalDate.of(2026, 12, 1);
  private static final LocalDate DEC_TO = LocalDate.of(2026, 12, 31);

  /** Числата на заданието: норма 22 работни дни, три дни непредставен болничен. */
  private static final int DEC_WORKING_DAYS = 22;

  @Test
  @DisplayName("⚠️ 3 дни непредставен болничен → т. 16.1 = 19, нула кофи, прекъсване 07–09.12")
  void theUnsubmittedSickNoteIsSubtractedFromPoint161AndInterruptsInsurance() {
    BgPaySlip slip = frozenSlip(new BigDecimal("3.00"));
    BgD1DaySource.Outcome out =
        read(slip, row("2026-12-07", "2026-12-09", "3", null, "Непредставен болничен", false));

    assertEquals(
        3,
        out.outsideInsuranceDays,
        "⚠️ ЧЕТЕ СЕ ОТ ЗАМРАЗЕНОТО ПОЛЕ bgLeaveDaysOutside, не наново от заявките. Повишаването в"
            + " кофата „други“ е на ФИША и не стига дотук — числото не се мени от него.");

    Map<String, Integer> byBucket = BgD1DayBuckets.sum(out.entries);
    assertEquals(
        0,
        BgD1DayBuckets.total(byBucket),
        "⚠️ И НУЛА КОФИ. Вписани в която и да е от шестте, същите дни щяха да влязат в сбора по"
            + " заб. 1 и да се върнат в „дни в осигуряване — общо“. Намерено: "
            + byBucket);

    assertEquals(
        1,
        out.interruptions.size(),
        "⚠️ И ТОЧНО ЕДНО ПРЕКЪСВАНЕ по КСО чл. 10 ал. 2 — двойката т. 14/15 за същите дати");
    assertEquals(LocalDate.of(2026, 12, 7), out.interruptions.get(0)[0], "първи ден извън");
    assertEquals(LocalDate.of(2026, 12, 9), out.interruptions.get(0)[1], "последен ден извън");
    assertTrue(
        out.problems.isEmpty(),
        "празна кофа + „не се зачита“ е СЪГЛАСУВАНАТА двойка, не проблем: " + out.problems);

    BgD1Record record = fill(slip, byBucket, out.outsideInsuranceDays);
    assertEquals(
        "19",
        record.getT161WorkedDays(),
        "⚠️ 22 отработени − 3 извън осигуряването. Това число НЕ се мени от повишаването: то пипа"
            + " коя кофа на ФИША носи деня, а не какво декларацията вади.");
    assertEquals("0", record.getT162SickDays(), "болничен лист няма — т. 16.2 е нула");
    assertEquals("0", record.getT16aEmployerSickDays(), "и т. 16.А също: няма дни по чл. 40 ал. 5");
    assertEquals(
        "19",
        record.getT16Days(),
        "⚠️ И СБОРЪТ ПО ЗАБ. 1 Е 19, НЕ 22: трите дни не са в осигуряване по нито един ред, и точно"
            + " това казва и т. 14/15 за същите дати.");
  }

  @Test
  @DisplayName("⚠️ КОНТРОЛА · самоотлъчка 3 дни дава СЪЩИТЕ числа — видът не мени изхода")
  void awolProducesTheSameNumbers() {
    BgPaySlip slip = frozenSlip(new BigDecimal("3.00"));
    BgD1DaySource.Outcome out =
        read(slip, row("2026-12-07", "2026-12-09", "3", null, "Самоотлъчка", false));

    Map<String, Integer> byBucket = BgD1DayBuckets.sum(out.entries);
    BgD1Record record = fill(slip, byBucket, out.outsideInsuranceDays);

    assertEquals(3, out.outsideInsuranceDays);
    assertEquals(1, out.interruptions.size());
    assertEquals(
        "19",
        record.getT161WorkedDays(),
        "⚠️ ДВАТА ВИДА СА НЕРАЗЛИЧИМИ ЗА ДЕКЛАРАЦИЯТА, и така трябва: и двата са „не се зачита за"
            + " осигурителен стаж, без кофа“. Разликата им е на фиша (категория 5 срещу 2) и вече"
            + " не се вижда оттук.");
  }

  // ═══ ФИКСТУРАТА ══════════════════════════════════════════════════════════

  /** Фиш със ЗАМРАЗЕНИ дни — точно каквото {@code freezeLeaveDays} записва. */
  private static BgPaySlip frozenSlip(BigDecimal outsideDays) {
    BgPaySlip slip = new BgPaySlip();
    slip.setBgWorkedDays(DEC_WORKING_DAYS);
    slip.setBgWorkingDaysInPeriod(DEC_WORKING_DAYS);
    slip.setBgLeaveDays161(BigDecimal.ZERO);
    slip.setBgLeaveDays162(BigDecimal.ZERO);
    slip.setBgLeaveDays163(BigDecimal.ZERO);
    slip.setBgLeaveDays164(BigDecimal.ZERO);
    slip.setBgLeaveDays165(BigDecimal.ZERO);
    slip.setBgLeaveDays16A(BigDecimal.ZERO);
    slip.setBgLeaveDaysOutside(outsideDays);
    slip.setBgLeaveDaysFrozenAt(LocalDate.of(2026, 12, 31).atStartOfDay());
    Employee employee = new Employee();
    employee.setId(7L);
    EmploymentContract contract = new EmploymentContract();
    contract.setEmployee(employee);
    contract.setBgHoursPerDay(new BigDecimal("8.00"));
    slip.setEmploymentContract(contract);
    return slip;
  }

  /** Ред на {@code BgD1DaySource#leaveRows} — седем колони, заявка в ДНИ. */
  private static Object[] row(
      String from, String to, String days, String d1Code, String typeName, boolean credited) {
    return new Object[] {
      LocalDate.parse(from).atStartOfDay(),
      LocalDate.parse(to).atTime(23, 59, 59),
      new BigDecimal(days),
      1,
      d1Code,
      typeName,
      Boolean.valueOf(credited)
    };
  }

  private static BgD1DaySource.Outcome read(BgPaySlip slip, Object[]... rows) {
    Period period = new Period();
    period.setFromDate(DEC_FROM);
    period.setToDate(DEC_TO);
    return new Source(Arrays.asList(rows)).read(slip, period);
  }

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

  /** Т. 16 така, както я пълни ИСТИНСКИЯТ строител на записа. */
  private static BgD1Record fill(BgPaySlip slip, Map<String, Integer> byBucket, int outside) {
    BgD1Record record = new BgD1Record();
    BgInsuredType type = new BgInsuredType();
    type.setCode("01");
    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1RecordBuilder.fillDays(
        record, slip, slip.getEmploymentContract(), type, byBucket, outside, null, findings, "01");
    List<String> aboutPoint16 = new ArrayList<>();
    for (BgD1FindingDto f : findings) {
      if ("D1_T161_NEGATIVE_REMAINDER".equals(f.getCode())
          || "D1_T16_EXCEEDS_PERIOD".equals(f.getCode())) {
        aboutPoint16.add(f.getCode() + ": " + f.getMessage());
      }
    }
    assertTrue(aboutPoint16.isEmpty(), "находки по т. 16: " + aboutPoint16);
    return record;
  }
}

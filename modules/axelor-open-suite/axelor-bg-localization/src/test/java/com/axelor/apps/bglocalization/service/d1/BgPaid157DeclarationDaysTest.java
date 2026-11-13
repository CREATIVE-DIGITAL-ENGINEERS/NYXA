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
 * ТРИТЕ РЕЖИМА НА КТ ЧЛ. 157 В ДЕКЛАРАЦИЯ ОБР. 1 — т. 16 и прекъсванията по т. 14/15.
 *
 * <p><b>Какво твърди този файл.</b> Разцепването мести не само пари, а и ТОЧКА от декларацията, и
 * двете посоки трябва да са измерими поотделно:
 *
 * <ul>
 *   <li><b>Режим 1 и 2</b> (ал. 3 т. 1 и т. 2) — работодателят плаща, тоест вноски има и дните са в
 *       <b>т. 16.1</b> („отработените дни в осигуряване … и всички дни с осигурителни вноски“).
 *       Разцепването НЕ мени точката им, само основата по т. 21.
 *   <li><b>Режим 3</b> (ал. 3 т. 3) — работодателят не плаща, тоест осигурителен доход по този
 *       договор НЯМА, а денят СЕ ЗАЧИТА за осигурителен стаж. Указание <b>т. 16.4</b> поема точно
 *       това: „работните дни в НЕПЛАТЕН отпуск за временна неработоспособност, и <b>други дни в
 *       осигуряване без осигурителни вноски</b>“.
 * </ul>
 *
 * <p><b>⚠️ И ЗАЩО Т. 14/15 НЕ СЕ ПИПАТ.</b> Прекъсването по <b>КСО чл. 10 ал. 2</b> се строи от
 * {@code countsAsInsuranceService}, не от кофата — а режим 3 се зачита за стаж. Заведен с празна
 * кофа и „не се зачита“ (прочитът, описан в {@code BgD1DayBuckets.UNBUCKETED}), той щеше да нареже
 * месеца на отрязъци при всяко явяване в съд. Парите са ЕДНАКВИ в двата прочита; т. 14/15 не са.
 */
class BgPaid157DeclarationDaysTest {

  private static final LocalDate DEC_FROM = LocalDate.of(2026, 12, 1);
  private static final LocalDate DEC_TO = LocalDate.of(2026, 12, 31);

  /** Декември 2026 — 22 работни дни, всичките зачетени на фиша. */
  private static final int DEC_WORKING_DAYS = 22;

  // ═══ ЧЕТЕНЕТО ОТ ЗАМРАЗЕНИТЕ ДНИ (#218) ══════════════════════════════════

  @Test
  @DisplayName("⚠️ РЕЖИМ 3 · три дни в съд → т. 16.4 = 3, т. 16.1 = 19, и НУЛА прекъсвания")
  void theSpecialLawRegimeFillsPoint164AndLeavesPoint161AsTheRemainder() {
    BgPaySlip slip = frozenSlip(new BigDecimal("3.00"), BigDecimal.ZERO);
    BgD1DaySource.Outcome out =
        read(slip, row("2026-12-07", "2026-12-09", "3", "16.4", "Отпуск чл.157 ал.1 т.4", true));

    Map<String, Integer> byBucket = BgD1DayBuckets.sum(out.entries);
    assertEquals(
        3,
        byBucket.get(BgD1DayBuckets.B_16_4).intValue(),
        "трите дни идват от ЗАМРАЗЕНОТО поле на фиша, не от втора сметка над заявките (#218)");
    assertEquals(0, byBucket.get(BgD1DayBuckets.B_16_1).intValue(), "нула от заявката в т. 16.1");
    assertEquals(
        0,
        out.outsideInsuranceDays,
        "⚠️ И НУЛА ИЗВЪН ОСИГУРЯВАНЕТО: кофата е непразна, тоест предикатът outsideInsurance не се"
            + " задейства и дните НЕ се вадят втори път");
    assertTrue(
        out.interruptions.isEmpty(),
        "⚠️ Т. 14/15 НЕ СЕ ПИПАТ. Прекъсването по КСО чл. 10 ал. 2 иска"
            + " countsAsInsuranceService = false, а режим 3 се зачита за стаж. Намерено: "
            + out.interruptions);
    assertTrue(
        out.problems.isEmpty(), "кофа 16.4 + „зачита се“ е съгласуваната двойка: " + out.problems);

    BgD1Record record = fill(slip, byBucket, out.outsideInsuranceDays);
    assertEquals("3", record.getT164NoContributionDays(), "указание т. 16.4");
    assertEquals(
        "19",
        record.getT161WorkedDays(),
        "⚠️ ОСТАТЪКЪТ: 22 отработени − 3 в т. 16.4 = 19. Дотук същите три дни стояха ВЪТРЕ в"
            + " т. 16.1 — декларация, която обявява дни с осигурителни вноски, за които вноски"
            + " няма.");
    assertEquals("22", record.getT16Days(), "заб. 1: сборът на кофите пълни позиции 1 и 2");
  }

  @Test
  @DisplayName("⚠️ РЕЖИМ 1 и 2 · същите три дни остават в т. 16.1 — работодателят плаща")
  void theEmployerPaidRegimesStayInPoint161() {
    BgPaySlip slip = frozenSlip(BigDecimal.ZERO, new BigDecimal("3.00"));
    BgD1DaySource.Outcome out =
        read(slip, row("2026-12-07", "2026-12-09", "3", "16.1", "Отпуск чл.157 ал.1 т.5а", true));

    Map<String, Integer> byBucket = BgD1DayBuckets.sum(out.entries);
    assertEquals(3, byBucket.get(BgD1DayBuckets.B_16_1).intValue());
    assertEquals(0, byBucket.get(BgD1DayBuckets.B_16_4).intValue());

    BgD1Record record = fill(slip, byBucket, out.outsideInsuranceDays);
    assertEquals(
        "22",
        record.getT161WorkedDays(),
        "⚠️ И ТОВА Е ВЯРНО: дните в платен отпуск СА дни с осигурителни вноски и НЕ се вадят от"
            + " остатъка. Между режим 1 и режим 2 се мени ОСНОВАТА по т. 21, не броят дни.");
    assertEquals("0", record.getT164NoContributionDays());
    assertEquals("22", record.getT16Days());
  }

  @Test
  @DisplayName("⚠️ И СМЕСЕНИЯТ МЕСЕЦ · 2 дни по т. 5а + 3 дни в съд → 16.1 = 19, 16.4 = 3")
  void aMonthWithTwoOfTheThreeRegimesSplitsBetweenTwoPoints() {
    BgPaySlip slip = frozenSlip(new BigDecimal("3.00"), new BigDecimal("2.00"));
    BgD1DaySource.Outcome out =
        read(
            slip,
            row("2026-12-07", "2026-12-08", "2", "16.1", "Отпуск чл.157 ал.1 т.5а", true),
            row("2026-12-09", "2026-12-11", "3", "16.4", "Отпуск чл.157 ал.1 т.4", true));

    Map<String, Integer> byBucket = BgD1DayBuckets.sum(out.entries);
    BgD1Record record = fill(slip, byBucket, out.outsideInsuranceDays);
    assertEquals("19", record.getT161WorkedDays(), "22 − 3; двата дни по т. 5а СА в остатъка");
    assertEquals("3", record.getT164NoContributionDays());
    assertEquals(
        "22",
        record.getT16Days(),
        "⚠️ И СБОРЪТ СИ ОСТАВА МЕСЕЦЪТ. Точно тук един ред за трите режима беше неразличим от"
            + " верния: сборът 22 е ВЕРЕН и при сгрешено разпределение по точки.");
  }

  // ═══ ФИКСТУРАТА ══════════════════════════════════════════════════════════

  /** Фиш със ЗАМРАЗЕНИ дни по кофа — точно каквото {@code freezeLeaveDays} записва. */
  private static BgPaySlip frozenSlip(BigDecimal days164, BigDecimal days161) {
    BgPaySlip slip = new BgPaySlip();
    slip.setBgWorkedDays(DEC_WORKING_DAYS);
    slip.setBgWorkingDaysInPeriod(DEC_WORKING_DAYS);
    slip.setBgLeaveDays161(days161);
    slip.setBgLeaveDays162(BigDecimal.ZERO);
    slip.setBgLeaveDays163(BigDecimal.ZERO);
    slip.setBgLeaveDays164(days164);
    slip.setBgLeaveDays165(BigDecimal.ZERO);
    slip.setBgLeaveDays16A(BigDecimal.ZERO);
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
    // ⚠️ ПИТА СЕ ЗА НАХОДКИ ПО Т. 16, А НЕ ЗА ПРАЗЕН СПИСЪК. Фикстурата не зарежда часовете по
    // позиции 3 и 4 (те са предмет на #218-часовия път, не на този файл) и точно за тях строителят
    // обявява липса — законно. Двете находки, които този файл НЕ бива да вижда, са другите:
    // отрицателен остатък в т. 16.1 и сбор над работните дни на месеца.
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

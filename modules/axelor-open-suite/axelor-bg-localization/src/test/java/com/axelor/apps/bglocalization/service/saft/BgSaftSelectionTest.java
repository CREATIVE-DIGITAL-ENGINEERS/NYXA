package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.service.saft.dto.SaftFileKind;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.repo.SaftReportRepository;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Видът и периодът на файла — месец, календарна година, произволни дати. */
class BgSaftSelectionTest {

  private static Period period(LocalDate from, LocalDate to) {
    Period period = new Period();
    period.setFromDate(from);
    period.setToDate(to);
    return period;
  }

  @Test
  @DisplayName("Месечният е един календарен месец — през периоди")
  void месечният() {
    BgSaftSelection s =
        BgSaftSelection.monthly(period(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)));
    assertEquals(SaftFileKind.MONTHLY, s.getKind());
    assertTrue(s.byPeriods());
    assertEquals(LocalDate.of(2026, 4, 1), s.getFromDate());
    assertEquals(LocalDate.of(2026, 4, 30), s.getToDate());
  }

  @Test
  @DisplayName("Месечен период извън един календарен месец няма вярно име — отказ")
  void месечният_извън_месеца() {
    assertThrows(
        IllegalArgumentException.class,
        () -> BgSaftSelection.monthly(period(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BgSaftSelection.monthly(period(LocalDate.of(2025, 12, 1), LocalDate.of(2026, 12, 31))));
    assertThrows(
        IllegalArgumentException.class,
        () -> BgSaftSelection.monthly(period(LocalDate.of(2026, 4, 30), LocalDate.of(2026, 4, 1))));
    assertThrows(IllegalArgumentException.class, () -> BgSaftSelection.monthly(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> BgSaftSelection.monthly(period(LocalDate.of(2026, 4, 1), null)));
  }

  @Test
  @DisplayName("Годишният е цялата календарна година — няма как на части (QA с. 21)")
  void годишният() {
    BgSaftSelection s = BgSaftSelection.annual(2026);
    assertEquals(SaftFileKind.ANNUAL, s.getKind());
    assertTrue(s.byPeriods());
    assertEquals(LocalDate.of(2026, 1, 1), s.getFromDate());
    assertEquals(LocalDate.of(2026, 12, 31), s.getToDate());
    assertThrows(IllegalArgumentException.class, () -> BgSaftSelection.annual(26));
    assertThrows(IllegalArgumentException.class, () -> BgSaftSelection.annual(20260));
  }

  @Test
  @DisplayName("При поискване: произволни дати, но краят не е преди началото")
  void при_поискване() {
    BgSaftSelection s =
        BgSaftSelection.onDemand(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 15));
    assertEquals(SaftFileKind.ON_DEMAND, s.getKind());
    assertFalse(s.byPeriods());
    BgSaftSelection oneDay =
        BgSaftSelection.onDemand(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1));
    assertEquals(oneDay.getFromDate(), oneDay.getToDate());
    assertThrows(
        IllegalArgumentException.class,
        () -> BgSaftSelection.onDemand(LocalDate.of(2026, 7, 15), LocalDate.of(2026, 7, 1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> BgSaftSelection.onDemand(null, LocalDate.of(2026, 7, 1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> BgSaftSelection.onDemand(LocalDate.of(2026, 7, 1), null));
  }

  // ─────────────── от отчета (T1) ───────────────

  private static SaftReport report(String type) {
    SaftReport report = new SaftReport();
    report.setReportTypeSelect(type);
    return report;
  }

  @Test
  @DisplayName(
      "От отчета по вида: месечният — от месеца, годишният — от годината, при поискване — от"
          + " датите; останалите полета не се гледат")
  void от_отчета() {
    SaftReport monthly = report(SaftReportRepository.REPORT_TYPE_MONTHLY);
    monthly.setPeriod(period(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)));
    monthly.setReportYear(2025);
    BgSaftSelection m = BgSaftSelection.of(monthly);
    assertEquals(SaftFileKind.MONTHLY, m.getKind());
    assertEquals(LocalDate.of(2026, 4, 1), m.getFromDate());

    SaftReport annual = report(SaftReportRepository.REPORT_TYPE_ANNUAL);
    annual.setReportYear(2026);
    annual.setPeriod(monthly.getPeriod());
    BgSaftSelection a = BgSaftSelection.of(annual);
    assertEquals(SaftFileKind.ANNUAL, a.getKind());
    assertEquals(LocalDate.of(2026, 1, 1), a.getFromDate());
    assertEquals(LocalDate.of(2026, 12, 31), a.getToDate());

    SaftReport onDemand = report(SaftReportRepository.REPORT_TYPE_ON_DEMAND);
    onDemand.setFromDate(LocalDate.of(2026, 7, 1));
    onDemand.setToDate(LocalDate.of(2026, 7, 15));
    onDemand.setReportYear(2026);
    BgSaftSelection d = BgSaftSelection.of(onDemand);
    assertEquals(SaftFileKind.ON_DEMAND, d.getKind());
    assertEquals(LocalDate.of(2026, 7, 1), d.getFromDate());
    assertEquals(LocalDate.of(2026, 7, 15), d.getToDate());
  }

  @Test
  @DisplayName("От отчета: без година, без дати, без месец или с непознат вид — отказ")
  void от_отчета_отказите() {
    assertThrows(IllegalArgumentException.class, () -> BgSaftSelection.of(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> BgSaftSelection.of(report(SaftReportRepository.REPORT_TYPE_ANNUAL)));
    assertThrows(
        IllegalArgumentException.class,
        () -> BgSaftSelection.of(report(SaftReportRepository.REPORT_TYPE_ON_DEMAND)));
    assertThrows(
        IllegalArgumentException.class,
        () -> BgSaftSelection.of(report(SaftReportRepository.REPORT_TYPE_MONTHLY)));
    assertThrows(IllegalArgumentException.class, () -> BgSaftSelection.of(report("weekly")));
  }
}

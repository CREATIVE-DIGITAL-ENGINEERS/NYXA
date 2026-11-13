package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.db.repo.SaftReportRepository;
import com.axelor.apps.napsubmissions.services.saft.SaftPeriodLock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * КЛЮЧЪТ НА ЗАКЛЮЧВАНЕТО ПРИ ПОДАВАНЕ ПО ВИДА (T1), без база — истинският текст на условието,
 * оценен от {@link BgSaftJpqlWhere} върху подаване в паметта.
 *
 * <p>Годишният: една календарна година — един файл (QA II.4.5, с. 21). При поискване: искането и
 * ПРИПОКРИВАЩИ СЕ дати — едно искане може да е на два файла за два подпериода (QA II.4.5, с.
 * 20–21), ново искане е нов файл.
 */
class BgSaftPeriodLockKeyTest {

  private static SaftReport annual(Integer year) {
    SaftReport r = new SaftReport();
    r.setCompany(new Company());
    r.setReportTypeSelect(SaftReportRepository.REPORT_TYPE_ANNUAL);
    r.setReportYear(year);
    return r;
  }

  private static SaftReport onDemand(String request, LocalDate from, LocalDate to) {
    SaftReport r = new SaftReport();
    r.setCompany(new Company());
    r.setReportTypeSelect(SaftReportRepository.REPORT_TYPE_ON_DEMAND);
    r.setNapRequestNumber(request);
    r.setFromDate(from);
    r.setToDate(to);
    return r;
  }

  private static LocalDate july(int day) {
    return LocalDate.of(2026, 7, day);
  }

  /** Заключва ли подаденият отчет {@code filed} новия {@code report}: условието и искането. */
  private static boolean locks(SaftReport report, SaftReport filed) {
    SaftPeriodLock.Key key = SaftPeriodLock.Key.of(report);
    Map<String, Object> paths = new HashMap<>();
    paths.put("self.saftReport.reportTypeSelect", filed.getReportTypeSelect());
    paths.put("self.saftReport.reportYear", (long) filed.getReportYear());
    paths.put("self.saftReport.fromDate", filed.getFromDate());
    paths.put("self.saftReport.toDate", filed.getToDate());
    Map<String, Object> parameters = new HashMap<>(key.getParameters());
    parameters.computeIfPresent("year", (k, v) -> ((Integer) v).longValue());
    NapSubmission submission = new NapSubmission();
    submission.setSaftReport(filed);
    return BgSaftJpqlWhere.matches(key.getWhere(), parameters, paths::get)
        && key.sameRequest(submission);
  }

  @Test
  @DisplayName("Годишният: същата година заключва, друга — не; при поискване не заключва годишен")
  void годишният() {
    assertTrue(locks(annual(2025), annual(2025)));
    assertFalse(locks(annual(2025), annual(2026)));
    assertFalse(
        locks(
            annual(2026), onDemand("ИСК-1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))),
        "файл при поискване за цялата година не е годишният файл");
  }

  @Test
  @DisplayName(
      "При поискване: същото искане с припокриващи се дати заключва; другият подпериод, друго"
          + " искане и годишен — не")
  void при_поискване() {
    SaftReport firstHalf = onDemand("ИСК-1", july(1), july(15));
    assertTrue(locks(onDemand("ИСК-1", july(1), july(15)), firstHalf), "същото");
    assertTrue(locks(onDemand("ИСК-1", july(10), july(31)), firstHalf), "застъпване");
    assertTrue(locks(onDemand("ИСК-1", july(15), july(20)), firstHalf), "граничният ден е общ");
    assertFalse(
        locks(onDemand("ИСК-1", july(16), july(31)), firstHalf), "вторият подпериод (QA с. 20)");
    assertFalse(locks(onDemand("ИСК-2", july(1), july(15)), firstHalf), "ново искане");
    SaftReport year = annual(2026);
    assertFalse(locks(onDemand("ИСК-1", july(1), july(15)), year), "годишният не е искане");
  }

  @Test
  @DisplayName("Номерът на искането — без интервалите в краищата и без разлика в регистъра")
  void номерът() {
    SaftReport filed = onDemand("  isk-7 ", july(1), july(15));
    assertTrue(locks(onDemand("ISK-7", july(1), july(15)), filed));
    assertFalse(locks(onDemand("ISK-77", july(1), july(15)), filed));
  }

  @Test
  @DisplayName("Месечният пази месеца, както от G6b; без период, година, дати или искане ключ няма")
  void месечният_и_празните() {
    SaftReport monthly = new SaftReport();
    monthly.setCompany(new Company());
    monthly.setReportTypeSelect(SaftReportRepository.REPORT_TYPE_MONTHLY);
    Period period = new Period();
    monthly.setPeriod(period);
    SaftPeriodLock.Key key = SaftPeriodLock.Key.of(monthly);
    assertEquals(SaftPeriodLock.Key.MONTHLY, key.getWhere());
    assertSame(period, key.getParameters().get("period"));

    monthly.setPeriod(null);
    assertNull(SaftPeriodLock.Key.of(monthly));
    assertNull(SaftPeriodLock.Key.of(annual(null)));
    assertNull(SaftPeriodLock.Key.of(onDemand(" ", july(1), july(15))));
    assertNull(SaftPeriodLock.Key.of(onDemand("ИСК-1", null, july(15))));
    SaftReport noCompany = annual(2025);
    noCompany.setCompany(null);
    assertNull(SaftPeriodLock.Key.of(noCompany));
  }

  @Test
  @DisplayName("Отказът казва вида и периода: годината, датите с искането; месечният — както беше")
  void текстът() {
    NapSubmission annual = new NapSubmission();
    annual.setSaftReport(annual(2025));
    annual.setNapIncomingNumber("30TST000000301");
    annual.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_ACCEPTED);
    String a = SaftPeriodLock.refusal(annual);
    assertTrue(a.contains("Годишният файл за 2025") && a.contains("приет от НАП"), a);
    assertTrue(a.contains("30TST000000301"), a);

    NapSubmission demand = new NapSubmission();
    demand.setSaftReport(onDemand("ИСК-1", july(1), july(15)));
    demand.setNapIncomingNumber("30TST000000302");
    demand.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SUBMITTED);
    String d = SaftPeriodLock.refusal(demand);
    assertTrue(d.contains("01.07.2026 – 15.07.2026") && d.contains("ИСК-1"), d);
    assertTrue(d.contains("чака изход"), d);

    NapSubmission month = new NapSubmission();
    month.setNapIncomingNumber("30TST000000303");
    month.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_ACCEPTED);
    assertEquals(
        String.format(SaftPeriodLock.ACCEPTED_LOCKED, "30TST000000303"),
        SaftPeriodLock.refusal(month));
  }
}

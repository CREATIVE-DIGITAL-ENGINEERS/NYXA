package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXsd;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.repo.SaftReportRepository;
import com.axelor.apps.napsubmissions.services.saft.SaftCheckResult;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРАВИЛАТА НА ЕКРАНА НАД ГЕНЕРАТОРА (G6b, T1), без база: DAYBOOK → ГРЕШКА, версията по дата, какво
 * иска всеки вид. Генераторът е истинският — чистият април на {@link BgSaftComposeTest}.
 */
class BgSaftFileEngineTest {

  private static final LocalDate OCT_31 = LocalDate.of(2026, 10, 31);
  private static final LocalDate NOV_01 = LocalDate.of(2026, 11, 1);

  private static BgSaftGenerationService.Generated generated(
      LocalDate createdOn, List<BgSaftEnvironmentCheck.MoveRow> unposted) {
    BgSaftAuditFileService.Inputs in = BgSaftComposeTest.clean();
    in.createdOn = createdOn;
    in.unposted = unposted;
    return BgSaftGenerationService.generate(BgSaftAuditFileService.compose(in));
  }

  private static List<String> codes(SaftCheckResult result, String severity) {
    return result.getFindings().stream()
        .filter(f -> f.getSeverity().equals(severity))
        .map(f -> f.getCode() + "@" + f.getTargetId())
        .collect(Collectors.toList());
  }

  // ─────────────── DAYBOOK ───────────────

  @Test
  @DisplayName(
      "Статия в DAYBOOK в месеца: ГРЕШКА с линк към нея, файл няма; предупреждението на G6a за"
          + " нея отпада, черновата (NEW) остава ПРЕДУПРЕЖДЕНИЕ")
  void daybook_е_грешка() {
    BgSaftGenerationService.Generated g =
        generated(
            OCT_31,
            List.of(
                new BgSaftEnvironmentCheck.MoveRow(41L, "ДН/41", MoveRepository.STATUS_DAYBOOK),
                new BgSaftEnvironmentCheck.MoveRow(42L, "ЧР/42", MoveRepository.STATUS_NEW)));
    assertTrue(g.isGenerated(), "генераторът сам не спира — G6a ги дава като предупреждения");

    SaftCheckResult result =
        BgSaftFileEngine.result(g, List.of(new BgSaftDaybookCheck.Row(41L, "ДН/41")), OCT_31);

    assertTrue(result.isChecked());
    assertFalse(result.isGenerated(), "DAYBOOK спира файла");
    assertNull(result.getBytes());
    assertEquals(List.of("SAFT_DAYBOOK_MOVE@41"), codes(result, SaftCheckResult.SEVERITY_ERROR));
    SaftCheckResult.Finding error =
        result.getFindings().stream()
            .filter(SaftCheckResult.Finding::isError)
            .findFirst()
            .orElseThrow();
    assertEquals(BgSaftRef.MOVE, error.getTargetModel(), "линк към статията");
    assertTrue(error.getMessage().contains("ДН/41"), error.getMessage());
    List<String> warnings = codes(result, SaftCheckResult.SEVERITY_WARNING);
    assertTrue(warnings.contains("SAFT_ENV_UNPOSTED_MOVE@42"), "черновата: " + warnings);
    assertFalse(warnings.contains("SAFT_ENV_UNPOSTED_MOVE@41"), "DAYBOOK не е два пъти");
    assertEquals("1.0.2", result.getAuditFileVersion());
  }

  @Test
  @DisplayName("Без DAYBOOK — файлът, името по Прил. 3, SHA-256 на генератора")
  void без_daybook_има_файл() {
    BgSaftGenerationService.Generated g = generated(OCT_31, List.of());
    SaftCheckResult result = BgSaftFileEngine.result(g, List.of(), OCT_31);
    assertTrue(result.isGenerated(), result.getFindings().toString());
    assertEquals("123456789_04_2026.xml", result.getFileName());
    assertEquals(g.getSha256(), result.getSha256());
    assertEquals(BgSaftGenerationService.sha256(result.getBytes()), result.getSha256());
  }

  @Test
  @DisplayName("ГРЕШКА в генератора (ЕИК на клон) — файл няма и без DAYBOOK")
  void грешката_на_генератора() {
    BgSaftAuditFileService.Inputs in = BgSaftComposeTest.clean();
    in.company.getPartner().setRegistrationCode("1234567890001");
    SaftCheckResult result =
        BgSaftFileEngine.result(
            BgSaftGenerationService.generate(BgSaftAuditFileService.compose(in)),
            List.of(),
            OCT_31);
    assertFalse(result.isGenerated());
    assertTrue(
        codes(result, SaftCheckResult.SEVERITY_ERROR).stream()
            .anyMatch(c -> c.startsWith("SAFT_FILE_NAME_BRANCH_EIK")));
  }

  // ─────────────── версията по дата ───────────────

  @Test
  @DisplayName(
      "31.10.2026 → S.H.1 1.0.2 и XSD 1.0.2; 01.11.2026 → S.H.1 1.0.3 и XSD 1.0.3 (една таблица)")
  void версията_по_дата() {
    SaftCheckResult oct = BgSaftFileEngine.result(generated(OCT_31, List.of()), List.of(), OCT_31);
    SaftCheckResult nov = BgSaftFileEngine.result(generated(NOV_01, List.of()), List.of(), NOV_01);
    assertTrue(oct.isGenerated(), oct.getFindings().toString());
    assertTrue(nov.isGenerated(), nov.getFindings().toString());

    assertEquals("1.0.2", oct.getAuditFileVersion());
    assertEquals("1.0.2", BgSaftSubmissionVersion.auditFileVersion(oct.getBytes()));
    assertEquals("1.0.3", nov.getAuditFileVersion());
    assertEquals("1.0.3", BgSaftSubmissionVersion.auditFileVersion(nov.getBytes()));

    // И XSD-то е от същия ред на таблицата: файлът минава своята схема.
    assertEquals(BgSaftXsd.V1_0_2, BgSaftXsd.forDate(OCT_31));
    assertEquals(BgSaftXsd.V1_0_3, BgSaftXsd.forDate(NOV_01));
    assertEquals(List.of(), BgSaftFixtures.validate(oct.getBytes(), BgSaftXsd.V1_0_2));
    assertEquals(List.of(), BgSaftFixtures.validate(nov.getBytes(), BgSaftXsd.V1_0_3));
    assertFalse(oct.getSha256().equals(nov.getSha256()), "различни файлове");
  }

  // ─────────────── какво иска всеки вид (T1) ───────────────

  private static SaftReport report(String type) {
    SaftReport report = new SaftReport();
    report.setCompany(new Company());
    report.setReportTypeSelect(type);
    return report;
  }

  private static List<String> scope(SaftReport report) {
    return BgSaftFileEngine.scope(report).stream()
        .map(SaftCheckResult.Finding::getCode)
        .collect(Collectors.toList());
  }

  @Test
  @DisplayName("Месечният иска месеца; без него — ГРЕШКА")
  void месечният_иска_месеца() {
    SaftReport report = report(SaftReportRepository.REPORT_TYPE_MONTHLY);
    report.setPeriod(new Period());
    assertEquals(List.of(), scope(report));
    report.setPeriod(null);
    assertEquals(List.of(BgSaftFileEngine.NO_PERIOD), scope(report));
  }

  @Test
  @DisplayName("Годишният иска календарна година от четири цифри — месецът не му трябва")
  void годишният_иска_година() {
    SaftReport report = report(SaftReportRepository.REPORT_TYPE_ANNUAL);
    assertEquals(List.of(BgSaftFileEngine.NO_YEAR), scope(report));
    report.setReportYear(26);
    assertEquals(List.of(BgSaftFileEngine.NO_YEAR), scope(report));
    report.setReportYear(2026);
    assertEquals(List.of(), scope(report), "без period — годишният не е месец");
  }

  @Test
  @DisplayName("При поискване иска двете дати в ред и номера на искането (ключът на заключването)")
  void при_поискване_иска_дати_и_искане() {
    SaftReport report = report(SaftReportRepository.REPORT_TYPE_ON_DEMAND);
    assertEquals(List.of(BgSaftFileEngine.NO_DATES, BgSaftFileEngine.NO_REQUEST), scope(report));
    report.setFromDate(LocalDate.of(2026, 7, 15));
    report.setToDate(LocalDate.of(2026, 7, 1));
    report.setNapRequestNumber("  ");
    assertEquals(
        List.of(BgSaftFileEngine.DATES_REVERSED, BgSaftFileEngine.NO_REQUEST), scope(report));
    report.setFromDate(LocalDate.of(2026, 7, 1));
    report.setToDate(LocalDate.of(2026, 7, 1));
    report.setNapRequestNumber("ИСК-1");
    assertEquals(List.of(), scope(report), "един ден е период");
  }

  @Test
  @DisplayName("Непознат вид и отчет без дружество — ГРЕШКА")
  void непознат_вид() {
    assertEquals(List.of(BgSaftFileEngine.UNKNOWN_KIND), scope(report("weekly")));
    SaftReport noCompany = report(SaftReportRepository.REPORT_TYPE_MONTHLY);
    noCompany.setCompany(null);
    noCompany.setPeriod(new Period());
    assertEquals(List.of(BgSaftFileEngine.NO_PERIOD), scope(noCompany));
  }

  // ─────────────── заявката ───────────────

  @Test
  @DisplayName("Заявката за DAYBOOK: само дружеството, само месецът, само статус DAYBOOK")
  void заявката() {
    String where = BgSaftJpqlWhere.where(BgSaftDaybookCheck.DAYBOOK_MOVES);
    Map<String, Object> parameters = new HashMap<>();
    parameters.put("companyId", 7L);
    parameters.put("fromDate", LocalDate.of(2026, 10, 1));
    parameters.put("toDate", LocalDate.of(2026, 10, 31));

    assertTrue(row(where, parameters, 7L, LocalDate.of(2026, 10, 15), 2), "DAYBOOK в месеца");
    assertTrue(row(where, parameters, 7L, LocalDate.of(2026, 10, 31), 2), "последният ден");
    assertFalse(row(where, parameters, 7L, LocalDate.of(2026, 10, 15), 3), "осчетоводена");
    assertFalse(row(where, parameters, 7L, LocalDate.of(2026, 10, 15), 1), "чернова");
    assertFalse(row(where, parameters, 8L, LocalDate.of(2026, 10, 15), 2), "друго дружество");
    assertFalse(row(where, parameters, 7L, LocalDate.of(2026, 11, 1), 2), "друг месец");
  }

  private static boolean row(
      String where, Map<String, Object> parameters, long company, LocalDate date, int status) {
    Map<String, Object> paths = new HashMap<>();
    paths.put("self.company.id", company);
    paths.put("self.date", date);
    paths.put("self.statusSelect", (long) status);
    return BgSaftJpqlWhere.matches(where, parameters, paths::get);
  }
}

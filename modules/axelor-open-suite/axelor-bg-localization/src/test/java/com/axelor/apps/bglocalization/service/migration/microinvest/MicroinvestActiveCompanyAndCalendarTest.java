package com.axelor.apps.bglocalization.service.migration.microinvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The importer targets the ACTIVE company and only it, creates the PAYROLL calendar its own data
 * needs, and finishes employees the same way the form's "Завършете" button does.
 *
 * <p>Pure logic only -- no Guice, no database, same house style as {@link
 * MicroinvestPilotImportServiceTest}. The database-touching halves are exercised by overriding the
 * two lookup seams on {@link MicroinvestPayrollCalendarService} with in-memory fakes, which is
 * enough to prove the DECISIONS (what is missing, what gets created, what is reused on a second
 * run); the queries themselves are ordinary repository filters.
 */
class MicroinvestActiveCompanyAndCalendarTest {

  // ═══ §A Active company is the only target, and no Company is ever created ═══

  @Test
  @DisplayName("source Bulstat and active EIK match across formatting differences -> proceed")
  void matchesBulstatIgnoringFormatting() {
    assertEquals(
        MicroinvestCompanyGuard.Verdict.MATCH,
        MicroinvestCompanyGuard.verdict("100000026", "BG 100000026"));
    assertEquals(
        MicroinvestCompanyGuard.Verdict.MATCH,
        MicroinvestCompanyGuard.verdict(" 100000026 ", "100000026"));
    assertEquals("100000026", MicroinvestCompanyGuard.normalizeBulstat("BG-100 000 026"));
    assertNull(MicroinvestCompanyGuard.normalizeBulstat("   "));
  }

  @Test
  @DisplayName(
      "a different Bulstat is a MISMATCH, named in full in the error -- never a name match")
  void blocksOnBulstatMismatch() {
    assertEquals(
        MicroinvestCompanyGuard.Verdict.MISMATCH,
        MicroinvestCompanyGuard.verdict("100000026", "131071587"));
    String message =
        MicroinvestCompanyGuard.problemMessage(
            MicroinvestCompanyGuard.Verdict.MISMATCH,
            "ТЕСТЕКС",
            "100000026",
            "ТЕСТЕКС ООД",
            "131071587");
    assertTrue(message.contains("ТЕСТЕКС"), message);
    assertTrue(message.contains("ТЕСТЕКС ООД"), message);
    assertTrue(message.contains("100000026"), message);
    assertTrue(message.contains("131071587"), message);
    assertTrue(message.contains("Нищо не е записано"), message);

    // The identical trade name must NOT be treated as a match: "ТЕСТЕКС" vs "ТЕСТЕКС ООД" would
    // otherwise produce a duplicate company.
    assertEquals(
        MicroinvestCompanyGuard.Verdict.MISMATCH,
        MicroinvestCompanyGuard.verdict("100000026", "206892598"));
  }

  @Test
  @DisplayName("no active company / no identifier on either side blocks too, with its own reason")
  void blocksWhenAnIdentifierIsMissing() {
    assertEquals(
        MicroinvestCompanyGuard.Verdict.NO_ACTIVE_COMPANY,
        MicroinvestCompanyGuard.verdict("100000026", "100000026", false));
    assertEquals(
        MicroinvestCompanyGuard.Verdict.NO_SOURCE_IDENTIFIER,
        MicroinvestCompanyGuard.verdict(null, "100000026"));
    assertEquals(
        MicroinvestCompanyGuard.Verdict.NO_ACTIVE_IDENTIFIER,
        MicroinvestCompanyGuard.verdict("100000026", null));
  }

  @Test
  @DisplayName("the warning banner names the active company and promises no new Company")
  void bannerNamesTheTargetCompany() {
    String banner = MicroinvestCompanyGuard.targetBanner("ТЕСТЕКС ООД", "100000026");
    assertTrue(banner.contains("ТЕСТЕКС ООД"), banner);
    assertTrue(banner.contains("100000026"), banner);
    assertTrue(banner.contains("Нова компания няма да бъде създадена"), banner);
  }

  /**
   * There is no find-or-create behaviour, not merely an unused one -- asserted against the source
   * because the failure mode it guards against (a second Company appearing beside the active one)
   * is invisible until it has already happened on a client instance.
   */
  @Test
  @DisplayName("the importer contains no code path that creates a Company or its Partner")
  void importerNeverCreatesACompany() throws IOException {
    String source =
        readMainSource("service/migration/microinvest/MicroinvestPilotImportService.java");
    assertFalse(source.contains("new Company()"), "importer must never instantiate a Company");
    assertFalse(source.contains("companyRepo"), "importer must not hold a CompanyRepository");
    assertFalse(source.contains("findOrCreateCompany"), "find-or-create must be gone entirely");
  }

  // ═══ §B PAYROLL years/periods are derived from the data, planned, and created idempotently ═══

  @Test
  @DisplayName("required months come from payslip/prior/Д1/Д6 dates, deduplicated")
  void derivesRequiredMonthsFromEveryHistoryKind() {
    MicroinvestSourceModels.ExportRoot export = exportWithHistory();
    Set<YearMonth> months = MicroinvestPayrollCalendarService.requiredMonths(export);
    assertEquals(
        new HashSet<>(
            List.of(
                YearMonth.of(2023, 11),
                YearMonth.of(2024, 1),
                YearMonth.of(2024, 7),
                YearMonth.of(2024, 12))),
        new HashSet<>(months));
  }

  @Test
  @DisplayName(
      "missing payroll years are planned once per calendar year, created on import, and REUSED"
          + " (not duplicated) on a second identical run")
  void createsMissingPayrollYearsIdempotently() throws AxelorException {
    Company company = new Company();
    company.setName("ТЕСТЕКС ООД");
    FakeCalendarService service = new FakeCalendarService();
    MicroinvestSourceModels.ExportRoot export = exportWithHistory();

    // ── first run: nothing exists, both calendar years are planned and created ──
    MicroinvestImportReport firstReport = new MicroinvestImportReport();
    MicroinvestPayrollCalendarService.Plan first = service.plan(company, export);
    assertEquals(List.of(2023, 2024), first.getYearsToCreate());
    assertEquals(4, first.getRequiredMonthCount());
    assertEquals(4, first.getMissingMonthCount());
    assertFalse(first.hasBlockers());

    // Preflight resolves a planned month to a SIMULATED period, so the payslips/Д1/Д6 of that
    // month are previewed as importable instead of skipped.
    Period simulated = first.resolve(LocalDate.of(2024, 7, 15));
    assertTrue(simulated != null && simulated.getId() == null, "planned month must simulate");

    service.apply(first, firstReport);
    assertEquals(List.of(2023, 2024), service.created);
    assertEquals(2, firstReport.created);
    assertEquals(0, firstReport.reused);

    // ── second identical run: the same plan finds nothing missing, creates nothing ──
    MicroinvestImportReport secondReport = new MicroinvestImportReport();
    MicroinvestPayrollCalendarService.Plan second = service.plan(company, export);
    assertEquals(List.of(), second.getYearsToCreate());
    assertEquals(0, second.getMissingMonthCount());
    service.apply(second, secondReport);
    assertEquals(List.of(2023, 2024), service.created, "no year may be created twice");
    assertEquals(0, secondReport.created);
  }

  @Test
  @DisplayName("a partial payroll year already on file blocks, it is never re-dated or replaced")
  void blocksRatherThanReworkingAnExistingPayrollYear() {
    Company company = new Company();
    FakeCalendarService service = new FakeCalendarService();
    Year partial = new Year();
    partial.setCode("2024-H1");
    partial.setFromDate(LocalDate.of(2024, 1, 1));
    partial.setToDate(LocalDate.of(2024, 6, 30));
    service.existingYears.add(partial);

    MicroinvestSourceModels.ExportRoot export = new MicroinvestSourceModels.ExportRoot();
    export.d6Values.add(d6(LocalDate.of(2024, 7, 1)));

    MicroinvestPayrollCalendarService.Plan plan = service.plan(company, export);
    assertTrue(plan.hasBlockers());
    assertEquals(List.of(), plan.getYearsToCreate());
    assertTrue(plan.getBlockedYears().get(2024).contains("2024-H1"));
    // A blocked month gets no simulated period either -- Preflight shows the same wall Import hits.
    assertNull(plan.resolve(LocalDate.of(2024, 7, 1)));
    assertTrue(service.created.isEmpty());
  }

  // ═══ §D Preflight/Import parity on a representative child record ═══

  /**
   * LeaveRequest stands in for the whole family (declarants, amendments, sick leave, Д1/Д6): if the
   * reuse lookup sat behind the dry-run early return, Preflight would announce rows as CREATED that
   * the Import that follows reports as REUSED. Asserted structurally -- the ordering of the two
   * statements IS the contract, and a unit test with mocked repositories would only re-describe it.
   */
  @Test
  @DisplayName("the reuse lookup runs before the dry-run branch, so Preflight predicts Import")
  void preflightUsesTheSameIdentityResolutionAsImport() throws IOException {
    String source =
        readMainSource("service/migration/microinvest/MicroinvestPilotImportService.java");
    int lookup = source.indexOf("LeaveRequest existing =");
    assertTrue(lookup > 0, "the LeaveRequest reuse lookup must still exist");
    int dryRunBranch = source.indexOf("if (dryRun) {", lookup);
    int reusedReport = source.indexOf("report.reused(label);", lookup);
    assertTrue(
        reusedReport > 0 && reusedReport < dryRunBranch,
        "REUSED must be decided before the dry-run branch, in both modes");

    // Same rule for the declarants: the whole createDeclarants body must not make its existence
    // check conditional on the mode.
    int start = source.indexOf("private void createDeclarants(");
    int end = source.indexOf("private String declarantCapacityOf(", start);
    assertTrue(start > 0 && end > start, "createDeclarants must still exist");
    assertFalse(
        source.substring(start, end).contains("!dryRun"),
        "the declarant reuse lookup must not be guarded by !dryRun");
  }

  // ═══ Helpers ═══

  private static MicroinvestSourceModels.ExportRoot exportWithHistory() {
    MicroinvestSourceModels.ExportRoot export = new MicroinvestSourceModels.ExportRoot();
    MicroinvestSourceModels.Employee employee = new MicroinvestSourceModels.Employee();
    employee.employeeId = 1;
    employee.payslipValues.add(formulaValue(LocalDate.of(2024, 1, 1)));
    employee.payslipValues.add(formulaValue(LocalDate.of(2024, 7, 1))); // same year, one Year row
    employee.priorPeriodValues.add(formulaValue(LocalDate.of(2023, 11, 1)));
    employee.d1Values.add(d1(LocalDate.of(2024, 1, 1))); // duplicate month, deduplicated
    export.employees.add(employee);
    export.d6Values.add(d6(LocalDate.of(2024, 12, 1)));
    return export;
  }

  private static MicroinvestSourceModels.FormulaValue formulaValue(LocalDate date) {
    MicroinvestSourceModels.FormulaValue v = new MicroinvestSourceModels.FormulaValue();
    v.payrollId = 100;
    v.date = date;
    v.formulaName = "Аванс";
    return v;
  }

  private static MicroinvestSourceModels.Dekl1FieldValue d1(LocalDate date) {
    MicroinvestSourceModels.Dekl1FieldValue v = new MicroinvestSourceModels.Dekl1FieldValue();
    v.payrollId = 100;
    v.date = date;
    return v;
  }

  private static MicroinvestSourceModels.Dekl6FieldValue d6(LocalDate date) {
    MicroinvestSourceModels.Dekl6FieldValue v = new MicroinvestSourceModels.Dekl6FieldValue();
    v.date = date;
    return v;
  }

  /**
   * Source text with comments removed. These assertions are about CODE, and this file's comments
   * quote the very constructs being asserted against (the old {@code !dryRun} guard, the old
   * find-or-create) precisely to explain why they are gone -- scanning the raw text would make
   * every such explanation a self-inflicted failure.
   */
  private static String readMainSource(String relativePath) throws IOException {
    Path path =
        Paths.get("src/main/java/com/axelor/apps/bglocalization").resolve(relativePath).normalize();
    assertTrue(Files.exists(path), "source not found: " + path.toAbsolutePath());
    String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
  }

  /**
   * In-memory stand-in for the three database seams: which PAYROLL years exist, which periods
   * exist, and what creating a year does. Everything else -- the planning, the deduplication, the
   * blocker rule, the re-verification -- is the real code under test.
   */
  private static final class FakeCalendarService extends MicroinvestPayrollCalendarService {

    private final List<Year> existingYears = new ArrayList<>();
    private final List<Integer> created = new ArrayList<>();

    @Override
    Period findPayrollPeriod(LocalDate date, Company company) {
      for (Year year : existingYears) {
        if (!date.isBefore(year.getFromDate()) && !date.isAfter(year.getToDate())) {
          Period period = new Period();
          period.setFromDate(date.withDayOfMonth(1));
          period.setToDate(date.withDayOfMonth(date.lengthOfMonth()));
          period.setYear(year);
          return period;
        }
      }
      return null;
    }

    @Override
    Year findOverlappingPayrollYear(int calendarYear, Company company) {
      for (Year year : existingYears) {
        if (!year.getFromDate().isAfter(LocalDate.of(calendarYear, 12, 31))
            && !year.getToDate().isBefore(LocalDate.of(calendarYear, 1, 1))) {
          return year;
        }
      }
      return null;
    }

    @Override
    Year createPayrollYear(Company company, int calendarYear) {
      created.add(calendarYear);
      Year year = new Year();
      year.setCode(String.valueOf(calendarYear));
      year.setFromDate(LocalDate.of(calendarYear, 1, 1));
      year.setToDate(LocalDate.of(calendarYear, 12, 31));
      existingYears.add(year);
      return year;
    }
  }
}

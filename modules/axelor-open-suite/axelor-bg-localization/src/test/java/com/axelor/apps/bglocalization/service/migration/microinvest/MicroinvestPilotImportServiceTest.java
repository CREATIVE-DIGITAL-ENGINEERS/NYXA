package com.axelor.apps.bglocalization.service.migration.microinvest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.City;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic pieces of the Microinvest pilot importer that don't need Guice/JPA: the BG name split
 * and the EGN-based sex derivation. No database, no Axelor test harness -- the rest (repository
 * writes) is exercised by running Preflight then Import from the wizard UI against one real pilot
 * company.
 */
class MicroinvestPilotImportServiceTest {

  @Test
  @DisplayName("three-part Bulgarian name splits into first/middle/last")
  void splitsThreePartName() {
    assertArrayEquals(
        new String[] {"Иван", "Георгиев", "Петров"},
        MicroinvestPilotImportService.splitBulgarianName("Иван Георгиев Петров"));
  }

  @Test
  @DisplayName("two-part name has no middle name")
  void splitsTwoPartName() {
    assertArrayEquals(
        new String[] {"Иван", null, "Петров"},
        MicroinvestPilotImportService.splitBulgarianName("Иван Петров"));
  }

  @Test
  @DisplayName("single-token name becomes the surname, not the first name")
  void singleTokenNameIsSurname() {
    assertArrayEquals(
        new String[] {null, null, "Петров"},
        MicroinvestPilotImportService.splitBulgarianName("Петров"));
  }

  @Test
  @DisplayName("extra whitespace between name parts is collapsed")
  void collapsesExtraWhitespace() {
    assertArrayEquals(
        new String[] {"Иван", "Георгиев", "Петров"},
        MicroinvestPilotImportService.splitBulgarianName("  Иван   Георгиев  Петров "));
  }

  @Test
  @DisplayName("EGN 9th digit even = female, odd = male")
  void derivesSexFromEgnParity() {
    // Synthetic EGNs (not real people): only the parity of the 9th digit matters here.
    org.junit.jupiter.api.Assertions.assertEquals(
        "M", MicroinvestPilotImportService.deriveSexFromEgn("7501011234")); // 9th digit '3' odd
    org.junit.jupiter.api.Assertions.assertEquals(
        "F", MicroinvestPilotImportService.deriveSexFromEgn("7501011224")); // 9th digit '2' even
  }

  @Test
  @DisplayName("a non-10-digit value is not guessed at")
  void doesNotGuessNonEgnValues() {
    assertNull(MicroinvestPilotImportService.deriveSexFromEgn(null));
    assertNull(MicroinvestPilotImportService.deriveSexFromEgn("123"));
    assertNull(MicroinvestPilotImportService.deriveSexFromEgn("ABCDEFGHIJ"));
  }

  /**
   * Parses a small, entirely synthetic export file (no real EGN/IBAN/address/name -- see the file
   * itself) through the same {@link MicroinvestExportFileParser} the wizard uses, and checks the
   * nested structure that matters for the mapping logic survives the JSON round-trip: the payroll
   * annex chain (two rows sharing one annexPayrollId), addresses/bank accounts nested under the
   * right employee, and the sick-leave event linked to its document detail by
   * absenseExportDetailId. This is the actual contract between the PowerShell export script and the
   * Java side -- exercising it here doesn't need a database or Guice.
   */
  @Test
  @DisplayName("the export file parses into the expected nested structure")
  void parsesSampleExportFile() throws IOException {
    byte[] content;
    try (InputStream in =
        getClass().getResourceAsStream("/microinvest/sample-company-export.json")) {
      content = in.readAllBytes();
    }
    MicroinvestSourceModels.ExportRoot root = MicroinvestExportFileParser.parse(content);

    assertEquals("ТЕСТОВА ФИРМА ЕООД", root.company.name);
    assertEquals("000000000", root.company.bulstat);

    assertEquals(2, root.officials.size());
    assertEquals("2", root.officials.get(0).positionIdentifier);

    assertEquals(2, root.employees.size());
    MicroinvestSourceModels.Employee first = root.employees.get(0);
    assertEquals(1, first.addresses.size());
    assertEquals("София", first.addresses.get(0).city);
    assertEquals(1, first.bankAccounts.size());
    assertEquals("BNBGBGSD", first.bankAccounts.get(0).bankBic);

    assertEquals(2, first.payrolls.size());
    assertEquals(100, (int) first.payrolls.get(0).annexPayrollId);
    assertEquals(100, (int) first.payrolls.get(1).annexPayrollId);
    assertEquals(LocalDate.of(2024, 6, 1), first.payrolls.get(1).fromDate);

    MicroinvestSourceModels.Employee second = root.employees.get(1);
    assertEquals(24, (int) second.declaredServiceMonths);
    assertTrue(second.addresses.isEmpty());

    assertEquals(1, root.hospitalEvents.size());
    MicroinvestSourceModels.LeaveEvent event = root.hospitalEvents.get(0);
    assertEquals(100, event.payrollId);
    assertEquals(1, (int) event.absenseExportDetailId);
    assertEquals(1, root.sickLeaveDetails.size());
    assertEquals(
        event.absenseExportDetailId.intValue(), root.sickLeaveDetails.get(0).absenseExportDetailId);

    // Phase 2 sections: payroll history nested under the employee, Dekl6 at the top level.
    assertEquals(2, first.payslipValues.size());
    assertEquals("Аванс", first.payslipValues.get(0).formulaName);
    assertEquals(0, new java.math.BigDecimal("200.00").compareTo(first.payslipValues.get(0).value));
    assertTrue(first.priorPeriodValues.isEmpty());
    assertEquals(2, first.d1Values.size());
    assertEquals("21", first.d1Values.get(0).formRefNo);

    assertEquals(3, root.d6Values.size());
    assertEquals("Наименование на осигурителя", root.d6Values.get(0).fieldName);
    assertEquals("ТЕСТОВА ФИРМА ЕООД", root.d6Values.get(0).section1);
  }

  // ═══ §1 Deleted-payroll chain resolution (MicroinvestPilotImportService.classifyChain) ═══

  private static MicroinvestSourceModels.Payroll payroll(int id, boolean deleted) {
    MicroinvestSourceModels.Payroll p = new MicroinvestSourceModels.Payroll();
    p.payrollId = id;
    p.isDeleted = deleted;
    return p;
  }

  @Test
  @DisplayName("root not deleted: proceed, regardless of any annex's own deleted flag")
  void proceedsWhenRootNotDeleted() {
    MicroinvestSourceModels.Payroll root = payroll(1, false);
    assertEquals(
        MicroinvestPilotImportService.ChainOutcome.PROCEED,
        MicroinvestPilotImportService.classifyChain(root, List.of(payroll(2, true))));
  }

  @Test
  @DisplayName("deleted root, no active annex: excluded, nothing to promote")
  void excludesWhenRootDeletedAndNoActiveAnnex() {
    MicroinvestSourceModels.Payroll root = payroll(1019, true);
    assertEquals(
        MicroinvestPilotImportService.ChainOutcome.EXCLUDED_DELETED,
        MicroinvestPilotImportService.classifyChain(root, List.of()));
    assertEquals(
        MicroinvestPilotImportService.ChainOutcome.EXCLUDED_DELETED,
        MicroinvestPilotImportService.classifyChain(root, List.of(payroll(1020, true))));
  }

  @Test
  @DisplayName("deleted root with an active annex: fail closed, whole chain skipped")
  void failsClosedWhenRootDeletedButAnnexActive() {
    MicroinvestSourceModels.Payroll root = payroll(1019, true);
    assertEquals(
        MicroinvestPilotImportService.ChainOutcome.FAIL_CLOSED,
        MicroinvestPilotImportService.classifyChain(
            root, List.of(payroll(1020, true), payroll(1021, false))));
  }

  // ═══ §2 City resolution order (MicroinvestPilotImportService.chooseCity) ═══

  private static City city(String name, String zip, String region, String municipality) {
    City c = new City();
    c.setName(name);
    c.setZip(zip);
    c.setRegion(region);
    c.setMunicipality(municipality);
    return c;
  }

  @Test
  @DisplayName("exact name+zip match wins outright")
  void resolvesExactNameAndZip() {
    City sofia1000 = city("София", "1000", "София-град", "Столична");
    assertEquals(
        sofia1000, MicroinvestPilotImportService.chooseCity(sofia1000, List.of(), null, null));
  }

  @Test
  @DisplayName("Sofia 1582/1592: no exact zip match, but a unique City name resolves it")
  void resolvesUniqueNameFallbackForSofiaPostcodes() {
    City sofia = city("София", "1000", "София-град", "Столична");
    // exact (name, zip) lookup found nothing for postcode 1582/1592 -- only the unique-name
    // candidate list is passed in, exactly as MicroinvestPilotImportService.resolveCity does.
    City resolved = MicroinvestPilotImportService.chooseCity(null, List.of(sofia), null, null);
    assertEquals(sofia, resolved);
  }

  @Test
  @DisplayName("duplicate city name narrowed to one by district/municipality")
  void narrowsDuplicateNameByMunicipality() {
    City plovdivCity = city("Куклен", "4101", "Пловдив", "Куклен");
    City otherCity = city("Куклен", "9999", "Друг регион", "Друга община");
    City resolved =
        MicroinvestPilotImportService.chooseCity(
            null, List.of(plovdivCity, otherCity), null, "Куклен");
    assertEquals(plovdivCity, resolved);
  }

  @Test
  @DisplayName("ambiguous duplicate name with no disambiguating district/municipality fails closed")
  void failsClosedOnAmbiguousDuplicateName() {
    City a = city("Александрово", "1111", "Регион А", "Община А");
    City b = city("Александрово", "2222", "Регион Б", "Община Б");
    assertNull(MicroinvestPilotImportService.chooseCity(null, List.of(a, b), null, null));
  }

  @Test
  @DisplayName("no candidate at all fails closed (unmatched)")
  void failsClosedOnNoCandidate() {
    assertNull(MicroinvestPilotImportService.chooseCity(null, List.of(), "some district", null));
  }

  // ═══ §3a Paid/unpaid leave-event mapping
  // (MicroinvestPilotImportService.confirmedLeaveReasonImportId) ═══

  @Test
  @DisplayName("paid annual leave maps to the confirmed LeaveReason importId")
  void mapsPaidAnnualLeave() {
    assertEquals(
        "lr_paid_annual",
        MicroinvestPilotImportService.confirmedLeaveReasonImportId("Платен годишен отпуск"));
  }

  @Test
  @DisplayName(
      "the specific unpaid-leave-without-insurance-service type maps to its confirmed reason")
  void mapsUnpaidLeaveWithoutInsuranceService() {
    assertEquals(
        "lr_unpaid_own_gt30",
        MicroinvestPilotImportService.confirmedLeaveReasonImportId(
            "Неплатен отпуск без осигурителен стаж от осигурен"));
  }

  @Test
  @DisplayName("an unrecognized leave-event type name is not guessed")
  void doesNotGuessUnknownLeaveEventType() {
    assertNull(MicroinvestPilotImportService.confirmedLeaveReasonImportId("Друг вид отпуск"));
    assertNull(MicroinvestPilotImportService.confirmedLeaveReasonImportId(null));
  }

  @Test
  @DisplayName(
      "attach() never touches a transient (not-yet-persisted) entity -- the Preflight-only"
          + " simulated EmploymentContract must be returned unchanged, not resolved against the DB")
  void attachPassesThroughATransientEntityUnchanged() {
    City transientCity = new City(); // getId() == null, same shape as the simulated preflight
    // EmploymentContract in importContracts -- neither is ever saved.
    assertTrue(transientCity == MicroinvestPilotImportService.attach(City.class, transientCity));
    assertNull(MicroinvestPilotImportService.attach(City.class, null));
  }
}

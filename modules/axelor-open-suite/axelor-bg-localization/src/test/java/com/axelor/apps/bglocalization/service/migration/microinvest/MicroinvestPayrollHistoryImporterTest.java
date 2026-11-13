package com.axelor.apps.bglocalization.service.migration.microinvest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic salary/D1/D6 coverage tallying -- no database, no Guice (the injected repo fields are
 * never touched by {@link MicroinvestPayrollHistoryImporter#tallySalaryAndDeclarationCoverage}
 * itself, only by the entity-creation methods this test doesn't call).
 */
class MicroinvestPayrollHistoryImporterTest {

  private static MicroinvestSourceModels.FormulaValue formulaValue(String name, String value) {
    MicroinvestSourceModels.FormulaValue v = new MicroinvestSourceModels.FormulaValue();
    v.formulaName = name;
    v.value = new BigDecimal(value);
    return v;
  }

  @Test
  @DisplayName(
      "a name consumed only as a BgPaySlip header field (not a line) is reported mapped, not"
          + " unmapped")
  void headerOnlyNameIsNotReportedUnmapped() {
    MicroinvestSourceModels.Employee emp = new MicroinvestSourceModels.Employee();
    // "Общо начисления" has no formula-line-mapping.csv entry -- it is consumed exclusively via
    // MicroinvestPayrollHistoryImporter.HEADER_TOTAL_SETTERS (BgPaySlip::setBgGrossTotal).
    emp.payslipValues.add(formulaValue("Общо начисления", "700.00"));
    // A name with neither a CSV line mapping nor a header setter: genuinely unmapped.
    emp.payslipValues.add(formulaValue("Съвсем непознато перо", "12.34"));

    MicroinvestSourceModels.ExportRoot export = new MicroinvestSourceModels.ExportRoot();
    export.employees.add(emp);

    MicroinvestImportReport report = new MicroinvestImportReport();
    new MicroinvestPayrollHistoryImporter().tallySalaryAndDeclarationCoverage(export, report);

    assertEquals(2, report.salaryRowsExported);
    assertEquals(1, report.salaryRowsMapped);
    assertEquals(1, report.salaryRowsUnmapped);
    assertEquals(0, new BigDecimal("700.00").compareTo(report.getMappedValueTotal()));
  }

  @Test
  @DisplayName("a CSV-mapped name is reported mapped with its value totalled")
  void csvMappedNameIsReportedMapped() {
    MicroinvestSourceModels.Employee emp = new MicroinvestSourceModels.Employee();
    emp.payslipValues.add(formulaValue("Аванс", "200.00")); // pl_avans, in formula-line-mapping.csv

    MicroinvestSourceModels.ExportRoot export = new MicroinvestSourceModels.ExportRoot();
    export.employees.add(emp);

    MicroinvestImportReport report = new MicroinvestImportReport();
    new MicroinvestPayrollHistoryImporter().tallySalaryAndDeclarationCoverage(export, report);

    assertEquals(1, report.salaryRowsMapped);
    assertEquals(0, report.salaryRowsUnmapped);
  }

  @Test
  @DisplayName(
      "a value with more than 2 decimals is rounded HALF_UP to BgPaySlipLine.bgAmount's own"
          + " @Digits(integer=18, fraction=2) scale before it would ever reach persistence")
  void normalizeMonetaryRoundsToTwoDecimals() {
    assertEquals(
        0,
        new BigDecimal("31.83")
            .compareTo(
                MicroinvestPayrollHistoryImporter.normalizeMonetary(new BigDecimal("31.825"))));
    assertEquals(
        0,
        new BigDecimal("369.69")
            .compareTo(
                MicroinvestPayrollHistoryImporter.normalizeMonetary(new BigDecimal("369.693387"))));
    // Already-2dp values pass through unchanged -- this is a safety normalization, not a recompute.
    assertEquals(
        0,
        new BigDecimal("700.00")
            .compareTo(
                MicroinvestPayrollHistoryImporter.normalizeMonetary(new BigDecimal("700.00"))));
  }
}

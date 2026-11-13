package com.axelor.apps.bglocalization.service.migration.microinvest;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain data holders matching the JSON export produced by the Microinvest export tool for one
 * Microinvest TRZ Pro company. Field names mirror the source SQL Server column names (not Nyxa's),
 * so the mapping/transform logic in {@link MicroinvestPilotImportService} is the only place that
 * translates between the two vocabularies.
 *
 * <p>Fields are public and default-constructed so Jackson can populate them directly from the
 * export file -- these are pure data, nothing here is ever written back to Microinvest (there is no
 * live connection to Microinvest at all; see {@code migration-plan.md} for why).
 */
final class MicroinvestSourceModels {

  private MicroinvestSourceModels() {}

  /** The whole export file for one company. */
  static final class ExportRoot {
    /**
     * Export format version. Absent (0) means version 2 or older -- a file produced before {@code
     * payrolls[].baseSalaryHistory}/{@code baseSalaryCurrent}/{@code nkpdCodes} existed. The
     * additions are purely additive, so an old file still parses; this number lets a reader that
     * NEEDS them say so instead of silently importing nothing.
     */
    public int exportFormatVersion;

    /**
     * {@code FormulaSalary.FormulaSalaryID} the base-salary rows were read from, resolved once at
     * export time from the formula name. Carried so the Java side never string-matches a translated
     * name.
     */
    public Integer baseSalaryFormulaId;

    /** {@code FormulaSalaryID} for „Работно време в часове на ден“. */
    public Integer hoursPerDayFormulaId;

    /** {@code FormulaSalaryID} for „Законоустановено работно време в часове на ден“ (240). */
    public Integer legalHoursPerDayFormulaId;

    /** {@code FormulaSalaryID} for „Категория труд“. */
    public Integer laborCategoryFormulaId;

    public Company company;
    public List<Official> officials = new ArrayList<>();
    public List<ContractReason> contractReasons = new ArrayList<>();
    public List<Employee> employees = new ArrayList<>();
    public List<LeaveEvent> hospitalEvents = new ArrayList<>();
    public List<SickLeaveDetail> sickLeaveDetails = new ArrayList<>();

    /**
     * Non-hospital leave events (paid/unpaid annual leave), {@code Events} where {@code
     * EnumEventTypes.IsHospital = 0 AND IsVacation = 1}. Kept separate from {@code hospitalEvents}
     * so the sick-leave import path is untouched by this addition.
     */
    public List<LeaveEvent> leaveEvents = new ArrayList<>();

    /**
     * Phase 2: Декларация обр. № 6 monthly company-level values ({@code Dekl6Value} joined to
     * {@code Dekl6} for the field name) -- company-level, not nested under an employee.
     */
    public List<Dekl6FieldValue> d6Values = new ArrayList<>();
  }

  static final class Company {
    public String name;
    public String bulstat;
    public String vatId;
    public String mol;
    public String ekatteCode;
  }

  static final class Official {
    public int officialId;
    public int employeeId;
    public int positionId;
    public String positionName;
    public String positionIdentifier;
  }

  static final class Employee {
    public int employeeId;
    public String name;
    public String egn;
    public boolean isForeigner;
    public LocalDate birthDate;
    public String birthPlace;
    public String idNumber;
    public LocalDate idIssueDate;
    public String idIssuedFrom;
    public String workPhone;
    public String phone;
    public String email;
    public boolean isDeleted;
    public String number;

    public List<Address> addresses = new ArrayList<>();
    public List<BankAccount> bankAccounts = new ArrayList<>();
    public List<Payroll> payrolls = new ArrayList<>();

    /** Aggregated from per.EmploymentRelationships at export time, if that table exists. */
    public Integer declaredServiceMonths;

    /**
     * Phase 2: {@code ValueSalary} rows for this employee's payrolls, joined to {@code
     * FormulaSalary} for the component name -- already-calculated monthly payslip results, zero
     * values excluded at export time. See {@code migration-plan.md} Phase 2/3.
     */
    public List<FormulaValue> payslipValues = new ArrayList<>();

    /**
     * Phase 2: {@code ValueSalaryPrior} rows, same shape as {@link #payslipValues} -- Microinvest's
     * own mid-year-onboarding/carry-forward snapshot. Empty for a company that was never onboarded
     * mid-year in Microinvest itself.
     */
    public List<FormulaValue> priorPeriodValues = new ArrayList<>();

    /**
     * Phase 2: {@code Dekl1Value} rows for this employee's payrolls, joined to {@code Dekl1} for
     * the field name/form reference number.
     */
    public List<Dekl1FieldValue> d1Values = new ArrayList<>();
  }

  /**
   * One {@code ValueSalary}/{@code ValueSalaryPrior} row: a single payroll-formula component's
   * value for one employment record for one month. {@code formulaName} is {@code
   * FormulaSalary.Name} joined at export time -- see {@link MicroinvestPayrollHistoryImporter} for
   * why name, not the opaque {@code FormulaSalaryID}/{@code Identifier}, is the matching key.
   */
  static final class FormulaValue {
    public int payrollId;
    public LocalDate date;
    public String formulaName;
    public java.math.BigDecimal value;
  }

  /**
   * One {@code Dekl1Value} row joined to {@code Dekl1} for the field's name and official form
   * reference number ({@code FormRefNo}, e.g. "21", "22a", "16.7") -- see {@code
   * MicroinvestD1FieldMapping} for the (name, formRefNo) -&gt; {@code BgD1Record} field mapping.
   * {@code value} is kept as text: Dekl1 mixes percentages, day-count strings with significant
   * leading zeros, and plain amounts in the same generic column.
   */
  static final class Dekl1FieldValue {
    public int payrollId;
    public LocalDate date;
    public String fieldName;
    public String formRefNo;
    public String value;
  }

  /**
   * One {@code Dekl6Value} row joined to {@code Dekl6} for the field name -- company-level, one row
   * per field per month, carrying Microinvest's up-to-four parallel "section" values for that field
   * (see {@code source-data-inventory.md} §9 and {@code MicroinvestPayrollHistoryImporter} for why
   * they are merged into one {@code BgD6Declaration} per period).
   */
  static final class Dekl6FieldValue {
    public LocalDate date;
    public String fieldName;
    public String section1;
    public String section2;
    public String section3;
    public String section4;
  }

  static final class Address {
    public String city;
    public String postalCode;
    public String street;
    public String district;
    public String municipality;
  }

  static final class BankAccount {
    public String bankBic;
    public String iban;
  }

  static final class ContractReason {
    public int contractReasonId;
    public String identifier;
    public String description;
  }

  static final class Payroll {
    public int payrollId;
    public int employeeId;
    public Integer annexPayrollId; // chain root; equals payrollId for the root row itself
    public int contractId;
    public String contractName;
    public Integer contractReasonId;
    public String contractNumber;
    public LocalDate contractDate;
    public LocalDate fromDate;
    public LocalDate toDate;
    public LocalDate contrractEndDate; // sic — source column name typo, kept for traceability
    public LocalDate contrractTermDate;
    public Integer positionId;
    public String positionName;
    public String ekatteCode;
    public boolean isDeleted;

    /**
     * CONTRACTUAL base monthly salary, effective-dated, from {@code VariablesHistory} joined to
     * {@code FormulaSalaryHistory} for {@code FormulaSalaryID = ExportRoot.baseSalaryFormulaId}.
     *
     * <p><b>⚠️ NOT a payslip line.</b> {@code ValueSalary} carries what was PAID for a month --
     * already reduced by partial months, unpaid leave and sick leave. This is the formula engine's
     * INPUT, i.e. the figure written in the contract. Ordered by date at export time.
     *
     * <p>⚠️ Microinvest carries a row forward for every month even after a contract has ended, so
     * "the newest row" is NOT the contractual salary of an ended contract -- selection must be by
     * effective date against the contract's own period. See {@link
     * MicroinvestContractPayrollFields}.
     */
    public List<BaseSalaryPoint> baseSalaryHistory = new ArrayList<>();

    /** Current scalar from {@code Variables}, for cross-checking the newest history row. */
    public java.math.BigDecimal baseSalaryCurrent;

    /** NKPD assignments from {@code PayrollKPDs} joined to {@code KPDs}, newest version last. */
    public List<NkpdAssignment> nkpdCodes = new ArrayList<>();

    /**
     * CONTRACTED hours per day, effective-dated, same source pair as the salary.
     *
     * <p><b>⚠️ Without it no 5xx line is produced at all.</b> НЕВД чл. 1 prorates the insurance
     * floor by DAYS under al. 3 (full time) and by HOURS under al. 4 (part time); the two give
     * different numbers for the same month, so {@code BgContributionResolver} refuses rather than
     * pick a threshold by default.
     */
    public List<BaseSalaryPoint> hoursPerDayHistory = new ArrayList<>();

    /** STATUTORY hours per day for the position (Д1 т. 16 поз. 3), same source pair. */
    public List<BaseSalaryPoint> legalHoursPerDayHistory = new ArrayList<>();

    /**
     * Категория труд (I/II/III под КСО), same source pair.
     *
     * <p><b>⚠️ Rates are found by the PAIR (insuredTypeCode, laborCategory).</b> With the category
     * empty the lookup returns nothing and the engine refuses with NO_CONTRIBUTIONS_FOUND -- an
     * insured-type code on its own selects no row. Lands on {@code Employee.bgLaborCategory}, not
     * on the contract.
     */
    public List<BaseSalaryPoint> laborCategoryHistory = new ArrayList<>();
  }

  /** One effective-dated contractual base-salary value for one employment record. */
  static final class BaseSalaryPoint {
    public LocalDate date;
    public java.math.BigDecimal value;
  }

  /**
   * One {@code PayrollKPDs} row resolved against {@code KPDs}.
   *
   * <p><b>⚠️ {@link #fullCode} IS the official NKPD code, not a manufactured one.</b> Microinvest
   * stores it in two {@code nvarchar(4)} halves -- {@code Section} (the unit group, e.g. 5120) and
   * {@code Code} (the position inside it, e.g. 2001) -- and their concatenation is what the
   * nomenclature calls 51202001. Both halves travel separately so the importer can show its work
   * and still refuse anything that does not match {@code BgNkpd} exactly.
   */
  static final class NkpdAssignment {
    public int payrollKpdId;
    public int kpdId;
    public int kpdVersionId;
    public String section;
    public String code;
    public String fullCode;
    public String description;
  }

  static final class LeaveEvent {
    public int eventId;
    public int payrollId;
    public int enumEventTypeId;
    public String eventTypeName;
    public boolean isHospital;
    public boolean isVacation;
    public LocalDate startDate;
    public LocalDate endDate;
    public Integer absenseExportDetailId;

    /**
     * Authoritative working-day count for this event, from Microinvest's own {@code
     * PayrollCalendar} (weekends/holidays inside {@code startDate..endDate} already excluded) --
     * NOT a calendar-day span, which would overcount. Only populated for {@code
     * ExportRoot.leaveEvents} (paid/unpaid annual leave); zero/absent on an older export or on
     * {@code hospitalEvents}, where it is unused (sick-leave duration is computed by Nyxa's own
     * engine at payslip time, not stored).
     */
    public int workingDays;
  }

  static final class SickLeaveDetail {
    public int absenseExportDetailId;
    public String sickNote;
    public String series;
    public Integer year;
    public LocalDate realStartDate;
    public LocalDate releaseDate;
    public LocalDate registerDate;
    public String registerNum;
    public boolean isContinued;
  }
}

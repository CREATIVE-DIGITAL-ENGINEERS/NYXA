package com.axelor.apps.bglocalization.service.migration.microinvest;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.Bank;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.City;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.PartnerAddress;
import com.axelor.apps.base.db.repo.AddressRepository;
import com.axelor.apps.base.db.repo.BankDetailsRepository;
import com.axelor.apps.base.db.repo.BankRepository;
import com.axelor.apps.base.db.repo.CityRepository;
import com.axelor.apps.base.db.repo.CountryRepository;
import com.axelor.apps.base.db.repo.PartnerAddressRepository;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.bglocalization.db.BgAbsenceType;
import com.axelor.apps.bglocalization.db.BgContractAmendment;
import com.axelor.apps.bglocalization.db.BgDeclarant;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.repo.BgAbsenceTypeRepository;
import com.axelor.apps.bglocalization.db.repo.BgContractAmendmentRepository;
import com.axelor.apps.bglocalization.db.repo.BgDeclarantRepository;
import com.axelor.apps.bglocalization.db.repo.BgInsuredTypeRepository;
import com.axelor.apps.bglocalization.db.repo.BgNkpdRepository;
import com.axelor.apps.bglocalization.db.repo.BgSickLeaveRepository;
import com.axelor.apps.bglocalization.service.BgEmployeeCompletionService;
import com.axelor.apps.hr.db.BankCard;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.hr.db.LeaveReason;
import com.axelor.apps.hr.db.LeaveRequest;
import com.axelor.apps.hr.db.repo.BankCardRepository;
import com.axelor.apps.hr.db.repo.EmployeeRepository;
import com.axelor.apps.hr.db.repo.EmploymentContractRepository;
import com.axelor.apps.hr.db.repo.LeaveReasonRepository;
import com.axelor.apps.hr.db.repo.LeaveRequestRepository;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.db.Model;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Microinvest TRZ Pro -&gt; Nyxa, one company at a time. Phase 1: master data + employment record +
 * confirmed leave/sick-leave data. Phase 2 (delegated to {@link MicroinvestPayrollHistoryImporter},
 * called from {@link #run}): historical payslips (ValueSalary/ValueSalaryPrior) and declarations
 * (Dekl1Value/Dekl6Value) for an employee/company Phase 1 already onboarded. Both phases run from
 * the same wizard/JSON upload and the same Preflight/Import pair. Both phases deliberately still
 * exclude annual tax recalculation, NKPD/NKID, Application15 and the Microinvest formula scripts
 * themselves.
 *
 * <p>There is no live connection to Microinvest anywhere in this class or module. The source data
 * is extracted once, externally, by the Microinvest export tool (run from a machine that can reach
 * the SQL Server with Windows Authentication) into one JSON file per company; the operator uploads
 * that file through the wizard. This deliberately avoids two real problems with a direct JDBC
 * connection from Nyxa: the app runs in a Linux container and Microinvest uses Windows Integrated
 * Auth (no clean way to bridge that without Kerberos infrastructure), and the JDBC driver isn't
 * available through this project's private Maven feed (would need an uncommitted/gitignored jar,
 * breaking clean-checkout builds).
 *
 * <p>Every write goes through the normal Axelor repositories -- nothing here issues raw SQL against
 * the Nyxa database either.
 *
 * <p>Idempotency is by natural key (Bulstat, EGN, contract start date, the DB unique constraint on
 * BgContractAmendment), not a generic imported-record tracking table -- this is a one-time tool,
 * not a sync framework. Re-running against the same company reuses what's already there instead of
 * duplicating it.
 */
@Singleton
public class MicroinvestPilotImportService {

  // Literal values, not the <extra-code> constants declared in Partner.xml / EmploymentContract.xml
  // (Partner.PARTNER_TYPE_COMPANY etc.) -- this Axelor codegen run does not emit that <extra-code>
  // block into the generated classes (confirmed: absent from every module's build/src-gen
  // Partner.java
  // and EmploymentContract.java), so referencing them fails to compile. Values confirmed directly
  // against the domain XML source instead.
  private static final int PARTNER_TYPE_COMPANY = 1;
  private static final int PARTNER_TYPE_INDIVIDUAL = 2;
  private static final int STATUS_ACTIVE = 2;
  private static final int STATUS_CLOSED = 3;

  /** Payroll.ContractReasons.Identifier -> EmploymentContract.bgContractType, confirmed only. */
  private static final Map<String, String> CONTRACT_TYPE_BY_REASON_IDENTIFIER = new HashMap<>();

  /**
   * Annex ContractReasons.Identifier -> BgContractAmendment.bgChangeGround (the ETZ point-12
   * codes).
   */
  private static final java.util.Set<String> VALID_CHANGE_GROUND_CODES =
      new java.util.HashSet<>(java.util.Arrays.asList("018", "019", "020", "021"));

  static {
    CONTRACT_TYPE_BY_REASON_IDENTIFIER.put("001", "67"); // Безсрочен ТД по чл.67 ал.1 т.1
    CONTRACT_TYPE_BY_REASON_IDENTIFIER.put("011", "111"); // Допълнителен ТД по чл.111
    CONTRACT_TYPE_BY_REASON_IDENTIFIER.put("012", "114"); // ТД по чл.114 ал.1
    // "007" (Art.70 probationary period) is deliberately NOT mapped: it can sit on top of either a
    // 67 or 68 contract and Microinvest's text doesn't say which -- see field-mapping.md.
  }

  @Inject private PartnerRepository partnerRepo;
  @Inject private EmployeeRepository employeeRepo;
  @Inject private EmploymentContractRepository contractRepo;
  @Inject private BgContractAmendmentRepository amendmentRepo;
  @Inject private BgDeclarantRepository declarantRepo;
  @Inject private AddressRepository addressRepo;
  @Inject private PartnerAddressRepository partnerAddressRepo;
  @Inject private BankDetailsRepository bankDetailsRepo;
  @Inject private BankCardRepository bankCardRepo;
  @Inject private BankRepository bankRepo;
  @Inject private CityRepository cityRepo;
  @Inject private CountryRepository countryRepo;
  @Inject private BgSickLeaveRepository sickLeaveRepo;
  @Inject private BgAbsenceTypeRepository absenceTypeRepo;
  @Inject private BgInsuredTypeRepository insuredTypeRepo;
  @Inject private BgNkpdRepository nkpdRepo;
  @Inject private LeaveRequestRepository leaveRequestRepo;
  @Inject private LeaveReasonRepository leaveReasonRepo;
  @Inject private MicroinvestPayrollHistoryImporter payrollHistoryImporter;
  @Inject private MicroinvestPayrollCalendarService payrollCalendarService;
  @Inject private BgEmployeeCompletionService employeeCompletionService;

  /** Read-only: builds the same report an import would, without saving anything. */
  public MicroinvestImportReport preflight(byte[] exportFileContent) throws IOException {
    return run(exportFileContent, true);
  }

  /** Performs the real import, one employee at a time so one bad record doesn't stop the rest. */
  public MicroinvestImportReport runImport(byte[] exportFileContent) throws IOException {
    return run(exportFileContent, false);
  }

  /**
   * The ONE company this import may touch: the operator's active company, resolved exactly as every
   * other company-scoped feature in this module does ({@code
   * AuthUtils.getUser().getActiveCompany()} -- see BgBankStatementImportService). Never derived
   * from the export file, never guessed from a single-company database: an "only one company
   * exists, so use it" fallback is precisely the kind of implicit target that lets a run write into
   * a company nobody chose.
   */
  public static Company activeCompany() {
    User user = AuthUtils.getUser();
    return user == null ? null : user.getActiveCompany();
  }

  private MicroinvestImportReport run(byte[] exportFileContent, boolean dryRun) throws IOException {
    MicroinvestImportReport report = new MicroinvestImportReport();
    report.note(dryRun ? "=== PREFLIGHT (read-only) ===" : "=== IMPORT ===");

    MicroinvestSourceModels.ExportRoot export =
        MicroinvestExportFileParser.parse(exportFileContent);
    MicroinvestSourceModels.Company srcCompany = export.company;

    // ── §A The active company is the only target, and it must already exist. Decided FIRST, before
    // the file is looked at any further and before a single write in either mode. ──
    Company company = activeCompany();
    String activeEik =
        company == null || company.getPartner() == null
            ? null
            : company.getPartner().getRegistrationCode();
    report.targetCompany(
        MicroinvestCompanyGuard.targetBanner(
            company == null ? null : company.getName(), activeEik));

    String sourceBulstat = srcCompany == null ? null : srcCompany.bulstat;
    String sourceName = srcCompany == null ? "?" : srcCompany.name;
    MicroinvestCompanyGuard.Verdict verdict =
        MicroinvestCompanyGuard.verdict(sourceBulstat, activeEik, company != null);
    if (verdict != MicroinvestCompanyGuard.Verdict.MATCH) {
      report.failed(
          "целева компания",
          MicroinvestCompanyGuard.problemMessage(
              verdict,
              sourceName,
              sourceBulstat,
              company == null ? null : company.getName(),
              activeEik));
      return report; // stops before ANY write, in both modes
    }
    report.note(
        "  източник: '"
            + sourceName
            + "' (БУЛСТАТ "
            + sourceBulstat
            + ") -> активна компания '"
            + company.getName()
            + "' (ЕИК "
            + activeEik
            + ") -- съвпадение потвърдено; компания НЕ се създава");

    // ── §3 prerequisite: the two standard LeaveReason rows the paid/unpaid annual-leave import
    // depends on must already exist -- created by the BG localization data-init sync
    // (DataInitSyncService, apps/init-data/*/leave_reason.xml), never guessed here by translated
    // display name. Checked by importId (never by name) and BLOCKS the real import outright rather
    // than silently skipping every leave row deep in the per-event loop, one at a time. ──
    List<String> missingLeaveReasonImportIds = new ArrayList<>();
    for (String importId : new String[] {"lr_paid_annual", "lr_unpaid_own_gt30"}) {
      if (leaveReasonRepo.all().filter("self.importId = :id").bind("id", importId).fetchOne()
          == null) {
        missingLeaveReasonImportIds.add(importId);
      }
    }
    if (!missingLeaveReasonImportIds.isEmpty()) {
      report.failed(
          "LeaveReason prerequisites",
          "missing required LeaveReason(s) with importId "
              + missingLeaveReasonImportIds
              + " -- run the BG localization data-init sync (DataInitSyncService) to create them"
              + (dryRun ? " before importing" : ""));
      if (!dryRun) {
        return report;
      }
    }

    // Salary/D1/D6 coverage does not depend on a real EmploymentContract/Period existing --
    // see tallySalaryAndDeclarationCoverage's own javadoc for why this runs unconditionally in
    // Preflight, even for a from-scratch company with no payroll periods configured yet, instead
    // of only being answerable after a real import creates real payslips to count.
    if (dryRun) {
      payrollHistoryImporter.tallySalaryAndDeclarationCoverage(export, report);
    }

    Map<Integer, MicroinvestSourceModels.Employee> employees = new LinkedHashMap<>();
    for (MicroinvestSourceModels.Employee e : export.employees) {
      employees.put(e.employeeId, e);
    }
    report.employeesFound = employees.size();

    Set<String> seenEgns = new HashSet<>();
    for (MicroinvestSourceModels.Employee e : employees.values()) {
      if (e.egn == null || e.egn.isBlank()) {
        continue;
      }
      if (!seenEgns.add(e.egn)) {
        report.duplicateEgnMerges++;
      }
    }
    report.uniqueEgnCount = seenEgns.size();

    Map<Integer, MicroinvestSourceModels.ContractReason> contractReasons = new HashMap<>();
    for (MicroinvestSourceModels.ContractReason cr : export.contractReasons) {
      contractReasons.put(cr.contractReasonId, cr);
    }
    Map<Integer, MicroinvestSourceModels.SickLeaveDetail> sickLeaveDetails = new HashMap<>();
    for (MicroinvestSourceModels.SickLeaveDetail d : export.sickLeaveDetails) {
      sickLeaveDetails.put(d.absenseExportDetailId, d);
    }

    // ── §B PAYROLL calendar prerequisite: worked out and (for a real import) created BEFORE the
    // employee phase, so the run either has every period its history needs or stops here. Never a
    // per-record skip -- that would silently drop hundreds of rows with "failed: 0". ──
    MicroinvestPayrollCalendarService.Plan periodPlan =
        payrollCalendarService.plan(company, export);
    report.payrollMonthsRequired = periodPlan.getRequiredMonthCount();
    report.payrollMonthsMissing = periodPlan.getMissingMonthCount();
    report.payrollYearsToCreate = periodPlan.getYearsToCreate().size();
    if (periodPlan.hasBlockers()) {
      for (Map.Entry<Integer, String> blocker : periodPlan.getBlockedYears().entrySet()) {
        report.failed("ТРЗ година " + blocker.getKey(), blocker.getValue());
      }
      return report; // abort before the employee phase -- no partial import
    }
    if (!periodPlan.getYearsToCreate().isEmpty()) {
      report.note(
          "  ТРЗ календар: липсват "
              + periodPlan.getMissingMonthCount()
              + " месеца -> "
              + (dryRun ? "ще бъдат създадени" : "създават се")
              + " ТРЗ години "
              + periodPlan.getYearsToCreate()
              + " с по 12 месечни периода (Year.typeSelect=PAYROLL; счетоводните/фискалните години"
              + " не се пипат)");
    }
    if (!dryRun) {
      try {
        payrollCalendarService.apply(periodPlan, report);
      } catch (Exception e) {
        report.failed(
            "ТРЗ календар",
            "не можа да бъде създаден, импортът е прекратен преди служителите: " + e.getMessage());
        return report;
      }
    }

    // ── Declarants: small and low-risk, one transaction. The company itself is NEVER created or
    // modified here -- it is the active company, resolved above. ──
    runWriteUnit(
        dryRun, () -> createDeclarants(company, export.officials, employees, dryRun, report));

    // Map PayrollID -> its EmploymentContract, filled in as each employee is processed, needed to
    // attach sick-leave events (which are keyed by PayrollID) to the right contract.
    Map<Integer, EmploymentContract> contractByPayrollId = new HashMap<>();

    for (MicroinvestSourceModels.Employee src : employees.values()) {
      if (src.isDeleted) {
        report.skipped("Employee #" + src.employeeId, "soft-deleted in Microinvest (IsDeleted=1)");
        continue;
      }
      report.sourceRecords++;
      // Caught per employee, not left to propagate: each employee is its own transaction (see
      // runWriteUnit), so on failure only that employee's writes roll back -- but without this
      // catch, the exception would still escape the loop and silently abort every employee after
      // it in iteration order: one bad address on the first employee would leave none imported,
      // the rest never attempted rather than rolled back.
      try {
        runWriteUnit(
            dryRun,
            () ->
                importOneEmployee(
                    srcCompany.bulstat,
                    src,
                    company,
                    contractReasons,
                    contractByPayrollId,
                    dryRun,
                    report));
      } catch (Exception e) {
        report.failed(
            "Employee " + src.name + " (#" + src.employeeId + ")",
            "unexpected error, this employee's changes were rolled back: " + e.getMessage());
        if (!dryRun) {
          JPA.clear();
        }
      }
    }

    // ── Полетата на договора, без които фишът не се смята ──
    //
    // ⚠️ ВИКА СЕ СЛЕД ЦИКЪЛА ЗА ДОГОВОРИТЕ И ПРЕДИ ИСТОРИЯТА, И РЕДЪТ Е ПРАВИЛО.
    // След договорите — защото contractByPayrollId трябва да е ПЪЛЕН: видът осигурен се събира от
    // всички периоди на договора, а те висят на различни PayrollID-та от една анексна верига.
    // Преди историята — защото BgD1Record-ите, които тя ражда, се четат от Д1 генераторите заедно
    // с договора; договор без вид осигурен прави и тях половинчати.
    //
    // ⚠️ ВЪРШИ РАБОТА И ЗА ЗАВАРЕН ДОГОВОР, не само за новосъздаден. Точно това е случаят днес:
    // 27-те договора вече са внесени (Фаза 1) и са с празни полета, тоест поправката е UPDATE
    // върху заварен запис, а не втори внос. Затова и брояч „обновен“ има смисъл да съществува.
    try {
      java.util.List<MicroinvestSourceModels.Dekl1FieldValue> allD1Values = new ArrayList<>();
      for (MicroinvestSourceModels.Employee src : employees.values()) {
        if (!src.isDeleted) {
          allD1Values.addAll(src.d1Values);
        }
      }
      java.util.Set<String> knownInsuredTypeCodes = new java.util.HashSet<>();
      java.util.Set<String> selfInsuredOnlyCodes = new java.util.HashSet<>();
      for (com.axelor.apps.bglocalization.db.BgInsuredType t : insuredTypeRepo.all().fetch()) {
        if (t.getCode() == null) {
          continue;
        }
        knownInsuredTypeCodes.add(t.getCode().trim());
        // Речникът сам обявява кои кодове са за самоосигуряващо се лице без работодател.
        if ("MIN_INCOME_SOL".equals(t.getBgFloorType())) {
          selfInsuredOnlyCodes.add(t.getCode().trim());
        }
      }

      // Речникът НКПД, по код. Последната дума за всяко предложение от извора.
      java.util.Map<String, com.axelor.apps.bglocalization.db.BgNkpd> nkpdByCode =
          new java.util.HashMap<>();
      for (com.axelor.apps.bglocalization.db.BgNkpd n : nkpdRepo.all().fetch()) {
        if (n.getCode() != null) {
          nkpdByCode.put(n.getCode().trim(), n);
        }
      }

      // Източниковите Payroll редове по идентификатор — оттам идват заплатата и НКПД.
      java.util.Map<Integer, MicroinvestSourceModels.Payroll> payrollsById =
          new java.util.HashMap<>();
      for (MicroinvestSourceModels.Employee src : employees.values()) {
        for (MicroinvestSourceModels.Payroll p : src.payrolls) {
          payrollsById.put(p.payrollId, p);
        }
      }

      java.util.List<MicroinvestContractPayrollFields.ContractResult> fieldResults =
          MicroinvestContractPayrollFields.resolve(
              contractByPayrollId,
              payrollsById,
              allD1Values,
              knownInsuredTypeCodes,
              selfInsuredOnlyCodes,
              nkpdByCode,
              contractPayrollFieldsOperativeDate());

      reportContractPayrollFields(fieldResults, report);

      // ⚠️ В сух пробег НИЩО не се записва — докладът вече каза какво БИ се случило.
      //
      // ⚠️ И ЗАПИСЪТ МИНАВА ПРЕЗ runWriteUnit, КАКТО ВСИЧКИ ОСТАНАЛИ. Към този момент в обхода няма
      // отворена транзакция: всеки предходен блок си е затворил своята. Директен save() тук минава
      // през repository-то без да гърми ВЕДНАГА и пада чак на flush с „no transaction is in
      // progress“ — тоест докладът вече е преброил обновените полета, а в базата няма нито едно.
      // Затова броячите се сверяват със SELECT след вноса, а не се четат от доклада.
      if (!dryRun) {
        runWriteUnit(
            false,
            () -> {
              MicroinvestContractPayrollFields.apply(fieldResults);
              for (MicroinvestContractPayrollFields.ContractResult r : fieldResults) {
                if (r.insuredType.outcome == MicroinvestContractPayrollFields.Outcome.UPDATED
                    || r.baseSalary.outcome == MicroinvestContractPayrollFields.Outcome.UPDATED
                    || r.nkpd.outcome == MicroinvestContractPayrollFields.Outcome.UPDATED
                    || r.hoursPerDay.outcome == MicroinvestContractPayrollFields.Outcome.UPDATED
                    || r.legalHoursPerDay.outcome
                        == MicroinvestContractPayrollFields.Outcome.UPDATED) {
                  contractRepo.save(r.contract);
                }
                // ⚠️ Категорията труд живее върху СЛУЖИТЕЛЯ, затова има свой запис. Договорът и
                // служителят са различни ентити и един save() не покрива и двата.
                if (r.laborCategory.outcome == MicroinvestContractPayrollFields.Outcome.UPDATED
                    && r.contract.getEmployee() != null) {
                  employeeRepo.save(r.contract.getEmployee());
                }
              }
            });
      }
    } catch (Exception e) {
      report.failed("Contract payroll fields", "unexpected error: " + e.getMessage());
      if (!dryRun) {
        JPA.clear();
      }
    }

    // ── Phase 2: payroll history (ValueSalary/ValueSalaryPrior/Dekl1Value), per employee, using
    // the PayrollID -> EmploymentContract map built above. Runs even when the export file has no
    // Phase 2 data (the lists are just empty) -- the same wizard/JSON handles both phases.
    // The company is always real now (the active one), so the old "brand-new company, nothing to
    // attach to" branch is gone along with the old find-or-create; missing PERIODS are handled by
    // the calendar plan above, not by bailing out here.
    for (MicroinvestSourceModels.Employee src : employees.values()) {
      if (src.isDeleted) {
        continue;
      }
      try {
        payrollHistoryImporter.importEmployeeHistory(
            company, periodPlan, contractByPayrollId, src, dryRun, report);
      } catch (Exception e) {
        report.failed(
            "Payroll history for " + src.name + " (#" + src.employeeId + ")",
            "unexpected error: " + e.getMessage());
        if (!dryRun) {
          JPA.clear();
        }
      }
    }

    // ── Phase 2: Декларация обр. № 6, company-level -- not keyed by employee/PayrollID at all ──
    try {
      payrollHistoryImporter.importD6History(company, periodPlan, export.d6Values, dryRun, report);
    } catch (Exception e) {
      report.failed("BgD6Declaration history", "unexpected error: " + e.getMessage());
      if (!dryRun) {
        JPA.clear();
      }
    }

    // ── Sick leave: needs the PayrollID -> EmploymentContract map built above ──
    for (MicroinvestSourceModels.LeaveEvent ev : export.hospitalEvents) {
      report.sourceRecords++;
      EmploymentContract contract = contractByPayrollId.get(ev.payrollId);
      MicroinvestSourceModels.SickLeaveDetail detail =
          ev.absenseExportDetailId == null ? null : sickLeaveDetails.get(ev.absenseExportDetailId);
      try {
        runWriteUnit(dryRun, () -> importSickLeave(ev, detail, contract, dryRun, report));
      } catch (Exception e) {
        report.failed(
            "BgSickLeave (Event #" + ev.eventId + ")", "unexpected error: " + e.getMessage());
        if (!dryRun) {
          JPA.clear();
        }
      }
    }

    // ── Paid/unpaid annual leave (§3): same PayrollID -> EmploymentContract map; LeaveRequest is
    // scoped to the active company, both when created and when looked up for reuse. ──
    for (MicroinvestSourceModels.LeaveEvent ev : export.leaveEvents) {
      report.sourceRecords++;
      EmploymentContract contract = contractByPayrollId.get(ev.payrollId);
      try {
        runWriteUnit(dryRun, () -> importLeaveEvent(ev, contract, company, dryRun, report));
      } catch (Exception e) {
        report.failed(
            "LeaveRequest (Event #" + ev.eventId + ")", "unexpected error: " + e.getMessage());
        if (!dryRun) {
          JPA.clear();
        }
      }
    }

    return report;
  }

  /** Runs one unit of work in its own transaction (import) or not at all (preflight). */
  private void runWriteUnit(boolean dryRun, Runnable work) {
    if (dryRun) {
      work.run();
    } else {
      JPA.runInTransaction(work);
    }
  }

  /**
   * Re-fetches a possibly-stale reference (e.g. loaded in an earlier record's transaction, now
   * detached because that transaction committed/rolled back and the persistence context was {@link
   * JPA#clear() cleared} after a failure -- see the catch blocks in {@link #run}) by its own id,
   * within the CURRENT persistence context. Passing a detached entity as a *-to-one association on
   * a brand-new entity is exactly what throws "detached entity passed to persist", cascading Phase
   * 2 failures after one bad record.
   *
   * <p>⚠️ Takes the entity type EXPLICITLY rather than reading {@code entity.getClass()}: a managed
   * entity handed back by a repository query is very often a Hibernate proxy (runtime class {@code
   * Company$HibernateProxy$...}), which has no registered persister of its own -- {@code
   * JPA.find(entity.getClass(), id)} on a proxy throws "Unable to locate persister" instead of
   * re-fetching anything. A typed lookup against the real entity class sidesteps the proxy question
   * entirely instead of trying to unwrap it.
   *
   * <p>A transient entity ({@code getId() == null} -- the Preflight-only simulated {@code
   * EmploymentContract}) is returned unchanged; safe to call unconditionally, including in dry-run
   * (a plain read).
   */
  static <T extends Model> T attach(Class<T> type, T entity) {
    return entity == null || entity.getId() == null ? entity : JPA.find(type, entity.getId());
  }

  // ═══ Declarants (the company itself is never created -- see MicroinvestCompanyGuard) ═══

  private void createDeclarants(
      Company company,
      List<MicroinvestSourceModels.Official> officials,
      Map<Integer, MicroinvestSourceModels.Employee> employees,
      boolean dryRun,
      MicroinvestImportReport report) {
    for (MicroinvestSourceModels.Official o : officials) {
      report.sourceRecords++;
      String capacity = declarantCapacityOf(o.positionIdentifier);
      MicroinvestSourceModels.Employee srcEmployee = employees.get(o.employeeId);
      String label =
          "Declarant "
              + (srcEmployee != null ? srcEmployee.name : "employee #" + o.employeeId)
              + " (role: "
              + o.positionName
              + ")";
      if (capacity == null) {
        report.skipped(
            label,
            "Microinvest role has no equivalent in Nyxa's declarantCapacity selection (only"
                + " R=legal representative / A=authorized representative exist)");
        continue;
      }
      if (srcEmployee == null || srcEmployee.egn == null || srcEmployee.egn.isBlank()) {
        report.skipped(label, "no EGN available to identify the declarant");
        continue;
      }
      // ⚠️ §D PARITY: the lookup runs in BOTH modes. Guarded by `!dryRun`, Preflight would
      // announce rows as CREATED that the Import immediately reports as REUSED -- it would not be
      // predicting the import, but describing a database it had refused to look at. Reading is
      // not writing; a dry run may query freely.
      boolean alreadyExists =
          declarantRepo
                  .all()
                  .filter("self.company = :company AND self.declarantEgn = :egn")
                  .bind("company", company)
                  .bind("egn", srcEmployee.egn)
                  .fetchOne()
              != null;
      if (alreadyExists) {
        report.reused(label);
        continue;
      }
      if (dryRun) {
        report.created(label);
        continue;
      }
      BgDeclarant declarant = new BgDeclarant();
      declarant.setCompany(company);
      declarant.setDeclarantEgn(srcEmployee.egn);
      declarant.setDeclarantNames(srcEmployee.name);
      declarant.setDeclarantCapacity(capacity);
      // Длъжността идва от НАИМЕНОВАНИЕТО на ролята в Microinvest („Управител“) — текст, а не кодът
      // R/A. Извън 2–100 знака остава празна: износът на Прил. 9 тогава отказва поименно.
      declarant.setDeclarantPosition(positionOf(o.positionName));
      declarantRepo.save(declarant);
      report.created(label);
    }
  }

  /**
   * Positions.Identifier is Microinvest's own fixed numbering for the four reserved "official"
   * roles (2=manager/legal representative, 3=authorized representative/bearer, 4=chief accountant,
   * 1=generic "official position" placeholder for civil contracts). Only 2 and 3 have a confirmed
   * equivalent in Nyxa's two-value bg.vat.declarant.capacity.select (R/A) -- see
   * nyxa-capability-inventory.md. 4 (chief accountant) and 1 are deliberately left unmapped rather
   * than guessed onto "A".
   */
  /** Длъжността за Прил. 9 — наименованието на ролята, ако е 2–100 знака; иначе празно. */
  static String positionOf(String positionName) {
    if (positionName == null) {
      return null;
    }
    String t = positionName.trim();
    return t.length() >= 2 && t.length() <= 100 ? t : null;
  }

  private String declarantCapacityOf(String positionIdentifier) {
    if ("2".equals(positionIdentifier)) {
      return "R";
    }
    if ("3".equals(positionIdentifier)) {
      return "A";
    }
    return null;
  }

  // ═══ Employee ═══

  private void importOneEmployee(
      String companyBulstat,
      MicroinvestSourceModels.Employee src,
      Company company,
      Map<Integer, MicroinvestSourceModels.ContractReason> contractReasons,
      Map<Integer, EmploymentContract> contractByPayrollId,
      boolean dryRun,
      MicroinvestImportReport report) {

    company = attach(Company.class, company);
    String label = "Employee " + src.name + " (#" + src.employeeId + ")";
    boolean hasEgn = src.egn != null && !src.egn.isBlank();
    if (!hasEgn) {
      report.skipped(
          label, "no EGN in source -- cannot safely identify or de-duplicate this person");
      return;
    }

    // ⚠️ §E EMPLOYEE IDENTITY IS GLOBAL, ON PURPOSE -- read off the real model, not assumed.
    // com.axelor.apps.hr.db.Employee has NO company field (checked field by field in
    // axelor-human-resource/domains/Employee.xml and this module's Employee.xml extension): a
    // person exists once, and the employer relationship lives on EmploymentContract.payCompany
    // (plus Partner.companySet). So the natural key here is the normalized EGN alone -- adding the
    // company to it would create a second Employee row for the same human being every time they
    // work for two client companies in the same Nyxa instance.
    //
    // What IS company-scoped is everything hanging off the person: contracts (payCompany),
    // amendments and sick leave (through the contract), leave requests (company), payslips and
    // declarations (through the contract/period). Each of those lookups pins the active company --
    // an EGN/date match alone must never let one company's record be "reused" by another.
    String normalizedEgn = normalizeEgn(src.egn);
    // Matched against both spellings rather than through a REPLACE()/TRIM() in JPQL: rows written
    // by an earlier run of this importer carry the raw source value, new ones carry the normalized
    // one, and a DB-side string function over the column would also defeat its index. fetch(1)
    // rather than fetchOne() so a legacy database holding BOTH spellings of the same person yields
    // the older row deterministically instead of a NonUniqueResultException mid-import.
    List<Employee> egnMatches =
        employeeRepo
            .all()
            .filter("self.bgPersonalIdNumber IN (:egns)")
            .bind("egns", new HashSet<>(Arrays.asList(src.egn.trim(), normalizedEgn)))
            .order("id")
            .fetch(1);
    Employee existing = egnMatches.isEmpty() ? null : egnMatches.get(0);
    Employee employee;
    if (existing != null) {
      report.reused(label);
      employee = existing;
    } else if (dryRun) {
      report.created(label);
      employee = null; // nothing to attach contracts to in a dry run without a real Employee
    } else {
      employee = createEmployee(src, report);
      report.created(label);
    }

    if (employee == null) {
      // Preflight for a brand-new employee: we still want the contract-chain analysis to show up
      // in the report, so run it against a throwaway in-memory Employee that is never saved.
      employee = new Employee();
    }

    Integer declaredServiceMonths = src.declaredServiceMonths;
    if (declaredServiceMonths != null && declaredServiceMonths > 0) {
      if (!dryRun) {
        employee.setBgInsuranceServiceMonths(declaredServiceMonths);
        employee.setBgInsuranceServiceDeclared(true);
        employeeRepo.save(employee);
      }
      report.note("  declared prior service: " + declaredServiceMonths + " months (" + label + ")");
    }

    // An already-imported employee keeps whatever address/bank data it already has -- the real
    // import only ever touches addresses/bank accounts for a brand-new employee (existing ==
    // null). Preflight runs the SAME resolution (City/Bank lookups) for a brand-new employee too,
    // not a generic placeholder, so address/bank outcomes are genuinely previewed rather than
    // merely announced -- see resolveCity/importBankAccounts' own dryRun handling.
    if (existing == null) {
      importAddresses(src, employee.getContactPartner(), dryRun, report);
      importBankAccounts(src, employee.getContactPartner(), dryRun, report);
    }

    importContracts(
        companyBulstat,
        src,
        employee,
        company,
        contractReasons,
        contractByPayrollId,
        dryRun,
        report);

    // ── §C Finish the record through the SAME logic as the form's "Завършете" button ──
    // Without this every imported employee stays on step 1 of the staged wizard and its fields are
    // hidden behind showIf="stepByStepSelect == 5" -- every imported employee would look empty
    // until an operator clicked through the green arrows one by one. Runs for
    // a freshly created employee AND for an already-imported one that is still incomplete; an
    // employee already finished (by a previous run or by hand) is left untouched.
    boolean needsCompletion =
        employee.getId() != null && BgEmployeeCompletionService.isIncomplete(employee);
    if (needsCompletion) {
      if (dryRun) {
        report.note("  ще бъде завършен (Завършете / stepByStepSelect=5): " + label);
      } else {
        employeeCompletionService.complete(employee, company);
        report.note("  завършен (Завършете / stepByStepSelect=5): " + label);
      }
    } else if (employee.getId() == null && dryRun) {
      report.note("  ще бъде създаден и завършен (Завършете / stepByStepSelect=5): " + label);
    }
  }

  /**
   * EGN/ЛНЧ as a bare digit string -- the only form two systems can be compared on. Whitespace and
   * punctuation are stripped; anything else is left alone so a genuinely odd value is not silently
   * turned into a different person's number.
   */
  static String normalizeEgn(String egn) {
    if (egn == null) {
      return null;
    }
    String cleaned = egn.replaceAll("[\\s\\-/]", "");
    return cleaned.isEmpty() ? null : cleaned;
  }

  private Employee createEmployee(
      MicroinvestSourceModels.Employee src, MicroinvestImportReport report) {
    String[] nameParts = splitBulgarianName(src.name);
    Partner partner = new Partner();
    partner.setPartnerTypeSelect(PARTNER_TYPE_INDIVIDUAL);
    partner.setIsEmployee(true);
    partner.setFirstName(nameParts[0]);
    partner.setName(nameParts[2]);
    partner = partnerRepo.save(partner);

    Employee employee = new Employee();
    employee.setContactPartner(partner);
    employee.setBgPersonalIdNumber(normalizeEgn(src.egn));
    employee.setBgPersonalIdType(src.isForeigner ? "2" : "1");
    employee.setBirthDate(src.birthDate);
    employee.setBgPersonnelNumber(src.number);
    employee.setBgIdCardNumber(src.idNumber);
    employee.setBgIdCardIssueDate(src.idIssueDate);
    employee.setBgIdCardIssuedBy(src.idIssuedFrom);
    employee.setFixedProPhone(src.workPhone);
    employee.setMobileProPhone(src.phone);

    String sex = deriveSexFromEgn(src.egn);
    if (sex != null) {
      employee.setSexSelect(sex);
    } else {
      report.note("  NOTE: could not derive sex from EGN for Employee #" + src.employeeId);
    }

    List<MicroinvestSourceModels.Payroll> active = src.payrolls;
    LocalDate earliestStart = null;
    for (MicroinvestSourceModels.Payroll p : active) {
      if (p.fromDate != null && (earliestStart == null || p.fromDate.isBefore(earliestStart))) {
        earliestStart = p.fromDate;
      }
    }
    employee.setHireDate(earliestStart);

    return employeeRepo.save(employee);
  }

  /**
   * Microinvest stores a single free-text "Собствено Презиме Фамилия" (first/middle/last) name.
   * Split heuristically: first token = first name, last token = surname, anything in between =
   * middle name. A single-token name (rare) is treated as the surname with no first name -- flagged
   * via a report note by the caller checking the result, not here.
   */
  static String[] splitBulgarianName(String fullName) {
    String trimmed = fullName == null ? "" : fullName.trim().replaceAll("\\s+", " ");
    if (trimmed.isEmpty()) {
      return new String[] {null, null, trimmed};
    }
    String[] tokens = trimmed.split(" ");
    if (tokens.length == 1) {
      return new String[] {null, null, tokens[0]};
    }
    String first = tokens[0];
    String last = tokens[tokens.length - 1];
    StringBuilder middle = new StringBuilder();
    for (int i = 1; i < tokens.length - 1; i++) {
      if (middle.length() > 0) {
        middle.append(' ');
      }
      middle.append(tokens[i]);
    }
    return new String[] {first, middle.length() == 0 ? null : middle.toString(), last};
  }

  /**
   * Bulgarian EGN digit 9 (0-indexed 8) is odd for men, even for women -- a fixed rule, not a
   * heuristic. Preferred over Microinvest's own Gender bit column, whose 0/1 convention could not
   * be confirmed against any documentation. Returns null (don't guess) when the EGN isn't exactly
   * 10 digits.
   */
  static String deriveSexFromEgn(String egn) {
    if (egn == null || !egn.matches("\\d{10}")) {
      return null;
    }
    int ninthDigit = egn.charAt(8) - '0';
    return ninthDigit % 2 == 0 ? "F" : "M";
  }

  /**
   * Bulgaria's {@code AddressTemplate} (base_address_template_line) marks exactly three fields
   * required: {@code streetName}, {@code city} (the many-to-one {@link City} reference -- NOT the
   * free-text {@code townName} field, which the template does not check at all), and {@code zip}.
   * {@code AddressBaseRepository.save()} enforces this on every save (via {@code
   * AddressTemplateServiceImpl.checkRequiredAddressFields}) -- there is no way to save an
   * incomplete Bulgarian address, so a source row missing any of the three is skipped and reported
   * rather than saved with an invented value.
   *
   * <p>{@code City} is resolved by {@link #resolveCity}, most-specific match first: exact (name,
   * zip); name + source district/municipality; a unique {@code City.name} regardless of zip (this
   * is what lets a real Sofia address with a district-level postcode like 1582/1592 import against
   * Nyxa's single Sofia settlement row, whose own canonical zip won't match every district code);
   * otherwise fails closed (unmatched/ambiguous), never guessed. The source postcode is preserved
   * on the {@link Address} either way (see {@code address.setZip} below).
   */
  private void importAddresses(
      MicroinvestSourceModels.Employee src,
      Partner partner,
      boolean dryRun,
      MicroinvestImportReport report) {
    // ⚠️ City/street/postcode resolution runs the SAME way in preflight as in the real import
    // (dryRun only skips the final Address/PartnerAddress save) -- a preflight for a brand-new
    // employee has no saved Partner yet (employee.getContactPartner() is null on an in-memory-only
    // Employee), so "partner == null" is treated the same as dryRun below rather than skipping
    // resolution entirely, which would leave Preflight unable to say whether an address would
    // actually match a City or not.
    Country bulgaria = countryRepo.all().filter("self.alpha2Code = :c").bind("c", "BG").fetchOne();
    boolean first = true;
    for (MicroinvestSourceModels.Address a : src.addresses) {
      String label = "Address for " + src.name + " (#" + src.employeeId + ")";

      if (a.street == null || a.street.isBlank()) {
        report.skipped(label, "no street text in source -- required field, not guessed");
        report.addressOutcome(MicroinvestImportReport.AddressOutcome.EMPTY);
        continue;
      }
      if (a.postalCode == null || a.postalCode.isBlank()) {
        report.skipped(label, "no postal code in source -- required field, not guessed");
        report.addressOutcome(MicroinvestImportReport.AddressOutcome.EMPTY);
        continue;
      }
      City city = resolveCity(a, report, label);
      if (city == null) {
        continue;
      }
      report.addressOutcome(MicroinvestImportReport.AddressOutcome.IMPORTED);

      if (dryRun || partner == null) {
        report.created(label);
        continue;
      }

      Address address = new Address();
      address.setCountry(bulgaria);
      address.setCity(city);
      address.setTownName(a.city);
      address.setZip(a.postalCode);
      address.setStreetName(a.street);
      address.setDistrictName(a.district);
      address = addressRepo.save(address);

      PartnerAddress pa = new PartnerAddress();
      pa.setPartner(partner);
      pa.setAddress(address);
      pa.setIsDefaultAddr(first);
      partnerAddressRepo.save(pa);
      first = false;
    }
    if (bulgaria == null && !src.addresses.isEmpty()) {
      report.note(
          "  NOTE: no Country with alpha2Code=BG found -- addresses saved without a country.");
    }
  }

  /**
   * City resolution order, most specific first, never guessing:
   *
   * <ol>
   *   <li>exact {@code name + zip} match (many Bulgarian city names repeat across different
   *       municipalities -- confirmed against the live nomenclature, e.g. "Александрово" exists 8
   *       times -- but this pair had zero collisions in the same check);
   *   <li>{@code name} + source district/municipality matched against {@code City.region} / {@code
   *       City.municipality} (the BG settlement extension on stock {@code City});
   *   <li>a unique {@code City.name} regardless of zip -- this is what resolves a real Sofia
   *       address whose district-level postcode (1582, 1592, ...) doesn't match Nyxa's single Sofia
   *       settlement row's own canonical zip;
   *   <li>otherwise fail closed: no candidate is "unmatched", 2+ remaining candidates is
   *       "ambiguous" -- neither creates an Address.
   * </ol>
   */
  private City resolveCity(
      MicroinvestSourceModels.Address a, MicroinvestImportReport report, String label) {
    if (a.city == null || a.city.isBlank()) {
      report.skipped(label, "no city name in source -- required field, not guessed");
      report.addressOutcome(MicroinvestImportReport.AddressOutcome.EMPTY);
      return null;
    }

    City exact =
        cityRepo
            .all()
            .filter("self.name = :name AND self.zip = :zip")
            .bind("name", a.city)
            .bind("zip", a.postalCode)
            .fetchOne();
    List<City> byName =
        exact != null
            ? List.of()
            : cityRepo.all().filter("self.name = :name").bind("name", a.city).fetch();

    City chosen = chooseCity(exact, byName, a.district, a.municipality);
    if (chosen != null) {
      return chosen;
    }
    if (byName.isEmpty()) {
      report.skipped(
          label,
          "no City named '" + a.city + "' in Nyxa's nomenclature -- required field, not guessed");
      report.addressOutcome(MicroinvestImportReport.AddressOutcome.UNMATCHED);
    } else {
      report.skipped(
          label,
          byName.size()
              + " City rows named '"
              + a.city
              + "' and district/municipality did not narrow to one -- ambiguous, not guessed");
      report.addressOutcome(MicroinvestImportReport.AddressOutcome.AMBIGUOUS);
    }
    return null;
  }

  /**
   * Package-visible (not private) pure decision, given already-fetched candidates, so a test can
   * exercise the resolution order without a database: exact (name, zip); a unique {@code byName}
   * regardless of zip; {@code byName} narrowed by district/municipality against {@code
   * City.region}/{@code City.municipality}; otherwise {@code null} (caller reports
   * unmatched/ambiguous from {@code byName}'s own size).
   */
  static City chooseCity(City exact, List<City> byName, String district, String municipality) {
    if (exact != null) {
      return exact;
    }
    if (byName.size() == 1) {
      return byName.get(0);
    }
    if (byName.size() > 1 && (isNotBlank(district) || isNotBlank(municipality))) {
      List<City> narrowed = new ArrayList<>();
      for (City c : byName) {
        boolean matchesRegion = isNotBlank(district) && district.equalsIgnoreCase(c.getRegion());
        boolean matchesMunicipality =
            isNotBlank(municipality) && municipality.equalsIgnoreCase(c.getMunicipality());
        if (matchesRegion || matchesMunicipality) {
          narrowed.add(c);
        }
      }
      if (narrowed.size() == 1) {
        return narrowed.get(0);
      }
    }
    return null;
  }

  private static boolean isNotBlank(String s) {
    return s != null && !s.isBlank();
  }

  private void importBankAccounts(
      MicroinvestSourceModels.Employee src,
      Partner partner,
      boolean dryRun,
      MicroinvestImportReport report) {
    for (MicroinvestSourceModels.BankAccount ba : src.bankAccounts) {
      Bank bank = bankRepo.all().filter("self.code = :bic").bind("bic", ba.bankBic).fetchOne();
      if (bank == null) {
        report.skipped(
            "Bank account for " + src.name, "no Bank with BIC " + ba.bankBic + " in Nyxa");
        report.bankAccountsMissingBic++;
        continue;
      }
      String label = "Bank account for " + src.name + " (BIC " + ba.bankBic + ")";
      if (dryRun || partner == null) {
        report.created(label);
        report.bankAccountsImported++;
        continue;
      }
      BankDetails details = new BankDetails();
      details.setPartner(partner);
      details.setBank(bank);
      details.setIban(ba.iban);
      details.setOwnerName(src.name);
      details = bankDetailsRepo.save(details);
      report.bankAccountsImported++;

      Employee employee =
          employeeRepo.all().filter("self.contactPartner = :p").bind("p", partner).fetchOne();
      if (employee != null) {
        BankCard card = new BankCard();
        card.setEmployee(employee);
        card.setBankDetails(details);
        bankCardRepo.save(card);
      }
    }
  }

  // ═══ Employment contracts + amendments ═══

  /**
   * Изписва по един ред за всяко от трите полета на всеки засегнат договор.
   *
   * <p><b>⚠️ ЛИЧНИ ДАННИ НЕ ВЛИЗАТ.</b> Редът се идентифицира със ЗАПИСИТЕ НА ИЗВОРА (PayrollID) и
   * с идентификатора на договора в Nyxa — не с име и не с ЕГН. Който трябва да стигне до човека,
   * има и двете следи; докладът може да се чете и препраща, без да ги носи.
   */
  private void reportContractPayrollFields(
      java.util.List<MicroinvestContractPayrollFields.ContractResult> results,
      MicroinvestImportReport report) {

    for (MicroinvestContractPayrollFields.ContractResult r : results) {
      String id =
          "EmploymentContract "
              + (r.contract.getId() == null ? "(нов)" : "#" + r.contract.getId())
              + " [Microinvest PayrollID "
              + r.sourcePayrollIds
              + "]";

      recordFieldOutcome(report, id + " · вид осигурен", r.insuredType);
      recordFieldOutcome(report, id + " · основна заплата", r.baseSalary);
      recordFieldOutcome(report, id + " · НКПД", r.nkpd);
      recordFieldOutcome(report, id + " · часове на ден", r.hoursPerDay);
      recordFieldOutcome(report, id + " · законоуст. часове", r.legalHoursPerDay);
      recordFieldOutcome(report, id + " · категория труд", r.laborCategory);
    }
  }

  private void recordFieldOutcome(
      MicroinvestImportReport report,
      String label,
      MicroinvestContractPayrollFields.FieldResult result) {

    // ⚠️ ЧЕТИРИТЕ НЕЩА, БЕЗ КОИТО РЕДЪТ НЕ СЕ ОТСЪЖДА: какво е заварено, какво се предлага, от кога
    // важи и от кой източников запис идва. Без тях Preflight казва „ще пипна нещо“, а човекът пред
    // екрана няма как да реши дали да го пусне.
    StringBuilder detail = new StringBuilder();
    detail.append("заварено=").append(result.existingValue == null ? "—" : result.existingValue);
    detail
        .append(" | предложено=")
        .append(result.proposedValue == null ? "—" : result.proposedValue);
    if (result.effectiveDate != null) {
      detail.append(" | в сила от=").append(result.effectiveDate);
    }
    if (result.sourceRecord != null) {
      detail.append(" | източник=").append(result.sourceRecord);
    }
    if (result.detail != null && !result.detail.isEmpty()) {
      detail.append(" | ").append(result.detail);
    }

    switch (result.outcome) {
      case UPDATED:
        report.contractFieldsUpdated++;
        report.updated(label, detail.toString());
        break;
      case UNCHANGED:
        report.contractFieldsUnchanged++;
        report.unchanged(label, detail.toString());
        break;
      case SKIPPED:
        report.contractFieldsSkipped++;
        report.skipped(label, detail.toString());
        break;
      case CONFLICT:
        report.contractFieldsConflict++;
        report.conflict(label, detail.toString());
        break;
      case FAILED:
      default:
        report.contractFieldsFailed++;
        report.failed(label, detail.toString());
        break;
    }
  }

  /**
   * Датата, към която се търси ВАЛИДНАТА договорна заплата.
   *
   * <p>⚠️ Краят на текущия месец, а не „най-новият ред в извора“. Microinvest пренася стойността
   * напред и за отдавна приключили договори (PayrollID 1 е спрял през юли 2022 г., но носи ред и за
   * юли 2026 г. с предевровата си стойност), затова изборът винаги е по дата — тук, и капнат по
   * края на самия договор в {@link MicroinvestContractPayrollFields}.
   */
  protected java.time.LocalDate contractPayrollFieldsOperativeDate() {
    java.time.LocalDate today = java.time.LocalDate.now();
    return today.withDayOfMonth(today.lengthOfMonth());
  }

  private void importContracts(
      String companyBulstat,
      MicroinvestSourceModels.Employee src,
      Employee employee,
      Company company,
      Map<Integer, MicroinvestSourceModels.ContractReason> contractReasons,
      Map<Integer, EmploymentContract> contractByPayrollId,
      boolean dryRun,
      MicroinvestImportReport report) {

    for (MicroinvestSourceModels.Payroll p : src.payrolls) {
      if (p.isDeleted) {
        report.payrollDeletedExcluded++;
      } else {
        report.payrollActive++;
      }
    }

    Map<Integer, List<MicroinvestSourceModels.Payroll>> chains = new LinkedHashMap<>();
    for (MicroinvestSourceModels.Payroll p : src.payrolls) {
      int chainKey = p.annexPayrollId != null ? p.annexPayrollId : p.payrollId;
      chains.computeIfAbsent(chainKey, k -> new ArrayList<>()).add(p);
    }

    List<EmploymentContract> contractsForThisEmployee = new ArrayList<>();

    for (Map.Entry<Integer, List<MicroinvestSourceModels.Payroll>> entry : chains.entrySet()) {
      int chainKey = entry.getKey();
      List<MicroinvestSourceModels.Payroll> chain = entry.getValue();

      // The chain's own root row is the one WHOSE OWN payrollId is the chain key (annexPayrollId
      // == null) -- found by id, not by earliest date, so a deleted root is recognized as the
      // root even if (defensively) its date happens not to sort first.
      MicroinvestSourceModels.Payroll root = null;
      for (MicroinvestSourceModels.Payroll p : chain) {
        if (p.payrollId == chainKey) {
          root = p;
          break;
        }
      }
      if (root == null) {
        chain.sort(
            Comparator.comparing(
                (MicroinvestSourceModels.Payroll p) ->
                    p.fromDate == null ? LocalDate.MIN : p.fromDate));
        root = chain.get(0);
      }
      List<MicroinvestSourceModels.Payroll> annexes = new ArrayList<>();
      for (MicroinvestSourceModels.Payroll p : chain) {
        if (p != root) {
          annexes.add(p);
        }
      }
      annexes.sort(
          Comparator.comparing(
              (MicroinvestSourceModels.Payroll p) ->
                  p.fromDate == null ? LocalDate.MIN : p.fromDate));

      String rootLabel =
          "EmploymentContract for "
              + src.name
              + " starting "
              + root.fromDate
              + " (Payroll #"
              + root.payrollId
              + ")";

      // ⚠️ КРАЯТ НА ДОГОВОРА ИДВА ОТ ТЕРМИНАЛНИЯ ЗАПИС НА ВЕРИГАТА, НЕ ОТ КОРЕНА Ѝ.
      // root.toDate е краят само на ПЪРВИЯ период; веригата продължава през анексите и завършва с
      // отворен или приключил терминален ред (виж MicroinvestPayrollChainResolver). Взето от
      // корена, то затваря жив договор и капва договорната заплата към отминала дата.
      MicroinvestPayrollChainResolver.ChainResolution chainEnd =
          MicroinvestPayrollChainResolver.resolve(root, chain);

      // ── §1 Deleted-payroll protection: three cases, decided from the root row alone ──
      ChainOutcome outcome = classifyChain(root, annexes);
      if (outcome != ChainOutcome.PROCEED) {
        if (outcome == ChainOutcome.EXCLUDED_DELETED) {
          // Whole chain is deleted (or a lone deleted root with no annexes): nothing to promote,
          // nothing to orphan -- simply excluded.
          report.skipped(rootLabel, "IsDeleted=1 in Microinvest -- intentionally excluded");
        } else {
          // Fail closed: never treat the earliest surviving annex as if it were the original
          // hire, never drop the annexes and create nothing else either -- leave the whole chain
          // for a human to look at in the source data.
          StringBuilder activeIds = new StringBuilder();
          for (MicroinvestSourceModels.Payroll p : annexes) {
            if (!p.isDeleted) {
              if (activeIds.length() > 0) {
                activeIds.append(", ");
              }
              activeIds.append('#').append(p.payrollId);
            }
          }
          report.skipped(
              rootLabel,
              "root Payroll #"
                  + root.payrollId
                  + " is deleted in Microinvest but active annex row(s) "
                  + activeIds
                  + " reference it -- needs manual review, not auto-resolved by promoting an"
                  + " annex to root or orphaning it");
          report.payrollChainsFailClosed++;
        }
        continue;
      }

      // ⚠️ Looked up regardless of dryRun -- read-only, and Preflight needs to know about an
      // ALREADY-imported contract (from a prior real run) to build contractByPayrollId, which
      // Phase 2's sick-leave and payroll-history preflight both depend on to say anything useful
      // about an employee that was already migrated. Only employee.getId() == null (this employee
      // doesn't exist yet even as a preflight preview) forces null -- there's nothing to look up.
      EmploymentContract contract =
          employee.getId() == null
              ? null
              : contractRepo
                  .all()
                  .filter(
                      "self.employee = :employee AND self.payCompany = :company AND self.startDate ="
                          + " :startDate")
                  .bind("employee", employee)
                  .bind("company", company)
                  .bind("startDate", root.fromDate)
                  .fetchOne();

      if (contract != null) {
        report.reused(rootLabel);
        report.contractRootsReused++;
        // ⚠️ И ЗАВАРЕНИЯТ ДОГОВОР СЕ ПОПРАВЯ. Фаза 1 е внесла края от корена на веригата; докато
        // той стои, договорът е „приключил“ по погрешка и заплатата му е капната към стара дата.
        applyResolvedEndDate(contract, root, chainEnd, rootLabel, dryRun, report);
      } else if (dryRun) {
        report.created(rootLabel);
        report.contractRootsCreated++;
        String ground = mapBgContractType(root, contractReasons, report, rootLabel);
        // ⚠️ SIMULATED, never saved (getId() stays null): a transient, in-memory contract with
        // exactly the fields the real one would carry, so downstream Preflight (sick leave,
        // paid/unpaid leave, Phase 2 payroll history) can evaluate against a real identity instead
        // of bailing out with "no EmploymentContract imported for this PayrollID" for every
        // brand-new company. Every place that reads this object again (below, and in
        // MicroinvestPayrollHistoryImporter) must treat a null getId() as "cannot already exist"
        // rather than querying a repository with it as a bind parameter -- Hibernate cannot resolve
        // a transient entity's foreign-key value.
        contract = new EmploymentContract();
        contract.setEmployee(employee);
        contract.setPayCompany(company);
        contract.setStartDate(root.fromDate);
        contract.setPosition(root.positionName);
        setChainEnd(contract, root, chainEnd);
        if (ground != null) {
          contract.setBgContractType(ground);
        }
        reportNewChainEnd(rootLabel, chainEnd, report);
        contractByPayrollId.put(root.payrollId, contract);
        for (MicroinvestSourceModels.Payroll p : annexes) {
          if (p.isDeleted) {
            report.skipped(
                "BgContractAmendment (Payroll #" + p.payrollId + ")",
                "IsDeleted=1 in Microinvest -- intentionally excluded");
            continue;
          }
          contractByPayrollId.put(p.payrollId, contract);
          previewAmendment(companyBulstat, src, p, contractReasons, report);
        }
        continue; // nothing real to attach downstream in a dry run -- only the simulated identity
      } else {
        contract = new EmploymentContract();
        contract.setEmployee(employee);
        contract.setPayCompany(company);
        contract.setStartDate(root.fromDate);
        contract.setPosition(root.positionName);
        setChainEnd(contract, root, chainEnd);
        String ground = mapBgContractType(root, contractReasons, report, rootLabel);
        if (ground != null) {
          contract.setBgContractType(ground);
        }
        reportNewChainEnd(rootLabel, chainEnd, report);
        contract = contractRepo.save(contract);
        report.created(rootLabel);
        report.contractRootsCreated++;
      }

      contractsForThisEmployee.add(contract);
      contractByPayrollId.put(root.payrollId, contract);

      for (MicroinvestSourceModels.Payroll p : annexes) {
        if (p.isDeleted) {
          report.skipped(
              "BgContractAmendment (Payroll #" + p.payrollId + ")",
              "IsDeleted=1 in Microinvest -- intentionally excluded");
          continue;
        }
        contractByPayrollId.put(p.payrollId, contract);
        importAmendment(companyBulstat, p, contract, contractReasons, dryRun, report);
      }
    }

    if (!dryRun && !contractsForThisEmployee.isEmpty()) {
      EmploymentContract primary =
          contractsForThisEmployee.stream()
              .max(
                  Comparator.comparing(
                      c -> c.getStartDate() == null ? LocalDate.MIN : c.getStartDate()))
              .orElse(null);
      for (EmploymentContract c : contractsForThisEmployee) {
        boolean isPrimary = c.equals(primary);
        if (!isPrimary && Boolean.TRUE.equals(c.getBgIsPrimaryContract())) {
          c.setBgIsPrimaryContract(false);
          contractRepo.save(c);
        }
      }
      if (primary != null && employee.getId() != null) {
        employee.setMainEmploymentContract(primary);
        employeeRepo.save(employee);
      }
    }
  }

  private String mapBgContractType(
      MicroinvestSourceModels.Payroll root,
      Map<Integer, MicroinvestSourceModels.ContractReason> contractReasons,
      MicroinvestImportReport report,
      String contractLabel) {
    MicroinvestSourceModels.ContractReason reason =
        root.contractReasonId == null ? null : contractReasons.get(root.contractReasonId);
    String identifier = reason == null ? null : reason.identifier;
    String mapped = identifier == null ? null : CONTRACT_TYPE_BY_REASON_IDENTIFIER.get(identifier);
    if (mapped == null) {
      report.note(
          "  bgContractType left unset for "
              + contractLabel
              + " -- ContractReason "
              + (reason == null ? "?" : reason.identifier + " (" + reason.description + ")")
              + " has no confirmed mapping");
    }
    return mapped;
  }

  private void previewAmendment(
      String companyBulstat,
      MicroinvestSourceModels.Employee src,
      MicroinvestSourceModels.Payroll annex,
      Map<Integer, MicroinvestSourceModels.ContractReason> contractReasons,
      MicroinvestImportReport report) {
    String label = "BgContractAmendment for " + src.name + " on " + annex.fromDate;
    String ground = changeGroundOf(companyBulstat, annex, contractReasons);
    if (ground == null) {
      report.skipped(
          label,
          "annex reason has no confirmed ETZ change-ground code (018/019/020/021) -- required field,"
              + " not guessed");
      report.amendmentsSkippedUnmappedGround++;
    } else {
      String auditNote =
          isOverriddenGround(companyBulstat, annex)
              ? " (ground " + ground + ", MANUAL OVERRIDE -- see change-ground-overrides.csv)"
              : " (ground " + ground + ")";
      report.created(label + auditNote);
      report.amendmentsCreated++;
    }
  }

  private void importAmendment(
      String companyBulstat,
      MicroinvestSourceModels.Payroll annex,
      EmploymentContract contract,
      Map<Integer, MicroinvestSourceModels.ContractReason> contractReasons,
      boolean dryRun,
      MicroinvestImportReport report) {
    String label =
        "BgContractAmendment on " + annex.fromDate + " (Payroll #" + annex.payrollId + ")";
    String ground = changeGroundOf(companyBulstat, annex, contractReasons);
    if (ground == null) {
      report.skipped(
          label,
          "annex reason has no confirmed ETZ change-ground code (018/019/020/021) -- required field,"
              + " not guessed");
      report.amendmentsSkippedUnmappedGround++;
      return;
    }
    String auditNote =
        isOverriddenGround(companyBulstat, annex)
            ? " (ground " + ground + ", MANUAL OVERRIDE -- see change-ground-overrides.csv)"
            : " (ground " + ground + ")";
    LocalDate changeDate = annex.fromDate != null ? annex.fromDate : annex.contractDate;
    // ⚠️ §D PARITY: looked up in BOTH modes, before the dryRun branch. A transient contract (the
    // Preflight simulation for a brand-new employee) cannot already have an amendment, so it
    // answers null without touching the repository -- Hibernate cannot resolve a transient entity
    // as a bind parameter. Everything else is a plain read and is safe in a dry run.
    BgContractAmendment existing =
        contract == null || contract.getId() == null
            ? null
            : amendmentRepo
                .all()
                .filter(
                    "self.employmentContract = :contract AND self.changeDate = :changeDate AND"
                        + " self.bgChangeGround = :ground")
                .bind("contract", contract)
                .bind("changeDate", changeDate)
                .bind("ground", ground)
                .fetchOne();
    if (existing != null) {
      report.reused(label);
      report.amendmentsReused++;
      return;
    }
    if (dryRun) {
      report.created(label + auditNote);
      report.amendmentsCreated++;
      return;
    }
    BgContractAmendment amendment = new BgContractAmendment();
    amendment.setEmploymentContract(contract);
    amendment.setChangeDate(changeDate);
    amendment.setBgChangeGround(ground);
    amendment.setNewWorkplaceEkatte(annex.ekatteCode);
    amendment.setNewTermDate(annex.contrractEndDate);
    amendmentRepo.save(amendment);
    report.created(label + auditNote);
    report.amendmentsCreated++;
  }

  /**
   * Записва края на правоотношението върху НОВ договор.
   *
   * <p>⚠️ При ОТКАЗ на резолвера се пада обратно на {@code root.toDate}. За нов договор няма ръчна
   * стойност, която да се пази, а да се остави празно би обявило за жив договор, за който изворът
   * не твърди такова нещо. Отказът се вижда в доклада с причината си — решението е на човек.
   */
  private void setChainEnd(
      EmploymentContract contract,
      MicroinvestSourceModels.Payroll root,
      MicroinvestPayrollChainResolver.ChainResolution chainEnd) {

    LocalDate end = chainEnd.isResolved() ? chainEnd.endDate : root.toDate;
    LocalDate term = chainEnd.isResolved() ? chainEnd.terminationDate : root.contrractTermDate;
    contract.setEndDate(end);
    contract.setBgTerminationDate(term);
    contract.setStatus(end == null ? STATUS_ACTIVE : STATUS_CLOSED);
  }

  private void reportNewChainEnd(
      String rootLabel,
      MicroinvestPayrollChainResolver.ChainResolution chainEnd,
      MicroinvestImportReport report) {

    if (chainEnd.isResolved()) {
      return;
    }
    report.contractEndDatesRefused++;
    report.conflict(
        rootLabel + " — край на договора",
        "веригата не е еднозначна ("
            + chainEnd.refusal
            + "); взет е краят на КОРЕНА — проверете го ръчно");
  }

  /**
   * Сверява заварения край на договора с решения по веригата и — ако се различават — го поправя.
   *
   * <p>⚠️ В сух пробег НИЩО не се записва: докладът само казва какво БИ станало. Точно това искаше
   * Preflight да покаже, преди краят на 27-те заварени договора да се пипне.
   *
   * <p>⚠️ При ОТКАЗ на резолвера заварената дата се ЗАПАЗВА — може да е въведена на ръка, а
   * двусмислената верига не е основание да се презапише.
   */
  private void applyResolvedEndDate(
      EmploymentContract contract,
      MicroinvestSourceModels.Payroll root,
      MicroinvestPayrollChainResolver.ChainResolution chainEnd,
      String rootLabel,
      boolean dryRun,
      MicroinvestImportReport report) {

    String what = rootLabel + " — край на договора";

    if (!chainEnd.isResolved()) {
      report.contractEndDatesRefused++;
      report.conflict(
          what,
          "веригата не е еднозначна ("
              + chainEnd.refusal
              + "); заварената стойност "
              + text(contract.getEndDate())
              + " се ЗАПАЗВА");
      return;
    }

    LocalDate existing = contract.getEndDate();
    LocalDate resolved = chainEnd.endDate;
    if (java.util.Objects.equals(existing, resolved)) {
      report.contractEndDatesUnchanged++;
      return;
    }

    String detail =
        text(existing)
            + " → "
            + text(resolved)
            + " (терминален Payroll #"
            + chainEnd.terminal.payrollId
            + " от верига #"
            + root.payrollId
            + ")";

    report.contractEndDatesUpdated++;
    report.updated(what, detail);

    if (dryRun) {
      return;
    }
    setChainEnd(contract, root, chainEnd);
    contractRepo.save(contract);
  }

  private static String text(LocalDate d) {
    return d == null ? "отворен" : d.toString();
  }

  /** Pure classification of a Payroll chain's deleted-root cases -- see {@link #classifyChain}. */
  enum ChainOutcome {
    /** Root not deleted: create/reuse the contract as usual. */
    PROCEED,
    /** Root deleted, no active (non-deleted) annex references it: excluded, nothing to promote. */
    EXCLUDED_DELETED,
    /**
     * Root deleted but at least one active annex references it: fail closed -- create nothing from
     * any row in the chain. Never promote the earliest surviving annex to root, never drop the
     * annexes and keep the rest either -- left for a human to review in the source data.
     */
    FAIL_CLOSED
  }

  /**
   * Package-visible (not private) so a test can exercise the three §1 cases directly, without a
   * database: root not deleted; root deleted with no active annex; root deleted with an active
   * annex (fail closed).
   */
  static ChainOutcome classifyChain(
      MicroinvestSourceModels.Payroll root, List<MicroinvestSourceModels.Payroll> annexes) {
    if (!root.isDeleted) {
      return ChainOutcome.PROCEED;
    }
    for (MicroinvestSourceModels.Payroll p : annexes) {
      if (!p.isDeleted) {
        return ChainOutcome.FAIL_CLOSED;
      }
    }
    return ChainOutcome.EXCLUDED_DELETED;
  }

  private String changeGroundOf(
      String companyBulstat,
      MicroinvestSourceModels.Payroll annex,
      Map<Integer, MicroinvestSourceModels.ContractReason> contractReasons) {
    String override = MicroinvestChangeGroundOverrides.overrideFor(companyBulstat, annex.payrollId);
    if (override != null) {
      return override;
    }
    MicroinvestSourceModels.ContractReason reason =
        annex.contractReasonId == null ? null : contractReasons.get(annex.contractReasonId);
    String identifier = reason == null ? null : reason.identifier;
    return identifier != null && VALID_CHANGE_GROUND_CODES.contains(identifier) ? identifier : null;
  }

  /**
   * Whether {@link #changeGroundOf} would resolve this annex through the manual override file,
   * rather than Microinvest's own ContractReasonID -- surfaced in the Preflight text for audit.
   */
  private boolean isOverriddenGround(String companyBulstat, MicroinvestSourceModels.Payroll annex) {
    return MicroinvestChangeGroundOverrides.overrideFor(companyBulstat, annex.payrollId) != null;
  }

  // ═══ Sick leave ═══

  private void importSickLeave(
      MicroinvestSourceModels.LeaveEvent ev,
      MicroinvestSourceModels.SickLeaveDetail detail,
      EmploymentContract contract,
      boolean dryRun,
      MicroinvestImportReport report) {
    contract = attach(EmploymentContract.class, contract);
    String label =
        "BgSickLeave " + ev.startDate + ".." + ev.endDate + " (Event #" + ev.eventId + ")";
    if (contract == null) {
      report.skipped(label, "no imported EmploymentContract found for this event's Payroll");
      return;
    }
    String absenceTypeCode = confirmedAbsenceTypeCode(ev.eventTypeName);
    if (absenceTypeCode == null) {
      report.skipped(
          label,
          "event type '"
              + ev.eventTypeName
              + "' has no confirmed BgAbsenceType mapping (only generic illness is mapped in Phase"
              + " 1)");
      return;
    }
    // §D PARITY: same lookup in both modes; a transient (simulated) contract answers null without
    // querying. Company scope comes from the contract itself -- BgSickLeave never crosses companies
    // because employmentContract.payCompany is pinned to the active one upstream.
    BgSickLeave existing =
        contract.getId() == null
            ? null
            : sickLeaveRepo
                .all()
                .filter(
                    "self.employmentContract = :contract AND self.fromDate = :from AND self.toDate"
                        + " = :to")
                .bind("contract", contract)
                .bind("from", ev.startDate)
                .bind("to", ev.endDate)
                .fetchOne();
    if (existing != null) {
      report.reused(label);
      report.sickLeaveCount++;
      return;
    }
    if (dryRun) {
      report.created(label);
      report.sickLeaveCount++;
      return;
    }
    BgAbsenceType absenceType =
        absenceTypeRepo.all().filter("self.code = :code").bind("code", absenceTypeCode).fetchOne();
    if (absenceType == null) {
      report.skipped(label, "BgAbsenceType with code '" + absenceTypeCode + "' not found in Nyxa");
      return;
    }
    BgSickLeave sickLeave = new BgSickLeave();
    sickLeave.setEmploymentContract(contract);
    sickLeave.setBgAbsenceType(absenceType);
    sickLeave.setFromDate(ev.startDate);
    sickLeave.setToDate(ev.endDate);
    sickLeave.setStatusSelect("VALIDATED");
    // Внесеният лист е история, не задача пред НОИ — „Прил. 9 за подаване“ не го показва и броячът
    // не го брои. Огледално на bgIsHistoricalImport върху фиша, Д1/Д6 и заявките за отпуск.
    sickLeave.setBgIsHistoricalImport(Boolean.TRUE);
    if (detail != null) {
      sickLeave.setBgDocumentNumber(detail.sickNote);
      sickLeave.setBgSeries(detail.series);
      sickLeave.setBgYear(detail.year);
      sickLeave.setBgIncapacityStartDate(detail.realStartDate);
      sickLeave.setBgIssueDate(detail.releaseDate);
      sickLeave.setBgEmployerReceivedNo(detail.registerNum);
      sickLeave.setBgEmployerReceivedDate(detail.registerDate);
      sickLeave.setBgIsContinuation(detail.isContinued);
    }
    sickLeaveRepo.save(sickLeave);
    report.created(label);
    report.sickLeaveCount++;
  }

  /**
   * Only "Болничен" (general illness) is mapped, to {@code sick_er} -- the code used as the
   * canonical example for a generic sick-leave input elsewhere in this module's own code (see
   * BgPayrollInput.java). Every other EnumEventTypes.IsHospital=1 label (child-care, work accident,
   * etc.) is deliberately left unmapped rather than guessed against bg_absence_type.csv's dozen
   * variants -- see gap-analysis.md.
   */
  private String confirmedAbsenceTypeCode(String eventTypeName) {
    if (eventTypeName != null && eventTypeName.trim().equals("Болничен")) {
      return "sick_er";
    }
    return null;
  }

  // ═══ Paid/unpaid annual leave (§3) ═══

  /**
   * {@code LeaveReason.importId}, not the raw source name -- see {@link
   * #confirmedLeaveReasonImportId}.
   */
  private static final String LEAVE_REASON_PAID_ANNUAL = "lr_paid_annual";

  private static final String LEAVE_REASON_UNPAID_NO_INSURANCE_SERVICE = "lr_unpaid_own_gt30";

  /**
   * Only two source {@code EnumEventTypes} names are mapped, both checked by their seeded {@code
   * BgAbsenceType} ATTRIBUTES, not by name proximity:
   *
   * <ul>
   *   <li>"Платен годишен отпуск" -&gt; {@code lr_paid_annual}: exact match, both the literal name
   *       and the attributes ({@code isPaid=true}, {@code payer=employer}, {@code
   *       bgPaidUnderArt177=true}).
   *   <li>"Неплатен отпуск без осигурителен стаж от осигурен" -&gt; {@code lr_unpaid_own_gt30}: the
   *       source name declares two facts -- no insurance-service credit and ЗОВ borne by the
   *       insured employee -- and {@code unpaid_own_gt30} is the only seeded {@code BgAbsenceType}
   *       with exactly that pair ({@code countsAsInsuranceService=false}, {@code
   *       healthContributionRule=unpaid_employee}), independent of its own le30/gt30 naming (a
   *       different, labor-service-duration axis). Everything else stays unmapped and reported
   *       rather than guessed.
   * </ul>
   */
  static String confirmedLeaveReasonImportId(String eventTypeName) {
    if (eventTypeName == null) {
      return null;
    }
    String name = eventTypeName.trim();
    if (name.equals("Платен годишен отпуск")) {
      return LEAVE_REASON_PAID_ANNUAL;
    }
    if (name.equals("Неплатен отпуск без осигурителен стаж от осигурен")) {
      return LEAVE_REASON_UNPAID_NO_INSURANCE_SERVICE;
    }
    return null;
  }

  private void tallyLeaveKind(String leaveReasonImportId, MicroinvestImportReport report) {
    if (LEAVE_REASON_PAID_ANNUAL.equals(leaveReasonImportId)) {
      report.paidLeaveCount++;
    } else if (LEAVE_REASON_UNPAID_NO_INSURANCE_SERVICE.equals(leaveReasonImportId)) {
      report.unpaidLeaveCount++;
    }
  }

  /**
   * Creates a historical {@code LeaveRequest} directly -- bypassing {@code
   * LeaveRequestSendService}/{@code ValidateService} entirely, so no notification or {@code
   * ICalendarEvent} fires and {@code LeaveLine} is never touched (see {@code
   * BgHistoricalLeaveFilter}'s javadoc for the full safety argument: the load-bearing protection
   * against this row reaching a LIVE payroll/D1/health-contribution/Приложение-№9 calculation is
   * that same shared filter, applied at the four query sites that read {@code LeaveRequest} -- this
   * method only needs to make sure the row is flagged so that filter can see it).
   *
   * <p>Duration is {@code ev.workingDays} -- Microinvest's own precomputed {@code PayrollCalendar}
   * working-day count for the event, verbatim, NOT a recomputed calendar-day span
   * (weekends/holidays inside {@code startDate..endDate} are already excluded there; see {@code
   * gap-analysis.md} for the verification against {@code EventPayments.EventPaymentDuration}).
   */
  private void importLeaveEvent(
      MicroinvestSourceModels.LeaveEvent ev,
      EmploymentContract contract,
      Company company,
      boolean dryRun,
      MicroinvestImportReport report) {
    contract = attach(EmploymentContract.class, contract);
    company = attach(Company.class, company);
    String label =
        "LeaveRequest " + ev.startDate + ".." + ev.endDate + " (Event #" + ev.eventId + ")";
    if (contract == null) {
      report.skipped(label, "no imported EmploymentContract found for this event's Payroll");
      return;
    }
    String leaveReasonImportId = confirmedLeaveReasonImportId(ev.eventTypeName);
    if (leaveReasonImportId == null) {
      report.skipped(
          label, "event type '" + ev.eventTypeName + "' has no confirmed LeaveReason mapping");
      return;
    }
    if (ev.workingDays <= 0) {
      report.skipped(
          label,
          "no authoritative working-day count in source (PayrollCalendar) -- required field, not"
              + " guessed");
      return;
    }
    LeaveReason leaveReason =
        leaveReasonRepo
            .all()
            .filter("self.importId = :id")
            .bind("id", leaveReasonImportId)
            .fetchOne();
    if (leaveReason == null) {
      report.blocked(
          label, "LeaveReason with importId '" + leaveReasonImportId + "' not found in Nyxa");
      return;
    }
    LocalDateTime fromDateT = ev.startDate.atStartOfDay();
    LocalDateTime toDateT = ev.endDate.atStartOfDay();
    // ⚠️ §D PARITY + §E COMPANY SCOPE, both in one query, in BOTH modes.
    // Parity: behind `if (dryRun) return created`, Preflight would say CREATED for leave rows an
    // Import immediately finds as REUSED.
    // Scope: `self.company = :company` is load-bearing. Employee is a global person (see
    // importOneEmployee), so employee+reason+dates alone would happily "reuse" another company's
    // leave record for the same human on the same dates -- silently under-importing this company's
    // own history. A transient employee (Preflight simulation) cannot have one: answer null.
    LeaveRequest existing =
        contract.getEmployee() == null || contract.getEmployee().getId() == null
            ? null
            : leaveRequestRepo
                .all()
                .filter(
                    "self.employee = :employee AND self.company = :company AND self.leaveReason ="
                        + " :reason AND self.fromDateT = :from AND self.toDateT = :to")
                .bind("employee", contract.getEmployee())
                .bind("company", company)
                .bind("reason", leaveReason)
                .bind("from", fromDateT)
                .bind("to", toDateT)
                .fetchOne();
    if (existing != null) {
      report.reused(label);
      tallyLeaveKind(leaveReasonImportId, report);
      return;
    }
    if (dryRun) {
      report.created(label);
      tallyLeaveKind(leaveReasonImportId, report);
      return;
    }
    LeaveRequest leaveRequest = new LeaveRequest();
    leaveRequest.setEmployee(contract.getEmployee());
    leaveRequest.setCompany(company);
    leaveRequest.setLeaveReason(leaveReason);
    leaveRequest.setFromDateT(fromDateT);
    leaveRequest.setToDateT(toDateT);
    leaveRequest.setDuration(BigDecimal.valueOf(ev.workingDays));
    leaveRequest.setStatusSelect(LeaveRequestRepository.STATUS_VALIDATED);
    leaveRequest.setValidationDateTime(fromDateT);
    leaveRequest.setBgIsHistoricalImport(true);
    leaveRequestRepo.save(leaveRequest);
    report.created(label);
    tallyLeaveKind(leaveReasonImportId, report);
  }
}

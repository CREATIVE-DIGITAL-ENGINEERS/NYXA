package com.axelor.apps.bglocalization.service.migration.microinvest;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgD1Declaration;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.repo.BgD1DeclarationRepository;
import com.axelor.apps.bglocalization.db.repo.BgD1RecordRepository;
import com.axelor.apps.bglocalization.db.repo.BgD6DeclarationRepository;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipLineRepository;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipLineTypeRepository;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipRepository;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.db.JPA;
import com.axelor.db.Model;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Microinvest TRZ Pro -&gt; Nyxa, Phase 2: historical payroll data for one company already
 * onboarded by {@link MicroinvestPilotImportService} (Phase 1) -- {@code ValueSalary}/{@code
 * ValueSalaryPrior} -&gt; historical {@code BgPaySlip}+{@code BgPaySlipLine}, {@code Dekl1Value}
 * -&gt; historical {@code BgD1Record}, {@code Dekl6Value} -&gt; historical {@code BgD6Declaration}.
 * It deliberately excludes annual tax recalculation, Приложение №15 and the formula scripts/{@code
 * Variables} themselves.
 *
 * <p>Every record this class creates is marked {@code bgIsHistoricalImport = true} and is a direct
 * transcription of an already-calculated Microinvest value -- nothing here recomputes anything
 * through Nyxa's own payroll engine ({@code BgPaySlipService}, {@code BgContributionResolver}, the
 * D1/D6 emitters). That is the whole point of Phase 2: the historical numbers are trusted as given,
 * not re-derived and not reconciled against Nyxa's own calculation at import time (see
 * migration-plan.md Phase 3 for why that comparison, when it happens, must be a read-only report,
 * not a write-back). The {@code bgIsHistoricalImport} flag is also what {@code
 * BgPaySlipController}/{@code BgD1DeclarationController}/{@code BgD6DeclarationController} check to
 * refuse regenerating or submitting these records -- see their guards.
 *
 * <p>⚠️ PERIOD LOOKUP GOES THROUGH {@code PeriodRepository} (now via {@link
 * MicroinvestPayrollCalendarService}), NOT {@code com.axelor.apps.base.service.PeriodService} --
 * {@code BgPaySlipLockedPeriodTest} enforces that the native period service is called from exactly
 * one file in this module ({@code BgPaySlipMoveService}, and only for the FISCAL ladder): the
 * module has its OWN payroll-period guard ({@code BgPaySlipResolver#refuseClosingPeriod}), and a
 * second caller of the native service for the PAYROLL ladder would be a second, uncoordinated guard
 * over the same thing. The calendar service mirrors {@code BgPaySlipService#currentOpenPeriod}'s
 * own raw-JPQL pattern instead (same predicate, no status filter -- a historical period is very
 * often already CLOSED, which the live "current open period" lookup deliberately excludes but a
 * backfill must not).
 */
@Singleton
class MicroinvestPayrollHistoryImporter {

  /**
   * The small set of Microinvest formula NAMES that are already-computed HEADER totals of a
   * payslip, not individual lines -- copied onto {@code BgPaySlip}'s own summary fields. Kept
   * separate from {@code formula-line-mapping.csv} (which is only for {@code BgPaySlipLine} rows):
   * these four are a handful of header fields, not a growing catalogue, so a CSV would be
   * over-engineering for it.
   */
  private static final Map<String, BiConsumer<BgPaySlip, BigDecimal>> HEADER_TOTAL_SETTERS =
      Map.of(
          "Общо начисления", BgPaySlip::setBgGrossTotal,
          "Данъчна основа за авансово облагане по ЗДДФЛ", BgPaySlip::setBgTaxBase,
          "Данък по ЗДДФЛ", BgPaySlip::setBgTaxAmount,
          "Сума за получаване", BgPaySlip::setBgNetTotal);

  @Inject private BgPaySlipRepository paySlipRepo;
  @Inject private BgPaySlipLineRepository paySlipLineRepo;
  @Inject private BgPaySlipLineTypeRepository paySlipLineTypeRepo;
  @Inject private BgD1DeclarationRepository d1DeclarationRepo;
  @Inject private BgD1RecordRepository d1RecordRepo;
  @Inject private BgD6DeclarationRepository d6DeclarationRepo;

  /**
   * The payroll-year (TYPE_PAYROLL) period covering {@code date}, resolved through the run's
   * calendar plan rather than by a query of this class's own.
   *
   * <p>⚠️ The plan is what turned the 296 "no payroll period open for &lt;month&gt;" skips into
   * real work: for a real import it answers with periods the plan has just created, and for a
   * Preflight it answers with a SIMULATED transient Period so the preview reports the payslip/Д1/Д6
   * as importable instead of silently dropping it. A returned Period with {@code getId() == null}
   * is simulated: it must never reach a repository query as a bind parameter.
   */
  private Period findPayrollPeriod(
      MicroinvestPayrollCalendarService.Plan periodPlan, LocalDate date) {
    return periodPlan == null ? null : periodPlan.resolve(date);
  }

  /** One employee's worth of Phase 2 history: real months, prior-period summary, and Д1. */
  void importEmployeeHistory(
      Company company,
      MicroinvestPayrollCalendarService.Plan periodPlan,
      Map<Integer, EmploymentContract> contractByPayrollId,
      MicroinvestSourceModels.Employee src,
      boolean dryRun,
      MicroinvestImportReport report) {

    Map<String, Long> realSlipByContractAndPeriod = new LinkedHashMap<>();

    importFormulaValues(
        company,
        periodPlan,
        contractByPayrollId,
        src.payslipValues,
        false,
        dryRun,
        report,
        realSlipByContractAndPeriod);
    importFormulaValues(
        company,
        periodPlan,
        contractByPayrollId,
        src.priorPeriodValues,
        true,
        dryRun,
        report,
        null);
    importD1Records(
        company,
        periodPlan,
        contractByPayrollId,
        src.d1Values,
        dryRun,
        report,
        realSlipByContractAndPeriod);
  }

  /** Company-level Декларация обр. № 6 -- called once for the whole export, not per employee. */
  void importD6History(
      Company company,
      MicroinvestPayrollCalendarService.Plan periodPlan,
      List<MicroinvestSourceModels.Dekl6FieldValue> values,
      boolean dryRun,
      MicroinvestImportReport report) {
    Map<LocalDate, List<MicroinvestSourceModels.Dekl6FieldValue>> byMonth = new LinkedHashMap<>();
    for (MicroinvestSourceModels.Dekl6FieldValue v : values) {
      if (v.date != null) {
        byMonth.computeIfAbsent(v.date, k -> new ArrayList<>()).add(v);
      }
    }
    for (Map.Entry<LocalDate, List<MicroinvestSourceModels.Dekl6FieldValue>> entry :
        byMonth.entrySet()) {
      LocalDate month = entry.getKey();
      String label = "BgD6Declaration for " + company.getName() + " " + month;
      report.sourceRecords++;
      try {
        runWriteUnit(
            dryRun,
            () -> {
              Company freshCompany = attach(Company.class, company);
              Period period = findPayrollPeriod(periodPlan, month);
              if (period == null) {
                report.blocked(
                    label,
                    "няма ТРЗ период за "
                        + month
                        + " и той не можа да бъде планиран -- виж блокерите за ТРЗ календара"
                        + " по-горе");
                return;
              }
              BgD6Declaration declaration =
                  period.getId() == null
                      ? null
                      : d6DeclarationRepo
                          .all()
                          .filter("self.company = :c AND self.bgPeriod = :p")
                          .bind("c", freshCompany)
                          .bind("p", period)
                          .fetchOne();
              boolean isNew = declaration == null;
              if (dryRun) {
                if (isNew) {
                  report.created(label);
                } else {
                  report.reused(label);
                }
                return;
              }
              if (declaration == null) {
                declaration = new BgD6Declaration();
                declaration.setCompany(freshCompany);
                declaration.setBgPeriod(period);
                declaration.setBgIsHistoricalImport(true);
                declaration.setStatusSelect("accepted");
              } else if (!Boolean.TRUE.equals(declaration.getBgIsHistoricalImport())) {
                report.skipped(
                    label, "a live (non-historical) declaration already exists for this period");
                return;
              }
              for (MicroinvestSourceModels.Dekl6FieldValue v : entry.getValue()) {
                report.d6FieldsExported++;
                if (MicroinvestD6FieldMapping.apply(
                    declaration,
                    v.fieldName,
                    Arrays.asList(v.section1, v.section2, v.section3, v.section4))) {
                  report.d6FieldsConsumed++;
                }
              }
              d6DeclarationRepo.save(declaration);
              if (isNew) {
                report.created(label);
              } else {
                report.reused(label + " (fields refreshed)");
              }
            });
      } catch (Exception e) {
        report.failed(label, "unexpected error: " + e.getMessage());
        if (!dryRun) {
          JPA.clear();
        }
      }
    }
  }

  // ═══ ValueSalary / ValueSalaryPrior -> BgPaySlip + BgPaySlipLine ═══

  private void importFormulaValues(
      Company company,
      MicroinvestPayrollCalendarService.Plan periodPlan,
      Map<Integer, EmploymentContract> contractByPayrollId,
      List<MicroinvestSourceModels.FormulaValue> values,
      boolean isPriorPeriod,
      boolean dryRun,
      MicroinvestImportReport report,
      Map<String, Long> realSlipByContractAndPeriodOut) {

    Map<String, List<MicroinvestSourceModels.FormulaValue>> byPayrollAndMonth =
        new LinkedHashMap<>();
    for (MicroinvestSourceModels.FormulaValue v : values) {
      if (v.date != null) {
        byPayrollAndMonth
            .computeIfAbsent(v.payrollId + "|" + v.date, k -> new ArrayList<>())
            .add(v);
      }
    }

    for (List<MicroinvestSourceModels.FormulaValue> group : byPayrollAndMonth.values()) {
      MicroinvestSourceModels.FormulaValue first = group.get(0);
      EmploymentContract contract = contractByPayrollId.get(first.payrollId);
      String label =
          (isPriorPeriod ? "Prior-period BgPaySlip" : "BgPaySlip")
              + " for Payroll #"
              + first.payrollId
              + " "
              + first.date;

      report.sourceRecords++;
      if (contract == null) {
        report.blocked(
            label,
            "no EmploymentContract imported for this PayrollID -- run Phase 1 for this employee"
                + " first");
        continue;
      }

      try {
        runWriteUnit(
            dryRun,
            () ->
                importOnePaySlip(
                    company,
                    periodPlan,
                    contract,
                    first.date,
                    group,
                    isPriorPeriod,
                    dryRun,
                    report,
                    label,
                    realSlipByContractAndPeriodOut));
      } catch (Exception e) {
        report.failed(label, "unexpected error, rolled back: " + e.getMessage());
        if (!dryRun) {
          JPA.clear();
        }
      }
    }
  }

  private void importOnePaySlip(
      Company company,
      MicroinvestPayrollCalendarService.Plan periodPlan,
      EmploymentContract contract,
      LocalDate month,
      List<MicroinvestSourceModels.FormulaValue> values,
      boolean isPriorPeriod,
      boolean dryRun,
      MicroinvestImportReport report,
      String label,
      Map<String, Long> realSlipByContractAndPeriodOut) {

    company = attach(Company.class, company);
    contract = attach(EmploymentContract.class, contract);
    Period period = findPayrollPeriod(periodPlan, month);
    if (period == null) {
      report.blocked(
          label,
          "няма ТРЗ период за "
              + month
              + " и той не можа да бъде планиран -- виж блокерите по-горе");
      return;
    }

    // ⚠️ contract.getId() may be null here: Preflight for a brand-new company now simulates an
    // in-memory EmploymentContract (see MicroinvestPilotImportService.importContracts) so this
    // method has something to key off of, but a transient entity cannot be resolved to a foreign
    // key by Hibernate -- querying with it as a bind parameter would throw, not just return no
    // rows. A never-saved contract cannot already have a payslip, so the answer is simply null.
    // The same now holds for the PERIOD: a Preflight-simulated one (id == null) is a period this
    // run intends to create, so nothing can already be filed against it.
    BgPaySlip existing =
        contract.getId() == null || period.getId() == null
            ? null
            : paySlipRepo
                .all()
                .filter("self.employmentContract = :c AND self.period = :p")
                .bind("c", contract)
                .bind("p", period)
                .fetchOne();

    if (dryRun) {
      // ⚠️ Row/mapped/unmapped counting for this group is NOT done here -- it runs once,
      // unconditionally, in MicroinvestPilotImportService's salary/D1/D6 coverage tally (see
      // tallySalaryAndDeclarationCoverage), which does not depend on a payroll period existing.
      // Counting it again here too would double it for every group where a period DOES exist.
      if (existing != null) {
        report.reused(label);
      } else {
        report.created(label + " (" + countMappable(values) + " mapped lines)");
      }
      return;
    }

    if (existing != null) {
      if (!Boolean.TRUE.equals(existing.getBgIsHistoricalImport())) {
        report.skipped(label, "a live (non-historical) payslip already exists for this period");
        return;
      }
      report.reused(label);
      if (!isPriorPeriod && realSlipByContractAndPeriodOut != null) {
        realSlipByContractAndPeriodOut.put(contractPeriodKey(contract, period), existing.getId());
      }
      return;
    }

    BgPaySlip slip = new BgPaySlip();
    slip.setEmploymentContract(contract);
    slip.setPeriod(period);
    slip.setStatusSelect(BgPaySlipResolver.CONFIRMED);
    slip.setBgIsHistoricalImport(true);
    slip.setNote(
        isPriorPeriod
            ? "MIGRATION: Microinvest ValueSalaryPrior -- prior-period/mid-year-onboarding summary,"
                + " not a computed payslip. Not linked to any BgD1Record."
            : "MIGRATION: imported from Microinvest ValueSalary (already-calculated historical"
                + " result, not recomputed by Nyxa).");
    slip = paySlipRepo.save(slip);

    int mappedCount = 0;
    for (MicroinvestSourceModels.FormulaValue v : values) {
      report.salaryRowsExported++;
      // Normalized to BgPaySlipLine.bgAmount's own @Digits(integer=16, fraction=2) scale HERE, at
      // the single point every Phase 2 monetary value flows through on its way into a BgPaySlip --
      // NOT a one-formula special case. Some Microinvest FormulaSalary values (daily-rate style
      // computations) carry more than 2 decimals; HALF_UP is standard monetary rounding, matching
      // how these figures already round for display in Microinvest itself.
      BigDecimal absValue = v.value == null ? null : normalizeMonetary(v.value.abs());
      BiConsumer<BgPaySlip, BigDecimal> headerSetter = HEADER_TOTAL_SETTERS.get(v.formulaName);
      if (headerSetter != null && absValue != null) {
        headerSetter.accept(slip, absValue);
      }
      String lineTypeCode = MicroinvestFormulaClassification.lineTypeCodeFor(v.formulaName);
      if (lineTypeCode == null) {
        // A name consumed as a BgPaySlip HEADER field (HEADER_TOTAL_SETTERS, just above) is not
        // "unmapped" even though it never becomes a BgPaySlipLine -- it already has a real,
        // curated Nyxa destination, just not a line-item one.
        if (headerSetter == null) {
          recordIfUnmapped(v.formulaName, v.value, report);
        } else if (absValue != null) {
          report.mappedComponent(absValue);
        }
        continue;
      }
      BgPaySlipLineType lineType =
          paySlipLineTypeRepo
              .all()
              .filter("self.code = :code")
              .bind("code", lineTypeCode)
              .fetchOne();
      if (lineType == null) {
        report.note(
            "  NOTE: BgPaySlipLineType '"
                + lineTypeCode
                + "' not found -- skipping "
                + v.formulaName);
        continue;
      }
      if (absValue == null || absValue.signum() == 0) {
        continue;
      }
      BgPaySlipLine line = new BgPaySlipLine();
      line.setPaySlip(slip);
      line.setBgPaySlipLineType(lineType);
      line.setBgLineNumber(lineType.getNumber());
      line.setName(lineType.getName());
      line.setBgSign(lineType.getBgSign());
      line.setBgIsTaxable(lineType.getBgIsTaxable());
      line.setBgIsInsuranceIncome(lineType.getBgIsInsuranceIncome());
      line.setBgIsPermanentCharacter(lineType.getBgIsPermanentCharacter());
      line.setBgFormsGrossWage(lineType.getBgFormsGrossWage());
      line.setBgAmount(absValue);
      paySlipLineRepo.save(line);
      mappedCount++;
      report.mappedComponent(absValue);
    }
    paySlipRepo.save(slip);
    report.created(label + " (" + mappedCount + " lines)");
    if (!isPriorPeriod && realSlipByContractAndPeriodOut != null) {
      realSlipByContractAndPeriodOut.put(contractPeriodKey(contract, period), slip.getId());
    }
  }

  /**
   * Rounds to {@code BgPaySlipLine.bgAmount}'s own domain scale ({@code @Digits(integer=18,
   * fraction=2)}) -- some Microinvest {@code FormulaSalary} values (daily-rate style computations)
   * carry more than 2 decimals, which the DB constraint rejects outright at persist time. Standard
   * monetary HALF_UP rounding, applied once at this single boundary every Phase 2 value crosses on
   * its way into a {@code BgPaySlip}/{@code BgPaySlipLine} -- never widens the column, never
   * touches the source {@code FormulaSalary} value itself (only this copy of it).
   */
  static BigDecimal normalizeMonetary(BigDecimal value) {
    return value == null ? null : value.setScale(2, java.math.RoundingMode.HALF_UP);
  }

  private void recordIfUnmapped(
      String formulaName, BigDecimal value, MicroinvestImportReport report) {
    if (formulaName != null
        && MicroinvestFormulaClassification.lineTypeCodeFor(formulaName) == null) {
      report.unmappedComponent(
          formulaName, MicroinvestFormulaClassification.categorize(formulaName), value);
    }
  }

  private long countMappable(List<MicroinvestSourceModels.FormulaValue> values) {
    return values.stream()
        .filter(v -> MicroinvestFormulaClassification.lineTypeCodeFor(v.formulaName) != null)
        .filter(v -> v.value != null && v.value.signum() != 0)
        .count();
  }

  private String contractPeriodKey(EmploymentContract contract, Period period) {
    return contract.getId() + "|" + period.getId();
  }

  // ═══ Dekl1Value -> BgD1Declaration + BgD1Record ═══

  private void importD1Records(
      Company company,
      MicroinvestPayrollCalendarService.Plan periodPlan,
      Map<Integer, EmploymentContract> contractByPayrollId,
      List<MicroinvestSourceModels.Dekl1FieldValue> values,
      boolean dryRun,
      MicroinvestImportReport report,
      Map<String, Long> realSlipByContractAndPeriod) {

    Map<String, List<MicroinvestSourceModels.Dekl1FieldValue>> byPayrollAndMonth =
        new LinkedHashMap<>();
    for (MicroinvestSourceModels.Dekl1FieldValue v : values) {
      if (v.date != null) {
        byPayrollAndMonth
            .computeIfAbsent(v.payrollId + "|" + v.date, k -> new ArrayList<>())
            .add(v);
      }
    }

    for (List<MicroinvestSourceModels.Dekl1FieldValue> group : byPayrollAndMonth.values()) {
      MicroinvestSourceModels.Dekl1FieldValue first = group.get(0);
      EmploymentContract contract = contractByPayrollId.get(first.payrollId);
      String label = "BgD1Record for Payroll #" + first.payrollId + " " + first.date;

      report.sourceRecords++;
      if (contract == null) {
        report.blocked(
            label,
            "no EmploymentContract imported for this PayrollID -- run Phase 1 for this employee"
                + " first");
        continue;
      }

      try {
        runWriteUnit(
            dryRun,
            () ->
                importOneD1Record(
                    company,
                    periodPlan,
                    contract,
                    first.date,
                    group,
                    dryRun,
                    report,
                    label,
                    realSlipByContractAndPeriod));
      } catch (Exception e) {
        report.failed(label, "unexpected error, rolled back: " + e.getMessage());
        if (!dryRun) {
          JPA.clear();
        }
      }
    }
  }

  private void importOneD1Record(
      Company company,
      MicroinvestPayrollCalendarService.Plan periodPlan,
      EmploymentContract contract,
      LocalDate month,
      List<MicroinvestSourceModels.Dekl1FieldValue> values,
      boolean dryRun,
      MicroinvestImportReport report,
      String label,
      Map<String, Long> realSlipByContractAndPeriod) {

    company = attach(Company.class, company);
    contract = attach(EmploymentContract.class, contract);
    Period period = findPayrollPeriod(periodPlan, month);
    if (period == null) {
      report.blocked(
          label,
          "няма ТРЗ период за "
              + month
              + " и той не можа да бъде планиран -- виж блокерите по-горе");
      return;
    }

    // ⚠️ §D PARITY: Preflight must not answer CREATED here without looking. The reuse lookup below
    // runs in both modes; a simulated period/contract (id == null) short-circuits it to "cannot
    // exist yet"
    // rather than querying with a transient bind parameter.
    BgD1Declaration declaration =
        period.getId() == null
            ? null
            : d1DeclarationRepo
                .all()
                .filter("self.company = :c AND self.bgDataPeriod = :p")
                .bind("c", company)
                .bind("p", period)
                .fetchOne();

    if (dryRun) {
      BgD1Record existingRecord =
          declaration == null || contract.getId() == null
              ? null
              : d1RecordRepo
                  .all()
                  .filter("self.bgD1Declaration = :d AND self.paySlip.employmentContract = :c")
                  .bind("d", declaration)
                  .bind("c", contract)
                  .fetchOne();
      if (existingRecord != null) {
        report.reused(label);
      } else {
        report.created(label);
      }
      return;
    }

    // Re-fetched by id (never the entity carried over from the OTHER transaction that created it
    // in importOnePaySlip, potentially minutes and many records earlier) -- see #attach.
    Long slipId =
        realSlipByContractAndPeriod == null
            ? null
            : realSlipByContractAndPeriod.get(contractPeriodKey(contract, period));
    BgPaySlip slip = slipId == null ? null : JPA.find(BgPaySlip.class, slipId);

    if (declaration == null) {
      declaration = new BgD1Declaration();
      declaration.setCompany(company);
      declaration.setBgDataPeriod(period);
      declaration.setBgIsHistoricalImport(true);
      declaration.setStatusSelect("accepted");
      declaration = d1DeclarationRepo.save(declaration);
    } else if (!Boolean.TRUE.equals(declaration.getBgIsHistoricalImport())) {
      report.skipped(label, "a live (non-historical) declaration already exists for this period");
      return;
    }

    BgD1Record existing =
        slip == null
            ? null
            : d1RecordRepo
                .all()
                .filter("self.bgD1Declaration = :d AND self.paySlip = :s")
                .bind("d", declaration)
                .bind("s", slip)
                .fetchOne();
    if (existing != null) {
      report.reused(label);
      return;
    }

    BgD1Record record = new BgD1Record();
    record.setBgD1Declaration(declaration);
    record.setPaySlip(slip);
    if (slip == null) {
      report.note(
          "  NOTE: "
              + label
              + " has no matching historical BgPaySlip (no ValueSalary for this month) --"
              + " created without a payslip reference.");
    }
    for (MicroinvestSourceModels.Dekl1FieldValue v : values) {
      report.d1FieldsExported++;
      if (MicroinvestD1FieldMapping.apply(record, v.formRefNo, v.fieldName, v.value)) {
        report.d1FieldsConsumed++;
      }
    }
    d1RecordRepo.save(record);
    report.created(label);
  }

  private void runWriteUnit(boolean dryRun, Runnable work) {
    if (dryRun) {
      work.run();
    } else {
      JPA.runInTransaction(work);
    }
  }

  /**
   * Re-fetches a possibly-stale reference by its own id within the CURRENT persistence context --
   * see {@link MicroinvestPilotImportService#attach} (same fix, duplicated here since each Phase 2
   * record already runs in its own transaction on the SAME shared EntityManager as every other
   * record: after an earlier record's transaction rolls back and {@link JPA#clear()} runs in the
   * catch block below, {@code company}/{@code contract} captured from an outer loop become
   * detached, and passing a detached entity as a *-to-one association on a new entity is exactly
   * what "detached entity passed to persist" means.
   *
   * <p>⚠️ Takes the entity type EXPLICITLY -- a managed entity from a repository query is very
   * often a Hibernate proxy ({@code Company$HibernateProxy$...}), which has no persister of its
   * own; {@code JPA.find(entity.getClass(), id)} on a proxy throws "Unable to locate persister"
   * instead of re-fetching. A transient entity is returned unchanged.
   */
  private static <T extends Model> T attach(Class<T> type, T entity) {
    return entity == null || entity.getId() == null ? entity : JPA.find(type, entity.getId());
  }

  /**
   * Preflight-only: mapped/unmapped salary rows and D1/D6 field consumption, computed directly
   * against the export JSON -- no database, no {@code EmploymentContract}/{@code Period} needed.
   *
   * <p>This is deliberately independent of {@link #importFormulaValues}/{@link #importD1Records}/
   * {@link #importD6History}'s own per-record dry-run counting (which those methods skip precisely
   * to avoid double-counting against this pass -- see the comment in {@code importOnePaySlip}):
   * whether a given month's {@code BgPaySlip}/{@code BgD1Record}/{@code BgD6Declaration} can
   * actually be CREATED depends on a Nyxa payroll fiscal year/period existing for that month, which
   * this migration does not create and Preflight cannot fabricate -- but whether a source VALUE
   * would map to a known Nyxa field is a pure data-shape question, independent of whether that
   * period exists yet. Answering it here means a from-scratch company (no periods configured at
   * all) still gets a real, complete salary/D1/D6 coverage report.
   */
  void tallySalaryAndDeclarationCoverage(
      MicroinvestSourceModels.ExportRoot export, MicroinvestImportReport report) {
    for (MicroinvestSourceModels.Employee e : export.employees) {
      tallyFormulaCoverage(e.payslipValues, report);
      tallyFormulaCoverage(e.priorPeriodValues, report);
      BgD1Record throwawayD1 = new BgD1Record();
      for (MicroinvestSourceModels.Dekl1FieldValue v : e.d1Values) {
        report.d1FieldsExported++;
        if (MicroinvestD1FieldMapping.apply(throwawayD1, v.formRefNo, v.fieldName, v.value)) {
          report.d1FieldsConsumed++;
        }
      }
    }
    BgD6Declaration throwawayD6 = new BgD6Declaration();
    for (MicroinvestSourceModels.Dekl6FieldValue v : export.d6Values) {
      report.d6FieldsExported++;
      if (MicroinvestD6FieldMapping.apply(
          throwawayD6,
          v.fieldName,
          Arrays.asList(v.section1, v.section2, v.section3, v.section4))) {
        report.d6FieldsConsumed++;
      }
    }
  }

  private void tallyFormulaCoverage(
      List<MicroinvestSourceModels.FormulaValue> values, MicroinvestImportReport report) {
    for (MicroinvestSourceModels.FormulaValue v : values) {
      report.salaryRowsExported++;
      BigDecimal abs = v.value == null ? null : v.value.abs();
      // Consumed either as a BgPaySlipLine (formula-line-mapping.csv) or as a BgPaySlip HEADER
      // field (HEADER_TOTAL_SETTERS) -- both are real, curated Nyxa destinations. See the matching
      // comment in importOnePaySlip.
      if (MicroinvestFormulaClassification.lineTypeCodeFor(v.formulaName) != null
          || HEADER_TOTAL_SETTERS.containsKey(v.formulaName)) {
        report.mappedComponent(abs);
      } else {
        recordIfUnmapped(v.formulaName, v.value, report);
      }
    }
  }
}

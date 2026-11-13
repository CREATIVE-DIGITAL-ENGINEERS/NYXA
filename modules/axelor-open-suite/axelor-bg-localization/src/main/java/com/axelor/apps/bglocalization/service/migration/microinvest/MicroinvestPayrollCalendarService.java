package com.axelor.apps.bglocalization.service.migration.microinvest;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.base.service.YearService;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Creates the PAYROLL calendar (Year.typeSelect = {@link YearRepository#TYPE_PAYROLL} + its 12
 * monthly Periods) that the Microinvest history import needs, for the ACTIVE company only.
 *
 * <p>⚠️ A MISSING PAYROLL PERIOD IS A PREREQUISITE, NOT A PER-RECORD SKIP. Treated as an ordinary
 * skip, it drops every BgPaySlip, BgD1Record and BgD6Declaration of the month for the same reason
 * ("no payroll period open for &lt;month&gt;") while the report still says "failed: 0". It is one
 * thing to either create or refuse over. Payroll years are not business data an operator can be
 * expected to pre-create for every historical month of a migration; they are a mechanical
 * consequence of the dates in the file.
 *
 * <p>⚠️ PAYROLL ≠ FISCAL. Nothing here reads, modifies or replaces an accounting/fiscal year
 * ({@link YearRepository#TYPE_FISCAL}): both ladders live in the same table and are told apart only
 * by {@code typeSelect}, so every query below pins it. An existing PAYROLL year is reused as-is and
 * never widened, re-dated or re-generated -- if one exists but does not cover a month the export
 * needs, that is reported as a blocker for a human, not silently patched.
 *
 * <p>Creation goes through the stock {@link YearService#createYear}/{@link
 * YearService#generatePeriodsForYear} -- the same code path as Configuration → Years → Generate
 * periods -- so the rows are indistinguishable from hand-made ones (period names {@code 01/2024}…,
 * codes {@code 01/2024_&lt;COMPANY CODE&gt;}, status inherited from the year).
 */
@Singleton
public class MicroinvestPayrollCalendarService {

  @Inject private PeriodRepository periodRepo;
  @Inject private YearRepository yearRepo;
  @Inject private YearService yearService;

  /**
   * What the calendar needs to look like for one export/company pair: which months the export
   * actually references, which of them have no PAYROLL period yet, which calendar years would
   * therefore be created, and which years cannot be created because a partial PAYROLL year is
   * already sitting on them.
   *
   * <p>Doubles as the PERIOD RESOLVER both phases use: in a real import (after {@link #apply})
   * every month resolves to a real, saved {@link Period}; in a Preflight the planned months resolve
   * to a SIMULATED transient Period (id == null) so the preflight can report the payslips and
   * declarations as importable instead of as hundreds of skips. Every reader must treat {@code
   * period.getId() == null} as "cannot already exist" rather than passing it to a repository query
   * -- the same rule as the simulated EmploymentContract in {@link MicroinvestPilotImportService}.
   */
  public final class Plan {

    private final Company company;
    private final TreeSet<YearMonth> requiredMonths = new TreeSet<>();
    private final TreeSet<YearMonth> missingMonths = new TreeSet<>();
    private final TreeSet<Integer> yearsToCreate = new TreeSet<>();
    private final Map<Integer, String> blockedYears = new LinkedHashMap<>();
    private final Map<YearMonth, Period> simulated = new LinkedHashMap<>();
    private boolean applied;

    private Plan(Company company) {
      this.company = company;
    }

    public boolean hasBlockers() {
      return !blockedYears.isEmpty();
    }

    public List<Integer> getYearsToCreate() {
      return new ArrayList<>(yearsToCreate);
    }

    public Map<Integer, String> getBlockedYears() {
      return blockedYears;
    }

    public int getRequiredMonthCount() {
      return requiredMonths.size();
    }

    public int getMissingMonthCount() {
      return missingMonths.size();
    }

    /**
     * The PAYROLL period covering {@code date} for this plan's company: a real one if it exists,
     * otherwise the simulated stand-in for a month this plan intends to create. Null only for a
     * month that is neither -- which, after {@link #apply} succeeded, cannot happen for any month
     * the export references.
     */
    public Period resolve(LocalDate date) {
      if (date == null) {
        return null;
      }
      Period real = findPayrollPeriod(date, company);
      if (real != null) {
        return real;
      }
      return simulated.get(YearMonth.from(date));
    }

    public boolean isApplied() {
      return applied;
    }
  }

  // ═══ Planning (read-only) ═══

  /**
   * The distinct months the export's payroll history references -- payslips, prior-period
   * summaries, Д1 and Д6 alike. Pure: derived from the JSON dates only, no database, so a Preflight
   * on a company with no calendar at all still reports exactly which years it would create.
   */
  static TreeSet<YearMonth> requiredMonths(MicroinvestSourceModels.ExportRoot export) {
    TreeSet<YearMonth> months = new TreeSet<>();
    for (MicroinvestSourceModels.Employee e : export.employees) {
      for (MicroinvestSourceModels.FormulaValue v : e.payslipValues) {
        addMonth(months, v.date);
      }
      for (MicroinvestSourceModels.FormulaValue v : e.priorPeriodValues) {
        addMonth(months, v.date);
      }
      for (MicroinvestSourceModels.Dekl1FieldValue v : e.d1Values) {
        addMonth(months, v.date);
      }
    }
    for (MicroinvestSourceModels.Dekl6FieldValue v : export.d6Values) {
      addMonth(months, v.date);
    }
    return months;
  }

  private static void addMonth(TreeSet<YearMonth> months, LocalDate date) {
    if (date != null) {
      months.add(YearMonth.from(date));
    }
  }

  /**
   * Reads the current calendar and works out what is missing. Never writes -- Preflight calls only
   * this, Import calls this and then {@link #apply}.
   */
  Plan plan(Company company, MicroinvestSourceModels.ExportRoot export) {
    Plan plan = new Plan(company);
    plan.requiredMonths.addAll(requiredMonths(export));

    for (YearMonth month : plan.requiredMonths) {
      if (findPayrollPeriod(month.atDay(1), company) == null) {
        plan.missingMonths.add(month);
      }
    }

    for (YearMonth month : plan.missingMonths) {
      int calendarYear = month.getYear();
      if (plan.yearsToCreate.contains(calendarYear)
          || plan.blockedYears.containsKey(calendarYear)) {
        continue;
      }
      Year overlapping = findOverlappingPayrollYear(calendarYear, company);
      if (overlapping == null) {
        plan.yearsToCreate.add(calendarYear);
      } else {
        plan.blockedYears.put(
            calendarYear,
            "вече съществува ТРЗ година '"
                + overlapping.getCode()
                + "' ("
                + overlapping.getFromDate()
                + ".."
                + overlapping.getToDate()
                + ") за тази компания, но тя няма период за "
                + month
                + " -- допълнете периодите ръчно (Configuration -> Years) и стартирайте отново;"
                + " импортът НЕ преправя заварена година");
      }
    }

    // Simulated stand-ins, for Preflight only: every missing month whose year this plan would
    // really create. A blocked year gets none -- its months stay unresolvable on purpose, so
    // Preflight shows the same blocker the real import would refuse over.
    for (YearMonth month : plan.missingMonths) {
      if (!plan.yearsToCreate.contains(month.getYear())) {
        continue;
      }
      Period simulated = new Period();
      simulated.setFromDate(month.atDay(1));
      simulated.setToDate(month.atEndOfMonth());
      simulated.setName(String.format("%02d", month.getMonthValue()) + "/" + month.getYear());
      plan.simulated.put(month, simulated);
    }
    return plan;
  }

  // ═══ Creation (idempotent) ═══

  /**
   * Creates the planned PAYROLL years and their 12 monthly periods, then re-verifies that every
   * month the export needs now resolves to a real period. Idempotent: a year another run (or an
   * operator) created in the meantime is found by {@link #findOverlappingPayrollYear} and reused,
   * not duplicated -- the DB's own unique constraint on (code, company, typeSelect) is the
   * backstop.
   *
   * @throws AxelorException if a year cannot be created or a required month still has no period
   *     afterwards -- the caller must abort the whole import at that point, before the employee
   *     phase, rather than continue into a partial one.
   */
  void apply(Plan plan, MicroinvestImportReport report) throws AxelorException {
    if (plan.hasBlockers()) {
      throw new IllegalStateException(
          "не може да се създаде ТРЗ календар: " + plan.blockedYears.values());
    }
    for (Integer calendarYear : plan.yearsToCreate) {
      Year existing = findOverlappingPayrollYear(calendarYear, plan.company);
      if (existing != null) {
        report.reused("ТРЗ година " + calendarYear + " (" + existing.getCode() + ")");
        continue;
      }
      createPayrollYear(plan.company, calendarYear);
      report.created("ТРЗ година " + calendarYear + " + 12 месечни периода");
    }
    plan.simulated.clear();
    plan.applied = true;

    List<String> stillMissing = new ArrayList<>();
    for (YearMonth month : plan.requiredMonths) {
      if (findPayrollPeriod(month.atDay(1), plan.company) == null) {
        stillMissing.add(month.toString());
      }
    }
    if (!stillMissing.isEmpty()) {
      throw new IllegalStateException(
          "ТРЗ периоди липсват след създаването на календара: " + stillMissing);
    }
  }

  /**
   * One full calendar PAYROLL year with its 12 monthly periods, through the stock {@link
   * YearService} -- the same code path as Configuration → Years → Generate periods. The seam a test
   * overrides to exercise {@link #apply}'s idempotency without a database.
   */
  Year createPayrollYear(Company company, int calendarYear) throws AxelorException {
    Year year =
        yearService.createYear(
            company,
            String.valueOf(calendarYear),
            String.valueOf(calendarYear),
            LocalDate.of(calendarYear, 1, 1),
            LocalDate.of(calendarYear, 12, 31),
            YearRepository.DURATION_MONTH,
            YearRepository.TYPE_PAYROLL);
    year.setStatusSelect(YearRepository.STATUS_OPENED);
    yearService.generatePeriodsForYear(year);
    return year;
  }

  // ═══ Lookups ═══

  /**
   * The PAYROLL period covering {@code date} for {@code company}. Mirrors {@code
   * BgPaySlipService#currentOpenPeriod}'s raw-repository pattern (no status filter -- a historical
   * period is very often already CLOSED) and deliberately does NOT go through {@code PeriodService}
   * -- see {@link MicroinvestPayrollHistoryImporter}'s class javadoc for why this module keeps the
   * payroll ladder off the native period service.
   *
   * <p>A company-owned year wins over a company-less one when both cover the date: a global payroll
   * year would otherwise let two companies share the same period rows.
   */
  Period findPayrollPeriod(LocalDate date, Company company) {
    List<Period> candidates =
        periodRepo
            .all()
            .filter(
                "self.year.typeSelect = :type AND self.fromDate <= :date AND self.toDate >= :date"
                    + " AND (self.year.company IS NULL OR self.year.company = :company)")
            .bind("type", YearRepository.TYPE_PAYROLL)
            .bind("date", date)
            .bind("company", company)
            .fetch();
    Period fallback = null;
    for (Period p : candidates) {
      if (p.getYear() != null && p.getYear().getCompany() != null) {
        return p;
      }
      fallback = p;
    }
    return fallback;
  }

  /**
   * Any PAYROLL year of this company overlapping the given calendar year, or null. {@code fetch(1)}
   * rather than {@code fetchOne()} on purpose: a legacy setup with two overlapping payroll years is
   * bad data, but it must produce the "already exists, complete it by hand" blocker below, not a
   * NonUniqueResultException out of the middle of an import.
   */
  Year findOverlappingPayrollYear(int calendarYear, Company company) {
    List<Year> found =
        yearRepo
            .all()
            .filter(
                "self.typeSelect = :type AND self.company = :company AND self.fromDate <= :yearEnd"
                    + " AND self.toDate >= :yearStart")
            .bind("type", YearRepository.TYPE_PAYROLL)
            .bind("company", company)
            .bind("yearStart", LocalDate.of(calendarYear, 1, 1))
            .bind("yearEnd", LocalDate.of(calendarYear, 12, 31))
            .order("fromDate")
            .fetch(1);
    return found.isEmpty() ? null : found.get(0);
  }
}

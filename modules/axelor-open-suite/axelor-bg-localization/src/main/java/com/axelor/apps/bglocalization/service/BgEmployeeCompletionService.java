/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 *
 * The shared "Завършете" (Finish) step of the staged Employee form.
 */
package com.axelor.apps.bglocalization.service;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.EventsPlanning;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.WeeklyPlanning;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.hr.db.repo.EmployeeRepository;
import com.axelor.apps.hr.service.app.AppHumanResourceService;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;

/**
 * The one place that knows what "finishing" an Employee means, shared by the UI button and the
 * Microinvest importer.
 *
 * <p>⚠️ WHAT "Завършете" ACTUALLY IS, traced end to end rather than guessed. The Bulgarian Employee
 * form is the stock staged form: {@code employee-form} (axelor-human-resource/views/Employee.xml)
 * declares a four-step wizard driven by {@code stepByStepSelect} -- the green arrows are {@code
 * action-employee-record-next-step} / {@code action-employee-record-previous-step} (± 1) and the
 * "Завършете" button is {@code action-employee-record-finish-sbs}, whose entire body is {@code
 * stepByStepSelect = 5}. Every ordinary panel of the form, including this module's own {@code
 * bgLocalizationPanel} (Employee-bg.xml), is {@code showIf="stepByStepSelect == 5"}; the wizard
 * panels are {@code showIf="stepByStepSelect < 5"}. So an Employee row is not "broken" before
 * finishing -- it is simply still IN the wizard, and the classic form that shows ЕГН, осигуряване
 * and the contracts tab is hidden behind that one integer.
 *
 * <p>That is why an imported employee looks empty without this service: {@code
 * Employee.stepByStepSelect} defaults to 1 (domain default), and an importer that creates the row
 * directly through the repository never goes near the form, so every person sits on step 1 of a
 * wizard nobody has opened, and the operator has to click through four arrows and "Завършете" per
 * person to see the data that is in fact already imported.
 *
 * <p>This class is the smallest reusable piece of the completion rules, and the button goes through
 * it too ({@code action-bg-localization-employee-method-finish-sbs} in Employee-bg.xml), so the two
 * can never drift: the UI and the importer finish an employee the same way, by definition.
 *
 * <p>Beyond the step itself it applies the defaults the wizard's own {@code
 * action-employee-record-default} (run on {@code onNew}) would have applied -- which a
 * repository-created employee also never received -- and only where the field is still empty, so
 * finishing an employee a second time changes nothing.
 */
@Singleton
public class BgEmployeeCompletionService {

  /** {@code stepByStepSelect} value that means "out of the wizard, classic form" -- see javadoc. */
  public static final int STEP_COMPLETED = 5;

  @Inject private EmployeeRepository employeeRepo;
  @Inject private PartnerRepository partnerRepo;
  @Inject private AppBaseService appBaseService;
  @Inject private AppHumanResourceService appHumanResourceService;

  /**
   * An employee still inside the staged wizard -- i.e. one whose ordinary fields the operator
   * cannot see yet.
   *
   * <p>⚠️ {@code getStepByStepSelect()} is Axelor-generated and NULL-COALESCES to 0, so an "is it
   * set?" null check would be dead code here; the comparison against {@link #STEP_COMPLETED} is the
   * only honest test, and it covers a never-initialized row (0) as well as one mid-wizard (1..4).
   */
  public static boolean isIncomplete(Employee employee) {
    return employee != null && employee.getStepByStepSelect() < STEP_COMPLETED;
  }

  /**
   * The defaults {@code action-employee-record-default} reads from the app configuration and the
   * active company. Passed in rather than looked up inside {@link #applyCompletion} so the decision
   * logic stays a pure function a test can drive without Guice or a database.
   */
  public static final class Defaults {
    public String timeLoggingPreferenceSelect;
    public BigDecimal dailyWorkHours;
    public Boolean negativeValueLeave;
    public WeeklyPlanning weeklyPlanning;
    public EventsPlanning publicHolidayEventsPlanning;
  }

  /**
   * Pure, in-memory completion: exactly what clicking through the wizard and pressing "Завършете"
   * leaves behind. Package-visible so a test can drive it without Guice/JPA.
   *
   * <ul>
   *   <li>the company relationship: {@code contactPartner.companySet} gains {@code company} and the
   *       partner is flagged {@code isEmployee} -- without it the person is invisible in every
   *       company-filtered HR view;
   *   <li>{@code mainEmploymentContract}: kept if already set, otherwise the latest-starting
   *       contract OF THIS COMPANY (never another company's -- that is the whole point of scoping);
   *   <li>{@code hireDate}: the earliest start date among this company's contracts, if still empty;
   *   <li>the {@code onNew} defaults, only where the field is still empty;
   *   <li>{@code stepByStepSelect = 5} last.
   * </ul>
   *
   * @return true if anything changed (so the caller can report reused-vs-completed honestly)
   */
  static boolean applyCompletion(Employee employee, Company company, Defaults defaults) {
    if (employee == null) {
      return false;
    }
    boolean changed = false;
    // The onNew defaults belong to CREATION, so they are applied only while the record is still
    // inside the wizard. Re-finishing an already-completed employee must never push an app-config
    // value back over what the operator has since chosen -- and for the boolean/decimal fields it
    // could not tell the two apart anyway: Axelor's generated getters null-coalesce
    // (getNegativeValueLeave() -> FALSE, getDailyWorkHours() -> ZERO), so "still empty" is not
    // observable on an existing row.
    boolean wasIncomplete = isIncomplete(employee);

    Partner partner = employee.getContactPartner();
    if (partner != null) {
      if (!Boolean.TRUE.equals(partner.getIsEmployee())) {
        partner.setIsEmployee(true);
        changed = true;
      }
      if (company != null
          && (partner.getCompanySet() == null || !partner.getCompanySet().contains(company))) {
        partner.addCompanySetItem(company);
        changed = true;
      }
    }

    if (employee.getEmploymentContractList() != null && company != null) {
      EmploymentContract latest = null;
      LocalDate earliestStart = null;
      for (EmploymentContract contract : employee.getEmploymentContractList()) {
        if (!company.equals(contract.getPayCompany())) {
          continue;
        }
        if (latest == null
            || Comparator.nullsFirst(Comparator.<LocalDate>naturalOrder())
                    .compare(contract.getStartDate(), latest.getStartDate())
                > 0) {
          latest = contract;
        }
        if (contract.getStartDate() != null
            && (earliestStart == null || contract.getStartDate().isBefore(earliestStart))) {
          earliestStart = contract.getStartDate();
        }
      }
      if (employee.getMainEmploymentContract() == null && latest != null) {
        employee.setMainEmploymentContract(latest);
        changed = true;
      }
      if (employee.getHireDate() == null && earliestStart != null) {
        employee.setHireDate(earliestStart);
        changed = true;
      }
    }

    if (defaults != null && wasIncomplete) {
      if (defaults.timeLoggingPreferenceSelect != null
          && (employee.getTimeLoggingPreferenceSelect() == null
              || employee.getTimeLoggingPreferenceSelect().isBlank())) {
        employee.setTimeLoggingPreferenceSelect(defaults.timeLoggingPreferenceSelect);
        changed = true;
      }
      if (defaults.dailyWorkHours != null
          && defaults.dailyWorkHours.signum() != 0
          && employee.getDailyWorkHours().signum() == 0) {
        employee.setDailyWorkHours(defaults.dailyWorkHours);
        changed = true;
      }
      if (Boolean.TRUE.equals(defaults.negativeValueLeave)
          && !Boolean.TRUE.equals(employee.getNegativeValueLeave())) {
        employee.setNegativeValueLeave(true);
        changed = true;
      }
      if (defaults.weeklyPlanning != null && employee.getWeeklyPlanning() == null) {
        employee.setWeeklyPlanning(defaults.weeklyPlanning);
        changed = true;
      }
      if (defaults.publicHolidayEventsPlanning != null
          && employee.getPublicHolidayEventsPlanning() == null) {
        employee.setPublicHolidayEventsPlanning(defaults.publicHolidayEventsPlanning);
        changed = true;
      }
    }

    if (isIncomplete(employee)) {
      employee.setStepByStepSelect(STEP_COMPLETED);
      changed = true;
    }
    return changed;
  }

  /** The app/company defaults, read once per call; missing app configuration is simply skipped. */
  public Defaults defaultsFor(Company company) {
    Defaults defaults = new Defaults();
    try {
      if (appBaseService.getAppBase() != null) {
        defaults.timeLoggingPreferenceSelect =
            appBaseService.getAppBase().getTimeLoggingPreferenceSelect();
        defaults.dailyWorkHours = appBaseService.getAppBase().getDailyWorkHours();
      }
      if (appHumanResourceService.getAppLeave() != null) {
        defaults.negativeValueLeave =
            appHumanResourceService.getAppLeave().getAllowNegativeLeaveEmployees();
      }
    } catch (RuntimeException e) {
      // App configuration not installed (a fresh instance, or the leave app disabled): the
      // wizard's own action silently skips those fields too (`if="__config__.app.isApp('leave')"`).
      // Never swallowed further than this -- everything else below still applies.
      defaults.negativeValueLeave = null;
    }
    if (company != null) {
      defaults.weeklyPlanning = company.getWeeklyPlanning();
      defaults.publicHolidayEventsPlanning = company.getPublicHolidayEventsPlanning();
    }
    return defaults;
  }

  /**
   * Completes and SAVES -- the importer's entry point. Idempotent: an already-completed employee is
   * left untouched and {@code false} is returned.
   */
  @Transactional(rollbackOn = {Exception.class})
  public boolean complete(Employee employee, Company company) {
    boolean changed = applyCompletion(employee, company, defaultsFor(company));
    if (changed) {
      if (employee.getContactPartner() != null && employee.getContactPartner().getId() != null) {
        partnerRepo.save(employee.getContactPartner());
      }
      employeeRepo.save(employee);
    }
    return changed;
  }

  /**
   * Completes WITHOUT saving -- the form controller's entry point, so the button keeps its existing
   * {@code validate,…,save} semantics and the values land through the form's own save.
   */
  public boolean completeInMemory(Employee employee, Company company) {
    return applyCompletion(employee, company, defaultsFor(company));
  }
}

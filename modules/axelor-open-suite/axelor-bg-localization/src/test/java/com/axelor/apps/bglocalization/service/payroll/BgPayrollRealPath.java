package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.bglocalization.db.BgAbsenceType;
import com.axelor.apps.bglocalization.db.BgD1Declaration;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgNkid;
import com.axelor.apps.bglocalization.db.BgNkpd;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgWorkingDayService;
import com.axelor.apps.bglocalization.service.d1.BgD1DeclarationKind;
import com.axelor.apps.bglocalization.service.d1.BgD1GenerationService;
import com.axelor.apps.bglocalization.service.d1.BgD1PeriodLockRules;
import com.axelor.apps.bglocalization.testsupport.BgTestApp;
import com.axelor.apps.bglocalization.web.BgPaySlipController;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.hr.db.LeaveReason;
import com.axelor.apps.hr.db.LeaveRequest;
import com.axelor.apps.hr.db.repo.LeaveReasonRepository;
import com.axelor.apps.hr.db.repo.LeaveRequestRepository;
import com.axelor.apps.hr.db.repo.TimesheetRepository;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.auth.db.Group;
import com.axelor.auth.db.User;
import com.axelor.data.Listener;
import com.axelor.data.csv.CSVImporter;
import com.axelor.db.JPA;
import com.axelor.db.Model;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.persistence.EntityManager;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;

/**
 * РЕАЛНИЯТ ПЪТ НА ФИША И НА Д1 ЗА ИНТЕГРАЦИОННИ СВИДЕТЕЛИ — сийдът на App Install, „Преизчисли“ →
 * „Потвърди“ → {@link BgD1GenerationService#generate}, без тестова транзакция около действията.
 *
 * <p>Данните са ФИКТИВНИ: „Нюкса Тест ЕООД“, служител P0045, без ЕГН и без реални имена. Вид
 * осигурен 01, НКИД 91.11 (ТЗПБ 0,5 %), НКПД гр. 9, роден след 1959, ТЕЛК ≥ 50 %, 4 ч/ден при
 * законови 8; основна 310,10, клас 1,2 % × 31 г. признат трудов стаж, парично СБКО 7,85 (перо 271,
 * ръчна сума).
 *
 * <p>⚠️ Справочните данни са сийдът ({@code apps/init-data/bg-localization-config.xml} през {@link
 * CSVImporter}) — същите ставки, пера и календар, които получава всяка инсталация. Зарежда се
 * веднъж на СХЕМА: проверката е по базата, не по статичен флаг, защото всеки клас получава нова
 * схема.
 */
final class BgPayrollRealPath {

  static final BigDecimal BASE = new BigDecimal("310.10");
  static final BigDecimal SBKO = new BigDecimal("7.85");

  /** Входовете на сийда, които пътят на фиша и на Д1 чете — в реда на конфигурацията. */
  private static final String[] SEED_FILES = {
    "bg_contribution_fund.csv",
    "bg_insurance_contribution.csv",
    "bg_insured_type.csv",
    "bg_income_type.csv",
    "bg_payroll_parameter.csv",
    "bg_tzpb_rate.csv",
    "bg_min_insurance_income.csv",
    "bg_min_insurance_income_amount.csv",
    "bg_absence_type.csv",
    "bg_compensation_type.csv",
    "bg_social_expense_cap.csv",
    "bg_pay_slip_line_type.csv",
    "bg_pay_slip_line_type_contribution.csv",
    "bg_pay_slip_line_type_compensation.csv",
    "bg_pay_slip_line_type_social.csv",
    "bg_nkpd.csv",
    "bg_nkid.csv",
    "bg_nkid_mod_mapping.csv",
    "bg_eventsPlanning.csv",
    "bg_eventsPlanningLine.csv"
  };

  /** Ид-тата на редовете, записани със SQL — далеч над поредиците на теста. */
  private static long sqlIds = 9_000_000L;

  private BgPayrollRealPath() {}

  // ═══ ФИКСТУРАТА ══════════════════════════════════════════════════════════

  static final class Fixture {
    Long companyId;
    Long employeeId;
    Long contractId;
    Long yearId;
    String tag;
  }

  /**
   * Дружество, заплатна година 2026, лице и договор. Периодите и фишовете — отделно.
   *
   * @param serviceMonths деклариран осигурителен стаж в месеци; {@code null} — НЕ е деклариран
   */
  static Fixture fixture(String letter, Integer serviceMonths) {
    return fixture(letter, serviceMonths, SERVICE_AS_OF);
  }

  /**
   * Датата, към която е деклариран стажът във фикстурата по подразбиране — краят на септември 2026:
   * свидетелите мерят болнични от октомври и ноември, тоест месец за удостоверяване между нея и
   * началото им няма.
   */
  static final LocalDate SERVICE_AS_OF = LocalDate.of(2026, 9, 30);

  /**
   * @param serviceAsOf към коя дата е деклариран стажът
   */
  static Fixture fixture(String letter, Integer serviceMonths, LocalDate serviceAsOf) {
    Fixture f = new Fixture();
    f.tag = letter + System.nanoTime();
    JPA.runInTransaction(
        () -> {
          Company company = new Company();
          company.setName("Нюкса Тест ЕООД " + f.tag);
          company.setCode("NX" + f.tag);
          company.setBgNkid(nkid("91.11"));
          company.setBgSeniorityRatePct(new BigDecimal("1.2"));
          JPA.em().persist(company);
          // Партньорът на дружеството е в самото дружество — след него, по реда на persist.
          Partner companyPartner = new Partner();
          companyPartner.setName("Нюкса Тест ЕООД " + f.tag);
          companyPartner.setCompany(company);
          JPA.em().persist(companyPartner);
          company.setPartner(companyPartner);
          // Редиците на дружеството ги създава BgTrzCompanySequenceListener при persist; номерът
          // от тях чете AppBase, която всяка инсталация има.
          BgTestApp.ensureAppBase();
          f.companyId = company.getId();

          Year year = new Year();
          year.setName("2026 " + f.tag);
          year.setCode("Y" + f.tag);
          year.setFromDate(LocalDate.of(2026, 1, 1));
          year.setToDate(LocalDate.of(2026, 12, 31));
          year.setCompany(company);
          year.setTypeSelect(YearRepository.TYPE_PAYROLL);
          JPA.em().persist(year);
          f.yearId = year.getId();

          Partner person = new Partner();
          person.setName("P0045 " + f.tag);
          person.setCompany(company);
          JPA.em().persist(person);
          Employee employee = new Employee();
          employee.setName("P0045 " + f.tag);
          employee.setContactPartner(person);
          employee.setBgLaborCategory("3");
          employee.setBgBornAfter1959(true);
          employee.setBirthDate(LocalDate.of(1975, 5, 5));
          employee.setBgDisabilityRelief(true);
          employee.setBgDisabilityValidUntil(LocalDate.of(2027, 12, 31));
          employee.setBgInsuranceServiceDeclared(serviceMonths != null);
          employee.setBgInsuranceServiceMonths(serviceMonths == null ? 0 : serviceMonths);
          employee.setBgInsuranceServiceAsOf(serviceMonths == null ? null : serviceAsOf);
          JPA.em().persist(employee);
          f.employeeId = employee.getId();

          f.contractId = newContract(employee, company).getId();
        });
    return f;
  }

  /** Договорът на фикстурата — същите условия за всяко лице и дружество. */
  private static EmploymentContract newContract(Employee employee, Company company) {
    EmploymentContract contract = new EmploymentContract();
    contract.setEmployee(employee);
    contract.setPayCompany(company);
    contract.setStartDate(LocalDate.of(2026, 7, 1));
    contract.setBgPriorExperienceYears(31);
    contract.setBgPriorExperienceMonths(0);
    contract.setBgPriorExperienceDays(0);
    contract.setBgInsuredTypeCode("01");
    contract.setBgNkpd(nkpd("91110001"));
    contract.setBgHoursPerDay(new BigDecimal("4.00"));
    contract.setBgLegalHoursPerDay(new BigDecimal("8.00"));
    contract.setBgDaysPerWeek(new BigDecimal("5.00"));
    contract.setBgBaseSalary(BASE);
    JPA.em().persist(contract);
    return contract;
  }

  /** ВТОРИ договор на СЪЩОТО лице в СЪЩОТО дружество — същите периоди, друг носител. */
  static Fixture secondContract(Fixture f) {
    Fixture g = new Fixture();
    g.companyId = f.companyId;
    g.employeeId = f.employeeId;
    g.yearId = f.yearId;
    g.tag = f.tag + "b";
    JPA.runInTransaction(
        () ->
            g.contractId =
                newContract(
                        JPA.em().find(Employee.class, f.employeeId),
                        JPA.em().find(Company.class, f.companyId))
                    .getId());
    return g;
  }

  /** ДРУГО дружество (със своя заплатна година) и договор в него за СЪЩОТО лице. */
  static Fixture otherCompany(Fixture f, String letter) {
    Fixture other = fixture(letter, 12);
    other.employeeId = f.employeeId;
    JPA.runInTransaction(
        () ->
            JPA.em()
                .find(EmploymentContract.class, other.contractId)
                .setEmployee(JPA.em().find(Employee.class, f.employeeId)));
    return other;
  }

  /** Отворен период на заплатната година на фикстурата. */
  static Long period(Fixture f, LocalDate from, LocalDate to) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          Period p = new Period();
          p.setName(String.format("%02d/%d %s", from.getMonthValue(), from.getYear(), f.tag));
          p.setFromDate(from);
          p.setToDate(to);
          p.setYear(JPA.em().find(Year.class, f.yearId));
          p.setStatusSelect(PeriodRepository.STATUS_OPENED);
          JPA.em().persist(p);
          id[0] = p.getId();
        });
    return id[0];
  }

  /** Сменя статуса на периода — затваряне преди корекция. */
  static void periodStatus(Long periodId, int status) {
    JPA.runInTransaction(() -> JPA.em().find(Period.class, periodId).setStatusSelect(status));
  }

  /** Чернова с ръчното перо 271 (парично СБКО 7,85). Всичко останало пишат генераторите. */
  static Long slip(Fixture f, Long periodId, String suffix) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          BgPaySlip slip = new BgPaySlip();
          slip.setEmploymentContract(JPA.em().find(EmploymentContract.class, f.contractId));
          slip.setPeriod(JPA.em().find(Period.class, periodId));
          slip.setReference("FISH-" + suffix + "-" + f.tag);
          slip.setStatusSelect(BgPaySlipResolver.DRAFT);
          slip.setBgIsCorrection(false);
          slip.setBgIsDeletion(false);
          slip.setBgAutoFillBaseSalary(true);
          slip.setBgNoOtherPayerIncome(true);
          slip.setBgPaySlipLineList(new ArrayList<>());
          BgPaySlipLineType sbko = lineType("pl_soc_ne_v_natura_dohod");
          BgPaySlipLine line = new BgPaySlipLine();
          line.setBgPaySlipLineType(sbko);
          line.setBgLineNumber(sbko.getNumber());
          line.setName(sbko.getName());
          line.setBgSign(sbko.getBgSign());
          line.setBgIsInsuranceIncome(sbko.getBgIsInsuranceIncome());
          line.setBgIsTaxable(sbko.getBgIsTaxable());
          line.setBgFormsGrossWage(sbko.getBgFormsGrossWage());
          line.setBgIsPermanentCharacter(sbko.getBgIsPermanentCharacter());
          line.setBgIsArt17Base(sbko.getBgIsArt17Base());
          line.setBgAmount(SBKO);
          line.setPaySlip(slip);
          slip.getBgPaySlipLineList().add(line);
          JPA.em().persist(slip);
          id[0] = slip.getId();
        });
    return id[0];
  }

  /** Валидиран болничен лист — вид отсъствие от сийда. */
  static Long sickSheet(Fixture f, String absenceCode, LocalDate from, LocalDate to, String no) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          BgSickLeave sheet = new BgSickLeave();
          sheet.setEmploymentContract(JPA.em().find(EmploymentContract.class, f.contractId));
          sheet.setBgAbsenceType(absenceType(absenceCode));
          sheet.setFromDate(from);
          sheet.setToDate(to);
          sheet.setBgIncapacityStartDate(from);
          sheet.setBgIsContinuation(false);
          sheet.setBgDocumentNumber(no);
          sheet.setStatusSelect(BgPayrollInputBuilder.SICK_LEAVE_VALIDATED);
          sheet.setBgEmployerReceivedDate(from.plusDays(7));
          JPA.em().persist(sheet);
          id[0] = sheet.getId();
        });
    return id[0];
  }

  /**
   * Деклариран осигурителен стаж на лицето — смяна МЕЖДУ две пресмятания: нова декларация „{@code
   * months} месеца към {@code asOf}“.
   */
  static void serviceMonths(Fixture f, int months, LocalDate asOf) {
    JPA.runInTransaction(
        () -> {
          Employee e = JPA.em().find(Employee.class, f.employeeId);
          e.setBgInsuranceServiceDeclared(true);
          e.setBgInsuranceServiceMonths(months);
          e.setBgInsuranceServiceAsOf(asOf);
        });
  }

  /**
   * ПОТВЪРДЕН ФИШ ЗА МИНАЛ МЕСЕЦ — историята, от която се извежда стажът към дата. Записва се
   * пряко: предмет на свидетелите е присъдата към началото на листа, а месеците преди него са
   * данни, не пресмятане. Годината на периода се създава при нужда (заплатна, на дружеството).
   */
  static Long confirmedSlip(Fixture f, java.time.YearMonth month) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          Company company = JPA.em().find(Company.class, f.companyId);
          List<Year> years =
              JPA.em()
                  .createQuery(
                      "SELECT y FROM Year y WHERE y.company = :c AND y.fromDate <= :d"
                          + " AND y.toDate >= :d AND y.typeSelect = :t",
                      Year.class)
                  .setParameter("c", company)
                  .setParameter("d", month.atDay(1))
                  .setParameter("t", YearRepository.TYPE_PAYROLL)
                  .getResultList();
          Year year;
          if (years.isEmpty()) {
            year = new Year();
            year.setName(month.getYear() + " " + f.tag);
            year.setCode("Y" + month.getYear() + f.tag);
            year.setFromDate(LocalDate.of(month.getYear(), 1, 1));
            year.setToDate(LocalDate.of(month.getYear(), 12, 31));
            year.setCompany(company);
            year.setTypeSelect(YearRepository.TYPE_PAYROLL);
            JPA.em().persist(year);
          } else {
            year = years.get(0);
          }
          Period p = new Period();
          p.setName(String.format("%02d/%d %s", month.getMonthValue(), month.getYear(), f.tag));
          p.setFromDate(month.atDay(1));
          p.setToDate(month.atEndOfMonth());
          p.setYear(year);
          p.setStatusSelect(PeriodRepository.STATUS_CLOSED);
          JPA.em().persist(p);
          BgPaySlip slip = new BgPaySlip();
          slip.setEmploymentContract(JPA.em().find(EmploymentContract.class, f.contractId));
          slip.setPeriod(p);
          slip.setReference("HIST-" + month + "-" + f.tag);
          slip.setStatusSelect(BgPaySlipResolver.CONFIRMED);
          slip.setBgIsCorrection(false);
          slip.setBgIsDeletion(false);
          slip.setBgLeaveDaysOutside(BigDecimal.ZERO);
          JPA.em().persist(slip);
          id[0] = slip.getId();
        });
    return id[0];
  }

  /** Валидирана заявка за отпуск в дни, по вид отсъствие от сийда. */
  static void leave(Fixture f, String absenceCode, LocalDate from, LocalDate to, int days) {
    JPA.runInTransaction(
        () -> {
          Employee employee = JPA.em().find(Employee.class, f.employeeId);
          LeaveReason reason = new LeaveReason();
          reason.setName(absenceCode + " " + employee.getName());
          reason.setBgAbsenceType(absenceType(absenceCode));
          reason.setLeaveReasonTypeSelect(LeaveReasonRepository.TYPE_SELECT_EVERY_YEAR);
          reason.setUnitSelect(1); // hr.leave.reason.unit.select: 1 = дни
          JPA.em().persist(reason);
          LeaveRequest l = new LeaveRequest();
          l.setEmployee(employee);
          l.setCompany(JPA.em().find(Company.class, f.companyId));
          l.setLeaveReason(reason);
          l.setFromDateT(from.atStartOfDay());
          l.setToDateT(to.atTime(23, 59, 59));
          l.setDuration(BigDecimal.valueOf(days));
          l.setStatusSelect(LeaveRequestRepository.STATUS_VALIDATED);
          l.setBgIsHistoricalImport(false);
          JPA.em().persist(l);
        });
  }

  /**
   * ВАЛИДИРАН отчет за време: 4 ч на всеки работен ден по календара на сийда, без дните между
   * {@code absentFrom} и {@code absentTo} (включително; {@code null} — без отсъствие). При непълно
   * работно време НЕВД чл. 1 ал. 4 иска отработени ЧАСОВЕ — без отчет вноските се отказват
   * поименно.
   *
   * <p>⚠️ SQL, А НЕ persist: проследяването на AOS Timesheet оценява {@code
   * __config__.app.getApp('timesheet')} при commit, а тестовата база няма App — артефакт на HSQLDB,
   * не на ТРЗ. Пътят, който ЧЕТЕ отчета ({@code BgPayrollInputBuilder#workedHoursByDate}), е
   * непроменен.
   */
  static void timesheet(
      Fixture f, LocalDate from, LocalDate to, LocalDate absentFrom, LocalDate absentTo) {
    JPA.runInTransaction(
        () -> {
          Set<LocalDate> holidays =
              Beans.get(BgWorkingDayService.class).officialHolidaysIn(from, to);
          long sheetId = ++sqlIds;
          JPA.em()
              .createNativeQuery(
                  "INSERT INTO hr_timesheet (id, version, employee, company, from_date, to_date,"
                      + " status_select) VALUES (?, 0, ?, ?, ?, ?, ?)")
              .setParameter(1, sheetId)
              .setParameter(2, f.employeeId)
              .setParameter(3, f.companyId)
              .setParameter(4, from)
              .setParameter(5, to)
              .setParameter(6, TimesheetRepository.STATUS_VALIDATED)
              .executeUpdate();
          for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            boolean absent = absentFrom != null && !d.isBefore(absentFrom) && !d.isAfter(absentTo);
            if (d.getDayOfWeek() == DayOfWeek.SATURDAY
                || d.getDayOfWeek() == DayOfWeek.SUNDAY
                || holidays.contains(d)
                || absent) {
              continue;
            }
            JPA.em()
                .createNativeQuery(
                    "INSERT INTO hr_timesheet_line (id, version, timesheet, employee, date_val,"
                        + " hours_duration, duration) VALUES (?, 0, ?, ?, ?, 4.00, 4.00)")
                .setParameter(1, ++sqlIds)
                .setParameter(2, sheetId)
                .setParameter(3, f.employeeId)
                .setParameter(4, d)
                .executeUpdate();
          }
        });
  }

  // ═══ ДЕЙСТВИЯТА — както ги вика action-method ═══════════════════════════

  /** „Преизчисли“ или „Потвърди“: контролерът от Guice, без транзакция. */
  static ActionResponse press(String action, Long slipId) {
    Map<String, Object> context = new HashMap<>();
    BgPaySlip current = fresh(em -> em.find(BgPaySlip.class, slipId));
    context.put("id", slipId);
    context.put("version", current.getVersion());
    context.put("_model", BgPaySlip.class.getName());
    Map<String, Object> data = new HashMap<>();
    data.put("context", context);
    ActionRequest request = new ActionRequest();
    request.setModel(BgPaySlip.class.getName());
    request.setData(data);
    ActionResponse response = new ActionResponse();
    assertFalse(JPA.em().getTransaction().isActive(), "действието тръгва БЕЗ транзакция");
    // ⚠️ Контекстът НЕ се чисти: Partner носи @Formula за PostgreSQL, която HSQLDB не изпълнява
    // (виж BgSickNoteArrivalAtomicityTest). Проверките четат през отделен EntityManager.
    BgPaySlipController controller = Beans.get(BgPaySlipController.class);
    if ("recalculate".equals(action)) {
      controller.recalculate(request, response);
    } else if ("confirm".equals(action)) {
      controller.confirm(request, response);
    } else if ("correct".equals(action)) {
      controller.correct(request, response);
    } else {
      throw new IllegalArgumentException(action);
    }
    return response;
  }

  /** Резултатът от един месец: фишът, записът по Д1 и следата на действията. */
  static final class Result {
    BgPaySlip slip;
    BgD1Record rec;
    Long declarationId;
    String trace;
  }

  /** Преизчисли → Потвърди → нова РЕДОВНА Д1 за периода → generate. */
  static Result run(Fixture f, Long slipId, Long periodId) {
    ActionResponse recalc = press("recalculate", slipId);
    ActionResponse confirm = press("confirm", slipId);
    BgPaySlip slip = fresh(em -> em.find(BgPaySlip.class, slipId));
    StringBuilder trace = new StringBuilder();
    trace.append("преизчисли: ").append(recalc.getData()).append('\n');
    trace.append("потвърди: ").append(confirm.getData()).append('\n');
    trace.append("статус: ").append(slip.getStatusSelect()).append('\n');
    trace.append("откази на генераторите: ").append(slip.getBgGeneratorGaps()).append('\n');
    assertEquals(
        BgPaySlipResolver.CONFIRMED, slip.getStatusSelect(), "фишът е потвърден: " + trace);
    Long declId = declaration(f, periodId, BgD1DeclarationKind.REGULAR, null);
    return d1(declId, slip, trace);
  }

  /**
   * НАП Е ПРИЕЛА РЕДОВНАТА за периода — прието подаване на Д1/Д6. Без него коригиращата Д1 се
   * отказва: корекция преди приемането влиза в редовната като обикновен запис (случай (а)).
   */
  static void accepted(Fixture f, Long periodId) {
    JPA.runInTransaction(
        () -> {
          NapSubmission submission = new NapSubmission();
          submission.setCompany(JPA.em().find(Company.class, f.companyId));
          submission.setPeriod(JPA.em().find(Period.class, periodId));
          submission.setServiceCodeSelect(BgD1PeriodLockRules.SERVICE_CODE);
          submission.setServiceDocumentTypeSelect(BgD1PeriodLockRules.DOCUMENT_TYPE);
          submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_ACCEPTED);
          JPA.em().persist(submission);
        });
  }

  /**
   * ОТВОРЕН ПЕРИОД, КОЙТО СЪДЪРЖА ДНЕС — там сяда нетната разлика от корекцията ({@code
   * BgPaySlipService#currentOpenPeriod}). Годината се създава при нужда, за да не зависи свидетелят
   * от датата, на която се пуска.
   */
  static void openToday(Fixture f) {
    java.time.YearMonth now = java.time.YearMonth.now();
    JPA.runInTransaction(
        () -> {
          Company company = JPA.em().find(Company.class, f.companyId);
          List<Year> years =
              JPA.em()
                  .createQuery(
                      "SELECT y FROM Year y WHERE y.company = :c AND y.fromDate <= :d"
                          + " AND y.toDate >= :d AND y.typeSelect = :t",
                      Year.class)
                  .setParameter("c", company)
                  .setParameter("d", now.atDay(1))
                  .setParameter("t", YearRepository.TYPE_PAYROLL)
                  .getResultList();
          Year year = years.isEmpty() ? null : years.get(0);
          if (year == null) {
            year = new Year();
            year.setName(now.getYear() + " " + f.tag);
            year.setCode("Y" + now.getYear() + f.tag);
            year.setFromDate(LocalDate.of(now.getYear(), 1, 1));
            year.setToDate(LocalDate.of(now.getYear(), 12, 31));
            year.setCompany(company);
            year.setTypeSelect(YearRepository.TYPE_PAYROLL);
            JPA.em().persist(year);
          }
          List<Period> open =
              JPA.em()
                  .createQuery(
                      "SELECT p FROM Period p WHERE p.year = :y AND p.fromDate = :d", Period.class)
                  .setParameter("y", year)
                  .setParameter("d", now.atDay(1))
                  .getResultList();
          if (open.isEmpty()) {
            Period p = new Period();
            p.setName(String.format("%02d/%d %s", now.getMonthValue(), now.getYear(), f.tag));
            p.setFromDate(now.atDay(1));
            p.setToDate(now.atEndOfMonth());
            p.setYear(year);
            p.setStatusSelect(PeriodRepository.STATUS_OPENED);
            JPA.em().persist(p);
          } else {
            open.get(0).setStatusSelect(PeriodRepository.STATUS_OPENED);
          }
        });
  }

  /** Нова декларация Д1; {@code corrects} — редовната, която коригиращата поправя. */
  static Long declaration(Fixture f, Long periodId, String kind, Long corrects) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          BgD1Declaration d = new BgD1Declaration();
          d.setCompany(JPA.em().find(Company.class, f.companyId));
          d.setBgDataPeriod(JPA.em().find(Period.class, periodId));
          d.setBgDeclarationKind(kind);
          if (corrects != null) {
            d.setCorrectsPrevious(JPA.em().find(BgD1Declaration.class, corrects));
          }
          JPA.em().persist(d);
          id[0] = d.getId();
        });
    return id[0];
  }

  /** {@link BgD1GenerationService#generate} и записът на фиша — четен през отделен EM. */
  static Result d1(Long declId, BgPaySlip slip, StringBuilder trace) {
    Beans.get(BgD1GenerationService.class).generate(declId);
    BgD1Declaration d = fresh(em -> em.find(BgD1Declaration.class, declId));
    trace.append("Д1 откази: ").append(d.getBgRefusalReport()).append('\n');
    trace.append("Д1 проверки: ").append(d.getBgValidationReport()).append('\n');
    Result r = new Result();
    r.slip = slip;
    r.declarationId = declId;
    r.rec =
        fresh(
            em -> {
              List<BgD1Record> found =
                  em.createQuery(
                          "SELECT r FROM BgD1Record r WHERE r.bgD1Declaration.id = :id"
                              + " AND r.paySlip.id = :slip",
                          BgD1Record.class)
                      .setParameter("id", declId)
                      .setParameter("slip", slip.getId())
                      .getResultList();
              return found.isEmpty() ? null : found.get(0);
            });
    r.trace = trace.toString();
    assertNotNull(r.rec, "Д1 запис за фиша има: " + r.trace);
    return r;
  }

  /** Отчетът на Д1 — за проверка на находки. */
  static String validationReport(Long declId) {
    return fresh(em -> em.find(BgD1Declaration.class, declId)).getBgValidationReport();
  }

  // ═══ СИЙДЪТ — веднъж на схема, по конфигурацията на App Install ══════════

  static synchronized void seed() throws IOException {
    loadSeed();
    login();
  }

  /**
   * Само сийдът — БЕЗ потребителя-администратор. За свидетели, които мерят и ролята: те закачат
   * свой потребител през {@code BgRoleLogin}.
   */
  static synchronized void seedData() throws IOException {
    loadSeed();
  }

  private static final String USER_CODE = "trz-witness";

  /**
   * ПОТРЕБИТЕЛ-АДМИНИСТРАТОР НА НИШКАТА. „Потвърди“ минава през {@code CapabilityGate}, а без
   * субект на Shiro той отговаря „Not logged in.“. Ролите НЕ са предмет на тези свидетели — те
   * мерят пресмятането и Д1; затова потребителят е в група {@code admins} ({@code
   * AuthUtils#isAdmin}).
   */
  private static void login() {
    Long existing =
        JPA.em()
            .createQuery("SELECT COUNT(u) FROM User u WHERE u.code = :c", Long.class)
            .setParameter("c", USER_CODE)
            .getSingleResult();
    if (existing == 0) {
      JPA.runInTransaction(
          () -> {
            Group admins =
                JPA
                    .em()
                    .createQuery("SELECT g FROM Group g WHERE g.code = 'admins'", Group.class)
                    .getResultList()
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (admins == null) {
              admins = new Group("admins", "Administrators");
              JPA.em().persist(admins);
            }
            User user = new User(USER_CODE, "Свидетел ТРЗ");
            user.setPassword("x"); // задължителна колона; входът е през субекта отдолу
            user.setGroup(admins);
            JPA.em().persist(user);
          });
    }
    // ⚠️ СУБЕКТ, ЗАКАЧЕН НА НИШКАТА, А НЕ ВХОД С ПАРОЛА: realm-ът на инсталацията
    // (Keycloak/Entra) не е предмет тук. AuthUtils#getUser чете само принципала — кода на
    // потребителя.
    Subject subject =
        new Subject.Builder(SecurityUtils.getSecurityManager())
            .principals(new SimplePrincipalCollection(USER_CODE, "trz-witness"))
            .authenticated(true)
            .buildSubject();
    ThreadContext.bind(subject);
  }

  /**
   * Откача субекта от нишката. ⚠️ ЗАДЪЛЖИТЕЛНО след всеки тест: интеграционните класове вървят в
   * ЕДНА JVM и закачен администратор би променил поведението на чужд клас, който мери точно „Not
   * logged in.“ или обхвата по дружество.
   */
  static void logout() {
    ThreadContext.unbindSubject();
  }

  private static void loadSeed() throws IOException {
    boolean present =
        JPA.em()
                .createQuery(
                    "SELECT COUNT(t) FROM BgPaySlipLineType t WHERE t.code = 'pl_osnovna'",
                    Long.class)
                .getSingleResult()
            > 0;
    if (present) {
      return;
    }
    String config = classpathText("/apps/init-data/bg-localization-config.xml");
    Matcher head = Pattern.compile("<csv-inputs[^>]*>").matcher(config);
    assertTrue(head.find());
    StringBuilder cfg = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    cfg.append(head.group()).append('\n');
    Path dir = Files.createTempDirectory("bg-payroll-real-path-seed");
    for (String file : SEED_FILES) {
      cfg.append(inputOf(config, file)).append('\n');
      Files.write(
          dir.resolve(file),
          classpathText("/apps/init-data/en/" + file).getBytes(StandardCharsets.UTF_8));
    }
    cfg.append("</csv-inputs>\n");
    Path configFile = dir.resolve("config.xml");
    Files.write(configFile, cfg.toString().getBytes(StandardCharsets.UTF_8));

    CSVImporter importer = new CSVImporter(configFile.toString(), dir.toString());
    List<String> errors = new ArrayList<>();
    importer.addListener(
        new Listener() {
          @Override
          public void imported(Model bean) {}

          @Override
          public void imported(Integer total, Integer success) {}

          @Override
          public void handle(Model bean, Exception e) {
            errors.add(String.valueOf(bean) + ": " + e);
          }
        });
    importer.run();
    assertEquals(new ArrayList<String>(), errors, "сийдът се зарежда без грешки");
  }

  private static String inputOf(String config, String file) {
    Matcher m =
        Pattern.compile(
                "<input file=\"" + Pattern.quote(file) + "\"[^>]*?(?:/>|[^/]>.*?</input>)",
                Pattern.DOTALL)
            .matcher(config);
    assertTrue(m.find(), file);
    return m.group();
  }

  private static String classpathText(String name) throws IOException {
    try (InputStream in = BgPayrollRealPath.class.getResourceAsStream(name)) {
      assertNotNull(in, name);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static BgAbsenceType absenceType(String code) {
    return JPA.em()
        .createQuery("SELECT t FROM BgAbsenceType t WHERE t.code = :c", BgAbsenceType.class)
        .setParameter("c", code)
        .getSingleResult();
  }

  static BgPaySlipLineType lineType(String code) {
    return JPA.em()
        .createQuery("SELECT t FROM BgPaySlipLineType t WHERE t.code = :c", BgPaySlipLineType.class)
        .setParameter("c", code)
        .setMaxResults(1)
        .getSingleResult();
  }

  private static BgNkid nkid(String code) {
    return JPA.em()
        .createQuery("SELECT n FROM BgNkid n WHERE n.code = :c", BgNkid.class)
        .setParameter("c", code)
        .setMaxResults(1)
        .getSingleResult();
  }

  private static BgNkpd nkpd(String code) {
    return JPA.em()
        .createQuery("SELECT n FROM BgNkpd n WHERE n.code = :c", BgNkpd.class)
        .setParameter("c", code)
        .setMaxResults(1)
        .getSingleResult();
  }

  // ═══ ЧЕТЕНЕТО ═══════════════════════════════════════════════════════════

  static <T> T fresh(Function<EntityManager, T> read) {
    EntityManager em = JPA.em().getEntityManagerFactory().createEntityManager();
    try {
      T value = read.apply(em);
      if (value instanceof BgPaySlip) {
        for (BgPaySlipLine l : ((BgPaySlip) value).getBgPaySlipLineList()) {
          if (l.getBgPaySlipLineType() != null) {
            l.getBgPaySlipLineType().getCode();
          }
        }
      }
      return value;
    } finally {
      em.close();
    }
  }

  static BgPaySlipLine lineOf(BgPaySlip slip, Object key) {
    for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
      BgPaySlipLineType t = l.getBgPaySlipLineType();
      if (key instanceof String && t != null && key.equals(t.getCode())) {
        return l;
      }
      if (key instanceof Integer && key.equals(l.getBgLineNumber())) {
        return l;
      }
    }
    return null;
  }

  static BigDecimal amountOf(BgPaySlip slip, Object key) {
    BgPaySlipLine l = lineOf(slip, key);
    return l == null ? null : l.getBgAmount();
  }

  static BigDecimal quantityOf(BgPaySlip slip, Object key) {
    BgPaySlipLine l = lineOf(slip, key);
    return l == null ? null : l.getBgQuantity();
  }

  /** Перото 516 със своя база по ЗЗО чл. 40 ал. 1 т. 5. */
  static BigDecimal healthT5(BgPaySlip slip) {
    for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
      if (com.axelor.apps.bglocalization.service.d1.BgD1ContributionAggregator.BASE_ART40_1_5
          .equals(l.getBgHealthBaseKind())) {
        return l.getBgAmount();
      }
    }
    return null;
  }

  /** Числото, сравнено по стойност; празното е провал със същия текст. */
  static void pin(String expected, Object actual, String what) {
    assertNotNull(actual, what + ": празно");
    assertEquals(
        0,
        new BigDecimal(expected).compareTo(new BigDecimal(String.valueOf(actual))),
        what + ": очаквано " + expected + ", намерено " + actual);
  }

  static String lines(BgPaySlip slip) {
    StringBuilder sb = new StringBuilder();
    for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
      sb.append(
          String.format(
              "  %3s %-28s %s к-во=%s ставка=%s сума=%s%n",
              l.getBgLineNumber(),
              l.getBgPaySlipLineType() == null ? l.getName() : l.getBgPaySlipLineType().getCode(),
              l.getBgSign(),
              l.getBgQuantity(),
              l.getBgRate(),
              l.getBgAmount()));
    }
    return sb.toString();
  }

  /** Работните дни между две дати по календара на сийда — за самопроверка на фикстурата. */
  static int workingDays(LocalDate from, LocalDate to) {
    Set<LocalDate> holidays =
        new HashSet<>(Beans.get(BgWorkingDayService.class).officialHolidaysIn(from, to));
    int n = 0;
    for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
      if (d.getDayOfWeek() != DayOfWeek.SATURDAY
          && d.getDayOfWeek() != DayOfWeek.SUNDAY
          && !holidays.contains(d)) {
        n++;
      }
    }
    return n;
  }
}

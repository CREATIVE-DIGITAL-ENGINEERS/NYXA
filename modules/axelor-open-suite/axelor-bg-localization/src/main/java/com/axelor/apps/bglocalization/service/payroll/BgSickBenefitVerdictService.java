package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.repo.BgSickLeaveRepository;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.payroll.BgSickBenefitVerdictRules.Input;
import com.axelor.apps.bglocalization.service.payroll.BgSickBenefitVerdictRules.Verdict;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.auth.AuthUtils;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * ПРИСЪДАТА ЗА ПРАВОТО НА ОБЕЗЩЕТЕНИЕ — чете се, смята се ВЕДНЪЖ, замразява се на листа.
 *
 * <p><b>Един писач.</b> Полетата {@code BgSickLeave.bgBenefitVerdict*} се пишат само оттук: при
 * първото пресмятане, което се нуждае от присъдата ({@code BgSickLeaveService} при „Преизчисли“,
 * „Отрази пристигнал болничен“, „Изведи/опресни обстоятелствата“ на Приложение № 9), или с
 * изричното {@link #redetermine} с причина. Фишът, Д1 и Приложение № 9 я ЧЕТАТ.
 *
 * <p><b>⚠️ Само активното дружество.</b> Историята на стажа се чете от потвърдените фишове на
 * лицето САМО при осигурителя по договора на листа ({@code payCompany}) — дружеството, в което
 * действието е пуснато (каталогът на nyxa-security ограничава листа до {@code
 * __user__.activeCompany}). Фишове на същото лице в друго дружество не се четат: изолация, не
 * пропуск — месец без фиш тук е отказ, не „осигурен другаде“.
 */
public class BgSickBenefitVerdictService {

  /** Замразената присъда на листа, или {@code null}. */
  public Verdict frozenOf(BgSickLeave sheet) {
    if (sheet == null || sheet.getBgBenefitVerdict() == null) {
      return null;
    }
    return Verdict.of(
        sheet.getBgBenefitVerdict(),
        sheet.getBgBenefitVerdictAsOf(),
        sheet.getBgBenefitVerdictServiceMonths(),
        sheet.getBgBenefitVerdictWhy());
  }

  /** Присъдата по днешните данни — БЕЗ запис. За {@link #ensureFrozen} и {@link #redetermine}. */
  public Verdict compute(BgSickLeave sheet) {
    Input in = new Input();
    if (sheet.getBgAbsenceType() != null) {
      in.absenceTypeCode = sheet.getBgAbsenceType().getCode();
      in.category = sheet.getBgAbsenceType().getCategory();
    }
    // ⚠️ ДАТАТА НА ИЗМЕРВАНЕ: „Дата на възникване“ на листа, а при празна — НАЧАЛОТО на листа.
    // Присъдата се смята за ПЪРВИЯ лист от периода, а неговото начало Е началото на
    // неработоспособността; заварените листове полето може да го нямат. Изводът се записва в
    // основанието на присъдата, не се крие.
    in.incapacityStart = sheet.getBgIncapacityStartDate();
    if (in.incapacityStart == null) {
      in.incapacityStart = sheet.getFromDate();
      in.startFromSheet = in.incapacityStart != null;
    }
    EmploymentContract contract = sheet.getEmploymentContract();
    Employee employee = contract == null ? null : contract.getEmployee();
    if (employee != null) {
      in.birthDate = employee.getBirthDate();
      in.serviceDeclared = Boolean.TRUE.equals(employee.getBgInsuranceServiceDeclared());
      // ⚠️ Стойността се чете САМО през флага: генерираният getter връща 0 и за празно.
      in.declaredMonths = in.serviceDeclared ? employee.getBgInsuranceServiceMonths() : null;
      in.declaredAsOf = employee.getBgInsuranceServiceAsOf();
      List<YearMonth> months =
          BgSickBenefitVerdictRules.monthsToVerify(in.declaredAsOf, in.incapacityStart);
      if (months != null && !months.isEmpty() && contract.getPayCompany() != null) {
        in.unverifiedMonths = unverifiedMonths(employee, contract.getPayCompany(), months);
      }
    }
    return BgSickBenefitVerdictRules.decide(in);
  }

  /**
   * Замразената присъда — или смятане ВЕДНЪЖ и замразяване. Отказ не се замразява: той е „не знам“
   * и следващото пресмятане след поправка на входа трябва да опита пак.
   */
  public Verdict ensureFrozen(BgSickLeave sheet) {
    Verdict frozen = frozenOf(sheet);
    if (frozen != null) {
      return frozen;
    }
    Verdict computed = compute(sheet);
    if (!computed.isRefused()) {
      freeze(sheet, computed, null);
      Beans.get(BgSickLeaveRepository.class).save(sheet);
    }
    return computed;
  }

  /**
   * ИЗРИЧНОТО НОВО ОПРЕДЕЛЯНЕ — с причина. Отказ не пипа нищо: старата присъда остава.
   *
   * <p>⚠️ Потвърдените фишове НЕ се преизчисляват от това: те носят замразените си дни ({@code
   * bgSickDaysNoBenefit}) и коригиращото копие ги наследява. Новата присъда важи за следващите
   * пресмятания.
   */
  @Transactional(rollbackOn = {Exception.class})
  public Verdict redetermine(BgSickLeave sheet, String reason) {
    if (reason == null || reason.trim().isEmpty()) {
      throw new IllegalStateException(
          "Новото определяне на правото иска причина — тя остава на листа заедно с кой и кога.");
    }
    Verdict computed = compute(sheet);
    if (computed.isRefused()) {
      throw new IllegalStateException(
          "Правото НЕ е определено наново — старата присъда остава: " + computed.refusal);
    }
    freeze(sheet, computed, reason.trim());
    Beans.get(BgSickLeaveRepository.class).save(sheet);
    return computed;
  }

  /** Записва присъдата върху листа — кой, кога, защо. Без запис в базата. */
  public void freeze(BgSickLeave sheet, Verdict verdict, String reason) {
    sheet.setBgBenefitVerdict(verdict.verdict);
    sheet.setBgBenefitVerdictAsOf(verdict.asOf);
    sheet.setBgBenefitVerdictServiceMonths(verdict.serviceMonths);
    sheet.setBgBenefitVerdictWhy(verdict.why);
    sheet.setBgBenefitVerdictBy(AuthUtils.getUser());
    sheet.setBgBenefitVerdictOn(LocalDateTime.now());
    sheet.setBgBenefitVerdictReason(reason);
  }

  /**
   * Месеците, които НЕ са удостоверени с потвърден фиш при осигурителя — с причината на всеки.
   *
   * <p>Удостоверен е месец, за който има ПОТВЪРДЕН или ИЗПЛАТЕН фиш на лицето при същото дружество
   * (без заличаващо копие), и поне един такъв фиш е без дни извън осигуряване ({@code
   * bgLeaveDaysOutside} — КСО чл. 9 ал. 2 т. 3, неплатен отпуск над 30 дни, неприсъствие).
   */
  protected List<String> unverifiedMonths(
      Employee employee, Company company, List<YearMonth> months) {
    LocalDate from = months.get(0).atDay(1);
    LocalDate to = months.get(months.size() - 1).atEndOfMonth();
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT s.period.fromDate, s.period.toDate, s.bgLeaveDaysOutside FROM BgPaySlip s"
                    + " WHERE s.employmentContract.employee.id = :employee"
                    + " AND s.employmentContract.payCompany.id = :company"
                    + " AND s.statusSelect IN (:statuses)"
                    + " AND (s.bgIsDeletion IS NULL OR s.bgIsDeletion = FALSE)"
                    + " AND s.period.fromDate <= :to AND s.period.toDate >= :from",
                Object[].class)
            .setParameter("employee", employee.getId())
            .setParameter("company", company.getId())
            .setParameter(
                "statuses", Arrays.asList(BgPaySlipResolver.CONFIRMED, BgPaySlipResolver.PAID))
            .setParameter("from", from)
            .setParameter("to", to)
            .getResultList();

    List<String> out = new ArrayList<>();
    for (YearMonth m : months) {
      boolean covered = false;
      boolean insured = false;
      for (Object[] row : rows) {
        LocalDate pFrom = (LocalDate) row[0];
        LocalDate pTo = (LocalDate) row[1];
        if (pFrom == null
            || pTo == null
            || pFrom.isAfter(m.atEndOfMonth())
            || pTo.isBefore(m.atDay(1))) {
          continue;
        }
        covered = true;
        BigDecimal outside = (BigDecimal) row[2];
        if (outside == null || outside.signum() == 0) {
          insured = true;
        }
      }
      if (!covered) {
        out.add(m + " — няма потвърден фиш");
      } else if (!insured) {
        out.add(m + " — фишът има дни извън осигуряване");
      }
    }
    return out;
  }
}

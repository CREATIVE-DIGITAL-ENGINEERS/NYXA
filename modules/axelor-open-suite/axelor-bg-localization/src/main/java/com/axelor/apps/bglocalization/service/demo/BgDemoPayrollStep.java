package com.axelor.apps.bglocalization.service.demo;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.base.service.PartnerService;
import com.axelor.apps.base.service.YearService;
import com.axelor.apps.bglocalization.db.BgD1Declaration;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.db.BgNkpd;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.d1.BgD1DeclarationKind;
import com.axelor.apps.bglocalization.service.d1.BgD1GenerationService;
import com.axelor.apps.bglocalization.service.d1.BgD6GenerationService;
import com.axelor.apps.bglocalization.service.d1.BgD6Point;
import com.axelor.apps.bglocalization.web.BgPaySlipController;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ТРЗ — {@code employees.csv}: служители, трудови договори и фишовете 01–10 през енджина.
 *
 * <p>Всичко е пътят на екрана, без да се пипа енджинът: заплатна година с месечни периоди; лице,
 * служител и договор (вид осигурен 01, код по НКПД, 8 часа, основна заплата, признат стаж); всеки
 * месец за всеки договор фиш чернова → „Преизчисли“ → „Потвърди“ → „Изплати“ с дата края на месеца
 * (Nyxa прави счетоводната статия при изплащането); после Д1 и Д6 за периода — „Генерирай“, НЕ
 * подадени: нищо не отива към НАП. Отказ на енджина спира наливането с текста му.
 *
 * <p>Имената, ЕГН (с вярна контролна цифра), адресите и IBAN са измислени.
 */
final class BgDemoPayrollStep {

  /** Подписът на заварения дефект при „Изплати“ (вж. {@link #month}). */
  static final String KNOWN_DEFECT = "was no longer referenced by the owning entity instance";

  private BgDemoPayrollStep() {}

  static boolean enabled(BgDemoContext ctx) {
    return !ctx.withoutPayroll() && !ctx.rows("employees").isEmpty();
  }

  /** Заплатната година, служителите и договорите — преди месечните събития. */
  static void setup(BgDemoContext ctx) throws Exception {
    if (!enabled(ctx)) {
      return;
    }
    BgDemoTx.run(
        () -> {
          Company company = ctx.company();
          LocalDate from = ctx.dates().first();
          LocalDate to = from.withMonth(12).withDayOfMonth(31);
          Year year =
              JPA.all(Year.class)
                  .filter(
                      "self.company = ?1 AND self.typeSelect = ?2 AND self.fromDate <= ?3"
                          + " AND self.toDate >= ?3",
                      company,
                      YearRepository.TYPE_PAYROLL,
                      from)
                  .fetchOne();
          if (year == null) {
            YearService years = Beans.get(YearService.class);
            year =
                years.createYear(
                    company,
                    "ТРЗ " + from.getYear(),
                    "TRZ" + from.getYear() + "-" + company.getCode(),
                    from,
                    to,
                    YearRepository.DURATION_MONTH,
                    YearRepository.TYPE_PAYROLL);
            years.generatePeriodsForYear(year);
          }
        });
    for (BgDemoCsv.Row r : ctx.rows("employees")) {
      BgDemoTx.run(() -> employee(ctx, r));
    }
    ctx.log("ТРЗ: " + ctx.rows("employees").size() + " служители с трудови договори");
  }

  static void employee(BgDemoContext ctx, BgDemoCsv.Row r) {
    Company company = ctx.company();
    Partner person = new Partner();
    person.setPartnerTypeSelect(PartnerRepository.PARTNER_TYPE_INDIVIDUAL);
    person.setIsContact(true);
    person.setFirstName(r.get("firstName"));
    person.setName(r.get("lastName"));
    person.setCompany(company);
    Address address = BgDemoCompanyStep.address(r.get("street"), r.get("zip"), r.get("city"), "BG");
    Beans.get(PartnerService.class).addPartnerAddress(person, address, true, false, false);
    person.setMainAddress(address);
    // ⚠️ ПРЕЗ РЕПОЗИТОРИТО, НЕ JPA.save. Пълното име на партньора го смята PartnerService#onSave;
    // без него Partner.fullName остава празно, Employee.name (изчислено от него) — също, а името на
    // договора (EmploymentContract.fullName = вид-служител-дати) се свежда до НАЧАЛНАТА ДАТА. Тя
    // би излязла в колоната „Служител“ на фишовете и при избора на договор в болничния.
    // Контрагентите (BgDemoPartnerStep) също минават оттук.
    person = Beans.get(PartnerRepository.class).save(person);
    BankDetails iban = new BankDetails();
    iban.setIban(r.get("iban"));
    iban.setBank(BgDemoCompanyStep.bank("DEMOBGSF", "Демо Банк АД", "BG"));
    iban.setPartner(person);
    iban.setOwnerName(r.get("firstName") + " " + r.get("lastName"));
    iban.setActive(true);
    iban.setIsDefault(true);
    person.addBankDetailsListItem(JPA.save(iban));

    Employee e = new Employee();
    e.setName(r.get("firstName") + " " + r.get("lastName"));
    e.setContactPartner(person);
    e.setBgPersonalIdNumber(r.get("egn"));
    e.setBgPersonalIdType("1");
    e.setBirthDate(LocalDate.parse(r.get("birthDate")));
    e.setBgBornAfter1959(e.getBirthDate().getYear() > 1959);
    e.setBgLaborCategory("3");
    e.setBgBenefitIban(r.get("iban"));
    e.setBgPersonnelNumber(r.get("code"));
    int months = r.integer("serviceYears") * 12;
    e.setBgInsuranceServiceDeclared(true);
    e.setBgInsuranceServiceMonths(months);
    e.setBgInsuranceServiceAsOf(ctx.dates().first().minusDays(1));
    e = JPA.save(e);

    EmploymentContract c = new EmploymentContract();
    c.setEmployee(e);
    c.setPayCompany(company);
    c.setStartDate(ctx.dates().of(r.get("start")).minusYears(r.integer("startYearShift")));
    c.setBgPriorExperienceYears(r.integer("priorYears"));
    c.setBgPriorExperienceMonths(0);
    c.setBgPriorExperienceDays(0);
    c.setBgInsuredTypeCode("01");
    c.setBgNkpd(JPA.all(BgNkpd.class).filter("self.code = ?1", r.get("nkpd")).fetchOne());
    if (c.getBgNkpd() == null) {
      throw new IllegalArgumentException(r.where() + ": няма код по НКПД " + r.get("nkpd"));
    }
    c.setBgHoursPerDay(new BigDecimal("8.00"));
    c.setBgLegalHoursPerDay(new BigDecimal("8.00"));
    c.setBgDaysPerWeek(new BigDecimal("5.00"));
    c.setBgBaseSalary(r.decimal("baseSalary"));
    c = JPA.save(c);
    ctx.put("contract", r.get("code"), c);
  }

  static List<BgDemoEvent> events(BgDemoContext ctx) {
    List<BgDemoEvent> out = new ArrayList<>();
    if (!enabled(ctx)) {
      return out;
    }
    LocalDate first = ctx.dates().first();
    for (int m = 1; m <= BgDemoDates.LAST_MONTH.getValue(); m++) {
      LocalDate end = first.withMonth(m).withDayOfMonth(first.withMonth(m).lengthOfMonth());
      out.add(
          new BgDemoEvent(
              end,
              BgDemoEvent.RANK_MONTH_END + 2,
              "ТРЗ " + String.format("%02d", m),
              () -> month(ctx, end)));
    }
    return out;
  }

  static void month(BgDemoContext ctx, LocalDate end) throws Exception {
    Long periodId =
        BgDemoTx.call(
            () ->
                JPA.all(Period.class)
                    .filter(
                        "self.year.company = ?1 AND self.year.typeSelect = ?2"
                            + " AND self.fromDate = ?3",
                        ctx.company(),
                        YearRepository.TYPE_PAYROLL,
                        end.withDayOfMonth(1))
                    .fetchOne()
                    .getId());
    BgPaySlipController controller = Beans.get(BgPaySlipController.class);
    int slips = 0;
    int unpaid = 0;
    for (BgDemoCsv.Row r : ctx.rows("employees")) {
      LocalDate start =
          BgDemoTx.call(
              () -> ctx.find(EmploymentContract.class, "contract", r.get("code")).getStartDate());
      if (start.isAfter(end)) {
        continue; // назначен след този месец
      }
      slips++;
      Long slipId =
          BgDemoTx.call(
              () -> {
                BgPaySlip slip = new BgPaySlip();
                slip.setEmploymentContract(
                    ctx.find(EmploymentContract.class, "contract", r.get("code")));
                slip.setPeriod(JPA.find(Period.class, periodId));
                slip.setReference("ФИШ-" + r.get("code") + "-" + end.getMonthValue());
                slip.setStatusSelect(BgPaySlipResolver.DRAFT);
                slip.setBgIsCorrection(false);
                slip.setBgIsDeletion(false);
                slip.setBgAutoFillBaseSalary(true);
                slip.setBgNoOtherPayerIncome(true);
                slip.setBgPaySlipLineList(new ArrayList<>());
                return JPA.save(slip).getId();
              });
      press(slipId, controller::recalculate, Map.of());
      press(slipId, controller::confirm, Map.of());
      BgDemoTx.run(() -> JPA.find(BgPaySlip.class, slipId).setBgPaidDate(end));
      Object paid = press(slipId, controller::pay, Map.of("bgPaidDate", end)).getData();
      String status = BgDemoTx.call(() -> JPA.find(BgPaySlip.class, slipId).getStatusSelect());
      if (!BgPaySlipResolver.PAID.equals(status) && String.valueOf(paid).contains(KNOWN_DEFECT)) {
        // ⚠️ ЗАВАРЕН ДЕФЕКТ В ЕНДЖИНА, НЕ СЕ ПОПРАВЯ ОТТУК: BgPaySlipMoveService подменя списъка
        // с редовете на статия, която MoveCreateServiceImpl#createMove вече е записал, и Hibernate
        // отказва при flush — изплащането се връща, фишът остава потвърден. Наливането продължава
        // (Д1/Д6 са по потвърдените фишове); щом дефектът се поправи, същото наливане изплаща.
        unpaid++;
        continue;
      }
      if (!BgPaySlipResolver.PAID.equals(status)) {
        throw new IllegalStateException(
            "Фишът на "
                + r.get("code")
                + " за "
                + end
                + " не е изплатен (статус "
                + status
                + "): "
                + paid);
      }
    }
    // Д1 и Д6 за периода — „Генерирай“; НЕ се подават.
    Long d1 =
        BgDemoTx.call(
            () -> {
              BgD1Declaration d = new BgD1Declaration();
              d.setCompany(ctx.company());
              d.setBgDataPeriod(JPA.find(Period.class, periodId));
              d.setBgDeclarationKind(BgD1DeclarationKind.REGULAR);
              return JPA.save(d).getId();
            });
    Beans.get(BgD1GenerationService.class).generate(d1);
    Long d6 =
        BgDemoTx.call(
            () -> {
              BgD6Declaration d = new BgD6Declaration();
              d.setCompany(ctx.company());
              d.setBgPeriod(JPA.find(Period.class, periodId));
              // т. 8.1 — ръчен избор; заплатите на набора са изплатени в края на същия месец.
              d.setT81PaymentKind(BgD6Point.PAYMENT_KIND_SAME_MONTH);
              return JPA.save(d).getId();
            });
    Beans.get(BgD6GenerationService.class).generate(d6);
    // Отказите и проверките на Д1 — в резултата: енджинът не се поправя оттук, само се вижда.
    String refusals = BgDemoTx.call(() -> JPA.find(BgD1Declaration.class, d1).getBgRefusalReport());
    if (refusals != null && !refusals.trim().isEmpty()) {
      ctx.log("  Д1 " + end.getMonthValue() + " — откази: " + refusals.trim());
    }
    ctx.log(
        "ТРЗ "
            + end.getMonthValue()
            + ": "
            + slips
            + " фиша потвърдени, "
            + (slips - unpaid)
            + " изплатени, Д1 и Д6 генерирани (не подадени)"
            + (unpaid == 0
                ? ""
                : " — НЕ изплатени: "
                    + unpaid
                    + " (дефект в енджина: статията по заплатата пада при запис — "
                    + KNOWN_DEFECT
                    + ")"));
  }

  private static com.axelor.rpc.ActionResponse press(
      Long slipId, BgDemoForm.Action action, Map<String, Object> screen) throws Exception {
    Map<String, Object> context = new java.util.HashMap<>(screen);
    context.put("version", BgDemoTx.call(() -> JPA.find(BgPaySlip.class, slipId).getVersion()));
    return BgDemoForm.press(BgPaySlip.class, slipId, context, action);
  }
}

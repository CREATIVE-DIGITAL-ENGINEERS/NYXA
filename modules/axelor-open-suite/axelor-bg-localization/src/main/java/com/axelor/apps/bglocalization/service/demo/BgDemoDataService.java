package com.axelor.apps.bglocalization.service.demo;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.service.partner.BgExplicitCompany;
import com.axelor.db.JPA;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * „НАЛЕЙ ДЕМО ДАННИ“ — измислен набор в НОВО ПРАЗНО дружество със сметкоплана BG_COA.
 *
 * <p>Всичко минава през услугите на Axelor/Nyxa, както при ръчна работа: партньорите през
 * хранилището им, фактурите — заглавие, редове, изчисление, валидиране, осчетоводяване; плащанията
 * — регистриране на плащане; встъпителните салда — ръчна статия в дневника „Начален баланс“. Нищо
 * не се пише с SQL и нищо не се изчислява във вноса — данъците, сметките и статиите са на Nyxa.
 *
 * <p>Само в празно дружество ({@link BgDemoEmptyCompanyGuard}); други дружества не се пипат. Всички
 * нови записи са на това дружество — наливането обявява дружеството изрично ({@link
 * BgExplicitCompany}), за да не вземат активното дружество на администратора.
 *
 * <p>Наборът е в {@code src/main/resources/demo-data/<набор>/}; датите са „ММ-ДД“ в избраната
 * година (по подразбиране текущата).
 */
public class BgDemoDataService {

  private static final Logger LOG = LoggerFactory.getLogger(BgDemoDataService.class);

  /** Резултатът: какво е налято, ред по ред. */
  public static final class Result {
    public final List<String> log;

    Result(List<String> log) {
      this.log = log;
    }
  }

  /** Отказът при непразно дружество — с всички причини наведнъж. */
  public static final class Refused extends Exception {
    public final List<String> reasons;

    Refused(List<String> reasons) {
      super(String.join(" ", reasons));
      this.reasons = reasons;
    }
  }

  public Result load(Long companyId, BgDemoDataset dataset, int year) throws Exception {
    return load(companyId, dataset, year, true);
  }

  /**
   * {@code payroll = false} — без ТРЗ: дружество, каквото оставя наливането отпреди ТРЗ да влезе в
   * набора (за проверката на „само ТРЗ“).
   */
  Result load(Long companyId, BgDemoDataset dataset, int year, boolean payroll) throws Exception {
    Company company = JPA.find(Company.class, companyId);
    List<String> refusals = new ArrayList<>(BgDemoPrerequisites.refusals(dataset));
    refusals.addAll(BgDemoEmptyCompanyGuard.refusals(company));
    if (!refusals.isEmpty()) {
      throw new Refused(refusals);
    }
    BgDemoContext ctx = new BgDemoContext(companyId, dataset, year);
    ctx.withoutPayroll(!payroll);
    BgExplicitCompany.call(
        company,
        () -> {
          run(ctx);
          return null;
        });
    return new Result(ctx.log());
  }

  /**
   * САМО ТРЗ — в демо дружество с вече налято счетоводство (наливане отпреди ТРЗ да влезе в
   * набора): служителите, договорите и фишовете 01–10 с Д1/Д6. Дружество с ТРЗ (фиш или трудов
   * договор) се отказва, както и дружество, което не е налято с този набор.
   */
  public Result loadPayroll(Long companyId, BgDemoDataset dataset, int year) throws Exception {
    Company company = JPA.find(Company.class, companyId);
    List<String> refusals = new ArrayList<>(BgDemoPrerequisites.refusals(dataset));
    refusals.addAll(payrollRefusals(company, dataset));
    if (!refusals.isEmpty()) {
      throw new Refused(refusals);
    }
    BgDemoContext ctx = new BgDemoContext(companyId, dataset, year);
    BgExplicitCompany.call(
        company,
        () -> {
          BgDemoPayrollStep.setup(ctx);
          List<BgDemoEvent> events = new ArrayList<>(BgDemoPayrollStep.events(ctx));
          events.sort(BgDemoEvent.ORDER);
          runEvents(events);
          return null;
        });
    return new Result(ctx.log());
  }

  /**
   * Отказите за „само ТРЗ“: наборът има ли ТРЗ, налято ли е дружеството с него, няма ли вече ТРЗ.
   */
  public static List<String> payrollRefusals(Company company, BgDemoDataset dataset) {
    List<String> out = new ArrayList<>();
    if (BgDemoCsv.read(dataset, "employees").isEmpty()) {
      out.add("Наборът няма ТРЗ.");
      return out;
    }
    String eik = BgDemoCompanyStep.keyValues(BgDemoCsv.read(dataset, "company")).get("eik");
    String own = company.getPartner() == null ? null : company.getPartner().getRegistrationCode();
    long invoices =
        JPA.all(com.axelor.apps.account.db.Invoice.class)
            .filter("self.company = ?1", company)
            .count();
    if (!eik.equals(own) || invoices == 0) {
      out.add(
          "Дружеството не е налято с демо набора (ЕИК "
              + eik
              + ") — първо „Налей демо данни“; той налива и ТРЗ.");
    }
    long contracts =
        JPA.all(com.axelor.apps.hr.db.EmploymentContract.class)
            .filter("self.payCompany = ?1", company)
            .count();
    long slips =
        JPA.all(com.axelor.apps.bglocalization.db.BgPaySlip.class)
            .filter("self.employmentContract.payCompany = ?1", company)
            .count();
    if (contracts > 0 || slips > 0) {
      out.add(
          "ТРЗ вече е налято: "
              + contracts
              + " трудови договора, "
              + slips
              + " фиша — повторно наливане се отказва.");
    }
    return out;
  }

  /** Причината с нарушенията на bean валидацията поименно (полето и съобщението). */
  static String reason(Throwable ex) {
    for (Throwable t = ex; t != null; t = t.getCause()) {
      if (t instanceof javax.validation.ConstraintViolationException) {
        StringBuilder b = new StringBuilder(String.valueOf(ex.getMessage()));
        for (javax.validation.ConstraintViolation<?> v :
            ((javax.validation.ConstraintViolationException) t).getConstraintViolations()) {
          b.append(" [")
              .append(v.getPropertyPath())
              .append(": ")
              .append(v.getMessage())
              .append(']');
        }
        return b.toString();
      }
    }
    return String.valueOf(ex.getMessage());
  }

  protected void run(BgDemoContext ctx) throws Exception {
    BgDemoCompanyStep.run(ctx);
    BgDemoRatesStep.run(ctx);
    BgDemoPartnerStep.run(ctx);
    BgDemoProductStep.run(ctx);
    BgDemoStockStep.setup(ctx);
    BgDemoAnalyticStep.setup(ctx);
    BgDemoBankStep.setup(ctx);
    BgDemoPayrollStep.setup(ctx);

    List<BgDemoEvent> events = new ArrayList<>();
    events.addAll(BgDemoOpeningStep.events(ctx));
    events.addAll(BgDemoInvoiceStep.events(ctx));
    events.addAll(BgDemoPaymentStep.events(ctx));
    events.addAll(BgDemoStockStep.countEvents(ctx));
    events.addAll(BgDemoAssetStep.events(ctx));
    events.addAll(BgDemoRevaluationStep.events(ctx));
    events.addAll(BgDemoBankStep.events(ctx));
    events.addAll(BgDemoPayrollStep.events(ctx));
    events.sort(BgDemoEvent.ORDER);
    runEvents(events);
    ctx.log("Събития: " + events.size());
    BgDemoVatReturnStep.run(ctx);
  }

  private static void runEvents(List<BgDemoEvent> events) {
    for (BgDemoEvent e : events) {
      try {
        e.action.run();
      } catch (Exception ex) {
        LOG.error("Демо данни: {} ({}) — {}", e.label, e.date, ex.getMessage(), ex);
        throw new IllegalStateException(
            "Демо данните спряха на „" + e.label + "“ (" + e.date + "): " + reason(ex), ex);
      }
    }
  }
}

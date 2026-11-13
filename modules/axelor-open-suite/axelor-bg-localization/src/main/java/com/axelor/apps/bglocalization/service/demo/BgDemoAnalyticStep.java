package com.axelor.apps.bglocalization.service.demo;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.AnalyticAccount;
import com.axelor.apps.account.db.AnalyticAxis;
import com.axelor.apps.account.db.AnalyticAxisByCompany;
import com.axelor.apps.account.db.AnalyticDistributionLine;
import com.axelor.apps.account.db.AnalyticDistributionTemplate;
import com.axelor.apps.account.db.AnalyticJournal;
import com.axelor.apps.account.db.AnalyticJournalType;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.repo.AccountConfigRepository;
import com.axelor.apps.account.db.repo.AnalyticAccountRepository;
import com.axelor.apps.account.db.repo.AnalyticJournalRepository;
import com.axelor.apps.account.service.app.AppAccountService;
import com.axelor.apps.account.service.invoice.InvoiceLineAnalyticService;
import com.axelor.apps.account.service.invoice.InvoiceToolService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.studio.db.AppAccount;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * АНАЛИТИЧНО РАЗПРЕДЕЛЕНИЕ ПО ЦЕНТРОВЕ — {@code analytics.csv}: {@code kind;code;name;axis}.
 *
 * <p>Две оси на дружеството — „Разходни центрове“ (Администрация, Продажби) и „Профит центрове“
 * (Услуги, Стоки), аналитичен дневник и свободно разпределение (както в „Счетоводна конфигурация ›
 * Аналитично“). Сметките 60x/70x на дружеството допускат разпределение. Всеки ред на фактура по
 * 60x/70x получава шаблон: разходен център — „Продажби“ за продажбите, „Администрация“ за
 * покупките; профит център — „Стоки“ за стоките и книгите, „Услуги“ за останалото. Аналитичните
 * редове съставя Axelor ({@link InvoiceLineAnalyticService#computeAnalyticDistribution}) и ги
 * пренася в статията при осчетоводяване.
 *
 * <p>⚠️ Аналитичното счетоводство в приложението „Счетоводство“ е ОБЩ ключ за всички дружества (по
 * подразбиране изключен). Стъпката го включва само ако е изключен и го пише в резултата; другите
 * дружества остават с изключена своя настройка и не се засягат.
 */
final class BgDemoAnalyticStep {

  private BgDemoAnalyticStep() {}

  static boolean enabled(BgDemoContext ctx) {
    return !ctx.rows("analytics").isEmpty();
  }

  static void setup(BgDemoContext ctx) throws Exception {
    if (!enabled(ctx)) {
      return;
    }
    BgDemoTx.run(
        () -> {
          AppAccount app = Beans.get(AppAccountService.class).getAppAccount();
          if (!app.getManageAnalyticAccounting()) {
            app.setManageAnalyticAccounting(true);
            JPA.save(app);
            ctx.log(
                "Аналитично счетоводство: включено в приложението „Счетоводство“ (беше изключено)");
          }
          Company company = ctx.company();
          String prefix = company.getCode() + "-";
          Map<String, AnalyticAxis> axes = new LinkedHashMap<>();
          for (BgDemoCsv.Row r : ctx.rows("analytics")) {
            if (!"AXIS".equals(r.get("kind"))) {
              continue;
            }
            AnalyticAxis axis = new AnalyticAxis();
            axis.setCode(prefix + r.get("code"));
            // Името на оста е уникално в цялата база — с кода на дружеството.
            axis.setName(r.get("name") + " (" + company.getCode() + ")");
            axis.setCompany(company);
            axes.put(r.get("code"), JPA.save(axis));
          }
          for (BgDemoCsv.Row r : ctx.rows("analytics")) {
            if (!"ACCOUNT".equals(r.get("kind"))) {
              continue;
            }
            AnalyticAccount a = new AnalyticAccount();
            a.setCode(prefix + r.get("code"));
            a.setName(r.get("name"));
            a.setAnalyticAxis(axes.get(r.get("axis")));
            a.setCompany(company);
            a.setStatusSelect(AnalyticAccountRepository.STATUS_ACTIVE);
            ctx.put("analytic", r.get("code"), JPA.save(a));
          }
          AnalyticJournalType type = JPA.all(AnalyticJournalType.class).order("id").fetchOne();
          if (type == null) {
            type = new AnalyticJournalType();
            type.setName("Аналитичен");
            type.setCode("ANA");
            type = JPA.save(type);
          }
          AnalyticJournal journal = new AnalyticJournal();
          journal.setName("Аналитичен дневник (" + company.getCode() + ")");
          journal.setCode(prefix + "ANA");
          journal.setType(type);
          journal.setCompany(company);
          journal.setStatusSelect(AnalyticJournalRepository.STATUS_ACTIVE);
          journal = JPA.save(journal);
          ctx.put("analyticJournal", "ANA", journal);

          AccountConfig config =
              JPA.all(AccountConfig.class).filter("self.company = ?1", company).fetchOne();
          config.setManageAnalyticAccounting(true);
          config.setNbrOfAnalyticAxisSelect(axes.size());
          config.setAnalyticDistributionTypeSelect(AccountConfigRepository.DISTRIBUTION_TYPE_FREE);
          config.setAnalyticJournal(journal);
          int seq = 0;
          for (AnalyticAxis axis : axes.values()) {
            AnalyticAxisByCompany link = new AnalyticAxisByCompany();
            link.setAccountConfig(config);
            link.setAnalyticAxis(axis);
            link.setSequence(seq++);
            config.addAnalyticAxisByCompanyListItem(link);
          }
          JPA.save(config);

          // Сметките 60x/70x допускат разпределение (картонът на сметката › Аналитично).
          for (Account a :
              JPA.all(Account.class)
                  .filter(
                      "self.company = ?1 AND (self.code LIKE '60%' OR self.code LIKE '70%')",
                      company)
                  .fetch()) {
            a.setAnalyticDistributionAuthorized(true);
            JPA.save(a);
          }
          ctx.log("Центрове: " + axes.size() + " оси, аналитично разпределение на 60x/70x");
        });
  }

  /** Шаблонът „разходен / профит център“ — създава се при първа нужда. */
  private static AnalyticDistributionTemplate template(
      BgDemoContext ctx, String cost, String profit) {
    String key = cost + "/" + profit;
    if (ctx.has("analyticTemplate", key)) {
      return ctx.find(AnalyticDistributionTemplate.class, "analyticTemplate", key);
    }
    Company company = ctx.company();
    AnalyticJournal journal = ctx.find(AnalyticJournal.class, "analyticJournal", "ANA");
    AnalyticDistributionTemplate t = new AnalyticDistributionTemplate();
    t.setCompany(company);
    t.setName(
        ctx.find(AnalyticAccount.class, "analytic", cost).getName()
            + " / "
            + ctx.find(AnalyticAccount.class, "analytic", profit).getName());
    List<AnalyticDistributionLine> lines = new ArrayList<>();
    for (String code : new String[] {cost, profit}) {
      AnalyticAccount account = ctx.find(AnalyticAccount.class, "analytic", code);
      AnalyticDistributionLine l = new AnalyticDistributionLine();
      l.setAnalyticDistributionTemplate(t);
      l.setAnalyticAxis(account.getAnalyticAxis());
      l.setAnalyticAccount(account);
      l.setPercentage(new BigDecimal("100"));
      l.setAnalyticJournal(journal);
      lines.add(l);
    }
    t.setAnalyticDistributionLineList(lines);
    t = JPA.save(t);
    ctx.put("analyticTemplate", key, t);
    return t;
  }

  /** Разпределя редовете по 60x/70x на фактурата (преди изчислението и осчетоводяването). */
  static void distribute(BgDemoContext ctx, Invoice invoice) throws AxelorException {
    if (!enabled(ctx)) {
      return;
    }
    boolean purchase = InvoiceToolService.isPurchase(invoice);
    InvoiceLineAnalyticService service = Beans.get(InvoiceLineAnalyticService.class);
    for (InvoiceLine line : invoice.getInvoiceLineList()) {
      Account account = line.getAccount();
      if (account == null
          || account.getCode() == null
          || !(account.getCode().startsWith("60") || account.getCode().startsWith("70"))) {
        continue;
      }
      String family =
          line.getProduct() == null || line.getProduct().getProductFamily() == null
              ? ""
              : line.getProduct().getProductFamily().getCode();
      String profit = "GOODS".equals(family) || "BOOK9".equals(family) ? "GDS" : "SRV";
      line.setAnalyticDistributionTemplate(template(ctx, purchase ? "ADM" : "SAL", profit));
      // Както полето „Шаблон за разпределение“ на реда: Axelor съставя прогнозните аналитични
      // редове, а при осчетоводяването ги прави реални по реда на статията.
      line.setAnalyticMoveLineList(new ArrayList<>());
      service.computeAnalyticDistribution(line).forEach(line::addAnalyticMoveLineListItem);
    }
  }
}

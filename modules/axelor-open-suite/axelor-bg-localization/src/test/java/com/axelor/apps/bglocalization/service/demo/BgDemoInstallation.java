package com.axelor.apps.bglocalization.service.demo;

import com.axelor.app.AppSettings;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.AccountingConfigTemplate;
import com.axelor.apps.account.service.AccountingConfigTemplateService;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaModule;
import com.axelor.meta.loader.ModuleManager;
import com.axelor.studio.app.service.AppService;
import com.axelor.studio.db.App;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * НОВА СРЕДА, КАКТО Я ВИЖДА АДМИНИСТРАТОРЪТ — за демо теста върху своя PostgreSQL база.
 *
 * <ol>
 *   <li>Първият старт: {@link ModuleManager#initialize} — модулите, изгледите и data-init на всеки
 *       модул (валути, държави, мерни единици, шаблонът BG_COA…).
 *   <li>App Install на всички приложения с езика „en“ (в Nyxa BG seed-ът е в {@code en/}) — ТРЗ
 *       параметрите, НКИД, видовете доход, причините за складови корекции…
 *   <li>Ново дружество от екрана ({@link CompanyRepository#save} — той създава поредицата на
 *       партньорите, шаблона на склада и партньора на дружеството) и бутонът „Импорт на счетоводни
 *       конфигурации“ с шаблона BG_COA.
 * </ol>
 *
 * <p>Стъпки 1–2 се правят веднъж на база (после са празен ход); стъпка 3 — при всяко пускане.
 */
final class BgDemoInstallation {

  private BgDemoInstallation() {}

  /** Каталогът за качени файлове — зипът на шаблона BG_COA се качва там при data-init. */
  static void uploadDir() throws Exception {
    Path dir =
        Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir"), "nyxa-demo-upload"));
    AppSettings.get().getInternalProperties().put("data.upload.dir", dir.toString());
    AppSettings.get()
        .getInternalProperties()
        .putIfAbsent("context.date", "com.axelor.apps.base.service.DateService:date");
    AppSettings.get().getInternalProperties().putIfAbsent("application.locale", "bg");
    // BIRT (PDF на фактурата при осчетоводяване) се свързва сам с базата — както с
    // axelor-config.properties на приложението.
    java.util.Map<String, String> p = AppSettings.get().getInternalProperties();
    p.put("db.default.driver", "org.postgresql.Driver");
    p.put("db.default.url", System.getProperty("demo.db.url"));
    p.put("db.default.user", System.getProperty("demo.db.username"));
    p.put("db.default.password", System.getProperty("demo.db.password"));
  }

  static void ensure() throws Exception {
    uploadDir();
    // Първият старт е минал, ако data-init е внесъл валутите (частичен неуспешен старт — не).
    long currencies =
        JPA.em()
            .createQuery("SELECT COUNT(c) FROM Currency c WHERE c.codeISO = 'EUR'", Long.class)
            .getSingleResult();
    if (currencies == 0) {
      Beans.get(ModuleManager.class).initialize(false, false);
    }
    AppService apps = Beans.get(AppService.class);
    long active =
        JPA.all(App.class).filter("self.code = 'bg-localization' AND self.active = true").count();
    if (active == 0) {
      apps.initApps();
      List<App> all = JPA.all(App.class).fetch();
      apps.bulkInstall(all, false, "en");
    }
  }

  /** Ново дружество по пътя на екрана + BG_COA. Връща id-то му. */
  static Long newCompany(String name, String code) throws Exception {
    AtomicReference<Long> id = new AtomicReference<>();
    JPA.runInTransaction(
        () -> {
          Company c = new Company();
          c.setName(name);
          c.setCode(code);
          c.setCurrency(BgDemoCompanyStep.currency("EUR"));
          c = Beans.get(CompanyRepository.class).save(c);
          AccountConfig config = new AccountConfig();
          config.setCompany(c);
          JPA.save(config);
          id.set(c.getId());
        });
    // ⚠️ Извън транзакция, както от екрана: вносът отваря свои транзакции на всеки вход.
    JPA.em().clear();
    Company company = JPA.find(Company.class, id.get());
    AccountConfig config =
        JPA.all(AccountConfig.class).filter("self.company = ?1", company).fetchOne();
    AccountingConfigTemplate t =
        JPA.all(AccountingConfigTemplate.class).filter("self.code = 'BG_COA'").fetchOne();
    if (t == null || t.getMetaFile() == null) {
      throw new IllegalStateException("шаблонът BG_COA липсва или е без зип");
    }
    if (!Beans.get(AccountingConfigTemplateService.class).installProcess(config, t, company)) {
      throw new IllegalStateException("installProcess върна false");
    }
    return id.get();
  }

  /**
   * Входът на техническия администратор {@code admin} от data-init (групата admins) с активно
   * дружество — този, който натиска бутона. Субектът се закача на нишката, както в BgRoleLogin.
   */
  static void loginAdmin(Long companyId) {
    JPA.runInTransaction(
        () -> {
          com.axelor.auth.db.User admin =
              JPA.all(com.axelor.auth.db.User.class).filter("self.code = 'admin'").fetchOne();
          Company company = JPA.find(Company.class, companyId);
          admin.setActiveCompany(company);
          if (admin.getCompanySet() == null) {
            admin.setCompanySet(new java.util.HashSet<>());
          }
          admin.getCompanySet().add(company);
          JPA.save(admin);
        });
    org.apache.shiro.subject.Subject subject =
        new org.apache.shiro.subject.Subject.Builder(
                org.apache.shiro.SecurityUtils.getSecurityManager())
            .principals(new org.apache.shiro.subject.SimplePrincipalCollection("admin", "it"))
            .authenticated(true)
            .buildSubject();
    org.apache.shiro.util.ThreadContext.bind(subject);
  }

  static MetaModule module(String name) {
    return JPA.all(MetaModule.class).filter("self.name = ?1", name).fetchOne();
  }
}

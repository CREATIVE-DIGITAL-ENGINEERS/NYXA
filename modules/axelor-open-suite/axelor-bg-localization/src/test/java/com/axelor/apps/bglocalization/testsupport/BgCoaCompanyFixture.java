package com.axelor.apps.bglocalization.testsupport;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.AccountingConfigTemplate;
import com.axelor.apps.account.service.AccountingConfigTemplateService;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.csv.script.ImportAccountingConfigTemplate;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.studio.db.AppAccount;
import com.axelor.studio.db.AppBase;
import com.axelor.studio.db.repo.AppAccountRepository;
import com.axelor.studio.db.repo.AppBaseRepository;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Дружество със сметкоплан BG_COA по ИСТИНСКИЯ път: data-init пакетира CSV-тата в зипа на шаблона
 * ({@code ImportAccountingConfigTemplate}), а бутонът „Импорт на счетоводни конфигурации“ внася
 * целия {@code chart-config.xml} от зипа ({@code AccountingConfigTemplateService#installProcess}).
 *
 * <p>Общо за тестовете на шаблона и за тези, които искат дружество, каквото ще има на нова среда.
 */
public final class BgCoaCompanyFixture {

  /** CSV-тата на BG_COA в кода. */
  public static final Path COA =
      BgDomainColumns.modulesRoot()
          .resolve("axelor-open-suite/axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA");

  private BgCoaCompanyFixture() {}

  public static <T> T tx(Supplier<T> work) {
    AtomicReference<T> out = new AtomicReference<>();
    JPA.runInTransaction(() -> out.set(work.get()));
    return out.get();
  }

  public interface Work {
    void run() throws Exception;
  }

  public static void txChecked(Work work) {
    JPA.runInTransaction(
        () -> {
          try {
            work.run();
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
  }

  /** Веригите на вноса четат глобалните настройки; в празна тестова база те са {@code null}. */
  public static void ensureAppConfigs() {
    if (Beans.get(AppBaseRepository.class).all().fetchOne() == null) {
      JPA.save(new AppBase());
    }
    if (Beans.get(AppAccountRepository.class).all().fetchOne() == null) {
      JPA.save(new AppAccount());
    }
  }

  /** Шаблонът, както го създава {@code data-init/input/account_accountingConfigTemplate.csv}. */
  public static AccountingConfigTemplate bgCoaTemplate() {
    AccountingConfigTemplate t =
        JPA.all(AccountingConfigTemplate.class).filter("self.code = 'BG_COA'").fetchOne();
    if (t == null) {
      t = new AccountingConfigTemplate();
      t.setName("Bulgaria - Сметкоплан");
      t.setCode("BG_COA");
      t.setCountryCode("bgr");
      t = JPA.save(t);
    }
    return t;
  }

  /** Колбекът на data-init — тоест каквото прави първият старт на нова база. */
  public static Long packageLikeDataInit() {
    AtomicReference<Long> id = new AtomicReference<>();
    txChecked(
        () -> {
          AccountingConfigTemplate t = bgCoaTemplate();
          Beans.get(ImportAccountingConfigTemplate.class)
              .importAccountingConfigTemplate(t, Collections.emptyMap());
          id.set(JPA.save(t).getId());
        });
    return id.get();
  }

  /** Празно дружество със своя валута и празна счетоводна конфигурация. Вика се в транзакция. */
  public static Company newCompany(String tag) {
    ensureAppConfigs();
    Currency currency = new Currency();
    currency.setName("Евро " + tag);
    currency.setCode("€");
    currency.setCodeISO("E" + tag);
    currency = JPA.save(currency);
    return newCompany(tag, currency);
  }

  /** Празно дружество с дадена валута и празна счетоводна конфигурация. Вика се в транзакция. */
  public static Company newCompany(String tag, Currency currency) {
    ensureAppConfigs();
    Company company = new Company();
    company.setName("Дружество " + tag);
    company.setCode("C-" + tag);
    company.setCurrency(currency);
    company = JPA.save(company);

    AccountConfig config = new AccountConfig();
    config.setCompany(company);
    JPA.save(config);
    return company;
  }

  /**
   * Бутонът „Импорт на счетоводни конфигурации“: целият chart-config.xml върху ЗИПА.
   *
   * <p>⚠️ Извън транзакция, както от екрана: вносът отваря свои транзакции на всеки вход, а в
   * обвиваща транзакция първият вход (типовете сметки) пада с „Transaction already active“ и всички
   * сметки остават без тип.
   */
  public static void importFromTemplate(Long companyId, Long templateId) throws Exception {
    JPA.em().clear();
    Company company = JPA.find(Company.class, companyId);
    AccountConfig config =
        JPA.all(AccountConfig.class).filter("self.company = ?1", company).fetchOne();
    AccountingConfigTemplate t = JPA.find(AccountingConfigTemplate.class, templateId);
    assertNotNull(t.getMetaFile(), "шаблонът е без зип — бутонът не би направил нищо");
    assertTrue(
        Beans.get(AccountingConfigTemplateService.class).installProcess(config, t, company),
        "installProcess върна false");
  }
}

package com.axelor.apps.bglocalization.service.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.AccountManagement;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.PaymentMode;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.ProductFamily;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.data.Listener;
import com.axelor.data.csv.CSVImporter;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.db.Model;
import com.axelor.inject.Beans;
import com.axelor.studio.db.AppAccount;
import com.axelor.studio.db.AppBase;
import com.axelor.studio.db.repo.AppAccountRepository;
import com.axelor.studio.db.repo.AppBaseRepository;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * НОВО ДРУЖЕСТВО ПОЛУЧАВА НАЧАЛНИТЕ НАСТРОЙКИ НА СЧЕТОВОДСТВОТО ОТ BG_COA — през истинския внос.
 *
 * <p>Същото като бутона в конфигурацията на сметките ({@code
 * AccountingConfigTemplateService#installProcess}): целият {@code l10n/chart-config.xml} върху
 * всички CSV на BG_COA, с контекста, който подава той. Дружеството вече има празна конфигурация,
 * както когато я отвориш от екрана, тоест се проверява и че вносът ПРЕЗАПИСВА стойностите по
 * подразбиране на домейна (допустимата данъчна разлика е 0.00 там).
 *
 * <p>Файловият двойник, който върви и в CI, е {@code BgCoaAccountConfigDefaultsTest}.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgAccountConfigTemplateIntegrationTest extends JpaSupport {

  private static final Path COA =
      BgDomainColumns.modulesRoot()
          .resolve("axelor-open-suite/axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA");

  private static final Path CONFIG =
      BgDomainColumns.modulesRoot()
          .resolve("axelor-open-suite/axelor-account/src/main/resources/l10n/chart-config.xml");

  private static <T> T tx(Supplier<T> work) {
    AtomicReference<T> out = new AtomicReference<>();
    JPA.runInTransaction(() -> out.set(work.get()));
    return out.get();
  }

  /** Веригите на вноса четат глобалните настройки; в празна тестова база те са {@code null}. */
  private static void ensureAppConfigs() {
    if (Beans.get(AppBaseRepository.class).all().fetchOne() == null) {
      JPA.save(new AppBase());
    }
    if (Beans.get(AppAccountRepository.class).all().fetchOne() == null) {
      JPA.save(new AppAccount());
    }
  }

  private static Company newCompany(String tag) {
    ensureAppConfigs();
    Currency currency = new Currency();
    currency.setName("Евро " + tag);
    currency.setCode("€");
    currency.setCodeISO("E" + tag);
    currency = JPA.save(currency);

    Company company = new Company();
    company.setName("Дружество " + tag);
    company.setCode("C-" + tag);
    company.setCurrency(currency);
    company = JPA.save(company);

    // Както от екрана: конфигурацията се създава, преди да се избере шаблонът.
    AccountConfig config = new AccountConfig();
    config.setCompany(company);
    JPA.save(config);
    return company;
  }

  @Test
  @DisplayName("Ново дружество получава от BG_COA настройките от прегледа на 20.10.2026")
  void новото_дружество_получава_настройките() throws Exception {
    Company company = tx(() -> newCompany("ACC"));
    tx(BgAccountConfigTemplateIntegrationTest::ensureAdvanceProduct);

    importBgCoa(company);

    Map<String, Object> actual =
        tx(
            () -> {
              AccountConfig c =
                  JPA.all(AccountConfig.class).filter("self.company = ?1", company).fetchOne();
              assertNotNull(c, "вносът не остави конфигурация");
              Function<Account, String> code = a -> a == null ? null : a.getCode();
              Journal journal = c.getBgVatCorrectionJournal();
              PaymentMode in = c.getInPaymentMode();
              PaymentMode out = c.getOutPaymentMode();
              Map<String, Object> read = new LinkedHashMap<>();
              read.put("resultProfitAccount", code.apply(c.getResultProfitAccount()));
              read.put("resultLossAccount", code.apply(c.getResultLossAccount()));
              read.put(
                  "cashPositionVariationDebitAccount",
                  code.apply(c.getCashPositionVariationDebitAccount()));
              read.put(
                  "cashPositionVariationCreditAccount",
                  code.apply(c.getCashPositionVariationCreditAccount()));
              read.put("bgVatChargedAccount", code.apply(c.getBgVatChargedAccount()));
              read.put("bgVatDeductibleAccount", code.apply(c.getBgVatDeductibleAccount()));
              read.put(
                  "bgVatAnnualCorrectionExpenseAccount",
                  code.apply(c.getBgVatAnnualCorrectionExpenseAccount()));
              read.put(
                  "bgVatCorrectionIncomeAccount", code.apply(c.getBgVatCorrectionIncomeAccount()));
              read.put("bgVatCorrectionJournal", journal == null ? null : journal.getCode());
              read.put(
                  "bgVatCorrectionJournal.company",
                  journal == null ? null : journal.getCompany().getId());
              read.put("inPaymentMode", in == null ? null : in.getCode());
              read.put("outPaymentMode", out == null ? null : out.getCode());
              // Стойността, не мащабът от базата.
              read.put("allowedTaxGap", c.getAllowedTaxGap().stripTrailingZeros());
              read.put("isDescriptionRequired", c.getIsDescriptionRequired());
              read.put(
                  "partnerAccountGenerationModeSelect", c.getPartnerAccountGenerationModeSelect());
              read.put("autoReconcileOnInvoice", c.getAutoReconcileOnInvoice());
              read.put("autoReconcileOnPayment", c.getAutoReconcileOnPayment());
              read.put("irrecoverableReasonPassage", c.getIrrecoverableReasonPassage());
              Product advance = c.getAdvancePaymentProduct();
              read.put("advancePaymentProduct", advance == null ? null : advance.getCode());
              AccountManagement family =
                  JPA.all(AccountManagement.class)
                      .filter(
                          "self.company = ?1 AND self.productFamily.importId = 'BG-ADV'", company)
                      .fetchOne();
              read.put(
                  "advanceFamily.saleAccount",
                  family == null ? null : code.apply(family.getSaleAccount()));
              read.put(
                  "advanceFamily.purchaseAccount",
                  family == null ? null : code.apply(family.getPurchaseAccount()));
              return read;
            });

    Map<String, Object> expected = new LinkedHashMap<>();
    expected.put("resultProfitAccount", "123");
    expected.put("resultLossAccount", "123");
    expected.put("cashPositionVariationDebitAccount", "60909");
    expected.put("cashPositionVariationCreditAccount", "709");
    expected.put("bgVatChargedAccount", "4532");
    expected.put("bgVatDeductibleAccount", "4531");
    expected.put("bgVatAnnualCorrectionExpenseAccount", "60909");
    expected.put("bgVatCorrectionIncomeAccount", "709");
    expected.put("bgVatCorrectionJournal", "VAT");
    expected.put("bgVatCorrectionJournal.company", company.getId());
    expected.put("inPaymentMode", "INC_WT");
    expected.put("outPaymentMode", "OUT_WT");
    expected.put("allowedTaxGap", new BigDecimal("0.01"));
    expected.put("isDescriptionRequired", Boolean.TRUE);
    expected.put("partnerAccountGenerationModeSelect", 0);
    expected.put("autoReconcileOnInvoice", Boolean.FALSE);
    expected.put("autoReconcileOnPayment", Boolean.FALSE);
    expected.put("irrecoverableReasonPassage", "Ръчно прехвърляне към несъбираеми вземания");
    expected.put("advancePaymentProduct", "BG-ADVANCE");
    expected.put("advanceFamily.saleAccount", "412");
    expected.put("advanceFamily.purchaseAccount", "402");
    assertEquals(expected, actual);
  }

  /**
   * „Аванс“ и „Аванси“ — както ги внася стартът от init-data на bg-localization ({@code
   * base_productFamily_advance.csv}, {@code base_product_advance.csv}); глобални, преди шаблона на
   * дружеството.
   */
  private static Product ensureAdvanceProduct() {
    ProductFamily family =
        JPA.all(ProductFamily.class).filter("self.importId = 'BG-ADV'").fetchOne();
    if (family == null) {
      family = new ProductFamily();
      family.setImportId("BG-ADV");
      family.setCode("ADV");
      family.setName("Аванси");
      family = JPA.save(family);
    }
    Product product = JPA.all(Product.class).filter("self.importId = 'BG-ADVANCE'").fetchOne();
    if (product == null) {
      product = new Product();
      product.setImportId("BG-ADVANCE");
      product.setCode("BG-ADVANCE");
      product.setName("Аванс");
      product.setProductTypeSelect("service");
      product.setProductFamily(family);
      product = JPA.save(product);
    }
    return product;
  }

  /**
   * Маха колона, чиято клетка е празна. ⚠️ Само заради HSQLDB: autoBind на точкова колона търси
   * Partner дори при празна стойност, а PostgreSQL формулата на Partner не върви в HSQLDB
   * („incompatible data types in combination“). В PostgreSQL редът минава с колоната; стойността ѝ
   * в BG_COA е празна, тоест вносът е същият.
   */
  private static void dropEmptyColumn(Path file, String column) throws Exception {
    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    List<String> header = Arrays.asList(lines.get(0).split(";", -1));
    int index = header.indexOf("\"" + column + "\"");
    assertTrue(index >= 0, column + " вече я няма в " + file.getFileName());
    List<String> out = new ArrayList<>();
    for (String line : lines) {
      List<String> cells = new ArrayList<>(Arrays.asList(line.split(";", -1)));
      String cell = cells.remove(index);
      assertTrue(out.isEmpty() || "\"\"".equals(cell), column + " вече не е празна: " + cell);
      out.add(String.join(";", cells));
    }
    Files.write(file, out, StandardCharsets.UTF_8);
  }

  /** Целият chart-config.xml върху копие на BG_COA, с контекста на {@code installProcess}. */
  private static void importBgCoa(Company company) throws Exception {
    Path dir = Files.createTempDirectory("bg-coa");
    try (DirectoryStream<Path> csv = Files.newDirectoryStream(COA, "*.csv")) {
      for (Path file : csv) {
        Files.copy(file, dir.resolve(file.getFileName()));
      }
    }
    dropEmptyColumn(dir.resolve("account_accountConfig_payments.csv"), "factorPartner.importId");
    Path config = dir.resolve("chart-config.xml");
    Files.copy(CONFIG, config);

    CSVImporter importer = new CSVImporter(config.toString(), dir.toString());
    Map<String, Object> context = new HashMap<>();
    context.put("_companyId", company.getId());
    context.put("_companyName", company.getName());
    context.put("_companyCode", company.getCode());
    context.put("_defaultBankDetailsId", 0);
    context.put("_dataFileName", "BG_COA.zip");
    importer.setContext(context);
    List<String> errors = new ArrayList<>();
    importer.addListener(
        new Listener() {
          @Override
          public void imported(Model bean) {}

          @Override
          public void imported(Integer total, Integer success) {}

          @Override
          public void handle(Model bean, Exception e) {
            errors.add((bean == null ? "?" : bean.getClass().getSimpleName()) + ": " + e);
          }
        });
    importer.run();
    assertEquals(List.of(), errors, "вносът на BG_COA върху ново дружество е без грешки");
  }
}

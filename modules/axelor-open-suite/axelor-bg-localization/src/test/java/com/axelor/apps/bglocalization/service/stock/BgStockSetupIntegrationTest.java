package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountManagement;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.PrintingTemplate;
import com.axelor.apps.base.db.ProductFamily;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.db.SequenceVersion;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.bglocalization.listener.BgLocalizationServerStartListener;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.stock.db.StockConfig;
import com.axelor.apps.stock.db.StockLocation;
import com.axelor.data.Listener;
import com.axelor.data.csv.CSVImporter;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.db.Model;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaModel;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * БГ настройката на склада срещу истинска база (HSQLDB): шаблонът за дружество е идемпотентен,
 * заварените получават само липсващото, а BG_COA, приложен след стартирането, обновява поредиците
 * от стартирането, вместо да прави втори.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}. В тестовия
 * classpath няма axelor-production, затова „Производство“ и „Брак“ тук не се създават; че се
 * насочват, когато production е налице, доказва {@code BgStockTemplateTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgStockSetupIntegrationTest extends JpaSupport {

  /** Локациите на шаблона, които StockConfig без production може да носи. */
  private static final List<String> LOCATIONS_WITHOUT_PRODUCTION =
      List.of("BGSTK-1", "BGSTK-2", "BGSTK-3", "BGSTK-4", "BGSTK-7");

  private static <T> T tx(Supplier<T> work) {
    AtomicReference<T> out = new AtomicReference<>();
    JPA.runInTransaction(() -> out.set(work.get()));
    return out.get();
  }

  private static void ensureAppBase() {
    if (Beans.get(com.axelor.studio.db.repo.AppBaseRepository.class).all().fetchOne() == null) {
      com.axelor.studio.db.AppBase appBase = new com.axelor.studio.db.AppBase();
      // Новото дружество получава партньора си при създаването, а номерът му минава през
      // проверката за вече даден номер — SELECT на Partner, който HSQLDB не чете (формулата). В
      // приложението (PostgreSQL) тя остава; тук се изключва.
      appBase.setCheckExistingSequenceOnGeneration(false);
      JPA.save(appBase);
    }
  }

  /** Заварено дружество: без поредици на склада, без StockConfig. */
  private static Company company(String tag) {
    return tx(
        () -> {
          ensureAppBase();
          Currency currency = new Currency();
          currency.setName("Евро " + tag);
          currency.setCode("€");
          currency.setCodeISO("S" + tag);
          currency = JPA.save(currency);
          Company company = new Company();
          company.setName("Склад " + tag);
          company.setCode("C-" + tag);
          company.setCurrency(currency);
          return JPA.save(company);
        });
  }

  private static List<Sequence> sequences(Company company, String code) {
    return tx(
        () ->
            JPA.all(Sequence.class)
                .filter("self.codeSelect = ?1 AND self.company = ?2", code, company)
                .fetch());
  }

  private static List<StockLocation> templateLocations(Company company) {
    return tx(
        () ->
            JPA.all(StockLocation.class)
                .filter(
                    "self.company = ?1 AND self.importId LIKE ?2",
                    company,
                    company.getCode() + "-BGSTK-%")
                .fetch());
  }

  private static StockConfig config(Company company) {
    return tx(() -> JPA.all(StockConfig.class).filter("self.company = ?1", company).fetchOne());
  }

  private static SequenceVersion versionOf(Sequence sequence) {
    return JPA.all(SequenceVersion.class).filter("self.sequence = ?1", sequence).fetchOne();
  }

  @Test
  @DisplayName("стартиране два пъти — по една поредица, една конфигурация, по една локация")
  void шаблонът_е_идемпотентен() {
    Company company = company("IDEM");
    BgStockSetupService setup = Beans.get(BgStockSetupService.class);
    setup.ensureTemplate(tx(() -> JPA.find(Company.class, company.getId())));
    setup.ensureTemplate(tx(() -> JPA.find(Company.class, company.getId())));

    for (BgStockTemplate.SequenceSpec spec : BgStockTemplate.SEQUENCES) {
      List<Sequence> found = sequences(company, spec.code);
      assertEquals(1, found.size(), spec.code);
      assertEquals("C-IDEM-" + spec.templateImportId, found.get(0).getImportId());
    }
    assertEquals(
        1,
        (long) tx(() -> JPA.all(StockConfig.class).filter("self.company = ?1", company).count()));
    List<String> keys =
        templateLocations(company).stream()
            .map(l -> l.getImportId().substring("C-IDEM-".length()))
            .sorted()
            .collect(Collectors.toList());
    assertEquals(LOCATIONS_WITHOUT_PRODUCTION, keys);

    StockConfig config = config(company);
    tx(
        () -> {
          StockConfig c = JPA.find(StockConfig.class, config.getId());
          assertEquals("Основен склад", c.getReceiptDefaultStockLocation().getName());
          assertEquals("Основен склад", c.getPickupDefaultStockLocation().getName());
          assertEquals("Доставчици", c.getSupplierVirtualStockLocation().getName());
          assertEquals("Клиенти", c.getCustomerVirtualStockLocation().getName());
          assertEquals("Инвентаризация", c.getInventoryVirtualStockLocation().getName());
          assertEquals("Подизпълнители", c.getVirtualOutsourcingStockLocation().getName());
          return c;
        });
  }

  @Test
  @DisplayName("заварено дружество получава липсващото, а съществуващото не се пипа")
  void заварено_дружество() {
    Company company = company("OLD");
    // Собствена поредица за приемане, вече издала 56 документа, и собствена клиентска локация.
    StockLocation own =
        tx(
            () -> {
              Sequence sequence = new Sequence();
              sequence.setCompany(JPA.find(Company.class, company.getId()));
              sequence.setCodeSelect("inStockMove");
              sequence.setName("Собствена");
              sequence.setPrefixe("OWN");
              sequence.setPadding(4);
              sequence.setToBeAdded(1);
              SequenceVersion version = new SequenceVersion();
              version.setStartDate(LocalDate.of(2000, 1, 1));
              version.setNextNum(57L);
              sequence.addSequenceVersionListItem(version);
              JPA.save(sequence);

              StockLocation location = new StockLocation();
              location.setName("Моите клиенти");
              location.setTypeSelect(3);
              location.setCompany(JPA.find(Company.class, company.getId()));
              location = JPA.save(location);
              StockConfig config = new StockConfig();
              config.setCompany(JPA.find(Company.class, company.getId()));
              config.setCustomerVirtualStockLocation(location);
              JPA.save(config);
              return location;
            });

    Beans.get(BgStockSetupService.class)
        .ensureTemplate(tx(() -> JPA.find(Company.class, company.getId())));

    List<Sequence> receipt = sequences(company, "inStockMove");
    assertEquals(1, receipt.size(), "собствената поредица не се дублира");
    assertEquals("OWN", receipt.get(0).getPrefixe());
    assertEquals(57L, tx(() -> versionOf(receipt.get(0)).getNextNum()), "и не се пипа");
    assertEquals(1, sequences(company, "outStockMove").size(), "липсващата се създава");

    tx(
        () -> {
          StockConfig c = config(company);
          StockConfig fresh = JPA.find(StockConfig.class, c.getId());
          assertEquals(own.getId(), fresh.getCustomerVirtualStockLocation().getId());
          assertNotNull(fresh.getSupplierVirtualStockLocation());
          return c;
        });
    assertTrue(
        templateLocations(company).stream().noneMatch(l -> "Клиенти".equals(l.getName())),
        "заетото поле не ражда втора клиентска локация");
  }

  @Test
  @DisplayName("стартиране, после BG_COA — по една поредица на склада, следващият номер остава")
  void стартиране_после_шаблон() throws Exception {
    Company company = company("TPL");
    Beans.get(BgStockSetupService.class)
        .ensureTemplate(tx(() -> JPA.find(Company.class, company.getId())));
    tx(
        () -> {
          SequenceVersion v = versionOf(sequences(company, "outStockMove").get(0));
          v.setNextNum(57L);
          return JPA.save(v);
        });

    importBgCoaSequences(company);

    for (BgStockTemplate.SequenceSpec spec : BgStockTemplate.SEQUENCES) {
      List<Sequence> found = sequences(company, spec.code);
      assertEquals(1, found.size(), spec.code + ": шаблонът обновява, не прави втора");
      List<SequenceVersion> versions =
          tx(
              () ->
                  JPA.all(SequenceVersion.class)
                      .filter("self.sequence = ?1", found.get(0))
                      .fetch());
      assertEquals(1, versions.size(), spec.code + ": една отворена версия");
    }
    assertEquals(57L, tx(() -> versionOf(sequences(company, "outStockMove").get(0)).getNextNum()));
  }

  @Test
  @DisplayName("ново дружество, записано през хранилището, получава склада веднага")
  void ново_дружество() {
    Company saved =
        tx(
            () -> {
              ensureAppBase();
              Currency currency = new Currency();
              currency.setName("Евро NEW");
              currency.setCode("€");
              currency.setCodeISO("SNEW");
              currency = JPA.save(currency);
              Company company = new Company();
              company.setName("Склад NEW");
              company.setCode("C-NEW");
              company.setCurrency(currency);
              return Beans.get(CompanyRepository.class).save(company);
            });
    assertNotNull(config(saved), "StockConfig");
    assertEquals(1, sequences(saved, "inStockMove").size());
    assertEquals(LOCATIONS_WITHOUT_PRODUCTION.size(), templateLocations(saved).size());
  }

  @Test
  @DisplayName("стартиращият пазач попълва вида на семействата по код и пази избора на потребителя")
  void видът_на_семействата() {
    tx(
        () -> {
          for (String code : List.of("GOODS", "MAT", "SERV")) {
            if (JPA.all(ProductFamily.class).filter("self.code = ?1", code).fetchOne() == null) {
              ProductFamily f = new ProductFamily();
              f.setCode(code);
              f.setName(code);
              JPA.save(f);
            }
          }
          ProductFamily mat = JPA.all(ProductFamily.class).filter("self.code = 'MAT'").fetchOne();
          mat.setBgSaftInventoryType("50"); // избор на потребителя
          ProductFamily goods =
              JPA.all(ProductFamily.class).filter("self.code = 'GOODS'").fetchOne();
          goods.setBgSaftInventoryType(null);
          return JPA.save(mat);
        });

    Beans.get(BgStockSetupService.class).ensureFamilyInventoryTypes();
    Beans.get(BgStockSetupService.class).ensureFamilyInventoryTypes();

    tx(
        () -> {
          assertEquals("30", family("GOODS").getBgSaftInventoryType());
          assertEquals("50", family("MAT").getBgSaftInventoryType(), "изборът остава");
          String serv = family("SERV").getBgSaftInventoryType();
          assertTrue(serv == null || serv.isEmpty(), "услугите не са СМЗ");
          return null;
        });
  }

  private static ProductFamily family(String code) {
    return JPA.all(ProductFamily.class).filter("self.code = ?1", code).fetchOne();
  }

  @Test
  @DisplayName(
      "складовата сметка се попълва от сметкоплана на дружеството на реда, само ако е празна")
  void складовата_сметка() {
    Company company = company("ACC");
    AccountManagement row =
        tx(
            () -> {
              Company c = JPA.find(Company.class, company.getId());
              Account a = new Account();
              a.setCode("304");
              a.setName("Стоки");
              a.setCompany(c);
              JPA.save(a);
              ProductFamily goods = family("GOODS");
              if (goods == null) {
                goods = new ProductFamily();
                goods.setCode("GOODS");
                goods.setName("Стоки");
                goods = JPA.save(goods);
              }
              AccountManagement am = new AccountManagement();
              am.setTypeSelect(1);
              am.setCompany(c);
              am.setProductFamily(goods);
              return JPA.save(am);
            });

    Beans.get(BgStockSetupService.class).ensureFamilyStockAccounts();

    tx(
        () -> {
          AccountManagement fresh = JPA.find(AccountManagement.class, row.getId());
          assertNotNull(fresh.getBgStockAccount());
          assertEquals("304", fresh.getBgStockAccount().getCode());
          assertEquals(company.getId(), fresh.getBgStockAccount().getCompany().getId());
          return fresh;
        });
  }

  // ---------------------------------------------------------------------------
  // BG_COA: двата <input> за поредиците дословно от chart-config.xml, редовете 146–149
  // ---------------------------------------------------------------------------

  private static String classpathText(String name) throws IOException {
    try (InputStream in = BgStockSetupIntegrationTest.class.getResourceAsStream(name)) {
      assertNotNull(in, name);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String inputOf(String config, String file) {
    Matcher m =
        Pattern.compile("<input file=\"" + Pattern.quote(file) + "\".*?</input>", Pattern.DOTALL)
            .matcher(config);
    assertTrue(m.find(), file);
    return m.group();
  }

  private static String rowsOf(String csv, List<String> prefixes) {
    List<String> lines = Arrays.asList(csv.split("\r?\n"));
    return lines.get(0)
        + "\n"
        + lines.stream()
            .filter(l -> prefixes.stream().anyMatch(l::startsWith))
            .collect(Collectors.joining("\n"))
        + "\n";
  }

  private static void importBgCoaSequences(Company company) throws IOException {
    String config = classpathText("/l10n/chart-config.xml");
    Matcher head = Pattern.compile("<csv-inputs[^>]*>").matcher(config);
    assertTrue(head.find());
    Path dir = Files.createTempDirectory("bg-coa-stock-sequences");
    Path configFile = dir.resolve("config.xml");
    Files.write(
        configFile,
        ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + head.group()
                + inputOf(config, "base_sequence.csv")
                + inputOf(config, "base_sequenceVersion.csv")
                + "</csv-inputs>\n")
            .getBytes(StandardCharsets.UTF_8));
    List<String> ids = new ArrayList<>();
    List<String> versionIds = new ArrayList<>();
    BgStockTemplate.SEQUENCES.forEach(
        s -> {
          ids.add("\"" + s.templateImportId + "\";");
          versionIds.add("\"V" + s.templateImportId + "\";");
        });
    String coa = "/l10n/l10n_bgr/BG_COA/";
    Files.write(
        dir.resolve("base_sequence.csv"),
        rowsOf(classpathText(coa + "base_sequence.csv"), ids).getBytes(StandardCharsets.UTF_8));
    Files.write(
        dir.resolve("base_sequenceVersion.csv"),
        rowsOf(classpathText(coa + "base_sequenceVersion.csv"), versionIds)
            .getBytes(StandardCharsets.UTF_8));

    CSVImporter importer = new CSVImporter(configFile.toString(), dir.toString());
    Map<String, Object> context = new HashMap<>();
    context.put("_companyId", company.getId());
    context.put("_companyName", company.getName());
    context.put("_companyCode", company.getCode());
    context.put("_defaultBankDetailsId", 0);
    context.put("_dataFileName", "BG_COA.zip");
    importer.setContext(context);
    List<String> errors = new ArrayList<>();
    List<Integer> imported = new ArrayList<>();
    importer.addListener(
        new Listener() {
          @Override
          public void imported(Model bean) {}

          @Override
          public void imported(Integer total, Integer success) {
            imported.add(success);
          }

          @Override
          public void handle(Model bean, Exception e) {
            errors.add(e.toString());
          }
        });
    importer.run();
    assertEquals(Collections.emptyList(), errors);
    assertEquals(Arrays.asList(4, 4), imported, "по четири реда от двата файла");
  }

  /** Шаблон за печат, както го внася data-init на axelor-stock: име + модел. */
  private static PrintingTemplate printingTemplate(
      String name, String simpleName, String fullName) {
    return tx(
        () -> {
          MetaModel model =
              JPA.all(MetaModel.class).filter("self.fullName = ?1", fullName).fetchOne();
          if (model == null) {
            model = new MetaModel();
            model.setName(simpleName);
            model.setFullName(fullName);
            model.setPackageName(fullName.substring(0, fullName.lastIndexOf('.')));
            model = JPA.save(model);
          }
          PrintingTemplate existing =
              JPA.all(PrintingTemplate.class)
                  .filter("self.name = ?1 AND self.metaModel = ?2", name, model)
                  .fetchOne();
          if (existing != null) {
            return existing;
          }
          PrintingTemplate template = new PrintingTemplate();
          template.setName(name);
          template.setMetaModel(model);
          return JPA.save(template);
        });
  }

  /**
   * „Печат“ на складово място иска шаблон от конфигурацията на склада на дружеството. Празното се
   * попълва с шаблоните на axelor-stock; избраният шаблон не се пипа.
   */
  @Test
  @DisplayName("шаблоните за печат: празното се попълва, избраното остава")
  void шаблоните_за_печат() {
    PrintingTemplate location =
        printingTemplate(
            "StockLocation PDF", "StockLocation", "com.axelor.apps.stock.db.StockLocation");
    PrintingTemplate inventory =
        printingTemplate("Inventory PDF", "Inventory", "com.axelor.apps.stock.db.Inventory");
    PrintingTemplate own =
        printingTemplate("Собствен опис", "Inventory", "com.axelor.apps.stock.db.Inventory");

    Company company = company("PRT");
    tx(
        () -> {
          StockConfig config = new StockConfig();
          config.setCompany(JPA.find(Company.class, company.getId()));
          config.setInventoryPrintTemplate(JPA.find(PrintingTemplate.class, own.getId()));
          return JPA.save(config);
        });

    Beans.get(BgStockSetupService.class)
        .ensureTemplate(tx(() -> JPA.find(Company.class, company.getId())));

    tx(
        () -> {
          StockConfig fresh = JPA.find(StockConfig.class, config(company).getId());
          assertNotNull(fresh.getStockLocationPrintTemplate(), "справката на склада без шаблон");
          assertEquals(location.getId(), fresh.getStockLocationPrintTemplate().getId());
          assertEquals(
              own.getId(), fresh.getInventoryPrintTemplate().getId(), "избраният не се пипа");
          assertTrue(!own.getId().equals(inventory.getId()));
          return fresh;
        });
  }

  @Test
  @DisplayName("стартиращият listener вика настройката на склада")
  void listener_ът_я_вика() {
    Company company = company("LSN");
    Beans.get(BgLocalizationServerStartListener.class).ensureStockSetup();
    assertNotNull(config(company));
    assertEquals(1, sequences(company, "inventory").size());
  }
}

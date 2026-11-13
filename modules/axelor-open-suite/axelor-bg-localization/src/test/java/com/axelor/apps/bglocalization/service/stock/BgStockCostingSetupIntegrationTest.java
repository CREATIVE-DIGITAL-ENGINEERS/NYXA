package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.JournalType;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.bglocalization.db.BgStockCostingPolicy;
import com.axelor.apps.bglocalization.db.repo.BgStockCostingPolicyRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.data.Listener;
import com.axelor.data.csv.CSVImporter;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.db.Model;
import com.axelor.inject.Beans;
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
import javax.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ИЗП срещу истинска база (HSQLDB): журналът „Изписване на МЗ“ за заварените дружества е
 * идемпотентен и пази избора на потребителя; BG_COA, приложен след стартирането, не прави втори
 * журнал STK; политиката с LIFO при МСФО не стига до базата, а смяната на базата на дружеството се
 * отказва при LIFO.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgStockCostingSetupIntegrationTest extends JpaSupport {

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

  private static void ensureJournalType() {
    if (JPA.all(JournalType.class).filter("self.code = 'OTH'").fetchOne() == null) {
      JournalType type = new JournalType();
      type.setCode("OTH");
      type.setName("Други");
      JPA.save(type);
    }
  }

  /** Заварено дружество с AccountConfig, без журнал STK. */
  private static Company company(String tag) {
    return tx(
        () -> {
          ensureAppBase();
          ensureJournalType();
          Currency currency = new Currency();
          currency.setName("Евро " + tag);
          currency.setCode("€");
          currency.setCodeISO("I" + tag);
          currency = JPA.save(currency);
          Company company = new Company();
          company.setName("ИЗП " + tag);
          company.setCode("I-" + tag);
          company.setCurrency(currency);
          company = JPA.save(company);
          AccountConfig config = new AccountConfig();
          config.setCompany(company);
          JPA.save(config);
          return company;
        });
  }

  private static List<Journal> journals(Company company) {
    return tx(
        () ->
            JPA.all(Journal.class)
                .filter("self.company = ?1 AND self.code = 'STK'", company)
                .fetch());
  }

  private static List<Sequence> sequences(Company company) {
    return tx(
        () ->
            JPA.all(Sequence.class)
                .filter(
                    "self.company = ?1 AND self.importId = ?2", company, company.getCode() + "-150")
                .fetch());
  }

  private static AccountConfig config(Company company) {
    return tx(() -> JPA.all(AccountConfig.class).filter("self.company = ?1", company).fetchOne());
  }

  private static Company fresh(Company company) {
    return tx(() -> JPA.find(Company.class, company.getId()));
  }

  @Test
  @DisplayName("стартиране два пъти — един журнал STK, една поредица, AccountConfig сочи журнала")
  void журналът_е_идемпотентен() {
    Company company = company("IDEM");
    BgStockCostingSetupService setup = Beans.get(BgStockCostingSetupService.class);
    setup.ensureIssueJournal(fresh(company));
    setup.ensureIssueJournal(fresh(company));

    List<Journal> found = journals(company);
    assertEquals(1, found.size());
    assertEquals("I-IDEM-98", found.get(0).getImportId());
    assertEquals(1, sequences(company).size());
    tx(
        () -> {
          Journal j = JPA.find(Journal.class, found.get(0).getId());
          assertEquals("Изписване на МЗ", j.getName());
          assertEquals("OTH", j.getJournalType().getCode());
          assertEquals(company.getCode() + "-150", j.getSequence().getImportId());
          AccountConfig c = JPA.find(AccountConfig.class, config(company).getId());
          assertEquals(j.getId(), c.getBgStockIssueJournal().getId());
          return j;
        });
  }

  @Test
  @DisplayName("попълненият журнал в AccountConfig е избор на потребителя и не се пренасочва")
  void изборът_остава() {
    Company company = company("OWN");
    Journal own =
        tx(
            () -> {
              Journal j = new Journal();
              j.setCompany(JPA.find(Company.class, company.getId()));
              j.setCode("MYSTK");
              j.setName("Моят журнал");
              j = JPA.save(j);
              AccountConfig c = JPA.find(AccountConfig.class, config(company).getId());
              c.setBgStockIssueJournal(j);
              JPA.save(c);
              return j;
            });

    Beans.get(BgStockCostingSetupService.class).ensureIssueJournal(fresh(company));

    assertEquals(
        own.getId(), tx(() -> config(company).getBgStockIssueJournal().getId()), "не се пипа");
    assertEquals(1, journals(company).size(), "журналът на шаблона все пак е налице");
  }

  @Test
  @DisplayName("стартиране, после BG_COA — един журнал STK и една поредица 150")
  void стартиране_после_шаблон() throws Exception {
    Company company = company("TPL");
    Beans.get(BgStockCostingSetupService.class).ensureIssueJournal(fresh(company));

    importBgCoaJournal(company);

    assertEquals(1, journals(company).size(), "шаблонът обновява, не прави втори журнал");
    assertEquals(1, sequences(company).size(), "и втора поредица");
  }

  @Test
  @DisplayName("ново дружество, записано през хранилището, получава журнала веднага")
  void ново_дружество() {
    Company saved =
        tx(
            () -> {
              ensureAppBase();
              ensureJournalType();
              Currency currency = new Currency();
              currency.setName("Евро NEW");
              currency.setCode("€");
              currency.setCodeISO("INEW");
              currency = JPA.save(currency);
              Company company = new Company();
              company.setName("ИЗП NEW");
              company.setCode("I-NEW");
              company.setCurrency(currency);
              return Beans.get(CompanyRepository.class).save(company);
            });
    assertEquals(1, journals(saved).size());
  }

  // ── политиката срещу базата ─────────────────────────────────────────────

  private static Year fiscalYear(Company company, int year) {
    return tx(
        () -> {
          Year y = new Year();
          y.setName(String.valueOf(year));
          y.setCode("FY" + year + company.getCode());
          y.setTypeSelect(YearRepository.TYPE_FISCAL);
          y.setCompany(JPA.find(Company.class, company.getId()));
          y.setFromDate(LocalDate.of(year, 1, 1));
          y.setToDate(LocalDate.of(year, 12, 31));
          return JPA.save(y);
        });
  }

  @Test
  @DisplayName("политика с LIFO при МСФО не стига до базата")
  void lifo_при_мсфо_не_се_записва() {
    Company company = company("IFRS");
    tx(
        () -> {
          Company c = JPA.find(Company.class, company.getId());
          c.setBgAccountingFramework(BgStockCostingRules.IFRS);
          return JPA.save(c);
        });
    Year year = fiscalYear(company, 2099);
    assertThrows(
        PersistenceException.class,
        () ->
            tx(
                () -> {
                  BgStockCostingPolicy p = new BgStockCostingPolicy();
                  p.setCompany(JPA.find(Company.class, company.getId()));
                  p.setYear(JPA.find(Year.class, year.getId()));
                  p.setCostingMethodSelect(BgStockCostingRules.LIFO);
                  p.setAverageScopeSelect(BgStockCostingRules.SCOPE_WAREHOUSE);
                  p.setPostingModeSelect(BgStockCostingRules.POSTING_CURRENT);
                  return Beans.get(BgStockCostingPolicyRepository.class).save(p);
                }));
    assertEquals(
        0L,
        (long)
            tx(
                () ->
                    JPA.all(BgStockCostingPolicy.class)
                        .filter("self.company.id = ?1", company.getId())
                        .count()));
  }

  @Test
  @DisplayName("дружество с LIFO политика за незавършила година не минава на МСФО")
  void смяна_на_базата_при_lifo() {
    Company company = company("NSS");
    tx(
        () -> {
          Company c = JPA.find(Company.class, company.getId());
          c.setBgAccountingFramework(BgStockCostingRules.NSS);
          return JPA.save(c);
        });
    Year year = fiscalYear(company, 2098);
    tx(
        () -> {
          BgStockCostingPolicy p = new BgStockCostingPolicy();
          p.setCompany(JPA.find(Company.class, company.getId()));
          p.setYear(JPA.find(Year.class, year.getId()));
          p.setCostingMethodSelect(BgStockCostingRules.LIFO);
          p.setAverageScopeSelect(BgStockCostingRules.SCOPE_WAREHOUSE);
          p.setPostingModeSelect(BgStockCostingRules.POSTING_CURRENT);
          return Beans.get(BgStockCostingPolicyRepository.class).save(p);
        });

    PersistenceException e =
        assertThrows(
            PersistenceException.class,
            () ->
                tx(
                    () -> {
                      Company c = JPA.find(Company.class, company.getId());
                      c.setBgAccountingFramework(BgStockCostingRules.IFRS);
                      return Beans.get(CompanyRepository.class).save(c);
                    }));
    assertTrue(e.getMessage().contains("2098"), e.getMessage());
  }

  // ── 120 с виновно лице е 442, не 498 ─────────────────────────────────────

  private static com.axelor.apps.account.db.Account account(Company company, String code) {
    com.axelor.apps.account.db.Account a = new com.axelor.apps.account.db.Account();
    a.setCode(code);
    a.setName(code);
    a.setCompany(company);
    return JPA.save(a);
  }

  private static com.axelor.apps.bglocalization.db.BgStockCostingAccountRule rule(
      String code, boolean partner, com.axelor.apps.account.db.Account account) {
    com.axelor.apps.bglocalization.db.BgStockCostingAccountRule r =
        new com.axelor.apps.bglocalization.db.BgStockCostingAccountRule();
    r.setMovementCode(code);
    r.setWithResponsiblePartner(partner);
    r.setAccount(account);
    return r;
  }

  @Test
  @DisplayName("заварена политика: 120 с лице по 498 става 442; друг избор не се пипа")
  void заварените_политики_498_стават_442() {
    Company company = company("442");
    Year year = fiscalYear(company, 2097);
    Year other = fiscalYear(company, 2096);
    Long[] ids =
        tx(
            () -> {
              Company c = JPA.find(Company.class, company.getId());
              com.axelor.apps.account.db.Account a498 = account(c, "498");
              account(c, "442");
              com.axelor.apps.account.db.Account own = account(c, "4421");
              BgStockCostingPolicy old = new BgStockCostingPolicy();
              old.setCompany(c);
              old.setYear(JPA.find(Year.class, year.getId()));
              old.setCostingMethodSelect(BgStockCostingRules.WAP_PERPETUAL);
              old.setAverageScopeSelect(BgStockCostingRules.SCOPE_WAREHOUSE);
              old.setPostingModeSelect(BgStockCostingRules.POSTING_CURRENT);
              old.addAccountRuleListItem(rule("120", true, a498));
              old.addAccountRuleListItem(rule("160", false, a498)); // 498 за друг код — чуждо
              old = JPA.save(old);
              BgStockCostingPolicy chosen = new BgStockCostingPolicy();
              chosen.setCompany(c);
              chosen.setYear(JPA.find(Year.class, other.getId()));
              chosen.setCostingMethodSelect(BgStockCostingRules.WAP_PERPETUAL);
              chosen.setAverageScopeSelect(BgStockCostingRules.SCOPE_WAREHOUSE);
              chosen.setPostingModeSelect(BgStockCostingRules.POSTING_CURRENT);
              chosen.addAccountRuleListItem(rule("120", true, own));
              chosen = JPA.save(chosen);
              return new Long[] {old.getId(), chosen.getId()};
            });

    Beans.get(BgStockCostingSetupService.class).ensureShortagePartnerAccounts();
    Beans.get(BgStockCostingSetupService.class).ensureShortagePartnerAccounts();

    tx(
        () -> {
          BgStockCostingPolicy old = JPA.find(BgStockCostingPolicy.class, ids[0]);
          for (com.axelor.apps.bglocalization.db.BgStockCostingAccountRule r :
              old.getAccountRuleList()) {
            String expected = "120".equals(r.getMovementCode()) ? "442" : "498";
            assertEquals(expected, r.getAccount().getCode(), r.getMovementCode());
          }
          BgStockCostingPolicy chosen = JPA.find(BgStockCostingPolicy.class, ids[1]);
          assertEquals("4421", chosen.getAccountRuleList().get(0).getAccount().getCode());
          return null;
        });
  }

  @Test
  @DisplayName("стартиращият listener вика и смяната 498 → 442")
  void listener_ът_вика_смяната() throws IOException {
    String listener =
        new String(
            Files.readAllBytes(
                java.nio.file.Paths.get(
                    "src/main/java/com/axelor/apps/bglocalization/listener/"
                        + "BgLocalizationServerStartListener.java")),
            StandardCharsets.UTF_8);
    assertTrue(listener.contains("ensureShortagePartnerAccounts()"));
  }

  // ── BG_COA: входовете за поредиците и журналите дословно от chart-config.xml ──

  private static String classpathText(String name) throws IOException {
    try (InputStream in = BgStockCostingSetupIntegrationTest.class.getResourceAsStream(name)) {
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

  private static void importBgCoaJournal(Company company) throws IOException {
    String config = classpathText("/l10n/chart-config.xml");
    Matcher head = Pattern.compile("<csv-inputs[^>]*>").matcher(config);
    assertTrue(head.find());
    Path dir = Files.createTempDirectory("bg-coa-stock-issue-journal");
    Path configFile = dir.resolve("config.xml");
    Files.write(
        configFile,
        ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + head.group()
                + inputOf(config, "base_sequence.csv")
                + inputOf(config, "base_sequenceVersion.csv")
                + inputOf(config, "account_journal.csv")
                + "</csv-inputs>\n")
            .getBytes(StandardCharsets.UTF_8));
    String coa = "/l10n/l10n_bgr/BG_COA/";
    Files.write(
        dir.resolve("base_sequence.csv"),
        rowsOf(classpathText(coa + "base_sequence.csv"), List.of("\"150\";"))
            .getBytes(StandardCharsets.UTF_8));
    Files.write(
        dir.resolve("base_sequenceVersion.csv"),
        rowsOf(classpathText(coa + "base_sequenceVersion.csv"), List.of("\"V150\";"))
            .getBytes(StandardCharsets.UTF_8));
    Files.write(
        dir.resolve("account_journal.csv"),
        rowsOf(classpathText(coa + "account_journal.csv"), List.of("\"98\";"))
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
    assertEquals(Arrays.asList(1, 1, 1), imported, "по един ред от трите файла");
  }
}

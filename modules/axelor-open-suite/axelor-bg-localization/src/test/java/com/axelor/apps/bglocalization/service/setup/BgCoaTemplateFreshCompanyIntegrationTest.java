package com.axelor.apps.bglocalization.service.setup;

import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.COA;
import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.importFromTemplate;
import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.newCompany;
import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.packageLikeDataInit;
import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.tx;
import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.txChecked;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountingConfigTemplate;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.fixedasset.BgFixedAssetSchemaCheck;
import com.axelor.apps.bglocalization.service.payroll.BgTrzSchemaCheck;
import com.axelor.apps.napsubmissions.common.immutability.SaftReportSchemaCheck;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * НОВО ДРУЖЕСТВО ПОЛУЧАВА ДНЕШНИЯ СМЕТКОПЛАН — през ЗИПА на шаблона, както в средата.
 *
 * <p>{@code BgAccountConfigTemplateIntegrationTest} внася CSV-тата направо от кода и затова не може
 * да види остарял зип. Тук пътят е истинският: data-init пакетира BG_COA в {@code
 * AccountingConfigTemplate.metaFile} ({@code ImportAccountingConfigTemplate}), а новото дружество
 * се внася от него ({@code AccountingConfigTemplateService#installProcess} — бутонът в
 * конфигурацията на сметките).
 *
 * <p>Целта (решение от 23.10.2026, чиста среда): 40901 „Ваучери за храна“ (разчет), 60212 „Разходи
 * за други външни услуги“ под 602, а 602 е групова сметка ({@code view}).
 *
 * <p>Вторият тест пресъздава заварена среда: зипът е от сметкоплана отпреди НАП (без 40901 и 60212,
 * 602 — осчетоводима). Контролата доказва, че тестът вижда остарелия зип; после {@link
 * BgCoaTemplateRefresh} го прави наново и следващото ново дружество получава целта.
 *
 * <p>⚠️ В HSQLDB редът на {@code account_accountConfig_payments.csv} пада на формулата на Partner
 * (виж {@code BgAccountConfigTemplateIntegrationTest#dropEmptyColumn}); вносът продължава със
 * следващите входове, а тук се сверяват само сметките.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgCoaTemplateFreshCompanyIntegrationTest extends JpaSupport {

  /** Четене без транзакция — вносът вече е записал в своите. */
  private static Account account(Long companyId, String code) {
    JPA.em().clear();
    return JPA.all(Account.class)
        .filter("self.company.id = ?1 AND self.code = ?2", companyId, code)
        .fetchOne();
  }

  private static void assertTodaysChart(Long companyId) {
    Account vouchers = account(companyId, "40901");
    assertNotNull(vouchers, "40901 „Ваучери за храна“ липсва");
    assertEquals("payable", vouchers.getAccountType().getTechnicalTypeSelect());

    Account services = account(companyId, "60212");
    assertNotNull(services, "60212 липсва");
    assertNotNull(services.getParentAccount(), "60212 е без родител");
    assertEquals("602", services.getParentAccount().getCode());

    Account group = account(companyId, "602");
    assertNotNull(group, "602 липсва");
    assertEquals("view", group.getAccountType().getTechnicalTypeSelect(), "602 не е групова");
  }

  @Test
  @DisplayName("Чиста база: зипът от data-init → ново дружество получава 40901, 60212; 602 е view")
  void новото_дружество_на_чиста_база_получава_днешния_сметкоплан() throws Exception {
    Long templateId = packageLikeDataInit();
    Long companyId = tx(() -> newCompany("FRESH").getId());

    importFromTemplate(companyId, templateId);

    assertTodaysChart(companyId);
  }

  @Test
  @DisplayName("Стар зип: контрола без 40901 → опресняване → новото дружество го получава")
  void старият_зип_се_опреснява_и_новото_дружество_получава_днешния_сметкоплан() throws Exception {
    Long templateId = packageLikeDataInit();
    MetaFile stale = uploadStaleZip();
    txChecked(
        () -> {
          AccountingConfigTemplate t = JPA.find(AccountingConfigTemplate.class, templateId);
          t.setMetaFile(JPA.find(MetaFile.class, stale.getId()));
          JPA.save(t);
        });

    // Контрола: от стария зип новото дружество НЕ получава 40901, а 602 е осчетоводима.
    Long before = tx(() -> newCompany("STALE").getId());
    importFromTemplate(before, templateId);
    // Вносът е минал (иначе „няма 40901“ не доказва нищо): 4091 е с тип, 602 е осчетоводима.
    Account sibling = account(before, "4091");
    assertNotNull(sibling, "контролата: вносът от стария зип не е минал");
    assertNotNull(sibling.getAccountType(), "контролата: сметките са без тип — вносът не е минал");
    assertEquals("charge", account(before, "602").getAccountType().getTechnicalTypeSelect());
    assertNull(account(before, "40901"), "контролата не е стар зип — тестът не би видял нищо");

    // Опресняването, както го вика вносът при старт.
    Long refreshed = refreshOnce(templateId);
    assertNotEquals(stale.getId(), refreshed, "зипът не е сменен");
    assertEquals(refreshed, refreshOnce(templateId), "второ опресняване качи нов зип");

    Long after = tx(() -> newCompany("AFTER").getId());
    importFromTemplate(after, templateId);
    assertTodaysChart(after);
  }

  /**
   * Чистата база стартира и ВТОРИ път, без нито един файл от db/migration.
   *
   * <p>Проверките при старт ({@code BgPartnerSchemaCheck}, {@code SaftReportSchemaCheck}) вървят в
   * Hibernate Integrator. При първия старт таблиците още ги няма и двете пропускат („таблица няма —
   * няма какво да се проверява“). При втория те четат схемата, която ddl=update е създал от СЛЕТИЯ
   * домейн — същата, която Hibernate е създал и тук. Сверява се точно тяхното условие:
   *
   * <ul>
   *   <li>base_partner: НЯМА уникален индекс само върху partner_seq (иначе отказ — файлът
   *       2026-10-20-partner-seq-per-company.sql), а има върху (company, partner_seq);
   *   <li>napsubmissions_saft_report: всичките колони на T1 (иначе отказ — файлът
   *       2026-10-21-saft-report-annual-ondemand.sql);
   *   <li>активите ({@code BgFixedAssetSchemaCheck}, SAF-T A1): колоните на A1 и уникален индекс
   *       върху (company, fixed_asset_seq) (иначе отказ — файлът
   *       2026-10-23-fixed-asset-inventory-number.sql). Същото с истинския Integrator върху
   *       PostgreSQL — {@code BgFixedAssetCleanSchemaDbTest};
   *   <li>касовата отчетност на дружеството (SAF-T, {@code BgSaftCashAccounting}): двете колони на
   *       base_company — само от домейна, без SQL файл; четецът на SAF-T ги чете;
   *   <li>фишът и обезщетението ({@link BgTrzSchemaCheck}): колона company и без уникален индекс
   *       само по reference (иначе отказ — файлът 2026-10-21-trz-numbering-per-company.sql).
   * </ul>
   *
   * <p>{@link BgTrzSchemaCheck} чете през DatabaseMetaData и се вика направо. Другите проверки
   * четат pg_catalog и извън PostgreSQL връщат „готово“ — затова тук се сверява тяхното условие.
   * Целият старт с всички интегратори (два пъти, плюс заварена база) — {@code
   * BgCleanDatabaseStartIntegrationTest}.
   */
  @Test
  @DisplayName("Чиста база стартира и втори път: схемата от домейна минава проверките при старт")
  void чистата_база_минава_проверките_при_старт() {
    List<List<String>> partnerUniques = new ArrayList<>();
    List<List<String>> assetUniques = new ArrayList<>();
    Set<String> saftColumns = new TreeSet<>();
    Set<List<String>> assetColumns = new HashSet<>();
    Set<String> companyColumns = new TreeSet<>();
    AtomicReference<String> trzRefusal = new AtomicReference<>();
    JPA.em()
        .unwrap(Session.class)
        .doWork(
            c -> {
              DatabaseMetaData md = c.getMetaData();
              partnerUniques.addAll(uniques(md, "base_partner"));
              assetUniques.addAll(uniques(md, "account_fixed_asset"));
              saftColumns.addAll(columns(md, SaftReportSchemaCheck.TABLE));
              companyColumns.addAll(columns(md, "base_company"));
              for (List<String> tableColumn : BgFixedAssetSchemaCheck.COLUMNS) {
                if (columns(md, tableColumn.get(0)).contains(tableColumn.get(1))) {
                  assetColumns.add(tableColumn);
                }
              }
              trzRefusal.set(BgTrzSchemaCheck.refusal(c));
            });

    assertFalse(partnerUniques.isEmpty(), "base_partner без уникални индекси — таблицата липсва?");
    assertFalse(
        partnerUniques.contains(List.of("partner_seq")),
        "чиста база получава глобалното UNIQUE (partner_seq) — вторият старт ще откаже: "
            + partnerUniques);
    assertTrue(
        partnerUniques.stream()
            .anyMatch(cols -> new TreeSet<>(cols).equals(Set.of("company", "partner_seq"))),
        "няма UNIQUE (company, partner_seq): " + partnerUniques);
    assertTrue(
        saftColumns.containsAll(SaftReportSchemaCheck.COLUMNS),
        "napsubmissions_saft_report без колоните на T1: " + saftColumns);
    assertEquals(
        new HashSet<>(BgFixedAssetSchemaCheck.COLUMNS), assetColumns, "колоните на A1 от домейна");
    assertTrue(
        assetUniques.stream()
            .anyMatch(
                cols ->
                    new TreeSet<>(cols)
                        .equals(new TreeSet<>(BgFixedAssetSchemaCheck.UNIQUE_COLUMNS))),
        "няма UNIQUE (company, fixed_asset_seq) — вторият старт ще откаже: " + assetUniques);
    assertTrue(
        companyColumns.containsAll(
            List.of("bg_vat_cash_accounting_from", "bg_vat_cash_accounting_to")),
        "base_company без колоните на касовата отчетност: " + companyColumns);
    assertNull(trzRefusal.get(), "вторият старт ще откаже");
  }

  /** Уникалните индекси на таблицата — всеки като списък колони (малки букви). */
  private static List<List<String>> uniques(DatabaseMetaData md, String name) throws SQLException {
    Map<String, List<String>> byIndex = new TreeMap<>();
    try (ResultSet rs = md.getIndexInfo(null, null, table(md, name), true, false)) {
      while (rs.next()) {
        String column = rs.getString("COLUMN_NAME");
        if (column != null) {
          byIndex
              .computeIfAbsent(rs.getString("INDEX_NAME"), k -> new ArrayList<>())
              .add(column.toLowerCase(Locale.ROOT));
        }
      }
    }
    return new ArrayList<>(byIndex.values());
  }

  /** Колоните на таблицата (малки букви). */
  private static Set<String> columns(DatabaseMetaData md, String name) throws SQLException {
    Set<String> out = new TreeSet<>();
    try (ResultSet rs = md.getColumns(null, null, table(md, name), null)) {
      while (rs.next()) {
        out.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
      }
    }
    return out;
  }

  /** Името, както го пази базата (HSQLDB — с главни букви). */
  private static String table(DatabaseMetaData md, String name) throws SQLException {
    return md.storesUpperCaseIdentifiers() ? name.toUpperCase(Locale.ROOT) : name;
  }

  private static Long refreshOnce(Long templateId) {
    AtomicReference<Long> id = new AtomicReference<>();
    txChecked(
        () -> {
          AccountingConfigTemplate t = JPA.find(AccountingConfigTemplate.class, templateId);
          Beans.get(BgCoaTemplateRefresh.class).refresh(t, Collections.emptyMap());
          id.set(JPA.save(t).getMetaFile().getId());
        });
    return id.get();
  }

  /**
   * BG_COA отпреди НАП, колкото е нужно за целта: без 40901 и 60212, а 602 — разходна, не група.
   * Всичко друго е днешното, тоест разликата е само в трите проверявани сметки.
   */
  private static MetaFile uploadStaleZip() throws Exception {
    Path dir = Files.createTempDirectory("bg-coa-stale");
    try (DirectoryStream<Path> csv = Files.newDirectoryStream(COA, "*.csv")) {
      for (Path file : csv) {
        Files.copy(file, dir.resolve(file.getFileName()));
      }
    }
    Path accounts = dir.resolve("account_account.csv");
    List<String> kept = new ArrayList<>();
    for (String line : Files.readAllLines(accounts, StandardCharsets.UTF_8)) {
      if (line.startsWith("\"40901\";") || line.startsWith("\"60212\";")) {
        continue;
      }
      if (line.startsWith("\"602\";")) {
        line = line.replaceFirst("^(\"602\";\"[^\"]*\";\"[^\"]*\";)\"1\";", "$1\"13\";");
      }
      kept.add(line);
    }
    Files.write(accounts, kept, StandardCharsets.UTF_8);

    File zip = File.createTempFile("BG_COA", ".zip");
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip.toPath()))) {
      try (DirectoryStream<Path> csv = Files.newDirectoryStream(dir, "*.csv")) {
        for (Path file : csv) {
          out.putNextEntry(new ZipEntry(file.getFileName().toString()));
          out.write(Files.readAllBytes(file));
          out.closeEntry();
        }
      }
    }
    AtomicReference<MetaFile> uploaded = new AtomicReference<>();
    txChecked(() -> uploaded.set(Beans.get(MetaFiles.class).upload(zip)));
    return uploaded.get();
  }
}

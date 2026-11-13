package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ФАЙЛЪТ ОТ automated.txt ({@code db/migration/2026-11-12-asset-journal-company.sql}) — приложен от
 * ФИКСТУРАТА, както го пуска деплоят: една транзакция около файла, всяка грешка я връща.
 *
 * <p>На PostgreSQL, в СОБСТВЕНА временна база (създава се и се трие тук; общата не се пипа):
 * дневниците, категориите ДМА, активите и движенията — само колоните, които файлът чете.
 *
 * <p>Без база (CI) — пропуснат с причина ({@link BgSkip}).
 */
class BgAssetJournalCompanyMigrationDbTest {

  private static final String FILE = "/db/migration/2026-11-12-asset-journal-company.sql";

  private static String scratch;
  private static String baseUrl;

  @BeforeAll
  static void createScratchDatabase() {
    baseUrl = BgTestDb.url();
    scratch = "nyxa_asset_journal_it_" + System.nanoTime();
    try (Connection admin = connect(baseUrl);
        Statement st = admin.createStatement()) {
      st.execute("CREATE DATABASE " + scratch + " TEMPLATE template0");
    } catch (SQLException e) {
      BgSkip.because("временна PostgreSQL база не може да се създаде: " + e.getMessage());
    }
  }

  @AfterAll
  static void dropScratchDatabase() throws SQLException {
    if (scratch == null || baseUrl == null) {
      return;
    }
    try (Connection admin = connect(baseUrl);
        Statement st = admin.createStatement()) {
      st.execute("DROP DATABASE IF EXISTS " + scratch);
    }
  }

  private static Connection connect(String url) throws SQLException {
    try {
      return DriverManager.getConnection(url, BgTestDb.user(), BgTestDb.password());
    } catch (SQLException e) {
      BgSkip.because("PostgreSQL не е достъпна (" + url + "): " + e.getMessage());
      throw e;
    }
  }

  private static Connection scratchConnection() throws SQLException {
    int slash = baseUrl.lastIndexOf('/');
    int query = baseUrl.indexOf('?', slash);
    String url =
        baseUrl.substring(0, slash + 1) + scratch + (query < 0 ? "" : baseUrl.substring(query));
    return connect(url);
  }

  private static String file() throws IOException {
    try (InputStream in = BgAssetJournalCompanyMigrationDbTest.class.getResourceAsStream(FILE)) {
      assertNotNull(in, "файлът е на classpath-а: " + FILE);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** Като деплоя: една транзакция около файла; грешка → ROLLBACK. Връща NOTICE-ите. */
  private static List<String> applyAsPipeline(Connection c) throws SQLException, IOException {
    List<String> notices = new ArrayList<>();
    c.setAutoCommit(false);
    try (Statement st = c.createStatement()) {
      st.execute(file());
      for (SQLWarning w = st.getWarnings(); w != null; w = w.getNextWarning()) {
        notices.add(w.getMessage());
      }
      c.commit();
    } catch (SQLException e) {
      c.rollback();
      throw e;
    } finally {
      c.setAutoCommit(true);
    }
    return notices;
  }

  private static void exec(Connection c, String... sql) throws SQLException {
    try (Statement st = c.createStatement()) {
      for (String s : sql) {
        st.execute(s);
      }
    }
  }

  private static void schema(Connection c) throws SQLException {
    exec(
        c,
        "DROP TABLE IF EXISTS account_move, account_fixed_asset, account_fixed_asset_category,"
            + " account_journal",
        "CREATE TABLE account_journal (id bigint PRIMARY KEY, version int, company bigint,"
            + " code varchar(255))",
        "CREATE TABLE account_fixed_asset_category (id bigint PRIMARY KEY, version int,"
            + " company bigint, journal bigint)",
        "CREATE TABLE account_fixed_asset (id bigint PRIMARY KEY, version int, company bigint,"
            + " journal bigint)",
        "CREATE TABLE account_move (id bigint PRIMARY KEY, version int, company bigint,"
            + " journal bigint, status_select int)");
  }

  /**
   * Дружество 1 — първото с BG_COA (MOA 10); 2 — с чужд MOA и свой (20); 3 — без MOA; 4 — с два MOA
   * (кой да вземе файлът, не е ясно).
   */
  private static void existing(Connection c) throws SQLException {
    schema(c);
    exec(
        c,
        "INSERT INTO account_journal (id, version, company, code) VALUES"
            + " (10, 0, 1, 'MOA'), (20, 0, 2, 'MOA'), (30, 0, 3, 'OTH'),"
            + " (40, 0, 4, 'MOA'), (41, 0, 4, 'MOA')",
        "INSERT INTO account_fixed_asset_category (id, version, company, journal) VALUES"
            + " (100, 0, 1, 10), (101, 0, 2, 10), (102, 0, 3, 10), (103, 0, 4, 10)",
        "INSERT INTO account_fixed_asset (id, version, company, journal) VALUES"
            + " (200, 0, 2, 10), (201, 0, 1, 10)",
        "INSERT INTO account_move (id, version, company, journal, status_select) VALUES"
            + " (300, 0, 2, 10, 1), (301, 0, 2, 10, 3), (302, 0, 2, 10, 2), (303, 0, 1, 10, 1)");
  }

  /** „таблица:id“ → „дневник/версия“. */
  private static Map<String, String> state(Connection c) throws SQLException {
    Map<String, String> out = new LinkedHashMap<>();
    for (String table :
        List.of("account_fixed_asset_category", "account_fixed_asset", "account_move")) {
      try (Statement st = c.createStatement();
          ResultSet rs =
              st.executeQuery("SELECT id, journal, version FROM " + table + " ORDER BY id")) {
        while (rs.next()) {
          out.put(table + ":" + rs.getLong(1), rs.getLong(2) + "/" + rs.getInt(3));
        }
      }
    }
    return out;
  }

  private static boolean has(List<String> notices, String text) {
    return notices.stream().anyMatch(n -> n.contains(text));
  }

  @Test
  @DisplayName(
      "Категория, актив и чернова с чужд MOA → MOA на своето дружество; осчетоводеното и „в"
          + " дневника“ — същите; без свой дневник — нищо и NOTICE; второ пускане — без промяна")
  void файлът() throws Exception {
    try (Connection c = scratchConnection()) {
      existing(c);
      List<String> notices = applyAsPipeline(c);
      Map<String, String> after = state(c);

      assertEquals("10/0", after.get("account_fixed_asset_category:100"), "своят — непокътнат");
      assertEquals("20/1", after.get("account_fixed_asset_category:101"), "чуждият → своя MOA");
      assertEquals("10/0", after.get("account_fixed_asset_category:102"), "без свой MOA — нищо");
      assertEquals("10/0", after.get("account_fixed_asset_category:103"), "два свои MOA — нищо");
      assertEquals("20/1", after.get("account_fixed_asset:200"), "активът → своя MOA");
      assertEquals("10/0", after.get("account_fixed_asset:201"), "своят — непокътнат");
      assertEquals("20/1", after.get("account_move:300"), "черновата → своя MOA");
      assertEquals("10/0", after.get("account_move:301"), "осчетоводеното — НЕ се пипа");
      assertEquals("10/0", after.get("account_move:302"), "„в дневника“ — НЕ се пипа");
      assertEquals("10/0", after.get("account_move:303"), "своята чернова — непокътната");

      assertTrue(has(notices, "категории ДМА 1, активи 1, чернови 1"), notices.toString());
      assertTrue(has(notices, "на дружество 3 с дневник MOA"), notices.toString());
      assertTrue(has(notices, "на дружество 4 с дневник MOA"), notices.toString());
      assertTrue(has(notices, "НЕ СЕ ПИПА: 1 движения в статус 2"), notices.toString());
      assertTrue(has(notices, "НЕ СЕ ПИПА: 1 движения в статус 3"), notices.toString());

      List<String> again = applyAsPipeline(c);
      assertEquals(after, state(c), "второ пускане — без промяна, и версиите не мърдат");
      assertTrue(has(again, "категории ДМА 0, активи 0, чернови 0"), again.toString());
    }
  }

  @Test
  @DisplayName("Нова база (таблиците от домейна, без редове) — празен ход, без грешка")
  void новата_база() throws Exception {
    try (Connection c = scratchConnection()) {
      schema(c);
      List<String> notices = applyAsPipeline(c);
      assertTrue(has(notices, "категории ДМА 0, активи 0, чернови 0"), notices.toString());
      assertEquals(Map.of(), state(c));
    }
  }
}

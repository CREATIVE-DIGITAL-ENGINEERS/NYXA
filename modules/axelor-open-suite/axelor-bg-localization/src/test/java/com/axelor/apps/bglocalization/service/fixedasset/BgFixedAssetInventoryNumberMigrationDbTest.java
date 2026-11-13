package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * МИГРАЦИОННИЯТ ФАЙЛ ({@code db/migration/2026-10-23-fixed-asset-inventory-number.sql}) — приложен
 * от ФИКСТУРАТА в една транзакция около файла, всяка грешка я връща. Заедно с него — проверката при
 * старт ({@link BgFixedAssetSchemaCheck}).
 *
 * <p>На PostgreSQL, в СОБСТВЕНА временна база (създава се и се трие тук; общата не се пипа):
 * таблиците на актива и на причината във вида на старата версия, със заварени активи.
 *
 * <p>Без база (CI) — пропуснат с причина ({@link BgSkip}).
 */
class BgFixedAssetInventoryNumberMigrationDbTest {

  private static final String FILE = "/db/migration/2026-10-23-fixed-asset-inventory-number.sql";

  /**
   * Името, което Hibernate дава на {@code <unique-constraint columns="company,fixedAssetSeq"/>} —
   * сверено с истински старт в {@link BgFixedAssetCleanSchemaDbTest}.
   */
  static final String UK_NAME = "uk_a53q3sjcj39twi5ixjpsj1ge4";

  private static String scratch;
  private static String baseUrl;

  @BeforeAll
  static void createScratchDatabase() {
    baseUrl = BgTestDb.url();
    scratch = "nyxa_fixed_asset_a1_it_" + System.nanoTime();
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
    try (InputStream in =
        BgFixedAssetInventoryNumberMigrationDbTest.class.getResourceAsStream(FILE)) {
      assertNotNull(in, "файлът е на classpath-а: " + FILE);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** Като обвивката на пайплайна: една транзакция около файла; грешка → ROLLBACK. */
  private static void applyAsPipeline(Connection c) throws SQLException, IOException {
    c.setAutoCommit(false);
    try (Statement st = c.createStatement()) {
      st.execute(file());
      c.commit();
    } catch (SQLException e) {
      c.rollback();
      throw e;
    } finally {
      c.setAutoCommit(true);
    }
  }

  private static void exec(Connection c, String... sql) throws SQLException {
    try (Statement st = c.createStatement()) {
      for (String s : sql) {
        st.execute(s);
      }
    }
  }

  /**
   * Старата схема: актив (дружество, номер, номер от Microinvest, статус, версия) и причина.
   *
   * <ul>
   *   <li>дружество 1: „001“ уникален → попълва се; „002“ на два актива → остават празни; „IMMO-1“
   *       като номер от Microinvest, но вече зает от заварен актив → остава празен;
   *   <li>дружество 2: същото „001“ → попълва се (уникалността е в дружеството);
   *   <li>празният низ за номер става NULL („няма номер“), с нова версия; NULL — непокътнат.
   * </ul>
   */
  private static void oldShape(Connection c) throws SQLException {
    exec(
        c,
        "DROP TABLE IF EXISTS account_fixed_asset",
        "DROP TABLE IF EXISTS account_asset_disposal_reason",
        "CREATE TABLE account_fixed_asset (id bigint PRIMARY KEY, version int, company bigint,"
            + " fixed_asset_seq varchar(255), bg_microinvest_inventory_number varchar(255),"
            + " status_select int)",
        "CREATE TABLE account_asset_disposal_reason (id bigint PRIMARY KEY, version int,"
            + " code varchar(255))",
        "INSERT INTO account_fixed_asset (id, version, company, fixed_asset_seq,"
            + " bg_microinvest_inventory_number, status_select) VALUES"
            + " (1, 0, 1, NULL, '001', 2),"
            + " (2, 0, 1, NULL, '002', 2),"
            + " (3, 0, 1, '', ' 002 ', 2),"
            + " (4, 0, 1, 'IMMO-1', NULL, 2),"
            + " (5, 0, 1, NULL, 'IMMO-1', 2),"
            + " (6, 0, 2, NULL, '001', 2),"
            + " (7, 0, 1, NULL, NULL, 1),"
            + " (8, 0, 1, '', NULL, 1)",
        "INSERT INTO account_asset_disposal_reason (id, version, code) VALUES (1, 0, 'BG_SALE')");
  }

  private static Map<Long, String> numbers(Connection c) throws SQLException {
    Map<Long, String> out = new LinkedHashMap<>();
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT id, fixed_asset_seq FROM account_fixed_asset ORDER BY id")) {
      while (rs.next()) {
        out.put(rs.getLong(1), rs.getString(2));
      }
    }
    return out;
  }

  /** Уникалните ограничения точно върху (company, fixed_asset_seq): име → с условие ли е. */
  private static Map<String, Boolean> uniques(Connection c) throws SQLException {
    Map<String, Boolean> out = new LinkedHashMap<>();
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT x.relname, i.indpred IS NOT NULL FROM pg_index i"
                    + " JOIN pg_class x ON x.oid = i.indexrelid"
                    + " WHERE i.indrelid = to_regclass('account_fixed_asset') AND i.indisunique"
                    + " AND i.indnatts = 2"
                    + " AND (SELECT array_agg(a.attname::text ORDER BY a.attname)"
                    + "      FROM unnest(i.indkey) k JOIN pg_attribute a"
                    + "      ON a.attrelid = i.indrelid AND a.attnum = k)"
                    + "     = ARRAY['company', 'fixed_asset_seq'] ORDER BY 1")) {
      while (rs.next()) {
        out.put(rs.getString(1), rs.getBoolean(2));
      }
    }
    return out;
  }

  private static int version(Connection c, long id) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery("SELECT version FROM account_fixed_asset WHERE id = " + id)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  @Test
  @DisplayName(
      "Нова база — стартът минава; стара — спира; файлът попълва само уникалните номера от"
          + " Microinvest, слага уникалността и FK-то; второ пускане — без промяна")
  void файлът() throws Exception {
    try (Connection c = scratchConnection()) {
      exec(c, "DROP TABLE IF EXISTS account_fixed_asset");
      assertNull(BgFixedAssetSchemaCheck.refusal(c), "нова база — Hibernate прави колоните");

      oldShape(c);
      String refusal = BgFixedAssetSchemaCheck.refusal(c);
      assertNotNull(refusal, "преди файла стартът спира");
      assertTrue(refusal.contains(BgFixedAssetSchemaCheck.MIGRATION_FILE), refusal);

      applyAsPipeline(c);
      assertNull(BgFixedAssetSchemaCheck.refusal(c), "след файла стартът минава");

      Map<Long, String> after = numbers(c);
      assertEquals("001", after.get(1L), "уникалният от Microinvest — попълнен");
      assertNull(after.get(2L), "дубликатът „002“ — празен");
      assertNull(after.get(3L), "дубликатът „ 002 “ (с интервали) — празен, празният низ → NULL");
      assertEquals("IMMO-1", after.get(4L), "заваденият номер — непокътнат");
      assertNull(after.get(5L), "номерът от Microinvest вече е зает в дружеството — празен");
      assertEquals("001", after.get(6L), "същият номер в друго дружество — попълнен");
      assertNull(after.get(7L));
      assertNull(after.get(8L), "празният низ → NULL");
      assertEquals(1, version(c, 1L), "попълненият — с нова версия");
      assertEquals(1, version(c, 8L), "празният низ, станал NULL — с нова версия");
      assertEquals(0, version(c, 2L), "непипнатият — със старата");
      assertEquals(
          Map.of(UK_NAME, false),
          uniques(c),
          "една уникалност, без условие, с името на Hibernate за домейна");

      applyAsPipeline(c);
      assertEquals(after, numbers(c), "второ пускане — без промяна и без грешка");
      assertEquals(1, version(c, 1L), "и версията не мърда");

      // Поведението на уникалността: NULL („няма номер“) се повтаря; непразен — не в едно
      // дружество, да в друго.
      exec(
          c,
          "INSERT INTO account_fixed_asset (id, version, company, fixed_asset_seq) VALUES"
              + " (20, 0, 1, NULL), (21, 0, 1, NULL), (23, 0, 2, 'IMMO-1')");
      c.setAutoCommit(false);
      Savepoint sp = c.setSavepoint();
      assertThrows(
          SQLException.class,
          () ->
              exec(
                  c,
                  "INSERT INTO account_fixed_asset (id, version, company, fixed_asset_seq)"
                      + " VALUES (24, 0, 1, '001')"),
          "същият номер в същото дружество");
      c.rollback(sp);
      c.setAutoCommit(true);

      // FK-то на „отделен от“.
      exec(c, "UPDATE account_fixed_asset SET bg_split_from = 1 WHERE id = 6");
      assertThrows(
          SQLException.class,
          () -> exec(c, "UPDATE account_fixed_asset SET bg_split_from = 999 WHERE id = 7"),
          "„отделен от“ към несъществуващ актив");
    }
  }

  @Test
  @DisplayName(
      "Частичният уникален индекс от ранна версия на файла се сменя с ограничението на домейна")
  void частичният_индекс_се_сменя() throws Exception {
    try (Connection c = scratchConnection()) {
      oldShape(c);
      exec(
          c,
          "CREATE UNIQUE INDEX uk_bg_fixed_asset_company_seq ON account_fixed_asset"
              + " (company, fixed_asset_seq)"
              + " WHERE fixed_asset_seq IS NOT NULL AND fixed_asset_seq <> ''");
      applyAsPipeline(c);
      assertEquals(Map.of(UK_NAME, false), uniques(c));
      assertNull(BgFixedAssetSchemaCheck.refusal(c));
    }
  }

  @Test
  @DisplayName(
      "Два актива с един и същ непразен номер в едно дружество — грешка със списъка, всичко се"
          + " връща")
  void дубликатите_спират() throws Exception {
    try (Connection c = scratchConnection()) {
      oldShape(c);
      exec(c, "UPDATE account_fixed_asset SET fixed_asset_seq = 'IMMO-1' WHERE id = 7");
      SQLException e = assertThrows(SQLException.class, () -> applyAsPipeline(c));
      assertTrue(e.getMessage().contains("ръчно решение"), e.getMessage());
      assertTrue(
          e.getMessage().contains("IMMO-1") && e.getMessage().contains("4, 7"), e.getMessage());
      assertNotNull(BgFixedAssetSchemaCheck.refusal(c), "върнато назад — колоните ги няма");
      assertNull(numbers(c).get(1L), "и попълването е върнато");
    }
  }
}

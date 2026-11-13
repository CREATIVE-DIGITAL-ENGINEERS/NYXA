package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ФАЙЛЪТ ЗА ПАЙПЛАЙНА ({@code db/migration/2026-10-23-bg-coa-duplicate-fixed-asset-sequence.sql}):
 * дублираната поредица 'fixedAsset' (importId 74) се маха при заварените дружества, ако не е
 * ползвана; ползвана — грешка за ръчно решение. Приложен от фикстурата като обвивката на пайплайна,
 * във временна PostgreSQL база. Без база (CI) — пропуснат ({@link BgSkip}).
 */
class BgFixedAssetSequenceMigrationDbTest {

  private static final String FILE =
      "/db/migration/2026-10-23-bg-coa-duplicate-fixed-asset-sequence.sql";

  private static String scratch;
  private static String baseUrl;

  @BeforeAll
  static void createScratchDatabase() {
    baseUrl = BgTestDb.url();
    scratch = "nyxa_fixed_asset_seq_it_" + System.nanoTime();
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
    try (InputStream in = BgFixedAssetSequenceMigrationDbTest.class.getResourceAsStream(FILE)) {
      assertNotNull(in, "файлът е на classpath-а: " + FILE);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

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
   * Дружество A: 104 и неползвана 74 → 74 се маха. Дружество C: само 104 → непокътнато. Дружество
   * D: поредица 'fixedAsset' извън BG_COA (без 104) → непокътнато, проверката не го гледа.
   */
  private static void scene(Connection c) throws SQLException {
    exec(
        c,
        "DROP TABLE IF EXISTS base_sequence_version",
        "DROP TABLE IF EXISTS base_sequence",
        "DROP TABLE IF EXISTS base_company",
        "CREATE TABLE base_company (id bigint PRIMARY KEY, code varchar(255), name varchar(255))",
        "CREATE TABLE base_sequence (id bigint PRIMARY KEY, company bigint,"
            + " code_select varchar(255), import_id varchar(255))",
        "CREATE TABLE base_sequence_version (id bigint PRIMARY KEY, sequence bigint,"
            + " next_num bigint)",
        "INSERT INTO base_company VALUES (1, 'A', 'Фирма А'), (3, 'C', 'Фирма В'),"
            + " (4, 'D', 'Фирма Г')",
        "INSERT INTO base_sequence VALUES (10, 1, 'fixedAsset', 'A-104'),"
            + " (11, 1, 'fixedAsset', 'A-74'), (12, 1, 'reconcile', 'A-65'),"
            + " (30, 3, 'fixedAsset', 'C-104'), (40, 4, 'fixedAsset', 'D-9')",
        "INSERT INTO base_sequence_version VALUES (100, 10, 7), (101, 11, 1), (300, 30, 1),"
            + " (400, 40, 1)");
  }

  private static List<String> sequences(Connection c) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT import_id FROM base_sequence ORDER BY id")) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  @Test
  @DisplayName(
      "Неползваната 74 се маха с версиите си; останалото е непокътнато; второ пускане — без"
          + " промяна")
  void файлът() throws Exception {
    try (Connection c = scratchConnection()) {
      scene(c);
      applyAsPipeline(c);
      assertEquals(List.of("A-104", "A-65", "C-104", "D-9"), sequences(c));
      try (Statement st = c.createStatement();
          ResultSet rs =
              st.executeQuery("SELECT count(*) FROM base_sequence_version WHERE sequence = 11")) {
        rs.next();
        assertEquals(0, rs.getInt(1), "версиите на изтритата — също");
      }
      applyAsPipeline(c);
      assertEquals(List.of("A-104", "A-65", "C-104", "D-9"), sequences(c), "второ пускане");
    }
  }

  @Test
  @DisplayName("Ползвана 74 (next_num > 1) — грешка за ръчно решение, нищо не е изтрито")
  void ползваната_спира() throws Exception {
    try (Connection c = scratchConnection()) {
      scene(c);
      exec(c, "UPDATE base_sequence_version SET next_num = 5 WHERE sequence = 11");
      SQLException e = assertThrows(SQLException.class, () -> applyAsPipeline(c));
      assertTrue(e.getMessage().contains("ИЗПОЛЗВАНА"), e.getMessage());
      assertTrue(sequences(c).contains("A-74"), "върнато назад");
    }
  }
}

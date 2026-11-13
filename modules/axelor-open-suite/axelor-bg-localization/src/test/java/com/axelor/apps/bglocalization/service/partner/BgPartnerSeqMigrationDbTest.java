package com.axelor.apps.bglocalization.service.partner;

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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * МИГРАЦИОННИЯТ ФАЙЛ ({@code db/migration/2026-10-20-partner-seq-per-company.sql}) — приложен от
 * ФИКСТУРАТА в една транзакция около файла; всяка грешка я връща.
 *
 * <p>На PostgreSQL, в СОБСТВЕНА временна база (създава се и се трие тук; общата база не се пипа):
 * таблица {@code base_partner} във вида на старата версия — глобално {@code UNIQUE (partner_seq)} с
 * името на Hibernate плюс отделен уникален индекс върху същата колона, и заварени редове.
 *
 * <p>Без база (CI) — пропуснат с причина ({@link BgSkip}).
 */
class BgPartnerSeqMigrationDbTest {

  private static final String FILE = "/db/migration/2026-10-20-partner-seq-per-company.sql";

  private static String scratch;
  private static String baseUrl;

  @BeforeAll
  static void createScratchDatabase() {
    baseUrl = BgTestDb.url();
    scratch = "nyxa_partner_seq_it_" + System.nanoTime();
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
    try (InputStream in = BgPartnerSeqMigrationDbTest.class.getResourceAsStream(FILE)) {
      assertNotNull(in, "файлът е на classpath-а: " + FILE);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** Една транзакция около файла; грешка → ROLLBACK. */
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

  /** Индексите на base_partner — за сравнение преди и след второто пускане. */
  private static List<String> indexes(Connection c) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'base_partner'"
                    + " ORDER BY indexname")) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  private static void oldShape(Connection c) throws SQLException {
    exec(
        c,
        "DROP TABLE IF EXISTS base_partner",
        "CREATE TABLE base_partner (id bigint PRIMARY KEY, version int, name varchar(255),"
            + " partner_seq varchar(255))",
        // Името на Hibernate за @Column(unique = true) — и отделен уникален индекс: файлът ги
        // намира по колоните, не по имената.
        "ALTER TABLE base_partner ADD CONSTRAINT uk_j701q4v9ypf9dpdsox062eeqg UNIQUE (partner_seq)",
        "CREATE UNIQUE INDEX some_partner_seq_idx ON base_partner (partner_seq)",
        "INSERT INTO base_partner (id, version, name, partner_seq) VALUES"
            + " (1, 0, 'a', 'P0001'), (2, 0, 'b', 'P0002'), (3, 0, 'c', 'P0003')");
  }

  @Test
  @DisplayName(
      "файлът: глобалната уникалност пада, (дружество, номер) идва, второ пускане — без промяна")
  void файлът() throws Exception {
    try (Connection c = scratchConnection()) {
      oldShape(c);
      assertNotNull(BgPartnerSchemaCheck.refusal(c), "преди файла стартът спира");

      applyAsPipeline(c);
      assertNull(BgPartnerSchemaCheck.refusal(c), "след файла стартът минава");
      List<String> after = indexes(c);
      assertTrue(
          after.stream()
              .anyMatch(
                  d ->
                      d.contains("uk_p09fve1mwil35xaxbps608xc")
                          && d.contains("(company, partner_seq)")),
          "уникалност по (дружество, номер) с името на Hibernate: " + after);
      assertTrue(
          after.stream().anyMatch(d -> d.contains("WHERE (company IS NULL)")),
          "без дружество — уникални: " + after);

      applyAsPipeline(c);
      assertEquals(after, indexes(c), "второ пускане — без промяна и без грешка");

      // Поведението: един номер в две дружества — да; в едно — не; двама без дружество — не.
      exec(
          c,
          "INSERT INTO base_partner (id, version, name, partner_seq, company) VALUES"
              + " (10, 0, 'А1', 'P0010', 1), (11, 0, 'Б1', 'P0010', 2)");
      c.setAutoCommit(false);
      Savepoint sp = c.setSavepoint();
      assertThrows(
          SQLException.class,
          () ->
              exec(
                  c,
                  "INSERT INTO base_partner (id, version, partner_seq, company)"
                      + " VALUES (12, 0, 'P0010', 1)"),
          "същият номер в същото дружество");
      c.rollback(sp);
      assertThrows(
          SQLException.class,
          () ->
              exec(
                  c,
                  "INSERT INTO base_partner (id, version, partner_seq)"
                      + " VALUES (13, 0, 'P0001')"),
          "двама без дружество с един номер (системни/заварени)");
      c.rollback();
      c.setAutoCommit(true);

      try (Statement st = c.createStatement();
          ResultSet rs =
              st.executeQuery("SELECT count(*), count(company) FROM base_partner WHERE id <= 3")) {
        rs.next();
        assertEquals(3, rs.getInt(1), "заварените са на място");
        assertEquals(0, rs.getInt(2), "и без дружество — файлът не пипа редове");
      }
    }
  }

  @Test
  @DisplayName("грешен резултат → проверката в края вдига грешка и връща целия файл")
  void проверката_в_края() throws Exception {
    try (Connection c = scratchConnection()) {
      oldShape(c);
      // Индекс със същото име, но върху друга колона: CREATE … IF NOT EXISTS го прескача и
      // уникалността без дружество не се ражда — проверката трябва да откаже.
      exec(c, "CREATE UNIQUE INDEX uk_bg_partner_seq_without_company ON base_partner (name)");
      SQLException e = assertThrows(SQLException.class, () -> applyAsPipeline(c));
      assertTrue(e.getMessage().contains("Резултатът не е верен"), e.getMessage());
      assertNotNull(BgPartnerSchemaCheck.refusal(c), "върнато назад — глобалната е още там");
    }
  }
}

package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import com.axelor.apps.napsubmissions.common.immutability.SaftReportSchemaCheck;
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
 * МИГРАЦИОННИЯТ ФАЙЛ ({@code db/migration/2026-10-21-saft-report-annual-ondemand.sql}) — приложен
 * от ФИКСТУРАТА така, както го прилага деплоят: една транзакция около файла, всяка грешка я връща.
 * Заедно с него — проверката при старт ({@link SaftReportSchemaCheck}) срещу истинската схема.
 *
 * <p>На PostgreSQL, в СОБСТВЕНА временна база (създава се и се трие тук; общата база не се пипа):
 * таблица {@code napsubmissions_saft_report} във вида на старата версия, със заварен месечен отчет.
 *
 * <p>Без база (CI) — пропуснат с причина ({@link BgSkip}).
 */
class BgSaftReportMigrationDbTest {

  private static final String FILE = "/db/migration/2026-10-21-saft-report-annual-ondemand.sql";

  private static String scratch;
  private static String baseUrl;

  @BeforeAll
  static void createScratchDatabase() {
    baseUrl = BgTestDb.url();
    scratch = "nyxa_saft_report_t1_it_" + System.nanoTime();
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
    try (InputStream in = BgSaftReportMigrationDbTest.class.getResourceAsStream(FILE)) {
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

  /** Колоните на таблицата с типовете им — за сравнение преди и след второто пускане. */
  private static List<String> columns(Connection c) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT a.attname || ' ' || format_type(a.atttypid, a.atttypmod) FROM pg_attribute a"
                    + " WHERE a.attrelid = 'napsubmissions_saft_report'::regclass AND a.attnum > 0"
                    + " AND NOT a.attisdropped ORDER BY a.attnum")) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  /** Таблицата във вида на старата версия (без колоните на T1) и един заварен месечен отчет. */
  private static void oldShape(Connection c) throws SQLException {
    exec(
        c,
        "DROP TABLE IF EXISTS napsubmissions_saft_report",
        "CREATE TABLE napsubmissions_saft_report (id bigint PRIMARY KEY, version int,"
            + " name varchar(255), company bigint, period bigint, report_type_select varchar(255))",
        "INSERT INTO napsubmissions_saft_report (id, version, name, company, period,"
            + " report_type_select) VALUES (1, 0, 'SAFT-1', 7, 10, 'monthly')");
  }

  @Test
  @DisplayName(
      "Без таблица (нова база) — стартът минава; стара таблица — спира; след файла — минава;"
          + " второ пускане — без промяна; заварените редове — непокътнати")
  void файлът() throws Exception {
    try (Connection c = scratchConnection()) {
      exec(c, "DROP TABLE IF EXISTS napsubmissions_saft_report");
      assertNull(SaftReportSchemaCheck.refusal(c), "нова база: колоните ги прави Hibernate");

      oldShape(c);
      String refusal = SaftReportSchemaCheck.refusal(c);
      assertNotNull(refusal, "преди файла стартът спира");
      assertTrue(refusal.contains(SaftReportSchemaCheck.MIGRATION_FILE), refusal);

      applyAsPipeline(c);
      assertNull(SaftReportSchemaCheck.refusal(c), "след файла стартът минава");
      List<String> after = columns(c);
      assertTrue(after.contains("report_year integer"), after.toString());
      assertTrue(after.contains("from_date date"), after.toString());
      assertTrue(after.contains("to_date date"), after.toString());
      assertTrue(after.contains("nap_request_number character varying(255)"), after.toString());
      assertTrue(after.contains("nap_request_date date"), after.toString());

      applyAsPipeline(c);
      assertEquals(after, columns(c), "второ пускане — без промяна и без грешка");

      try (Statement st = c.createStatement();
          ResultSet rs =
              st.executeQuery(
                  "SELECT name, report_type_select, report_year FROM napsubmissions_saft_report")) {
        assertTrue(rs.next());
        assertEquals("SAFT-1", rs.getString(1), "заварените са на място");
        assertEquals("monthly", rs.getString(2));
        assertNull(rs.getObject(3), "новите колони са празни — файлът не пипа редове");
      }
    }
  }

  @Test
  @DisplayName("Колона с друг тип → проверката в края вдига грешка и връща целия файл")
  void проверката_в_края() throws Exception {
    try (Connection c = scratchConnection()) {
      oldShape(c);
      // Колона със същото име и друг тип: ADD COLUMN IF NOT EXISTS я прескача — проверката трябва
      // да откаже, а другите четири колони да се върнат назад.
      exec(c, "ALTER TABLE napsubmissions_saft_report ADD COLUMN report_year varchar(4)");
      SQLException e = assertThrows(SQLException.class, () -> applyAsPipeline(c));
      assertTrue(e.getMessage().contains("Резултатът не е верен"), e.getMessage());
      assertTrue(e.getMessage().contains("report_year"), e.getMessage());
      List<String> back = columns(c);
      assertTrue(back.stream().noneMatch(col -> col.startsWith("from_date")), "върнато: " + back);
      assertNotNull(SaftReportSchemaCheck.refusal(c), "стартът пак спира");
    }
  }
}

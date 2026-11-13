package com.axelor.apps.bglocalization.service.accounting;

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
 * ФАЙЛЪТ ОТ automated.txt ({@code db/migration/2026-11-12-bg-coa-report-cogs-advances.sql}) —
 * приложен от ФИКСТУРАТА, както го пуска деплоят: една транзакция около файла.
 *
 * <p>Своя временна PostgreSQL база (създава се и се трие тук). Без база (CI) — пропуснат с причина
 * ({@link BgSkip}).
 */
class BgCoaReportCogsAdvancesMigrationDbTest {

  private static final String FILE = "/db/migration/2026-11-12-bg-coa-report-cogs-advances.sql";

  private static String scratch;
  private static String baseUrl;

  @BeforeAll
  static void createScratchDatabase() {
    baseUrl = BgTestDb.url();
    scratch = "nyxa_report_cogs_it_" + System.nanoTime();
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
    try (InputStream in = BgCoaReportCogsAdvancesMigrationDbTest.class.getResourceAsStream(FILE)) {
      assertNotNull(in, "файлът е на classpath-а: " + FILE);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

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

  private static final String COLS =
      "(id, version, import_id, report_type, sequence, code, type_select, rule_type_select,"
          + " style_select, detail_by_select, result_select, negate_value, rule, account_code,"
          + " hidden, not_computed_if_intersect, balance_before_period)";

  private static String line(
      long id, long type, String importId, String code, String rule, String account) {
    return line(id, type, importId, (int) (id % 10), code, rule, account);
  }

  private static String line(
      long id, long type, String importId, int seq, String code, String rule, String account) {
    return String.format(
        "(%d, 0, '%s', %d, %d, '%s', 1, %d, 0, 0, %d, false, %s, %s, true, false, false)",
        id,
        importId,
        type,
        seq,
        code,
        rule == null ? 2 : 3,
        rule == null ? 1 : 0,
        rule == null ? "NULL" : "'" + rule + "'",
        account == null ? "NULL" : "'" + account + "'");
  }

  /**
   * Справки 1 (ОПР НСС), 2 (баланс НСС), 3 (ОПР МСС), 4 (баланс МСС) — заварени по стария CSV; 5 —
   * ОПР НСС, вече по новия (чиста база): трябва да остане непокътната.
   */
  private static void existing(Connection c) throws SQLException {
    exec(
        c,
        "DROP TABLE IF EXISTS account_accounting_report_config_line_account_set,"
            + " account_accounting_report_config_line, account_fixed_asset_line,"
            + " account_fixed_asset, account_move, account_move_line",
        // Активите и статиите — само колоните, които файлът чете (т. 3, само отчет).
        "CREATE TABLE account_fixed_asset (id bigint PRIMARY KEY, disposal_date date,"
            + " disposal_value numeric)",
        "CREATE TABLE account_fixed_asset_line (id bigint PRIMARY KEY, fixed_asset bigint,"
            + " depreciation_date date, depreciation numeric, depreciation_account_move bigint)",
        "CREATE TABLE account_move (id bigint PRIMARY KEY, status_select int)",
        "CREATE TABLE account_move_line (id bigint PRIMARY KEY, move bigint, debit numeric,"
            + " credit numeric)",
        // Обзавеждането, продадено за 5 200 на 22.09: пропорционалният ред 73,33, статията — 5 200
        // (осчетоводена); сървърът — вярна статия.
        "INSERT INTO account_fixed_asset VALUES (1, '2026-09-22', 5200), (2, NULL, 0)",
        "INSERT INTO account_fixed_asset_line VALUES (1, 1, '2026-09-22', 73.33, 11),"
            + " (2, 2, '2026-09-30', 200, 12)",
        "INSERT INTO account_move VALUES (11, 3), (12, 3)",
        "INSERT INTO account_move_line VALUES (1, 11, 5200, 0), (2, 11, 0, 5200),"
            + " (3, 12, 200, 0), (4, 12, 0, 200)",
        "DROP SEQUENCE IF EXISTS account_accounting_report_config_line_seq",
        "CREATE SEQUENCE account_accounting_report_config_line_seq START 1000",
        "CREATE TABLE account_accounting_report_config_line (id bigint PRIMARY KEY, version int,"
            + " archived boolean, created_on timestamp, updated_on timestamp, created_by bigint,"
            + " updated_by bigint, import_id varchar(255), import_origin varchar(255),"
            + " process_instance_id varchar(255), attrs jsonb, report_type bigint,"
            + " report_type_column bigint, sequence int, code varchar(255), label varchar(255),"
            + " type_select int, rule_type_select int, style_select int, detail_by_select int,"
            + " result_select int, negate_value boolean, rule text, account_code text,"
            + " analytic_account_code text, hidden boolean, not_computed_if_intersect boolean,"
            + " balance_before_period boolean)",
        "CREATE TABLE account_accounting_report_config_line_account_set"
            + " (account_accounting_report_config_line bigint, account_set bigint)",
        "INSERT INTO account_accounting_report_config_line "
            + COLS
            + " VALUES "
            + String.join(
                ", ",
                line(10, 1, "DT1-2064", 63, "E_1_7_6091", null, "6091"),
                line(11, 1, "DT1-2065", 64, "E_ST1_7", "E_1_7_609 + E_1_7_6091 + E_3_1_691", null),
                line(12, 1, "DT1-2066", 65, "E_SEC1", "E_ST1_1 + E_ST1_7", null),
                line(20, 2, "DT1-2113", "A_2_2_412", null, "412"),
                line(21, 2, "DT1-2223", "P_3_3_402", null, "402"),
                line(22, 2, "DT1-2121", "A_ST2_2", "A_2_2_411 + A_2_2_412 + A_2_2_4121", null),
                line(23, 2, "DT1-2231", "P_ST3_3", "P_3_3_401 + P_3_3_402", null),
                line(30, 3, "DT1-2328", 56, "MO_6091", null, "6091"),
                line(31, 3, "DT1-2329", 57, "MO_ST_21931", "MO_609 + MO_6091", null),
                line(40, 4, "DT1-2373", "MA_2_2_412", null, "412"),
                line(41, 4, "DT1-2483", "MP_3_2_402", null, "402"),
                line(42, 4, "DT1-2381", "MA_ST2_2", "MA_2_2_411 + MA_2_2_412", null),
                line(43, 4, "DT1-2491", "MP_ST3_2", "MP_3_2_401 + MP_3_2_402", null),
                line(50, 5, "DT2-2064", "E_1_7_6091", null, "6091"),
                line(51, 5, "DT2-2788", "E_1_7_STOCK", null, "702@30,706@30"),
                line(52, 5, "DT2-2065", "E_ST1_7", "E_1_7_609 + E_1_7_STOCK", null),
                line(53, 5, "DT2-2790", "E_ST1_7_A", "E_1_7_STOCK", null)),
        "INSERT INTO account_accounting_report_config_line_account_set VALUES"
            + " (20, 412), (21, 402), (40, 412), (41, 402), (10, 6091)");
  }

  /** „справка|код“ → „сметки|правило|import|версия“ (и „...#ред“ — поредността). */
  private static Map<String, String> state(Connection c) throws SQLException {
    Map<String, String> out = new LinkedHashMap<>();
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT report_type, code, account_code, rule, import_id, version, sequence, label,"
                    + " hidden FROM account_accounting_report_config_line"
                    + " ORDER BY report_type, code")) {
      while (rs.next()) {
        out.put(
            rs.getLong(1) + "|" + rs.getString(2),
            rs.getString(3) + "|" + rs.getString(4) + "|" + rs.getString(5) + "|v" + rs.getInt(6));
        out.put(
            rs.getLong(1) + "|" + rs.getString(2) + "#",
            rs.getInt(7) + "|" + rs.getString(8) + "|" + rs.getBoolean(9));
      }
    }
    return out;
  }

  private static int links(Connection c, long lineId) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT count(*) FROM account_accounting_report_config_line_account_set"
                    + " WHERE account_accounting_report_config_line = "
                    + lineId)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  @Test
  @DisplayName(
      "Заварени справки: ред за себестойността в „Други разходи“ (НСС и МСС), 402/412 на верните"
          + " страни без стария набор; новата справка — непокътната; второ пускане — без промяна")
  void файлът() throws Exception {
    try (Connection c = scratchConnection()) {
      existing(c);
      Map<String, String> fresh = new LinkedHashMap<>();
      state(c)
          .forEach(
              (k, v) -> {
                if (k.startsWith("5|")) {
                  fresh.put(k, v);
                }
              });
      List<String> notices = applyAsPipeline(c);
      Map<String, String> after = state(c);

      assertEquals("702@30,706@30|null|DT1-2788|v0", after.get("1|E_1_7_STOCK"));
      assertEquals("67|null|true", after.get("1|E_1_7_STOCK#"), "скритият — последен в справката");
      assertEquals("null|E_1_7_STOCK|DT1-2790|v0", after.get("1|E_ST1_7_A"));
      assertEquals(
          "65|      в т.ч. балансова стойност на продадените активи|false",
          after.get("1|E_ST1_7_A#"),
          "видимият „в т.ч.“ — веднага под „Други разходи“");
      assertEquals("66|null|true", after.get("1|E_SEC1#"), "следващият ред слиза с едно");
      assertEquals("64|null|true", after.get("1|E_ST1_7#"), "„Други разходи“ остават");
      assertEquals("null|MO_STOCK|DT1-2791|v0", after.get("3|MO_ST_21931_A"));
      assertEquals(
          "58|      в т.ч. балансова стойност на продадените активи|false",
          after.get("3|MO_ST_21931_A#"));
      assertEquals(
          "null|E_1_7_609 + E_1_7_6091 + E_3_1_691 + E_1_7_STOCK|DT1-2065|v1",
          after.get("1|E_ST1_7"));
      assertEquals("702@30,706@30|null|DT1-2789|v0", after.get("3|MO_STOCK"));
      assertEquals("null|MO_609 + MO_6091 + MO_STOCK|DT1-2329|v1", after.get("3|MO_ST_21931"));

      assertEquals("402|null|DT1-2113|v1", after.get("2|A_2_2_402"));
      assertEquals("412|null|DT1-2223|v1", after.get("2|P_3_3_412"));
      // A_2_2_4121 е друг код — думата не се сменя в средата.
      assertEquals("null|A_2_2_411 + A_2_2_402 + A_2_2_4121|DT1-2121|v1", after.get("2|A_ST2_2"));
      assertEquals("null|P_3_3_401 + P_3_3_412|DT1-2231|v1", after.get("2|P_ST3_3"));
      assertEquals("402|null|DT1-2373|v1", after.get("4|MA_2_2_402"));
      assertEquals("412|null|DT1-2483|v1", after.get("4|MP_3_2_412"));
      assertEquals("null|MA_2_2_411 + MA_2_2_402|DT1-2381|v1", after.get("4|MA_ST2_2"));
      assertEquals("null|MP_3_2_401 + MP_3_2_412|DT1-2491|v1", after.get("4|MP_ST3_2"));
      assertEquals(0, links(c, 20), "старият набор (412) на реда за 402 е изчистен");
      assertEquals(0, links(c, 41), "старият набор (402) на реда за 412 е изчистен");
      assertEquals(1, links(c, 10), "чуждият набор не се пипа");

      fresh.forEach((k, v) -> assertEquals(v, after.get(k), "новата справка — непокътната: " + k));
      assertTrue(
          notices.stream().anyMatch(n -> n.contains("себестойността 2, сборни редове 2")),
          notices.toString());
      assertTrue(
          notices.stream().anyMatch(n -> n.contains("разменени редове 402/412 4")),
          notices.toString());
      // Т. 3: осчетоводената статия с продажната цена — само преброена, не пипната.
      assertTrue(
          notices.stream()
              .anyMatch(
                  n -> n.contains("НЕ СЕ ПИПА: 1 статии в статус 3") && n.contains("5126.67")),
          notices.toString());
      try (Statement st = c.createStatement();
          ResultSet rs =
              st.executeQuery("SELECT sum(debit) FROM account_move_line WHERE move = 11")) {
        rs.next();
        assertEquals(0, new java.math.BigDecimal("5200").compareTo(rs.getBigDecimal(1)));
      }

      List<String> again = applyAsPipeline(c);
      assertEquals(after, state(c), "второ пускане — без промяна");
      assertTrue(
          again.stream().anyMatch(n -> n.contains("себестойността 0, сборни редове 0")),
          again.toString());
    }
  }

  @Test
  @DisplayName("Проверката в края: остатък (сбор без новия ред) връща целия файл назад")
  void проверката_спира() throws Exception {
    try (Connection c = scratchConnection()) {
      existing(c);
      // Сборът „Други разходи“ без реда 6091 — файлът не добавя ред, проверката хваща остатъка.
      exec(c, "DELETE FROM account_accounting_report_config_line WHERE id = 10");
      Map<String, String> before = state(c);
      SQLException e = assertThrows(SQLException.class, () -> applyAsPipeline(c));
      assertTrue(e.getMessage().contains("остават"), e.getMessage());
      assertEquals(before, state(c), "нищо не е записано");
    }
  }
}

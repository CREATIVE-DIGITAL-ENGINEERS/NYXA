package com.axelor.apps.account.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * ФАКТУРАТА ПЕЧАТА ИМЕТО НА ДАНЪКА, НЕ КОДА МУ.
 *
 * <p>{@code Tax.code} от сметкоплана („BG_SRS_20“) не е за клиента. Двете заявки на {@code
 * Invoice.rptdesign} взимат {@code Tax.name} („Продажби – стандартна ставка 20%“), а кодът остава
 * само при празно име.
 *
 * <p>Изразът се взима ДОСЛОВНО от заявката и се пуска в HSQLDB върху таблица със същите колони —
 * като {@link InvoiceReportTaxEventDateTest}.
 */
class InvoiceReportTaxNameTest {

  private static final Path REPORT = Paths.get("src/main/resources/reports/Invoice.rptdesign");

  /**
   * Таблицата с данъците под редовете ({@code InvoiceLineTaxDS}): изразът пред {@code tax_code}.
   */
  private static final Pattern SUMMARY =
      Pattern.compile("\\n\\s*([^\\n]*?)\\s+AS tax_code,", Pattern.CASE_INSENSITIVE);

  /** Данъкът на реда ({@code InvoiceLineTaxLineDS}): изразът, който става колона {@code code}. */
  private static final Pattern LINE =
      Pattern.compile(
          "select TaxLine\\.value,\\s*(.*?)\\s+AS code,\\s*Tax\\.manage_by_amount",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

  private static String report;
  private static Connection db;

  @BeforeAll
  static void setUp() throws Exception {
    assertTrue(Files.isRegularFile(REPORT), "не намирам " + REPORT.toAbsolutePath());
    report = new String(Files.readAllBytes(REPORT), StandardCharsets.UTF_8);
    db = DriverManager.getConnection("jdbc:hsqldb:mem:invoiceTaxName", "SA", "");
    try (Statement st = db.createStatement()) {
      st.execute(
          "CREATE TABLE account_tax (id BIGINT PRIMARY KEY, code VARCHAR(64), name VARCHAR(255))");
      st.execute(
          "INSERT INTO account_tax VALUES (1, 'BG_SRS_20', 'Продажби – стандартна ставка 20%')");
      st.execute("INSERT INTO account_tax VALUES (2, 'NO_NAME', NULL)");
      st.execute("INSERT INTO account_tax VALUES (3, 'EMPTY_NAME', '')");
    }
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (db != null) {
      try (Statement st = db.createStatement()) {
        st.execute("SHUTDOWN");
      }
      db.close();
    }
  }

  @Test
  void таблицата_с_данъците_печата_името() throws Exception {
    String expression = expression(SUMMARY, "tax_code в InvoiceLineTaxDS");
    assertEquals("Продажби – стандартна ставка 20%", printed(expression, 1));
    assertEquals("NO_NAME", printed(expression, 2), "без име — кодът");
    assertEquals("EMPTY_NAME", printed(expression, 3), "празно име — кодът");
  }

  @Test
  void редът_печата_името() throws Exception {
    String expression = expression(LINE, "code в InvoiceLineTaxLineDS");
    assertEquals("Продажби – стандартна ставка 20%", printed(expression, 1));
    assertEquals("NO_NAME", printed(expression, 2), "без име — кодът");
  }

  private static String expression(Pattern pattern, String what) {
    Matcher m = pattern.matcher(report);
    assertTrue(m.find(), "не намирам израза за " + what + " в " + REPORT);
    String expression = m.group(1).trim();
    assertTrue(!m.find(), "изразът за " + what + " се среща повече от веднъж");
    return expression;
  }

  private static String printed(String expression, long taxId) throws Exception {
    try (Statement st = db.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT " + expression + " FROM account_tax Tax WHERE Tax.id = " + taxId)) {
      assertTrue(rs.next());
      return rs.getString(1);
    }
  }
}

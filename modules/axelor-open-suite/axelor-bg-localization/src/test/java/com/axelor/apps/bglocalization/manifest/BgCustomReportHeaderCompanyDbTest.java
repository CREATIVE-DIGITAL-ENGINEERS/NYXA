package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * „КОМПАНИЯ :“ В ЗАГЛАВИЕТО НА CUSTOM СПРАВКАТА Е ДРУЖЕСТВОТО НА СПРАВКАТА.
 *
 * <p>Custom справката се смята по {@code companySet} — {@code company} там е скрито поле, което
 * onNew на AOS попълва с АКТИВНОТО дружество ({@code if="!isCustom"} в AccountingReport.xml, а
 * контекстът носи {@code _isCustom}). Заглавие на {@code AccountingReportType3000.rptdesign}, което
 * взима името от {@code AccountingReport.company}, печата активното дружество, а стойностите са на
 * избраното — справка за едно дружество със заглавие на друго.
 *
 * <p>Пуска истинската заявка на заглавието от шаблона срещу живата база, в транзакция, която се
 * връща. Без база — пропуснат с причина (CI няма база).
 */
class BgCustomReportHeaderCompanyDbTest {

  private static final Path TEMPLATE =
      Paths.get("../axelor-account/src/main/resources/reports/AccountingReportType3000.rptdesign");

  private static String headerQuery() throws Exception {
    String xml = new String(Files.readAllBytes(TEMPLATE), StandardCharsets.UTF_8);
    Matcher m =
        Pattern.compile(
                "<xml-property name=\"queryText\"><!\\[CDATA\\[(.*?)\\]\\]></xml-property>",
                Pattern.DOTALL)
            .matcher(xml);
    while (m.find()) {
      if (m.group(1).contains("as company_name")) {
        return m.group(1);
      }
    }
    throw new AssertionError("няма заявка с company_name в " + TEMPLATE);
  }

  private static Connection connect() {
    try {
      return DriverManager.getConnection(BgTestDb.url(), BgTestDb.user(), BgTestDb.password());
    } catch (SQLException e) {
      BgSkip.because("няма достъпна база (" + BgTestDb.url() + "): " + e.getMessage());
      return null;
    }
  }

  /** Името в заглавието за справка с {@code company} и {@code companySet} (null = без набор). */
  private static String headerCompany(Connection c, Long[] set) throws Exception {
    try (Statement st = c.createStatement()) {
      st.executeUpdate(
          "INSERT INTO base_company (id, code, name) VALUES"
              + " (-91001, 'ZZ-HDR-ACT', 'Активното дружество'),"
              + " (-91002, 'ZZ-HDR-REP', 'Дружеството на справката'),"
              + " (-91003, 'ZZ-HDR-OTH', 'Трето дружество')");
      st.executeUpdate(
          "INSERT INTO account_accounting_report_type"
              + " (id, name, report_export_type_select, type_select)"
              + " VALUES (-91001, 'Баланс (МСС) — тест', 1, 3000)");
      st.executeUpdate(
          "INSERT INTO account_accounting_report (id, date_val, report_type, company)"
              + " VALUES (-91001, DATE '2026-10-31', -91001, -91001)");
    }
    for (Long companyId : set) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO account_accounting_report_company_set"
                  + " (account_accounting_report, company_set) VALUES (-91001, ?)")) {
        ps.setLong(1, companyId);
        ps.executeUpdate();
      }
    }
    try (PreparedStatement ps = c.prepareStatement(headerQuery())) {
      ps.setLong(1, -91001L);
      ps.setLong(2, -91001L);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "заглавието не върна ред");
        return rs.getString("company_name");
      }
    }
  }

  private static String inRolledBackTransaction(Long... set) throws Exception {
    try (Connection c = connect()) {
      c.setAutoCommit(false);
      try {
        return headerCompany(c, set);
      } finally {
        c.rollback();
      }
    }
  }

  @Test
  @DisplayName("Едно дружество в набора → заглавието е то, не активното от onNew")
  void singleCompanyInTheSetIsTheHeader() throws Exception {
    assertEquals("Дружеството на справката", inRolledBackTransaction(-91002L));
  }

  @Test
  @DisplayName("Няколко дружества → без едно име в заглавието (всяко е в таблицата си)")
  void severalCompaniesHaveNoSingleHeaderName() throws Exception {
    assertNull(inRolledBackTransaction(-91002L, -91003L));
  }

  @Test
  @DisplayName("Без набор (не-custom употреба на шаблона) → company на справката, както досега")
  void noSetFallsBackToTheReportCompany() throws Exception {
    assertEquals("Активното дружество", inRolledBackTransaction());
  }
}

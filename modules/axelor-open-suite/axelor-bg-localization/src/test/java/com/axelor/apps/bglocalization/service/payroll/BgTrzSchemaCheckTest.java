package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРЕДПАЗИТЕЛЯТ ПРИ СТАРТ ({@link BgTrzIntegrator#requireSchema}, {@link BgTrzSchemaCheck}) — срещу
 * ИСТИНСКА схема в собствена HSQLDB в паметта, без база отвън: върви на всяка машина и в CI.
 *
 * <p>Старата схема (без company, с глобалното уникално по reference) → приложението не тръгва и
 * назовава файла за пайплайна; мигрираната — тръгва; празна база (нова инсталация) — тръгва.
 */
class BgTrzSchemaCheckTest {

  private static Connection fresh() throws SQLException {
    return DriverManager.getConnection(
        "jdbc:hsqldb:mem:trz_schema_" + System.nanoTime() + ";shutdown=true", "SA", "");
  }

  private static void exec(Connection c, String... sql) throws SQLException {
    try (Statement st = c.createStatement()) {
      for (String s : sql) {
        st.execute(s);
      }
    }
  }

  /** Схемата на старата версия: без company, глобалното уникално по reference (както Hibernate). */
  private static void oldSchema(Connection c) throws SQLException {
    exec(
        c,
        "CREATE TABLE bglocalization_bg_pay_slip (id BIGINT PRIMARY KEY, reference VARCHAR(255),"
            + " CONSTRAINT uk_old_slip UNIQUE (reference))",
        "CREATE TABLE bglocalization_bg_compensation (id BIGINT PRIMARY KEY,"
            + " reference VARCHAR(255), CONSTRAINT uk_old_comp UNIQUE (reference))");
  }

  @Test
  @DisplayName("стара схема (без company, глобално по reference) → стартът спира с името на файла")
  void theOldSchemaStopsTheStart() throws SQLException {
    try (Connection c = fresh()) {
      oldSchema(c);
      IllegalStateException refusal =
          assertThrows(IllegalStateException.class, () -> BgTrzIntegrator.requireSchema(c));
      String text = refusal.getMessage();
      assertTrue(text.contains(BgTrzSchemaCheck.MIGRATION_FILE), text);
      assertTrue(text.contains("срещу базата"), text);
      assertTrue(
          text.toLowerCase().contains("bglocalization_bg_pay_slip няма колона company"), text);
      assertTrue(
          text.toLowerCase().contains("bglocalization_bg_compensation няма колона company"), text);
      assertTrue(text.contains("глобалното уникално по reference"), text);
    }
  }

  @Test
  @DisplayName("колоната я има, но глобалното уникално по reference стои → стартът спира")
  void aRemainingGlobalUniqueStopsTheStart() throws SQLException {
    try (Connection c = fresh()) {
      oldSchema(c);
      exec(
          c,
          "ALTER TABLE bglocalization_bg_pay_slip ADD COLUMN company BIGINT",
          "ALTER TABLE bglocalization_bg_compensation ADD COLUMN company BIGINT",
          "ALTER TABLE bglocalization_bg_compensation DROP CONSTRAINT uk_old_comp");
      IllegalStateException refusal =
          assertThrows(IllegalStateException.class, () -> BgTrzIntegrator.requireSchema(c));
      String text = refusal.getMessage().toLowerCase();
      assertTrue(text.contains("bglocalization_bg_pay_slip още има глобалното уникално"), text);
      assertTrue(
          !text.contains("bglocalization_bg_compensation"), "обезщетението е готово: " + text);
    }
  }

  @Test
  @DisplayName("мигрирана схема (company + уникално по (company, reference)) → стартът минава")
  void theMigratedSchemaStarts() throws SQLException {
    try (Connection c = fresh()) {
      exec(
          c,
          "CREATE TABLE bglocalization_bg_pay_slip (id BIGINT PRIMARY KEY, company BIGINT,"
              + " reference VARCHAR(255),"
              + " CONSTRAINT uk_bg_pay_slip_company_reference UNIQUE (company, reference))",
          "CREATE TABLE bglocalization_bg_compensation (id BIGINT PRIMARY KEY, company BIGINT,"
              + " reference VARCHAR(255),"
              + " CONSTRAINT uk_bg_compensation_company_reference UNIQUE (company, reference))");
      assertDoesNotThrow(() -> BgTrzIntegrator.requireSchema(c));
    }
  }

  @Test
  @DisplayName("празна база (нова инсталация) → няма какво да се проверява, стартът минава")
  void anEmptyDatabaseStarts() throws SQLException {
    try (Connection c = fresh()) {
      assertDoesNotThrow(() -> BgTrzIntegrator.requireSchema(c));
    }
  }
}

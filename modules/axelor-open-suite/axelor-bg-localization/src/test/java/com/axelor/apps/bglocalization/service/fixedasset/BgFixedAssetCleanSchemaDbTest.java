package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import com.axelor.db.JpaScanner;
import com.axelor.db.hibernate.dialect.CustomDialectResolver;
import com.axelor.db.hibernate.naming.ImplicitNamingStrategyImpl;
import com.axelor.db.hibernate.naming.PhysicalNamingStrategyImpl;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * ЧИСТА БАЗА, СХЕМАТА ОТ ДОМЕЙНА — без файловете от {@code db/migration} (новите среди 08/09).
 *
 * <p>Стартът е истинският: Hibernate с настройките на {@code JpaModule} (скенерът на моделите,
 * именуването на Axelor) и {@code ddl=update} — така, както приложението вдига схемата на празна
 * база. Интеграторите от {@code META-INF/services}, вкл. {@link BgFixedAssetIntegrator}, се викат
 * от самия Hibernate. Пускат се ДВА старта: първият намира празна база (проверката няма какво да
 * гледа) и създава таблиците; вторият е всеки следващ рестарт — там проверката вижда схемата, която
 * домейнът е създал, и тя трябва да ѝ стига.
 *
 * <p>Моделите са тези на classpath-а на модула (подмножество на приложението, но с активите,
 * дружеството и причината за отписване).
 *
 * <p>Собствена временна база (създава се и се трие тук); без база (CI) — пропуснат с причина.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BgFixedAssetCleanSchemaDbTest {

  private static final String FILE = "/db/migration/2026-10-23-fixed-asset-inventory-number.sql";

  /** Уникалните индекси точно върху (company, fixed_asset_seq) — с условие или без. */
  private static final String UNIQUE_COUNT_SQL =
      "SELECT count(*) FROM pg_index i"
          + " WHERE i.indrelid = to_regclass('account_fixed_asset') AND i.indisunique"
          + " AND i.indnatts = 2"
          + " AND (SELECT array_agg(a.attname::text ORDER BY a.attname) FROM unnest(i.indkey) k"
          + "      JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k)"
          + "     = ARRAY['company', 'fixed_asset_seq']";

  private static String scratch;
  private static String baseUrl;

  @BeforeAll
  static void createScratchDatabase() {
    baseUrl = BgTestDb.url();
    scratch = "nyxa_fixed_asset_clean_it_" + System.nanoTime();
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
      st.execute("DROP DATABASE IF EXISTS " + scratch + " WITH (FORCE)");
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

  private static String scratchUrl() {
    int slash = baseUrl.lastIndexOf('/');
    int query = baseUrl.indexOf('?', slash);
    return baseUrl.substring(0, slash + 1) + scratch + (query < 0 ? "" : baseUrl.substring(query));
  }

  /**
   * Един старт на Hibernate срещу временната база, с {@code ddl=update} и настройките на {@code
   * JpaModule}. Провал в интегратор = провален старт (изключението излиза оттук).
   */
  private static void boot() {
    Map<String, Object> p = new HashMap<>();
    p.put(AvailableSettings.SCANNER, JpaScanner.class.getName());
    p.put(AvailableSettings.USE_NEW_ID_GENERATOR_MAPPINGS, "true");
    p.put(AvailableSettings.IMPLICIT_NAMING_STRATEGY, ImplicitNamingStrategyImpl.class.getName());
    p.put(AvailableSettings.PHYSICAL_NAMING_STRATEGY, PhysicalNamingStrategyImpl.class.getName());
    p.put(AvailableSettings.DIALECT_RESOLVERS, CustomDialectResolver.class.getName());
    p.put(AvailableSettings.JPA_JDBC_DRIVER, "org.postgresql.Driver");
    p.put(AvailableSettings.JPA_JDBC_URL, scratchUrl());
    p.put(AvailableSettings.JPA_JDBC_USER, BgTestDb.user());
    p.put(AvailableSettings.JPA_JDBC_PASSWORD, BgTestDb.password());
    p.put(AvailableSettings.HBM2DDL_AUTO, "update");
    p.put("javax.persistence.validation.mode", "none");
    EntityManagerFactory emf = Persistence.createEntityManagerFactory("testUnit", p);
    emf.close();
  }

  /** Името на уникалното ограничение (contype u) точно върху (company, fixed_asset_seq). */
  private static String uniqueConstraintName(Connection c) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT string_agg(con.conname, ',') FROM pg_constraint con"
                    + " WHERE con.conrelid = to_regclass('account_fixed_asset')"
                    + " AND con.contype = 'u'"
                    + " AND pg_get_constraintdef(con.oid) = 'UNIQUE (company, fixed_asset_seq)'")) {
      rs.next();
      return rs.getString(1);
    }
  }

  private static long uniqueCount(Connection c) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(UNIQUE_COUNT_SQL)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static String file() throws IOException {
    try (InputStream in = BgFixedAssetCleanSchemaDbTest.class.getResourceAsStream(FILE)) {
      assertNotNull(in, "файлът е на classpath-а: " + FILE);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  @Order(1)
  @DisplayName(
      "първи старт на празна база: проверката няма какво да гледа, домейнът създава схемата")
  void firstBootOnEmptyDatabase() throws SQLException, IOException {
    boot();
    try (Connection c = connect(scratchUrl())) {
      assertEquals(1, uniqueCount(c), "уникалността (company, fixed_asset_seq) идва от домейна");
      assertEquals(
          BgFixedAssetInventoryNumberMigrationDbTest.UK_NAME,
          uniqueConstraintName(c),
          "името на Hibernate е същото, с което я слага файлът за заварените бази");
      assertTrue(file().contains(BgFixedAssetInventoryNumberMigrationDbTest.UK_NAME));
    }
  }

  @Test
  @Order(2)
  @DisplayName("схемата от домейна стига на проверката при старт")
  void domainSchemaPassesTheStartupCheck() throws SQLException {
    try (Connection c = connect(scratchUrl())) {
      assertNull(BgFixedAssetSchemaCheck.refusal(c));
    }
  }

  @Test
  @Order(3)
  @DisplayName("втори старт (рестарт) на същата база минава и не добавя втора уникалност")
  void restartOnDomainSchema() throws SQLException {
    boot();
    try (Connection c = connect(scratchUrl())) {
      assertEquals(1, uniqueCount(c));
    }
  }

  @Test
  @Order(4)
  @DisplayName("пуснат по погрешка върху схемата от домейна, файлът не добавя втора уникалност")
  void migrationFileOnDomainSchemaIsANoOp() throws SQLException, IOException {
    try (Connection c = connect(scratchUrl())) {
      c.setAutoCommit(false);
      try (Statement st = c.createStatement()) {
        st.execute(file());
        c.commit();
      } catch (SQLException e) {
        c.rollback();
        throw e;
      }
      c.setAutoCommit(true);
      assertEquals(1, uniqueCount(c));
      assertNull(BgFixedAssetSchemaCheck.refusal(c));
    }
    boot();
    try (Connection c = connect(scratchUrl())) {
      assertEquals(1, uniqueCount(c), "и рестартът след файла не добавя втора");
    }
  }
}

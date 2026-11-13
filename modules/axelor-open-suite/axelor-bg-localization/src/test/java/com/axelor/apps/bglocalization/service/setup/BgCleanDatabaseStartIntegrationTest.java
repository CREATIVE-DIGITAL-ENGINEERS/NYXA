package com.axelor.apps.bglocalization.service.setup;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.fixedasset.BgFixedAssetSchemaCheck;
import com.axelor.apps.bglocalization.service.partner.BgPartnerSchemaCheck;
import com.axelor.apps.bglocalization.service.payroll.BgTrzSchemaCheck;
import com.axelor.db.JpaScanner;
import com.axelor.db.hibernate.dialect.CustomDialectResolver;
import com.axelor.db.hibernate.naming.ImplicitNamingStrategyImpl;
import com.axelor.db.hibernate.naming.PhysicalNamingStrategyImpl;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.integrator.spi.IntegratorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * НОВ КЛИЕНТ С ПРАЗНА БАЗА СТАРТИРА — с всички проверки при старт от {@code
 * META-INF/services/org.hibernate.integrator.spi.Integrator}.
 *
 * <p>Стартът е истинският: Hibernate с настройките на {@code JpaModule} и {@code ddl=update}, както
 * приложението вдига схемата на празна база. Интеграторите ги вика самият Hibernate — в {@code
 * SessionFactoryImpl} (5.6.15) {@code integrator.integrate} е на ред 300, а {@code ddl=update}
 * ({@code SchemaManagementToolCoordinator.process}) — на ред 335. Тоест всяка проверка вижда базата
 * ПРЕДИ ddl=update: на първия старт — празна, на всеки следващ — схемата, която домейнът е създал.
 *
 * <ol>
 *   <li>първи старт на празна база — минава, и трите интегратора са викнати;
 *   <li>проверките срещу схемата от домейна — „готово“;
 *   <li>втори старт (рестарт) — минава;
 *   <li>заварена база без колоната от {@link BgTrzSchemaCheck#MIGRATION_FILE} — стартът спира с
 *       името на файла, а колоната остава липсваща: ddl=update не я е добавил, до него не се стига.
 * </ol>
 *
 * <p>⚠️ HSQLDB в паметта, за да върви на всяка машина и в CI. {@link BgTrzSchemaCheck} чете през
 * {@code DatabaseMetaData} и тук гледа наистина; {@link BgPartnerSchemaCheck} и {@link
 * BgFixedAssetSchemaCheck} четат {@code pg_catalog} и извън PostgreSQL връщат „готово“ — техният
 * интегратор пак се вика, но условието им върху PostgreSQL мерят {@code
 * BgFixedAssetCleanSchemaDbTest} и {@code BgCoaTemplateFreshCompanyIntegrationTest}.
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BgCleanDatabaseStartIntegrationTest {

  private static final String SERVICES = "/META-INF/services/" + Integrator.class.getName();

  /** Проверките при старт на модула — всяка в свой интегратор. */
  private static final Set<String> STARTUP_CHECKS =
      Set.of(
          "com.axelor.apps.bglocalization.service.partner.BgPartnerIntegrator",
          "com.axelor.apps.bglocalization.service.fixedasset.BgFixedAssetIntegrator",
          "com.axelor.apps.bglocalization.service.payroll.BgTrzIntegrator");

  /** Своя база за класа: живее, докато живее JVM-ът — между стартовете също. */
  private static final String URL = "jdbc:hsqldb:mem:clean_start_" + System.nanoTime();

  private static final String USER = "SA";

  private static Connection connect() throws SQLException {
    return DriverManager.getConnection(URL, USER, "");
  }

  /**
   * Един старт на Hibernate срещу базата, с {@code ddl=update} и настройките на {@code JpaModule}.
   * Провал в интегратор = провален старт (изключението излиза оттук). Връща заредените интегратори
   * и слушателите на PERSIST — следите, че интеграторите наистина са минали.
   */
  private static Boot boot() {
    Map<String, Object> p = new HashMap<>();
    p.put(AvailableSettings.SCANNER, JpaScanner.class.getName());
    p.put(AvailableSettings.USE_NEW_ID_GENERATOR_MAPPINGS, "true");
    p.put(AvailableSettings.IMPLICIT_NAMING_STRATEGY, ImplicitNamingStrategyImpl.class.getName());
    p.put(AvailableSettings.PHYSICAL_NAMING_STRATEGY, PhysicalNamingStrategyImpl.class.getName());
    p.put(AvailableSettings.DIALECT_RESOLVERS, CustomDialectResolver.class.getName());
    p.put(AvailableSettings.JPA_JDBC_DRIVER, "org.hsqldb.jdbc.JDBCDriver");
    p.put(AvailableSettings.JPA_JDBC_URL, URL);
    p.put(AvailableSettings.JPA_JDBC_USER, USER);
    p.put(AvailableSettings.JPA_JDBC_PASSWORD, "");
    p.put(AvailableSettings.HBM2DDL_AUTO, "update");
    p.put("javax.persistence.validation.mode", "none");
    EntityManagerFactory emf = Persistence.createEntityManagerFactory("testUnit", p);
    try {
      SessionFactoryImplementor sf = emf.unwrap(SessionFactoryImplementor.class);
      Set<String> integrators = new TreeSet<>();
      for (Integrator integrator :
          sf.getServiceRegistry().getService(IntegratorService.class).getIntegrators()) {
        integrators.add(integrator.getClass().getName());
      }
      Set<String> persist = new TreeSet<>();
      sf.getServiceRegistry()
          .getService(EventListenerRegistry.class)
          .getEventListenerGroup(EventType.PERSIST)
          .listeners()
          .forEach(l -> persist.add(l.getClass().getSimpleName()));
      return new Boot(integrators, persist);
    } finally {
      emf.close();
    }
  }

  private static final class Boot {
    final Set<String> integrators;
    final Set<String> persistListeners;

    Boot(Set<String> integrators, Set<String> persistListeners) {
      this.integrators = integrators;
      this.persistListeners = persistListeners;
    }
  }

  private static Set<String> services() throws IOException {
    try (InputStream in = BgCleanDatabaseStartIntegrationTest.class.getResourceAsStream(SERVICES)) {
      assertNotNull(in, SERVICES + " липсва от classpath-а");
      return new String(in.readAllBytes(), StandardCharsets.UTF_8)
          .lines()
          .map(String::trim)
          .filter(l -> !l.isEmpty() && !l.startsWith("#"))
          .collect(Collectors.toCollection(TreeSet::new));
    }
  }

  private static Set<String> columns(String table) throws SQLException {
    Set<String> out = new TreeSet<>();
    try (Connection c = connect();
        ResultSet rs = c.getMetaData().getColumns(null, null, upper(c, table), null)) {
      while (rs.next()) {
        out.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
      }
    }
    return out;
  }

  private static String upper(Connection c, String table) throws SQLException {
    DatabaseMetaData md = c.getMetaData();
    return md.storesUpperCaseIdentifiers() ? table.toUpperCase(Locale.ROOT) : table;
  }

  @Test
  @Order(1)
  @DisplayName("първи старт на празна база: минава, и трите проверки при старт са викнати")
  void firstStartOnAnEmptyDatabase() throws Exception {
    Set<String> services = services();
    assertTrue(
        services.containsAll(STARTUP_CHECKS),
        "проверка при старт е изпаднала от " + SERVICES + ": " + services);

    Boot boot = boot();

    assertTrue(
        boot.integrators.containsAll(services),
        "Hibernate не е заредил всички интегратори от " + SERVICES + ": " + boot.integrators);
    assertTrue(
        boot.persistListeners.contains("BgTrzCompanySequenceListener"),
        "BgTrzIntegrator не е стигнал до регистрацията: " + boot.persistListeners);
    assertTrue(
        columns("bglocalization_bg_pay_slip").contains("company"),
        "ddl=update не е създал фиша с company — тестът не е стартирал схемата");
  }

  @Test
  @Order(2)
  @DisplayName("схемата от домейна стига на всяка проверка при старт")
  void theDomainSchemaPassesEveryStartupCheck() throws SQLException {
    try (Connection c = connect()) {
      assertNull(BgTrzSchemaCheck.refusal(c));
      assertNull(BgPartnerSchemaCheck.refusal(c));
      assertNull(BgFixedAssetSchemaCheck.refusal(c));
    }
  }

  @Test
  @Order(3)
  @DisplayName("втори старт (рестарт) на същата база минава")
  void restartOnTheDomainSchema() {
    Boot boot = boot();
    assertTrue(boot.persistListeners.contains("BgTrzCompanySequenceListener"));
  }

  @Test
  @Order(4)
  @DisplayName("заварена база без колоната от 2026-10-21-trz-numbering… → стартът спира с името")
  void anExistingDatabaseWithoutTheColumnStopsTheStart() throws SQLException {
    try (Connection c = connect();
        Statement st = c.createStatement()) {
      st.execute("ALTER TABLE bglocalization_bg_pay_slip DROP COLUMN company CASCADE");
    }
    assertFalse(columns("bglocalization_bg_pay_slip").contains("company"));

    RuntimeException failure = assertThrows(RuntimeException.class, () -> boot());

    List<String> messages = new ArrayList<>();
    for (Throwable t = failure; t != null; t = t.getCause()) {
      messages.add(String.valueOf(t.getMessage()));
    }
    String refusal =
        messages.stream()
            .filter(m -> m.contains(BgTrzSchemaCheck.MIGRATION_FILE))
            .findFirst()
            .orElse(null);
    assertNotNull(refusal, "отказът не назовава файла: " + messages);
    assertTrue(refusal.contains("bglocalization_bg_pay_slip няма колона company"), refusal);
    assertTrue(refusal.contains("срещу базата"), refusal);
    assertFalse(
        columns("bglocalization_bg_pay_slip").contains("company"),
        "колоната се е появила — ddl=update е минал преди проверката");
  }
}

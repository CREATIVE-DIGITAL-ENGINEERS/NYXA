package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import com.axelor.app.AppModule;
import com.axelor.apps.base.module.BaseModule;
import com.axelor.auth.AuthModule;
import com.axelor.db.JpaModule;
import com.google.inject.AbstractModule;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DB-backed wiring for the accounting-import tests that have to POST a move.
 *
 * <p>Uses the {@code testPostgres} persistence unit — a scratch PostgreSQL database, never the real
 * {@code nyxa} one — because HSQLDB cannot select the {@code Move} entity at all: this module's
 * {@code Move.xml} extension declares {@code ocrUploadIds} as a PostgreSQL-only {@code
 * array_to_string(array_agg(...))} formula which Hibernate inlines into every {@code Move} select.
 *
 * <p>Deliberately separate from {@link MicroinvestAccountingTestModule}, which keeps the fast
 * HSQLDB unit for the schema-shape assertions that do not need a posted move.
 */
public class MicroinvestAccountingPostgresModule extends AbstractModule {

  /** Името на persistence unit-а в {@code src/test/resources/META-INF/persistence.xml}. */
  static final String UNIT = "testPostgres";

  /**
   * ИМА ЛИ ТЕСТОВА PostgreSQL — условието на {@code @EnabledIf} за класовете с този модул.
   *
   * <p>⚠️ Проверява се ПРЕДИ Guice: {@code GuiceExtension} вдига инжектора (и JpaModule) в {@code
   * beforeAll}, тоест {@code assumeTrue} в {@code @BeforeAll} идва късно — без база класът падаше с
   * {@code initializationError}, вместо да се пропусне като другите DB тестове. URL-ът,
   * потребителят и паролата се четат от САМИЯ persistence unit, не се дублират тук.
   */
  public static boolean available() {
    try {
      String[] connection = connectionOf(UNIT);
      if (connection == null) {
        return false;
      }
      DriverManager.setLoginTimeout(3);
      try (Connection c =
          DriverManager.getConnection(connection[0], connection[1], connection[2])) {
        return c.isValid(3);
      }
    } catch (Exception e) {
      return false;
    }
  }

  /** {url, user, password} на persistence unit-а, или {@code null}. */
  static String[] connectionOf(String unit) throws Exception {
    Enumeration<URL> files =
        MicroinvestAccountingPostgresModule.class
            .getClassLoader()
            .getResources("META-INF/persistence.xml");
    Pattern block =
        Pattern.compile(
            "<persistence-unit name=\"" + Pattern.quote(unit) + "\".*?</persistence-unit>",
            Pattern.DOTALL);
    while (files.hasMoreElements()) {
      String xml;
      try (InputStream in = files.nextElement().openStream()) {
        xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      Matcher m = block.matcher(xml);
      if (m.find()) {
        String body = m.group();
        return new String[] {
          property(body, "javax.persistence.jdbc.url"),
          property(body, "javax.persistence.jdbc.user"),
          property(body, "javax.persistence.jdbc.password")
        };
      }
    }
    return null;
  }

  private static String property(String body, String name) {
    Matcher m =
        Pattern.compile("<property\\s+name=\"" + Pattern.quote(name) + "\"\\s+value=\"([^\"]*)\"")
            .matcher(body);
    return m.find() ? m.group(1) : null;
  }

  @Override
  protected void configure() {
    install(new JpaModule(UNIT, true, true));
    install(new BaseModule());
    install(new AuthModule());
    install(new AppModule());
  }
}

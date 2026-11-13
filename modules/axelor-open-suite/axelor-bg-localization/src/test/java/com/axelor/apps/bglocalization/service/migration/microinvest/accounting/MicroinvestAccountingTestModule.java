package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import com.axelor.app.AppModule;
import com.axelor.apps.base.module.BaseModule;
import com.axelor.auth.AuthModule;
import com.axelor.db.JpaModule;
import com.google.inject.AbstractModule;

/**
 * DB-backed wiring for the accounting-import persistence tests.
 *
 * <p>Runs against the module's in-memory HSQLDB {@code testUnit} (see {@code
 * src/test/resources/META-INF/persistence.xml}) with {@code hbm2ddl.auto=create}, so the schema
 * under test is the one the domain XMLs generate — never the real {@code nyxa} database.
 *
 * <p>{@code AppModule} scans the classpath and auto-installs every {@code AxelorModule} it finds,
 * which on this test classpath includes the real {@code BgLocalizationModule}; nothing is
 * overridden here because these tests exercise the real repositories.
 */
public class MicroinvestAccountingTestModule extends AbstractModule {
  @Override
  protected void configure() {
    install(new JpaModule("testUnit", true, true));
    install(new BaseModule());
    install(new AuthModule());
    install(new AppModule());
  }
}

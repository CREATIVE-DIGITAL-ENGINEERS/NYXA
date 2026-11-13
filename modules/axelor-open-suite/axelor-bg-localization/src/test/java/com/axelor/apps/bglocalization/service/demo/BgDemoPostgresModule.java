package com.axelor.apps.bglocalization.service.demo;

import com.axelor.app.AppModule;
import com.axelor.auth.AuthModule;
import com.axelor.db.JpaModule;
import com.google.inject.AbstractModule;

/**
 * ПРОИЗВОДСТВЕНОТО ОКАБЕЛЯВАНЕ ВЪРХУ ИЗОЛИРАНА PostgreSQL — за демо данните.
 *
 * <p>Persistence unit {@code demoPostgres} — настройките на приложението (валидацията „auto“
 * оразмерява колоните по {@code @Size}, както в production); адресът, потребителят и паролата идват
 * от {@code -Ddemo.db.url/username/password} и стават {@code axelor.config.hibernate.connection.*},
 * преди {@code JpaModule} да прочете настройките (същият механизъм като {@code
 * BgSaftTrialPostgresModule}). {@code -Ddemo.db.ddl=update} създава схемата на празна база.
 *
 * <p>⚠️ Базата е СВОЯ, празна в началото — никога общата {@code nyxa}. Тестът я подготвя като
 * първия старт на нова среда (data-init + App Install) и после налива в ново дружество при всяко
 * пускане.
 *
 * <p>Защо PostgreSQL, а не HSQLDB: фактурите, плащанията и отчетите четат {@code Move}, {@code
 * InvoiceTerm} и {@code Partner}, чиито формули са PostgreSQL-only.
 */
public class BgDemoPostgresModule extends AbstractModule {

  static final String UNIT = "demoPostgres";

  /** Префиксът на свойствата за базата. */
  static final String DB = "demo.db.";

  @Override
  protected void configure() {
    for (String key : new String[] {"url", "username", "password"}) {
      String value = System.getProperty(DB + key);
      if (value != null) {
        System.setProperty("axelor.config.hibernate.connection." + key, value);
      }
    }
    String ddl = System.getProperty(DB + "ddl");
    if (ddl != null) {
      System.setProperty("axelor.config.hibernate.hbm2ddl.auto", ddl);
    }
    install(new JpaModule(UNIT, true, true));
    install(new AuthModule());
    // AppModule открива и инсталира ВСИЧКИ модули от classpath-а — както в приложението. Изрично
    // инсталиран модул би минал втори път: прихващачите (BgStockMoveGuard) се закачат двойно и
    // всяко изписване се осчетоводява два пъти.
    install(new AppModule());
  }
}

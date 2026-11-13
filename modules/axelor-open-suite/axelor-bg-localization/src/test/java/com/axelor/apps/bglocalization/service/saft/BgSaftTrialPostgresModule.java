package com.axelor.apps.bglocalization.service.saft;

import com.axelor.app.AppModule;
import com.axelor.apps.account.module.AccountModule;
import com.axelor.apps.base.module.BaseModule;
import com.axelor.apps.bglocalization.module.BgLocalizationModule;
import com.axelor.apps.hr.module.HumanResourceModule;
import com.axelor.auth.AuthModule;
import com.axelor.db.JpaModule;
import com.google.inject.AbstractModule;

/**
 * ПРОИЗВОДСТВЕНОТО ОКАБЕЛЯВАНЕ ВЪРХУ ИЗОЛИРАНА PostgreSQL — за пробния файл на G6c.
 *
 * <p>Persistence unit {@code testPostgres} ({@code src/test/resources/META-INF/persistence.xml},
 * {@code hibernate.hbm2ddl.auto = none} — схемата НЕ се пипа). Адресът и потребителят се подават с
 * {@code -Dsaft.trial.db.url/username/password}; тук стават {@code
 * axelor.config.hibernate.connection.*}, преди {@code JpaModule} да прочете настройките: той подава
 * {@code hibernate.*} от тях на Hibernate, а Hikari ги чете пред {@code javax.persistence.jdbc.*}
 * от файла. ⚠️ {@code -Dhibernate.connection.*} и {@code db.testPostgres.*} НЕ бият файла —
 * проверено; а Gradle прехвърля в разклонената JVM само {@code microinvest.*}, {@code hibernate.*}
 * и {@code saft.trial.*} (build.gradle). Базата е тази, срещу която е вдигнато приложението с App
 * Install и BG_COA — никога общата {@code nyxa} и никога работна среда.
 *
 * <p>Защо PostgreSQL, а не HSQLDB: четците на SAF-T избират {@code Move} и {@code Partner}, а
 * формулите им ({@code Move.ocrUploadIds}, балансите на партньора) са PostgreSQL-only.
 */
public class BgSaftTrialPostgresModule extends AbstractModule {

  static final String UNIT = "testPostgres";

  /** Префиксът на свойствата за базата: {@code saft.trial.db.url/username/password}. */
  static final String DB = "saft.trial.db.";

  @Override
  protected void configure() {
    for (String key : new String[] {"url", "username", "password"}) {
      String value = System.getProperty(DB + key);
      if (value != null) {
        System.setProperty("axelor.config.hibernate.connection." + key, value);
      }
    }
    // G6b: схемата на КЛОНИНГ на пробната база се догонва с develop (-Dsaft.trial.db.ddl=update);
    // без ключа е „none“ — оригиналната база не се пипа.
    String ddl = System.getProperty(DB + "ddl");
    if (ddl != null) {
      System.setProperty("axelor.config.hibernate.hbm2ddl.auto", ddl);
    }
    install(new JpaModule(UNIT, true, true));
    install(new BaseModule());
    install(new AccountModule());
    install(new HumanResourceModule());
    install(new AuthModule());
    install(new AppModule());
    install(new BgLocalizationModule());
  }
}

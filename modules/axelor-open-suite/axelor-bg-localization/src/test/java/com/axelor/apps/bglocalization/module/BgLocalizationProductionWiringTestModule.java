package com.axelor.apps.bglocalization.module;

import com.axelor.app.AppModule;
import com.axelor.apps.account.module.AccountModule;
import com.axelor.apps.base.module.BaseModule;
import com.axelor.apps.hr.module.HumanResourceModule;
import com.axelor.auth.AuthModule;
import com.axelor.db.JpaModule;
import com.google.inject.AbstractModule;

/**
 * Unlike {@link BgLocalizationTestModule} (deliberately WITHOUT {@code BgLocalizationModule} -- see
 * its own javadoc), this installs the REAL production {@link BgLocalizationModule}, the same way
 * {@code AccountTestModule}/{@code AiOcrTestModule} install their own module under test alongside
 * its declared dependencies. This is what proves the Guice binding this module adds
 * (NominatimHttpClient -> NominatimHttpClientImpl) actually resolves in the real object graph, not
 * just in a hand-built one where every dependency is manually mocked in.
 */
public class BgLocalizationProductionWiringTestModule extends AbstractModule {
  @Override
  protected void configure() {
    install(new JpaModule("testUnit", true, true));
    install(new BaseModule());
    install(new AccountModule());
    install(new HumanResourceModule());
    install(new AuthModule());
    install(new AppModule());
    install(new BgLocalizationModule());
  }
}

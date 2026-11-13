package com.axelor.apps.bglocalization.module;

import com.axelor.app.AppModule;
import com.axelor.apps.base.module.BaseModule;
import com.axelor.auth.AuthModule;
import com.axelor.db.JpaModule;
import com.google.inject.AbstractModule;

/**
 * Minimal DB-backed test wiring for axelor-bg-localization: just enough to persist/read the stock
 * entities this module's own persistence-only tests touch (Address, City, AppBgLocalization) via
 * their stock repositories. Deliberately does NOT install {@code BgLocalizationModule} or {@code
 * AiOcrModule} -- the geocoding SERVICES are already covered by pure/mocked unit tests
 * (BgAddressGeocodeServiceTest etc.); this module exists only to prove the new columns actually
 * round-trip through a real (in-memory HSQLDB) persistence context, not to re-test the services.
 */
public class BgLocalizationTestModule extends AbstractModule {
  @Override
  protected void configure() {
    install(new JpaModule("testUnit", true, true));
    install(new BaseModule());
    install(new AuthModule());
    install(new AppModule());
  }
}

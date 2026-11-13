package com.axelor.apps.bglocalization.service.geocode;

import com.axelor.app.AppModule;
import com.axelor.apps.base.module.BaseModule;
import com.axelor.auth.AuthModule;
import com.axelor.db.JpaModule;
import com.google.inject.AbstractModule;
import com.google.inject.util.Modules;

/**
 * DB-backed wiring for {@link BgCoordinatePrecisionPersistenceTest}: real {@code
 * BgAddressGeocodeService}/{@code BgCityCentroidService} objects, Guice-managed (so the real
 * {@code @Transactional} interceptor applies -- unlike a hand-built {@code new
 * BgCityCentroidService(...)}, which bypasses Guice AOP entirely and would make a rollback test
 * meaningless), with only the network edge ({@link NominatimHttpClient}) replaced by {@link
 * ScriptableNominatimHttpClient}.
 *
 * <p>{@code AppModule} scans the classpath and auto-installs every {@code AxelorModule} it finds
 * there -- on THIS module's test classpath that unavoidably includes the real {@code
 * BgLocalizationModule} itself (proven the hard way: a plain {@code bind(NominatimHttpClient...)}
 * next to {@code install(new AppModule())} throws Guice {@code BindingAlreadySet}, both bindings
 * pointing at the same key from two different places). {@link Modules#override} is the correct tool
 * for exactly this: keep every other real binding {@code AppModule} (and whatever it transitively
 * installs) provides, and replace only this one key.
 */
public class BgGeocodePrecisionTestModule extends AbstractModule {
  @Override
  protected void configure() {
    install(new JpaModule("testUnit", true, true));
    install(new BaseModule());
    install(new AuthModule());
    install(
        Modules.override(new AppModule())
            .with(
                binder ->
                    binder
                        .bind(NominatimHttpClient.class)
                        .to(ScriptableNominatimHttpClient.class)));
  }
}

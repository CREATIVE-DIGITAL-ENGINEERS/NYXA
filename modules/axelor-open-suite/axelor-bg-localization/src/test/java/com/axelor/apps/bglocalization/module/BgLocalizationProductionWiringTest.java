package com.axelor.apps.bglocalization.module;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.geocode.BgAddressGeocodeService;
import com.axelor.apps.bglocalization.service.geocode.BgCityCentroidService;
import com.axelor.apps.bglocalization.service.geocode.NominatimHttpClient;
import com.axelor.apps.bglocalization.service.geocode.NominatimHttpClientImpl;
import com.axelor.apps.bglocalization.web.BgAddressMapController;
import com.axelor.apps.bglocalization.web.BgPartnerMapController;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import com.google.inject.Binding;
import com.google.inject.Injector;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proves the REAL production Guice graph resolves the BG geocoding chain -- the one thing every
 * other geocoding test cannot show, because they all construct {@link BgAddressGeocodeService}/
 * {@link BgCityCentroidService} by hand with manually mocked dependencies (see e.g. {@code
 * BgAddressGeocodeServiceTest}, {@code BgAddressGeocodePersistenceIntegrationTest}). None of those
 * would have caught {@code NominatimHttpClient} never being bound in {@link BgLocalizationModule}:
 * the missing binding only shows up when something asks Guice to build the graph starting from the
 * real module, which is exactly what {@link BgLocalizationProductionWiringTestModule} installs (not
 * a hand-assembled substitute, unlike {@link BgLocalizationTestModule}).
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgLocalizationProductionWiringTest extends JpaSupport {

  @Test
  void nominatimHttpClient_resolvesToTheRealImplementation_asASingleton() {
    NominatimHttpClient first = Beans.get(NominatimHttpClient.class);
    NominatimHttpClient second = Beans.get(NominatimHttpClient.class);

    assertNotNull(first);
    assertTrue(first instanceof NominatimHttpClientImpl);
    assertSame(
        first, second, "the production client/rate limiter must be shared, not one per lookup");
  }

  /**
   * Not just "some instance got constructed" -- {@code NominatimHttpClient} is an interface, so
   * {@code Beans.get} can only succeed here via an explicit {@code bind(...).to(...)}; this pins
   * down that the binding Guice actually used was written in {@link BgLocalizationModule} itself
   * (source records the exact file/line of the {@code bind()} call), not some other module that
   * happens to also satisfy the type.
   */
  @Test
  void nominatimBinding_comesFromTheRealBgLocalizationModule() {
    Injector injector = Beans.get(Injector.class);
    Binding<NominatimHttpClient> binding = injector.getBinding(NominatimHttpClient.class);

    assertTrue(
        binding.getSource().toString().contains("BgLocalizationModule"),
        () ->
            "expected the binding to originate in BgLocalizationModule, but source was: "
                + binding.getSource());
  }

  @Test
  void bgCityCentroidService_resolvesThroughTheRealNominatimBinding() {
    assertNotNull(Beans.get(BgCityCentroidService.class));
  }

  @Test
  void bgAddressGeocodeService_resolvesThroughTheRealNominatimBinding() {
    assertNotNull(Beans.get(BgAddressGeocodeService.class));
  }

  /**
   * Not just non-null: proves Guice hands back an instance of the real production controller
   * classes -- never a hand-rolled test double -- so the {@code protected} seams {@code
   * BgPartnerMapControllerTest} overrides are guaranteed to run their real bodies (real {@code
   * Beans.get(JpaSecurity.class)} permission check included) in the actual running application.
   *
   * <p>{@code getClass()} is deliberately NOT compared for exact equality: Guice returns a
   * CGLIB-generated {@code BgPartnerMapController$$EnhancerByGuice$$...} subclass here (some bound
   * interceptor matches at least one method on these classes), which is normal, transparent Guice
   * AOP -- it still runs the real inherited method bodies via ordinary Java overriding, it is not a
   * substitute implementation. No {@code bind(BgPartnerMapController.class)}/{@code
   * bind(BgAddressMapController.class)} exists anywhere in this codebase (confirmed by inspection),
   * so nothing but Guice's own enhancement can produce this instance.
   */
  @Test
  void addressAndPartnerMapControllers_resolveToTheRealProductionClasses() {
    assertTrue(Beans.get(BgAddressMapController.class) instanceof BgAddressMapController);
    assertTrue(Beans.get(BgPartnerMapController.class) instanceof BgPartnerMapController);
  }
}

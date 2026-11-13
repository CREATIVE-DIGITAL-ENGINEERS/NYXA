package com.axelor.apps.bglocalization.service.geocode;

import com.axelor.apps.base.db.AddressTemplate;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.repo.AddressTemplateRepository;
import com.axelor.apps.base.db.repo.CountryRepository;
import com.axelor.inject.Beans;
import com.axelor.studio.db.AppBase;
import com.axelor.studio.db.repo.AppBaseRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assertions;

/**
 * Shared fixture builder for the {@code BgCoordinatePrecisionPersistenceTest} family: the same
 * AppBase/AddressTemplate/Country prerequisites {@code AddressRepository.save()} needs, mirroring
 * {@code BgAddressGeocodePersistenceIntegrationTest}. Each caller runs in its OWN test class (its
 * own fresh Guice injector and in-memory schema -- see each test's own javadoc for why this is
 * split into separate classes rather than one class with several {@code @Test} methods).
 */
final class BgGeocodeTestFixtures {

  private BgGeocodeTestFixtures() {}

  static Country setUpBulgaria() {
    AppBase appBase = new AppBase();
    appBase.setMapApiSelect(AppBaseRepository.MAP_API_OPEN_STREET_MAP);
    Beans.get(AppBaseRepository.class).save(appBase);

    AddressTemplate template = new AddressTemplate();
    template.setName("DEFAULT");
    template.setEngineSelect(AddressTemplateRepository.GROOVY_TEMPLATE);
    template.setAddressL2Str("${(Address.department == null) ? '' : Address.department}");
    template.setAddressL3Str("${(Address.floor == null) ? '' : Address.floor}");
    template.setAddressL4Str("${(Address.streetName == null) ? '' : Address.streetName}");
    template.setAddressL5Str("${(Address.postBox == null) ? '' : Address.postBox}");
    template.setAddressL6Str(
        "${(Address.city == null) ? '' : Address.city.name} ${(Address.zip == null) ? '' :"
            + " Address.zip}");
    template.setTemplateStr(
        "${(Address.addressL4 == null) ? '' : Address.addressL4}\n"
            + "${(Address.addressL6 == null) ? '' : Address.addressL6}\n"
            + "${(Address.country == null) ? '' : Address.country.name}");
    template.setAddressTemplateLineList(new ArrayList<>());
    Beans.get(AddressTemplateRepository.class).save(template);

    Country bulgaria = new Country();
    bulgaria.setName("България");
    bulgaria.setAlpha2Code("BG");
    bulgaria.setAddressTemplate(template);
    Beans.get(CountryRepository.class).save(bulgaria);
    return bulgaria;
  }

  /**
   * Tolerant coordinate comparison, same 0.01 tolerance as {@code
   * BgAddressGeocodePersistenceIntegrationTest.assertCloseTo} -- for the same reason, confirmed
   * empirically here: the generated entities' {@code @Column} annotations carry no {@code
   * precision}/{@code scale} at all (checked directly in {@code
   * axelor-bg-localization/build/src-gen/.../City.java} and axelor-base's own {@code Address.java}
   * -- both just {@code @Column(nullable = true)}), so this test persistence unit's plain {@code
   * hibernate.hbm2ddl.auto=create} falls back to Hibernate's generic {@code BigDecimal} default,
   * {@code NUMERIC(19,2)} (confirmed via {@code DatabaseMetaData.getColumns} against the live
   * HSQLDB schema), REGARDLESS of the {@code precision="9" scale="6"}/{@code precision="38"
   * scale="18"} declared in the domain XML. That real precision/scale is enforced two OTHER ways in
   * this codebase, neither of which is this raw JPA `@Column`: the real application's DB schema is
   * built by Axelor's own model-sync tooling (which does read it), and {@link
   * BgCoordinateNormalizer} plus the generated Bean Validation {@code @Digits} constraint (the
   * thing that actually threw "numeric value out of bounds" in production) are what enforce it
   * logically. So this test can faithfully prove the persisted value survives the round trip at
   * city-block precision (proving persistence completes and nothing is silently dropped), and
   * separately -- via the assertion immediately after {@code resolve()}/{@code
   * getOrResolveCentroid()}, before any DB round-trip -- that the in-memory value {@link
   * BgCoordinateNormalizer} produced is exact. It cannot prove exact 6-decimal survival through
   * HSQLDB specifically, because this schema cannot store that many regardless of what Java sends
   * it.
   */
  static void assertCloseTo(BigDecimal expected, BigDecimal actual) {
    Assertions.assertNotNull(actual);
    Supplier<String> message = () -> "expected ~" + expected + " but was " + actual;
    Assertions.assertTrue(
        actual.subtract(expected).abs().compareTo(new BigDecimal("0.01")) < 0, message);
  }
}

package com.axelor.apps.bglocalization.service.geocode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.City;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.repo.AddressRepository;
import com.axelor.apps.base.db.repo.CityRepository;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * DB-backed (in-memory HSQLDB) proof that a Nominatim-style coordinate carrying more than 6
 * fractional digits is rounded (HALF_UP) before it reaches {@code Address.latit/longit}, via the
 * real full-address path ({@link BgAddressGeocodeService}) -- one of the two places {@link
 * BgCoordinateNormalizer} was wired in to fix "Validation failed for com.axelor.apps.base.db.City:
 * latit: numeric value out of bounds (3 digits.6 digits expected)", seen manually clicking Map on a
 * Supplier whose address fell through to the City-centroid tier (see {@link
 * BgCityHighPrecisionPersistenceTest} for that path).
 *
 * <p>One {@code @Test} method, its own class, matching this module's established convention ({@code
 * BgAddressGeocodePersistenceIntegrationTest} also has exactly one). The reload assertion is
 * tolerant, not exact -- see {@link BgGeocodeTestFixtures#assertCloseTo} for why: this test
 * persistence unit's generated entities carry no {@code @Column(precision=,scale=)} at all, so
 * HSQLDB stores every {@code BigDecimal} as {@code NUMERIC(19,2)} regardless of what the domain XML
 * declares or what Java sends it. The EXACT value {@link BgCoordinateNormalizer} produces is
 * asserted separately, in memory, right after {@code resolve()} returns and before any DB
 * round-trip.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgGeocodePrecisionTestModule.class)
@Tag("integration")
class BgAddressHighPrecisionPersistenceTest extends JpaSupport {

  @Test
  void highPrecisionFullAddressMatch_isRoundedToSixDecimalsBeforePersisting() {
    // 7th fractional digit is exactly 5 (nothing after) on the latitude -- HALF_UP must round up;
    // the longitude's 7th digit is 1, which must NOT round up. Same rounding cases the pure
    // BgCoordinateNormalizerTest already proves in isolation; this proves the whole real chain
    // (real Guice service, real @Transactional, real HSQLDB column) applies it identically.
    String highPrecisionXml =
        "<searchresults><place lat=\"42.1234565\" lon=\"23.1234561\">"
            + "<house_number>1</house_number><road>ул. Test</road>"
            + "<city>София</city><country_code>bg</country_code></place></searchresults>";

    Long[] addressId = new Long[1];
    JPA.runInTransaction(
        () -> {
          Country bulgaria = BgGeocodeTestFixtures.setUpBulgaria();
          City sofia = new City();
          sofia.setName("София");
          sofia.setCountry(bulgaria);
          Beans.get(CityRepository.class).save(sofia);

          Address address = new Address();
          address.setCountry(bulgaria);
          address.setCity(sofia);
          address.setStreetName("ул. Test 1");
          Beans.get(AddressRepository.class).save(address);
          addressId[0] = address.getId();
        });

    ((ScriptableNominatimHttpClient) Beans.get(NominatimHttpClient.class))
        .setNextResponseXml(highPrecisionXml);

    Address managed = Beans.get(AddressRepository.class).find(addressId[0]);
    GeocodeResult result = Beans.get(BgAddressGeocodeService.class).resolve(managed, false);

    assertTrue(result.isSafe());
    assertEquals(GeocodeAccuracy.EXACT, result.getAccuracy());
    // Exact, in memory, before any DB round-trip -- see BgCoordinateNormalizerTest and
    // BgAddressGeocodeServiceTest for the same proof against a pure/mocked orchestration.
    assertEquals(0, result.getLatitude().compareTo(new BigDecimal("42.123457")));
    assertEquals(0, result.getLongitude().compareTo(new BigDecimal("23.123456")));

    JPA.clear();

    Address reloaded = Beans.get(AddressRepository.class).find(addressId[0]);
    BgGeocodeTestFixtures.assertCloseTo(new BigDecimal("42.123457"), reloaded.getLatit());
    BgGeocodeTestFixtures.assertCloseTo(new BigDecimal("23.123456"), reloaded.getLongit());
    assertTrue(reloaded.getIsValidLatLong());
  }
}

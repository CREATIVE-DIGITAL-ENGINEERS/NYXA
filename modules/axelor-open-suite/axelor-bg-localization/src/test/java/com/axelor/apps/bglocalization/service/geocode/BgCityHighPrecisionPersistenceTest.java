package com.axelor.apps.bglocalization.service.geocode;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.City;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.repo.CityRepository;
import com.axelor.apps.base.db.repo.CountryRepository;
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
 * fractional digits is rounded (HALF_UP) before it reaches {@code City.latit/longit} (precision="9"
 * scale="6"), via the City-centroid fallback ({@link BgCityCentroidService}) -- this is the exact
 * path that threw "Validation failed for com.axelor.apps.base.db.City: latit: numeric value out of
 * bounds (3 digits.6 digits expected)" in production. See {@link
 * BgAddressHighPrecisionPersistenceTest}'s javadoc for why this is its own class with one
 * {@code @Test} method rather than a shared class with several, and for why the reload assertion
 * below is tolerant rather than exact.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgGeocodePrecisionTestModule.class)
@Tag("integration")
class BgCityHighPrecisionPersistenceTest extends JpaSupport {

  @Test
  void highPrecisionCityCentroid_isRoundedToSixDecimalsBeforePersisting() {
    // Latitude's 7th digit is 5 with nothing after -> rounds up. Longitude's 7th digit is also 5
    // with nothing after -> rounds up too. No settlement/postcode tags: isSafeCityCentroid treats
    // an absent signal as "nothing to disprove a match", not a mismatch.
    String highPrecisionXml =
        "<searchresults><place lat=\"42.9876545\" lon=\"23.5555555\">"
            + "<country_code>bg</country_code></place></searchresults>";

    Long[] ids = new Long[2]; // [0]=country id, [1]=city id
    JPA.runInTransaction(
        () -> {
          Country bulgaria = BgGeocodeTestFixtures.setUpBulgaria();
          City sofia = new City();
          sofia.setName("София");
          sofia.setCountry(bulgaria);
          Beans.get(CityRepository.class).save(sofia);
          ids[0] = bulgaria.getId();
          ids[1] = sofia.getId();
        });

    ((ScriptableNominatimHttpClient) Beans.get(NominatimHttpClient.class))
        .setNextResponseXml(highPrecisionXml);

    Country bulgaria = Beans.get(CountryRepository.class).find(ids[0]);
    City managedCity = Beans.get(CityRepository.class).find(ids[1]);
    var centroid =
        Beans.get(BgCityCentroidService.class).getOrResolveCentroid(managedCity, bulgaria);

    assertTrue(centroid.isPresent());
    // In memory, right after resolving, the value is exactly the normalized one -- see
    // BgCoordinateNormalizerTest and BgAddressGeocodeServiceTest for that proof in isolation.
    BgGeocodeTestFixtures.assertCloseTo(new BigDecimal("42.987655"), managedCity.getLatit());

    JPA.clear();

    City reloaded = Beans.get(CityRepository.class).find(ids[1]);
    BgGeocodeTestFixtures.assertCloseTo(new BigDecimal("42.987655"), reloaded.getLatit());
    BgGeocodeTestFixtures.assertCloseTo(new BigDecimal("23.555556"), reloaded.getLongit());
  }
}

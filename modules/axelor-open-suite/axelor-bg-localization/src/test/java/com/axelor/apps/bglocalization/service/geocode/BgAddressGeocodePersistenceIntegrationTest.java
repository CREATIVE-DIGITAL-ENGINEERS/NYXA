package com.axelor.apps.bglocalization.service.geocode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.axelor.apps.aiocr.service.ocr.BgCityPostcodeService;
import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.AddressTemplate;
import com.axelor.apps.base.db.City;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.repo.AddressRepository;
import com.axelor.apps.base.db.repo.AddressTemplateRepository;
import com.axelor.apps.base.db.repo.CityRepository;
import com.axelor.apps.base.db.repo.CountryRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationTest;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.studio.db.AppBase;
import com.axelor.studio.db.repo.AppBaseRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * DB-backed (in-memory HSQLDB) proof of the one thing the pure/mocked unit tests cannot show: the
 * new Address (bgGeocodeAccuracySelect/bgGeocodeProvider/bgGeocodedOn + stock latit/longit) and
 * City (latit/longit centroid) columns actually round-trip through a real persistence context, and
 * that {@link BgAddressGeocodeService} recognizes a freshly-read Address as safely cached (no
 * network call) rather than only an in-memory one from the same test method. No live network call,
 * no live/shared DB -- this is an isolated in-memory schema (see src/test/resources/META-INF/
 * persistence.xml), created and torn down per test run.
 */
class BgAddressGeocodePersistenceIntegrationTest extends BgLocalizationTest {

  /**
   * Tolerant coordinate comparison. The stock {@code AddressTemplateServiceImpl} round-trips the
   * whole Address through a Commons-BeanUtils {@code Mapper}/{@code Context} conversion while
   * computing {@code formattedFullName} on save (unrelated to anything this test or
   * BgAddressGeocodeService does), which can narrow a BigDecimal's scale. What this test actually
   * needs to prove -- the persisted value survives a fresh read as (about) the same location, not
   * bit-for-bit precision -- doesn't need tighter than city-block tolerance.
   */
  private static void assertCloseTo(double expected, BigDecimal actual) {
    assertNotNull(actual);
    assertTrue(
        Math.abs(actual.doubleValue() - expected) < 0.01,
        () -> "expected ~" + expected + " but was " + actual);
  }

  @Test
  void addressAndCityGeocodeFields_persistAndAreReusedAfterAFreshRepositoryRead() {
    Long[] ids = new Long[2]; // [0]=city id, [1]=address id

    JPA.runInTransaction(
        () -> {
          // A fresh test schema has no App Config row at all -- a real installation always has
          // one (App Install seeds it). AddressBaseRepository.save() reads it (map provider
          // config) on every save, so without this the save itself NPEs, unrelated to what this
          // test is actually proving.
          AppBase appBase = new AppBase();
          appBase.setMapApiSelect(AppBaseRepository.MAP_API_OPEN_STREET_MAP);
          Beans.get(AppBaseRepository.class).save(appBase);

          // Mirrors the real (stock) "DEFAULT" AddressTemplate every Country needs before
          // AddressBaseRepository.save() can compute Address.formattedFullName -- a fresh test
          // schema has none, unlike a real installation.
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
          // No required-field lines: this fixture always fills every field anyway, and
          // checkRequiredAddressFields NPEs on a null (never-set) collection rather than an empty
          // one.
          template.setAddressTemplateLineList(new java.util.ArrayList<>());
          Beans.get(AddressTemplateRepository.class).save(template);

          Country bulgaria = new Country();
          bulgaria.setName("България");
          bulgaria.setAlpha2Code("BG");
          bulgaria.setAddressTemplate(template);
          Beans.get(CountryRepository.class).save(bulgaria);

          City sofia = new City();
          sofia.setName("София");
          sofia.setCountry(bulgaria);
          sofia.setLatit(new BigDecimal("42.697700"));
          sofia.setLongit(new BigDecimal("23.321900"));
          Beans.get(CityRepository.class).save(sofia);

          Address address = new Address();
          address.setCountry(bulgaria);
          address.setCity(sofia);
          address.setZip("1000");
          address.setStreetName("ул. Test 1");
          address.setLatit(BigDecimal.valueOf(42.6977));
          address.setLongit(BigDecimal.valueOf(23.3219));
          address.setIsValidLatLong(true);
          address.setBgGeocodeAccuracySelect(GeocodeAccuracy.EXACT.getSelectionValue());
          address.setBgGeocodeProvider("nominatim");
          address.setBgGeocodedOn(LocalDateTime.now());
          Beans.get(AddressRepository.class).save(address);

          ids[0] = sofia.getId();
          ids[1] = address.getId();
        });

    // Discard the persistence context used above -- what follows must come from the DB, not a
    // cached Java object still holding the values we just set.
    JPA.clear();

    City reloadedCity = Beans.get(CityRepository.class).find(ids[0]);
    assertNotNull(reloadedCity);
    assertNotNull(reloadedCity.getLatit());
    assertCloseTo(42.697700, reloadedCity.getLatit());
    assertCloseTo(23.321900, reloadedCity.getLongit());

    Address reloadedAddress = Beans.get(AddressRepository.class).find(ids[1]);
    assertNotNull(reloadedAddress);
    assertTrue(reloadedAddress.getIsValidLatLong());
    assertEquals(
        GeocodeAccuracy.EXACT.getSelectionValue(), reloadedAddress.getBgGeocodeAccuracySelect());
    assertEquals("nominatim", reloadedAddress.getBgGeocodeProvider());
    assertNotNull(reloadedAddress.getBgGeocodedOn());
    assertCloseTo(42.6977, reloadedAddress.getLatit());

    // Reuse check: resolving a freshly-DB-read Address must recognize the safe cached result and
    // make NO network call -- proves the cache check reads real persisted state, not test fixture
    // state still sitting in memory.
    NominatimHttpClient httpClient = mock(NominatimHttpClient.class);
    BgAddressGeocodeService service =
        new BgAddressGeocodeService(
            httpClient, mock(BgCityCentroidService.class), mock(BgCityPostcodeService.class));

    GeocodeResult result = service.resolve(reloadedAddress, false);

    assertEquals(GeocodeAccuracy.EXACT, result.getAccuracy());
    verify(httpClient, never()).search(any(), any());
  }
}

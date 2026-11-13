package com.axelor.apps.bglocalization.service.geocode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axelor.apps.aiocr.service.ocr.BgCityPostcodeService;
import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.City;
import com.axelor.apps.base.db.Country;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Orchestration-level test: reuse of a cached safe result, the full-address -> City-centroid
 * fallback chain, provider-failure handling, and manual refresh -- all against a mocked
 * NominatimHttpClient/BgCityCentroidService/BgCityPostcodeService. No live network call, no DB.
 */
class BgAddressGeocodeServiceTest {

  private static final String SOFIA_EXACT_MATCH_XML =
      "<searchresults><place lat=\"42.6977\" lon=\"23.3219\">"
          + "<house_number>1</house_number><road>ул. Test</road>"
          + "<city>София</city><postcode>1000</postcode>"
          + "<country_code>bg</country_code></place></searchresults>";

  private static final String ITALY_MISMATCH_XML =
      "<searchresults><place lat=\"44.9\" lon=\"7.68\">"
          + "<road>Via Roma</road><city>Carignano</city>"
          + "<country_code>it</country_code></place></searchresults>";

  private NominatimHttpClient httpClient;
  private BgCityCentroidService centroidService;
  private BgCityPostcodeService cityPostcodeService;
  private BgAddressGeocodeService service;
  private City sofia;
  private Country bulgaria;

  @BeforeEach
  void setUp() {
    httpClient = mock(NominatimHttpClient.class);
    centroidService = mock(BgCityCentroidService.class);
    cityPostcodeService = mock(BgCityPostcodeService.class);
    when(cityPostcodeService.getValidPostcodes(any())).thenReturn(Collections.emptyList());
    service = new BgAddressGeocodeService(httpClient, centroidService, cityPostcodeService);

    sofia = new City();
    sofia.setName("София");
    sofia.setNameEn("Sofia");
    bulgaria = new Country();
    bulgaria.setName("България");
    bulgaria.setAlpha2Code("BG");
  }

  private Address bgAddress() {
    Address address = new Address();
    address.setStreetName("ул. Test 1");
    address.setZip("1000");
    address.setCity(sofia);
    address.setCountry(bulgaria);
    return address;
  }

  @Test
  void cachedSafeResult_isReusedWithoutAnyNetworkCall() {
    Address address = bgAddress();
    address.setLatit(java.math.BigDecimal.valueOf(42.5));
    address.setLongit(java.math.BigDecimal.valueOf(23.1));
    address.setBgGeocodeAccuracySelect(GeocodeAccuracy.EXACT.getSelectionValue());

    GeocodeResult result = service.resolve(address, false);

    assertEquals(GeocodeAccuracy.EXACT, result.getAccuracy());
    verify(httpClient, never()).search(any(), any());
  }

  @Test
  void highPrecisionFullAddressMatch_isRoundedToSixDecimals() {
    // Same rounding case BgCoordinateNormalizerTest proves in isolation, exercised here through
    // the whole orchestration (parse -> validate -> normalize -> GeocodeResult), pure/mocked --
    // the DB-backed proof of the same rounding lives in BgAddressHighPrecisionPersistenceTest.
    String xml =
        "<searchresults><place lat=\"42.1234565\" lon=\"23.1234561\">"
            + "<house_number>1</house_number><road>ул. Test</road>"
            + "<city>София</city><country_code>bg</country_code></place></searchresults>";
    when(httpClient.search(anyString(), anyString())).thenReturn(Optional.of(xml));
    Address address = bgAddress();

    GeocodeResult result = service.resolve(address, false);

    assertEquals(GeocodeAccuracy.EXACT, result.getAccuracy());
    assertEquals(0, result.getLatitude().compareTo(new java.math.BigDecimal("42.123457")));
    assertEquals(0, result.getLongitude().compareTo(new java.math.BigDecimal("23.123456")));
  }

  @Test
  void validFullAddressMatch_isAcceptedDirectly_noCityFallbackNeeded() {
    when(httpClient.search(anyString(), anyString()))
        .thenReturn(Optional.of(SOFIA_EXACT_MATCH_XML));
    Address address = bgAddress();

    GeocodeResult result = service.resolve(address, false);

    assertEquals(GeocodeAccuracy.EXACT, result.getAccuracy());
    assertEquals("nominatim", address.getBgGeocodeProvider());
    verify(centroidService, never()).getOrResolveCentroid(any(), any());
  }

  @Test
  void rejectedFullAddressResult_fallsBackToCityCentroid() {
    when(httpClient.search(anyString(), anyString())).thenReturn(Optional.of(ITALY_MISMATCH_XML));
    when(centroidService.getOrResolveCentroid(sofia, bulgaria))
        .thenReturn(Optional.of(new double[] {42.6977, 23.3219}));
    Address address = bgAddress();

    GeocodeResult result = service.resolve(address, false);

    assertEquals(GeocodeAccuracy.CITY, result.getAccuracy());
    assertEquals("city-centroid", address.getBgGeocodeProvider());
    assertEquals(0, address.getLatit().compareTo(java.math.BigDecimal.valueOf(42.6977)));
  }

  @Test
  void providerFailure_fallsBackSafelyToCityCentroid() {
    when(httpClient.search(anyString(), anyString())).thenReturn(Optional.empty());
    when(centroidService.getOrResolveCentroid(sofia, bulgaria))
        .thenReturn(Optional.of(new double[] {42.6977, 23.3219}));
    Address address = bgAddress();

    GeocodeResult result = service.resolve(address, false);

    assertEquals(GeocodeAccuracy.CITY, result.getAccuracy());
  }

  @Test
  void noSafeResultAnywhere_returnsErrorAndStoresNoCoordinates() {
    when(httpClient.search(anyString(), anyString())).thenReturn(Optional.empty());
    when(centroidService.getOrResolveCentroid(sofia, bulgaria)).thenReturn(Optional.empty());
    Address address = bgAddress();

    GeocodeResult result = service.resolve(address, false);

    assertEquals(GeocodeAccuracy.NONE, result.getAccuracy());
    assertFalse(result.isSafe());
    assertNull(address.getLatit());
    assertNull(address.getLongit());
    assertEquals(GeocodeAccuracy.NONE.getSelectionValue(), address.getBgGeocodeAccuracySelect());
  }

  @Test
  void manualRefresh_bypassesCacheAndResolvesAgain() {
    Address address = bgAddress();
    // Stale CITY-tier cache from a previous resolution.
    address.setLatit(java.math.BigDecimal.valueOf(42.6977));
    address.setLongit(java.math.BigDecimal.valueOf(23.3219));
    address.setBgGeocodeAccuracySelect(GeocodeAccuracy.CITY.getSelectionValue());

    // This time a real full-address match is available.
    when(httpClient.search(anyString(), anyString()))
        .thenReturn(Optional.of(SOFIA_EXACT_MATCH_XML));

    GeocodeResult result = service.resolve(address, true);

    assertEquals(GeocodeAccuracy.EXACT, result.getAccuracy());
    verify(httpClient).search(anyString(), anyString());
  }

  @Test
  void missingCity_resolvesToNone_withoutCallingTheNetwork() {
    Address address = new Address();
    address.setCountry(bulgaria);

    GeocodeResult result = service.resolve(address, false);

    assertEquals(GeocodeAccuracy.NONE, result.getAccuracy());
    verify(httpClient, never()).search(any(), any());
  }
}

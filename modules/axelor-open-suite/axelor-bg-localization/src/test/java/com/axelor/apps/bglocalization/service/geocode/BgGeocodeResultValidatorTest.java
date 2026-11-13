package com.axelor.apps.bglocalization.service.geocode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.City;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Core safety net: no Nominatim result is ever accepted blindly. */
class BgGeocodeResultValidatorTest {

  private NominatimPlace place(
      double lat,
      double lon,
      String countryCode,
      String settlement,
      String postcode,
      boolean houseNumber,
      boolean road) {
    return new NominatimPlace(
        BigDecimal.valueOf(lat),
        BigDecimal.valueOf(lon),
        countryCode,
        settlement,
        postcode,
        houseNumber,
        road);
  }

  @Test
  void validBulgarianHouseNumberMatch_isAcceptedAsExact() {
    NominatimPlace sofia = place(42.65, 23.40, "bg", "София", "1000", true, true);
    GeocodeAccuracy result =
        BgGeocodeResultValidator.validate(
            sofia, "bg", "София", "Sofia", "1000", Collections.emptyList(), null);
    assertEquals(GeocodeAccuracy.EXACT, result);
  }

  @Test
  void validBulgarianStreetLevelMatch_withoutHouseNumber_isAcceptedAsStreet() {
    NominatimPlace sofia = place(42.65, 23.40, "bg", "София", null, false, true);
    GeocodeAccuracy result =
        BgGeocodeResultValidator.validate(
            sofia, "bg", "София", "Sofia", "1000", Collections.emptyList(), null);
    assertEquals(GeocodeAccuracy.STREET, result);
  }

  @Test
  void italianResult_isRejectedOnCountryMismatch_theCarignanoCase() {
    NominatimPlace carignano = place(44.9, 7.68, "it", "Carignano", "10041", false, true);
    GeocodeAccuracy result =
        BgGeocodeResultValidator.validate(
            carignano, "bg", "София", "Sofia", "1000", Collections.emptyList(), null);
    assertEquals(GeocodeAccuracy.NONE, result);
  }

  @Test
  void wrongBulgarianCity_isRejected() {
    // Same country, but the result landed in a different settlement than the one the user selected.
    NominatimPlace plovdiv = place(42.17, 24.74, "bg", "Пловдив", "4000", true, true);
    GeocodeAccuracy result =
        BgGeocodeResultValidator.validate(
            plovdiv, "bg", "София", "Sofia", "1000", Collections.emptyList(), null);
    assertEquals(GeocodeAccuracy.NONE, result);
  }

  @Test
  void incompatiblePostcode_isRejected_whenNotInAllowedListEither() {
    NominatimPlace sofia = place(42.65, 23.40, "bg", "София", "9999", true, true);
    GeocodeAccuracy result =
        BgGeocodeResultValidator.validate(
            sofia, "bg", "София", "Sofia", "1000", List.of("1000", "1111"), null);
    assertEquals(GeocodeAccuracy.NONE, result);
  }

  @Test
  void resultPostcode_acceptedWhenInCityAllowedList_evenIfNotTheAddressOwnZip() {
    // Sofia-style multi-postcode city: the geocoder found a different (but still valid) district
    // postcode than the one on the Address -- BgCityPostcodeService's allowed set covers this.
    NominatimPlace sofia = place(42.65, 23.40, "bg", "София", "1111", true, true);
    GeocodeAccuracy result =
        BgGeocodeResultValidator.validate(
            sofia, "bg", "София", "Sofia", "1000", List.of("1000", "1111"), null);
    assertEquals(GeocodeAccuracy.EXACT, result);
  }

  @Test
  void implausiblyFarFromTrustedCityCentroid_isRejected_evenIfNameAndCountryMatch() {
    // Right country, right-sounding city label, but 500km from Sofia's own known centroid.
    NominatimPlace suspicious = place(46.5, 23.40, "bg", "София", null, true, true);
    double[] sofiaCentroid = {42.6977, 23.3219};
    GeocodeAccuracy result =
        BgGeocodeResultValidator.validate(
            suspicious, "bg", "София", "Sofia", null, Collections.emptyList(), sofiaCentroid);
    assertEquals(GeocodeAccuracy.NONE, result);
  }

  @Test
  void withinDistanceOfTrustedCentroid_isAccepted() {
    NominatimPlace nearby = place(42.70, 23.33, "bg", "София", null, true, true);
    double[] sofiaCentroid = {42.6977, 23.3219};
    GeocodeAccuracy result =
        BgGeocodeResultValidator.validate(
            nearby, "bg", "София", "Sofia", null, Collections.emptyList(), sofiaCentroid);
    assertEquals(GeocodeAccuracy.EXACT, result);
  }

  @Test
  void neitherHouseNumberNorRoad_isRejected_notTrustedAtAnyTier() {
    NominatimPlace justACity = place(42.65, 23.40, "bg", "София", null, false, false);
    GeocodeAccuracy result =
        BgGeocodeResultValidator.validate(
            justACity, "bg", "София", "Sofia", null, Collections.emptyList(), null);
    assertEquals(GeocodeAccuracy.NONE, result);
  }

  private City city(String name, String nameEn, String region, String municipality, String zip) {
    City city = new City();
    city.setName(name);
    city.setNameEn(nameEn);
    city.setRegion(region);
    city.setMunicipality(municipality);
    city.setZip(zip);
    return city;
  }

  private NominatimPlace placeWithArea(
      double lat,
      double lon,
      String countryCode,
      String settlement,
      String postcode,
      String county,
      String stateDistrict,
      String state) {
    return new NominatimPlace(
        BigDecimal.valueOf(lat),
        BigDecimal.valueOf(lon),
        countryCode,
        settlement,
        postcode,
        false,
        false,
        county,
        stateDistrict,
        state);
  }

  @Test
  void cityCentroidQuery_acceptsPlainBoundaryResultWithNoSettlementTag() {
    NominatimPlace boundary = place(42.6977, 23.3219, "bg", null, null, false, false);
    City sofia = city("София", "Sofia", "София (столица)", "Столична", "1000");
    assertTrue(
        BgGeocodeResultValidator.isSafeCityCentroid(
            boundary, "bg", sofia, Collections.emptyList()));
  }

  @Test
  void cityCentroidQuery_rejectsWrongCountry() {
    NominatimPlace boundary = place(42.6977, 23.3219, "it", null, null, false, false);
    City sofia = city("София", "Sofia", "София (столица)", "Столична", "1000");
    assertFalse(
        BgGeocodeResultValidator.isSafeCityCentroid(
            boundary, "bg", sofia, Collections.emptyList()));
  }

  @Test
  void cityCentroidQuery_rejectsDifferentNamesakeSettlement_disambiguatedByMunicipality() {
    // Two different real BG settlements both named "Александрово" -- same country, same-looking
    // name only if you ignore where they actually are. The result's own municipality/region label
    // does not match the selected City's, so it must be rejected rather than cached.
    NominatimPlace wrongAleksandrovo =
        placeWithArea(43.6, 24.6, "bg", "Александрово", "5140", "Ловеч", "Ловеч", null);
    City targetAleksandrovo = city("Александрово", "Aleksandrovo", "Хасково", "Хасково", "6430");

    assertFalse(
        BgGeocodeResultValidator.isSafeCityCentroid(
            wrongAleksandrovo, "bg", targetAleksandrovo, Collections.emptyList()));
  }

  @Test
  void cityCentroidQuery_acceptsSameNamesakeSettlement_whenMunicipalityMatches() {
    NominatimPlace correctAleksandrovo =
        placeWithArea(41.99, 25.55, "bg", "Александрово", "6430", "Хасково", "Хасково", null);
    City targetAleksandrovo = city("Александрово", "Aleksandrovo", "Хасково", "Хасково", "6430");

    assertTrue(
        BgGeocodeResultValidator.isSafeCityCentroid(
            correctAleksandrovo, "bg", targetAleksandrovo, Collections.emptyList()));
  }

  @Test
  void cityCentroidQuery_rejectsWhenPostcodeDisagreesAndIsNotInAllowedList() {
    // Same settlement name AND same municipality (so the region check alone would pass) -- only
    // the postcode disagrees and isn't in the City's allowed set either.
    NominatimPlace wrongPostcode =
        placeWithArea(42.6977, 23.3219, "bg", "София", "9999", "Столична", null, null);
    City sofia = city("София", "Sofia", "София (столица)", "Столична", "1000");

    assertFalse(
        BgGeocodeResultValidator.isSafeCityCentroid(
            wrongPostcode, "bg", sofia, List.of("1000", "1111")));
  }

  @Test
  void cityCentroidQuery_acceptsWhenNoRegionInfoAvailableOnEitherSide_cannotDisprove() {
    NominatimPlace noAreaInfo =
        placeWithArea(42.15, 24.75, "bg", "Пловдив", null, null, null, null);
    City plovdiv = city("Пловдив", "Plovdiv", null, null, "4000");

    assertTrue(
        BgGeocodeResultValidator.isSafeCityCentroid(
            noAreaInfo, "bg", plovdiv, Collections.emptyList()));
  }

  @Test
  void regionOrMunicipalityCompatible_looseNormalizedSubstringMatch() {
    // Nominatim's "state" label ("София-град") is not spelled the same as City.region ("София"),
    // but one contains the other once normalized -- close enough to not be a disproof.
    NominatimPlace place = placeWithArea(42.7, 23.3, "bg", "София", null, null, null, "София-град");
    City sofia = city("София", "Sofia", "София", "Столична", "1000");
    assertTrue(BgGeocodeResultValidator.regionOrMunicipalityCompatible(place, sofia));
  }

  @Test
  void regionOrMunicipalityCompatible_rejectsClearMismatch() {
    NominatimPlace place =
        placeWithArea(43.6, 24.6, "bg", "Александрово", null, "Ловеч", null, null);
    City targetInHaskovo = city("Александрово", "Aleksandrovo", "Хасково", "Хасково", "6430");
    assertFalse(BgGeocodeResultValidator.regionOrMunicipalityCompatible(place, targetInHaskovo));
  }
}

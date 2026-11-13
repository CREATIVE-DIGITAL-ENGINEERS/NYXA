package com.axelor.apps.bglocalization.service.geocode;

import com.axelor.apps.base.db.City;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether a Nominatim result is safe to trust, and at what {@link GeocodeAccuracy} tier.
 * Never accepts a first result blindly: country must match, the settlement name must match the
 * selected City (normalized BG/Latin), and if both sides carry a postcode it must agree with either
 * the Address's own postcode or the City's known allowed set. A City centroid, when already known,
 * additionally caps how far away an "exact"/"street" result is allowed to land -- this is what
 * would have caught the Carignano, Italy mismatch even if every text-based check above had passed.
 * A rejected result must never be returned as accepted, so the caller can never cache it.
 */
public final class BgGeocodeResultValidator {

  /** Reject anything farther than this from a known City centroid, regardless of other checks. */
  private static final double MAX_DISTANCE_FROM_CITY_KM = 50.0;

  private static final double EARTH_RADIUS_KM = 6371.0;

  private BgGeocodeResultValidator() {}

  /**
   * @param place the parsed Nominatim result to judge.
   * @param expectedCountryAlpha2 e.g. "bg", the Address's own country.
   * @param expectedCityName the selected City's (Cyrillic) name.
   * @param expectedCityNameEn the selected City's English name, or null/blank if none.
   * @param addressPostcode the Address's own zip, or null/blank if none.
   * @param allowedCityPostcodes exact valid postcodes for the City (may be empty = no restriction),
   *     from {@code BgCityPostcodeService.getValidPostcodes} -- reused, not reimplemented.
   * @param trustedCityCentroid the City's own cached centroid, or null if not yet known.
   * @return the accepted tier (EXACT or STREET), or {@link GeocodeAccuracy#NONE} if rejected.
   */
  public static GeocodeAccuracy validate(
      NominatimPlace place,
      String expectedCountryAlpha2,
      String expectedCityName,
      String expectedCityNameEn,
      String addressPostcode,
      List<String> allowedCityPostcodes,
      double[] trustedCityCentroid) {

    if (place == null) {
      return GeocodeAccuracy.NONE;
    }

    if (!countryMatches(place.getCountryCode(), expectedCountryAlpha2)) {
      return GeocodeAccuracy.NONE;
    }

    if (!settlementMatches(place.getSettlementName(), expectedCityName, expectedCityNameEn)) {
      return GeocodeAccuracy.NONE;
    }

    if (!postcodeCompatible(place.getPostcode(), addressPostcode, allowedCityPostcodes)) {
      return GeocodeAccuracy.NONE;
    }

    if (trustedCityCentroid != null
        && distanceKm(
                place.getLatitude(),
                place.getLongitude(),
                trustedCityCentroid[0],
                trustedCityCentroid[1])
            > MAX_DISTANCE_FROM_CITY_KM) {
      return GeocodeAccuracy.NONE;
    }

    return place.hasHouseNumber()
        ? GeocodeAccuracy.EXACT
        : (place.hasRoad() ? GeocodeAccuracy.STREET : GeocodeAccuracy.NONE);
  }

  /**
   * Validates a City-only (centroid) result. Country alone is NOT enough: Bulgaria has many
   * same-named settlements in different municipalities (e.g. "Александрово" exists 8 times), so a
   * plain "City, Country" query can come back for the WRONG namesake. This also checks, wherever
   * Nominatim and the City both actually carry the information: the settlement name itself
   * (normalized BG/Latin), the region/municipality (loosely, since Nominatim's own administrative
   * labels are not a clean 1:1 with BG's oblast/obshtina split), and the postcode (against the
   * City's own default zip or its known allowed set). A mismatch on any signal that IS available on
   * both sides is rejected -- never cached as the City's centroid.
   */
  public static boolean isSafeCityCentroid(
      NominatimPlace place,
      String expectedCountryAlpha2,
      City city,
      List<String> allowedCityPostcodes) {
    if (place == null || city == null) {
      return false;
    }
    if (!countryMatches(place.getCountryCode(), expectedCountryAlpha2)) {
      return false;
    }
    String settlement = place.getSettlementName();
    // A pure "City, Country" query commonly resolves to an administrative boundary with no
    // city/town/village address-detail tag of its own -- absence here is not itself a mismatch,
    // but every other available signal is still checked below.
    if (settlement != null
        && !settlement.trim().isEmpty()
        && !settlementMatches(settlement, city.getName(), city.getNameEn())) {
      return false;
    }
    if (!regionOrMunicipalityCompatible(place, city)) {
      return false;
    }
    return postcodeCompatible(place.getPostcode(), city.getZip(), allowedCityPostcodes);
  }

  /**
   * True unless BOTH sides actually carry region/municipality information AND none of it lines up
   * -- this is what tells two identically-named BG settlements apart. Deliberately lenient
   * (normalized substring, either direction) because Nominatim's county/state_district/state labels
   * are not guaranteed to spell a BG oblast/obshtina name the same way
   * City.region/City.municipality does; the goal is to catch a clear mismatch, not to demand an
   * exact administrative match.
   */
  static boolean regionOrMunicipalityCompatible(NominatimPlace place, City city) {
    List<String> placeAreas =
        nonBlank(place.getCounty(), place.getStateDistrict(), place.getState());
    List<String> cityAreas = nonBlank(city.getRegion(), city.getMunicipality());
    if (placeAreas.isEmpty() || cityAreas.isEmpty()) {
      return true; // Nothing to cross-check on one side -- can't disprove a match.
    }
    for (String placeArea : placeAreas) {
      String normalizedPlaceArea = normalize(placeArea);
      for (String cityArea : cityAreas) {
        String normalizedCityArea = normalize(cityArea);
        if (normalizedPlaceArea.contains(normalizedCityArea)
            || normalizedCityArea.contains(normalizedPlaceArea)) {
          return true;
        }
      }
    }
    return false;
  }

  private static List<String> nonBlank(String... values) {
    List<String> result = new java.util.ArrayList<>();
    for (String v : values) {
      if (v != null && !v.trim().isEmpty()) {
        result.add(v);
      }
    }
    return result;
  }

  private static boolean countryMatches(String actualCountryCode, String expectedAlpha2) {
    if (expectedAlpha2 == null || expectedAlpha2.trim().isEmpty()) {
      return false;
    }
    return expectedAlpha2.trim().equalsIgnoreCase(normalize(actualCountryCode));
  }

  private static boolean settlementMatches(
      String actual, String expectedCyrillic, String expectedEn) {
    String normalizedActual = normalize(actual);
    if (normalizedActual.isEmpty()) {
      return false;
    }
    if (normalizedActual.equals(normalize(expectedCyrillic))) {
      return true;
    }
    return expectedEn != null && normalizedActual.equals(normalize(expectedEn));
  }

  private static boolean postcodeCompatible(
      String resultPostcode, String addressPostcode, List<String> allowedCityPostcodes) {
    if (resultPostcode == null || resultPostcode.trim().isEmpty()) {
      return true; // Nothing to cross-check on the result side.
    }
    if (addressPostcode == null || addressPostcode.trim().isEmpty()) {
      return true; // Nothing to cross-check on the input side.
    }
    String result = resultPostcode.trim();
    if (result.equals(addressPostcode.trim())) {
      return true;
    }
    return allowedCityPostcodes != null && allowedCityPostcodes.contains(result);
  }

  private static String normalize(String s) {
    return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
  }

  /** Great-circle distance in kilometers. Package-visible for direct unit testing. */
  static double distanceKm(BigDecimal lat1, BigDecimal lon1, double lat2, double lon2) {
    if (lat1 == null || lon1 == null) {
      return Double.MAX_VALUE;
    }
    double phi1 = Math.toRadians(lat1.doubleValue());
    double phi2 = Math.toRadians(lat2);
    double deltaPhi = Math.toRadians(lat2 - lat1.doubleValue());
    double deltaLambda = Math.toRadians(lon2 - lon1.doubleValue());
    double a =
        Math.sin(deltaPhi / 2) * Math.sin(deltaPhi / 2)
            + Math.cos(phi1)
                * Math.cos(phi2)
                * Math.sin(deltaLambda / 2)
                * Math.sin(deltaLambda / 2);
    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return EARTH_RADIUS_KM * c;
  }
}

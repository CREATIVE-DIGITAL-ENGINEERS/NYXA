package com.axelor.apps.bglocalization.service.geocode;

import java.math.BigDecimal;

/**
 * The handful of fields actually needed out of one Nominatim {@code <place>} result -- never the
 * raw response payload (see BgAddressGeocodeService, which persists none of this).
 */
public final class NominatimPlace {

  private final BigDecimal latitude;
  private final BigDecimal longitude;
  private final String countryCode;
  private final String settlementName;
  private final String postcode;
  private final boolean hasHouseNumber;
  private final boolean hasRoad;
  // Nominatim's loose administrative-area labels -- not a clean 1:1 with BG's own oblast/obshtina
  // split, but the only signal available to tell two same-named BG settlements apart (e.g. the 8
  // different "Александрово"s). See BgGeocodeResultValidator.regionOrMunicipalityCompatible.
  private final String county;
  private final String stateDistrict;
  private final String state;

  public NominatimPlace(
      BigDecimal latitude,
      BigDecimal longitude,
      String countryCode,
      String settlementName,
      String postcode,
      boolean hasHouseNumber,
      boolean hasRoad,
      String county,
      String stateDistrict,
      String state) {
    this.latitude = latitude;
    this.longitude = longitude;
    this.countryCode = countryCode;
    this.settlementName = settlementName;
    this.postcode = postcode;
    this.hasHouseNumber = hasHouseNumber;
    this.hasRoad = hasRoad;
    this.county = county;
    this.stateDistrict = stateDistrict;
    this.state = state;
  }

  /** Convenience for callers that don't need the region/municipality disambiguation fields. */
  public NominatimPlace(
      BigDecimal latitude,
      BigDecimal longitude,
      String countryCode,
      String settlementName,
      String postcode,
      boolean hasHouseNumber,
      boolean hasRoad) {
    this(
        latitude,
        longitude,
        countryCode,
        settlementName,
        postcode,
        hasHouseNumber,
        hasRoad,
        null,
        null,
        null);
  }

  public BigDecimal getLatitude() {
    return latitude;
  }

  public BigDecimal getLongitude() {
    return longitude;
  }

  public String getCountryCode() {
    return countryCode;
  }

  public String getSettlementName() {
    return settlementName;
  }

  public String getPostcode() {
    return postcode;
  }

  public boolean hasHouseNumber() {
    return hasHouseNumber;
  }

  public boolean hasRoad() {
    return hasRoad;
  }

  public String getCounty() {
    return county;
  }

  public String getStateDistrict() {
    return stateDistrict;
  }

  public String getState() {
    return state;
  }
}

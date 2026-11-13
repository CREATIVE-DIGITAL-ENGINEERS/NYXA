package com.axelor.apps.bglocalization.service.geocode;

import java.math.BigDecimal;

/** Outcome of {@code BgAddressGeocodeService.resolve} -- what to show, and how much to trust it. */
public final class GeocodeResult {

  private final GeocodeAccuracy accuracy;
  private final BigDecimal latitude;
  private final BigDecimal longitude;
  private final String message;

  private GeocodeResult(
      GeocodeAccuracy accuracy, BigDecimal latitude, BigDecimal longitude, String message) {
    this.accuracy = accuracy;
    this.latitude = latitude;
    this.longitude = longitude;
    this.message = message;
  }

  public static GeocodeResult of(
      GeocodeAccuracy accuracy, BigDecimal latitude, BigDecimal longitude) {
    return new GeocodeResult(accuracy, latitude, longitude, null);
  }

  public static GeocodeResult none(String message) {
    return new GeocodeResult(GeocodeAccuracy.NONE, null, null, message);
  }

  public GeocodeAccuracy getAccuracy() {
    return accuracy;
  }

  public BigDecimal getLatitude() {
    return latitude;
  }

  public BigDecimal getLongitude() {
    return longitude;
  }

  public boolean isSafe() {
    return accuracy != GeocodeAccuracy.NONE && latitude != null && longitude != null;
  }

  public String getMessage() {
    return message;
  }
}

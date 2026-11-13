package com.axelor.apps.bglocalization.service.geocode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * The one place a raw coordinate from Nominatim is made safe to persist, used identically for both
 * entities that store one: {@code Address.latit/longit} (this module extends nothing there -- stock
 * precision="38" scale="18") and {@code City.latit/longit} (this module's OWN additive columns,
 * deliberately precision="9" scale="6" -- see {@code City.xml}). Nominatim's XML routinely carries
 * more than 6 fractional digits, which Bean Validation rejects outright against City's column
 * ("numeric value out of bounds") -- not truncated, not rounded, just refused at flush time.
 *
 * <p>Rounds to {@link #SCALE} (6) even for Address, whose column could hold far more: one shared
 * rule for both entities is simpler than two, and 6 fractional degrees is already sub-30cm
 * precision at Bulgaria's latitude -- more than a map pin needs. Widening City's column instead
 * would only move the real question (how much external precision is meaningful) into schema without
 * answering it, and every caller would still have to guard against a provider one day returning
 * even more digits.
 */
public final class BgCoordinateNormalizer {

  static final int SCALE = 6;

  private static final BigDecimal MAX_LATITUDE = BigDecimal.valueOf(90);
  private static final BigDecimal MAX_LONGITUDE = BigDecimal.valueOf(180);

  private BgCoordinateNormalizer() {}

  /** Rounds and range-checks a latitude ({@code -90..90}); empty if unsafe to store. */
  public static Optional<BigDecimal> normalizeLatitude(BigDecimal raw) {
    return normalize(raw, MAX_LATITUDE);
  }

  /** Rounds and range-checks a longitude ({@code -180..180}); empty if unsafe to store. */
  public static Optional<BigDecimal> normalizeLongitude(BigDecimal raw) {
    return normalize(raw, MAX_LONGITUDE);
  }

  private static Optional<BigDecimal> normalize(BigDecimal raw, BigDecimal bound) {
    if (raw == null) {
      return Optional.empty();
    }
    double asDouble = raw.doubleValue();
    if (Double.isNaN(asDouble) || Double.isInfinite(asDouble)) {
      return Optional.empty();
    }
    BigDecimal rounded = raw.setScale(SCALE, RoundingMode.HALF_UP);
    if (rounded.abs().compareTo(bound) > 0) {
      return Optional.empty();
    }
    return Optional.of(rounded);
  }
}

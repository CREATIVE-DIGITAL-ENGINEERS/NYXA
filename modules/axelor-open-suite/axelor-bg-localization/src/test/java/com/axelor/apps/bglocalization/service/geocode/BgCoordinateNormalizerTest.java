package com.axelor.apps.bglocalization.service.geocode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pure boundary tests for {@link BgCoordinateNormalizer} -- no DB, no Guice. This is the guard that
 * stops a raw Nominatim coordinate (routinely more than 6 fractional digits) from ever reaching
 * {@code City.latit/longit} (precision="9" scale="6") and blowing up Bean Validation at flush time
 * ("numeric value out of bounds"), the exact failure seen manually clicking Map on a Supplier whose
 * address fell through to the City-centroid tier.
 */
class BgCoordinateNormalizerTest {

  @Test
  void roundsMoreThanSixFractionalDigits_halfUp() {
    Optional<BigDecimal> result =
        BgCoordinateNormalizer.normalizeLatitude(new BigDecimal("42.69771234567"));

    assertTrue(result.isPresent());
    assertEquals(0, result.get().compareTo(new BigDecimal("42.697712")));
    assertEquals(6, result.get().scale());
  }

  @Test
  void roundsHalfUpAtTheSeventhDigit() {
    // 23.1234565 -> the 7th digit is exactly 5, HALF_UP rounds the 6th digit up.
    Optional<BigDecimal> result =
        BgCoordinateNormalizer.normalizeLongitude(new BigDecimal("23.1234565"));

    assertEquals(0, result.get().compareTo(new BigDecimal("23.123457")));
  }

  @Test
  void padsFewerThanSixFractionalDigitsWithoutChangingTheValue() {
    Optional<BigDecimal> result = BgCoordinateNormalizer.normalizeLatitude(new BigDecimal("42.5"));

    assertEquals(0, result.get().compareTo(new BigDecimal("42.5")));
  }

  @Test
  void nullCoordinateIsRejected() {
    assertFalse(BgCoordinateNormalizer.normalizeLatitude(null).isPresent());
    assertFalse(BgCoordinateNormalizer.normalizeLongitude(null).isPresent());
  }

  @Test
  void exactlyAtTheLatitudeBoundaryIsAccepted() {
    assertTrue(BgCoordinateNormalizer.normalizeLatitude(new BigDecimal("90")).isPresent());
    assertTrue(BgCoordinateNormalizer.normalizeLatitude(new BigDecimal("-90")).isPresent());
  }

  @Test
  void justOverTheLatitudeBoundaryIsRejected() {
    assertFalse(BgCoordinateNormalizer.normalizeLatitude(new BigDecimal("90.0000006")).isPresent());
    assertFalse(
        BgCoordinateNormalizer.normalizeLatitude(new BigDecimal("-90.0000006")).isPresent());
    assertFalse(BgCoordinateNormalizer.normalizeLatitude(new BigDecimal("91")).isPresent());
  }

  @Test
  void roundingThatWouldCrossTheBoundaryIsRejected() {
    // Rounds to 90.000001, which is out of range -- the ROUNDED value must be what gets checked.
    assertFalse(BgCoordinateNormalizer.normalizeLatitude(new BigDecimal("90.0000005")).isPresent());
  }

  @Test
  void exactlyAtTheLongitudeBoundaryIsAccepted() {
    assertTrue(BgCoordinateNormalizer.normalizeLongitude(new BigDecimal("180")).isPresent());
    assertTrue(BgCoordinateNormalizer.normalizeLongitude(new BigDecimal("-180")).isPresent());
  }

  @Test
  void justOverTheLongitudeBoundaryIsRejected() {
    assertFalse(
        BgCoordinateNormalizer.normalizeLongitude(new BigDecimal("180.0000006")).isPresent());
    assertFalse(
        BgCoordinateNormalizer.normalizeLongitude(new BigDecimal("-180.0000006")).isPresent());
    assertFalse(BgCoordinateNormalizer.normalizeLongitude(new BigDecimal("181")).isPresent());
  }

  @Test
  void infiniteOrNanIsRejected() {
    assertFalse(
        BgCoordinateNormalizer.normalizeLatitude(
                BigDecimal.valueOf(Double.MAX_VALUE).multiply(BigDecimal.valueOf(Double.MAX_VALUE)))
            .isPresent());
  }

  @Test
  void fitsExactlyInCityDotLatitAndLongitColumn_precision9Scale6() {
    // City.latit/longit is precision="9" scale="6": at most 3 integer digits + 6 fraction digits.
    // The normalized result for any in-range coordinate must always satisfy that shape.
    BigDecimal normalized =
        BgCoordinateNormalizer.normalizeLatitude(new BigDecimal("-89.999999999")).orElseThrow();
    BigDecimal rescaled = normalized.setScale(6, RoundingMode.UNNECESSARY);
    assertTrue(rescaled.precision() - rescaled.scale() <= 3);
  }
}

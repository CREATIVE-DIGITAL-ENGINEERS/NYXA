package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Pure unit test (no DB) for the importer's row parsing and idempotency key. Covers "duplicate CSV
 * rows do not create duplicates" and "postcode stays String / leading-zero safe" without needing
 * HSQLDB (BgCityPostcodeImportService itself is DB-backed and covered by manual/integration testing
 * per the project's "no integrationTest run" rule).
 */
class BgCityPostcodeImportServiceTest {

  @Test
  void parsesEkatteAndPostcodeColumns() {
    assertArrayEquals(
        new String[] {"68134", "1000"}, BgCityPostcodeImportService.parseRow("68134,1000"));
  }

  @Test
  void trimsWhitespaceAroundColumns() {
    assertArrayEquals(
        new String[] {"68134", "1000"}, BgCityPostcodeImportService.parseRow(" 68134 , 1000 "));
  }

  @Test
  void leadingZeroPostcodeStaysString() {
    assertArrayEquals(
        new String[] {"12345", "0700"}, BgCityPostcodeImportService.parseRow("12345,0700"));
  }

  @Test
  void blankOrMalformedLinesAreSkipped() {
    assertNull(BgCityPostcodeImportService.parseRow(""));
    assertNull(BgCityPostcodeImportService.parseRow("   "));
    assertNull(BgCityPostcodeImportService.parseRow("68134"));
    assertNull(BgCityPostcodeImportService.parseRow("68134,"));
    assertNull(BgCityPostcodeImportService.parseRow(",1000"));
  }

  @Test
  void dedupKeyIsSameForSameCityAndPostcode_differentOtherwise() {
    String key1 = BgCityPostcodeImportService.dedupKey(1L, "1000");
    String key2 = BgCityPostcodeImportService.dedupKey(1L, "1000");
    assertNotEquals(key1, BgCityPostcodeImportService.dedupKey(1L, "1001"));
    assertNotEquals(key1, BgCityPostcodeImportService.dedupKey(2L, "1000"));
    assertArrayEquals(key1.split("\\|"), key2.split("\\|"));
  }
}

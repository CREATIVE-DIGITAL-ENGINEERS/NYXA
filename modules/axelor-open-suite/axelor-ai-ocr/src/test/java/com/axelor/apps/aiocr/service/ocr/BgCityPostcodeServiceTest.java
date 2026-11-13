package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure decision-matrix test for {@link BgCityPostcodeService} (no DB): city with one postcode ->
 * readonly / only that exact value accepted; multiple postcodes -> any exact match accepted, others
 * rejected; no validation rows at all -> unrestricted (existing behavior preserved).
 */
class BgCityPostcodeServiceTest {

  @Test
  void oneValidPostcode_isReadonlyAndOnlyExactValueAccepted() {
    List<String> postcodes = List.of("1000");
    assertTrue(BgCityPostcodeService.isSinglePostcode(postcodes));
    assertTrue(BgCityPostcodeService.isZipValid(postcodes, "1000"));
    assertFalse(BgCityPostcodeService.isZipValid(postcodes, "1001"));
  }

  @Test
  void multipleValidPostcodes_editableAndExactSetEnforced() {
    List<String> postcodes = List.of("1359", "1373", "1404");
    assertFalse(BgCityPostcodeService.isSinglePostcode(postcodes));
    assertTrue(BgCityPostcodeService.isZipValid(postcodes, "1373"));
    assertFalse(BgCityPostcodeService.isZipValid(postcodes, "1999"));
  }

  @Test
  void noValidationData_isUnrestricted() {
    List<String> postcodes = Collections.emptyList();
    assertFalse(BgCityPostcodeService.isSinglePostcode(postcodes));
    assertTrue(BgCityPostcodeService.isZipValid(postcodes, "anything"));
  }

  @Test
  void postcodeComparisonIsExactStringNotNumeric_leadingZeroSafe() {
    List<String> postcodes = List.of("0700");
    assertTrue(BgCityPostcodeService.isZipValid(postcodes, "0700"));
    assertFalse(BgCityPostcodeService.isZipValid(postcodes, "700"));
  }
}

package com.axelor.apps.bglocalization.service.geocode;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Real production street-text samples (from the affected Purchases -> Suppliers addresses) proving
 * entrance/floor/apartment are stripped while block/neighborhood/house-number survive.
 */
class BgAddressTextSanitizerTest {

  @Test
  void stripsEntranceFloorAndApartment_keepsBlockAndNeighborhood() {
    assertEquals(
        "ж.к. Дружба 2, бл. 419",
        BgAddressTextSanitizer.stripUnitDetails("ж.к. Дружба 2, бл. 419, вх. Б, ет. 8, ап. 54"));
  }

  @Test
  void stripsDecimalApartmentNumber() {
    assertEquals(
        "жк. Дружба, бл.281А",
        BgAddressTextSanitizer.stripUnitDetails("жк. Дружба, бл.281А, вх.А, ет.3, ап.3.1"));
  }

  @Test
  void stripsWithoutSeparatorPunctuation() {
    assertEquals(
        "ЖК РАЗСАДНИКА бл.25",
        BgAddressTextSanitizer.stripUnitDetails("ЖК РАЗСАДНИКА бл.25 вх.Д ет.2 ап.95"));
  }

  @Test
  void keepsStreetAndHouseNumber_whenNoUnitDetailsPresent() {
    assertEquals(
        "БУЛ.ВАСИЛ ЛЕВСКИ 170", BgAddressTextSanitizer.stripUnitDetails("БУЛ.ВАСИЛ ЛЕВСКИ 170"));
  }

  @Test
  void stripsFloorAtEndOfString() {
    assertEquals(
        "ул. \"Лучезар Станчев\" №3",
        BgAddressTextSanitizer.stripUnitDetails("ул. \"Лучезар Станчев\" №3 ет.9"));
  }

  @Test
  void blankOrNullInputReturnsEmptyString() {
    assertEquals("", BgAddressTextSanitizer.stripUnitDetails(null));
    assertEquals("", BgAddressTextSanitizer.stripUnitDetails("   "));
  }
}

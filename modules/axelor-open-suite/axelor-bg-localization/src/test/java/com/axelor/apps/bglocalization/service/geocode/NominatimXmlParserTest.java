package com.axelor.apps.bglocalization.service.geocode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Parses canned Nominatim response bodies -- no live network call ever. The Plovdiv/Sofia bodies
 * are shaped exactly like real Nominatim responses; the Italy one is fabricated to exercise the
 * rejection path.
 */
class NominatimXmlParserTest {

  private static final String PLOVDIV_HOUSE_NUMBER_MATCH =
      "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>"
          + "<searchresults><place place_id=\"1\" lat=\"42.1750318\" lon=\"24.7423277\""
          + " display_name=\"170, бул. Васил Левски, Пловдив, България\">"
          + "<house_number>170</house_number><road>бул. Васил Левски</road>"
          + "<city>Пловдив</city><postcode>4003</postcode>"
          + "<country>България</country><country_code>bg</country_code></place></searchresults>";

  private static final String SOFIA_NEIGHBORHOOD_MATCH =
      "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>"
          + "<searchresults><place place_id=\"2\" lat=\"42.6548862\" lon=\"23.4008491\""
          + " display_name=\"ж.к. Дружба, София, България\" class=\"boundary\" type=\"administrative\">"
          + "<suburb>ж.к. Дружба</suburb><city>София</city>"
          + "<country>България</country><country_code>bg</country_code></place></searchresults>";

  private static final String ITALY_MISMATCH =
      "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>"
          + "<searchresults><place place_id=\"3\" lat=\"44.9\" lon=\"7.68\""
          + " display_name=\"Via Roma, Carignano, Italia\">"
          + "<road>Via Roma</road><city>Carignano</city><postcode>10041</postcode>"
          + "<country>Italia</country><country_code>it</country_code></place></searchresults>";

  private static final String NO_RESULTS =
      "<?xml version=\"1.0\" encoding=\"UTF-8\" ?>"
          + "<searchresults timestamp=\"x\" attribution=\"x\" querystring=\"x\"/>";

  @Test
  void parsesExactHouseNumberMatch() {
    Optional<NominatimPlace> place =
        NominatimXmlParser.parseFirstResult(PLOVDIV_HOUSE_NUMBER_MATCH);
    assertTrue(place.isPresent());
    assertEquals(0, place.get().getLatitude().compareTo(new BigDecimal("42.1750318")));
    assertTrue(place.get().hasHouseNumber());
    assertTrue(place.get().hasRoad());
    assertEquals("bg", place.get().getCountryCode());
    assertEquals("Пловдив", place.get().getSettlementName());
    assertEquals("4003", place.get().getPostcode());
  }

  @Test
  void parsesStreetLevelMatchWithoutHouseNumber() {
    Optional<NominatimPlace> place = NominatimXmlParser.parseFirstResult(SOFIA_NEIGHBORHOOD_MATCH);
    assertTrue(place.isPresent());
    assertFalse(place.get().hasHouseNumber());
    assertFalse(place.get().hasRoad());
    assertEquals("София", place.get().getSettlementName());
  }

  @Test
  void parsesAForeignCountryResult_dataIntact_forTheValidatorToReject() {
    Optional<NominatimPlace> place = NominatimXmlParser.parseFirstResult(ITALY_MISMATCH);
    assertTrue(place.isPresent());
    assertEquals("it", place.get().getCountryCode());
    assertEquals("Carignano", place.get().getSettlementName());
  }

  @Test
  void noResultsElementYieldsEmpty() {
    assertTrue(NominatimXmlParser.parseFirstResult(NO_RESULTS).isEmpty());
  }

  @Test
  void blankOrMalformedInputYieldsEmpty_neverThrows() {
    assertTrue(NominatimXmlParser.parseFirstResult(null).isEmpty());
    assertTrue(NominatimXmlParser.parseFirstResult("").isEmpty());
    assertTrue(NominatimXmlParser.parseFirstResult("not xml at all").isEmpty());
  }
}

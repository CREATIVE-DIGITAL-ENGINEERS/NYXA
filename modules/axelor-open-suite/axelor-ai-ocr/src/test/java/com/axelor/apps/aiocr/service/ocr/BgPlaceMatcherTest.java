package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.axelor.apps.base.db.City;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Resolving dataset City candidates to a single reliable city — or nothing (never guess). */
class BgPlaceMatcherTest {

  private static City city(String name, String region, String municipality, String zip) {
    City c = new City();
    c.setName(name);
    c.setRegion(region);
    c.setMunicipality(municipality);
    c.setZip(zip);
    return c;
  }

  @Test
  void uniqueMatchReturnsThatCity() {
    City sofia = city("София", "София Столица", "Столична", "1000");
    assertSame(sofia, BgPlaceMatcher.resolveCity(Collections.singletonList(sofia), null, null));
  }

  @Test
  void duplicateResolvedByRegion() {
    City blago = city("Абланица", "Благоевград", "Хаджидимово", "2932");
    City lovech = city("Абланица", "Ловеч", "Ловеч", "5574");
    assertSame(lovech, BgPlaceMatcher.resolveCity(Arrays.asList(blago, lovech), "Ловеч", null));
    assertSame(
        blago, BgPlaceMatcher.resolveCity(Arrays.asList(blago, lovech), "Благоевград", null));
  }

  @Test
  void sameNameAndRegionResolvedByMunicipality() {
    // Слатина, Ловеч exists twice — only the municipality distinguishes them (and their zips
    // differ).
    City yablanitsa = city("Слатина", "Ловеч", "Ябланица", "5768");
    City lovech = city("Слатина", "Ловеч", "Ловеч", "5564");
    List<City> both = Arrays.asList(yablanitsa, lovech);
    assertSame(yablanitsa, BgPlaceMatcher.resolveCity(both, "Ловеч", "Ябланица"));
    assertSame(lovech, BgPlaceMatcher.resolveCity(both, "Ловеч", "Ловеч"));
  }

  @Test
  void sameNameRegionWithoutMunicipalityIsAmbiguousNull() {
    // Region alone does not disambiguate → never guess a (wrong) zip → To review.
    List<City> both =
        Arrays.asList(
            city("Слатина", "Ловеч", "Ябланица", "5768"),
            city("Слатина", "Ловеч", "Ловеч", "5564"));
    assertNull(BgPlaceMatcher.resolveCity(both, "Ловеч", null));
    assertNull(BgPlaceMatcher.resolveCity(both, "Ловеч", "Тетевен")); // municipality not among them
  }

  @Test
  void sameNameRegionResolvedByEnglishMunicipality() {
    // The invoice writes the municipality in Latin ("Yablanitsa") — it must still resolve via
    // City.municipalityEn.
    City yablanitsa = city("Слатина", "Ловеч", "Ябланица", "5768");
    yablanitsa.setMunicipalityEn("Yablanitsa");
    City lovech = city("Слатина", "Ловеч", "Ловеч", "5564");
    lovech.setMunicipalityEn("Lovech");
    List<City> both = Arrays.asList(yablanitsa, lovech);
    assertSame(yablanitsa, BgPlaceMatcher.resolveCity(both, "Ловеч", "Yablanitsa"));
    assertSame(lovech, BgPlaceMatcher.resolveCity(both, "Lovech", "Lovech"));
  }

  @Test
  void unknownCityReturnsNull() {
    assertNull(BgPlaceMatcher.resolveCity(Collections.emptyList(), null, null));
    assertNull(BgPlaceMatcher.resolveCity(null, "София Столица", "Столична"));
  }
}

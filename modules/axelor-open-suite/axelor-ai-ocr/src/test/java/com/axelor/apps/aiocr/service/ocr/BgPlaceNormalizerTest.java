package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Normalization used to match an OCR city name against the BG settlement dataset. */
class BgPlaceNormalizerTest {

  @Test
  void stripsMarkerCaseAndWhitespace() {
    assertEquals("софия", BgPlaceNormalizer.normalizeCyrillic("  гр. София "));
    assertEquals("софия", BgPlaceNormalizer.normalizeCyrillic("СОФИЯ"));
    assertEquals("велико търново", BgPlaceNormalizer.normalizeCyrillic("гр.Велико Търново"));
  }

  @Test
  void bulgarianOcrKeyMatchesDatasetName() {
    // The OCR city and the stored dataset name normalize to the same Cyrillic key.
    assertEquals(
        BgPlaceNormalizer.normalizeCyrillic("София"),
        BgPlaceNormalizer.normalizeCyrillic("гр. София"));
  }

  @Test
  void englishOcrKeyMatchesDatasetNameEn() {
    assertEquals("sofia", BgPlaceNormalizer.normalizeLatin("Sofia"));
    assertEquals(
        BgPlaceNormalizer.normalizeLatin("SOFIA "), BgPlaceNormalizer.normalizeLatin("Sofia"));
  }

  @Test
  void foldsLatinHomoglyphsToCyrillic() {
    // "Cофия" with a Latin capital C (OCR artefact) still normalizes to the Cyrillic key.
    assertEquals(
        BgPlaceNormalizer.normalizeCyrillic("София"), BgPlaceNormalizer.normalizeCyrillic("Cофия"));
  }

  @Test
  void sameNormalizedComparesRegionsAndMunicipalities() {
    assertTrue(BgPlaceNormalizer.sameNormalized("Ловеч", " ловеч "));
    assertFalse(BgPlaceNormalizer.sameNormalized("Ловеч", "Благоевград"));
    assertFalse(BgPlaceNormalizer.sameNormalized(null, "Ловеч"));
  }
}

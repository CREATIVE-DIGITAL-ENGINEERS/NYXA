package com.axelor.apps.aiocr.service.ocr;

import com.axelor.apps.base.db.City;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure resolver that turns same-name {@link City} candidates into a SINGLE reliable city — or
 * nothing. Never guesses:
 *
 * <ul>
 *   <li>0 candidates → null (unknown city);
 *   <li>exactly 1 → that city;
 *   <li>several → narrow by the OCR region (област), then by the OCR municipality (община) when
 *       present; a single survivor → that city; otherwise null (ambiguous → caller leaves the
 *       address empty / invoice "To review"). Because same-name/same-region settlements can have
 *       different postcodes, a still-ambiguous set is never guessed.
 * </ul>
 */
public final class BgPlaceMatcher {

  private BgPlaceMatcher() {}

  public static City resolveCity(List<City> candidates, String ocrRegion, String ocrMunicipality) {
    if (candidates == null || candidates.isEmpty()) {
      return null;
    }
    List<City> filtered = narrow(candidates, ocrRegion, true);
    if (filtered.size() > 1) {
      filtered = narrow(filtered, ocrMunicipality, false);
    }
    return filtered.size() == 1 ? filtered.get(0) : null;
  }

  /**
   * Keep candidates whose region (or municipality) matches the OCR value — comparing against BOTH
   * the Bulgarian and the English form so a Latin-written invoice matches too. Ignore the filter if
   * none match (so a wrong/absent OCR value never eliminates every candidate).
   */
  private static List<City> narrow(List<City> in, String ocrValue, boolean byRegion) {
    if (ocrValue == null || ocrValue.trim().isEmpty()) {
      return in;
    }
    List<City> out = new ArrayList<>();
    for (City c : in) {
      String bg = byRegion ? c.getRegion() : c.getMunicipality();
      String en = byRegion ? c.getRegionEn() : c.getMunicipalityEn();
      if (BgPlaceNormalizer.sameNormalized(bg, ocrValue)
          || BgPlaceNormalizer.sameNormalized(en, ocrValue)) {
        out.add(c);
      }
    }
    return out.isEmpty() ? in : out;
  }
}

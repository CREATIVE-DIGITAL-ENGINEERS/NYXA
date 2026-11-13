package com.axelor.apps.aiocr.service.ocr;

import com.axelor.apps.base.db.City;
import com.axelor.db.JPA;
import com.google.inject.Singleton;
import java.util.Collections;
import java.util.List;

/**
 * Exact-postcode lookup/validation for a {@link City}, backed by the {@code BgCityPostcode} rows
 * imported by {@link BgCityPostcodeImportService} from {@code bg_valid_postcodes.csv}. A city with
 * no rows here has no known validation data, so callers must treat an empty list as "unrestricted"
 * (existing free-entry behavior), not as "no postcode is valid".
 */
@Singleton
public class BgCityPostcodeService {

  /** The exact valid postcodes for {@code city}, or empty if this city has no validation data. */
  public List<String> getValidPostcodes(City city) {
    if (city == null || city.getId() == null) {
      return Collections.emptyList();
    }
    return JPA.em()
        .createQuery(
            "SELECT p.postcode FROM BgCityPostcode p WHERE p.city.id = :cityId", String.class)
        .setParameter("cityId", city.getId())
        .getResultList();
  }

  /**
   * True only when the city has exactly one known valid postcode (Address.zip must be readonly).
   */
  public boolean isSinglePostcode(City city) {
    return isSinglePostcode(getValidPostcodes(city));
  }

  /**
   * True when {@code zip} is acceptable for {@code city}: either the city has no validation data
   * (case 3 — unrestricted), or {@code zip} is exactly one of its known valid postcodes.
   */
  public boolean isZipValid(City city, String zip) {
    return isZipValid(getValidPostcodes(city), zip);
  }

  /** Pure decision, no DB access — kept separate so it can be unit-tested without HSQLDB. */
  public static boolean isSinglePostcode(List<String> postcodes) {
    return postcodes.size() == 1;
  }

  /** Pure decision, no DB access — kept separate so it can be unit-tested without HSQLDB. */
  public static boolean isZipValid(List<String> postcodes, String zip) {
    return postcodes.isEmpty() || postcodes.contains(zip);
  }
}

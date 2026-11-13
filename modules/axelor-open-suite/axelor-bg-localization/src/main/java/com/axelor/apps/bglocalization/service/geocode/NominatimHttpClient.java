package com.axelor.apps.bglocalization.service.geocode;

import java.util.Optional;

/**
 * The only seam that does real network I/O for BG geocoding. Kept to one trivial method so tests
 * can supply canned responses instead of ever calling the live Nominatim service.
 */
public interface NominatimHttpClient {

  /**
   * Runs a Nominatim {@code /search} for {@code query}, restricted to {@code countryCodeLower} (ISO
   * alpha-2, lowercase). Returns the raw XML response body, or empty on any failure (timeout,
   * non-200, network error) -- callers must treat empty exactly like "no result", never retry
   * automatically.
   */
  Optional<String> search(String query, String countryCodeLower);
}

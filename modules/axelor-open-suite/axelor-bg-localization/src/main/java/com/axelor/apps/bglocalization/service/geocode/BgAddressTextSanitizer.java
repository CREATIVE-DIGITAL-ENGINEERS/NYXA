package com.axelor.apps.bglocalization.service.geocode;

import java.util.regex.Pattern;

/**
 * Strips Bulgarian entrance/floor/apartment notation ({@code вх.}/{@code вход}, {@code ет.}/{@code
 * етаж}, {@code ап.}/{@code апт.}/{@code апартамент}) from free-text street address, since that
 * level of detail adds no map precision and commonly confuses geocoders (see
 * BgGeocodeQueryBuilder). Everything else -- house number, block ({@code бл.}), neighborhood
 * ({@code ж.к.}) -- is left untouched.
 */
public final class BgAddressTextSanitizer {

  // Each token: the abbreviation/word, optional trailing dot, optional spaces, then its value
  // (letters/digits/dots, e.g. "8", "Б", "3.1"). Bulgarian is case-insensitive here since users
  // type
  // both "ЕТ." and "ет.".
  private static final Pattern ENTRANCE = Pattern.compile("(?iu)\\bвх(?:од)?\\.?\\s*[\\p{L}\\d]+");
  private static final Pattern FLOOR = Pattern.compile("(?iu)\\bет(?:аж)?\\.?\\s*[\\d.]+");
  private static final Pattern APARTMENT =
      Pattern.compile("(?iu)\\bап(?:т|артамент)?\\.?\\s*[\\d.]+");
  // Collapse whatever separator punctuation/whitespace is left behind by a removed token.
  private static final Pattern DANGLING_SEPARATORS = Pattern.compile("\\s*,\\s*(,|$)");
  private static final Pattern EXTRA_SPACES = Pattern.compile("\\s{2,}");
  private static final Pattern LEADING_TRAILING_SEPARATORS = Pattern.compile("^[,\\s]+|[,\\s]+$");

  private BgAddressTextSanitizer() {}

  /**
   * Removes entrance/floor/apartment tokens from {@code text} and normalizes the leftover
   * punctuation/whitespace. Returns an empty string (never null) for null/blank input.
   */
  public static String stripUnitDetails(String text) {
    if (text == null || text.trim().isEmpty()) {
      return "";
    }
    String result = text;
    result = ENTRANCE.matcher(result).replaceAll("");
    result = FLOOR.matcher(result).replaceAll("");
    result = APARTMENT.matcher(result).replaceAll("");
    // Repeatedly collapse "x, , y" / trailing "," left by removed tokens until stable.
    String previous;
    do {
      previous = result;
      result = DANGLING_SEPARATORS.matcher(result).replaceAll("$1");
    } while (!result.equals(previous));
    result = EXTRA_SPACES.matcher(result).replaceAll(" ");
    result = LEADING_TRAILING_SEPARATORS.matcher(result).replaceAll("");
    return result.trim();
  }
}

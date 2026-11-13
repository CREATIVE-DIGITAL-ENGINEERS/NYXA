package com.axelor.apps.aiocr.service.ocr;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Pure normalization for matching a Bulgarian settlement name against the reference dataset.
 * Dependency-free and deliberately conservative. Two forms are produced because the dataset stores
 * both a Cyrillic name and an English name:
 *
 * <ul>
 *   <li>{@link #normalizeCyrillic} — lower-cases, strips the "гр./с./град/село" marker and
 *       punctuation, collapses whitespace, then folds common Latin look-alike letters to Cyrillic
 *       (a frequent OCR artefact, e.g. Latin "C/O" inside "CОФИЯ"). Compared to {@code
 *       normalizedName}.
 *   <li>{@link #normalizeLatin} — same, but WITHOUT homoglyph folding (kept Latin). Compared to
 *       {@code normalizedNameEn}.
 * </ul>
 */
public final class BgPlaceNormalizer {

  private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
  // Leading settlement marker: "гр.", "гр ", "град ", "с.", "село ".
  private static final Pattern MARKER = Pattern.compile("^(?:гр\\.?|град|с\\.|село)\\s+|^гр\\.", F);
  private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{Nd} -]+", F);
  private static final Pattern SPACES = Pattern.compile("\\s+");

  // Latin → Cyrillic visual look-alikes (lower-case), applied only for the Cyrillic comparison.
  private static final String LAT = "aceopxykmthbн";
  private static final String CYR = "асеорхукмтхвн";

  private BgPlaceNormalizer() {}

  private static String base(String s) {
    if (s == null) {
      return "";
    }
    String t = Normalizer.normalize(s, Normalizer.Form.NFC).trim().toLowerCase();
    t = MARKER.matcher(t).replaceAll("");
    t = NON_WORD.matcher(t).replaceAll(" ");
    t = SPACES.matcher(t).replaceAll(" ").trim();
    return t;
  }

  /** Normalized Cyrillic form (with Latin→Cyrillic homoglyph folding). */
  public static String normalizeCyrillic(String s) {
    String t = base(s);
    StringBuilder sb = new StringBuilder(t.length());
    for (int i = 0; i < t.length(); i++) {
      char c = t.charAt(i);
      int idx = LAT.indexOf(c);
      sb.append(idx >= 0 ? CYR.charAt(idx) : c);
    }
    return sb.toString();
  }

  /** Normalized Latin form (no homoglyph folding). */
  public static String normalizeLatin(String s) {
    return base(s);
  }

  /**
   * True when {@code a} and {@code b} are the same after plain (marker/case/space) normalization.
   */
  public static boolean sameNormalized(String a, String b) {
    String na = base(a);
    String nb = base(b);
    return !na.isEmpty() && na.equals(nb);
  }
}

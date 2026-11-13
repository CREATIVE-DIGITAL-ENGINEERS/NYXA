package com.axelor.apps.bglocalization.service.fatca;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * GIIN — Global Intermediary Identification Number от регистъра на IRS.
 *
 * <p>Формат {@code XXXXXX.XXXXX.XX.XXX}, 19 знака: FATCA ID (6), вид (5), категория (2 букви),
 * цифров код на държавата по ISO 3166-1 (3). Източник: irs.gov, „FATCA Registration and FFI List:
 * GIIN Composition Information“ (обновена 05.12.2025).
 *
 * <p><b>⚠️ ПРОВЕРЯВА СЕ СТРУКТУРАТА, НЕ РЕГИСТЪРЪТ.</b> Дали номерът е в списъка на IRS, тук не се
 * знае. Държава, различна от България ({@value #BULGARIA}), е предупреждение, а не отказ: клон в
 * чужбина има GIIN с код на своята държава.
 */
public final class BgFatcaGiin {

  /** ISO 3166-1 цифров код на България. */
  public static final String BULGARIA = "100";

  private static final Pattern FORMAT =
      Pattern.compile("^[0-9A-Z]{6}\\.[0-9A-Z]{5}\\.[A-Z]{2}\\.[0-9]{3}$");

  private BgFatcaGiin() {}

  /** Без интервали, с главни букви; празното е {@code null}. */
  public static String normalize(String giin) {
    if (giin == null || giin.trim().isEmpty()) {
      return null;
    }
    return giin.trim().toUpperCase(Locale.ROOT);
  }

  public static boolean isValid(String giin) {
    String value = normalize(giin);
    return value != null && FORMAT.matcher(value).matches();
  }

  public static boolean isBulgarian(String giin) {
    String value = normalize(giin);
    return value != null && value.endsWith("." + BULGARIA);
  }
}

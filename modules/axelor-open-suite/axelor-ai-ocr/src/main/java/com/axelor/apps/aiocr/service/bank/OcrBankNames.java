package com.axelor.apps.aiocr.service.bank;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Joins the parts of a bank's name, leaving out the empty ones. */
public final class OcrBankNames {

  private OcrBankNames() {}

  private static final String SEPARATOR = " - ";

  /** {@code code - name}, or whichever of the two is there; null when neither is. */
  public static String join(String code, String name) {
    String joined =
        Arrays.stream(new String[] {code, name})
            .filter(OcrBankNames::present)
            .map(String::trim)
            .collect(Collectors.joining(SEPARATOR));
    return joined.isEmpty() ? null : joined;
  }

  /**
   * A stored bank name with the parts that read {@code null} removed: {@code BNBGBGSD - null}
   * becomes {@code BNBGBGSD}. A name with no such part is returned unchanged.
   */
  public static String clean(String bankFullName) {
    if (bankFullName == null) {
      return null;
    }
    String[] parts = bankFullName.split(SEPARATOR, -1);
    if (Arrays.stream(parts).allMatch(OcrBankNames::present)) {
      return bankFullName;
    }
    String joined =
        Arrays.stream(parts).filter(OcrBankNames::present).collect(Collectors.joining(SEPARATOR));
    return joined.isEmpty() ? null : joined;
  }

  private static boolean present(String part) {
    return part != null && !part.trim().isEmpty() && !"null".equals(part.trim());
  }
}

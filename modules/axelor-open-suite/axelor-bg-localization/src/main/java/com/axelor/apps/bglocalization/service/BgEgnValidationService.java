/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 *
 * EGN / LNCh checksum and date validation.
 */
package com.axelor.apps.bglocalization.service;

import java.time.LocalDate;

/**
 * Validation of Bulgarian personal identifiers.
 *
 * <ul>
 *   <li>ЕГН (EGN) - Единен граждански номер: 10 digits, encodes a birth date (with month
 *       corrections for the century) and a control digit.
 *   <li>ЛНЧ (LNCh) - Личен номер на чужденец: 10 digits, control digit only (no date component).
 * </ul>
 */
public class BgEgnValidationService {

  /** EGN control-digit weights applied to the first 9 digits. */
  private static final int[] EGN_WEIGHTS = {2, 4, 8, 5, 10, 9, 7, 3, 6};

  /** LNCh control-digit weights applied to the first 9 digits. */
  private static final int[] LNCH_WEIGHTS = {21, 19, 17, 13, 11, 9, 7, 3, 1};

  /**
   * Validate an EGN: 10 digits, a valid encoded birth date (with +20/+40 century corrections) and a
   * matching control digit (weighted sum mod 11, remainder 10 mapped to 0).
   *
   * @return {@code true} when the value is a structurally valid EGN.
   */
  public boolean validateEgn(String egn) {
    int[] digits = toDigits(egn);
    if (digits == null) {
      return false;
    }
    if (!hasValidEgnDate(digits)) {
      return false;
    }

    int sum = 0;
    for (int i = 0; i < EGN_WEIGHTS.length; i++) {
      sum += digits[i] * EGN_WEIGHTS[i];
    }
    int control = sum % 11;
    if (control == 10) {
      control = 0;
    }
    return control == digits[9];
  }

  /**
   * Validate an LNCh: 10 digits and a matching control digit (weighted sum mod 10). LNCh does not
   * encode a birth date, so no date check is performed.
   *
   * @return {@code true} when the value is a structurally valid LNCh.
   */
  public boolean validateLnch(String lnch) {
    int[] digits = toDigits(lnch);
    if (digits == null) {
      return false;
    }

    int sum = 0;
    for (int i = 0; i < LNCH_WEIGHTS.length; i++) {
      sum += digits[i] * LNCH_WEIGHTS[i];
    }
    int control = sum % 10;
    return control == digits[9];
  }

  /** Parse exactly 10 numeric characters into an int[10]; returns {@code null} otherwise. */
  protected int[] toDigits(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    if (trimmed.length() != 10) {
      return null;
    }
    int[] digits = new int[10];
    for (int i = 0; i < 10; i++) {
      char c = trimmed.charAt(i);
      if (c < '0' || c > '9') {
        return null;
      }
      digits[i] = c - '0';
    }
    return digits;
  }

  /**
   * Check that digits 0-5 (YYMMDD) encode a real calendar date, applying the GRAO month
   * corrections: month 1-12 → born 1900-1999; month 21-32 (+20) → born 1800-1899; month 41-52 (+40)
   * → born 2000 and later.
   */
  protected boolean hasValidEgnDate(int[] digits) {
    int yy = digits[0] * 10 + digits[1];
    int mm = digits[2] * 10 + digits[3];
    int dd = digits[4] * 10 + digits[5];

    int year;
    int month;
    if (mm >= 1 && mm <= 12) {
      year = 1900 + yy;
      month = mm;
    } else if (mm >= 21 && mm <= 32) {
      year = 1800 + yy;
      month = mm - 20;
    } else if (mm >= 41 && mm <= 52) {
      year = 2000 + yy;
      month = mm - 40;
    } else {
      return false;
    }

    try {
      LocalDate.of(year, month, dd);
      return true;
    } catch (java.time.DateTimeException e) {
      return false;
    }
  }
}

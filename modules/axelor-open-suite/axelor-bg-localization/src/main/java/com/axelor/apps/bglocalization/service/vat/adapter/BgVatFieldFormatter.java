package com.axelor.apps.bglocalization.service.vat.adapter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Shared fixed-width field formatting for the NAP VAT diaries (продажби PRODAGBI + покупки
 * POKUPKI). Single source of truth for padding, date and amount formatting so both adapters stay
 * byte-consistent.
 */
public final class BgVatFieldFormatter {

  /**
   * Клонът (позиции 22–25) на дружество без клонове: „0“, ляво подравнено — както в приетите от
   * portal.nra.bg файлове. „00“ го нямат.
   */
  public static final String DEFAULT_BRANCH = "0";

  private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private static final int AMOUNT_SCALE = 2;

  private BgVatFieldFormatter() {}

  /** Normalise a free-text field: never null; strip CR/LF/TAB so it cannot corrupt the record. */
  public static String field(String value) {
    if (value == null) {
      return "";
    }
    return value.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
  }

  /**
   * Left-align in {@code width}, space-padded on the right; truncate to {@code width} if longer.
   */
  public static String padRight(String value, int width) {
    String v = value == null ? "" : value;
    if (v.length() >= width) {
      return v.substring(0, width);
    }
    StringBuilder sb = new StringBuilder(width).append(v);
    while (sb.length() < width) {
      sb.append(' ');
    }
    return sb.toString();
  }

  /** Right-align in {@code width}, space-padded on the left; keep last {@code width} if longer. */
  public static String padLeft(String value, int width) {
    return pad(value, width, ' ');
  }

  /** Right-align in {@code width}, '0'-padded on the left (напр. кл.03 вид документа). */
  public static String zeroPadLeft(String value, int width) {
    return pad(value, width, '0');
  }

  private static String pad(String value, int width, char fill) {
    String v = value == null ? "" : value;
    if (v.length() >= width) {
      return v.substring(v.length() - width);
    }
    StringBuilder sb = new StringBuilder(width);
    for (int i = v.length(); i < width; i++) {
      sb.append(fill);
    }
    return sb.append(v).toString();
  }

  /**
   * 2-decimal plain amount: dot separator, "0.00" for null/zero, leading '-' for negatives (кл.14
   * годишна корекция, кредитни известия). Width/alignment padding is the caller's job.
   */
  public static String formatAmount(BigDecimal value) {
    return roundAmount(value).toPlainString();
  }

  /**
   * До стотинка, HALF_UP — точността на колоните на дневниците и на декларацията. Сумите на AOS
   * (фактура, ред, данъчен ред) са със scale 3; закръгля се СБОРЪТ на документа по колона, не всеки
   * ред от фактурата поотделно.
   */
  public static BigDecimal roundAmount(BigDecimal value) {
    BigDecimal v = value == null ? BigDecimal.ZERO : value;
    return v.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
  }

  public static String formatDate(LocalDate date) {
    return date == null ? "" : date.format(DATE_FORMAT);
  }
}

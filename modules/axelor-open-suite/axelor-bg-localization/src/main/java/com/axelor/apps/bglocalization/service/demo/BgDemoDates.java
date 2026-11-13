package com.axelor.apps.bglocalization.service.demo;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Month;

/**
 * ДАТИТЕ НА НАБОРА СА „МЕСЕЦ-ДЕН“, ГОДИНАТА Е НА НАЛИВАНЕТО.
 *
 * <p>В CSV-тата датата е {@code ММ-ДД} (например {@code 03-16}), за да работи наборът и догодина.
 * Годината се избира при наливането (по подразбиране текущата). Периодът на набора е 01.01–31.10;
 * ноември и декември остават празни.
 *
 * <p>Дата, която не съществува в избраната година ({@code 02-29} в невисокосна), е грешка в
 * данните, а не тихо преместване — наборът не я ползва.
 */
public final class BgDemoDates {

  /** Последният месец с данни. */
  public static final Month LAST_MONTH = Month.OCTOBER;

  private final int year;

  public BgDemoDates(int year) {
    this.year = year;
  }

  public int year() {
    return year;
  }

  public LocalDate first() {
    return LocalDate.of(year, 1, 1);
  }

  /** 31.10 на годината — последният ден с документи. */
  public LocalDate last() {
    return LocalDate.of(year, LAST_MONTH, 1).withDayOfMonth(31);
  }

  /** {@code ММ-ДД} → дата в годината на наливането. Празно → {@code null}. */
  public LocalDate of(String monthDay) {
    if (monthDay == null || monthDay.trim().isEmpty()) {
      return null;
    }
    String[] p = monthDay.trim().split("-");
    if (p.length != 2) {
      throw new IllegalArgumentException("Датата трябва да е ММ-ДД: " + monthDay);
    }
    try {
      return LocalDate.of(year, Integer.parseInt(p[0]), Integer.parseInt(p[1]));
    } catch (DateTimeException | NumberFormatException e) {
      throw new IllegalArgumentException("Няма такава дата през " + year + ": " + monthDay, e);
    }
  }

  /** Последният ден на месеца. */
  public LocalDate endOfMonth(int month) {
    LocalDate d = LocalDate.of(year, month, 1);
    return d.withDayOfMonth(d.lengthOfMonth());
  }
}

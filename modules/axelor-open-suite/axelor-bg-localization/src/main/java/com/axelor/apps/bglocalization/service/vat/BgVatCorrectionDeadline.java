package com.axelor.apps.bglocalization.service.vat;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.function.Predicate;

/**
 * „СЪЗДАЙ КОРЕКЦИЯ“ СЛЕД СРОКА — ПРЕДУПРЕЖДЕНИЕ, НЕ ЗАБРАНА. Чиста функция: без база.
 *
 * <p>Повторното подаване на целия комплект за същия период (ЗДДС чл. 126 ал. 2) е допустимо само до
 * срока по чл. 125 ал. 5 — 14-о число на месеца след периода, преместено на първия работен ден,
 * когато е събота, неделя или празник ({@link BgVatReportingPeriod#deadline(String, Predicate)};
 * основанието за преместването — да се потвърди). След срока поправката е по чл. 126 ал. 3: т. 1 —
 * в текущия период, т. 2 — писмено уведомление до НАП. Корекцията все пак се създава; човекът
 * решава.
 *
 * <p>Отхвърлен оригинал — без предупреждение: отхвърленият от НАП файл не е подаване ({@link
 * BgVatFiledPeriods}), декларацията за периода трябва да може да се подаде и след срока.
 */
public final class BgVatCorrectionDeadline {

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private BgVatCorrectionDeadline() {}

  /**
   * Предупреждението при създаване на коригираща; {@code null} — няма.
   *
   * @param originalStatus статусът на декларацията, от която се създава корекцията
   * @param period периодът ({@code ГГГГММ})
   * @param today денят на създаване
   * @param holiday празник ли е денят — по календара на дружеството
   */
  public static String warning(
      String originalStatus, String period, LocalDate today, Predicate<LocalDate> holiday) {
    if ("rejected".equals(originalStatus) || !BgVatReportingPeriod.isValid(period)) {
      return null;
    }
    LocalDate deadline = BgVatReportingPeriod.deadline(period, holiday);
    if (!today.isAfter(deadline)) {
      return null;
    }
    return "Срокът за подаване за "
        + BgVatReportingPeriod.label(period)
        + " изтече на "
        + DATE.format(deadline)
        + ". Повторно подаване по чл. 126 ал. 2 ЗДДС е само до срока. След него неотразен документ"
        + " се включва в текущия период (чл. 126 ал. 3 т. 1), а неправилно отразен се поправя с"
        + " писмено уведомление до НАП (т. 2).";
  }
}

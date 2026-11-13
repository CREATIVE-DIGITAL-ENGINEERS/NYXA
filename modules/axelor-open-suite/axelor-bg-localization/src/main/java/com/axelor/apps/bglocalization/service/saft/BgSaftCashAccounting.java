package com.axelor.apps.bglocalization.service.saft;

import java.time.LocalDate;
import java.util.Set;

/**
 * КАСОВА ОТЧЕТНОСТ ПО ДДС (глава 17а ЗДДС) В SAF-T — чиста функция над периода на дружеството.
 *
 * <p><b>Отговорът на НАП по имейл от 19.10.2026</b>:
 *
 * <ul>
 *   <li>издадените фактури и известия по режима — SalesInvoices, S.I.9 = 11/12/13, S.TI.2 = 100211
 *       или 100213 (тоест обичайният код на ставката);
 *   <li>получените фактури и известия по режима „от лице, което не е регистрирано по режима“ —
 *       PurchaseInvoices, S.I.9 = 11/12/13, S.TI.2 = 100330/100331/100332 по правото на кредит;
 *   <li>получените протоколи по чл. 151в ал. 3 или ал. 7 — PurchaseInvoices, S.I.9 = 91 или 93,
 *       S.TI.2 = 100330/100331/100332.
 * </ul>
 *
 * <p><b>⚠️ ПОЛУЧАТЕЛ ПО РЕЖИМА — ГРЕШКА.</b> НАП описва само получател, който НЕ е по режима.
 * Получените документи, когато самото дружество е по режима към датата на документа, са ГРЕШКА „да
 * се потвърди“ (запитване до НАП от 26.10.2026).
 *
 * <p><b>Периодът</b> е на дружеството ({@code Company.bgVatCashAccountingFrom/To}): „от“ — първият
 * ден, в който режимът се прилага (ЗДДС чл. 151б ал. 1: „от първия ден на месеца, следващ месеца на
 * получаване на разрешението“), „до“ — последният ден (чл. 151б ал. 9: прекратяването е „от деня,
 * следващ деня на връчване на акта“); празно „до“ = режимът продължава. Сверено 26.10.2026 с
 * консолидирания ЗДДС (lex.bg, последно изм. ДВ бр. 69/2026). Празно „от“ = дружеството не е по
 * режима.
 */
public final class BgSaftCashAccounting {

  /** Дружество, което не прилага режима. */
  public static final BgSaftCashAccounting NONE = new BgSaftCashAccounting(null, null);

  /** Фактура, дебитно и кредитно известие по касова отчетност (Nom_Invoice_Types). */
  static final Set<String> INVOICE_TYPES = Set.of("11", "12", "13");

  /** Получените протоколи по чл. 151в ал. 3 и ал. 7 (отговорът на НАП: 91 или 93). */
  static final Set<String> RECEIVED_PROTOCOL_TYPES = Set.of("91", "93");

  private final LocalDate from;
  private final LocalDate to;

  private BgSaftCashAccounting(LocalDate from, LocalDate to) {
    this.from = from;
    this.to = to;
  }

  /** Периодът от полетата на дружеството; двете празни — {@link #NONE}. */
  public static BgSaftCashAccounting of(LocalDate from, LocalDate to) {
    return from == null && to == null ? NONE : new BgSaftCashAccounting(from, to);
  }

  /** Видът е фактура/известие по режима. */
  static boolean isInvoiceType(String type) {
    return type != null && INVOICE_TYPES.contains(type.trim());
  }

  /** Видът е получен протокол по чл. 151в ал. 3/7. */
  static boolean isReceivedProtocolType(String type) {
    return type != null && RECEIVED_PROTOCOL_TYPES.contains(type.trim());
  }

  /**
   * Защо получен документ по режима НЕ се подава; {@code null} — подава се с кода по кредита.
   *
   * @param date датата на документа
   */
  public String receivedRefusal(LocalDate date) {
    if (from == null && to != null) {
      return "касова отчетност на дружеството: „до“ " + to + " без „от“ — периодът не е еднозначен";
    }
    if (from != null && to != null && to.isBefore(from)) {
      return "касова отчетност на дружеството: „до“ " + to + " е преди „от“ " + from;
    }
    if (from == null) {
      return null;
    }
    if (date == null) {
      return "документ по касова отчетност без дата — не се знае дали дружеството е по режима";
    }
    if (date.isBefore(from) || (to != null && date.isAfter(to))) {
      return null;
    }
    return "дружеството е по касова отчетност към "
        + date
        + " — НАП описва само получател, който не е по режима; "
        + BgSaftNapPending.OWN_CASH_ACCOUNTING;
  }
}

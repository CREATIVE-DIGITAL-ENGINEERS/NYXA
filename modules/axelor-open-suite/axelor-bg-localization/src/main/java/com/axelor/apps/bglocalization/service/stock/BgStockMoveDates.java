package com.axelor.apps.bglocalization.service.stock;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/**
 * Датата на документа на складовото движение ({@code StockMove.bgDocumentDate}, SAF-T SD.MG.6) —
 * правилата за нея. Чиста функция.
 *
 * <p>SD.MG.6 е „дата на документа, с който е заведено материалното движение“, а SD.MG.7 — „дата на
 * осчетоводяване“. Axelor има само realDate, която е денят на реализиране и се презаписва при
 * отказ, затова документната дата е отделно поле, а realDate не се пипа.
 *
 * <ul>
 *   <li>в бъдещето — отказ: документ за движение, което още не е станало, няма;
 *   <li>в период в ход на затваряне („временно затворен“, „в затваряне“) — отказ: изписването би
 *       легнало в период, който се затваря;
 *   <li>в липсващ или затворен фискален период — отказ: периодът е приключен (или не е отворен), а
 *       по чл. 79 ал. 4 ЗДДС корекцията се прави в периода на обстоятелството;
 *   <li>в друг месец от realDate — само предупреждение: движението е реално, но е заведено късно и
 *       SAF-T ще го покаже в месеца на документа.
 * </ul>
 */
public final class BgStockMoveDates {

  /** Състоянието на фискалния период, в който пада датата. */
  public enum PeriodState {
    OPEN,
    MISSING,
    /** „Временно затворен“ или „в затваряне“ — счетоводното затваряне е в ход. */
    CLOSING,
    CLOSED
  }

  private static final DateTimeFormatter BG = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private BgStockMoveDates() {}

  /** Защо датата на документа се отказва, или {@code null}, когато е допустима. */
  public static String refusal(LocalDate documentDate, LocalDate today, PeriodState period) {
    if (documentDate == null) {
      return null;
    }
    if (today != null && documentDate.isAfter(today)) {
      return String.format(
          "Датата на документа %s е в бъдещето. Складовият документ се реализира с датата на"
              + " документа, с който е заведен (SAF-T SD.MG.6), а такъв документ още няма.",
          BG.format(documentDate));
    }
    if (period == PeriodState.MISSING) {
      return String.format(
          "За датата на документа %s дружеството няма фискален период. Отворете фискалната година"
              + " и периода, преди да реализирате движението.",
          BG.format(documentDate));
    }
    if (period == PeriodState.CLOSING) {
      return String.format(
          "Фискалният период на датата на документа %s е в ход на счетоводно затваряне (временно"
              + " затворен или в затваряне). Движение в него не се реализира — изписването би"
              + " легнало в период, който се затваря. Изберете дата в отворен период или се"
              + " координирайте със счетоводството.",
          BG.format(documentDate));
    }
    if (period == PeriodState.CLOSED) {
      return String.format(
          "Фискалният период на датата на документа %s е затворен. Движение в приключен период"
              + " не се реализира — изберете дата в отворен период.",
          BG.format(documentDate));
    }
    return null;
  }

  /**
   * Предупреждение, когато документът и реализирането са в различни месеци, или {@code null}. Не е
   * отказ: SAF-T подава движението с датата на документа, а realDate остава като дата на
   * осчетоводяване (SD.MG.7).
   */
  public static String monthWarning(LocalDate documentDate, LocalDate realDate) {
    if (documentDate == null || realDate == null) {
      return null;
    }
    if (YearMonth.from(documentDate).equals(YearMonth.from(realDate))) {
      return null;
    }
    return String.format(
        "Документът е от %s, а движението е реализирано на %s — в друг месец. SAF-T ще го подаде"
            + " с датата на документа.",
        BG.format(documentDate), BG.format(realDate));
  }
}

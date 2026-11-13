package com.axelor.apps.bglocalization.service.vat.adapter;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * ПЕРИОДЪТ НА ИЗИСКУЕМОСТТА НА VIR РЕДА — ППЗДДС чл. 117 ал. 2 т. 11: „данъчен период, през който
 * данъкът за доставките по т. 8 - 10 е станал изискуем, в случай че този период е различен от
 * данъчния период по т. 3“ (консолидиран текст, последно изм. ДВ бр. 54/2024, сверено 27.10.2026).
 *
 * <p>Периодът по вида на реда (ЗДДС, lex.bg, последно изм. ДВ бр. 69/2026, сверено 27.10.2026):
 *
 * <ul>
 *   <li><b>ВОД</b> — чл. 51 ал. 3: „на 15-ия ден на месеца, следващ месеца, през който е възникнало
 *       данъчното събитие“; ал. 4: „на датата на издаване на фактурата … когато тази фактура е
 *       издадена преди 15-ия ден на месеца, следващ месеца“.
 *   <li><b>Посредник в тристранна операция</b> — доставката му „не е вътреобщностна доставка“ (чл.
 *       7 ал. 5 т. 6), тоест чл. 51 НЕ важи: периодът е на данъчното събитие (чл. 25 ал. 2), а VIES
 *       е за периода, в който е извършена (чл. 125 ал. 2; чл. 62 ал. 5 т. 4).
 *   <li><b>Услуга по чл. 21 ал. 2</b> — чл. 25 ал. 6: на датата на данъчното събитие.
 *   <li><b>Без дата на данъчното събитие</b> — датата на документа: друга няма, а дневниците
 *       отразяват документа по нея.
 * </ul>
 *
 * <p>Авансите: по ВОД и от посредник не са във VIES (ППЗДДС чл. 117 ал. 4 — {@link
 * BgVatViesAdapter}); по услуга са (ал. 2 т. 4 б. „б“) и изискуемостта им е при плащането (ЗДДС чл.
 * 25 ал. 7) — от документа не се познава, остава по датата на събитието или на документа (отделна
 * задача).
 */
public final class BgVatViesPeriod {

  private static final DateTimeFormatter MM_YYYY = DateTimeFormatter.ofPattern("MM/yyyy");

  private BgVatViesPeriod() {}

  /** Видът на реда във VIES — решава правилото за изискуемостта. */
  public enum Kind {
    /** ВОД — ЗДДС чл. 51 ал. 3–4. */
    INTRA_COMMUNITY_SUPPLY,
    /** Посредник в тристранна операция — чл. 25 ал. 2 (не е ВОД, чл. 7 ал. 5 т. 6). */
    TRIANGULAR_INTERMEDIARY,
    /** Услуга по чл. 21 ал. 2 — чл. 25 ал. 6. */
    SERVICE
  }

  /** Датата, на която данъкът е станал изискуем; {@code null}, когато документът няма дати. */
  public static LocalDate chargeability(LocalDate documentDate, LocalDate taxEventDate, Kind kind) {
    if (taxEventDate == null) {
      return documentDate;
    }
    if (kind != Kind.INTRA_COMMUNITY_SUPPLY) {
      return taxEventDate;
    }
    LocalDate fifteenth = taxEventDate.plusMonths(1).withDayOfMonth(15);
    return documentDate != null && documentDate.isBefore(fifteenth) ? documentDate : fifteenth;
  }

  /**
   * Полето „данъчен период“ на VIR: празно, когато изискуемостта е в периода на декларацията (или
   * не се знае), иначе „мм/гггг“.
   *
   * @param declarationPeriod периодът на декларацията като „мм/гггг“ (т. 3)
   */
  public static String label(LocalDate chargeability, String declarationPeriod) {
    if (chargeability == null) {
      return "";
    }
    String period = chargeability.format(MM_YYYY);
    return period.equals(declarationPeriod == null ? "" : declarationPeriod.trim()) ? "" : period;
  }
}

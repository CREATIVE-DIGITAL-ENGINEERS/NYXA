package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * „Период на отразяване по ЗДДС“ ({@code Invoice.bgVatReportingPeriod}, {@code ГГГГММ}) — в кой
 * данъчен период документът влиза в дневниците, когато това не е месецът на статията му.
 *
 * <p>Празно = месецът на статията ({@code Move.date}, за фактура — {@code invoiceDate}), както
 * досега. Изричният период се отразява в {@link BgVatPeriodScope}; счетоводната дата не се мени.
 *
 * <p><b>Кои периоди са позволени</b> ({@link #allowed}) — чисти функции, без база:
 *
 * <ul>
 *   <li><b>Своят период</b> — неподаден, или подаден, но срокът за подаване (14-о число на
 *       следващия месец, ЗДДС чл. 125 ал. 5) не е изтекъл: грешката се поправя с повторно подаване
 *       (чл. 126 ал. 2).
 *   <li><b>След срока</b> — пропуснатият документ влиза в периода, в който грешката е установена
 *       (чл. 126 ал. 3 т. 1); „установена“ = денят на въвеждане (решение Р9 от 28.10.2026). Тук
 *       срокът е 14-о без пренасяне; пренасянето при неработен ден е в {@link #deadline(String,
 *       Predicate)} (ползва го коригиращата декларация).
 *   <li><b>Покупка</b> — и всеки неподаден период до дванадесетия след периода на издаване (чл. 72
 *       ал. 1, чл. 124 ал. 4). Издаването е датата на доставчика ({@code originDate}), иначе {@code
 *       invoiceDate} (решение Р6).
 *   <li><b>Получено кредитно известие</b> — без отлагане (чл. 124 ал. 5): като продажба.
 *   <li>Никога към по-ранен период: неправилно отразен документ се поправя с уведомление до НАП
 *       (чл. 126 ал. 3 т. 2), не с тази функция.
 *   <li>Отказ: фактура с протокол по чл. 117 (дневникът е на протокола — решение Р5) и статия,
 *       мигрирана от Microinvest.
 * </ul>
 */
public final class BgVatReportingPeriod {

  public static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private static final Pattern PATTERN = Pattern.compile("\\d{4}(0[1-9]|1[0-2])");

  /** Денят от следващия месец, до който се подава декларацията (ЗДДС чл. 125 ал. 5). */
  static final int DEADLINE_DAY = 14;

  /** Покупка: до дванадесетия период след издаването (ЗДДС чл. 72 ал. 1, чл. 124 ал. 4). */
  static final int PURCHASE_PERIODS = 12;

  /** Как документът може да се отлага. */
  public enum Kind {
    /** Издаден документ (и кредитно известие към него) и получено кредитно известие. */
    FIXED,
    /** Получена фактура (и дебитно известие) — с правото на кредит до 12 периода по-късно. */
    PURCHASE
  }

  private BgVatReportingPeriod() {}

  /** {@code ГГГГММ} на датата; {@code null} без дата. */
  public static String of(LocalDate date) {
    return date == null ? null : YearMonth.from(date).format(FORMAT);
  }

  public static boolean isValid(String period) {
    return period != null && PATTERN.matcher(period).matches();
  }

  /** Изричният период на фактурата; {@code null} — празно (месецът на статията). */
  public static String explicit(Invoice invoice) {
    String value = invoice == null ? null : invoice.getBgVatReportingPeriod();
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }

  /** Своят период — месецът на статията ({@code invoiceDate}; без нея — {@code originDate}). */
  public static String own(Invoice invoice) {
    return of(
        invoice.getInvoiceDate() != null ? invoice.getInvoiceDate() : invoice.getOriginDate());
  }

  /** Периодът, в който фактурата е в дневниците: изричният, иначе своят. */
  public static String effective(Invoice invoice) {
    String explicit = explicit(invoice);
    return explicit != null ? explicit : own(invoice);
  }

  /** Периодът на издаване за 12-те периода: датата на доставчика, иначе {@code invoiceDate}. */
  public static String issued(Invoice invoice) {
    return of(invoice.getOriginDate() != null ? invoice.getOriginDate() : invoice.getInvoiceDate());
  }

  public static Kind kind(Invoice invoice) {
    return invoice.getOperationTypeSelect() == InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE
        ? Kind.PURCHASE
        : Kind.FIXED;
  }

  /** {@code period} + {@code months}. */
  public static String plus(String period, int months) {
    return YearMonth.parse(period, FORMAT).plusMonths(months).format(FORMAT);
  }

  /** Последният ден за подаване на декларацията за периода — 14-о число на следващия месец. */
  public static LocalDate deadline(String period) {
    return YearMonth.parse(period, FORMAT).plusMonths(1).atDay(DEADLINE_DAY);
  }

  /**
   * Срокът, преместен на първия следващ работен ден, когато 14-о е събота, неделя или празник.
   * Основанието за преместването — да се потвърди.
   *
   * @param holiday празник ли е денят — по календара на дружеството; без календар — никой
   */
  public static LocalDate deadline(String period, Predicate<LocalDate> holiday) {
    LocalDate day = deadline(period);
    while (isWeekend(day) || holiday.test(day)) {
      day = day.plusDays(1);
    }
    return day;
  }

  private static boolean isWeekend(LocalDate day) {
    return day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
  }

  /** „ММ.ГГГГ“ за съобщенията. */
  public static String label(String period) {
    return isValid(period) ? period.substring(4) + "." + period.substring(0, 4) : period;
  }

  /**
   * Позволените периоди за документа, във възходящ ред: своят, когато още може, и периодите по чл.
   * 126 ал. 3 т. 1 и чл. 72 ал. 1. Празен списък — няма позволен период.
   *
   * @param own своят период ({@link #own})
   * @param issued периодът на издаване ({@link #issued}) — само за покупка
   * @param today денят на въвеждане
   * @param filed подаден ли е периодът
   */
  public static List<String> allowed(
      Kind kind, String own, String issued, LocalDate today, Predicate<String> filed) {
    List<String> allowed = new ArrayList<>();
    boolean ownOpen = !filed.test(own) || !today.isAfter(deadline(own));
    if (ownOpen) {
      allowed.add(own);
    }
    if (kind == Kind.PURCHASE) {
      String last = plus(issued == null ? own : issued, PURCHASE_PERIODS);
      for (String p = plus(own, 1); p.compareTo(last) <= 0; p = plus(p, 1)) {
        if (!filed.test(p)) {
          allowed.add(p);
        }
      }
    } else if (!ownOpen) {
      String current = of(today);
      if (current.compareTo(own) > 0 && !filed.test(current)) {
        allowed.add(current);
      }
    }
    return allowed;
  }

  /**
   * Защо документът не може да се отрази в {@code target}; {@code null} — може.
   *
   * @param hasProtocol има протокол по чл. 117 от фактурата
   * @param migrated статията е мигрирана от Microinvest
   */
  public static String refusal(
      Invoice invoice,
      String target,
      LocalDate today,
      Predicate<String> filed,
      boolean hasProtocol,
      boolean migrated) {
    if (!isValid(target)) {
      return "Периодът на отразяване е във вида ГГГГММ (напр. 202606).";
    }
    if (migrated) {
      return "Статията е мигрирана от Microinvest — периодът ѝ е деклариран оттам.";
    }
    if (hasProtocol) {
      return "Фактурата е в дневниците чрез протокола си по чл. 117 ЗДДС — периодът е на"
          + " протокола (закъснял протокол, ППЗДДС чл. 81 ал. 5).";
    }
    String own = own(invoice);
    if (own == null) {
      return "Фактурата няма дата.";
    }
    if (target.compareTo(own) < 0) {
      return "Към по-ранен период не се отразява: неправилно отразен документ се поправя с писмено"
          + " уведомление до НАП (ЗДДС чл. 126 ал. 3 т. 2).";
    }
    Kind kind = kind(invoice);
    List<String> allowed = allowed(kind, own, issued(invoice), today, filed);
    if (allowed.contains(target)) {
      return null;
    }
    if (filed.test(target)) {
      return "Периодът " + label(target) + " е подаден.";
    }
    if (kind == Kind.PURCHASE) {
      return "Правото на данъчен кредит се упражнява до дванадесетия период след издаването —"
          + " последният е "
          + label(plus(issued(invoice) == null ? own : issued(invoice), PURCHASE_PERIODS))
          + " (ЗДДС чл. 72 ал. 1, чл. 124 ал. 4).";
    }
    if (allowed.contains(own)) {
      return "Документът се отразява в своя период "
          + label(own)
          + (invoice.getOperationTypeSelect() == InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND
              ? " (получено кредитно известие — ЗДДС чл. 124 ал. 5"
              : " (ЗДДС чл. 124 ал. 2")
          + (filed.test(own)
              ? "; подаден, но срокът е до "
                  + DATE.format(deadline(own))
                  + " — подайте отново, чл. 126 ал. 2)."
              : ").");
    }
    return "Пропуснат документ от подаден период се отразява в периода, в който грешката е"
        + " установена — "
        + label(of(today))
        + " (ЗДДС чл. 126 ал. 3 т. 1).";
  }
}

package com.axelor.apps.bglocalization.service.bnb;

import com.axelor.apps.bglocalization.service.accounting.BgAccountingCurrencyPolicy;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * СПБ-6А И СПБ-6Б НА БНБ — ОТ САЛДАТА ПО ПАРТНЬОР КЪМ РЕДОВЕТЕ НА ОБРАЗЕЦА, БЕЗ БАЗА.
 *
 * <p><b>Източникът.</b> Указанията към Наредба № 27 на БНБ, в сила от 01.04.2026 г. (§ 4 ПЗР), за
 * отчетни периоди от 01.01.2026 г. (§ 3 ПЗР): чл. 8, т. 1 и т. 2 и образците по чл. 9, ал. 1, т. 3
 * и т. 4 от Наредбата. Юридическите лица и ЕТ отчитат търговските си вземания (СПБ-6А) и задължения
 * (СПБ-6Б) към чуждестранни лица ТРИМЕСЕЧНО, ако общата сума в началото ИЛИ в края на тримесечието
 * е равна на или надвишава 100 000 евро. Годишна форма за тях няма — СПБ-8 е на физическите лица.
 *
 * <p><b>Знакът решава формата.</b> Салдото по сметка, партньор и валута се дели по знак — както ГОД
 * дели по партньор (BgNsiGodCalculator, DP/KP): дебитното е вземане (6А), кредитното — задължение
 * (6Б). Два партньора с противоположни салда не се нетират; началото и краят се делят поотделно.
 *
 * <p><b>Сумите са в оригиналната валута, в хиляди</b> (колони 7 и 10), закръглени HALF_UP — 1 500
 * USD е 2. Ред, който е нула и в началото, и в края, отпада. Левовете се четат като евро по курса
 * 1,95583 (ЗВЕРБ): от 01.01.2026 г. левът не е валута на вземане.
 *
 * <p><b>Какво счетоводството не знае.</b> Типът на чуждестранното лице (D1–D4), секторът му
 * (S11–S15) и първоначалният матуритет (S/L) нямат поле в партньора. Попълват се стойности по
 * подразбиране (D4; S11 за фирма, S14 за физическо лице; S) и се връщат като такива — Excel-ът ги
 * оцветява за проверка. Колони 8 и 9 (промени без плащане) не се попълват.
 */
public final class BgBnbSpb6 {

  private BgBnbSpb6() {}

  /** 100 000 евро — Указания, чл. 8, т. 1 и т. 2. */
  public static final BigDecimal THRESHOLD_EUR = new BigDecimal("100000");

  /** Над толкова записа — само по електронен път (Наредба № 27, чл. 14, ал. 5). */
  public static final int PAPER_MAX_RECORDS = 5;

  /**
   * F81 — търговски кредити и аванси (Указания, СПБ-6А/6Б, колона 3, т. I): доставено, но неплатено
   * (401, 404, 405, 408 / 411, 414, 415, 418) и авансово платено или получено (402 / 412, 416).
   * Указанията не назовават сметки — съответствието е тълкуване и е „да се потвърди“.
   */
  static final List<String> F81 =
      List.of("401", "402", "404", "405", "408", "411", "412", "414", "415", "416", "418");

  /** F89 — „Други“ вземания и задължения (т. II): 498 и 499. Да се потвърди. */
  static final List<String> F89 = List.of("498", "499");

  /** Свързани лица по сметкоплана — подсказка за D1–D3, не решение. */
  static final List<String> RELATED = List.of("405", "415");

  /** Финансови кредити — СПБ-1 и СПБ-4/СПБ-4А, не СПБ-6А/6Б (Указания, чл. 7, т. 1 и т. 4). */
  static final List<String> LOANS = List.of("15");

  /** Разчети с бюджета и осигуряването — никога вземане от чуждестранно лице. */
  static final List<String> NOT_CLAIMS = List.of("45", "46");

  public static final String RESIDENT_COUNTRY = "BG";

  /** Вид по сметката: F81, F89 или null — най-дългият съвпадащ префикс. */
  public static String kindOf(String account) {
    String best = null;
    int len = -1;
    for (String p : F81) {
      if (account.startsWith(p) && p.length() > len) {
        best = "F81";
        len = p.length();
      }
    }
    for (String p : F89) {
      if (account.startsWith(p) && p.length() > len) {
        best = "F89";
        len = p.length();
      }
    }
    return best;
  }

  static boolean startsWithAny(String account, List<String> prefixes) {
    for (String p : prefixes) {
      if (account.startsWith(p)) {
        return true;
      }
    }
    return false;
  }

  /** Хиляди валутни единици, HALF_UP (Указания, СПБ-6А, колони 7 и 10). */
  public static long thousands(BigDecimal amount) {
    return amount.movePointLeft(3).setScale(0, RoundingMode.HALF_UP).longValueExact();
  }

  // ---------------------------------------------------------------------------
  // Периодът
  // ---------------------------------------------------------------------------

  /** Тримесечие 1–4 или 0 — работен лист за цялата година (не е отчетна форма). */
  public static final class Period {
    public final int year;
    public final int quarter;

    /** Датата на салдото „в началото“ — последният ден преди периода. */
    public final LocalDate opening;

    /** Датата на салдото „в края“. */
    public final LocalDate closing;

    private Period(int year, int quarter) {
      this.year = year;
      this.quarter = quarter;
      LocalDate first = LocalDate.of(year, quarter == 0 ? 1 : 3 * quarter - 2, 1);
      this.opening = first.minusDays(1);
      this.closing = quarter == 0 ? LocalDate.of(year, 12, 31) : first.plusMonths(3).minusDays(1);
    }

    public static Period of(int year, int quarter) {
      if (quarter < 0 || quarter > 4) {
        throw new IllegalArgumentException("Тримесечието е от 1 до 4 (0 — цялата година).");
      }
      if (year < BgAccountingCurrencyPolicy.TRANSITION.getYear()) {
        throw new IllegalArgumentException(
            "За отчетни периоди преди 01.01.2026 г. важи Указание № БНБ-43508 от 25.03.2025 г."
                + " (§ 3 ПЗР на указанията от 01.04.2026 г.) — справката не го поддържа.");
      }
      return new Period(year, quarter);
    }

    public boolean isFiling() {
      return quarter != 0;
    }

    /** До 20-о число на следващия месец; за IV тримесечие — до 25 януари (Приложение 1). */
    public LocalDate deadline() {
      if (quarter == 0) {
        return null;
      }
      return quarter == 4 ? LocalDate.of(year + 1, 1, 25) : closing.plusDays(20);
    }
  }

  // ---------------------------------------------------------------------------
  // Входът
  // ---------------------------------------------------------------------------

  /** Сумата на една група редове във валутата на вземането — и защо е такава. */
  public static final class Amount {
    public final String currency;
    public final BigDecimal value;

    /** Непразно — сумата във валута липсва и групата не влиза в справката. */
    public final String problem;

    Amount(String currency, BigDecimal value, String problem) {
      this.currency = currency;
      this.value = value;
      this.problem = problem;
    }
  }

  /**
   * Салдото на една група (сметка, партньор, валута на статията) към дата, във валутата на
   * вземането. Частите са по ГОД (BgNsiGodLedger): [0] — статиите до 31.12.2025 г., [1] — след
   * това.
   *
   * <ul>
   *   <li>Без валута на статията — отчетната сума: левовете превалутирани, плюс еврото.
   *   <li>BGN — до 2025 г. това е отчетната валута (дебит − кредит), после сумата във валута;
   *       всичко в евро по 1,95583.
   *   <li>EUR — до 2025 г. сумата във валута, после отчетната.
   *   <li>Друга — сумата във валута. Нула при ненулева отчетна сума значи, че валутната сума не е
   *       записана — групата се връща като проблем, не като нула.
   * </ul>
   */
  public static Amount inCurrency(
      String currency, BigDecimal[] currencyAmount, BigDecimal[] bookAmount) {
    if (currency == null) {
      return new Amount("EUR", eur(bookAmount[0], bookAmount[1]), null);
    }
    switch (currency) {
      case "BGN":
        return new Amount("EUR", eur(bookAmount[0].add(currencyAmount[1]), BigDecimal.ZERO), null);
      case "EUR":
        return new Amount("EUR", currencyAmount[0].add(bookAmount[1]), null);
      default:
        BigDecimal value = currencyAmount[0].add(currencyAmount[1]);
        BigDecimal book = bookAmount[0].add(bookAmount[1]);
        if (value.signum() == 0 && book.signum() != 0) {
          return new Amount(
              currency, value, "сумата във " + currency + " липсва, а отчетната е " + book);
        }
        return new Amount(currency, value, null);
    }
  }

  private static BigDecimal eur(BigDecimal bgn, BigDecimal eur) {
    return BgAccountingCurrencyPolicy.component(bgn, eur, "EUR");
  }

  /** Салдото на сметка по партньор и валута в началото и в края; дебит − кредит. */
  public static final class Balance {
    public final String account;
    public final long partnerId;
    public final String partnerName;
    public final boolean individual;

    /** ISO alpha-2 от основния адрес; null — партньорът няма държава. */
    public final String country;

    public final String currency;
    public BigDecimal opening = BigDecimal.ZERO;
    public BigDecimal closing = BigDecimal.ZERO;

    public Balance(
        String account,
        long partnerId,
        String partnerName,
        boolean individual,
        String country,
        String currency) {
      this.account = account;
      this.partnerId = partnerId;
      this.partnerName = partnerName;
      this.individual = individual;
      this.country = country;
      this.currency = currency;
    }

    boolean isZero() {
      return opening.signum() == 0 && closing.signum() == 0;
    }
  }

  // ---------------------------------------------------------------------------
  // Изходът
  // ---------------------------------------------------------------------------

  public enum Form {
    A("СПБ-6А", "вземанията", "вземане", "чл. 9, ал. 1, т. 3", "чл. 8, т. 1"),
    B("СПБ-6Б", "задълженията", "задължение", "чл. 9, ал. 1, т. 4", "чл. 8, т. 2");

    public final String code;
    public final String plural;
    public final String singular;

    /** Образецът — по Наредба № 27. */
    public final String ordinance;

    /** Обхватът и прагът — по указанията от 01.04.2026 г. */
    public final String guideline;

    Form(String code, String plural, String singular, String ordinance, String guideline) {
      this.code = code;
      this.plural = plural;
      this.singular = singular;
      this.ordinance = ordinance;
      this.guideline = guideline;
    }
  }

  /** Един ред на образеца: колони 1–6 и 7, 10 (точни; хилядите — от {@link #thousands}). */
  public static final class Row {
    public final String type;
    public final String sector;
    public final String kind;
    public final String country;
    public final String currency;
    public final String maturity;

    /** Салдото по 405/415 подсказва свързано лице — редът е само на този партньор. */
    public final boolean likelyRelated;

    public BigDecimal opening = BigDecimal.ZERO;
    public BigDecimal closing = BigDecimal.ZERO;
    public final Set<String> partners = new TreeSet<>();

    Row(
        String type,
        String sector,
        String kind,
        String country,
        String currency,
        String maturity,
        boolean likelyRelated) {
      this.type = type;
      this.sector = sector;
      this.kind = kind;
      this.country = country;
      this.currency = currency;
      this.maturity = maturity;
      this.likelyRelated = likelyRelated;
    }

    public long openingK() {
      return thousands(opening);
    }

    public long closingK() {
      return thousands(closing);
    }
  }

  public enum Status {
    /** Равно на или над прага в началото или в края — подава се. */
    FILE,
    /** Под прага и в началото, и в края — не се подава. */
    BELOW,
    /** Липсва курс — прагът не е проверен. */
    UNKNOWN
  }

  public static final class Sheet {
    public final Form form;
    public final List<Row> rows = new ArrayList<>();

    /** Общата сума в евро; null — липсва курс за някоя валута. */
    public BigDecimal openingEur = BigDecimal.ZERO;

    public BigDecimal closingEur = BigDecimal.ZERO;
    public final Set<String> missingRates = new TreeSet<>();

    Sheet(Form form) {
      this.form = form;
    }

    public Status status() {
      if (atOrAbove(openingEur) || atOrAbove(closingEur)) {
        return Status.FILE;
      }
      return missingRates.isEmpty() ? Status.BELOW : Status.UNKNOWN;
    }

    private static boolean atOrAbove(BigDecimal eur) {
      return eur != null && eur.compareTo(THRESHOLD_EUR) >= 0;
    }

    public boolean hasOther() {
      return rows.stream().anyMatch(r -> "F89".equals(r.kind));
    }

    /**
     * Клетките със стойност по подразбиране: тип, сектор и матуритет на всеки ред и описанието на
     * „другите“, ако има ред F89.
     */
    public int defaults() {
      return rows.size() * 3 + (hasOther() ? 1 : 0);
    }
  }

  public static final class Result {
    public final Period period;
    public final Sheet a = new Sheet(Form.A);
    public final Sheet b = new Sheet(Form.B);

    /** Групите, които трябва да види човек — нищо не изчезва мълчаливо. */
    public final List<String> warnings = new ArrayList<>();

    /** Салдата на чуждестранните партньори, които влизат във формите — за сверка. */
    public final List<Balance> details = new ArrayList<>();

    Result(Period period) {
      this.period = period;
    }

    public Sheet sheet(Form form) {
      return form == Form.A ? a : b;
    }
  }

  /**
   * Курсът „евро за една единица“ към дата; null — няма. За EUR винаги 1.
   *
   * <p>Чл. 5, ал. 11 от указанията: салдата — по курса към края на периода.
   */
  public interface Rates {
    BigDecimal eurPerUnit(String currency, LocalDate date);
  }

  // ---------------------------------------------------------------------------
  // Сметката
  // ---------------------------------------------------------------------------

  public static Result build(Period period, List<Balance> balances, Rates rates) {
    Result result = new Result(period);
    Set<Long> related = new HashSet<>();
    for (Balance b : balances) {
      if (!b.isZero() && startsWithAny(b.account, RELATED) && isForeign(b.country)) {
        related.add(b.partnerId);
      }
    }
    Map<String, Row> rowsA = new LinkedHashMap<>();
    Map<String, Row> rowsB = new LinkedHashMap<>();
    for (Balance b : balances) {
      if (b.isZero()) {
        continue;
      }
      String kind = kindOf(b.account);
      if (b.country == null) {
        if (kind != null || startsWithAny(b.account, LOANS)) {
          result.warnings.add(
              "Без държава в основния адрес — не е преценено дали е чуждестранно лице: "
                  + describe(b));
        }
        continue;
      }
      if (!isForeign(b.country)) {
        continue;
      }
      if (kind == null) {
        if (startsWithAny(b.account, LOANS)) {
          result.warnings.add(
              "Финансов кредит — отчита се със СПБ-1 и СПБ-4/СПБ-4А, не тук: " + describe(b));
        } else if (b.account.startsWith("4") && !startsWithAny(b.account, NOT_CLAIMS)) {
          result.warnings.add(
              "Сметката няма вид (F81/F89/F51D) — да се потвърди и при нужда да се добави ръчно: "
                  + describe(b));
        }
        continue;
      }
      result.details.add(b);
      boolean isRelated = related.contains(b.partnerId);
      add(
          rowsA,
          b,
          kind,
          isRelated,
          b.opening.max(BigDecimal.ZERO),
          b.closing.max(BigDecimal.ZERO));
      add(
          rowsB,
          b,
          kind,
          isRelated,
          b.opening.min(BigDecimal.ZERO).negate(),
          b.closing.min(BigDecimal.ZERO).negate());
    }
    fill(result.a, rowsA, period, rates);
    fill(result.b, rowsB, period, rates);
    return result;
  }

  static boolean isForeign(String country) {
    return country != null && !RESIDENT_COUNTRY.equalsIgnoreCase(country);
  }

  private static void add(
      Map<String, Row> rows,
      Balance b,
      String kind,
      boolean isRelated,
      BigDecimal opening,
      BigDecimal closing) {
    if (opening.signum() == 0 && closing.signum() == 0) {
      return;
    }
    String sector = b.individual ? "S14" : "S11";
    String country = b.country.toUpperCase();
    String key =
        String.join("|", "D4", sector, kind, country, b.currency, "S")
            + (isRelated ? "|" + b.partnerId : "");
    Row row =
        rows.computeIfAbsent(
            key, k -> new Row("D4", sector, kind, country, b.currency, "S", isRelated));
    row.opening = row.opening.add(opening);
    row.closing = row.closing.add(closing);
    row.partners.add(b.partnerName);
  }

  private static void fill(Sheet sheet, Map<String, Row> rows, Period period, Rates rates) {
    for (Row row : rows.values()) {
      sheet.openingEur =
          addEur(sheet, sheet.openingEur, row.opening, row.currency, period.opening, rates);
      sheet.closingEur =
          addEur(sheet, sheet.closingEur, row.closing, row.currency, period.closing, rates);
      if (row.openingK() != 0 || row.closingK() != 0) {
        sheet.rows.add(row);
      }
    }
    sheet.rows.sort(
        Comparator.comparing((Row r) -> r.kind)
            .thenComparing(r -> r.type)
            .thenComparing(r -> r.country)
            .thenComparing(r -> r.currency)
            .thenComparing(r -> r.sector)
            .thenComparing(r -> String.join(", ", r.partners)));
  }

  private static BigDecimal addEur(
      Sheet sheet,
      BigDecimal total,
      BigDecimal amount,
      String currency,
      LocalDate date,
      Rates rates) {
    if (amount.signum() == 0) {
      return total;
    }
    BigDecimal rate = "EUR".equals(currency) ? BigDecimal.ONE : rates.eurPerUnit(currency, date);
    if (rate == null) {
      sheet.missingRates.add(currency + " към " + date);
      return null;
    }
    return total == null ? null : total.add(amount.multiply(rate));
  }

  private static String describe(Balance b) {
    return b.partnerName
        + " ("
        + (b.country == null ? "без държава" : b.country)
        + "), сметка "
        + b.account
        + ", "
        + b.currency
        + ": в началото "
        + b.opening.toPlainString()
        + ", в края "
        + b.closing.toPlainString();
  }
}

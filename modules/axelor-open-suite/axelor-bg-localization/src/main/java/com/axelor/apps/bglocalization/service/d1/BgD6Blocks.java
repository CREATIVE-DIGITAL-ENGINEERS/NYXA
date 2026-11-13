package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * БЛОКОВЕТЕ НА РЕДА НА Д6 — до четири, всеки със свой месец, дата, вид плащане и суми. Чиста
 * функция: без база, без Guice.
 *
 * <p><b>ПРАВИЛОТО:</b>
 *
 * <ol>
 *   <li><b>Заплатите</b> — блок с РЪЧНО избрания вид 5 или 6, месецът на периода, т. 18. Сумите са
 *       сборът на декларацията МИНУС вноските на обезщетенията по вид 27.
 *   <li><b>Вноските по вид 27</b> — ОТДЕЛЕН блок с вид 6, АВТОМАТИЧНО: указанието към т. 8 го
 *       изисква за лицата по чл. 9 ал. 3 т. 5 КСО. Месецът е месецът на частта (= периодът), датата
 *       — последният календарен ден от месеца, в който обезщетението е изплатено (указание т. 18).
 *       Различни месеци на изплащане дават различни блокове. Неизплатено → ОТКАЗ с името на
 *       обезщетението.
 *   <li><b>Данъкът</b> — блок с вид 8 за МЕСЕЦА НА УДЪРЖАНЕ: на заплатите — както досега (при вид 6
 *       месецът на т. 18); на обезщетението — месецът на изплащането му. Еднакви месец и дата се
 *       сливат в един блок.
 *   <li><b>Повече от четири блока → ОТКАЗ</b>, който ги изрежда: редът има точно четири.
 * </ol>
 *
 * <p>⚠️ Блок на заплатите НЯМА, когато след изваждането на обезщетенията не остава нито една сума —
 * Д6 само с обезщетение (образецът 01.2025: блок с вид 6 и блок с вид 8). Тогава и ръчният вид не
 * се иска ({@link BgD6Emitter#paymentKindRefusal(BgD6Declaration, BgD6CompensationLedger)}).
 */
public final class BgD6Blocks {

  /** Блоковете на реда — винаги четири във файла. */
  public static final int MAX = 4;

  private BgD6Blocks() {}

  /** Един блок — 13 полета във файла. */
  public static final class Block {
    final LocalDate month;
    final LocalDate date;
    final String kind;
    final Map<String, BigDecimal> sums = new LinkedHashMap<>();
    BigDecimal tax;
    BigDecimal advanceTax;

    Block(LocalDate month, LocalDate date, String kind) {
      this.month = month;
      this.date = date;
      this.kind = kind;
    }

    public LocalDate getMonth() {
      return month;
    }

    public LocalDate getDate() {
      return date;
    }

    public String getKind() {
      return kind;
    }

    public BigDecimal sum(String point) {
      return sums.get(point);
    }

    public BigDecimal getTax() {
      return tax;
    }

    public BigDecimal getAdvanceTax() {
      return advanceTax;
    }

    String describe() {
      return "вид "
          + (kind == null ? "(празен)" : kind)
          + " · "
          + (month == null ? "?" : YearMonth.from(month))
          + " · "
          + (date == null ? "без дата" : date);
    }
  }

  /** Точките на вноските, по реда на блока във файла. */
  static final String[] CONTRIBUTION_POINTS = {
    BgD6Point.T10_DOO,
    BgD6Point.T11_TEACHER_PF,
    BgD6Point.T12_UPF,
    BgD6Point.T13_PPF,
    BgD6Point.T14_HEALTH,
    BgD6Point.T15_GVRS
  };

  /**
   * Има ли блок на заплатите — сума, която НЕ е на обезщетенията по вид 27.
   *
   * <p>⚠️ Без обезщетения отговорът е винаги „да“, и празната декларация пак има блок 1 — както
   * досега.
   */
  public static boolean hasSalaryBlock(BgD6Declaration d, BgD6CompensationLedger ledger) {
    if (ledger == null || ledger.isEmpty()) {
      return true;
    }
    Map<String, BigDecimal> salary = salarySums(d, ledger);
    for (BigDecimal value : salary.values()) {
      if (isPositive(value)) {
        return true;
      }
    }
    return isPositive(salaryTax(d, ledger)) || isPositive(d.getT17AdvanceTax());
  }

  /**
   * Блоковете на реда.
   *
   * @throws IllegalStateException при неизплатено обезщетение или повече от четири блока — отказ с
   *     назована причина, не файл с половин истина
   */
  public static List<Block> of(BgD6Declaration d, BgD6CompensationLedger ledger) {
    if (ledger == null) {
      ledger = BgD6CompensationLedger.none();
    }
    String kind = trim(d.getT81PaymentKind());
    boolean oneKindForBoth = BgD6Point.PAYMENT_KIND_ART41A_FOREST.equals(kind);
    Period period = d.getBgPeriod();
    LocalDate periodStart = period == null ? null : period.getFromDate();
    LocalDate paid = d.getT18PaymentDate();

    List<Block> blocks = new ArrayList<>();
    List<String> unpaid = new ArrayList<>();

    // ═══ 1 · заплатите — ръчният вид ═══
    if (hasSalaryBlock(d, ledger)) {
      Block salary = new Block(periodStart, paid, kind);
      salary.sums.putAll(salarySums(d, ledger));
      if (oneKindForBoth) {
        salary.tax = d.getT16Tax();
        salary.advanceTax = d.getT17AdvanceTax();
      }
      blocks.add(salary);
    }

    // ═══ 2 · обезщетенията по вид 27 — вид 6, по месец на изплащане ═══
    Map<LocalDate, Block> byPaidMonth = new LinkedHashMap<>();
    for (BgCompensationParts.Share share : ledger.getInPeriod()) {
      LocalDate paidOn = share.paidDate();
      if (paidOn == null) {
        unpaid.add(
            "обезщетението „"
                + share.reference()
                + "“ (част "
                + share.getSegment()
                + ") няма дата на изплащане");
        continue;
      }
      LocalDate date = YearMonth.from(paidOn).atEndOfMonth();
      Block block =
          byPaidMonth.computeIfAbsent(
              date, k -> new Block(periodStart, k, BgD6Point.PAYMENT_KIND_LATER_MONTH));
      for (Map.Entry<String, BigDecimal> e : share.d6ByPoint().entrySet()) {
        block.sums.merge(e.getKey(), e.getValue(), BigDecimal::add);
      }
    }
    blocks.addAll(byPaidMonth.values());

    // ═══ 3 · данъкът — вид 8, по месец на удържане ═══
    if (!oneKindForBoth) {
      BigDecimal salaryTax = salaryTax(d, ledger);
      if (isPositive(salaryTax) || isPositive(d.getT17AdvanceTax())) {
        LocalDate taxMonth = BgD6Point.PAYMENT_KIND_LATER_MONTH.equals(kind) ? paid : periodStart;
        addTax(blocks, taxMonth, paid, salaryTax, d.getT17AdvanceTax());
      }
      for (BgD6CompensationLedger.TaxPart part : ledger.getTaxes()) {
        if (!isPositive(part.getAmount())) {
          continue;
        }
        if (part.getPaidDate() == null) {
          unpaid.add(
              "обезщетението „" + part.getReference() + "“ няма дата на изплащане (за данъка)");
          continue;
        }
        LocalDate date = YearMonth.from(part.getPaidDate()).atEndOfMonth();
        addTax(blocks, date, date, part.getAmount(), null);
      }
    }

    if (!unpaid.isEmpty()) {
      throw new IllegalStateException(
          "Д6 не може да се сглоби: "
              + String.join("; ", unpaid)
              + ". ⚠️ Вноските по чл. 9 ал. 3 т. 5 КСО са с вид плащане 6 и датата им е последният"
              + " календарен ден от месеца, в който обезщетението е ИЗПЛАТЕНО (указанията към т. 8 и"
              + " т. 18) — без изплащане няма нито дата, нито месец за данъка. Изплатете фиша, който"
              + " носи обезщетението, и генерирайте отново.");
    }
    if (blocks.size() > MAX) {
      List<String> described = new ArrayList<>();
      for (Block b : blocks) {
        described.add(b.describe());
      }
      throw new IllegalStateException(
          "Д6 иска "
              + blocks.size()
              + " блока, а редът на файла има точно "
              + MAX
              + ": "
              + String.join("; ", described)
              + ". Подайте обезщетенията с различни месеци на изплащане в отделни декларации.");
    }
    return blocks;
  }

  /** Сумите на заплатите — сборът на декларацията минус вноските на обезщетенията. */
  static Map<String, BigDecimal> salarySums(BgD6Declaration d, BgD6CompensationLedger ledger) {
    Map<String, BigDecimal> compensation = ledger.contributionsByPoint();
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    out.put(BgD6Point.T10_DOO, minus(d.getT10Doo(), compensation.get(BgD6Point.T10_DOO)));
    out.put(
        BgD6Point.T11_TEACHER_PF,
        minus(d.getT11TeacherPf(), compensation.get(BgD6Point.T11_TEACHER_PF)));
    out.put(BgD6Point.T12_UPF, minus(d.getT12Upf(), compensation.get(BgD6Point.T12_UPF)));
    out.put(BgD6Point.T13_PPF, minus(d.getT13Ppf(), compensation.get(BgD6Point.T13_PPF)));
    out.put(BgD6Point.T14_HEALTH, minus(d.getT14Health(), compensation.get(BgD6Point.T14_HEALTH)));
    out.put(BgD6Point.T15_GVRS, minus(d.getT15Gvrs(), compensation.get(BgD6Point.T15_GVRS)));
    return out;
  }

  /** Данъкът на заплатите — т. 16 минус данъка на обезщетенията на месеца. */
  static BigDecimal salaryTax(BgD6Declaration d, BgD6CompensationLedger ledger) {
    return minus(d.getT16Tax(), ledger.tax());
  }

  /** Добавя данък в блок с вид 8 за този месец и тази дата — същите се сливат. */
  private static void addTax(
      List<Block> blocks, LocalDate month, LocalDate date, BigDecimal tax, BigDecimal advance) {
    for (Block b : blocks) {
      if (BgD6Point.PAYMENT_KIND_TAX.equals(b.kind)
          && sameMonth(b.month, month)
          && (b.date == null ? date == null : b.date.equals(date))) {
        b.tax = plus(b.tax, tax);
        b.advanceTax = plus(b.advanceTax, advance);
        return;
      }
    }
    Block block = new Block(month, date, BgD6Point.PAYMENT_KIND_TAX);
    block.tax = tax;
    block.advanceTax = advance;
    blocks.add(block);
  }

  private static boolean sameMonth(LocalDate a, LocalDate b) {
    if (a == null || b == null) {
      return a == b;
    }
    return YearMonth.from(a).equals(YearMonth.from(b));
  }

  private static BigDecimal minus(BigDecimal a, BigDecimal b) {
    BigDecimal left = a == null ? BigDecimal.ZERO : a;
    return b == null ? left : left.subtract(b);
  }

  private static BigDecimal plus(BigDecimal a, BigDecimal b) {
    if (a == null) {
      return b;
    }
    return b == null ? a : a.add(b);
  }

  private static boolean isPositive(BigDecimal value) {
    return value != null && value.signum() != 0;
  }

  private static String trim(String value) {
    if (value == null) {
      return null;
    }
    String v = value.trim();
    return v.isEmpty() ? null : v;
  }
}

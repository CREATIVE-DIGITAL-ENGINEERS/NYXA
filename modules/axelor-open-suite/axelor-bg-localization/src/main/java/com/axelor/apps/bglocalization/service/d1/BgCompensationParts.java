package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ОБЕЗЩЕТЕНИЕТО ПО ЧЛ. 222 АЛ. 1 КТ (ВИД ОСИГУРЕН 27) → ЧАСТИ ПО КАЛЕНДАРНИ МЕСЕЦИ. Чиста функция:
 * без база, без Guice.
 *
 * <p><b>ЗАЩО ЧАСТИ.</b> Указание т. 12 към Д1 дава на лицата, „получаващи обезщетение за времето,
 * през което са останали без работа“, СВОЙ код — 27, — а т. 16.1 брои „всички дни с осигурителни
 * вноски на основание чл. 9, ал. 3 от КСО“. Тоест редът вид 27 е за МЕСЕЦА БЕЗ РАБОТА, не за месеца
 * на изплащане, и обезщетение, чийто период минава в следващ месец, дава ПО ЕДИН РЕД В Д1 НА ВСЕКИ
 * МЕСЕЦ. Образецът на Microinvest (прекратяване 23.03.2025, 1077,00): ред за 03.2025 с 307,74 и ред
 * за 04.2025 с 769,26.
 *
 * <p><b>ПРАВИЛАТА (по подадените файлове за 03 и 04.2025):</b>
 *
 * <ol>
 *   <li><b>Периодът</b> е от деня след прекратяването до СЪЩАТА ДАТА следващия месец (23.03 →
 *       24.03–23.04). Календарен месец без нито един работен ден се РЕЖЕ: образецът за 01.2025
 *       (прекратяване 01.01) има период 02.01–01.02, а 01.02.2025 е събота — редът е само 02–31.01.
 *   <li><b>Разделянето е по РАБОТНИТЕ дни на целия период</b>, не по календарните: дневно = сума /
 *       всички работни дни, HALF_UP до стотинка (1077,00 / 21 = 51,29); всяка част освен последната
 *       = дневно × работните дни в месеца (6 × 51,29 = 307,74); ПОСЛЕДНАТА = ОСТАТЪКЪТ (769,26).
 *       Сборът е сумата до стотинка — по устройство, не по случайност.
 *   <li><b>Вноските по фонд</b> на всяка част се смятат от ЗАМРАЗЕНАТА ставка на перото ({@code
 *       bgRate}), HALF_UP до стотинка — поотделно за всеки фонд и платец (§ 2 ал. 2), — а
 *       последната част взима ОСТАТЪКА до сумата на перото. Тоест сборът по месеци е точно онова,
 *       което фишът е начислил.
 *   <li><b>Данъкът на частта</b> е (частта − личните вноски на частта) × ставката; последната е
 *       остатъкът до данъка на обезщетението. Образецът: (307,74 − 27,02) × 10 % = 28,07.
 *   <li><b>Таванът е ОБЩ с вид 01 за лицето, дружеството и месеца</b> (КСО чл. 6 ал. 11: „върху
 *       сбора от осигурителните им доходи, но върху не повече от максималния месечен размер … по
 *       следния ред: 1. доходи от дейности …; 2. доходи от обезщетения, изплащани по Кодекса на
 *       труда …“). Осигурителният доход на частта = min(частта; таванът за месеца − осигурителния
 *       доход по вид 01, но не под 0) — {@link #capped}. Вноските по фонд се смятат от него, т. 21
 *       на реда 27 е той, а т. 31 остава цялата част.
 * </ol>
 *
 * <p><b>⚠️ ФИШЪТ НОСИ ЦЯЛОТО ОБЕЗЩЕТЕНИЕ, ДЕКЛАРАЦИИТЕ — ЧАСТИТЕ.</b> Перото 9xx и вноските 5xx са
 * върху цялата сума (така се начислява и осчетоводява), а частите се извеждат оттук при всяко
 * генериране на Д1 и Д6. Отделен ред на фиша за всяка част би искал маркер „за кой месец“ — а
 * единственият такъв ({@code bgCorrectsPeriod}) е на ретро-делтата и счетоводството го чете като
 * такава ({@code BgPayrollPostingPlan}).
 *
 * <p><b>⚠️ ПЕРАТА НА ВИД 27 СЕ РАЗПОЗНАВАТ ПО ПРЕПРАТКАТА КЪМ ДОКУМЕНТА</b> ({@code
 * BgPaySlipLine.bgCompensation}) — и перото 9xx, и вноските му 5xx я носят. Затова четците на
 * препратката, които търсят ПЕРОТО на обезщетението, гледат само група 9xx ({@link
 * #isCompensationLine}).
 */
public final class BgCompensationParts {

  /** Видът осигурен на обезщетението за оставане без работа — указание т. 12 към Д1. */
  public static final String INSURED_TYPE = "27";

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  private BgCompensationParts() {}

  /** Работните дни между две дати, двете включително — календарът е на базата, не тук. */
  @FunctionalInterface
  public interface WorkingDays {
    int between(LocalDate from, LocalDate to);
  }

  /**
   * МЯСТОТО ПОД ТАВАНА за вид 27 в един месец: таванът (MAX_INSURABLE_INCOME) минус осигурителния
   * доход по вид 01 на лицето в същото дружество. Може да е отрицателно — {@link #capped} го реже
   * до 0.
   *
   * <p>⚠️ {@code null} Е ОТКАЗ, не „без таван“: месец без параметър MAX_INSURABLE_INCOME би
   * осигурил цялата част, а това изглежда точно толкова пресметнато, колкото вярната сума.
   */
  @FunctionalInterface
  public interface Room {
    BigDecimal in(YearMonth month);
  }

  // ═══ РАЗПОЗНАВАНЕТО ═════════════════════════════════════════════════════

  /** Документът е от вид с код 27 за Д1 — днес чл. 222 ал. 1 КТ и чл. 106 ал. 2 ЗДСл. */
  public static boolean isType27(BgCompensation doc) {
    BgCompensationType type = doc == null ? null : doc.getBgCompensationType();
    String code = type == null ? null : type.getD1Code();
    return code != null && INSURED_TYPE.equals(code.trim());
  }

  /**
   * ПЕРОТО НА ОБЕЗЩЕТЕНИЕТО — група 9xx с препратка към документ.
   *
   * <p>⚠️ Групата е част от правилото: вноските 5xx на вид 27 носят СЪЩАТА препратка (оттам знае Д1
   * кои ставки са на реда 27). Четец, който търси перото по препратката без групата, би намерил и
   * вноската — и би я взел за второ обезщетение.
   */
  public static boolean isCompensationLine(BgPaySlipLine line) {
    return line != null
        && line.getBgCompensation() != null
        && BgPaySlipResolver.groupOf(line) == BgPaySlipResolver.GROUP_COMPENSATIONS;
  }

  /** Перо, което е на реда 27 — перото 9xx на обезщетението или вноска 5xx върху него. */
  public static boolean isPartLine(BgPaySlipLine line) {
    if (line == null || !isType27(line.getBgCompensation())) {
      return false;
    }
    int group = BgPaySlipResolver.groupOf(line);
    return group == BgPaySlipResolver.GROUP_COMPENSATIONS
        || group == BgPaySlipResolver.GROUP_CONTRIBUTIONS;
  }

  /** Перата на фиша БЕЗ тези на реда 27 — това вижда редът 01 на същия фиш. */
  public static List<BgPaySlipLine> withoutPartLines(List<BgPaySlipLine> lines) {
    List<BgPaySlipLine> out = new ArrayList<>();
    for (BgPaySlipLine line : safe(lines)) {
      if (!isPartLine(line)) {
        out.add(line);
      }
    }
    return out;
  }

  /** Обезщетение по вид 27, носено от един фиш: документът, перото му и вноските върху него. */
  public static final class Carried {
    private final BgCompensation doc;
    private BgPaySlipLine compensationLine;
    private final List<BgPaySlipLine> contributions = new ArrayList<>();

    Carried(BgCompensation doc) {
      this.doc = doc;
    }

    public BgCompensation getDoc() {
      return doc;
    }

    public BgPaySlipLine getCompensationLine() {
      return compensationLine;
    }

    public List<BgPaySlipLine> getContributions() {
      return contributions;
    }

    /** Сумата на перото — замразената сума на документа. */
    public BigDecimal amount() {
      return compensationLine == null ? BigDecimal.ZERO : compensationLine.getBgAmount();
    }

    /** Личните вноски върху ЦЯЛОТО обезщетение. */
    public BigDecimal personal() {
      BigDecimal total = BigDecimal.ZERO;
      for (BgPaySlipLine line : contributions) {
        if (BgPaySlipResolver.DEBIT.equals(line.getBgSign())) {
          total = total.add(line.getBgAmount());
        }
      }
      return total;
    }

    public String reference() {
      String reference = doc.getReference();
      if (reference != null && !reference.trim().isEmpty()) {
        return reference.trim();
      }
      return doc.getId() == null ? "без номер" : "без номер (id " + doc.getId() + ")";
    }
  }

  /**
   * Обезщетенията по вид 27 на фиша, по реда на перата им. Само собственият период ({@link
   * BgPaySlipResolver#isOwnPeriod}) — ретро-делтата не носи документ.
   */
  public static List<Carried> carried(BgPaySlip slip) {
    Map<Object, Carried> byDoc = new LinkedHashMap<>();
    if (slip == null) {
      return Collections.emptyList();
    }
    for (BgPaySlipLine line : safe(slip.getBgPaySlipLineList())) {
      if (!BgPaySlipResolver.isOwnPeriod(line) || !isPartLine(line)) {
        continue;
      }
      BgCompensation doc = line.getBgCompensation();
      Carried c = byDoc.computeIfAbsent(keyOf(doc), k -> new Carried(doc));
      if (BgPaySlipResolver.groupOf(line) == BgPaySlipResolver.GROUP_COMPENSATIONS) {
        c.compensationLine = line;
      } else {
        c.contributions.add(line);
      }
    }
    List<Carried> out = new ArrayList<>();
    for (Carried c : byDoc.values()) {
      if (c.compensationLine != null) {
        out.add(c);
      }
    }
    return out;
  }

  /**
   * Фишът носи САМО обезщетение по вид 27 — тогава ред 01 за него няма („фиш само с обезщетение →
   * само ред 27“).
   *
   * <p>⚠️ „Само“ значи: извън перата на реда 27 няма нито начисление (облагаемо, осигурително или
   * за БРЗ), нито вноска. Данъкът и плащанията не са твърдение за трудово правоотношение.
   */
  public static boolean onlyCompensation(BgPaySlip slip) {
    if (slip == null || carried(slip).isEmpty()) {
      return false;
    }
    for (BgPaySlipLine line : safe(slip.getBgPaySlipLineList())) {
      if (!BgPaySlipResolver.isOwnPeriod(line) || isPartLine(line)) {
        continue;
      }
      if (BgPaySlipResolver.groupOf(line) == BgPaySlipResolver.GROUP_CONTRIBUTIONS) {
        return false;
      }
      if (BgPaySlipResolver.CREDIT.equals(line.getBgSign())
          && (Boolean.TRUE.equals(line.getBgIsTaxable())
              || Boolean.TRUE.equals(line.getBgIsInsuranceIncome())
              || Boolean.TRUE.equals(line.getBgFormsGrossWage()))) {
        return false;
      }
    }
    return true;
  }

  // ═══ ПЕРИОДЪТ И ЧАСТИТЕ ═════════════════════════════════════════════════

  /** Първият ден без работа — денят след прекратяването. */
  public static LocalDate periodStart(LocalDate terminationDate) {
    return terminationDate == null ? null : terminationDate.plusDays(1);
  }

  /** Последният ден на периода — същата дата следващия месец (23.03 → 23.04; 31.01 → 28.02). */
  public static LocalDate periodEnd(LocalDate terminationDate) {
    return terminationDate == null ? null : terminationDate.plusMonths(1);
  }

  /**
   * Една част — календарният месец, границите ѝ в него, работните дни, сумата и осигурителният
   * доход (сумата под общия таван — {@link #capped}).
   */
  public static final class Segment {
    private final LocalDate from;
    private final LocalDate to;
    private final int workingDays;
    private final BigDecimal amount;
    private final BigDecimal insurable;

    Segment(LocalDate from, LocalDate to, int workingDays, BigDecimal amount) {
      this(from, to, workingDays, amount, amount);
    }

    Segment(
        LocalDate from, LocalDate to, int workingDays, BigDecimal amount, BigDecimal insurable) {
      this.from = from;
      this.to = to;
      this.workingDays = workingDays;
      this.amount = amount;
      this.insurable = insurable;
    }

    public LocalDate getFrom() {
      return from;
    }

    public LocalDate getTo() {
      return to;
    }

    public int getWorkingDays() {
      return workingDays;
    }

    /** Цялата част — облагаемият доход (т. 31). */
    public BigDecimal getAmount() {
      return amount;
    }

    /** Осигурителният доход на частта — под общия таван с вид 01 (т. 21, базата на вноските). */
    public BigDecimal getInsurable() {
      return insurable;
    }

    public YearMonth month() {
      return YearMonth.from(from);
    }

    @Override
    public String toString() {
      return from
          + ".."
          + to
          + " ("
          + workingDays
          + " р.д.) "
          + amount.toPlainString()
          + (insurable.compareTo(amount) == 0 ? "" : ", осигурени " + insurable.toPlainString());
    }
  }

  /**
   * ЧАСТИТЕ НА ОБЕЗЩЕТЕНИЕТО по календарни месеци — по работните дни на целия период.
   *
   * @return празен списък, когато периодът няма нито един работен ден (или липсват входове) —
   *     повикващият го обявява; тихо „една част за всичко“ би декларирало месец, който не е без
   *     работа
   */
  public static List<Segment> split(
      BigDecimal amount, LocalDate terminationDate, WorkingDays workingDays) {
    if (amount == null || amount.signum() <= 0 || terminationDate == null || workingDays == null) {
      return Collections.emptyList();
    }
    LocalDate start = periodStart(terminationDate);
    LocalDate end = periodEnd(terminationDate);

    // ⚠️ МЕСЕЦ БЕЗ РАБОТЕН ДЕН СЕ РЕЖЕ, И ТОВА Е ПРАВИЛО, НЕ ОПТИМИЗАЦИЯ: редът 27 декларира дни с
    // вноски (т. 16.1), а част без нито един такъв ден е ред с нула дни и нула сума.
    List<LocalDate[]> bounds = new ArrayList<>();
    List<Integer> days = new ArrayList<>();
    int total = 0;
    for (YearMonth m = YearMonth.from(start);
        !m.isAfter(YearMonth.from(end));
        m = m.plusMonths(1)) {
      LocalDate from = start.isAfter(m.atDay(1)) ? start : m.atDay(1);
      LocalDate to = end.isBefore(m.atEndOfMonth()) ? end : m.atEndOfMonth();
      int wd = workingDays.between(from, to);
      if (wd <= 0) {
        continue;
      }
      bounds.add(new LocalDate[] {from, to});
      days.add(wd);
      total += wd;
    }
    if (total == 0) {
      return Collections.emptyList();
    }

    // ⚠️ ДНЕВНОТО СЕ ЗАКРЪГЛЯ ПЪРВО, И ТОВА Е ЧАСТ ОТ ПРАВИЛОТО: 1077,00 / 21 = 51,2857… → 51,29, а
    // мартенската част е 6 × 51,29 = 307,74 — не 6 / 21 × 1077,00 = 307,71.
    BigDecimal daily = amount.divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    List<Segment> out = new ArrayList<>();
    BigDecimal assigned = BigDecimal.ZERO;
    for (int i = 0; i < bounds.size(); i++) {
      BigDecimal part =
          i == bounds.size() - 1
              ? amount.subtract(assigned)
              : daily.multiply(BigDecimal.valueOf(days.get(i))).setScale(2, RoundingMode.HALF_UP);
      assigned = assigned.add(part);
      out.add(new Segment(bounds.get(i)[0], bounds.get(i)[1], days.get(i), part));
    }
    return out;
  }

  // ═══ ОБЩИЯТ ТАВАН С ВИД 01 (КСО ЧЛ. 6 АЛ. 11) ════════════════════════════

  /**
   * ЧАСТИТЕ ПОД ОБЩИЯ ТАВАН: осигурителният доход на всяка = min(частта; мястото под тавана, но не
   * под 0). Сумата (т. 31) не се пипа — таванът е на осигурителния доход, не на облагаемия.
   *
   * <p>⚠️ РЕДЪТ Е ЗАКОНЪТ, НЕ ИЗБОР: ал. 11 т. 1 (дейности — вид 01) се изчерпва ПРЕДИ т. 2
   * (обезщетения по КТ — вид 27). Тоест вид 01 не се реже заради обезщетението, а обезщетението
   * получава остатъка до тавана.
   *
   * @throws IllegalStateException месец без таван — виж {@link Room}
   */
  public static List<Segment> capped(List<Segment> segments, Room room) {
    List<Segment> out = new ArrayList<>();
    for (Segment s : segments) {
      BigDecimal left = roomIn(room, s.month());
      BigDecimal insurable = s.getAmount().min(left.max(BigDecimal.ZERO));
      out.add(new Segment(s.getFrom(), s.getTo(), s.getWorkingDays(), s.getAmount(), insurable));
    }
    return out;
  }

  /**
   * ОСИГУРИТЕЛНИЯТ ДОХОД НА ЦЯЛОТО ОБЕЗЩЕТЕНИЕ — базата, върху която фишът начислява вноските по
   * вид 27: сборът на частите под общия таван.
   *
   * <p>⚠️ КАЛЕНДАРЪТ СЕ ПИТА САМО КОГАТО ТАВАНЪТ РЕЖЕ. Има ли във всеки месец на периода място за
   * цялото обезщетение, базата е цялата сума и разделянето не я мени — а фишът не бива да чака
   * календара на следващата година, за да начисли вноски, които не зависят от него.
   *
   * @throws IllegalStateException месец без таван ({@link Room}) или непокрит календар
   */
  public static BigDecimal insurableOf(
      BigDecimal amount, LocalDate terminationDate, WorkingDays workingDays, Room room) {
    if (amount == null || amount.signum() <= 0 || terminationDate == null) {
      return amount == null ? BigDecimal.ZERO : amount;
    }
    YearMonth last = YearMonth.from(periodEnd(terminationDate));
    boolean bites = false;
    for (YearMonth m = YearMonth.from(periodStart(terminationDate));
        !m.isAfter(last);
        m = m.plusMonths(1)) {
      if (roomIn(room, m).compareTo(amount) < 0) {
        bites = true;
      }
    }
    if (!bites) {
      return amount;
    }
    List<Segment> segments = split(amount, terminationDate, workingDays);
    // Период без работен ден: базата остава цялата сума, а Д1 го обявява (D1_T27_NO_PARTS).
    return segments.isEmpty() ? amount : insurableTotal(capped(segments, room));
  }

  /** Сборът на осигурителния доход на частите. */
  public static BigDecimal insurableTotal(List<Segment> segments) {
    BigDecimal total = BigDecimal.ZERO;
    for (Segment s : segments) {
      total = total.add(s.getInsurable());
    }
    return total;
  }

  /**
   * ОСИГУРИТЕЛНИЯТ ДОХОД ПО ВИД 01 НА ФИША — базата ({@code bgQuantity}) на обикновените му вноски,
   * без тези на вид 27 и без особените здравни бази (болничен, неплатен отпуск). Без такива пера —
   * 0.
   *
   * <p>⚠️ БАЗАТА НА ВНОСКИТЕ, НЕ СБОРЪТ НА НАЧИСЛЕНИЯТА: тя вече е минала през МОД пода и през
   * тавана на вид 01, т.е. е точно „осигурителният доход“ от т. 1 на ал. 11. Различни бази на един
   * фиш потвърждаването отказва ({@code BgPaySlipService#contributionBaseMismatch}); тук се взима
   * по-голямата — по-малко място, не повече.
   */
  public static BigDecimal regularBase(BgPaySlip slip) {
    BigDecimal base = BigDecimal.ZERO;
    for (BgPaySlipLine line : safe(slip == null ? null : slip.getBgPaySlipLineList())) {
      if (BgPaySlipResolver.isOwnPeriod(line)
          && BgPaySlipResolver.groupOf(line) == BgPaySlipResolver.GROUP_CONTRIBUTIONS
          && !isPartLine(line)
          && BgD1ContributionAggregator.BASE_STANDARD.equals(
              BgD1ContributionAggregator.healthBaseOf(line))
          && line.getBgQuantity().compareTo(base) > 0) {
        base = line.getBgQuantity();
      }
    }
    return base;
  }

  private static BigDecimal roomIn(Room room, YearMonth month) {
    BigDecimal left = room == null ? null : room.in(month);
    if (left == null) {
      throw new IllegalStateException(
          "няма максимален месечен осигурителен доход (MAX_INSURABLE_INCOME) за "
              + month
              + " — общият таван на вид 01 и вид 27 (КСО чл. 6 ал. 11) не може да се приложи");
    }
    return left;
  }

  // ═══ ВНОСКИТЕ И ДАНЪКЪТ НА ЧАСТТА ════════════════════════════════════════

  /** Вноска на един фонд и платец в една част — перото-източник (ставката) и сумата за частта. */
  public static final class FundPart {
    private final BgPaySlipLine source;
    private final BigDecimal amount;

    FundPart(BgPaySlipLine source, BigDecimal amount) {
      this.source = source;
      this.amount = amount;
    }

    public BgPaySlipLine getSource() {
      return source;
    }

    public BigDecimal getAmount() {
      return amount;
    }
  }

  /** Една част, готова за Д1 и Д6 — сумата, вноските по фонд, личните вноски и данъкът. */
  public static final class Share {
    private final Carried carried;
    private final Segment segment;
    private final List<FundPart> funds;
    private final BigDecimal tax;

    Share(Carried carried, Segment segment, List<FundPart> funds, BigDecimal tax) {
      this.carried = carried;
      this.segment = segment;
      this.funds = funds;
      this.tax = tax;
    }

    public BgCompensation getDoc() {
      return carried.getDoc();
    }

    public String reference() {
      return carried.reference();
    }

    public Segment getSegment() {
      return segment;
    }

    public List<FundPart> getFunds() {
      return funds;
    }

    public BigDecimal getTax() {
      return tax;
    }

    /** Перата-източници — ставките за т. 22 … т. 30 на реда 27. */
    public List<BgPaySlipLine> sourceLines() {
      List<BgPaySlipLine> out = new ArrayList<>();
      for (FundPart f : funds) {
        out.add(f.getSource());
      }
      return out;
    }

    public BigDecimal personal() {
      BigDecimal total = BigDecimal.ZERO;
      for (FundPart f : funds) {
        if (BgPaySlipResolver.DEBIT.equals(f.getSource().getBgSign())) {
          total = total.add(f.getAmount());
        }
      }
      return total;
    }

    /** „точка от Д6 → сбор в левове“ за вноските на частта. ⚠️ Без данъка — той е на месеца му. */
    public Map<String, BigDecimal> d6ByPoint() {
      Map<String, BigDecimal> out = new LinkedHashMap<>();
      for (FundPart f : funds) {
        String point = BgD6Point.forFund(BgD6Aggregator.fundCodeOf(f.getSource()));
        if (point != null) {
          out.merge(point, f.getAmount(), BigDecimal::add);
        }
      }
      return out;
    }

    /** Платено ли е и кога — за датата на блока с код 6 в Д6. */
    public LocalDate paidDate() {
      return carried.getDoc().getBgPaidDate();
    }
  }

  /**
   * ЧАСТИТЕ НА ЕДНО ОБЕЗЩЕТЕНИЕ, с вноските и данъка им.
   *
   * @param taxTotal данъкът на ЦЯЛОТО обезщетение — виж {@link #taxTotals}
   * @param taxRatePct ставката на данъка от перото 7xx; {@code null} → данъкът на частите се дели
   *     пропорционално на облагаемата им основа
   */
  public static List<Share> shares(
      Carried carried, List<Segment> segments, BigDecimal taxTotal, BigDecimal taxRatePct) {
    List<Share> out = new ArrayList<>();
    if (carried == null || segments == null || segments.isEmpty()) {
      return out;
    }
    BigDecimal amount = carried.amount();
    BigDecimal personalWhole = carried.personal();
    BigDecimal tax = taxTotal == null ? BigDecimal.ZERO : taxTotal;

    Map<BgPaySlipLine, BigDecimal> assignedByLine = new LinkedHashMap<>();
    BigDecimal assignedTax = BigDecimal.ZERO;
    for (int i = 0; i < segments.size(); i++) {
      Segment segment = segments.get(i);
      boolean last = i == segments.size() - 1;

      List<FundPart> funds = new ArrayList<>();
      BigDecimal personal = BigDecimal.ZERO;
      for (BgPaySlipLine line : carried.getContributions()) {
        BigDecimal before = assignedByLine.getOrDefault(line, BigDecimal.ZERO);
        // ⚠️ ОТ ОСИГУРИТЕЛНИЯ ДОХОД НА ЧАСТТА, НЕ ОТ СУМАТА: под общия таван двете се разминават.
        BigDecimal part =
            last
                ? line.getBgAmount().subtract(before)
                : percentOf(segment.getInsurable(), line.getBgRate());
        assignedByLine.put(line, before.add(part));
        funds.add(new FundPart(line, part));
        if (BgPaySlipResolver.DEBIT.equals(line.getBgSign())) {
          personal = personal.add(part);
        }
      }

      BigDecimal partTax;
      if (last) {
        partTax = tax.subtract(assignedTax);
      } else if (taxRatePct != null && taxRatePct.signum() > 0) {
        partTax = percentOf(segment.getAmount().subtract(personal), taxRatePct);
      } else {
        BigDecimal wholeBase = amount.subtract(personalWhole);
        partTax =
            wholeBase.signum() <= 0
                ? BigDecimal.ZERO
                : tax.multiply(segment.getAmount().subtract(personal))
                    .divide(wholeBase, 2, RoundingMode.HALF_UP);
      }
      assignedTax = assignedTax.add(partTax);
      out.add(new Share(carried, segment, funds, partTax));
    }
    return out;
  }

  /**
   * Частите на едно обезщетение от фиша — периодът, общият таван, вноските и данъкът, в една
   * стъпка.
   */
  public static List<Share> sharesOf(
      BgPaySlip slip, Carried carried, List<Carried> all, WorkingDays workingDays, Room room) {
    List<Segment> segments =
        capped(split(carried.amount(), carried.getDoc().getBgEventDate(), workingDays), room);
    return shares(carried, segments, taxTotals(slip, all).get(carried.getDoc()), taxRateOf(slip));
  }

  /**
   * Базата, върху която фишът е начислил вноските по вид 27 ({@code bgQuantity} на перата им), или
   * {@code null}, ако перо с база няма (таванът е изчерпан и пера няма, или фишът е заварен).
   */
  public static BigDecimal chargedBase(Carried carried) {
    for (BgPaySlipLine line : carried.getContributions()) {
      if (line.getBgQuantity().signum() != 0) {
        return line.getBgQuantity();
      }
    }
    return null;
  }

  /**
   * ДАНЪКЪТ НА ВСЯКО ОБЕЗЩЕТЕНИЕ ПО ВИД 27 на фиша, и остатъкът за реда 01.
   *
   * <p><b>Правилото:</b> редът 27 получава (обезщетение − личните вноски върху него) × ставката,
   * редът 01 — остатъка до данъка на фиша. Сборът на редовете Е данъкът на фиша до стотинка.
   *
   * <p>⚠️ ФИШ САМО С ОБЕЗЩЕТЕНИЕ: последното обезщетение взима остатъка до данъка на фиша, а не
   * своята формула — иначе едно HALF_UP на друго ниво оставя стотинка без ред.
   */
  public static Map<BgCompensation, BigDecimal> taxTotals(BgPaySlip slip, List<Carried> all) {
    Map<BgCompensation, BigDecimal> out = new LinkedHashMap<>();
    if (slip == null || all == null || all.isEmpty()) {
      return out;
    }
    BigDecimal slipTax = slip.getBgTaxAmount();
    BigDecimal rate = taxRateOf(slip);
    boolean only = onlyCompensation(slip);
    BigDecimal assigned = BigDecimal.ZERO;
    for (int i = 0; i < all.size(); i++) {
      Carried c = all.get(i);
      BigDecimal tax;
      if (only && i == all.size() - 1) {
        tax = slipTax.subtract(assigned);
      } else if (rate != null && rate.signum() > 0) {
        tax = percentOf(c.amount().subtract(c.personal()), rate);
      } else {
        tax = proportionalTax(slip, c, slipTax);
      }
      assigned = assigned.add(tax);
      out.put(c.getDoc(), tax);
    }
    return out;
  }

  /** Данъкът на фиша минус данъка на обезщетенията по вид 27 — т. 31а на реда 01. */
  public static BigDecimal taxWithoutParts(BgPaySlip slip) {
    BigDecimal slipTax = slip == null ? BigDecimal.ZERO : slip.getBgTaxAmount();
    for (BigDecimal tax : taxTotals(slip, carried(slip)).values()) {
      slipTax = slipTax.subtract(tax);
    }
    return slipTax;
  }

  /** Ставката на данъка — от перото 7xx (DEBIT), което я носи; {@code null} ако няма. */
  public static BigDecimal taxRateOf(BgPaySlip slip) {
    for (BgPaySlipLine line : safe(slip == null ? null : slip.getBgPaySlipLineList())) {
      if (BgPaySlipResolver.isOwnPeriod(line)
          && BgPaySlipResolver.groupOf(line) == BgPaySlipResolver.GROUP_TAX
          && BgPaySlipResolver.DEBIT.equals(line.getBgSign())
          && line.getBgRate().signum() > 0) {
        return line.getBgRate();
      }
    }
    return null;
  }

  /** Без ставка на перото: делът на обезщетението в облагаемата основа на фиша. */
  private static BigDecimal proportionalTax(BgPaySlip slip, Carried c, BigDecimal slipTax) {
    List<BgPaySlipLine> lines = slip.getBgPaySlipLineList();
    BigDecimal personalAll = BigDecimal.ZERO;
    for (BgPaySlipLine line : safe(lines)) {
      if (BgPaySlipResolver.isOwnPeriod(line)
          && BgPaySlipResolver.groupOf(line) == BgPaySlipResolver.GROUP_CONTRIBUTIONS
          && BgPaySlipResolver.DEBIT.equals(line.getBgSign())) {
        personalAll = personalAll.add(line.getBgAmount());
      }
    }
    BigDecimal base = BgPaySlipResolver.accruedTaxableTotal(lines).subtract(personalAll);
    if (base.signum() <= 0) {
      return BigDecimal.ZERO;
    }
    return slipTax
        .multiply(c.amount().subtract(c.personal()))
        .divide(base, 2, RoundingMode.HALF_UP);
  }

  private static BigDecimal percentOf(BigDecimal amount, BigDecimal ratePct) {
    if (amount == null || ratePct == null) {
      return BigDecimal.ZERO;
    }
    return amount.multiply(ratePct).divide(HUNDRED, 2, RoundingMode.HALF_UP);
  }

  private static Object keyOf(BgCompensation doc) {
    return doc.getId() != null ? (Object) doc.getId() : doc;
  }

  private static List<BgPaySlipLine> safe(List<BgPaySlipLine> lines) {
    return lines == null ? Collections.emptyList() : lines;
  }
}

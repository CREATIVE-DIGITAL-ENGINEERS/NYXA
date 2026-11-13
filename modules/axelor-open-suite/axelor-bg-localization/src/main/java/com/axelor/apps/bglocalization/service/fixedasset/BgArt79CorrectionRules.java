package com.axelor.apps.bglocalization.service.fixedasset;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Еднократна корекция на ползван данъчен кредит за ДЪЛГОТРАЙНИ АКТИВИ — <b>чл. 79 ал. 3 ЗДДС</b>,
 * като чиста функция.
 *
 * <p>Сверено дословно на 11.09.2026 г. срещу ДВА независими източника, които съвпадат по същество
 * (разликите са само типографски — lex.bg сваля {@code <sub>}, тъй че „К₀“ излиза като „К 0“):
 * текстът на ЗДДС до ДВ бр. 115 от 30.12.2025 г. и консолидираният текст на lex.bg — до ДВ бр. 69
 * от 31.07.2026 г. Тоест бр. 69/2026 НЕ е пипал чл. 79: потвърдено от двете страни, а не прието
 * наум.
 *
 * <p><b>Чиста функция, защото правилото иска свидетел с ЧИСЛА.</b> Модулът няма mock-подпора в
 * тестовете; услугата отгоре само подава фактите и прибира резултата. Същият довод, по който са
 * изнесени {@link BgAssetWriteOffRules} и {@code BgSubsequentCostRules}.
 *
 * <p><b>Тук НЕ се преоткрива чл. 80 ал. 2.</b> Субстантивните освобождавания (доказано унищожаване,
 * непреодолима сила, брак) висят под условие за ДОКАЗВАНЕ, каквото този слой не вижда — те се
 * решават от причината за извеждане и от гардрейла на {@code
 * BgFixedAssetWriteOffManagementRepository}. Калкулаторът смята само когато корекционно събитие
 * вече е обявено. Единственото ограничение по чл. 80, което е ИЗЧИСЛИМО, е давността по ал. 1 т.
 * 3/т. 4 — и тя е тук.
 *
 * <p>Реквизитите, които протоколът по <b>ППЗДДС чл. 66 ал. 3</b> иска, излизат от {@link Result}:
 * т. 7 брой години, т. 8 пропорция, т. 9 коефициент, т. 10 размер на дължимия данък.
 */
public final class BgArt79CorrectionRules {

  private BgArt79CorrectionRules() {}

  /**
   * Трите точки на чл. 79 ал. 3 — те и само те решават знаменателя.
   *
   * <p>⚠️ Класификацията е ИЗРИЧЕН факт, а не извод от категорията по чл. 55 ЗКПО. Категория I
   * („масивни сгради, включително инвестиционни имоти, съоръжения, предавателни устройства…“) е
   * близка, но не е същото понятие като „недвижим имот“ по ЗДДС. Догадката тук сменя 1/20 с 1/5,
   * тоест умножава данъка по четири — затова полето е задължително, а не изведено.
   */
  public enum AssetKind {
    /** Ал. 3 т. 1 — „за недвижимите имоти“: 1/20, 20-годишен срок. */
    IMMOVABLE_PROPERTY(20, "т. 1", BgAssetRemoval.VatGround.ART80_1_3),

    /** Ал. 3 т. 2 — „за останалите стоки“: 1/5, 5-годишен срок. */
    GOODS(5, "т. 2", BgAssetRemoval.VatGround.ART80_1_4),

    /** Ал. 3 т. 3 — „за услуги“: 1/5, 5-годишен срок, БЕЗ буква „в“. */
    SERVICE(5, "т. 3", BgAssetRemoval.VatGround.ART80_1_4);

    private final int periodYears;
    private final String point;
    private final String timeBarGround;

    AssetKind(int periodYears, String point, String timeBarGround) {
      this.periodYears = periodYears;
      this.point = point;
      this.timeBarGround = timeBarGround;
    }

    /** n от формулата: 20 за недвижим имот, 5 за останалите стоки и за услугите. */
    public int periodYears() {
      return periodYears;
    }

    /** Точката на ал. 3, както се изписва в протокола. */
    public String point() {
      return point;
    }

    /** Основанието по чл. 80 ал. 1 при изтекъл срок: т. 3 за имоти, т. 4 за останалите. */
    public String timeBarGround() {
      return timeBarGround;
    }
  }

  /**
   * Видът данъчен кредит при придобиването — буквата на точката.
   *
   * <p>Буквите не са козметика: те решават кои множители влизат във формулата. Подразбира се от
   * {@code InvoiceLine.bgVatCreditType}, но пропорционалният (чл. 71а/71б) няма свой код там и се
   * маркира на ръка.
   */
  public enum CreditType {
    /** Буква „а“ — приспаднат ИЗЦЯЛО. Чл. 79в т. 3: К₀ е 1, коеф. по чл. 73 не се взема предвид. */
    FULL("б. „а“"),

    /** Буква „б“ — приспаднат ЧАСТИЧНО, с коефициента по чл. 73. */
    PARTIAL("б. „б“"),

    /** Буква „в“ — приспаднат ПРОПОРЦИОНАЛНО на степента на използване (чл. 71а/71б). */
    PROPORTIONAL("б. „в“"),

    /** Не е приспадан кредит (чл. 70). Чл. 79в т. 5: К₀ е 0 — няма какво да се връща. */
    NONE("");

    private final String letter;

    CreditType(String letter) {
      this.letter = letter;
    }

    public String letter() {
      return letter;
    }
  }

  /**
   * Фактите от ПРИДОБИВАНЕТО плюс годината на събитието.
   *
   * <p>Акцесорите нарочно са без префикс {@code get}: това не е Axelor entity и тук няма
   * подразбиращи се стойности — {@code null} значи „не е въведено“ и точно това трябва да се
   * различава от нула.
   */
  public static final class Input {

    private final AssetKind kind;
    private final CreditType creditType;
    private final BigDecimal vatCharged;
    private final int creditYear;
    private final Integer firstUseYear;
    private final int eventYear;
    private final BigDecimal k0;
    private final BigDecimal proportion;

    public Input(
        AssetKind kind,
        CreditType creditType,
        BigDecimal vatCharged,
        int creditYear,
        Integer firstUseYear,
        int eventYear,
        BigDecimal k0,
        BigDecimal proportion) {
      this.kind = kind;
      this.creditType = creditType;
      this.vatCharged = vatCharged;
      this.creditYear = creditYear;
      this.firstUseYear = firstUseYear;
      this.eventYear = eventYear;
      this.k0 = k0;
      this.proportion = proportion;
    }

    public AssetKind kind() {
      return kind;
    }

    public CreditType creditType() {
      return creditType;
    }

    /**
     * НДДС — „начисленият ДДС при производството, придобиването или вноса на стоката, включително
     * при придобиването или изграждането на недвижим имот, или при получаване на услугата“.
     *
     * <p>⚠️ Това е БРУТНИЯТ начислен данък, НЕ „ползваният данъчен кредит“. Ползваният кредит е
     * мярката на ал. 1 и ал. 2 (за неДМА); при ал. 3 К₀ и ПрНИД₀ влизат ОТДЕЛНО във формулата.
     * Подаден нетният кредит при частичен К₀ би занижил данъка с квадрата на коефициента.
     */
    public BigDecimal vatCharged() {
      return vatCharged;
    }

    /** Годината, през която е УПРАЖНЕНО правото на данъчен кредит — началото на n-годишния срок. */
    public int creditYear() {
      return creditYear;
    }

    /** Годината на фактическото използване — алтернативно начало, САМО за недвижим имот. */
    public Integer firstUseYear() {
      return firstUseYear;
    }

    /** Годината, през която е настъпило обстоятелството по ал. 1 и 2. */
    public int eventYear() {
      return eventYear;
    }

    /** К₀ — коефициентът по чл. 73 за годината, през която е упражнено правото. */
    public BigDecimal k0() {
      return k0;
    }

    /** ПрНИД₀ — пропорцията на използване за независима икономическа дейност в същата година. */
    public BigDecimal proportion() {
      return proportion;
    }
  }

  /**
   * Изчисленото — заедно с множителите, които са влезли в него.
   *
   * <p>Множителите се връщат, а не се изхвърлят, защото ППЗДДС чл. 66 ал. 3 иска протоколът да
   * съдържа точно тях. Резултат само със сумата би направил протокола непопълним.
   */
  public static final class Result {

    private final BigDecimal amount;
    private final int years;
    private final int periodYears;
    private final int periodStartYear;
    private final BigDecimal appliedK0;
    private final BigDecimal appliedProportion;
    private final String legalGround;
    private final String timeBarGround;

    Result(
        BigDecimal amount,
        int years,
        int periodYears,
        int periodStartYear,
        BigDecimal appliedK0,
        BigDecimal appliedProportion,
        String legalGround,
        String timeBarGround) {
      this.amount = amount;
      this.years = years;
      this.periodYears = periodYears;
      this.periodStartYear = periodStartYear;
      this.appliedK0 = appliedK0;
      this.appliedProportion = appliedProportion;
      this.legalGround = legalGround;
      this.timeBarGround = timeBarGround;
    }

    /** ДД — дължимият данък, закръглен до стотинки (мащабът на дневника и декларацията). */
    public BigDecimal amount() {
      return amount;
    }

    /** БГ — броят на съответните години (ППЗДДС чл. 66 ал. 3 т. 7). */
    public int years() {
      return years;
    }

    /** n — 20 или 5, според точката на ал. 3. */
    public int periodYears() {
      return periodYears;
    }

    /** Годината, от началото на която тече n-годишният срок. */
    public int periodStartYear() {
      return periodStartYear;
    }

    /** К₀, както е приложен (чл. 79в вече е наложен: 1 при пълен, 0 при чл. 70). */
    public BigDecimal appliedK0() {
      return appliedK0;
    }

    /** ПрНИД₀, както е приложен; {@code null}, когато формулата не го ползва. */
    public BigDecimal appliedProportion() {
      return appliedProportion;
    }

    /** Основанието за протокола — напр. „чл. 79 ал. 3 т. 2 б. „б““. */
    public String legalGround() {
      return legalGround;
    }

    /**
     * Основанието по чл. 80 ал. 1, когато срокът е изтекъл и корекция не се дължи: {@code
     * ART80_1_3} за недвижим имот, {@code ART80_1_4} за останалите. {@code null}, докато срокът
     * тече.
     */
    public String timeBarGround() {
      return timeBarGround;
    }

    /** Дължи ли се изобщо корекция. */
    public boolean isDue() {
      return amount.signum() != 0;
    }
  }

  // ------------------------------------------------------------------ чл. 79 ал. 3

  /**
   * ДД по чл. 79 ал. 3 — дължимият данък при унищожаване, липси, брак или последваща доставка без
   * право на приспадане.
   *
   * <p>Дословно ал. 3 и трите ѝ точки (n = 20 за т. 1; 5 за т. 2 и т. 3):
   *
   * <pre>
   *   б. „а“ (изцяло):         ДД = НДДС x 1/n x БГ
   *   б. „б“ (частично):       ДД = НДДС x 1/n x К0 x БГ
   *   б. „в“ (пропорционално): ДД = НДДС x 1/n x ПрНИД0 x К0 x БГ
   * </pre>
   *
   * <p>⚠️ Т. 3 (услуги) НЯМА буква „в“ — законът дава само а) и б). Пропорционална услуга се
   * отказва, вместо да се смята по чужда формула.
   *
   * @throws IllegalArgumentException при комбинация, която чл. 79в изключва
   */
  public static Result correction(Input in) {
    validate(in);

    int n = in.kind().periodYears();
    int start = periodStartYear(in);
    int years = remainingYears(start, n, in.eventYear());

    BigDecimal k0 = effectiveK0(in);
    BigDecimal proportion = in.creditType() == CreditType.PROPORTIONAL ? in.proportion() : null;

    if (years == 0) {
      // Чл. 80 ал. 1 т. 3/т. 4 — срокът е изтекъл, корекция изобщо не се извършва.
      return new Result(
          zero(), 0, n, start, k0, proportion, legalGround(in), in.kind().timeBarGround());
    }

    // Множителите се трупат в числителя и се дели ВЕДНЪЖ накрая: 1/n по средата би внесло
    // закръгляне в множител, вместо в резултата.
    BigDecimal factor = k0;
    if (proportion != null) {
      factor = factor.multiply(proportion);
    }
    BigDecimal numerator = nz(in.vatCharged()).multiply(factor).multiply(BigDecimal.valueOf(years));

    return new Result(scaled(numerator, n), years, n, start, k0, proportion, legalGround(in), null);
  }

  // ------------------------------------------------------------------ чл. 79 ал. 5

  /**
   * ДК по <b>чл. 79 ал. 5</b> — неползваният при придобиването данъчен кредит, който лицето има
   * право да ПРИСПАДНЕ, когато извърши облагаема доставка на актива.
   *
   * <p>Същата граница активи→ДДС с обратен знак: при ал. 3 частичният кредит се връща в бюджета,
   * защото активът напуска облагаемата дейност; при ал. 5 неползваната част се възстановява, защото
   * активът излиза с ОБЛАГАЕМА доставка, тоест задним число е бил използван за нея.
   *
   * <p>Дословно формулите (n = 20 за т. 1; 5 за т. 2 и т. 3):
   *
   * <pre>
   *   частично (чл. 73):      ДК = НДДС x 1/n x (1 − К0) x БГ
   *   пропорционално (71а/б): ДК = НДДС x 1/n x (1 − ПрНИД0 x К0) x БГ
   * </pre>
   *
   * <p>⚠️ <b>Ал. 5 НЯМА вариант за пълен кредит</b> — и това не е пропуск на закона: приспаднал
   * изцяло, лицето няма неползвана част. Затова {@link CreditType#FULL} връща нула, вместо да
   * хвърля: това е отговор, а не невалиден вход.
   *
   * <p>⚠️ <b>Ал. 6 НЕ е тук.</b> Тя дава същото право на лице, което не е приспаднало кредит на
   * основание чл. 70, но ПОСЛЕ е приспаднало по чл. 79а или 79б — а годишните корекции по 79а/79б
   * този модул още не моделира. Затова {@link CreditType#NONE} също връща нула: в света на този
   * модул предходна корекция няма. Когато 79а/79б влязат, ал. 6 идва с тях.
   *
   * <p>Упражняването е по ал. 7 — „в данъчния период, през който е извършена доставката …, или в
   * един от следващите 12 данъчни периода, чрез съставяне на протокол … и отразяването му в
   * <b>дневника за покупки</b> и справка-декларацията“. Подразбирането е периодът на доставката.
   *
   * @throws IllegalArgumentException при комбинация, която чл. 79в изключва
   */
  public static Result deduction(Input in) {
    validate(in);

    int n = in.kind().periodYears();
    int start = periodStartYear(in);
    int years = remainingYears(start, n, in.eventYear());

    BigDecimal k0 = effectiveK0(in);
    BigDecimal proportion = in.creditType() == CreditType.PROPORTIONAL ? in.proportion() : null;
    String ground = deductionGround(in);

    // Пълен кредит: няма неползвана част. Без кредит (чл. 70): случаят е на ал. 6 — виж javadoc-а.
    boolean nothingUnused =
        in.creditType() == CreditType.FULL || in.creditType() == CreditType.NONE;
    if (years == 0 || nothingUnused) {
      String timeBar = years == 0 ? in.kind().timeBarGround() : null;
      return new Result(zero(), years, n, start, k0, proportion, ground, timeBar);
    }

    // Неползваната част: (1 − К₀), а при буква „б“ — (1 − ПрНИД₀ × К₀).
    BigDecimal used = proportion == null ? k0 : k0.multiply(proportion);
    BigDecimal unused = BigDecimal.ONE.subtract(used);
    BigDecimal numerator = nz(in.vatCharged()).multiply(unused).multiply(BigDecimal.valueOf(years));

    return new Result(scaled(numerator, n), years, n, start, k0, proportion, ground, null);
  }

  /**
   * Основанието по ал. 5 — БУКВИТЕ СА ДРУГИ, а не същите като при ал. 3.
   *
   * <p>Ал. 5 няма вариант за пълен кредит, тъй че частичният е буква „а“, а пропорционалният —
   * буква „б“. Т. 3 (услуги) там е едно изречение без букви. Преписана буквата от ал. 3, цитатът в
   * протокола би сочил несъществуваща разпоредба.
   */
  static String deductionGround(Input in) {
    String base = "чл. 79 ал. 5 " + in.kind().point();
    if (in.kind() == AssetKind.SERVICE) {
      return base;
    }
    if (in.creditType() == CreditType.PARTIAL) {
      return base + " б. „а“";
    }
    if (in.creditType() == CreditType.PROPORTIONAL) {
      return base + " б. „б“";
    }
    return base;
  }

  // ------------------------------------------------------------------ общи части

  /**
   * Началото на n-годишния срок — годината на упражняване на кредита.
   *
   * <p>Дословно БГ се брои „считано от началото на годината на упражняване правото на данъчен
   * кредит, <b>съответно от началото на годината на фактическото използване, в случай че имотът не
   * е използван повече от една година след годината на упражняване правото на данъчен кредит</b>“.
   *
   * <p>⚠️ Второто начало е САМО в т. 1 (недвижими имоти) — т. 2 и т. 3 го нямат в текста си. За
   * движима вещ и за услуга годината на фактическо използване не мести нищо.
   */
  static int periodStartYear(Input in) {
    Integer firstUse = in.firstUseYear();
    if (in.kind() == AssetKind.IMMOVABLE_PROPERTY
        && firstUse != null
        && firstUse > in.creditYear() + 1) {
      return firstUse;
    }
    return in.creditYear();
  }

  /**
   * БГ — „броят на годините от настъпване на обстоятелството …, ВКЛЮЧИТЕЛНО годината на настъпване
   * на обстоятелството, до изтичане на n-годишния срок, считано от началото на годината на
   * упражняване правото на данъчен кредит“.
   *
   * <p>Срокът тръгва от 1 януари на {@code periodStartYear} и обхваща годините {@code start …
   * start+n−1}. Оттам БГ = start + n − година на събитието. Кредит 2026 и брак 2028 при n = 5 дават
   * срок 2026–2030 и БГ = 3 (2028, 2029, 2030) — броят се и годината на събитието, и последната
   * година от срока.
   *
   * <p>Нула значи изтекъл срок — чл. 80 ал. 1 т. 3/т. 4 отказва корекцията изцяло. Таванът n пази
   * от събитие, вписано ПРЕДИ годината на кредита: повече години от самия срок не съществуват.
   */
  static int remainingYears(int periodStartYear, int periodYears, int eventYear) {
    int remaining = periodStartYear + periodYears - eventYear;
    if (remaining <= 0) {
      return 0;
    }
    return Math.min(remaining, periodYears);
  }

  /**
   * К₀, както чл. 79в го заковава — затова коефициентът по чл. 73 не се чете при пълен кредит и при
   * чл. 70.
   *
   * <p>Т. 3: „К₀ е 1, ако … изцяло е приспаднало данъчен кредит …; в този случай коефициентът по
   * чл. 73 … НЕ СЕ ВЗЕМА ПРЕДВИД.“ Т. 5: „К₀ е 0, ако … не е приспаднало данъчен кредит на
   * основание чл. 70 …“
   */
  static BigDecimal effectiveK0(Input in) {
    switch (in.creditType()) {
      case FULL:
        return BigDecimal.ONE;
      case NONE:
        return BigDecimal.ZERO;
      default:
        return in.k0();
    }
  }

  /** Основанието за протокола — точка и буква, както ги изписва законът. */
  static String legalGround(Input in) {
    return ("чл. 79 ал. 3 " + in.kind().point() + " " + in.creditType().letter()).trim();
  }

  /**
   * Чл. 79в — разпоредбата, която прави формулата изпълнима. Невалидната комбинация се ОТКАЗВА,
   * вместо да се смята по подразбиране: тихо приетата нула тук е грешен данък, а не празно поле.
   */
  static void validate(Input in) {
    if (in == null) {
      throw new IllegalArgumentException("Липсват данни за корекция по чл. 79 ал. 3");
    }
    if (in.kind() == null || in.creditType() == null) {
      throw new IllegalArgumentException(
          "Корекция по чл. 79 ал. 3 иска класификация (т. 1/т. 2/т. 3) и вид данъчен кредит");
    }
    if (in.kind() == AssetKind.SERVICE && in.creditType() == CreditType.PROPORTIONAL) {
      throw new IllegalArgumentException(
          "Чл. 79 ал. 3 т. 3 (услуги) няма буква „в“ — пропорционален кредит по чл. 71а/71б"
              + " там не е предвиден");
    }
    if (in.creditType() == CreditType.PARTIAL || in.creditType() == CreditType.PROPORTIONAL) {
      requireK0(in.k0());
    }
    if (in.creditType() == CreditType.PROPORTIONAL) {
      requireProportion(in.proportion());
    }
  }

  /** Чл. 79в т. 4: „К₀ НЕ МОЖЕ ДА Е 0, ако … е приспаднало частичен данъчен кредит“. */
  private static void requireK0(BigDecimal k0) {
    if (k0 == null || k0.signum() <= 0 || k0.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException(
          "Чл. 79в т. 4: при частичен данъчен кредит К₀ не може да е 0 — очаква се (0; 1], а е "
              + k0);
    }
  }

  /** Чл. 79в т. 8: „ПрНИД₀ не може да бъде 0 или 1“. */
  private static void requireProportion(BigDecimal proportion) {
    if (proportion == null
        || proportion.signum() <= 0
        || proportion.compareTo(BigDecimal.ONE) >= 0) {
      throw new IllegalArgumentException(
          "Чл. 79в т. 8: ПрНИД₀ не може да бъде 0 или 1 — очаква се (0; 1), а е " + proportion);
    }
  }

  /** Делението на n е ЕДИНСТВЕНОТО закръгляне — до стотинки, мащабът на дневника. */
  private static BigDecimal scaled(BigDecimal numerator, int periodYears) {
    return numerator.divide(BigDecimal.valueOf(periodYears), 2, RoundingMode.HALF_UP);
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(2);
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}

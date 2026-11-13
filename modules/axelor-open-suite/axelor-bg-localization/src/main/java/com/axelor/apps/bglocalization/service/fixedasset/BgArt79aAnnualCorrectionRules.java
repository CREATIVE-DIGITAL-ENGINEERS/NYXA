package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.AssetKind;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.CreditType;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * ГОДИШНА корекция на ползван данъчен кредит при ИЗМЕНЕНИЕ В ИЗПОЛЗВАНЕТО — <b>чл. 79а</b> (стоки и
 * недвижими имоти) и <b>чл. 79б ЗДДС</b> (услуги), като чиста функция.
 *
 * <p>Сверено дословно на 15.09.2026 г. срещу ДВА независими източника, чиито текстове съвпадат знак
 * по знак след NFC и свиване на интервалите: текстът на ЗДДС до ДВ бр. 115 от 30.12.2025 г. и
 * консолидираният текст на lex.bg ({@code ldoc/2135533201}) — до ДВ бр. 69 от 31.07.2026 г.
 * Правилникът е сверен по същия начин: kik-info срещу lex.bg ({@code ldoc/2135534826}).
 *
 * <p><b>Различава се от {@link BgArt79CorrectionRules} по ТРИ неща, не по едно.</b>
 *
 * <ol>
 *   <li><b>Тригерът.</b> Чл. 79 ал. 3 е ЕДНОКРАТНА корекция при отписване; тук е ЕЖЕГОДНА, за
 *       държан актив, при „изменение в използването“ (ал. 1).
 *   <li><b>Множителят.</b> Там {@code 1/n × БГ} — наведнъж за всички оставащи години. Тук {@code
 *       1/n} за ЕДНА година, всяка година поотделно. „Брой години“ тук НЕ съществува.
 *   <li><b>БУКВИТЕ СА ДРУГИ.</b> Чл. 79 ал. 3 има ТРИ букви и започва от „приспаднат изцяло“,
 *       защото без ползван кредит няма какво да се връща. Чл. 79а ал. 3 има ЧЕТИРИ и започва от „НЕ
 *       е приспаднат данъчен кредит на основание чл. 70“ — тоест буква „а“ на единия член е
 *       различен случай от буква „а“ на другия. Преписана буквата, цитатът в протокола сочи чужда
 *       разпоредба, а множителите излизат разместени.
 * </ol>
 *
 * <p>Знакът е част от резултата, не от маршрута: ал. 4 (и на двата члена) иска „<b>със знак
 * (+)</b>, когато е в посока увеличение … <b>със знак (−)</b>, когато е в посока намаление“, като и
 * двете отиват в ЕДНА И СЪЩА колона — дневника за покупки. Виж {@link #correction(Input)}.
 *
 * <p>Прозорецът и спирането по ал. 8 не са тук — те са състояние през годините и живеят в {@link
 * BgArt79aCorrectionWindow}.
 */
public final class BgArt79aAnnualCorrectionRules {

  private BgArt79aAnnualCorrectionRules() {}

  /**
   * Използването на актива ПРЕЗ ГОДИНАТА Х — то и само то решава Кх.
   *
   * <p>Дословно чл. 79в т. 1 и т. 2. И двете точки завършват с „в този случай коефициентът по чл.
   * 73 за съответната година <b>не се взема предвид</b>“ — тоест чистите случаи изобщо не четат
   * коефициента, а не „ползват го, но го закръглят“.
   */
  public enum TaxableUse {
    /** Чл. 79в т. 1 — само доставки С право на данъчен кредит: Кх е 1. */
    ONLY_WITH_CREDIT,

    /** Чл. 79в т. 2 — само доставки БЕЗ право на данъчен кредит: Кх е 0. */
    ONLY_WITHOUT_CREDIT,

    /** Смесено — Кх е коефициентът по чл. 73 за година х. */
    MIXED
  }

  /**
   * Използването за независима икономическа дейност през година х — решава ПрНИДх.
   *
   * <p>Дословно чл. 79в т. 6 и т. 7.
   */
  public enum NidUse {
    /** Чл. 79в т. 6 — само за независима икономическа дейност: ПрНИДх е 1. */
    ONLY_NID,

    /** Чл. 79в т. 7 — само за цели извън нея: ПрНИДх е 0. */
    ONLY_NON_NID,

    /** Смесено — ПрНИДх е подадената пропорция за година х. */
    MIXED
  }

  /**
   * Фактите за ЕДНА корекционна година.
   *
   * <p>Акцесорите нарочно са без префикс {@code get} — това не е Axelor entity и {@code null} тук
   * значи „не е въведено“, за разлика от генерираните getter-и, които връщат нула.
   */
  public static final class Input {

    private final AssetKind kind;
    private final CreditType creditType;
    private final BigDecimal vatCharged;
    private final int eventYear;
    private final BigDecimal k0;
    private final BigDecimal proportion0;
    private final TaxableUse taxableUse;
    private final BigDecimal kx;
    private final NidUse nidUse;
    private final BigDecimal proportionX;
    private final boolean noCreditUnderArt70Para1Pt45;
    private final boolean art67Para2ConditionMet;
    private final boolean art70Para2RequirementsFailed;
    private final Integer periodYearsOverride;

    public Input(
        AssetKind kind,
        CreditType creditType,
        BigDecimal vatCharged,
        int eventYear,
        BigDecimal k0,
        BigDecimal proportion0,
        TaxableUse taxableUse,
        BigDecimal kx,
        NidUse nidUse,
        BigDecimal proportionX,
        boolean noCreditUnderArt70Para1Pt45,
        boolean art67Para2ConditionMet,
        boolean art70Para2RequirementsFailed) {
      this(
          kind,
          creditType,
          vatCharged,
          eventYear,
          k0,
          proportion0,
          taxableUse,
          kx,
          nidUse,
          proportionX,
          noCreditUnderArt70Para1Pt45,
          art67Para2ConditionMet,
          art70Para2RequirementsFailed,
          null);
    }

    /**
     * Същото, но със ЯВЕН знаменател — вратата за втория носител по ППЗДДС чл. 67 ал. 4.
     *
     * @param periodYearsOverride n, когато съставителят го обявява изрично; {@code null} значи
     *     законовият период на класификацията (20 или 5)
     */
    public Input(
        AssetKind kind,
        CreditType creditType,
        BigDecimal vatCharged,
        int eventYear,
        BigDecimal k0,
        BigDecimal proportion0,
        TaxableUse taxableUse,
        BigDecimal kx,
        NidUse nidUse,
        BigDecimal proportionX,
        boolean noCreditUnderArt70Para1Pt45,
        boolean art67Para2ConditionMet,
        boolean art70Para2RequirementsFailed,
        Integer periodYearsOverride) {
      this.periodYearsOverride = periodYearsOverride;
      this.kind = kind;
      this.creditType = creditType;
      this.vatCharged = vatCharged;
      this.eventYear = eventYear;
      this.k0 = k0;
      this.proportion0 = proportion0;
      this.taxableUse = taxableUse;
      this.kx = kx;
      this.nidUse = nidUse;
      this.proportionX = proportionX;
      this.noCreditUnderArt70Para1Pt45 = noCreditUnderArt70Para1Pt45;
      this.art67Para2ConditionMet = art67Para2ConditionMet;
      this.art70Para2RequirementsFailed = art70Para2RequirementsFailed;
    }

    public AssetKind kind() {
      return kind;
    }

    public CreditType creditType() {
      return creditType;
    }

    /**
     * НДДС — начисленият ДДС при производството, придобиването или вноса на стоката, включително
     * при придобиването или изграждането на недвижим имот, съответно при получаване на услугата.
     *
     * <p>⚠️ БРУТНИЯТ начислен данък, НЕ ползваният кредит — К₀ и ПрНИД₀ влизат отделно.
     */
    public BigDecimal vatCharged() {
      return vatCharged;
    }

    /** Годината, за която се смята корекцията — „годината на настъпване на изменението“. */
    public int eventYear() {
      return eventYear;
    }

    /** К₀ — коефициентът по чл. 73 за годината, през която е упражнено право на данъчен кредит. */
    public BigDecimal k0() {
      return k0;
    }

    /** ПрНИД₀ — пропорцията за НИД в същата година. Само за буква „г“. */
    public BigDecimal proportion0() {
      return proportion0;
    }

    public TaxableUse taxableUse() {
      return taxableUse;
    }

    /** Коефициентът по чл. 73 за година х. Чете се САМО при {@link TaxableUse#MIXED}. */
    public BigDecimal kx() {
      return kx;
    }

    public NidUse nidUse() {
      return nidUse;
    }

    /** ПрНИДх за година х. Чете се САМО при {@link NidUse#MIXED}. */
    public BigDecimal proportionX() {
      return proportionX;
    }

    /**
     * Кредит не е приспаднат на основание <b>чл. 70 ал. 1 т. 4 и 5</b> — мотоциклет/лек автомобил и
     * съпътстващите ги стоки и услуги.
     *
     * <p>Отделя се от общото „чл. 70“, защото ППЗДДС чл. 67 ал. 2 окачва корекцията по т. 2 б. „а“
     * на допълнително условие ИМЕННО за тези две точки.
     */
    public boolean noCreditUnderArt70Para1Pt45() {
      return noCreditUnderArt70Para1Pt45;
    }

    /** Изпълнено ли е едно от двете условия по ППЗДДС чл. 67 ал. 2 за година х. */
    public boolean art67Para2ConditionMet() {
      return art67Para2ConditionMet;
    }

    /** За година х НЕ са изпълнени изискванията на чл. 70 ал. 2 т. 1 - 5 (ППЗДДС чл. 67 ал. 3). */
    public boolean art70Para2RequirementsFailed() {
      return art70Para2RequirementsFailed;
    }

    /**
     * Знаменателят n, когато е обявен изрично; {@code null} = законовият период на класификацията.
     *
     * <p>⚠️ <b>Съществува заради ППЗДДС чл. 67 ал. 4 и заради това, което тя НЕ казва.</b>
     * Разпоредбата дава прозореца („за остатъка от годините“), но не дава формула, а думата
     * „пропорционално“ допуска две аритметики, чиято разлика е в ПЪТИ. Затова знаменателят е вход,
     * а не извод: обявен на реда, той е четим в протокола и обратим през настройка, ако излезе
     * официално становище с друг прочит. Подразбирането е законовият период — виж {@link
     * #periodYears(Input)}.
     */
    public Integer periodYearsOverride() {
      return periodYearsOverride;
    }
  }

  /** Изчисленото — заедно с множителите, които ППЗДДС чл. 66 ал. 5/6 иска в протокола. */
  public static final class Result {

    private final BigDecimal amount;
    private final int periodYears;
    private final BigDecimal appliedK0;
    private final BigDecimal appliedKx;
    private final BigDecimal appliedProportion0;
    private final BigDecimal appliedProportionX;
    private final String legalGround;
    private final String refusalReason;

    Result(
        BigDecimal amount,
        int periodYears,
        BigDecimal appliedK0,
        BigDecimal appliedKx,
        BigDecimal appliedProportion0,
        BigDecimal appliedProportionX,
        String legalGround,
        String refusalReason) {
      this.amount = amount;
      this.periodYears = periodYears;
      this.appliedK0 = appliedK0;
      this.appliedKx = appliedKx;
      this.appliedProportion0 = appliedProportion0;
      this.appliedProportionX = appliedProportionX;
      this.legalGround = legalGround;
      this.refusalReason = refusalReason;
    }

    /**
     * ИРПДК — изменението в размера на ползвания данъчен кредит, закръглено до стотинки.
     *
     * <p>Положително значи УВЕЛИЧЕНИЕ на кредита, тоест знак (+) по ал. 4 т. 1; отрицателно —
     * НАМАЛЕНИЕ, знак (−) по т. 2.
     */
    public BigDecimal amount() {
      return amount;
    }

    /** n — 20 за недвижим имот, 5 за останалите стоки и за услугите. */
    public int periodYears() {
      return periodYears;
    }

    /** К₀, както чл. 79в го е заковал. */
    public BigDecimal appliedK0() {
      return appliedK0;
    }

    /** Кх, както чл. 79в и ППЗДДС чл. 67 ал. 3 са го заковали. */
    public BigDecimal appliedKx() {
      return appliedKx;
    }

    /** ПрНИД₀, както е приложен; {@code null}, когато формулата не го ползва. */
    public BigDecimal appliedProportion0() {
      return appliedProportion0;
    }

    /** ПрНИДх, както е приложен; {@code null}, когато формулата не го ползва. */
    public BigDecimal appliedProportionX() {
      return appliedProportionX;
    }

    /** Основанието за протокола — напр. „чл. 79а ал. 3 т. 1 б. „в““. */
    public String legalGround() {
      return legalGround;
    }

    /**
     * Защо корекция не се извършва, когато разпоредба я отказва (ППЗДДС чл. 67 ал. 2). {@code
     * null}, когато отказ няма — включително при пресметната нула, която е отговор, а не отказ.
     */
    public String refusalReason() {
      return refusalReason;
    }

    /** Дължи ли се изобщо корекция. */
    public boolean isDue() {
      return amount.signum() != 0;
    }

    /**
     * Може ли корекцията да НЕ се извърши — <b>чл. 79а ал. 6</b> и <b>чл. 79б ал. 5</b>:
     * „Корекцията по ал. 3 може да не се извършва, когато е в посока УВЕЛИЧЕНИЕ на размера на
     * ползвания данъчен кредит.“
     *
     * <p>⚠️ Асиметрията е целият смисъл: намалението е ЗАДЪЛЖИТЕЛНО. Пропуснато намаление е скрит
     * данъчен пасив; пропуснато увеличение е само неупражнено право.
     */
    public boolean canBeSkipped() {
      return amount.signum() > 0;
    }
  }

  // ------------------------------------------------------------------ формулата

  /**
   * ИРПДК за ЕДНА година.
   *
   * <p>Дословно <b>чл. 79а ал. 3</b> (n = 20 за т. 1 недвижими имоти, n = 5 за т. 2 останалите
   * стоки):
   *
   * <pre>
   *   б. „а“ (не е приспаднат, чл. 70):  ИРПДК = НДДС x 1/n x ПрНИДx x Кx
   *   б. „б“ (приспаднат изцяло):        ИРПДК = НДДС x 1/n x (Кx - 1)
   *   б. „в“ (приспаднат частично):      ИРПДК = НДДС x 1/n x (Кx - К0)
   *   б. „г“ (пропорционално, 71а/71б):  ИРПДК = НДДС x 1/n x (ПрНИДx x Кx - ПрНИД0 x К0)
   * </pre>
   *
   * <p>Дословно <b>чл. 79б ал. 3</b> (услуги, n = 5, ТРИ точки БЕЗ букви и БЕЗ ПрНИД):
   *
   * <pre>
   *   т. 1 (не е приспаднат, чл. 70):    ИРПДК = НДДС x 1/5 x Кx
   *   т. 2 (приспаднат изцяло):          ИРПДК = НДДС x 1/5 x (Кx - 1)
   *   т. 3 (приспаднат частично):        ИРПДК = НДДС x 1/5 x (Кx - К0)
   * </pre>
   *
   * <p>⚠️ <b>Буква „а“ няма изваждане</b> — и това не е пропуск на закона: К₀ е 0 по чл. 79в т. 5,
   * тъй че няма ползван кредит, от който да се вади. Резултатът там е винаги ≥ 0, тоест само
   * увеличение.
   *
   * <p>⚠️ <b>Чл. 79б т. 1 няма ПрНИД</b>, докато чл. 79а б. „а“ има. Услугите нямат пропорционален
   * режим по чл. 71а/71б в този член — пренесен оттам, множителят би бил изсмукан от пръстите.
   *
   * <p>Делението на n е ЕДИНСТВЕНОТО закръгляне: множителите се трупат точно и се дели веднъж
   * накрая, до стотинки — мащабът на дневника и на справка-декларацията.
   *
   * @throws IllegalArgumentException при комбинация, която чл. 79в или самият член изключват
   */
  public static Result correction(Input in) {
    validate(in);

    int n = periodYears(in);
    BigDecimal k0 = effectiveK0(in.creditType(), in.k0());
    BigDecimal kx = effectiveKx(in);
    BigDecimal proportionX = usesProportionX(in) ? effectiveProportionX(in) : null;
    BigDecimal proportion0 = in.creditType() == CreditType.PROPORTIONAL ? in.proportion0() : null;
    String ground = legalGround(in);

    String refusal = art67Para2Refusal(in);
    if (refusal != null) {
      return new Result(zero(), n, k0, kx, proportion0, proportionX, ground, refusal);
    }

    BigDecimal factor = factor(in, k0, kx, proportion0, proportionX);
    BigDecimal amount =
        nz(in.vatCharged()).multiply(factor).divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);

    return new Result(amount, n, k0, kx, proportion0, proportionX, ground, null);
  }

  /**
   * n — знаменателят на формулата.
   *
   * <p><b>ЗАКОНОВИЯТ период, не остатъкът.</b> ППЗДДС чл. 67 ал. 4 праща втория носител да язди
   * „остатъка от годините на 20-годишния, съответно 5-годишния, срок“ — тоест тя мени ПРОЗОРЕЦА, а
   * изрично назовава срока, който остава 20-, съответно 5-годишен. Подзаконов акт не мени
   * знаменател, закован в закона; а знаменател 1/R би бил точно нов, по-къс срок — тъкмо това,
   * което чл. 79а ал. 10 забранява („не възниква нов 20-годишен срок, съответно 5-годишен срок“).
   *
   * <p>Следствието е признато и записано, а не скрито: при 1/20 върху остатък от R години се
   * коригира само R/20 от начисления данък, а остатъкът до пълния НДДС никога не се коригира. Това
   * е резултат от текста, не пропуск на модела.
   *
   * <p>⚠️ „Пропорционално“ в ал. 4 допуска и втори прочит (знаменател = остатъкът) и разликата е в
   * ПЪТИ. Затова стойността е ЯВЕН вход и се снима на реда: ако излезе официално становище с другия
   * прочит, обръщането е настройка, а не пренаписване на формулата.
   */
  static int periodYears(Input in) {
    Integer override = in.periodYearsOverride();
    return override == null ? in.kind().periodYears() : override;
  }

  /** Множителят в скобите — единственото място, където четирите случая се различават. */
  static BigDecimal factor(
      Input in, BigDecimal k0, BigDecimal kx, BigDecimal proportion0, BigDecimal proportionX) {
    switch (in.creditType()) {
      case NONE:
        // 79а б. „а“: ПрНИДx × Кx. 79б т. 1: само Кx — там ПрНИД изобщо не се среща.
        return in.kind() == AssetKind.SERVICE ? kx : proportionX.multiply(kx);
      case FULL:
        return kx.subtract(BigDecimal.ONE);
      case PARTIAL:
        return kx.subtract(k0);
      case PROPORTIONAL:
        return proportionX.multiply(kx).subtract(proportion0.multiply(k0));
      default:
        throw new IllegalArgumentException("Непознат вид данъчен кредит: " + in.creditType());
    }
  }

  /** Ползва ли формулата ПрНИДх изобщо — само буква „а“ и буква „г“, и никога за услуга. */
  static boolean usesProportionX(Input in) {
    if (in.kind() == AssetKind.SERVICE) {
      return false;
    }
    return in.creditType() == CreditType.NONE || in.creditType() == CreditType.PROPORTIONAL;
  }

  // ------------------------------------------------------------------ чл. 79в

  /**
   * К₀, както чл. 79в го заковава.
   *
   * <p>Т. 3: „К₀ е 1, ако … изцяло е приспаднало данъчен кредит …; в този случай коефициентът по
   * чл. 73 … не се взема предвид.“ Т. 5: „К₀ е 0, ако … не е приспаднало данъчен кредит на
   * основание чл. 70 …“
   */
  static BigDecimal effectiveK0(CreditType creditType, BigDecimal k0) {
    switch (creditType) {
      case FULL:
        return BigDecimal.ONE;
      case NONE:
        return BigDecimal.ZERO;
      default:
        return k0;
    }
  }

  /**
   * Кх, както чл. 79в т. 1 и т. 2 го заковават — плюс отмяната по <b>ППЗДДС чл. 67 ал. 3</b>.
   *
   * <p>Дословно ал. 3: „За стоки и услуги, за които е приспаднат изцяло данъчен кредит на основание
   * чл. 70, ал. 2, т. 1 - 5 от закона, за всяка от годините … за която не са изпълнени изискванията
   * на чл. 70, ал. 2, т. 1 - 5 от закона, за целите на изчисляване на корекцията по <b>чл. 79а, ал.
   * 3, т. 2, буква „б“</b> от закона Кх е 0.“
   *
   * <p>⚠️ <b>ОБХВАТЪТ Е ТОЧНО ЕДНА БУКВА.</b> Правилникът сочи т. 2 (останалите стоки) буква „б“
   * (приспаднат изцяло) — тоест НЕ важи за недвижим имот и НЕ важи за услуга по чл. 79б. Разширено
   * наум „за всички изцяло приспаднали“, правилото би нулирало Кх на имот и би поискало обратно
   * 1/20 от данъка без основание.
   */
  static BigDecimal effectiveKx(Input in) {
    if (in.art70Para2RequirementsFailed()
        && in.kind() == AssetKind.GOODS
        && in.creditType() == CreditType.FULL) {
      return BigDecimal.ZERO;
    }
    switch (in.taxableUse()) {
      case ONLY_WITH_CREDIT:
        return BigDecimal.ONE;
      case ONLY_WITHOUT_CREDIT:
        return BigDecimal.ZERO;
      default:
        return in.kx();
    }
  }

  /** ПрНИДх, както чл. 79в т. 6 и т. 7 го заковават. */
  static BigDecimal effectiveProportionX(Input in) {
    switch (in.nidUse()) {
      case ONLY_NID:
        return BigDecimal.ONE;
      case ONLY_NON_NID:
        return BigDecimal.ZERO;
      default:
        return in.proportionX();
    }
  }

  // ------------------------------------------------------------------ ППЗДДС чл. 67 ал. 2

  /**
   * Отказът по <b>ППЗДДС чл. 67 ал. 2</b> — или {@code null}, когато не се прилага.
   *
   * <p>Дословно: „За стоки и услуги, за които на основание <b>чл. 70, ал. 1, т. 4 и 5</b> от закона
   * не е приспаднат данъчен кредит, корекция по <b>чл. 79а, ал. 3, т. 2, буква „а“</b> от закона се
   * извършва <b>САМО АКО</b> за годината, през която се извършва корекцията, е изпълнено някое от
   * следните условия: 1. в рамките на съответната година стоката или услугата се използва
   * единствено за дейностите по чл. 70, ал. 2, т. 1 - 4 от закона, или 2. … когато една или повече
   * от изброените … са <b>основна дейност</b> по смисъла на § 1, т. 18а …“
   *
   * <p>⚠️ Това е ОТКАЗ, не изключение: корекцията изобщо не се извършва, а не „извършва се и после
   * се освобождава“. Затова се връща нула с обявена причина, вместо да се хвърля — липсата на
   * условието е законен изход, а не невалиден вход.
   *
   * <p>⚠️ Обхватът пак е ЕДНА буква: т. 2 (останалите стоки) буква „а“. Недвижим имот и услуга не
   * минават оттук.
   */
  static String art67Para2Refusal(Input in) {
    boolean scoped =
        in.kind() == AssetKind.GOODS
            && in.creditType() == CreditType.NONE
            && in.noCreditUnderArt70Para1Pt45();
    if (scoped && !in.art67Para2ConditionMet()) {
      return "ППЗДДС чл. 67 ал. 2 — корекция по чл. 79а ал. 3 т. 2 б. „а“ не се извършва:"
          + " за годината не е изпълнено нито едно от двете условия (използване единствено за"
          + " дейностите по чл. 70 ал. 2 т. 1 - 4, или те да са основна дейност по § 1 т. 18а).";
    }
    return null;
  }

  // ------------------------------------------------------------------ основание и валидация

  /**
   * Основанието за протокола — точка и буква, както ги изписва законът.
   *
   * <p>⚠️ Чл. 79а има ЧЕТИРИ букви, а чл. 79б — ТРИ ТОЧКИ без букви. Един общ низ за двата члена би
   * цитирал несъществуваща разпоредба на половината случаи.
   */
  static String legalGround(Input in) {
    if (in.kind() == AssetKind.SERVICE) {
      return "чл. 79б ал. 3 " + servicePoint(in.creditType());
    }
    String point = in.kind() == AssetKind.IMMOVABLE_PROPERTY ? "т. 1" : "т. 2";
    return "чл. 79а ал. 3 " + point + " б. " + goodsLetter(in.creditType());
  }

  /** Буквите на чл. 79а ал. 3 — започват от „НЕ е приспаднат“, за разлика от чл. 79 ал. 3. */
  static String goodsLetter(CreditType creditType) {
    switch (creditType) {
      case NONE:
        return "„а“";
      case FULL:
        return "„б“";
      case PARTIAL:
        return "„в“";
      default:
        return "„г“";
    }
  }

  /** Точките на чл. 79б ал. 3 — три, без букви и без пропорционален вариант. */
  static String servicePoint(CreditType creditType) {
    switch (creditType) {
      case NONE:
        return "т. 1";
      case FULL:
        return "т. 2";
      default:
        return "т. 3";
    }
  }

  /**
   * Чл. 79в и обхватът на самите членове. Невалидната комбинация се ОТКАЗВА, вместо да се смята по
   * подразбиране: тихо приетата нула тук е грешен данък, а не празно поле.
   */
  static void validate(Input in) {
    if (in == null) {
      throw new IllegalArgumentException("Липсват данни за годишна корекция по чл. 79а/79б");
    }
    if (in.kind() == null || in.creditType() == null) {
      throw new IllegalArgumentException(
          "Годишната корекция иска класификация (имот/стока/услуга) и вид данъчен кредит");
    }
    if (in.taxableUse() == null) {
      throw new IllegalArgumentException(
          "Чл. 79в т. 1 и т. 2: иска се използването през годината, за да се определи Кх");
    }
    if (in.kind() == AssetKind.SERVICE && in.creditType() == CreditType.PROPORTIONAL) {
      throw new IllegalArgumentException(
          "Чл. 79б ал. 3 има само три точки — пропорционален данъчен кредит по чл. 71а/71б"
              + " за услуги там не е предвиден");
    }
    Integer override = in.periodYearsOverride();
    if (override != null && override <= 0) {
      throw new IllegalArgumentException(
          "Знаменателят по ППЗДДС чл. 67 ал. 4 трябва да е положителен, а е " + override);
    }
    if (in.taxableUse() == TaxableUse.MIXED) {
      requireRange(in.kx(), "Кх — коефициентът по чл. 73 за годината", true);
    }
    if (in.creditType() == CreditType.PARTIAL || in.creditType() == CreditType.PROPORTIONAL) {
      requireK0(in.k0());
    }
    if (in.creditType() == CreditType.PROPORTIONAL) {
      requireProportion0(in.proportion0());
    }
    if (usesProportionX(in) && in.nidUse() == null) {
      throw new IllegalArgumentException(
          "Чл. 79в т. 6 и т. 7: иска се използването за НИД през годината, за да се определи"
              + " ПрНИДх");
    }
    if (usesProportionX(in) && in.nidUse() == NidUse.MIXED) {
      requireRange(in.proportionX(), "ПрНИДх — пропорцията за НИД за годината", true);
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
  private static void requireProportion0(BigDecimal proportion0) {
    if (proportion0 == null
        || proportion0.signum() <= 0
        || proportion0.compareTo(BigDecimal.ONE) >= 0) {
      throw new IllegalArgumentException(
          "Чл. 79в т. 8: ПрНИД₀ не може да бъде 0 или 1 — очаква се (0; 1), а е " + proportion0);
    }
  }

  /**
   * Смесеният случай иска ЧИСЛО в [0; 1].
   *
   * <p>Чистите 0 и 1 идват от {@link TaxableUse} и {@link NidUse}, не от стойността — така
   * непопълненото поле не може да мине за „само освободени доставки“.
   */
  private static void requireRange(BigDecimal value, String label, boolean inclusive) {
    boolean bad =
        value == null
            || value.signum() < 0
            || (inclusive
                ? value.compareTo(BigDecimal.ONE) > 0
                : value.compareTo(BigDecimal.ONE) >= 0);
    if (bad) {
      throw new IllegalArgumentException(label + " се очаква в [0; 1], а е " + value);
    }
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(2);
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}

package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.AssetKind;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.CreditType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * НОСИТЕЛИТЕ на начислен ДДС върху ЕДИН актив — чиста функция.
 *
 * <p>Един актив носи повече от един начислен данък: този при придобиването и по един за всяко
 * подобрение. Законът го казва сам — <b>чл. 79а ал. 9</b>: „при извършено подобрение на
 * съществуваща сграда, в резултат на което е налице нова сграда, се извършват <b>ОТДЕЛНИ
 * КОРЕКЦИИ</b>“. Оттам и името: носител = един НДДС със свой прозорец, свои коефициенти и свой
 * протокол.
 *
 * <p><b>Разграничителят не е наш избор — той е в § 1 т. 32 ДР ЗДДС</b>, а за сгради т. 32 сама
 * препраща към § 1 т. 5 б. „в“ подт. „аа“:
 *
 * <ul>
 *   <li><b>б. „а“ — за СГРАДИ:</b> подобрение е онова надстрояване/реконструкция/обновяване, „в
 *       резултат на което е налице <b>нова сграда</b>“. Има нова сграда ⇒ <b>чл. 79а ал. 9</b>: нов
 *       20-годишен срок със свое начало. Няма нова сграда ⇒ <b>изобщо не е „подобрение“ по ЗДДС</b>
 *       и носител не се поражда.
 *   <li><b>б. „б“ — за стоки, недвижими имоти РАЗЛИЧНИ ОТ СГРАДИ, и услуги:</b> последващи разходи,
 *       водещи до икономическа изгода над първоначално оценената. Носител има ⇒ <b>ППЗДДС чл. 67
 *       ал. 4</b>: без нов срок, язди ОСТАТЪКА от срока на заварения актив.
 * </ul>
 *
 * <p>⚠️ <b>Третата пътека — „сграда без нова сграда“ — е ПРАЗНА, и трите текста я изключват
 * поотделно.</b> Чл. 79а ал. 10 и ППЗДДС чл. 67 ал. 4 казват „недвижими имоти, <b>различни от
 * сграда</b>“; § 1 т. 32 б. „а“ иска новата сграда като РЕЗУЛТАТ. Капиталов разход по ЗКПО върху
 * сграда, който не прави нова сграда, просто няма носител по ЗДДС. Това е извод от съпоставка на
 * трите текста, не цитат — затова редът се МАРКИРА с основание, вместо да изчезне мълчаливо.
 *
 * <p>⚠️ <b>{@code classificationSelect} (CAPITAL/CURRENT) НЕ Е разграничителят.</b> Той е ЗКПО чл.
 * 63/64 — „водят до бъдещи икономически изгоди“. ЗДДС пита друго и двете оси са независими:
 * капиталов по ЗКПО разход върху сграда, който не дава нова сграда, е CAPITAL и въпреки това не е
 * подобрение по ЗДДС. Слепени, едната ос би решавала вместо другата.
 *
 * <p>⚠️ <b>Спирането по ал. 8 е на АКТИВА, споделено от всички носители.</b> Дословно подлогът е
 * „стоката, съответно недвижимият имот“, не начисленият данък. Затова {@code suspendedYears} се
 * подава веднъж за актива и важи за всеки носител — и оттам остатъкът по чл. 67 ал. 4 РАСТЕ, а не
 * се топи.
 */
public final class BgArt79aCarriers {

  private BgArt79aCarriers() {}

  /** Режимът на носителя — решава прозореца, не формулата. */
  public enum Regime {
    /** Носителят при придобиване: свой срок по чл. 79а ал. 3 / чл. 79б ал. 3. */
    ACQUISITION,

    /** Чл. 79а ал. 9 — подобрение, дало нова сграда: ПРЕСЕН 20-годишен срок. */
    NEW_TERM_ART9,

    /** ППЗДДС чл. 67 ал. 4 — без нов срок, остатъкът от срока на заварения актив. */
    REMAINDER_ART67_4,

    /** Разходът не поражда носител по ЗДДС; причината е обявена. */
    NONE
  }

  /** Фактите за ЗДДС носителя при ПРИДОБИВАНЕТО — седемте полета на актива. */
  public static final class AssetFacts {
    private final AssetKind kind;
    private final CreditType creditType;
    private final BigDecimal vatCharged;
    private final int creditYear;
    private final Integer firstUseYear;
    private final BigDecimal k0;
    private final BigDecimal proportion0;

    public AssetFacts(
        AssetKind kind,
        CreditType creditType,
        BigDecimal vatCharged,
        int creditYear,
        Integer firstUseYear,
        BigDecimal k0,
        BigDecimal proportion0) {
      this.kind = kind;
      this.creditType = creditType;
      this.vatCharged = vatCharged;
      this.creditYear = creditYear;
      this.firstUseYear = firstUseYear;
      this.k0 = k0;
      this.proportion0 = proportion0;
    }
  }

  /**
   * Фактите за ЕДИН последващ разход.
   *
   * <p>⚠️ Коефициентите са СВОИ, а не на актива: кредитът за подобрението е от ДРУГА година, тъй че
   * К₀ на втория носител по конструкция се различава от К₀ при придобиването. Пренесени от актива,
   * те биха описали чужд оборот.
   */
  public static final class CostFacts {
    private final Long id;
    private final boolean improvement;
    private final boolean assetIsBuilding;
    private final boolean resultsInNewBuilding;
    private final AssetKind kind;
    private final CreditType creditType;
    private final BigDecimal vatCharged;
    private final int creditYear;
    private final Integer firstUseYear;
    private final BigDecimal k0;
    private final BigDecimal proportion0;

    public CostFacts(
        Long id,
        boolean improvement,
        boolean assetIsBuilding,
        boolean resultsInNewBuilding,
        AssetKind kind,
        CreditType creditType,
        BigDecimal vatCharged,
        int creditYear,
        Integer firstUseYear,
        BigDecimal k0,
        BigDecimal proportion0) {
      this.id = id;
      this.improvement = improvement;
      this.assetIsBuilding = assetIsBuilding;
      this.resultsInNewBuilding = resultsInNewBuilding;
      this.kind = kind;
      this.creditType = creditType;
      this.vatCharged = vatCharged;
      this.creditYear = creditYear;
      this.firstUseYear = firstUseYear;
      this.k0 = k0;
      this.proportion0 = proportion0;
    }

    public Long id() {
      return id;
    }

    public boolean assetIsBuilding() {
      return assetIsBuilding;
    }
  }

  /** Един носител, готов за прозореца и за формулата. */
  public static final class Carrier {
    private final Regime regime;
    private final Long subsequentCostId;
    private final AssetKind kind;
    private final CreditType creditType;
    private final BigDecimal vatCharged;
    private final int startYear;
    private final int periodYears;
    private final int remainingYears;
    private final BigDecimal k0;
    private final BigDecimal proportion0;
    private final String refusalReason;

    Carrier(
        Regime regime,
        Long subsequentCostId,
        AssetKind kind,
        CreditType creditType,
        BigDecimal vatCharged,
        int startYear,
        int periodYears,
        int remainingYears,
        BigDecimal k0,
        BigDecimal proportion0,
        String refusalReason) {
      this.regime = regime;
      this.subsequentCostId = subsequentCostId;
      this.kind = kind;
      this.creditType = creditType;
      this.vatCharged = vatCharged;
      this.startYear = startYear;
      this.periodYears = periodYears;
      this.remainingYears = remainingYears;
      this.k0 = k0;
      this.proportion0 = proportion0;
      this.refusalReason = refusalReason;
    }

    public Regime regime() {
      return regime;
    }

    /** {@code null} за носителя при придобиване. */
    public Long subsequentCostId() {
      return subsequentCostId;
    }

    public AssetKind kind() {
      return kind;
    }

    public CreditType creditType() {
      return creditType;
    }

    /** НДДС на ТОЗИ носител — при ал. 9 това е данъкът за подобрението (ал. 9 т. 2 дословно). */
    public BigDecimal vatCharged() {
      return vatCharged;
    }

    /** Началото на прозореца на носителя. */
    public int startYear() {
      return startYear;
    }

    /** n — законовият период (Q1): 20 за недвижим имот, 5 за останалите стоки и за услугите. */
    public int periodYears() {
      return periodYears;
    }

    /**
     * R по ППЗДДС чл. 67 ал. 4 — остатъкът от срока на ЗАВАРЕНИЯ актив, броен от кредитната година
     * на подобрението ВКЛЮЧИТЕЛНО. Нула за другите режими, където остатък няма смисъл.
     *
     * <p>⚠️ Не е знаменател и не е брой коригиращи години — виж {@link
     * BgArt79aCorrectionWindow#remainingYears}.
     */
    public int remainingYears() {
      return remainingYears;
    }

    public BigDecimal k0() {
      return k0;
    }

    public BigDecimal proportion0() {
      return proportion0;
    }

    /** Защо носител няма; {@code null}, когато носителят е валиден. */
    public String refusalReason() {
      return refusalReason;
    }

    public boolean isDue() {
      return regime != Regime.NONE;
    }
  }

  /**
   * Носителите на един актив: №1 е придобиването, после по един на всеки квалифициращ разход.
   *
   * <p>Редът е стабилен — придобиването винаги първо, после разходите в подадения ред — защото
   * протоколите се четат заедно в книгата и разбъркан ред би направил сверката ръчна.
   *
   * @param suspendedYears спрените години НА АКТИВА (ал. 8) — споделени от всички носители
   */
  public static List<Carrier> of(
      AssetFacts asset, List<CostFacts> costs, Set<Integer> suspendedYears) {
    List<Carrier> carriers = new ArrayList<>();

    int assetStart =
        BgArt79aCorrectionWindow.periodStartYear(
            asset.kind,
            asset.creditType == CreditType.NONE ? 0 : asset.creditYear,
            asset.creditType == CreditType.NONE ? asset.creditYear : 0,
            asset.firstUseYear);
    carriers.add(
        new Carrier(
            Regime.ACQUISITION,
            null,
            asset.kind,
            asset.creditType,
            asset.vatCharged,
            assetStart,
            asset.kind.periodYears(),
            0,
            asset.k0,
            asset.proportion0,
            null));

    if (costs == null) {
      return carriers;
    }
    String contradiction = buildingContradiction(costs);
    for (CostFacts cost : costs) {
      carriers.add(carrierFor(asset, assetStart, cost, suspendedYears, contradiction));
    }
    return carriers;
  }

  /**
   * Дали активът е СГРАДА е свойство на АКТИВА, обявено върху разхода — тъй че два разхода на един
   * актив не могат да си противоречат.
   *
   * <p>Противоречието е ОТКАЗ, не мълчаливо мнозинство: то значи, че някой е обявил един и същ имот
   * веднъж като сграда и веднъж като не-сграда, а от това зависи дали изобщо има носител.
   */
  static String buildingContradiction(List<CostFacts> costs) {
    Boolean seen = null;
    for (CostFacts cost : costs) {
      if (!cost.improvement) {
        continue;
      }
      if (seen == null) {
        seen = cost.assetIsBuilding;
      } else if (seen != cost.assetIsBuilding) {
        return "Противоречиво обявяване: за един и същ актив едни последващи разходи го сочат като"
            + " СГРАДА, а други — не. От това зависи дали изобщо възниква носител по § 1 т. 32"
            + " ДР ЗДДС, тъй че корекция не се изчислява, докато обявяването не се уеднакви.";
      }
    }
    return null;
  }

  /** Режимът и прозорецът на един последващ разход. */
  static Carrier carrierFor(
      AssetFacts asset,
      int assetStart,
      CostFacts cost,
      Set<Integer> suspendedYears,
      String contradiction) {
    if (contradiction != null) {
      return none(cost, contradiction);
    }
    if (!cost.improvement) {
      return none(
          cost,
          "Разходът не е обявен за „подобрение“ по § 1 т. 32 ДР ЗДДС — носител на начислен ДДС не"
              + " възниква. (Класификацията CAPITAL/CURRENT е по ЗКПО чл. 63/64 и не отговаря на"
              + " този въпрос.)");
    }

    if (cost.assetIsBuilding) {
      if (!cost.resultsInNewBuilding) {
        return none(
            cost,
            "Подобрение на СГРАДА, което не дава „нова сграда“ по § 1 т. 5 б. „в“ подт. „аа“:"
                + " § 1 т. 32 б. „а“ иска новата сграда като резултат, а чл. 79а ал. 10 и ППЗДДС"
                + " чл. 67 ал. 4 обхващат само недвижими имоти РАЗЛИЧНИ ОТ СГРАДА. Носител не"
                + " възниква по нито един от трите текста.");
      }
      // Чл. 79а ал. 9 — нова сграда ⇒ нов 20-годишен срок със СВОЕ начало.
      int start =
          BgArt79aCorrectionWindow.periodStartYear(
              AssetKind.IMMOVABLE_PROPERTY,
              cost.creditType == CreditType.NONE ? 0 : cost.creditYear,
              cost.creditType == CreditType.NONE ? cost.creditYear : 0,
              cost.firstUseYear);
      return new Carrier(
          Regime.NEW_TERM_ART9,
          cost.id,
          AssetKind.IMMOVABLE_PROPERTY,
          cost.creditType,
          cost.vatCharged,
          start,
          AssetKind.IMMOVABLE_PROPERTY.periodYears(),
          0,
          cost.k0,
          cost.proportion0,
          null);
    }

    // ППЗДДС чл. 67 ал. 4 — без нов срок; прозорецът свършва с този на заварения актив.
    AssetKind kind = cost.kind == null ? asset.kind : cost.kind;
    int start = cost.creditYear;
    int remaining =
        BgArt79aCorrectionWindow.remainingYears(
            assetStart, asset.kind.periodYears(), suspendedYears, start);
    if (remaining == 0) {
      return none(
          cost,
          "Срокът на заварения актив вече е изтекъл — ППЗДДС чл. 67 ал. 4 дава на подобрението само"
              + " „остатъка от годините“, а остатък няма. Чл. 80 ал. 1 т. 3/т. 4.");
    }
    return new Carrier(
        Regime.REMAINDER_ART67_4,
        cost.id,
        kind,
        cost.creditType,
        cost.vatCharged,
        start,
        kind.periodYears(),
        remaining,
        cost.k0,
        cost.proportion0,
        null);
  }

  private static Carrier none(CostFacts cost, String reason) {
    return new Carrier(
        Regime.NONE, cost.id, null, null, BigDecimal.ZERO, 0, 0, 0, null, null, reason);
  }
}

package com.axelor.apps.bglocalization.service.payroll;

import java.math.BigDecimal;

/**
 * ЕДИН ОТГОВОР НА ЕДИН ГЕНЕРАТОР ЗА ЕДНО ПЕРО — сума ИЛИ обявена причина да няма сума.
 *
 * <p><b>Защо резултат, а не {@code BigDecimal}.</b> Същият довод като при {@link
 * BgEarningsOutcome}: „сметнато е 109,87“, „по закон е нула и ред НЕ се прави“ и „трябваше да
 * сметна и не можах“ са ТРИ РАЗЛИЧНИ факта. Свити до число, и трите стават нула — а нула на фиша
 * значи „законно нула лева“, тоест най-скъпата възможна лъжа за перо, чийто вход просто липсва.
 * Правилото на модула: {@code 0} НЕ Е РЕШЕНИЕ.
 *
 * <p><b>⚠️ ЕДИН КЛАС ЗА ТРИ ГЕНЕРАТОРА, а не три почти еднакви — и това е решение, не пестене.</b>
 * {@link BgEarningsOutcome} и {@link BgSickLeaveOutcome} имат СВОИ речници на състоянията, защото
 * носят въпроси, които са само техни: {@code SOURCE_MISSING} и {@code NOT_THIS_ENGINE} значат нещо
 * единствено при обход на целия речник с типове пера, а {@code NO_EMPLOYER_DAYS} — единствено при
 * разделяне на дни между осигурител и ДОО. Трите генератора тук (обезщетения 9xx, осигуровки 5xx,
 * данък 7xx) задават ЕДИН И СЪЩ въпрос за всяко перо: записва ли се, или не, и защо. Тоест общият
 * клас е общ речник, а не общ калъп — и точно затова е един.
 *
 * <p><b>Кой изход какво значи и на кого е адресиран.</b> Разликата праща читателя на РАЗЛИЧНО място
 * да поправя:
 *
 * <ul>
 *   <li>{@link Status#COMPUTED} — перото се записва; {@link #amount} е закръглено по § 5.3.
 *   <li>{@link Status#LEGAL_ZERO} — сумата е нула ПО СМЕТКА или ПО ЗАКОН и ред НЕ се създава.
 *       Адресат: никой — това е нормалното състояние. Фонд с нулева ставка (ГВРС по ЗБДОО чл. 15
 *       ал. 1) и данъчна основа, свита до нула от личните вноски, са точно това.
 *   <li>{@link Status#DIVERGENT} — перото СЕ ЗАПИСВА, но нещо в източника му не съвпада с речника.
 *       Адресат: човекът, който гледа фиша. Виж по-долу защо това не е отказ.
 *   <li>{@link Status#REFUSED} — генераторът ТРЯБВАШЕ да сметне и НЕ МОЖА. Ред НЕ се създава.
 *       Адресат: операторът или seed-ът, и {@link #detail} казва КОЙ от двата.
 * </ul>
 *
 * <p><b>⚠️ {@link Status#LEGAL_ZERO} И {@link Status#REFUSED} НЕ СЕ СЛИВАТ</b>, колкото и еднакво
 * да изглеждат отвън — и в двата случая ред няма. Първото значи „така е вярно“; второто значи
 * „поправи нещо СЕГА“. Слети в едно състояние, отказите щяха да се четат като законна нула и никой
 * нямаше да ги поправи. Същата разлика като {@code INPUT_NOT_ASSEMBLED} срещу {@code
 * NO_CONTRIBUTIONS_FOUND} при осигуровките — една дума разлика, два различни файла за поправяне.
 *
 * <p><b>⚠️ {@link Status#DIVERGENT} НЕ Е ОТКАЗ, И ТОВА Е НАРОЧНО.</b> Перото се записва с
 * стойностите ОТ РЕЧНИКА (замразяване по копиране), защото те са правилото; разминаването се
 * ОБЯВЯВА, вместо да блокира фиша. Обратното — отказ — би спряло изплащането на обезщетение, чийто
 * документ е бил потвърден ПРЕДИ каталогът да се поправи, тоест по причина, за която лицето не
 * отговаря. А мълчанието би било по-лошо и от двете: фишът щеше да плаща по едни флагове, а
 * документът да твърди други, и никой не би узнал кой от двата е бил прав.
 *
 * <p>Обектът е НЕИЗМЕНЯЕМ и не пази нищо от базата.
 */
public final class BgPaySlipLineOutcome {

  /** Четирите изхода. Виж класовия javadoc за адресата на всеки. */
  public enum Status {
    COMPUTED,
    LEGAL_ZERO,
    DIVERGENT,
    REFUSED
  }

  /** Кой от четирите изхода. Никога {@code null}. */
  public final Status status;

  /**
   * Стабилният код на типа перо, за който е този отговор ({@code pl_dod}, {@code
   * pl_vn_pension_lice}, {@code pl_obez_kt_222_1}…), или името на източника, когато тип още не е
   * намерен.
   */
  public final String lineCode;

  /**
   * Сумата, закръглена до 2 знака — САМО при {@link Status#COMPUTED} и {@link Status#DIVERGENT}.
   *
   * <p>⚠️ {@code null} при другите две, и това е нарочно: поле, което при отказ носи нула, рано или
   * късно се сумира от някого заедно с истинските суми.
   *
   * <p>⚠️ И ТРИТЕ ГЕНЕРАТОРА САМО КОПИРАТ ТУК — § 5.3. Сумите идват вече закръглени от резолверите
   * ({@code BgContributionResolver} закръгля всяка вноска ПООТДЕЛНО по НЕВД чл. 2 ал. 4, {@code
   * BgPaySlipResolver#taxAmount} закръгля данъка, а обезщетението е замразено върху документа си).
   * Второ закръгляне тук би било свободно да се размине с първото.
   */
  public final BigDecimal amount;

  /**
   * ДОКУМЕНТАЦИЯ на сумата, не неин източник: осигурителната база при вноска, основата при данъка.
   */
  public final BigDecimal quantity;

  /** ДОКУМЕНТАЦИЯ: ставката на вноската или на данъка в проценти. Празно при обезщетение. */
  public final BigDecimal rate;

  /**
   * ЦЯЛО ИЗРЕЧЕНИЕ за човек — какво липсва и къде се поправя, не код за грешка.
   *
   * <p>Празно е само при {@link Status#COMPUTED}: сметната сума се обяснява сама от количеството и
   * ставката до нея.
   */
  public final String detail;

  private BgPaySlipLineOutcome(
      Status status,
      String lineCode,
      BigDecimal amount,
      BigDecimal quantity,
      BigDecimal rate,
      String detail) {
    this.status = status;
    this.lineCode = lineCode;
    this.amount = amount;
    this.quantity = quantity;
    this.rate = rate;
    this.detail = detail;
  }

  /** Сметнато перо: сумата плюс двете числа, които я обясняват. */
  public static BgPaySlipLineOutcome computed(
      String lineCode, BigDecimal amount, BigDecimal quantity, BigDecimal rate) {
    return new BgPaySlipLineOutcome(Status.COMPUTED, lineCode, amount, quantity, rate, null);
  }

  /** Сметнато перо, чийто източник се разминава с речника. Записва се ПО РЕЧНИКА. */
  public static BgPaySlipLineOutcome divergent(
      String lineCode, BigDecimal amount, BigDecimal quantity, BigDecimal rate, String detail) {
    return new BgPaySlipLineOutcome(Status.DIVERGENT, lineCode, amount, quantity, rate, detail);
  }

  /** Нула по сметка или по закон — ред НЕ се създава и никой не поправя нищо. */
  public static BgPaySlipLineOutcome legalZero(String lineCode, String detail) {
    return new BgPaySlipLineOutcome(Status.LEGAL_ZERO, lineCode, null, null, null, detail);
  }

  /** Генераторът трябваше да сметне и не можа. {@code detail} казва какво липсва. */
  public static BgPaySlipLineOutcome refused(String lineCode, String detail) {
    return new BgPaySlipLineOutcome(Status.REFUSED, lineCode, null, null, null, detail);
  }

  /**
   * Записва ли се перо от този отговор.
   *
   * <p>⚠️ ВКЛЮЧВА {@link Status#DIVERGENT}. Разминаването се обявява, а перото пак се записва — виж
   * класовия javadoc защо блокиране тук би било по-лошо.
   */
  public boolean isComputed() {
    return status == Status.COMPUTED || status == Status.DIVERGENT;
  }

  /** Иска ли този отговор някой да поправи нещо СЕГА. */
  public boolean isRefusal() {
    return status == Status.REFUSED;
  }

  /** Записано ли е перо, чийто източник спори с речника. */
  public boolean isDivergent() {
    return status == Status.DIVERGENT;
  }

  @Override
  public String toString() {
    return status
        + "["
        + lineCode
        + (amount == null ? "" : " = " + amount)
        + (detail == null ? "" : " — " + detail)
        + "]";
  }
}

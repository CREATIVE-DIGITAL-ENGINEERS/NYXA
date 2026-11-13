package com.axelor.apps.bglocalization.service.payroll;

import java.math.BigDecimal;

/**
 * ЕДИН ОТГОВОР НА ЕНДЖИНА ЗА ЕДИН ТИП ПЕРО — сума ИЛИ обявена причина да няма сума.
 *
 * <p><b>Защо резултат, а не {@code BigDecimal}.</b> Четирите изхода на този енджин НЕ са едно
 * число: „сметнато е 120,00“, „източникът за нощния труд още не съществува“, „това перо се въвежда
 * на ръка“ и „не мога, защото основната заплата не е попълнена“ са четири РАЗЛИЧНИ факта. Свити до
 * число, и четирите стават нула — а нула на фиша значи „законно нула лева“, тоест най-скъпата
 * възможна лъжа за перо, чийто източник просто липсва. Оттам правилото на модула: {@code 0} НЕ Е
 * РЕШЕНИЕ.
 *
 * <p><b>Кой изход какво значи и на кого е адресиран.</b> Разликата не е козметична — тя праща
 * читателя на РАЗЛИЧНО място да поправя:
 *
 * <ul>
 *   <li>{@link Status#COMPUTED} — перото се записва; {@link #amount} е закръглено по § 5.3.
 *   <li>{@link Status#SOURCE_MISSING} — типът е РАЗПОЗНАТ, формулата му е известна, но входът му
 *       още няма източник в модела (часовете са {@code DECLARED_ABSENT} до WS-5). Ред НЕ се
 *       създава. Адресат: WS-5, не операторът.
 *   <li>{@link Status#NOT_THIS_ENGINE} — перото не е работа на начисленията ({@code MANUAL_AMOUNT}
 *       се въвежда на ръка, {@code DERIVED} идва от осигуровките, обезщетенията и ретро-делтата).
 *       Адресат: никой — това е нормалното състояние.
 *   <li>{@link Status#NOT_ENTITLED} — типът е РАЗПОЗНАТ, входът му е ПЪЛЕН и формулата би дала
 *       число, но на ТОВА лице перото НЕ СЕ ДЪЛЖИ ПО ЗАКОН. Днес единственият такъв случай е класът
 *       прослужено време при неТПО: НСОРЗ чл. 12 ал. 1 го дава само на работниците и служителите по
 *       индивидуален трудов договор. Ред НЕ се създава, а вече създаден се МАХА. Адресат: никой —
 *       това е верният фиш.
 *   <li>{@link Status#REFUSED} — енджинът ТРЯБВАШЕ да сметне и НЕ МОЖА: липсва основна заплата,
 *       липсва норма, липсва котвата на стажа, липсва параметърът {@code SENIORITY_MIN_PCT} или
 *       {@code bgCalculationBase} носи стойност, която дисчпачът не познава. Адресат: операторът
 *       или seed-ът, и {@link #detail} казва КОЙ от двата.
 * </ul>
 *
 * <p><b>⚠️ {@link Status#NOT_ENTITLED} НЕ Е СУМА НУЛА, И ТОВА Е ЦЕЛИЯТ СМИСЪЛ НА ОТДЕЛНОТО
 * СЪСТОЯНИЕ.</b> Перо „клас прослужено време — 0,00“ върху фиш на управител по ДУК твърди, че
 * класът СЕ ДЪЛЖИ и този месец излиза нула лева — а истината е, че не се дължи изобщо, нито този
 * месец, нито някой друг. Свито до число, „не се дължи“ става неразличимо от „дължи се, но е нула“,
 * и първият читател, който събере перата, ще ги събере еднакво. Оттам правилото на модула,
 * повторено тук: {@code 0} НЕ Е РЕШЕНИЕ.
 *
 * <p>⚠️ И НЕ Е {@link Status#NOT_THIS_ENGINE}: онова значи „друга услуга пише това перо“, тоест
 * число ЩЕ СЕ ПОЯВИ някъде другаде. Тук няма да се появи никъде.
 *
 * <p>⚠️ {@link Status#SOURCE_MISSING} и {@link Status#REFUSED} не се сливат, колкото и да си
 * приличат отвън. Първото значи „ела пак след WS-5“ и е ОЧАКВАНО днес; второто значи „поправи нещо
 * СЕГА“. Слети в едно състояние, отказите за непопълнена заплата щяха да се четат като „чака се
 * входният слой“ и никой нямаше да ги поправи. Същата разлика като {@code INPUT_NOT_ASSEMBLED}
 * срещу {@code NO_CONTRIBUTIONS_FOUND} при осигуровките — една дума разлика, два различни файла за
 * поправяне.
 *
 * <p>Обектът е НЕИЗМЕНЯЕМ и не пази нищо от базата: произвежда се от чистата половина на енджина и
 * се чете от базовата.
 */
public final class BgEarningsOutcome {

  /** Петте изхода. Виж класовия javadoc за адресата на всеки. */
  public enum Status {
    COMPUTED,
    SOURCE_MISSING,
    NOT_THIS_ENGINE,
    NOT_ENTITLED,
    REFUSED
  }

  /** Кой от петте изхода. Никога {@code null}. */
  public final Status status;

  /**
   * Стабилният код на типа перо, за който е този отговор ({@code pl_osnovna}, {@code pl_tspo}…).
   */
  public final String lineCode;

  /**
   * Сумата, закръглена до 2 знака по „половина нагоре“ (§ 5.3) — САМО при {@link Status#COMPUTED}.
   *
   * <p>{@code null} при четирите останали, и това е нарочно: поле, което при отказ носи нула, рано
   * или късно се сумира от някого заедно с истинските суми.
   */
  public final BigDecimal amount;

  /**
   * ДОКУМЕНТАЦИЯ на сумата, не неин източник: отработените дни при основната, навършените години
   * при класа. Влиза в {@code BgPaySlipLine.bgQuantity}, за да се чете откъде идва числото.
   */
  public final BigDecimal quantity;

  /** ДОКУМЕНТАЦИЯ: месечната основна при основното перо, процентът за година при класа. */
  public final BigDecimal rate;

  /**
   * ЦЯЛО ИЗРЕЧЕНИЕ за човек — какво липсва и къде се поправя, не код за грешка.
   *
   * <p>Празно е само при {@link Status#COMPUTED}: сметната сума се обяснява сама от количеството и
   * ставката до нея.
   */
  public final String detail;

  private BgEarningsOutcome(
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
  public static BgEarningsOutcome computed(
      String lineCode, BigDecimal amount, BigDecimal quantity, BigDecimal rate) {
    return new BgEarningsOutcome(Status.COMPUTED, lineCode, amount, quantity, rate, null);
  }

  /** Разпознат тип без източник за входа си — чака WS-5. Ред НЕ се създава. */
  public static BgEarningsOutcome sourceMissing(String lineCode, String detail) {
    return new BgEarningsOutcome(Status.SOURCE_MISSING, lineCode, null, null, null, detail);
  }

  /** Перо извън обхвата на този енджин — ръчно въведено или производно. */
  public static BgEarningsOutcome notThisEngine(String lineCode, String detail) {
    return new BgEarningsOutcome(Status.NOT_THIS_ENGINE, lineCode, null, null, null, detail);
  }

  /**
   * Перо, което на ТОВА лице не се дължи по закон. Ред не се създава, а заварен се МАХА.
   *
   * <p>⚠️ {@code detail} назовава НОРМАТА, а не липса: тук нищо не липсва — правото го няма.
   */
  public static BgEarningsOutcome notEntitled(String lineCode, String detail) {
    return new BgEarningsOutcome(Status.NOT_ENTITLED, lineCode, null, null, null, detail);
  }

  /** Енджинът трябваше да сметне и не можа. {@code detail} казва какво липсва. */
  public static BgEarningsOutcome refused(String lineCode, String detail) {
    return new BgEarningsOutcome(Status.REFUSED, lineCode, null, null, null, detail);
  }

  /** Записва ли се перо от този отговор. */
  public boolean isComputed() {
    return status == Status.COMPUTED;
  }

  /** Иска ли този отговор някой да поправи нещо СЕГА. */
  public boolean isRefusal() {
    return status == Status.REFUSED;
  }

  /**
   * Отрича ли този отговор самото ПРАВО на перото — тоест трябва ли заварен ред да се МАХНЕ.
   *
   * <p>⚠️ Отделен въпрос, а не отрицание на {@link #isComputed()}: {@code SOURCE_MISSING} и {@code
   * REFUSED} също не са сметнати, но при тях заварен ред ОСТАВА — той може да е точно ръчно
   * въведеното, което тези два изхода очакват от оператора.
   */
  public boolean isNotEntitled() {
    return status == Status.NOT_ENTITLED;
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

package com.axelor.apps.bglocalization.service.payroll;

import java.math.BigDecimal;

/**
 * ЕДИН ОТГОВОР НА ЕНДЖИНА ЗА БОЛНИЧНИ — сума ИЛИ обявена причина да няма сума.
 *
 * <p>Огледален на {@link BgEarningsOutcome} и по същия довод: четирите изхода НЕ са едно число.
 * „Сметнато е 109,87“, „този месец няма дни за сметка на осигурителя“, „епизодите нямат източник в
 * модела“ и „не мога, защото осигурителният стаж не е подаден“ са четири РАЗЛИЧНИ факта. Свити до
 * число, и четирите стават нула — а нула на фиша значи „законно нула евро болничен“, тоест точно
 * лъжата, която не се вижда.
 *
 * <p><b>Кой изход какво значи и на кого е адресиран.</b>
 *
 * <ul>
 *   <li>{@link Status#COMPUTED} — перото се записва; {@link #amount} е закръглено по § 5.3.
 *   <li>{@link Status#NO_EMPLOYER_DAYS} — сметнато е и отговорът е НУЛА ДНИ: няма епизоди, или
 *       епизодът е майчинство и бащинство (осигурителят не плаща първите дни), или стажът е под
 *       шест месеца, или дните падат в друг период. Ред НЕ се създава — перо със сума 0,00 би
 *       твърдяло, че болничен ИМА и е нулев. Адресат: никой, това е нормалното състояние.
 *   <li>{@link Status#SOURCE_MISSING} — правилото е известно, но входът му няма източник в модела.
 *       ⚠️ ДНЕС ТОВА Е ПОЛОЖЕНИЕТО НА ЦЕЛИЯ ЕНДЖИН: {@code BgPayrollInputBuilder.DECLARED_ABSENT}
 *       обявява и {@code sickLeaveEpisodes}, и {@code insuranceServiceMonths} за без източник,
 *       тоест в производство списъкът с епизоди е винаги празен. Адресат: WS-5, не операторът.
 *   <li>{@link Status#REFUSED} — енджинът ТРЯБВАШЕ да сметне и НЕ МОЖА: липсва основна заплата,
 *       липсва норма, липсва процентът в речника на отсъствията, липсва осигурителният стаж при
 *       епизод, който го изисква. Адресат: операторът или seed-ът, и {@link #detail} казва кой.
 * </ul>
 *
 * <p>⚠️ {@link Status#NO_EMPLOYER_DAYS} и {@link Status#REFUSED} не се сливат, колкото и еднакво да
 * изглеждат отвън — и двете не създават ред. Първото значи „всичко е наред, няма какво да се
 * плати“; второто — „поправи нещо СЕГА, инак лицето остава без болничен, който му се дължи“.
 *
 * <p>Обектът е НЕИЗМЕНЯЕМ и не пази нищо от базата.
 */
public final class BgSickLeaveOutcome {

  /** Четирите изхода. Виж класовия javadoc за адресата на всеки. */
  public enum Status {
    COMPUTED,
    NO_EMPLOYER_DAYS,
    SOURCE_MISSING,
    REFUSED
  }

  /** Кой от четирите изхода. Никога {@code null}. */
  public final Status status;

  /** Стабилният код на типа перо, за който е този отговор. */
  public final String lineCode;

  /**
   * Сумата, закръглена до 2 знака по „половина нагоре“ (§ 5.3) — САМО при {@link Status#COMPUTED}.
   *
   * <p>{@code null} при трите останали, и това е нарочно: поле, което при отказ носи нула, рано или
   * късно се сумира от някого заедно с истинските суми.
   */
  public final BigDecimal amount;

  /**
   * ДОКУМЕНТАЦИЯ на сумата, не неин източник: РАБОТНИТЕ ДНИ, за които се плаща. Влиза в {@code
   * BgPaySlipLine.bgQuantity}, за да се чете откъде идва числото.
   */
  public final BigDecimal quantity;

  /** ДОКУМЕНТАЦИЯ: среднодневната база, върху която е сметнат процентът. Влиза в {@code bgRate}. */
  public final BigDecimal rate;

  /** ЦЯЛО ИЗРЕЧЕНИЕ за човек. Празно е само при {@link Status#COMPUTED}. */
  public final String detail;

  private BgSickLeaveOutcome(
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
  public static BgSickLeaveOutcome computed(
      String lineCode, BigDecimal amount, BigDecimal quantity, BigDecimal rate) {
    return new BgSickLeaveOutcome(Status.COMPUTED, lineCode, amount, quantity, rate, null);
  }

  /** Сметнато е и отговорът е нула дни за сметка на осигурителя. Ред НЕ се създава. */
  public static BgSickLeaveOutcome noEmployerDays(String lineCode, String detail) {
    return new BgSickLeaveOutcome(Status.NO_EMPLOYER_DAYS, lineCode, null, null, null, detail);
  }

  /** Правилото е известно, входът му няма източник в модела — чака WS-5. */
  public static BgSickLeaveOutcome sourceMissing(String lineCode, String detail) {
    return new BgSickLeaveOutcome(Status.SOURCE_MISSING, lineCode, null, null, null, detail);
  }

  /** Енджинът трябваше да сметне и не можа. {@code detail} казва какво липсва. */
  public static BgSickLeaveOutcome refused(String lineCode, String detail) {
    return new BgSickLeaveOutcome(Status.REFUSED, lineCode, null, null, null, detail);
  }

  /** Записва ли се перо от този отговор. */
  public boolean isComputed() {
    return status == Status.COMPUTED;
  }

  /** Иска ли този отговор някой да поправи нещо СЕГА. */
  public boolean isRefusal() {
    return status == Status.REFUSED;
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

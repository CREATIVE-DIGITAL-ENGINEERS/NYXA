package com.axelor.apps.bglocalization.service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Result of resolving the insurable income base: {@code min(max(gross, МОД floor), cap)}.
 *
 * <p>Immutable and Axelor-free. Carries the cap value and the period it comes from so a diagnostic
 * screen can show which cap was applied — the 2026 cap rose from 2111.64 to 2300 EUR on 01.08.2026,
 * so the applied period matters. A missing cap parameter blocks rather than silently skipping the
 * ceiling.
 */
public class BgInsurableIncomeResult {

  public enum Status {
    RESOLVED,
    /**
     * Базата е определена, но КООРДИНИРАНО с деклариран доход при друг платец — КСО чл. 6 ал. 11.
     *
     * <p>⚠️ Отделно от {@code RESOLVED} нарочно: при координация МОД подът и единичният таван на
     * ТОВА правоотношение НЕ се прилагат, тоест едно и също число значи различно нещо според това
     * по кой път е излязло. Виж {@link BgInsurableIncomeResult#coordinated}.
     */
    COORDINATED,
    /**
     * The MAX_INSURABLE_INCOME parameter is missing for the date — the ceiling cannot be applied.
     */
    MISSING_CAP,
    /**
     * The work-time facts cannot produce a proration coefficient — a zero or negative denominator,
     * or a negative numerator. Blocking on purpose: dividing by zero, or quietly treating the month
     * as full, would understate or overstate the base with no trace.
     */
    INVALID_WORK_TIME,
    /**
     * The МОД lookup returned a blocking status — the floor could not be determined, so the base
     * must not be computed.
     *
     * <p>Deliberately distinct from {@code BgMinIncomeResult.Status.NOT_APPLICABLE}: there the
     * floor legitimately does not exist for the insured type and the gross stands on its own, which
     * is a correct answer. Here the floor exists in principle but the data needed to find it (НКПД,
     * НКИД, an Appendix 1 row) is missing or ambiguous. Collapsing the two would drop the floor and
     * still report RESOLVED — a lower base with nothing on screen to say why.
     */
    MOD_BLOCKED,
    /**
     * The insured type declares a statutory floor ({@code BgInsuredType.bgFloorType}) but the
     * {@code BgPayrollParameter} row naming it is missing for the date. Blocking for the same
     * reason as a missing cap: computing without the floor returns a <em>smaller</em> base, and a
     * base that is too low is the direction that does not announce itself.
     */
    MISSING_FLOOR,
    /**
     * Декларираният чужд осигурителен доход стои на ПО-НИСКО ниво по КСО чл. 6 ал. 11 от това
     * правоотношение. Формулата на координацията реже ТОВА правоотношение, а редът иска обратното —
     * и едно число не изразява ред между различни нива. Виж {@link
     * BgIncomeCoordinationResolver.Status#DECLARED_LOWER_PRIORITY}.
     */
    DECLARED_LOWER_PRIORITY,
    /**
     * Морско лице с деклариран доход от друг платец. Чл. 4а ал. 6 КСО подрежда осигурителния доход
     * на морското лице на СВОЕ ниво и кое от нивата се реже не следва от едно число. Морското лице
     * е отделно парче работа и до него координацията НЕ се прилага мълчаливо.
     */
    SEAFARER_MULTI_SOURCE,
    /**
     * Правилото на гражданския договор (КСО чл. 6 ал. 4 / чл. 4 ал. 3 т. 5) не може да се приложи —
     * липсва разходната ставка по чл. 29 ЗДДФЛ или минималната работна заплата за периода.
     *
     * <p>⚠️ БЛОКИРА, ЗАЩОТО ДВЕТЕ ЛИПСИ ВОДЯТ В ПРОТИВОПОЛОЖНИ ПОСОКИ И НИТО ЕДНАТА НЕ СЕ ВИЖДА.
     * Без ставката базата излиза ЗАВИШЕНА (пълното възнаграждение вместо намаленото); без МРЗ не
     * може да се каже дали изобщо възниква осигуряване. И в двата случая сметка без параметъра
     * връща правдоподобно число и чист зелен резултат. Виж {@link BgCivilContractResolver.Status}.
     *
     * <p>⚠️ И НЕ СЕ БЪРКА С {@code BgCivilContractResolver.Status#BELOW_THRESHOLD}. Той е ЗАКОНЕН
     * изход с база нула и минава за {@code RESOLVED}: под прага по т. 5 вноски просто не се дължат.
     * Слети в едно, законната нула би спирала фиша.
     */
    CIVIL_CONTRACT_BLOCKED
  }

  private final Status status;
  private final BigDecimal amount;
  private final BigDecimal capAmount;
  private final LocalDate capValidFrom;
  private final String detail;
  private final BigDecimal otherPayerIncome;

  private BgInsurableIncomeResult(
      Status status,
      BigDecimal amount,
      BigDecimal capAmount,
      LocalDate capValidFrom,
      String detail,
      BigDecimal otherPayerIncome) {
    this.status = status;
    this.amount = amount;
    this.capAmount = capAmount;
    this.capValidFrom = capValidFrom;
    this.detail = detail;
    this.otherPayerIncome = otherPayerIncome;
  }

  public static BgInsurableIncomeResult resolved(
      BigDecimal amount, BigDecimal capAmount, LocalDate capValidFrom) {
    return new BgInsurableIncomeResult(
        Status.RESOLVED, amount, capAmount, capValidFrom, null, null);
  }

  /**
   * РАЗРЕШЕНА, НО КООРДИНИРАНА база по КСО чл. 6 ал. 11 — {@code min(собствен, MAX − деклариран)},
   * не под нула.
   *
   * <p><b>⚠️ ОТДЕЛЕН ВХОД, А НЕ {@link #resolved} с друго число.</b> Двете носят различно ТВЪРДЕНИЕ
   * при една и съща сума: 300,00 по нормалния път значи „доходът е 300,00 и е над всеки праг“, а
   * 300,00 по координирания значи „доходът е 300,00, МОД подът НЕ е приложен, защото прагът вече е
   * покрит при другия платец“. Слети в едно, екранът не може да каже кое от двете чете, а разликата
   * е точно тази, която прави нулата на Николай законна вместо подозрителна.
   *
   * @param otherPayerIncome декларираният чужд доход — носи се нататък, за да може отказът или
   *     екранът да покаже ЗАЩО базата е такава, вместо само КАКВА е
   */
  public static BgInsurableIncomeResult coordinated(
      BigDecimal amount,
      BigDecimal capAmount,
      LocalDate capValidFrom,
      BigDecimal otherPayerIncome) {
    return new BgInsurableIncomeResult(
        Status.COORDINATED, amount, capAmount, capValidFrom, null, otherPayerIncome);
  }

  public static BgInsurableIncomeResult missingCap(String detail) {
    return new BgInsurableIncomeResult(Status.MISSING_CAP, null, null, null, detail, null);
  }

  public static BgInsurableIncomeResult invalidWorkTime(String detail) {
    return new BgInsurableIncomeResult(Status.INVALID_WORK_TIME, null, null, null, detail, null);
  }

  public static BgInsurableIncomeResult modBlocked(String detail) {
    return new BgInsurableIncomeResult(Status.MOD_BLOCKED, null, null, null, detail, null);
  }

  public static BgInsurableIncomeResult missingFloor(String detail) {
    return new BgInsurableIncomeResult(Status.MISSING_FLOOR, null, null, null, detail, null);
  }

  /** Декларираното стои на по-ниско ниво по чл. 6 ал. 11 — един скалар не изразява ред. */
  public static BgInsurableIncomeResult declaredLowerPriority(
      String detail, BigDecimal otherPayerIncome) {
    return new BgInsurableIncomeResult(
        Status.DECLARED_LOWER_PRIORITY, null, null, null, detail, otherPayerIncome);
  }

  /**
   * Правилото на гражданския договор не може да се приложи — вж. {@link
   * Status#CIVIL_CONTRACT_BLOCKED}.
   */
  public static BgInsurableIncomeResult civilContractBlocked(String detail) {
    return new BgInsurableIncomeResult(
        Status.CIVIL_CONTRACT_BLOCKED, null, null, null, detail, null);
  }

  /** Морско лице с деклариран доход от друг платец — чл. 4а ал. 6, отделно парче. */
  public static BgInsurableIncomeResult seafarerMultiSource(
      String detail, BigDecimal otherPayerIncome) {
    return new BgInsurableIncomeResult(
        Status.SEAFARER_MULTI_SOURCE, null, null, null, detail, otherPayerIncome);
  }

  public Status getStatus() {
    return status;
  }

  /**
   * ⚠️ И КООРДИНИРАНАТА БАЗА Е РАЗРЕШЕНА. Повикващите питат едно: има ли база, с която да се смята.
   * Ако {@code COORDINATED} падаше извън този въпрос, всеки фиш на второ правоотношение щеше да се
   * откаже с „осигурителната база не е определена“ — при напълно определена база.
   */
  public boolean isResolved() {
    return status == Status.RESOLVED || status == Status.COORDINATED;
  }

  /** Координирана ли е базата по чл. 6 ал. 11 — тоест приложен ли е остатъкът до тавана. */
  public boolean isCoordinated() {
    return status == Status.COORDINATED;
  }

  public boolean isBlocking() {
    return !isResolved();
  }

  /** The insurable base. */
  public BigDecimal getAmount() {
    return amount;
  }

  /** The applied ceiling — for transparency. */
  public BigDecimal getCapAmount() {
    return capAmount;
  }

  /** The period the applied ceiling comes from — for transparency. */
  public LocalDate getCapValidFrom() {
    return capValidFrom;
  }

  /**
   * Декларираният доход при друг платец, когато базата е координирана — инак {@code null}.
   *
   * <p>За прозрачност, по същия довод като {@link #getCapAmount}: база 0,00 без числото, което я е
   * изяло, е неразличима от база 0,00 при липса на начисления.
   */
  public BigDecimal getOtherPayerIncome() {
    return otherPayerIncome;
  }

  public String getDetail() {
    return detail;
  }
}

package com.axelor.apps.bglocalization.service;

import java.math.BigDecimal;

/**
 * Monthly work-time facts used to prorate the МОД floor.
 *
 * <p>НЕВД art. 1 par. 4 prorates by hours, art. 1 par. 3 by days. Both denominators are monthly —
 * the statutory hours <em>for the month</em> and the working days <em>in the month</em> — never a
 * daily norm. That distinction is the whole reason this class carries hours instead of a ready-made
 * ratio: for 40 contracted hours a month over 21 working days the daily ratio gives 2/8 = 0.2500
 * while the correct figure is 40/168 = 0.2381, some 5% of the base. A caller that can only supply a
 * ratio has already thrown away the information needed to get this right.
 *
 * <p>The days (hours) under art. 40 par. 5 <b>КСО</b> — the opening days of temporary incapacity
 * paid by the employer — count towards the numerator. They are carried separately rather than
 * folded into the worked figure so a caller cannot drop them silently: an unset field is visible
 * here, a forgotten addition inside the caller is not.
 *
 * <p><b>⚠️ ДВА РАБОТНИ ДНИ, НЕ ТРИ.</b> This javadoc used to say "the first three days". Чл. 40 ал.
 * 5 КСО (изм. ДВ бр. 106/2023, в сила от 01.01.2024): осигурителят изплаща за първите ДВА работни
 * дни от временната неработоспособност 70 на сто от среднодневното брутно възнаграждение за месеца,
 * в който е настъпила неработоспособността, но не по-малко от 70 на сто от среднодневното уговорено
 * възнаграждение. Преди 2024 г. дните бяха три.
 *
 * <p>The number itself is <em>not</em> used here: this class only carries whatever days the caller
 * supplies. It is corrected because a stale number in a javadoc is read as fact by the next author.
 *
 * <p><b>⚠️ КОЙ ЧЛ. 40 АЛ. 5.</b> КСО, not ЗЗО. Both acts have an art. 40 par. 5 and both are used
 * in this module: КСО art. 40 par. 5 is the employer-paid opening days of sick leave, ЗЗО art. 40
 * par. 5 is the health-contribution base during unpaid leave ({@code HEALTH_UNPAID_LEAVE_BASE}).
 * The field names below say only "art40Par5" — read them as КСО.
 *
 * <p>Plain immutable POJO — no Axelor, no Guice — so the arithmetic using it stays unit testable in
 * isolation, like {@link BgMinIncomeResult} and {@link BgContributionRequest}.
 */
public class BgWorkTimeFacts {

  private final BigDecimal statutoryMonthlyHours;
  private final BigDecimal workedHours;
  private final BigDecimal art40Par5Hours;
  private final BigDecimal workingDaysInMonth;
  private final BigDecimal workedDays;
  private final BigDecimal art40Par5Days;

  public BgWorkTimeFacts(
      BigDecimal statutoryMonthlyHours,
      BigDecimal workedHours,
      BigDecimal art40Par5Hours,
      BigDecimal workingDaysInMonth,
      BigDecimal workedDays,
      BigDecimal art40Par5Days) {
    this.statutoryMonthlyHours = statutoryMonthlyHours;
    this.workedHours = workedHours;
    this.art40Par5Hours = art40Par5Hours;
    this.workingDaysInMonth = workingDaysInMonth;
    this.workedDays = workedDays;
    this.art40Par5Days = art40Par5Days;
  }

  /**
   * No proration: a full month on full working time. Every caller that knows nothing about work
   * time gets this, so its result is the pre-proration behaviour unchanged.
   */
  public static BgWorkTimeFacts fullMonth() {
    return new BgWorkTimeFacts(null, null, null, null, null, null);
  }

  /** Proration by hours — НЕВД art. 1 par. 4. */
  public static BgWorkTimeFacts byHours(
      BigDecimal statutoryMonthlyHours, BigDecimal workedHours, BigDecimal art40Par5Hours) {
    return new BgWorkTimeFacts(
        statutoryMonthlyHours, workedHours, art40Par5Hours, null, null, null);
  }

  /** Proration by days — НЕВД art. 1 par. 3. */
  public static BgWorkTimeFacts byDays(
      BigDecimal workingDaysInMonth, BigDecimal workedDays, BigDecimal art40Par5Days) {
    return new BgWorkTimeFacts(null, null, null, workingDaysInMonth, workedDays, art40Par5Days);
  }

  /** Statutory working hours for the month — the art. 1 par. 4 denominator. */
  public BigDecimal getStatutoryMonthlyHours() {
    return statutoryMonthlyHours;
  }

  public BigDecimal getWorkedHours() {
    return workedHours;
  }

  /** Hours matching the days under art. 40 par. 5 КСО — part of the art. 1 par. 4 numerator. */
  public BigDecimal getArt40Par5Hours() {
    return art40Par5Hours;
  }

  /** Working days in the month — the art. 1 par. 3 denominator. */
  public BigDecimal getWorkingDaysInMonth() {
    return workingDaysInMonth;
  }

  public BigDecimal getWorkedDays() {
    return workedDays;
  }

  /** Days under art. 40 par. 5 КСО — part of the art. 1 par. 3 numerator. */
  public BigDecimal getArt40Par5Days() {
    return art40Par5Days;
  }

  /**
   * True when any hour figure was supplied — presence, not a usable denominator.
   *
   * <p>Deliberately not {@code statutoryMonthlyHours > 0}: a caller that supplies worked hours but
   * leaves the statutory hours at zero has a data error, and it has to surface as a blocking result
   * rather than fall through to the day rule or, worse, to no proration at all and a silently
   * over-stated base.
   */
  public boolean hasHourData() {
    return statutoryMonthlyHours != null || workedHours != null || art40Par5Hours != null;
  }

  /** True when any day figure was supplied. Same presence-not-validity reasoning as hours. */
  public boolean hasDayData() {
    return workingDaysInMonth != null || workedDays != null || art40Par5Days != null;
  }

  @Override
  public String toString() {
    if (hasHourData()) {
      return "BgWorkTimeFacts[hours "
          + workedHours
          + "+"
          + art40Par5Hours
          + "/"
          + statutoryMonthlyHours
          + "]";
    }
    if (hasDayData()) {
      return "BgWorkTimeFacts[days "
          + workedDays
          + "+"
          + art40Par5Days
          + "/"
          + workingDaysInMonth
          + "]";
    }
    return "BgWorkTimeFacts[fullMonth]";
  }
}

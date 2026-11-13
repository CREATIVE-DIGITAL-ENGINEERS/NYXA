package com.axelor.apps.bglocalization.service.payroll;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * БАЗАТА НА СРЕДНОДНЕВНОТО ПО ЧЛ. 40 АЛ. 5 КСО ЗА ЕДИН МЕСЕЦ НА НАСТЪПВАНЕ — числата или защо ги
 * няма.
 *
 * <p><b>Основание.</b> Осигурителят плаща първите два работни дни „в размер 70 на сто от
 * среднодневното брутно възнаграждение за МЕСЕЦА, В КОЙТО Е НАСТЪПИЛА неработоспособността, но не
 * по-малко от 70 на сто от среднодневното уговорено възнаграждение“ (изм. ДВ бр. 106/2023, в сила
 * от 01.01.2024). И двете среднодневни — брутното и уговореното — са за месеца на настъпване, не за
 * месеца на фиша, който плаща деня.
 *
 * <p><b>⚠️ ЧИСЛИТЕЛ И ЗНАМЕНАТЕЛ ИДВАТ ОТ ЕДИН И СЪЩИ ФИШ.</b> {@link #monthlyBase} и {@link
 * #workingDays} са на фиша за {@link #month}: при настъпване в текущия месец — на самия фиш, при
 * настъпване в предходен — на ПОТВЪРДЕНИЯ фиш за онзи месец. Смесени (сбор от единия, работни дни
 * от другия), те дават среднодневно на месец, който не съществува.
 *
 * <p>Обектът е НЕИЗМЕНЯЕМ: {@code BgSickLeaveService} го сглобява, {@link BgSickLeaveResolver} го
 * чете.
 */
public final class BgSickLeaveOnsetBase {

  /** Месецът на настъпване, за който е базата. */
  public final YearMonth month;

  /**
   * Месечната база на среднодневното БРУТНО — по решение № 1 постоянното месечно възнаграждение
   * (виж {@link BgSickLeaveResolver#DAILY_BASE_IS_CONTRACTED_SALARY}). Единица: ЕВРО на месец.
   */
  public final BigDecimal monthlyBase;

  /** Уговореното месечно възнаграждение за пода по ал. 5. Единица: ЕВРО на месец. */
  public final BigDecimal contractedMonthly;

  /** Работните дни на месеца на настъпване — делителят. Единица: РАБОТНИ ДНИ. */
  public final Integer workingDays;

  /**
   * ЦЯЛО ИЗРЕЧЕНИЕ — защо базата за този месец не може да се вземе (напр. няма потвърден фиш), или
   * {@code null}.
   */
  public final String refusal;

  private BgSickLeaveOnsetBase(
      YearMonth month,
      BigDecimal monthlyBase,
      BigDecimal contractedMonthly,
      Integer workingDays,
      String refusal) {
    this.month = month;
    this.monthlyBase = monthlyBase;
    this.contractedMonthly = contractedMonthly;
    this.workingDays = workingDays;
    this.refusal = refusal;
  }

  /** Базата е намерена — числата са от фиша за {@code month}. */
  public static BgSickLeaveOnsetBase of(
      YearMonth month, BigDecimal monthlyBase, BigDecimal contractedMonthly, Integer workingDays) {
    return new BgSickLeaveOnsetBase(month, monthlyBase, contractedMonthly, workingDays, null);
  }

  /** Базата за {@code month} не може да се вземе — {@code refusal} казва защо. */
  public static BgSickLeaveOnsetBase refused(YearMonth month, String refusal) {
    return new BgSickLeaveOnsetBase(month, null, null, null, refusal);
  }

  @Override
  public String toString() {
    return refusal == null
        ? "BgSickLeaveOnsetBase[" + month + ": " + monthlyBase + " / " + workingDays + "]"
        : "BgSickLeaveOnsetBase[" + month + ", отказ: " + refusal + "]";
  }
}

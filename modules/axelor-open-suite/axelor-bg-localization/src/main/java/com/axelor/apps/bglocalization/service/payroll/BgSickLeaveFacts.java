package com.axelor.apps.bglocalization.service.payroll;

import java.math.BigDecimal;

/**
 * ЧИСЛАТА, ОТ КОИТО ЗАВИСЯТ БОЛНИЧНИЯТ И ЗДРАВНАТА ВНОСКА — извадени веднъж, подадени на резолвера.
 *
 * <p><b>Защо обект, а не шест аргумента.</b> Същият довод като при {@link BgEarningsFacts}: шест
 * числа като аргументи се пренареждат при първата добавка и {@code (база, уговорено, норма)} тихо
 * става {@code (уговорено, база, норма)} — грешка, която компилаторът НЕ вижда, защото и трите са
 * числа. Като полета с имена не се пренареждат.
 *
 * <p><b>⚠️ {@code null} ТУК ЗНАЧИ „НЕ ЗНАМ“, НЕ НУЛА.</b> Всяко поле е обвит тип точно затова:
 * {@code compensationPct} нула значи „вид отсъствие без парично обезщетение“ и е ЗАКОНЕН случай, а
 * {@code null} значи, че редът в речника не е намерен. Двете водят до различен изход — липса на
 * перо срещу отказ — и класът съществува, за да не се слеят по пътя.
 *
 * <p><b>⚠️ ДВЕ ОТДЕЛНИ БАЗИ, КОИТО НЕ СЕ СЛИВАТ.</b> {@link #monthlyBase} е база на
 * ВЪЗНАГРАЖДЕНИЕТО по чл. 40 ал. 5 КСО; {@link #healthAbsenceBase} е база на ЗДРАВНАТА ВНОСКА по
 * ЗЗО чл. 40 ал. 1 т. 5 и е законов параметър, не заплата. Слети в едно поле, здравната вноска щеше
 * да се смята върху заплатата на лицето — по-голяма сума, и то за сметка на работодателя.
 *
 * <p><b>⚠️ ДНИТЕ НЕ СА ТУК, И ТОВА НЕ Е ПРОПУСК.</b> Разделянето между осигурител и ДОО живее в
 * {@link BgSickLeaveDays} — заедно с причината, когато е отказано. Копирани и тук, двата броя щяха
 * да са свободни да се разминат с обяснението си, а „нула дни, защото майчинство“ и „нула дни,
 * защото стажът е неизвестен“ водят до различен фиш. Затова резолверът приема ДВАТА обекта: числа и
 * разделяне.
 *
 * <p>Обектът е НЕИЗМЕНЯЕМ и не пази нищо от базата: {@code BgSickLeaveService} го сглобява, {@link
 * BgSickLeaveResolver} го чете.
 */
public final class BgSickLeaveFacts {

  /**
   * МЕСЕЧНАТА БАЗА, от която се извежда среднодневното брутно. Единица: ЕВРО на месец.
   *
   * <p><b>⚠️ РЕШЕНИЕ № 1 — виж {@link BgSickLeaveResolver#DAILY_BASE_IS_CONTRACTED_SALARY}.</b> Тук
   * влиза ПОСТОЯННОТО МЕСЕЧНО ВЪЗНАГРАЖДЕНИЕ (основна + клас, степен и постоянни пера за целия
   * месец), не действително начисленото бруто — при дни за сметка на работодателя. {@code null} или
   * нула — непопълнена, тоест ОТКАЗ, не нула евро болничен.
   */
  public final BigDecimal monthlyBase;

  /**
   * УГОВОРЕНОТО месечно възнаграждение — за законовия ПОД по чл. 40 ал. 5. Единица: ЕВРО на месец.
   *
   * <p>Подът е „не по-малко от 70 на сто от среднодневното УГОВОРЕНО възнаграждение“. Днес съвпада
   * с {@link #monthlyBase} по решение № 1, и точно затова стои като ОТДЕЛНО поле: щом базата някой
   * ден стане действителното бруто, подът вече е на мястото си и не се дописва в бързината.
   */
  public final BigDecimal contractedMonthly;

  /**
   * Работни дни в периода — делителят. Единица: РАБОТНИ ДНИ. {@code null} или нула — неопределен.
   */
  public final Integer workingDaysInPeriod;

  /**
   * Процентът на обезщетението от речника на отсъствията. Единица: ПРОЦЕНТ (70 значи 70 на сто).
   *
   * <p>{@code null} значи „редът в {@code BgAbsenceType} не е намерен или процентът му е празен“ —
   * ОТКАЗ. Нула значи „вид без обезщетение“ и е изчислена нула.
   */
  public final BigDecimal compensationPct;

  /**
   * {@code HEALTH_ABSENCE_BASE} за периода. Единица: ЕВРО на месец.
   *
   * <p>Пълният минимален осигурителен доход за самоосигуряващите се лица — 550,66 до 31.07.2026 г.
   * и 620,20 от 01.08.2026 г. {@code null} значи „параметърът не е зареден за датата“, тоест ОТКАЗ.
   */
  public final BigDecimal healthAbsenceBase;

  /**
   * Ставката на здравната вноска ЗА СМЕТКА НА РАБОТОДАТЕЛЯ. Единица: ПРОЦЕНТ.
   *
   * <p>⚠️ Чете се от {@code bg_insurance_contribution} за вида осигурен и категорията труд — НЕ е
   * число в кода. Виж {@link BgSickLeaveResolver#HEALTH_ABSENCE_RATE_IS_EMPLOYER_SHARE}. {@code
   * null} значи, че редът не е намерен — ОТКАЗ, а не нула на сто.
   */
  public final BigDecimal healthEmployerRatePct;

  public BgSickLeaveFacts(
      BigDecimal monthlyBase,
      BigDecimal contractedMonthly,
      Integer workingDaysInPeriod,
      BigDecimal compensationPct,
      BigDecimal healthAbsenceBase,
      BigDecimal healthEmployerRatePct) {
    this.monthlyBase = monthlyBase;
    this.contractedMonthly = contractedMonthly;
    this.workingDaysInPeriod = workingDaysInPeriod;
    this.compensationPct = compensationPct;
    this.healthAbsenceBase = healthAbsenceBase;
    this.healthEmployerRatePct = healthEmployerRatePct;
  }
}

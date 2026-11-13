package com.axelor.apps.bglocalization.service.payroll;

import java.math.BigDecimal;

/**
 * ЕДИН ОТГОВОР НА ЕНДЖИНА ПО ЧЛ. 177 — сума ИЛИ обявена причина да няма сума.
 *
 * <p>Същият довод като при {@code BgEarningsOutcome}: „няма ползван отпуск по чл. 177“ и „има и не
 * можах да го сметна“ са РАЗЛИЧНИ факта. Свити до липсващо перо, вторият става неразличим от първия
 * — тоест недоплатен отпуск, който изглежда като нормален фиш.
 *
 * <p><b>⚠️ СУМАТА СЕ НОСИ РАЗГЛОБЕНА, И ТОВА НЕ Е ДИАГНОСТИКА.</b> {@link #carve} и {@link #uplift}
 * са двете половини на едно число и всяка отговаря на различен въпрос: карвът казва „колко пари
 * слязоха от останалите пера“, надбавката — „колко добавя чл. 177 над вече платеното“. Свидетелят
 * на инварианта („нето нула“) проверява именно че надбавката е нула, а не че сумата е някаква;
 * разглобени, двете правят разликата проверима, а не изведена от тотала.
 */
public final class BgArt177Outcome {

  public enum Status {
    /** Перото е записано. */
    COMPUTED,
    /** Няма повод — месецът не носи отпуск по чл. 177, или фишът е ретро. Нормално състояние. */
    NOT_APPLICABLE,
    /** Има повод и НЕ можа да се сметне. {@link #detail} казва какво да се поправи. */
    REFUSED
  }

  public final Status status;

  /** Закръглената сума на перото: {@link #carve} + {@link #uplift}. Само при COMPUTED. */
  public final BigDecimal amount;

  /** Дните платен отпуск по чл. 177 — количеството на реда. */
  public final BigDecimal days;

  /** Парите, свалени от перата с постоянен характер за същите дни. */
  public final BigDecimal carve;

  /**
   * Надбавката над вече платеното. НУЛА значи, че базовият месец е носил същото възнаграждение —
   * тогава фишът излиза бит по бит какъвто беше без чл. 177.
   */
  public final BigDecimal uplift;

  /** Пълното право по чл. 177 за тези дни — за сверяване и за бележката, не за сбора. */
  public final BigDecimal entitlement;

  /** Коя норма е дала среднодневното — влиза в бележката на перото. */
  public final String basis;

  /** ЦЯЛО ИЗРЕЧЕНИЕ за човек. Празно е само при COMPUTED. */
  public final String detail;

  private BgArt177Outcome(
      Status status,
      BigDecimal amount,
      BigDecimal days,
      BigDecimal carve,
      BigDecimal uplift,
      BigDecimal entitlement,
      String basis,
      String detail) {
    this.status = status;
    this.amount = amount;
    this.days = days;
    this.carve = carve;
    this.uplift = uplift;
    this.entitlement = entitlement;
    this.basis = basis;
    this.detail = detail;
  }

  public static BgArt177Outcome computed(
      BigDecimal amount,
      BigDecimal days,
      BigDecimal carve,
      BigDecimal uplift,
      BigDecimal entitlement,
      String basis) {
    return new BgArt177Outcome(
        Status.COMPUTED, amount, days, carve, uplift, entitlement, basis, null);
  }

  public static BgArt177Outcome notApplicable(String detail) {
    return new BgArt177Outcome(Status.NOT_APPLICABLE, null, null, null, null, null, null, detail);
  }

  public static BgArt177Outcome refused(String detail) {
    return new BgArt177Outcome(Status.REFUSED, null, null, null, null, null, null, detail);
  }

  public boolean isComputed() {
    return status == Status.COMPUTED;
  }

  public boolean isRefused() {
    return status == Status.REFUSED;
  }
}

package com.axelor.apps.bglocalization.service.art49;

import java.math.BigDecimal;

/**
 * РЕЗУЛТАТЪТ ОТ ГОДИШНОТО ПРЕИЗЧИСЛЯВАНЕ ПО ЧЛ. 49 — колоните на справката, а не „някакви числа“.
 *
 * <p>Всяко поле носи в името си колоната или реда, който попълва. Това е нарочно: човекът, който
 * сверява екрана срещу хартията, търси „колона 19“, а не „settlement“.
 *
 * <p><b>⚠️ ЕДИН ОБЕКТ ЗА ЕДНО ЛИЦЕ, А НЕ ЗА ЕДИН КОД.</b> Редовете 8.1, 8.2 и 8.3 и колоните 17–22
 * не съществуват на ниво код на дохода — те са сборни за лицето. Точно затова тук няма поле „код“.
 */
public final class BgArt49Result {

  private final BigDecimal baseArt25Para1;
  private final BigDecimal baseArt25Para3;
  private final BigDecimal baseArt49;
  private final BgArt49Reliefs reliefs;
  private final BigDecimal reliefTotal;
  private final BigDecimal taxableBase;
  private final BigDecimal annualTax;
  private final BigDecimal advanceTaxForSettlement;
  private final BigDecimal settlement;
  private final BigDecimal toWithhold;
  private final BigDecimal toRefund;
  private final BigDecimal totalWithheld;

  BgArt49Result(
      BigDecimal baseArt25Para1,
      BigDecimal baseArt25Para3,
      BigDecimal baseArt49,
      BgArt49Reliefs reliefs,
      BigDecimal reliefTotal,
      BigDecimal taxableBase,
      BigDecimal annualTax,
      BigDecimal advanceTaxForSettlement,
      BigDecimal settlement,
      BigDecimal toWithhold,
      BigDecimal toRefund,
      BigDecimal totalWithheld) {
    this.baseArt25Para1 = baseArt25Para1;
    this.baseArt25Para3 = baseArt25Para3;
    this.baseArt49 = baseArt49;
    this.reliefs = reliefs;
    this.reliefTotal = reliefTotal;
    this.taxableBase = taxableBase;
    this.annualTax = annualTax;
    this.advanceTaxForSettlement = advanceTaxForSettlement;
    this.settlement = settlement;
    this.toWithhold = toWithhold;
    this.toRefund = toRefund;
    this.totalWithheld = totalWithheld;
  }

  /** Ред 8.1, колона 6 — годишната данъчна основа по чл. 25 ал. 1 (кодове 101). */
  public BigDecimal getBaseArt25Para1() {
    return baseArt25Para1;
  }

  /** Ред 8.2, колона 6 — годишната данъчна основа по чл. 25 ал. 3 (код 103, морско лице). */
  public BigDecimal getBaseArt25Para3() {
    return baseArt25Para3;
  }

  /** Ред 8.3, колона 6 — годишната данъчна основа по чл. 49 = ред 8.1 + ред 8.2. */
  public BigDecimal getBaseArt49() {
    return baseArt49;
  }

  /**
   * ПРИЛОЖЕНИТЕ облекчения — колони от 8 до 16 на ред 8.3, вече минали през таваните.
   *
   * <p>⚠️ Това е ПОЛЗВАНОТО, а не поисканото: указание т. 7 иска в колоните размера, приложен при
   * определянето на годишния данък. Показано поисканото, колона 17 нямаше да се връзва със сбора
   * си.
   */
  public BgArt49Reliefs getReliefs() {
    return reliefs;
  }

  /** Сборът от колони 8 до 16 — умалителят на колона 17. */
  public BigDecimal getReliefTotal() {
    return reliefTotal;
  }

  /** Колона 17 — годишната данъчна основа по чл. 49 ал. 1 СЛЕД намаленията. */
  public BigDecimal getTaxableBase() {
    return taxableBase;
  }

  /** Колона 18 — дължимият годишен данък (колона 17 × ставката). */
  public BigDecimal getAnnualTax() {
    return annualTax;
  }

  /**
   * Сборът в колона 7 за доходите С КОДОВЕ 101 И 103 — умалителят на колона 19.
   *
   * <p>⚠️ Не е същото като колона 22: там влиза и данъкът по код 102. Двете суми съвпадат само за
   * лице без доход по код 102 — тоест за почти всяко лице, и точно затова разликата е тиха.
   */
  public BigDecimal getAdvanceTaxForSettlement() {
    return advanceTaxForSettlement;
  }

  /**
   * Колона 19 — разликата между колона 18 и авансово удържаното, ЗАКРЪГЛЕНА до цяла парична
   * единица.
   *
   * <p>Положителна значи довнасяне, отрицателна — възстановяване.
   */
  public BigDecimal getSettlement() {
    return settlement;
  }

  /** Колона 20 — сумата по колона 19, удържана от лицето до 31 януари (чл. 49 ал. 5). */
  public BigDecimal getToWithhold() {
    return toWithhold;
  }

  /** Колона 21 — сумата по колона 19, възстановена на лицето до 31 януари (чл. 49 ал. 6). */
  public BigDecimal getToRefund() {
    return toRefund;
  }

  /** Колона 22 — общият удържан данък: сборът в колона 7 + колона 20 − колона 21. */
  public BigDecimal getTotalWithheld() {
    return totalWithheld;
  }

  @Override
  public String toString() {
    return "к.6(8.1)="
        + baseArt25Para1
        + " к.6(8.2)="
        + baseArt25Para3
        + " к.6(8.3)="
        + baseArt49
        + " к.8–16="
        + reliefTotal
        + " к.17="
        + taxableBase
        + " к.18="
        + annualTax
        + " к.19="
        + settlement
        + " к.20="
        + toWithhold
        + " к.21="
        + toRefund
        + " к.22="
        + totalWithheld;
  }
}

package com.axelor.apps.bglocalization.service.zddfl;

import java.math.BigDecimal;

/**
 * Входът за едно изчисление по ЗДДФЛ — ПЛОСЪК, без нито един Axelor тип.
 *
 * <p><b>Защо не се подава самият запис.</b> Правилото се проверява с числа, а числата идват от две
 * места: ставките — от каталога {@code BgIncomeType} за ДАТАТА на плащането, сумите — от самия ред.
 * Подадени като запис, двете биха довлекли базата в теста и свидетелите щяха да искат жива схема,
 * за да кажат, че 1000 при 25 на сто дава 75. Тук те са прости стойности и се четат наум.
 *
 * <p>⚠️ <b>Ставките се ПОДАВАТ, не се знаят.</b> Нито 10, нито 25/40/60 стоят в кода: те са
 * темпорални и живеят на реда от каталога. Заковани тук, промяна в чл. 29 или в чл. 46 щеше да иска
 * билд, а вече подадена справка — да смени числата си със задна дата.
 */
public final class BgIncomeTaxRequest {

  /** Шаблонът от каталога — той, а не ставките, избира формулата. */
  public final String taxPattern;

  /** Нормативни разходи в проценти от брутния доход (шаблони A и B). */
  public final BigDecimal expenseRate;

  /** Ставка на окончателния данък по чл. 46 (шаблон C). */
  public final BigDecimal finalTaxRate;

  /** Ставка на авансовия данък по чл. 43, 44 и 44а (шаблони A, B и E). */
  public final BigDecimal advanceTaxRate;

  /** Стойността по подразбиране за КОДА — виж {@link #selfInsuredDeclared} за изключението. */
  public final boolean withheldByPayer;

  /** Колона 11 — брутният размер на дохода. */
  public final BigDecimal grossAmount;

  /**
   * Колона 12 — удържаните задължителни осигурителни вноски.
   *
   * <p>⚠️ Участва САМО при шаблон A. Виж {@link BgIncomeTaxResolver} — асиметрията между чл. 43 ал.
   * 1 и чл. 44 ал. 4 е в закона, не в кода.
   */
  public final BigDecimal insuranceAmount;

  /** Чл. 43 ал. 5 — получателят е СОЛ и е декларирал писмено, че ще внася данъка сам. */
  public final boolean selfInsuredDeclared;

  public BgIncomeTaxRequest(
      String taxPattern,
      BigDecimal expenseRate,
      BigDecimal finalTaxRate,
      BigDecimal advanceTaxRate,
      boolean withheldByPayer,
      BigDecimal grossAmount,
      BigDecimal insuranceAmount,
      boolean selfInsuredDeclared) {
    this.taxPattern = taxPattern;
    this.expenseRate = expenseRate;
    this.finalTaxRate = finalTaxRate;
    this.advanceTaxRate = advanceTaxRate;
    this.withheldByPayer = withheldByPayer;
    this.grossAmount = grossAmount;
    this.insuranceAmount = insuranceAmount;
    this.selfInsuredDeclared = selfInsuredDeclared;
  }
}

package com.axelor.apps.bglocalization.service.zddfl;

import java.math.BigDecimal;

/**
 * Изходът на едно изчисление: трите суми, видът данък — или ОТКАЗ с причина.
 *
 * <p>⚠️ <b>Отказът е стойност, а не изключение.</b> Той се показва на екрана до полетата, които е
 * отказал да напълни, и потребителят чете КОЯ норма липсва. Хвърлен като изключение, той щеше да
 * стигне до trace-а и формата щеше да остане с празни суми без обяснение.
 *
 * <p>⚠️ <b>И трите суми се връщат ВИНАГИ, включително нулевите.</b> Основата при шаблон D е нула,
 * защото платецът НЕ ЗНАЕ цената на придобиване — не защото сделката е на загуба. Нулата тук е
 * отговор „не се определя от платеца“, а не сметнат резултат; затова я придружава {@link #note}.
 */
public final class BgIncomeTaxResult {

  /** Нормативно признатите разходи в левове. */
  public final BigDecimal expenseAmount;

  /** Основата, върху която е сметнат данъкът. */
  public final BigDecimal taxableBase;

  /** Колона 13 — удържаният данък. */
  public final BigDecimal taxAmount;

  /** ADVANCE, FINAL или NONE — виж {@code bg.income.payment.tax.kind.select}. */
  public final String taxKind;

  /** Обяснение към нулата или към неудържания данък. {@code null}, когато няма какво да се каже. */
  public final String note;

  /** Причината изчислението да не се направи. {@code null} при успех. */
  public final String refusal;

  private BgIncomeTaxResult(
      BigDecimal expenseAmount,
      BigDecimal taxableBase,
      BigDecimal taxAmount,
      String taxKind,
      String note,
      String refusal) {
    this.expenseAmount = expenseAmount;
    this.taxableBase = taxableBase;
    this.taxAmount = taxAmount;
    this.taxKind = taxKind;
    this.note = note;
    this.refusal = refusal;
  }

  static BgIncomeTaxResult of(
      BigDecimal expenseAmount,
      BigDecimal taxableBase,
      BigDecimal taxAmount,
      String taxKind,
      String note) {
    return new BgIncomeTaxResult(expenseAmount, taxableBase, taxAmount, taxKind, note, null);
  }

  static BgIncomeTaxResult refuse(String reason) {
    return new BgIncomeTaxResult(null, null, null, null, null, reason);
  }

  /** Има ли изчислен резултат — тоест може ли формата да пише сумите. */
  public boolean isResolved() {
    return refusal == null;
  }
}

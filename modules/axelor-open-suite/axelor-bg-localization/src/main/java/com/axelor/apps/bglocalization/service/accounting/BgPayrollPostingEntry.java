package com.axelor.apps.bglocalization.service.accounting;

import java.math.BigDecimal;

/**
 * ЕДНО ПЕРО НА ФИША, ПРЕВЕДЕНО В ДВОЙКА СМЕТКИ — дебитна и кредитна, за една и съща сума.
 *
 * <p><b>⚠️ ДВОЙКА, А НЕ ЕДНА СТРАНА, И ТОВА Е ЦЯЛАТА ПРИЧИНА СТАТИЯТА ДА БАЛАНСИРА.</b> Ако всяко
 * перо даваше по един ред, равенството Dr = Cr щеше да е СЛЕДСТВИЕ от аритметиката на фиша: нетото
 * би трябвало да излезе като разлика и всяка стотинка разминаване щеше да се появи чак накрая, като
 * едно необяснимо число. При двойки балансът е ПО УСТРОЙСТВО — всяко перо внася еднакво от двете
 * страни, тоест сборът им също. Оттам следва, че тест за баланс не може да мине „случайно“, а
 * счетоводителят вижда СРЕЩУ КОЕ перо стои всеки лев.
 *
 * <p>⚠️ Сумата е ВИНАГИ положителна, както на самото перо ({@code BgPaySlipLine.bgAmount}).
 * Посоката я носят двете сметки, а не знакът — същият принцип, по който знакът е на перото, не на
 * сумата.
 */
public final class BgPayrollPostingEntry {

  private final int lineNumber;
  private final String label;
  private final String debitAccount;
  private final String creditAccount;
  private final BigDecimal amount;
  private final String analytic;

  public BgPayrollPostingEntry(
      int lineNumber, String label, String debitAccount, String creditAccount, BigDecimal amount) {
    this(lineNumber, label, debitAccount, creditAccount, amount, null);
  }

  public BgPayrollPostingEntry(
      int lineNumber,
      String label,
      String debitAccount,
      String creditAccount,
      BigDecimal amount,
      String analytic) {
    this.lineNumber = lineNumber;
    this.label = label;
    this.debitAccount = debitAccount;
    this.creditAccount = creditAccount;
    this.amount = amount;
    this.analytic = analytic;
  }

  /** Номерът на перото, от което идва двойката — замразеното копие, не речникът. */
  public int getLineNumber() {
    return lineNumber;
  }

  /** Името на перото, за описанието на счетоводния ред. */
  public String getLabel() {
    return label;
  }

  public String getDebitAccount() {
    return debitAccount;
  }

  public String getCreditAccount() {
    return creditAccount;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  /**
   * Аналитичността, с която двойката влиза в статията — днес само видът удръжка по 8xx.
   *
   * <p>⚠️ Стои на ДВОЙКАТА, а не се извежда наново при сбирането: тя е свойство на ПЕРОТО (запор,
   * заем, издръжка), а перото се вижда само тук. Изведена при сбирането, тя щеше да иска номерът на
   * перото да се носи до края — тоест същият прочит на две места.
   */
  public String getAnalytic() {
    return analytic;
  }

  @Override
  public String toString() {
    return lineNumber
        + " · Dr "
        + debitAccount
        + " / Cr "
        + creditAccount
        + (analytic == null ? "" : " · " + analytic)
        + " · "
        + amount;
  }
}

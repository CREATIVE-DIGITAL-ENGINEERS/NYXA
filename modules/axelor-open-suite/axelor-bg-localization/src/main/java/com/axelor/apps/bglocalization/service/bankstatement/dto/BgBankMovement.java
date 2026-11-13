package com.axelor.apps.bglocalization.service.bankstatement.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One parsed statement movement (a single spreadsheet data row). Pure data carrier — no accounting
 * logic. {@code debit == true} means outgoing (Разход), {@code false} means incoming (Приход). The
 * amount is always the positive magnitude in the statement-account currency; direction lives in the
 * {@code debit} flag.
 */
public class BgBankMovement {

  /** 1-based spreadsheet row number (for human-readable error reporting). */
  public int excelRow;

  public String operationType; // Вид операция
  public String type; // Тип (Разход / Приход)
  public String counterpartyName; // Наредител/Получател
  public String counterpartyAccount; // От/към сметка
  public String channel; // Канал
  public String currencyOfOperation; // Валута на операцията
  public BigDecimal amount; // magnitude in the account currency (Сума в EUR for an EUR account)
  public String description; // Основание
  public LocalDate operationDate; // Дата на обработка
  public String reference; // Бордеро №
  public LocalDate valueDate; // Вальор
  public BigDecimal runningBalance; // Салдо след операцията
  public boolean debit; // true = Разход (outgoing), false = Приход (incoming)

  /** Signed amount: positive for an incoming (credit) movement, negative for outgoing (debit). */
  public BigDecimal signedAmount() {
    if (amount == null) {
      return BigDecimal.ZERO;
    }
    return debit ? amount.negate() : amount;
  }
}

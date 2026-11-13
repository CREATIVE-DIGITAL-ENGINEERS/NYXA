package com.axelor.apps.bglocalization.service.saft.dto;

import java.math.BigDecimal;

/**
 * AmountStructure — сума във валутата по подразбиране плюс валутата на документа.
 *
 * <p>⚠️ И ТРИТЕ ПЪРВИ СА ЗАДЪЛЖИТЕЛНИ, ДОРИ ЗА СУМА В ЕВРО: схемата иска {@code CurrencyCode} и
 * {@code CurrencyAmount} на всяка сума. За собствени данни след 01.01.2026 г. това е EUR и същата
 * сума — виж {@link #eur(BigDecimal)}.
 */
public final class SaftAmount {

  /** Сумата в EUR (валутата по подразбиране на файла). */
  private BigDecimal amount;

  /** ISO 4217 код на валутата на документа. */
  private String currencyCode;

  /** Сумата във валутата на документа. */
  private BigDecimal currencyAmount;

  /** Незадължителен курс: CurrencyAmount × ExchangeRate = Amount. */
  private BigDecimal exchangeRate;

  /** Сума в евро — валутата на документа и валутата по подразбиране съвпадат. */
  public static SaftAmount eur(BigDecimal amount) {
    return new SaftAmount().amount(amount).currencyCode("EUR").currencyAmount(amount);
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public SaftAmount amount(BigDecimal value) {
    this.amount = value;
    return this;
  }

  public String getCurrencyCode() {
    return currencyCode;
  }

  public SaftAmount currencyCode(String value) {
    this.currencyCode = value;
    return this;
  }

  public BigDecimal getCurrencyAmount() {
    return currencyAmount;
  }

  public SaftAmount currencyAmount(BigDecimal value) {
    this.currencyAmount = value;
    return this;
  }

  public BigDecimal getExchangeRate() {
    return exchangeRate;
  }

  public SaftAmount exchangeRate(BigDecimal value) {
    this.exchangeRate = value;
    return this;
  }
}

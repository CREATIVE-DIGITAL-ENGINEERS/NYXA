package com.axelor.apps.bglocalization.service.saft.dto;

import java.math.BigDecimal;

/** Сметка от GeneralLedgerAccounts. Салдата са СО ЗНАК (дебит положителен). */
public final class SaftGlAccount {

  /** Кодът по сметкоплана на НАП. */
  private String accountId;

  private String accountDescription;

  /** Сметката в системата на лицето. */
  private String taxpayerAccountId;

  /** Active / Passive / Bifunctional. */
  private String accountType;

  private BigDecimal openingBalance;

  private BigDecimal closingBalance;

  public String getAccountId() {
    return accountId;
  }

  public SaftGlAccount accountId(String value) {
    this.accountId = value;
    return this;
  }

  public String getAccountDescription() {
    return accountDescription;
  }

  public SaftGlAccount accountDescription(String value) {
    this.accountDescription = value;
    return this;
  }

  public String getTaxpayerAccountId() {
    return taxpayerAccountId;
  }

  public SaftGlAccount taxpayerAccountId(String value) {
    this.taxpayerAccountId = value;
    return this;
  }

  public String getAccountType() {
    return accountType;
  }

  public SaftGlAccount accountType(String value) {
    this.accountType = value;
    return this;
  }

  public BigDecimal getOpeningBalance() {
    return openingBalance;
  }

  public SaftGlAccount openingBalance(BigDecimal value) {
    this.openingBalance = value;
    return this;
  }

  public BigDecimal getClosingBalance() {
    return closingBalance;
  }

  public SaftGlAccount closingBalance(BigDecimal value) {
    this.closingBalance = value;
    return this;
  }
}

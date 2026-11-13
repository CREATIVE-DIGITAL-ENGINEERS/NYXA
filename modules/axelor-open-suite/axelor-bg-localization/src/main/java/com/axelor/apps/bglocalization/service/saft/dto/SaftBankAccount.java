package com.axelor.apps.bglocalization.service.saft.dto;

/** BankAccountStructure — само клонът с IBAN (S.BA.1). */
public final class SaftBankAccount {

  private String ibanNumber;

  public String getIbanNumber() {
    return ibanNumber;
  }

  public SaftBankAccount ibanNumber(String value) {
    this.ibanNumber = value;
    return this;
  }
}

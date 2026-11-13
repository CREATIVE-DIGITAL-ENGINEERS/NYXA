package com.axelor.apps.aiocr.service.bank;

import com.axelor.apps.base.db.Bank;
import com.axelor.apps.base.service.BankServiceImpl;

/**
 * A bank's name without the word "null" in it.
 *
 * <p>Stock {@code BankServiceImpl.computeFullName} writes {@code code + " - " + bankName}, so a
 * bank created from a bare BIC — the "+ Create" line of the bank-details form, with the name left
 * empty — is stored as {@code BNBGBGSD - null}, and every bank account at that bank then reads
 * {@code BG… - BNBGBGSD - null} on the supplier invoice. Here the empty part is simply left out.
 */
public class OcrBankServiceImpl extends BankServiceImpl {

  @Override
  public void computeFullName(Bank bank) {
    bank.setFullName(OcrBankNames.join(bank.getCode(), bank.getBankName()));
  }
}

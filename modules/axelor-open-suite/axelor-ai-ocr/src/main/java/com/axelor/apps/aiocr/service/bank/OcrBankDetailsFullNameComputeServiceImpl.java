package com.axelor.apps.aiocr.service.bank;

import com.axelor.apps.base.service.BankDetailsFullNameComputeServiceImpl;

/**
 * The bank-account label without a {@code null} inherited from its bank.
 *
 * <p>{@link OcrBankServiceImpl} stops new banks from being named {@code BNBGBGSD - null}, but a
 * bank saved before it keeps that name until someone saves it again, and the bank-account label is
 * built from it. The label is computed whenever it is read, so cleaning the bank's part here fixes
 * every account at once, with no data change.
 */
public class OcrBankDetailsFullNameComputeServiceImpl
    extends BankDetailsFullNameComputeServiceImpl {

  @Override
  public String computeBankDetailsFullName(
      String code, String label, String iban, String bankFullName) {
    return super.computeBankDetailsFullName(code, label, iban, OcrBankNames.clean(bankFullName));
  }
}

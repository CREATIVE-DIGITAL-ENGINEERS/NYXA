package com.axelor.apps.aiocr.service.bank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.base.db.Bank;
import org.junit.jupiter.api.Test;

/**
 * A bank created from a bare BIC has no name; stock Axelor then names it {@code BNBGBGSD - null}
 * and every bank account at that bank reads {@code BG… - BNBGBGSD - null} on the supplier invoice.
 */
class OcrBankNamesTest {

  @Test
  void aBankWithoutANameIsCalledByItsBicAlone() {
    Bank bank = new Bank();
    bank.setCode("BNBGBGSD");
    new OcrBankServiceImpl().computeFullName(bank);
    assertEquals("BNBGBGSD", bank.getFullName());

    bank.setBankName("БНБ");
    new OcrBankServiceImpl().computeFullName(bank);
    assertEquals("BNBGBGSD - БНБ", bank.getFullName());
  }

  @Test
  void anAlreadyStoredNullIsLeftOutOfTheAccountLabel() {
    assertEquals(
        "BG80BNBG96611020345678 - BNBGBGSD",
        new OcrBankDetailsFullNameComputeServiceImpl()
            .computeBankDetailsFullName(null, null, "BG80BNBG96611020345678", "BNBGBGSD - null"));
  }

  @Test
  void cleanLeavesAProperNameAlone() {
    assertEquals("BNBGBGSD - БНБ", OcrBankNames.clean("BNBGBGSD - БНБ"));
    assertEquals("BNBGBGSD", OcrBankNames.clean("BNBGBGSD - null"));
    assertEquals("БНБ", OcrBankNames.clean("null - БНБ"));
    assertNull(OcrBankNames.clean("null - null"));
    assertNull(OcrBankNames.clean(null));
  }

  @Test
  void joinSkipsBlankParts() {
    assertEquals("A - B", OcrBankNames.join(" A ", "B"));
    assertEquals("B", OcrBankNames.join("  ", "B"));
    assertNull(OcrBankNames.join(null, null));
  }
}

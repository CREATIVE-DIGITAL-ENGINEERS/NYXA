package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ocr.BgOcrBankDetails.Decision;
import org.junit.jupiter.api.Test;

/** Unit tests for the pure OCR bank-details decision logic. */
class BgOcrBankDetailsTest {

  private static final String IBAN = "BG14UNCR70001522083175";

  @Test
  void normalizeStripsSpacesAndUppercases() {
    assertEquals(IBAN, BgOcrBankDetails.normalizeIban("bg14 uncr 7000 1522 0831 75"));
    assertNull(BgOcrBankDetails.normalizeIban("   "));
    assertNull(BgOcrBankDetails.normalizeIban(null));
  }

  @Test
  void ibanValidity() {
    assertTrue(BgOcrBankDetails.isValidIban(IBAN));
    assertFalse(BgOcrBankDetails.isValidIban("12345"));
    assertFalse(BgOcrBankDetails.isValidIban("BGXX"));
    assertFalse(BgOcrBankDetails.isValidIban(null));
  }

  @Test
  void extractIbanStripsCurrencySuffixAndSpaces() {
    // The STSA account came back from Azure with a trailing " (EUR)" — the exact reason a valid
    // IBAN was previously dropped by a strict full-string match.
    assertEquals(
        "BG42STSA93000030253828", BgOcrBankDetails.extractIban("BG42STSA93000030253828 (EUR)"));
    // Spaces between IBAN groups are tolerated.
    assertEquals(
        "BG18UBBS88881000754699", BgOcrBankDetails.extractIban("BG18 UBBS 8888 1000 7546 99"));
    // A leading label does not prevent extraction.
    assertEquals(IBAN, BgOcrBankDetails.extractIban("IBAN: " + IBAN));
    // Not an IBAN → null (never a loose string).
    assertNull(BgOcrBankDetails.extractIban("BG100000026")); // a VAT/EIK is too short
    assertNull(BgOcrBankDetails.extractIban("STSABGSF")); // a SWIFT is not an IBAN
    assertNull(BgOcrBankDetails.extractIban(null));
  }

  @Test
  void findIbanInTextRecoversFromMarkersAndRejectsNoise() {
    String text =
        "Плащане: Банка\nIBAN: BG42STSA93000030253828 (EUR)\nБанков код : STSABGSF\n"
            + "ДДС No: BG100000026\n";
    assertEquals("BG42STSA93000030253828", BgOcrBankDetails.findIbanInText(text));
    // A Bulgarian "Банкова сметка" label with spaces inside the number.
    assertEquals(
        "BG18UBBS88881000754699",
        BgOcrBankDetails.findIbanInText("Банкова сметка BG18 UBBS 8888 1000 7546 99 код"));
    // No IBAN anywhere → null; a VAT number alone must not be mistaken for an IBAN.
    assertNull(BgOcrBankDetails.findIbanInText("ДДС No: BG100000026 ИН по ДДС BG831108786"));
    assertNull(BgOcrBankDetails.findIbanInText(null));
  }

  @Test
  void existingExactSupplierIbanIsSelected() {
    assertEquals(Decision.SELECT, BgOcrBankDetails.decide(true, IBAN, 1, false, true));
  }

  @Test
  void newSupplierIbanWithResolvableBankIsCreated() {
    assertEquals(Decision.CREATE, BgOcrBankDetails.decide(true, IBAN, 0, false, true));
  }

  @Test
  void newSupplierIbanWithoutBankWarns() {
    assertEquals(Decision.WARN_NO_BANK, BgOcrBankDetails.decide(true, IBAN, 0, false, false));
  }

  @Test
  void multipleSupplierMatchesWarnAndSelectNone() {
    assertEquals(Decision.WARN_MULTIPLE, BgOcrBankDetails.decide(true, IBAN, 2, false, true));
  }

  @Test
  void ibanOwnedByAnotherEntityWarnsNotReused() {
    assertEquals(Decision.WARN_OTHER_OWNER, BgOcrBankDetails.decide(true, IBAN, 0, true, true));
  }

  @Test
  void missingSupplierSkips() {
    assertEquals(Decision.SKIP, BgOcrBankDetails.decide(false, IBAN, 0, false, true));
  }

  @Test
  void invalidIbanSkips() {
    assertEquals(Decision.SKIP, BgOcrBankDetails.decide(true, "12345", 0, false, true));
    assertEquals(Decision.SKIP, BgOcrBankDetails.decide(true, null, 0, false, true));
  }

  @Test
  void findAllBgIbansReturnsAllInOrderDistinct() {
    // Fixture P1 (manyinvoices_plusreciepts): a normal invoice listing two supplier bank accounts.
    String p1 =
        "Банкови сметки:\nОББ АД\nIBAN BG75UBBS81551066001817\nBIC код UBBSBGSF\n"
            + "УНИКРЕДИТ БУЛБАНК АД\nIBAN BG88UNCR96601025696011\nBIC код UNCRBGSF";
    java.util.List<String> all = BgOcrBankDetails.findAllBgIbans(p1);
    assertEquals(2, all.size());
    assertEquals("BG75UBBS81551066001817", all.get(0));
    assertEquals("BG88UNCR96601025696011", all.get(1)); // the LAST one is the fallback choice
  }

  @Test
  void findAllBgIbansIgnoresVatAndDeduplicates() {
    // A VAT/EIK (BG100000026) is too short to be an IBAN; a repeated IBAN appears once.
    String text =
        "ЗДДС № BG100000026\nIBAN BG75UBBS81551066001817\n... BG75UBBS81551066001817 (EUR)";
    java.util.List<String> all = BgOcrBankDetails.findAllBgIbans(text);
    assertEquals(1, all.size());
    assertEquals("BG75UBBS81551066001817", all.get(0));
  }

  @Test
  void findAllBgIbansEmptyWhenNoIban() {
    // Fixture P3: a normal cash invoice with an empty "IBAN:" line — nothing to fall back to.
    assertTrue(BgOcrBankDetails.findAllBgIbans("Плащане:\nВ брой\nIBAN:\n").isEmpty());
    assertTrue(BgOcrBankDetails.findAllBgIbans(null).isEmpty());
  }
}

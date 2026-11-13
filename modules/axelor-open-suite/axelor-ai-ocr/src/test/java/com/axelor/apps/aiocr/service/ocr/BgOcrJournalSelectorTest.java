package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ocr.BgOcrJournalSelector.Decision;
import org.junit.jupiter.api.Test;

/** Unit tests for the exact PCH purchase-journal selection rule. */
class BgOcrJournalSelectorTest {

  private static final Long COMPANY = 1L;
  private static final Long OTHER_COMPANY = 2L;

  @Test
  void exactPchForSameCompanyMatches() {
    assertTrue(BgOcrJournalSelector.isPurchaseJournal("PCH", COMPANY, 1, COMPANY));
    assertTrue(BgOcrJournalSelector.isPurchaseJournal(" pch ", COMPANY, 1, COMPANY)); // trim/case
  }

  @Test
  void pchFromAnotherCompanyIgnored() {
    assertFalse(BgOcrJournalSelector.isPurchaseJournal("PCH", OTHER_COMPANY, 1, COMPANY));
    assertFalse(BgOcrJournalSelector.isPurchaseJournal("PCH", null, 1, COMPANY));
  }

  @Test
  void wrongJournalTypeIgnored() {
    assertFalse(BgOcrJournalSelector.isPurchaseJournal("PCH", COMPANY, 2, COMPANY)); // SALE
    assertFalse(BgOcrJournalSelector.isPurchaseJournal("PCH", COMPANY, null, COMPANY));
  }

  @Test
  void nonPchCodeIgnored() {
    assertFalse(BgOcrJournalSelector.isPurchaseJournal("SAL", COMPANY, 1, COMPANY));
    assertFalse(BgOcrJournalSelector.isPurchaseJournal(null, COMPANY, 1, COMPANY));
  }

  @Test
  void decideSelectsOnlyForSingleMatch() {
    assertEquals(Decision.SELECT, BgOcrJournalSelector.decide(1));
  }

  @Test
  void decideWarnsForMultipleAndNeverArbitrarilySelects() {
    assertEquals(Decision.WARN_MULTIPLE, BgOcrJournalSelector.decide(2));
    assertEquals(Decision.WARN_MULTIPLE, BgOcrJournalSelector.decide(5));
  }

  @Test
  void decideNoneWhenNoMatch() {
    assertEquals(Decision.NONE, BgOcrJournalSelector.decide(0));
  }
}

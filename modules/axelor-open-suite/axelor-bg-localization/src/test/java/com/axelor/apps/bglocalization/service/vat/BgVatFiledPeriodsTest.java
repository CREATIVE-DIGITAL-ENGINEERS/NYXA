package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** т. 5 (Р8): подадена = submitted, accepted; останалите — не. Заявката — в IT. */
class BgVatFiledPeriodsTest {

  @Test
  @DisplayName("submitted и accepted са подаване")
  void подадени() {
    assertTrue(BgVatFiledPeriods.isFiledStatus("submitted"));
    assertTrue(BgVatFiledPeriods.isFiledStatus("accepted"));
  }

  @Test
  @DisplayName("corrected, draft, generated, rejected — не са подаване")
  void неподадени() {
    for (String status : new String[] {"corrected", "draft", "generated", "rejected", null}) {
      assertFalse(BgVatFiledPeriods.isFiledStatus(status), String.valueOf(status));
    }
  }
}

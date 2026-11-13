package com.axelor.apps.aiocr.service.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.supplier.SupplierEik;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Bounds and emptiness rules — the guards that keep a search from becoming a table scan. */
class OcrRecordQueryTest {

  @Test
  void limitIsClampedIntoRange() {
    assertEquals(OcrRecordQuery.DEFAULT_LIMIT, new OcrRecordQuery().getLimit());
    assertEquals(OcrRecordQuery.MAX_LIMIT, new OcrRecordQuery().setLimit(10_000).getLimit());
    assertEquals(1, new OcrRecordQuery().setLimit(0).getLimit());
    assertEquals(1, new OcrRecordQuery().setLimit(-5).getLimit());
    assertEquals(5, new OcrRecordQuery().setLimit(5).getLimit());
  }

  @Test
  void offsetIsNeverNegative() {
    assertEquals(0, new OcrRecordQuery().setOffset(-1).getOffset());
    assertEquals(20, new OcrRecordQuery().setOffset(20).getOffset());
  }

  @Test
  void emptyQueryIsDetected() {
    assertTrue(new OcrRecordQuery().isEmpty());
    assertTrue(new OcrRecordQuery().setFileName("   ").isEmpty(), "blank is not a criterion");
    assertFalse(new OcrRecordQuery().setUploadId(1L).isEmpty());
    assertFalse(new OcrRecordQuery().setFileName("inv").isEmpty());
    assertFalse(new OcrRecordQuery().setCompanyId(1L).isEmpty());
  }

  /**
   * The searched value AND the stored values are normalised by {@link SupplierEik}: the old
   * condition stripped only spaces on the stored side, so a supplier whose VAT number was stored
   * BG-prefixed never matched.
   */
  @Test
  void eikIsMatchedByTheSharedSupplierEikRuleOnBothSides() {
    for (String raw : new String[] {"BG831919536", "bg 831 919 536", "831919536"}) {
      Map<String, Object> params = new HashMap<>();
      String condition = OcrRecordSearchService.eikCriterion(raw, params);
      assertEquals("831919536", params.get("eik"), raw);
      assertEquals(SupplierEik.identityCondition("self.invoice.partner", "eik"), condition);
    }
    String condition = OcrRecordSearchService.eikCriterion("BG831919536", new HashMap<>());
    assertTrue(condition.contains("self.invoice.partner.taxNbr, '')), ' ', ''), 'BG', '')"));
    assertTrue(
        condition.contains("self.invoice.partner.registrationCode, '')), ' ', ''), 'BG', '')"));
  }

  @Test
  void anImplausibleEikIsABadArgumentNotAnEmptyResult() {
    assertThrows(
        IllegalArgumentException.class,
        () -> OcrRecordSearchService.eikCriterion("12AB", new HashMap<>()));
  }
}

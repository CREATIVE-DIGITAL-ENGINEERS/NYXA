package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.stock.db.Inventory;
import com.axelor.apps.stock.db.StockCorrectionReason;
import com.axelor.apps.stock.db.StockLocation;
import com.axelor.apps.stock.db.StockMove;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ДДС сигналите на складово движение — по един свидетел на предупреждение. */
class BgStockVatSignalsTest {

  /** Текстът от заданието за 120/160/170, дума по дума (ЗДДС до ДВ бр. 115/2025). */
  private static final String ART_79 =
      "Възможна корекция на данъчния кредит по чл. 79, ал. 1 ЗДДС с протокол по ал. 4, освен в"
          + " случаите на чл. 80, ал. 2, т. 1–4 (непреодолима сила, аварии, естествени фири в норма,"
          + " технологичен брак в норма, изтекъл срок в обичайни размери, обективно неизползваеми"
          + " стоки и др.).";

  private static StockMove withReason(String code, String signal) {
    StockMove move = new StockMove();
    StockCorrectionReason reason = new StockCorrectionReason();
    reason.setBgStockMovementCode(code);
    reason.setBgVatSignalSelect(signal);
    move.setStockCorrectionReason(reason);
    return move;
  }

  private static StockMove withCode(String code) {
    StockMove move = new StockMove();
    move.setBgSaftMovementCode(code);
    return move;
  }

  @Test
  @DisplayName("WARNING 120/160/170 — чл. 79 ал. 1, текстът от заданието дума по дума")
  void чл79() {
    for (String code : List.of("120", "160", "170")) {
      assertEquals(ART_79, BgStockVatSignals.warningOf(withCode(code)), code);
    }
    assertEquals(ART_79, BgStockVatSignals.warningOf(withReason("160", "ART_79")), "Брак");
  }

  @Test
  @DisplayName("WARNING 150 — чл. 6 ал. 3 т. 2 (безвъзмездно прехвърляне)")
  void чл6_ал3_т2() {
    String warning = BgStockVatSignals.warningOf(withReason("150", "ART_6_3_2"));
    assertTrue(warning.contains("чл. 6, ал. 3, т. 2"), warning);
    assertEquals(warning, BgStockVatSignals.warningOf(withCode("150")), "и по кода");
  }

  @Test
  @DisplayName("WARNING мостри — чл. 6 ал. 3 т. 2 с изключението по ал. 4 т. 2")
  void мостри() {
    String warning = BgStockVatSignals.warningOf(withReason("150", "ART_6_3_2_SAMPLES"));
    assertTrue(warning.contains("чл. 6, ал. 3, т. 2"), warning);
    assertTrue(warning.contains("чл. 6, ал. 4, т. 2"), warning);
  }

  @Test
  @DisplayName("WARNING лично ползване — чл. 6 ал. 3 т. 1, макар кодът да е 180")
  void лично_ползване() {
    String warning = BgStockVatSignals.warningOf(withReason("180", "ART_6_3_1"));
    assertTrue(warning.contains("чл. 6, ал. 3, т. 1"), warning);
  }

  @Test
  @DisplayName("без сигнал: работно облекло (NONE), други (180), отстъпки в натура")
  void без_сигнал() {
    assertNull(BgStockVatSignals.warningOf(withReason("180", "NONE")), "облекло, ЛПС");
    assertNull(BgStockVatSignals.warningOf(withReason("180", "")), "Други");
    assertNull(BgStockVatSignals.warningOf(withReason("60", "")));
    assertNull(BgStockVatSignals.warningOf(withReason("65", "")));
    assertNull(BgStockVatSignals.warningOf(new StockMove()));
  }

  private static StockMove inventoryMove(int fromType, int toType) {
    StockMove move = new StockMove();
    move.setInventory(new Inventory());
    StockLocation from = new StockLocation();
    from.setTypeSelect(fromType);
    StockLocation to = new StockLocation();
    to.setTypeSelect(toType);
    move.setFromStockLocation(from);
    move.setToStockLocation(to);
    return move;
  }

  @Test
  @DisplayName("WARNING липса от инвентаризация (120) — по посоката; излишъкът е без сигнал")
  void инвентаризация() {
    assertEquals(ART_79, BgStockVatSignals.warningOf(inventoryMove(1, 3)), "липса");
    assertNull(BgStockVatSignals.warningOf(inventoryMove(3, 1)), "излишък");
  }

  @Test
  @DisplayName("ръчният код бие причината")
  void ръчният_код_бие_причината() {
    StockMove move = withReason("180", "ART_6_3_1");
    move.setBgSaftMovementCode("160");
    assertEquals(ART_79, BgStockVatSignals.warningOf(move));
  }

  @Test
  @DisplayName("списъкът за протокол е същото правило като предупреждението")
  void списъкът() {
    StockMove scrap = withCode("160");
    scrap.setId(1L);
    StockMove other = withReason("180", "");
    other.setId(2L);
    StockMove gift = withReason("150", "ART_6_3_2");
    gift.setId(3L);
    assertEquals(
        List.of(1L, 3L), BgStockVatSignalList.withSignal(Arrays.asList(scrap, other, gift)));
  }
}

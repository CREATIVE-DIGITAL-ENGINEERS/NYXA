package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.BgNraNomenclatures;
import com.axelor.apps.bglocalization.service.stock.BgStockMovementCodes.Direction;
import com.axelor.apps.stock.db.StockLocation;
import com.axelor.apps.stock.db.StockMove;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Посоката на кодовете по „Stock movement“ — правилото, по което се отказва реализирането. */
class BgStockMovementCodesTest {

  @Test
  @DisplayName("посоката е дадена за всичките 20 кода на номенклатурата и само за тях, в реда ѝ")
  void покрива_номенклатурата() {
    assertEquals(
        new ArrayList<>(BgNraNomenclatures.movementTypes().keySet()),
        new ArrayList<>(BgStockMovementCodes.codes()));
  }

  @Test
  @DisplayName("60 е само вход; 65, 150, 160, 170 — само изход; 80 и 180 — всяка посока")
  void посоките_от_заданието() {
    assertEquals(EnumSet.of(Direction.IN), BgStockMovementCodes.allowed("60"));
    for (String out : List.of("65", "150", "160", "170")) {
      assertEquals(EnumSet.of(Direction.OUT), BgStockMovementCodes.allowed(out), out);
    }
    for (String any : List.of("80", "180")) {
      assertEquals(EnumSet.allOf(Direction.class), BgStockMovementCodes.allowed(any), any);
    }
  }

  @Test
  @DisplayName("кодовете само по стойност не стоят на движение с количество")
  void само_по_стойност() {
    for (String code : List.of("90", "100", "101", "130", "140")) {
      assertTrue(BgStockMovementCodes.allowed(code).isEmpty(), code);
      String refusal = BgStockMovementCodes.refusal(code, Direction.IN);
      assertNotNull(refusal, code);
      assertTrue(refusal.contains("само по стойност"), refusal);
    }
  }

  @Test
  @DisplayName("отказът казва кода, допустимата и действителната посока")
  void отказ_при_грешна_посока() {
    String refusal = BgStockMovementCodes.refusal("60", Direction.OUT);
    assertNotNull(refusal);
    assertTrue(refusal.contains("„60“") && refusal.contains("вход") && refusal.contains("изход"));
    assertNull(BgStockMovementCodes.refusal("60", Direction.IN));
    assertNull(BgStockMovementCodes.refusal("160", Direction.OUT));
    assertNotNull(BgStockMovementCodes.refusal("160", Direction.IN));
    assertNotNull(BgStockMovementCodes.refusal("999", Direction.IN), "непознат код");
    assertNull(BgStockMovementCodes.refusal("", Direction.IN), "празен код — автоматично");
    assertNull(BgStockMovementCodes.refusal("160", null), "посока не се определя");
  }

  private static StockLocation location(int type) {
    StockLocation l = new StockLocation();
    l.setTypeSelect(type);
    return l;
  }

  @Test
  @DisplayName("посоката на движението е по виртуалността на двете локации на хедъра")
  void посока_на_движението() {
    StockLocation internal = location(1);
    StockLocation external = location(2);
    StockLocation virtual = location(3);
    StockMove in = new StockMove();
    in.setFromStockLocation(virtual);
    in.setToStockLocation(internal);
    assertEquals(Direction.IN, BgStockMovementCodes.directionOf(in));

    StockMove out = new StockMove();
    out.setFromStockLocation(internal);
    out.setToStockLocation(virtual);
    assertEquals(Direction.OUT, BgStockMovementCodes.directionOf(out));

    StockMove transfer = new StockMove();
    transfer.setFromStockLocation(internal);
    transfer.setToStockLocation(external);
    assertEquals(Direction.TRANSFER, BgStockMovementCodes.directionOf(transfer));

    StockMove none = new StockMove();
    none.setFromStockLocation(virtual);
    none.setToStockLocation(location(3));
    assertNull(BgStockMovementCodes.directionOf(none));
    assertNull(BgStockMovementCodes.directionOf(new StockMove()));
  }
}

package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.bglocalization.service.stock.BgStockMovementClassifier.Role;
import com.axelor.apps.bglocalization.service.stock.BgStockMovementCodes.Direction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Кодът по „Stock movement“: ръчен > причина > автоматичен по посоката и ролята. */
class BgStockMovementClassifierTest {

  private static String auto(Direction d, Role r) {
    return BgStockMovementClassifier.classify(null, null, d, r);
  }

  @Test
  @DisplayName("ръчният код бие причината, причината бие автоматичния")
  void предимството() {
    assertEquals(
        "150", BgStockMovementClassifier.classify(" 150 ", "160", Direction.OUT, Role.CUSTOMER));
    assertEquals(
        "160", BgStockMovementClassifier.classify("", "160", Direction.OUT, Role.CUSTOMER));
    assertEquals("30", BgStockMovementClassifier.classify(null, " ", Direction.OUT, Role.CUSTOMER));
  }

  @Test
  @DisplayName("вход: доставчик 10, клиент 40, инвентаризация 110, производство 20")
  void входът() {
    assertEquals("10", auto(Direction.IN, Role.SUPPLIER));
    assertEquals("40", auto(Direction.IN, Role.CUSTOMER));
    assertEquals("110", auto(Direction.IN, Role.INVENTORY));
    assertEquals("20", auto(Direction.IN, Role.PRODUCTION));
    assertNull(auto(Direction.IN, Role.OTHER), "неизвестна виртуална — без догадка");
  }

  @Test
  @DisplayName("изход: клиент 30, доставчик 50, инвентаризация 120, производство 70, брак 160")
  void изходът() {
    assertEquals("30", auto(Direction.OUT, Role.CUSTOMER));
    assertEquals("50", auto(Direction.OUT, Role.SUPPLIER));
    assertEquals("120", auto(Direction.OUT, Role.INVENTORY));
    assertEquals("70", auto(Direction.OUT, Role.PRODUCTION));
    assertEquals("160", auto(Direction.OUT, Role.WASTE));
    assertEquals("80", auto(Direction.OUT, Role.OUTSOURCING), "S1: подизпълнителят е трансфер");
    assertNull(auto(Direction.OUT, Role.OTHER));
    assertNull(auto(Direction.OUT, null));
  }

  @Test
  @DisplayName("трансфер — 80; без посока — без код")
  void трансферът() {
    assertEquals("80", auto(Direction.TRANSFER, null));
    assertNull(auto(null, null));
  }
}

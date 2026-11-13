package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Двигателят на себестойността — средна претеглена след всяко постъпление, „стойност, не цена“.
 * Задължителният пример от заданието: вход 100 бр. × 10,00 и 50 бр. × 12,00; изход 120 бр.
 */
class BgStockCostEngineTest {

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  @Test
  @DisplayName("задължителният пример: средна претеглена — изход 1 280,00, остатък 30 × = 320,00")
  void задължителният_пример() {
    BgStockCostEngine.Layer layer = BgStockCostEngine.Layer.empty();
    BgStockCostEngine.receive(layer, d("100"), BgStockCostEngine.valueOf(d("100"), d("10.00")));
    BgStockCostEngine.receive(layer, d("50"), BgStockCostEngine.valueOf(d("50"), d("12.00")));
    assertEquals(d("1600.00"), layer.value());

    BgStockCostEngine.Issue issue = BgStockCostEngine.issue(layer, d("120"));
    assertEquals(d("1280.00"), issue.amount(), "1 600 / 150 × 120");
    assertEquals(0, d("30").compareTo(layer.qty()));
    assertEquals(d("320.00"), layer.value());
    // Формулата на Axelor с 2 знака на единичната цена би дала 10,67 × 120 = 1 280,40.
    assertTrue(issue.amount().compareTo(d("1280.40")) != 0);
  }

  @Test
  @DisplayName("изходът, който изчерпва слоя, взема ЦЯЛАТА остатъчна стойност — без стотинки")
  void изчерпването_взема_остатъка() {
    BgStockCostEngine.Layer layer = BgStockCostEngine.Layer.empty();
    BgStockCostEngine.receive(layer, d("3"), d("1.00"));
    assertEquals(d("0.33"), BgStockCostEngine.issue(layer, d("1")).amount());
    assertEquals(d("0.34"), BgStockCostEngine.issue(layer, d("1")).amount(), "0,67 / 2 = 0,335");
    assertEquals(d("0.33"), BgStockCostEngine.issue(layer, d("1")).amount(), "целият остатък");
    assertEquals(0, layer.qty().signum());
    assertEquals(0, layer.value().signum(), "в слоя не остават стотинки");
  }

  @Test
  @DisplayName("недостиг: слоят не се пипа, сумата е празна, недостигът е назован")
  void недостигът() {
    BgStockCostEngine.Layer layer = BgStockCostEngine.Layer.empty();
    BgStockCostEngine.receive(layer, d("10"), d("100.00"));
    BgStockCostEngine.Issue issue = BgStockCostEngine.issue(layer, d("12"));
    assertTrue(issue.isShort());
    assertNull(issue.amount());
    assertEquals(0, d("2").compareTo(issue.shortage()));
    assertEquals(0, d("10").compareTo(layer.qty()), "слоят е непокътнат");
    assertEquals(d("100.00"), layer.value());
  }

  @Test
  @DisplayName("сверка: Σ изписано + остатък = Σ постъпления до стотинка, при всякакъв ред")
  void стойността_се_запазва() {
    Random random = new Random(20261008L);
    for (int run = 0; run < 200; run++) {
      BgStockCostEngine.Layer layer = BgStockCostEngine.Layer.empty();
      BigDecimal received = BigDecimal.ZERO;
      BigDecimal issued = BigDecimal.ZERO;
      for (int step = 0; step < 40; step++) {
        if (random.nextInt(3) > 0 || layer.qty().signum() == 0) {
          BigDecimal qty = BigDecimal.valueOf(1 + random.nextInt(97));
          BigDecimal price = BigDecimal.valueOf(1 + random.nextInt(99_999), 3);
          BigDecimal value = BgStockCostEngine.valueOf(qty, price);
          BgStockCostEngine.receive(layer, qty, value);
          received = received.add(value);
        } else {
          BigDecimal qty = BigDecimal.valueOf(1 + random.nextInt(layer.qty().intValue()));
          issued = issued.add(BgStockCostEngine.issue(layer, qty).amount());
        }
        assertEquals(received, issued.add(layer.value()), "стъпка " + step + " на пробег " + run);
        assertTrue(layer.value().signum() >= 0);
      }
    }
  }

  @Test
  @DisplayName("стойността на постъпление: к-во × цена, HALF_UP до 0,01")
  void стойност_на_постъпление() {
    assertEquals(d("10.67"), BgStockCostEngine.valueOf(d("1"), d("10.666")));
    assertEquals(d("0.01"), BgStockCostEngine.valueOf(d("1"), d("0.005")));
    assertEquals(d("0.00"), BgStockCostEngine.valueOf(d("3"), null));
  }

  @Test
  @DisplayName("средната единична цена е само за показване; празен слой — без цена")
  void средната_цена() {
    BgStockCostEngine.Layer layer = new BgStockCostEngine.Layer(d("150"), d("1600.00"));
    assertEquals(d("10.666667"), layer.unitCost());
    assertNull(BgStockCostEngine.Layer.empty().unitCost());
  }

  @Test
  @DisplayName("отрицателно количество — програмна грешка, не тихо изписване")
  void отрицателното_количество() {
    BgStockCostEngine.Layer layer = BgStockCostEngine.Layer.empty();
    assertThrows(IllegalArgumentException.class, () -> BgStockCostEngine.issue(layer, d("-1")));
    assertThrows(
        IllegalArgumentException.class, () -> BgStockCostEngine.receive(layer, d("-1"), d("1")));
  }
}

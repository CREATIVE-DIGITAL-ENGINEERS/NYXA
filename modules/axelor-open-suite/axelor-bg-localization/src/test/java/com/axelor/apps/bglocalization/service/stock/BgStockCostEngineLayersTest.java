package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FIFO и LIFO (СС 2 т. 9.2 а и т. 10) — слоеве по реда на постъпване, „стойност, не цена“.
 * Задължителният пример: вход 100 бр. × 10,00 и 50 бр. × 12,00; изход 120 бр.
 */
class BgStockCostEngineLayersTest {

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  /** Двата входа от примера, в реда на постъпване. */
  private static List<BgStockCostEngine.Layer> example() {
    List<BgStockCostEngine.Layer> layers = new ArrayList<>();
    layers.add(new BgStockCostEngine.Layer(d("100"), d("1000.00")));
    layers.add(new BgStockCostEngine.Layer(d("50"), d("600.00")));
    return layers;
  }

  @Test
  @DisplayName("FIFO: изход 1 240,00 (100 × 10 + 20 × 12); остатък 30 × 12,00 = 360,00")
  void fifo_примерът() {
    List<BgStockCostEngine.Layer> layers = example();
    BgStockCostEngine.Issue issue = BgStockCostEngine.issue(layers, d("120"), false);
    assertEquals(d("1240.00"), issue.amount());
    assertEquals(0, layers.get(0).qty().signum(), "първият слой е изчерпан");
    assertEquals(0, d("30").compareTo(layers.get(1).qty()));
    assertEquals(d("360.00"), layers.get(1).value());
  }

  @Test
  @DisplayName("LIFO: изход 1 300,00 (50 × 12 + 70 × 10); остатък 30 × 10,00 = 300,00")
  void lifo_примерът() {
    List<BgStockCostEngine.Layer> layers = example();
    BgStockCostEngine.Issue issue = BgStockCostEngine.issue(layers, d("120"), true);
    assertEquals(d("1300.00"), issue.amount());
    assertEquals(0, layers.get(1).qty().signum(), "последният слой е изчерпан");
    assertEquals(0, d("30").compareTo(layers.get(0).qty()));
    assertEquals(d("300.00"), layers.get(0).value());
  }

  @Test
  @DisplayName("всеки изчерпан слой взема ЦЯЛАТА си стойност — без стотинки в слоевете")
  void изчерпването_по_слоеве() {
    List<BgStockCostEngine.Layer> layers = new ArrayList<>();
    layers.add(new BgStockCostEngine.Layer(d("3"), d("1.00")));
    layers.add(new BgStockCostEngine.Layer(d("3"), d("2.00")));
    assertEquals(d("0.33"), BgStockCostEngine.issue(layers, d("1"), false).amount());
    assertEquals(d("0.67"), BgStockCostEngine.issue(layers, d("2"), false).amount(), "остатъкът");
    assertEquals(
        d("1.33"), BgStockCostEngine.issue(layers, d("2"), false).amount(), "2 от 3 бр. за 2,00");
    assertEquals(d("0.67"), BgStockCostEngine.issue(layers, d("1"), false).amount(), "остатъкът");
    for (BgStockCostEngine.Layer l : layers) {
      assertEquals(0, l.qty().signum());
      assertEquals(0, l.value().signum());
    }
  }

  @Test
  @DisplayName("недостиг: нито един слой не се пипа, сумата е празна")
  void недостигът() {
    List<BgStockCostEngine.Layer> layers = example();
    BgStockCostEngine.Issue issue = BgStockCostEngine.issue(layers, d("151"), false);
    assertTrue(issue.isShort());
    assertNull(issue.amount());
    assertEquals(0, d("1").compareTo(issue.shortage()));
    assertEquals(d("1000.00"), layers.get(0).value());
    assertEquals(d("600.00"), layers.get(1).value());
  }

  @Test
  @DisplayName("изходът казва колко и на каква стойност е взел от всеки слой (за трансфер)")
  void взетото_по_слоеве() {
    List<BgStockCostEngine.Layer> layers = example();
    BgStockCostEngine.Issue issue = BgStockCostEngine.issue(layers, d("120"), false);
    List<BgStockCostEngine.Layer> taken = issue.taken();
    assertEquals(2, taken.size());
    assertEquals(0, d("100").compareTo(taken.get(0).qty()));
    assertEquals(d("1000.00"), taken.get(0).value());
    assertEquals(0, d("20").compareTo(taken.get(1).qty()));
    assertEquals(d("240.00"), taken.get(1).value());
  }

  @Test
  @DisplayName("смяна FIFO/LIFO → средна от началото на годината: един слой със същата стойност")
  void сливането() {
    List<BgStockCostEngine.Layer> layers = example();
    BgStockCostEngine.issue(layers, d("120"), false);
    BgStockCostEngine.Layer merged = BgStockCostEngine.merge(layers);
    assertEquals(0, d("30").compareTo(merged.qty()));
    assertEquals(d("360.00"), merged.value());
  }

  @Test
  @DisplayName("сверка: Σ изписано + Σ остатъци = Σ постъпления до стотинка, FIFO и LIFO")
  void стойността_се_запазва() {
    for (boolean lifo : new boolean[] {false, true}) {
      Random random = new Random(lifo ? 2027L : 2026L);
      for (int run = 0; run < 200; run++) {
        List<BgStockCostEngine.Layer> layers = new ArrayList<>();
        BigDecimal received = BigDecimal.ZERO;
        BigDecimal issued = BigDecimal.ZERO;
        int onHand = 0;
        for (int step = 0; step < 40; step++) {
          if (random.nextInt(3) > 0 || onHand == 0) {
            int qty = 1 + random.nextInt(97);
            BigDecimal value =
                BgStockCostEngine.valueOf(
                    BigDecimal.valueOf(qty), BigDecimal.valueOf(1 + random.nextInt(99_999), 3));
            layers.add(new BgStockCostEngine.Layer(BigDecimal.valueOf(qty), value));
            received = received.add(value);
            onHand += qty;
          } else {
            int qty = 1 + random.nextInt(onHand);
            issued =
                issued.add(BgStockCostEngine.issue(layers, BigDecimal.valueOf(qty), lifo).amount());
            onHand -= qty;
          }
          BigDecimal remaining = BgStockCostEngine.merge(layers).value();
          assertEquals(
              received, issued.add(remaining), (lifo ? "LIFO " : "FIFO ") + run + "/" + step);
        }
      }
    }
  }
}

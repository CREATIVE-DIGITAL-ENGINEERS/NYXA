package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.axelor.apps.stock.db.StockLocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Складът за SAF-T: най-горният вътрешен родител, освен ако има вдигнат флаг по пътя. */
class BgSaftWarehousesTest {

  private static StockLocation location(int type, StockLocation parent, boolean flag) {
    StockLocation l = new StockLocation();
    l.setTypeSelect(type);
    l.setParentStockLocation(parent);
    l.setBgSaftWarehouse(flag);
    return l;
  }

  @Test
  @DisplayName("без флаг — най-горният вътрешен родител")
  void най_горният_родител() {
    StockLocation top = location(1, null, false);
    StockLocation hall = location(1, top, false);
    StockLocation shelf = location(1, hall, false);
    assertSame(top, BgSaftWarehouses.of(shelf));
    assertSame(top, BgSaftWarehouses.of(top));
  }

  @Test
  @DisplayName("с флаг — най-близкият вдигнат предшественик или самата локация")
  void флагът_печели() {
    StockLocation top = location(1, null, true);
    StockLocation shop = location(1, top, true);
    StockLocation shelf = location(1, shop, false);
    assertSame(shop, BgSaftWarehouses.of(shelf));
    assertSame(shop, BgSaftWarehouses.of(shop));
  }

  @Test
  @DisplayName("виртуална без флаг не е склад; с флаг (подизпълнител) — е")
  void виртуалната() {
    assertNull(BgSaftWarehouses.of(location(3, null, false)));
    StockLocation subcontractor = location(3, null, true);
    assertSame(subcontractor, BgSaftWarehouses.of(subcontractor));
    assertNull(BgSaftWarehouses.of(null));
  }

  @Test
  @DisplayName("цикъл в дървото не зацикля")
  void цикъл() {
    StockLocation a = location(1, null, false);
    StockLocation b = location(1, a, false);
    a.setParentStockLocation(b);
    BgSaftWarehouses.of(b);
  }
}

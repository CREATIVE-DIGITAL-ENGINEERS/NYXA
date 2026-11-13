package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.ProductFamily;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Видът на запаса: продуктът бие семейството, празното на двете места значи „не е СМЗ“. */
class BgSaftInventoryTypesTest {

  private static Product product(String own, String family) {
    Product p = new Product();
    p.setBgSaftInventoryType(own);
    if (family != null) {
      ProductFamily f = new ProductFamily();
      f.setBgSaftInventoryType(family);
      p.setProductFamily(f);
    }
    return p;
  }

  @Test
  @DisplayName("без override на продукта — видът на семейството")
  void от_семейството() {
    assertEquals("30", BgSaftInventoryTypes.typeOf(product(null, "30")));
    assertEquals("30", BgSaftInventoryTypes.typeOf(product("  ", "30")));
  }

  @Test
  @DisplayName("override на продукта бие семейството — хранителен продукт в REST9")
  void продуктът_бие_семейството() {
    assertEquals("10", BgSaftInventoryTypes.typeOf(product("10", null)));
    assertEquals("10", BgSaftInventoryTypes.typeOf(product("10", "30")));
  }

  @Test
  @DisplayName("празно и на двете места — не е стоково-материален запас")
  void не_е_смз() {
    assertNull(BgSaftInventoryTypes.typeOf(product(null, null)));
    assertNull(BgSaftInventoryTypes.typeOf(product(null, "")));
    assertNull(BgSaftInventoryTypes.typeOf(null));
  }
}

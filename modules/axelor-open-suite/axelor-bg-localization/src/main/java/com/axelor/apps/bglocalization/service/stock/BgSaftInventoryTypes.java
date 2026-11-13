package com.axelor.apps.bglocalization.service.stock;

import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.ProductFamily;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Видът на запаса по номенклатурата Nom_Inventory_Types (SAF-T MF.PS.6) и складовата сметка 30x на
 * БГ семействата. Чиста функция.
 *
 * <p>Видът се чете от продукта, а при празен — от семейството. Празно и на двете места значи „не е
 * стоково-материален запас“: услугите, ДМА и директно разходваното не са СМЗ (QA с. 76, Прил. № 3
 * т. IV.6). REST9 е празно нарочно: готовите ястия не са СМЗ, а продуктите, от които се приготвят,
 * са (QA с. 85–86) — те получават вида си на ниво продукт.
 */
public final class BgSaftInventoryTypes {

  /**
   * Видът по подразбиране на БГ семействата по КОД — същият като колоната {@code
   * bgSaftInventoryType} в {@code BG_COA/base_productFamily.csv}. По код, а не по importId: BG_COA
   * внася семействата с importId 1–8 без префикс, а демото на axelor-base заема същите номера.
   */
  public static final Map<String, String> FAMILY_TYPES;

  /**
   * Складовата сметка 30x по КОД на семейството — същата като колоната {@code bgStockAccount_code}
   * в {@code BG_COA/account_accountManagement.csv}. PROD → 303, а не покупната 60101.
   */
  public static final Map<String, String> FAMILY_STOCK_ACCOUNTS;

  static {
    Map<String, String> types = new LinkedHashMap<>();
    types.put("GOODS", "30");
    types.put("PROD", "20");
    types.put("MAT", "10");
    types.put("BOOK9", "30");
    FAMILY_TYPES = Collections.unmodifiableMap(types);

    Map<String, String> accounts = new LinkedHashMap<>();
    accounts.put("GOODS", "304");
    accounts.put("PROD", "303");
    accounts.put("MAT", "302");
    accounts.put("BOOK9", "304");
    FAMILY_STOCK_ACCOUNTS = Collections.unmodifiableMap(accounts);
  }

  private BgSaftInventoryTypes() {}

  /** Видът на запаса на продукта, или {@code null}, когато не е стоково-материален запас. */
  public static String typeOf(Product product) {
    if (product == null) {
      return null;
    }
    String own = blankToNull(product.getBgSaftInventoryType());
    if (own != null) {
      return own;
    }
    ProductFamily family = product.getProductFamily();
    return family == null ? null : blankToNull(family.getBgSaftInventoryType());
  }

  private static String blankToNull(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }
}

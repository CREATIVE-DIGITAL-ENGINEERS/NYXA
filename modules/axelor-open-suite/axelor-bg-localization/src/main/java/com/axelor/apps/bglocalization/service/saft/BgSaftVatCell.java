package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseTarget;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesColumn;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * КОЛОНА ОТ ДНЕВНИКА (Блок Б) → КЛЕТКА 02-xx/03-xx — чиста функция. Кодът по TAX-IMP за клетката
 * идва от {@code BgSafVatCellCode}, не оттук: тук е само съответствието колона → клетка.
 *
 * <p><b>⚠️ НОМЕРЪТ НА КОЛОНАТА НЕ Е НОМЕРЪТ НА КЛЕТКАТА.</b> Колоните на дневниците са по
 * Приложения № 10 и № 11 към ППЗДДС (к.09–к.25), клетките — по справка-декларацията. Правилото е
 * това на {@code BgVatDeclarationAdapter.buildLines} (коментарите {@code // 02-XX}): к.11 → 02-11,
 * к.13 (ВОП) → 02-12, к.14 (чл. 82) → 02-26, к.17 → 02-13 и т.н. Колоните на ДДС-а (к.12, к.15,
 * к.18; к.11 и к.13 на покупките) нямат клетка тук — TaxCode е кодът на ОСНОВАТА, а данъкът е
 * S.TI.6 TaxAmount.
 *
 * <p>Капанът „100 + номер“ (02-20, 02-23, 02-24 и 03-43 срещу 100242) не стига дотук: клетката се
 * търси в таблицата, а не се смята.
 */
public final class BgSaftVatCell {

  private static final Map<BgVatSalesColumn, String> SALES = sales();
  private static final Map<BgVatPurchaseColumn, String> PURCHASES = purchases();

  private BgSaftVatCell() {}

  private static Map<BgVatSalesColumn, String> sales() {
    Map<BgVatSalesColumn, String> m = new EnumMap<>(BgVatSalesColumn.class);
    m.put(BgVatSalesColumn.K11_BASE_20, "02-11");
    m.put(BgVatSalesColumn.K13_BASE_ICA, "02-12");
    m.put(BgVatSalesColumn.K14_BASE_RC, "02-26");
    m.put(BgVatSalesColumn.K17_BASE_9, "02-13");
    m.put(BgVatSalesColumn.K19_BASE_ZERO_CH3, "02-14");
    m.put(BgVatSalesColumn.K20_BASE_ICS, "02-15");
    m.put(BgVatSalesColumn.K21_BASE_ART140_146_173, "02-16");
    m.put(BgVatSalesColumn.K22_BASE_EU_SERVICES, "02-17");
    m.put(BgVatSalesColumn.K23_BASE_ART69_2, "02-18");
    m.put(BgVatSalesColumn.K24_BASE_EXEMPT, "02-19");
    m.put(BgVatSalesColumn.K25_BASE_TRIANGULAR, "02-25");
    return Collections.unmodifiableMap(m);
  }

  private static Map<BgVatPurchaseColumn, String> purchases() {
    Map<BgVatPurchaseColumn, String> m = new EnumMap<>(BgVatPurchaseColumn.class);
    m.put(BgVatPurchaseColumn.K09_BASE_NO_CREDIT, "03-30");
    m.put(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT, "03-31");
    m.put(BgVatPurchaseColumn.K12_BASE_PARTIAL_CREDIT, "03-32");
    m.put(BgVatPurchaseColumn.K15_BASE_TRIANGULAR_ACQ, "03-44");
    return Collections.unmodifiableMap(m);
  }

  /** Клетката на продажбената колона за основа; {@code null} за колона на ДДС или без колона. */
  public static String ofSales(BgVatSalesColumn baseColumn) {
    return baseColumn == null ? null : SALES.get(baseColumn);
  }

  /** Клетката на покупната цел по колоната ѝ за основа; {@code null}, когато основа няма. */
  public static String ofPurchase(BgVatPurchaseTarget target) {
    return target == null || target.getBaseColumn() == null
        ? null
        : PURCHASES.get(target.getBaseColumn());
  }

  /** Клетката на покупната колона за основа; {@code null} за колона на ДДС или без колона. */
  public static String ofPurchaseColumn(BgVatPurchaseColumn baseColumn) {
    return baseColumn == null ? null : PURCHASES.get(baseColumn);
  }

  /**
   * Ключът на реда в {@code BgSafVatCellCode}: клетката, а при четирите кода по чл. 163а — и
   * стойността в нея ({@code 02-27[01]}).
   */
  public static String key(String cellCode, String cellValue) {
    String cell = cellCode == null ? "" : cellCode.trim();
    String value = cellValue == null ? "" : cellValue.trim();
    return value.isEmpty() ? cell : cell + "[" + value + "]";
  }
}

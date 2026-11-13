package com.axelor.apps.bglocalization.service.stock;

import com.axelor.apps.stock.db.StockLocation;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Складът за SAF-T (WarehouseID: MF.PS.2, ShipFrom/ShipTo на движението) за една локация. Чиста
 * функция.
 *
 * <p>Правилото от S1: складът е НАЙ-ГОРНИЯТ вътрешен родител, а локацията (LocationID, MF.PS.3) е
 * листът. Изключението е флагът {@code StockLocation.bgSaftWarehouse}: вдигнат ли е на локацията
 * или на неин предшественик, складът е НАЙ-БЛИЗКИЯТ вдигнат. Флагът важи и за виртуална локация —
 * така локацията на подизпълнителя влиза в PhysicalStock (решение на S1 за код 80).
 */
public final class BgSaftWarehouses {

  private BgSaftWarehouses() {}

  /** Складът на локацията, или {@code null} — виртуална локация без флаг не е склад. */
  public static StockLocation of(StockLocation location) {
    if (location == null) {
      return null;
    }
    Set<StockLocation> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    StockLocation topOwn = null;
    for (StockLocation l = location; l != null && seen.add(l); l = l.getParentStockLocation()) {
      if (Boolean.TRUE.equals(l.getBgSaftWarehouse())) {
        return l;
      }
      if (l.getTypeSelect() != BgStockMovementCodes.LOCATION_VIRTUAL) {
        topOwn = l;
      }
    }
    return location.getTypeSelect() == BgStockMovementCodes.LOCATION_VIRTUAL ? null : topOwn;
  }
}

package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.event.Observes;
import com.axelor.events.PreRequest;
import com.axelor.events.RequestEvent;
import com.axelor.events.qualifiers.EntityType;
import com.axelor.rpc.Request;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.inject.Named;

/**
 * МАСОВОТО ОБНОВЯВАНЕ НА АКТИВИ НЕ ПИПА ИНВЕНТАРНИЯ НОМЕР И КАКВО ТОЙ ОЗНАЧАВА.
 *
 * <p>{@code Resource.updateMass} пише с JPQL {@code UPDATE … WHERE id IN (…)}, без да зарежда
 * редовете, тоест без {@code PreUpdate}: {@link BgFixedAssetListener} не го вижда. Затова полетата
 * от {@link BgFixedAssetGuard#NOT_MASS_UPDATABLE} се отказват тук, за всички. Образецът е {@code
 * BgPartnerRequestGuard}.
 */
public class BgFixedAssetRequestGuard {

  public void onMassUpdate(
      @Observes @Named(RequestEvent.MASS_UPDATE) @EntityType(FixedAsset.class) PreRequest event) {
    for (String key : massUpdateValues(event.getRequest()).keySet()) {
      String field = key.replaceFirst("^self\\.", "");
      if (BgFixedAssetGuard.NOT_MASS_UPDATABLE.contains(field)) {
        throw new BgFixedAssetIdentityException(
            "Полето „" + field + "“ на актива не се сменя с масово обновяване.");
      }
    }
  }

  /**
   * Стойностите на масовото обновяване. ⚠️ Те са в {@code records[0]}, НЕ в {@code data}: {@code
   * data} носи филтъра — така ги праща клиентът и така ги чете {@code Resource.updateMass}.
   */
  @SuppressWarnings("unchecked")
  static Map<String, Object> massUpdateValues(Request request) {
    if (request == null) {
      return Collections.emptyMap();
    }
    List<Object> records = request.getRecords();
    if (records != null && !records.isEmpty() && records.get(0) instanceof Map) {
      return (Map<String, Object>) records.get(0);
    }
    return request.getData() == null ? Collections.emptyMap() : request.getData();
  }
}

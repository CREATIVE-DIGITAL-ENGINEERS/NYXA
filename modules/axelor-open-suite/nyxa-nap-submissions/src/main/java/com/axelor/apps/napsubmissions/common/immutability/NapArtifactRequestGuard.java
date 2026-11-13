package com.axelor.apps.napsubmissions.common.immutability;

import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmissionArtifact;
import com.axelor.event.Observes;
import com.axelor.events.PreRequest;
import com.axelor.events.RequestEvent;
import com.axelor.events.qualifiers.EntityType;
import com.axelor.rpc.Request;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.inject.Named;

/**
 * МАСОВОТО ОБНОВЯВАНЕ ({@code mass-update} от UI/REST) НЕ ПИПА ДОКАЗАТЕЛСТВОТО.
 *
 * <p>{@code Resource.updateMass} пише с JPQL {@code UPDATE … WHERE id IN (…)} — без зареждане на
 * редовете, тоест без {@code PreUpdate}: {@link NapArtifactImmutabilityListener} не го вижда.
 * Затова тук, в {@link PreRequest} преди заявката: масово обновяване на поле, което е неизменно
 * (същите списъци като на слушателя), се отказва за всички, и за admin.
 *
 * <p>Промяна на заварен файл чрез {@code save} НЕ се проверява тук — нея я хваща слушателят на ниво
 * Hibernate по старото състояние, по всеки път (REST, UI, услуги, AI). Тук остава само СЪЗДАВАНЕТО
 * през REST: на ниво Hibernate INSERT от услуга и INSERT от клиент не се различават, а {@code
 * company} се попълва САМО от сървъра (решение от 17.10.2026).
 */
public class NapArtifactRequestGuard {

  public void onSave(
      @Observes @Named(RequestEvent.SAVE) @EntityType(NapArtifact.class) PreRequest event) {
    for (Map<String, Object> record : records(event.getRequest())) {
      if (idOf(record) == null && idOf(record.get("company")) != null) {
        throw new RegulatoryImmutabilityException(
            "Cannot set the company of a regulatory file from a client — the server sets it");
      }
    }
  }

  public void onMassUpdate(
      @Observes @Named(RequestEvent.MASS_UPDATE) @EntityType(NapArtifact.class) PreRequest event) {
    refuseFixed(event, NapArtifactImmutabilityListener.ARTIFACT_FIXED, "regulatory file");
  }

  public void onLinkMassUpdate(
      @Observes @Named(RequestEvent.MASS_UPDATE) @EntityType(NapSubmissionArtifact.class)
          PreRequest event) {
    refuseFixed(event, NapArtifactImmutabilityListener.LINK_FIXED, "submission-file link");
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> records(Request request) {
    if (request == null) {
      return Collections.emptyList();
    }
    List<Map<String, Object>> out = new ArrayList<>();
    if (request.getRecords() != null && !request.getRecords().isEmpty()) {
      for (Object record : request.getRecords()) {
        if (record instanceof Map) {
          out.add((Map<String, Object>) record);
        }
      }
    } else if (request.getData() != null) {
      out.add(request.getData());
    }
    return out;
  }

  /** id от {@code {id: …}} или от самия запис; {@code null}, ако няма. */
  @SuppressWarnings("unchecked")
  static Long idOf(Object value) {
    if (value instanceof Map) {
      value = ((Map<String, Object>) value).get("id");
    }
    if (value == null) {
      return null;
    }
    long id = Long.parseLong(value.toString());
    return id <= 0 ? null : id;
  }

  /**
   * Стойностите на масовото обновяване. ⚠️ Те са в {@code records[0]}, НЕ в {@code data}: {@code
   * data} носи филтъра ({@code _domain}, {@code _domainContext}, {@code criteria}) — така ги праща
   * клиентът (axelor-front {@code grid.tsx}) и така ги чете {@code Resource.updateMass}. Проверка
   * по {@code data} не вижда нито едно поле. {@code data} остава само ако записи няма.
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

  /** Име на поле от ключ на масовото обновяване ({@code self.x} → {@code x}). */
  static String fieldOf(String key) {
    return key.replaceFirst("^self\\.", "");
  }

  private static void refuseFixed(PreRequest event, Collection<String> fixed, String what) {
    Map<String, Object> data = massUpdateValues(event.getRequest());
    for (String key : data.keySet()) {
      String field = fieldOf(key);
      if (fixed.contains(field)) {
        throw new RegulatoryImmutabilityException(
            "Cannot mass-update '" + field + "' of a " + what + " — it is part of the evidence");
      }
    }
  }
}

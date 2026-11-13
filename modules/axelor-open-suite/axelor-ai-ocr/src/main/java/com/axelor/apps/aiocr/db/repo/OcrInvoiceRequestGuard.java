package com.axelor.apps.aiocr.db.repo;

import com.axelor.apps.account.db.Invoice;
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
 * The mass-update REST path does not load rows: {@code Resource.updateMass} issues a JPQL {@code
 * UPDATE … WHERE id IN (…)}, so no Hibernate {@code PreUpdate} fires and {@link
 * OcrInvoiceImmutabilityListener} cannot see it. This refuses, in the request itself, any mass
 * update that targets a protected OCR document-total / correction / audit column — for every
 * caller, including admin.
 *
 * <p>A hand-written JPQL {@code UPDATE} in server code bypasses this too; there is no
 * user-reachable route to it, and only a database trigger could stop it. Everything a client can
 * actually invoke — form Save, single-record REST, and mass update — is covered (the first two by
 * the listener, this by the request guard).
 */
public class OcrInvoiceRequestGuard {

  public void onMassUpdate(
      @Observes @Named(RequestEvent.MASS_UPDATE) @EntityType(Invoice.class) PreRequest event) {
    rejectProtectedMassUpdate(event.getRequest());
  }

  /**
   * Refuses the request when its mass-update values target a protected column. Pure, for testing.
   */
  static void rejectProtectedMassUpdate(Request request) {
    for (String key : massUpdateValues(request).keySet()) {
      String field = key.replaceFirst("^self\\.", "");
      if (OcrInvoiceWriteGuard.PROTECTED_FIELDS.contains(field)) {
        throw new IllegalArgumentException(
            "Cannot mass-update '"
                + field
                + "': the OCR document total and its correction change only through the audited"
                + " correction action.");
      }
    }
  }

  /**
   * The mass-update field/value map. It is in {@code records[0]} (the axelor-front grid sends it
   * there and {@code Resource.updateMass} reads it there); {@code data} carries the filter, so a
   * check against {@code data} would see no fields. Mirrors the НАП guard's reader.
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

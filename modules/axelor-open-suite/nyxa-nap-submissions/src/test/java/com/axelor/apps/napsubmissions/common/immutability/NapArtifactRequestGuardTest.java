package com.axelor.apps.napsubmissions.common.immutability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axelor.events.PreRequest;
import com.axelor.events.RequestEvent;
import com.axelor.rpc.Request;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Масовото обновяване ({@code mass-update}) пише с масов UPDATE покрай Hibernate пазача, затова
 * неизменните полета се отказват още в заявката. Обикновеният запис се проверява от {@code
 * NapArtifactImmutabilityListener}.
 */
class NapArtifactRequestGuardTest {

  private static final NapArtifactRequestGuard GUARD = new NapArtifactRequestGuard();

  private static Map<String, Object> ref(long id) {
    Map<String, Object> ref = new HashMap<>();
    ref.put("id", id);
    return ref;
  }

  /**
   * {@code POST /ws/rest/<model>/updateMass} — както го праща клиентът (axelor-front {@code
   * grid.tsx}) и както го чете {@code Resource.updateMass}: стойностите в {@code records[0]},
   * филтърът ({@code _domain}, {@code _domainContext}) в {@code data}.
   */
  static PreRequest massUpdate(String field, Object value) {
    Map<String, Object> values = new HashMap<>();
    values.put(field, value);
    List<Object> records = new ArrayList<>();
    records.add(values);
    Map<String, Object> filter = new HashMap<>();
    filter.put("_domain", "self.id IN (:__ids__)");
    filter.put("_domainContext", Map.of("__ids__", List.of(1L)));
    Request request = new Request();
    request.setRecords(records);
    request.setData(filter);
    return new PreRequest(RequestEvent.MASS_UPDATE, request);
  }

  @Test
  void massUpdateOfCompanyIsRefused() {
    assertThrows(
        RegulatoryImmutabilityException.class,
        () -> GUARD.onMassUpdate(massUpdate("company", ref(20))));
  }

  @Test
  void massUpdateOfTheEvidenceIsRefused() {
    for (String field :
        new String[] {"sha256", "metaFile", "artifactTypeSelect", "saftReport", "byteLength"}) {
      assertThrows(
          RegulatoryImmutabilityException.class,
          () -> GUARD.onMassUpdate(massUpdate(field, "x")),
          field);
    }
    assertDoesNotThrow(() -> GUARD.onMassUpdate(massUpdate("name", "x")));
  }

  @Test
  void massUpdateOfTheSubmissionLinkIsRefused() {
    for (String field : new String[] {"artifact", "submission", "roleSelect"}) {
      assertThrows(
          RegulatoryImmutabilityException.class,
          () -> GUARD.onLinkMassUpdate(massUpdate(field, ref(1))),
          field);
    }
  }

  // --- създаване през REST: company го попълва само сървърът --------------------------------

  private static PreRequest save(Long id, Object company) {
    Map<String, Object> data = new HashMap<>();
    if (id != null) {
      data.put("id", id);
    }
    data.put("company", company);
    data.put("name", "x");
    Request request = new Request();
    request.setData(data);
    return new PreRequest(RequestEvent.SAVE, request);
  }

  @Test
  void settingTheCompanyOnACreateIsRefused() {
    assertThrows(RegulatoryImmutabilityException.class, () -> GUARD.onSave(save(null, ref(10))));
    assertDoesNotThrow(() -> GUARD.onSave(save(null, null)));
  }

  @Test
  void anUpdateIsLeftToTheHibernateGuard() {
    assertDoesNotThrow(() -> GUARD.onSave(save(1L, ref(20))));
  }
}

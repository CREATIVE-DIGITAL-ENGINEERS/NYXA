package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * РЕШЕТКАТА НА ДРУЖЕСТВОТО — свалянето на другите СЕ СЛУЧВА, докато още се знае кой е новият.
 *
 * <p>⚠️ КАКВО ДОКАЗВА И КАКВО НЕ. Доказва решението върху редовете така, както идват от екрана. НЕ
 * доказва, че onChange се изпълнява — това е твърдение за {@code one-to-many.tsx} ({@code
 * setValue(nextItems, changed = true, …)}) и се чете в платформата, не се симулира тук.
 *
 * <p>⚠️ И РАЗГРАНИЧИТЕЛЯТ НЕ Е ДОГАДКА, ЗАТОВА ТЕСТЪТ ГО ПРОВЕРЯВА И В ДВЕТЕ ПОСОКИ. Правило
 * „печели последният ред“ би минало вдигането на ВТОРИЯ и би погодило вдигането на ПЪРВИЯ. Тук
 * печели онзи, който е вдигнат в подаденото, но не е бил вдигнат в базата — и двете посоки дават
 * онова, което човекът е щракнал.
 */
class BgDeclarantGridAutoClearTest {

  private static Map<String, Object> row(Long id, String names, Boolean isDefault) {
    Map<String, Object> map = new LinkedHashMap<>();
    if (id != null) {
      map.put("id", id);
    }
    map.put("declarantNames", names);
    if (isDefault != null) {
      map.put("isDefault", isDefault);
    }
    return map;
  }

  private static Set<Long> persisted(Long... ids) {
    return new HashSet<>(Arrays.asList(ids));
  }

  private static boolean isDefault(Map<String, Object> row) {
    return Boolean.TRUE.equals(row.get("isDefault"));
  }

  @Test
  @DisplayName("⚠️ Вдигаш ВТОРИЯ през решетката → първият се сваля ВЕДНАГА — счупеният път")
  void raisingTheSecondRowClearsTheFirst() {
    Map<String, Object> first = row(1L, "Иван Иванов", true);
    Map<String, Object> second = row(2L, "Мария Петрова", true);
    List<Map<String, Object>> rows = Arrays.asList(first, second);

    assertTrue(BgDeclarantResolver.clearOthersAroundNewlyRaised(rows, persisted(1L)));

    assertFalse(isDefault(first), "старият default пада");
    assertTrue(isDefault(second), "щракнатият остава");
  }

  @Test
  @DisplayName("⚠️ Вдигаш ПЪРВИЯ → вторият се сваля — посоката, която „последният ред“ би погодила")
  void raisingTheFirstRowClearsTheSecond() {
    Map<String, Object> first = row(1L, "Иван Иванов", true);
    Map<String, Object> second = row(2L, "Мария Петрова", true);
    List<Map<String, Object>> rows = Arrays.asList(first, second);

    assertTrue(BgDeclarantResolver.clearOthersAroundNewlyRaised(rows, persisted(2L)));

    assertTrue(isDefault(first), "щракнатият остава");
    assertFalse(isDefault(second), "старият default пада");
  }

  @Test
  @DisplayName("Нов, незаписан ред като default сваля стария записан")
  void aBrandNewRowRaisedAsDefaultClearsThePersistedOne() {
    Map<String, Object> existing = row(1L, "Иван Иванов", true);
    Map<String, Object> fresh = row(null, "Мария Петрова", true);
    List<Map<String, Object>> rows = Arrays.asList(existing, fresh);

    assertTrue(BgDeclarantResolver.clearOthersAroundNewlyRaised(rows, persisted(1L)));

    assertFalse(isDefault(existing));
    assertTrue(isDefault(fresh));
  }

  @Test
  @DisplayName("⚠️ Редакция по ДРУГО поле е no-op — дефолтът не мърда")
  void editingAnUnrelatedFieldChangesNothing() {
    Map<String, Object> first = row(1L, "Иван Иванов", true);
    Map<String, Object> second = row(2L, "Мария Петрова ПРОМЕНЕНА", false);
    List<Map<String, Object>> rows = Arrays.asList(first, second);

    assertFalse(BgDeclarantResolver.clearOthersAroundNewlyRaised(rows, persisted(1L)));

    assertTrue(isDefault(first), "завареният default стои");
    assertFalse(isDefault(second));
  }

  @Test
  @DisplayName("⚠️ Само СВАЛЯНЕ на отметката е no-op — екранът НЕ слага default вместо човека")
  void untickingTheOnlyDefaultDoesNotPickANewOne() {
    Map<String, Object> first = row(1L, "Иван Иванов", false);
    Map<String, Object> second = row(2L, "Мария Петрова", false);
    List<Map<String, Object>> rows = Arrays.asList(first, second);

    assertFalse(BgDeclarantResolver.clearOthersAroundNewlyRaised(rows, persisted(1L)));

    assertFalse(isDefault(first));
    assertFalse(isDefault(second), "„поне един default“ не е инвариантът");
  }

  @Test
  @DisplayName("⚠️ ДВАМА новоповдигнати са no-op — кой печели пак е неизвестно; лови отказът")
  void twoNewlyRaisedRowsAreLeftToTheSaveTimeRefusal() {
    Map<String, Object> first = row(1L, "Иван Иванов", true);
    Map<String, Object> second = row(2L, "Мария Петрова", true);
    List<Map<String, Object>> rows = Arrays.asList(first, second);

    assertFalse(
        BgDeclarantResolver.clearOthersAroundNewlyRaised(rows, Collections.<Long>emptySet()));

    assertTrue(isDefault(first), "нищо не е пипнато");
    assertTrue(isDefault(second));
  }

  @Test
  @DisplayName("⚠️ Ред-бланка без ключа isDefault се чете от БАЗАТА — инак екранът показва ДВА")
  void aStubRowWithoutTheFlagFallsBackToTheDatabase() {
    Map<String, Object> stub = new HashMap<>();
    stub.put("id", 1L);
    stub.put("version", 0);
    Map<String, Object> raised = row(2L, "Мария Петрова", true);
    List<Map<String, Object>> rows = Arrays.asList(stub, raised);

    assertTrue(BgDeclarantResolver.clearOthersAroundNewlyRaised(rows, persisted(1L)));

    assertEquals(Boolean.FALSE, stub.get("isDefault"), "флагът се дописва изрично");
    assertTrue(isDefault(raised));
  }

  @Test
  @DisplayName("Единственият вдигнат ред няма какво да сваля — без излишно връщане към екрана")
  void aSingleRaisedRowChangesNothing() {
    List<Map<String, Object>> rows =
        Arrays.asList(row(1L, "Иван Иванов", true), row(2L, "Мария Петрова", false));

    assertFalse(
        BgDeclarantResolver.clearOthersAroundNewlyRaised(rows, Collections.<Long>emptySet()));
  }

  @Test
  @DisplayName("Празна и липсваща колекция не гърмят")
  void emptyInputIsHarmless() {
    assertFalse(BgDeclarantResolver.clearOthersAroundNewlyRaised(null, persisted(1L)));
    assertFalse(
        BgDeclarantResolver.clearOthersAroundNewlyRaised(
            Collections.<Map<String, Object>>emptyList(), persisted(1L)));
  }
}

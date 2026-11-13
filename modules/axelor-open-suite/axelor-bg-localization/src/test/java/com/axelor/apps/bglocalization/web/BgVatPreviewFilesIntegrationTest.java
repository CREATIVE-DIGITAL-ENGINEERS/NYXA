package com.axelor.apps.bglocalization.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgDeclarant;
import com.axelor.apps.bglocalization.db.BgVatPackage;
import com.axelor.apps.bglocalization.db.BgVatPackageEvent;
import com.axelor.apps.bglocalization.db.BgVatReturn;
import com.axelor.apps.bglocalization.db.repo.BgVatPackageRepository;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * „ПРЕГЛЕД НА ФАЙЛОВЕТЕ“ ПРЕЗ ИСТИНСКИЯ КОНТРОЛЕР. Събитието „previewed“ не се свързва с проксито
 * на контекста ({@code Context.asType}), а със записа от базата — иначе бутонът пада с
 * ValidationException „company must not be null“ при persist на BgVatReturn$ByteBuddy. Контекстът
 * тук е като от формата — само id, версия и няколко полета.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgVatPreviewFilesTestModule.class)
@Tag("integration")
class BgVatPreviewFilesIntegrationTest extends JpaSupport {

  private static int seq;

  private static Long[] fixture() {
    String tag = String.valueOf(++seq) + System.nanoTime() % 100000;
    Long[] ids = new Long[3];
    JPA.runInTransaction(
        () -> {
          Company company = new Company();
          company.setName("Преглед " + tag);
          company.setCode("PV" + tag);
          JPA.em().persist(company);
          BgVatReturn r = new BgVatReturn();
          r.setCompany(company);
          r.setPeriod("202610");
          r.setStatusSelect("generated");
          JPA.em().persist(r);
          BgVatPackage pkg = new BgVatPackage();
          pkg.setBgVatReturn(r);
          pkg.setCompany(company);
          pkg.setPeriod("202610");
          pkg.setGeneratedOn(LocalDateTime.now());
          pkg.setStatusSelect(BgVatPackageRepository.STATUS_PREPARED);
          JPA.em().persist(pkg);
          ids[0] = company.getId();
          ids[1] = r.getId();
          ids[2] = pkg.getId();
        });
    // Нова заявка — записът не е в persistence контекста, както при истинския бутон.
    JPA.em().clear();
    return ids;
  }

  /**
   * Декларация с декларатор и пакет, чийто отпечатък вече не съвпада с данните: всяко пресмятане на
   * „остарял“ дава true, а в базата пакетът е записан като неостарял — точно случаят, в който
   * onLoad/прегледът биха писали (пакетът + събитие „stale_detected“).
   */
  private static Long[] staleFixture() {
    Long[] ids = fixture();
    JPA.runInTransaction(
        () -> {
          Company company = JPA.em().find(Company.class, ids[0]);
          BgDeclarant declarant = new BgDeclarant();
          declarant.setCompany(company);
          declarant.setDeclarantNames("Иван Иванов");
          declarant.setDeclarantEgn("8001010000");
          declarant.setIsDefault(true);
          JPA.em().persist(declarant);
          BgVatPackage pkg = JPA.em().find(BgVatPackage.class, ids[2]);
          pkg.setSourceFingerprint("отпечатък-отпреди-промяната");
          pkg.setStale(false);
        });
    JPA.em().clear();
    return ids;
  }

  /** {@code _original} на формата — каквото front-ът праща при save (processOriginal). */
  private static Map<String, Object> originalAsTheFormHoldsIt(Long returnId) {
    JPA.em().clear();
    BgVatReturn r = JPA.em().find(BgVatReturn.class, returnId);
    Map<String, Object> original = new HashMap<>();
    original.put("id", r.getId());
    original.put("version", r.getVersion());
    List<Map<String, Object>> packages = new ArrayList<>();
    for (BgVatPackage p :
        JPA.em()
            .createQuery(
                "SELECT p FROM BgVatPackage p WHERE p.bgVatReturn.id = :r", BgVatPackage.class)
            .setParameter("r", returnId)
            .getResultList()) {
      packages.add(new HashMap<>(Map.of("id", p.getId(), "version", p.getVersion())));
    }
    original.put("bgVatPackageList", packages);
    List<Map<String, Object>> events = new ArrayList<>();
    for (BgVatPackageEvent e :
        JPA.em()
            .createQuery(
                "SELECT e FROM BgVatPackageEvent e WHERE e.bgVatReturn.id = :r",
                BgVatPackageEvent.class)
            .setParameter("r", returnId)
            .getResultList()) {
      events.add(new HashMap<>(Map.of("id", e.getId(), "version", e.getVersion())));
    }
    original.put("bgVatPackageEventList", events);
    JPA.em().clear();
    return original;
  }

  private static ActionRequest formRequest(Long returnId, Long companyId) {
    BgVatReturn record = JPA.em().find(BgVatReturn.class, returnId);
    Map<String, Object> context = new HashMap<>();
    context.put("id", record.getId());
    context.put("version", record.getVersion());
    context.put("_model", BgVatReturn.class.getName());
    context.put("statusSelect", record.getStatusSelect());
    context.put("period", record.getPeriod());
    context.put("company", Map.of("id", companyId));
    JPA.em().clear();
    Map<String, Object> data = new HashMap<>();
    data.put("context", context);
    ActionRequest request = new ActionRequest();
    request.setModel(BgVatReturn.class.getName());
    request.setData(data);
    return request;
  }

  /** Записът на формата след действието минава ли проверката на платформата при save. */
  private static void assertTheFormCanStillSave(Map<String, Object> original, String after) {
    JPA.em().clear();
    assertDoesNotThrow(
        () -> JPA.runInTransaction(() -> JPA.verify(BgVatReturn.class, original)),
        after
            + " промени пакетите/логовете под отворената форма → OptimisticLockException при save");
  }

  @Test
  @DisplayName("Отварянето (onLoad) показва „остарял“, без да пише под отворената форма")
  void onLoadReportsStaleWithoutWriting() {
    Long[] ids = staleFixture();
    Map<String, Object> original = originalAsTheFormHoldsIt(ids[1]);
    ActionRequest request = formRequest(ids[1], ids[0]);
    ActionResponse response = new ActionResponse();
    JPA.runInTransaction(
        () -> Beans.get(BgVatReturnController.class).checkPackageStale(request, response));
    String data = String.valueOf(response.getData());

    assertTrue(data.contains("bgPackageStale=true"), data);
    assertTheFormCanStillSave(original, "onLoad");
  }

  @Test
  @DisplayName("„Преглед на файловете“ не пише в декларацията и в нейните пакети/логове")
  void previewDoesNotWriteUnderTheOpenForm() {
    Long[] ids = staleFixture();
    Map<String, Object> original = originalAsTheFormHoldsIt(ids[1]);
    String data = openPreview(ids[1], ids[0]);

    assertTrue(data.contains("_showRecord=" + ids[2]), data);
    assertTheFormCanStillSave(original, "прегледът");
  }

  private static String openPreview(Long returnId, Long companyId) {
    BgVatReturn record = JPA.em().find(BgVatReturn.class, returnId);
    Map<String, Object> context = new HashMap<>();
    context.put("id", record.getId());
    context.put("version", record.getVersion());
    context.put("_model", BgVatReturn.class.getName());
    context.put("statusSelect", record.getStatusSelect());
    context.put("period", record.getPeriod());
    context.put("company", Map.of("id", companyId));
    JPA.em().clear();
    Map<String, Object> data = new HashMap<>();
    data.put("context", context);
    ActionRequest request = new ActionRequest();
    request.setModel(BgVatReturn.class.getName());
    request.setData(data);
    ActionResponse response = new ActionResponse();
    JPA.runInTransaction(
        () -> Beans.get(BgVatReturnController.class).openPreview(request, response));
    return String.valueOf(response.getData());
  }

  @Test
  @DisplayName(
      "Преглед на файловете: отваря пакета и записва „previewed“ към пакета, не към декларацията")
  void previewOpensThePackageAndLogsTheEvent() {
    Long[] ids = fixture();
    long returnsBefore = JPA.all(BgVatReturn.class).count();
    String data = openPreview(ids[1], ids[0]);

    assertTrue(data.contains("_showRecord=" + ids[2]), data);
    assertFalse(data.contains("must not be null"), data);
    assertFalse(data.toLowerCase().contains("exception"), data);

    // От базата, не от persistence контекста: там проксито на контекста подменя getId().
    JPA.em().clear();
    List<BgVatPackageEvent> logged =
        JPA.em()
            .createQuery(
                "SELECT e FROM BgVatPackageEvent e WHERE e.bgVatPackage.id = :p"
                    + " AND e.eventTypeSelect = 'previewed'",
                BgVatPackageEvent.class)
            .setParameter("p", ids[2])
            .getResultList();
    assertEquals(1, logged.size(), "едно събитие към пакета");
    // Без декларацията: нов ред в нейните логове под отворената форма прави
    // следващия ѝ save OptimisticLockException (previewDoesNotWriteUnderTheOpenForm).
    assertEquals(null, logged.get(0).getBgVatReturn(), "събитието не е в логовете на декларацията");
    // ⚠️ M2O на Axelor е с cascade PERSIST: проксито става НОВА декларация (с празно дружество —
    // в теста bean validation е изключена, иначе това е „company must not be null“).
    assertEquals(returnsBefore, JPA.all(BgVatReturn.class).count(), "нова декларация");
  }
}

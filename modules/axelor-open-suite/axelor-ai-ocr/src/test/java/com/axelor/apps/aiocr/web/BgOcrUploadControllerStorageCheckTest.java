package com.axelor.apps.aiocr.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.meta.schema.actions.validate.validator.Error;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The UI button is admin-only ({@code showIf} in {@code AiOcrUpload.xml}), but that is only a hint
 * — {@link BgOcrUploadController#storageCheck} must refuse a non-admin caller itself, before
 * touching the scan service. Driven through the {@code isAdmin()} seam so this does not need a real
 * security context or a database.
 */
class BgOcrUploadControllerStorageCheckTest {

  private static final class Controller extends BgOcrUploadController {
    boolean admin;

    @Override
    protected boolean isAdmin() {
      return admin;
    }
  }

  @Test
  void nonAdminIsRefusedBeforeAnyScan() {
    Controller controller = new Controller();
    controller.admin = false;
    ActionResponse response = new ActionResponse();

    // Must return cleanly with a refusal, and must not attempt Beans.get(...).scan() at all (which
    // would throw here — no Guice injector in this pure unit test — proving the admin check runs
    // first and short-circuits everything else).
    controller.storageCheck(new ActionRequest(), response);

    Object data = response.getData();
    assertNotNull(data, "a refusal must reach the client as a response message");
    @SuppressWarnings("unchecked")
    Map<String, Object> flash = (Map<String, Object>) ((List<Object>) data).get(0);
    assertTrue(flash.containsKey(Error.KEY), "a non-admin call must be reported as an error");
  }

  /** Same admin re-check, now for the temporary "Clean orphan files" action. */
  @Test
  void nonAdminIsRefusedBeforeAnyCleanup() {
    Controller controller = new Controller();
    controller.admin = false;
    ActionResponse response = new ActionResponse();

    // Must not attempt Beans.get(OcrOrphanFileCleanupService.class).cleanup() at all (which would
    // throw here — no Guice injector in this pure unit test), proving the admin check runs first.
    controller.cleanOrphanFiles(new ActionRequest(), response);

    Object data = response.getData();
    assertNotNull(data, "a refusal must reach the client as a response message");
    @SuppressWarnings("unchecked")
    Map<String, Object> flash = (Map<String, Object>) ((List<Object>) data).get(0);
    assertTrue(flash.containsKey(Error.KEY), "a non-admin call must be reported as an error");
  }

  /** Same admin re-check, now for the temporary "Orphan inventory" action. */
  @Test
  void nonAdminIsRefusedBeforeAnyInventory() {
    Controller controller = new Controller();
    controller.admin = false;
    ActionResponse response = new ActionResponse();

    // Must not attempt Beans.get(OcrOrphanInventoryService.class).list() at all (which would throw
    // here — no Guice injector in this pure unit test), proving the admin check runs first.
    controller.orphanInventory(new ActionRequest(), response);

    Object data = response.getData();
    assertNotNull(data, "a refusal must reach the client as a response message");
    @SuppressWarnings("unchecked")
    Map<String, Object> flash = (Map<String, Object>) ((List<Object>) data).get(0);
    assertTrue(flash.containsKey(Error.KEY), "a non-admin call must be reported as an error");
  }

  /** Same admin re-check, now for the temporary single-file "Download orphan file" action. */
  @Test
  void nonAdminIsRefusedBeforeAnyDownload() {
    Controller controller = new Controller();
    controller.admin = false;
    ActionResponse response = new ActionResponse();

    // Must not attempt Beans.get(OcrOrphanInventoryService.class).authorizeDownload(...) at all
    // (which would throw here — no Guice injector in this pure unit test), proving the admin check
    // runs first, before even looking at the metaFileId the request carries.
    controller.downloadOrphanFile(new ActionRequest(), response);

    Object data = response.getData();
    assertNotNull(data, "a refusal must reach the client as a response message");
    @SuppressWarnings("unchecked")
    Map<String, Object> flash = (Map<String, Object>) ((List<Object>) data).get(0);
    assertTrue(flash.containsKey(Error.KEY), "a non-admin call must be reported as an error");
  }

  // --- BgOcrUploadController#parseOrphanMetaFileId: strict parsing, entity-context immune --------

  @Test
  void parseOrphanMetaFileIdRejectsAMissingValue() {
    Controller controller = new Controller();

    assertThrows(
        IllegalArgumentException.class,
        () -> controller.parseOrphanMetaFileId(new HashMap<>()),
        "no $orphanMetaFileId at all must be refused, not silently treated as null");
  }

  @Test
  void parseOrphanMetaFileIdRejectsABlankValue() {
    Controller controller = new Controller();
    Map<String, Object> context = new HashMap<>();
    context.put("$orphanMetaFileId", "   ");

    assertThrows(IllegalArgumentException.class, () -> controller.parseOrphanMetaFileId(context));
  }

  @Test
  void parseOrphanMetaFileIdRejectsANonNumericValue() {
    Controller controller = new Controller();
    Map<String, Object> context = new HashMap<>();
    context.put("$orphanMetaFileId", "not-a-number");

    assertThrows(IllegalArgumentException.class, () -> controller.parseOrphanMetaFileId(context));
  }

  @Test
  void parseOrphanMetaFileIdAcceptsANumericString() {
    Controller controller = new Controller();
    Map<String, Object> context = new HashMap<>();
    context.put("$orphanMetaFileId", "42");

    assertEquals(42L, controller.parseOrphanMetaFileId(context));
  }

  @Test
  void parseOrphanMetaFileIdAcceptsARealNumberType() {
    Controller controller = new Controller();
    Map<String, Object> context = new HashMap<>();
    context.put("$orphanMetaFileId", 42L);

    assertEquals(42L, controller.parseOrphanMetaFileId(context));
  }

  /**
   * The core anti-tampering property: only the dummy {@code $orphanMetaFileId} key is ever
   * consulted. A context that ALSO carries an {@code id} (as a stray/posted entity might) must not
   * influence the outcome at all — proving no entity from the request context is ever used for
   * authorization, only the explicit dummy value.
   */
  @Test
  void parseOrphanMetaFileIdIgnoresAnyOtherContextKeyIncludingId() {
    Controller controller = new Controller();
    Map<String, Object> context = new HashMap<>();
    context.put("id", 999L); // a decoy, as if a MetaFile entity had been posted
    context.put("_model", "com.axelor.meta.db.MetaFile");
    context.put("$orphanMetaFileId", "42");

    assertEquals(
        42L,
        controller.parseOrphanMetaFileId(context),
        "must read only $orphanMetaFileId, never id or any other context key");
  }

  @Test
  void parseOrphanMetaFileIdRejectsWhenOnlyADecoyIdIsPresent() {
    Controller controller = new Controller();
    Map<String, Object> context = new HashMap<>();
    context.put("id", 999L); // no $orphanMetaFileId at all

    assertThrows(
        IllegalArgumentException.class,
        () -> controller.parseOrphanMetaFileId(context),
        "a posted id must never be mistaken for the admin-entered $orphanMetaFileId");
  }
}

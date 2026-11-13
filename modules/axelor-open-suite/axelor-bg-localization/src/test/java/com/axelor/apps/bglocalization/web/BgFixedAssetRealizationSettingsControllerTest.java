package com.axelor.apps.bglocalization.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.axelor.apps.bglocalization.db.BgFixedAssetRealizationSettings;
import com.axelor.apps.bglocalization.db.repo.BgFixedAssetRealizationSettingsRepository;
import com.axelor.apps.bglocalization.service.batch.BgFixedAssetRealizationSettingsService;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BgFixedAssetRealizationSettingsController}. No mocking framework is used
 * (matching this module's established style, see {@code AiAccountantAuthorizationTest}): {@link
 * BgFixedAssetRealizationSettingsService} and the admin check are faked by subclassing and
 * overriding the controller's protected seams, so neither Guice nor a real {@code
 * AuthUtils.getUser()} is needed.
 */
class BgFixedAssetRealizationSettingsControllerTest {

  private static class FakeSettingsService extends BgFixedAssetRealizationSettingsService {
    List<Long> selectedCompanyIds = List.of();
    BgFixedAssetRealizationSettings lastSynced;
    int syncCalls;

    FakeSettingsService() {
      super(null, null, null);
    }

    @Override
    public List<Long> findSelectedCompanyIds() {
      return selectedCompanyIds;
    }

    @Override
    public void syncSchedule(BgFixedAssetRealizationSettings settings) {
      syncCalls++;
      lastSynced = settings;
    }
  }

  /** Fakes the generated repository so {@code syncScheduleAfterSave} can be tested without JPA. */
  private static class FakeSettingsRepo extends BgFixedAssetRealizationSettingsRepository {
    BgFixedAssetRealizationSettings toReturn;
    Long lastRequestedId;

    @Override
    public BgFixedAssetRealizationSettings find(Long id) {
      lastRequestedId = id;
      return toReturn;
    }
  }

  private static class TestController extends BgFixedAssetRealizationSettingsController {
    boolean admin = true;
    final FakeSettingsService settingsService = new FakeSettingsService();
    final FakeSettingsRepo settingsRepo = new FakeSettingsRepo();
    Long extractedId = 42L;
    int manualRunCalls;

    @Override
    protected boolean isAdmin() {
      return admin;
    }

    @Override
    protected BgFixedAssetRealizationSettingsService settingsService() {
      return settingsService;
    }

    @Override
    protected BgFixedAssetRealizationSettingsRepository settingsRepo() {
      return settingsRepo;
    }

    @Override
    protected Long extractSettingsId(ActionRequest request) {
      return extractedId;
    }

    @Override
    protected void triggerManualRun() {
      manualRunCalls++;
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> payload(ActionResponse response) {
    Object data = response.getData();
    if (!(data instanceof List) || ((List<Object>) data).isEmpty()) {
      return Map.of();
    }
    Object first = ((List<Object>) data).get(0);
    return first instanceof Map ? (Map<String, Object>) first : Map.of();
  }

  /** {@code setError(message)} stores {"error": {"message": message}} — this reads the text. */
  @SuppressWarnings("unchecked")
  private static Object errorMessage(ActionResponse response) {
    Object error = payload(response).get("error");
    return error instanceof Map ? ((Map<String, Object>) error).get("message") : null;
  }

  @Test
  void runNowRefusesANonAdminAndNeverStartsTheJob() {
    TestController controller = new TestController();
    controller.admin = false;
    controller.settingsService.selectedCompanyIds = List.of(1L);
    ActionResponse response = new ActionResponse();

    controller.runNow(null, response);

    assertEquals(0, controller.manualRunCalls);
    assertEquals(
        "You are not allowed to view or change the depreciation automation settings.",
        errorMessage(response));
  }

  @Test
  void runNowRefusesAnEmptySelectionAndNeverStartsTheJob() {
    TestController controller = new TestController();
    controller.settingsService.selectedCompanyIds = List.of(); // nothing selected
    ActionResponse response = new ActionResponse();

    controller.runNow(null, response);

    assertEquals(0, controller.manualRunCalls);
    assertEquals(
        "Select at least one company before running. Nothing was started.", errorMessage(response));
  }

  @Test
  void runNowStartsTheJobForAnAdminWithASelection() {
    TestController controller = new TestController();
    controller.settingsService.selectedCompanyIds = List.of(1L, 2L);
    ActionResponse response = new ActionResponse();

    controller.runNow(null, response);

    assertEquals(1, controller.manualRunCalls);
    assertNull(errorMessage(response));
  }

  /** Run now must work exactly the same whether the monthly schedule is enabled or not. */
  @Test
  void runNowStartsTheJobEvenWhenAutomaticProcessingIsDisabled() {
    TestController controller = new TestController();
    // runNow never even looks at the "active" flag — it only cares about the persisted company
    // selection — so there is nothing to toggle here; this test pins down that indifference.
    controller.settingsService.selectedCompanyIds = List.of(1L);
    ActionResponse response = new ActionResponse();

    controller.runNow(null, response);

    assertEquals(1, controller.manualRunCalls);
    assertNull(errorMessage(response));
  }

  /**
   * The form-save path: after "save" has already committed the settings row (see the form's onSave
   * chain), this action must re-read that exact persisted row by id — never trust the request
   * context for it — and hand it to {@code syncSchedule}.
   */
  @Test
  void syncScheduleAfterSaveReadsThePersistedRowAndSyncsIt() {
    TestController controller = new TestController();
    BgFixedAssetRealizationSettings persisted = new BgFixedAssetRealizationSettings();
    controller.settingsRepo.toReturn = persisted;
    ActionResponse response = new ActionResponse();

    controller.syncScheduleAfterSave(null, response);

    assertEquals(42L, controller.settingsRepo.lastRequestedId);
    assertEquals(1, controller.settingsService.syncCalls);
    assertSame(persisted, controller.settingsService.lastSynced);
    assertNull(errorMessage(response));
  }

  @Test
  void syncScheduleAfterSaveRefusesANonAdminAndNeverSyncs() {
    TestController controller = new TestController();
    controller.admin = false;
    ActionResponse response = new ActionResponse();

    controller.syncScheduleAfterSave(null, response);

    assertEquals(0, controller.settingsService.syncCalls);
    assertEquals(
        "You are not allowed to view or change the depreciation automation settings.",
        errorMessage(response));
  }

  @Test
  void saveGuardRefusesANonAdmin() {
    TestController controller = new TestController();
    controller.admin = false;
    ActionResponse response = new ActionResponse();

    controller.denyNonAdminOnSave(null, response);

    assertEquals(
        "You are not allowed to view or change the depreciation automation settings.",
        errorMessage(response));
  }
}

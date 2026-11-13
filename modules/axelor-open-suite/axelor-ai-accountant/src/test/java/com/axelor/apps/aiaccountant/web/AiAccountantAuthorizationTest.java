package com.axelor.apps.aiaccountant.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.db.AiAccountantSetting;
import com.axelor.rpc.ActionResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Non-administrators are refused by the action itself, not merely by a hidden menu.
 *
 * <p>{@code groups="admins"} on a menuitem decides what is drawn. An action can be invoked by name
 * over the web API by anyone who can reach it, so the check that matters is the one inside the
 * action — which is what these tests pin down.
 */
class AiAccountantAuthorizationTest {

  /** Settings controller with the authorisation and the database both stubbed. */
  private static class TestSettingController extends AiAccountantSettingController {
    private final boolean admin;
    boolean created;

    TestSettingController(boolean admin) {
      this.admin = admin;
    }

    @Override
    protected boolean isAdmin() {
      return admin;
    }

    @Override
    protected AiAccountantSetting findOrCreate() {
      created = true;
      AiAccountantSetting setting = new AiAccountantSetting();
      setting.setSingletonKey("default");
      return setting;
    }
  }

  private static class TestStatsController extends AiAccountantStatsController {
    private final boolean admin;

    TestStatsController(boolean admin) {
      this.admin = admin;
    }

    @Override
    protected boolean isAdmin() {
      return admin;
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

  @Test
  void nonAdminIsRefusedTheSettingsScreenAndNoRowIsCreated() {
    TestSettingController controller = new TestSettingController(false);
    ActionResponse response = new ActionResponse();

    controller.openSettings(null, response);

    assertNotNull(payload(response).get("error"), "the refusal must reach the client as an error");
    assertNull(payload(response).get("view"), "and must not open the form anyway");
    // The row is created by opening the screen, so a refused caller must not cause one either.
    assertFalse(controller.created);
  }

  @Test
  void adminGetsTheSettingsForm() {
    TestSettingController controller = new TestSettingController(true);
    ActionResponse response = new ActionResponse();

    controller.openSettings(null, response);

    assertNull(payload(response).get("error"));
    assertNotNull(payload(response).get("view"));
    assertTrue(controller.created);
  }

  @Test
  void nonAdminIsRefusedTheEffectiveValues() {
    TestSettingController controller = new TestSettingController(false);
    ActionResponse response = new ActionResponse();

    controller.showEffectiveValues(null, response);

    // The effective values name what the assistant is configured to do; refused callers get none.
    assertNotNull(payload(response).get("error"));
    assertNull(payload(response).get("values"));
  }

  @Test
  void nonAdminGetsNoAzureStatusDetail() {
    ActionResponse response = new ActionResponse();

    new TestStatsController(false).azureStatus(null, response);

    // Answers, because a dashlet dataset that errors renders as a broken tile — but says nothing
    // about the configuration.
    Object data = response.getData();
    assertTrue(data instanceof List);
    @SuppressWarnings("unchecked")
    Map<String, Object> row = (Map<String, Object>) ((List<Object>) data).get(0);
    assertEquals("Not available", row.get("headline"));
    assertFalse(String.valueOf(row.get("detail")).contains("aiaccountant."));
  }
}

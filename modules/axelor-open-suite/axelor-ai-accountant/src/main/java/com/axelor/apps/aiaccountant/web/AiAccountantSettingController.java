package com.axelor.apps.aiaccountant.web;

import com.axelor.apps.aiaccountant.config.AiAccountantSettingOverrideService;
import com.axelor.apps.aiaccountant.config.AiConversationMemoryPreset;
import com.axelor.apps.aiaccountant.db.AiAccountantSetting;
import com.axelor.apps.aiaccountant.db.repo.AiAccountantSettingRepository;
import com.axelor.auth.AuthUtils;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.persist.Transactional;

/**
 * Opens the single settings row and explains what is actually in force.
 *
 * <h2>Authorisation is checked here, not only on the menu</h2>
 *
 * The menu entry is hidden from non-administrators, which is presentation. An action can be invoked
 * by name by anyone who can reach the web API, so the check is repeated where it means something —
 * the same {@code AuthUtils.isAdmin} guard {@code AiAccountantRestController} already uses for the
 * tool-publishing endpoints.
 *
 * <h2>The one place the row is created</h2>
 *
 * {@link #openSettings} is the only writer of a missing row. The configuration readers deliberately
 * never create it: a getter that inserts is a getter that can fail on a unique constraint when two
 * requests read it at once, and an absent row already has a correct meaning — inherit everything.
 */
public class AiAccountantSettingController {

  public void openSettings(ActionRequest request, ActionResponse response) {
    if (denyNonAdmin(response)) {
      return;
    }
    AiAccountantSetting setting = findOrCreate();
    response.setView(
        ActionView.define(I18n.get("AI Accountant Settings"))
            .model(AiAccountantSetting.class.getName())
            .add("form", "ai-accountant-setting-form")
            .param("forceEdit", "true")
            .context("_showRecord", setting.getId())
            .map());
  }

  /**
   * What the chosen conversation-memory preset actually means in tokens.
   *
   * <p>The technical toggles this used to describe (enabled override, router, max tool iterations)
   * are no longer on this form — Application Configuration is the real on/off switch now — but
   * their backend fields and precedence are untouched.
   *
   * <p>Computed by asking {@link AiAccountantSettingOverrideService} rather than by re-reading the
   * row and repeating its logic — if this screen and the assistant ever disagreed about what is in
   * effect, the screen would be the more convincing of the two and the wrong one.
   */
  public void showEffectiveValues(ActionRequest request, ActionResponse response) {
    if (denyNonAdmin(response)) {
      return;
    }
    AiAccountantSettingOverrideService overrides =
        Beans.get(AiAccountantSettingOverrideService.class);

    response.setValue(
        "$effectiveConversationMemory", describeMemory(overrides.conversationMemoryPreset()));
  }

  /** "Medium — up to 160,000 tokens of conversation before it starts fresh (recommended)." */
  protected String describeMemory(AiConversationMemoryPreset preset) {
    String label;
    switch (preset) {
      case MINIMAL:
        label = I18n.get("Minimal");
        break;
      case LOW:
        label = I18n.get("Low");
        break;
      case HIGH:
        label = I18n.get("High");
        break;
      case MEDIUM:
      default:
        label = I18n.get("Medium");
        break;
    }
    String tokens = String.format("%,d", preset.tokens());
    String suffix =
        preset == AiConversationMemoryPreset.MEDIUM ? " (" + I18n.get("recommended") + ")" : "";
    return label
        + " — "
        + I18n.get("keeps up to")
        + " "
        + tokens
        + " "
        + I18n.get("tokens of conversation before it starts fresh")
        + suffix
        + ".";
  }

  /**
   * Finds the row, or creates the one row.
   *
   * <p>Not annotated on the public action: the view/response work around it has no business being
   * inside a transaction, and keeping the write to its own method keeps the transaction as short as
   * the insert it exists for.
   */
  @Transactional
  protected AiAccountantSetting findOrCreate() {
    AiAccountantSettingRepository repo = Beans.get(AiAccountantSettingRepository.class);
    AiAccountantSetting setting =
        repo.all()
            .filter("self.singletonKey = :key")
            .bind("key", AiAccountantSettingOverrideService.SINGLETON_KEY)
            .fetchOne();
    if (setting != null) {
      return setting;
    }
    setting = new AiAccountantSetting();
    setting.setSingletonKey(AiAccountantSettingOverrideService.SINGLETON_KEY);
    // Every toggle stays at its Inherit default, so creating the row changes nothing about how the
    // assistant behaves. Opening this screen must not be an act of configuration.
    return repo.save(setting);
  }

  /** True when the caller was refused, so the action should stop. */
  protected boolean denyNonAdmin(ActionResponse response) {
    if (isAdmin()) {
      return false;
    }
    response.setError(
        I18n.get("You are not allowed to view or change the AI Accountant settings."));
    return true;
  }

  /** The authorisation itself, as a seam — {@code AuthUtils} is static and cannot be stubbed. */
  protected boolean isAdmin() {
    return AuthUtils.isAdmin(AuthUtils.getUser());
  }
}

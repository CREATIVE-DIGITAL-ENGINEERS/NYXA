package com.axelor.apps.bglocalization.web;

import com.axelor.apps.bglocalization.db.BgFixedAssetRealizationSettings;
import com.axelor.apps.bglocalization.db.repo.BgFixedAssetRealizationSettingsRepository;
import com.axelor.apps.bglocalization.job.BgFixedAssetRealizationJob;
import com.axelor.apps.bglocalization.service.batch.BgFixedAssetRealizationSettingsService;
import com.axelor.auth.AuthUtils;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import java.util.List;

/**
 * Backs the admin-only "Depreciation Automation" settings screen (Accounting > Fixed assets >
 * Depreciation Automation).
 *
 * <h2>Authorisation is checked here, not only on the menu</h2>
 *
 * The menu entry is {@code groups="admins"}, which is presentation only. An action can be invoked
 * by name by anyone who can reach the web API, so every action below repeats the check — same
 * rationale, same idiom ({@code AuthUtils.isAdmin}), as {@code AiAccountantSettingController}.
 *
 * <h2>Run now never trusts the caller's context for scope</h2>
 *
 * {@link #runNow} reads the company scope exclusively from the persisted settings row (via {@link
 * BgFixedAssetRealizationSettingsService#findSelectedCompanyIds()}), never from {@code
 * request.getContext()} — so it can never be tricked into running a different scope than what is
 * actually saved.
 */
public class BgFixedAssetRealizationSettingsController {

  public void openSettings(ActionRequest request, ActionResponse response) {
    if (denyNonAdmin(response)) {
      return;
    }
    BgFixedAssetRealizationSettings settings = settingsService().findOrCreate();
    response.setView(
        ActionView.define(I18n.get("Depreciation Automation"))
            .model(BgFixedAssetRealizationSettings.class.getName())
            .add("form", "bg-fixed-asset-realization-settings-form")
            .param("forceEdit", "true")
            .context("_showRecord", settings.getId())
            .map());
  }

  /** First link of the onSave chain: refuses the save outright for a non-admin. */
  public void denyNonAdminOnSave(ActionRequest request, ActionResponse response) {
    denyNonAdmin(response);
  }

  /** Last link of the onSave chain: re-reads the just-saved row and syncs the derived schedule. */
  public void syncScheduleAfterSave(ActionRequest request, ActionResponse response) {
    if (denyNonAdmin(response)) {
      return;
    }
    Long id = extractSettingsId(request);
    BgFixedAssetRealizationSettings settings = settingsRepo().find(id);
    settingsService().syncSchedule(settings);
    response.setNotify(I18n.get("Settings saved. The monthly schedule has been refreshed."));
  }

  // A separate, overridable seam so a test can supply a fixed id without needing a real
  // ActionRequest/Context (which needs a live Mapper/JPA setup this module's pure unit tests
  // deliberately avoid, matching its established no-mocking-framework style).
  protected Long extractSettingsId(ActionRequest request) {
    return request.getContext().asType(BgFixedAssetRealizationSettings.class).getId();
  }

  /**
   * Starts the existing job asynchronously for the persisted company selection and returns
   * immediately — never holds the HTTP request while depreciation is processed. Works exactly the
   * same whether Automatic monthly processing is on or off; that flag only gates the Quartz
   * trigger, never this action.
   */
  public void runNow(ActionRequest request, ActionResponse response) {
    if (denyNonAdmin(response)) {
      return;
    }
    List<Long> companyIds = settingsService().findSelectedCompanyIds();
    if (companyIds.isEmpty()) {
      response.setError(
          I18n.get("Select at least one company before running. Nothing was started."));
      return;
    }
    triggerManualRun();
    response.setNotify(
        I18n.get("Depreciation run started in the background for the selected companies."));
  }

  // A separate, overridable seam so a test can prove runNow() decides to start the job without
  // actually spawning a background thread / needing a Guice container.
  protected void triggerManualRun() {
    Beans.get(BgFixedAssetRealizationJob.class).triggerManualRun();
  }

  /** True when the caller was refused, so the calling action should stop. */
  protected boolean denyNonAdmin(ActionResponse response) {
    if (isAdmin()) {
      return false;
    }
    response.setError(
        I18n.get("You are not allowed to view or change the depreciation automation settings."));
    return true;
  }

  /** The authorisation itself, as a seam — {@code AuthUtils} is static and cannot be stubbed. */
  protected boolean isAdmin() {
    return AuthUtils.isAdmin(AuthUtils.getUser());
  }

  protected BgFixedAssetRealizationSettingsService settingsService() {
    return Beans.get(BgFixedAssetRealizationSettingsService.class);
  }

  protected BgFixedAssetRealizationSettingsRepository settingsRepo() {
    return Beans.get(BgFixedAssetRealizationSettingsRepository.class);
  }
}

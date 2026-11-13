package com.axelor.apps.aiaccountant.web;

import com.axelor.apps.aiaccountant.azuremonitor.AiAzureMetricSnapshotFreshness;
import com.axelor.apps.aiaccountant.azuremonitor.AiAzureMonitorStatus;
import com.axelor.apps.aiaccountant.azuremonitor.AiAzureMonitorUnavailable.Reason;
import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.axelor.auth.AuthUtils;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Feeds the Azure panel's banner: is this data fresh, and if not, why not.
 *
 * <h2>Why the page needs to be told this</h2>
 *
 * An empty Azure chart has three quite different causes — the feature is switched off, the {@code
 * Monitoring Reader} role was never granted, or the resource genuinely served nothing. On an axis
 * they are indistinguishable, and the first two are not things anyone will guess. So the banner
 * says which, in words, above the charts.
 *
 * <p>Freshness comes from the rows themselves rather than from the in-memory status, because that
 * is the answer that survives a restart and is the same on every node.
 */
public class AiAccountantStatsController {

  public void azureStatus(ActionRequest request, ActionResponse response) {
    if (!isAdmin()) {
      response.setData(
          Collections.singletonList(
              row(
                  "exclamation-triangle",
                  I18n.get("Not available"),
                  I18n.get("AI Accountant statistics are restricted to administrators."),
                  "")));
      return;
    }

    AiAccountantConfig config = Beans.get(AiAccountantConfig.class);
    AiAzureMonitorStatus status = Beans.get(AiAzureMonitorStatus.class);
    Map<String, Object> data;

    if (!config.isAzureMonitorEnabled()) {
      data =
          row(
              "toggle-off",
              I18n.get("Azure metrics are switched off"),
              I18n.get(
                  "Set aiaccountant.azure.monitor.enabled=true once the app registration has the"
                      + " 'Monitoring Reader' role on the Foundry resource. The local charts below"
                      + " do not depend on this."),
              freshness());
    } else if (config.azureMonitorResourceId().isEmpty()) {
      data =
          row(
              "exclamation-triangle",
              I18n.get("No Azure resource configured"),
              I18n.get("Set aiaccountant.azure.monitor.resource.id to the Foundry resource id."),
              freshness());
    } else if (status.getLastFailureReason() != null) {
      data =
          row(
              status.getLastFailureReason() == Reason.FORBIDDEN
                  ? "shield-exclamation"
                  : "exclamation-triangle",
              I18n.get("Azure metrics unavailable") + " (" + status.getLastFailureReason() + ")",
              status.getLastMessage() == null ? "" : status.getLastMessage(),
              freshness());
    } else if (status.getLastSuccessAt() == null) {
      data =
          row(
              "hourglass",
              I18n.get("Waiting for the first collection"),
              I18n.get(
                  "The scheduled jobs have not run yet on this node. The charts fill in once they"
                      + " do."),
              freshness());
    } else {
      data =
          row(
              "check-circle",
              I18n.get("Azure metrics are up to date"),
              I18n.get("Last collection wrote")
                  + " "
                  + status.getLastPointCount()
                  + " "
                  + I18n.get("datapoint(s)."),
              freshness());
    }
    response.setData(Collections.singletonList(data));
  }

  /**
   * When each stored series was last written, read from the rows.
   *
   * <p>Both grains are reported separately and deliberately: they are refreshed on different
   * schedules, so one being stale while the other is current is normal and worth seeing rather than
   * averaging away.
   */
  protected String freshness() {
    AiAzureMetricSnapshotFreshness freshness = Beans.get(AiAzureMetricSnapshotFreshness.class);
    LocalDateTime hourly = freshness.lastCollectedHourly();
    LocalDateTime daily = freshness.lastCollectedDaily();
    return I18n.get("Hourly series")
        + ": "
        + (hourly == null ? I18n.get("never") : hourly.toString())
        + " · "
        + I18n.get("daily series")
        + ": "
        + (daily == null ? I18n.get("never") : daily.toString())
        + " (UTC)";
  }

  /** The authorisation itself, as a seam — {@code AuthUtils} is static and cannot be stubbed. */
  protected boolean isAdmin() {
    return AuthUtils.isAdmin(AuthUtils.getUser());
  }

  protected Map<String, Object> row(String icon, String headline, String detail, String freshness) {
    Map<String, Object> data = new HashMap<>();
    data.put("icon", icon);
    data.put("headline", headline);
    data.put("detail", detail);
    data.put("freshness", freshness);
    return data;
  }
}

package com.axelor.apps.nyxasecurity.web;

import com.axelor.apps.nyxasecurity.gate.Refusals;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.studio.app.web.AppController;

/**
 * Apps management without importing demo data or roles, for everyone, technical admins included.
 * Demo data would land in a client's live records; the stock roles, with their {@code perm.*}
 * permissions, have no place next to the role catalogue. The buttons are removed from the views
 * (views/App.xml); this refuses the same actions when they are called directly. Install, uninstall
 * and configure are axelor-studio's, unchanged.
 */
public class NxAppController extends AppController {

  @Override
  public void importDataDemo(ActionRequest request, ActionResponse response) {
    response.setError(Refusals.tr(Refusals.APP_IMPORT_UNAVAILABLE));
  }

  @Override
  public void importRoles(ActionRequest request, ActionResponse response) {
    response.setError(Refusals.tr(Refusals.APP_IMPORT_UNAVAILABLE));
  }

  @Override
  public void importAllRoles(ActionRequest request, ActionResponse response) {
    response.setError(Refusals.tr(Refusals.APP_IMPORT_UNAVAILABLE));
  }

  /** Bulk install, but never with demo data. */
  @Override
  public void bulkInstall(ActionRequest request, ActionResponse response) {
    if (Boolean.TRUE.equals(request.getContext().get("importDemoData"))) {
      response.setError(Refusals.tr(Refusals.APP_IMPORT_UNAVAILABLE));
      return;
    }
    super.bulkInstall(request, response);
  }
}

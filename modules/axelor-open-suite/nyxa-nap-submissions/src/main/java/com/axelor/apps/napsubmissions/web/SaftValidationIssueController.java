package com.axelor.apps.napsubmissions.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.napsubmissions.db.SaftValidationIssue;
import com.axelor.i18n.I18n;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

/** Opens the Axelor source record a validation issue points to, when one is recorded. */
@Singleton
public class SaftValidationIssueController {

  public void navigateToSource(ActionRequest request, ActionResponse response) {
    try {
      SaftValidationIssue issue = request.getContext().asType(SaftValidationIssue.class);
      if (issue.getTargetModel() == null || issue.getTargetRecordId() == null) {
        response.setInfo(I18n.get("This issue has no linked source record to navigate to."));
        return;
      }
      String simpleName =
          issue.getTargetModel().substring(issue.getTargetModel().lastIndexOf('.') + 1);
      response.setView(
          ActionView.define(simpleName)
              .model(issue.getTargetModel())
              .add("form")
              .add("grid")
              .context("_showRecord", issue.getTargetRecordId())
              .map());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }
}

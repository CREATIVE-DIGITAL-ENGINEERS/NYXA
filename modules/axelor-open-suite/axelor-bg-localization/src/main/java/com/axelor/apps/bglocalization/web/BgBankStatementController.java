package com.axelor.apps.bglocalization.web;

import com.axelor.apps.bankpayment.db.BankStatement;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.service.bankstatement.BgBankStatementImportService;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

/** Thin controller for the BG Postbank statement screen. */
@Singleton
public class BgBankStatementController {

  /**
   * "Auto create Bank Details": create (or reuse) the company BankDetails for the IBAN parsed from
   * the imported statement and link this statement's unassigned lines to it, so the user can
   * reconcile without leaving the screen.
   */
  public void autoCreateBankDetails(ActionRequest request, ActionResponse response) {
    try {
      BankStatement statement = request.getContext().asType(BankStatement.class);
      BankDetails bankDetails =
          Beans.get(BgBankStatementImportService.class)
              .createBankDetailsForStatement(statement.getId());
      response.setReload(true);
      response.setNotify(
          String.format(I18n.get("Bank Details ready for IBAN %s."), bankDetails.getIban()));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Form onLoad: when this statement was rejected as a duplicate import, surface a bottom-right
   * notification so the user sees it without scrolling to the report at the bottom.
   */
  public void notifyDuplicate(ActionRequest request, ActionResponse response) {
    BankStatement statement = request.getContext().asType(BankStatement.class);
    Integer status = statement.getBgImportStatusSelect();
    if (status == BgBankStatementImportService.STATUS_DUPLICATE) {
      response.setNotify(
          I18n.get("Duplicate statement: this file was already imported — nothing was imported."));
    }
  }
}

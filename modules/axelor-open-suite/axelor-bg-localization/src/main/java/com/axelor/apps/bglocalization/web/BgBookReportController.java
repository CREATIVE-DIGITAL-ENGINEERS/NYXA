package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgBookReport;
import com.axelor.apps.bglocalization.service.accounting.BgBookReportService;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

/**
 * ЕКРАНЪТ НА СПРАВКИТЕ ПО СЧЕТОВОДНА ВАЛУТА — един бутон върху преходна форма.
 *
 * <p>Тънък слой нарочно, по образеца на {@code BgPayrollLedgerController}: правилото за валутата и
 * обходът на проводките живеят в {@code BgBookReportService}; тук само се чете контекстът, подава
 * се активното дружество и резултатът се връща в полето {@code reportText}. Работи върху контекста
 * без {@code save} — формата е преходна (canSave="false").
 */
@Singleton
public class BgBookReportController {

  /**
   * Генерира избраната справка за периода в активното дружество.
   *
   * <p>Дружеството е активното на потребителя: справката няма свое поле за дружество, а {@code
   * BgBookReportService} отказва с ясно съобщение, ако липсва.
   */
  public void generate(ActionRequest request, ActionResponse response) {
    try {
      BgBookReport form = request.getContext().asType(BgBookReport.class);
      User user = AuthUtils.getUser();
      Company company = user == null ? null : user.getActiveCompany();
      String text =
          Beans.get(BgBookReportService.class)
              .generate(company, form.getFromDate(), form.getToDate(), form.getReportKind());
      response.setValue("reportText", text);
    } catch (Exception e) {
      response.setValue("reportText", null);
      TraceBackService.trace(response, e);
    }
  }
}

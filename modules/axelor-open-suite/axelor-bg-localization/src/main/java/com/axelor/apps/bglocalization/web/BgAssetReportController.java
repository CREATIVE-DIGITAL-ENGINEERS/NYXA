package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgAssetPlanReport;
import com.axelor.apps.bglocalization.db.BgTaxDifferenceReport;
import com.axelor.apps.bglocalization.db.repo.BgAssetPlanReportRepository;
import com.axelor.apps.bglocalization.db.repo.BgTaxDifferenceReportRepository;
import com.axelor.apps.bglocalization.service.fixedasset.BgAssetPlanReportService;
import com.axelor.apps.bglocalization.service.fixedasset.BgGddMapping;
import com.axelor.apps.bglocalization.service.fixedasset.BgTaxDifferenceReportService;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

/** Бутоните „Изчисли“ на трите справки за активи. */
@Singleton
public class BgAssetReportController {

  /**
   * Изчислява справката за данъчни временни разлики.
   *
   * <p>⚠️ Съобщението КАЗВА за чакащите потвърждение, вместо да ги остави в списъка. Непотвърдено
   * обръщане по чл. 59 ал. 2 е пропусната задължителна корекция — тоест занижен данък — и не бива
   * да чака някой да разгъне панел, за да го види.
   */
  public void computeTaxDifferences(ActionRequest request, ActionResponse response) {
    try {
      BgTaxDifferenceReport report = request.getContext().asType(BgTaxDifferenceReport.class);
      if (report.getId() != null) {
        report = Beans.get(BgTaxDifferenceReportRepository.class).find(report.getId());
      }
      report = Beans.get(BgTaxDifferenceReportService.class).compute(report);
      response.setReload(true);

      // Генерираният getter на Integer връща 0 за незададена колона, никога null (виж
      // BgDefaultedGetterTest) — тъй че сравнение с null е мъртво условие.
      int pending = report.getPendingCount();
      StringBuilder msg = new StringBuilder();
      if (pending > 0) {
        msg.append(
            String.format(
                "⚠️ %d обръщания по чл. 59 ал. 2 ЧАКАТ ПОТВЪРЖДЕНИЕ и НЕ са включени в сборовете."
                    + " Намалението е задължително — непотвърдено, то занижава данъчния резултат."
                    + " Потвърдете ги на актива и изчислете отново. ",
                pending));
      }
      // Маркерът изброява ТОЧНО несверените редове и е празен, когато няма такива. Глобален флаг
      // би трябвало да се вдигне „наведнъж" за целия образец — а сверени са само редовете по
      // амортизациите.
      msg.append(BgGddMapping.unverifiedNote());
      if (msg.length() > 0) {
        response.setInfo(I18n.get(msg.toString()));
      }
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** Изчислява амортизационния план — данъчния или счетоводния, според planSelect. */
  public void computeAssetPlan(ActionRequest request, ActionResponse response) {
    try {
      BgAssetPlanReport report = request.getContext().asType(BgAssetPlanReport.class);
      if (report.getId() != null) {
        report = Beans.get(BgAssetPlanReportRepository.class).find(report.getId());
      }
      Beans.get(BgAssetPlanReportService.class).compute(report);
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }
}

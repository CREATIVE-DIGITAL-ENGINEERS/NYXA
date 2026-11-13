package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgNsiGodReport;
import com.axelor.apps.bglocalization.db.repo.BgNsiGodReportRepository;
import com.axelor.apps.bglocalization.service.nsi.BgNsiGodService;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

/**
 * Бутоните на справката „ГОД към НСИ — баланс и ОПР“.
 *
 * <p>Всяко действие: роля {@code accounting.write} (или {@code all.write}, {@code client.admin}),
 * запис върху справката и активно дружество — на ЗАПИСАНАТА справка, не на изпратената от клиента.
 * Платформата проверява само четене преди action.
 */
@Singleton
public class BgNsiGodController {

  public void compute(ActionRequest request, ActionResponse response) {
    writable(request, response, "compute the НСИ annual report", BgNsiGodService::compute);
  }

  public void lock(ActionRequest request, ActionResponse response) {
    writable(request, response, "lock the НСИ annual report", BgNsiGodService::lock);
  }

  public void unlock(ActionRequest request, ActionResponse response) {
    writable(request, response, "unlock the НСИ annual report", BgNsiGodService::unlock);
  }

  /** Новата справка: активното дружество, миналата година и единицата по нея. */
  public void defaults(ActionRequest request, ActionResponse response) {
    int year = java.time.LocalDate.now().getYear() - 1;
    response.setValue("reportYear", year);
    response.setValue("unitSelect", BgNsiGodService.defaultUnit(year));
  }

  /** При смяна на годината — единицата по нея. */
  public void yearChanged(ActionRequest request, ActionResponse response) {
    BgNsiGodReport report = request.getContext().asType(BgNsiGodReport.class);
    if (report.getReportYear() >= 2000) {
      response.setValue("unitSelect", BgNsiGodService.defaultUnit(report.getReportYear()));
    }
  }

  private interface Action {
    BgNsiGodReport apply(BgNsiGodService service, BgNsiGodReport report);
  }

  private static void writable(
      ActionRequest request, ActionResponse response, String action, Action work) {
    try {
      BgNsiGodReport context = request.getContext().asType(BgNsiGodReport.class);
      CapabilityGate.requireRole(action, Capabilities.ACCOUNTING_WRITE);
      if (context.getId() == null) {
        throw new IllegalStateException("Запишете справката първо.");
      }
      CapabilityGate.requireWrite(BgNsiGodReport.class, context.getId());
      BgNsiGodReport stored = Beans.get(BgNsiGodReportRepository.class).find(context.getId());
      CapabilityGate.requireActiveCompany(stored == null ? null : stored.getCompany());
      work.apply(Beans.get(BgNsiGodService.class), stored);
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }
}

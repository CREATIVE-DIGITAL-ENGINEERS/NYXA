package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.callable.ControllerCallableTool;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.service.demo.BgDemoBackgroundRun;
import com.axelor.apps.bglocalization.service.demo.BgDemoDataService;
import com.axelor.apps.bglocalization.service.demo.BgDemoDataset;
import com.axelor.apps.bglocalization.service.demo.BgDemoEmptyCompanyGuard;
import com.axelor.apps.bglocalization.service.demo.BgDemoPrerequisites;
import com.axelor.auth.AuthUtils;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.shiro.SecurityUtils;

/**
 * БУТОНИТЕ „НАЛЕЙ ДЕМО ДАННИ“ във формата на дружеството — само за технически администратор.
 *
 * <p>Отказ за всеки друг потребител тук, в кода — изгледът ги скрива, но действието може да се
 * извика и направо. Наборът идва от името на бутона ({@code bgDemoTradeBtn} → „Демо Търговия“,
 * {@code bgDemoHoldingBtn} → „Демо Холдинг“; {@code bgDemoPayrollBtn} — само ТРЗ на „Демо Търговия“
 * в дружество с вече налято счетоводство), годината — днешната дата на приложението.
 *
 * <p>Наливането трае минути: върви в отделна нишка ({@link ControllerCallableTool}, както вносът на
 * преводи в AOS) с потребителя на заявката и със свой обхват на заявка ({@link
 * BgDemoBackgroundRun}); ако не свърши до времето за изчакване, екранът казва, че процесът се
 * изчислява, а изходът (готово или грешката с причината) идва във „Входящи“.
 */
public class BgDemoDataController {

  public void load(ActionRequest request, ActionResponse response) {
    if (!AuthUtils.isAdmin(AuthUtils.getUser())) {
      response.setError("Демо данни налива само технически администратор.");
      return;
    }
    Company company = request.getContext().asType(Company.class);
    if (company == null || company.getId() == null) {
      response.setError("Запишете дружеството преди наливането.");
      return;
    }
    String signal = (String) request.getContext().get("_signal");
    BgDemoDataset dataset =
        "bgDemoHoldingBtn".equals(signal) ? BgDemoDataset.HOLDING : BgDemoDataset.TRADE;
    // „Налей демо ТРЗ“ — само ТРЗ в дружество с вече налято счетоводство на набора.
    boolean payrollOnly = "bgDemoPayrollBtn".equals(signal);
    int year = Beans.get(AppBaseService.class).getTodayDate(null).getYear();
    Long companyId = company.getId();

    // Липсващо приложение, непразно дружество (или за ТРЗ: не е налято / вече има ТРЗ) — отказ
    // веднага, с всички причини.
    List<String> refusals = new ArrayList<>(BgDemoPrerequisites.refusals(dataset));
    Company current = JPA.find(Company.class, companyId);
    refusals.addAll(
        payrollOnly
            ? BgDemoDataService.payrollRefusals(current, dataset)
            : BgDemoEmptyCompanyGuard.refusals(current));
    if (!refusals.isEmpty()) {
      response.setError(String.join("<br/>", refusals));
      return;
    }

    BgDemoDataService service = Beans.get(BgDemoDataService.class);
    Long userId = AuthUtils.getUser().getId();
    // ⚠️ Нишката на ControllerCallableTool няма HTTP заявка: обхватът на заявката, следата при
    // грешка и известието до потребителя са в BgDemoBackgroundRun — и за двата бутона. Изходът се
    // пази тук, защото инструментът не връща резултата на задачата.
    AtomicReference<BgDemoBackgroundRun.Outcome> outcome = new AtomicReference<>();
    Callable<Void> work =
        SecurityUtils.getSubject()
            .associateWith(
                () -> {
                  outcome.set(
                      BgDemoBackgroundRun.run(
                          companyId,
                          userId,
                          () ->
                              payrollOnly
                                  ? service.loadPayroll(companyId, dataset, year)
                                  : service.load(companyId, dataset, year)));
                  return null;
                });
    try {
      new ControllerCallableTool<Void>().runInSeparateThread(work, response);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
      return;
    }
    BgDemoBackgroundRun.Outcome done = outcome.get();
    if (done == null) {
      // Още тече (инструментът вече е казал „процесът се изчислява“).
      response.setInfo("Наливането продължава във фона. Резултатът ще дойде във „Входящи“.");
    } else if (done.ok) {
      response.setInfo(String.join("<br/>", BgDemoBackgroundRun.lines(done)));
      response.setReload(true);
    } else {
      response.setError(String.join("<br/>", BgDemoBackgroundRun.lines(done)));
    }
  }
}

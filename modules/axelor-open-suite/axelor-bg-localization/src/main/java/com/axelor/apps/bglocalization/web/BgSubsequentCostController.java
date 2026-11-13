package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgSubsequentCost;
import com.axelor.apps.bglocalization.db.repo.BgSubsequentCostRepository;
import com.axelor.apps.bglocalization.service.fixedasset.BgSubsequentCostRules;
import com.axelor.apps.bglocalization.service.fixedasset.BgSubsequentCostService;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

/** Бутоните на последващия разход: „Определи пътя" и „Приложи". */
@Singleton
public class BgSubsequentCostController {

  /** Показва по кой член ще мине разходът и от коя дата — преди да се пипне нещо. */
  public void resolve(ActionRequest request, ActionResponse response) {
    try {
      BgSubsequentCost cost = request.getContext().asType(BgSubsequentCost.class);
      Beans.get(BgSubsequentCostService.class).resolve(cost);
      response.setValues(cost);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Прилага разхода.
   *
   * <p>⚠️ Съобщението казва КАКВО е станало и по кой член, а не само „готово“. Правният път решава
   * дали ДАС се увеличава, или се ражда втори актив — разлика, която съставителят трябва да види
   * веднага, защото поправката ѝ после е ръчна.
   */
  public void apply(ActionRequest request, ActionResponse response) {
    try {
      BgSubsequentCost cost = request.getContext().asType(BgSubsequentCost.class);
      if (cost.getId() != null) {
        cost = Beans.get(BgSubsequentCostRepository.class).find(cost.getId());
      }
      cost = Beans.get(BgSubsequentCostService.class).apply(cost);
      response.setReload(true);

      String routing = cost.getRoutingSelect();
      if (BgSubsequentCostRules.Routing.ART63.equals(routing)) {
        response.setInfo(
            String.format(
                "Чл. 63 ЗКПО: данъчната амортизируема стойност е увеличена от %s на %s, в сила от"
                    + " %s — началото на месеца, през който са завършени разходите. Остатъкът от"
                    + " данъчния план е преизчислен; РЕАЛИЗИРАНИТЕ редове не са пипани и корекция"
                    + " по чл. 62 ал. 4 не се прави (последващият разход не е съществувал през"
                    + " предходните години).",
                cost.getAmortizableValueBefore(),
                cost.getAmortizableValueAfter(),
                cost.getEffectiveFrom()));
      } else if (BgSubsequentCostRules.Routing.ART64.equals(routing)) {
        response.setInfo(
            String.format(
                "Чл. 64 ал. 1 ЗКПО: породен е САМОСТОЯТЕЛЕН данъчен амортизируем актив „%s“, в"
                    + " категорията на стария (ал. 3). %s",
                cost.getGeneratedFixedAsset() == null
                    ? ""
                    : cost.getGeneratedFixedAsset().getName(),
                Boolean.TRUE.equals(cost.getIsBelowThreshold())
                    ? "⚠️ Сумата е ПОД прага по чл. 50/51, тъй че данъчен план не му се строи."
                        + " Забележете: прагът е приложен по тълкуване — чл. 64 ал. 1 казва „се"
                        + " завежда“ без да препраща към праг. Да се потвърди."
                    : "Данъчният план се строи по наследената норма."));
      } else {
        response.setInfo(
            "Разходът е класифициран като ТЕКУЩ или активът не е нито в данъчния план, нито само на"
                + " баланс — чл. 63 и чл. 64 не се прилагат и данъчната амортизируема стойност"
                + " остава непроменена. Това е правилният резултат, не пропуск.");
      }
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }
}

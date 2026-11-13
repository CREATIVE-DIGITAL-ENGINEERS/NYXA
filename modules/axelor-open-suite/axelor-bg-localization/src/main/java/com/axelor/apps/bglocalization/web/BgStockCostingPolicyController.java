package com.axelor.apps.bglocalization.web;

import com.axelor.apps.bglocalization.db.BgStockCostingPolicy;
import com.axelor.apps.bglocalization.db.repo.BgStockCostingPolicyRepository;
import com.axelor.apps.bglocalization.service.stock.BgStockCostingPolicyService;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.persist.Transactional;
import java.util.List;

/** Бутоните на политиката за изписване (ИЗП). И двата работят върху ЗАПИСАНАТА политика. */
public class BgStockCostingPolicyController {

  /** Добавя липсващите редове от предложението на Nyxa; попълнените не се пипат. */
  @Transactional(rollbackOn = Exception.class)
  public void fillDefaultRules(ActionRequest request, ActionResponse response) {
    BgStockCostingPolicy policy = saved(request);
    if (policy == null) {
      response.setInfo(I18n.get("Запишете политиката, преди да попълните сметките."));
      return;
    }
    List<String> missing = Beans.get(BgStockCostingPolicyService.class).fillDefaultRules(policy);
    Beans.get(BgStockCostingPolicyRepository.class).save(policy);
    response.setReload(true);
    if (!missing.isEmpty()) {
      response.setInfo(
          "Тези сметки ги няма в сметкоплана на дружеството и редовете им не са добавени: "
              + String.join(", ", missing)
              + ". Без ред за кода изписването остава неосчетоводено с грешка.");
    }
  }

  /** Активира политиката — от тук методът не се сменя до края на годината (СС 2 т. 8.5). */
  public void activate(ActionRequest request, ActionResponse response) {
    BgStockCostingPolicy policy = saved(request);
    if (policy == null) {
      response.setInfo(I18n.get("Запишете политиката, преди да я активирате."));
      return;
    }
    try {
      Beans.get(BgStockCostingPolicyService.class).activate(policy);
      response.setReload(true);
    } catch (Exception e) {
      response.setError(I18n.get(e.getMessage()));
    }
  }

  private static BgStockCostingPolicy saved(ActionRequest request) {
    BgStockCostingPolicy context = request.getContext().asType(BgStockCostingPolicy.class);
    return context.getId() == null
        ? null
        : Beans.get(BgStockCostingPolicyRepository.class).find(context.getId());
  }
}

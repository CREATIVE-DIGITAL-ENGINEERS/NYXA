package com.axelor.apps.bglocalization.db.repo;

import com.axelor.apps.bglocalization.db.BgStockCostingPolicy;
import com.axelor.apps.bglocalization.service.stock.BgStockCostingPolicyService;
import com.axelor.apps.bglocalization.service.stock.BgStockCostingRules;
import com.axelor.inject.Beans;
import javax.persistence.PersistenceException;

/**
 * Пазачът на политиката за изписване (ИЗП) при запис и триене — за всеки път (екран, REST, внос).
 *
 * <p>Изключенията по семейства и сметките висят на политиката с cascade, тоест записът на формата
 * минава само през тази репозитория и вижда цялата колекция ({@code BgCompanyManagementRepository}
 * обяснява защо репозиторията на детето не се докосва).
 *
 * <p>Отказът е {@code PersistenceException} по идиома на {@code BgCompanyManagementRepository}:
 * {@code AxelorException} не се обявява в подписа на {@code save}.
 */
public class BgStockCostingPolicyManagementRepository extends BgStockCostingPolicyRepository {

  @Override
  public BgStockCostingPolicy save(BgStockCostingPolicy policy) {
    if (policy != null) {
      String framework =
          policy.getCompany() == null ? null : policy.getCompany().getBgAccountingFramework();
      String refusal = BgStockCostingRules.refusal(policy, framework);
      if (refusal == null) {
        refusal = BgStockCostingRules.lockRefusal(policy);
      }
      if (refusal == null) {
        refusal = trackingRefusal(policy);
      }
      if (refusal != null) {
        throw new PersistenceException(refusal);
      }
      // Новата политика без нито един ред за сметка получава предложението на Nyxa. Попълненото
      // не се пипа — сметките са избор на дружеството.
      if (policy.getId() == null
          && (policy.getAccountRuleList() == null || policy.getAccountRuleList().isEmpty())) {
        fillDefaults(policy);
      }
    }
    return saveRecord(policy);
  }

  @Override
  public void remove(BgStockCostingPolicy policy) {
    String refusal = BgStockCostingRules.removeRefusal(policy);
    if (refusal != null) {
      throw new PersistenceException(refusal);
    }
    super.remove(policy);
  }

  /**
   * Правило 3: конкретна стойност само за продукти с проследяване — свой шев (чете базата), за да
   * може тестът на пазача да мине без нея. Пита се само когато политиката ползва конкретна
   * стойност.
   */
  protected String trackingRefusal(BgStockCostingPolicy policy) {
    return BgStockCostingPolicyService.usesSpecific(policy)
        ? Beans.get(BgStockCostingPolicyService.class).trackingRefusal(policy)
        : null;
  }

  /** Предложението на Nyxa — свой шев, за да може тестът да провери пазача без база. */
  protected void fillDefaults(BgStockCostingPolicy policy) {
    Beans.get(BgStockCostingPolicyService.class).fillDefaultRules(policy);
  }

  /** Самият запис — свой шев, за да може тестът да провери пазача без JPA. */
  protected BgStockCostingPolicy saveRecord(BgStockCostingPolicy policy) {
    return super.save(policy);
  }
}

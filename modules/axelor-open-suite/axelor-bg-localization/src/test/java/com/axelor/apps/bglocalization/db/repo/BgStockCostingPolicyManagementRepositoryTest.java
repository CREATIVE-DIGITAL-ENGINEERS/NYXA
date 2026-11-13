package com.axelor.apps.bglocalization.db.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.bglocalization.db.BgStockCostingAccountRule;
import com.axelor.apps.bglocalization.db.BgStockCostingPolicy;
import com.axelor.apps.bglocalization.service.stock.BgStockCostingRules;
import javax.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Пазачът на политиката при запис и триене — всеки път към базата минава оттук. */
class BgStockCostingPolicyManagementRepositoryTest {

  /** Без JPA: записът и предложението на Nyxa се броят, не се изпълняват. */
  static class Repo extends BgStockCostingPolicyManagementRepository {
    int saved;
    int filled;

    @Override
    protected BgStockCostingPolicy saveRecord(BgStockCostingPolicy policy) {
      saved++;
      return policy;
    }

    @Override
    protected void fillDefaults(BgStockCostingPolicy policy) {
      filled++;
    }
  }

  private static BgStockCostingPolicy policy(String framework, String method) {
    Company company = new Company();
    company.setId(1L);
    company.setName("Склад ЕООД");
    company.setBgAccountingFramework(framework);
    Year year = new Year();
    year.setName("2027");
    year.setTypeSelect(YearRepository.TYPE_FISCAL);
    year.setCompany(company);
    BgStockCostingPolicy p = new BgStockCostingPolicy();
    p.setCompany(company);
    p.setYear(year);
    p.setCostingMethodSelect(method);
    p.setAverageScopeSelect(BgStockCostingRules.SCOPE_WAREHOUSE);
    p.setPostingModeSelect(BgStockCostingRules.POSTING_CURRENT);
    p.setStatusSelect(BgStockCostingPolicyRepository.STATUS_DRAFT);
    return p;
  }

  @Test
  @DisplayName("LIFO при МСФО не се записва — нито през екрана, нито през REST")
  void lifo_при_мсфо_не_се_записва() {
    Repo repo = new Repo();
    PersistenceException e =
        assertThrows(
            PersistenceException.class,
            () -> repo.save(policy(BgStockCostingRules.IFRS, BgStockCostingRules.LIFO)));
    assertTrue(e.getMessage().contains("МСФО"), e.getMessage());
    assertEquals(0, repo.saved);
  }

  @Test
  @DisplayName("смяна на метода в активна политика не се записва")
  void смяна_в_активна_не_се_записва() {
    Repo repo = new Repo();
    BgStockCostingPolicy p = policy(BgStockCostingRules.NSS, BgStockCostingRules.FIFO);
    p.setId(5L);
    p.setStatusSelect(BgStockCostingPolicyRepository.STATUS_ACTIVE);
    p.setLockedSignature(BgStockCostingRules.signature(p));
    p.setCostingMethodSelect(BgStockCostingRules.WAP_PERPETUAL);
    assertThrows(PersistenceException.class, () -> repo.save(p));
    assertEquals(0, repo.saved);
  }

  @Test
  @DisplayName("активна политика не се трие")
  void активна_не_се_трие() {
    Repo repo = new Repo();
    BgStockCostingPolicy p = policy(BgStockCostingRules.NSS, BgStockCostingRules.FIFO);
    p.setStatusSelect(BgStockCostingPolicyRepository.STATUS_ACTIVE);
    assertThrows(PersistenceException.class, () -> repo.remove(p));
  }

  @Test
  @DisplayName("новата политика без сметки получава предложението; попълнената — не")
  void предложението_само_за_празна_нова() {
    Repo repo = new Repo();
    BgStockCostingPolicy fresh = policy(BgStockCostingRules.NSS, BgStockCostingRules.FIFO);
    assertSame(fresh, repo.save(fresh));
    assertEquals(1, repo.filled);

    BgStockCostingPolicy withRules = policy(BgStockCostingRules.NSS, BgStockCostingRules.FIFO);
    BgStockCostingAccountRule rule = new BgStockCostingAccountRule();
    rule.setMovementCode("30");
    rule.setInventoryType("30");
    withRules.addAccountRuleListItem(rule);
    repo.save(withRules);
    assertEquals(1, repo.filled, "попълнените сметки са избор на дружеството");

    BgStockCostingPolicy existing = policy(BgStockCostingRules.NSS, BgStockCostingRules.FIFO);
    existing.setId(9L);
    repo.save(existing);
    assertEquals(1, repo.filled, "заварената не се допълва сама — има бутон");
    assertEquals(3, repo.saved);
  }
}

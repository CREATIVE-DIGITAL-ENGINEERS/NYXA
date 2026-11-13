package com.axelor.apps.bglocalization.db.repo;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Правилото на пазача „болничен само по договор на активното дружество“. Пътят през repository-то и
 * базата — {@code BgTrzVisibleDefectsPostgresIntegrationTest}.
 */
class BgSickLeaveCompanyRefusalTest {

  private static Company company(long id, String name) {
    Company c = new Company();
    c.setId(id);
    c.setName(name);
    return c;
  }

  @Test
  @DisplayName("Договор на друго дружество → отказ, който назовава и двете")
  void anotherCompanysContractIsRefused() {
    String refusal =
        BgSickLeaveManagementRepository.companyRefusal(
            company(2, "Демо Холдинг АД"), company(1, "Демо Търговия ООД"));
    assertNotNull(refusal);
    assertTrue(refusal.contains("Демо Холдинг АД") && refusal.contains("Демо Търговия ООД"));
  }

  @Test
  @DisplayName("Договор на активното дружество → без отказ")
  void theActiveCompanysContractPasses() {
    assertNull(
        BgSickLeaveManagementRepository.companyRefusal(
            company(1, "Демо Търговия ООД"), company(1, "Демо Търговия ООД")));
  }

  @Test
  @DisplayName("Договор без дружество при активно дружество → отказ")
  void aContractWithoutCompanyIsRefused() {
    assertNotNull(
        BgSickLeaveManagementRepository.companyRefusal(null, company(1, "Демо Търговия ООД")));
  }
}

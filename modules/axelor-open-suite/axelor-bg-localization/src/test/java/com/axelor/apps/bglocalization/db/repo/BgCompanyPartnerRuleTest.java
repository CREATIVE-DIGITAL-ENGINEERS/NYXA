package com.axelor.apps.bglocalization.db.repo;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Партньорът на дружеството е в самото дружество — правилото без база. */
class BgCompanyPartnerRuleTest {

  private static Company company(Long id) {
    Company c = new Company();
    c.setId(id);
    c.setCode("C" + id);
    return c;
  }

  private static Partner partner(Long id, Company owner) {
    Partner p = new Partner();
    p.setId(id);
    p.setCompany(owner);
    return p;
  }

  @Test
  @DisplayName("свой партньор и празно — минават; чужд и системен — отказ")
  void правилото() {
    Company a = company(1L);
    Company b = company(2L);
    assertNull(BgCompanyManagementRepository.companyPartnerRefusal(a, false), "без партньор");

    a.setPartner(partner(10L, a));
    assertNull(BgCompanyManagementRepository.companyPartnerRefusal(a, false), "свой");

    a.setPartner(partner(11L, b));
    assertNotNull(BgCompanyManagementRepository.companyPartnerRefusal(a, false), "на Б");

    Partner system = partner(12L, null);
    system.setNxSystem(true);
    a.setPartner(system);
    assertNotNull(BgCompanyManagementRepository.companyPartnerRefusal(a, false), "системен");

    a.setPartner(partner(13L, null));
    assertNull(
        BgCompanyManagementRepository.companyPartnerRefusal(a, false),
        "заварено дружество със заварен партньор — непроменено");
    assertNotNull(
        BgCompanyManagementRepository.companyPartnerRefusal(a, true),
        "ново дружество не взема заварен партньор");
  }

  @Test
  @DisplayName("ново дружество: само нов партньор (за него) или нищо")
  void ново_дружество() {
    Company fresh = company(null);
    fresh.setPartner(partner(10L, company(1L)));
    assertNotNull(BgCompanyManagementRepository.companyPartnerRefusal(fresh, true), "чужд");

    Partner pending = partner(null, null);
    fresh.setPartner(pending);
    assertNull(
        BgCompanyManagementRepository.companyPartnerRefusal(fresh, true), "нов, без дружество");
    assertSame(pending, BgCompanyManagementRepository.detachNewPartner(fresh), "откача се");
    assertNull(fresh.getPartner(), "записва се след дружеството");

    fresh.setPartner(partner(10L, null));
    assertNull(BgCompanyManagementRepository.detachNewPartner(fresh), "записан не се откача");
  }
}

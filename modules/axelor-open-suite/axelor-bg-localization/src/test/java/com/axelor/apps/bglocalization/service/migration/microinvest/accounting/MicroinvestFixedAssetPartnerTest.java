package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import org.junit.jupiter.api.Test;

/** Database-free rules for the partner the stock FixedAsset requires on imported assets. */
class MicroinvestFixedAssetPartnerTest {

  private static Company company(Partner partner) {
    Company company = new Company();
    company.setCode("TESTFISH");
    company.setPartner(partner);
    return company;
  }

  private static Partner partner(String name) {
    Partner partner = new Partner();
    partner.setName(name);
    return partner;
  }

  @Test
  void sourceSupplierWins() {
    Partner supplier = partner("Доставчик");
    Partner owner = partner("Собственик");
    assertSame(
        supplier, MicroinvestFixedAssetImportService.resolvePartner(supplier, company(owner), 17));
  }

  @Test
  void withoutSourceSupplierTheCompanyPartnerIsUsed() {
    Partner owner = partner("Собственик");
    assertSame(owner, MicroinvestFixedAssetImportService.resolvePartner(null, company(owner), 17));
  }

  @Test
  void withoutSourceSupplierAndWithoutCompanyPartnerTheYearIsAborted() {
    MicroinvestAccountingAbortException abort =
        assertThrows(
            MicroinvestAccountingAbortException.class,
            () -> MicroinvestFixedAssetImportService.resolvePartner(null, company(null), 17));
    assertTrue(abort.getMessage().contains("актив 17"), abort.getMessage());
    assertTrue(abort.getMessage().contains("Company.partner"), abort.getMessage());
  }
}

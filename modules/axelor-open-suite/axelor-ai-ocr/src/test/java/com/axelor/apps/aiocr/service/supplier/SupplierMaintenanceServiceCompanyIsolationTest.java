package com.axelor.apps.aiocr.service.supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.record.OcrBlocked;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Companies are isolated — suppliers are never shared between them, confirmed by the accountant —
 * so company 4 already holding an external supplier for an ЕИК must never stop company 5 creating
 * its own, separate one. These run with no Guice injector and no database: {@link
 * SupplierMaintenanceService#companyId()} and {@link SupplierMaintenanceService#matchEikForCompany}
 * are the two seams {@code assertNoDuplicateRegistrationCode} and {@code findScopedByEik} read
 * instead of {@code AiCompanyScope} and {@code PartnerRepository}, and {@code isCompanyIdentity} is
 * overridden the same way — the same idiom {@code SupplierSearchServiceTest} uses for the sibling
 * search service.
 */
class SupplierMaintenanceServiceCompanyIsolationTest {

  private static SupplierMaintenanceService serviceWith(
      Long activeCompanyId,
      List<Partner> matchesInActiveCompanyScope,
      Set<Long> companyIdentityIds) {
    return new SupplierMaintenanceService(null) {
      @Override
      protected Long companyId() {
        return activeCompanyId;
      }

      @Override
      protected List<Partner> matchEikForCompany(String canonicalEik, Long companyId) {
        assertEquals(
            activeCompanyId, companyId, "must always ask for the ACTIVE company's own scope");
        return matchesInActiveCompanyScope;
      }

      @Override
      public boolean isCompanyIdentity(Partner partner) {
        return companyIdentityIds.contains(partner.getId());
      }
    };
  }

  private static Partner newPartner(String eik) {
    Partner partner = new Partner();
    partner.setRegistrationCode(eik);
    return partner;
  }

  private static Partner existingPartner(long id, String eik) {
    Partner partner = new Partner();
    partner.setId(id);
    partner.setFullName("Supplier " + id);
    partner.setRegistrationCode(eik);
    return partner;
  }

  // ── assertNoDuplicateRegistrationCode ────────────────────────────────────────────────────

  @Test
  void sameEikAlreadyHeldInAnotherCompanyDoesNotBlockCreatingHere() {
    // Company 4's own row for this ЕИК is simply never in what company 5's OWN scope-matching
    // query returns.
    SupplierMaintenanceService service =
        serviceWith(5L, Collections.emptyList(), Collections.emptySet());

    assertDoesNotThrow(() -> service.assertNoDuplicateRegistrationCode(newPartner("130159785")));
  }

  @Test
  void sameEikAlreadyInThisCompanysOwnScopeIsRefused() {
    Partner existing = existingPartner(164, "130159785");
    SupplierMaintenanceService service = serviceWith(5L, List.of(existing), Collections.emptySet());

    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.assertNoDuplicateRegistrationCode(newPartner("130159785")));
    assertTrue(ex.getMessage().contains("130159785"));
  }

  @Test
  void updatingTheMatchedPartnerItselfIsNotADuplicateOfItself() {
    Partner existing = existingPartner(164, "130159785");
    SupplierMaintenanceService service = serviceWith(5L, List.of(existing), Collections.emptySet());

    assertDoesNotThrow(
        () -> service.assertNoDuplicateRegistrationCode(existingPartner(164, "130159785")));
  }

  @Test
  void aCompanysOwnIdentityPartnerNeverBlocksOrCountsAsADuplicate() {
    Partner companyIdentity = existingPartner(163, "100000065");
    SupplierMaintenanceService service = serviceWith(5L, List.of(companyIdentity), Set.of(163L));

    assertDoesNotThrow(() -> service.assertNoDuplicateRegistrationCode(newPartner("100000065")));
  }

  /**
   * The duplicate refusal names the accessible record by id and points at operation=update; it must
   * not claim that find_supplier would not show the existing partner.
   */
  @Test
  void aDuplicateRefusalGivesTheAccessibleExistingSupplierIdAndPointsAtUpdate() {
    Partner existing = existingPartner(164, "130159785");
    existing.setIsSupplier(Boolean.FALSE);
    existing.addCompanySetItem(company(5L));
    SupplierMaintenanceService service = duplicateService(existing, true);

    DuplicateSupplierException ex =
        assertThrows(
            DuplicateSupplierException.class,
            () -> service.assertNoDuplicateRegistrationCode(newPartner("130159785")));

    assertEquals(164L, ex.getExistingSupplierId());
    assertEquals(Boolean.FALSE, ex.getExistingIsSupplier());
    assertEquals("164", ex.details().get("existingSupplierId"));
    assertEquals("update", ex.details().get("repairOperation"));
    assertTrue(ex.getMessage().contains("operation=update"), ex.getMessage());
    assertFalse(ex.getMessage().contains("find_supplier will not show"), ex.getMessage());
  }

  /** Not readable by this caller: the duplicate is still refused, but nothing about it is shown. */
  @Test
  void aDuplicateTheCallerCannotReadIsRefusedWithoutDetails() {
    Partner existing = existingPartner(164, "130159785");
    SupplierMaintenanceService service = duplicateService(existing, false);

    DuplicateSupplierException ex =
        assertThrows(
            DuplicateSupplierException.class,
            () -> service.assertNoDuplicateRegistrationCode(newPartner("130159785")));

    assertNull(ex.getExistingSupplierId());
    assertTrue(ex.details().isEmpty());
    assertFalse(ex.getMessage().contains("164"), "no id or name of an unreadable record");
  }

  // ── shared partners ───────────────────────────────────────────────────────────────────────

  @Test
  void aPartnerSharedWithEveryCompanyOrAnotherOneIsRefusedForUpdate() {
    SupplierMaintenanceService service =
        serviceWith(5L, Collections.emptyList(), Collections.emptySet());
    Partner allCompanies = existingPartner(164, "130159785"); // empty companySet
    Partner twoCompanies = existingPartner(165, "130159785");
    twoCompanies.addCompanySetItem(company(5L));
    twoCompanies.addCompanySetItem(company(4L));

    OcrBlocked empty =
        assertThrows(
            OcrBlocked.class, () -> service.assertOwnedOnlyByActiveCompany(allCompanies, 164));
    assertThrows(OcrBlocked.class, () -> service.assertOwnedOnlyByActiveCompany(twoCompanies, 165));
    assertTrue(empty.getMessage().contains("Nothing was changed"), empty.getMessage());
    assertTrue(
        allCompanies.getCompanySet() == null || allCompanies.getCompanySet().isEmpty(),
        "refusing must never auto-link the partner");
  }

  @Test
  void aPartnerOfTheActiveCompanyAloneMayBeUpdated() {
    SupplierMaintenanceService service =
        serviceWith(5L, Collections.emptyList(), Collections.emptySet());
    Partner own = existingPartner(164, "130159785");
    own.addCompanySetItem(company(5L));

    assertDoesNotThrow(() -> service.assertOwnedOnlyByActiveCompany(own, 164));
  }

  /** Company 5, one matching partner, nobody's identity, and a fixed answer to "may I read it". */
  private static SupplierMaintenanceService duplicateService(Partner existing, boolean readable) {
    return new SupplierMaintenanceService(null) {
      @Override
      protected Long companyId() {
        return 5L;
      }

      @Override
      protected List<Partner> matchEikForCompany(String canonicalEik, Long companyId) {
        return List.of(existing);
      }

      @Override
      public boolean isCompanyIdentity(Partner partner) {
        return false;
      }

      @Override
      protected boolean canRead(Partner partner) {
        return readable;
      }
    };
  }

  private static Company company(long id) {
    Company company = new Company();
    company.setId(id);
    return company;
  }

  // ── findScopedByEik ───────────────────────────────────────────────────────────────────────

  /**
   * The exact bug a company-oblivious {@code findScopedByEik} would reintroduce the moment two
   * companies are allowed the same ЕИК: a query with no company filter would see both rows, read
   * two as "ambiguous" and answer null even for the company whose own create just succeeded.
   */
  @Test
  void findScopedByEikFindsThisCompanysOwnMatchRegardlessOfAnotherCompanysRow() {
    Partner existing = existingPartner(164, "130159785");
    SupplierMaintenanceService service = serviceWith(5L, List.of(existing), Collections.emptySet());

    assertEquals(existing, service.findScopedByEik("130159785"));
  }

  @Test
  void findScopedByEikReturnsNullWhenNothingMatchesInThisCompanysOwnScope() {
    // Emulates company 4 holding the ЕИК: that row never appears in what company 5's own
    // scope-matching query returns, so this reads exactly as "not found" — never as somebody
    // else's answer.
    SupplierMaintenanceService service =
        serviceWith(5L, Collections.emptyList(), Collections.emptySet());

    assertNull(service.findScopedByEik("130159785"));
  }

  @Test
  void findScopedByEikNeverReturnsACompanysOwnIdentityPartner() {
    Partner companyIdentity = existingPartner(163, "100000065");
    SupplierMaintenanceService service = serviceWith(5L, List.of(companyIdentity), Set.of(163L));

    assertNull(service.findScopedByEik("100000065"));
  }
}

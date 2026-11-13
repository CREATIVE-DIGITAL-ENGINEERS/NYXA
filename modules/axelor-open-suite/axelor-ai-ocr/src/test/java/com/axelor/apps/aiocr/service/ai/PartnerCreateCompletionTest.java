package com.axelor.apps.aiocr.service.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.context.AiCompletionRegistry;
import com.axelor.apps.aiaccountant.context.AiCompletionResolver;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiocr.service.supplier.SupplierMaintenanceService;
import com.axelor.apps.base.db.Partner;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link PartnerCreateCompletion}, active (never {@code @Disabled}) and running with no Guice
 * injector and no database at all.
 *
 * <h2>What this proves, and what it does not</h2>
 *
 * These tests exercise {@link PartnerCreateCompletion}'s own decision logic — parsing the target,
 * gating on the active company scope, and reading whatever {@link #supplierMaintenanceService()}
 * (its one seam) answers — against a small fake standing in for {@link SupplierMaintenanceService}.
 * The fake mimics that service's real, documented contract (a company-scoped ЕИК lookup) with
 * canned data; it does not run {@code findScopedByEik}'s own JPQL, and none of {@code
 * SupplierMaintenanceService}'s real lookup/create/edit logic is touched, changed, or exercised
 * here. Whether the REAL {@code findScopedByEik} — a plain, unmodified query against {@code
 * PartnerRepository} — resolves correctly against a genuine PostgreSQL database is production
 * behaviour this suite does not, and cannot, verify; that would need a live or Postgres-backed
 * integration run, which is out of scope for this pass.
 */
class PartnerCreateCompletionTest {

  private final PartnerCreateCompletion completion = new PartnerCreateCompletion();

  @Test
  void notThisResolversRefIsNull() {
    assertNull(completion.isComplete("Partner#12", "CREATE_SUPPLIER_INVOICE"));
    assertNull(completion.isComplete("AiOcrUpload#18", "CREATE_SUPPLIER_INVOICE"));
    assertNull(completion.isComplete(null, "CREATE_SUPPLIER_INVOICE"));
  }

  @Test
  void aMalformedPartnerCreateRefIsAlsoNull() {
    assertNull(
        completion.isComplete("PartnerCreate:notanumber:203970390", "CREATE_SUPPLIER_INVOICE"));
  }

  /**
   * A fake standing in for {@link SupplierMaintenanceService}, never touching its real lookup
   * logic: it answers from the one (ЕИК, owning company) pair it was built with, applying the same
   * two facts the real {@code findScopedByEik} + {@code AiCompanyScope.partnerInScope} combination
   * does — a match on the normalised ЕИК, and visibility from the company currently in scope.
   */
  private static SupplierMaintenanceService fakeLookup(String knownEik, Long ownerCompanyId) {
    return new SupplierMaintenanceService(null) {
      @Override
      public Partner findScopedByEik(String eik) {
        if (!knownEik.equals(eik)) {
          return null;
        }
        if (!AiCompanyScope.ownsId(ownerCompanyId)) {
          return null;
        }
        Partner partner = new Partner();
        partner.setRegistrationCode(eik);
        return partner;
      }
    };
  }

  private static PartnerCreateCompletion completionWith(SupplierMaintenanceService lookup) {
    return new PartnerCreateCompletion() {
      @Override
      protected SupplierMaintenanceService supplierMaintenanceService() {
        return lookup;
      }
    };
  }

  @Test
  void sameCompanyAndNormalisedSameEikIsComplete() {
    PartnerCreateCompletion resolver = completionWith(fakeLookup("831257890", 2L));

    Boolean result =
        AiExecutionContext.callAsSystem(
            2L,
            "test",
            () -> resolver.isComplete("PartnerCreate:2:831257890", "CREATE_SUPPLIER_INVOICE"));

    assertTrue(Boolean.TRUE.equals(result));
  }

  @Test
  void differentEikIsNotComplete() {
    PartnerCreateCompletion resolver = completionWith(fakeLookup("831257890", 2L));

    Boolean result =
        AiExecutionContext.callAsSystem(
            2L,
            "test",
            () -> resolver.isComplete("PartnerCreate:2:999999999", "CREATE_SUPPLIER_INVOICE"));

    assertFalse(Boolean.TRUE.equals(result));
  }

  /**
   * The same ЕИК exists, but the fake only recognises it as owned by company 2 — asking about it as
   * company 3's target (both in the ref and in the active scope) must not read that other company's
   * supplier as completing it.
   */
  @Test
  void sameEikButAnotherCompanysSupplierIsNotComplete() {
    PartnerCreateCompletion resolver = completionWith(fakeLookup("831257890", 2L));

    Boolean result =
        AiExecutionContext.callAsSystem(
            3L,
            "test",
            () -> resolver.isComplete("PartnerCreate:3:831257890", "CREATE_SUPPLIER_INVOICE"));

    assertFalse(Boolean.TRUE.equals(result));
  }

  /**
   * The resolver never even reaches the lookup for a target it does not own — the active scope
   * being a third company altogether must not matter, because {@code ownsId} already refused before
   * {@link #supplierMaintenanceService()} would have been asked anything.
   */
  @Test
  void aTargetForAnotherCompanyIsIgnoredRegardlessOfTheLookup() {
    PartnerCreateCompletion resolver =
        completionWith(
            new SupplierMaintenanceService(null) {
              @Override
              public Partner findScopedByEik(String eik) {
                throw new AssertionError(
                    "must not be asked about a target this company does not own");
              }
            });

    Boolean result =
        AiExecutionContext.callAsSystem(
            9L,
            "test",
            () -> resolver.isComplete("PartnerCreate:2:831257890", "CREATE_SUPPLIER_INVOICE"));

    assertNull(result);
  }

  /**
   * The completion registry contract, exercised with no Guice injector: {@link
   * AiCompletionRegistry#discover} is overridden the same way {@code AiTargetRegistry.discover()}
   * is elsewhere in this codebase's own tests, so this proves {@link PartnerCreateCompletion}
   * actually answers through {@code isComplete(ref, task)} the way the registry calls it — not only
   * when called directly.
   */
  @Test
  void answersThroughTheCompletionRegistryContract() {
    PartnerCreateCompletion resolver = completionWith(fakeLookup("831257890", 2L));
    AiCompletionRegistry registry =
        new AiCompletionRegistry() {
          @Override
          protected List<AiCompletionResolver> discover() {
            return Collections.singletonList(resolver);
          }
        };

    Boolean result =
        AiExecutionContext.callAsSystem(
            2L,
            "test",
            () -> registry.isComplete("PartnerCreate:2:831257890", "CREATE_SUPPLIER_INVOICE"));

    assertTrue(result);
  }
}

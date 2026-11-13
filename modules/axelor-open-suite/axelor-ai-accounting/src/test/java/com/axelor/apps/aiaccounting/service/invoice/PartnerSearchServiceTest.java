package com.axelor.apps.aiaccounting.service.invoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiaccountant.security.AiPartnerIdentity;
import com.axelor.apps.base.db.Partner;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What {@code find_customer} asks of the database — read at the seam, so no database is needed.
 *
 * <p>The exclusions have to be in the query itself: filtering rows afterwards would page over
 * records the caller is never offered and hide real matches behind them.
 */
class PartnerSearchServiceTest {

  /** Captures the filter and binds instead of running them. */
  private static final class Capturing extends PartnerSearchService {
    String filter;
    Map<String, Object> params;

    @Override
    protected List<Partner> fetch(String filter, Map<String, Object> params, Role role, int max) {
      this.filter = filter;
      this.params = params;
      return Collections.emptyList();
    }
  }

  private static Capturing search(PartnerSearchService.Criteria criteria) {
    Capturing service = new Capturing();
    AiExecutionContext.runAsSystem(1L, "test", () -> service.search(criteria));
    return service;
  }

  private static PartnerSearchService.Criteria customer() {
    PartnerSearchService.Criteria criteria = new PartnerSearchService.Criteria();
    criteria.role = PartnerSearchService.Role.CUSTOMER;
    return criteria;
  }

  @Test
  void findCustomerNeverReturnsArchivedPartnersOrAnyCompanysOwnIdentity() {
    PartnerSearchService.Criteria criteria = customer();
    criteria.name = "Демо";

    Capturing done = search(criteria);

    assertTrue(done.filter.contains(AiPartnerIdentity.notArchived("self")), done.filter);
    assertTrue(done.filter.contains(AiPartnerIdentity.notCompanyIdentity("self")), done.filter);
    assertTrue(done.filter.contains("self.isContact"), "contacts stay excluded: " + done.filter);
    assertEquals(1L, done.params.get("aiScopeCompanyId"), "always inside the active company");
  }

  @Test
  void aBgPrefixedEikIsNormalisedAndAlsoMatchesTheStoredVatNumber() {
    PartnerSearchService.Criteria criteria = customer();
    criteria.registrationCode = "BG 203 970 390";

    Capturing done = search(criteria);

    assertTrue(done.params.containsValue("203970390"), "canonical digits: " + done.params);
    assertTrue(done.filter.contains("self.registrationCode"), done.filter);
    assertTrue(done.filter.contains("self.taxNbr"), "an ЕИК must also match taxNbr");
  }

  @Test
  void aBgVatNumberAndItsEikAreOneKeyAndAForeignVatIsComparedAsStored() {
    PartnerSearchService.Criteria bg = customer();
    bg.registrationCode = "203970390";
    bg.taxNumber = "BG203970390";
    Capturing same = search(bg);
    assertEquals(
        1L,
        same.params.values().stream().filter("203970390"::equals).count(),
        "one key for both: " + same.params);

    PartnerSearchService.Criteria foreign = customer();
    foreign.taxNumber = "DE 123456789";
    Capturing done = search(foreign);
    assertEquals("DE123456789", done.params.get("foreignVat"));
    assertFalse(done.params.containsValue("123456789"), "a foreign VAT is not a BG ЕИК");
  }

  @Test
  void anImplausibleEikIsRefusedAndMessagesUseTheSchemasNames() {
    PartnerSearchService.Criteria wrong = customer();
    wrong.registrationCode = "12345";
    IllegalArgumentException implausible =
        assertThrows(IllegalArgumentException.class, () -> search(wrong));
    assertTrue(implausible.getMessage().contains("not a plausible ЕИК"));

    IllegalArgumentException empty =
        assertThrows(IllegalArgumentException.class, () -> search(customer()));
    for (String name : Arrays.asList("customerId", "eik", "vatNumber")) {
      assertTrue(empty.getMessage().contains(name), empty.getMessage());
    }
    assertFalse(empty.getMessage().contains("registrationCode"), empty.getMessage());

    PartnerSearchService.Criteria byId = customer();
    byId.partnerId = 7L;
    byId.registrationCode = "203970390";
    byId.taxNumber = "DE123456789";
    Capturing done = new Capturing();
    PartnerSearchService.SearchResult result =
        AiExecutionContext.callAsSystem(1L, "test", () -> done.search(byId));
    assertEquals(Arrays.asList("customerId", "eik", "vatNumber"), result.getApplied());
  }
}

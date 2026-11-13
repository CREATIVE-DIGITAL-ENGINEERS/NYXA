package com.axelor.apps.aiaccounting.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiaccountant.service.AiBlocked;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A move's partner comes from a model-supplied id, so the id grants nothing by itself.
 *
 * <p>Pure — no Guice, no JPA: the partner rows and the company's own identity are supplied through
 * the service's seams, and the scope is a system context for company {@link #OURS}. Every unusable
 * partner must be refused with exactly the answer a missing id gets.
 */
class MoveCreationServicePartnerTest {

  private static final long OURS = 1L;
  private static final long THEIRS = 2L;
  private static final long OWN_IDENTITY_ID = 900L;

  private final Map<Long, Partner> rows = new HashMap<>();

  private final MoveCreationService service =
      new MoveCreationService() {
        @Override
        protected Partner loadPartner(long partnerId) {
          return rows.get(partnerId);
        }

        @Override
        protected Long ownIdentityPartnerId() {
          return OWN_IDENTITY_ID;
        }
      };

  private static Company company(long id) {
    Company company = new Company();
    company.setId(id);
    return company;
  }

  private Partner partner(long id, Long companyId) {
    Partner partner = new Partner();
    partner.setId(id);
    Set<Company> companies = new HashSet<>();
    if (companyId != null) {
      companies.add(company(companyId));
    }
    partner.setCompanySet(companies);
    rows.put(id, partner);
    return partner;
  }

  private AiBlocked refusal(long partnerId) {
    return AiExecutionContext.callAsSystem(
        OURS, "test", () -> assertThrows(AiBlocked.class, () -> service.resolvePartner(partnerId)));
  }

  /** The not-found answer with the id swapped out, so different ids can be compared. */
  private static String shape(AiBlocked blocked, long partnerId) {
    return blocked.getMessage().replace(String.valueOf(partnerId), "<id>");
  }

  @Test
  void anotherCompanysPartnerIsRefusedExactlyLikeAMissingOne() {
    partner(20L, THEIRS);

    AiBlocked missing = refusal(10L);
    AiBlocked foreign = refusal(20L);

    assertEquals(AiBlockerCode.ACCOUNTING_REFERENCE_NOT_FOUND.wire(), missing.getCode());
    assertEquals(missing.getCode(), foreign.getCode());
    assertEquals(shape(missing, 10L), shape(foreign, 20L));
  }

  @Test
  void contactsArchivedPartnersAndTheCompanysOwnIdentityAreRefusedTheSameWay() {
    partner(30L, null).setIsContact(true);
    partner(31L, null).setArchived(true);
    partner(OWN_IDENTITY_ID, OURS);

    String expected = shape(refusal(10L), 10L);
    assertEquals(expected, shape(refusal(30L), 30L));
    assertEquals(expected, shape(refusal(31L), 31L));
    assertEquals(expected, shape(refusal(OWN_IDENTITY_ID), OWN_IDENTITY_ID));
  }

  @Test
  void aSharedOrOwnPartnerResolvesAndNoIdMeansNoPartner() {
    Partner shared = partner(40L, null);
    Partner ours = partner(41L, OURS);

    AiExecutionContext.runAsSystem(
        OURS,
        "test",
        () -> {
          assertSame(shared, service.resolvePartner(40L));
          assertSame(ours, service.resolvePartner(41L));
          assertNull(service.resolvePartner(null));
        });
  }
}

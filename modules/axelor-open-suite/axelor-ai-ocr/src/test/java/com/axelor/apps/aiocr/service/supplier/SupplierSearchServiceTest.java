package com.axelor.apps.aiocr.service.supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.base.db.Partner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code find_supplier} answers about the ACTIVE company only.
 *
 * <p>The defect this covers: a zero-match search used to run a second, unscoped query keeping only
 * the ЕИК/VAT conditions, so an ЕИК + mismatched name search found this company's OWN partner and
 * reported it as "exists in another company" — and, when it really was another company's, it
 * disclosed that record's existence. There is now exactly one query, always company-scoped.
 *
 * <p>Runs with no Guice injector and no database: {@link #companyId()} and {@link #runQuery} are
 * the two seams {@code search} reads instead of {@code AiCompanyScope} and {@code
 * PartnerRepository}, the same idiom {@code SupplierMessageTargetResolverTest} uses for the sibling
 * resolver.
 */
class SupplierSearchServiceTest {

  /** Answers every query with the same canned rows and records what was asked. */
  private static final class FakeSearchService extends SupplierSearchService {

    private final List<Partner> rows;
    private final List<List<String>> conditionsSeen = new ArrayList<>();

    FakeSearchService(List<Partner> rows) {
      this.rows = rows;
    }

    @Override
    protected Long companyId() {
      return 5L;
    }

    @Override
    protected List<Partner> runQuery(
        List<String> conditions, Map<String, Object> binds, int limit) {
      conditionsSeen.add(new ArrayList<>(conditions));
      return rows;
    }
  }

  /**
   * ЕИК + a name that does not match this company's own partner: the scoped query finds nothing,
   * and nothing else runs — no unscoped existence check, so nothing can be reported as "elsewhere".
   */
  @Test
  void anEikAndMismatchedNameSearchRunsOnlyTheCompanyScopedQuery() {
    FakeSearchService service = new FakeSearchService(Collections.emptyList());

    SupplierSearchService.SearchResult result =
        service.search(
            new SupplierSearchService.Criteria().setEik("130159785").setName("Wrong Name Ltd"));

    assertTrue(result.getMatches().isEmpty());
    assertEquals(1, service.conditionsSeen.size(), "no second, unscoped query may run");
    List<String> only = service.conditionsSeen.get(0);
    assertTrue(
        only.contains(AiCompanyScope.partnerFilter("self")),
        "the one query must carry the company scope: " + only);
    assertTrue(
        only.contains("NOT EXISTS (SELECT 1 FROM Company c WHERE c.partner = self)"),
        "a company's own identity Partner is never a supplier: " + only);
  }
}

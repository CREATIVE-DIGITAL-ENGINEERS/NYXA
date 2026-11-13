package com.axelor.apps.aiocr.service.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.supplier.SupplierSearchService;
import com.axelor.apps.base.db.Partner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A zero-match {@code find_supplier} reply speaks about THIS company only: it never says the code
 * exists in another company, and after an ЕИК + name miss it sends the model back to an ЕИК-only
 * search rather than straight into a create that would hit the duplicate refusal.
 */
class FindSupplierToolTest {

  /** Runs {@link SupplierSearchService#search} for real, with no Guice injector or database. */
  private static final class FakeSearchService extends SupplierSearchService {

    private final List<Partner> rows;

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
      return rows;
    }
  }

  private static FindSupplierTool toolWith(SupplierSearchService service) {
    return new FindSupplierTool() {
      @Override
      protected SupplierSearchService searchService() {
        return service;
      }

      @Override
      protected void requireReadPermission() {
        // no permission engine without an injector
      }
    };
  }

  @Test
  void anEikAndNameMissNeverClaimsTheCodeExistsElsewhere() throws Exception {
    FindSupplierTool tool = toolWith(new FakeSearchService(Collections.emptyList()));
    ObjectNode args =
        new ObjectMapper().createObjectNode().put("eik", "130159785").put("name", "Wrong Ltd");

    @SuppressWarnings("unchecked")
    Map<String, Object> out = (Map<String, Object>) tool.execute(args);

    assertEquals(0, out.get("matchCount"));
    assertFalse(out.containsKey("existsOutsideCompanyScope"));
    String action = ((String) out.get("action")).toLowerCase();
    assertFalse(action.contains("another company") || action.contains("different company"));
    assertTrue(action.contains("еик alone"), "must re-search by the ЕИК alone first: " + action);
  }

  @Test
  void aGenuinelyUnknownCodeInvitesTheCreateWithoutAskingForAgreementAgain() throws Exception {
    FindSupplierTool tool = toolWith(new FakeSearchService(Collections.emptyList()));

    @SuppressWarnings("unchecked")
    Map<String, Object> out =
        (Map<String, Object>)
            tool.execute(new ObjectMapper().createObjectNode().put("eik", "999999999"));

    String action = (String) out.get("action");
    assertTrue(action.contains("manage_supplier (operation=create)"), action);
    assertFalse(action.contains("accepted your offer"), action);
  }
}

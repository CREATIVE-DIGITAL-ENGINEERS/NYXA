package com.axelor.apps.aiaccountant.route;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.aiaccountant.prompt.AiTask;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * {@link AiRouteAdapter#scope} precedence: an explicit named reference outranks a resumable
 * continuation — see the method javadoc. Before this fix, {@code resumable} was checked first, so a
 * message naming a record other than the one being resumed still read as {@code CONVERSATION}.
 */
class AiRouteAdapterTest {

  @Test
  void anExplicitReferenceOutranksAResumableContinuation() {
    assertEquals(
        AiScope.NAMED_RECORDS,
        AiRouteAdapter.scope("продължи", null, true, Collections.singletonList("Invoice#900")));
  }

  /**
   * On a supplier's own form a change request stays a change — it used to drop to GENERAL, which
   * opened no job and left the turn without the supplier playbook.
   */
  @Test
  void aChangeOnTheSupplierFormIsSupplierMaintenance() {
    assertEquals(
        AiTask.MAINTAIN_SUPPLIER,
        AiRouteAdapter.writeTaskFor(AiDomain.PARTNER, AiTask.REPAIR_SUPPLIER_INVOICE));
    assertEquals(AiTask.REVIEW_SUPPLIER, AiRouteAdapter.reviewTaskFor(AiDomain.PARTNER));
    assertEquals(
        AiTask.REPAIR_SUPPLIER_INVOICE,
        AiRouteAdapter.writeTaskFor(AiDomain.SUPPLIER_INVOICE, AiTask.MAINTAIN_SUPPLIER));
    assertEquals(
        AiTask.GENERAL,
        AiRouteAdapter.writeTaskFor(AiDomain.JOURNAL_ENTRY, AiTask.REPAIR_SUPPLIER_INVOICE));
  }

  @Test
  void aResumableContinuationStillWinsWithNoExplicitReference() {
    assertEquals(
        AiScope.CONVERSATION,
        AiRouteAdapter.scope("продължи", null, true, Collections.emptyList()));
  }

  /**
   * A complete, self-contained create instruction — name, ЕИК, phone all in one sentence — must
   * resolve EXPLICIT on its own words, with no job on record at all. This is the fact {@code
   * AiWriteAuthorisation.checkAuthorisation} needs to run the write in this one turn, with no
   * confirmation round trip: {@code EXPLICIT} is the only explicitness that passes it outright.
   */
  @Test
  void aCompleteCreateInstructionIsExplicitWithNoJobOnRecord() {
    AiRoute route =
        AiRouteAdapter.resolve(
            "Направи доставчик с ЕИК 130159785 телефон +359 2 995 0534 и име SAP BULGARIA",
            null,
            null,
            null);

    assertEquals(AiExplicitness.EXPLICIT, route.explicitness());
    assertEquals(AiTask.MAINTAIN_SUPPLIER, route.task());
  }
}

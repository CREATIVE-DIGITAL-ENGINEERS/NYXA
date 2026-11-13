package com.axelor.apps.aiaccountant.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.dto.PageContext;
import com.axelor.apps.aiaccountant.prompt.AiTask;
import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.route.AiExplicitness;
import com.axelor.apps.aiaccountant.route.AiRoute;
import com.axelor.apps.aiaccountant.route.AiScope;
import com.axelor.apps.aiaccountant.route.AiWriteOperation;
import com.axelor.apps.aiaccountant.service.AiWorkflowIntent;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * {@link AiRouteSelector#clamp} no longer caps a ROUTE verdict's explicitness at the adapter's own
 * reading — see the class javadoc. A validated router route is used exactly as the router returned
 * it, even when the adapter's lexical fallback read the same message as unwritten/GENERAL.
 */
class AiRouteSelectorTest {

  @Test
  void routerExplicitnessIsNotCappedByTheAdapter() {
    AiRoute routerRoute =
        new AiRoute(
            AiDomain.SUPPLIER_INVOICE,
            AiTask.CREATE_SUPPLIER_INVOICE,
            AiWorkflowIntent.Stage.DRAFT,
            AiScope.NAMED_RECORDS,
            AiExplicitness.EXPLICIT);
    AiRoute adapterFallback =
        new AiRoute(
            AiDomain.GENERAL, AiTask.GENERAL, null, AiScope.UNSCOPED, AiExplicitness.IMPLIED);

    assertSame(routerRoute, AiRouteSelector.clamp(routerRoute, adapterFallback));
  }

  private static AiWorkflow activeJobOn(String target) {
    AiWorkflow workflow = new AiWorkflow();
    workflow.setGoal("POSTED");
    workflow.addTarget(target);
    workflow.setAuthorisedTargets(Collections.singletonList(target));
    return workflow;
  }

  /**
   * An active, closed-target server workflow is authoritative on its own — the page having moved on
   * to a different record is not, by itself, evidence the continuation is about something else. See
   * {@link AiWorkflowController#open}, which no longer treats page navigation alone as ending a job
   * either.
   */
  @Test
  void anActiveWorkflowVerifiesContinuationRegardlessOfThePage() {
    AiWorkflow preTurn = activeJobOn("Invoice#606");
    PageContext page = new PageContext();
    page.setModel("com.axelor.apps.account.db.Invoice");
    page.setRecordId(900L);

    AiRouteSelector selector = new AiRouteSelector(null, null);
    assertTrue(selector.verifiedTarget(page, preTurn, null, AiRoute.unknown()));
  }

  /**
   * An explicit reference this turn is the one thing that may override a continuation — so it is
   * never verified as one, whatever the active workflow's own targets are.
   */
  @Test
  void anExplicitReferenceIsNeverVerifiedAsAContinuation() {
    AiWorkflow preTurn = activeJobOn("Invoice#606");
    AiRoute namedRecordsRoute =
        new AiRoute(
            AiDomain.SUPPLIER_INVOICE,
            AiTask.GENERAL,
            null,
            AiScope.NAMED_RECORDS,
            AiExplicitness.NONE);

    AiRouteSelector selector = new AiRouteSelector(null, null);
    assertFalse(selector.verifiedTarget(null, preTurn, null, namedRecordsRoute));
  }

  /**
   * Read A, open B, say «да»: the continuation is verified by A's job, so it is bound to that job —
   * rescoped to CONVERSATION whatever scope the router wrote — and never to the record on screen.
   */
  @Test
  void aJobVerifiedContinuationIsBoundToTheJobAndNotToTheOpenRecord() {
    AiWorkflow preTurn = activeJobOn("Invoice#606");
    PageContext page = new PageContext();
    page.setModel("com.axelor.apps.account.db.Invoice");
    page.setRecordId(900L);
    AiRoute continued =
        new AiRoute(
            AiDomain.SUPPLIER_INVOICE,
            AiTask.VALIDATE_SUPPLIER_INVOICE,
            AiWorkflowIntent.Stage.VALIDATED,
            AiScope.OPEN_RECORD,
            AiExplicitness.EXPLICIT);

    AiRouteSelector selector = new AiRouteSelector(null, null);
    AiRouteSelector.Binding binding =
        selector.continuationBinding(page, preTurn, null, AiRoute.unknown(), continued);
    AiRoute chosen = AiRouteSelector.clampContinuation(continued, AiScope.CONVERSATION);

    assertEquals(AiRouteSelector.Binding.JOB, binding);
    assertEquals(AiScope.CONVERSATION, chosen.scope(), "never the record on screen");
    assertEquals(AiExplicitness.INHERITED, chosen.explicitness(), "an approval only inherits");
  }

  /**
   * A bare «да» states nothing of its own: never EXPLICIT, and with no job it authorises nothing.
   */
  @Test
  void aBareAffirmationIsNeverExplicitAndInheritsOnlyAnActiveJob() {
    AiRoute routerSaysExplicit =
        new AiRoute(
            AiDomain.SUPPLIER_INVOICE,
            AiTask.VALIDATE_SUPPLIER_INVOICE,
            AiWorkflowIntent.Stage.VALIDATED,
            AiScope.OPEN_RECORD,
            AiExplicitness.EXPLICIT);

    AiRoute noJob = AiRouteSelector.clamp(routerSaysExplicit, AiRoute.unknown(), null, "да");
    AiRoute withJob =
        AiRouteSelector.clamp(
            routerSaysExplicit, AiRoute.unknown(), activeJobOn("Invoice#606"), "Да, ок");
    AiRoute ownChange =
        AiRouteSelector.clamp(routerSaysExplicit, AiRoute.unknown(), null, "да, и осчетоводи");

    assertEquals(AiExplicitness.IMPLIED, noJob.explicitness());
    assertEquals(AiExplicitness.INHERITED, withJob.explicitness());
    assertEquals(AiExplicitness.EXPLICIT, ownChange.explicitness(), "it states its own change");
  }

  private static AiRoute supplierRoute(AiScope scope, AiWriteOperation operation) {
    return new AiRoute(
        AiDomain.PARTNER,
        AiTask.MAINTAIN_SUPPLIER,
        null,
        scope,
        AiExplicitness.EXPLICIT,
        null,
        null,
        null,
        operation);
  }

  /**
   * The live case: «Създай доставчик ДЕМО ДОСТАВЧИК ЕООД, ЕИК 999999995, населено място София»
   * typed over Purchases → Suppliers with no row open. The list made it WORKLIST, and a WORKLIST
   * with no selection is refused as worklist_selection_unresolved. A creation is never a list
   * instruction; an update over the same list keeps its list scope, and a bare «да» still mints no
   * creation of its own.
   */
  @Test
  void aCreateOverAnOpenListIsNeverAWorklistInstruction() {
    String message = "Създай доставчик ДЕМО ДОСТАВЧИК ЕООД, ЕИК 999999995, населено място София";

    AiRoute create =
        AiRouteSelector.clamp(
            supplierRoute(AiScope.WORKLIST, AiWriteOperation.CREATE),
            AiRoute.unknown(),
            null,
            message);
    AiRoute update =
        AiRouteSelector.clamp(
            supplierRoute(AiScope.WORKLIST, AiWriteOperation.UPDATE),
            AiRoute.unknown(),
            null,
            "смени IBAN-а на доставчик с ЕИК 999999995");
    AiRoute bareYes =
        AiRouteSelector.clamp(
            supplierRoute(AiScope.WORKLIST, AiWriteOperation.CREATE),
            AiRoute.unknown(),
            null,
            "да");

    assertEquals(AiScope.UNSCOPED, create.scope());
    assertNull(create.selection());
    assertEquals(AiWriteOperation.CREATE, create.operation());
    assertEquals(AiExplicitness.EXPLICIT, create.explicitness());
    assertEquals(AiScope.WORKLIST, update.scope(), "only a creation leaves the list");
    assertEquals(AiExplicitness.IMPLIED, bareYes.explicitness(), "«да» authorises nothing");
  }

  /**
   * A request that cannot fit the router's envelope is never sent: the adapter's route is used, and
   * the router is not called at all.
   */
  @Test
  void anOverBudgetRouterRequestIsSkippedAndTheAdapterRouteUsed() {
    boolean[] called = {false};
    AiRouterService router =
        new AiRouterService(null, null) {
          @Override
          public AiRouterVerdict classify(String input, int timeoutSeconds) {
            called[0] = true;
            return AiRouterVerdict.failed("unexpected", 0L);
          }
        };
    AiRouteSelector selector =
        new AiRouteSelector(null, router) {
          @Override
          public boolean isActive() {
            return true;
          }

          @Override
          protected int routerBudgetSeconds() {
            return 10;
          }

          @Override
          protected int routerRequestTokenBudget() {
            return 500;
          }

          @Override
          protected int routerOutputReserveTokens() {
            return 100;
          }
        };
    AiRoute adapter =
        new AiRoute(AiDomain.GENERAL, AiTask.GENERAL, null, AiScope.UNSCOPED, AiExplicitness.NONE);

    AiRouteSelection selection =
        selector.select(
            "покажи последните фактури", null, null, 1L, adapter, null, Collections.emptyList(), 0);

    assertFalse(called[0], "the contract alone exceeds 400 tokens, so nothing is sent");
    assertSame(adapter, selection.route());
    assertEquals(AiRouterLog.USED_ADAPTER_FALLBACK, selection.used());
  }
}

package com.axelor.apps.aiaccountant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.context.AiCompletionRegistry;
import com.axelor.apps.aiaccountant.context.AiCompletionResolver;
import com.axelor.apps.aiaccountant.context.AiTargetIdentity;
import com.axelor.apps.aiaccountant.context.AiTargetRegistry;
import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.dto.PageContext;
import com.axelor.apps.aiaccountant.prompt.AiTask;
import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.route.AiExplicitness;
import com.axelor.apps.aiaccountant.route.AiRoute;
import com.axelor.apps.aiaccountant.route.AiScope;
import com.axelor.apps.aiaccountant.route.AiWriteOperation;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiaccountant.tool.AiTurnToolLog;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link AiWorkflowController#open} no longer treats page navigation alone as a reason to end an
 * active job — see {@link AiWorkflowController#matchesPrevious}. Only an explicit reference to a
 * different record does that; a continuation word or an ambient, no-instruction turn keeps the job
 * regardless of what the page currently shows.
 */
class AiWorkflowControllerTest {

  private static AiWorkflow jobOn(String target) {
    AiWorkflow workflow = new AiWorkflow();
    workflow.setGoal("POSTED");
    workflow.addTarget(target);
    workflow.setAuthorisedTargets(Collections.singletonList(target));
    return workflow;
  }

  private static PageContext pageOn(long recordId) {
    PageContext page = new PageContext();
    page.setModel("com.axelor.apps.account.db.Invoice");
    page.setRecordId(recordId);
    return page;
  }

  @Test
  void aContinuationResumesTheJobEvenWhenThePageHasMovedOn() {
    AiWorkflow previous = jobOn("Invoice#606");
    PageContext elsewhere = pageOn(900L);
    AiRoute route =
        new AiRoute(
            AiDomain.SUPPLIER_INVOICE, AiTask.GENERAL, null, AiScope.UNSCOPED, AiExplicitness.NONE);

    AiWorkflowController controller = new AiWorkflowController();
    AiWorkflow result =
        controller.open(
            "продължи", route, elsewhere, previous, 1L, "conv-1", Collections.emptyList());

    assertSame(previous, result);
  }

  @Test
  void anAmbientTurnKeepsTheJobEvenWhenThePageHasMovedOn() {
    AiWorkflow previous = jobOn("Invoice#606");
    PageContext elsewhere = pageOn(900L);
    AiRoute route =
        new AiRoute(
            AiDomain.SUPPLIER_INVOICE, AiTask.GENERAL, null, AiScope.UNSCOPED, AiExplicitness.NONE);

    AiWorkflowController controller = new AiWorkflowController();
    AiWorkflow result =
        controller.open(
            "какво остана?", route, elsewhere, previous, 1L, "conv-1", Collections.emptyList());

    assertSame(previous, result);
  }

  /**
   * A registry with no resolvers at all, so the comparison needs no running Guice injector — the
   * same idiom {@code AiRecordDomainRegistryTest} uses for its own registry.
   */
  private static AiTargetRegistry emptyRegistry() {
    return new AiTargetRegistry() {
      @Override
      protected List<AiTargetIdentity> discover() {
        return Collections.emptyList();
      }
    };
  }

  /**
   * An explicit reference to a different record is the one thing that ends the job here — the turn
   * itself asks for nothing (no goal, no write task), so what is correct is exactly "not this job",
   * not a freshly opened one either.
   */
  @Test
  void anExplicitReferenceToADifferentRecordEndsTheJob() {
    AiWorkflow previous = jobOn("Invoice#606");
    PageContext elsewhere = pageOn(900L);
    AiRoute route =
        new AiRoute(
            AiDomain.SUPPLIER_INVOICE,
            AiTask.GENERAL,
            null,
            AiScope.NAMED_RECORDS,
            AiExplicitness.NONE);

    AiWorkflowController controller =
        new AiWorkflowController() {
          @Override
          protected AiTargetRegistry targetRegistry() {
            return emptyRegistry();
          }
        };
    AiWorkflow result =
        controller.open(
            "оправи invoiceId=900",
            route,
            elsewhere,
            previous,
            1L,
            "conv-1",
            Collections.singletonList("Invoice#900"));

    assertNull(result, "no instruction was given about record 900 either, so nothing resumes");
  }

  /**
   * A write-authorising instruction, with the record established from the page rather than typed in
   * database-id syntax, opens a fresh job scoped to THAT record — never widened with, or confused
   * for, a stale job blocked on a different one. This is the "Upload#18 supersedes a blocked
   * Upload#11" case as it actually arises: a trusted read tool ({@code get_ocr_record}) establishes
   * which upload this turn is about, the same way the page establishes it here, without the user
   * ever typing {@code Upload#18} in the message itself.
   */
  @Test
  void aWriteAuthorisingInstructionOnADifferentOpenRecordOpensAFreshJobForIt() {
    AiWorkflow previous = jobOn("AiOcrUpload#11");
    previous.setWorkflowId("old-job-on-upload-11");
    PageContext upload18 = new PageContext();
    upload18.setModel("com.axelor.apps.aiocr.db.AiOcrUpload");
    upload18.setRecordId(18L);
    AiRoute route =
        new AiRoute(
            AiDomain.SUPPLIER_INVOICE,
            AiTask.CREATE_SUPPLIER_INVOICE,
            null,
            AiScope.OPEN_RECORD,
            AiExplicitness.EXPLICIT);

    AiWorkflowController controller = new AiWorkflowController();
    AiWorkflow result =
        controller.open("Създай доставчика 831257890", route, upload18, previous, 1L, "conv-1");

    assertNotNull(result, "a fresh, write-authorising instruction on the open record opens a job");
    assertNotEquals(previous.getWorkflowId(), result.getWorkflowId());
    assertEquals(Collections.singletonList("AiOcrUpload#18"), result.getAuthorisedTargets());
  }

  // ── markWorkDone: a same-turn retry must clear an earlier same-turn refusal ────────────────

  private static AiCompletionRegistry fixedCompletionRegistry(AiCompletionResolver resolver) {
    return new AiCompletionRegistry() {
      @Override
      public List<AiCompletionResolver> all() {
        return Collections.singletonList(resolver);
      }
    };
  }

  private static AiWorkflowController controllerCompletingEveryTargetAs(boolean complete) {
    return new AiWorkflowController() {
      @Override
      protected AiCompletionRegistry completionRegistry() {
        return fixedCompletionRegistry((ref, task) -> complete);
      }
    };
  }

  /**
   * {@code edit_supplier_invoice} refuses an IBAN not yet on the supplier, then a later call in the
   * SAME turn (after {@code manage_supplier} added it) retries and succeeds. {@code markWorkDone}
   * must not skip completion for a record already marked blocked, or the later success on that very
   * target never gets a chance to clear the earlier refusal and "Invoice#3" stays blocked even
   * though the job has, in fact, finished.
   */
  @Test
  void aSuccessfulRetryInTheSameTurnClearsTheEarlierRefusal() {
    AiWorkflow workflow = new AiWorkflow();
    workflow.setWriteJob(true);
    workflow.setTask("REPAIR_SUPPLIER_INVOICE");
    AiWorkflowController controller = controllerCompletingEveryTargetAs(true);

    AiTurnToolLog.begin();
    AiWriteEffects.begin();
    try {
      AiTurnToolLog.record(
          "edit_supplier_invoice",
          true,
          AiTurnToolLog.Status.FAILED,
          "Invoice#3",
          "bankIban is not registered on the supplier",
          "VALIDATION_ERROR");
      AiTurnToolLog.record(
          "edit_supplier_invoice", true, AiTurnToolLog.Status.OK, "Invoice#3", null, null);

      controller.update(workflow, null);
    } finally {
      AiWriteEffects.end();
      AiTurnToolLog.end();
    }

    assertTrue(workflow.getCompleted().contains("Invoice#3"));
    assertTrue(
        workflow.blocked().isEmpty(),
        "a later success on the same target must clear an earlier same-turn refusal");
  }

  /**
   * The other direction: nothing in the turn ever proved the target reached its goal, so it must
   * stay blocked — clearing a blocker takes a verified completion, not merely a later call
   * existing.
   */
  @Test
  void aTargetTheCompletionRegistryStillRefusesStaysBlocked() {
    AiWorkflow workflow = new AiWorkflow();
    workflow.setWriteJob(true);
    workflow.setTask("REPAIR_SUPPLIER_INVOICE");
    AiWorkflowController controller = controllerCompletingEveryTargetAs(false);

    AiTurnToolLog.begin();
    AiWriteEffects.begin();
    try {
      AiTurnToolLog.record(
          "edit_supplier_invoice",
          true,
          AiTurnToolLog.Status.FAILED,
          "Invoice#3",
          "bankIban is not registered on the supplier",
          "VALIDATION_ERROR");

      controller.update(workflow, null);
    } finally {
      AiWriteEffects.end();
      AiTurnToolLog.end();
    }

    assertFalse(workflow.getCompleted().contains("Invoice#3"));
    assertEquals(1, workflow.blocked().size());
  }

  /**
   * Completing one target is not licence to touch any other: a second, unrelated blocker on the
   * same job must survive exactly as {@code AiWorkflowStore.reconcileCompletedTarget}'s own tests
   * require of the stored row — this is the same rule inside the in-turn fold that feeds it.
   */
  @Test
  void completingOneTargetLeavesAnUnrelatedBlockerUntouched() {
    AiWorkflow workflow = new AiWorkflow();
    workflow.setWriteJob(true);
    workflow.setTask("REPAIR_SUPPLIER_INVOICE");
    AiWorkflowController controller =
        new AiWorkflowController() {
          @Override
          protected AiCompletionRegistry completionRegistry() {
            return fixedCompletionRegistry(
                (ref, task) -> "Invoice#3".equals(ref) ? Boolean.TRUE : Boolean.FALSE);
          }
        };

    AiTurnToolLog.begin();
    AiWriteEffects.begin();
    try {
      AiTurnToolLog.record(
          "edit_supplier_invoice",
          true,
          AiTurnToolLog.Status.FAILED,
          "Invoice#3",
          "bankIban is not registered on the supplier",
          "VALIDATION_ERROR");
      AiTurnToolLog.record(
          "edit_supplier_invoice", true, AiTurnToolLog.Status.OK, "Invoice#3", null, null);
      AiTurnToolLog.record(
          "edit_supplier_invoice",
          true,
          AiTurnToolLog.Status.FAILED,
          "Invoice#7",
          "duplicate number",
          "VALIDATION_ERROR");

      controller.update(workflow, null);
    } finally {
      AiWriteEffects.end();
      AiTurnToolLog.end();
    }

    assertTrue(workflow.getCompleted().contains("Invoice#3"));
    assertEquals(1, workflow.blocked().size());
    assertEquals("Invoice#7", workflow.blocked().get(0).getRef());
  }

  // ── settledAfterThisTurn: the iteration-cap notice's second, general route to "complete" ────

  /**
   * {@code AiTurnTruthCheck.assess} can only prove completion against a lifecycle destination, so a
   * goal-less repair job — which has no destination by design — could never satisfy it, however
   * cleanly it actually finished. This is the workflow's own structured fallback: fold this turn's
   * findings on a private copy and ask whether anything is still outstanding.
   */
  @Test
  void settledAfterThisTurnIsTrueOnceTheRetriedTargetIsProvenComplete() {
    AiWorkflow workflow = new AiWorkflow();
    workflow.setWriteJob(true);
    workflow.setTask("REPAIR_SUPPLIER_INVOICE");
    AiWorkflowController controller = controllerCompletingEveryTargetAs(true);

    AiTurnToolLog.begin();
    AiWriteEffects.begin();
    boolean settled;
    try {
      AiTurnToolLog.record(
          "edit_supplier_invoice", true, AiTurnToolLog.Status.OK, "Invoice#3", null, null);
      settled = controller.settledAfterThisTurn(workflow, null);
    } finally {
      AiWriteEffects.end();
      AiTurnToolLog.end();
    }

    assertTrue(settled);
    // Read-only: nothing was folded into, or persisted from, the live workflow the reply is still
    // about to be built from.
    assertTrue(workflow.getTargets().isEmpty());
  }

  @Test
  void settledAfterThisTurnIsFalseWithARealBlockerOutstanding() {
    AiWorkflow workflow = new AiWorkflow();
    workflow.setWriteJob(true);
    workflow.setTask("REPAIR_SUPPLIER_INVOICE");
    AiWorkflowController controller = controllerCompletingEveryTargetAs(false);

    AiTurnToolLog.begin();
    AiWriteEffects.begin();
    boolean settled;
    try {
      AiTurnToolLog.record(
          "edit_supplier_invoice",
          true,
          AiTurnToolLog.Status.FAILED,
          "Invoice#3",
          "bankIban is not registered on the supplier",
          "VALIDATION_ERROR");
      settled = controller.settledAfterThisTurn(workflow, null);
    } finally {
      AiWriteEffects.end();
      AiTurnToolLog.end();
    }

    assertFalse(settled, "a genuine blocker must keep the iteration-cap notice honest");
  }

  @Test
  void settledAfterThisTurnIsFalseForAReadOnlyJobOrNoJobAtAll() {
    AiWorkflow readOnly = new AiWorkflow();
    readOnly.setWriteJob(false);

    AiWorkflowController controller = new AiWorkflowController();
    assertFalse(controller.settledAfterThisTurn(readOnly, null));
    assertFalse(controller.settledAfterThisTurn(null, null));
  }

  // ── isAffectedRecordOnly: a bare Partner#id is never asked to prove itself ──────────────────

  /**
   * A completion registry shaped like real production after removing the unsound {@code
   * PartnerRecordCompletion}: it answers a well-formed {@code PartnerBankAccount:} reference — the
   * one this job could have been authorised against — and knows nothing about a bare {@code
   * Partner#id}.
   */
  private static AiWorkflowController controllerWithRealisticPartnerResolvers() {
    return new AiWorkflowController() {
      @Override
      protected AiCompletionRegistry completionRegistry() {
        return fixedCompletionRegistry(
            (ref, task) ->
                ref != null && ref.startsWith("PartnerBankAccount:") ? Boolean.TRUE : null);
      }
    };
  }

  /**
   * The regression this whole fix is about: {@code manage_supplier} refuses to add an IBAN (the
   * supplier already exists — that fact is unrelated to whether the write landed) and {@code
   * Partner#84} is marked blocked. Existence must never stand in for proof the specific edit
   * succeeded, so the blocker survives, and the iteration-cap warning must not be suppressed
   * either.
   */
  @Test
  void aFailedManageSupplierUpdateKeepsTheBlockerDespiteThePartnerExisting() {
    AiWorkflow workflow = new AiWorkflow();
    workflow.setWriteJob(true);
    workflow.setTask("REPAIR_SUPPLIER_INVOICE");
    AiWorkflowController controller = controllerWithRealisticPartnerResolvers();

    AiTurnToolLog.begin();
    AiWriteEffects.begin();
    boolean settled;
    try {
      AiTurnToolLog.record(
          "manage_supplier",
          true,
          AiTurnToolLog.Status.FAILED,
          "Partner#84",
          "This IBAN is already registered on a different partner.",
          "VALIDATION_ERROR");
      settled = controller.settledAfterThisTurn(workflow, null);
      controller.update(workflow, null);
    } finally {
      AiWriteEffects.end();
      AiTurnToolLog.end();
    }

    assertFalse(settled, "a genuine manage_supplier refusal must keep the limit warning honest");
    assertFalse(workflow.getCompleted().contains("Partner#84"));
    assertEquals(1, workflow.blocked().size());
    assertEquals("Partner#84", workflow.blocked().get(0).getRef());
  }

  /**
   * The sound replacement for existence-based proof: a job authorised against the exact typed
   * postcondition clears its blocker once — and only once — a later call in the same turn actually
   * lands the requested IBAN. {@code PartnerBankAccountCompletion} is exercised through this exact
   * ref shape in its own test; here the workflow's own retry-clears-a-blocker mechanism (preserved
   * from the earlier fix) is proven against it.
   */
  @Test
  void aSuccessfulRetryOnTheExactTypedBankAccountTargetClearsTheEarlierBlocker() {
    String target = "PartnerBankAccount:2:84:BG75UBBS80021029841118";
    AiWorkflow workflow = new AiWorkflow();
    workflow.setWriteJob(true);
    workflow.setTask("REPAIR_SUPPLIER_INVOICE");
    AiWorkflowController controller = controllerWithRealisticPartnerResolvers();

    AiTurnToolLog.begin();
    AiWriteEffects.begin();
    try {
      AiTurnToolLog.record(
          "manage_supplier",
          true,
          AiTurnToolLog.Status.FAILED,
          target,
          "This IBAN is already registered on a different partner.",
          "VALIDATION_ERROR");
      AiTurnToolLog.record("manage_supplier", true, AiTurnToolLog.Status.OK, target, null, null);

      controller.update(workflow, null);
    } finally {
      AiWriteEffects.end();
      AiTurnToolLog.end();
    }

    assertTrue(workflow.getCompleted().contains(target));
    assertTrue(workflow.blocked().isEmpty(), "the exact-IBAN proof must clear the earlier refusal");
  }

  /**
   * A bare {@code Partner#id} written successfully — the ordinary shape of a {@code
   * manage_supplier} call that landed — must never become a required-work target: it does not show
   * up as completed, blocked or remaining, and it must not be able to settle a job whose real
   * target ({@code Invoice#3}) is still blocked. Neither a supporting write stuck as outstanding
   * work forever ("Остават 1: Partner#84") nor existence treated as proof is acceptable, and both
   * are avoided the same way: the record is simply never tracked as something to complete.
   */
  @Test
  void aSuccessfulBarePartnerWriteIsNeitherTrackedNorAbleToSettleTheJob() {
    AiWorkflow workflow = new AiWorkflow();
    workflow.setWriteJob(true);
    workflow.setTask("REPAIR_SUPPLIER_INVOICE");
    AiWorkflowController controller = controllerWithRealisticPartnerResolvers();

    AiTurnToolLog.begin();
    AiWriteEffects.begin();
    boolean settled;
    try {
      AiTurnToolLog.record(
          "manage_supplier", true, AiTurnToolLog.Status.OK, "Partner#84", null, null);
      AiTurnToolLog.record(
          "edit_supplier_invoice",
          true,
          AiTurnToolLog.Status.FAILED,
          "Invoice#3",
          "bankIban is not registered on the supplier",
          "VALIDATION_ERROR");

      settled = controller.settledAfterThisTurn(workflow, null);
      controller.update(workflow, null);
    } finally {
      AiWriteEffects.end();
      AiTurnToolLog.end();
    }

    assertFalse(
        settled, "an unrelated successful Partner write must not settle a job left blocked");
    assertNull(workflow.find("Partner#84"), "a successful supporting write is never a job target");
    assertFalse(workflow.getCompleted().contains("Partner#84"));
    assertEquals(1, workflow.blocked().size());
    assertEquals("Invoice#3", workflow.blocked().get(0).getRef());
  }

  // ── Duplicate-ЕИК create: a terminal refusal must not leave an active job behind ───────────

  /**
   * A create refused as a duplicate ЕИК is a terminal, answered instruction, not unfinished work.
   * {@code PartnerCreate:<company>:<eik>} is the ONLY target such a job is ever authorised over —
   * see {@code SupplierMessageTargetResolver} — so once it is blocked there is nothing left to
   * attempt and the job must settle. Freezing the EXISTING partner's own ref into the job as well
   * would leave it "remaining" forever, since nothing ever touches it, and the job would never
   * settle.
   */
  @Test
  void aDuplicateEikCreateHasNothingLeftToAttemptAndSettles() {
    String target = "PartnerCreate:5:831642181";
    AiWorkflow workflow = new AiWorkflow();
    workflow.setWriteJob(true);
    workflow.setTask("MAINTAIN_SUPPLIER");
    workflow.setAuthorisedTargets(Collections.singletonList(target));
    workflow.addTarget(target);
    AiWorkflowController controller = controllerWithRealisticPartnerResolvers();

    AiTurnToolLog.begin();
    AiWriteEffects.begin();
    try {
      AiTurnToolLog.record(
          "manage_supplier",
          true,
          AiTurnToolLog.Status.FAILED,
          target,
          "Registration number already exists for partner VIVACOM.",
          "VALIDATION_ERROR");
      controller.update(workflow, null);
    } finally {
      AiWriteEffects.end();
      AiTurnToolLog.end();
    }

    assertTrue(
        workflow.isSettled(), "the only target this job had is now blocked; nothing remains");
    assertFalse(workflow.hasRemaining());
    assertEquals(1, workflow.blocked().size());
    assertEquals(target, workflow.blocked().get(0).getRef());
  }

  /**
   * A duplicate-ЕИК create leaves a job on record authorised only over its own ЕИК's synthetic
   * target, and the NEXT message is a complete, unrelated explicit create for a DIFFERENT ЕИК. Even
   * when the route this turn is handed carries {@code AiScope.CONVERSATION} — the shape a router
   * CONTINUE verdict produces when it (wrongly) reads the new message as confirming the earlier
   * proposal — this turn's own words name their own target, and that must win over inheriting the
   * stale job's. A {@code CONVERSATION} branch that inherits unconditionally would authorise {@code
   * PartnerCreate:5:130159785} against stale {@code PartnerCreate:5:831642181} instead of its own
   * target.
   */
  @Test
  void aFreshExplicitCreateUnderAConversationScopedRouteResolvesItsOwnTargetNotTheStaleOne() {
    AiWorkflow stale = jobOn("PartnerCreate:5:831642181");
    stale.setWorkflowId("stale-duplicate-eik-job");
    AiRoute route =
        new AiRoute(
            AiDomain.PARTNER,
            AiTask.MAINTAIN_SUPPLIER,
            null,
            AiScope.CONVERSATION,
            AiExplicitness.INHERITED);

    AiWorkflowController controller =
        new AiWorkflowController() {
          @Override
          protected List<String> messageTargets(AiRoute r, String message) {
            return Collections.singletonList("PartnerCreate:5:130159785");
          }

          @Override
          protected ResolvedSet resolveExplicitSet(List<String> explicitRefs) {
            ResolvedSet resolved = new ResolvedSet();
            resolved.authorised.addAll(explicitRefs);
            return resolved;
          }

          /**
           * Needed because the message's own target is now compared against the job's before the
           * INHERITED branch may resume it — see {@code matchesPrevious}. Without a registry that
           * comparison fails closed ("matches"), and the stale job would swallow this instruction.
           */
          @Override
          protected AiTargetRegistry targetRegistry() {
            return emptyRegistry();
          }
        };

    AiWorkflow result =
        controller.open(
            "Направи доставчик с ЕИК 130159785 телефон +359 2 995 0534 и име SAP BULGARIA",
            route,
            null,
            stale,
            5L,
            "conv-1",
            Collections.emptyList());

    assertNotEquals(stale.getWorkflowId(), result.getWorkflowId());
    assertEquals(
        Collections.singletonList("PartnerCreate:5:130159785"), result.getAuthorisedTargets());
  }

  /**
   * A route verdict carrying {@code AiScope.CONVERSATION} with NO previous job on record at all —
   * {@code previous} is null, so the CONVERSATION branch in {@code authoriseTargets} (which
   * requires {@code previous != null}) never runs. A message-based fallback gated to {@code
   * UNSCOPED}/{@code WORKLIST} only would let CONVERSATION fall through both and freeze an EMPTY
   * authorised set, and a complete, self-contained instruction — name, ЕИК, phone, company, all in
   * one sentence, with nothing to continue — would be refused with "the set of records this
   * instruction covers could not be established", even though the message names its own target
   * plainly.
   *
   * <p>No open record, no previous job, no continuation word: the ONLY evidence is the message
   * itself, which the fallback reads regardless of why the route ended up CONVERSATION-scoped.
   */
  @Test
  void aConversationScopedTurnWithNoPreviousJobStillResolvesItsOwnExplicitTarget() {
    String message =
        "Направи доставчик с ЕИК 130159785 телефон +359 2 995 0534 и име SAP BULGARIA. Трябва ми"
            + " да е свързан с Тест Фиш България ООД - активната фирма.";
    AiRoute route =
        new AiRoute(
            AiDomain.PARTNER,
            AiTask.MAINTAIN_SUPPLIER,
            null,
            AiScope.CONVERSATION,
            AiExplicitness.EXPLICIT);

    AiWorkflowController controller =
        new AiWorkflowController() {
          @Override
          protected List<String> messageTargets(AiRoute r, String msg) {
            assertEquals(message, msg);
            return Collections.singletonList("PartnerCreate:5:130159785");
          }

          @Override
          protected ResolvedSet resolveExplicitSet(List<String> explicitRefs) {
            // Standing in for PartnerCreationTargetIdentity.canonical/writable, which only ever
            // inspect the company id EMBEDDED IN THE REF ITSELF (5, the active company) — never
            // whether some OTHER company's own supplier happens to hold this ЕИК, which is why a
            // company-4-only holder can never make this ineligible.
            ResolvedSet resolved = new ResolvedSet();
            resolved.authorised.addAll(explicitRefs);
            return resolved;
          }
        };

    AiWorkflow result =
        controller.open(message, route, null, null, 5L, "conv-1", Collections.emptyList());

    assertNotNull(
        result, "a complete explicit instruction must open a job even with none before it");
    assertEquals(
        Collections.singletonList("PartnerCreate:5:130159785"), result.getAuthorisedTargets());
    assertTrue(result.hasClosedTargets());
    assertTrue(result.isActive());
  }

  /**
   * A turn routed {@code AiScope.NAMED_RECORDS} — not {@code CONVERSATION} — with an EMPTY {@code
   * explicitRefs}. {@code AiExplicitReferenceParser} never produces that combination on its own
   * ({@code AiRouteAdapter} only selects {@code NAMED_RECORDS} because the parser found
   * unmistakable database-id syntax), so this can only be the Router Agent setting {@code
   * scope=NAMED_RECORDS} on a ROUTE verdict because it read «ЕИК 130159785» as "a record the user
   * named" — true in spirit, not in the narrow syntactic sense this scope means. Without the
   * message fallback, {@code resolveExplicitSet(emptyList)} trivially freezes an authorised set of
   * nothing ({@code "authorisedTargets":[]}) and {@code checkTargets} refuses with "could not be
   * established" — {@code manage_supplier} is never even reached as an authorisation question, only
   * as a blocked-target report afterwards.
   */
  @Test
  void aNamedRecordsRouteWithNoActualExplicitReferenceStillResolvesTheMessagesOwnTarget() {
    String message =
        "Направи доставчик с ЕИК 130159785 телефон +359 2 995 0534 и име SAP BULGARIA. Трябва ми"
            + " да е свързан с Тест Фиш България ООД - активната фирма.";
    AiRoute route =
        new AiRoute(
            AiDomain.PARTNER,
            AiTask.MAINTAIN_SUPPLIER,
            null,
            AiScope.NAMED_RECORDS,
            AiExplicitness.EXPLICIT);

    AiWorkflowController controller =
        new AiWorkflowController() {
          @Override
          protected List<String> messageTargets(AiRoute r, String msg) {
            assertEquals(message, msg);
            return Collections.singletonList("PartnerCreate:5:130159785");
          }

          @Override
          protected ResolvedSet resolveExplicitSet(List<String> explicitRefs) {
            ResolvedSet resolved = new ResolvedSet();
            resolved.authorised.addAll(explicitRefs);
            return resolved;
          }
        };

    // explicitRefs empty, exactly as AiExplicitReferenceParser.parse would answer for this
    // message: no invoiceId=/Partner#-style syntax anywhere in it.
    AiWorkflow result =
        controller.open(message, route, null, null, 5L, "conv-1", Collections.emptyList());

    assertNotNull(result);
    assertEquals(
        Collections.singletonList("PartnerCreate:5:130159785"), result.getAuthorisedTargets());
    assertTrue(result.hasClosedTargets());
  }

  /**
   * NAMED_RECORDS with a genuinely non-empty {@code explicitRefs} must keep refusing to widen the
   * set from the message — the one case {@link
   * #aNamedRecordsRouteWithNoActualExplicitReferenceStillResolvesTheMessagesOwnTarget} must not
   * weaken. «Валидирай invoiceId=641 и нещо си EИК 999999999» names 641 explicitly; a stray ЕИК
   * elsewhere in the same sentence must never be added to it.
   */
  @Test
  void namedRecordsWithARealExplicitReferenceNeverFallsBackToTheMessage() {
    AiRoute route =
        new AiRoute(
            AiDomain.SUPPLIER_INVOICE,
            AiTask.REPAIR_SUPPLIER_INVOICE,
            null,
            AiScope.NAMED_RECORDS,
            AiExplicitness.EXPLICIT);

    AiWorkflowController controller =
        new AiWorkflowController() {
          @Override
          protected List<String> messageTargets(AiRoute r, String msg) {
            throw new AssertionError("must never be called when explicitRefs is non-empty");
          }

          @Override
          protected ResolvedSet resolveExplicitSet(List<String> explicitRefs) {
            ResolvedSet resolved = new ResolvedSet();
            resolved.authorised.addAll(explicitRefs);
            return resolved;
          }
        };

    AiWorkflow result =
        controller.open(
            "осчетоводи invoiceId=641",
            route,
            null,
            null,
            5L,
            "conv-1",
            Collections.singletonList("Invoice#641"));

    assertEquals(Collections.singletonList("Invoice#641"), result.getAuthorisedTargets());
  }

  /**
   * CONVERSATION with a previous job that IS active but whose own authorised set is empty (an
   * open-ended job, never closed to particular records) must still resolve THIS message's own
   * target first — inheriting an empty set is not "nothing to inherit, fall back to the message" by
   * accident, it is the same rule stated outright: the message's own words are read before any
   * inherited target is considered, whatever that inherited target is.
   */
  @Test
  void aConversationScopedTurnWithAPreviousOpenJobStillResolvesItsOwnExplicitTarget() {
    AiWorkflow openJob = new AiWorkflow();
    openJob.setWorkflowId("open-worklist-job");
    openJob.setWriteJob(true);
    openJob.setTask("REPAIR_SUPPLIER_INVOICE");
    openJob.addTarget("Invoice#900"); // seeded as progress, never authorised/closed
    String message =
        "Направи доставчик с ЕИК 130159785 телефон +359 2 995 0534 и име SAP BULGARIA. Трябва ми"
            + " да е свързан с Тест Фиш България ООД - активната фирма.";
    AiRoute route =
        new AiRoute(
            AiDomain.PARTNER,
            AiTask.MAINTAIN_SUPPLIER,
            null,
            AiScope.CONVERSATION,
            AiExplicitness.EXPLICIT);

    AiWorkflowController controller =
        new AiWorkflowController() {
          @Override
          protected List<String> messageTargets(AiRoute r, String msg) {
            return Collections.singletonList("PartnerCreate:5:130159785");
          }

          @Override
          protected ResolvedSet resolveExplicitSet(List<String> explicitRefs) {
            ResolvedSet resolved = new ResolvedSet();
            resolved.authorised.addAll(explicitRefs);
            return resolved;
          }
        };

    AiWorkflow result =
        controller.open(message, route, null, openJob, 5L, "conv-1", Collections.emptyList());

    assertEquals(
        Collections.singletonList("PartnerCreate:5:130159785"), result.getAuthorisedTargets());
  }

  private static PageContext openInvoice(long id) {
    PageContext page = new PageContext();
    page.setModel("com.axelor.apps.account.db.Invoice");
    page.setRecordId(id);
    return page;
  }

  /**
   * «валидирай invoiceId=641» with invoice 900 on screen: a router that answered OPEN_RECORD must
   * not turn it into "the record on screen". The parser's references decide whatever the scope
   * says.
   */
  @Test
  void explicitReferencesOverrideARouterOpenRecordVerdict() {
    AiRoute route =
        new AiRoute(
            AiDomain.SUPPLIER_INVOICE,
            AiTask.VALIDATE_SUPPLIER_INVOICE,
            AiWorkflowIntent.Stage.VALIDATED,
            AiScope.OPEN_RECORD,
            AiExplicitness.EXPLICIT);
    AiWorkflowController controller =
        new AiWorkflowController() {
          @Override
          protected ResolvedSet resolveExplicitSet(List<String> explicitRefs) {
            ResolvedSet resolved = new ResolvedSet();
            resolved.authorised.addAll(explicitRefs);
            return resolved;
          }
        };

    AiWorkflow result =
        controller.open(
            "валидирай invoiceId=641",
            route,
            openInvoice(900L),
            null,
            5L,
            "conv-1",
            Collections.singletonList("Invoice#641"));

    assertEquals(Collections.singletonList("Invoice#641"), result.getAuthorisedTargets());
  }

  /**
   * A named id that does not resolve — mistyped, another company's — authorises nothing, and is
   * never replaced by the record on screen or by anything else the message might name.
   */
  @Test
  void anUnresolvableExplicitReferenceNeverFallsBackToTheOpenRecord() {
    AiRoute route =
        new AiRoute(
            AiDomain.SUPPLIER_INVOICE,
            AiTask.VALIDATE_SUPPLIER_INVOICE,
            AiWorkflowIntent.Stage.VALIDATED,
            AiScope.OPEN_RECORD,
            AiExplicitness.EXPLICIT);
    AiWorkflowController controller =
        new AiWorkflowController() {
          @Override
          protected ResolvedSet resolveExplicitSet(List<String> explicitRefs) {
            return new ResolvedSet(); // nothing identifiable in the active company
          }

          @Override
          protected List<String> messageTargets(AiRoute r, String msg) {
            throw new AssertionError("an explicit set that fails must not be replaced");
          }
        };

    AiWorkflow result =
        controller.open(
            "валидирай invoiceId=641",
            route,
            openInvoice(900L),
            null,
            5L,
            "conv-1",
            Collections.singletonList("Invoice#641"));

    assertTrue(result.getAuthorisedTargets().isEmpty(), "never Invoice#900");
    assertEquals(AiBlockerCode.EXPLICIT_REFERENCE_UNRESOLVED.wire(), result.getTargetRefusalCode());
  }

  /**
   * «осчетоводи фактурите на Лидл» on the invoice list, routed by the deterministic adapter — which
   * states no {@code AiSetSelection} at all, because it reads the page rather than the sentence.
   *
   * <p>The refusal code changed with the selection mechanism and the property did not: nothing
   * establishes how much of the list is meant, so nothing is authorised. It is never read as "all
   * of it", which is the one guess available and the one that would post every eligible invoice.
   * The narrowed-but-stated case — the router answering {@code FILTERED} — is {@code
   * AiWorkflowSetSelectionTest.anUnsupportedFilterIsRefusedAndNeverWidenedToTheWholeQueue}.
   */
  @Test
  void aWorklistInstructionWithNoStatedSelectionIsRefusedRatherThanWidened() {
    AiRoute route =
        new AiRoute(
            AiDomain.SUPPLIER_INVOICE,
            AiTask.POST_SUPPLIER_INVOICE,
            AiWorkflowIntent.Stage.POSTED,
            AiScope.WORKLIST,
            AiExplicitness.EXPLICIT);
    PageContext list = new PageContext();
    list.setModel("com.axelor.apps.account.db.Invoice");
    AiWorkflowController controller =
        new AiWorkflowController() {
          @Override
          protected ResolvedSet resolveSet(AiRoute r, PageContext p) {
            throw new AssertionError("the unfiltered queue must not be resolved");
          }

          @Override
          protected List<String> messageTargets(AiRoute r, String msg) {
            return Collections.emptyList(); // "Лидл" names no record by a business key
          }
        };

    AiWorkflow result =
        controller.open(
            "осчетоводи фактурите на Лидл",
            route,
            list,
            null,
            5L,
            "conv-1",
            Collections.emptyList());

    assertTrue(result.getAuthorisedTargets().isEmpty());
    assertEquals(AiBlockerCode.WORKLIST_SELECTION_UNRESOLVED.wire(), result.getTargetRefusalCode());
  }

  /**
   * The other half of «Създай доставчик … ЕИК 999999995» on Purchases → Suppliers: once the
   * selector has taken the creation off the list (see {@code
   * AiRouteSelectorTest.aCreateOverAnOpenListIsNeverAWorklistInstruction}), the job freezes the
   * creation's own target and no worklist is ever consulted or blamed.
   */
  @Test
  void aCreateFromTheSupplierListFreezesItsOwnTargetAndNeverTheWorklist() {
    AiRoute route =
        new AiRoute(
            AiDomain.PARTNER,
            AiTask.MAINTAIN_SUPPLIER,
            null,
            AiScope.UNSCOPED,
            AiExplicitness.EXPLICIT,
            null,
            null,
            null,
            AiWriteOperation.CREATE);
    PageContext list = new PageContext();
    list.setModel("com.axelor.apps.base.db.Partner");
    AiWorkflowController controller =
        new AiWorkflowController() {
          @Override
          protected Selected resolveWorklist(AiRoute r, AiWorkflow previous) {
            throw new AssertionError("a creation is not a list instruction");
          }

          @Override
          protected List<String> messageTargets(AiRoute r, String msg) {
            return Collections.singletonList("PartnerCreate:3:999999995");
          }

          @Override
          protected ResolvedSet resolveExplicitSet(List<String> explicitRefs) {
            ResolvedSet resolved = new ResolvedSet();
            resolved.authorised.addAll(explicitRefs);
            return resolved;
          }
        };

    AiWorkflow result =
        controller.open(
            "Създай доставчик ДЕМО ДОСТАВЧИК ЕООД, ЕИК 999999995, населено място София",
            route,
            list,
            null,
            3L,
            "conv-1",
            Collections.emptyList());

    assertEquals(
        Collections.singletonList("PartnerCreate:3:999999995"), result.getAuthorisedTargets());
    assertNull(result.getTargetRefusalCode());
  }

  /**
   * «Създай липсващия доставчик» (or a bare «да») with nothing the server can tie it to is refused
   * with the one question that unblocks it; a router that left out CREATE gets no broader scope.
   */
  @Test
  void aVagueSupplierCreateWithNoProvenTargetIsRefusedNotGuessed() {
    AiWorkflowController controller =
        new AiWorkflowController() {
          @Override
          protected List<String> messageTargets(AiRoute r, String msg) {
            return Collections.emptyList();
          }

          @Override
          protected List<String> contextTargets(AiRoute r, String msg, PageContext page) {
            return Collections.emptyList();
          }
        };
    for (AiWriteOperation operation : new AiWriteOperation[] {AiWriteOperation.CREATE, null}) {
      AiRoute route =
          new AiRoute(
              AiDomain.PARTNER,
              AiTask.MAINTAIN_SUPPLIER,
              null,
              AiScope.UNSCOPED,
              AiExplicitness.EXPLICIT,
              null,
              null,
              null,
              operation);

      AiWorkflow result =
          controller.open(
              "създай липсващия доставчик",
              route,
              null,
              null,
              3L,
              "conv-2",
              Collections.emptyList());

      assertTrue(result.getAuthorisedTargets().isEmpty());
      if (operation == AiWriteOperation.CREATE) {
        assertNotNull(result.getTargetRefusalCode());
      }
    }
  }
}

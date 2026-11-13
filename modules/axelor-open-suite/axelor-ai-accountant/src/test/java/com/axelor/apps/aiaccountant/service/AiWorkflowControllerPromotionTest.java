package com.axelor.apps.aiaccountant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.context.AiTargetIdentity;
import com.axelor.apps.aiaccountant.context.AiTargetRegistry;
import com.axelor.apps.aiaccountant.route.AiExplicitness;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link AiWorkflowController#promoteIfConfirmed}: the only path by which a bare "yes" may
 * authorise a write, and the guardrails around it.
 *
 * <h2>What this exists for</h2>
 *
 * The assistant reads an upload's stored OCR evidence, finds an ЕИК with no matching supplier, and
 * proposes creating one. The user replies "дадада създай доставчика. информацията е правилна". A
 * proposal made only in prose gives that confirmation nothing to attach to, and the write is
 * refused with {@code write_not_authorised: ... there is no job on record to continue}. This tests
 * the structured form: the proposal is a row a trusted read wrote, keyed to the exact user, company
 * and conversation, never to the model's own words — and it is good for exactly one turn: this
 * method is called every turn, and whatever this turn is, a stored proposal does not survive it
 * unconfirmed.
 */
class AiWorkflowControllerPromotionTest {

  /**
   * An in-memory stand-in, so these tests need no database — see {@code AiPendingProposalStore}.
   */
  private static final class FakeProposalStore extends AiPendingProposalStore {

    private final Map<String, AiPendingProposalStore.Reading> byKey = new HashMap<>();
    private int consumeCalls = 0;

    private static String key(Long userId, Long companyId, String conversationId) {
      return userId + "/" + companyId + "/" + conversationId;
    }

    void seed(
        Long userId,
        Long companyId,
        String conversationId,
        String canonicalTarget,
        String sourceRef,
        String task) {
      byKey.put(
          key(userId, companyId, conversationId),
          new AiPendingProposalStore.Reading(canonicalTarget, sourceRef, task));
    }

    @Override
    public AiPendingProposalStore.Reading find(Long userId, Long companyId, String conversationId) {
      return byKey.get(key(userId, companyId, conversationId));
    }

    @Override
    public void record(
        Long userId,
        Long companyId,
        String conversationId,
        String canonicalTarget,
        String sourceRef,
        String task) {
      byKey.put(
          key(userId, companyId, conversationId),
          new AiPendingProposalStore.Reading(canonicalTarget, sourceRef, task));
    }

    @Override
    public void consume(Long userId, Long companyId, String conversationId) {
      consumeCalls++;
      byKey.remove(key(userId, companyId, conversationId));
    }

    boolean isEmpty() {
      return byKey.isEmpty();
    }
  }

  private static AiTargetRegistry emptyRegistry() {
    return new AiTargetRegistry() {
      @Override
      protected List<AiTargetIdentity> discover() {
        return Collections.emptyList();
      }
    };
  }

  private static AiWorkflowController controllerWith(FakeProposalStore store) {
    return new AiWorkflowController() {
      @Override
      protected AiPendingProposalStore pendingProposalStore() {
        return store;
      }

      @Override
      protected AiTargetRegistry targetRegistry() {
        return emptyRegistry();
      }
    };
  }

  // ── no proposal on record ────────────────────────────────────────────────

  @Test
  void ordinaryInstructionsWithNothingPendingConsultNothing() {
    FakeProposalStore store = new FakeProposalStore();
    AiWorkflowController controller = controllerWith(store);

    AiWorkflowController.Promotion promotion =
        controller.promoteIfConfirmed(
            "Създай доставчика 831642181 за Upload#11", Collections.emptyList(), 1L, 2L, "c-1");

    assertNull(promotion);
    assertEquals(0, store.consumeCalls, "nothing was pending, so there was nothing to invalidate");
  }

  /** "unrelated 'yes' does not promote" — no proposal was ever read for this conversation. */
  @Test
  void unrelatedYesDoesNotPromote() {
    FakeProposalStore store = new FakeProposalStore();
    AiWorkflowController controller = controllerWith(store);

    assertNull(controller.promoteIfConfirmed("да", Collections.emptyList(), 1L, 2L, "c-1"));
  }

  // ── the one-turn window ──────────────────────────────────────────────────

  /**
   * "да, създай доставчика on the next turn promotes exactly once" — the heart of the fix. A second
   * "да" right after finds nothing: the single stored proposal was consumed by the first.
   */
  @Test
  void confirmationOnTheNextTurnPromotesExactlyOnce() {
    FakeProposalStore store = new FakeProposalStore();
    store.seed(1L, 2L, "c-1", "PartnerCreate:2:831257890", "Upload#18", "CREATE_SUPPLIER_INVOICE");
    AiWorkflowController controller = controllerWith(store);

    AiWorkflowController.Promotion promotion =
        controller.promoteIfConfirmed(
            "дадада създай доставчика. информацията е правилна",
            Collections.emptyList(),
            1L,
            2L,
            "c-1");

    assertNotNull(promotion);
    assertEquals(
        Collections.singletonList("PartnerCreate:2:831257890"),
        promotion.workflow().getAuthorisedTargets());
    assertEquals(AiExplicitness.EXPLICIT, promotion.route().explicitness());
    assertTrue(
        promotion.workflow().isActive(),
        "the promoted job must be active, or checkAuthorisation refuses it");
    assertTrue(store.isEmpty(), "the proposal is single-use: promoting it consumes it immediately");

    assertNull(
        controller.promoteIfConfirmed("да", Collections.emptyList(), 1L, 2L, "c-1"),
        "a second confirmation finds nothing left to confirm");
  }

  /**
   * "expired/intervening-turn proposal does not promote" — an unrelated turn in between (a
   * question, a new instruction, anything that is not the matching confirmation) invalidates the
   * proposal, exactly as the explicit-different-target case does; the following "да" then finds
   * nothing.
   */
  @Test
  void anInterveningUnrelatedTurnExpiresTheProposal() {
    FakeProposalStore store = new FakeProposalStore();
    store.seed(1L, 2L, "c-1", "PartnerCreate:2:831257890", "Upload#18", "CREATE_SUPPLIER_INVOICE");
    AiWorkflowController controller = controllerWith(store);

    // Turn N+1: something else entirely — not a confirmation of anything.
    assertNull(
        controller.promoteIfConfirmed(
            "коя е активната компания?", Collections.emptyList(), 1L, 2L, "c-1"));
    assertTrue(store.isEmpty(), "the intervening turn must have invalidated the stale proposal");

    // Turn N+2: the confirmation arrives one turn too late.
    assertNull(
        controller.promoteIfConfirmed(
            "дадада създай доставчика", Collections.emptyList(), 1L, 2L, "c-1"));
  }

  /** A task switch is an intervening turn like any other — it also expires the proposal. */
  @Test
  void aTaskSwitchAlsoExpiresTheProposal() {
    FakeProposalStore store = new FakeProposalStore();
    store.seed(1L, 2L, "c-1", "PartnerCreate:2:831257890", "Upload#18", "CREATE_SUPPLIER_INVOICE");
    AiWorkflowController controller = controllerWith(store);

    assertNull(
        controller.promoteIfConfirmed(
            "оправи и осчетоводи фактура 946", Collections.emptyList(), 1L, 2L, "c-1"));

    assertNull(
        controller.promoteIfConfirmed("да", Collections.emptyList(), 1L, 2L, "c-1"),
        "the proposal did not survive the intervening task switch");
  }

  // ── scope: company, conversation, upload/target ─────────────────────────

  /** "changed ... company ... refuses" — a proposal recorded under a different company key. */
  @Test
  void confirmationForADifferentCompanyFindsNothing() {
    FakeProposalStore store = new FakeProposalStore();
    store.seed(1L, 2L, "c-1", "PartnerCreate:2:831257890", "Upload#18", "CREATE_SUPPLIER_INVOICE");
    AiWorkflowController controller = controllerWith(store);

    assertNull(controller.promoteIfConfirmed("да", Collections.emptyList(), 1L, 3L, "c-1"));
    assertFalse(store.isEmpty(), "a lookup under a different company must not touch the real one");
  }

  /** "changed ... conversation ... refuses" — same reasoning, the other half of the key. */
  @Test
  void confirmationForADifferentConversationFindsNothing() {
    FakeProposalStore store = new FakeProposalStore();
    store.seed(1L, 2L, "c-1", "PartnerCreate:2:831257890", "Upload#18", "CREATE_SUPPLIER_INVOICE");
    AiWorkflowController controller = controllerWith(store);

    assertNull(controller.promoteIfConfirmed("да", Collections.emptyList(), 1L, 2L, "c-2"));
    assertFalse(
        store.isEmpty(), "a lookup under a different conversation must not touch the real one");
  }

  /**
   * "changed ... upload/target ... refuses" — an explicit reference in the confirming message
   * itself outranks the stored proposal, the same rule an ordinary resumed job is held to. Naming a
   * different record is evidence this confirmation is not about the proposal, so it is refused —
   * and, per the one-turn rule, the stale proposal is also invalidated rather than left open for a
   * differently-worded retry.
   */
  @Test
  void anExplicitReferenceToADifferentRecordRefusesAndExpiresThePromotion() {
    FakeProposalStore store = new FakeProposalStore();
    store.seed(1L, 2L, "c-1", "PartnerCreate:2:831257890", "Upload#18", "CREATE_SUPPLIER_INVOICE");
    AiWorkflowController controller = controllerWith(store);

    AiWorkflowController.Promotion promotion =
        controller.promoteIfConfirmed(
            "да, за Upload#99", Collections.singletonList("Upload#99"), 1L, 2L, "c-1");

    assertNull(promotion);
    assertTrue(store.isEmpty(), "naming a different record is itself an intervening event");
  }

  /**
   * Recording a proposal is never, by itself, an authorisation — including within the very turn
   * that reads it: a trusted read tool runs mid-turn, after this turn's own job has already been
   * opened and frozen, so a target it discovers cannot retroactively join that job's authorised
   * set. {@code AiWriteAuthorisation} still has to be run against whatever job the turn actually
   * opened, and a job unrelated to the proposal is unaffected by one merely being on record.
   */
  @Test
  void recordingAProposalDoesNotChangeAnUnrelatedJobsAuthorisedTargets() {
    FakeProposalStore store = new FakeProposalStore();
    // This turn's own job, frozen BEFORE the read tool below ever runs.
    com.axelor.apps.aiaccountant.dto.AiWorkflow thisTurnsJob =
        new com.axelor.apps.aiaccountant.dto.AiWorkflow();
    thisTurnsJob.setWriteJob(true);
    thisTurnsJob.addTarget("AiOcrUpload#11");
    thisTurnsJob.setAuthorisedTargets(Collections.singletonList("AiOcrUpload#11"));

    // Mid-turn: a read tool discovers a missing supplier and records a proposal.
    store.record(
        1L, 2L, "c-1", "PartnerCreate:2:831257890", "Upload#18", "CREATE_SUPPLIER_INVOICE");

    // The tool loop then (hypothetically) tries manage_supplier(create) for that new target, in
    // the SAME turn, against the job that was already frozen.
    AiTargetRegistry registry = emptyRegistry();
    AiWriteAuthorisation.TargetCheck check =
        AiWriteAuthorisation.checkTargets(thisTurnsJob, "PartnerCreate:2:831257890", registry);

    assertNotNull(check.refusal(), "discovery alone must never authorise a write in the same turn");
  }

  /**
   * A broken store must fail the turn, not be read as "nothing was pending". {@link
   * AiWorkflowController#promoteIfConfirmed} deliberately does not catch a {@link RuntimeException}
   * from {@link AiWorkflowController#pendingProposalStore()} any more — see its own javadoc —
   * precisely so a genuine dependency-injection failure in production surfaces instead of silently
   * disabling confirmation handling.
   */
  @Test
  void aRealStoreFailureIsNotSwallowedAsNoProposal() {
    AiWorkflowController controller =
        new AiWorkflowController() {
          @Override
          protected AiPendingProposalStore pendingProposalStore() {
            return new AiPendingProposalStore() {
              @Override
              public Reading find(Long userId, Long companyId, String conversationId) {
                throw new IllegalStateException(
                    "Guice binding for AiPendingProposalStore is broken");
              }
            };
          }

          @Override
          protected AiTargetRegistry targetRegistry() {
            return emptyRegistry();
          }
        };

    assertThrows(
        IllegalStateException.class,
        () -> controller.promoteIfConfirmed("да", Collections.emptyList(), 1L, 2L, "c-1"));
  }
}

package com.axelor.apps.aiaccountant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.dto.AiConversationState;
import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.prompt.AiTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The job the server keeps track of: its goal, its targets, and what became of each one.
 *
 * <p>No Guice and no database. Everything asserted here is bookkeeping about the conversation,
 * which is exactly the part that used to be inferred from the model's prose and is now recorded —
 * so the tests are about whether the record survives a continuation, a partial failure and a second
 * look, not about anything accounting.
 */
class AiWorkflowStateTest {

  private static final AiWorkflowController CONTROLLER = new AiWorkflowController();

  private static AiWorkflow job(String goal, String request, String... targets) {
    AiWorkflow workflow = new AiWorkflow();
    workflow.setGoal(goal);
    workflow.setRequest(request);
    for (String t : targets) {
      workflow.addTarget(t);
    }
    return workflow;
  }

  /**
   * A job the server holds, serialised and read back exactly as {@code AiWorkflowStore} does.
   *
   * <p>It used to go through the conversation blob, because that was where a job travelled between
   * turns. It no longer travels there at all — the row is the only copy — so the round trip that
   * matters is the store's JSON, and testing the blob's would be testing a path nothing uses.
   */
  private static AiWorkflow storedAndReloaded(AiWorkflow workflow) {
    try {
      ObjectMapper mapper = new ObjectMapper();
      return mapper.readValue(mapper.writeValueAsString(workflow), AiWorkflow.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  // ── continuation ──────────────────────────────────────────────────────────

  /**
   * The scope failure. «Продължи» carries no goal and no records of its own, so re-deriving them
   * from the word finds nothing — which is how the assistant ended up asking which invoices were
   * meant, about a set the user had named one message earlier.
   */
  @Test
  void continuationKeepsTheGoalAndTheTargets() {
    AiWorkflow before =
        job("POSTED", "оправи всички и ги осчетоводи", "Upload#941", "Upload#942", "Upload#943");
    before.markCompleted("Upload#941");

    AiWorkflow after = CONTROLLER.open(before, "продължи", null, AiTask.GENERAL);

    assertSame(before, after, "a continuation resumes the job, it does not start one");
    assertEquals("POSTED", after.getGoal());
    assertEquals("оправи всички и ги осчетоводи", after.getRequest());
    assertEquals(Arrays.asList("Upload#942", "Upload#943"), after.remaining());
  }

  /** A new instruction with its own destination replaces the job rather than joining it. */
  @Test
  void aNewInstructionDoesNotWidenTheOldAuthorisation() {
    AiWorkflow before = job("POSTED", "осчетоводи тези двете", "Upload#941", "Upload#942");

    AiWorkflow after =
        CONTROLLER.open(
            before, "осчетоводи 946", AiWorkflowIntent.Stage.POSTED, AiTask.POST_SUPPLIER_INVOICE);

    assertEquals("осчетоводи 946", after.getRequest());
    assertTrue(after.getTargets().isEmpty(), "the previous targets must not be inherited");
  }

  /** A question about the job must not end the job it is asking about. */
  @Test
  void askingWhatRemainsKeepsTheJobAlive() {
    AiWorkflow before = job("POSTED", "осчетоводи всички", "Upload#941", "Upload#942");
    before.markCompleted("Upload#941");

    AiWorkflow after = CONTROLLER.open(before, "какво остана?", null, AiTask.GENERAL);

    assertSame(before, after);
    assertEquals(Arrays.asList("Upload#942"), after.remaining());
  }

  /** "What remains" is answered from the state, not from prose. */
  @Test
  void whatRemainsIsReadFromTheRecordedState() {
    AiWorkflow workflow =
        job("POSTED", "осчетоводи всички", "Upload#941", "Upload#942", "Upload#943", "Upload#944");
    workflow.markCompleted("Upload#941");
    workflow.markCompleted("Upload#942");
    workflow.markBlocked("Upload#943", AiBlockerClass.HUMAN_DECISION_REQUIRED.name(), "дублиран №");

    assertEquals(Arrays.asList("Upload#941", "Upload#942"), workflow.getCompleted());
    assertEquals(1, workflow.blocked().size());
    assertEquals(Arrays.asList("Upload#944"), workflow.remaining());

    String line = CONTROLLER.statusLine(workflow, true);
    assertTrue(line.contains("Готови: Upload#941, Upload#942"), line);
    assertTrue(line.contains("Блокирани: Upload#943 — дублиран №"), line);
    assertTrue(line.contains("Остават 1: Upload#944"), line);
  }

  // ── never repeat a completed record ───────────────────────────────────────

  /**
   * Idempotency, at the level that matters: a record that reached the goal leaves the work list for
   * good, so a continuation cannot walk back over it.
   */
  @Test
  void aCompletedTargetIsNeverOfferedAsWorkAgain() {
    AiWorkflow workflow = job("POSTED", "осчетоводи ги", "Upload#941", "Upload#942");
    workflow.markCompleted("Upload#941");

    assertFalse(workflow.remaining().contains("Upload#941"));

    // A later refusal on an already-posted invoice says nothing about it, and must not resurrect
    // it.
    workflow.markBlocked("Upload#941", AiBlockerClass.TECHNICAL_FAILURE.name(), "already posted");

    assertTrue(workflow.blocked().isEmpty(), "a done record cannot become blocked");
    assertFalse(workflow.remaining().contains("Upload#941"));
    assertEquals(Arrays.asList("Upload#942"), workflow.remaining());
  }

  /** Marking the same completion twice is not two completions. */
  @Test
  void completingTwiceIsIdempotent() {
    AiWorkflow workflow = job("POSTED", "осчетоводи я", "Upload#941");
    workflow.markCompleted("Upload#941");
    workflow.markCompleted("Upload#941");

    assertEquals(1, workflow.getCompleted().size());
    assertEquals(1, workflow.getTargets().size());
  }

  // ── friction ──────────────────────────────────────────────────────────────

  /** With work outstanding, the next move is work — not "shall I continue?". */
  @Test
  void doesNotNeedTheUserWhileFixableWorkRemains() {
    AiWorkflow workflow = job("POSTED", "осчетоводи ги", "Upload#941", "Upload#942");
    workflow.markBlocked("Upload#941", AiBlockerClass.FIXABLE.name(), "липсва сметка");

    assertTrue(CONTROLLER.shouldKeepWorking(workflow), "942 has not been tried");
    assertFalse(
        CONTROLLER.needsUser(workflow),
        "a missing account is work the assistant is authorised to do");
  }

  /** Only a human-decision blocker justifies turning to the user. */
  @Test
  void onlyAHumanDecisionBlockerAsksTheUser() {
    AiWorkflow workflow = job("POSTED", "осчетоводи я", "Upload#941");
    workflow.markBlocked("Upload#941", AiBlockerClass.FIXABLE.name(), "липсва данъчен код");
    assertFalse(CONTROLLER.needsUser(workflow));

    workflow.markBlocked(
        "Upload#941", AiBlockerClass.HUMAN_DECISION_REQUIRED.name(), "дублиран номер");
    assertTrue(CONTROLLER.needsUser(workflow));
  }

  /** A settled job has nothing to keep working on, whatever its blockers were. */
  @Test
  void aSettledJobIsNotStillRunning() {
    AiWorkflow workflow = job("POSTED", "осчетоводи ги", "Upload#941", "Upload#942");
    workflow.markCompleted("Upload#941");
    workflow.markBlocked("Upload#942", AiBlockerClass.HUMAN_DECISION_REQUIRED.name(), "PFP");

    assertTrue(workflow.isSettled());
    assertFalse(CONTROLLER.shouldKeepWorking(workflow));
    assertTrue(CONTROLLER.needsUser(workflow));
  }

  // ── transport ─────────────────────────────────────────────────────────────

  /**
   * The job has to survive the round trip through the store, which is now the only place it lives.
   *
   * <p>It used to travel in the conversation blob as well, and this test asserted that. The blob is
   * a value the browser supplies, so a job read back from it could not be an authorisation — a
   * client with no server record could hand itself one. The job is written to and read from {@code
   * AiWorkflowState} alone, and what has to survive intact is that serialisation: the goal, the
   * per-record completions, and each blocker with its class.
   */
  @Test
  void survivesSerialisationIntoTheStoredJob() {
    AiWorkflow workflow = job("POSTED", "оправи всички и осчетоводи", "Upload#941", "Upload#942");
    workflow.markCompleted("Upload#941");
    workflow.markBlocked("Upload#942", AiBlockerClass.HUMAN_DECISION_REQUIRED.name(), "дублиран №");

    AiWorkflow back = storedAndReloaded(workflow);

    assertNotNull(back);
    assertTrue(back.isActive());
    assertEquals("POSTED", back.getGoal());
    assertEquals(Arrays.asList("Upload#941"), back.getCompleted());
    assertEquals(1, back.blocked().size());
    assertEquals(AiBlockerClass.HUMAN_DECISION_REQUIRED.name(), back.blocked().get(0).getBlocker());
  }

  /**
   * The blob is not a job, however complete it looks: a stored blob from before the field was
   * removed still parses, keeps everything else, and its job is neither read nor written back.
   */
  @Test
  void theConversationBlobNoLongerCarriesAJob() {
    String legacy =
        "{\"v\":3,\"pending\":true,\"request\":\"оправи всички и осчетоводи\","
            + "\"workflow\":{\"goal\":\"POSTED\",\"targets\":[\"Upload#941\"]}}";

    AiConversationState back = AiConversationState.parse(legacy);

    assertNotNull(back, "an older blob with a workflow block must not be dropped");
    assertEquals("оправи всички и осчетоводи", back.getRequest());
    assertFalse(back.toJson().contains("workflow"), "a returned blob never carries a job");
  }

  /**
   * A pre-AI-6 blob has targets but no per-record outcome, so it is dropped rather than half-read.
   */
  @Test
  void anOlderStateBlobIsNotReinterpreted() {
    assertNull(AiConversationState.parse("{\"v\":2,\"pending\":true,\"request\":\"осчетоводи\"}"));
  }

  /** The target list is bounded: the blob lives in the browser. */
  @Test
  void theTargetSetIsBounded() {
    AiWorkflow workflow = job("POSTED", "всички");
    for (int i = 0; i < AiWorkflow.MAX_TARGETS + 10; i++) {
      workflow.addTarget("Upload#" + i);
    }
    assertEquals(AiWorkflow.MAX_TARGETS, workflow.getTargets().size());
  }

  // ── the authorised set is not the search page size ────────────────────────

  /**
   * The cap used to be 25, borrowed from the search page size, and that stopped being defensible
   * once the search learned to page. «Всички» can now genuinely resolve to forty records, and a
   * workflow that silently kept twenty-five of them would finish early and call it done — which is
   * the failure the whole workflow state exists to prevent.
   */
  @Test
  void aSetLargerThanOneSearchPageSurvivesInFull() {
    AiWorkflow workflow = job("POSTED", "оправи всички и ги осчетоводи");
    for (int i = 1; i <= 40; i++) {
      workflow.addTarget("Upload#" + i);
    }

    assertEquals(40, workflow.getTargets().size(), "the whole authorised set is kept");
    assertEquals(40, workflow.remaining().size());

    AiWorkflow back = storedAndReloaded(workflow);
    assertNotNull(back);
    assertEquals(40, back.remaining().size(), "and survives the store's round trip");

    AiWorkflow resumed = CONTROLLER.open(back, "продължи", null, AiTask.GENERAL);
    assertEquals(40, resumed.remaining().size(), "«продължи» inherits all forty, not twenty-five");
  }

  /** The prompt gets a sample and a count; the state keeps the list. */
  @Test
  void theModelSeesAPreviewAndTheTotalRatherThanFortyIds() {
    List<String> refs = new ArrayList<>();
    for (int i = 1; i <= 40; i++) {
      refs.add("Upload#" + i);
    }

    String preview = AiWorkflow.preview(refs, true);

    assertTrue(preview.startsWith("Upload#1, Upload#2"), preview);
    assertTrue(preview.contains("и още " + (40 - AiWorkflow.PREVIEW_REFS)), preview);
    assertFalse(preview.contains("Upload#40"), "the tail is counted, not listed");
    assertTrue(preview.length() < 200, "a preview, not a list: " + preview.length() + " chars");
  }

  /** A short set is shown in full — the preview only kicks in when there is something to hide. */
  @Test
  void aShortSetIsShownWhole() {
    assertEquals(
        "Upload#1, Upload#2", AiWorkflow.preview(Arrays.asList("Upload#1", "Upload#2"), true));
    assertEquals("", AiWorkflow.preview(new ArrayList<>(), true));
  }

  /** The status line leads with the count, because that is the number a bulk job owes the user. */
  @Test
  void theStatusLineCountsRatherThanListsALargeSet() {
    AiWorkflow workflow = job("POSTED", "осчетоводи всички");
    for (int i = 1; i <= 40; i++) {
      workflow.addTarget("Upload#" + i);
    }
    workflow.markCompleted("Upload#1");

    String line = CONTROLLER.statusLine(workflow, true);

    assertTrue(line.contains("Остават 39"), line);
    assertTrue(line.contains("и още " + (39 - AiWorkflow.PREVIEW_REFS)), line);
    assertTrue(line.length() < 400, "a status line, not a report: " + line.length() + " chars");
  }

  // ── when the status is owed, and to what it listens ───────────────────────

  /**
   * Whether the user is told what remains is decided by the records, not by how the reply reads.
   *
   * <p>This used to be gated on a regex matching a promise, which meant the correction was
   * strongest against clumsy honesty — a reply that said «обработвам останалите» got the truth
   * appended — and absent against silence: a reply that simply did not mention the other thirty
   * invoices triggered nothing at all. The condition is now a fact about the job.
   */
  @Test
  void theStatusIsOwedWheneverWorkIsLeftOrStopped() {
    AiWorkflow remaining = job("POSTED", "осчетоводи трите", "Upload#1", "Upload#2");
    remaining.markCompleted("Upload#1");
    assertTrue(CONTROLLER.hasUnfinishedBusiness(remaining), "one target has not been reached");

    AiWorkflow stopped = job("POSTED", "осчетоводи двете", "Upload#1", "Upload#2");
    stopped.markCompleted("Upload#1");
    stopped.markBlocked(
        "Upload#2", AiBlockerClass.HUMAN_DECISION_REQUIRED.name(), "дублиран номер");
    assertTrue(
        CONTROLLER.hasUnfinishedBusiness(stopped), "nothing is left to try, but work stopped");
  }

  /** A job that finished owes nothing: repeating «Готови: …» on every turn is noise, not truth. */
  @Test
  void aFinishedJobIsNotAnnouncedAgain() {
    AiWorkflow done = job("POSTED", "осчетоводи двете", "Upload#1", "Upload#2");
    done.markCompleted("Upload#1");
    done.markCompleted("Upload#2");

    assertFalse(CONTROLLER.hasUnfinishedBusiness(done));
    assertFalse(CONTROLLER.hasUnfinishedBusiness(null));
    assertFalse(
        CONTROLLER.hasUnfinishedBusiness(new AiWorkflow()), "a job with no goal is not a job");
  }

  /**
   * The blocker's class comes from the refusal's own code, not from re-reading its sentence.
   *
   * <p>The message here says "duplicate", which the prose fallback reads as a human decision. The
   * code says otherwise and the code wins — so a bulk job keeps working on a record whose only
   * problem is one the assistant can fix.
   */
  @Test
  void aBlockersClassComesFromItsCodeNotItsWords() {
    String reason = "Duplicate expense lines: line 2 has no account.";

    assertEquals(
        AiBlockerClass.FIXABLE,
        AiBlockerClass.of(AiBlockerCode.NOT_READY_TO_POST.wire(), null, reason));
    assertEquals(
        AiBlockerClass.HUMAN_DECISION_REQUIRED,
        AiBlockerClass.of(null, null, reason),
        "without the code, the same refusal reads as a question");
  }

  /**
   * The real bound is bytes, not a record count. Reasons are the variable part — up to 160
   * characters each — so they are what the budget sheds, and a blocked record keeps the
   * classification that decides whether to ask the user.
   */
  @Test
  void theBlobIsCappedBySizeAndShedsReasonsBeforeTargets() {
    AiWorkflow workflow = job("POSTED", "осчетоводи всички");
    StringBuilder longReason = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      longReason.append('я');
    }
    for (int i = 1; i <= AiWorkflow.MAX_TARGETS; i++) {
      workflow.addTarget("Upload#" + i);
      workflow.markBlocked(
          "Upload#" + i, AiBlockerClass.HUMAN_DECISION_REQUIRED.name(), longReason.toString());
    }
    assertTrue(workflow.estimatedBytes() > AiWorkflow.MAX_STATE_BYTES, "the setup must overflow");

    workflow.capToBudget();

    assertTrue(
        workflow.estimatedBytes() <= AiWorkflow.MAX_STATE_BYTES,
        "still " + workflow.estimatedBytes() + " bytes");
    assertEquals(
        AiWorkflow.MAX_TARGETS, workflow.getTargets().size(), "no target was dropped to fit");
    assertEquals(
        AiBlockerClass.HUMAN_DECISION_REQUIRED.name(),
        workflow.getTargets().get(0).getBlocker(),
        "the classification is what decides whether to ask, so it must survive");
  }

  /** An ordinary job is nowhere near the budget and must come through untouched. */
  @Test
  void anOrdinaryJobIsNotTrimmedAtAll() {
    AiWorkflow workflow = job("POSTED", "осчетоводи ги", "Upload#941", "Upload#942");
    workflow.markBlocked("Upload#942", AiBlockerClass.HUMAN_DECISION_REQUIRED.name(), "дублиран №");

    workflow.capToBudget();

    assertEquals(2, workflow.getTargets().size());
    assertEquals("дублиран №", workflow.blocked().get(0).getReason());
  }

  /** Without a goal there is no job, and an empty job must not be carried or announced. */
  @Test
  void nothingIsRecordedForATurnWithNoDestination() {
    assertNull(CONTROLLER.open(null, "коя е активната фирма?", null, AiTask.GENERAL));
    assertNull(CONTROLLER.statusLine(null, true));
    assertNull(
        CONTROLLER.statusLine(job("POSTED", "осчетоводи"), true), "no targets, nothing to say");
  }
}

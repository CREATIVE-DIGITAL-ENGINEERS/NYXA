package com.axelor.apps.aiaccountant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.context.AiTargetIdentity;
import com.axelor.apps.aiaccountant.context.AiTargetRegistry;
import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.prompt.AiTask;
import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.route.AiExplicitness;
import com.axelor.apps.aiaccountant.route.AiRoute;
import com.axelor.apps.aiaccountant.route.AiScope;
import com.axelor.apps.aiaccountant.route.AiSetSelection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a list instruction authorises, now that the server is told its shape rather than reading its
 * words.
 *
 * <p>The behaviour these cover is the one the whitelist could not express: «осчетоводи фактурите»
 * (WHOLE, no quantifier word at all), «следващите две» (COUNT), «останалите» (REMAINING) and a
 * named subset (FILTERED) all resolve to exactly the records asked for, and a narrowing the server
 * has no query for is refused precisely instead of being answered with the whole queue.
 */
class AiWorkflowSetSelectionTest {

  /** Four eligible uploads, oldest first — the provider's own documented order. */
  private static final List<String> QUEUE =
      Arrays.asList("Upload#11", "Upload#12", "Upload#13", "Upload#14");

  /**
   * A controller whose queue, filters and ceiling are fixed, and whose target registry resolves
   * everything it is given — so the test is about the SELECTION, not about identity or permission.
   */
  private static AiWorkflowController controller(List<String> queue, String supportedFilter) {
    return new AiWorkflowController() {

      @Override
      protected List<String> worklistCandidates(AiDomain domain, String filter, int limit) {
        List<String> source =
            filter == null || filter.equals(supportedFilter)
                ? (filter == null ? queue : Collections.singletonList("Upload#13"))
                : Collections.emptyList();
        return new ArrayList<>(source.subList(0, Math.min(limit, source.size())));
      }

      @Override
      protected boolean supportsFilter(AiDomain domain, String filter) {
        return supportedFilter != null && supportedFilter.equals(filter);
      }

      @Override
      protected int maxAuthorisedTargets() {
        return 25;
      }

      @Override
      protected AiTargetRegistry targetRegistry() {
        return permissive();
      }
    };
  }

  /** Resolves and permits every reference, so nothing but the selection decides the outcome. */
  private static AiTargetRegistry permissive() {
    return new AiTargetRegistry() {
      @Override
      protected List<AiTargetIdentity> discover() {
        return Collections.emptyList();
      }

      @Override
      public String canonical(String ref) {
        return ref;
      }

      @Override
      public boolean writable(String ref) {
        return true;
      }
    };
  }

  private static AiRoute worklist(AiSetSelection selection, Integer count, String filter) {
    return new AiRoute(
        AiDomain.SUPPLIER_INVOICE,
        AiTask.POST_SUPPLIER_INVOICE,
        AiWorkflowIntent.Stage.POSTED,
        AiScope.WORKLIST,
        AiExplicitness.EXPLICIT,
        selection,
        count,
        filter);
  }

  private static AiWorkflow open(AiWorkflowController controller, AiRoute route, String message) {
    return open(controller, route, message, null);
  }

  private static AiWorkflow open(
      AiWorkflowController controller, AiRoute route, String message, AiWorkflow previous) {
    return controller.open(message, route, null, previous, 7L, "conv-1", Collections.emptyList());
  }

  /** «Осчетоводи фактурите» — no quantifier word anywhere, and it still means all of them. */
  @Test
  void wholeAuthorisesEveryEligibleRecordWithoutAQuantifierWord() {
    AiWorkflow job =
        open(
            controller(QUEUE, null),
            worklist(AiSetSelection.WHOLE, null, null),
            "осчетоводи фактурите");

    assertEquals(QUEUE, job.getAuthorisedTargets());
  }

  /** «Следващите две» — the head of the queue, in its own stable order, and no more. */
  @Test
  void countAuthorisesExactlyThatManyFromTheHeadOfTheQueue() {
    AiWorkflow job =
        open(
            controller(QUEUE, null),
            worklist(AiSetSelection.COUNT, 2, null),
            "осчетоводи следващите две");

    assertEquals(Arrays.asList("Upload#11", "Upload#12"), job.getAuthorisedTargets());
  }

  /** A count larger than one instruction may cover is refused, never truncated to the ceiling. */
  @Test
  void aCountOverTheCeilingIsRefusedRatherThanTrimmed() {
    AiWorkflow job =
        open(controller(QUEUE, null), worklist(AiSetSelection.COUNT, 99, null), "осчетоводи 99");

    assertTrue(job.getAuthorisedTargets().isEmpty());
    assertEquals(AiBlockerCode.WORKLIST_SELECTION_UNRESOLVED.wire(), job.getTargetRefusalCode());
  }

  /** «Останалите» — what is left of the job on record, never the whole queue again. */
  @Test
  void remainingTakesWhatIsLeftOfTheJobOnRecordAndNothingElse() {
    AiWorkflow previous = new AiWorkflow();
    previous.setGoal("POSTED");
    previous.setAuthorisedTargets(QUEUE);
    for (String ref : QUEUE) {
      previous.addTarget(ref);
    }
    previous.markCompleted("Upload#11");
    previous.markCompleted("Upload#12");

    AiWorkflow job =
        open(
            controller(QUEUE, null),
            worklist(AiSetSelection.REMAINING, null, null),
            "осчетоводи останалите",
            previous);

    assertEquals(Arrays.asList("Upload#13", "Upload#14"), job.getAuthorisedTargets());
  }

  /** «Останалите» with nothing outstanding names no record, and says so rather than widening. */
  @Test
  void remainingWithNoJobOnRecordIsRefusedPrecisely() {
    AiWorkflow job =
        open(
            controller(QUEUE, null),
            worklist(AiSetSelection.REMAINING, null, null),
            "осчетоводи останалите");

    assertTrue(job.getAuthorisedTargets().isEmpty());
    assertEquals(AiBlockerCode.WORKLIST_NOTHING_REMAINING.wire(), job.getTargetRefusalCode());
  }

  /** A subset a provider declares is resolved exactly, and only that subset is authorised. */
  @Test
  void aSupportedFilterAuthorisesOnlyTheMatchingRecords() {
    AiWorkflow job =
        open(
            controller(QUEUE, "NEEDS_REVIEW"),
            worklist(AiSetSelection.FILTERED, null, "NEEDS_REVIEW"),
            "осчетоводи тези за преглед");

    assertEquals(Collections.singletonList("Upload#13"), job.getAuthorisedTargets());
  }

  /** The defect the whitelist existed for: a supplier filter must never become the whole queue. */
  @Test
  void anUnsupportedFilterIsRefusedAndNeverWidenedToTheWholeQueue() {
    AiWorkflow job =
        open(
            controller(QUEUE, "NEEDS_REVIEW"),
            worklist(AiSetSelection.FILTERED, null, "supplier Лидл"),
            "осчетоводи фактурите на Лидл");

    assertTrue(job.getAuthorisedTargets().isEmpty());
    assertEquals(AiBlockerCode.WORKLIST_FILTER_UNSUPPORTED.wire(), job.getTargetRefusalCode());
    assertTrue(job.getTargetRefusalMessage().contains("supplier Лидл"));
  }

  /** No selection at all — the deterministic fallback route — is refused, never read as "all". */
  @Test
  void anUnstatedSelectionIsRefusedRatherThanGuessedAtAsTheWholeQueue() {
    AiWorkflow job =
        open(controller(QUEUE, null), worklist(null, null, null), "осчетоводи фактурите");

    assertTrue(job.getAuthorisedTargets().isEmpty());
    assertEquals(AiBlockerCode.WORKLIST_SELECTION_UNRESOLVED.wire(), job.getTargetRefusalCode());
    assertFalse(job.hasClosedTargets());
  }
}

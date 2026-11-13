package com.axelor.apps.aiaccounting.ai;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.aiaccountant.context.AiCompletionResolver;
import com.axelor.apps.aiaccounting.service.MoveSearchService;
import com.axelor.apps.aiaccounting.service.MoveViewService;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Whether a creation or repair job on a {@code Move#id} is actually finished.
 *
 * <h2>The defect this closes</h2>
 *
 * A goal-less {@code create_move} turn ends with two targets on the job: the synthetic {@code
 * Create:JOURNAL_ENTRY:<company>} destination it was authorised on, and {@code Move#602}, which
 * {@code AiWorkflowController.markWorkDone} adds from {@code AiWriteEffects} because the turn
 * really did write it. The first settles through {@code AiCreationCompletion}. The second had no
 * resolver at all — {@code AiCompletionRegistry} answered null, and the controller's documented
 * rule is that an unverifiable target stays active — so a journal entry that had been created
 * exactly as asked was reported as outstanding work for the rest of the conversation: the status
 * line kept naming it, {@code shouldKeepWorking} kept saying there was more to do, and the next
 * unrelated instruction could inherit the job instead of opening its own.
 *
 * <p>The invoice side has had {@link InvoiceCompletion} for this since creation existed; this is
 * the same rule for the entry side, and it is deliberately written to the same shape.
 *
 * <h2>What is NOT answered here, and why that matters for DAYBOOK</h2>
 *
 * {@code POST_JOURNAL_ENTRY} is absent. Its completion is the status itself, which {@code
 * AiWorkflowController.markCompleted} already reads from {@link MovePageRecordResolver#lifecycle} —
 * where DAYBOOK maps to {@code VALIDATED} and only ACCOUNTED maps to {@code POSTED}. Answering for
 * it here would put a second opinion beside that fact, and the one way this class could do damage
 * is by calling a daybook entry finished when the job's goal was POSTED. It cannot: a turn with a
 * lifecycle goal never reaches the goal-less path, and this resolver never claims a status.
 *
 * <p>So «създай статия и я осчетоводи» still settles only when the move is ACCOUNTED, or is
 * accurately blocked by whatever refused the posting; «създай статия» settles as soon as the entry
 * exists.
 */
@Singleton
public class MoveCompletion implements AiCompletionResolver {

  /** The two goal-less journal-entry jobs. See the class javadoc for why POST is not here. */
  private static final Set<String> ANSWERED_TASKS =
      new HashSet<>(Arrays.asList("CREATE_JOURNAL_ENTRY", "REPAIR_JOURNAL_ENTRY"));

  @Override
  public Boolean isComplete(String ref, String task) {
    if (task == null || !ANSWERED_TASKS.contains(task)) {
      return null;
    }
    Long id = moveId(ref);
    if (id == null) {
      // Not a Move# reference — not this resolver's to answer.
      return null;
    }
    Move move = find(id);
    if (move == null) {
      // Not this company's, or gone. Silence rather than false: an unverifiable outcome is not a
      // finished one, and the registry treats no answer as "still active".
      return null;
    }
    if (MoveRepository.STATUS_CANCELED == nullSafe(move.getStatusSelect())) {
      // A cancelled entry is not the entry that was asked for. Said as a real FALSE, not silence:
      // the resolver owns this reference and can see that the work did not end as requested.
      return Boolean.FALSE;
    }
    if ("CREATE_JOURNAL_ENTRY".equals(task)) {
      // Creation asks for an entry to exist, not for it to be postable — the same reading {@link
      // InvoiceCompletion} gives a created draft. Whether it can also be posted depends on the
      // period, the journal and the account configuration, none of which the user asked to have
      // changed and some of which no tool here can change. Holding the job open on them would
      // report a created entry as unfinished for ever.
      return Boolean.TRUE;
    }
    // A repair asks for the entry to be put right, and the one checkable postcondition an entry
    // has of its own — independent of the period, the journal and the company's configuration — is
    // that it balances. Debit equals credit or the entry cannot move at all, and an unbalanced
    // entry is exactly what «оправи статията» is usually about.
    return balanced(move);
  }

  /** Debit equals credit, read through the same summation the entry's own view reports. */
  protected Boolean balanced(Move move) {
    MoveViewService view = view();
    BigDecimal debit = view.totalDebit(move);
    BigDecimal credit = view.totalCredit(move);
    if (debit == null || credit == null) {
      return null;
    }
    return debit.compareTo(credit) == 0;
  }

  /** The entry, company-scoped. A seam so a test needs no running Guice injector. */
  protected Move find(long id) {
    return Beans.get(MoveSearchService.class).findScoped(id);
  }

  /** The view service, for the one summation. A seam, as above. */
  protected MoveViewService view() {
    return Beans.get(MoveViewService.class);
  }

  private static int nullSafe(Integer status) {
    return status == null ? -1 : status;
  }

  /** The id of a {@code Move#id} reference, or null for anything else. */
  static Long moveId(String ref) {
    if (ref == null) {
      return null;
    }
    String trimmed = ref.trim();
    int hash = trimmed.lastIndexOf('#');
    if (hash <= 0 || !"Move".equals(trimmed.substring(0, hash))) {
      return null;
    }
    try {
      return Long.parseLong(trimmed.substring(hash + 1).trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}

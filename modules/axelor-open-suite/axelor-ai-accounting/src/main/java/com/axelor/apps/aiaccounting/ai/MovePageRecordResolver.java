package com.axelor.apps.aiaccounting.ai;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.aiaccountant.context.AiPageRecordResolver;
import com.axelor.apps.aiaccountant.service.AiWorkflowIntent;
import com.axelor.apps.aiaccounting.service.MoveSearchService;
import com.axelor.apps.aiaccounting.service.MoveViewService;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.math.BigDecimal;

/**
 * Answers "what is this move, and how far along is it?" for an accounting move.
 *
 * <h2>Why a second resolver rather than a branch in the first</h2>
 *
 * {@code OcrPageRecordResolver} — in the OCR module, which this one deliberately does not depend on
 * — already recognises a {@code Move}, but only to point back at the OCR upload that produced it,
 * and only for moves that came from OCR. A move entered by hand under Accounting → Moves has no
 * upload behind it, so that resolver returns null for it and the model gets nothing. The registry
 * collects every resolver's answer for {@code describe} and takes the first non-null for {@code
 * state} and {@code lifecycle}, so the two coexist: for an OCR move the other one explains its
 * origin, and this one states its accounting status.
 *
 * <p>Company-scoped like everything else, and never throws — the registry swallows failures, and a
 * page context that cannot be built must leave the turn working exactly as it did before.
 */
@Singleton
public class MovePageRecordResolver implements AiPageRecordResolver {

  static final String MOVE_MODEL = "com.axelor.apps.account.db.Move";

  @Override
  public String describe(String model, long recordId) {
    Move move = moveFor(model, recordId);
    if (move == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    sb.append("This is accounting move moveId=")
        .append(move.getId())
        .append(move.getReference() == null ? "" : " (" + move.getReference() + ")")
        .append(", status ")
        .append(MoveViewService.statusName(move.getStatusSelect()))
        .append(". Use moveId=")
        .append(move.getId())
        .append(" directly and do NOT call search_moves.");

    // Whether it balances is the single question a move is most often opened for, and it is one
    // Java can answer before the turn starts. Saying it here costs a dozen tokens and saves a whole
    // get_move round trip; saying WHY it does not balance still needs the read tool.
    if (MoveViewService.isPostable(move)) {
      MoveViewService view = Beans.get(MoveViewService.class);
      BigDecimal difference = view.totalDebit(move).subtract(view.totalCredit(move));
      sb.append(" It is not yet finally posted; debit-credit difference is ")
          .append(difference.toPlainString())
          .append(difference.signum() == 0 ? " (balanced)." : " (NOT balanced).");
    }
    return sb.toString();
  }

  /** The move's position in one clause, for a truncation summary. */
  @Override
  public String state(String model, long recordId) {
    Move move = moveFor(model, recordId);
    if (move == null || move.getStatusSelect() == null) {
      return null;
    }
    String label = "move " + (move.getReference() == null ? move.getId() : move.getReference());
    switch (move.getStatusSelect()) {
      case MoveRepository.STATUS_NEW:
        return label + " is still NEW (not posted)";
      case MoveRepository.STATUS_SIMULATED:
        return label + " is SIMULATED (not posted)";
      case MoveRepository.STATUS_DAYBOOK:
        return label + " is in DAYBOOK but NOT finally accounted";
      case MoveRepository.STATUS_ACCOUNTED:
        return label + " is ACCOUNTED";
      case MoveRepository.STATUS_CANCELED:
        return label + " is CANCELED";
      default:
        return null;
    }
  }

  /**
   * The same status as a closed token, so the workflow can tell whether the goal was reached.
   *
   * <h2>The one judgement call here</h2>
   *
   * DAYBOOK maps to {@code VALIDATED}, not {@code POSTED}. A daybook entry is recorded and
   * reversible and is not yet in the final books, so reporting «осчетоводено» for it would be the
   * same class of untruth this vocabulary exists to prevent — and a job whose goal is POSTED must
   * keep the move on its remaining list until the second call has run. SIMULATED maps to {@code
   * DRAFT} for the same reason: a simulation is explicitly not an accounting fact.
   */
  @Override
  public String lifecycle(String model, long recordId) {
    Move move = moveFor(model, recordId);
    if (move == null || move.getStatusSelect() == null) {
      return null;
    }
    switch (move.getStatusSelect()) {
      case MoveRepository.STATUS_NEW:
      case MoveRepository.STATUS_SIMULATED:
        return AiWorkflowIntent.DRAFT;
      case MoveRepository.STATUS_DAYBOOK:
        return AiWorkflowIntent.VALIDATED;
      case MoveRepository.STATUS_ACCOUNTED:
        return AiWorkflowIntent.POSTED;
      case MoveRepository.STATUS_CANCELED:
        return AiWorkflowIntent.CANCELLED;
      default:
        return null;
    }
  }

  /** The move an id names, company-scoped, or null for any other model. */
  protected Move moveFor(String model, long recordId) {
    if (!MOVE_MODEL.equals(model)) {
      return null;
    }
    return Beans.get(MoveSearchService.class).findScoped(recordId);
  }
}

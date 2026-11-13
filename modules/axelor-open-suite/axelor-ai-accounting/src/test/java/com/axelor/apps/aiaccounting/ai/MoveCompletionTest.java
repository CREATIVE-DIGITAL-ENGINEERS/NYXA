package com.axelor.apps.aiaccounting.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.aiaccounting.service.MoveViewService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * A created journal entry settles its job; a posting goal still has to be reached.
 *
 * <p>A goal-less {@code create_move} leaves the move on the job, so a resolver has to answer for
 * it; otherwise the entry is reported as outstanding work for the rest of the conversation even
 * though the {@code Create:JOURNAL_ENTRY:…} destination has settled.
 */
class MoveCompletionTest {

  private static Move move(int status, String debit, String credit) {
    Move move = new Move();
    move.setId(602L);
    move.setStatusSelect(status);
    move.setMoveLineList(null);
    return move;
  }

  /** A resolver with the lookup and the summation fixed, so no Guice injector is needed. */
  private static MoveCompletion completion(Move found, String debit, String credit) {
    return new MoveCompletion() {
      @Override
      protected Move find(long id) {
        return found;
      }

      @Override
      protected Boolean balanced(Move m) {
        return new BigDecimal(debit).compareTo(new BigDecimal(credit)) == 0;
      }

      @Override
      protected MoveViewService view() {
        throw new AssertionError("balanced() is overridden; the view must not be reached");
      }
    };
  }

  @Test
  void aCreatedEntrySettlesAsSoonAsItExists() {
    Move created = move(MoveRepository.STATUS_NEW, "100", "100");

    assertEquals(
        Boolean.TRUE,
        completion(created, "100", "100").isComplete("Move#602", "CREATE_JOURNAL_ENTRY"));
  }

  /**
   * A NEW entry is not postable yet, and that must not hold a creation job open — the period, the
   * journal and the account configuration are not what «създай статия» asked to have changed.
   */
  @Test
  void aCreatedEntryDoesNotHaveToBePostableToSettle() {
    Move unbalanced = move(MoveRepository.STATUS_NEW, "100", "90");

    assertEquals(
        Boolean.TRUE,
        completion(unbalanced, "100", "90").isComplete("Move#602", "CREATE_JOURNAL_ENTRY"));
  }

  @Test
  void aRepairSettlesOnlyWhenTheEntryBalances() {
    assertEquals(
        Boolean.TRUE,
        completion(move(MoveRepository.STATUS_NEW, "100", "100"), "100", "100")
            .isComplete("Move#602", "REPAIR_JOURNAL_ENTRY"));
    assertEquals(
        Boolean.FALSE,
        completion(move(MoveRepository.STATUS_NEW, "100", "90"), "100", "90")
            .isComplete("Move#602", "REPAIR_JOURNAL_ENTRY"));
  }

  /** A cancelled entry is not the entry that was asked for — a real FALSE, not silence. */
  @Test
  void aCancelledEntryNeverSettles() {
    assertEquals(
        Boolean.FALSE,
        completion(move(MoveRepository.STATUS_CANCELED, "100", "100"), "100", "100")
            .isComplete("Move#602", "CREATE_JOURNAL_ENTRY"));
  }

  /**
   * The truth this class must not touch: DAYBOOK is not ACCOUNTED. A posting job's completion is
   * the status itself, read by {@code AiWorkflowController} from {@code
   * MovePageRecordResolver.lifecycle} — so POST_JOURNAL_ENTRY gets no answer here, and a daybook
   * entry can never be reported as posted by this resolver.
   */
  @Test
  void aPostingJobIsNotAnsweredHereSoDaybookIsNeverReportedAsAccounted() {
    assertNull(
        completion(move(MoveRepository.STATUS_DAYBOOK, "100", "100"), "100", "100")
            .isComplete("Move#602", "POST_JOURNAL_ENTRY"));
    assertNull(
        completion(move(MoveRepository.STATUS_ACCOUNTED, "100", "100"), "100", "100")
            .isComplete("Move#602", "POST_JOURNAL_ENTRY"));
  }

  /** Not this resolver's reference, and not this company's record: silence, never a verdict. */
  @Test
  void aForeignReferenceOrAnUnreachableRecordIsLeftToSomebodyElse() {
    assertNull(
        completion(move(MoveRepository.STATUS_NEW, "1", "1"), "1", "1")
            .isComplete("Invoice#651", "CREATE_JOURNAL_ENTRY"));
    assertNull(completion(null, "1", "1").isComplete("Move#602", "CREATE_JOURNAL_ENTRY"));
    assertTrue(MoveCompletion.moveId("Upload#946") == null);
    assertEquals(Long.valueOf(602L), MoveCompletion.moveId("Move#602"));
  }
}

package com.axelor.apps.aiaccounting.service;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.move.MoveValidateService;
import com.axelor.apps.aiaccountant.security.AiDraftOnlyRefusal;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiaccountant.service.AiBlocked;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Posts an accounting move — the Accounting button on the Move form, and nothing else.
 *
 * <h2>Axelor decides, this relays</h2>
 *
 * The whole operation is {@code MoveValidateService.accounting(move)}, which is the same call
 * {@code MoveController.accounting} makes on the same entity. Every rule that matters is in there
 * and stays there: whether the company runs a daybook, whether the period is open, whether this
 * user's roles allow the journal, whether the entry balances, whether an account requires tax.
 * Nothing here re-implements or pre-empts any of it, and in particular nothing here sets {@code
 * statusSelect} — the status is a consequence of the service having run, never something written by
 * hand.
 *
 * <p>That also means the outcome is not always ACCOUNTED. With daybook mode on for the company and
 * the journal, the same call moves a NEW entry to DAYBOOK instead, and a second call takes it the
 * rest of the way — for a user the posting gate (nyxa-security {@code MovePostingGuard}) admits,
 * i.e. client.admin or an administrator; anyone else is refused and the move stays in DAYBOOK. The
 * result reports the status Axelor actually left ({@code statusReached}) and whether that is final
 * ({@code accounted}), rather than the one that was asked for.
 *
 * <h2>What it refuses</h2>
 *
 * Only status, and only before calling. An already-ACCOUNTED move is not posted twice and a
 * CANCELED one is not revived; both are stated rather than attempted. Everything else — the
 * preconditions, the period, the roles — is Axelor's refusal, relayed with its own wording because
 * that wording names the actual accounting reason and a paraphrase would send someone looking for
 * the wrong problem.
 */
@Singleton
public class MoveAccountingService {

  private static final Logger LOG = LoggerFactory.getLogger(MoveAccountingService.class);

  /**
   * Runs the checks without writing, so "can this be posted?" costs no draft and no rollback.
   *
   * <p>Read-only by construction: {@code checkPreconditions} is the same method {@code accounting}
   * calls first, and calling it alone is how the form's own check button behaves.
   */
  public Map<String, Object> check(Move move) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("moveId", move.getId());
    out.put("reference", move.getReference());
    out.put("status", MoveViewService.statusName(move.getStatusSelect()));
    if (!MoveViewService.isPostable(move)) {
      out.put("postable", false);
      out.put(
          "blocker",
          "A move in status "
              + MoveViewService.statusName(move.getStatusSelect())
              + " cannot be posted.");
      return out;
    }
    try {
      Beans.get(MoveValidateService.class).checkPreconditions(move);
      out.put("postable", true);
    } catch (Exception e) {
      out.put("postable", false);
      out.put("blocker", message(e));
    }
    return out;
  }

  /**
   * Posts it.
   *
   * <p>Transactional and rolling back on anything, so a refusal part-way through leaves the move
   * exactly as it was found — which is what lets the refusal say "nothing was posted" truthfully.
   */
  @Transactional(rollbackOn = Exception.class)
  public Map<String, Object> post(Move move) {
    requirePostableStatus(move);

    try {
      Beans.get(MoveValidateService.class).accounting(move);
      // Inside the try: some refusals only surface when the change is flushed, notably the
      // drafts-only guard (AiDraftOnlyPostingGuard), which refuses making the move ACCOUNTED from
      // an AI tool. Outside the try it escaped as a raw PersistenceException, so the model never
      // learned why the post was refused.
      JPA.flush();
    } catch (Exception e) {
      if (AiDraftOnlyRefusal.find(e) != null) {
        throw new AiBlocked(AiBlockerCode.AXELOR_REFUSED_POSTING, AiDraftOnlyRefusal.MESSAGE, e);
      }
      throw new AiBlocked(
          AiBlockerCode.AXELOR_REFUSED_MOVE,
          "Axelor refused to post move "
              + reference(move)
              + ": "
              + message(e)
              + " Nothing was posted.",
          e);
    }

    JPA.refresh(move);
    AiWriteEffects.touched(Move.class.getName(), move.getId());
    LOG.info(
        "AI Accountant: move {} posted by {} — status {}.",
        move.getId(),
        AiExecutionContext.callerDescription(),
        MoveViewService.statusName(move.getStatusSelect()));

    return reachedResult(move, null);
  }

  /** Wire name of the status the call actually left the move in. */
  public static final String STATUS_REACHED = "statusReached";

  /** Wire name of the flag that is true only when the move is definitively ACCOUNTED. */
  public static final String ACCOUNTED = "accounted";

  /**
   * The result of a post that moved the move forward, read off the move as it now is.
   *
   * <h2>Why there is no "posted" flag</h2>
   *
   * In a company that runs a daybook the same call takes NEW to DAYBOOK, which is validated into
   * the daybook but not definitive. A {@code posted: true} beside DAYBOOK read as "in the books"
   * and the reply said so. So the result names the status reached and, separately, whether that
   * status is the final one — two facts, never one word standing for both.
   *
   * <p>Shared with {@code PostMoveTool}'s lost-answer verifier, so both report the same shape.
   *
   * @param note appended to the next step, or null — e.g. that the outcome was confirmed by
   *     re-reading the move rather than reported by the write itself
   */
  public static Map<String, Object> reachedResult(Move move, String note) {
    return reachedResult(move, Beans.get(MoveViewService.class).detail(move), note);
  }

  /** As above, with the move's detail already read — the seam a test uses without an injector. */
  static Map<String, Object> reachedResult(Move move, Map<String, Object> detail, String note) {
    Integer status = move.getStatusSelect();
    boolean accounted = status != null && status == MoveRepository.STATUS_ACCOUNTED;
    boolean daybook = status != null && status == MoveRepository.STATUS_DAYBOOK;
    Map<String, Object> out = new LinkedHashMap<>();
    out.put(STATUS_REACHED, MoveViewService.statusName(status));
    out.put(ACCOUNTED, accounted);
    out.putAll(detail);
    String nextStep;
    if (accounted) {
      nextStep = "The move is ACCOUNTED — definitively in the books. Do not post it again.";
    } else if (daybook) {
      // Not a failure and not a partial success: it is what daybook mode does.
      nextStep =
          "The move is now in DAYBOOK: validated into the daybook, NOT accounted definitively."
              + " Taking it to ACCOUNTED is the user's, from the move form: the assistant only"
              + " prepares drafts, so do not call post_move on it again. Staying in DAYBOOK for"
              + " review is the normal outcome, not an error.";
    } else {
      nextStep =
          "Axelor left the move in "
              + MoveViewService.statusName(status)
              + ", so it is neither in the daybook nor in the books. Report that status as it is.";
    }
    out.put("nextStep", note == null ? nextStep : nextStep + " " + note);
    return out;
  }

  /**
   * The one thing refused before Axelor is asked.
   *
   * <p>Status is checked here rather than left to {@code accounting} because Axelor's own message
   * for a second posting is about internals, while the useful answer — it is already in the books,
   * there is nothing to do — is one this layer can state plainly.
   */
  protected void requirePostableStatus(Move move) {
    Integer status = move.getStatusSelect();
    if (status == null) {
      throw new AiBlocked(
          AiBlockerCode.MOVE_INVALID_STATUS,
          "Move " + reference(move) + " has no status. Nothing was posted.");
    }
    if (status == MoveRepository.STATUS_ACCOUNTED) {
      throw new AiBlocked(
          AiBlockerCode.MOVE_INVALID_STATUS,
          "Move "
              + reference(move)
              + " is already ACCOUNTED — it is in the books and there is nothing to post. Say so"
              + " rather than trying again.");
    }
    if (status == MoveRepository.STATUS_CANCELED) {
      throw new AiBlocked(
          AiBlockerCode.MOVE_INVALID_STATUS,
          "Move "
              + reference(move)
              + " is CANCELED and cannot be posted. Nothing here reopens a cancelled entry.");
    }
  }

  private static String reference(Move move) {
    return move.getReference() == null ? String.valueOf(move.getId()) : move.getReference();
  }

  /**
   * The innermost non-empty message: a refusal raised during the flush (e.g. a Hibernate guard) may
   * arrive wrapped, and the wrapper's text says nothing the user can act on.
   */
  private static String message(Exception e) {
    String message = null;
    for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t.getMessage() != null && !t.getMessage().trim().isEmpty()) {
        message = t.getMessage().trim();
      }
    }
    return message == null ? e.getClass().getSimpleName() : message;
  }
}

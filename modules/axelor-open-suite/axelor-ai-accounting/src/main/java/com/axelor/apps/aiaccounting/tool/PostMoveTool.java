package com.axelor.apps.aiaccounting.tool;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.aiaccountant.resilience.AiWriteOutcome;
import com.axelor.apps.aiaccountant.resilience.AiWriteProbe;
import com.axelor.apps.aiaccountant.resilience.AiWriteVerifier;
import com.axelor.apps.aiaccountant.service.AiBlocked;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccountant.tool.AiWriteMode;
import com.axelor.apps.aiaccounting.service.MoveAccountingService;
import com.axelor.apps.aiaccounting.service.MoveSearchService;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.util.EnumSet;
import java.util.Set;

/**
 * WRITE tool: post an accounting move — the Accounting button, through Axelor's own service.
 *
 * <p>The highest-consequence tool for a journal entry: it puts the document in the books and cannot
 * be undone from the chat. Every rule about whether it may happen belongs to {@code
 * MoveValidateService} and stays there.
 */
@Singleton
public class PostMoveTool implements AiTool, AiWriteVerifier {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "post_move";
  }

  @Override
  public String description() {
    return "Validates an accounting move through Axelor's real lifecycle "
        + "(MoveValidateService.accounting) — the button on the Move form. The assistant only "
        + "prepares drafts: it never makes an entry ACCOUNTED. Where the company runs a daybook "
        + "this call moves NEW to DAYBOOK; everywhere else it is refused, nothing is posted, and "
        + "the user validates the move themselves from its form — tell them so. Only for a move "
        + "the user asked to post. Also refused with the reason when the entry does not balance, "
        + "the period is closed, the journal's roles do not allow this user, an account needs a "
        + "tax that is missing, or Axelor would otherwise have asked a question. A move that is "
        + "already ACCOUNTED is not posted again — say so instead. The result reports "
        + "statusReached and accounted (true only for ACCOUNTED); never call DAYBOOK accounted or "
        + "in the final books. Read the move with get_move first when you are not sure it is "
        + "ready.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props
        .putObject("moveId")
        .put("type", "integer")
        .put("description", "The id of the move to post.");
    schema.putArray("required").add("moveId");
    schema.put("additionalProperties", false);
    return schema;
  }

  @Override
  public AiCapability capability() {
    return AiCapability.JOURNAL_ENTRY;
  }

  @Override
  public AiToolKind kind() {
    return AiToolKind.WRITE;
  }

  /** Never unattended: this is the entry into the books. */
  @Override
  public Set<AiWriteMode> writeModes() {
    return EnumSet.of(AiWriteMode.CONFIRMED);
  }

  /** Last (40), matching ventilation: everything else about the entry has to have landed first. */
  @Override
  public int writeOrder() {
    return 40;
  }

  /**
   * The move this call is aimed at, so a refusal on one entry does not stop the others.
   *
   * <p>The default implementation reads {@code uploadId}, which this tool does not take. Without
   * the override every failed posting in a batch would abort the whole response, including entries
   * that have nothing to do with the one that failed.
   */
  @Override
  public String targetKey(JsonNode args) {
    if (args == null) {
      return null;
    }
    JsonNode moveId = args.get("moveId");
    if (moveId == null || moveId.isNull()) {
      return null;
    }
    String text = moveId.asText("").trim();
    return text.isEmpty() ? null : "Move#" + text;
  }

  /**
   * The status the move is in before the post, which is what makes the postcondition exact.
   *
   * <p>Without it, DAYBOOK afterwards is unreadable: in a company that runs a daybook the first
   * post takes NEW to DAYBOOK and the second takes DAYBOOK to ACCOUNTED, so the same reading means
   * "it worked" or "nothing happened" depending on where it started — and posting is the operation
   * where guessing is least acceptable.
   */
  @Override
  public Object beforeExecute(JsonNode args) {
    Move move = scoped(args);
    return move == null ? null : move.getStatusSelect();
  }

  /**
   * After a lost answer: did the move actually go into the books?
   *
   * <h2>An exact postcondition, read against an exact precondition</h2>
   *
   * A posting has one effect and it is a single field. Compared with the status captured before the
   * call, the reading is unambiguous:
   *
   * <ul>
   *   <li><b>unchanged</b> — the transaction rolled back and nothing was posted. Provable, and the
   *       only case a retry may be built on.
   *   <li><b>moved forward</b> — NEW to DAYBOOK, NEW to ACCOUNTED, DAYBOOK to ACCOUNTED. The post
   *       landed and the entry is in the books; reported as the ordinary success it is, in the same
   *       shape, so the model tells the user what actually happened instead of sending them to look
   *       for a move that is already posted.
   *   <li><b>anything else</b> — a status this operation does not produce, or a move that can no
   *       longer be read. Unknown, and the turn says so.
   * </ul>
   *
   * <p>Strictly speaking a move that moved forward could have been posted by somebody else in the
   * same seconds. The claim made here survives that: it says the move is posted and shows its
   * current state, both read from the database, and it never says "I posted it". What matters for
   * safety is the other direction — never reporting a post that did not happen, and never retrying
   * one that did — and the unchanged case is what carries that, provably.
   */
  @Override
  public AiWriteOutcome verifyAfterAmbiguousFailure(JsonNode args, AiWriteProbe probe) {
    Integer before = probe == null ? null : (Integer) probe.toolState();
    Move move = scoped(args);
    if (before == null || move == null || move.getStatusSelect() == null) {
      return AiWriteOutcome.unknown();
    }
    int now = move.getStatusSelect();
    if (now == before) {
      return AiWriteOutcome.notWritten();
    }
    boolean posted = now == MoveRepository.STATUS_DAYBOOK || now == MoveRepository.STATUS_ACCOUNTED;
    if (!posted) {
      // CANCELED, or something this operation does not produce. Not ours to interpret.
      return AiWriteOutcome.unknown();
    }
    // The same shape the write itself returns: statusReached plus accounted, never one "posted"
    // flag standing for both DAYBOOK and ACCOUNTED.
    return AiWriteOutcome.completed(
        MoveAccountingService.reachedResult(
            move,
            "NOTE: the connection was lost while the move was being posted, so this was confirmed"
                + " by re-reading the move rather than reported by the write itself."));
  }

  /** The move this call names, in the active company, or null. Never throws. */
  protected Move scoped(JsonNode args) {
    try {
      return Beans.get(MoveSearchService.class).findScoped(AiToolArgs.requireId(args, "moveId"));
    } catch (RuntimeException e) {
      return null;
    }
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    long moveId = AiToolArgs.requireId(args, "moveId");
    Move move = Beans.get(MoveSearchService.class).findScoped(moveId);
    // Same message for absent and another company's, so an id cannot be used to probe existence.
    if (move == null) {
      throw new AiToolException("No move with id " + moveId + ".");
    }
    if (!Beans.get(JpaSecurity.class)
        .isPermitted(JpaSecurity.CAN_WRITE, Move.class, move.getId())) {
      throw new AiToolException("You do not have permission to post move " + moveId + ".");
    }

    try {
      return Beans.get(MoveAccountingService.class).post(move);
    } catch (AiBlocked e) {
      throw e.asToolException();
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new AiToolException(e.getMessage());
    }
  }
}

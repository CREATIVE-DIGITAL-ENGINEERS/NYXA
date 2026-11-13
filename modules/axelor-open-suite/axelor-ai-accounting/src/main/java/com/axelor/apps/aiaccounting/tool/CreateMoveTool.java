package com.axelor.apps.aiaccounting.tool;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.aiaccountant.context.AiCreationTarget;
import com.axelor.apps.aiaccountant.resilience.AiWriteOutcome;
import com.axelor.apps.aiaccountant.resilience.AiWriteProbe;
import com.axelor.apps.aiaccountant.resilience.AiWriteVerifier;
import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.service.AiBlocked;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccountant.tool.AiWriteMode;
import com.axelor.apps.aiaccounting.service.MoveCreationService;
import com.axelor.apps.aiaccounting.service.MoveSearchService;
import com.axelor.apps.aiaccounting.service.MoveViewService;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * WRITE tool: create a manual accounting move in NEW status.
 *
 * <p>Creates a draft and stops there. Posting is {@code post_move}, deliberately separate: writing
 * a journal entry down and putting it in the books are two decisions, and the second one is the
 * irreversible one.
 */
@Singleton
public class CreateMoveTool implements AiTool, AiWriteVerifier {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "create_move";
  }

  @Override
  public String description() {
    return "Creates a manual accounting move (journal entry) in NEW status — a draft, exactly as "
        + "if it had been entered on the Move form and saved. Does NOT post it: the entry is not "
        + "in the books until post_move runs. Give the journal code and at least two lines, each "
        + "with an account code, a POSITIVE amount and a side ('debit' or 'credit'); a negative "
        + "amount is expressed by putting it on the other side. Total debit must equal total "
        + "credit — the tool refuses and creates NOTHING when it does not, and reports both "
        + "totals so the lines can be corrected in one go. The journal and every account are "
        + "looked up by the exact code in the active company; an unknown code is refused and is "
        + "NEVER substituted with a similar one. Only for an entry the user actually asked for, "
        + "with the accounts they named or that were read off a document — never invent an "
        + "account code.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props
        .putObject("journalCode")
        .put("type", "string")
        .put(
            "description",
            "Exact journal code for the entry, e.g. 'OD' for miscellaneous operations. Must exist"
                + " in the active company.");
    props
        .putObject("date")
        .put("type", "string")
        .put(
            "description",
            "Move date, ISO yyyy-MM-dd. Defaults to today. Its accounting period must be open.");
    props
        .putObject("partnerId")
        .put("type", "integer")
        .put("description", "Optional partner for the whole move, when the entry has one.");
    props
        .putObject("origin")
        .put("type", "string")
        .put("description", "Source document reference, e.g. the invoice or statement number.");
    props
        .putObject("originDate")
        .put("type", "string")
        .put("description", "Date of the source document, ISO yyyy-MM-dd.");
    props
        .putObject("description")
        .put("type", "string")
        .put("description", "What the entry is for, in the accountant's own words.");

    ObjectNode lines = props.putObject("lines");
    lines.put("type", "array");
    lines.put(
        "description",
        "The entry's lines, at least two, in the order they should be numbered. Debits and credits"
            + " must total the same.");
    ObjectNode line = lines.putObject("items");
    line.put("type", "object");
    ObjectNode lineProps = line.putObject("properties");
    lineProps
        .putObject("accountCode")
        .put("type", "string")
        .put(
            "description",
            "Exact account code from this company's chart, e.g. '401' or '602'. Never invented.");
    lineProps
        .putObject("amount")
        .put("type", "number")
        .put("description", "The line amount, always POSITIVE. The side decides its direction.");
    lineProps
        .putObject("side")
        .put("type", "string")
        .put("description", "'debit' or 'credit'. One line carries one side, never both.")
        .putArray("enum")
        .add("debit")
        .add("credit");
    lineProps
        .putObject("partnerId")
        .put("type", "integer")
        .put("description", "Optional partner for this line only; defaults to the move's partner.");
    lineProps
        .putObject("description")
        .put("type", "string")
        .put("description", "Line narrative; defaults to the move description.");
    lineProps
        .putObject("dueDate")
        .put("type", "string")
        .put("description", "Optional due date for a payable/receivable line, ISO yyyy-MM-dd.");
    line.putArray("required").add("accountCode").add("amount").add("side");
    line.put("additionalProperties", false);

    schema.putArray("required").add("journalCode").add("lines");
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

  /** Chat only. An entry nobody asked for is not something an unattended pipeline should write. */
  @Override
  public Set<AiWriteMode> writeModes() {
    return EnumSet.of(AiWriteMode.CONFIRMED);
  }

  /** Before posting (40): the entry has to exist before it can go into the books. */
  @Override
  public int writeOrder() {
    return 20;
  }

  /**
   * The creation destination: a journal entry in this company, named before one exists.
   *
   * <p>Null used to be the answer, and it meant {@code AiWriteAuthorisation.checkTargets} refused
   * every call — a null target is refused once a job has a closed set, and a job with no set is
   * refused outright — so «създай статия …» could not be carried out however explicitly it was
   * asked for. The job freezes this same reference from the route's own task; see {@link
   * AiCreationTarget}.
   *
   * <p>A failure still aborts the rest of the response, because every other write in a creation
   * turn is about the entry that was supposed to exist: the batch runner aborts on a failed target
   * that later calls depend on, and here they all do.
   */
  @Override
  public String targetKey(JsonNode args) {
    return AiCreationTarget.ref(AiDomain.JOURNAL_ENTRY);
  }

  /**
   * After a lost answer: is the entry this call was writing actually in the database?
   *
   * <h2>The identity exists before the outcome does</h2>
   *
   * This is the case a fingerprint can never answer — there was no record before the call, so there
   * is nothing to compare and the generic reconciliation can only ever say "unknown". Every
   * timed-out {@code create_move} would end as an uncertain outcome, and the user would be sent
   * looking for an entry nobody could name.
   *
   * <p>But an identity does exist by then. {@code MoveCreationService} saves through the repository
   * and flushes inside its transaction, and reports the saved id through {@code AiWriteEffects}
   * before that transaction commits — so the primary key is known while only the <em>commit's</em>
   * outcome is in doubt. Asking whether that exact row is now in the database is an existence test
   * on a server-generated key: it exists, so the commit landed; it does not, so the transaction
   * rolled back and nothing was created.
   *
   * <p>Note what is deliberately <b>not</b> done: no search for a move with this journal, date and
   * amounts. Two identical entries are a legitimate thing for an accountant to want, so such a
   * search would report the wrong one as often as the right one, and a wrong "created" ends the
   * conversation on a duplicate nobody looks for again.
   *
   * <p>The read is company-scoped through {@code MoveSearchService.findScoped}, so a row this
   * company cannot see never confirms a write made in its books.
   */
  @Override
  public AiWriteOutcome verifyAfterAmbiguousFailure(JsonNode args, AiWriteProbe probe) {
    Long created = probe == null ? null : probe.newId(Move.class);
    if (created == null) {
      // The attempt never got as far as saving, so nothing was reported and there is no identity to
      // check. That is itself informative — the failure happened before the write — but not proof,
      // because the reporting call sits inside the same transaction and a failure between the save
      // and it would look identical. Unknown, and the caller stops.
      return AiWriteOutcome.unknown();
    }
    Move move = Beans.get(MoveSearchService.class).findScoped(created);
    if (move == null) {
      // The row is not there. The transaction rolled back and no entry was created — a proof, not
      // an absence of evidence, because the id being checked is the one the attempt itself saved.
      return AiWriteOutcome.notWritten();
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("created", true);
    out.putAll(Beans.get(MoveViewService.class).detail(move));
    out.put(
        "nextStep",
        "The move is a NEW draft and is NOT in the books. Show it to the user and post it with"
            + " post_move only if they ask for that. NOTE: the connection was lost while it was"
            + " being created, so the entry was confirmed by re-reading it rather than reported by"
            + " the write itself — show the user the lines before anything else is done to it.");
    return AiWriteOutcome.completed(out);
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    if (!Beans.get(JpaSecurity.class).isPermitted(JpaSecurity.CAN_CREATE, Move.class)) {
      throw new AiToolException("You do not have permission to create accounting moves.");
    }

    MoveCreationService.MoveRequest request = new MoveCreationService.MoveRequest();
    request.journalCode = AiToolArgs.requireText(args, "journalCode");
    request.date = date(args, "date");
    request.originDate = date(args, "originDate");
    request.partnerId = AiToolArgs.optId(args, "partnerId");
    request.origin = AiToolArgs.optText(args, "origin");
    request.description = AiToolArgs.optText(args, "description");

    JsonNode lines = args == null ? null : args.get("lines");
    if (lines == null || !lines.isArray() || lines.size() == 0) {
      throw new AiToolException(
          "'lines' must be an array with at least two lines — one debit and one credit.");
    }
    for (int i = 0; i < lines.size(); i++) {
      request.lines.add(line(lines.get(i), i + 1));
    }

    try {
      return Beans.get(MoveCreationService.class).create(request);
    } catch (AiBlocked e) {
      // A named blocker: its code travels with the refusal, so an unbalanced entry is recognised as
      // something to correct and retry rather than as a question for the user.
      throw e.asToolException();
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new AiToolException(e.getMessage());
    }
  }

  /** One line, with the side turned into a boolean here so the service can never see both. */
  protected MoveCreationService.LineRequest line(JsonNode node, int number) throws AiToolException {
    if (node == null || !node.isObject()) {
      throw new AiToolException("Line " + number + " is not an object.");
    }
    MoveCreationService.LineRequest line = new MoveCreationService.LineRequest();
    line.accountCode = AiToolArgs.requireText(node, "accountCode");

    JsonNode amount = node.get("amount");
    if (amount == null || amount.isNull() || !amount.isNumber()) {
      throw new AiToolException("Line " + number + " needs a numeric 'amount'.");
    }
    line.amount = amount.decimalValue();
    if (line.amount.signum() <= 0) {
      throw new AiToolException(
          "Line "
              + number
              + " has amount "
              + line.amount.toPlainString()
              + ". Amounts are always positive; put it on the other side instead.");
    }

    String side = AiToolArgs.requireText(node, "side");
    if ("debit".equalsIgnoreCase(side)) {
      line.debit = true;
    } else if ("credit".equalsIgnoreCase(side)) {
      line.debit = false;
    } else {
      throw new AiToolException(
          "Line " + number + " has side '" + side + "'. Use 'debit' or 'credit'.");
    }

    line.partnerId = AiToolArgs.optId(node, "partnerId");
    line.description = AiToolArgs.optText(node, "description");
    line.dueDate = date(node, "dueDate");
    return line;
  }

  protected LocalDate date(JsonNode args, String field) throws AiToolException {
    String raw = AiToolArgs.optText(args, field);
    if (raw == null) {
      return null;
    }
    try {
      return LocalDate.parse(raw);
    } catch (DateTimeParseException e) {
      throw new AiToolException("'" + field + "' must be an ISO date (yyyy-MM-dd).");
    }
  }
}

package com.axelor.apps.aiaccounting.tool;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.aiaccountant.service.AiBlocked;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccountant.tool.AiWriteMode;
import com.axelor.apps.aiaccounting.service.MoveEditService;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Set;

/**
 * WRITE tool: correct a NEW draft accounting move — header fields and lines, in one call.
 *
 * <p>One call for the whole correction, like {@code edit_supplier_invoice}: a change that touches
 * the date and three lines is one transaction that either lands or does not, rather than four that
 * can each fail and leave the entry in a state nobody asked for.
 */
@Singleton
public class EditMoveTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "edit_move";
  }

  @Override
  public String description() {
    return "Corrects an accounting move that is still a NEW draft — header fields and line "
        + "add/update/delete, all in ONE atomic call. Refused, with nothing changed, for any "
        + "other status: an ACCOUNTED move is in the books and is corrected by a reversing "
        + "entry, a DAYBOOK move goes through Axelor's own daybook path, a CANCELED one needs a "
        + "new entry, and a SIMULATED one has frozen line fields. Read the move with get_move "
        + "first and use the real lineIds it reports — never guess one. On a line, amount is "
        + "always POSITIVE and 'side' decides its direction; giving amount alone keeps the "
        + "current side, giving side alone flips the current amount. Omitted fields are left "
        + "unchanged, not cleared. Changing the date also moves the move's period and every "
        + "line's date. The result does NOT have to balance — a draft may be saved out of "
        + "balance and post_move is what refuses it — but the reply always reports whether it "
        + "does. Does NOT post: use post_move for that.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props
        .putObject("moveId")
        .put("type", "integer")
        .put("description", "The id of the NEW draft move to correct.");
    props
        .putObject("journalCode")
        .put("type", "string")
        .put("description", "New journal code. Must exist in the active company.");
    props
        .putObject("date")
        .put("type", "string")
        .put(
            "description",
            "New move date, ISO yyyy-MM-dd. Also re-resolves the accounting period and restamps"
                + " every line, so the entry stays inside its own period.");
    props
        .putObject("partnerId")
        .put("type", "integer")
        .put("description", "New partner for the move header.");
    props
        .putObject("origin")
        .put("type", "string")
        .put("description", "New source document reference.");
    props
        .putObject("originDate")
        .put("type", "string")
        .put("description", "New source document date, ISO yyyy-MM-dd.");
    props
        .putObject("description")
        .put("type", "string")
        .put("description", "New description for the entry.");
    props
        .putObject("functionalOriginSelect")
        .put("type", "integer")
        .put(
            "description",
            "Functional origin code. Must be one the journal authorises, or posting will refuse"
                + " it.");

    ObjectNode lineOps = props.putObject("lineOperations");
    lineOps.put("type", "array");
    lineOps.put(
        "description",
        "Per-line changes, batched. Applied updates, then deletes, then adds, so a lineId stays"
            + " valid for the whole request whatever order they are listed in. Deleting every line"
            + " is refused — an empty move cannot be posted.");
    ObjectNode item = lineOps.putObject("items");
    item.put("type", "object");
    ObjectNode lineProps = item.putObject("properties");
    ObjectNode operation = lineProps.putObject("operation");
    operation.put("type", "string");
    operation.putArray("enum").add("add").add("update").add("delete");
    operation.put("description", "add | update | delete.");
    lineProps
        .putObject("lineId")
        .put("type", "integer")
        .put(
            "description",
            "Required for update and delete. The moveLineId get_move reported, never a guess.");
    lineProps
        .putObject("accountCode")
        .put("type", "string")
        .put(
            "description",
            "Exact account code from this company's chart. Required for add. Never invented.");
    lineProps
        .putObject("amount")
        .put("type", "number")
        .put("description", "Always POSITIVE. Required for add. The side decides its direction.");
    lineProps
        .putObject("side")
        .put("type", "string")
        .put("description", "'debit' or 'credit'. Required for add. One line, one side.")
        .putArray("enum")
        .add("debit")
        .add("credit");
    lineProps
        .putObject("partnerId")
        .put("type", "integer")
        .put("description", "Partner for this line.");
    lineProps.putObject("description").put("type", "string").put("description", "Line narrative.");
    lineProps
        .putObject("dueDate")
        .put("type", "string")
        .put("description", "Due date for a payable/receivable line, ISO yyyy-MM-dd.");
    item.putArray("required").add("operation");
    item.put("additionalProperties", false);

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

  /** Chat only. Correcting somebody's draft entry is not an unattended pipeline's business. */
  @Override
  public Set<AiWriteMode> writeModes() {
    return EnumSet.of(AiWriteMode.CONFIRMED);
  }

  /** Corrections before posting (40), matching edit_supplier_invoice's place in the order. */
  @Override
  public int writeOrder() {
    return 20;
  }

  /** The move this call is aimed at, so a refusal on one entry does not stop the others. */
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

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    long moveId = AiToolArgs.requireId(args, "moveId");
    if (!Beans.get(JpaSecurity.class).isPermitted(JpaSecurity.CAN_WRITE, Move.class, moveId)) {
      throw new AiToolException("You do not have permission to change move " + moveId + ".");
    }

    MoveEditService.EditRequest request = new MoveEditService.EditRequest();
    request.moveId = moveId;
    request.journalCode = AiToolArgs.optText(args, "journalCode");
    request.date = date(args, "date");
    request.originDate = date(args, "originDate");
    request.partnerId = AiToolArgs.optId(args, "partnerId");
    request.origin = AiToolArgs.optText(args, "origin");
    request.description = AiToolArgs.optText(args, "description");
    if (args.hasNonNull("functionalOriginSelect")) {
      request.functionalOriginSelect = args.get("functionalOriginSelect").asInt();
    }

    JsonNode operations = args.get("lineOperations");
    if (operations != null && !operations.isNull()) {
      if (!operations.isArray()) {
        throw new AiToolException("'lineOperations' must be an array.");
      }
      for (int i = 0; i < operations.size(); i++) {
        request.lineOperations.add(operation(operations.get(i), i + 1));
      }
    }

    try {
      return Beans.get(MoveEditService.class).edit(request);
    } catch (AiBlocked e) {
      // The refusal's code travels with it, so "wrong status" is recognised as a decision for the
      // user while an unbalanced result is recognised as something to correct and retry.
      throw e.asToolException();
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new AiToolException(e.getMessage());
    }
  }

  /**
   * One line operation.
   *
   * <p>The side is turned into a boolean here, so the service can never be handed a line that
   * claims both — Axelor's {@code MOVE_6} made unrepresentable rather than merely refused.
   */
  protected MoveEditService.LineOperation operation(JsonNode node, int position)
      throws AiToolException {
    if (node == null || !node.isObject()) {
      throw new AiToolException("Line operation " + position + " is not an object.");
    }
    MoveEditService.LineOperation op = new MoveEditService.LineOperation();

    String name = AiToolArgs.requireText(node, "operation");
    if ("add".equalsIgnoreCase(name)) {
      op.operation = MoveEditService.LineOp.ADD;
    } else if ("update".equalsIgnoreCase(name)) {
      op.operation = MoveEditService.LineOp.UPDATE;
    } else if ("delete".equalsIgnoreCase(name)) {
      op.operation = MoveEditService.LineOp.DELETE;
    } else {
      throw new AiToolException(
          "Line operation "
              + position
              + " has operation '"
              + name
              + "'. Use add, update or"
              + " delete.");
    }

    op.lineId = AiToolArgs.optId(node, "lineId");
    if (op.operation != MoveEditService.LineOp.ADD && op.lineId == null) {
      throw new AiToolException(
          "Line operation " + position + " is an " + name + " and needs a lineId.");
    }

    op.accountCode = AiToolArgs.optText(node, "accountCode");
    if (op.operation == MoveEditService.LineOp.ADD
        && (op.accountCode == null || op.accountCode.isEmpty())) {
      throw new AiToolException(
          "Line operation " + position + " adds a line and needs an" + " accountCode.");
    }

    JsonNode amount = node.get("amount");
    if (amount != null && !amount.isNull()) {
      if (!amount.isNumber()) {
        throw new AiToolException("Line operation " + position + " has a non-numeric 'amount'.");
      }
      op.amount = amount.decimalValue();
    }

    String side = AiToolArgs.optText(node, "side");
    if (side != null) {
      if ("debit".equalsIgnoreCase(side)) {
        op.debit = Boolean.TRUE;
      } else if ("credit".equalsIgnoreCase(side)) {
        op.debit = Boolean.FALSE;
      } else {
        throw new AiToolException(
            "Line operation " + position + " has side '" + side + "'. Use 'debit' or 'credit'.");
      }
    }

    op.partnerId = AiToolArgs.optId(node, "partnerId");
    op.description = AiToolArgs.optText(node, "description");
    op.dueDate = date(node, "dueDate");
    return op;
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

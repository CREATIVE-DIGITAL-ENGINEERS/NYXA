package com.axelor.apps.aiaccounting.tool;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccounting.service.MoveAccountingService;
import com.axelor.apps.aiaccounting.service.MoveSearchService;
import com.axelor.apps.aiaccounting.service.MoveViewService;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;

/** READ tool: one accounting move in full — header, lines, balance and what blocks posting. */
@Singleton
public class GetMoveTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "get_move";
  }

  @Override
  public String description() {
    return "Returns one accounting move in full: header, its lines with account codes and "
        + "debit/credit, the computed totals and whether it balances, and its status. Use it to "
        + "explain what an entry contains or why it will not post. Set checkPosting=true to also "
        + "run Axelor's own pre-posting checks and get back the exact blocker — that is read-only "
        + "and changes nothing. Read the move with this before posting it. Only ever returns a "
        + "move of the active company.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props
        .putObject("moveId")
        .put("type", "integer")
        .put("description", "The move id, as search_moves reported it.");
    props
        .putObject("checkPosting")
        .put("type", "boolean")
        .put(
            "description",
            "Also run Axelor's pre-posting checks and report the blocker. Read-only. Use it when"
                + " asked why a move will not post, or before calling post_move.");
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
    return AiToolKind.READ;
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    long moveId = AiToolArgs.requireId(args, "moveId");
    Move move = Beans.get(MoveSearchService.class).findScoped(moveId);
    // findScoped answers not-found and another-company's identically, on purpose: knowing an id
    // from another company must not confirm that the record exists.
    if (move == null) {
      throw new AiToolException("No move with id " + moveId + ".");
    }
    if (!Beans.get(JpaSecurity.class).isPermitted(JpaSecurity.CAN_READ, Move.class, move.getId())) {
      throw new AiToolException("You do not have permission to read move " + moveId + ".");
    }

    Map<String, Object> out = new LinkedHashMap<>(Beans.get(MoveViewService.class).detail(move));
    if (args != null && args.hasNonNull("checkPosting") && args.get("checkPosting").asBoolean()) {
      out.put("postingCheck", Beans.get(MoveAccountingService.class).check(move));
    }
    return out;
  }
}

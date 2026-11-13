package com.axelor.apps.aibanking.tool;

import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aibanking.service.BankStatementSearchService;
import com.axelor.apps.aibanking.service.BankStatementViewService;
import com.axelor.apps.bankpayment.db.BankStatement;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;

/** READ tool: one bank statement with its balance check and a sample of its lines. */
@Singleton
public class GetBankStatementTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "get_bank_statement";
  }

  @Override
  public String description() {
    return "Returns one bank statement in full: header, and — once it has lines — per bank account "
        + "the opening balance, the movement total, the closing balance and whether they agree (the "
        + "intra-statement balance check Axelor also makes at import). Also returns a sample of the "
        + "lines (capped, with linesTruncated when there are more), how much is still to reconcile "
        + "and unreconciledLineCount. That balance check answers 'does this statement add up', but it "
        + "is only ONE of the import checks, so a balanced statement is not proof a file will import: "
        + "Axelor also refuses a duplicate statement and one whose opening balance does not follow "
        + "the previous statement's close. A RECEIVED (not-yet-imported) statement has no lines or "
        + "balances yet — they are created at import — so this cannot diagnose a file before it is "
        + "imported. Statement lines are read-only in Axelor and there is no tool that edits them. "
        + "Only ever returns a statement belonging to the active company: another company's, an "
        + "unknown id, and an older record whose company was never set and whose lines do not settle "
        + "it are all answered as not found.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props
        .putObject("bankStatementId")
        .put("type", "integer")
        .put("description", "The statement id, as search_bank_statements reported it.");
    schema.putArray("required").add("bankStatementId");
    schema.put("additionalProperties", false);
    return schema;
  }

  @Override
  public AiCapability capability() {
    return AiCapability.BANK_STATEMENT;
  }

  @Override
  public AiToolKind kind() {
    return AiToolKind.READ;
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    long id = AiToolArgs.requireId(args, "bankStatementId");
    // Strictly scoped: absent, another company's, and an unattributable legacy record are all
    // answered the same way. Describing a statement this company cannot be shown to own would put
    // a record outside the active company into the answer.
    BankStatement statement = Beans.get(BankStatementSearchService.class).findScoped(id);
    if (statement == null) {
      throw new AiToolException("No bank statement with id " + id + ".");
    }
    if (!Beans.get(JpaSecurity.class)
        .isPermitted(JpaSecurity.CAN_READ, BankStatement.class, statement.getId())) {
      throw new AiToolException("You do not have permission to read bank statement " + id + ".");
    }
    return Beans.get(BankStatementViewService.class).detail(statement);
  }
}

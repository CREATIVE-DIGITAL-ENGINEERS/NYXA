package com.axelor.apps.aiocr.service.ai.tool;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiReadGuard;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiocr.service.accounting.PartnerAccountService;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;

/**
 * READ tool: are this company's supplier and customer accounts configured for partner accounting?
 *
 * <p>Answers the question behind the readiness blocker "payable account is not reconcilable" —
 * which account, which flag, and where it is configured. Takes no arguments: the accounts are the
 * active company's, and there is nothing for a caller to choose.
 */
@Singleton
public class CheckPartnerAccountsTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "check_partner_accounts";
  }

  @Override
  public String description() {
    return "Lists the active company's supplier (payable) and customer (receivable) partner "
        + "accounts with, per account, its code, name, role, where it is configured, and whether "
        + "reconcileOk and useForPartnerBalance are both on. This explains a 'payable account is "
        + "not reconcilable' blocker. Read-only; fix_partner_account applies the correction.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    schema.putObject("properties");
    schema.putArray("required");
    schema.put("additionalProperties", false);
    return schema;
  }

  @Override
  public AiCapability capability() {
    return AiCapability.ACCOUNTING;
  }

  @Override
  public AiToolKind kind() {
    return AiToolKind.READ;
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    // The same rule every list-style read applies: the user must be allowed to read accounts at
    // all. System-context work passes inside AiReadGuard itself.
    AiReadGuard.requireRead(Account.class, "accounts");
    try {
      return Beans.get(PartnerAccountService.class).inspect();
    } catch (PartnerAccountService.PartnerAccountRefusedException e) {
      throw new AiToolException(e.getMessage());
    } catch (RuntimeException e) {
      throw new AiToolException(
          "The company's partner accounts could not be read: " + e.getMessage());
    }
  }
}

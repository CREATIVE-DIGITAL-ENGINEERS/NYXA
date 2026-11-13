package com.axelor.apps.aiocr.service.ai.tool;

import com.axelor.apps.aiaccountant.resilience.AiWriteOutcome;
import com.axelor.apps.aiaccountant.resilience.AiWriteProbe;
import com.axelor.apps.aiaccountant.resilience.AiWriteVerifier;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccountant.tool.AiWriteMode;
import com.axelor.apps.aiocr.service.accounting.PartnerAccountService;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * WRITE tool: make one partner account usable for partner accounting.
 *
 * <p>Turns {@code reconcileOk} and {@code useForPartnerBalance} on, and does nothing else. It takes
 * an account <em>code</em> rather than an id or a field path, because the only decision a caller
 * gets to make is which of this company's partner accounts to repair — everything about what
 * "repair" means is fixed in {@link PartnerAccountService}, which refuses any account that is not
 * this company's, is not a payable/receivable type, or is not actually used as a partner account.
 *
 * <p>Chat-only. The flags decide how money is attributed to a partner and to a balance, so the
 * automatic OCR pass may not reach this at all — an intake job that fixed accounting configuration
 * on its own would be changing the books' behaviour with nobody watching.
 */
@Singleton
public class FixPartnerAccountTool implements AiTool, AiWriteVerifier {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "fix_partner_account";
  }

  @Override
  public String description() {
    return "Turns on reconcileOk and useForPartnerBalance for ONE of the active company's partner "
        + "accounts — the flags Axelor requires before an invoice using that account can be "
        + "validated or ventilated. Give the code as check_partner_accounts reported it (e.g. "
        + "'401'). Refused unless the account is this company's, is payable/receivable, and is "
        + "really used as a partner account, so an unrelated ledger account cannot be touched. "
        + "Only ever turns flags ON and posts nothing. One account is named per call, but Axelor's "
        + "own save also turns reconcileOk on for the accounts listed as compatible with it — say "
        + "so when reporting the change.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props
        .putObject("accountCode")
        .put("type", "string")
        .put(
            "description",
            "The account code as check_partner_accounts reported it, e.g. '401' for Доставчици."
                + " Never invent one, and never pass an expense or income account.");
    props
        .putObject("reason")
        .put("type", "string")
        .put(
            "description",
            "Why it is being corrected — e.g. the invoice or record whose validation it blocks.");
    schema.putArray("required").add("accountCode");
    schema.put("additionalProperties", false);
    return schema;
  }

  @Override
  public AiCapability capability() {
    return AiCapability.ACCOUNTING;
  }

  @Override
  public AiToolKind kind() {
    return AiToolKind.WRITE;
  }

  /** Never unattended: accounting configuration is not something an automatic pass may decide. */
  @Override
  public Set<AiWriteMode> writeModes() {
    return EnumSet.of(AiWriteMode.CONFIRMED);
  }

  /** Before any invoice work (20): a correction here is what lets the rest of it be posted. */
  @Override
  public int writeOrder() {
    return 3;
  }

  /**
   * After a lost answer: are the flags on?
   *
   * <h2>Why no baseline is needed here, and why that is unusual</h2>
   *
   * Almost every write needs a before-state to be judged, because "the record is in state X" says
   * nothing without knowing where it started. This one is the exception: it only ever turns flags
   * ON, so its whole intent is the end state rather than a transition. Both flags on means what was
   * asked for is true — and it does not matter whether this call or a previous one made it so,
   * because the operation is idempotent and its purpose is served either way. Both flags off means
   * nothing was written and it may run again.
   *
   * <p>No target and therefore no fingerprint: this changes a company's chart of accounts, which
   * several documents share, so the batch runner's whole-response abort is the correct blast radius
   * and a per-record target would understate it. The postcondition is what makes the operation
   * reconcilable despite that.
   */
  @Override
  public AiWriteOutcome verifyAfterAmbiguousFailure(JsonNode args, AiWriteProbe probe) {
    String accountCode = AiToolArgs.optText(args, "accountCode");
    if (accountCode == null || accountCode.trim().isEmpty()) {
      return AiWriteOutcome.unknown();
    }
    PartnerAccountService service = Beans.get(PartnerAccountService.class);
    Boolean enabled = service.isEnabledForPartnerAccounting(accountCode);
    if (enabled == null) {
      // The account cannot be resolved in this company — the same answer the write path would have
      // refused with. Nothing established.
      return AiWriteOutcome.unknown();
    }
    if (!enabled) {
      return AiWriteOutcome.notWritten();
    }
    Map<String, Object> result = service.confirmedResult(accountCode);
    return result == null ? AiWriteOutcome.unknown() : AiWriteOutcome.completed(result);
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    String accountCode = AiToolArgs.requireText(args, "accountCode");
    String reason = AiToolArgs.optText(args, "reason");
    try {
      return Beans.get(PartnerAccountService.class).enablePartnerAccounting(accountCode, reason);
    } catch (PartnerAccountService.PartnerAccountRefusedException
        | IllegalArgumentException
        | IllegalStateException e) {
      throw new AiToolException(e.getMessage());
    }
  }
}

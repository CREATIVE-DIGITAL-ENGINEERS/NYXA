package com.axelor.apps.aibanking.tool;

import com.axelor.apps.aiaccountant.resilience.AiWriteOutcome;
import com.axelor.apps.aiaccountant.resilience.AiWriteProbe;
import com.axelor.apps.aiaccountant.resilience.AiWriteVerifier;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccountant.tool.AiWriteMode;
import com.axelor.apps.aibanking.service.BankStatementImportRunService;
import com.axelor.apps.bankpayment.db.BankStatement;
import com.axelor.apps.bankpayment.db.repo.BankStatementRepository;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * WRITE tool: run a bank statement's import — the Import button, through Axelor's own service.
 *
 * <p>The only write this domain offers the assistant. Statement lines are read-only in Axelor, a
 * statement is created by attaching a file (which this assistant cannot do), and deleting one is
 * left to a person.
 */
@Singleton
public class RunBankStatementImportTool implements AiTool, AiWriteVerifier {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "run_bank_statement_import";
  }

  @Override
  public String description() {
    return "Imports a RECEIVED bank statement's attached file through Axelor's real importer "
        + "(BankStatementImportService.runImport) — the Import button on the Bank statement form. "
        + "Creates the statement's lines and sets it to IMPORTED. This does NOT post anything: "
        + "statement lines are not accounting entries and become accounting only through bank "
        + "reconciliation, which is a separate screen. You must give bankDetailsId — the bank "
        + "account the statement is expected to be for — because a statement names a company but "
        + "never an account, and the file has to land somewhere specific; the call is refused if "
        + "that account is not this company's, if it is not the account of the company the "
        + "statement itself is for, or if the imported lines turn out to be for a different "
        + "account. Also refused, with nothing imported, when the "
        + "statement is already IMPORTED, has no file or no file format, or when Axelor's own "
        + "checks reject the file — a duplicate statement, an opening balance that does not "
        + "follow the previous statement's closing one, or movements that do not add up. Those "
        + "are decisions about the file and need a person; do not retry them. Only CFONB120 "
        + "statements can be imported from here: every other format is imported by Axelor in "
        + "batches that are committed as it goes and could not be rolled back if the account "
        + "check failed, so those are refused and must be imported from the Bank statement form. "
        + "Cannot attach a file: if there is none, say so.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props
        .putObject("bankStatementId")
        .put("type", "integer")
        .put("description", "The id of the RECEIVED statement to import.");
    props
        .putObject("bankDetailsId")
        .put("type", "integer")
        .put(
            "description",
            "The bank account this statement is for. Must belong to the active company, and every"
                + " imported line must land on it. Never guess it — ask the user which account the"
                + " file is for if it is not already established.");
    schema.putArray("required").add("bankStatementId").add("bankDetailsId");
    schema.put("additionalProperties", false);
    return schema;
  }

  @Override
  public AiCapability capability() {
    return AiCapability.BANK_STATEMENT;
  }

  @Override
  public AiToolKind kind() {
    return AiToolKind.WRITE;
  }

  /** Chat only. An import is a person's decision about a file they attached. */
  @Override
  public Set<AiWriteMode> writeModes() {
    return EnumSet.of(AiWriteMode.CONFIRMED);
  }

  /** Early: an import produces the data every other banking operation would read. */
  @Override
  public int writeOrder() {
    return 10;
  }

  /** The statement this call is aimed at, so one failed import does not stop the others. */
  @Override
  public String targetKey(JsonNode args) {
    if (args == null) {
      return null;
    }
    JsonNode id = args.get("bankStatementId");
    if (id == null || id.isNull()) {
      return null;
    }
    String text = id.asText("").trim();
    return text.isEmpty() ? null : "BankStatement#" + text;
  }

  /** The statement's status before the import — the baseline the postcondition is read against. */
  @Override
  public Object beforeExecute(JsonNode args) {
    try {
      return Beans.get(BankStatementImportRunService.class)
          .scopedStatus(AiToolArgs.requireId(args, "bankStatementId"));
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * After a lost answer: was the statement imported?
   *
   * <h2>Why guessing is not an option here</h2>
   *
   * Importing a statement twice is what Axelor's duplicate and initial-balance checks exist to
   * prevent, and a retry after a lost answer is exactly how a system arrives at that. Reporting a
   * failure instead is no better: the accountant would go looking for lines that are already there
   * and reconcile against a statement they believe is empty.
   *
   * <p>Both are avoidable, because an import has one lifecycle effect and it is readable. Unchanged
   * status means the transaction rolled back and nothing was imported — provable, retryable.
   * IMPORTED means it landed, and the ordinary success is reported with the statement re-read.
   */
  @Override
  public AiWriteOutcome verifyAfterAmbiguousFailure(JsonNode args, AiWriteProbe probe) {
    Integer before = probe == null ? null : (Integer) probe.toolState();
    long bankStatementId;
    try {
      bankStatementId = AiToolArgs.requireId(args, "bankStatementId");
    } catch (RuntimeException e) {
      return AiWriteOutcome.unknown();
    }
    BankStatementImportRunService service = Beans.get(BankStatementImportRunService.class);
    Integer now = service.scopedStatus(bankStatementId);
    if (before == null || now == null) {
      return AiWriteOutcome.unknown();
    }
    if (now.intValue() == before.intValue()) {
      return AiWriteOutcome.notWritten();
    }
    if (now.intValue() != BankStatementRepository.STATUS_IMPORTED) {
      // A status an import does not produce. Something else moved it, and interpreting that is not
      // this method's job.
      return AiWriteOutcome.unknown();
    }
    Map<String, Object> result = service.confirmedResult(bankStatementId);
    return result == null ? AiWriteOutcome.unknown() : AiWriteOutcome.completed(result);
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    long bankStatementId = AiToolArgs.requireId(args, "bankStatementId");
    long bankDetailsId = AiToolArgs.requireId(args, "bankDetailsId");
    if (!Beans.get(JpaSecurity.class)
        .isPermitted(JpaSecurity.CAN_WRITE, BankStatement.class, bankStatementId)) {
      throw new AiToolException(
          "You do not have permission to import bank statement " + bankStatementId + ".");
    }
    try {
      // The service throws AiToolException already carrying its blocker code, so it passes
      // straight through to the dispatcher; only Axelor's own unchecked types need wrapping.
      return Beans.get(BankStatementImportRunService.class)
          .runImport(bankStatementId, bankDetailsId);
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new AiToolException(e.getMessage());
    }
  }
}

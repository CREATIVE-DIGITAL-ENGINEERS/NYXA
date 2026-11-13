package com.axelor.apps.aibanking.tool;

import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiReadGuard;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aibanking.service.BankStatementSearchService;
import com.axelor.apps.aibanking.service.BankStatementViewService;
import com.axelor.apps.bankpayment.db.BankStatement;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** READ tool: find bank statements. */
@Singleton
public class SearchBankStatementsTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "search_bank_statements";
  }

  @Override
  public String description() {
    return "Finds bank statements — the records under Accounting → Bank statements. Filter by "
        + "name, status (RECEIVED or IMPORTED), bank account or period; every filter is optional "
        + "and they combine with AND. Returns summaries with bankStatementId, name, status, the "
        + "date range and whether a file is attached; use get_bank_statement for the lines and "
        + "the balance check. Both RECEIVED (a file waiting to be imported) and IMPORTED "
        + "statements are listed, as long as they belong to the active company. Older records "
        + "created before statements carried a company are listed only when their imported lines "
        + "all sit on this company's bank accounts; one that is still ambiguous is returned by no "
        + "tool, and the answer is that its company has to be set on the Bank statement form. "
        + "Results are one page: when totalMatches exceeds what came back, "
        + "call again with offset.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props
        .putObject("bankStatementId")
        .put("type", "integer")
        .put("description", "Exact statement id, when it is already known.");
    props
        .putObject("name")
        .put("type", "string")
        .put("description", "Part of the statement name — matched anywhere in it.");
    props
        .putObject("status")
        .put("type", "string")
        .put(
            "description",
            "RECEIVED (a file waiting to be imported) or IMPORTED (its lines exist). These are the"
                + " only two statuses a bank statement has.");
    props
        .putObject("bankDetailsId")
        .put("type", "integer")
        .put("description", "Only statements with at least one line on this bank account.");
    props
        .putObject("fromDate")
        .put("type", "string")
        .put("description", "Statements whose period ends on or after this date, ISO yyyy-MM-dd.");
    props
        .putObject("toDate")
        .put("type", "string")
        .put(
            "description",
            "Statements whose period starts on or before this date, ISO yyyy-MM-dd.");
    props
        .putObject("limit")
        .put("type", "integer")
        .put(
            "description",
            "Maximum statements to return (default "
                + BankStatementSearchService.DEFAULT_LIMIT
                + ", max "
                + BankStatementSearchService.MAX_LIMIT
                + ").");
    props
        .putObject("offset")
        .put("type", "integer")
        .put("description", "How many matches to skip. Use the returned nextOffset to page.");
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
    AiReadGuard.requireRead(com.axelor.apps.bankpayment.db.BankStatement.class, "bank statements");
    BankStatementSearchService.StatementQuery q = new BankStatementSearchService.StatementQuery();
    q.bankStatementId = AiToolArgs.optId(args, "bankStatementId");
    q.name = AiToolArgs.optText(args, "name");
    q.bankDetailsId = AiToolArgs.optId(args, "bankDetailsId");
    q.fromDate = date(args, "fromDate");
    q.toDate = date(args, "toDate");
    String status = AiToolArgs.optText(args, "status");
    if (status != null) {
      q.statusSelect = BankStatementViewService.statusValue(status);
      if (q.statusSelect == null) {
        throw new AiToolException(
            "'" + status + "' is not a bank statement status. Use RECEIVED or IMPORTED.");
      }
    }

    q.limit =
        AiToolArgs.limit(
            args,
            "limit",
            BankStatementSearchService.DEFAULT_LIMIT,
            BankStatementSearchService.MAX_LIMIT);
    Long requestedOffset = AiToolArgs.optId(args, "offset");
    int offset =
        requestedOffset == null
            ? 0
            : (int) Math.max(0L, Math.min(requestedOffset, BankStatementSearchService.MAX_OFFSET));
    q.offset = offset;

    BankStatementSearchService.Page page = Beans.get(BankStatementSearchService.class).search(q);
    BankStatementViewService view = Beans.get(BankStatementViewService.class);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("totalMatches", page.getTotalMatches());
    out.put("returned", page.getStatements().size());
    out.put("offset", offset);
    // Said on every result, not only the empty one: "no statements" and "no statements I am able
    // to attribute to your company" are different answers, and the model cannot tell them apart
    // unless the tool says which one this is.
    out.put(
        "scopeNote",
        "Only this company's statements are listed — the company recorded on the statement, or,"
            + " for older records that have none, one proved by every line sitting on this"
            + " company's bank accounts. An older statement whose company was never set and whose"
            + " lines do not settle it is listed by no tool until somebody sets the company on the"
            + " Bank statement form.");

    if (page.getStatements().isEmpty()) {
      out.put("outcome", offset > 0 ? "page" : "not_found");
      out.put("statements", new ArrayList<>());
      if (offset > 0) {
        out.put(
            "hint",
            "No statements at offset "
                + offset
                + "; the "
                + page.getTotalMatches()
                + " matching statements were already returned. The set is complete.");
      }
      return out;
    }

    List<Map<String, Object>> items = new ArrayList<>();
    for (BankStatement statement : page.getStatements()) {
      items.add(view.summarise(statement));
    }
    out.put("statements", items);

    long seen = (long) offset + page.getStatements().size();
    if (seen < page.getTotalMatches()) {
      out.put("outcome", "page");
      out.put("nextOffset", seen);
      out.put(
          "nextStep",
          "Statements "
              + (offset + 1)
              + "-"
              + seen
              + " of "
              + page.getTotalMatches()
              + ". Call search_bank_statements again with offset="
              + seen
              + " for the rest.");
      return out;
    }
    if (page.getTotalMatches() == 1 && offset == 0) {
      out.put("outcome", "single");
      out.put(
          "nextStep",
          "Exactly one statement matched. Call get_bank_statement with bankStatementId="
              + page.getStatements().get(0).getId()
              + " for its lines and balance check. Do not search again.");
      return out;
    }
    out.put("outcome", "list");
    return out;
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

package com.axelor.apps.aiaccounting.tool;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiReadGuard;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccounting.service.MoveSearchService;
import com.axelor.apps.aiaccounting.service.MoveViewService;
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

/** READ tool: find accounting moves in the active company. */
@Singleton
public class SearchMovesTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Override
  public String name() {
    return "search_moves";
  }

  @Override
  public String description() {
    return "Finds accounting moves (journal entries) in the active company — the records under "
        + "Accounting → Moves. Filter by reference, journal code, status, partner, origin or date "
        + "range; every filter is optional and they combine with AND. Returns compact summaries "
        + "with moveId, reference, status, journal, date and the debit/credit totals. Use the "
        + "moveId with get_move for the lines and the balance. Results are one page: when "
        + "totalMatches is larger than what came back, call again with offset to get the rest — "
        + "never treat the first page as the whole set. Only ever returns this company's moves.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");
    props
        .putObject("moveId")
        .put("type", "integer")
        .put("description", "Exact move id, when it is already known.");
    props
        .putObject("reference")
        .put("type", "string")
        .put("description", "Part of the move reference, e.g. 'PCH2026' — matched anywhere in it.");
    props
        .putObject("journalCode")
        .put("type", "string")
        .put("description", "Exact journal code, e.g. 'PCH' or 'OD'. Not the journal name.");
    props
        .putObject("status")
        .put("type", "string")
        .put(
            "description",
            "One of NEW, DAYBOOK, ACCOUNTED, CANCELED, SIMULATED. NEW and DAYBOOK are not yet"
                + " finally in the books; ACCOUNTED is.");
    props
        .putObject("partnerName")
        .put("type", "string")
        .put("description", "Part of the partner's name.");
    props
        .putObject("origin")
        .put("type", "string")
        .put("description", "Part of the origin field — usually the source document's number.");
    props
        .putObject("dateFrom")
        .put("type", "string")
        .put("description", "Earliest move date, ISO yyyy-MM-dd, inclusive.");
    props
        .putObject("dateTo")
        .put("type", "string")
        .put("description", "Latest move date, ISO yyyy-MM-dd, inclusive.");
    props
        .putObject("limit")
        .put("type", "integer")
        .put(
            "description",
            "Maximum moves to return (default "
                + MoveSearchService.DEFAULT_LIMIT
                + ", max "
                + MoveSearchService.MAX_LIMIT
                + ").");
    props
        .putObject("offset")
        .put("type", "integer")
        .put(
            "description",
            "How many matches to skip. Use the returned nextOffset to walk a set larger than one"
                + " page. Omit for the first page.");
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
    AiReadGuard.requireRead(com.axelor.apps.account.db.Move.class, "accounting moves");
    MoveSearchService.MoveQuery q = new MoveSearchService.MoveQuery();
    q.moveId = AiToolArgs.optId(args, "moveId");
    q.reference = AiToolArgs.optText(args, "reference");
    q.journalCode = AiToolArgs.optText(args, "journalCode");
    q.partnerName = AiToolArgs.optText(args, "partnerName");
    q.origin = AiToolArgs.optText(args, "origin");
    q.dateFrom = date(args, "dateFrom");
    q.dateTo = date(args, "dateTo");

    String status = AiToolArgs.optText(args, "status");
    if (status != null) {
      q.statusSelect = MoveViewService.statusValue(status);
      if (q.statusSelect == null) {
        throw new AiToolException(
            "'"
                + status
                + "' is not a move status. Use NEW, DAYBOOK, ACCOUNTED, CANCELED or"
                + " SIMULATED.");
      }
    }

    q.limit =
        AiToolArgs.limit(
            args, "limit", MoveSearchService.DEFAULT_LIMIT, MoveSearchService.MAX_LIMIT);
    Long requestedOffset = AiToolArgs.optId(args, "offset");
    int offset =
        requestedOffset == null
            ? 0
            : (int) Math.max(0L, Math.min(requestedOffset, MoveSearchService.MAX_OFFSET));
    q.offset = offset;

    MoveSearchService.Page page = Beans.get(MoveSearchService.class).search(q);
    MoveViewService view = Beans.get(MoveViewService.class);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("totalMatches", page.getTotalMatches());
    out.put("returned", page.getMoves().size());
    out.put("offset", offset);

    if (page.getMoves().isEmpty()) {
      out.put("outcome", offset > 0 ? "page" : "not_found");
      out.put("moves", new ArrayList<>());
      if (offset > 0) {
        // Past the end of a set that does have matches. "not_found" here would read as "your
        // filters match nothing" and send the model off to change them.
        out.put(
            "hint",
            "No moves at offset "
                + offset
                + "; the "
                + page.getTotalMatches()
                + " matching moves were already returned by the earlier pages. The set is complete"
                + " — do not page further and do not change the filters.");
      }
      return out;
    }

    List<Map<String, Object>> items = new ArrayList<>();
    for (Move move : page.getMoves()) {
      items.add(view.summarise(move));
    }
    out.put("moves", items);

    long seen = (long) offset + page.getMoves().size();
    if (seen < page.getTotalMatches()) {
      out.put("outcome", "page");
      out.put("nextOffset", seen);
      out.put(
          "nextStep",
          "Moves "
              + (offset + 1)
              + "-"
              + seen
              + " of "
              + page.getTotalMatches()
              + ". Call search_moves again with offset="
              + seen
              + " for the rest.");
      return out;
    }

    // "Exactly one match" is a statement about the whole set, not about this page — on the last
    // page of a long set a single row is routine and must not read as "this is the only one".
    if (page.getTotalMatches() == 1 && offset == 0) {
      out.put("outcome", "single");
      out.put(
          "nextStep",
          "Exactly one move matched. Call get_move with moveId="
              + page.getMoves().get(0).getId()
              + " for its lines and balance. Do not search again.");
      return out;
    }
    out.put("outcome", "list");
    return out;
  }

  /** An ISO date, or a refusal that says so — never a silently ignored filter. */
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

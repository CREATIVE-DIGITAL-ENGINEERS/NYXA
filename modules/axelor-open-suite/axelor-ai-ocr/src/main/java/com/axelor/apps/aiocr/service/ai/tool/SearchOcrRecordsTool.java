package com.axelor.apps.aiocr.service.ai.tool;

import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.aiocr.service.record.OcrBlocked;
import com.axelor.apps.aiocr.service.record.OcrRecordQuery;
import com.axelor.apps.aiocr.service.record.OcrRecordSearchService;
import com.axelor.apps.aiocr.service.record.OcrRecordViewService;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Locates OCR records. Thin adapter over {@link OcrRecordSearchService} — all querying, bounding,
 * permission and company-scope logic lives in the service so a UI can reuse it.
 *
 * <p>Never silently picks one of several matches: an ambiguous search comes back as a candidate
 * list with {@code outcome=multiple}, so the agent has to narrow it down.
 *
 * <p>There is deliberately no company parameter. Every search is confined to the caller's active
 * company by the service, and that is not something a caller — least of all a model — gets to
 * choose.
 */
@Singleton
public class SearchOcrRecordsTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Near-miss candidates offered when an exact file-name search finds nothing. */
  static final int MAX_SUGGESTIONS = 5;

  /**
   * Ceiling on how far a caller may page.
   *
   * <p>Not a permission — every page is company-scoped and permission-filtered like any other
   * search — but a bound on how much work one malformed argument can ask the database for. At 25
   * per page this is 400 pages, which is far past any real intake queue and still a number rather
   * than {@code Integer.MAX_VALUE}.
   */
  static final int MAX_OFFSET = 10_000;

  @Override
  public String name() {
    return "search_ocr_records";
  }

  @Override
  public String description() {
    return "Finds OCR upload records (scanned supplier documents). Combine parameters to narrow the "
        + "search. Returns outcome=not_found, single, multiple or page; on multiple, refine rather "
        + "than guessing. Follow a match with get_ocr_record on the returned uploadId. A not_found "
        + "on fileName returns near-miss 'suggestions' — use those instead of searching the same "
        + "name again. When the answer carries 'nextOffset', more matches exist than were returned: "
        + "call again with the same filters and that offset to work through the whole set.";
  }

  /**
   * Only the filters an accountant's question actually maps onto.
   *
   * <p>{@code docKey}, {@code fileHash}, {@code carrierKind} and {@code importArchiveId} were
   * published and are not: they are the intake pipeline's own plumbing — a deduplication key, a
   * SHA-256, a carrier classification and a parent-archive link. No accountant asks a question that
   * resolves to one of them, and their descriptions had to explain internal machinery before the
   * model could even consider them. {@code OcrRecordQuery} still carries all four for the UI and
   * for any Java caller, so nothing lost a capability; they simply stopped costing bytes on every
   * request.
   *
   * <p>{@code offset} went with them, on the reasoning that paging through an ambiguous search is
   * not how it gets resolved — refining it is. True for an ambiguous search, and wrong for the
   * other thing this tool is asked to do. Rule 11 tells the model that «всички» means the complete
   * eligible set and to read {@code totalMatches} rather than stop at what it can see; with {@code
   * limit} capped at 25 and no offset, a set of 30 was simply unreachable, and the honest
   * instruction had no honest mechanism behind it. It is back, described as paging a known total
   * rather than as a way to browse.
   *
   * <p>Ids are typed {@code integer} rather than {@code string}, which is what they are; {@code
   * AiToolArgs.optId} accepts either, so this is a correction, not a change of contract.
   */
  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode p = schema.putObject("properties");

    id(p, "uploadId", "Exact OCR upload id.");
    id(p, "invoiceId", "Exact supplier invoice id linked to the upload.");
    str(p, "invoiceNumber", "Supplier's own invoice number (partial).");
    str(p, "fileName", "Uploaded file name (partial).");
    str(p, "partnerName", "Supplier company name (partial).");
    str(
        p,
        "eik",
        "Supplier ЕИК or BG VAT number, digits or BG-prefixed; matched against both the stored ЕИК"
            + " and VAT number.");
    str(p, "iban", "IBAN on the invoice (exact).");
    enumStr(
        p,
        "status",
        "Upload status. uploaded = attached but not started; the deletedFrom… values mean the"
            + " generated record was deleted; extracting/completedWithIssues/archive apply to"
            + " archive/split carriers.",
        STATUSES);
    enumStr(p, "ocrReviewStatus", "Review state of the linked invoice.", OCR_REVIEW_STATUSES);
    enumStr(p, "segmentType", "Split child type.", SEGMENT_TYPES);
    // No companyId: the search is always confined to the user's active company, and offering the
    // parameter would invite the model to try widening it.
    str(p, "createdFrom", "Earliest upload date, ISO yyyy-MM-dd.");
    str(p, "createdTo", "Latest upload date, ISO yyyy-MM-dd.");
    ObjectNode limit = p.putObject("limit");
    limit.put("type", "integer");
    limit.put("description", "Max candidates (default " + DEFAULT_MODEL_LIMIT + ", max 25).");
    ObjectNode offset = p.putObject("offset");
    offset.put("type", "integer");
    offset.put(
        "description",
        "Skip this many matches, for working through a set larger than one page. Only for paging a"
            + " known total: repeat the SAME filters and use the 'nextOffset' the previous answer"
            + " gave you. Never use it to browse an ambiguous search — narrow that instead.");

    schema.putArray("required");
    schema.put("additionalProperties", false);
    return schema;
  }

  /**
   * Candidates returned by default when the model does not ask for more.
   *
   * <p>Lower than the service's own default of ten: a search that comes back with more than a
   * handful is ambiguous, and the answer to that is to narrow it — which the result explicitly
   * tells the model to do — not to read five more summaries it will not use. The model can still
   * raise it up to the service maximum when it genuinely wants a list.
   */
  static final int DEFAULT_MODEL_LIMIT = 5;

  /** Every code of selection {@code ai.ocr.upload.status.select} (AiOcrUpload.statusSelect). */
  static final List<String> STATUSES =
      Arrays.asList(
          AiOcrUploadStatus.UPLOADED,
          AiOcrUploadStatus.QUEUED,
          AiOcrUploadStatus.PROCESSING,
          AiOcrUploadStatus.COMPLETED,
          AiOcrUploadStatus.FAILED,
          AiOcrUploadStatus.DUPLICATE,
          AiOcrUploadStatus.CANCELLED,
          AiOcrUploadStatus.UNSUPPORTED,
          AiOcrUploadStatus.NEEDS_REVIEW,
          AiOcrUploadStatus.DELETED_FROM_SUPPL_INVOICES,
          AiOcrUploadStatus.DELETED_FROM_MOVES,
          AiOcrUploadStatus.DELETED_FROM_BANK_STATEMENTS,
          AiOcrUploadStatus.EXTRACTING,
          AiOcrUploadStatus.COMPLETED_WITH_ISSUES,
          AiOcrUploadStatus.ARCHIVE);

  /** Selection {@code ai.ocr.invoice.review.status.select} (Invoice.ocrReviewStatus). */
  static final List<String> OCR_REVIEW_STATUSES =
      Arrays.asList(
          BgOcrIntakeService.OCR_REVIEW_TO_REVIEW, BgOcrIntakeService.OCR_REVIEW_COMPLETE);

  /** Selection {@code ai.ocr.segment.type.select} (AiOcrUpload.segmentType). */
  static final List<String> SEGMENT_TYPES =
      Arrays.asList(
          AiOcrUploadStatus.SEGMENT_TYPE_INVOICE,
          AiOcrUploadStatus.SEGMENT_TYPE_MOVE,
          AiOcrUploadStatus.SEGMENT_TYPE_REVIEW,
          AiOcrUploadStatus.SEGMENT_TYPE_BANK_STATEMENT);

  private static void enumStr(
      ObjectNode props, String name, String description, List<String> values) {
    ObjectNode n = props.putObject(name);
    n.put("type", "string");
    n.put("description", description);
    com.fasterxml.jackson.databind.node.ArrayNode allowed = n.putArray("enum");
    values.forEach(allowed::add);
  }

  /**
   * A fixed-code filter, checked exactly: an unknown code used to reach the query and come back as
   * {@code not_found}, which reads as "no such records" rather than "that is not a status".
   */
  static String fixedCode(JsonNode args, String field, List<String> values) {
    String v = AiToolArgs.optText(args, field);
    if (v == null || values.contains(v)) {
      return v;
    }
    throw new OcrBlocked(
            AiBlockerCode.BAD_ARGUMENTS,
            "'"
                + v
                + "' is not a valid "
                + field
                + ". Use one of: "
                + String.join(", ", values)
                + ".")
        .asToolException();
  }

  private static void str(ObjectNode props, String name, String description) {
    ObjectNode n = props.putObject(name);
    n.put("type", "string");
    n.put("description", description);
  }

  private static void id(ObjectNode props, String name, String description) {
    ObjectNode n = props.putObject(name);
    n.put("type", "integer");
    n.put("description", description);
  }

  @Override
  public AiCapability capability() {
    return AiCapability.SUPPLIER_INVOICE;
  }

  @Override
  public AiToolKind kind() {
    return AiToolKind.READ;
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    OcrRecordQuery q = new OcrRecordQuery();
    q.setUploadId(AiToolArgs.optId(args, "uploadId"));
    q.setInvoiceId(AiToolArgs.optId(args, "invoiceId"));
    q.setInvoiceNumber(AiToolArgs.optText(args, "invoiceNumber"));
    q.setFileName(AiToolArgs.optText(args, "fileName"));
    q.setPartnerName(AiToolArgs.optText(args, "partnerName"));
    q.setEik(AiToolArgs.optText(args, "eik"));
    q.setIban(AiToolArgs.optText(args, "iban"));
    q.setStatusSelect(fixedCode(args, "status", STATUSES));
    q.setOcrReviewStatus(fixedCode(args, "ocrReviewStatus", OCR_REVIEW_STATUSES));
    q.setSegmentType(fixedCode(args, "segmentType", SEGMENT_TYPES));
    // carrierKind, docKey, fileHash and importArchiveId are not offered to the model (see
    // parametersSchema). OcrRecordQuery still supports them for the UI and for Java callers.
    // companyId is never read from the arguments — the service applies the active company itself.
    q.setCreatedFrom(date(args, "createdFrom"));
    q.setCreatedTo(date(args, "createdTo"));
    q.setLimit(AiToolArgs.limit(args, "limit", DEFAULT_MODEL_LIMIT, OcrRecordQuery.MAX_LIMIT));
    // offset is a row count to skip, not an id: 0 is the first page and a negative clamps to it.
    // optId would reject both as "must be a positive id", so read it with the paging helper
    // instead.
    int offset = AiToolArgs.offset(args, "offset", MAX_OFFSET);
    q.setOffset(offset);

    OcrRecordSearchService.SearchOutcome outcome;
    try {
      outcome = Beans.get(OcrRecordSearchService.class).search(q);
    } catch (IllegalArgumentException e) {
      // Every refusal the search raises is about the arguments (no criterion, an implausible ЕИК),
      // so it says so in code, not only in prose.
      throw new OcrBlocked(AiBlockerCode.BAD_ARGUMENTS, e.getMessage()).asToolException();
    }

    OcrRecordViewService view = Beans.get(OcrRecordViewService.class);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("totalMatches", outcome.getTotalMatches());
    out.put("returned", outcome.getRecords().size());
    out.put("truncated", outcome.isTruncated());
    out.put("offset", offset);

    if (outcome.isNotFound()) {
      out.put("outcome", offset > 0 ? "page" : "not_found");
      out.put("records", new ArrayList<>());
      if (offset > 0) {
        // Past the end of a set that does have matches. Saying "not_found" here would read as "your
        // filters match nothing", and the model would go and change them.
        out.put(
            "hint",
            "No records at offset "
                + offset
                + "; the "
                + outcome.getTotalMatches()
                + " matching records were already returned by the earlier pages. The set is"
                + " complete — do not page further and do not change the filters.");
        return out;
      }
      addFileNameSuggestions(out, q.getFileName(), view);
      return out;
    }

    List<Map<String, Object>> items = new ArrayList<>();
    for (AiOcrUpload u : outcome.getRecords()) {
      items.add(view.summarise(u));
    }

    // "Exactly one match" is a statement about the whole search, not about this page. On page two
    // of a thirty-record set the last page routinely holds one record, and answering 'single, do
    // not search again' there would end the job five records early — the exact failure paging was
    // added to fix.
    if (outcome.isUnique() && offset == 0) {
      AiOcrUpload single = outcome.single();
      out.put("outcome", "single");
      out.put("record", view.summarise(single));
      out.put(
          "nextStep",
          "Exactly one record matched. Call get_ocr_record with uploadId="
              + single.getId()
              + " to get the detail needed to explain its status. Do not search again.");
      return out;
    }

    out.put("outcome", offset > 0 ? "page" : "multiple");
    out.put("records", items);
    if (outcome.isTruncated()) {
      long nextOffset = (long) offset + items.size();
      out.put("nextOffset", nextOffset);
      out.put(
          "nextStep",
          "This is records "
              + (offset + 1)
              + "-"
              + nextOffset
              + " of "
              + outcome.getTotalMatches()
              + ". If the user asked for ALL of them, call this tool again with the SAME filters and"
              + " offset="
              + nextOffset
              + " to get the rest — do not stop here and do not report the job as finished.");
      return out;
    }

    out.put("complete", true);
    if (offset > 0) {
      out.put(
          "nextStep",
          "Records "
              + (offset + 1)
              + "-"
              + (offset + items.size())
              + " of "
              + outcome.getTotalMatches()
              + ". That is the last page: you now have the complete set.");
      return out;
    }
    // One instruction, not two that pull in opposite directions ("narrow it" vs "pick one").
    out.put(
        "nextStep",
        "All "
            + outcome.getTotalMatches()
            + " matches are in 'records'. If the request covers all of them, work through them. If"
            + " it means one, take it only when the records themselves single it out (invoice"
            + " number, supplier, date); otherwise narrow the search with another filter, or ask"
            + " the user which one — never assume the first. Do not repeat this search unchanged.");
    return out;
  }

  /**
   * Turns a fruitless file-name search into something actionable.
   *
   * <p>Without this a mistyped name (very common with Cyrillic ones) yields a bare not_found, and
   * the agent burns its whole tool budget re-searching variants of the same word.
   */
  private void addFileNameSuggestions(
      Map<String, Object> out, String fileName, OcrRecordViewService view) {
    if (fileName == null || fileName.trim().isEmpty()) {
      out.put("hint", "No OCR record matched. Try fewer or broader criteria.");
      return;
    }
    OcrRecordSearchService svc = Beans.get(OcrRecordSearchService.class);
    List<AiOcrUpload> near = svc.suggestByFileName(fileName, MAX_SUGGESTIONS);
    if (near.isEmpty()) {
      out.put(
          "hint",
          "No OCR record has a file name like '"
              + fileName.trim()
              + "', and nothing similar exists either. Do NOT search this name again. Search a "
              + "different field (supplier name, EIK, invoice number, date range) or ask the user.");
      return;
    }
    List<Map<String, Object>> items = new ArrayList<>();
    for (AiOcrUpload u : near) {
      Map<String, Object> m = new LinkedHashMap<>(view.summarise(u));
      m.put("similarity", round2(svc.fileNameSimilarity(fileName, u.getFileName())));
      items.add(m);
    }
    out.put("suggestions", items);
    out.put(
        "hint",
        "No exact match for '"
            + fileName.trim()
            + "', but these file names are close — the searched name was probably mistyped. Do NOT "
            + "repeat this search. Use the uploadId of the best suggestion with get_ocr_record, or "
            + "ask the user to confirm which one they meant.");
  }

  private static double round2(double v) {
    return Math.round(v * 100d) / 100d;
  }

  private static LocalDate date(JsonNode args, String field) {
    String v = AiToolArgs.optText(args, field);
    if (v == null) {
      return null;
    }
    try {
      return LocalDate.parse(v);
    } catch (DateTimeParseException e) {
      throw new AiToolException("'" + field + "' must be an ISO date (yyyy-MM-dd).");
    }
  }
}

package com.axelor.apps.aiocr.service.record;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.service.invoice.InvoiceLineService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceHeaderEditService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceSide;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldChange;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.apps.aiocr.service.supplier.SupplierSearchService;
import com.axelor.apps.base.db.Partner;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One request, one correction to a whole OCR draft supplier invoice.
 *
 * <p>Everything a correction needs used to be six separate tools: change the supplier, set the
 * dates, replace the lines, then one call per line for its product and one more for its tax. A
 * single realistic fix — "wrong supplier, both dates missing, lines are nonsense" — cost six model
 * round trips, six parked proposals and six things for the user to read before agreeing. This
 * expresses the same intent as one typed request.
 *
 * <h2>It orchestrates; it does not reimplement</h2>
 *
 * Every operation is delegated to the service that already owned it, unchanged:
 *
 * <ul>
 *   <li><b>supplier</b> — {@link OcrSupplierCorrectionService#changeSupplier}, which re-derives the
 *       invoicing address, payment mode and condition, bank details, fiscal position and due date;
 *   <li><b>header fields</b> — {@link OcrRecordUpdateService#apply}, with its closed field
 *       vocabulary, per-field validation and date-dependent refresh (due date, payment delay, line
 *       tax versions);
 *   <li><b>lines</b> — {@link OcrInvoiceLineEditService}, which derives every amount Axelor is
 *       supposed to derive and refuses anything that does not add up.
 * </ul>
 *
 * <p>There is no new accounting logic here and no field patcher: this class chooses an order and
 * collects results.
 *
 * <h2>The order, and why it is that order</h2>
 *
 * <ol>
 *   <li><b>create the draft</b> (only with {@code createIfMissing}) — a record whose ЕИК matched no
 *       Partner at intake has no Invoice at all, so there is nothing for the later steps to address
 *       until one exists. Built by {@code BgOcrIntakeService.createDraftForSupplier}, which reuses
 *       the very method ordinary intake uses, from the OCR result already stored on the record — so
 *       no document is re-scanned and there is no second draft-building implementation.
 *   <li><b>supplier</b> — an identity change re-derives address, payment terms and fiscal position,
 *       so anything else must land on the new identity, not the old one.
 *   <li><b>header</b> — the dates come next because everything after them depends on them: on a
 *       purchase invoice the due date and each line's date-versioned {@code TaxLine} are both
 *       resolved from the origin date.
 *   <li><b>replaceLines</b> — a wholesale rebuild deletes the lines a per-line edit would address,
 *       so it has to happen before those.
 *   <li><b>line updates</b>, then <b>deletes</b>, then <b>adds</b> — updates and deletes address
 *       ids that exist now; adds create new ones, and running them last keeps the ids a caller was
 *       given valid for the whole request.
 * </ol>
 *
 * <p>Atomicity is not this class's job and is deliberately not attempted here: it runs inside the
 * single transaction this method opens, and every service it calls is {@code @Transactional} and
 * therefore joins rather than commits. A failure anywhere throws, and the whole request — including
 * the parts that had already succeeded — rolls back.
 *
 * <p>Each delegate re-derives review status, readiness and the audit note as it goes, so the state
 * reported at the end is computed from the saved record rather than assembled from the requests.
 */
@Singleton
public class SupplierInvoiceEditService {

  private static final Logger LOG = LoggerFactory.getLogger(SupplierInvoiceEditService.class);

  /** Ceiling on line operations in one request, so a runaway suggestion cannot loop. */
  public static final int MAX_LINE_OPERATIONS = 40;

  public static final String OP_ADD = "add";
  public static final String OP_UPDATE = "update";
  public static final String OP_DELETE = "delete";

  private final OcrRecordViewService viewService;

  @Inject
  public SupplierInvoiceEditService(OcrRecordViewService viewService) {
    this.viewService = viewService;
  }

  // ---------------------------------------------------------------------------
  // Input
  // ---------------------------------------------------------------------------

  /** One line operation: what to do, to which line, with which values. */
  public static final class LineOperation {
    private final String operation;
    private final Long lineId;
    private final OcrInvoiceLineEditService.LineSpec spec;

    public LineOperation(String operation, Long lineId, OcrInvoiceLineEditService.LineSpec spec) {
      this.operation = operation == null ? null : operation.trim().toLowerCase();
      this.lineId = lineId;
      this.spec = spec;
    }

    public String getOperation() {
      return operation;
    }

    public Long getLineId() {
      return lineId;
    }

    public OcrInvoiceLineEditService.LineSpec getSpec() {
      return spec;
    }
  }

  /**
   * A whole correction, as a caller means it. Every part is optional; at least one must be present.
   *
   * <p>Closed by construction — the header fields are the ones {@link
   * OcrRecordUpdateService#WRITABLE_FIELDS} already allows and nothing else.
   */
  public static final class EditRequest {
    private Long supplierId;
    private String supplierEik;
    private boolean createIfMissing;
    private final Map<String, String> header = new LinkedHashMap<>();
    private JsonNode extendedHeader;
    private List<Long> replaceLineIds;
    private List<OcrInvoiceLineEditService.LineSpec> replacementLines;
    private final List<LineOperation> lineOperations = new ArrayList<>();

    /** Point the invoice at a different existing supplier. */
    public EditRequest setSupplierId(Long supplierId) {
      this.supplierId = supplierId;
      return this;
    }

    /**
     * Create the draft supplier invoice first, when this upload never got one because no supplier
     * matched its ЕИК at intake. Needs {@code supplierId} or {@code supplierEik}.
     */
    public EditRequest setCreateIfMissing(boolean createIfMissing) {
      this.createIfMissing = createIfMissing;
      return this;
    }

    /**
     * The supplier's ЕИК, as an alternative to {@code supplierId}.
     *
     * <p>This exists for the one case where an id cannot be known in advance: creating the supplier
     * and creating this invoice are two parts of a single approval, so at the moment the request is
     * built the Partner does not exist and has no id. The ЕИК does exist — it is printed on the
     * document and is the same identity key intake matches on — and it is resolved when the action
     * actually runs, by which time the earlier part of the same approval has created the Partner.
     */
    public EditRequest setSupplierEik(String supplierEik) {
      this.supplierEik =
          supplierEik == null || supplierEik.trim().isEmpty() ? null : supplierEik.trim();
      return this;
    }

    /**
     * One writable header field.
     *
     * @param field must be in {@link OcrRecordUpdateService#WRITABLE_FIELDS}
     */
    public EditRequest header(String field, String value) {
      if (field != null) {
        header.put(field, value);
      }
      return this;
    }

    /**
     * Replace a set of lines wholesale.
     *
     * @param replaceLineIds ids to remove; null or empty means every line
     */
    public EditRequest replaceLines(
        List<Long> replaceLineIds, List<OcrInvoiceLineEditService.LineSpec> lines) {
      this.replaceLineIds = replaceLineIds;
      this.replacementLines = lines;
      return this;
    }

    public EditRequest addOperation(LineOperation operation) {
      if (operation != null) {
        lineOperations.add(operation);
      }
      return this;
    }

    /**
     * The header fields this package does not own, as the caller sent them.
     *
     * <h2>Why there are two header buckets</h2>
     *
     * {@link OcrRecordUpdateService} owns six fields, and owns them for a reason: each one has
     * consequences this package has to run afterwards — a changed date recomputes the due date and
     * re-resolves the lines' tax versions, and any change at all recomputes the review status,
     * rebuilds the VAT summary and syncs the upload. That is not generic invoice behaviour and does
     * not belong in a shared service.
     *
     * <p>Everything else about a supplier invoice — its payment mode, its journal, the NAP document
     * type — is exactly the same as on a customer invoice, and is applied by {@code
     * InvoiceHeaderEditService}. Those fields were simply unreachable before: the closed six-field
     * allowlist was the whole of what the assistant could change on a header.
     *
     * <p>Split by name rather than by call, so one request still means one transaction.
     */
    public EditRequest setExtendedHeader(JsonNode extendedHeader) {
      this.extendedHeader = extendedHeader;
      return this;
    }

    public JsonNode getExtendedHeader() {
      return extendedHeader;
    }

    public Map<String, String> getHeader() {
      return header;
    }

    public List<LineOperation> getLineOperations() {
      return lineOperations;
    }

    public boolean isEmpty() {
      return !createIfMissing
          && supplierId == null
          && supplierEik == null
          && header.isEmpty()
          && (extendedHeader == null || extendedHeader.size() == 0)
          && (replacementLines == null || replacementLines.isEmpty())
          && lineOperations.isEmpty();
    }
  }

  /** What the whole request did, plus the recomputed state of the invoice. */
  public static final class EditResult {
    private final List<String> changes = new ArrayList<>();
    private final List<String> steps = new ArrayList<>();
    private Long uploadId;
    private Long invoiceId;
    private Map<String, Object> record = new LinkedHashMap<>();

    /** Every individual change, in the order it was applied. */
    public List<String> getChanges() {
      return changes;
    }

    /** Which operations ran, for a compact summary. */
    public List<String> getSteps() {
      return steps;
    }

    public Long getUploadId() {
      return uploadId;
    }

    public Long getInvoiceId() {
      return invoiceId;
    }

    /**
     * The compact outcome, recomputed from the saved record.
     *
     * <p>Deliberately not the full record projection it used to be. A correction on a real invoice
     * returned every line, the extraction snapshot and the whole accumulated audit note, which
     * exceeded the AI tool-result ceiling — so the caller lost the outcome of a change that had
     * already been committed. See {@code OcrRecordViewService.writeSummary}.
     */
    public Map<String, Object> getRecord() {
      return record;
    }
  }

  // ---------------------------------------------------------------------------
  // Execution
  // ---------------------------------------------------------------------------

  /**
   * Applies the whole request, or refuses it and changes nothing.
   *
   * <h2>Why the transaction is one call down</h2>
   *
   * Rollback is what makes the sequence atomic <em>in the database</em>, and it is not the whole
   * story: the invoice, its lines and the upload are managed entities, so the fields a refused
   * request had already set are still on them in the persistence context after the rollback. Guice
   * persist's entity manager outlives one transaction — it is opened per request — so the next
   * write in the same chat turn would flush those fields with its own commit, and a correction the
   * user was told had been refused would land by side effect.
   *
   * <p>So when the transaction has actually ended in a refusal, the upload and its invoice are
   * detached here, outside it; the next read loads them from the database as they really are. When
   * a caller's own transaction is still open, the session is left to it — whether its work is kept
   * is its decision, not this method's. The same shape as {@code InvoiceEditService.apply}.
   *
   * @throws IllegalArgumentException for anything the delegates refuse; nothing is left half-done
   */
  public EditResult apply(long uploadId, EditRequest request, String reason) {
    try {
      // Through the injector: @Transactional is a method interceptor, and a self-call bypasses it.
      return intercepted().applyInTransaction(uploadId, request, reason);
    } catch (RuntimeException e) {
      discardRolledBackState(uploadId);
      throw e;
    }
  }

  /** This service through the injector, so {@code @Transactional} applies. A seam for tests. */
  protected SupplierInvoiceEditService intercepted() {
    return Beans.get(SupplierInvoiceEditService.class);
  }

  /**
   * Drops what a rolled-back request left on the upload and its invoice in the persistence context.
   *
   * <p>Only those two — detaching the invoice cascades to its lines — never the whole session: a
   * chat turn may hold other managed entities that must stay attached. And only once the
   * transaction is over. Best effort: a session that cannot be inspected is left as it is, and the
   * refusal is still the answer.
   */
  protected void discardRolledBackState(long uploadId) {
    try {
      if (JPA.em().getTransaction().isActive()) {
        return;
      }
      AiOcrUpload managed = JPA.em().find(AiOcrUpload.class, uploadId);
      if (managed == null) {
        return;
      }
      Invoice invoice = managed.getInvoice();
      if (invoice != null) {
        JPA.em().detach(invoice);
      }
      JPA.em().detach(managed);
    } catch (RuntimeException e) {
      LOG.debug(
          "AI OCR: could not clear the session after a refused correction: {}", e.getMessage());
    }
  }

  /** The request itself. Call {@link #apply}, which also cleans up after a refusal. */
  @Transactional(rollbackOn = Exception.class)
  public EditResult applyInTransaction(long uploadId, EditRequest request, String reason) {
    if (request == null || request.isEmpty()) {
      throw new IllegalArgumentException(
          "Nothing to change. Give a supplierId (or supplierEik), createIfMissing, at least one"
              + " header field, replacement lines, or a line operation.");
    }
    if (request.lineOperations.size() > MAX_LINE_OPERATIONS) {
      throw new IllegalArgumentException(
          "At most " + MAX_LINE_OPERATIONS + " line operations may be requested at once.");
    }
    EditResult result = new EditResult();

    // ── 0. create the draft, when there is not one yet ──────────────────────
    // Intake creates no Invoice at all when the document's ЕИК matches no Partner (the stock
    // Invoice requires one), so the record sits Needs review with nothing to correct. Once the
    // accountant has created the supplier, this turns that record into an ordinary OCR draft in the
    // same operation as the corrections that follow — so the user approves once and never has to go
    // and press a "create invoice" button between two halves of one intent.
    boolean created = false;
    if (request.createIfMissing) {
      AiOcrUpload target = OcrDraftInvoiceGuard.resolveForCreationIfNeeded(uploadId);
      if (target != null) {
        Partner supplier = resolveSupplier(request, uploadId);
        try {
          Beans.get(BgOcrIntakeService.class).createDraftForSupplier(uploadId, supplier);
        } catch (IllegalArgumentException | IllegalStateException e) {
          throw e;
        } catch (Exception e) {
          // Anything the intake pipeline throws has to become a runtime failure, or the caller's
          // transaction would commit a half-built draft.
          throw new IllegalStateException(
              "The invoice draft could not be created from the stored OCR result: "
                  + e.getMessage(),
              e);
        }
        created = true;
        result.steps.add("createDraft");
        result.changes.add(
            "created the draft supplier invoice from the stored OCR result, supplier: "
                + supplier.getFullName());
      }
    }

    // Guard once up front so an unusable target is refused before any delegate runs. Each delegate
    // re-checks for itself — they are reachable independently — but this makes the refusal one
    // clear
    // message rather than one from whichever step happened to be first.
    OcrDraftInvoiceGuard.resolve(uploadId);

    // ── 1. supplier identity ────────────────────────────────────────────────
    // Skipped when step 0 just built the draft for this very supplier: changeSupplier would find it
    // already set and add a "supplier already X; not changed" line that reads like a failure.
    //
    // Resolved rather than read straight off the request, so an invoice that already exists can
    // also
    // be pointed at a supplier named by ЕИК — the case where the draft was built at intake with the
    // wrong counterparty and the right one is being created in this same approval.
    Partner requested = created ? null : resolveSupplierOrNull(request);
    if (requested != null) {
      OcrSupplierCorrectionService.SupplierChangeResult changed;
      try {
        changed =
            Beans.get(OcrSupplierCorrectionService.class)
                .changeSupplier(uploadId, requested.getId());
      } catch (OcrSupplierCorrectionService.SupplierChangeRefusedException e) {
        throw new IllegalArgumentException(e.getMessage());
      }
      if (changed.isChanged()) {
        result.steps.add("supplier");
        result.changes.add(
            "supplier: "
                + (changed.getPreviousPartnerName() == null
                    ? "(none)"
                    : changed.getPreviousPartnerName())
                + " → "
                + changed.getNewPartnerName());
        for (Map.Entry<String, String> field : changed.getRefreshedFields().entrySet()) {
          result.changes.add(
              "re-derived "
                  + field.getKey()
                  + ": "
                  + (field.getValue() == null ? "(empty)" : field.getValue()));
        }
      } else {
        result.changes.add("supplier already " + changed.getNewPartnerName() + "; not changed");
      }
    }

    // ── 2. header fields ────────────────────────────────────────────────────
    if (!request.header.isEmpty()) {
      OcrRecordUpdateService.UpdateResult updated;
      try {
        updated = Beans.get(OcrRecordUpdateService.class).apply(uploadId, request.header);
      } catch (OcrRecordUpdateService.UpdateRefusedException e) {
        throw new IllegalArgumentException(e.getMessage());
      }
      // A rejected field is a caller error, not a partial success: fail the whole request rather
      // than report "3 of 4 fields changed" and leave the user to work out which.
      if (!updated.getRejected().isEmpty()) {
        StringBuilder sb = new StringBuilder("These header fields were refused:");
        for (OcrRecordUpdateService.Rejection r : updated.getRejected()) {
          sb.append("\n- ").append(r.getField()).append(": ").append(r.getReason());
        }
        throw new IllegalArgumentException(sb.toString());
      }
      if (updated.isChanged()) {
        result.steps.add("header");
        for (OcrRecordUpdateService.Change c : updated.getChanged()) {
          result.changes.add(
              c.getField()
                  + ": "
                  + (c.getOldValue() == null ? "(empty)" : c.getOldValue())
                  + " → "
                  + (c.getNewValue() == null ? "(empty)" : c.getNewValue()));
        }
      }
    }

    // ── 2b. the header fields this package does not own ─────────────────────
    // Payment mode, journal, fiscal position, the NAP document type: the same fields, with the same
    // meanings, as on a customer invoice, applied by the shared service rather than duplicated into
    // the six-field allowlist above. In the same transaction, so one request is still
    // all-or-nothing.
    if (request.getExtendedHeader() != null && request.getExtendedHeader().size() > 0) {
      OcrDraftInvoiceGuard.Target target = OcrDraftInvoiceGuard.resolve(uploadId);
      InvoiceHeaderEditService.Result extended =
          Beans.get(InvoiceHeaderEditService.class)
              .apply(target.getInvoice(), InvoiceSide.SUPPLIER, request.getExtendedHeader());
      // Same rule as the six fields above: a rejected field fails the whole request rather than
      // reporting "3 of 4 changed" and leaving the user to work out which.
      if (!extended.getRejected().isEmpty()) {
        StringBuilder sb = new StringBuilder("These header fields were refused:");
        for (InvoiceHeaderEditService.Rejection r : extended.getRejected()) {
          sb.append("\n- ").append(r.getField()).append(": ").append(r.getReason());
        }
        throw new IllegalArgumentException(sb.toString());
      }
      if (extended.isChanged()) {
        result.steps.add("header");
        for (AiInvoiceFieldChange c : extended.getChanges()) {
          result.changes.add(
              c.getField()
                  + ": "
                  + (c.getOldValue() == null ? "(empty)" : c.getOldValue())
                  + " → "
                  + (c.getNewValue() == null ? "(empty)" : c.getNewValue()));
        }
        JPA.save(target.getInvoice());
      }
      if (extended.isLinesNeedRecompute()) {
        try {
          Beans.get(InvoiceLineService.class)
              .updateLinesAfterFiscalPositionChange(target.getInvoice());
          result.steps.add("lineTaxes");
        } catch (Exception e) {
          throw new IllegalStateException(
              "The fiscal position changed but Axelor could not update the lines for it: "
                  + e.getMessage(),
              e);
        }
      }
    }

    OcrInvoiceLineEditService lines = Beans.get(OcrInvoiceLineEditService.class);

    // ── 3. wholesale line replacement ───────────────────────────────────────
    if (request.replacementLines != null && !request.replacementLines.isEmpty()) {
      OcrInvoiceLineEditService.LineEditResult replaced =
          lines.replaceLines(uploadId, request.replaceLineIds, request.replacementLines, reason);
      result.steps.add("replaceLines");
      result.changes.addAll(replaced.getAudit());
    }

    // ── 4. per-line operations, updates → deletes → adds ────────────────────
    for (LineOperation op : ordered(request.lineOperations)) {
      OcrInvoiceLineEditService.LineEditResult edited;
      switch (op.getOperation() == null ? "" : op.getOperation()) {
        case OP_UPDATE:
          edited = lines.updateLine(uploadId, requireLineId(op), op.getSpec(), reason);
          break;
        case OP_DELETE:
          edited = lines.deleteLine(uploadId, requireLineId(op), reason);
          break;
        case OP_ADD:
          edited = lines.addLine(uploadId, op.getSpec(), reason);
          break;
        default:
          throw new IllegalArgumentException(
              "'"
                  + op.getOperation()
                  + "' is not a line operation. Use "
                  + OP_ADD
                  + ", "
                  + OP_UPDATE
                  + " or "
                  + OP_DELETE
                  + ".");
      }
      result.steps.add("line:" + op.getOperation());
      result.changes.addAll(edited.getAudit());
    }

    // ── final state, read back from the record ──────────────────────────────
    AiOcrUpload upload = Beans.get(AiOcrUploadRepository.class).find(uploadId);
    result.uploadId = uploadId;
    result.invoiceId = upload.getInvoice() == null ? null : upload.getInvoice().getId();
    result.record = viewService.writeSummary(upload, result.steps, result.changes);

    LOG.info(
        "OCR record {}: invoice corrected in one request ({}) — {} change(s).",
        uploadId,
        String.join(", ", result.steps),
        result.changes.size());
    return result;
  }

  /**
   * The supplier the new draft belongs to: by id when the caller has one, otherwise by ЕИК.
   *
   * <p>Both routes end at {@code requireSupplierInScope}, so a partner from another company is
   * invisible either way and a partner that is not marked as a supplier is refused either way. The
   * ЕИК route only ever <em>finds</em> — it never creates. If the ЕИК matches nothing, that is a
   * refusal telling the caller to create the supplier first, not a licence to invent one.
   *
   * <p>Ambiguity is refused rather than resolved. Two partners sharing an ЕИК is a data problem,
   * and picking one of them would attach an invoice to an arbitrary counterparty.
   */
  protected Partner resolveSupplier(EditRequest request, long uploadId) {
    Partner supplier = resolveSupplierOrNull(request);
    if (supplier == null) {
      throw new IllegalArgumentException(
          "OCR record "
              + uploadId
              + " has no invoice yet, so creating one needs the supplier it belongs to. Give"
              + " supplierId, or supplierEik when the supplier is being created in this same"
              + " approval.");
    }
    return supplier;
  }

  /** As {@link #resolveSupplier}, but {@code null} when the request named no supplier at all. */
  protected Partner resolveSupplierOrNull(EditRequest request) {
    if (request.supplierId == null && request.supplierEik == null) {
      return null;
    }
    Long id = request.supplierId;
    if (id == null) {
      SupplierSearchService.SearchResult found =
          Beans.get(SupplierSearchService.class)
              .search(new SupplierSearchService.Criteria().setEik(request.supplierEik));
      if (found.getMatches().isEmpty()) {
        throw new IllegalArgumentException(
            "No supplier with ЕИК "
                + request.supplierEik
                + " exists, so the invoice cannot be created. Create the supplier first — suppliers"
                + " are never created automatically.");
      }
      if (!found.isUnique()) {
        throw new IllegalArgumentException(
            "ЕИК "
                + request.supplierEik
                + " matches "
                + found.getMatches().size()
                + " partners, so which one the invoice belongs to is not decidable. Pass"
                + " supplierId.");
      }
      id = found.sole().getId();
    }
    try {
      return Beans.get(OcrSupplierCorrectionService.class).requireSupplierInScope(id);
    } catch (OcrSupplierCorrectionService.SupplierChangeRefusedException e) {
      throw new IllegalArgumentException(e.getMessage());
    }
  }

  /**
   * Updates first, then deletes, then adds.
   *
   * <p>Stable within each group, so two updates apply in the order the caller wrote them. Sorting
   * rather than trusting the caller's order is what keeps a supplied {@code lineId} meaningful for
   * the whole request: an add that ran first would shift nothing, but a delete that ran first could
   * remove a line a later update names.
   */
  protected List<LineOperation> ordered(List<LineOperation> operations) {
    List<LineOperation> out = new ArrayList<>();
    for (String phase : new String[] {OP_UPDATE, OP_DELETE, OP_ADD}) {
      for (LineOperation op : operations) {
        if (phase.equals(op.getOperation())) {
          out.add(op);
        }
      }
    }
    // Anything with an unrecognised operation is kept so the loop above reports it by name rather
    // than silently dropping it.
    for (LineOperation op : operations) {
      if (!out.contains(op)) {
        out.add(op);
      }
    }
    return out;
  }

  private static long requireLineId(LineOperation op) {
    if (op.getLineId() == null) {
      throw new IllegalArgumentException(
          "A line " + op.getOperation() + " needs the lineId it applies to.");
    }
    return op.getLineId();
  }
}

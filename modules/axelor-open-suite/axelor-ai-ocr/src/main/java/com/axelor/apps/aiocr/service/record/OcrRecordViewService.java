package com.axelor.apps.aiocr.service.record;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoiceLineTax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceStructuredBlockers;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceViewService;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldRegistry;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.aiocr.service.ocr.BgOcrBankDetails;
import com.axelor.apps.aiocr.service.readiness.OcrAccountingTotals;
import com.axelor.apps.aiocr.service.readiness.OcrAmountReconciliation;
import com.axelor.apps.aiocr.service.readiness.OcrInvoiceReadiness;
import com.axelor.apps.aiocr.service.readiness.OcrSupplierInvoiceReadinessService;
import com.axelor.apps.base.db.Partner;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compact, safe projections of an OCR record.
 *
 * <p>Deliberately never returns whole entities: an AI tool result is model-visible text, so only
 * fields that are useful for reconciliation are exposed. Filesystem paths ({@code sourcePath}) and
 * storage locations ({@code blobUrl}) are withheld.
 *
 * <p>Reusable by any caller — the AI tools are thin adapters over these methods.
 *
 * <p><b>Self-guarding.</b> Every method that projects a whole record ({@link #summarise}, {@link
 * #detail}, {@link #rawOcr}) refuses a record outside the caller's active company on its own,
 * rather than relying on the caller to have checked. See {@link #assertInScope}.
 */
@Singleton
public class OcrRecordViewService {

  /** Default ceiling on an embedded raw-OCR payload. */
  public static final int DEFAULT_MAX_RAW_BYTES = 48_000;

  /** Where a record's stored OCR JSON comes from — see {@link #rawOcr}. */
  public enum RawSource {
    /** Per-segment result of a split child: the correct slice, no sibling data. */
    CACHED_SEGMENT,
    /** The Azure analyzeResult of a plain, single-document upload — this document only. */
    SINGLE_DOCUMENT,
    /** The single Azure analyzeResult of a split/bank-statement carrier. */
    SPLIT_SOURCE,
    /** A page-range marker, not an OCR result (bank-statement children carry this). */
    PAGE_RANGE_MARKER,
    /**
     * Nothing stored — e.g. a record processed before OCR JSON retention, or a failed extraction.
     */
    NONE
  }

  /**
   * Ceiling on how many invoice lines {@link #detail} embeds. A tool result must stay under {@code
   * aiaccountant.tool.max.result.bytes} (32 KB), and no supplier invoice needs more than this many
   * lines to diagnose; the flag tells the caller when it is seeing a subset.
   */
  public static final int MAX_LINES = 50;

  /**
   * Per-line projection. Without this a model cannot see line accounts, products or taxes at all,
   * so it cannot explain — let alone repair — the commonest reasons an invoice is unventilatable.
   */
  public List<Map<String, Object>> lines(Invoice invoice) {
    List<Map<String, Object>> out = new ArrayList<>();
    List<InvoiceLine> lines = invoice.getInvoiceLineList();
    if (lines == null) {
      return out;
    }
    for (int i = 0; i < lines.size() && i < MAX_LINES; i++) {
      InvoiceLine line = lines.get(i);
      Map<String, Object> l = new LinkedHashMap<>();
      l.put("index", i);
      l.put("lineId", line.getId());
      // Intake sets productName from the description when the document carries no product code, so
      // on most OCR lines these two hold the same string. Emitting it twice doubles the largest
      // free-text field in the projection for no information at all.
      String description = line.getDescription();
      String productName = line.getProductName();
      l.put("description", description);
      if (productName != null && !productName.equals(description)) {
        l.put("productName", productName);
      }
      l.put("qty", line.getQty());
      l.put("unitId", line.getUnit() == null ? null : line.getUnit().getId());
      l.put("unitName", line.getUnit() == null ? null : line.getUnit().getName());
      l.put("unitPrice", line.getPrice());
      l.put("discountTypeSelect", line.getDiscountTypeSelect());
      l.put("discountAmount", line.getDiscountAmount());
      l.put("exTaxTotal", line.getExTaxTotal());
      l.put(
          "taxTotal",
          line.getInTaxTotal() == null || line.getExTaxTotal() == null
              ? null
              : line.getInTaxTotal().subtract(line.getExTaxTotal()));
      l.put("inTaxTotal", line.getInTaxTotal());
      l.put("taxRate", line.getTaxRate());
      l.put("taxCode", line.getTaxCode());
      l.put("hasTaxLine", line.getTaxLineSet() != null && !line.getTaxLineSet().isEmpty());
      l.put("productId", line.getProduct() == null ? null : line.getProduct().getId());
      l.put("productCode", line.getProduct() == null ? null : line.getProduct().getCode());
      l.put("accountId", line.getAccount() == null ? null : line.getAccount().getId());
      l.put("accountCode", line.getAccount() == null ? null : line.getAccount().getCode());
      l.put("fixedAssets", line.getFixedAssets());
      l.put(
          "fixedAssetCategoryId",
          line.getFixedAssetCategory() == null ? null : line.getFixedAssetCategory().getId());
      l.put(
          "fixedAssetCategoryName",
          line.getFixedAssetCategory() == null ? null : line.getFixedAssetCategory().getName());
      l.put(
          "cutOffStartDate",
          line.getCutOffStartDate() == null ? null : line.getCutOffStartDate().toString());
      l.put(
          "cutOffEndDate",
          line.getCutOffEndDate() == null ? null : line.getCutOffEndDate().toString());
      l.put("coefficient", line.getCoefficient());
      l.put(
          "analyticDistributionTemplateId",
          line.getAnalyticDistributionTemplate() == null
              ? null
              : line.getAnalyticDistributionTemplate().getId());
      l.put(
          "analyticDistributionTemplateName",
          line.getAnalyticDistributionTemplate() == null
              ? null
              : line.getAnalyticDistributionTemplate().getName());
      // BG VAT-credit type / ZDDFL income type, contributed the same way InvoiceViewService.lines
      // reads them — so a line looked up via get_ocr_record and one via get_supplier_invoice never
      // disagree about what these fields currently hold.
      Map<String, Object> contributed =
          Beans.get(AiInvoiceFieldRegistry.class).readLineFields(line);
      for (Map.Entry<String, Object> e : contributed.entrySet()) {
        l.putIfAbsent(e.getKey(), e.getValue());
      }
      out.add(l);
    }
    return out;
  }

  /**
   * Arithmetic verdict on the header and the lines, delegated in full to {@link
   * OcrAmountReconciliation} so the record projection, the readiness rules and the line editor
   * cannot reach three different conclusions from the same figures.
   *
   * <p>Note the change of meaning that came with invoice 576: {@code linesReconcile} and {@code
   * headerReconciles} now mean <em>equal</em>, not "within a tolerance", and the VAT is compared as
   * well as the net — so a document whose declared VAT is a stotinka above what its lines produce
   * is reported as a rounding difference instead of a match.
   */
  public Map<String, Object> amountsReconcile(Invoice invoice) {
    return OcrAmountReconciliation.of(invoice).toMap();
  }

  /**
   * The invoice's VAT summary — {@code invoiceLineTaxList}, one row per rate.
   *
   * <p>Compact by construction: a Bulgarian purchase invoice has at most three rates, so the rows
   * are listed in full rather than counted. {@code rowCount == 0} on an invoice whose lines carry a
   * tax is the condition behind Axelor's "There is no taxes on this invoice" prompt.
   */
  /**
   * The most recent audit block only, not the whole accumulated history.
   *
   * <p>{@code aiValidationNotes} is append-only: intake writes a readiness report, then every
   * correction, validation and posting appends another block separated by a blank line. On real
   * records it averages ~7 KB and reaches 14 KB — roughly 1,700 to 3,500 tokens of prose returned
   * on <em>every</em> read of the record, growing with each write.
   *
   * <p>Nothing decision-critical is lost by trimming it. Everything a caller has to act on is
   * already returned structurally and freshly computed: the blockers in {@code readiness}, the
   * arithmetic in {@code amountsReconcile}, the outstanding fields in {@code missingReviewFields}.
   * The notes are a human audit trail, and the newest block is the only part that describes the
   * current state — the rest is history a reviewer reads on the record itself, in the form.
   */
  protected String latestNote(String notes) {
    if (notes == null || notes.trim().isEmpty()) {
      return null;
    }
    String trimmed = notes.trim();
    int lastBlock = trimmed.lastIndexOf("\n\n");
    String latest = lastBlock < 0 ? trimmed : trimmed.substring(lastBlock + 2);
    int omitted = lastBlock < 0 ? 0 : trimmed.length() - latest.length();
    if (latest.length() > MAX_NOTE_CHARS) {
      latest = latest.substring(0, MAX_NOTE_CHARS) + "…";
    }
    return omitted == 0
        ? latest
        : latest
            + "\n(only the latest audit entry is shown; "
            + omitted
            + " earlier characters"
            + " are on the record itself.)";
  }

  /** Ceiling on the single audit block returned, so one very long entry cannot undo the saving. */
  public static final int MAX_NOTE_CHARS = 1200;

  public Map<String, Object> vatSummary(Invoice invoice) {
    Map<String, Object> out = new LinkedHashMap<>();
    List<InvoiceLineTax> rows =
        invoice.getInvoiceLineTaxList() == null
            ? new ArrayList<>()
            : invoice.getInvoiceLineTaxList();
    out.put("rowCount", rows.size());
    List<Map<String, Object>> detail = new ArrayList<>();
    for (InvoiceLineTax row : rows) {
      Map<String, Object> r = new LinkedHashMap<>();
      TaxLine taxLine = row.getTaxLine();
      r.put(
          "taxCode",
          taxLine == null || taxLine.getTax() == null ? null : taxLine.getTax().getCode());
      r.put("rate", taxLine == null ? null : taxLine.getValue());
      r.put("exTaxBase", row.getExTaxBase());
      r.put("taxTotal", row.getTaxTotal());
      r.put(
          "vatAccount", row.getImputedAccount() == null ? null : row.getImputedAccount().getCode());
      detail.add(r);
    }
    out.put("rows", detail);
    return out;
  }

  /**
   * Refuses to project a record that does not belong to the caller's active company.
   *
   * <p>This class is a projection service: it turns a record into the map a caller reads. Until now
   * it trusted whoever handed it the record, which meant its safety was a property of its call
   * sites rather than of itself — three correct callers today and one forgetful one tomorrow. The
   * check therefore lives here, so a new caller cannot introduce a leak by omission.
   *
   * <p>The message is the not-found wording used everywhere else in this package: an id from
   * another company must not be usable to confirm that the record exists.
   */
  protected void assertInScope(AiOcrUpload u) {
    if (u == null) {
      throw new IllegalArgumentException("No OCR record was supplied.");
    }
    if (!AiCompanyScope.owns(u.getCompany())) {
      throw new IllegalArgumentException("No OCR record with id " + u.getId() + ".");
    }
  }

  /** One line summary, used for candidate lists. */
  public Map<String, Object> summarise(AiOcrUpload u) {
    assertInScope(u);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("uploadId", u.getId());
    m.put("fileName", u.getFileName());
    m.put("status", u.getStatusSelect());
    m.put("createdOn", u.getCreatedOn() == null ? null : u.getCreatedOn().toString());
    m.put("companyId", u.getCompany() == null ? null : u.getCompany().getId());
    m.put("company", u.getCompany() == null ? null : u.getCompany().getName());

    Invoice invoice = u.getInvoice();
    m.put("invoiceId", invoice == null ? null : invoice.getId());
    m.put("supplierInvoiceNb", invoice == null ? null : invoice.getSupplierInvoiceNb());
    Partner partner = invoice == null ? null : invoice.getPartner();
    m.put("partnerId", partner == null ? null : partner.getId());
    m.put("partnerName", partner == null ? null : partner.getName());
    m.put("ocrReviewStatus", invoice == null ? null : invoice.getOcrReviewStatus());
    // A non-invoice document is booked straight to a Move, and until now the candidate list said
    // nothing about it — so an upload that HAD produced something looked unprocessed here, and the
    // move it produced could only be found by opening the record. The detail view already reports
    // it; a search result is where the id is first quoted, so it belongs in both.
    m.put("moveId", u.getMove() == null ? null : u.getMove().getId());
    return m;
  }

  /** Full compact projection: upload + invoice + partner + review state + OCR availability. */
  public Map<String, Object> detail(AiOcrUpload u) {
    assertInScope(u);
    Map<String, Object> m = new LinkedHashMap<>();

    Map<String, Object> upload = new LinkedHashMap<>();
    upload.put("uploadId", u.getId());
    upload.put("fileName", u.getFileName());
    upload.put("status", u.getStatusSelect());
    upload.put("engineUsed", u.getEngineUsed());
    upload.put("confidence", u.getConfidence());
    upload.put("createdOn", u.getCreatedOn() == null ? null : u.getCreatedOn().toString());
    upload.put("processedOn", u.getProcessedOn() == null ? null : u.getProcessedOn().toString());
    upload.put("retryCount", u.getRetryCount());
    upload.put("companyId", u.getCompany() == null ? null : u.getCompany().getId());
    upload.put("company", u.getCompany() == null ? null : u.getCompany().getName());
    upload.put("uploadedBy", u.getUploadedBy() == null ? null : u.getUploadedBy().getName());
    upload.put("carrierKind", u.getCarrierKind());
    upload.put("segmentType", u.getSegmentType());
    upload.put(
        "importArchiveId", u.getImportArchive() == null ? null : u.getImportArchive().getId());
    upload.put("docKey", u.getDocKey());
    upload.put("fileHash", u.getFileHash());
    upload.put("progressPercent", u.getProgressPercent());
    m.put("upload", upload);

    Map<String, Object> review = new LinkedHashMap<>();
    review.put("extractionErrors", u.getExtractionErrors());
    review.put("extractionInformation", u.getExtractionInformation());
    // rawExtractedJson is a human-readable snapshot despite its name — surfaced as such.
    review.put("extractionSnapshot", u.getRawExtractedJson());
    m.put("extraction", review);

    Invoice invoice = u.getInvoice();
    if (invoice == null) {
      m.put("invoice", null);
    } else {
      Map<String, Object> inv = new LinkedHashMap<>();
      inv.put("invoiceId", invoice.getId());
      inv.put("supplierInvoiceNb", invoice.getSupplierInvoiceNb());
      inv.put(
          "invoiceDate",
          invoice.getInvoiceDate() == null ? null : invoice.getInvoiceDate().toString());
      inv.put("statusSelect", invoice.getStatusSelect());
      // The raw number means DRAFT/VALIDATED/VENTILATED/CANCELED; emit the label too so the model
      // reads the lifecycle state the same way it does from get_supplier_invoice, and does not
      // confuse it with the string upload.status above (a different concept entirely).
      inv.put("status", Beans.get(InvoiceViewService.class).statusLabel(invoice.getStatusSelect()));
      inv.put("ocrReviewStatus", invoice.getOcrReviewStatus());
      inv.put("ocrMissingFields", invoice.getOcrMissingFields());
      inv.put("aiGenerated", invoice.getAiGenerated());
      inv.put("aiConfidence", invoice.getAiConfidence());
      inv.put("aiReviewStatus", invoice.getAiReviewStatus());
      inv.put("aiValidationNotes", latestNote(invoice.getAiValidationNotes()));
      inv.put("externalReference", invoice.getExternalReference());
      inv.put("exTaxTotal", invoice.getExTaxTotal());
      inv.put("taxTotal", invoice.getTaxTotal());
      inv.put("inTaxTotal", invoice.getInTaxTotal());
      inv.put("currency", invoice.getCurrency() == null ? null : invoice.getCurrency().getCode());
      inv.put("companyId", invoice.getCompany() == null ? null : invoice.getCompany().getId());
      inv.put(
          "bankDetailsIban",
          invoice.getBankDetails() == null ? null : invoice.getBankDetails().getIban());
      inv.put(
          "paymentMode",
          invoice.getPaymentMode() == null ? null : invoice.getPaymentMode().getCode());
      inv.put(
          "paymentCondition",
          invoice.getPaymentCondition() == null ? null : invoice.getPaymentCondition().getCode());
      inv.put("dueDate", invoice.getDueDate() == null ? null : invoice.getDueDate().toString());
      inv.put(
          "originDate",
          invoice.getOriginDate() == null ? null : invoice.getOriginDate().toString());
      // Recomputed live so the answer matches what the form would show right now.
      List<String> missing = BgOcrIntakeService.missingReviewFields(invoice);
      inv.put("missingReviewFields", missing);
      inv.put("readyForReviewCompletion", missing.isEmpty());
      // The authoritative figures, and the only ones that may be quoted as what will be validated,
      // posted or settled. Placed before amountsReconcile on purpose: the diagnostic is read in the
      // light of these, not the other way round.
      inv.put("accountingTotals", OcrAccountingTotals.of(invoice).toMap());
      inv.put("amountsReconcile", amountsReconcile(invoice));
      // The invoice's Tax section, per rate. A caller asking why Validate warns about missing taxes
      // needs this and not the lines: the lines say which rate applies, this is the aggregation
      // Axelor posts from.
      inv.put("vatSummary", vatSummary(invoice));
      m.put("invoice", inv);

      // Accounting readiness, recomputed live like missingReviewFields above. Exposed as
      // structured lists rather than the aiValidationNotes prose, because a caller needs to act on
      // individual blockers.
      OcrInvoiceReadiness readiness =
          Beans.get(OcrSupplierInvoiceReadinessService.class).check(invoice, new ArrayList<>());
      Map<String, Object> ready = new LinkedHashMap<>();
      ready.put("validateBlockers", readiness.getValidateBlockers());
      ready.put("ventilateBlockers", readiness.getVentilateBlockers());
      // Warnings are not blockers but must not be swallowed either: a rounding difference between
      // the document's VAT and the VAT its lines produce lives here, and it is the only place the
      // caller is told which figure will actually be posted.
      ready.put("warnings", readiness.getWarnings());
      ready.put("populated", readiness.getPopulated());
      m.put("readiness", ready);
      m.put("readyToValidate", readiness.getValidateBlockers().isEmpty());
      // The ventilate-ready twin, computed from the blockers already gathered above. Without it a
      // caller asking "can this be posted?" had to infer the answer from an empty ventilateBlockers
      // list, the way get_supplier_invoice's postReady spares it from doing.
      m.put("readyToVentilate", readiness.getVentilateBlockers().isEmpty());
      // Field-addressable findings alongside the prose ones above — "lines[2].account: missing"
      // rather than a count. Never a gate: usable below is still decided by readiness alone.
      m.put("structuredBlockers", InvoiceStructuredBlockers.build(invoice));

      // The real bar for "this invoice needs no human". ocrReviewStatus alone is not it: it checks
      // only partner/address/bank/currency/total, so an invoice with no line accounts and no tax
      // can read "complete" while being impossible to validate or ventilate.
      m.put(
          "usable",
          missing.isEmpty()
              && readiness.getValidateBlockers().isEmpty()
              && readiness.getVentilateBlockers().isEmpty());

      m.put("lines", lines(invoice));

      Partner partner = invoice.getPartner();
      if (partner == null) {
        m.put("partner", null);
      } else {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("partnerId", partner.getId());
        p.put("name", partner.getName());
        p.put("fullName", partner.getFullName());
        p.put("registrationCode", partner.getRegistrationCode());
        p.put("taxNbr", partner.getTaxNbr());
        m.put("partner", p);
      }
    }

    m.put("move", u.getMove() == null ? null : u.getMove().getId());

    Map<String, Object> raw = new LinkedHashMap<>();
    RawSource source = rawSourceOf(u);
    raw.put("source", source.name());
    raw.put(
        "available",
        source == RawSource.CACHED_SEGMENT
            || source == RawSource.SINGLE_DOCUMENT
            || source == RawSource.SPLIT_SOURCE);
    raw.put("description", describe(source));
    // Full IBANs already present in the stored OCR text. Cheap, and it removes the commonest
    // reason to go back to the user: a compact projection shows only the tail of an account
    // number, and the whole number is usually sitting in the payload.
    raw.put("ibanCandidates", ibanCandidates(u));
    m.put("rawOcr", raw);

    return m;
  }

  /**
   * Which stored OCR payload belongs to THIS record.
   *
   * <p>The distinction matters: a split carrier's {@code splitSourceJson} is the analyzeResult of a
   * whole multi-invoice PDF, so returning it for a child would hand back every sibling invoice. A
   * split child instead carries {@code cachedOcrJson} — the per-segment {@code OcrExtractResult}
   * produced by the segmenter, which holds only that slice's text and fields.
   *
   * <p>A plain single upload stores neither: the Azure JSON is used during processing and not
   * retained, so {@link RawSource#NONE} is the honest answer rather than an empty string.
   */
  public RawSource rawSourceOf(AiOcrUpload u) {
    if (notBlank(u.getCachedOcrJson())) {
      // Bank-statement children carry a {"fromPage":n,"toPage":m} marker here, not an OCR result.
      return isPageRangeMarker(u.getCachedOcrJson())
          ? RawSource.PAGE_RANGE_MARKER
          : RawSource.CACHED_SEGMENT;
    }
    // Checked before splitSourceJson: exactly one of the two is ever populated, and a record that
    // has its own single-document payload is by definition not a carrier.
    if (notBlank(u.getOcrResultJson())) {
      return RawSource.SINGLE_DOCUMENT;
    }
    if (notBlank(u.getSplitSourceJson())) {
      return RawSource.SPLIT_SOURCE;
    }
    return RawSource.NONE;
  }

  /**
   * What a WRITE returns: the outcome, not the record.
   *
   * <p>Write tools used to answer with {@link #detail}, which embeds every line, the extraction
   * snapshot and the accumulated {@code aiValidationNotes}. On a real invoice that blew past the 32
   * KB tool-result ceiling, the dispatcher replaced the whole thing with {@code result_too_large},
   * and the model — having lost the outcome of a change that had actually been committed — went
   * looking for a smaller payload to send. One such "fix" was to rewrite a 38-line invoice as a
   * single summary line, which is data loss caused by a reporting limit.
   *
   * <p>So a write reports what a caller needs to say what happened and nothing else: whether it
   * worked, which records to refresh, how much changed, the totals, and whether the invoice is now
   * usable. Line detail is a read; it is available from {@link #detail} when it is genuinely
   * wanted. The counts here are deliberately enough to notice that lines went missing.
   *
   * @param changes one line per applied change, already bounded by the caller
   * @param steps which operations ran
   */
  public Map<String, Object> writeSummary(AiOcrUpload u, List<String> steps, List<String> changes) {
    assertInScope(u);
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("status", "applied");
    m.put("uploadId", u.getId());

    Invoice invoice = u.getInvoice();
    m.put("invoiceId", invoice == null ? null : invoice.getId());
    if (steps != null) {
      m.put("applied", steps);
    }
    if (changes != null) {
      m.put("changes", cap(changes, MAX_CHANGES));
      if (changes.size() > MAX_CHANGES) {
        m.put("changesOmitted", changes.size() - MAX_CHANGES);
      }
    }
    if (invoice == null) {
      return m;
    }

    m.put("supplierInvoiceNb", invoice.getSupplierInvoiceNb());
    m.put(
        "supplier",
        invoice.getPartner() == null
            ? null
            : invoice.getPartner().getSimpleFullName() == null
                ? invoice.getPartner().getName()
                : invoice.getPartner().getSimpleFullName());
    m.put(
        "invoiceDate",
        invoice.getInvoiceDate() == null ? null : invoice.getInvoiceDate().toString());
    m.put(
        "originDate", invoice.getOriginDate() == null ? null : invoice.getOriginDate().toString());
    m.put(
        "bankDetailsIban",
        invoice.getBankDetails() == null ? null : invoice.getBankDetails().getIban());

    Map<String, Object> totals = new LinkedHashMap<>();
    totals.put("exTaxTotal", invoice.getExTaxTotal());
    totals.put("taxTotal", invoice.getTaxTotal());
    totals.put("inTaxTotal", invoice.getInTaxTotal());
    totals.put("currency", invoice.getCurrency() == null ? null : invoice.getCurrency().getCode());
    m.put("totals", totals);

    List<InvoiceLine> lines =
        invoice.getInvoiceLineList() == null ? new ArrayList<>() : invoice.getInvoiceLineList();
    int noAccount = 0;
    int noTax = 0;
    for (InvoiceLine line : lines) {
      if (line.getAccount() == null) {
        noAccount++;
      }
      if (line.getTaxLineSet() == null || line.getTaxLineSet().isEmpty()) {
        noTax++;
      }
    }
    Map<String, Object> lineInfo = new LinkedHashMap<>();
    lineInfo.put("count", lines.size());
    lineInfo.put("withoutAccount", noAccount);
    lineInfo.put("withoutTax", noTax);
    m.put("lines", lineInfo);
    // The per-rate aggregation Axelor posts the VAT from. Reported because an empty one is what
    // makes Validate ask "There is no taxes on this invoice"; see OcrInvoiceTaxSummaryService.
    m.put("vatSummary", vatSummary(invoice));

    List<String> missing = BgOcrIntakeService.missingReviewFields(invoice);
    OcrInvoiceReadiness readiness =
        Beans.get(OcrSupplierInvoiceReadinessService.class).check(invoice, new ArrayList<>());
    m.put("ocrReviewStatus", invoice.getOcrReviewStatus());
    m.put("missingReviewFields", missing);
    m.put("accountingTotals", OcrAccountingTotals.of(invoice).toMap());
    m.put("amountsReconcile", OcrAmountReconciliation.of(invoice).toMap());
    Map<String, Object> ready = new LinkedHashMap<>();
    ready.put("validateBlockers", cap(readiness.getValidateBlockers(), MAX_BLOCKERS));
    ready.put("ventilateBlockers", cap(readiness.getVentilateBlockers(), MAX_BLOCKERS));
    ready.put("warnings", cap(readiness.getWarnings(), MAX_BLOCKERS));
    m.put("readiness", ready);
    boolean validateReady = readiness.getValidateBlockers().isEmpty();
    m.put("readyToValidate", validateReady);
    // Field-addressable findings alongside the prose ones above — "lines[2].account: missing"
    // rather than a count — so a caller can walk straight to the field that needs fixing. Never a
    // gate: readiness above is still what decides usable.
    m.put("structuredBlockers", InvoiceStructuredBlockers.build(invoice));
    m.put(
        "usable",
        missing.isEmpty()
            && readiness.getValidateBlockers().isEmpty()
            && readiness.getVentilateBlockers().isEmpty());

    // What the client should reload. The chat panel already refreshes from writeResult, so this is
    // for the model: it says which record it just changed without inviting a re-read of it.
    Map<String, Object> refresh = new LinkedHashMap<>();
    refresh.put("model", Invoice.class.getName());
    refresh.put("id", invoice.getId());
    m.put("refresh", refresh);
    m.put(
        "note",
        "This is the saved state, recomputed. Report it as it is — do not re-read the record to"
            + " check, and never rewrite real invoice lines to make a result smaller.");
    return m;
  }

  /** Ceilings on the lists a write summary embeds, so one call cannot grow without bound. */
  public static final int MAX_CHANGES = 40;

  private static final int MAX_BLOCKERS = 20;

  private static List<String> cap(List<String> values, int max) {
    if (values == null) {
      return new ArrayList<>();
    }
    return values.size() <= max ? values : new ArrayList<>(values.subList(0, max));
  }

  /** Ceiling on reported IBAN candidates; a document listing more than this is not a normal one. */
  public static final int MAX_IBAN_CANDIDATES = 5;

  /**
   * Every full Bulgarian IBAN in this record's own stored OCR text, in the order printed.
   *
   * <p>Why it is in the default projection rather than behind {@code includeRawOcr}: a compact
   * record shows an account as its last four digits, and being unable to see the rest was being
   * resolved by asking the user to type an IBAN that the stored payload already contained in full.
   * Reading it here costs one regex pass and makes that question unnecessary.
   *
   * <p>Deliberately restricted to a record's <em>own</em> payload. A split carrier's {@code
   * splitSourceJson} covers every child of a multi-invoice PDF, so listing its IBANs against one
   * child would offer another document's bank account for this one — the precise mistake the bank
   * path exists to prevent. Carriers therefore report nothing here.
   *
   * <p>Evidence, not a decision: the IBANs are what the document says, and every use of one still
   * goes through the ownership checks in {@code OcrRecordUpdateService} / {@code
   * SupplierMaintenanceService}.
   */
  public List<String> ibanCandidates(AiOcrUpload u) {
    RawSource source = rawSourceOf(u);
    if (source != RawSource.CACHED_SEGMENT && source != RawSource.SINGLE_DOCUMENT) {
      return new ArrayList<>();
    }
    String payload = storedPayload(u, source);
    if (payload == null) {
      return new ArrayList<>();
    }
    List<String> found = BgOcrBankDetails.findAllBgIbans(payload);
    return found.size() > MAX_IBAN_CANDIDATES
        ? new ArrayList<>(found.subList(0, MAX_IBAN_CANDIDATES))
        : found;
  }

  /** The one stored payload that belongs to this record, or null when it has none. */
  protected String storedPayload(AiOcrUpload u, RawSource source) {
    switch (source) {
      case CACHED_SEGMENT:
        return u.getCachedOcrJson();
      case SINGLE_DOCUMENT:
        return u.getOcrResultJson();
      case SPLIT_SOURCE:
        return u.getSplitSourceJson();
      default:
        return null;
    }
  }

  /**
   * The stored OCR JSON for this record, bounded and always valid JSON.
   *
   * <p>Never calls Azure: this is purely a read of what processing already persisted.
   *
   * @param maxBytes ceiling on the embedded payload; over it, a bounded descriptor is returned with
   *     {@code truncated=true} instead of a cut-off string, so the caller always gets parseable
   *     JSON
   */
  public Map<String, Object> rawOcr(AiOcrUpload u, int maxBytes) {
    // The raw payload is the whole document's text; if anything in this class needs the scope
    // check,
    // it is this.
    assertInScope(u);
    Map<String, Object> out = new LinkedHashMap<>();
    RawSource source = rawSourceOf(u);
    out.put("uploadId", u.getId());
    out.put("source", source.name());
    out.put("description", describe(source));

    String payload = storedPayload(u, source);

    if (payload == null) {
      out.put("available", false);
      out.put("truncated", false);
      out.put("json", null);
      if (source == RawSource.PAGE_RANGE_MARKER) {
        // Still worth returning: it says which pages of the carrier this child covers.
        out.put("pageRange", u.getCachedOcrJson());
      }
      if (source == RawSource.NONE && u.getImportArchive() != null) {
        out.put(
            "hint",
            "This record has no stored OCR JSON of its own; its carrier upload "
                + u.getImportArchive().getId()
                + " may hold the source analyzeResult.");
      }
      return out;
    }

    int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
    out.put("available", true);
    out.put("sizeBytes", bytes);
    if (bytes > maxBytes) {
      // Cutting JSON mid-string would produce an unparseable result, so describe it instead.
      out.put("truncated", true);
      out.put("json", null);
      out.put(
          "note",
          "The stored OCR payload is "
              + bytes
              + " bytes, over the "
              + maxBytes
              + " byte limit. Ask for a specific field instead of the whole document.");
      out.put("preview", summarisePayload(payload));
    } else {
      out.put("truncated", false);
      out.put("json", payload);
    }
    return out;
  }

  /** Cheap structural summary used when the payload itself is too large to embed. */
  protected Map<String, Object> summarisePayload(String payload) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("length", payload.length());
    p.put("head", payload.substring(0, Math.min(400, payload.length())));
    List<String> keys = new ArrayList<>();
    // Top-level-ish key sniff; purely informational, never parsed by the caller.
    for (String candidate :
        new String[] {
          "analyzeResult",
          "content",
          "pages",
          "documents",
          "rawText",
          "rawJson",
          "supplierName",
          "invoiceNumber",
          "totalGross",
          "fromPage"
        }) {
      if (payload.contains("\"" + candidate + "\"")) {
        keys.add(candidate);
      }
    }
    p.put("containsKeys", keys);
    return p;
  }

  private static String describe(RawSource source) {
    switch (source) {
      case CACHED_SEGMENT:
        return "Per-segment OCR result for this split child (cachedOcrJson) — this slice only.";
      case SINGLE_DOCUMENT:
        return "Azure analyzeResult for this single-document upload (ocrResultJson) — this "
            + "document only.";
      case SPLIT_SOURCE:
        return "Single Azure analyzeResult for the whole carrier document (splitSourceJson); "
            + "it covers every child of this carrier, not one invoice.";
      case PAGE_RANGE_MARKER:
        return "This child stores a page-range marker, not an OCR result.";
      default:
        return "No Azure OCR JSON is retained for this record.";
    }
  }

  /**
   * A bank-statement child's cachedOcrJson is {"fromPage":n,"toPage":m}, not an OcrExtractResult.
   */
  protected static boolean isPageRangeMarker(String json) {
    String s = json.trim();
    return s.startsWith("{")
        && s.contains("\"fromPage\"")
        && s.contains("\"toPage\"")
        && !s.contains("\"rawText\"")
        && !s.contains("\"supplierName\"");
  }

  /** Bank-statement carriers are split by page range; kept for callers that branch on it. */
  public static boolean isBankStatementCarrier(AiOcrUpload u) {
    return AiOcrUploadStatus.CARRIER_KIND_PDF_BANK_STATEMENT.equals(u.getCarrierKind());
  }

  private static boolean notBlank(String v) {
    return v != null && !v.trim().isEmpty();
  }
}

package com.axelor.apps.aiocr.service.record;

import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.apps.aiocr.service.ocr.BgOcrBankDetails;
import com.axelor.apps.aiocr.service.ocr.BgOcrTaxEventDate;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractLine;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the document actually says, in a few hundred bytes instead of the whole OCR payload.
 *
 * <h2>Why this exists</h2>
 *
 * The only way to see the evidence behind a record used to be {@code get_ocr_record} with {@code
 * includeRawOcr=true}, which returns Azure's stored {@code analyzeResult} — tens to hundreds of
 * kilobytes of geometry, per-word bounding boxes and confidences. Two things followed. Over the AI
 * tool-result ceiling it came back as {@code result_too_large} and the caller learned nothing at
 * all; under it, it consumed the turn's whole context to answer a question as small as "what
 * address is printed on this invoice?". So the assistant asked the user for values that were
 * sitting in the payload, which is the opposite of the point.
 *
 * <p>Everything here is read from what processing already stored — {@code cachedOcrJson}, {@code
 * ocrResultJson} or {@code splitSourceJson}, rehydrated by the intake service's own {@link
 * BgOcrIntakeService#rehydrateExtraction}. Azure is never called again and the source PDF is never
 * touched, so this is free and cannot disagree with what intake saw.
 *
 * <h2>Two shapes</h2>
 *
 * <ul>
 *   <li><b>fields</b> — the structured values: supplier identity and address, IBAN/BIC, invoice
 *       number, dates, currency, totals, line count. This is what almost every question needs.
 *   <li><b>find</b> — a text search over the extracted document text, answered with the matching
 *       lines and their neighbours. For everything the structured fields do not carry: a contract
 *       number, an item description, a payment note.
 * </ul>
 *
 * <p>Both are hard-bounded ({@link #MAX_SNIPPETS} × {@link #MAX_SNIPPET_CHARS}), so no argument
 * combination can produce an unbounded result. Evidence only: nothing here writes, and a value read
 * from a document still goes through the same validation as any other input when it is used.
 */
@Singleton
public class OcrEvidenceService {

  private static final Logger LOG = LoggerFactory.getLogger(OcrEvidenceService.class);

  /** Ceiling on returned text snippets. */
  public static final int MAX_SNIPPETS = 8;

  /** Ceiling on each snippet, so one long line cannot fill the result. */
  public static final int MAX_SNIPPET_CHARS = 300;

  /** Ceiling on the line descriptions echoed back, which are only there to identify a line. */
  public static final int MAX_LINES = 25;

  private final AiOcrUploadRepository uploadRepo;

  @Inject
  public OcrEvidenceService(AiOcrUploadRepository uploadRepo) {
    this.uploadRepo = uploadRepo;
  }

  /** Raised when the record cannot be read; the message is safe to show a caller. */
  public static class EvidenceUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public EvidenceUnavailableException(String message) {
      super(message);
    }
  }

  /**
   * The evidence for one record.
   *
   * @param find optional text to look for in the document text; null for the structured fields only
   */
  public Map<String, Object> evidence(long uploadId, String find, boolean includeLines) {
    AiOcrUpload upload = requireReadable(uploadId);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("uploadId", uploadId);
    out.put("fileName", upload.getFileName());

    OcrExtractResult result;
    try {
      result = Beans.get(BgOcrIntakeService.class).rehydrateExtraction(upload);
    } catch (RuntimeException e) {
      // A record processed before OCR retention, or one whose extraction failed, has nothing to
      // quote. Saying so plainly is the useful answer — it is the difference between "the document
      // does not say" and "I could not look".
      LOG.info("No stored OCR extraction for record {}: {}", uploadId, e.getMessage());
      out.put("available", false);
      out.put(
          "reason",
          "No OCR extraction is stored for this record, so the document text cannot be quoted.");
      return out;
    }
    out.put("available", true);
    out.put("fields", fields(result));
    if (includeLines) {
      out.put("documentLines", lines(result));
    }
    if (find != null && !find.trim().isEmpty()) {
      out.put("find", find.trim());
      out.put("matches", search(result.getRawText(), find.trim()));
    }
    return out;
  }

  /**
   * The document lines containing a given text — for a caller that has to CHECK one figure rather
   * than read the whole document.
   *
   * <p>{@link #evidence} already searches, but it answers with a map the caller then has to pick
   * apart, and its {@code matches} key is absent both when nothing matched and when there was
   * nothing to search. The one caller that must tell those two apart is {@code
   * correct_ocr_document_total}: "the figure is not printed on this document" is a refusal, and "I
   * could not look" is a different refusal. Hence a method with a three-valued answer rather than a
   * second reading of the generic one.
   *
   * <p>Same scope and permission checks as {@link #evidence}, in the same order and with the same
   * wording, because this reads the same document text.
   *
   * @return the matching lines (empty when the text is not in the document), or {@code null} when
   *     no extraction is stored and nothing could be looked at
   * @throws EvidenceUnavailableException when the record is not this company's or not readable
   */
  public List<String> quote(long uploadId, String needle) {
    AiOcrUpload upload = requireReadable(uploadId);
    if (needle == null || needle.trim().isEmpty()) {
      return new ArrayList<>();
    }
    OcrExtractResult result;
    try {
      result = Beans.get(BgOcrIntakeService.class).rehydrateExtraction(upload);
    } catch (RuntimeException e) {
      LOG.info("No stored OCR extraction for record {}: {}", uploadId, e.getMessage());
      return null;
    }
    return search(result.getRawText(), needle.trim());
  }

  /**
   * The record, once it is established that this caller may read it.
   *
   * <p>Out of scope answers exactly as unknown: an id from another company must not confirm that
   * the record exists. Same wording as everywhere else in this package.
   */
  protected AiOcrUpload requireReadable(long uploadId) {
    AiOcrUpload upload = uploadRepo.find(uploadId);
    if (upload == null || !AiCompanyScope.owns(upload.getCompany())) {
      throw new EvidenceUnavailableException("No OCR record with id " + uploadId + ".");
    }
    if (!Beans.get(JpaSecurity.class)
        .isPermitted(JpaSecurity.CAN_READ, AiOcrUpload.class, upload.getId())) {
      throw new EvidenceUnavailableException(
          "You do not have permission to read OCR record " + uploadId + ".");
    }
    return upload;
  }

  /** The structured values, exactly as extraction read them. Null means the document had none. */
  protected Map<String, Object> fields(OcrExtractResult r) {
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("supplierName", r.getSupplierName());
    f.put("supplierEik", r.getSupplierEik());
    f.put("supplierVat", r.getSupplierVat());
    // The whole printed address line and the parsed parts side by side: the raw line is what
    // manage_supplier's address.raw wants, and the parts say what was actually recognised in it.
    Map<String, Object> address = new LinkedHashMap<>();
    address.put("raw", r.getSupplierAddress());
    address.put("zip", r.getSupplierAddrZip());
    address.put("city", r.getSupplierAddrCity());
    address.put("street", r.getSupplierAddrStreet());
    address.put("number", r.getSupplierAddrNumber());
    address.put("recipient", r.getSupplierAddressRecipient());
    f.put("supplierAddress", address);
    f.put("iban", r.getIban());
    // Every full Bulgarian IBAN in the text, not only the structured one: an invoice often prints
    // several accounts and the structured field carries at most one of them.
    f.put("ibanCandidates", BgOcrBankDetails.findAllBgIbans(r.getRawText()));
    f.put("swift", r.getSwift());
    f.put("invoiceNumber", r.getInvoiceNumber());
    f.put("invoiceDate", r.getInvoiceDate() == null ? null : r.getInvoiceDate().toString());
    f.put("dueDate", r.getDueDate() == null ? null : r.getDueDate().toString());
    // Only the date the document labels as such, with how sure that reading is — a later correction
    // (edit_supplier_invoice bgVatTaxEventDate) needs the same answer intake had, not a guess.
    BgOcrTaxEventDate.Reading taxEvent = BgOcrTaxEventDate.read(r.getRawText());
    f.put("taxEventDate", taxEvent.date() == null ? null : taxEvent.date().toString());
    f.put("taxEventDateStatus", taxEvent.status().name());
    f.put("paymentTerm", r.getPaymentTerm());
    f.put("currency", r.getCurrency());
    f.put("totalBase", r.getTotalBase());
    f.put("totalVat", r.getTotalVat());
    f.put("totalGross", r.getTotalGross());
    f.put("customerName", r.getCustomerName());
    f.put("customerEik", r.getCustomerEik());
    f.put("lineCount", r.getLines() == null ? 0 : r.getLines().size());
    f.put("hasText", r.getRawText() != null && !r.getRawText().trim().isEmpty());
    return f;
  }

  /** The extracted line items, as the document listed them — identification, not accounting. */
  protected List<Map<String, Object>> lines(OcrExtractResult r) {
    List<Map<String, Object>> out = new ArrayList<>();
    if (r.getLines() == null) {
      return out;
    }
    for (OcrExtractLine line : r.getLines()) {
      if (out.size() >= MAX_LINES) {
        break;
      }
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("description", clip(line.getDescription()));
      m.put("productCode", line.getProductCode());
      m.put("qty", line.getQty());
      m.put("unitPrice", line.getUnitPrice());
      m.put("netAmount", line.getNetAmount());
      m.put("grossAmount", line.getGrossAmount());
      m.put("vatRate", line.getVatRate());
      out.add(m);
    }
    return out;
  }

  /**
   * Lines of the document text containing {@code needle}, case-insensitively.
   *
   * <p>Line-oriented rather than character-window: OCR text keeps the document's own line breaks,
   * so the line a value appears on is the unit a person would read — and a label and its value are
   * usually on the same line or the next one, which is why the following line is included when the
   * match line is short.
   */
  protected List<String> search(String text, String needle) {
    List<String> out = new ArrayList<>();
    if (text == null || text.isEmpty()) {
      return out;
    }
    String[] lines = text.split("\\r?\\n");
    String wanted = needle.toLowerCase(Locale.ROOT);
    for (int i = 0; i < lines.length && out.size() < MAX_SNIPPETS; i++) {
      if (!lines[i].toLowerCase(Locale.ROOT).contains(wanted)) {
        continue;
      }
      StringBuilder snippet = new StringBuilder(lines[i].trim());
      if (snippet.length() < 40 && i + 1 < lines.length) {
        snippet.append(" | ").append(lines[i + 1].trim());
      }
      out.add(clip(snippet.toString()));
    }
    return out;
  }

  private static String clip(String value) {
    if (value == null) {
      return null;
    }
    String v = value.trim();
    return v.length() <= MAX_SNIPPET_CHARS ? v : v.substring(0, MAX_SNIPPET_CHARS) + "…";
  }
}

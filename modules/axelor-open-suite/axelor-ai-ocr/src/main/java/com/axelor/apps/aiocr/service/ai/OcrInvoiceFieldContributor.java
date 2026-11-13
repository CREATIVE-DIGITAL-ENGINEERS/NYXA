package com.axelor.apps.aiocr.service.ai;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldContributor;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.service.record.OcrInvoiceLink;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports which OCR upload(s) an invoice was built from, wherever the invoice tools describe it.
 *
 * <h2>The gap this closes</h2>
 *
 * An upload and the invoice it produces get their ids from different sequences: upload 973 becomes
 * invoice 651. The upload-anchored tools say both numbers, because they start from the upload — but
 * {@code search_supplier_invoices} and {@code get_supplier_invoice} start from the invoice and used
 * to report only its own id. So the assistant could truthfully call the same document 973 in one
 * sentence and 651 in the next, and a user reading the OCR queue beside the invoice list had
 * nothing connecting the two. Reporting the upload ids on the invoice side removes the ambiguity at
 * the source rather than asking the model to remember a mapping.
 *
 * <p>The direction of the dependency is what makes this legal: {@code axelor-ai-accounting} defines
 * the interface and must not know about OCR; this module implements it and already depends on that
 * one. Same arrangement as {@link OcrInvoiceReadinessContributor}.
 *
 * <h2>Read-only, and a list on purpose</h2>
 *
 * Nothing here is editable, so only the read half of the contract is implemented and no field is
 * offered to the tool schema. The value is a list because {@code AiOcrUpload.invoice} has no
 * uniqueness behind it — a re-scan or a duplicate re-import leaves several uploads on one invoice,
 * and naming only one of them would be a guess. An invoice nobody scanned contributes nothing and
 * costs one indexed query.
 *
 * <p>It is contributed as a ROW field, not a header field, and only once. A row is where an id is
 * first quoted, so this has to survive into a search result — and because {@code detail()} is built
 * on top of the row, contributing it here covers the detail view too. Implementing both halves
 * would run the lookup twice for one {@code get_supplier_invoice} call and change nothing.
 *
 * <h2>Company scope</h2>
 *
 * The lookup goes through {@link OcrInvoiceLink}, which drops any upload outside the active
 * company. An id from another company's books must not reach the assistant, and an invoice is not a
 * capability to read one.
 */
@Singleton
public class OcrInvoiceFieldContributor implements AiInvoiceFieldContributor {

  /** The field name is the same one the Invoice grid column and the entity formula field use. */
  public static final String FIELD_UPLOAD_IDS = "ocrUploadIds";

  @Override
  public Map<String, Object> readRowFields(Invoice invoice) {
    if (invoice == null || invoice.getId() == null) {
      return Collections.emptyMap();
    }
    // Company-scoped and deterministic already; sorted ascending here so the value reads the same
    // way as the "Upload ID(s)" column on the invoice list.
    List<AiOcrUpload> uploads = Beans.get(OcrInvoiceLink.class).uploadsForInvoice(invoice.getId());
    if (uploads == null || uploads.isEmpty()) {
      return Collections.emptyMap();
    }
    List<Long> ids = new ArrayList<>(uploads.size());
    for (AiOcrUpload upload : uploads) {
      if (upload != null && upload.getId() != null) {
        ids.add(upload.getId());
      }
    }
    if (ids.isEmpty()) {
      return Collections.emptyMap();
    }
    Collections.sort(ids);
    StringBuilder joined = new StringBuilder();
    for (Long id : ids) {
      if (joined.length() > 0) {
        joined.append(", ");
      }
      joined.append(id);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put(FIELD_UPLOAD_IDS, joined.toString());
    return out;
  }
}

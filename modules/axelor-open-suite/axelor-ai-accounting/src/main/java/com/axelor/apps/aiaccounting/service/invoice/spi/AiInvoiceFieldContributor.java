package com.axelor.apps.aiaccounting.service.invoice.spi;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Lets a module add its own editable fields to the invoice tools without this module knowing what
 * they mean.
 *
 * <h2>The problem this solves</h2>
 *
 * A Bulgarian installation needs the NAP document type and the чл.163а/167а supply type to be
 * editable, and a per-line VAT-credit type and income type with them. Those fields are merged onto
 * the generated {@code Invoice} and {@code InvoiceLine} entities by {@code axelor-bg-localization},
 * so they are technically reachable from here — but hardcoding their names, their selection codes
 * and the rules about when they make sense would put one country's tax law inside the generic
 * invoice service, where nobody maintaining it would think to look.
 *
 * <p>So the localisation keeps its own knowledge and contributes it. A contributor owns both
 * halves: it <b>declares</b> its fields, so the tool schema can offer them and reject a value
 * outside the selection, and it <b>applies</b> them, by calling its own typed setters. That second
 * half is why this is not a reflection layer — nothing here writes a field by name, and a
 * contributor that is not installed simply contributes nothing.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>Return null from an apply method for a field this contributor does not own. Two
 *       contributors must never own the same field name; the registry logs and refuses if they do.
 *   <li>Throw {@link IllegalArgumentException} to refuse a value — a bad selection code, a field
 *       that makes no sense on this document. The message is shown to the caller.
 *   <li>A JSON null value means the caller asked for the field to be cleared. Refuse it if the
 *       field is not clearable; the declared spec is what the schema promised.
 *   <li>Never touch anything but the field being applied, and never save. The caller owns the
 *       transaction.
 * </ul>
 */
public interface AiInvoiceFieldContributor {

  /** The extra header fields this contributor offers. Empty when it has none. */
  default List<AiInvoiceFieldSpec> headerFields() {
    return Collections.emptyList();
  }

  /** The extra line fields this contributor offers. Empty when it has none. */
  default List<AiInvoiceFieldSpec> lineFields() {
    return Collections.emptyList();
  }

  /**
   * Called once, right after a brand-new invoice is generated and before any caller-supplied header
   * overrides are applied, so a contributor can default one of its OWN header fields when it is
   * blank. An explicit value applied afterwards always wins — this only fills a gap.
   *
   * <p>Must only set a field it owns, must only touch it when currently blank, and — like every
   * other method here — must never save. A contributor with nothing safe to default leaves this as
   * a no-op rather than guessing; most fields have no safe default at all.
   */
  default void applyCreationDefaults(Invoice invoice) {
    // no-op
  }

  /**
   * Applies one header field this contributor owns.
   *
   * @param value the raw JSON the caller sent; a null node means "clear it"
   * @return what changed, or null when this contributor does not own {@code field}
   */
  default AiInvoiceFieldChange applyHeaderField(Invoice invoice, String field, JsonNode value) {
    return null;
  }

  /**
   * Applies one line field this contributor owns.
   *
   * @return what changed, or null when this contributor does not own {@code field}
   */
  default AiInvoiceFieldChange applyLineField(InvoiceLine line, String field, JsonNode value) {
    return null;
  }

  /**
   * The current value of each header field this contributor owns, for the detail view.
   *
   * <h2>Why reading is contributed too, not just writing</h2>
   *
   * A field the tools can set but never show is a field nobody can check. If the NAP document type
   * were writable but absent from {@code get_customer_invoice}, the only way to find out what it
   * currently says would be to open the form — and the assistant would be editing a value it cannot
   * see. So the same contributor answers both, and the generic view service stays free of any
   * knowledge of what these fields mean.
   *
   * <p>Keys should be the field names from {@link #headerFields()}. Values must be plain scalars —
   * strings, numbers, booleans — because they go straight into a JSON reply.
   */
  default Map<String, Object> readHeaderFields(Invoice invoice) {
    return Collections.emptyMap();
  }

  /** The current value of each line field this contributor owns, for the detail view. */
  default Map<String, Object> readLineFields(InvoiceLine line) {
    return Collections.emptyMap();
  }

  /**
   * The few values worth repeating on every row of a SEARCH result.
   *
   * <h2>Why this is separate from {@link #readHeaderFields}</h2>
   *
   * A search can return twenty-five invoices, and every key here is paid for twenty-five times
   * against a tool-result ceiling of 32 KB. Most contributed fields do not earn that: a NAP
   * document type is something you ask about one invoice you have already chosen, so it belongs in
   * the detail view and nowhere else. A few do earn it — an identifier that makes the row itself
   * ambiguous until it is present, such as the OCR upload the invoice came from, because a row is
   * exactly where an id is first quoted and a second call to disambiguate has already come too
   * late.
   *
   * <p>So the default is nothing, and a contributor opts in deliberately for the fields that pass
   * that bar. Whatever is returned here reaches the detail view too — {@code detail()} is built on
   * top of the row — so a field belongs in EITHER this method or {@link #readHeaderFields}, never
   * in both: putting it in both makes the contributor do its work twice for one detail call.
   */
  default Map<String, Object> readRowFields(Invoice invoice) {
    return Collections.emptyMap();
  }
}

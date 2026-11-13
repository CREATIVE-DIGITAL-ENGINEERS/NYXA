package com.axelor.apps.aiaccounting.service.invoice.spi;

import com.axelor.apps.account.db.Invoice;
import java.util.Collections;
import java.util.List;

/**
 * Lets a module add its own blockers and its own state to the shared invoice readiness answer.
 *
 * <h2>Why the shared layer has to be able to ask</h2>
 *
 * An OCR-created supplier invoice has blockers no generic check knows about: whether the extracted
 * amounts still reconcile against the scanned document, whether its review is complete. Those live
 * in {@code axelor-ai-ocr}, which depends on this module — so this module cannot call into it, and
 * a dependency the other way would be a cycle.
 *
 * <p>The tempting alternative was to leave OCR blockers on the OCR path only, on the reasoning that
 * an OCR invoice is always addressed by its upload id. That is not true: {@code
 * search_supplier_invoices} and {@code get_supplier_invoice} return {@code Invoice#id} for every
 * match regardless of where the invoice came from, and {@code AiWorkflowController} already treats
 * the two reference forms as interchangeable. So a perfectly ordinary sequence of calls reaches an
 * OCR invoice through the generic path, and if the generic path could not see OCR blockers it would
 * cheerfully report an invoice as ready when its own module knows it is not.
 *
 * <p>Hence this: the shared readiness check asks whoever has something to add. An implementation
 * that does not recognise the invoice — because it is not OCR-created, or not this module's concern
 * — returns nothing, and costs one method call.
 *
 * <h2>Contract</h2>
 *
 * <ul>
 *   <li>Never throw. A contributor that fails is logged and skipped; readiness must still answer.
 *   <li>Never write anything. This is asked on read paths, inside and outside transactions.
 *   <li>Return quickly for invoices that are not yours — check the cheap discriminator first.
 * </ul>
 */
public interface AiInvoiceReadinessContributor {

  /**
   * Extra blockers this contributor knows about, in the caller's language, or empty when it has
   * nothing to add for this invoice.
   *
   * @param task the {@code AiTask} name the question is being asked for, or null
   */
  default List<String> extraBlockers(Invoice invoice, String task) {
    return Collections.emptyList();
  }

  /**
   * Extra state to fold into this invoice's write fingerprint, or null when there is none.
   *
   * <h2>What belongs here</h2>
   *
   * Anything outside the {@code Invoice} row and its lines that a write through this module's tools
   * can change, and whose change means "the write landed". An OCR upload's own processing status is
   * the example: it lives on another table, so the generic fingerprint cannot see it, and a
   * reconciliation that could not see it would conclude nothing had happened.
   *
   * <p>The value is opaque and only ever compared for equality. Stability matters more than
   * readability: the same invoice in the same state must produce the same string every time.
   */
  default String extraFingerprint(Invoice invoice) {
    return null;
  }
}

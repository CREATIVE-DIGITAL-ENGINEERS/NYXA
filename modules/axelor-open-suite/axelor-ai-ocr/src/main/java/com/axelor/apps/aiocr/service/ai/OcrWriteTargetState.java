package com.axelor.apps.aiocr.service.ai;

import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.tool.AiWriteTargetState;
import com.axelor.db.JPA;
import com.google.inject.Singleton;
import java.util.List;
import javax.persistence.Query;

/**
 * Fingerprints an OCR record for {@link AiWriteTargetState}, so a write that failed is retried
 * exactly when something relevant has changed — and so a write whose answer was lost can be proven
 * not to have landed.
 *
 * <h2>Which references it answers, and which it gave up</h2>
 *
 * {@code Upload#946} and {@code AiOcrUpload#946} — an upload, including one that has not produced
 * an invoice yet, which is the case nothing else can describe.
 *
 * <p>{@code Invoice#606} is no longer answered here. {@code InvoiceRecordResolver} in {@code
 * axelor-ai-accounting} owns it, for the same reason it owns the canonical form: this class cannot
 * tell a supplier invoice from a customer one, and a fingerprint provider that answered for both
 * would be picked or not picked by classpath order.
 *
 * <p>Nothing is lost by that, because of how the reconciliation asks. It canonicalises the target
 * first, and {@code OcrTargetIdentity} canonicalises an upload that produced an invoice to {@code
 * Invoice#606} — so an OCR write is fingerprinted by the invoice resolver, with the OCR-specific
 * signals folded in through {@link OcrInvoiceReadinessContributor}. An upload with no invoice yet
 * canonicalises to itself and is answered here, which is why this class still exists.
 *
 * <h2>Company scoping is part of the answer</h2>
 *
 * A record of another company fingerprints as null, exactly as though it did not exist. The value
 * is used to decide whether a write landed, and reading a record the caller cannot see would let
 * one company's row settle a question about another company's write. Null means "cannot say", which
 * every caller already treats as unproven rather than as permission.
 *
 * <h2>What is included, and why</h2>
 *
 * <ul>
 *   <li><b>the upload</b> — its version, last update and processing stamp;
 *   <li><b>the invoice</b> — its version, last update, lifecycle status and review status. This is
 *       what nearly every write targets, and Hibernate's {@code version} moves on any save, from
 *       the chat or from the form, so a manual UI correction shows up here without anyone reporting
 *       it;
 *   <li><b>the payment condition</b>, by version. It is not part of the invoice, but ventilation
 *       refuses on it, and a refusal has to be released by whatever fixes it. Without this, an
 *       accountant repairing the payment condition — the case that produced "the payment condition
 *       30 days net is not correctly configured" — would leave the invoice untouched, the
 *       fingerprint identical, and a now-valid retry still refused.
 * </ul>
 *
 * <p>The rule this follows: fingerprint what the refusals actually depend on, not just the record
 * the tool names. Anything left out silently turns "retry when state changes" back into "never
 * retry".
 *
 * <h2>Shape</h2>
 *
 * <p>One projection query on ids, no entities loaded and no lazy graph walked, because this runs on
 * every write attempt. The value is opaque: it is compared for equality, never parsed, never logged
 * and never shown to the model, so version counters and timestamps carry nothing confidential.
 * Anything unreadable degrades to null — "unknown" — which the guard handles rather than treating
 * as a constant.
 */
@Singleton
public class OcrWriteTargetState implements AiWriteTargetState {

  @Override
  public String fingerprint(String targetKey) {
    if (targetKey == null) {
      return null;
    }
    int hash = targetKey.lastIndexOf('#');
    if (hash <= 0) {
      return null;
    }
    Long id = idOf(targetKey.substring(hash + 1));
    if (id == null) {
      return null;
    }
    switch (targetKey.substring(0, hash)) {
      case "Upload":
      case "AiOcrUpload":
        return byUpload("u.id = :id", id);
      default:
        // Not this module's kind of record — Invoice# included. Null lets the registry ask the
        // others, which is how the invoice resolver gets to answer for it.
        return null;
    }
  }

  /** The projection, by upload id. */
  protected String byUpload(String predicate, long id) {
    Query query =
        JPA.em()
            .createQuery(
                "select u.id, u.version, u.updatedOn, u.processedOn, u.statusSelect,"
                    + " i.id, i.version, i.updatedOn, i.statusSelect, i.ocrReviewStatus,"
                    + " pc.id, pc.version, c.id"
                    + " from AiOcrUpload u"
                    + " left join u.invoice i"
                    + " left join i.paymentCondition pc"
                    + " left join u.company c"
                    + " where "
                    + predicate);
    query.setParameter("id", id);
    List<?> rows = query.getResultList();
    if (rows.isEmpty()) {
      // No such record: it cannot be described, so say unknown rather than invent a stable value
      // that would freeze the guard on a row nobody can read.
      return null;
    }
    Object[] row = (Object[]) rows.get(0);
    Long companyId =
        row[row.length - 1] == null ? null : ((Number) row[row.length - 1]).longValue();
    if (companyId == null || !AiCompanyScope.ownsId(companyId)) {
      // Another company's document, or one with no company at all. Neither can settle anything
      // about a write made in the active company's books.
      return null;
    }
    // Keyed on the upload id, never on the reference the caller used, so {@code Upload#946} and
    // {@code Invoice#606} produce the same string for the same document.
    StringBuilder sb = new StringBuilder("AiOcrUpload#").append(row[0]);
    for (int i = 1; i < row.length - 1; i++) {
      sb.append('|').append(row[i] == null ? "-" : row[i]);
    }
    return sb.toString();
  }

  private static Long idOf(String text) {
    try {
      return Long.parseLong(text.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}

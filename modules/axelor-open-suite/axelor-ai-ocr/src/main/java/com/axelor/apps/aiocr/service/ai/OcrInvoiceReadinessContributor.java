package com.axelor.apps.aiocr.service.ai;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceReadinessContributor;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.service.readiness.OcrInvoiceReadiness;
import com.axelor.apps.aiocr.service.readiness.OcrSupplierInvoiceReadinessService;
import com.axelor.apps.aiocr.service.record.OcrInvoiceLink;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Makes the OCR-specific blockers and state visible to the generic invoice services.
 *
 * <h2>The gap this closes</h2>
 *
 * An OCR-created supplier invoice has things wrong with it that no generic check knows about: the
 * extracted amounts may no longer reconcile against the scanned document, and its review may be
 * incomplete. Those live here, in the module that did the extraction.
 *
 * <p>It would have been easy to leave them here and reason that an OCR invoice is always addressed
 * by its upload id — the OCR tools all take {@code uploadId}, after all. That reasoning is wrong.
 * {@code search_supplier_invoices} and {@code get_supplier_invoice} return {@code Invoice#id} for
 * every match regardless of where the invoice came from, {@code AiWriteEffects} records writes
 * against the invoice, and {@code AiWorkflowController} already treats the two reference forms as
 * interchangeable. So an OCR invoice is reached through the generic path by perfectly ordinary use,
 * and a generic path that could not see these blockers would report it as ready to post when this
 * module knows it is not.
 *
 * <p>Hence the contribution. The direction of the dependency is what matters: {@code
 * axelor-ai-accounting} defines the interface and knows nothing about OCR; this module implements
 * it and already depends on that one. No cycle, and no invoice logic duplicated on either side.
 *
 * <h2>Nothing for an invoice that is not ours</h2>
 *
 * The first thing both methods do is look for an upload. An invoice somebody typed in by hand, or a
 * customer invoice, has none — so this returns nothing and costs one indexed query.
 */
@Singleton
public class OcrInvoiceReadinessContributor implements AiInvoiceReadinessContributor {

  @Override
  public List<String> extraBlockers(Invoice invoice, String task) {
    AiOcrUpload upload = uploadOf(invoice);
    if (upload == null) {
      return Collections.emptyList();
    }
    List<String> blockers = new ArrayList<>();
    try {
      // The module's own readiness rules — the amount reconciliation against what was scanned, the
      // VAT rounding diagnosis, the review status — asked as they always were. Only the blockers
      // are taken: the generic layer has already said everything about accounts and configuration.
      OcrInvoiceReadiness readiness =
          Beans.get(OcrSupplierInvoiceReadinessService.class).check(invoice, null);
      for (String blocker : readiness.getValidateBlockers()) {
        if (isOcrSpecific(blocker)) {
          blockers.add(blocker);
        }
      }
      for (String blocker : readiness.getVentilateBlockers()) {
        if (isOcrSpecific(blocker) && !blockers.contains(blocker)) {
          blockers.add(blocker);
        }
      }
    } catch (Exception e) {
      // Never throws: readiness has to answer, and a contributor that cannot is not a blocker.
      return Collections.emptyList();
    }
    return blockers;
  }

  /**
   * The upload's own state, folded into the invoice's write fingerprint.
   *
   * <h2>Why this is not optional</h2>
   *
   * The generic fingerprint reads the {@code Invoice} row and its lines. The upload is a different
   * table: its processing status and its own version move when intake syncs the review, and an
   * invoice whose upload changed while the invoice itself did not would otherwise fingerprint as
   * unchanged. That is precisely the reading that lets a lost write be classified NOT_WRITTEN and
   * retried, so the signals this class used to fingerprint on its own are contributed here instead
   * of being dropped.
   */
  @Override
  public String extraFingerprint(Invoice invoice) {
    AiOcrUpload upload = uploadOf(invoice);
    if (upload == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder("AiOcrUpload#").append(upload.getId());
    append(sb, upload.getVersion());
    append(sb, upload.getUpdatedOn());
    append(sb, upload.getProcessedOn());
    append(sb, upload.getStatusSelect());
    // Derived from the invoice's own fields, but recomputed and saved by intake rather than by the
    // edit path, so it can move without the invoice's version doing so.
    append(sb, invoice.getOcrReviewStatus());
    return sb.toString();
  }

  /**
   * The upload that speaks for this invoice now, in the active company, or null.
   *
   * <p>Company-scoped like every other read here: an upload from another company's books cannot
   * describe or block anything in these.
   *
   * <p>An invoice can have several uploads — the same document scanned twice, a re-scan after a
   * failed extraction — so the choice is made deterministically by {@link OcrInvoiceLink}: the
   * newest one. A superseded upload is history and does not get a vote, which is what keeps a stale
   * failed scan from blocking an invoice that has since been re-scanned.
   */
  protected AiOcrUpload uploadOf(Invoice invoice) {
    if (invoice == null || invoice.getId() == null) {
      return null;
    }
    // The cheap discriminator first: an invoice nobody generated has no upload, and most invoices
    // in a company that also sells are not OCR ones.
    if (!Boolean.TRUE.equals(invoice.getAiGenerated())) {
      return null;
    }
    return Beans.get(OcrInvoiceLink.class).currentUpload(invoice.getId());
  }

  /**
   * Whether a blocker is one only this module could have produced.
   *
   * <h2>Why the list is filtered at all</h2>
   *
   * {@code OcrSupplierInvoiceReadinessService} answers the whole question — accounts, journals,
   * sequences, payment conditions AND the OCR reconciliation — because it predates the generic
   * service and had to. Contributing all of it would say everything twice: the caller would see
   * "the invoice has no payment condition" from the generic rules and again from here.
   *
   * <p>So only the ones the generic layer cannot know about are passed on, matched on the
   * vocabulary those rules use. A blocker this filter does not recognise is dropped rather than
   * duplicated, which is the safe direction: the generic layer has already reported every
   * configuration and field problem, and the reconciliation wording is what is unique here.
   */
  protected boolean isOcrSpecific(String blocker) {
    if (blocker == null) {
      return false;
    }
    String text = blocker.toLowerCase(java.util.Locale.ROOT);
    return text.contains("reconcil")
        || text.contains("ocr")
        || text.contains("scanned")
        || text.contains("extracted")
        || text.contains("review")
        || text.contains("rounding")
        || text.contains("vat summary")
        || text.contains("tax summary");
  }

  private static void append(StringBuilder sb, Object value) {
    sb.append('|').append(value == null ? "-" : value);
  }
}

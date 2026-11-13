package com.axelor.apps.aiocr.service.record;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceTerm;
import com.axelor.apps.account.db.PaymentCondition;
import com.axelor.apps.account.service.PaymentConditionService;
import com.axelor.apps.account.service.invoice.InvoiceTermService;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.apps.aiocr.service.readiness.OcrAccountingTotals;
import com.axelor.apps.aiocr.service.readiness.OcrInvoiceReadiness;
import com.axelor.apps.aiocr.service.readiness.OcrSupplierInvoiceReadinessService;
import com.axelor.apps.base.AxelorException;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates the payment terms a validated supplier invoice should already have.
 *
 * <h2>The situation this repairs</h2>
 *
 * {@code InvoiceTermServiceImpl.computeInvoiceTerms} returns immediately, without an error, when
 * the payment condition carries no lines. An invoice validated in that state is stored with
 * <b>zero</b> invoice terms and nothing says so. It then cannot be posted: {@code
 * MoveLineCreateServiceImpl.addInvoiceTermMoveLines} builds the supplier credit line by iterating
 * {@code invoiceTermList}, so an empty list produces a Move of debits and no credit at all, which
 * Axelor rejects through {@code checkSpecialAccountAmount} — in the language of special and
 * commitment accounts, naming none of this.
 *
 * <p>Repairing the payment condition afterwards does not backfill the invoices already validated
 * against it, and validation cannot be run twice. That left an accountant with a validated,
 * un-postable document and no supported route forward that a chat could take.
 *
 * <h2>Why this is not a workaround</h2>
 *
 * It runs precisely what the form runs. The {@code paymentCondition} field on {@code invoice-form}
 * is editable while {@code statusSelect != 3}, and its {@code onChange} is {@code
 * action-invoice-group-payment-condition-onchange} → {@code InvoiceController.computeInvoiceTerms}
 * → {@link InvoiceTermService#checkAndComputeInvoiceTerms}, then {@code
 * computeInvoiceTermsDueDates}. {@code checkAndComputeInvoiceTerms} early-returns only for
 * VENTILATED, so a VALIDATED invoice is a supported target — this is Axelor's own lifecycle,
 * reached by the same two calls, on a document in the state Axelor allows.
 *
 * <p>No {@link InvoiceTerm} is constructed here. Nothing is dated, amounted or apportioned here. If
 * the native services produce nothing usable, this refuses and writes nothing.
 *
 * <h2>Amounts</h2>
 *
 * The terms come out of {@code invoice.getInTaxTotal()} — {@code computeInvoiceTerm} is {@code
 * inTaxTotal × percentage / 100} and {@code computeInvoiceTerms} gives the last term {@code
 * inTaxTotal} minus the rest, so the set totals the invoice gross by construction. That is verified
 * below against {@link OcrAccountingTotals}, never recomputed from the lines: the invoice's own
 * total is the authoritative one.
 */
@Singleton
public class SupplierInvoiceTermsRefreshService {

  private static final Logger LOG =
      LoggerFactory.getLogger(SupplierInvoiceTermsRefreshService.class);

  /** The terms must total the invoice gross to the last decimal Axelor stores. */
  private static final BigDecimal TERM_TOLERANCE = new BigDecimal("0.01");

  private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  /**
   * Regenerates the invoice terms of the validated invoice behind an OCR record.
   *
   * @throws IllegalArgumentException / IllegalStateException on any refusal — both roll the
   *     transaction back, so the invoice is left exactly as it was
   */
  @Transactional(rollbackOn = Exception.class)
  public Map<String, Object> refresh(long uploadId) {
    // Company ownership, aiGenerated, VALIDATED (so DRAFT and VENTILATED are both refused, each
    // with its own message), and Axelor's own write permission on the row — all from the one guard
    // every operation in this package shares.
    OcrDraftInvoiceGuard.Target target = OcrDraftInvoiceGuard.resolveValidated(uploadId);
    AiOcrUpload upload = target.getUpload();
    Invoice invoice = target.getInvoice();

    requireSupplierInvoice(invoice);
    PaymentCondition condition = requireUsablePaymentCondition(invoice);
    // Captured before the call so "unchanged" is asserted rather than assumed.
    Long conditionIdBefore = condition.getId();
    int termsBefore =
        invoice.getInvoiceTermList() == null ? 0 : invoice.getInvoiceTermList().size();

    InvoiceTermService invoiceTermService = Beans.get(InvoiceTermService.class);
    try {
      // The form's two calls, in the form's order. Nothing else.
      invoiceTermService.checkAndComputeInvoiceTerms(invoice);
      invoiceTermService.computeInvoiceTermsDueDates(invoice);
    } catch (Exception e) {
      throw new IllegalStateException(
          "Axelor could not generate the payment terms of invoice "
              + invoice.getId()
              + ": "
              + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().trim())
              + " Nothing was changed.",
          e);
    }

    JPA.save(invoice);
    JPA.flush();

    OcrAccountingTotals totals = verify(invoice, conditionIdBefore);

    note(invoice, termsBefore);
    JPA.save(invoice);
    JPA.flush();

    AiWriteEffects.touched(Invoice.class.getName(), invoice.getId());
    AiWriteEffects.touched(AiOcrUpload.class.getName(), upload.getId());

    LOG.info(
        "OCR record {}: invoice {} payment terms regenerated ({} -> {}) by {}.",
        uploadId,
        invoice.getId(),
        termsBefore,
        invoice.getInvoiceTermList().size(),
        AiExecutionContext.callerDescription());

    return result(upload, invoice, totals);
  }

  // ---------------------------------------------------------------------------
  // Guards
  // ---------------------------------------------------------------------------

  /** Supplier documents only — the operation type decides the payment direction of every term. */
  protected void requireSupplierInvoice(Invoice invoice) {
    Integer type = invoice.getOperationTypeSelect();
    if (type == null
        || (type
                != com.axelor.apps.account.db.repo.InvoiceRepository
                    .OPERATION_TYPE_SUPPLIER_PURCHASE
            && type
                != com.axelor.apps.account.db.repo.InvoiceRepository
                    .OPERATION_TYPE_SUPPLIER_REFUND)) {
      throw new IllegalArgumentException(
          "Invoice "
              + invoice.getId()
              + " is not a supplier invoice; refusing to touch its terms.");
    }
  }

  /**
   * A payment condition that Axelor will accept.
   *
   * <p>Checked with Axelor's own service rather than by counting lines here: the same rule decides
   * ventilation, and a second implementation of it would eventually disagree. Without this the
   * refresh would "succeed" by generating nothing at all — {@code computeInvoiceTerms} returns
   * silently on a condition with no lines, which is exactly the defect being repaired.
   */
  protected PaymentCondition requireUsablePaymentCondition(Invoice invoice) {
    PaymentCondition condition = invoice.getPaymentCondition();
    if (condition == null) {
      throw new IllegalArgumentException(
          "Invoice "
              + invoice.getId()
              + " has no payment condition, so there is nothing to generate terms from. Set one on"
              + " the invoice first; nothing was changed.");
    }
    try {
      Beans.get(PaymentConditionService.class).checkPaymentCondition(condition);
    } catch (AxelorException e) {
      throw new IllegalArgumentException(
          "Payment condition "
              + condition.getName()
              + " is still not correctly configured, so regenerating the terms would produce none"
              + " again: "
              + (e.getMessage() == null ? "" : e.getMessage().trim())
              + " It needs at least one line whose payment percentages total 100 (Accounting →"
              + " Configuration → Financial → Payment conditions). Nothing was changed.");
    }
    return condition;
  }

  // ---------------------------------------------------------------------------
  // Verification — throws, which rolls the whole refresh back
  // ---------------------------------------------------------------------------

  /**
   * What actually came out, checked against what had to.
   *
   * <p>Every failure here throws, and the method is inside the transaction, so a refresh that did
   * not produce a usable set of terms leaves the invoice exactly as it was rather than half-fixed.
   */
  protected OcrAccountingTotals verify(Invoice invoice, Long conditionIdBefore) {
    OcrAccountingTotals totals = OcrAccountingTotals.of(invoice);
    BigDecimal gross = totals.getInTaxTotal();
    List<InvoiceTerm> terms =
        invoice.getInvoiceTermList() == null ? new ArrayList<>() : invoice.getInvoiceTermList();

    if (invoice.getPaymentCondition() == null
        || !invoice.getPaymentCondition().getId().equals(conditionIdBefore)) {
      throw new IllegalStateException(
          "Regenerating the terms of invoice "
              + invoice.getId()
              + " changed its payment condition, which it must not. Nothing was changed.");
    }
    if (terms.isEmpty()) {
      if (gross.signum() == 0) {
        return totals; // nothing to settle; Axelor would create no move either
      }
      throw new IllegalStateException(
          "Axelor generated no payment terms for invoice "
              + invoice.getId()
              + " even though its payment condition is valid. Nothing was changed.");
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (InvoiceTerm term : terms) {
      if (term.getDueDate() == null) {
        throw new IllegalStateException(
            "A regenerated payment term of invoice "
                + invoice.getId()
                + " has no due date. Nothing was changed.");
      }
      sum = sum.add(term.getAmount() == null ? BigDecimal.ZERO : term.getAmount());
    }
    // Against the invoice's own gross — the authoritative figure — never against a total re-added
    // from the lines.
    if (sum.subtract(gross).abs().compareTo(TERM_TOLERANCE) > 0) {
      throw new IllegalStateException(
          "The regenerated payment terms of invoice "
              + invoice.getId()
              + " total "
              + sum
              + " but the invoice gross is "
              + gross
              + ". Nothing was changed.");
    }
    return totals;
  }

  // ---------------------------------------------------------------------------
  // Audit + result
  // ---------------------------------------------------------------------------

  protected void note(Invoice invoice, int termsBefore) {
    StringBuilder sb = new StringBuilder();
    String existing = invoice.getAiValidationNotes();
    if (existing != null && !existing.trim().isEmpty()) {
      sb.append(existing.trim()).append("\n\n");
    }
    sb.append("[")
        .append(LocalDateTime.now().format(STAMP))
        .append("] Payment terms regenerated by ")
        .append(AiExecutionContext.callerDescription())
        .append(" through InvoiceTermService.checkAndComputeInvoiceTerms: ")
        .append(termsBefore)
        .append(" -> ")
        .append(invoice.getInvoiceTermList().size())
        .append(" term(s) totalling ")
        .append(invoice.getInTaxTotal())
        .append(" from payment condition ")
        .append(invoice.getPaymentCondition().getName())
        .append(". Amounts and status unchanged.");
    invoice.setAiValidationNotes(sb.toString());
  }

  protected Map<String, Object> result(
      AiOcrUpload upload, Invoice invoice, OcrAccountingTotals totals) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("status", "ok");
    out.put("uploadId", upload.getId());
    out.put("invoiceId", invoice.getId());
    out.put("paymentCondition", invoice.getPaymentCondition().getName());

    List<InvoiceTerm> terms =
        invoice.getInvoiceTermList() == null ? new ArrayList<>() : invoice.getInvoiceTermList();
    out.put("termCount", terms.size());
    List<Map<String, Object>> rows = new ArrayList<>();
    BigDecimal sum = BigDecimal.ZERO;
    for (InvoiceTerm term : terms) {
      Map<String, Object> r = new LinkedHashMap<>();
      r.put("dueDate", term.getDueDate() == null ? null : term.getDueDate().toString());
      r.put("amount", term.getAmount());
      r.put("percentage", term.getPercentage());
      rows.add(r);
      sum = sum.add(term.getAmount() == null ? BigDecimal.ZERO : term.getAmount());
    }
    out.put("terms", rows);
    out.put("totalTermsAmount", sum);
    out.put("invoiceGross", totals.getInTaxTotal());
    // The authoritative block travels with the result, so the answer to "what will be settled" is
    // in the same payload as the terms and cannot be assembled from anything else.
    out.put("accountingTotals", totals.toMap());

    OcrInvoiceReadiness readiness =
        Beans.get(OcrSupplierInvoiceReadinessService.class).check(invoice, new ArrayList<>());
    List<String> blockers = new ArrayList<>(readiness.getVentilateBlockers());
    List<String> missing = BgOcrIntakeService.missingReviewFields(invoice);
    out.put("readyToVentilate", blockers.isEmpty() && readiness.getValidateBlockers().isEmpty());
    out.put("remainingBlockers", blockers);
    out.put("missingReviewFields", missing);
    out.put("refresh", refreshTarget(invoice));
    return out;
  }

  /** What the open form should reload, in the shape the other write tools already return. */
  protected Map<String, Object> refreshTarget(Invoice invoice) {
    Map<String, Object> refresh = new LinkedHashMap<>();
    refresh.put("model", Invoice.class.getName());
    refresh.put("id", invoice.getId());
    return refresh;
  }
}

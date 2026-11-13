package com.axelor.apps.aiaccounting.service.invoice;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.PaymentCondition;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.service.PaymentConditionService;
import com.axelor.apps.account.service.invoice.InvoiceService;
import com.axelor.apps.account.service.invoice.InvoiceTermService;
import com.axelor.apps.aiaccountant.security.AiDraftOnlyRefusal;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves an invoice along its own lifecycle, through Axelor's own transitions.
 *
 * <h2>Nothing here sets a status</h2>
 *
 * {@code InvoiceService.validate} and {@code InvoiceService.ventilate} are the same calls the
 * Validate and Ventilate buttons make. They resolve the journal, build the invoice terms, compute
 * the totals, assign the number from the company's sequence and create the accounting move — a long
 * chain this package has no business reimplementing, and could not reimplement correctly. Writing
 * {@code statusSelect} directly would produce a document that looks validated and had none of that
 * done to it.
 *
 * <h2>Readiness is checked first, and reported rather than discovered</h2>
 *
 * Axelor refuses an unready invoice too, but its message is about the first thing it happened to
 * check. {@link InvoiceReadinessService} names all of them at once, so a caller can fix them in one
 * pass instead of discovering them one failed transition at a time.
 *
 * <h2>Both sides, one implementation</h2>
 *
 * The transitions are identical for a supplier and a customer invoice; only the journal, the
 * sequence and the account differ, and Axelor selects those from {@code operationTypeSelect}
 * itself. So the side is used for one thing here — refusing a record the calling tool has no
 * business touching — and never to choose a code path.
 */
@Singleton
public class InvoiceLifecycleService {

  private static final Logger LOG = LoggerFactory.getLogger(InvoiceLifecycleService.class);

  /** DRAFT → VALIDATED, through {@code InvoiceService.validate}. */
  @Transactional(rollbackOn = Exception.class)
  public Invoice validate(long invoiceId, InvoiceSide side) {
    InvoiceRecordGuard.Target target =
        Beans.get(InvoiceRecordGuard.class).resolveDraft(invoiceId, side);
    Invoice invoice = target.getInvoice();

    InvoiceReadiness readiness =
        Beans.get(InvoiceReadinessService.class).check(invoice, "VALIDATE");
    if (!readiness.isValidateReady()) {
      throw new InvoiceBlocked(
          AiBlockerCode.NOT_READY_TO_VALIDATE,
          "Invoice "
              + invoiceId
              + " cannot be validated yet: "
              + String.join(" ", readiness.getValidateBlockers()));
    }

    try {
      Beans.get(InvoiceService.class).validate(invoice);
    } catch (Exception e) {
      throw new InvoiceBlocked(
          AiBlockerCode.AXELOR_REFUSED_VALIDATION,
          "Axelor refused to validate invoice " + invoiceId + ": " + message(e),
          e);
    }
    JPA.flush();
    requireStatus(invoice, InvoiceRepository.STATUS_VALIDATED, "validation");
    AiWriteEffects.touched(Invoice.class.getName(), invoice.getId());
    LOG.info("AI Accountant: invoice {} validated.", invoiceId);
    return invoice;
  }

  /** VALIDATED → VENTILATED, through {@code InvoiceService.ventilate}. Cannot be undone. */
  @Transactional(rollbackOn = Exception.class)
  public Invoice ventilate(long invoiceId, InvoiceSide side) {
    InvoiceRecordGuard.Target target =
        Beans.get(InvoiceRecordGuard.class).resolveValidated(invoiceId, side);
    Invoice invoice = target.getInvoice();

    InvoiceReadiness readiness = Beans.get(InvoiceReadinessService.class).check(invoice, "POST");
    if (!readiness.isVentilateReady()) {
      throw new InvoiceBlocked(
          AiBlockerCode.NOT_READY_TO_POST,
          "Invoice "
              + invoiceId
              + " cannot be posted yet: "
              + String.join(" ", readiness.getVentilateBlockers()));
    }

    try {
      Beans.get(InvoiceService.class).ventilate(invoice);
    } catch (Exception e) {
      throw new InvoiceBlocked(
          AiBlockerCode.AXELOR_REFUSED_POSTING,
          AiDraftOnlyRefusal.find(e) != null
              ? AiDraftOnlyRefusal.MESSAGE
              : "Axelor refused to post invoice " + invoiceId + ": " + message(e),
          e);
    }
    JPA.flush();
    requireStatus(invoice, InvoiceRepository.STATUS_VENTILATED, "posting");
    AiWriteEffects.touched(Invoice.class.getName(), invoice.getId());
    LOG.info(
        "AI Accountant: invoice {} posted as {} (move {}).",
        invoiceId,
        invoice.getInvoiceId(),
        invoice.getMove() == null ? "none" : invoice.getMove().getId());
    return invoice;
  }

  /**
   * Rebuilds the payment terms and their due dates, the way re-picking the payment condition on the
   * form does.
   *
   * <p>The same two calls in the same order, so nothing about the amounts, the dates or the status
   * changes as a side effect. For a validated invoice whose payment condition or dates were
   * corrected and whose terms are now stale.
   */
  @Transactional(rollbackOn = Exception.class)
  public Invoice refreshTerms(long invoiceId, InvoiceSide side) {
    InvoiceRecordGuard.Target target =
        Beans.get(InvoiceRecordGuard.class).resolveValidated(invoiceId, side);
    Invoice invoice = target.getInvoice();

    PaymentCondition condition = invoice.getPaymentCondition();
    if (condition == null) {
      throw new InvoiceBlocked(
          AiBlockerCode.MISSING_INVOICE_TERMS,
          "Invoice " + invoiceId + " has no payment condition, so there are no terms to rebuild.");
    }
    try {
      Beans.get(PaymentConditionService.class).checkPaymentCondition(condition);
    } catch (Exception e) {
      throw new InvoiceBlocked(
          AiBlockerCode.INVOICE_TERMS_NOT_CREATABLE,
          "The payment condition on invoice " + invoiceId + " cannot be used: " + message(e),
          e);
    }

    InvoiceTermService terms = Beans.get(InvoiceTermService.class);
    try {
      terms.checkAndComputeInvoiceTerms(invoice);
      terms.computeInvoiceTermsDueDates(invoice);
    } catch (Exception e) {
      throw new InvoiceBlocked(
          AiBlockerCode.INVOICE_TERMS_NOT_CREATABLE,
          "Axelor could not rebuild the terms of invoice " + invoiceId + ": " + message(e),
          e);
    }
    JPA.save(invoice);
    JPA.flush();
    AiWriteEffects.touched(Invoice.class.getName(), invoice.getId());
    LOG.info(
        "AI Accountant: invoice {} terms refreshed ({} term(s)).",
        invoiceId,
        invoice.getInvoiceTermList() == null ? 0 : invoice.getInvoiceTermList().size());
    return invoice;
  }

  /** The status this invoice is in now, or null when it cannot be read at all. */
  public Integer scopedStatus(long invoiceId, InvoiceSide side) {
    InvoiceRecordGuard.Target target =
        Beans.get(InvoiceRecordGuard.class).findAnyStatus(invoiceId, side);
    return target == null ? null : target.getInvoice().getStatusSelect();
  }

  /**
   * The reply a transition would have produced, rebuilt by re-reading the record.
   *
   * <p>For the confirmation path: when a call's answer was lost and the record proves the write
   * landed, the caller still owes the user the ordinary success reply rather than a note about
   * plumbing. Read-only, and null when the record cannot be read at all.
   */
  public java.util.Map<String, Object> confirmedResult(long invoiceId, InvoiceSide side) {
    InvoiceRecordGuard.Target target =
        Beans.get(InvoiceRecordGuard.class).findAnyStatus(invoiceId, side);
    if (target == null) {
      return null;
    }
    return Beans.get(InvoiceViewService.class)
        .writeSummary(target.getInvoice(), java.util.Collections.singletonList("confirmed"));
  }

  /**
   * Refuses when the transition reported success without moving the status.
   *
   * <p>Axelor's own transitions are reliable; this catches the case that is not a transition
   * failure at all — a service replaced, a hook that swallowed something — where reporting success
   * would tell the user an invoice is posted when it is not.
   */
  private static void requireStatus(Invoice invoice, int expected, String what) {
    if (invoice.getStatusSelect() == null || invoice.getStatusSelect() != expected) {
      throw new InvoiceBlocked(
          AiBlockerCode.TOOL_FAILED,
          "Invoice "
              + invoice.getId()
              + " reported no error from "
              + what
              + " but its status is "
              + invoice.getStatusSelect()
              + " rather than "
              + expected
              + ". Nothing is claimed about it; check the record.");
    }
  }

  private static String message(Exception e) {
    String text = e.getMessage();
    if (text == null || text.trim().isEmpty()) {
      return e.getClass().getSimpleName();
    }
    // Axelor's messages arrive with markup from the UI layer often enough to be worth stripping.
    return text.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
  }
}

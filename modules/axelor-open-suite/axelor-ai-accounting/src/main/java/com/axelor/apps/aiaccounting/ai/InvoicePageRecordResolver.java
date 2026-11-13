package com.axelor.apps.aiaccounting.ai;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiaccountant.context.AiPageRecordResolver;
import com.axelor.apps.aiaccountant.service.AiWorkflowIntent;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceReadiness;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceReadinessService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceSide;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.util.List;

/**
 * Tells the model what the invoice on screen actually is.
 *
 * <h2>Which of the three methods this owns, and which it shares</h2>
 *
 * {@code describe} is explicitly a multi-contributor method — an invoice can legitimately be both
 * an accounting document and an OCR record, and both facts are worth saying — so this class
 * contributes the accounting half and the OCR module keeps contributing its own line about the
 * upload it came from.
 *
 * <p>{@code state} and {@code lifecycle} are first-answer-wins, so they need exactly one owner, and
 * this is it. They used to be answered by the OCR resolver for every invoice, which was right while
 * every invoice was a supplier invoice and became a silent mislabel the moment one was not.
 */
@Singleton
public class InvoicePageRecordResolver implements AiPageRecordResolver {

  @Override
  public String describe(String model, long recordId) {
    if (!InvoiceRecordResolver.INVOICE_MODEL.equals(model)) {
      return null;
    }
    Invoice invoice = InvoiceRecordResolver.find(recordId);
    if (invoice == null) {
      return null;
    }
    InvoiceSide side =
        InvoiceSide.SUPPLIER.covers(invoice) ? InvoiceSide.SUPPLIER : InvoiceSide.CUSTOMER;
    StringBuilder sb = new StringBuilder();
    sb.append("The open record is ")
        .append(side.label())
        .append(" invoiceId=")
        .append(recordId)
        .append(" (")
        .append(status(invoice))
        .append(")");
    if (invoice.getPartner() != null) {
      sb.append(" for ").append(invoice.getPartner().getFullName());
    }
    if (invoice.getInTaxTotal() != null) {
      sb.append(", total ").append(invoice.getInTaxTotal().toPlainString());
      if (invoice.getCurrency() != null) {
        sb.append(' ').append(invoice.getCurrency().getCode());
      }
    }
    sb.append(". Use invoiceId=")
        .append(recordId)
        .append(" with the ")
        .append(side == InvoiceSide.CUSTOMER ? "customer" : "supplier")
        .append("-invoice tools; the tools for the other direction refuse it.");

    // Why it cannot move yet, said here so a review turn does not have to call a tool to find out.
    InvoiceReadiness readiness = Beans.get(InvoiceReadinessService.class).check(invoice, null);
    List<String> blockers =
        readiness.isValidateReady()
            ? readiness.getVentilateBlockers()
            : readiness.getValidateBlockers();
    if (!blockers.isEmpty()) {
      sb.append(" Outstanding: ").append(String.join(" ", blockers));
    }
    return sb.toString();
  }

  @Override
  public String state(String model, long recordId) {
    if (!InvoiceRecordResolver.INVOICE_MODEL.equals(model)) {
      return null;
    }
    Invoice invoice = InvoiceRecordResolver.find(recordId);
    if (invoice == null) {
      return null;
    }
    return status(invoice);
  }

  @Override
  public String lifecycle(String model, long recordId) {
    if (!InvoiceRecordResolver.INVOICE_MODEL.equals(model)) {
      return null;
    }
    Invoice invoice = InvoiceRecordResolver.find(recordId);
    if (invoice == null || invoice.getStatusSelect() == null) {
      return null;
    }
    switch (invoice.getStatusSelect()) {
      case InvoiceRepository.STATUS_DRAFT:
        return AiWorkflowIntent.DRAFT;
      case InvoiceRepository.STATUS_VALIDATED:
        return AiWorkflowIntent.VALIDATED;
      case InvoiceRepository.STATUS_VENTILATED:
        return AiWorkflowIntent.POSTED;
      case InvoiceRepository.STATUS_CANCELED:
        return AiWorkflowIntent.CANCELLED;
      default:
        return null;
    }
  }

  private static String status(Invoice invoice) {
    Integer status = invoice.getStatusSelect();
    if (status == null) {
      return "status unknown";
    }
    switch (status) {
      case InvoiceRepository.STATUS_DRAFT:
        return "DRAFT, editable";
      case InvoiceRepository.STATUS_VALIDATED:
        return "VALIDATED, not yet posted";
      case InvoiceRepository.STATUS_VENTILATED:
        return "VENTILATED, in the books";
      case InvoiceRepository.STATUS_CANCELED:
        return "CANCELED";
      default:
        return "status " + status;
    }
  }
}

package com.axelor.apps.bglocalization.service.saft.dto;

/**
 * SourceDocumentsMonthly. Редът във файла е продажби, ПЛАЩАНИЯ, покупки — както го иска схемата.
 */
public final class SaftSourceDocuments {

  private SaftInvoiceSection salesInvoices;

  private SaftPaymentSection payments;

  private SaftInvoiceSection purchaseInvoices;

  public SaftInvoiceSection getSalesInvoices() {
    return salesInvoices;
  }

  public SaftSourceDocuments salesInvoices(SaftInvoiceSection value) {
    this.salesInvoices = value;
    return this;
  }

  public SaftPaymentSection getPayments() {
    return payments;
  }

  public SaftSourceDocuments payments(SaftPaymentSection value) {
    this.payments = value;
    return this;
  }

  public SaftInvoiceSection getPurchaseInvoices() {
    return purchaseInvoices;
  }

  public SaftSourceDocuments purchaseInvoices(SaftInvoiceSection value) {
    this.purchaseInvoices = value;
    return this;
  }
}

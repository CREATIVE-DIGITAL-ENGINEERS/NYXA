package com.axelor.apps.aiaccounting.service.invoice;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;

/**
 * Which direction of invoice a tool is allowed to touch.
 *
 * <h2>Why this is a parameter and never an inference</h2>
 *
 * A supplier invoice and a customer invoice are the same {@code Invoice} entity, separated only by
 * {@code operationTypeSelect}: 1 supplier purchase, 2 supplier credit note, 3 customer sale, 4
 * customer credit note. Every shared service below is therefore given the side its caller is
 * allowed to work on, and refuses a record of the other one. Deriving the side from the record
 * instead would make {@code edit_customer_invoice} silently able to edit a supplier invoice the
 * moment somebody passed it the wrong id — which is the whole reason the two tool families exist
 * separately.
 *
 * <p>Nothing here can move an invoice between sides. There is no setter for it anywhere in this
 * package, no writable field for it in any schema, and the standard direction is fixed by which
 * creation tool was called.
 */
public enum InvoiceSide {
  /** Documents that arrived from somebody the company buys from. */
  SUPPLIER(
      InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE,
      InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND,
      "supplier invoice"),

  /** Documents this company issues to somebody it sells to. */
  CUSTOMER(
      InvoiceRepository.OPERATION_TYPE_CLIENT_SALE,
      InvoiceRepository.OPERATION_TYPE_CLIENT_REFUND,
      "customer invoice");

  private final int standardType;
  private final int refundType;
  private final String label;

  InvoiceSide(int standardType, int refundType, String label) {
    this.standardType = standardType;
    this.refundType = refundType;
    this.label = label;
  }

  /**
   * The operation type a plain invoice of this side is created with.
   *
   * <p>Read by the creation service and by nothing else. Credit notes are not created by any tool
   * here; {@link #refundType()} exists so a search can still find the ones Axelor itself raised.
   */
  public int standardType() {
    return standardType;
  }

  /** The operation type a credit note of this side carries. */
  public int refundType() {
    return refundType;
  }

  /** How to name this kind of document to a person. */
  public String label() {
    return label;
  }

  /** True when the invoice belongs to this side. False for null, and for the other side. */
  public boolean covers(Invoice invoice) {
    if (invoice == null || invoice.getOperationTypeSelect() == null) {
      return false;
    }
    int type = invoice.getOperationTypeSelect();
    return type == standardType || type == refundType;
  }

  /** True for purchases and purchase credit notes — what Axelor's own services call isPurchase. */
  public boolean isPurchase() {
    return this == SUPPLIER;
  }
}

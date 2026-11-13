package com.axelor.apps.aiocr.service;

/**
 * The record type a user can manually reroute ONE {@code AiOcrUpload} (or one split segment) to,
 * when the automatic classification picked the wrong one.
 *
 * <p>This is the single typed vocabulary shared by the UI and the service: the four view buttons
 * pass one of these codes, {@link OcrRerouteService} switches on the enum, and the same values name
 * the CURRENT destination of an upload. Nothing here participates in automatic classification —
 * {@code BgOcrDocumentClassifier} and {@code BgOcrIntakeService#routeExtracted} are untouched; a
 * reroute states the destination explicitly and bypasses the classifier entirely.
 */
public enum OcrRerouteDestination {

  /** A draft accounting {@code Move} (the non-invoice document destination). */
  MOVE("move", "Move"),

  /** A draft supplier (purchase) {@code Invoice} — the ordinary OCR intake destination. */
  SUPPLIER_INVOICE("supplierInvoice", "Supplier Invoice"),

  /** A draft customer (sale) {@code Invoice}. */
  CUSTOMER_INVOICE("customerInvoice", "Customer Invoice"),

  /** A reviewable {@code BankStatement}, created through the bank-statement extension point. */
  BANK_STATEMENT("bankStatement", "Bank Statement");

  private final String code;
  private final String label;

  OcrRerouteDestination(String code, String label) {
    this.code = code;
    this.label = label;
  }

  /** The stable code used by the view buttons and by any stored/audit text. */
  public String getCode() {
    return code;
  }

  /** Short English label used in confirmations, notifications and the audit note. */
  public String getLabel() {
    return label;
  }

  /** The destination for a code, or {@code null} when the code is unknown/blank. */
  public static OcrRerouteDestination fromCode(String code) {
    if (code == null) {
      return null;
    }
    String trimmed = code.trim();
    for (OcrRerouteDestination d : values()) {
      if (d.code.equalsIgnoreCase(trimmed) || d.name().equalsIgnoreCase(trimmed)) {
        return d;
      }
    }
    return null;
  }
}

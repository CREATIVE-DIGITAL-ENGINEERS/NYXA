package com.axelor.apps.aiaccounting.service.invoice.spi;

/** One accepted change, with before and after, so the reply can say what actually moved. */
public final class AiInvoiceFieldChange {

  private final String field;
  private final String oldValue;
  private final String newValue;

  public AiInvoiceFieldChange(String field, String oldValue, String newValue) {
    this.field = field;
    this.oldValue = oldValue;
    this.newValue = newValue;
  }

  public String getField() {
    return field;
  }

  public String getOldValue() {
    return oldValue;
  }

  public String getNewValue() {
    return newValue;
  }

  /** Null-safe equality, for deciding whether a write changed anything at all. */
  public static boolean same(String a, String b) {
    return a == null ? b == null : a.equals(b);
  }
}

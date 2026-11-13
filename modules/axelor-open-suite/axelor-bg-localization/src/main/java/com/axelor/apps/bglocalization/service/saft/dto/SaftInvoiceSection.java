package com.axelor.apps.bglocalization.service.saft.dto;

import java.math.BigDecimal;
import java.util.Collections;

/**
 * SalesInvoices или PurchaseInvoices. Фактурите са {@code Iterable} — виж {@link
 * SaftGeneralLedger}.
 */
public final class SaftInvoiceSection {

  private Long numberOfEntries;

  private BigDecimal totalDebit;

  private BigDecimal totalCredit;

  /** Поне една. */
  private Iterable<SaftInvoice> invoices = Collections.emptyList();

  public Long getNumberOfEntries() {
    return numberOfEntries;
  }

  public SaftInvoiceSection numberOfEntries(Long value) {
    this.numberOfEntries = value;
    return this;
  }

  public BigDecimal getTotalDebit() {
    return totalDebit;
  }

  public SaftInvoiceSection totalDebit(BigDecimal value) {
    this.totalDebit = value;
    return this;
  }

  public BigDecimal getTotalCredit() {
    return totalCredit;
  }

  public SaftInvoiceSection totalCredit(BigDecimal value) {
    this.totalCredit = value;
    return this;
  }

  public Iterable<SaftInvoice> getInvoices() {
    return invoices;
  }

  public SaftInvoiceSection invoices(Iterable<SaftInvoice> value) {
    this.invoices = value;
    return this;
  }
}

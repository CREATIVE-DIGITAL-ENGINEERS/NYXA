package com.axelor.apps.bglocalization.service.saft.dto;

import java.math.BigDecimal;
import java.util.Collections;

/** Payments. Плащанията са {@code Iterable} — виж {@link SaftGeneralLedger}. */
public final class SaftPaymentSection {

  private Long numberOfEntries;

  private BigDecimal totalDebit;

  private BigDecimal totalCredit;

  /** Поне едно. */
  private Iterable<SaftPayment> payments = Collections.emptyList();

  public Long getNumberOfEntries() {
    return numberOfEntries;
  }

  public SaftPaymentSection numberOfEntries(Long value) {
    this.numberOfEntries = value;
    return this;
  }

  public BigDecimal getTotalDebit() {
    return totalDebit;
  }

  public SaftPaymentSection totalDebit(BigDecimal value) {
    this.totalDebit = value;
    return this;
  }

  public BigDecimal getTotalCredit() {
    return totalCredit;
  }

  public SaftPaymentSection totalCredit(BigDecimal value) {
    this.totalCredit = value;
    return this;
  }

  public Iterable<SaftPayment> getPayments() {
    return payments;
  }

  public SaftPaymentSection payments(Iterable<SaftPayment> value) {
    this.payments = value;
    return this;
  }
}

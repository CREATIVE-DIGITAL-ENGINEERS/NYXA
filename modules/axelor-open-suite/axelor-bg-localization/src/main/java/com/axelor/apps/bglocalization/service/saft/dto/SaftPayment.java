package com.axelor.apps.bglocalization.service.saft.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Payment. */
public final class SaftPayment {

  private String paymentRefNo;

  private Integer period;

  private Integer periodYear;

  private String transactionId;

  private LocalDate transactionDate;

  /** Nom_PaymentMethod — методът (01/02/03). */
  private String paymentMethod;

  private String description;

  /** Поне един. */
  private final List<SaftPaymentLine> lines = new ArrayList<>();

  /** PaymentSettlement/PaymentMechanism — пише се само при попълнен. */
  private String paymentMechanism;

  /** PaymentDocumentTotals се пише само при попълнено бруто. */
  private BigDecimal grossTotal;

  public String getPaymentRefNo() {
    return paymentRefNo;
  }

  public SaftPayment paymentRefNo(String value) {
    this.paymentRefNo = value;
    return this;
  }

  public Integer getPeriod() {
    return period;
  }

  public SaftPayment period(Integer value) {
    this.period = value;
    return this;
  }

  public Integer getPeriodYear() {
    return periodYear;
  }

  public SaftPayment periodYear(Integer value) {
    this.periodYear = value;
    return this;
  }

  public String getTransactionId() {
    return transactionId;
  }

  public SaftPayment transactionId(String value) {
    this.transactionId = value;
    return this;
  }

  public LocalDate getTransactionDate() {
    return transactionDate;
  }

  public SaftPayment transactionDate(LocalDate value) {
    this.transactionDate = value;
    return this;
  }

  public String getPaymentMethod() {
    return paymentMethod;
  }

  public SaftPayment paymentMethod(String value) {
    this.paymentMethod = value;
    return this;
  }

  public String getDescription() {
    return description;
  }

  public SaftPayment description(String value) {
    this.description = value;
    return this;
  }

  public List<SaftPaymentLine> getLines() {
    return lines;
  }

  public SaftPayment addLine(SaftPaymentLine value) {
    this.lines.add(value);
    return this;
  }

  public String getPaymentMechanism() {
    return paymentMechanism;
  }

  public SaftPayment paymentMechanism(String value) {
    this.paymentMechanism = value;
    return this;
  }

  public BigDecimal getGrossTotal() {
    return grossTotal;
  }

  public SaftPayment grossTotal(BigDecimal value) {
    this.grossTotal = value;
    return this;
  }
}

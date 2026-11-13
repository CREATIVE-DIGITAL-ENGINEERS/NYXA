package com.axelor.apps.bglocalization.service.saft.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** PaymentLine. */
public final class SaftPaymentLine {

  private String lineNumber;

  private String sourceDocumentId;

  private String accountId;

  private String customerId;

  private String supplierId;

  private LocalDate taxPointDate;

  private String description;

  /** D / C. */
  private String debitCreditIndicator;

  private SaftAmount paymentLineAmount;

  private final List<SaftTaxInformation> taxInformation = new ArrayList<>();

  public String getLineNumber() {
    return lineNumber;
  }

  public SaftPaymentLine lineNumber(String value) {
    this.lineNumber = value;
    return this;
  }

  public String getSourceDocumentId() {
    return sourceDocumentId;
  }

  public SaftPaymentLine sourceDocumentId(String value) {
    this.sourceDocumentId = value;
    return this;
  }

  public String getAccountId() {
    return accountId;
  }

  public SaftPaymentLine accountId(String value) {
    this.accountId = value;
    return this;
  }

  public String getCustomerId() {
    return customerId;
  }

  public SaftPaymentLine customerId(String value) {
    this.customerId = value;
    return this;
  }

  public String getSupplierId() {
    return supplierId;
  }

  public SaftPaymentLine supplierId(String value) {
    this.supplierId = value;
    return this;
  }

  public LocalDate getTaxPointDate() {
    return taxPointDate;
  }

  public SaftPaymentLine taxPointDate(LocalDate value) {
    this.taxPointDate = value;
    return this;
  }

  public String getDescription() {
    return description;
  }

  public SaftPaymentLine description(String value) {
    this.description = value;
    return this;
  }

  public String getDebitCreditIndicator() {
    return debitCreditIndicator;
  }

  public SaftPaymentLine debitCreditIndicator(String value) {
    this.debitCreditIndicator = value;
    return this;
  }

  public SaftAmount getPaymentLineAmount() {
    return paymentLineAmount;
  }

  public SaftPaymentLine paymentLineAmount(SaftAmount value) {
    this.paymentLineAmount = value;
    return this;
  }

  public List<SaftTaxInformation> getTaxInformation() {
    return taxInformation;
  }

  public SaftPaymentLine addTaxInformation(SaftTaxInformation value) {
    this.taxInformation.add(value);
    return this;
  }
}

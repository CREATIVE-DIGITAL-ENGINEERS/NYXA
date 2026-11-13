package com.axelor.apps.bglocalization.service.saft.dto;

import java.util.ArrayList;
import java.util.List;

/** TransactionLine от главната книга. */
public final class SaftTransactionLine {

  private String recordId;

  private String accountId;

  private String taxpayerAccountId;

  /** Незадължителен. */
  private String sourceDocumentId;

  /** „0“, когато няма. */
  private String customerId;

  /** „0“, когато няма. */
  private String supplierId;

  private String description;

  /** true → DebitAmount, false → CreditAmount. */
  private Boolean debit;

  private SaftAmount amount;

  /** Поне един — схемата го иска на ВСЕКИ ред. */
  private final List<SaftTaxInformation> taxInformation = new ArrayList<>();

  public String getRecordId() {
    return recordId;
  }

  public SaftTransactionLine recordId(String value) {
    this.recordId = value;
    return this;
  }

  public String getAccountId() {
    return accountId;
  }

  public SaftTransactionLine accountId(String value) {
    this.accountId = value;
    return this;
  }

  public String getTaxpayerAccountId() {
    return taxpayerAccountId;
  }

  public SaftTransactionLine taxpayerAccountId(String value) {
    this.taxpayerAccountId = value;
    return this;
  }

  public String getSourceDocumentId() {
    return sourceDocumentId;
  }

  public SaftTransactionLine sourceDocumentId(String value) {
    this.sourceDocumentId = value;
    return this;
  }

  public String getCustomerId() {
    return customerId;
  }

  public SaftTransactionLine customerId(String value) {
    this.customerId = value;
    return this;
  }

  public String getSupplierId() {
    return supplierId;
  }

  public SaftTransactionLine supplierId(String value) {
    this.supplierId = value;
    return this;
  }

  public String getDescription() {
    return description;
  }

  public SaftTransactionLine description(String value) {
    this.description = value;
    return this;
  }

  public Boolean getDebit() {
    return debit;
  }

  public SaftTransactionLine debit(Boolean value) {
    this.debit = value;
    return this;
  }

  public SaftAmount getAmount() {
    return amount;
  }

  public SaftTransactionLine amount(SaftAmount value) {
    this.amount = value;
    return this;
  }

  public List<SaftTaxInformation> getTaxInformation() {
    return taxInformation;
  }

  public SaftTransactionLine addTaxInformation(SaftTaxInformation value) {
    this.taxInformation.add(value);
    return this;
  }
}

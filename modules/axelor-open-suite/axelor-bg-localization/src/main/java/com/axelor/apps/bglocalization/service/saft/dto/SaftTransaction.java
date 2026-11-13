package com.axelor.apps.bglocalization.service.saft.dto;

import java.time.LocalDate;
import java.util.Collections;

/** Transaction от главната книга — един счетоводен запис. */
public final class SaftTransaction {

  private String transactionId;

  private Integer period;

  private Integer periodYear;

  private LocalDate transactionDate;

  private String description;

  private LocalDate systemEntryDate;

  private LocalDate glPostingDate;

  private String customerId;

  private String supplierId;

  /** Поне един. */
  private Iterable<SaftTransactionLine> lines = Collections.emptyList();

  public String getTransactionId() {
    return transactionId;
  }

  public SaftTransaction transactionId(String value) {
    this.transactionId = value;
    return this;
  }

  public Integer getPeriod() {
    return period;
  }

  public SaftTransaction period(Integer value) {
    this.period = value;
    return this;
  }

  public Integer getPeriodYear() {
    return periodYear;
  }

  public SaftTransaction periodYear(Integer value) {
    this.periodYear = value;
    return this;
  }

  public LocalDate getTransactionDate() {
    return transactionDate;
  }

  public SaftTransaction transactionDate(LocalDate value) {
    this.transactionDate = value;
    return this;
  }

  public String getDescription() {
    return description;
  }

  public SaftTransaction description(String value) {
    this.description = value;
    return this;
  }

  public LocalDate getSystemEntryDate() {
    return systemEntryDate;
  }

  public SaftTransaction systemEntryDate(LocalDate value) {
    this.systemEntryDate = value;
    return this;
  }

  public LocalDate getGlPostingDate() {
    return glPostingDate;
  }

  public SaftTransaction glPostingDate(LocalDate value) {
    this.glPostingDate = value;
    return this;
  }

  public String getCustomerId() {
    return customerId;
  }

  public SaftTransaction customerId(String value) {
    this.customerId = value;
    return this;
  }

  public String getSupplierId() {
    return supplierId;
  }

  public SaftTransaction supplierId(String value) {
    this.supplierId = value;
    return this;
  }

  public Iterable<SaftTransactionLine> getLines() {
    return lines;
  }

  public SaftTransaction lines(Iterable<SaftTransactionLine> value) {
    this.lines = value;
    return this;
  }
}

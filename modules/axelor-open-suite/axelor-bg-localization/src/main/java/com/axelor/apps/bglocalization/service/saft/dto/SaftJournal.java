package com.axelor.apps.bglocalization.service.saft.dto;

import java.util.Collections;

/** Journal от главната книга. */
public final class SaftJournal {

  private String journalId;

  private String description;

  private String type;

  /** Поне една. */
  private Iterable<SaftTransaction> transactions = Collections.emptyList();

  public String getJournalId() {
    return journalId;
  }

  public SaftJournal journalId(String value) {
    this.journalId = value;
    return this;
  }

  public String getDescription() {
    return description;
  }

  public SaftJournal description(String value) {
    this.description = value;
    return this;
  }

  public String getType() {
    return type;
  }

  public SaftJournal type(String value) {
    this.type = value;
    return this;
  }

  public Iterable<SaftTransaction> getTransactions() {
    return transactions;
  }

  public SaftJournal transactions(Iterable<SaftTransaction> value) {
    this.transactions = value;
    return this;
  }
}

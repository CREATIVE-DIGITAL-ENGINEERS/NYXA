package com.axelor.apps.bglocalization.service.bankstatement.dto;

import java.util.ArrayList;
import java.util.List;

/** Outcome of a statement import: the created statement id + counts + messages for the UI toast. */
public class BgStatementImportResult {

  public Long bankStatementId;
  public int imported;
  public int duplicates;
  public int invalid; // malformed rows that could not be turned into a movement
  public int manualCorrection; // parsed but needing review (e.g. no matching BankDetails)
  public String importStatus; // matches the bg.bank.statement.import.status.select value
  public final List<String> messages = new ArrayList<>();

  public void addMessage(String m) {
    if (m != null && !m.trim().isEmpty()) {
      messages.add(m.trim());
    }
  }
}

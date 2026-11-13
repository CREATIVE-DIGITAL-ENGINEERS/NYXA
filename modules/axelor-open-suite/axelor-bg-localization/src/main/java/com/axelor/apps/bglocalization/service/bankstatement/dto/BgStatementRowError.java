package com.axelor.apps.bglocalization.service.bankstatement.dto;

/** A single row-level problem: the 1-based spreadsheet row number and a human-readable reason. */
public class BgStatementRowError {

  public final int excelRow;
  public final String reason;

  public BgStatementRowError(int excelRow, String reason) {
    this.excelRow = excelRow;
    this.reason = reason;
  }

  @Override
  public String toString() {
    return "Row " + excelRow + ": " + reason;
  }
}

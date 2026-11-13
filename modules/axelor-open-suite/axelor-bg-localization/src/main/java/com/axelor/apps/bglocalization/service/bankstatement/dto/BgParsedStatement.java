package com.axelor.apps.bglocalization.service.bankstatement.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The result of parsing a Bulgarian bank statement (Postbank XLSX <b>or</b> a PDF/OCR statement):
 * the account header plus the valid parsed movements and any row-level errors. Bank-agnostic in
 * shape (produced by the bank-specific parser).
 */
public class BgParsedStatement {

  public String iban; // resolved from the "Сметка" metadata cell
  public String currencyCode; // e.g. EUR, from the "(EUR)" suffix of the account cell
  public String accountHolder; // "Банков клиент"

  /**
   * Explicit statement period, when the source carries it in the header (the PDF/OCR statement
   * prints "01.01.2026 - 31.01.2026"). Null for the XLSX export, which has no header period — the
   * persist step then falls back to the min/max of movement dates.
   */
  public LocalDate periodFrom;

  public LocalDate periodTo;

  public final List<BgBankMovement> movements = new ArrayList<>();
  public final List<BgStatementRowError> errors = new ArrayList<>();

  /**
   * True when the header row and account were recognized (i.e. this really is the expected layout).
   */
  public boolean recognized;
}

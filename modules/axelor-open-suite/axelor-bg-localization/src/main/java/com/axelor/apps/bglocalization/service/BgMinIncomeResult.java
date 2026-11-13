package com.axelor.apps.bglocalization.service;

import java.math.BigDecimal;

/**
 * Result of a minimum insurance income (МОД) lookup.
 *
 * <p>Carries not only the amount but also the row code and qualification group, because both are
 * mandatory fields of Declaration 1: item 12.3 requires the row number from Appendix 1 of the State
 * Social Insurance Budget Act, and item 12.1 requires the qualification group column number.
 * Returning them here prevents WS-4 from having to repeat the lookup.
 *
 * <p>This class is immutable and carries no Axelor dependency, so it is unit testable in isolation.
 */
public class BgMinIncomeResult {

  public enum Status {
    /** Amount successfully resolved. */
    RESOLVED,
    /**
     * МОД does not apply to this insured type. Not an error — caller falls back to its own floor.
     */
    NOT_APPLICABLE,
    /** Insured type code not found for the period. */
    INSURED_TYPE_NOT_FOUND,
    /** Employment contract has no НКПД code. */
    NKPD_MISSING,
    /** НКПД code is malformed (not 8 digits, or first character is not a digit). */
    NKPD_INVALID,
    /** НКПД class 0 (armed forces). Appendix 1 defines no row for this class. */
    QUALIFICATION_GROUP_ZERO,
    /** Company has no economic activity code assigned. */
    NKID_MISSING,
    /** НКИД code not found in the nomenclature. */
    NKID_NOT_FOUND,
    /** НКИД maps to more than one row; company must set an explicit override. */
    AMBIGUOUS_REQUIRES_OVERRIDE,
    /** НКИД exists but has no МОД row. Legitimate for 98.10 and 98.20 (household employers). */
    NO_MOD_ROW_FOR_NKID,
    /** Row code does not resolve to a row valid for the period. */
    MOD_ROW_NOT_FOUND,
    /**
     * Row exists but defines no amount for this qualification group. Unreachable by construction —
     * sparse rows are only reachable through an НКПД-level override whose group always matches.
     * Reaching it means the override table is wrong.
     */
    AMOUNT_NOT_DEFINED
  }

  private final Status status;
  private final BigDecimal amount;
  private final String rowCode;
  private final Integer qualificationGroup;
  private final String detail;

  private BgMinIncomeResult(
      Status status, BigDecimal amount, String rowCode, Integer qualificationGroup, String detail) {
    this.status = status;
    this.amount = amount;
    this.rowCode = rowCode;
    this.qualificationGroup = qualificationGroup;
    this.detail = detail;
  }

  public static BgMinIncomeResult resolved(BigDecimal amount, String rowCode, int group) {
    return new BgMinIncomeResult(Status.RESOLVED, amount, rowCode, group, null);
  }

  public static BgMinIncomeResult notApplicable(String detail) {
    return new BgMinIncomeResult(Status.NOT_APPLICABLE, null, null, null, detail);
  }

  public static BgMinIncomeResult failure(Status status, String detail) {
    return new BgMinIncomeResult(status, null, null, null, detail);
  }

  /** Failure that still knows the row and group — useful for diagnostics. */
  public static BgMinIncomeResult failure(
      Status status, String rowCode, Integer group, String detail) {
    return new BgMinIncomeResult(status, null, rowCode, group, detail);
  }

  public Status getStatus() {
    return status;
  }

  public boolean isResolved() {
    return status == Status.RESOLVED;
  }

  /** True when the caller must stop and surface an error rather than substitute a default. */
  public boolean isBlocking() {
    return status != Status.RESOLVED && status != Status.NOT_APPLICABLE;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  /** Row number from Appendix 1 — Declaration 1, item 12.3. */
  public String getRowCode() {
    return rowCode;
  }

  /** Qualification group column number — Declaration 1, item 12.1. */
  public Integer getQualificationGroup() {
    return qualificationGroup;
  }

  /** Context for the error message: which НКИД, which code, which group. */
  public String getDetail() {
    return detail;
  }

  @Override
  public String toString() {
    return "BgMinIncomeResult["
        + status
        + (amount != null ? ", amount=" + amount : "")
        + (rowCode != null ? ", row=" + rowCode : "")
        + (qualificationGroup != null ? ", group=" + qualificationGroup : "")
        + (detail != null ? ", " + detail : "")
        + "]";
  }
}

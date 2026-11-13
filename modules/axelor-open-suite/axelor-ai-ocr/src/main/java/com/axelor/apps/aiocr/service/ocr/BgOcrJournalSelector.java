package com.axelor.apps.aiocr.service.ocr;

/**
 * Pure selection rule for the supplier-purchase journal set on an OCR DRAFT invoice. The invoice
 * journal must be the EXISTING purchase journal with code {@code PCH} (label "Покупки"), scoped to
 * the invoice's own company and of the purchase journal type. Selection is exact: never the first
 * record, never a newly created journal. Zero or several candidates → leave the journal empty and
 * let the accountant set it (a readiness warning is raised by the intake service).
 *
 * <p>In this repo's BG chart of accounts the purchase journal's {@code JournalType} has {@code
 * technicalTypeSelect = 1} (JournalType name "Purchase"); that is the type checked here. No
 * dependencies, so it is fully unit-testable.
 */
public final class BgOcrJournalSelector {

  /** Exact journal code of the BG supplier-purchase journal. */
  public static final String PURCHASE_JOURNAL_CODE = "PCH";

  /** JournalType.technicalTypeSelect for the purchase journal in the BG chart (EXPENSE = 1). */
  public static final int PURCHASE_TECHNICAL_TYPE = 1;

  /** What the intake should do given the count of exact, company-scoped PCH matches. */
  public enum Decision {
    SELECT,
    WARN_MULTIPLE,
    NONE
  }

  private BgOcrJournalSelector() {}

  /**
   * True when a candidate journal is the purchase journal for the invoice's company: exact code
   * {@code PCH}, same non-null company, purchase journal type. Mirrors the intake DB filter so the
   * "other company / wrong type" exclusions are unit-testable.
   */
  public static boolean isPurchaseJournal(
      String code, Long journalCompanyId, Integer technicalType, Long invoiceCompanyId) {
    return code != null
        && PURCHASE_JOURNAL_CODE.equalsIgnoreCase(code.trim())
        && journalCompanyId != null
        && journalCompanyId.equals(invoiceCompanyId)
        && technicalType != null
        && technicalType == PURCHASE_TECHNICAL_TYPE;
  }

  public static Decision decide(int matchCount) {
    if (matchCount == 1) {
      return Decision.SELECT;
    }
    return matchCount > 1 ? Decision.WARN_MULTIPLE : Decision.NONE;
  }
}

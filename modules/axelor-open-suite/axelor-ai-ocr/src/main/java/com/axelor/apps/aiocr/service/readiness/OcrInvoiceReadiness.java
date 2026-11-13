package com.axelor.apps.aiocr.service.readiness;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured readiness report for an OCR-generated DRAFT supplier invoice. Says what is populated,
 * what still blocks the standard Validate/Ventilate, which company/partner configuration is
 * missing, and carries the OCR warnings/discrepancies. It never changes the invoice — it only
 * describes it, so the accountant knows exactly what to fix before validating.
 */
public class OcrInvoiceReadiness {

  private final List<String> populated = new ArrayList<>();
  private final List<String> validateBlockers = new ArrayList<>();
  private final List<String> ventilateBlockers = new ArrayList<>();
  private final List<String> warnings = new ArrayList<>();
  private final List<OcrSupplierInvoiceReadinessService.LineIssue> lineIssues = new ArrayList<>();

  public List<String> getPopulated() {
    return populated;
  }

  /**
   * Per-line problems, so a caller can act on one line instead of parsing {@link #toNotes()}. Empty
   * when every line has a product, an account and a tax.
   */
  public List<OcrSupplierInvoiceReadinessService.LineIssue> getLineIssues() {
    return lineIssues;
  }

  public List<String> getValidateBlockers() {
    return validateBlockers;
  }

  public List<String> getVentilateBlockers() {
    return ventilateBlockers;
  }

  public List<String> getWarnings() {
    return warnings;
  }

  /** Validate-ready when there are no Validate blockers (config + header essentials present). */
  public boolean isValidateReady() {
    return validateBlockers.isEmpty();
  }

  /** Ventilate-ready implies validate-ready plus no ventilate blockers. */
  public boolean isVentilateReady() {
    return validateBlockers.isEmpty() && ventilateBlockers.isEmpty();
  }

  /**
   * Human-readable notes for the AI OCR panel / aiValidationNotes (no secrets, no raw content). The
   * concrete problems (what OCR could not import and why — e.g. "IBAN belongs to another partner",
   * "address incomplete") lead, so the accountant reads the reasons first; the accounting-readiness
   * verdict (a SEPARATE concern from the OCR review flag) is summarised last.
   */
  public String toNotes() {
    StringBuilder sb = new StringBuilder();
    appendSection(sb, "Warnings / discrepancies", warnings);
    appendSection(sb, "Blocks Validate", validateBlockers);
    appendSection(sb, "Blocks Ventilate", ventilateBlockers);
    sb.append("AI-OCR readiness: ")
        .append(isValidateReady() ? "ready to Validate" : "NOT ready to Validate")
        .append(isVentilateReady() ? "; ready to Ventilate." : "; Ventilate still blocked.");
    return sb.toString().trim();
  }

  private static void appendSection(StringBuilder sb, String title, List<String> items) {
    if (items.isEmpty()) {
      return;
    }
    sb.append(title).append(":\n");
    for (String item : items) {
      sb.append("  - ").append(item).append('\n');
    }
  }
}

package com.axelor.apps.aiocr.service.ocr;

import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure decision gate: may a DRAFT supplier invoice be created from an extraction result?
 *
 * <p>A draft is created ONLY when extraction succeeded AND the critical fields are present AND
 * confidence is at least the hard floor. This class has no dependencies (no DB/Guice) so it is
 * fully unit-testable; the intake service calls it and, on any issue, leaves the upload FAILED
 * without creating an invoice. Duplicate detection is separate (needs the DB) and handled by the
 * intake service.
 */
public final class BgOcrGate {

  private BgOcrGate() {}

  /**
   * @return the list of blocking issues; empty means the gate passes.
   * @param hardFloor minimum acceptable confidence (0..100); below it → no draft.
   */
  public static List<String> issues(OcrExtractResult result, BigDecimal hardFloor) {
    List<String> issues = new ArrayList<>();
    if (result == null) {
      issues.add("No extraction result.");
      return issues;
    }
    if (result.isExtractionFailed()) {
      issues.add("Extraction failed: " + String.join("; ", result.getErrors()));
      return issues;
    }
    issues.addAll(fieldIssues(result));
    if (hardFloor != null
        && result.getConfidence() != null
        && result.getConfidence().compareTo(hardFloor) < 0) {
      issues.add(
          "Confidence " + result.getConfidence() + "% is below the hard floor " + hardFloor + "%.");
    }
    return issues;
  }

  /**
   * The missing/invalid CRITICAL invoice fields (supplier, invoice number, gross total) — the
   * confidence floor is intentionally NOT included. Used by the intake to create a completable
   * draft and flag what the user must fill, rather than failing the whole job.
   */
  public static List<String> fieldIssues(OcrExtractResult result) {
    List<String> issues = new ArrayList<>();
    if (result == null) {
      return issues;
    }
    if (isBlank(result.getSupplierName()) && isBlank(result.getSupplierEik())) {
      issues.add("supplier (name/EIK)");
    }
    if (isBlank(result.getInvoiceNumber())) {
      issues.add("invoice number");
    }
    if (result.getTotalGross() == null || result.getTotalGross().signum() <= 0) {
      issues.add("total (gross) amount");
    }
    return issues;
  }

  public static boolean passes(OcrExtractResult result, BigDecimal hardFloor) {
    return issues(result, hardFloor).isEmpty();
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}

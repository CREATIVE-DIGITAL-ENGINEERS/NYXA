package com.axelor.apps.aiocr.service.ai;

import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Carries the (possibly corrected) extraction together with the audit trail of what the AI did.
 *
 * <p>A bare {@code OcrExtractResult} return would lose the audit trail, and the places that need it
 * sit in different branches of {@code routeInvoice}: the draft path writes {@code
 * Invoice.aiValidationNotes}, while the supplier-unmatched path has no invoice at all and must
 * write the upload's {@code extractionInformation}. Threading one immutable value through the
 * method keeps that possible without a field — which matters, because the intake service runs
 * concurrent workers.
 *
 * <p>{@link #toNotes()} mirrors the {@code OcrInvoiceReadiness.toNotes()} idiom already used in
 * {@code service/readiness}, so note-building stays consistent across the module.
 */
public final class BgOcrAiCorrection {

  /** One accepted change, recorded before/after for the audit trail. */
  public static final class Applied {
    private final String field;
    private final String oldValue;
    private final String newValue;
    private final double confidence;
    private final String reason;

    public Applied(
        String field, String oldValue, String newValue, double confidence, String reason) {
      this.field = field;
      this.oldValue = oldValue;
      this.newValue = newValue;
      this.confidence = confidence;
      this.reason = reason;
    }

    public String getField() {
      return field;
    }

    public String getOldValue() {
      return oldValue;
    }

    public String getNewValue() {
      return newValue;
    }

    public double getConfidence() {
      return confidence;
    }

    public String getReason() {
      return reason;
    }
  }

  private final OcrExtractResult result;
  private final List<Applied> applied;
  private final List<String> rejected;
  private final List<String> unresolved;
  private final List<String> toolsUsed;
  private final String failureNote;
  private final boolean aiApplied;

  private BgOcrAiCorrection(
      OcrExtractResult result,
      List<Applied> applied,
      List<String> rejected,
      List<String> unresolved,
      List<String> toolsUsed,
      String failureNote) {
    this.result = result;
    this.applied = applied == null ? new ArrayList<>() : applied;
    this.rejected = rejected == null ? new ArrayList<>() : rejected;
    this.unresolved = unresolved == null ? new ArrayList<>() : unresolved;
    this.toolsUsed = toolsUsed == null ? new ArrayList<>() : toolsUsed;
    this.failureNote = failureNote;
    this.aiApplied = !this.applied.isEmpty();
  }

  /**
   * The no-op outcome: AI disabled, not applicable, timed out, or failed. {@link #getResult()}
   * returns the untouched extraction, so callers never need a null check and behaviour is identical
   * to the pre-AI pipeline.
   */
  public static BgOcrAiCorrection none(OcrExtractResult original) {
    return new BgOcrAiCorrection(original, null, null, null, null, null);
  }

  public static BgOcrAiCorrection failed(OcrExtractResult original, String note) {
    return new BgOcrAiCorrection(original, null, null, null, null, note);
  }

  public static BgOcrAiCorrection of(
      OcrExtractResult result,
      List<Applied> applied,
      List<String> rejected,
      List<String> unresolved,
      List<String> toolsUsed) {
    return new BgOcrAiCorrection(result, applied, rejected, unresolved, toolsUsed, null);
  }

  public OcrExtractResult getResult() {
    return result;
  }

  public List<Applied> getApplied() {
    return Collections.unmodifiableList(applied);
  }

  public List<String> getRejected() {
    return Collections.unmodifiableList(rejected);
  }

  public List<String> getUnresolved() {
    return Collections.unmodifiableList(unresolved);
  }

  public List<String> getToolsUsed() {
    return Collections.unmodifiableList(toolsUsed);
  }

  /** True when at least one correction survived validation and was written to the extraction. */
  public boolean isAiApplied() {
    return aiApplied;
  }

  /** Nothing worth recording happened. */
  public boolean isEmpty() {
    return applied.isEmpty() && rejected.isEmpty() && unresolved.isEmpty() && failureNote == null;
  }

  /**
   * Human-readable audit note, or null when there is nothing to say — so callers can assign the
   * result straight to a field without writing an empty string.
   */
  public String toNotes() {
    if (isEmpty()) {
      return null;
    }
    StringBuilder sb = new StringBuilder("AI Accountant:");
    if (failureNote != null) {
      sb.append("\n- not applied: ").append(failureNote);
    }
    for (Applied a : applied) {
      sb.append("\n- ")
          .append(a.getField())
          .append(": ")
          .append(display(a.getOldValue()))
          .append(" → ")
          .append(display(a.getNewValue()))
          .append(" (confidence ")
          .append(String.format(java.util.Locale.ROOT, "%.2f", a.getConfidence()))
          .append(")");
      if (a.getReason() != null && !a.getReason().trim().isEmpty()) {
        sb.append(" — ").append(a.getReason().trim());
      }
    }
    for (String r : rejected) {
      sb.append("\n- rejected: ").append(r);
    }
    if (!unresolved.isEmpty()) {
      sb.append("\n- still unresolved: ").append(String.join(", ", unresolved));
    }
    if (!toolsUsed.isEmpty()) {
      sb.append("\n- tools used: ").append(String.join(", ", toolsUsed));
    }
    return sb.toString();
  }

  private static String display(String v) {
    return (v == null || v.trim().isEmpty()) ? "(empty)" : v;
  }
}

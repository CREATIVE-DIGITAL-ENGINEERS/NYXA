package com.axelor.apps.aiaccountant.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** One target record of an active workflow, and what became of it. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AiWorkflowRecord {

  /** Keeps a blocker reason a sentence long: it is a label, not the tool's full explanation. */
  public static final int MAX_REASON_CHARS = 160;

  /** {@code Invoice#606} — the same short form the rest of the state uses. */
  private String ref;

  /** {@link com.axelor.apps.aiaccountant.service.AiBlockerClass} name, or null while unblocked. */
  private String blocker;

  /**
   * The blocker's own code — {@code DUPLICATE_INVOICE_NUMBER}, {@code MOVE_NOT_BALANCED} — as the
   * tool named it, or null when it named none.
   *
   * <p>Kept beside the class rather than instead of it. The class answers the orchestration
   * question (keep working, ask a person, say it cannot be done); the code says <em>which</em>
   * blocker it was, which is what makes two turns comparable and what a later phase can act on
   * without going back to the sentence. Deriving one from the other only works in one direction.
   */
  private String blockerCode;

  /** Why it is blocked, in the tool's own words, truncated. Null while unblocked. */
  private String reason;

  public AiWorkflowRecord() {}

  public AiWorkflowRecord(String ref) {
    this.ref = ref;
  }

  public String getRef() {
    return ref;
  }

  public void setRef(String ref) {
    this.ref = ref;
  }

  public String getBlocker() {
    return blocker;
  }

  public void setBlocker(String blocker) {
    this.blocker = blocker;
  }

  public String getBlockerCode() {
    return blockerCode;
  }

  public void setBlockerCode(String blockerCode) {
    this.blockerCode = blockerCode;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    if (reason == null) {
      this.reason = null;
      return;
    }
    String trimmed = reason.trim();
    this.reason = trimmed.length() > MAX_REASON_CHARS ? truncateAtWord(trimmed) : trimmed;
  }

  /**
   * Cuts to the last whole word that fits within {@code MAX_REASON_CHARS - 1} (room for the
   * ellipsis), so a blocked-target reason never ends mid-word.
   */
  private static String truncateAtWord(String text) {
    String head = text.substring(0, MAX_REASON_CHARS - 1);
    int lastSpace = head.lastIndexOf(' ');
    String cut = lastSpace > 0 ? head.substring(0, lastSpace) : head;
    return cut + "…";
  }

  public boolean isBlocked() {
    return blocker != null;
  }
}

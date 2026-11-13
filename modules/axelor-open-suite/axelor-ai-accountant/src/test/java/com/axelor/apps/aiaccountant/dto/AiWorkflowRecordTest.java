package com.axelor.apps.aiaccountant.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link AiWorkflowRecord#setReason} used to hard-cut a long blocker reason at {@code
 * MAX_REASON_CHARS} with no regard for word boundaries — reproduced by {@code
 * AiWriteAuthorisation}'s own NONE-explicitness refusal, which lands mid-word ("...if a ch") at
 * exactly 160 characters. It now stops at the last whole word and marks the cut with an ellipsis.
 */
class AiWorkflowRecordTest {

  private static final String NONE_EXPLICITNESS_REFUSAL =
      "Nothing in this message asked for a change — it names no outcome and no correction, so"
          + " there is no authorisation for a write. Answer what was asked, and if a change is"
          + " needed, say what you would change and let the user ask for it. Nothing was"
          + " changed.";

  @Test
  void longReasonIsCutAtAWordBoundaryWithAnEllipsis() {
    AiWorkflowRecord record = new AiWorkflowRecord("Invoice#645");

    record.setReason(NONE_EXPLICITNESS_REFUSAL);

    String reason = record.getReason();
    assertTrue(
        reason.length() <= AiWorkflowRecord.MAX_REASON_CHARS,
        "must stay within the documented limit");
    assertTrue(reason.endsWith("…"), "a truncated reason must say so");
    assertFalse(reason.contains(" ch…"), "must not cut in the middle of a word");
  }

  @Test
  void shortReasonIsKeptExactly() {
    AiWorkflowRecord record = new AiWorkflowRecord("Invoice#645");

    record.setReason("Duplicate invoice number.");

    assertEquals("Duplicate invoice number.", record.getReason());
  }
}

package com.axelor.apps.napsubmissions.common.submission;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The stored summary must never be renderable markup and must respect the length cap. */
class ProcessingResultSummaryTest {

  @Test
  void stripsTagsAndNeutralizesAngleBrackets() {
    String out = NapProcessingResultService.toSafePlainText("<script>alert(1)</script> ok");
    assertFalse(out.contains("<script>"));
    assertFalse(out.contains("<"));
    assertFalse(out.contains(">"));
    assertTrue(out.contains("ok"));
  }

  @Test
  void truncatesToHardLimit() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 5000; i++) {
      sb.append('x');
    }
    String out = NapProcessingResultService.toSafePlainText(sb.toString());
    assertTrue(out.length() <= NapProcessingResultService.MAX_SUMMARY_LENGTH);
  }

  @Test
  void nullStaysNull() {
    assertNull(NapProcessingResultService.toSafePlainText(null));
  }
}

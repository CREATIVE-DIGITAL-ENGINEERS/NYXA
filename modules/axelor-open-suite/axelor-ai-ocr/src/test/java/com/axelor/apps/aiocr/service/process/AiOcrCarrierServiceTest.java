package com.axelor.apps.aiocr.service.process;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import org.junit.jupiter.api.Test;

/** Pure tests for the carrier status + progress rollup (no Guice/DB). */
class AiOcrCarrierServiceTest {

  @Test
  void extractingUntilFinished() {
    assertEquals(AiOcrUploadStatus.EXTRACTING, AiOcrCarrierService.computeStatus(5, 2, 0, false));
    assertEquals(0, AiOcrCarrierService.computePercent(0, 0, false));
    // percent can advance while extracting but never hits 100 until finished
    assertEquals(40, AiOcrCarrierService.computePercent(5, 2, false));
  }

  @Test
  void processingWhenPendingChildrenRemain() {
    assertEquals(AiOcrUploadStatus.PROCESSING, AiOcrCarrierService.computeStatus(5, 3, 0, true));
    assertEquals(60, AiOcrCarrierService.computePercent(5, 3, true));
    assertEquals(99, AiOcrCarrierService.computePercent(100, 100 - 1, true)); // capped below 100
  }

  @Test
  void completedWhenAllTerminalAndClean() {
    assertEquals(AiOcrUploadStatus.COMPLETED, AiOcrCarrierService.computeStatus(5, 5, 0, true));
    assertEquals(100, AiOcrCarrierService.computePercent(5, 5, true));
  }

  @Test
  void completedWithIssuesWhenAnyProblematic() {
    assertEquals(
        AiOcrUploadStatus.COMPLETED_WITH_ISSUES, AiOcrCarrierService.computeStatus(5, 5, 2, true));
    assertEquals(100, AiOcrCarrierService.computePercent(5, 5, true));
  }

  @Test
  void failedWhenFinishedWithNoChildren() {
    assertEquals(AiOcrUploadStatus.FAILED, AiOcrCarrierService.computeStatus(0, 0, 0, true));
    assertEquals(100, AiOcrCarrierService.computePercent(0, 0, true));
  }

  @Test
  void remainingNeverNegativeViaPercentBounds() {
    // terminal can't exceed total in practice, but guard the math anyway.
    assertEquals(100, AiOcrCarrierService.computePercent(3, 5, true));
  }
}

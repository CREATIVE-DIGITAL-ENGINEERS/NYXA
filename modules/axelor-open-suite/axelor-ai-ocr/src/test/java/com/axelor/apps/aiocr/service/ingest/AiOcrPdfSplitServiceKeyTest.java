package com.axelor.apps.aiocr.service.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * The bank-statement split child key must be deterministic from the ORIGINAL carrier (hash + page
 * range + ordinal), never the non-stable slice bytes — so re-splitting the same carrier yields the
 * same keys and the {@code (importArchive, extractionKey)} unique index skips existing children (no
 * duplicate statement children on a retry/restart).
 */
class AiOcrPdfSplitServiceKeyTest {

  @Test
  void keyIsDeterministicPerCarrierAndRange() {
    String a = AiOcrPdfSplitService.bankStatementKey("HASH", 1, 2, 0);
    String b = AiOcrPdfSplitService.bankStatementKey("HASH", 1, 2, 0);
    assertEquals(a, b);
  }

  @Test
  void differentRangeOrdinalOrCarrierGivesDifferentKeys() {
    String base = AiOcrPdfSplitService.bankStatementKey("HASH", 1, 2, 0);
    assertNotEquals(base, AiOcrPdfSplitService.bankStatementKey("HASH", 3, 4, 0)); // range
    assertNotEquals(base, AiOcrPdfSplitService.bankStatementKey("HASH", 1, 2, 1)); // ordinal
    assertNotEquals(base, AiOcrPdfSplitService.bankStatementKey("OTHER", 1, 2, 0)); // carrier
  }
}

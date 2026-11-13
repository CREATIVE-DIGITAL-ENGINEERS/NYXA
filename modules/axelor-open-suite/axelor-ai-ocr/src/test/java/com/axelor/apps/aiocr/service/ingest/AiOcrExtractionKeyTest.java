package com.axelor.apps.aiocr.service.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/** Pure tests for the deterministic, restart-safe extraction key. */
class AiOcrExtractionKeyTest {

  @Test
  void normalizesPathForStableKeys() {
    // Backslashes, case and surrounding space must not change the key (restart-safe idempotency).
    assertEquals(
        AiOcrArchiveExtractService.keyFor("Folder/Doc.PDF", "abc"),
        AiOcrArchiveExtractService.keyFor("  folder\\doc.pdf ", "abc"));
  }

  @Test
  void documentKeyUsesHash_outcomeKeyUsesType() {
    String doc = AiOcrArchiveExtractService.keyFor("a/b.pdf", "hash123");
    String unsupported = AiOcrArchiveExtractService.keyFor("a/b.pdf", "unsupported");
    String corrupt = AiOcrArchiveExtractService.keyFor("a/b.pdf", "corrupt");
    assertNotEquals(doc, unsupported);
    assertNotEquals(doc, corrupt);
    assertNotEquals(unsupported, corrupt);
    // Same path + same hash → identical key (upsert skips the already-created child on re-run).
    assertEquals(doc, AiOcrArchiveExtractService.keyFor("a/b.pdf", "hash123"));
  }

  @Test
  void differentContentSamePathDiffersByHash() {
    assertNotEquals(
        AiOcrArchiveExtractService.keyFor("a/b.pdf", "hash1"),
        AiOcrArchiveExtractService.keyFor("a/b.pdf", "hash2"));
  }
}

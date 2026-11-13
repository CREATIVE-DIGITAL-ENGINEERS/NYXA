package com.axelor.apps.aiocr.service.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Pure tests for the SHA-256 dedup hash. */
class AiOcrFileHashTest {

  @Test
  void knownVector() {
    // SHA-256 of the empty input.
    assertEquals(
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        AiOcrFileHash.sha256(new byte[0]));
  }

  @Test
  void sameContentSameHash_differentContentDifferentHash() throws IOException {
    byte[] a = "hello world".getBytes(StandardCharsets.UTF_8);
    byte[] b = "hello worle".getBytes(StandardCharsets.UTF_8);
    assertEquals(AiOcrFileHash.sha256(a), AiOcrFileHash.sha256(a));
    assertNotEquals(AiOcrFileHash.sha256(a), AiOcrFileHash.sha256(b));
  }

  @Test
  void streamAndBytesAgree() throws IOException {
    byte[] content = "the quick brown fox".getBytes(StandardCharsets.UTF_8);
    Path tmp = Files.createTempFile("aiocr-hash-", ".bin");
    try {
      Files.write(tmp, content);
      assertEquals(AiOcrFileHash.sha256(content), AiOcrFileHash.sha256(tmp));
    } finally {
      Files.deleteIfExists(tmp);
    }
  }
}

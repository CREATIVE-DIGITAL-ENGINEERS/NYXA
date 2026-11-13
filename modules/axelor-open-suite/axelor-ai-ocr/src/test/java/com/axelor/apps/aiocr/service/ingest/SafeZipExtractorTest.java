package com.axelor.apps.aiocr.service.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Pure tests for the recursive, guarded ZIP extractor (zip-bomb, limits, traversal, corruption).
 */
class SafeZipExtractorTest {

  /** Collects the callbacks so the test can assert what was discovered. */
  private static final class Collector implements SafeZipExtractor.EntryHandler {
    final List<String> documents = new ArrayList<>();
    final List<String> unsupported = new ArrayList<>();
    final List<String> corruptNested = new ArrayList<>();

    @Override
    public void onDocument(String path, Path tempFile) throws IOException {
      // Prove the bytes were materialised and readable during the callback.
      assertTrue(Files.exists(tempFile));
      documents.add(path);
    }

    @Override
    public void onUnsupported(String path) {
      unsupported.add(path);
    }

    @Override
    public void onCorruptNested(String path, String reason) {
      corruptNested.add(path);
    }
  }

  // ---------------------------------------------------------------------------

  @Test
  void extractsSupported_recursesNested_flagsUnsupported() throws IOException {
    Map<String, byte[]> inner = new LinkedHashMap<>();
    inner.put("c.png", bytes(100));
    byte[] innerZip = zip(inner);

    Map<String, byte[]> outer = new LinkedHashMap<>();
    outer.put("a.pdf", bytes(100));
    outer.put("docs/b.jpg", bytes(100));
    outer.put("notes.txt", bytes(50)); // unsupported
    outer.put("nested/inner.zip", innerZip);
    Path zip = writeTemp(zip(outer));

    Collector c = new Collector();
    try {
      SafeZipExtractor.extract(zip, new SafeZipExtractor.Limits(), c);
    } finally {
      Files.deleteIfExists(zip);
    }

    assertTrue(c.documents.contains("a.pdf"));
    assertTrue(c.documents.contains("docs/b.jpg"));
    // Nested paths are preserved (archive path + inner entry path).
    assertTrue(c.documents.contains("nested/inner.zip/c.png"));
    assertEquals(Arrays.asList("notes.txt"), c.unsupported);
  }

  @Test
  void rejectsZipSlipEntry() throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("../evil.pdf", bytes(10));
    Path zip = writeTemp(zip(entries));
    try {
      assertThrows(
          SafeZipExtractor.UnreadableArchiveException.class,
          () -> SafeZipExtractor.extract(zip, new SafeZipExtractor.Limits(), new Collector()));
    } finally {
      Files.deleteIfExists(zip);
    }
  }

  @Test
  void enforcesEntryCountLimit() throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("a.pdf", bytes(10));
    entries.put("b.pdf", bytes(10));
    entries.put("c.pdf", bytes(10));
    Path zip = writeTemp(zip(entries));
    SafeZipExtractor.Limits limits = new SafeZipExtractor.Limits();
    limits.maxEntries = 2;
    try {
      assertThrows(
          SafeZipExtractor.ArchiveLimitException.class,
          () -> SafeZipExtractor.extract(zip, limits, new Collector()));
    } finally {
      Files.deleteIfExists(zip);
    }
  }

  @Test
  void enforcesPerEntrySizeLimit() throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("big.pdf", bytes(5000));
    Path zip = writeTemp(zip(entries));
    SafeZipExtractor.Limits limits = new SafeZipExtractor.Limits();
    limits.maxEntryBytes = 1000;
    try {
      assertThrows(
          SafeZipExtractor.ArchiveLimitException.class,
          () -> SafeZipExtractor.extract(zip, limits, new Collector()));
    } finally {
      Files.deleteIfExists(zip);
    }
  }

  @Test
  void enforcesTotalSizeLimit() throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("a.pdf", bytes(3000));
    entries.put("b.pdf", bytes(3000));
    Path zip = writeTemp(zip(entries));
    SafeZipExtractor.Limits limits = new SafeZipExtractor.Limits();
    limits.maxTotalBytes = 5000;
    try {
      assertThrows(
          SafeZipExtractor.ArchiveLimitException.class,
          () -> SafeZipExtractor.extract(zip, limits, new Collector()));
    } finally {
      Files.deleteIfExists(zip);
    }
  }

  @Test
  void detectsZipBombByExpansionRatio() throws IOException {
    // A highly compressible entry: 500 KB of zeros compresses to a few hundred bytes, so the
    // expansion ratio is enormous relative to the tiny archive.
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("zeros.pdf", new byte[500_000]);
    Path zip = writeTemp(zip(entries));
    SafeZipExtractor.Limits limits = new SafeZipExtractor.Limits();
    limits.maxRatio = 10;
    try {
      assertThrows(
          SafeZipExtractor.ArchiveLimitException.class,
          () -> SafeZipExtractor.extract(zip, limits, new Collector()));
    } finally {
      Files.deleteIfExists(zip);
    }
  }

  @Test
  void enforcesNestingDepthLimit() throws IOException {
    Map<String, byte[]> level0 = new LinkedHashMap<>();
    level0.put("deep.pdf", bytes(10));
    byte[] z0 = zip(level0);

    Map<String, byte[]> level1 = new LinkedHashMap<>();
    level1.put("inner.zip", z0);
    byte[] z1 = zip(level1);

    Map<String, byte[]> level2 = new LinkedHashMap<>();
    level2.put("mid.zip", z1);
    Path zip = writeTemp(zip(level2));

    SafeZipExtractor.Limits limits = new SafeZipExtractor.Limits();
    limits.maxDepth = 1; // outer=0, mid=1, inner=2 → exceeds
    try {
      assertThrows(
          SafeZipExtractor.ArchiveLimitException.class,
          () -> SafeZipExtractor.extract(zip, limits, new Collector()));
    } finally {
      Files.deleteIfExists(zip);
    }
  }

  @Test
  void rejectsCorruptedArchive() throws IOException {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("a.pdf", bytes(2000));
    byte[] good = zip(entries);
    byte[] truncated = Arrays.copyOf(good, good.length / 2); // cut mid-stream
    Path zip = writeTemp(truncated);
    try {
      assertThrows(
          SafeZipExtractor.ArchiveRejectedException.class,
          () -> SafeZipExtractor.extract(zip, new SafeZipExtractor.Limits(), new Collector()));
    } finally {
      Files.deleteIfExists(zip);
    }
  }

  // ---------------------------------------------------------------------------

  private static byte[] bytes(int n) {
    byte[] b = new byte[n];
    for (int i = 0; i < n; i++) {
      b[i] = (byte) (i % 251);
    }
    return b;
  }

  private static byte[] zip(Map<String, byte[]> entries) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(out)) {
      for (Map.Entry<String, byte[]> e : entries.entrySet()) {
        zos.putNextEntry(new ZipEntry(e.getKey()));
        zos.write(e.getValue());
        zos.closeEntry();
      }
    }
    return out.toByteArray();
  }

  private static Path writeTemp(byte[] content) throws IOException {
    Path p = Files.createTempFile("aiocr-test-", ".zip");
    Files.write(p, content);
    return p;
  }
}

package com.axelor.apps.aiocr.service.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.storage.OcrMetaFileReferenceScanner.BatchResult;
import com.axelor.apps.aiocr.service.storage.OcrUploadStorageDiagnosticService.DiskWalk;
import com.axelor.apps.aiocr.service.storage.OcrUploadStorageDiagnosticService.FileRow;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure tests for the scan's decision logic: every I/O seam ({@link
 * OcrUploadStorageDiagnosticService#resolveUploadRoot()}, {@code walkUploadRoot}, {@code
 * fetchMetaFilePage}, {@code checkReferences}) is stubbed, so these exercise only the counting and
 * fail-closed rules — no database, no filesystem.
 */
class OcrUploadStorageDiagnosticServiceTest {

  /** A configurable stand-in for the real disk walk + MetaFile table + reference scanner. */
  private static final class Fake extends OcrUploadStorageDiagnosticService {

    DiskWalk diskWalk = new DiskWalk(new HashMap<>(), true, 0L, 0L);
    List<FileRow> metaFileRows = new ArrayList<>();
    Set<Long> referenced = new LinkedHashSet<>();
    String referenceFailure;

    Fake() {
      super(null);
    }

    @Override
    protected Path resolveUploadRoot() {
      return Path.of(".");
    }

    @Override
    protected Path resolveQuarantineDir(Path root) {
      return null;
    }

    @Override
    protected long usableSpace(Path root) {
      return 1_000_000L;
    }

    @Override
    protected DiskWalk walkUploadRoot(Path root, Path quarantineDir) {
      return diskWalk;
    }

    @Override
    protected List<FileRow> fetchMetaFilePage(Long lastId, int limit) {
      if (lastId == null) {
        return page(0, limit);
      }
      int startIndex = -1;
      for (int i = 0; i < metaFileRows.size(); i++) {
        if (metaFileRows.get(i).id > lastId) {
          startIndex = i;
          break;
        }
      }
      return startIndex < 0 ? Collections.emptyList() : page(startIndex, limit);
    }

    private List<FileRow> page(int fromIndex, int limit) {
      int to = Math.min(metaFileRows.size(), fromIndex + limit);
      return new ArrayList<>(metaFileRows.subList(fromIndex, to));
    }

    @Override
    protected BatchResult checkReferences(List<Long> ids) {
      if (referenceFailure != null) {
        return new BatchResult(
            Collections.emptySet(), new LinkedHashSet<>(ids), 1, referenceFailure);
      }
      Set<Long> hit = new LinkedHashSet<>();
      for (Long id : ids) {
        if (referenced.contains(id)) {
          hit.add(id);
        }
      }
      return new BatchResult(hit, Collections.emptySet(), 1, null);
    }
  }

  private static FileRow row(long id, String path, long size) {
    return new FileRow(id, path, size);
  }

  @Test
  void referencedMetaFileIsNotOrphan() {
    Fake fake = new Fake();
    fake.metaFileRows.add(row(1L, "a.pdf", 100));
    fake.referenced.add(1L);
    fake.diskWalk = new DiskWalk(mapOf("a.pdf", 100L), true, 0, 0);

    OcrUploadStorageDiagnosticService.Result r = fake.scan();

    assertEquals(0L, r.getUnreferencedCount());
    assertEquals(0L, r.getUnreferencedBytes());
  }

  @Test
  void genuinelyUnreferencedRowIsCounted() {
    Fake fake = new Fake();
    fake.metaFileRows.add(row(1L, "a.pdf", 100));
    fake.metaFileRows.add(row(2L, "b.pdf", 200));
    fake.referenced.add(1L); // only row 1 is referenced
    fake.diskWalk = new DiskWalk(mapOf("a.pdf", 100L, "b.pdf", 200L), true, 0, 0);

    OcrUploadStorageDiagnosticService.Result r = fake.scan();

    assertEquals(1L, r.getUnreferencedCount());
    assertEquals(200L, r.getUnreferencedBytes());
  }

  @Test
  void strayAndMissingBlobsAreDistinguished() {
    Fake fake = new Fake();
    // Row "gone.pdf" has no matching disk entry -> missing.
    fake.metaFileRows.add(row(1L, "gone.pdf", 50));
    fake.referenced.add(1L);
    // Disk entry "stray.pdf" has no matching MetaFile row -> stray.
    fake.diskWalk = new DiskWalk(mapOf("stray.pdf", 77L), true, 0, 0);

    OcrUploadStorageDiagnosticService.Result r = fake.scan();

    assertEquals(1L, r.getMissingBlobCount());
    assertEquals(50L, r.getMissingBlobBytes());
    assertEquals(1L, r.getStrayBlobCount());
    assertEquals(77L, r.getStrayBlobBytes());
  }

  @Test
  void quarantineIsExcludedFromStray() {
    Fake fake = new Fake();
    // The disk walk itself must never put quarantined files into sizeByPath (that is asserted by
    // walkUploadRoot's own contract); here we assert the report surfaces them ONLY as quarantine,
    // never as stray, by keeping them out of the returned map and into the quarantine tally.
    fake.diskWalk = new DiskWalk(new HashMap<>(), true, 3L, 999L);

    OcrUploadStorageDiagnosticService.Result r = fake.scan();

    assertEquals(3L, r.getQuarantineFileCount());
    assertEquals(999L, r.getQuarantineBytes());
    assertEquals(0L, r.getStrayBlobCount());
  }

  @Test
  void failedReferenceQueryProducesUnknownNotZero() {
    Fake fake = new Fake();
    fake.metaFileRows.add(row(1L, "a.pdf", 100));
    fake.referenceFailure = "the relation X could not be queried";
    fake.diskWalk = new DiskWalk(mapOf("a.pdf", 100L), true, 0, 0);

    OcrUploadStorageDiagnosticService.Result r = fake.scan();

    assertNull(r.getUnreferencedCount(), "a failed reference check must never read as zero");
    assertNull(r.getUnreferencedBytes());
    assertEquals(OcrUploadStorageDiagnosticService.State.INCOMPLETE, r.getState());
  }

  @Test
  void capProducesIncompleteAndSuppressesPartialTotals() {
    Fake fake = new Fake();
    // The disk walk itself reports it was capped (as the real walkFileTree does at the entry
    // bound).
    fake.diskWalk = new DiskWalk(mapOf("a.pdf", 100L), false, 0, 0);
    fake.metaFileRows.add(row(1L, "a.pdf", 100));
    fake.referenced.add(1L);

    OcrUploadStorageDiagnosticService.Result r = fake.scan();

    assertEquals(OcrUploadStorageDiagnosticService.State.INCOMPLETE, r.getState());
    assertNull(r.getStrayBlobCount(), "a capped scan must never publish a partial stray total");
    assertNull(r.getMissingBlobCount(), "a capped scan must never publish a partial missing total");
  }

  @Test
  void unresolvableUploadRootFailsCleanly() {
    OcrUploadStorageDiagnosticService fake =
        new OcrUploadStorageDiagnosticService(null) {
          @Override
          protected Path resolveUploadRoot() {
            return null;
          }
        };

    OcrUploadStorageDiagnosticService.Result r = fake.scan();

    assertEquals(OcrUploadStorageDiagnosticService.State.FAILED, r.getState());
    assertNull(r.getUploadFileCount());
    assertTrue(r.getReason() != null && !r.getReason().isEmpty());
  }

  private static Map<String, Long> mapOf(Object... kv) {
    Map<String, Long> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], (Long) kv[i + 1]);
    }
    return m;
  }
}

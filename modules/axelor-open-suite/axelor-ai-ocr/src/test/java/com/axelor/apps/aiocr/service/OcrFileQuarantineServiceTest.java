package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.axelor.meta.db.MetaFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Filesystem-level tests for the quarantine mechanics (move-aside, missing-file behavior,
 * move-failure abort). Uses a real temp dir via test seams — no DB/Guice/static config.
 */
class OcrFileQuarantineServiceTest {

  /** A service wired to fixed test directories via the protected seams. */
  private static class TestQuarantineService extends OcrFileQuarantineService {
    private final Path quarantineDir;
    private final Path srcPath;
    boolean failMove;

    TestQuarantineService(Path quarantineDir, Path srcPath) {
      this.quarantineDir = quarantineDir;
      this.srcPath = srcPath;
    }

    @Override
    public Path quarantineDir() {
      try {
        Files.createDirectories(quarantineDir);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      return quarantineDir;
    }

    @Override
    protected Path resolveSourcePath(MetaFile metaFile) {
      return srcPath;
    }

    @Override
    protected void atomicMove(Path src, Path target) throws IOException {
      if (failMove) {
        throw new IOException("simulated move failure");
      }
      super.atomicMove(src, target);
    }
  }

  private static MetaFile metaFile() {
    MetaFile mf = mock(MetaFile.class);
    lenient().when(mf.getId()).thenReturn(1L);
    return mf;
  }

  @Test
  void quarantineMovesBlobAside(@TempDir Path dir) throws IOException {
    Path src = dir.resolve("upload/blob.pdf");
    Files.createDirectories(src.getParent());
    Files.writeString(src, "PDF");
    TestQuarantineService svc = new TestQuarantineService(dir.resolve("q"), src);

    Optional<QuarantinedFile> qf = svc.quarantine(metaFile());

    assertTrue(qf.isPresent());
    assertFalse(Files.exists(src), "source blob moved out");
    assertTrue(Files.exists(qf.get().getQuarantine()), "blob now in quarantine");
    assertEquals(src, qf.get().getOriginal());
  }

  @Test
  void quarantineMissingFileReturnsEmptyAndDoesNotThrow(@TempDir Path dir) {
    Path src = dir.resolve("upload/missing.pdf"); // never created
    TestQuarantineService svc = new TestQuarantineService(dir.resolve("q"), src);

    assertTrue(svc.quarantine(metaFile()).isEmpty());
  }

  @Test
  void quarantineMoveFailureThrowsAndLeavesSource(@TempDir Path dir) throws IOException {
    Path src = dir.resolve("upload/blob.pdf");
    Files.createDirectories(src.getParent());
    Files.writeString(src, "PDF");
    TestQuarantineService svc = new TestQuarantineService(dir.resolve("q"), src);
    svc.failMove = true;

    assertThrows(IllegalStateException.class, () -> svc.quarantine(metaFile()));
    assertTrue(Files.exists(src), "source blob untouched when move fails");
  }

  @Test
  void permanentlyDeleteRemovesQuarantinedBlob(@TempDir Path dir) throws IOException {
    Path q = dir.resolve("q/blob");
    Files.createDirectories(q.getParent());
    Files.writeString(q, "PDF");
    OcrFileQuarantineService svc = new OcrFileQuarantineService();

    svc.permanentlyDelete(new QuarantinedFile(dir.resolve("orig"), q, 1L));

    assertFalse(Files.exists(q));
  }

  @Test
  void restoreMovesBlobBackToOriginal(@TempDir Path dir) throws IOException {
    Path original = dir.resolve("upload/blob.pdf");
    Files.createDirectories(original.getParent());
    Path q = dir.resolve("q/blob");
    Files.createDirectories(q.getParent());
    Files.writeString(q, "PDF");
    OcrFileQuarantineService svc = new OcrFileQuarantineService();

    svc.restore(new QuarantinedFile(original, q, 1L));

    assertTrue(Files.exists(original), "blob restored to original path");
    assertFalse(Files.exists(q), "removed from quarantine");
  }

  @Test
  void sweepDeletesOnlyOldFiles(@TempDir Path dir) throws IOException {
    Path q = dir.resolve("q");
    Path oldFile = q.resolve("old");
    Path newFile = q.resolve("new");
    Files.createDirectories(q);
    Files.writeString(oldFile, "x");
    Files.writeString(newFile, "y");
    // Age the old file well beyond the default 24h retention.
    Files.setLastModifiedTime(oldFile, java.nio.file.attribute.FileTime.fromMillis(0L));
    OcrFileQuarantineService svc =
        new OcrFileQuarantineService() {
          @Override
          public Path quarantineDir() {
            return q;
          }
        };

    int deleted = svc.sweep(System.currentTimeMillis());

    assertEquals(1, deleted);
    assertFalse(Files.exists(oldFile));
    assertTrue(Files.exists(newFile));
  }
}

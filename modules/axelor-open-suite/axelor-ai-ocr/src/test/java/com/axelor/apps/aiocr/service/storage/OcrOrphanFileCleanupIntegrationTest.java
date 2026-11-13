package com.axelor.apps.aiocr.service.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.apps.aiocr.service.OcrUploadFileCleanupService;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * DB-backed proof of {@link OcrOrphanFileCleanupService} over the real Guice/JPA stack (mirrors the
 * pattern in {@link OcrSharedAttachmentIntegrationTest}): real {@code MetaFile} rows, real {@code
 * AiOcrUpload} holders, and real physical blobs under the test-only upload dir ({@code
 * axelor-config.properties}: {@code data.upload.dir}).
 */
class OcrOrphanFileCleanupIntegrationTest extends AiOcrTest {

  private static int seq = 0;

  private OcrOrphanFileCleanupService cleanupService() {
    return Beans.get(OcrOrphanFileCleanupService.class);
  }

  private long countById(String entityName, long id) {
    return JPA.em()
        .createQuery("SELECT COUNT(e) FROM " + entityName + " e WHERE e.id = :id", Long.class)
        .setParameter("id", id)
        .getSingleResult();
  }

  private MetaFile newBlob(String content) throws Exception {
    File tmp = File.createTempFile("ocr-orphan-it", ".pdf");
    Files.writeString(tmp.toPath(), content);
    return Beans.get(MetaFiles.class).upload(tmp);
  }

  /**
   * Proves points 1, 2 and 4: a truly unreferenced {@code MetaFile} is removed (row + blob) while a
   * referenced one is left alone, and a second run finds nothing left to do.
   */
  @Test
  void cleanupRemovesOnlyTheTrulyOrphanedFileAndSecondRunIsANoOp() {
    long[] orphanId = new long[1];
    long[] holderMetaFileId = new long[1];
    long[] holderUploadId = new long[1];
    Path[] orphanPath = new Path[1];
    Path[] holderPath = new Path[1];

    inTransaction(
        () -> {
          try {
            MetaFile orphan = newBlob("ORPHAN-" + (++seq));
            orphanId[0] = orphan.getId();
            orphanPath[0] = MetaFiles.getPath(orphan);

            MetaFile holderFile = newBlob("HOLDER-" + seq);
            holderMetaFileId[0] = holderFile.getId();
            holderPath[0] = MetaFiles.getPath(holderFile);

            AiOcrUpload holder = new AiOcrUpload();
            holder.setOriginalFile(holderFile);
            holder.setStatusSelect(AiOcrUploadStatus.COMPLETED);
            holderUploadId[0] = JPA.save(holder).getId();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    JPA.em().clear();

    assertTrue(Files.exists(orphanPath[0]), "fixture: orphan blob exists before cleanup");
    assertTrue(Files.exists(holderPath[0]), "fixture: holder blob exists before cleanup");

    OcrOrphanFileCleanupService.Result first = cleanupService().cleanup();

    assertFalse(first.isAborted(), "a complete fresh scan must not abort: " + first.getReason());
    assertEquals(1, first.getCandidateCount(), "only the orphan is a candidate");
    assertEquals(1, first.getDeletedCount());
    assertEquals(1, first.getBlobsReclaimedCount());
    assertEquals(0, first.getFailureCount());
    assertEquals(0, first.getSkippedCount());

    JPA.em().clear();
    assertEquals(0, countById("MetaFile", orphanId[0]), "the truly unreferenced row is gone");
    assertFalse(Files.exists(orphanPath[0]), "and its blob is permanently removed after commit");

    assertEquals(
        1, countById("MetaFile", holderMetaFileId[0]), "the referenced file's row survives");
    assertTrue(Files.exists(holderPath[0]), "and its blob survives");
    assertEquals(1, countById("AiOcrUpload", holderUploadId[0]));
    assertEquals(
        holderMetaFileId[0],
        Beans.get(AiOcrUploadRepository.class).find(holderUploadId[0]).getOriginalFile().getId(),
        "the holder still points at its own file");

    // Idempotent: the orphan is already gone and the holder is still referenced -> nothing to do.
    OcrOrphanFileCleanupService.Result second = cleanupService().cleanup();

    assertFalse(second.isAborted());
    assertEquals(0, second.getCandidateCount(), "second run finds no candidates");
    assertEquals(0, second.getDeletedCount());
  }

  /**
   * Proves point 3, driven through the real transaction seam: {@link OcrOrphanFileCleanupService}
   * deletes a candidate by delegating to {@link
   * OcrUploadFileCleanupService#quarantineIfUnreferenced(MetaFile)} — the same primitive exercised
   * here directly, forcing a rollback right after quarantine so the {@code
   * OcrFileTransactionSynchronization} restore path is the thing under test.
   */
  @Test
  void rollbackAfterQuarantineRestoresTheRowAndTheBlob() {
    long[] fileId = new long[1];
    Path[] blobPath = new Path[1];

    inTransaction(
        () -> {
          try {
            MetaFile orphan = newBlob("ROLLBACK-ORPHAN-" + (++seq));
            fileId[0] = orphan.getId();
            blobPath[0] = MetaFiles.getPath(orphan);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    JPA.em().clear();

    assertThrows(
        RuntimeException.class,
        () ->
            inTransaction(
                () -> {
                  MetaFile mf = JPA.em().find(MetaFile.class, fileId[0]);
                  boolean removed =
                      Beans.get(OcrUploadFileCleanupService.class).quarantineIfUnreferenced(mf);
                  assertTrue(removed, "fixture: the file really is unreferenced");
                  assertFalse(
                      Files.exists(blobPath[0]),
                      "quarantined (moved aside), not deleted, during the transaction");
                  throw new IllegalStateException("forced rollback after quarantine");
                }));
    JPA.em().clear();

    assertEquals(1, countById("MetaFile", fileId[0]), "the row is restored by the rollback");
    assertTrue(Files.exists(blobPath[0]), "and the blob is restored from quarantine");
  }
}

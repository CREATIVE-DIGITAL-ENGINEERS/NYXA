package com.axelor.apps.aiocr.service.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.aiocr.service.process.AiOcrCarrierService;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Regression: an archive whose every entry is already terminal on creation must still finish.
 *
 * <p>A ZIP entry whose content hash is already imported is created directly as a {@code duplicate}
 * child — terminal, with no file of its own and nothing left to process. No worker ever runs for
 * it, so the ONLY carrier rollup that will ever happen for such an archive is the one extraction
 * does itself. That makes write ordering decisive here: the rollup writes {@code archive} + 100%
 * with a bulk UPDATE, and an entity save of the carrier after it (an entity loaded before the
 * rollup; Axelor entities are not {@code @DynamicUpdate}) would write every column back — reverting
 * the status to {@code extracting} and the percent to null, for good.
 *
 * <p>Deliberately drives the real {@link AiOcrArchiveExtractService#extract} rather than the rollup
 * alone: what matters is not how a status is computed, only what writes last. No OCR runs here — a
 * duplicate child is terminal before any extractor is consulted — so this never calls Azure.
 */
class AiOcrArchiveDuplicateOnlyCarrierIntegrationTest extends AiOcrTest {

  private AiOcrUploadRepository uploads() {
    return Beans.get(AiOcrUploadRepository.class);
  }

  @Test
  void anAllDuplicateArchiveEndsArchivedAtHundredPercent() {
    inTransaction(
        () -> {
          try {
            byte[] content = "already-imported document bytes".getBytes("UTF-8");
            String hash = AiOcrFileHash.sha256(content);

            // The prior import that makes the ZIP entry a duplicate (same hash, same — absent —
            // company).
            AiOcrUpload prior = new AiOcrUpload();
            prior.setFileName("earlier.pdf");
            prior.setFileHash(hash);
            prior.setStatusSelect(AiOcrUploadStatus.COMPLETED);
            JPA.save(prior);

            MetaFile archive =
                Beans.get(MetaFiles.class).upload(zipOf("invoice.pdf", content).toFile());
            AiOcrUpload carrier = new AiOcrUpload();
            carrier.setFileName("all-duplicates.zip");
            carrier.setArchiveFile(archive);
            carrier.setStatusSelect(AiOcrUploadStatus.UPLOADED);
            carrier = JPA.save(carrier);
            Long carrierId = carrier.getId();

            Beans.get(AiOcrArchiveExtractService.class).extract(carrierId);

            // recompute() persists via bulk JPQL UPDATEs; clear the session so the assertions below
            // read what is actually in the database rather than a cached instance.
            JPA.em().clear();

            List<AiOcrUpload> children =
                uploads().all().filter("self.importArchive.id = :c").bind("c", carrierId).fetch();
            assertEquals(1, children.size(), "one entry in, one child out");
            assertEquals(
                AiOcrUploadStatus.DUPLICATE,
                children.get(0).getStatusSelect(),
                "the entry was already imported, so its child is terminal on creation");

            AiOcrUpload after = uploads().find(carrierId);
            assertNotEquals(
                AiOcrUploadStatus.EXTRACTING,
                after.getStatusSelect(),
                "the carrier must not be left mid-extraction once extraction has finished");
            assertEquals(
                AiOcrUploadStatus.ARCHIVE,
                after.getStatusSelect(),
                "every child terminal and none problematic: a duplicate-only ZIP is a clean archive");
            assertNotNull(after.getProgressPercent(), "the rollup owns the percent");
            assertEquals(100, after.getProgressPercent().intValue());

            // The ZIP itself is correctly KEPT (a duplicate child holds no file of its own, so the
            // reclaim cannot prove the entry is stored anywhere else) — and it is that retention
            // branch's own carrier save that must not revert the rollup.
            assertNotNull(after.getArchiveFile(), "reclaim keeps a duplicate-only ZIP");

            // A ZIP carrier never carries a content hash of its own: it is extracted, never OCR'd,
            // so it never passes the byte-identical guard that assigns one. That is why
            // re-uploading
            // a ZIP can only ever match its own carrier row — there is nothing to disambiguate, and
            // the structural row cannot be chosen over a document by accident.
            assertNull(after.getFileHash(), "a ZIP carrier holds no fileHash");
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
  }

  @Test
  void aSaveOfAStaleCarrierCopyCannotRevertTheRollup() {
    inTransaction(
        () -> {
          AiOcrUpload carrier = new AiOcrUpload();
          carrier.setFileName("carrier.zip");
          carrier.setStatusSelect(AiOcrUploadStatus.EXTRACTING);
          carrier.setExtractionFinishedOn(java.time.LocalDateTime.now());
          carrier = JPA.save(carrier);

          AiOcrUpload child = new AiOcrUpload();
          child.setImportArchive(carrier);
          child.setStatusSelect(AiOcrUploadStatus.DUPLICATE);
          JPA.save(child);

          Beans.get(AiOcrCarrierService.class).recompute(carrier.getId());

          // Exactly what reclaimArchiveIfFullyExtracted does next: append the retention note to the
          // carrier instance loaded BEFORE the rollup, and save it. Without the refresh inside
          // recompute this full-column write puts 'extracting' and a null percent straight back.
          carrier.setExtractionInformation("Original archive kept: ...");
          JPA.save(carrier);
          JPA.em().clear();

          AiOcrUpload after = uploads().find(carrier.getId());
          assertEquals(
              AiOcrUploadStatus.ARCHIVE,
              after.getStatusSelect(),
              "the rollup is authoritative; a later entity save must carry it, not revert it");
          assertNotNull(after.getProgressPercent());
          assertEquals(100, after.getProgressPercent().intValue());
          assertNotNull(after.getExtractionInformation(), "and the note still lands");
        });
  }

  /** A one-entry ZIP written to a temp file. */
  private static Path zipOf(String entryName, byte[] content) throws IOException {
    Path zip = Files.createTempFile("aiocr-dup-", ".zip");
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
      out.putNextEntry(new ZipEntry(entryName));
      out.write(content);
      out.closeEntry();
    }
    return zip;
  }
}

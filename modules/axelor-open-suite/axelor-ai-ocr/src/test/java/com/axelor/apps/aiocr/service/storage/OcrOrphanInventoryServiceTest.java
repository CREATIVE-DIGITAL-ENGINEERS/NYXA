package com.axelor.apps.aiocr.service.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.storage.OcrMetaFileReferenceScanner.FileReferences;
import com.axelor.apps.aiocr.service.storage.OcrMetaFileReferenceScanner.Relation;
import com.axelor.apps.aiocr.service.storage.OcrOrphanInventoryService.Candidate;
import com.axelor.apps.aiocr.service.storage.OcrOrphanInventoryService.DownloadAuthorization;
import com.axelor.apps.aiocr.service.storage.OcrOrphanInventoryService.Inventory;
import com.axelor.apps.aiocr.service.storage.OcrUploadStorageDiagnosticService.Result;
import com.axelor.apps.aiocr.service.storage.OcrUploadStorageDiagnosticService.ScanOutcome;
import com.axelor.apps.aiocr.service.storage.OcrUploadStorageDiagnosticService.State;
import com.axelor.apps.aiocr.service.storage.OcrUploadStorageDiagnosticService.UnreferencedFile;
import com.axelor.meta.db.MetaFile;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure tests for {@link OcrOrphanInventoryService}'s candidate-selection and download-authorization
 * logic: every I/O seam is stubbed, so these exercise only the read-only reporting and re-check
 * rules — no database, no filesystem. Candidate selection must come from the exact same {@link
 * OcrUploadStorageDiagnosticService#computeScan()} outcome shape the cleanup service consumes.
 */
class OcrOrphanInventoryServiceTest {

  private static final class Fake extends OcrOrphanInventoryService {
    ScanOutcome outcome;
    Path root = Path.of("root");
    Map<Long, MetaFile> files = new HashMap<>();
    Map<Long, Path> paths = new HashMap<>();
    Set<Path> regularFiles = new HashSet<>();
    Map<Path, Long> sizes = new HashMap<>();
    Map<Long, FileReferences> refs = new HashMap<>();

    Fake() {
      super(null, null, null);
    }

    @Override
    protected ScanOutcome computeScan() {
      return outcome;
    }

    @Override
    protected Path resolveUploadRoot() {
      return root;
    }

    @Override
    protected MetaFile findMetaFile(Long id) {
      return files.get(id);
    }

    @Override
    protected FileReferences checkReferences(MetaFile mf) {
      return refs.get(mf.getId());
    }

    @Override
    protected Path resolvePath(MetaFile mf) {
      return paths.get(mf.getId());
    }

    @Override
    protected boolean isRegularFile(Path path) {
      return regularFiles.contains(path);
    }

    @Override
    protected Long fileSize(Path path) {
      return sizes.get(path);
    }
  }

  /**
   * {@code createdOn}/{@code createdBy} have private setters on {@code AuditableModel} (only
   * Hibernate's auditing listener sets them), so a pure unit test cannot populate them. The
   * pass-through of those two fields in {@link OcrOrphanInventoryService#list()} is a one-line
   * getter call each, so this is left untested here rather than reached for via reflection.
   */
  private static MetaFile metaFile(long id, String name, String type, String path, long size) {
    MetaFile mf = new MetaFile();
    mf.setId(id);
    mf.setFileName(name);
    mf.setFileType(type);
    mf.setFilePath(path);
    mf.setFileSize(size);
    return mf;
  }

  private static ScanOutcome outcomeWith(State state, String reason, List<UnreferencedFile> rows) {
    Result result =
        new Result(state, reason, 0L, 0L, 0L, 0L, true, 0L, 0L, "n/a", 0L, 0L, 0L, 0L, 0L, 0L);
    return new ScanOutcome(result, rows);
  }

  private static UnreferencedFile unref(long id, long size, boolean blobMissing) {
    return new UnreferencedFile(id, size, blobMissing);
  }

  private static FileReferences unreferenced() {
    return new FileReferences(Set.of(), Set.of(), null);
  }

  private static FileReferences referenced() {
    Set<Relation> other = Set.of(new Relation(MetaFile.class, "dummy", false));
    return new FileReferences(Set.of(), other, null);
  }

  // --- list() ------------------------------------------------------------------------------------

  @Test
  void incompleteScanShowsNoCandidates() {
    Fake fake = new Fake();
    fake.outcome = outcomeWith(State.INCOMPLETE, "capped", List.of());

    Inventory inventory = fake.list();

    assertFalse(inventory.isAvailable());
    assertTrue(inventory.getCandidates().isEmpty());
    assertEquals("capped", inventory.getReason());
  }

  @Test
  void completeScanReportsAllFieldsForAHealthyCandidate() {
    Fake fake = new Fake();
    MetaFile mf = metaFile(1L, "a.pdf", "application/pdf", "orphans/a.pdf", 100L);
    fake.files.put(1L, mf);
    Path p = fake.root.resolve("orphans/a.pdf");
    fake.paths.put(1L, p);
    fake.regularFiles.add(p);
    fake.sizes.put(p, 100L);
    fake.outcome = outcomeWith(State.COMPLETE, null, List.of(unref(1L, 100L, false)));

    Inventory inventory = fake.list();

    assertTrue(inventory.isAvailable());
    assertEquals(1, inventory.getCandidates().size());
    Candidate c = inventory.getCandidates().get(0);
    assertEquals(1L, c.getId());
    assertEquals("a.pdf", c.getFileName());
    assertEquals("application/pdf", c.getFileType());
    assertEquals("orphans/a.pdf", c.getFilePath());
    assertEquals(100L, c.getRecordedSize());
    assertEquals(100L, c.getActualSize());
    assertTrue(c.isBlobExists());
    assertTrue(c.isDownloadable());
  }

  @Test
  void missingBlobIsReportedNotDownloadable() {
    Fake fake = new Fake();
    MetaFile mf = metaFile(2L, "b.pdf", "application/pdf", "orphans/b.pdf", 50L);
    fake.files.put(2L, mf);
    fake.paths.put(2L, fake.root.resolve("orphans/b.pdf")); // never marked regular -> missing
    fake.outcome = outcomeWith(State.COMPLETE, null, List.of(unref(2L, 50L, true)));

    Inventory inventory = fake.list();

    Candidate c = inventory.getCandidates().get(0);
    assertFalse(c.isBlobExists());
    assertNull(c.getActualSize());
    assertFalse(c.isDownloadable());
  }

  @Test
  void pathOutsideUploadRootIsNeverDownloadableEvenIfTheBlobExists() {
    Fake fake = new Fake();
    MetaFile mf = metaFile(3L, "c.pdf", "application/pdf", "../../etc/c.pdf", 10L);
    fake.files.put(3L, mf);
    Path p = Path.of("elsewhere/c.pdf"); // NOT under fake.root
    fake.paths.put(3L, p);
    fake.regularFiles.add(p);
    fake.sizes.put(p, 10L);
    fake.outcome = outcomeWith(State.COMPLETE, null, List.of(unref(3L, 10L, false)));

    Inventory inventory = fake.list();

    Candidate c = inventory.getCandidates().get(0);
    assertTrue(c.isBlobExists());
    assertFalse(c.isDownloadable(), "outside the upload root must never be reported downloadable");
  }

  @Test
  void candidateAlreadyReclaimedSinceTheScanIsSilentlyOmitted() {
    Fake fake = new Fake();
    // No MetaFile registered for id 7 -> findMetaFile returns null.
    fake.outcome = outcomeWith(State.COMPLETE, null, List.of(unref(7L, 10L, false)));

    Inventory inventory = fake.list();

    assertTrue(inventory.isAvailable());
    assertTrue(inventory.getCandidates().isEmpty());
  }

  // --- authorizeDownload()
  // ------------------------------------------------------------------------

  @Test
  void authorizeDownloadDeniesAReferencedFile() {
    Fake fake = new Fake();
    MetaFile mf = metaFile(4L, "d.pdf", "application/pdf", "orphans/d.pdf", 10L);
    fake.files.put(4L, mf);
    fake.refs.put(4L, referenced());

    DownloadAuthorization auth = fake.authorizeDownload(4L);

    assertFalse(auth.isAllowed());
  }

  @Test
  void authorizeDownloadDeniesOutsideUploadRoot() {
    Fake fake = new Fake();
    MetaFile mf = metaFile(5L, "e.pdf", "application/pdf", "e.pdf", 10L);
    fake.files.put(5L, mf);
    fake.refs.put(5L, unreferenced());
    Path p = Path.of("elsewhere/e.pdf");
    fake.paths.put(5L, p);
    fake.regularFiles.add(p);

    DownloadAuthorization auth = fake.authorizeDownload(5L);

    assertFalse(auth.isAllowed());
  }

  @Test
  void authorizeDownloadDeniesMissingBlob() {
    Fake fake = new Fake();
    MetaFile mf = metaFile(8L, "h.pdf", "application/pdf", "orphans/h.pdf", 10L);
    fake.files.put(8L, mf);
    fake.refs.put(8L, unreferenced());
    fake.paths.put(8L, fake.root.resolve("orphans/h.pdf")); // never marked regular

    DownloadAuthorization auth = fake.authorizeDownload(8L);

    assertFalse(auth.isAllowed());
  }

  @Test
  void authorizeDownloadAllowsAProvenUnreferencedFileUnderRoot() {
    Fake fake = new Fake();
    MetaFile mf = metaFile(6L, "f.pdf", "application/pdf", "orphans/f.pdf", 10L);
    fake.files.put(6L, mf);
    fake.refs.put(6L, unreferenced());
    Path p = fake.root.resolve("orphans/f.pdf");
    fake.paths.put(6L, p);
    fake.regularFiles.add(p);

    DownloadAuthorization auth = fake.authorizeDownload(6L);

    assertTrue(auth.isAllowed());
    assertEquals(mf, auth.getMetaFile());
  }

  @Test
  void authorizeDownloadDeniesUnknownId() {
    Fake fake = new Fake();

    DownloadAuthorization auth = fake.authorizeDownload(999L);

    assertFalse(auth.isAllowed());
  }

  @Test
  void authorizeDownloadDeniesNullId() {
    Fake fake = new Fake();

    DownloadAuthorization auth = fake.authorizeDownload(null);

    assertFalse(auth.isAllowed());
  }
}

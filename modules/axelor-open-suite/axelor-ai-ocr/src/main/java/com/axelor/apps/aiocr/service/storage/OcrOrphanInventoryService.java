package com.axelor.apps.aiocr.service.storage;

import com.axelor.apps.aiocr.service.storage.OcrMetaFileReferenceScanner.FileReferences;
import com.axelor.apps.aiocr.service.storage.OcrUploadStorageDiagnosticService.ScanOutcome;
import com.axelor.apps.aiocr.service.storage.OcrUploadStorageDiagnosticService.State;
import com.axelor.apps.aiocr.service.storage.OcrUploadStorageDiagnosticService.UnreferencedFile;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.db.repo.MetaFileRepository;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Temporary, STRICTLY READ-ONLY inventory of the orphan candidates {@link
 * OcrOrphanFileCleanupService} would act on — for an admin to inspect on a hosted environment with
 * no Azure/File Share access, before approving the destructive cleanup.
 *
 * <p>Deliberately shares its candidate definition with the cleanup service rather than inventing a
 * separate or weaker one: both call the exact same {@link
 * OcrUploadStorageDiagnosticService#computeScan()}, which runs a fresh, bounded, fully-proven scan
 * every time — never an earlier Storage check, never a partial result. If the scan is not {@link
 * State#COMPLETE}, no candidate list is produced — see {@link Inventory#isAvailable()}.
 *
 * <p>This class never deletes, moves, writes or creates anything: no SQL DELETE, no {@code
 * Files.delete/move/write}, no repository save/remove, no quarantine call. It only reads MetaFile
 * rows and stats/reads existing files to report on them, and answers whether one specific,
 * re-proven-unreferenced candidate may be handed to the platform's own MetaFile download endpoint
 * (see {@link #authorizeDownload(Long)}) — it never streams bytes itself.
 */
@Singleton
public class OcrOrphanInventoryService {

  private static final Logger LOG = LoggerFactory.getLogger(OcrOrphanInventoryService.class);

  protected final OcrUploadStorageDiagnosticService diagnosticService;
  protected final OcrMetaFileReferenceScanner scanner;
  protected final MetaFileRepository metaFileRepo;

  @Inject
  public OcrOrphanInventoryService(
      OcrUploadStorageDiagnosticService diagnosticService,
      OcrMetaFileReferenceScanner scanner,
      MetaFileRepository metaFileRepo) {
    this.diagnosticService = diagnosticService;
    this.scanner = scanner;
    this.metaFileRepo = metaFileRepo;
  }

  /** One candidate row: display fields only, never an absolute server path. */
  public static final class Candidate {
    private final Long id;
    private final String fileName;
    private final String fileType;
    private final String filePath;
    private final Long recordedSize;
    private final Long actualSize;
    private final boolean blobExists;
    private final boolean downloadable;
    private final LocalDateTime createdOn;
    private final String createdBy;

    Candidate(
        Long id,
        String fileName,
        String fileType,
        String filePath,
        Long recordedSize,
        Long actualSize,
        boolean blobExists,
        boolean downloadable,
        LocalDateTime createdOn,
        String createdBy) {
      this.id = id;
      this.fileName = fileName;
      this.fileType = fileType;
      this.filePath = filePath;
      this.recordedSize = recordedSize;
      this.actualSize = actualSize;
      this.blobExists = blobExists;
      this.downloadable = downloadable;
      this.createdOn = createdOn;
      this.createdBy = createdBy;
    }

    public Long getId() {
      return id;
    }

    public String getFileName() {
      return fileName;
    }

    public String getFileType() {
      return fileType;
    }

    public String getFilePath() {
      return filePath;
    }

    public Long getRecordedSize() {
      return recordedSize;
    }

    public Long getActualSize() {
      return actualSize;
    }

    public boolean isBlobExists() {
      return blobExists;
    }

    public boolean isDownloadable() {
      return downloadable;
    }

    public LocalDateTime getCreatedOn() {
      return createdOn;
    }

    public String getCreatedBy() {
      return createdBy;
    }
  }

  /** The full listing, or the reason none is shown. */
  public static final class Inventory {
    private final boolean available;
    private final String reason;
    private final List<Candidate> candidates;

    private Inventory(boolean available, String reason, List<Candidate> candidates) {
      this.available = available;
      this.reason = reason;
      this.candidates = candidates;
    }

    static Inventory unavailable(String reason) {
      return new Inventory(false, reason, Collections.emptyList());
    }

    static Inventory available(List<Candidate> candidates) {
      return new Inventory(true, null, candidates);
    }

    public boolean isAvailable() {
      return available;
    }

    public String getReason() {
      return reason;
    }

    public List<Candidate> getCandidates() {
      return candidates;
    }
  }

  /**
   * The exact candidate set {@link OcrOrphanFileCleanupService} would act on, from one fresh scan —
   * nothing withheld when complete, nothing shown when not.
   */
  public Inventory list() {
    ScanOutcome outcome = computeScan();
    if (outcome.result.getState() != State.COMPLETE) {
      String why =
          outcome.result.getReason() != null
              ? outcome.result.getReason()
              : "the scan did not complete";
      return Inventory.unavailable(why);
    }
    Path root = resolveUploadRoot();
    List<Candidate> out = new ArrayList<>(outcome.unreferencedFiles.size());
    for (UnreferencedFile u : outcome.unreferencedFiles) {
      MetaFile mf = findMetaFile(u.getId());
      if (mf == null) {
        continue; // reclaimed since the scan a moment ago — nothing to show
      }
      Path resolved = resolvePath(mf);
      boolean blobExists = resolved != null && isRegularFile(resolved);
      Long actualSize = blobExists ? fileSize(resolved) : null;
      boolean underRoot = root != null && resolved != null && isUnder(root, resolved);
      boolean downloadable = blobExists && underRoot;
      out.add(
          new Candidate(
              mf.getId(),
              mf.getFileName(),
              mf.getFileType(),
              mf.getFilePath(),
              mf.getFileSize(),
              actualSize,
              blobExists,
              downloadable,
              mf.getCreatedOn(),
              mf.getCreatedBy() != null ? mf.getCreatedBy().getCode() : null));
    }
    return Inventory.available(out);
  }

  /** Whether one candidate may be handed to the platform's own MetaFile download endpoint. */
  public static final class DownloadAuthorization {
    private final boolean allowed;
    private final String reason;
    private final MetaFile metaFile;

    private DownloadAuthorization(boolean allowed, String reason, MetaFile metaFile) {
      this.allowed = allowed;
      this.reason = reason;
      this.metaFile = metaFile;
    }

    static DownloadAuthorization denied(String reason) {
      return new DownloadAuthorization(false, reason, null);
    }

    static DownloadAuthorization allowed(MetaFile metaFile) {
      return new DownloadAuthorization(true, null, metaFile);
    }

    public boolean isAllowed() {
      return allowed;
    }

    public String getReason() {
      return reason;
    }

    public MetaFile getMetaFile() {
      return metaFile;
    }
  }

  /**
   * Re-proves, right now, that {@code metaFileId} is still an unreferenced MetaFile whose blob is a
   * regular file under the configured upload root — never trusting the inventory listing (which may
   * be stale by the time the admin clicks Download). Accepts only an id: the caller can never hand
   * this an arbitrary filesystem path. The admin check itself is the controller's job (this class
   * has no notion of "admin"), applied before this is ever called.
   */
  public DownloadAuthorization authorizeDownload(Long metaFileId) {
    if (metaFileId == null) {
      return DownloadAuthorization.denied("No MetaFile id was supplied.");
    }
    MetaFile mf = findMetaFile(metaFileId);
    if (mf == null) {
      return DownloadAuthorization.denied("No MetaFile with that id.");
    }
    FileReferences refs = checkReferences(mf);
    if (!refs.isUnreferenced()) {
      return DownloadAuthorization.denied(
          "This file is no longer a proven orphan (referenced, or the reference check is"
              + " unprovable) — refusing to download.");
    }
    Path root = resolveUploadRoot();
    if (root == null) {
      return DownloadAuthorization.denied("The upload directory could not be resolved.");
    }
    Path resolved = resolvePath(mf);
    if (resolved == null || !isUnder(root, resolved)) {
      return DownloadAuthorization.denied(
          "The resolved file path is not under the configured upload root.");
    }
    if (!isRegularFile(resolved)) {
      return DownloadAuthorization.denied("The physical file does not exist.");
    }
    return DownloadAuthorization.allowed(mf);
  }

  // ---------------------------------------------------------------------------------------------
  // Seams — overridable so a test can drive the logic above without a database or filesystem.
  // ---------------------------------------------------------------------------------------------

  protected ScanOutcome computeScan() {
    return diagnosticService.computeScan();
  }

  protected Path resolveUploadRoot() {
    return diagnosticService.resolveUploadRoot();
  }

  protected MetaFile findMetaFile(Long id) {
    return metaFileRepo.find(id);
  }

  protected FileReferences checkReferences(MetaFile mf) {
    return scanner.referencesOf(mf);
  }

  protected Path resolvePath(MetaFile mf) {
    try {
      return MetaFiles.getPath(mf);
    } catch (RuntimeException e) {
      LOG.warn("Could not resolve the path for MetaFile #{}: {}", mf.getId(), e.getMessage());
      return null;
    }
  }

  protected boolean isRegularFile(Path path) {
    return Files.isRegularFile(path);
  }

  protected Long fileSize(Path path) {
    try {
      return Files.size(path);
    } catch (IOException e) {
      return null;
    }
  }

  protected boolean isUnder(Path root, Path candidate) {
    return candidate.normalize().startsWith(root.normalize());
  }
}

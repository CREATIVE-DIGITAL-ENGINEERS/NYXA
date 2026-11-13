package com.axelor.apps.aiocr.service.ingest;

import com.axelor.app.AppSettings;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.OcrUploadFileCleanupService;
import com.axelor.apps.aiocr.service.audit.AiOcrUploadAuditService;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.aiocr.service.process.AiOcrCarrierService;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Expands a ZIP carrier into one child {@link AiOcrUpload} per contained entry. Runs in the
 * background (extraction pool), never in the HTTP request. Restart-safe and idempotent: each child
 * has a deterministic {@code extractionKey} unique within the carrier, so a crash mid-extraction is
 * recovered by re-running from the preserved original ZIP and upsert/skipping already-created
 * children (never deleting or duplicating). The carrier row is kept as the per-ZIP progress record.
 *
 * <p>Outcomes per entry: supported+new → {@code queued}; supported+duplicate (company+hash) →
 * {@code duplicate}; unsupported regular file → {@code unsupported}; corrupt nested ZIP → {@code
 * failed} (extraction of other entries continues). A top-level ZIP that can't be read before any
 * usable entry → carrier {@code failed}. A controlled stop (disk reserve / a ZIP guard) always sets
 * {@code extractionFinishedOn} and leaves already-created children intact.
 */
@Singleton
public class AiOcrArchiveExtractService {

  private static final Logger LOG = LoggerFactory.getLogger(AiOcrArchiveExtractService.class);

  protected final AiOcrUploadRepository uploadRepo;
  protected final AiOcrCarrierService carrierService;

  @Inject
  public AiOcrArchiveExtractService(
      AiOcrUploadRepository uploadRepo, AiOcrCarrierService carrierService) {
    this.uploadRepo = uploadRepo;
    this.carrierService = carrierService;
  }

  /**
   * Extract the carrier's archive into child records (idempotent). Safe to re-run after a crash.
   *
   * <p>The child creation runs inside an explicit <b>system context</b> scoped to the carrier's
   * company. Today this always executes on the extraction pool (no authenticated subject), so the
   * seam changes nothing; it is declared anyway because the invariant it protects is load-bearing —
   * {@code AiOcrUploadManagementRepository} treats any insert by an authenticated caller as a
   * manual upload and forces it to {@code uploaded}, and an {@code uploaded} child is in neither
   * the terminal nor the problematic list of {@code AiOcrCarrierService.recompute}, so it would
   * stall its carrier at {@code processing} forever. With the seam, a future synchronous caller
   * cannot silently reclassify these children.
   */
  public void extract(Long carrierId) {
    AiOcrUpload carrier = uploadRepo.find(carrierId);
    if (carrier == null || carrier.getArchiveFile() == null) {
      return;
    }
    Path zipPath = MetaFiles.getPath(carrier.getArchiveFile());
    if (zipPath == null || !Files.exists(zipPath)) {
      // The original ZIP is gone — cannot (re)extract. Fail the carrier with a clear message.
      finishCarrier(carrierId, "The uploaded archive could not be found in storage.");
      return;
    }
    Long companyId = carrier.getCompany() != null ? carrier.getCompany().getId() : null;
    Long userId = carrier.getUploadedBy() != null ? carrier.getUploadedBy().getId() : null;
    if (AiExecutionContext.isSystem()) {
      doExtract(carrierId, companyId, userId, zipPath);
    } else {
      AiExecutionContext.runAsSystem(
          companyId, "ocr-archive-extract", () -> doExtract(carrierId, companyId, userId, zipPath));
    }
  }

  protected void doExtract(Long carrierId, Long companyId, Long userId, Path zipPath) {
    beginExtraction(carrierId);

    // Accounts for EVERY entry the extractor reports, so the reclaim decision below can be proven
    // rather than assumed. See AiOcrArchiveReclaimPolicy for why each category matters.
    final AiOcrArchiveReclaimPolicy.Tally tally = new AiOcrArchiveReclaimPolicy.Tally();

    SafeZipExtractor.EntryHandler handler =
        new SafeZipExtractor.EntryHandler() {
          @Override
          public void onDocument(String path, Path tempFile) throws IOException {
            String hash = AiOcrFileHash.sha256(tempFile);
            upsertDocument(carrierId, companyId, userId, path, hash, tempFile);
            tally.recordDocument();
          }

          @Override
          public void onUnsupported(String path) {
            // A non-PDF/image regular file (e.g. .xlsx, .docx) is simply skipped — no record is
            // created. It never reaches OCR. Only PDF/PNG/JPG/JPEG/TIFF documents become records.
            // Unchanged behaviour, but now TALLIED: because these bytes are stored nowhere else,
            // the archive is their only copy and must not be reclaimed.
            tally.recordUnsupported(path);
          }

          @Override
          public void onCorruptNested(String path, String reason) {
            upsertOutcome(
                carrierId,
                companyId,
                userId,
                path,
                keyFor(path, "corrupt"),
                AiOcrUploadStatus.FAILED,
                reason);
            tally.recordCorruptNested(path);
          }
        };

    try {
      SafeZipExtractor.extract(zipPath, resolveLimits(), handler);
      markExtractionFinished(carrierId, null);
    } catch (SafeZipExtractor.ArchiveRejectedException | IOException e) {
      // Controlled stop. If children already exist, keep them (completedWithIssues); else the
      // top-level archive was unreadable before anything usable → carrier failed.
      tally.recordStop(e.getMessage());
      markExtractionFinished(carrierId, "Extraction stopped: " + e.getMessage());
    }

    // Always consulted: on a clean pass it reclaims the ZIP, otherwise it records why the ZIP was
    // kept. Never throws — a reclaim problem must not turn a successful extraction into a failure.
    try {
      reclaimArchiveIfFullyExtracted(carrierId, tally);
    } catch (RuntimeException e) {
      LOG.warn(
          "Could not evaluate archive reclaim for carrier {}; the original archive is kept: {}",
          carrierId,
          e.getMessage(),
          e);
    }

    // The rollup is the LAST write of this extraction, deliberately — and there is exactly one of
    // it, which is why the two calls above only stamp extractionFinishedOn / the reclaim note.
    // Both of those save the carrier ENTITY, and an entity save after the rollup would write the
    // whole row back from a copy loaded before it (Axelor entities are not @DynamicUpdate),
    // reverting status and percent. A ZIP whose children are all created already-terminal (every
    // entry a duplicate) has nothing left to process afterwards, so no worker would ever recompute
    // it again and it would stay 'extracting' with a null percent for good.
    // AiOcrCarrierService.recompute additionally refreshes the row it updates, so the invariant
    // survives even if a future caller adds a save after this point.
    carrierService.recompute(carrierId);
  }

  /**
   * Drop the carrier's original ZIP once every entry has been written out as its own durable child
   * file, keeping the carrier row, its file name, its hashes and its audit trail.
   *
   * <p>Why this is safe: each child holds its own {@code originalFile}, so it stays viewable,
   * re-processable and re-tryable without the archive; the children's {@code fileHash} values (not
   * the ZIP) are what deduplication reads; and {@link #extract} already treats a carrier with no
   * {@code archiveFile} as a no-op, so a later re-extraction neither fails nor loses anything.
   *
   * <p>Why it matters: the ZIP doubles the bytes an archive import costs, and a carrier with a
   * ventilated child can never be deleted — so without this the duplicate copy is pinned for the
   * life of the database.
   *
   * <p>Idempotent. A carrier whose archive is already gone returns immediately, and the decision
   * itself is re-derived from committed database state, so repeated completions and retries agree.
   * The blob is only ever <em>quarantined</em> here: {@link
   * com.axelor.apps.aiocr.service.OcrUploadFileCleanupService#quarantineIfUnreferenced} moves it
   * aside inside the transaction and the post-commit synchronization deletes it (restoring it on
   * rollback), and it refuses outright if anything else still references the file.
   */
  protected void reclaimArchiveIfFullyExtracted(
      Long carrierId, AiOcrArchiveReclaimPolicy.Tally tally) {
    // Joins the caller's transaction when there is one and opens its own otherwise, so the
    // synchronization registered below is always attached to a live transaction.
    JPA.runInTransaction(
        () -> {
          AiOcrUpload carrier = uploadRepo.find(carrierId);
          if (carrier == null) {
            return;
          }
          MetaFile archive = carrier.getArchiveFile();
          long childCount = countChildren(carrierId);
          ChildStorageAudit audit = auditChildStorage(carrierId);
          AiOcrArchiveReclaimPolicy.Decision decision =
              AiOcrArchiveReclaimPolicy.decide(
                  archive != null, tally, childCount, audit.verified, audit.firstFailure);

          if (!decision.isReclaim()) {
            if (archive != null) {
              // Say why the ZIP is still there; a silent retention is indistinguishable from a bug.
              appendInformation(carrier, "Original archive kept: " + decision.getReason() + ".");
              uploadRepo.save(carrier);
            }
            return;
          }

          long stored = countChildren(carrierId);
          // Null the reference and flush BEFORE the reference test, or this carrier still counts as
          // a referrer and nothing would ever be reclaimed.
          carrier.setArchiveFile(null);
          appendInformation(
              carrier,
              String.format(
                  "Original archive reclaimed after a fully successful extraction: all %s"
                      + " document(s) are stored individually. The record, file name and content"
                      + " hashes are kept.",
                  stored));
          uploadRepo.save(carrier);
          JPA.flush();

          boolean removed =
              Beans.get(OcrUploadFileCleanupService.class).quarantineIfUnreferenced(archive);
          if (!removed) {
            LOG.info(
                "Carrier {}: the original archive is still referenced elsewhere, so its blob was"
                    + " left in place (the carrier no longer holds it).",
                carrierId);
          }
        });
  }

  protected long countChildren(Long carrierId) {
    return uploadRepo
        .all()
        .filter("self.importArchive.id = :carrierId")
        .bind("carrierId", carrierId)
        .count();
  }

  /** How many children are provably stored, and the first reason one is not. */
  protected static final class ChildStorageAudit {
    final long verified;
    final String firstFailure;

    ChildStorageAudit(long verified, String firstFailure) {
      this.verified = verified;
      this.firstFailure = firstFailure;
    }
  }

  /**
   * Prove that every child of this carrier is <b>durably stored</b>, not merely recorded.
   *
   * <p>A {@code MetaFile} row is not evidence that its bytes exist: a failed upload, an interrupted
   * copy, a restored database pointing at a different share, or a blob deleted out of band all
   * leave the row behind. Reclaiming the ZIP on the strength of the row alone would then destroy
   * the only remaining copy — so each child is re-read from the database and its blob is confirmed
   * through the platform's own storage abstraction to be a regular, non-empty file.
   *
   * <p>{@link MetaFiles#getPath(MetaFile)} resolves against the configured {@code data.upload.dir},
   * which in production is the mounted Azure File Share — no path is hardcoded or reconstructed
   * here, so this behaves identically on the dev volume and on the share.
   *
   * <p>Never throws: any failure (including an I/O error from the share) is turned into a retention
   * reason, because failing to verify storage must keep the archive, never fail the extraction.
   */
  protected ChildStorageAudit auditChildStorage(Long carrierId) {
    long verified = 0;
    String firstFailure = null;
    try {
      List<AiOcrUpload> children =
          uploadRepo
              .all()
              .filter("self.importArchive.id = :carrierId")
              .bind("carrierId", carrierId)
              .fetch();
      for (AiOcrUpload child : children) {
        String failure = verifyChildStorage(child);
        if (failure == null) {
          verified++;
        } else if (firstFailure == null) {
          firstFailure = failure;
        }
      }
    } catch (RuntimeException e) {
      // Cannot enumerate/verify — treat as unproven, never as an extraction failure.
      return new ChildStorageAudit(
          0, "the stored documents could not be verified (" + e.getMessage() + ")");
    }
    return new ChildStorageAudit(verified, firstFailure);
  }

  /** {@code null} when this child's blob is confirmed present and non-empty. */
  protected String verifyChildStorage(AiOcrUpload child) {
    if (child == null) {
      return AiOcrArchiveReclaimPolicy.childStorageFailure(null, false, false, false, false, 0L);
    }
    String label =
        child.getFileName() != null && !child.getFileName().trim().isEmpty()
            ? child.getFileName()
            : String.valueOf(child.getId());
    MetaFile file = child.getOriginalFile();
    if (file == null) {
      return AiOcrArchiveReclaimPolicy.childStorageFailure(label, true, false, false, false, 0L);
    }
    Path path;
    try {
      path = MetaFiles.getPath(file); // resolves under data.upload.dir (the File Share in prod)
    } catch (RuntimeException e) {
      return AiOcrArchiveReclaimPolicy.childStorageFailure(label, true, true, false, false, 0L);
    }
    if (path == null) {
      return AiOcrArchiveReclaimPolicy.childStorageFailure(label, true, true, false, false, 0L);
    }
    try {
      boolean regular = Files.isRegularFile(path);
      long size = regular ? Files.size(path) : 0L;
      return AiOcrArchiveReclaimPolicy.childStorageFailure(label, true, true, true, regular, size);
    } catch (IOException | RuntimeException e) {
      // An unreadable share is "unproven", not "absent" — either way the ZIP stays.
      return "the stored file for document \""
          + label
          + "\" could not be read from storage ("
          + e.getMessage()
          + ")";
    }
  }

  /** Append one line to the carrier's user-visible extraction information. */
  private static void appendInformation(AiOcrUpload carrier, String line) {
    String prev = carrier.getExtractionInformation();
    carrier.setExtractionInformation(
        (prev == null || prev.trim().isEmpty()) ? line : prev + "\n" + line);
  }

  /**
   * Synchronous claim run on the HTTP request when the user clicks "Extract archive", BEFORE the
   * background extraction is submitted. Flips the carrier to {@code extracting} and stamps the REAL
   * {@code extractionStartedOn} (the moment of the click) in its OWN committed transaction, so:
   *
   * <ul>
   *   <li>the reload immediately hides the "Extract archive" button (its {@code showIf} now sees a
   *       non-null {@code extractionStartedOn}) — a repeated click is a no-op;
   *   <li>the start time is the click moment, not a value stamped on the background thread a
   *       millisecond before a near-instant extraction — which made start == finish for small ZIPs.
   * </ul>
   *
   * A bulk UPDATE (no {@code @Version}) guarded by {@code extractionStartedOn IS NULL} keeps it
   * idempotent: only the first click stamps the time; a re-extract after a crash leaves it intact.
   */
  @Transactional
  public boolean claimExtraction(Long carrierId) {
    int updated =
        JPA.em()
            .createQuery(
                "UPDATE AiOcrUpload s SET s.statusSelect = :extracting, "
                    + "s.extractionStartedOn = :now, s.extractionFinishedOn = null, "
                    + "s.extractionAttemptId = COALESCE(s.extractionAttemptId, :attempt) "
                    + "WHERE s.id = :id AND s.extractionStartedOn IS NULL")
            .setParameter("extracting", AiOcrUploadStatus.EXTRACTING)
            .setParameter("now", LocalDateTime.now())
            .setParameter("attempt", UUID.randomUUID().toString())
            .setParameter("id", carrierId)
            .executeUpdate();
    if (updated == 1) {
      // Bulk UPDATE bypasses JPA entity lifecycle, so the Upload Audit trail needs an explicit
      // sync — atomic with the claim above (same transaction): a persistence failure here rolls
      // both back (see AiOcrUploadAuditService's class javadoc).
      Beans.get(AiOcrUploadAuditService.class).syncByIds(List.of(carrierId));
    }
    return updated == 1;
  }

  @Transactional
  public void beginExtraction(Long carrierId) {
    AiOcrUpload carrier = uploadRepo.find(carrierId);
    if (carrier == null) {
      return;
    }
    if (carrier.getExtractionStartedOn() == null) {
      carrier.setExtractionStartedOn(LocalDateTime.now());
    }
    // Give the carrier a clear name in the list (the ZIP file name) instead of a blank row.
    if ((carrier.getFileName() == null || carrier.getFileName().trim().isEmpty())
        && carrier.getArchiveFile() != null) {
      carrier.setFileName(carrier.getArchiveFile().getFileName());
    }
    if (carrier.getExtractionAttemptId() == null) {
      carrier.setExtractionAttemptId(UUID.randomUUID().toString());
    }
    carrier.setExtractionFinishedOn(null);
    carrier.setStatusSelect(AiOcrUploadStatus.EXTRACTING);
    uploadRepo.save(carrier);
  }

  /**
   * Set extractionFinishedOn (+ optional reason), then recompute the carrier status from children.
   *
   * <p>Used by the pre-extraction bail-outs in {@link #extract}, where there is no reclaim step to
   * order against. {@link #doExtract} deliberately does NOT use this: it stamps the row with {@link
   * #markExtractionFinished} and runs the single rollup itself, after the archive reclaim.
   */
  @Transactional
  public void finishCarrier(Long carrierId, String reason) {
    markExtractionFinished(carrierId, reason);
    carrierService.recompute(carrierId);
  }

  /**
   * Stamp extractionFinishedOn (+ optional reason) on the carrier. Writes the ENTITY, so it must
   * never run after the rollup — see the ordering note at the end of {@link #doExtract}.
   */
  @Transactional
  public void markExtractionFinished(Long carrierId, String reason) {
    AiOcrUpload carrier = uploadRepo.find(carrierId);
    if (carrier == null) {
      return;
    }
    carrier.setExtractionFinishedOn(LocalDateTime.now());
    if (reason != null && !reason.trim().isEmpty()) {
      carrier.setExtractionErrors(reason.trim());
    }
    uploadRepo.save(carrier);
  }

  /**
   * Upsert a supported document: skip if this exact (carrier, path+hash) child already exists; else
   * create a {@code duplicate} child when (company, hash) is already present anywhere, otherwise a
   * {@code queued} child with the uploaded file.
   */
  @Transactional
  public void upsertDocument(
      Long carrierId, Long companyId, Long userId, String sourcePath, String hash, Path tempFile)
      throws IOException {
    String key = keyFor(sourcePath, hash);
    if (childExists(carrierId, key)) {
      return; // idempotent — already created on a previous (crashed) run
    }
    boolean duplicate = isDuplicate(companyId, hash);
    AiOcrUpload doc = newChild(carrierId, companyId, userId, sourcePath, key);
    doc.setFileHash(hash);
    if (duplicate) {
      doc.setFileName(leafName(sourcePath));
      doc.setStatusSelect(AiOcrUploadStatus.DUPLICATE);
      doc.setExtractionErrors(
          "Duplicate of an already-imported file (same company + content hash).");
    } else {
      String leaf = leafName(sourcePath);
      MetaFile metaFile;
      try (InputStream in = Files.newInputStream(tempFile)) {
        metaFile = Beans.get(MetaFiles.class).upload(in, leaf);
      }
      doc.setOriginalFile(metaFile);
      doc.setFileName(leaf);
      doc.setStatusSelect(AiOcrUploadStatus.QUEUED);
    }
    uploadRepo.save(doc);
  }

  /** Upsert a non-document outcome (unsupported / corrupt-nested) as one terminal child. */
  @Transactional
  public void upsertOutcome(
      Long carrierId,
      Long companyId,
      Long userId,
      String sourcePath,
      String key,
      String status,
      String message) {
    if (childExists(carrierId, key)) {
      return;
    }
    AiOcrUpload doc = newChild(carrierId, companyId, userId, sourcePath, key);
    doc.setFileName(leafName(sourcePath));
    doc.setStatusSelect(status);
    doc.setExtractionErrors(message);
    doc.setProcessedOn(LocalDateTime.now());
    uploadRepo.save(doc);
  }

  protected AiOcrUpload newChild(
      Long carrierId, Long companyId, Long userId, String sourcePath, String key) {
    AiOcrUpload doc = new AiOcrUpload();
    doc.setImportArchive(uploadRepo.find(carrierId));
    doc.setExtractionKey(key);
    doc.setSourcePath(sourcePath);
    if (companyId != null) {
      doc.setCompany(
          Beans.get(com.axelor.apps.base.db.repo.CompanyRepository.class).find(companyId));
    }
    if (userId != null) {
      doc.setUploadedBy(Beans.get(com.axelor.auth.db.repo.UserRepository.class).find(userId));
    }
    return doc;
  }

  protected boolean childExists(Long carrierId, String key) {
    return uploadRepo
            .all()
            .filter("self.importArchive.id = :carrierId AND self.extractionKey = :key")
            .bind("carrierId", carrierId)
            .bind("key", key)
            .count()
        > 0;
  }

  protected boolean isDuplicate(Long companyId, String hash) {
    if (hash == null) {
      return false;
    }
    return uploadRepo
            .all()
            .filter(
                "self.fileHash = :hash AND self.statusSelect NOT IN :ignored "
                    + "AND ((:companyId IS NULL AND self.company IS NULL) OR self.company.id = :companyId)")
            .bind("hash", hash)
            .bind(
                "ignored",
                java.util.Arrays.asList(AiOcrUploadStatus.CANCELLED, AiOcrUploadStatus.DUPLICATE))
            .bind("companyId", companyId)
            .count()
        > 0;
  }

  /** Deterministic key, unique within a carrier: normalized path + hash / outcome type. */
  static String keyFor(String sourcePath, String hashOrOutcome) {
    return normalizePath(sourcePath) + "|" + (hashOrOutcome == null ? "" : hashOrOutcome);
  }

  static String normalizePath(String sourcePath) {
    if (sourcePath == null) {
      return "";
    }
    return sourcePath.replace('\\', '/').trim().toLowerCase();
  }

  protected static String leafName(String sourcePath) {
    if (sourcePath == null) {
      return "document";
    }
    String p = sourcePath.replace('\\', '/');
    int slash = p.lastIndexOf('/');
    String leaf = slash >= 0 ? p.substring(slash + 1) : p;
    return leaf.isEmpty() ? "document" : leaf;
  }

  protected SafeZipExtractor.Limits resolveLimits() {
    SafeZipExtractor.Limits limits = new SafeZipExtractor.Limits();
    limits.maxDepth = cfgInt("aiocr.zip.max-depth", limits.maxDepth);
    limits.maxEntries = cfgLong("aiocr.zip.max-entries", limits.maxEntries);
    limits.maxEntryBytes = cfgLong("aiocr.zip.max-entry-bytes", limits.maxEntryBytes);
    limits.maxTotalBytes = cfgLong("aiocr.zip.max-total-bytes", limits.maxTotalBytes);
    limits.maxRatio = cfgLong("aiocr.zip.max-ratio", limits.maxRatio);
    limits.minFreeBytes = cfgLong("aiocr.storage.min-free-bytes", 0L);
    return limits;
  }

  protected static int cfgInt(String key, int def) {
    try {
      return Integer.parseInt(AppSettings.get().get(key, String.valueOf(def)).trim());
    } catch (RuntimeException e) {
      return def;
    }
  }

  protected static long cfgLong(String key, long def) {
    try {
      return Long.parseLong(AppSettings.get().get(key, String.valueOf(def)).trim());
    } catch (RuntimeException e) {
      return def;
    }
  }
}

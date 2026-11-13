package com.axelor.apps.aiocr.service;

import com.axelor.app.AppSettings;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rollback-safe physical-file deletion via a quarantine/trash directory.
 *
 * <p>Absolute atomicity between JPA and the filesystem is impossible. This service gives practical
 * rollback-safe semantics: during the delete transaction a blob is <b>moved aside</b> into a
 * quarantine directory (an atomic same-filesystem rename); the DB row is removed only after that
 * succeeds. A {@link OcrFileTransactionSynchronization} then, after the transaction completes,
 * either <b>restores</b> the blob (on rollback) or <b>permanently deletes</b> it (on commit). If
 * the post-commit deletion fails the blob simply stays in quarantine — the durable retry queue
 * swept by {@code OcrFileQuarantineSweepJob}.
 *
 * <p>The quarantine directory lives under the upload dir (same filesystem, so the move is atomic
 * and passes the platform containment guard), unless {@code aiocr.file.quarantine.dir} overrides it
 * (it must then be on the same filesystem as {@code data.upload.dir}).
 */
@Singleton
public class OcrFileQuarantineService {

  private static final Logger LOG = LoggerFactory.getLogger(OcrFileQuarantineService.class);

  /** Relative name of the quarantine folder under the (tenant) upload directory. */
  protected static final String QUARANTINE_DIR_NAME = ".ocr-quarantine";

  /** Optional absolute override; must be on the same filesystem as data.upload.dir. */
  protected static final String QUARANTINE_DIR_KEY = "aiocr.file.quarantine.dir";

  /** How long a leftover quarantined blob is kept before the sweeper reclaims it. */
  protected static final String RETENTION_HOURS_KEY = "aiocr.file.quarantine.retention.hours";

  protected static final int DEFAULT_RETENTION_HOURS = 24;

  /** The quarantine directory for the current tenant, creating it if missing. */
  public Path quarantineDir() {
    String override = AppSettings.get().get(QUARANTINE_DIR_KEY, null);
    Path dir =
        (override != null && !override.trim().isEmpty())
            ? Path.of(override.trim())
            : MetaFiles.getPath(QUARANTINE_DIR_NAME);
    try {
      Files.createDirectories(dir);
    } catch (IOException e) {
      throw new IllegalStateException("Cannot create OCR quarantine directory: " + dir, e);
    }
    return dir;
  }

  /** Bootstrap hook (startup) — ensures the directory exists early; never fatal. */
  public void ensureQuarantineDir() {
    try {
      quarantineDir();
    } catch (RuntimeException e) {
      LOG.warn("Could not pre-create the OCR quarantine directory; will retry on first use.", e);
    }
  }

  /**
   * Move a MetaFile's physical blob aside into quarantine. Returns the {@link QuarantinedFile}
   * handle, or empty when the physical file is already absent (missing-file behavior: nothing to
   * move/restore/delete — the caller still removes the DB row). Throws an unchecked exception when
   * the move fails, so the caller's transaction rolls back before any DB row is deleted.
   */
  public Optional<QuarantinedFile> quarantine(MetaFile metaFile) {
    if (metaFile == null) {
      return Optional.empty();
    }
    Path src = resolveSourcePath(metaFile);
    if (!Files.exists(src)) {
      LOG.warn(
          "OCR delete: physical blob for MetaFile id={} is already missing at {}; removing the"
              + " record only.",
          metaFile.getId(),
          src);
      return Optional.empty();
    }
    Path target = quarantineDir().resolve(UUID.randomUUID().toString());
    try {
      atomicMove(src, target);
    } catch (IOException e) {
      // Abort BEFORE the DB row is removed → the whole transaction rolls back (and anything already
      // quarantined in this transaction is restored by the synchronization).
      throw new IllegalStateException(
          "Failed to quarantine the file for MetaFile id="
              + metaFile.getId()
              + " (src="
              + src
              + "); aborting delete.",
          e);
    }
    return Optional.of(new QuarantinedFile(src, target, metaFile.getId()));
  }

  /** Resolve a MetaFile's physical blob path (overridable seam for tests). */
  protected Path resolveSourcePath(MetaFile metaFile) {
    return MetaFiles.getPath(metaFile);
  }

  /** Atomic same-filesystem move (overridable seam for tests). */
  protected void atomicMove(Path src, Path target) throws IOException {
    Files.move(src, target, StandardCopyOption.ATOMIC_MOVE);
  }

  /** Permanently remove a quarantined blob (after commit). */
  public void permanentlyDelete(QuarantinedFile qf) throws IOException {
    Files.deleteIfExists(qf.getQuarantine());
  }

  /** Move a quarantined blob back to its original path (on rollback). */
  public void restore(QuarantinedFile qf) throws IOException {
    Files.move(qf.getQuarantine(), qf.getOriginal(), StandardCopyOption.ATOMIC_MOVE);
  }

  /** Retention window for leftover quarantined blobs. */
  public Duration retention() {
    int hours = AppSettings.get().getInt(RETENTION_HOURS_KEY, DEFAULT_RETENTION_HOURS);
    return Duration.ofHours(hours > 0 ? hours : DEFAULT_RETENTION_HOURS);
  }

  /**
   * Reclaim leftover quarantined blobs older than {@link #retention()} — the retry for post-commit
   * deletions that failed. Returns the number of files deleted. Never throws; logs per-file errors.
   */
  public int sweep(long nowMillis) {
    Path dir;
    try {
      dir = quarantineDir();
    } catch (RuntimeException e) {
      LOG.warn("OCR quarantine sweep skipped: directory unavailable.", e);
      return 0;
    }
    long cutoff = nowMillis - retention().toMillis();
    int deleted = 0;
    try (var stream = Files.list(dir)) {
      for (Path p : (Iterable<Path>) stream::iterator) {
        try {
          if (Files.isRegularFile(p) && Files.getLastModifiedTime(p).toMillis() < cutoff) {
            Files.deleteIfExists(p);
            deleted++;
          }
        } catch (IOException e) {
          LOG.warn("OCR quarantine sweep: could not delete {}", p, e);
        }
      }
    } catch (IOException e) {
      LOG.warn("OCR quarantine sweep: could not list {}", dir, e);
    }
    return deleted;
  }
}

package com.axelor.apps.aiocr.service;

import java.nio.file.Path;

/**
 * Immutable record of one physical blob that has been moved aside into the quarantine directory
 * during an OCR-invoice delete. Held in memory by {@link OcrFileTransactionSynchronization} for the
 * lifetime of the transaction:
 *
 * <ul>
 *   <li>on rollback the blob is moved back {@code quarantine → original};
 *   <li>on commit the quarantined blob is permanently deleted.
 * </ul>
 *
 * {@code metaFileId} is kept only for logging/diagnostics (the MetaFile row is removed within the
 * same transaction).
 */
public final class QuarantinedFile {

  private final Path original;
  private final Path quarantine;
  private final Long metaFileId;

  public QuarantinedFile(Path original, Path quarantine, Long metaFileId) {
    this.original = original;
    this.quarantine = quarantine;
    this.metaFileId = metaFileId;
  }

  public Path getOriginal() {
    return original;
  }

  public Path getQuarantine() {
    return quarantine;
  }

  public Long getMetaFileId() {
    return metaFileId;
  }

  @Override
  public String toString() {
    return "QuarantinedFile{metaFileId="
        + metaFileId
        + ", original="
        + original
        + ", quarantine="
        + quarantine
        + '}';
  }
}

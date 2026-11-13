package com.axelor.apps.aiocr.service.storage;

import com.axelor.apps.aiocr.service.storage.OcrMetaFileReferenceScanner.FileReferences;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.db.repo.MetaFileRepository;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.io.File;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reference protection for physical file deletion and in-place overwrite.
 *
 * <h2>Why this exists</h2>
 *
 * Once an OCR upload and the Invoice/Move it produced <b>share one {@link MetaFile}</b> instead of
 * holding two byte-identical copies, the platform's own reference counting is no longer sufficient:
 * {@code DMSFileRepository.remove} counts only {@code DMSFile} and {@code MetaAttachment} rows and
 * knows nothing about {@code AiOcrUpload.originalFile} or {@code BankStatement.bankStatementFile}.
 * Left alone it would delete the shared blob when the invoice's attachment goes away, destroying
 * the upload's reprocessable source.
 *
 * <p>Worse, {@link MetaFiles#delete(MetaFile)} removes the row with a <i>queued</i> {@code
 * em.remove} and then deletes the blob immediately, so a foreign-key violation only surfaces at
 * flush/commit — after the bytes are gone. A rollback restores the row but not the file. A foreign
 * key is therefore not a byte-loss defence, and the protection has to sit in front of the deletion
 * itself.
 *
 * <h2>What it does — and deliberately does not do</h2>
 *
 * {@link #delete(MetaFile)} is a <b>precondition assertion</b> on a documented contract: the caller
 * must already have detached (and flushed) the one reference it owns. It never guesses which
 * attachment a caller meant and never removes {@code DMSFile}/{@code MetaAttachment} rows of its
 * own — identifying the exact attachment belongs to the owner-aware caller, which alone knows the
 * related model and id.
 *
 * <p>Nothing that succeeds today changes: in the only state where the platform's delete succeeds
 * (nothing references the file any more) this delegates unchanged to {@code super}.
 */
public class OcrMetaFiles extends MetaFiles {

  private static final Logger LOG = LoggerFactory.getLogger(OcrMetaFiles.class);

  protected final OcrMetaFileReferenceScanner scanner;

  @Inject
  public OcrMetaFiles(MetaFileRepository filesRepo, OcrMetaFileReferenceScanner scanner) {
    super(filesRepo);
    this.scanner = scanner;
  }

  /**
   * Delete the file only when nothing at all still references it.
   *
   * <table>
   *   <tr><th>State</th><th>Action</th></tr>
   *   <tr><td>scan unproven</td><td>retain + WARN — an unproven scan is never a licence to delete
   *       </td></tr>
   *   <tr><td>a DMSFile/MetaAttachment holder remains</td><td><b>refuse</b> — the caller did not
   *       identify and detach one exact attachment, or a second DMS consumer exists</td></tr>
   *   <tr><td>only business holders remain</td><td>retain and return normally, so the caller's own
   *       delete (e.g. the Invoice cascade) still completes while the holder keeps the blob</td></tr>
   *   <tr><td>no holders</td><td>delegate to {@code super} — unchanged platform behaviour</td></tr>
   * </table>
   *
   * <p>Note the third row: it is what lets {@code DMSFileRepository.remove} finish deleting an
   * Invoice's attachment while {@code AiOcrUpload.originalFile} keeps the shared source file.
   */
  @Override
  @Transactional
  public void delete(MetaFile metaFile) throws IOException {
    if (metaFile == null || metaFile.getId() == null) {
      super.delete(metaFile);
      return;
    }
    FileReferences refs = scanner.referencesOf(metaFile);
    if (refs.isUnproven()) {
      LOG.warn(
          "Refusing to delete MetaFile id={} ({}): the reference scan is not conclusive — {}."
              + " The record and its blob are retained.",
          metaFile.getId(),
          metaFile.getFileName(),
          refs.getFailureReason());
      return;
    }
    if (refs.hasDmsHolder()) {
      throw new IllegalStateException(
          "Cannot delete the file \""
              + metaFile.getFileName()
              + "\": it is still attached to a record ("
              + refs.getDmsHolders()
              + "). Remove that attachment through the record that owns it, or — if this file is"
              + " shared with another record — remove only that record's own attachment.");
    }
    if (refs.hasOtherHolder()) {
      LOG.debug(
          "Keeping MetaFile id={} ({}): still held by {}.",
          metaFile.getId(),
          metaFile.getFileName(),
          refs.getOtherHolders());
      return;
    }
    doDelete(metaFile);
  }

  /** The unguarded platform delete. Overridable seam so a test can assert delegation. */
  protected void doDelete(MetaFile metaFile) throws IOException {
    super.delete(metaFile);
  }

  /** The unguarded platform in-place upload. Overridable seam so a test can assert delegation. */
  protected MetaFile doUpload(File file, MetaFile metaFile) throws IOException {
    return super.upload(file, metaFile);
  }

  /**
   * Refuse an in-place byte overwrite of a file anything else still references.
   *
   * <p>{@code MetaFiles.upload(File, MetaFile)} in update mode keeps the MetaFile id (and therefore
   * every reference to it) but repoints {@code filePath} and <b>permanently deletes the previous
   * blob</b>. On a shared file that silently rewrites what the other consumer sees. Copy-on-write
   * cannot be substituted here: the REST upload endpoint responds with the entity it was given, not
   * with the instance this method returns, so a replacement MetaFile would never reach the client.
   * Refusal is the only correct behaviour. An unproven scan refuses too.
   *
   * <p>A fresh MetaFile (no {@code filePath} yet) is not an overwrite and passes straight through.
   */
  @Override
  @Transactional
  public MetaFile upload(File file, MetaFile metaFile) throws IOException {
    boolean update =
        metaFile != null
            && metaFile.getId() != null
            && metaFile.getFilePath() != null
            && !metaFile.getFilePath().trim().isEmpty();
    if (!update) {
      return doUpload(file, metaFile);
    }
    FileReferences refs = scanner.referencesOf(metaFile);
    if (!refs.isUnreferenced()) {
      throw new IllegalStateException(
          "Cannot replace the contents of \""
              + metaFile.getFileName()
              + "\" in place: "
              + (refs.isUnproven()
                  ? "the reference scan is not conclusive — " + refs.getFailureReason()
                  : "it is still referenced by " + describeHolders(refs))
              + ". Remove the attachment first and add the new file, so the other record keeps its"
              + " original document.");
    }
    return doUpload(file, metaFile);
  }

  private static String describeHolders(FileReferences refs) {
    if (refs.hasDmsHolder() && refs.hasOtherHolder()) {
      return refs.getDmsHolders() + " and " + refs.getOtherHolders();
    }
    return refs.hasDmsHolder()
        ? refs.getDmsHolders().toString()
        : refs.getOtherHolders().toString();
  }
}

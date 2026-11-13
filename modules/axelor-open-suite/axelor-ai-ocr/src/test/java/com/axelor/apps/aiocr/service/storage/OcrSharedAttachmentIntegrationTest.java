package com.axelor.apps.aiocr.service.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.apps.aiocr.service.OcrUploadFileCleanupService;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.db.JPA;
import com.axelor.dms.db.DMSFile;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * DB-backed proof of Phase 4A over the real Guice/JPA stack: one {@link MetaFile} is shared by a
 * plain foreign-key holder and a DMS attachment instead of the bytes being stored twice, and every
 * delete path stays correct and rollback-safe.
 *
 * <h2>Why the DMS owner here is an upload, not an Invoice</h2>
 *
 * Attaching a DMS file to an Invoice is impossible under this harness: {@code MetaFiles.attach} →
 * {@code DMSFileRepository.save} → {@code findOrCreateHome} → {@code findRelated} does a {@code
 * JPA.find(Invoice, id)}, and the stock {@code Invoice} carries a {@code @Formula} sub-select
 * HSQLDB cannot parse (the same limitation {@code OcrInvoiceDeleteIntegrationTest} documents by
 * never SELECTing an Invoice). That is a dialect limitation of the test database, not a property of
 * the guard.
 *
 * <p>So the DMS attachment is owned by a second {@link AiOcrUpload}, which HSQLDB can SELECT. The
 * code path under test is identical and, importantly, still the one that matters: deleting that
 * record reaches {@code OcrMetaFiles.delete} through {@code AuditTracker.processDelete} → {@code
 * MetaFiles.deleteAttachments} → {@code DMSFileRepository.remove} inside Hibernate's
 * <b>beforeCompletion</b> phase — exactly where an Invoice delete would arrive, and the phase where
 * the reference scan had to be proven safe to run. Invoice deletion itself is covered by the
 * existing {@code OcrInvoiceDeleteIntegrationTest} and remains a manual check for the shared case.
 */
class OcrSharedAttachmentIntegrationTest extends AiOcrTest {

  private static int seq = 0;

  private AiOcrUploadRepository uploadRepo() {
    return Beans.get(AiOcrUploadRepository.class);
  }

  private OcrUploadFileCleanupService cleanup() {
    return Beans.get(OcrUploadFileCleanupService.class);
  }

  private long countById(String entityName, long id) {
    return JPA.em()
        .createQuery("SELECT COUNT(e) FROM " + entityName + " e WHERE e.id = :id", Long.class)
        .setParameter("id", id)
        .getSingleResult();
  }

  private long dmsCountFor(long metaFileId) {
    return JPA.em()
        .createQuery("SELECT COUNT(d) FROM DMSFile d WHERE d.metaFile.id = :id", Long.class)
        .setParameter("id", metaFileId)
        .getSingleResult();
  }

  private long attachmentCountFor(long metaFileId) {
    return JPA.em()
        .createQuery("SELECT COUNT(a) FROM MetaAttachment a WHERE a.metaFile.id = :id", Long.class)
        .setParameter("id", metaFileId)
        .getSingleResult();
  }

  private MetaFile newBlob(String content) throws Exception {
    File tmp = File.createTempFile("ocr-share-it", ".pdf");
    Files.writeString(tmp.toPath(), content);
    return Beans.get(MetaFiles.class).upload(tmp);
  }

  private static final class Fixture {
    long holderId; // upload holding the file via originalFile (a plain FK, no attachment)
    long attachedToId; // record carrying the DMS attachment of the SAME file
    long blobId;
    Path blobPath;
  }

  /**
   * One MetaFile, two consumers: {@code holder.originalFile} (plain FK) and a DMS attachment on
   * {@code attachedTo}. When {@code sameRecord} is true both roles are the one upload — the shape a
   * carrier has when the splitter attaches an unmatched receipt slice to the record that also owns
   * the file.
   */
  private Fixture sharedFile(boolean sameRecord) {
    Fixture fx = new Fixture();
    inTransaction(
        () -> {
          try {
            MetaFile blob = newBlob("SHARED-PDF-" + (++seq));
            fx.blobId = blob.getId();
            fx.blobPath = MetaFiles.getPath(blob);

            AiOcrUpload holder = new AiOcrUpload();
            holder.setOriginalFile(blob);
            holder.setStatusSelect(AiOcrUploadStatus.COMPLETED);
            fx.holderId = JPA.save(holder).getId();

            AiOcrUpload attachTarget = holder;
            if (!sameRecord) {
              AiOcrUpload other = new AiOcrUpload();
              other.setStatusSelect(AiOcrUploadStatus.COMPLETED);
              attachTarget = JPA.save(other);
            }
            fx.attachedToId = attachTarget.getId();

            // Phase 4A: the SAME MetaFile is attached, never a physical copy.
            Beans.get(MetaFiles.class).attach(blob, blob.getFileName(), attachTarget);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    return fx;
  }

  // --- sharing is real, and the guard is actually installed ------------------------------------

  @Test
  void theGuardIsBoundOverMetaFiles() {
    assertTrue(
        Beans.get(MetaFiles.class) instanceof OcrMetaFiles,
        "MetaFiles must resolve to the reference-protecting override");
  }

  @Test
  void oneMetaFileServesBothConsumers() {
    Fixture fx = sharedFile(false);
    JPA.em().clear();
    assertEquals(1, dmsCountFor(fx.blobId), "the attachment points at the holder's own file");
    assertEquals(1, attachmentCountFor(fx.blobId));
    assertEquals(fx.blobId, uploadRepo().find(fx.holderId).getOriginalFile().getId());
    assertEquals(
        1,
        JPA.em()
            .createQuery("SELECT COUNT(f) FROM MetaFile f WHERE f.id = :id", Long.class)
            .setParameter("id", fx.blobId)
            .getSingleResult()
            .intValue(),
        "no second MetaFile row exists for these bytes");
  }

  // --- the beforeCompletion path: deleting the attachment owner must keep the blob --------------

  /**
   * Deletes the DMS-owning record with a plain {@code em.remove}, so the attachment cleanup happens
   * where it really happens: {@code AuditTracker} → {@code deleteAttachments} → {@code
   * DMSFileRepository.remove} → {@code OcrMetaFiles.delete}, inside Hibernate beforeCompletion.
   * This is the phase in which the reference scan had to be proven safe, and the outcome the
   * sharing depends on: the blob survives because a business holder still points at it.
   */
  @Test
  void deletingTheAttachmentOwnerInBeforeCompletionKeepsTheSharedBlob() {
    Fixture fx = sharedFile(false);

    inTransaction(
        () -> {
          AiOcrUpload attachedTo = JPA.em().find(AiOcrUpload.class, fx.attachedToId);
          JPA.em().remove(attachedTo); // NOT the OCR repo: the raw platform cascade
        });
    JPA.em().clear();

    assertEquals(0, countById("AiOcrUpload", fx.attachedToId), "the owner really was deleted");
    assertEquals(1, countById("AiOcrUpload", fx.holderId), "the holder survives");
    assertEquals(1, countById("MetaFile", fx.blobId), "the shared MetaFile survives");
    assertTrue(Files.exists(fx.blobPath), "and so does the blob — the holder still needs it");
    assertEquals(0, dmsCountFor(fx.blobId), "the attachment row is gone");
    assertEquals(0, attachmentCountFor(fx.blobId), "and so is its MetaAttachment");
    assertNotNull(uploadRepo().find(fx.holderId).getOriginalFile(), "holder still points at it");
  }

  // --- tree deletion: detach the exact attachment, then quarantine post-commit ------------------

  @Test
  void fullTreeDeleteQuarantinesThenRemovesTheSharedBlobAfterCommit() {
    Fixture fx = sharedFile(true); // holder owns the file AND carries its attachment

    inTransaction(
        () -> {
          AiOcrUpload root = uploadRepo().find(fx.holderId);
          cleanup().fullyDeleteUploads(root, u -> uploadRepo().remove(u));
          // Inside the transaction the blob is only MOVED ASIDE, never physically deleted.
          assertFalse(Files.exists(fx.blobPath), "quarantined during the transaction");
        });
    JPA.em().clear();

    assertEquals(0, countById("AiOcrUpload", fx.holderId), "upload removed");
    assertEquals(0, countById("MetaFile", fx.blobId), "MetaFile row removed");
    assertEquals(0, dmsCountFor(fx.blobId), "attachment removed");
    assertFalse(Files.exists(fx.blobPath), "blob permanently gone after commit");
  }

  @Test
  void rollbackAfterDetachRestoresTheBlobAndEveryReference() {
    Fixture fx = sharedFile(true);

    assertThrows(
        RuntimeException.class,
        () ->
            inTransaction(
                () -> {
                  AiOcrUpload root = uploadRepo().find(fx.holderId);
                  cleanup().fullyDeleteUploads(root, u -> uploadRepo().remove(u));
                  throw new IllegalStateException("forced rollback after the delete work");
                }));
    JPA.em().clear();

    assertEquals(1, countById("AiOcrUpload", fx.holderId), "upload row restored");
    assertEquals(1, countById("MetaFile", fx.blobId), "MetaFile row restored");
    assertEquals(1, dmsCountFor(fx.blobId), "the detached attachment came back");
    assertTrue(Files.exists(fx.blobPath), "and the blob was restored from quarantine");
    assertNotNull(uploadRepo().find(fx.holderId).getOriginalFile());
  }

  // --- a duplicate upload must never touch the document it merely links ------------------------

  /**
   * A duplicate holds its OWN file and only LINKS the original's invoice. Deleting it must reclaim
   * its own blob and leave the original upload, its shared file and the linked invoice untouched.
   * The Invoice is only INSERTed and counted, never SELECTed (see the class comment).
   */
  @Test
  void deletingADuplicateUploadPreservesTheOriginalDocument() {
    Fixture fx = sharedFile(true);
    long[] dupId = new long[1];
    long[] dupBlobId = new long[1];
    Path[] dupBlob = new Path[1];
    long[] invoiceId = new long[1];

    inTransaction(
        () -> {
          try {
            Invoice inv = new Invoice();
            inv.setStatusSelect(InvoiceRepository.STATUS_DRAFT);
            inv.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
            inv.setAiGenerated(Boolean.TRUE);
            inv.setInvoiceId("SH-DUP-" + (++seq));
            inv.setInvoiceLineList(new java.util.ArrayList<>());
            invoiceId[0] = JPA.save(inv).getId();

            AiOcrUpload original = JPA.em().find(AiOcrUpload.class, fx.holderId);
            original.setInvoice(inv);

            MetaFile own = newBlob("DUPLICATE-PDF-" + seq);
            dupBlobId[0] = own.getId();
            dupBlob[0] = MetaFiles.getPath(own);
            AiOcrUpload dup = new AiOcrUpload();
            dup.setStatusSelect(AiOcrUploadStatus.DUPLICATE);
            dup.setInvoice(inv); // linked, NOT produced
            dup.setOriginalFile(own);
            dupId[0] = JPA.save(dup).getId();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });

    inTransaction(
        () -> {
          AiOcrUpload dup = uploadRepo().find(dupId[0]);
          cleanup().fullyDeleteUploads(dup, u -> uploadRepo().remove(u));
        });
    JPA.em().clear();

    assertEquals(0, countById("AiOcrUpload", dupId[0]), "the duplicate upload is gone");
    assertEquals(0, countById("MetaFile", dupBlobId[0]), "its OWN file row is reclaimed");
    assertFalse(Files.exists(dupBlob[0]), "its own blob is reclaimed");

    assertEquals(1, countById("Invoice", invoiceId[0]), "the LINKED invoice is not deleted");
    assertEquals(1, countById("AiOcrUpload", fx.holderId), "the original upload is untouched");
    assertEquals(1, countById("MetaFile", fx.blobId), "the shared MetaFile is untouched");
    assertTrue(Files.exists(fx.blobPath), "and its blob is untouched");
    assertEquals(1, dmsCountFor(fx.blobId), "the original keeps its attachment");
    assertEquals(1, attachmentCountFor(fx.blobId), "and its MetaAttachment");
  }

  // --- the guard refuses rather than destroying ------------------------------------------------

  @Test
  void contextFreeMetaFileDeleteIsRefusedAndRemovesNothing() {
    Fixture fx = sharedFile(false);
    JPA.em().clear();

    // What MetaService.removeAttachment does: delete by MetaFile id, with no attachment context.
    assertThrows(
        IllegalStateException.class,
        () ->
            inTransaction(
                () -> {
                  try {
                    Beans.get(MetaFiles.class).delete(JPA.em().find(MetaFile.class, fx.blobId));
                  } catch (IllegalStateException e) {
                    throw e;
                  } catch (Exception e) {
                    throw new RuntimeException(e);
                  }
                }));
    JPA.em().clear();

    assertEquals(1, countById("MetaFile", fx.blobId), "MetaFile kept");
    assertTrue(Files.exists(fx.blobPath), "blob kept");
    assertEquals(1, dmsCountFor(fx.blobId), "the attachment was not silently removed either");
    assertEquals(1, attachmentCountFor(fx.blobId));
  }

  // --- sharability: a second DMS consumer forces an independent copy ---------------------------

  @Test
  void aForeignDmsHolderPreventsSharing() {
    Fixture fx = sharedFile(false);
    JPA.em().clear();
    MetaFile blob = JPA.em().find(MetaFile.class, fx.blobId);
    OcrFileSharingService sharing = Beans.get(OcrFileSharingService.class);
    assertFalse(
        sharing.canShareWith(blob, AiOcrUpload.class.getName(), fx.holderId),
        "another record already holds it as an attachment — must copy instead");
    assertTrue(
        sharing.canShareWith(blob, AiOcrUpload.class.getName(), fx.attachedToId),
        "the record that already owns the attachment is not a foreign holder");
  }

  @Test
  void anOrphanMetaAttachmentAlonePreventsSharing() {
    Fixture fx = sharedFile(false);
    inTransaction(
        () -> {
          // Drop the DMSFile but leave the MetaAttachment — the orphan the platform's owner-blind
          // bookkeeping can leave behind.
          DMSFile d =
              JPA.em()
                  .createQuery("SELECT d FROM DMSFile d WHERE d.metaFile.id = :id", DMSFile.class)
                  .setParameter("id", fx.blobId)
                  .getSingleResult();
          d.setMetaFile(null);
          JPA.em().remove(d);
        });
    JPA.em().clear();

    assertEquals(0, dmsCountFor(fx.blobId));
    assertEquals(1, attachmentCountFor(fx.blobId), "the orphan attachment remains");
    MetaFile blob = JPA.em().find(MetaFile.class, fx.blobId);
    assertFalse(
        Beans.get(OcrFileSharingService.class)
            .canShareWith(blob, AiOcrUpload.class.getName(), fx.holderId),
        "an orphan MetaAttachment alone must block sharing");
  }
}

package com.axelor.apps.aiocr.service.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * The storage shape the whole-document promotion produces: a PDF carrier and its single invoice
 * child hold the SAME {@link MetaFile}, so the required three-row hierarchy costs no second copy of
 * the document. Both are plain foreign keys ({@code originalFile}), which is the sharing {@code
 * OcrFileSharingService} documents as supported.
 *
 * <p>What has to stay true is the deletion arithmetic, and it is not asserted by inspection here
 * but driven through the real cleanup service: one holder going away must not take the blob, the
 * last holder going away must, and a rollback must put everything back.
 */
class AiOcrSplitSharedCarrierFileIntegrationTest extends AiOcrTest {

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

  private static final class Tree {
    long carrierId;
    long leafId;
    long blobId;
    Path blobPath;
  }

  /** ZIP-less minimal shape: PDF carrier + invoice child, both pointing at one MetaFile. */
  private Tree sharedTree() {
    Tree t = new Tree();
    inTransaction(
        () -> {
          try {
            File tmp = File.createTempFile("ocr-whole-doc", ".pdf");
            Files.writeString(tmp.toPath(), "WHOLE-DOCUMENT-PDF-" + (++seq));
            MetaFile blob = Beans.get(MetaFiles.class).upload(tmp);
            t.blobId = blob.getId();
            t.blobPath = MetaFiles.getPath(blob);

            AiOcrUpload carrier = new AiOcrUpload();
            carrier.setFileName("onepager.pdf");
            carrier.setOriginalFile(blob);
            carrier.setCarrierKind(AiOcrUploadStatus.CARRIER_KIND_PDF_SPLIT);
            carrier.setStatusSelect(AiOcrUploadStatus.ARCHIVE);
            t.carrierId = JPA.save(carrier).getId();

            AiOcrUpload leaf = new AiOcrUpload();
            leaf.setImportArchive(carrier);
            leaf.setSegmentType(AiOcrUploadStatus.SEGMENT_TYPE_INVOICE);
            leaf.setFileName("onepager-invoice-p1-0.pdf");
            leaf.setOriginalFile(blob); // SHARED, not a copy
            leaf.setStatusSelect(AiOcrUploadStatus.COMPLETED);
            t.leafId = JPA.save(leaf).getId();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    return t;
  }

  /**
   * The claim itself, driven through the real splitter rather than a hand-built fixture: a carrier
   * whose single invoice covers its whole PDF produces the invoice child WITHOUT writing a second
   * MetaFile. Uses the stored {@code splitSourceJson} as the split source, so no Azure call happens
   * (the splitter never makes one — it exists to reuse the single result already taken).
   */
  @Test
  void splittingAWholeDocumentInvoiceWritesNoSecondBlob() {
    long[] carrierId = new long[1];
    long[] blobId = new long[1];

    inTransaction(
        () -> {
          try {
            MetaFile blob = Beans.get(MetaFiles.class).upload(onePagePdf());
            blobId[0] = blob.getId();

            AiOcrUpload carrier = new AiOcrUpload();
            carrier.setFileName("onepager.pdf");
            carrier.setOriginalFile(blob);
            carrier.setCarrierKind(AiOcrUploadStatus.CARRIER_KIND_PDF_SPLIT);
            carrier.setStatusSelect(AiOcrUploadStatus.EXTRACTING);
            carrier.setSplitSourceJson(SINGLE_INVOICE_AZURE_JSON);
            carrierId[0] = JPA.save(carrier).getId();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });

    inTransaction(() -> Beans.get(AiOcrPdfSplitService.class).split(carrierId[0]));
    JPA.em().clear();

    java.util.List<AiOcrUpload> children =
        uploadRepo().all().filter("self.importArchive.id = :c").bind("c", carrierId[0]).fetch();
    assertEquals(1, children.size(), "the invoice child exists — the hierarchy is unchanged");
    assertEquals(
        blobId[0],
        children.get(0).getOriginalFile().getId(),
        "and it points at the carrier's own MetaFile rather than a re-serialised copy");
    assertEquals(
        1,
        JPA.em()
            .createQuery(
                "SELECT COUNT(f) FROM MetaFile f WHERE f.id = :id OR f.fileName LIKE :slice",
                Long.class)
            .setParameter("id", blobId[0])
            .setParameter("slice", "onepager-invoice-%")
            .getSingleResult()
            .longValue(),
        "no slice MetaFile was created alongside the carrier's file");
  }

  /** A minimal real one-page PDF, so PDFBox can count its pages. */
  private static File onePagePdf() throws Exception {
    File f = File.createTempFile("ocr-onepager", ".pdf");
    try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
      doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
      doc.save(f);
    }
    return f;
  }

  /** One page, one invoice, nothing else — the ZIP+single-invoice shape. */
  private static final String SINGLE_INVOICE_AZURE_JSON =
      "{\"pages\":[{\"pageNumber\":1,\"width\":8.5,\"height\":11,\"lines\":["
          + "{\"content\":\"Фактура\"},{\"content\":\"№ 1000000001\"},{\"content\":\"AA EOOD\"}]}],"
          + "\"documents\":[{\"docType\":\"invoice\",\"confidence\":1,"
          + "\"boundingRegions\":[{\"pageNumber\":1}],\"fields\":{"
          + "\"VendorTaxId\":{\"valueString\":\"BG111111111\"},"
          + "\"InvoiceId\":{\"valueString\":\"1000000001\"},"
          + "\"InvoiceTotal\":{\"valueCurrency\":{\"amount\":100.00,\"currencyCode\":\"EUR\"}},"
          + "\"SubTotal\":{\"valueCurrency\":{\"amount\":100.00,\"currencyCode\":\"EUR\"}}}}]}";

  @Test
  void theCarrierAndItsLeafShareOneBlob() {
    Tree t = sharedTree();
    JPA.em().clear();

    assertEquals(
        1,
        countById("MetaFile", t.blobId),
        "one MetaFile row for the document, not one per record");
    assertEquals(t.blobId, uploadRepo().find(t.carrierId).getOriginalFile().getId());
    assertEquals(
        t.blobId,
        uploadRepo().find(t.leafId).getOriginalFile().getId(),
        "the leaf points at the carrier's own file");
    assertTrue(Files.exists(t.blobPath), "and there is exactly one blob on disk");
  }

  @Test
  void deletingOnlyTheLeafKeepsTheCarriersBlob() {
    Tree t = sharedTree();

    inTransaction(
        () -> {
          AiOcrUpload leaf = uploadRepo().find(t.leafId);
          cleanup().fullyDeleteUploads(leaf, u -> uploadRepo().remove(u));
          assertTrue(Files.exists(t.blobPath), "never quarantined: the carrier still holds it");
        });
    JPA.em().clear();

    assertEquals(0, countById("AiOcrUpload", t.leafId), "the leaf really was deleted");
    assertEquals(1, countById("AiOcrUpload", t.carrierId), "the carrier survives");
    assertEquals(1, countById("MetaFile", t.blobId), "and so does the shared MetaFile row");
    assertTrue(Files.exists(t.blobPath), "the document is still there for the carrier");
    assertNotNull(uploadRepo().find(t.carrierId).getOriginalFile());
  }

  @Test
  void deletingTheWholeTreeRemovesTheBlobAfterCommit() {
    Tree t = sharedTree();

    inTransaction(
        () -> {
          AiOcrUpload carrier = uploadRepo().find(t.carrierId);
          cleanup().fullyDeleteUploads(carrier, u -> uploadRepo().remove(u));
          // Inside the transaction the blob is only MOVED ASIDE, never physically deleted.
          assertFalse(Files.exists(t.blobPath), "quarantined during the transaction");
        });
    JPA.em().clear();

    assertEquals(0, countById("AiOcrUpload", t.carrierId), "carrier removed");
    assertEquals(0, countById("AiOcrUpload", t.leafId), "leaf removed with it");
    assertEquals(0, countById("MetaFile", t.blobId), "last reference gone: MetaFile row removed");
    assertFalse(Files.exists(t.blobPath), "blob permanently gone after commit");
  }

  @Test
  void rollbackRestoresTheTreeAndTheBlob() {
    Tree t = sharedTree();

    assertThrows(
        RuntimeException.class,
        () ->
            inTransaction(
                () -> {
                  AiOcrUpload carrier = uploadRepo().find(t.carrierId);
                  cleanup().fullyDeleteUploads(carrier, u -> uploadRepo().remove(u));
                  throw new IllegalStateException("forced rollback after the delete work");
                }));
    JPA.em().clear();

    assertEquals(1, countById("AiOcrUpload", t.carrierId), "carrier row restored");
    assertEquals(1, countById("AiOcrUpload", t.leafId), "leaf row restored");
    assertEquals(1, countById("MetaFile", t.blobId), "MetaFile row restored");
    assertTrue(Files.exists(t.blobPath), "and the blob came back out of quarantine");
    assertEquals(t.blobId, uploadRepo().find(t.leafId).getOriginalFile().getId());
  }
}

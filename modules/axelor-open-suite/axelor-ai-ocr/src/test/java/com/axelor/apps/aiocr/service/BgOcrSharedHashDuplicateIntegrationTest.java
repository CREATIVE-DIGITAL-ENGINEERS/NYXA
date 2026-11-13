package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.apps.aiocr.service.ingest.AiOcrFileHash;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * The whole chain the shared MetaFile creates, over the real Guice/JPA stack: a {@code pdfSplit}
 * carrier and its invoice leaf hold one file and therefore one content hash, and a later DIRECT
 * upload of that same PDF must resolve to the LEAF — the document — never to the structural
 * carrier, and must inherit the leaf's Invoice.
 *
 * <p>The Invoice is only INSERTed and referenced through its proxy id, never SELECTed: the stock
 * {@code Invoice} carries a {@code @Formula} sub-select HSQLDB cannot parse (documented on {@code
 * OcrSharedAttachmentIntegrationTest}). That is a dialect limitation of the test database, not of
 * the code under test.
 */
class BgOcrSharedHashDuplicateIntegrationTest extends AiOcrTest {

  private static int seq = 0;

  private BgOcrIntakeService intake() {
    return Beans.get(BgOcrIntakeService.class);
  }

  /** The bytes every row in one scenario shares. */
  private static File samePdf(String marker) throws Exception {
    File f = File.createTempFile("ocr-shared-hash", ".pdf");
    Files.writeString(f.toPath(), "SHARED-BYTES-" + marker);
    return f;
  }

  private static final class Tree {
    long carrierId;
    long leafId;
    String hash;
    File source;
  }

  /**
   * Carrier + leaf sharing ONE MetaFile and one hash — the shape the whole-document promotion
   * produces. {@code leafInvoiceId} is null for the needs-review case.
   */
  private Tree sharedTree(String status, Long leafInvoiceId) {
    Tree t = new Tree();
    inTransaction(
        () -> {
          try {
            t.source = samePdf("" + (++seq));
            MetaFile blob = Beans.get(MetaFiles.class).upload(t.source);
            t.hash = AiOcrFileHash.sha256(MetaFiles.getPath(blob));

            AiOcrUpload carrier = new AiOcrUpload();
            carrier.setFileName("onepager.pdf");
            carrier.setOriginalFile(blob);
            carrier.setFileHash(t.hash);
            carrier.setCarrierKind(AiOcrUploadStatus.CARRIER_KIND_PDF_SPLIT);
            carrier.setStatusSelect(AiOcrUploadStatus.ARCHIVE);
            t.carrierId = JPA.save(carrier).getId();

            AiOcrUpload leaf = new AiOcrUpload();
            leaf.setImportArchive(carrier);
            leaf.setSegmentType(AiOcrUploadStatus.SEGMENT_TYPE_INVOICE);
            leaf.setFileName("onepager-invoice-p1-0.pdf");
            leaf.setOriginalFile(blob); // the SAME file, hence the same hash
            leaf.setFileHash(t.hash);
            leaf.setStatusSelect(status);
            if (leafInvoiceId != null) {
              leaf.setInvoice(JPA.em().getReference(Invoice.class, leafInvoiceId));
            }
            t.leafId = JPA.save(leaf).getId();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    return t;
  }

  private long newInvoice() {
    long[] id = new long[1];
    inTransaction(
        () -> {
          Invoice inv = new Invoice();
          inv.setStatusSelect(InvoiceRepository.STATUS_DRAFT);
          inv.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
          inv.setInvoiceId("SHARED-HASH-" + (++seq));
          inv.setInvoiceLineList(new java.util.ArrayList<>());
          id[0] = JPA.save(inv).getId();
        });
    return id[0];
  }

  /** A fresh direct upload of the identical bytes, run through the L1 guard. */
  private AiOcrUpload reupload(Tree t) {
    long[] newId = new long[1];
    boolean[] blocked = new boolean[1];
    inTransaction(
        () -> {
          try {
            // A direct re-upload stores its OWN blob; only the CONTENT is identical.
            MetaFile ownBlob = Beans.get(MetaFiles.class).upload(t.source);
            AiOcrUpload fresh = new AiOcrUpload();
            fresh.setFileName("onepager.pdf");
            fresh.setOriginalFile(ownBlob);
            fresh.setStatusSelect(AiOcrUploadStatus.PROCESSING);
            newId[0] = JPA.save(fresh).getId();
            blocked[0] =
                intake().blockIfByteIdenticalDuplicate(JPA.em().find(AiOcrUpload.class, newId[0]));
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    assertTrue(blocked[0], "byte-identical content must still be blocked as a duplicate");
    JPA.em().clear();
    return JPA.em().find(AiOcrUpload.class, newId[0]);
  }

  // --- (1)(2) the duplicate resolves to the leaf and keeps its invoice --------------------------

  @Test
  void aReuploadResolvesToTheInvoiceLeafAndInheritsItsInvoice() {
    long invoiceId = newInvoice();
    Tree t = sharedTree(AiOcrUploadStatus.COMPLETED, invoiceId);

    AiOcrUpload dup = reupload(t);

    assertEquals(AiOcrUploadStatus.DUPLICATE, dup.getStatusSelect());
    assertNotNull(dup.getInvoice(), "the duplicate must preserve the document it points at");
    assertEquals(invoiceId, dup.getInvoice().getId().longValue());
    assertTrue(
        dup.getExtractionErrors().contains("upload #" + t.leafId),
        "must name the leaf: " + dup.getExtractionErrors());
    assertFalse(
        dup.getExtractionErrors().contains("upload #" + t.carrierId),
        "and never the structural carrier");
  }

  // --- (3) a needsReview leaf with no document still beats the carrier --------------------------

  @Test
  void aNeedsReviewLeafIsStillPreferredOverTheCarrier() {
    Tree t = sharedTree(AiOcrUploadStatus.NEEDS_REVIEW, null);

    AiOcrUpload dup = reupload(t);

    assertEquals(AiOcrUploadStatus.DUPLICATE, dup.getStatusSelect());
    assertNull(
        dup.getInvoice(), "there is no document yet — nothing to inherit, and none invented");
    assertTrue(
        dup.getExtractionErrors().contains("upload #" + t.leafId),
        "the review leaf is the meaningful row: " + dup.getExtractionErrors());
  }

  // --- (4) repeatable, and not an artefact of row order -----------------------------------------

  @Test
  void theSameTreeResolvesToTheSameRowEveryTime() {
    // Deliberately the document-less tree: what is under test here is that the SAME row is chosen
    // on every run, and an Invoice adds nothing to that while costing this harness a SELECT it
    // cannot parse (the @Formula limitation noted on the class). Invoice preservation is covered by
    // aReuploadResolvesToTheInvoiceLeafAndInheritsItsInvoice.
    Tree t = sharedTree(AiOcrUploadStatus.NEEDS_REVIEW, null);

    // Three independent re-uploads of the identical bytes must all name the same source. Each new
    // duplicate is itself excluded from later lookups (DUPLICATE is an ignored status), so the
    // candidate set stays carrier+leaf and the answer must not drift.
    for (int i = 0; i < 3; i++) {
      AiOcrUpload dup = reupload(t);
      assertTrue(
          dup.getExtractionErrors().contains("upload #" + t.leafId),
          "run " + i + " picked a different row: " + dup.getExtractionErrors());
    }
  }

  // --- (5) the ordinary single-row case is untouched ---------------------------------------------

  @Test
  void anOrdinaryNonSharedUploadStillResolvesToItself() {
    long[] originalId = new long[1];
    File[] src = new File[1];
    inTransaction(
        () -> {
          try {
            src[0] = samePdf("plain-" + (++seq));
            MetaFile blob = Beans.get(MetaFiles.class).upload(src[0]);
            AiOcrUpload original = new AiOcrUpload();
            original.setFileName("plain.pdf");
            original.setOriginalFile(blob);
            original.setFileHash(AiOcrFileHash.sha256(MetaFiles.getPath(blob)));
            original.setStatusSelect(AiOcrUploadStatus.COMPLETED);
            originalId[0] = JPA.save(original).getId();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });

    Tree t = new Tree();
    t.source = src[0];
    AiOcrUpload dup = reupload(t);

    assertEquals(AiOcrUploadStatus.DUPLICATE, dup.getStatusSelect());
    assertTrue(
        dup.getExtractionErrors().contains("upload #" + originalId[0]),
        "one candidate, same answer as before: " + dup.getExtractionErrors());
  }
}

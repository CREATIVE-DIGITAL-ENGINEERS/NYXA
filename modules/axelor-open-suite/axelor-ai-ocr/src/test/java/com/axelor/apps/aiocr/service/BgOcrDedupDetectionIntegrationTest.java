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
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import java.io.File;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * DB-backed proof of the two-layer duplicate detection:
 *
 * <ul>
 *   <li><b>L1</b> — an exact re-upload (same company + identical bytes) is blocked BEFORE OCR:
 *       {@code blockIfByteIdenticalDuplicate} returns true and flags the upload DUPLICATE, so the
 *       extractor is never called. Scoped per company.
 *   <li><b>L2</b> — a re-scan / re-export (different bytes → different hash) slips past L1 but is
 *       caught AFTER OCR by the semantic key (company | supplier | docType | invoiceNo).
 * </ul>
 */
class BgOcrDedupDetectionIntegrationTest extends AiOcrTest {

  private BgOcrIntakeService svc() {
    return Beans.get(BgOcrIntakeService.class);
  }

  private MetaFile blob(String content) throws Exception {
    File tmp = File.createTempFile("ocr-dedup", ".pdf");
    Files.writeString(tmp.toPath(), content);
    return Beans.get(MetaFiles.class).upload(tmp);
  }

  private Company company(String code) {
    Company c =
        Beans.get(CompanyRepository.class)
            .all()
            .filter("self.code = :c")
            .bind("c", code)
            .fetchOne();
    if (c == null) {
      c = new Company();
      c.setName(code);
      c.setCode(code);
      c = JPA.save(c);
    }
    return c;
  }

  @Test
  void l1BlocksByteIdenticalReuploadBeforeOcr() {
    inTransaction(
        () -> {
          try {
            Company a = company("DEDUP-A");
            MetaFile file = blob("IDENTICAL-BYTES-1");
            String hash = AiOcrFileHash.sha256(MetaFiles.getPath(file));

            // Already imported: a completed upload of this exact file for company A.
            AiOcrUpload prior = new AiOcrUpload();
            prior.setCompany(a);
            prior.setOriginalFile(file);
            prior.setFileHash(hash);
            prior.setStatusSelect(AiOcrUploadStatus.COMPLETED);
            prior = JPA.save(prior);

            // Re-upload of the same bytes (no hash yet — the manual path).
            AiOcrUpload reupload = new AiOcrUpload();
            reupload.setCompany(a);
            reupload.setOriginalFile(file);
            reupload.setStatusSelect(AiOcrUploadStatus.QUEUED);
            reupload = JPA.save(reupload);

            boolean blocked = svc().blockIfByteIdenticalDuplicate(reupload);

            assertTrue(blocked, "byte-identical re-upload must be blocked at L1");
            assertEquals(AiOcrUploadStatus.DUPLICATE, reupload.getStatusSelect());
            assertEquals(
                hash, reupload.getFileHash(), "hash computed + persisted on the re-upload");
            assertNull(reupload.getInvoice(), "prior had no invoice → none linked");

            // Company scoping: the same bytes under a DIFFERENT company are not a duplicate.
            Company b = company("DEDUP-B");
            AiOcrUpload other = new AiOcrUpload();
            other.setCompany(b);
            other.setOriginalFile(file);
            other.setStatusSelect(AiOcrUploadStatus.QUEUED);
            other = JPA.save(other);
            assertFalse(
                svc().blockIfByteIdenticalDuplicate(other),
                "same bytes, different company → not a duplicate");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  @Test
  void semanticKeyCatchesReScanThatL1Misses() {
    inTransaction(
        () -> {
          try {
            Company a = company("DEDUP-C");
            String key = a.getId() + "|831641791|INVOICE|0000000065";

            MetaFile scan1 = blob("SCAN-VERSION-1");
            String hash1 = AiOcrFileHash.sha256(MetaFiles.getPath(scan1));
            Invoice inv = supplierInvoice(a);
            AiOcrUpload prior = new AiOcrUpload();
            prior.setCompany(a);
            prior.setOriginalFile(scan1);
            prior.setFileHash(hash1);
            prior.setDocKey(key);
            prior.setInvoice(inv);
            prior.setStatusSelect(AiOcrUploadStatus.COMPLETED);
            prior = JPA.save(prior);

            // A re-scan: SAME invoice, DIFFERENT bytes → different hash.
            MetaFile scan2 = blob("SCAN-VERSION-2-different-bytes");
            String hash2 = AiOcrFileHash.sha256(MetaFiles.getPath(scan2));
            AiOcrUpload reScan = new AiOcrUpload();
            reScan.setCompany(a);
            reScan.setOriginalFile(scan2);
            reScan.setStatusSelect(AiOcrUploadStatus.QUEUED);
            reScan = JPA.save(reScan);

            // L1 misses it (bytes differ) ...
            assertNull(
                svc().findByteIdenticalDuplicate(reScan, hash2, a.getId()),
                "different bytes → L1 finds nothing");

            // ... but the semantic key catches it (sibling upload with the same key links an
            // invoice).
            Invoice found =
                svc().findExistingInvoiceForKey(key, reScan.getId(), a.getId(), null, null);
            assertNotNull(found, "re-scan caught by the semantic key");
            assertEquals(inv.getId(), found.getId());
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  private Invoice supplierInvoice(Company company) {
    Invoice inv = new Invoice();
    inv.setStatusSelect(InvoiceRepository.STATUS_DRAFT);
    inv.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    inv.setCompany(company);
    inv.setInvoiceId("IT-" + System.nanoTime());
    inv.setInvoiceLineList(new java.util.ArrayList<>());
    return JPA.save(inv);
  }

  @Test
  void byteCheckIgnoresDeletedAndReprocessedHistory() {
    inTransaction(
        () -> {
          try {
            Company a = company("DEDUP-D");
            MetaFile file = blob("REPROCESS-BYTES");
            String hash = AiOcrFileHash.sha256(MetaFiles.getPath(file));

            // A prior upload whose invoice was deleted from Suppl. Invoices (unlinked, kept).
            AiOcrUpload deleted = new AiOcrUpload();
            deleted.setCompany(a);
            deleted.setOriginalFile(file);
            deleted.setFileHash(hash);
            deleted.setStatusSelect(AiOcrUploadStatus.DELETED_FROM_SUPPL_INVOICES);
            JPA.save(deleted);

            // Re-uploading the same bytes must NOT be blocked against that deleted history.
            AiOcrUpload reupload = new AiOcrUpload();
            reupload.setCompany(a);
            reupload.setOriginalFile(file);
            reupload.setStatusSelect(AiOcrUploadStatus.QUEUED);
            reupload = JPA.save(reupload);

            assertFalse(
                svc().blockIfByteIdenticalDuplicate(reupload),
                "a deleted/reprocessable prior is not an L1 duplicate");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }
}

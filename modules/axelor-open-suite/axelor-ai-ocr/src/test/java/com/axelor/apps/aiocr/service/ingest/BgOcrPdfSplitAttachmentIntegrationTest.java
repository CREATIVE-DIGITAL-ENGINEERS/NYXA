package com.axelor.apps.aiocr.service.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.aiocr.service.optimize.PdfSplitService;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * DB-backed proof of the split ATTACHMENT lifecycle (the part the pure-segmenter test cannot
 * cover): running {@link AiOcrPdfSplitService#split} on a real "invoice + duplicate copy + stock
 * receipt + its copy" PDF must produce exactly ONE invoice child whose {@code originalFile} is the
 * invoice's OWN one-page slice (never the whole carrier PDF, never the copy), with the stock
 * receipt attached as a SEPARATE file — and no records for the duplicate copies. Uses the stored
 * Azure fixture as the split source (no Azure call). Skips gracefully when the untracked test-data
 * files are absent.
 */
class BgOcrPdfSplitAttachmentIntegrationTest extends AiOcrTest {

  @Test
  void doubledInvoiceAttachesOnlyItsOwnSlices() {
    // p1 invoice, p2 copy, p3 stock receipt, p4 receipt copy. The receipt total (336.55) matches
    // the
    // invoice total, so it attaches by CONTENT; the duplicate copy (same No) is detected and
    // dropped, so exactly ONE 1-page receipt is attached.
    runSplitAndAssert("doubledinvoice_doubleddeliverynote", 1);
  }

  @Test
  void doubledInvoiceWithBlankPageAttachesOnlyItsOwnSlices() {
    // p1 invoice, p2 copy, p3 letterhead + p4 stock receipt, p5 letterhead + p6 stock copy, p7
    // tail. The invoice slice is still just p1; ONE stock receipt (matched by its 236.49 total) is
    // attached and its duplicate copy dropped.
    runSplitAndAssert("doubledinvoice_blankpage_doubleddeliverynote", -1);
  }

  private void runSplitAndAssert(String base, int expectedStockPages) {
    Path pdf = testData(base + ".pdf");
    Path fixture = testData("fixtures/" + base + ".azure-full.json");
    assumeTrue(
        pdf != null && Files.exists(pdf) && fixture != null && Files.exists(fixture),
        "Requires test-data/" + base + ".pdf and its stitched Azure fixture.");

    inTransaction(
        () -> {
          try {
            MetaFile original = Beans.get(MetaFiles.class).upload(pdf.toFile());
            AiOcrUpload carrier = new AiOcrUpload();
            carrier.setOriginalFile(original);
            carrier.setFileName(base + ".pdf");
            carrier.setSplitSourceJson(
                new String(Files.readAllBytes(fixture), StandardCharsets.UTF_8));
            carrier.setStatusSelect(AiOcrUploadStatus.EXTRACTING);
            carrier = JPA.save(carrier);

            Beans.get(AiOcrPdfSplitService.class).split(carrier.getId());

            List<AiOcrUpload> children =
                Beans.get(AiOcrUploadRepository.class)
                    .all()
                    .filter("self.importArchive.id = :c")
                    .bind("c", carrier.getId())
                    .fetch();

            // Exactly one record (the invoice) — the duplicate copy created none.
            assertEquals(1, children.size(), "expected exactly one invoice child");
            AiOcrUpload invoice = children.get(0);
            assertEquals(
                AiOcrUploadStatus.SEGMENT_TYPE_INVOICE,
                invoice.getSegmentType(),
                "the single child must be the invoice");

            // Its attachment is its OWN one page — never the copy, never the whole carrier PDF.
            assertEquals(
                Integer.valueOf(1),
                pageCount(invoice.getOriginalFile()),
                "invoice slice must be exactly its own single page");

            // Exactly ONE stock receipt is attached as a SEPARATE file (its duplicate copy
            // dropped),
            // matched to the invoice by content — not folded into the invoice slice.
            assertNotNull(invoice.getStockReceiptFileSet(), "stock receipt set must exist");
            assertEquals(
                1,
                invoice.getStockReceiptFileSet().size(),
                "exactly one stock receipt (the duplicate copy is dropped)");
            MetaFile receipt = invoice.getStockReceiptFileSet().iterator().next();
            if (expectedStockPages > 0) {
              assertEquals(
                  Integer.valueOf(expectedStockPages),
                  pageCount(receipt),
                  "stock receipt attachment page count");
            }
            assertNull(invoice.getPosReceiptFile(), "no POS/fiscal receipt in this document");

            // The carrier still holds the full original PDF (accessible only via Upload Invoices).
            AiOcrUpload reloaded = Beans.get(AiOcrUploadRepository.class).find(carrier.getId());
            assertNotNull(reloaded.getOriginalFile());
            assertNull(reloaded.getInvoice(), "the carrier itself is never routed to an invoice");
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
  }

  private static Integer pageCount(MetaFile file) {
    assertNotNull(file, "expected an attached file");
    Path path = MetaFiles.getPath(file);
    return Beans.get(PdfSplitService.class).pageCountOrNull(path);
  }

  private static Path testData(String relative) {
    Path dir = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8 && dir != null; i++) {
      Path candidate = dir.resolve("test-data").resolve(relative);
      if (Files.exists(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    return null;
  }
}

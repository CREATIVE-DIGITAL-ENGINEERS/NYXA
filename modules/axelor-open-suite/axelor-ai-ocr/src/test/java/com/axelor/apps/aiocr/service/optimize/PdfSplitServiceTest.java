package com.axelor.apps.aiocr.service.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Security/regression tests for the PDFBox slicer: it must extract a correct contiguous page range,
 * reject an invalid range, and fail SAFELY on a malformed/untrusted PDF (a clear exception or
 * {@code null}, never a crash that would poison the background worker).
 */
class PdfSplitServiceTest {

  private static Path plainPdf(Path dir, int pages) throws IOException {
    Path pdf = dir.resolve("plain-" + pages + ".pdf");
    try (PDDocument doc = new PDDocument()) {
      for (int i = 0; i < pages; i++) {
        doc.addPage(new PDPage());
      }
      doc.save(pdf.toFile());
    }
    return pdf;
  }

  @Test
  void slicesAContiguousRange(@TempDir Path dir) throws Exception {
    PdfSplitService svc = new PdfSplitService();
    Path src = plainPdf(dir, 4);
    assertEquals(Integer.valueOf(4), svc.pageCountOrNull(src));

    Path slice = svc.slicePages(src, 2, 3);
    try {
      assertEquals(Integer.valueOf(2), svc.pageCountOrNull(slice));
    } finally {
      Files.deleteIfExists(slice);
    }
  }

  @Test
  void rejectsAnInvalidRange(@TempDir Path dir) throws Exception {
    PdfSplitService svc = new PdfSplitService();
    Path src = plainPdf(dir, 2);
    // 5-6 is entirely past the end → clamped from>to → IOException, no partial output.
    assertThrows(IOException.class, () -> svc.slicePages(src, 5, 6));
  }

  @Test
  void malformedSourceFailsSafely(@TempDir Path dir) throws Exception {
    PdfSplitService svc = new PdfSplitService();
    Path junk = dir.resolve("broken.pdf");
    Files.write(junk, "%PDF-1.7 not actually a pdf".getBytes(StandardCharsets.UTF_8));

    assertNull(svc.pageCountOrNull(junk), "unreadable PDF → null page count, no crash");
    assertThrows(IOException.class, () -> svc.slicePages(junk, 1, 1));
    assertNull(
        svc.cropRegion(junk, 1, new double[] {10, 10, 100, 100}, 200, 200),
        "unreadable PDF → null crop, no crash");
  }

  @Test
  void cropRejectsAWholePageBoxAsUnreliable(@TempDir Path dir) throws Exception {
    PdfSplitService svc = new PdfSplitService();
    Path src = plainPdf(dir, 1);
    // A box that covers ~the whole page is not a reliable same-page split → null (caller keeps
    // page).
    assertNull(svc.cropRegion(src, 1, new double[] {0, 0, 1000, 1000}, 1000, 1000));
  }

  @Test
  void tempSliceFilesDoNotLeakOnSuccess(@TempDir Path dir) throws Exception {
    PdfSplitService svc = new PdfSplitService();
    Path src = plainPdf(dir, 3);
    Path slice = svc.slicePages(src, 1, 1);
    assertTrue(Files.exists(slice));
    // The caller owns deletion; prove the returned path is a real deletable temp file.
    Files.deleteIfExists(slice);
  }
}

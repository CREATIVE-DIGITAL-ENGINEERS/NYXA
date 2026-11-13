package com.axelor.apps.aiocr.service.optimize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 2 (PDF performance): {@link PdfSplitService.SliceSession} must parse the source PDF ONCE
 * for all of a document's Azure chunks, while producing byte-for-byte the same slices the
 * one-shot-per-call API produced — same pages, same order, same dimensions/rotation/crop box, same
 * embedded resources — and must leak neither a PDFBox document nor a temp file.
 */
class PdfSliceSessionTest {

  /** Counts every PDF parse, which is exactly what the session exists to reduce. */
  private static final class CountingSplitService extends PdfSplitService {
    final AtomicInteger loads = new AtomicInteger();

    @Override
    protected PDDocument load(Path src) throws IOException {
      loads.incrementAndGet();
      return super.load(src);
    }
  }

  /** Pages carrying unique text, so order and content can be asserted after slicing. */
  private static Path textPdf(Path dir, int pages) throws IOException {
    Path pdf = dir.resolve("text-" + pages + ".pdf");
    try (PDDocument doc = new PDDocument()) {
      for (int i = 1; i <= pages; i++) {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
          cs.beginText();
          cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 24);
          cs.newLineAtOffset(72, 700);
          cs.showText("PAGE-MARKER-" + i);
          cs.endText();
        }
      }
      doc.save(pdf.toFile());
    }
    return pdf;
  }

  private static String textOf(Path pdf) throws IOException {
    try (PDDocument doc = org.apache.pdfbox.Loader.loadPDF(pdf.toFile())) {
      return new PDFTextStripper().getText(doc);
    }
  }

  // -------------------------------------------------------------------------
  // 1. The source is parsed once per document, not once per chunk
  // -------------------------------------------------------------------------

  @Test
  void aSessionParsesTheSourceOnceForEveryChunk(@TempDir Path dir) throws Exception {
    CountingSplitService svc = new CountingSplitService();
    Path src = textPdf(dir, 10);

    List<Path> slices = new ArrayList<>();
    try (PdfSplitService.SliceSession session = svc.openSession(src)) {
      assertEquals(10, session.pageCount(), "page count comes from the single parse");
      for (int from = 1; from <= 10; from += 2) {
        slices.add(session.slicePages(from, from + 1));
      }
    }
    try {
      assertEquals(5, slices.size(), "F0 chunking: 10 pages → five 2-page requests");
      assertEquals(1, svc.loads.get(), "one parse for the whole document, not one per chunk");
    } finally {
      for (Path p : slices) {
        Files.deleteIfExists(p);
      }
    }
  }

  @Test
  void theOneShotApiStillParsesPerCallAndIsWhatTheSessionReplaces(@TempDir Path dir)
      throws Exception {
    CountingSplitService svc = new CountingSplitService();
    Path src = textPdf(dir, 10);
    for (int from = 1; from <= 10; from += 2) {
      Files.deleteIfExists(svc.slicePages(src, from, from + 1));
    }
    // The measured "before": five chunks cost five full parses of the same bytes.
    assertEquals(5, svc.loads.get());
  }

  // -------------------------------------------------------------------------
  // 2-3. Even/odd page counts, page order and page content
  // -------------------------------------------------------------------------

  @Test
  void slicesAnOddPageDocumentIntoChunksWithTheRightPagesInOrder(@TempDir Path dir)
      throws Exception {
    PdfSplitService svc = new PdfSplitService();
    Path src = textPdf(dir, 7);

    List<Path> slices = new ArrayList<>();
    try (PdfSplitService.SliceSession session = svc.openSession(src)) {
      for (int from = 1; from <= 7; from += 2) {
        slices.add(session.slicePages(from, Math.min(from + 1, 7)));
      }
    }
    try {
      assertEquals(4, slices.size(), "7 pages → 2+2+2+1");
      int[] expectedPages = {2, 2, 2, 1};
      for (int i = 0; i < slices.size(); i++) {
        Path slice = slices.get(i);
        assertEquals(
            Integer.valueOf(expectedPages[i]),
            svc.pageCountOrNull(slice),
            "chunk " + i + " page count (last chunk of an odd document is a single page)");
        String text = textOf(slice);
        for (int p = 1; p <= expectedPages[i]; p++) {
          int sourcePage = i * 2 + p;
          assertTrue(
              text.contains("PAGE-MARKER-" + sourcePage),
              "chunk " + i + " must carry source page " + sourcePage);
        }
      }
      // Order, not just membership: page 3 must precede page 4 inside the second chunk.
      String second = textOf(slices.get(1));
      assertTrue(
          second.indexOf("PAGE-MARKER-3") < second.indexOf("PAGE-MARKER-4"), "page order kept");
    } finally {
      for (Path p : slices) {
        Files.deleteIfExists(p);
      }
    }
  }

  @Test
  void sessionSlicesMatchOneShotSlicesPageForPage(@TempDir Path dir) throws Exception {
    PdfSplitService svc = new PdfSplitService();
    Path src = textPdf(dir, 5);
    try (PdfSplitService.SliceSession session = svc.openSession(src)) {
      for (int from = 1; from <= 5; from += 2) {
        int to = Math.min(from + 1, 5);
        Path viaSession = session.slicePages(from, to);
        Path viaOneShot = svc.slicePages(src, from, to);
        try {
          assertEquals(textOf(viaOneShot), textOf(viaSession), "same text, same order");
          assertEquals(svc.pageCountOrNull(viaOneShot), svc.pageCountOrNull(viaSession));
        } finally {
          Files.deleteIfExists(viaSession);
          Files.deleteIfExists(viaOneShot);
        }
      }
    }
  }

  // -------------------------------------------------------------------------
  // 4. Page geometry: dimensions, rotation, crop box
  // -------------------------------------------------------------------------

  @Test
  void preservesPageDimensionsRotationAndCropBox(@TempDir Path dir) throws Exception {
    PdfSplitService svc = new PdfSplitService();
    Path src = dir.resolve("geometry.pdf");
    try (PDDocument doc = new PDDocument()) {
      PDPage a4 = new PDPage(PDRectangle.A4);
      a4.setRotation(90);
      a4.setCropBox(new PDRectangle(10, 20, 400, 600));
      doc.addPage(a4);
      PDPage a5 = new PDPage(PDRectangle.A5);
      a5.setRotation(270);
      doc.addPage(a5);
      doc.save(src.toFile());
    }

    Path slice;
    try (PdfSplitService.SliceSession session = svc.openSession(src)) {
      slice = session.slicePages(1, 2);
    }
    try (PDDocument out = org.apache.pdfbox.Loader.loadPDF(slice.toFile())) {
      PDPage first = out.getPage(0);
      assertEquals(90, first.getRotation());
      assertEquals(PDRectangle.A4.getWidth(), first.getMediaBox().getWidth(), 0.01f);
      assertEquals(PDRectangle.A4.getHeight(), first.getMediaBox().getHeight(), 0.01f);
      assertEquals(10f, first.getCropBox().getLowerLeftX(), 0.01f);
      assertEquals(400f, first.getCropBox().getWidth(), 0.01f);

      PDPage second = out.getPage(1);
      assertEquals(270, second.getRotation());
      assertEquals(PDRectangle.A5.getWidth(), second.getMediaBox().getWidth(), 0.01f);
    } finally {
      Files.deleteIfExists(slice);
    }
  }

  // -------------------------------------------------------------------------
  // 5. Image-heavy (scanned) documents keep their image resources
  // -------------------------------------------------------------------------

  @Test
  void keepsEmbeddedImagesOfAScannedStylePdf(@TempDir Path dir) throws Exception {
    PdfSplitService svc = new PdfSplitService();
    Path src = dir.resolve("scanned.pdf");
    try (PDDocument doc = new PDDocument()) {
      for (int i = 0; i < 3; i++) {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        BufferedImage img = new BufferedImage(120, 160, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 120; x++) {
          for (int y = 0; y < 160; y++) {
            img.setRGB(x, y, ((x + y + i) % 2 == 0 ? Color.WHITE : Color.BLACK).getRGB());
          }
        }
        PDImageXObject xobj = LosslessFactory.createFromImage(doc, img);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
          cs.drawImage(xobj, 40, 400, 240, 320);
        }
      }
      doc.save(src.toFile());
    }

    Path slice;
    try (PdfSplitService.SliceSession session = svc.openSession(src)) {
      slice = session.slicePages(2, 3);
    }
    try (PDDocument out = org.apache.pdfbox.Loader.loadPDF(slice.toFile())) {
      assertEquals(2, out.getNumberOfPages());
      for (int i = 0; i < 2; i++) {
        assertNotNull(out.getPage(i).getResources(), "sliced page keeps its resources");
        assertTrue(
            out.getPage(i).getResources().getXObjectNames().iterator().hasNext(),
            "sliced page " + i + " still carries its scanned image");
      }
    } finally {
      Files.deleteIfExists(slice);
    }
  }

  // -------------------------------------------------------------------------
  // 6-7. Invalid input, resource lifecycle, no temp-file leaks
  // -------------------------------------------------------------------------

  @Test
  void openingACorruptPdfFailsCleanly(@TempDir Path dir) throws Exception {
    PdfSplitService svc = new PdfSplitService();
    Path junk = dir.resolve("broken.pdf");
    Files.write(junk, "%PDF-1.7 not actually a pdf".getBytes(StandardCharsets.UTF_8));
    assertThrows(IOException.class, () -> svc.openSession(junk));
  }

  @Test
  void anInvalidRangeThrowsAndLeavesNoTempFile(@TempDir Path dir) throws Exception {
    PdfSplitService svc = new PdfSplitService();
    Path src = textPdf(dir, 2);
    long before = countSliceTemps();
    try (PdfSplitService.SliceSession session = svc.openSession(src)) {
      assertThrows(IOException.class, () -> session.slicePages(5, 6));
    }
    assertEquals(before, countSliceTemps(), "a rejected range must not leave a temp slice behind");
  }

  @Test
  void aClosedSessionRefusesFurtherSlicingAndClosesIdempotently(@TempDir Path dir)
      throws Exception {
    PdfSplitService svc = new PdfSplitService();
    Path src = textPdf(dir, 4);
    PdfSplitService.SliceSession session = svc.openSession(src);
    Files.deleteIfExists(session.slicePages(1, 2));
    session.close();
    session.close(); // idempotent, never throws
    assertThrows(IOException.class, () -> session.slicePages(3, 4));
  }

  @Test
  void slicingManyChunksLeavesOnlyTheReturnedTempFiles(@TempDir Path dir) throws Exception {
    PdfSplitService svc = new PdfSplitService();
    Path src = textPdf(dir, 12);
    long before = countSliceTemps();
    List<Path> slices = new ArrayList<>();
    try (PdfSplitService.SliceSession session = svc.openSession(src)) {
      for (int from = 1; from <= 12; from += 2) {
        slices.add(session.slicePages(from, from + 1));
      }
    }
    assertEquals(before + 6, countSliceTemps(), "exactly the six returned slices exist");
    for (Path p : slices) {
      Files.deleteIfExists(p);
    }
    assertEquals(before, countSliceTemps(), "all temp slices are deletable by the caller");
    assertFalse(slices.isEmpty());
  }

  /** How many {@code aiocr-slice-*} temp files currently exist in the JVM temp dir. */
  private static long countSliceTemps() throws IOException {
    Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
    try (java.util.stream.Stream<Path> s = Files.list(tmp)) {
      return s.filter(p -> p.getFileName().toString().startsWith("aiocr-slice-")).count();
    }
  }
}

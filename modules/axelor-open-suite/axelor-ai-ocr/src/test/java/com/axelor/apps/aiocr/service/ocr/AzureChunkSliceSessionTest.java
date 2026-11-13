package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.aiocr.service.optimize.PdfSplitService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 2: the Azure F0 chunk loop must keep submitting the same 2-page slices it always did, while
 * parsing the source PDF exactly once for the whole document instead of once per chunk.
 *
 * <p>No Azure call is made — submit/poll are stubbed at the same seams the other extractor tests
 * use; what is asserted is the PDF that WOULD have been sent.
 */
class AzureChunkSliceSessionTest {

  private static final String SUCCEEDED =
      "{\"status\":\"succeeded\",\"analyzeResult\":{\"content\":\"c\",\"pages\":[]}}";

  /** Counts PDF parses so "once per document, not once per chunk" is a measurement, not a claim. */
  private static final class CountingSplitService extends PdfSplitService {
    final AtomicInteger loads = new AtomicInteger();

    @Override
    protected PDDocument load(Path src) throws IOException {
      loads.incrementAndGet();
      return super.load(src);
    }
  }

  /** Captures every body that would have gone to Azure. */
  private static final class CapturingExtractor extends AzureDocumentIntelligenceExtractor {
    final List<byte[]> submitted = new ArrayList<>();

    @Override
    protected String endpoint() {
      return "https://x.cognitiveservices.azure.com";
    }

    @Override
    protected String key() {
      return "k";
    }

    @Override
    protected int maxRetries() {
      return 1;
    }

    @Override
    protected void sleepBeforeNextPoll(String r) {}

    @Override
    protected AzureResponse httpSubmit(String u, byte[] b, String m) {
      submitted.add(b.clone());
      return new AzureResponse(202, "", "https://x/op/1", null);
    }

    @Override
    protected AzureResponse httpGet(String u) {
      return new AzureResponse(200, SUCCEEDED, null, null);
    }
  }

  private static Path pdf(Path dir, int pages) throws IOException {
    Path out = dir.resolve("src-" + pages + ".pdf");
    try (PDDocument doc = new PDDocument()) {
      for (int i = 0; i < pages; i++) {
        doc.addPage(new PDPage(PDRectangle.A4));
      }
      doc.save(out.toFile());
    }
    return out;
  }

  private static int pagesOf(byte[] pdfBytes) throws IOException {
    try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
      return doc.getNumberOfPages();
    }
  }

  @Test
  void fiveChunksOfATenPageDocumentCostOneParseAndSubmitTwoPagesEach(@TempDir Path dir)
      throws Exception {
    CountingSplitService splitter = new CountingSplitService();
    CapturingExtractor extractor = new CapturingExtractor();
    Path src = pdf(dir, 10);
    long cap = 4L * 1024 * 1024; // F0 per-request byte limit

    List<String> chunkJsons = new ArrayList<>();
    List<OcrExtractResult.PageCompression> compressions = new ArrayList<>();
    try (PdfSplitService.SliceSession session = splitter.openSession(src)) {
      for (int from = 1; from <= 10; from += 2) {
        assertNull(
            extractor.ocrChunkRange(session, from, from + 1, cap, chunkJsons, compressions),
            "chunk " + from + " must succeed");
      }
    }

    assertEquals(1, splitter.loads.get(), "ONE parse of the source for all five chunks");
    assertEquals(5, extractor.submitted.size(), "F0: 10 pages → five requests");
    assertEquals(5, chunkJsons.size(), "every chunk contributed its analyzeResult");
    assertTrue(compressions.isEmpty(), "small pages never trigger lossy transport compression");
    for (byte[] body : extractor.submitted) {
      assertEquals(2, pagesOf(body), "F0: at most 2 pages per analyze request");
      assertTrue(body.length <= cap, "F0: at most 4 MB per analyze request");
    }
  }

  @Test
  void anOddPageDocumentEndsWithASinglePageRequest(@TempDir Path dir) throws Exception {
    CountingSplitService splitter = new CountingSplitService();
    CapturingExtractor extractor = new CapturingExtractor();
    Path src = pdf(dir, 5);
    long cap = 4L * 1024 * 1024;

    List<String> chunkJsons = new ArrayList<>();
    List<OcrExtractResult.PageCompression> compressions = new ArrayList<>();
    try (PdfSplitService.SliceSession session = splitter.openSession(src)) {
      for (int from = 1; from <= 5; from += 2) {
        int to = Math.min(from + 1, 5);
        assertNull(extractor.ocrChunkRange(session, from, to, cap, chunkJsons, compressions));
      }
    }

    assertEquals(1, splitter.loads.get());
    assertEquals(3, extractor.submitted.size(), "5 pages → 2+2+1");
    assertEquals(2, pagesOf(extractor.submitted.get(0)));
    assertEquals(2, pagesOf(extractor.submitted.get(1)));
    assertEquals(1, pagesOf(extractor.submitted.get(2)));
  }

  @Test
  void anOversizedTwoPageChunkIsReslicedPerPageFromTheSameParse(@TempDir Path dir)
      throws Exception {
    CountingSplitService splitter = new CountingSplitService();
    CapturingExtractor extractor = new CapturingExtractor();
    Path src = pdf(dir, 2);
    // A cap below the 2-page slice but above a 1-page slice forces the lossless per-page re-slice
    // (never the lossy path, which only applies to a single page that is STILL over the cap).
    long twoPageBytes;
    long onePageBytes;
    try (PdfSplitService.SliceSession s = splitter.openSession(src)) {
      Path two = s.slicePages(1, 2);
      Path one = s.slicePages(1, 1);
      twoPageBytes = Files.size(two);
      onePageBytes = Files.size(one);
      Files.deleteIfExists(two);
      Files.deleteIfExists(one);
    }
    assertTrue(onePageBytes < twoPageBytes, "fixture must have a smaller single-page slice");
    long cap = (onePageBytes + twoPageBytes) / 2;
    splitter.loads.set(0);

    List<String> chunkJsons = new ArrayList<>();
    List<OcrExtractResult.PageCompression> compressions = new ArrayList<>();
    try (PdfSplitService.SliceSession session = splitter.openSession(src)) {
      assertNull(extractor.ocrChunkRange(session, 1, 2, cap, chunkJsons, compressions));
    }

    assertEquals(
        1, splitter.loads.get(), "the re-slice reuses the SAME parse, it does not reparse");
    assertEquals(2, extractor.submitted.size(), "the oversized chunk became two single-page sends");
    assertEquals(1, pagesOf(extractor.submitted.get(0)));
    assertEquals(1, pagesOf(extractor.submitted.get(1)));
    assertTrue(compressions.isEmpty(), "lossless re-slice fit the cap → no lossy compression");
  }
}

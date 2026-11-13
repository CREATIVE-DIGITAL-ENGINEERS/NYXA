package com.axelor.apps.aiocr.devfixtures;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.axelor.apps.aiocr.service.ocr.AzureAnalyzeResultStitcher;
import com.axelor.apps.aiocr.service.ocr.AzureRawAnalyzer;
import com.axelor.apps.aiocr.service.optimize.PdfSplitService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

/**
 * DEV/TEST-ONLY fixture generator. Disabled unless {@code AIOCR_FIXTURES} is set, and needs real
 * Azure credentials in the environment ({@code AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT} / {@code
 * _KEY}).
 *
 * <p>Workaround for the Azure free-tier per-analysis page limit: each PDF in {@code test-data/} is
 * split with PDFBox into ≤2-page chunks (the last chunk may be 1 or 2 pages) and each chunk is
 * submitted <b>sequentially</b> (never concurrently) to the real Azure path. Every chunk's raw JSON
 * is saved, then {@link AzureAnalyzeResultStitcher} reconstructs one logical full-PDF {@code
 * analyzeResult} — so the Phase A segmenter can be driven offline from {@code
 * NAME.azure-full.json}.
 *
 * <p>This does NOT change production OCR, which still does a single Azure call and never chunks.
 * Output goes to {@code test-data/fixtures/} (untracked), never into module resources.
 *
 * <p>Run only the two-invoice file (default):
 *
 * <pre>AIOCR_FIXTURES=1 ./gradlew :modules:axelor-ai-ocr:test --tests '*AzureFixtureGenerator'
 * </pre>
 *
 * Run all: also set {@code AIOCR_FIXTURES_FILES=all} (or a comma-separated list of file names).
 */
class AzureFixtureGenerator {

  private static final int MAX_PAGES_PER_CHUNK = 2;

  private static final String[] DEFAULT_FILES = {"twosinglepageinvoices.pdf"};

  private static final String[] ALL_FILES = {
    "twosinglepageinvoices.pdf",
    "twopageinvoice.pdf",
    "doubledinvoice_doubleddeliverynote.pdf",
    "doubledinvoice_blankpage_doubleddeliverynote.pdf",
    "invoicesandother_plusreceipts.pdf",
    "manyinvoices_plusreciepts.pdf",
    "bankstatements.pdf"
  };

  @Test
  void generateFixtures() throws Exception {
    assumeTrue(
        env("AIOCR_FIXTURES") != null,
        "Set AIOCR_FIXTURES=1 (and Azure credentials) to run the dev fixture generator.");
    assumeTrue(
        !env0("AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT").isEmpty()
            && !env0("AZURE_DOCUMENT_INTELLIGENCE_KEY").isEmpty(),
        "Azure endpoint/key env vars are required.");

    Path testData = resolveTestDataDir();
    Path outDir = testData.resolve("fixtures");
    Files.createDirectories(outDir);

    for (String name : filesToProcess()) {
      Path pdf = testData.resolve(name);
      if (!Files.exists(pdf)) {
        System.out.println("[fixtures] SKIP missing " + pdf);
        continue;
      }
      generateForPdf(pdf, outDir);
    }
  }

  private void generateForPdf(Path pdf, Path outDir) throws Exception {
    String base = stripExt(pdf.getFileName().toString());
    PdfSplitService splitter = new PdfSplitService();
    AzureRawAnalyzer analyzer = new AzureRawAnalyzer();

    Integer pages = splitter.pageCountOrNull(pdf);
    if (pages == null || pages < 1) {
      System.out.println("[fixtures] SKIP unreadable " + pdf);
      return;
    }
    System.out.printf(
        "[fixtures] %s: %d page(s), chunking by %d%n", base, pages, MAX_PAGES_PER_CHUNK);

    List<String> chunkResults = new ArrayList<>();
    for (int from = 1; from <= pages; from += MAX_PAGES_PER_CHUNK) {
      int to = Math.min(from + MAX_PAGES_PER_CHUNK - 1, pages);
      submitRange(pdf, base, from, to, splitter, analyzer, outDir, chunkResults);
    }

    if (chunkResults.isEmpty()) {
      System.out.println("[fixtures]   no successful chunks — no stitched result for " + base);
      return;
    }
    String full = new AzureAnalyzeResultStitcher().stitch(chunkResults);
    Path fullPath = outDir.resolve(base + ".azure-full.json");
    write(fullPath, full);
    System.out.println("[fixtures]   stitched → " + fullPath);
  }

  /**
   * Submit pages {@code [from..to]} (≤2) to Azure and append the raw result. If Azure rejects the
   * slice because it is too large for the tier ({@code InvalidContentLength}), fall back to
   * submitting each page individually (scanned single pages are typically under the limit) so no
   * page is lost from the stitched result. Runs strictly sequentially — never concurrent.
   */
  private void submitRange(
      Path pdf,
      String base,
      int from,
      int to,
      PdfSplitService splitter,
      AzureRawAnalyzer analyzer,
      Path outDir,
      List<String> chunkResults)
      throws Exception {
    String label = base + ".p" + from + (to > from ? "-" + to : "");
    Path slice = null;
    try {
      slice = splitter.slicePages(pdf, from, to);
      byte[] bytes = Files.readAllBytes(slice);
      System.out.printf("[fixtures]   pages %d-%d (%d bytes) → Azure…%n", from, to, bytes.length);
      String json = analyzer.analyzeRaw(bytes, "application/pdf");
      write(outDir.resolve(label + ".azure.json"), json);
      chunkResults.add(json);
    } catch (AzureRawAnalyzer.AzureChunkException e) {
      if (isTooLarge(e)) {
        // The slice exceeds the tier's image-size limit. Splitting the PDF page-by-page is not
        // reliable (PDFBox can drop a page's large scanned image), so render each page to a
        // downscaled JPEG and OCR that instead — content + per-page coordinates are preserved.
        System.out.printf(
            "[fixtures]   pages %d-%d too large — rendering page(s) to JPEG…%n", from, to);
        deleteQuietly(slice);
        slice = null;
        for (int p = from; p <= to; p++) {
          submitPageAsImage(pdf, base, p, analyzer, outDir, chunkResults);
        }
        return;
      }
      // Save the failure so tier rejections stay inspectable; the page is simply absent upstream.
      write(outDir.resolve(label + ".error.txt"), "HTTP " + e.status + "\n" + e.body);
      System.out.printf("[fixtures]   pages %d-%d FAILED: %s%n", from, to, e.getMessage());
    } finally {
      deleteQuietly(slice);
    }
  }

  /** Render one page to a downscaled JPEG (under the tier size cap) and OCR it as an image. */
  private void submitPageAsImage(
      Path pdf, String base, int page, AzureRawAnalyzer analyzer, Path outDir, List<String> results)
      throws Exception {
    String label = base + ".p" + page + ".img";
    byte[] jpeg;
    try {
      jpeg = renderJpegUnderCap(pdf, page);
    } catch (IOException e) {
      write(outDir.resolve(label + ".error.txt"), "render failed: " + e.getMessage());
      System.out.printf("[fixtures]   page %d render FAILED: %s%n", page, e.getMessage());
      return;
    }
    if (jpeg == null) {
      write(outDir.resolve(label + ".error.txt"), "could not render page under size cap");
      System.out.printf("[fixtures]   page %d could not be rendered under cap%n", page);
      return;
    }
    try {
      System.out.printf("[fixtures]   page %d (image, %d bytes) → Azure…%n", page, jpeg.length);
      String json = analyzer.analyzeRaw(jpeg, "image/jpeg");
      write(outDir.resolve(label + ".azure.json"), json);
      results.add(json);
    } catch (AzureRawAnalyzer.AzureChunkException e) {
      write(outDir.resolve(label + ".error.txt"), "HTTP " + e.status + "\n" + e.body);
      System.out.printf("[fixtures]   page %d image FAILED: %s%n", page, e.getMessage());
    }
  }

  /** Render {@code page} (1-based) to a JPEG, lowering DPI until it fits the tier cap (~3.8 MB). */
  private static byte[] renderJpegUnderCap(Path pdf, int page) throws IOException {
    int[] dpis = {200, 150, 120, 100, 72};
    try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
      PDFRenderer renderer = new PDFRenderer(doc);
      byte[] last = null;
      for (int dpi : dpis) {
        BufferedImage img = renderer.renderImageWithDPI(page - 1, dpi, ImageType.RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", bos);
        last = bos.toByteArray();
        if (last.length <= 3_800_000) {
          return last;
        }
      }
      return last; // smallest we could make it (submit anyway; may still be rejected)
    }
  }

  private static boolean isTooLarge(AzureRawAnalyzer.AzureChunkException e) {
    if (e.status != 400 || e.body == null) {
      return false;
    }
    String b = e.body.toLowerCase();
    return b.contains("invalidcontentlength") || b.contains("too large");
  }

  // ---------------------------------------------------------------------------

  private String[] filesToProcess() {
    String sel = env("AIOCR_FIXTURES_FILES");
    if (sel == null || sel.trim().isEmpty()) {
      return DEFAULT_FILES;
    }
    if ("all".equalsIgnoreCase(sel.trim())) {
      return ALL_FILES;
    }
    return sel.split("\\s*,\\s*");
  }

  /** Walk up from the working dir to find the repo-level {@code test-data} directory. */
  private static Path resolveTestDataDir() {
    String override = env("AIOCR_TESTDATA_DIR");
    if (override != null && !override.trim().isEmpty()) {
      return Path.of(override.trim());
    }
    Path dir = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8 && dir != null; i++) {
      Path candidate = dir.resolve("test-data");
      if (Files.isDirectory(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    throw new IllegalStateException(
        "Could not locate a repo-level 'test-data' directory from " + Path.of("").toAbsolutePath());
  }

  private static void write(Path path, String content) throws IOException {
    Files.write(path, content.getBytes(StandardCharsets.UTF_8));
  }

  private static String stripExt(String name) {
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }

  private static void deleteQuietly(Path p) {
    if (p != null) {
      try {
        Files.deleteIfExists(p);
      } catch (IOException ignore) {
        // best-effort
      }
    }
  }

  private static String env(String name) {
    return System.getenv(name);
  }

  private static String env0(String name) {
    String v = System.getenv(name);
    return v == null ? "" : v.trim();
  }
}

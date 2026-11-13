package com.axelor.apps.aiocr.service.optimize;

import com.axelor.app.AppSettings;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PDF slicing for the multi-document splitter, using Apache PDFBox (in-process, no external CLI).
 * The splitter operates on the boundaries decided by {@code BgOcrPdfSegmenter} from a SINGLE Azure
 * OCR result — this class only writes the physical slice PDFs.
 *
 * <ul>
 *   <li>{@link #slicePages} extracts a contiguous page range into a new PDF (one invoice / stock
 *       receipt / other document that occupies whole pages).
 *   <li>{@link #cropRegion} crops a single page to a vertical band (two invoices on one physical
 *       page). Azure coordinates (origin top-left, page-relative) are converted to PDF points
 *       (origin bottom-left) via unit-independent fractions of the page. When the region cannot be
 *       determined reliably it returns {@code null} so the caller falls back to the whole page and
 *       marks the record "To review" — never cutting content incorrectly.
 * </ul>
 *
 * <p>Every returned {@link Path} is a temp file the caller is responsible for deleting.
 */
@Singleton
public class PdfSplitService {

  private static final Logger LOG = LoggerFactory.getLogger(PdfSplitService.class);

  /**
   * Above this source size, parse with a temp-file-backed stream cache so a large (or maliciously
   * crafted) PDF cannot force unbounded heap allocation. Mirrors {@code PdfOptimizationService}.
   */
  private static final long STREAM_CACHE_THRESHOLD = 25L * 1024 * 1024;

  private volatile Semaphore permits;

  /**
   * Parse a PDF with a bounded-heap stream cache for large inputs (untrusted uploads). This is the
   * ONLY place this class parses a PDF, and it is overridable so a test can count parses — that
   * count is the whole point of {@link SliceSession}.
   */
  protected PDDocument load(Path src) throws IOException {
    long size;
    try {
      size = Files.size(src);
    } catch (IOException e) {
      size = 0;
    }
    return size > STREAM_CACHE_THRESHOLD
        ? Loader.loadPDF(src.toFile(), IOUtils.createTempFileOnlyStreamCache())
        : Loader.loadPDF(src.toFile());
  }

  /**
   * Extract pages {@code [fromPage1 .. toPage1]} (1-based, inclusive) of {@code src} into a new
   * temp PDF. Returns the temp path; the caller deletes it. Throws on an unreadable/invalid source.
   *
   * <p>One-shot convenience: it parses {@code src}, writes the slice and closes the source again. A
   * caller that slices the SAME source repeatedly (the Azure chunk loop) must use {@link
   * #openSession} instead, which parses once for all of its slices.
   */
  public Path slicePages(Path src, int fromPage1, int toPage1) throws IOException {
    try (SliceSession session = openSession(src)) {
      return session.slicePages(fromPage1, toPage1);
    }
  }

  /**
   * Parse {@code src} ONCE and hand back a session that can cut many page ranges out of it.
   *
   * <p>Why: the Azure F0 transport chunks a PDF into 2-page requests, so a 50-page document asks
   * for ~25 slices. Doing that through {@link #slicePages(Path, int, int)} re-parsed the whole
   * source for every one of them — 25 full PDFBox parses of the same bytes, which on a large
   * scanned PDF is the dominant local CPU/IO cost of the run. A session pays that parse once.
   *
   * <p><b>Thread confinement:</b> the session owns a mutable {@link PDDocument} and is NOT
   * thread-safe. It must be used on the thread that opened it (enforced), which is how the pipeline
   * uses it — one upload is OCR'd start-to-finish on a single worker thread. Concurrency between
   * documents is unaffected: each has its own session.
   *
   * <p><b>Memory:</b> only ONE destination document exists at a time — each slice is written and
   * closed before the next is built — so a long document never holds every chunk in heap. The
   * source is parsed with the same bounded-heap stream cache as everywhere else.
   *
   * <p>The caller must {@link SliceSession#close() close} the session; the source document stays
   * open until then, which is required because PDFBox slices share objects with their source until
   * they are saved.
   */
  public SliceSession openSession(Path src) throws IOException {
    boolean acquired = false;
    PDDocument in = null;
    try {
      permits().acquire();
      acquired = true;
      in = load(src);
      SliceSession session = new SliceSession(in);
      in = null; // ownership handed to the session
      return session;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while opening the PDF for slicing.", e);
    } finally {
      if (in != null) {
        try {
          in.close();
        } catch (IOException ignore) {
          // best-effort: we are already failing
        }
      }
      if (acquired) {
        permits().release();
      }
    }
  }

  /**
   * A source PDF parsed once, sliced many times. See {@link #openSession} for the rationale and the
   * threading/memory contract. Slicing semantics are identical to {@link #slicePages(Path, int,
   * int)} — same page selection, same clamping, same {@code importPage} copy (dimensions, rotation,
   * crop/media box, resources, fonts and images all preserved), same temp-file ownership.
   */
  public final class SliceSession implements AutoCloseable {

    private final PDDocument in;
    private final int pageCount;
    private final Thread owner;
    private volatile boolean closed;

    private SliceSession(PDDocument in) {
      this.in = in;
      this.pageCount = in.getNumberOfPages();
      this.owner = Thread.currentThread();
    }

    /** Page count of the parsed source — already known, never a second parse. */
    public int pageCount() {
      return pageCount;
    }

    /**
     * Extract pages {@code [fromPage1 .. toPage1]} (1-based, inclusive) into a new temp PDF, from
     * the already-parsed source. Returns the temp path; the caller deletes it.
     */
    public Path slicePages(int fromPage1, int toPage1) throws IOException {
      if (closed) {
        throw new IOException("This PDF slice session is already closed.");
      }
      if (Thread.currentThread() != owner) {
        // A shared PDDocument across threads corrupts silently; fail loudly instead.
        throw new IllegalStateException(
            "A PDF slice session must be used on the thread that opened it.");
      }
      boolean acquired = false;
      Path out = null;
      try {
        permits().acquire();
        acquired = true;
        out = Files.createTempFile("aiocr-slice-", ".pdf");
        int from = Math.max(1, fromPage1);
        int to = Math.min(pageCount, toPage1);
        if (from > to) {
          throw new IOException(
              "Invalid page range " + fromPage1 + "-" + toPage1 + " of " + pageCount);
        }
        // The destination is built, saved and closed inside this call: the source must stay open
        // across the save (slices share objects with it), the destination must not.
        try (PDDocument dst = new PDDocument()) {
          for (int i = from; i <= to; i++) {
            dst.importPage(in.getPage(i - 1));
          }
          dst.save(out.toFile());
        }
        return out;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        deleteQuietly(out);
        throw new IOException("Interrupted while slicing PDF pages.", e);
      } catch (IOException e) {
        deleteQuietly(out);
        throw e;
      } finally {
        if (acquired) {
          permits().release();
        }
      }
    }

    /** Close the parsed source. Idempotent; never throws into the pipeline. */
    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      try {
        in.close();
      } catch (IOException e) {
        LOG.debug("Could not close the sliced PDF source: {}", e.getMessage());
      }
    }
  }

  /**
   * Crop page {@code page1} (1-based) of {@code src} to the band described by {@code bbox}
   * (left,top,right,bottom) expressed in the same units as {@code pageW}/{@code pageH} (Azure's
   * page dimensions, origin top-left). Returns a temp single-page PDF, or {@code null} when the
   * region is unreliable (missing/degenerate box, rotated page, or a box that covers ~the whole
   * page) so the caller falls back to the whole page + To review.
   */
  public Path cropRegion(Path src, int page1, double[] bbox, double pageW, double pageH) {
    if (bbox == null || bbox.length < 4 || pageW <= 0 || pageH <= 0) {
      return null;
    }
    double left = bbox[0];
    double top = bbox[1];
    double right = bbox[2];
    double bottom = bbox[3];
    // Unit-independent fractions of the page (Azure y grows downward from the top).
    double fx0 = clampFraction(Math.min(left, right) / pageW);
    double fx1 = clampFraction(Math.max(left, right) / pageW);
    double fyTop = clampFraction(Math.min(top, bottom) / pageH);
    double fyBot = clampFraction(Math.max(top, bottom) / pageH);
    // A degenerate or whole-page box is not a reliable same-page split.
    if (fx1 - fx0 < 0.02 || fyBot - fyTop < 0.02) {
      return null;
    }
    if (fx1 - fx0 > 0.95 && fyBot - fyTop > 0.95) {
      return null;
    }

    boolean acquired = false;
    Path out = null;
    try {
      permits().acquire();
      acquired = true;
      out = Files.createTempFile("aiocr-crop-", ".pdf");
      try (PDDocument in = load(src);
          PDDocument dst = new PDDocument()) {
        if (page1 < 1 || page1 > in.getNumberOfPages()) {
          return null;
        }
        PDPage srcPage = in.getPage(page1 - 1);
        // Rotated pages make the top-left→bottom-left mapping ambiguous; fall back to whole page.
        if ((srcPage.getRotation() % 360) != 0) {
          return null;
        }
        PDPage page = dst.importPage(srcPage);
        PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
        float pw = box.getWidth();
        float ph = box.getHeight();
        float marginY = (float) (SAFE_MARGIN_FRACTION * ph);
        float marginX = (float) (SAFE_MARGIN_FRACTION * pw);
        float x0 = box.getLowerLeftX() + (float) (fx0 * pw) - marginX;
        float x1 = box.getLowerLeftX() + (float) (fx1 * pw) + marginX;
        // Flip vertical: Azure top fraction → PDF upper Y.
        float yTop = box.getUpperRightY() - (float) (fyTop * ph) + marginY;
        float yBot = box.getUpperRightY() - (float) (fyBot * ph) - marginY;
        x0 = Math.max(box.getLowerLeftX(), x0);
        x1 = Math.min(box.getUpperRightX(), x1);
        yBot = Math.max(box.getLowerLeftY(), yBot);
        yTop = Math.min(box.getUpperRightY(), yTop);
        if (x1 - x0 <= 1f || yTop - yBot <= 1f) {
          return null;
        }
        PDRectangle crop = new PDRectangle(x0, yBot, x1 - x0, yTop - yBot);
        page.setCropBox(crop);
        page.setMediaBox(crop);
        dst.save(out.toFile());
      }
      return out;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      deleteQuietly(out);
      return null;
    } catch (Exception e) {
      LOG.warn("PDF region crop failed (page {} of {}): {}", page1, src, e.getMessage());
      deleteQuietly(out);
      return null;
    } finally {
      if (acquired) {
        permits().release();
      }
    }
  }

  /** Best-effort page count via PDFBox (null when unreadable). */
  public Integer pageCountOrNull(Path src) {
    try (PDDocument in = load(src)) {
      return in.getNumberOfPages();
    } catch (IOException e) {
      return null;
    }
  }

  // ---------------------------------------------------------------------------

  private static final double SAFE_MARGIN_FRACTION = 0.015;

  private static double clampFraction(double f) {
    if (f < 0) {
      return 0;
    }
    return Math.min(f, 1.0);
  }

  private static void deleteQuietly(Path p) {
    if (p != null) {
      try {
        Files.deleteIfExists(p);
      } catch (IOException ignore) {
        // best-effort cleanup
      }
    }
  }

  private Semaphore permits() {
    Semaphore local = permits;
    if (local == null) {
      synchronized (this) {
        if (permits == null) {
          int size;
          try {
            size = Integer.parseInt(AppSettings.get().get("aiocr.split.pool-size", "2").trim());
          } catch (RuntimeException e) {
            size = 2;
          }
          permits = new Semaphore(Math.max(1, size));
        }
        local = permits;
      }
    }
    return local;
  }
}

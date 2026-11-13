package com.axelor.apps.aiocr.service.optimize;

import com.axelor.app.AppSettings;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Conservative, invoice-safe pre-OCR PDF normalization using <b>Apache PDFBox only</b> — no
 * Ghostscript, no qpdf, no external process, no Python. The normalization is strictly
 * <b>lossless</b>: PDFBox re-writes the PDF object structure with object-stream / Flate compression
 * ({@link CompressParameters#DEFAULT_COMPRESSION}), which shrinks bloated cross-reference tables
 * and uncompressed streams while preserving <b>everything that renders</b> — text, vectors, fonts,
 * embedded images (never re-encoded or downsampled), colour, page dimensions and page count. It
 * never rasterizes and never recompresses images, so an image-heavy PDF simply does not shrink (and
 * is left as-is), rather than being degraded.
 *
 * <p>The produced file is a separate temp file; the caller persists it in place of the original and
 * deletes the temp. The result is accepted only when PDFBox can reopen it and its page count equals
 * the original's. The input path is never written while the source document is open — the document
 * is closed before the caller replaces the input.
 */
@Singleton
public class PdfOptimizationService {

  private static final Logger LOG = LoggerFactory.getLogger(PdfOptimizationService.class);

  /** Above this source size, load with a temp-file stream cache to bound heap use. */
  private static final long STREAM_CACHE_THRESHOLD = 25L * 1024 * 1024;

  /**
   * Hard ceiling (bytes) on any PDF PDFBox will parse, as a first-line resource-exhaustion guard.
   */
  private static final long DEFAULT_MAX_BYTES = 150L * 1024 * 1024;

  private volatile Semaphore permits;

  /** Result of a single structural {@link #inspect} parse of an untrusted PDF. */
  public static final class PdfInfo {
    public final Integer pages; // null when the PDF is unreadable or encrypted
    public final boolean encrypted; // true = a user password we do not have is required
    public final boolean readable; // true = PDFBox opened it and we could read the page count

    PdfInfo(Integer pages, boolean encrypted, boolean readable) {
      this.pages = pages;
      this.encrypted = encrypted;
      this.readable = readable;
    }
  }

  /**
   * Inspect an untrusted PDF in a single bounded-heap parse: page count, whether it is
   * password-protected (so the caller can fail clearly instead of shipping it to Azure), and
   * whether it is readable at all. Never throws — a malformed PDF yields {@code readable=false}.
   */
  public PdfInfo inspect(Path file) {
    long size;
    try {
      size = Files.size(file);
    } catch (IOException e) {
      return new PdfInfo(null, false, false);
    }
    try (PDDocument doc = load(file, size)) {
      return new PdfInfo(doc.getNumberOfPages(), false, true);
    } catch (InvalidPasswordException e) {
      return new PdfInfo(null, true, false);
    } catch (IOException | RuntimeException e) {
      return new PdfInfo(null, false, false);
    }
  }

  /** Hard byte ceiling for any PDF this pipeline will parse (configurable). */
  public long maxBytes() {
    try {
      long v =
          Long.parseLong(
              AppSettings.get()
                  .get("aiocr.pdf.max-bytes", String.valueOf(DEFAULT_MAX_BYTES))
                  .trim());
      return v > 0 ? v : DEFAULT_MAX_BYTES;
    } catch (RuntimeException e) {
      return DEFAULT_MAX_BYTES;
    }
  }

  /** Outcome of a lossless normalization attempt. */
  public static final class OptimizationResult {
    public final Path optimizedFile; // temp normalized file (caller moves/deletes); null when none
    public final long originalBytes;
    public final long optimizedBytes; // 0 when no accepted output
    public final boolean optimized; // an accepted, valid, page-count-preserving file was produced
    public final Integer pageCount; // page count of the normalized file (null when not produced)
    public final String message;

    OptimizationResult(
        Path optimizedFile,
        long originalBytes,
        long optimizedBytes,
        boolean optimized,
        Integer pageCount,
        String message) {
      this.optimizedFile = optimizedFile;
      this.originalBytes = originalBytes;
      this.optimizedBytes = optimizedBytes;
      this.optimized = optimized;
      this.pageCount = pageCount;
      this.message = message;
    }
  }

  /**
   * Produce a losslessly normalized copy of {@code original} in a temp file. Never throws for a
   * normal failure — an unreadable/invalid PDF yields a not-optimized result and the caller keeps
   * the original. The returned {@link OptimizationResult#optimizedFile} (when present) is the
   * caller's to move into place and/or delete.
   */
  public OptimizationResult normalizeLossless(Path original) {
    long originalBytes;
    try {
      originalBytes = Files.size(original);
    } catch (IOException e) {
      return new OptimizationResult(null, 0, 0, false, null, "Could not read the original PDF.");
    }
    // First-line resource-exhaustion guard: refuse to even parse a PDF above the hard byte ceiling.
    // The extractor gate independently rejects it before Azure; here we just avoid the heavy parse.
    if (originalBytes > maxBytes()) {
      return new OptimizationResult(
          null, originalBytes, 0, false, null, "PDF exceeds the maximum processable size.");
    }
    Path output = null;
    boolean acquired = false;
    try {
      permits().acquire();
      acquired = true;

      output = Files.createTempFile("aiocr-pdfnorm-", ".pdf");
      // PDFBox writes a fresh file; remove the just-created empty temp so save() does not warn
      // about
      // overwriting (we never write the input path, and never read the output while writing it).
      Files.deleteIfExists(output);

      int pageCount;
      try (PDDocument doc = load(original, originalBytes)) {
        pageCount = doc.getNumberOfPages();
        doc.save(output.toFile(), CompressParameters.DEFAULT_COMPRESSION);
      }

      // Validate the SEPARATE output: it must reopen and preserve the page count.
      Integer outPages = pageCountOrNull(output);
      long optimizedBytes = Files.size(output);
      if (outPages == null || outPages != pageCount || optimizedBytes <= 0) {
        return new OptimizationResult(
            deleteAnd(output),
            originalBytes,
            0,
            false,
            pageCount,
            "Normalized PDF rejected (unreadable or page-count mismatch).");
      }
      return new OptimizationResult(output, originalBytes, optimizedBytes, true, pageCount, "ok");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new OptimizationResult(
          deleteAnd(output), originalBytes, 0, false, null, "Interrupted.");
    } catch (Exception e) {
      LOG.warn("PDF lossless normalization error: {}", e.getMessage());
      return new OptimizationResult(
          deleteAnd(output),
          originalBytes,
          0,
          false,
          null,
          "Normalization error: " + e.getMessage());
    } finally {
      if (acquired) {
        permits().release();
      }
    }
  }

  /**
   * Accept a normalized file only when PDFBox could reopen it and its page count equals the
   * original's. Non-empty output is implied by a successful re-open. Pure + unit-tested.
   */
  public static boolean accept(long optimizedBytes, Integer origPages, Integer optPages) {
    return optimizedBytes > 0
        && origPages != null
        && optPages != null
        && origPages.equals(optPages);
  }

  private PDDocument load(Path file, long size) throws IOException {
    return size > STREAM_CACHE_THRESHOLD
        ? Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache())
        : Loader.loadPDF(file.toFile());
  }

  /** Best-effort PDF page count via PDFBox (null when the file is unreadable). */
  public Integer pageCountOrNull(Path file) {
    long size;
    try {
      size = Files.size(file);
    } catch (IOException e) {
      return null;
    }
    try (PDDocument doc = load(file, size)) {
      return doc.getNumberOfPages();
    } catch (IOException e) {
      return null;
    }
  }

  private static Path deleteAnd(Path p) {
    if (p != null) {
      try {
        Files.deleteIfExists(p);
      } catch (IOException ignore) {
        // best-effort cleanup
      }
    }
    return null;
  }

  private Semaphore permits() {
    Semaphore local = permits;
    if (local == null) {
      synchronized (this) {
        if (permits == null) {
          permits = new Semaphore(Math.max(1, cfgInt("aiocr.optimize.pool-size", 2)));
        }
        local = permits;
      }
    }
    return local;
  }

  /** Whether the lossless pre-OCR normalization step is enabled (default true). */
  public boolean isEnabled() {
    return Boolean.parseBoolean(AppSettings.get().get("aiocr.optimize.enabled", "true").trim());
  }

  protected static int cfgInt(String key, int def) {
    try {
      return Integer.parseInt(AppSettings.get().get(key, String.valueOf(def)).trim());
    } catch (RuntimeException e) {
      return def;
    }
  }
}

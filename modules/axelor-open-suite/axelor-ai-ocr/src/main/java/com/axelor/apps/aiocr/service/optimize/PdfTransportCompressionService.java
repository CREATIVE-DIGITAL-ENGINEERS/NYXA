package com.axelor.apps.aiocr.service.optimize;

import com.axelor.app.AppSettings;
import com.google.inject.Singleton;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Semaphore;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Adaptive <b>lossy</b> compression used ONLY as an Azure OCR transport fallback for a single PDF
 * page that still exceeds the per-request byte cap after lossless normalization. The lossless page
 * stays the source of record ({@code originalFile}, attachments, dedup) — this class produces a
 * <b>transient</b> JPEG-backed one-page PDF that is submitted to Azure and then deleted; it is
 * never stored or attached.
 *
 * <p>Apache PDFBox only (no Ghostscript/qpdf/external process). The page is rendered with {@link
 * PDFRenderer}, re-encoded as JPEG, and wrapped in a fresh one-page PDF whose physical size matches
 * the original. The algorithm degrades as little as possible:
 *
 * <ol>
 *   <li>target ≈ {@code target-fraction} (default 95%) of the per-request cap, leaving headroom;
 *   <li>start at a high DPI (300) and binary-search the <b>highest</b> JPEG quality whose ACTUAL
 *       encoded size fits the target (never assuming JPEG size scales linearly);
 *   <li>only if even the minimum quality does not fit at that DPI, step the DPI down the ladder
 *       (300 → 250 → 220 → 200 → 180) and retry;
 *   <li>stop at the first setting that fits; a 5 MB page is nudged slightly, a 15 MB page harder.
 * </ol>
 *
 * <p>If the minimum DPI + minimum quality still cannot fit under the hard cap, it throws {@link
 * LossyCompressionException} so the caller fails the page with a clear message rather than shipping
 * unreadable OCR input. Reuses the same bounded-concurrency semaphore + temp-file discipline as the
 * other PDFBox services; every rendered/encoded temp artifact is deleted.
 */
@Singleton
public class PdfTransportCompressionService {

  /** Above this source size, load with a temp-file stream cache to bound heap use. */
  private static final long STREAM_CACHE_THRESHOLD = 25L * 1024 * 1024;

  private static final String DEFAULT_DPI_LADDER = "300,250,220,200,180";
  private static final double DEFAULT_TARGET_FRACTION = 0.95;
  private static final float DEFAULT_MAX_QUALITY = 0.95f;
  private static final float DEFAULT_MIN_QUALITY = 0.45f;

  private volatile Semaphore permits;

  /** Thrown when a page cannot be reduced under the cap even at the minimum readable settings. */
  public static class LossyCompressionException extends Exception {
    public LossyCompressionException(String message) {
      super(message);
    }
  }

  /**
   * The transient lossy artifact plus the exact settings chosen, so callers can persist/log the
   * details WITHOUT parsing any string. {@link #file} is the caller's to submit and delete.
   */
  public static final class CompressionResult {
    public final Path file; // transient JPEG-backed one-page PDF (caller submits, then deletes)
    public final long originalBytes; // the lossless page size fed in
    public final long compressedBytes; // the produced transport-copy size
    public final int dpi; // selected render DPI
    public final float quality; // selected JPEG quality (0..1)
    public final long capBytes; // the Azure per-request byte limit that had to be met

    CompressionResult(
        Path file,
        long originalBytes,
        long compressedBytes,
        int dpi,
        float quality,
        long capBytes) {
      this.file = file;
      this.originalBytes = originalBytes;
      this.compressedBytes = compressedBytes;
      this.dpi = dpi;
      this.quality = quality;
      this.capBytes = capBytes;
    }
  }

  /** A JPEG encoding together with the quality it was produced at. */
  private static final class Encoded {
    final byte[] bytes;
    final float quality;

    Encoded(byte[] bytes, float quality) {
      this.bytes = bytes;
      this.quality = quality;
    }
  }

  /** Whether the adaptive lossy transport fallback is enabled (default true). */
  public boolean isEnabled() {
    return Boolean.parseBoolean(AppSettings.get().get("aiocr.azure.lossy.enabled", "true").trim());
  }

  /**
   * Produce a transient JPEG-backed one-page PDF of {@code onePagePdf} that fits under {@code
   * hardCapBytes} (the Azure per-request byte limit), with the least degradation possible. Returns
   * a temp path the CALLER must delete. Throws {@link LossyCompressionException} when it cannot fit
   * at the minimum settings (or when disabled), and {@link IOException} on a render/encode error.
   */
  public CompressionResult compressPageToFit(Path onePagePdf, long hardCapBytes)
      throws IOException, LossyCompressionException {
    if (!isEnabled()) {
      throw new LossyCompressionException(
          "adaptive lossy transport compression is disabled (aiocr.azure.lossy.enabled=false)");
    }
    long target = Math.max(1, (long) (hardCapBytes * targetFraction()));
    int[] dpiLadder = dpiLadder();
    float minQ = minQuality();
    float maxQ = maxQuality();

    boolean acquired = false;
    Path out = null;
    try {
      permits().acquire();
      acquired = true;

      long srcSize;
      try {
        srcSize = Files.size(onePagePdf);
      } catch (IOException e) {
        srcSize = 0;
      }
      try (PDDocument doc = load(onePagePdf, srcSize)) {
        if (doc.getNumberOfPages() < 1) {
          throw new IOException("The oversized OCR page slice has no page to render.");
        }
        PDFRenderer renderer = new PDFRenderer(doc);
        for (int dpi : dpiLadder) {
          BufferedImage image = null;
          try {
            image = renderer.renderImageWithDPI(0, dpi, ImageType.RGB);
            Encoded jpeg = highestQualityUnder(image, target, minQ, maxQ);
            if (jpeg == null) {
              continue; // even the minimum quality is too big at this DPI → step DPI down
            }
            out = writeOnePagePdf(jpeg.bytes, image.getWidth(), image.getHeight(), dpi);
            long produced = Files.size(out);
            if (produced <= hardCapBytes) {
              CompressionResult result =
                  new CompressionResult(out, srcSize, produced, dpi, jpeg.quality, hardCapBytes);
              out = null; // hand ownership to the caller; do not delete in finally
              return result;
            }
            // The PDF wrapper pushed it just over the hard cap — drop the DPI and retry.
            deleteQuietly(out);
            out = null;
          } finally {
            if (image != null) {
              image.flush();
            }
          }
        }
      }
      throw new LossyCompressionException(
          "could not fit the page under "
              + (hardCapBytes / (1024 * 1024))
              + " MB even at the minimum "
              + dpiLadder[dpiLadder.length - 1]
              + " DPI / quality "
              + minQ
              + " (below this the OCR input would be unreadable)");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while compressing the oversized OCR page.", e);
    } finally {
      // On success `out` was nulled and handed to the caller; on ANY failure path (checked, runtime
      // or error) this deletes the transient artifact so nothing leaks.
      deleteQuietly(out);
      if (acquired) {
        permits().release();
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Adaptive quality search (uses ACTUAL encoded size, not a linear model)
  // ---------------------------------------------------------------------------

  /**
   * The JPEG bytes for the highest quality in [{@code minQ}, {@code maxQ}] whose actual encoded
   * size is ≤ {@code target}, or {@code null} when even {@code minQ} is too big. Binary search on
   * quality (monotone: higher quality ⇒ larger), re-encoding at each probe so the decision uses the
   * real output size.
   */
  private Encoded highestQualityUnder(BufferedImage image, long target, float minQ, float maxQ)
      throws IOException {
    byte[] atMin = encodeJpeg(image, minQ);
    if (atMin.length > target) {
      return null;
    }
    byte[] atMax = encodeJpeg(image, maxQ);
    if (atMax.length <= target) {
      return new Encoded(atMax, maxQ); // already fits at the highest quality — minimal degradation
    }
    Encoded best = new Encoded(atMin, minQ);
    float lo = minQ;
    float hi = maxQ;
    for (int i = 0; i < 7; i++) { // ~7 probes narrows quality to <1% granularity
      float mid = (lo + hi) / 2f;
      byte[] probe = encodeJpeg(image, mid);
      if (probe.length <= target) {
        best = new Encoded(probe, mid);
        lo = mid; // push for higher quality
      } else {
        hi = mid;
      }
    }
    return best;
  }

  /** Encode a BufferedImage to JPEG at {@code quality} in [0,1]; returns the encoded bytes. */
  private static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
    Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
    if (!writers.hasNext()) {
      throw new IOException("No JPEG image writer is available in this JRE.");
    }
    ImageWriter writer = writers.next();
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
      writer.setOutput(ios);
      ImageWriteParam param = writer.getDefaultWriteParam();
      if (param.canWriteCompressed()) {
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(Math.max(0f, Math.min(1f, quality)));
      }
      writer.write(null, new IIOImage(image, null, null), param);
      ios.flush();
    } finally {
      writer.dispose();
    }
    return bos.toByteArray();
  }

  /**
   * Wrap already-encoded JPEG bytes into a fresh one-page PDF whose physical size matches the
   * source page (image pixels ÷ render DPI × 72 pt). {@link PDImageXObject#createFromByteArray}
   * stores the JPEG directly (DCTDecode) — no re-encode, so the measured JPEG size is what ends up
   * in the PDF.
   */
  private Path writeOnePagePdf(byte[] jpeg, int widthPx, int heightPx, int dpi) throws IOException {
    Path out = Files.createTempFile("aiocr-lossy-", ".pdf");
    try (PDDocument doc = new PDDocument()) {
      float widthPts = widthPx * 72f / dpi;
      float heightPts = heightPx * 72f / dpi;
      PDPage page = new PDPage(new PDRectangle(widthPts, heightPts));
      doc.addPage(page);
      PDImageXObject image = PDImageXObject.createFromByteArray(doc, jpeg, "page");
      try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
        cs.drawImage(image, 0, 0, widthPts, heightPts);
      }
      doc.save(out.toFile());
    } catch (IOException e) {
      deleteQuietly(out);
      throw e;
    }
    return out;
  }

  // ---------------------------------------------------------------------------
  // Config + infra
  // ---------------------------------------------------------------------------

  private PDDocument load(Path file, long size) throws IOException {
    return size > STREAM_CACHE_THRESHOLD
        ? Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache())
        : Loader.loadPDF(file.toFile());
  }

  private double targetFraction() {
    try {
      double v =
          Double.parseDouble(
              AppSettings.get()
                  .get("aiocr.azure.lossy.target-fraction", String.valueOf(DEFAULT_TARGET_FRACTION))
                  .trim());
      return (v > 0 && v <= 1.0) ? v : DEFAULT_TARGET_FRACTION;
    } catch (RuntimeException e) {
      return DEFAULT_TARGET_FRACTION;
    }
  }

  private float maxQuality() {
    return clampQuality("aiocr.azure.lossy.max-quality", DEFAULT_MAX_QUALITY);
  }

  private float minQuality() {
    float min = clampQuality("aiocr.azure.lossy.min-quality", DEFAULT_MIN_QUALITY);
    float max = maxQuality();
    return Math.min(min, max);
  }

  private float clampQuality(String key, float def) {
    try {
      float v = Float.parseFloat(AppSettings.get().get(key, String.valueOf(def)).trim());
      return Math.max(0.1f, Math.min(1.0f, v));
    } catch (RuntimeException e) {
      return def;
    }
  }

  /** The DPI ladder (descending), first entry the start DPI, last the minimum DPI. */
  private int[] dpiLadder() {
    String raw = AppSettings.get().get("aiocr.azure.lossy.dpi-ladder", DEFAULT_DPI_LADDER);
    List<Integer> ladder = new ArrayList<>();
    if (raw != null) {
      for (String part : raw.split(",")) {
        try {
          int dpi = Integer.parseInt(part.trim());
          if (dpi >= 72 && dpi <= 600) {
            ladder.add(dpi);
          }
        } catch (NumberFormatException ignore) {
          // skip a malformed ladder entry
        }
      }
    }
    if (ladder.isEmpty()) {
      for (String part : DEFAULT_DPI_LADDER.split(",")) {
        ladder.add(Integer.parseInt(part.trim()));
      }
    }
    int[] out = new int[ladder.size()];
    for (int i = 0; i < out.length; i++) {
      out[i] = ladder.get(i);
    }
    return out;
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
            size =
                Integer.parseInt(AppSettings.get().get("aiocr.azure.lossy.pool-size", "2").trim());
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

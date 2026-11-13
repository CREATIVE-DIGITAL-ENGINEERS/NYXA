package com.axelor.apps.aiocr.service.ocr;

import com.axelor.app.AppSettings;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractLine;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.aiocr.service.optimize.PdfOptimizationService;
import com.axelor.apps.aiocr.service.optimize.PdfSplitService;
import com.axelor.apps.aiocr.service.optimize.PdfTransportCompressionService;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Direct Azure Document Intelligence extractor (prebuilt-invoice, REST, no SDK). Reads the uploaded
 * {@link MetaFile}, submits it to Azure, polls for the analysis result, then maps + validates it.
 *
 * <p>Chosen over the Azure Java SDK to avoid dragging azure-core/Reactor/Netty into Axelor's
 * runtime. Uses only the JDK {@code java.net.http.HttpClient} (pinned HTTP/1.1) + Jackson (already
 * on the classpath). Authenticates with the API key ({@code Ocp-Apim-Subscription-Key}); managed
 * identity can be added later without touching callers.
 *
 * <p>Failure model: a readable-but-rejected document (local MIME/size reject, Azure 400/413/415, or
 * a failed/cancelled analysis) → {@link OcrExtractResult} with {@code extractionFailed=true} (gate
 * fails the job inline, no invoice). Service problems (401/403, 429/5xx after bounded retries,
 * connection/timeout, malformed response, analysis timeout) → {@link OcrServiceException}. Never
 * falls back to mock/pymupdf/another provider.
 *
 * <p>Every Azure rejection message quotes Azure's own {@code error.code: error.message} rather than
 * a guess at what went wrong. That matters most for 403, which Azure uses both for a real refusal
 * and for a used-up call-volume allowance: only the latter is retried, and only the body can tell
 * them apart. A quota 403 is further split into a short, self-clearing dip (retried) and a
 * confirmed exhausted allowance (failed at once, because no backoff this class will sit through can
 * outlast it). See {@link #classifyQuota} and {@link #submitRejectionMessage}.
 */
@Singleton
public class AzureDocumentIntelligenceExtractor implements OcrExtractor {

  private static final Logger LOG =
      LoggerFactory.getLogger(AzureDocumentIntelligenceExtractor.class);

  public static final String ENGINE = "azure";

  static final String ENV_ENDPOINT = "AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT";
  static final String ENV_KEY = "AZURE_DOCUMENT_INTELLIGENCE_KEY";
  static final String DEFAULT_MODEL = "prebuilt-invoice";
  static final String DEFAULT_API_VERSION = "2024-11-30";
  static final String DEFAULT_ALLOWED_MIME =
      "application/pdf,image/jpeg,image/jpg,image/png,image/tiff";

  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  public String name() {
    return ENGINE;
  }

  /** Minimal transport response, so the HTTP calls can be stubbed in tests. */
  protected static final class AzureResponse {
    final int status;
    final String body;
    final String operationLocation;
    final String retryAfter;

    AzureResponse(int status, String body, String operationLocation, String retryAfter) {
      this.status = status;
      this.body = body;
      this.operationLocation = operationLocation;
      this.retryAfter = retryAfter;
    }
  }

  // ---------------------------------------------------------------------------
  // Entry point
  // ---------------------------------------------------------------------------

  @Override
  public OcrExtractResult extract(MetaFile file) {
    return extract(file, OcrProgressListener.NONE);
  }

  @Override
  public OcrExtractResult extract(MetaFile file, OcrProgressListener progress) {
    if (progress == null) {
      progress = OcrProgressListener.NONE;
    }
    if (file == null) {
      return failed("No original file attached to the upload.");
    }
    if (endpoint().isEmpty() || key().isEmpty()) {
      throw new OcrServiceException(
          "Azure Document Intelligence is not configured (AZURE_DOCUMENT_INTELLIGENCE_ENDPOINT / _KEY).");
    }

    Path path = MetaFiles.getPath(file);
    if (path == null || !Files.exists(path)) {
      return failed("Original file not found in Axelor storage.");
    }

    String mime = resolveMime(file, path);
    Set<String> allowed = allowedMime();
    if (mime == null || !allowed.contains(mime.toLowerCase())) {
      return failed(
          "Unsupported file type '" + mime + "'. Allowed: " + String.join(", ", allowed) + ".");
    }

    long requestCap = (long) maxRequestMb() * 1024 * 1024;
    boolean isPdf = "application/pdf".equalsIgnoreCase(mime);

    long size;
    try {
      size = Files.size(path);
    } catch (IOException e) {
      throw new OcrServiceException("Could not read the file: " + e.getMessage(), e);
    }

    if (isPdf) {
      PdfOptimizationService pdf = Beans.get(PdfOptimizationService.class);
      // Untrusted upload: reject an oversized PDF up front (before any heavy PDFBox parse).
      if (size > pdf.maxBytes()) {
        return failed(
            "PDF is "
                + (size / (1024 * 1024))
                + " MB, over the maximum size this pipeline will process — split it or raise"
                + " aiocr.pdf.max-bytes.");
      }
      // ONE bounded-heap structural parse: page count + encryption (no qpdf). An encrypted PDF we
      // cannot open fails clearly here rather than producing an opaque Azure error; an absurdly
      // long
      // PDF is failed outright below — chunking it would mean thousands of Azure calls.
      PdfOptimizationService.PdfInfo info = pdf.inspect(path);
      if (info.encrypted) {
        return failed(
            "This PDF is encrypted/password-protected and cannot be processed. Remove the password"
                + " protection and re-upload.");
      }
      Integer pages = info.pages;
      if (maxPages() > 0 && pages != null && pages > maxPages()) {
        return failed(
            "PDF has "
                + pages
                + " pages, over the maximum of "
                + maxPages()
                + " pages this pipeline will OCR — a higher Azure tier / manual split is required.");
      }
      // A PDF that does not fit one Azure request (too many pages OR too many bytes) is OCR'd in
      // sequential PDFBox page chunks and stitched back into ONE logical analyzeResult, which feeds
      // the exact same segmentation pipeline. Chunking is an internal transport detail — it never
      // creates upload children on its own.
      if (pages != null && (pages > maxRequestPages() || size > requestCap)) {
        return analyzeChunked(path, pages, file, progress);
      }
    }

    // Fits one request (a small PDF, or an image within the per-request cap).
    if (size > requestCap) {
      return failed(
          "File is "
              + (size / (1024 * 1024))
              + " MB, over the "
              + maxRequestMb()
              + " MB Azure per-request limit"
              + (isPdf ? " even after lossless normalization" : "")
              + " — a higher Azure tier is required (or this single page cannot be reduced"
              + " losslessly).");
    }
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(path);
    } catch (IOException e) {
      throw new OcrServiceException("Could not read the file: " + e.getMessage(), e);
    }
    OcrExtractResult single = analyze(bytes, mime, file);
    // A single-request document is analysed as one unit → report it fully processed once done.
    progress.onPagesProcessed(1, 1);
    return single;
  }

  // ---------------------------------------------------------------------------
  // Chunked OCR for PDFs that exceed one Azure request (page or byte limit)
  // ---------------------------------------------------------------------------

  /**
   * OCR a PDF that exceeds one Azure request by slicing it into sequential PDFBox page chunks
   * (never concurrent), submitting each through the SAME retry/polling path, then stitching the
   * per-chunk {@code analyzeResult}s into one logical result (page numbers, spans, offsets and
   * bounding regions rebased) that is mapped + fed to the same segmenter. No lossy rasterization: a
   * single physical page that still exceeds the per-request byte cap fails the document, naming the
   * page.
   */
  protected OcrExtractResult analyzeChunked(
      Path pdf, int pageCount, MetaFile file, OcrProgressListener progress) {
    long requestCap = (long) maxRequestMb() * 1024 * 1024;
    int chunkPages = Math.max(1, maxRequestPages());
    PdfSplitService splitter = Beans.get(PdfSplitService.class);
    List<String> chunkJsons = new ArrayList<>();
    // One structured entry per page that had to be lossy-compressed for transport (usually empty).
    // The full technical detail is logged at INFO; the intake builds a short client summary from
    // it.
    List<OcrExtractResult.PageCompression> pageCompressions = new ArrayList<>();

    OcrPerfTrace.set("ocrPages", pageCount);
    OcrPerfTrace.set("ocrChunks", (pageCount + chunkPages - 1) / chunkPages);
    // ONE parse of the source for every chunk of this document (see PdfSplitService.openSession).
    // The session is thread-confined and this loop is sequential on the worker thread, which is
    // exactly the contract it requires.
    long openStartNs = System.nanoTime();
    try (PdfSplitService.SliceSession session = splitter.openSession(pdf)) {
      OcrPerfTrace.since("pdfOpenMs", openStartNs);
      OcrPerfTrace.set("pdfLoads", 1);
      for (int from = 1; from <= pageCount; from += chunkPages) {
        int to = Math.min(from + chunkPages - 1, pageCount);
        OcrExtractResult failure =
            ocrChunkRange(session, from, to, requestCap, chunkJsons, pageCompressions);
        if (failure != null) {
          return failure; // a chunk was rejected / a page is too large / analysis failed
        }
        // Report cumulative pages after each committed chunk so the carrier's progress bar advances
        // live (e.g. 5 pages as 2+2+1 → 40% → 80% → 100%). Never let a listener error stop OCR.
        try {
          progress.onPagesProcessed(to, pageCount);
        } catch (RuntimeException ignore) {
          // progress is best-effort UI only
        }
      }
    } catch (IOException e) {
      throw new OcrServiceException("Could not open the PDF for chunked OCR: " + e.getMessage(), e);
    }
    if (chunkJsons.isEmpty()) {
      return failed("The PDF produced no OCR chunks.");
    }

    long stitchStartNs = System.nanoTime();
    String stitched = new AzureAnalyzeResultStitcher(mapper).stitch(chunkJsons);
    OcrPerfTrace.since("stitchMs", stitchStartNs);
    JsonNode analyzeResult;
    try {
      analyzeResult = mapper.readTree(stitched);
    } catch (IOException e) {
      throw new OcrServiceException("Could not parse the stitched OCR result.", e);
    }
    OcrExtractResult result = mapAnalyzeResult(analyzeResult, mapper);
    result.setRawJson(stitched);
    result.setEngineUsed(ENGINE);
    result.setCorrelationId(String.valueOf(file.getId()));
    result.setConfidence(toPercent(computeOverallConfidence(result.getFieldConfidence(), result)));
    // Carry the structured compression records so the intake can build a short client summary for
    // the upload's Extraction information field — never mixed into Extraction errors.
    result.getPageCompressions().addAll(pageCompressions);
    validate(result);
    return result;
  }

  /**
   * OCR one page range and append its raw JSON to {@code chunkJsons}. When the sliced range exceeds
   * the per-request byte cap it is re-sliced page-by-page (lossless); a single page still over the
   * cap fails the whole document (clearly naming the page). Returns {@code null} on success, or a
   * failed {@link OcrExtractResult} identifying the offending chunk.
   */
  protected OcrExtractResult ocrChunkRange(
      PdfSplitService.SliceSession session,
      int from,
      int to,
      long requestCap,
      List<String> chunkJsons,
      List<OcrExtractResult.PageCompression> pageCompressions) {
    Path slice = null;
    Path lossy = null;
    try {
      long sliceStartNs = System.nanoTime();
      slice = session.slicePages(from, to);
      OcrPerfTrace.since("pdfSliceMs", sliceStartNs);
      byte[] bytes = Files.readAllBytes(slice);
      if (bytes.length > requestCap) {
        if (from != to) {
          // A multi-page chunk over the cap: re-slice page-by-page (lossless) first, so lossy
          // compression is only ever considered for a genuinely oversized SINGLE page.
          for (int p = from; p <= to; p++) {
            OcrExtractResult f =
                ocrChunkRange(session, p, p, requestCap, chunkJsons, pageCompressions);
            if (f != null) {
              return f;
            }
          }
          return null;
        }
        // A single physical page that still exceeds the per-request cap after lossless
        // normalization: fall back to ADAPTIVE LOSSY TRANSPORT compression — a transient
        // JPEG-backed one-page PDF submitted to Azure and then deleted. The lossless page stays the
        // source of record; the lossy artifact is never stored or attached.
        try {
          long compressStartNs = System.nanoTime();
          PdfTransportCompressionService.CompressionResult compressed =
              Beans.get(PdfTransportCompressionService.class).compressPageToFit(slice, requestCap);
          OcrPerfTrace.since("pdfCompressMs", compressStartNs);
          OcrPerfTrace.count("pdfCompressedPages", 1);
          lossy = compressed.file;
          bytes = Files.readAllBytes(lossy);
          // Full technical detail (page, sizes, DPI, JPEG quality, Azure limit) goes to the INFO
          // log
          // ONLY. The client-facing Extraction information field gets a short, jargon-free summary
          // built later from the structured record — so keep only structured data on the result.
          LOG.info(formatCompressionNote(from, compressed, maxRequestMb()));
          pageCompressions.add(
              new OcrExtractResult.PageCompression(
                  from, compressed.originalBytes, compressed.compressedBytes));
        } catch (PdfTransportCompressionService.LossyCompressionException e) {
          return failed(
              "OCR chunk page "
                  + from
                  + " is "
                  + (bytes.length / (1024 * 1024))
                  + " MB, over the "
                  + maxRequestMb()
                  + " MB Azure per-request limit even after lossless normalization and adaptive"
                  + " lossy compression ("
                  + e.getMessage()
                  + ") — a higher Azure tier is required for this document.");
        }
      }

      String range = (from == to) ? ("page " + from) : ("pages " + from + "-" + to);
      AzureResponse submit = submitWithRetry(bytes, "application/pdf", range);
      if (submit.status == 400 || submit.status == 413 || submit.status == 415) {
        return failed(
            "Azure rejected OCR chunk "
                + range
                + " (HTTP "
                + submit.status
                + "): "
                + azureError(submit.body)
                + ".");
      }
      if (submit.status == 401 || submit.status == 403) {
        // Same helper as the whole-document path, so a chunked document reports a quota exhaustion
        // and an auth refusal exactly as a single-page one does — only with the page range added.
        throw new OcrServiceException(submitRejectionMessage(submit, range));
      }
      if (submit.status != 202 || submit.operationLocation == null) {
        throw new OcrServiceException(
            "Azure submit for OCR chunk "
                + range
                + " returned unexpected HTTP "
                + submit.status
                + ".");
      }
      try {
        JsonNode analyzeResult = pollUntilDone(submit.operationLocation, submit.retryAfter);
        chunkJsons.add(analyzeResult.toString());
      } catch (OcrAnalysisFailedException e) {
        return failed("Azure could not analyse OCR chunk " + range + ": " + e.getMessage());
      }
      return null;
    } catch (IOException e) {
      throw new OcrServiceException(
          "Could not slice/read OCR chunk pages " + from + "-" + to + ": " + e.getMessage(), e);
    } finally {
      deleteQuietly(slice); // temp lossless page slice
      deleteQuietly(lossy); // transient lossy transport artifact (never stored/attached)
    }
  }

  private static void deleteQuietly(Path p) {
    if (p != null) {
      try {
        Files.deleteIfExists(p);
      } catch (IOException ignore) {
        // best-effort cleanup of a temp artifact
      }
    }
  }

  /** Submit → poll → map a document already read into {@code bytes}. */
  protected OcrExtractResult analyze(byte[] bytes, String mime, MetaFile file) {
    AzureResponse submit = submitWithRetry(bytes, mime);
    if (submit.status == 400 || submit.status == 413 || submit.status == 415) {
      // Surface Azure's own error code/message + file size, so the reason is visible in the job.
      long mb = bytes.length / (1024 * 1024);
      return failed(
          "Azure rejected the document (HTTP "
              + submit.status
              + "): "
              + azureError(submit.body)
              + " [file "
              + mb
              + " MB]. Large or many-page PDFs exceed the Azure tier limits.");
    }
    if (submit.status == 401 || submit.status == 403) {
      // A quota 403 is retried inside submitWithRetry and throws from there once the attempts are
      // spent, so what normally reaches here is a 401 or a permanent 403. The quota wording is
      // still
      // built by the same helper, so the two cannot drift apart.
      throw new OcrServiceException(submitRejectionMessage(submit, null));
    }
    if (submit.status != 202 || submit.operationLocation == null) {
      throw new OcrServiceException(
          "Azure submit returned unexpected HTTP " + submit.status + " (no Operation-Location).");
    }
    JsonNode analyzeResult;
    try {
      analyzeResult = pollUntilDone(submit.operationLocation, submit.retryAfter);
    } catch (OcrAnalysisFailedException e) {
      // The document reached Azure but could not be analysed → no invoice, gate fails inline.
      return failed(e.getMessage());
    }
    OcrExtractResult result = mapAnalyzeResult(analyzeResult, mapper);
    // Keep the raw analysis JSON so a multi-invoice PDF can be segmented + split without a second
    // Azure call (documents[]/pages[]/boundingRegions live only here, not on the flat result).
    result.setRawJson(analyzeResult.toString());
    result.setEngineUsed(ENGINE);
    result.setCorrelationId(String.valueOf(file.getId()));
    result.setConfidence(toPercent(computeOverallConfidence(result.getFieldConfidence(), result)));
    validate(result);
    return result;
  }

  // ---------------------------------------------------------------------------
  // Submit with bounded retry (429 / 5xx / network, plus a quota-exhausted 403)
  // ---------------------------------------------------------------------------

  protected AzureResponse submitWithRetry(byte[] bytes, String mime) {
    return submitWithRetry(bytes, mime, null);
  }

  /**
   * As {@link #submitWithRetry(byte[], String)}, naming the page range in any failure message.
   *
   * @param chunkRange e.g. "pages 4-6", or null for a whole-document submit
   */
  protected AzureResponse submitWithRetry(byte[] bytes, String mime, String chunkRange) {
    String url =
        stripTrailingSlash(endpoint())
            + "/documentintelligence/documentModels/"
            + model()
            + ":analyze?api-version="
            + apiVersion();
    int attempts = Math.min(Math.max(0, maxRetries()) + 1, 5); // hard cap at 5 total attempts
    OcrServiceException last = null;
    for (int i = 1; i <= attempts; i++) {
      try {
        AzureResponse resp = httpSubmit(url, bytes, mime);
        // A used-up call-volume allowance arrives as 403, not 429. Which KIND of exhaustion it is
        // decides everything: a short, self-clearing dip is worth waiting out, but a spent monthly
        // allowance will not come back inside any backoff this class is willing to sit through, so
        // retrying it only burns the attempt budget and delays a failure the accountant has to act
        // on anyway. Every other 403 (permissions, key auth disabled, network ACL) still falls
        // straight through to the caller and fails on the first attempt.
        QuotaState quota = classifyQuota(resp);
        if (quota == QuotaState.EXHAUSTED) {
          OcrPerfTrace.count("azureQuotaExhausted", 1);
          throw new OcrServiceException(submitRejectionMessage(resp, chunkRange));
        }
        if (resp.status == 429
            || (resp.status >= 500 && resp.status < 600)
            || quota == QuotaState.TEMPORARY) {
          if (resp.status == 429) {
            OcrPerfTrace.count("azure429Count", 1);
          }
          recordRetryAfter(KIND_POST, resp.status, resp.retryAfter, chunkRange);
          // Keep Azure's own wording for the quota case: this is the exception that surfaces on the
          // OCR record once the attempts run out, and "transient HTTP 403" would explain nothing.
          last =
              quota == QuotaState.TEMPORARY
                  ? new OcrServiceException(submitRejectionMessage(resp, chunkRange))
                  : new OcrServiceException("Azure submit transient HTTP " + resp.status + ".");
          if (i < attempts) {
            OcrPerfTrace.count("azureRetryCount", 1);
            backoff(i, resp.retryAfter);
            continue;
          }
          throw last;
        }
        // A 202 may carry Retry-After too. That one is NOT throttling — it is Azure saying how
        // long the analysis will plausibly take, so it is recorded quietly (no WARN) and honoured
        // by pollUntilDone before the first result poll.
        if (resp.status == 202) {
          OcrPerfTrace.max("azureSubmitRetryAfterS", retryAfterSeconds(resp.retryAfter));
        }
        return resp;
      } catch (java.net.http.HttpTimeoutException e) {
        last = new OcrServiceException("Azure submit timed out (attempt " + i + ").", e);
      } catch (IOException e) {
        last =
            new OcrServiceException("Cannot reach Azure (attempt " + i + "): " + e.getMessage(), e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new OcrServiceException("Interrupted while calling Azure.", e);
      }
      if (i < attempts) {
        backoff(i, null);
      } else {
        throw last;
      }
    }
    throw (last != null) ? last : new OcrServiceException("Azure submit failed.");
  }

  // ---------------------------------------------------------------------------
  // Poll until the analysis completes
  // ---------------------------------------------------------------------------

  protected JsonNode pollUntilDone(String operationLocation) {
    return pollUntilDone(operationLocation, null);
  }

  /**
   * Poll an operation, honouring the {@code Retry-After} the accepting 202 returned.
   *
   * <p>Azure's own guidance is to wait the announced delay before the FIRST result poll: the
   * analysis cannot possibly have finished sooner, so a poll sent immediately is guaranteed to
   * return {@code running}. It is not free — it consumes a slot on the shared 1/s poll allowance
   * and counts toward the tier's request budget, which is exactly the traffic that comes back 429.
   * Honouring the 202's hint ({@link AzureResponse#retryAfter}) keeps every chunk from opening with
   * at least one certainly-useless GET.
   *
   * <p>The wait is capped by {@link #RETRY_AFTER_CAP_MS} like every other announced delay, so a
   * pathological hint cannot park a worker.
   */
  protected JsonNode pollUntilDone(String operationLocation, String submitRetryAfter) {
    long deadline = monotonicMillis() + (long) pollMaxSeconds() * 1000L;
    int transient429or5xx = 0;
    if (submitRetryAfter != null) {
      long firstWaitNs = System.nanoTime();
      sleepBeforeNextPoll(submitRetryAfter);
      OcrPerfTrace.since("azurePollSleepMs", firstWaitNs);
      checkDeadline(deadline);
    }
    while (true) {
      AzureResponse resp;
      try {
        resp = httpGet(operationLocation);
      } catch (java.net.http.HttpTimeoutException e) {
        throw new OcrServiceException("Azure poll timed out.", e);
      } catch (IOException e) {
        throw new OcrServiceException("Azure poll connection error: " + e.getMessage(), e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new OcrServiceException("Interrupted while polling Azure.", e);
      }

      if (resp.status == 429 || (resp.status >= 500 && resp.status < 600)) {
        recordRetryAfter(KIND_GET, resp.status, resp.retryAfter, null);
        if (resp.status == 429) {
          OcrPerfTrace.count("azure429Count", 1);
          // Slow EVERY poller on this resource down, not just this one — same reasoning as the
          // submit-side penalty in backoff().
          limiter(AzureRateLimiter.Kind.POLL)
              .penalize(retryAfterMillis(resp.retryAfter, pollIntervalMs()));
        }
        OcrPerfTrace.count("azureRetryCount", 1);
        if (++transient429or5xx > Math.max(1, maxRetries() + 2)) {
          throw new OcrServiceException("Azure poll kept returning HTTP " + resp.status + ".");
        }
        // Measure the wait actually taken, not the one computed: a 429 on the POLL path costs the
        // same kind of time as submit-side backoff(), and leaving it out of azureBackoffMs is why a
        // document throttled only while polling reported azureBackoffMs=0 despite azure429Count>0.
        long waitStartNs = System.nanoTime();
        sleepBeforeNextPoll(resp.retryAfter);
        OcrPerfTrace.since(resp.status == 429 ? "azureBackoffMs" : "azurePollSleepMs", waitStartNs);
        checkDeadline(deadline);
        continue;
      }
      if (resp.status != 200) {
        throw new OcrServiceException("Azure poll unexpected HTTP " + resp.status + ".");
      }

      JsonNode root = parse(resp.body);
      String status = text(root, "status");
      if ("succeeded".equalsIgnoreCase(status)) {
        JsonNode analyzeResult = root.get("analyzeResult");
        if (analyzeResult == null || analyzeResult.isNull()) {
          throw new OcrServiceException("Azure succeeded but returned no analyzeResult.");
        }
        return analyzeResult;
      }
      if ("failed".equalsIgnoreCase(status) || "canceled".equalsIgnoreCase(status)) {
        // Analysis definitively failed → treat as an unreadable document (no invoice), not a
        // service outage: return via exception carrying a controlled reason.
        throw new OcrAnalysisFailedException("Azure analysis " + status + ".");
      }
      // notStarted / running — ordinary poll pacing. Measured separately so "the document spent
      // four minutes waiting for Azure to finish" is visible in the trace instead of hiding in
      // totalMs.
      long pollSleepNs = System.nanoTime();
      sleepBeforeNextPoll(null);
      OcrPerfTrace.since("azurePollSleepMs", pollSleepNs);
      checkDeadline(deadline);
    }
  }

  /**
   * Marker for a definitively failed/cancelled analysis → surfaced as extractionFailed upstream.
   */
  static class OcrAnalysisFailedException extends RuntimeException {
    OcrAnalysisFailedException(String m) {
      super(m);
    }
  }

  private void checkDeadline(long deadline) {
    if (monotonicMillis() > deadline) {
      throw new OcrServiceException("Azure analysis did not complete within the poll timeout.");
    }
  }

  // ---------------------------------------------------------------------------
  // Real HTTP (isolated seams — overridden in tests)
  // ---------------------------------------------------------------------------

  /**
   * The process-wide limiter for THIS Azure resource and request kind. Keyed on the configured
   * endpoint, so two resources (or a test endpoint and the real one) never share a slot sequence.
   */
  protected AzureRateLimiter limiter(AzureRateLimiter.Kind kind) {
    return AzureRateLimiter.forResource(endpoint(), kind);
  }

  protected AzureResponse httpSubmit(String url, byte[] body, String mime)
      throws IOException, InterruptedException {
    // Pace against this resource's analyze allowance BEFORE opening the connection: on F0 (1/s)
    // the alternative is a 429 that costs a round trip AND the backoff that follows it. Only the
    // Azure call is paced — local PDF normalization/slicing/compression stay fully parallel.
    OcrPerfTrace.add(
        "azureRateWaitMs",
        limiter(AzureRateLimiter.Kind.ANALYZE).acquire(AzureRateLimiter.Kind.ANALYZE));
    OcrPerfTrace.count("azurePostCount", 1);
    long startNs = System.nanoTime();
    try {
      HttpResponse<String> resp =
          client()
              .send(
                  HttpRequest.newBuilder(URI.create(url))
                      .timeout(Duration.ofSeconds(requestTimeout()))
                      .header("Ocp-Apim-Subscription-Key", key())
                      .header("Content-Type", mime)
                      .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                      .build(),
                  HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return new AzureResponse(
          resp.statusCode(),
          resp.body(),
          resp.headers().firstValue("Operation-Location").orElse(null),
          resp.headers().firstValue("Retry-After").orElse(null));
    } finally {
      OcrPerfTrace.since("azurePostMs", startNs);
    }
  }

  protected AzureResponse httpGet(String url) throws IOException, InterruptedException {
    // Polls get their OWN limiter: a long poll loop must never eat the submit allowance.
    OcrPerfTrace.add(
        "azureRateWaitMs", limiter(AzureRateLimiter.Kind.POLL).acquire(AzureRateLimiter.Kind.POLL));
    OcrPerfTrace.count("azureGetCount", 1);
    long startNs = System.nanoTime();
    try {
      HttpResponse<String> resp =
          client()
              .send(
                  HttpRequest.newBuilder(URI.create(url))
                      .timeout(Duration.ofSeconds(requestTimeout()))
                      .header("Ocp-Apim-Subscription-Key", key())
                      .GET()
                      .build(),
                  HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      return new AzureResponse(
          resp.statusCode(),
          resp.body(),
          null,
          resp.headers().firstValue("Retry-After").orElse(null));
    } finally {
      OcrPerfTrace.since("azureGetMs", startNs);
    }
  }

  /**
   * The ONE {@link HttpClient} this extractor uses.
   *
   * <p>A {@code java.net.http.HttpClient} is thread-safe, owns a connection pool and a selector
   * thread, and in Java 11 cannot be closed — so a client per request would mean a fresh TCP+TLS
   * handshake on every submit and every poll (a 25-chunk PDF polls well over a hundred times) plus
   * a selector thread per call that only goes away at GC. Reusing one client keeps the TLS session
   * and the keep-alive connection, which also removes a source of jitter between when the rate
   * limiter releases a request and when Azure actually sees it.
   *
   * <p>Consequence to know: {@code connectTimeout} is fixed at first use, so changing that setting
   * needs a restart. The per-request read timeout is still applied per request.
   */
  private volatile HttpClient httpClient;

  private HttpClient client() {
    HttpClient local = httpClient;
    if (local == null) {
      synchronized (this) {
        if (httpClient == null) {
          httpClient =
              HttpClient.newBuilder()
                  .version(HttpClient.Version.HTTP_1_1)
                  .connectTimeout(Duration.ofSeconds(connectTimeout()))
                  .build();
        }
        local = httpClient;
      }
    }
    return local;
  }

  /**
   * Exponential backoff with jitter. A numeric {@code Retry-After} is honored exactly; otherwise
   * the delay is {@code base·2^(attempt-1)} capped, plus up to that much random jitter, so
   * concurrent workers don't retry in lockstep.
   */
  protected void backoff(int attempt, String retryAfter) {
    long base = 500L;
    long cap = 30_000L;
    long exp = Math.min(cap, base * (1L << Math.max(0, attempt - 1)));
    long jitter = (long) (java.util.concurrent.ThreadLocalRandom.current().nextDouble() * exp);
    long delay = retryAfterMillis(retryAfter, exp + jitter);
    // Back the WHOLE resource off, not just this thread. Azure refused one worker's request; the
    // other workers are about to make the same request against the same allowance, and letting
    // them through is what turns one 429 into a burst of them.
    limiter(AzureRateLimiter.Kind.ANALYZE).penalize(delay);
    OcrPerfTrace.add("azureBackoffMs", delay);
    sleep(delay);
  }

  protected void sleepBeforeNextPoll(String retryAfter) {
    sleep(retryAfterMillis(retryAfter, pollIntervalMs()));
  }

  /**
   * Longest we will ever honour a {@code Retry-After} for.
   *
   * <p>Load-bearing since quota 403s became retryable. Azure sends {@code Retry-After} on a used-up
   * allowance with the real replenishment delay, which can be hours or days; obeying that literally
   * would park an OCR worker thread — holding its slot in the processing pool — for that long, on a
   * document that would be better off failing with a clear message the accountant can act on. The
   * cap matches the exponential backoff's own ceiling, so waiting never exceeds what this class was
   * already prepared to wait.
   */
  static final long RETRY_AFTER_CAP_MS = 30_000L;

  /** Which Azure call a throttling response came from — POST (:analyze) or GET (result poll). */
  static final String KIND_POST = "Post";

  static final String KIND_GET = "Get";

  /**
   * Record ONE throttling/defer response, sanitized.
   *
   * <p>Why the raw seconds matter: {@link #RETRY_AFTER_CAP_MS} silently caps what we actually wait
   * for, so a trace could show a 30 s backoff whether Azure asked for 30 seconds or for an hour —
   * and those mean completely different things (a transient dip vs a spent allowance). The peak is
   * kept with {@link OcrPerfTrace#max} precisely so it cannot be averaged away, and POST and GET
   * are kept apart so submit throttling is never confused with poll throttling.
   *
   * <p>What is logged: the call kind, the HTTP status, the announced delay and the page range.
   * <b>Never</b> the response body, the endpoint, the key, the file name or any extracted field —
   * this line is safe to leave on at WARN in production and safe to paste into a ticket.
   */
  protected void recordRetryAfter(String kind, int status, String retryAfter, String range) {
    long seconds = retryAfterSeconds(retryAfter);
    OcrPerfTrace.max("azureRetryAfter" + kind + "MaxS", seconds);
    OcrPerfTrace.add("azureRetryAfter" + kind + "SumS", seconds);
    if (LOG.isWarnEnabled()) {
      LOG.warn(
          "Azure throttling: call={} status={} retryAfter={} scope={}",
          kind.toUpperCase(Locale.ROOT),
          status,
          retryAfter == null ? "absent" : (seconds > 0 ? seconds + "s" : "non-numeric"),
          range == null ? "whole document" : range);
    }
  }

  private static long retryAfterMillis(String retryAfter, long fallback) {
    if (retryAfter != null) {
      try {
        long ms = Math.max(0L, Long.parseLong(retryAfter.trim()) * 1000L);
        return Math.min(ms, RETRY_AFTER_CAP_MS);
      } catch (NumberFormatException ignore) {
        // non-numeric (HTTP-date) — fall back
      }
    }
    return Math.min(fallback, RETRY_AFTER_CAP_MS);
  }

  private void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new OcrServiceException("Interrupted during Azure wait.", e);
    }
  }

  /** Overridable clock (avoids Date.now in tests). */
  protected long monotonicMillis() {
    return System.nanoTime() / 1_000_000L;
  }

  /**
   * Rebuild the extraction result from an {@code analyzeResult} JSON that a previous call already
   * stored on the upload ({@code ocrResultJson} for a plain document, {@code splitSourceJson} for a
   * carrier, {@code cachedOcrJson} for a split child).
   *
   * <p>This is what makes an OCR document re-derivable <b>without a second Azure call</b>: the
   * stored JSON is the whole input to the mapping, and the mapping is pure, so the result is the
   * same one intake produced. It runs the identical steps as the live path — map, keep the raw
   * JSON, stamp the engine, recompute the confidence, validate — so a caller cannot end up with a
   * differently-shaped result by taking this route.
   *
   * <p>The correlation id is deliberately not set: it identifies the {@code MetaFile} of the call
   * that produced the JSON, and inventing one here would attribute this result to a request that
   * never happened.
   *
   * @throws OcrServiceException when the stored text is not a usable analyzeResult
   */
  public static OcrExtractResult fromStoredAnalyzeResult(String analyzeResultJson) {
    if (analyzeResultJson == null || analyzeResultJson.trim().isEmpty()) {
      throw new OcrServiceException("No stored OCR result to rebuild from.");
    }
    ObjectMapper om = new ObjectMapper();
    JsonNode analyzeResult;
    try {
      analyzeResult = om.readTree(analyzeResultJson);
    } catch (Exception e) {
      throw new OcrServiceException("The stored OCR result is not valid JSON: " + e.getMessage());
    }
    // Some stores keep the whole Azure envelope rather than the analyzeResult itself; accept both.
    if (analyzeResult.has("analyzeResult") && !analyzeResult.get("analyzeResult").isNull()) {
      analyzeResult = analyzeResult.get("analyzeResult");
    }
    OcrExtractResult result = mapAnalyzeResult(analyzeResult, om);
    result.setRawJson(analyzeResult.toString());
    result.setEngineUsed(ENGINE);
    result.setConfidence(toPercent(computeOverallConfidence(result.getFieldConfidence(), result)));
    validate(result);
    return result;
  }

  // ---------------------------------------------------------------------------
  // Mapping (pure) — Azure analyzeResult JSON → OcrExtractResult
  // ---------------------------------------------------------------------------

  static OcrExtractResult mapAnalyzeResult(JsonNode analyzeResult, ObjectMapper mapper) {
    OcrExtractResult r = new OcrExtractResult();
    // Full OCR text is a sibling of "documents" and is present regardless of the model
    // (prebuilt-invoice or prebuilt-read). Capture it for BG-specific text signals (payment
    // method).
    r.setRawText(text(analyzeResult, "content"));
    JsonNode docs = analyzeResult.get("documents");
    JsonNode fields =
        (docs != null && docs.isArray() && docs.size() > 0) ? docs.get(0).get("fields") : null;
    if (fields == null || fields.isNull()) {
      // A successful analysis with no structured invoice fields means the wrong model was used
      // (prebuilt-read / prebuilt-layout / etc. return content/paragraphs but no
      // documents[0].fields).
      // This is a configuration/service problem, not a bad document, so surface it loudly.
      String modelId = text(analyzeResult, "modelId");
      throw new OcrServiceException(
          "Expected a prebuilt-invoice result with structured document fields, but received model '"
              + (isBlank(modelId) ? "unknown" : modelId)
              + "'.");
    }

    mapDocumentFields(r, fields);
    return r;
  }

  /**
   * Map ONE Azure invoice document's {@code fields} object onto {@code r}. Shared by the
   * single-document path ({@code documents[0]}) and the multi-invoice PDF splitter, which calls
   * this per {@code documents[i]}. It only fills the structured invoice fields; {@code rawText},
   * {@code engineUsed}, {@code correlationId} and {@code confidence} are owned by the caller so a
   * per-segment result can carry its own segment-scoped text.
   */
  public static void mapDocumentFields(OcrExtractResult r, JsonNode fields) {
    mapDocumentFields(r, fields, null, null);
  }

  /**
   * As {@link #mapDocumentFields(OcrExtractResult, JsonNode)}, but keeping only the {@code Items}
   * that Azure located inside pages {@code [fromPage, toPage]}.
   *
   * <h3>Why the item list has to be page-scoped</h3>
   *
   * When a PDF holds the same invoice twice, Azure returns ONE document spanning both physical
   * pages whose {@code Items} array therefore contains every line twice, while {@code SubTotal} /
   * {@code InvoiceTotal} stay single. The splitter drops the repeated page and slices out only the
   * retained one, so mapping the whole document onto that slice attributes lines to pages the slice
   * does not contain — a draft whose lines sum to twice its own header. Each item carries its own
   * {@code boundingRegions[].pageNumber}, so the correct lines can simply be selected rather than
   * guessed at from their values.
   *
   * <p>The filter is applied <b>before</b> {@link #normalizeAzureLineAmounts}, because that decides
   * whether Azure's per-item {@code Amount} is net or gross by comparing their <em>sum</em> against
   * the header — a doubled sum makes it choose wrongly.
   *
   * <p>An item with no page information is kept: absent provenance is not evidence that a line
   * belongs elsewhere, and dropping a real line is the worse error.
   *
   * @param fromPage first retained page, or {@code null} for no page restriction
   * @param toPage last retained page, or {@code null} for no page restriction
   */
  public static void mapDocumentFields(
      OcrExtractResult r, JsonNode fields, Integer fromPage, Integer toPage) {
    if (fields == null || fields.isNull()) {
      return;
    }
    r.setSupplierName(fstr(fields, "VendorName"));
    r.setSupplierVat(fstr(fields, "VendorTaxId"));
    r.setSupplierEik(deriveEik(r.getSupplierVat()));
    r.setSupplierAddress(fstr(fields, "VendorAddress"));
    r.setSupplierAddressRecipient(fstr(fields, "VendorAddressRecipient"));
    // Structured supplier address parts (Azure valueAddress) — preferred over regex-parsing the
    // text.
    JsonNode vendorAddr = field(fields, "VendorAddress");
    JsonNode vendorValueAddress = vendorAddr != null ? vendorAddr.get("valueAddress") : null;
    if (vendorValueAddress != null && !vendorValueAddress.isNull()) {
      r.setSupplierAddrStreet(vaddr(vendorValueAddress, "road"));
      r.setSupplierAddrNumber(vaddr(vendorValueAddress, "houseNumber"));
      r.setSupplierAddrCity(vaddr(vendorValueAddress, "city"));
      r.setSupplierAddrZip(vaddr(vendorValueAddress, "postalCode"));
    }
    // Recipient identity — captured only to cross-check the selected company (see
    // BgOcrReviewChecks).
    r.setCustomerName(fstr(fields, "CustomerName"));
    r.setCustomerVat(fstr(fields, "CustomerTaxId"));
    r.setCustomerEik(deriveEik(r.getCustomerVat()));
    r.setCustomerAddress(fstr(fields, "CustomerAddress"));
    r.setCustomerAddressRecipient(fstr(fields, "CustomerAddressRecipient"));
    r.setCustomerId(fstr(fields, "CustomerId"));
    r.setInvoiceNumber(fstr(fields, "InvoiceId"));
    r.setInvoiceDate(fdate(fields, "InvoiceDate"));
    r.setDueDate(fdate(fields, "DueDate"));
    r.setPaymentTerm(fstr(fields, "PaymentTerm"));
    r.setTotalBase(fnum(fields, "SubTotal"));
    r.setTotalVat(fnum(fields, "TotalTax"));
    r.setTotalGross(fnum(fields, "InvoiceTotal"));
    r.setCurrency(firstCurrency(fields, "InvoiceTotal", "SubTotal", "TotalTax"));
    mapPaymentDetails(fields, r);

    List<BigDecimal> azureItemAmounts = new ArrayList<>();
    JsonNode items = field(fields, "Items");
    if (items != null) {
      JsonNode arr = items.get("valueArray");
      if (arr != null && arr.isArray()) {
        for (JsonNode entry : arr) {
          JsonNode obj = entry.get("valueObject");
          if (obj == null) {
            continue;
          }
          if (!onRetainedPage(entry, fromPage, toPage)) {
            continue; // Azure located this line on a page this segment does not contain.
          }
          OcrExtractLine line = new OcrExtractLine();
          line.setDescription(fstr(obj, "Description"));
          line.setProductCode(fstr(obj, "ProductCode"));
          line.setUnit(fstr(obj, "Unit"));
          line.setQty(fnum(obj, "Quantity"));
          line.setUnitPrice(fnum(obj, "UnitPrice"));
          azureItemAmounts.add(fnum(obj, "Amount"));
          line.setVatAmount(fnum(obj, "Tax"));
          line.setVatRate(parseRateToPercent(fstr(obj, "TaxRate")));
          r.getLines().add(line);
        }
      }
    }
    normalizeAzureLineAmounts(r, azureItemAmounts);

    Map<String, Double> fc = r.getFieldConfidence();
    fc.put("supplierName", fconf(fields, "VendorName"));
    fc.put("supplierVat", fconf(fields, "VendorTaxId"));
    fc.put("invoiceNumber", fconf(fields, "InvoiceId"));
    fc.put("invoiceDate", fconf(fields, "InvoiceDate"));
    fc.put("currency", fconf(fields, "InvoiceTotal"));
    fc.put("taxBase", fconf(fields, "SubTotal"));
    fc.put("vatAmount", fconf(fields, "TotalTax"));
    fc.put("totalAmount", fconf(fields, "InvoiceTotal"));
  }

  /**
   * Whether an {@code Items} entry lies on a page this segment kept.
   *
   * <p>Only a page Azure actually reported can exclude a line: no range asked for, or no {@code
   * boundingRegions[].pageNumber} on the entry, and the line is kept.
   */
  static boolean onRetainedPage(JsonNode entry, Integer fromPage, Integer toPage) {
    if (fromPage == null || toPage == null) {
      return true;
    }
    JsonNode regions = entry.get("boundingRegions");
    if (regions == null || !regions.isArray() || regions.size() == 0) {
      return true;
    }
    boolean sawPage = false;
    for (JsonNode region : regions) {
      JsonNode page = region.get("pageNumber");
      if (page == null || !page.isNumber()) {
        continue;
      }
      sawPage = true;
      int pageNumber = page.asInt();
      if (pageNumber >= fromPage && pageNumber <= toPage) {
        return true; // a line straddling a page break belongs to the segment it starts in
      }
    }
    return !sawPage;
  }

  /** Map a single, unambiguous Azure PaymentDetails entry. */
  private static void mapPaymentDetails(JsonNode fields, OcrExtractResult r) {
    JsonNode paymentDetails = field(fields, "PaymentDetails");
    JsonNode values = paymentDetails != null ? paymentDetails.get("valueArray") : null;
    if (values == null || !values.isArray()) {
      return;
    }

    Map<String, String> swiftByIban = new LinkedHashMap<>();
    Set<String> ibans = new LinkedHashSet<>();
    for (JsonNode entry : values) {
      JsonNode obj = entry.get("valueObject");
      if (obj == null || obj.isNull()) {
        continue;
      }
      // Extract the IBAN token from Azure's value, which may carry a trailing currency suffix
      // ("BG42STSA…3828 (EUR)") or internal spaces ("BG18 UBBS 8888 …"). A strict full-string match
      // would drop the suffixed one — the exact reason a valid IBAN went undetected.
      String iban = BgOcrBankDetails.extractIban(fstr(obj, "IBAN"));
      if (iban == null) {
        continue;
      }
      ibans.add(iban);
      String swift = normalizeCode(fstr(obj, "SWIFT"));
      if (swift != null) {
        swiftByIban.put(iban, swift);
      }
    }
    if (ibans.size() == 1) {
      String iban = ibans.iterator().next();
      r.setIban(iban);
      r.setSwift(swiftByIban.get(iban));
    } else if (ibans.size() > 1) {
      r.getWarnings()
          .add("Multiple supplier IBANs were extracted; bank details were not selected.");
    }
  }

  /**
   * Azure's Item.Amount differs between invoice layouts: it can reconcile to either SubTotal or
   * InvoiceTotal. Decide once per document, then derive missing net/gross amounts, VAT rates,
   * quantities, and unit prices from values that reconcile within rounding tolerance.
   */
  static void normalizeAzureLineAmounts(OcrExtractResult r, List<BigDecimal> azureAmounts) {
    BigDecimal amountSum = sum(azureAmounts);
    boolean amountIsGross =
        closerTo(amountSum, r.getTotalGross(), r.getTotalBase())
            && r.getTotalVat() != null
            && r.getTotalVat().abs().compareTo(TOL) > 0;

    for (int i = 0; i < r.getLines().size(); i++) {
      OcrExtractLine line = r.getLines().get(i);
      BigDecimal amount = i < azureAmounts.size() ? azureAmounts.get(i) : null;
      BigDecimal tax = line.getVatAmount();
      // Captured BEFORE anything below derives a value: did the DOCUMENT state this line's VAT?
      boolean statedVat = line.isVatEvidenceExplicit();

      if (amount != null) {
        if (amountIsGross && (tax == null || amount.compareTo(tax) >= 0)) {
          line.setGrossAmount(amount);
          line.setNetAmount(
              tax == null ? netFromGross(amount, line.getVatRate()) : amount.subtract(tax));
        } else {
          line.setNetAmount(amount);
          line.setGrossAmount(
              tax == null ? grossFromNet(amount, line.getVatRate()) : amount.add(tax));
        }
      } else if (line.getQty() != null && line.getUnitPrice() != null) {
        line.setNetAmount(line.getQty().multiply(line.getUnitPrice()));
        line.setGrossAmount(
            tax == null
                ? grossFromNet(line.getNetAmount(), line.getVatRate())
                : line.getNetAmount().add(tax));
      }

      // ⚠️ THE ZERO THAT WAS NOT THERE. When the document states neither a rate nor a per-line
      // tax, grossFromNet/netFromGross above return the amount UNCHANGED — so gross equals net,
      // not because the supply is zero-rated but because nothing was known. Deriving
      // "VAT = gross − net" from that pair invents a 0.00, deriveVatRate reads the invented zero
      // as a genuine 0% supply, and BgOcrVatMapping maps every line to BG_ZRP: invoices #10 and
      // #12 were booked with no VAT at all against documents that declare 20%.
      //
      // Worse, the invented zero was PERSISTED: OcrResultJson writes the normalised result into
      // AiOcrUpload.cachedOcrJson ("vatRate":0.00,"vatAmount":0.00), and every split child reads
      // it back through Jackson's setters. So the fabrication survived the round trip and any
      // later recovery saw an explicit 0% instead of an unknown.
      //
      // Both figures therefore stay NULL unless the document actually said something. Null
      // serialises as null, rehydrates through setVatRate(null) without marking evidence, and is
      // what lets BgOcrVatRateInference recover the rate from the document summary.
      if (statedVat) {
        if (line.getVatAmount() == null
            && line.getGrossAmount() != null
            && line.getNetAmount() != null) {
          line.setDerivedVatAmount(line.getGrossAmount().subtract(line.getNetAmount()));
        }
        if (line.getVatRate() == null) {
          line.setDerivedVatRate(deriveVatRate(line.getNetAmount(), line.getVatAmount()));
        }
      }
      inferQuantityOrUnitPrice(line);
    }
  }

  private static boolean closerTo(BigDecimal value, BigDecimal candidate, BigDecimal other) {
    if (value == null || candidate == null) {
      return false;
    }
    BigDecimal candidateDistance = value.subtract(candidate).abs();
    if (other == null) {
      return candidateDistance.compareTo(TOL) <= 0;
    }
    return candidateDistance.compareTo(value.subtract(other).abs()) < 0;
  }

  private static BigDecimal netFromGross(BigDecimal gross, BigDecimal rate) {
    if (gross == null || rate == null) {
      return gross;
    }
    return gross.divide(BigDecimal.ONE.add(rate.movePointLeft(2)), 2, RoundingMode.HALF_UP);
  }

  private static BigDecimal grossFromNet(BigDecimal net, BigDecimal rate) {
    if (net == null || rate == null) {
      return net;
    }
    return net.multiply(BigDecimal.ONE.add(rate.movePointLeft(2)))
        .setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal deriveVatRate(BigDecimal net, BigDecimal tax) {
    if (net == null || tax == null || net.signum() == 0) {
      return null;
    }
    BigDecimal calculated =
        tax.multiply(new BigDecimal("100")).divide(net, 4, RoundingMode.HALF_UP);
    for (BigDecimal common :
        new BigDecimal[] {
          BigDecimal.ZERO, new BigDecimal("5"), new BigDecimal("9"), new BigDecimal("20")
        }) {
      if (calculated.subtract(common).abs().compareTo(new BigDecimal("0.25")) <= 0) {
        return common.setScale(2);
      }
    }
    return calculated.setScale(2, RoundingMode.HALF_UP);
  }

  private static void inferQuantityOrUnitPrice(OcrExtractLine line) {
    BigDecimal net = line.getNetAmount();
    if (line.getQty() == null
        && net != null
        && line.getUnitPrice() != null
        && line.getUnitPrice().signum() > 0) {
      BigDecimal quantity =
          net.divide(line.getUnitPrice(), 3, RoundingMode.HALF_UP).stripTrailingZeros();
      if (quantity.signum() > 0
          && line.getUnitPrice().multiply(quantity).subtract(net).abs().compareTo(TOL) <= 0) {
        line.setQty(quantity);
      }
    } else if (line.getUnitPrice() == null
        && net != null
        && line.getQty() != null
        && line.getQty().signum() > 0) {
      line.setUnitPrice(net.divide(line.getQty(), 6, RoundingMode.HALF_UP).stripTrailingZeros());
    }
  }

  private static BigDecimal sum(List<BigDecimal> values) {
    BigDecimal total = null;
    for (BigDecimal value : values) {
      if (value != null) {
        total = total == null ? value : total.add(value);
      }
    }
    return total;
  }

  private static String normalizeCode(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.replaceAll("\\s+", "").toUpperCase();
    return normalized.isEmpty() ? null : normalized;
  }

  // ---------------------------------------------------------------------------
  // Deterministic overall confidence (0..1) from critical fields
  // ---------------------------------------------------------------------------

  static final String[] SUPPLIER_IDENTITY = {"supplierName", "supplierVat"};

  static double computeOverallConfidence(Map<String, Double> fc, OcrExtractResult r) {
    double[] scores = {
      supplierIdentityScore(fc, r),
      critical(fc, "invoiceNumber", r.getInvoiceNumber()),
      critical(fc, "invoiceDate", r.getInvoiceDate()),
      critical(fc, "currency", r.getCurrency()),
      critical(fc, "taxBase", r.getTotalBase()),
      critical(fc, "vatAmount", r.getTotalVat()),
      critical(fc, "totalAmount", r.getTotalGross())
    };
    double sum = 0;
    for (double s : scores) {
      sum += s;
    }
    return Math.round((sum / scores.length) * 10000.0) / 10000.0;
  }

  /**
   * Overall extraction confidence (as a 0..100 percentage) for an already-mapped result, computed
   * from its per-field confidences exactly like a full-document extraction. Split children reuse
   * this instead of Azure's {@code documents[].confidence} (the document-TYPE classification score,
   * routinely 1.0 → a misleading 100%), so a child's confidence is the same field-extraction metric
   * shown on the parent carrier.
   */
  public static BigDecimal overallConfidencePercent(OcrExtractResult r) {
    return toPercent(computeOverallConfidence(r.getFieldConfidence(), r));
  }

  private static double supplierIdentityScore(Map<String, Double> fc, OcrExtractResult r) {
    boolean present =
        (r.getSupplierName() != null && !r.getSupplierName().isEmpty())
            || (r.getSupplierEik() != null && !r.getSupplierEik().isEmpty())
            || (r.getSupplierVat() != null && !r.getSupplierVat().isEmpty());
    if (!present) {
      return 0.0;
    }
    double best = 0.0;
    for (String k : SUPPLIER_IDENTITY) {
      best = Math.max(best, fc.getOrDefault(k, 0.0));
    }
    return best;
  }

  private static double critical(Map<String, Double> fc, String key, Object value) {
    if (value == null || (value instanceof String && ((String) value).isEmpty())) {
      return 0.0;
    }
    return fc.getOrDefault(key, 0.0);
  }

  // ---------------------------------------------------------------------------
  // Validation — flag anomalies as warnings/discrepancies; never auto-correct
  // ---------------------------------------------------------------------------

  static final BigDecimal TOL = new BigDecimal("0.02");

  // Static because it is pure — it only appends warnings/discrepancies to the result it is given.
  // That is what lets fromStoredAnalyzeResult run the same validation without an extractor
  // instance.
  static void validate(OcrExtractResult r) {
    if (isBlank(r.getSupplierName()) && isBlank(r.getSupplierEik())) {
      r.getWarnings().add("Missing supplier identity (name and EIK).");
    }
    if (isBlank(r.getInvoiceNumber())) {
      r.getWarnings().add("Missing invoice number.");
    }
    if (r.getInvoiceDate() == null) {
      r.getWarnings().add("Missing invoice date.");
    }
    BigDecimal base = r.getTotalBase();
    BigDecimal vat = r.getTotalVat();
    BigDecimal gross = r.getTotalGross();
    if (base != null && vat != null && gross != null) {
      if (base.add(vat).subtract(gross).abs().compareTo(TOL) > 0) {
        r.getDiscrepancies()
            .add("Totals do not reconcile: taxable + VAT differs from the invoice total.");
      }
    }
    if (!isBlank(r.getCurrency()) && !"EUR".equalsIgnoreCase(r.getCurrency().trim())) {
      r.getWarnings()
          .add("Currency is '" + r.getCurrency() + "', not EUR — verify before approving.");
    }
    if (r.getConfidence() != null && r.getConfidence().compareTo(new BigDecimal("70")) < 0) {
      r.getWarnings().add("Low overall confidence: " + r.getConfidence() + "%.");
    }
  }

  // ---------------------------------------------------------------------------
  // JSON field accessors (Azure field object shapes)
  // ---------------------------------------------------------------------------

  private static JsonNode field(JsonNode fields, String key) {
    if (fields == null) {
      return null;
    }
    JsonNode f = fields.get(key);
    return (f == null || f.isNull()) ? null : f;
  }

  /** Read a plain string leaf from an Azure {@code valueAddress} object; null when absent/blank. */
  private static String vaddr(JsonNode valueAddress, String key) {
    if (valueAddress == null || !valueAddress.hasNonNull(key)) {
      return null;
    }
    String s = valueAddress.get(key).asText().trim();
    return s.isEmpty() ? null : s;
  }

  private static String fstr(JsonNode fields, String key) {
    JsonNode f = field(fields, key);
    if (f == null) {
      return null;
    }
    for (String attr : new String[] {"valueString", "content"}) {
      JsonNode v = f.get(attr);
      if (v != null && !v.isNull()) {
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
      }
    }
    return null;
  }

  private static BigDecimal fnum(JsonNode fields, String key) {
    JsonNode f = field(fields, key);
    if (f == null) {
      return null;
    }
    JsonNode cur = f.get("valueCurrency");
    if (cur != null && cur.get("amount") != null && !cur.get("amount").isNull()) {
      return decimal(cur.get("amount").asText());
    }
    for (String attr : new String[] {"valueNumber", "valueInteger"}) {
      JsonNode v = f.get(attr);
      if (v != null && !v.isNull()) {
        return decimal(v.asText());
      }
    }
    return null;
  }

  private static String firstCurrency(JsonNode fields, String... keys) {
    for (String key : keys) {
      JsonNode f = field(fields, key);
      if (f != null && f.get("valueCurrency") != null) {
        JsonNode code = f.get("valueCurrency").get("currencyCode");
        if (code != null && !code.isNull()) {
          String c = code.asText().trim().toUpperCase();
          if (!c.isEmpty()) {
            return c;
          }
        }
      }
    }
    return null;
  }

  private static LocalDate fdate(JsonNode fields, String key) {
    JsonNode f = field(fields, key);
    if (f == null) {
      return null;
    }
    JsonNode v = f.get("valueDate");
    if (v == null || v.isNull()) {
      return null;
    }
    try {
      String s = v.asText();
      return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    } catch (DateTimeParseException e) {
      LOG.warn("Unparseable Azure date for {}", key);
      return null;
    }
  }

  private static double fconf(JsonNode fields, String key) {
    JsonNode f = field(fields, key);
    if (f == null) {
      return 0.0;
    }
    JsonNode c = f.get("confidence");
    return (c != null && c.isNumber()) ? c.asDouble() : 0.0;
  }

  private static String deriveEik(String vat) {
    if (vat == null) {
      return null;
    }
    String v = vat.replace(" ", "");
    String digits = v.toUpperCase().startsWith("BG") ? v.substring(2) : v;
    return digits.matches("\\d{9}(\\d{4})?") ? digits : null;
  }

  static BigDecimal parseRateToPercent(String raw) {
    if (raw == null) {
      return null;
    }
    BigDecimal d = decimal(raw.replace("%", "").trim());
    if (d == null) {
      return null;
    }
    // Azure gives "20%"/"20" (percentage) or 0.20 (fraction) → normalise to percentage.
    return (d.compareTo(BigDecimal.ONE) <= 0 ? d.multiply(new BigDecimal("100")) : d)
        .setScale(2, RoundingMode.HALF_UP);
  }

  static BigDecimal toPercent(double zeroToOne) {
    return new BigDecimal(zeroToOne * 100).setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal decimal(String s) {
    try {
      return new BigDecimal(s.trim());
    } catch (NumberFormatException | NullPointerException e) {
      return null;
    }
  }

  private JsonNode parse(String body) {
    if (body == null || body.trim().isEmpty()) {
      throw new OcrServiceException("Azure returned an empty body.");
    }
    try {
      return mapper.readTree(body);
    } catch (IOException e) {
      throw new OcrServiceException("Azure returned malformed JSON.", e);
    }
  }

  private static String text(JsonNode n, String field) {
    if (n == null || !n.hasNonNull(field)) {
      return null;
    }
    return n.get(field).asText();
  }

  // ---------------------------------------------------------------------------
  // Helpers / config
  // ---------------------------------------------------------------------------

  /**
   * Phrases that mark a 403 as <b>temporary call-volume exhaustion</b> rather than a refusal.
   *
   * <p>Azure answers a used-up free/low-tier allowance with HTTP 403 (not 429) and a body like
   * {@code {"error":{"code":"403","message":"Out of call volume quota. Quota will be replenished in
   * 2 days."}}}. That is a transient condition and the request is worth repeating; a permission,
   * key-auth-disabled or network-ACL 403 is not, and must never be retried.
   *
   * <p>Deliberately narrow. Matching a bare "quota" would also catch permanent refusals that merely
   * mention the word, and turning those into retries would spend the whole backoff budget on a call
   * that cannot succeed. Every phrase here names call volume or replenishment specifically.
   */
  private static final String[] QUOTA_EXHAUSTED_PHRASES = {
    "out of call volume quota",
    "out of quota",
    "call volume quota",
    "quota will be replenished",
    "will be replenished in"
  };

  /** How a quota-flavoured 403 should be treated. */
  protected enum QuotaState {
    /** Not a quota response at all (not a 403, or a 403 for some other reason). */
    NONE,
    /** A short, self-clearing dip — worth waiting out inside the normal backoff budget. */
    TEMPORARY,
    /**
     * A confirmed exhausted allowance (typically the F0 monthly page quota) that Azure says will
     * replenish far beyond anything we are prepared to wait. Must fail immediately.
     */
    EXHAUSTED
  }

  /**
   * Classify a 403 as not-quota / temporary / confirmed-exhausted.
   *
   * <p>Read from the response body, not from the status alone: 403 is the same status Azure uses
   * for a disabled key, a denied network rule and a missing role assignment, none of which improve
   * by asking again.
   *
   * <p>The temporary/exhausted split is what stops the pipeline wasting ~30 s of backoff per
   * document once the free tier's monthly page allowance is spent. Azure states the replenishment
   * delay in the message ({@code "Quota will be replenished in 2 days."}) and/or in {@code
   * Retry-After}; anything at or beyond {@link #quotaExhaustedThresholdSeconds()} is treated as
   * exhausted, because no backoff this class will sit through can outlast it.
   *
   * <p>A quota 403 that announces <b>no</b> delay is also not retried, but that is a policy
   * decision rather than a proven diagnosis: retrying a quota refusal three times inside 30 s
   * almost never succeeds, so the attempts are better spent nowhere. Because it is not proven,
   * {@link #submitRejectionMessage} says so in different words for this case — see {@link
   * #announcedQuotaDelaySeconds}. Claiming a confirmed monthly exhaustion here would send the
   * reader to the Azure portal to look at a quota that may be fine.
   */
  protected QuotaState classifyQuota(AzureResponse resp) {
    if (resp == null || resp.status != 403 || resp.body == null) {
      return QuotaState.NONE;
    }
    String body = resp.body.toLowerCase(java.util.Locale.ROOT);
    boolean quota = false;
    for (String phrase : QUOTA_EXHAUSTED_PHRASES) {
      if (body.contains(phrase)) {
        quota = true;
        break;
      }
    }
    if (!quota) {
      return QuotaState.NONE;
    }
    long announced = announcedQuotaDelaySeconds(resp);
    if (announced <= 0) {
      return QuotaState.EXHAUSTED; // no stated recovery time — do not invent a short one
    }
    return announced >= quotaExhaustedThresholdSeconds()
        ? QuotaState.EXHAUSTED
        : QuotaState.TEMPORARY;
  }

  /**
   * How long Azure said the allowance needs, in seconds, taking the longer of the message text and
   * a numeric {@code Retry-After}. {@code 0} means Azure stated no recovery time at all — which is
   * the difference between "the quota is spent until Tuesday" and "we do not know why yet", and the
   * two must not be reported as if they were the same fact.
   */
  protected long announcedQuotaDelaySeconds(AzureResponse resp) {
    if (resp == null || resp.body == null) {
      return 0L;
    }
    return Math.max(
        replenishSeconds(resp.body.toLowerCase(java.util.Locale.ROOT)),
        retryAfterSeconds(resp.retryAfter));
  }

  /**
   * Beyond this many seconds of announced replenishment delay, a quota 403 is a confirmed
   * exhaustion and is not retried.
   */
  protected int quotaExhaustedThresholdSeconds() {
    return cfgInt("aiocr.azure.quota.exhausted-threshold-seconds", 300);
  }

  /** {@code "quota will be replenished in 2 days"} → seconds; 0 when the body states no delay. */
  static long replenishSeconds(String lowerCaseBody) {
    if (lowerCaseBody == null) {
      return 0L;
    }
    java.util.regex.Matcher m = REPLENISH_IN.matcher(lowerCaseBody);
    if (!m.find()) {
      return 0L;
    }
    long amount;
    try {
      amount = Long.parseLong(m.group(1));
    } catch (NumberFormatException e) {
      return 0L;
    }
    String unit = m.group(2);
    if (unit.startsWith("sec")) {
      return amount;
    }
    if (unit.startsWith("min")) {
      return amount * 60L;
    }
    if (unit.startsWith("hour") || unit.startsWith("hr")) {
      return amount * 3600L;
    }
    return amount * 86_400L; // day(s)
  }

  /** A numeric {@code Retry-After} in seconds; 0 when absent or an HTTP-date. */
  private static long retryAfterSeconds(String retryAfter) {
    if (retryAfter == null) {
      return 0L;
    }
    try {
      return Math.max(0L, Long.parseLong(retryAfter.trim()));
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  private static final java.util.regex.Pattern REPLENISH_IN =
      java.util.regex.Pattern.compile(
          "replenished\\s+in\\s+(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)");

  /**
   * The message for a 401/403 submit rejection, saying which of the two it actually is.
   *
   * <p>A fixed "Azure authentication failed. Check endpoint/key." for both statuses would throw
   * away Azure's own explanation — already parsed and sitting in the response body. When a
   * free-tier allowance runs out, the record would then blame the credentials, and the reader would
   * re-check an endpoint and key that are both fine. Azure's text is the useful part, so it is
   * kept.
   *
   * @param chunkRange e.g. "pages 1-3" for the chunked path; null for a whole-document submit
   */
  protected String submitRejectionMessage(AzureResponse resp, String chunkRange) {
    String where = chunkRange == null ? "" : " for OCR chunk " + chunkRange;
    String detail = azureError(resp.body);
    QuotaState quota = classifyQuota(resp);
    if (quota == QuotaState.EXHAUSTED && announcedQuotaDelaySeconds(resp) > 0) {
      // Azure named a recovery time long enough to rule out a momentary dip, so the diagnosis is
      // evidence-based and the message can state it. It also says no retry was attempted: the
      // older wording left the reader expecting the job to come back by itself.
      return "Azure OCR allowance exhausted (HTTP "
          + resp.status
          + ")"
          + where
          + ": "
          + detail
          + ". This is the resource's call-volume/page quota, not temporary rate limiting, so the"
          + " request was NOT retried. Processing resumes when Azure replenishes the allowance or"
          + " when the resource is moved to a higher tier; retry this document afterwards.";
    }
    if (quota == QuotaState.EXHAUSTED) {
      // Same fail-fast, deliberately weaker claim: Azure refused on quota grounds but named no
      // recovery time, so we do NOT know that the monthly allowance is spent. Saying we did would
      // send the reader to check a quota that may be perfectly healthy.
      return "Azure refused the request on quota grounds (HTTP "
          + resp.status
          + ")"
          + where
          + ": "
          + detail
          + ". Azure did not state when the allowance returns, so the request was not retried — a"
          + " quota refusal rarely clears within the few seconds a retry would cover. Check the"
          + " resource's quota/tier in Azure; if this was a brief dip, retry this document.";
    }
    if (quota == QuotaState.TEMPORARY) {
      return "Azure quota/call-volume exceeded (HTTP "
          + resp.status
          + ")"
          + where
          + ": "
          + detail
          + ". The OCR allowance for this Azure resource is used up; it resumes when Azure"
          + " replenishes it, or on a higher tier.";
    }
    // 401, or a 403 that is a genuine refusal: permissions, key auth disabled, or a network rule.
    return "Azure rejected authentication (HTTP "
        + resp.status
        + ")"
        + where
        + ": "
        + detail
        + ". Check the endpoint, the key, and any network/firewall rule on the Azure resource.";
  }

  /** Extract Azure's {@code error.code: error.message} from a rejection body; falls back to raw. */
  private String azureError(String body) {
    if (body == null || body.trim().isEmpty()) {
      return "no error body";
    }
    body = redactKey(body);
    try {
      JsonNode err = mapper.readTree(body).get("error");
      if (err != null && !err.isNull()) {
        String code = text(err, "code");
        String message = text(err, "message");
        String joined =
            (code == null ? "" : code)
                + (code != null && message != null ? ": " : "")
                + (message == null ? "" : message);
        if (!joined.trim().isEmpty()) {
          return joined.length() > 300 ? joined.substring(0, 300) : joined;
        }
      }
    } catch (IOException ignore) {
      // fall through to raw body
    }
    return body.length() > 200 ? body.substring(0, 200) : body;
  }

  /**
   * Belt-and-braces: never let the subscription key reach an error message.
   *
   * <p>Azure does not echo the {@code Ocp-Apim-Subscription-Key} header back, so in practice this
   * changes nothing. But these messages are stored on the OCR record and shown in the UI, and the
   * body being quoted comes from whatever host {@code endpoint()} points at — which is
   * configuration, not a guarantee. Redacting costs one string compare.
   */
  private String redactKey(String body) {
    String key = key();
    if (body == null || key == null || key.trim().length() < 8) {
      return body;
    }
    return body.contains(key) ? body.replace(key, "***") : body;
  }

  /**
   * User-friendly, non-error note describing the adaptive lossy TRANSPORT compression applied to
   * one oversized page. Built from the STRUCTURED {@link
   * PdfTransportCompressionService.CompressionResult} (never by parsing a log line). Keeps every
   * technical detail: page, original lossless size, compressed transport size, DPI, JPEG quality,
   * and the configured Azure per-request limit.
   */
  static String formatCompressionNote(
      int page, PdfTransportCompressionService.CompressionResult r, int capMb) {
    return "Page "
        + page
        + " exceeded the Azure OCR upload size limit and was temporarily compressed for OCR"
        + " processing only. The original client PDF was not modified. Transport copy: "
        + mb(r.originalBytes)
        + " MB → "
        + mb(r.compressedBytes)
        + " MB; resolution: "
        + r.dpi
        + " DPI; JPEG quality: "
        + String.format(Locale.ROOT, "%.2f", r.quality)
        + "; Azure request limit: "
        + capMb
        + " MB.";
  }

  private static String mb(long bytes) {
    return String.format(Locale.ROOT, "%.2f", bytes / (1024.0 * 1024.0));
  }

  private OcrExtractResult failed(String reason) {
    OcrExtractResult r = new OcrExtractResult();
    r.setEngineUsed(ENGINE);
    r.setExtractionFailed(true);
    r.setConfidence(BigDecimal.ZERO);
    r.getErrors().add(reason);
    return r;
  }

  private static boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }

  private static String stripTrailingSlash(String s) {
    return s.replaceAll("/+$", "");
  }

  private String resolveMime(MetaFile file, Path path) {
    String mime = file.getFileType();
    if (mime != null && !mime.trim().isEmpty()) {
      return mime.trim();
    }
    try {
      return Files.probeContentType(path);
    } catch (IOException e) {
      return null;
    }
  }

  private Set<String> allowedMime() {
    return Arrays.stream(cfg("aiocr.extractor.allowed-mime", DEFAULT_ALLOWED_MIME).split(","))
        .map(s -> s.trim().toLowerCase())
        .filter(s -> !s.isEmpty())
        .collect(Collectors.toCollection(HashSet::new));
  }

  protected String endpoint() {
    return firstNonBlank(env(ENV_ENDPOINT), cfg("aiocr.azure.endpoint", ""));
  }

  protected String key() {
    return firstNonBlank(env(ENV_KEY), cfg("aiocr.azure.key", ""));
  }

  protected String model() {
    return cfg("aiocr.azure.model", DEFAULT_MODEL);
  }

  protected String apiVersion() {
    return cfg("aiocr.azure.api-version", DEFAULT_API_VERSION);
  }

  /**
   * Maximum bytes Azure will accept in ONE analyze request (configurable, defaults to the free-tier
   * ~4 MB). A PDF over this is OCR'd in chunks; an image/single page over it fails. Falls back to
   * the legacy {@code aiocr.extractor.max-file-size-mb} key when the new key is unset.
   */
  protected int maxRequestMb() {
    return cfgInt("aiocr.azure.max-request-mb", 4);
  }

  /**
   * Maximum pages Azure will accept in ONE analyze request (configurable, defaults to the free-tier
   * limit of 2) — also the page granularity when chunking a larger PDF.
   */
  protected int maxRequestPages() {
    return cfgInt("aiocr.azure.max-request-pages", 2);
  }

  /**
   * Absolute page ceiling for the whole document: beyond this, chunking would mean too many Azure
   * calls, so the document is failed outright (0 disables the check).
   */
  protected int maxPages() {
    return cfgInt("aiocr.extractor.max-pages", 2000);
  }

  protected int maxRetries() {
    return cfgInt("aiocr.azure.max-retries", 3);
  }

  protected int connectTimeout() {
    return cfgInt("aiocr.azure.connect-timeout-seconds", 15);
  }

  protected int requestTimeout() {
    return cfgInt("aiocr.azure.request-timeout-seconds", 60);
  }

  protected int pollIntervalMs() {
    return cfgInt("aiocr.azure.poll-interval-ms", 1500);
  }

  protected int pollMaxSeconds() {
    return cfgInt("aiocr.azure.poll-max-seconds", 120);
  }

  private static String env(String name) {
    String v = System.getenv(name);
    return v == null ? "" : v.trim();
  }

  private static String firstNonBlank(String a, String b) {
    return (a != null && !a.isEmpty()) ? a : (b == null ? "" : b);
  }

  private String cfg(String key, String def) {
    String v = AppSettings.get().get(key, def);
    return v == null ? def : v;
  }

  private int cfgInt(String key, int def) {
    try {
      return Integer.parseInt(AppSettings.get().get(key, String.valueOf(def)).trim());
    } catch (NumberFormatException e) {
      return def;
    }
  }
}

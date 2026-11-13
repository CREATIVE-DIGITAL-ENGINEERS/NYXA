package com.axelor.apps.aiocr.service.ocr;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One structured INFO timing line per OCR unit of work, so a slow upload can be attributed to a
 * phase instead of guessed at.
 *
 * <h3>What it records, and what it must never record</h3>
 *
 * Durations, counts and byte sizes only — plus the {@code AiOcrUpload} id, which is already in
 * every other log line. <b>No document content, no extracted field, no supplier identity, no file
 * name, no endpoint, no key.</b> The whole point is that this line is safe to leave on at INFO in
 * production and safe to paste into a ticket.
 *
 * <h3>Why a ThreadLocal</h3>
 *
 * One upload is processed start-to-finish on a single worker thread: the executor claims it, the
 * intake normalizes it, the extractor runs every Azure chunk sequentially on that same thread, and
 * routing finishes on it. A thread-local therefore reaches every phase without threading a context
 * object through a dozen signatures in {@code BgOcrIntakeService} and the extractor — which is what
 * keeps this change additive rather than a refactor of the pipeline.
 *
 * <p>Because it is thread-confined it needs no synchronization. It is cleared in a {@code finally}
 * by {@link #end()}, which matters on a pooled thread: a leaked trace would attribute the next
 * document's timings to the previous one.
 *
 * <p>Every recording method is a null-safe static no-op when no trace is active, so instrumentation
 * can be dropped into any code path (including ones reached from tests or from a synchronous
 * controller) without a guard and without ever throwing into the pipeline.
 */
public final class OcrPerfTrace {

  private static final Logger LOG = LoggerFactory.getLogger(OcrPerfTrace.class);

  private static final ThreadLocal<OcrPerfTrace> CURRENT = new ThreadLocal<>();

  private final String kind;
  private final Long id;
  private final long startNs;
  private final Map<String, Long> values = new LinkedHashMap<>();

  private OcrPerfTrace(String kind, Long id, long queueWaitMs) {
    this.kind = kind;
    this.id = id;
    this.startNs = System.nanoTime();
    if (queueWaitMs >= 0) {
      values.put("queueWaitMs", queueWaitMs);
    }
  }

  /**
   * Begin a trace on this thread.
   *
   * @param kind the unit of work — {@code process}, {@code reprocess}, {@code extract}, {@code
   *     split}
   * @param id the AiOcrUpload id this work belongs to
   * @param queueWaitMs how long the task sat in the worker queue before starting, or a negative
   *     value when that is not known
   */
  public static OcrPerfTrace begin(String kind, Long id, long queueWaitMs) {
    OcrPerfTrace trace = new OcrPerfTrace(kind, id, queueWaitMs);
    CURRENT.set(trace);
    return trace;
  }

  /** The trace active on this thread, or {@code null}. */
  public static OcrPerfTrace current() {
    return CURRENT.get();
  }

  /** Emit the summary line and clear the thread. Safe to call when no trace is active. */
  public static void end() {
    OcrPerfTrace trace = CURRENT.get();
    CURRENT.remove();
    if (trace == null) {
      return;
    }
    try {
      if (LOG.isInfoEnabled()) {
        LOG.info(trace.summary());
      }
    } catch (RuntimeException e) {
      // Telemetry must never break the pipeline.
      LOG.debug("Could not emit the OCR performance trace: {}", e.getMessage());
    }
  }

  /** Add {@code ms} to a duration counter (accumulates across repeated phases, e.g. per chunk). */
  public static void add(String name, long ms) {
    OcrPerfTrace trace = CURRENT.get();
    if (trace == null || ms <= 0) {
      return;
    }
    trace.values.merge(name, ms, Long::sum);
  }

  /**
   * Add the elapsed time since {@code startNs} (a {@link System#nanoTime()} reading) to a phase.
   */
  public static void since(String name, long startNs) {
    add(name, (System.nanoTime() - startNs) / 1_000_000L);
  }

  /** Increment an event counter (requests, 429s, retries, chunks). */
  public static void count(String name, long by) {
    OcrPerfTrace trace = CURRENT.get();
    if (trace == null || by <= 0) {
      return;
    }
    trace.values.merge(name, by, Long::sum);
  }

  /**
   * Keep the LARGEST value seen for this key. Used for figures where the peak is the diagnostic and
   * a sum would hide it — notably the raw {@code Retry-After} Azure asked for, where "30" and
   * "3600" mean completely different things and must not be averaged away.
   */
  public static void max(String name, long value) {
    OcrPerfTrace trace = CURRENT.get();
    if (trace == null || value <= 0) {
      return;
    }
    trace.values.merge(name, value, Long::max);
  }

  /** Record an absolute (non-accumulating) value, e.g. a page count. */
  public static void set(String name, long value) {
    OcrPerfTrace trace = CURRENT.get();
    if (trace == null) {
      return;
    }
    trace.values.put(name, value);
  }

  /** The structured {@code key=value} line. Visible for tests. */
  String summary() {
    StringBuilder sb = new StringBuilder(160);
    sb.append("AI-OCR perf kind=").append(kind);
    if (id != null) {
      sb.append(" id=").append(id);
    }
    sb.append(" totalMs=").append((System.nanoTime() - startNs) / 1_000_000L);
    for (Map.Entry<String, Long> e : values.entrySet()) {
      sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
    }
    return sb.toString();
  }
}

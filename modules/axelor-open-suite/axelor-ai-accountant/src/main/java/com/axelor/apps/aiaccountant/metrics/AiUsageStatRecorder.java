package com.axelor.apps.aiaccountant.metrics;

import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.servlet.RequestScoper;
import com.google.inject.servlet.ServletScopes;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persists measured requests on one background thread, off the path of whatever produced them.
 *
 * <h2>Why not simply save where the metric is emitted</h2>
 *
 * {@code AiRequestMetrics} fires deep inside a call that usually has a business transaction open. A
 * save there joins that transaction — Guice-persist has no notion of a nested one — so a failed
 * insert, a constraint violation or a closed connection could mark an invoice, an OCR intake or a
 * chat turn rollback-only. Telemetry is not permitted to cost the work it measures, so the write
 * happens somewhere else entirely.
 *
 * <h2>Lifecycle is owned, not improvised</h2>
 *
 * One daemon thread, started from {@code StartupEvent} and stopped from {@code ShutdownEvent} (see
 * {@code AiAccountantServerStartListener}) — the same hooks {@code AiOcrProcessingExecutor} uses,
 * which is what keeps a redeploy from leaving the previous deployment's thread behind. Each unit of
 * work opens its own Guice request scope, exactly as {@code ThreadedJob} does for a scheduled job,
 * because the persistence context is request-scoped and this thread is not a request.
 *
 * <h2>A full queue drops rows, deliberately</h2>
 *
 * {@link #record} never blocks and never waits: a burst that outruns the writer, or a shutdown with
 * items still queued, loses those rows. That is an accepted trade rather than an oversight — Azure
 * Monitor is the authority on totals, and this table is the authority on the breakdown. Losing some
 * breakdown detail is survivable; making a user wait on a metric, or dropping their turn because a
 * metrics insert failed, is not.
 */
@Singleton
public class AiUsageStatRecorder {

  private static final Logger LOG = LoggerFactory.getLogger(AiUsageStatRecorder.class);

  /** One row is tiny and the writer is fast; this only has to absorb a burst, not a backlog. */
  protected static final int QUEUE_CAPACITY = 512;

  /** How long the worker waits for a row before looping, so shutdown is observed promptly. */
  protected static final long POLL_SECONDS = 1L;

  /** How long shutdown waits for the worker to finish the row in its hands. */
  protected static final long SHUTDOWN_WAIT_SECONDS = 5L;

  private final BlockingQueue<AiUsageSnapshot> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
  private final AtomicLong dropped = new AtomicLong();

  private volatile boolean running;
  private Thread worker;

  public synchronized void start() {
    if (running) {
      return;
    }
    running = true;
    worker = new Thread(this::drainUntilStopped, "ai-usage-stat-recorder");
    worker.setDaemon(true);
    worker.start();
    LOG.info("AI Accountant: usage statistics recorder started.");
  }

  public synchronized void shutdown() {
    if (!running) {
      return;
    }
    running = false;
    if (worker != null) {
      worker.interrupt();
      try {
        worker.join(TimeUnit.SECONDS.toMillis(SHUTDOWN_WAIT_SECONDS));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      worker = null;
    }
    long lost = queue.size() + dropped.get();
    queue.clear();
    LOG.info(
        "AI Accountant: usage statistics recorder stopped ({} row(s) not written; Azure Monitor"
            + " remains the authority on totals).",
        lost);
  }

  /** True while the worker is up. Exposed for the lifecycle test, not for callers to branch on. */
  public boolean isRunning() {
    return running;
  }

  /** How many rows a full queue has discarded since boot. */
  public long droppedCount() {
    return dropped.get();
  }

  /**
   * Hands one measurement over. Never blocks, never throws.
   *
   * <p>Dropped rather than queued when the recorder is stopped: with no writer running, an
   * unbounded accumulation would be a memory leak that only shows up on a long-running instance
   * with the assistant switched off.
   */
  public void record(AiUsageSnapshot snapshot) {
    if (snapshot == null || !running) {
      return;
    }
    if (!queue.offer(snapshot)) {
      long total = dropped.incrementAndGet();
      LOG.debug(
          "AI Accountant: usage statistics queue is full; dropped this row ({} in total).", total);
    }
  }

  protected void drainUntilStopped() {
    while (running) {
      AiUsageSnapshot snapshot;
      try {
        snapshot = queue.poll(POLL_SECONDS, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if (snapshot != null) {
        writeInOwnScope(snapshot);
      }
    }
  }

  /**
   * One row, in its own request scope and its own transaction.
   *
   * <p>Everything is swallowed: this thread has no caller to report to, and a metrics failure is
   * not an application failure. {@code JPA.clear()} keeps the long-lived thread's persistence
   * context from accumulating every entity it has ever touched.
   */
  protected void writeInOwnScope(AiUsageSnapshot snapshot) {
    RequestScoper scope = ServletScopes.scopeRequest(Collections.emptyMap());
    try (RequestScoper.CloseableScope ignored = scope.open()) {
      writer().write(snapshot);
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not record usage statistics: {}", e.getMessage());
    } finally {
      try {
        JPA.clear();
      } catch (RuntimeException e) {
        LOG.debug("AI Accountant: could not clear the recorder's context: {}", e.getMessage());
      }
    }
  }

  protected AiUsageStatWriter writer() {
    return Beans.get(AiUsageStatWriter.class);
  }
}

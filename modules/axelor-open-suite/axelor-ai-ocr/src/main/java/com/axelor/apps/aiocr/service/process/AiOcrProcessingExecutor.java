package com.axelor.apps.aiocr.service.process;

import com.axelor.app.AppSettings;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.apps.aiocr.service.audit.AiOcrUploadAuditService;
import com.axelor.apps.aiocr.service.ingest.AiOcrArchiveExtractService;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.aiocr.service.ocr.OcrPerfTrace;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import com.google.inject.servlet.RequestScoper;
import com.google.inject.servlet.ServletScopes;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * App-managed background processing for the AI-OCR pipeline (Quartz stays OFF). Two bounded pools —
 * a small extraction pool and an OCR worker pool (3–5) with an explicitly bounded queue — plus a
 * continuous DB-backed dispatcher that drains {@code queued} documents whenever worker capacity
 * frees up, so records can never stay stranded. Each task runs on its own thread with its own short
 * persistence context/transaction; only IDs cross threads. The atomic {@code QUEUED→PROCESSING}
 * claim in {@link BgOcrIntakeService} is the final duplicate-processing guard.
 */
@Singleton
public class AiOcrProcessingExecutor {

  private static final Logger LOG = LoggerFactory.getLogger(AiOcrProcessingExecutor.class);

  protected final AiOcrUploadRepository uploadRepo;

  private volatile boolean running = false;
  private ExecutorService extractPool;
  private ThreadPoolExecutor ocrPool;
  private Semaphore permits;
  private Thread dispatcher;
  private final Object signal = new Object();
  private final Set<Long> inFlight = ConcurrentHashMap.newKeySet();

  @Inject
  public AiOcrProcessingExecutor(AiOcrUploadRepository uploadRepo) {
    this.uploadRepo = uploadRepo;
  }

  // ---------------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------------

  public synchronized void start() {
    if (running) {
      return;
    }
    int poolSize = clamp(cfgInt("aiocr.process.pool-size", 4), 3, 5);
    int queueCap = Math.max(1, cfgInt("aiocr.process.queue-capacity", 100));
    int extractSize = Math.max(1, cfgInt("aiocr.extract.pool-size", 2));
    permits = new Semaphore(poolSize + queueCap);
    ocrPool =
        new ThreadPoolExecutor(
            poolSize, poolSize, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCap));
    extractPool = Executors.newFixedThreadPool(extractSize);
    running = true;
    dispatcher = new Thread(this::dispatchLoop, "aiocr-dispatcher");
    dispatcher.setDaemon(true);
    dispatcher.start();
    LOG.info(
        "AI-OCR executor started (ocr={}, queue={}, extract={}).", poolSize, queueCap, extractSize);
  }

  /** Graceful shutdown: reject new submissions, stop the dispatcher, drain within a timeout. */
  public synchronized void shutdown() {
    if (!running) {
      return;
    }
    running = false;
    wake();
    if (dispatcher != null) {
      dispatcher.interrupt();
    }
    shutdownPool(extractPool);
    shutdownPool(ocrPool);
    LOG.info("AI-OCR executor stopped.");
  }

  private static void shutdownPool(ExecutorService pool) {
    if (pool == null) {
      return;
    }
    pool.shutdown();
    try {
      if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {
        pool.shutdownNow();
      }
    } catch (InterruptedException e) {
      pool.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }

  /** Wake the dispatcher (after extraction, worker completion, or startup recovery). */
  public void wake() {
    synchronized (signal) {
      signal.notifyAll();
    }
  }

  // ---------------------------------------------------------------------------
  // Submission
  // ---------------------------------------------------------------------------

  /** Extract a ZIP carrier in the background, then wake the dispatcher to process its documents. */
  public void submitExtraction(Long carrierId) {
    start();
    if (!running || carrierId == null) {
      return;
    }
    final long submittedNs = System.nanoTime();
    extractPool.execute(
        () ->
            withTrace(
                "extract",
                carrierId,
                submittedNs,
                () ->
                    inRequestScope(
                        () -> {
                          try {
                            Beans.get(AiOcrArchiveExtractService.class).extract(carrierId);
                          } catch (Exception e) {
                            LOG.error(
                                "Extraction failed for carrier {}: {}", carrierId, e.getMessage());
                          }
                        })));
    wake();
  }

  /**
   * Split a multi-document PDF carrier in the background (reusing its stored single OCR result),
   * then wake the dispatcher to process the per-document children. Mirrors {@link
   * #submitExtraction}.
   */
  public void submitSplit(Long carrierId) {
    start();
    if (!running || carrierId == null) {
      return;
    }
    final long submittedNs = System.nanoTime();
    extractPool.execute(
        () ->
            withTrace(
                "split",
                carrierId,
                submittedNs,
                () ->
                    inRequestScope(
                        () -> {
                          try {
                            Beans.get(
                                    com.axelor.apps.aiocr.service.ingest.AiOcrPdfSplitService.class)
                                .split(carrierId);
                          } catch (Exception e) {
                            LOG.error(
                                "PDF split failed for carrier {}: {}", carrierId, e.getMessage());
                          }
                        })));
    wake();
  }

  /**
   * Split a multi-bank-statement PDF carrier in the background (reusing its stored single OCR
   * result), then wake the dispatcher to process the per-statement children. Mirrors {@link
   * #submitSplit}.
   */
  public void submitBankStatementSplit(Long carrierId) {
    start();
    if (!running || carrierId == null) {
      return;
    }
    final long submittedNs = System.nanoTime();
    extractPool.execute(
        () ->
            withTrace(
                "bankStatementSplit",
                carrierId,
                submittedNs,
                () ->
                    inRequestScope(
                        () -> {
                          try {
                            Beans.get(
                                    com.axelor.apps.aiocr.service.ingest.AiOcrPdfSplitService.class)
                                .splitBankStatements(carrierId);
                          } catch (Exception e) {
                            LOG.error(
                                "Bank-statement split failed for carrier {}: {}",
                                carrierId,
                                e.getMessage());
                          }
                        })));
    wake();
  }

  /**
   * Run the full OCR pipeline for a single upload the caller has ALREADY claimed (status
   * PROCESSING) on the request thread. Used by the manual "Process"/"Reprocess" buttons: the
   * controller flips the row to PROCESSING synchronously (so the green button disappears at once
   * and a second click is a no-op), then hands the claimed id here to run the heavy work —
   * normalization, the long Azure OCR call, splitting and routing — off the request thread. Mirrors
   * {@link #submitExtraction}; failures are recorded as FAILED so nothing is lost.
   */
  public void submitProcess(Long uploadId) {
    start();
    if (!running || uploadId == null) {
      return;
    }
    final long submittedNs = System.nanoTime();
    extractPool.execute(
        () ->
            withTrace(
                "process",
                uploadId,
                submittedNs,
                () ->
                    inRequestScope(
                        () -> {
                          try {
                            Beans.get(BgOcrIntakeService.class).processClaimed(uploadId);
                          } catch (Exception e) {
                            try {
                              Beans.get(BgOcrIntakeService.class)
                                  .markFailed(uploadId, rootMessage(e));
                            } catch (Exception ignore) {
                              // markFailed runs in its own tx; never let one bad job stop the pump
                            }
                          }
                          recomputeCarrierQuietly(uploadId);
                        })));
    wake();
  }

  /**
   * Rerun local parsing/routing for a single upload the caller has ALREADY claimed (status
   * PROCESSING) from its own stored OCR result — no Azure call. Used by the smart Retry action
   * ({@link BgOcrIntakeService#retryOne}) when {@link BgOcrIntakeService#decideRetryMode} finds a
   * usable stored extraction. Mirrors {@link #submitProcess} exactly, calling {@link
   * BgOcrIntakeService#localReprocess} instead of {@link BgOcrIntakeService#processClaimed};
   * failures are recorded as FAILED so nothing is lost.
   */
  public void submitLocalReprocess(Long uploadId) {
    start();
    if (!running || uploadId == null) {
      return;
    }
    final long submittedNs = System.nanoTime();
    extractPool.execute(
        () ->
            withTrace(
                "reprocess",
                uploadId,
                submittedNs,
                () ->
                    inRequestScope(
                        () -> {
                          try {
                            Beans.get(BgOcrIntakeService.class).localReprocess(uploadId);
                          } catch (Exception e) {
                            try {
                              Beans.get(BgOcrIntakeService.class)
                                  .markFailed(uploadId, rootMessage(e));
                            } catch (Exception ignore) {
                              // markFailed runs in its own tx; never let one bad job stop the pump
                            }
                          }
                          recomputeCarrierQuietly(uploadId);
                        })));
    wake();
  }

  // ---------------------------------------------------------------------------
  // Continuous DB-backed dispatcher
  // ---------------------------------------------------------------------------

  private void dispatchLoop() {
    long intervalMs = Math.max(500L, cfgInt("aiocr.process.dispatch-interval-ms", 5000));
    while (running) {
      try {
        dispatchOnce();
      } catch (Exception e) {
        LOG.warn("Dispatcher cycle error: {}", e.getMessage());
      }
      synchronized (signal) {
        try {
          signal.wait(intervalMs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }

  private void dispatchOnce() {
    int free = permits.availablePermits();
    if (free <= 0) {
      return;
    }
    // Short, self-contained transaction to read the next page of queued IDs; then close it.
    final List<Long>[] holder = new List[] {Collections.emptyList()};
    inRequestScope(() -> holder[0] = fetchQueuedIds(free + inFlight.size()));
    for (Long id : holder[0]) {
      if (inFlight.contains(id)) {
        continue;
      }
      if (!permits.tryAcquire()) {
        break; // capacity full — leave the rest queued in the DB for a later cycle
      }
      inFlight.add(id);
      // Stamped at submission so runOcr can report how long the task actually sat in the worker
      // queue — the one number that separates "Azure is slow" from "all workers were busy".
      final long submittedNs = System.nanoTime();
      try {
        ocrPool.execute(() -> runOcr(id, submittedNs));
      } catch (RuntimeException reject) {
        // Rejected (shutdown / full): release and leave it queued for later.
        inFlight.remove(id);
        permits.release();
        break;
      }
    }
  }

  private void runOcr(Long id, long submittedNs) {
    try {
      withTrace(
          "process",
          id,
          submittedNs,
          () ->
              inRequestScope(
                  () -> {
                    try {
                      Beans.get(BgOcrIntakeService.class).process(id);
                    } catch (Exception e) {
                      try {
                        Beans.get(BgOcrIntakeService.class).markFailed(id, rootMessage(e));
                      } catch (Exception ignore) {
                        // markFailed runs in its own tx; never let one bad job stop the pump
                      }
                    }
                    recomputeCarrierQuietly(id);
                  }));
    } finally {
      inFlight.remove(id);
      permits.release();
      wake();
    }
  }

  /**
   * Run {@code work} under a performance trace that is always closed, even on failure.
   *
   * <p>The trace is thread-local and these pools are reused, so the {@code finally} is
   * load-bearing: a leaked trace would attribute the next document's phases to this one.
   */
  protected void withTrace(String kind, Long id, long submittedNs, Runnable work) {
    OcrPerfTrace.begin(kind, id, (System.nanoTime() - submittedNs) / 1_000_000L);
    try {
      work.run();
    } finally {
      OcrPerfTrace.end();
    }
  }

  // ---------------------------------------------------------------------------
  // Startup recovery
  // ---------------------------------------------------------------------------

  public void recoverOnStartup() {
    start();
    inRequestScope(
        () -> {
          try {
            int stale = resetStaleProcessing(cfgInt("aiocr.process.stale-timeout-seconds", 900));
            if (stale > 0) {
              LOG.info("Reset {} stale processing document(s) back to queued.", stale);
            }
            for (Long carrierId : findExtractingCarrierIds()) {
              submitExtraction(carrierId);
            }
            for (Long carrierId : findSplittingCarrierIds()) {
              submitSplit(carrierId);
            }
            for (Long carrierId : findBankStatementSplittingCarrierIds()) {
              submitBankStatementSplit(carrierId);
            }
            int reconciled = reconcileUnfinalizedCarriers();
            if (reconciled > 0) {
              LOG.info("Reconciled {} carrier(s) whose rollup had not been finalized.", reconciled);
            }
          } catch (Exception e) {
            LOG.warn("Startup recovery error: {}", e.getMessage());
          }
        });
    wake();
  }

  // ---------------------------------------------------------------------------
  // DB helpers (@Transactional — self-invocation is intercepted under Guice AOP)
  // ---------------------------------------------------------------------------

  @Transactional
  public List<Long> fetchQueuedIds(int limit) {
    List<AiOcrUpload> rows =
        uploadRepo
            .all()
            .filter("self.statusSelect = :queued AND self.originalFile IS NOT NULL")
            .bind("queued", AiOcrUploadStatus.QUEUED)
            .order("id")
            .fetch(Math.max(1, limit), 0);
    java.util.List<Long> ids = new java.util.ArrayList<>();
    for (AiOcrUpload r : rows) {
      ids.add(r.getId());
    }
    return ids;
  }

  @Transactional
  public int resetStaleProcessing(int timeoutSeconds) {
    LocalDateTime cutoff = LocalDateTime.now().minusSeconds(Math.max(1, timeoutSeconds));
    // Read the candidate ids BEFORE the atomic bulk update below (kept exactly as it was — its
    // guarded WHERE clause must stay the sole source of truth for which rows actually reset), so
    // the Upload Audit trail (bulk UPDATE bypasses JPA entity lifecycle) can be synced for them
    // afterwards, in this same @Transactional method: a persistence failure in that sync rolls
    // back the reset for the whole batch alongside it (see AiOcrUploadAuditService's class
    // javadoc). This only runs at startup recovery, so the narrow race between this SELECT and
    // the UPDATE below is not a practical concern.
    List<Long> candidateIds =
        JPA.em()
            .createQuery(
                "SELECT s.id FROM AiOcrUpload s WHERE s.statusSelect = :processing "
                    + "AND s.originalFile IS NOT NULL AND (s.lockedOn IS NULL OR s.lockedOn < :cutoff)",
                Long.class)
            .setParameter("processing", AiOcrUploadStatus.PROCESSING)
            .setParameter("cutoff", cutoff)
            .getResultList();
    int updated =
        JPA.em()
            .createQuery(
                "UPDATE AiOcrUpload s SET s.statusSelect = :queued, s.lockedOn = null "
                    + "WHERE s.statusSelect = :processing AND s.originalFile IS NOT NULL "
                    + "AND (s.lockedOn IS NULL OR s.lockedOn < :cutoff)")
            .setParameter("queued", AiOcrUploadStatus.QUEUED)
            .setParameter("processing", AiOcrUploadStatus.PROCESSING)
            .setParameter("cutoff", cutoff)
            .executeUpdate();
    if (!candidateIds.isEmpty()) {
      Beans.get(AiOcrUploadAuditService.class).syncByIds(candidateIds);
    }
    return updated;
  }

  @Transactional
  public List<Long> findExtractingCarrierIds() {
    List<AiOcrUpload> rows =
        uploadRepo
            .all()
            .filter("self.archiveFile IS NOT NULL AND self.statusSelect = :extracting")
            .bind("extracting", AiOcrUploadStatus.EXTRACTING)
            .fetch();
    java.util.List<Long> ids = new java.util.ArrayList<>();
    for (AiOcrUpload r : rows) {
      ids.add(r.getId());
    }
    return ids;
  }

  /** Split-PDF carriers left mid-split by a crash — re-submitted to the splitter on startup. */
  @Transactional
  public List<Long> findSplittingCarrierIds() {
    List<AiOcrUpload> rows =
        uploadRepo
            .all()
            .filter("self.carrierKind = :kind AND self.statusSelect = :extracting")
            .bind("kind", AiOcrUploadStatus.CARRIER_KIND_PDF_SPLIT)
            .bind("extracting", AiOcrUploadStatus.EXTRACTING)
            .fetch();
    java.util.List<Long> ids = new java.util.ArrayList<>();
    for (AiOcrUpload r : rows) {
      ids.add(r.getId());
    }
    return ids;
  }

  /** Bank-statement split carriers left mid-split by a crash — re-submitted on startup. */
  @Transactional
  public List<Long> findBankStatementSplittingCarrierIds() {
    List<AiOcrUpload> rows =
        uploadRepo
            .all()
            .filter("self.carrierKind = :kind AND self.statusSelect = :extracting")
            .bind("kind", AiOcrUploadStatus.CARRIER_KIND_PDF_BANK_STATEMENT)
            .bind("extracting", AiOcrUploadStatus.EXTRACTING)
            .fetch();
    java.util.List<Long> ids = new java.util.ArrayList<>();
    for (AiOcrUpload r : rows) {
      ids.add(r.getId());
    }
    return ids;
  }

  /**
   * Re-derive the status of every carrier that extraction finished but that is still sitting in a
   * non-terminal state, and return how many were revisited.
   *
   * <p>Why this is needed even with the rollup lock: the rollup only ever runs as a reaction to a
   * child finishing. If the process dies between a child's last completion and its carrier's rollup
   * — or if a carrier was already left behind by the pre-lock race — there is no event left to fire
   * and the carrier stays non-terminal forever. Nothing else reaches it either: {@link
   * #resetStaleProcessing} and the poll job both filter on {@code originalFile IS NOT NULL}, and an
   * archive carrier keeps its ZIP in {@code archiveFile} with a NULL {@code originalFile}.
   *
   * <p>Safe to run at every boot and safe to run repeatedly: it only re-reads child rows and
   * re-derives the same value {@link AiOcrCarrierService#recompute} would have written. It never
   * re-extracts, never re-submits a document and never calls Azure — a carrier whose children are
   * genuinely still unfinished simply recomputes to the same non-terminal status.
   */
  public int reconcileUnfinalizedCarriers() {
    AiOcrCarrierService carriers = Beans.get(AiOcrCarrierService.class);
    int count = 0;
    // Deliberately NOT @Transactional, and the ids are collected before the loop. Each recompute
    // therefore runs in its OWN transaction and releases its carrier locks before the next one
    // starts. Holding them all in one transaction would be a deadlock: this loop can reach a
    // parent ZIP before its nested carrier, while a worker finishing a child always locks the
    // nested carrier first and the ZIP second — two threads acquiring the same pair in opposite
    // order. One transaction per carrier keeps every lock chain strictly child → parent, which is
    // a consistent global order and therefore cycle-free. It also means one unreconcilable carrier
    // cannot roll back the others.
    for (Long carrierId : findUnfinalizedCarrierIds()) {
      try {
        carriers.recompute(carrierId);
        count++;
      } catch (Exception e) {
        LOG.warn("Could not reconcile carrier {}: {}", carrierId, e.getMessage());
      }
    }
    return count;
  }

  /** Ids of carriers whose extraction finished but whose status is still non-terminal. */
  @Transactional
  public List<Long> findUnfinalizedCarrierIds() {
    return JPA.em()
        .createQuery(
            "SELECT s.id FROM AiOcrUpload s WHERE s.extractionFinishedOn IS NOT NULL "
                + "AND s.statusSelect IN :open "
                + "AND EXISTS (SELECT 1 FROM AiOcrUpload c WHERE c.importArchive = s) "
                + "ORDER BY s.id",
            Long.class)
        .setParameter(
            "open",
            java.util.Arrays.asList(AiOcrUploadStatus.EXTRACTING, AiOcrUploadStatus.PROCESSING))
        .getResultList();
  }

  @Transactional
  public void recomputeCarrierForDoc(Long docId) {
    AiOcrUpload doc = uploadRepo.find(docId);
    if (doc != null && doc.getImportArchive() != null) {
      Beans.get(AiOcrCarrierService.class).recompute(doc.getImportArchive().getId());
    }
  }

  /**
   * The carrier rollup for a finished document, which must never take the worker down with it.
   *
   * <p>Since {@code lockForRollup} now refuses to compute from an unlocked read, a rollup CAN fail
   * (a lock timeout that outlasts its retries, or a doomed transaction). The document itself is
   * already committed by then, so the only consequence is a carrier left non-terminal — which
   * {@link #reconcileUnfinalizedCarriers} finalizes at the next startup. Caught OUTSIDE the
   * transactional proxy on purpose, so a rollback raised at commit time is caught here too, and
   * logged rather than swallowed by the thread pool.
   */
  protected void recomputeCarrierQuietly(Long docId) {
    try {
      recomputeCarrierForDoc(docId);
    } catch (Exception e) {
      LOG.warn(
          "Carrier rollup for document {} did not complete ({}); the carrier is left for startup"
              + " reconciliation.",
          docId,
          e.getMessage());
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  protected void inRequestScope(Runnable work) {
    RequestScoper scope = ServletScopes.scopeRequest(Collections.emptyMap());
    try (RequestScoper.CloseableScope ignored = scope.open()) {
      work.run();
    } finally {
      JPA.clear();
    }
  }

  protected static int clamp(int v, int lo, int hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  protected static String rootMessage(Throwable t) {
    String message = null;
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c.getMessage() != null && !c.getMessage().trim().isEmpty()) {
        message = c.getMessage().trim();
      }
    }
    return message != null ? message : t.toString();
  }

  protected static int cfgInt(String key, int def) {
    try {
      return Integer.parseInt(AppSettings.get().get(key, String.valueOf(def)).trim());
    } catch (RuntimeException e) {
      return def;
    }
  }
}

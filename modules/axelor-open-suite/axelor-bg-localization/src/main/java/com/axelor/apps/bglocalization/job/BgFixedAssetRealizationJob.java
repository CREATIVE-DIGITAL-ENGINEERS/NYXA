package com.axelor.apps.bglocalization.job;

import com.axelor.apps.account.db.AccountingBatch;
import com.axelor.apps.account.service.batch.AccountingBatchService;
import com.axelor.apps.base.job.ThreadedJob;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.service.batch.BgFixedAssetRealizationPreparationService;
import com.axelor.apps.bglocalization.service.batch.BgFixedAssetRealizationSettingsService;
import com.axelor.db.JPA;
import com.google.inject.Inject;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Monthly job (fires on the 1st): for every company selected on the "Depreciation Automation"
 * settings screen (Accounting > Fixed assets — see {@link BgFixedAssetRealizationSettingsService}),
 * realizes due fixed-asset depreciation lines into draft accounting moves via the existing {@code
 * BatchRealizeFixedAssetLine} / {@code BgBatchRealizeFixedAssetLine} batch — unchanged, called
 * exactly as the Administration UI "Run" button already does. The selected companies are the ONLY
 * scope: an empty selection means nothing runs, and there is deliberately no fallback to "every
 * active company".
 *
 * <p>Extends {@link ThreadedJob} (not a plain {@code org.quartz.Job}) because that is the only
 * verified way, in this codebase, for a Quartz-triggered class to get a working Guice
 * request/persistence scope (see {@code GuiceJobFactory}, which does no such wrapping itself).
 * {@code GuiceJobFactory.newJob} instantiates the job via {@code Beans.get(jobClass)}, so this
 * constructor is injected normally, same as any other Guice-managed class.
 *
 * <p><b>Concurrency guard.</b> {@code @DisallowConcurrentExecution} alone is NOT sufficient here.
 * {@link ThreadedJob#execute} blocks on {@code thread.join()} for the normal-path duration of
 * {@link #executeInThread}, but if the Quartz worker thread running {@code execute()} is itself
 * interrupted while blocked on that {@code join()} (scheduler shutdown, thread-pool recycling, …),
 * the {@code catch (InterruptedException e)} branch lets {@code execute()} return through its
 * {@code finally} block while the spawned worker thread keeps running {@link #executeInThread}
 * detached — at that point Quartz's "currently executing" bookkeeping clears, so {@code
 * DisallowConcurrentExecution} stops protecting anything. That is shared {@code ThreadedJob}
 * behaviour (used by other jobs too), not something to redesign here. Instead, {@link #RUNNING} is
 * a static guard scoped to exactly this class's {@link #executeInThread} lifetime — set on entry,
 * cleared in that method's own {@code finally} — so a second fire is refused for as long as the
 * real per-company loop is still running, regardless of what the outer {@code execute()} does.
 *
 * <p>Each company is prepared and dispatched as two separate calls to two separate Guice-managed
 * beans ({@link BgFixedAssetRealizationPreparationService#prepare} then {@link
 * AccountingBatchService#run}) so that neither call's transaction can extend into the other — see
 * the design plan for why wrapping both in one transaction was rejected. A failure for one company
 * is caught here, outside both calls, and does not stop the loop.
 *
 * <p>Companies are iterated by id, not by a held {@code Company} reference: {@link JPA#clear()}
 * runs after every company, which would otherwise leave the next iteration holding a detached
 * entity. {@link BgFixedAssetRealizationPreparationService#prepare(Long)} re-fetches the company
 * itself, inside its own transaction.
 */
@DisallowConcurrentExecution
public class BgFixedAssetRealizationJob extends ThreadedJob {

  private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

  protected final BgFixedAssetRealizationSettingsService settingsService;
  protected final BgFixedAssetRealizationPreparationService preparationService;
  protected final AccountingBatchService accountingBatchService;

  @Inject
  public BgFixedAssetRealizationJob(
      BgFixedAssetRealizationSettingsService settingsService,
      BgFixedAssetRealizationPreparationService preparationService,
      AccountingBatchService accountingBatchService) {
    this.settingsService = settingsService;
    this.preparationService = preparationService;
    this.accountingBatchService = accountingBatchService;
  }

  @Override
  public void executeInThread(JobExecutionContext context) {
    if (!RUNNING.compareAndSet(false, true)) {
      LOG.warn(
          "Fixed asset realization: a previous run is still in progress (or the outer Quartz "
              + "execute() returned early after an interrupt) — skipping this fire.");
      return;
    }
    try {
      for (Long companyId : selectedCompanyIds()) {
        try {
          // Call 1: its own transaction, fully committed (or rolled back) before this returns.
          AccountingBatch accountingBatch = preparationService.prepare(companyId);
          if (accountingBatch != null) {
            // Call 2: no ambient transaction here — AbstractBatch's own internal per-step
            // transactions run exactly as they do for any other caller.
            accountingBatchService.run(accountingBatch);
          }
        } catch (Exception e) {
          reportFailure(e);
        } finally {
          clearPersistenceContext();
        }
      }
    } finally {
      RUNNING.set(false);
    }
  }

  // A separate, overridable seam (rather than inlining the call in executeInThread) so a test
  // can supply a fixed company id list without needing a real settings row.
  protected List<Long> selectedCompanyIds() {
    return settingsService.findSelectedCompanyIds();
  }

  /**
   * Fires the same company loop immediately, off-cron, in a detached thread with its own Guice
   * request scope — mirroring exactly what {@link ThreadedJob#execute} does for a real Quartz fire,
   * minus the {@code thread.join()}: the caller (the "Run now" settings action) must not block the
   * HTTP request while depreciation is processed. Runs the persisted company selection regardless
   * of whether the monthly schedule is enabled — that flag only gates the Quartz trigger, never
   * this method. The static {@link #RUNNING} guard above still applies, so a manual run while a
   * scheduled run is already in progress (or vice-versa) is refused exactly like a re-entrant
   * Quartz fire.
   */
  public void triggerManualRun() {
    Thread thread = new Thread(() -> executeInThreadedRequestScope(null));
    thread.setUncaughtExceptionHandler(
        (t, e) -> {
          LOG.error("Fixed asset realization: manual run failed: {}", e.getMessage(), e);
          TraceBackService.trace(e);
        });
    thread.start();
  }

  /**
   * Records one company's failure without letting a failure IN the recording itself take down the
   * loop. {@link TraceBackService#trace(Throwable)} persists a TraceBack via {@code
   * JPA.runInTransaction(...)} — if the persistence context is itself unavailable (a pure unit test
   * with no container, but in principle also a real DB hiccup during error logging), that call
   * throws too; left uncaught, that second exception — not the original failure — would propagate
   * out of this method and abort the whole company loop, silently skipping every company after this
   * one. Catching it here and falling back to a plain logger call keeps the per-company isolation
   * guarantee regardless of what happens while reporting the error.
   */
  protected void reportFailure(Exception e) {
    try {
      TraceBackService.trace(e);
    } catch (Exception tracebackFailure) {
      LOG.error("Fixed asset realization: company processing failed: {}", e.getMessage(), e);
      LOG.error(
          "Fixed asset realization: recording that failure as a TraceBack ALSO failed: {}",
          tracebackFailure.getMessage(),
          tracebackFailure);
    }
  }

  /**
   * Real runtime behaviour is exactly {@code JPA.clear()} — never skip or weaken it in production.
   * This seam exists solely so a pure unit test (no Guice/Persist container, where {@code
   * JPA.clear()} itself throws "JPA context not initialized") can override it as a no-op, instead
   * of the test having to fake a whole persistence context just to observe the loop's control flow.
   */
  protected void clearPersistenceContext() {
    JPA.clear();
  }

  /** Test-only: resets the static concurrency guard between test methods. */
  static void resetConcurrencyGuardForTests() {
    RUNNING.set(false);
  }
}

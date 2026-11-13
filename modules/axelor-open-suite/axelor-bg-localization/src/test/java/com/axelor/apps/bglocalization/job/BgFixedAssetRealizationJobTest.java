package com.axelor.apps.bglocalization.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.AccountingBatch;
import com.axelor.apps.account.service.batch.AccountingBatchService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Batch;
import com.axelor.apps.bglocalization.service.batch.BgFixedAssetRealizationPreparationService;
import com.axelor.apps.bglocalization.service.batch.BgFixedAssetRealizationSettingsService;
import com.axelor.db.Model;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.DisallowConcurrentExecution;

/**
 * Unit tests for {@link BgFixedAssetRealizationJob}. No mocking framework is used, matching this
 * module's established style (fake behaviour via subclassing protected/overridable seams).
 *
 * <p>Scope: per-company failure isolation (one company's exception does not stop the loop), correct
 * skip when {@code prepare()} returns null, the presence of the Quartz concurrency guard
 * annotation, and the additional static full-lifetime guard that covers the case where {@code
 * ThreadedJob.execute()} can return early (see the class javadoc on {@link
 * BgFixedAssetRealizationJob} for why {@code @DisallowConcurrentExecution} alone is not enough).
 */
class BgFixedAssetRealizationJobTest {

  @BeforeEach
  void resetGuard() {
    BgFixedAssetRealizationJob.resetConcurrencyGuardForTests();
  }

  /** Fakes prepare() per company id without touching any repository/JPA machinery. */
  private static class FakePreparationService extends BgFixedAssetRealizationPreparationService {
    final Map<Long, AccountingBatch> results = new HashMap<>();

    FakePreparationService() {
      super(null, null, null, null);
    }

    @Override
    public AccountingBatch prepare(Long companyId) {
      return results.get(companyId);
    }
  }

  /** Fakes run() per AccountingBatch, optionally throwing for a marked one. */
  private static class FakeAccountingBatchService extends AccountingBatchService {
    final List<AccountingBatch> runCalls = new ArrayList<>();
    AccountingBatch shouldFail;

    @Override
    public Batch run(Model batchModel) throws AxelorException {
      runCalls.add((AccountingBatch) batchModel);
      if (batchModel == shouldFail) {
        throw new AxelorException(0, "boom");
      }
      return null;
    }
  }

  /** Fakes the company id list so no CompanyRepository/JPQL Query is needed. */
  private static class FixedCompanyIdsJob extends BgFixedAssetRealizationJob {
    final List<Long> companyIds;

    FixedCompanyIdsJob(
        List<Long> companyIds,
        BgFixedAssetRealizationPreparationService preparationService,
        AccountingBatchService accountingBatchService) {
      super(null, preparationService, accountingBatchService);
      this.companyIds = companyIds;
    }

    @Override
    protected List<Long> selectedCompanyIds() {
      return companyIds;
    }

    @Override
    protected void clearPersistenceContext() {
      // No-op: this is a pure unit test with no Guice/Persist container, so the real
      // JPA.clear() would throw "JPA context not initialized" here. Production still calls the
      // real JPA.clear() unconditionally — see BgFixedAssetRealizationJob.clearPersistenceContext.
    }
  }

  /**
   * Also proves the TraceBack-failure fallback for free: {@code reportFailure(e)} calls the REAL
   * {@code TraceBackService.trace(e)} here (not a fake), which itself throws "JPA context not
   * initialized" in this pure-test environment (no Guice/Persist container) — exactly the scenario
   * {@link BgFixedAssetRealizationJob#reportFailure} is meant to absorb. If that fallback didn't
   * work, this test would fail with company B never dispatched (or the test itself throwing), not
   * just company A's own failure being unhandled.
   */
  @Test
  void oneCompanysFailureDoesNotBlockTheNextCompany() {
    AccountingBatch batchA = new AccountingBatch();
    AccountingBatch batchB = new AccountingBatch();

    FakePreparationService preparationService = new FakePreparationService();
    preparationService.results.put(1L, batchA);
    preparationService.results.put(2L, batchB);

    FakeAccountingBatchService accountingBatchService = new FakeAccountingBatchService();
    accountingBatchService.shouldFail = batchA;

    FixedCompanyIdsJob job =
        new FixedCompanyIdsJob(List.of(1L, 2L), preparationService, accountingBatchService);
    job.executeInThread(null);

    assertEquals(
        List.of(batchA, batchB),
        accountingBatchService.runCalls,
        "company B must still be dispatched even though company A's run() threw AND the "
            + "subsequent TraceBack recording of that failure also threw");
  }

  @Test
  void aSkippedCompanyIsNeverDispatched() {
    AccountingBatch batchB = new AccountingBatch();

    FakePreparationService preparationService = new FakePreparationService();
    preparationService.results.put(1L, null); // prepare() says skip
    preparationService.results.put(2L, batchB);

    FakeAccountingBatchService accountingBatchService = new FakeAccountingBatchService();

    FixedCompanyIdsJob job =
        new FixedCompanyIdsJob(List.of(1L, 2L), preparationService, accountingBatchService);
    job.executeInThread(null);

    assertEquals(
        List.of(batchB),
        accountingBatchService.runCalls,
        "a company prepare() skips must never reach run()");
  }

  @Test
  void concurrencyGuardAnnotationIsPresent() {
    assertTrue(
        BgFixedAssetRealizationJob.class.isAnnotationPresent(DisallowConcurrentExecution.class),
        "the job must declare @DisallowConcurrentExecution so Quartz refuses to fire a second "
            + "instance while a run is still in progress");
  }

  /**
   * Simulates a second Quartz fire happening while the first {@code executeInThread} call is still
   * in the middle of its own company loop — the exact scenario {@code @DisallowConcurrentExecution}
   * cannot cover if the outer {@code execute()} returned early after an interrupt.
   */
  private static class ReentrantFireJob extends BgFixedAssetRealizationJob {
    boolean reentered;

    ReentrantFireJob(
        BgFixedAssetRealizationPreparationService preparationService,
        AccountingBatchService accountingBatchService) {
      super(null, preparationService, accountingBatchService);
    }

    @Override
    protected List<Long> selectedCompanyIds() {
      if (!reentered) {
        reentered = true;
        // A second fire arriving while this call is still "in progress" (guard already set).
        executeInThread(null);
      }
      return List.of(1L);
    }

    @Override
    protected void clearPersistenceContext() {
      // No-op — see FixedCompanyIdsJob.clearPersistenceContext for why.
    }
  }

  @Test
  void aReentrantFireWhileStillRunningIsSkippedByTheStaticGuard() {
    AccountingBatch batch = new AccountingBatch();
    FakePreparationService preparationService = new FakePreparationService();
    preparationService.results.put(1L, batch);
    FakeAccountingBatchService accountingBatchService = new FakeAccountingBatchService();

    ReentrantFireJob job = new ReentrantFireJob(preparationService, accountingBatchService);
    job.executeInThread(null);

    assertEquals(
        List.of(batch),
        accountingBatchService.runCalls,
        "the nested/reentrant call must dispatch nothing — only the outer call's one company "
            + "run must go through, proving the static guard covers the whole loop, not just "
            + "the initial dispatch");
  }

  /** Fakes the persisted selection without needing a real settings row/repository/JPA. */
  private static class FakeSettingsService extends BgFixedAssetRealizationSettingsService {
    List<Long> selected = List.of();

    FakeSettingsService() {
      super(null, null, null);
    }

    @Override
    public List<Long> findSelectedCompanyIds() {
      return selected;
    }
  }

  /**
   * Proves the job's default {@code selectedCompanyIds()} really delegates to the settings service
   * (not "every active company") — the other tests above all override that seam directly, which
   * proves the loop's behaviour but not this specific wiring.
   */
  @Test
  void companyScopeComesFromTheSettingsServiceNotFromAllActiveCompanies() {
    AccountingBatch batch = new AccountingBatch();
    FakePreparationService preparationService = new FakePreparationService();
    preparationService.results.put(7L, batch);
    FakeAccountingBatchService accountingBatchService = new FakeAccountingBatchService();

    FakeSettingsService settingsService = new FakeSettingsService();
    settingsService.selected = List.of(7L);

    BgFixedAssetRealizationJob job =
        new BgFixedAssetRealizationJob(
            settingsService, preparationService, accountingBatchService) {
          @Override
          protected void clearPersistenceContext() {
            // No-op — see FixedCompanyIdsJob.clearPersistenceContext for why.
          }
        };
    job.executeInThread(null);

    assertEquals(List.of(batch), accountingBatchService.runCalls);
  }

  @Test
  void emptySelectionMeansNothingIsDispatched() {
    FakePreparationService preparationService = new FakePreparationService();
    FakeAccountingBatchService accountingBatchService = new FakeAccountingBatchService();

    FakeSettingsService settingsService = new FakeSettingsService();
    settingsService.selected = List.of(); // nothing selected

    BgFixedAssetRealizationJob job =
        new BgFixedAssetRealizationJob(
            settingsService, preparationService, accountingBatchService) {
          @Override
          protected void clearPersistenceContext() {
            // No-op — see FixedCompanyIdsJob.clearPersistenceContext for why.
          }
        };
    job.executeInThread(null);

    assertTrue(
        accountingBatchService.runCalls.isEmpty(),
        "an empty company selection must process nothing — no fallback to all companies");
  }
}

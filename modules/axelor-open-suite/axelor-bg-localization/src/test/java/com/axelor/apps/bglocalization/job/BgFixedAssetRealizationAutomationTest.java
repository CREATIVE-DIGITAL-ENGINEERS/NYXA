package com.axelor.apps.bglocalization.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.AccountingBatch;
import com.axelor.apps.account.service.batch.AccountingBatchService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Batch;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgFixedAssetRealizationSettings;
import com.axelor.apps.bglocalization.service.batch.BgFixedAssetRealizationPreparationService;
import com.axelor.apps.bglocalization.service.batch.BgFixedAssetRealizationSettingsService;
import com.axelor.db.Model;
import com.axelor.meta.db.MetaSchedule;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.Job;

/**
 * End-to-end unit test for the Depreciation Automation feature as a whole: what an admin saves on
 * the settings screen must produce a schedule that really fires ONCE A MONTH, and a fire must
 * really depreciate the fixed assets of exactly the companies that were selected there.
 *
 * <p>Deliberately one test spanning both halves, because the bug this covers lives precisely in the
 * seam between them: the settings row and the job were each correct on their own while the derived
 * cron said "every day". The two halves are therefore wired to the SAME {@link
 * BgFixedAssetRealizationSettingsService} instance, exactly as production does — the schedule and
 * the run scope cannot silently drift apart here.
 *
 * <p>The real {@link org.quartz.CronExpression} evaluates the produced cron: nothing is asserted
 * about the schedule by string comparison alone, since a string can look monthly and still fire
 * daily. Everything that would need a database (repositories, Quartz's live scheduler, {@code
 * JPA.clear()}) is faked by overriding the established protected seams — no mocking framework, same
 * style as the rest of this module's tests.
 */
class BgFixedAssetRealizationAutomationTest {

  private static final long SOFIA_ID = 11L;
  private static final long PLOVDIV_ID = 22L;

  @BeforeEach
  void resetGuard() {
    BgFixedAssetRealizationJob.resetConcurrencyGuardForTests();
  }

  @Test
  void anActiveConfigurationDepreciatesTheSelectedCompaniesOnceEveryMonth() throws Exception {
    // --- what the admin saves on Accounting > Fixed assets > Depreciation Automation ---
    BgFixedAssetRealizationSettings settings = new BgFixedAssetRealizationSettings();
    settings.setActive(true);
    settings.setExecutionTime(LocalTime.of(3, 30));
    settings.setCompanySet(companies(SOFIA_ID, PLOVDIV_ID));

    FakeSettingsService settingsService = new FakeSettingsService();
    settingsService.existingSettings = settings;
    settingsService.syncSchedule(settings);

    // --- half 1: the saved settings produce a live, MONTHLY schedule for this job ---
    MetaSchedule schedule = settingsService.existingSchedule;
    assertEquals(BgFixedAssetRealizationJob.class.getName(), schedule.getJob());
    assertTrue(schedule.getActive(), "an active configuration must leave the schedule enabled");
    assertQuartzWouldRegister(schedule);

    List<LocalDateTime> fireTimes = nextFireTimes(schedule.getCron(), 3);
    assertEquals(
        List.of(
            LocalDateTime.of(2026, 2, 1, 3, 30),
            LocalDateTime.of(2026, 3, 1, 3, 30),
            LocalDateTime.of(2026, 4, 1, 3, 30)),
        fireTimes,
        "consecutive months at the configured time — a daily cron would fire on Jan 16, 17, 18");

    // --- half 2: one such fire depreciates exactly the companies selected above ---
    AccountingBatch sofiaBatch = new AccountingBatch();
    AccountingBatch plovdivBatch = new AccountingBatch();
    FakePreparationService preparationService = new FakePreparationService();
    preparationService.batches.put(SOFIA_ID, sofiaBatch);
    preparationService.batches.put(PLOVDIV_ID, plovdivBatch);
    FakeAccountingBatchService accountingBatchService = new FakeAccountingBatchService();

    // The job reads its scope through the SAME settings service that produced the schedule.
    new TestableJob(settingsService, preparationService, accountingBatchService)
        .executeInThread(null);

    assertEquals(
        List.of(SOFIA_ID, PLOVDIV_ID),
        preparationService.preparedCompanyIds,
        "every selected company must be prepared, and no other");
    assertEquals(
        List.of(sofiaBatch, plovdivBatch),
        accountingBatchService.runCalls,
        "and each prepared company's realization batch must actually be dispatched");
  }

  /**
   * Re-runs every guard {@code JobRunner.configure(MetaSchedule)} applies before it will register a
   * row with Quartz. Each one FAILS SILENTLY in production — an unresolvable class, a class that is
   * not a {@link org.quartz.Job}, or a cron Quartz rejects is only logged, and the schedule is
   * skipped — so the automation would simply never run with nothing visible to the admin. This
   * matters now that {@code ENABLE_QUARTZ=true} makes the scheduler actually read these rows.
   */
  private static void assertQuartzWouldRegister(MetaSchedule schedule) throws Exception {
    Class<?> jobClass = Class.forName(schedule.getJob());
    assertTrue(
        Job.class.isAssignableFrom(jobClass),
        "JobRunner skips a row whose class is not an org.quartz.Job");
    // Throws if Quartz itself will not accept the expression.
    CronScheduleBuilder.cronSchedule(schedule.getCron());
  }

  private static Set<Company> companies(Long... ids) {
    Set<Company> companySet = new LinkedHashSet<>();
    for (Long id : ids) {
      Company company = new Company();
      company.setId(id);
      companySet.add(company);
    }
    return companySet;
  }

  /** Asks Quartz itself when the produced cron would really fire, starting mid-January. */
  private static List<LocalDateTime> nextFireTimes(String cron, int count) throws Exception {
    CronExpression expression = new CronExpression(cron);
    ZoneId zone = ZoneId.systemDefault();
    List<LocalDateTime> fireTimes = new ArrayList<>();
    Date from = Date.from(LocalDateTime.of(2026, 1, 15, 12, 0).atZone(zone).toInstant());
    for (int i = 0; i < count; i++) {
      from = expression.getNextValidTimeAfter(from);
      fireTimes.add(LocalDateTime.ofInstant(from.toInstant(), zone));
    }
    return fireTimes;
  }

  /** The real service, with only its repository/Quartz/transaction seams faked. */
  private static class FakeSettingsService extends BgFixedAssetRealizationSettingsService {
    BgFixedAssetRealizationSettings existingSettings;
    MetaSchedule existingSchedule;

    FakeSettingsService() {
      super(null, null, null);
    }

    @Override
    protected BgFixedAssetRealizationSettings findExisting() {
      return existingSettings;
    }

    @Override
    protected MetaSchedule findSchedule(String name) {
      return SCHEDULE_NAME.equals(name) ? existingSchedule : null;
    }

    @Override
    protected void saveSchedule(MetaSchedule schedule) {
      existingSchedule = schedule;
    }

    @Override
    protected <T> T runInTransaction(Supplier<T> task) {
      return task.get();
    }

    @Override
    protected void refreshLive(MetaSchedule schedule) {
      // No live Quartz scheduler in a unit test; the persisted row is what this test asserts on.
    }

    @Override
    protected void unscheduleLegacyJob() {
      // Nothing to unschedule without a live Quartz scheduler.
    }
  }

  /** Records which companies were prepared, in order, without touching any repository. */
  private static class FakePreparationService extends BgFixedAssetRealizationPreparationService {
    final Map<Long, AccountingBatch> batches = new HashMap<>();
    final List<Long> preparedCompanyIds = new ArrayList<>();

    FakePreparationService() {
      super(null, null, null, null);
    }

    @Override
    public AccountingBatch prepare(Long companyId) {
      preparedCompanyIds.add(companyId);
      return batches.get(companyId);
    }
  }

  /** Stands in for the stock batch that does the actual depreciation realization. */
  private static class FakeAccountingBatchService extends AccountingBatchService {
    final List<AccountingBatch> runCalls = new ArrayList<>();

    @Override
    public Batch run(Model batchModel) throws AxelorException {
      runCalls.add((AccountingBatch) batchModel);
      return null;
    }
  }

  /** The real job; only {@code JPA.clear()} is neutralised (no persistence context here). */
  private static class TestableJob extends BgFixedAssetRealizationJob {
    TestableJob(
        BgFixedAssetRealizationSettingsService settingsService,
        BgFixedAssetRealizationPreparationService preparationService,
        AccountingBatchService accountingBatchService) {
      super(settingsService, preparationService, accountingBatchService);
    }

    @Override
    protected void clearPersistenceContext() {
      // See BgFixedAssetRealizationJobTest: the real JPA.clear() would throw "JPA context not
      // initialized" in a pure unit test. Production always calls the real one.
    }
  }
}

package com.axelor.apps.bglocalization.service.batch;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgFixedAssetRealizationSettings;
import com.axelor.apps.bglocalization.db.repo.BgFixedAssetRealizationSettingsRepository;
import com.axelor.apps.bglocalization.job.BgFixedAssetRealizationJob;
import com.axelor.db.JPA;
import com.axelor.meta.db.MetaSchedule;
import com.axelor.meta.db.repo.MetaScheduleRepository;
import com.axelor.quartz.JobRunner;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.lang.invoke.MethodHandles;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the single {@link BgFixedAssetRealizationSettings} row and keeps the derived {@value
 * #SCHEDULE_NAME} {@link MetaSchedule} row (monthly cron + active) in sync — on every settings save
 * ({@code BgFixedAssetRealizationSettingsController}) and on every server startup ({@code
 * BgLocalizationServerStartListener}). This entity is the ONLY source of truth for that schedule: a
 * generic Job Management edit to the MetaSchedule row is overwritten on the next sync, by design.
 *
 * <p>The run is MONTHLY — it fires on the 1st of each month at the configured time (see {@link
 * #cronFor}). It was daily until the schedule was corrected; {@link #adoptOrCreateSchedule} and
 * {@link #unscheduleLegacyJob} migrate an installation that still carries the old row and its old
 * live Quartz key.
 *
 * <p>{@link BgFixedAssetRealizationJob} reads its company scope exclusively through {@link
 * #findSelectedCompanyIds()} — never "all active companies" — so a scheduled run and a manual "Run
 * now" always see the exact same, currently-persisted selection.
 */
public class BgFixedAssetRealizationSettingsService {

  public static final String SINGLETON_KEY = "default";

  /** The derived {@link MetaSchedule} row's name — also the Quartz {@code JobKey}. */
  public static final String SCHEDULE_NAME = "bg-fixed-asset-realization-monthly";

  /**
   * What {@link #SCHEDULE_NAME} was called while the run was daily. An installation upgraded from
   * that version still has the row under this name, so {@link #adoptOrCreateSchedule} renames it
   * (it is the same schedule, not a second one) and {@link #unscheduleLegacyJob} drops the stale
   * Quartz key — see those methods for why doing only one of the two is not enough.
   */
  public static final String LEGACY_SCHEDULE_NAME = "bg-fixed-asset-realization-daily";

  /** Day of month the monthly run fires on — see {@link #cronFor}. */
  public static final int EXECUTION_DAY_OF_MONTH = 1;

  private static final LocalTime DEFAULT_EXECUTION_TIME = LocalTime.of(2, 0);
  // Kept comfortably under MetaSchedule.description's stock varchar(255) column — do not widen
  // that stock column, keep this string short instead.
  private static final String SCHEDULE_DESCRIPTION =
      "Realizes due fixed asset depreciation lines (selected companies only) into draft moves "
          + "monthly, on the 1st. Never posts/validates. Derived from Depreciation Automation "
          + "settings — do not edit cron/active directly; overwritten on next sync.";

  private final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  protected final BgFixedAssetRealizationSettingsRepository settingsRepo;
  protected final MetaScheduleRepository metaScheduleRepo;
  protected final JobRunner jobRunner;

  @Inject
  public BgFixedAssetRealizationSettingsService(
      BgFixedAssetRealizationSettingsRepository settingsRepo,
      MetaScheduleRepository metaScheduleRepo,
      JobRunner jobRunner) {
    this.settingsRepo = settingsRepo;
    this.metaScheduleRepo = metaScheduleRepo;
    this.jobRunner = jobRunner;
  }

  /**
   * Finds the one settings row, or creates it — disabled, no companies, as required on first boot —
   * if nobody has ever opened the screen or booted the server before.
   */
  @Transactional
  public BgFixedAssetRealizationSettings findOrCreate() {
    BgFixedAssetRealizationSettings settings = findExisting();
    if (settings != null) {
      return settings;
    }
    settings = new BgFixedAssetRealizationSettings();
    settings.setSingletonKey(SINGLETON_KEY);
    settings.setActive(false);
    settings.setExecutionTime(DEFAULT_EXECUTION_TIME);
    settings = saveSettings(settings);
    log.info("Created default BgFixedAssetRealizationSettings row (disabled, no companies).");
    return settings;
  }

  /**
   * The only source of execution scope, for both the scheduled job and Run now — the persisted
   * selection, never a caller-supplied list. An absent row or an empty selection both mean "process
   * nothing"; there is deliberately no fallback to every active company.
   */
  public List<Long> findSelectedCompanyIds() {
    BgFixedAssetRealizationSettings settings = findExisting();
    if (settings == null || settings.getCompanySet() == null) {
      return Collections.emptyList();
    }
    return settings.getCompanySet().stream().map(Company::getId).collect(Collectors.toList());
  }

  /**
   * Writes {@code settings}'s active flag + execution time onto the derived MetaSchedule row
   * (creating it on the very first sync), then — only once that write has actually committed —
   * best-effort asks {@link JobRunner} to pick the change up live.
   *
   * <p><b>Why {@link #persistSchedule} is not simply {@code @Transactional} and called via {@code
   * this}.</b> Both {@link BgLocalizationServerStartListener} (startup) and {@code
   * BgFixedAssetRealizationSettingsController} (form save) call {@code syncSchedule} on THIS SAME
   * object — a same-class self-invocation of whatever {@code persistSchedule} does next. Guice's
   * method-interception AOP (which backs {@code @Transactional} here, same as everywhere else in
   * this codebase) is only guaranteed to fire on calls made through the Guice-provided reference
   * from OUTSIDE the intercepted method's own class — a bare {@code this.persistSchedule(...)} /
   * {@code persistSchedule(...)} call from within this class is exactly the self-invocation case
   * Guice's own AOP documentation calls out as NOT reliably intercepted. Relying on
   * {@code @Transactional} there would silently run the write with no real commit boundary at all
   * in that case — worse than the original bug, not a fix for it. {@link #persistSchedule} instead
   * wraps its write in {@link JPA#withTransaction}, the same explicit, non-AOP transaction API
   * {@code TraceBackService} already uses elsewhere in this codebase (see {@code
   * BgFixedAssetRealizationJob}'s own javadoc) — it begins and commits a real transaction itself
   * (or joins one already open) regardless of how the method is invoked, so there is no
   * self-invocation ambiguity to depend on.
   *
   * <p><b>Why the write and the live refresh are still two separate steps.</b> {@link
   * JobRunner#update} mutates Quartz's live, in-memory scheduler immediately and cannot be rolled
   * back; calling it before {@link #persistSchedule} has returned — i.e. before its transaction has
   * committed — would risk leaving Quartz running a cron/active value that a failed commit then
   * rolled back in the database. This mirrors stock Axelor's own {@code Schedules.xml} ("save"
   * then, as a separate already-committed action, {@code JobController:update}), which never shares
   * a transaction between the two either.
   *
   * <p>A live-refresh failure — the scheduler disabled or stopped, normal outside a full server run
   * — is logged, never thrown: the saved row is authoritative regardless and is picked up correctly
   * the next time the scheduler itself starts.
   */
  public void syncSchedule(BgFixedAssetRealizationSettings settings) {
    MetaSchedule schedule = persistSchedule(settings);
    // Its own try/catch, ahead of the real refresh: dropping the stale key is best-effort
    // housekeeping for upgraded installations only, and must never be able to stop the schedule
    // that actually matters from being refreshed.
    try {
      unscheduleLegacyJob();
    } catch (Exception e) {
      log.warn(
          "Fixed asset realization: could not drop the legacy Quartz key '{}' ({}); it is gone "
              + "anyway on the next server restart, since the renamed row is the only one left "
              + "in the database.",
          LEGACY_SCHEDULE_NAME,
          e.getMessage());
    }
    try {
      refreshLive(schedule);
    } catch (Exception e) {
      log.warn(
          "Fixed asset realization: could not live-refresh the Quartz schedule ({}); the saved "
              + "cron/active will still apply the next time the scheduler starts.",
          e.getMessage());
    }
  }

  /**
   * Removes the pre-rename Quartz job/trigger from the LIVE scheduler. Renaming the database row
   * alone is not enough while the server is running: {@link JobRunner} identifies a Quartz {@code
   * JobKey} by {@link MetaSchedule#getName()}, so the job registered at startup under {@link
   * #LEGACY_SCHEDULE_NAME} stays registered — and keeps firing on its old daily cron — no matter
   * what the renamed row now says. Passing a transient, inactive {@link MetaSchedule} carrying just
   * the old name makes {@link JobRunner#update} delete that key and return without scheduling
   * anything; nothing is written to the database. When the key does not exist (a fresh install, or
   * a second sync in the same server run) it is a no-op.
   *
   * <p>Quartz here uses the default in-memory job store ({@code SchedulerProvider} configures no
   * {@code JobStore}), so there is no persisted trigger to clean up beyond this live one.
   */
  protected void unscheduleLegacyJob() throws SchedulerException {
    MetaSchedule legacyKey = new MetaSchedule();
    legacyKey.setName(LEGACY_SCHEDULE_NAME);
    legacyKey.setActive(false);
    jobRunner.update(legacyKey);
  }

  // Deliberately NOT @Transactional — see the javadoc on syncSchedule for why a same-class
  // self-invocation cannot safely depend on Guice's method-interception AOP here.
  // JPA.withTransaction (via the runInTransaction seam below) is the real commit boundary
  // instead: an exception thrown while building/saving the schedule propagates out of this
  // method with the row rolled back and un-committed, so syncSchedule's call to refreshLive is
  // never reached.
  protected MetaSchedule persistSchedule(BgFixedAssetRealizationSettings settings) {
    return runInTransaction(
        () -> {
          MetaSchedule schedule = adoptOrCreateSchedule();
          // Set on every sync, not just on create: the row is derived, so a hand-edited or
          // renamed-from-legacy row is corrected back to the job this service owns.
          schedule.setJob(BgFixedAssetRealizationJob.class.getName());
          schedule.setCron(cronFor(settings.getExecutionTime()));
          schedule.setActive(Boolean.TRUE.equals(settings.getActive()));
          schedule.setDescription(SCHEDULE_DESCRIPTION);
          saveSchedule(schedule);
          return schedule;
        });
  }

  /**
   * Returns the one row this service owns, migrating an upgraded installation on the way: a
   * pre-rename row is RENAMED IN PLACE rather than left behind and replaced by a new one, so the
   * schedule keeps its id, its history and any {@code MetaScheduleParam} rows hanging off it, and
   * no second row is ever left active in Job Management firing the old daily cron.
   *
   * <p>If both names somehow exist — someone created the monthly row by hand before an upgrade ran
   * — the correctly-named one wins and the legacy one is deactivated, never deleted: a row that
   * might carry an operator's own edits is parked for inspection, not silently destroyed.
   */
  protected MetaSchedule adoptOrCreateSchedule() {
    MetaSchedule schedule = findSchedule(SCHEDULE_NAME);
    MetaSchedule legacy = findSchedule(LEGACY_SCHEDULE_NAME);

    if (legacy != null && schedule == null) {
      legacy.setName(SCHEDULE_NAME);
      log.info(
          "Fixed asset realization: renamed the schedule row '{}' to '{}' (the run is monthly, "
              + "not daily); its cron and active flag are re-derived from the settings row.",
          LEGACY_SCHEDULE_NAME,
          SCHEDULE_NAME);
      return legacy;
    }

    if (legacy != null) {
      legacy.setActive(false);
      saveSchedule(legacy);
      log.warn(
          "Fixed asset realization: found BOTH '{}' and '{}'. '{}' is authoritative; the legacy "
              + "row has been deactivated (not deleted) and can be removed by hand once checked.",
          LEGACY_SCHEDULE_NAME,
          SCHEDULE_NAME,
          SCHEDULE_NAME);
    }

    if (schedule == null) {
      schedule = new MetaSchedule();
      schedule.setName(SCHEDULE_NAME);
    }
    return schedule;
  }

  /**
   * Real runtime behaviour is exactly {@link JPA#withTransaction}: begins and commits a genuine
   * transaction around {@code task} (or joins one already active, never double-committing). This
   * seam exists solely so a pure unit test (no Guice/Persist container, where {@code JPA.em()}
   * itself throws "JPA context not initialized") can override it as a direct passthrough, instead
   * of needing a real persistence context just to observe {@link #persistSchedule}'s business logic
   * and its interaction with {@link #syncSchedule}.
   */
  protected <T> T runInTransaction(Supplier<T> task) {
    return JPA.withTransaction(task);
  }

  protected BgFixedAssetRealizationSettings findExisting() {
    return settingsRepo
        .all()
        .filter("self.singletonKey = :key")
        .bind("key", SINGLETON_KEY)
        .fetchOne();
  }

  protected BgFixedAssetRealizationSettings saveSettings(BgFixedAssetRealizationSettings settings) {
    return settingsRepo.save(settings);
  }

  protected MetaSchedule findSchedule(String name) {
    return metaScheduleRepo.findByName(name);
  }

  protected void saveSchedule(MetaSchedule schedule) {
    metaScheduleRepo.save(schedule);
  }

  // A separate, overridable seam so a test can prove the try/catch above really swallows a
  // refresh failure, by making JUST the call throw rather than faking the catch itself.
  protected void refreshLive(MetaSchedule schedule) throws SchedulerException {
    jobRunner.update(schedule);
  }

  /**
   * Monthly, not daily: the cron fires on the {@link #EXECUTION_DAY_OF_MONTH}st of every month at
   * the configured time. Depreciation lines are dated at month end, so realizing them once, on the
   * 1st, picks up everything that fell due in the month that just closed — a daily fire re-ran the
   * same batch 30 times a month for no extra effect.
   *
   * <p>The day is deliberately fixed at 1 rather than configurable: any day above 28 would be
   * silently skipped in February by Quartz, and the 1st is the only choice that always exists and
   * always sits after the previous month's last depreciation date.
   */
  protected String cronFor(LocalTime executionTime) {
    LocalTime time = executionTime != null ? executionTime : DEFAULT_EXECUTION_TIME;
    return String.format(
        "0 %d %d %d * ?", time.getMinute(), time.getHour(), EXECUTION_DAY_OF_MONTH);
  }
}

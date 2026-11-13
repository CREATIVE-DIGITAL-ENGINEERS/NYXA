package com.axelor.apps.bglocalization.service.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgFixedAssetRealizationSettings;
import com.axelor.meta.db.MetaSchedule;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BgFixedAssetRealizationSettingsService}. No mocking framework is used
 * (matching this module's established style): the repositories/{@link com.axelor.quartz.JobRunner}
 * are faked by subclassing and overriding the protected seams that would otherwise touch real
 * JPA/Quartz — including {@link BgFixedAssetRealizationSettingsService#runInTransaction}, the seam
 * that replaced a same-class {@code @Transactional} self-invocation (see the class javadoc on
 * {@code syncSchedule}): overriding it here as a direct passthrough is exactly the same idiom this
 * codebase already uses for {@code JPA.clear()} elsewhere (e.g. {@code
 * BgFixedAssetRealizationJob#clearPersistenceContext}).
 */
class BgFixedAssetRealizationSettingsServiceTest {

  private static class FakeService extends BgFixedAssetRealizationSettingsService {
    BgFixedAssetRealizationSettings existingSettings;
    int settingsSaveCalls;

    /** The row stored under the current name — what findSchedule(SCHEDULE_NAME) returns. */
    MetaSchedule existingSchedule;

    /** A row left over under the pre-rename name, for the migration tests. */
    MetaSchedule legacySchedule;

    final List<MetaSchedule> savedSchedules = new ArrayList<>();
    int refreshCalls;
    RuntimeException refreshFailure;

    int legacyUnscheduleCalls;
    RuntimeException legacyUnscheduleFailure;

    /** Records call order across the transaction boundary and the live refresh. */
    final List<String> events = new ArrayList<>();

    /** When set, simulates the enclosing transaction failing to commit AFTER the task ran. */
    RuntimeException commitFailure;

    FakeService() {
      super(null, null, null);
    }

    @Override
    protected BgFixedAssetRealizationSettings findExisting() {
      return existingSettings;
    }

    @Override
    protected BgFixedAssetRealizationSettings saveSettings(
        BgFixedAssetRealizationSettings settings) {
      settingsSaveCalls++;
      existingSettings = settings;
      return settings;
    }

    // Models the real lookup-by-name: the renamed row stops being findable under the old name
    // exactly as it does in the database, so the migration cannot pass here by accident.
    @Override
    protected MetaSchedule findSchedule(String name) {
      if (SCHEDULE_NAME.equals(name)) {
        return existingSchedule;
      }
      if (LEGACY_SCHEDULE_NAME.equals(name)) {
        return legacySchedule != null && LEGACY_SCHEDULE_NAME.equals(legacySchedule.getName())
            ? legacySchedule
            : null;
      }
      return null;
    }

    @Override
    protected void saveSchedule(MetaSchedule schedule) {
      savedSchedules.add(schedule);
      if (SCHEDULE_NAME.equals(schedule.getName())) {
        existingSchedule = schedule;
      }
    }

    @Override
    protected void unscheduleLegacyJob() {
      legacyUnscheduleCalls++;
      if (legacyUnscheduleFailure != null) {
        throw legacyUnscheduleFailure;
      }
    }

    // Bypasses the real JPA.withTransaction (which would throw "JPA context not initialized"
    // here, no Guice/Persist container) while still modelling a transaction boundary: the task
    // runs, and only AFTER it completes can a simulated commit failure be raised — proving the
    // ordering guarantee does not depend on the task's own business logic throwing.
    @Override
    protected <T> T runInTransaction(Supplier<T> task) {
      events.add("persist:start");
      T result = task.get();
      events.add("persist:done");
      if (commitFailure != null) {
        throw commitFailure;
      }
      return result;
    }

    @Override
    protected void refreshLive(MetaSchedule schedule) {
      events.add("refresh");
      refreshCalls++;
      if (refreshFailure != null) {
        throw refreshFailure;
      }
    }
  }

  @Test
  void firstCallCreatesADisabledRowWithNoCompanies() {
    FakeService service = new FakeService();

    BgFixedAssetRealizationSettings settings = service.findOrCreate();

    assertEquals(1, service.settingsSaveCalls);
    assertFalse(Boolean.TRUE.equals(settings.getActive()));
    assertTrue(settings.getCompanySet() == null || settings.getCompanySet().isEmpty());
  }

  @Test
  void repeatedCallsNeverCreateASecondSettingsRow() {
    FakeService service = new FakeService();

    BgFixedAssetRealizationSettings first = service.findOrCreate();
    BgFixedAssetRealizationSettings second = service.findOrCreate();

    assertEquals(1, service.settingsSaveCalls);
    assertSame(first, second);
  }

  @Test
  void emptyOrMissingSelectionMeansNoCompanyIds() {
    FakeService service = new FakeService();
    service.existingSettings = null;
    assertTrue(service.findSelectedCompanyIds().isEmpty());

    service.existingSettings = new BgFixedAssetRealizationSettings();
    assertTrue(service.findSelectedCompanyIds().isEmpty());
  }

  @Test
  void executionTimeIsTranslatedIntoTheMatchingMonthlyCron() {
    FakeService service = new FakeService();
    BgFixedAssetRealizationSettings settings = new BgFixedAssetRealizationSettings();
    settings.setActive(true);
    settings.setExecutionTime(LocalTime.of(14, 30));

    service.syncSchedule(settings);

    // Day-of-month 1, not "*": the run is monthly, not daily.
    assertEquals("0 30 14 1 * ?", service.existingSchedule.getCron());
    assertTrue(service.existingSchedule.getActive());
    assertEquals(1, service.refreshCalls);
  }

  @Test
  void disabledSettingsProduceADisabledSchedule() {
    FakeService service = new FakeService();
    BgFixedAssetRealizationSettings settings = new BgFixedAssetRealizationSettings();
    settings.setActive(false);
    settings.setExecutionTime(LocalTime.of(2, 0));

    service.syncSchedule(settings);

    assertFalse(Boolean.TRUE.equals(service.existingSchedule.getActive()));
  }

  @Test
  void repeatedSyncNeverCreatesASecondScheduleRow() {
    FakeService service = new FakeService();
    BgFixedAssetRealizationSettings settings = new BgFixedAssetRealizationSettings();
    settings.setActive(false);
    settings.setExecutionTime(LocalTime.of(2, 0));

    service.syncSchedule(settings);
    service.syncSchedule(settings);

    assertEquals(2, service.savedSchedules.size());
    assertSame(
        service.savedSchedules.get(0),
        service.savedSchedules.get(1),
        "the second sync must update the SAME row found by findSchedule(), never create another");
  }

  /**
   * The core guarantee this whole redesign exists for: the live Quartz refresh must happen strictly
   * AFTER the persistence transaction has run to completion, never interleaved with it.
   */
  @Test
  void quartzRefreshHappensOnlyAfterTheTransactionCompletes() {
    FakeService service = new FakeService();
    BgFixedAssetRealizationSettings settings = new BgFixedAssetRealizationSettings();
    settings.setActive(true);
    settings.setExecutionTime(LocalTime.of(2, 0));

    service.syncSchedule(settings);

    assertEquals(List.of("persist:start", "persist:done", "refresh"), service.events);
  }

  /**
   * Business-logic-level failure (building/saving the schedule itself throws, inside the
   * transactional task): must never reach the live refresh.
   */
  @Test
  void aFailedScheduleSaveNeverReachesTheLiveRefresh() {
    FakeService service =
        new FakeService() {
          @Override
          protected void saveSchedule(MetaSchedule schedule) {
            throw new RuntimeException("simulated save failure");
          }
        };
    BgFixedAssetRealizationSettings settings = new BgFixedAssetRealizationSettings();
    settings.setActive(true);
    settings.setExecutionTime(LocalTime.of(2, 0));

    try {
      service.syncSchedule(settings);
      throw new AssertionError("expected the save failure to propagate");
    } catch (RuntimeException expected) {
      // expected: the save failed
    }

    assertEquals(0, service.refreshCalls, "a failed save must never trigger a live Quartz update");
  }

  /**
   * The scenario that matters most: the schedule is built/saved successfully (the task itself never
   * throws), but the surrounding transaction then fails to COMMIT. Even then, the live refresh must
   * never be reached — proving the guarantee does not merely depend on the task's own business
   * logic succeeding or failing.
   */
  @Test
  void aCommitFailureAfterTheTaskRanNeverReachesTheLiveRefresh() {
    FakeService service = new FakeService();
    service.commitFailure = new RuntimeException("simulated commit failure");
    BgFixedAssetRealizationSettings settings = new BgFixedAssetRealizationSettings();
    settings.setActive(true);
    settings.setExecutionTime(LocalTime.of(2, 0));

    try {
      service.syncSchedule(settings);
      throw new AssertionError("expected the commit failure to propagate");
    } catch (RuntimeException expected) {
      // expected: the commit failed
    }

    assertEquals(
        List.of("persist:start", "persist:done"),
        service.events,
        "the task completed, but a failed commit must still never reach the live refresh");
    assertEquals(0, service.refreshCalls);
  }

  /**
   * The persisted description must fit stock MetaSchedule.description's varchar(255) column;
   * otherwise the row's own INSERT/UPDATE fails at startup with "value too long for type character
   * varying(255)".
   */
  @Test
  void persistedScheduleDescriptionNeverExceedsTheStockVarchar255Column() {
    FakeService service = new FakeService();
    BgFixedAssetRealizationSettings settings = new BgFixedAssetRealizationSettings();
    settings.setActive(true);
    settings.setExecutionTime(LocalTime.of(2, 0));

    service.syncSchedule(settings);

    String description = service.existingSchedule.getDescription();
    assertTrue(description != null && description.length() <= 255, "length was " + description);
  }

  @Test
  void aLiveRefreshFailureIsLoggedNotThrown() {
    FakeService service = new FakeService();
    service.refreshFailure = new IllegalStateException("The scheduler service is disabled.");
    BgFixedAssetRealizationSettings settings = new BgFixedAssetRealizationSettings();
    settings.setActive(true);
    settings.setExecutionTime(LocalTime.of(2, 0));

    service.syncSchedule(settings); // must not throw

    assertEquals(1, service.savedSchedules.size(), "the row is still saved despite the failure");
  }

  /**
   * Upgrade path from the version where the run was daily. Renaming the row and dropping the stale
   * live Quartz key are BOTH required: the row alone would leave the job registered under the old
   * key still firing on its daily cron until the next restart, and the key alone would leave a
   * second row in Job Management that nothing syncs.
   */
  @Test
  void aLegacyDailyRowIsRenamedInPlaceAndItsStaleQuartzKeyIsDropped() {
    FakeService service = new FakeService();
    MetaSchedule legacy = new MetaSchedule();
    legacy.setName(BgFixedAssetRealizationSettingsService.LEGACY_SCHEDULE_NAME);
    legacy.setCron("0 0 2 * * ?");
    legacy.setActive(true);
    service.legacySchedule = legacy;

    BgFixedAssetRealizationSettings settings = new BgFixedAssetRealizationSettings();
    settings.setActive(true);
    settings.setExecutionTime(LocalTime.of(2, 0));

    service.syncSchedule(settings);

    assertEquals(1, service.savedSchedules.size(), "the legacy row is reused, never duplicated");
    assertSame(legacy, service.savedSchedules.get(0), "renamed IN PLACE, keeping its id/params");
    assertEquals(BgFixedAssetRealizationSettingsService.SCHEDULE_NAME, legacy.getName());
    assertEquals("0 0 2 1 * ?", legacy.getCron(), "and its daily cron is corrected to monthly");
    assertEquals(1, service.legacyUnscheduleCalls, "the stale live Quartz key must be dropped");
    assertEquals(1, service.refreshCalls);
  }

  @Test
  void whenBothNamesExistTheMonthlyRowWinsAndTheLegacyOneIsOnlyDeactivated() {
    FakeService service = new FakeService();
    MetaSchedule current = new MetaSchedule();
    current.setName(BgFixedAssetRealizationSettingsService.SCHEDULE_NAME);
    service.existingSchedule = current;
    MetaSchedule legacy = new MetaSchedule();
    legacy.setName(BgFixedAssetRealizationSettingsService.LEGACY_SCHEDULE_NAME);
    legacy.setActive(true);
    service.legacySchedule = legacy;

    BgFixedAssetRealizationSettings settings = new BgFixedAssetRealizationSettings();
    settings.setActive(true);
    settings.setExecutionTime(LocalTime.of(2, 0));

    service.syncSchedule(settings);

    assertFalse(Boolean.TRUE.equals(legacy.getActive()), "the duplicate must stop firing");
    assertEquals(
        BgFixedAssetRealizationSettingsService.LEGACY_SCHEDULE_NAME,
        legacy.getName(),
        "and must be left in place for inspection, never deleted or renamed over the live row");
    assertTrue(current.getActive());
    assertEquals("0 0 2 1 * ?", current.getCron());
  }

  /** Housekeeping for old installations must never cost a current one its schedule refresh. */
  @Test
  void aFailureDroppingTheLegacyKeyStillRefreshesTheRealSchedule() {
    FakeService service = new FakeService();
    service.legacyUnscheduleFailure =
        new IllegalStateException("The scheduler service is stopped.");
    BgFixedAssetRealizationSettings settings = new BgFixedAssetRealizationSettings();
    settings.setActive(true);
    settings.setExecutionTime(LocalTime.of(2, 0));

    service.syncSchedule(settings); // must not throw

    assertEquals(1, service.refreshCalls);
  }
}

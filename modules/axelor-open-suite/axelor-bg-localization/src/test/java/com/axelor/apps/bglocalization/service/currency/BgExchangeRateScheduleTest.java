package com.axelor.apps.bglocalization.service.currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.job.CurrencyConversionJob;
import com.axelor.meta.db.MetaSchedule;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.Job;

class BgExchangeRateScheduleTest {

  @Test
  void createsAnActiveWeekdayScheduleForTheCurrencyJobAndRegistersItLive() throws Exception {
    FakeSchedule service = new FakeSchedule();

    assertTrue(service.ensure());

    MetaSchedule schedule = service.saved;
    assertEquals(BgExchangeRateSchedule.SCHEDULE_NAME, schedule.getName());
    assertEquals(CurrencyConversionJob.class.getName(), schedule.getJob());
    assertTrue(schedule.getActive());
    assertSame(schedule, service.refreshed, "Quartz is already running at StartupEvent");

    // The guards JobRunner.configure applies silently: a real Job class and a cron Quartz accepts.
    assertTrue(Job.class.isAssignableFrom(Class.forName(schedule.getJob())));
    CronScheduleBuilder.cronSchedule(schedule.getCron());

    // From Friday 2026-01-16 noon: Friday 18:00, then Monday and Tuesday — never the weekend.
    assertEquals(
        List.of(
            LocalDateTime.of(2026, 1, 16, 18, 0),
            LocalDateTime.of(2026, 1, 19, 18, 0),
            LocalDateTime.of(2026, 1, 20, 18, 0)),
        nextFireTimes(schedule.getCron(), LocalDateTime.of(2026, 1, 16, 12, 0), 3));
  }

  @Test
  void leavesAnExistingRowAloneEvenWhenTheAdminSwitchedItOff() {
    FakeSchedule service = new FakeSchedule();
    MetaSchedule existing = new MetaSchedule();
    existing.setName(BgExchangeRateSchedule.SCHEDULE_NAME);
    existing.setActive(false);
    service.existing = existing;

    assertFalse(service.ensure());
    assertNull(service.saved);
    assertFalse(existing.getActive());
  }

  @Test
  void doesNotAddASecondScheduleWhenTheAdminAlreadyRunsTheJob() {
    FakeSchedule service = new FakeSchedule();
    service.activeScheduleForJob = true;

    assertFalse(service.ensure());
    assertNull(service.saved, "two active schedules would fetch the rates twice");
  }

  @Test
  void aFailedLiveRegistrationStillKeepsTheCreatedRow() {
    FakeSchedule service = new FakeSchedule();
    service.refreshFails = true;

    assertTrue(service.ensure(), "the row is saved and starts with the next restart");
    assertEquals(BgExchangeRateSchedule.SCHEDULE_NAME, service.saved.getName());
  }

  private static List<LocalDateTime> nextFireTimes(String cron, LocalDateTime start, int count)
      throws Exception {
    CronExpression expression = new CronExpression(cron);
    ZoneId zone = ZoneId.systemDefault();
    List<LocalDateTime> fireTimes = new ArrayList<>();
    Date from = Date.from(start.atZone(zone).toInstant());
    for (int i = 0; i < count; i++) {
      from = expression.getNextValidTimeAfter(from);
      fireTimes.add(LocalDateTime.ofInstant(from.toInstant(), zone));
    }
    return fireTimes;
  }

  /** The real service, with only its repository, transaction and live-Quartz seams faked. */
  private static class FakeSchedule extends BgExchangeRateSchedule {
    MetaSchedule existing;
    boolean activeScheduleForJob;
    boolean refreshFails;
    MetaSchedule saved;
    MetaSchedule refreshed;

    FakeSchedule() {
      super(null, null);
    }

    @Override
    protected MetaSchedule findSchedule(String name) {
      return existing != null && name.equals(existing.getName()) ? existing : null;
    }

    @Override
    protected boolean hasActiveScheduleForJob() {
      return activeScheduleForJob;
    }

    @Override
    protected void saveSchedule(MetaSchedule schedule) {
      saved = schedule;
    }

    @Override
    protected <T> T runInTransaction(Supplier<T> task) {
      return task.get();
    }

    @Override
    protected void refreshLive(MetaSchedule schedule) {
      if (refreshFails) {
        throw new IllegalStateException("The scheduler service has been stopped.");
      }
      refreshed = schedule;
    }
  }
}

package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.job.BgStockValuationJob;
import com.axelor.meta.db.MetaSchedule;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class BgStockValuationScheduleTest {

  @Test
  void aNewScheduleIsRegisteredWithTheRunningSchedulerNotOnlySaved() {
    FakeSchedule service = new FakeSchedule();

    assertTrue(service.ensure());

    assertEquals(BgStockValuationJob.class.getName(), service.saved.getJob());
    assertTrue(service.saved.getActive());
    assertSame(
        service.saved,
        service.refreshed,
        "Quartz is already running at StartupEvent; a saved-only row waits for the next restart");
  }

  @Test
  void anExistingRowIsNeitherSavedNorReRegistered() {
    FakeSchedule service = new FakeSchedule();
    MetaSchedule existing = new MetaSchedule();
    existing.setName(BgStockValuationSchedule.SCHEDULE_NAME);
    existing.setActive(false);
    service.existing = existing;

    assertFalse(service.ensure());
    assertNull(service.saved);
    assertNull(service.refreshed, "the admin's switched-off schedule must stay off");
  }

  @Test
  void aFailedLiveRegistrationStillKeepsTheCreatedRow() {
    FakeSchedule service = new FakeSchedule();
    service.refreshFails = true;

    assertTrue(service.ensure(), "the row is saved and starts with the next restart");
    assertEquals(BgStockValuationSchedule.SCHEDULE_NAME, service.saved.getName());
  }

  /** The real service, with only its repository, transaction and live-Quartz seams faked. */
  private static class FakeSchedule extends BgStockValuationSchedule {
    MetaSchedule existing;
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

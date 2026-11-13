package com.axelor.apps.bglocalization.service.stock;

import com.axelor.apps.bglocalization.job.BgStockValuationJob;
import com.axelor.db.JPA;
import com.axelor.meta.db.MetaSchedule;
import com.axelor.meta.db.repo.MetaScheduleRepository;
import com.axelor.quartz.JobRunner;
import com.google.inject.Inject;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Графикът на ИЗП: задачата {@link BgStockValuationJob} на всяка минута. Създава се при стартиране,
 * само ако липсва — изключеният от администратора график остава изключен.
 */
public class BgStockValuationSchedule {

  private static final Logger log = LoggerFactory.getLogger(BgStockValuationSchedule.class);

  public static final String SCHEDULE_NAME = "bg-stock-valuation-minutely";

  /** Всяка минута, в нулевата секунда (Quartz). */
  public static final String CRON = "0 * * * * ?";

  static final String DESCRIPTION =
      "ИЗП: оценка и осчетоводяване на изписването от склада след реализиране (BG).";

  protected final MetaScheduleRepository scheduleRepo;
  protected final JobRunner jobRunner;

  @Inject
  public BgStockValuationSchedule(MetaScheduleRepository scheduleRepo, JobRunner jobRunner) {
    this.scheduleRepo = scheduleRepo;
    this.jobRunner = jobRunner;
  }

  /** Създава графика, ако го няма. Връща {@code true}, когато е създаден. */
  public boolean ensure() {
    MetaSchedule created =
        runInTransaction(
            () -> {
              if (findSchedule(SCHEDULE_NAME) != null) {
                return null;
              }
              MetaSchedule schedule = new MetaSchedule();
              schedule.setName(SCHEDULE_NAME);
              schedule.setJob(BgStockValuationJob.class.getName());
              schedule.setCron(CRON);
              schedule.setActive(true);
              schedule.setDescription(DESCRIPTION);
              saveSchedule(schedule);
              return schedule;
            });
    if (created == null) {
      return false;
    }
    // Quartz се стартира ПРЕДИ StartupEvent и чете графиците само тогава — без това на нова
    // инсталация оценката тръгва чак след следващия рестарт, а дотогава реализациите чакат.
    try {
      refreshLive(created);
    } catch (Exception e) {
      log.warn(
          "Stock valuation: the {} schedule was created but not registered with the running "
              + "scheduler ({}); it will start with the next restart.",
          SCHEDULE_NAME,
          e.getMessage());
    }
    return true;
  }

  protected MetaSchedule findSchedule(String name) {
    return scheduleRepo.findByName(name);
  }

  protected void saveSchedule(MetaSchedule schedule) {
    scheduleRepo.save(schedule);
  }

  protected <T> T runInTransaction(Supplier<T> task) {
    return JPA.withTransaction(task);
  }

  /** Регистрира реда в работещия Quartz; без нищо, когато Quartz е изключен. */
  protected void refreshLive(MetaSchedule schedule) throws Exception {
    if (jobRunner.isEnabled()) {
      jobRunner.update(schedule);
    }
  }
}

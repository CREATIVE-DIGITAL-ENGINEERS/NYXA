package com.axelor.apps.bglocalization.service.currency;

import com.axelor.apps.base.job.CurrencyConversionJob;
import com.axelor.db.JPA;
import com.axelor.meta.db.MetaSchedule;
import com.axelor.meta.db.repo.MetaScheduleRepository;
import com.axelor.quartz.JobRunner;
import com.google.inject.Inject;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Дневният график на валутните курсове: задачата на AOS {@link CurrencyConversionJob} в работните
 * дни. Тя пита услугата от App Base → „Currency Ws URL“ (ЕЦБ) и обновява само двойките валути,
 * които вече имат ред в списъка с курсове на App Base.
 *
 * <p><b>Защо 18:00 в работните дни.</b> ЕЦБ публикува референтните курсове веднъж на работен ден
 * около 16:00 ч. централноевропейско време. 18:00 по часовника на сървъра е след това и при UTC, и
 * при българско време. Почивен ден не вреди: услугата се връща до 7 дни назад до последния
 * публикуван курс и добавя ред само при променен курс. Затова не е на всеки 5 минути като
 * неактивния ред, който AOS зарежда при първа инсталация.
 *
 * <p>Създава се при стартиране, само ако липсва — изключеният от администратора график остава
 * изключен. Не се създава и ако администраторът вече е пуснал същата задача в свой активен график —
 * иначе курсовете ще се теглят два пъти.
 */
public class BgExchangeRateSchedule {

  private static final Logger log = LoggerFactory.getLogger(BgExchangeRateSchedule.class);

  public static final String SCHEDULE_NAME = "bg-exchange-rates-daily";

  /** 18:00 от понеделник до петък (Quartz). */
  public static final String CRON = "0 0 18 ? * MON-FRI";

  static final String DESCRIPTION =
      "Валутни курсове: дневните референтни курсове на ЕЦБ за двойките в App Base (BG).";

  protected final MetaScheduleRepository scheduleRepo;
  protected final JobRunner jobRunner;

  @Inject
  public BgExchangeRateSchedule(MetaScheduleRepository scheduleRepo, JobRunner jobRunner) {
    this.scheduleRepo = scheduleRepo;
    this.jobRunner = jobRunner;
  }

  /** Създава графика, ако го няма. Връща {@code true}, когато е създаден. */
  public boolean ensure() {
    MetaSchedule created =
        runInTransaction(
            () -> {
              if (findSchedule(SCHEDULE_NAME) != null || hasActiveScheduleForJob()) {
                return null;
              }
              MetaSchedule schedule = new MetaSchedule();
              schedule.setName(SCHEDULE_NAME);
              schedule.setJob(CurrencyConversionJob.class.getName());
              schedule.setCron(CRON);
              schedule.setActive(true);
              schedule.setDescription(DESCRIPTION);
              saveSchedule(schedule);
              return schedule;
            });
    if (created == null) {
      return false;
    }
    // Quartz се стартира ПРЕДИ StartupEvent и чете графиците само тогава — без това новият ред
    // ще тръгне чак след следващия рестарт.
    try {
      refreshLive(created);
    } catch (Exception e) {
      log.warn(
          "Exchange rates: the {} schedule was created but not registered with the running "
              + "scheduler ({}); it will start with the next restart.",
          SCHEDULE_NAME,
          e.getMessage());
    }
    return true;
  }

  protected MetaSchedule findSchedule(String name) {
    return scheduleRepo.findByName(name);
  }

  protected boolean hasActiveScheduleForJob() {
    return scheduleRepo
            .all()
            .filter("self.job = :job AND self.active = TRUE")
            .bind("job", CurrencyConversionJob.class.getName())
            .count()
        > 0;
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

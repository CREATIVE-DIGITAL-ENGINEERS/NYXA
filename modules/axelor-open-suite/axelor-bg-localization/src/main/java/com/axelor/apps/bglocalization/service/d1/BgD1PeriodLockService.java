package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * ДЕКЛАРИРАН ЛИ Е ПЕРИОДЪТ КЪМ НАП — единствената заявка зад ключ (Б).
 *
 * <p>Правилото (кои статуси, коя услуга, кой вид документ) живее в {@link BgD1PeriodLockRules} и се
 * проверява без база. Тук е само изпълнението му.
 *
 * <p><b>⚠️ ПЕРИОД БЕЗ {@code id} ВРЪЩА FALSE, БЕЗ ДА СЕ ПИПА БАЗАТА.</b> Такъв период е сглобен в
 * паметта — тоест или е нов, още незаписан, или е тест. За него подаване не може да съществува:
 * {@code NapSubmission.period} е връзка към РЕД. Изпратено в заявка, то би върнало същото „не“, но
 * с обръщение към Hibernate от всеки от стотиците тестове, които строят фиш наум.
 */
@Singleton
public class BgD1PeriodLockService {

  protected final NapSubmissionRepository submissionRepo;

  @Inject
  public BgD1PeriodLockService(NapSubmissionRepository submissionRepo) {
    this.submissionRepo = submissionRepo;
  }

  /**
   * Има ли за периода подадена и НЕОТХВЪРЛЕНА Д1/Д6.
   *
   * <p>⚠️ ПРИСЪСТВИЕ, не брой. Едно подаване стига, за да е деклариран месецът, а при корекции те
   * са повече от едно — броенето би искало да знае кое от тях е „последното“, въпрос, на който
   * регистърът отговаря с верига {@code previousSubmission}, а пазачът няма нужда от нея.
   */
  public boolean isDeclaredToNap(Period period) {
    if (period == null || period.getId() == null) {
      return false;
    }
    return submissionRepo
            .all()
            .filter(BgD1PeriodLockRules.FILTER)
            .bind("period", period)
            .bind("service", BgD1PeriodLockRules.SERVICE_CODE)
            .bind("documentType", BgD1PeriodLockRules.DOCUMENT_TYPE)
            .bind("statuses", BgD1PeriodLockRules.LOCKING_STATUSES)
            .count()
        > 0;
  }
}

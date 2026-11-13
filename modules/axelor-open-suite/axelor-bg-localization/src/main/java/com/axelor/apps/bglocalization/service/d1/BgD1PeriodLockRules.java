package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.services.declarations.common.ServiceDocumentType;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * КОЕ ПОДАВАНЕ ЗАКЛЮЧВА ПЕРИОДА — ключ (Б) на заключването, изписан като ЧИСТО правило.
 *
 * <p>Правилото и заявката са разделени нарочно: тук стои КОЕ се брои за „подадено и неотхвърлено“ —
 * свидетел без база, без Guice и без контейнер, — а {@link BgD1PeriodLockService} само го изпълнява
 * срещу базата. Слети в едно, единственият начин да се провери осемте статуса щеше да е инсталация.
 *
 * <p><b>⚠️ ЗАКЛЮЧВА ПОДАВАНЕТО, НЕ ГЕНЕРИРАНЕТО.</b> {@code BgD1SubmissionService#attach} спира на
 * {@code STATUS_DRAFT}: байтовете са произведени, но подписът и подаването са човешки стъпки по
 * устройство. Тоест ГЕНЕРИРАНА декларация не е изявление пред НАП и не заключва нищо — инак всеки
 * пробен натиск на „Закачи файловете“ щеше да замразява месеца.
 *
 * <p><b>⚠️ И ОТХВЪРЛЕНАТА НЕ ЗАКЛЮЧВА, ЗАЩОТО ИМЕННО ТЯ ИСКА ПОПРАВКА.</b> {@code STATUS_REJECTED}
 * значи, че НАП не е приела файла: тогава верният път е да се поправи фишът и да се подаде наново,
 * а не корекция на нещо, което НАП изобщо не пази. Заключен и той, пазачът щеше да блокира
 * единствената поправка, която в този случай има смисъл.
 *
 * <p><b>⚠️ НЕИЗВЕСТНИЯТ ИЗХОД ЗАКЛЮЧВА, И ТОВА Е ИЗБОР.</b> {@code
 * STATUS_SUBMISSION_OUTCOME_UNKNOWN} значи „връзката падна и НЕ се знае дали НАП го е приела“ —
 * самият транспорт отказва повторен POST по този статус ({@code
 * DeclarationsSubmissionService#submit}). Двете четения не са равностойни: пуснат като „неподаден“,
 * месецът се редактира върху декларация, която може вече да е в НАП; заключен, операторът минава
 * през корекция, която е вярна и в двата случая. Отключването е човешка стъпка и си има врата —
 * {@code manuallyResolveOutcomeUnknown} записва действителния изход.
 *
 * <p><b>⚠️ „ИЗИСКВА КОРЕКЦИЯ“ СЪЩО ЗАКЛЮЧВА.</b> {@code STATUS_CORRECTION_REQUIRED} не е отказ:
 * файлът е в НАП и точно затова поправката му е КОРИГИРАЩА декларация. Наредено при отхвърлените,
 * пазачът щеше да пусне редовна редакция на месец, който НАП вече пази.
 *
 * <p><b>⚠️ И ЕДНО, КОЕТО НЕ Е ПРОПУСК: {@code STATUS_SUBMITTING} не заключва.</b> Той се слага и се
 * презаписва вътре в ЕДНА транзакция около самия POST, тоест в базата не остава — а прочетен като
 * „подадено“, би заключил месец по опит, който е приключил с техническа грешка.
 */
public final class BgD1PeriodLockRules {

  /**
   * Статусите, при които подаването е РЕАЛНО отишло към НАП и не е отказано.
   *
   * <p>⚠️ Списък на ДОПУСКАНЕТО, не на отричането: нов статус в {@code
   * nap.submission.status.select} по подразбиране НЕ заключва. Обратното изброяване („всичко без
   * draft и rejected“) щеше да вдига ключа сам, при първия непознат статус.
   */
  public static final List<String> LOCKING_STATUSES =
      Collections.unmodifiableList(
          Arrays.asList(
              NapSubmissionRepository.STATUS_SUBMITTED,
              NapSubmissionRepository.STATUS_ACCEPTED,
              NapSubmissionRepository.STATUS_CORRECTION_REQUIRED,
              NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN));

  /**
   * Услугата на подаването — „декларации“, не SAF-T.
   *
   * <p>⚠️ Питането е ИЗРИЧНО, а не „всяко подаване за този период“. Регистърът е обща за всички
   * услуги към НАП; ДДС-справка, SAF-T или годишна справка по чл. 73 за същия месец нямат нищо общо
   * с фишовете и не бива да ги замразяват. Днес нито една от тях не пълни {@code period} — тоест
   * широкият прочит щеше да е зелен и да се счупи мълчаливо при първата, която го напълни.
   */
  public static final String SERVICE_CODE = NapSubmissionRepository.SERVICE_DECLARATIONS;

  /** Видът документ — Д1 и Д6 пътуват в ЕДНО подаване ({@code DEC_1_6}). */
  public static final String DOCUMENT_TYPE = ServiceDocumentType.DEC_1_6.name();

  /**
   * ЕДНА ЗАЯВКА, както коментарът на {@code isPeriodLocked} подсказва.
   *
   * <p>⚠️ Ключът е ПЕРИОДЪТ, а не декларацията. Обратният прочит през {@code relatedModel}/{@code
   * relatedRecordId} до {@code BgD1Declaration} и оттам до {@code bgDataPeriod} дава същия отговор
   * с две заявки и с низово сравнение на име на клас; {@code NapSubmission.period} е ЕДНО И СЪЩО
   * поле, попълнено от {@code BgD1SubmissionService#attach} точно с {@code d1.getBgDataPeriod()}.
   *
   * <p>⚠️ Фирма НЕ се пита, и това не е пропуск: {@code base.Period} принадлежи на {@code Year}, а
   * той — на дружество. Тоест периодът вече Е на едно дружество и втори филтър би бил същото
   * условие, написано два пъти.
   *
   * <p>⚠️ И ФАЙЛОВЕТЕ НЕ СЕ ПИТАТ. Заключва ПОДАВАНЕТО, а какво носи то (само Д1, или Д1 и Д6) е
   * въпрос на съдържание — подаване само с Д1 декларира същия месец за същите лица.
   */
  public static final String SCOPE_FILTER =
      "self.period = :period"
          + " AND self.serviceCodeSelect = :service"
          + " AND self.serviceDocumentTypeSelect = :documentType";

  /**
   * ⚠️ ОБХВАТЪТ Е ОТДЕЛЕН ОТ СТАТУСИТЕ, защото втори читател пита СЪЩИТЕ три неща с ДРУГ въпрос за
   * статуса: {@code BgD1GenerationService#latestSubmissionStatus} иска ПОСЛЕДНОТО подаване, не
   * онези, които заключват. Преписан там, обхватът е свободен да се размине — и разминаването е
   * безшумно: заявка за друга услуга връща редове, които изглеждат правдоподобно.
   */
  public static final String FILTER =
      SCOPE_FILTER + " AND self.submissionStatusSelect IN :statuses";

  private BgD1PeriodLockRules() {}

  /** Заключва ли този статус на подаване. {@code null} и непознат — не. */
  public static boolean locks(String submissionStatus) {
    return submissionStatus != null && LOCKING_STATUSES.contains(submissionStatus);
  }
}

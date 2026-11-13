package com.axelor.apps.bglocalization.service.zddfl;

import com.axelor.apps.napsubmissions.common.artifact.NapArtifactService;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionFileRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.auth.AuthUtils;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * ЗАКАЧАНЕТО НА ЕДИН ЗДДФЛ-ОБРАЗЕЦ КЪМ {@code nyxa-nap-submissions} — общо за чл. 55 и чл. 73.
 *
 * <p><b>⚠️ ИМЕТО Е ИСТОРИЧЕСКО: услугата обслужва И декларацията по чл. 55 (образец 4001).</b>
 * Двата образеца правят едно и също нещо с транспорта — качен файл става НЕПРОМЕНИМ артефакт и ред
 * на подаване — и второ копие на тези двайсет реда би се разминало при първата поправка.
 *
 * <p><b>⚠️ ЕДНО КЪМ ЕДНО С {@code BgD1SubmissionService#persist}, С ЕДНА ИЗРИЧНА РАЗЛИКА:</b> там
 * байтовете се качват В МОМЕНТА на закачането; тук {@code MetaFile} вече съществува, защото се
 * ражда при ГЕНЕРИРАНЕ. Причината е проверимостта: файлът трябва да може да бъде СВАЛЕН и погледнат
 * преди да бъде закачен, а не да се появи за пръв път вътре в подаването.
 *
 * <p><b>⚠️ {@code fileDocumentTypeSelect} ОСТАВА ПРАЗЕН — ОТКАЗ, А НЕ ПРОПУСК.</b> {@code
 * FileDocumentType} е фиксиран от договора на Declarations API и не познава нито 4001, нито 5-2001;
 * {@code DeclarationsDocumentTypeMatrix} отхвърля непотвърдена комбинация. Сложена която и да е от
 * наличните стойности, файлът щеше да тръгне към услугата на ДРУГА декларация. По същия довод
 * {@code serviceDocumentTypeSelect} остава празен и днес.
 *
 * <p><b>⚠️ КАНАЛЪТ Е {@code manual_portal} И НИЩО НЕ СЕ ИЗПРАЩА.</b> Той по устройство не извършва
 * транспорт, а записва какво е направил човек на портала — точно затова и приемането/отхвърлянето
 * по-долу са РЪЧНИ преходи с входящ номер и дата, а не отговор от услуга.
 */
@Singleton
public class BgDeclarationSubmissionService {

  protected final NapArtifactService artifactService;
  protected final NapSubmissionRepository submissionRepo;
  protected final NapSubmissionFileRepository submissionFileRepo;

  @Inject
  public BgDeclarationSubmissionService(
      NapArtifactService artifactService,
      NapSubmissionRepository submissionRepo,
      NapSubmissionFileRepository submissionFileRepo) {
    this.artifactService = artifactService;
    this.submissionRepo = submissionRepo;
    this.submissionFileRepo = submissionFileRepo;
  }

  /**
   * ОТБЕЛЯЗВА, ЧЕ ОБРАЗЕЦЪТ Е ПОДАДЕН през портала, и връща записа в одитната следа.
   *
   * <p><b>НЕ подава</b> — каналът е {@code manual_portal} и по устройство не извършва транспорт.
   *
   * <p>⚠️ Файлът е НЕЗАДЪЛЖИТЕЛЕН: докато форматът на записа не дойде със заповед на НАП, човек
   * подава през портала без наш файл. Подаването пак трябва да има къде да живее — инак за цял
   * период не остава следа. Входящият номер обаче е ЗАДЪЛЖИТЕЛЕН: той е разликата между „подадено“
   * и празно твърдение.
   *
   * @throws IllegalStateException когато липсва входящ номер или дата
   */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission attach(BgDeclarationSubmissionSpec spec) throws IOException {
    BgDeclarationStatus.requireIncomingNumber(spec.getIncomingNumber(), spec.getIncomingDate());

    NapSubmission submission = new NapSubmission();
    submission.setServiceCodeSelect(
        spec.getServiceCode() == null
            ? NapSubmissionRepository.SERVICE_DECLARATIONS
            : spec.getServiceCode());
    submission.setCompany(spec.getCompany());
    submission.setReferenceDate(spec.getReferenceDate());
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_DRAFT);
    submission.setChannelSelect(NapSubmissionRepository.CHANNEL_MANUAL_PORTAL);
    submission.setEnvironmentSelect(NapSubmissionRepository.ENVIRONMENT_TEST);
    submission.setName(spec.getName());
    submission.setTaxpayerPin(spec.getTaxpayerPin());
    submission.setRelatedModel(spec.getRelatedModel());
    submission.setRelatedRecordId(spec.getRelatedRecordId());
    submission.setPreviousSubmission(spec.getPreviousSubmission());

    NapSubmission saved = submissionRepo.save(submission);

    // ⚠️ РЕД САМО ПРИ ИСТИНСКИ ФАЙЛ. Създаден „за симетрия“ при липсващ MetaFile, той щеше да носи
    // нула байта, нула записи и SHA-256 на нищото — тоест да твърди подаден файл, какъвто няма.
    if (spec.getMetaFile() != null) {
      submissionFileRepo.save(persist(saved, spec));
    }

    recordManualSubmission(saved, spec.getIncomingNumber(), spec.getIncomingDate());
    return saved;
  }

  /**
   * ЕДИН ФАЙЛ → НЕПРОМЕНИМ АРТЕФАКТ И РЕД НА ПОДАВАНЕТО.
   *
   * <p>⚠️ {@code fileSize} НЕ е второ независимо число: {@code
   * DeclarationsSubmissionMapper#buildFile} сверява дължината, записана на АРТЕФАКТА, срещу
   * действително прочетените байтове. Истината е в артефакта и в неговия SHA-256; метаданните на
   * реда са удобство за екрана.
   */
  protected NapSubmissionFile persist(NapSubmission submission, BgDeclarationSubmissionSpec spec)
      throws IOException {

    // ⚠️ ПРЕИЗПОЛЗВА, КОГАТО ГЕНЕРИРАНЕТО ВЕЧЕ Е СЪЗДАЛО АРТЕФАКТ. Втори артефакт за същите байтове
    // значи два отпечатъка и два кандидата за „подаденото“. Създава се нов само когато файлът е
    // дошъл отвън — тогава артефакт просто няма.
    NapArtifact payload =
        spec.getArtifact() != null
            ? spec.getArtifact()
            : artifactService.createForCompany(
                spec.getMetaFile(), NapArtifactRepository.TYPE_PAYLOAD, submission.getCompany());

    NapSubmissionFile file = new NapSubmissionFile();
    file.setSubmission(submission);
    file.setPayloadArtifact(payload);
    file.setSequence(0);
    file.setSubmittedFileName(spec.getFileName());
    // ⚠️ fileDocumentTypeSelect НЕ се задава — виж класовия javadoc.
    file.setFileTypeSelect(fileTypeOf(spec.getFileName()));
    file.setFileSize(payload.getByteLength());
    file.setNumRecords(spec.getRecordCount());
    file.setFileStatusSelect(NapSubmissionFileRepository.FILE_STATUS_PENDING);
    return file;
  }

  /**
   * Записва, че човек е подал през портала и е получил входящ номер.
   *
   * <p>⚠️ {@code submittedOn} идва от ДАТАТА НА ПОРТАЛА, а не от {@code now()}: човек въвежда
   * номера дни след като го е получил, и записаният момент трябва да е моментът на подаването.
   * {@code submittedBy} обаче е ТОЙ — кой е въвел, е отделен въпрос от кога е подадено.
   */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission recordManualSubmission(
      NapSubmission submission, String incomingNumber, LocalDate incomingDate) {
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SUBMITTED);
    submission.setNapIncomingNumber(incomingNumber);
    submission.setSubmittedOn(
        incomingDate == null ? LocalDateTime.now() : incomingDate.atStartOfDay());
    submission.setSubmittedBy(AuthUtils.getUser());
    return submissionRepo.save(submission);
  }

  /**
   * РЪЧЕН ПРЕХОД: НАП е ПРИЕЛА подаването.
   *
   * <p>⚠️ Иска входящ номер/протокол и дата. Без тях „прието“ е твърдение без свидетел, а точно то
   * е в основата на отговора „подадено ли е навреме“.
   */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission markAccepted(
      NapSubmission submission, String incomingNumber, LocalDate responseDate, String note) {
    return applyManualOutcome(
        submission, NapSubmissionRepository.STATUS_ACCEPTED, incomingNumber, responseDate, note);
  }

  /** РЪЧЕН ПРЕХОД: НАП е ОТХВЪРЛИЛА подаването. */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission markRejected(
      NapSubmission submission, String incomingNumber, LocalDate responseDate, String note) {
    return applyManualOutcome(
        submission, NapSubmissionRepository.STATUS_REJECTED, incomingNumber, responseDate, note);
  }

  /**
   * Общото тяло на двата ръчни изхода.
   *
   * <p>⚠️ {@code processedOn} се пише от ДАТАТА НА ОТГОВОРА, а не от „сега“: човек въвежда изхода
   * дни след като го е получил, и записаният момент трябва да е този на НАП, не на въвеждането.
   */
  protected NapSubmission applyManualOutcome(
      NapSubmission submission,
      String status,
      String incomingNumber,
      LocalDate responseDate,
      String note) {

    if (notBlank(incomingNumber)) {
      submission.setNapIncomingNumber(incomingNumber);
    }
    submission.setSubmissionStatusSelect(status);
    submission.setExternalStatus(status);
    if (responseDate != null) {
      submission.setProcessedOn(responseDate.atStartOfDay());
    }
    if (notBlank(note)) {
      submission.setResponseMeta(note);
    }
    return submissionRepo.save(submission);
  }

  /**
   * Типът на файла — ИЗВЕДЕН от разширението, а не избран.
   *
   * <p>⚠️ Разширението идва от оформлението, тоест от заповедта на НАП. Докато нея я няма, няма и
   * име на файл — а щом има, типът е това, което името вече казва. Неразпознато разширение дава
   * {@code null}: селекцията {@code nap.file.type.select} познава само txt, csv и xml, и измислена
   * стойност там е невалиден запис, а не по-пълен.
   */
  static String fileTypeOf(String fileName) {
    if (fileName == null) {
      return null;
    }
    int dot = fileName.lastIndexOf('.');
    if (dot < 0 || dot == fileName.length() - 1) {
      return null;
    }
    String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    switch (extension) {
      case "txt":
      case "csv":
      case "xml":
        return extension;
      default:
        return null;
    }
  }

  private static boolean notBlank(String value) {
    return value != null && !value.trim().isEmpty();
  }
}

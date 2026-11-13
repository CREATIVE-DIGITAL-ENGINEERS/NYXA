package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.bglocalization.db.BgD1Declaration;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.napsubmissions.common.artifact.NapArtifactService;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionFileRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.services.declarations.common.PinType;
import com.axelor.apps.napsubmissions.services.declarations.common.ServiceDocumentType;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * ЗАКАЧАНЕТО НА Д1 И Д6 КЪМ ТРАНСПОРТА — байтовете стават <b>подаване-чернова</b>.
 *
 * <p><b>⚠️ ТОВА НЕ ПОДАВА. И НЕ Е „още не е дописано“.</b> Услугата спира на {@code STATUS_DRAFT} и
 * не докосва {@code DeclarationsSubmissionService#submit}. Причината е в самия транспорт:
 * подаването иска <b>ДЕТАЧЕН PKCS#7 подпис</b> на точните байтове и <b>публичния сертификат</b> на
 * подаващия ({@code DeclarationsSubmissionMapper} го изисква за ВСЕКИ файл), а частният ключ на КЕП
 * никога не влиза в ERP-а. Тоест подписването и подаването СА човешки стъпки по устройство, не по
 * недоглеждане — и след тях идва тестовата среда на НАП, която също е човешка.
 *
 * <p><b>⚠️ КАКВО ТОГАВА ПРАВИ.</b> Точно онова, което машината може да направи вярно: произвежда
 * байтовете (Етап 6 на WS-7), качва ги като {@link MetaFile}, превръща ги в НЕПРОМЕНИМИ {@link
 * NapArtifact} (SHA-256 и дължина се смятат ВЕДНЪЖ, при създаването) и ги закача като два {@link
 * NapSubmissionFile} с верните метаданни на договора — {@code fileDocumentType} и {@code
 * numRecords}. Оттам транспортът е самодостатъчен.
 *
 * <p><b>⚠️ ДВА ФАЙЛА, ЕДНО ПОДАВАНЕ, И РЕДЪТ ИМ Е ЧАСТ ОТ ДОГОВОРА.</b> {@code
 * DeclarationsSubmissionMapper} подрежда файловете по {@code sequence} — тоест Д1 (последователност
 * 0) излиза преди Д6 (1). Едно подаване с два файла е точно каквото {@code DEC_1_6} значи; две
 * отделни подавания биха били две различни входящи номера за едно задължение.
 *
 * <p><b>⚠️ NUMRECORDS СА РАЗЛИЧНИ ЧИСЛА ЗА ДВАТА ФАЙЛА, И ТОВА НЕ Е ПРОПУСК.</b> Д1 носи по един
 * запис на ЛИЦЕ; Д6 е агрегат на ДРУЖЕСТВОТО и файлът ѝ носи точно ЕДИН запис. Пренесено едно към
 * едно, {@code numRecords} на Д6 щеше да е броят на лицата — число, което изглежда напълно разумно
 * и не отговаря на нито един ред във файла.
 *
 * <p><b>⚠️ И ЗАЩО ТУК, А НЕ В {@code nyxa-nap-submissions}.</b> Транспортът зависи само от {@code
 * axelor-base} и нищо в него не знае за заплати. Обратната посока би вкарала целия данъчен модул в
 * зависимостите на всяка бъдеща услуга към НАП.
 */
@Singleton
public class BgD1SubmissionService {

  /** Името на файла на Д1 — от заповедта по чл. 8 ал. 5. */
  public static final String D1_FILE_NAME = "EMPL2021.TXT";

  /**
   * Името на файла на Д6.
   *
   * <p>⚠️ НЕ Е ОТ ЗАПОВЕДТА, и това е обявена граница. Справката по {@code file_3906} дава
   * СТРУКТУРАТА на записа, но не и задължително име на файла — за Д1 то е изрично („EMPL2021.TXT“),
   * за Д6 не е набавено. Избрано е име, което човек разпознава; ако НАП изисква друго, {@code
   * NapSubmissionFile.submittedFileName} се редактира на екрана, без да се пипа код.
   */
  public static final String D6_FILE_NAME = "DEC6.TXT";

  /** Типът на файла по {@code nap.file.type.select} — форматът е текстов, разделен със запетая. */
  public static final String FILE_TYPE_TXT = "txt";

  protected final BgD1FileService fileService;
  protected final NapArtifactService artifactService;
  protected final NapSubmissionRepository submissionRepo;
  protected final NapSubmissionFileRepository submissionFileRepo;
  protected final MetaFiles metaFiles;

  @Inject
  public BgD1SubmissionService(
      BgD1FileService fileService,
      NapArtifactService artifactService,
      NapSubmissionRepository submissionRepo,
      NapSubmissionFileRepository submissionFileRepo,
      MetaFiles metaFiles) {
    this.fileService = fileService;
    this.artifactService = artifactService;
    this.submissionRepo = submissionRepo;
    this.submissionFileRepo = submissionFileRepo;
    this.metaFiles = metaFiles;
  }

  /**
   * Прави подаване-чернова с двата файла и го връща. <b>НЕ подава.</b>
   *
   * @param d1 декларацията обр. 1 — записите ѝ дават редовете на {@code EMPL2021.TXT}
   * @param d6 декларацията обр. 6 за същото дружество и месец, или {@code null}
   * @param sourceFlag поле 53 на Д1 — ЕИК на разработчика или на осигурителната каса
   */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission attach(BgD1Declaration d1, BgD6Declaration d6, String sourceFlag)
      throws IOException {

    // ⚠️ ПЛАНЪТ СЕ ПРАВИ ПРЕДИ ДА СЕ ПИПНЕ БАЗАТА. Отказите му (нула записи, непотвърдена
    // комбинация в матрицата) падат тук, а не след като вече съществуват подаване и артефакти —
    // тоест базата не носи следа от опит, който не може да успее.
    List<BgD1SubmissionPlan.PlannedFile> planned =
        BgD1SubmissionPlan.plan(
            fileService, d1.getBgD1RecordList(), d6, d6LedgerFor(d6), sourceFlag);

    NapSubmission submission = new NapSubmission();
    submission.setServiceCodeSelect(NapSubmissionRepository.SERVICE_DECLARATIONS);
    submission.setServiceDocumentTypeSelect(ServiceDocumentType.DEC_1_6.name());
    submission.setCompany(d1.getCompany());
    submission.setPeriod(d1.getBgDataPeriod());
    submission.setReferenceDate(periodStart(d1.getBgDataPeriod()));
    // ⚠️ ОСТАВА ЧЕРНОВА. Виж класовия javadoc: подписът и подаването са човешки стъпки.
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_DRAFT);
    submission.setChannelSelect(NapSubmissionRepository.CHANNEL_API);
    submission.setEnvironmentSelect(NapSubmissionRepository.ENVIRONMENT_TEST);
    submission.setName(submissionName(d1));

    // ⚠️ ЕИК ПО БУЛСТАТ, НЕ ДДС НОМЕРЪТ — същият капан като при т. 4 на Д1. „БУЛСТАТ (фирма)“ и
    // „БУЛСТАТ (лице)“ са различни стойности на договора, а кое от двете е вярно зависи от
    // правната форма на осигурителя, не от числото — затова видът се чете от САМИЯ партньор
    // (partnerTypeSelect), а не се гадае по ЕИК-а. Полето е readonly на екрана, тоест ако не се
    // попълни тук, транспортът няма откъде да го получи.
    Partner partner = partnerOf(d1.getCompany());
    if (partner != null) {
      submission.setTaxpayerPin(trim(partner.getRegistrationCode()));
      submission.setTaxpayerPinTypeSelect(taxpayerPinType(partner));
    }
    submission.setRelatedModel(BgD1Declaration.class.getName());
    submission.setRelatedRecordId(d1.getId());

    NapSubmission saved = submissionRepo.save(submission);

    for (BgD1SubmissionPlan.PlannedFile file : planned) {
      submissionFileRepo.save(persist(saved, file));
    }
    return saved;
  }

  /**
   * Обезщетенията по вид 27 за месеца на Д6 — ПО СЪЩИЯ ПЪТ като генерирането ({@link
   * BgD6GenerationService#ledgerFor}): блоковете не се пазят, а се извеждат, тоест файлът и
   * генерирането трябва да виждат едно и също.
   */
  protected BgD6CompensationLedger d6LedgerFor(BgD6Declaration d6) {
    if (d6 == null) {
      return BgD6CompensationLedger.none();
    }
    return com.axelor.inject.Beans.get(BgD6GenerationService.class).ledgerFor(d6);
  }

  /**
   * Един планиран файл става НЕПРОМЕНИМ артефакт и ред на подаването.
   *
   * <p>⚠️ {@code fileSize} НЕ е второ независимо число: {@code
   * DeclarationsSubmissionMapper#buildFile} сверява дължината, записана на АРТЕФАКТА, срещу
   * действително прочетените байтове и отказва при разминаване. Тоест метаданните на реда са
   * УДОБСТВО за екрана, а истината е в артефакта и в неговия SHA-256.
   */
  protected NapSubmissionFile persist(
      NapSubmission submission, BgD1SubmissionPlan.PlannedFile planned) throws IOException {

    MetaFile metaFile =
        metaFiles.upload(new ByteArrayInputStream(planned.getBytes()), planned.getFileName());
    NapArtifact payload =
        artifactService.createForCompany(
            metaFile, NapArtifactRepository.TYPE_PAYLOAD, submission.getCompany());

    NapSubmissionFile file = new NapSubmissionFile();
    file.setSubmission(submission);
    file.setPayloadArtifact(payload);
    file.setSequence(planned.getSequence());
    file.setSubmittedFileName(planned.getFileName());
    file.setFileDocumentTypeSelect(planned.getDocumentType().name());
    file.setFileTypeSelect(FILE_TYPE_TXT);
    file.setFileSize((long) planned.getBytes().length);
    file.setNumRecords(planned.getNumRecords());
    file.setFileStatusSelect(NapSubmissionFileRepository.FILE_STATUS_PENDING);
    return file;
  }

  /** Име за човек: „Д1/Д6 · <дружество> · ММ.ГГГГ“. */
  protected String submissionName(BgD1Declaration d1) {
    Company company = d1.getCompany();
    LocalDate from = periodStart(d1.getBgDataPeriod());
    return "Д1/Д6 · "
        + (company == null ? "?" : nz(company.getName()))
        + " · "
        + (from == null ? "?" : String.format("%02d.%d", from.getMonthValue(), from.getYear()));
  }

  /**
   * Видът на идентификатора на ЗЛ по договора на НАП: ЮЛ подава с {@code BUS_BULSTAT}, физическо
   * лице с ЕИК по БУЛСТАТ (напр. ЕТ) — с {@code IND_BULSTAT}. Източникът е правната форма, записана
   * на партньора, а не самият номер.
   */
  protected static String taxpayerPinType(Partner partner) {
    return PartnerRepository.PARTNER_TYPE_INDIVIDUAL == nz(partner.getPartnerTypeSelect())
        ? PinType.IND_BULSTAT.name()
        : PinType.BUS_BULSTAT.name();
  }

  private static int nz(Integer value) {
    return value == null ? 0 : value;
  }

  private static Partner partnerOf(Company company) {
    return company == null ? null : company.getPartner();
  }

  private static LocalDate periodStart(Period period) {
    return period == null ? null : period.getFromDate();
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }
}

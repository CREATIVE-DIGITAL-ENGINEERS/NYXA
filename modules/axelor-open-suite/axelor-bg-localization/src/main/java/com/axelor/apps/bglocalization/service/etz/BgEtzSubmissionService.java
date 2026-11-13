package com.axelor.apps.bglocalization.service.etz;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.napsubmissions.common.artifact.NapArtifactService;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionFileRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
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
 * ЗАКАЧАНЕТО НА ЕТЗ ЗАПИСА КЪМ ТРАНСПОРТА — байтовете стават <b>подаване-чернова</b>.
 *
 * <p>Огледало на {@code BgD1SubmissionService}, със същите три граници и една допълнителна.
 *
 * <p><b>⚠️ ТОВА НЕ ПОДАВА — и каналът е РЪЧНИЯТ ПОРТАЛ.</b> Услугата спира на {@code STATUS_DRAFT}.
 * Човекът сваля файла, качва го в портала на НАП със своя КЕП или ПИК (НВРЗ чл. 9 ал. 2) и въвежда
 * входящия номер — пътят на {@code ManualSubmissionService}. Каналът API за ЕТЗ отказва ({@code
 * NapFilingLock.MANUAL_ONLY}): API-то иска детачен PKCS#7 със сертификата на подаващия, а в Nyxa
 * КЕП няма. Тестова среда няма — подаването е в портала.
 *
 * <p><b>⚠️ ЗАКАЧАЛКАТА Е КОНКРЕТНИЯТ ЗАПИС.</b> При сключване — договорът; при изменение и
 * прехвърляне — анексът и трансферът (виж втория {@code attach}). ⚠️ Правилото „един документ —
 * едно подаване“ ({@code NapFilingLock}) заключва по {@code relatedModel} + {@code
 * relatedRecordId}: прекратяването, закачено пак за договора, би било заключено от вече подаденото
 * сключване. То иска своя закачалка.
 *
 * <p><b>⚠️ ПЕРИОД НЕ СЕ ПОПЪЛВА.</b> {@code NapSubmission.period} е незадължителен и ЕТЗ записът е
 * за СЪБИТИЕ с дата, не за осигурителен период. Попълнен с месеца на събитието, той би изглеждал
 * като период на деклариране и би подвел справките.
 */
@Singleton
public class BgEtzSubmissionService {

  protected final NapArtifactService artifactService;
  protected final NapSubmissionRepository submissionRepo;
  protected final NapSubmissionFileRepository submissionFileRepo;
  protected final MetaFiles metaFiles;

  @Inject
  public BgEtzSubmissionService(
      NapArtifactService artifactService,
      NapSubmissionRepository submissionRepo,
      NapSubmissionFileRepository submissionFileRepo,
      MetaFiles metaFiles) {
    this.artifactService = artifactService;
    this.submissionRepo = submissionRepo;
    this.submissionFileRepo = submissionFileRepo;
    this.metaFiles = metaFiles;
  }

  /**
   * Прави подаване-чернова с ЕТЗ записа и го връща. <b>НЕ подава.</b>
   *
   * @param contract договорът, за който е събитието
   * @param kind събитието — то решава услугата и типа на файла
   * @param bytes готовите байтове (WINDOWS-1251)
   * @param eventDate датата на събитието — влиза като {@code referenceDate}
   */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission attach(
      EmploymentContract contract, BgEtzEventKind kind, byte[] bytes, LocalDate eventDate)
      throws IOException {
    return attach(contract, kind, bytes, eventDate, null, null);
  }

  /**
   * Същото, но с изрична закачалка към КОНКРЕТНИЯ запис, породил събитието.
   *
   * <p><b>⚠️ БЕЗ ТОВА ДВА АНЕКСА НА ЕДИН ДОГОВОР СОЧАТ ЕДНО И СЪЩО.</b> При сключване и
   * прекратяване закачалката е договорът и това е вярно — те се случват по веднъж. Изменението и
   * прехвърлянето се случват по МНОГО пъти и всяко има свой запис; закачени за договора, двете
   * подавания биха се различавали само по дата и никой не би могъл да каже кое кой анекс покрива.
   *
   * @param relatedModel класът на записа ({@code BgContractAmendment} / {@code BgContractTransfer})
   * @param relatedId неговият идентификатор
   */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission attach(
      EmploymentContract contract,
      BgEtzEventKind kind,
      byte[] bytes,
      LocalDate eventDate,
      Class<?> relatedModel,
      Long relatedId)
      throws IOException {

    if (contract == null) {
      throw new IllegalStateException("няма договор, към който да се закачи ЕТЗ подаването");
    }

    // ⚠️ ПЛАНЪТ СЕ ПРАВИ ПРЕДИ ДА СЕ ПИПНЕ БАЗАТА — отказите му падат тук, а не след като вече
    // съществуват подаване и артефакт.
    Partner partner = partnerOf(contract.getPayCompany());
    String eik = partner == null ? null : trim(partner.getRegistrationCode());
    List<BgEtzSubmissionPlan.PlannedFile> planned = BgEtzSubmissionPlan.plan(kind, bytes, eik);

    NapSubmission submission = new NapSubmission();
    submission.setServiceCodeSelect(NapSubmissionRepository.SERVICE_DECLARATIONS);
    submission.setServiceDocumentTypeSelect(kind.getService().name());
    submission.setCompany(contract.getPayCompany());
    submission.setReferenceDate(eventDate);
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_DRAFT);
    // Само през портала — виж класовия javadoc. Средата е на API-то; тук няма такава.
    submission.setChannelSelect(NapSubmissionRepository.CHANNEL_MANUAL_PORTAL);
    submission.setEnvironmentSelect(null);
    submission.setName(submissionName(contract, kind, eventDate));

    // ⚠️ ЕИК ПО БУЛСТАТ, НЕ ДДС НОМЕРЪТ. taxpayerPinType остава за човека: „БУЛСТАТ (фирма)“ и
    // „БУЛСТАТ (лице)“ са различни стойности на договора и кое е вярно зависи от правната форма.
    submission.setTaxpayerPin(eik);
    // ⚠️ Закачалката е КОНКРЕТНИЯТ запис, когато го има. Виж javadoc-а на този метод защо
    // договорът не стига за изменение и прехвърляне.
    submission.setRelatedModel(
        (relatedModel == null ? EmploymentContract.class : relatedModel).getName());
    submission.setRelatedRecordId(relatedModel == null ? contract.getId() : relatedId);

    NapSubmission saved = submissionRepo.save(submission);
    for (BgEtzSubmissionPlan.PlannedFile file : planned) {
      submissionFileRepo.save(persist(saved, file));
    }
    return saved;
  }

  /**
   * Един планиран файл става НЕПРОМЕНИМ артефакт и ред на подаването.
   *
   * <p>⚠️ {@code numRecords} НЕ се попълва: маперът го изисква само за {@code DEC_1}/{@code DEC_6}.
   * Виж {@link BgEtzSubmissionPlan}.
   */
  protected NapSubmissionFile persist(
      NapSubmission submission, BgEtzSubmissionPlan.PlannedFile planned) throws IOException {

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
    file.setFileTypeSelect(BgEtzSubmissionPlan.FILE_TYPE_XML);
    file.setFileSize((long) planned.getBytes().length);
    file.setFileStatusSelect(NapSubmissionFileRepository.FILE_STATUS_PENDING);
    return file;
  }

  /** Име за човек: „ЕТЗ прекратяване · <дружество> · 11.09.2026“. */
  protected String submissionName(
      EmploymentContract contract, BgEtzEventKind kind, LocalDate eventDate) {
    Company company = contract.getPayCompany();
    return "ЕТЗ "
        + (kind == null ? "?" : kind.getLabel())
        + " · "
        + (company == null ? "?" : nz(company.getName()))
        + " · "
        + (eventDate == null
            ? "?"
            : String.format(
                "%02d.%02d.%d",
                eventDate.getDayOfMonth(), eventDate.getMonthValue(), eventDate.getYear()));
  }

  private static Partner partnerOf(Company company) {
    return company == null ? null : company.getPartner();
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }
}

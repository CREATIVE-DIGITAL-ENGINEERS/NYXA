package com.axelor.apps.bglocalization.service.fatca;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgFatcaAccount;
import com.axelor.apps.bglocalization.db.BgFatcaProfile;
import com.axelor.apps.bglocalization.db.BgFatcaReport;
import com.axelor.apps.bglocalization.db.repo.BgFatcaProfileRepository;
import com.axelor.apps.bglocalization.db.repo.BgFatcaReportRepository;
import com.axelor.apps.bglocalization.service.fatca.dto.BgFatcaFileContent;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationPlannedFile;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationStatus;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationSubmissionService;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationSubmissionSpec;
import com.axelor.apps.napsubmissions.common.artifact.Institution;
import com.axelor.apps.napsubmissions.common.artifact.NapArtifactService;
import com.axelor.apps.napsubmissions.common.artifact.RegulatoryFile;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ЦИКЪЛЪТ НА FATCA ОТЧЕТА: чернова → генериран → подаден (ръчно, през портала на НАП).
 *
 * <p><b>⚠️ НИЩО НЕ СЕ ИЗПРАЩА.</b> API на НАП за FATCA няма; „Отбележи подаден“ само записва
 * входящия номер, който порталът е дал на качения с КЕП файл.
 *
 * <p><b>⚠️ ФАЙЛЪТ СЕ РАЖДА ПРИ ГЕНЕРИРАНЕ</b> — за да може човек да го свали и провери, преди да го
 * качи. „Върни в чернова“ забравя файла, но не го трие: артефактът остава в одитната следа.
 */
@Singleton
public class BgFatcaReportService {

  private static final String MIME_XML = "application/xml";

  protected final BgFatcaReportRepository reportRepo;
  protected final BgFatcaProfileRepository profileRepo;
  protected final BgDeclarationSubmissionService submissionService;
  protected final NapArtifactService artifactService;

  @Inject
  public BgFatcaReportService(
      BgFatcaReportRepository reportRepo,
      BgFatcaProfileRepository profileRepo,
      BgDeclarationSubmissionService submissionService,
      NapArtifactService artifactService) {
    this.reportRepo = reportRepo;
    this.profileRepo = profileRepo;
    this.submissionService = submissionService;
    this.artifactService = artifactService;
  }

  /** FATCA статусът на дружеството или {@code null}. */
  public BgFatcaProfile profileOf(Company company) {
    if (company == null) {
      return null;
    }
    return profileRepo.all().filter("self.company = ?1", company).fetchOne();
  }

  /**
   * Отчет подава само финансова институция за отчитане. НФЧП декларира статуса си пред банката и
   * отчет не подава.
   *
   * @throws IllegalStateException с причината
   */
  public BgFatcaProfile requireReportingFi(Company company) {
    BgFatcaProfile profile = profileOf(company);
    if (profile == null) {
      throw new IllegalStateException(
          "Компанията няма FATCA статус — попълни „FATCA статус на компанията“.");
    }
    if (!BgFatcaStatus.REPORTING_FI.equals(profile.getStatusSelect())) {
      throw new IllegalStateException(
          "FATCA отчет подава само финансова институция за отчитане. Компанията е с друг FATCA"
              + " статус — тя декларира статуса си пред банката и отчет към НАП не подава.");
    }
    return profile;
  }

  /**
   * ГЕНЕРИРА ФАЙЛА от записаните сметки. Без сметки — нулев отчет.
   *
   * @throws IllegalStateException при грешно състояние или блокираща находка — тогава файл няма
   */
  @Transactional(rollbackOn = Exception.class)
  public BgFatcaReport generate(BgFatcaReport reportCtx, LocalDate today) throws IOException {
    BgFatcaReport report = reportRepo.find(reportCtx.getId());
    if (!BgFatcaStatus.DRAFT.equals(report.getStatusSelect())) {
      throw new IllegalStateException(
          "Отчетът вече има файл. За промяна — „Върни в чернова“ (само преди подаване).");
    }
    BgFatcaProfile profile = requireReportingFi(report.getCompany());

    BgFatcaFileContent content =
        BgFatcaFileCollector.collect(
            report, profile, () -> UUID.randomUUID().toString(), LocalDateTime.now());
    BgDeclarationPlannedFile planned = BgFatcaSubmissionPlan.plan(content, today);

    NapArtifact artifact =
        artifactService.createFromFile(
            new RegulatoryFile(
                Institution.NAP, planned.getFileName(), MIME_XML, planned.getBytes()),
            NapArtifactRepository.TYPE_PAYLOAD,
            report.getCompany());

    Map<Long, String> docRefIds = new HashMap<>();
    for (BgFatcaFileContent.Account a : content.getAccounts()) {
      docRefIds.put(a.getId(), a.getDocRefId());
    }
    if (report.getAccountList() != null) {
      for (BgFatcaAccount a : report.getAccountList()) {
        a.setDocRefId(docRefIds.get(a.getId()));
      }
    }
    report.setFiName(content.getFiName());
    report.setFiGiin(content.getGiin());
    report.setMessageRefId(content.getMessageRefId());
    report.setGeneratedArtifact(artifact);
    report.setGeneratedFile(artifact.getMetaFile());
    report.setFileGeneratedOn(LocalDateTime.now());
    report.setFileSha256(artifact.getSha256());
    report.setFileAccountCount(planned.getNumRecords());
    report.setStatusSelect(BgFatcaStatus.GENERATED);
    return reportRepo.save(report);
  }

  /**
   * ВРЪЩА В ЧЕРНОВА — забравя генерирания файл, за да се поправят сметките.
   *
   * <p>⚠️ Само преди подаване: подаденият отчет е краен (корекции FATCA2–4 няма). Артефактът не се
   * трие — той е следата на това, което е било генерирано.
   */
  @Transactional(rollbackOn = Exception.class)
  public BgFatcaReport returnToDraft(BgFatcaReport reportCtx) {
    BgFatcaReport report = reportRepo.find(reportCtx.getId());
    if (!BgFatcaStatus.GENERATED.equals(report.getStatusSelect())) {
      throw new IllegalStateException(
          BgFatcaStatus.SUBMITTED.equals(report.getStatusSelect())
              ? "Отчетът е подаден — не се връща в чернова. Корекции (FATCA2–4) не се поддържат."
              : "Отчетът е чернова — няма файл за забравяне.");
    }
    report.setGeneratedFile(null);
    report.setGeneratedArtifact(null);
    report.setFileGeneratedOn(null);
    report.setFileSha256(null);
    report.setFileAccountCount(null);
    report.setMessageRefId(null);
    if (report.getAccountList() != null) {
      for (BgFatcaAccount a : report.getAccountList()) {
        a.setDocRefId(null);
      }
    }
    report.setStatusSelect(BgFatcaStatus.DRAFT);
    return reportRepo.save(report);
  }

  /**
   * ОТБЕЛЯЗВА ОТЧЕТА КАТО ПОДАДЕН през портала — с входящия номер и датата от НАП.
   *
   * <p>⚠️ Иска генериран файл: подаването сочи точно него. Тестов файл (FATCA11) не се отбелязва —
   * тестовата услуга не е подаване. Датата не може да е в бъдещето.
   *
   * <p>{@code referenceDate} е СРОКЪТ — 30 юни на следващата година (по страницата на НАП за
   * FATCA).
   */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission markSubmitted(
      BgFatcaReport reportCtx, String incomingNumber, LocalDate incomingDate, LocalDate today)
      throws IOException {
    BgFatcaReport report = reportRepo.find(reportCtx.getId());
    if (!BgFatcaStatus.GENERATED.equals(report.getStatusSelect())
        || report.getGeneratedFile() == null) {
      throw new IllegalStateException(
          "Отбелязва се само генериран и неподаден отчет — първо „Генерирай файла“.");
    }
    if (Boolean.TRUE.equals(report.getTestFile())) {
      throw new IllegalStateException(
          "Тестов файл (FATCA11) не се отбелязва като подаден — тестовата услуга не е подаване.");
    }
    BgDeclarationStatus.requireIncomingNumber(incomingNumber, incomingDate);
    if (incomingDate.isAfter(today)) {
      throw new IllegalStateException("Датата на входящия номер е в бъдещето.");
    }

    BgDeclarationSubmissionSpec spec =
        new BgDeclarationSubmissionSpec()
            .setServiceCode(NapSubmissionRepository.SERVICE_FATCA)
            .setCompany(report.getCompany())
            .setName("FATCA отчет / " + report.getReportYear())
            .setReferenceDate(LocalDate.of(report.getReportYear() + 1, 6, 30))
            .setTaxpayerPin(taxpayerPin(report.getCompany()))
            .setRelatedModel(BgFatcaReport.class.getName())
            .setRelatedRecordId(report.getId())
            .setMetaFile(report.getGeneratedFile())
            .setArtifact(report.getGeneratedArtifact())
            .setFileName(report.getGeneratedFile().getFileName())
            .setRecordCount(report.getFileAccountCount())
            .setIncomingNumber(incomingNumber.trim())
            .setIncomingDate(incomingDate);

    NapSubmission saved = submissionService.attach(spec);
    report.setNapSubmission(saved);
    report.setNapIncomingNumber(incomingNumber.trim());
    report.setNapIncomingDate(incomingDate);
    report.setStatusSelect(BgFatcaStatus.SUBMITTED);
    reportRepo.save(report);
    return saved;
  }

  private static String taxpayerPin(Company company) {
    if (company == null || company.getPartner() == null) {
      return null;
    }
    String code = company.getPartner().getRegistrationCode();
    return code == null || code.trim().isEmpty() ? null : code.trim();
  }
}

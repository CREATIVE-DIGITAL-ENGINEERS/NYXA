package com.axelor.apps.napsubmissions.services.saft;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.napsubmissions.common.artifact.Institution;
import com.axelor.apps.napsubmissions.common.artifact.NapArtifactService;
import com.axelor.apps.napsubmissions.common.artifact.RegulatoryFile;
import com.axelor.apps.napsubmissions.common.audit.NapAuditService;
import com.axelor.apps.napsubmissions.common.immutability.SaftReportStatusGuard;
import com.axelor.apps.napsubmissions.common.submission.ManualSubmissionService;
import com.axelor.apps.napsubmissions.common.submission.NapProcessingResultService;
import com.axelor.apps.napsubmissions.common.submission.NapSubmissionWorkflowService;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.SaftValidationIssue;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactRepository;
import com.axelor.apps.napsubmissions.db.repo.NapAuditEventRepository;
import com.axelor.apps.napsubmissions.db.repo.NapProcessingResultRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.db.repo.SaftReportRepository;
import com.axelor.apps.napsubmissions.db.repo.SaftValidationIssueRepository;
import com.axelor.db.JPA;
import com.axelor.i18n.I18n;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * SAF-T ФАЙЛЪТ ОТ ЕКРАНА (G6b; годишният и при поискване — T1): проверка → генериране → подаване в
 * портала → изход.
 *
 * <p><b>⚠️ READY САМО СЛЕД ИСТИНСКА ПРОВЕРКА (риск 6 от одита).</b> Готовността идва от живата
 * проверка ({@link SaftFileEngine}: сглобяване, централните правила и XSD-то над самите байтове),
 * не от записаните находки — те са само за показване. Без проверка (модулът липсва) отчетът не
 * става READY. Статусите и генерираният файл се пишат само тук, в {@link
 * SaftReportStatusGuard#allow}; всеки друг път е отказ.
 *
 * <p><b>Пътят е „Генериран → Подаден → Приет / Отхвърлен“</b>, без одобрение от втори човек
 * (решение 20.10.2026): защитата е способността за подаване ({@code NapFilingGate}, {@code
 * accounting.nap.write}) и проверката по-горе.
 *
 * <ul>
 *   <li><b>Генерирай</b> — отказва при ГРЕШКА; файлът е нов, неизменен {@link NapArtifact} на
 *       дружеството. Ново генериране = нов артефакт; старите остават.
 *   <li><b>Подаден</b> — само в портала (SAF-T не е в API-то на НАП): вх. № и датата от портала, по
 *       пътя на #331 ({@link ManualSubmissionService}: версията за днес, доказателството на #334,
 *       „един документ — едно подаване“).
 *   <li><b>Отхвърлен</b> позволява нов опит; <b>Приет</b> заключва периода — месеца, годината или
 *       искането ({@link SaftPeriodLock}). Коригиращ файл за приет период е извън обхвата — отказ с
 *       ясен текст.
 * </ul>
 */
@Singleton
public class SaftReportWorkflowService {

  /** Целта на находките от протокола на НАП (опитът) — по нея следващата проверка не ги трие. */
  public static final String PROTOCOL_MODEL = NapSubmission.class.getName();

  protected final SaftReportRepository reportRepo;
  protected final NapArtifactService artifactService;
  protected final NapAuditService auditService;

  /**
   * Мързеливо: веригата на генератора (банкови сметки → … → PaymentModeServiceImpl) има услуги с
   * обхват на HTTP заявката, а тази услуга се инжектира и извън заявка (тестове, стартови стъпки).
   */
  protected final Provider<SaftFileEngine> engine;

  protected final SaftPeriodLock periodLock;
  protected final SaftValidationIssueRepository issueRepo;
  protected final NapSubmissionRepository submissionRepo;
  protected final NapSubmissionWorkflowService submissionWorkflow;
  protected final ManualSubmissionService manualSubmission;
  protected final NapProcessingResultService processingResults;
  protected final SaftPayloadVersionCheck versionCheck;

  @Inject
  public SaftReportWorkflowService(
      SaftReportRepository reportRepo,
      NapArtifactService artifactService,
      NapAuditService auditService,
      Provider<SaftFileEngine> engine,
      SaftPeriodLock periodLock,
      SaftValidationIssueRepository issueRepo,
      NapSubmissionRepository submissionRepo,
      NapSubmissionWorkflowService submissionWorkflow,
      ManualSubmissionService manualSubmission,
      NapProcessingResultService processingResults,
      SaftPayloadVersionCheck versionCheck) {
    this.reportRepo = reportRepo;
    this.artifactService = artifactService;
    this.auditService = auditService;
    this.engine = engine;
    this.periodLock = periodLock;
    this.issueRepo = issueRepo;
    this.submissionRepo = submissionRepo;
    this.submissionWorkflow = submissionWorkflow;
    this.manualSubmission = manualSubmission;
    this.processingResults = processingResults;
    this.versionCheck = versionCheck;
  }

  // ─────────────── Провери ───────────────

  /**
   * „Провери“: пълната проверка, без да се записва файл. Находките заменят предишните (без тези от
   * протокола на НАП); READY само без ГРЕШКА.
   *
   * @param today датата на дружеството — от нея са S.H.1 и XSD-то
   */
  @Transactional(rollbackOn = Exception.class)
  public SaftCheckResult validateReport(SaftReport input, LocalDate today) throws Exception {
    SaftCheckResult result = engine.get().run(input, today, lastGenerated(input));
    SaftReport report = attached(input);
    SaftReportStatusGuard.allow(
        () -> {
          String previous = report.getLifecycleStatusSelect();
          replaceIssues(report, result);
          boolean passed = result.isChecked() && !result.hasErrors();
          report.setReadinessStatusSelect(
              passed
                  ? SaftReportRepository.READINESS_READY
                  : SaftReportRepository.READINESS_INCOMPLETE);
          if (!passed) {
            report.setLifecycleStatusSelect(SaftReportRepository.LIFECYCLE_FAILED);
          } else if (!SaftReportRepository.LIFECYCLE_GENERATED.equals(previous)) {
            report.setLifecycleStatusSelect(SaftReportRepository.LIFECYCLE_READY);
          }
          auditService.logHuman(
              report,
              null,
              NapAuditEventRepository.EVENT_VALIDATION,
              previous,
              report.getLifecycleStatusSelect(),
              summary(result));
          reportRepo.save(report);
          JPA.flush();
          return null;
        });
    return result;
  }

  // ─────────────── Генерирай ───────────────

  /**
   * „Генерирай“: проверката и — без ГРЕШКА — нов неизменен файл. При ГРЕШКА файл няма, находките
   * остават записани и отчетът е FAILED (не се хвърля: находките трябва да се запишат).
   *
   * @throws AxelorException периодът е подаден или приет ({@link SaftPeriodLock})
   */
  @Transactional(rollbackOn = Exception.class)
  public SaftCheckResult generate(SaftReport input, LocalDate today) throws Exception {
    String locked = periodLock.refusal(input);
    if (locked != null) {
      throw new AxelorException(TraceBackRepository.CATEGORY_INCONSISTENCY, locked);
    }
    SaftCheckResult result = engine.get().run(input, today, lastGenerated(input));
    SaftReport report = attached(input);
    SaftReportStatusGuard.allow(
        () -> {
          String previous = report.getLifecycleStatusSelect();
          replaceIssues(report, result);
          if (!result.isGenerated()) {
            report.setLifecycleStatusSelect(SaftReportRepository.LIFECYCLE_FAILED);
            report.setReadinessStatusSelect(SaftReportRepository.READINESS_INCOMPLETE);
            auditService.logHuman(
                report,
                null,
                NapAuditEventRepository.EVENT_VALIDATION,
                previous,
                SaftReportRepository.LIFECYCLE_FAILED,
                "Generation refused — " + summary(result));
          } else {
            NapArtifact artifact = store(report, result);
            report.setGeneratedArtifact(artifact);
            report.setSchemaVersion(result.getAuditFileVersion());
            report.setLifecycleStatusSelect(SaftReportRepository.LIFECYCLE_GENERATED);
            report.setReadinessStatusSelect(SaftReportRepository.READINESS_READY);
            auditService.logHuman(
                report,
                null,
                NapAuditEventRepository.EVENT_ARTIFACT_ATTACHED,
                previous,
                SaftReportRepository.LIFECYCLE_GENERATED,
                "Generated "
                    + artifact.getName()
                    + " (S.H.1 "
                    + result.getAuditFileVersion()
                    + ", sha256="
                    + artifact.getSha256()
                    + ") — "
                    + summary(result));
          }
          reportRepo.save(report);
          JPA.flush();
          return null;
        });
    return result;
  }

  /**
   * Отчетът, закачен към сесията — СЛЕД двигателя и ПРЕДИ записа.
   *
   * <p>⚠️ Двигателят може да изчисти сесията: читателите на SAF-T четат на парчета и викат {@code
   * JPA.clear()} — само когато периодът има осчетоводени редове, тоест на празен месец това не се
   * вижда. Подаденият отчет тогава е откачен и новата находка ({@link SaftValidationIssue},
   * many-to-one с каскада persist) пада с „detached entity passed to persist: SaftReport“. Затова
   * отчетът се чете наново по id; незаписани промени по него преди двигателя няма.
   */
  protected SaftReport attached(SaftReport report) {
    if (report.getId() == null || JPA.em().contains(report)) {
      return report;
    }
    return reportRepo.find(report.getId());
  }

  /** Байтовете → неизменен артефакт на дружеството; отпечатъкът се сверява два пъти. */
  protected NapArtifact store(SaftReport report, SaftCheckResult result) throws IOException {
    RegulatoryFile file =
        new RegulatoryFile(
            Institution.NAP, result.getFileName(), "application/xml", result.getBytes());
    if (!file.getSha256().equals(result.getSha256())) {
      throw new IllegalStateException("SAF-T: SHA-256 на генератора не съвпада с байтовете");
    }
    NapArtifact artifact =
        artifactService.createForReport(file, NapArtifactRepository.TYPE_PAYLOAD, report);
    if (!result.getSha256().equals(artifact.getSha256())) {
      throw new IllegalStateException("SAF-T: записаният файл не съвпада с генерирания");
    }
    return artifact;
  }

  // ─────────────── Свали за портала ───────────────

  /**
   * Файлът за портала — последният генериран. Отказ, ако версията му (S.H.1) не е версията за днес:
   * версията е по момента на подаване (QA II.2.4), а файлът може да е от преди смяната ѝ.
   */
  public NapArtifact fileForPortal(SaftReport report, LocalDate today) throws AxelorException {
    NapArtifact artifact = report.getGeneratedArtifact();
    if (artifact == null || artifact.getMetaFile() == null) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_NO_VALUE,
          I18n.get("Няма генериран SAF-T файл — първо „Генерирай“."));
    }
    String refusal = versionCheck.refusal(bytes(artifact.getMetaFile()), today);
    if (refusal != null) {
      throw new AxelorException(TraceBackRepository.CATEGORY_INCONSISTENCY, refusal);
    }
    return artifact;
  }

  // ─────────────── Подаден ───────────────

  /**
   * „Отбележи подаден“: нов опит с генерирания файл и вх. № с датата от портала — по ръчния път на
   * #331. Отказ, ако периодът вече е подаден или приет.
   */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission recordPortalFiling(
      SaftReport report, String incomingNumber, LocalDate portalDate) throws AxelorException {
    String locked = periodLock.refusal(report);
    if (locked != null) {
      throw new AxelorException(TraceBackRepository.CATEGORY_INCONSISTENCY, locked);
    }
    NapSubmission submission =
        submissionWorkflow.createSubmission(report, NapSubmissionRepository.CHANNEL_MANUAL_PORTAL);
    return manualSubmission.recordManualSubmission(submission, incomingNumber, portalDate);
  }

  /** Опитът, който чака изход (подаден, без отбелязан резултат); {@code null}, ако няма. */
  public NapSubmission awaitingOutcome(SaftReport report) {
    return latest(report, List.of(NapSubmissionRepository.STATUS_SUBMITTED));
  }

  /** Последният подаден опит (за протокола): подаден, приет или отхвърлен. */
  public NapSubmission lastFiled(SaftReport report) {
    return latest(
        report,
        List.of(
            NapSubmissionRepository.STATUS_SUBMITTED,
            NapSubmissionRepository.STATUS_ACCEPTED,
            NapSubmissionRepository.STATUS_REJECTED,
            NapSubmissionRepository.STATUS_CORRECTION_REQUIRED));
  }

  // ─────────────── Приет / Отхвърлен ───────────────

  /** „Приет“: заключва периода. */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission recordAccepted(NapSubmission submission) throws AxelorException {
    requireAwaiting(submission);
    return submissionWorkflow.recordAccepted(submission, null);
  }

  /** „Отхвърлен“: с причина; позволява нов опит. */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission recordRejected(NapSubmission submission, String reason)
      throws AxelorException {
    requireAwaiting(submission);
    if (reason == null || reason.isBlank()) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_MISSING_FIELD,
          I18n.get("Въведете причината за отхвърлянето (от протокола на НАП)."));
    }
    return submissionWorkflow.recordRejected(submission, reason.trim());
  }

  protected void requireAwaiting(NapSubmission submission) throws AxelorException {
    if (submission == null
        || !NapSubmissionRepository.STATUS_SUBMITTED.equals(
            submission.getSubmissionStatusSelect())) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get("Няма подаване, което чака изход — първо „Отбележи подаден“."));
    }
  }

  // ─────────────── Протоколът на НАП ───────────────

  /**
   * Протоколът на НАП (errsaft XML) към последния подаден опит: неизменен файл, резултат от
   * обработката и разчетените грешки като находки с връзка към опита. Статусът не се сменя — него
   * го отбелязва човекът.
   */
  @Transactional(rollbackOn = Exception.class)
  public List<SaftNapProtocol.Error> attachProtocol(NapSubmission submission, MetaFile protocol)
      throws AxelorException, IOException {
    List<SaftNapProtocol.Error> errors;
    try {
      errors = SaftNapProtocol.parse(bytes(protocol));
    } catch (IllegalArgumentException e) {
      throw new AxelorException(TraceBackRepository.CATEGORY_INCONSISTENCY, e.getMessage());
    }
    String number = submission.getNapIncomingNumber();
    StringBuilder text = new StringBuilder();
    text.append(
        errors.isEmpty()
            ? "Протокол на НАП без грешки."
            : "Протокол на НАП: " + errors.size() + " грешки. ");
    for (SaftNapProtocol.Error error : errors) {
      text.append(error).append("; ");
    }
    processingResults.recordResult(
        submission,
        protocol,
        NapProcessingResultRepository.FORMAT_TEXT,
        errors.isEmpty()
            ? NapProcessingResultRepository.OUTCOME_UNKNOWN
            : NapProcessingResultRepository.OUTCOME_REJECTED,
        text.toString(),
        "errsaft-1");

    SaftReport report = submission.getSaftReport();
    for (SaftValidationIssue old :
        issueRepo
            .all()
            .filter("self.targetModel = :model AND self.targetRecordId = :id")
            .bind("model", PROTOCOL_MODEL)
            .bind("id", submission.getId())
            .fetch()) {
      issueRepo.remove(old);
    }
    for (SaftNapProtocol.Error error : errors) {
      SaftValidationIssue issue = new SaftValidationIssue();
      issue.setSaftReport(report);
      issue.setSeveritySelect(SaftValidationIssueRepository.SEVERITY_BLOCKING);
      issue.setBlocking(true);
      issue.setSectionFieldId(error.getCode());
      issue.setMessage(
          "Протокол на НАП (вх. № "
              + (number == null ? "—" : number)
              + "), грешка "
              + error.getNumber()
              + ": "
              + error.getDescription());
      issue.setTargetModel(PROTOCOL_MODEL);
      issue.setTargetRecordId(submission.getId());
      issueRepo.save(issue);
    }
    return errors;
  }

  // ─────────────── Коригиращ файл ───────────────

  /**
   * Коригиращ файл — ИЗВЪН ОБХВАТА: за приет период чакаме указания от НАП, а неприет период не се
   * коригира, а се генерира наново. Винаги отказ, с ясен текст.
   */
  public SaftReport createCorrection(SaftReport report, String reason) throws AxelorException {
    if (periodLock.accepted(report)) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY, periodLock.refusal(report));
    }
    throw new AxelorException(
        TraceBackRepository.CATEGORY_INCONSISTENCY,
        I18n.get(
            "Коригиращ файл е само за приет период. Този период не е приет — поправете данните и"
                + " генерирайте файла наново."));
  }

  /**
   * Attach an externally prepared file to the report (before G6b the only way to get a file in).
   * Not reachable from the screen any more: a filing carries only the generated file ({@code
   * generatedArtifact}). Kept for the artifact-company tests.
   */
  @Transactional(rollbackOn = Exception.class)
  public NapArtifact attachPreparedArtifact(SaftReport report, MetaFile metaFile)
      throws IOException {
    NapArtifact artifact =
        artifactService.createFromMetaFile(metaFile, NapArtifactRepository.TYPE_PAYLOAD, report);
    auditService.logHuman(
        report,
        null,
        NapAuditEventRepository.EVENT_ARTIFACT_ATTACHED,
        report.getLifecycleStatusSelect(),
        report.getLifecycleStatusSelect(),
        "Attached prepared file: " + artifact.getName() + " (sha256=" + artifact.getSha256() + ")");
    return artifact;
  }

  // ─────────────── помощни ───────────────

  /** Находките от проверката заменят предишните; тези от протокола на НАП остават. */
  protected void replaceIssues(SaftReport report, SaftCheckResult result) {
    for (SaftValidationIssue old :
        issueRepo
            .all()
            .filter(
                "self.saftReport = :report AND (self.targetModel IS NULL OR self.targetModel !="
                    + " :protocol)")
            .bind("report", report)
            .bind("protocol", PROTOCOL_MODEL)
            .fetch()) {
      issueRepo.remove(old);
    }
    String period = SaftPeriodLock.label(report);
    for (SaftCheckResult.Finding finding : result.getFindings()) {
      SaftValidationIssue issue = new SaftValidationIssue();
      issue.setSaftReport(report);
      issue.setSeveritySelect(
          finding.isError()
              ? SaftValidationIssueRepository.SEVERITY_BLOCKING
              : SaftValidationIssueRepository.SEVERITY_WARNING);
      issue.setBlocking(finding.isError());
      issue.setSectionFieldId(finding.getCode());
      issue.setPeriod(period);
      issue.setMessage(finding.getMessage());
      issue.setTargetModel(finding.getTargetModel());
      issue.setTargetRecordId(finding.getTargetId());
      issueRepo.save(issue);
    }
  }

  protected NapSubmission latest(SaftReport report, List<String> statuses) {
    return submissionRepo
        .all()
        .filter("self.saftReport = :report AND self.submissionStatusSelect IN :statuses")
        .bind("report", report)
        .bind("statuses", statuses)
        .order("-id")
        .fetchOne();
  }

  protected LocalDateTime lastGenerated(SaftReport report) {
    NapArtifact last = report.getGeneratedArtifact();
    return last == null ? null : last.getCreatedOn();
  }

  protected static byte[] bytes(MetaFile file) throws AxelorException {
    try {
      return Files.readAllBytes(MetaFiles.getPath(file));
    } catch (IOException e) {
      throw new AxelorException(
          e, TraceBackRepository.CATEGORY_INCONSISTENCY, I18n.get("Файлът не може да се прочете."));
    }
  }

  protected static String summary(SaftCheckResult result) {
    long errors = result.getFindings().stream().filter(SaftCheckResult.Finding::isError).count();
    long warnings = result.getFindings().size() - errors;
    return (result.isChecked() ? "checked" : "check unavailable")
        + ": "
        + errors
        + " errors, "
        + warnings
        + " warnings";
  }
}

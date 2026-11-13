package com.axelor.apps.napsubmissions.common.submission;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.napsubmissions.common.artifact.NapArtifactService;
import com.axelor.apps.napsubmissions.common.audit.NapAuditService;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.repo.NapAuditEventRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionArtifactRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionFileRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.db.repo.SaftReportRepository;
import com.axelor.auth.AuthUtils;
import com.axelor.i18n.I18n;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Common workflow for a НАП submission attempt. A submission is always created against an already
 * approved/signed report, and submitting/refreshing are explicit human actions. Retries are manual
 * in this iteration (a user re-invokes the action); there is no automatic retry loop.
 */
@Singleton
public class NapSubmissionWorkflowService {

  protected final NapSubmissionRepository submissionRepo;
  protected final NapArtifactService artifactService;
  protected final NapAuditService auditService;
  protected final NapSubmissionClient submissionClient;
  protected final NapFilingLock filingLock;
  protected final NapSubmissionFileRepository submissionFileRepo;

  @Inject
  public NapSubmissionWorkflowService(
      NapSubmissionRepository submissionRepo,
      NapArtifactService artifactService,
      NapAuditService auditService,
      NapSubmissionClient submissionClient,
      NapFilingLock filingLock,
      NapSubmissionFileRepository submissionFileRepo) {
    this.submissionRepo = submissionRepo;
    this.artifactService = artifactService;
    this.auditService = auditService;
    this.submissionClient = submissionClient;
    this.filingLock = filingLock;
    this.submissionFileRepo = submissionFileRepo;
  }

  /**
   * A new filing attempt for a GENERATED SAF-T report (G6b), carrying exactly the file the
   * generator wrote last ({@code SaftReport.generatedArtifact}) — as the payload link and as the
   * submission's file, so its SHA-256 is shown with the filing. There is no approval step for SAF-T
   * (decision 20.10.2026): the protection is the filing capability and a report that is
   * READY/GENERATED only after a real check.
   */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission createSubmission(SaftReport report, String channel) throws AxelorException {
    String status = report.getLifecycleStatusSelect();
    NapArtifact payload = report.getGeneratedArtifact();
    if (!SaftReportRepository.LIFECYCLE_GENERATED.equals(status) || payload == null) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(
              "Only a generated SAF-T file can be submitted — press “Generate” first (currently:"
                  + " %s)."),
          status);
    }

    long previousAttempts =
        submissionRepo.all().filter("self.saftReport = :r").bind("r", report).count();

    NapSubmission submission = new NapSubmission();
    submission.setSaftReport(report);
    submission.setCompany(report.getCompany());
    submission.setPeriod(report.getPeriod());
    submission.setServiceCodeSelect(NapSubmissionRepository.SERVICE_SAFT);
    submission.setRelatedModel(SaftReport.class.getName());
    submission.setRelatedRecordId(report.getId());
    submission.setChannelSelect(channel);
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_DRAFT);
    submission.setAttemptNumber((int) previousAttempts + 1);
    submission.setIdempotencyKey(UUID.randomUUID().toString());
    submission.setSubmittedBy(AuthUtils.getUser());
    submissionRepo.save(submission);

    artifactService.link(submission, payload, NapSubmissionArtifactRepository.ROLE_PAYLOAD);
    NapSubmissionFile file = new NapSubmissionFile();
    file.setSubmission(submission);
    file.setPayloadArtifact(payload);
    file.setSequence(0);
    file.setSubmittedFileName(payload.getOriginalFileName());
    file.setFileSize(payload.getByteLength());
    submissionFileRepo.save(file);

    auditService.logHuman(
        null,
        submission,
        NapAuditEventRepository.EVENT_STATUS_CHANGE,
        null,
        NapSubmissionRepository.STATUS_DRAFT,
        "Created submission attempt #"
            + submission.getAttemptNumber()
            + " for "
            + payload.getName()
            + " (sha256="
            + payload.getSha256()
            + ")");
    return submission;
  }

  /**
   * Attempt an API submission. With the current {@link com.axelor.apps.napsubmissions.api
   * .UnsupportedNapApiSubmissionClient} this always ends {@code technical_error} with a clear "API
   * not configured" message; it never throws or blocks the app.
   */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission submitThroughApi(NapSubmission submission) throws AxelorException {
    // Never resubmit an attempt whose outcome is unknown — the server may already hold it.
    // Reconcile
    // via status refresh or resolve it explicitly first.
    if (NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN.equals(
        submission.getSubmissionStatusSelect())) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(
              "This submission's outcome is unknown; resubmitting is blocked. Refresh its status or"
                  + " resolve it manually first."));
    }
    LocalDateTime now = LocalDateTime.now();
    // A filed submission is evidence (#334): its rule is asked BEFORE the client is called, not at
    // the flush after it — with a Bulgarian refusal. Resending after a technical error stays
    // allowed.
    Map<String, Object> planned = new LinkedHashMap<>();
    planned.put("channelSelect", NapSubmissionRepository.CHANNEL_API);
    planned.put("submissionStatusSelect", NapSubmissionRepository.STATUS_SUBMITTING);
    planned.put("submittedOn", now);
    planned.put("submittedBy", AuthUtils.getUser());
    NapFilingEvidenceCheck.requireWritable(submission, planned, "The API submission");
    // One document, one filing: another row of the same document already filed locks this one.
    filingLock.requireApiAllowed(submission);
    String previous = submission.getSubmissionStatusSelect();
    submission.setChannelSelect(NapSubmissionRepository.CHANNEL_API);
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SUBMITTING);
    submission.setSubmittedOn(now);
    submission.setSubmittedBy(AuthUtils.getUser());

    NapSubmissionResult result = submissionClient.submit(submission);
    applyResult(submission, result, true);

    auditService.logHuman(
        null,
        submission,
        NapAuditEventRepository.EVENT_API_SUBMISSION,
        previous,
        submission.getSubmissionStatusSelect(),
        result.getErrorMessage());
    return submissionRepo.save(submission);
  }

  /** Re-query external status (manual action). */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission refreshSubmissionStatus(NapSubmission submission) {
    String previous = submission.getSubmissionStatusSelect();
    NapSubmissionResult result = submissionClient.getStatus(submission);
    applyResult(submission, result, false);
    auditService.logHuman(
        null,
        submission,
        NapAuditEventRepository.EVENT_STATUS_CHANGE,
        previous,
        submission.getSubmissionStatusSelect(),
        result.getErrorMessage());
    return submissionRepo.save(submission);
  }

  /** Record НАП acceptance and the incoming number (manual or API-driven). */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission recordAccepted(NapSubmission submission, String incomingNumber) {
    String previous = submission.getSubmissionStatusSelect();
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_ACCEPTED);
    submission.setProcessedOn(LocalDateTime.now());
    if (incomingNumber != null && !incomingNumber.isBlank()) {
      submission.setNapIncomingNumber(incomingNumber);
    }
    auditService.logHuman(
        null,
        submission,
        NapAuditEventRepository.EVENT_ACCEPTANCE,
        previous,
        NapSubmissionRepository.STATUS_ACCEPTED,
        String.format(I18n.get("NAP incoming no.: %s"), submission.getNapIncomingNumber()));
    return submissionRepo.save(submission);
  }

  /** Record НАП rejection with the reason/error. */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission recordRejected(NapSubmission submission, String errorMessage) {
    String previous = submission.getSubmissionStatusSelect();
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_REJECTED);
    submission.setProcessedOn(LocalDateTime.now());
    submission.setErrorMessage(errorMessage);
    auditService.logHuman(
        null,
        submission,
        NapAuditEventRepository.EVENT_REJECTION,
        previous,
        NapSubmissionRepository.STATUS_REJECTED,
        errorMessage);
    return submissionRepo.save(submission);
  }

  protected void applyResult(
      NapSubmission submission, NapSubmissionResult result, boolean submitting) {
    submission.setExternalStatus(result.getExternalStatus());
    submission.setCorrelationId(result.getCorrelationId());
    submission.setExceptionId(result.getExceptionId());
    if (result.getDocumentId() != null) {
      submission.setDocumentId(result.getDocumentId());
    }
    if (result.getEntryNumber() != null) {
      submission.setEntryNumber(result.getEntryNumber());
    }
    if (result.getEntryDate() != null) {
      submission.setEntryDate(result.getEntryDate());
    }

    if (result.isOutcomeUnknown()) {
      // Ambiguous: unknown whether НАП accepted it. Do NOT retry blindly — reconcile via the result
      // endpoint using documentId/entryNumber first.
      submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN);
      submission.setErrorMessage(result.getErrorMessage());
    } else if (!result.isConfigured() || !result.isSuccess()) {
      submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_TECHNICAL_ERROR);
      submission.setErrorCode(result.getErrorCode());
      submission.setErrorMessage(result.getErrorMessage());
    } else if (submitting) {
      submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SUBMITTED);
    }
  }
}

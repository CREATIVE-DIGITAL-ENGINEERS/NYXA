package com.axelor.apps.napsubmissions.common.submission;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.napsubmissions.common.artifact.NapArtifactService;
import com.axelor.apps.napsubmissions.common.audit.NapAuditService;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionArtifact;
import com.axelor.apps.napsubmissions.db.repo.NapAuditEventRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionArtifactRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.services.saft.SaftPayloadVersionCheck;
import com.axelor.auth.AuthUtils;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manual portal workflow. This is NOT an API client — it performs no transport. It only records
 * what a human did on the НАП portal (filed the file, got an incoming number, attached the
 * response) so the audit trail and status stay accurate even when the API is unavailable.
 *
 * <p>This is the path that is ALWAYS there (decision 17–18.10.2026): Nyxa generates the file, the
 * person downloads it and files it on the portal with their own КЕП, then enters the incoming
 * number and date here. A document filed through the API cannot be marked by hand ({@link
 * NapFilingLock}).
 */
@Singleton
public class ManualSubmissionService {

  protected final NapSubmissionRepository submissionRepo;
  protected final NapArtifactService artifactService;
  protected final NapAuditService auditService;
  protected final SaftPayloadVersionCheck versionCheck;
  protected final NapFilingLock filingLock;

  @Inject
  public ManualSubmissionService(
      NapSubmissionRepository submissionRepo,
      NapArtifactService artifactService,
      NapAuditService auditService,
      SaftPayloadVersionCheck versionCheck,
      NapFilingLock filingLock) {
    this.submissionRepo = submissionRepo;
    this.artifactService = artifactService;
    this.auditService = auditService;
    this.versionCheck = versionCheck;
    this.filingLock = filingLock;
  }

  /**
   * Record that the operator uploaded the file at the portal and captured the incoming number.
   *
   * <p>{@code submittedOn} is the PORTAL's date, not now: the number is often entered days later.
   * {@code submittedBy} is who entered it. The SHA-256 of what was filed is that of the immutable
   * {@code NapArtifact} behind each file, whose link freezes on filing (#327, #334).
   */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission recordManualSubmission(
      NapSubmission submission, String incomingNumber, LocalDate portalDate)
      throws AxelorException {
    if (incomingNumber == null || incomingNumber.isBlank()) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_MISSING_FIELD,
          I18n.get("The NAP incoming number is required to record a manual submission."));
    }
    if (portalDate == null) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_MISSING_FIELD,
          I18n.get("Enter the date on which the portal issued the incoming number."));
    }
    if (portalDate.isAfter(LocalDate.now())) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get("The portal submission date cannot be in the future."));
    }
    checkPayloadVersion(submission);
    String number = incomingNumber.trim();
    // Подаденото е доказателство (#334): правилото му се пита ПРЕДИ записа, с български отказ.
    Map<String, Object> planned = new LinkedHashMap<>();
    planned.put("channelSelect", NapSubmissionRepository.CHANNEL_MANUAL_PORTAL);
    planned.put("submissionStatusSelect", NapSubmissionRepository.STATUS_SUBMITTED);
    planned.put("napIncomingNumber", number);
    planned.put("submittedOn", portalDate.atStartOfDay());
    planned.put("submittedBy", AuthUtils.getUser());
    NapFilingEvidenceCheck.requireWritable(submission, planned, "The manual recording");
    filingLock.requireManualAllowed(submission);

    String previous = submission.getSubmissionStatusSelect();
    submission.setChannelSelect(NapSubmissionRepository.CHANNEL_MANUAL_PORTAL);
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SUBMITTED);
    submission.setNapIncomingNumber(number);
    submission.setSubmittedOn(portalDate.atStartOfDay());
    submission.setSubmittedBy(AuthUtils.getUser());
    auditService.logHuman(
        null,
        submission,
        NapAuditEventRepository.EVENT_MANUAL_SUBMISSION,
        previous,
        NapSubmissionRepository.STATUS_SUBMITTED,
        String.format(I18n.get("NAP incoming no.: %s; portal date: %s"), number, portalDate));
    return submissionRepo.save(submission);
  }

  /**
   * SAF-T: файлът, за който се записва входящ номер, трябва да е по версията за ДНЕС — версията е
   * по момента на подаване (QA „Въпроси и отговори“ на НАП, март 2026 г., версия 2, II.2.4, с. 13),
   * а файлът може да е генериран преди смяната ѝ. Отказът е преди всяка промяна на състоянието.
   */
  protected void checkPayloadVersion(NapSubmission submission) throws AxelorException {
    if (submission.getSaftReport() == null) {
      return;
    }
    LocalDate today = today(submission);
    for (byte[] payload : payloads(submission)) {
      String refusal = versionCheck.refusal(payload, today);
      if (refusal != null) {
        throw new AxelorException(TraceBackRepository.CATEGORY_INCONSISTENCY, refusal);
      }
    }
  }

  /** Байтовете на файловете, които опитът подава (роля „payload“). */
  protected List<byte[]> payloads(NapSubmission submission) throws AxelorException {
    List<byte[]> payloads = new ArrayList<>();
    if (submission.getArtifactLinkList() == null) {
      return payloads;
    }
    for (NapSubmissionArtifact link : submission.getArtifactLinkList()) {
      if (!NapSubmissionArtifactRepository.ROLE_PAYLOAD.equals(link.getRoleSelect())
          || link.getArtifact() == null
          || link.getArtifact().getMetaFile() == null) {
        continue;
      }
      try {
        payloads.add(Files.readAllBytes(MetaFiles.getPath(link.getArtifact().getMetaFile())));
      } catch (IOException e) {
        throw new AxelorException(
            e,
            TraceBackRepository.CATEGORY_INCONSISTENCY,
            I18n.get("Cannot read the submitted file to check its SAF-T version."));
      }
    }
    return payloads;
  }

  /** Днешната дата на дружеството — датата на подаването. */
  protected LocalDate today(NapSubmission submission) {
    return Beans.get(AppBaseService.class).getTodayDate(submission.getCompany());
  }

  /** Attach a НАП response / validation-report file to the attempt. */
  @Transactional(rollbackOn = Exception.class)
  public NapArtifact attachResponseFile(
      NapSubmission submission, MetaFile metaFile, String artifactType) throws IOException {
    NapArtifact artifact = artifactService.createForSubmission(metaFile, artifactType, submission);
    artifactService.link(submission, artifact, NapSubmissionArtifactRepository.ROLE_RESPONSE);
    auditService.logHuman(
        null,
        submission,
        NapAuditEventRepository.EVENT_ARTIFACT_ATTACHED,
        submission.getSubmissionStatusSelect(),
        submission.getSubmissionStatusSelect(),
        "Attached response file: " + artifact.getName());
    return artifact;
  }

  /** Record that НАП requires a correction. */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission recordCorrectionRequired(NapSubmission submission, String reason) {
    String previous = submission.getSubmissionStatusSelect();
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_CORRECTION_REQUIRED);
    submission.setCorrectionReason(reason);
    auditService.logHuman(
        null,
        submission,
        NapAuditEventRepository.EVENT_CORRECTION,
        previous,
        NapSubmissionRepository.STATUS_CORRECTION_REQUIRED,
        reason);
    return submissionRepo.save(submission);
  }
}

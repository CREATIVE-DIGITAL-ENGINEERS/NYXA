package com.axelor.apps.napsubmissions.services.declarations.common;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.napsubmissions.common.audit.NapAuditService;
import com.axelor.apps.napsubmissions.common.submission.NapProcessingResultService;
import com.axelor.apps.napsubmissions.common.submission.NapResultHtmlSanitizer;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.repo.NapAuditEventRepository;
import com.axelor.apps.napsubmissions.db.repo.NapProcessingResultRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionFileRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.i18n.I18n;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates a НАП Declarations submit/result round-trip and persists the outcome onto the {@link
 * NapSubmission} (external identifiers, status, immutable result artifact + {@code
 * NapProcessingResult}, audit). It is the declarations-specific service on top of the pure
 * transport {@link DeclarationsApiClient}; it contains NO D1/D6/ETZ business rules.
 *
 * <p>Submitting and fetching are explicit, human-initiated actions. Status is changed only here,
 * never via editable form fields. On an ambiguous submit failure it sets {@code
 * SUBMISSION_OUTCOME_UNKNOWN} and never auto-creates a second submission.
 */
@Singleton
public class DeclarationsSubmissionService {

  private static final String PARSER_VERSION = "declarations-2";

  protected final DeclarationsApiClient apiClient;
  protected final DeclarationsSubmissionMapper mapper;
  protected final DeclarationsResultDecoder decoder;
  protected final DeclarationsResultParser resultParser;
  protected final NapProcessingResultService processingResultService;
  protected final NapAuditService auditService;
  protected final NapSubmissionRepository submissionRepo;
  protected final NapSubmissionFileRepository fileRepo;
  protected final MetaFiles metaFiles;

  @Inject
  public DeclarationsSubmissionService(
      DeclarationsApiClient apiClient,
      DeclarationsSubmissionMapper mapper,
      DeclarationsResultDecoder decoder,
      DeclarationsResultParser resultParser,
      NapProcessingResultService processingResultService,
      NapAuditService auditService,
      NapSubmissionRepository submissionRepo,
      NapSubmissionFileRepository fileRepo,
      MetaFiles metaFiles) {
    this.apiClient = apiClient;
    this.mapper = mapper;
    this.decoder = decoder;
    this.resultParser = resultParser;
    this.processingResultService = processingResultService;
    this.auditService = auditService;
    this.submissionRepo = submissionRepo;
    this.fileRepo = fileRepo;
    this.metaFiles = metaFiles;
  }

  /**
   * Submit the declaration files to НАП. HTTP success means "accepted for processing" only — the
   * status is set to SUBMITTED, never ACCEPTED. The internal idempotency key is preserved
   * throughout.
   */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission submit(NapSubmission submission, DeclarationsSubmitContext context)
      throws AxelorException {
    // Ambiguous-outcome guard: once a submit outcome is unknown, НАП may already hold the request.
    // Re-POSTing risks a double filing / API_DUPLICATED_FILE. Only reconciliation (fetchResult) or
    // an
    // explicit manual resolution may move it forward.
    if (NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN.equals(
        submission.getSubmissionStatusSelect())) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(
              "This submission's outcome is unknown. Reconcile via 'fetch result' or resolve it"
                  + " manually — resubmitting is blocked to avoid a duplicate filing."));
    }
    // Duplicate-submit guard: an НАП document id (or any post-submit state) means the filing
    // already exists remotely. A second POST would be a second filing / API_DUPLICATED_FILE.
    if (hasDocumentId(submission) || alreadySubmitted(submission)) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(
              "This submission has already been sent to NAP (document id %s). Fetch its"
                  + " result instead of submitting again."),
          String.valueOf(submission.getDocumentId()));
    }
    String previous = submission.getSubmissionStatusSelect();
    submission.setChannelSelect(NapSubmissionRepository.CHANNEL_API);
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SUBMITTING);
    submission.setSubmittedOn(LocalDateTime.now());
    submission.setSubmittedBy(currentUser());
    clearError(submission);

    DeclarationsSubmitRequest request;
    try {
      request = mapper.build(submission, context);
    } catch (DeclarationsApiException e) {
      return failTechnical(submission, previous, e, "submit(build)");
    }
    // Kept for the result request, which НАП authenticates with the same certificate. Public data;
    // written before the POST so an attempt with an unknown outcome can still be looked up.
    submission.setSignerCertificateBase64(request.userSignatureBase64);

    try {
      DeclarationsSubmitResponse response = apiClient.submit(request);
      submission.setDocumentId(response.documentId);
      submission.setEntryNumber(response.entryNumber);
      submission.setEntryDate(response.entryDate);
      submission.setExternalStatus("SUBMITTED");
      submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SUBMITTED);
      submission.setResponseMeta(
          "documentId=" + response.documentId + "; entryNumber=" + response.entryNumber);
      log(
          submission,
          NapAuditEventRepository.EVENT_API_SUBMISSION,
          previous,
          NapSubmissionRepository.STATUS_SUBMITTED,
          "Accepted for processing (documentId=" + response.documentId + ")");
      return save(submission);
    } catch (DeclarationsApiException e) {
      return handleSubmitError(submission, previous, e);
    }
  }

  /**
   * Whether НАП has issued a document id for this attempt. The generated getter never returns
   * {@code null} — an unset id reads as {@code 0} — so "no id yet" must be tested for explicitly.
   */
  public static boolean hasDocumentId(NapSubmission submission) {
    Long documentId = submission.getDocumentId();
    return documentId != null && documentId != 0L;
  }

  /** Any state that can only be reached after НАП has already been asked to take the filing. */
  protected boolean alreadySubmitted(NapSubmission submission) {
    String status = submission.getSubmissionStatusSelect();
    return NapSubmissionRepository.STATUS_SUBMITTING.equals(status)
        || NapSubmissionRepository.STATUS_SUBMITTED.equals(status)
        || NapSubmissionRepository.STATUS_ACCEPTED.equals(status)
        || NapSubmissionRepository.STATUS_REJECTED.equals(status)
        || NapSubmissionRepository.STATUS_CORRECTION_REQUIRED.equals(status);
  }

  protected NapSubmission handleSubmitError(
      NapSubmission submission, String previous, DeclarationsApiException e) {
    recordError(submission, e);
    switch (e.getCategory()) {
      case UNKNOWN_OUTCOME:
        // НАП may or may not have received it — do NOT create a second submission; reconcile.
        submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN);
        audit(submission, previous, NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN, e, "submit");
        break;
      case DUPLICATE:
        submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_REJECTED);
        audit(submission, previous, NapSubmissionRepository.STATUS_REJECTED, e, "submit");
        break;
      case CONFIG:
        // Nothing left Nyxa: the API is off, the endpoint or the key is missing. Marked so the UI
        // can say "not configured" rather than blame НАП for a technical failure.
        submission.setExternalStatus("API_NOT_CONFIGURED");
        submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_TECHNICAL_ERROR);
        audit(submission, previous, NapSubmissionRepository.STATUS_TECHNICAL_ERROR, e, "submit");
        break;
      default:
        submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_TECHNICAL_ERROR);
        audit(submission, previous, NapSubmissionRepository.STATUS_TECHNICAL_ERROR, e, "submit");
    }
    return save(submission);
  }

  /**
   * Fetch the asynchronous processing result, persist the raw response as an immutable artifact +
   * {@link com.axelor.apps.napsubmissions.db.NapProcessingResult}, and apply the parsed outcome to
   * the authoritative local status. The raw bytes are preserved whatever the parser concludes; an
   * unreadable response leaves the submission submitted and flagged for review, never accepted.
   */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission fetchResult(
      NapSubmission submission, String userCertificateBase64, String userPin, String userPinType)
      throws Exception {
    String previous = submission.getSubmissionStatusSelect();
    submission.setLastStatusCheckOn(LocalDateTime.now());
    // ⚠️ ДОГОВОРЪТ ДАВА ТОЧНО ДВА КЛЮЧА ЗА ТЪРСЕНЕ, И ВТОРИЯТ Е ДВОЙКА. „Ако липсва documentId,
    // трябва да сте включили входящ номер (entryNumber) И дата (entryDate)" (Формат за API, т. 2;
    // грешка API_DOC_ID_OR_ENTRY_NUMBER_PARAMETER_ERROR). Само входящ номер, без дата, е невалидна
    // заявка — отказваме преди мрежата, вместо да получим 400 от НАП.
    if (!hasDocumentId(submission)
        && (isBlank(submission.getEntryNumber()) || isBlank(submission.getEntryDate()))) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get(
              "The result is looked up by a NAP document id OR by the incoming number +"
                  + " filing date pair. This submission has no document id, and the incoming"
                  + " number and/or the filing date are missing. Resolve the attempt"
                  + " manually."));
    }

    // Every file must carry a НАП document type: НАП's verdict is recorded per type, and a file
    // whose
    // type is unknown would silently get none. Refused before НАП is asked (review of #298).
    if (submission.getSubmissionFileList() != null) {
      for (NapSubmissionFile file : submission.getSubmissionFileList()) {
        fileDocumentType(file);
      }
    }

    DeclarationsResultRequest request = new DeclarationsResultRequest();
    request.taxpayerPin = submission.getTaxpayerPin();
    request.taxpayerPinType = pin(submission.getTaxpayerPinTypeSelect());
    // The author is the signer, supplied by the caller from the submission's recorded QES profile
    // — never the logged-in operator, who may be a colleague pressing "Обнови резултата".
    request.userPin = userPin;
    request.userPinType = pin(userPinType);
    request.userSignatureBase64 = userCertificateBase64;
    if (hasDocumentId(submission)) {
      request.documentId = submission.getDocumentId();
    } else {
      request.entryNumber = submission.getEntryNumber();
      request.entryDate = submission.getEntryDate();
    }

    try {
      DeclarationsResultResponse response = apiClient.getResult(request);
      if (response != null && notBlank(response.entryNumber)) {
        submission.setEntryNumber(response.entryNumber);
        submission.setEntryDate(response.entryDate);
      }
      DeclarationsResultDecoder.Decoded decoded = decoder.decode(response);
      if (decoded == null) {
        submission.setExternalStatus("RESULT_EMPTY");
        return save(submission);
      }

      // Parse first, store always: the raw bytes are the evidence, the parse is only a reading.
      ParsedDeclarationsResult parsed =
          resultParser.parse(decoded.rawBytes, submittedFileTypes(submission));
      String fileName =
          "nap-result-"
              + submission.getId()
              + "-attempt"
              + submission.getAttemptNumber()
              + "."
              + decoded.fileExtension;
      MetaFile rawFile = uploadRaw(decoded.rawBytes, fileName);
      // A cleaned display copy of НАП's HTML for the result page; the raw bytes above stay
      // evidence.
      String safeHtml =
          NapProcessingResultRepository.FORMAT_HTML.equals(decoded.format)
              ? NapResultHtmlSanitizer.sanitize(decoded.rawBytes)
              : null;
      persistResult(
          submission,
          rawFile,
          decoded.format,
          parsed.overallOutcome,
          parsed.summary,
          PARSER_VERSION,
          safeHtml);

      submission.setExternalStatus(parsed.needsReview ? "RESULT_NEEDS_REVIEW" : "RESULT_RECEIVED");
      applyFileOutcomes(submission, parsed);
      applyOverallOutcome(submission, previous, parsed);
      return save(submission);
    } catch (DeclarationsApiException e) {
      if (e.getCategory() == DeclarationsApiException.Category.RESULT_NOT_READY) {
        submission.setExternalStatus("RESULT_NOT_READY");
        log(
            submission,
            NapAuditEventRepository.EVENT_STATUS_CHANGE,
            previous,
            submission.getSubmissionStatusSelect(),
            "Result not ready yet");
        return save(submission);
      }
      recordError(submission, e);
      audit(submission, previous, submission.getSubmissionStatusSelect(), e, "result");
      return save(submission);
    }
  }

  /** The НАП file document types this submission actually sent, for the parser's completeness. */
  protected Set<FileDocumentType> submittedFileTypes(NapSubmission submission) {
    Set<FileDocumentType> types = EnumSet.noneOf(FileDocumentType.class);
    if (submission.getSubmissionFileList() == null) {
      return types;
    }
    for (NapSubmissionFile file : submission.getSubmissionFileList()) {
      types.add(fileDocumentType(file));
    }
    return types;
  }

  /** Record НАП's verdict per file where the response identified Д1 and Д6 separately. */
  protected void applyFileOutcomes(NapSubmission submission, ParsedDeclarationsResult parsed) {
    if (submission.getSubmissionFileList() == null || parsed.fileOutcomes.isEmpty()) {
      return;
    }
    for (NapSubmissionFile file : submission.getSubmissionFileList()) {
      String outcome = parsed.fileOutcomes.get(fileDocumentType(file));
      if (outcome == null) {
        continue;
      }
      file.setFileStatusSelect(fileStatusFor(outcome));
      saveFile(file);
    }
  }

  protected String fileStatusFor(String outcome) {
    if (NapProcessingResultRepository.OUTCOME_ACCEPTED.equals(outcome)) {
      return NapSubmissionFileRepository.FILE_STATUS_ACCEPTED;
    }
    if (NapProcessingResultRepository.OUTCOME_REJECTED.equals(outcome)
        || NapProcessingResultRepository.OUTCOME_PARTIALLY_ACCEPTED.equals(outcome)) {
      return NapSubmissionFileRepository.FILE_STATUS_REJECTED;
    }
    if (NapProcessingResultRepository.OUTCOME_PENDING.equals(outcome)) {
      return NapSubmissionFileRepository.FILE_STATUS_PENDING;
    }
    return NapSubmissionFileRepository.FILE_STATUS_UNKNOWN;
  }

  /**
   * Move the AUTHORITATIVE local status from the parsed outcome. Only an explicit acceptance of
   * every submitted file accepts; anything undecided or unreadable stays submitted so a human (or a
   * later refresh) decides — HTTP 200 alone never accepts.
   */
  protected void applyOverallOutcome(
      NapSubmission submission, String previous, ParsedDeclarationsResult parsed) {
    String outcome = parsed.overallOutcome;
    if (NapProcessingResultRepository.OUTCOME_ACCEPTED.equals(outcome)) {
      submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_ACCEPTED);
      submission.setProcessedOn(LocalDateTime.now());
      if (isBlank(submission.getNapIncomingNumber())) {
        submission.setNapIncomingNumber(submission.getEntryNumber());
      }
      log(
          submission,
          NapAuditEventRepository.EVENT_ACCEPTANCE,
          previous,
          NapSubmissionRepository.STATUS_ACCEPTED,
          parsed.summary);
      return;
    }
    if (NapProcessingResultRepository.OUTCOME_REJECTED.equals(outcome)) {
      submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_REJECTED);
      submission.setProcessedOn(LocalDateTime.now());
      submission.setErrorMessage(parsed.summary);
      log(
          submission,
          NapAuditEventRepository.EVENT_REJECTION,
          previous,
          NapSubmissionRepository.STATUS_REJECTED,
          parsed.summary);
      return;
    }
    // PENDING / UNKNOWN: the submission stays where it is; an outcome-unknown attempt that НАП
    // demonstrably received is reconciled to SUBMITTED by the identifiers in the response.
    if (NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN.equals(previous)) {
      submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SUBMITTED);
    }
    log(
        submission,
        NapAuditEventRepository.EVENT_STATUS_CHANGE,
        previous,
        submission.getSubmissionStatusSelect(),
        parsed.needsReview ? "Result could not be parsed; raw evidence stored" : parsed.summary);
  }

  /**
   * Explicit, human-authorized resolution of a {@code SUBMISSION_OUTCOME_UNKNOWN} attempt after the
   * operator has reconciled the true state (e.g. via a result query or the portal). Only allowed
   * target states are SUBMITTED, REJECTED or TECHNICAL_ERROR. The original idempotency key,
   * payload, external identifiers and prior audit trail are preserved — nothing is cleared.
   * Authorization is enforced at the action/permission layer (submission role).
   */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission manuallyResolveOutcomeUnknown(
      NapSubmission submission, String resolvedStatus, String reason) throws AxelorException {
    if (!NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN.equals(
        submission.getSubmissionStatusSelect())) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get("Manual resolution is only allowed for a submission with an unknown outcome."));
    }
    List<String> allowed =
        Arrays.asList(
            NapSubmissionRepository.STATUS_SUBMITTED,
            NapSubmissionRepository.STATUS_REJECTED,
            NapSubmissionRepository.STATUS_TECHNICAL_ERROR);
    if (resolvedStatus == null || !allowed.contains(resolvedStatus)) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          I18n.get("Resolved status must be one of: submitted, rejected, technical_error."));
    }
    if (reason == null || reason.isBlank()) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_MISSING_FIELD, I18n.get("A resolution reason is required."));
    }
    String previous = submission.getSubmissionStatusSelect();
    submission.setSubmissionStatusSelect(resolvedStatus);
    submission.setCorrectionReason(reason); // reconciliation note; payload/idempotencyKey untouched
    log(
        submission,
        NapAuditEventRepository.EVENT_STATUS_CHANGE,
        previous,
        resolvedStatus,
        "Manual outcome-unknown resolution: " + reason);
    return save(submission);
  }

  // --- helpers -------------------------------------------------------------------------------

  protected NapSubmission failTechnical(
      NapSubmission submission, String previous, DeclarationsApiException e, String op) {
    recordError(submission, e);
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_TECHNICAL_ERROR);
    audit(submission, previous, NapSubmissionRepository.STATUS_TECHNICAL_ERROR, e, op);
    return save(submission);
  }

  // --- side-effect seams (overridden in tests) --------------------------------------------------

  protected User currentUser() {
    return AuthUtils.getUser();
  }

  protected NapSubmission save(NapSubmission submission) {
    return submissionRepo.save(submission);
  }

  protected void saveFile(NapSubmissionFile file) {
    fileRepo.save(file);
  }

  protected MetaFile uploadRaw(byte[] bytes, String fileName) throws IOException {
    return metaFiles.upload(new ByteArrayInputStream(bytes), fileName);
  }

  protected void persistResult(
      NapSubmission submission,
      MetaFile rawFile,
      String format,
      String derivedOutcome,
      String summary,
      String parserVersion,
      String safeHtml)
      throws IOException {
    processingResultService.recordResult(
        submission, rawFile, format, derivedOutcome, summary, parserVersion, safeHtml);
  }

  protected void log(
      NapSubmission submission, String event, String previous, String newStatus, String message) {
    auditService.logHuman(null, submission, event, previous, newStatus, message);
  }

  protected static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  protected static boolean notBlank(String value) {
    return !isBlank(value);
  }

  protected void recordError(NapSubmission submission, DeclarationsApiException e) {
    submission.setErrorCode(e.getErrorCode());
    submission.setExceptionId(e.getExceptionId());
    submission.setErrorMessage(e.getMessage());
  }

  protected void clearError(NapSubmission submission) {
    submission.setErrorCode(null);
    submission.setExceptionId(null);
    submission.setErrorMessage(null);
  }

  protected void audit(
      NapSubmission submission,
      String previous,
      String newStatus,
      DeclarationsApiException e,
      String op) {
    log(
        submission,
        NapAuditEventRepository.EVENT_API_SUBMISSION,
        previous,
        newStatus,
        op + " " + e.getCategory() + (e.getErrorCode() == null ? "" : " / " + e.getErrorCode()));
  }

  /**
   * An identifier type. Empty stays empty (НАП then answers for itself); an UNKNOWN value is
   * refused by name — it used to be sent as null in silence.
   */
  protected PinType pin(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return PinType.valueOf(value);
    } catch (IllegalArgumentException ex) {
      throw new DeclarationsEvidenceException(
          String.format(
              I18n.get(
                  "Unknown identifier type “%s” — NAP cannot be queried for the result. The"
                      + " query was not sent."),
              value));
    }
  }

  /**
   * The НАП document type of a submitted file; a missing or unknown one is refused by name — НАП's
   * verdict for it could not be recorded.
   */
  protected FileDocumentType fileDocumentType(NapSubmissionFile file) {
    String value = file.getFileDocumentTypeSelect();
    try {
      return FileDocumentType.valueOf(value == null ? "" : value.trim());
    } catch (IllegalArgumentException ex) {
      throw new DeclarationsEvidenceException(
          String.format(
              I18n.get(
                  "Unknown file type “%s” (file “%s”) — the NAP verdict cannot be recorded"
                      + " against it. The query was not sent."),
              value == null ? "" : value,
              file.getSubmittedFileName() == null ? "—" : file.getSubmittedFileName()));
    }
  }
}

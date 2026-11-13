package com.axelor.apps.napsubmissions.common.submission;

import com.axelor.apps.napsubmissions.common.artifact.NapArtifactService;
import com.axelor.apps.napsubmissions.common.audit.NapAuditService;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapProcessingResult;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactRepository;
import com.axelor.apps.napsubmissions.db.repo.NapAuditEventRepository;
import com.axelor.apps.napsubmissions.db.repo.NapProcessingResultRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionArtifactRepository;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Persists a НАП processing result. ALWAYS stores the raw response as an immutable artifact and
 * records the interpreted outcome alongside it — never only the interpreted status. Raw HTML is
 * never rendered; the UI shows the plain-text {@code safeSummary} and the cleaned {@code safeHtml}.
 */
@Singleton
public class NapProcessingResultService {

  protected final NapProcessingResultRepository resultRepo;
  protected final NapArtifactService artifactService;
  protected final NapAuditService auditService;

  @Inject
  public NapProcessingResultService(
      NapProcessingResultRepository resultRepo,
      NapArtifactService artifactService,
      NapAuditService auditService) {
    this.resultRepo = resultRepo;
    this.artifactService = artifactService;
    this.auditService = auditService;
  }

  /**
   * Record a fetched processing result: store the raw response bytes as an immutable artifact, link
   * it to the submission, and persist the derived outcome + sanitized summary.
   */
  @Transactional(rollbackOn = Exception.class)
  public NapProcessingResult recordResult(
      NapSubmission submission,
      MetaFile rawResponseFile,
      String resultFormat,
      String derivedOutcome,
      String safeSummary,
      String parserVersion)
      throws IOException {
    return recordResult(
        submission,
        rawResponseFile,
        resultFormat,
        derivedOutcome,
        safeSummary,
        parserVersion,
        null);
  }

  /**
   * Same, with НАП's HTML result cleaned for display ({@link NapResultHtmlSanitizer}); null when
   * the result is not HTML.
   */
  @Transactional(rollbackOn = Exception.class)
  public NapProcessingResult recordResult(
      NapSubmission submission,
      MetaFile rawResponseFile,
      String resultFormat,
      String derivedOutcome,
      String safeSummary,
      String parserVersion,
      String safeHtml)
      throws IOException {
    NapArtifact rawArtifact =
        artifactService.createForSubmission(
            rawResponseFile, NapArtifactRepository.TYPE_PROCESSING_RESULT, submission);
    artifactService.link(
        submission, rawArtifact, NapSubmissionArtifactRepository.ROLE_PROCESSING_RESULT);

    NapProcessingResult result = new NapProcessingResult();
    result.setSubmission(submission);
    result.setRawResultArtifact(rawArtifact);
    result.setResultFormatSelect(resultFormat);
    result.setDerivedOutcomeSelect(derivedOutcome);
    // Defence in depth: the summary is always reduced to escaped plain text with a hard length cap.
    // Raw HTML (or any markup) never lands in safeSummary — the untouched bytes live only in the
    // immutable rawResultArtifact.
    result.setSafeSummary(toSafePlainText(safeSummary));
    result.setSafeHtml(safeHtml);
    result.setParserVersion(parserVersion);
    result.setFetchedAt(LocalDateTime.now());
    resultRepo.save(result);

    auditService.logHuman(
        null,
        submission,
        NapAuditEventRepository.EVENT_PROCESSING_RESULT,
        submission.getSubmissionStatusSelect(),
        submission.getSubmissionStatusSelect(),
        "Recorded processing result (" + resultFormat + " → " + derivedOutcome + ")");
    return result;
  }

  /** Max length of a stored summary. Raw content lives in the artifact, not here. */
  static final int MAX_SUMMARY_LENGTH = 500;

  /**
   * Reduce any input to safe, escaped plain text: strip HTML/markup tags, neutralize residual angle
   * brackets and ampersands, collapse whitespace and hard-truncate. Never returns renderable
   * markup.
   */
  static String toSafePlainText(String value) {
    if (value == null) {
      return null;
    }
    String text =
        value
            .replaceAll("<[^>]*>", " ") // drop tags
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replaceAll("\\s+", " ")
            .trim();
    if (text.length() > MAX_SUMMARY_LENGTH) {
      text = text.substring(0, MAX_SUMMARY_LENGTH - 1) + "…";
    }
    return text;
  }
}

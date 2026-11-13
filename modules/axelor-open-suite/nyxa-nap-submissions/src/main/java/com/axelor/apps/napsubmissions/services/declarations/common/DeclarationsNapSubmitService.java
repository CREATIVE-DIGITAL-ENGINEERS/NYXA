package com.axelor.apps.napsubmissions.services.declarations.common;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.napsubmissions.common.signing.TransientQesSignature;
import com.axelor.apps.napsubmissions.common.submission.NapFilingLock;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.UserQesProfile;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.i18n.I18n;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The one entry point for "send this SIGNED submission to НАП": it verifies the signing evidence,
 * builds the official {@link DeclarationsSubmitContext} from the submission's own snapshot, and
 * hands over to the existing {@link DeclarationsSubmissionService} transport orchestration.
 *
 * <p>It owns no transport and no signing of its own — it is the seam that keeps {@link
 * com.axelor.apps.napsubmissions.common.signing.QesSigningService} free of НАП and {@link
 * DeclarationsSubmissionService} free of QES.
 *
 * <p><b>THE АВТОР IS THE SIGNER, NOT THE OPERATOR.</b> Both entry points read {@code userPin} /
 * {@code userPinType} from the {@link UserQesProfile} RECORDED ON THE SUBMISSION when it was
 * signed, never from the session. A colleague may press "Подай" or "Обнови резултата"; the identity
 * НАП is given stays the person whose КЕП actually signed the files, which is the only identity
 * consistent with the certificate travelling in {@code userSignatureBase64}.
 */
@Singleton
public class DeclarationsNapSubmitService {

  protected final DeclarationsEvidenceService evidenceService;
  protected final DeclarationsSubmissionService submissionService;
  protected final NapFilingLock filingLock;

  @Inject
  public DeclarationsNapSubmitService(
      DeclarationsEvidenceService evidenceService,
      DeclarationsSubmissionService submissionService,
      NapFilingLock filingLock) {
    this.evidenceService = evidenceService;
    this.submissionService = submissionService;
    this.filingLock = filingLock;
  }

  /**
   * Verify the evidence and submit. Throws {@link DeclarationsEvidenceException} (nothing sent) or
   * returns the submission in whatever state the transport left it.
   *
   * <p>One document, one filing: a document already filed by hand on the portal, in another row of
   * the same document, is refused before anything else ({@link NapFilingLock}). The row itself,
   * once filed, is evidence ({@code NapSubmissionEvidence}, #334).
   */
  public NapSubmission submitSigned(NapSubmission submission) throws AxelorException {
    refuseLegacyPathForDeclarations(submission);
    filingLock.requireApiAllowed(submission);
    SignedSubmissionEvidence evidence = evidenceService.verify(submission);
    return submissionService.submit(submission, buildContext(submission, evidence));
  }

  /**
   * ЗАТВОРЕН резервен път за Д1/Д6. Този метод чете подпис и сертификат от ПЕРСИСТИРАНИ артефакти,
   * което противоречи на действащото решение „подписът и сертификатът не се пазят трайно“. За
   * {@code DEC_1_6} единственият допустим път е {@link #submitSignedTransient} (през „Подпиши и
   * изпрати“).
   *
   * <p>Колоните и историческите редове НЕ се пипат — затваря се само изпращането по този път.
   */
  protected void refuseLegacyPathForDeclarations(NapSubmission submission) {
    if (submission != null
        && ServiceDocumentType.DEC_1_6.name().equals(submission.getServiceDocumentTypeSelect())) {
      throw new DeclarationsEvidenceException(
          I18n.get(
              "Submitting D1/D6 with a stored signature is closed. Use “Sign and send”"
                  + " — the signature is made at the moment of sending and is not stored."));
    }
  }

  /**
   * THE REAL SUBMIT PATH: send a submission whose detached PKCS#7 and signer certificate exist ONLY
   * in memory ({@link TransientQesSignature}) and are never persisted. The evidence is verified
   * cryptographically over the exact locked bytes ({@link
   * DeclarationsEvidenceService#verifyTransient}) before anything reaches НАП; a missing signature
   * is refused there, before the network. The transient PKCS#7 travels into the request builder
   * (never a stored artifact), and nothing about the signature or certificate is written to the
   * database or a {@code MetaFile}.
   *
   * <p>On an ambiguous (unknown) outcome the underlying transport marks the submission and blocks
   * any further POST — the transient signature is gone after this call, so there is nothing to
   * blindly re-send.
   */
  public NapSubmission submitSignedTransient(
      NapSubmission submission, Map<NapSubmissionFile, TransientQesSignature> signaturesByFile)
      throws AxelorException {
    filingLock.requireApiAllowed(submission);
    SignedSubmissionEvidence evidence =
        evidenceService.verifyTransient(submission, signaturesByFile);
    DeclarationsSubmitContext context = buildContext(submission, evidence);
    context.detachedPkcs7ByFile = detachedByFile(signaturesByFile);
    return submissionService.submit(submission, context);
  }

  /** The per-file detached PKCS#7 bytes, by file instance — transient, never persisted. */
  protected Map<NapSubmissionFile, byte[]> detachedByFile(
      Map<NapSubmissionFile, TransientQesSignature> signaturesByFile) {
    Map<NapSubmissionFile, byte[]> byFile = new IdentityHashMap<>();
    if (signaturesByFile != null) {
      for (Map.Entry<NapSubmissionFile, TransientQesSignature> entry :
          signaturesByFile.entrySet()) {
        byFile.put(entry.getKey(), entry.getValue().getDetachedPkcs7());
      }
    }
    return byFile;
  }

  /**
   * Fetch the НАП processing result of an ALREADY SENT submission. The certificate comes from the
   * narrow certificate-only evidence path (which requires the НАП document id and authorises no
   * POST), and the author identity from the submission's recorded signer profile — so refreshing
   * never depends on who is logged in.
   */
  public NapSubmission fetchResult(NapSubmission submission) throws Exception {
    String certificateBase64 = evidenceService.verifyCertificateForResult(submission);
    UserQesProfile profile = recordedSignerProfile(submission);
    return submissionService.fetchResult(
        submission, certificateBase64, profile.getSignerPin(), profile.getSignerPinTypeSelect());
  }

  /**
   * The scalar transport inputs: the verified signer certificate, the signer's own IDENTIFIER and
   * identifier type read from their {@link UserQesProfile} (НАП requires {@code userPin} to
   * identify the OWNER of the certificate in {@code userSignatureBase64} — ЕГН/ЛНЧ/служебен
   * номер/ЕИК, never a device unlock code), and — for {@code DEC_1_6} — the tax period / year /
   * insurance-fund flag derived from the reporting {@link Period}.
   */
  protected DeclarationsSubmitContext buildContext(
      NapSubmission submission, SignedSubmissionEvidence evidence) {
    DeclarationsSubmitContext context = new DeclarationsSubmitContext();
    context.userCertificateBase64 = evidence.certificateBase64;

    UserQesProfile profile = recordedSignerProfile(submission);
    context.userPin = profile.getSignerPin();
    context.userPinType = profile.getSignerPinTypeSelect();

    if (!ServiceDocumentType.DEC_1_6.name().equals(submission.getServiceDocumentTypeSelect())) {
      return context;
    }
    Period period = submission.getPeriod();
    if (period == null || period.getFromDate() == null || period.getToDate() == null) {
      throw new DeclarationsEvidenceException(
          I18n.get(
              "The reporting period for a form 1/6 declaration is missing; NAP requires"
                  + " the period."));
    }
    LocalDate from = period.getFromDate();
    LocalDate to = period.getToDate();
    if (from.getYear() != to.getYear()) {
      throw new DeclarationsEvidenceException(
          I18n.get(
              "The reporting period spans two years; NAP expects a single year for form"
                  + " 1/6."));
    }
    context.taxPeriodFrom = twoDigits(from.getMonthValue());
    context.taxPeriodTo = twoDigits(to.getMonthValue());
    context.year = String.valueOf(from.getYear());
    context.insuranceFund = Boolean.TRUE.equals(submission.getFiledByInsuranceFund()) ? 1 : 0;
    return context;
  }

  /** True when the НАП submit button should be offered at all for this submission. */
  public boolean isSubmittable(NapSubmission submission) {
    return submission != null
        && NapSubmissionRepository.SERVICE_DECLARATIONS.equals(submission.getServiceCodeSelect())
        && !DeclarationsSubmissionService.hasDocumentId(submission)
        && (NapSubmissionRepository.STATUS_SIGNED.equals(submission.getSubmissionStatusSelect())
            || NapSubmissionRepository.STATUS_TECHNICAL_ERROR.equals(
                submission.getSubmissionStatusSelect()));
  }

  /**
   * The QES profile this submission was SIGNED with — the only acceptable source of the НАП author
   * identity.
   *
   * <p><b>Fails closed on a legacy row.</b> A submission signed before the profile was recorded
   * cannot prove whose КЕП is on its files, and guessing the author from the session would be
   * exactly the bug this field exists to prevent. Such a submission is refused with an instruction
   * to sign it again, which re-records the profile. The PIN is never parsed out of the certificate.
   */
  protected UserQesProfile recordedSignerProfile(NapSubmission submission) {
    UserQesProfile profile = submission == null ? null : submission.getQesSignerProfile();
    if (profile == null) {
      throw new DeclarationsEvidenceException(
          I18n.get(
              "This submission does not record whose QES signed it, so NAP cannot be"
                  + " told who the author is. Sign the submission again and retry."));
    }
    if (isBlank(profile.getSignerPin()) || isBlank(profile.getSignerPinTypeSelect())) {
      throw new DeclarationsEvidenceException(
          I18n.get(
              "The signing profile of the person who signed this submission has no"
                  + " identifier (EGN/LNCh) or no identifier type; NAP requires the author to"
                  + " be identified."));
    }
    return profile;
  }

  protected static String twoDigits(int month) {
    return month < 10 ? "0" + month : String.valueOf(month);
  }

  protected static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}

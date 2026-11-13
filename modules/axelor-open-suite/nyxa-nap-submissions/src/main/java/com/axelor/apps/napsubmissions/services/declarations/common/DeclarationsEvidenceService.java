package com.axelor.apps.napsubmissions.services.declarations.common;

import com.axelor.apps.napsubmissions.common.artifact.RegulatoryDigest;
import com.axelor.apps.napsubmissions.common.signing.DetachedPkcs7Verifier;
import com.axelor.apps.napsubmissions.common.signing.TransientQesSignature;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionFileRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.i18n.I18n;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Re-verifies, immediately before an НАП submit, that a submission really is signed and that the
 * bytes on disk are still the bytes that were signed. It reads NOTHING from the signing session and
 * trusts NO stored status on its own: every artifact is re-read and re-hashed here.
 *
 * <p>It refuses — before any network call — when:
 *
 * <ul>
 *   <li>the submission is not {@code SIGNED} (or a signed attempt retried after a technical error);
 *   <li>it has no files, or a file is not {@code COMPLETED} in QES terms (partially signed);
 *   <li>a payload / detached PKCS#7 / signer-certificate artifact is missing or not finalized;
 *   <li>the re-read payload digest differs from the digest that was submitted for signing, or from
 *       the finalized artifact's recorded digest/length;
 *   <li>a certificate's bytes no longer hash to the recorded {@code signerCertificateSha256};
 *   <li>the files do not all share one signer certificate.
 * </ul>
 *
 * <p>It signs nothing and sends nothing: it only proves the evidence, so {@link
 * DeclarationsSubmissionMapper} can build the request from bytes that are known-good.
 *
 * <p><b>TWO PATHS, AND THEY ANSWER OPPOSITE QUESTIONS.</b> {@link #verify} authorises a POST and so
 * refuses a submission that already has a НАП document id. {@link #verifyCertificateForResult}
 * authorises nothing — it serves the result query of an already-sent submission and therefore
 * REQUIRES that document id, checking only the signer certificate the query carries. Neither
 * relaxes the other: the submit gate is untouched, and the result path can never produce a {@link
 * SignedSubmissionEvidence}.
 */
@Singleton
public class DeclarationsEvidenceService {

  /**
   * Verify the full signing evidence of {@code submission} and return it. Throws {@link
   * DeclarationsEvidenceException} with a user-facing reason on the first problem found.
   */
  public SignedSubmissionEvidence verify(NapSubmission submission) {
    requireSubmittable(submission);

    List<NapSubmissionFile> files = orderedFiles(submission);
    if (files.isEmpty()) {
      throw refuse(I18n.get("The submission has no files."));
    }

    Set<FileDocumentType> documentTypes = EnumSet.noneOf(FileDocumentType.class);

    for (NapSubmissionFile file : files) {
      verifyQesCompleted(file);
      verifyPayload(file);
      verifySignature(file);
      documentTypes.add(fileDocumentType(file));
    }

    SharedCertificate certificate = verifySharedCertificate(files);
    return new SignedSubmissionEvidence(
        certificate.base64, certificate.sha256, files, documentTypes);
  }

  /**
   * THE REAL SUBMIT-TIME PATH: verify a submission whose signature and certificate exist ONLY in
   * memory ({@link TransientQesSignature}) and are never written to the database or a {@code
   * MetaFile}. Only the payload is read from storage — it is the locked declaration file; the
   * signature and certificate are handed in transiently, proven here, and discarded by the caller
   * after the one submit.
   *
   * <p>For each file this CRYPTOGRAPHICALLY verifies — not a SHA-256 of a stored artifact — that
   * the transient detached PKCS#7 is a valid signature, by the transient certificate's key, over
   * the EXACT locked payload bytes ({@link DetachedPkcs7Verifier}); it also checks the locked
   * digest and the digest the signer reports still match those bytes, and that every file shares
   * one signer certificate. Any missing signature, any changed byte, any wrong signer → refused
   * here, before the network.
   */
  public SignedSubmissionEvidence verifyTransient(
      NapSubmission submission, Map<NapSubmissionFile, TransientQesSignature> signaturesByFile) {
    requireSubmittableTransient(submission);

    List<NapSubmissionFile> files = orderedFiles(submission);
    if (files.isEmpty()) {
      throw refuse(I18n.get("The submission has no files."));
    }
    Map<NapSubmissionFile, TransientQesSignature> signatures =
        signaturesByFile == null ? Collections.emptyMap() : signaturesByFile;

    Set<FileDocumentType> documentTypes = EnumSet.noneOf(FileDocumentType.class);
    byte[] sharedCertificate = null;
    String sharedCertificateSha = null;

    for (NapSubmissionFile file : files) {
      TransientQesSignature signature = signatures.get(file);
      if (signature == null
          || signature.getDetachedPkcs7() == null
          || signature.getDetachedPkcs7().length == 0) {
        throw refuse(
            I18n.get(
                "A submission file is not signed (no signature in memory); the submission"
                    + " was not sent."));
      }
      byte[] certificate = signature.getCertificate();
      if (certificate == null || certificate.length == 0) {
        throw refuse(I18n.get("A submission file has no signer certificate."));
      }

      byte[] payloadBytes = readLockedPayload(file);
      String currentSha = RegulatoryDigest.sha256Hex(payloadBytes);
      String requested = file.getQesRequestedSha256();
      if (requested != null && !requested.isBlank() && !requested.equals(currentSha)) {
        throw refuse(
            I18n.get(
                "The data changed after locking (SHA-256 mismatch); the submission was"
                    + " refused."));
      }
      if (signature.getPayloadSha256Hex() != null
          && !signature.getPayloadSha256Hex().equals(currentSha)) {
        throw refuse(
            I18n.get(
                "The signature was produced over different data (digest mismatch); the"
                    + " submission was refused."));
      }
      // The cryptographic proof: this exact PKCS#7, this certificate's key, these exact bytes.
      if (!DetachedPkcs7Verifier.verifies(
          payloadBytes, signature.getDetachedPkcs7(), certificate)) {
        throw refuse(
            I18n.get(
                "The signature (PKCS#7) is not valid for this data and the signer's"
                    + " certificate; the submission was refused."));
      }

      String certificateSha = RegulatoryDigest.sha256Hex(certificate);
      if (sharedCertificateSha == null) {
        sharedCertificate = certificate;
        sharedCertificateSha = certificateSha;
      } else if (!sharedCertificateSha.equals(certificateSha)) {
        throw refuse(
            I18n.get(
                "The files in this submission are signed with different certificates; the"
                    + " submission was refused."));
      }
      documentTypes.add(fileDocumentType(file));
    }
    return new SignedSubmissionEvidence(
        toBase64Certificate(sharedCertificate), sharedCertificateSha, files, documentTypes);
  }

  /** Read the LOCKED payload bytes from the persisted (finalized) payload artifact. */
  protected byte[] readLockedPayload(NapSubmissionFile file) {
    NapArtifact payload = file.getPayloadArtifact();
    if (payload == null || !Boolean.TRUE.equals(payload.getFinalized())) {
      throw refuse(I18n.get("There is no finalized file (payload) to submit."));
    }
    byte[] bytes = readBytes(payload, I18n.get("the submission data"));
    if (payload.getByteLength() != null && payload.getByteLength() != bytes.length) {
      throw refuse(
          I18n.get(
              "The data length does not match the finalized file; the submission was"
                  + " refused."));
    }
    return bytes;
  }

  /**
   * THE RESULT-ONLY PATH — deliberately narrower than {@link #verify}, and never a licence to send.
   *
   * <p>НАП wants the signer's certificate again when the processing result of an ALREADY SENT
   * submission is queried. {@link #verify} cannot serve that call: it refuses, correctly, exactly
   * when a НАП document id exists, because its job is to authorise a POST. So this method asks the
   * opposite question — it REQUIRES the document id.
   *
   * <p><b>⚠️ Сертификатът НЕ се чете от базата — Nyxa не го пази трайно.</b> Подписващият го
   * предоставя ОТНОВО за тази заявка ({@code submission.resultSignerCertificatePem}, преходно
   * поле). Липсва ли — ЯСЕН ОТКАЗ: входящият номер сам по себе си НЕ е достатъчен, защото
   * контрактът на НАП иска сертификат и при заявката за резултат. Връща base64 (без PEM обвивка),
   * без да пази нищо.
   */
  public String verifyCertificateForResult(NapSubmission submission) {
    if (submission == null) {
      throw refuse(I18n.get("There is no submission."));
    }
    if (!DeclarationsSubmissionService.hasDocumentId(submission)) {
      throw refuse(
          I18n.get(
              "This submission has no NAP document number yet, so no result can be" + " fetched."));
    }
    // The certificate the submission was sent with; a pasted one only for submissions sent before
    // it was kept (or to override it).
    String provided = submission.getResultSignerCertificatePem();
    if (provided == null || provided.isBlank()) {
      provided = submission.getSignerCertificateBase64();
    }
    if (provided == null || provided.isBlank()) {
      throw refuse(
          I18n.get(
              "To check the result, NAP requires the signer's public certificate. This"
                  + " submission was sent before Nyxa kept it, so provide it once more. The"
                  + " incoming number alone is not enough."));
    }
    return normalizeSuppliedCertificate(provided);
  }

  /**
   * Нормализира въведен от човек сертификат до формата на договора: „премахване на 'начален' и
   * 'краен' ред, както и премахване на новите редове" (Формат за API, userSignatureBase64).
   *
   * <p><b>⚠️ Вече голо base64 тяло се връща БЕЗ промяна.</b> Повторното му кодиране би дало двойно
   * кодиран сертификат, който НАП отхвърля (API_INVALID_BASE64_ENCODING /
   * API_UNRECOGNIZED_CERTIFICATE_OR_USER) — затова PEM се обелва, а голото base64 се пропуска както
   * е. Съдържание, което не е нито PEM, нито base64, се отказва тук, а не в мрежата.
   */
  protected String normalizeSuppliedCertificate(String supplied) {
    String stripped = supplied.trim().replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s+", "");
    if (stripped.isEmpty()) {
      throw refuse(I18n.get("The supplied certificate is empty."));
    }
    if (!stripped.matches("[A-Za-z0-9+/]+={0,2}")) {
      throw refuse(
          I18n.get(
              "The supplied certificate is not in a valid format. Paste it as PEM"
                  + " (-----BEGIN CERTIFICATE-----…) or as base64 content."));
    }
    try {
      Base64.getDecoder().decode(stripped);
    } catch (IllegalArgumentException e) {
      throw refuse(I18n.get("The supplied certificate is not valid base64."));
    }
    return stripped;
  }

  /**
   * Re-read every file's signer certificate, prove each still hashes to its recorded fingerprint,
   * and prove the whole submission carries exactly ONE certificate. Used by the submit-path {@link
   * #verify} only (the result path no longer reads a stored certificate — see {@link
   * #verifyCertificateForResult}).
   */
  protected SharedCertificate verifySharedCertificate(List<NapSubmissionFile> files) {
    Set<String> digests = new LinkedHashSet<>();
    String base64 = null;
    for (NapSubmissionFile file : files) {
      base64 = verifyCertificate(file);
      digests.add(file.getSignerCertificateSha256());
    }
    if (digests.size() > 1) {
      throw refuse(
          I18n.get(
              "The files in this submission are signed with different certificates; the"
                  + " submission was refused."));
    }
    return new SharedCertificate(base64, digests.iterator().next());
  }

  /** The one signer certificate of a submission, verified against its recorded fingerprint. */
  protected static final class SharedCertificate {
    protected final String base64;
    protected final String sha256;

    protected SharedCertificate(String base64, String sha256) {
      this.base64 = base64;
      this.sha256 = sha256;
    }
  }

  // --- individual checks ------------------------------------------------------------------------

  /**
   * Only a SIGNED submission may be sent. A previous attempt that ended in a local technical error
   * (nothing reached НАП, no document id) may be retried — its signatures are still valid and are
   * re-verified below like any other.
   */
  protected void requireSubmittable(NapSubmission submission) {
    if (submission == null) {
      throw refuse(I18n.get("There is no submission."));
    }
    String status = submission.getSubmissionStatusSelect();
    if (DeclarationsSubmissionService.hasDocumentId(submission)) {
      throw refuse(
          I18n.get(
              "The submission has already been sent to NAP. Use “Refresh the result"
                  + " from NAP” instead of submitting again."));
    }
    if (NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN.equals(status)) {
      throw refuse(
          I18n.get(
              "Unclear outcome of the previous attempt: it is not known whether NAP"
                  + " accepted the submission. Resubmitting is blocked until the outcome is"
                  + " clarified."));
    }
    boolean signed = NapSubmissionRepository.STATUS_SIGNED.equals(status);
    boolean retryableFailure =
        NapSubmissionRepository.STATUS_TECHNICAL_ERROR.equals(status)
            && !DeclarationsSubmissionService.hasDocumentId(submission);
    if (!signed && !retryableFailure) {
      throw refuse(
          I18n.get(
              "The submission is not signed. Sign it with a QES and try again — only"
                  + " signed declarations are sent to NAP."));
    }
  }

  /**
   * The transient path signs DURING the submit action, so it legitimately starts from a LOCKED
   * submission (the selection and period are frozen; nothing is signed yet). A previous local
   * technical failure may be retried — it will simply be signed again. Everything that must still
   * refuse does: an already-filed submission (НАП document id) and an ambiguous outcome.
   */
  protected void requireSubmittableTransient(NapSubmission submission) {
    if (submission == null) {
      throw refuse(I18n.get("There is no submission."));
    }
    if (DeclarationsSubmissionService.hasDocumentId(submission)) {
      throw refuse(
          I18n.get(
              "The submission has already been sent to NAP. Use “Refresh the result"
                  + " from NAP” instead of submitting again."));
    }
    String status = submission.getSubmissionStatusSelect();
    if (NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN.equals(status)) {
      throw refuse(
          I18n.get(
              "Unclear outcome of the previous attempt: it is not known whether NAP"
                  + " accepted the submission. Resubmitting is blocked until the outcome is"
                  + " clarified."));
    }
    boolean signable =
        NapSubmissionRepository.STATUS_LOCKED.equals(status)
            || NapSubmissionRepository.STATUS_SIGNED.equals(status)
            || NapSubmissionRepository.STATUS_TECHNICAL_ERROR.equals(status);
    if (!signable) {
      throw refuse(
          I18n.get(
              "Lock the selection first (screen “File D1/D6 with NAP”) before signing"
                  + " and sending."));
    }
  }

  protected void verifyQesCompleted(NapSubmissionFile file) {
    if (!NapSubmissionFileRepository.QES_STATUS_COMPLETED.equals(
        file.getQesSigningStatusSelect())) {
      throw refuse(
          I18n.get(
              "A submission file is not signed (signing did not complete); a partially"
                  + " signed submission is not sent."));
    }
  }

  /** Re-read the payload and prove it is byte-identical to what was signed. */
  protected void verifyPayload(NapSubmissionFile file) {
    NapArtifact payload = file.getPayloadArtifact();
    if (payload == null || !Boolean.TRUE.equals(payload.getFinalized())) {
      throw refuse(I18n.get("There is no finalized file (payload) to submit."));
    }
    String requested = file.getQesRequestedSha256();
    if (requested == null || requested.isBlank()) {
      throw refuse(I18n.get("The verified fingerprint (SHA-256) of the signed data is missing."));
    }
    byte[] bytes = readBytes(payload, I18n.get("the submission data"));
    String currentSha = RegulatoryDigest.sha256Hex(bytes);
    if (!requested.equals(currentSha)
        || (payload.getSha256() != null && !payload.getSha256().equals(currentSha))) {
      throw refuse(
          I18n.get(
              "The data changed after signing (SHA-256 mismatch); the submission was"
                  + " refused."));
    }
    if (payload.getByteLength() != null && payload.getByteLength() != bytes.length) {
      throw refuse(
          I18n.get(
              "The data length does not match the finalized file; the submission was"
                  + " refused."));
    }
  }

  protected void verifySignature(NapSubmissionFile file) {
    NapArtifact signature = file.getSignatureArtifact();
    if (signature == null || !Boolean.TRUE.equals(signature.getFinalized())) {
      throw refuse(I18n.get("A submission file has no detached PKCS#7 signature."));
    }
    byte[] bytes = readBytes(signature, I18n.get("the signature"));
    if (bytes.length == 0) {
      throw refuse(I18n.get("The signature (PKCS#7) is empty; the submission was refused."));
    }
    if (signature.getSha256() != null
        && !signature.getSha256().equals(RegulatoryDigest.sha256Hex(bytes))) {
      throw refuse(I18n.get("The signature changed after signing; the submission was refused."));
    }
  }

  /** Verify the certificate snapshot and return it base64-encoded for the НАП request. */
  protected String verifyCertificate(NapSubmissionFile file) {
    NapArtifact certificate = file.getSignerCertificateArtifact();
    if (certificate == null) {
      throw refuse(I18n.get("A submission file has no signer certificate."));
    }
    String recorded = file.getSignerCertificateSha256();
    if (recorded == null || recorded.isBlank()) {
      throw refuse(I18n.get("The fingerprint of the signer's certificate is missing."));
    }
    byte[] bytes = readBytes(certificate, I18n.get("the certificate"));
    if (bytes.length == 0 || !recorded.equals(RegulatoryDigest.sha256Hex(bytes))) {
      throw refuse(I18n.get("The certificate changed after signing; the submission was refused."));
    }
    return toBase64Certificate(bytes);
  }

  protected FileDocumentType fileDocumentType(NapSubmissionFile file) {
    String value = file.getFileDocumentTypeSelect();
    if (value == null || value.isBlank()) {
      throw refuse(I18n.get("A submission file has no document type (D1/D6)."));
    }
    try {
      return FileDocumentType.valueOf(value);
    } catch (IllegalArgumentException e) {
      throw refuse(I18n.get("Unknown document type for a submission file."));
    }
  }

  // --- helpers ----------------------------------------------------------------------------------

  protected List<NapSubmissionFile> orderedFiles(NapSubmission submission) {
    List<NapSubmissionFile> files = new ArrayList<>();
    if (submission.getSubmissionFileList() != null) {
      files.addAll(submission.getSubmissionFileList());
    }
    files.sort(Comparator.comparing(f -> f.getSequence() == null ? 0 : f.getSequence()));
    return files;
  }

  /**
   * НАП expects the certificate base64-encoded with no PEM armor. A DER snapshot is encoded as is;
   * a PEM snapshot has its header/footer and newlines stripped (the inner body is already base64).
   */
  protected String toBase64Certificate(byte[] bytes) {
    String text = new String(bytes, StandardCharsets.US_ASCII);
    if (text.startsWith("-----BEGIN")) {
      return text.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s+", "");
    }
    return Base64.getEncoder().encodeToString(bytes);
  }

  /** Read the exact stored bytes of an artifact. Overridden in tests to avoid the filesystem. */
  protected byte[] readBytes(NapArtifact artifact, String what) {
    MetaFile metaFile = artifact.getMetaFile();
    if (metaFile == null) {
      throw refuse(String.format(I18n.get("There is no stored file for %s."), what));
    }
    try {
      return Files.readAllBytes(MetaFiles.getPath(metaFile));
    } catch (Exception e) {
      throw refuse(String.format(I18n.get("Cannot read %s."), what));
    }
  }

  protected DeclarationsEvidenceException refuse(String message) {
    return new DeclarationsEvidenceException(message);
  }
}

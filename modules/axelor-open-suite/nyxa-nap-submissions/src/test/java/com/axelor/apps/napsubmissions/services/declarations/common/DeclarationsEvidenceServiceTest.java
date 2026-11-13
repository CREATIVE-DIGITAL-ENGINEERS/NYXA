package com.axelor.apps.napsubmissions.services.declarations.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.napsubmissions.common.artifact.RegulatoryDigest;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionFileRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The pre-submit evidence check. Artifact bytes are served from memory through the service's {@code
 * readBytes} seam, so no filesystem and no Postgres are involved.
 */
class DeclarationsEvidenceServiceTest {

  private static final byte[] D1 = "D1-payload-bytes".getBytes(StandardCharsets.UTF_8);
  private static final byte[] D6 = "D6-payload-bytes".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PKCS7 = "pkcs7-detached".getBytes(StandardCharsets.UTF_8);
  private static final byte[] CERT = "cert-alice".getBytes(StandardCharsets.UTF_8);
  private static final byte[] OTHER_CERT = "cert-bob".getBytes(StandardCharsets.UTF_8);

  /** The contract's form: a bare base64 body with no PEM armor and no newlines. */
  private static final String CERT_BASE64 = Base64.getEncoder().encodeToString(CERT);

  private final Map<NapArtifact, byte[]> bytes = new IdentityHashMap<>();

  private final DeclarationsEvidenceService service =
      new DeclarationsEvidenceService() {
        @Override
        protected byte[] readBytes(NapArtifact artifact, String what) {
          return bytes.get(artifact);
        }
      };

  // --- fixtures ---------------------------------------------------------------------------------

  private NapArtifact artifact(byte[] content) {
    NapArtifact artifact = new NapArtifact();
    artifact.setSha256(RegulatoryDigest.sha256Hex(content));
    artifact.setByteLength((long) content.length);
    artifact.setFinalized(Boolean.TRUE);
    bytes.put(artifact, content);
    return artifact;
  }

  private NapSubmissionFile signedFile(String docType, byte[] payload, byte[] certificate) {
    NapSubmissionFile file = new NapSubmissionFile();
    file.setFileDocumentTypeSelect(docType);
    file.setPayloadArtifact(artifact(payload));
    file.setQesRequestedSha256(RegulatoryDigest.sha256Hex(payload));
    file.setSignatureArtifact(artifact(PKCS7));
    file.setSignerCertificateArtifact(artifact(certificate));
    file.setSignerCertificateSha256(RegulatoryDigest.sha256Hex(certificate));
    file.setQesSigningStatusSelect(NapSubmissionFileRepository.QES_STATUS_COMPLETED);
    return file;
  }

  private NapSubmission signedWith(NapSubmissionFile... files) {
    NapSubmission submission = new NapSubmission();
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SIGNED);
    submission.setSubmissionFileList(new ArrayList<>(Arrays.asList(files)));
    return submission;
  }

  /** The same submission after НАП accepted it for processing and gave it a document id. */
  private NapSubmission submitted(NapSubmissionFile... files) {
    NapSubmission submission = signedWith(files);
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SUBMITTED);
    submission.setDocumentId(4242L);
    return submission;
  }

  // --- tests ------------------------------------------------------------------------------------

  @Test
  void signedSubmissionYieldsTheSharedCertificate() {
    NapSubmission submission =
        signedWith(signedFile("DEC_1", D1, CERT), signedFile("DEC_6", D6, CERT));

    SignedSubmissionEvidence evidence = service.verify(submission);

    assertEquals(Base64.getEncoder().encodeToString(CERT), evidence.certificateBase64);
    assertEquals(2, evidence.files.size());
    assertEquals(2, evidence.fileDocumentTypes.size());
  }

  @Test
  void unsignedSubmissionIsRefused() {
    NapSubmission submission = signedWith(signedFile("DEC_1", D1, CERT));
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_DRAFT);

    assertThrows(DeclarationsEvidenceException.class, () -> service.verify(submission));
  }

  @Test
  void partiallySignedCombinedSubmissionIsRefused() {
    NapSubmissionFile d6 = signedFile("DEC_6", D6, CERT);
    d6.setQesSigningStatusSelect(NapSubmissionFileRepository.QES_STATUS_PENDING);

    NapSubmission submission = signedWith(signedFile("DEC_1", D1, CERT), d6);

    assertThrows(DeclarationsEvidenceException.class, () -> service.verify(submission));
  }

  @Test
  void payloadChangedAfterSigningIsRefused() {
    NapSubmissionFile file = signedFile("DEC_1", D1, CERT);
    // The bytes on disk drifted; the recorded digests still describe the signed content.
    bytes.put(file.getPayloadArtifact(), "D1-tampered".getBytes(StandardCharsets.UTF_8));

    NapSubmission submission = signedWith(file);

    assertThrows(DeclarationsEvidenceException.class, () -> service.verify(submission));
  }

  @Test
  void filesSignedWithDifferentCertificatesAreRefused() {
    NapSubmission submission =
        signedWith(signedFile("DEC_1", D1, CERT), signedFile("DEC_6", D6, OTHER_CERT));

    assertThrows(DeclarationsEvidenceException.class, () -> service.verify(submission));
  }

  @Test
  void missingSignatureArtifactIsRefused() {
    NapSubmissionFile file = signedFile("DEC_1", D1, CERT);
    file.setSignatureArtifact(null);

    assertThrows(DeclarationsEvidenceException.class, () -> service.verify(signedWith(file)));
  }

  // --- the certificate-only path used by the result query ---------------------------------------
  // ⚠️ Nyxa не пази сертификата трайно: за резултата подписващият го предоставя ОТНОВО (преходно
  // поле resultSignerCertificatePem). Само входящ номер/документен id не е достатъчен.

  /**
   * The submit gate refuses a sent submission; the result path serves it from the re-supplied cert.
   */
  @Test
  void submittedSubmissionUsesTheReSuppliedCertificateForTheResultQuery() {
    NapSubmission submission = submitted(signedFile("DEC_1", D1, CERT));
    submission.setResultSignerCertificatePem(CERT_BASE64);

    assertThrows(DeclarationsEvidenceException.class, () -> service.verify(submission));
    assertEquals(CERT_BASE64, service.verifyCertificateForResult(submission));
  }

  /**
   * ⚠️ Вече голо base64 тяло се връща БЕЗ промяна. Повторно кодиране би дало двойно кодиран
   * сертификат, който НАП отхвърля (API_INVALID_BASE64_ENCODING).
   */
  @Test
  void aPemCertificateIsStrippedAndBareBase64IsNotReEncoded() {
    NapSubmission pem = submitted(signedFile("DEC_1", D1, CERT));
    pem.setResultSignerCertificatePem(
        "-----BEGIN CERTIFICATE-----\n" + CERT_BASE64 + "\n-----END CERTIFICATE-----\n");
    assertEquals(CERT_BASE64, service.verifyCertificateForResult(pem));

    NapSubmission bare = submitted(signedFile("DEC_1", D1, CERT));
    bare.setResultSignerCertificatePem(CERT_BASE64);
    assertEquals(CERT_BASE64, service.verifyCertificateForResult(bare));
  }

  @Test
  void aCertificateThatIsNeitherPemNorBase64IsRefused() {
    NapSubmission submission = submitted(signedFile("DEC_1", D1, CERT));
    submission.setResultSignerCertificatePem("cert-alice (not base64)");

    assertThrows(
        DeclarationsEvidenceException.class, () -> service.verifyCertificateForResult(submission));
  }

  /** The certificate the submission was sent with is used; nothing has to be pasted. */
  @Test
  void theCertificateKeptAtSubmitIsUsedForTheResultQuery() {
    NapSubmission submission = submitted(signedFile("DEC_1", D1, CERT));
    submission.setSignerCertificateBase64(CERT_BASE64);

    assertEquals(CERT_BASE64, service.verifyCertificateForResult(submission));
  }

  /** A pasted certificate still wins, e.g. to correct a submission sent before it was kept. */
  @Test
  void aPastedCertificateOverridesTheKeptOne() {
    NapSubmission submission = submitted(signedFile("DEC_1", D1, CERT));
    submission.setSignerCertificateBase64("QUJD");
    submission.setResultSignerCertificatePem(CERT_BASE64);

    assertEquals(CERT_BASE64, service.verifyCertificateForResult(submission));
  }

  @Test
  void resultQueryIsRefusedWhenTheCertificateIsNotReSupplied() {
    // НАП has a document id, but the signer did not re-provide the public certificate.
    NapSubmission submission = submitted(signedFile("DEC_1", D1, CERT));

    DeclarationsEvidenceException e =
        assertThrows(
            DeclarationsEvidenceException.class,
            () -> service.verifyCertificateForResult(submission));
    assertTrue(e.getMessage().contains("certificate"), e.getMessage());
  }

  @Test
  void resultQueryIsRefusedBeforeNapHasGivenADocumentId() {
    NapSubmission submission = signedWith(signedFile("DEC_1", D1, CERT));
    submission.setResultSignerCertificatePem(CERT_BASE64);

    assertThrows(
        DeclarationsEvidenceException.class, () -> service.verifyCertificateForResult(submission));
  }
}

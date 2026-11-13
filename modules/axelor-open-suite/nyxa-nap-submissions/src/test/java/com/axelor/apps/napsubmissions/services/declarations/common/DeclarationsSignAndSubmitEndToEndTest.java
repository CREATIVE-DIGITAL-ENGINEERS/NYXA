package com.axelor.apps.napsubmissions.services.declarations.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.napsubmissions.common.artifact.RegulatoryDigest;
import com.axelor.apps.napsubmissions.common.signing.TestOnlyQesSigner;
import com.axelor.apps.napsubmissions.common.signing.TransientQesSignature;
import com.axelor.apps.napsubmissions.common.submission.NapFilingLock;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.UserQesProfile;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsApiException.Category;
import com.axelor.auth.db.User;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The REAL "подпиши и изпрати" path, end to end: the detached PKCS#7 and the signer certificate
 * live ONLY in memory ({@link TransientQesSignature}) and are NEVER persisted — the one thing both
 * the placeholder-byte fixtures and the earlier artifact-stamping test got wrong.
 *
 * <p>Only the payload (the locked declaration file) is a stored artifact. A {@link
 * TestOnlyQesSigner} (test sources only, never production) signs those exact locked bytes with a
 * genuine detached PKCS#7 and a self-signed throwaway certificate, both handed to {@link
 * DeclarationsNapSubmitService#submitSignedTransient} as transient bytes. Production verification
 * is CRYPTOGRAPHIC: the evidence service checks the signature over the exact bytes by the signer's
 * key, not a SHA-256 of a stored artifact. The test asserts the request carries that genuine
 * signature, that no signature/certificate artifact was ever written, and that the path fails
 * closed — before the network — on a missing signature or a byte changed after signing, and never
 * re-POSTs an ambiguous outcome.
 *
 * <p>No container, no database, no НАП: every persistence seam is in-memory and the transport is a
 * capturing fake. <b>This proves the wiring, not a real round-trip;</b> filing against НАП's own
 * TEST service with a real КЕП is a separate, manual step.
 */
class DeclarationsSignAndSubmitEndToEndTest {

  private static final byte[] D1 =
      ("0010010000010001" + "\r\n" + "Д1 тестови запис").getBytes(StandardCharsets.UTF_8);
  private static final byte[] D6 =
      ("0010060000060006" + "\r\n" + "Д6 тестови запис").getBytes(StandardCharsets.UTF_8);

  private final Map<NapArtifact, byte[]> payloadBytes = new IdentityHashMap<>();
  private final Map<NapSubmissionFile, TransientQesSignature> signatures = new IdentityHashMap<>();
  private final TestOnlyQesSigner signer = new TestOnlyQesSigner("ТЕСТ ПОДПИСВАЩ");

  /** Captures the exact request that reaches the transport and replays a scripted outcome. */
  private static final class CapturingApiClient implements DeclarationsApiClient {
    DeclarationsSubmitRequest sent;
    Category failWith;

    @Override
    public DeclarationsSubmitResponse submit(DeclarationsSubmitRequest request) {
      sent = request;
      if (failWith != null) {
        throw new DeclarationsApiException(failWith, "submit", "scripted failure", null);
      }
      DeclarationsSubmitResponse response = new DeclarationsSubmitResponse();
      response.documentId = 778899L;
      response.entryNumber = "30E000000777";
      response.entryDate = "2026-04-15";
      return response;
    }

    @Override
    public DeclarationsResultResponse getResult(DeclarationsResultRequest request) {
      throw new UnsupportedOperationException("not exercised here");
    }
  }

  private final CapturingApiClient apiClient = new CapturingApiClient();

  // --- service under test, in-memory seams ------------------------------------------------------

  private DeclarationsNapSubmitService service() {
    DeclarationsEvidenceService evidence =
        new DeclarationsEvidenceService() {
          @Override
          protected byte[] readBytes(NapArtifact artifact, String what) {
            return payloadBytes.get(artifact);
          }
        };
    DeclarationsSubmissionMapper mapper =
        new DeclarationsSubmissionMapper() {
          @Override
          protected byte[] readBytes(NapArtifact artifact) {
            return payloadBytes.get(artifact);
          }
        };
    DeclarationsSubmissionService submissionService =
        new DeclarationsSubmissionService(
            apiClient,
            mapper,
            new DeclarationsResultDecoder(),
            new DeclarationsResultParser(),
            null,
            null,
            null,
            null,
            null) {
          @Override
          protected User currentUser() {
            return null;
          }

          @Override
          protected NapSubmission save(NapSubmission submission) {
            return submission;
          }

          @Override
          protected void log(NapSubmission s, String e, String p, String n, String m) {
            // audit covered by its own test
          }
        };
    NapFilingLock lock =
        new NapFilingLock(null) {
          @Override
          protected NapSubmission filedSibling(NapSubmission submission) {
            return null;
          }
        };
    return new DeclarationsNapSubmitService(evidence, submissionService, lock);
  }

  // --- the locked payload (persisted) and the TRANSIENT signature (in memory only) --------------

  private NapArtifact lockedPayload(byte[] content) {
    NapArtifact artifact = new NapArtifact();
    artifact.setSha256(RegulatoryDigest.sha256Hex(content));
    artifact.setByteLength((long) content.length);
    artifact.setFinalized(Boolean.TRUE);
    payloadBytes.put(artifact, content);
    return artifact;
  }

  /** Lock the payload, sign it for real in memory, and register the transient signature. */
  private NapSubmissionFile signInMemory(String docType, byte[] payload, int sequence) {
    NapSubmissionFile file = new NapSubmissionFile();
    file.setSequence(sequence);
    file.setSubmittedFileName(docType + ".txt");
    file.setFileDocumentTypeSelect(docType);
    file.setFileTypeSelect("txt");
    file.setNumRecords(1);
    file.setPayloadArtifact(lockedPayload(payload));
    file.setQesRequestedSha256(RegulatoryDigest.sha256Hex(payload));
    // ⚠️ Deliberately NO signatureArtifact and NO signerCertificateArtifact: nothing persisted.

    byte[] pkcs7 = signer.detachedPkcs7(payload);
    signatures.put(
        file,
        new TransientQesSignature(
            pkcs7, signer.certificateDer(), RegulatoryDigest.sha256Hex(payload)));
    return file;
  }

  private NapSubmission signedSubmission(NapSubmissionFile... files) {
    Period period = new Period();
    period.setFromDate(LocalDate.of(2026, 4, 1));
    period.setToDate(LocalDate.of(2026, 4, 30));

    UserQesProfile profile = new UserQesProfile();
    profile.setEnabled(Boolean.TRUE);
    profile.setSignerPin("7501011234");
    profile.setSignerPinTypeSelect(PinType.IND_EGN.name());

    NapSubmission submission = new NapSubmission();
    submission.setServiceCodeSelect(NapSubmissionRepository.SERVICE_DECLARATIONS);
    submission.setServiceDocumentTypeSelect(ServiceDocumentType.DEC_1_6.name());
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SIGNED);
    submission.setPeriod(period);
    submission.setTaxpayerPin("831234567");
    submission.setTaxpayerPinTypeSelect(PinType.BUS_BULSTAT.name());
    submission.setQesSignerProfile(profile);
    submission.setFiledByInsuranceFund(Boolean.FALSE);
    submission.setSubmissionFileList(new ArrayList<>(Arrays.asList(files)));
    return submission;
  }

  // --- tests ------------------------------------------------------------------------------------

  @Test
  void combinedD1AndD6SubmitCarriesAGenuineDetachedPkcs7AndPersistsNoSignature()
      throws AxelorException {
    NapSubmissionFile d1 = signInMemory("DEC_1", D1, 0);
    NapSubmissionFile d6 = signInMemory("DEC_6", D6, 1);
    NapSubmission submission = signedSubmission(d1, d6);

    NapSubmission sent = service().submitSignedTransient(submission, signatures);

    assertEquals(NapSubmissionRepository.STATUS_SUBMITTED, sent.getSubmissionStatusSelect());
    assertEquals(Long.valueOf(778899L), sent.getDocumentId());
    assertEquals("30E000000777", sent.getEntryNumber());

    DeclarationsSubmitRequest request = apiClient.sent;
    assertEquals(2, request.files.size());
    assertEquals(
        Base64.getEncoder().encodeToString(signer.certificateDer()), request.userSignatureBase64);
    assertGenuineSignature(request.files.get(0), D1);
    assertGenuineSignature(request.files.get(1), D6);

    // Nothing about the signature or the certificate was ever persisted on the files.
    assertNull(d1.getSignatureArtifact());
    assertNull(d1.getSignerCertificateArtifact());
    assertNull(d6.getSignatureArtifact());
    assertNull(d6.getSignerCertificateArtifact());
  }

  private void assertGenuineSignature(DeclarationsFile dto, byte[] payload) {
    byte[] pkcs7 = Base64.getDecoder().decode(dto.base64EncodedPkcs7);
    assertTrue(
        signer.verifyDetached(payload, pkcs7),
        "base64EncodedPkcs7 must be a genuine detached signature over the file payload");
    assertEquals(Base64.getEncoder().encodeToString(payload), dto.fileContentBase64);
  }

  @Test
  void missingTransientSignatureIsRefusedBeforeTheNetwork() {
    NapSubmissionFile file = signInMemory("DEC_1", D1, 0);
    signatures.remove(file); // the in-memory signature is simply not there
    NapSubmission submission = signedSubmission(file);

    assertThrows(
        DeclarationsEvidenceException.class,
        () -> service().submitSignedTransient(submission, signatures));
    assertNull(apiClient.sent);
  }

  @Test
  void payloadChangedAfterSigningFailsCryptographicVerificationBeforeTheNetwork() {
    NapSubmissionFile file = signInMemory("DEC_1", D1, 0);
    // Tamper the LOCKED payload bytes after signing: the detached PKCS#7 no longer verifies over
    // them, so the cryptographic gate must refuse before any НАП call.
    payloadBytes.put(file.getPayloadArtifact(), "tampered".getBytes(StandardCharsets.UTF_8));
    NapSubmission submission = signedSubmission(file);

    assertThrows(
        DeclarationsEvidenceException.class,
        () -> service().submitSignedTransient(submission, signatures));
    assertNull(apiClient.sent);
  }

  @Test
  void ambiguousOutcomeIsNeverReSubmitted() throws AxelorException {
    apiClient.failWith = Category.UNKNOWN_OUTCOME;
    NapSubmissionFile file = signInMemory("DEC_1", D1, 0);
    NapSubmission submission = signedSubmission(file);
    DeclarationsNapSubmitService service = service();

    NapSubmission after = service.submitSignedTransient(submission, signatures);
    assertEquals(NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN, after.getSubmissionStatusSelect());

    // A second attempt is refused before the transport — no blind re-POST of an attempt НАП may
    // already hold (and in the real path the transient signature is gone anyway).
    assertThrows(
        DeclarationsEvidenceException.class,
        () -> service.submitSignedTransient(submission, signatures));
  }
}

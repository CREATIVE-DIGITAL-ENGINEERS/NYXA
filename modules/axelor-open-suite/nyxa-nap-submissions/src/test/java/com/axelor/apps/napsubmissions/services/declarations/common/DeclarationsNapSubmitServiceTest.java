package com.axelor.apps.napsubmissions.services.declarations.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import com.axelor.meta.db.MetaFile;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The signed-submission → НАП submit path down to the exact {@link DeclarationsSubmitRequest} that
 * reaches the client, exercised on the TRANSIENT path — the only path open for Д1/Д6. The signature
 * and certificate are produced in memory by the TEST-ONLY signer and never persisted; the transport
 * is a capturing fake and every persistence seam is in-memory: no container, no database, no НАП.
 *
 * <p>Its distinctive subject is the AUTHOR IDENTITY rule: НАП must always be told the identity of
 * the person whose КЕП signed, read from the submission's recorded {@code UserQesProfile} — never
 * from the session and never from a stale snapshot on the row.
 */
class DeclarationsNapSubmitServiceTest {

  private static final byte[] D1 = "D1-payload".getBytes(StandardCharsets.UTF_8);
  private static final byte[] D6 = "D6-payload".getBytes(StandardCharsets.UTF_8);

  private final Map<NapArtifact, byte[]> bytes = new IdentityHashMap<>();
  private final Map<NapSubmissionFile, TransientQesSignature> signatures = new IdentityHashMap<>();
  private final TestOnlyQesSigner signer = new TestOnlyQesSigner("ALICE");

  /** Captures what was sent and replays a scripted outcome. */
  private static final class FakeApiClient implements DeclarationsApiClient {
    final java.util.List<DeclarationsSubmitRequest> sent = new ArrayList<>();
    Category failWith;
    long nextDocumentId = 4242L;

    @Override
    public DeclarationsSubmitResponse submit(DeclarationsSubmitRequest request) {
      sent.add(request);
      if (failWith != null) {
        throw new DeclarationsApiException(failWith, "submit", "scripted failure", null);
      }
      DeclarationsSubmitResponse response = new DeclarationsSubmitResponse();
      response.documentId = nextDocumentId;
      response.entryNumber = "30E000000001";
      response.entryDate = "2026-04-15";
      return response;
    }

    final java.util.List<DeclarationsResultRequest> resultQueries = new ArrayList<>();

    @Override
    public DeclarationsResultResponse getResult(DeclarationsResultRequest request) {
      resultQueries.add(request);
      DeclarationsResultResponse response = new DeclarationsResultResponse();
      response.entryNumber = "30E000000001";
      response.entryDate = "2026-04-15";
      response.base64FullHtmlResultInfo =
          Base64.getEncoder().encodeToString(ACCEPTED_D1.getBytes(StandardCharsets.UTF_8));
      return response;
    }
  }

  private static final String ACCEPTED_D1 =
      "<table><tr><td> Брой подадени Декларации обр.1: 1 Брой приети Декларации обр.1:1"
          + " Брой отхвърлени Декларации обр.1:0 </td></tr></table>";

  private final FakeApiClient apiClient = new FakeApiClient();

  // --- the service under test, with in-memory seams ---------------------------------------------

  private DeclarationsNapSubmitService service() {
    DeclarationsEvidenceService evidence =
        new DeclarationsEvidenceService() {
          @Override
          protected byte[] readBytes(NapArtifact artifact, String what) {
            return bytes.get(artifact);
          }
        };
    DeclarationsSubmissionMapper mapper =
        new DeclarationsSubmissionMapper() {
          @Override
          protected byte[] readBytes(NapArtifact artifact) {
            return bytes.get(artifact);
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
          protected void saveFile(NapSubmissionFile file) {
            // in-memory seam
          }

          @Override
          protected MetaFile uploadRaw(byte[] raw, String fileName) {
            return new MetaFile();
          }

          @Override
          protected void persistResult(
              NapSubmission submission,
              MetaFile rawFile,
              String format,
              String derivedOutcome,
              String summary,
              String parserVersion,
              String safeHtml) {
            // the НАП result row is exercised by DeclarationsFetchResultTest
          }

          @Override
          protected void log(
              NapSubmission submission,
              String event,
              String previous,
              String newStatus,
              String message) {
            // audit is exercised by its own test; here it is a no-op seam
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

  private NapSubmission submit(NapSubmission submission) throws AxelorException {
    return service().submitSignedTransient(submission, signatures);
  }

  /** Alice signs. Her profile is the ONLY source of the НАП {@code userPin}. */
  private final UserQesProfile alice = qesProfile("7501011234", PinType.IND_EGN.name());

  private static UserQesProfile qesProfile(String pin, String pinType) {
    UserQesProfile profile = new UserQesProfile();
    profile.setEnabled(Boolean.TRUE);
    profile.setSignerPin(pin);
    profile.setSignerPinTypeSelect(pinType);
    return profile;
  }

  // --- fixtures ---------------------------------------------------------------------------------

  private NapArtifact artifact(byte[] content) {
    NapArtifact artifact = new NapArtifact();
    artifact.setSha256(RegulatoryDigest.sha256Hex(content));
    artifact.setByteLength((long) content.length);
    artifact.setFinalized(Boolean.TRUE);
    bytes.put(artifact, content);
    return artifact;
  }

  /** A locked file plus its TRANSIENT, genuinely produced signature (never persisted). */
  private NapSubmissionFile file(String docType, byte[] payload, int sequence) {
    NapSubmissionFile file = new NapSubmissionFile();
    file.setSequence(sequence);
    file.setSubmittedFileName(docType + ".txt");
    file.setFileDocumentTypeSelect(docType);
    file.setFileTypeSelect("txt");
    file.setNumRecords(1);
    file.setPayloadArtifact(artifact(payload));
    file.setQesRequestedSha256(RegulatoryDigest.sha256Hex(payload));
    signatures.put(
        file,
        new TransientQesSignature(
            signer.detachedPkcs7(payload),
            signer.certificateDer(),
            RegulatoryDigest.sha256Hex(payload)));
    return file;
  }

  private NapSubmission signed(NapSubmissionFile... files) {
    Period period = new Period();
    period.setFromDate(LocalDate.of(2026, 4, 1));
    period.setToDate(LocalDate.of(2026, 4, 30));

    NapSubmission submission = new NapSubmission();
    submission.setServiceCodeSelect(NapSubmissionRepository.SERVICE_DECLARATIONS);
    submission.setServiceDocumentTypeSelect(ServiceDocumentType.DEC_1_6.name());
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SIGNED);
    submission.setPeriod(period);
    submission.setTaxpayerPin("831234567");
    submission.setTaxpayerPinTypeSelect(PinType.BUS_BULSTAT.name());
    // ⚠️ submitterPin is deliberately NOT set: the signer's PIN lives (encrypted) on the signer's
    // UserQesProfile, which the submission REFERENCES; it is never duplicated onto the row.
    submission.setQesSignerProfile(alice);
    submission.setFiledByInsuranceFund(Boolean.FALSE);
    submission.setSubmissionFileList(new ArrayList<>(Arrays.asList(files)));
    return submission;
  }

  // --- tests ------------------------------------------------------------------------------------

  @Test
  void d1OnlySignedSubmissionSendsTheExpectedRequest() throws AxelorException {
    NapSubmission submission = signed(file("DEC_1", D1, 0));

    NapSubmission sent = submit(submission);

    assertEquals(1, apiClient.sent.size());
    DeclarationsSubmitRequest request = apiClient.sent.get(0);
    assertEquals(ServiceDocumentType.DEC_1_6, request.serviceDocumentType);
    assertEquals("831234567", request.taxpayerPin);
    assertEquals(PinType.BUS_BULSTAT, request.taxpayerPinType);
    assertEquals("7501011234", request.userPin);
    assertEquals(PinType.IND_EGN, request.userPinType);
    assertEquals(
        Base64.getEncoder().encodeToString(signer.certificateDer()), request.userSignatureBase64);
    assertEquals("04", request.taxPeriodFrom);
    assertEquals("04", request.taxPeriodTo);
    assertEquals("2026", request.year);
    assertEquals(Integer.valueOf(0), request.insuranceFund);

    assertEquals(1, request.files.size());
    DeclarationsFile d1 = request.files.get(0);
    assertEquals(FileDocumentType.DEC_1, d1.fileDocumentType);
    assertEquals(D1.length, d1.fileSize);
    assertEquals(Base64.getEncoder().encodeToString(D1), d1.fileContentBase64);
    assertTrue(signer.verifyDetached(D1, Base64.getDecoder().decode(d1.base64EncodedPkcs7)));

    assertEquals(NapSubmissionRepository.STATUS_SUBMITTED, sent.getSubmissionStatusSelect());
    assertEquals(Long.valueOf(4242L), sent.getDocumentId());
    assertEquals("30E000000001", sent.getEntryNumber());
  }

  @Test
  void combinedSubmissionSendsExactlyTwoCorrectlyTypedFiles() throws AxelorException {
    NapSubmission submission = signed(file("DEC_1", D1, 0), file("DEC_6", D6, 1));

    submit(submission);

    DeclarationsSubmitRequest request = apiClient.sent.get(0);
    assertEquals(2, request.files.size());
    assertEquals(FileDocumentType.DEC_1, request.files.get(0).fileDocumentType);
    assertEquals(FileDocumentType.DEC_6, request.files.get(1).fileDocumentType);
    assertTrue(
        signer.verifyDetached(
            D6, Base64.getDecoder().decode(request.files.get(1).base64EncodedPkcs7)));
  }

  @Test
  void secondSubmitDoesNotCreateADuplicateRemoteSubmission() throws AxelorException {
    NapSubmission submission = signed(file("DEC_1", D1, 0));

    submit(submission);
    assertEquals(Long.valueOf(4242L), submission.getDocumentId());

    // Refused by the evidence gate on the НАП document id, before any second POST is built.
    assertThrows(DeclarationsEvidenceException.class, () -> submit(submission));
    assertEquals(1, apiClient.sent.size());
  }

  @Test
  void ambiguousTimeoutBlocksAnyFurtherSubmit() throws AxelorException {
    apiClient.failWith = Category.UNKNOWN_OUTCOME;
    NapSubmission submission = signed(file("DEC_1", D1, 0));

    NapSubmission after = submit(submission);
    assertEquals(NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN, after.getSubmissionStatusSelect());

    // Evidence still verifies, but the gate refuses to re-POST an attempt НАП may hold.
    assertThrows(DeclarationsEvidenceException.class, () -> submit(submission));
    assertEquals(1, apiClient.sent.size());
  }

  /**
   * The НАП contract: {@code userSignatureBase64} is the SIGNER's public certificate (bare base64,
   * no PEM armor) and {@code base64EncodedPkcs7} is the detached PKCS#7 of that one file — never
   * the other way round. The accompanying {@code userPin} identifies the owner of that certificate
   * and is read from the signer's QES profile, not from a stale snapshot on the submission row.
   */
  @Test
  void certificateAndSignatureKeepTheirDocumentedEncodingsAndSides() throws AxelorException {
    NapSubmission submission = signed(file("DEC_1", D1, 0));
    // A stale snapshot on the row must NOT win over the signer's own profile.
    submission.setSubmitterPin("9999999999");
    submission.setSubmitterPinTypeSelect(PinType.IND_LNCH.name());

    submit(submission);

    DeclarationsSubmitRequest request = apiClient.sent.get(0);
    String certificateBase64 = Base64.getEncoder().encodeToString(signer.certificateDer());
    assertEquals(certificateBase64, request.userSignatureBase64);
    assertEquals("7501011234", request.userPin);
    assertEquals(PinType.IND_EGN, request.userPinType);
    // The two are distinct: the PKCS#7 never carries the certificate bytes, nor the reverse.
    assertNotEquals(certificateBase64, request.files.get(0).base64EncodedPkcs7);
  }

  /**
   * Alice signed; Bob presses "Подай". НАП must be told Alice is the author, because Alice's
   * certificate is what travels in {@code userSignatureBase64} — Bob's session is irrelevant, and
   * nothing in this test supplies a logged-in user at all.
   */
  @Test
  void anotherUserMaySubmitButTheAuthorStaysTheSigner() throws AxelorException {
    NapSubmission submission = signed(file("DEC_1", D1, 0));
    submission.setQesSignerProfile(qesProfile("6803125566", PinType.IND_EGN.name()));

    submit(submission);

    DeclarationsSubmitRequest request = apiClient.sent.get(0);
    assertEquals("6803125566", request.userPin);
    assertEquals(PinType.IND_EGN, request.userPinType);
  }

  /** The same rule on the way back: the result query carries the SIGNER's identity, not Bob's. */
  @Test
  void anotherUserMayRefreshButTheResultQueryCarriesTheSignersIdentity() throws Exception {
    NapSubmission submission = signed(file("DEC_1", D1, 0));
    submission.setQesSignerProfile(qesProfile("6803125566", PinType.IND_EGN.name()));
    DeclarationsNapSubmitService service = service();
    service.submitSignedTransient(submission, signatures);

    // The submit gate now refuses this submission (it has a НАП document id) — the result path
    // must still work. ⚠️ Nyxa does not store the certificate, so the signer RE-SUPPLIES it
    // (transient field) for the result query, in the contract's bare-base64 form.
    submission.setResultSignerCertificatePem(
        Base64.getEncoder().encodeToString(signer.certificateDer()));
    NapSubmission refreshed = service.fetchResult(submission);

    assertEquals(1, apiClient.resultQueries.size());
    DeclarationsResultRequest query = apiClient.resultQueries.get(0);
    assertEquals("6803125566", query.userPin);
    assertEquals(PinType.IND_EGN, query.userPinType);
    assertEquals(
        Base64.getEncoder().encodeToString(signer.certificateDer()), query.userSignatureBase64);
    assertEquals(Long.valueOf(4242L), query.documentId);
    assertEquals(NapSubmissionRepository.STATUS_ACCEPTED, refreshed.getSubmissionStatusSelect());
  }

  /**
   * A submission that cannot name its author is refused: guessing it from the session is the bug
   * the recorded signer profile exists to prevent.
   */
  @Test
  void aSignedSubmissionWithNoRecordedSignerProfileIsRefused() {
    NapSubmission submission = signed(file("DEC_1", D1, 0));
    submission.setQesSignerProfile(null);

    assertThrows(DeclarationsEvidenceException.class, () -> submit(submission));
    assertTrue(apiClient.sent.isEmpty());
  }

  /** The legacy stored-signature path is CLOSED for Д1/Д6: it may not reach the transport. */
  @Test
  void theLegacyStoredSignaturePathIsClosedForDeclarations() {
    NapSubmission submission = signed(file("DEC_1", D1, 0));
    DeclarationsNapSubmitService service = service();

    assertThrows(DeclarationsEvidenceException.class, () -> service.submitSigned(submission));
    assertTrue(apiClient.sent.isEmpty());
  }

  @Test
  void aSubmissionThatIsNotLockedYetNeverReachesTheTransport() {
    NapSubmission submission = signed(file("DEC_1", D1, 0));
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_DRAFT);

    assertThrows(DeclarationsEvidenceException.class, () -> submit(submission));
    assertTrue(apiClient.sent.isEmpty());
  }
}

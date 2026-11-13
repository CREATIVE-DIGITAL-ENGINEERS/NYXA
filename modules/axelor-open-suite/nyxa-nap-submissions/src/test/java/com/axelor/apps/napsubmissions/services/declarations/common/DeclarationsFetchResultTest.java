package com.axelor.apps.napsubmissions.services.declarations.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.repo.NapProcessingResultRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionFileRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.meta.db.MetaFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Result retrieval end to end at the service level: the raw НАП bytes are always preserved, and the
 * authoritative local status follows the parsed outcome — accepted only on an explicit acceptance
 * of every submitted file, never on HTTP 200 alone.
 */
class DeclarationsFetchResultTest {

  private static final String ACCEPTED_D1 =
      "<table><tr><td> Брой подадени Декларации обр.1: 1 Брой приети Декларации обр.1:1"
          + " Брой отхвърлени Декларации обр.1:0 </td></tr></table>";

  private static final String D6_REJECTED =
      "<table><tr><td>Брой подадени Декларации обр.1: 1 Брой приети Декларации обр.1:1"
          + " Брой отхвърлени Декларации обр.1:0</td></tr>"
          + "<tr><td>Брой подадени Декларации обр.6:1 Брой приети Декларации обр.6:0"
          + " Брой отхвърлени Декларации обр.6:1</td></tr></table>";

  private static final String UNREADABLE = "<html><body><p>§§§</p></body></html>";

  /** What the service persisted for the attempt. */
  private byte[] storedRawBytes;

  private String storedOutcome;

  private String storedSafeHtml;

  /** How many times НАП was asked for a result. */
  private int resultQueries;

  private DeclarationsSubmissionService service(String html) {
    DeclarationsApiClient client =
        new DeclarationsApiClient() {
          @Override
          public DeclarationsSubmitResponse submit(DeclarationsSubmitRequest request) {
            throw new UnsupportedOperationException("not used in this test");
          }

          @Override
          public DeclarationsResultResponse getResult(DeclarationsResultRequest request) {
            resultQueries++;
            DeclarationsResultResponse response = new DeclarationsResultResponse();
            response.entryNumber = "30E000000001";
            response.entryDate = "2026-04-15";
            response.base64FullHtmlResultInfo =
                Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));
            return response;
          }
        };
    return new DeclarationsSubmissionService(
        client,
        null,
        new DeclarationsResultDecoder(),
        new DeclarationsResultParser(),
        null,
        null,
        null,
        null,
        null) {
      @Override
      protected NapSubmission save(NapSubmission submission) {
        return submission;
      }

      @Override
      protected void saveFile(NapSubmissionFile file) {
        // in-memory: the file entity itself carries the assertion
      }

      @Override
      protected MetaFile uploadRaw(byte[] raw, String fileName) {
        storedRawBytes = raw;
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
        storedOutcome = derivedOutcome;
        storedSafeHtml = safeHtml;
      }

      @Override
      protected void log(
          NapSubmission submission,
          String event,
          String previous,
          String newStatus,
          String message) {
        // no-op seam
      }
    };
  }

  private NapSubmissionFile file(String docType) {
    NapSubmissionFile file = new NapSubmissionFile();
    file.setFileDocumentTypeSelect(docType);
    file.setFileStatusSelect(NapSubmissionFileRepository.FILE_STATUS_PENDING);
    return file;
  }

  private NapSubmission submitted(NapSubmissionFile... files) {
    NapSubmission submission = new NapSubmission();
    submission.setServiceCodeSelect(NapSubmissionRepository.SERVICE_DECLARATIONS);
    submission.setServiceDocumentTypeSelect(ServiceDocumentType.DEC_1_6.name());
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SUBMITTED);
    submission.setDocumentId(4242L);
    submission.setAttemptNumber(1);
    submission.setSubmissionFileList(new ArrayList<>(Arrays.asList(files)));
    return submission;
  }

  // --- tests ------------------------------------------------------------------------------------

  @Test
  void acceptedD1OnlyResultBecomesAccepted() throws Exception {
    NapSubmissionFile d1 = file("DEC_1");
    NapSubmission submission = submitted(d1);

    service(ACCEPTED_D1).fetchResult(submission, "cert", "7501011234", "IND_EGN");

    assertEquals(NapSubmissionRepository.STATUS_ACCEPTED, submission.getSubmissionStatusSelect());
    assertEquals(NapProcessingResultRepository.OUTCOME_ACCEPTED, storedOutcome);
    assertEquals(NapSubmissionFileRepository.FILE_STATUS_ACCEPTED, d1.getFileStatusSelect());
    assertEquals("30E000000001", submission.getEntryNumber());
    // The HTML result is kept as a cleaned display copy, its table intact.
    assertTrue(storedSafeHtml.contains("<table>"), storedSafeHtml);
    assertTrue(storedSafeHtml.contains("Брой приети Декларации обр.1"), storedSafeHtml);
  }

  @Test
  void combinedResultWithOneRejectedFileBecomesRejected() throws Exception {
    NapSubmissionFile d1 = file("DEC_1");
    NapSubmissionFile d6 = file("DEC_6");

    NapSubmission submission = submitted(d1, d6);
    service(D6_REJECTED).fetchResult(submission, "cert", "7501011234", "IND_EGN");

    assertEquals(NapSubmissionRepository.STATUS_REJECTED, submission.getSubmissionStatusSelect());
    assertEquals(NapSubmissionFileRepository.FILE_STATUS_ACCEPTED, d1.getFileStatusSelect());
    assertEquals(NapSubmissionFileRepository.FILE_STATUS_REJECTED, d6.getFileStatusSelect());
  }

  /** Д6 was submitted but the response says nothing about it — still submitted, never accepted. */
  @Test
  void processingResultRemainsSubmitted() throws Exception {
    NapSubmission submission = submitted(file("DEC_1"), file("DEC_6"));

    service(ACCEPTED_D1).fetchResult(submission, "cert", "7501011234", "IND_EGN");

    assertEquals(NapSubmissionRepository.STATUS_SUBMITTED, submission.getSubmissionStatusSelect());
    assertEquals(NapProcessingResultRepository.OUTCOME_PENDING, storedOutcome);
  }

  // --- unknown values are refused loudly, before НАП is asked (review of #298) -----------------

  /**
   * A file whose stored document type is not a НАП type cannot carry НАП's verdict. It used to be
   * skipped in silence, so the verdict for it was never recorded; now nothing is queried and the
   * refusal names the value.
   */
  @Test
  void anUnknownFileDocumentTypeIsRefusedLoudly() {
    NapSubmissionFile d1 = file("DEC_1");
    NapSubmissionFile odd = file("DEC_7");
    NapSubmission submission = submitted(d1, odd);

    DeclarationsEvidenceException e =
        assertThrows(
            DeclarationsEvidenceException.class,
            () -> service(ACCEPTED_D1).fetchResult(submission, "cert", "7501011234", "IND_EGN"));
    assertTrue(e.getMessage().contains("DEC_7"), e.getMessage());
    assertEquals(0, resultQueries, "НАП is not asked");
    assertEquals(NapSubmissionRepository.STATUS_SUBMITTED, submission.getSubmissionStatusSelect());
    assertEquals(NapSubmissionFileRepository.FILE_STATUS_PENDING, d1.getFileStatusSelect());
  }

  /** An unknown submitter identifier type used to be sent as null. */
  @Test
  void anUnknownUserPinTypeIsRefusedLoudly() {
    NapSubmission submission = submitted(file("DEC_1"));

    DeclarationsEvidenceException e =
        assertThrows(
            DeclarationsEvidenceException.class,
            () -> service(ACCEPTED_D1).fetchResult(submission, "cert", "7501011234", "IND_XYZ"));
    assertTrue(e.getMessage().contains("IND_XYZ"), e.getMessage());
    assertEquals(0, resultQueries, "НАП is not asked");
  }

  /** The same for the taxpayer's identifier type stored on the submission. */
  @Test
  void anUnknownTaxpayerPinTypeIsRefusedLoudly() {
    NapSubmission submission = submitted(file("DEC_1"));
    submission.setTaxpayerPinTypeSelect("BUS_XYZ");

    DeclarationsEvidenceException e =
        assertThrows(
            DeclarationsEvidenceException.class,
            () -> service(ACCEPTED_D1).fetchResult(submission, "cert", "7501011234", "IND_EGN"));
    assertTrue(e.getMessage().contains("BUS_XYZ"), e.getMessage());
    assertEquals(0, resultQueries, "НАП is not asked");
  }

  /**
   * ⚠️ Търсенето на резултат е по documentId ИЛИ по КОМБИНАЦИЯТА entryNumber + entryDate (Формат за
   * API, т. 2; API_DOC_ID_OR_ENTRY_NUMBER_PARAMETER_ERROR). Входящ номер без дата е невалидна
   * заявка и се отказва ПРЕДИ мрежата, вместо да върне 400 от НАП.
   */
  @Test
  void anEntryNumberWithoutAnEntryDateIsRefusedBeforeNapIsAsked() {
    NapSubmission submission = submitted(file("DEC_1"));
    submission.setDocumentId(null);
    submission.setEntryNumber("30E000000001");
    submission.setEntryDate(null);

    assertThrows(
        AxelorException.class,
        () -> service(ACCEPTED_D1).fetchResult(submission, "cert", "7501011234", "IND_EGN"));
    assertEquals(0, resultQueries, "НАП is not asked");
  }

  @Test
  void unreadableResultPreservesRawEvidenceAndFlagsReview() throws Exception {
    NapSubmission submission = submitted(file("DEC_1"));

    service(UNREADABLE).fetchResult(submission, "cert", "7501011234", "IND_EGN");

    assertArrayEquals(UNREADABLE.getBytes(StandardCharsets.UTF_8), storedRawBytes);
    assertEquals(NapProcessingResultRepository.OUTCOME_UNKNOWN, storedOutcome);
    assertEquals("RESULT_NEEDS_REVIEW", submission.getExternalStatus());
    assertEquals(NapSubmissionRepository.STATUS_SUBMITTED, submission.getSubmissionStatusSelect());
  }
}

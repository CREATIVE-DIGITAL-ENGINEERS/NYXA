package com.axelor.apps.napsubmissions.services.declarations.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.napsubmissions.db.repo.NapProcessingResultRepository;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The НАП result reading, against snippets taken verbatim from the official samples in {@code the
 * official NAP API specification API_MockResultData.txt}. The rule under test is conservatism: only
 * an explicit acceptance of every submitted file accepts.
 */
class DeclarationsResultParserTest {

  private final DeclarationsResultParser parser = new DeclarationsResultParser();

  private static final Set<FileDocumentType> D1_ONLY = EnumSet.of(FileDocumentType.DEC_1);
  private static final Set<FileDocumentType> D1_D6 =
      EnumSet.of(FileDocumentType.DEC_1, FileDocumentType.DEC_6);

  /** Official documentId 8: a Д1-only submission, fully accepted. */
  private static final String ACCEPTED_D1_ONLY =
      "<table><tr><td> Брой подадени Декларации обр.1: 1 Брой приети Декларации обр.1:1"
          + " Брой отхвърлени Декларации обр.1:0 </td></tr></table>";

  /** Official documentId 9: combined Д1+Д6, everything accepted. */
  private static final String ACCEPTED_D1_D6 =
      "<table><tr><td> Брой подадени Декларации обр.1: 5 Брой приети Декларации обр.1:5"
          + " Брой отхвърлени Декларации обр.1:0 </td></tr>"
          + "<tr><td> Брой подадени Декларации обр.6:1 Брой приети Декларации обр.6:1"
          + " Брой отхвърлени Декларации обр.6:0 </td></tr></table>";

  /** Official documentId 10: Д1 partially accepted, Д6 rejected. */
  private static final String ONE_FILE_REJECTED =
      "<table><tr><td>Брой подадени Декларации обр.1: 6 Брой приети Декларации обр.1:1"
          + " Брой отхвърлени Декларации обр.1:5</td></tr>"
          + "<tr><td>Брой подадени Декларации обр.6:1 Брой приети Декларации обр.6:0"
          + " Брой отхвърлени Декларации обр.6:1</td></tr></table>";

  private ParsedDeclarationsResult parse(String html, Set<FileDocumentType> submitted) {
    return parser.parse(html.getBytes(StandardCharsets.UTF_8), submitted);
  }

  @Test
  void acceptedD1OnlyResultIsAccepted() {
    ParsedDeclarationsResult parsed = parse(ACCEPTED_D1_ONLY, D1_ONLY);

    assertEquals(NapProcessingResultRepository.OUTCOME_ACCEPTED, parsed.overallOutcome);
    assertEquals(
        NapProcessingResultRepository.OUTCOME_ACCEPTED,
        parsed.fileOutcomes.get(FileDocumentType.DEC_1));
    assertFalse(parsed.needsReview);
  }

  @Test
  void combinedResultRecordsBothFilesSeparately() {
    ParsedDeclarationsResult parsed = parse(ACCEPTED_D1_D6, D1_D6);

    assertEquals(NapProcessingResultRepository.OUTCOME_ACCEPTED, parsed.overallOutcome);
    assertEquals(2, parsed.fileOutcomes.size());
    assertEquals(
        NapProcessingResultRepository.OUTCOME_ACCEPTED,
        parsed.fileOutcomes.get(FileDocumentType.DEC_6));
  }

  @Test
  void combinedResultWithOneRejectedFileIsRejected() {
    ParsedDeclarationsResult parsed = parse(ONE_FILE_REJECTED, D1_D6);

    assertEquals(NapProcessingResultRepository.OUTCOME_REJECTED, parsed.overallOutcome);
    assertEquals(
        NapProcessingResultRepository.OUTCOME_PARTIALLY_ACCEPTED,
        parsed.fileOutcomes.get(FileDocumentType.DEC_1));
    assertEquals(
        NapProcessingResultRepository.OUTCOME_REJECTED,
        parsed.fileOutcomes.get(FileDocumentType.DEC_6));
  }

  /** Д6 was submitted but the body only speaks about Д1 — still processing, never accepted. */
  @Test
  void submittedFileWithoutAnyResultStaysProcessing() {
    ParsedDeclarationsResult parsed = parse(ACCEPTED_D1_ONLY, D1_D6);

    assertEquals(NapProcessingResultRepository.OUTCOME_PENDING, parsed.overallOutcome);
    assertNull(parsed.fileOutcomes.get(FileDocumentType.DEC_6));
  }

  @Test
  void malformedBodyIsFlaggedForReviewAndNeverAccepted() {
    ParsedDeclarationsResult parsed = parse("<html><body><p>&lt;&gt;?!</p></body></html>", D1_ONLY);

    assertEquals(NapProcessingResultRepository.OUTCOME_UNKNOWN, parsed.overallOutcome);
    assertTrue(parsed.needsReview);
    assertTrue(parsed.fileOutcomes.isEmpty());
  }
}

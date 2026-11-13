package com.axelor.apps.aiocr.service.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Near-miss file-name matching — the scoring, not the query.
 *
 * <p>Replaces {@code OcrRecordFileNameSuggestionTest}, which persisted a handful of uploads and
 * then asserted things that are decided entirely by {@code baseName} and {@code similarity}. It
 * paid a full Guice + HSQLDB bootstrap (~25s) to test pure arithmetic on two strings, and one of
 * its seven cases did not touch the database at all.
 *
 * <p>The case it exists for: a user asks about "Получетаел.pdf" when the stored file is
 * "Получател.pdf". The exact search finds nothing, and with no candidates to work from the agent
 * would re-run variations of the same search until it hits its tool budget. What makes that fixable
 * is that the typo scores above the threshold and the unrelated document scores below it — which is
 * exactly what is asserted here.
 *
 * <p>The surrounding query behaviour (SQL prefilter, company scoping, result cap) is covered by
 * {@code OcrRecordSearchIntegrationTest}, where a database is genuinely the thing under test.
 */
class OcrRecordFileNameSimilarityTest {

  private static final double THRESHOLD = OcrRecordSearchService.SUGGEST_MIN_SIMILARITY;

  /** Convenience: score two raw file names the way the ranking does. */
  private static double score(String searched, String candidate) {
    return OcrRecordSearchService.similarity(
        OcrRecordSearchService.baseName(searched), OcrRecordSearchService.baseName(candidate));
  }

  @Test
  void theCyrillicTypoThatCausedTheLiveFailureScoresAboveTheThreshold() {
    assertTrue(
        score("Получетаел.pdf", "Получател.pdf") >= THRESHOLD,
        "a two-character transposition must still surface the document");
  }

  @Test
  void anUnrelatedDocumentScoresBelowTheThreshold() {
    assertTrue(
        score("quarterly-bank-statement-summary.pdf", "Получател.pdf") < THRESHOLD,
        "a different document must never be presented as a typo");
  }

  @Test
  void theNearerOfTwoCandidatesScoresHigherSoRankingPutsItFirst() {
    // suggestByFileName sorts by this score descending; if the comparison is right, the order is.
    double near = score("Faktura-2026-0043.pdf", "Faktura-2026-0042.pdf");
    double far = score("Faktura-2026-0043.pdf", "Faktura-2019-9987.pdf");
    assertTrue(near > far, "expected " + near + " > " + far);
    assertTrue(near >= THRESHOLD);
  }

  @Test
  void aShortFragmentContainedInALongerNameIsAStrongHit() {
    // Plain edit distance would punish the length difference alone, so containment is
    // special-cased.
    assertTrue(
        score("invoice-p19-18", "manyinvoices_plusreciepts-invoice-p19-18.pdf") >= THRESHOLD);
  }

  @Test
  void separatorsCaseAndExtensionAreNormalisedAway() {
    assertEquals("fact12", OcrRecordSearchService.baseName("Fact_1 (2).PDF"));
    assertEquals("fact12", OcrRecordSearchService.baseName("  FACT-1.2.pdf  "));
    assertEquals("", OcrRecordSearchService.baseName(null));
    assertEquals("", OcrRecordSearchService.baseName(""));
    // A dotless name must not lose its last segment to the extension strip.
    assertEquals("invoice", OcrRecordSearchService.baseName("invoice"));
  }

  @Test
  void identicalNamesScoreExactlyOneAndEmptyOnesScoreZero() {
    assertEquals(1d, OcrRecordSearchService.similarity("abc", "abc"));
    assertEquals(0d, OcrRecordSearchService.similarity("", "abc"));
    assertEquals(0d, OcrRecordSearchService.similarity("abc", ""));
  }

  @Test
  void aFragmentShorterThanFourCharactersIsRejectedByTheSuggestionGuard() {
    // suggestByFileName returns nothing below this length rather than matching half the table.
    // Asserted on the normalised form, which is what the guard measures.
    assertTrue(OcrRecordSearchService.baseName("По").length() < 4);
    assertTrue(OcrRecordSearchService.baseName("").length() < 4);
    assertTrue(OcrRecordSearchService.baseName("a.b").length() < 4);
    assertTrue(OcrRecordSearchService.baseName("Получател.pdf").length() >= 4);
  }

  @Test
  void theScanLimitAndThresholdAreTheValuesTheRankingWasTunedFor() {
    // Pinned so a casual edit cannot quietly turn suggestions into a full-table fuzzy scan.
    assertEquals(200, OcrRecordSearchService.SUGGEST_SCAN_LIMIT);
    assertEquals(0.55d, OcrRecordSearchService.SUGGEST_MIN_SIMILARITY);
  }

  @Test
  void levenshteinIsSymmetricAndCountsTheEditsItShould() {
    assertEquals(0, OcrRecordSearchService.levenshtein("abc", "abc"));
    assertEquals(1, OcrRecordSearchService.levenshtein("abc", "abd"));
    assertEquals(3, OcrRecordSearchService.levenshtein("abc", ""));
    assertEquals(
        OcrRecordSearchService.levenshtein("получател", "получетаел"),
        OcrRecordSearchService.levenshtein("получетаел", "получател"));
  }
}

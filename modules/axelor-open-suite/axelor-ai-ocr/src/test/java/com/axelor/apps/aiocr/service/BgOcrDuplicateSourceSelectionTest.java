package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Which byte-identical upload a new duplicate points at, once a whole-document split child shares
 * its carrier's MetaFile and therefore its content hash. Two live rows then hold one hash and they
 * are NOT interchangeable — the carrier is a structural container with no accounting document, the
 * leaf is the document a user cares about — so the guard has to choose rather than take whichever
 * row the database happens to return.
 *
 * <p>Every case is asserted in BOTH input orders: a rule that only holds when the rows arrive in a
 * convenient order is not a rule.
 */
class BgOcrDuplicateSourceSelectionTest {

  private static AiOcrUpload row(long id, String status, String carrierKind) {
    AiOcrUpload u = new AiOcrUpload();
    u.setId(id);
    u.setStatusSelect(status);
    u.setCarrierKind(carrierKind);
    return u;
  }

  /** The pdfSplit carrier: holds the file, holds the hash, produces no accounting document. */
  private static AiOcrUpload carrier(long id) {
    return row(id, AiOcrUploadStatus.ARCHIVE, AiOcrUploadStatus.CARRIER_KIND_PDF_SPLIT);
  }

  private static AiOcrUpload leafWithInvoice(long id, Invoice invoice) {
    AiOcrUpload u = row(id, AiOcrUploadStatus.COMPLETED, null);
    u.setSegmentType(AiOcrUploadStatus.SEGMENT_TYPE_INVOICE);
    u.setInvoice(invoice);
    return u;
  }

  private static AiOcrUpload leafNeedingReview(long id) {
    AiOcrUpload u = row(id, AiOcrUploadStatus.NEEDS_REVIEW, null);
    u.setSegmentType(AiOcrUploadStatus.SEGMENT_TYPE_INVOICE);
    return u;
  }

  /** Assert the same winner whichever order the candidates arrive in. */
  private static void assertPicksRegardlessOfOrder(AiOcrUpload expected, AiOcrUpload... rows) {
    List<AiOcrUpload> forward = new ArrayList<>(Arrays.asList(rows));
    List<AiOcrUpload> backward = new ArrayList<>(forward);
    Collections.reverse(backward);
    assertSame(expected, BgOcrIntakeService.pickDuplicateSource(forward), "in listed order");
    assertSame(expected, BgOcrIntakeService.pickDuplicateSource(backward), "in reversed order");
  }

  // --- (1) + (2) the shared-hash tree, and the document it must preserve -----------------------

  @Test
  void aDirectReuploadResolvesToTheInvoiceLeafNotItsCarrier() {
    Invoice invoice = new Invoice();
    invoice.setId(500L);
    AiOcrUpload pdfCarrier = carrier(10L); // lower id — would win any "oldest row" rule
    AiOcrUpload leaf = leafWithInvoice(11L, invoice);

    assertPicksRegardlessOfOrder(leaf, pdfCarrier, leaf);
    assertSame(
        invoice,
        BgOcrIntakeService.pickDuplicateSource(Arrays.asList(pdfCarrier, leaf)).getInvoice(),
        "the duplicate inherits the leaf's invoice, which is the whole point of linking it");
  }

  @Test
  void aMoveProducingLeafIsChosenAndCarriesItsMove() {
    Move move = new Move();
    move.setId(600L);
    AiOcrUpload pdfCarrier = carrier(20L);
    AiOcrUpload leaf = row(21L, AiOcrUploadStatus.COMPLETED, null);
    leaf.setMove(move);

    assertPicksRegardlessOfOrder(leaf, pdfCarrier, leaf);
    assertSame(
        move, BgOcrIntakeService.pickDuplicateSource(Arrays.asList(leaf, pdfCarrier)).getMove());
  }

  // --- (3) a leaf with no document yet ---------------------------------------------------------

  @Test
  void aNeedsReviewLeafStillBeatsAStructuralCarrier() {
    // The leaf has produced nothing yet (no supplier match, say). It is still the document row, and
    // still the one a user would open — the carrier is scaffolding.
    AiOcrUpload pdfCarrier = carrier(30L);
    AiOcrUpload leaf = leafNeedingReview(31L);

    assertPicksRegardlessOfOrder(leaf, pdfCarrier, leaf);
  }

  @Test
  void aDocumentProducingLeafOutranksADocumentlessOne() {
    // Two live leaves of the same bytes: prefer the one whose document the duplicate can preserve.
    Invoice invoice = new Invoice();
    invoice.setId(501L);
    AiOcrUpload reviewing = leafNeedingReview(40L);
    AiOcrUpload completed = leafWithInvoice(41L, invoice);

    assertPicksRegardlessOfOrder(completed, reviewing, completed);
  }

  // --- (4) nothing is decided by an id ---------------------------------------------------------

  @Test
  void idOnlyBreaksTiesInsideOneRankAndNeverDecidesAcrossRanks() {
    Invoice invoice = new Invoice();
    invoice.setId(502L);
    // The carrier is both OLDER and NEWER than the leaf in these two arrangements; the leaf wins
    // either way, so no id-based rule is doing the work.
    AiOcrUpload laterLeaf = leafWithInvoice(2L, invoice);
    assertPicksRegardlessOfOrder(laterLeaf, carrier(1L), laterLeaf);
    AiOcrUpload lateCarrier = carrier(99L);
    AiOcrUpload earlyLeaf = leafWithInvoice(3L, invoice);
    assertPicksRegardlessOfOrder(earlyLeaf, lateCarrier, earlyLeaf);

    // Within one rank, the oldest — the original import — wins, deterministically.
    AiOcrUpload older = leafNeedingReview(7L);
    AiOcrUpload newer = leafNeedingReview(8L);
    assertPicksRegardlessOfOrder(older, older, newer);
  }

  @Test
  void aCarrierIsChosenOnlyWhenItIsTheSoleCandidate() {
    // Pre-existing behaviour is preserved: with nothing better, the carrier still answers, so a
    // re-upload is still reported as a duplicate rather than silently re-imported.
    AiOcrUpload only = carrier(50L);
    assertSame(only, BgOcrIntakeService.pickDuplicateSource(Collections.singletonList(only)));
  }

  // --- (5) the ordinary, non-shared cases are unchanged -----------------------------------------

  @Test
  void asingleOrdinaryUploadIsStillTheAnswer() {
    AiOcrUpload plain = row(60L, AiOcrUploadStatus.COMPLETED, null);
    assertSame(plain, BgOcrIntakeService.pickDuplicateSource(Collections.singletonList(plain)));
  }

  @Test
  void multiInvoiceSlicesKeepTheirOwnHashesSoOnlyOneRowEverMatches() {
    // A multi-invoice PDF still slices, so each child has its OWN bytes and its own hash: the
    // candidate list for any one of those hashes has exactly one row and the ranking is a no-op.
    Invoice invoice = new Invoice();
    invoice.setId(503L);
    AiOcrUpload slice = leafWithInvoice(70L, invoice);
    assertSame(slice, BgOcrIntakeService.pickDuplicateSource(Collections.singletonList(slice)));
  }

  @Test
  void noCandidatesMeansNoDuplicate() {
    assertNull(BgOcrIntakeService.pickDuplicateSource(Collections.emptyList()));
    assertNull(BgOcrIntakeService.pickDuplicateSource(null));
    assertNull(BgOcrIntakeService.pickDuplicateSource(Collections.singletonList(null)));
  }

  // --- the ranks themselves ---------------------------------------------------------------------

  @Test
  void theRankOrdersMeaningNotIdentity() {
    Invoice invoice = new Invoice();
    invoice.setId(504L);
    AiOcrUpload carrierWithDoc = carrier(80L);
    carrierWithDoc.setInvoice(invoice);

    assertEquals(0, BgOcrIntakeService.duplicateSourceRank(leafWithInvoice(81L, invoice)));
    assertEquals(1, BgOcrIntakeService.duplicateSourceRank(leafNeedingReview(82L)));
    assertEquals(2, BgOcrIntakeService.duplicateSourceRank(carrierWithDoc));
    assertEquals(3, BgOcrIntakeService.duplicateSourceRank(carrier(83L)));
  }
}

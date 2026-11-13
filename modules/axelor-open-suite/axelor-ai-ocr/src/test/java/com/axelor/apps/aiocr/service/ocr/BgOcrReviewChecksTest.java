package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the pure OCR review checks: identity cross-check and currency mismatch. */
class BgOcrReviewChecksTest {

  // ── identity cross-check ──────────────────────────────────────────────────────

  @Test
  void supplierMatchingCompanyFlagsPossibleSwap() {
    // Azure labelled the WeAccount company (100000026) as the vendor, and the real supplier as the
    // customer — so both the swap warning and the recipient-mismatch warning legitimately fire.
    List<String> w =
        BgOcrReviewChecks.identityWarnings(
            "100000026", "BG100000026", // supplier (wrong — it's us)
            "131391369", "BG131391369", // customer (actually the real supplier)
            "100000026", "BG100000026"); // company
    assertTrue(w.stream().anyMatch(x -> x.toLowerCase().contains("swap")));
  }

  @Test
  void recipientNotMatchingCompanyIsFlagged() {
    // Invoice addressed to a different company than the one selected on the upload.
    List<String> w =
        BgOcrReviewChecks.identityWarnings(
            "131391369", "BG131391369", // supplier (fine)
            "999999999", "BG999999999", // customer (different company)
            "100000026", "BG100000026"); // company
    assertEquals(1, w.size());
    assertTrue(w.get(0).toLowerCase().contains("recipient"));
  }

  @Test
  void correctSupplierAndRecipientYieldNoWarnings() {
    List<String> w =
        BgOcrReviewChecks.identityWarnings(
            "131391369", "BG131391369", // supplier
            "100000026", "BG100000026", // customer == company
            "100000026", "BG100000026"); // company
    assertTrue(w.isEmpty());
  }

  @Test
  void bgPrefixAndSpacesAreNormalisedBeforeComparing() {
    // Customer given as raw EIK with spaces; company given with BG VAT prefix — same party.
    List<String> w =
        BgOcrReviewChecks.identityWarnings(
            "131391369",
            "BG131391369", // supplier
            "100 000 026",
            null, // customer, spaced EIK, no VAT
            null,
            "BG100000026"); // company, VAT only
    assertTrue(w.isEmpty(), "normalised EIK/VAT should match, so no recipient mismatch");
  }

  @Test
  void unknownCompanyIdentityProducesNoWarnings() {
    // With no company identity we cannot compare — stay silent rather than guess.
    List<String> w =
        BgOcrReviewChecks.identityWarnings(
            "131391369", "BG131391369", "999999999", "BG999999999", null, null);
    assertTrue(w.isEmpty());
  }

  @Test
  void missingCustomerDoesNotFlagRecipientMismatch() {
    // Supplier is fine and there is no extracted customer → only silence (no false positive).
    List<String> w =
        BgOcrReviewChecks.identityWarnings(
            "131391369", "BG131391369", null, null, "100000026", "BG100000026");
    assertTrue(w.isEmpty());
  }

  // ── currency mismatch ─────────────────────────────────────────────────────────

  @Test
  void currencyMismatchIsReported() {
    String note = BgOcrReviewChecks.currencyMismatchNote("USD", "EUR");
    assertTrue(note != null && note.contains("USD") && note.contains("EUR"));
  }

  @Test
  void sameCurrencyCaseInsensitiveIsNotReported() {
    assertNull(BgOcrReviewChecks.currencyMismatchNote("eur", "EUR"));
  }

  @Test
  void currencyMismatchNoteNullWhenEitherSideBlank() {
    assertNull(BgOcrReviewChecks.currencyMismatchNote(null, "EUR"));
    assertNull(BgOcrReviewChecks.currencyMismatchNote("EUR", null));
    assertNull(BgOcrReviewChecks.currencyMismatchNote("  ", "EUR"));
  }
}

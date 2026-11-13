package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ingest.BgOcrPdfSegmenter;
import com.axelor.apps.aiocr.service.ingest.BgOcrPdfSegmenter.SegmentPlan;
import org.junit.jupiter.api.Test;

/**
 * The required archive hierarchy: a ZIP containing one PDF containing one invoice must leave three
 * records (ZIP carrier -> PDF carrier -> invoice child), exactly as a ZIP containing a five-invoice
 * PDF does. A DIRECTLY uploaded one-invoice PDF must still be a single terminal record.
 *
 * <p>Pure: the decision is {@code BgOcrIntakeService.mustBecomeSplitCarrier} plus the two facts the
 * segmenter derives from the ONE Azure result already in hand, so this needs no Guice, no JPA and
 * no OCR call.
 */
class BgOcrArchiveChildCarrierPromotionTest {

  private static final boolean FROM_ARCHIVE = true;
  private static final boolean DIRECT = false;

  // --- the rule itself -------------------------------------------------------

  @Test
  void aLoneInvoiceBecomesACarrierOnlyWhenItCameOutOfAnArchive() {
    // needsSplit is false for a single invoice, which must not end the story and collapse the
    // extracted PDF and its invoice into one row.
    assertTrue(
        BgOcrIntakeService.mustBecomeSplitCarrier(FROM_ARCHIVE, false, true, 1),
        "a ZIP-extracted PDF holding one invoice must still become the middle carrier");
    assertFalse(
        BgOcrIntakeService.mustBecomeSplitCarrier(DIRECT, false, true, 1),
        "a directly uploaded one-invoice PDF stays a single terminal record");
  }

  @Test
  void multiDocumentPdfsAreUnaffectedByWhereTheyCameFrom() {
    // needsSplit already decided these; the archive arm must not change the answer either way.
    assertTrue(BgOcrIntakeService.mustBecomeSplitCarrier(DIRECT, true, true, 2));
    assertTrue(BgOcrIntakeService.mustBecomeSplitCarrier(FROM_ARCHIVE, true, true, 2));
  }

  @Test
  void aPdfWithNoInvoiceIsNeverPromotedByTheArchiveArm() {
    // A ZIP-extracted bank statement / unrecognised document has no invoice anchor. It keeps its
    // own routing (the bank-statement path runs before this rule at all) and is never carved up.
    assertFalse(
        BgOcrIntakeService.mustBecomeSplitCarrier(FROM_ARCHIVE, false, false, 1),
        "no invoice in the PDF: the archive arm must not fire");
  }

  @Test
  void aPlanThatWouldProduceNoChildIsRefused() {
    // Splitting into zero children leaves a finished carrier with no children, which the rollup
    // reads as FAILED. Better to stay one document than to invent a failed carrier — and this is
    // also why a duplicate or failed archive entry can never grow an invoice child: it is created
    // terminal by the extractor and never reaches this decision at all.
    assertFalse(BgOcrIntakeService.mustBecomeSplitCarrier(FROM_ARCHIVE, false, true, 0));
    assertFalse(BgOcrIntakeService.mustBecomeSplitCarrier(DIRECT, true, true, 0));
  }

  // --- the facts the rule is fed, straight from the segmenter ----------------

  @Test
  void segmenterReportsALoneInvoiceAsAnchoredButNotNeedingASplit() {
    String json =
        "{\"pages\":["
            + page(1, "Фактура", "№ 1000000001", "AA EOOD")
            + "],\"documents\":["
            + doc(1, "BG111111111", "1000000001", "100.00")
            + "]}";

    SegmentPlan plan = new BgOcrPdfSegmenter().segment(json);

    assertFalse(plan.needsSplit, "one invoice alone is not a multi-document PDF");
    assertTrue(plan.hasInvoiceAnchor, "but it IS an invoice — the fact the archive arm needs");
    assertEquals(1, plan.recordSegmentCount(), "and it would produce exactly one child record");

    // Put together: this is the ZIP+single-invoice case, and it now promotes.
    assertTrue(
        BgOcrIntakeService.mustBecomeSplitCarrier(
            FROM_ARCHIVE, plan.needsSplit, plan.hasInvoiceAnchor, plan.recordSegmentCount()));
    assertFalse(
        BgOcrIntakeService.mustBecomeSplitCarrier(
            DIRECT, plan.needsSplit, plan.hasInvoiceAnchor, plan.recordSegmentCount()));
  }

  @Test
  void segmenterReportsNoAnchorForAPdfWithoutAnInvoice() {
    String json = "{\"pages\":[" + page(1, "ОТЧЕТ ПО СМЕТКА", "период 01.01", "BANK") + "]}";

    SegmentPlan plan = new BgOcrPdfSegmenter().segment(json);

    assertFalse(plan.hasInvoiceAnchor, "no invoice anchor in a statement-like page");
    assertFalse(
        BgOcrIntakeService.mustBecomeSplitCarrier(
            FROM_ARCHIVE, plan.needsSplit, plan.hasInvoiceAnchor, plan.recordSegmentCount()));
  }

  // --- tiny Azure-JSON builders (no external fixtures) ---

  private static String page(int n, String l1, String l2, String l3) {
    return "{\"pageNumber\":"
        + n
        + ",\"width\":8.5,\"height\":11,\"lines\":["
        + "{\"content\":\""
        + l1
        + "\"},{\"content\":\""
        + l2
        + "\"},{\"content\":\""
        + l3
        + "\"}]}";
  }

  private static String doc(int page, String vat, String invId, String total) {
    return "{\"docType\":\"invoice\",\"confidence\":1,\"boundingRegions\":[{\"pageNumber\":"
        + page
        + "}],\"fields\":{\"VendorTaxId\":{\"valueString\":\""
        + vat
        + "\"},\"InvoiceId\":{\"valueString\":\""
        + invId
        + "\"},\"InvoiceTotal\":{\"valueCurrency\":{\"amount\":"
        + total
        + ",\"currencyCode\":\"EUR\"}},\"SubTotal\":{\"valueCurrency\":{\"amount\":"
        + total
        + ",\"currencyCode\":\"EUR\"}}}}";
  }
}

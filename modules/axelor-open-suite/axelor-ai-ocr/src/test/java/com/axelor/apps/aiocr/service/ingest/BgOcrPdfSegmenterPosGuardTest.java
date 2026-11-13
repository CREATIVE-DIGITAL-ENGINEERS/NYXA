package com.axelor.apps.aiocr.service.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ingest.BgOcrPdfSegmenter.DocSegment;
import com.axelor.apps.aiocr.service.ingest.BgOcrPdfSegmenter.SegmentPlan;
import com.axelor.apps.aiocr.service.ingest.BgOcrPdfSegmenter.Type;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The POS/receipt-attachment guard: a page that LOOKS like a fiscal receipt (ФИСКАЛЕН БОН) but
 * whose covering Azure document is actually a DISTINCT invoice — different supplier EIK, different
 * document number, incompatible total — must not be silently attached to the preceding invoice.
 * This models the real Samsung page-6 case, where Azure surfaced page 6 as its own
 * (different-supplier) document with a fiscal-receipt layout and no legible "Фактура" title, and it
 * was being swallowed as the previous invoice's POS receipt. Uses only structural identity — no
 * supplier-specific text.
 */
class BgOcrPdfSegmenterPosGuardTest {

  /**
   * Page 2 is a fiscal-receipt-layout invoice of a DIFFERENT supplier (different EIK + number)
   * whose total Azure did not surface (so it is not anchored the normal way). It must NOT be
   * attached to page 1's invoice as a POS receipt — it is preserved as its own record (review),
   * never lost.
   */
  @Test
  void distinctSupplierFiscalPageIsNotAttachedAsReceipt() {
    String json =
        "{\"pages\":["
            + page(1, "Фактура", "№ 1000000001", "AA EOOD")
            + ","
            + page(2, "ФИСКАЛЕН БОН", "№ 2000000002", "BB EOOD")
            + "],\"documents\":["
            // p1: full invoice for supplier BG111111111
            + doc(1, "BG111111111", "1000000001", "100.00")
            + ","
            // p2: DIFFERENT supplier BG222222222, own number, NO total (degraded → not anchored)
            + docNoTotal(2, "BG222222222", "2000000002")
            + "]}";

    SegmentPlan plan = new BgOcrPdfSegmenter().segment(json);

    // No POS_RECEIPT: page 2 was not attached to page 1's invoice.
    assertFalse(
        plan.segments.stream().anyMatch(s -> s.type == Type.POS_RECEIPT),
        "distinct-supplier fiscal page must not be attached as a POS receipt");
    // Two record segments: the page-1 invoice and a preserved page-2 record.
    List<DocSegment> records =
        plan.segments.stream()
            .filter(s -> s.type == Type.INVOICE || s.type == Type.REVIEW)
            .collect(java.util.stream.Collectors.toList());
    assertEquals(2, records.size(), "page 2 must be preserved as its own record");
    DocSegment page2 = plan.segments.stream().filter(s -> s.fromPage == 2).findFirst().orElse(null);
    assertTrue(page2 != null && page2.type == Type.REVIEW, "page 2 kept for review, not attached");
  }

  /**
   * A genuine card-slip of the SAME purchase — same supplier, same total, no own number — carries
   * no evidence of a different document, so it is still attached to the preceding invoice as before
   * (no separate record).
   */
  @Test
  void genuineSameSupplierReceiptIsStillAttached() {
    String json =
        "{\"pages\":["
            + page(1, "Фактура", "№ 1000000001", "AA EOOD")
            + ","
            + page(2, "ФИСКАЛЕН БОН", "AA EOOD", "100.00")
            + "],\"documents\":["
            + doc(1, "BG111111111", "1000000001", "100.00")
            + ","
            // p2: SAME supplier, SAME total, no own number → genuine supporting receipt
            + docNoNumber(2, "BG111111111", "100.00")
            + "]}";

    SegmentPlan plan = new BgOcrPdfSegmenter().segment(json);

    assertTrue(
        plan.segments.stream().anyMatch(s -> s.type == Type.POS_RECEIPT),
        "same-supplier same-total receipt must still be attached as a POS receipt");
    long records =
        plan.segments.stream().filter(s -> s.type == Type.INVOICE || s.type == Type.REVIEW).count();
    assertEquals(1, records, "only the page-1 invoice is a record; the receipt is attached");
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

  private static String docNoTotal(int page, String vat, String invId) {
    return "{\"docType\":\"invoice\",\"confidence\":1,\"boundingRegions\":[{\"pageNumber\":"
        + page
        + "}],\"fields\":{\"VendorTaxId\":{\"valueString\":\""
        + vat
        + "\"},\"InvoiceId\":{\"valueString\":\""
        + invId
        + "\"}}}";
  }

  private static String docNoNumber(int page, String vat, String total) {
    return "{\"docType\":\"invoice\",\"confidence\":1,\"boundingRegions\":[{\"pageNumber\":"
        + page
        + "}],\"fields\":{\"VendorTaxId\":{\"valueString\":\""
        + vat
        + "\"},\"InvoiceTotal\":{\"valueCurrency\":{\"amount\":"
        + total
        + ",\"currencyCode\":\"EUR\"}}}}";
  }
}

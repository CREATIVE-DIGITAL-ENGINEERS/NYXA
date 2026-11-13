package com.axelor.apps.aiocr.service.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.aiocr.service.ingest.BgOcrPdfSegmenter.DocSegment;
import com.axelor.apps.aiocr.service.ingest.BgOcrPdfSegmenter.Type;
import com.axelor.apps.aiocr.service.ingest.StockReceiptMatcher.Decision;
import com.axelor.apps.aiocr.service.ingest.StockReceiptMatcher.Status;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractLine;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Content-based stock-receipt → invoice matching (no Azure, no DB). Covers the spec: total is the
 * primary signal, items corroborate/disambiguate, a receipt may precede its invoice, duplicate
 * copies are ignored, position is only a weak tie-breaker, and equally-plausible invoices stay
 * ambiguous (never guessed).
 */
class StockReceiptMatcherTest {

  @Test
  void matchesByTotalWhenSingleInvoice() {
    List<DocSegment> segs =
        List.of(
            invoice(1, "336.55", "screws", "bolts"), receipt(3, "9000121609", "336.55", "screws"));
    Map<Integer, Decision> m = new StockReceiptMatcher().match(segs);
    assertEquals(Status.MATCHED, m.get(1).status);
    assertEquals(Integer.valueOf(0), m.get(1).invoiceSegIndex);
  }

  @Test
  void receiptMatchesLaterInvoiceByTotal() {
    // Receipt appears BEFORE its invoice, and there is a decoy invoice with a different total.
    List<DocSegment> segs =
        List.of(
            receipt(1, "555", "100.00", "widget"),
            invoice(2, "50.00", "other"),
            invoice(3, "100.00", "widget"));
    Map<Integer, Decision> m = new StockReceiptMatcher().match(segs);
    assertEquals(Status.MATCHED, m.get(0).status);
    assertEquals(Integer.valueOf(2), m.get(0).invoiceSegIndex);
  }

  @Test
  void disambiguatesEqualTotalsByItemOverlap() {
    // Two invoices with the SAME total; only the items tell them apart.
    List<DocSegment> segs =
        List.of(
            invoice(1, "100.00", "apples", "pears"),
            invoice(2, "100.00", "hammers", "nails"),
            receipt(4, "777", "100.00", "hammers", "nails"));
    Map<Integer, Decision> m = new StockReceiptMatcher().match(segs);
    assertEquals(Status.MATCHED, m.get(2).status);
    assertEquals(Integer.valueOf(1), m.get(2).invoiceSegIndex);
  }

  @Test
  void toleratesSmallOcrSpellingDifferencesInItems() {
    List<DocSegment> segs =
        List.of(
            invoice(1, "100.00", "professional cleaning kit"),
            invoice(2, "100.00", "office chair deluxe"),
            receipt(4, "888", "100.00", "profesional cleaning kit")); // one-letter OCR slip
    Map<Integer, Decision> m = new StockReceiptMatcher().match(segs);
    assertEquals(Status.MATCHED, m.get(2).status);
    assertEquals(Integer.valueOf(0), m.get(2).invoiceSegIndex);
  }

  @Test
  void duplicateCopiesBySameNumberAreIgnored() {
    List<DocSegment> segs =
        List.of(
            invoice(1, "336.55", "screws"),
            receipt(3, "9000121609", "336.55", "screws"),
            receipt(4, "9000121609", "336.55", "screws")); // same receipt No → copy
    Map<Integer, Decision> m = new StockReceiptMatcher().match(segs);
    assertEquals(Status.MATCHED, m.get(1).status);
    assertEquals(Status.DUPLICATE, m.get(2).status);
    assertEquals(Integer.valueOf(1), m.get(2).duplicateOfSegIndex);
  }

  @Test
  void duplicateCopiesByIdenticalTotalAndItemsAreIgnored() {
    // No receipt numbers, but the two receipts are otherwise identical → a copy.
    List<DocSegment> segs =
        List.of(
            invoice(1, "42.00", "cable", "tape"),
            receipt(3, null, "42.00", "cable", "tape"),
            receipt(4, null, "42.00", "cable", "tape"));
    Map<Integer, Decision> m = new StockReceiptMatcher().match(segs);
    assertEquals(Status.MATCHED, m.get(1).status);
    assertEquals(Status.DUPLICATE, m.get(2).status);
  }

  @Test
  void ambiguousWhenTwoInvoicesEquallyPlausible() {
    // Same total, no distinguishing items, and equal page distance → never guessed.
    List<DocSegment> segs =
        List.of(invoice(1, "100.00"), receipt(2, "999", "100.00", "mystery"), invoice(3, "100.00"));
    Map<Integer, Decision> m = new StockReceiptMatcher().match(segs);
    assertEquals(Status.AMBIGUOUS, m.get(1).status);
    assertNull(m.get(1).invoiceSegIndex);
  }

  @Test
  void positionBreaksAnOtherwiseEvenTie() {
    // Same total + no items, but one invoice is clearly nearer → weak position tie-break applies.
    List<DocSegment> segs =
        List.of(invoice(1, "100.00"), receipt(2, "999", "100.00"), invoice(9, "100.00"));
    Map<Integer, Decision> m = new StockReceiptMatcher().match(segs);
    assertEquals(Status.MATCHED, m.get(1).status);
    assertEquals(Integer.valueOf(0), m.get(1).invoiceSegIndex); // the nearer invoice (page 1)
  }

  @Test
  void noMatchWhenNoInvoiceIsPlausible() {
    List<DocSegment> segs =
        List.of(invoice(1, "50.00", "food"), receipt(3, "111", "999.00", "machinery"));
    Map<Integer, Decision> m = new StockReceiptMatcher().match(segs);
    assertEquals(Status.NO_MATCH, m.get(1).status);
    assertNull(m.get(1).invoiceSegIndex);
  }

  // --- helpers ---

  private static DocSegment invoice(int page, String total, String... items) {
    DocSegment s = new DocSegment();
    s.type = Type.INVOICE;
    s.fromPage = page;
    s.toPage = page;
    s.mappedResult = result(total, items);
    return s;
  }

  private static DocSegment receipt(int page, String number, String total, String... items) {
    DocSegment s = new DocSegment();
    s.type = Type.STOCK_RECEIPT;
    s.fromPage = page;
    s.toPage = page;
    s.receiptNumber = number;
    s.mappedResult = result(total, items);
    return s;
  }

  private static OcrExtractResult result(String total, String... items) {
    OcrExtractResult r = new OcrExtractResult();
    r.setTotalGross(new BigDecimal(total));
    for (String it : items) {
      OcrExtractLine l = new OcrExtractLine();
      l.setDescription(it);
      r.getLines().add(l);
    }
    return r;
  }
}

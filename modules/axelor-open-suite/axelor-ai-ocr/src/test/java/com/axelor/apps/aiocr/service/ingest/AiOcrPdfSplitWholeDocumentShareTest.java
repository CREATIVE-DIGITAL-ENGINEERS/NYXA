package com.axelor.apps.aiocr.service.ingest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ingest.BgOcrPdfSegmenter.DocSegment;
import com.axelor.apps.aiocr.service.ingest.BgOcrPdfSegmenter.SegmentPlan;
import com.axelor.apps.aiocr.service.ingest.BgOcrPdfSegmenter.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * When the one record in a split plan IS the whole source PDF, slicing it would write a second blob
 * holding the same document, so the child shares the carrier's existing MetaFile instead. This is
 * the rule that decides that, and it has to be strict in one direction only: a wrong "share" would
 * hand the leaf a file containing pages that were meant to stay behind, while a wrong "slice" only
 * costs one duplicated file.
 */
class AiOcrPdfSplitWholeDocumentShareTest {

  private static DocSegment seg(Type type, int from, int to) {
    DocSegment s = new DocSegment();
    s.type = type;
    s.fromPage = from;
    s.toPage = to;
    return s;
  }

  private static SegmentPlan plan(int pageCount, DocSegment... segments) {
    List<DocSegment> list = new ArrayList<>(Arrays.asList(segments));
    return new SegmentPlan(list, false, pageCount, true);
  }

  @Test
  void aLoneInvoiceSpanningEveryPageIsTheWholeDocument() {
    assertTrue(
        AiOcrPdfSplitService.isWholeDocumentSingleRecord(
            plan(2, seg(Type.INVOICE, 1, 2)), Integer.valueOf(2)),
        "one invoice, pages 1-2 of a 2-page PDF: the slice would be the carrier's own file");
    // A one-page invoice in a one-page PDF is the same situation.
    assertTrue(
        AiOcrPdfSplitService.isWholeDocumentSingleRecord(
            plan(1, seg(Type.INVOICE, 1, 1)), Integer.valueOf(1)));
    // MOVE and REVIEW records share the rule; only the receipt/blank kinds never get a child.
    assertTrue(
        AiOcrPdfSplitService.isWholeDocumentSingleRecord(
            plan(1, seg(Type.MOVE, 1, 1)), Integer.valueOf(1)));
    assertTrue(
        AiOcrPdfSplitService.isWholeDocumentSingleRecord(
            plan(1, seg(Type.REVIEW, 1, 1)), Integer.valueOf(1)));
  }

  @Test
  void aPartialPageRangeIsAlwaysSliced() {
    // The invoice is p1 of 3 — pages 2-3 must stay on the carrier and off the leaf.
    assertFalse(
        AiOcrPdfSplitService.isWholeDocumentSingleRecord(
            plan(3, seg(Type.INVOICE, 1, 1)), Integer.valueOf(3)));
    // Starts late: a dropped duplicate copy on page 1.
    assertFalse(
        AiOcrPdfSplitService.isWholeDocumentSingleRecord(
            plan(2, seg(Type.INVOICE, 2, 2)), Integer.valueOf(2)));
  }

  @Test
  void anythingElseInThePlanForcesASlice() {
    // A stock receipt is evidence that belongs on the carrier and as its own attachment, so even
    // though the invoice's range covers the file, the leaf must not carry the receipt page.
    assertFalse(
        AiOcrPdfSplitService.isWholeDocumentSingleRecord(
            plan(2, seg(Type.INVOICE, 1, 2), seg(Type.STOCK_RECEIPT, 2, 2)), Integer.valueOf(2)),
        "a receipt segment means the PDF holds more than the invoice");
    assertFalse(
        AiOcrPdfSplitService.isWholeDocumentSingleRecord(
            plan(2, seg(Type.INVOICE, 1, 1), seg(Type.POS_RECEIPT, 2, 2)), Integer.valueOf(2)));
    assertFalse(
        AiOcrPdfSplitService.isWholeDocumentSingleRecord(
            plan(2, seg(Type.INVOICE, 1, 1), seg(Type.BLANK, 2, 2)), Integer.valueOf(2)));
    // Two invoices: the multi-invoice case, untouched by any of this.
    assertFalse(
        AiOcrPdfSplitService.isWholeDocumentSingleRecord(
            plan(2, seg(Type.INVOICE, 1, 1), seg(Type.INVOICE, 2, 2)), Integer.valueOf(2)));
  }

  @Test
  void aCroppedSegmentIsNeverTheWholeDocument() {
    DocSegment cropped = seg(Type.INVOICE, 1, 1);
    cropped.cropBbox = new double[] {0, 0, 8.5, 5.5};
    cropped.cropPageW = 8.5;
    cropped.cropPageH = 11;
    assertTrue(cropped.isCrop(), "fixture sanity");
    assertFalse(
        AiOcrPdfSplitService.isWholeDocumentSingleRecord(plan(1, cropped), Integer.valueOf(1)),
        "a same-page crop is by definition part of a page");
  }

  @Test
  void anUnreadableOrDisagreeingPageCountRefusesToShare() {
    // PDFBox could not read the file: never claim the range covers it.
    assertFalse(
        AiOcrPdfSplitService.isWholeDocumentSingleRecord(plan(2, seg(Type.INVOICE, 1, 2)), null));
    // Azure reported 2 pages but the PDF really has 3 — the third was never classified, so the
    // "covers everything" claim would be about the OCR result, not about the bytes.
    assertFalse(
        AiOcrPdfSplitService.isWholeDocumentSingleRecord(
            plan(2, seg(Type.INVOICE, 1, 2)), Integer.valueOf(3)));
    assertFalse(
        AiOcrPdfSplitService.isWholeDocumentSingleRecord(
            plan(0, seg(Type.INVOICE, 1, 1)), Integer.valueOf(0)));
  }

  @Test
  void anEmptyOrAbsentPlanNeverShares() {
    assertFalse(AiOcrPdfSplitService.isWholeDocumentSingleRecord(null, Integer.valueOf(1)));
    assertFalse(AiOcrPdfSplitService.isWholeDocumentSingleRecord(plan(1), Integer.valueOf(1)));
  }
}

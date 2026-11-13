package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The client-facing "Extraction information" summary: short, jargon-free, one line, and free of any
 * internal/provider vocabulary (no OCR/Azure provider, request limit, chunking, DPI, JPEG quality,
 * or stitching — those live only in the INFO log).
 */
class BgOcrCompressionInfoTest {

  private static long mb(double m) {
    return (long) (m * 1024 * 1024);
  }

  @Test
  void nullOrEmptyYieldsNoInformation() {
    assertNull(BgOcrIntakeService.compressionInformation(null));
    assertNull(BgOcrIntakeService.compressionInformation(new ArrayList<>()));
  }

  @Test
  void singlePageUsesSingularAndPreservedNotice() {
    List<OcrExtractResult.PageCompression> pages = new ArrayList<>();
    pages.add(new OcrExtractResult.PageCompression(4, mb(7.2), mb(3.7)));
    String info = BgOcrIntakeService.compressionInformation(pages);
    assertEquals(
        "Page 4 was optimized for processing: 7.2→3.7 MB."
            + " The original document was preserved unchanged.",
        info);
  }

  @Test
  void multiplePagesJoinWithAndAndListSizes() {
    List<OcrExtractResult.PageCompression> pages = new ArrayList<>();
    pages.add(new OcrExtractResult.PageCompression(1, mb(7.0), mb(3.9)));
    pages.add(new OcrExtractResult.PageCompression(2, mb(10.0), mb(3.7)));
    pages.add(new OcrExtractResult.PageCompression(6, mb(5.1), mb(3.8)));
    pages.add(new OcrExtractResult.PageCompression(9, mb(8.4), mb(3.9)));
    String info = BgOcrIntakeService.compressionInformation(pages);
    assertTrue(info.startsWith("Pages 1, 2, 6 and 9 were optimized for processing:"), info);
    assertTrue(info.contains("7.0→3.9 MB, 10.0→3.7 MB, 5.1→3.8 MB, 8.4→3.9 MB"), info);
    assertTrue(info.endsWith("The original document was preserved unchanged."), info);
  }

  /** No internal/provider vocabulary may leak into the client field. */
  @Test
  void summaryHidesInternalVocabulary() {
    List<OcrExtractResult.PageCompression> pages = new ArrayList<>();
    pages.add(new OcrExtractResult.PageCompression(2, mb(6.0), mb(3.5)));
    String info = BgOcrIntakeService.compressionInformation(pages).toLowerCase();
    for (String banned :
        new String[] {"azure", "ocr", "dpi", "jpeg", "quality", "limit", "chunk", "stitch"}) {
      assertFalse(info.contains(banned), "leaked internal term: " + banned);
    }
  }
}

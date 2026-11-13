package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractLine;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Scoping an Azure document's line items to the pages a split segment actually keeps.
 *
 * <p>The case: a PDF holding the same invoice twice. Azure returns ONE document spanning both
 * physical pages, so its {@code Items} array carries every line twice while {@code SubTotal} stays
 * single. The splitter keeps page 1 and drops the copy on page 2 — the mapped result has to follow.
 */
class AzureItemPageScopeTest {

  private static final ObjectMapper M = new ObjectMapper();

  /** Two real lines on page 1 (55.00 + 25.46), the same two repeated on page 2. SubTotal 80.46. */
  private static final String DOUBLED_DOCUMENT =
      "{\"SubTotal\":{\"valueNumber\":80.46},"
          + "\"TotalTax\":{\"valueNumber\":16.09},"
          + "\"InvoiceTotal\":{\"valueNumber\":96.55},"
          + "\"Items\":{\"valueArray\":["
          + item(1, "Кърпи за ръце", "55.00")
          + ","
          + item(1, "Микрофибър лента", "25.46")
          + ","
          + item(2, "Кърпи за ръце", "55.00")
          + ","
          + item(2, "Микрофибър лента", "25.46")
          + "]}}";

  private static String item(int page, String description, String amount) {
    return "{\"boundingRegions\":[{\"pageNumber\":"
        + page
        + "}],\"valueObject\":{"
        + "\"Description\":{\"valueString\":\""
        + description
        + "\"},"
        + "\"Quantity\":{\"valueNumber\":1},"
        + "\"UnitPrice\":{\"valueNumber\":"
        + amount
        + "},"
        + "\"Amount\":{\"valueNumber\":"
        + amount
        + "}}}";
  }

  private static JsonNode fields(String json) throws IOException {
    return M.readTree(json);
  }

  private static BigDecimal netSum(OcrExtractResult r) {
    BigDecimal total = BigDecimal.ZERO;
    for (OcrExtractLine line : r.getLines()) {
      total = total.add(line.getNetAmount() == null ? BigDecimal.ZERO : line.getNetAmount());
    }
    return total;
  }

  @Test
  void aSegmentKeepingOnlyPageOneGetsOnlyPageOnesItems() throws IOException {
    OcrExtractResult r = new OcrExtractResult();
    AzureDocumentIntelligenceExtractor.mapDocumentFields(r, fields(DOUBLED_DOCUMENT), 1, 1);

    assertEquals(2, r.getLines().size(), "the copy on page 2 belongs to no retained page");
    // And the kept lines now agree with the header the same document reported — which is also what
    // lets the net/gross classification of Azure's Amount come out right.
    assertEquals(0, netSum(r).compareTo(new BigDecimal("80.46")));
  }

  @Test
  void withoutAPageRangeTheWholeDocumentIsStillMapped() throws IOException {
    // The single-document path has no split segment, so it must keep behaving exactly as before.
    OcrExtractResult r = new OcrExtractResult();
    AzureDocumentIntelligenceExtractor.mapDocumentFields(r, fields(DOUBLED_DOCUMENT));

    assertEquals(4, r.getLines().size());
  }

  @Test
  void aMultiPageSegmentKeepsEveryPageItRetains() throws IOException {
    // A genuine two-page invoice: the continuation page is part of the segment, so its lines stay.
    OcrExtractResult r = new OcrExtractResult();
    AzureDocumentIntelligenceExtractor.mapDocumentFields(r, fields(DOUBLED_DOCUMENT), 1, 2);

    assertEquals(4, r.getLines().size());
  }

  @Test
  void anItemWithNoPageInformationIsNeverDropped() throws IOException {
    // Absent provenance is not evidence that a line belongs elsewhere, and dropping a real line is
    // the worse error.
    String json =
        "{\"Items\":{\"valueArray\":["
            + "{\"valueObject\":{\"Description\":{\"valueString\":\"No regions\"},"
            + "\"Amount\":{\"valueNumber\":10.00}}},"
            + item(2, "On the dropped copy", "10.00")
            + "]}}";
    OcrExtractResult r = new OcrExtractResult();
    AzureDocumentIntelligenceExtractor.mapDocumentFields(r, fields(json), 1, 1);

    assertEquals(1, r.getLines().size());
    assertEquals("No regions", r.getLines().get(0).getDescription());
  }

  @Test
  void theRetainedPageTestReadsEveryRegionOfAStraddlingLine() throws IOException {
    JsonNode straddling =
        M.readTree("{\"boundingRegions\":[{\"pageNumber\":1},{\"pageNumber\":2}]}");
    JsonNode onPageTwo = M.readTree("{\"boundingRegions\":[{\"pageNumber\":2}]}");

    // A line that starts on the retained page and spills over stays with the segment it starts in.
    assertTrue(AzureDocumentIntelligenceExtractor.onRetainedPage(straddling, 1, 1));
    assertFalse(AzureDocumentIntelligenceExtractor.onRetainedPage(onPageTwo, 1, 1));
    // No range asked for: nothing is ever excluded.
    assertTrue(AzureDocumentIntelligenceExtractor.onRetainedPage(onPageTwo, null, null));
  }
}

package com.axelor.apps.bglocalization.service.bankstatement;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests statement-boundary detection: a single-account/period PDF is one statement (preserving the
 * single-statement behaviour), while an IBAN change, a period change, or a pagination reset ("1 от
 * N" past the first page) starts a new statement. Also parses the real stored fixture, which is one
 * 6-page statement, when present.
 */
class BgBankStatementOcrSegmenterTest {

  private static final ObjectMapper M = new ObjectMapper();

  private static final String IBAN_A = "BG93BPBI88981032573701";
  private static final String IBAN_B = "BG18UBBS80021062502410";
  private static final String P1 = "01.01.2026 - 31.01.2026";
  private static final String P2 = "01.02.2026 - 28.02.2026";

  @Test
  void oneAccountAndPeriodIsASingleStatement() {
    String json =
        doc(
            new Page[] {
              page(1, IBAN_A, P1, 1, 3), page(2, IBAN_A, P1, 2, 3), page(3, IBAN_A, P1, 3, 3)
            });
    List<int[]> segs = new BgBankStatementOcrSegmenter().segment(json);
    assertEquals(1, segs.size());
    assertArrayEquals(new int[] {1, 3}, segs.get(0));
  }

  @Test
  void ibanAndPeriodChangeStartsANewStatement() {
    String json =
        doc(
            new Page[] {
              page(1, IBAN_A, P1, 1, 2), page(2, IBAN_A, P1, 2, 2),
              page(3, IBAN_B, P2, 1, 2), page(4, IBAN_B, P2, 2, 2)
            });
    List<int[]> segs = new BgBankStatementOcrSegmenter().segment(json);
    assertEquals(2, segs.size());
    assertArrayEquals(new int[] {1, 2}, segs.get(0));
    assertArrayEquals(new int[] {3, 4}, segs.get(1));
  }

  @Test
  void paginationResetSplitsEvenWhenAccountAndPeriodMatch() {
    // Two statements for the SAME account+period: only the "1 от 2" reset distinguishes them.
    String json =
        doc(
            new Page[] {
              page(1, IBAN_A, P1, 1, 2), page(2, IBAN_A, P1, 2, 2),
              page(3, IBAN_A, P1, 1, 2), page(4, IBAN_A, P1, 2, 2)
            });
    List<int[]> segs = new BgBankStatementOcrSegmenter().segment(json);
    assertEquals(2, segs.size());
    assertArrayEquals(new int[] {1, 2}, segs.get(0));
    assertArrayEquals(new int[] {3, 4}, segs.get(1));
  }

  @Test
  void missingSignalsContinueTheCurrentStatement() {
    // Page 2 has no metadata/pagination → conservative continuation, not a split.
    String json = doc(new Page[] {page(1, IBAN_A, P1, 1, 2), page(2, null, null, 0, 0)});
    List<int[]> segs = new BgBankStatementOcrSegmenter().segment(json);
    assertEquals(1, segs.size());
    assertArrayEquals(new int[] {1, 2}, segs.get(0));
  }

  @Test
  void theRealFixtureIsOneStatementWhenPresent() throws Exception {
    Path fixture = testData("fixtures/bankstatements.azure-full.json");
    if (fixture == null || !Files.exists(fixture)) {
      // Виж бележката в BgBankStatementOcrParserTest: assumeTrue губи причината в отчета.
      BgSkip.because(
          "липсва неверсионираният фикстур test-data/fixtures/bankstatements.azure-full.json"
              + " (реален извлек от Azure OCR) — сегментирането на истински шестстраничен документ"
              + " НЕ е проверено.");
    }
    String json = new String(Files.readAllBytes(fixture), StandardCharsets.UTF_8);
    List<int[]> segs = new BgBankStatementOcrSegmenter().segment(json);
    assertEquals(1, segs.size(), "the sample PDF is a single 6-page statement");
    assertArrayEquals(new int[] {1, 6}, segs.get(0));
  }

  // --- helpers ---

  private static final class Page {
    final int number;
    final String iban;
    final String period;
    final int pagIndex;
    final int pagTotal;

    Page(int number, String iban, String period, int pagIndex, int pagTotal) {
      this.number = number;
      this.iban = iban;
      this.period = period;
      this.pagIndex = pagIndex;
      this.pagTotal = pagTotal;
    }
  }

  private static Page page(int n, String iban, String period, int idx, int total) {
    return new Page(n, iban, period, idx, total);
  }

  private static String doc(Page[] pages) {
    ObjectNode root = M.createObjectNode();
    ArrayNode pagesNode = root.putArray("pages");
    ArrayNode tables = root.putArray("tables");
    for (Page p : pages) {
      ObjectNode pn = pagesNode.addObject();
      pn.put("pageNumber", p.number);
      ArrayNode lines = pn.putArray("lines");
      if (p.pagIndex > 0) {
        lines.addObject().put("content", p.pagIndex + " от " + p.pagTotal);
      }
      if (p.iban != null || p.period != null) {
        tables.add(metaTable(p));
      }
    }
    return root.toString();
  }

  private static ObjectNode metaTable(Page p) {
    ObjectNode t = M.createObjectNode();
    t.put("rowCount", 2);
    t.put("columnCount", 2);
    t.putArray("boundingRegions").addObject().put("pageNumber", p.number);
    ArrayNode cells = t.putArray("cells");
    cell(cells, 0, 0, "IBAN");
    cell(cells, 0, 1, p.iban == null ? "" : p.iban);
    cell(cells, 1, 0, "месечно / monthly");
    cell(cells, 1, 1, p.period == null ? "" : p.period);
    return t;
  }

  private static void cell(ArrayNode cells, int r, int c, String content) {
    ObjectNode cell = cells.addObject();
    cell.put("rowIndex", r);
    cell.put("columnIndex", c);
    cell.put("content", content);
  }

  private static Path testData(String relative) {
    Path dir = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8 && dir != null; i++) {
      Path candidate = dir.resolve("test-data").resolve(relative);
      if (Files.exists(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    return null;
  }
}

package com.axelor.apps.bglocalization.service.bankstatement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits one uploaded bank-statement PDF (Azure {@code analyzeResult}) into <b>page ranges</b>, one
 * per detected statement — because a single PDF may hold several statements (different accounts or
 * periods). Conservative: a new statement starts only on a clear signal, otherwise a page continues
 * the current statement.
 *
 * <p>Boundary signals (any one, evaluated page-to-page):
 *
 * <ul>
 *   <li><b>IBAN change</b> — the per-page account IBAN differs from the previous page's;
 *   <li><b>Period change</b> — the per-page statement period differs;
 *   <li><b>Pagination reset</b> — a page numbered "1 от N" that is not the first page.
 * </ul>
 *
 * IBAN + period come from each page's 2-column account-metadata table (attributed by {@code
 * boundingRegions[].pageNumber}); pagination from a page line matching "N от M". A page that
 * carries none of these signals continues the current statement (so continuation pages stay
 * together). A PDF that is one statement yields exactly one range — preserving the single-statement
 * behaviour.
 */
public class BgBankStatementOcrSegmenter {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  // "N от M" pagination (Bulgarian "от" only — the "/" form collides with card-reference numbers).
  private static final Pattern PAGINATION =
      Pattern.compile("\\b(\\d{1,3})\\s*от\\s*(\\d{1,3})\\b", Pattern.UNICODE_CASE);

  private static final Pattern IBAN =
      Pattern.compile("([A-Z]{2}(?:[ ]?[0-9]){2}(?:[ ]?[A-Z0-9]){8,30})");

  /**
   * Return the page ranges (1-based, inclusive) of the detected statements, in document order.
   * Empty when the page structure cannot be read — the caller then treats the whole PDF as one
   * statement.
   */
  public List<int[]> segment(String analyzeResultJson) {
    List<int[]> ranges = new ArrayList<>();
    if (analyzeResultJson == null || analyzeResultJson.trim().isEmpty()) {
      return ranges;
    }
    JsonNode root;
    try {
      root = MAPPER.readTree(analyzeResultJson);
    } catch (Exception e) {
      return ranges;
    }
    int pageCount = pageCount(root);
    if (pageCount < 1) {
      return ranges;
    }

    // 1-based per-page descriptors.
    String[] ibanByPage = new String[pageCount + 1];
    String[] periodByPage = new String[pageCount + 1];
    Integer[] pagIndexByPage = new Integer[pageCount + 1];
    readPageMetadata(root, ibanByPage, periodByPage);
    readPagination(root, pagIndexByPage, pageCount);

    int start = 1;
    for (int p = 2; p <= pageCount; p++) {
      if (isBoundary(p, ibanByPage, periodByPage, pagIndexByPage)) {
        ranges.add(new int[] {start, p - 1});
        start = p;
      }
    }
    ranges.add(new int[] {start, pageCount});
    return ranges;
  }

  private static boolean isBoundary(int p, String[] iban, String[] period, Integer[] pagIndex) {
    boolean ibanChange = iban[p] != null && iban[p - 1] != null && !iban[p].equals(iban[p - 1]);
    boolean periodChange =
        period[p] != null && period[p - 1] != null && !period[p].equals(period[p - 1]);
    boolean pagReset = pagIndex[p] != null && pagIndex[p] == 1;
    return ibanChange || periodChange || pagReset;
  }

  // ---------------------------------------------------------------------------

  private static int pageCount(JsonNode root) {
    JsonNode pages = root.path("pages");
    if (pages.isArray() && pages.size() > 0) {
      int max = pages.size();
      for (JsonNode pg : pages) {
        max = Math.max(max, pg.path("pageNumber").asInt(0));
      }
      return max;
    }
    // Fallback: the greatest table page number.
    int max = 0;
    for (JsonNode t : root.path("tables")) {
      for (JsonNode br : t.path("boundingRegions")) {
        max = Math.max(max, br.path("pageNumber").asInt(0));
      }
    }
    return max;
  }

  /** IBAN + period per page, from the 2-column account-metadata table on each page. */
  private static void readPageMetadata(JsonNode root, String[] ibanByPage, String[] periodByPage) {
    for (JsonNode t : root.path("tables")) {
      if (t.path("columnCount").asInt(0) != 2) {
        continue;
      }
      int page = t.path("boundingRegions").path(0).path("pageNumber").asInt(0);
      if (page < 1 || page >= ibanByPage.length) {
        continue;
      }
      for (String[] row : grid(t)) {
        String label = norm(row[0]);
        String value = row[1] == null ? "" : row[1].trim();
        if (value.isEmpty()) {
          continue;
        }
        if (ibanByPage[page] == null && label.contains("iban")) {
          Matcher m = IBAN.matcher(value.toUpperCase(Locale.ROOT));
          if (m.find()) {
            ibanByPage[page] = m.group(1).replaceAll("\\s+", "");
          }
        } else if (periodByPage[page] == null
            && (label.contains("месечно")
                || label.contains("monthly")
                || label.contains("период")
                || label.contains("period"))) {
          periodByPage[page] = value.replaceAll("\\s+", " ").trim();
        }
      }
    }
  }

  /** Pagination index per page, from a page line matching "N от M" (index ≤ total). */
  private static void readPagination(JsonNode root, Integer[] pagIndexByPage, int pageCount) {
    for (JsonNode pg : root.path("pages")) {
      int page = pg.path("pageNumber").asInt(0);
      if (page < 1 || page >= pagIndexByPage.length) {
        continue;
      }
      for (JsonNode line : pg.path("lines")) {
        Matcher m = PAGINATION.matcher(line.path("content").asText(""));
        if (m.find()) {
          int idx = Integer.parseInt(m.group(1));
          int total = Integer.parseInt(m.group(2));
          if (idx >= 1 && total >= 1 && idx <= total) {
            pagIndexByPage[page] = idx;
            break;
          }
        }
      }
    }
  }

  private static String[][] grid(JsonNode table) {
    int rows = table.path("rowCount").asInt(0);
    int cols = table.path("columnCount").asInt(0);
    String[][] g = new String[Math.max(rows, 0)][Math.max(cols, 0)];
    for (String[] r : g) {
      java.util.Arrays.fill(r, "");
    }
    for (JsonNode c : table.path("cells")) {
      int ri = c.path("rowIndex").asInt(-1);
      int ci = c.path("columnIndex").asInt(-1);
      if (ri >= 0 && ri < rows && ci >= 0 && ci < cols) {
        g[ri][ci] = c.path("content").asText("");
      }
    }
    return g;
  }

  private static String norm(String s) {
    return s == null ? "" : s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
  }
}

package com.axelor.apps.bglocalization.service.bankstatement;

import com.axelor.apps.bglocalization.service.bankstatement.dto.BgBankMovement;
import com.axelor.apps.bglocalization.service.bankstatement.dto.BgParsedStatement;
import com.axelor.apps.bglocalization.service.bankstatement.dto.BgStatementRowError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses an Azure Document Intelligence {@code analyzeResult} JSON of a Bulgarian <b>PDF bank
 * statement</b> (Postbank / Пощенска банка "ОТЧЕТ ПО СМЕТКА") into the same {@link
 * BgParsedStatement} /{@link BgBankMovement} model the XLSX importer produces, so the shared
 * persist step ({@code BgBankStatementImportService.persistMovements}) builds the BankStatement
 * lines identically.
 *
 * <p>Azure returns each page's transaction grid as a 10-column table (Date | Value date | Doc/Ref |
 * Reason | Debit EUR | Debit BGN | Credit EUR | Credit BGN | Balance EUR | Balance BGN) with the
 * wrapped "Основание" already contained in one cell, plus a 2-column account-metadata table (IBAN /
 * Currency / period). Columns are matched by <b>header text</b> (not fixed index) for tolerance,
 * and the account currency chooses the EUR-vs-BGN amount columns. Reuses the pure decimal/date
 * helpers from {@link BgPostbankStatementParser}. Only the currently-known Postbank PDF layout is
 * supported; other banks/variants get their own parser.
 */
public class BgBankStatementOcrParser {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  // An IBAN embedded in a "Основание" / metadata value. Single spaces are tolerated between ALL
  // groups (Azure keeps the printed "BG 93 BPBI 8898 10 32573701"), then stripped by the caller.
  private static final Pattern IBAN =
      Pattern.compile("([A-Z]{2}(?:[ ]?[0-9]){2}(?:[ ]?[A-Z0-9]){8,30})");

  // Canonical transaction columns.
  private static final int OP_DATE = 0;
  private static final int VALUE_DATE = 1;
  private static final int DOC_REF = 2;
  private static final int REASON = 3;
  private static final int DEBIT_EUR = 4;
  private static final int DEBIT_BGN = 5;
  private static final int CREDIT_EUR = 6;
  private static final int CREDIT_BGN = 7;
  private static final int BAL_EUR = 8;
  private static final int BAL_BGN = 9;
  private static final int NCOLS = 10;

  /**
   * Parse the raw Azure {@code analyzeResult} JSON of a whole statement PDF (all pages = one
   * statement).
   */
  public BgParsedStatement parse(String analyzeResultJson) {
    return parse(analyzeResultJson, 1, Integer.MAX_VALUE);
  }

  /**
   * Parse only the pages {@code [fromPage..toPage]} of the Azure result into one statement — used
   * by the multi-statement path, which detects statement boundaries first and parses each page
   * range independently. Tables are attributed to a page via their {@code
   * boundingRegions[].pageNumber}; tables without a page number are always included. Never throws —
   * collects row errors.
   */
  public BgParsedStatement parse(String analyzeResultJson, int fromPage, int toPage) {
    BgParsedStatement st = new BgParsedStatement();
    if (analyzeResultJson == null || analyzeResultJson.trim().isEmpty()) {
      st.errors.add(new BgStatementRowError(0, "No OCR result."));
      return st;
    }
    JsonNode root;
    try {
      root = MAPPER.readTree(analyzeResultJson);
    } catch (Exception e) {
      st.errors.add(new BgStatementRowError(0, "Unreadable OCR JSON: " + e.getMessage()));
      return st;
    }
    JsonNode tables = root.path("tables");
    String content = root.path("content").asText("");

    readMetadata(tables, content, st, fromPage, toPage);

    if (tables.isArray()) {
      for (JsonNode t : tables) {
        if (!inRange(t, fromPage, toPage)) {
          continue;
        }
        String[][] grid = toGrid(t);
        if (isTransactionTable(grid)) {
          parseTransactionTable(grid, st);
        }
      }
    }

    // Recognized only when we have an account + at least one movement (else the caller marks the
    // statement "manual correction required" and keeps the PDF for review).
    st.recognized = st.iban != null && !st.movements.isEmpty();
    return st;
  }

  // ---------------------------------------------------------------------------
  // Metadata (IBAN / currency / period) from the 2-column account table + content fallback
  // ---------------------------------------------------------------------------

  private void readMetadata(
      JsonNode tables, String content, BgParsedStatement st, int fromPage, int toPage) {
    if (tables.isArray()) {
      for (JsonNode t : tables) {
        if (!inRange(t, fromPage, toPage)) {
          continue;
        }
        String[][] g = toGrid(t);
        if (g.length == 0 || g[0].length != 2) {
          continue; // metadata block is 2 columns (label | value)
        }
        for (String[] row : g) {
          String label = norm(row[0]);
          String value = row[1] == null ? "" : row[1].trim();
          if (value.isEmpty()) {
            continue;
          }
          if (st.iban == null && label.contains("iban")) {
            String iban = firstIban(value);
            if (iban != null) {
              st.iban = iban;
            }
          } else if (st.currencyCode == null
              && (label.contains("валута") || label.contains("currency"))) {
            st.currencyCode = value.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT);
          } else if (st.periodFrom == null
              && (label.contains("месечно")
                  || label.contains("monthly")
                  || label.contains("период")
                  || label.contains("period"))) {
            readPeriod(value, st);
          }
        }
      }
    }
    // Content is whole-document; only use it as an IBAN fallback for a FULL-document parse, never
    // for
    // a bounded page range (it would grab another statement's IBAN).
    boolean fullDoc = fromPage <= 1 && toPage == Integer.MAX_VALUE;
    if (st.iban == null && fullDoc) {
      st.iban = firstIban(content);
    }
    if (st.currencyCode == null) {
      st.currencyCode = "EUR"; // BG statements default; amount-column choice also defaults to EUR
    }
  }

  /** Whether a table belongs to the page range (a table with no page number is always included). */
  private static boolean inRange(JsonNode table, int fromPage, int toPage) {
    JsonNode br = table.path("boundingRegions");
    if (!br.isArray() || br.size() == 0) {
      return true; // unknown page → include
    }
    int page = br.get(0).path("pageNumber").asInt(0);
    return page == 0 || (page >= fromPage && page <= toPage);
  }

  private static void readPeriod(String value, BgParsedStatement st) {
    // "01.01.2026 - 31.01.2026" (endash/hyphen variants tolerated).
    String[] parts = value.split("[-–—]");
    if (parts.length >= 2) {
      LocalDate from = BgPostbankStatementParser.parseBgDate(parts[0]);
      LocalDate to = BgPostbankStatementParser.parseBgDate(parts[1]);
      if (from != null && to != null) {
        st.periodFrom = from;
        st.periodTo = to;
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Transaction table
  // ---------------------------------------------------------------------------

  /** A transaction grid: a header row that carries Reason + Debit/Credit + Balance labels. */
  private static boolean isTransactionTable(String[][] g) {
    if (g.length < 2 || g[0].length < 8) {
      return false;
    }
    String header = String.join(" ", g[0]).toLowerCase(Locale.ROOT);
    boolean reason = header.contains("основание") || header.contains("reason");
    boolean money =
        (header.contains("дебит") || header.contains("debit"))
            && (header.contains("кредит") || header.contains("credit"));
    boolean balance = header.contains("салдо") || header.contains("balance");
    return reason && money && balance;
  }

  private void parseTransactionTable(String[][] g, BgParsedStatement st) {
    int[] col = mapColumns(g[0]);
    boolean bgnAccount = "BGN".equalsIgnoreCase(st.currencyCode);
    int debitCol = bgnAccount ? col[DEBIT_BGN] : col[DEBIT_EUR];
    int creditCol = bgnAccount ? col[CREDIT_BGN] : col[CREDIT_EUR];
    int balCol = bgnAccount ? col[BAL_BGN] : col[BAL_EUR];

    for (int r = 1; r < g.length; r++) {
      String[] row = g[r];
      String opText = at(row, col[OP_DATE]);
      // Skip the "Общо / Total" summary row and any row with no operation date.
      String opNorm = norm(opText);
      if (opNorm.isEmpty() || opNorm.contains("общо") || opNorm.contains("total")) {
        continue;
      }

      java.math.BigDecimal debit = BgPostbankStatementParser.parseBgDecimal(at(row, debitCol));
      java.math.BigDecimal credit = BgPostbankStatementParser.parseBgDecimal(at(row, creditCol));
      boolean isDebit = debit != null && debit.signum() != 0;
      boolean isCredit = credit != null && credit.signum() != 0;
      if (!isDebit && !isCredit) {
        st.errors.add(new BgStatementRowError(r + 1, "Row with no debit/credit amount — skipped."));
        continue;
      }

      LocalDate opDate = resolveDate(opText, st);
      LocalDate valDate = resolveDate(at(row, col[VALUE_DATE]), st);
      if (opDate == null && valDate == null) {
        st.errors.add(new BgStatementRowError(r + 1, "Row with no usable date — skipped."));
        continue;
      }

      BgBankMovement m = new BgBankMovement();
      m.excelRow = r + 1;
      m.debit = isDebit;
      m.amount = (isDebit ? debit : credit).abs();
      m.operationDate = opDate != null ? opDate : valDate;
      m.valueDate = valDate != null ? valDate : opDate;
      m.reference = firstLine(at(row, col[DOC_REF]));
      m.description = collapse(at(row, col[REASON]));
      m.counterpartyAccount = firstIban(m.description); // counterparty IBAN embedded in Основание
      m.runningBalance = BgPostbankStatementParser.parseBgDecimal(at(row, balCol));
      st.movements.add(m);
    }
  }

  /**
   * Map the canonical column index → the actual column, by header text, with positional default.
   */
  private static int[] mapColumns(String[] header) {
    int[] col = new int[NCOLS];
    for (int i = 0; i < NCOLS; i++) {
      col[i] = i; // positional default (the layout is stable), overridden below when identifiable
    }
    for (int i = 0; i < header.length; i++) {
      String h = norm(header[i]);
      boolean eur = h.contains("eur");
      boolean bgn = h.contains("bgn");
      if ((h.contains("вальор") || h.contains("value"))) {
        col[VALUE_DATE] = i;
      } else if (h.contains("дата") || (h.contains("date") && !h.contains("value"))) {
        col[OP_DATE] = i;
      } else if (h.contains("док") || h.contains("реф") || h.contains("doc") || h.contains("ref")) {
        col[DOC_REF] = i;
      } else if (h.contains("основание") || h.contains("reason")) {
        col[REASON] = i;
      } else if ((h.contains("дебит") || h.contains("debit"))) {
        if (bgn) {
          col[DEBIT_BGN] = i;
        } else if (eur) {
          col[DEBIT_EUR] = i;
        }
      } else if ((h.contains("кредит") || h.contains("credit"))) {
        if (bgn) {
          col[CREDIT_BGN] = i;
        } else if (eur) {
          col[CREDIT_EUR] = i;
        }
      } else if ((h.contains("салдо") || h.contains("balance"))) {
        if (bgn) {
          col[BAL_BGN] = i;
        } else if (eur) {
          col[BAL_EUR] = i;
        }
      }
    }
    return col;
  }

  // ---------------------------------------------------------------------------
  // Dates: statement cells carry "dd.MM" (no year) or "dd.MM.yyyy"; resolve the year from the
  // period
  // ---------------------------------------------------------------------------

  /**
   * Parse a statement date cell. A full {@code dd.MM.yyyy} is parsed directly; a bare {@code dd.MM}
   * (the usual form) gets its year from the statement period, handling a December value date shown
   * in a January statement (previous year).
   */
  private LocalDate resolveDate(String raw, BgParsedStatement st) {
    if (raw == null || raw.trim().isEmpty()) {
      return null;
    }
    String token = raw.trim().split("\\s+")[0];
    LocalDate full = BgPostbankStatementParser.parseBgDate(token); // dd.MM.yyyy
    if (full != null) {
      return full;
    }
    String[] p = token.split("\\.");
    if (p.length < 2) {
      return null;
    }
    Integer day = toInt(p[0]);
    Integer month = toInt(p[1]);
    if (day == null || month == null) {
      return null;
    }
    int year = resolveYear(month, st);
    try {
      return LocalDate.of(year, month, day);
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Pick the year for a bare {@code dd.MM} from the statement period (falls back to the end year).
   */
  private static int resolveYear(int month, BgParsedStatement st) {
    LocalDate ref = st.periodTo != null ? st.periodTo : st.periodFrom;
    if (ref == null) {
      return LocalDate.now().getYear();
    }
    int base = ref.getYear();
    // A value date whose month is far AFTER the period-end month belongs to the previous year
    // (e.g. 31.12 shown in a January statement).
    if (st.periodTo != null && month > st.periodTo.getMonthValue() + 1) {
      return base - 1;
    }
    // A month far BEFORE the period-start month belongs to the next year (period spanning Dec→Jan).
    if (st.periodFrom != null && month + 1 < st.periodFrom.getMonthValue()) {
      return st.periodFrom.getYear() + 1;
    }
    return base;
  }

  // ---------------------------------------------------------------------------
  // Small helpers
  // ---------------------------------------------------------------------------

  /** Build a dense {@code [rowCount][columnCount]} string grid from an Azure table node. */
  private static String[][] toGrid(JsonNode table) {
    int rows = table.path("rowCount").asInt(0);
    int cols = table.path("columnCount").asInt(0);
    if (rows <= 0 || cols <= 0) {
      return new String[0][0];
    }
    String[][] g = new String[rows][cols];
    for (String[] r : g) {
      java.util.Arrays.fill(r, "");
    }
    JsonNode cells = table.path("cells");
    if (cells.isArray()) {
      for (JsonNode c : cells) {
        int ri = c.path("rowIndex").asInt(-1);
        int ci = c.path("columnIndex").asInt(-1);
        if (ri >= 0 && ri < rows && ci >= 0 && ci < cols) {
          g[ri][ci] = c.path("content").asText("");
        }
      }
    }
    return g;
  }

  private static String at(String[] row, int idx) {
    if (row == null || idx < 0 || idx >= row.length) {
      return "";
    }
    return row[idx] == null ? "" : row[idx].trim();
  }

  private static String firstLine(String s) {
    if (s == null) {
      return null;
    }
    String[] lines = s.split("[\\r\\n]+");
    for (String l : lines) {
      if (!l.trim().isEmpty()) {
        return l.trim();
      }
    }
    return null;
  }

  private static String collapse(String s) {
    return s == null ? null : s.replaceAll("\\s+", " ").trim();
  }

  private static String firstIban(String s) {
    if (s == null) {
      return null;
    }
    Matcher m = IBAN.matcher(s.toUpperCase(Locale.ROOT));
    if (m.find()) {
      return m.group(1).replaceAll("\\s+", "");
    }
    return null;
  }

  private static Integer toInt(String s) {
    try {
      return Integer.valueOf(s.trim());
    } catch (Exception e) {
      return null;
    }
  }

  private static String norm(String s) {
    return s == null ? "" : s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
  }
}

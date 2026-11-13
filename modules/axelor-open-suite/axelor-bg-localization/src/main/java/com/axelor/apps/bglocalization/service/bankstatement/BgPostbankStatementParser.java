package com.axelor.apps.bglocalization.service.bankstatement;

import com.axelor.apps.bglocalization.service.bankstatement.dto.BgBankMovement;
import com.axelor.apps.bglocalization.service.bankstatement.dto.BgParsedStatement;
import com.axelor.apps.bglocalization.service.bankstatement.dto.BgStatementRowError;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank-specific parser for the Bulgarian <b>Postbank / Юробанк</b> "Движения по сметка" XLSX
 * export. Turns the raw cell grid (produced by {@link BgXlsxGridReader}) into a {@link
 * BgParsedStatement}.
 *
 * <p>Everything bank-specific lives here so the reader and the import service stay reusable: header
 * names + aliases, Bulgarian date/decimal formats, and the {@code Тип}→debit/credit rule. Columns
 * are matched by header name (not fixed position), so minor column reordering is tolerated. Only
 * the currently-known Postbank layout is supported; other banks/variants get their own parser.
 */
public class BgPostbankStatementParser {

  // Canonical column keys.
  static final String OPERATION_TYPE = "operationType"; // Вид операция
  static final String TYPE = "type"; // Тип (Разход/Приход)
  static final String COUNTERPARTY_NAME = "counterpartyName"; // Наредител/Получател
  static final String COUNTERPARTY_ACCOUNT = "counterpartyAccount"; // От/към сметка
  static final String CHANNEL = "channel"; // Канал
  static final String AMOUNT_ORIG = "amountOrig"; // Сума
  static final String CURRENCY_OP = "currencyOp"; // Валута на операцията
  static final String AMOUNT_EUR = "amountEur"; // Сума в EUR
  static final String AMOUNT_BGN = "amountBgn"; // Сума в BGN (1.95583)
  static final String DESCRIPTION = "description"; // Основание
  static final String OPERATION_DATE = "operationDate"; // Дата на обработка
  static final String REFERENCE = "reference"; // Бордеро №
  static final String BORDERO_DATE = "borderoDate"; // Дата на бордеро
  static final String VALUE_DATE = "valueDate"; // Вальор
  static final String BALANCE = "balance"; // Салдо след операцията

  private static final DateTimeFormatter DOT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
  private static final DateTimeFormatter SLASH = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private static final DateTimeFormatter DASH = DateTimeFormatter.ofPattern("dd-MM-yyyy");

  private static final Pattern IBAN = Pattern.compile("([A-Z]{2}\\d{2}[A-Z0-9]{8,30})");
  private static final Pattern PARENS = Pattern.compile("\\(([^)]+)\\)");

  /**
   * Parse a full grid into a {@link BgParsedStatement}. Never throws on bad rows — collects errors.
   */
  public BgParsedStatement parse(List<String[]> grid) {
    BgParsedStatement st = new BgParsedStatement();
    if (grid == null || grid.isEmpty()) {
      st.errors.add(new BgStatementRowError(0, "Empty spreadsheet."));
      return st;
    }

    int headerRow = findHeaderRow(grid);
    if (headerRow < 0) {
      st.errors.add(new BgStatementRowError(0, "Postbank movement header row not found."));
      return st;
    }
    Map<String, Integer> col = buildColumnMap(grid.get(headerRow));

    // Account metadata sits above the movement header (label in one cell, value in the next).
    readMetadata(grid, headerRow, st);
    st.recognized =
        col.containsKey(TYPE) && (col.containsKey(AMOUNT_EUR) || col.containsKey(AMOUNT_ORIG));

    String amountKey = amountColumnFor(st.currencyCode, col);

    for (int r = headerRow + 1; r < grid.size(); r++) {
      String[] row = grid.get(r);
      if (isBlankRow(row)) {
        continue; // genuinely empty row — not a malformed movement
      }
      int excelRow = r + 1; // spreadsheet rows are 1-based
      String typeText = cell(row, col.get(TYPE));
      String amountText = cell(row, col.get(amountKey));
      // Only rows that carry movement content are treated as candidates.
      if (blank(typeText) && blank(amountText) && blank(cell(row, col.get(DESCRIPTION)))) {
        continue;
      }

      Boolean debit = directionIsDebit(typeText);
      if (debit == null) {
        st.errors.add(new BgStatementRowError(excelRow, "Unrecognized Тип '" + typeText + "'."));
        continue;
      }
      BigDecimal amount = parseBgDecimal(amountText);
      if (amount == null || amount.signum() == 0) {
        st.errors.add(
            new BgStatementRowError(excelRow, "Missing/zero amount ('" + amountText + "')."));
        continue;
      }
      LocalDate opDate = parseBgDate(cell(row, col.get(OPERATION_DATE)));
      LocalDate valDate = parseBgDate(cell(row, col.get(VALUE_DATE)));
      if (opDate == null && valDate == null) {
        st.errors.add(new BgStatementRowError(excelRow, "No usable operation/value date."));
        continue;
      }

      BgBankMovement m = new BgBankMovement();
      m.excelRow = excelRow;
      m.debit = debit;
      m.amount = amount.abs();
      m.type = trim(typeText);
      m.operationType = cell(row, col.get(OPERATION_TYPE));
      m.counterpartyName = cell(row, col.get(COUNTERPARTY_NAME));
      m.counterpartyAccount = cell(row, col.get(COUNTERPARTY_ACCOUNT));
      m.channel = cell(row, col.get(CHANNEL));
      m.currencyOfOperation = cell(row, col.get(CURRENCY_OP));
      m.description = cell(row, col.get(DESCRIPTION));
      m.operationDate = opDate != null ? opDate : valDate;
      m.valueDate = valDate != null ? valDate : opDate;
      m.reference = cell(row, col.get(REFERENCE));
      m.runningBalance = parseBgDecimal(cell(row, col.get(BALANCE)));
      st.movements.add(m);
    }
    return st;
  }

  // ---------------------------------------------------------------------------
  // Header / column mapping (by name, with known aliases)
  // ---------------------------------------------------------------------------

  /** Find the movement header row: the first row that carries several known header labels. */
  static int findHeaderRow(List<String[]> grid) {
    int best = -1;
    int bestScore = 0;
    for (int r = 0; r < grid.size(); r++) {
      int score = buildColumnMap(grid.get(r)).size();
      if (score > bestScore) {
        bestScore = score;
        best = r;
      }
    }
    return bestScore >= 5 ? best : -1;
  }

  /** Map canonical column key → column index, using normalized header text + known aliases. */
  static Map<String, Integer> buildColumnMap(String[] header) {
    Map<String, Integer> map = new HashMap<>();
    if (header == null) {
      return map;
    }
    for (int i = 0; i < header.length; i++) {
      String key = canonicalHeader(header[i]);
      if (key != null && !map.containsKey(key)) {
        map.put(key, i);
      }
    }
    return map;
  }

  /** Normalize a header cell to a canonical column key (null when unrecognized). */
  static String canonicalHeader(String raw) {
    String h = norm(raw);
    if (h.isEmpty()) {
      return null;
    }
    // Order matters: the more specific "сума в …" must be tested before the bare "сума".
    if (h.startsWith("сума в eur")) {
      return AMOUNT_EUR;
    }
    if (h.startsWith("сума в bgn")) {
      return AMOUNT_BGN;
    }
    if (h.equals("сума")) {
      return AMOUNT_ORIG;
    }
    if (h.startsWith("вид операц")) {
      return OPERATION_TYPE;
    }
    if (h.equals("тип")) {
      return TYPE;
    }
    if (h.startsWith("наредител") || h.contains("получател")) {
      return COUNTERPARTY_NAME;
    }
    if (h.startsWith("от/към") || h.startsWith("от / към") || h.contains("към сметка")) {
      return COUNTERPARTY_ACCOUNT;
    }
    if (h.equals("канал")) {
      return CHANNEL;
    }
    if (h.startsWith("валута")) {
      return CURRENCY_OP;
    }
    if (h.startsWith("основание")) {
      return DESCRIPTION;
    }
    if (h.startsWith("дата на обработка") || h.startsWith("дата на осчетоводяване")) {
      return OPERATION_DATE;
    }
    if (h.startsWith("дата на бордеро")) {
      return BORDERO_DATE;
    }
    if (h.startsWith("бордеро")) {
      return REFERENCE;
    }
    if (h.startsWith("вальор")) {
      return VALUE_DATE;
    }
    if (h.startsWith("салдо")) {
      return BALANCE;
    }
    return null;
  }

  /**
   * Choose the amount column matching the statement-account currency; EUR account → "Сума в EUR".
   */
  static String amountColumnFor(String currencyCode, Map<String, Integer> col) {
    String cur = norm(currencyCode);
    if (cur.equals("eur") && col.containsKey(AMOUNT_EUR)) {
      return AMOUNT_EUR;
    }
    if (cur.equals("bgn") && col.containsKey(AMOUNT_BGN)) {
      return AMOUNT_BGN;
    }
    if (col.containsKey(AMOUNT_EUR)) {
      return AMOUNT_EUR; // provided files are EUR accounts
    }
    return col.containsKey(AMOUNT_ORIG) ? AMOUNT_ORIG : AMOUNT_EUR;
  }

  // ---------------------------------------------------------------------------
  // Metadata (account IBAN + currency + holder)
  // ---------------------------------------------------------------------------

  private static void readMetadata(List<String[]> grid, int headerRow, BgParsedStatement st) {
    for (int r = 0; r < headerRow; r++) {
      String[] row = grid.get(r);
      for (int i = 0; i < row.length; i++) {
        String label = norm(row[i]);
        String value = nextNonBlank(row, i);
        if (label.startsWith("сметка") && value != null) {
          Matcher ib = IBAN.matcher(value.replaceAll("\\s+", ""));
          if (ib.find()) {
            st.iban = ib.group(1);
          }
          Matcher pr = PARENS.matcher(value);
          if (pr.find()) {
            st.currencyCode = pr.group(1).trim();
          }
        } else if (label.startsWith("банков клиент") && value != null) {
          st.accountHolder = value.trim();
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Direction / dates / decimals (pure, unit-tested)
  // ---------------------------------------------------------------------------

  /**
   * {@code Тип} → direction: Разход = debit (true), Приход = credit (false), else null (invalid).
   */
  public static Boolean directionIsDebit(String typeText) {
    String t = norm(typeText);
    if (t.startsWith("разход")) {
      return Boolean.TRUE;
    }
    if (t.startsWith("приход")) {
      return Boolean.FALSE;
    }
    return null;
  }

  /** Parse a Bulgarian date ("dd.MM.yyyy", optionally with a trailing time, or "dd/MM/yyyy"). */
  public static LocalDate parseBgDate(String raw) {
    if (blank(raw)) {
      return null;
    }
    String token = raw.trim().split("\\s+")[0]; // drop any trailing "HH:mm:ss"
    for (DateTimeFormatter f : new DateTimeFormatter[] {DOT, SLASH, DASH}) {
      try {
        return LocalDate.parse(token, f);
      } catch (Exception ignore) {
        // try the next format
      }
    }
    return null;
  }

  /**
   * Parse a Bulgarian decimal: tolerant of spaces/NBSP thousands and either ',' or '.' as decimal.
   */
  public static BigDecimal parseBgDecimal(String raw) {
    if (blank(raw)) {
      return null;
    }
    String s = raw.replace(' ', ' ').replace(' ', ' ').trim();
    s = s.replaceAll("[\\s ]", ""); // strip thousands spaces
    if (s.isEmpty()) {
      return null;
    }
    int lastComma = s.lastIndexOf(',');
    int lastDot = s.lastIndexOf('.');
    if (lastComma >= 0 && lastDot >= 0) {
      // Both present: the right-most separator is the decimal point; the other is thousands.
      if (lastComma > lastDot) {
        s = s.replace(".", "").replace(',', '.');
      } else {
        s = s.replace(",", "");
      }
    } else if (lastComma >= 0) {
      s = s.replace(',', '.'); // comma is the decimal separator
    }
    try {
      return new BigDecimal(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  // ---------------------------------------------------------------------------
  // Duplicate key + balance validation (pure, unit-tested)
  // ---------------------------------------------------------------------------

  /**
   * Duplicate key for a movement. Primary form uses IBAN + Бордеро № + value date + signed amount.
   * When Бордеро № is missing, falls back to IBAN + operation/value date + signed amount +
   * normalized description + counterparty account/name — so overlapping files never double-import.
   */
  public static String duplicateKey(String iban, BgBankMovement m) {
    String acc = norm(iban);
    String signed = m.signedAmount().stripTrailingZeros().toPlainString();
    String vd = m.valueDate != null ? m.valueDate.toString() : "";
    if (!blank(m.reference)) {
      return String.join("|", "REF", acc, norm(m.reference), vd, signed);
    }
    String od = m.operationDate != null ? m.operationDate.toString() : "";
    String cp = norm(firstNonBlank(m.counterpartyAccount, m.counterpartyName));
    return String.join("|", "FALLBACK", acc, od, vd, signed, norm(m.description), cp);
  }

  /** Outcome of the running-balance chain validation. */
  public enum BalanceCheck {
    VALIDATED,
    MISMATCH,
    NOT_AVAILABLE
  }

  private static final BigDecimal BALANCE_TOLERANCE = new BigDecimal("0.01");

  /**
   * Validate the per-row running balance ("Салдо след операцията") as a <b>chain</b>: for every
   * pair of adjacent rows, the difference between their running balances must equal the signed
   * amount of the transaction that sits between them. This directly checks each row's amount +
   * debit/credit sign against the bank's own running total, so a wrong extraction breaks the chain.
   *
   * <p>The statement is listed in running-balance order (Postbank lists newest-first), so we accept
   * either direction: newest-first (each row's balance = the next/older row's balance + this row's
   * signed amount) or oldest-first (the reverse). It does NOT sort by date — deriving boundaries
   * from dates is unreliable when several transactions share a day (only the date, not the time, is
   * available). Returns NOT_AVAILABLE when a running balance is missing (nothing to check against)
   * or there is a single row (no pair to chain).
   */
  public static BalanceCheck validateBalances(List<BgBankMovement> movements) {
    if (movements == null || movements.size() < 2) {
      return BalanceCheck.NOT_AVAILABLE;
    }
    for (BgBankMovement m : movements) {
      if (m.runningBalance == null) {
        return BalanceCheck.NOT_AVAILABLE;
      }
    }
    boolean newestFirst = true; // balance[i] == balance[i+1] + signed[i]
    boolean oldestFirst = true; // balance[i+1] == balance[i] + signed[i+1]
    for (int i = 0; i < movements.size() - 1; i++) {
      BgBankMovement cur = movements.get(i);
      BgBankMovement next = movements.get(i + 1);
      if (!approxEqual(cur.runningBalance, next.runningBalance.add(cur.signedAmount()))) {
        newestFirst = false;
      }
      if (!approxEqual(next.runningBalance, cur.runningBalance.add(next.signedAmount()))) {
        oldestFirst = false;
      }
    }
    return (newestFirst || oldestFirst) ? BalanceCheck.VALIDATED : BalanceCheck.MISMATCH;
  }

  private static boolean approxEqual(BigDecimal a, BigDecimal b) {
    return a.subtract(b).abs().compareTo(BALANCE_TOLERANCE) <= 0;
  }

  // ---------------------------------------------------------------------------
  // Small helpers
  // ---------------------------------------------------------------------------

  private static String cell(String[] row, Integer idx) {
    if (idx == null || row == null || idx < 0 || idx >= row.length) {
      return null;
    }
    String v = row[idx];
    return v == null ? null : v.trim();
  }

  private static String nextNonBlank(String[] row, int from) {
    for (int i = from + 1; i < row.length; i++) {
      if (!blank(row[i])) {
        return row[i].trim();
      }
    }
    return null;
  }

  private static boolean isBlankRow(String[] row) {
    if (row == null) {
      return true;
    }
    for (String c : row) {
      if (!blank(c)) {
        return false;
      }
    }
    return true;
  }

  static String norm(String s) {
    return s == null ? "" : s.replace(' ', ' ').replaceAll("\\s+", " ").trim().toLowerCase();
  }

  private static String trim(String s) {
    return s == null ? null : s.trim();
  }

  private static boolean blank(String s) {
    return s == null || s.trim().isEmpty();
  }

  private static String firstNonBlank(String a, String b) {
    return !blank(a) ? a : (b == null ? "" : b);
  }
}

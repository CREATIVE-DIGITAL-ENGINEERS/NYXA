package com.axelor.apps.aiocr.service.ocr;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure classifier that decides — from the OCR full text — whether a document is a <b>bank
 * statement</b> that should be routed to Bank Statements instead of a Supplier Invoice or a Move.
 *
 * <p>Detection is by <b>document title</b>, inspected only in the document's heading region (the
 * first {@link #HEADING_LINE_LIMIT} non-empty OCR lines), never in the body: a real statement
 * prints a heading such as {@code ОТЧЕТ ПО СМЕТКА / ACCOUNT STATEMENT} (Postbank) or {@code
 * Движения по сметка}. Those phrases are specific enough to decide on their own, with priority over
 * invoice detection — a statement's transaction narratives routinely quote {@code Фактура} / {@code
 * Ф-ра} (e.g. a transfer that pays an invoice), and that must NOT pull the whole statement into the
 * invoice flow.
 *
 * <p>The bare word {@code Извлечение} ("extract/statement") is common Bulgarian vocabulary and is
 * also used as the title of ordinary billing/collection notices (e.g. a leasing or insurance
 * company's premium due-notice) that are not bank statements at all. It is therefore treated as a
 * <b>weak</b> marker: a heading match only counts when corroborated by at least two independent
 * structural bank-statement signals (an account/IBAN heading, a statement period, an
 * opening/closing balance, or a transaction ledger) found anywhere in the document. Without that
 * corroboration this classifier returns {@code false} and leaves the document to the downstream
 * invoice/move classifier — it never guesses Invoice vs Move itself.
 *
 * <p>Within the heading region, whichever candidate title — a strong marker, a standalone {@code
 * фактура}, or the weak {@code извлечение} — occurs <b>first</b> decides: an invoice heading
 * followed much later (still inside the heading window) by an incidental strong-sounding phrase in
 * payment-instruction boilerplate (e.g. "вижте движения по сметка") must not flip a real invoice
 * into a statement, while a genuine statement title followed by a transaction narrative quoting
 * "Фактура"/"Ф-ра" must still be recognized as a statement. There is no whole-document invoice
 * veto: that "Фактура" mention deep in a statement's transactions must not stop it from being
 * recognized as a statement either.
 *
 * <p>Case-insensitive, whitespace-normalized, no dependencies (fully unit-testable).
 */
public final class BgBankStatementOcrClassifier {

  private BgBankStatementOcrClassifier() {}

  /** Non-empty OCR lines inspected for a document title; the body beyond this is never checked. */
  private static final int HEADING_LINE_LIMIT = 35;

  /** Standalone "фактура" in the heading — a real invoice title, always wins. */
  private static final Pattern FAKTURA_HEADING = Pattern.compile("(?<!\\p{L})фактура(?!\\p{L})");

  /**
   * Strong statement titles: specific enough on their own, no corroboration needed. None of these
   * phrases occur on an actual supplier invoice.
   */
  private static final Pattern STRONG_TITLE =
      Pattern.compile(
          "(?<!\\p{L})(?:"
              + "отчет по сметка" // Postbank / Пощенска банка PDF heading (Cyrillic)
              + "|account statement" // its English half (bilingual header)
              + "|bank statement" // generic English heading
              + "|движения по сметка" // Postbank "account movements" (also the XLSX export title)
              + "|извлечение по сметка" // "statement of account" — as specific as the phrases above
              + ")(?!\\p{L})");

  /**
   * Weak statement title: the bare generic word (not qualified by "по сметка", which is {@link
   * #STRONG_TITLE}), needs structural corroboration (see class doc).
   */
  private static final Pattern WEAK_TITLE = Pattern.compile("(?<!\\p{L})извлечение(?!\\p{L})");

  // --- Structural corroboration signals for the weak "извлечение" title -----------------------

  /** Checked in the heading region only — a real statement declares its account near the title. */
  private static final Pattern ACCOUNT_HEADING =
      Pattern.compile("(?<!\\p{L})iban(?!\\p{L})|сметка\\s*(?:№|no\\.?|n:)?\\s*\\d");

  private static final Pattern STATEMENT_PERIOD =
      Pattern.compile("период на извлечени|отчетен период|за периода|statement period");

  private static final Pattern BALANCE =
      Pattern.compile("(?<!\\p{L})(?:салдо|наличност|balance)(?!\\p{L})");

  private static final Pattern CYR_DEBIT = Pattern.compile("(?<!\\p{L})дебит(?!\\p{L})");
  private static final Pattern CYR_CREDIT = Pattern.compile("(?<!\\p{L})кредит(?!\\p{L})");
  private static final Pattern EN_DEBIT = Pattern.compile("(?<!\\p{L})debit(?!\\p{L})");
  private static final Pattern EN_CREDIT = Pattern.compile("(?<!\\p{L})credit(?!\\p{L})");
  private static final Pattern VALUE_DATE = Pattern.compile("(?<!\\p{L})вальор(?!\\p{L})");

  /**
   * @param rawText the full OCR text ({@code analyzeResult.content}); callers must handle
   *     extraction failure BEFORE calling this.
   * @return {@code true} when the text carries a bank-statement title, evaluated on the heading
   *     region only. Null/blank → {@code false}.
   */
  public static boolean isBankStatement(String rawText) {
    if (rawText == null) {
      return false;
    }
    String heading = headingRegion(rawText);
    if (heading.isEmpty()) {
      return false;
    }

    int strongPos = firstMatchStart(STRONG_TITLE, heading);
    int fakturaPos = firstMatchStart(FAKTURA_HEADING, heading);
    int weakPos = firstMatchStart(WEAK_TITLE, heading);

    if (strongPos < 0 && fakturaPos < 0 && weakPos < 0) {
      return false; // no candidate title at all
    }
    if (strongPos >= 0 && isEarliestOrTied(strongPos, fakturaPos, weakPos)) {
      // The earliest candidate title is a specific bank-statement phrase — it wins outright, even
      // when a later "Фактура" is quoted in a transaction narrative.
      return true;
    }
    if (fakturaPos >= 0 && isEarliestOrTied(fakturaPos, weakPos)) {
      // The earliest candidate title is a real invoice heading — never re-route this document on
      // a later, weaker "извлечение"/strong-phrase mention.
      return false;
    }
    // The earliest (and only remaining) candidate is the weak, bare "извлечение" title.
    String fullText = normalize(rawText);
    return countStructuralSignals(heading, fullText) >= 2;
  }

  /**
   * @return true if {@code candidate} is present and no smaller non-negative position exists.
   */
  private static boolean isEarliestOrTied(int candidate, int... others) {
    for (int other : others) {
      if (other >= 0 && other < candidate) {
        return false;
      }
    }
    return true;
  }

  private static int firstMatchStart(Pattern pattern, String text) {
    Matcher matcher = pattern.matcher(text);
    return matcher.find() ? matcher.start() : -1;
  }

  /** The first {@link #HEADING_LINE_LIMIT} non-empty OCR lines, normalized and lower-cased. */
  private static String headingRegion(String rawText) {
    StringBuilder heading = new StringBuilder();
    int nonEmptyLines = 0;
    for (String line : rawText.split("\\r?\\n")) {
      if (line.trim().isEmpty()) {
        continue;
      }
      if (nonEmptyLines >= HEADING_LINE_LIMIT) {
        break;
      }
      heading.append(line).append(' ');
      nonEmptyLines++;
    }
    return normalize(heading.toString());
  }

  private static String normalize(String text) {
    return text.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT).trim();
  }

  private static int countStructuralSignals(String heading, String fullText) {
    int signals = 0;
    if (ACCOUNT_HEADING.matcher(heading).find()) {
      signals++;
    }
    if (STATEMENT_PERIOD.matcher(fullText).find()) {
      signals++;
    }
    if (BALANCE.matcher(fullText).find()) {
      signals++;
    }
    if (hasTransactionLedger(fullText)) {
      signals++;
    }
    return signals;
  }

  private static boolean hasTransactionLedger(String fullText) {
    boolean bgLedger = CYR_DEBIT.matcher(fullText).find() && CYR_CREDIT.matcher(fullText).find();
    boolean enLedger = EN_DEBIT.matcher(fullText).find() && EN_CREDIT.matcher(fullText).find();
    return bgLedger || enLedger || VALUE_DATE.matcher(fullText).find();
  }
}

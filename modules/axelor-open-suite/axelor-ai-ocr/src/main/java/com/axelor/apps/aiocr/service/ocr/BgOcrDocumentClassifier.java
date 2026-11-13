package com.axelor.apps.aiocr.service.ocr;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure classifier that decides, from the OCR full text, the <b>document type</b> — INVOICE,
 * NON_INVOICE, or UNCERTAIN. This is deliberately independent of OCR field confidence: a
 * low-quality scan of a real invoice is still an invoice, and a crisp scan of a contract is still
 * not one.
 *
 * <ul>
 *   <li>NON_INVOICE — a <b>strong</b> bank/payment marker is present (bank statement, payment
 *       order, card transaction, cash withdrawal, POS purchase). These win even over a "фактура"
 *       mention, because such documents quote "фактура &lt;number&gt;" in transaction narratives.
 *   <li>INVOICE — no strong bank marker, but the text contains the standalone Bulgarian word
 *       "фактура" (e.g. "ФАКТУРА", "Данъчна фактура"); a word that merely contains those letters
 *       ("фактурата") does not count.
 *   <li>NON_INVOICE — no "фактура", but a weak non-invoice marker is present (contract, receipt,
 *       quote, …).
 *   <li>UNCERTAIN — neither signal: the type cannot be decided, so a human should pick.
 * </ul>
 *
 * <p>Within the heading region, when both a "фактура" heading and a {@code
 * STRONG_NON_INVOICE_TITLE} phrase are present, whichever occurs <b>first</b> decides — an early
 * genuine invoice heading is never overturned by a later, incidental strong-title phrase still
 * inside the same heading window (and vice versa), mirroring {@code BgBankStatementOcrClassifier}'s
 * earliest-candidate-wins rule. {@code STRONG_NON_INVOICE_NARRATIVE} is unaffected by this ordering
 * — it is checked over the whole document, independent of heading position, and always wins (see
 * its own doc).
 *
 * <p>Case-insensitive, whitespace-normalized, no dependencies (fully unit-testable).
 */
public final class BgOcrDocumentClassifier {

  private static final int F = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

  /** Non-empty OCR lines inspected for a document title; the body beyond this is never checked. */
  private static final int HEADING_LINE_LIMIT = 35;

  /** Standalone "фактура" (not part of a longer word). */
  private static final Pattern FAKTURA = Pattern.compile("(?<!\\p{L})фактура(?!\\p{L})", F);

  /**
   * <b>Strong</b> non-invoice document TITLES — evaluated ONLY in the heading region (the first
   * {@link #HEADING_LINE_LIMIT} non-empty OCR lines), never in the body. Each of these names a
   * specific non-invoice document TYPE (a statement, a payment/transfer order), and — exactly like
   * a bare "извлечение" — the same wording routinely turns up as ordinary boilerplate deep in a
   * real invoice's own body: a "ways to pay" paragraph ("...с платежно нареждане при ОББ...") is
   * common on Bulgarian invoices and premium notices and must NOT flip them into a Move. A real
   * Payment/Transfer Order or account statement prints this as its own title near the top, so
   * restricting the match to the heading loses no genuine non-invoice document.
   */
  private static final Pattern STRONG_NON_INVOICE_TITLE =
      Pattern.compile(
          "(?<!\\p{L})(?:"
              + "извлечение по сметка" // statement of account (specific, not the bare word)
              + "|движения по сметка" // account movements
              + "|платежно нареждане" // payment order
              + "|нареждане за (?:кредитен )?превод" // transfer order
              + ")(?!\\p{L})",
          F);

  /**
   * <b>Strong</b> non-invoice TRANSACTION NARRATIVE markers — evaluated anywhere in the document,
   * no heading restriction. Unlike the titles above, these only ever occur inside a genuine bank
   * statement's own transaction lines (e.g. "Транзакция с ДК ...", "Покупка на ПОС ...", "Теглене
   * на пари ..."); no ordinary invoice narrates its own card transactions or cash withdrawals, so
   * there is no boilerplate-collision risk and no heading restriction is needed here.
   */
  private static final Pattern STRONG_NON_INVOICE_NARRATIVE =
      Pattern.compile(
          "(?<!\\p{L})(?:"
              + "транзакция с дк" // (debit) card transaction line
              + "|теглене на пари" // cash withdrawal
              + "|покупка на пос" // POS purchase
              + ")(?!\\p{L})",
          F);

  /**
   * <b>Weak</b> non-invoice markers (standalone words/phrases). Only consulted when there is no
   * "фактура", so a real invoice (which says "фактура") is never sent here.
   */
  private static final Pattern NON_INVOICE =
      Pattern.compile(
          "(?<!\\p{L})(?:"
              + "договор" // contract
              + "|разписка" // receipt
              + "|квитанция" // receipt
              + "|оферта" // quote/offer
              + "|касов бон" // cash receipt
              + "|фискален бон" // fiscal receipt
              + ")(?!\\p{L})",
          F);

  private BgOcrDocumentClassifier() {}

  /** The three-way document classification. */
  public enum Classification {
    INVOICE,
    NON_INVOICE,
    UNCERTAIN
  }

  /**
   * @param rawText the full OCR text ({@code analyzeResult.content}). Callers must handle
   *     extraction failure BEFORE calling this.
   * @return INVOICE / NON_INVOICE / UNCERTAIN. Null/blank text → UNCERTAIN (nothing to go on).
   */
  public static Classification classify(String rawText) {
    if (rawText == null) {
      return Classification.UNCERTAIN;
    }
    String normalized = rawText.replaceAll("\\s+", " ").trim();
    if (normalized.isEmpty()) {
      return Classification.UNCERTAIN;
    }
    // Transaction-narrative markers are inherently unambiguous (only ever occur inside a real bank
    // statement's own transaction lines) — checked first, over the whole document, independent of
    // heading position.
    if (STRONG_NON_INVOICE_NARRATIVE.matcher(normalized).find()) {
      return Classification.NON_INVOICE;
    }
    // Titles are only trusted in the heading. When both a "фактура" heading and a strong
    // non-invoice title are present there, whichever occurs FIRST decides — an early genuine
    // invoice heading must not be overturned by a later, incidental title phrase still inside the
    // same heading window (and a genuine non-invoice title must not be overturned by a later,
    // incidental "фактура" reference either).
    String heading = headingRegion(rawText);
    int titlePos = firstMatchStart(STRONG_NON_INVOICE_TITLE, heading);
    int fakturaPos = firstMatchStart(FAKTURA, heading);
    if (titlePos >= 0 && (fakturaPos < 0 || titlePos <= fakturaPos)) {
      return Classification.NON_INVOICE;
    }
    if (fakturaPos >= 0) {
      return Classification.INVOICE;
    }
    if (NON_INVOICE.matcher(normalized).find()) {
      return Classification.NON_INVOICE;
    }
    return Classification.UNCERTAIN;
  }

  private static int firstMatchStart(Pattern pattern, String text) {
    Matcher matcher = pattern.matcher(text);
    return matcher.find() ? matcher.start() : -1;
  }

  /** The first {@link #HEADING_LINE_LIMIT} non-empty OCR lines, whitespace-normalized. */
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
    return heading.toString().replaceAll("\\s+", " ").trim();
  }

  /** Backwards-compatible helper: true only when the document is classified as an INVOICE. */
  public static boolean isInvoice(String rawText) {
    return classify(rawText) == Classification.INVOICE;
  }
}

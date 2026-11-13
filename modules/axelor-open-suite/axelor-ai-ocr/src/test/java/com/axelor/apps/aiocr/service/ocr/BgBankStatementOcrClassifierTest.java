package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the title-marker bank-statement classifier. Detection is by document heading and
 * has priority over invoice detection: a statement is recognized even when its transaction
 * descriptions quote "Фактура"/"Ф-ра" (there is no invoice-word veto). A plain invoice with no
 * statement heading is not a statement.
 */
class BgBankStatementOcrClassifierTest {

  @Test
  void postbankBilingualHeadingIsAStatementEvenWithInvoiceWordsInLines() {
    String text =
        "ОТЧЕТ ПО СМЕТКА / ACCOUNT STATEMENT\n"
            + "IBAN BG93 BPBI 8898 10 32573701  Currency EUR\n"
            + "06.01 STSABGSF BG 03 STSA ... NAVIA PROPERTIES LTD ФАКТУРА НОМЕР 66\n"
            + "Ф-РА 0000000077 01.04.2026\n";
    assertTrue(BgBankStatementOcrClassifier.isBankStatement(text));
  }

  @Test
  void recognizesTheOtherStatementHeadings() {
    assertTrue(BgBankStatementOcrClassifier.isBankStatement("Bank Statement\nsome lines"));
    assertTrue(BgBankStatementOcrClassifier.isBankStatement("ACCOUNT STATEMENT period ..."));
    assertTrue(BgBankStatementOcrClassifier.isBankStatement("Извлечение по сметка\n..."));
    assertTrue(BgBankStatementOcrClassifier.isBankStatement("Движения по сметка\n..."));
  }

  @Test
  void isCaseInsensitive() {
    assertTrue(BgBankStatementOcrClassifier.isBankStatement("отчет по сметка ..."));
    assertTrue(BgBankStatementOcrClassifier.isBankStatement("аccount statement".replace("а", "a")));
  }

  @Test
  void aPlainInvoiceWithNoStatementHeadingIsNotAStatement() {
    assertFalse(
        BgBankStatementOcrClassifier.isBankStatement(
            "ФАКТУРА № 0000000123\nДоставчик ЕООД\nСума за плащане 120.00"));
    assertFalse(BgBankStatementOcrClassifier.isBankStatement(""));
    assertFalse(BgBankStatementOcrClassifier.isBankStatement(null));
  }

  /**
   * Regression coverage for a leasing/insurance-company collection notice (ОББ Интерлийз): its
   * heading is the bare, ambiguous word "Извлечение", but it is a genuine invoice-like billing
   * document, never a bank statement.
   */
  private static String invoiceHeadingWithBareWordInBody(String bodyLine) {
    return "ФАКТУРА № 0121449096\n"
        + "Доставчик ЕАД, ЕИК 831642181\n"
        + "Получател: ТЕСТЕКС ООД\n"
        + "Дата на издаване: 15.08.2026\n"
        + "Обща сума за плащане: 63.86 EUR\n"
        + bodyLine
        + "\n";
  }

  @Test
  void invoiceHeadingWithIzvlechenieInBodyIsNotAStatement() {
    assertFalse(
        BgBankStatementOcrClassifier.isBankStatement(
            invoiceHeadingWithBareWordInBody("Настоящото приложение — извлечение по услуги.")));
  }

  @Test
  void invoiceHeadingWithDvijeniaPoSmetkaInPaymentTextIsNotAStatement() {
    assertFalse(
        BgBankStatementOcrClassifier.isBankStatement(
            invoiceHeadingWithBareWordInBody(
                "Плащане по банков път — вижте движения по сметка за детайли.")));
  }

  @Test
  void realIzvlechenieHeadingWithoutStructuralSignalsIsNotAStatement() {
    // Mirrors the actual ОББ Интерлийз "Извлечение" premium due-notice: bare title, no фактура
    // anywhere, but also no account/IBAN heading, period, balance or ledger — just one billed
    // item. Weak marker alone must not route this to Bank Statements; downstream decides.
    String text =
        "ОББ ИНТЕРЛИЙЗ ЕАД\n"
            + "Получател: ТЕСТЕКС ООД\n"
            + "ИЗВЛЕЧЕНИЕ\n"
            + "№ 0013413643\n"
            + "Дата: 21.08.2026\n"
            + "Основание Валута Сума\n"
            + "Застрахователна премия за възстановяване КАСКО EUR 171.25\n"
            + "Брутна сума: 171.25 EUR\n";
    assertFalse(BgBankStatementOcrClassifier.isBankStatement(text));
  }

  @Test
  void realIzvlechenieHeadingWithTwoStructuralSignalsIsAStatement() {
    // Same bare title, but now corroborated by an account/IBAN heading AND a stated period —
    // this is what a real bank statement titled just "Извлечение" looks like.
    String text =
        "ИЗВЛЕЧЕНИЕ\n"
            + "IBAN BG80UBBS80021012345678\n"
            + "Период на извлечението: 01.08.2026 - 31.08.2026\n"
            + "Дата Основание Сума\n"
            + "01.08 Такса за обслужване 5.00\n";
    assertTrue(BgBankStatementOcrClassifier.isBankStatement(text));
  }

  @Test
  void bankStatementMentioningFakturaOnlyInTransactionRowsRemainsAStatement() {
    StringBuilder text = new StringBuilder("ОТЧЕТ ПО СМЕТКА / ACCOUNT STATEMENT\n");
    text.append("IBAN BG93 BPBI 8898 10 32573701  Currency EUR\n");
    for (int i = 0; i < 40; i++) {
      text.append("0").append(i % 10).append(".01 transaction narrative line filler text\n");
    }
    text.append("06.01 STSABGSF BG 03 STSA ... NAVIA PROPERTIES LTD ФАКТУРА НОМЕР 66\n");
    text.append("Ф-РА 0000000077 01.04.2026\n");
    assertTrue(BgBankStatementOcrClassifier.isBankStatement(text.toString()));
  }

  @Test
  void headingDetectionToleratesCasingWhitespaceAndOcrLineBreaks() {
    assertTrue(
        BgBankStatementOcrClassifier.isBankStatement(
            "  Отчет   по\n\n сметка  \n\nsome    lines  \n"));
    assertTrue(BgBankStatementOcrClassifier.isBankStatement("ОтЧеТ пО сМеТкА\r\nIBAN ...\r\n"));
    assertFalse(
        BgBankStatementOcrClassifier.isBankStatement("\n\n   \nФАКТУРА № 5\n\n\nСума 10.00\n\n"));
  }
}

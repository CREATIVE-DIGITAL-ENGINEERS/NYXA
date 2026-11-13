package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ocr.BgOcrDocumentClassifier.Classification;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure document classifier that drives Invoice-vs-Move routing: text with the
 * standalone word "фактура" → invoice; anything else → non-invoice (Move).
 */
class BgOcrDocumentClassifierTest {

  @Test
  void faktoraWithNumberIsInvoice() {
    assertTrue(BgOcrDocumentClassifier.isInvoice("Фактура №123\nОТП Лизинг ЕООД"));
  }

  @Test
  void upperCaseTaxInvoiceIsInvoice() {
    assertTrue(BgOcrDocumentClassifier.isInvoice("ДАНЪЧНА ФАКТУРА\nЕИК 131391369"));
  }

  @Test
  void unrelatedDocumentIsNonInvoice() {
    // No "фактура" → routed to a Move, not an Invoice.
    assertFalse(
        BgOcrDocumentClassifier.isInvoice("Приходен касов ордер\nПлатежно нареждане\nСума 100"));
  }

  @Test
  void nullAndBlankAreNonInvoice() {
    assertFalse(BgOcrDocumentClassifier.isInvoice(null));
    assertFalse(BgOcrDocumentClassifier.isInvoice(""));
    assertFalse(BgOcrDocumentClassifier.isInvoice("   \n\t "));
  }

  @Test
  void wordMerelyContainingLettersDoesNotMatch() {
    // "фактурата"/"фактуриране" contain the letters but are not the standalone word.
    assertFalse(BgOcrDocumentClassifier.isInvoice("плащане по фактурата предстои"));
    assertFalse(BgOcrDocumentClassifier.isInvoice("процес по фактуриране на услугите"));
  }

  @Test
  void standaloneWordFoundAcrossWhitespaceAndPunctuation() {
    assertTrue(BgOcrDocumentClassifier.isInvoice("Оригинал\n\n  фактура  \n№ 7"));
    assertTrue(BgOcrDocumentClassifier.isInvoice("(фактура)"));
  }

  // --- Three-way classification (independent of OCR field confidence) ---

  @Test
  void classifyInvoice() {
    assertEquals(Classification.INVOICE, BgOcrDocumentClassifier.classify("ДАНЪЧНА ФАКТУРА №7"));
  }

  @Test
  void classifyNonInvoiceByMarker() {
    // Clear non-invoice markers → NON_INVOICE (Move). No "фактура" present.
    assertEquals(
        Classification.NON_INVOICE,
        BgOcrDocumentClassifier.classify("ИЗВЛЕЧЕНИЕ по сметка\nСалдо 100"));
    assertEquals(
        Classification.NON_INVOICE, BgOcrDocumentClassifier.classify("Движения по сметка BG93..."));
    assertEquals(
        Classification.NON_INVOICE,
        BgOcrDocumentClassifier.classify("Платежно нареждане\nСума 250"));
    assertEquals(
        Classification.NON_INVOICE, BgOcrDocumentClassifier.classify("ДОГОВОР за наем №5"));
  }

  @Test
  void classifyUncertainWhenNoClearSignal() {
    // No standalone "фактура" and no non-invoice marker → the human decides.
    assertEquals(
        Classification.UNCERTAIN,
        BgOcrDocumentClassifier.classify("плащане по фактурата предстои"));
    assertEquals(
        Classification.UNCERTAIN, BgOcrDocumentClassifier.classify("ОТП Лизинг ЕООД\nСума 1000"));
    assertEquals(Classification.UNCERTAIN, BgOcrDocumentClassifier.classify(null));
    assertEquals(Classification.UNCERTAIN, BgOcrDocumentClassifier.classify("   "));
  }

  @Test
  void invoiceWinsOverWeakNonInvoiceMarker() {
    // A real invoice that also mentions a contract is still an INVOICE.
    assertEquals(
        Classification.INVOICE,
        BgOcrDocumentClassifier.classify("ФАКТУРА към ДОГОВОР №5\nЕИК 131391369"));
  }

  @Test
  void bankStatementQuotingFakturaIsNonInvoice() {
    // Real BG93 case: a Postbank statement whose transfer lines quote "ФАКТУРА <number>".
    // Strong bank markers must win over the bare "фактура" reference → NON_INVOICE (Move).
    String bankStatement =
        "UNCRBGSFXXX BG 14 UNCR 7000 15 22083175 Линдстрьом ЕООД ФАКТУРА 0000596482 | net 10881.51"
            + " Транзакция с ДК 516905XXXXXX3375 Покупка на ПОС, Пощенска банка"
            + " Теглене на пари в брой, АТМ";
    assertEquals(Classification.NON_INVOICE, BgOcrDocumentClassifier.classify(bankStatement));
  }

  @Test
  void invoiceWithIzvlechenieSubHeadingDeepInBodyIsStillInvoice() {
    // Real Vivacom case: a genuine "ФАКТУРА" heading near the top, and an incidental "Извлечение
    // по услуги" sub-heading in a usage-detail appendix further down. The bare word must no
    // longer force NON_INVOICE — "фактура" decides.
    String text =
        "VIVACOM\nФАКТУРА №: 0121449096\nЕИК 831642181\n...\nИзвлечение по услуги\n3a 0876218897";
    assertEquals(Classification.INVOICE, BgOcrDocumentClassifier.classify(text));
  }

  @Test
  void bareIzvlechenieAloneIsNoLongerAutomaticallyNonInvoice() {
    // Real ОББ Интерлийз case: a bare "ИЗВЛЕЧЕНИЕ" title with no "фактура" anywhere and no other
    // marker. This classifier alone cannot decide — it must NOT force Move on that word alone;
    // the caller's structured-evidence fallback decides (see BgOcrRoutingIntegrationTest).
    String text = "ОББ ИНТЕРЛИЙЗ ЕАД\nИЗВЛЕЧЕНИЕ\n№ 0013413643\nБрутна сума: 171.25 EUR";
    assertEquals(Classification.UNCERTAIN, BgOcrDocumentClassifier.classify(text));
  }

  @Test
  void izvlechenieByAccountStillNonInvoice() {
    // The specific, unambiguous phrase "извлечение по сметка" remains a protected statement
    // title, unlike the bare word.
    assertEquals(
        Classification.NON_INVOICE,
        BgOcrDocumentClassifier.classify("Извлечение по сметка\nIBAN BG12..."));
  }

  @Test
  void paymentOrderPhraseInFooterBoilerplateNoLongerForcesNonInvoice() {
    // Real production regression: the ОББ Интерлийз premium notice has no "фактура" and no strong
    // marker in its heading, but its footer "ways to pay" paragraph literally contains "платежно
    // нареждане" (payment order) as ordinary boilerplate — "...с платежно нареждане при ... - ЦУ,"
    // — far past the heading. That must NOT force NON_INVOICE; it must stay UNCERTAIN so the
    // caller's structured-evidence fallback can promote it to INVOICE.
    StringBuilder sb = new StringBuilder("ИЗВЛЕЧЕНИЕ\n№ 1\nБрутна сума: 100.00 EUR\n");
    for (int i = 0; i < 40; i++) {
      sb.append("филър ред ").append(i).append("\n");
    }
    sb.append("- По банков път с платежно нареждане при Тест Банк - ЦУ,\n");
    assertEquals(Classification.UNCERTAIN, BgOcrDocumentClassifier.classify(sb.toString()));
  }

  @Test
  void paymentOrderTitleInHeadingIsStillNonInvoice() {
    // A genuine Payment Order document prints this as its own title near the top — that must
    // still be recognized (heading restriction loses no genuine non-invoice document).
    assertEquals(
        Classification.NON_INVOICE,
        BgOcrDocumentClassifier.classify("Платежно нареждане\nСума 250"));
  }

  @Test
  void earlyFakturaHeadingWinsOverLaterPaymentOrderPhraseWithinTheSameHeadingWindow() {
    // Remaining regression risk: an early, genuine "ФАКТУРА" heading, with the "ways to pay"
    // paragraph ("...платежно нареждане...") still landing inside the 35-line heading window (not
    // deep in the body like the real ОББ Интерлийз case). The earlier genuine title must still
    // decide — INVOICE, never NON_INVOICE — regardless of a later strong-title phrase in the same
    // window.
    StringBuilder sb = new StringBuilder("ФАКТУРА № 0000000001\nЕИК 999000001\n");
    for (int i = 0; i < 20; i++) {
      sb.append("ред ").append(i).append("\n");
    }
    sb.append("Плащане с платежно нареждане по посочената сметка.\n");
    assertEquals(Classification.INVOICE, BgOcrDocumentClassifier.classify(sb.toString()));
  }

  @Test
  void earlyPaymentOrderHeadingWinsOverLaterFakturaWithinTheSameHeadingWindow() {
    // The mirror case: a genuine "Платежно нареждане" title early, with "фактура" quoted later in
    // the same heading window (e.g. a payment reference) — must still be NON_INVOICE (Move).
    StringBuilder sb = new StringBuilder("Платежно нареждане\nНаредител: Тестов Клиент ООД\n");
    for (int i = 0; i < 20; i++) {
      sb.append("ред ").append(i).append("\n");
    }
    sb.append("Основание: плащане по фактура № 5\n");
    assertEquals(Classification.NON_INVOICE, BgOcrDocumentClassifier.classify(sb.toString()));
  }
}

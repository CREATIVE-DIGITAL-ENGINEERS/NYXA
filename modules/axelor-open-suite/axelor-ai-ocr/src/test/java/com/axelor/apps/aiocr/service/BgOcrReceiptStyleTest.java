package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import org.junit.jupiter.api.Test;

/**
 * Confident receipt-style classification, verified against the real {@code
 * manyinvoices_plusreciepts} OCR fixture. A page is receipt-style only when it carries BOTH a
 * fiscal-receipt phrase AND a fiscal-device wall-clock timestamp (HH:MM:SS) — the two signals every
 * fiscal receipt in the sample has and none of the normal invoices do. Neither "paid in cash" nor a
 * missing IBAN is evidence, so a normal cash invoice (fixture page 3) is NOT receipt-style while a
 * fiscal receipt paid in cash (fixture page 10) still is.
 */
class BgOcrReceiptStyleTest {

  /**
   * Fixture P7: an ELTRADE fuel-station fiscal receipt (phrase + 20:30:33 time) → receipt-style.
   */
  @Test
  void fuelStationFiscalReceiptWithTimeIsReceiptStyle() {
    String receipt =
        "ELTRADE\nДЕМО ОЙЛ ЕООД\nОРИГИНАЛ\nФактура №9360017358\nПропан-Бутан\n"
            + "ОБЩА СУМА ЕВРО\n24.91\nкредитни/дебитни карти\n24.91\n"
            + "11-03-2026\n1 артикул\n20:30:33\nBG\nФИСКАЛЕН БОН\nSS000079";
    assertTrue(BgOcrIntakeService.isFiscalReceiptStyle(receipt));
  }

  /** Fixture P10: a fiscal receipt paid in cash ("В БРОЙ ЕВРО") is still receipt-style. */
  @Test
  void cashFiscalReceiptIsStillReceiptStyle() {
    String receipt =
        "ELTRADE\nДЕМО ОЙЛ ЕООД\nОРИГИНАЛ\nФактура №9360017363\nБензин А-95Н\n"
            + "ОБЩА СУМА ЕВРО\n20.00\nВ БРОЙ ЕВРО\n20.00\n"
            + "12-03-2026\n1 артикул\n08:15:38\nBG\nФИСКАЛЕН БОН\nSS000079";
    assertTrue(BgOcrIntakeService.isFiscalReceiptStyle(receipt));
  }

  /** Cyrillic case-folding of the phrase (still requires the corroborating timestamp). */
  @Test
  void markerCasingIsFolded() {
    assertTrue(BgOcrIntakeService.isFiscalReceiptStyle("...\nфискален бон\n...\n16:30:02\n..."));
    assertTrue(BgOcrIntakeService.isFiscalReceiptStyle("...\nКасова Бележка\n...\n09:25:34\n..."));
  }

  /** Conservative: the fiscal phrase ALONE (no fiscal-device timestamp) does NOT classify. */
  @Test
  void fiscalPhraseWithoutTimestampIsNotConfident() {
    assertFalse(
        BgOcrIntakeService.isFiscalReceiptStyle(
            "Фактура № 9360017358\nОБЩА СУМА ЕВРО 24.91\nФИСКАЛЕН БОН"));
  }

  /** Conservative: a bare timestamp with no fiscal phrase does NOT classify. */
  @Test
  void timestampWithoutFiscalPhraseIsNotReceiptStyle() {
    assertFalse(
        BgOcrIntakeService.isFiscalReceiptStyle(
            "ФАКТУРА № 0000001234\nОтпечатана 09.03.2026 14:22:31\nОБЩО 120.00"));
  }

  /** Fixture P1: a normal bank-transfer invoice (no fiscal phrase, no HH:MM:SS) is NOT receipt. */
  @Test
  void normalBankTransferInvoiceIsNotReceiptStyle() {
    String invoice =
        "ФАКТУРА / Invoice\n№: 1000066314\nДоставчик: БУЛИНЖЕНЕРИНГ КЪМПАНИ ООД\nЕИК 121032148\n"
            + "Начин на плащане: по банка\nIBAN BG75UBBS81551066001817\nСума за плащане, евро: 39.72";
    assertFalse(BgOcrIntakeService.isFiscalReceiptStyle(invoice));
  }

  /**
   * Fixture P3: a NORMAL software (Microinvest) invoice that happens to be paid in cash, with an
   * empty IBAN — must NOT be classified as receipt-style (cash payment and missing IBAN are not
   * evidence). No fiscal phrase, no fiscal-device timestamp.
   */
  @Test
  void normalCashInvoiceWithEmptyIbanIsNotReceiptStyle() {
    String invoice =
        "Фактура\nОригинал\nНомер: 0000000521\nДата: 09.03.2026\nДоставчик Даскалов Монтажи ЕООД\n"
            + "Сума за плащане: 36.30 €\nПлащане:\nВ брой\nIBAN:\nMicroinvest Invoice Pro";
    assertFalse(BgOcrIntakeService.isFiscalReceiptStyle(invoice));
  }

  /** Fixture P5: a normal card invoice (no fiscal phrase, no HH:MM:SS) is NOT receipt-style. */
  @Test
  void normalCardInvoiceIsNotReceiptStyle() {
    String invoice =
        "Фактура\nНомер 0000024819\nДоставчик \"ЕН ДЖИ ТИ\" ЕООД\n"
            + "Плащане\nДебитна/Кредитна карта\nIBAN BG57UBBS81551000628215";
    assertFalse(BgOcrIntakeService.isFiscalReceiptStyle(invoice));
  }

  @Test
  void blankTextIsNotReceiptStyle() {
    assertFalse(BgOcrIntakeService.isFiscalReceiptStyle(null));
    assertFalse(BgOcrIntakeService.isFiscalReceiptStyle("   "));
  }

  // --- Receipt line filtering (issue 2) ---

  @Test
  void fiscalSummaryRowsAreDropped() {
    // The exact fiscal breakdown labels from the manyinvoices fuel receipts (p7/p10/p17).
    for (String label :
        new String[] {
          "ед.цена",
          "количество",
          "сума*В",
          "Отстъпка",
          "#в т.ч. ДДС",
          "#в т.ч. АКЦИЗ",
          "#HETO CT-CT",
          "ОБЩА СУМА ЕВРО",
          "обменен курс",
          "кредитни/дебитни карти",
          "В БРОЙ ЕВРО",
          "1.29",
          "20.00"
        }) {
      assertTrue(BgOcrIntakeService.isFiscalSummaryLine(label), label);
    }
  }

  @Test
  void realProductsAreKept() {
    // Genuine purchased products/services must NOT be treated as summary rows.
    assertFalse(BgOcrIntakeService.isFiscalSummaryLine("Бензин А-95Н"));
    assertFalse(BgOcrIntakeService.isFiscalSummaryLine("Пропан-Бутан"));
    assertFalse(BgOcrIntakeService.isFiscalSummaryLine("Diesel EKONOMY"));
    assertFalse(
        BgOcrIntakeService.isFiscalSummaryLine("Samsung S948 Galaxy S26 Ultra 5G 16GB/1TB"));
  }

  @Test
  void recoversProductNameFromFuelReceipt() {
    // Fixture P10: one real item ("Бензин А-95Н") sits between the invoice number and the summary
    // table; the 6 numeric summary rows must NOT be picked.
    String p10 =
        "ELTRADE\nОРИГИНАЛ\nФактура №9360017363\nБензин А-95Н\nед.цена\n1.29\nколичество\n15.500\n"
            + "сума*В\n20.00\n#в т.ч. ДДС\n3.33#\nОБЩА СУМА ЕВРО\n20.00\nФИСКАЛЕН БОН\n08:15:38";
    assertEquals("Бензин А-95Н", BgOcrIntakeService.recoverReceiptProductName(p10));
  }

  @Test
  void recoversProductNameSkippingHashNoiseLines() {
    // Fixture P17: transaction/column "#..." lines precede the real product "Diesel EKONOMY".
    String p17 =
        "ELTRADE\nОРИГИНАЛ\nФактура №1084630089\n#Транзакция №: 1076948\n#\n#Колонка: 02\n#\n"
            + "Diesel EKONOMY\n#\nед.цена\n1.55";
    assertEquals("Diesel EKONOMY", BgOcrIntakeService.recoverReceiptProductName(p17));
  }

  @Test
  void recoverProductNameNullWhenOnlySummaryFollows() {
    // No product line between the invoice number and the summary table → null (no fabrication).
    String noProduct = "Фактура №123\nед.цена\n1.00\nОБЩА СУМА ЕВРО\n1.00";
    assertNull(BgOcrIntakeService.recoverReceiptProductName(noProduct));
    assertNull(BgOcrIntakeService.recoverReceiptProductName(null));
  }

  // --- Dual-currency EUR total extraction (issue: 140.72 BGN vs 71.95 EUR) ---

  @Test
  void picksEurGrossAndNetIgnoringLeva() {
    // Fixture P17: Azure's InvoiceTotal was the ЛЕВА value 140.72; the EUR total is 71.95.
    String p17 =
        "Фактура №1084630089\nОБЩА СУМА ЕВРО\n71.95\nОБЩА СУМА ЛЕВА\n140.72\n"
            + "обменен курс 1ЕВРО=1.95583 ЛЕВА\nВСИЧКО\n71.95\nДДС*В\n11.99\nB=20.00%\n"
            + "нето стойност\n59.96\nКРЕДИТНИ/ДЕБИТНИ КАР\n71.95";
    java.math.BigDecimal[] eur = BgOcrIntakeService.receiptEurGrossNet(p17);
    assertEquals(0, new java.math.BigDecimal("71.95").compareTo(eur[0])); // EUR gross, not 140.72
    assertEquals(0, new java.math.BigDecimal("59.96").compareTo(eur[1])); // EUR net
    // VAT = gross - net = 11.99 (as the receipt's ДДС*В confirms).
    assertEquals(0, new java.math.BigDecimal("11.99").compareTo(eur[0].subtract(eur[1])));
  }

  @Test
  void picksEurGrossForFuelReceipt() {
    // Fixture P10 (Бензин А-95Н): EUR 20.00 / ЛЕВА 39.12.
    String p10 =
        "Фактура №9360017363\nОБЩА СУМА ЕВРО\n20.00\nОБЩА СУМА ЛЕВА\n39.12\n"
            + "ВСИЧКО\n20.00\nДДС*В\n3.33\nB=20.00%\nнето стойност\n16.67\nВ БРОЙ ЕВРО\n20.00";
    java.math.BigDecimal[] eur = BgOcrIntakeService.receiptEurGrossNet(p10);
    assertEquals(0, new java.math.BigDecimal("20.00").compareTo(eur[0]));
    assertEquals(0, new java.math.BigDecimal("16.67").compareTo(eur[1]));
  }

  @Test
  void noEurOverrideWhenLabelNotFollowedByNumber() {
    // Fixture P2/P6 layout: "ОБЩА СУМА ЕВРО" is immediately followed by another label, not a value,
    // so no EUR gross is extracted and Azure's (already-EUR) total is kept.
    String p2 = "ОБЩА СУМА ЕВРО\nОБЩА СУМА ЛЕВА\n10.50\nСума за плащане:\n10.50 €";
    assertNull(BgOcrIntakeService.receiptEurGrossNet(p2)[0]);
    assertNull(BgOcrIntakeService.receiptEurGrossNet("no totals here")[0]);
    assertNull(BgOcrIntakeService.receiptEurGrossNet(null)[0]);
  }

  @Test
  void picksEurGrossWhenValueFollowsTheLevaLabelNotTheEuroLabel() {
    // Fixture P18 (inv538): OCR reading order puts the BGN amount (111.83) right after the EUR
    // label and the real EUR total (57.18) after the ЛЕВА label + курс line. The peg-ratio rule
    // must
    // still pick 57.18 (the smaller of the pegged pair), not the 111.83 that was booked before.
    String p18 =
        "ОБЩА СУМА ЕВРО\n111.83\nОБЩА СУМА ЛЕВА\nобменен курс 1ЕВРО=1.95583 ЛЕВА\n57.18\n"
            + "ВСИЧКО\nДДС*В\n9.53\nнето стойност\n47.65";
    java.math.BigDecimal[] eur = BgOcrIntakeService.receiptEurGrossNet(p18);
    assertEquals(0, new java.math.BigDecimal("57.18").compareTo(eur[0])); // EUR gross, not 111.83
    assertEquals(0, new java.math.BigDecimal("47.65").compareTo(eur[1])); // EUR net
    assertEquals(0, new java.math.BigDecimal("9.53").compareTo(eur[0].subtract(eur[1]))); // VAT
  }

  @Test
  void picksEurTotalsWhenLabelLeadingCharacterIsMangled() {
    // Fixture P8 (inv530): OCR dropped the first glyph of every totals label ("ОБЩА СУМА ЕВРО" →
    // "Б©А СУМА ЕВРО", "нето стойност" → "ето стойност"). Matching the distinctive tail must still
    // recover EUR gross 49.45 (not Azure's 41.21) and net 41.21.
    String p8 =
        "Б©А СУМА ЕВРО\n49.45\nША СУМА ЛЕВА\n96.72\nобменен курс ЕВРО=1.95583 ЛЕВА\n"
            + "ЗИЧКО\n49.45\nДС*В\n8.24\nето стойност\n41.21";
    java.math.BigDecimal[] eur = BgOcrIntakeService.receiptEurGrossNet(p8);
    assertEquals(0, new java.math.BigDecimal("49.45").compareTo(eur[0]));
    assertEquals(0, new java.math.BigDecimal("41.21").compareTo(eur[1]));
  }

  // --- Fiscal summary rows with a dropped/mangled first character (issue 4) ---

  @Test
  void droppedFirstCharSummaryRowsAreStillDropped() {
    // Fixture P8: Azure returned the summary breakdown as "items" whose descriptions lost their
    // leading glyph — these must NOT become product lines.
    assertTrue(BgOcrIntakeService.isFiscalSummaryLine("д.цена")); // ед.цена
    assertTrue(BgOcrIntakeService.isFiscalSummaryLine("оличество")); // количество
    assertTrue(BgOcrIntakeService.isFiscalSummaryLine("ума*В")); // сума*В
    assertTrue(BgOcrIntakeService.isFiscalSummaryLine("ето стойност")); // нето стойност
    // Real products are still kept.
    assertFalse(BgOcrIntakeService.isFiscalSummaryLine("Бензин А-95Н"));
    assertFalse(BgOcrIntakeService.isFiscalSummaryLine("Пропан-Бутан"));
    assertFalse(BgOcrIntakeService.isFiscalSummaryLine("Samsung S948 Galaxy S26 Ultra 5G"));
  }

  // --- EUR/BGN VAT reconciliation on normal invoices (issue 3b) ---

  @Test
  void reconcilesVatReadInBgnOnEuroInvoice() {
    // Fixture P2 (inv526): base 8.75 € / gross 10.50 € but VAT read in лв. (3.42 = 1.75 × 1.95583).
    OcrExtractResult r = new OcrExtractResult();
    r.setTotalBase(new java.math.BigDecimal("8.75"));
    r.setTotalVat(new java.math.BigDecimal("3.42"));
    r.setTotalGross(new java.math.BigDecimal("10.50"));
    BgOcrIntakeService.reconcileEurVat(r);
    assertEquals(0, new java.math.BigDecimal("1.75").compareTo(r.getTotalVat()));
  }

  @Test
  void leavesConsistentEuroTotalsUntouched() {
    // Fixture P1 (inv524): base 33.10 + VAT 6.62 = gross 39.72 already reconciles → no change.
    OcrExtractResult r = new OcrExtractResult();
    r.setTotalBase(new java.math.BigDecimal("33.10"));
    r.setTotalVat(new java.math.BigDecimal("6.62"));
    r.setTotalGross(new java.math.BigDecimal("39.72"));
    BgOcrIntakeService.reconcileEurVat(r);
    assertEquals(0, new java.math.BigDecimal("6.62").compareTo(r.getTotalVat()));
  }

  // --- Dual-currency NORMAL invoices: EUR net/gross from labelled text (eu-invoices ДУКОВ) ---

  @Test
  void booksEurNetAndGrossWhenAzurePickedBgnColumn() {
    // eu-invoices Invoice-830: Azure returned BGN net/gross (321.54 / 385.85) with the EUR VAT
    // (32.88), so base + VAT ≠ gross. The document prints "164.40 EUR" / "32.88 EUR" / "197.28
    // EUR",
    // so net/gross must be re-booked to 164.40 / 197.28.
    OcrExtractResult r = new OcrExtractResult();
    r.setTotalBase(new java.math.BigDecimal("321.54"));
    r.setTotalVat(new java.math.BigDecimal("32.88"));
    r.setTotalGross(new java.math.BigDecimal("385.85"));
    r.setRawText(
        "Данъчна основа (20.00 %): BGN 321.54\n164.40 EUR\nНачислен ДДС: BGN 64.31\n32.88 EUR\n"
            + "197.28 EUR\nСума за плащане: BGN 385.85");
    BgOcrIntakeService.applyEurTotalsFromText(r);
    assertEquals(0, new java.math.BigDecimal("164.40").compareTo(r.getTotalBase()));
    assertEquals(0, new java.math.BigDecimal("197.28").compareTo(r.getTotalGross()));
    assertEquals(0, new java.math.BigDecimal("32.88").compareTo(r.getTotalVat())); // VAT unchanged
  }

  @Test
  void leavesEuroOnlyInvoiceUntouched() {
    // eu-invoices Ф-ра 0000000851: EUR-only invoice already reconciles (154.00 + 30.80 = 184.80) →
    // no change, even though "154.00 €" etc. appear in the text.
    OcrExtractResult r = new OcrExtractResult();
    r.setTotalBase(new java.math.BigDecimal("154.00"));
    r.setTotalVat(new java.math.BigDecimal("30.80"));
    r.setTotalGross(new java.math.BigDecimal("184.80"));
    r.setRawText("Данъчна основа 20%: 154.00 €\nДДС 20%: 30.80 €\n184.80 €");
    BgOcrIntakeService.applyEurTotalsFromText(r);
    assertEquals(0, new java.math.BigDecimal("154.00").compareTo(r.getTotalBase()));
    assertEquals(0, new java.math.BigDecimal("184.80").compareTo(r.getTotalGross()));
  }

  @Test
  void leavesTotalsUntouchedWhenNoEurTokens() {
    // A BGN-only invoice (no "EUR"/"€" tokens) must not be altered even if it doesn't reconcile.
    OcrExtractResult r = new OcrExtractResult();
    r.setTotalBase(new java.math.BigDecimal("321.54"));
    r.setTotalVat(new java.math.BigDecimal("32.88"));
    r.setTotalGross(new java.math.BigDecimal("385.85"));
    r.setRawText("Данъчна основа: BGN 321.54\nСума за плащане: BGN 385.85");
    BgOcrIntakeService.applyEurTotalsFromText(r);
    assertEquals(0, new java.math.BigDecimal("321.54").compareTo(r.getTotalBase()));
  }
}

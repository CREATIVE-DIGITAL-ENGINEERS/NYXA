package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import org.junit.jupiter.api.Test;

/**
 * Pure tests for the semantic duplicate key: supplier-id / invoice-number normalization, the
 * document-type classifier, the hardened "Фактура" number fallback, and the docKey gating.
 */
class BgOcrDedupKeyTest {

  // ---- supplier identifier
  // ------------------------------------------------------------------------

  @Test
  void bgEikDigitsOnly() {
    assertEquals("831641791", BgOcrIntakeService.normalizeSupplierId("831641791", null));
    assertEquals("831641791", BgOcrIntakeService.normalizeSupplierId("831 641 791", null));
    assertEquals("831641791", BgOcrIntakeService.normalizeSupplierId(null, "BG831641791"));
    assertEquals("1234567890123", BgOcrIntakeService.normalizeSupplierId("1234567890123", null));
  }

  @Test
  void foreignVatKeepsPrefix() {
    assertEquals("DE123456789", BgOcrIntakeService.normalizeSupplierId(null, "DE123456789"));
    assertEquals("RO1234567", BgOcrIntakeService.normalizeSupplierId(null, "RO1234567"));
  }

  @Test
  void invalidOrAmbiguousSupplierIsNull() {
    assertNull(BgOcrIntakeService.normalizeSupplierId("12345", null)); // wrong length
    assertNull(BgOcrIntakeService.normalizeSupplierId(null, null));
    // EIK and VAT disagree after normalization → ambiguous
    assertNull(BgOcrIntakeService.normalizeSupplierId("831641791", "BG999999999"));
    // EIK and VAT agree → the shared id
    assertEquals("131391369", BgOcrIntakeService.normalizeSupplierId("131391369", "BG131391369"));
  }

  // ---- invoice number
  // -----------------------------------------------------------------------------

  @Test
  void invoiceNumberStripsPrefixKeepsLeadingZeros() {
    assertEquals("0000601146", BgOcrIntakeService.normalizeInvoiceNo("№ 0000601146"));
    assertEquals("0000601146", BgOcrIntakeService.normalizeInvoiceNo("No 0000601146"));
    assertEquals("0000601146", BgOcrIntakeService.normalizeInvoiceNo("N0000601146"));
    assertEquals("123", BgOcrIntakeService.normalizeInvoiceNo("Фактура № 123"));
    assertEquals("0000601146", BgOcrIntakeService.normalizeInvoiceNo("0000601146"));
  }

  @Test
  void invoiceNumberAllowsShortAndAlphanumeric() {
    assertEquals("7", BgOcrIntakeService.normalizeInvoiceNo("7"));
    assertEquals("ABC12", BgOcrIntakeService.normalizeInvoiceNo("ABC-12"));
    assertNull(BgOcrIntakeService.normalizeInvoiceNo("   "));
    assertNull(BgOcrIntakeService.normalizeInvoiceNo("№"));
  }

  // ---- document type
  // ------------------------------------------------------------------------------

  @Test
  void documentTypeClassification() {
    assertEquals(
        "CREDIT_NOTE", BgOcrIntakeService.docType(result("Кредитно известие към фактура")));
    assertEquals("DEBIT_NOTE", BgOcrIntakeService.docType(result("ДЕБИТНО ИЗВЕСТИЕ")));
    assertEquals("INVOICE", BgOcrIntakeService.docType(result("Фактура № 12")));
  }

  // ---- Фактура number fallback (hardened)
  // --------------------------------------------------------

  @Test
  void fallbackCapturesNumberNearFaktura() {
    assertEquals(
        "0000601146",
        BgOcrIntakeService.invoiceNoFromText("Оригинал\nФактура № 0000601146\nДата 12.05.2024 г."));
    assertEquals("000123A", BgOcrIntakeService.invoiceNoFromText("Фактура № 000123A"));
    // the "Ф-ра" abbreviation is also an anchor
    assertEquals("0000000065", BgOcrIntakeService.invoiceNoFromText("Ф-ра 0000000065"));
  }

  @Test
  void fallbackRejectsDatesAndOtherRefs() {
    // date on the same line as the anchor → rejected (never captures a date)
    assertNull(BgOcrIntakeService.invoiceNoFromText("Фактура № 0000601146 Дата 12.05.2024"));
    // PO / contract references, no фактура anchor
    assertNull(BgOcrIntakeService.invoiceNoFromText("Договор № 555\nПоръчка № 42"));
    // two distinct candidates → ambiguous
    assertNull(BgOcrIntakeService.invoiceNoFromText("Фактура № 7\nФактура № 8"));
    // no number
    assertNull(BgOcrIntakeService.invoiceNoFromText("плащане по фактурата предстои"));
    assertNull(BgOcrIntakeService.invoiceNoFromText(null));
  }

  // ---- supplier ЕИК recovered from text
  // -----------------------------------------------------------

  @Test
  void supplierEikFromTextPicksTheNonExcludedOne() {
    // supplier ЕИК 831641791, customer ЕИК 204123456 (excluded) → supplier recovered
    String text = "Доставчик ЕИК 831641791\nПолучател ЕИК: 204123456";
    java.util.Set<String> excluded = new java.util.HashSet<>();
    excluded.add("204123456");
    assertEquals("831641791", BgOcrIntakeService.supplierEikFromText(text, excluded));
  }

  @Test
  void supplierEikFromTextReadsTheDdsVatLabel() {
    // Invoice printing only "ДДС №" (no "ЕИК" anywhere) — Azure returns no VendorTaxId, so the
    // supplier identity has to come from this label. Buyer's ДДС № is excluded; the tax lines
    // ("ДДС 20%") must not be mistaken for an identity.
    String text =
        "ТЕСТЕКС ООД\nДДС №\nBG100000026\n"
            + "ПРИМЕРНА ДИДЖИТАЛ КОМПАНИЯ А ЕООД\nДДС №\nBG100000019\n"
            + "ДДС 20%: 1 000.00 лв.\nIBAN BG80BNBG96611020345678";
    java.util.Set<String> excluded = new java.util.HashSet<>();
    excluded.add("100000026");
    assertEquals("100000019", BgOcrIntakeService.supplierEikFromText(text, excluded));
  }

  @Test
  void supplierEikFromTextIgnoresBareDdsOnTaxLines() {
    // No identity anywhere: the only "ЕИК-shaped" digits sit on a tax/total line and behind a
    // longer number. Recovering anything here would invent a supplier.
    String text = "Сума без ДДС 5000.00\nДДС 20% 1000.00\nФУ 1234567890123456";
    assertNull(BgOcrIntakeService.supplierEikFromText(text, new java.util.HashSet<>()));
  }

  @Test
  void supplierEikFromTextNullWhenAmbiguous() {
    // two ЕИК, none excluded → ambiguous → null (safe, no guess)
    String text = "ЕИК 831641791\nЕИК 204123456";
    assertNull(BgOcrIntakeService.supplierEikFromText(text, new java.util.HashSet<>()));
    assertNull(BgOcrIntakeService.supplierEikFromText(null, new java.util.HashSet<>()));
  }

  // ---- docKey gating
  // ------------------------------------------------------------------------------

  private final BgOcrIntakeService svc = new BgOcrIntakeService(null, null, null, null, null);

  @Test
  void docKeyComposedWhenReliable() {
    OcrExtractResult r = result("Фактура № 0000601146");
    r.setSupplierEik("831641791");
    r.setSupplierVat("BG831641791");
    r.setInvoiceNumber("№ 0000601146");
    assertEquals("1|831641791|INVOICE|0000601146", svc.docKey(r, 1L));
    // different company → different key
    assertEquals("2|831641791|INVOICE|0000601146", svc.docKey(r, 2L));
  }

  @Test
  void docKeyIncludesDocumentType() {
    OcrExtractResult r = result("Кредитно известие");
    r.setSupplierEik("831641791");
    r.setInvoiceNumber("№ 5");
    assertEquals("1|831641791|CREDIT_NOTE|5", svc.docKey(r, 1L));
  }

  @Test
  void docKeyNullWhenPartMissing() {
    OcrExtractResult r = result("Фактура");
    r.setSupplierEik("831641791");
    assertNull(svc.docKey(r, 1L)); // no invoice number
    r.setInvoiceNumber("5");
    assertNull(svc.docKey(r, null)); // no company
    OcrExtractResult noSupplier = result("Фактура № 5");
    noSupplier.setInvoiceNumber("5");
    assertNull(svc.docKey(noSupplier, 1L)); // no supplier id
  }

  private static OcrExtractResult result(String rawText) {
    OcrExtractResult r = new OcrExtractResult();
    r.setRawText(rawText);
    return r;
  }
}

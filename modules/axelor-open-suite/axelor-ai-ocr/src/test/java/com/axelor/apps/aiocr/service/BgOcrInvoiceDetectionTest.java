package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Language-independent invoice detection: a genuine English/EU/foreign supplier invoice (no
 * Bulgarian "фактура") must be recognized from structured evidence (invoice number + supplier +
 * total/items) or an explicit international "Invoice" title, while an H1 customs declaration stays
 * a non-invoice document. Uses the real eu-invoices examples 875/880/882/883/887/888 (invoices) and
 * 878/879 (customs).
 */
class BgOcrInvoiceDetectionTest {

  private static OcrExtractResult res(String no, String supplier, String gross, String raw) {
    OcrExtractResult r = new OcrExtractResult();
    r.setInvoiceNumber(no);
    r.setSupplierName(supplier);
    if (gross != null) {
      r.setTotalGross(new BigDecimal(gross));
    }
    r.setRawText(raw);
    return r;
  }

  @Test
  void foreignInvoicesAreRecognizedFromStructuredFields() {
    // 882 XPEL BV, 875 WT, 887 LEGEND, 888 Jiangsu Yaosheng — number + supplier + positive total.
    assertTrue(
        BgOcrIntakeService.hasForeignInvoiceEvidence(res("INVBV63692", "XPEL BV", "264.00", "")));
    assertTrue(
        BgOcrIntakeService.hasForeignInvoiceEvidence(res("1014XN2026", "WT", "4122.00", "")));
    assertTrue(
        BgOcrIntakeService.hasForeignInvoiceEvidence(res("RE2026-1119", "LEGEND", "3406.70", "")));
    assertTrue(
        BgOcrIntakeService.hasForeignInvoiceEvidence(
            res("YS20260423NH1", "JIANGSU YAOSHENG NEW MATERIAL CO., LTD", "845.00", "")));
  }

  @Test
  void invoiceKeywordCorroboratedByATotalIsAnInvoice() {
    // No structured number, but an explicit international title plus a total.
    assertTrue(
        BgOcrIntakeService.hasForeignInvoiceEvidence(res(null, null, "500.00", "TAX INVOICE")));
    assertTrue(
        BgOcrIntakeService.hasForeignInvoiceEvidence(res("F-2026-1", null, null, "Rechnung")));
  }

  @Test
  void customsDeclarationIsNotAnInvoice() {
    // 878/879 H1 SAD: no invoice number, and MRN present → not invoice evidence, and is customs.
    OcrExtractResult h1 =
        res(null, "БВМ ТРЕЙДИНГ ООД", "200.21", "BG005100 IM A H1\nMRN: 26BG005100604888R2");
    assertFalse(BgOcrIntakeService.hasForeignInvoiceEvidence(h1));
    assertTrue(BgOcrIntakeService.isCustomsDeclaration(h1.getRawText()));
  }

  @Test
  void ambiguousDocumentIsNotSilentlyAnInvoice() {
    // A delivery note with no number, no total and no invoice title is neither invoice nor customs.
    OcrExtractResult r = res(null, "Some Co", null, "Delivery note\nGoods received");
    assertFalse(BgOcrIntakeService.hasForeignInvoiceEvidence(r));
    assertFalse(BgOcrIntakeService.isCustomsDeclaration(r.getRawText()));
  }

  @Test
  void aRealInvoiceIsNotMistakenForCustoms() {
    assertFalse(
        BgOcrIntakeService.isCustomsDeclaration("XPEL BV\nInvoice INVBV63692\nTotal 264.00"));
  }
}

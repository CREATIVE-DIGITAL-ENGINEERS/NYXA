package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * {@link BgOcrIntakeService#applyDocumentBreakdown} only records a breakdown that could be a real
 * document summary. A balanced-but-impossible pair (upload 70: net 4.98, VAT 75.77, gross 80.75)
 * must not be marked trusted, or the component check reconciles the invoice against nonsense.
 *
 * <p>The method touches nothing but the result it is given and the invoice it writes, so it runs
 * with null repositories.
 */
class BgOcrDocumentBreakdownTest {

  private final BgOcrIntakeService service = new BgOcrIntakeService(null, null, null, null, null);

  private static OcrExtractResult result(String net, String vat, String gross) {
    OcrExtractResult r = new OcrExtractResult();
    r.setTotalBase(net == null ? null : new BigDecimal(net));
    r.setTotalVat(vat == null ? null : new BigDecimal(vat));
    r.setTotalGross(gross == null ? null : new BigDecimal(gross));
    return r;
  }

  @Test
  void upload70GarbageBreakdownIsRejectedDespiteBalancing() {
    Invoice invoice = new Invoice();
    // 4.98 + 75.77 = 80.75 exactly, yet VAT is ~1521% of net — a mis-paired SubTotal and subtotal.
    service.applyDocumentBreakdown(invoice, result("4.98", "75.77", "80.75"));

    // The marker is the authoritative signal (a generated decimal getter defaults NULL to ZERO, so
    // the columns cannot speak for themselves — see BgDefaultedGetterTest).
    assertEquals(Boolean.FALSE, orFalse(invoice.getOcrDocumentBreakdownAvailable()));
  }

  @Test
  void aRealTwentyPercentBreakdownIsRecorded() {
    Invoice invoice = new Invoice();
    // The upload's true printed summary: net 72.27, VAT 14.46, gross 86.73.
    service.applyDocumentBreakdown(invoice, result("72.27", "14.46", "86.73"));

    assertEquals(Boolean.TRUE, invoice.getOcrDocumentBreakdownAvailable());
    assertEquals(new BigDecimal("72.27"), invoice.getOcrDocumentNetTotal());
    assertEquals(new BigDecimal("14.46"), invoice.getOcrDocumentVatTotal());
  }

  @Test
  void upload70TotalBelowLineSumIsFlaggedAsAProbableSubtotal() {
    // Extracted total 80.75, line items total 86.72 — a grand total can never be below its parts.
    assertEquals(true, BgOcrIntakeService.documentTotalBelowLineSum(bd("80.75"), bd("86.72"), 3));
  }

  @Test
  void aNormalTotalAtOrAboveTheLineSumIsNotFlagged() {
    // Equal (per-line rounding), and legitimately higher (a discount/charge line) — neither is a
    // subtotal signal.
    assertEquals(false, BgOcrIntakeService.documentTotalBelowLineSum(bd("86.72"), bd("86.72"), 3));
    assertEquals(false, BgOcrIntakeService.documentTotalBelowLineSum(bd("90.00"), bd("86.72"), 3));
    // One cent below over three lines stays within the rounding allowance.
    assertEquals(false, BgOcrIntakeService.documentTotalBelowLineSum(bd("86.71"), bd("86.72"), 3));
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  private static Boolean orFalse(Boolean b) {
    return b == null ? Boolean.FALSE : b;
  }
}

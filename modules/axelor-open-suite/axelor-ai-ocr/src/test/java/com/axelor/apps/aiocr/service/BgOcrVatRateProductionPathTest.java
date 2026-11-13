package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoiceLineTax;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiocr.service.ocr.AzureDocumentIntelligenceExtractor;
import com.axelor.apps.aiocr.service.ocr.BgOcrVatMapping;
import com.axelor.apps.aiocr.service.ocr.OcrResultJson;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractLine;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.aiocr.service.record.OcrRoundingLineService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The production path for a real-shaped invoice, end to end, from the wire shape Azure actually
 * returned — no direct call to the inference helper anywhere in this test.
 *
 * <pre>
 *   stored analyzeResult JSON
 *     → AzureDocumentIntelligenceExtractor.fromStoredAnalyzeResult   (the real parser)
 *     → OcrResultJson.toJson / fromJson                              (the real split-cache round trip)
 *     → BgOcrIntakeService.recoverMissingLineVatRates                (the real intake step)
 *     → BgOcrVatMapping.taxCodeForRate                               (the real line tax mapping)
 *     → OcrRoundingLineService.reconcileBeforeValidation             (the real 609 reconciliation)
 * </pre>
 *
 * <p>A hand-built {@code OcrExtractResult} passed straight to the inference would never touch the
 * two places that matter: the parser must not fabricate a 0.00 rate, and the cache must not persist
 * such a fabrication for the split child to read back as explicit evidence.
 */
class BgOcrVatRateProductionPathTest {

  /** The ten line nets of the real document; they sum to 146.56 against a printed net of 146.57. */
  private static final String[] LINE_NETS = {
    "3.30", "5.00", "5.38", "4.05", "1.39", "2.39", "10.62", "2.25", "16.18", "96.00"
  };

  /**
   * The Azure {@code analyzeResult} shape this document produced: a SubTotal/TotalTax/InvoiceTotal
   * summary, and items carrying ONLY an Amount — no {@code TaxRate}, no {@code Tax}.
   */
  private static String storedAnalyzeResultJson() {
    StringBuilder items = new StringBuilder();
    for (int i = 0; i < LINE_NETS.length; i++) {
      if (i > 0) {
        items.append(',');
      }
      items
          .append("{\"valueObject\":{")
          .append("\"Description\":{\"valueString\":\"item ")
          .append(i)
          .append("\"},")
          .append("\"Amount\":{\"valueCurrency\":{\"amount\":")
          .append(LINE_NETS[i])
          .append(",\"currencyCode\":\"EUR\"}}")
          .append("}}");
    }
    return "{\"documents\":[{\"fields\":{"
        + "\"VendorName\":{\"valueString\":\"СИ ТИ ДИ ЕООД\"},"
        + "\"VendorTaxId\":{\"valueString\":\"BG130943075\"},"
        + "\"InvoiceId\":{\"valueString\":\"0900092440\"},"
        + "\"InvoiceDate\":{\"valueDate\":\"2026-06-05\"},"
        + "\"SubTotal\":{\"valueCurrency\":{\"amount\":146.57,\"currencyCode\":\"EUR\"}},"
        + "\"TotalTax\":{\"valueCurrency\":{\"amount\":29.31,\"currencyCode\":\"EUR\"}},"
        + "\"InvoiceTotal\":{\"valueCurrency\":{\"amount\":175.88,\"currencyCode\":\"EUR\"}},"
        + "\"Items\":{\"valueArray\":["
        + items
        + "]}}}],\"content\":\"ТЕСТЕКС\"}";
  }

  @Test
  void freshUploadOfThisPdfProducesTwentyPercentLinesAndOneCentOn609() throws Exception {
    // ── 1. the real parser ────────────────────────────────────────────────────────────────────
    OcrExtractResult parsed =
        AzureDocumentIntelligenceExtractor.fromStoredAnalyzeResult(storedAnalyzeResultJson());

    assertEquals(new BigDecimal("146.57"), parsed.getTotalBase());
    assertEquals(new BigDecimal("29.31"), parsed.getTotalVat());
    assertEquals(new BigDecimal("175.88"), parsed.getTotalGross());
    assertEquals(10, parsed.getLines().size());
    for (OcrExtractLine line : parsed.getLines()) {
      assertNull(line.getVatRate(), "a rate nobody read must stay UNKNOWN, never become 0.00");
      assertNull(line.getVatAmount(), "and no VAT amount may be invented from an invented gross");
      assertFalse(line.isVatEvidenceExplicit(), "nothing here is evidence from the document");
    }

    // ── 2. the real split-cache round trip (AiOcrUpload.cachedOcrJson) ────────────────────────
    String cached = OcrResultJson.toJson(parsed);
    assertTrue(cached.contains("\"vatRate\":null"), "the cache must persist unknown, not 0.00");
    OcrExtractResult rehydrated = OcrResultJson.fromJson(cached);
    for (OcrExtractLine line : rehydrated.getLines()) {
      assertNull(line.getVatRate());
      assertFalse(
          line.isVatEvidenceExplicit(),
          "Jackson must not mark a rehydrated null as evidence — this is what blocked recovery");
    }

    // ── 3. the real intake step ───────────────────────────────────────────────────────────────
    List<String> reviewNotes = new ArrayList<>();
    new BgOcrIntakeService(null, null, null, null, null)
        .recoverMissingLineVatRates(rehydrated, false, reviewNotes);

    // ── 4. the real line tax mapping buildInvoiceLine uses ────────────────────────────────────
    BigDecimal netSum = BigDecimal.ZERO;
    for (OcrExtractLine line : rehydrated.getLines()) {
      assertEquals(new BigDecimal("20"), line.getVatRate());
      assertEquals(
          BgOcrVatMapping.BG_STD_20,
          BgOcrVatMapping.taxCodeForRate(line.getVatRate()),
          "every applicable line must map to the 20% purchase tax");
      netSum = netSum.add(line.getNetAmount());
    }
    assertEquals(new BigDecimal("146.56"), netSum);
    assertEquals(1, reviewNotes.size(), "the recovery must announce itself");

    // ── 5. what Axelor's per-rate summary will carry, and the real 609 reconciliation ─────────
    BigDecimal computedVat =
        netSum
            .multiply(new BigDecimal("20"))
            .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    assertEquals(new BigDecimal("29.31"), computedVat, "computed VAT");
    assertEquals(new BigDecimal("175.87"), netSum.add(computedVat), "gross before adjustment");

    Invoice invoice = draftFrom(rehydrated, netSum, computedVat);
    new ReconcilerWithStubbedLookups().reconcileBeforeValidation(invoice);

    List<InvoiceLine> corrections = new ArrayList<>();
    for (InvoiceLine line : invoice.getInvoiceLineList()) {
      if (Boolean.TRUE.equals(line.getOcrRoundingAdjustment())) {
        corrections.add(line);
      }
    }
    assertEquals(1, corrections.size(), "exactly one automatic rounding line");
    assertEquals(new BigDecimal("0.01"), corrections.get(0).getExTaxTotal());
    assertEquals(BgOcrVatMapping.BG_ZERO, corrections.get(0).getTaxCode(), "booked without VAT");
    assertEquals("60909", corrections.get(0).getAccount().getCode());
    // ⚠️ И ТОЙ Е ОБИКНОВЕН РЕД: и той не бива да остава с unit = null като OCR редовете.
    // Закръглението няма продукт, значи пада направо на настроената единица.
    assertSame(
        DEFAULT_UNIT,
        corrections.get(0).getUnit(),
        "редът за закръгление не бива да стига до записа без мерна единица");
    assertEquals(new BigDecimal("175.88"), invoice.getInTaxTotal(), "the printed document total");
  }

  /** The draft the intake would persist: the recovered lines plus the document's own gross. */
  private static Invoice draftFrom(
      OcrExtractResult result, BigDecimal netSum, BigDecimal computedVat) {
    Invoice invoice = new Invoice();
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    invoice.setStatusSelect(InvoiceRepository.STATUS_DRAFT);
    invoice.setOcrDocumentTotal(result.getTotalGross());
    invoice.setInvoiceLineList(new ArrayList<>());
    for (OcrExtractLine src : result.getLines()) {
      InvoiceLine line = new InvoiceLine();
      line.setExTaxTotal(src.getNetAmount());
      line.setCompanyExTaxTotal(src.getNetAmount());
      line.setTaxRate(src.getVatRate());
      line.setTaxCode(BgOcrVatMapping.taxCodeForRate(src.getVatRate()));
      line.setTaxLineSet(taxLineSetAt(src.getVatRate()));
      invoice.addInvoiceLineListItem(line);
    }
    invoice.setExTaxTotal(netSum);
    invoice.setTaxTotal(computedVat);
    invoice.setInTaxTotal(netSum.add(computedVat));
    return invoice;
  }

  private static Set<TaxLine> taxLineSetAt(BigDecimal ratePercent) {
    Tax tax = new Tax();
    tax.setCode(BgOcrVatMapping.taxCodeForRate(ratePercent));
    TaxLine taxLine = new TaxLine();
    taxLine.setTax(tax);
    taxLine.setValue(ratePercent);
    Set<TaxLine> set = new HashSet<>();
    set.add(taxLine);
    return set;
  }

  /**
   * The real reconciliation, with only its four DB lookups replaced — {@code recompute} reproduces
   * what {@code TaxInvoiceLine} + {@code InvoiceTaxComputeService} do: group the lines' bases per
   * rate, round each rate's VAT once, gross = net + Σ rows.
   */
  /** Каквото настроената локализация би върнала за единица по подразбиране. */
  static final com.axelor.apps.base.db.Unit DEFAULT_UNIT = defaultUnit();

  private static com.axelor.apps.base.db.Unit defaultUnit() {
    com.axelor.apps.base.db.Unit unit = new com.axelor.apps.base.db.Unit();
    unit.setName("Брой");
    return unit;
  }

  private static class ReconcilerWithStubbedLookups extends OcrRoundingLineService {

    @Override
    protected com.axelor.apps.aiocr.service.record.OcrInvoiceTaxSummaryService.Result
        syncTaxSummary(Invoice invoice) {
      return null;
    }

    /**
     * Редът за закръгление е ОБИКНОВЕН ред без продукт, тоест минава през същото споделено правило
     * като OCR редовете. Подменен е само доставчикът на настройката — самото прилагане е
     * продукционното.
     */
    @Override
    protected com.axelor.apps.aiaccounting.service.invoice.InvoiceLineUnitDefaultService
        unitDefaults() {
      return com.axelor.apps.aiocr.testsupport.UnitDefaultsForTests.configured(DEFAULT_UNIT);
    }

    @Override
    protected void recompute(Invoice invoice) {
      java.util.Map<BigDecimal, BigDecimal> baseByRate = new java.util.LinkedHashMap<>();
      BigDecimal net = BigDecimal.ZERO;
      for (InvoiceLine line : invoice.getInvoiceLineList()) {
        BigDecimal lineNet = line.getExTaxTotal() == null ? BigDecimal.ZERO : line.getExTaxTotal();
        net = net.add(lineNet);
        if (line.getTaxLineSet() == null) {
          continue;
        }
        for (TaxLine taxLine : line.getTaxLineSet()) {
          baseByRate.merge(taxLine.getValue(), lineNet, BigDecimal::add);
        }
      }
      BigDecimal vat = BigDecimal.ZERO;
      List<InvoiceLineTax> rows = new ArrayList<>();
      for (java.util.Map.Entry<BigDecimal, BigDecimal> entry : baseByRate.entrySet()) {
        BigDecimal rowTax =
            entry
                .getValue()
                .multiply(entry.getKey())
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        InvoiceLineTax row = new InvoiceLineTax();
        row.setExTaxBase(entry.getValue());
        row.setTaxTotal(rowTax);
        rows.add(row);
        vat = vat.add(rowTax);
      }
      invoice.setInvoiceLineTaxList(rows);
      invoice.setExTaxTotal(net.setScale(2, RoundingMode.HALF_UP));
      invoice.setTaxTotal(vat);
      invoice.setInTaxTotal(net.add(vat).setScale(2, RoundingMode.HALF_UP));
    }

    @Override
    protected Account resolveRoundingAccount(Invoice invoice) {
      Account account = new Account();
      account.setCode("60909"); // the company's Cashier Debit Regulation account
      account.setStatusSelect(AccountRepository.STATUS_ACTIVE);
      return account;
    }

    @Override
    protected TaxLine resolveZeroRateTaxLine(Invoice invoice) {
      Tax tax = new Tax();
      tax.setCode(BgOcrVatMapping.BG_ZERO);
      TaxLine taxLine = new TaxLine();
      taxLine.setTax(tax);
      taxLine.setValue(BigDecimal.ZERO);
      return taxLine;
    }

    @Override
    protected BigDecimal toCompanyAmount(Invoice invoice, BigDecimal amount) {
      return amount;
    }
  }

  @Test
  void aGenuineZeroVatDocumentStillMapsToBgZrpThroughTheSamePath() {
    String json =
        storedAnalyzeResultJson()
            .replace("\"amount\":29.31", "\"amount\":0.00")
            .replace("\"amount\":175.88", "\"amount\":146.57");

    OcrExtractResult parsed = AzureDocumentIntelligenceExtractor.fromStoredAnalyzeResult(json);
    OcrExtractResult rehydrated = OcrResultJson.fromJson(OcrResultJson.toJson(parsed));
    new BgOcrIntakeService(null, null, null, null, null)
        .recoverMissingLineVatRates(rehydrated, false, new ArrayList<>());

    for (OcrExtractLine line : rehydrated.getLines()) {
      assertNotNull(line.getVatRate(), "a zero-rated document gets a decided rate, not an unknown");
      assertEquals(0, line.getVatRate().signum());
      assertEquals(BgOcrVatMapping.BG_ZERO, BgOcrVatMapping.taxCodeForRate(line.getVatRate()));
    }
  }

  @Test
  void aContradictoryDocumentStaysUnknownAndIsNeverZeroRated() {
    // net + VAT = 175.88 but the document prints 181.00 as its gross.
    String json = storedAnalyzeResultJson().replace("\"amount\":175.88", "\"amount\":181.00");

    OcrExtractResult parsed = AzureDocumentIntelligenceExtractor.fromStoredAnalyzeResult(json);
    OcrExtractResult rehydrated = OcrResultJson.fromJson(OcrResultJson.toJson(parsed));
    List<String> notes = new ArrayList<>();
    new BgOcrIntakeService(null, null, null, null, null)
        .recoverMissingLineVatRates(rehydrated, false, notes);

    for (OcrExtractLine line : rehydrated.getLines()) {
      assertNull(line.getVatRate(), "unknown must stay unknown");
      assertNull(
          BgOcrVatMapping.taxCodeForRate(line.getVatRate()),
          "no tax code at all — the line is reported as missing its tax, not booked at 0%");
    }
    assertEquals(1, notes.size());
    assertTrue(notes.get(0).contains("could not be recovered"), notes.get(0));
  }
}

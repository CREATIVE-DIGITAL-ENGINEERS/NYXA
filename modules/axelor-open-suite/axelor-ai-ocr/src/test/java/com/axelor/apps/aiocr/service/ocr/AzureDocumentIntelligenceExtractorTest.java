package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ocr.AzureDocumentIntelligenceExtractor.AzureResponse;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.Test;

/** Unit tests for the direct Azure extractor: mapping, confidence, validation, submit/poll. */
class AzureDocumentIntelligenceExtractorTest {

  private static final ObjectMapper M = new ObjectMapper();

  private static final String ANALYZE_RESULT =
      "{\"content\":\"ФАКТУРА No 0003478613 Начин на плащане: по банков път\","
          + "\"documents\":[{\"fields\":{"
          + "\"VendorName\":{\"valueString\":\"ACME EOOD\",\"confidence\":0.97},"
          + "\"VendorTaxId\":{\"valueString\":\"BG131391369\",\"confidence\":0.93},"
          + "\"CustomerName\":{\"valueString\":\"TESTEKS OOD\"},"
          + "\"CustomerTaxId\":{\"valueString\":\"BG100000026\"},"
          + "\"InvoiceId\":{\"valueString\":\"0003478613\",\"confidence\":0.99},"
          + "\"InvoiceDate\":{\"valueDate\":\"2026-06-01\",\"confidence\":0.95},"
          + "\"DueDate\":{\"valueDate\":\"2026-07-01\"},"
          + "\"SubTotal\":{\"valueCurrency\":{\"amount\":100.0,\"currencyCode\":\"EUR\"},\"confidence\":0.90},"
          + "\"TotalTax\":{\"valueCurrency\":{\"amount\":20.0,\"currencyCode\":\"EUR\"},\"confidence\":0.90},"
          + "\"InvoiceTotal\":{\"valueCurrency\":{\"amount\":120.0,\"currencyCode\":\"EUR\"},\"confidence\":0.96},"
          + "\"Items\":{\"valueArray\":[{\"valueObject\":{"
          + "\"Description\":{\"valueString\":\"Widget\"},\"ProductCode\":{\"valueString\":\"P1\"},"
          + "\"Quantity\":{\"valueNumber\":2},"
          + "\"UnitPrice\":{\"valueCurrency\":{\"amount\":50.0,\"currencyCode\":\"EUR\"}},"
          + "\"Amount\":{\"valueCurrency\":{\"amount\":100.0,\"currencyCode\":\"EUR\"}},"
          + "\"Tax\":{\"valueCurrency\":{\"amount\":20.0,\"currencyCode\":\"EUR\"}},"
          + "\"TaxRate\":{\"valueString\":\"20%\"}}}]}"
          + "}}]}";

  private static JsonNode json(String s) {
    try {
      return M.readTree(s);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /** Extractor with stubbed transport + clock, so no live Azure/AppSettings/sleeps are needed. */
  private static class TestExtractor extends AzureDocumentIntelligenceExtractor {
    final Deque<Object> submitScript = new ArrayDeque<>();
    final Deque<Object> pollScript = new ArrayDeque<>();
    int submitCalls = 0;
    int pollCalls = 0;
    long clock = 0;
    long clockStep = 0;

    @Override
    protected String endpoint() {
      return "https://x.cognitiveservices.azure.com";
    }

    @Override
    protected String key() {
      return "k";
    }

    @Override
    protected int maxRetries() {
      return 2;
    }

    @Override
    protected void backoff(int a, String r) {}

    @Override
    protected void sleepBeforeNextPoll(String r) {}

    @Override
    protected int pollMaxSeconds() {
      return 100;
    }

    @Override
    protected long monotonicMillis() {
      long v = clock;
      clock += clockStep;
      return v;
    }

    @Override
    protected AzureResponse httpSubmit(String u, byte[] b, String m) throws IOException {
      submitCalls++;
      Object n = submitScript.poll();
      if (n instanceof IOException) {
        throw (IOException) n;
      }
      return (AzureResponse) n;
    }

    @Override
    protected AzureResponse httpGet(String u) throws IOException {
      pollCalls++;
      Object n = pollScript.poll();
      if (n == null) {
        return new AzureResponse(200, "{\"status\":\"running\"}", null, null);
      }
      if (n instanceof IOException) {
        throw (IOException) n;
      }
      return (AzureResponse) n;
    }
  }

  // ── mapping ──────────────────────────────────────────────────────────────────

  @Test
  void mapsAzureResultFields() {
    OcrExtractResult r =
        AzureDocumentIntelligenceExtractor.mapAnalyzeResult(json(ANALYZE_RESULT), M);
    assertFalse(r.isExtractionFailed());
    assertEquals("ACME EOOD", r.getSupplierName());
    assertEquals("BG131391369", r.getSupplierVat());
    assertEquals("131391369", r.getSupplierEik()); // derived from VAT
    assertEquals("TESTEKS OOD", r.getCustomerName());
    assertEquals("BG100000026", r.getCustomerVat());
    assertEquals("100000026", r.getCustomerEik()); // derived from customer VAT
    assertEquals("0003478613", r.getInvoiceNumber());
    assertEquals(LocalDate.of(2026, 6, 1), r.getInvoiceDate());
    assertEquals(LocalDate.of(2026, 7, 1), r.getDueDate());
    assertEquals(0, new BigDecimal("100.0").compareTo(r.getTotalBase()));
    assertEquals(0, new BigDecimal("20.0").compareTo(r.getTotalVat()));
    assertEquals(0, new BigDecimal("120.0").compareTo(r.getTotalGross()));
    assertEquals("EUR", r.getCurrency());
    assertEquals(1, r.getLines().size());
    assertEquals("Widget", r.getLines().get(0).getDescription());
    assertEquals("P1", r.getLines().get(0).getProductCode());
    assertEquals(0, new BigDecimal("100.0").compareTo(r.getLines().get(0).getNetAmount()));
    assertEquals(
        0, new BigDecimal("20.00").compareTo(r.getLines().get(0).getVatRate())); // 20% → 20.00
    assertEquals(0.97, r.getFieldConfidence().get("supplierName"));
    assertEquals(0.96, r.getFieldConfidence().get("totalAmount"));
    assertTrue(r.getRawText() != null && r.getRawText().contains("банков път")); // content captured
  }

  @Test
  void wrongModelWithoutFieldsThrowsClearError() {
    // prebuilt-read / prebuilt-layout return content/paragraphs but no documents[0].fields.
    OcrServiceException ex =
        assertThrows(
            OcrServiceException.class,
            () ->
                AzureDocumentIntelligenceExtractor.mapAnalyzeResult(
                    json(
                        "{\"modelId\":\"prebuilt-read\",\"content\":\"ФАКТУРА\",\"paragraphs\":[]}"),
                    M));
    assertTrue(ex.getMessage().contains("prebuilt-invoice"));
    assertTrue(ex.getMessage().contains("prebuilt-read")); // echoes the actual model received
  }

  @Test
  void emptyDocumentsArrayThrowsClearError() {
    OcrServiceException ex =
        assertThrows(
            OcrServiceException.class,
            () ->
                AzureDocumentIntelligenceExtractor.mapAnalyzeResult(
                    json("{\"modelId\":\"prebuilt-layout\",\"documents\":[]}"), M));
    assertTrue(ex.getMessage().contains("prebuilt-layout"));
  }

  @Test
  void mapMissingFieldsAreNull() {
    OcrExtractResult r =
        AzureDocumentIntelligenceExtractor.mapAnalyzeResult(
            json("{\"documents\":[{\"fields\":{\"VendorName\":{\"valueString\":\"X\"}}}]}"), M);
    assertEquals("X", r.getSupplierName());
    assertNull(r.getInvoiceNumber());
    assertNull(r.getTotalGross());
    assertTrue(r.getLines().isEmpty());
  }

  // ── confidence ───────────────────────────────────────────────────────────────

  @Test
  void overallConfidenceIsDeterministicMeanOfCriticalFields() {
    OcrExtractResult r =
        AzureDocumentIntelligenceExtractor.mapAnalyzeResult(json(ANALYZE_RESULT), M);
    double overall =
        AzureDocumentIntelligenceExtractor.computeOverallConfidence(r.getFieldConfidence(), r);
    // (max(0.97,0.93) + 0.99 + 0.95 + 0.96 + 0.90 + 0.90 + 0.96) / 7
    assertEquals(0.9471, overall);
  }

  @Test
  void missingCriticalFieldLowersConfidence() {
    OcrExtractResult r =
        AzureDocumentIntelligenceExtractor.mapAnalyzeResult(json(ANALYZE_RESULT), M);
    r.setInvoiceNumber(null); // critical field gone
    double overall =
        AzureDocumentIntelligenceExtractor.computeOverallConfidence(r.getFieldConfidence(), r);
    assertTrue(overall < 0.9471);
  }

  @Test
  void toPercentScales() {
    assertEquals(
        0, new BigDecimal("94.71").compareTo(AzureDocumentIntelligenceExtractor.toPercent(0.9471)));
  }

  @Test
  void parseRateHandlesPercentAndFraction() {
    assertEquals(
        0,
        new BigDecimal("20.00")
            .compareTo(AzureDocumentIntelligenceExtractor.parseRateToPercent("20%")));
    assertEquals(
        0,
        new BigDecimal("20.00")
            .compareTo(AzureDocumentIntelligenceExtractor.parseRateToPercent("0.20")));
    assertEquals(
        0,
        new BigDecimal("9.00")
            .compareTo(AzureDocumentIntelligenceExtractor.parseRateToPercent("9")));
    assertNull(AzureDocumentIntelligenceExtractor.parseRateToPercent(null));
  }

  // ── validation ───────────────────────────────────────────────────────────────

  @Test
  void validateFlagsTotalsMismatchAndCurrency() {
    OcrExtractResult r = new OcrExtractResult();
    r.setSupplierName("X");
    r.setInvoiceNumber("1");
    r.setInvoiceDate(LocalDate.of(2026, 1, 1));
    r.setTotalBase(new BigDecimal("100"));
    r.setTotalVat(new BigDecimal("20"));
    r.setTotalGross(new BigDecimal("999")); // mismatch
    r.setCurrency("USD"); // non-EUR
    r.setConfidence(new BigDecimal("95"));
    new AzureDocumentIntelligenceExtractor().validate(r);
    assertFalse(r.getDiscrepancies().isEmpty());
    assertTrue(r.getWarnings().stream().anyMatch(w -> w.contains("not EUR")));
  }

  // ── submit orchestration ───────────────────────────────────────────────────────

  @Test
  void submit202ReturnsWithoutRetry() {
    TestExtractor e = new TestExtractor();
    e.submitScript.add(new AzureResponse(202, "", "https://op", null));
    AzureResponse r = e.submitWithRetry(new byte[] {1}, "application/pdf");
    assertEquals(202, r.status);
    assertEquals(1, e.submitCalls);
  }

  @Test
  void submit5xxRetriesThenThrows() {
    TestExtractor e = new TestExtractor();
    e.submitScript.add(new AzureResponse(503, "", null, null));
    e.submitScript.add(new AzureResponse(500, "", null, null));
    e.submitScript.add(new AzureResponse(502, "", null, null));
    assertThrows(
        OcrServiceException.class, () -> e.submitWithRetry(new byte[] {1}, "application/pdf"));
    assertEquals(3, e.submitCalls); // maxRetries(2)+1
  }

  @Test
  void submit429RetriesThenThrows() {
    TestExtractor e = new TestExtractor();
    e.submitScript.add(new AzureResponse(429, "", null, "1"));
    e.submitScript.add(new AzureResponse(429, "", null, "1"));
    e.submitScript.add(new AzureResponse(429, "", null, "1"));
    assertThrows(
        OcrServiceException.class, () -> e.submitWithRetry(new byte[] {1}, "application/pdf"));
    assertEquals(3, e.submitCalls);
  }

  @Test
  void submitRecoversAfterNetworkError() {
    TestExtractor e = new TestExtractor();
    e.submitScript.add(new IOException("conn refused"));
    e.submitScript.add(new AzureResponse(202, "", "https://op", null));
    AzureResponse r = e.submitWithRetry(new byte[] {1}, "application/pdf");
    assertEquals(202, r.status);
    assertEquals(2, e.submitCalls);
  }

  // ── poll orchestration ──────────────────────────────────────────────────────────

  @Test
  void pollRunningThenSucceededReturnsAnalyzeResult() {
    TestExtractor e = new TestExtractor();
    e.pollScript.add(new AzureResponse(200, "{\"status\":\"running\"}", null, null));
    e.pollScript.add(
        new AzureResponse(
            200, "{\"status\":\"succeeded\",\"analyzeResult\":{\"documents\":[]}}", null, null));
    JsonNode ar = e.pollUntilDone("https://op");
    assertTrue(ar.has("documents"));
  }

  @Test
  void pollFailedStatusIsAnalysisFailed() {
    TestExtractor e = new TestExtractor();
    e.pollScript.add(new AzureResponse(200, "{\"status\":\"failed\"}", null, null));
    assertThrows(
        AzureDocumentIntelligenceExtractor.OcrAnalysisFailedException.class,
        () -> e.pollUntilDone("https://op"));
  }

  @Test
  void pollTransient429ThenSucceeds() {
    TestExtractor e = new TestExtractor();
    e.pollScript.add(new AzureResponse(429, "", null, "1"));
    e.pollScript.add(
        new AzureResponse(
            200, "{\"status\":\"succeeded\",\"analyzeResult\":{\"documents\":[]}}", null, null));
    assertTrue(e.pollUntilDone("https://op").has("documents"));
  }

  @Test
  void pollTimesOut() {
    TestExtractor e = new TestExtractor();
    e.clockStep = 60000; // each clock read advances 60s; deadline = 100s → times out quickly
    // pollScript empty → httpGet always returns "running"
    assertThrows(OcrServiceException.class, () -> e.pollUntilDone("https://op"));
  }

  @Test
  void pollMalformedBodyThrows() {
    TestExtractor e = new TestExtractor();
    e.pollScript.add(new AzureResponse(200, "{not json", null, null));
    assertThrows(OcrServiceException.class, () -> e.pollUntilDone("https://op"));
  }
}

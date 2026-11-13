package com.axelor.apps.aiocr.service.ocr;

import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractLine;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Deterministic mock engine — canned data, no external calls. Selected server-side via {@code
 * aiocr.extractor.mode=mock} for local development and tests. A filename containing {@code "fail"}
 * returns an extraction failure so the FAILED path can be exercised without Azure. A filename
 * containing {@code "multi"} or {@code "split"} returns a multi-document {@code rawJson} (two
 * invoices + a stock receipt across three pages) so the PDF document splitter can be exercised
 * without Azure.
 */
@Singleton
public class MockOcrExtractor implements OcrExtractor {

  public static final String ENGINE = "mock";

  @Override
  public String name() {
    return ENGINE;
  }

  @Override
  public OcrExtractResult extract(MetaFile file) {
    String fileName = file != null ? file.getFileName() : null;
    OcrExtractResult result = new OcrExtractResult();
    result.setEngineUsed(ENGINE);
    if (file != null) {
      result.setCorrelationId(String.valueOf(file.getId()));
    }

    if (fileName != null && fileName.toLowerCase().contains("fail")) {
      result.setExtractionFailed(true);
      result.getErrors().add("Mock: simulated unreadable document (filename contains 'fail').");
      result.setConfidence(BigDecimal.ZERO);
      return result;
    }

    result.setSupplierName("Примерен Доставчик ЕООД");
    result.setSupplierEik("831641791");
    result.setSupplierVat("BG831641791");
    result.setInvoiceNumber("0000012345");
    result.setInvoiceDate(LocalDate.of(2026, 6, 15));
    result.setCurrency("EUR");
    result.setConfidence(new BigDecimal("92.50"));

    OcrExtractLine line = new OcrExtractLine();
    line.setDescription("Услуга по договор");
    line.setQty(BigDecimal.ONE);
    line.setUnitPrice(new BigDecimal("1000.00"));
    line.setNetAmount(new BigDecimal("1000.00"));
    line.setVatRate(new BigDecimal("20"));
    line.setVatAmount(new BigDecimal("200.00"));
    result.getLines().add(line);

    result.setTotalBase(new BigDecimal("1000.00"));
    result.setTotalVat(new BigDecimal("200.00"));
    result.setTotalGross(new BigDecimal("1200.00"));

    String lower = fileName != null ? fileName.toLowerCase() : "";
    if (lower.contains("multi") || lower.contains("split")) {
      result.setRawText(MULTI_DOC_TEXT);
      result.setRawJson(MULTI_DOC_JSON);
    }
    return result;
  }

  /**
   * Three A4 pages (8.5×11 inch): page 1 invoice 0000012345, page 2 "Стокова разписка" for it, page
   * 3 invoice 0000067890. Shaped like an Azure {@code analyzeResult} so {@link
   * com.axelor.apps.aiocr.service.ingest.BgOcrPdfSegmenter} can segment it.
   */
  private static final String MULTI_DOC_TEXT =
      "Фактура № 0000012345\nПримерен Доставчик ЕООД\nСтокова разписка\nФактура № 0000067890\n";

  private static final String MULTI_DOC_JSON =
      "{"
          + "\"content\":\"Фактура № 0000012345 ... Стокова разписка ... Фактура № 0000067890\","
          + "\"pages\":["
          + "{\"pageNumber\":1,\"unit\":\"inch\",\"width\":8.5,\"height\":11.0,\"lines\":["
          + "{\"content\":\"Фактура № 0000012345\",\"polygon\":[1.0,1.0,4.0,1.0,4.0,1.3,1.0,1.3]},"
          + "{\"content\":\"Примерен Доставчик ЕООД\",\"polygon\":[1.0,1.5,5.0,1.5,5.0,1.8,1.0,1.8]}"
          + "]},"
          + "{\"pageNumber\":2,\"unit\":\"inch\",\"width\":8.5,\"height\":11.0,\"lines\":["
          + "{\"content\":\"Стокова разписка\",\"polygon\":[1.0,1.0,4.0,1.0,4.0,1.3,1.0,1.3]}"
          + "]},"
          + "{\"pageNumber\":3,\"unit\":\"inch\",\"width\":8.5,\"height\":11.0,\"lines\":["
          + "{\"content\":\"Фактура № 0000067890\",\"polygon\":[1.0,1.0,4.0,1.0,4.0,1.3,1.0,1.3]}"
          + "]}"
          + "],"
          + "\"documents\":["
          + "{\"boundingRegions\":[{\"pageNumber\":1,\"polygon\":[0.5,0.5,8.0,0.5,8.0,10.5,0.5,10.5]}],"
          + "\"fields\":{"
          + "\"InvoiceId\":{\"content\":\"0000012345\",\"confidence\":0.95},"
          + "\"VendorName\":{\"content\":\"Примерен Доставчик ЕООД\",\"confidence\":0.9},"
          + "\"VendorTaxId\":{\"content\":\"BG831641791\",\"confidence\":0.9},"
          + "\"InvoiceTotal\":{\"valueCurrency\":{\"amount\":1200.00,\"currencyCode\":\"EUR\"},\"confidence\":0.9}"
          + "}},"
          + "{\"boundingRegions\":[{\"pageNumber\":3,\"polygon\":[0.5,0.5,8.0,0.5,8.0,10.5,0.5,10.5]}],"
          + "\"fields\":{"
          + "\"InvoiceId\":{\"content\":\"0000067890\",\"confidence\":0.95},"
          + "\"VendorName\":{\"content\":\"Втори Доставчик ООД\",\"confidence\":0.9},"
          + "\"VendorTaxId\":{\"content\":\"BG204957650\",\"confidence\":0.9},"
          + "\"InvoiceTotal\":{\"valueCurrency\":{\"amount\":600.00,\"currencyCode\":\"EUR\"},\"confidence\":0.9}"
          + "}}"
          + "]"
          + "}";
}

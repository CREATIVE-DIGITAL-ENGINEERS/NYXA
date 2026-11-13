package com.axelor.apps.aiocr.service.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.base.db.Company;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Raw-OCR source selection and bounding. No DB and no Azure: these are pure decisions over what
 * processing already stored, which is exactly the property that must hold.
 */
class OcrRecordViewServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static OcrRecordViewService service() {
    return new OcrRecordViewService();
  }

  /**
   * The projection service now refuses a record outside the caller's company on its own, so these
   * tests have to state which company they are acting for. There is no database here, hence the
   * hand-set id: the guard compares ids, and that is all it needs.
   */
  private static final long SCOPE_COMPANY_ID = 7L;

  private static Company scopeCompany() {
    Company c = new Company();
    c.setId(SCOPE_COMPANY_ID);
    return c;
  }

  /** Body type that may throw, so a test doing JSON round-trips does not need its own wrapper. */
  private interface ScopedBody {
    void run() throws Exception;
  }

  private static void inScope(ScopedBody body) {
    AiExecutionContext.callAsSystem(
        SCOPE_COMPANY_ID,
        "test",
        () -> {
          body.run();
          return null;
        });
  }

  /** A serialized OcrExtractResult, as a split child stores it. */
  private static String segmentJson() {
    return "{\"supplierName\":\"Демо ЕООД\",\"invoiceNumber\":\"1234\","
        + "\"rawText\":\"Фактура 1234\",\"totalGross\":120.00}";
  }

  /** A carrier's whole-document analyzeResult. */
  private static String carrierJson() {
    return "{\"analyzeResult\":{\"content\":\"…many invoices…\",\"pages\":[{},{},{}]}}";
  }

  @Test
  void splitChildUsesItsOwnSegmentJsonNotTheCarrier() {
    inScope(
        () -> {
          AiOcrUpload child = new AiOcrUpload();
          child.setCompany(scopeCompany());
          child.setCachedOcrJson(segmentJson());
          // Even if the carrier payload were somehow present, the child's own slice must win.
          child.setSplitSourceJson(carrierJson());

          assertEquals(OcrRecordViewService.RawSource.CACHED_SEGMENT, service().rawSourceOf(child));

          Map<String, Object> raw = service().rawOcr(child, 100_000);
          assertEquals(Boolean.TRUE, raw.get("available"));
          assertEquals(segmentJson(), raw.get("json"));
          assertFalse(
              String.valueOf(raw.get("json")).contains("analyzeResult"), "no carrier data leaked");
        });
  }

  @Test
  void carrierUsesSplitSourceJson() {
    inScope(
        () -> {
          AiOcrUpload carrier = new AiOcrUpload();
          carrier.setCompany(scopeCompany());
          carrier.setCarrierKind(AiOcrUploadStatus.CARRIER_KIND_PDF_SPLIT);
          carrier.setSplitSourceJson(carrierJson());

          assertEquals(OcrRecordViewService.RawSource.SPLIT_SOURCE, service().rawSourceOf(carrier));
          Map<String, Object> raw = service().rawOcr(carrier, 100_000);
          assertEquals(carrierJson(), raw.get("json"));
          // The description must warn that this covers every child, not one invoice.
          assertTrue(String.valueOf(raw.get("description")).contains("every child"));
        });
  }

  @Test
  void bankStatementChildPageMarkerIsNotPresentedAsOcrResult() {
    inScope(
        () -> {
          AiOcrUpload child = new AiOcrUpload();
          child.setCompany(scopeCompany());
          child.setCachedOcrJson("{\"fromPage\":3,\"toPage\":5}");

          assertEquals(
              OcrRecordViewService.RawSource.PAGE_RANGE_MARKER, service().rawSourceOf(child));
          Map<String, Object> raw = service().rawOcr(child, 100_000);
          assertEquals(Boolean.FALSE, raw.get("available"));
          assertNull(raw.get("json"));
          assertEquals("{\"fromPage\":3,\"toPage\":5}", raw.get("pageRange"));
        });
  }

  @Test
  void plainUploadReturnsItsOwnSingleDocumentJson() {
    inScope(
        () -> {
          AiOcrUpload plain = new AiOcrUpload();
          plain.setCompany(scopeCompany());
          plain.setRawExtractedJson("engine=azure; confidence=91; supplier=Демо");
          plain.setOcrResultJson(carrierJson());

          assertEquals(
              OcrRecordViewService.RawSource.SINGLE_DOCUMENT, service().rawSourceOf(plain));
          Map<String, Object> raw = service().rawOcr(plain, 100_000);
          assertEquals(Boolean.TRUE, raw.get("available"));
          assertEquals(carrierJson(), raw.get("json"));
          // Must not be described as covering several children — it is one document.
          assertTrue(String.valueOf(raw.get("description")).contains("this document only"));
        });
  }

  @Test
  void recordWithNoStoredJsonAtAllReportsNone() {
    inScope(
        () -> {
          // e.g. a record processed before OCR JSON retention, or one whose extraction failed.
          AiOcrUpload plain = new AiOcrUpload();
          plain.setCompany(scopeCompany());
          plain.setRawExtractedJson("engine=azure; confidence=91");

          assertEquals(OcrRecordViewService.RawSource.NONE, service().rawSourceOf(plain));
          Map<String, Object> raw = service().rawOcr(plain, 100_000);
          assertEquals(Boolean.FALSE, raw.get("available"));
          assertNull(raw.get("json"));
        });
  }

  @Test
  void splitChildStillWinsOverASingleDocumentPayload() {
    inScope(
        () -> {
          // Guards the precedence order: a child's own slice must never be replaced by a
          // whole-document
          // payload, whichever fields happen to be populated.
          AiOcrUpload child = new AiOcrUpload();
          child.setCompany(scopeCompany());
          child.setCachedOcrJson(segmentJson());
          child.setOcrResultJson(carrierJson());

          assertEquals(OcrRecordViewService.RawSource.CACHED_SEGMENT, service().rawSourceOf(child));
          assertEquals(segmentJson(), service().rawOcr(child, 100_000).get("json"));
        });
  }

  @Test
  void carrierSemanticsAreUnchangedBySingleDocumentRetention() {
    inScope(
        () -> {
          AiOcrUpload carrier = new AiOcrUpload();
          carrier.setCompany(scopeCompany());
          carrier.setCarrierKind(AiOcrUploadStatus.CARRIER_KIND_PDF_SPLIT);
          carrier.setSplitSourceJson(carrierJson());

          assertEquals(OcrRecordViewService.RawSource.SPLIT_SOURCE, service().rawSourceOf(carrier));
          assertTrue(
              String.valueOf(service().rawOcr(carrier, 100_000).get("description"))
                  .contains("every child"));
        });
  }

  @Test
  void oversizedPayloadReturnsValidBoundedJsonNotACutString() {
    inScope(
        () -> {
          StringBuilder big = new StringBuilder("{\"analyzeResult\":{\"content\":\"");
          for (int i = 0; i < 60_000; i++) {
            big.append('x');
          }
          big.append("\"}}");

          AiOcrUpload carrier = new AiOcrUpload();
          carrier.setCompany(scopeCompany());
          carrier.setSplitSourceJson(big.toString());

          Map<String, Object> raw = service().rawOcr(carrier, 10_000);
          assertEquals(Boolean.TRUE, raw.get("truncated"));
          assertNull(raw.get("json"), "a cut-off JSON string must never be returned");
          assertNotNull(raw.get("note"));
          assertNotNull(raw.get("sizeBytes"));

          // The whole envelope must still serialise to valid JSON.
          String serialised = MAPPER.writeValueAsString(raw);
          assertNotNull(MAPPER.readTree(serialised));

          @SuppressWarnings("unchecked")
          Map<String, Object> preview = (Map<String, Object>) raw.get("preview");
          assertTrue(preview.get("containsKeys").toString().contains("analyzeResult"));
        });
  }

  @Test
  void payloadAtTheLimitIsStillEmbedded() {
    inScope(
        () -> {
          AiOcrUpload child = new AiOcrUpload();
          child.setCompany(scopeCompany());
          child.setCachedOcrJson(segmentJson());
          // Must be UTF-8 explicitly: the payload contains Cyrillic, so the platform default
          // charset
          // would give a different byte count than the service uses.
          int exact = segmentJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
          Map<String, Object> raw = service().rawOcr(child, exact);
          assertEquals(Boolean.FALSE, raw.get("truncated"));
          assertEquals(segmentJson(), raw.get("json"));
        });
  }

  @Test
  void detailAdvertisesRawAvailabilityWithoutEmbeddingIt() {
    inScope(
        () -> {
          AiOcrUpload child = new AiOcrUpload();
          child.setCompany(scopeCompany());
          child.setFileName("invoice-1.pdf");
          child.setStatusSelect(AiOcrUploadStatus.NEEDS_REVIEW);
          child.setCachedOcrJson(segmentJson());

          Map<String, Object> detail = service().detail(child);

          @SuppressWarnings("unchecked")
          Map<String, Object> raw = (Map<String, Object>) detail.get("rawOcr");
          assertEquals(Boolean.TRUE, raw.get("available"));
          assertNull(raw.get("json"), "detail must not embed raw OCR by default");
          assertNull(detail.get("invoice"));
        });
  }

  @Test
  void detailNeverExposesStoragePaths() {
    inScope(
        () -> {
          AiOcrUpload u = new AiOcrUpload();
          u.setCompany(scopeCompany());
          u.setFileName("x.pdf");
          u.setBlobUrl("https://storage.example/secret-container/x.pdf");
          u.setSourcePath("/data/attachments/inbox/x.pdf");

          String serialised = service().detail(u).toString();
          assertFalse(serialised.contains("secret-container"), "blobUrl must not be exposed");
          assertFalse(serialised.contains("/data/attachments"), "sourcePath must not be exposed");
        });
  }

  @Test
  void childWithoutOwnJsonPointsAtItsCarrier() {
    inScope(
        () -> {
          AiOcrUpload carrier = new AiOcrUpload();
          carrier.setCompany(scopeCompany());
          carrier.setSplitSourceJson(carrierJson());

          AiOcrUpload child = new AiOcrUpload();
          child.setCompany(scopeCompany());
          child.setImportArchive(carrier);

          Map<String, Object> raw = service().rawOcr(child, 100_000);
          assertEquals(Boolean.FALSE, raw.get("available"));
          // The hint helps the agent ask for the right record instead of assuming none exists.
          assertNotNull(raw.get("hint"));
        });
  }
}

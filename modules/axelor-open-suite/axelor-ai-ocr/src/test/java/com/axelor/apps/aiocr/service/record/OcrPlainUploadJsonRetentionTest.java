package com.axelor.apps.aiocr.service.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Retention of the Azure analyzeResult for an ordinary (non-split) document.
 *
 * <p>Exercises {@code persistMeta} directly rather than the whole pipeline, so the assertion is
 * about storage and never reaches Azure — the extractor is not involved at all.
 */
class OcrPlainUploadJsonRetentionTest extends AiOcrTest {

  private static final String AZURE_JSON =
      "{\"analyzeResult\":{\"content\":\"Фактура 1234\",\"pages\":[{}]}}";

  /** persistMeta is private by design; the storage decision it makes is what this test is about. */
  private void persistMeta(AiOcrUpload upload, OcrExtractResult result) {
    try {
      Method m =
          BgOcrIntakeService.class.getDeclaredMethod(
              "persistMeta", AiOcrUpload.class, OcrExtractResult.class);
      m.setAccessible(true);
      m.invoke(Beans.get(BgOcrIntakeService.class), upload, result);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static OcrExtractResult resultWithRawJson(String rawJson) {
    OcrExtractResult r = new OcrExtractResult();
    r.setEngineUsed("azure");
    r.setSupplierName("Демо ЕООД");
    r.setRawJson(rawJson);
    return r;
  }

  private AiOcrUpload newUpload(String fileName, String status) {
    AiOcrUpload u = new AiOcrUpload();
    u.setFileName(fileName);
    u.setStatusSelect(status);
    return JPA.save(u);
  }

  /**
   * Runs {@code body} as an authorized caller confined to {@code companyCode}, the way the view
   * service's {@link OcrRecordViewService#assertInScope} expects. Reading a record through {@code
   * rawOcr}/{@code detail} is now a company-scoped operation, so the fixture has to establish a
   * scope and hand the test a company to stamp onto its uploads.
   */
  private void asSystem(String companyCode, Consumer<Company> body) {
    inTransaction(
        () -> {
          Company scope = company(companyCode);
          AiExecutionContext.runAsSystem(scope.getId(), "test", () -> body.accept(scope));
        });
  }

  private Company company(String code) {
    Company c =
        Beans.get(CompanyRepository.class)
            .all()
            .filter("self.code = :c")
            .bind("c", code)
            .fetchOne();
    if (c == null) {
      c = new Company();
      c.setName(code);
      c.setCode(code);
      c = JPA.save(c);
    }
    return c;
  }

  @Test
  void plainUploadRetainsTheAzureJson() {
    inTransaction(
        () -> {
          AiOcrUpload saved = newUpload("plain.pdf", AiOcrUploadStatus.PROCESSING);
          persistMeta(saved, resultWithRawJson(AZURE_JSON));
          JPA.save(saved);

          AiOcrUpload reloaded = JPA.find(AiOcrUpload.class, saved.getId());
          assertEquals(AZURE_JSON, reloaded.getOcrResultJson());
          // The other two slots stay empty: exactly one of the three is ever populated.
          assertNull(reloaded.getCachedOcrJson());
          assertNull(reloaded.getSplitSourceJson());
          // rawExtractedJson remains the human-readable snapshot, not JSON.
          assertNotNull(reloaded.getRawExtractedJson());
        });
  }

  @Test
  void aSegmentResultWithoutRawJsonStoresNothing() {
    inTransaction(
        () -> {
          // Split children carry a per-segment result whose rawJson is null (only the extractor
          // sets it), so persistMeta must leave the field alone rather than write an empty value.
          AiOcrUpload child = new AiOcrUpload();
          child.setFileName("child.pdf");
          child.setSegmentType(AiOcrUploadStatus.SEGMENT_TYPE_INVOICE);
          child.setStatusSelect(AiOcrUploadStatus.PROCESSING);
          AiOcrUpload saved = JPA.save(child);

          persistMeta(saved, resultWithRawJson(null));
          JPA.save(saved);

          assertNull(JPA.find(AiOcrUpload.class, saved.getId()).getOcrResultJson());
        });
  }

  @Test
  void blankRawJsonIsNotStored() {
    inTransaction(
        () -> {
          AiOcrUpload saved = newUpload("blank.pdf", AiOcrUploadStatus.PROCESSING);
          persistMeta(saved, resultWithRawJson("   "));
          JPA.save(saved);

          assertNull(JPA.find(AiOcrUpload.class, saved.getId()).getOcrResultJson());
        });
  }

  @Test
  void carrierDoesNotKeepTwoCopiesOfTheSameJson() {
    inTransaction(
        () -> {
          // When a document turns out to be a carrier, splitSourceJson takes ownership and the
          // staged single-document copy is dropped, so the payload is never stored twice.
          AiOcrUpload carrier = new AiOcrUpload();
          carrier.setFileName("carrier.pdf");
          carrier.setStatusSelect(AiOcrUploadStatus.PROCESSING);
          carrier.setOcrResultJson(AZURE_JSON);
          carrier.setSplitSourceJson(AZURE_JSON);
          carrier.setOcrResultJson(null);
          carrier.setCarrierKind(AiOcrUploadStatus.CARRIER_KIND_PDF_SPLIT);
          AiOcrUpload saved = JPA.save(carrier);

          AiOcrUpload reloaded = JPA.find(AiOcrUpload.class, saved.getId());
          assertNull(reloaded.getOcrResultJson());
          assertEquals(AZURE_JSON, reloaded.getSplitSourceJson());
          assertEquals(
              OcrRecordViewService.RawSource.SPLIT_SOURCE,
              Beans.get(OcrRecordViewService.class).rawSourceOf(reloaded));
        });
  }

  @Test
  void retrievableThroughTheViewServiceLikeAnySplitChild() {
    asSystem(
        "RET1",
        scope -> {
          AiOcrUpload saved = newUpload("plain2.pdf", AiOcrUploadStatus.COMPLETED);
          saved.setCompany(scope);
          persistMeta(saved, resultWithRawJson(AZURE_JSON));
          JPA.save(saved);

          OcrRecordViewService view = Beans.get(OcrRecordViewService.class);
          AiOcrUpload reloaded = JPA.find(AiOcrUpload.class, saved.getId());

          assertEquals(OcrRecordViewService.RawSource.SINGLE_DOCUMENT, view.rawSourceOf(reloaded));
          Map<String, Object> raw = view.rawOcr(reloaded, 100_000);
          assertEquals(Boolean.TRUE, raw.get("available"));
          assertEquals(AZURE_JSON, raw.get("json"));

          @SuppressWarnings("unchecked")
          Map<String, Object> advertised =
              (Map<String, Object>) view.detail(reloaded).get("rawOcr");
          assertEquals(Boolean.TRUE, advertised.get("available"));
          assertNull(advertised.get("json"), "detail must still not embed it by default");
        });
  }

  @Test
  void noAzureCallIsPossibleFromRetrieval() {
    asSystem(
        "RET2",
        scope -> {
          // The read path only touches stored columns; there is no extractor on it at all. The
          // upload stays transient — rawOcr reads its fields directly — but it must still be in the
          // caller's company for the scope check to let the read through.
          AiOcrUpload upload = new AiOcrUpload();
          upload.setCompany(scope);
          upload.setOcrResultJson(AZURE_JSON);
          Map<String, Object> raw = Beans.get(OcrRecordViewService.class).rawOcr(upload, 100_000);
          assertTrue((Boolean) raw.get("available"));
          assertEquals(AZURE_JSON, raw.get("json"));
        });
  }
}

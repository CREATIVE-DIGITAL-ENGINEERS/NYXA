package com.axelor.apps.aiocr.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.aiocr.service.readiness.OcrInvoiceReadiness;
import com.axelor.apps.aiocr.service.readiness.OcrSupplierInvoiceReadinessService.LineIssue;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The repair pass's own logic: which lines it will ask about, what it accepts from the model, and
 * the gate that stops it being enabled by accident.
 *
 * <p>Applying a repair needs Guice, a product catalogue and account management, so that side is
 * covered by the DB-backed tests. What is worth isolating here is the selection and parsing, since
 * those decide what the model is ever allowed to touch.
 */
class BgOcrLineRepairServiceTest {

  private final BgOcrLineRepairService service = new BgOcrLineRepairService();

  private static LineIssue issue(
      int index, long id, boolean noProduct, boolean noAccount, boolean noTax) {
    return new LineIssue(index, id, "desc", new BigDecimal("20"), noProduct, noAccount, noTax);
  }

  private static OcrInvoiceReadiness readinessWith(LineIssue... issues) {
    OcrInvoiceReadiness r = new OcrInvoiceReadiness();
    for (LineIssue i : issues) {
      r.getLineIssues().add(i);
    }
    return r;
  }

  @Test
  void onlyLinesMissingAProductOrAccountAreOfferedToTheModel() {
    List<LineIssue> repairable =
        service.repairable(
            readinessWith(
                issue(0, 10L, true, true, false), // missing product → ask
                issue(1, 11L, false, true, false), // missing account → ask
                issue(2, 12L, false, false, true))); // ONLY missing tax → do not ask

    assertEquals(2, repairable.size());
    assertEquals(10L, repairable.get(0).getLineId());
    assertEquals(11L, repairable.get(1).getLineId());
  }

  @Test
  void aTaxOnlyProblemIsNeverSentToTheModel() {
    // Deliberate: an unrecognised VAT rate is usually a real special regime (reverse charge, ВОП,
    // import, exempt). Guessing 20% there misstates VAT rather than fixing anything, so the tax
    // decision stays with the accountant.
    assertTrue(service.repairable(readinessWith(issue(0, 10L, false, false, true))).isEmpty());
  }

  @Test
  void noReadinessOrNoIssuesMeansNoWork() {
    assertTrue(service.repairable(null).isEmpty());
    assertTrue(service.repairable(new OcrInvoiceReadiness()).isEmpty());
  }

  @Test
  void aCleanInvoiceNeverReachesTheModel() {
    // Also the disabled path: repair() short-circuits before any chat call.
    OcrExtractResult result = new OcrExtractResult();
    BgOcrAiCorrection out = service.repair(null, result, new OcrInvoiceReadiness());
    assertFalse(out.isAiApplied());
    assertTrue(out.isEmpty());
    assertEquals(result, out.getResult(), "the untouched extraction must come back");
  }

  @Test
  void disabledByDefaultSoIntakeIsUnchanged() {
    // Both flags must be on; the master OCR-AI switch alone is not enough to start a second pass.
    assertFalse(
        new BgOcrLineRepairService() {
          @Override
          protected String cfg(String key, String def) {
            return "aiaccountant.ocr.enabled".equals(key) ? "true" : "false";
          }
        }.isEnabled());

    assertTrue(
        new BgOcrLineRepairService() {
          @Override
          protected String cfg(String key, String def) {
            return "true";
          }
        }.isEnabled());
  }

  @Test
  void repliesAreParsedStrictlyAndProseIsRejected() {
    assertNull(service.parseStrict(null));
    assertNull(service.parseStrict("   "));
    assertNull(service.parseStrict("I could not find a matching product."));
    assertNull(service.parseStrict("[1,2,3]"));

    assertTrue(service.parseStrict("{\"repairs\":[]}").has("repairs"));
    // A markdown fence is tolerated: models add them despite being told not to.
    assertTrue(service.parseStrict("```json\n{\"repairs\":[{\"lineId\":1}]}\n```").has("repairs"));
  }

  @Test
  void theConfidenceFloorDefaultsHighAndSurvivesGarbageConfig() {
    assertEquals(0.90d, service.minConfidence(), 0.0001d);
    assertEquals(
        0.90d,
        new BgOcrLineRepairService() {
          @Override
          protected String cfg(String key, String def) {
            return "not-a-number";
          }
        }.minConfidence(),
        0.0001d);
  }
}

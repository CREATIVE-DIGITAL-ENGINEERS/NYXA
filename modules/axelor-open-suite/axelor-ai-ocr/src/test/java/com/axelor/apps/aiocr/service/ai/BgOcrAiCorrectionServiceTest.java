package com.axelor.apps.aiocr.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Java-side validation of AI suggestions. This is the safety net: the model's confidence never
 * authorises a change on its own, so every rejection path is exercised here.
 */
class BgOcrAiCorrectionServiceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Config-free instance: overrides the AppSettings-backed knobs. */
  private static BgOcrAiCorrectionService service() {
    return new BgOcrAiCorrectionService() {
      @Override
      public boolean isEnabled() {
        return true;
      }

      @Override
      protected double minConfidence() {
        return 0.90d;
      }

      @Override
      protected int maxRequestBytes() {
        return 131_072;
      }
    };
  }

  private static OcrExtractResult result() {
    OcrExtractResult r = new OcrExtractResult();
    r.setSupplierEik("83191953"); // 8 digits — truncated
    r.setSupplierName("Демо ЕООД");
    r.setInvoiceNumber("1234");
    r.setTotalGross(new BigDecimal("120.00"));
    return r;
  }

  private static Set<String> problems(String... fields) {
    return new LinkedHashSet<>(Arrays.asList(fields));
  }

  private static JsonNode json(String s) throws Exception {
    return MAPPER.readTree(s);
  }

  private static String correction(String field, String value, double confidence) {
    return "{\"corrections\":[{\"field\":\""
        + field
        + "\",\"suggestedValue\":\""
        + value
        + "\",\"confidence\":"
        + confidence
        + ",\"action\":\"SET\",\"reason\":\"test\"}],\"unresolved\":[]}";
  }

  @Test
  void appliesAValidHighConfidenceCorrection() throws Exception {
    OcrExtractResult r = result();
    BgOcrAiCorrection out =
        service()
            .applyValidated(
                r,
                json(correction("supplierEik", "831919536", 0.95)),
                problems("supplierEik"),
                null);

    assertTrue(out.isAiApplied());
    assertEquals("831919536", r.getSupplierEik());
    assertEquals(1, out.getApplied().size());
    assertEquals("83191953", out.getApplied().get(0).getOldValue());
    assertNotNull(out.toNotes());
  }

  @Test
  void rejectsLowConfidence() throws Exception {
    OcrExtractResult r = result();
    BgOcrAiCorrection out =
        service()
            .applyValidated(
                r,
                json(correction("supplierEik", "831919536", 0.5)),
                problems("supplierEik"),
                null);

    assertFalse(out.isAiApplied());
    assertEquals("83191953", r.getSupplierEik(), "value must be untouched");
    assertEquals(1, out.getRejected().size());
  }

  @Test
  void rejectsFieldThatWasNotReportedAsAProblem() throws Exception {
    OcrExtractResult r = result();
    // The model tries to rewrite the invoice number, which nobody flagged.
    BgOcrAiCorrection out =
        service()
            .applyValidated(
                r, json(correction("invoiceNo", "9999", 0.99)), problems("supplierEik"), null);

    assertFalse(out.isAiApplied());
    assertEquals("1234", r.getInvoiceNumber());
    assertTrue(out.getRejected().get(0).contains("not reported"));
  }

  @Test
  void rejectsUnknownField() throws Exception {
    OcrExtractResult r = result();
    BgOcrAiCorrection out =
        service()
            .applyValidated(
                r, json(correction("bankAccountOwner", "x", 0.99)), problems("supplierEik"), null);
    assertFalse(out.isAiApplied());
    assertTrue(out.getRejected().get(0).contains("unknown field"));
  }

  @Test
  void rejectsImplausibleEik() throws Exception {
    OcrExtractResult r = result();
    BgOcrAiCorrection out =
        service()
            .applyValidated(
                r, json(correction("supplierEik", "42", 0.99)), problems("supplierEik"), null);
    assertFalse(out.isAiApplied());
    assertEquals("83191953", r.getSupplierEik());
  }

  @Test
  void rejectsIbanWithBadChecksum() throws Exception {
    OcrExtractResult r = result();
    BgOcrAiCorrection out =
        service()
            .applyValidated(
                r,
                json(correction("iban", "BG80BNBG96611020345679", 0.99)),
                problems("iban"),
                null);
    assertFalse(out.isAiApplied());
    assertNull(r.getIban());
  }

  @Test
  void acceptsValidIbanAndNormalisesIt() throws Exception {
    OcrExtractResult r = result();
    BgOcrAiCorrection out =
        service()
            .applyValidated(
                r,
                json(correction("iban", "BG80 BNBG 9661 1020 3456 78", 0.99)),
                problems("iban"),
                null);
    assertTrue(out.isAiApplied());
    assertEquals("BG80BNBG96611020345678", r.getIban());
  }

  @Test
  void rejectsFutureDate() throws Exception {
    OcrExtractResult r = result();
    String future = LocalDate.now().plusDays(10).toString();
    BgOcrAiCorrection out =
        service()
            .applyValidated(
                r, json(correction("invoiceDate", future, 0.99)), problems("invoiceDate"), null);
    assertFalse(out.isAiApplied());
    assertNull(r.getInvoiceDate());
  }

  @Test
  void ignoresClearAndNoneActions() throws Exception {
    OcrExtractResult r = result();
    String body =
        "{\"corrections\":[{\"field\":\"supplierEik\",\"suggestedValue\":\"831919536\","
            + "\"confidence\":0.99,\"action\":\"CLEAR\"}]}";
    BgOcrAiCorrection out = service().applyValidated(r, json(body), problems("supplierEik"), null);
    assertFalse(out.isAiApplied());
    assertEquals("83191953", r.getSupplierEik());
  }

  @Test
  void parseStrictHandlesFencedAndSurroundedJson() {
    BgOcrAiCorrectionService s = service();
    assertNotNull(s.parseStrict("```json\n{\"corrections\":[]}\n```"));
    assertNotNull(s.parseStrict("Here you go: {\"corrections\":[]} hope that helps"));
    assertNull(s.parseStrict("I could not determine anything."));
    assertNull(s.parseStrict(""));
    assertNull(s.parseStrict(null));
  }

  @Test
  void disabledServiceReturnsUntouchedResult() {
    BgOcrAiCorrectionService disabled =
        new BgOcrAiCorrectionService() {
          @Override
          public boolean isEnabled() {
            return false;
          }
        };
    OcrExtractResult r = result();
    BgOcrAiCorrection out = disabled.correct(null, r, false);

    assertFalse(out.isAiApplied());
    assertTrue(out.isEmpty());
    assertNull(out.toNotes(), "no notes must be written when AI is off");
    assertEquals(r, out.getResult());
  }

  @Test
  void trimMiddleKeepsHeadAndTail() {
    StringBuilder sb = new StringBuilder();
    sb.append("HEADSTART");
    for (int i = 0; i < 5000; i++) {
      sb.append('x');
    }
    sb.append("TAILEND");
    String trimmed = BgOcrAiCorrectionService.trimMiddle(sb.toString(), 200);

    assertTrue(trimmed.startsWith("HEADSTART"), "identity block must survive");
    assertTrue(trimmed.endsWith("TAILEND"), "payment block must survive");
    assertTrue(trimmed.contains("truncated"));
    assertTrue(trimmed.length() < 400);
  }

  @Test
  void notesRecordRejectionsAndUnresolved() throws Exception {
    OcrExtractResult r = result();
    String body =
        "{\"corrections\":[{\"field\":\"supplierEik\",\"suggestedValue\":\"42\","
            + "\"confidence\":0.99,\"action\":\"SET\"}],\"unresolved\":[\"iban\"]}";
    BgOcrAiCorrection out =
        service().applyValidated(r, json(body), problems("supplierEik", "iban"), null);

    String notes = out.toNotes();
    assertNotNull(notes);
    assertTrue(notes.contains("rejected"));
    assertTrue(notes.contains("iban"));
  }
}

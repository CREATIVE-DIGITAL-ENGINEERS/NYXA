package com.axelor.apps.napsubmissions.services.declarations.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

/**
 * Serialization/deserialization tests against the official Declarations contract shapes — using the
 * same Jackson configuration as {@link NraDeclarationsApiClient} (omit nulls, ignore unknown
 * fields).
 */
class DeclarationsDtoJsonTest {

  private final ObjectMapper mapper =
      new ObjectMapper()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
          .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  @Test
  void submitRequestSerializesEnumsByNameAndOmitsNulls() throws Exception {
    DeclarationsSubmitRequest req = new DeclarationsSubmitRequest();
    req.taxpayerPin = "987654321";
    req.taxpayerPinType = PinType.BUS_BULSTAT;
    req.userPin = "1234567890";
    req.userPinType = PinType.IND_EGN;
    req.serviceDocumentType = ServiceDocumentType.DEC_1_6;
    req.userSignatureBase64 = "Y2VydA==";
    req.taxPeriodFrom = "03";
    req.taxPeriodTo = "03";
    req.year = "2023";
    req.insuranceFund = 0;

    DeclarationsFile f = new DeclarationsFile();
    f.fileName = "file_D1";
    f.fileType = "txt";
    f.fileSize = 631;
    f.fileContentBase64 = "Y29udGVudA==";
    f.base64EncodedPkcs7 = "cGtjczc=";
    f.fileDocumentType = FileDocumentType.DEC_1;
    f.numRecords = 1;
    req.files = new ArrayList<>();
    req.files.add(f);

    String json = mapper.writeValueAsString(req);
    assertTrue(json.contains("\"taxpayerPinType\":\"BUS_BULSTAT\""));
    assertTrue(json.contains("\"serviceDocumentType\":\"DEC_1_6\""));
    assertTrue(json.contains("\"fileDocumentType\":\"DEC_1\""));
    assertTrue(json.contains("\"insuranceFund\":0"));
    assertTrue(json.contains("\"numRecords\":1"));
    // A DEC_1 request has no reason to carry a correlation-style null field; nulls are omitted.
    assertFalse(json.contains("null"));
  }

  @Test
  void submitResponseDeserializes() throws Exception {
    String json =
        "{\"entryNumber\":\"30E000000001\",\"entryDate\":\"2023-07-14\",\"documentId\":1}";
    DeclarationsSubmitResponse r = mapper.readValue(json, DeclarationsSubmitResponse.class);
    assertEquals(1L, r.documentId);
    assertEquals("30E000000001", r.entryNumber);
    assertEquals("2023-07-14", r.entryDate);
  }

  @Test
  void errorDeserializesAndIgnoresUnknownFields() throws Exception {
    String json =
        "{\"code\":400,\"errorCode\":\"API_DUPLICATED_FILE\",\"exceptionId\":\"abc\","
            + "\"i18nMessage\":\"dup\",\"timestamp\":\"2026-03-24T10:00:00Z\",\"extra\":\"x\"}";
    DeclarationsApiError e = mapper.readValue(json, DeclarationsApiError.class);
    assertEquals("API_DUPLICATED_FILE", e.errorCode);
    assertEquals("abc", e.exceptionId);
    assertEquals(Integer.valueOf(400), e.code);
  }

  @Test
  void resultResponseDeserializes() throws Exception {
    String json =
        "{\"entryNumber\":\"30E000000000\",\"entryDate\":\"2023-01-01\","
            + "\"base64HtmlResult\":\"PHRhYmxlPg==\",\"taxPeriodFrom\":\"2025-09-01\","
            + "\"taxPeriodTo\":\"2025-09-30\"}";
    DeclarationsResultResponse r = mapper.readValue(json, DeclarationsResultResponse.class);
    assertEquals("30E000000000", r.entryNumber);
    assertEquals("PHRhYmxlPg==", r.base64HtmlResult);
    assertEquals("2025-09-01", r.taxPeriodFrom);
  }
}

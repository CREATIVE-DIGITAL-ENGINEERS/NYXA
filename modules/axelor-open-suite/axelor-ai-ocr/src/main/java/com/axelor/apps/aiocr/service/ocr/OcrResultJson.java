package com.axelor.apps.aiocr.service.ocr;

import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * (De)serializes an {@link OcrExtractResult} to JSON so a split child can carry the per-segment OCR
 * result of the single Azure call and {@code BgOcrIntakeService.process()} can reuse it instead of
 * calling Azure again (OCR-once). {@code java.time} is handled via auto-registered modules.
 */
public final class OcrResultJson {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private OcrResultJson() {}

  public static String toJson(OcrExtractResult result) {
    if (result == null) {
      return null;
    }
    try {
      return MAPPER.writeValueAsString(result);
    } catch (Exception e) {
      throw new IllegalStateException("Could not serialize OCR result: " + e.getMessage(), e);
    }
  }

  public static OcrExtractResult fromJson(String json) {
    if (json == null || json.trim().isEmpty()) {
      return null;
    }
    try {
      return MAPPER.readValue(json, OcrExtractResult.class);
    } catch (Exception e) {
      throw new IllegalStateException("Could not deserialize OCR result: " + e.getMessage(), e);
    }
  }
}

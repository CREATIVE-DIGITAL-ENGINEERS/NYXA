package com.axelor.apps.aiocr.service.ocr;

/**
 * Raised when the OCR extraction service (Azure Document Intelligence) is unavailable or
 * misbehaving: connection failure, timeout, analysis timeout, HTTP 401/403/429/5xx after bounded
 * retries, malformed/empty response, or a failed/cancelled analysis operation.
 *
 * <p>Distinct from a document the service could read but rejected as unreadable/unsupported — those
 * return an {@link com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult} with {@code
 * extractionFailed=true} so the intake gate fails the job inline. Propagating this from the poller
 * lets the job be recorded FAILED (via {@code BgOcrIntakeService.markFailed}) without an invoice.
 */
public class OcrServiceException extends RuntimeException {

  public OcrServiceException(String message) {
    super(message);
  }

  public OcrServiceException(String message, Throwable cause) {
    super(message, cause);
  }
}

package com.axelor.apps.aiocr.db.repo;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axelor.rpc.Request;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The mass-update REST path (JPQL UPDATE, no row load, no Hibernate event) is refused in the
 * request when it targets a protected OCR column — the one write path the flush-layer listener
 * cannot see.
 */
class OcrInvoiceRequestGuardTest {

  private static Request massUpdate(Map<String, Object> values) {
    Request r = new Request();
    r.setRecords(Collections.singletonList(values));
    return r;
  }

  @Test
  void massUpdatingACorrectionColumnIsRefused() {
    Request r = massUpdate(Collections.singletonMap("self.ocrDocumentTotalCorrected", 86.73));
    assertThrows(
        IllegalArgumentException.class, () -> OcrInvoiceRequestGuard.rejectProtectedMassUpdate(r));
  }

  @Test
  void massUpdatingTheExtractedTotalIsRefused() {
    Request r = massUpdate(Collections.singletonMap("self.ocrDocumentTotal", 80.75));
    assertThrows(
        IllegalArgumentException.class, () -> OcrInvoiceRequestGuard.rejectProtectedMassUpdate(r));
  }

  @Test
  void massUpdatingAnOrdinaryColumnIsAllowed() {
    Request r = massUpdate(Collections.singletonMap("self.note", "hello"));
    assertDoesNotThrow(() -> OcrInvoiceRequestGuard.rejectProtectedMassUpdate(r));
  }

  @Test
  void anEmptyRequestIsAllowed() {
    assertDoesNotThrow(() -> OcrInvoiceRequestGuard.rejectProtectedMassUpdate(new Request()));
    Request withData = new Request();
    withData.setRecords(List.<Object>of());
    assertDoesNotThrow(() -> OcrInvoiceRequestGuard.rejectProtectedMassUpdate(withData));
  }
}

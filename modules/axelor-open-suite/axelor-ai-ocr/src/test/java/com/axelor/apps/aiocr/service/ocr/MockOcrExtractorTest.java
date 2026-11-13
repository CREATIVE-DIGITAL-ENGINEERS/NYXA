package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.meta.db.MetaFile;
import org.junit.jupiter.api.Test;

class MockOcrExtractorTest {

  @Test
  void goodFileReturnsCannedInvoice() {
    OcrExtractResult r = new MockOcrExtractor().extract(null);
    assertFalse(r.isExtractionFailed());
    assertEquals("mock", r.getEngineUsed());
    assertEquals("0000012345", r.getInvoiceNumber());
    assertEquals(1, r.getLines().size());
  }

  @Test
  void failFilenameTriggersFailure() {
    MetaFile file = mock(MetaFile.class);
    when(file.getFileName()).thenReturn("scan-fail.pdf");
    OcrExtractResult r = new MockOcrExtractor().extract(file);
    assertTrue(r.isExtractionFailed());
    assertFalse(r.getErrors().isEmpty());
  }
}

package com.axelor.apps.aiocr.service.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pure tests for the supported-type allow-list. */
class AiOcrSupportedTypesTest {

  @Test
  void supportedExtensions() {
    assertTrue(AiOcrSupportedTypes.isSupportedFileName("invoice.pdf"));
    assertTrue(AiOcrSupportedTypes.isSupportedFileName("scan.PNG"));
    assertTrue(AiOcrSupportedTypes.isSupportedFileName("a.jpg"));
    assertTrue(AiOcrSupportedTypes.isSupportedFileName("a.jpeg"));
    assertTrue(AiOcrSupportedTypes.isSupportedFileName("a.tif"));
    assertTrue(AiOcrSupportedTypes.isSupportedFileName("folder/sub/a.tiff"));
  }

  @Test
  void unsupportedExtensions() {
    assertFalse(AiOcrSupportedTypes.isSupportedFileName("notes.docx"));
    assertFalse(AiOcrSupportedTypes.isSupportedFileName("data.csv"));
    assertFalse(AiOcrSupportedTypes.isSupportedFileName("noext"));
    assertFalse(AiOcrSupportedTypes.isSupportedFileName("archive.pdf.zip"));
    assertFalse(AiOcrSupportedTypes.isSupportedFileName(null));
  }

  @Test
  void zipDetection() {
    assertTrue(AiOcrSupportedTypes.isZip("bundle.zip"));
    assertTrue(AiOcrSupportedTypes.isZip("a/b/inner.ZIP"));
    assertFalse(AiOcrSupportedTypes.isZip("a.pdf"));
  }

  @Test
  void zipUploadPrefersTheExtensionOverTheMimeType() {
    assertTrue(AiOcrSupportedTypes.isZipUpload("bundle.zip", "application/zip"));
    assertTrue(AiOcrSupportedTypes.isZipUpload("BUNDLE.ZIP", null));
    // No extension → the MIME type is all there is.
    assertTrue(AiOcrSupportedTypes.isZipUpload("bundle", "application/zip"));
    assertTrue(AiOcrSupportedTypes.isZipUpload("bundle", "application/x-zip-compressed"));

    assertFalse(AiOcrSupportedTypes.isZipUpload("invoice.pdf", "application/pdf"));
    // The ZIP-container trap: an OOXML file IS a zip, and browsers do report it as one. The
    // extension has to win, or a spreadsheet reaches extraction and fails the carrier.
    assertFalse(AiOcrSupportedTypes.isZipUpload("book.xlsx", "application/zip"));
    assertFalse(AiOcrSupportedTypes.isZipUpload("notes.docx", "application/zip"));
    assertFalse(AiOcrSupportedTypes.isZipUpload("bundle", null));
    assertFalse(AiOcrSupportedTypes.isZipUpload(null, null));
  }

  @Test
  void mimeDetection() {
    assertTrue(AiOcrSupportedTypes.isSupportedMime("application/pdf"));
    assertTrue(AiOcrSupportedTypes.isSupportedMime("image/tiff; charset=binary"));
    assertFalse(AiOcrSupportedTypes.isSupportedMime("text/plain"));
    assertFalse(AiOcrSupportedTypes.isSupportedMime(null));
  }

  @Test
  void extensionOf() {
    assertEquals("pdf", AiOcrSupportedTypes.extensionOf("a/b/c.PDF"));
    assertEquals("", AiOcrSupportedTypes.extensionOf("noext"));
    assertEquals("", AiOcrSupportedTypes.extensionOf("dir.with.dot/file"));
  }
}

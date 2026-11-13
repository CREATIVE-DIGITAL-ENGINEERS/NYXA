package com.axelor.apps.aiocr.service.optimize;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pure tests for the PDFBox-only lossless normalization accept rule and the untrusted-PDF {@code
 * inspect} guard. The normalizer keeps a normalized file only when PDFBox can reopen it and the
 * page count is preserved (no content dropped) — never based on image quality, DPI or lossy
 * heuristics. {@code inspect} must recognise an encrypted PDF (so the caller can fail clearly) and
 * must fail safely on malformed bytes rather than throwing.
 */
class PdfOptimizationServiceTest {

  @Test
  void acceptOnlyWhenReopenableAndPageCountPreserved() {
    // valid (non-empty), same page count → accept
    assertTrue(PdfOptimizationService.accept(400, 3, 3));
    // page-count mismatch → reject (content dropped)
    assertFalse(PdfOptimizationService.accept(400, 3, 2));
    // empty output → reject
    assertFalse(PdfOptimizationService.accept(0, 3, 3));
    // unknown original page count → reject (cannot prove pages preserved)
    assertFalse(PdfOptimizationService.accept(400, null, 3));
    // unknown output page count (unreadable output) → reject
    assertFalse(PdfOptimizationService.accept(400, 3, null));
  }

  @Test
  void inspectReadsPageCountOfAPlainPdf(@TempDir Path dir) throws Exception {
    Path pdf = dir.resolve("plain.pdf");
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage());
      doc.addPage(new PDPage());
      doc.save(pdf.toFile());
    }
    PdfOptimizationService.PdfInfo info = new PdfOptimizationService().inspect(pdf);
    assertTrue(info.readable);
    assertFalse(info.encrypted);
    assertTrue(info.pages != null && info.pages == 2);
  }

  @Test
  void inspectFlagsAnEncryptedPdfInsteadOfShippingItOnward(@TempDir Path dir) throws Exception {
    Path pdf = dir.resolve("encrypted.pdf");
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage());
      StandardProtectionPolicy policy =
          new StandardProtectionPolicy("owner-secret", "user-secret", new AccessPermission());
      policy.setEncryptionKeyLength(128);
      doc.protect(policy);
      doc.save(pdf.toFile());
    }
    PdfOptimizationService.PdfInfo info = new PdfOptimizationService().inspect(pdf);
    assertTrue(info.encrypted, "a password-protected PDF must be flagged, not silently parsed");
    assertFalse(info.readable);
    assertNull(info.pages);
  }

  @Test
  void inspectFailsSafelyOnMalformedBytes(@TempDir Path dir) throws Exception {
    Path junk = dir.resolve("broken.pdf");
    Files.write(junk, "%PDF-1.7 this is not a real pdf".getBytes(StandardCharsets.UTF_8));
    PdfOptimizationService.PdfInfo info = new PdfOptimizationService().inspect(junk);
    assertFalse(info.readable);
    assertFalse(info.encrypted);
    assertNull(info.pages);
  }

  @Test
  void inspectFailsSafelyOnAMissingFile(@TempDir Path dir) {
    PdfOptimizationService.PdfInfo info =
        new PdfOptimizationService().inspect(dir.resolve("does-not-exist.pdf"));
    assertFalse(info.readable);
    assertFalse(info.encrypted);
    assertNull(info.pages);
  }
}

package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pure tests for the shared 255-char truncation applied to every OCR-derived Move string (origin,
 * description, warning text, move-line description) so a long/garbage OCR value can never overflow
 * a varchar(255) column and fail the Move insert.
 */
class BgOcrTruncateTest {

  private String repeat(char c, int n) {
    StringBuilder sb = new StringBuilder(n);
    for (int i = 0; i < n; i++) {
      sb.append(c);
    }
    return sb.toString();
  }

  @Test
  void longStringIsTruncatedToExactly255() {
    String input = repeat('x', 300);
    String out = BgOcrIntakeService.truncateTo255(input);
    assertEquals(255, out.length());
    assertTrue(input.startsWith(out)); // prefix preserved, tail dropped
  }

  @Test
  void veryLongUnicodeValueTruncated() {
    // Simulate a garbage OCR "invoice number" far beyond the column width.
    String input = repeat('Я', 1000);
    assertEquals(255, BgOcrIntakeService.truncateTo255(input).length());
  }

  @Test
  void exactly255Unchanged() {
    String input = repeat('a', 255);
    assertEquals(input, BgOcrIntakeService.truncateTo255(input));
  }

  @Test
  void shortStringUnchanged() {
    assertEquals("only-test.pdf", BgOcrIntakeService.truncateTo255("only-test.pdf"));
  }

  @Test
  void nullSafe() {
    assertNull(BgOcrIntakeService.truncateTo255(null));
  }
}

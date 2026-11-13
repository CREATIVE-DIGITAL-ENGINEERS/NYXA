package com.axelor.apps.aiocr.service.supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The canonical ЕИК is digits only: a BG VAT number, an "ЕИК:"/"БУЛСТАТ" label and any spaces or
 * punctuation are stripped; a foreign VAT or a wrong length yields null. VAT is never stored inside
 * the ЕИК value.
 */
class SupplierEikTest {

  @Test
  void plainEikPassesThrough() {
    assertEquals("203970390", SupplierEik.canonical("203970390"));
    assertEquals("1234567890123", SupplierEik.canonical("1234567890123"));
  }

  @Test
  void stripsBgVatPrefix() {
    assertEquals("203970390", SupplierEik.canonical("BG203970390"));
    assertEquals("203970390", SupplierEik.canonical("bg203970390"));
    assertEquals("1234567890123", SupplierEik.canonical("BG1234567890123"));
  }

  @Test
  void stripsLabelSpacesAndPunctuation() {
    assertEquals("203970390", SupplierEik.canonical("ЕИК:203970390"));
    assertEquals("203970390", SupplierEik.canonical("ЕИК: 203 970 390"));
    assertEquals("203970390", SupplierEik.canonical("БУЛСТАТ 203970390"));
    assertEquals("203970390", SupplierEik.canonical("203-970-390"));
    assertEquals("203970390", SupplierEik.canonical("  203970390  "));
  }

  @Test
  void nullWhenNotAUsableEik() {
    assertNull(SupplierEik.canonical(null));
    assertNull(SupplierEik.canonical(""));
    assertNull(SupplierEik.canonical("   "));
    assertNull(SupplierEik.canonical("Some Supplier Ltd"));
    assertNull(SupplierEik.canonical("12345")); // too short
    assertNull(SupplierEik.canonical("FR12345678901")); // foreign VAT, not a BG ЕИК
  }
}

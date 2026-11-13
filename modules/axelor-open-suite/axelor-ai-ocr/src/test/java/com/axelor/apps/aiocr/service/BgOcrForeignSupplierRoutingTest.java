package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.aiocr.service.BgOcrIntakeService.SupplierOrigin;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import org.junit.jupiter.api.Test;

/**
 * After a document is confidently an invoice, the SUPPLIER's country is decided from strong
 * supplier-side signals (VAT/IBAN country, corroborated address) — never the document language and
 * never the buyer identity. Foreign → Move (NAP Фактура); BG → Supplier Invoice; unclear → stay
 * conservative.
 */
class BgOcrForeignSupplierRoutingTest {

  private static final String OUR_BG_ID = "205123456";

  private static OcrExtractResult r() {
    return new OcrExtractResult();
  }

  private static SupplierOrigin origin(OcrExtractResult r) {
    return BgOcrIntakeService.classifySupplierOrigin(r, OUR_BG_ID);
  }

  @Test
  void foreignByVendorVatPrefix() {
    OcrExtractResult r = r();
    r.setSupplierVat("HU12345678");
    assertEquals(SupplierOrigin.FOREIGN, origin(r));
    OcrExtractResult de = r();
    de.setSupplierVat("DE123456789");
    assertEquals(SupplierOrigin.FOREIGN, origin(de));
    // A VAT whose body carries a letter (e.g. NL) still classifies by its country prefix.
    OcrExtractResult nl = r();
    nl.setSupplierVat("NL123456789B01");
    assertEquals(SupplierOrigin.FOREIGN, origin(nl));
  }

  @Test
  void foreignByVendorIbanCountry() {
    OcrExtractResult r = r();
    r.setIban("HU42117730161111101800000000");
    assertEquals(SupplierOrigin.FOREIGN, origin(r));
  }

  @Test
  void bulgarianByVatOrEikOrIban() {
    OcrExtractResult vat = r();
    vat.setSupplierVat("BG203970390");
    assertEquals(SupplierOrigin.BULGARIAN, origin(vat));
    OcrExtractResult eik = r();
    eik.setSupplierEik("203970390");
    assertEquals(SupplierOrigin.BULGARIAN, origin(eik));
    // A prefix-less BG-shaped number in the VAT field is a BG ЕИК, not a foreign VAT.
    OcrExtractResult plain = r();
    plain.setSupplierVat("203970390");
    assertEquals(SupplierOrigin.BULGARIAN, origin(plain));
    OcrExtractResult iban = r();
    iban.setIban("BG80BNBG96611020345678");
    assertEquals(SupplierOrigin.BULGARIAN, origin(iban));
  }

  @Test
  void contradictoryIdentityIsUnclear() {
    OcrExtractResult r = r();
    r.setSupplierVat("HU12345678");
    r.setSupplierEik("203970390"); // BG ЕИК alongside a foreign VAT → do not guess
    assertEquals(SupplierOrigin.UNCLEAR, origin(r));
  }

  @Test
  void noSignalsIsUnclear() {
    OcrExtractResult r = r();
    r.setSupplierName("Some Supplier Ltd");
    r.setInvoiceNumber("INV-1");
    assertEquals(SupplierOrigin.UNCLEAR, origin(r));
  }

  @Test
  void foreignAddressNeedsCorroboration() {
    // Foreign supplier address, but the buyer is our BG company → confidently foreign.
    OcrExtractResult ok = r();
    ok.setSupplierAddress("1054 Budapest, Szabadsag ter 1, Hungary");
    ok.setCustomerVat("BG" + OUR_BG_ID);
    assertEquals(SupplierOrigin.FOREIGN, origin(ok));

    // Same foreign address but no corroboration (unknown buyer, no BIC) → stay conservative.
    OcrExtractResult weak = r();
    weak.setSupplierAddress("1054 Budapest, Szabadsag ter 1, Hungary");
    assertEquals(SupplierOrigin.UNCLEAR, origin(weak));

    // Non-EU supplier (China) with no EU VAT/IBAN, but the buyer is our BG company → foreign Move.
    OcrExtractResult cn = r();
    cn.setSupplierAddress("No. 168 Dongqing Road, Xuyi County, Jiangsu Province, China");
    cn.setCustomerVat("BG" + OUR_BG_ID);
    assertEquals(SupplierOrigin.FOREIGN, origin(cn));
  }

  @Test
  void doesNotMistakeBuyerVatForSupplier() {
    // The supplier is BG; only the BUYER carries a foreign VAT — must NOT become foreign.
    OcrExtractResult r = r();
    r.setSupplierVat("BG203970390");
    r.setCustomerVat("HU12345678");
    assertEquals(SupplierOrigin.BULGARIAN, origin(r));
  }

  @Test
  void countryHelpers() {
    assertEquals("HU", BgOcrIntakeService.vatCountry("HU12345678"));
    assertEquals("BG", BgOcrIntakeService.vatCountry("BG 203 970 390"));
    assertEquals(null, BgOcrIntakeService.vatCountry("203970390"));
    assertEquals("DE", BgOcrIntakeService.ibanCountry("DE89 3704 0044 0532 0130 00"));
    assertEquals(null, BgOcrIntakeService.ibanCountry("NOTANIBAN"));
    assertEquals("GB", BgOcrIntakeService.bicCountry("BARCGB22"));
  }
}

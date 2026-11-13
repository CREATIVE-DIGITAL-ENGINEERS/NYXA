package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractLine;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Product;
import org.junit.jupiter.api.Test;

/**
 * Proves the generic OCR purchase product is resolved <b>per company</b> (Company field), with no
 * global default and no first-record fallback. With no supplier product code on the line,
 * resolveProduct never touches the repository, so no Guice/DB runtime is needed.
 */
class BgOcrProductResolutionTest {

  private final BgOcrIntakeService svc = new BgOcrIntakeService(null, null, null, null, null);

  private OcrExtractLine lineWithoutCode() {
    return new OcrExtractLine(); // productCode is null → skip repo lookup, use company field
  }

  @Test
  void genericProductIsResolvedPerCompany() {
    Product productA = mock(Product.class);
    Product productB = mock(Product.class);
    Company companyA = mock(Company.class);
    Company companyB = mock(Company.class);
    when(companyA.getAiOcrGenericPurchaseProduct()).thenReturn(productA);
    when(companyB.getAiOcrGenericPurchaseProduct()).thenReturn(productB);

    // Each company resolves its OWN configured product — not one shared global value.
    assertSame(productA, svc.resolveProduct(lineWithoutCode(), companyA));
    assertSame(productB, svc.resolveProduct(lineWithoutCode(), companyB));
  }

  @Test
  void unconfiguredCompanyYieldsNoProductNoFallback() {
    Company company = mock(Company.class);
    when(company.getAiOcrGenericPurchaseProduct()).thenReturn(null);
    // No global default, no arbitrary first product — description-only + readiness blocker.
    assertNull(svc.resolveProduct(lineWithoutCode(), company));
  }

  @Test
  void nullCompanyYieldsNoProduct() {
    assertNull(svc.resolveProduct(lineWithoutCode(), null));
  }

  // ---------------------------------------------------------------------------
  // Tier 2: exact product-name match
  // ---------------------------------------------------------------------------

  /** Stubs only the name lookup, so the tier ordering can be tested with no repository. */
  private BgOcrIntakeService withNameMatch(Product match) {
    return new BgOcrIntakeService(null, null, null, null, null) {
      @Override
      protected Product resolveProductByName(String description) {
        return description == null || description.trim().isEmpty() ? null : match;
      }
    };
  }

  private OcrExtractLine lineDescribed(String description) {
    OcrExtractLine line = new OcrExtractLine();
    line.setDescription(description);
    return line;
  }

  @Test
  void aNameMatchIsPreferredOverTheGenericProduct() {
    // The reason this tier exists: most BG invoices print no product code, so tier 1 never fires
    // and every line used to collapse onto the generic product (or onto nothing at all).
    Product named = mock(Product.class);
    Product generic = mock(Product.class);
    Company company = mock(Company.class);
    when(company.getAiOcrGenericPurchaseProduct()).thenReturn(generic);

    assertSame(named, withNameMatch(named).resolveProduct(lineDescribed("Секретен ключ"), company));
  }

  @Test
  void withoutANameMatchTheGenericProductStillWins() {
    Product generic = mock(Product.class);
    Company company = mock(Company.class);
    when(company.getAiOcrGenericPurchaseProduct()).thenReturn(generic);

    // resolveProductByName returning null stands for both "no match" and "several matches" — an
    // ambiguous name must fall through rather than be resolved arbitrarily.
    assertSame(generic, withNameMatch(null).resolveProduct(lineDescribed("Услуга"), company));
  }

  @Test
  void aBlankDescriptionNeverReachesTheNameLookup() {
    Company company = mock(Company.class);
    when(company.getAiOcrGenericPurchaseProduct()).thenReturn(null);

    // Guarded before the query: a blank description would otherwise match on an empty name.
    assertNull(svc.resolveProduct(lineDescribed("   "), company));
    assertNull(svc.resolveProductByName(null));
    assertNull(svc.resolveProductByName("  "));
  }
}

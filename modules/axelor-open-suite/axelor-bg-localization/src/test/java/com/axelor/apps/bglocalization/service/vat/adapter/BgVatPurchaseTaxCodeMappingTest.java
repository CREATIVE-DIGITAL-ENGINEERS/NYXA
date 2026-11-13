package com.axelor.apps.bglocalization.service.vat.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import org.junit.jupiter.api.Test;

/** Покупните данъци с неподвижна колона — чистата функция. Без база, без Guice. */
class BgVatPurchaseTaxCodeMappingTest {

  @Test
  void exemptAndZeroRated_areK09_triangularIsK15() {
    // к.9 „…без право на данъчен кредит или без данък“ (ППЗДДС Прил. № 11).
    assertSame(
        BgVatPurchaseTarget.NO_CREDIT, BgVatPurchaseTaxCodeMapping.forTaxCode("BG_EX_P").get());
    assertSame(
        BgVatPurchaseTarget.NO_CREDIT, BgVatPurchaseTaxCodeMapping.forTaxCode("BG_ZRP").get());
    // к.15 „ДО от придобиване на стоки от посредник в тристранна операция“.
    assertSame(
        BgVatPurchaseTarget.TRIANGULAR,
        BgVatPurchaseTaxCodeMapping.forTaxCode(" BG_TRIANG_P ").get());
    assertEquals(
        new LinkedHashSet<>(Arrays.asList("BG_EX_P", "BG_ZRP", "BG_TRIANG_P")),
        BgVatPurchaseTaxCodeMapping.taxCodes());
  }

  @Test
  void anyOtherTax_leavesTheColumnToTheCreditType() {
    for (String tax : new String[] {"BG_SRP_20", "BG_LRP_9", "BG_IMP_STD", "BG_VOP_ACQ", null}) {
      assertFalse(BgVatPurchaseTaxCodeMapping.forTaxCode(tax).isPresent(), String.valueOf(tax));
    }
    assertFalse(BgVatPurchaseTaxCodeMapping.forTaxCodes(null).isPresent());
    assertFalse(BgVatPurchaseTaxCodeMapping.forTaxCodes(Collections.emptyList()).isPresent());
    assertSame(
        BgVatPurchaseTarget.NO_CREDIT,
        BgVatPurchaseTaxCodeMapping.forTaxCodes(Arrays.asList("BG_SRP_20", "BG_EX_P")).get());
  }

  @Test
  void onlyCreditTypeThree_agreesWithK09_andNoneWithK15() {
    BgVatPurchaseTarget k09 = BgVatPurchaseTarget.NO_CREDIT;
    BgVatPurchaseTarget k15 = BgVatPurchaseTarget.TRIANGULAR;
    assertTrue(BgVatPurchaseTaxCodeMapping.agreesWith(k09, "3"));
    assertTrue(BgVatPurchaseTaxCodeMapping.agreesWith(k09, " 3 "));
    assertFalse(BgVatPurchaseTaxCodeMapping.agreesWith(k09, "1"));
    assertFalse(BgVatPurchaseTaxCodeMapping.agreesWith(k09, "2"));
    for (String credit : new String[] {"1", "2", "3"}) {
      assertFalse(BgVatPurchaseTaxCodeMapping.agreesWith(k15, credit), credit);
    }
    // Празното не е спор: решава данъкът и нищо не е прието по подразбиране.
    assertTrue(BgVatPurchaseTaxCodeMapping.agreesWith(k09, null));
    assertTrue(BgVatPurchaseTaxCodeMapping.agreesWith(k15, " "));
  }
}

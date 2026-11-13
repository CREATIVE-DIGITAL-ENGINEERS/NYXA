package com.axelor.apps.bglocalization.service.vat.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.vat.adapter.BgVatMoveOperationRouting.Kind;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatMoveOperationRouting.Route;
import org.junit.jupiter.api.Test;

/** Чистата функция ДДС операция на ръчния запис → маршрут и колони. Без база, без Guice. */
class BgVatMoveOperationRoutingTest {

  private static Route route(String operation) {
    return BgVatMoveOperationRouting.forOperation(operation)
        .orElseThrow(() -> new AssertionError("no route for " + operation));
  }

  private static void purchase(String operation, BgVatPurchaseTarget target) {
    Route r = route(operation);
    assertEquals(Kind.PURCHASE, r.getKind(), operation);
    assertSame(target, r.getPurchaseTarget(), operation);
    // Покупката по операция не гледа кредитния тип на реда.
    assertFalse(r.isPurchaseByCreditType(), operation);
    assertSame(target, r.purchaseTargetFor("3"), operation);
    assertNull(r.getSalesBaseColumn(), operation);
    assertNull(r.getSalesVatColumn(), operation);
  }

  private static void protocol(
      String operation, BgVatSalesColumn salesBase, BgVatSalesColumn salesVat) {
    Route r = route(operation);
    assertEquals(Kind.PROTOCOL, r.getKind(), operation);
    // Входящата страна на протокола е по кредитния тип на реда (ППЗДДС чл. 97а ал. 4 б. „б“):
    // к.10/к.11, к.12/к.13 или к.9; празен тип — пълен кредит.
    assertNull(r.getPurchaseTarget(), operation);
    assertTrue(r.isPurchaseByCreditType(), operation);
    assertSame(BgVatPurchaseTarget.FULL_CREDIT, r.purchaseTargetFor("1"), operation);
    assertSame(BgVatPurchaseTarget.PARTIAL_CREDIT, r.purchaseTargetFor("2"), operation);
    assertSame(BgVatPurchaseTarget.NO_CREDIT, r.purchaseTargetFor("3"), operation);
    assertSame(BgVatPurchaseTarget.FULL_CREDIT, r.purchaseTargetFor(null), operation);
    assertEquals(salesBase, r.getSalesBaseColumn(), operation);
    assertEquals(salesVat, r.getSalesVatColumn(), operation);
  }

  @Test
  void purchaseOperations() {
    purchase("PURCHASE_FULL", BgVatPurchaseTarget.FULL_CREDIT);
    purchase("PURCHASE_PARTIAL", BgVatPurchaseTarget.PARTIAL_CREDIT);
    purchase("PURCHASE_82_NONE", BgVatPurchaseTarget.NO_CREDIT);
    purchase("PURCHASE_ANNUAL", BgVatPurchaseTarget.ANNUAL_ADJUSTMENT);
    purchase("PURCHASE_TRIANGULAR", BgVatPurchaseTarget.TRIANGULAR);
  }

  @Test
  void annualAndTriangularFillOneSideOnly() {
    assertNull(BgVatPurchaseTarget.ANNUAL_ADJUSTMENT.getBaseColumn());
    assertEquals(
        BgVatPurchaseColumn.K14_ANNUAL_ADJUSTMENT,
        BgVatPurchaseTarget.ANNUAL_ADJUSTMENT.getVatColumn());
    assertEquals(
        BgVatPurchaseColumn.K15_BASE_TRIANGULAR_ACQ,
        BgVatPurchaseTarget.TRIANGULAR.getBaseColumn());
    // Заварено: ДДС на тристранната операция не отива в нито една колона.
    assertNull(BgVatPurchaseTarget.TRIANGULAR.getVatColumn());
    assertFalse(BgVatPurchaseTarget.TRIANGULAR.isVatFoldedIntoBase());
  }

  @Test
  void protocolOperations() {
    // ППЗДДС Прил. № 10: к.13 „ДО на ВОП“, к.15 „Начислен ДДС за ВОП и доставки по к. 14“.
    protocol("VOP", BgVatSalesColumn.K13_BASE_ICA, BgVatSalesColumn.K15_VAT_ICA_RC);
    protocol("ART163A", BgVatSalesColumn.K14_BASE_RC, BgVatSalesColumn.K15_VAT_ICA_RC);
    protocol("SERVICE_NONEU", BgVatSalesColumn.K14_BASE_RC, BgVatSalesColumn.K15_VAT_ICA_RC);
    // Получената услуга от ЕС е чл. 82 ал. 2 т. 3 — к.14/к.15, не к.22 (там са НАШИТЕ услуги).
    protocol("SERVICE_EU", BgVatSalesColumn.K14_BASE_RC, BgVatSalesColumn.K15_VAT_ICA_RC);
    // ППЗДДС чл. 49а ал. 4 т. 1: „данъчната основа в колони 9 и 11, съответно данъка в колони 10 и
    // 12“ — к.9/к.10 са тоталите на реда.
    protocol("IMPORT_167A", BgVatSalesColumn.K11_BASE_20, BgVatSalesColumn.K12_VAT_20);
    Route importRoute = route("IMPORT_167A");
    assertEquals(BgVatMoveOperationRouting.SUPPLY_TYPE_IMPORT_167A, importRoute.getSupplyType());
    assertEquals("03", importRoute.getSupplyType());
    assertTrue(importRoute.hasCustomsDocument());
    for (String other : new String[] {"VOP", "SERVICE_EU", "SERVICE_NONEU", "ART163A"}) {
      assertNull(route(other).getSupplyType(), other);
      assertFalse(route(other).hasCustomsDocument(), other);
    }
  }

  @Test
  void art79Protocols() {
    Route correction = route("CORRECTION_79");
    assertEquals(Kind.ART79_CORRECTION, correction.getKind());
    assertNull(correction.getPurchaseTarget());
    assertNull(correction.getSalesBaseColumn());
    assertEquals(BgVatSalesColumn.K12_VAT_20, correction.getSalesVatColumn());

    for (String operation : new String[] {"DEDUCTION_79", "ANNUAL_79A"}) {
      Route r = route(operation);
      assertSame(BgVatPurchaseTarget.FULL_CREDIT_VAT_ONLY, r.getPurchaseTarget(), operation);
      assertNull(r.getSalesBaseColumn(), operation);
      assertNull(r.getSalesVatColumn(), operation);
    }
    assertEquals(Kind.ART79_DEDUCTION, route("DEDUCTION_79").getKind());
    assertEquals(Kind.ART79A_ANNUAL, route("ANNUAL_79A").getKind());
    assertNull(BgVatPurchaseTarget.FULL_CREDIT_VAT_ONLY.getBaseColumn());
    assertEquals(
        BgVatPurchaseColumn.K11_VAT_FULL_CREDIT,
        BgVatPurchaseTarget.FULL_CREDIT_VAT_ONLY.getVatColumn());
  }

  /** Сравнението е точно: интервалите ги реже колекторът, а малките букви са непознати. */
  @Test
  void lookupIsExact() {
    assertFalse(BgVatMoveOperationRouting.forOperation(null).isPresent());
    assertFalse(BgVatMoveOperationRouting.forOperation("vop").isPresent());
    assertFalse(BgVatMoveOperationRouting.forOperation(" VOP ").isPresent());
    assertFalse(BgVatMoveOperationRouting.forOperation("FOO").isPresent());
  }

  /** Нова операция в селекцията без маршрут трябва да падне тук, а не като „неразпозната“. */
  @Test
  void coversExactlyTheSelectionValues() throws Exception {
    assertEquals(
        BgVatPurchaseCreditTypeMappingTest.selectionValues("bg.vat.move.operation.select"),
        BgVatMoveOperationRouting.operations());
  }
}

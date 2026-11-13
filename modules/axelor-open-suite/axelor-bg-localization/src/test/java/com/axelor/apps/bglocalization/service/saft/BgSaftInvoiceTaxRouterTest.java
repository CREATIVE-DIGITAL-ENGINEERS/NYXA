package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceTaxRouter.Kind;
import com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceTaxRouter.Route;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseTarget;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesTaxCodeMapping;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BgSaftInvoiceTaxRouterTest {

  private static final Map<String, BgSaftTaxTableAssembler.MappingRow> MAPPINGS =
      BgSaftInvoiceFixtures.seedTaxMappings();
  private static final Map<String, String> CELLS = BgSaftInvoiceFixtures.seedCellCodes();

  /** TAX-IMP кодът по решението на рутера — през истинската таблица на клетките. */
  private static String code(boolean sales, String tax, String credit) {
    Route route = BgSaftInvoiceTaxRouter.route(sales, tax, credit, MAPPINGS.get(tax));
    switch (route.getKind()) {
      case CODE:
        return route.getValue();
      case CELL:
        return CELLS.get(route.getValue());
      default:
        return route.getKind().name();
    }
  }

  @Test
  @DisplayName("Всеки от 50-те данъка на seed-а е в точно едно семейство на рутера")
  void семействата() {
    assertEquals(50, MAPPINGS.size());
    assertEquals(new TreeSet<>(MAPPINGS.keySet()), new TreeSet<>(BgSaftInvoiceTaxRouter.taxes()));
    int families =
        BgSaftInvoiceTaxRouter.SALES_FIXED.size()
            + BgSaftInvoiceTaxRouter.SALES_BY_DIARY_COLUMN.size()
            + BgSaftInvoiceTaxRouter.SALES_CELLS.size()
            + BgSaftInvoiceTaxRouter.PURCHASE_FIXED.size()
            + BgSaftInvoiceTaxRouter.PURCHASE_BY_CREDIT.size()
            + BgSaftInvoiceTaxRouter.PURCHASE_CELLS.size()
            + BgSaftInvoiceTaxRouter.UNRESOLVED.size()
            + BgSaftInvoiceTaxRouter.PROTOCOL.size();
    assertEquals(50, families, "данък в две семейства");
  }

  @Test
  @DisplayName("Продажби с фиксиран код — прекият код от BgSafTaxMapping")
  void продажби_фиксирани() {
    assertEquals("100211", code(true, "BG_SRS_20", null));
    for (String tax : List.of("BG_LRS_9", "BG_LRS_9_BOOK", "BG_LRS_9_BABY", "BG_LRS_9_REST")) {
      assertEquals("100213", code(true, tax, null), tax);
    }
    assertEquals("100219", code(true, "BG_EX_S", null));
    assertEquals("100215", code(true, "BG_VOD", null));
    assertEquals("100217", code(true, "BG_EU_S_RCM_S", null));
    assertEquals("100214", code(true, "BG_EXP", null));
    assertEquals("100218", code(true, "BG_NON_EU_SERV_S", null));
    assertEquals("100225", code(true, "BG_TRIANG_S", null));
  }

  @Test
  @DisplayName("Фиксираният продажбен код съвпада с колоната на Блок Б за същия данък")
  void продажби_като_дневника() {
    List<String> drift = new ArrayList<>();
    for (String tax : BgSaftInvoiceTaxRouter.SALES_FIXED) {
      BgVatSalesTaxCodeMapping.ColumnTarget target =
          BgVatSalesTaxCodeMapping.forTaxCode(tax).orElse(null);
      assertNotNull(target, tax + " няма колона в Блок Б");
      String viaDiary = CELLS.get(BgSaftVatCell.ofSales(target.getBaseColumn()));
      if (!MAPPINGS.get(tax).getSafTaxCode().equals(viaDiary)) {
        drift.add(tax + ": мапинг " + MAPPINGS.get(tax).getSafTaxCode() + ", дневник " + viaDiary);
      }
    }
    assertEquals(List.of(), drift);
  }

  @Test
  @DisplayName("Покупки по кредита: 1 → 100331, 2 → 100332, 3 → 100330, празно → 100331 с флаг")
  void покупки_по_кредита() {
    assertEquals("100331", code(false, "BG_SRP_20", "1"));
    assertEquals("100332", code(false, "BG_SRP_20", "2"));
    assertEquals("100330", code(false, "BG_SRP_20", "3"));
    assertEquals("100331", code(false, "BG_SRP_20", null));
    assertTrue(
        BgSaftInvoiceTaxRouter.route(false, "BG_SRP_20", " ", MAPPINGS.get("BG_SRP_20"))
            .isCreditDefaulted());
    assertFalse(
        BgSaftInvoiceTaxRouter.route(false, "BG_SRP_20", "1", MAPPINGS.get("BG_SRP_20"))
            .isCreditDefaulted());
    // Покупка с 9 % — пак 100331 (QA с. 81); внос по чл. 55 — по кредита.
    assertEquals("100331", code(false, "BG_LRP_9", "1"));
    assertEquals("100332", code(false, "BG_IMP_STD", "2"));
  }

  @Test
  @DisplayName("Покупки с еднозначна клетка: освободени и 0 % → 100330, тристранна → 100344")
  void покупки_фиксирани() {
    assertEquals("100330", code(false, "BG_EX_P", "1"));
    assertEquals("100344", code(false, "BG_TRIANG_P", "1"));
    // ZRP не следва кредита: к.9 е „…или без данък“ (Прил. № 11), QA с. 81.
    assertEquals("100330", code(false, "BG_ZRP", "1"));
    assertEquals("100330", code(false, "BG_ZRP", "2"));
  }

  @Test
  @DisplayName("OSS/MOSS → 100218; ZRS → колоната на Блок Б к.19 → 02-14 → 100214")
  void решените() {
    assertEquals("100218", code(true, "BG_OSS_EU", null));
    assertEquals("100218", code(true, "BG_MOSS", null));
    // Блок Б вече мапва ZRS (к.19, 0 % по глава трета), тоест кодът идва от колоната му.
    assertEquals("100214", code(true, "BG_ZRS", null));
    assertEquals(
        BgVatSalesColumn.K19_BASE_ZERO_CH3,
        BgVatSalesTaxCodeMapping.forTaxCode("BG_ZRS").get().getBaseColumn());
    // И OSS/MOSS на SAF-T са колоната на Блок Б: к.23 → 02-18.
    for (String oss : new String[] {"BG_OSS_EU", "BG_MOSS"}) {
      assertEquals(
          "02-18",
          BgSaftVatCell.ofSales(BgVatSalesTaxCodeMapping.forTaxCode(oss).get().getBaseColumn()));
    }
  }

  @Test
  @DisplayName(
      "Известията с родов данък — ГРЕШКА „нерешен код“; маржовата схема — ГРЕШКА „чака НАП“"
          + " (решение 6 от 14.10.2026)")
  void нерешените() {
    for (String tax : List.of("BG_ADJ_CREDIT", "BG_ADJ_DEBIT")) {
      assertEquals("UNRESOLVED", code(true, tax, null), tax);
      assertEquals("UNRESOLVED", code(false, tax, null), tax);
    }
    for (String tax : List.of("BG_MARGIN", "BG_TOUR", "BG_SECONDHAND")) {
      assertEquals("NAP_PENDING", code(true, tax, null), tax);
      assertEquals("NAP_PENDING", code(false, tax, null), tax);
    }
  }

  @Test
  @DisplayName(
      "Чака НАП (BgSaftNapPending) — преди семейството, от двете страни, с текста на решението")
  void чака_нап() {
    for (String tax : BgSaftNapPending.TAXES.keySet()) {
      for (boolean sales : new boolean[] {true, false}) {
        BgSaftInvoiceTaxRouter.Route route =
            BgSaftInvoiceTaxRouter.route(
                sales, tax, "1", new BgSaftTaxTableAssembler.MappingRow("100", null));
        assertEquals(BgSaftInvoiceTaxRouter.Kind.NAP_PENDING, route.getKind(), tax);
        // След отговора на НАП от 19.10.2026 остава само незапитаното — „да се потвърди“.
        assertTrue(
            route.getReason().endsWith(BgSaftNapPending.OPINION), tax + ": " + route.getReason());
      }
    }
  }

  @Test
  @DisplayName("Протоколен данък във фактура — ГРЕШКА „протоколът е ръчна статия“, от двете страни")
  void протоколните() {
    for (String tax :
        List.of(
            "BG_VOP_ACQ",
            "BG_VOP_SELF",
            "BG_RCM_ART82_P",
            "BG_RCM_SCRAP_S",
            "BG_IMP_ART57",
            "BG_ADJ_ART79")) {
      assertEquals("PROTOCOL", code(true, tax, "1"), tax);
      assertEquals("PROTOCOL", code(false, tax, "1"), tax);
    }
    // Отговорът на НАП по имейл от 19.10.2026: покупните данъци по Прил. № 2 и протоколът по чл.
    // 151в вече не „чакат НАП“ — по рутера са протоколни; получената фактура по 163а и полученият
    // протокол 91/93 се решават по документа (BgSaftInvoicesAssembler).
    for (String tax : List.of("BG_RCM_SCRAP_P", "BG_RCM_GRAIN_P", "BG_PROTO_151V")) {
      assertEquals("PROTOCOL", code(true, tax, "1"), tax);
      assertEquals("PROTOCOL", code(false, tax, "1"), tax);
    }
    // Обратното начисляване извън Прил. № 2 и златото — „да се потвърди“.
    for (String tax : List.of("BG_RCM_MOBILE_P", "BG_RCM_BUILD_P", "BG_GOLD_ART163G")) {
      assertEquals("NAP_PENDING", code(true, tax, "1"), tax);
      assertEquals("NAP_PENDING", code(false, tax, "1"), tax);
    }
  }

  @Test
  @DisplayName(
      "Отговорът на НАП: получена фактура по 163а → 03-30; протоколът по 151в — по кредита")
  void отговорът_на_нап() {
    BgSaftInvoiceTaxRouter.Route received = BgSaftInvoiceTaxRouter.art163aReceivedInvoice();
    assertEquals(Kind.CELL, received.getKind());
    assertEquals("03-30", received.getValue());
    assertEquals(
        java.util.Set.of("BG_RCM_SCRAP_P", "BG_RCM_GRAIN_P"),
        BgSaftInvoiceTaxRouter.ART163A_RECEIVED_TAXES);
    assertEquals("03-31", BgSaftInvoiceTaxRouter.byCredit("1").getValue());
    assertEquals("03-32", BgSaftInvoiceTaxRouter.byCredit("2").getValue());
    assertEquals("03-30", BgSaftInvoiceTaxRouter.byCredit("3").getValue());
    assertTrue(BgSaftInvoiceTaxRouter.byCredit(null).isCreditDefaulted());
  }

  @Test
  @DisplayName("Отрицателни свидетели: грешна посока, данък без мапинг, разминал се мапинг")
  void грешките() {
    assertEquals("WRONG_DIRECTION", code(false, "BG_SRS_20", "1"));
    assertEquals("WRONG_DIRECTION", code(true, "BG_SRP_20", "1"));
    assertEquals(
        Kind.UNKNOWN, BgSaftInvoiceTaxRouter.route(true, "BG_SRS_20", null, null).getKind());
    // Фиксиран данък, който мапингът вече води като динамичен — не се приема мълчаливо.
    assertEquals(
        Kind.UNKNOWN,
        BgSaftInvoiceTaxRouter.route(
                true,
                "BG_SRS_20",
                null,
                new BgSaftTaxTableAssembler.MappingRow("100", "100211", true))
            .getKind());
    assertEquals(
        Kind.UNKNOWN,
        BgSaftInvoiceTaxRouter.route(
                true, "BG_NEW", null, new BgSaftTaxTableAssembler.MappingRow("100", "100211"))
            .getKind());
  }

  @Test
  @DisplayName("Ред без данък: в дневника за покупки → 100330 (QA с. 81), иначе 000/000000")
  void без_данък() {
    Route inDiary = BgSaftInvoiceTaxRouter.untaxed(true);
    assertEquals(Kind.CELL, inDiary.getKind());
    assertEquals("100330", CELLS.get(inDiary.getValue()));
    assertEquals(Kind.NOT_APPLICABLE, BgSaftInvoiceTaxRouter.untaxed(false).getKind());
  }

  @Test
  @DisplayName("Всяка клетка, до която рутерът стига, я има в BgSafVatCellCode")
  void клетките_са_в_таблицата() {
    List<String> missing = new ArrayList<>();
    for (BgVatSalesColumn column : BgVatSalesColumn.values()) {
      String cell = BgSaftVatCell.ofSales(column);
      if (cell != null && !CELLS.containsKey(cell)) {
        missing.add(column + " → " + cell);
      }
    }
    for (BgVatPurchaseTarget target :
        List.of(
            BgVatPurchaseTarget.FULL_CREDIT,
            BgVatPurchaseTarget.PARTIAL_CREDIT,
            BgVatPurchaseTarget.NO_CREDIT,
            BgVatPurchaseTarget.TRIANGULAR)) {
      String cell = BgSaftVatCell.ofPurchase(target);
      if (!CELLS.containsKey(cell)) {
        missing.add(target.getBaseColumn() + " → " + cell);
      }
    }
    BgSaftInvoiceTaxRouter.SALES_CELLS.values().forEach(c -> assertTrue(CELLS.containsKey(c), c));
    BgSaftInvoiceTaxRouter.PURCHASE_CELLS
        .values()
        .forEach(c -> assertTrue(CELLS.containsKey(c), c));
    assertEquals(List.of(), missing);
    // Колоните на ДДС и годишната корекция нямат клетка на основа — TaxCode е кодът на основата.
    assertNull(BgSaftVatCell.ofSales(BgVatSalesColumn.K12_VAT_20));
    assertNull(BgSaftVatCell.ofPurchase(BgVatPurchaseTarget.ANNUAL_ADJUSTMENT));
    // Ключът на стойност в клетка — четирите кода по чл. 163а.
    assertEquals("100227", CELLS.get(BgSaftVatCell.key("02-27", "01")));
    assertEquals("100346", CELLS.get(BgSaftVatCell.key("03-45", "01")));
  }
}

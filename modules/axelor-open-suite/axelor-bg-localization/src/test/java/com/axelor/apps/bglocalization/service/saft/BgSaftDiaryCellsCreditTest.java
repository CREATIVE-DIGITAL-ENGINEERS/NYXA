package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseColumn;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПОКУПНАТА СТРАНА НА ПРОТОКОЛА ПО ЧЛ. 163а И ЧЛ. 167а — по отговора на НАП по имейл от 19.10.2026:
 * една клетка с основата на протокола и S.TI.6 = ползваният данъчен кредит (к.11 + к.13), а не
 * некредитираният данък. Кодът на клетката е от {@code bg_saf_vat_cell_code.csv}.
 */
class BgSaftDiaryCellsCreditTest {

  private static final BigDecimal BASE = new BigDecimal("100.00");
  private static final BigDecimal TAX = new BigDecimal("20.00");
  private static final Map<String, String> CODES = BgSaftInvoiceFixtures.seedCellCodes();
  private static final BgSaftDiaryCells.Rates RATES =
      BgSaftDiaryCells.rates(Collections.emptyList(), new BigDecimal("0.20"));

  private static Map<BgVatPurchaseColumn, BigDecimal> columns(String... pairs) {
    Map<BgVatPurchaseColumn, BigDecimal> m = new EnumMap<>(BgVatPurchaseColumn.class);
    for (int i = 0; i < pairs.length; i += 2) {
      m.put(BgVatPurchaseColumn.valueOf(pairs[i]), new BigDecimal(pairs[i + 1]));
    }
    return m;
  }

  /** Единствената клетка: TAX-IMP кодът, основата и данъкът. */
  private static String[] only(BgSaftDiaryCells.Coding coding) {
    assertEquals(List.of(), coding.getErrors());
    assertEquals(1, coding.getCells().size(), "една клетка");
    BgSaftDiaryCells.Cell cell = coding.getCells().get(0);
    return new String[] {
      CODES.get(cell.getKey()), cell.getBase().toPlainString(), cell.getTax().toPlainString()
    };
  }

  private static BgSaftDiaryCells.Coding purchases(
      Map<BgVatPurchaseColumn, BigDecimal> columns, String supply, String operation) {
    return BgSaftDiaryCells.purchases(columns, supply, operation, RATES, BASE, TAX);
  }

  // ─────────────── чл. 163а ───────────────

  @Test
  @DisplayName("Чл. 163а, част I („01“), пълен кредит → 100346, основа 100, данък 20")
  void код_100346_пълен_кредит() {
    String[] cell =
        only(
            purchases(
                columns("K10_BASE_FULL_CREDIT", "100", "K11_VAT_FULL_CREDIT", "20"),
                "01",
                "ART163A"));
    assertEquals("100346", cell[0]);
    assertEquals("100.00", cell[1]);
    assertEquals("20.00", cell[2]);
  }

  @Test
  @DisplayName("Чл. 163а, част II („02“), частичен кредит → 100345, данък = к.13")
  void код_100345_частичен_кредит() {
    String[] cell =
        only(
            purchases(
                columns("K12_BASE_PARTIAL_CREDIT", "100", "K13_VAT_PARTIAL_CREDIT", "20"),
                "02",
                "ART163A"));
    assertEquals("100345", cell[0]);
    assertEquals("100.00", cell[1]);
    assertEquals("20.00", cell[2]);
  }

  @Test
  @DisplayName("Чл. 163а без право на кредит (к.9) → 100346, данък 0 — не некредитираният 20")
  void код_100346_без_кредит() {
    String[] cell = only(purchases(columns("K09_BASE_NO_CREDIT", "120"), "01", "ART163A"));
    assertEquals("100346", cell[0]);
    assertEquals("100.00", cell[1]);
    assertEquals("0.00", cell[2]);
  }

  @Test
  @DisplayName("Чл. 163а, кредит отчасти пълен и отчасти без — ЕДНА клетка, данъкът е само к.11")
  void код_100346_смесен_кредит() {
    String[] cell =
        only(
            purchases(
                columns(
                    "K09_BASE_NO_CREDIT",
                    "60",
                    "K10_BASE_FULL_CREDIT",
                    "50",
                    "K11_VAT_FULL_CREDIT",
                    "10"),
                "01",
                "ART163A"));
    assertEquals("100346", cell[0]);
    assertEquals("100.00", cell[1]);
    assertEquals("10.00", cell[2]);
  }

  @Test
  @DisplayName("Чл. 163а без к.8а „01“/„02“ — ГРЕШКА, не догадка")
  void без_частта() {
    BgSaftDiaryCells.Coding coding =
        purchases(
            columns("K10_BASE_FULL_CREDIT", "100", "K11_VAT_FULL_CREDIT", "20"), "", "ART163A");
    assertTrue(coding.getCells().isEmpty());
    assertTrue(coding.getErrors().get(0).contains("без к.8а"), coding.getErrors().toString());
  }

  @Test
  @DisplayName("к.9, който не е частта без кредит от протокола — ГРЕШКА (контролата остава)")
  void к9_разминаване() {
    BgSaftDiaryCells.Coding coding =
        purchases(
            columns(
                "K09_BASE_NO_CREDIT",
                "50",
                "K10_BASE_FULL_CREDIT",
                "50",
                "K11_VAT_FULL_CREDIT",
                "10"),
            "01",
            "ART163A");
    assertTrue(coding.getErrors().get(0).startsWith("к.9 50"), coding.getErrors().toString());
  }

  // ─────────────── чл. 167а ───────────────

  @Test
  @DisplayName("Внос по чл. 167а (к.8а „03“), пълен кредит → 100347, данък 20")
  void код_100347_пълен_кредит() {
    String[] cell =
        only(
            purchases(
                columns("K10_BASE_FULL_CREDIT", "100", "K11_VAT_FULL_CREDIT", "20"),
                "03",
                "IMPORT_167A"));
    assertEquals("100347", cell[0]);
    assertEquals("20.00", cell[2]);
  }

  @Test
  @DisplayName("Внос по чл. 167а без кредит → 100347, данък 0 — без ГРЕШКА за ставката 20 %")
  void код_100347_без_кредит() {
    String[] cell = only(purchases(columns("K09_BASE_NO_CREDIT", "120"), "03", "IMPORT_167A"));
    assertEquals("100347", cell[0]);
    assertEquals("100.00", cell[1]);
    assertEquals("0.00", cell[2]);
  }

  @Test
  @DisplayName("Внос по чл. 167а, частичен кредит → 100347, данък = к.13")
  void код_100347_частичен_кредит() {
    String[] cell =
        only(
            purchases(
                columns("K12_BASE_PARTIAL_CREDIT", "100", "K13_VAT_PARTIAL_CREDIT", "20"),
                "03",
                "IMPORT_167A"));
    assertEquals("100347", cell[0]);
    assertEquals("20.00", cell[2]);
  }

  // ─────────────── останалото — без промяна ───────────────

  @Test
  @DisplayName("ВОП и обикновена покупка — по кредита, както преди (100331/100332/100330)")
  void другите_по_кредита() {
    assertEquals(
        "100331",
        only(
            purchases(
                columns("K10_BASE_FULL_CREDIT", "100", "K11_VAT_FULL_CREDIT", "20"), "", "VOP"))[
            0]);
    assertEquals(
        "100332",
        only(
            BgSaftDiaryCells.purchases(
                columns("K12_BASE_PARTIAL_CREDIT", "100", "K13_VAT_PARTIAL_CREDIT", "20"),
                "",
                "PURCHASE_PARTIAL",
                RATES,
                null,
                null))[0]);
    assertEquals(
        "100330",
        only(
            BgSaftDiaryCells.purchases(
                columns("K09_BASE_NO_CREDIT", "120"), "", "PURCHASE_82_NONE", RATES, null, null))[
            0]);
  }
}

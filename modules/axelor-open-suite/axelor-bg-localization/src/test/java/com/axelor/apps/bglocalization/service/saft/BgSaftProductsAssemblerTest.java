package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.BgSaftProductsAssembler.ConversionRow;
import com.axelor.apps.bglocalization.service.saft.BgSaftProductsAssembler.ProductRow;
import com.axelor.apps.bglocalization.service.saft.BgSaftProductsAssembler.Result;
import com.axelor.apps.bglocalization.service.saft.BgSaftProductsAssembler.UnitRow;
import com.axelor.apps.bglocalization.service.saft.dto.SaftProduct;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Products с числа: кодът по КН (интервали, глави, услуги, липса), видът стока/услуга, мерната
 * единица и коефициентът — 1 за каноничната, ≠ 1 за кратното, през трета единица с предупреждение.
 */
class BgSaftProductsAssemblerTest {

  private final List<ProductRow> products = new ArrayList<>();
  private final List<UnitRow> units = new ArrayList<>(BgSaftFixtures.units());
  private final List<ConversionRow> conversions = new ArrayList<>(BgSaftFixtures.conversions());

  private void goods(long id, String code, Long unitId, String customsCode) {
    products.add(new ProductRow(id, code, "Стока " + code, "storable", unitId, customsCode));
  }

  private void service(long id, String code, Long unitId, String customsCode) {
    products.add(new ProductRow(id, code, "Услуга " + code, "service", unitId, customsCode));
  }

  private Result assemble() {
    return BgSaftProductsAssembler.assemble(
        products, units, conversions, BgNraNomenclatures.nc8Codes());
  }

  private static SaftProduct product(Result result, String code) {
    return result.getProducts().stream()
        .filter(p -> p.getProductCode().equals(code))
        .findFirst()
        .orElseThrow(() -> new AssertionError("няма продукт " + code));
  }

  private static BgDeclarationFileFinding finding(Result result, String code) {
    return BgSaftTestFindings.merged(result.getFindings(), code)
        .orElseThrow(
            () -> new AssertionError("няма находка " + code + ": " + result.getFindings()));
  }

  @org.junit.jupiter.api.Test
  @org.junit.jupiter.api.DisplayName(
      "G2c: продаденото влиза; купеното — само по сметка от група 30; ДМА и разходвано — не;"
          + " наличността — винаги")
  void изборът_на_продуктите() {
    BgSaftProductsAssembler.ProductRow sold = row(1L, "S1");
    BgSaftProductsAssembler.ProductRow stock = row(2L, "G1");
    BgSaftProductsAssembler.ProductRow asset = row(3L, "FA1");
    BgSaftProductsAssembler.ProductRow expensed = row(4L, "EX1");
    BgSaftProductsAssembler.ProductRow stocked = row(5L, "G5");
    java.util.Map<Long, String> nra = java.util.Map.of(30L, "302", 20L, "204", 60L, "602");
    List<BgSaftProductsAssembler.ProductRow> selected =
        BgSaftProductsAssembler.select(
            List.of(
                new BgSaftProductsAssembler.Use(sold, true, 70L),
                new BgSaftProductsAssembler.Use(stock, false, 30L),
                new BgSaftProductsAssembler.Use(asset, false, 20L),
                new BgSaftProductsAssembler.Use(expensed, false, 60L),
                new BgSaftProductsAssembler.Use(asset, false, null)),
            List.of(stocked),
            nra);
    assertEquals(
        List.of(1L, 2L, 5L),
        selected.stream()
            .map(BgSaftProductsAssembler.ProductRow::getId)
            .collect(Collectors.toList()));
  }

  private static BgSaftProductsAssembler.ProductRow row(long id, String code) {
    return new BgSaftProductsAssembler.ProductRow(id, code, code, "storable", 2L, null);
  }

  private static List<String> codes(Result result) {
    return result.getFindings().stream()
        .map(BgDeclarationFileFinding::getCode)
        .collect(Collectors.toList());
  }

  // ─────────────── код по КН ───────────────

  @Test
  @DisplayName("КН8 с интервали, с точки или с NBSP → 8 цифри от NC8_TARIC, без находка")
  void кн8_се_нормализира() {
    goods(1, "G1", 2L, "0101 21 00");
    goods(2, "G2", 2L, "8517.13.00");
    goods(3, "G3", 2L, " 8517 13 00 ");

    Result result = assemble();
    assertEquals(List.of(), result.getFindings());
    assertEquals("01012100", product(result, "G1").getProductCommodityCode());
    assertEquals("85171300", product(result, "G2").getProductCommodityCode());
    assertEquals("85171300", product(result, "G3").getProductCommodityCode());
    assertEquals(BgSaftProductsAssembler.GOODS, product(result, "G1").getGoodsServicesId());
  }

  @Test
  @DisplayName("Услугата е 00000000 — и когато продуктът носи код по КН")
  void услугата_е_осем_нули() {
    service(1, "S1", 101L, null);
    service(2, "S2", 101L, "0101 21 00");

    Result result = assemble();
    assertEquals(List.of(), result.getFindings());
    assertEquals("00000000", product(result, "S1").getProductCommodityCode());
    assertEquals("00000000", product(result, "S2").getProductCommodityCode());
    assertEquals(BgSaftProductsAssembler.SERVICES, product(result, "S1").getGoodsServicesId());
  }

  @Test
  @DisplayName("Отрицателен свидетел: стока без КН8 излиза „0“ с предупреждение, не празна")
  void липсващият_кн8_е_нула() {
    goods(1, "G1", 2L, null);
    goods(2, "G2", 2L, "  ");

    Result result = assemble();
    assertEquals("0", product(result, "G1").getProductCommodityCode());
    assertEquals("0", product(result, "G2").getProductCommodityCode());
    BgDeclarationFileFinding missing = finding(result, "SAFT_P_NC8_MISSING");
    assertFalse(missing.isBlocking());
    // По една находка на продукт, с указател към него (G6a).
    assertTrue(missing.getMessage().contains(": G1 | "), missing.getMessage());
    assertTrue(missing.getMessage().endsWith(": G2"), missing.getMessage());
    assertEquals(List.of("SAFT_P_NC8_MISSING", "SAFT_P_NC8_MISSING"), codes(result));
    assertTrue(
        result.getFindings().stream()
            .allMatch(
                f -> BgSaftRef.PRODUCT.equals(f.getTargetModel()) && f.getTargetId() != null));
  }

  @Test
  @DisplayName("Глава, TARIC, непознат код и кодът на услугите върху стока → „0“ с причината")
  void невалидният_кн8_е_нула() {
    goods(1, "G1", 2L, "0101");
    goods(2, "G2", 2L, "0101210000");
    goods(3, "G3", 2L, "99999999");
    goods(4, "G4", 2L, "00000000");

    Result result = assemble();
    for (String code : List.of("G1", "G2", "G3", "G4")) {
      assertEquals("0", product(result, code).getProductCommodityCode(), code);
    }
    String message = finding(result, "SAFT_P_NC8_INVALID").getMessage();
    assertTrue(message.contains("G1 („0101“ — не е 8 цифри)"), message);
    assertTrue(message.contains("G2 („0101210000“ — не е 8 цифри)"), message);
    assertTrue(message.contains("G3 („99999999“ — няма го в NC8_TARIC)"), message);
    assertTrue(message.contains("G4 („00000000“ — това е кодът на услугите)"), message);
  }

  @Test
  @DisplayName("Непознат вид: GoodsServicesID не се подава, а кодът по КН се търси като за стока")
  void непознатият_вид() {
    products.add(new ProductRow(1L, "X1", "Нещо", null, 2L, "0101 21 00"));
    Result result = assemble();
    assertNull(product(result, "X1").getGoodsServicesId());
    assertEquals("01012100", product(result, "X1").getProductCommodityCode());
    assertEquals(List.of("SAFT_P_TYPE_UNKNOWN"), codes(result));
  }

  // ─────────────── мерна единица и коефициент ───────────────

  @Test
  @DisplayName("Каноничната единица: UOMBase = кодът, коефициент 1 — „Брой“, „Килограм“, „Час“")
  void каноничната_единица() {
    goods(1, "G1", 2L, "0101 21 00");
    goods(2, "G2", 306L, "0101 21 00");
    service(3, "S1", 101L, null);
    service(4, "S2", 103L, null);

    Result result = assemble();
    assertEquals(List.of(), result.getFindings());
    for (String[] expected :
        new String[][] {{"G1", "H87"}, {"G2", "KGM"}, {"S1", "HUR"}, {"S2", "E49"}}) {
      SaftProduct p = product(result, expected[0]);
      assertEquals(expected[1], p.getUomStandard(), expected[0]);
      assertEquals(expected[1], p.getUomBase(), "UOMBase = UOMStandard ⇒ 1 буквално");
      assertEquals("1", p.getUomToUomBaseConversionFactor().toPlainString(), expected[0]);
    }
  }

  @Test
  @DisplayName("Кратното: кутия от 12 броя → 1/12, седмица → 0.2, година → 1/12 — никога 1")
  void кратното_е_различно_от_едно() {
    goods(1, "G1", 5L, "0101 21 00");
    service(2, "S1", 104L, null);
    service(3, "S2", 106L, null);

    Result result = assemble();
    assertEquals(List.of(), result.getFindings());
    SaftProduct box = product(result, "G1");
    assertEquals("Кутия от 12 броя", box.getUomBase());
    assertEquals("H87", box.getUomStandard());
    assertEquals("0.0833333333", box.getUomToUomBaseConversionFactor().toPlainString());
    assertEquals("Работна седмица", product(result, "S1").getUomBase());
    assertEquals("0.2", product(result, "S1").getUomToUomBaseConversionFactor().toPlainString());
    assertEquals("WM", product(result, "S2").getUomStandard());
    assertEquals(
        "0.0833333333", product(result, "S2").getUomToUomBaseConversionFactor().toPlainString());
  }

  @Test
  @DisplayName("Половин работен ден стига работния ден само през „Час“ → 2, с предупреждение")
  void непрекият_път() {
    service(1, "S1", 102L, null);

    Result result = assemble();
    SaftProduct halfDay = product(result, "S1");
    assertEquals("Половин работен ден", halfDay.getUomBase());
    assertEquals("E49", halfDay.getUomStandard());
    assertEquals("2", halfDay.getUomToUomBaseConversionFactor().toPlainString());
    BgDeclarationFileFinding indirect = finding(result, "SAFT_P_UOM_INDIRECT");
    assertFalse(indirect.isBlocking());
    assertTrue(indirect.getMessage().contains("през „Час“"), indirect.getMessage());
  }

  @Test
  @DisplayName("Единица със същата големина като стандартната е стандартната: UOMBase = кодът, 1")
  void същата_големина() {
    units.add(new UnitRow(7L, "Бройка", "H87"));
    conversions.add(BgSaftFixtures.conversion(7, 2, "1"));
    goods(1, "G1", 7L, "0101 21 00");

    Result result = assemble();
    assertEquals(List.of(), result.getFindings());
    assertEquals("H87", product(result, "G1").getUomBase());
    assertEquals("1", product(result, "G1").getUomToUomBaseConversionFactor().toPlainString());
  }

  @Test
  @DisplayName("Кратно без конверсия към стандартната — грешка, коефициентът е неизвестен")
  void без_конверсия() {
    units.add(new UnitRow(8L, "Стек", "H87"));
    goods(1, "G1", 8L, "0101 21 00");

    Result result = assemble();
    assertEquals("Стек", product(result, "G1").getUomBase());
    assertTrue(finding(result, "SAFT_P_UOM_NO_CONVERSION").isBlocking());
  }

  @Test
  @DisplayName("Две единици с общ код и без конверсия помежду им — нито една не е стандартната")
  void без_стандартна_единица() {
    units.add(new UnitRow(20L, "Кашон", "XBX"));
    units.add(new UnitRow(21L, "Голям кашон", "XBX"));
    goods(1, "G1", 20L, "0101 21 00");

    Result result = assemble();
    assertEquals("Кашон", product(result, "G1").getUomBase());
    assertTrue(finding(result, "SAFT_P_UOM_NO_STANDARD_UNIT").isBlocking());
  }

  @Test
  @DisplayName("Единица без код по НАП → празен UOMStandard с предупреждение; без единица → грешка")
  void без_код_и_без_единица() {
    units.add(new UnitRow(900L, "Палет", null));
    goods(1, "G1", 900L, "0101 21 00");
    goods(2, "G2", null, "0101 21 00");

    Result result = assemble();
    assertEquals("", product(result, "G1").getUomStandard());
    assertEquals("Палет", product(result, "G1").getUomBase());
    assertFalse(finding(result, "SAFT_P_UOM_NO_CODE").isBlocking());
    assertEquals("", product(result, "G2").getUomStandard());
    assertTrue(finding(result, "SAFT_P_NO_UNIT").isBlocking());
  }

  // ─────────────── обхват и форма ───────────────

  @Test
  @DisplayName("Повторен продукт влиза веднъж; редът е по код")
  void по_веднъж_и_подредени() {
    goods(2, "B", 2L, "0101 21 00");
    goods(1, "A", 2L, "0101 21 00");
    goods(2, "B", 2L, "0101 21 00");

    Result result = assemble();
    assertEquals(
        List.of("A", "B"),
        result.getProducts().stream()
            .map(SaftProduct::getProductCode)
            .collect(Collectors.toList()));
  }

  @Test
  @DisplayName("Без код, без име или над дължината по схемата — грешка с продукта")
  void непълният_продукт() {
    products.add(new ProductRow(1L, null, "Без код", "storable", 2L, "0101 21 00"));
    units.add(new UnitRow(9L, "Кутия с изключително дълго име за мерна единица", "H87"));
    conversions.add(BgSaftFixtures.conversion(9, 2, "3"));
    goods(2, "G2", 9L, "0101 21 00");

    Result result = assemble();
    assertTrue(finding(result, "SAFT_P_INCOMPLETE").getMessage().endsWith(": #1"));
    String tooLong = finding(result, "SAFT_P_TOO_LONG").getMessage();
    assertTrue(tooLong.contains("G2 UOMBase 47 > 35"), tooLong);
  }

  @Test
  @DisplayName("Празен период — грешка, схемата иска поне един продукт")
  void празният_период() {
    Result result = assemble();
    assertTrue(result.getProducts().isEmpty());
    assertTrue(finding(result, "SAFT_P_EMPTY").isBlocking());
  }

  @Test
  @DisplayName("Посоката на коефициента е в един метод: 1 стандартна в базови, 10 знака, HALF_UP")
  void коефициентът() {
    assertEquals("12", BgSaftProductsAssembler.factor(new BigDecimal("12.000")).toPlainString());
    assertEquals(
        "0.3333333333",
        BgSaftProductsAssembler.factor(
                BigDecimal.ONE.divide(new BigDecimal("3"), MathContext.DECIMAL128))
            .toPlainString());
    assertEquals(
        "0.6666666667",
        BgSaftProductsAssembler.factor(new BigDecimal("0.66666666666")).toPlainString());
  }
}

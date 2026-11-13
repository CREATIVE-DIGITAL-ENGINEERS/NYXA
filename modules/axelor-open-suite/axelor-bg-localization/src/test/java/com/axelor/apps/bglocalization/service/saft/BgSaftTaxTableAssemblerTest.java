package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.BgSaftTaxTableAssembler.MappingRow;
import com.axelor.apps.bglocalization.service.saft.BgSaftTaxTableAssembler.Result;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxCodeDetail;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxTableEntry;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** TaxTable с числа: само ползваните кодове, 000/000000, процентът на ДДС и не-ДДС кодовете. */
class BgSaftTaxTableAssemblerTest {

  private final Map<String, MappingRow> mappings = new HashMap<>();

  private void mapped(String tax, String type, String code) {
    mappings.put(tax, new MappingRow(type, code));
  }

  private Result assemble(boolean untaxedLines, String... usedTaxes) {
    return BgSaftTaxTableAssembler.assemble(
        List.of(usedTaxes), mappings, untaxedLines, BgNraNomenclatures.taxCodes());
  }

  private static List<String> types(Result result) {
    return result.getEntries().stream()
        .map(SaftTaxTableEntry::getTaxType)
        .collect(Collectors.toList());
  }

  private static List<String> taxCodes(Result result) {
    return result.getEntries().stream()
        .flatMap(e -> e.getTaxCodeDetails().stream())
        .map(SaftTaxCodeDetail::getTaxCode)
        .collect(Collectors.toList());
  }

  private static SaftTaxCodeDetail detail(Result result, String code) {
    return result.getEntries().stream()
        .flatMap(e -> e.getTaxCodeDetails().stream())
        .filter(d -> d.getTaxCode().equals(code))
        .findFirst()
        .orElseThrow(() -> new AssertionError("няма код " + code));
  }

  private static BgDeclarationFileFinding finding(Result result, String code) {
    return BgSaftTestFindings.merged(result.getFindings(), code)
        .orElseThrow(
            () -> new AssertionError("няма находка " + code + ": " + result.getFindings()));
  }

  private static List<String> codes(Result result) {
    return result.getFindings().stream()
        .map(BgDeclarationFileFinding::getCode)
        .collect(Collectors.toList());
  }

  @Test
  @DisplayName("Само ползваните кодове, по веднъж: 20 % → 20, 9 % → 9, 0 % → 0, без находки")
  void само_ползваните() {
    mapped("BG_SRS_20", "100", "100211");
    mapped("BG_LRS_9", "100", "100213");
    mapped("BG_EXP", "100", "100214");
    mapped("BG_VOD", "100", "100215");

    Result result = assemble(false, "BG_SRS_20", "BG_LRS_9", "BG_EXP", "BG_SRS_20");
    assertEquals(List.of(), result.getFindings());
    assertEquals(List.of("100"), types(result));
    assertEquals(
        List.of("100211", "100213", "100214"), taxCodes(result), "без BG_VOD — не е ползван");
    SaftTaxTableEntry vat = result.getEntries().get(0);
    assertEquals("ДАНЪК ВЪРХУ ДОБАВЕНАТА СТОЙНОСТ", vat.getDescription());

    SaftTaxCodeDetail standard = detail(result, "100211");
    assertEquals("20", standard.getTaxPercentage().toPlainString());
    assertEquals("BG", standard.getCountry());
    assertEquals(
        "Данъчна основа на облагаемите доставки със ставка 20 %", standard.getDescription());
    assertEquals("9", detail(result, "100213").getTaxPercentage().toPlainString());
    assertEquals("0", detail(result, "100214").getTaxPercentage().toPlainString());
  }

  @Test
  @DisplayName("ДДС с „Н/П“ няма процент — нито базата по чл. 82, нито кредитната клетка")
  void ддс_с_нп_е_без_процент() {
    mapped("BG_RCM_ART82_S", "100", "100226");
    mapped("BG_SRP_20", "100", "100331");

    Result result = assemble(false, "BG_RCM_ART82_S", "BG_SRP_20");
    assertEquals(List.of(), result.getFindings());
    assertEquals(List.of("100226", "100331"), taxCodes(result));
    assertNull(detail(result, "100226").getTaxPercentage());
    assertNull(detail(result, "100331").getTaxPercentage());
  }

  @Test
  @DisplayName("Ред без данък дава 000/000000 — законната употреба по R5, без находка")
  void редът_без_данък_дава_000() {
    Result result = assemble(true);
    assertEquals(List.of(), result.getFindings());
    assertEquals(List.of("000"), types(result));
    assertEquals(List.of("000000"), taxCodes(result));
    SaftTaxCodeDetail detail = detail(result, "000000");
    assertNull(detail.getTaxPercentage());
    assertEquals("BG", detail.getCountry());
    assertFalse(result.getEntries().get(0).getDescription().isEmpty());
  }

  @Test
  @DisplayName("Данък без ред в мапинга → 000/000000 с предупреждение, по веднъж")
  void неизвестният_данък_става_000() {
    mapped("BG_SRS_20", "100", "100211");
    Result result = assemble(true, "BG_SRS_20", "CUSTOM_TAX", "OTHER_TAX");
    assertEquals(List.of("000", "100"), types(result));
    assertEquals(List.of("000000", "100211"), taxCodes(result));
    BgDeclarationFileFinding unmapped = finding(result, "SAFT_TT_TAX_UNMAPPED");
    assertFalse(unmapped.isBlocking());
    // По една находка на данък (G6a).
    assertTrue(unmapped.getMessage().contains(": CUSTOM_TAX | "), unmapped.getMessage());
    assertTrue(unmapped.getMessage().endsWith(": OTHER_TAX"), unmapped.getMessage());
  }

  @Test
  @DisplayName("Код, който го няма в TAX-IMP (100010 от образеца) → 000/000000 с предупреждение")
  void кодът_извън_tax_imp() {
    mapped("BG_SRS_20", "100", "100010");
    Result result = assemble(false, "BG_SRS_20");
    assertEquals(List.of("000000"), taxCodes(result));
    BgDeclarationFileFinding invalid = finding(result, "SAFT_TT_TAX_CODE_INVALID");
    assertFalse(invalid.isBlocking());
    assertTrue(invalid.getMessage().contains("BG_SRS_20 → „100010“"), invalid.getMessage());
  }

  @Test
  @DisplayName("„Да се потвърди“ НЕ става 000000 — не влиза и излиза с предупреждение")
  void да_се_потвърди_не_става_000() {
    mapped("BG_SRS_20", "100", "100211");
    mapped("BG_ZRS", "100", "");
    mapped("BG_OSS_EU", "100", null);

    Result result = assemble(false, "BG_SRS_20", "BG_ZRS", "BG_OSS_EU");
    assertEquals(List.of("100211"), taxCodes(result), "нито 000000, нито празен код");
    assertEquals(List.of("SAFT_TT_TAX_TO_CONFIRM", "SAFT_TT_TAX_TO_CONFIRM"), codes(result));
    assertTrue(
        finding(result, "SAFT_TT_TAX_TO_CONFIRM").getMessage().contains(": BG_OSS_EU | "),
        finding(result, "SAFT_TT_TAX_TO_CONFIRM").getMessage());
  }

  @Test
  @DisplayName("Не-ДДС: плоската сума по Закона за хазарта не влиза в TaxPercentage, нито дробта")
  void не_ддс_е_без_процент() {
    mapped("GAMBLING", "500", "500020");
    mapped("WITHHOLDING", "700", "700010");

    Result result = assemble(false, "GAMBLING", "WITHHOLDING");
    assertEquals(List.of("500", "700"), types(result));
    assertNull(detail(result, "500020").getTaxPercentage(), "500 е сума, не 50 000 %");
    assertNull(detail(result, "700010").getTaxPercentage());
    assertEquals(
        "ДАНЪК ВЪРХУ ХАЗАРТНАТА ДЕЙНОСТ ПО ЗКПО", result.getEntries().get(0).getDescription());
    BgDeclarationFileFinding nonVat = finding(result, "SAFT_TT_NON_VAT_TO_CONFIRM");
    assertFalse(nonVat.isBlocking());
    assertTrue(nonVat.getMessage().contains("500020 (ставка „500“)"), nonVat.getMessage());
    assertTrue(nonVat.getMessage().contains("700010 (ставка „0.1“)"), nonVat.getMessage());
  }

  @Test
  @DisplayName("Видът идва от номенклатурата, а разминаването с мапинга е предупреждение")
  void видът_е_от_номенклатурата() {
    mapped("BG_SRS_20", "200", "100211");
    Result result = assemble(false, "BG_SRS_20");
    assertEquals(List.of("100"), types(result));
    assertEquals(List.of("SAFT_TT_TAX_TYPE_MISMATCH"), codes(result));
  }

  @Test
  @DisplayName("Редът по вид и по код: 000 преди 100 преди 500")
  void подредбата() {
    mapped("GAMBLING", "500", "500020");
    mapped("BG_LRS_9", "100", "100213");
    mapped("BG_SRS_20", "100", "100211");
    Result result = assemble(true, "GAMBLING", "BG_LRS_9", "BG_SRS_20");
    assertEquals(List.of("000", "100", "500"), types(result));
    assertEquals(List.of("000000", "100211", "100213", "500020"), taxCodes(result));
  }

  @Test
  @DisplayName("Процентът: само ДДС и само дроб от 0 до 1 — сума, „Н/П“ и празно дават null")
  void процентът() {
    assertEquals("20", percentage("100", "0.2"));
    assertEquals("9", percentage("100", "0.09"));
    assertEquals("0", percentage("100", "0"));
    assertEquals("12.5", percentage("100", "0.125"));
    assertNull(percentage("100", "500"), "сума, не 50 000 %");
    assertNull(percentage("100", "-0.2"));
    assertNull(percentage("100", "Н/П"));
    assertNull(percentage("100", ""));
    assertNull(percentage("700", "0.1"), "не-ДДС — да се потвърди");
  }

  private static String percentage(String type, String rate) {
    BigDecimal value =
        BgSaftTaxTableAssembler.percentage(
            new BgNraNomenclatures.TaxCode(type, type + "999", "Данък", "Описание", rate));
    return value == null ? null : value.toPlainString();
  }

  @Test
  @DisplayName("Празен период: няма ред, тоест няма и таблица — грешка, схемата иска поне един")
  void празният_период() {
    Result result = assemble(false);
    assertTrue(result.getEntries().isEmpty());
    assertTrue(finding(result, "SAFT_TT_EMPTY").isBlocking());
  }
}

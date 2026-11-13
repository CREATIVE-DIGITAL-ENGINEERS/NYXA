package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.vat.adapter.BgVatDeclarationAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesTaxCodeMapping;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatAmountDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatSalesRowDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatValidationErrorDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure (no DB / no Guice) unit tests for WS-3 sales-diary generation: tax-code → NAP column
 * mapping, row assembly (totals, mixed rates, reverse charge, credit-note sign), the physical row
 * layout, sales/purchase classification, and the production blocking gates.
 */
class BgVatWs3SalesTest {

  private final BgVatSalesAdapter adapter = new BgVatSalesAdapter();

  private BgVatExportRequestDto request() {
    BgVatExportRequestDto request = new BgVatExportRequestDto();
    request.setCompanyVatNumber("BG123456789");
    request.setPeriodFrom(LocalDate.of(2026, 6, 1));
    request.setPeriodTo(LocalDate.of(2026, 6, 30));
    request.setEncoding("cp1251");
    return request;
  }

  private BgVatAmountDto bucket(String taxCode, String base, String vat) {
    BgVatAmountDto a = new BgVatAmountDto();
    a.setTaxCode(taxCode);
    a.setBase(base == null ? null : new BigDecimal(base));
    a.setVatAmount(vat == null ? null : new BigDecimal(vat));
    return a;
  }

  private BgVatDocumentDto salesDoc(String number, String napType, BgVatAmountDto... buckets) {
    BgVatDocumentDto doc = new BgVatDocumentDto();
    doc.setDocumentNumber(number);
    doc.setDocumentDate(LocalDate.of(2026, 6, 15));
    doc.setCounterpartyVatNumber("BG999888777");
    doc.setCounterpartyName("Acme");
    doc.setNapDocumentType(napType);
    doc.setOperationType(3); // customer sale
    doc.getAmounts().addAll(Arrays.asList(buckets));
    return doc;
  }

  // --- Mapping ---------------------------------------------------------------

  @Test
  void mapping_coversCoreRegimes() {
    assertEquals(
        BgVatSalesColumn.K11_BASE_20,
        BgVatSalesTaxCodeMapping.forTaxCode("BG_SRS_20").get().getBaseColumn());
    assertEquals(
        BgVatSalesColumn.K12_VAT_20,
        BgVatSalesTaxCodeMapping.forTaxCode("BG_SRS_20").get().getVatColumn());

    // Reduced 9% → к17 / к18.
    assertEquals(
        BgVatSalesColumn.K17_BASE_9,
        BgVatSalesTaxCodeMapping.forTaxCode("BG_LRS_9").get().getBaseColumn());
    assertEquals(
        BgVatSalesColumn.K18_VAT_9,
        BgVatSalesTaxCodeMapping.forTaxCode("BG_LRS_9").get().getVatColumn());

    // ВОП self-assessment: base к13, VAT к15.
    assertEquals(
        BgVatSalesColumn.K13_BASE_ICA,
        BgVatSalesTaxCodeMapping.forTaxCode("BG_VOP_SELF").get().getBaseColumn());
    assertEquals(
        BgVatSalesColumn.K15_VAT_ICA_RC,
        BgVatSalesTaxCodeMapping.forTaxCode("BG_VOP_SELF").get().getVatColumn());

    // Reverse charge — BOTH чл.82 and чл.163а: base к14, VAT к15 (чл.163а differs only by the к.8а
    // identification code, not by a value column).
    assertEquals(
        BgVatSalesColumn.K14_BASE_RC,
        BgVatSalesTaxCodeMapping.forTaxCode("BG_RCM_ART82_S").get().getBaseColumn());
    assertEquals(
        BgVatSalesColumn.K15_VAT_ICA_RC,
        BgVatSalesTaxCodeMapping.forTaxCode("BG_RCM_ART82_S").get().getVatColumn());
    assertEquals(
        BgVatSalesColumn.K14_BASE_RC,
        BgVatSalesTaxCodeMapping.forTaxCode("BG_RCM_BUILD_S").get().getBaseColumn());
    assertEquals(
        BgVatSalesColumn.K15_VAT_ICA_RC,
        BgVatSalesTaxCodeMapping.forTaxCode("BG_RCM_BUILD_S").get().getVatColumn());

    // Zero-VAT regimes: base column, null VAT column.
    assertEquals(
        BgVatSalesColumn.K20_BASE_ICS,
        BgVatSalesTaxCodeMapping.forTaxCode("BG_VOD").get().getBaseColumn());
    assertEquals(null, BgVatSalesTaxCodeMapping.forTaxCode("BG_VOD").get().getVatColumn());
    assertEquals(
        BgVatSalesColumn.K25_BASE_TRIANGULAR,
        BgVatSalesTaxCodeMapping.forTaxCode("BG_TRIANG_S").get().getBaseColumn());
    assertEquals(
        BgVatSalesColumn.K19_BASE_ZERO_CH3,
        BgVatSalesTaxCodeMapping.forTaxCode("BG_EXP").get().getBaseColumn());
    assertEquals(
        BgVatSalesColumn.K24_BASE_EXEMPT,
        BgVatSalesTaxCodeMapping.forTaxCode("BG_EX_S").get().getBaseColumn());
    assertEquals(
        BgVatSalesColumn.K22_BASE_EU_SERVICES,
        BgVatSalesTaxCodeMapping.forTaxCode("BG_EU_S_RCM_S").get().getBaseColumn());
    assertEquals(
        BgVatSalesColumn.K23_BASE_ART69_2,
        BgVatSalesTaxCodeMapping.forTaxCode("BG_NON_EU_SERV_S").get().getBaseColumn());
  }

  @Test
  void mapping_specialSchemesAreDeliberatelyUnmapped() {
    // Left unmapped on purpose (need accountant/CTO confirmation) — must not silently get a column.
    assertFalse(BgVatSalesTaxCodeMapping.isMapped("BG_MARGIN"));
    assertFalse(BgVatSalesTaxCodeMapping.isMapped("BG_TOUR"));
    assertFalse(BgVatSalesTaxCodeMapping.isMapped("BG_SECONDHAND"));
    // Инвестиционното злато: освободеното (чл. 160) и облагаемото с обратно начисляване (чл. 161)
    // не се различават в данните — блокирано до отделните данъчни кодове.
    assertFalse(BgVatSalesTaxCodeMapping.isMapped("BG_GOLD_ART163G"));
    assertFalse(BgVatSalesTaxCodeMapping.isMapped(null));
  }

  @Test
  void mapping_zeroRateIsK19_andOssMossAreK23_withoutVat() {
    // к.19 „ДО на доставките със ставка 0 % по глава трета от ЗДДС“ (ППЗДДС Прил. № 10).
    assertEquals(
        BgVatSalesColumn.K19_BASE_ZERO_CH3,
        BgVatSalesTaxCodeMapping.forTaxCode("BG_ZRS").get().getBaseColumn());
    assertEquals(null, BgVatSalesTaxCodeMapping.forTaxCode("BG_ZRS").get().getVatColumn());
    // к.23 „ДО на доставки по чл. 69, ал. 2 ЗДДС, вкл. … дистанционни продажби …“.
    for (String oss : new String[] {"BG_OSS_EU", "BG_MOSS"}) {
      assertEquals(
          BgVatSalesColumn.K23_BASE_ART69_2,
          BgVatSalesTaxCodeMapping.forTaxCode(oss).get().getBaseColumn(),
          oss);
      assertEquals(null, BgVatSalesTaxCodeMapping.forTaxCode(oss).get().getVatColumn(), oss);
    }
  }

  @Test
  void zeroRateAndOss_passTheGate_andLandInTheirDeclarationCells() {
    BgVatDocumentDto zero = salesDoc("Z-1", "01", bucket("BG_ZRS", "300", "0"));
    BgVatDocumentDto oss = salesDoc("O-1", "01", bucket("BG_OSS_EU", "500", "0"));
    BgVatDocumentDto moss = salesDoc("O-2", "01", bucket("BG_MOSS", "200", "0"));
    List<BgVatDocumentDto> docs = Arrays.asList(zero, oss, moss);

    assertTrue(
        new BgVatValidator()
            .validateSalesForProduction(docs, request()).stream()
                .noneMatch(f -> BgVatValidationErrorDto.SEVERITY_ERROR.equals(f.getSeverity())),
        "ZRS/OSS/MOSS вече имат колона — нищо не блокира PRODAGBI");

    String record =
        new BgVatDeclarationAdapter(adapter)
            .buildLines(
                docs,
                Collections.emptyList(),
                new BgVatDeclarationAdapter.Header("BG123456789", "Х", "202606", "Х"),
                new BgVatDeclarationAdapter.ManualCells(null, null, null, null, null, null))
            .get(0);
    // Заглавие 151, после кл.01, 20, 11, 21, 12, 22, 23, 13, 24, 14, 15, 16, 17, 18, 19 по 15.
    // 01-01 = [02-10] = сбор на 02-11…02-16, 02-26 — с 02-14 (к.19), без 02-18 (к.23).
    assertEquals("300.00", record.substring(151, 166).trim());
    // 01-14 = [02-14]
    assertEquals("300.00", record.substring(286, 301).trim());
    // 01-18 = [02-18] + [02-25]
    assertEquals("700.00", record.substring(346, 361).trim());
    // 01-20 = [02-20] — данък няма.
    assertEquals("0.00", record.substring(166, 181).trim());
  }

  // --- Row assembly ----------------------------------------------------------

  @Test
  void row_standard20Percent_totalsAndColumns() {
    BgVatDocumentDto doc = salesDoc("INV-1", "01", bucket("BG_SRS_20", "1000", "200"));
    BgVatSalesRowDto row = adapter.buildRows(Collections.singletonList(doc), request()).get(0);

    assertEquals(0, new BigDecimal("1000").compareTo(row.getAmount(BgVatSalesColumn.K11_BASE_20)));
    assertEquals(0, new BigDecimal("200").compareTo(row.getAmount(BgVatSalesColumn.K12_VAT_20)));
    assertEquals(
        0, new BigDecimal("1000").compareTo(row.getAmount(BgVatSalesColumn.K09_TOTAL_BASE)));
    assertEquals(0, new BigDecimal("200").compareTo(row.getAmount(BgVatSalesColumn.K10_TOTAL_VAT)));
    assertEquals("202606", row.getPeriodCode());
    assertEquals(1, row.getSequence());
  }

  @Test
  void row_mixedRates_sumIntoTotals() {
    BgVatDocumentDto doc =
        salesDoc(
            "INV-2", "01", bucket("BG_SRS_20", "1000", "200"), bucket("BG_LRS_9", "500", "45"));
    BgVatSalesRowDto row = adapter.buildRows(Collections.singletonList(doc), request()).get(0);

    assertEquals(0, new BigDecimal("1000").compareTo(row.getAmount(BgVatSalesColumn.K11_BASE_20)));
    assertEquals(0, new BigDecimal("500").compareTo(row.getAmount(BgVatSalesColumn.K17_BASE_9)));
    assertEquals(0, new BigDecimal("45").compareTo(row.getAmount(BgVatSalesColumn.K18_VAT_9)));
    assertEquals(
        0, new BigDecimal("1500").compareTo(row.getAmount(BgVatSalesColumn.K09_TOTAL_BASE)));
    assertEquals(0, new BigDecimal("245").compareTo(row.getAmount(BgVatSalesColumn.K10_TOTAL_VAT)));
  }

  @Test
  void row_reverseChargeArt82_goesToK14AndK15() {
    BgVatDocumentDto doc = salesDoc("PROT-1", "09", bucket("BG_RCM_ART82_S", "800", "160"));
    BgVatSalesRowDto row = adapter.buildRows(Collections.singletonList(doc), request()).get(0);

    assertEquals(0, new BigDecimal("800").compareTo(row.getAmount(BgVatSalesColumn.K14_BASE_RC)));
    assertEquals(
        0, new BigDecimal("160").compareTo(row.getAmount(BgVatSalesColumn.K15_VAT_ICA_RC)));
    assertEquals(0, new BigDecimal("160").compareTo(row.getAmount(BgVatSalesColumn.K10_TOTAL_VAT)));
  }

  @Test
  void row_creditNote_negatesAmounts() {
    BgVatDocumentDto doc = salesDoc("CN-1", "03", bucket("BG_SRS_20", "1000", "200"));
    BgVatSalesRowDto row = adapter.buildRows(Collections.singletonList(doc), request()).get(0);

    assertEquals(0, new BigDecimal("-1000").compareTo(row.getAmount(BgVatSalesColumn.K11_BASE_20)));
    assertEquals(0, new BigDecimal("-200").compareTo(row.getAmount(BgVatSalesColumn.K12_VAT_20)));
  }

  // --- Physical layout -------------------------------------------------------

  @Test
  void formatRow_isFixedWidth424() {
    BgVatDocumentDto doc = salesDoc("INV-1", "01", bucket("BG_SRS_20", "1000", "200"));
    List<String> lines = adapter.buildLines(Collections.singletonList(doc), request());

    assertEquals(1, lines.size());
    String line = lines.get(0);
    assertEquals(424, line.length());
    assertEquals("BG123456789    ", line.substring(0, 15)); // [0:15]  ЕИК, left, space-padded
    assertEquals("202606", line.substring(15, 21)); // [15:21] период
    assertEquals("01", line.substring(40, 42)); // [40:42] кл.03 вид (2, водеща нула)
    assertEquals(
        "INV-1" + " ".repeat(15), line.substring(42, 62)); // [42:62] кл.04 № (20, дословно)
    assertEquals("15/06/2026", line.substring(62, 72)); // [62:72] кл.05 дата
    // Value area [167:422]: 17 × 15, right-aligned. к.09 & к.11 = 1000.00; к.10 & к.12 = 200.00.
    assertEquals("        1000.00", line.substring(167, 182)); // к.09
    assertEquals("         200.00", line.substring(182, 197)); // к.10
    assertEquals("        1000.00", line.substring(197, 212)); // к.11
    assertEquals("         200.00", line.substring(212, 227)); // к.12
  }

  // --- Classification --------------------------------------------------------

  @Test
  void isSalesDocument_bySalesTaxCodeOrOperationType() {
    // Purchase-only document (op=1, BG_SRP_20) is not a sales document.
    BgVatDocumentDto purchase = new BgVatDocumentDto();
    purchase.setOperationType(1);
    purchase.getAmounts().add(bucket("BG_SRP_20", "1000", "200"));
    assertFalse(adapter.isSalesDocument(purchase));

    assertTrue(
        adapter.isSalesDocument(salesDoc("INV-1", "01", bucket("BG_SRS_20", "1000", "200"))));
  }

  // --- Production gates -------------------------------------------------------

  @Test
  void gate_unmappedTaxCode_isBlockingError() {
    BgVatDocumentDto doc = salesDoc("INV-9", "01", bucket("BG_MARGIN", "1000", "0"));
    List<BgVatValidationErrorDto> findings =
        new BgVatValidator().validateSalesForProduction(Collections.singletonList(doc), request());
    assertTrue(
        findings.stream()
            .anyMatch(
                f ->
                    BgVatValidator.UNMAPPED_TAX_CODE_CODE.equals(f.getCode())
                        && BgVatValidationErrorDto.SEVERITY_ERROR.equals(f.getSeverity())));
  }

  @Test
  void gate_missingCompanyVatNumber_isBlockingError() {
    BgVatExportRequestDto req = request();
    req.setCompanyVatNumber(null);
    BgVatDocumentDto doc = salesDoc("INV-1", "01", bucket("BG_SRS_20", "1000", "200"));
    List<BgVatValidationErrorDto> findings =
        new BgVatValidator().validateSalesForProduction(Collections.singletonList(doc), req);
    assertTrue(
        findings.stream()
            .anyMatch(f -> BgVatValidator.COMPANY_VAT_NUMBER_MISSING_CODE.equals(f.getCode())));
  }

  @Test
  void gate_validSalesDoc_hasNoBlockingErrors() {
    BgVatDocumentDto doc = salesDoc("INV-1", "01", bucket("BG_SRS_20", "1000", "200"));
    List<BgVatValidationErrorDto> findings =
        new BgVatValidator().validateSalesForProduction(Collections.singletonList(doc), request());
    assertTrue(
        findings.stream()
            .noneMatch(f -> BgVatValidationErrorDto.SEVERITY_ERROR.equals(f.getSeverity())));
  }
}

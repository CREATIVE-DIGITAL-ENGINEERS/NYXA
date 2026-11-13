package com.axelor.apps.bglocalization.service.vat.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.bglocalization.service.vat.dto.BgVatAmountDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatSalesRowDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Locks the three Приложение №12 layout fixes that the single golden fixture row does not exercise:
 * the literal 20-char document number (поле 02-05), the чл.163а/167а supply-type code at the end of
 * the record (поле 02-27), and the к.09 taxable-base total composition (поле 02-10). In the {@code
 * ...vat.adapter} package on purpose so it can call the package-visible {@code formatRow} directly.
 */
class BgVatSalesLayoutTest {

  private final BgVatSalesAdapter adapter = new BgVatSalesAdapter();

  private BgVatSalesRowDto baseRow() {
    BgVatSalesRowDto row = new BgVatSalesRowDto();
    row.setCompanyVatNumber("BG123456789");
    row.setPeriodCode("202606");
    row.setBranch("0");
    row.setSequence(1);
    row.setNapDocumentType("01");
    row.setDocumentNumber("");
    row.setCounterpartyVatNumber("BG999888777");
    row.setCounterpartyName("Acme");
    return row;
  }

  // а) Document number is written verbatim, left-aligned in 20 chars — NOT zero-padded.
  @Test
  void docNumber_isLiteralLeftAlignedIn20() {
    BgVatSalesRowDto row = baseRow();
    row.setDocumentNumber("250");
    String line = adapter.formatRow(row);
    assertEquals("250" + " ".repeat(17), line.substring(42, 62));
  }

  // б) чл.163а supply-type code goes into the final 2 chars of the record (поле 02-27).
  @Test
  void art163aSupplyType_goesToTrailingTwoChars() {
    BgVatSalesRowDto row = baseRow();
    row.setSupplyTypeArt163a("01");
    String line = adapter.formatRow(row);
    assertEquals("01", line.substring(422, 424));
    assertEquals(424, line.length());
  }

  // в) к.09 taxable-base total EXCLUDES к.24 (освободени) and к.25 (тристранни).
  @Test
  void k09_excludesExemptAndTriangular() {
    BgVatExportRequestDto request = new BgVatExportRequestDto();
    request.setCompanyVatNumber("BG123456789");
    request.setPeriodFrom(LocalDate.of(2026, 6, 1));
    request.setPeriodTo(LocalDate.of(2026, 6, 30));

    BgVatDocumentDto doc = new BgVatDocumentDto();
    doc.setDocumentNumber("INV-Z");
    doc.setDocumentDate(LocalDate.of(2026, 6, 15));
    doc.setNapDocumentType("01");
    doc.setOperationType(3);
    doc.getAmounts()
        .addAll(
            Arrays.asList(
                bucket("BG_EX_S", "100"), // → к.24 освободени
                bucket("BG_TRIANG_S", "200"))); // → к.25 тристранни

    List<String> lines = adapter.buildLines(Collections.singletonList(doc), request);
    String line = lines.get(0);

    // к.09 = value column 0 → [167:182]; must be 0.00 (к.24/к.25 are not in the base total).
    assertEquals("0.00", line.substring(167, 182).trim());
    // Sanity: the amounts are present, just in к.24 (col idx 15) and к.25 (col idx 16).
    assertEquals("100.00", line.substring(167 + 15 * 15, 167 + 16 * 15).trim()); // к.24
    assertEquals("200.00", line.substring(167 + 16 * 15, 167 + 17 * 15).trim()); // к.25
  }

  private static BgVatAmountDto bucket(String taxCode, String base) {
    BgVatAmountDto a = new BgVatAmountDto();
    a.setTaxCode(taxCode);
    a.setBase(new BigDecimal(base));
    return a;
  }
}

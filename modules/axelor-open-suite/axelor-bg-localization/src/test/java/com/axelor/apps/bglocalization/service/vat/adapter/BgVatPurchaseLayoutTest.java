package com.axelor.apps.bglocalization.service.vat.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.bglocalization.service.vat.dto.BgVatPurchaseRowDto;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Locks the POKUPKI layout details the golden fixture rows do not exercise: literal 20-char
 * document number (поле 03-06), чл.163а/167а supply-type code at the end (поле 03-45), and a
 * NEGATIVE к.14 annual correction (поле 03-43, чл.73 ал.8).
 */
class BgVatPurchaseLayoutTest {

  private final BgVatPurchaseAdapter adapter = new BgVatPurchaseAdapter();

  private BgVatPurchaseRowDto baseRow() {
    BgVatPurchaseRowDto row = new BgVatPurchaseRowDto();
    row.setCompanyVatNumber("BG100000033");
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
    BgVatPurchaseRowDto row = baseRow();
    row.setDocumentNumber("250");
    String line = adapter.formatRow(row);
    assertEquals("250" + " ".repeat(17), line.substring(42, 62));
    assertEquals(274, line.length());
  }

  // б) чл.163а supply-type code goes into the final 2 chars of the record (поле 03-45).
  @Test
  void art163aSupplyType_goesToTrailingTwoChars() {
    BgVatPurchaseRowDto row = baseRow();
    row.setSupplyTypeArt163a("01");
    String line = adapter.formatRow(row);
    assertEquals("01", line.substring(272, 274));
    assertEquals(274, line.length());
  }

  // в) к.14 (годишна корекция по чл.73 ал.8) may be negative → right-aligned "-50.00".
  @Test
  void k14_annualCorrection_canBeNegative() {
    BgVatPurchaseRowDto row = baseRow();
    row.addAmount(BgVatPurchaseColumn.K14_ANNUAL_ADJUSTMENT, new BigDecimal("-50.00"));
    String line = adapter.formatRow(row);
    // к.14 = value column 5 → [242:257].
    assertEquals("-50.00", line.substring(242, 257).trim());
  }
}

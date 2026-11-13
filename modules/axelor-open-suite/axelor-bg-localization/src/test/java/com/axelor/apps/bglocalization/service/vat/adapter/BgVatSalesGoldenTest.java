package com.axelor.apps.bglocalization.service.vat.adapter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.axelor.apps.bglocalization.service.vat.BgVatFileWriter;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatSalesRowDto;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * Golden-file lock for the PRODAGBI.TXT sales-diary record. Reconstructs the single accepted record
 * from {@code src/test/resources/nap/PRODAGBI.txt} (period 202606, byte-verified CP1251 + CRLF, no
 * BOM) and asserts, byte-for-byte, that {@link BgVatSalesAdapter#formatRow} plus {@link
 * BgVatFileWriter} reproduce it exactly. This is the acceptance criterion for the physical layout —
 * if it fails, the fixed-width layout regressed.
 *
 * <p>This test lives in the {@code ...vat.adapter} package on purpose, so it can exercise the
 * package-visible {@code formatRow} directly.
 */
class BgVatSalesGoldenTest {

  private static final String RESOURCE = "/nap/PRODAGBI.txt";
  private static final Charset CP1251 = Charset.forName("windows-1251");

  private final BgVatSalesAdapter adapter = new BgVatSalesAdapter();

  /** The real record's field values (decoded from the accepted fixture). */
  private BgVatSalesRowDto goldenRow() {
    BgVatSalesRowDto row = new BgVatSalesRowDto();
    row.setCompanyVatNumber("BG100000033");
    row.setPeriodCode("202606");
    row.setBranch("0");
    row.setSequence(1);
    row.setNapDocumentType("01");
    row.setDocumentNumber("3000000004");
    row.setDocumentDate(LocalDate.of(2026, 6, 8));
    row.setCounterpartyVatNumber("BG100000058");
    row.setCounterpartyName("ДЕМОС - ТРАНС ЕООД");
    row.setSubject("ремарке товарен автомобил");
    // кл.09–кл.25 = [5500, 1100, 5500, 1100, 0×13]; zero buckets are left unset (→ "0.00").
    row.addAmount(BgVatSalesColumn.K09_TOTAL_BASE, new BigDecimal("5500.00"));
    row.addAmount(BgVatSalesColumn.K10_TOTAL_VAT, new BigDecimal("1100.00"));
    row.addAmount(BgVatSalesColumn.K11_BASE_20, new BigDecimal("5500.00"));
    row.addAmount(BgVatSalesColumn.K12_VAT_20, new BigDecimal("1100.00"));
    return row;
  }

  private byte[] fixtureBytes() throws IOException {
    try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
      assertNotNull(in, "missing golden fixture " + RESOURCE);
      return in.readAllBytes();
    }
  }

  @Test
  void formatRow_matchesAcceptedRecord_charForChar() throws IOException {
    byte[] fileBytes = fixtureBytes();
    String expectedLine = new String(fileBytes, CP1251).split("\r\n", -1)[0];

    assertEquals(424, expectedLine.length(), "fixture record length");

    String actual = adapter.formatRow(goldenRow());
    assertEquals(424, actual.length(), "generated record length");
    assertEquals(expectedLine, actual);
  }

  @Test
  void writerOutput_isByteForByteEqualToFile() throws IOException {
    byte[] fileBytes = fixtureBytes();

    String line = adapter.formatRow(goldenRow());
    byte[] written = new BgVatFileWriter().toBytes(Collections.singletonList(line), "cp1251");

    // Same bytes incl. the trailing CRLF and no BOM.
    assertArrayEquals(fileBytes, written);
  }
}

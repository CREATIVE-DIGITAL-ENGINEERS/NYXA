package com.axelor.apps.bglocalization.service.vat.adapter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.axelor.apps.bglocalization.service.vat.BgVatFileWriter;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatPurchaseRowDto;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden-file lock for the POKUPKI.TXT purchase-diary records. Reconstructs the two accepted
 * records from {@code src/test/resources/nap/POKUPKI_1.txt} (period 202606, byte-verified CP1251 +
 * CRLF, no BOM) and asserts, byte-for-byte, that {@link BgVatPurchaseAdapter#formatRow}/{@code
 * buildLines} plus {@link BgVatFileWriter} reproduce them exactly.
 */
class BgVatPurchaseGoldenTest {

  private static final String RESOURCE = "/nap/POKUPKI_1.txt";
  private static final Charset CP1251 = Charset.forName("windows-1251");

  private final BgVatPurchaseAdapter adapter = new BgVatPurchaseAdapter();

  private BgVatPurchaseRowDto commonRow(int sequence) {
    BgVatPurchaseRowDto row = new BgVatPurchaseRowDto();
    row.setCompanyVatNumber("BG100000033");
    row.setPeriodCode("202606");
    row.setBranch("0");
    row.setSequence(sequence);
    row.setNapDocumentType("01");
    row.setSupplyTypeArt163a(""); // нормална доставка
    return row;
  }

  private List<BgVatPurchaseRowDto> goldenRows() {
    BgVatPurchaseRowDto r1 = commonRow(1);
    r1.setDocumentNumber("0117334750");
    r1.setDocumentDate(LocalDate.of(2026, 6, 15));
    r1.setCounterpartyVatNumber("BG831642181");
    r1.setCounterpartyName("Виваком България ЕАД");
    r1.setSubject("телефония");
    r1.addAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT, new BigDecimal("91.41"));
    r1.addAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT, new BigDecimal("18.28"));

    BgVatPurchaseRowDto r2 = commonRow(2);
    r2.setDocumentNumber("0000000889");
    r2.setDocumentDate(LocalDate.of(2026, 6, 30));
    r2.setCounterpartyVatNumber("BG100000040");
    r2.setCounterpartyName("А Б В АКАУНТИНГ ЕООД");
    r2.setSubject("счетоводно обслужване");
    r2.addAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT, new BigDecimal("150.00"));
    r2.addAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT, new BigDecimal("30.00"));

    return Arrays.asList(r1, r2);
  }

  private byte[] fixtureBytes() throws IOException {
    try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
      assertNotNull(in, "missing golden fixture " + RESOURCE);
      return in.readAllBytes();
    }
  }

  @Test
  void buildLines_matchAcceptedRecords_charForChar() throws IOException {
    byte[] fileBytes = fixtureBytes();
    String[] expected = new String(fileBytes, CP1251).split("\r\n", -1);

    List<String> actual = adapter.buildLines(goldenRows());
    assertEquals(2, actual.size());
    for (int i = 0; i < 2; i++) {
      assertEquals(274, expected[i].length(), "fixture record " + (i + 1) + " length");
      assertEquals(274, actual.get(i).length(), "generated record " + (i + 1) + " length");
      assertEquals(expected[i], actual.get(i), "record " + (i + 1));
    }
  }

  @Test
  void writerOutput_isByteForByteEqualToFile() throws IOException {
    byte[] fileBytes = fixtureBytes();

    List<String> lines = adapter.buildLines(goldenRows());
    byte[] written = new BgVatFileWriter().toBytes(lines, "cp1251");

    // Same bytes incl. both CRLFs and no BOM.
    assertArrayEquals(fileBytes, written);
  }
}

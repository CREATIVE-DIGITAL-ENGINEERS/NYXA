package com.axelor.apps.bglocalization.service.vat.adapter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.axelor.apps.bglocalization.service.vat.BgVatFileWriter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatDeclarationAdapter.Header;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatDeclarationAdapter.ManualCells;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatAmountDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden-file lock for the DEKLAR.TXT declaration record. Reconstructs the accepted record from
 * {@code src/test/resources/nap/DEKLAR_1.txt} (period 202606, byte-verified CP1251 + CRLF, no BOM)
 * from the SAME sales/purchase inputs that produce PRODAGBI.txt / POKUPKI_1.txt, plus the manual
 * cells, and asserts byte-for-byte equality (record length 590).
 */
class BgVatDeclarationGoldenTest {

  private static final String RESOURCE = "/nap/DEKLAR_1.txt";
  private static final Charset CP1251 = Charset.forName("windows-1251");

  private final BgVatDeclarationAdapter adapter =
      new BgVatDeclarationAdapter(new BgVatSalesAdapter());

  private BgVatAmountDto bucket(String taxCode, String base, String vat) {
    BgVatAmountDto a = new BgVatAmountDto();
    a.setTaxCode(taxCode);
    a.setBase(new BigDecimal(base));
    a.setVatAmount(new BigDecimal(vat));
    return a;
  }

  /** One standard 20% sale → PRODAGBI: к.09/к.11=5500, к.10/к.12=1100. */
  private List<BgVatDocumentDto> salesDocs() {
    BgVatDocumentDto d = new BgVatDocumentDto();
    d.setNapDocumentType("01");
    d.setOperationType(3); // customer sale
    d.getAmounts().add(bucket("BG_SRS_20", "5500", "1100"));
    return Collections.singletonList(d);
  }

  /** Two purchases with full credit → POKUPKI: к.10=91.41/150.00, к.11=18.28/30.00. */
  private List<BgVatDocumentDto> purchaseDocs() {
    BgVatDocumentDto p1 = new BgVatDocumentDto();
    p1.setNapDocumentType("01");
    p1.addPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT, new BigDecimal("91.41"));
    p1.addPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT, new BigDecimal("18.28"));

    BgVatDocumentDto p2 = new BgVatDocumentDto();
    p2.setNapDocumentType("01");
    p2.addPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT, new BigDecimal("150.00"));
    p2.addPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT, new BigDecimal("30.00"));

    return java.util.Arrays.asList(p1, p2);
  }

  private Header header() {
    return new Header(
        "BG100000033", "ТЕСТОВА ГРУП ЕООД", "202606", "6803030040/Николай Стоянов Димитров");
  }

  private ManualCells manual() {
    return new ManualCells(
        new BigDecimal("0.00"), // кл.33 коефициент
        BigDecimal.ZERO, // кл.70
        new BigDecimal("1051.72"), // кл.71
        BigDecimal.ZERO, // кл.80
        BigDecimal.ZERO, // кл.81
        BigDecimal.ZERO); // кл.82
  }

  private byte[] fixtureBytes() throws IOException {
    try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
      assertNotNull(in, "missing golden fixture " + RESOURCE);
      return in.readAllBytes();
    }
  }

  @Test
  void buildLines_matchesAcceptedRecord_charForChar() throws IOException {
    byte[] fileBytes = fixtureBytes();
    String expected = new String(fileBytes, CP1251).split("\r\n", -1)[0];

    List<String> lines = adapter.buildLines(salesDocs(), purchaseDocs(), header(), manual());
    assertEquals(1, lines.size());
    String actual = lines.get(0);

    assertEquals(590, expected.length(), "fixture record length");
    assertEquals(590, actual.length(), "generated record length");
    if (!expected.equals(actual)) {
      int n = Math.min(expected.length(), actual.length());
      int i = 0;
      while (i < n && expected.charAt(i) == actual.charAt(i)) {
        i++;
      }
      int s = Math.max(0, i - 2);
      int e = Math.min(n, i + 16);
      System.out.println(
          "First diff at index "
              + i
              + " [expected|actual] window ["
              + s
              + ":"
              + e
              + "]:\n  expected |"
              + expected.substring(s, e)
              + "|\n  actual   |"
              + actual.substring(s, e)
              + "|");
    }
    assertEquals(expected, actual);
  }

  @Test
  void writerOutput_isByteForByteEqualToFile() throws IOException {
    byte[] fileBytes = fixtureBytes();
    List<String> lines = adapter.buildLines(salesDocs(), purchaseDocs(), header(), manual());
    byte[] written = new BgVatFileWriter().toBytes(lines, "cp1251");
    assertArrayEquals(fileBytes, written);
  }
}

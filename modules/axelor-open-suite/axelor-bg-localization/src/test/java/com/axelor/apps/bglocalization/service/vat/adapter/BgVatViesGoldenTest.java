package com.axelor.apps.bglocalization.service.vat.adapter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.axelor.apps.bglocalization.service.vat.BgVatFileWriter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatViesAdapter.Declarant;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatViesAdapter.RegisteredPerson;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatAmountDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Golden-file lock for VIES.TXT (Variant 1: VHR·VDR·VTR·TTR·VIR). Reconstructs the accepted file
 * from {@code src/test/resources/nap/VIES.txt} (period 06/2026, CP1251 + CRLF after every record,
 * no BOM, 1131 bytes) and asserts byte-for-byte equality.
 */
class BgVatViesGoldenTest {

  private static final String RESOURCE = "/nap/VIES.txt";
  private static final Charset CP1251 = Charset.forName("windows-1251");

  private final BgVatViesAdapter adapter = new BgVatViesAdapter(new BgVatSalesAdapter());

  /** One intra-community supply of goods (ВОД) to an EU customer. */
  private BgVatDocumentDto vod(String vin, String base) {
    BgVatDocumentDto d = new BgVatDocumentDto();
    d.setNapDocumentType("01");
    d.setOperationType(3); // customer sale
    d.setCounterpartyVatNumber(vin);
    BgVatAmountDto a = new BgVatAmountDto();
    a.setTaxCode("BG_VOD"); // → к.20 (02-15) ВОД
    a.setBase(new BigDecimal(base));
    d.getAmounts().add(a);
    return d;
  }

  private List<BgVatDocumentDto> salesDocs() {
    List<BgVatDocumentDto> docs = new ArrayList<>();
    docs.add(vod("EL900000017", "1377.12"));
    docs.add(vod("EL900000029", "934.00"));
    docs.add(vod("EL900000030", "748.00"));
    docs.add(vod("EL900000042", "492.50"));
    docs.add(vod("EL900000054", "207.00"));
    return docs;
  }

  private RegisteredPerson person() {
    return new RegisteredPerson(
        "BG100000065", "Тест Фиш България ООД", "с. Герман ул. Васил Стоилов 33");
  }

  private Declarant declarant() {
    return new Declarant(
        "6803030040", "Николай Димитров", "София", "1186", "с. Герман ул. Васил Стоилов 33", "A");
  }

  private byte[] fixtureBytes() throws IOException {
    try (InputStream in = getClass().getResourceAsStream(RESOURCE)) {
      assertNotNull(in, "missing golden fixture " + RESOURCE);
      return in.readAllBytes();
    }
  }

  @Test
  void buildLines_matchAcceptedSections_lineForLine() throws IOException {
    byte[] fileBytes = fixtureBytes();
    String[] expected = new String(fileBytes, CP1251).split("\r\n", -1);

    List<String> actual = adapter.buildLines(salesDocs(), person(), declarant(), "06/2026");
    // expected has a trailing empty element after the last CRLF.
    assertEquals(actual.size(), expected.length - 1, "section count");
    for (int i = 0; i < actual.size(); i++) {
      if (!expected[i].equals(actual.get(i))) {
        int n = Math.min(expected[i].length(), actual.get(i).length());
        int j = 0;
        while (j < n && expected[i].charAt(j) == actual.get(i).charAt(j)) {
          j++;
        }
        System.out.println(
            "Section "
                + i
                + " ("
                + actual.get(i).substring(0, 3)
                + ") first diff at "
                + j
                + ":\n  expected |"
                + expected[i]
                + "|\n  actual   |"
                + actual.get(i)
                + "|");
      }
      assertEquals(expected[i], actual.get(i), "section " + i);
    }
  }

  @Test
  void writerOutput_isByteForByteEqualToFile() throws IOException {
    byte[] fileBytes = fixtureBytes();
    List<String> lines = adapter.buildLines(salesDocs(), person(), declarant(), "06/2026");
    byte[] written = new BgVatFileWriter().toBytes(lines, "cp1251");
    assertEquals(1131, fileBytes.length, "fixture size");
    assertArrayEquals(fileBytes, written);
  }
}

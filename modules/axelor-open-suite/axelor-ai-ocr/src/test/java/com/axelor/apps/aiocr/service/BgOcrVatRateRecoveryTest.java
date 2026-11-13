package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractLine;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Intake-side VAT-rate recovery: invoice #12's real line section, and the three ways it must
 * refuse.
 *
 * <p>The service is constructed with null collaborators on purpose — the method under test reads
 * nothing but the extraction it is handed, so this stays a pure test with no Guice and no JPA.
 */
class BgOcrVatRateRecoveryTest {

  private final BgOcrIntakeService intake = new BgOcrIntakeService(null, null, null, null, null);

  /** The ten line nets of upload #41, in order. They sum to 146.56. */
  private static final String[] INVOICE_12_LINE_NETS = {
    "3.30", "5.00", "5.38", "4.05", "1.39", "2.39", "10.62", "2.25", "16.18", "96.00"
  };

  private static OcrExtractResult document(String net, String vat, String gross, String... nets) {
    OcrExtractResult result = new OcrExtractResult();
    result.setTotalBase(net == null ? null : new BigDecimal(net));
    result.setTotalVat(vat == null ? null : new BigDecimal(vat));
    result.setTotalGross(gross == null ? null : new BigDecimal(gross));
    List<OcrExtractLine> lines = new ArrayList<>();
    for (String lineNet : nets) {
      OcrExtractLine line = new OcrExtractLine();
      line.setDescription("line " + lineNet);
      line.setNetAmount(new BigDecimal(lineNet));
      // Exactly what Azure left behind for #41: an amount, and no VAT evidence whatsoever. The
      // normalisation then derived a zero — derived, so it is not evidence.
      line.setDerivedVatAmount(BigDecimal.ZERO);
      line.setDerivedVatRate(BigDecimal.ZERO.setScale(2));
      lines.add(line);
    }
    result.setLines(lines);
    return result;
  }

  @Test
  void invoice12_everyRatelessLineBecomesTwentyPercent() {
    OcrExtractResult result = document("146.57", "29.31", "175.88", INVOICE_12_LINE_NETS);
    List<String> notes = new ArrayList<>();

    intake.recoverMissingLineVatRates(result, false, notes);

    BigDecimal netSum = BigDecimal.ZERO;
    for (OcrExtractLine line : result.getLines()) {
      assertEquals(new BigDecimal("20"), line.getVatRate(), line.getDescription());
      assertEquals(
          line.getNetAmount().add(line.getVatAmount()),
          line.getGrossAmount(),
          "the line must stay self-consistent");
      netSum = netSum.add(line.getNetAmount());
    }
    assertEquals(new BigDecimal("146.56"), netSum);

    // What Axelor will post: it rounds ONCE per rate, not per line.
    BigDecimal perRateVat =
        netSum
            .multiply(new BigDecimal("20"))
            .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
    assertEquals(new BigDecimal("29.31"), perRateVat, "the VAT Axelor's summary will carry");
    assertEquals(
        new BigDecimal("175.87"),
        netSum.add(perRateVat),
        "gross before the 609 correction — the printed 175.88 is then exactly 0.01 away");

    assertTrue(notes.size() == 1 && notes.get(0).contains("20"), notes.toString());
  }

  @Test
  void aGenuineZeroVatDocumentKeepsItsZeroRate() {
    OcrExtractResult result = document("146.56", "0.00", "146.56", INVOICE_12_LINE_NETS);
    List<String> notes = new ArrayList<>();

    intake.recoverMissingLineVatRates(result, false, notes);

    for (OcrExtractLine line : result.getLines()) {
      assertEquals(0, line.getVatRate().signum(), "a zero-rated document stays zero-rated");
    }
    assertTrue(notes.isEmpty(), "nothing to report when there was nothing to recover");
  }

  @Test
  void anExplicitPerLineRateIsNeverOverwritten() {
    OcrExtractResult result = document("146.57", "29.31", "175.88", INVOICE_12_LINE_NETS);
    // One line the document really did print a rate for — 9%, deliberately not the rate the
    // summary would imply. A document that states VAT anywhere is never re-rated wholesale.
    result.getLines().get(3).setVatRate(new BigDecimal("9"));

    intake.recoverMissingLineVatRates(result, false, new ArrayList<>());

    assertEquals(new BigDecimal("9"), result.getLines().get(3).getVatRate());
    assertEquals(
        0,
        result.getLines().get(0).getVatRate().signum(),
        "the other lines keep exactly what was extracted, mixed rates included");
  }

  @Test
  void anUnprovableRateFailsClosedAndSaysSo() {
    // net + VAT does not make the printed gross: the document contradicts itself.
    OcrExtractResult result = document("146.57", "29.31", "181.00", INVOICE_12_LINE_NETS);
    List<String> notes = new ArrayList<>();

    intake.recoverMissingLineVatRates(result, false, notes);

    for (OcrExtractLine line : result.getLines()) {
      assertEquals(0, line.getVatRate().signum(), "nothing is invented");
    }
    assertEquals(1, notes.size(), "a document that declares VAT and keeps none must explain why");
    assertTrue(notes.get(0).contains("could not be recovered"), notes.get(0));
  }
}

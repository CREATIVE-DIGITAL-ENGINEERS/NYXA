package com.axelor.apps.aiocr.service.readiness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoiceLineTax;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The cent that blocked invoices 0900092440 and 0900092945.
 *
 * <p>Both suppliers' documents are internally correct: every line's gross is its net plus VAT
 * rounded at that line, and the line grosses sum exactly to the printed invoice total. Axelor
 * rounds once per rate instead, comes out a cent short on VAT, and the resulting debits no longer
 * equal the payable credit — so ventilation is blocked and, worse, validation would silently post a
 * cent less than the supplier billed.
 *
 * <p>Pure — no Guice, no JPA.
 */
class OcrVatRoundingReconcilerTest {

  private static final BigDecimal RATE_20 = new BigDecimal("20");

  /** A 20% purchase tax, flagged the way the fix requires. */
  private Tax tax(String code, boolean manageByAmount) {
    Tax tax = new Tax();
    tax.setCode(code);
    tax.setManageByAmount(manageByAmount);
    return tax;
  }

  private TaxLine taxLine(Tax tax) {
    TaxLine taxLine = new TaxLine();
    taxLine.setTax(tax);
    taxLine.setValue(RATE_20);
    return taxLine;
  }

  private InvoiceLine line(String net) {
    BigDecimal exTax = new BigDecimal(net);
    // What the supplier prints: VAT rounded at the line.
    BigDecimal vat =
        exTax.multiply(new BigDecimal("0.20")).setScale(2, java.math.RoundingMode.HALF_UP);
    InvoiceLine line = new InvoiceLine();
    line.setExTaxTotal(exTax);
    line.setInTaxTotal(exTax.add(vat));
    line.setCompanyExTaxTotal(exTax);
    line.setCompanyInTaxTotal(exTax.add(vat));
    return line;
  }

  /**
   * Assembles the invoice exactly as the pipeline leaves it: OCR gross on the header, and a summary
   * row carrying Axelor's single-shot {@code round(Σ base × rate)}.
   */
  private Invoice invoice(List<InvoiceLine> lines, Tax tax) {
    BigDecimal netSum = BigDecimal.ZERO;
    BigDecimal grossSum = BigDecimal.ZERO;
    for (InvoiceLine line : lines) {
      netSum = netSum.add(line.getExTaxTotal());
      grossSum = grossSum.add(line.getInTaxTotal());
    }
    BigDecimal axelorVat =
        netSum.multiply(new BigDecimal("0.20")).setScale(2, java.math.RoundingMode.HALF_UP);

    InvoiceLineTax row = new InvoiceLineTax();
    row.setTaxLine(taxLine(tax));
    row.setExTaxBase(netSum);
    row.setCompanyExTaxBase(netSum);
    row.setTaxTotal(axelorVat);
    row.setPercentageTaxTotal(axelorVat);
    row.setCompanyTaxTotal(axelorVat);
    row.setInTaxTotal(netSum.add(axelorVat));
    row.setCompanyInTaxTotal(netSum.add(axelorVat));

    Invoice invoice = new Invoice();
    invoice.setStatusSelect(InvoiceRepository.STATUS_DRAFT);
    invoice.setInvoiceLineList(new ArrayList<>(lines));
    invoice.setInvoiceLineTaxList(new ArrayList<>(List.of(row)));
    // The printed gross, which is the sum of the per-line grosses.
    invoice.setInTaxTotal(grossSum);
    invoice.setCompanyInTaxTotal(grossSum);
    return invoice;
  }

  /** Invoice 0900092440: ten lines, 146.56 net, printed total 175.88. */
  private List<InvoiceLine> invoice0900092440Lines() {
    return List.of(
        line("3.30"),
        line("5.00"),
        line("5.38"),
        line("4.05"),
        line("1.39"),
        line("2.39"),
        line("10.62"),
        line("2.25"),
        line("16.18"),
        line("96.00"));
  }

  /** Invoice 0900092945: eight lines, 197.07 net, printed total 236.49. */
  private List<InvoiceLine> invoice0900092945Lines() {
    return List.of(
        line("15.00"),
        line("16.14"),
        line("2.70"),
        line("11.80"),
        line("16.18"),
        line("60.80"),
        line("10.45"),
        line("64.00"));
  }

  private BigDecimal postingDelta(Invoice invoice) {
    BigDecimal debits = BigDecimal.ZERO;
    for (InvoiceLine line : invoice.getInvoiceLineList()) {
      debits = debits.add(line.getCompanyExTaxTotal());
    }
    for (InvoiceLineTax row : invoice.getInvoiceLineTaxList()) {
      debits = debits.add(row.getCompanyTaxTotal());
    }
    return debits.subtract(invoice.getCompanyInTaxTotal());
  }

  @Test
  void invoice0900092440IsBalancedByAddingTheLostCentToTheVatRow() {
    Invoice invoice = invoice(invoice0900092440Lines(), tax("BG_SRP_20", true));
    InvoiceLineTax row = invoice.getInvoiceLineTaxList().get(0);

    // The state that produces the blocker: 146.56 + 29.31 = 175.87 against a credit of 175.88.
    assertEquals(0, new BigDecimal("146.56").compareTo(row.getExTaxBase()));
    assertEquals(0, new BigDecimal("29.31").compareTo(row.getTaxTotal()));
    assertEquals(0, new BigDecimal("-0.01").compareTo(postingDelta(invoice)));

    OcrVatRoundingReconciler.Outcome outcome = OcrVatRoundingReconciler.apply(invoice);

    assertTrue(outcome.isApplied(), outcome.getRefusalReason());
    assertEquals(0, new BigDecimal("29.32").compareTo(row.getTaxTotal()));
    assertEquals(0, new BigDecimal("29.32").compareTo(row.getCompanyTaxTotal()));
    assertEquals(0, BigDecimal.ZERO.compareTo(postingDelta(invoice)));
  }

  @Test
  void invoice0900092945IsBalancedTheSameWay() {
    Invoice invoice = invoice(invoice0900092945Lines(), tax("BG_SRP_20", true));
    InvoiceLineTax row = invoice.getInvoiceLineTaxList().get(0);

    assertEquals(0, new BigDecimal("197.07").compareTo(row.getExTaxBase()));
    assertEquals(0, new BigDecimal("39.41").compareTo(row.getTaxTotal()));

    assertTrue(OcrVatRoundingReconciler.apply(invoice).isApplied());

    assertEquals(0, new BigDecimal("39.42").compareTo(row.getTaxTotal()));
    assertEquals(0, BigDecimal.ZERO.compareTo(postingDelta(invoice)));
    // The payable stays what the supplier billed — the whole point of the fix.
    assertEquals(0, new BigDecimal("236.49").compareTo(invoice.getInTaxTotal()));
  }

  @Test
  void percentageTaxTotalKeepsTheDerivedFigureSoAxelorPreservesTheOverride() {
    Invoice invoice = invoice(invoice0900092440Lines(), tax("BG_SRP_20", true));
    InvoiceLineTax row = invoice.getInvoiceLineTaxList().get(0);

    OcrVatRoundingReconciler.apply(invoice);

    // InvoiceGenerator.getUpdatedInvoiceLineTax keeps the row only while these two differ, and
    // TaxInvoiceLine reuses it only while percentageTaxTotal still equals what it recomputes.
    assertEquals(0, new BigDecimal("29.31").compareTo(row.getPercentageTaxTotal()));
    assertEquals(0, new BigDecimal("29.32").compareTo(row.getTaxTotal()));
    assertTrue(row.getTaxTotal().compareTo(row.getPercentageTaxTotal()) != 0);
  }

  @Test
  void runningItTwiceChangesNothingTheSecondTime() {
    Invoice invoice = invoice(invoice0900092440Lines(), tax("BG_SRP_20", true));
    InvoiceLineTax row = invoice.getInvoiceLineTaxList().get(0);

    assertTrue(OcrVatRoundingReconciler.apply(invoice).isApplied());
    OcrVatRoundingReconciler.Outcome second = OcrVatRoundingReconciler.apply(invoice);

    assertFalse(second.isApplied(), "a second pass must find nothing left to correct");
    assertEquals(0, new BigDecimal("29.32").compareTo(row.getTaxTotal()));
  }

  @Test
  void anAlreadyBalancedInvoiceIsLeftAlone() {
    // 100.00 at 20% rounds identically per line and per rate, so there is no cent to move.
    Invoice invoice = invoice(List.of(line("100.00")), tax("BG_SRP_20", true));

    OcrVatRoundingReconciler.Outcome outcome = OcrVatRoundingReconciler.apply(invoice);

    assertFalse(outcome.isApplied());
    assertEquals(
        0, new BigDecimal("20.00").compareTo(invoice.getInvoiceLineTaxList().get(0).getTaxTotal()));
  }

  @Test
  void aMissingManageByAmountFlagRefusesAndSaysWhichTax() {
    Invoice invoice = invoice(invoice0900092440Lines(), tax("BG_SRP_20", false));
    InvoiceLineTax row = invoice.getInvoiceLineTaxList().get(0);

    OcrVatRoundingReconciler.Outcome outcome = OcrVatRoundingReconciler.apply(invoice);

    assertFalse(outcome.isApplied());
    assertTrue(outcome.isBlockedByTaxConfig());
    assertNotNull(outcome.getRefusalReason());
    assertTrue(
        outcome.getRefusalReason().contains("BG_SRP_20"),
        "the admin has to be told which tax to configure: " + outcome.getRefusalReason());
    // Nothing written — the correction would not have survived validation anyway.
    assertEquals(0, new BigDecimal("29.31").compareTo(row.getTaxTotal()));
  }

  @Test
  void
      computedTotalOneCentBelowOcrTotalIsAdjustedEvenWhenPerLineGrossDoesNotMatchThePrintedTotal() {
    Invoice invoice = invoice(invoice0900092440Lines(), tax("BG_SRP_20", true));
    InvoiceLineTax row = invoice.getInvoiceLineTaxList().get(0);
    // Azure's own per-line gross figures do not have to sum to its InvoiceTotal field: bump one
    // line's reported gross by a cent, leaving the net (and therefore the computed total) alone.
    // Requiring Σ line.inTaxTotal == printed gross first — as an earlier version of this class did
    // — would bail here even though the computed total is still exactly one cent short.
    InvoiceLine firstLine = invoice.getInvoiceLineList().get(0);
    firstLine.setInTaxTotal(firstLine.getInTaxTotal().add(new BigDecimal("0.01")));

    OcrVatRoundingReconciler.Outcome outcome = OcrVatRoundingReconciler.apply(invoice);

    assertTrue(outcome.isApplied(), outcome.getRefusalReason());
    assertEquals(0, new BigDecimal("29.32").compareTo(row.getTaxTotal()));
    assertEquals(0, BigDecimal.ZERO.compareTo(postingDelta(invoice)));
  }

  @Test
  void computedTotalOneCentAboveOcrTotalIsAdjustedDownward() {
    Invoice invoice = invoice(invoice0900092440Lines(), tax("BG_SRP_20", true));
    InvoiceLineTax row = invoice.getInvoiceLineTaxList().get(0);
    // Computed (146.56 net + 29.31 VAT = 175.87) is now ABOVE the printed/OCR gross, the opposite
    // direction from the other cases — reconciliation must work both ways.
    invoice.setInTaxTotal(new BigDecimal("175.86"));
    invoice.setCompanyInTaxTotal(new BigDecimal("175.86"));

    OcrVatRoundingReconciler.Outcome outcome = OcrVatRoundingReconciler.apply(invoice);

    assertTrue(outcome.isApplied(), outcome.getRefusalReason());
    assertEquals(0, new BigDecimal("29.30").compareTo(row.getTaxTotal()));
    assertEquals(0, BigDecimal.ZERO.compareTo(postingDelta(invoice)));
  }

  @Test
  void linesThatDoNotReproduceThePrintedTotalAreLeftToTheExistingBlocker() {
    Invoice invoice = invoice(invoice0900092440Lines(), tax("BG_SRP_20", true));
    // A genuinely wrong extraction rather than per-line rounding: the printed gross no longer
    // matches the sum of the line grosses.
    invoice.setInTaxTotal(new BigDecimal("200.00"));
    invoice.setCompanyInTaxTotal(new BigDecimal("200.00"));

    OcrVatRoundingReconciler.Outcome outcome = OcrVatRoundingReconciler.apply(invoice);

    assertFalse(outcome.isApplied());
    assertFalse(
        outcome.isBlockedByTaxConfig(), "this is not a tax-configuration problem, it is bad data");
    assertEquals(
        0, new BigDecimal("29.31").compareTo(invoice.getInvoiceLineTaxList().get(0).getTaxTotal()));
  }

  @Test
  void diagnoseReportsTheSameVerdictWithoutWritingAnything() {
    Invoice invoice = invoice(invoice0900092440Lines(), tax("BG_SRP_20", true));
    InvoiceLineTax row = invoice.getInvoiceLineTaxList().get(0);

    OcrVatRoundingReconciler.Outcome outcome = OcrVatRoundingReconciler.diagnose(invoice);

    assertTrue(outcome.isApplied(), "diagnose reports what apply would have done");
    assertEquals(0, new BigDecimal("29.31").compareTo(row.getTaxTotal()), "but must not write");
    assertEquals(0, new BigDecimal("-0.01").compareTo(postingDelta(invoice)));
  }

  @Test
  void aNonDraftInvoiceIsNeverTouched() {
    Invoice invoice = invoice(invoice0900092440Lines(), tax("BG_SRP_20", true));
    invoice.setStatusSelect(InvoiceRepository.STATUS_VENTILATED);

    assertFalse(OcrVatRoundingReconciler.apply(invoice).isApplied());
    assertEquals(
        0, new BigDecimal("29.31").compareTo(invoice.getInvoiceLineTaxList().get(0).getTaxTotal()));
  }
}

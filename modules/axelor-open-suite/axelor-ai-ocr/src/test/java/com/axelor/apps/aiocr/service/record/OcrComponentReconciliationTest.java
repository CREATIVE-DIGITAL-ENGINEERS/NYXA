package com.axelor.apps.aiocr.service.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoiceLineTax;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiocr.service.ocr.BgOcrVatMapping;
import com.axelor.apps.base.AxelorException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Invoice #14: the right total built from the wrong two halves.
 *
 * <p>Ten 20% lines summing to 146.56, a 20% summary row pushed to 29.32 by the {@code
 * manageByAmount} override, and a printed document of net 146.57 / VAT 29.31 / gross 175.88. The
 * gross matched exactly — 146.56 + 29.32 = 175.88 — so the gross-only comparison found nothing to
 * do and the invoice validated with a stotinka of net declared as VAT.
 *
 * <p>{@link Reconciler#recompute} is not a stub: it reproduces {@code
 * InvoiceGenerator.initInvoiceLineTaxList} + {@code TaxInvoiceLine} + {@code
 * InvoiceTaxComputeService}, INCLUDING the rule that preserves a {@code manageByAmount} row whose
 * {@code taxTotal} differs from its {@code percentageTaxTotal}. Without that the test could not
 * show the override being corrected rather than simply regenerated away.
 */
class OcrComponentReconciliationTest {

  private static final String[] LINE_NETS = {
    "3.30", "5.00", "5.38", "4.05", "1.39", "2.39", "10.62", "2.25", "16.18", "96.00"
  };

  // ── the invoice exactly as the database holds #14 ──────────────────────────────────────────

  private static Invoice invoice14(boolean withBreakdown) {
    Invoice invoice = new Invoice();
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    invoice.setStatusSelect(InvoiceRepository.STATUS_DRAFT);
    invoice.setInvoiceLineList(new ArrayList<>());
    for (String net : LINE_NETS) {
      InvoiceLine line = new InvoiceLine();
      line.setExTaxTotal(new BigDecimal(net));
      line.setCompanyExTaxTotal(new BigDecimal(net));
      line.setTaxRate(new BigDecimal("20"));
      line.setTaxCode(BgOcrVatMapping.BG_STD_20);
      line.setTaxLineSet(taxLineSetAt(new BigDecimal("20")));
      invoice.addInvoiceLineListItem(line);
    }
    // The persisted summary row: the manageByAmount override that reached the printed gross by
    // inflating the VAT (tax_total 29.320 over percentage_tax_total 29.310).
    InvoiceLineTax row = new InvoiceLineTax();
    row.setExTaxBase(new BigDecimal("146.56"));
    row.setCompanyExTaxBase(new BigDecimal("146.56"));
    row.setTaxTotal(new BigDecimal("29.32"));
    row.setCompanyTaxTotal(new BigDecimal("29.32"));
    row.setPercentageTaxTotal(new BigDecimal("29.31"));
    row.setInTaxTotal(new BigDecimal("175.88"));
    row.setTaxLine(manageByAmountTaxLine());
    List<InvoiceLineTax> rows = new ArrayList<>();
    rows.add(row);
    invoice.setInvoiceLineTaxList(rows);

    invoice.setExTaxTotal(new BigDecimal("146.56"));
    invoice.setTaxTotal(new BigDecimal("29.32"));
    invoice.setInTaxTotal(new BigDecimal("175.88"));
    invoice.setCompanyExTaxTotal(new BigDecimal("146.56"));
    invoice.setCompanyTaxTotal(new BigDecimal("29.32"));
    invoice.setCompanyInTaxTotal(new BigDecimal("175.88"));

    invoice.setOcrDocumentTotal(new BigDecimal("175.88"));
    if (withBreakdown) {
      invoice.setOcrDocumentNetTotal(new BigDecimal("146.57"));
      invoice.setOcrDocumentVatTotal(new BigDecimal("29.31"));
      invoice.setOcrDocumentBreakdownAvailable(true);
    }
    return invoice;
  }

  private static TaxLine manageByAmountTaxLine() {
    Tax tax = new Tax();
    tax.setCode(BgOcrVatMapping.BG_STD_20);
    tax.setManageByAmount(true);
    TaxLine taxLine = new TaxLine();
    taxLine.setTax(tax);
    taxLine.setValue(new BigDecimal("20"));
    return taxLine;
  }

  private static Set<TaxLine> taxLineSetAt(BigDecimal rate) {
    Set<TaxLine> set = new HashSet<>();
    set.add(rate.signum() == 0 ? zeroRateTaxLine() : manageByAmountTaxLine());
    return set;
  }

  /** A tax line at an arbitrary rate, for building a deliberately mixed-rate invoice. */
  private static Set<TaxLine> taxLineSetAtRate(BigDecimal rate) {
    Tax tax = new Tax();
    tax.setCode("BG_RATE_" + rate.intValue());
    TaxLine taxLine = new TaxLine();
    taxLine.setTax(tax);
    taxLine.setValue(rate);
    Set<TaxLine> set = new HashSet<>();
    set.add(taxLine);
    return set;
  }

  private static TaxLine zeroRateTaxLine() {
    Tax tax = new Tax();
    tax.setCode(BgOcrVatMapping.BG_ZERO);
    TaxLine taxLine = new TaxLine();
    taxLine.setTax(tax);
    taxLine.setValue(BigDecimal.ZERO);
    return taxLine;
  }

  // ── the tests ──────────────────────────────────────────────────────────────────────────────

  @Test
  void componentReconciliationFixesBothHalvesAndBooksTheNetCentTo609() throws Exception {
    Invoice invoice = invoice14(true);

    new Reconciler().reconcileBeforeValidation(invoice);

    assertEquals(new BigDecimal("146.57"), invoice.getExTaxTotal(), "net");
    assertEquals(new BigDecimal("29.31"), invoice.getTaxTotal(), "VAT, corrected from 29.32");
    assertEquals(new BigDecimal("175.88"), invoice.getInTaxTotal(), "gross");

    InvoiceLine correction = onlyAdjustmentLine(invoice);
    assertNotNull(correction, "the net stotinka belongs on 609, not in the VAT");
    assertEquals(new BigDecimal("0.01"), correction.getExTaxTotal());
    assertEquals("60909", correction.getAccount().getCode());
    // The cent is part of the document's 20% taxable base — same bucket as the lines it came from.
    assertEquals(BgOcrVatMapping.BG_STD_20, correction.getTaxCode());
    assertEquals(new BigDecimal("20"), correction.getTaxRate());

    // EXACTLY ONE Tax row: base 146.57 at 20%, VAT 29.31. No second BG_ZRP row.
    assertEquals(1, invoice.getInvoiceLineTaxList().size(), "the Tax section has one row");
    InvoiceLineTax only = invoice.getInvoiceLineTaxList().get(0);
    assertEquals(new BigDecimal("146.57"), only.getExTaxBase());
    assertEquals(new BigDecimal("29.31"), only.getTaxTotal());
    assertEquals(new BigDecimal("20"), only.getTaxLine().getValue());
  }

  @Test
  void theBucketsCreditTypeIsInheritedByTheCorrection() throws Exception {
    Invoice invoice = invoice14(true);
    RecordingCreditType recorder = new RecordingCreditType();
    Reconciler reconciler = new Reconciler();
    reconciler.creditType = recorder;

    reconciler.reconcileBeforeValidation(invoice);

    assertNotNull(recorder.roundingLine, "the credit type must be asked for");
    assertEquals(
        LINE_NETS.length,
        recorder.bucketLines.size(),
        "every ordinary line of the bucket is offered as the evidence to inherit from");
    assertTrue(
        !recorder.bucketLines.contains(recorder.roundingLine),
        "the correction is never its own evidence");
  }

  /** Stands in for bg-localization's binding, which owns the bgVatCreditType field. */
  private static class RecordingCreditType {
    private InvoiceLine roundingLine;
    private List<InvoiceLine> bucketLines = new ArrayList<>();
    private String refusal;
  }

  @Test
  void draftPreparationIsIdempotent() throws Exception {
    Invoice invoice = invoice14(true);
    Reconciler reconciler = new Reconciler();

    assertNull(reconciler.prepareDraft(invoice));
    assertNull(reconciler.prepareDraft(invoice));
    assertNull(reconciler.prepareDraft(invoice));

    assertEquals(LINE_NETS.length + 1, invoice.getInvoiceLineList().size(), "one correction only");
    assertEquals(new BigDecimal("0.01"), onlyAdjustmentLine(invoice).getExTaxTotal());
    assertEquals(1, invoice.getInvoiceLineTaxList().size(), "still exactly one Tax row");
    assertEquals(new BigDecimal("146.57"), invoice.getExTaxTotal());
    assertEquals(new BigDecimal("29.31"), invoice.getTaxTotal());
    assertEquals(new BigDecimal("175.88"), invoice.getInTaxTotal());
  }

  @Test
  void validatingAPreparedDraftRebuildsNothingAndAddsNothing() throws Exception {
    Invoice invoice = invoice14(true);
    Reconciler reconciler = new Reconciler();
    reconciler.prepareDraft(invoice);

    int linesAfterPrepare = invoice.getInvoiceLineList().size();
    reconciler.syncCount = 0;
    reconciler.computeCount = 0;

    reconciler.reconcileBeforeValidation(invoice);

    // THE duplicate-row fix: on an already-prepared draft the validation hook reads four figures
    // and returns. Stock validation's own single compute then regenerates the same summary.
    assertEquals(0, reconciler.syncCount, "no tax-summary rebuild inside the validation call");
    assertEquals(0, reconciler.computeCount, "no custom compute inside the validation call");
    assertEquals(linesAfterPrepare, invoice.getInvoiceLineList().size(), "no new line");
    assertEquals(1, invoice.getInvoiceLineTaxList().size(), "no new Tax row");
    assertEquals(new BigDecimal("146.57"), invoice.getExTaxTotal());
    assertEquals(new BigDecimal("29.31"), invoice.getTaxTotal());
    assertEquals(new BigDecimal("175.88"), invoice.getInTaxTotal());
  }

  @Test
  void aNegativeRoundingDifferenceWorksSymmetrically() throws Exception {
    Invoice invoice = invoice14(true);
    // The document's net is a stotinka BELOW the lines: the correction must be −0.01.
    invoice.setOcrDocumentNetTotal(new BigDecimal("146.55"));
    invoice.setOcrDocumentTotal(new BigDecimal("175.86"));

    new Reconciler().prepareDraft(invoice);

    assertEquals(new BigDecimal("-0.01"), onlyAdjustmentLine(invoice).getExTaxTotal());
    assertEquals(new BigDecimal("146.55"), invoice.getExTaxTotal());
    assertEquals(new BigDecimal("29.31"), invoice.getTaxTotal());
    assertEquals(new BigDecimal("175.86"), invoice.getInTaxTotal());
    assertEquals(1, invoice.getInvoiceLineTaxList().size());
  }

  @Test
  void aMixedRateInvoiceIsRefusedRatherThanGuessed() {
    Invoice invoice = invoice14(true);
    // One line moved to 9%: there is no single bucket the cent belongs to any more.
    invoice.getInvoiceLineList().get(2).setTaxLineSet(taxLineSetAtRate(new BigDecimal("9")));

    AxelorException raised =
        assertThrows(
            AxelorException.class, () -> new Reconciler().reconcileBeforeValidation(invoice));

    assertTrue(raised.getMessage().contains("mixes VAT treatments"), raised.getMessage());
    assertNull(onlyAdjustmentLine(invoice), "never placed in a bucket that is not provable");
  }

  @Test
  void conflictingCreditTypesRefuseTheAutomaticPlacement() {
    Invoice invoice = invoice14(true);
    Reconciler reconciler = new Reconciler();
    RecordingCreditType recorder = new RecordingCreditType();
    recorder.refusal = "Редовете имат различен вид данъчен кредит.";
    reconciler.creditType = recorder;

    AxelorException raised =
        assertThrows(AxelorException.class, () -> reconciler.reconcileBeforeValidation(invoice));

    assertTrue(raised.getMessage().contains("данъчен кредит"), raised.getMessage());
  }

  @Test
  void anOlderOcrInvoiceWithoutTheMarkerKeepsTheGrossOnlyBehaviour() throws Exception {
    // No marker: the decimals are NULL, which the generated getters report as 0.00. Treating that
    // as a declared net/VAT of zero would refuse every historical invoice.
    Invoice invoice = invoice14(false);

    new Reconciler().reconcileBeforeValidation(invoice);

    assertNull(onlyAdjustmentLine(invoice), "gross already matches, so nothing is booked");
    assertEquals(new BigDecimal("146.56"), invoice.getExTaxTotal());
    assertEquals(new BigDecimal("29.32"), invoice.getTaxTotal(), "the override is left alone");
    assertEquals(new BigDecimal("175.88"), invoice.getInTaxTotal());
  }

  @Test
  void aComponentErrorTooLargeToBeRoundingIsRefusedNotBookedTo609() {
    Invoice invoice = invoice14(true);
    invoice.setOcrDocumentNetTotal(new BigDecimal("150.00")); // 3.44 of net unaccounted for
    invoice.setOcrDocumentTotal(new BigDecimal("179.31"));

    AxelorException raised =
        assertThrows(
            AxelorException.class, () -> new Reconciler().reconcileBeforeValidation(invoice));

    assertTrue(raised.getMessage().contains("150.00"), raised.getMessage());
    assertNull(onlyAdjustmentLine(invoice), "a real error must never be plugged into 609");
  }

  private static InvoiceLine onlyAdjustmentLine(Invoice invoice) {
    InvoiceLine found = null;
    for (InvoiceLine line : invoice.getInvoiceLineList()) {
      if (Boolean.TRUE.equals(line.getOcrRoundingAdjustment())) {
        assertTrue(found == null, "more than one automatic rounding line");
        found = line;
      }
    }
    return found;
  }

  private static InvoiceLineTax rowAtRate(Invoice invoice, BigDecimal rate) {
    for (InvoiceLineTax row : invoice.getInvoiceLineTaxList()) {
      if (row.getTaxLine() != null && row.getTaxLine().getValue().compareTo(rate) == 0) {
        return row;
      }
    }
    return null;
  }

  /** The real service with only its DB lookups replaced, and a faithful Axelor recomputation. */
  private static class Reconciler extends OcrRoundingLineService {

    /** Тестът твърди сметки, не единици: без инсталирана локализация правилото мълчи. */
    @Override
    protected com.axelor.apps.aiaccounting.service.invoice.InvoiceLineUnitDefaultService
        unitDefaults() {
      return com.axelor.apps.aiocr.testsupport.UnitDefaultsForTests.none();
    }

    private RecordingCreditType creditType;
    private int syncCount;
    private int computeCount;

    @Override
    protected OcrInvoiceTaxSummaryService.Result syncTaxSummary(Invoice invoice) {
      syncCount++;
      return null; // the summary is already on the invoice, exactly as persisted
    }

    @Override
    protected String applyCreditType(InvoiceLine roundingLine, List<InvoiceLine> bucketLines) {
      if (creditType == null) {
        return null;
      }
      creditType.roundingLine = roundingLine;
      creditType.bucketLines = new ArrayList<>(bucketLines);
      return creditType.refusal;
    }

    /**
     * {@code InvoiceGenerator.initInvoiceLineTaxList} + {@code TaxInvoiceLine} + {@code
     * InvoiceTaxComputeService}: keep a manageByAmount row whose amount was overridden, regenerate
     * everything else per rate, then re-derive the header.
     */
    @Override
    protected void recompute(Invoice invoice) {
      computeCount++;
      List<InvoiceLineTax> kept = new ArrayList<>();
      if (invoice.getInvoiceLineTaxList() != null) {
        for (InvoiceLineTax row : invoice.getInvoiceLineTaxList()) {
          boolean manageByAmount =
              row.getTaxLine() != null
                  && row.getTaxLine().getTax() != null
                  && Boolean.TRUE.equals(row.getTaxLine().getTax().getManageByAmount());
          if (manageByAmount && row.getTaxTotal().compareTo(row.getPercentageTaxTotal()) != 0) {
            kept.add(row);
          }
        }
      }

      Map<BigDecimal, BigDecimal> baseByRate = new LinkedHashMap<>();
      BigDecimal net = BigDecimal.ZERO;
      for (InvoiceLine line : invoice.getInvoiceLineList()) {
        BigDecimal lineNet = line.getExTaxTotal() == null ? BigDecimal.ZERO : line.getExTaxTotal();
        net = net.add(lineNet);
        if (line.getTaxLineSet() == null) {
          continue;
        }
        for (TaxLine taxLine : line.getTaxLineSet()) {
          baseByRate.merge(taxLine.getValue(), lineNet, BigDecimal::add);
        }
      }

      List<InvoiceLineTax> rows = new ArrayList<>();
      BigDecimal vat = BigDecimal.ZERO;
      for (Map.Entry<BigDecimal, BigDecimal> entry : baseByRate.entrySet()) {
        BigDecimal base = entry.getValue();
        BigDecimal derived =
            base.multiply(entry.getKey()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        InvoiceLineTax reused = null;
        for (InvoiceLineTax row : kept) {
          if (row.getTaxLine().getValue().compareTo(entry.getKey()) == 0
              && row.getPercentageTaxTotal().compareTo(derived) == 0
              && row.getExTaxBase().compareTo(base) == 0) {
            reused = row;
            break;
          }
        }
        InvoiceLineTax row = reused;
        if (row == null) {
          row = new InvoiceLineTax();
          row.setTaxLine(
              entry.getKey().signum() == 0 ? zeroRateTaxLine() : manageByAmountTaxLine());
          row.setExTaxBase(base);
          row.setCompanyExTaxBase(base);
          row.setPercentageTaxTotal(derived);
          row.setTaxTotal(derived);
          row.setCompanyTaxTotal(derived);
        }
        row.setInTaxTotal(row.getExTaxBase().add(row.getTaxTotal()));
        rows.add(row);
        vat = vat.add(row.getTaxTotal());
      }

      invoice.setInvoiceLineTaxList(rows);
      invoice.setExTaxTotal(net.setScale(2, RoundingMode.HALF_UP));
      invoice.setTaxTotal(vat);
      invoice.setInTaxTotal(net.add(vat).setScale(2, RoundingMode.HALF_UP));
      invoice.setCompanyExTaxTotal(invoice.getExTaxTotal());
      invoice.setCompanyTaxTotal(vat);
      invoice.setCompanyInTaxTotal(invoice.getInTaxTotal());
    }

    @Override
    protected Account resolveRoundingAccount(Invoice invoice) {
      Account account = new Account();
      account.setCode("60909"); // the company's Cashier Debit Regulation account
      account.setStatusSelect(AccountRepository.STATUS_ACTIVE);
      return account;
    }

    @Override
    protected TaxLine resolveZeroRateTaxLine(Invoice invoice) {
      return zeroRateTaxLine(); // only the legacy, gross-only path reaches this
    }

    @Override
    protected BigDecimal toCompanyAmount(Invoice invoice, BigDecimal amount) {
      return amount;
    }
  }
}

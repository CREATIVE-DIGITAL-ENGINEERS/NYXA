package com.axelor.apps.aiocr.service.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.AccountType;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoiceLineTax;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.account.db.repo.AccountTypeRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiocr.service.ocr.BgOcrVatMapping;
import com.axelor.apps.base.AxelorException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The 0.01/0.02 correction line: that it is measured against the GROSS, that every line's own tax
 * contributes to that gross, that it is idempotent, and the things it refuses to do.
 *
 * <p>The service's contacts with the runtime are {@code protected} seams, so the rules run with no
 * Guice and no JPA. The important one is {@link TestService#recompute}, which is not a stub: it
 * reproduces what {@code TaxInvoiceLine} + {@code InvoiceGenerator.computeInvoice} + {@code
 * InvoiceTaxComputeService} actually do — group the lines' bases per RATE, round each rate's VAT
 * once, then {@code inTaxTotal = exTaxTotal + Σ row.taxTotal}. Without that shape the tests could
 * not tell a gross from a net, which is exactly the failure below.
 */
class OcrRoundingLineServiceTest {

  /** The service with its runtime contacts replaced; {@code account} null = company has no 609. */
  private static class TestService extends OcrRoundingLineService {

    /** Тестът твърди сметки, не единици: без инсталирана локализация правилото мълчи. */
    @Override
    protected com.axelor.apps.aiaccounting.service.invoice.InvoiceLineUnitDefaultService
        unitDefaults() {
      return com.axelor.apps.aiocr.testsupport.UnitDefaultsForTests.none();
    }

    private final Account account;

    TestService(Account account) {
      this.account = account;
    }

    /** The OCR seam is exercised through {@link #recompute}; nothing to rebuild separately. */
    @Override
    protected OcrInvoiceTaxSummaryService.Result syncTaxSummary(Invoice invoice) {
      return null;
    }

    /** Axelor's per-rate aggregation, reproduced: every line, every line's own rate. */
    @Override
    protected void recompute(Invoice invoice) {
      Map<BigDecimal, BigDecimal> baseByRate = new LinkedHashMap<>();
      BigDecimal net = BigDecimal.ZERO;
      for (InvoiceLine line : invoice.getInvoiceLineList()) {
        BigDecimal lineNet = line.getExTaxTotal() == null ? BigDecimal.ZERO : line.getExTaxTotal();
        net = net.add(lineNet);
        Set<TaxLine> taxLineSet = line.getTaxLineSet();
        if (taxLineSet == null) {
          continue;
        }
        for (TaxLine taxLine : taxLineSet) {
          baseByRate.merge(taxLine.getValue(), lineNet, BigDecimal::add);
        }
      }
      BigDecimal vat = BigDecimal.ZERO;
      java.util.List<InvoiceLineTax> rows = new ArrayList<>();
      for (Map.Entry<BigDecimal, BigDecimal> entry : baseByRate.entrySet()) {
        BigDecimal rowTax =
            entry
                .getValue()
                .multiply(entry.getKey())
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        InvoiceLineTax row = new InvoiceLineTax();
        row.setExTaxBase(entry.getValue());
        row.setTaxTotal(rowTax);
        row.setTaxLine(taxLineAt(entry.getKey()));
        rows.add(row);
        vat = vat.add(rowTax);
      }
      invoice.setInvoiceLineTaxList(rows);
      invoice.setExTaxTotal(net.setScale(2, RoundingMode.HALF_UP));
      invoice.setTaxTotal(vat);
      invoice.setInTaxTotal(net.add(vat).setScale(2, RoundingMode.HALF_UP));
    }

    @Override
    protected Account resolveRoundingAccount(Invoice invoice) {
      return account;
    }

    @Override
    protected TaxLine resolveZeroRateTaxLine(Invoice invoice) {
      return taxLineAt(BigDecimal.ZERO);
    }

    @Override
    protected BigDecimal toCompanyAmount(Invoice invoice, BigDecimal amount) {
      return amount; // same currency, the usual case
    }
  }

  /**
   * The failure being guarded: compute leaves the per-rate summary empty, so {@code inTaxTotal}
   * collapses to the NET and the printed gross is compared against it.
   */
  private static class NoVatSummaryService extends TestService {

    NoVatSummaryService() {
      super(account609());
    }

    @Override
    protected void recompute(Invoice invoice) {
      BigDecimal net = BigDecimal.ZERO;
      for (InvoiceLine line : invoice.getInvoiceLineList()) {
        net = net.add(line.getExTaxTotal() == null ? BigDecimal.ZERO : line.getExTaxTotal());
      }
      invoice.setInvoiceLineTaxList(new ArrayList<>());
      invoice.setExTaxTotal(net);
      invoice.setTaxTotal(BigDecimal.ZERO);
      invoice.setInTaxTotal(net); // ← gross == net, the faulty figure
    }
  }

  private static TaxLine taxLineAt(BigDecimal ratePercent) {
    Tax tax = new Tax();
    tax.setCode(
        ratePercent.signum() == 0 ? BgOcrVatMapping.BG_ZERO : "BG_RATE_" + ratePercent.intValue());
    TaxLine taxLine = new TaxLine();
    taxLine.setTax(tax);
    taxLine.setValue(ratePercent);
    return taxLine;
  }

  /** The company's Cashier Debit Regulation account, as BG_COA sets it: 60909, an expense. */
  private static Account account609() {
    Account account = new Account();
    account.setCode("60909");
    account.setName("Други разходи");
    AccountType expense = new AccountType();
    expense.setTechnicalTypeSelect(AccountTypeRepository.TYPE_CHARGE);
    account.setAccountType(expense);
    account.setStatusSelect(AccountRepository.STATUS_ACTIVE);
    return account;
  }

  /** The group account 609 «Други разходи» of the Bulgarian chart — no line may be booked on it. */
  private static Account groupAccount609() {
    Account account = new Account();
    account.setCode("609");
    AccountType view = new AccountType();
    view.setTechnicalTypeSelect(AccountTypeRepository.TYPE_VIEW);
    account.setAccountType(view);
    return account;
  }

  /** A draft supplier invoice carrying the gross its document printed. */
  private static Invoice supplierInvoice(String documentTotal) {
    Invoice invoice = new Invoice();
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    invoice.setStatusSelect(InvoiceRepository.STATUS_DRAFT);
    invoice.setOcrDocumentTotal(new BigDecimal(documentTotal));
    invoice.setInvoiceLineList(new ArrayList<>());
    invoice.setInvoiceLineTaxList(new ArrayList<>());
    return invoice;
  }

  private static void addLine(Invoice invoice, String net, String ratePercent) {
    InvoiceLine line = new InvoiceLine();
    line.setExTaxTotal(new BigDecimal(net));
    line.setCompanyExTaxTotal(new BigDecimal(net));
    Set<TaxLine> taxLineSet = new HashSet<>();
    taxLineSet.add(taxLineAt(new BigDecimal(ratePercent)));
    line.setTaxLineSet(taxLineSet);
    invoice.addInvoiceLineListItem(line);
  }

  /** The reference invoice: 146.56 net at 20% → 29.31 VAT → 175.87 gross. */
  private static Invoice referenceInvoice(String documentTotal) {
    Invoice invoice = supplierInvoice(documentTotal);
    addLine(invoice, "146.56", "20");
    invoice.setTaxTotal(new BigDecimal("29.31"));
    invoice.setInTaxTotal(new BigDecimal("175.87"));
    return invoice;
  }

  private static InvoiceLine adjustmentLineOf(Invoice invoice) {
    InvoiceLine found = null;
    for (InvoiceLine line : invoice.getInvoiceLineList()) {
      if (Boolean.TRUE.equals(line.getOcrRoundingAdjustment())) {
        assertTrue(found == null, "more than one automatic rounding line");
        found = line;
      }
    }
    return found;
  }

  // ─────────────────────────────────────────────────────────────── the reported bug

  @Test
  void theRegressionCase_146_56_net_plus_29_31_vat_against_a_printed_175_88() throws Exception {
    // Reported behaviour: "calculated total 146.56, difference 29.32" — the printed GROSS compared
    // against the NET because the VAT had not been computed yet.
    Invoice invoice = referenceInvoice("175.88");

    new TestService(account609()).reconcileBeforeValidation(invoice);

    InvoiceLine correction = adjustmentLineOf(invoice);
    assertNotNull(correction, "a one-stotinka rounding difference must be corrected, not refused");
    assertEquals(new BigDecimal("0.01"), correction.getExTaxTotal());
    assertEquals(
        new BigDecimal("175.88"), invoice.getInTaxTotal(), "gross now equals the document");
    assertEquals(new BigDecimal("29.31"), invoice.getTaxTotal(), "the VAT is untouched");
    assertEquals(new BigDecimal("146.57"), invoice.getExTaxTotal(), "net carries the correction");
  }

  @Test
  void theRealLifecycle_summaryEmptyOnTheDraft_generatedDuringValidation() throws Exception {
    // Invoice #12's shape as the database actually holds an OCR draft: the persisted header still
    // carries what the intake wrote, and invoiceLineTaxList is EMPTY — the per-rate summary is
    // generated by the computation validation runs, not before it. The reconciliation must
    // therefore measure the gross AFTER that generation, never the stored header.
    Invoice invoice = supplierInvoice("175.88");
    addLine(invoice, "146.56", "20");
    invoice.setInvoiceLineTaxList(new ArrayList<>()); // nothing persisted yet
    invoice.setExTaxTotal(new BigDecimal("146.56"));
    invoice.setTaxTotal(BigDecimal.ZERO); // no VAT stored on the draft
    invoice.setInTaxTotal(new BigDecimal("146.56")); // stored gross == stored net

    new TestService(account609()).reconcileBeforeValidation(invoice);

    // VAT 29.31 generated during the computation → gross 175.87 → delta 0.01, never 29.32.
    assertEquals(new BigDecimal("29.31"), invoice.getTaxTotal(), "VAT generated during validation");
    assertEquals(new BigDecimal("0.01"), adjustmentLineOf(invoice).getExTaxTotal());
    assertEquals(new BigDecimal("175.88"), invoice.getInTaxTotal());
  }

  @Test
  void zeroRatedLinesAreNamedAsMissingVatRatherThanAsARoundingDifference() {
    // Invoice #12 exactly as it stands: ten lines summing to 146.56, every one of them on the
    // zero-rate BG_ZRP, against a printed 175.88. The refusal is correct, but it must say WHY.
    Invoice invoice = supplierInvoice("175.88");
    addLine(invoice, "146.56", "0");

    AxelorException raised =
        assertThrows(
            AxelorException.class,
            () -> new TestService(account609()).reconcileBeforeValidation(invoice));

    assertTrue(raised.getMessage().contains("no VAT"), raised.getMessage());
    assertTrue(raised.getMessage().contains(BgOcrVatMapping.BG_ZERO), raised.getMessage());
    assertTrue(
        raised.getMessage().contains("not a rounding difference"),
        "the missing VAT must not read as a rounding failure: " + raised.getMessage());
    assertNull(adjustmentLineOf(invoice), "a missing tax rate is never plugged with a 609 line");
    assertEquals(OcrDocumentRefusal.Kind.NO_VAT, kindOf(raised), "the user is told to set the tax");
  }

  @Test
  void vatFromEveryLineTaxIsIncluded_twoDifferentRates() throws Exception {
    // 100.00 @ 20% = 20.00 and 50.00 @ 9% = 4.50 → net 150.00, VAT 24.50, gross 174.50.
    // If only one rate were counted the delta would be 4.51 or 20.01 and this would be refused.
    Invoice invoice = supplierInvoice("174.51");
    addLine(invoice, "100.00", "20");
    addLine(invoice, "50.00", "9");

    new TestService(account609()).reconcileBeforeValidation(invoice);

    assertEquals(new BigDecimal("0.01"), adjustmentLineOf(invoice).getExTaxTotal());
    assertEquals(new BigDecimal("24.50"), invoice.getTaxTotal(), "both rates contributed");
    assertEquals(new BigDecimal("174.51"), invoice.getInTaxTotal());
  }

  @Test
  void anEmptyVatSummaryIsRefusedInsteadOfBeingComparedAgainstTheNet() {
    Invoice invoice = referenceInvoice("175.88");

    AxelorException raised =
        assertThrows(
            AxelorException.class,
            () -> new NoVatSummaryService().reconcileBeforeValidation(invoice));

    assertTrue(raised.getMessage().contains("VAT"), raised.getMessage());
    assertTrue(
        !raised.getMessage().contains("29.32"),
        "the whole VAT must never be reported as a rounding difference: " + raised.getMessage());
    assertNull(adjustmentLineOf(invoice), "nothing may be booked on a broken computation");
    assertEquals(OcrDocumentRefusal.Kind.NO_VAT, kindOf(raised));
  }

  // ─────────────────────────────────────────────────────────────── the four directions

  @Test
  void aCentShortIsBookedToSixZeroNineWithoutVat() throws Exception {
    Invoice invoice = referenceInvoice("175.88");

    new TestService(account609()).reconcileBeforeValidation(invoice);

    InvoiceLine correction = adjustmentLineOf(invoice);
    assertEquals(new BigDecimal("0.01"), correction.getExTaxTotal());
    assertEquals(
        new BigDecimal("0.01"), correction.getInTaxTotal(), "the correction carries no VAT");
    assertEquals(BigDecimal.ZERO, correction.getTaxRate());
    assertEquals(BgOcrVatMapping.BG_ZERO, correction.getTaxCode());
    assertEquals("60909", correction.getAccount().getCode());
  }

  @Test
  void twoCentsShortIsBookedToo() throws Exception {
    Invoice invoice = referenceInvoice("175.89");

    new TestService(account609()).reconcileBeforeValidation(invoice);

    assertEquals(new BigDecimal("0.02"), adjustmentLineOf(invoice).getExTaxTotal());
    assertEquals(new BigDecimal("175.89"), invoice.getInTaxTotal());
  }

  @Test
  void axelorBeingHigherProducesANegativeCorrection() throws Exception {
    Invoice invoice = referenceInvoice("175.86");

    new TestService(account609()).reconcileBeforeValidation(invoice);

    assertEquals(new BigDecimal("-0.01"), adjustmentLineOf(invoice).getExTaxTotal());
    assertEquals(new BigDecimal("175.86"), invoice.getInTaxTotal());
  }

  @Test
  void axelorBeingTwoCentsHigherIsCorrectedTheSameWay() throws Exception {
    Invoice invoice = referenceInvoice("175.85");

    new TestService(account609()).reconcileBeforeValidation(invoice);

    assertEquals(new BigDecimal("-0.02"), adjustmentLineOf(invoice).getExTaxTotal());
    assertEquals(new BigDecimal("175.85"), invoice.getInTaxTotal());
  }

  // ─────────────────────────────────────────────────────────────── idempotence & refusals

  @Test
  void aSecondPassNeitherDuplicatesNorDoublesTheCorrection() throws Exception {
    Invoice invoice = referenceInvoice("175.88");
    OcrRoundingLineService service = new TestService(account609());

    service.reconcileBeforeValidation(invoice);
    service.reconcileBeforeValidation(invoice);

    assertEquals(2, invoice.getInvoiceLineList().size(), "one product line + one correction");
    assertEquals(new BigDecimal("0.01"), adjustmentLineOf(invoice).getExTaxTotal());
    assertEquals(new BigDecimal("175.88"), invoice.getInTaxTotal());
  }

  @Test
  void aDifferenceLargerThanTwoCentsRefusesValidationAndWritesNothing() {
    Invoice invoice = referenceInvoice("175.84"); // 0.03 away from the calculated 175.87

    AxelorException raised =
        assertThrows(
            AxelorException.class,
            () -> new TestService(account609()).reconcileBeforeValidation(invoice));

    assertTrue(raised.getMessage().contains("175.84"), raised.getMessage());
    assertTrue(raised.getMessage().contains("175.87"), raised.getMessage());
    assertTrue(raised.getMessage().contains("0.03"), raised.getMessage());
    assertNull(adjustmentLineOf(invoice), "nothing may be booked for a real mismatch");
    assertEquals(OcrDocumentRefusal.Kind.MISMATCH, kindOf(raised));
    assertEquals(
        InvoiceRepository.STATUS_DRAFT, invoice.getStatusSelect().intValue(), "stays a draft");
  }

  @Test
  void aMissingSixZeroNineRefusesInsteadOfGuessingAnAccount() {
    Invoice invoice = referenceInvoice("175.88");

    AxelorException raised =
        assertThrows(
            AxelorException.class, () -> new TestService(null).reconcileBeforeValidation(invoice));

    assertTrue(
        raised.getMessage().contains("Cashier Debit Regulation account"), raised.getMessage());
    assertNull(adjustmentLineOf(invoice), "no line without an account to book it to");
    assertEquals(OcrDocumentRefusal.Kind.ROUNDING_ACCOUNT_MISSING, kindOf(raised));
  }

  @Test
  void aGroupRoundingAccountRefusesInsteadOfBookingALineOnIt() {
    Invoice invoice = referenceInvoice("175.88");

    AxelorException raised =
        assertThrows(
            AxelorException.class,
            () -> new TestService(groupAccount609()).reconcileBeforeValidation(invoice));

    assertTrue(raised.getMessage().contains("group (view) account"), raised.getMessage());
    assertNull(adjustmentLineOf(invoice), "no line on a group account");
    assertEquals(OcrDocumentRefusal.Kind.ROUNDING_ACCOUNT_UNUSABLE, kindOf(raised));
  }

  @Test
  void anInactiveRoundingAccountRefusesToo() {
    Invoice invoice = referenceInvoice("175.88");
    Account inactive = account609();
    inactive.setStatusSelect(AccountRepository.STATUS_INACTIVE);

    AxelorException raised =
        assertThrows(
            AxelorException.class,
            () -> new TestService(inactive).reconcileBeforeValidation(invoice));

    assertTrue(raised.getMessage().contains("inactive"), raised.getMessage());
    assertNull(adjustmentLineOf(invoice));
    assertEquals(OcrDocumentRefusal.Kind.ROUNDING_ACCOUNT_UNUSABLE, kindOf(raised));
  }

  @Test
  void theRoundingAccountIsTheCompanysDebitRegulationAccountForBothSigns() {
    AccountConfig config = new AccountConfig();
    Account debit = account609();
    Account credit = new Account();
    credit.setCode("709");
    config.setCashPositionVariationDebitAccount(debit);
    config.setCashPositionVariationCreditAccount(credit);
    assertSame(debit, OcrRoundingLineService.roundingAccountOf(config));
    assertNull(OcrRoundingLineService.roundingAccountOf(new AccountConfig()));
    assertNull(OcrRoundingLineService.roundingAccountOf(null));
  }

  @Test
  void onlyAPostingActiveAccountCanCarryTheCorrection() {
    assertNull(OcrRoundingLineService.roundingAccountProblem(account609()));
    assertTrue(OcrRoundingLineService.roundingAccountProblem(null).contains("no rounding account"));
    assertTrue(
        OcrRoundingLineService.roundingAccountProblem(groupAccount609())
            .contains("609 is a group"));
    Account untyped = new Account();
    untyped.setCode("60909");
    untyped.setStatusSelect(AccountRepository.STATUS_ACTIVE);
    assertNull(
        OcrRoundingLineService.roundingAccountProblem(untyped),
        "an active account with no type is left to Axelor's own checks, not refused here");
    Account fresh = new Account();
    fresh.setCode("60909");
    assertTrue(
        OcrRoundingLineService.roundingAccountProblem(fresh).contains("inactive"),
        "status 0 — the domain default — is inactive, exactly as Axelor's posting reads it");
  }

  /**
   * The kind decides the message the user reads at Validate (OcrInvoiceServiceImpl shows {@code
   * userMessage}, not this English diagnosis), so a refusal must always carry one.
   */
  private static OcrDocumentRefusal.Kind kindOf(AxelorException raised) {
    assertTrue(
        raised instanceof OcrDocumentRefusal,
        "a refusal of the rounding service is an OcrDocumentRefusal: " + raised.getClass());
    return ((OcrDocumentRefusal) raised).getKind();
  }

  @Test
  void aCustomerInvoiceIsNeverTouched() throws Exception {
    Invoice invoice = referenceInvoice("175.84");
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_CLIENT_SALE);

    new TestService(account609()).reconcileBeforeValidation(invoice);

    assertNull(adjustmentLineOf(invoice));
  }

  @Test
  void anInvoiceWithNoDocumentTotalIsNeitherCorrectedNorBlocked() throws Exception {
    Invoice invoice = referenceInvoice("175.00");
    invoice.setOcrDocumentTotal(null); // keyed by hand: no document, no trusted total

    new TestService(account609()).reconcileBeforeValidation(invoice);

    assertNull(adjustmentLineOf(invoice));
  }

  @Test
  void ventilationIsRefusedForAMismatchButNotForACorrectedInvoice() throws Exception {
    OcrRoundingLineService service = new TestService(account609());

    Invoice corrected = referenceInvoice("175.87");
    corrected.setStatusSelect(InvoiceRepository.STATUS_VALIDATED);
    service.checkBeforeVentilation(corrected); // must not throw

    Invoice broken = referenceInvoice("170.00");
    broken.setStatusSelect(InvoiceRepository.STATUS_VALIDATED);
    assertThrows(AxelorException.class, () -> service.checkBeforeVentilation(broken));
  }
}

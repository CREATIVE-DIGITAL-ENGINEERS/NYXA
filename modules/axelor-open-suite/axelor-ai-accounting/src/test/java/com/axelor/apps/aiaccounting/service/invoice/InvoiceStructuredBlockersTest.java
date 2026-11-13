package com.axelor.apps.aiaccounting.service.invoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoiceLineTax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Field-addressable findings — never a source of new blocking rules, never able to write anything.
 *
 * <p>Pure — no Guice, no JPA. Every test goes through the explicit {@link
 * InvoiceStructuredBlockers#build(Invoice, AiInvoiceFieldRegistry)} seam with a bare {@code new
 * AiInvoiceFieldRegistry()} (which needs no Guice injection itself; only {@code Beans.get(Class)}
 * does), never the single-argument {@link InvoiceStructuredBlockers#build(Invoice)} overload — that
 * one calls {@code Beans.get} directly and would throw outside an injector, which is the point: a
 * genuine wiring failure in production must surface, not be read as "nothing to report".
 */
class InvoiceStructuredBlockersTest {

  private final AiInvoiceFieldRegistry registry = new AiInvoiceFieldRegistry();

  private InvoiceLine line(BigDecimal net, Account account) {
    InvoiceLine line = new InvoiceLine();
    line.setProductName("Консултантска услуга");
    line.setExTaxTotal(net);
    line.setInTaxTotal(net);
    line.setAccount(account);
    return line;
  }

  private Account account(String code) {
    Account account = new Account();
    account.setCode(code);
    return account;
  }

  /** A line with a tax attached — the only kind {@code taxSummaryBlocker} may compare. */
  private InvoiceLine taxedLine(BigDecimal net, Account account) {
    InvoiceLine line = line(net, account);
    Set<TaxLine> taxLineSet = new HashSet<>();
    taxLineSet.add(new TaxLine());
    line.setTaxLineSet(taxLineSet);
    return line;
  }

  @Test
  void aLineWithNoAccountIsAFieldAddressableBlocker() {
    Invoice invoice = new Invoice();
    List<InvoiceLine> lines = new ArrayList<>();
    lines.add(line(new BigDecimal("10.00"), null));
    invoice.setInvoiceLineList(lines);

    List<Map<String, Object>> blockers = InvoiceStructuredBlockers.build(invoice, registry);

    assertTrue(
        blockers.stream()
            .anyMatch(
                b ->
                    "lines[0].account".equals(b.get("field"))
                        && "missing".equals(b.get("code"))
                        && "blocker".equals(b.get("severity"))),
        blockers.toString());
  }

  @Test
  void theTaxSummaryIsNeverWritableAndALargerMismatchIsStillReported() {
    // The tax summary itself has no setter anywhere in this package — InvoiceLineEditService and
    // OcrInvoiceLineEditService both only ever write exTaxTotal/qty/price on a LINE, never a
    // InvoiceLineTax row directly. What this proves is the other half: once the summary and the
    // TAXED lines disagree by more than one rounding step per taxed line, it is reported (as a
    // warning — Axelor's own compute/sync self-heals this on every write, so it is not an actual
    // validate/ventilate gate the way a missing account is), not silently ignored.
    Invoice invoice = new Invoice();
    List<InvoiceLine> lines = new ArrayList<>();
    lines.add(taxedLine(new BigDecimal("100.00"), account("601")));
    invoice.setInvoiceLineList(lines);

    InvoiceLineTax row = new InvoiceLineTax();
    row.setExTaxBase(new BigDecimal("70.00")); // stale: the line above says 100.00
    invoice.setInvoiceLineTaxList(new ArrayList<>(List.of(row)));

    List<Map<String, Object>> blockers = InvoiceStructuredBlockers.build(invoice, registry);

    assertTrue(
        blockers.stream()
            .anyMatch(
                b ->
                    "invoice.taxSummary".equals(b.get("field"))
                        && "inconsistent".equals(b.get("code"))
                        && "warning".equals(b.get("severity"))),
        blockers.toString());
  }

  @Test
  void aOneCentRoundingGapBetweenTheSummaryAndTheLinesIsNotFlagged() {
    Invoice invoice = new Invoice();
    List<InvoiceLine> lines = new ArrayList<>();
    lines.add(taxedLine(new BigDecimal("100.00"), account("601")));
    invoice.setInvoiceLineList(lines);

    InvoiceLineTax row = new InvoiceLineTax();
    row.setExTaxBase(new BigDecimal("99.99")); // one rounding step for one line — not a mismatch
    invoice.setInvoiceLineTaxList(new ArrayList<>(List.of(row)));

    List<Map<String, Object>> blockers = InvoiceStructuredBlockers.build(invoice, registry);

    assertFalse(
        blockers.stream().anyMatch(b -> "invoice.taxSummary".equals(b.get("field"))),
        blockers.toString());
  }

  @Test
  void anExemptLineAlongsideATaxedOneIsNotFalselyFlaggedAsInconsistent() {
    // A perfectly valid invoice with one taxed line (100.00, VAT) and one exempt line (50.00, no
    // tax at all) must NOT be flagged: the exempt line's net is not part of the comparison. It
    // produces no InvoiceLineTax row — there is nothing for it to reconcile against — so only the
    // taxed line's net may be compared to the tax summary's base.
    Invoice invoice = new Invoice();
    List<InvoiceLine> lines = new ArrayList<>();
    lines.add(taxedLine(new BigDecimal("100.00"), account("601")));
    lines.add(line(new BigDecimal("50.00"), account("603"))); // exempt: no taxLineSet at all
    invoice.setInvoiceLineList(lines);

    InvoiceLineTax row = new InvoiceLineTax();
    row.setExTaxBase(
        new BigDecimal(
            "100.00")); // exactly the taxed line's net, correctly excludes the exempt one
    invoice.setInvoiceLineTaxList(new ArrayList<>(List.of(row)));

    List<Map<String, Object>> blockers = InvoiceStructuredBlockers.build(invoice, registry);

    assertFalse(
        blockers.stream().anyMatch(b -> "invoice.taxSummary".equals(b.get("field"))),
        blockers.toString());
    // The exempt line still gets its own (non-blocking) tax-missing warning — that check is
    // per-line and unrelated to the header-level tax-summary comparison this test is about.
    assertTrue(
        blockers.stream()
            .anyMatch(
                b -> "lines[1].tax".equals(b.get("field")) && "warning".equals(b.get("severity"))),
        blockers.toString());
  }

  @Test
  void anInvoiceWithNoTaxedLinesAtAllIsNeverFlaggedForATaxSummaryItShouldNotHave() {
    Invoice invoice = new Invoice();
    List<InvoiceLine> lines = new ArrayList<>();
    lines.add(line(new BigDecimal("50.00"), account("603"))); // exempt only
    invoice.setInvoiceLineList(lines);
    // No tax rows at all — correct for an all-exempt invoice.
    invoice.setInvoiceLineTaxList(new ArrayList<>());

    List<Map<String, Object>> blockers = InvoiceStructuredBlockers.build(invoice, registry);

    assertFalse(blockers.stream().anyMatch(b -> "invoice.taxSummary".equals(b.get("field"))));
  }

  @Test
  void aFullyPopulatedPurchaseLineProducesOnlyTheVatCreditInfo() {
    Invoice invoice = new Invoice();
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    InvoiceLine line = taxedLine(new BigDecimal("50.00"), account("602"));
    com.axelor.apps.base.db.Unit unit = new com.axelor.apps.base.db.Unit();
    unit.setName("бр.");
    line.setUnit(unit);
    invoice.setInvoiceLineList(new ArrayList<>(List.of(line)));

    List<Map<String, Object>> blockers = InvoiceStructuredBlockers.build(invoice, registry);

    // The registry is real here (just empty of contributors on this module's own classpath), so on
    // a
    // PURCHASE the vatCreditType check DOES run and DOES fire — bgVatCreditType is genuinely unset.
    assertEquals(1, blockers.size(), blockers.toString());
    assertEquals("lines[0].vatCreditType", blockers.get(0).get("field"));
    assertEquals("info", blockers.get(0).get("severity"));
  }

  @Test
  void aSaleInvoiceNeverGetsThePurchaseOnlyVatCreditFinding() {
    // данъчен кредит (VAT-credit right) is a buyer's concept with no column on a sales invoice. The
    // finding that fires for a supplier line must not be copied onto a customer invoice — this is
    // the
    // leak get_customer_invoice would otherwise have shown once it started returning
    // structuredBlockers.
    Invoice invoice = new Invoice();
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_CLIENT_SALE);
    InvoiceLine line = taxedLine(new BigDecimal("50.00"), account("702"));
    com.axelor.apps.base.db.Unit unit = new com.axelor.apps.base.db.Unit();
    unit.setName("бр.");
    line.setUnit(unit);
    invoice.setInvoiceLineList(new ArrayList<>(List.of(line)));

    List<Map<String, Object>> blockers = InvoiceStructuredBlockers.build(invoice, registry);

    assertTrue(blockers.isEmpty(), blockers.toString());
  }

  @Test
  void aSaleInvoiceStillReportsTheSideNeutralAccountBlocker() {
    // The side-neutral findings still work for a customer invoice: a missing account is a real
    // validate blocker on a sale just as on a purchase, so get_customer_invoice gains the same
    // field-addressable answer the supplier side already had.
    Invoice invoice = new Invoice();
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_CLIENT_SALE);
    List<InvoiceLine> lines = new ArrayList<>();
    lines.add(line(new BigDecimal("10.00"), null)); // no account
    invoice.setInvoiceLineList(lines);

    List<Map<String, Object>> blockers = InvoiceStructuredBlockers.build(invoice, registry);

    assertTrue(
        blockers.stream()
            .anyMatch(
                b ->
                    "lines[0].account".equals(b.get("field"))
                        && "blocker".equals(b.get("severity"))),
        blockers.toString());
    assertFalse(
        blockers.stream().anyMatch(b -> "lines[0].vatCreditType".equals(b.get("field"))),
        blockers.toString());
  }
}

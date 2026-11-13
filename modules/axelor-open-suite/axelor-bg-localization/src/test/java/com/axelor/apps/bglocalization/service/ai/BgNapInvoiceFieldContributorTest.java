package com.axelor.apps.bglocalization.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldChange;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldSpec;
import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * The one safe default among the NAP fields: {@code napDocumentTypeSelect} defaults to "01 –
 * Фактура" on a brand-new, blank SUPPLIER PURCHASE invoice — never on a refund/credit note, never
 * on a customer document, and never over an explicit value. Pure — no Guice, no JPA.
 */
class BgNapInvoiceFieldContributorTest {

  private final BgNapInvoiceFieldContributor contributor = new BgNapInvoiceFieldContributor();

  @Test
  void blankNapDocumentTypeDefaultsToInvoiceOnASupplierPurchase() {
    Invoice invoice = new Invoice();
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);

    contributor.applyCreationDefaults(invoice);

    assertEquals("01", invoice.getNapDocumentTypeSelect());
  }

  @Test
  void anExplicitValueIsNeverOverwritten() {
    Invoice invoice = new Invoice();
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    invoice.setNapDocumentTypeSelect("03"); // Кредитно известие, set by whoever built this invoice

    contributor.applyCreationDefaults(invoice);

    assertEquals("03", invoice.getNapDocumentTypeSelect(), "an explicit value must survive");
  }

  @Test
  void aCustomerInvoiceIsNeverDefaulted() {
    Invoice invoice = new Invoice();
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_CLIENT_SALE);

    contributor.applyCreationDefaults(invoice);

    assertNull(invoice.getNapDocumentTypeSelect(), "a customer document is not a Фактура to NAP");
  }

  @Test
  void aSupplierRefundIsNeverDefaultedEither() {
    // A credit note against a supplier invoice is legally "03 – Кредитно известие", never
    // "01 – Фактура" — this is exactly the case the task warned against guessing.
    Invoice invoice = new Invoice();
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND);

    contributor.applyCreationDefaults(invoice);

    assertNull(invoice.getNapDocumentTypeSelect());
  }

  /**
   * The gap: «дата на данъчното събитие» lives in {@code Invoice.bgVatTaxEventDate}, which the VAT
   * diary reads, and no AI tool could declare, write or read it — so a date the agent read off the
   * document had nowhere to go. Declared for supplier invoices, written as the date given, read
   * back, and never derived from the invoice or due date.
   */
  @Test
  void theTaxEventDateIsDeclaredWrittenAndReadBackWithoutTouchingOtherDates() {
    assertTrue(
        contributor.headerFields().stream()
            .anyMatch(
                spec ->
                    "bgVatTaxEventDate".equals(spec.getName())
                        && spec.getApplies() == AiInvoiceFieldSpec.Applies.SUPPLIER_ONLY));
    Invoice invoice = new Invoice();
    invoice.setInvoiceDate(LocalDate.of(2026, 10, 1));
    invoice.setOriginDate(LocalDate.of(2026, 10, 1));

    AiInvoiceFieldChange change =
        contributor.applyHeaderField(invoice, "bgVatTaxEventDate", TextNode.valueOf("2026-09-28"));

    assertEquals(LocalDate.of(2026, 9, 28), invoice.getBgVatTaxEventDate());
    assertNull(change.getOldValue());
    assertEquals("2026-09-28", change.getNewValue());
    assertEquals(LocalDate.of(2026, 10, 1), invoice.getInvoiceDate());
    assertEquals(LocalDate.of(2026, 10, 1), invoice.getOriginDate());
    assertEquals("2026-09-28", contributor.readHeaderFields(invoice).get("bgVatTaxEventDate"));
  }

  @Test
  void aTaxEventDateThatIsNotAnIsoDateIsRefusedAndNullClearsIt() {
    Invoice invoice = new Invoice();
    invoice.setBgVatTaxEventDate(LocalDate.of(2026, 9, 28));

    assertThrows(
        IllegalArgumentException.class,
        () ->
            contributor.applyHeaderField(
                invoice, "bgVatTaxEventDate", TextNode.valueOf("28.09.2026")));
    assertEquals(LocalDate.of(2026, 9, 28), invoice.getBgVatTaxEventDate());

    contributor.applyHeaderField(invoice, "bgVatTaxEventDate", NullNode.getInstance());
    assertNull(invoice.getBgVatTaxEventDate());
  }

  @Test
  void everyLineFieldDeclaredClearableReallyIsEmptiedByNull() {
    // The tool schemas widen a clearable field to accept null; that promise is only true if the
    // apply code empties the field on null instead of refusing it or leaving it as it was.
    BgIncomeType income = new BgIncomeType();
    income.setCode("2");
    InvoiceLine line = new InvoiceLine();
    line.setBgVatCreditType("1");
    line.setBgIncomeType(income);

    for (AiInvoiceFieldSpec spec : contributor.lineFields()) {
      assertTrue(spec.isClearable(), spec.getName() + " is declared clearable");
    }

    AiInvoiceFieldChange credit =
        contributor.applyLineField(line, "bgVatCreditType", NullNode.getInstance());
    assertNull(line.getBgVatCreditType());
    assertEquals("1", credit.getOldValue());
    assertNull(credit.getNewValue());

    AiInvoiceFieldChange cleared =
        contributor.applyLineField(line, "bgIncomeTypeId", NullNode.getInstance());
    assertNull(line.getBgIncomeType());
    assertEquals("2", cleared.getOldValue());

    line.setBgIncomeType(income);
    contributor.applyLineField(line, "bgIncomeTypeCode", NullNode.getInstance());
    assertNull(line.getBgIncomeType());
  }
}

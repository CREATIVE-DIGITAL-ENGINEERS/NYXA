package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.dto.SaftAmount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAuditFile;
import com.axelor.apps.bglocalization.service.saft.dto.SaftGeneralLedger;
import com.axelor.apps.bglocalization.service.saft.dto.SaftGlAccount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoice;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoiceLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoiceSection;
import com.axelor.apps.bglocalization.service.saft.dto.SaftJournal;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMasterFiles;
import com.axelor.apps.bglocalization.service.saft.dto.SaftParty;
import com.axelor.apps.bglocalization.service.saft.dto.SaftProduct;
import com.axelor.apps.bglocalization.service.saft.dto.SaftSourceDocuments;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxCodeDetail;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxInformation;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxTableEntry;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransaction;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransactionLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftUom;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QA IV.2.4 (с. 96) НАД ГОТОВИЯ ФАЙЛ — по един свидетел на вид ID и двете изключения.
 *
 * <p>Таблиците: клиент „10111111110“, сметки 411 и 702, данък 100211, продукт G1, единица H87.
 * Подаващият е „10123456789“.
 */
class BgSaftReferenceCheckTest {

  private static final String OWN = "10123456789";

  private static SaftMasterFiles master() {
    SaftMasterFiles m = new SaftMasterFiles();
    m.addCustomer(new SaftParty().partyId("10111111110"));
    m.addGlAccount(new SaftGlAccount().accountId("411"));
    m.addGlAccount(new SaftGlAccount().accountId("702"));
    m.addTaxTableEntry(
        new SaftTaxTableEntry()
            .taxType("100")
            .addTaxCodeDetail(new SaftTaxCodeDetail().taxCode("100211")));
    m.addProduct(new SaftProduct().productCode("G1").uomBase("H87").uomStandard("H87"));
    m.addUom(new SaftUom().unitOfMeasure("H87"));
    return m;
  }

  private static SaftTaxInformation tax(String type, String code) {
    return new SaftTaxInformation()
        .taxType(type)
        .taxCode(code)
        .taxAmount(SaftAmount.eur(BigDecimal.ZERO));
  }

  private static SaftGeneralLedger ledger(String customer, String supplier, String account) {
    SaftTransactionLine line =
        new SaftTransactionLine()
            .recordId("1")
            .accountId(account)
            .customerId(customer)
            .supplierId(supplier)
            .addTaxInformation(tax("100", "100211"));
    SaftTransaction t =
        new SaftTransaction().customerId(customer).supplierId(supplier).lines(List.of(line));
    return new SaftGeneralLedger()
        .numberOfEntries(1L)
        .journals(List.of(new SaftJournal().transactions(List.of(t))));
  }

  private static SaftInvoiceSection sales(SaftInvoiceLine line) {
    SaftInvoice invoice =
        new SaftInvoice()
            .invoiceNo("1")
            .partyRole(SaftInvoice.PartyRole.CUSTOMER)
            .partyId("10111111110")
            .accountId("411")
            .addLine(line);
    return new SaftInvoiceSection().numberOfEntries(1L).invoices(List.of(invoice));
  }

  private static SaftInvoiceLine invoiceLine(String product, String uom, SaftTaxInformation tax) {
    return new SaftInvoiceLine()
        .accountId("702")
        .productCode(product)
        .invoiceUom(uom)
        .addTaxInformation(tax);
  }

  private static List<String> check(SaftAuditFile file) {
    return BgSaftReferenceCheck.check(file, OWN).stream()
        .peek(f -> assertTrue(f.isBlocking(), f.toString()))
        .map(f -> f.getCode() + " " + f.getMessage().replaceAll(".*: ", ""))
        .collect(Collectors.toList());
  }

  @Test
  @DisplayName("Всичко е в таблиците — нула находки; подаващият и „0“ са изключения")
  void изрядният() {
    SaftAuditFile file =
        new SaftAuditFile()
            .masterFiles(master())
            .generalLedger(ledger(OWN, OWN, "411"))
            .sourceDocuments(
                new SaftSourceDocuments()
                    .salesInvoices(sales(invoiceLine("0", "H87", tax("100", "100211")))));
    assertEquals(List.of(), check(file));
    file.generalLedger(ledger("10111111110", "0", "411"));
    assertEquals(List.of(), check(file));
  }

  @Test
  @DisplayName("CustomerID/SupplierID извън Customers/Suppliers — ГРЕШКА с указател към партньора")
  void контрагентът() {
    SaftAuditFile file =
        new SaftAuditFile().masterFiles(master()).generalLedger(ledger("0", "10222222220", "411"));
    assertEquals(
        List.of("SAFT_REF_PARTY_NOT_IN_MASTER GeneralLedgerEntries SupplierID 10222222220"),
        check(file));
    BgDeclarationFileFinding finding =
        BgSaftReferenceCheck.check(file, OWN, Map.of("10222222220", 77L)).get(0);
    assertEquals(BgSaftRef.PARTNER, finding.getTargetModel());
    assertEquals(77L, finding.getTargetId());
  }

  @Test
  @DisplayName(
      "Покупната фактура без изрична роля носи SupplierInfo — сверява се със Suppliers, не с"
          + " Customers")
  void покупката_без_роля() {
    SaftMasterFiles master = master();
    master.addSupplier(new SaftParty().partyId("10222222220"));
    master.addGlAccount(new SaftGlAccount().accountId("401"));
    SaftInvoice invoice =
        new SaftInvoice()
            .invoiceNo("P1")
            .partyId("10222222220")
            .accountId("401")
            .addLine(invoiceLine("G1", "H87", tax("100", "100211")));
    SaftAuditFile file =
        new SaftAuditFile()
            .masterFiles(master)
            .sourceDocuments(
                new SaftSourceDocuments()
                    .purchaseInvoices(
                        new SaftInvoiceSection().numberOfEntries(1L).invoices(List.of(invoice))));
    // Ролята null значи „по раздела“ (SaftInvoice, BgSaftXmlWriter): в покупките — SupplierInfo.
    assertEquals(List.of(), check(file));

    invoice.partyId("10333333330");
    assertEquals(
        List.of("SAFT_REF_PARTY_NOT_IN_MASTER PurchaseInvoices SupplierID 10333333330"),
        check(file));
  }

  @Test
  @DisplayName("AccountID извън GeneralLedgerAccounts — ГРЕШКА; празният е на assembler-а")
  void сметката() {
    SaftAuditFile file =
        new SaftAuditFile().masterFiles(master()).generalLedger(ledger(OWN, OWN, "503"));
    assertEquals(
        List.of("SAFT_REF_ACCOUNT_NOT_IN_MASTER GeneralLedgerEntries AccountID 503"), check(file));
    file.generalLedger(ledger(OWN, OWN, ""));
    assertEquals(List.of(), check(file));
  }

  @Test
  @DisplayName("TaxType/TaxCode извън TaxTable — ГРЕШКА (QA с. 38, MF.TT.1)")
  void данъкът() {
    SaftAuditFile file =
        new SaftAuditFile()
            .masterFiles(master())
            .sourceDocuments(
                new SaftSourceDocuments()
                    .salesInvoices(sales(invoiceLine("G1", "H87", tax("600", "600020")))));
    assertEquals(List.of("SAFT_REF_TAX_NOT_IN_TABLE SalesInvoices 600/600020"), check(file));
  }

  @Test
  @DisplayName("ProductCode извън Products и InvoiceUOM извън UOMTable — ГРЕШКА; „0“ е изключение")
  void продуктът_и_единицата() {
    SaftAuditFile file =
        new SaftAuditFile()
            .masterFiles(master())
            .sourceDocuments(
                new SaftSourceDocuments()
                    .salesInvoices(sales(invoiceLine("G9", "KGM", tax("100", "100211")))));
    assertEquals(
        List.of(
            "SAFT_REF_PRODUCT_NOT_IN_MASTER SalesInvoices ProductCode G9",
            "SAFT_REF_UOM_NOT_IN_TABLE SalesInvoices KGM"),
        check(file));
  }

  @Test
  @DisplayName("UOMBase на продукт извън UOMTable — ГРЕШКА")
  void базовата_единица() {
    SaftMasterFiles m = master();
    m.addProduct(new SaftProduct().productCode("G2").uomBase("XUN").uomStandard("H87"));
    assertEquals(
        List.of("SAFT_REF_UOM_NOT_IN_TABLE Products XUN"),
        check(new SaftAuditFile().masterFiles(m)));
  }

  @Test
  @DisplayName("Нулевите структури (QA IV.2.6) не се проверяват — 998 и „0“ са по образеца")
  void нулевите_структури() {
    SaftAuditFile file =
        new SaftAuditFile()
            .masterFiles(master())
            .generalLedger(
                new SaftGeneralLedger()
                    .numberOfEntries(0L)
                    .journals(List.of(BgSaftGlEntriesAssembler.emptyJournal(null))))
            .sourceDocuments(
                new SaftSourceDocuments()
                    .salesInvoices(
                        new SaftInvoiceSection()
                            .numberOfEntries(0L)
                            .invoices(List.of(BgSaftInvoicesAssembler.emptyInvoice()))));
    assertEquals(List.of(), check(file));
  }
}

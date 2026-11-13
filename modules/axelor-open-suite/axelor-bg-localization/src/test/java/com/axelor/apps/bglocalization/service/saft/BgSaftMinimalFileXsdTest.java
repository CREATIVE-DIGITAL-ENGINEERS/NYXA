package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXmlWriter;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAmount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAuditFile;
import com.axelor.apps.bglocalization.service.saft.dto.SaftGlAccount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoice;
import com.axelor.apps.bglocalization.service.saft.dto.SaftJournal;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMasterFiles;
import com.axelor.apps.bglocalization.service.saft.dto.SaftPayment;
import com.axelor.apps.bglocalization.service.saft.dto.SaftProduct;
import com.axelor.apps.bglocalization.service.saft.dto.SaftProductTax;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxInformation;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxRegistration;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxTableEntry;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransaction;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransactionLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftUom;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ФАЙЛЪТ ОТ WRITER-А МИНАВА ЗАКОВАНАТА СХЕМА — И ТЕСТЪТ МОЖЕ ДА ПАДНЕ.
 *
 * <p><b>⚠️ ЗЕЛЕНОТО ТУК ЗНАЧИ САМО СТРУКТУРА.</b> Минимален файл с измислени числа, проверен през
 * същия {@code BgXsdValidator}, който ще вика генерирането. Сумите не се връзват и не трябва — това
 * е семантичната врата, не схемата.
 *
 * <p><b>⚠️ ДВАТА ОТРИЦАТЕЛНИ СВИДЕТЕЛЯ СА ЗАДЪЛЖИТЕЛНИ.</b> Валидатор, който връща „0 находки“ за
 * всичко, би направил първия тест вечно зелен. Затова един развален файл и една забранена от
 * схемата стойност трябва да паднат.
 */
class BgSaftMinimalFileXsdTest {

  @Test
  @DisplayName("Минималният месечен файл минава схемата")
  void минималният_файл_минава() {
    byte[] xml = BgSaftXmlWriter.toBytes(BgSaftFixtures.minimal());
    assertEquals(List.of(), BgSaftFixtures.validate(xml));
  }

  @Test
  @DisplayName("Файлът с незадължителните елементи, които writer-ът познава, също минава")
  void пълният_файл_минава() {
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getHeader()
        .auditFileCountry("BG")
        .headerComment("M")
        .taxEntity("Company")
        .getCompany()
        .addTaxRegistration(
            new SaftTaxRegistration()
                .taxRegistrationNumber("BG123456789")
                .taxType("100010")
                .taxNumber("123456789")
                .taxAuthority("NRA")
                .taxVerificationDate(BgSaftFixtures.DAY));
    file.getHeader().getCompany().getContacts().get(0).addTelephone("+35921234567");
    file.getHeader().getCompany().getContacts().get(0).addEmail("office@example.bg");
    file.getHeader().getCompany().getAddresses().get(0).streetName("Витоша").number("1");
    file.getMasterFiles().getGlAccounts().get(0).closingBalance(new BigDecimal("-15.50"));
    file.getMasterFiles()
        .getProducts()
        .get(0)
        .goodsServicesId("01")
        .productCommodityCode("85171300")
        .addTax(new SaftProductTax().taxType("100").taxCode("100211"));
    file.getMasterFiles()
        .getTaxTableEntries()
        .get(0)
        .getTaxCodeDetails()
        .get(0)
        .description("Стандартна ставка")
        .taxPercentage(new BigDecimal("20"))
        .effectiveDate(BgSaftFixtures.DAY);
    SaftInvoice invoice = BgSaftFixtures.invoice();
    invoice
        .period(4)
        .periodYear(2026)
        .netTotal(new BigDecimal("100.00"))
        .grossTotal(new BigDecimal("120.00"))
        .addTaxInformationTotal(BgSaftFixtures.tax());
    invoice.getLines().get(0).lineNumber("1").invoiceUom("H87").goodsServicesId("01");
    // Данък „да се потвърди“: празен код — изявление, което схемата приема.
    invoice
        .getLines()
        .get(0)
        .addTaxInformation(
            new SaftTaxInformation()
                .taxType("100")
                .taxCode("")
                .taxAmount(
                    new SaftAmount()
                        .amount(new BigDecimal("10.00"))
                        .currencyCode("USD")
                        .currencyAmount(new BigDecimal("11.70"))
                        .exchangeRate(new BigDecimal("0.8547"))));
    file.getSourceDocuments().getSalesInvoices().invoices(List.of(invoice));
    SaftPayment payment = file.getSourceDocuments().getPayments().getPayments().iterator().next();
    payment.paymentMechanism("42").grossTotal(new BigDecimal("120.00"));

    assertEquals(List.of(), BgSaftFixtures.validate(BgSaftXmlWriter.toBytes(file)));
  }

  @Test
  @DisplayName("Сметките от assembler-а минават схемата: навити, през родител, кредитно салдо")
  void сметките_от_assembler_а_минават() {
    List<SaftGlAccount> accounts = BgSaftFixtures.assembledGlAccounts();
    assertEquals(4, accounts.size());
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getMasterFiles().getGlAccounts().clear();
    accounts.forEach(file.getMasterFiles()::addGlAccount);
    assertEquals(List.of(), BgSaftFixtures.validate(BgSaftXmlWriter.toBytes(file)));
  }

  @Test
  @DisplayName("Клиентите и доставчиците от assembler-а минават схемата: 10, 11, 15, двете страни")
  void контрагентите_от_assembler_а_минават() {
    BgSaftPartiesAssembler.Result parties = BgSaftFixtures.assembledParties();
    assertEquals(2, parties.getCustomers().size());
    assertEquals(2, parties.getSuppliers().size());
    assertTrue(parties.getFindings().stream().noneMatch(BgDeclarationFileFinding::isBlocking));
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getMasterFiles().getCustomers().clear();
    file.getMasterFiles().getSuppliers().clear();
    parties.getCustomers().forEach(file.getMasterFiles()::addCustomer);
    parties.getSuppliers().forEach(file.getMasterFiles()::addSupplier);
    assertEquals(List.of(), BgSaftFixtures.validate(BgSaftXmlWriter.toBytes(file)));
  }

  @Test
  @DisplayName("Отрицателен свидетел: файл без Customers пада — схемата иска поне един клиент")
  void без_клиенти_пада() {
    String xml =
        new String(BgSaftXmlWriter.toBytes(BgSaftFixtures.minimal()), StandardCharsets.UTF_8);
    String broken = xml.replaceAll("<nsSAFT:Customers>.*?</nsSAFT:Customers>", "");
    assertFalse(broken.equals(xml), "подмяната трябва да е махнала Customers");
    assertFalse(BgSaftFixtures.validate(broken.getBytes(StandardCharsets.UTF_8)).isEmpty());

    // И writer-ът не измисля запис: празната таблица спира с пътя си.
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getMasterFiles().getSuppliers().clear();
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> BgSaftXmlWriter.toBytes(file));
    assertTrue(e.getMessage().contains("Suppliers/Supplier"), e.getMessage());
  }

  @Test
  @DisplayName("Отрицателен свидетел: контрагент с празна държава пада — затова находката е ГРЕШКА")
  void празната_държава_пада() {
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getMasterFiles().getCustomers().get(0).getAddresses().get(0).country("");
    assertFalse(BgSaftFixtures.validate(BgSaftXmlWriter.toBytes(file)).isEmpty());
  }

  @Test
  @DisplayName("TaxTable, UOMTable и Products от assembler-ите минават схемата")
  void трите_таблици_от_assembler_ите_минават() {
    List<SaftTaxTableEntry> taxes = BgSaftFixtures.assembledTaxTable();
    List<SaftProduct> products = BgSaftFixtures.assembledProducts();
    List<SaftUom> uoms = BgSaftFixtures.assembledUoms(products);
    assertEquals(
        List.of("000", "100"),
        taxes.stream().map(SaftTaxTableEntry::getTaxType).collect(Collectors.toList()));
    assertEquals(5, products.size());
    assertEquals(
        List.of("E49", "H87", "HUR", "KGM"),
        uoms.stream().map(SaftUom::getUnitOfMeasure).collect(Collectors.toList()));

    SaftAuditFile file = BgSaftFixtures.minimal();
    SaftMasterFiles m = file.getMasterFiles();
    m.getTaxTableEntries().clear();
    m.getUoms().clear();
    m.getProducts().clear();
    taxes.forEach(m::addTaxTableEntry);
    uoms.forEach(m::addUom);
    products.forEach(m::addProduct);
    byte[] xml = BgSaftXmlWriter.toBytes(file);
    assertEquals(List.of(), BgSaftFixtures.validate(xml));

    String text = new String(xml, StandardCharsets.UTF_8);
    assertTrue(text.contains("<nsSAFT:TaxPercentage>20</nsSAFT:TaxPercentage>"), "20 %, не 0.2");
    assertTrue(
        text.contains("<nsSAFT:ProductCommodityCode>00000000</nsSAFT:ProductCommodityCode>"));
    assertTrue(
        text.contains("<nsSAFT:ProductCommodityCode>85171300</nsSAFT:ProductCommodityCode>"));
    assertTrue(text.contains("<nsSAFT:ProductCommodityCode>0</nsSAFT:ProductCommodityCode>"));
    String factor = "UOMToUOMBaseConversionFactor>";
    assertTrue(text.contains("<nsSAFT:" + factor + "0.0833333333</nsSAFT:" + factor));
  }

  @Test
  @DisplayName("Дневникът от assembler-а минава схемата: журнали, дебит и кредит, 000/000000")
  void дневникът_от_assembler_а_минава() {
    SaftAuditFile file =
        BgSaftFixtures.minimal().generalLedger(BgSaftFixtures.assembledGlEntries());
    byte[] xml = BgSaftXmlWriter.toBytes(file);
    assertEquals(List.of(), BgSaftFixtures.validate(xml));

    String gl = generalLedger(new String(xml, StandardCharsets.UTF_8));
    assertTrue(gl.contains("<nsSAFT:NumberOfEntries>4</nsSAFT:NumberOfEntries>"));
    assertTrue(gl.contains("<nsSAFT:TotalDebit>265.00</nsSAFT:TotalDebit>"));
    assertTrue(gl.contains("<nsSAFT:CreditAmount>"), "xs:choice — кредитният ред");
    assertTrue(gl.contains("<nsSAFT:TaxCode>000000</nsSAFT:TaxCode>"));
    // „Да се потвърди“: схемата приема празния код, но НАП го отказва (QA IV.2.3) — затова
    // празен TaxCode не се пише никога (G6a).
    assertFalse(gl.contains("<nsSAFT:TaxCode></nsSAFT:TaxCode>"));
    assertFalse(gl.contains("<nsSAFT:TaxCode/>"));
    assertTrue(gl.contains("<nsSAFT:SupplierID>10123456789</nsSAFT:SupplierID>"));
  }

  @Test
  @DisplayName(
      "Отрицателен свидетел: ред на транзакция без TaxInformation пада — GL.33 е Mandatory")
  void ред_без_данъчна_информация_пада() {
    String xml =
        new String(
            BgSaftXmlWriter.toBytes(
                BgSaftFixtures.minimal().generalLedger(BgSaftFixtures.assembledGlEntries())),
            StandardCharsets.UTF_8);
    String gl = generalLedger(xml);
    String stripped = gl.replaceAll("<nsSAFT:TaxInformation>.*?</nsSAFT:TaxInformation>", "");
    assertFalse(stripped.equals(gl), "подмяната трябва да е махнала TaxInformation");
    String broken = xml.replace(gl, stripped);
    List<BgDeclarationFileFinding> findings =
        BgSaftFixtures.validate(broken.getBytes(StandardCharsets.UTF_8));
    assertFalse(findings.isEmpty(), "ред без TaxInformation не може да мине схемата");
    assertTrue(findings.get(0).isBlocking());
    assertTrue(findings.get(0).getMessage().contains("TaxInformation"), findings.toString());

    // И writer-ът не пропуска празен списък: спира с пътя си.
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.generalLedger(BgSaftFixtures.generalLedger());
    SaftJournal journal = file.getGeneralLedger().getJournals().iterator().next();
    SaftTransaction transaction = journal.getTransactions().iterator().next();
    SaftTransactionLine line = transaction.getLines().iterator().next();
    line.getTaxInformation().clear();
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> BgSaftXmlWriter.toBytes(file));
    assertTrue(e.getMessage().contains("TransactionLine/TaxInformation"), e.getMessage());
  }

  @Test
  @DisplayName("Плащанията от assembler-а минават схемата: метод/механизъм, празен метод, D и C")
  void плащанията_от_assembler_а_минават() {
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getSourceDocuments().payments(BgSaftFixtures.assembledPayments());
    byte[] xml = BgSaftXmlWriter.toBytes(file);
    assertEquals(List.of(), BgSaftFixtures.validate(xml));

    String payments = section(new String(xml, StandardCharsets.UTF_8), "Payments");
    assertTrue(payments.contains("<nsSAFT:NumberOfEntries>9</nsSAFT:NumberOfEntries>"));
    assertTrue(payments.contains("<nsSAFT:TotalDebit>165.00</nsSAFT:TotalDebit>"));
    assertTrue(payments.contains("<nsSAFT:TotalCredit>178.00</nsSAFT:TotalCredit>"));
    assertTrue(payments.contains("<nsSAFT:PaymentMethod>02</nsSAFT:PaymentMethod>"));
    assertTrue(payments.contains("<nsSAFT:PaymentMechanism>99</nsSAFT:PaymentMechanism>"));
    // Неописуемият режим: празният метод схемата приема — вратата е находката.
    assertTrue(payments.contains("<nsSAFT:PaymentMethod></nsSAFT:PaymentMethod>"));
    assertTrue(payments.contains("<nsSAFT:DebitCreditIndicator>C</nsSAFT:DebitCreditIndicator>"));
    assertTrue(payments.contains("<nsSAFT:AccountID>422</nsSAFT:AccountID>"));
  }

  @Test
  @DisplayName("Отрицателен свидетел: Payments без нито едно Payment пада — затова SAFT_PAY_EMPTY")
  void празните_плащания_падат() {
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getSourceDocuments().payments(BgSaftFixtures.assembledPayments());
    String xml = new String(BgSaftXmlWriter.toBytes(file), StandardCharsets.UTF_8);
    String payments = section(xml, "Payments");
    String stripped =
        payments
            .replaceAll("<nsSAFT:Payment>.*?</nsSAFT:Payment>", "")
            .replaceAll(
                "<nsSAFT:NumberOfEntries>9</nsSAFT:NumberOfEntries>",
                "<nsSAFT:NumberOfEntries>0</nsSAFT:NumberOfEntries>");
    assertFalse(stripped.contains("<nsSAFT:Payment>"), "подмяната трябва да е махнала плащанията");
    List<BgDeclarationFileFinding> findings =
        BgSaftFixtures.validate(xml.replace(payments, stripped).getBytes(StandardCharsets.UTF_8));
    assertFalse(findings.isEmpty(), "схемата иска поне едно Payment (SD.P.4)");
    assertTrue(findings.get(0).isBlocking());

    // И writer-ът не измисля плащане: празният списък спира с пътя си.
    file.getSourceDocuments().getPayments().payments(List.of());
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> BgSaftXmlWriter.toBytes(file));
    assertTrue(e.getMessage().contains("Payments/Payment"), e.getMessage());
  }

  @Test
  @DisplayName("Отрицателен свидетел: DebitCreditIndicator извън D/C пада")
  void чуждият_индикатор_пада() {
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getSourceDocuments()
        .getPayments()
        .getPayments()
        .iterator()
        .next()
        .getLines()
        .get(0)
        .debitCreditIndicator("-");
    assertFalse(BgSaftFixtures.validate(BgSaftXmlWriter.toBytes(file)).isEmpty());
  }

  /** Само една секция на файла, с отварящия и затварящия ѝ таг. */
  private static String section(String xml, String name) {
    int from = xml.indexOf("<nsSAFT:" + name + ">");
    String close = "</nsSAFT:" + name + ">";
    int to = xml.indexOf(close);
    assertTrue(from >= 0 && to > from, "файлът няма " + name);
    return xml.substring(from, to + close.length());
  }

  /** Само секцията GeneralLedgerEntries — фактурите също носят TaxInformation. */
  private static String generalLedger(String xml) {
    int from = xml.indexOf("<nsSAFT:GeneralLedgerEntries>");
    int to = xml.indexOf("</nsSAFT:GeneralLedgerEntries>");
    assertTrue(from >= 0 && to > from, "файлът няма GeneralLedgerEntries");
    return xml.substring(from, to);
  }

  @Test
  @DisplayName("Отрицателен свидетел: Products без нито един Product пада")
  void празните_продукти_падат() {
    String xml =
        new String(BgSaftXmlWriter.toBytes(BgSaftFixtures.minimal()), StandardCharsets.UTF_8);
    String broken =
        xml.replaceAll(
            "<nsSAFT:Products>.*?</nsSAFT:Products>", "<nsSAFT:Products></nsSAFT:Products>");
    assertFalse(broken.equals(xml), "подмяната трябва да е махнала продукта");
    assertFalse(BgSaftFixtures.validate(broken.getBytes(StandardCharsets.UTF_8)).isEmpty());
  }

  @Test
  @DisplayName("Отрицателен свидетел: UOMBase над 35 знака пада — дължината се хваща и от схемата")
  void дългата_базова_единица_пада() {
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getMasterFiles()
        .getProducts()
        .get(0)
        .uomBase("Кутия с изключително дълго име за мерна единица");
    assertFalse(BgSaftFixtures.validate(BgSaftXmlWriter.toBytes(file)).isEmpty());
  }

  @Test
  @DisplayName("Празен RegistrationNumber схемата ПРИЕМА — единствената врата е находката")
  void празният_регистрационен_номер_минава_схемата() {
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getMasterFiles().getCustomers().get(0).registrationNumber("").partyId("15");
    assertEquals(List.of(), BgSaftFixtures.validate(BgSaftXmlWriter.toBytes(file)));
  }

  @Test
  @DisplayName("Отрицателен свидетел: празен AccountID (сметка без код по НАП) пада")
  void празният_код_на_сметката_пада() {
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getMasterFiles().getGlAccounts().get(0).accountId("");
    assertFalse(BgSaftFixtures.validate(BgSaftXmlWriter.toBytes(file)).isEmpty());
  }

  @Test
  @DisplayName("Отрицателен свидетел: AccountType с кирилско „е“, както го пише книгата, пада")
  void кирилското_е_пада() {
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getMasterFiles().getGlAccounts().get(0).accountType("Activе");
    assertFalse(BgSaftFixtures.validate(BgSaftXmlWriter.toBytes(file)).isEmpty());
  }

  @Test
  @DisplayName("Отрицателен свидетел: файл без UOMTable пада")
  void без_задължителна_таблица_пада() {
    String xml =
        new String(BgSaftXmlWriter.toBytes(BgSaftFixtures.minimal()), StandardCharsets.UTF_8);
    String broken = xml.replaceAll("<nsSAFT:UOMTable>.*?</nsSAFT:UOMTable>", "");
    assertFalse(broken.equals(xml), "подмяната трябва да е махнала UOMTable");

    List<BgDeclarationFileFinding> findings =
        BgSaftFixtures.validate(broken.getBytes(StandardCharsets.UTF_8));
    assertFalse(findings.isEmpty(), "файл без задължителна таблица не може да мине схемата");
    assertTrue(findings.get(0).isBlocking());
  }

  @Test
  @DisplayName("Отрицателен свидетел: DefaultCurrencyCode различна от EUR пада")
  void левът_като_валута_по_подразбиране_пада() {
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getHeader().defaultCurrencyCode("BGN");
    assertFalse(BgSaftFixtures.validate(BgSaftXmlWriter.toBytes(file)).isEmpty());
  }
}

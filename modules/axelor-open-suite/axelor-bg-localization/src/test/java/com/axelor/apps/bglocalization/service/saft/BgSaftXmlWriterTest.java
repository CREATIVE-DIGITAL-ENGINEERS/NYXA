package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXmlWriter;
import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXsd;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAuditFile;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoice;
import com.axelor.apps.bglocalization.service.saft.dto.SaftJournal;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransaction;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Договорът на writer-а, който XSD проверката сама не казва: КОЕ липсва и как се пише. */
class BgSaftXmlWriterTest {

  private static String xml(SaftAuditFile file) {
    return new String(BgSaftXmlWriter.toBytes(file), StandardCharsets.UTF_8);
  }

  private static Document parse(SaftAuditFile file) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    return f.newDocumentBuilder().parse(new ByteArrayInputStream(BgSaftXmlWriter.toBytes(file)));
  }

  private static String first(Document doc, String name) {
    return doc.getElementsByTagNameNS(BgSaftXsd.NAMESPACE, name).item(0).getTextContent();
  }

  @Test
  @DisplayName("Коренът е AuditFile в пространството на схемата, с префикса от еталоните")
  void коренът_и_пространството() throws Exception {
    Element root = parse(BgSaftFixtures.minimal()).getDocumentElement();
    assertEquals(BgSaftXsd.ROOT_ELEMENT, root.getLocalName());
    assertEquals(BgSaftXsd.NAMESPACE, root.getNamespaceURI());
    assertEquals(BgSaftXsd.NAMESPACE_PREFIX, root.getPrefix());
    // Кавичките в пролога зависят от StAX имплементацията на classpath-а; и двете са XML.
    assertTrue(
        xml(BgSaftFixtures.minimal())
            .matches("(?s)^<\\?xml version=[\"']1\\.0[\"'] encoding=[\"']UTF-8[\"'].*"));
  }

  @Test
  @DisplayName("Кирилицата и специалните знаци пътуват непокътнати")
  void кирилица_и_специални_знаци() throws Exception {
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getHeader().getCompany().name("„Иван & Син“ <ООД>");
    assertEquals("„Иван & Син“ <ООД>", first(parse(file), "Name"));
  }

  @Test
  @DisplayName("Празен низ в задължителен елемент е изявление — пише се празен елемент")
  void празният_низ_се_пише() {
    assertTrue(xml(BgSaftFixtures.minimal()).contains("<nsSAFT:SoftwareID></nsSAFT:SoftwareID>"));
  }

  @Test
  @DisplayName("null в задължителен елемент е забравено поле — спира с пътя си")
  void null_спира_с_пътя() {
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getHeader().softwareId(null);
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> BgSaftXmlWriter.toBytes(file));
    assertTrue(e.getMessage().contains("Header/SoftwareID"), e.getMessage());
  }

  @Test
  @DisplayName("Празен задължителен списък спира с името си, а не в XSD-то")
  void празен_списък_спира() {
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getMasterFiles().getUoms().clear();
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> BgSaftXmlWriter.toBytes(file));
    assertTrue(e.getMessage().contains("UOMTable/UOMTableEntry"), e.getMessage());
  }

  @Test
  @DisplayName("ProductCommodityCode без стойност спира с пътя си — 0/00000000 решава assembler-ът")
  void кодът_по_кн_без_стойност_спира() {
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getMasterFiles().getProducts().get(0).productCommodityCode(null);
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> BgSaftXmlWriter.toBytes(file));
    assertTrue(e.getMessage().contains("Product/ProductCommodityCode"), e.getMessage());
  }

  @Test
  @DisplayName("Сума с повече от два знака не се закръгля тихо")
  void сумата_не_се_закръгля() {
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getGeneralLedger().totalDebit(new BigDecimal("120.005"));
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> BgSaftXmlWriter.toBytes(file));
    assertTrue(e.getMessage().contains("GeneralLedgerEntries/TotalDebit"), e.getMessage());
  }

  @Test
  @DisplayName("Сумите излизат с два знака, салдото със знак минус — като кредитно")
  void формат_на_сумите_и_салдата() throws Exception {
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getGeneralLedger().totalCredit(new BigDecimal("7"));
    file.getMasterFiles().getGlAccounts().get(0).closingBalance(new BigDecimal("-15.5"));
    Document doc = parse(file);
    assertEquals("7.00", first(doc, "TotalCredit"));
    Element account = (Element) doc.getElementsByTagNameNS(BgSaftXsd.NAMESPACE, "Account").item(0);
    assertEquals(
        "15.50",
        account
            .getElementsByTagNameNS(BgSaftXsd.NAMESPACE, "ClosingCreditBalance")
            .item(0)
            .getTextContent());
    assertEquals(
        0,
        account.getElementsByTagNameNS(BgSaftXsd.NAMESPACE, "ClosingDebitBalance").getLength(),
        "салдото е или дебитно, или кредитно — не и двете");
  }

  @Test
  @DisplayName("Журналите се обхождат ВЕДНЪЖ — извличането ще ги подава като курсор")
  void журналите_се_обхождат_веднъж() {
    SaftAuditFile file = BgSaftFixtures.minimal();
    SaftJournal journal = file.getGeneralLedger().getJournals().iterator().next();
    List<SaftTransaction> transactions = List.of(journal.getTransactions().iterator().next());
    AtomicInteger calls = new AtomicInteger();
    Iterable<SaftTransaction> once =
        () -> {
          if (calls.incrementAndGet() > 1) {
            throw new IllegalStateException("втори обход на курсора");
          }
          return transactions.iterator();
        };
    journal.transactions(once);
    BgSaftXmlWriter.toBytes(file);
    assertEquals(1, calls.get());
  }

  @Test
  @DisplayName("Празен Iterable от журнали спира като празен списък")
  void празен_iterable_спира() {
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getGeneralLedger().journals(Collections::<SaftJournal>emptyIterator);
    assertThrows(IllegalStateException.class, () -> BgSaftXmlWriter.toBytes(file));
  }

  @Test
  @DisplayName("Фактурата за продажба носи CustomerInfo, за покупка — SupplierInfo")
  void контрагентът_следва_раздела() {
    String xml = xml(BgSaftFixtures.minimal());
    int sales = xml.indexOf("<nsSAFT:SalesInvoices>");
    int payments = xml.indexOf("<nsSAFT:Payments>");
    int purchases = xml.indexOf("<nsSAFT:PurchaseInvoices>");
    assertTrue(sales < payments && payments < purchases, "редът е продажби, плащания, покупки");
    assertTrue(xml.substring(sales, payments).contains("<nsSAFT:CustomerInfo>"));
    assertTrue(xml.substring(purchases).contains("<nsSAFT:SupplierInfo>"));
  }

  @Test
  @DisplayName("Изричната роля бие раздела: протоколът за ВОП в продажбите носи SupplierInfo")
  void ролята_бие_раздела() {
    SaftAuditFile file = BgSaftFixtures.minimal();
    SaftInvoice protocol =
        BgSaftFixtures.invoice().partyRole(SaftInvoice.PartyRole.SUPPLIER).partyId("11DE1");
    file.getSourceDocuments().getSalesInvoices().invoices(List.of(protocol));
    String xml = xml(file);
    String sales =
        xml.substring(xml.indexOf("<nsSAFT:SalesInvoices>"), xml.indexOf("<nsSAFT:Payments>"));
    assertTrue(sales.contains("<nsSAFT:SupplierInfo>"));
    assertTrue(sales.contains("<nsSAFT:SupplierID>11DE1</nsSAFT:SupplierID>"));
    assertTrue(!sales.contains("<nsSAFT:CustomerInfo>"));
    assertEquals(List.of(), BgSaftFixtures.validate(xml.getBytes(StandardCharsets.UTF_8)));
  }
}

package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatDeclarationAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesAdapter;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ФАКТУРА ЗА ПОКУПКА С ДАНЪК, КОЙТО РЕШАВА КОЛОНАТА.
 *
 * <p>Освободената (BG_EX_P) и 0-процентната (BG_ZRP) покупка са к.9 „…без право на данъчен кредит
 * или без данък“, а придобиването от посредник в тристранна операция (BG_TRIANG_P) е к.15 — каквото
 * и да пише в кредитния тип на реда. Преди всичко вървеше по кредитния тип и празното беше к.10.
 */
class BgVatPurchaseInvoiceTaxCodeTest {

  /** Само за да се стигне до {@code protected enrichFromInvoice}. Репозиторият не се ползва. */
  private static final class Collector extends BgVatDocumentCollector {
    Collector() {
      super(null);
    }

    BgVatDocumentDto of(Invoice invoice) {
      BgVatDocumentDto document = new BgVatDocumentDto();
      enrichFromInvoice(document, invoice);
      return document;
    }
  }

  private static final Collector COLLECTOR = new Collector();

  private static InvoiceLine line(String taxCode, String exTax, String inTax, String creditType) {
    Tax tax = new Tax();
    tax.setCode(taxCode);
    TaxLine taxLine = new TaxLine();
    taxLine.setTax(tax);
    InvoiceLine line = new InvoiceLine();
    line.setCompanyExTaxTotal(new BigDecimal(exTax));
    line.setCompanyInTaxTotal(new BigDecimal(inTax));
    line.setTaxLineSet(new HashSet<>(Collections.singleton(taxLine)));
    line.setBgVatCreditType(creditType);
    return line;
  }

  private static BgVatDocumentDto purchase(InvoiceLine... lines) {
    Invoice invoice = new Invoice();
    invoice.setOperationTypeSelect(1);
    invoice.setInvoiceLineList(new ArrayList<>(Arrays.asList(lines)));
    BgVatDocumentDto doc = COLLECTOR.of(invoice);
    doc.setNapDocumentType("01");
    return doc;
  }

  private static void eq(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual), expected + " vs " + actual);
  }

  @Test
  @DisplayName("BG_TRIANG_P без кредитен тип → к.15, не к.10; ДДС никъде")
  void тристранна_към_к15() {
    BgVatDocumentDto doc = purchase(line("BG_TRIANG_P", "1000", "1000", null));

    eq("1000", doc.getPurchaseAmount(BgVatPurchaseColumn.K15_BASE_TRIANGULAR_ACQ));
    assertEquals(1, doc.getPurchaseAmounts().size(), doc.getPurchaseAmounts().toString());
    // Нищо не е прието по подразбиране: колоната я дава данъкът.
    assertTrue(doc.getWarnings().isEmpty(), doc.getWarnings().toString());
  }

  @Test
  @DisplayName("BG_EX_P и BG_ZRP без кредитен тип → к.9, не к.10")
  void освободена_и_нулева_към_к9() {
    for (String tax : new String[] {"BG_EX_P", "BG_ZRP"}) {
      BgVatDocumentDto doc = purchase(line(tax, "1000", "1000", null));

      eq("1000", doc.getPurchaseAmount(BgVatPurchaseColumn.K09_BASE_NO_CREDIT));
      assertEquals(1, doc.getPurchaseAmounts().size(), tax + " " + doc.getPurchaseAmounts());
      assertTrue(doc.getWarnings().isEmpty(), tax + " " + doc.getWarnings());
    }
  }

  @Test
  @DisplayName("Изричен „1“/„2“ върху BG_EX_P/BG_ZRP → пак к.9, с предупреждение; „3“ — без")
  void изричният_кредит_не_мени_колоната() {
    for (String tax : new String[] {"BG_EX_P", "BG_ZRP"}) {
      for (String credit : new String[] {"1", "2"}) {
        BgVatDocumentDto doc = purchase(line(tax, "1000", "1000", credit));

        eq("1000", doc.getPurchaseAmount(BgVatPurchaseColumn.K09_BASE_NO_CREDIT));
        assertEquals(1, doc.getPurchaseAmounts().size());
        assertEquals(
            Collections.singletonList(
                "Данъкът на реда определя колоната — кредитният тип „"
                    + credit
                    + "“ е пренебрегнат (invoice line null)"),
            doc.getWarnings(),
            tax + "/" + credit);
      }
      assertTrue(purchase(line(tax, "1000", "1000", "3")).getWarnings().isEmpty(), tax);
    }
    BgVatDocumentDto triangular = purchase(line("BG_TRIANG_P", "1000", "1000", "1"));
    eq("1000", triangular.getPurchaseAmount(BgVatPurchaseColumn.K15_BASE_TRIANGULAR_ACQ));
    assertEquals(1, triangular.getWarnings().size());
  }

  @Test
  @DisplayName("Обикновеният ред до тях върви по кредитния си тип — един ред в дневника")
  void смесена_фактура() {
    BgVatDocumentDto doc =
        purchase(line("BG_SRP_20", "500", "600", "1"), line("BG_EX_P", "300", "300", null));

    eq("500", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("100", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    eq("300", doc.getPurchaseAmount(BgVatPurchaseColumn.K09_BASE_NO_CREDIT));
  }

  @Test
  @DisplayName("DEKLAR: освободената покупка е в кл.30, не в кл.31; тристранната — също в кл.30")
  void декларацията() {
    List<BgVatDocumentDto> purchases =
        Arrays.asList(
            purchase(line("BG_EX_P", "1000", "1000", null)),
            purchase(line("BG_TRIANG_P", "400", "400", null)));
    String record =
        new BgVatDeclarationAdapter(new BgVatSalesAdapter())
            .buildLines(
                Collections.emptyList(),
                purchases,
                new BgVatDeclarationAdapter.Header("BG100000033", "Х", "202606", "Х"),
                new BgVatDeclarationAdapter.ManualCells(null, null, null, null, null, null))
            .get(0);

    // 01-30 = [03-30] + [03-44] — к.9 и к.15; 01-31 = [03-31] — к.10.
    // Клетките: 151 заглавие + 15 продажбени × 15 знака = 376 → кл.30 [376:391], кл.31 [391:406].
    assertEquals("1400.00", record.substring(376, 391).trim());
    assertEquals("0.00", record.substring(391, 406).trim());
  }
}

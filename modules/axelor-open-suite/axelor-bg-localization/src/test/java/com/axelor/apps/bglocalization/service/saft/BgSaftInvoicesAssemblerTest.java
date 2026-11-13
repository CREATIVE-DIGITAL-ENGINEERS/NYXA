package com.axelor.apps.bglocalization.service.saft;

import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.ACCOUNT_ASSET;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.ACCOUNT_EXPENSE;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.ACCOUNT_MATERIALS;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.ACCOUNT_SALES;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.ACCOUNT_UNMAPPED;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.APRIL_10;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.APRIL_5;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.BETA;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.LRP_9;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.LRS_9;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.SRP_20;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.SRS_20;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.invoiceTax;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.line;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.purchase;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.sale;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.BgSaftInvoicesAssembler.InvoiceTaxRow;
import com.axelor.apps.bglocalization.service.saft.BgSaftInvoicesAssembler.LineRow;
import com.axelor.apps.bglocalization.service.saft.BgSaftInvoicesAssembler.Result;
import com.axelor.apps.bglocalization.service.saft.BgSaftInvoicesAssembler.Source;
import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXmlWriter;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAuditFile;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoice;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoiceLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoiceSection;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxInformation;
import com.axelor.apps.bglocalization.service.vat.BgVatValidator;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BgSaftInvoicesAssemblerTest {

  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }

  private static List<SaftInvoice> invoices(SaftInvoiceSection section) {
    List<SaftInvoice> list = new ArrayList<>();
    section.getInvoices().forEach(list::add);
    return list;
  }

  private static BgDeclarationFileFinding finding(Result result, String code) {
    return BgSaftTestFindings.merged(result.getFindings(), code)
        .orElseThrow(() -> new AssertionError("няма находка " + code + ": " + codes(result)));
  }

  private static List<String> codes(Result result) {
    return result.getFindings().stream()
        .map(BgDeclarationFileFinding::getCode)
        .collect(Collectors.toList());
  }

  private static List<String> errors(Result result) {
    return result.getFindings().stream()
        .filter(BgDeclarationFileFinding::isBlocking)
        .map(f -> f.getCode() + ": " + f.getMessage())
        .collect(Collectors.toList());
  }

  private static SaftTaxInformation onlyTax(SaftInvoiceLine line) {
    assertEquals(1, line.getTaxInformation().size(), "един TaxInformation на ред");
    return line.getTaxInformation().get(0);
  }

  // ─────────────── продажба ───────────────

  private static Source sale20and9() {
    return new Source()
        .add(sale(1, "S-1", 3))
        .add(
            line(11, 1, 1, ACCOUNT_SALES, "100.00", "20.00")
                .product(501L, "P1", "Стока")
                .qty(d("2"))
                .tax(SRS_20, "BG_SRS_20", d("20.0000000000")))
        .add(
            line(12, 1, 2, ACCOUNT_SALES, "50.00", "4.50")
                .product(502L, "B1", "Книга")
                .tax(LRS_9, "BG_LRS_9", d("9.0000000000")))
        // Заглавен ред — не е ред на документа.
        .add(new LineRow(10L, 1L).sequence(0).type(1).description("Заглавие"))
        .add(invoiceTax(1, SRS_20, "20.00"))
        .add(invoiceTax(1, LRS_9, "4.50"));
  }

  @Test
  @DisplayName("Периодът на отразяване в друг месец: предупреждение, фактурата остава в SAF-T")
  void период_на_отразяване() {
    Result result =
        BgSaftInvoiceFixtures.assemble(
            sale20and9().reportingPeriod(1L, "ф-ра № S-1 — в дневниците за 05.2026"));

    assertEquals(List.of(), errors(result));
    assertEquals(1L, result.getSales().getNumberOfEntries());
    BgDeclarationFileFinding finding = finding(result, "SAFT_INV_REPORTING_PERIOD");
    assertFalse(finding.isBlocking());
    assertTrue(
        finding.getMessage().contains("S-1 — в дневниците за 05.2026"), finding.getMessage());
  }

  @Test
  @DisplayName("Без период на отразяване — без предупреждението")
  void без_период_на_отразяване() {
    assertFalse(
        codes(BgSaftInvoiceFixtures.assemble(sale20and9())).contains("SAFT_INV_REPORTING_PERIOD"));
  }

  @Test
  @DisplayName("Продажба 20 % и 9 %: кодът, ставката и данъкът на ред, контролните суми")
  void продажба() {
    Result result = BgSaftInvoiceFixtures.assemble(sale20and9());
    assertEquals(List.of(), errors(result));

    SaftInvoiceSection sales = result.getSales();
    assertEquals(1L, sales.getNumberOfEntries());
    // Σ InvoiceLineAmount с D — БЕЗ TaxAmount (QA IV.2.5): 150.00, а не 174.50.
    assertEquals(d("150.00"), sales.getTotalDebit());
    assertEquals(d("0.00"), sales.getTotalCredit());

    SaftInvoice invoice = invoices(sales).get(0);
    assertEquals("S-1", invoice.getInvoiceNo());
    assertNull(invoice.getPartyRole(), "продажбата носи CustomerInfo по раздела");
    assertEquals("10130175000", invoice.getPartyId());
    assertEquals("Алфа ООД", invoice.getPartyName());
    assertEquals("411", invoice.getAccountId());
    assertEquals(APRIL_10, invoice.getInvoiceDate());
    assertEquals("01", invoice.getInvoiceType());
    assertEquals("N", invoice.getSelfBillingIndicator());
    assertEquals("MOV-S-1", invoice.getTransactionId());
    assertEquals(Integer.valueOf(4), invoice.getPeriod());
    assertEquals(Integer.valueOf(2026), invoice.getPeriodYear());

    assertEquals(2, invoice.getLines().size(), "заглавният ред не влиза");
    SaftInvoiceLine first = invoice.getLines().get(0);
    assertEquals("1", first.getLineNumber());
    assertEquals("702", first.getAccountId());
    assertEquals("P1", first.getProductCode());
    assertEquals("Стока", first.getDescription());
    assertEquals(d("2"), first.getQuantity());
    assertEquals(d("50.00"), first.getUnitPrice());
    assertEquals(APRIL_10, first.getTaxPointDate());
    assertEquals(d("100.00"), first.getInvoiceLineAmount().getAmount());
    assertEquals("EUR", first.getInvoiceLineAmount().getCurrencyCode());
    assertEquals("D", first.getDebitCreditIndicator());
    SaftTaxInformation tax20 = onlyTax(first);
    assertEquals("100", tax20.getTaxType());
    assertEquals("100211", tax20.getTaxCode());
    assertEquals(d("20"), tax20.getTaxPercentage());
    assertEquals(d("20.00"), tax20.getTaxAmount().getAmount());

    SaftTaxInformation tax9 = onlyTax(invoice.getLines().get(1));
    assertEquals("100213", tax9.getTaxCode());
    assertEquals(d("9"), tax9.getTaxPercentage());
    assertEquals(d("4.50"), tax9.getTaxAmount().getAmount());

    assertEquals(d("150.00"), invoice.getNetTotal());
    assertEquals(d("174.50"), invoice.getGrossTotal());
    assertEquals(2, invoice.getTaxInformationTotals().size());
    SaftTaxInformation total20 = invoice.getTaxInformationTotals().get(0);
    assertEquals("100211", total20.getTaxCode());
    assertEquals(d("100.00"), total20.getTaxBase());
    assertEquals(d("20.00"), total20.getTaxAmount().getAmount());

    // Покупки няма — нулевата структура, с предупреждение.
    assertEquals(0L, result.getPurchases().getNumberOfEntries());
    finding(result, "SAFT_INV_PURCHASES_EMPTY");
    assertFalse(codes(result).contains("SAFT_INV_SALES_EMPTY"));
  }

  @Test
  @DisplayName("Кредитно известие и отстъпка: C с положителна сума, данъкът в посоката на реда")
  void кредит() {
    Source source =
        new Source()
            .add(sale(3, "S-2", 4))
            .add(line(31, 3, 1, ACCOUNT_SALES, "30.00", "6.00").tax(SRS_20, "BG_SRS_20", d("20")))
            .add(invoiceTax(3, SRS_20, "6.00"))
            .add(sale(4, "S-3", 3))
            .add(line(41, 4, 1, ACCOUNT_SALES, "100.00", "20.00").tax(SRS_20, "BG_SRS_20", d("20")))
            .add(line(42, 4, 2, ACCOUNT_SALES, "-10.00", "-2.00").tax(SRS_20, "BG_SRS_20", d("20")))
            .add(invoiceTax(4, SRS_20, "18.00"));
    Result result = BgSaftInvoiceFixtures.assemble(source);
    assertEquals(List.of(), errors(result));
    List<SaftInvoice> sales = invoices(result.getSales());

    SaftInvoice credit = sales.get(0);
    assertEquals("03", credit.getInvoiceType());
    SaftInvoiceLine creditLine = credit.getLines().get(0);
    assertEquals("C", creditLine.getDebitCreditIndicator());
    assertEquals(d("30.00"), creditLine.getInvoiceLineAmount().getAmount());
    assertEquals(d("6.00"), onlyTax(creditLine).getTaxAmount().getAmount());
    assertEquals(d("-30.00"), credit.getNetTotal());
    assertEquals(d("-36.00"), credit.getGrossTotal());
    assertEquals(d("-30.00"), credit.getTaxInformationTotals().get(0).getTaxBase());
    assertEquals(d("-6.00"), credit.getTaxInformationTotals().get(0).getTaxAmount().getAmount());

    SaftInvoice discounted = sales.get(1);
    SaftInvoiceLine discount = discounted.getLines().get(1);
    assertEquals("C", discount.getDebitCreditIndicator());
    assertEquals(d("10.00"), discount.getInvoiceLineAmount().getAmount());
    assertEquals(d("2.00"), onlyTax(discount).getTaxAmount().getAmount());
    assertEquals(d("90.00"), discounted.getNetTotal());
    assertEquals(d("108.00"), discounted.getGrossTotal());

    // D = 100, C = 30 + 10 — без данъците.
    assertEquals(d("100.00"), result.getSales().getTotalDebit());
    assertEquals(d("40.00"), result.getSales().getTotalCredit());
  }

  // ─────────────── покупка ───────────────

  @Test
  @DisplayName(
      "Покупка: кредитът на ред, ред без данък, ДМА и разход с ProductCode 0, датата на доставчика")
  void покупка() {
    Source source =
        new Source()
            .add(purchase(2, "INV-77", BETA, 1))
            .add(
                line(21, 2, 1, ACCOUNT_MATERIALS, "200.00", "40.00")
                    .product(601L, "G1", "Гвоздеи")
                    .creditType("1")
                    .tax(SRP_20, "BG_SRP_20", d("20")))
            .add(
                line(22, 2, 2, ACCOUNT_EXPENSE, "100.00", "20.00")
                    .product(602L, "S1", "Наем")
                    .creditType("2")
                    .tax(SRP_20, "BG_SRP_20", d("20")))
            .add(
                line(23, 2, 3, ACCOUNT_EXPENSE, "10.00", "2.00")
                    .creditType("3")
                    .tax(SRP_20, "BG_SRP_20", d("20")))
            .add(
                line(24, 2, 4, ACCOUNT_EXPENSE, "50.00", "10.00").tax(SRP_20, "BG_SRP_20", d("20")))
            .add(
                line(25, 2, 5, ACCOUNT_EXPENSE, "5.00", "0").description("<p>Такса&nbsp;банка</p>"))
            .add(
                line(26, 2, 6, ACCOUNT_ASSET, "100.00", "9.00")
                    .product(603L, "M1", "Машина")
                    .creditType("1")
                    .tax(LRP_9, "BG_LRP_9", d("9")))
            .add(invoiceTax(2, SRP_20, "72.00"))
            .add(invoiceTax(2, LRP_9, "9.00"));
    Result result = BgSaftInvoiceFixtures.assemble(source);
    assertEquals(List.of(), errors(result));

    SaftInvoice invoice = invoices(result.getPurchases()).get(0);
    assertEquals("INV-77", invoice.getInvoiceNo(), "номерът на доставчика, както в дневника");
    assertEquals("11DE123456789", invoice.getPartyId());
    assertEquals("401", invoice.getAccountId());
    assertEquals(APRIL_5, invoice.getInvoiceDate(), "датата на документа на доставчика");

    List<SaftInvoiceLine> lines = invoice.getLines();
    assertEquals("100331", onlyTax(lines.get(0)).getTaxCode());
    assertEquals("G1", lines.get(0).getProductCode(), "запас — кодът на продукта");
    assertEquals("100332", onlyTax(lines.get(1)).getTaxCode());
    assertEquals("0", lines.get(1).getProductCode(), "разход — „0“ (Прил. 3 т. IV.6)");
    assertEquals("100330", onlyTax(lines.get(2)).getTaxCode());
    assertEquals(d("2.00"), onlyTax(lines.get(2)).getTaxAmount().getAmount());
    assertEquals("100331", onlyTax(lines.get(3)).getTaxCode(), "празен кредит → пълен");
    SaftTaxInformation untaxed = onlyTax(lines.get(4));
    assertEquals("100330", untaxed.getTaxCode(), "без данък в дневника за покупки (QA с. 81)");
    assertNull(untaxed.getTaxPercentage());
    assertEquals(d("0.00"), untaxed.getTaxAmount().getAmount());
    assertEquals("Такса банка", lines.get(4).getDescription());
    assertEquals("100331", onlyTax(lines.get(5)).getTaxCode(), "9 % — пак 100331");
    assertEquals(d("9"), onlyTax(lines.get(5)).getTaxPercentage());
    assertEquals("0", lines.get(5).getProductCode(), "ДМА — „0“ (QA с. 76)");
    assertEquals(APRIL_5, lines.get(0).getTaxPointDate());

    assertEquals(d("465.00"), result.getPurchases().getTotalDebit());
    assertEquals(d("465.00"), invoice.getNetTotal());
    assertEquals(d("546.00"), invoice.getGrossTotal());
    SaftTaxInformation full = invoice.getTaxInformationTotals().get(0);
    assertEquals("100331", full.getTaxCode());
    assertEquals(d("350.00"), full.getTaxBase());
    assertEquals(d("59.00"), full.getTaxAmount().getAmount());

    assertTrue(finding(result, "SAFT_INV_CREDIT_DEFAULTED").getMessage().contains("INV-77/4"));
    assertTrue(finding(result, "SAFT_INV_UNTAXED_REGISTERED").getMessage().contains("INV-77/5"));
    assertFalse(codes(result).contains("SAFT_INV_NOT_IN_MASTER"));
  }

  @Test
  @DisplayName("Ред без данък в продажба — 000/000000, не 100330")
  void продажба_без_данък() {
    Source source =
        new Source()
            .add(sale(5, "S-5", 3))
            .add(line(51, 5, 1, ACCOUNT_SALES, "10.00", "0").description("Необлагаемо"));
    SaftTaxInformation tax =
        onlyTax(
            invoices(BgSaftInvoiceFixtures.assemble(source).getSales()).get(0).getLines().get(0));
    assertEquals("000", tax.getTaxType());
    assertEquals("000000", tax.getTaxCode());
  }

  // ─────────────── данъкът на ред: най-големият остатък ───────────────

  private static List<BigDecimal> taxes(Source source) {
    Result result = BgSaftInvoiceFixtures.assemble(source);
    List<BigDecimal> taxes = new ArrayList<>();
    for (SaftInvoiceLine line : invoices(result.getSales()).get(0).getLines()) {
      taxes.add(onlyTax(line).getTaxAmount().getAmount());
    }
    return taxes;
  }

  @Test
  @DisplayName("Остатъкът до InvoiceLineTax: равни остатъци по реда, по-големият остатък печели")
  void остатъкът() {
    // 3 × 0.03 при 20 % = 3 × 0.006; фактурата е 0.02 → 0.01, 0.01, 0.00.
    Source equal = new Source().add(sale(6, "R-1", 3)).add(invoiceTax(6, SRS_20, "0.02"));
    for (int i = 1; i <= 3; i++) {
      equal.add(line(60 + i, 6, i, ACCOUNT_SALES, "0.03", "0").tax(SRS_20, "BG_SRS_20", d("20")));
    }
    assertEquals(List.of(d("0.01"), d("0.01"), d("0.00")), taxes(equal));

    // 0.08 → 0.016 (остатък 0.006), 0.09 → 0.018 (остатък 0.008); фактурата е 0.03 → вторият.
    Source largest =
        new Source()
            .add(sale(7, "R-2", 3))
            .add(line(71, 7, 1, ACCOUNT_SALES, "0.08", "0").tax(SRS_20, "BG_SRS_20", d("20")))
            .add(line(72, 7, 2, ACCOUNT_SALES, "0.09", "0").tax(SRS_20, "BG_SRS_20", d("20")))
            .add(invoiceTax(7, SRS_20, "0.03"));
    assertEquals(List.of(d("0.01"), d("0.02")), taxes(largest));

    // Повече от стотинка на ред — разминаване в данните, находка.
    Source residual =
        new Source()
            .add(sale(8, "R-3", 3))
            .add(line(81, 8, 1, ACCOUNT_SALES, "0.03", "0").tax(SRS_20, "BG_SRS_20", d("20")))
            .add(invoiceTax(8, SRS_20, "0.05"));
    Result result = BgSaftInvoiceFixtures.assemble(residual);
    assertEquals(
        d("0.05"),
        onlyTax(invoices(result.getSales()).get(0).getLines().get(0)).getTaxAmount().getAmount());
    assertTrue(finding(result, "SAFT_INV_TAX_RESIDUAL").getMessage().contains("R-3"));
  }

  // ─────────────── ГРЕШКИТЕ: нищо не изпада мълчаливо ───────────────

  private static Source broken() {
    Source source = new Source();
    // Обратно начисляване — протоколът е G5b.
    source
        .add(purchase(15, "RC-1", BETA, 1))
        .add(
            line(151, 15, 1, ACCOUNT_MATERIALS, "100.00", "20.00")
                .creditType("1")
                .tax(SRP_20, "BG_SRP_20", d("20")))
        .add(invoiceTax(15, SRP_20, "20.00"))
        .add(new InvoiceTaxRow(15L, 499L, d("-20.00"), true));
    // Данък от семейство „G5b“ без флаг за обратно начисляване.
    source
        .add(purchase(16, "VOP-1", BETA, 1))
        .add(
            line(161, 16, 1, ACCOUNT_MATERIALS, "100.00", "20.00")
                .tax(405L, "BG_VOP_ACQ", d("20")));
    // Маржова схема — нерешен код.
    source
        .add(sale(17, "M-1", 3))
        .add(line(171, 17, 1, ACCOUNT_SALES, "100.00", "20.00").tax(406L, "BG_MARGIN", d("20")));
    // Продажбен данък в покупка.
    source
        .add(purchase(18, "DIR-1", BETA, 1))
        .add(
            line(181, 18, 1, ACCOUNT_MATERIALS, "100.00", "20.00")
                .tax(SRS_20, "BG_SRS_20", d("20")));
    // 0 % без колона в Блок Б, към контрагент с ДДС номер от ЕС.
    source
        .add(sale(19, "Z-1", 3).partner(BETA, 2L))
        .add(line(191, 19, 1, ACCOUNT_SALES, "100.00", "0").tax(407L, "BG_ZRS", d("0")));
    // Вид 09 — протокол; вид 81 в покупка; без вид.
    source
        .add(sale(20, "T-9", 3).documentType("09"))
        .add(line(201, 20, 1, ACCOUNT_SALES, "1.00", "0.20").tax(SRS_20, "BG_SRS_20", d("20")));
    source
        .add(purchase(21, "R-81", BETA, 1).documentType("81"))
        .add(
            line(211, 21, 1, ACCOUNT_MATERIALS, "1.00", "0.20")
                .creditType("1")
                .tax(SRP_20, "BG_SRP_20", d("20")));
    source
        .add(sale(22, "NT-1", 3).documentType(null))
        .add(line(221, 22, 1, ACCOUNT_SALES, "1.00", "0.20").tax(SRS_20, "BG_SRS_20", d("20")));
    // Сметка без мапинг.
    source
        .add(sale(23, "ACC-1", 3))
        .add(line(231, 23, 1, ACCOUNT_UNMAPPED, "1.00", "0.20").tax(SRS_20, "BG_SRS_20", d("20")));
    // Касова отчетност — издадена: по отговора на НАП (19.10.2026) вече не е „чака НАП“.
    source
        .add(sale(25, "K-11", 3).documentType("11"))
        .add(line(251, 25, 1, ACCOUNT_SALES, "1.00", "0.20").tax(SRS_20, "BG_SRS_20", d("20")));
    // И една изрядна.
    source
        .add(sale(24, "OK-1", 3))
        .add(line(241, 24, 1, ACCOUNT_SALES, "10.00", "2.00").tax(SRS_20, "BG_SRS_20", d("20")))
        .add(invoiceTax(24, SRS_20, "2.00"));
    return source;
  }

  @Test
  @DisplayName("Фактура-протокол, нерешен код и вид, грешна посока, вид, сметка — ГРЕШКА")
  void грешките() {
    Result result = BgSaftInvoiceFixtures.assemble(broken());

    BgDeclarationFileFinding protocol = finding(result, "SAFT_INV_PROTOCOL_INVOICE");
    assertTrue(protocol.getMessage().contains("„Състави протокол по чл. 117“"));
    assertTrue(protocol.getMessage().contains("ръчна статия с ДДС операция"));
    assertTrue(protocol.getMessage().contains("RC-1 — обратно начисляване"));
    assertTrue(protocol.getMessage().contains("VOP-1 — BG_VOP_ACQ"));
    assertTrue(protocol.getMessage().contains("T-9 — вид 09"));
    // Маржата чака НАП — ГРЕШКА; издадената фактура по касова отчетност (K-11) вече не е там
    // (отговорът на НАП по имейл от 19.10.2026: 100211/100213).
    BgDeclarationFileFinding pending = finding(result, "SAFT_INV_NAP_PENDING");
    assertFalse(pending.getMessage().contains("K-11"), pending.getMessage());
    assertTrue(pending.getMessage().contains("M-1 — BG_MARGIN — маржова схема"));
    // Маржата не е в запитването от 05.10 — „чака становище … да се потвърди“, без датата.
    assertTrue(
        pending.getMessage().contains("100216) — " + BgSaftNapPending.OPINION),
        pending.getMessage());
    // ZRS вече има колона в Блок Б (к.19 → 100214): не е нерешен, остава само предупреждението.
    assertTrue(
        result.getFindings().stream()
            .noneMatch(
                f ->
                    f.getCode().equals("SAFT_INV_TAX_UNRESOLVED")
                        && f.getMessage().contains("BG_ZRS")));
    assertTrue(finding(result, "SAFT_INV_ZERO_RATE_EU").getMessage().contains("Z-1/1"));
    assertTrue(
        finding(result, "SAFT_INV_TAX_DIRECTION").getMessage().contains("DIR-1/1 BG_SRS_20"));
    assertTrue(finding(result, "SAFT_INV_TYPE_DIARY").getMessage().contains("R-81"));
    assertTrue(finding(result, "SAFT_INV_TYPE_MISSING").getMessage().contains("NT-1"));
    assertTrue(
        finding(result, "SAFT_INV_ACCOUNT_UNMAPPED").getMessage().contains("ACC-1/1 S.I.30"));
    for (String code :
        List.of(
            "SAFT_INV_PROTOCOL_INVOICE",
            "SAFT_INV_NAP_PENDING",
            "SAFT_INV_TAX_DIRECTION",
            "SAFT_INV_TYPE_DIARY",
            "SAFT_INV_TYPE_MISSING",
            "SAFT_INV_ACCOUNT_UNMAPPED")) {
      assertTrue(finding(result, code).isBlocking(), code + " блокира");
    }

    // В секциите са изрядната, Z-1 и K-11: ZRS вече има колона в Блок Б (к.19 → 100214), тоест
    // не е нерешен; издадената фактура по касова отчетност е 100211 (отговорът на НАП по имейл от
    // 19.10.2026). Предупреждението за контрагент от ЕС не блокира. Покупките са нулевата
    // структура.
    assertEquals(
        List.of("K-11", "OK-1", "Z-1"),
        invoices(result.getSales()).stream()
            .map(SaftInvoice::getInvoiceNo)
            .collect(Collectors.toList()));
    // K-11 1.00 + OK-1 10.00 + Z-1 100.00 (0 %, без данък).
    assertEquals(d("111.00"), result.getSales().getTotalDebit());
    assertEquals(0L, result.getPurchases().getNumberOfEntries());
  }

  // ─────────────── обратното начисляване по присъдата на дневника ───────────────

  /** Покупна фактура за ВОП 1000 / 20 % — двата данъчни реда, както ги прави Axelor. */
  private static Source reverseCharge(BgSaftInvoicesAssembler.DiaryVerdict verdict) {
    Source source = new Source();
    source
        .add(purchase(40, "VOP-40", BETA, 1).diaryVerdict(verdict))
        .add(
            line(401, 40, 1, ACCOUNT_MATERIALS, "1000.00", "1200.00")
                .creditType("1")
                .tax(405L, "BG_VOP_ACQ", d("20")))
        .add(new InvoiceTaxRow(40L, 405L, d("200.00"), false))
        .add(new InvoiceTaxRow(40L, 499L, d("-200.00"), true));
    // И една изрядна, за да не е секцията нулевата структура.
    source
        .add(sale(24, "OK-1", 3))
        .add(line(241, 24, 1, ACCOUNT_SALES, "10.00", "2.00").tax(SRS_20, "BG_SRS_20", d("20")))
        .add(invoiceTax(24, SRS_20, "2.00"));
    return source;
  }

  /**
   * ВОП с протокол: дневникът не носи фактурата (номерът и датата ѝ са в протокола — ЗДДС чл. 117
   * ал. 2 т. 9), затова и PurchaseInvoices — Прил. 3 т. IV.7: там са документите от дневника за
   * покупки. Протоколът е в двете секции от реда си в дневника (G5b). ПРЕДУПРЕЖДЕНИЕ, не ГРЕШКА.
   */
  @Test
  @DisplayName("ВОП с протокол — фактурата не е в PurchaseInvoices, ПРЕДУПРЕЖДЕНИЕ вместо ГРЕШКА")
  void обратното_начисляване_с_протокол() {
    Result result =
        BgSaftInvoiceFixtures.assemble(
            reverseCharge(new BgSaftInvoicesAssembler.DiaryVerdict(false, List.of())));

    assertEquals(List.of(), errors(result));
    assertEquals(0L, result.getPurchases().getNumberOfEntries());
    BgDeclarationFileFinding note = finding(result, "SAFT_INV_REVERSE_CHARGE_BY_PROTOCOL");
    assertFalse(note.isBlocking());
    assertTrue(note.getMessage().contains("VOP-40"), note.getMessage());
    assertTrue(note.getMessage().contains("Прил. 3 т. IV.7"), note.getMessage());
  }

  @Test
  @DisplayName("Без протокол — ГРЕШКА с причината на дневника")
  void обратното_начисляване_без_протокол() {
    Result result =
        BgSaftInvoiceFixtures.assemble(
            reverseCharge(
                new BgSaftInvoicesAssembler.DiaryVerdict(
                    false,
                    List.of("VAT_INVOICE_PROTOCOL_MISSING: Фактура с обратно начисляване без"))));

    BgDeclarationFileFinding protocol = finding(result, "SAFT_INV_PROTOCOL_INVOICE");
    assertTrue(protocol.isBlocking());
    assertTrue(
        protocol
            .getMessage()
            .contains("VOP-40 — обратно начисляване — дневникът: VAT_INVOICE_PROTOCOL_MISSING"),
        protocol.getMessage());
    assertTrue(
        result.getFindings().stream()
            .noneMatch(f -> f.getCode().equals("SAFT_INV_REVERSE_CHARGE_BY_PROTOCOL")));
  }

  @Test
  @DisplayName(
      "163а в дневника само с колони 1–8а, но с данък извън Прил. № 2 (ВОП) — ГРЕШКА „протокол“;"
          + " 100330 е само за отпадъците и зърното (BgSaftNapAnswerInvoicesTest)")
  void обратното_начисляване_по_чл163а() {
    Result result =
        BgSaftInvoiceFixtures.assemble(
            reverseCharge(new BgSaftInvoicesAssembler.DiaryVerdict(true, List.of())));

    BgDeclarationFileFinding protocol = finding(result, "SAFT_INV_PROTOCOL_INVOICE");
    assertTrue(protocol.isBlocking());
    assertTrue(protocol.getMessage().contains("VOP-40 — BG_VOP_ACQ"), protocol.getMessage());
    BgDeclarationFileFinding own =
        result.getFindings().stream()
            .filter(f -> f.getCode().equals("SAFT_INV_PROTOCOL_INVOICE"))
            .findFirst()
            .get();
    assertEquals(BgSaftRef.INVOICE, own.getTargetModel());
    assertEquals(40L, own.getTargetId());
  }

  @Test
  @DisplayName("к.8а 41/43/46/48 (складиране до поискване, ЗДДС чл. 15а) — ГРЕШКА, не ВОД 100215")
  void складиране_до_поискване() {
    // Контролата: същата ВОД без к.8а излиза със 100215.
    Source control = new Source();
    control
        .add(sale(30, "VOD-1", 3).partner(BETA, 2L))
        .add(line(301, 30, 1, ACCOUNT_SALES, "100.00", "0").tax(408L, "BG_VOD", d("0")));
    Result plain = BgSaftInvoiceFixtures.assemble(control);
    assertEquals(List.of(), errors(plain));
    assertEquals(
        "100215", onlyTax(invoices(plain.getSales()).get(0).getLines().get(0)).getTaxCode());

    for (String code : List.of("41", "43", "46", "48")) {
      assertTrue(BgVatValidator.isCallOffStockSupplyType(code), code);
      Source source = new Source();
      source
          .add(sale(30, "CO-" + code, 3).partner(BETA, 2L).supplyType(code))
          .add(line(301, 30, 1, ACCOUNT_SALES, "100.00", "0").tax(408L, "BG_VOD", d("0")));
      Result result = BgSaftInvoiceFixtures.assemble(source);
      BgDeclarationFileFinding found = finding(result, "SAFT_INV_CALL_OFF_STOCK");
      assertTrue(found.isBlocking(), code);
      assertTrue(found.getMessage().contains("CO-" + code + " (к.8а " + code + ")"), code);
      assertEquals(0L, result.getSales().getNumberOfEntries(), code);
    }
    // 01/02 (чл. 163а) и 03 (чл. 167а) не са складиране.
    for (String code : List.of("01", "02", "03", "07", "08")) {
      assertFalse(BgVatValidator.isCallOffStockSupplyType(code), code);
    }
  }

  @Test
  @DisplayName("Празен TaxType/TaxCode не излиза НИКОГА (QA IV.2.3)")
  void никога_празен_таг() {
    Result result = BgSaftInvoiceFixtures.assemble(broken());
    List<String> blank = new ArrayList<>();
    for (SaftInvoiceSection section : List.of(result.getSales(), result.getPurchases())) {
      for (SaftInvoice invoice : invoices(section)) {
        for (SaftInvoiceLine line : invoice.getLines()) {
          assertFalse(line.getTaxInformation().isEmpty(), invoice.getInvoiceNo());
          for (SaftTaxInformation tax : line.getTaxInformation()) {
            if (tax.getTaxType() == null
                || tax.getTaxType().trim().isEmpty()
                || tax.getTaxCode() == null
                || tax.getTaxCode().trim().isEmpty()) {
              blank.add(invoice.getInvoiceNo());
            }
          }
        }
      }
    }
    assertEquals(List.of(), blank);
    // И XML-ът: нито <TaxType/>, нито <TaxCode/>.
    String xml = new String(BgSaftXmlWriter.toBytes(file(result)), StandardCharsets.UTF_8);
    assertFalse(
        xml.contains("<nsSAFT:TaxType/>") || xml.contains("<nsSAFT:TaxType></nsSAFT:TaxType>"));
    assertFalse(
        xml.contains("<nsSAFT:TaxCode/>") || xml.contains("<nsSAFT:TaxCode></nsSAFT:TaxCode>"));
  }

  // ─────────────── месец без документи (QA IV.2.6) ───────────────

  @Test
  @DisplayName("Месец без продажби и покупки — нулевата структура на QA дословно, в евро")
  void нулевата_структура() {
    Result result = BgSaftInvoiceFixtures.assemble(new Source());
    assertEquals(List.of(), errors(result));
    finding(result, "SAFT_INV_SALES_EMPTY");
    finding(result, "SAFT_INV_PURCHASES_EMPTY");
    for (SaftInvoiceSection section : List.of(result.getSales(), result.getPurchases())) {
      assertEquals(0L, section.getNumberOfEntries());
      assertEquals(d("0.00"), section.getTotalDebit());
      assertEquals(d("0.00"), section.getTotalCredit());
      SaftInvoice empty = invoices(section).get(0);
      assertEquals("1", empty.getInvoiceNo());
      assertEquals(SaftInvoice.PartyRole.CUSTOMER, empty.getPartyRole());
      assertEquals("0", empty.getPartyId());
      assertEquals(" ", empty.getPartyName());
      assertEquals("", empty.getBillingAddress().getCity());
      assertEquals("BG", empty.getBillingAddress().getCountry());
      assertEquals("998", empty.getAccountId());
      assertEquals("1900-01-01", empty.getInvoiceDate().toString());
      assertEquals("01", empty.getInvoiceType());
      assertEquals("1", empty.getTransactionId());
      SaftInvoiceLine line = empty.getLines().get(0);
      assertEquals("998", line.getAccountId());
      assertEquals("0", line.getProductCode());
      assertEquals("0", line.getInvoiceUom());
      assertEquals(BigDecimal.ONE, line.getUomToUomBaseConversionFactor());
      assertEquals("Продажба", line.getDescription());
      assertEquals("EUR", line.getInvoiceLineAmount().getCurrencyCode());
      assertEquals("000000", onlyTax(line).getTaxCode());
    }
  }

  // ─────────────── схемата ───────────────

  private static SaftAuditFile file(Result result) {
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getSourceDocuments()
        .salesInvoices(result.getSales())
        .purchaseInvoices(result.getPurchases());
    return file;
  }

  @Test
  @DisplayName("Сглобените продажби и покупки минават схемата — и нулевата структура също")
  void схемата() {
    Source source = sale20and9();
    source
        .add(purchase(2, "INV-77", BETA, 1))
        .add(
            line(21, 2, 1, ACCOUNT_MATERIALS, "200.00", "40.00")
                .product(601L, "G1", "Гвоздеи")
                .creditType("1")
                .tax(SRP_20, "BG_SRP_20", d("20")))
        .add(invoiceTax(2, SRP_20, "40.00"));
    assertEquals(
        List.of(),
        BgSaftFixtures.validate(
            BgSaftXmlWriter.toBytes(file(BgSaftInvoiceFixtures.assemble(source)))));
    assertEquals(
        List.of(),
        BgSaftFixtures.validate(
            BgSaftXmlWriter.toBytes(file(BgSaftInvoiceFixtures.assemble(new Source())))));
  }
}

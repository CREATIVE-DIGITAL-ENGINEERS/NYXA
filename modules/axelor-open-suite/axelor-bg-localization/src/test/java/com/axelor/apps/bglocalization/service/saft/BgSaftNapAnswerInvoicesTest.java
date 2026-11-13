package com.axelor.apps.bglocalization.service.saft;

import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.ACCOUNT_EXPENSE;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.ACCOUNT_MATERIALS;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.ACCOUNT_SALES;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.APRIL_5;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.BETA;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.LRS_9;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.SRP_20;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.SRS_20;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.invoiceTax;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.line;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.purchase;
import static com.axelor.apps.bglocalization.service.saft.BgSaftInvoiceFixtures.sale;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.BgSaftInvoicesAssembler.DiaryVerdict;
import com.axelor.apps.bglocalization.service.saft.BgSaftInvoicesAssembler.InvoiceTaxRow;
import com.axelor.apps.bglocalization.service.saft.BgSaftInvoicesAssembler.Result;
import com.axelor.apps.bglocalization.service.saft.BgSaftInvoicesAssembler.Source;
import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXmlWriter;
import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXsd;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAuditFile;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoice;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoiceSection;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxInformation;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ФАКТУРИТЕ ПО ОТГОВОРА НА НАП ПО ИМЕЙЛ ОТ 19.10.2026 — по един тест на код.
 *
 * <ul>
 *   <li>получената фактура по чл. 163а (Прил. № 2) → PurchaseInvoices 100330, без данък;
 *   <li>касова отчетност: издадените 11/12/13 → 100211/100213; получените 11/12/13 и протоколите
 *       91/93 при дружество ИЗВЪН режима → 100330/100331/100332 по кредита; при дружество ПО режима
 *       към датата на документа → ГРЕШКА „да се потвърди“.
 * </ul>
 *
 * <p>Протоколите по чл. 163а и чл. 167а (покупната страна — 100346/100345/100347 с ползвания
 * кредит) са в {@link BgSaftInvoicesParityTest}: там минават през истинския колектор.
 */
class BgSaftNapAnswerInvoicesTest {

  /** Периодът на режима покрива датата на покупките на фикстурите ({@code originDate} 05.04). */
  private static final BgSaftCashAccounting IN_REGIME =
      BgSaftCashAccounting.of(LocalDate.of(2026, 4, 1), null);

  /** Режимът е прекратен преди датата на покупките (последен ден 04.04). */
  private static final BgSaftCashAccounting ENDED =
      BgSaftCashAccounting.of(LocalDate.of(2025, 1, 1), LocalDate.of(2026, 4, 4));

  private static final long RCM_TAX_LINE = 406L;
  private static final long CASH_PROTOCOL_TAX_LINE = 407L;

  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }

  private static List<SaftInvoice> invoices(SaftInvoiceSection section) {
    List<SaftInvoice> list = new ArrayList<>();
    section.getInvoices().forEach(list::add);
    return list;
  }

  private static SaftInvoice only(SaftInvoiceSection section) {
    List<SaftInvoice> list = invoices(section);
    assertEquals(1, list.size(), "един документ в секцията");
    return list.get(0);
  }

  private static SaftTaxInformation tax(SaftInvoice invoice) {
    assertEquals(1, invoice.getLines().size());
    assertEquals(1, invoice.getLines().get(0).getTaxInformation().size());
    return invoice.getLines().get(0).getTaxInformation().get(0);
  }

  private static List<String> errors(Result result) {
    return result.getFindings().stream()
        .filter(BgDeclarationFileFinding::isBlocking)
        .map(f -> f.getCode() + ": " + f.getMessage())
        .collect(Collectors.toList());
  }

  private static BgDeclarationFileFinding finding(Result result, String code) {
    return BgSaftTestFindings.merged(result.getFindings(), code)
        .orElseThrow(() -> new AssertionError("няма находка " + code + ": " + errors(result)));
  }

  /** Изрядна продажба, за да не е секцията нулевата структура. */
  private static Source withControl(Source source) {
    return source
        .add(sale(24, "OK-1", 3))
        .add(line(241, 24, 1, ACCOUNT_SALES, "10.00", "2.00").tax(SRS_20, "BG_SRS_20", d("20")))
        .add(invoiceTax(24, SRS_20, "2.00"));
  }

  // ─────────────── чл. 163а: получената фактура ───────────────

  /** Получена фактура по чл. 163а — данъкът е на получателя, на фактурата ДДС няма. */
  private static Source received163a(String tax) {
    return new Source()
        .add(purchase(50, "RC-163A", BETA, 1).diaryVerdict(new DiaryVerdict(true, List.of())))
        .add(
            line(501, 50, 1, ACCOUNT_MATERIALS, "500.00", "500.00")
                .creditType("1")
                .tax(RCM_TAX_LINE, tax, d("20")))
        .add(new InvoiceTaxRow(50L, RCM_TAX_LINE, d("100.00"), true));
  }

  @Test
  @DisplayName("Получена фактура по чл. 163а, част I (отпадъци) → PurchaseInvoices 100330, данък 0")
  void чл_163а_фактурата_част_I() {
    Result result = BgSaftInvoiceFixtures.assemble(received163a("BG_RCM_SCRAP_P"));
    assertEquals(List.of(), errors(result));
    SaftInvoice invoice = only(result.getPurchases());
    assertEquals("01", invoice.getInvoiceType());
    assertEquals("100330", tax(invoice).getTaxCode());
    assertEquals(0, tax(invoice).getTaxAmount().getAmount().signum(), "на фактурата ДДС няма");
    assertEquals(d("500.00"), invoice.getLines().get(0).getInvoiceLineAmount().getAmount());
  }

  @Test
  @DisplayName("Получена фактура по чл. 163а, част II (зърно) → PurchaseInvoices 100330, данък 0")
  void чл_163а_фактурата_част_II() {
    Result result = BgSaftInvoiceFixtures.assemble(received163a("BG_RCM_GRAIN_P"));
    assertEquals(List.of(), errors(result));
    assertEquals("100330", tax(only(result.getPurchases())).getTaxCode());
    assertEquals(0, tax(only(result.getPurchases())).getTaxAmount().getAmount().signum());
  }

  @Test
  @DisplayName("Обратно начисляване извън Прил. № 2 (СМР) — остава ГРЕШКА „да се потвърди“")
  void извън_приложение_2() {
    Result result = BgSaftInvoiceFixtures.assemble(withControl(received163a("BG_RCM_BUILD_P")));
    BgDeclarationFileFinding pending = finding(result, "SAFT_INV_NAP_PENDING");
    assertTrue(pending.getMessage().contains("извън Прил. № 2"), pending.getMessage());
    assertTrue(pending.getMessage().contains(BgSaftNapPending.OPINION), pending.getMessage());
    assertEquals(0L, result.getPurchases().getNumberOfEntries());
  }

  @Test
  @DisplayName("Златото и маржовата схема остават ГРЕШКА „да се потвърди“ (отделно запитване)")
  void злато_и_марж() {
    for (String taxCode : List.of("BG_GOLD_ART163G", "BG_MARGIN")) {
      Source source =
          withControl(
              new Source()
                  .add(sale(60, "X-" + taxCode, 3))
                  .add(line(601, 60, 1, ACCOUNT_SALES, "100.00", "0").tax(408L, taxCode, d("0"))));
      BgDeclarationFileFinding pending =
          finding(BgSaftInvoiceFixtures.assemble(source), "SAFT_INV_NAP_PENDING");
      assertTrue(pending.getMessage().contains(BgSaftNapPending.OPINION), pending.getMessage());
    }
  }

  // ─────────────── касова отчетност: издадените ───────────────

  @Test
  @DisplayName("Издадена фактура по касова отчетност (вид 11), 20 % → SalesInvoices 100211")
  void касова_издадена_фактура_11() {
    Result result =
        BgSaftInvoiceFixtures.assemble(
            new Source()
                .add(sale(70, "K-11", 3).documentType("11"))
                .add(
                    line(701, 70, 1, ACCOUNT_SALES, "100.00", "20.00")
                        .tax(SRS_20, "BG_SRS_20", d("20")))
                .add(invoiceTax(70, SRS_20, "20.00")));
    assertEquals(List.of(), errors(result));
    SaftInvoice invoice = only(result.getSales());
    assertEquals("11", invoice.getInvoiceType());
    assertEquals("100211", tax(invoice).getTaxCode());
    assertEquals(d("20.00"), tax(invoice).getTaxAmount().getAmount());
  }

  @Test
  @DisplayName("Издадено дебитно известие по касова отчетност (вид 12) → SalesInvoices 100211")
  void касова_издадено_известие_12() {
    Result result =
        BgSaftInvoiceFixtures.assemble(
            new Source()
                .add(sale(71, "K-12", 3).documentType("12"))
                .add(
                    line(711, 71, 1, ACCOUNT_SALES, "10.00", "2.00")
                        .tax(SRS_20, "BG_SRS_20", d("20")))
                .add(invoiceTax(71, SRS_20, "2.00")));
    assertEquals(List.of(), errors(result));
    assertEquals("12", only(result.getSales()).getInvoiceType());
    assertEquals("100211", tax(only(result.getSales())).getTaxCode());
  }

  @Test
  @DisplayName(
      "Издадено кредитно известие по касова отчетност (вид 13), 9 % → SalesInvoices 100213")
  void касова_издадено_известие_13_9() {
    Result result =
        BgSaftInvoiceFixtures.assemble(
            new Source()
                .add(sale(72, "K-13", 4).documentType("13"))
                .add(
                    line(721, 72, 1, ACCOUNT_SALES, "10.00", "0.90").tax(LRS_9, "BG_LRS_9", d("9")))
                .add(invoiceTax(72, LRS_9, "0.90")));
    assertEquals(List.of(), errors(result));
    assertEquals("13", only(result.getSales()).getInvoiceType());
    assertEquals("100213", tax(only(result.getSales())).getTaxCode());
  }

  // ─────────────── касова отчетност: получените ───────────────

  private static Source receivedCash(String type, String creditType, String taxCode, long taxLine) {
    return withControl(
        new Source()
            .add(purchase(80, "K-IN", BETA, 1).documentType(type))
            .add(
                line(801, 80, 1, ACCOUNT_EXPENSE, "100.00", "20.00")
                    .creditType(creditType)
                    .tax(taxLine, taxCode, d("20")))
            .add(invoiceTax(80, taxLine, "20.00")));
  }

  private static SaftInvoice receivedCashPurchase(
      BgSaftCashAccounting cash, String type, String creditType, String taxCode, long taxLine) {
    Result result =
        BgSaftInvoiceFixtures.assemble(
            receivedCash(type, creditType, taxCode, taxLine).cashAccounting(cash));
    assertEquals(List.of(), errors(result));
    SaftInvoice invoice = only(result.getPurchases());
    assertEquals(type, invoice.getInvoiceType());
    return invoice;
  }

  @Test
  @DisplayName(
      "Получена фактура по касова отчетност, дружеството извън режима, пълен кредит → 100331")
  void касова_получена_пълен_кредит() {
    SaftInvoice invoice =
        receivedCashPurchase(BgSaftCashAccounting.NONE, "11", "1", "BG_SRP_20", SRP_20);
    assertEquals("100331", tax(invoice).getTaxCode());
    assertEquals(d("20.00"), tax(invoice).getTaxAmount().getAmount());
  }

  @Test
  @DisplayName("Получено дебитно известие по касова отчетност, частичен кредит → 100332")
  void касова_получена_частичен_кредит() {
    assertEquals(
        "100332",
        tax(receivedCashPurchase(BgSaftCashAccounting.NONE, "12", "2", "BG_SRP_20", SRP_20))
            .getTaxCode());
  }

  @Test
  @DisplayName("Получено кредитно известие по касова отчетност, без кредит → 100330")
  void касова_получена_без_кредит() {
    assertEquals(
        "100330",
        tax(receivedCashPurchase(BgSaftCashAccounting.NONE, "13", "3", "BG_SRP_20", SRP_20))
            .getTaxCode());
  }

  @Test
  @DisplayName("Режимът на дружеството е прекратен преди датата на документа → кодът по кредита")
  void касова_получена_след_прекратяване() {
    assertEquals(
        "100331", tax(receivedCashPurchase(ENDED, "11", "1", "BG_SRP_20", SRP_20)).getTaxCode());
  }

  @Test
  @DisplayName("Получен протокол по чл. 151в ал. 3 (вид 91) с BG_PROTO_151V → по кредита, 100331")
  void касова_протокол_91() {
    assertEquals(
        "100331",
        tax(receivedCashPurchase(
                BgSaftCashAccounting.NONE,
                "91",
                "1",
                BgSaftInvoiceTaxRouter.CASH_PROTOCOL_TAX,
                CASH_PROTOCOL_TAX_LINE))
            .getTaxCode());
  }

  @Test
  @DisplayName("Получен протокол по чл. 151в ал. 7 (вид 93), частичен кредит → 100332")
  void касова_протокол_93() {
    assertEquals(
        "100332",
        tax(receivedCashPurchase(
                BgSaftCashAccounting.NONE,
                "93",
                "2",
                BgSaftInvoiceTaxRouter.CASH_PROTOCOL_TAX,
                CASH_PROTOCOL_TAX_LINE))
            .getTaxCode());
  }

  @Test
  @DisplayName("Дружеството е по касова отчетност към датата — получените 11 и 91 са ГРЕШКА")
  void касова_получена_при_дружество_по_режима() {
    for (String[] c :
        new String[][] {
          {"11", "BG_SRP_20", String.valueOf(SRP_20)},
          {"91", BgSaftInvoiceTaxRouter.CASH_PROTOCOL_TAX, String.valueOf(CASH_PROTOCOL_TAX_LINE)}
        }) {
      Result result =
          BgSaftInvoiceFixtures.assemble(
              receivedCash(c[0], "1", c[1], Long.parseLong(c[2])).cashAccounting(IN_REGIME));
      BgDeclarationFileFinding pending = finding(result, "SAFT_INV_NAP_PENDING");
      assertTrue(pending.isBlocking());
      assertTrue(
          pending.getMessage().contains("вид " + c[0] + " — дружеството е по касова отчетност"),
          pending.getMessage());
      assertTrue(pending.getMessage().contains(APRIL_5.toString()), pending.getMessage());
      assertTrue(
          pending.getMessage().contains(BgSaftNapPending.OWN_CASH_ACCOUNTING),
          pending.getMessage());
      assertEquals(0L, result.getPurchases().getNumberOfEntries(), c[0]);
    }
  }

  @Test
  @DisplayName("Протокол по чл. 151в в ПРОДАЖБИТЕ (вид 91) — НАП не го е описал: остава ГРЕШКА")
  void касова_протокол_в_продажбите() {
    Result result =
        BgSaftInvoiceFixtures.assemble(
            withControl(
                new Source()
                    .add(sale(90, "K-91", 3).documentType("91"))
                    .add(
                        line(901, 90, 1, ACCOUNT_SALES, "100.00", "20.00")
                            .tax(SRS_20, "BG_SRS_20", d("20")))));
    assertTrue(finding(result, "SAFT_INV_PROTOCOL_INVOICE").getMessage().contains("K-91 — вид 91"));
  }

  // ─────────────── схемата ───────────────

  @Test
  @DisplayName("Новите документи минават и XSD 1.0.2, и XSD 1.0.3")
  void схемата() {
    Source source =
        received163a("BG_RCM_SCRAP_P")
            .add(sale(70, "K-11", 3).documentType("11"))
            .add(
                line(701, 70, 1, ACCOUNT_SALES, "100.00", "20.00")
                    .tax(SRS_20, "BG_SRS_20", d("20")))
            .add(invoiceTax(70, SRS_20, "20.00"))
            .add(purchase(80, "K-IN", BETA, 1).documentType("91"))
            .add(
                line(801, 80, 1, ACCOUNT_EXPENSE, "100.00", "20.00")
                    .creditType("1")
                    .tax(CASH_PROTOCOL_TAX_LINE, BgSaftInvoiceTaxRouter.CASH_PROTOCOL_TAX, d("20")))
            .add(invoiceTax(80, CASH_PROTOCOL_TAX_LINE, "20.00"));
    Result result = BgSaftInvoiceFixtures.assemble(source);
    assertEquals(List.of(), errors(result));
    assertEquals(1L, result.getSales().getNumberOfEntries());
    assertEquals(2L, result.getPurchases().getNumberOfEntries());
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getSourceDocuments()
        .salesInvoices(result.getSales())
        .purchaseInvoices(result.getPurchases());
    byte[] xml = BgSaftXmlWriter.toBytes(file);
    assertEquals(List.of(), BgSaftFixtures.validate(xml, BgSaftXsd.V1_0_2), "XSD 1.0.2");
    assertEquals(List.of(), BgSaftFixtures.validate(xml, BgSaftXsd.V1_0_3), "XSD 1.0.3");
  }
}

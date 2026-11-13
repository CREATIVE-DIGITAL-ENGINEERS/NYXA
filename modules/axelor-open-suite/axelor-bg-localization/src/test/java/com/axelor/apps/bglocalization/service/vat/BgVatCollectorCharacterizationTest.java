package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoiceLineTax;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatCounterpartyId;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatAmountDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

/**
 * Характеризиращи тестове на маршрутизацията в {@link BgVatDocumentCollector}: кредитният тип на
 * реда ({@code accumulatePurchaseColumns}) и ДДС операцията на ръчния запис ({@code
 * routeMoveOperation}).
 *
 * <p>Те НЕ казват какво е правилно — заковават какво колекторът прави ДНЕС, включително заварените
 * мълчания (непознат кредитен тип без предупреждение, тристранната операция без ДДС, мъртвата
 * резервна стойност на фактурата). Всеки тест сравнява ЦЕЛИЯ документ като един низ — колони, мащаб
 * на сумите, totalVat и предупрежденията дословно — тъй че извадката на двете функции се доказва с
 * това, че този файл остава непроменен и зелен.
 *
 * <p>Пътищата, които {@link BgVatMoveCollectorTest} вече свидетелства (PURCHASE_FULL, VOP,
 * SERVICE_EU, ART163A, смесен ред „1“+„3“, чл. 79/79а), не се повтарят.
 */
class BgVatCollectorCharacterizationTest {

  /**
   * Носителят на протокола (ред по 4531) без кредитен тип: пълен кредит с предупреждение, както при
   * ред на фактура (ППЗДДС чл. 97а ал. 4 б. „б“ — колоната е по кредитния тип на реда).
   */
  private static final String CREDIT_DEFAULTED =
      "Ред без вид данъчен кредит — приет пълен ДК (move line null)";

  private final BgVatDocumentCollector collector = new BgVatDocumentCollector(null);

  // ---------------------------------------------------------------------------
  // Помощници
  // ---------------------------------------------------------------------------

  private static MoveLine line(
      String accountCode, String debit, String taxRate, String taxAmount, String creditType) {
    MoveLine l = new MoveLine();
    Account a = new Account();
    a.setCode(accountCode);
    l.setAccount(a);
    l.setDebit(new BigDecimal(debit));
    l.setCredit(BigDecimal.ZERO);
    if (taxRate != null) {
      l.setTaxRate(new BigDecimal(taxRate));
    }
    if (taxAmount != null) {
      l.setTaxAmount(new BigDecimal(taxAmount));
    }
    l.setBgVatCreditType(creditType);
    return l;
  }

  private static MoveLine creditLine(String accountCode, String credit, String taxRate) {
    MoveLine l = line(accountCode, "0", taxRate, null, null);
    l.setCredit(new BigDecimal(credit));
    return l;
  }

  private static Move move(String operation, MoveLine... lines) {
    Move move = new Move();
    move.setBgVatMoveVatOperation(operation);
    move.setMoveLineList(new ArrayList<>(Arrays.asList(lines)));
    return move;
  }

  private static InvoiceLine invoiceLine(String creditType, String companyEx, String companyIn) {
    InvoiceLine l = new InvoiceLine();
    l.setBgVatCreditType(creditType);
    if (companyEx != null) {
      l.setCompanyExTaxTotal(new BigDecimal(companyEx));
    }
    if (companyIn != null) {
      l.setCompanyInTaxTotal(new BigDecimal(companyIn));
    }
    return l;
  }

  private static Invoice invoice(int operationType, InvoiceLine... lines) {
    Invoice invoice = new Invoice();
    invoice.setOperationTypeSelect(operationType);
    invoice.setInvoiceLineList(new ArrayList<>(Arrays.asList(lines)));
    return invoice;
  }

  private BgVatDocumentDto fromMove(Move move) {
    BgVatDocumentDto doc = new BgVatDocumentDto();
    collector.enrichFromMove(doc, move);
    return doc;
  }

  private BgVatDocumentDto fromInvoice(Invoice invoice) {
    BgVatDocumentDto doc = new BgVatDocumentDto();
    collector.enrichFromInvoice(doc, invoice);
    return doc;
  }

  /** Целият маршрутизиран резултат като един низ — мащабът на всяка сума се вижда. */
  private static String snapshot(BgVatDocumentDto d) {
    StringBuilder amounts = new StringBuilder();
    for (BgVatAmountDto a : d.getAmounts()) {
      amounts
          .append('(')
          .append(a.getTaxCode())
          .append(' ')
          .append(a.getVatRatePercent())
          .append(' ')
          .append(a.getBase())
          .append(' ')
          .append(a.getVatAmount())
          .append(')');
    }
    return "P"
        + d.getPurchaseAmounts()
        + " S"
        + d.getSalesAmounts()
        + " vat="
        + d.getTotalVat()
        + " base="
        + d.getTotalBase()
        + " gross="
        + d.getTotalGross()
        + " A["
        + amounts
        + "] W"
        + d.getWarnings();
  }

  // ---------------------------------------------------------------------------
  // Кредитен тип на реда — ръчен запис, режим 1
  // ---------------------------------------------------------------------------

  @Test
  void credit2_routesToPartialK12K13() {
    assertEquals(
        "P{K12_BASE_PARTIAL_CREDIT=100, K13_VAT_PARTIAL_CREDIT=20} S{} vat=0 base=null"
            + " gross=null A[] W[]",
        snapshot(fromMove(move(null, line("601", "100", null, "20", "2")))));
  }

  @Test
  void credit2_withSurroundingSpaces_isTrimmed() {
    assertEquals(
        "P{K12_BASE_PARTIAL_CREDIT=100, K13_VAT_PARTIAL_CREDIT=20} S{} vat=0 base=null"
            + " gross=null A[] W[]",
        snapshot(fromMove(move(null, line("601", "100", null, "20", " 2 ")))));
  }

  /** Заварено мълчание: непознат код пада на пълен кредит БЕЗ предупреждение. */
  @Test
  void unknownCredit_fallsToFullCredit_silently() {
    assertEquals(
        "P{K10_BASE_FULL_CREDIT=100, K11_VAT_FULL_CREDIT=20} S{} vat=0 base=null gross=null A[]"
            + " W[]",
        snapshot(fromMove(move(null, line("601", "100", null, "20", "9")))));
  }

  /**
   * Кредит само от интервали НЕ отваря режим 1 — записът пада в остатъчния режим 3. Данъкът на реда
   * е {@code taxLineSet}: {@code taxAmount} извън сметка за данък вече не е ДДС ({@link
   * BgVatDocumentCollector#carriesTax}).
   */
  @Test
  void whitespaceOnlyCredit_doesNotEnterLineMode() {
    MoveLine taxed = line("601", "100", null, "20", "  ");
    taxed.setTaxLineSet(new java.util.HashSet<>(java.util.List.of(new TaxLine())));
    assertEquals(
        "P{} S{} vat=20 base=null gross=null A[(null 0 null 20)] W[Ръчен запис с ДДС, но без ДДС"
            + " операция или кредитен тип — данъчната основа не е определена]",
        snapshot(fromMove(move(null, taxed))));
  }

  /** b = −v при кредит „3“: к.09 = 0 и колоната изобщо НЕ се появява. */
  @Test
  void credit3_baseCancelledByVat_writesNoColumn() {
    assertEquals(
        "P{} S{} vat=0 base=null gross=null A[] W[]",
        snapshot(fromMove(move(null, line("601", "100", null, "-100", "3")))));
  }

  @Test
  void credit3_cancelledLine_doesNotSwallowAnotherLine() {
    assertEquals(
        "P{K09_BASE_NO_CREDIT=50} S{} vat=0 base=null gross=null A[] W[]",
        snapshot(
            fromMove(
                move(
                    null,
                    line("601", "100", null, "-100", "3"),
                    line("602", "50", null, null, "3")))));
  }

  // ---------------------------------------------------------------------------
  // Фактура за покупка — целият път през enrichFromInvoice
  // ---------------------------------------------------------------------------

  @Test
  void purchaseInvoice_mixedCredits_andBlankDefaultsWithWarning() {
    Invoice inv =
        invoice(
            1,
            invoiceLine("1", "100", "120"),
            invoiceLine("2", "50", "60"),
            invoiceLine("3", "30", "36"),
            invoiceLine(null, "10", "12"));
    assertEquals(
        "P{K09_BASE_NO_CREDIT=36, K10_BASE_FULL_CREDIT=110, K11_VAT_FULL_CREDIT=22,"
            + " K12_BASE_PARTIAL_CREDIT=50, K13_VAT_PARTIAL_CREDIT=10} S{} vat=0 base=0 gross=0"
            + " A[] W[Ред без вид данъчен кредит — приет пълен ДК (invoice line null)]",
        snapshot(fromInvoice(inv)));
  }

  @Test
  void purchaseInvoice_blankCreditWithSpaces_defaultsWithWarning() {
    assertEquals(
        "P{K10_BASE_FULL_CREDIT=100, K11_VAT_FULL_CREDIT=20} S{} vat=0 base=0 gross=0 A[]"
            + " W[Ред без вид данъчен кредит — приет пълен ДК (invoice line null)]",
        snapshot(fromInvoice(invoice(1, invoiceLine("   ", "100", "120")))));
  }

  /** Кредитното известие (тип 2) минава по същия път, без смяна на знака. */
  @Test
  void supplierRefund_routedLikePurchase() {
    assertEquals(
        "P{K12_BASE_PARTIAL_CREDIT=100, K13_VAT_PARTIAL_CREDIT=20} S{} vat=0 base=0 gross=0 A[]"
            + " W[]",
        snapshot(fromInvoice(invoice(2, invoiceLine("2", "100", "120")))));
  }

  @Test
  void salesInvoice_ignoresCreditTypeOnItsLines() {
    assertEquals(
        "P{} S{} vat=0 base=0 gross=0 A[] W[]",
        snapshot(fromInvoice(invoice(3, invoiceLine("1", "100", "120")))));
  }

  @Test
  void invoiceWithoutOperationType_routesNothing() {
    Invoice inv = new Invoice();
    inv.setInvoiceLineList(new ArrayList<>(Arrays.asList(invoiceLine("1", "100", "120"))));
    assertEquals("P{} S{} vat=0 base=0 gross=0 A[] W[]", snapshot(fromInvoice(inv)));
  }

  @Test
  void purchaseInvoice_withoutLines_doesNotFail() {
    Invoice inv = invoice(1);
    inv.setInvoiceLineList(null);
    assertEquals("P{} S{} vat=0 base=0 gross=0 A[] W[]", snapshot(fromInvoice(inv)));
  }

  /**
   * Заварено: генерираният decimal getter връща 0, никога null, тъй че резервната стойност {@code
   * exTaxTotal}/{@code inTaxTotal} никога не се стига — ред без суми във фирмената валута не дава
   * нищо.
   */
  @Test
  void purchaseInvoice_documentCurrencyFallback_isNeverReached() {
    InvoiceLine l = invoiceLine("1", null, null);
    l.setExTaxTotal(new BigDecimal("100"));
    l.setInTaxTotal(new BigDecimal("120"));
    assertEquals("P{} S{} vat=0 base=0 gross=0 A[] W[]", snapshot(fromInvoice(invoice(1, l))));
  }

  @Test
  void purchaseInvoice_taxLinesAndHeaderTotals() {
    Tax tax = new Tax();
    tax.setCode("BG_SRS_20");
    TaxLine taxLine = new TaxLine();
    taxLine.setTax(tax);
    taxLine.setValue(new BigDecimal("20"));
    InvoiceLineTax lineTax = new InvoiceLineTax();
    lineTax.setTaxLine(taxLine);
    lineTax.setCompanyExTaxBase(new BigDecimal("100"));
    lineTax.setCompanyTaxTotal(new BigDecimal("20"));

    Invoice inv = invoice(1, invoiceLine("1", "100", "120"));
    inv.setInvoiceLineTaxList(new ArrayList<>(Collections.singletonList(lineTax)));
    inv.setCompanyExTaxTotal(new BigDecimal("100"));
    inv.setCompanyTaxTotal(new BigDecimal("20"));
    inv.setCompanyInTaxTotal(new BigDecimal("120"));
    Partner partner = new Partner();
    partner.setTaxNbr("BG111111111");
    inv.setPartner(partner);
    inv.setPartnerTaxNbr("BG222222222");

    BgVatDocumentDto doc = fromInvoice(inv);
    assertEquals(
        "P{K10_BASE_FULL_CREDIT=100, K11_VAT_FULL_CREDIT=20} S{} vat=20 base=100 gross=120"
            + " A[(BG_SRS_20 20 100 20)] W[]",
        snapshot(doc));
    assertEquals("BG222222222", doc.getCounterpartyVatNumber());
    assertEquals("BG222222222", doc.getCounterpartyDiaryId());
  }

  /** Физическо лице без ДДС номер: „999999999999999“ — ЕГН никога (§ 1 т. 1 ДР ППЗДДС). */
  @Test
  void purchaseInvoice_ofAnIndividualWithAnEgn_isTheNoIdCode() {
    Invoice inv = invoice(1, invoiceLine("1", "100", "100"));
    Partner partner = new Partner();
    partner.setPartnerTypeSelect(2);
    partner.setBgIdentifierType("0");
    partner.setRegistrationCode("7501010010");
    inv.setPartner(partner);

    BgVatDocumentDto doc = fromInvoice(inv);

    assertEquals(BgVatCounterpartyId.NO_ID, doc.getCounterpartyDiaryId());
    assertEquals(null, doc.getCounterpartyVatNumber());
  }

  // ---------------------------------------------------------------------------
  // ДДС операция на ръчния запис — режим 2
  // ---------------------------------------------------------------------------

  @Test
  void purchasePartial_routesToK12K13() {
    assertEquals(
        "P{K12_BASE_PARTIAL_CREDIT=100, K13_VAT_PARTIAL_CREDIT=20} S{} vat=20 base=null"
            + " gross=null A[] W[]",
        snapshot(
            fromMove(
                move(
                    "PURCHASE_PARTIAL",
                    line("601", "100", "20", null, null),
                    line("4531", "20", null, null, null)))));
  }

  @Test
  void purchase82None_foldsVatIntoK09() {
    assertEquals(
        "P{K09_BASE_NO_CREDIT=120} S{} vat=20 base=null gross=null A[] W[]",
        snapshot(
            fromMove(
                move(
                    "PURCHASE_82_NONE",
                    line("601", "100", "20", null, null),
                    line("4531", "20", null, null, null)))));
  }

  /** b = −v при PURCHASE_82_NONE: к.09 = 0 и колоната не се появява, а totalVat остава. */
  @Test
  void purchase82None_baseCancelledByVat_writesNoColumn() {
    assertEquals(
        "P{} S{} vat=20 base=null gross=null A[] W[]",
        snapshot(
            fromMove(
                move(
                    "PURCHASE_82_NONE",
                    creditLine("601", "20", "20"),
                    line("4531", "20", null, null, null)))));
  }

  @Test
  void purchaseAnnual_signedVatIntoK14_withoutBase() {
    assertEquals(
        "P{K14_ANNUAL_ADJUSTMENT=-50} S{} vat=-50 base=null gross=null A[] W[]",
        snapshot(
            fromMove(
                move(
                    "PURCHASE_ANNUAL",
                    line("609", "50", null, null, null),
                    creditLine("4531", "50", null)))));
  }

  /** Заварено: при тристранната операция ДДС по 4531 не влиза в НИТО ЕДНА колона. */
  @Test
  void purchaseTriangular_baseIntoK15_vatNowhere() {
    assertEquals(
        "P{K15_BASE_TRIANGULAR_ACQ=100} S{} vat=20 base=null gross=null A[] W[]",
        snapshot(
            fromMove(
                move(
                    "PURCHASE_TRIANGULAR",
                    line("601", "100", "20", null, null),
                    line("4531", "20", null, null, null)))));
  }

  @Test
  void serviceNonEu_fillsK10K11_andSalesK14K15() {
    Move m = move("SERVICE_NONEU", line("4531", "20", null, null, null));
    m.setBgVatMoveVatRate(new BigDecimal("0.20"));
    assertEquals(
        "P{K10_BASE_FULL_CREDIT=100.00, K11_VAT_FULL_CREDIT=20} S{K14_BASE_RC=100.00,"
            + " K15_VAT_ICA_RC=20} vat=20 base=null gross=null A[] W["
            + CREDIT_DEFAULTED
            + "]",
        snapshot(fromMove(m)));
  }

  @Test
  void import167a_fillsBothSides() {
    Move m = move("IMPORT_167A", line("4531", "20", null, null, null));
    m.setBgVatMoveVatRate(new BigDecimal("0.20"));
    // ППЗДДС чл. 49а ал. 4 т. 1: основата в к.9 и к.11, данъкът в к.10 и к.12 на продажбите.
    assertEquals(
        "P{K10_BASE_FULL_CREDIT=100.00, K11_VAT_FULL_CREDIT=20} S{K11_BASE_20=100.00,"
            + " K12_VAT_20=20} vat=20 base=null gross=null A[] W["
            + CREDIT_DEFAULTED
            + "]",
        snapshot(fromMove(m)));
  }

  @Test
  void protocolWithoutRate_takesBaseFromBaseLines_andWarns() {
    MoveLine base = line("601", "100", null, null, null);
    base.setTaxLineSet(new HashSet<>(Collections.singletonList(new TaxLine())));
    assertEquals(
        "P{K10_BASE_FULL_CREDIT=100, K11_VAT_FULL_CREDIT=20} S{K13_BASE_ICA=100,"
            + " K15_VAT_ICA_RC=20} vat=20 base=null gross=null A[] W[Протокол без ставка — базата"
            + " е взета от базовите редове, "
            + CREDIT_DEFAULTED
            + "]",
        snapshot(fromMove(move("VOP", base, line("4531", "20", null, null, null)))));
  }

  @Test
  void protocolBaseMismatch_warnsButKeepsReverseComputedBase() {
    Move m = move("VOP", line("601", "50", "20", null, null), line("4531", "20", null, null, null));
    assertEquals(
        "P{K10_BASE_FULL_CREDIT=100.00, K11_VAT_FULL_CREDIT=20} S{K13_BASE_ICA=100.00,"
            + " K15_VAT_ICA_RC=20} vat=20 base=null gross=null A[] W[Разминаване база на"
            + " протокол: изчислена 100.00 ≠ базови редове 50, "
            + CREDIT_DEFAULTED
            + "]",
        snapshot(fromMove(m)));
  }

  @Test
  void operationWithSurroundingSpaces_isTrimmed() {
    Move m = move(" VOP ", line("4531", "20", null, null, null));
    m.setBgVatMoveVatRate(new BigDecimal("0.20"));
    assertEquals(
        "P{K10_BASE_FULL_CREDIT=100.00, K11_VAT_FULL_CREDIT=20} S{K13_BASE_ICA=100.00,"
            + " K15_VAT_ICA_RC=20} vat=20 base=null gross=null A[] W["
            + CREDIT_DEFAULTED
            + "]",
        snapshot(fromMove(m)));
  }

  /** Непозната операция: нищо не се маршрутизира, но totalVat вече е записан. */
  @Test
  void unknownOperation_warns_andKeepsTotalVat() {
    assertEquals(
        "P{} S{} vat=20 base=null gross=null A[] W[Неразпозната ДДС операция за ръчен запис:"
            + " FOO]",
        snapshot(fromMove(move("FOO", line("4531", "20", null, null, null)))));
  }

  /** Сравнението е точно: малки букви са непозната операция. */
  @Test
  void lowercaseOperation_isUnknown() {
    assertEquals(
        "P{} S{} vat=20 base=null gross=null A[] W[Неразпозната ДДС операция за ръчен запис:"
            + " vop]",
        snapshot(fromMove(move("vop", line("4531", "20", null, null, null)))));
  }

  /** чл. 79 ал. 4 без 4532: totalVat остава сумата по 4531, записана преди маршрута. */
  @Test
  void art79CorrectionWithoutChargedVat_keepsInputTotalVat() {
    assertEquals(
        "P{} S{} vat=20 base=null gross=null A[] W[Протокол по чл. 79 ал. 4 без начислен данък"
            + " по сметка 4532 — нищо не влиза в к.12"
            + "]",
        snapshot(fromMove(move("CORRECTION_79", line("4531", "20", null, null, null)))));
  }
}

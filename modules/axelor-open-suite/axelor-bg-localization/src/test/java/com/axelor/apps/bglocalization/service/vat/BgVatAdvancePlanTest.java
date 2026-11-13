package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoiceLineTax;
import com.axelor.apps.account.db.InvoicePayment;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.InvoicePaymentRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * АВАНСОВАТА ФАКТУРА В СТРАНАТА — правилата без база.
 *
 * <p>ЗДДС чл. 25 ал. 7: при авансово плащане „данъкът става изискуем при получаване на плащането
 * (за размера на плащането)“; чл. 113 ал. 4 — фактура до 5 дни от плащането; чл. 124 ал. 2 —
 * издаденият документ е в дневника за продажби за периода на издаване (lex.bg, посл. изм. ДВ бр.
 * 69/2026; цитираните членове са в редакцията до ДВ бр. 115/2025). Окончателната фактура приспада
 * аванса с ред за приспадане — основата и ДДС намаляват, двойно облагане няма (решение от
 * 28.10.2026, схема А). Редът се познава по {@code InvoiceLine.bgAdvanceDeduction} и {@code
 * bgAdvanceInvoice}; текстът му е само за печат.
 */
class BgVatAdvancePlanTest {

  private static long ids = 1;

  static TaxLine taxLine(String code, String percent) {
    Tax tax = new Tax();
    tax.setCode(code);
    TaxLine line = new TaxLine();
    line.setId(ids++);
    line.setTax(tax);
    line.setValue(new BigDecimal(percent));
    return line;
  }

  static final TaxLine VAT20 = taxLine("BG_SRS_20", "20");
  static final TaxLine VAT9 = taxLine("BG_LRS_9", "9");
  static final TaxLine VOD0 = taxLine("BG_VOD_0", "0");

  static InvoiceLineTax rate(TaxLine taxLine, String base, String vat) {
    InvoiceLineTax t = new InvoiceLineTax();
    t.setTaxLine(taxLine);
    t.setExTaxBase(new BigDecimal(base));
    t.setTaxTotal(new BigDecimal(vat));
    t.setCompanyExTaxBase(new BigDecimal(base));
    t.setCompanyTaxTotal(new BigDecimal(vat));
    return t;
  }

  static Invoice advance(String number, LocalDate date, InvoiceLineTax... rates) {
    Invoice invoice = new Invoice();
    invoice.setId(ids++);
    invoice.setInvoiceId(number);
    invoice.setInvoiceDate(date);
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_CLIENT_SALE);
    invoice.setOperationSubTypeSelect(InvoiceRepository.OPERATION_SUB_TYPE_ADVANCE);
    invoice.setInvoiceLineTaxList(new ArrayList<>(Arrays.asList(rates)));
    return invoice;
  }

  static InvoiceLine line(String name, TaxLine taxLine, String base, Account account) {
    InvoiceLine line = new InvoiceLine();
    line.setProductName(name);
    line.setTaxLineSet(new HashSet<>(List.of(taxLine)));
    line.setExTaxTotal(new BigDecimal(base));
    line.setCompanyExTaxTotal(new BigDecimal(base));
    line.setAccount(account);
    return line;
  }

  /** Ред за приспадане, какъвто го слага бутонът: полетата + текст (какъвто и да е). */
  static InvoiceLine deduction(
      Invoice advance, String text, TaxLine taxLine, String base, Account account) {
    InvoiceLine line = line(text, taxLine, base, account);
    line.setBgAdvanceDeduction(true);
    line.setBgAdvanceInvoice(advance);
    return line;
  }

  static String bg(Invoice advance) {
    return BgVatAdvancePlan.deductionLabel(advance, BgVatAdvancePlan.DEDUCTION_LABEL);
  }

  /** Авансова фактура от доставчик: ставките и по един ред на ставка с кредитния тип. */
  static Invoice purchaseAdvance(String number, String creditType, InvoiceLineTax... rates) {
    Invoice invoice = advance(number, JUNE_5, rates);
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    invoice.setInvoiceLineList(new ArrayList<>());
    for (InvoiceLineTax rate : rates) {
      InvoiceLine line =
          line("Аванс", rate.getTaxLine(), rate.getExTaxBase().toPlainString(), null);
      line.setBgVatCreditType(creditType);
      invoice.getInvoiceLineList().add(line);
    }
    return invoice;
  }

  static Invoice fin(Invoice... advances) {
    Invoice invoice = new Invoice();
    invoice.setId(ids++);
    invoice.setInvoiceId("2026000200");
    invoice.setInvoiceDate(LocalDate.of(2026, 7, 10));
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_CLIENT_SALE);
    invoice.setOperationSubTypeSelect(InvoiceRepository.OPERATION_SUB_TYPE_DEFAULT);
    invoice.setAdvancePaymentInvoiceSet(new HashSet<>(Arrays.asList(advances)));
    invoice.setInvoiceLineList(new ArrayList<>());
    return invoice;
  }

  static Account account(String code) {
    Account a = new Account();
    a.setId(ids++);
    a.setCode(code);
    return a;
  }

  static final Account A702 = account("702");
  static final Account A412 = account("412");

  static final LocalDate JUNE_5 = LocalDate.of(2026, 6, 5);

  @Test
  @DisplayName("Текстът на реда: „Приспадане на аванс по ф-ра № … от дд.мм.гггг“ — или преведеният")
  void текстът_на_реда() {
    Invoice a = advance("0000000123", JUNE_5, rate(VAT20, "1000", "200"));
    assertEquals("Приспадане на аванс по ф-ра № 0000000123 от 05.06.2026", bg(a));
    assertEquals(
        "Advance deduction, invoice No. 0000000123 of 05.06.2026",
        BgVatAdvancePlan.deductionLabel(a, "Advance deduction, invoice No. %s of %s"));
  }

  @Test
  @DisplayName("Авансова продажба и покупка с ДДС: да; ВОД аванс (0 %) и обикновена фактура — не")
  void коя_авансова_получава_статия() {
    assertTrue(BgVatAdvancePlan.needsVatMove(advance("1", JUNE_5, rate(VAT20, "1000", "200"))));
    assertFalse(BgVatAdvancePlan.needsVatMove(advance("2", JUNE_5, rate(VOD0, "1000", "0"))));

    Invoice purchase = purchaseAdvance("3", "1", rate(VAT20, "1000", "200"));
    assertTrue(BgVatAdvancePlan.needsVatMove(purchase), "авансова от доставчик");
    assertTrue(BgVatAdvancePlan.isPurchase(purchase));

    Invoice plain = advance("4", JUNE_5, rate(VAT20, "1000", "200"));
    plain.setOperationSubTypeSelect(InvoiceRepository.OPERATION_SUB_TYPE_DEFAULT);
    assertFalse(BgVatAdvancePlan.needsVatMove(plain));

    Invoice refund = advance("5", JUNE_5, rate(VAT20, "1000", "200"));
    refund.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_CLIENT_REFUND);
    assertTrue(BgVatAdvancePlan.needsVatMove(refund), "КИ към авансова — обратната статия");
    assertTrue(BgVatAdvancePlan.isRefund(refund));
  }

  @Test
  @DisplayName("Две ставки в аванса → два реда за приспадане, основата и ДДС със знак минус")
  void по_ред_на_ставка() {
    Invoice a = advance("0000000123", JUNE_5, rate(VAT20, "1000", "200"), rate(VAT9, "100", "9"));
    List<BgVatAdvancePlan.Deduction> d = BgVatAdvancePlan.deductions(a);

    assertEquals(2, d.size());
    assertEquals(VAT20, d.get(0).getTaxLine());
    assertEquals(0, new BigDecimal("-1000").compareTo(d.get(0).getBase()));
    assertEquals(0, new BigDecimal("-200").compareTo(d.get(0).getVat()));
    assertEquals(0, new BigDecimal("-100").compareTo(d.get(1).getBase()));
    assertEquals(0, new BigDecimal("-9").compareTo(d.get(1).getVat()));
    assertSame(a, d.get(0).getAdvance());
  }

  @Test
  @DisplayName("Окончателна без реда за приспадане на закачен аванс → липсва, с основата")
  void липсващото_приспадане() {
    Invoice a = advance("0000000123", JUNE_5, rate(VAT20, "1000", "200"));
    Invoice f = fin(a);
    f.getInvoiceLineList().add(line("Стока", VAT20, "3000", A702));

    List<String> missing = BgVatAdvancePlan.missingDeductions(f, List.of(a));

    assertEquals(1, missing.size());
    assertTrue(missing.get(0).contains("0000000123"), missing.get(0));
    assertTrue(missing.get(0).contains("-1000.00"), missing.get(0));
  }

  @Test
  @DisplayName("С точния ред — нищо не липсва; с друга сума — липсва")
  void точното_приспадане() {
    Invoice a = advance("0000000123", JUNE_5, rate(VAT20, "1000", "200"));
    Invoice f = fin(a);
    f.getInvoiceLineList().add(line("Стока", VAT20, "3000", A702));
    f.getInvoiceLineList().add(deduction(a, bg(a), VAT20, "-1000", A412));
    assertEquals(List.of(), BgVatAdvancePlan.missingDeductions(f, List.of(a)));

    f.getInvoiceLineList().get(1).setExTaxTotal(new BigDecimal("-900"));
    assertEquals(1, BgVatAdvancePlan.missingDeductions(f, List.of(a)).size());
  }

  @Test
  @DisplayName(
      "Редът се познава по полето: на английски и с редактиран текст — да; само текстът — не")
  void разпознава_се_по_полето() {
    Invoice a = advance("0000000123", JUNE_5, rate(VAT20, "1000", "200"));
    for (String text :
        List.of(
            "Advance deduction, invoice No. 0000000123 of 05.06.2026",
            "Аванс по договор 15/2026 (редактиран от оператора)")) {
      Invoice f = fin(a);
      f.getInvoiceLineList().add(line("Стока", VAT20, "3000", A702));
      f.getInvoiceLineList().add(deduction(a, text, VAT20, "-1000", A412));

      assertTrue(BgVatAdvancePlan.isDeduction(f.getInvoiceLineList().get(1)), text);
      assertTrue(BgVatAdvancePlan.hasDeductions(f), text);
      assertEquals(List.of(a), BgVatAdvancePlan.deductedAdvances(f), text);
      assertEquals(List.of(), BgVatAdvancePlan.missingDeductions(f, List.of(a)), text);
    }

    Invoice onlyText = fin(a);
    onlyText.getInvoiceLineList().add(line(bg(a), VAT20, "-1000", A412));
    assertFalse(BgVatAdvancePlan.isDeduction(onlyText.getInvoiceLineList().get(0)));
    assertFalse(BgVatAdvancePlan.hasDeductions(onlyText));
    assertEquals(List.of(), BgVatAdvancePlan.deductedAdvances(onlyText));
    assertEquals(1, BgVatAdvancePlan.missingDeductions(onlyText, List.of(a)).size());
  }

  @Test
  @DisplayName("Ред за приспадане на друга авансова не покрива тази")
  void редът_е_на_своята_авансова() {
    Invoice a = advance("0000000123", JUNE_5, rate(VAT20, "1000", "200"));
    Invoice b = advance("0000000124", JUNE_5, rate(VAT20, "1000", "200"));
    Invoice f = fin(a, b);
    f.getInvoiceLineList().add(deduction(b, bg(a), VAT20, "-1000", A412));

    assertEquals(List.of(b), BgVatAdvancePlan.deductedAdvances(f));
    List<String> missing = BgVatAdvancePlan.missingDeductions(f, List.of(a, b));
    assertEquals(1, missing.size());
    assertTrue(missing.get(0).contains("0000000123"), missing.get(0));
  }

  @Test
  @DisplayName(
      "Без статия при плащане (generateMoveForInvoicePayment = false): авансова с ДДС — отказ")
  void без_статия_при_плащане() {
    Invoice a = advance("0000000123", JUNE_5, rate(VAT20, "1000", "200"));
    assertTrue(BgVatAdvancePlan.refusedWithoutPaymentMoves(a, false));
    assertFalse(BgVatAdvancePlan.refusedWithoutPaymentMoves(a, true));
    assertFalse(
        BgVatAdvancePlan.refusedWithoutPaymentMoves(
            advance("2", JUNE_5, rate(VOD0, "1000", "0")), false),
        "без ДДС — без статия, AOS както досега");
  }

  @Test
  @DisplayName("Приспаднатите — само авансовите от връзката, за които има ред")
  void приспаднатите() {
    Invoice a = advance("0000000123", JUNE_5, rate(VAT20, "1000", "200"));
    Invoice b = advance("0000000124", JUNE_5, rate(VAT20, "500", "100"));
    Invoice f = fin(a, b);
    f.getInvoiceLineList().add(deduction(a, bg(a), VAT20, "-1000", A412));

    assertEquals(
        List.of("0000000123"),
        BgVatAdvancePlan.deductedAdvances(f).stream()
            .map(Invoice::getInvoiceId)
            .collect(Collectors.toList()));
    assertTrue(BgVatAdvancePlan.hasDeductions(f));
    assertFalse(BgVatAdvancePlan.hasDeductions(fin(a)));
  }

  @Test
  @DisplayName("Окончателна със сума 0: Дт 412 / Кт 702 за основата — по сметките на редовете")
  void нулевата_окончателна() {
    Invoice a = advance("0000000123", JUNE_5, rate(VAT20, "3000", "600"));
    Invoice f = fin(a);
    f.getInvoiceLineList().add(line("Стока", VAT20, "3000", A702));
    f.getInvoiceLineList().add(deduction(a, bg(a), VAT20, "-3000", A412));

    List<BgVatAdvancePlan.Posting> postings = BgVatAdvancePlan.zeroFinalPostings(f);

    assertEquals(2, postings.size());
    BgVatAdvancePlan.Posting debit =
        postings.stream().filter(BgVatAdvancePlan.Posting::isDebit).findFirst().get();
    BgVatAdvancePlan.Posting credit = postings.stream().filter(p -> !p.isDebit()).findFirst().get();
    assertEquals("412", debit.getAccount().getCode());
    assertEquals(0, new BigDecimal("3000").compareTo(debit.getAmount()));
    assertEquals("702", credit.getAccount().getCode());
    assertEquals(0, new BigDecimal("3000").compareTo(credit.getAmount()));
  }

  @Test
  @DisplayName("Статията на авансовата: Дт 412 = сборът на ДДС, Кт 4532 по ставка; КИ — обратно")
  void статията_на_авансовата() {
    Invoice a = advance("0000000123", JUNE_5, rate(VAT20, "1000", "200"), rate(VAT9, "100", "9"));
    List<BgVatAdvancePlan.Rate> rates = BgVatAdvancePlan.rates(a);
    assertEquals(2, rates.size());
    assertEquals(0, new BigDecimal("209").compareTo(BgVatAdvancePlan.totalVat(a)));
    assertTrue(BgVatAdvancePlan.advanceAccountIsDebit(a));

    Invoice refund = advance("0000000125", JUNE_5, rate(VAT20, "1000", "200"));
    refund.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_CLIENT_REFUND);
    assertFalse(BgVatAdvancePlan.advanceAccountIsDebit(refund));
  }

  // ---------------------------------------------------------------------------
  // Покупки
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName(
      "Авансова от доставчик: Дт 4531 / Кт 402 — сметката за аванси е в кредита; КИ — обратно")
  void страната_при_покупка() {
    Invoice a = purchaseAdvance("П-1", "1", rate(VAT20, "1000", "200"));
    assertFalse(BgVatAdvancePlan.advanceAccountIsDebit(a));

    Invoice refund = purchaseAdvance("П-2", "1", rate(VAT20, "1000", "200"));
    refund.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND);
    assertTrue(BgVatAdvancePlan.isRefund(refund));
    assertTrue(BgVatAdvancePlan.needsVatMove(refund));
    assertTrue(BgVatAdvancePlan.advanceAccountIsDebit(refund));
  }

  @Test
  @DisplayName("Без право на кредит (тип 3) — без статия: ДДС-ът не е за 4531 (граница)")
  void без_право_на_кредит() {
    assertFalse(
        BgVatAdvancePlan.needsVatMove(purchaseAdvance("П-3", "3", rate(VAT20, "1000", "200"))));
    assertTrue(
        BgVatAdvancePlan.needsVatMove(purchaseAdvance("П-4", "2", rate(VAT20, "1000", "200"))),
        "частичен кредит — статия с целия ДДС, както обикновената фактура");
    assertTrue(
        BgVatAdvancePlan.needsVatMove(purchaseAdvance("П-5", null, rate(VAT20, "1000", "200"))),
        "празно = пълен кредит (колекторът)");
  }

  @Test
  @DisplayName(
      "Редът за приспадане при покупка носи кредитния тип на аванса — минусът е в същата колона")
  void кредитният_тип_на_реда() {
    Invoice a = purchaseAdvance("П-6", "2", rate(VAT20, "1000", "200"), rate(VAT9, "100", "9"));
    List<BgVatAdvancePlan.Deduction> d = BgVatAdvancePlan.deductions(a);
    assertEquals(2, d.size());
    assertEquals("2", d.get(0).getCreditType());
    assertEquals("2", d.get(1).getCreditType());

    Invoice sale = advance("0000000123", JUNE_5, rate(VAT20, "1000", "200"));
    assertEquals(null, BgVatAdvancePlan.deductions(sale).get(0).getCreditType());
  }

  @Test
  @DisplayName(
      "Окончателна от доставчик със сума 0: Дт 601 / Кт 402 — страните обратно на продажбата")
  void нулевата_окончателна_от_доставчик() {
    Invoice a = purchaseAdvance("П-7", "1", rate(VAT20, "3000", "600"));
    Invoice f = fin(a);
    f.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    Account a601 = account("601");
    Account a402 = account("402");
    f.getInvoiceLineList().add(line("Материали", VAT20, "3000", a601));
    f.getInvoiceLineList().add(deduction(a, bg(a), VAT20, "-3000", a402));

    List<BgVatAdvancePlan.Posting> postings = BgVatAdvancePlan.zeroFinalPostings(f);

    BgVatAdvancePlan.Posting debit =
        postings.stream().filter(BgVatAdvancePlan.Posting::isDebit).findFirst().get();
    BgVatAdvancePlan.Posting credit = postings.stream().filter(p -> !p.isDebit()).findFirst().get();
    assertEquals("601", debit.getAccount().getCode());
    assertEquals("402", credit.getAccount().getCode());
    assertEquals(0, new BigDecimal("3000").compareTo(credit.getAmount()));
  }

  static InvoicePayment payment(String amount, LocalDate date, int status) {
    InvoicePayment p = new InvoicePayment();
    p.setAmount(new BigDecimal(amount));
    p.setPaymentDate(date);
    p.setStatusSelect(status);
    return p;
  }

  @Test
  @DisplayName("Неплатена до края на периода авансова от доставчик — кредитът възниква с плащането")
  void неплатената_авансова() {
    Invoice a = purchaseAdvance("П-8", "1", rate(VAT20, "1000", "200"));
    a.setInTaxTotal(new BigDecimal("1200"));
    LocalDate end = LocalDate.of(2026, 6, 30);
    a.setInvoicePaymentList(new ArrayList<>());
    assertTrue(BgVatAdvancePlan.unpaidAt(a, end), "без плащане");

    a.getInvoicePaymentList()
        .add(payment("1200", LocalDate.of(2026, 7, 2), InvoicePaymentRepository.STATUS_VALIDATED));
    assertTrue(BgVatAdvancePlan.unpaidAt(a, end), "платена след периода");

    a.getInvoicePaymentList()
        .add(payment("1200", LocalDate.of(2026, 6, 3), InvoicePaymentRepository.STATUS_CANCELED));
    assertTrue(BgVatAdvancePlan.unpaidAt(a, end), "анулираното плащане не се брои");

    a.getInvoicePaymentList()
        .add(payment("1200", LocalDate.of(2026, 6, 3), InvoicePaymentRepository.STATUS_VALIDATED));
    assertFalse(BgVatAdvancePlan.unpaidAt(a, end));
  }

  @Test
  @DisplayName("Окончателна — продажба и покупка; авансовата и КИ — не")
  void окончателната() {
    Invoice f = fin();
    assertTrue(BgVatAdvancePlan.isFinal(f));
    f.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    assertTrue(BgVatAdvancePlan.isFinal(f));
    f.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_CLIENT_REFUND);
    assertFalse(BgVatAdvancePlan.isFinal(f));
    assertFalse(BgVatAdvancePlan.isFinal(purchaseAdvance("П-9", "1", rate(VAT20, "1", "0.2"))));
  }
}

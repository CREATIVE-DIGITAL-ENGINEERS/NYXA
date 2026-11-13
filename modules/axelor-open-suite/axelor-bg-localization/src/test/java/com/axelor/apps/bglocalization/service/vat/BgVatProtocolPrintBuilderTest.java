package com.axelor.apps.bglocalization.service.vat;

import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.invoice;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.reverseCharge;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.supplier;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesAdapter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПЕЧАТНИЯТ ПРОТОКОЛ ПО ЧЛ. 117 ЗДДС — <b>числата на листа са числата на дневниците</b>.
 *
 * <p>Номерът, датата, основата и данъкът се сверяват с реда, който PRODAGBI/POKUPKI пишат за същата
 * статия ({@link BgVatMoveDiaryRows#rows}) — независимо изчислен тук, а не прочетен от печата.
 * Реквизитите на ЗДДС чл. 117 ал. 2 т. 1–10 (консолидиран текст на lex.bg, последно изм. ДВ бр.
 * 69/2026, сверено 27.10.2026) — всеки със своя източник: т. 4 от фактурата, т. 7 от статията, т. 9
 * и т. 10 от доставчика, при чл. 167а — митническият документ (ЗДДС чл. 57 ал. 6 т. 2).
 */
class BgVatProtocolPrintBuilderTest {

  static final LocalDate JUNE_10 = LocalDate.of(2026, 6, 10);
  static final LocalDate JUNE_12 = LocalDate.of(2026, 6, 12);
  static final LocalDate JUNE_20 = LocalDate.of(2026, 6, 20);

  static final BgVatDocumentCollector COLLECTOR = new BgVatDocumentCollector(null);
  static final BgVatSalesAdapter SALES = new BgVatSalesAdapter();
  static final BgVatMoveDiaryRows ROWS =
      new BgVatMoveDiaryRows(
          COLLECTOR, new BgVatExportService(COLLECTOR, null, null, SALES, null, null, null), SALES);
  static final BgVatProtocolPrintBuilder BUILDER = new BgVatProtocolPrintBuilder(ROWS);

  /** Регистрираното лице — издателят на протокола (чл. 117 ал. 2 т. 2). */
  static void registered(Company company) {
    Partner partner = new Partner();
    partner.setFullName("Дружество ЕООД");
    partner.setTaxNbr("BG100000033");
    company.setPartner(partner);
    company.setName("Дружество ЕООД");
  }

  /** ВОП 1000 / 20 % от германски доставчик — протоколът, както го съставя Nyxa от фактурата. */
  static Move generatedVop() throws Exception {
    Invoice invoice =
        invoice(
            1,
            supplier(7, "DE123456789", "DE"),
            JUNE_20,
            JUNE_12,
            JUNE_10,
            reverseCharge("BG_VOP_ACQ", "BG_VOP_SELF", "20", "1000", "1", false));
    registered(invoice.getCompany());
    return generate(invoice);
  }

  /** Доставка по чл. 163а, Прил. № 2 ч. II (зърно), 500 / 20 % от доставчик в страната. */
  static Move generatedGrain() throws Exception {
    Invoice invoice =
        invoice(
            3,
            supplier(8, "BG100000033", "BG"),
            JUNE_20,
            JUNE_12,
            JUNE_10,
            reverseCharge("BG_RCM_GRAIN_P", "BG_RCM_GRAIN_S", "20", "500", "1", false));
    registered(invoice.getCompany());
    return generate(invoice);
  }

  static Move generate(Invoice invoice) throws Exception {
    BgVatInvoiceProtocolService.Result result =
        new BgVatInvoiceProtocolFixtures.InMemoryService().generate(invoice, null);
    assertEquals(List.of(), result.getErrors());
    return result.getCreated().get(0);
  }

  static MoveLine line(long account, String code, String debit, String credit) {
    Account a = BgVatInvoiceProtocolFixtures.account(account, code);
    MoveLine l = new MoveLine();
    l.setAccount(a);
    l.setDebit(new BigDecimal(debit));
    l.setCredit(new BigDecimal(credit));
    return l;
  }

  /** Ръчен протокол: Дт 304 / Кт 401 (основата), Дт 4531 / Кт 4532 (данъкът). */
  static Move manual(long id, String operation, String rate, String base, String tax) {
    Company company = BgVatInvoiceProtocolFixtures.company();
    registered(company);
    Period period = new Period();
    period.setFromDate(LocalDate.of(2026, 6, 1));
    MoveLine baseLine = line(304, "304", base, "0");
    baseLine.setTaxRate(new BigDecimal(rate));
    baseLine.setBgVatCreditType("1");
    Move m = new Move();
    m.setId(id);
    m.setReference("MOV-" + id);
    m.setStatusSelect(MoveRepository.STATUS_ACCOUNTED);
    m.setCompany(company);
    m.setCurrency(company.getCurrency());
    m.setDate(JUNE_20);
    m.setPeriod(period);
    m.setPartner(supplier(9, "DE811907980", "DE"));
    m.setNapDocumentTypeSelect("09");
    m.setBgVatMoveVatOperation(operation);
    m.setBgVatProtocolNumber(String.format("%010d", id));
    m.setOrigin("RE-2026-77");
    m.setOriginDate(JUNE_12);
    m.setDescription("Абонамент за софтуер, юни 2026");
    m.setMoveLineList(
        new ArrayList<>(
            Arrays.asList(
                baseLine,
                line(401, "401", "0", base),
                line(4531, "4531", tax, "0"),
                line(4532, "4532", "0", tax))));
    return m;
  }

  static BgVatMoveDiaryRows.Row diaryRow(Move move) {
    return ROWS.rows(move).stream()
        .filter(r -> !r.getDocument().isIdentificationOnly())
        .findFirst()
        .orElseThrow();
  }

  // ---------------------------------------------------------------------------
  // т. 1, т. 5, т. 8 — номерът, датата и сумите са тези на дневниците
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("ВОП от фактура: номер, дата, основа и данък = редът в PRODAGBI и в POKUPKI")
  void вопът_е_редът_от_дневниците() throws Exception {
    Move protocol = generatedVop();
    BgVatMoveDiaryRows.Row row = diaryRow(protocol);
    BgVatProtocolPrint print = BUILDER.build(protocol);

    assertTrue(row.isSales() && row.isPurchases(), "протоколът е и в двата дневника");
    assertEquals(row.getDocument().getDocumentNumber(), print.getNumber());
    assertEquals("0000000001", print.getNumber());
    assertEquals(row.getDocument().getDocumentDate(), print.getDate());
    assertEquals(row.getSalesTotalBase(), print.getBase());
    assertEquals(row.getSalesTotalVat(), print.getTax());
    assertEquals(new BigDecimal("1000.00"), print.getBase());
    assertEquals(new BigDecimal("200.00"), print.getTax());
    // Покупната страна (пълен кредит, к.10/к.11) носи същите числа.
    assertEquals(
        row.getPurchaseColumns().get(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT), print.getBase());
    assertEquals(
        row.getPurchaseColumns().get(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT), print.getTax());
  }

  @Test
  @DisplayName("ВОП от фактура: т. 2, т. 3, т. 4, т. 6, т. 7, т. 9 — всеки от своя източник")
  void вопът_реквизитите() throws Exception {
    BgVatProtocolPrint print = BUILDER.build(generatedVop());

    assertEquals("чл. 117, ал. 1, т. 1 ЗДДС", print.getIssuedUnder());
    // т. 2 — издателят е регистрираното лице (получателят по доставката), не доставчикът.
    assertEquals("Дружество ЕООД", print.getIssuerName());
    assertEquals("BG100000033", print.getIssuerVatNumber());
    // т. 3 — от реда на фактурата: видът и количеството.
    assertEquals(1, print.getItems().size());
    assertEquals("Стока", print.getItems().get(0).getName());
    assertEquals("1", print.getItems().get(0).getQuantity());
    // т. 4 — датата на данъчното събитие от фактурата, не датата на протокола.
    assertEquals(JUNE_10, print.getTaxEventDate());
    assertEquals("20 %", print.getRateLabel());
    assertEquals("чл. 84 ЗДДС", print.getBasis());
    // т. 9 — ДДС номерът на доставчика от другата ДЧ и номерът/датата на фактурата му.
    assertEquals("т. 9", print.getSupplierIdPoint());
    assertEquals("DE123456789", print.getSupplierId());
    assertEquals("Доставчик 7", print.getSupplierName());
    assertEquals("INV-1", print.getInvoiceNumber());
    assertEquals(JUNE_12, print.getInvoiceDate());
    assertEquals("EUR", print.getCurrency());
    assertEquals("06.2026", print.getDiaryPeriod());
    assertEquals(List.of(), print.getWarnings());
  }

  @Test
  @DisplayName("Закръгляне по документ: данък 246,915 → 246,92 — колкото PRODAGBI, не повече")
  void закръглянето_е_на_дневника() throws Exception {
    Move protocol = manual(41, "SERVICE_EU", "20", "1234.575", "246.915");
    protocol.setBgVatProtocolBase(new BigDecimal("1234.575"));
    BgVatMoveDiaryRows.Row row = diaryRow(protocol);
    BgVatProtocolPrint print = BUILDER.build(protocol);

    assertEquals(new BigDecimal("246.92"), row.getSalesTotalVat());
    assertEquals(new BigDecimal("1234.58"), row.getSalesTotalBase());
    assertEquals(row.getSalesTotalVat(), print.getTax());
    assertEquals(row.getSalesTotalBase(), print.getBase());
  }

  // ---------------------------------------------------------------------------
  // Другите случаи
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("чл. 163а, Прил. № 2 ч. II: т. 10 — ИН по чл. 84 ДОПК на доставчика, к.8а „02“")
  void чл163а_зърно() throws Exception {
    Move protocol = generatedGrain();
    BgVatMoveDiaryRows.Row row = diaryRow(protocol);
    BgVatProtocolPrint print = BUILDER.build(protocol);

    assertEquals("чл. 117, ал. 1, т. 4 ЗДДС", print.getIssuedUnder());
    assertEquals("чл. 163а, ал. 2 ЗДДС", print.getBasis());
    assertEquals("т. 10", print.getSupplierIdPoint());
    assertEquals(row.getDocument().getCounterpartyDiaryId(), print.getSupplierId());
    assertEquals("INV-3", print.getInvoiceNumber());
    assertEquals(JUNE_12, print.getInvoiceDate());
    assertEquals("02", print.getSupplyType());
    assertEquals(row.getSalesTotalBase(), print.getBase());
    assertEquals(new BigDecimal("100.00"), print.getTax());
  }

  @Test
  @DisplayName("Внос по чл. 167а: т. 2 на чл. 117 ал. 1, митническият документ, к.8а „03“")
  void внос_по_167а() throws Exception {
    Move protocol = manual(42, "IMPORT_167A", "20", "60000", "12000");
    protocol.setPartner(supplier(40, null, "CN"));
    protocol.setOrigin("26BG001000000123R4");
    protocol.setOriginDate(JUNE_12);
    BgVatMoveDiaryRows.Row row = diaryRow(protocol);
    BgVatProtocolPrint print = BUILDER.build(protocol);

    assertEquals("чл. 117, ал. 1, т. 2 ЗДДС", print.getIssuedUnder());
    assertEquals("26BG001000000123R4", print.getCustomsDocument());
    assertEquals(JUNE_12, print.getCustomsDate());
    // Датата на вноса (чл. 54) — датата на митническия документ.
    assertEquals(JUNE_12, print.getTaxEventDate());
    assertEquals("03", print.getSupplyType());
    assertEquals("", print.getSupplierIdPoint(), "т. 9 и т. 10 не са за внос");
    assertEquals(row.getSalesTotalBase(), print.getBase());
    assertEquals(row.getSalesTotalVat(), print.getTax());
    assertEquals(new BigDecimal("12000.00"), print.getTax());
  }

  @Test
  @DisplayName("Услуга от трета страна без ДДС номер: номерът на доставчика е този от дневника")
  void доставчикът_е_този_от_дневника() throws Exception {
    Move protocol = manual(47, "SERVICE_NONEU", "20", "100", "20");
    protocol.setPartner(supplier(48, null, "US"));
    BgVatMoveDiaryRows.Row row = diaryRow(protocol);
    BgVatProtocolPrint print = BUILDER.build(protocol);

    assertTrue(!row.getDocument().getCounterpartyDiaryId().isEmpty());
    assertEquals(row.getDocument().getCounterpartyDiaryId(), print.getSupplierId());
    assertEquals("", print.getSupplierIdPoint(), "т. 9 е само за доставчик от друга ДЧ");
  }

  @Test
  @DisplayName("Ръчен протокол: основанието и т. 4/т. 3 без източник — ПРЕДУПРЕЖДЕНИЕ върху листа")
  void ръчният_протокол_казва_какво_липсва() throws Exception {
    BgVatProtocolPrint print = BUILDER.build(manual(43, "SERVICE_EU", "20", "100", "20"));

    assertEquals("чл. 82, ал. 2, т. 3 ЗДДС", print.getBasis());
    assertEquals(null, print.getTaxEventDate());
    assertEquals("Абонамент за софтуер, юни 2026", print.getItems().get(0).getName());
    assertEquals("", print.getItems().get(0).getQuantity());
    assertEquals("RE-2026-77", print.getInvoiceNumber());
    assertEquals("DE811907980", print.getSupplierId());
    String warnings = String.join("\n", print.getWarnings());
    assertTrue(warnings.contains("основанието"), warnings);
    assertTrue(warnings.contains("данъчното събитие"), warnings);
    assertTrue(warnings.contains("количеството"), warnings);
  }

  @Test
  @DisplayName("Отказ: статия без операция, протокол по чл. 79 и протокол без номер не се печатат")
  void отказите() throws Exception {
    Move plain = manual(44, "VOP", "20", "100", "20");
    plain.setBgVatMoveVatOperation(null);
    plain.getMoveLineList().forEach(l -> l.setBgVatCreditType(null));
    plain.getMoveLineList().forEach(l -> l.setTaxRate(BigDecimal.ZERO));
    plain.getMoveLineList().removeIf(l -> l.getAccount().getCode().startsWith("453"));
    assertThrows(AxelorException.class, () -> BUILDER.build(plain));

    assertThrows(
        AxelorException.class, () -> BUILDER.build(manual(45, "CORRECTION_79", "20", "100", "20")));

    Move unnumbered = manual(46, "VOP", "20", "100", "20");
    unnumbered.setBgVatProtocolNumber(null);
    AxelorException e = assertThrows(AxelorException.class, () -> BUILDER.build(unnumbered));
    assertTrue(e.getMessage().contains("номер"), e.getMessage());
  }
}

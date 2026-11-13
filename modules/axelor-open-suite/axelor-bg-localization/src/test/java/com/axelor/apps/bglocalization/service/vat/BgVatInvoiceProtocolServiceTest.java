package com.axelor.apps.bglocalization.service.vat;

import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.ACCOUNT_GOODS;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.EUR;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.USD;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.account;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.invoice;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.reverseCharge;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.supplier;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseColumn;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatValidationErrorDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Съставянето на протокола по чл. 117 от покупна фактура — статията, полетата ѝ, „веднъж“, заварен
 * ръчен протокол и отказите. Записът е в паметта ({@link BgVatInvoiceProtocolFixtures}); пътят през
 * базата е в {@code BgVatProtocolReachabilityIntegrationTest}.
 */
class BgVatInvoiceProtocolServiceTest {

  private static final LocalDate JUNE_10 = LocalDate.of(2026, 6, 10);
  private static final LocalDate JUNE_12 = LocalDate.of(2026, 6, 12);
  private static final LocalDate JUNE_20 = LocalDate.of(2026, 6, 20);

  private final BgVatInvoiceProtocolFixtures.InMemoryService service =
      new BgVatInvoiceProtocolFixtures.InMemoryService();

  private static Partner germany() {
    return supplier(7, "DE123456789", "DE");
  }

  private static Invoice vop(InvoiceLine... lines) {
    return invoice(1, germany(), JUNE_20, JUNE_12, JUNE_10, lines);
  }

  private static InvoiceLine vopLine(String base, String credit) {
    return reverseCharge("BG_VOP_ACQ", "BG_VOP_SELF", "20", base, credit, false);
  }

  private static void eq(String expected, BigDecimal actual) {
    assertEquals(
        0, new BigDecimal(expected).compareTo(actual), "очаквано " + expected + ", а е " + actual);
  }

  private static BgVatDocumentDto collect(Move move) {
    BgVatDocumentDto doc = new BgVatDocumentDto();
    new BgVatDocumentCollector(null).enrichFromMove(doc, move);
    return doc;
  }

  // ---------------------------------------------------------------------------
  // Статията
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("ВОП 1 000 / 20 %: статия като ръчния протокол + основа, основание, връзка, номер")
  void статията() throws Exception {
    Invoice invoice = vop(vopLine("1000", "1"));
    BgVatInvoiceProtocolService.Result result = service.generate(invoice, null);

    assertEquals(List.of(), result.getErrors());
    assertEquals(List.of(), result.getWarnings());
    assertEquals(1, result.getCreated().size());
    Move move = result.getCreated().get(0);
    assertEquals(MoveRepository.STATUS_ACCOUNTED, move.getStatusSelect());
    assertEquals("0000000001", move.getBgVatProtocolNumber(), "т. 1 — номерът при осчетоводяване");
    assertEquals(JUNE_20, move.getDate(), "т. 1 — max(изискуемост 12.06, фактура 20.06)");
    assertEquals("09", move.getNapDocumentTypeSelect());
    assertEquals("VOP", move.getBgVatMoveVatOperation());
    eq("0.2", move.getBgVatMoveVatRate()); // т. 6
    eq("1000.00", move.getBgVatProtocolBase()); // т. 5
    assertEquals("чл. 84 ЗДДС", move.getBgVatProtocolBasis()); // т. 7
    assertSame(invoice, move.getBgVatSourceInvoice());
    assertTrue(invoice.getBgVatProtocolMoveList().contains(move), "обратната посока");
    assertEquals(null, move.getInvoice(), "НЕ Move.invoice — колекторът би чел фактурата");
    assertSame(invoice.getPartner(), move.getPartner()); // т. 9 — ДДС номерът на доставчика
    assertEquals("INV-1", move.getOrigin()); // т. 9 — номерът на фактурата
    assertEquals(JUNE_12, move.getOriginDate()); // т. 9 — датата ѝ
    assertTrue(move.getDescription().contains("чл. 117, ал. 1, т. 1 ЗДДС — чл. 84 ЗДДС"));

    List<MoveLine> lines = move.getMoveLineList();
    assertEquals(2, lines.size());
    assertEquals("4531", lines.get(0).getAccount().getCode());
    eq("200", lines.get(0).getDebit());
    assertEquals("1", lines.get(0).getBgVatCreditType());
    assertEquals("4532", lines.get(1).getAccount().getCode());
    eq("200", lines.get(1).getCredit()); // т. 8
  }

  @Test
  @DisplayName("Ставките 20 % и 9 % — два протокола; основата не губи центове (1234.57 → 246.91)")
  void ставките() throws Exception {
    Invoice invoice =
        vop(
            vopLine("1234.57", "1"),
            reverseCharge("BG_VOP_ACQ", "BG_VOP_SELF", "9", "500", "1", false));
    BgVatInvoiceProtocolService.Result result = service.generate(invoice, null);

    assertEquals(List.of(), result.getErrors());
    assertEquals(2, result.getCreated().size());
    Move first = result.getCreated().get(0);
    eq("1234.57", first.getBgVatProtocolBase());
    eq("246.91", first.getMoveLineList().get(1).getCredit());
    eq(
        "1234.57",
        collect(first)
            .getSalesAmount(
                com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesColumn.K13_BASE_ICA));
    Move second = result.getCreated().get(1);
    eq("0.09", second.getBgVatMoveVatRate());
    eq("45.00", second.getMoveLineList().get(1).getCredit());
  }

  @Test
  @DisplayName(
      "Кредитният тип на реда: частичен → к.12/к.13; без право → дебит по сметката на реда, к.9")
  void кредитът() throws Exception {
    Invoice partial = vop(vopLine("1000", "2"));
    Move p = service.generate(partial, null).getCreated().get(0);
    BgVatDocumentDto doc = collect(p);
    eq("1000", doc.getPurchaseAmount(BgVatPurchaseColumn.K12_BASE_PARTIAL_CREDIT));
    eq("200", doc.getPurchaseAmount(BgVatPurchaseColumn.K13_VAT_PARTIAL_CREDIT));

    Invoice none = vop(vopLine("1000", "3"));
    none.setSupplierInvoiceNb("INV-NONE");
    Move n =
        new BgVatInvoiceProtocolFixtures.InMemoryService().generate(none, null).getCreated().get(0);
    MoveLine debit = n.getMoveLineList().get(0);
    assertEquals(Long.valueOf(ACCOUNT_GOODS), debit.getAccount().getId(), "данъкът е разход");
    assertEquals("3", debit.getBgVatCreditType());
    eq("1200", collect(n).getPurchaseAmount(BgVatPurchaseColumn.K09_BASE_NO_CREDIT));
  }

  @Test
  @DisplayName("Без кредитен тип на реда — пълен кредит с ПРЕДУПРЕЖДЕНИЕ")
  void без_кредитен_тип() throws Exception {
    BgVatInvoiceProtocolService.Result result = service.generate(vop(vopLine("100", null)), null);
    assertEquals(1, result.getCreated().size());
    assertTrue(
        result.getWarnings().stream().anyMatch(w -> w.contains("без вид данъчен кредит")),
        result.getWarnings().toString());
  }

  @Test
  @DisplayName(
      "Без дата на събитието — датата на фактурата на доставчика, с ПРЕДУПРЕЖДЕНИЕ; без двете — ГРЕШКА")
  void датата_на_събитието() throws Exception {
    Invoice invoice = vop(vopLine("100", "1"));
    invoice.setBgVatTaxEventDate(null);
    BgVatInvoiceProtocolService.Result result = service.generate(invoice, null);
    assertEquals(1, result.getCreated().size());
    assertTrue(result.getWarnings().get(0).contains("взета е датата на фактурата на доставчика"));

    Invoice none = vop(vopLine("100", "1"));
    none.setBgVatTaxEventDate(null);
    none.setOriginDate(null);
    BgVatInvoiceProtocolService.Result refused =
        new BgVatInvoiceProtocolFixtures.InMemoryService().generate(none, null);
    assertEquals(0, refused.getCreated().size());
    assertTrue(refused.getErrors().get(0).contains("чл. 117, ал. 2, т. 4"));
  }

  @Test
  @DisplayName("Исканата дата в срока — тя; извън срока — ГРЕШКА")
  void исканата_дата() throws Exception {
    // Изискуемост 12.06 (фактурата на доставчика преди 15.07 — чл. 63 ал. 4), срок 27.06.
    Move move =
        service.generate(vop(vopLine("100", "1")), LocalDate.of(2026, 6, 27)).getCreated().get(0);
    assertEquals(LocalDate.of(2026, 6, 27), move.getDate());

    BgVatInvoiceProtocolService.Result late =
        new BgVatInvoiceProtocolFixtures.InMemoryService()
            .generate(vop(vopLine("100", "1")), LocalDate.of(2026, 6, 28));
    assertEquals(0, late.getCreated().size());
    assertTrue(late.getErrors().get(0).contains("след срока по ЗДДС чл. 117, ал. 3"));
  }

  @Test
  @DisplayName(
      "ВОП: изискуемостта е от датата на фактурата на ДОСТАВЧИКА (чл. 63 ал. 4) — 01.03 / 03.03 / 10.03")
  void изискуемостта_от_датата_на_доставчика() throws Exception {
    // Събитие 01.03, фактура на доставчика 03.03 (преди 15.04 → D_ch = 03.03), въведена 10.03.
    Invoice invoice =
        invoice(
            9,
            germany(),
            LocalDate.of(2026, 3, 10),
            LocalDate.of(2026, 3, 3),
            LocalDate.of(2026, 3, 1),
            vopLine("100", "1"));
    BgVatInvoiceProtocolService.Result result = service.generate(invoice, null);
    assertEquals(List.of(), result.getErrors());
    assertEquals(
        LocalDate.of(2026, 3, 10), result.getCreated().get(0).getDate(), "max(03.03, 10.03)");

    // Срокът е 03.03 + 15 = 18.03 — от originDate, не от invoiceDate (10.03 + 15 = 25.03).
    Invoice last =
        invoice(
            10,
            germany(),
            LocalDate.of(2026, 3, 10),
            LocalDate.of(2026, 3, 3),
            LocalDate.of(2026, 3, 1),
            vopLine("100", "1"));
    assertEquals(
        LocalDate.of(2026, 3, 18),
        new BgVatInvoiceProtocolFixtures.InMemoryService()
            .generate(last, LocalDate.of(2026, 3, 18))
            .getCreated()
            .get(0)
            .getDate());
    Invoice after =
        invoice(
            11,
            germany(),
            LocalDate.of(2026, 3, 10),
            LocalDate.of(2026, 3, 3),
            LocalDate.of(2026, 3, 1),
            vopLine("100", "1"));
    BgVatInvoiceProtocolService.Result late =
        new BgVatInvoiceProtocolFixtures.InMemoryService()
            .generate(after, LocalDate.of(2026, 3, 19));
    assertEquals(0, late.getCreated().size());
    assertTrue(late.getErrors().get(0).contains("(2026-03-18)"), late.getErrors().get(0));
  }

  @Test
  @DisplayName(
      "ВОП без дата на фактурата на доставчика — ПРЕДУПРЕЖДЕНИЕ: изискуемостта е 15-о число (ал. 3)")
  void вопът_без_дата_на_доставчика() throws Exception {
    Invoice invoice =
        invoice(
            12,
            germany(),
            LocalDate.of(2026, 3, 20),
            null,
            LocalDate.of(2026, 3, 1),
            vopLine("100", "1"));
    BgVatInvoiceProtocolService.Result result = service.generate(invoice, null);
    assertEquals(List.of(), result.getErrors());
    // D_ch = 15.04 (ал. 3), а датата по подразбиране е max(15.04, 20.03) = 15.04.
    assertEquals(LocalDate.of(2026, 4, 15), result.getCreated().get(0).getDate());
    assertTrue(
        result.getWarnings().stream().anyMatch(w -> w.contains("чл. 63, ал. 4")),
        result.getWarnings().toString());
  }

  // ---------------------------------------------------------------------------
  // Отрицателните свидетели
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("Втори път — втори протокол НЕ се създава")
  void веднъж() throws Exception {
    Invoice invoice = vop(vopLine("1000", "1"));
    assertEquals(1, service.generate(invoice, null).getCreated().size());
    BgVatInvoiceProtocolService.Result again = service.generate(invoice, null);
    assertEquals(0, again.getCreated().size());
    assertEquals(1, invoice.getBgVatProtocolMoveList().size());
    assertTrue(again.getWarnings().get(0).contains("протоколът вече е съставен — № 0000000001"));
  }

  @Test
  @DisplayName(
      "Заварен ръчен протокол — свързва се с ПРЕДУПРЕЖДЕНИЕ, втори не се създава; разликата в сумите")
  void заварен_ръчен() throws Exception {
    Invoice invoice = vop(vopLine("1000", "1"));
    Move manual = new Move();
    manual.setId(77L);
    manual.setReference("ME-77");
    manual.setPartner(invoice.getPartner());
    manual.setOrigin("INV-1");
    manual.setDate(JUNE_20);
    manual.setStatusSelect(MoveRepository.STATUS_ACCOUNTED);
    manual.setBgVatMoveVatOperation("VOP");
    manual.setBgVatMoveVatRate(new BigDecimal("0.20"));
    manual.setBgVatProtocolNumber("0000000042");
    MoveLine charged = new MoveLine();
    charged.setAccount(account(4532L, "4532"));
    charged.setCredit(new BigDecimal("180.00"));
    manual.setMoveLineList(new ArrayList<>(List.of(charged)));
    service.manual.add(manual);

    BgVatInvoiceProtocolService.Result result = service.generate(invoice, null);
    assertEquals(0, result.getCreated().size());
    assertEquals(List.of(manual), result.getLinked());
    assertSame(invoice, manual.getBgVatSourceInvoice());
    String warning = result.getWarnings().get(0);
    assertTrue(warning.contains("вече има ръчен протокол № 0000000042"), warning);
    assertTrue(warning.contains("ръчният е основа 900.00 / данък 180.00"), warning);
    assertTrue(warning.contains("разлика в данъка 20.00"), warning);

    // И колекторът брои свързания ръчен протокол: фактурата вече не е „без протокол“.
    assertEquals(0, service.generate(invoice, null).getLinked().size(), "веднъж свързан");
  }

  @Test
  @DisplayName(
      "Дружество без поредица — протокол без номер, а дневникът дава VAT_PROTOCOL_NUMBER_MISSING")
  void без_номер() throws Exception {
    service.withSequence = false;
    BgVatInvoiceProtocolService.Result result = service.generate(vop(vopLine("1000", "1")), null);
    Move move = result.getCreated().get(0);
    assertEquals(null, move.getBgVatProtocolNumber());
    assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("протоколът няма номер")));
    List<String> codes =
        collect(move).getErrors().stream()
            .map(BgVatValidationErrorDto::getCode)
            .collect(Collectors.toList());
    assertEquals(List.of(BgVatValidator.PROTOCOL_NUMBER_MISSING_CODE), codes);
  }

  @Test
  @DisplayName("Известие (КИ и вид 02/03), авансова и балансова фактура — ГРЕШКА, без протокол")
  void известията_и_авансите() throws Exception {
    Invoice refund = vop(vopLine("100", "1"));
    refund.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND);
    assertRefused(refund, "чл. 117, ал. 4");

    Invoice debitNote = vop(vopLine("100", "1"));
    debitNote.setNapDocumentTypeSelect("02");
    assertRefused(debitNote, "чл. 117, ал. 4");

    Invoice advance = vop(vopLine("100", "1"));
    advance.setOperationSubTypeSelect(InvoiceRepository.OPERATION_SUB_TYPE_ADVANCE);
    assertRefused(advance, "чл. 25, ал. 7");

    Invoice balance = vop(vopLine("100", "1"));
    balance.setOperationSubTypeSelect(InvoiceRepository.OPERATION_SUB_TYPE_BALANCE);
    assertRefused(balance, "балансова фактура");

    Invoice draft = vop(vopLine("100", "1"));
    draft.setStatusSelect(InvoiceRepository.STATUS_VALIDATED);
    assertRefused(draft, "не е осчетоводена");
  }

  @Test
  @DisplayName("Случай без основание наред със законен — нито един протокол (без частични)")
  void без_частични() throws Exception {
    Invoice invoice =
        invoice(
            5,
            supplier(8, "BG175074752", "BG"),
            JUNE_20,
            JUNE_12,
            JUNE_10,
            reverseCharge("BG_RCM_SCRAP_P", "BG_RCM_SCRAP_S", "20", "100", "1", false),
            reverseCharge("BG_RCM_BUILD_P", "BG_RCM_BUILD_S", "20", "100", "1", true));
    assertRefused(invoice, "няма основание за обратно начисляване");
  }

  @Test
  @DisplayName("Подадена декларация или затворен период за датата — ГРЕШКА, никога тихо")
  void периодът() throws Exception {
    service.filedPeriods.add("202606");
    BgVatInvoiceProtocolService.Result filed = service.generate(vop(vopLine("100", "1")), null);
    assertEquals(0, filed.getCreated().size());
    assertTrue(filed.getErrors().get(0).contains("ДДС декларацията за 202606 е подадена"));

    BgVatInvoiceProtocolFixtures.InMemoryService closed =
        new BgVatInvoiceProtocolFixtures.InMemoryService();
    closed.closedDates.add(JUNE_20);
    BgVatInvoiceProtocolService.Result refused = closed.generate(vop(vopLine("100", "1")), null);
    assertEquals(0, refused.getCreated().size());
    assertTrue(refused.getErrors().get(0).contains("счетоводният период на 2026-06-20 е затворен"));
  }

  @Test
  @DisplayName("Подаден период с коригираща в corrected/generated — протоколът влиза в нея")
  void подаден_период_с_отворена_коригираща() throws Exception {
    service.filedPeriods.add("202606");
    service.openCorrections.add("202606");
    BgVatInvoiceProtocolService.Result open = service.generate(vop(vopLine("100", "1")), null);
    assertEquals(List.of(), open.getErrors());
    assertEquals(1, open.getCreated().size());

    // Коригираща за друг период не отваря юни.
    BgVatInvoiceProtocolFixtures.InMemoryService other =
        new BgVatInvoiceProtocolFixtures.InMemoryService();
    other.filedPeriods.add("202606");
    other.openCorrections.add("202605");
    BgVatInvoiceProtocolService.Result refused = other.generate(vop(vopLine("100", "1")), null);
    assertEquals(0, refused.getCreated().size());
    assertTrue(refused.getErrors().get(0).contains("ДДС декларацията за 202606 е подадена"));
  }

  // ---------------------------------------------------------------------------
  // Закъснелият протокол — ППЗДДС чл. 81 ал. 5 във вр. с ЗДДС чл. 73а; чл. 126 ал. 3 т. 2
  // ---------------------------------------------------------------------------

  /** ВОП: събитие 10.06, фактура на доставчика 12.06 (D_ch), въведена 05.07 — срокът е 27.06. */
  private static Invoice lateVop(long id) {
    return invoice(id, germany(), LocalDate.of(2026, 7, 5), JUNE_12, JUNE_10, vopLine("1000", "1"));
  }

  @Test
  @DisplayName(
      "Закъснял: издаден 05.07, статията на 12.06 (периодът на изискуемостта), ПРЕДУПРЕЖДЕНИЕ")
  void закъснелият() throws Exception {
    BgVatInvoiceProtocolService.Result result = service.generate(lateVop(6), null);

    assertEquals(List.of(), result.getErrors());
    Move move = result.getCreated().get(0);
    assertEquals(JUNE_12, move.getDate(), "дневниците — за периода на изискуемостта");
    assertEquals(
        LocalDate.of(2026, 7, 5), move.getBgVatProtocolDate(), "т. 1 — датата на издаване");
    assertEquals("0000000001", move.getBgVatProtocolNumber());
    assertTrue(
        result.getWarnings().stream()
            .anyMatch(
                w ->
                    w.contains("след срока по ЗДДС чл. 117, ал. 3 (2026-06-27)")
                        && w.contains("ППЗДДС чл. 81, ал. 5")
                        && w.contains("202606")),
        result.getWarnings().toString());
    assertTrue(move.getDescription().contains("закъснял"), move.getDescription());
    // В срока полето остава празно — датата е тази на статията.
    Move inTime =
        new BgVatInvoiceProtocolFixtures.InMemoryService()
            .generate(vop(vopLine("100", "1")), null)
            .getCreated()
            .get(0);
    assertEquals(null, inTime.getBgVatProtocolDate());
  }

  @Test
  @DisplayName(
      "Закъснял в подаден или затворен период на изискуемостта — ГРЕШКА с указанието по чл. 126 ал. 3 т. 2")
  void закъснелият_в_подаден_период() throws Exception {
    service.filedPeriods.add("202606");
    BgVatInvoiceProtocolService.Result filed = service.generate(lateVop(6), null);
    assertEquals(0, filed.getCreated().size());
    String error = filed.getErrors().get(0);
    assertTrue(error.contains("ДДС декларацията за 202606 е подадена"), error);
    assertTrue(error.contains("ЗДДС чл. 126, ал. 3, т. 2"), error);
    assertTrue(error.contains("ППЗДДС чл. 81, ал. 5"), error);

    BgVatInvoiceProtocolFixtures.InMemoryService closed =
        new BgVatInvoiceProtocolFixtures.InMemoryService();
    closed.closedDates.add(JUNE_12);
    String refused = closed.generate(lateVop(7), null).getErrors().get(0);
    assertTrue(refused.contains("счетоводният период на 2026-06-12"), refused);
    assertTrue(refused.contains("ЗДДС чл. 126, ал. 3, т. 2"), refused);
  }

  // ---------------------------------------------------------------------------
  // Валутата — ЗДДС чл. 26 ал. 6
  // ---------------------------------------------------------------------------

  /** ВОП за 1000 USD; по курса на фактурата 930 EUR. Изискуемост 12.06. */
  private static Invoice usd(long id) {
    InvoiceLine line = vopLine("1000", "1");
    line.setCompanyExTaxTotal(new BigDecimal("930.00"));
    Invoice invoice = invoice(id, germany(), JUNE_20, JUNE_12, JUNE_10, line);
    invoice.setCurrency(USD);
    return invoice;
  }

  @Test
  @DisplayName(
      "1000 USD: основата по последния курс на ЕЦБ с дата ≤ изискуемостта (12.06 → 1.08), не по-късния")
  void валутата() throws Exception {
    service.rate(EUR, USD, LocalDate.of(2026, 6, 1), "1.1000");
    service.rate(EUR, USD, LocalDate.of(2026, 6, 11), "1.0800");
    service.rate(EUR, USD, LocalDate.of(2026, 6, 13), "1.0500"); // след изискуемостта — не важи
    BgVatInvoiceProtocolService.Result result = service.generate(usd(8), null);

    assertEquals(List.of(), result.getErrors());
    Move move = result.getCreated().get(0);
    eq("925.93", move.getBgVatProtocolBase()); // 1000 / 1.08
    eq("185.19", move.getMoveLineList().get(1).getCredit());
    assertEquals(EUR, move.getCurrency(), "статията е във валутата на дружеството");
    assertTrue(
        move.getDescription().contains("курс на ЕЦБ 1 EUR = 1.08 USD от 2026-06-11"),
        move.getDescription());
    assertTrue(
        result.getWarnings().stream().anyMatch(w -> w.contains("925.93") && w.contains("930.00")),
        "разликата с курса на фактурата: " + result.getWarnings());
  }

  @Test
  @DisplayName("Курсът от самия ден на изискуемостта важи (да се потвърди); пряк USD→EUR също")
  void курсът_от_същия_ден() throws Exception {
    service.rate(USD, EUR, JUNE_12, "0.925000");
    Move move = service.generate(usd(9), null).getCreated().get(0);
    eq("925.00", move.getBgVatProtocolBase());
  }

  @Test
  @DisplayName("Пряк и обратен ред — по-новият (обратният EUR→USD от 11.06 бие прекия от 01.06)")
  void по_новият_курс() throws Exception {
    service.rate(USD, EUR, LocalDate.of(2026, 6, 1), "0.950000");
    service.rate(EUR, USD, LocalDate.of(2026, 6, 11), "1.0800");
    eq("925.93", service.generate(usd(12), null).getCreated().get(0).getBgVatProtocolBase());
  }

  @Test
  @DisplayName("Няма курс до изискуемостта — ГРЕШКА; стар курс (>7 дни) — ПРЕДУПРЕЖДЕНИЕ")
  void без_курс() throws Exception {
    service.rate(EUR, USD, LocalDate.of(2026, 6, 13), "1.0500"); // само след изискуемостта
    BgVatInvoiceProtocolService.Result none = service.generate(usd(10), null);
    assertEquals(0, none.getCreated().size());
    assertTrue(
        none.getErrors().get(0).contains("няма курс на ЕЦБ за USD с дата до 2026-06-12"),
        none.getErrors().get(0));

    BgVatInvoiceProtocolFixtures.InMemoryService stale =
        new BgVatInvoiceProtocolFixtures.InMemoryService();
    stale.rate(EUR, USD, LocalDate.of(2026, 6, 1), "1.1000");
    BgVatInvoiceProtocolService.Result old = stale.generate(usd(11), null);
    assertEquals(1, old.getCreated().size());
    assertTrue(
        old.getWarnings().stream()
            .anyMatch(w -> w.contains("последният курс на ЕЦБ е от 2026-06-01")),
        old.getWarnings().toString());
  }

  @Test
  @DisplayName("Фактура без обратно начисляване — само предупреждение")
  void без_обратно_начисляване() throws Exception {
    Invoice invoice = vop(BgVatInvoiceProtocolFixtures.plain("BG_SRP_20", "20", "100", "1"));
    BgVatInvoiceProtocolService.Result result = service.generate(invoice, null);
    assertEquals(0, result.getCreated().size());
    assertEquals(List.of(), result.getErrors());
    assertTrue(result.getWarnings().get(0).contains("няма редове с обратно начисляване"));
  }

  private void assertRefused(Invoice invoice, String reason) throws Exception {
    BgVatInvoiceProtocolService.Result result =
        new BgVatInvoiceProtocolFixtures.InMemoryService().generate(invoice, null);
    assertEquals(0, result.getCreated().size(), result.getErrors().toString());
    assertTrue(
        result.getErrors().stream().anyMatch(e -> e.contains(reason)),
        result.getErrors().toString());
  }
}

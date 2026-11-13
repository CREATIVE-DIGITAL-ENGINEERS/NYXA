package com.axelor.apps.bglocalization.service.vat;

import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.invoice;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.invoiceMove;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.plain;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.reverseCharge;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.supplier;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatDeclarationAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatViesAdapter;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatValidationErrorDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПОКУПНАТА ФАКТУРА С ОБРАТНО НАЧИСЛЯВАНЕ В ДНЕВНИЦИТЕ — според протокола ѝ по чл. 117.
 *
 * <ul>
 *   <li>ВОП и чл. 82: фактурата НЕ влиза в дневниците — основата и данъкът са на протокола, а
 *       номерът и датата ѝ са негов реквизит (ЗДДС чл. 117 ал. 2 т. 9; ППЗДДС чл. 81 ал. 3).
 *   <li>Чл. 163а: фактурата е в дневника за покупки само с колони 1–8а — ППЗДДС чл. 97а ал. 5:
 *       „Регистрираното лице, получател по доставките по ал. 1, отразява получената фактура и
 *       известия към нея в дневника за покупките, като попълва само данните в колони от 1 до 8а на
 *       дневника за покупките.“
 *   <li>Без протокол — ГРЕШКА {@link BgVatValidator#INVOICE_PROTOCOL_MISSING_CODE}, не {@code
 *       UNMAPPED_SALES_TAX_CODE}.
 * </ul>
 *
 * <p>Пътят е този на файла: {@code collect()} на колектора (подадени статии вместо JPQL) и филтрите
 * на {@link BgVatExportService}.
 */
class BgVatInvoiceProtocolDiaryTest {

  static final LocalDate JUNE_10 = LocalDate.of(2026, 6, 10);
  static final LocalDate JUNE_12 = LocalDate.of(2026, 6, 12);
  static final LocalDate JUNE_20 = LocalDate.of(2026, 6, 20);

  private static final class Collector extends BgVatDocumentCollector {
    private final List<Move> moves;

    Collector(List<Move> moves) {
      super(null);
      this.moves = moves;
    }

    @Override
    protected List<Move> findPostedMoves(BgVatExportRequestDto request) {
      return moves;
    }
  }

  private final BgVatSalesAdapter salesAdapter = new BgVatSalesAdapter();

  private BgVatExportService export(Move... moves) {
    return new BgVatExportService(
        new Collector(Arrays.asList(moves)),
        new BgVatValidator(),
        new BgVatPurchaseAdapter(),
        salesAdapter,
        new BgVatDeclarationAdapter(salesAdapter),
        new BgVatViesAdapter(salesAdapter),
        new BgVatFileWriter());
  }

  static BgVatExportRequestDto request() {
    BgVatExportRequestDto r = new BgVatExportRequestDto();
    r.setCompanyId(1L);
    r.setCompanyVatNumber("BG100000033");
    r.setPeriodFrom(LocalDate.of(2026, 6, 1));
    r.setPeriodTo(LocalDate.of(2026, 6, 30));
    return r;
  }

  private static BgVatDocumentDto invoiceDoc(BgVatExportService service, Move invoiceMove) {
    return invoiceDoc(service.collector.collect(request()), invoiceMove);
  }

  /** ⚠️ От СЪЩИЯ списък, който минава през филтрите — DTO-то няма equals. */
  private static BgVatDocumentDto invoiceDoc(List<BgVatDocumentDto> docs, Move invoiceMove) {
    return docs.stream()
        .filter(d -> d.getDocumentNumber().equals(invoiceMove.getInvoice().getSupplierInvoiceNb()))
        .findFirst()
        .orElseThrow();
  }

  private static List<String> codes(BgVatDocumentDto doc) {
    return doc.getErrors().stream()
        .map(BgVatValidationErrorDto::getCode)
        .collect(Collectors.toList());
  }

  private static Partner germany() {
    return supplier(7, "DE123456789", "DE");
  }

  private static Invoice vop() {
    return invoice(
        1,
        germany(),
        JUNE_20,
        JUNE_12,
        JUNE_10,
        reverseCharge("BG_VOP_ACQ", "BG_VOP_SELF", "20", "1000", "1", false));
  }

  /** Съставя протоколите на фактурата и връща първия. */
  private static Move generate(Invoice invoice) throws Exception {
    BgVatInvoiceProtocolService.Result result =
        new BgVatInvoiceProtocolFixtures.InMemoryService().generate(invoice, null);
    assertEquals(List.of(), result.getErrors());
    return result.getCreated().get(0);
  }

  // ---------------------------------------------------------------------------
  // ВОП и чл. 82 — фактурата не е в дневниците
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName(
      "ВОП с протокол: фактурата не е в нито един дневник, протоколът е к.13/к.15 + к.10/к.11")
  void вопът_с_протокол() throws Exception {
    Invoice invoice = vop();
    Move invoiceMove = invoiceMove(invoice);
    Move protocol = generate(invoice);
    BgVatExportService service = export(invoiceMove, protocol);
    List<BgVatDocumentDto> docs = service.collector.collect(request());

    BgVatDocumentDto doc = invoiceDoc(docs, invoiceMove);
    assertEquals(List.of(), codes(doc));
    assertTrue(doc.getPurchaseAmounts().isEmpty(), doc.getPurchaseAmounts().toString());
    assertTrue(doc.getSalesAmounts().isEmpty());
    assertTrue(doc.getAmounts().isEmpty(), "данъците с обратно начисляване не са на фактурата");
    assertFalse(service.filterSalesDocuments(docs).contains(doc));
    assertFalse(service.filterPurchaseDocuments(docs).contains(doc));

    BgVatDocumentDto row =
        docs.stream().filter(d -> "0000000001".equals(d.getDocumentNumber())).findFirst().get();
    assertTrue(service.filterSalesDocuments(docs).contains(row));
    assertTrue(service.filterPurchaseDocuments(docs).contains(row));
    eq("1000", row.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("200", row.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
  }

  @Test
  @DisplayName("ВОП без протокол: ГРЕШКА „без протокол“, не UNMAPPED_SALES_TAX_CODE и не к.10/к.11")
  void вопът_без_протокол() {
    Invoice invoice = vop();
    Move invoiceMove = invoiceMove(invoice);
    BgVatExportService service = export(invoiceMove);
    BgVatDocumentDto doc = invoiceDoc(service, invoiceMove);

    assertEquals(List.of(BgVatValidator.INVOICE_PROTOCOL_MISSING_CODE), codes(doc));
    assertTrue(
        doc.getErrors().get(0).getMessage().contains("„Състави протокол по чл. 117“"),
        doc.getErrors().get(0).getMessage());
    assertFalse(salesAdapter.isSalesDocument(doc), "BG_VOP_SELF вече не го прави продажба");
    assertEquals(List.of(), salesAdapter.unmappedTaxCodes(doc));
    // Самата фактура не дава 1000 + 200 в к.10/к.11 — иначе кредитът е двоен до протокола.
    assertTrue(doc.getPurchaseAmounts().isEmpty(), doc.getPurchaseAmounts().toString());
    List<BgVatValidationErrorDto> findings =
        new BgVatValidator().validateDocuments(List.of(doc), request());
    assertTrue(
        findings.stream()
            .anyMatch(
                f ->
                    BgVatValidator.INVOICE_PROTOCOL_MISSING_CODE.equals(f.getCode())
                        && BgVatValidationErrorDto.SEVERITY_ERROR.equals(f.getSeverity())));
  }

  @Test
  @DisplayName("Протокол, който не е осчетоводен — фактурата е пак „без протокол“, с указание")
  void неосчетоводеният_протокол() throws Exception {
    Invoice invoice = vop();
    Move invoiceMove = invoiceMove(invoice);
    Move protocol = generate(invoice);
    // Дневникът (daybook) не дава номер — той идва при окончателното осчетоводяване.
    protocol.setStatusSelect(MoveRepository.STATUS_DAYBOOK);
    protocol.setBgVatProtocolNumber(null);
    BgVatDocumentDto doc = invoiceDoc(export(invoiceMove), invoiceMove);

    assertEquals(List.of(BgVatValidator.INVOICE_PROTOCOL_MISSING_CODE), codes(doc));
    assertTrue(
        doc.getErrors().get(0).getMessage().contains("свързан, но неосчетоводен: MOV-1000"),
        doc.getErrors().get(0).getMessage());
  }

  @Test
  @DisplayName("Услуга по чл. 82 от ЕС с протокол — фактурата не е в дневниците")
  void услугата_по_чл82() throws Exception {
    Invoice invoice =
        invoice(
            2,
            germany(),
            JUNE_20,
            JUNE_12,
            JUNE_10,
            reverseCharge("BG_EU_S_RCM_P", "BG_RCM_ART82_S", "20", "1000", "1", true));
    Move invoiceMove = invoiceMove(invoice);
    Move protocol = generate(invoice);
    assertEquals("SERVICE_EU", protocol.getBgVatMoveVatOperation());
    BgVatExportService service = export(invoiceMove, protocol);
    List<BgVatDocumentDto> docs = service.collector.collect(request());
    BgVatDocumentDto doc = invoiceDoc(docs, invoiceMove);

    assertEquals(List.of(), codes(doc));
    assertFalse(service.filterSalesDocuments(docs).contains(doc));
    assertFalse(service.filterPurchaseDocuments(docs).contains(doc));
  }

  // ---------------------------------------------------------------------------
  // Чл. 163а — само колони 1–8а в покупките
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("163а (отпадъци) с протокол: фактурата е в покупките само с колони 1–8а, к.8а „01“")
  void чл163а_отпадъци() throws Exception {
    assertArt163a("BG_RCM_SCRAP_P", "BG_RCM_SCRAP_S", "01");
  }

  @Test
  @DisplayName("163а (зърно, Прил. № 2 ч. II) с протокол: к.8а „02“")
  void чл163а_зърно() throws Exception {
    assertArt163a("BG_RCM_GRAIN_P", "BG_RCM_GRAIN_S", "02");
  }

  private void assertArt163a(String own, String self, String supplyType) throws Exception {
    Partner local = supplier(8, "BG100000033", "BG");
    Invoice invoice =
        invoice(
            3, local, JUNE_20, JUNE_12, JUNE_10, reverseCharge(own, self, "20", "500", "1", false));
    Move invoiceMove = invoiceMove(invoice);
    Move protocol = generate(invoice);
    BgVatExportService service = export(invoiceMove, protocol);
    List<BgVatDocumentDto> docs = service.collector.collect(request());
    BgVatDocumentDto doc = invoiceDoc(docs, invoiceMove);

    assertEquals(List.of(), codes(doc));
    assertEquals(supplyType, doc.getSupplyTypeArt163a());
    assertTrue(doc.isIdentificationOnly());
    assertTrue(doc.getPurchaseAmounts().isEmpty(), doc.getPurchaseAmounts().toString());
    assertTrue(service.filterPurchaseDocuments(docs).contains(doc), "колони 1–8а в покупките");
    assertFalse(service.filterSalesDocuments(docs).contains(doc), "фактурата не е в продажбите");

    BgVatDocumentDto row =
        docs.stream().filter(d -> "0000000001".equals(d.getDocumentNumber())).findFirst().get();
    assertEquals(supplyType, row.getSupplyTypeArt163a());
  }

  // ---------------------------------------------------------------------------
  // Без протокол по решение
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("СМР с „обратно начисляване“ — ГРЕШКА с причината: няма основание")
  void смр() {
    Invoice invoice =
        invoice(
            4,
            supplier(8, "BG100000033", "BG"),
            JUNE_20,
            JUNE_12,
            JUNE_10,
            reverseCharge("BG_RCM_BUILD_P", "BG_RCM_BUILD_S", "20", "500", "1", true));
    BgVatDocumentDto doc = invoiceDoc(export(invoiceMove(invoice)), invoice.getMove());
    assertEquals(List.of(BgVatValidator.INVOICE_PROTOCOL_MISSING_CODE), codes(doc));
    assertTrue(
        doc.getErrors().get(0).getMessage().contains("няма основание за обратно начисляване"),
        doc.getErrors().get(0).getMessage());
  }

  @Test
  @DisplayName("Кредитно известие с обратно начисляване — ГРЕШКА: протокол по чл. 117 ал. 4")
  void известието() {
    Invoice invoice = vop();
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND);
    BgVatDocumentDto doc = invoiceDoc(export(invoiceMove(invoice)), invoice.getMove());
    assertEquals(List.of(BgVatValidator.INVOICE_PROTOCOL_MISSING_CODE), codes(doc));
    assertTrue(
        doc.getErrors().get(0).getMessage().contains("чл. 117, ал. 4"),
        doc.getErrors().get(0).getMessage());
  }

  @Test
  @DisplayName("Услуга от доставчик с „-EX“ — ПРЕДУПРЕЖДЕНИЕ, без протокол и без ГРЕШКА")
  void малкото_предприятие() {
    Invoice invoice =
        invoice(
            5,
            supplier(9, "BG100000033-EX", "AT"),
            JUNE_20,
            JUNE_12,
            JUNE_10,
            reverseCharge("BG_RCM_ART82_P", "BG_RCM_ART82_S", "20", "300", "1", true));
    BgVatExportService service = export(invoiceMove(invoice));
    BgVatDocumentDto doc = invoiceDoc(service, invoice.getMove());
    assertEquals(List.of(), codes(doc));
    assertTrue(
        doc.getWarnings().stream().anyMatch(w -> w.contains("„-EX“")),
        doc.getWarnings().toString());
  }

  @Test
  @DisplayName("Услуга извън ЕС с BG_NON_EU_SERV_P — ПРЕДУПРЕЖДЕНИЕ за мястото на изпълнение")
  void услугата_извън_ЕС() {
    Invoice invoice =
        invoice(
            6,
            supplier(10, null, "US"),
            JUNE_20,
            JUNE_12,
            JUNE_10,
            plain("BG_NON_EU_SERV_P", "0", "400", "1"));
    BgVatDocumentDto doc = invoiceDoc(export(invoiceMove(invoice)), invoice.getMove());
    assertEquals(List.of(), codes(doc));
    assertTrue(
        doc.getWarnings().stream().anyMatch(w -> w.contains("ЗДДС чл. 21")),
        doc.getWarnings().toString());
    eq("400", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
  }

  @Test
  @DisplayName("Тристранна (посредник, BG_TRIANG_P) — к.15 на покупките, без протокол и без ГРЕШКА")
  void тристранната() {
    Invoice invoice =
        invoice(7, germany(), JUNE_20, JUNE_12, JUNE_10, plain("BG_TRIANG_P", "0", "800", null));
    BgVatDocumentDto doc = invoiceDoc(export(invoiceMove(invoice)), invoice.getMove());
    assertEquals(List.of(), codes(doc));
    eq("800", doc.getPurchaseAmount(BgVatPurchaseColumn.K15_BASE_TRIANGULAR_ACQ));
  }

  @Test
  @DisplayName("Обикновената покупна фактура не мърда — регресия")
  void обикновената() {
    Invoice invoice =
        invoice(8, germany(), JUNE_20, JUNE_12, null, plain("BG_SRP_20", "20", "100", "1"));
    BgVatDocumentDto doc = invoiceDoc(export(invoiceMove(invoice)), invoice.getMove());
    assertEquals(List.of(), codes(doc));
    eq("100", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("20", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    assertEquals(new ArrayList<>(List.of("BG_SRP_20")), taxCodes(doc));
  }

  private static List<String> taxCodes(BgVatDocumentDto doc) {
    return doc.getAmounts().stream().map(a -> a.getTaxCode()).collect(Collectors.toList());
  }

  static void eq(String expected, BigDecimal actual) {
    assertEquals(
        0, new BigDecimal(expected).compareTo(actual), "очаквано " + expected + ", а е " + actual);
  }

  // ---------------------------------------------------------------------------
  // Данъчните редове на 4531/4532 (BG_COA: сметки от тип „данък“) — дневникът е същият
  // ---------------------------------------------------------------------------

  /** Протоколът с изчистени данъчни редове — както беше преди поправката. */
  private static Move withoutTaxLines(Move protocol) {
    for (MoveLine line : protocol.getMoveLineList()) {
      line.setTaxLineSet(new java.util.HashSet<>());
    }
    return protocol;
  }

  private static BgVatDocumentDto protocolRow(List<BgVatDocumentDto> docs) {
    return docs.stream().filter(d -> "0000000001".equals(d.getDocumentNumber())).findFirst().get();
  }

  @Test
  @DisplayName(
      "Протоколът по чл. 117: 4531 носи данъка на кредита, 4532 — самоначисления; в дневниците е"
          + " точно един ред за покупки и един за продажби, със сумите отпреди")
  void данъчните_редове_на_протокола_не_местят_дневниците() throws Exception {
    Invoice invoice = vop();
    Move invoiceMove = invoiceMove(invoice);
    Move protocol = generate(invoice);

    java.util.Map<String, String> taxByAccount = new java.util.HashMap<>();
    for (MoveLine line : protocol.getMoveLineList()) {
      assertEquals(1, line.getTaxLineSet().size(), line.getAccount().getCode());
      taxByAccount.put(
          line.getAccount().getCode(), line.getTaxLineSet().iterator().next().getTax().getCode());
    }
    assertEquals("BG_VOP_ACQ", taxByAccount.get("4531"), "правото на кредит (покупката)");
    assertEquals("BG_VOP_SELF", taxByAccount.get("4532"), "самоначисленият данък");

    BgVatExportService service = export(invoiceMove, protocol);
    List<BgVatDocumentDto> docs = service.collector.collect(request());
    BgVatDocumentDto row = protocolRow(docs);
    assertEquals(1, service.filterPurchaseDocuments(docs).stream().filter(d -> d == row).count());
    assertEquals(1, service.filterSalesDocuments(docs).stream().filter(d -> d == row).count());
    assertEquals(
        1,
        service.filterPurchaseDocuments(docs).stream()
            .filter(d -> "0000000001".equals(d.getDocumentNumber()))
            .count(),
        "един ред в дневника за покупки");
    assertEquals(
        1,
        service.filterSalesDocuments(docs).stream()
            .filter(d -> "0000000001".equals(d.getDocumentNumber()))
            .count(),
        "един ред в дневника за продажби");

    // Същият протокол без данъчните редове — сумите в дневниците и общият ДДС са същите.
    BgVatExportService before = export(invoiceMove, withoutTaxLines(generate(vop())));
    BgVatDocumentDto old = protocolRow(before.collector.collect(request()));
    assertEquals(old.getPurchaseAmounts(), row.getPurchaseAmounts());
    assertEquals(old.getSalesAmounts(), row.getSalesAmounts());
    assertEquals(0, old.getTotalVat().compareTo(row.getTotalVat()));
    eq("1000", row.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("200", row.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
  }
}

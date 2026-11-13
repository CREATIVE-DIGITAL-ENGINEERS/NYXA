package com.axelor.apps.bglocalization.service.vat;

import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.invoice;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.invoiceMove;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.manualEquivalent;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.reverseCharge;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.supplier;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatDeclarationAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatViesAdapter;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExclusionsDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportResultDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatGeneratedFileDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatPurchaseRowDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatSalesRowDto;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРОТОКОЛЪТ ОТ ФАКТУРА Е СЪЩИЯТ ВИД ЗАПИС КАТО РЪЧНИЯ.
 *
 * <p>Съставен от покупна фактура ({@link BgVatInvoiceProtocolService}) и еквивалентен ръчен
 * протокол — същата статия, без изричната основа, основанието и връзката — дават ЕДНИ И СЪЩИ
 * PRODAGBI.TXT, POKUPKI.TXT и DEKLAR.TXT, байт по байт, през целия {@link
 * BgVatExportService#generate}. После — числата от образеца на всеки случай и какво добавя самата
 * фактура: при ВОП и чл. 82 нищо, при чл. 163а — един ред в покупките само с колони 1–8а.
 *
 * <p>Полетата на дневниците (ППЗДДС Прил. № 12): 02-12 = к.13, 02-26 = к.14, 02-22 = к.15; DEKLAR
 * 01-12 = [02-12] + [02-26], 01-22 = [02-22], 01-31 = [03-31] (к.10), 01-41 = [03-41] (к.11).
 */
class BgVatInvoiceProtocolEquivalenceTest {

  private static final LocalDate JUNE_10 = LocalDate.of(2026, 6, 10);
  private static final LocalDate JUNE_12 = LocalDate.of(2026, 6, 12);
  private static final LocalDate JUNE_20 = LocalDate.of(2026, 6, 20);

  /** Клетките на DEKLAR.TXT в реда на записа; всяка е 15 знака, кл.33 — 4. */
  private static final List<String> CELLS =
      Arrays.asList(
          "01", "20", "11", "21", "12", "22", "23", "13", "24", "14", "15", "16", "17", "18", "19",
          "30", "31", "41", "32", "42", "43", "33", "40", "50", "60", "70", "71", "80", "81", "82");

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

    /** Без репозиторий: изключените статии са празни. */
    @Override
    public BgVatExclusionsDto excludedMoves(BgVatExportRequestDto request) {
      return new BgVatExclusionsDto();
    }
  }

  /** Целият {@code generate()}; качването е подменено — файловете остават в паметта. */
  private static final class Export extends BgVatExportService {
    private final Map<String, byte[]> files = new LinkedHashMap<>();

    Export(List<Move> moves, BgVatSalesAdapter sales) {
      super(
          new Collector(moves),
          new BgVatValidator(),
          new BgVatPurchaseAdapter(),
          sales,
          new BgVatDeclarationAdapter(sales),
          new BgVatViesAdapter(sales),
          new BgVatFileWriter());
    }

    @Override
    protected BgVatGeneratedFileDto uploadFile(
        BgVatExportResultDto result, String scope, String fileName, byte[] bytes) {
      files.put(fileName, bytes);
      BgVatGeneratedFileDto file = new BgVatGeneratedFileDto(scope, fileName, null, null, bytes);
      result.getGeneratedFiles().add(file);
      return file;
    }
  }

  /** Един пробег: файловете и редовете на дневниците. */
  private static final class Run {
    final Map<String, byte[]> files;
    final BgVatExportResultDto result;

    Run(Map<String, byte[]> files, BgVatExportResultDto result) {
      this.files = files;
      this.result = result;
    }

    String text(String file) {
      byte[] bytes = files.get(file);
      assertNotNull(
          bytes,
          file + " не е генериран: " + result.getWarnings() + " / " + result.getValidationErrors());
      return new String(bytes, Charset.forName("windows-1251"));
    }

    /** Клетките на DEKLAR и броят на редовете в двата дневника. */
    Map<String, BigDecimal> deklar() {
      String record = text("deklar.txt").split("\r?\n")[0];
      Map<String, BigDecimal> cells = new LinkedHashMap<>();
      cells.put("брой продажби", new BigDecimal(record.substring(121, 136).trim()));
      cells.put("брой покупки", new BigDecimal(record.substring(136, 151).trim()));
      int at = 151;
      for (String cell : CELLS) {
        int width = "33".equals(cell) ? 4 : 15;
        cells.put(cell, new BigDecimal(record.substring(at, at + width).trim()));
        at += width;
      }
      return cells;
    }
  }

  private static Run run(Move... moves) {
    BgVatSalesAdapter sales = new BgVatSalesAdapter();
    Export export = new Export(Arrays.asList(moves), sales);
    BgVatExportRequestDto request = new BgVatExportRequestDto();
    request.setCompanyId(1L);
    request.setCompanyName("Дружество");
    request.setCompanyVatNumber("BG100000033");
    request.setPeriodFrom(LocalDate.of(2026, 6, 1));
    request.setPeriodTo(LocalDate.of(2026, 6, 30));
    request.setReportType("all");
    request.setEncoding("cp1251");
    request.setCoefficientK33(new BigDecimal("0.50"));
    BgVatExportResultDto result = export.generate(request);
    return new Run(export.files, result);
  }

  /** Протоколът от фактурата и ръчният му еквивалент — съставен от СЪЩИТЕ данни. */
  private static Move[] generatedAndManual(Invoice invoice) throws Exception {
    BgVatInvoiceProtocolService.Result result =
        new BgVatInvoiceProtocolFixtures.InMemoryService().generate(invoice, null);
    assertEquals(List.of(), result.getErrors());
    assertEquals(1, result.getCreated().size());
    Move generated = result.getCreated().get(0);
    Move manual = manualEquivalent(generated);
    assertNull(manual.getBgVatSourceInvoice());
    assertEquals(0, manual.getBgVatProtocolBase().signum(), "ръчният е без изрична основа");
    return new Move[] {generated, manual};
  }

  /** Двата пробега дават едни и същи три файла, байт по байт. */
  private static Run assertSameFiles(Move generated, Move manual) {
    Run fromInvoice = run(generated);
    Run byHand = run(manual);
    for (String file : List.of("prodagbi.txt", "pokupki.txt", "deklar.txt")) {
      fromInvoice.text(file);
      assertArrayEquals(byHand.files.get(file), fromInvoice.files.get(file), file);
    }
    return fromInvoice;
  }

  private static void eq(String expected, BigDecimal actual, String what) {
    assertEquals(
        0,
        new BigDecimal(expected).compareTo(actual == null ? BigDecimal.ZERO : actual),
        what + ": очаквано " + expected + ", а е " + actual);
  }

  /** Самоначисляване с пълен кредит: всички клетки освен изброените са нула. */
  private static void assertDeklar(Map<String, BigDecimal> cells, Map<String, String> expected) {
    for (Map.Entry<String, BigDecimal> cell : cells.entrySet()) {
      String want = expected.getOrDefault(cell.getKey(), "0");
      eq(want, cell.getValue(), "DEKLAR кл." + cell.getKey());
    }
  }

  private static Map<String, String> selfCharge(String base, String tax) {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("брой продажби", "1");
    m.put("брой покупки", "1");
    m.put("01", base); // 01-01 = [02-10]
    m.put("20", tax); // 01-20 = [02-20]
    m.put("12", base); // 01-12 = [02-12] + [02-26]
    m.put("22", tax); // 01-22 = [02-22]
    m.put("31", base); // 01-31 = [03-31]
    m.put("41", tax); // 01-41 = [03-41]
    m.put("40", tax); // 01-40 = [01-41] + [01-42] × [01-33] + [01-43]
    m.put("33", "0.50"); // кл.33 — подаден, не мърда
    return m;
  }

  // ---------------------------------------------------------------------------
  // Случаите
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("ВОП 1 000 EUR / 20 %: к.13 1000, к.15 200; к.10/к.11; DEKLAR 01-12 1000, 01-22 200")
  void вопът() throws Exception {
    Partner germany = supplier(7, "DE123456789", "DE");
    Invoice invoice =
        invoice(
            1,
            germany,
            JUNE_20,
            JUNE_12,
            JUNE_10,
            reverseCharge("BG_VOP_ACQ", "BG_VOP_SELF", "20", "1000", "1", false));
    Move invoiceMove = invoiceMove(invoice);
    Move[] pair = generatedAndManual(invoice);
    Run run = assertSameFiles(pair[0], pair[1]);

    BgVatSalesRowDto sale = run.result.getSalesRows().get(0);
    assertEquals("0000000001", sale.getDocumentNumber());
    assertEquals("09", sale.getNapDocumentType());
    assertEquals(JUNE_20, sale.getDocumentDate(), "max(изискуемост 12.06, фактура 20.06)");
    eq("1000", sale.getAmount(BgVatSalesColumn.K13_BASE_ICA), "к.13");
    eq("200", sale.getAmount(BgVatSalesColumn.K15_VAT_ICA_RC), "к.15");
    eq("0", sale.getAmount(BgVatSalesColumn.K14_BASE_RC), "к.14");
    BgVatPurchaseRowDto purchase = run.result.getPurchaseRows().get(0);
    eq("1000", purchase.getAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT), "к.10");
    eq("200", purchase.getAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT), "к.11");
    Map<String, BigDecimal> cells = run.deklar();
    assertDeklar(cells, selfCharge("1000", "200"));

    // Самата фактура не добавя нищо — нито ред, нито клетка.
    Run withInvoice = run(invoiceMove, pair[0]);
    for (String file : List.of("prodagbi.txt", "pokupki.txt", "deklar.txt")) {
      assertArrayEquals(run.files.get(file), withInvoice.files.get(file), "с фактурата: " + file);
    }
  }

  @Test
  @DisplayName("Чл. 82 (услуга от ЕС) 1 000 / 20 %: к.14 1000, к.15 200; DEKLAR 01-12 1000 (02-26)")
  void чл82() throws Exception {
    Invoice invoice =
        invoice(
            2,
            supplier(7, "DE123456789", "DE"),
            JUNE_20,
            JUNE_12,
            JUNE_10,
            reverseCharge("BG_EU_S_RCM_P", "BG_RCM_ART82_S", "20", "1000", "1", true));
    Move invoiceMove = invoiceMove(invoice);
    Move[] pair = generatedAndManual(invoice);
    assertEquals(
        JUNE_20, pair[0].getDate(), "max(събитие 10.06, фактура 20.06) — в срока до 25.06");
    Run run = assertSameFiles(pair[0], pair[1]);

    BgVatSalesRowDto sale = run.result.getSalesRows().get(0);
    eq("0", sale.getAmount(BgVatSalesColumn.K13_BASE_ICA), "к.13");
    eq("1000", sale.getAmount(BgVatSalesColumn.K14_BASE_RC), "к.14");
    eq("200", sale.getAmount(BgVatSalesColumn.K15_VAT_ICA_RC), "к.15");
    assertDeklar(run.deklar(), selfCharge("1000", "200"));

    Run withInvoice = run(invoiceMove, pair[0]);
    for (String file : List.of("prodagbi.txt", "pokupki.txt", "deklar.txt")) {
      assertArrayEquals(run.files.get(file), withInvoice.files.get(file), "с фактурата: " + file);
    }
  }

  @Test
  @DisplayName(
      "163а с к.8а „01“: к.14/к.15 и к.8а в двата дневника; фактурата — +1 ред само с 1–8а")
  void чл163а_01() throws Exception {
    assertArt163a(reverseCharge("BG_RCM_SCRAP_P", "BG_RCM_SCRAP_S", "20", "500", "1", false), "01");
  }

  @Test
  @DisplayName("163а с к.8а „02“ (Прил. № 2 ч. II): същото с „02“")
  void чл163а_02() throws Exception {
    assertArt163a(reverseCharge("BG_RCM_GRAIN_P", "BG_RCM_GRAIN_S", "20", "500", "1", false), "02");
  }

  private void assertArt163a(InvoiceLine line, String supplyType) throws Exception {
    Invoice invoice = invoice(3, supplier(8, "BG175074752", "BG"), JUNE_20, JUNE_12, JUNE_10, line);
    Move invoiceMove = invoiceMove(invoice);
    Move[] pair = generatedAndManual(invoice);
    assertEquals(supplyType, pair[0].getBgVatSupplyType());
    Run run = assertSameFiles(pair[0], pair[1]);

    BgVatSalesRowDto sale = run.result.getSalesRows().get(0);
    eq("500", sale.getAmount(BgVatSalesColumn.K14_BASE_RC), "к.14");
    eq("100", sale.getAmount(BgVatSalesColumn.K15_VAT_ICA_RC), "к.15");
    assertEquals(supplyType, sale.getSupplyTypeArt163a());
    BgVatPurchaseRowDto purchase = run.result.getPurchaseRows().get(0);
    assertEquals(supplyType, purchase.getSupplyTypeArt163a());
    eq("500", purchase.getAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT), "к.10");
    eq("100", purchase.getAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT), "к.11");
    assertDeklar(run.deklar(), selfCharge("500", "100"));

    // С фактурата: PRODAGBI не мърда; POKUPKI има втори ред — номерът на доставчика, к.8а, без
    // суми (ППЗДДС чл. 97а ал. 5); DEKLAR брои реда, но нито една клетка за стойност не мърда.
    Run withInvoice = run(invoiceMove, pair[0]);
    assertArrayEquals(run.files.get("prodagbi.txt"), withInvoice.files.get("prodagbi.txt"));
    assertEquals(2, withInvoice.result.getPurchaseRows().size());
    BgVatPurchaseRowDto invoiceRow =
        withInvoice.result.getPurchaseRows().stream()
            .filter(r -> "INV-3".equals(r.getDocumentNumber()))
            .findFirst()
            .orElseThrow();
    assertEquals("01", invoiceRow.getNapDocumentType());
    assertEquals(supplyType, invoiceRow.getSupplyTypeArt163a());
    assertTrue(
        invoiceRow.getAmounts().values().stream().allMatch(v -> v.signum() == 0),
        invoiceRow.getAmounts().toString());
    Map<String, String> expected = selfCharge("500", "100");
    expected.put("брой покупки", "2");
    assertDeklar(withInvoice.deklar(), expected);
  }

  @Test
  @DisplayName(
      "Закъснял ВОП: в дневниците на юни (изискуемостта) с дата 05.07; = ръчния със същата дата")
  void закъснелият() throws Exception {
    Invoice invoice =
        invoice(
            5,
            supplier(7, "DE123456789", "DE"),
            LocalDate.of(2026, 7, 5),
            JUNE_12,
            JUNE_10,
            reverseCharge("BG_VOP_ACQ", "BG_VOP_SELF", "20", "1000", "1", false));
    Move[] pair = generatedAndManual(invoice);
    assertEquals(JUNE_12, pair[0].getDate());
    Run run = assertSameFiles(pair[0], pair[1]);

    BgVatSalesRowDto sale = run.result.getSalesRows().get(0);
    assertEquals(LocalDate.of(2026, 7, 5), sale.getDocumentDate(), "т. 1 — датата на издаване");
    assertEquals(LocalDate.of(2026, 7, 5), run.result.getPurchaseRows().get(0).getDocumentDate());
    assertDeklar(run.deklar(), selfCharge("1000", "200"));
    // Не общото „Document date … is outside the selected period“, а обяснението по ал. 5.
    assertTrue(
        run.result.getValidationErrors().stream()
            .noneMatch(e -> "DATE_PERIOD_INCONSISTENT".equals(e.getCode())),
        run.result.getValidationErrors().toString());
    assertTrue(
        run.result.getValidationErrors().stream()
            .anyMatch(
                e ->
                    e.getMessage().contains("ППЗДДС чл. 81, ал. 5")
                        && e.getMessage().contains("2026-07-05")),
        run.result.getValidationErrors().toString());
  }

  @Test
  @DisplayName("1000 USD при 1 EUR = 1.08 USD: к.13 925.93, к.15 185.19; = ръчния протокол в евро")
  void валутата() throws Exception {
    InvoiceLine line = reverseCharge("BG_VOP_ACQ", "BG_VOP_SELF", "20", "1000", "1", false);
    line.setCompanyExTaxTotal(new BigDecimal("930.00"));
    Invoice invoice = invoice(6, supplier(7, "DE123456789", "DE"), JUNE_20, JUNE_12, JUNE_10, line);
    invoice.setCurrency(BgVatInvoiceProtocolFixtures.USD);
    BgVatInvoiceProtocolFixtures.InMemoryService service =
        new BgVatInvoiceProtocolFixtures.InMemoryService();
    service.rate(
        BgVatInvoiceProtocolFixtures.EUR,
        BgVatInvoiceProtocolFixtures.USD,
        LocalDate.of(2026, 6, 11),
        "1.0800");
    BgVatInvoiceProtocolService.Result result = service.generate(invoice, null);
    assertEquals(List.of(), result.getErrors());
    Move generated = result.getCreated().get(0);
    // Свидетелят: без изрична основа ръчният протокол губи центове — 185.19 / 0.20 = 925.95.
    Run lossy = run(manualEquivalent(generated));
    eq(
        "925.95",
        lossy.result.getSalesRows().get(0).getAmount(BgVatSalesColumn.K13_BASE_ICA),
        "к.13");
    // …и не тихо: предупреждение с изведената основа.
    assertTrue(
        lossy.result.getValidationErrors().stream()
            .anyMatch(
                e ->
                    BgVatValidator.PROTOCOL_BASE_DERIVED_CODE.equals(e.getCode())
                        && e.getMessage().contains("925.95")),
        lossy.result.getValidationErrors().toString());
    // Ръчният протокол във валута носи основата си изрично (полето е и на ръчната статия).
    Move manual = manualEquivalent(generated);
    manual.setBgVatProtocolBase(generated.getBgVatProtocolBase());
    Run run = assertSameFiles(generated, manual);

    BgVatSalesRowDto sale = run.result.getSalesRows().get(0);
    eq("925.93", sale.getAmount(BgVatSalesColumn.K13_BASE_ICA), "к.13");
    eq("185.19", sale.getAmount(BgVatSalesColumn.K15_VAT_ICA_RC), "к.15");
    assertDeklar(run.deklar(), selfCharge("925.93", "185.19"));
  }

  @Test
  @DisplayName(
      "Свидетел: без протокол фактурата за ВОП не дава файлове — ГРЕШКА, не тих двоен кредит")
  void без_протокол() {
    Invoice invoice =
        invoice(
            4,
            supplier(7, "DE123456789", "DE"),
            JUNE_20,
            JUNE_12,
            JUNE_10,
            reverseCharge("BG_VOP_ACQ", "BG_VOP_SELF", "20", "1000", "1", false));
    Run run = run(invoiceMove(invoice));
    // ГРЕШКА в резултата — пакетът не е готов (BgVatPackageBuilder: READINESS_ERRORS).
    assertTrue(
        run.result.getValidationErrors().stream()
            .anyMatch(
                e ->
                    BgVatValidator.INVOICE_PROTOCOL_MISSING_CODE.equals(e.getCode())
                        && "ERROR".equals(e.getSeverity())),
        run.result.getValidationErrors().toString());
    // Нито ред в покупките от самата фактура — старият път беше к.10 1000 / к.11 200.
    assertEquals(0, run.result.getPurchaseRows().size());
    assertEquals(0, run.result.getSalesRows().size());
  }
}

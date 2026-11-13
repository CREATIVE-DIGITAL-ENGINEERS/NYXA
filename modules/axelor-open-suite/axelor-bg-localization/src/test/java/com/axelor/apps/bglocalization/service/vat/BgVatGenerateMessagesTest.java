package com.axelor.apps.bglocalization.service.vat;

import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.invoice;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.invoiceMove;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.plain;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.reverseCharge;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.supplier;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatDeclarationAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatViesAdapter;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExclusionsDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportResultDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatGeneratedFileDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatPurchaseRowDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatValidationErrorDto;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * „ГЕНЕРИРАЙ“ НЕ МЪЛЧИ: осчетоводена покупка, а декларацията е нулева — потребителят вижда защо, на
 * български, а не само в резюмето на пакета. Тук: защо статия или документ изпада (DAYBOOK,
 * чернова, извън периода, друго дружество, нулеви суми, обратно начисляване), текстовете на
 * български и диалогът във формата.
 */
class BgVatGenerateMessagesTest {

  static final LocalDate JUNE_10 = LocalDate.of(2026, 6, 10);

  /** Събирач без база: статиите на всяка заявка са подадени. */
  private static final class Collector extends BgVatDocumentCollector {
    final List<Move> posted = new ArrayList<>();
    final List<Move> unposted = new ArrayList<>();
    final List<Move> outsidePeriod = new ArrayList<>();
    final List<Move> otherCompany = new ArrayList<>();
    final List<Move> reportedElsewhere = new ArrayList<>();

    Collector() {
      super(null);
    }

    @Override
    protected List<Move> findPostedMoves(BgVatExportRequestDto request) {
      return posted;
    }

    @Override
    protected List<Move> findUnpostedMoves(BgVatExportRequestDto request) {
      return unposted;
    }

    @Override
    protected List<Move> findMovesOutsidePeriod(BgVatExportRequestDto request) {
      return outsidePeriod;
    }

    @Override
    protected List<Move> findMovesInOtherCompany(BgVatExportRequestDto request) {
      return otherCompany;
    }

    @Override
    protected List<Move> findMovesReportedElsewhere(BgVatExportRequestDto request) {
      return reportedElsewhere;
    }
  }

  /** Целият {@code generate()}; качването е подменено — файловете остават в паметта. */
  private static final class Export extends BgVatExportService {
    Export(Collector collector) {
      this(collector, new BgVatSalesAdapter());
    }

    private Export(Collector collector, BgVatSalesAdapter sales) {
      super(
          collector,
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
      BgVatGeneratedFileDto file = new BgVatGeneratedFileDto(scope, fileName, null, null, bytes);
      result.getGeneratedFiles().add(file);
      return file;
    }
  }

  /** Както го праща декларацията: всичките четири файла, и празните. */
  private static BgVatExportRequestDto request() {
    BgVatExportRequestDto request = new BgVatExportRequestDto();
    request.setCompanyId(1L);
    request.setCompanyName("Дружество");
    request.setCompanyVatNumber("BG100000033");
    request.setPeriodFrom(LocalDate.of(2026, 6, 1));
    request.setPeriodTo(LocalDate.of(2026, 6, 30));
    request.setReportType("all");
    request.setEncoding("cp1251");
    request.setEmitEmptyFiles(true);
    return request;
  }

  private static Move move(long id, String reference, int status) {
    Move move = new Move();
    move.setId(id);
    move.setReference(reference);
    move.setStatusSelect(status);
    return move;
  }

  /** Основа 150 и ДДС 30 в документа, а във валутата на дружеството — нули. */
  private static Invoice zeroInCompanyCurrency() {
    InvoiceLine line = plain("BG_SRP_20", "20", "150", "1");
    line.setCompanyExTaxTotal(BigDecimal.ZERO);
    line.setCompanyInTaxTotal(BigDecimal.ZERO);
    Invoice invoice = invoice(2, supplier(5, "BG100000033", "BG"), JUNE_10, JUNE_10, JUNE_10, line);
    invoice.setSupplierInvoiceNb("0000009001");
    return invoice;
  }

  private static BgVatDocumentDto document(Invoice invoice) {
    Collector collector = new Collector();
    collector.posted.add(invoiceMove(invoice));
    return collector.collect(request()).get(0);
  }

  // ---------------------------------------------------------------------------
  // Събирачът: защо статията не е взета, защо документът изпада
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("неосчетоводените статии: DAYBOOK отделно от черновите; без номер — „статия id N“")
  void неосчетоводените_по_статус() {
    Collector collector = new Collector();
    collector.unposted.addAll(
        Arrays.asList(
            move(1, "PCH260001", MoveRepository.STATUS_DAYBOOK),
            move(2, null, MoveRepository.STATUS_NEW),
            move(3, " ", MoveRepository.STATUS_SIMULATED)));

    BgVatExclusionsDto exclusions = collector.excludedMoves(request());

    assertEquals(List.of("PCH260001"), exclusions.getDaybookMoves());
    assertEquals(List.of("статия id 2", "статия id 3"), exclusions.getDraftMoves());
    assertTrue(exclusions.getOutsidePeriodMoves().isEmpty());
  }

  @Test
  @DisplayName("извън периода и друго дружество — по номера на статията")
  void извън_периода_и_друго_дружество() {
    Collector collector = new Collector();
    collector.outsidePeriod.add(move(4, "PCH260007", MoveRepository.STATUS_ACCOUNTED));
    collector.otherCompany.add(move(5, "PCH260002", MoveRepository.STATUS_ACCOUNTED));

    BgVatExclusionsDto exclusions = collector.excludedMoves(request());

    assertEquals(List.of("PCH260007"), exclusions.getOutsidePeriodMoves());
    assertEquals(List.of("PCH260002"), exclusions.getOtherCompanyMoves());
  }

  @Test
  @DisplayName(
      "отразена в друг период — номерът на статията и периодът ѝ, отделно от „извън периода“")
  void отразена_в_друг_период() {
    Collector collector = new Collector();
    Move move = move(6, "SAL260123", MoveRepository.STATUS_ACCOUNTED);
    Invoice invoice = new Invoice();
    invoice.setBgVatReportingPeriod("202607");
    move.setInvoice(invoice);
    collector.reportedElsewhere.add(move);

    BgVatExclusionsDto exclusions = collector.excludedMoves(request());

    assertEquals(List.of("SAL260123 → 07.2026"), exclusions.getReportedElsewhereMoves());
    assertTrue(exclusions.getOutsidePeriodMoves().isEmpty());
    assertTrue(
        BgVatGenerateReport.exclusionLines(exclusions).stream()
            .anyMatch(l -> l.contains("отразени в друг данъчен период") && l.contains("SAL260123")),
        BgVatGenerateReport.exclusionLines(exclusions).toString());
  }

  @Test
  @DisplayName("колекторът носи изричния период на фактурата към документа; празното — null")
  void периодът_към_документа() {
    Invoice invoice = zeroInCompanyCurrency();
    assertNull(document(invoice).getReportingPeriod());
    invoice.setBgVatReportingPeriod("202607");
    assertEquals("202607", document(invoice).getReportingPeriod());
  }

  @Test
  @DisplayName("без дружество или период — нищо не се търси")
  void без_параметри_нищо() {
    Collector collector = new Collector();
    collector.unposted.add(move(1, "PCH260001", MoveRepository.STATUS_DAYBOOK));
    BgVatExportRequestDto request = request();
    request.setCompanyId(null);

    assertTrue(collector.excludedMoves(request).isEmpty());
  }

  @Test
  @DisplayName("покупка с нулеви суми във валутата на дружеството: флаг, POKUPKI празен")
  void нулеви_суми_флаг() {
    BgVatDocumentDto doc = document(zeroInCompanyCurrency());

    assertTrue(doc.isZeroAmountPurchase());
    assertFalse(doc.isReverseChargeInvoice());
    assertTrue(doc.getPurchaseAmounts().isEmpty(), doc.getPurchaseAmounts().toString());
  }

  @Test
  @DisplayName("обикновена покупка: без флагове, к.10/к.11 са попълнени")
  void обикновена_покупка_без_флаг() {
    Invoice invoice =
        invoice(
            3,
            supplier(5, "BG100000033", "BG"),
            JUNE_10,
            JUNE_10,
            JUNE_10,
            plain("BG_SRP_20", "20", "150", "1"));
    BgVatDocumentDto doc = document(invoice);

    assertFalse(doc.isZeroAmountPurchase());
    assertFalse(doc.isReverseChargeInvoice());
    assertFalse(doc.getPurchaseAmounts().isEmpty());
  }

  @Test
  @DisplayName("един нулев ред до ненулев не прави фактурата „нулева“")
  void един_нулев_ред_не_стига() {
    InvoiceLine zero = plain("BG_SRP_20", "20", "0", "1");
    Invoice invoice =
        invoice(
            4,
            supplier(5, "BG100000033", "BG"),
            JUNE_10,
            JUNE_10,
            JUNE_10,
            zero,
            plain("BG_SRP_20", "20", "150", "1"));

    assertFalse(document(invoice).isZeroAmountPurchase());
  }

  @Test
  @DisplayName("ВОП: флаг за обратно начисляване, без „нулеви суми“")
  void вопът_е_обратно_начисляване() {
    Invoice invoice =
        invoice(
            6,
            supplier(7, "DE123456789", "DE"),
            JUNE_10,
            JUNE_10,
            JUNE_10,
            reverseCharge("BG_VOP_ACQ", "BG_VOP_SELF", "20", "1000", "1", false));
    BgVatDocumentDto doc = document(invoice);

    assertTrue(doc.isReverseChargeInvoice());
    assertFalse(doc.isZeroAmountPurchase());
  }

  // ---------------------------------------------------------------------------
  // Генерирането: изпадналите документи и предупрежденията на български
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("Нулевите суми изпадат с причина, декларацията е нулева, диалогът казва защо")
  void нулеви_суми() {
    Collector collector = new Collector();
    Invoice invoice = zeroInCompanyCurrency();
    collector.posted.add(invoiceMove(invoice));

    BgVatExportResultDto result = new Export(collector).generate(request());

    assertEquals(List.of("0000009001"), result.getExclusions().getZeroAmountDocuments());
    assertTrue(result.getPurchaseRows().isEmpty());
    assertTrue(
        result.getWarnings().stream()
            .anyMatch(w -> w.contains("нулеви основа и ДДС") && w.contains(": 1 (0000009001)")),
        result.getWarnings().toString());

    String dialog = BgVatGenerateReport.dialog(result);
    assertNotNull(dialog);
    assertTrue(dialog.contains("Дневниците за периода са празни"), dialog);
    assertTrue(dialog.contains("0000009001"), dialog);
  }

  @Test
  @DisplayName("празен период: българско „няма документи“ + причините; без английския текст")
  void празен_период_на_български() {
    Collector collector = new Collector();
    collector.unposted.add(move(1, "PCH260001", MoveRepository.STATUS_DAYBOOK));
    collector.outsidePeriod.add(move(2, "PCH260003", MoveRepository.STATUS_ACCOUNTED));

    BgVatExportResultDto result = new Export(collector).generate(request());

    assertTrue(result.getWarnings().contains(BgVatExportService.NO_DOCUMENTS_WARNING));
    assertTrue(
        result.getWarnings().stream()
            .anyMatch(w -> w.contains("(DAYBOOK)") && w.contains("PCH260001")),
        result.getWarnings().toString());
    assertTrue(
        result.getWarnings().stream()
            .anyMatch(w -> w.contains("извън него") && w.contains("PCH260003")),
        result.getWarnings().toString());
    for (String warning : result.getWarnings()) {
      assertFalse(warning.contains("No ACCOUNTED"), warning);
      assertFalse(warning.contains("Dry-run"), warning);
    }
    // DEKLAR/PRODAGBI/POKUPKI и празни (ППЗДДС чл. 114 ал. 3); VIES — не (чл. 117 ал. 5).
    assertEquals(3, result.getGeneratedFiles().size(), "пакетът е пълен и при нулева декларация");
  }

  @Test
  @DisplayName("ВОП без протокол е „обратно начисляване“; с осчетоводен протокол — не е изпаднал")
  void обратното_начисляване_само_с_грешка() {
    BgVatExportService service = new Export(new Collector());
    BgVatDocumentDto withError = new BgVatDocumentDto();
    withError.setDocumentNumber("INV-6");
    withError.setReverseChargeInvoice(true);
    withError.addError(BgVatValidator.INVOICE_PROTOCOL_MISSING_CODE, "без протокол");
    BgVatDocumentDto covered = new BgVatDocumentDto();
    covered.setDocumentNumber("INV-7");
    covered.setReverseChargeInvoice(true);
    BgVatDocumentDto other = new BgVatDocumentDto();

    BgVatExclusionsDto exclusions = new BgVatExclusionsDto();
    service.classifyDropped(
        Arrays.asList(withError, covered, other),
        Collections.emptyList(),
        Collections.emptyList(),
        exclusions);

    assertEquals(List.of("INV-6"), exclusions.getReverseChargeDocuments());
    assertEquals(List.of("без номер"), exclusions.getOtherDroppedDocuments());
    assertTrue(exclusions.getZeroAmountDocuments().isEmpty());
  }

  @Test
  @DisplayName("документ в някой дневник не е изпаднал — по идентичност, не по номер")
  void в_дневник_не_е_изпаднал() {
    BgVatExportService service = new Export(new Collector());
    BgVatDocumentDto inPurchases = new BgVatDocumentDto();
    inPurchases.setDocumentNumber("X");
    inPurchases.setZeroAmountPurchase(true);
    BgVatDocumentDto sameNumber = new BgVatDocumentDto();
    sameNumber.setDocumentNumber("X");
    sameNumber.setZeroAmountPurchase(true);

    BgVatExclusionsDto exclusions = new BgVatExclusionsDto();
    service.classifyDropped(
        Arrays.asList(inPurchases, sameNumber),
        Collections.emptyList(),
        Collections.singletonList(inPurchases),
        exclusions);

    assertEquals(List.of("X"), exclusions.getZeroAmountDocuments());
  }

  // ---------------------------------------------------------------------------
  // Текстовете и диалогът
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("ред на причина: брой, първите пет номера и „и още N“; празните причини липсват")
  void редовете_на_причините() {
    BgVatExclusionsDto exclusions = new BgVatExclusionsDto();
    for (int i = 1; i <= 7; i++) {
      exclusions.getDaybookMoves().add("PCH26000" + i);
    }

    List<String> lines = BgVatGenerateReport.exclusionLines(exclusions);

    assertEquals(1, lines.size(), lines.toString());
    assertTrue(
        lines
            .get(0)
            .endsWith(": 7 (PCH260001, PCH260002, PCH260003, PCH260004, PCH260005 и още 2)"),
        lines.get(0));
    assertTrue(BgVatGenerateReport.exclusionLines(new BgVatExclusionsDto()).isEmpty());
  }

  @Test
  @DisplayName("всяка от седемте причини има свой ред")
  void седемте_причини() {
    BgVatExclusionsDto e = new BgVatExclusionsDto();
    e.getDaybookMoves().add("a");
    e.getDraftMoves().add("b");
    e.getOutsidePeriodMoves().add("c");
    e.getOtherCompanyMoves().add("d");
    e.getZeroAmountDocuments().add("e");
    e.getReverseChargeDocuments().add("f");
    e.getOtherDroppedDocuments().add("g");

    List<String> lines = BgVatGenerateReport.exclusionLines(e);

    assertEquals(7, lines.size(), lines.toString());
    String[] keys = {
      "(DAYBOOK)", "чернови", "извън него", "друго дружество", "нулеви", "обратно", "нито един"
    };
    for (int i = 0; i < keys.length; i++) {
      assertTrue(lines.get(i).contains(keys[i]), lines.get(i));
    }
  }

  @Test
  @DisplayName("пълни дневници и само обичайните бележки — без диалог (известие)")
  void обичайните_бележки_без_диалог() {
    BgVatExportResultDto result = new BgVatExportResultDto();
    result.getPurchaseRows().add(new BgVatPurchaseRowDto());
    result.getWarnings().add(BgVatExportService.SALES_FORMAT_DISCLAIMER);
    result.getWarnings().add(BgVatExportService.VIES_NOT_FILED);
    result.setMessage("4 NAP файл(а) генериран(и).");

    assertNull(BgVatGenerateReport.dialog(result));
  }

  @Test
  @DisplayName("пълни дневници + грешка: диалог с броя редове и ❌; текстът е екраниран")
  void грешка_в_диалога() {
    BgVatExportResultDto result = new BgVatExportResultDto();
    result.getPurchaseRows().add(new BgVatPurchaseRowDto());
    result
        .getValidationErrors()
        .add(
            new BgVatValidationErrorDto(
                "X", BgVatValidationErrorDto.SEVERITY_ERROR, "<script>липсва вид</script>"));

    String dialog = BgVatGenerateReport.dialog(result);

    assertNotNull(dialog);
    assertTrue(dialog.contains("за покупки: 1"), dialog);
    assertTrue(dialog.contains("❌ &lt;script&gt;липсва вид&lt;/script&gt;"), dialog);
    assertFalse(dialog.contains("<script>"), dialog);
  }

  @Test
  @DisplayName("над 15 реда — съкратено, с указание къде е целият списък")
  void дълъг_диалог_се_съкращава() {
    BgVatExportResultDto result = new BgVatExportResultDto();
    for (int i = 0; i < 20; i++) {
      result.getWarnings().add("предупреждение " + i);
    }

    String dialog = BgVatGenerateReport.dialog(result);

    assertTrue(dialog.contains("предупреждение 14"), dialog);
    assertFalse(dialog.contains("предупреждение 15"), dialog);
    assertTrue(dialog.contains("… и още 5"), dialog);
  }

  // ---------------------------------------------------------------------------
  // Преводите
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName(
      "„Concurrent updates error“ и текстът му са на български в главния messages_bg.csv, не в"
          + " модула")
  void конкурентните_промени_на_български() throws IOException {
    // Главният модул се зарежда последен и печели (BgConflictRatchetTest) — преводът е там.
    Path root = Paths.get("").toAbsolutePath();
    while (!Files.isRegularFile(root.resolve("settings.gradle"))) {
      root = root.getParent();
    }
    List<String> rows =
        Files.readAllLines(
            root.resolve("src/main/resources/i18n/messages_bg.csv"), StandardCharsets.UTF_8);
    List<String> moduleRows =
        Files.readAllLines(
            Paths.get("src/main/resources/i18n/messages_bg.csv"), StandardCharsets.UTF_8);
    for (String key :
        new String[] {
          "\"Concurrent updates error\",\"Записът е променен",
          "\"Record was updated or deleted by another transaction.\",\"Записът е променен или изтрит"
        }) {
      assertTrue(rows.stream().anyMatch(r -> r.startsWith(key)), key);
      String bare = key.substring(0, key.indexOf("\",\"") + 2);
      assertFalse(
          moduleRows.stream().anyMatch(r -> r.startsWith(bare)), "дубликат в модула: " + key);
    }
  }
}

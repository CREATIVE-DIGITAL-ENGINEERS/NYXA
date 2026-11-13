package com.axelor.apps.bglocalization.service.vat;

import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.invoice;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.invoiceMove;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.plain;
import static com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures.supplier;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatDeclarationAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatViesAdapter;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportResultDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatGeneratedFileDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatPreparedFile;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatPreparedPackage;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatPurchaseRowDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatSalesRowDto;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ДЕКЛАРАЦИЯТА СЕ ЗАПИСВА И СЪС СУМИ С ТРИ ЗНАКА: сумите на AOS са със scale 3, а колоните на
 * снимката BgVatPurchaseLine/BgVatSalesLine — {@code @Digits(fraction = 2)}. Всяка трета цифра,
 * различна от нула, без закръгляне дава ConstraintViolationException при запис.
 *
 * <p>Правилото: по документ и по колона — СБОР с пълната точност, после до стотинка HALF_UP; к.09 и
 * к.10 на PRODAGBI — сборът на закръглените колони; DEKLAR и VIES — сбор на закръглените редове.
 *
 * <p>Защо другите тестове не го хващат: всичките им суми са цели или с два знака („1000“, „150“,
 * „0.50“), никой не подава трета ненулева цифра, а снимката (единственото място с ограничението) не
 * се пуска в unit тест — тя записва в базата. TXT файловете пък закръглят всяко поле при
 * форматирането, тоест златните файлове излизат верни и с незакръглени редове.
 */
class BgVatAmountRoundingTest {

  static final LocalDate JUNE_10 = LocalDate.of(2026, 6, 10);
  static final Charset CP1251 = Charset.forName("windows-1251");

  /** Събирач без база: статиите са подадени, изключени няма. */
  private static final class Collector extends BgVatDocumentCollector {
    final List<Move> posted = new ArrayList<>();

    Collector(Move... moves) {
      super(null);
      posted.addAll(Arrays.asList(moves));
    }

    @Override
    protected List<Move> findPostedMoves(BgVatExportRequestDto request) {
      return posted;
    }

    @Override
    protected List<Move> findUnpostedMoves(BgVatExportRequestDto request) {
      return Collections.emptyList();
    }

    @Override
    protected List<Move> findMovesOutsidePeriod(BgVatExportRequestDto request) {
      return Collections.emptyList();
    }

    @Override
    protected List<Move> findMovesInOtherCompany(BgVatExportRequestDto request) {
      return Collections.emptyList();
    }

    @Override
    protected List<Move> findMovesReportedElsewhere(BgVatExportRequestDto request) {
      return Collections.emptyList();
    }
  }

  /** Целият {@code generate()}; файловете остават в паметта, а {@code failOn} не се качва. */
  private static final class Export extends BgVatExportService {
    final String failOn;

    Export(Collector collector, String failOn) {
      this(collector, new BgVatSalesAdapter(), failOn);
    }

    private Export(Collector collector, BgVatSalesAdapter sales, String failOn) {
      super(
          collector,
          new BgVatValidator(),
          new BgVatPurchaseAdapter(),
          sales,
          new BgVatDeclarationAdapter(sales),
          new BgVatViesAdapter(sales),
          new BgVatFileWriter());
      this.failOn = failOn;
    }

    @Override
    protected BgVatGeneratedFileDto uploadFile(
        BgVatExportResultDto result, String scope, String fileName, byte[] bytes)
        throws IOException {
      if (fileName.equals(failOn)) {
        throw new IOException("диск");
      }
      BgVatGeneratedFileDto file = new BgVatGeneratedFileDto(scope, fileName, null, null, bytes);
      result.getGeneratedFiles().add(file);
      return file;
    }
  }

  /** Само за да се стигне до {@code protected sumPurchases}. */
  private static final class Deklar extends BgVatDeclarationAdapter {
    Deklar() {
      super(new BgVatSalesAdapter());
    }

    Map<BgVatPurchaseColumn, BigDecimal> purchases(List<BgVatDocumentDto> docs) {
      return sumPurchases(docs);
    }
  }

  private static BgVatExportRequestDto request() {
    BgVatExportRequestDto request = new BgVatExportRequestDto();
    request.setCompanyId(1L);
    request.setCompanyName("Дружество");
    request.setCompanyVatNumber("BG100000033");
    request.setCompanyAddress("гр. София, ул. Седалище 1");
    request.setPeriodFrom(LocalDate.of(2026, 6, 1));
    request.setPeriodTo(LocalDate.of(2026, 6, 30));
    request.setReportType("all");
    request.setEncoding("cp1251");
    request.setEmitEmptyFiles(true);
    request.setDeclarantEgn("8505151039");
    request.setDeclarantNames("Иван Георгиев Петров");
    request.setDeclarantCapacity("R");
    request.setDeclarantCity("София");
    request.setDeclarantPostCode("1000");
    request.setDeclarantAddress("ул. Раковски 1");
    return request;
  }

  private static BgVatExportResultDto generate(Move... moves) {
    return new Export(new Collector(moves), null).generate(request());
  }

  /**
   * Пет реда по 20 %: 80.000/16.000, 70.000/14.000 и три пъти 83.335/16.667. Сборът е 400.005 /
   * 80.001 → 400.01 / 80.00; закръглени ред по ред щяха да дадат 400.02 / 80.01.
   */
  private static Invoice threeDecimals(long id, String tax, int operationType) {
    InvoiceLine[] lines = {
      plain(tax, "20", "80.000", "1"),
      plain(tax, "20", "70.000", "1"),
      plain(tax, "20", "83.335", "1"),
      plain(tax, "20", "83.335", "1"),
      plain(tax, "20", "83.335", "1")
    };
    Invoice invoice =
        invoice(id, supplier(5, "BG100000033", "BG"), JUNE_10, JUNE_10, JUNE_10, lines);
    invoice.setOperationTypeSelect(operationType);
    // Както AOS: тоталите на фактурата са сборът на редовете, със scale 3.
    invoice.setCompanyExTaxTotal(new BigDecimal("400.005"));
    invoice.setCompanyTaxTotal(new BigDecimal("80.001"));
    invoice.setCompanyInTaxTotal(new BigDecimal("480.006"));
    return invoice;
  }

  private static void eq(String expected, BigDecimal actual) {
    assertEquals(new BigDecimal(expected), actual, "стойност и scale");
  }

  private static String file(BgVatExportResultDto result, String name) {
    return result.getGeneratedFiles().stream()
        .filter(f -> f.getFileName().equals(name))
        .map(f -> new String(f.getContent(), CP1251))
        .findFirst()
        .orElse(null);
  }

  private static List<String> fileNames(BgVatExportResultDto result) {
    return result.getGeneratedFiles().stream()
        .map(BgVatGeneratedFileDto::getFileName)
        .collect(Collectors.toList());
  }

  // ---------------------------------------------------------------------------
  // Покупки: POKUPKI, DEKLAR, снимката
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("покупка със scale 3: к.10/к.11 = 400.01/80.00 — сбор, после закръгляне")
  void покупка_сбор_после_закръгляне() {
    Invoice invoice =
        threeDecimals(31, "BG_SRP_20", InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    BgVatExportResultDto result = generate(invoiceMove(invoice));

    assertTrue(result.isSuccess(), result.getValidationErrors().toString());
    BgVatPurchaseRowDto row = result.getPurchaseRows().get(0);
    eq("400.01", row.getAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("80.00", row.getAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    // Ред по ред щеше да е 400.02 / 80.01 — тестът различава двата начина.
    assertNotEquals(
        0,
        new BigDecimal("400.02")
            .compareTo(row.getAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT)));
    assertNotEquals(
        0,
        new BigDecimal("80.01").compareTo(row.getAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT)));
    // Сборът по документ = тоталите на фактурата, закръглени.
    eq("400.01", invoice.getCompanyExTaxTotal().setScale(2, java.math.RoundingMode.HALF_UP));
    eq("80.00", invoice.getCompanyTaxTotal().setScale(2, java.math.RoundingMode.HALF_UP));
    // Снимката вече се записва; тоталите на пакета — също с 2 знака.
    assertTrue(
        BgVatDiarySnapshotService.amountProblems(result.getSalesRows(), result.getPurchaseRows())
            .isEmpty());
    eq("400.01", result.getTotalBase());
    eq("80.00", result.getTotalVat());
  }

  @Test
  @DisplayName("DEKLAR сумира закръглените колони на POKUPKI — к.30/к.31 = 400.01/80.00")
  void декларацията_от_закръглените_покупки() {
    Invoice invoice =
        threeDecimals(32, "BG_SRP_20", InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    BgVatExportService service = new Export(new Collector(invoiceMove(invoice)), null);
    List<BgVatDocumentDto> docs = service.collector.collect(request());

    Map<BgVatPurchaseColumn, BigDecimal> sums = new Deklar().purchases(docs);
    eq("400.01", sums.get(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("80.00", sums.get(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    // Пълната точност остава за маршрутизацията.
    assertEquals(
        0,
        new BigDecimal("400.005")
            .compareTo(docs.get(0).getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT)));
  }

  // ---------------------------------------------------------------------------
  // Продажби: PRODAGBI, к.09/к.10, VIES
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("продажба със scale 3: к.11/к.12 = 400.01/80.00, к.09/к.10 — сборът на закръглените")
  void продажба_сбор_после_закръгляне() {
    Invoice invoice = threeDecimals(33, "BG_SRS_20", InvoiceRepository.OPERATION_TYPE_CLIENT_SALE);
    BgVatExportResultDto result = generate(invoiceMove(invoice));

    BgVatSalesRowDto row = result.getSalesRows().get(0);
    eq("400.01", row.getAmount(BgVatSalesColumn.K11_BASE_20));
    eq("80.00", row.getAmount(BgVatSalesColumn.K12_VAT_20));
    eq("400.01", row.getAmount(BgVatSalesColumn.K09_TOTAL_BASE));
    eq("80.00", row.getAmount(BgVatSalesColumn.K10_TOTAL_VAT));
    assertTrue(
        BgVatDiarySnapshotService.amountProblems(result.getSalesRows(), result.getPurchaseRows())
            .isEmpty());
    assertTrue(file(result, "prodagbi.txt").contains("400.01"), file(result, "prodagbi.txt"));
  }

  @Test
  @DisplayName("VIES = сбор на закръглените редове: два ВОД по 100.005 → 200.02, не 200.01")
  void vies_от_закръглените_редове() {
    Invoice first = vod(41);
    Invoice second = vod(42);
    BgVatExportResultDto result = generate(invoiceMove(first), invoiceMove(second));

    for (BgVatSalesRowDto row : result.getSalesRows()) {
      eq("100.01", row.getAmount(BgVatSalesColumn.K20_BASE_ICS));
    }
    String vies = file(result, "vies.txt");
    String vir =
        Arrays.stream(vies.split("\r\n")).filter(l -> l.startsWith("VIR")).findFirst().orElse("");
    assertTrue(vir.contains("200.02"), vir);
    assertFalse(vir.contains("200.01"), vir);
  }

  private static Invoice vod(long id) {
    Invoice invoice =
        invoice(
            id,
            supplier(7, "DE123456789", "DE"),
            JUNE_10,
            JUNE_10,
            JUNE_10,
            plain("BG_VOD", "0", "100.005", null));
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_CLIENT_SALE);
    return invoice;
  }

  // ---------------------------------------------------------------------------
  // Снимката: проверката на сумите казва документ, колона и защо
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("незакръглена сума в реда: документ, колона, стойност и причина на български")
  void снимката_казва_кое_не_се_побира() {
    BgVatPurchaseRowDto row = new BgVatPurchaseRowDto();
    row.setDocumentNumber("0000009001");
    row.addAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT, new BigDecimal("150.000"));
    row.addAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT, new BigDecimal("16.667"));

    List<String> problems =
        BgVatDiarySnapshotService.amountProblems(null, Collections.singletonList(row));

    // 150.000 се побира (нулите отзад не се броят — както @Digits), 16.667 — не.
    assertEquals(1, problems.size(), problems.toString());
    String problem = problems.get(0);
    assertTrue(problem.contains("дневник за покупки"), problem);
    assertTrue(problem.contains("документ № 0000009001"), problem);
    assertTrue(problem.contains("кл.11 = 16.667"), problem);
    assertTrue(problem.contains("повече от 2 знака след десетичната запетая"), problem);
  }

  @Test
  @DisplayName("17 цифри преди запетаята не се побират в precision 18 / scale 2")
  void твърде_голяма_сума() {
    BgVatSalesRowDto row = new BgVatSalesRowDto();
    row.addAmount(BgVatSalesColumn.K11_BASE_20, new BigDecimal("12345678901234567.00"));
    row.addAmount(BgVatSalesColumn.K12_VAT_20, new BigDecimal("1234567890123456.99"));

    List<String> problems =
        BgVatDiarySnapshotService.amountProblems(Collections.singletonList(row), null);

    assertEquals(1, problems.size(), problems.toString());
    assertTrue(problems.get(0).contains("документ № без номер, кл.11"), problems.get(0));
    assertTrue(problems.get(0).contains("повече от 16 цифри"), problems.get(0));
  }

  // ---------------------------------------------------------------------------
  // VIES: файл само при данни (ППЗДДС чл. 117 ал. 5, виж generateViesFile)
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("без редове за VIES: VIES.TXT няма, останалите три са налице, съобщението го казва")
  void без_vies_няма_файл() {
    Invoice invoice =
        threeDecimals(51, "BG_SRP_20", InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    BgVatExportResultDto result = generate(invoiceMove(invoice));

    assertEquals(List.of("prodagbi.txt", "pokupki.txt", "deklar.txt"), fileNames(result));
    assertTrue(result.getWarnings().contains(BgVatExportService.VIES_NOT_FILED));
    assertTrue(
        result.getMessage().endsWith("Няма данни за VIES декларация за периода — не се подава."),
        result.getMessage());
    assertTrue(BgVatGenerateReport.missingDiaryFiles(result).isEmpty());
  }

  @Test
  @DisplayName(
      "нулев период: пак без VIES.TXT; DEKLAR/PRODAGBI/POKUPKI — и празни (ППЗДДС чл. 114 ал. 3)")
  void нулев_период_без_vies() {
    BgVatExportResultDto result = generate();

    assertEquals(List.of("prodagbi.txt", "pokupki.txt", "deklar.txt"), fileNames(result));
    assertTrue(result.getWarnings().contains(BgVatExportService.VIES_NOT_FILED));
  }

  @Test
  @DisplayName(
      "празен период: DEKLAR.TXT е ЕДИН запис с нули; POKUPKI/PRODAGBI — 0 записа, 0 байта")
  void празен_период_deklar_е_запис_с_нули() {
    BgVatExportResultDto result = generate();

    // Приложение № 12, „Общи изисквания“: стойностите в DEKLAR.TXT са сборът от POKUPKI и PRODAGBI,
    // а дневник без документи „няма да съдържа нито един запис“ — сборът е нула, не липсващ запис.
    String deklar = file(result, "deklar.txt");
    List<String> records =
        Arrays.stream(deklar.split("\r\n")).filter(l -> !l.isEmpty()).collect(Collectors.toList());
    assertEquals(1, records.size(), deklar);
    String record = records.get(0);
    assertTrue(record.contains("202606"), record);
    Matcher amount = Pattern.compile("-?\\d+\\.\\d{2}").matcher(record);
    int cells = 0;
    while (amount.find()) {
      assertEquals("0.00", amount.group(), record);
      cells++;
    }
    assertTrue(cells >= 25, "клетките на DEKLAR: " + cells + " в " + record);
    assertEquals("", file(result, "prodagbi.txt"));
    assertEquals("", file(result, "pokupki.txt"));

    // Така го вижда и пакетът: DEKLAR — 1 запис, OK; дневниците — 0 записа, празни.
    BgVatPreparedPackage pkg = BgVatPackageBuilder.build(result);
    for (BgVatPreparedFile f : pkg.getFiles()) {
      if ("declaration".equals(f.getScope())) {
        assertEquals(1, f.getRecordCount());
        assertEquals(BgVatPreparedFile.STATUS_OK, f.getStatus());
      } else {
        assertEquals(0, f.getRecordCount(), f.getScope());
        assertEquals(0, f.getByteSize(), f.getScope());
      }
    }
  }

  @Test
  @DisplayName("с ВОД: VIES.TXT е в пакета и няма „не се подава“")
  void с_вод_има_vies() {
    BgVatExportResultDto result = generate(invoiceMove(vod(52)));

    assertTrue(fileNames(result).contains("vies.txt"), fileNames(result).toString());
    assertFalse(result.getWarnings().contains(BgVatExportService.VIES_NOT_FILED));
    assertFalse(result.getMessage().contains("не се подава"), result.getMessage());
  }

  // ---------------------------------------------------------------------------
  // Неуспешен файл: грешка, не „генериран“
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("POKUPKI.TXT не се качи: ГРЕШКА, success=false, пакетът е непълен")
  void неуспешен_файл_е_грешка() {
    BgVatExportResultDto result = new Export(new Collector(), "pokupki.txt").generate(request());

    assertFalse(result.isSuccess());
    assertTrue(
        result.getValidationErrors().stream()
            .anyMatch(
                e ->
                    BgVatExportService.FILE_GENERATION_FAILED_CODE.equals(e.getCode())
                        && e.getMessage().contains("pokupki.txt")
                        && e.getMessage().contains("диск")),
        result.getValidationErrors().toString());
    assertEquals(List.of("pokupki.txt"), BgVatGenerateReport.missingDiaryFiles(result));

    String dialog = BgVatGenerateReport.incompleteDialog(result, List.of("pokupki.txt"));
    assertTrue(dialog.contains("Пакетът за НАП е непълен — липсва pokupki.txt"), dialog);
    assertTrue(dialog.contains("не е отбелязана като генерирана"), dialog);
    assertTrue(dialog.contains("❌ Неуспешно генериране на празен pokupki.txt: диск"), dialog);
  }
}

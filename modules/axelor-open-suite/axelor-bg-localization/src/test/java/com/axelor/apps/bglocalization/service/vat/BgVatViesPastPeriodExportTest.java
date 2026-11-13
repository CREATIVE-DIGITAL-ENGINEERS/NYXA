package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatDeclarationAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatViesAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatViesPastPeriodTest;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExclusionsDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportResultDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatGeneratedFileDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatValidationErrorDto;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * VIES ЗА ПЕРИОДА — целият {@code generate()} (колекторът подава готовите документи, качването е в
 * паметта).
 *
 * <p>ППЗДДС чл. 117 ал. 5: VIES „не се подава“, когато лицето едновременно не е извършило ВОД,
 * услуги по чл. 21 ал. 2, тристранни операции (т. 1–3) и „не е отразило пропуснати данни за
 * предходен данъчен период в съответствие с чл. 126, ал. 3, т. 1 от закона“ (т. 4). Т. 5–8
 * (складиране до поискване) Nyxa не съставя — ПРЕДУПРЕЖДЕНИЕ, че се попълва ръчно. Консолидиран
 * текст, последно изм. ДВ бр. 54/2024, сверено 27.10.2026; ЗДДС чл. 126 ал. 3 т. 1 — lex.bg,
 * последно изм. ДВ бр. 69/2026.
 */
class BgVatViesPastPeriodExportTest {

  private static final class Collector extends BgVatDocumentCollector {
    private final List<BgVatDocumentDto> documents;

    Collector(List<BgVatDocumentDto> documents) {
      super(null);
      this.documents = documents;
    }

    @Override
    public List<BgVatDocumentDto> collect(BgVatExportRequestDto request) {
      return documents;
    }

    @Override
    public BgVatExclusionsDto excludedMoves(BgVatExportRequestDto request) {
      return new BgVatExclusionsDto();
    }
  }

  private static final class Export extends BgVatExportService {
    Export(List<BgVatDocumentDto> documents, BgVatSalesAdapter sales) {
      super(
          new Collector(documents),
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

  private static BgVatExportResultDto generate(BgVatDocumentDto... documents) {
    return new Export(new ArrayList<>(Arrays.asList(documents)), new BgVatSalesAdapter())
        .generate(request());
  }

  /** Редовете на VIES.TXT; {@code null}, когато файл няма. */
  private static List<String> vies(BgVatExportResultDto result) {
    for (BgVatGeneratedFileDto file : result.getGeneratedFiles()) {
      if ("vies.txt".equals(file.getFileName())) {
        String text = new String(file.getContent(), Charset.forName("windows-1251"));
        return Arrays.asList(text.split("\r\n"));
      }
    }
    return null;
  }

  private static BgVatDocumentDto past() {
    return BgVatViesPastPeriodTest.doc(
        "DE123456789",
        BgVatSalesColumn.K20_BASE_ICS,
        "1000",
        LocalDate.of(2026, 6, 3),
        LocalDate.of(2026, 4, 20));
  }

  private static BgVatDocumentDto current() {
    return BgVatViesPastPeriodTest.doc(
        "FR12345678901",
        BgVatSalesColumn.K22_BASE_EU_SERVICES,
        "300",
        LocalDate.of(2026, 6, 10),
        null);
  }

  @Test
  @DisplayName("Празен период — VIES не се съставя, а формата казва „не се подава“")
  void празният_период() {
    BgVatExportResultDto result = generate();

    assertNull(vies(result));
    assertTrue(result.getWarnings().contains(BgVatExportService.VIES_NOT_FILED));
  }

  @Test
  @DisplayName("Само документ за минал период — VIES се съставя и редът носи „05/2026“ (т. 11)")
  void само_минал_период() {
    BgVatExportResultDto result = generate(past());
    List<String> lines = vies(result);

    assertFalse(result.getWarnings().contains(BgVatExportService.VIES_NOT_FILED));
    assertEquals(
        List.of("1|DE123456789|1000.00|0.00|0.00|05/2026"), BgVatViesPastPeriodTest.virs(lines));
    assertEquals("VHR06/2026    1", lines.get(0));
  }

  @Test
  @DisplayName("Смесен период — текущият ред с празен период, миналият с „05/2026“")
  void смесеният_период() {
    List<String> lines = vies(generate(current(), past()));

    assertEquals(
        List.of("1|FR12345678901|0.00|0.00|300.00|", "2|DE123456789|1000.00|0.00|0.00|05/2026"),
        BgVatViesPastPeriodTest.virs(lines));
  }

  @Test
  @DisplayName(
      "Складиране до поискване: VIES не го съставя — ПРЕДУПРЕЖДЕНИЕ на български, не грешка")
  void складирането_до_поискване() {
    BgVatDocumentDto callOff = current();
    callOff.setSupplyTypeArt163a("41");
    BgVatExportResultDto result = generate(callOff);

    List<BgVatValidationErrorDto> findings =
        result.getValidationErrors().stream()
            .filter(f -> BgVatValidator.VIES_CALL_OFF_STOCK_MANUAL_CODE.equals(f.getCode()))
            .collect(Collectors.toList());
    assertEquals(1, findings.size(), result.getValidationErrors().toString());
    assertEquals(BgVatValidationErrorDto.SEVERITY_WARNING, findings.get(0).getSeverity());
    assertTrue(
        findings.get(0).getMessage().startsWith("VIES за складиране до поискване се попълва ръчно"),
        findings.get(0).getMessage());
    // Останалото от VIES се съставя както обикновено.
    assertEquals(
        List.of("1|FR12345678901|0.00|0.00|300.00|"), BgVatViesPastPeriodTest.virs(vies(result)));
  }

  @Test
  @DisplayName("Колекторът чете датата на данъчното събитие и на ПРОДАЖНАТА фактура")
  void колекторът_чете_събитието() {
    Invoice invoice = new Invoice();
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_CLIENT_SALE);
    invoice.setInvoiceId("2026000123");
    invoice.setInvoiceDate(LocalDate.of(2026, 6, 3));
    invoice.setBgVatTaxEventDate(LocalDate.of(2026, 4, 20));
    BgVatDocumentDto document = new BgVatDocumentDto();

    new BgVatDocumentCollector(null).enrichFromInvoice(document, invoice);

    assertEquals(LocalDate.of(2026, 4, 20), document.getTaxEventDate());
    assertEquals(LocalDate.of(2026, 6, 3), document.getDocumentDate());
  }

  @Test
  @DisplayName("Колекторът отбелязва авансовата фактура (operationSubTypeSelect = аванс)")
  void колекторът_отбелязва_аванса() {
    Invoice invoice = new Invoice();
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_CLIENT_SALE);
    invoice.setInvoiceId("2026000124");
    invoice.setInvoiceDate(LocalDate.of(2026, 6, 3));
    BgVatDocumentCollector collector = new BgVatDocumentCollector(null);

    invoice.setOperationSubTypeSelect(InvoiceRepository.OPERATION_SUB_TYPE_ADVANCE);
    BgVatDocumentDto advance = new BgVatDocumentDto();
    collector.enrichFromInvoice(advance, invoice);
    assertTrue(advance.isAdvancePayment());

    invoice.setOperationSubTypeSelect(InvoiceRepository.OPERATION_SUB_TYPE_DEFAULT);
    BgVatDocumentDto plain = new BgVatDocumentDto();
    collector.enrichFromInvoice(plain, invoice);
    assertFalse(plain.isAdvancePayment());
  }
}

package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.ProductFamily;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSubject;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatAmountDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatPurchaseRowDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatValidationErrorDto;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PRODAGBI.TXT И POKUPKI.TXT, КОИТО portal.nra.bg ПРИЕМА — фиксирани очаквани редове (424 / 274
 * знака, CP1251, CRLF). За да минат „Формиране на декларация“: „вид на стоката/услугата“ попълнен
 * (т. 1), клон „0“, не „00“ (т. 2), името на контрагента без кода на партньора (т. 3); т. 6 — номер
 * на покупка, различен от 10 цифри, е само предупреждение.
 *
 * <p>Всички данни са измислени: ИН с нули (демо дружествата), имена „Демо …“.
 */
class BgVatNapPortalDiaryRowsTest {

  private static final Charset CP1251 = Charset.forName("windows-1251");
  private static final String ZERO = sp(11) + "0.00";

  private static String sp(int n) {
    return " ".repeat(n);
  }

  /** Ляво подравнено поле — само за четимост на очаквания ред. */
  private static String left(String value, int width) {
    assertTrue(value.length() <= width, value);
    return value + sp(width - value.length());
  }

  private static String zeros(int count) {
    return ZERO.repeat(count);
  }

  private static BgVatExportRequestDto request() {
    BgVatExportRequestDto request = new BgVatExportRequestDto();
    request.setCompanyVatNumber("BG000000107");
    request.setPeriodFrom(LocalDate.of(2026, 10, 1));
    request.setPeriodTo(LocalDate.of(2026, 10, 31));
    return request;
  }

  private static Partner company(String code, String name) {
    Partner p = new Partner();
    p.setPartnerTypeSelect(PartnerRepository.PARTNER_TYPE_COMPANY);
    p.setName(name);
    p.setFullName(code + " - " + name);
    return p;
  }

  private static Partner person(String code, String firstName, String lastName) {
    Partner p = new Partner();
    p.setPartnerTypeSelect(PartnerRepository.PARTNER_TYPE_INDIVIDUAL);
    p.setFirstName(firstName);
    p.setName(lastName);
    p.setFullName(code + " - " + lastName + " " + firstName);
    return p;
  }

  private static InvoiceLine line(String base, ProductFamily family, String productType) {
    InvoiceLine line = new InvoiceLine();
    line.setCompanyExTaxTotal(new BigDecimal(base));
    if (productType != null) {
      Product product = new Product();
      product.setProductTypeSelect(productType);
      product.setProductFamily(family);
      line.setProduct(product);
    }
    return line;
  }

  private static ProductFamily family(String name) {
    ProductFamily f = new ProductFamily();
    f.setName(name);
    return f;
  }

  private static Invoice invoice(InvoiceLine... lines) {
    Invoice invoice = new Invoice();
    invoice.setInvoiceLineList(Arrays.asList(lines));
    return invoice;
  }

  private static String subjectOf(Invoice invoice) {
    return BgVatSubject.fromLines(BgVatDocumentCollector.subjectLines(invoice));
  }

  private static BgVatAmountDto amount(String taxCode, String base, String vat) {
    BgVatAmountDto a = new BgVatAmountDto();
    a.setTaxCode(taxCode);
    a.setBase(new BigDecimal(base));
    a.setVatAmount(new BigDecimal(vat));
    return a;
  }

  private static byte[] crlfCp1251(String... lines) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (String line : lines) {
      byte[] bytes = (line + "\r\n").getBytes(CP1251);
      out.write(bytes, 0, bytes.length);
    }
    return out.toByteArray();
  }

  // ---------------------------------------------------------------------------
  // PRODAGBI.TXT — т. 1, 2, 3
  // ---------------------------------------------------------------------------

  private static BgVatDocumentDto saleToCompany() {
    BgVatDocumentDto d = new BgVatDocumentDto();
    d.setNapDocumentType("01");
    d.setDocumentNumber("0000000083");
    d.setDocumentDate(LocalDate.of(2026, 10, 19));
    d.setCounterpartyDiaryId("BG000000114");
    d.setCounterpartyName(BgVatPartyName.of(company("P0019", "Демо Клиент ООД")));
    // Най-голямата основа е хранителният ред → семейството му.
    d.setSubject(
        subjectOf(
            invoice(
                line("20.00", family("Опаковки"), "storable"),
                line("80.00", family("Хранителни стоки"), "storable"))));
    d.getAmounts().add(amount("BG_SRS_20", "100.00", "20.00"));
    return d;
  }

  private static BgVatDocumentDto saleToPerson() {
    BgVatDocumentDto d = new BgVatDocumentDto();
    d.setNapDocumentType("01");
    d.setDocumentNumber("0000000084");
    d.setDocumentDate(LocalDate.of(2026, 10, 20));
    d.setCounterpartyDiaryId("999999999999999");
    d.setCounterpartyName(BgVatPartyName.of(person("P0020", "Деян", "Демов")));
    // Ред без продукт — „вид“ няма откъде да дойде: резервната стойност, не празно.
    d.setSubject(subjectOf(invoice(line("50.00", null, null))));
    d.getAmounts().add(amount("BG_SRS_20", "50.00", "10.00"));
    return d;
  }

  @Test
  @DisplayName("PRODAGBI: клон „0“, името без кода, вид на стоката — фиксиран ред, CP1251 + CRLF")
  void salesRowsAreTheAcceptedLayout() throws Exception {
    List<String> lines =
        new BgVatSalesAdapter()
            .buildLines(Arrays.asList(saleToCompany(), saleToPerson()), request());

    String expected1 =
        "BG000000107    "
            + "202610"
            + "0   "
            + sp(14)
            + "1"
            + "01"
            + left("0000000083", 20)
            + "19/10/2026"
            + left("BG000000114", 15)
            + left("Демо Клиент ООД", 50)
            + left("Хранителни стоки", 30)
            + sp(9)
            + "100.00"
            + sp(10)
            + "20.00"
            + sp(9)
            + "100.00"
            + sp(10)
            + "20.00"
            + zeros(13)
            + "  ";
    String expected2 =
        "BG000000107    "
            + "202610"
            + "0   "
            + sp(14)
            + "2"
            + "01"
            + left("0000000084", 20)
            + "20/10/2026"
            + "999999999999999"
            + left("Деян Демов", 50)
            + left("стоки и услуги", 30)
            + sp(10)
            + "50.00"
            + sp(10)
            + "10.00"
            + sp(10)
            + "50.00"
            + sp(10)
            + "10.00"
            + zeros(13)
            + "  ";

    assertEquals(424, expected1.length());
    assertEquals(424, expected2.length());
    assertEquals(expected1, lines.get(0));
    assertEquals(expected2, lines.get(1));
    assertArrayEquals(
        crlfCp1251(expected1, expected2), new BgVatFileWriter().toBytes(lines, "cp1251"));
  }

  // ---------------------------------------------------------------------------
  // POKUPKI.TXT — т. 1, 2, 3, 6
  // ---------------------------------------------------------------------------

  private static BgVatDocumentDto purchase(String number) {
    BgVatDocumentDto d = new BgVatDocumentDto();
    d.setNapDocumentType("01");
    d.setDocumentNumber(number);
    d.setDocumentDate(LocalDate.of(2026, 10, 5));
    d.setCounterpartyDiaryId("BG000000121");
    d.setCounterpartyName(BgVatPartyName.of(company("P0031", "Демо Доставчик ЕООД")));
    // Продукт без семейство, услуга → „услуги“.
    d.setSubject(subjectOf(invoice(line("50.00", null, "service"))));
    d.addPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT, new BigDecimal("50.00"));
    d.addPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT, new BigDecimal("10.00"));
    return d;
  }

  /** Както {@code BgVatExportService.generatePurchaseFile}: редовете + сумите по колони. */
  private static List<String> purchaseLines(List<BgVatDocumentDto> documents) {
    BgVatPurchaseAdapter adapter = new BgVatPurchaseAdapter();
    List<BgVatPurchaseRowDto> rows = adapter.buildRows(documents, request());
    for (int i = 0; i < documents.size(); i++) {
      for (Map.Entry<BgVatPurchaseColumn, BigDecimal> e :
          documents.get(i).getPurchaseDiaryAmounts().entrySet()) {
        rows.get(i).addAmount(e.getKey(), e.getValue());
      }
      rows.get(i).setSupplyTypeArt163a(documents.get(i).getSupplyTypeArt163a());
    }
    return adapter.buildLines(rows);
  }

  private static String expectedPurchase(int sequence, String number) {
    return "BG000000107    "
        + "202610"
        + "0   "
        + sp(14)
        + sequence
        + "01"
        + left(number, 20)
        + "05/10/2026"
        + left("BG000000121", 15)
        + left("Демо Доставчик ЕООД", 50)
        + left("услуги", 30)
        + ZERO
        + sp(10)
        + "50.00"
        + sp(10)
        + "10.00"
        + zeros(4)
        + "  ";
  }

  @Test
  @DisplayName("POKUPKI: клон „0“, името без кода, вид на услугата — фиксиран ред, CP1251 + CRLF")
  void purchaseRowIsTheAcceptedLayout() throws Exception {
    List<String> lines = purchaseLines(Collections.singletonList(purchase("0000000095")));

    String expected = expectedPurchase(1, "0000000095");
    assertEquals(274, expected.length());
    assertEquals(expected, lines.get(0));
    assertArrayEquals(crlfCp1251(expected), new BgVatFileWriter().toBytes(lines, "cp1251"));
  }

  @Test
  @DisplayName("POKUPKI: номер, различен от 10 цифри — дословно в реда и само предупреждение")
  void purchaseNumberNotTenDigitsWarnsOnly() throws Exception {
    BgVatDocumentDto bad = purchase("NM-0095");
    BgVatDocumentDto good = purchase("0000000095");
    List<String> lines = purchaseLines(Arrays.asList(bad, good));

    // Редът е непроменен: номерът е на доставчика.
    String expected = expectedPurchase(1, "NM-0095");
    assertEquals(274, expected.length());
    assertEquals(expected, lines.get(0));

    List<BgVatValidationErrorDto> findings =
        new BgVatValidator().validatePurchaseDocumentNumbers(Arrays.asList(bad, good));
    assertEquals(1, findings.size(), findings.toString());
    BgVatValidationErrorDto finding = findings.get(0);
    assertEquals(BgVatValidator.PURCHASE_DOCUMENT_NUMBER_NOT_10_DIGITS_CODE, finding.getCode());
    assertEquals(BgVatValidationErrorDto.SEVERITY_WARNING, finding.getSeverity());
    assertTrue(finding.getMessage().contains("NM-0095"), finding.getMessage());
  }

  @Test
  @DisplayName("Предупреждението е само за вид 01/02/03 от БГ доставчик")
  void tenDigitWarningScope() {
    BgVatDocumentDto foreign = purchase("INV-77");
    foreign.setCounterpartyDiaryId("999999999999999");
    BgVatDocumentDto euSupplier = purchase("INV-78");
    euSupplier.setCounterpartyDiaryId("DE000000000");
    BgVatDocumentDto protocol = purchase("P-12");
    protocol.setNapDocumentType("09");
    BgVatDocumentDto byEik = purchase("12345");
    byEik.setCounterpartyDiaryId("000000121");
    BgVatDocumentDto creditNote = purchase("КИ-5");
    creditNote.setNapDocumentType("03");

    List<BgVatValidationErrorDto> findings =
        new BgVatValidator()
            .validatePurchaseDocumentNumbers(
                Arrays.asList(foreign, euSupplier, protocol, byEik, creditNote));
    assertEquals(2, findings.size(), findings.toString());
    assertTrue(findings.get(0).getMessage().contains("12345"));
    assertTrue(findings.get(1).getMessage().contains("КИ-5"));
  }

  // ---------------------------------------------------------------------------
  // Правилата поотделно
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("Името: юридическо — name; физическо — име и фамилия; никога fullName с кода")
  void partyName() {
    assertEquals("Демо Клиент ООД", BgVatPartyName.of(company("P0019", "Демо Клиент ООД")));
    assertEquals("Деян Демов", BgVatPartyName.of(person("P0020", "Деян", "Демов")));
    assertEquals("Демов", BgVatPartyName.of(person("P0021", null, "Демов")));
  }

  @Test
  @DisplayName("Видът: семейството на най-голямата основа, иначе стоки/услуги, до 30 знака")
  void subjectRules() {
    assertEquals(
        "Услуги",
        subjectOf(
            invoice(
                line("-300.00", family("Услуги"), "service"),
                line("200.00", family("Стоки"), "storable"))));
    assertEquals("стоки", subjectOf(invoice(line("10.00", null, "storable"))));
    assertEquals("услуги", subjectOf(invoice(line("10.00", null, "service"))));
    assertEquals(
        "Строителни и ремонтни материал",
        subjectOf(invoice(line("10.00", family("Строителни и ремонтни материали"), "storable"))));
    assertEquals(BgVatSubject.FALLBACK, BgVatSubject.orFallback(null));
    assertEquals(BgVatSubject.GOODS, BgVatSubject.forOperation("VOP"));
    assertEquals(BgVatSubject.SERVICES, BgVatSubject.forOperation(" SERVICE_EU "));
    assertEquals(null, BgVatSubject.forOperation("CORRECTION_79"));
  }
}

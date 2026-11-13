package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.City;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.service.BankDetailsServiceImpl;
import com.axelor.apps.bglocalization.db.BgDeclarant;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import com.axelor.message.db.EmailAddress;
import com.axelor.studio.db.AppBgLocalization;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЦЕЛИЯТ МЕСЕЧЕН ФАЙЛ БЕЗ БАЗА — {@link BgSaftAuditFileService#compose} И ГЕНЕРИРАНЕТО (G6a).
 *
 * <p><b>Чист април:</b> продажба с ДДС на клиент (4111/702/4532), плащането ѝ по банка (503/4111,
 * INC_WT) и една стока на склад. Нито един документ — фактурите са нулевата структура, а доставчик
 * няма, тоест Suppliers е нулевата структура: и двете са ПРЕДУПРЕЖДЕНИЯ, не ГРЕШКИ.
 *
 * <p><b>⚠️ ОТПЕЧАТЪКЪТ Е НА compose(), НЕ НА WRITER-А.</b> {@link BgSaftMonthlyRegressionTest}
 * заковава байтовете на ръчно сглобени DTO-та; тук се заковава пътят от прочетените редове до файла
 * — с централната проверка, TaxTable от кодовете на файла и членството по референция. Смени ли се
 * СЪЗНАТЕЛНО, числото се вдига в същия PR, с причината.
 */
class BgSaftComposeTest {

  private static final LocalDate CREATED = LocalDate.of(2026, 5, 10);

  /**
   * 17 022 байта; файлът минава XSD 1.0.2 и е прегледан ръчно (сметките, клиентът, нулевият
   * доставчик „0“/998, TaxTable 000000 и 100211, дневникът). S.H.1 е „1.0.2“, не „007“ — с „007“ в
   * таблицата минава отпечатъкът {@code abfcf022…f901} (17 020 байта).
   */
  private static final String CLEAN_SHA256 =
      "6a635335ebb92c4abded75c695a6763692cfe43686957c33162e92cb22611530";

  // ─────────────── чистият април ───────────────

  private static Company company() {
    Country bg = new Country();
    bg.setAlpha2Code("BG");
    City sofia = new City();
    sofia.setName("София");
    Address address = new Address();
    address.setStreetName("бул. Витоша");
    address.setBuildingNumber("1");
    address.setZip("1000");
    address.setCity(sofia);
    address.setCountry(bg);
    EmailAddress email = new EmailAddress();
    email.setAddress("office@example.bg");
    Partner partner = new Partner();
    partner.setId(900L);
    partner.setRegistrationCode("123456789");
    partner.setTaxNbr("BG123456789");
    partner.setFixedPhone("+35921234567");
    partner.setEmailAddress(email);
    BankDetails bank = new BankDetails();
    bank.setIban("BG80BNBG96611020345678");
    BgDeclarant declarant = new BgDeclarant();
    declarant.setDeclarantNames("Иван Петров Иванов");
    declarant.setDeclarantCapacity("управител");
    declarant.setIsDefault(true);
    Company company = new Company();
    company.setId(7L);
    company.setName("Тест ЕООД");
    company.setPartner(partner);
    company.setAddress(address);
    company.setDefaultBankDetails(bank);
    List<BgDeclarant> declarants = new ArrayList<>();
    declarants.add(declarant);
    company.setBgDeclarantList(declarants);
    return company;
  }

  private static AppBgLocalization app() {
    AppBgLocalization app = new AppBgLocalization();
    app.setSaftSoftwareCompanyName("Софтуер ООД");
    app.setSaftSoftwareId("Nyxa");
    app.setSaftSoftwareVersion("1.0");
    return app;
  }

  private static Period period(LocalDate from, LocalDate to) {
    Period period = new Period();
    period.setId(426L);
    period.setFromDate(from);
    period.setToDate(to);
    return period;
  }

  private static BgSaftGlEntriesAssembler.LineRow line(
      long id, int counter, long move, String reference, long account, String code) {
    return new BgSaftGlEntriesAssembler.LineRow(id)
        .counter(counter)
        .account(account, code)
        .moveId(move)
        .reference(reference)
        .moveDescription("Април")
        .date(LocalDate.of(2026, 4, 10))
        .originDate(LocalDate.of(2026, 4, 10))
        .createdOn(LocalDate.of(2026, 4, 10))
        .periodFrom(LocalDate.of(2026, 4, 1));
  }

  private static BigDecimal d(String value) {
    return new BigDecimal(value);
  }

  /** Продажбата (статия 1) и плащането ѝ (статия 2). */
  private static List<BgSaftGlEntriesAssembler.LineRow> glLines() {
    List<BgSaftGlEntriesAssembler.LineRow> lines = new ArrayList<>();
    lines.add(
        line(1, 1, 1, "VEN/0001", 2, "4111")
            .debit(d("120"))
            .partnerId(10L)
            .journal(1L, "VEN", "Продажби"));
    lines.add(line(2, 2, 1, "VEN/0001", 3, "702").credit(d("100")).journal(1L, "VEN", "Продажби"));
    lines.add(
        line(3, 3, 1, "VEN/0001", 4, "4532")
            .credit(d("20"))
            .taxCodes(List.of("BG_SRS_20"))
            .journal(1L, "VEN", "Продажби"));
    lines.add(
        line(4, 1, 2, "BQ/0001", 8, "503")
            .debit(d("120"))
            .paymentModeCode("INC_WT")
            .journal(3L, "BQ", "Банка"));
    lines.add(
        line(5, 2, 2, "BQ/0001", 2, "4111")
            .credit(d("120"))
            .partnerId(10L)
            .paymentModeCode("INC_WT")
            .journal(3L, "BQ", "Банка"));
    return lines;
  }

  private static BgSaftBalance turnover(String debit, String credit) {
    return new BgSaftBalance(BigDecimal.ZERO, d(debit), d(credit));
  }

  static BgSaftAuditFileService.Inputs clean() {
    BgSaftAuditFileService.Inputs in = new BgSaftAuditFileService.Inputs();
    in.company = company();
    in.app = app();
    in.period = period(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30));
    in.createdOn = CREATED;
    in.fromDate = in.period.getFromDate();
    in.toDate = in.period.getToDate();
    in.chart = BgSaftFixtures.glChart();
    in.accountMappings = BgSaftFixtures.glMappings();
    in.taxMappings = BgSaftFixtures.glTaxMappings();
    in.paymentMappings = BgSaftFixtures.seedPaymentMappings();
    in.cellCodes = BgSaftInvoiceFixtures.seedCellCodes();
    in.withholdingMappings = new LinkedHashMap<>();
    in.withholdingMappings.put("301", new BgSaftWithholdingRouter.Mapping("700500", "CODE"));
    in.accountBalances = new LinkedHashMap<>();
    in.accountBalances.put(2L, turnover("120", "120"));
    in.accountBalances.put(3L, turnover("0", "100"));
    in.accountBalances.put(4L, turnover("0", "20"));
    in.accountBalances.put(8L, turnover("120", "0"));
    in.partyBalances = new HashMap<>();
    in.partyBalances.put(new BgSaftPartiesAssembler.Key(2L, 10L), turnover("120", "120"));
    in.partners = BgSaftFixtures.glPartners();
    in.partnerLoader = ids -> Map.of();
    in.referenceBalances = ids -> Map.of();
    in.glLines = glLines();
    in.withholding =
        BgSaftWithholdingRouter.route(
            in.glLines, Map.of(), Map.of(), in.withholdingMappings, BgNraNomenclatures.taxCodes());
    in.paymentLines =
        in.glLines.stream().filter(l -> l.moveId() == 2L).collect(Collectors.toList());
    in.invoiceSource = new BgSaftInvoicesAssembler.Source();
    in.invoicedProducts = List.of();
    in.stockedProducts =
        List.of(
            new BgSaftProductsAssembler.ProductRow(
                2L, "G1", "Смартфон", "storable", 2L, "8517 13 00"));
    in.units = BgSaftFixtures.units();
    in.conversions = BgSaftFixtures.conversions();
    in.usedTaxes = java.util.Set.of("BG_SRS_20");
    in.untaxedLines = true;
    in.unposted = List.of();
    in.createdAfter = since -> List.of();
    in.ibanRefusal = BgSaftIbanCheck.refusal(new BankDetailsServiceImpl());
    return in;
  }

  private static List<String> blocking(BgSaftAuditFileService.Result result) {
    return result.getFindings().stream()
        .filter(BgDeclarationFileFinding::isBlocking)
        .map(BgDeclarationFileFinding::toString)
        .collect(Collectors.toList());
  }

  private static List<BgDeclarationFileFinding> of(
      BgSaftAuditFileService.Result result, String code) {
    return result.getFindings().stream()
        .filter(f -> f.getCode().equals(code))
        .collect(Collectors.toList());
  }

  // ─────────────── чистият месец — файл, XSD, отпечатък ───────────────

  @Test
  @DisplayName(
      "Чист месец: нула ГРЕШКИ, файлът минава XSD, името е по Прил. 3 т. I.2, SHA-256 е закован")
  void чистият_месец_се_генерира() {
    BgSaftAuditFileService.Result result = BgSaftAuditFileService.compose(clean());
    assertEquals(List.of(), blocking(result));
    BgSaftGenerationService.Generated generated = BgSaftGenerationService.generate(result);
    assertTrue(generated.isGenerated(), generated.getFindings().toString());
    assertEquals("123456789_04_2026.xml", generated.getFileName());
    assertEquals(List.of(), BgSaftFixtures.validate(generated.getBytes()));
    assertEquals(CLEAN_SHA256, generated.getSha256(), "дължина " + generated.getBytes().length);
    // TaxTable е от кодовете на файла: 100211 (продажбата) и 000000 (редовете без данък).
    assertEquals(
        List.of("000/000000", "100/100211"),
        result.getFile().getMasterFiles().getTaxTableEntries().stream()
            .flatMap(
                e -> e.getTaxCodeDetails().stream().map(d -> e.getTaxType() + "/" + d.getTaxCode()))
            .collect(Collectors.toList()));
  }

  @Test
  @DisplayName("IBAN на подаващия: липсващ или невалиден е ГРЕШКА към дружеството, файл няма")
  void ibanът_спира_файла() {
    BgSaftAuditFileService.Inputs in = clean();
    in.company.setDefaultBankDetails(null);
    BgSaftAuditFileService.Result result = BgSaftAuditFileService.compose(in);
    List<BgDeclarationFileFinding> missing = of(result, "SAFT_HEADER_IBAN_MISSING");
    assertEquals(1, missing.size(), result.getFindings().toString());
    assertTrue(missing.get(0).isBlocking());
    assertEquals(BgSaftRef.COMPANY, missing.get(0).getTargetModel());
    assertFalse(BgSaftGenerationService.generate(result).isGenerated());

    in = clean();
    in.company.getDefaultBankDetails().setIban("BG81BNBG96611020345678");
    result = BgSaftAuditFileService.compose(in);
    assertEquals(1, of(result, "SAFT_HEADER_IBAN_INVALID").size(), result.getFindings().toString());
    assertFalse(BgSaftGenerationService.generate(result).isGenerated());
  }

  @Test
  @DisplayName("ГРЕШКА спира генерирането: няма байтове, няма име, находките са там")
  void грешката_спира_файла() {
    BgSaftAuditFileService.Inputs in = clean();
    // Ред по сметка без код по НАП — SAFT_GLE_UNMAPPED.
    List<BgSaftGlEntriesAssembler.LineRow> lines = new ArrayList<>(in.glLines);
    lines.add(line(6, 4, 1, "VEN/0001", 99, "6999").debit(d("0")).journal(1L, "VEN", "Продажби"));
    in.glLines = lines;
    BgSaftAuditFileService.Result result = BgSaftAuditFileService.compose(in);
    assertTrue(result.isBlocked());
    BgSaftGenerationService.Generated generated = BgSaftGenerationService.generate(result);
    assertFalse(generated.isGenerated());
    assertNull(generated.getBytes());
    assertNull(generated.getFileName());
    assertNull(generated.getSha256());
    assertTrue(
        generated.getFindings().stream()
            .anyMatch(f -> f.isBlocking() && f.getCode().equals("SAFT_GLE_UNMAPPED")));
  }

  // ─────────────── ЕИК на подаващия ───────────────

  @Test
  @DisplayName(
      "ЕИК с „BG“ — махнат с предупреждение към партньора; ID на подаващия е 10+ЕИК (QA IV.2.4)")
  void еик_с_bg() {
    BgSaftAuditFileService.Inputs in = clean();
    in.company.getPartner().setRegistrationCode("BG123456789");
    BgSaftAuditFileService.Result result = BgSaftAuditFileService.compose(in);
    assertEquals(List.of(), blocking(result));
    BgDeclarationFileFinding removed = of(result, "SAFT_HEADER_EIK_BG_PREFIX_REMOVED").get(0);
    assertFalse(removed.isBlocking());
    assertEquals(BgSaftRef.PARTNER, removed.getTargetModel());
    assertEquals(900L, removed.getTargetId());
    assertEquals("123456789", result.getFile().getHeader().getCompany().getRegistrationNumber());
    assertEquals("123456789_04_2026.xml", result.getFileName());
  }

  @Test
  @DisplayName("13-цифрен ЕИК (клон) — ГРЕШКА, без име на файл (Прил. 3 т. I.2; QA II.1.10)")
  void еик_на_клон() {
    BgSaftAuditFileService.Inputs in = clean();
    in.company.getPartner().setRegistrationCode("1234567890001");
    BgSaftAuditFileService.Result result = BgSaftAuditFileService.compose(in);
    assertTrue(of(result, "SAFT_FILE_NAME_BRANCH_EIK").get(0).isBlocking());
    assertNull(result.getFileName());
    assertFalse(BgSaftGenerationService.generate(result).isGenerated());
  }

  // ─────────────── средата ───────────────

  @Test
  @DisplayName("Период, който не е цял календарен месец — ГРЕШКА към периода")
  void периодът_не_е_месец() {
    BgSaftAuditFileService.Inputs in = clean();
    in.period = period(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 29));
    in.toDate = in.period.getToDate();
    BgDeclarationFileFinding finding =
        of(BgSaftAuditFileService.compose(in), "SAFT_PERIOD_NOT_CALENDAR_MONTH").get(0);
    assertTrue(finding.isBlocking());
    assertEquals(BgSaftRef.PERIOD, finding.getTargetModel());
    assertEquals(426L, finding.getTargetId());
  }

  @Test
  @DisplayName(
      "Таблица без ключовия си ред — ГРЕШКА „App Install“ по таблица, по присъствие, не по брой")
  void без_app_install() {
    BgSaftAuditFileService.Inputs in = clean();
    in.withholdingMappings = new LinkedHashMap<>(); // празна — като на база без App Install
    in.paymentMappings = new LinkedHashMap<>(in.paymentMappings);
    in.paymentMappings.remove("INC_WT"); // непълна — ключовият ред липсва
    List<BgDeclarationFileFinding> install =
        of(BgSaftAuditFileService.compose(in), "SAFT_ENV_APP_INSTALL");
    assertEquals(2, install.size(), install.toString());
    assertTrue(install.stream().allMatch(BgDeclarationFileFinding::isBlocking));
    assertTrue(install.get(0).getMessage().contains("BgSafPaymentMapping"));
    assertTrue(install.get(1).getMessage().contains("BgSafWithholdingMapping"));
  }

  @Test
  @DisplayName(
      "Неосчетоводени статии в месеца и статии след последното генериране — ПРЕДУПРЕЖДЕНИЯ към"
          + " статията")
  void статиите_извън_файла() {
    BgSaftAuditFileService.Inputs in = clean();
    in.unposted =
        List.of(
            new BgSaftEnvironmentCheck.MoveRow(51L, "ACH/0009", 2),
            new BgSaftEnvironmentCheck.MoveRow(52L, null, 1));
    LocalDateTime last = LocalDateTime.of(2026, 5, 5, 12, 0);
    in.lastGenerated = last;
    in.createdAfter =
        since -> {
          assertEquals(last, since);
          return List.of(new BgSaftEnvironmentCheck.MoveRow(53L, "VEN/0007", 3));
        };
    BgSaftAuditFileService.Result result = BgSaftAuditFileService.compose(in);
    List<BgDeclarationFileFinding> unposted = of(result, "SAFT_ENV_UNPOSTED_MOVE");
    assertEquals(2, unposted.size());
    assertFalse(unposted.get(0).isBlocking());
    assertTrue(unposted.get(0).getMessage().contains("в дневника, неосчетоводена"));
    assertTrue(unposted.get(1).getMessage().endsWith("#52"));
    assertEquals(51L, unposted.get(0).getTargetId());
    BgDeclarationFileFinding late = of(result, "SAFT_ENV_CREATED_AFTER_GENERATION").get(0);
    assertFalse(late.isBlocking());
    assertEquals(BgSaftRef.MOVE, late.getTargetModel());
    assertEquals(53L, late.getTargetId());
    // Без предишно генериране — нищо за „след“.
    assertTrue(
        of(BgSaftAuditFileService.compose(clean()), "SAFT_ENV_CREATED_AFTER_GENERATION").isEmpty());
  }

  // ─────────────── членството и TaxTable в целия файл ───────────────

  @Test
  @DisplayName(
      "Нулевите структури на чистия месец: Suppliers „0“ и фактурите — без ГРЕШКА, без"
          + " SAFT_REF_* за „0“ и 998")
  void нулевите_структури_минават_проверката() {
    BgSaftAuditFileService.Result result = BgSaftAuditFileService.compose(clean());
    assertEquals(
        List.of("0"),
        result.getFile().getMasterFiles().getSuppliers().stream()
            .map(p -> p.getPartyId())
            .collect(Collectors.toList()));
    assertTrue(
        result.getFindings().stream().noneMatch(f -> f.getCode().startsWith("SAFT_REF_")),
        result.getFindings().toString());
    assertNotNull(of(result, "SAFT_SUPPLIERS_EMPTY").get(0));
  }
}

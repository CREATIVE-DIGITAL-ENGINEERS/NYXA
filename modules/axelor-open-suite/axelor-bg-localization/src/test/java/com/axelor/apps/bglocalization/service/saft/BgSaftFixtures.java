package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.bglocalization.service.common.BgXsdValidator;
import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXsd;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAddress;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAmount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAuditFile;
import com.axelor.apps.bglocalization.service.saft.dto.SaftBankAccount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftCompanyHeader;
import com.axelor.apps.bglocalization.service.saft.dto.SaftContact;
import com.axelor.apps.bglocalization.service.saft.dto.SaftFileKind;
import com.axelor.apps.bglocalization.service.saft.dto.SaftGeneralLedger;
import com.axelor.apps.bglocalization.service.saft.dto.SaftGlAccount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftHeader;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoice;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoiceLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoiceSection;
import com.axelor.apps.bglocalization.service.saft.dto.SaftJournal;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMasterFiles;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMasterFilesAnnual;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMasterFilesOnDemand;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMovementOfGoods;
import com.axelor.apps.bglocalization.service.saft.dto.SaftOwnership;
import com.axelor.apps.bglocalization.service.saft.dto.SaftParty;
import com.axelor.apps.bglocalization.service.saft.dto.SaftPayment;
import com.axelor.apps.bglocalization.service.saft.dto.SaftPaymentLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftPaymentSection;
import com.axelor.apps.bglocalization.service.saft.dto.SaftPersonName;
import com.axelor.apps.bglocalization.service.saft.dto.SaftPhysicalStockEntry;
import com.axelor.apps.bglocalization.service.saft.dto.SaftProduct;
import com.axelor.apps.bglocalization.service.saft.dto.SaftSourceDocuments;
import com.axelor.apps.bglocalization.service.saft.dto.SaftStockMovement;
import com.axelor.apps.bglocalization.service.saft.dto.SaftStockMovementLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxCodeDetail;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxInformation;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxTableEntry;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransaction;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransactionLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftUom;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.repo.SaftReportRepository;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * МИНИМАЛНИЯТ МЕСЕЧЕН ФАЙЛ: всеки задължителен 1..n списък с ТОЧНО един ред и нито един
 * незадължителен елемент.
 *
 * <p>⚠️ ЧИСЛАТА СА ИЗМИСЛЕНИ, СТРУКТУРАТА НЕ Е. Фикстурата доказва, че writer-ът покрива целия
 * задължителен скелет на схемата, а не че сумите се връзват — това е работа на семантичния
 * валидатор (G6). Всеки метод връща НОВ обект, за да може тестът да го разваля, без да пипа чужд.
 */
final class BgSaftFixtures {

  private BgSaftFixtures() {}

  static final LocalDate DAY = LocalDate.of(2026, 4, 15);

  /** Дружеството и финансовата година на месечния отчет за април 2026. */
  static final long COMPANY_ID = 7L;

  static final long YEAR_ID = 26L;

  /** Месечният отчет за април 2026 — обхватът на статиите в тестовете на предиката. */
  static SaftReport april() {
    Company company = new Company();
    company.setId(COMPANY_ID);
    Year year = new Year();
    year.setId(YEAR_ID);
    year.setCompany(company);
    year.setTypeSelect(YearRepository.TYPE_FISCAL);
    year.setFromDate(LocalDate.of(2026, 1, 1));
    year.setToDate(LocalDate.of(2026, 12, 31));
    Period period = new Period();
    period.setYear(year);
    period.setFromDate(LocalDate.of(2026, 4, 1));
    period.setToDate(LocalDate.of(2026, 4, 30));
    SaftReport report = new SaftReport();
    report.setCompany(company);
    report.setPeriod(period);
    report.setReportTypeSelect(SaftReportRepository.REPORT_TYPE_MONTHLY);
    return report;
  }

  /** Срещу схемата, която таблицата дава за деня на фикстурите ({@link #DAY}). */
  static List<BgDeclarationFileFinding> validate(byte[] xml) {
    return validate(xml, BgSaftXsd.forDate(DAY));
  }

  static List<BgDeclarationFileFinding> validate(byte[] xml, BgSaftXsd.Version version) {
    return BgXsdValidator.validate(
        xml, version.getResource(), version.describe(), BgSaftXsd.CODE_PREFIX);
  }

  static SaftAuditFile minimal() {
    return new SaftAuditFile()
        .header(header())
        .masterFiles(masterFiles())
        .generalLedger(generalLedger())
        .sourceDocuments(sourceDocuments());
  }

  static SaftAddress address() {
    return new SaftAddress().city("София").country("BG");
  }

  static SaftHeader header() {
    return new SaftHeader()
        .auditFileVersion(BgSaftXsd.forDate(DAY).getAuditFileVersion())
        .auditFileDateCreated(DAY)
        .softwareCompanyName("")
        .softwareId("")
        .softwareVersion("")
        .company(
            new SaftCompanyHeader()
                .registrationNumber("123456789")
                .name("Тест ЕООД")
                .addAddress(address())
                .addContact(
                    new SaftContact()
                        .contactPerson(
                            new SaftPersonName()
                                .firstName("Иван")
                                .lastName("Иванов")
                                .addOtherTitle("управител")))
                .addBankAccount(new SaftBankAccount().ibanNumber("BG80BNBG96611020345678")))
        .ownership(
            new SaftOwnership()
                .isPartOfGroup("5")
                .addUltimateOwnerNameCyrillicBg("00")
                .addUltimateOwnerUicBg("00")
                .addUltimateOwnerNameCyrillicForeign("00")
                .addUltimateOwnerNameLatinForeign("00")
                .addCountryForeign("00"))
        .defaultCurrencyCode("EUR")
        .periodStart(4)
        .periodStartYear(2026)
        .periodEnd(4)
        .periodEndYear(2026)
        .taxAccountingBasis("A");
  }

  static SaftParty party(String id) {
    return new SaftParty()
        .registrationNumber(id)
        .name("Контрагент " + id)
        .addAddress(address())
        .relatedParty("N")
        .partyId(id)
        .accountId("411")
        .openingBalance(BigDecimal.ZERO)
        .closingBalance(BigDecimal.ZERO);
  }

  static SaftMasterFiles masterFiles() {
    return new SaftMasterFiles()
        .addGlAccount(
            new SaftGlAccount()
                .accountId("411")
                .accountDescription("Клиенти")
                .taxpayerAccountId("411")
                .accountType("Active")
                .openingBalance(BigDecimal.ZERO)
                .closingBalance(new BigDecimal("120.00")))
        .addCustomer(party("111111111"))
        .addSupplier(party("222222222"))
        .addTaxTableEntry(
            new SaftTaxTableEntry()
                .taxType("100")
                .description("ДДС")
                .addTaxCodeDetail(new SaftTaxCodeDetail().taxCode("100211").country("BG")))
        .addUom(new SaftUom().unitOfMeasure("H87").description("брой"))
        .addProduct(
            new SaftProduct()
                .productCode("P1")
                .description("Стока")
                .productCommodityCode("0")
                .uomBase("бр.")
                .uomStandard("H87")
                .uomToUomBaseConversionFactor(BigDecimal.ONE));
  }

  /**
   * GeneralLedgerAccounts, сглобени от assembler-а, а не на ръка: навиване (1221/1222 → 122),
   * сметка през родител (4111 → 411), кредитно салдо и сметка без А/П ос (→ Bifunctional).
   */
  static List<SaftGlAccount> assembledGlAccounts() {
    List<BgSaftGlAccountsAssembler.AccountRow> chart =
        List.of(
            new BgSaftGlAccountsAssembler.AccountRow(1L, "411", "Клиенти", "1", null),
            new BgSaftGlAccountsAssembler.AccountRow(2L, "4111", "Клиенти в страната", "1", 1L),
            new BgSaftGlAccountsAssembler.AccountRow(3L, "1221", "Непокрита загуба", "2", null),
            new BgSaftGlAccountsAssembler.AccountRow(
                4L, "1222", "Неразпределена печалба", "2", null),
            new BgSaftGlAccountsAssembler.AccountRow(5L, "123", "Печалби и загуби", null, null));
    Map<String, BgSaftGlAccountsAssembler.MappingRow> mappings =
        Map.of(
            "411", new BgSaftGlAccountsAssembler.MappingRow("411", false),
            "1221", new BgSaftGlAccountsAssembler.MappingRow("122", false),
            "1222", new BgSaftGlAccountsAssembler.MappingRow("122", false),
            "123", new BgSaftGlAccountsAssembler.MappingRow("123", false));
    Map<Long, BgSaftBalance> balances = new LinkedHashMap<>();
    balances.put(2L, new BgSaftBalance(new BigDecimal("80.000"), new BigDecimal("40"), null));
    balances.put(3L, BgSaftBalance.opening(new BigDecimal("15.50")));
    balances.put(4L, new BgSaftBalance(new BigDecimal("-300"), null, new BigDecimal("20.25")));
    balances.put(5L, BgSaftBalance.turnover(new BigDecimal("5"), new BigDecimal("5")));
    return BgSaftGlAccountsAssembler.assemble(
            chart, mappings, BgNraNomenclatures.accounts().keySet(), balances, false, false)
        .getAccounts();
  }

  /**
   * Customers и Suppliers, сглобени от assembler-а, а не на ръка: ЕИК (10), ДДС номер в ЕС (11),
   * физическо лице без данни (15), кредитно салдо и клиент, който е и доставчик.
   */
  static BgSaftPartiesAssembler.Result assembledParties() {
    List<BgSaftGlAccountsAssembler.AccountRow> chart =
        List.of(
            new BgSaftGlAccountsAssembler.AccountRow(1L, "411", "Клиенти", "1", null),
            new BgSaftGlAccountsAssembler.AccountRow(2L, "4111", "Клиенти в страната", "1", 1L),
            new BgSaftGlAccountsAssembler.AccountRow(3L, "401", "Доставчици", "2", null));
    Map<String, BgSaftGlAccountsAssembler.MappingRow> mappings =
        Map.of(
            "411", new BgSaftGlAccountsAssembler.MappingRow("411", false),
            "401", new BgSaftGlAccountsAssembler.MappingRow("401", false));
    Map<Long, BgSaftPartiesAssembler.PartyRow> partners =
        Map.of(
            10L,
            new BgSaftPartiesAssembler.PartyRow(10L)
                .partnerSeq("P10")
                .partnerTypeSelect(1)
                .name("Алфа ООД")
                .registrationCode("130175000")
                .street("Витоша")
                .number("1")
                .postalCode("1000")
                .city("София")
                .country("BG"),
            20L,
            new BgSaftPartiesAssembler.PartyRow(20L)
                .partnerSeq("P20")
                .partnerTypeSelect(1)
                .name("Beta GmbH")
                .taxNbr("DE123456789")
                .city("Berlin")
                .country("DE"),
            30L,
            new BgSaftPartiesAssembler.PartyRow(30L)
                .partnerSeq("P30")
                .partnerTypeSelect(2)
                .firstName("Иван")
                .name("Иванов")
                .city("Пловдив")
                .country("BG"));
    Map<BgSaftPartiesAssembler.Key, BgSaftBalance> balances = new LinkedHashMap<>();
    balances.put(
        new BgSaftPartiesAssembler.Key(2L, 10L),
        new BgSaftBalance(new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("30")));
    balances.put(
        new BgSaftPartiesAssembler.Key(2L, 20L),
        BgSaftBalance.turnover(new BigDecimal("120"), null));
    balances.put(
        new BgSaftPartiesAssembler.Key(3L, 10L),
        new BgSaftBalance(new BigDecimal("-80"), new BigDecimal("80"), new BigDecimal("200")));
    balances.put(
        new BgSaftPartiesAssembler.Key(3L, 30L),
        BgSaftBalance.turnover(null, new BigDecimal("45")));
    return BgSaftPartiesAssembler.assemble(chart, mappings, partners, balances);
  }

  /** Мерните единици на seed-а ({@code base_unit.csv}), със същите id-та като importId-тата. */
  static List<BgSaftProductsAssembler.UnitRow> units() {
    return List.of(
        new BgSaftProductsAssembler.UnitRow(1L, "Единица", "XUN"),
        new BgSaftProductsAssembler.UnitRow(2L, "Брой", "H87"),
        new BgSaftProductsAssembler.UnitRow(5L, "Кутия от 12 броя", "H87"),
        new BgSaftProductsAssembler.UnitRow(101L, "Час", "HUR"),
        new BgSaftProductsAssembler.UnitRow(102L, "Половин работен ден", "E49"),
        new BgSaftProductsAssembler.UnitRow(103L, "Работен ден", "E49"),
        new BgSaftProductsAssembler.UnitRow(104L, "Работна седмица", "E49"),
        new BgSaftProductsAssembler.UnitRow(105L, "Работен месец", "WM"),
        new BgSaftProductsAssembler.UnitRow(106L, "Работна година", "WM"),
        new BgSaftProductsAssembler.UnitRow(306L, "Килограм", "KGM"));
  }

  /** Конверсиите между тях от {@code base_unitConversion.csv}: 1 начална = coef крайни. */
  static List<BgSaftProductsAssembler.ConversionRow> conversions() {
    return List.of(
        conversion(5, 2, "12"),
        conversion(5, 1, "12"),
        conversion(102, 101, "4"),
        conversion(103, 101, "8"),
        conversion(104, 103, "5"),
        conversion(105, 103, "20"),
        conversion(106, 105, "12"),
        conversion(106, 103, "251"));
  }

  static BgSaftProductsAssembler.ConversionRow conversion(long start, long end, String coef) {
    return new BgSaftProductsAssembler.ConversionRow(start, end, new BigDecimal(coef));
  }

  /**
   * Products, сглобени от assembler-а: услуга (00000000), стока с КН8 с интервали, стока без КН8
   * („0“), глава вместо КН8 („0“), кутия от 12 броя (коефициент ≠ 1) и половин работен ден (през
   * „Час“).
   */
  static List<SaftProduct> assembledProducts() {
    return BgSaftProductsAssembler.assemble(
            List.of(
                new BgSaftProductsAssembler.ProductRow(
                    1L, "S1", "Консултация", "service", 101L, null),
                new BgSaftProductsAssembler.ProductRow(
                    2L, "G1", "Смартфон", "storable", 2L, "8517 13 00"),
                new BgSaftProductsAssembler.ProductRow(
                    3L, "G2", "Химикалки, кутия", "storable", 5L, null),
                new BgSaftProductsAssembler.ProductRow(4L, "G3", "Жито", "storable", 306L, "1001"),
                new BgSaftProductsAssembler.ProductRow(
                    5L, "S2", "Монтаж, половин ден", "service", 102L, null)),
            units(),
            conversions(),
            BgNraNomenclatures.nc8Codes())
        .getProducts();
  }

  /** UOMTable от кодовете, които продуктите ползват. */
  static List<SaftUom> assembledUoms(List<SaftProduct> products) {
    return BgSaftUomTableAssembler.assemble(
            products.stream().map(SaftProduct::getUomStandard).collect(Collectors.toList()),
            BgNraNomenclatures.unitsOfMeasure())
        .getEntries();
  }

  /** TaxTable: ред без данък (000), 20 %, 9 %, „Н/П“ и динамичен данък с подразбиращия се код. */
  static List<SaftTaxTableEntry> assembledTaxTable() {
    Map<String, BgSaftTaxTableAssembler.MappingRow> mappings =
        Map.of(
            "BG_SRS_20", new BgSaftTaxTableAssembler.MappingRow("100", "100211"),
            "BG_LRS_9", new BgSaftTaxTableAssembler.MappingRow("100", "100213"),
            "BG_RCM_ART82_S", new BgSaftTaxTableAssembler.MappingRow("100", "100226"),
            "BG_SRP_20", new BgSaftTaxTableAssembler.MappingRow("100", "100331"));
    return BgSaftTaxTableAssembler.assemble(
            mappings.keySet(), mappings, true, BgNraNomenclatures.taxCodes())
        .getEntries();
  }

  /** Подаващият в дневника — „10“ + ЕИК от Header-а на фикстурата. */
  static final String OWN_ID = "10123456789";

  /** Сметкопланът на дневника: 4111 през родителя 411 и две задбалансови (изключени). */
  static List<BgSaftGlAccountsAssembler.AccountRow> glChart() {
    return List.of(
        new BgSaftGlAccountsAssembler.AccountRow(1L, "411", "Клиенти", "1", null),
        new BgSaftGlAccountsAssembler.AccountRow(2L, "4111", "Клиенти в страната", "1", 1L),
        new BgSaftGlAccountsAssembler.AccountRow(3L, "702", "Приходи от продажби", "2", null),
        new BgSaftGlAccountsAssembler.AccountRow(4L, "4532", "ДДС за продажбите", "2", null),
        new BgSaftGlAccountsAssembler.AccountRow(5L, "302", "Материали", "1", null),
        new BgSaftGlAccountsAssembler.AccountRow(6L, "4531", "ДДС за покупките", "1", null),
        new BgSaftGlAccountsAssembler.AccountRow(7L, "401", "Доставчици", "2", null),
        new BgSaftGlAccountsAssembler.AccountRow(8L, "503", "Разплащателна сметка", "1", null),
        new BgSaftGlAccountsAssembler.AccountRow(10L, "9110", "Задбалансови активи", "1", null),
        new BgSaftGlAccountsAssembler.AccountRow(11L, "9190", "Задбалансови пасиви", "2", null));
  }

  static Map<String, BgSaftGlAccountsAssembler.MappingRow> glMappings() {
    Map<String, BgSaftGlAccountsAssembler.MappingRow> mappings = new LinkedHashMap<>();
    for (String code : List.of("411", "702", "4532", "302", "4531", "401", "503")) {
      mappings.put(code, new BgSaftGlAccountsAssembler.MappingRow(code, false));
    }
    mappings.put("9110", new BgSaftGlAccountsAssembler.MappingRow("911", true));
    mappings.put("9190", new BgSaftGlAccountsAssembler.MappingRow("", true));
    return mappings;
  }

  /** ЕИК (10), ДДС номер в ЕС (11) и физическо лице без данни (15). */
  static Map<Long, BgSaftPartiesAssembler.PartyRow> glPartners() {
    return Map.of(
        10L,
        new BgSaftPartiesAssembler.PartyRow(10L)
            .partnerSeq("P10")
            .partnerTypeSelect(1)
            .name("Алфа ООД")
            .registrationCode("130175000")
            .city("София")
            .country("BG"),
        20L,
        new BgSaftPartiesAssembler.PartyRow(20L)
            .partnerSeq("P20")
            .partnerTypeSelect(1)
            .name("Beta GmbH")
            .taxNbr("DE123456789")
            .city("Berlin")
            .country("DE"),
        30L,
        new BgSaftPartiesAssembler.PartyRow(30L)
            .partnerSeq("P30")
            .partnerTypeSelect(2)
            .firstName("Иван")
            .name("Иванов")
            .city("Пловдив")
            .country("BG"));
  }

  /** Фиксиран код, динамичен (подразбиране 100331) и „да се потвърди“ (празен код). */
  static Map<String, BgSaftTaxTableAssembler.MappingRow> glTaxMappings() {
    return Map.of(
        "BG_SRS_20", new BgSaftTaxTableAssembler.MappingRow("100", "100211", false),
        "BG_SRP_20", new BgSaftTaxTableAssembler.MappingRow("100", "100331", true),
        "BG_ZRS", new BgSaftTaxTableAssembler.MappingRow("100", "", false));
  }

  private static BgSaftGlEntriesAssembler.LineRow glLine(
      long id, Integer counter, long account, String code, String debit, String credit) {
    return new BgSaftGlEntriesAssembler.LineRow(id)
        .counter(counter)
        .debit(debit == null ? null : new BigDecimal(debit))
        .credit(credit == null ? null : new BigDecimal(credit))
        .account(account, code)
        .periodFrom(LocalDate.of(2026, 4, 1));
  }

  /**
   * Редовете на пет статии: продажба на клиент с ДДС, покупка с динамичен данък и трети знак,
   * плащане към двама доставчици, банкова такса без контрагент с „да се потвърди“ и неизвестен
   * данък и ред без номер, и статия само по задбалансови сметки. Редовете са разбъркани — редът е
   * работа на assembler-а.
   */
  static List<BgSaftGlEntriesAssembler.LineRow> glLines() {
    List<BgSaftGlEntriesAssembler.LineRow> lines = new ArrayList<>();
    LocalDate sale = LocalDate.of(2026, 4, 10);
    for (BgSaftGlEntriesAssembler.LineRow line :
        List.of(
            glLine(9003L, 3, 4L, "4532", null, "20").taxCodes(List.of("BG_SRS_20")),
            glLine(9001L, 1, 2L, "4111", "120", "0").partnerId(10L).description("Вземане"),
            glLine(9002L, 2, 3L, "702", "0", "100").taxCodes(List.of("BG_SRS_20")))) {
      lines.add(
          line.moveId(501L)
              .reference("VEN/0001")
              .moveDescription("Продажба по ф-ра 1")
              .origin("1")
              .date(sale)
              .originDate(LocalDate.of(2026, 4, 9))
              .createdOn(LocalDate.of(2026, 4, 11))
              .journal(1L, "VEN", "Продажби"));
    }
    for (BgSaftGlEntriesAssembler.LineRow line :
        List.of(
            glLine(9011L, 1, 5L, "302", "50.004", "0").taxCodes(List.of("BG_SRP_20")),
            glLine(9012L, 2, 6L, "4531", "10", "0").taxCodes(List.of("BG_SRP_20")),
            glLine(9013L, 3, 7L, "401", "0", "60").partnerId(20L))) {
      lines.add(
          line.moveId(502L)
              .reference("ACH/0001")
              .moveDescription("Покупка")
              .date(LocalDate.of(2026, 4, 12))
              .createdOn(LocalDate.of(2026, 4, 12))
              .journal(2L, "ACH", "Покупки"));
    }
    for (BgSaftGlEntriesAssembler.LineRow line :
        List.of(
            glLine(9023L, 3, 7L, "401", "50", "0").partnerId(30L),
            glLine(9021L, 1, 8L, "503", "0", "75"),
            glLine(9022L, 2, 7L, "401", "25", "0").partnerId(20L))) {
      lines.add(
          line.moveId(503L)
              .reference("BQ/0001")
              .moveDescription("Плащане към доставчици")
              .date(LocalDate.of(2026, 4, 26))
              .createdOn(LocalDate.of(2026, 4, 26))
              .journal(3L, "BQ", "Банка"));
    }
    for (BgSaftGlEntriesAssembler.LineRow line :
        List.of(
            glLine(9004L, null, 8L, "503", "0", "10"),
            glLine(9031L, 1, 5L, "302", "10", "0").taxCodes(List.of("XX_TAX", "BG_ZRS")))) {
      lines.add(
          line.moveId(504L)
              .reference("BQ/0002")
              .origin("BANK-77")
              .date(LocalDate.of(2026, 4, 28))
              .createdOn(LocalDate.of(2026, 4, 28))
              .journal(3L, "BQ", "Банка"));
    }
    for (BgSaftGlEntriesAssembler.LineRow line :
        List.of(glLine(9041L, 1, 10L, "9110", "5", "0"), glLine(9042L, 2, 11L, "9190", "0", "5"))) {
      lines.add(
          line.moveId(505L)
              .reference("OD/0001")
              .moveDescription("Задбалансово")
              .date(LocalDate.of(2026, 4, 15))
              .createdOn(LocalDate.of(2026, 4, 15))
              .journal(4L, "OD", "Други"));
    }
    return lines;
  }

  /** Customers и Suppliers на същите редове — оборотът им по (сметка, партньор). */
  static BgSaftPartiesAssembler.Result glParties() {
    Map<BgSaftPartiesAssembler.Key, BgSaftBalance> balances = new LinkedHashMap<>();
    balances.put(
        new BgSaftPartiesAssembler.Key(2L, 10L),
        BgSaftBalance.turnover(new BigDecimal("120"), null));
    balances.put(
        new BgSaftPartiesAssembler.Key(7L, 20L),
        BgSaftBalance.turnover(new BigDecimal("25"), new BigDecimal("60")));
    balances.put(
        new BgSaftPartiesAssembler.Key(7L, 30L),
        BgSaftBalance.turnover(new BigDecimal("50"), null));
    return BgSaftPartiesAssembler.assemble(glChart(), glMappings(), glPartners(), balances);
  }

  static BgSaftGlEntriesAssembler.Result assembledGlEntriesResult() {
    return BgSaftGlEntriesAssembler.assemble(
        glLines(),
        glChart(),
        glMappings(),
        glPartners(),
        glTaxMappings(),
        BgNraNomenclatures.taxCodes(),
        OWN_ID);
  }

  /** GeneralLedgerEntries, сглобени от assembler-а, а не на ръка. */
  static SaftGeneralLedger assembledGlEntries() {
    return assembledGlEntriesResult().getGeneralLedger();
  }

  // ─────────────── Payments (G4) ───────────────

  /** Сметкопланът на плащанията: този на дневника плюс каса (501) и подотчетни лица (422). */
  static List<BgSaftGlAccountsAssembler.AccountRow> payChart() {
    List<BgSaftGlAccountsAssembler.AccountRow> chart = new ArrayList<>(glChart());
    chart.add(new BgSaftGlAccountsAssembler.AccountRow(12L, "501", "Каса в лева", "1", null));
    chart.add(new BgSaftGlAccountsAssembler.AccountRow(13L, "422", "Подотчетни лица", "3", null));
    return chart;
  }

  static Map<String, BgSaftGlAccountsAssembler.MappingRow> payMappings() {
    Map<String, BgSaftGlAccountsAssembler.MappingRow> mappings = glMappings();
    mappings.put("501", new BgSaftGlAccountsAssembler.MappingRow("501", false));
    mappings.put("422", new BgSaftGlAccountsAssembler.MappingRow("422", false));
    return mappings;
  }

  /**
   * Мапингът на режимите — ИСТИНСКИЯТ seed ({@code bg_saf_payment_mapping.csv}), прочетен от
   * classpath-а, а не преписан: методът и механизмът в тестовете са тези, които ще влязат в базата.
   */
  static Map<String, BgSaftPaymentsAssembler.MappingRow> seedPaymentMappings() {
    String resource = "/apps/init-data/en/bg_saf_payment_mapping.csv";
    String header = "importId;paymentModeCode;methodCode;mechanismCode;isExcluded;note";
    Map<String, BgSaftPaymentsAssembler.MappingRow> mappings = new LinkedHashMap<>();
    try (InputStream in = BgSaftFixtures.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException(resource + " не е в classpath-а");
      }
      BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
      if (!header.equals(reader.readLine())) {
        throw new IllegalStateException(resource + ": заглавният ред е променен");
      }
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.isEmpty()) {
          continue;
        }
        String[] f = line.split(";", -1);
        if (f.length != 6) {
          throw new IllegalStateException(resource + ": " + f.length + " полета в „" + line + "“");
        }
        mappings.put(
            f[1], new BgSaftPaymentsAssembler.MappingRow(f[2], f[3], Boolean.parseBoolean(f[4])));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return mappings;
  }

  private static LocalDate april(int day) {
    return LocalDate.of(2026, 4, day);
  }

  /** Редовете на една статия-плащане — общите полета на статията върху всеки ред. */
  private static List<BgSaftGlEntriesAssembler.LineRow> payMove(
      long moveId,
      String reference,
      String description,
      String origin,
      LocalDate date,
      long journalId,
      String journalCode,
      String paymentMode,
      BgSaftGlEntriesAssembler.LineRow... lines) {
    List<BgSaftGlEntriesAssembler.LineRow> rows = new ArrayList<>();
    for (BgSaftGlEntriesAssembler.LineRow line : lines) {
      rows.add(
          line.moveId(moveId)
              .reference(reference)
              .moveDescription(description)
              .origin(origin)
              .date(date)
              .createdOn(date)
              .journal(journalId, journalCode, journalCode)
              .paymentModeCode(paymentMode));
    }
    return rows;
  }

  /**
   * Редовете на статиите-плащания за април: двете банкови статии на дневника ({@link #glLines()} —
   * към двама доставчици с OUT_WT и банкова такса без режим) и още осем — постъпление от клиент с
   * трети знак (INC_WT), плащане в брой на доставчик (OUT_CSH), внасяне в касата от банката (превод
   * между собствени сметки), прихващане (OUT_OFFSET), плащане през подотчетно лице (OUT_ADV),
   * изключен режим (INC_OTH), режим без мапинг и статия по банката без паричен ред. Редовете са
   * разбъркани — редът е работа на assembler-а.
   */
  static List<BgSaftGlEntriesAssembler.LineRow> payLines() {
    List<BgSaftGlEntriesAssembler.LineRow> lines = new ArrayList<>();
    for (BgSaftGlEntriesAssembler.LineRow line : glLines()) {
      if (line.moveId() == 503L) {
        lines.add(line.paymentModeCode("OUT_WT"));
      } else if (line.moveId() == 504L) {
        lines.add(line);
      }
    }
    lines.addAll(
        payMove(
            601L,
            "BQ/0003",
            "Плащане от Алфа",
            "1",
            april(5),
            3L,
            "BQ",
            "INC_WT",
            glLine(9101L, 1, 8L, "503", "120.004", "0"),
            glLine(9102L, 2, 2L, "4111", "0", "120.004").partnerId(10L)));
    lines.addAll(
        payMove(
            602L,
            "CSH/0001",
            "Плащане в брой на Beta",
            null,
            april(7),
            5L,
            "CSH",
            "OUT_CSH",
            glLine(9111L, 1, 7L, "401", "30", "0").partnerId(20L),
            glLine(9112L, 2, 12L, "501", "0", "30")));
    lines.addAll(
        payMove(
            603L,
            "CSH/0002",
            "Внасяне в касата",
            null,
            april(8),
            5L,
            "CSH",
            null,
            glLine(9121L, 1, 12L, "501", "200", "0"),
            glLine(9122L, 2, 8L, "503", "0", "200")));
    lines.addAll(
        payMove(
            604L,
            "OFS/0001",
            "Прихващане с Алфа",
            null,
            april(20),
            6L,
            "MOA",
            "OUT_OFFSET",
            glLine(9131L, 1, 7L, "401", "40", "0").partnerId(10L),
            glLine(9132L, 2, 2L, "4111", "0", "40").partnerId(10L)));
    lines.addAll(
        payMove(
            605L,
            "ADV/0001",
            "Платено от подотчетно лице",
            null,
            april(21),
            6L,
            "MOA",
            "OUT_ADV",
            glLine(9141L, 1, 7L, "401", "15", "0").partnerId(20L),
            glLine(9142L, 2, 13L, "422", "0", "15")));
    lines.addAll(
        payMove(
            606L,
            "BQ/0004",
            "Друго постъпление",
            null,
            april(22),
            3L,
            "BQ",
            "INC_OTH",
            glLine(9151L, 1, 8L, "503", "5", "0"),
            glLine(9152L, 2, 2L, "4111", "0", "5").partnerId(10L)));
    lines.addAll(
        payMove(
            607L,
            "BQ/0005",
            "Непознат режим",
            null,
            april(23),
            3L,
            "BQ",
            "XX_PAY",
            glLine(9161L, 1, 7L, "401", "8", "0").partnerId(30L),
            glLine(9162L, 2, 8L, "503", "0", "8")));
    lines.addAll(
        payMove(
            608L,
            "BQ/0006",
            "Преквалификация",
            null,
            april(24),
            3L,
            "BQ",
            "OUT_WT",
            glLine(9171L, 1, 7L, "401", "7", "0").partnerId(20L),
            glLine(9172L, 2, 2L, "4111", "0", "7").partnerId(10L)));
    Collections.reverse(lines);
    return lines;
  }

  /** Customers и Suppliers на плащанията — Алфа е и клиент, и доставчик (прихващането). */
  static BgSaftPartiesAssembler.Result payParties() {
    Map<BgSaftPartiesAssembler.Key, BgSaftBalance> balances = new LinkedHashMap<>();
    balances.put(
        new BgSaftPartiesAssembler.Key(2L, 10L),
        BgSaftBalance.turnover(new BigDecimal("120"), new BigDecimal("172.004")));
    balances.put(
        new BgSaftPartiesAssembler.Key(7L, 10L),
        BgSaftBalance.turnover(new BigDecimal("40"), null));
    balances.put(
        new BgSaftPartiesAssembler.Key(7L, 20L),
        BgSaftBalance.turnover(new BigDecimal("77"), new BigDecimal("60")));
    balances.put(
        new BgSaftPartiesAssembler.Key(7L, 30L),
        BgSaftBalance.turnover(new BigDecimal("58"), null));
    return BgSaftPartiesAssembler.assemble(payChart(), payMappings(), glPartners(), balances);
  }

  static BgSaftPaymentsAssembler.Result assembledPaymentsResult() {
    return BgSaftPaymentsAssembler.assemble(
        payLines(),
        payChart(),
        payMappings(),
        glPartners(),
        seedPaymentMappings(),
        BgNraNomenclatures.paymentMechanisms(),
        OWN_ID);
  }

  /** Payments, сглобени от assembler-а, а не на ръка. */
  static SaftPaymentSection assembledPayments() {
    return assembledPaymentsResult().getPayments();
  }

  static SaftTaxInformation tax() {
    return new SaftTaxInformation()
        .taxType("100")
        .taxCode("100211")
        .taxAmount(SaftAmount.eur(new BigDecimal("20.00")));
  }

  static SaftGeneralLedger generalLedger() {
    return new SaftGeneralLedger()
        .numberOfEntries(1L)
        .totalDebit(new BigDecimal("120.00"))
        .totalCredit(new BigDecimal("120.00"))
        .journals(
            List.of(
                new SaftJournal()
                    .transactions(
                        List.of(
                            new SaftTransaction()
                                .transactionId("T1")
                                .period(4)
                                .periodYear(2026)
                                .transactionDate(DAY)
                                .description("Продажба")
                                .systemEntryDate(DAY)
                                .glPostingDate(DAY)
                                .customerId("111111111")
                                .supplierId("0")
                                .lines(
                                    List.of(
                                        new SaftTransactionLine()
                                            .recordId("1")
                                            .accountId("411")
                                            .taxpayerAccountId("411")
                                            .customerId("111111111")
                                            .supplierId("0")
                                            .description("Вземане")
                                            .debit(true)
                                            .amount(SaftAmount.eur(new BigDecimal("120.00")))
                                            .addTaxInformation(tax())))))));
  }

  static SaftInvoice invoice() {
    return new SaftInvoice()
        .invoiceNo("0000000001")
        .partyId("111111111")
        .partyName("Контрагент")
        .billingAddress(address())
        .accountId("411")
        .invoiceDate(DAY)
        .invoiceType("01")
        .selfBillingIndicator("N")
        .transactionId("T1")
        .addLine(
            new SaftInvoiceLine()
                .accountId("702")
                .productCode("P1")
                .quantity(BigDecimal.ONE)
                .unitPrice(new BigDecimal("100.00"))
                .taxPointDate(DAY)
                .description("Стока")
                .invoiceLineAmount(SaftAmount.eur(new BigDecimal("100.00")))
                .debitCreditIndicator("C")
                .addTaxInformation(tax()));
  }

  static SaftInvoiceSection invoices() {
    return new SaftInvoiceSection()
        .numberOfEntries(1L)
        .totalDebit(BigDecimal.ZERO)
        .totalCredit(new BigDecimal("100.00"))
        .invoices(List.of(invoice()));
  }

  static SaftSourceDocuments sourceDocuments() {
    return new SaftSourceDocuments()
        .salesInvoices(invoices())
        .payments(
            new SaftPaymentSection()
                .numberOfEntries(1L)
                .totalDebit(new BigDecimal("120.00"))
                .totalCredit(BigDecimal.ZERO)
                .payments(
                    List.of(
                        new SaftPayment()
                            .paymentRefNo("PAY1")
                            .transactionId("T2")
                            .transactionDate(DAY)
                            .paymentMethod("03")
                            .description("Плащане")
                            .addLine(
                                new SaftPaymentLine()
                                    .accountId("503")
                                    .customerId("111111111")
                                    .supplierId("0")
                                    .debitCreditIndicator("D")
                                    .paymentLineAmount(SaftAmount.eur(new BigDecimal("120.00")))))))
        .purchaseInvoices(invoices());
  }

  // ─────────────── годишният файл и файлът при поискване (F0) ───────────────

  /** Периодът на файла при поискване — първата половина на юли, както в примера на QA с. 20. */
  static final LocalDate ON_DEMAND_FROM = LocalDate.of(2026, 7, 1);

  static final LocalDate ON_DEMAND_TO = LocalDate.of(2026, 7, 15);

  /**
   * Минималният годишен файл: периоди 1–12, „A“, празното Assets (Asset 0..n) и нулевата структура
   * на AssetTransactions по QA IV.2.6.
   */
  static SaftAuditFile minimalAnnual() {
    return new SaftAuditFile()
        .kind(SaftFileKind.ANNUAL)
        .header(header().periodStart(1).periodEnd(12).headerComment("A"))
        .masterFilesAnnual(new SaftMasterFilesAnnual())
        .assetTransactions(BgSaftZeroStructures.assetTransactions());
  }

  /**
   * Минималният файл при поискване: датите вместо периодите, „D“, без TaxTable и по един ред във
   * всяка задължителна таблица; MovementTypeTable е от assembler-а — само видът на движението.
   */
  static SaftAuditFile minimalOnDemand() {
    SaftMovementOfGoods goods = movementOfGoods();
    SaftMasterFilesOnDemand m =
        new SaftMasterFilesOnDemand()
            .addUom(new SaftUom().unitOfMeasure("H87").description("бройка"))
            .addProduct(masterFiles().getProducts().get(0))
            .addPhysicalStockEntry(physicalStockEntry());
    BgSaftMovementTypeTableAssembler.assemble(goods, BgNraNomenclatures.movementTypes())
        .getEntries()
        .forEach(m::addMovementType);
    return new SaftAuditFile()
        .kind(SaftFileKind.ON_DEMAND)
        .header(
            header()
                .periodStart(null)
                .periodStartYear(null)
                .periodEnd(null)
                .periodEndYear(null)
                .selectionStartDate(ON_DEMAND_FROM)
                .selectionEndDate(ON_DEMAND_TO)
                .headerComment("D"))
        .masterFilesOnDemand(m)
        .movementOfGoods(goods);
  }

  /** Наличността на P1: десет в началото, пет в края, по 2.50. */
  static SaftPhysicalStockEntry physicalStockEntry() {
    return new SaftPhysicalStockEntry()
        .warehouseId("WH1")
        .productCode("P1")
        .productType("30")
        .stockAccountCommodityCode("85171300")
        .ownerId(OWN_ID)
        .uomPhysicalStock("H87")
        .uomToUomBaseConversionFactor(BigDecimal.ONE)
        .unitPriceBegin(new BigDecimal("2.50"))
        .unitPriceEnd(new BigDecimal("2.50"))
        .openingStockQuantity(new BigDecimal("10"))
        .openingStockValue(new BigDecimal("25.00"))
        .closingStockQuantity(new BigDecimal("5"))
        .closingStockValue(new BigDecimal("12.50"));
  }

  /** Едно движение: продажба (30) на пет броя — излизането е с „-“ (SD.MG.1, бележката). */
  static SaftMovementOfGoods movementOfGoods() {
    return new SaftMovementOfGoods()
        .numberOfMovementLines(1L)
        .totalQuantityReceived(BigDecimal.ZERO)
        .totalQuantityIssued(new BigDecimal("5"))
        .addStockMovement(
            new SaftStockMovement()
                .movementReference("SM1")
                .movementDate(LocalDate.of(2026, 7, 10))
                .movementType("30")
                .sourceId("admin")
                .systemId("SM1")
                .documentType("01")
                .documentNumber("0000000001")
                .addLine(
                    new SaftStockMovementLine()
                        .lineNumber("1")
                        .accountId("304")
                        .transactionId("T1")
                        .customerId("111111111")
                        .supplierId("0")
                        .productCode("P1")
                        .quantity(new BigDecimal("-5"))
                        .unitOfMeasure("H87")
                        .uomToUomPhysicalStockConversionFactor(BigDecimal.ONE)
                        .bookValue(new BigDecimal("12.50"))
                        .movementSubType("30")));
  }
}

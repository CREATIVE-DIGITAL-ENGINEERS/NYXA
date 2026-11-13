package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.service.BankDetailsService;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAuditFile;
import com.axelor.apps.bglocalization.service.saft.dto.SaftCompanyHeader;
import com.axelor.apps.bglocalization.service.saft.dto.SaftGeneralLedger;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoice;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoiceLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoiceSection;
import com.axelor.apps.bglocalization.service.saft.dto.SaftJournal;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMasterFiles;
import com.axelor.apps.bglocalization.service.saft.dto.SaftParty;
import com.axelor.apps.bglocalization.service.saft.dto.SaftProduct;
import com.axelor.apps.bglocalization.service.saft.dto.SaftSourceDocuments;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransaction;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransactionLine;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.db.JPA;
import com.axelor.studio.db.AppBgLocalization;
import com.axelor.studio.db.repo.AppBgLocalizationRepository;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * ОТ {@link SaftReport} ДО МОДЕЛА НА ФАЙЛА — дружеството и периодът идват от отчета, а не от
 * извикващия.
 *
 * <p><b>⚠️ СМЕТКОПЛАНЪТ И МАПИНГЪТ СЕ ЧЕТАТ ВЕДНЪЖ</b> и се подават и на сметките, и на
 * контрагентите — така AccountID на клиента е същият като в GeneralLedgerAccounts.
 *
 * <p><b>⚠️ ОТКАЗВА, ВМЕСТО ДА ГАДАЕ</b> — виж {@link BgSaftMoveScope#of(SaftReport)}: без период,
 * без финансова година или с година на друго дружество файл не се сглобява.
 *
 * <p><b>⚠️ СЪДЪРЖАНИЕТО.</b> Тук влизат Header, шестте таблици на MasterFiles —
 * GeneralLedgerAccounts, Customers, Suppliers, TaxTable, UOMTable и Products — дневникът
 * (GeneralLedgerEntries) и SourceDocuments: фактурите, протоколите и останалите статии с ДДС без
 * фактура (от реда им в дневниците, G5b) и плащанията. Документ без код дава ГРЕШКА от {@link
 * BgSaftInvoicesAssembler}, тоест непълен файл не може да излезе по погрешка.
 *
 * <p><b>⚠️ ПЛАЩАНИЯТА СА СТАТИИ ОТ ДНЕВНИКА</b> — {@link BgSaftMoveScope#PAYMENT_LINES}, със същите
 * сметкоплан, мапинг, контрагенти и подаващ, тоест TransactionID, AccountID и CustomerID/SupplierID
 * на плащането са тези от дневника и от таблиците.
 *
 * <p><b>⚠️ ДНЕВНИКЪТ СЕ СВЕРЯВА СЪС САЛДАТА.</b> Двете части четат статиите през {@link
 * BgSaftMoveScope}; {@link BgSaftGlEntriesAssembler#reconcile} проверява равенството „начално +
 * оборот = крайно“ по сметка върху самите числа на файла, както ще го провери НАП.
 *
 * <p><b>⚠️ КОДОВЕТЕ 600/700 В ДНЕВНИКА (G3b)</b> идват от {@link BgSaftWithholdingRouter} — по
 * фишовете и фактурите на СЪЩИТЕ статии и по ръчния код на реда/сметката.
 *
 * <p>UOMTable се сглобява СЛЕД продуктите: в нея влизат кодовете, които те ползват. Находките все
 * пак следват реда на секциите във файла.
 */
@Singleton
public class BgSaftAuditFileService {

  protected final BgSaftLedgerReader ledgerReader;
  protected final BgSaftMasterDataReader masterDataReader;
  protected final BgSaftGlEntriesReader glEntriesReader;
  protected final BgSaftPaymentsReader paymentsReader;
  protected final BgSaftInvoicesReader invoicesReader;
  protected final BgSaftWithholdingReader withholdingReader;
  protected final AppBgLocalizationRepository appRepo;
  protected final BankDetailsService bankDetailsService;

  @Inject
  public BgSaftAuditFileService(
      BgSaftLedgerReader ledgerReader,
      BgSaftMasterDataReader masterDataReader,
      BgSaftGlEntriesReader glEntriesReader,
      BgSaftPaymentsReader paymentsReader,
      BgSaftInvoicesReader invoicesReader,
      BgSaftWithholdingReader withholdingReader,
      AppBgLocalizationRepository appRepo,
      BankDetailsService bankDetailsService) {
    this.ledgerReader = ledgerReader;
    this.masterDataReader = masterDataReader;
    this.glEntriesReader = glEntriesReader;
    this.paymentsReader = paymentsReader;
    this.invoicesReader = invoicesReader;
    this.withholdingReader = withholdingReader;
    this.appRepo = appRepo;
    this.bankDetailsService = bankDetailsService;
  }

  /** Моделът на файла и всички находки по пътя, в реда на секциите. */
  public static final class Result {
    private final SaftAuditFile file;
    private final List<BgDeclarationFileFinding> findings;
    private final String fileName;

    private Result(SaftAuditFile file, List<BgDeclarationFileFinding> findings, String fileName) {
      this.file = file;
      this.findings = Collections.unmodifiableList(findings);
      this.fileName = fileName;
    }

    /**
     * За сглобяването на годишния файл и на файла при поискване ({@link
     * BgSaftAnnualOnDemandService}).
     */
    static Result of(SaftAuditFile file, List<BgDeclarationFileFinding> findings, String fileName) {
      return new Result(file, findings, fileName);
    }

    /** Името по Прил. 3 т. I.2; {@code null}, когато ЕИК-ът го прави невъзможно (ГРЕШКА). */
    public String getFileName() {
      return fileName;
    }

    /** Има ли ГРЕШКА — тогава файл не се произвежда. */
    public boolean isBlocked() {
      return findings.stream().anyMatch(BgDeclarationFileFinding::isBlocking);
    }

    public SaftAuditFile getFile() {
      return file;
    }

    public List<BgDeclarationFileFinding> getFindings() {
      return findings;
    }
  }

  public Result assemble(SaftReport report, LocalDate createdOn) {
    return assemble(report, createdOn, null);
  }

  /**
   * Моделът на файла и находките — на assembler-ите, на централната проверка по QA IV.2.4 ({@link
   * BgSaftReferenceCheck}) и на средата ({@link BgSaftEnvironmentCheck}). Чете базата и предава на
   * {@link #compose}.
   *
   * @param lastGenerated кога е генериран последният файл на отчета; {@code null} — няма такъв
   */
  public Result assemble(SaftReport report, LocalDate createdOn, LocalDateTime lastGenerated) {
    BgSaftMoveScope scope = BgSaftMoveScope.of(report);
    Long companyId = report.getCompany().getId();
    Long periodId = report.getPeriod().getId();
    Inputs in = new Inputs();
    in.company = report.getCompany();
    in.app = appRepo.all().fetchOne();
    in.period = report.getPeriod();
    in.createdOn = createdOn;
    in.lastGenerated = lastGenerated;
    in.fromDate = scope.getFromDate();
    in.toDate = scope.getToDate();
    in.chart = ledgerReader.chart(report.getCompany().getId());
    in.accountMappings = ledgerReader.accountMappings();
    in.taxMappings = masterDataReader.taxMappings();
    in.paymentMappings = paymentsReader.paymentMappings();
    in.cellCodes = invoicesReader.vatCellCodes();
    in.withholdingMappings = withholdingReader.mappings();
    in.accountBalances = ledgerReader.balancesByAccount(scope);
    in.postedBeforeYear = ledgerReader.postedBeforeYear(scope);
    in.openingMoveInYear = ledgerReader.openingMoveInYear(scope);
    in.partyBalances =
        ledgerReader.balancesByAccountAndPartner(
            scope, BgSaftPartiesAssembler.partyAccounts(in.chart, in.accountMappings).keySet());
    Set<Long> partnerIds = new HashSet<>();
    in.partyBalances.keySet().forEach(key -> partnerIds.add(key.getPartnerId()));
    partnerIds.remove(null);
    in.partners = ledgerReader.partners(partnerIds);
    in.partnerLoader = ledgerReader::partners;
    in.referenceBalances = ids -> ledgerReader.balancesByAccountAndPartner(scope, ids);
    in.glLines = glEntriesReader.lines(scope);
    // G3b — кодовете 600/700 по документите на СЪЩИТЕ статии; веднага след четенето, преди
    // следващ читател да изчисти сесията (перата на фишовете са entity-та).
    Set<Long> moveIds = new HashSet<>();
    in.glLines.forEach(line -> moveIds.add(line.moveId()));
    in.withholding =
        BgSaftWithholdingRouter.route(
            in.glLines,
            withholdingReader.payroll(moveIds),
            withholdingReader.invoices(moveIds),
            in.withholdingMappings,
            BgNraNomenclatures.taxCodes());
    in.paymentLines = paymentsReader.lines(scope);
    in.invoiceSource = invoicesReader.source(scope);
    in.invoicedProducts = masterDataReader.invoicedProducts(scope);
    in.stockedProducts = masterDataReader.stockedProducts(scope);
    in.units = masterDataReader.units();
    in.conversions = masterDataReader.unitConversions();
    in.usedTaxes = masterDataReader.usedTaxes(scope);
    in.untaxedLines = masterDataReader.hasUntaxedLines(scope);
    in.unposted = ledgerReader.unpostedMoves(scope);
    in.createdAfter = since -> ledgerReader.movesCreatedAfter(scope, since);
    in.ibanRefusal = BgSaftIbanCheck.refusal(bankDetailsService);
    // Читателите чистят сесията на парчета (JPA.clear()) — дружеството, периодът и приложението
    // отгоре вече са откачени, а заглавието обхожда мързеливи връзки (партньор, адрес, банкови
    // сметки, декларатори). Затова се четат НАНОВО точно преди сглобяването; без това всяко
    // генериране срещу истинска база гърми с LazyInitializationException (пробният файл, G6c).
    in.company = JPA.find(Company.class, companyId);
    in.period = JPA.find(Period.class, periodId);
    in.app = appRepo.all().fetchOne();
    return compose(in);
  }

  /** Всичко, което сглобяването чете от базата — за да е {@link #compose} чиста функция. */
  public static final class Inputs {
    Company company;
    AppBgLocalization app;
    Period period;
    LocalDate createdOn;
    LocalDateTime lastGenerated;
    LocalDate fromDate;
    LocalDate toDate;
    List<BgSaftGlAccountsAssembler.AccountRow> chart;
    Map<String, BgSaftGlAccountsAssembler.MappingRow> accountMappings;
    Map<String, BgSaftTaxTableAssembler.MappingRow> taxMappings;
    Map<String, BgSaftPaymentsAssembler.MappingRow> paymentMappings;
    Map<String, String> cellCodes;
    Map<String, BgSaftWithholdingRouter.Mapping> withholdingMappings;
    Map<Long, BgSaftBalance> accountBalances;
    boolean postedBeforeYear;
    boolean openingMoveInYear;
    Map<BgSaftPartiesAssembler.Key, BgSaftBalance> partyBalances;
    Map<Long, BgSaftPartiesAssembler.PartyRow> partners;
    Function<Collection<Long>, Map<Long, BgSaftPartiesAssembler.PartyRow>> partnerLoader;
    Function<Collection<Long>, Map<BgSaftPartiesAssembler.Key, BgSaftBalance>> referenceBalances;
    List<BgSaftGlEntriesAssembler.LineRow> glLines;
    BgSaftWithholdingRouter.Result withholding;
    List<BgSaftGlEntriesAssembler.LineRow> paymentLines;
    BgSaftInvoicesAssembler.Source invoiceSource;
    List<BgSaftProductsAssembler.Use> invoicedProducts;
    List<BgSaftProductsAssembler.ProductRow> stockedProducts;
    List<BgSaftProductsAssembler.UnitRow> units;
    List<BgSaftProductsAssembler.ConversionRow> conversions;
    Set<String> usedTaxes;
    boolean untaxedLines;
    List<BgSaftEnvironmentCheck.MoveRow> unposted;
    Function<LocalDateTime, List<BgSaftEnvironmentCheck.MoveRow>> createdAfter;

    /** Защо един IBAN е невалиден — {@link BgSaftIbanCheck#refusal}; {@code null} = валиден. */
    Function<String, String> ibanRefusal;
  }

  /** Сглобяването без база — от прочетеното в {@link Inputs}. */
  static Result compose(Inputs in) {
    YearMonth reportMonth = YearMonth.from(in.fromDate);
    List<BgDeclarationFileFinding> findings = new ArrayList<>();

    // Средата — периодът и таблиците от App Install, преди всичко останало.
    findings.addAll(BgSaftEnvironmentCheck.period(in.period.getId(), in.fromDate, in.toDate));
    Map<String, Set<String>> loaded = new LinkedHashMap<>();
    loaded.put("BgSafAccountMapping", in.accountMappings.keySet());
    loaded.put("BgSafTaxMapping", in.taxMappings.keySet());
    loaded.put("BgSafPaymentMapping", in.paymentMappings.keySet());
    loaded.put("BgSafVatCellCode", in.cellCodes.keySet());
    loaded.put("BgSafWithholdingMapping", in.withholdingMappings.keySet());
    findings.addAll(BgSaftEnvironmentCheck.referenceTables(loaded));

    BgSaftHeaderAssembler.Result header =
        BgSaftHeaderAssembler.assemble(in.company, in.app, in.period, in.createdOn);
    for (BgDeclarationFileFinding finding : header.getFindings()) {
      findings.add(headerTarget(finding, in.company, in.app));
    }
    // S.CMH.6 / S.BA.1 — задължителен и валиден IBAN на подаващия (протокол 30TST000014738).
    for (BgDeclarationFileFinding finding :
        BgSaftIbanCheck.check(header.getHeader().getCompany(), in.ibanRefusal)) {
      findings.add(headerTarget(finding, in.company, in.app));
    }
    String ownId = ownId(header.getHeader().getCompany());
    // Името на файла носи ЕИК-а — 13-цифрен (клон) или невалиден е ГРЕШКА (Прил. 3 т. I.2).
    BgSaftFileName.Result fileName =
        BgSaftFileName.of(
            header.getHeader().getCompany().getRegistrationNumber(),
            BgSaftSelection.monthly(in.period));
    findings.addAll(fileName.getFindings());

    BgSaftGlAccountsAssembler.Result accounts =
        BgSaftGlAccountsAssembler.assemble(
            in.chart,
            in.accountMappings,
            BgNraNomenclatures.accounts().keySet(),
            in.accountBalances,
            in.postedBeforeYear,
            in.openingMoveInYear);
    findings.addAll(accounts.getFindings());

    Set<Long> partyAccountIds =
        BgSaftPartiesAssembler.partyAccounts(in.chart, in.accountMappings).keySet();
    Map<BgSaftPartiesAssembler.Key, BgSaftBalance> partyBalances = new HashMap<>(in.partyBalances);

    // Плащанията — статиите-плащания от същия дневник, със същите ID-та.
    BgSaftPaymentsAssembler.Result payments =
        BgSaftPaymentsAssembler.assemble(
            in.paymentLines,
            in.chart,
            in.accountMappings,
            in.partners,
            in.paymentMappings,
            BgNraNomenclatures.paymentMechanisms(),
            ownId);

    // Фактурите и протоколите — контрагентът им със същия ID като в таблиците; партньор без салдо
    // по 40/41 се дочита.
    Map<Long, BgSaftPartiesAssembler.PartyRow> documentPartners = new HashMap<>(in.partners);
    Set<Long> missing = new HashSet<>(in.invoiceSource.partnerIds());
    missing.removeAll(documentPartners.keySet());
    if (!missing.isEmpty()) {
      documentPartners.putAll(in.partnerLoader.apply(missing));
    }
    BgSaftInvoicesAssembler.Result invoices =
        BgSaftInvoicesAssembler.assemble(
            in.invoiceSource,
            in.chart,
            in.accountMappings,
            documentPartners,
            in.taxMappings,
            in.cellCodes,
            BgNraNomenclatures.taxCodes(),
            BgNraNomenclatures.invoiceTypes(),
            ownParty(header.getHeader().getCompany(), ownId));

    // Дневникът — СЛЕД документите: данък без код в мапинга взима кода на документа на статията
    // (решение от 15.10.2026). Партньорите и мапингите са същите като на таблиците.
    BgSaftGlEntriesAssembler.Result entries =
        BgSaftGlEntriesAssembler.assemble(
            in.glLines,
            in.chart,
            in.accountMappings,
            in.partners,
            in.taxMappings,
            BgNraNomenclatures.taxCodes(),
            ownId,
            in.withholding.getByLine(),
            reportMonth,
            invoices.getDocumentCodes());

    // Customers/Suppliers — оборот по 40/41 ИЛИ референция от подаден документ (решение 2); салдата
    // по разчетните сметки на референциите извън 40/41 се дочитат.
    Set<Long> referenceAccounts = new HashSet<>();
    for (BgSaftPartiesAssembler.Reference reference : invoices.getReferences()) {
      if (reference.accountId() != null && !partyAccountIds.contains(reference.accountId())) {
        referenceAccounts.add(reference.accountId());
      }
    }
    if (!referenceAccounts.isEmpty()) {
      partyBalances.putAll(in.referenceBalances.apply(referenceAccounts));
    }
    BgSaftPartiesAssembler.Result parties =
        BgSaftPartiesAssembler.assemble(
            in.chart,
            in.accountMappings,
            documentPartners,
            partyBalances,
            invoices.getReferences());

    BgSaftProductsAssembler.Result products =
        BgSaftProductsAssembler.assemble(
            BgSaftProductsAssembler.select(
                in.invoicedProducts,
                in.stockedProducts,
                BgSaftGlAccountsAssembler.nraAccountIds(in.chart, in.accountMappings)),
            in.units,
            in.conversions,
            BgNraNomenclatures.nc8Codes());
    BgSaftUomTableAssembler.Result uoms =
        BgSaftUomTableAssembler.assemble(
            products.getProducts().stream()
                .map(SaftProduct::getUomStandard)
                .collect(Collectors.toList()),
            BgNraNomenclatures.unitsOfMeasure());
    // TaxTable — от кодовете, които файлът реално сочи (след 600/700 и клетките, G6a); мапингът
    // дава само находките си.
    BgSaftTaxTableAssembler.Result taxMappingFindings =
        BgSaftTaxTableAssembler.assemble(
            in.usedTaxes, in.taxMappings, in.untaxedLines, BgNraNomenclatures.taxCodes());
    BgSaftTaxTableAssembler.Result taxes =
        BgSaftTaxTableAssembler.fromCodes(
            usedTaxCodes(entries.getGeneralLedger(), invoices), BgNraNomenclatures.taxCodes());

    // Находките — в реда на секциите във файла.
    findings.addAll(parties.getFindings());
    findings.addAll(taxes.getFindings());
    taxMappingFindings.getFindings().stream()
        .filter(f -> !MAPPING_ONLY_EXCLUDED.contains(f.getCode()))
        .forEach(findings::add);
    findings.addAll(uoms.getFindings());
    findings.addAll(products.getFindings());
    findings.addAll(entries.getFindings());
    findings.addAll(in.withholding.getFindings());
    findings.addAll(
        BgSaftGlEntriesAssembler.reconcile(
            accounts.getAccounts(), entries.getNetByTaxpayerAccount()));
    findings.addAll(payments.getFindings());
    findings.addAll(invoices.getFindings());

    SaftMasterFiles masterFiles = new SaftMasterFiles();
    accounts.getAccounts().forEach(masterFiles::addGlAccount);
    parties.getCustomers().forEach(masterFiles::addCustomer);
    parties.getSuppliers().forEach(masterFiles::addSupplier);
    taxes.getEntries().forEach(masterFiles::addTaxTableEntry);
    uoms.getEntries().forEach(masterFiles::addUom);
    products.getProducts().forEach(masterFiles::addProduct);
    SaftAuditFile file =
        new SaftAuditFile()
            .header(header.getHeader())
            .masterFiles(masterFiles)
            .generalLedger(entries.getGeneralLedger())
            .sourceDocuments(
                new SaftSourceDocuments()
                    .salesInvoices(invoices.getSales())
                    .payments(payments.getPayments())
                    .purchaseInvoices(invoices.getPurchases()));

    // QA IV.2.4 над готовия файл — всеки рефериран ID е в MasterFiles.
    findings.addAll(BgSaftReferenceCheck.check(file, ownId, partnerIdsByPartyId(documentPartners)));
    // Статиите, които не са във файла или са след последното генериране.
    findings.addAll(BgSaftEnvironmentCheck.unposted(in.unposted));
    if (in.lastGenerated != null) {
      findings.addAll(
          BgSaftEnvironmentCheck.createdAfter(
              in.createdAfter.apply(in.lastGenerated), in.lastGenerated));
    }
    return new Result(file, findings, fileName.getFileName());
  }

  /**
   * Находките на мапинга на данъците, които TaxTable от кодовете на файла казва по-точно: празна
   * таблица и не-ДДС кодовете.
   */
  static final Set<String> MAPPING_ONLY_EXCLUDED =
      Collections.unmodifiableSet(
          new HashSet<>(Arrays.asList("SAFT_TT_EMPTY", "SAFT_TT_NON_VAT_TO_CONFIRM")));

  /**
   * S.TI.2 на всеки ред на дневника, фактурите и протоколите — това, което TaxTable трябва да има.
   */
  static Set<String> usedTaxCodes(
      SaftGeneralLedger ledger, BgSaftInvoicesAssembler.Result invoices) {
    Set<String> codes = new TreeSet<>();
    for (SaftJournal journal : ledger.getJournals()) {
      for (SaftTransaction t : journal.getTransactions()) {
        for (SaftTransactionLine line : t.getLines()) {
          line.getTaxInformation().forEach(ti -> codes.add(ti.getTaxCode()));
        }
      }
    }
    for (SaftInvoiceSection section : Arrays.asList(invoices.getSales(), invoices.getPurchases())) {
      if (section.getNumberOfEntries() != null && section.getNumberOfEntries() == 0L) {
        continue; // нулевата структура — 000000 е по образеца, не по данък
      }
      for (SaftInvoice invoice : section.getInvoices()) {
        for (SaftInvoiceLine line : invoice.getLines()) {
          line.getTaxInformation().forEach(ti -> codes.add(ti.getTaxCode()));
        }
      }
    }
    return codes;
  }

  /** ID-то по НАП → партньорът зад него — за указателя на находките по QA IV.2.4. */
  private static Map<String, Long> partnerIdsByPartyId(
      Map<Long, BgSaftPartiesAssembler.PartyRow> partners) {
    Map<String, Long> ids = new HashMap<>();
    partners.forEach(
        (id, row) -> ids.putIfAbsent(BgSaftPartiesAssembler.identify(row).getPartyId(), id));
    return ids;
  }

  /** Находките на Header-а сочат дружеството, а тези за софтуера — настройките на модула. */
  static BgDeclarationFileFinding headerTarget(
      BgDeclarationFileFinding finding, Company company, AppBgLocalization app) {
    if (finding.getTargetModel() != null) {
      return finding;
    }
    if (finding.getCode().startsWith("SAFT_HEADER_SOFTWARE_")) {
      return app == null ? finding : finding.at(APP_MODEL, app.getId());
    }
    return finding.at(BgSaftRef.COMPANY, company.getId());
  }

  private static final String APP_MODEL = "com.axelor.studio.db.AppBgLocalization";

  /**
   * Подаващият в дневника — „10“ + ЕИК (QA, GL.20), същият ЕИК като в Header (S.CMH.1). Без ЕИК
   * Header-ът вече дава ГРЕШКА.
   */
  /**
   * Подаващият като контрагент на протокол без доставка (чл. 79, 79а) — Прил. 3 т. IV.7: „данни на
   * лицето, подаващо файла“. ID-то е това на GL, името и адресът — от Header.
   */
  static SaftParty ownParty(SaftCompanyHeader company, String ownId) {
    SaftParty party = new SaftParty().partyId(ownId);
    if (company != null) {
      party.name(company.getName());
      if (!company.getAddresses().isEmpty()) {
        party.addAddress(company.getAddresses().get(0));
      }
    }
    return party;
  }

  static String ownId(SaftCompanyHeader company) {
    String eik = company == null ? null : company.getRegistrationNumber();
    return BgSaftPartyId.EIK + (eik == null ? "" : eik.trim());
  }
}

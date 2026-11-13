package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.account.db.repo.InvoiceLineRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.bglocalization.service.saft.BgSaftGlAccountsAssembler.Resolution;
import com.axelor.apps.bglocalization.service.saft.BgSaftPartiesAssembler.PartyRow;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAddress;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAmount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoice;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoiceLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoiceSection;
import com.axelor.apps.bglocalization.service.saft.dto.SaftParty;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxInformation;
import com.axelor.apps.bglocalization.service.vat.BgVatValidator;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseCreditTypeMapping;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseTarget;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SourceDocuments/SalesInvoices и PurchaseInvoices (S.I.*, SD.SI.*, SD.PI.*) ОТ ФАКТУРИТЕ НА
 * ПЕРИОДА — чиста функция (G5a).
 *
 * <p><b>Нула заявки.</b> Фактурите идват от {@link BgSaftInvoicesReader} — тези, чиито статии са в
 * {@link BgSaftMoveScope#PERIOD_MOVES}, тоест статиите на дневника: S.I.18 TransactionID е GL.9 на
 * осчетоводяването (QA с. 24). Сметкопланът, мапингите и контрагентите са тези на G2.
 *
 * <p><b>⚠️ ПОСОКАТА Е {@code operationTypeSelect}</b> — 3/4 продажби, 1/2 покупки, същото като
 * дневниците на Блок Б. Данъчният код на реда е на {@link BgSaftInvoiceTaxRouter}.
 *
 * <p><b>⚠️ ПРОТОКОЛЪТ Е РЪЧНА СТАТИЯ, НЕ ФАКТУРА.</b> Протоколите и останалите статии с ДДС без
 * фактура идват от реда, който дневниците правят от тях ({@link BgSaftProtocolInvoices}, G5b).
 * Фактура, която е протокол или иска протокол — обратно начисляване, вид 09 или 91–94, протоколен
 * данък — е ГРЕШКА с указание да се състави протоколът: дневникът не я подава като протокол.
 * Изключенията:
 *
 * <ul>
 *   <li>фактурата с обратно начисляване, която ДНЕВНИКЪТ вече е отразил чрез протокола ѝ по чл. 117
 *       ({@link DiaryVerdict}): при ВОП и чл. 82 тя не е в дневниците, затова не е и тук
 *       (ПРЕДУПРЕЖДЕНИЕ); при чл. 163а е в покупките само с колони 1–8а и тук е със 100330, без
 *       данък (отговорът на НАП по имейл от 19.10.2026);
 *   <li>полученият протокол по чл. 151в ал. 3/7 (вид 91/93) — в PurchaseInvoices по кредита, и
 *       фактурите/известията по касова отчетност (11/12/13) — по {@link BgSaftCashAccounting}.
 * </ul>
 *
 * <p>Вид без маршрут (29, 95, отчетите, регистрите) е ГРЕШКА „нерешен код“; златото, маржата,
 * обратното начисляване извън Прил. № 2 и получените документи при дружество по касова отчетност са
 * ГРЕШКА „да се потвърди“ ({@link BgSaftNapPending}). Нищо не изпада мълчаливо. Документ с каквато
 * и да е ГРЕШКА не влиза в секцията: схемата никога не вижда празен TaxType/TaxCode (QA IV.2.3, с.
 * 95–96).
 *
 * <p><b>⚠️ НОМЕРЪТ Е ТОЗИ НА ДНЕВНИКА</b> ({@link #documentNumber}): номерът на доставчика, иначе
 * нашият — правилото на {@code BgVatDocumentCollector.enrichFromInvoice}, сверено с тест.
 *
 * <p><b>⚠️ ДАТИТЕ.</b> Продажба: S.I.8 и S.I.43 са {@code invoiceDate}. Печатът на фактурата
 * (общият {@code Invoice.rptdesign}; BG шаблон няма) показва само „Дата“ = {@code invoiceDate}, без
 * отделна дата на данъчното събитие — по ЗДДС чл. 114 ал. 1 т. 10 тя тогава е датата на издаване.
 * Покупка: датата на документа на доставчика ({@code originDate}), иначе {@code invoiceDate}. ⚠️
 * ПРАЗНИНА: в Axelor няма поле „дата на данъчното събитие“, различна от датата на документа.
 *
 * <p><b>⚠️ СУМИТЕ.</b> InvoiceLineAmount е основата на реда във валутата на дружеството, 3 → 2
 * знака HALF_UP с находка. D/C по QA (с. 76–78): D увеличава вземането/задължението, C е
 * намалението с ПОЛОЖИТЕЛЕН знак — кредитно известие и отстъпка. TaxAmount е данъкът на реда в
 * същата посока. Данъкът на фактурата Axelor го смята по ставка ({@code InvoiceLineTax}), а не по
 * ред — затова остатъкът се разпределя по метода на най-големия остатък в детерминиран ред, така че
 * Σ на редовете = {@code InvoiceLineTax.companyTaxTotal} и S.I.67 GrossTotal излиза сумата на
 * фактурата. TotalDebit/TotalCredit (SD.SI.2/3, SD.PI.2/3) са Σ InvoiceLineAmount по индикатор, БЕЗ
 * TaxAmount (QA IV.2.5, с. 96–97); NumberOfEntries е броят на фактурите.
 *
 * <p><b>⚠️ ProductCode „0“</b> (Прил. 3 т. IV.6–7, QA с. 75–76): ред без продукт; ред на покупка,
 * който не е по сметка за стоково-материални запаси (група 30 на НАП) — ДМА, директно разходвани
 * артикули, текущи разходи.
 *
 * <p><b>⚠️ МЕСЕЦ БЕЗ ДОКУМЕНТИ</b> — нулевата структура от QA IV.2.6 (с. 99–100) дословно, с
 * CurrencyCode EUR. Сметка 998 от нея я НЯМА в номенклатурата на сметките — следва се QA.
 */
public final class BgSaftInvoicesAssembler {

  public static final String SELF_BILLING_NO = "N";

  /** S.I.36, когато продуктов код не се подава. */
  public static final String NO_PRODUCT = "0";

  static final String DEBIT = "D";
  static final String CREDIT = "C";

  /** Група 30 на НАП — стоково-материалните запаси. */
  static final String STOCK_GROUP = "30";

  /** Видовете документ, които G5a подава; останалите от Nom_Invoice_Types са протоколи и отчети. */
  static final Set<String> G5A_TYPES = set("01", "02", "03", "07", "23");

  /**
   * Фактура с вид на протокол — 09 и протоколите по чл. 151в/151г (91–94). Протоколът се подава от
   * ръчната статия, по която го пише дневникът.
   */
  static final Set<String> PROTOCOL_TYPES = set("09", "91", "92", "93", "94");

  /** ППЗДДС Прил. № 12: вид, който POKUPKI не може да съдържа. */
  static final Set<String> NOT_IN_PURCHASE_DIARY = set("04", "50", "81", "82", "83", "84", "85");

  /** ППЗДДС Прил. № 12: вид, който PRODAGBI не може да съдържа. */
  static final Set<String> NOT_IN_SALES_DIARY = set("05", "92");

  // ─────────────── нулевата структура (QA IV.2.6) ───────────────

  static final String EMPTY_ACCOUNT = "998";
  static final LocalDate EMPTY_DATE = LocalDate.of(1900, 1, 1);
  static final String EMPTY_DESCRIPTION = "Продажба";

  private static final int SCALE = 2;
  private static final int QUANTITY_SCALE = 6;

  /** SAFmiddle2textType, SAFlongtextType. */
  private static final int MIDDLE2 = 70;

  private static final int LONG = 256;

  private static final BigDecimal HUNDRED = new BigDecimal(100);
  private static final BigDecimal CENT = new BigDecimal("0.01");

  private BgSaftInvoicesAssembler() {}

  // ─────────────── входът ───────────────

  /** Фактура, както я чете {@link BgSaftInvoicesReader}. */
  public static final class InvoiceRow {
    private final Long id;
    private String invoiceId;
    private String supplierInvoiceNb;
    private int operationType;
    private String documentType;
    private LocalDate invoiceDate;
    private LocalDate originDate;
    private Long partnerId;
    private Long partnerAccountId;
    private Long moveId;
    private String moveReference;
    private LocalDate periodFrom;
    private String supplyType;
    private DiaryVerdict diaryVerdict;

    public InvoiceRow(Long id) {
      this.id = id;
    }

    /** {@code Invoice.invoiceId} и {@code Invoice.supplierInvoiceNb}. */
    public InvoiceRow number(String invoiceId, String supplierInvoiceNb) {
      this.invoiceId = invoiceId;
      this.supplierInvoiceNb = supplierInvoiceNb;
      return this;
    }

    public InvoiceRow operationType(Integer value) {
      this.operationType = value == null ? 0 : value;
      return this;
    }

    /** {@code Invoice.napDocumentTypeSelect}. */
    public InvoiceRow documentType(String value) {
      this.documentType = value;
      return this;
    }

    public InvoiceRow dates(LocalDate invoiceDate, LocalDate originDate) {
      this.invoiceDate = invoiceDate;
      this.originDate = originDate;
      return this;
    }

    /** {@code Invoice.partner} и {@code Invoice.partnerAccount}. */
    public InvoiceRow partner(Long partnerId, Long partnerAccountId) {
      this.partnerId = partnerId;
      this.partnerAccountId = partnerAccountId;
      return this;
    }

    /** Статията: id, {@code reference} и началото на периода ѝ. */
    public InvoiceRow move(Long moveId, String reference, LocalDate periodFrom) {
      this.moveId = moveId;
      this.moveReference = reference;
      this.periodFrom = periodFrom;
      return this;
    }

    /** {@code Invoice.bgVatSupplyType} — к.8а (поле 02-27 / 03-45). */
    public InvoiceRow supplyType(String value) {
      this.supplyType = value;
      return this;
    }

    /** Присъдата на дневника — само за фактура с обратно начисляване. */
    public InvoiceRow diaryVerdict(DiaryVerdict value) {
      this.diaryVerdict = value;
      return this;
    }
  }

  /**
   * Присъдата на дневника за фактура с обратно начисляване — от {@code BgVatMoveDiaryRows}, тоест
   * от колектора, който решава дали протоколът по чл. 117 я покрива.
   */
  public static final class DiaryVerdict {
    private final boolean inDiaries;
    private final List<String> errors;

    /**
     * @param inDiaries фактурата има ред в PRODAGBI или POKUPKI
     * @param errors грешките на документа в дневника („код: съобщение“)
     */
    public DiaryVerdict(boolean inDiaries, List<String> errors) {
      this.inDiaries = inDiaries;
      this.errors = errors == null ? Collections.emptyList() : new ArrayList<>(errors);
    }

    /** Покрита от протокола и извън дневниците — ВОП и чл. 82. */
    boolean coveredOutsideDiaries() {
      return !inDiaries && errors.isEmpty();
    }

    /**
     * Получената фактура по чл. 163а — в дневника за покупки само с колони 1–8а (ППЗДДС чл. 97а ал.
     * 5). В PurchaseInvoices е със 100330 (отговорът на НАП по имейл от 19.10.2026).
     */
    boolean art163aInPurchaseDiary() {
      return inDiaries && errors.isEmpty();
    }

    /** Защо фактурата не се подава — за ГРЕШКАТА. */
    String reason() {
      return errors.isEmpty()
          ? "обратно начисляване"
          : "обратно начисляване — дневникът: " + String.join("; ", errors);
    }
  }

  /** Ред на фактура. Сумите са във валутата на дружеството. */
  public static final class LineRow {
    private final Long id;
    private final Long invoiceId;
    private int sequence;
    private int type;
    private Long productId;
    private String productCode;
    private String productName;
    private String description;
    private BigDecimal qty;
    private BigDecimal exTax;
    private BigDecimal inTax;
    private Long accountId;
    private String creditType;
    private final List<LineTax> taxes = new ArrayList<>();

    public LineRow(Long id, Long invoiceId) {
      this.id = id;
      this.invoiceId = invoiceId;
    }

    public LineRow sequence(Integer value) {
      this.sequence = value == null ? 0 : value;
      return this;
    }

    /** {@code InvoiceLine.typeSelect}: заглавните редове не са редове на документа. */
    public LineRow type(Integer value) {
      this.type = value == null ? InvoiceLineRepository.TYPE_NORMAL : value;
      return this;
    }

    public LineRow product(Long productId, String productCode, String productName) {
      this.productId = productId;
      this.productCode = productCode;
      this.productName = productName;
      return this;
    }

    public LineRow description(String value) {
      this.description = value;
      return this;
    }

    public LineRow qty(BigDecimal value) {
      this.qty = value;
      return this;
    }

    /** {@code companyExTaxTotal} и {@code companyInTaxTotal}. */
    public LineRow amounts(BigDecimal exTax, BigDecimal inTax) {
      this.exTax = exTax;
      this.inTax = inTax;
      return this;
    }

    public LineRow accountId(Long value) {
      this.accountId = value;
      return this;
    }

    /** {@code InvoiceLine.bgVatCreditType}. */
    public LineRow creditType(String value) {
      this.creditType = value;
      return this;
    }

    /** Данък от {@code taxLineSet}: ставката е в проценти ({@code TaxLine.value}, 20 = 20 %). */
    public LineRow tax(Long taxLineId, String taxCode, BigDecimal rate) {
      taxes.add(new LineTax(taxLineId, taxCode, rate));
      return this;
    }
  }

  private static final class LineTax {
    private final Long taxLineId;
    private final String code;
    private final BigDecimal rate;

    private LineTax(Long taxLineId, String code, BigDecimal rate) {
      this.taxLineId = taxLineId;
      this.code = code;
      this.rate = rate;
    }
  }

  /** Ред на {@code InvoiceLineTax} — данъкът на фактурата по ставка. */
  public static final class InvoiceTaxRow {
    private final Long invoiceId;
    private final Long taxLineId;
    private final BigDecimal companyTaxTotal;
    private final boolean reverseCharged;

    public InvoiceTaxRow(
        Long invoiceId, Long taxLineId, BigDecimal companyTaxTotal, Boolean reverseCharged) {
      this.invoiceId = invoiceId;
      this.taxLineId = taxLineId;
      this.companyTaxTotal = companyTaxTotal;
      this.reverseCharged = Boolean.TRUE.equals(reverseCharged);
    }
  }

  /** Всичко, което четецът е прочел за периода. */
  public static final class Source {
    private final List<InvoiceRow> invoices = new ArrayList<>();
    private final List<LineRow> lines = new ArrayList<>();
    private final List<InvoiceTaxRow> invoiceTaxes = new ArrayList<>();
    private final List<BgSaftProtocolInvoices.DiaryDocument> diaryDocuments = new ArrayList<>();
    private final List<String> outOfScope = new ArrayList<>();
    private final Map<Long, String> reportingPeriod = new LinkedHashMap<>();
    private BgSaftCashAccounting cashAccounting = BgSaftCashAccounting.NONE;

    public Source add(InvoiceRow value) {
      invoices.add(value);
      return this;
    }

    public Source add(LineRow value) {
      lines.add(value);
      return this;
    }

    public Source add(InvoiceTaxRow value) {
      invoiceTaxes.add(value);
      return this;
    }

    /** Ред в дневниците от статия без фактура — протокол, ЕАД или ръчна покупка (G5b). */
    public Source add(BgSaftProtocolInvoices.DiaryDocument value) {
      diaryDocuments.add(value);
      return this;
    }

    /** Периодът на дружеството по касова отчетност ({@code Company.bgVatCashAccountingFrom/To}). */
    public Source cashAccounting(BgSaftCashAccounting value) {
      this.cashAccounting = value == null ? BgSaftCashAccounting.NONE : value;
      return this;
    }

    /** Статия с ред в дневника, която GL не носи — за предупреждението. */
    public Source outOfScope(String label) {
      outOfScope.add(label);
      return this;
    }

    /** Фактура, чийто дневник е в друг месец от SAF-T („Период на отразяване по ЗДДС“). */
    public Source reportingPeriod(Long invoiceId, String label) {
      reportingPeriod.put(invoiceId, label);
      return this;
    }

    /** Партньорите на фактурите — да се дочетат онези, които таблиците не носят. */
    public Set<Long> partnerIds() {
      Set<Long> ids = new HashSet<>();
      for (BgSaftProtocolInvoices.DiaryDocument document : diaryDocuments) {
        if (document.getPartnerId() != null) {
          ids.add(document.getPartnerId());
        }
      }
      for (InvoiceRow invoice : invoices) {
        if (invoice.partnerId != null) {
          ids.add(invoice.partnerId);
        }
      }
      return ids;
    }
  }

  /** Двете секции и находките. */
  public static final class Result {
    private final SaftInvoiceSection sales;
    private final SaftInvoiceSection purchases;
    private final List<BgDeclarationFileFinding> findings;
    private final List<BgSaftPartiesAssembler.Reference> references;
    private final BgSaftDocumentCodes documents;

    private Result(
        SaftInvoiceSection sales,
        SaftInvoiceSection purchases,
        List<BgDeclarationFileFinding> findings,
        List<BgSaftPartiesAssembler.Reference> references,
        BgSaftDocumentCodes documents) {
      this.sales = sales;
      this.purchases = purchases;
      this.findings = Collections.unmodifiableList(findings);
      this.references = Collections.unmodifiableList(references);
      this.documents = documents;
    }

    /**
     * Контрагентите на подадените документи — Customers/Suppliers ги включват и без оборот по 40/41
     * (членство по референция, решение 2 от 14.10.2026).
     */
    public List<BgSaftPartiesAssembler.Reference> getReferences() {
      return references;
    }

    /** Кодовете на подадените документи по статия — за дневника (G6a, решение от 15.10.2026). */
    public BgSaftDocumentCodes getDocumentCodes() {
      return documents;
    }

    public SaftInvoiceSection getSales() {
      return sales;
    }

    public SaftInvoiceSection getPurchases() {
      return purchases;
    }

    public List<BgDeclarationFileFinding> getFindings() {
      return findings;
    }
  }

  // ─────────────── сглобяването ───────────────

  /**
   * @param source фактурите на периода и статиите с ДДС без фактура
   * @param chart сметкопланът на дружеството
   * @param accountMappings {@code BgSafAccountMapping} по код на сметката
   * @param partners партньорите по id — на таблиците и на фактурите
   * @param taxMappings {@code BgSafTaxMapping} по код на данъка
   * @param cellCodes {@code BgSafVatCellCode}: ключ ({@link BgSaftVatCell#key}) → TAX-IMP код
   * @param nomenclature TAX-IMP ({@link BgNraNomenclatures#taxCodes()})
   * @param invoiceTypes Nom_Invoice_Types ({@link BgNraNomenclatures#invoiceTypes()})
   * @param own подаващият — „10“ + ЕИК, името и адресът от Header: протоколите без доставка
   */
  public static Result assemble(
      Source source,
      Collection<BgSaftGlAccountsAssembler.AccountRow> chart,
      Map<String, BgSaftGlAccountsAssembler.MappingRow> accountMappings,
      Map<Long, PartyRow> partners,
      Map<String, BgSaftTaxTableAssembler.MappingRow> taxMappings,
      Map<String, String> cellCodes,
      Map<String, BgNraNomenclatures.TaxCode> nomenclature,
      Map<String, String> invoiceTypes,
      SaftParty own) {
    Map<Long, Resolution> resolutions =
        BgSaftGlAccountsAssembler.resolutions(chart, accountMappings);
    Context c =
        new Context(resolutions, partners, taxMappings, cellCodes, nomenclature, invoiceTypes);
    c.cashAccounting = source.cashAccounting;

    Map<Long, List<LineRow>> lines = new HashMap<>();
    for (LineRow line : source.lines) {
      lines.computeIfAbsent(line.invoiceId, k -> new ArrayList<>()).add(line);
    }
    lines.values().forEach(list -> list.sort(LINE_ORDER));
    Map<Long, List<InvoiceTaxRow>> taxes = new HashMap<>();
    for (InvoiceTaxRow tax : source.invoiceTaxes) {
      taxes.computeIfAbsent(tax.invoiceId, k -> new ArrayList<>()).add(tax);
    }

    List<InvoiceRow> invoices = new ArrayList<>(source.invoices);
    invoices.sort(INVOICE_ORDER);
    Section sales = new Section();
    Section purchases = new Section();
    for (InvoiceRow invoice : invoices) {
      Boolean isSales = direction(invoice.operationType);
      c.current = BgSaftRef.of(label(invoice), BgSaftRef.INVOICE, invoice.id);
      if (isSales == null) {
        c.found.operation.add(
            c.ref(label(invoice) + " (operationTypeSelect " + invoice.operationType + ")"));
        continue;
      }
      Section section = isSales ? sales : purchases;
      SaftInvoice built =
          invoice(
              invoice,
              isSales,
              lines.getOrDefault(invoice.id, Collections.emptyList()),
              taxes.getOrDefault(invoice.id, Collections.emptyList()),
              section,
              c);
      if (built != null) {
        section.invoices.add(built);
        c.documents.add(invoice.moveId, built);
      }
    }
    source.reportingPeriod.forEach(
        (id, label) -> c.found.reportingPeriod.add(BgSaftRef.of(label, BgSaftRef.INVOICE, id)));
    // Протоколите и останалите статии с ДДС без фактура — от реда им в дневника (G5b).
    BgSaftProtocolInvoices.Result protocols =
        BgSaftProtocolInvoices.assemble(
            source.diaryDocuments,
            source.outOfScope,
            resolutions,
            partners,
            own,
            cellCodes,
            nomenclature,
            invoiceTypes,
            source.cashAccounting);
    protocols.getSales().forEach(sales::addBuilt);
    protocols.getPurchases().forEach(purchases::addBuilt);
    c.references.addAll(protocols.getReferences());
    c.documents.addAll(protocols.getDocumentCodes());

    List<BgDeclarationFileFinding> findings = c.found.toFindings();
    findings.addAll(protocols.getFindings());
    findings.addAll(Findings.empty(sales.empty(), purchases.empty()));
    return new Result(
        sales.toSection(), purchases.toSection(), findings, c.references, c.documents);
  }

  /**
   * Номерът на документа — правилото на дневника ({@code
   * BgVatDocumentCollector.enrichFromInvoice}): номерът на доставчика ({@code supplierInvoiceNb}),
   * а без него нашият ({@code invoiceId}).
   */
  static String documentNumber(String supplierInvoiceNb, String invoiceId) {
    return supplierInvoiceNb == null || supplierInvoiceNb.trim().isEmpty()
        ? invoiceId
        : supplierInvoiceNb;
  }

  /**
   * Влиза ли фактурата в дневника за покупки — същото като колектора: покупка или КИ от доставчик,
   * чийто поне един ред пълни колона по кредитния си тип (нулева сума колоната не пълни).
   */
  static boolean inPurchaseDiary(int operationType, List<LineRow> lines) {
    if (operationType != InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE
        && operationType != InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND) {
      return false;
    }
    for (LineRow line : lines) {
      BigDecimal base = nonNull(line.exTax);
      BigDecimal vat = nonNull(line.inTax).subtract(base);
      BgVatPurchaseTarget target = BgVatPurchaseCreditTypeMapping.forCreditType(line.creditType);
      BigDecimal baseColumn = target.isVatFoldedIntoBase() ? base.add(vat) : base;
      if ((target.getBaseColumn() != null && baseColumn.signum() != 0)
          || (target.getVatColumn() != null && vat.signum() != 0)) {
        return true;
      }
    }
    return false;
  }

  /** S.I.18 — правилото на GL.9 (G3): номерът на статията, а без него id-то ѝ. */
  static String transactionReference(String reference, Long moveId) {
    String value = trim(reference);
    return value == null ? String.valueOf(moveId) : value;
  }

  // ─────────────── една фактура ───────────────

  /** Делът на един ред от данъка на фактурата по една ставка. */
  private static final class Share {
    private final LineTax tax;
    private final BigDecimal raw;
    private final int order;
    private BigDecimal amount;

    private Share(LineTax tax, BigDecimal raw, int order) {
      this.tax = tax;
      this.raw = raw;
      this.order = order;
    }
  }

  /** Един TaxInformation на реда и делът му; без дял — ред без данък (сума 0). */
  private static final class Coded {
    private final SaftTaxInformation information;
    private final Share share;

    private Coded(SaftTaxInformation information, Share share) {
      this.information = information;
      this.share = share;
    }
  }

  private static SaftInvoice invoice(
      InvoiceRow invoice,
      boolean sales,
      List<LineRow> allLines,
      List<InvoiceTaxRow> invoiceTaxes,
      Section section,
      Context c) {
    String label = label(invoice);
    c.current = BgSaftRef.of(label, BgSaftRef.INVOICE, invoice.id);
    boolean reverseCharged = invoiceTaxes.stream().anyMatch(tax -> tax.reverseCharged);
    if (reverseCharged
        && invoice.diaryVerdict != null
        && invoice.diaryVerdict.coveredOutsideDiaries()) {
      c.found.byProtocol.add(c.ref(label));
      return null;
    }
    Set<String> protocolInvoice = new LinkedHashSet<>();
    Set<String> unresolvedType = new LinkedHashSet<>();
    Set<String> napPending = new LinkedHashSet<>();
    boolean blocked = false;

    PartyRow partner = invoice.partnerId == null ? null : c.partners.get(invoice.partnerId);
    if (partner == null) {
      c.found.partner.add(c.ref(label));
      blocked = true;
    }
    LocalDate date =
        sales
            ? firstNonNull(invoice.invoiceDate, invoice.originDate)
            : firstNonNull(invoice.originDate, invoice.invoiceDate);
    String type = trim(invoice.documentType);
    // Получен протокол по чл. 151в ал. 3/7 — вид 91/93 в PurchaseInvoices (отговорът на НАП).
    boolean receivedCashProtocol = !sales && BgSaftCashAccounting.isReceivedProtocolType(type);
    if (type == null) {
      c.found.typeMissing.add(c.ref(label));
      blocked = true;
    } else if (!c.invoiceTypes.containsKey(type)) {
      c.found.typeInvalid.add(c.ref(label + " („" + type + "“)"));
      blocked = true;
    } else if ((sales ? NOT_IN_SALES_DIARY : NOT_IN_PURCHASE_DIARY).contains(type)) {
      c.found.typeDiary.add(c.ref(label + " („" + type + "“)"));
      blocked = true;
    } else if (PROTOCOL_TYPES.contains(type) && !receivedCashProtocol) {
      protocolInvoice.add("вид " + type + " — " + c.invoiceTypes.get(type));
    } else if (BgSaftCashAccounting.isInvoiceType(type) || receivedCashProtocol) {
      // Касова отчетност — издадените се подават с кода на ставката, получените — по кредита,
      // освен когато самото дружество е по режима (отговорът на НАП по имейл от 19.10.2026).
      String refusal = sales ? null : c.cashAccounting.receivedRefusal(date);
      if (refusal != null) {
        napPending.add("вид " + type + " — " + refusal);
      }
    } else if (!G5A_TYPES.contains(type)) {
      unresolvedType.add("вид " + type + " — " + c.invoiceTypes.get(type));
    }
    // Складиране до поискване (ЗДДС чл. 15а): VIES го оставя за ръчно попълване
    // (VIES_CALL_OFF_STOCK_MANUAL), а кодовете му 100230–100233 нямат маршрут. Без проверката
    // ВОД-ът излизаше като 100215.
    if (BgVatValidator.isCallOffStockSupplyType(invoice.supplyType)) {
      c.found.callOffStock.add(c.ref(label + " (к.8а " + invoice.supplyType.trim() + ")"));
      blocked = true;
    }
    // Получената фактура по чл. 163а: данъците по Прил. № 2 → 100330 без данък (отговорът на НАП).
    boolean art163aReceived =
        reverseCharged
            && !sales
            && invoice.diaryVerdict != null
            && invoice.diaryVerdict.art163aInPurchaseDiary();
    if (reverseCharged && !art163aReceived) {
      protocolInvoice.add(
          invoice.diaryVerdict == null ? "обратно начисляване" : invoice.diaryVerdict.reason());
    }

    String number = documentNumber(invoice.supplierInvoiceNb, invoice.invoiceId);
    if (trim(number) == null) {
      c.found.noNumber.add(c.ref(label));
      blocked = true;
    }
    String headerAccount = c.nraCode(invoice.partnerAccountId);
    if (headerAccount == null) {
      c.found.accountUnmapped.add(c.ref(label + " S.I.4"));
      blocked = true;
    }

    // Редовете — без заглавните; кодът на всеки данък през рутера.
    List<LineRow> lines = new ArrayList<>();
    for (LineRow line : allLines) {
      if (line.type != InvoiceLineRepository.TYPE_TITLE) {
        lines.add(line);
      }
    }
    boolean inPurchaseDiary = inPurchaseDiary(invoice.operationType, allLines);
    boolean eu =
        partner != null
            && BgSaftPartiesAssembler.identify(partner)
                .getPartyId()
                .startsWith(BgSaftPartyId.EU_VAT);
    Map<LineRow, List<Coded>> coded = new HashMap<>();
    Map<Long, List<Share>> shares = new LinkedHashMap<>();
    int order = 0;
    for (int i = 0; i < lines.size(); i++) {
      LineRow line = lines.get(i);
      String where = label + "/" + (i + 1);
      if (c.nraCode(line.accountId) == null) {
        c.found.accountUnmapped.add(c.ref(where + " S.I.30"));
        blocked = true;
      }
      List<Coded> lineCodes = new ArrayList<>();
      if (line.taxes.isEmpty()) {
        SaftTaxInformation information =
            c.code(BgSaftInvoiceTaxRouter.untaxed(inPurchaseDiary), "без данък", where);
        if (information == null) {
          blocked = true;
        } else {
          lineCodes.add(new Coded(information, null));
        }
        if (!sales && partner != null && BgSaftPartiesAssembler.hasVatNumber(partner)) {
          c.found.untaxedRegistered.add(c.ref(where));
        }
      }
      for (LineTax tax : line.taxes) {
        String taxCode = trim(tax.code);
        if (art163aReceived && BgSaftInvoiceTaxRouter.ART163A_RECEIVED_TAXES.contains(taxCode)) {
          SaftTaxInformation information =
              c.code(BgSaftInvoiceTaxRouter.art163aReceivedInvoice(), taxCode, where);
          if (information == null) {
            blocked = true;
          } else {
            lineCodes.add(new Coded(information, null));
          }
          continue;
        }
        BgSaftInvoiceTaxRouter.Route route =
            receivedCashProtocol && BgSaftInvoiceTaxRouter.CASH_PROTOCOL_TAX.equals(taxCode)
                ? BgSaftInvoiceTaxRouter.byCredit(line.creditType)
                : BgSaftInvoiceTaxRouter.route(
                    sales,
                    taxCode,
                    line.creditType,
                    taxCode == null ? null : c.taxMappings.get(taxCode));
        if (BgSaftInvoiceTaxRouter.SALES_BY_DIARY_COLUMN.contains(taxCode) && eu) {
          c.found.zeroRateEu.add(c.ref(where));
        }
        if (route.getKind() == BgSaftInvoiceTaxRouter.Kind.PROTOCOL) {
          protocolInvoice.add(taxCode + " — " + route.getReason());
          continue;
        }
        if (route.getKind() == BgSaftInvoiceTaxRouter.Kind.NAP_PENDING) {
          napPending.add(taxCode + " — " + route.getReason());
          continue;
        }
        if (route.isCreditDefaulted()) {
          c.found.creditDefaulted.add(c.ref(where));
        }
        SaftTaxInformation information = c.code(route, taxCode, where);
        if (information == null) {
          blocked = true;
          continue;
        }
        if (tax.rate != null) {
          information.taxPercentage(percentage(tax.rate));
        }
        BigDecimal raw = nonNull(line.exTax).multiply(nonNull(tax.rate)).divide(HUNDRED);
        Share share = new Share(tax, raw, order++);
        shares.computeIfAbsent(tax.taxLineId, k -> new ArrayList<>()).add(share);
        lineCodes.add(new Coded(information, share));
      }
      coded.put(line, lineCodes);
    }

    if (!napPending.isEmpty()) {
      c.found.napPending.add(c.ref(label + " — " + String.join("; ", napPending)));
      return null;
    }
    if (!unresolvedType.isEmpty()) {
      c.found.typeUnresolved.add(c.ref(label + " — " + String.join("; ", unresolvedType)));
      return null;
    }
    if (!protocolInvoice.isEmpty()) {
      c.found.protocolInvoice.add(c.ref(label + " — " + String.join("; ", protocolInvoice)));
      return null;
    }
    if (blocked) {
      return null;
    }

    // Данъкът на реда: Σ по ставка = InvoiceLineTax.companyTaxTotal.
    for (Map.Entry<Long, List<Share>> entry : shares.entrySet()) {
      BigDecimal target = target(entry.getKey(), entry.getValue(), invoiceTaxes, label, c);
      allocate(entry.getValue(), target, label, c);
    }

    boolean refund =
        invoice.operationType == InvoiceRepository.OPERATION_TYPE_CLIENT_REFUND
            || invoice.operationType == InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND;
    if (trim(invoice.moveReference) == null) {
      c.found.noReference.add(c.ref(label));
    }
    SaftParty party = BgSaftPartiesAssembler.describe(partner);
    c.references.add(
        new BgSaftPartiesAssembler.Reference(
            invoice.partnerId,
            sales ? BgSaftPartiesAssembler.Side.CUSTOMER : BgSaftPartiesAssembler.Side.SUPPLIER,
            invoice.partnerAccountId,
            label));

    SaftInvoice result =
        new SaftInvoice()
            .invoiceNo(c.fit(number, MIDDLE2, label + " InvoiceNo"))
            .partyId(party.getPartyId())
            .partyName(party.getName())
            .billingAddress(party.getAddresses().isEmpty() ? null : party.getAddresses().get(0))
            .accountId(headerAccount)
            .invoiceDate(date)
            .invoiceType(type)
            .selfBillingIndicator(SELF_BILLING_NO)
            .transactionId(
                c.fit(
                    transactionReference(invoice.moveReference, invoice.moveId),
                    MIDDLE2,
                    label + " TransactionID"));
    if (invoice.periodFrom != null) {
      result.period(invoice.periodFrom.getMonthValue()).periodYear(invoice.periodFrom.getYear());
    }

    Map<String, SaftTaxInformation> totals = new LinkedHashMap<>();
    BigDecimal net = BigDecimal.ZERO.setScale(SCALE);
    BigDecimal taxes = BigDecimal.ZERO.setScale(SCALE);
    for (int i = 0; i < lines.size(); i++) {
      LineRow line = lines.get(i);
      String where = label + "/" + (i + 1);
      BigDecimal amount = c.round(nonNull(line.exTax), where + " InvoiceLineAmount");
      BigDecimal signed = refund ? amount.negate() : amount;
      boolean debit = signed.signum() >= 0;
      BigDecimal present = signed.abs();
      if (debit) {
        section.debit = section.debit.add(present);
      } else {
        section.credit = section.credit.add(present);
      }
      net = net.add(signed);
      // Данъкът е в посоката на реда: положителен при D и при C с положителна сума (КИ, отстъпка).
      BigDecimal direction = BigDecimal.valueOf((refund ? -1 : 1) * (debit ? 1 : -1));

      String lineAccount = c.nraCode(line.accountId);
      BigDecimal quantity = c.quantity(nonNull(line.qty).abs(), where);
      SaftInvoiceLine saftLine =
          new SaftInvoiceLine()
              .lineNumber(String.valueOf(i + 1))
              .accountId(lineAccount)
              .productCode(productCode(line, sales, lineAccount, where, c))
              .productDescription(
                  c.fit(trim(line.productName), LONG, where + " ProductDescription"))
              .quantity(quantity)
              .unitPrice(unitPrice(present, quantity, where, c))
              .taxPointDate(date)
              .description(description(line, where, c))
              .invoiceLineAmount(SaftAmount.eur(present))
              .debitCreditIndicator(debit ? DEBIT : CREDIT);
      for (Coded code : coded.get(line)) {
        BigDecimal lineTax =
            code.share == null ? BigDecimal.ZERO.setScale(SCALE) : code.share.amount;
        BigDecimal taxPresent = lineTax.multiply(direction);
        SaftTaxInformation information = code.information.taxAmount(SaftAmount.eur(taxPresent));
        saftLine.addTaxInformation(information);
        BigDecimal taxSigned = debit ? taxPresent : taxPresent.negate();
        taxes = taxes.add(taxSigned);
        SaftTaxInformation total =
            totals.computeIfAbsent(
                information.getTaxType() + "|" + information.getTaxCode(),
                k ->
                    new SaftTaxInformation()
                        .taxType(information.getTaxType())
                        .taxCode(information.getTaxCode())
                        .taxBase(BigDecimal.ZERO.setScale(SCALE))
                        .taxAmount(SaftAmount.eur(BigDecimal.ZERO.setScale(SCALE))));
        total.taxBase(total.getTaxBase().add(signed));
        total.taxAmount(SaftAmount.eur(total.getTaxAmount().getAmount().add(taxSigned)));
      }
      result.addLine(saftLine);
    }
    totals.values().forEach(result::addTaxInformationTotal);
    result.netTotal(net).grossTotal(net.add(taxes));
    return result;
  }

  /** Данъкът на фактурата по тази ставка — сборът от редовете на {@code InvoiceLineTax}. */
  private static BigDecimal target(
      Long taxLineId,
      List<Share> shares,
      List<InvoiceTaxRow> invoiceTaxes,
      String label,
      Context c) {
    BigDecimal total = null;
    for (InvoiceTaxRow row : invoiceTaxes) {
      if (!row.reverseCharged && taxLineId != null && taxLineId.equals(row.taxLineId)) {
        total = (total == null ? BigDecimal.ZERO : total).add(nonNull(row.companyTaxTotal));
      }
    }
    if (total == null) {
      BigDecimal raw = BigDecimal.ZERO;
      for (Share share : shares) {
        raw = raw.add(share.raw);
      }
      c.found.noInvoiceTax.add(c.ref(label + " / " + shares.get(0).tax.code));
      return raw.setScale(SCALE, RoundingMode.HALF_UP);
    }
    return c.round(total, label + " InvoiceLineTax");
  }

  /**
   * Най-големият остатък: всеки ред получава данъка си, отрязан към нулата до стотинка; разликата
   * до целта се раздава по стотинка — на най-големия остатък, а при равен остатък по реда на
   * документа. Повече от стотинка на ред значи разминаване в данните — находка.
   */
  private static void allocate(List<Share> shares, BigDecimal target, String label, Context c) {
    BigDecimal sum = BigDecimal.ZERO.setScale(SCALE);
    for (Share share : shares) {
      share.amount = share.raw.setScale(SCALE, RoundingMode.DOWN);
      sum = sum.add(share.amount);
    }
    BigDecimal diff = target.subtract(sum);
    int cents = diff.movePointRight(SCALE).intValueExact();
    if (cents == 0) {
      return;
    }
    List<Share> ordered = new ArrayList<>(shares);
    Comparator<Share> byRemainder = Comparator.comparing((Share s) -> s.raw.subtract(s.amount));
    ordered.sort(
        (cents > 0 ? byRemainder.reversed() : byRemainder).thenComparing((Share s) -> s.order));
    if (Math.abs(cents) > ordered.size()) {
      c.found.residual.add(
          c.ref(label + " / " + shares.get(0).tax.code + " (" + diff.toPlainString() + ")"));
    }
    BigDecimal step = cents > 0 ? CENT : CENT.negate();
    for (int i = 0; i < Math.abs(cents); i++) {
      Share share = ordered.get(i % ordered.size());
      share.amount = share.amount.add(step);
    }
  }

  // ─────────────── полетата на реда ───────────────

  private static String productCode(
      LineRow line, boolean sales, String lineAccount, String where, Context c) {
    if (line.productId == null) {
      return NO_PRODUCT;
    }
    String code = trim(line.productCode);
    if (code == null) {
      c.found.noProductCode.add(c.ref(where));
      return NO_PRODUCT;
    }
    // Покупка на ДМА, директно разходван артикул или текущ разход — не са запаси (Прил. 3 т. IV.6).
    if (!sales && (lineAccount == null || !lineAccount.startsWith(STOCK_GROUP))) {
      return NO_PRODUCT;
    }
    return c.fit(code, MIDDLE2, where + " ProductCode");
  }

  private static BigDecimal unitPrice(
      BigDecimal amount, BigDecimal quantity, String where, Context c) {
    if (quantity.signum() == 0) {
      c.found.noQuantity.add(c.ref(where));
      return amount;
    }
    return amount.divide(quantity, SCALE, RoundingMode.HALF_UP);
  }

  private static String description(LineRow line, String where, Context c) {
    String text = trim(line.productName);
    if (text == null) {
      text = plain(line.description);
    }
    if (text == null) {
      c.found.noDescription.add(c.ref(where));
      text = "Ред " + where.substring(where.lastIndexOf('/') + 1);
    }
    return c.fit(text, LONG, where + " Description");
  }

  /** Описанието на реда може да е HTML (голямо текстово поле) — само текстът. */
  private static String plain(String html) {
    if (html == null) {
      return null;
    }
    return trim(html.replaceAll("<[^>]*>", " ").replace("&nbsp;", " ").replaceAll("\\s+", " "));
  }

  /** TaxPercentage: ставката в проценти, без нулите отзад ({@code 20.0000000000} → 20). */
  private static BigDecimal percentage(BigDecimal rate) {
    BigDecimal v = rate.stripTrailingZeros();
    return v.scale() < 0 ? v.setScale(0) : v;
  }

  // ─────────────── секцията ───────────────

  private static final class Section {
    private final List<SaftInvoice> invoices = new ArrayList<>();
    private BigDecimal debit = BigDecimal.ZERO.setScale(SCALE);
    private BigDecimal credit = BigDecimal.ZERO.setScale(SCALE);

    private boolean empty() {
      return invoices.isEmpty();
    }

    /** Готов документ: TotalDebit/TotalCredit — Σ InvoiceLineAmount по индикатор, без данъка. */
    private void addBuilt(SaftInvoice invoice) {
      invoices.add(invoice);
      for (SaftInvoiceLine line : invoice.getLines()) {
        BigDecimal amount = line.getInvoiceLineAmount().getAmount();
        if (DEBIT.equals(line.getDebitCreditIndicator())) {
          debit = debit.add(amount);
        } else {
          credit = credit.add(amount);
        }
      }
    }

    private SaftInvoiceSection toSection() {
      if (invoices.isEmpty()) {
        return new SaftInvoiceSection()
            .numberOfEntries(0L)
            .totalDebit(BigDecimal.ZERO.setScale(SCALE))
            .totalCredit(BigDecimal.ZERO.setScale(SCALE))
            .invoices(List.of(emptyInvoice()));
      }
      return new SaftInvoiceSection()
          .numberOfEntries((long) invoices.size())
          .totalDebit(debit)
          .totalCredit(credit)
          .invoices(invoices);
    }
  }

  /**
   * Нулевата структура за период без документи — QA IV.2.6 (с. 99–100), дословно, включително
   * CustomerInfo и „Продажба“ и за PurchaseInvoices (QA дава един пример за двете). Валутата е EUR.
   */
  static SaftInvoice emptyInvoice() {
    BigDecimal zero = BigDecimal.ZERO.setScale(SCALE);
    return new SaftInvoice()
        .invoiceNo("1")
        .partyRole(SaftInvoice.PartyRole.CUSTOMER)
        .partyId("0")
        .partyName(" ")
        .billingAddress(new SaftAddress().city("").country(BgSaftTaxTableAssembler.COUNTRY))
        .accountId(EMPTY_ACCOUNT)
        .invoiceDate(EMPTY_DATE)
        .invoiceType("01")
        .selfBillingIndicator(SELF_BILLING_NO)
        .transactionId("1")
        .addLine(
            new SaftInvoiceLine()
                .lineNumber("1")
                .accountId(EMPTY_ACCOUNT)
                .productCode(NO_PRODUCT)
                .productDescription("0")
                .quantity(BigDecimal.ZERO)
                .invoiceUom("0")
                .uomToUomBaseConversionFactor(BigDecimal.ONE)
                .unitPrice(zero)
                .taxPointDate(EMPTY_DATE)
                .description(EMPTY_DESCRIPTION)
                .invoiceLineAmount(SaftAmount.eur(zero))
                .debitCreditIndicator(DEBIT)
                .addTaxInformation(
                    new SaftTaxInformation()
                        .taxType(BgSaftTaxTableAssembler.NOT_APPLICABLE_TYPE)
                        .taxCode(BgSaftTaxTableAssembler.NOT_APPLICABLE_CODE)
                        .taxAmount(SaftAmount.eur(zero))));
  }

  // ─────────────── контекстът на едно сглобяване ───────────────

  private static final class Context {
    private final Map<Long, Resolution> accounts;
    private final Map<Long, PartyRow> partners;
    private final Map<String, BgSaftTaxTableAssembler.MappingRow> taxMappings;
    private final Map<String, String> cellCodes;
    private final Map<String, BgNraNomenclatures.TaxCode> nomenclature;
    private final Map<String, String> invoiceTypes;
    private final Findings found = new Findings();
    private final List<BgSaftPartiesAssembler.Reference> references = new ArrayList<>();
    private final BgSaftDocumentCodes documents = new BgSaftDocumentCodes();
    private BgSaftCashAccounting cashAccounting = BgSaftCashAccounting.NONE;

    /** Документът, който се сглобява — към него сочат находките. */
    private BgSaftRef current;

    private Context(
        Map<Long, Resolution> accounts,
        Map<Long, PartyRow> partners,
        Map<String, BgSaftTaxTableAssembler.MappingRow> taxMappings,
        Map<String, String> cellCodes,
        Map<String, BgNraNomenclatures.TaxCode> nomenclature,
        Map<String, String> invoiceTypes) {
      this.accounts = accounts;
      this.partners = partners;
      this.taxMappings = taxMappings;
      this.cellCodes = cellCodes;
      this.nomenclature = nomenclature;
      this.invoiceTypes = invoiceTypes;
    }

    /** Препратка към текущия документ с етикета {@code text}. */
    private BgSaftRef ref(String text) {
      return current == null ? BgSaftRef.text(text) : current.withLabel(text);
    }

    /** Кодът по НАП на наша сметка; {@code null} — без код или изключена. */
    private String nraCode(Long accountId) {
      Resolution resolution = accountId == null ? null : accounts.get(accountId);
      if (resolution == null || resolution.isExcluded() || resolution.getNraCode().isEmpty()) {
        return null;
      }
      return resolution.getNraCode();
    }

    /**
     * TaxInformation без сумата — видът и кодът по решението на рутера; {@code null} и находка,
     * когато код няма. Празен таг не излиза никога.
     */
    private SaftTaxInformation code(BgSaftInvoiceTaxRouter.Route route, String tax, String where) {
      String labelled = where + " " + tax;
      switch (route.getKind()) {
        case NOT_APPLICABLE:
          return new SaftTaxInformation()
              .taxType(BgSaftTaxTableAssembler.NOT_APPLICABLE_TYPE)
              .taxCode(BgSaftTaxTableAssembler.NOT_APPLICABLE_CODE);
        case CODE:
          return known(route.getValue(), labelled);
        case CELL:
          {
            String code = cellCodes.get(route.getValue());
            if (code == null) {
              found.taxUnknown.add(
                  ref(labelled + " (клетка " + route.getValue() + " я няма в BgSafVatCellCode)"));
              return null;
            }
            return known(code, labelled);
          }
        case UNRESOLVED:
          found.taxUnresolved.add(ref(labelled + " (" + route.getReason() + ")"));
          return null;
        case WRONG_DIRECTION:
          found.taxDirection.add(ref(labelled + " (" + route.getReason() + ")"));
          return null;
        default:
          found.taxUnknown.add(ref(labelled + " (" + route.getReason() + ")"));
          return null;
      }
    }

    private SaftTaxInformation known(String code, String labelled) {
      BgNraNomenclatures.TaxCode row = nomenclature.get(code);
      if (row == null) {
        found.taxUnknown.add(ref(labelled + " (кодът " + code + " го няма в TAX-IMP)"));
        return null;
      }
      return new SaftTaxInformation().taxType(row.taxType()).taxCode(code);
    }

    /** 2 знака HALF_UP; трети знак е находка. */
    private BigDecimal round(BigDecimal value, String what) {
      BigDecimal rounded = value.setScale(SCALE, RoundingMode.HALF_UP);
      if (value.compareTo(rounded) != 0) {
        found.rounded.add(ref(what));
      }
      return rounded;
    }

    /** SAFquantityType: до 6 знака. */
    private BigDecimal quantity(BigDecimal value, String where) {
      BigDecimal rounded = value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
      if (value.compareTo(rounded) != 0) {
        found.rounded.add(ref(where + " Quantity"));
      }
      BigDecimal stripped = rounded.stripTrailingZeros();
      return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /** Съкращава до {@code max} знака: по-дългото схемата отказва. {@code null} остава. */
    private String fit(String value, int max, String what) {
      if (value == null || value.codePointCount(0, value.length()) <= max) {
        return value;
      }
      found.truncated.add(ref(what));
      return value.substring(0, value.offsetByCodePoints(0, max));
    }
  }

  /** Находките, събрани по вид — всеки документ или ред по веднъж, в реда на появяване. */
  private static final class Findings {
    private final Set<BgSaftRef> protocolInvoice = new LinkedHashSet<>();
    private final Set<BgSaftRef> byProtocol = new LinkedHashSet<>();
    private final Set<BgSaftRef> typeUnresolved = new LinkedHashSet<>();
    private final Set<BgSaftRef> operation = new LinkedHashSet<>();
    private final Set<BgSaftRef> callOffStock = new LinkedHashSet<>();
    private final Set<BgSaftRef> partner = new LinkedHashSet<>();
    private final Set<BgSaftRef> typeMissing = new LinkedHashSet<>();
    private final Set<BgSaftRef> typeInvalid = new LinkedHashSet<>();
    private final Set<BgSaftRef> typeDiary = new LinkedHashSet<>();
    private final Set<BgSaftRef> noNumber = new LinkedHashSet<>();
    private final Set<BgSaftRef> accountUnmapped = new LinkedHashSet<>();
    private final Set<BgSaftRef> taxUnresolved = new LinkedHashSet<>();
    private final Set<BgSaftRef> taxDirection = new LinkedHashSet<>();
    private final Set<BgSaftRef> taxUnknown = new LinkedHashSet<>();
    private final Set<BgSaftRef> creditDefaulted = new LinkedHashSet<>();
    private final Set<BgSaftRef> untaxedRegistered = new LinkedHashSet<>();
    private final Set<BgSaftRef> zeroRateEu = new LinkedHashSet<>();
    private final Set<BgSaftRef> napPending = new LinkedHashSet<>();
    private final Set<BgSaftRef> noInvoiceTax = new LinkedHashSet<>();
    private final Set<BgSaftRef> residual = new LinkedHashSet<>();
    private final Set<BgSaftRef> noReference = new LinkedHashSet<>();
    private final Set<BgSaftRef> noProductCode = new LinkedHashSet<>();
    private final Set<BgSaftRef> noDescription = new LinkedHashSet<>();
    private final Set<BgSaftRef> noQuantity = new LinkedHashSet<>();
    private final Set<BgSaftRef> rounded = new LinkedHashSet<>();
    private final Set<BgSaftRef> truncated = new LinkedHashSet<>();
    private final Set<BgSaftRef> reportingPeriod = new LinkedHashSet<>();

    private List<BgDeclarationFileFinding> toFindings() {
      List<BgDeclarationFileFinding> findings = new ArrayList<>();
      error(
          findings,
          "SAFT_INV_PROTOCOL_INVOICE",
          "Фактура, която е протокол или иска протокол (обратно начисляване, вид 09 или 91–94,"
              + " протоколен данък) — съставете протокола с „Състави протокол по чл. 117“ на"
              + " фактурата или като ръчна статия с ДДС операция; така го подават и дневниците"
              + " (Блок Б). Документът не е подаден: ",
          protocolInvoice);
      error(
          findings,
          "SAFT_INV_NAP_PENDING",
          "Документ, чийто код в SAF-T чака НАП — не е подаден: ",
          napPending);
      error(
          findings,
          "SAFT_INV_TYPE_UNRESOLVED",
          "Вид на документа без маршрут в SAF-T — нерешен код, не празен таг (QA IV.2.3). Не е"
              + " подаден: ",
          typeUnresolved);
      error(
          findings,
          "SAFT_INV_CALL_OFF_STOCK",
          "Складиране на стоки до поискване (к.8а 41/43/46/48, ЗДДС чл. 15а) — VIES го оставя"
              + " за ръчно попълване (VIES_CALL_OFF_STOCK_MANUAL), а кодовете 100230–100233 нямат"
              + " маршрут; документът не е подаден: ",
          callOffStock);
      error(
          findings,
          "SAFT_INV_OPERATION",
          "Фактура, която не е нито продажба, нито покупка — посоката не се знае: ",
          operation);
      error(
          findings,
          "SAFT_INV_PARTNER",
          "Фактура без прочетен контрагент — S.I.2/S.I.3 са задължителни: ",
          partner);
      error(
          findings,
          "SAFT_INV_TYPE_MISSING",
          "Фактура без вид на документа по НАП — S.I.9 InvoiceType е задължителен: ",
          typeMissing);
      error(
          findings,
          "SAFT_INV_TYPE_INVALID",
          "Вид на документа извън Nom_Invoice_Types — НАП го отказва: ",
          typeInvalid);
      error(
          findings,
          "SAFT_INV_TYPE_DIARY",
          "Вид на документа, който съответният дневник не може да съдържа (ППЗДДС Прил. № 12): ",
          typeDiary);
      error(findings, "SAFT_INV_NUMBER", "Фактура без номер — S.I.1 е задължителен: ", noNumber);
      error(
          findings,
          "SAFT_INV_ACCOUNT_UNMAPPED",
          "Сметка без код по НАП или изключена — AccountID (S.I.4/S.I.30) не може да се подаде: ",
          accountUnmapped);
      error(
          findings,
          "SAFT_INV_TAX_UNRESOLVED",
          "Данъчният код на реда не може да се определи — празен TaxType/TaxCode НАП отхвърля (QA"
              + " IV.2.3), а 000/000000 е само за ред без ДДС: ",
          taxUnresolved);
      error(
          findings,
          "SAFT_INV_TAX_DIRECTION",
          "Данък от другата страна на сделката — кодът би бил грешен: ",
          taxDirection);
      error(
          findings,
          "SAFT_INV_TAX_UNKNOWN",
          "Данък без код по TAX-IMP — мапингът, рутерът или таблицата на клетките не го познават: ",
          taxUnknown);
      warning(
          findings,
          "SAFT_INV_REVERSE_CHARGE_BY_PROTOCOL",
          "Фактура с обратно начисляване, отразена чрез протокола си по чл. 117 ЗДДС — не е в"
              + " дневниците (номерът и датата ѝ са в протокола, ЗДДС чл. 117 ал. 2 т. 9), затова"
              + " не е и в PurchaseInvoices (Прил. 3 т. IV.7: документите от дневника за покупки);"
              + " протоколът е в двете секции. Алтернативата от т. IV.7 — фактурата с 000/000000"
              + " и артикулите си — е отделна задача: ",
          byProtocol);
      warning(
          findings,
          "SAFT_INV_CREDIT_DEFAULTED",
          "Ред на покупка без вид данъчен кредит — приет е пълен кредит (100331), както в"
              + " дневника: ",
          creditDefaulted);
      warning(
          findings,
          "SAFT_INV_UNTAXED_REGISTERED",
          "Ред без данък във фактура от доставчик с ДДС номер — подава се като „без данък“"
              + " (100330, QA с. 81); проверете дали данъкът не е пропуснат: ",
          untaxedRegistered);
      warning(
          findings,
          "SAFT_INV_ZERO_RATE_EU",
          "BG_ZRS при контрагент с ДДС номер от ЕС — ВОД (глава пета) минава само през BG_VOD: ",
          zeroRateEu);
      warning(
          findings,
          "SAFT_INV_NO_INVOICE_TAX",
          "Данък на ред без ред в InvoiceLineTax — данъкът е сборът от редовете: ",
          noInvoiceTax);
      warning(
          findings,
          "SAFT_INV_TAX_RESIDUAL",
          "Данъкът на фактурата се разминава с редовете ѝ с повече от стотинка на ред: ",
          residual);
      warning(
          findings,
          "SAFT_INV_NO_REFERENCE",
          "Статия без номер — TransactionID (S.I.18) е id-то ѝ, както в дневника: ",
          noReference);
      warning(
          findings,
          "SAFT_INV_NO_PRODUCT_CODE",
          "Продукт без код — ProductCode (S.I.36) е „0“: ",
          noProductCode);
      warning(
          findings,
          "SAFT_INV_NO_DESCRIPTION",
          "Ред без описание — Description (S.I.45) е номерът на реда: ",
          noDescription);
      warning(
          findings,
          "SAFT_INV_NO_QUANTITY",
          "Ред без количество — UnitPrice (S.I.42) е сумата на реда: ",
          noQuantity);
      warning(
          findings,
          "SAFT_INV_ROUNDED",
          "Сума с повече знаци, отколкото схемата допуска — закръглена HALF_UP: ",
          rounded);
      warning(
          findings,
          "SAFT_INV_TRUNCATED",
          "Текст по-дълъг, отколкото схемата допуска — съкратен: ",
          truncated);
      warning(
          findings,
          "SAFT_INV_REPORTING_PERIOD",
          "Фактура с „Период на отразяване по ЗДДС“ в друг месец — в SAF-T е по датата на"
              + " статията, както GeneralLedgerEntries, а в дневниците — по периода на отразяване;"
              + " SalesInvoices/PurchaseInvoices и дневниците на месеца се разминават (до отговора"
              + " на НАП): ",
          reportingPeriod);
      return findings;
    }

    /** Нулевата структура — след протоколите, които също пълнят секциите. */
    private static List<BgDeclarationFileFinding> empty(
        boolean salesEmpty, boolean purchasesEmpty) {
      List<BgDeclarationFileFinding> findings = new ArrayList<>();
      if (salesEmpty) {
        findings.add(
            BgDeclarationFileFinding.warning(
                "SAFT_INV_SALES_EMPTY",
                "Няма подаден документ за продажба — SalesInvoices е нулевата структура по QA"
                    + " IV.2.6 (с. 99–100)."));
      }
      if (purchasesEmpty) {
        findings.add(
            BgDeclarationFileFinding.warning(
                "SAFT_INV_PURCHASES_EMPTY",
                "Няма подаден документ за покупка — PurchaseInvoices е нулевата структура по QA"
                    + " IV.2.6 (с. 99–100)."));
      }
      return findings;
    }

    private static void error(
        List<BgDeclarationFileFinding> findings, String code, String prefix, Set<BgSaftRef> items) {
      BgSaftRef.error(findings, code, prefix, items);
    }

    private static void warning(
        List<BgDeclarationFileFinding> findings, String code, String prefix, Set<BgSaftRef> items) {
      BgSaftRef.warning(findings, code, prefix, items);
    }
  }

  // ─────────────── помощници ───────────────

  private static final Comparator<LineRow> LINE_ORDER =
      Comparator.comparingInt((LineRow line) -> line.sequence)
          .thenComparing(line -> line.id, Comparator.nullsLast(Comparator.naturalOrder()));

  private static final Comparator<InvoiceRow> INVOICE_ORDER =
      Comparator.comparing(
              (InvoiceRow invoice) -> firstNonNull(invoice.invoiceDate, invoice.originDate),
              Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(
              invoice -> documentNumber(invoice.supplierInvoiceNb, invoice.invoiceId),
              Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(invoice -> invoice.id, Comparator.nullsLast(Comparator.naturalOrder()));

  /** {@code true} продажба, {@code false} покупка, {@code null} — нито едното. */
  private static Boolean direction(int operationType) {
    switch (operationType) {
      case InvoiceRepository.OPERATION_TYPE_CLIENT_SALE:
      case InvoiceRepository.OPERATION_TYPE_CLIENT_REFUND:
        return Boolean.TRUE;
      case InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE:
      case InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND:
        return Boolean.FALSE;
      default:
        return null;
    }
  }

  private static String label(InvoiceRow invoice) {
    String number = trim(documentNumber(invoice.supplierInvoiceNb, invoice.invoiceId));
    return number == null ? "#" + invoice.id : number;
  }

  private static Set<String> set(String... values) {
    return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(values)));
  }

  private static BigDecimal nonNull(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static <T> T firstNonNull(T first, T second) {
    return first != null ? first : second;
  }

  private static String trim(String value) {
    if (value == null) {
      return null;
    }
    String v = value.trim();
    return v.isEmpty() ? null : v;
  }
}

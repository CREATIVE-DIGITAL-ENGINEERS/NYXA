package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.saft.BgSaftGlAccountsAssembler.Resolution;
import com.axelor.apps.bglocalization.service.saft.BgSaftPartiesAssembler.PartyRow;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAmount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoice;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoiceLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftParty;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxInformation;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesColumn;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ПРОТОКОЛИТЕ И ОСТАНАЛИТЕ СТАТИИ С ДДС БЕЗ ФАКТУРА В SalesInvoices/PurchaseInvoices (G5b) — чиста
 * функция върху редовете, които дневниците правят от тях.
 *
 * <p><b>⚠️ ЕДИН ИЗТОЧНИК НА ИСТИНА — РЕДЪТ В ДНЕВНИКА.</b> Прил. 3 т. IV.7: в SalesInvoices са
 * документите от дневника за продажби, „включително протоколите за самоначисляване“, в
 * PurchaseInvoices — тези от дневника за покупки. Затова входът е редът на Блок Б ({@code
 * BgVatMoveDiaryRows}): номерът е 02-05/03-06, датата — 02-06/03-07, видът — 02-04/03-05, а
 * основата и данъкът са колоните. Кодът е клетката + к.8а + ставката ({@link BgSaftDiaryCells}).
 *
 * <p><b>⚠️ ИЗКЛЮЧЕНИЯТА ОТ ПАРИТЕТА — изброени, всяко с норма:</b>
 *
 * <ul>
 *   <li>ЕАД по чл. 167а е в дневника за продажби само с колони 1–8 (ППЗДДС чл. 49а ал. 4 т. 2), но
 *       НЕ е в SalesInvoices (QA с. 60). В PurchaseInvoices е със 100330 (QA с. 60), а основата му
 *       е тази на протокола: в дневника ЕАД няма суми (ал. 5 т. 2).
 *   <li>к.9 на покупките сгъва основа и данък; SAF-T ги подава поотделно ({@link
 *       BgSaftDiaryCells}).
 * </ul>
 *
 * <p><b>⚠️ КОНТРАГЕНТЪТ.</b> Протокол във връзка с доставка носи доставчика — SupplierInfo и в
 * SalesInvoices (QA с. 58; Прил. 3 т. IV.7 „данните за конкретния контрагент“). Протоколът без
 * доставка (чл. 79, 79а) носи подаващия — „10“ + ЕИК, както в GL; той не е в Customers/Suppliers и
 * не се проверява там (книгата, GL.19/GL.28; QA IV.2.4 вини само формата на ID-то).
 *
 * <p><b>⚠️ СМЕТКИТЕ</b> — Прил. 3 т. IV.7, последните два абзаца: при протокол S.I.4 е сметката за
 * начисления ДДС по продажби (4532) / покупки (4531), а S.I.30 — кореспондиращата ѝ; при ЕАД S.I.4
 * е сметката за разчет с митница, а S.I.30 — сметката за ДДС по покупки. Без сметка за митница в
 * статията ЕАД е по аналогия с протокола, с ПРЕДУПРЕЖДЕНИЕ: т. IV.7 я „допуска“, не я изисква.
 * Всички са от статията на S.I.18, тоест от същата транзакция като в GeneralLedgerEntries.
 *
 * <p><b>⚠️ ПРОДУКТИ НЯМА</b> — {@code MoveLine} няма продукт. Редът е един на клетка, с ProductCode
 * „0“; при ВОП и ЕАД това е ПРЕДУПРЕЖДЕНИЕ (QA с. 58–61 иска артикулите).
 */
public final class BgSaftProtocolInvoices {

  /** Протоколите: самоначисляване, внос по чл. 167а и корекциите по чл. 79/79а. */
  static final Set<String> PROTOCOLS =
      Set.of(
          "VOP",
          "SERVICE_EU",
          "SERVICE_NONEU",
          "ART163A",
          "IMPORT_167A",
          "CORRECTION_79",
          "DEDUCTION_79",
          "ANNUAL_79A");

  /**
   * Без доставка — носят подаващия (Прил. 3 т. IV.7): корекциите по чл. 79/79а и годишната корекция
   * по чл. 73 ал. 8.
   */
  static final Set<String> WITHOUT_SUPPLY =
      Set.of("CORRECTION_79", "DEDUCTION_79", "ANNUAL_79A", "PURCHASE_ANNUAL");

  /** Ръчните покупки — не са протоколи, но редът им в дневника съществува. */
  static final Set<String> PURCHASES =
      Set.of(
          "PURCHASE_FULL",
          "PURCHASE_82_NONE",
          "PURCHASE_PARTIAL",
          "PURCHASE_ANNUAL",
          "PURCHASE_TRIANGULAR");

  /** ВОП и вносът — QA с. 58–61 иска артикулите по продукт. */
  static final Set<String> GOODS = Set.of("VOP", "IMPORT_167A");

  /**
   * Видовете, които статия може да носи в SAF-T: фактурните на G5a и 09 „Протокол“. Касовата
   * отчетност (11/12/13, полученият протокол 91/93) — по {@link BgSaftCashAccounting}.
   */
  static final Set<String> TYPES = Set.of("01", "02", "03", "07", "09", "23");

  static final String NO_PRODUCT = "0";
  static final String DEBIT = "D";
  static final String CREDIT = "C";
  private static final int SCALE = 2;
  private static final int MIDDLE2 = 70;
  private static final int LONG = 256;

  private BgSaftProtocolInvoices() {}

  // ─────────────── входът ───────────────

  /** От коя страна на ДДС е редът — по правилото на колектора (4531/4532). */
  public enum VatSide {
    PURCHASE,
    SALES
  }

  /** Ред на статията. */
  public static final class MoveLineRow {
    private final Long accountId;
    private final VatSide vatSide;
    private final BigDecimal debit;
    private final BigDecimal credit;
    private final BigDecimal taxRate;
    private final Long partnerId;
    private final String creditType;

    /**
     * @param taxRate {@code MoveLine.taxRate} — в проценти
     */
    public MoveLineRow(
        Long accountId,
        VatSide vatSide,
        BigDecimal debit,
        BigDecimal credit,
        BigDecimal taxRate,
        Long partnerId,
        String creditType) {
      this.accountId = accountId;
      this.vatSide = vatSide;
      this.debit = debit == null ? BigDecimal.ZERO : debit;
      this.credit = credit == null ? BigDecimal.ZERO : credit;
      this.taxRate = taxRate == null ? BigDecimal.ZERO : taxRate;
      this.partnerId = partnerId;
      this.creditType = creditType;
    }

    private BigDecimal amount() {
      return debit.subtract(credit);
    }
  }

  /** Един ред в дневниците, направен от статия без фактура. */
  public static final class DiaryDocument {
    private final Long moveId;
    private String moveReference;
    private LocalDate periodFrom;
    private String operation;
    private String description;
    private Long partnerId;
    private BigDecimal manualRate;
    private final List<MoveLineRow> lines = new ArrayList<>();

    private String number;
    private LocalDate date;
    private String type;
    private String supplyType;
    private boolean customs;
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private BigDecimal derivedBase;
    private boolean sales;
    private boolean purchases;
    private final Map<BgVatSalesColumn, BigDecimal> salesColumns =
        new EnumMap<>(BgVatSalesColumn.class);
    private final Map<BgVatPurchaseColumn, BigDecimal> purchaseColumns =
        new EnumMap<>(BgVatPurchaseColumn.class);

    public DiaryDocument(Long moveId) {
      this.moveId = moveId;
    }

    /** Статията: номерът ѝ и началото на периода ѝ. */
    public DiaryDocument move(String reference, LocalDate periodFrom) {
      this.moveReference = reference;
      this.periodFrom = periodFrom;
      return this;
    }

    /** {@code Move.bgVatMoveVatOperation}, {@code Move.description}. */
    public DiaryDocument operation(String operation, String description) {
      this.operation = operation;
      this.description = description;
      return this;
    }

    /** {@code Move.partner} и {@code Move.bgVatMoveVatRate}. */
    public DiaryDocument partner(Long partnerId, BigDecimal manualRate) {
      this.partnerId = partnerId;
      this.manualRate = manualRate;
      return this;
    }

    public DiaryDocument line(MoveLineRow line) {
      lines.add(line);
      return this;
    }

    /** Основата, изведена от данъка без нищо, което да я свери ({@code BgVatDocumentDto}). */
    public DiaryDocument derivedBase(BigDecimal value) {
      this.derivedBase = value;
      return this;
    }

    /** Идентификацията на реда в дневника: номер, дата, вид, к.8а; ЕАД — само колони 1–8. */
    public DiaryDocument identity(
        String number, LocalDate date, String type, String supplyType, boolean customs) {
      this.number = number;
      this.date = date;
      this.type = type;
      this.supplyType = supplyType;
      this.customs = customs;
      return this;
    }

    /** Грешка, която колекторът е записал на документа ({@code BgVatDocumentDto.getErrors}). */
    public DiaryDocument error(String error) {
      errors.add(error);
      return this;
    }

    /** Предупреждение на дневника за документа — напр. ред без вид данъчен кредит. */
    public DiaryDocument warning(String warning) {
      warnings.add(warning);
      return this;
    }

    /** Ред в PRODAGBI с колоните си (без тоталите). */
    public DiaryDocument sales(Map<BgVatSalesColumn, BigDecimal> columns) {
      this.sales = true;
      salesColumns.putAll(columns);
      return this;
    }

    /** Ред в POKUPKI с колоните си. */
    public DiaryDocument purchases(Map<BgVatPurchaseColumn, BigDecimal> columns) {
      this.purchases = true;
      purchaseColumns.putAll(columns);
      return this;
    }

    public Long getMoveId() {
      return moveId;
    }

    public Long getPartnerId() {
      return partnerId;
    }

    /** Партньорите по редовете — за общия протокол по чл. 163б ал. 1 т. 2. */
    Set<Long> linePartners() {
      Set<Long> ids = new LinkedHashSet<>();
      for (MoveLineRow line : lines) {
        if (line.partnerId != null) {
          ids.add(line.partnerId);
        }
      }
      return ids;
    }
  }

  // ─────────────── изходът ───────────────

  /** Документите за двете секции и находките. */
  public static final class Result {
    private final List<SaftInvoice> sales;
    private final List<SaftInvoice> purchases;
    private final List<BgDeclarationFileFinding> findings;
    private final List<BgSaftPartiesAssembler.Reference> references;
    private final BgSaftDocumentCodes documents;

    private Result(
        List<SaftInvoice> sales,
        List<SaftInvoice> purchases,
        List<BgDeclarationFileFinding> findings,
        List<BgSaftPartiesAssembler.Reference> references,
        BgSaftDocumentCodes documents) {
      this.sales = sales;
      this.purchases = purchases;
      this.findings = findings;
      this.references = references;
      this.documents = documents;
    }

    /** Кодовете на подадените протоколи по статия — за дневника. */
    BgSaftDocumentCodes getDocumentCodes() {
      return documents;
    }

    /** Контрагентите на подадените протоколи — за членството по референция. */
    List<BgSaftPartiesAssembler.Reference> getReferences() {
      return references;
    }

    public List<SaftInvoice> getSales() {
      return sales;
    }

    public List<SaftInvoice> getPurchases() {
      return purchases;
    }

    public List<BgDeclarationFileFinding> getFindings() {
      return findings;
    }
  }

  // ─────────────── сглобяването ───────────────

  /**
   * @param documents редовете на дневниците от статиите без фактура
   * @param outOfScope статии с ред в дневника, които GL не носи (ignoreInAccountingOk, откриваща)
   * @param accounts AccountID по id на сметката
   * @param partners партньорите по id
   * @param own подаващият — ID „10“ + ЕИК, име и адрес от Header
   * @param cellCodes {@code BgSafVatCellCode}: ключ → TAX-IMP код
   * @param nomenclature TAX-IMP
   * @param invoiceTypes Nom_Invoice_Types
   * @param cashAccounting периодът на дружеството по касова отчетност
   */
  /** Белегът на предупреждението на колектора за празен {@code bgVatCreditType}. */
  static final String CREDIT_DEFAULTED_MARK = "без вид данъчен кредит";

  static Result assemble(
      List<DiaryDocument> documents,
      List<String> outOfScope,
      Map<Long, Resolution> accounts,
      Map<Long, PartyRow> partners,
      SaftParty own,
      Map<String, String> cellCodes,
      Map<String, BgNraNomenclatures.TaxCode> nomenclature,
      Map<String, String> invoiceTypes,
      BgSaftCashAccounting cashAccounting) {
    Context c = new Context(accounts, partners, own, cellCodes, nomenclature, invoiceTypes);
    c.cashAccounting = cashAccounting == null ? BgSaftCashAccounting.NONE : cashAccounting;
    outOfScope.forEach(label -> c.found.outOfScope.add(BgSaftRef.text(label)));

    List<DiaryDocument> sorted = new ArrayList<>(documents);
    sorted.sort(ORDER);
    // Основата на протокола — за ЕАД, който в дневника няма суми.
    Map<Long, BigDecimal> importBase = new HashMap<>();
    for (DiaryDocument doc : sorted) {
      if (!doc.customs) {
        BigDecimal k11 = doc.salesColumns.get(BgVatSalesColumn.K11_BASE_20);
        if (k11 != null) {
          importBase.put(doc.moveId, k11);
        }
      }
    }
    List<SaftInvoice> sales = new ArrayList<>();
    List<SaftInvoice> purchases = new ArrayList<>();
    for (DiaryDocument doc : sorted) {
      document(doc, importBase.get(doc.moveId), sales, purchases, c);
    }
    return new Result(sales, purchases, c.found.toFindings(), c.references, c.documents);
  }

  private static void document(
      DiaryDocument doc,
      BigDecimal importBase,
      List<SaftInvoice> sales,
      List<SaftInvoice> purchases,
      Context c) {
    String label = label(doc);
    c.current = BgSaftRef.move(doc.moveId, label);
    String op = trim(doc.operation);
    boolean protocol = op != null && PROTOCOLS.contains(op);
    boolean anyCreditType = false;
    for (MoveLineRow line : doc.lines) {
      anyCreditType |= trim(line.creditType) != null;
    }
    if (op == null && !anyCreditType) {
      c.found.noBase.add(c.ref(label));
      return;
    }
    if (op != null && !protocol && !PURCHASES.contains(op)) {
      c.found.operation.add(c.ref(label + " (" + op + ")"));
      return;
    }

    boolean blocked = false;
    for (String error : doc.errors) {
      c.found.diary.add(c.ref(label + " — " + error));
      blocked = true;
    }
    // Празен вид данъчен кредит — дневникът приема пълен кредит и предупреждава; SAF-T казва
    // същото, иначе протоколът излизаше тихо със 100331 (G6a).
    for (String warning : doc.warnings) {
      if (warning.contains(CREDIT_DEFAULTED_MARK)) {
        c.found.creditDefaulted.add(c.ref(label + " — " + warning));
      }
    }
    if (doc.derivedBase != null) {
      c.found.baseDerived.add(c.ref(label + " — " + doc.derivedBase.toPlainString()));
    }
    String type = trim(doc.type);
    if (type == null) {
      c.found.typeMissing.add(c.ref(label));
      blocked = true;
    } else if (!c.invoiceTypes.containsKey(type)) {
      c.found.typeInvalid.add(c.ref(label + " („" + type + "“)"));
      blocked = true;
    } else if (BgSaftCashAccounting.isInvoiceType(type)
        || (BgSaftCashAccounting.isReceivedProtocolType(type) && doc.purchases && !doc.sales)) {
      // Касова отчетност (отговорът на НАП по имейл от 19.10.2026): издаденото — по ставката,
      // полученото — по кредита, освен когато самото дружество е по режима.
      String refusal = doc.purchases ? c.cashAccounting.receivedRefusal(doc.date) : null;
      if (refusal != null) {
        c.found.napPending.add(c.ref(label + " — вид " + type + " — " + refusal));
        blocked = true;
      }
    } else if (!TYPES.contains(type)) {
      c.found.typeUnresolved.add(
          c.ref(label + " („" + type + "“ — " + c.invoiceTypes.get(type) + ")"));
      blocked = true;
    }
    if (trim(doc.number) == null) {
      c.found.noNumber.add(c.ref(label));
      blocked = true;
    }

    // Контрагентът: подаващият при протокол без доставка, иначе партньорът на статията.
    boolean ownParty = op != null && WITHOUT_SUPPLY.contains(op);
    PartyRow partner = doc.partnerId == null ? null : c.partners.get(doc.partnerId);
    if (!ownParty) {
      Set<Long> linePartners = doc.linePartners();
      if (doc.partnerId != null) {
        linePartners.remove(doc.partnerId);
      }
      if ("ART163A".equals(op) && (doc.partnerId == null || !linePartners.isEmpty())) {
        c.found.general.add(c.ref(label));
        blocked = true;
      } else if (partner == null) {
        c.found.partner.add(c.ref(label));
        blocked = true;
      }
    }

    BgSaftDiaryCells.Rates rates = BgSaftDiaryCells.rates(rateLines(doc), doc.manualRate);
    BgSaftDiaryCells.Coding salesCoding = null;
    BgSaftDiaryCells.Coding purchaseCoding = null;
    if (doc.sales && !doc.customs && !doc.salesColumns.isEmpty()) {
      salesCoding = BgSaftDiaryCells.sales(doc.salesColumns, doc.supplyType, op, rates);
    }
    if (doc.purchases) {
      if (doc.customs) {
        purchaseCoding = BgSaftDiaryCells.customs(importBase);
      } else if (!doc.purchaseColumns.isEmpty()) {
        BigDecimal base = null;
        BigDecimal tax = null;
        if (protocol && salesCoding != null) {
          base = BigDecimal.ZERO;
          tax = BigDecimal.ZERO;
          for (BgSaftDiaryCells.Cell cell : salesCoding.getCells()) {
            base = base.add(cell.getBase());
            tax = tax.add(cell.getTax());
          }
        }
        purchaseCoding =
            BgSaftDiaryCells.purchases(doc.purchaseColumns, doc.supplyType, op, rates, base, tax);
      }
    }
    for (BgSaftDiaryCells.Coding coding :
        new BgSaftDiaryCells.Coding[] {salesCoding, purchaseCoding}) {
      if (coding == null) {
        continue;
      }
      for (String error : coding.getErrors()) {
        (BgSaftNapPending.isPending(error) ? c.found.napPending : c.found.code)
            .add(c.ref(label + " — " + error));
        blocked = true;
      }
      for (String warning : coding.getWarnings()) {
        c.found.codeWarning.add(c.ref(label + " — " + warning));
      }
      // Клетка без код — докладва се и когато документът е блокиран и по друга причина.
      for (BgSaftDiaryCells.Cell cell : coding.getCells()) {
        if (c.code(cell.getKey(), label) == null) {
          blocked = true;
        }
      }
    }
    if (blocked) {
      return;
    }
    if (doc.customs || (op != null && GOODS.contains(op))) {
      c.found.products.add(c.ref(label));
    }

    if (salesCoding != null && !salesCoding.getCells().isEmpty()) {
      SaftInvoice built = build(doc, true, protocol, ownParty, partner, salesCoding, label, c);
      if (built != null) {
        sales.add(built);
        c.documents.add(doc.moveId, built);
      }
    }
    if (purchaseCoding != null && !purchaseCoding.getCells().isEmpty()) {
      SaftInvoice built = build(doc, false, protocol, ownParty, partner, purchaseCoding, label, c);
      if (built != null) {
        purchases.add(built);
        c.documents.add(doc.moveId, built);
      }
    }
  }

  private static List<BgSaftDiaryCells.RateLine> rateLines(DiaryDocument doc) {
    List<BgSaftDiaryCells.RateLine> lines = new ArrayList<>();
    for (MoveLineRow line : doc.lines) {
      lines.add(new BgSaftDiaryCells.RateLine(line.taxRate, line.amount(), line.vatSide != null));
    }
    return lines;
  }

  /** Един SAF-T документ от едната страна на реда в дневника. */
  private static SaftInvoice build(
      DiaryDocument doc,
      boolean salesSide,
      boolean protocol,
      boolean ownParty,
      PartyRow partner,
      BgSaftDiaryCells.Coding coding,
      String label,
      Context c) {
    String where = label + (salesSide ? " (продажби)" : " (покупки)");
    // Кодовете — преди всичко: клетка без ред в BgSafVatCellCode е ГРЕШКА, не празен таг.
    List<SaftTaxInformation> informations = new ArrayList<>();
    boolean blocked = false;
    for (BgSaftDiaryCells.Cell cell : coding.getCells()) {
      SaftTaxInformation information = c.code(cell.getKey(), where);
      if (information == null) {
        blocked = true;
      }
      informations.add(information);
    }

    // Документ без доставка (корекциите и годишната корекция) няма разчетна сметка — сметките са
    // по правилото на протокола: ДДС сметката на страната и насрещната ѝ.
    Long[] accounts = accounts(doc, salesSide, protocol || ownParty);
    if (doc.customs && accounts[0] == null) {
      // ЕАД без сметка за разчет с митница: Прил. 3 т. IV.7 я „допуска“, не я изисква — сметките
      // са по аналогия с протокола от същата статия (4531 и насрещната ѝ), с предупреждение.
      accounts = protocolAccounts(doc, false);
      c.found.customsAccount.add(c.ref(label));
    }
    String head = c.nraCode(accounts[0]);
    String corresponding = c.nraCode(accounts[1]);
    if (head == null) {
      c.found.accountUnmapped.add(c.ref(where + " S.I.4"));
      blocked = true;
    }
    if (corresponding == null) {
      c.found.accountUnmapped.add(c.ref(where + " S.I.30"));
      blocked = true;
    }
    if (blocked) {
      return null;
    }

    SaftParty party;
    if (ownParty) {
      party = c.own;
    } else {
      party = BgSaftPartiesAssembler.describe(partner);
      c.references.add(
          new BgSaftPartiesAssembler.Reference(
              doc.partnerId, BgSaftPartiesAssembler.Side.SUPPLIER, settlement(doc), label));
    }
    if (trim(doc.moveReference) == null) {
      c.found.noReference.add(c.ref(label));
    }

    SaftInvoice result =
        new SaftInvoice()
            .invoiceNo(c.fit(trim(doc.number), MIDDLE2, label + " InvoiceNo"))
            .partyId(party.getPartyId())
            .partyName(party.getName())
            .billingAddress(party.getAddresses().isEmpty() ? null : party.getAddresses().get(0))
            .accountId(head)
            .invoiceDate(doc.date)
            .invoiceType(trim(doc.type))
            .selfBillingIndicator(BgSaftInvoicesAssembler.SELF_BILLING_NO)
            .transactionId(
                c.fit(
                    BgSaftInvoicesAssembler.transactionReference(doc.moveReference, doc.moveId),
                    MIDDLE2,
                    label + " TransactionID"));
    // Протокол за доставка в SalesInvoices носи доставчика (QA с. 58).
    if (salesSide && !ownParty) {
      result.partyRole(SaftInvoice.PartyRole.SUPPLIER);
    }
    if (doc.periodFrom != null) {
      result.period(doc.periodFrom.getMonthValue()).periodYear(doc.periodFrom.getYear());
    }

    String text = description(doc);
    Map<String, SaftTaxInformation> totals = new LinkedHashMap<>();
    BigDecimal net = BigDecimal.ZERO.setScale(SCALE);
    BigDecimal taxes = BigDecimal.ZERO.setScale(SCALE);
    List<BgSaftDiaryCells.Cell> cells = coding.getCells();
    for (int i = 0; i < cells.size(); i++) {
      BgSaftDiaryCells.Cell cell = cells.get(i);
      SaftTaxInformation code = informations.get(i);
      // D/C по QA (с. 76–78): посоката е на основата, а при основа 0 — на данъка.
      BigDecimal sign = cell.getBase().signum() != 0 ? cell.getBase() : cell.getTax();
      boolean debit = sign.signum() >= 0;
      BigDecimal present = cell.getBase().abs();
      BigDecimal taxPresent = debit ? cell.getTax() : cell.getTax().negate();
      SaftTaxInformation information =
          new SaftTaxInformation()
              .taxType(code.getTaxType())
              .taxCode(code.getTaxCode())
              .taxAmount(SaftAmount.eur(taxPresent));
      if (cell.getRate() != null) {
        information.taxPercentage(percentage(cell.getRate()));
      }
      result.addLine(
          new SaftInvoiceLine()
              .lineNumber(String.valueOf(i + 1))
              .accountId(corresponding)
              .productCode(NO_PRODUCT)
              .quantity(BigDecimal.ONE)
              .unitPrice(present)
              .taxPointDate(doc.date)
              .description(c.fit(text, LONG, label + " Description"))
              .invoiceLineAmount(SaftAmount.eur(present))
              .debitCreditIndicator(debit ? DEBIT : CREDIT)
              .addTaxInformation(information));
      net = net.add(cell.getBase());
      taxes = taxes.add(cell.getTax());
      SaftTaxInformation total =
          totals.computeIfAbsent(
              information.getTaxType() + "|" + information.getTaxCode(),
              k ->
                  new SaftTaxInformation()
                      .taxType(information.getTaxType())
                      .taxCode(information.getTaxCode())
                      .taxBase(BigDecimal.ZERO.setScale(SCALE))
                      .taxAmount(SaftAmount.eur(BigDecimal.ZERO.setScale(SCALE))));
      total.taxBase(total.getTaxBase().add(cell.getBase()));
      total.taxAmount(SaftAmount.eur(total.getTaxAmount().getAmount().add(cell.getTax())));
    }
    totals.values().forEach(result::addTaxInformationTotal);
    result.netTotal(net).grossTotal(net.add(taxes));
    return result;
  }

  /**
   * S.I.4 и S.I.30 — Прил. 3 т. IV.7, последните два абзаца.
   *
   * <ul>
   *   <li>Протокол: S.I.4 е сметката за ДДС на страната (4532 продажби, 4531 покупки), S.I.30 —
   *       сметката за ДДС на другата страна, а без нея — най-голямата друга сметка на статията.
   *   <li>ЕАД: S.I.4 е сметката за разчет с митница — кредитният ред извън 4531/4532; S.I.30 е
   *       сметката за ДДС по покупки. Без такъв ред S.I.4 е {@code null}, а сглобяването минава към
   *       {@link #protocolAccounts} с предупреждение.
   *   <li>Ръчна покупка: S.I.4 е разчетната сметка — най-големият кредитен ред извън ДДС; S.I.30 —
   *       най-големият дебитен ред извън ДДС, а без такъв — сметката за ДДС по покупки.
   * </ul>
   *
   * @return {S.I.4, S.I.30}: id-тата на сметките, {@code null} — няма такава
   */
  static Long[] accounts(DiaryDocument doc, boolean salesSide, boolean protocol) {
    if (doc.customs) {
      MoveLineRow customs = largest(doc.lines, l -> l.vatSide == null && l.credit.signum() != 0);
      MoveLineRow vat = first(doc.lines, VatSide.PURCHASE);
      return new Long[] {account(customs), account(vat)};
    }
    if (protocol) {
      return protocolAccounts(doc, salesSide);
    }
    MoveLineRow head = largest(doc.lines, l -> l.vatSide == null && l.credit.signum() != 0);
    MoveLineRow corresponding = largest(doc.lines, l -> l.vatSide == null && l.debit.signum() != 0);
    if (corresponding == null) {
      corresponding = first(doc.lines, VatSide.PURCHASE);
    }
    return new Long[] {account(head), account(corresponding)};
  }

  /** Протокол: ДДС сметката на страната и насрещната ѝ, а без нея — най-голямата друга сметка. */
  static Long[] protocolAccounts(DiaryDocument doc, boolean salesSide) {
    VatSide own = salesSide ? VatSide.SALES : VatSide.PURCHASE;
    VatSide other = salesSide ? VatSide.PURCHASE : VatSide.SALES;
    MoveLineRow head = first(doc.lines, own);
    if (head == null) {
      head = largest(doc.lines, l -> l.vatSide != other);
    }
    MoveLineRow corresponding = first(doc.lines, other);
    Long headAccount = account(head);
    if (corresponding == null) {
      corresponding =
          largest(doc.lines, l -> l.accountId != null && !l.accountId.equals(headAccount));
    }
    return new Long[] {headAccount, account(corresponding)};
  }

  private interface LineFilter {
    boolean test(MoveLineRow line);
  }

  private static MoveLineRow first(List<MoveLineRow> lines, VatSide side) {
    for (MoveLineRow line : lines) {
      if (line.vatSide == side) {
        return line;
      }
    }
    return null;
  }

  /** Най-големият по сума ред, който минава филтъра; при равенство — първият. */
  private static MoveLineRow largest(List<MoveLineRow> lines, LineFilter filter) {
    MoveLineRow best = null;
    for (MoveLineRow line : lines) {
      if (filter.test(line)
          && (best == null || line.amount().abs().compareTo(best.amount().abs()) > 0)) {
        best = line;
      }
    }
    return best;
  }

  private static Long account(MoveLineRow line) {
    return line == null ? null : line.accountId;
  }

  /** S.I.45: описанието на статията; без него — видът на протокола. */
  private static String description(DiaryDocument doc) {
    String text = trim(doc.description);
    if (text != null) {
      return text;
    }
    String op = trim(doc.operation);
    if (doc.customs) {
      return "Митническа декларация — внос по чл. 167а ЗДДС";
    }
    return op == null ? "Статия с ДДС" : "ДДС протокол — " + op;
  }

  private static BigDecimal percentage(BigDecimal rate) {
    BigDecimal v = rate.movePointRight(2).stripTrailingZeros();
    return v.scale() < 0 ? v.setScale(0) : v;
  }

  private static final Comparator<DiaryDocument> ORDER =
      Comparator.comparing(
              (DiaryDocument d) -> d.date, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(d -> d.moveId, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(d -> d.customs);

  /**
   * Разчетната сметка на контрагента в статията — първият ред с неговия партньор и без ДДС страна
   * (QA с. 37, MF.S.5: в MasterFiles AccountID е „разчетната сметка на контрагента, която е
   * посочена и в GeneralLedgerEntries“); {@code null}, когато статията няма такъв ред.
   */
  private static Long settlement(DiaryDocument doc) {
    for (MoveLineRow line : doc.lines) {
      if (line.vatSide == null && doc.partnerId != null && doc.partnerId.equals(line.partnerId)) {
        return line.accountId;
      }
    }
    return null;
  }

  private static String label(DiaryDocument doc) {
    String number = trim(doc.number);
    String reference = trim(doc.moveReference);
    String name = number != null ? number : reference != null ? reference : "#" + doc.moveId;
    return doc.customs ? name + " (ЕАД)" : name;
  }

  private static String trim(String value) {
    if (value == null) {
      return null;
    }
    String v = value.trim();
    return v.isEmpty() ? null : v;
  }

  // ─────────────── контекстът ───────────────

  private static final class Context {
    private final Map<Long, Resolution> accounts;
    private final Map<Long, PartyRow> partners;
    private final SaftParty own;
    private final Map<String, String> cellCodes;
    private final Map<String, BgNraNomenclatures.TaxCode> nomenclature;
    private final Map<String, String> invoiceTypes;
    private final Findings found = new Findings();
    private final List<BgSaftPartiesAssembler.Reference> references = new ArrayList<>();
    private final BgSaftDocumentCodes documents = new BgSaftDocumentCodes();

    /** Статията, която се сглобява — към нея сочат находките. */
    private BgSaftRef current;

    private BgSaftCashAccounting cashAccounting = BgSaftCashAccounting.NONE;

    private Context(
        Map<Long, Resolution> accounts,
        Map<Long, PartyRow> partners,
        SaftParty own,
        Map<String, String> cellCodes,
        Map<String, BgNraNomenclatures.TaxCode> nomenclature,
        Map<String, String> invoiceTypes) {
      this.accounts = accounts;
      this.partners = partners;
      this.own = own;
      this.cellCodes = cellCodes;
      this.nomenclature = nomenclature;
      this.invoiceTypes = invoiceTypes;
    }

    /** Препратка към текущата статия с етикета {@code text}. */
    private BgSaftRef ref(String text) {
      return current == null ? BgSaftRef.text(text) : current.withLabel(text);
    }

    private String nraCode(Long accountId) {
      Resolution resolution = accountId == null ? null : accounts.get(accountId);
      if (resolution == null || resolution.isExcluded() || resolution.getNraCode().isEmpty()) {
        return null;
      }
      return resolution.getNraCode();
    }

    /** Видът и кодът от {@code BgSafVatCellCode} и TAX-IMP; {@code null} и находка, ако няма. */
    private SaftTaxInformation code(String key, String where) {
      String code = key == null ? null : cellCodes.get(key);
      if (code == null) {
        found.taxUnknown.add(ref(where + " (клетка " + key + " я няма в BgSafVatCellCode)"));
        return null;
      }
      BgNraNomenclatures.TaxCode row = nomenclature.get(code);
      if (row == null) {
        found.taxUnknown.add(ref(where + " (кодът " + code + " го няма в TAX-IMP)"));
        return null;
      }
      return new SaftTaxInformation().taxType(row.taxType()).taxCode(code);
    }

    private String fit(String value, int max, String what) {
      if (value == null || value.codePointCount(0, value.length()) <= max) {
        return value;
      }
      found.truncated.add(ref(what));
      return value.substring(0, value.offsetByCodePoints(0, max));
    }
  }

  private static final class Findings {
    private final Set<BgSaftRef> noBase = new LinkedHashSet<>();
    private final Set<BgSaftRef> operation = new LinkedHashSet<>();
    private final Set<BgSaftRef> diary = new LinkedHashSet<>();
    private final Set<BgSaftRef> typeMissing = new LinkedHashSet<>();
    private final Set<BgSaftRef> typeInvalid = new LinkedHashSet<>();
    private final Set<BgSaftRef> typeUnresolved = new LinkedHashSet<>();
    private final Set<BgSaftRef> noNumber = new LinkedHashSet<>();
    private final Set<BgSaftRef> general = new LinkedHashSet<>();
    private final Set<BgSaftRef> partner = new LinkedHashSet<>();
    private final Set<BgSaftRef> code = new LinkedHashSet<>();
    private final Set<BgSaftRef> taxUnknown = new LinkedHashSet<>();
    private final Set<BgSaftRef> customsAccount = new LinkedHashSet<>();
    private final Set<BgSaftRef> accountUnmapped = new LinkedHashSet<>();
    private final Set<BgSaftRef> codeWarning = new LinkedHashSet<>();
    private final Set<BgSaftRef> baseDerived = new LinkedHashSet<>();
    private final Set<BgSaftRef> products = new LinkedHashSet<>();
    private final Set<BgSaftRef> noReference = new LinkedHashSet<>();
    private final Set<BgSaftRef> truncated = new LinkedHashSet<>();
    private final Set<BgSaftRef> outOfScope = new LinkedHashSet<>();
    private final Set<BgSaftRef> napPending = new LinkedHashSet<>();
    private final Set<BgSaftRef> creditDefaulted = new LinkedHashSet<>();

    private List<BgDeclarationFileFinding> toFindings() {
      List<BgDeclarationFileFinding> findings = new ArrayList<>();
      error(
          findings,
          "SAFT_PROT_NO_BASE",
          "Статия с ДДС без ДДС операция и без кредитен тип — основата не е определена и в"
              + " дневника; не е подадена: ",
          noBase);
      error(findings, "SAFT_PROT_OPERATION", "Непозната ДДС операция на статия: ", operation);
      error(
          findings,
          "SAFT_PROT_DIARY",
          "Дневникът блокира реда — документът не е подаден и в SAF-T: ",
          diary);
      error(
          findings,
          "SAFT_PROT_TYPE_MISSING",
          "Статия без вид на документа по НАП — S.I.9 InvoiceType е задължителен: ",
          typeMissing);
      error(
          findings,
          "SAFT_PROT_TYPE_INVALID",
          "Вид на документа извън Nom_Invoice_Types — НАП го отказва: ",
          typeInvalid);
      error(
          findings,
          "SAFT_PROT_TYPE_UNRESOLVED",
          "Вид на документа без маршрут в SAF-T (издаден протокол по чл. 151в/151г, 126б, 113б,"
              + " регистри и отчети) — не е подаден: ",
          typeUnresolved);
      error(findings, "SAFT_PROT_NUMBER", "Протокол без номер — S.I.1 е задължителен: ", noNumber);
      error(
          findings,
          "SAFT_PROT_GENERAL",
          "Общ протокол по чл. 163б ал. 1 т. 2 ЗДДС (много доставчици — физически лица) — SupplierInfo"
              + " е един; не е подаден: ",
          general);
      error(
          findings,
          "SAFT_PROT_PARTNER",
          "Протокол за доставка без контрагент — SupplierInfo (S.I.3) е задължителен (QA с. 58): ",
          partner);
      error(
          findings,
          "SAFT_PROT_NAP_PENDING",
          "Протокол, чийто код в SAF-T чака НАП — не е подаден: ",
          napPending);
      error(
          findings,
          "SAFT_PROT_CODE",
          "Данъчният код не се определя от реда в дневника — празен TaxType/TaxCode НАП отхвърля"
              + " (QA IV.2.3): ",
          code);
      error(
          findings,
          "SAFT_PROT_TAX_UNKNOWN",
          "Клетка без код в BgSafVatCellCode или код извън TAX-IMP: ",
          taxUnknown);
      error(
          findings,
          "SAFT_PROT_ACCOUNT_UNMAPPED",
          "Сметка без код по НАП или изключена — AccountID (S.I.4/S.I.30) не може да се подаде: ",
          accountUnmapped);
      warning(findings, "SAFT_PROT_CODE_WARNING", "Кодът да се потвърди: ", codeWarning);
      warning(
          findings,
          "SAFT_PROT_CREDIT_DEFAULTED",
          "Ред на покупка без вид данъчен кредит — приет е пълен кредит (100331), както в"
              + " дневника: ",
          creditDefaulted);
      warning(
          findings,
          "SAFT_PROT_BASE_DERIVED",
          "Основата на протокола е изведена от данъка (данък / ставка, VAT_PROTOCOL_BASE_DERIVED)"
              + " — губи стотинки; въведете основата в протокола: ",
          baseDerived);
      warning(
          findings,
          "SAFT_PROT_EAD_NO_CUSTOMS_ACCOUNT",
          "ЕАД без сметка за митница — S.I.4 по аналогия с протокола, да се потвърди (Прил. 3 т."
              + " IV.7 „допуска“ сметката за разчет с митница, не я изисква): ",
          customsAccount);
      warning(
          findings,
          "SAFT_PROT_PRODUCTS",
          "Протоколът/ЕАД няма артикули — подаден с ProductCode „0“ на ред по клетка; QA (с. 58–61)"
              + " иска кода на всеки внесен продукт: ",
          products);
      warning(
          findings,
          "SAFT_PROT_NO_REFERENCE",
          "Статия без номер — TransactionID (S.I.18) е id-то ѝ, както в дневника: ",
          noReference);
      warning(
          findings,
          "SAFT_PROT_TRUNCATED",
          "Текст по-дълъг, отколкото схемата допуска — съкратен: ",
          truncated);
      warning(
          findings,
          "SAFT_PROT_OUT_OF_GL",
          "Статия с ред в дневника, която GeneralLedgerEntries не носи (изключена от счетоводството"
              + " или откриваща) — не е в SalesInvoices/PurchaseInvoices: ",
          outOfScope);
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
}

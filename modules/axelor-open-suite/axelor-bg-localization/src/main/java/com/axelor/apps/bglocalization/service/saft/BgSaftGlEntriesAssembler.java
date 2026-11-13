package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.saft.BgSaftGlAccountsAssembler.AccountRow;
import com.axelor.apps.bglocalization.service.saft.BgSaftGlAccountsAssembler.Resolution;
import com.axelor.apps.bglocalization.service.saft.BgSaftPartiesAssembler.PartyRow;
import com.axelor.apps.bglocalization.service.saft.BgSaftPartiesAssembler.Side;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAmount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftGeneralLedger;
import com.axelor.apps.bglocalization.service.saft.dto.SaftGlAccount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftJournal;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxInformation;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransaction;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransactionLine;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
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
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * GeneralLedgerEntries (GL.*) ОТ РЕДОВЕТЕ НА СТАТИИТЕ — чиста функция.
 *
 * <p><b>Нула заявки.</b> Редовете идват от {@link BgSaftGlEntriesReader} (тоест от {@link
 * BgSaftMoveScope#TURNOVER_LINES} — същите статии като оборота в GeneralLedgerAccounts), а
 * сметкопланът, мапингите и контрагентите — от G2. Всяко правило тук се проверява с числа.
 *
 * <p><b>⚠️ ЖУРНАЛ → СТАТИЯ → РЕД.</b> Journal е журналът на Axelor (GL.5 = кодът, GL.6 = името),
 * Transaction е статията, TransactionLine е редът ѝ. Изключена сметка (структурна или задбалансова)
 * НЕ влиза: GeneralLedgerAccounts я пропуска, тоест ред по нея би сочил сметка, която файлът няма —
 * редът отпада с предупреждение, а статия без нито един останал ред отпада цялата.
 *
 * <p><b>⚠️ CustomerID / SupplierID — ПО КНИГАТА И QA, НЕ ПО ОБРАЗЕЦА.</b> Бележката на GL.19 и QA
 * (с. 46–48, GL.19/GL.20) казват: ред по сметка на клиент (група 41 на НАП) носи идентификатора на
 * контрагента си в CustomerID и „0“ в SupplierID, по сметка на доставчик (група 40) — обратното.
 * Статия с един клиент или един доставчик носи неговия идентификатор, и всеки неин ред без
 * контрагент — също. Статия, НЕСВЪРЗАНА с контрагент, и статия с няколко контрагента от една страна
 * носят и в двете полета идентификатора на ПОДАВАЩИЯ — „10“ + ЕИК, не „0“. Контрагентът е
 * партньорът на реда по сметката от група 40/41 — същият източник, от който G2b строи Customers и
 * Suppliers — и идентификаторът е същият ({@link BgSaftPartiesAssembler#identify}): НАП проверява,
 * че всеки ID в дневника го има в таблиците. Статия с клиент И доставчик носи и двата — да се
 * потвърди.
 *
 * <p><b>⚠️ ДАТИТЕ — ПО QA (с. 45–46).</b> GL.12 TransactionDate е датата на документа ({@code
 * Move.originDate}, а без нея датата на статията); GL.17 SystemEntryDate — въвеждането ({@code
 * createdOn}); GL.18 GLPostingDate и GL.10/GL.11 — отчетната дата и периодът, за които се отнася
 * осчетоводяването ({@code Move.date} и {@code Move.period}). {@code Move.accountingDate} НЕ е
 * GL.18: Axelor пише в него деня на валидирането, а QA дава пример с осчетоводяване на 14.04 и
 * GLPostingDate 31.03.
 *
 * <p><b>⚠️ RecordID (GL.23) Е НОМЕРЪТ НА РЕДА В СТАТИЯТА</b> във възходящ ред (QA с. 48) — {@code
 * MoveLine.counter}; без него — id на реда, с предупреждение.
 *
 * <p><b>⚠️ TaxInformation (GL.33) Е НА ВСЕКИ РЕД.</b> Ред без данък носи 000/000000 (TAX-IMP,
 * бележка R5) — законната употреба, без находка. Изключението е редът по сметката S.I.4 на протокол
 * (4531/4532): той взима кода на протокола, ако е еднозначен ({@link
 * BgSaftDocumentCodes#protocolCodeFor}, решение от 21.10.2026). Ред с данък носи по един
 * TaxInformation на данък, с кода от {@code BgSafTaxMapping} — същото правило като TaxTable (G2c):
 * без ред в мапинга или с код извън TAX-IMP — 000/000000 с предупреждение; динамичен —
 * подразбиращият се код с предупреждение, а истинската клетка се смята с документите (G5). S.TI.6
 * TaxAmount е задължителен, но сумата на данъка по ред също е G5 — тук е 0.00, с предупреждение.
 *
 * <p><b>⚠️ ПРАЗЕН TaxCode НИКОГА</b> (QA IV.2.3, с. 95; решение 1 от 14.10.2026). Данък, чийто ред
 * в мапинга е без код, защото кодът зависи от сделката (протоколи по чл. 117, корекции по чл.
 * 79/79а, внос по чл. 167а, известия), взима кода на ДОКУМЕНТА на същата статия — този, който
 * секцията за фактури и протоколи смята от клетката на дневника ({@link BgSaftDocumentCodes};
 * решение от 15.10.2026). Без документ или без еднозначен код е ГРЕШКА за статията и в XML-а стои
 * 000/000000 — файл с грешка така или иначе не се произвежда. Данък, чийто код чака НАП ({@link
 * BgSaftNapPending}), е ГРЕШКА винаги, и с документ.
 *
 * <p><b>⚠️ СТАТИЯТА СЕ БАЛАНСИРА — ИНАЧЕ ГРЕШКА.</b> Дебитът и кредитът на закръглените редове на
 * всяка транзакция са равни. Редовете по изключени сметки отпадат (с предупреждение по сметка); ако
 * без тях статията не се балансира — типично откриването и закриването на годината в Axelor през
 * 9000 „Начален баланс“ и 9010 „Заключителен баланс“ — тя е ГРЕШКА, а не тихо изпусната.
 *
 * <p><b>⚠️ ПЕРИОДЪТ НА СТАТИЯТА.</b> Обхватът е по {@code Move.date}; статия, чийто {@code
 * Move.period} е друг месец (13-и период, приключване), излиза с GL.10/GL.11 по своя период и с
 * предупреждение — QA с. 46: „годината и месеца, за който се отнася осчетоводяването“.
 *
 * <p><b>⚠️ КОДОВЕТЕ 600 И 700 (G3b) ИДВАТ ОТВЪН</b> — от {@link BgSaftWithholdingRouter}, по id на
 * реда, със сумата си. Стоят СЛЕД ДДС кода (XSD 1.0.2 допуска TaxInformation {@code
 * maxOccurs="unbounded"}), а ред само с тях НЕ носи 000/000000: неприложимостта е за транзакции,
 * „не свързани с начисляването“ на 600/700 (Прил. 3 т. IV.8.2).
 *
 * <p><b>⚠️ СУМАТА Е ПО ЗНАКА, ЗАКРЪГЛЕНА ДО 2 ЗНАКА HALF_UP.</b> Дебит минус кредит на реда:
 * неотрицателното е DebitAmount, отрицателното — CreditAmount (GL.31/32, xs:choice), с находка при
 * трети знак. NumberOfEntries е броят на статиите (QA с. 45), TotalDebit/TotalCredit — сборът на
 * закръглените суми, които файлът показва.
 */
public final class BgSaftGlEntriesAssembler {

  /** GL.19/GL.20 — „няма такъв контрагент“ в другото поле. */
  public static final String NO_PARTY = "0";

  private static final int SCALE = 2;

  /** SAFshorttextType, SAFmiddle2textType, SAFlongtextType. */
  private static final int SHORT = 18;

  private static final int MIDDLE2 = 70;
  private static final int LONG = 256;

  private BgSaftGlEntriesAssembler() {}

  /** Ред на статия, както го чете {@link BgSaftGlEntriesReader}. */
  public static final class LineRow {
    private final Long id;
    private Integer counter;
    private BigDecimal debit;
    private BigDecimal credit;
    private String description;
    private Long accountId;
    private String accountCode;
    private Long partnerId;
    private Long moveId;
    private String reference;
    private String moveDescription;
    private String origin;
    private LocalDate date;
    private LocalDate originDate;
    private LocalDate createdOn;
    private LocalDate periodFrom;
    private Long journalId;
    private String journalCode;
    private String journalName;
    private List<String> taxCodes = Collections.emptyList();
    private String paymentModeCode;
    private String saftTaxCode;
    private String accountSaftTaxCode;

    public LineRow(Long id) {
      this.id = id;
    }

    /** Номерът на реда в статията. */
    public LineRow counter(Integer value) {
      this.counter = value;
      return this;
    }

    public LineRow debit(BigDecimal value) {
      this.debit = value;
      return this;
    }

    public LineRow credit(BigDecimal value) {
      this.credit = value;
      return this;
    }

    public LineRow description(String value) {
      this.description = value;
      return this;
    }

    public LineRow account(Long id, String code) {
      this.accountId = id;
      this.accountCode = code;
      return this;
    }

    /** Партньорът на реда; {@code null} за ред без такъв. */
    public LineRow partnerId(Long value) {
      this.partnerId = value;
      return this;
    }

    public LineRow moveId(Long value) {
      this.moveId = value;
      return this;
    }

    /** {@code Move.reference} — номерът на статията. */
    public LineRow reference(String value) {
      this.reference = value;
      return this;
    }

    public LineRow moveDescription(String value) {
      this.moveDescription = value;
      return this;
    }

    /** {@code Move.origin} — номерът на документа. */
    public LineRow origin(String value) {
      this.origin = value;
      return this;
    }

    /** {@code Move.date} — отчетната дата. */
    public LineRow date(LocalDate value) {
      this.date = value;
      return this;
    }

    /** {@code Move.originDate} — датата на документа. */
    public LineRow originDate(LocalDate value) {
      this.originDate = value;
      return this;
    }

    public LineRow createdOn(LocalDate value) {
      this.createdOn = value;
      return this;
    }

    /** Началото на {@code Move.period}. */
    public LineRow periodFrom(LocalDate value) {
      this.periodFrom = value;
      return this;
    }

    public LineRow journal(Long id, String code, String name) {
      this.journalId = id;
      this.journalCode = code;
      this.journalName = name;
      return this;
    }

    /** {@code Tax.code} на данъците по реда. */
    public LineRow taxCodes(List<String> value) {
      this.taxCodes = value == null ? Collections.emptyList() : value;
      return this;
    }

    /** {@code Move.paymentMode.code} — чете го само Payments (G4); дневникът не го ползва. */
    public LineRow paymentModeCode(String value) {
      this.paymentModeCode = value;
      return this;
    }

    /** {@code MoveLine.bgSaftTaxCode} — ръчният код 600/700 на реда (G3b); бие сметката. */
    public LineRow saftTaxCode(String value) {
      this.saftTaxCode = value;
      return this;
    }

    /** {@code Account.bgSaftTaxCode} — подразбирането на сметката на дружеството (G3b). */
    public LineRow accountSaftTaxCode(String value) {
      this.accountSaftTaxCode = value;
      return this;
    }

    // Четенето — за Payments (G4), който сглобява плащанията от СЪЩИТЕ редове. Имената са тези на
    // setter-ите, не getX(): редът е ръчна проекция, в която null значи „няма“, а не генериран
    // getter със стойност по подразбиране (BgDefaultedGetterTest).

    Long id() {
      return id;
    }

    Integer counter() {
      return counter;
    }

    BigDecimal debit() {
      return debit;
    }

    BigDecimal credit() {
      return credit;
    }

    String description() {
      return description;
    }

    Long accountId() {
      return accountId;
    }

    Long partnerId() {
      return partnerId;
    }

    Long moveId() {
      return moveId;
    }

    String reference() {
      return reference;
    }

    String moveDescription() {
      return moveDescription;
    }

    String origin() {
      return origin;
    }

    LocalDate date() {
      return date;
    }

    LocalDate periodFrom() {
      return periodFrom;
    }

    String paymentModeCode() {
      return paymentModeCode;
    }

    String accountCode() {
      return accountCode;
    }

    String saftTaxCode() {
      return saftTaxCode;
    }

    String accountSaftTaxCode() {
      return accountSaftTaxCode;
    }
  }

  /** Дневникът, находките и оборотът по наша сметка, който той показва. */
  public static final class Result {
    private final SaftGeneralLedger generalLedger;
    private final List<BgDeclarationFileFinding> findings;
    private final Map<String, BigDecimal> netByTaxpayerAccount;

    private Result(
        SaftGeneralLedger generalLedger,
        List<BgDeclarationFileFinding> findings,
        Map<String, BigDecimal> netByTaxpayerAccount) {
      this.generalLedger = generalLedger;
      this.findings = Collections.unmodifiableList(findings);
      this.netByTaxpayerAccount = Collections.unmodifiableMap(netByTaxpayerAccount);
    }

    public SaftGeneralLedger getGeneralLedger() {
      return generalLedger;
    }

    public List<BgDeclarationFileFinding> getFindings() {
      return findings;
    }

    /** Дебит минус кредит по TaxpayerAccountID — закръглените суми от файла. */
    public Map<String, BigDecimal> getNetByTaxpayerAccount() {
      return netByTaxpayerAccount;
    }
  }

  /**
   * @param lines редовете на статиите в периода
   * @param chart сметкопланът на дружеството
   * @param accountMappings {@code BgSafAccountMapping} по код на сметката
   * @param partners партньорите по id (същите като за Customers/Suppliers)
   * @param taxMappings {@code BgSafTaxMapping} по код на данъка
   * @param nomenclature TAX-IMP ({@link BgNraNomenclatures#taxCodes()})
   * @param ownId идентификаторът на подаващия — „10“ + ЕИК
   */
  public static Result assemble(
      Collection<LineRow> lines,
      Collection<AccountRow> chart,
      Map<String, BgSaftGlAccountsAssembler.MappingRow> accountMappings,
      Map<Long, PartyRow> partners,
      Map<String, BgSaftTaxTableAssembler.MappingRow> taxMappings,
      Map<String, BgNraNomenclatures.TaxCode> nomenclature,
      String ownId) {
    return assemble(
        lines,
        chart,
        accountMappings,
        partners,
        taxMappings,
        nomenclature,
        ownId,
        Collections.emptyMap());
  }

  /**
   * Същото, с кодовете 600/700 от {@link BgSaftWithholdingRouter} по id на реда (G3b).
   *
   * @param withholdings {@link BgSaftWithholdingRouter.Result#getByLine()}
   */
  public static Result assemble(
      Collection<LineRow> lines,
      Collection<AccountRow> chart,
      Map<String, BgSaftGlAccountsAssembler.MappingRow> accountMappings,
      Map<Long, PartyRow> partners,
      Map<String, BgSaftTaxTableAssembler.MappingRow> taxMappings,
      Map<String, BgNraNomenclatures.TaxCode> nomenclature,
      String ownId,
      Map<Long, List<SaftTaxInformation>> withholdings) {
    return assemble(
        lines,
        chart,
        accountMappings,
        partners,
        taxMappings,
        nomenclature,
        ownId,
        withholdings,
        null);
  }

  /**
   * Същото, с месеца на отчета — статия с {@code Move.period} в друг месец дава предупреждение.
   *
   * @param reportMonth месецът на файла; {@code null} — без тази проверка
   */
  public static Result assemble(
      Collection<LineRow> lines,
      Collection<AccountRow> chart,
      Map<String, BgSaftGlAccountsAssembler.MappingRow> accountMappings,
      Map<Long, PartyRow> partners,
      Map<String, BgSaftTaxTableAssembler.MappingRow> taxMappings,
      Map<String, BgNraNomenclatures.TaxCode> nomenclature,
      String ownId,
      Map<Long, List<SaftTaxInformation>> withholdings,
      YearMonth reportMonth) {
    return assemble(
        lines,
        chart,
        accountMappings,
        partners,
        taxMappings,
        nomenclature,
        ownId,
        withholdings,
        reportMonth,
        BgSaftDocumentCodes.EMPTY);
  }

  /**
   * Същото, с кодовете на документите на статиите — за данък без код в мапинга дневникът взима кода
   * на документа (протокол, фактура) на същата статия.
   *
   * @param documents {@link BgSaftInvoicesAssembler.Result#getDocumentCodes()}
   */
  public static Result assemble(
      Collection<LineRow> lines,
      Collection<AccountRow> chart,
      Map<String, BgSaftGlAccountsAssembler.MappingRow> accountMappings,
      Map<Long, PartyRow> partners,
      Map<String, BgSaftTaxTableAssembler.MappingRow> taxMappings,
      Map<String, BgNraNomenclatures.TaxCode> nomenclature,
      String ownId,
      Map<Long, List<SaftTaxInformation>> withholdings,
      YearMonth reportMonth,
      BgSaftDocumentCodes documents) {
    Context c =
        new Context(
            BgSaftGlAccountsAssembler.resolutions(chart, accountMappings),
            partners,
            taxMappings,
            nomenclature,
            ownId,
            withholdings,
            reportMonth,
            documents);

    // Статиите — без редовете по изключени сметки.
    Map<Long, List<LineRow>> byMove = new LinkedHashMap<>();
    Set<Long> allMoves = new HashSet<>();
    for (LineRow line : lines) {
      allMoves.add(line.moveId);
      Resolution account = c.accounts.get(line.accountId);
      if (account != null && account.isExcluded()) {
        c.found.excluded.merge(
            BgSaftRef.account(line.accountId, account.getCode()), 1, Integer::sum);
        c.withExcluded.add(line.moveId);
        continue;
      }
      byMove.computeIfAbsent(line.moveId, k -> new ArrayList<>()).add(line);
    }
    int droppedMoves = allMoves.size() - byMove.size();

    List<List<LineRow>> moves = new ArrayList<>(byMove.values());
    moves.forEach(move -> move.sort(LINE_ORDER));
    moves.sort(Comparator.comparing((List<LineRow> move) -> move.get(0), MOVE_ORDER));

    List<SaftJournal> journals = new ArrayList<>();
    List<SaftTransaction> transactions = null;
    Long journalId = null;
    for (List<LineRow> move : moves) {
      LineRow head = move.get(0);
      if (transactions == null || !Objects.equals(journalId, head.journalId)) {
        transactions = new ArrayList<>();
        journalId = head.journalId;
        journals.add(
            new SaftJournal()
                .journalId(
                    c.fit(trim(head.journalCode), SHORT, null, "JournalID " + head.journalCode))
                .description(
                    c.fit(trim(head.journalName), LONG, null, head.journalCode + " Description"))
                .transactions(transactions));
      }
      transactions.add(transaction(move, c));
    }

    if (journals.isEmpty()) {
      journals.add(emptyJournal(reportMonth));
    }
    SaftGeneralLedger ledger =
        new SaftGeneralLedger()
            .numberOfEntries((long) moves.size())
            .totalDebit(c.totalDebit)
            .totalCredit(c.totalCredit)
            .journals(journals);
    return new Result(ledger, c.found.toFindings(moves.isEmpty(), droppedMoves), c.net);
  }

  /**
   * НАП сверява салдата срещу оборотите (QA с. 30): за всяка сметка от GeneralLedgerAccounts крайно
   * минус начално салдо е дебитът минус кредитът ѝ в дневника, и в дневника няма сметка, която
   * таблицата да няма. Разминаване значи различни статии в двете части — или трети знак, закръглен
   * веднъж по сума и веднъж по ред.
   */
  public static List<BgDeclarationFileFinding> reconcile(
      Collection<SaftGlAccount> accounts, Map<String, BigDecimal> netByTaxpayerAccount) {
    List<String> mismatched = new ArrayList<>();
    Set<String> known = new HashSet<>();
    for (SaftGlAccount account : accounts) {
      String code = account.getTaxpayerAccountId();
      known.add(code);
      BigDecimal expected = account.getClosingBalance().subtract(account.getOpeningBalance());
      BigDecimal actual = netByTaxpayerAccount.getOrDefault(code, BigDecimal.ZERO);
      if (expected.compareTo(actual) != 0) {
        mismatched.add(
            code
                + " (салдата: "
                + expected.toPlainString()
                + ", дневникът: "
                + actual.toPlainString()
                + ")");
      }
    }
    for (String code : new TreeSet<>(netByTaxpayerAccount.keySet())) {
      if (!known.contains(code)) {
        mismatched.add(code + " (в дневника, без ред в GeneralLedgerAccounts)");
      }
    }
    List<BgDeclarationFileFinding> findings = new ArrayList<>();
    for (String item : mismatched) {
      findings.add(
          BgDeclarationFileFinding.warning(
              "SAFT_GLE_GLA_MISMATCH",
              "Оборотът в GeneralLedgerEntries не връзва началното с крайното салдо в"
                  + " GeneralLedgerAccounts — НАП сверява равенството за всяка сметка: "
                  + item));
    }
    return findings;
  }

  // ─────────────── една статия ───────────────

  private static SaftTransaction transaction(List<LineRow> rows, Context c) {
    LineRow head = rows.get(0);
    String reference = trim(head.reference);
    if (reference == null) {
      reference = String.valueOf(head.moveId);
      c.found.noReference.add(BgSaftRef.move(head.moveId, "#" + head.moveId));
    }
    BgSaftRef move = BgSaftRef.move(head.moveId, reference);
    String transactionId = c.fit(reference, MIDDLE2, move, "TransactionID " + reference);
    String description = firstNonBlank(head.moveDescription, head.origin);
    if (description == null) {
      c.found.noDescription.add(move);
      description = "";
    }
    description = c.fit(description, LONG, move, reference + " Description");

    LocalDate period = head.periodFrom;
    if (period == null) {
      c.found.noPeriod.add(move);
      period = head.date;
    } else if (c.reportMonth != null && !YearMonth.from(period).equals(c.reportMonth)) {
      c.found.otherPeriod.add(
          move.withLabel(reference + " (период " + YearMonth.from(period) + ")"));
    }
    LocalDate entered = head.createdOn;
    if (entered == null) {
      c.found.noEntryDate.add(move);
      entered = head.date;
    }

    // Контрагентите на статията — от редовете по сметки на клиенти и доставчици.
    Map<LineRow, Party> parties = new HashMap<>();
    Set<String> customers = new TreeSet<>();
    Set<String> suppliers = new TreeSet<>();
    for (LineRow line : rows) {
      Party party = c.party(line);
      if (party != null) {
        parties.put(line, party);
        (party.side == Side.CUSTOMER ? customers : suppliers).add(party.id);
      }
    }
    String customerId;
    String supplierId;
    if (customers.size() > 1 || suppliers.size() > 1 || customers.size() + suppliers.size() == 0) {
      customerId = c.ownId;
      supplierId = c.ownId;
    } else {
      customerId = customers.isEmpty() ? NO_PARTY : customers.iterator().next();
      supplierId = suppliers.isEmpty() ? NO_PARTY : suppliers.iterator().next();
    }

    List<SaftTransactionLine> lines = new ArrayList<>();
    BigDecimal balance = BigDecimal.ZERO;
    for (LineRow line : rows) {
      Party party = parties.get(line);
      SaftTransactionLine written = line(line, move, description, c);
      balance =
          Boolean.TRUE.equals(written.getDebit())
              ? balance.add(written.getAmount().getAmount())
              : balance.subtract(written.getAmount().getAmount());
      lines.add(
          written
              .customerId(
                  party == null ? customerId : party.side == Side.CUSTOMER ? party.id : NO_PARTY)
              .supplierId(
                  party == null ? supplierId : party.side == Side.SUPPLIER ? party.id : NO_PARTY));
    }
    if (balance.signum() != 0) {
      (c.withExcluded.contains(head.moveId) ? c.found.unbalancedExcluded : c.found.unbalanced)
          .add(move.withLabel(reference + " (Дт − Кт = " + balance.toPlainString() + ")"));
    }
    return new SaftTransaction()
        .transactionId(transactionId)
        .period(period.getMonthValue())
        .periodYear(period.getYear())
        .transactionDate(head.originDate != null ? head.originDate : head.date)
        .description(description)
        .systemEntryDate(entered)
        .glPostingDate(head.date)
        .customerId(customerId)
        .supplierId(supplierId)
        .lines(lines);
  }

  private static SaftTransactionLine line(
      LineRow line, BgSaftRef move, String transactionDescription, Context c) {
    String reference = move.getLabel();
    String recordId;
    if (line.counter != null && line.counter > 0) {
      recordId = line.counter.toString();
    } else {
      recordId = String.valueOf(line.id);
      c.found.noCounter.add(move);
    }

    String code = trim(line.accountCode) == null ? "#" + line.accountId : line.accountCode.trim();
    Resolution account = c.accounts.get(line.accountId);
    String accountId;
    if (account == null || account.getNraCode().isEmpty()) {
      c.found.unmapped.add(
          BgSaftRef.account(
              line.accountId,
              account == null ? code + " (извън сметкоплана на дружеството)" : code));
      accountId = "";
    } else {
      accountId = account.getNraCode();
    }

    BigDecimal raw = nonNull(line.debit).subtract(nonNull(line.credit));
    BigDecimal amount = raw.setScale(SCALE, RoundingMode.HALF_UP);
    if (raw.compareTo(amount) != 0) {
      c.found.rounded.add(move);
    }
    boolean debit = amount.signum() >= 0;
    if (debit) {
      c.totalDebit = c.totalDebit.add(amount);
    } else {
      c.totalCredit = c.totalCredit.add(amount.negate());
    }
    c.net.merge(code, amount, BigDecimal::add);

    String description = trim(line.description);
    SaftTransactionLine result =
        new SaftTransactionLine()
            .recordId(recordId)
            .accountId(accountId)
            .taxpayerAccountId(c.fit(code, MIDDLE2, move, reference + " TaxpayerAccountID"))
            .description(
                description == null
                    ? transactionDescription
                    : c.fit(description, LONG, move, reference + "/" + recordId + " Description"))
            .debit(debit)
            .amount(SaftAmount.eur(amount.abs()));
    Set<String> taxes = new TreeSet<>();
    for (String tax : line.taxCodes) {
      if (trim(tax) != null) {
        taxes.add(tax.trim());
      }
    }
    List<SaftTaxInformation> withheld =
        c.withholdings.getOrDefault(line.id, Collections.emptyList());
    if (taxes.isEmpty() && withheld.isEmpty()) {
      result.addTaxInformation(c.untaxedInformation(move, accountId));
    }
    for (String tax : taxes) {
      result.addTaxInformation(c.taxInformation(tax, move, accountId));
    }
    withheld.forEach(result::addTaxInformation);
    return result;
  }

  /** S.TI с TaxAmount 0.00 — сумата на данъка по ред е G5. {@code null} код значи 000000. */
  private static SaftTaxInformation taxInformation(String type, String code) {
    return new SaftTaxInformation()
        .taxType(type)
        .taxCode(code == null ? BgSaftTaxTableAssembler.NOT_APPLICABLE_CODE : code)
        .taxAmount(SaftAmount.eur(BigDecimal.ZERO.setScale(SCALE)));
  }

  // ─────────────── контекстът на едно сглобяване ───────────────

  /** Контрагентът на един ред и таблицата му. */
  private static final class Party {
    private final Side side;
    private final String id;

    private Party(Side side, String id) {
      this.side = side;
      this.id = id;
    }
  }

  private static final class Context {
    private final Map<Long, Resolution> accounts;
    private final Map<Long, PartyRow> partners;
    private final Map<String, BgSaftTaxTableAssembler.MappingRow> taxMappings;
    private final Map<String, BgNraNomenclatures.TaxCode> nomenclature;
    private final String ownId;
    private final Map<Long, List<SaftTaxInformation>> withholdings;
    private final YearMonth reportMonth;
    private final BgSaftDocumentCodes documents;
    private final Set<Long> withExcluded = new HashSet<>();
    private final Map<Long, String> partyIds = new HashMap<>();
    private final Findings found = new Findings();
    private final Map<String, BigDecimal> net = new TreeMap<>();
    private BigDecimal totalDebit = BigDecimal.ZERO.setScale(SCALE);
    private BigDecimal totalCredit = BigDecimal.ZERO.setScale(SCALE);

    private Context(
        Map<Long, Resolution> accounts,
        Map<Long, PartyRow> partners,
        Map<String, BgSaftTaxTableAssembler.MappingRow> taxMappings,
        Map<String, BgNraNomenclatures.TaxCode> nomenclature,
        String ownId,
        Map<Long, List<SaftTaxInformation>> withholdings,
        YearMonth reportMonth,
        BgSaftDocumentCodes documents) {
      this.withholdings = withholdings == null ? Collections.emptyMap() : withholdings;
      this.reportMonth = reportMonth;
      this.documents = documents == null ? BgSaftDocumentCodes.EMPTY : documents;
      this.accounts = accounts;
      this.partners = partners;
      this.taxMappings = taxMappings;
      this.nomenclature = nomenclature;
      this.ownId = ownId;
    }

    /**
     * Контрагентът на ред по сметка от група 40/41 — със същия идентификатор като в
     * Customers/Suppliers. Ред по друга сметка или без прочетен партньор няма такъв. Дали ID-то е в
     * таблиците, проверява {@link BgSaftReferenceCheck} над готовия файл (QA IV.2.4).
     */
    private Party party(LineRow line) {
      Resolution account = accounts.get(line.accountId);
      Side side =
          account == null || account.getNraCode().isEmpty()
              ? null
              : BgSaftPartiesAssembler.sideOf(account.getNraCode());
      PartyRow row = line.partnerId == null ? null : partners.get(line.partnerId);
      if (side == null || row == null) {
        return null;
      }
      String id =
          partyIds.computeIfAbsent(
              line.partnerId, k -> BgSaftPartiesAssembler.identify(row).getPartyId());
      return new Party(side, id);
    }

    /**
     * TaxInformation за един данък по правилата на TaxTable (G2c). Празен код и код, който чака
     * НАП, са ГРЕШКА за статията; в XML-а остава 000/000000, никога празен таг (QA IV.2.3).
     */
    private SaftTaxInformation taxInformation(String tax, BgSaftRef move, String accountNra) {
      String pending = BgSaftNapPending.TAXES.get(tax);
      if (pending != null) {
        found.napPending.add(
            move.withLabel(move.getLabel() + " — " + tax + ": " + BgSaftNapPending.reasonFor(tax)));
        return BgSaftGlEntriesAssembler.taxInformation(
            BgSaftTaxTableAssembler.NOT_APPLICABLE_TYPE, null);
      }
      BgSaftTaxTableAssembler.MappingRow mapping = taxMappings.get(tax);
      if (mapping == null) {
        found.taxUnmapped.add(BgSaftRef.text(tax));
        return BgSaftGlEntriesAssembler.taxInformation(
            BgSaftTaxTableAssembler.NOT_APPLICABLE_TYPE, null);
      }
      String code = trim(mapping.getSafTaxCode());
      if (code == null) {
        // Кодът зависи от сделката — този на документа на същата статия (протокол, фактура), за
        // да са дневникът и секцията един и същ код (решение от 15.10.2026). Без документ или с
        // нееднозначен код — ГРЕШКА.
        String documentCode = documents.codeFor(move.getId(), accountNra);
        BgNraNomenclatures.TaxCode documentRow =
            documentCode == null ? null : nomenclature.get(documentCode);
        if (documentRow == null) {
          found.taxToConfirm.add(move.withLabel(move.getLabel() + " — " + tax));
          return BgSaftGlEntriesAssembler.taxInformation(
              BgSaftTaxTableAssembler.NOT_APPLICABLE_TYPE, null);
        }
        found.taxAmountDeferred++;
        return BgSaftGlEntriesAssembler.taxInformation(trim(documentRow.taxType()), documentCode);
      }
      BgNraNomenclatures.TaxCode row = nomenclature.get(code);
      if (row == null) {
        found.taxInvalid.add(BgSaftRef.text(tax + " → „" + code + "“"));
        return BgSaftGlEntriesAssembler.taxInformation(
            BgSaftTaxTableAssembler.NOT_APPLICABLE_TYPE, null);
      }
      if (mapping.isDynamic()) {
        found.dynamic.merge(BgSaftRef.text(tax + " → " + code), 1, Integer::sum);
      }
      found.taxAmountDeferred++;
      return BgSaftGlEntriesAssembler.taxInformation(trim(row.taxType()), code);
    }

    /**
     * TaxInformation на ред без данък: кодът на протокола на статията по сметката на реда ({@link
     * BgSaftDocumentCodes#protocolCodeFor}), иначе 000/000000 — без находка, неприложимостта е
     * законна (Прил. № 3 т. IV.8.2).
     */
    private SaftTaxInformation untaxedInformation(BgSaftRef move, String accountNra) {
      String code = documents.protocolCodeFor(move.getId(), accountNra);
      BgNraNomenclatures.TaxCode row = code == null ? null : nomenclature.get(code);
      if (row == null) {
        return BgSaftGlEntriesAssembler.taxInformation(
            BgSaftTaxTableAssembler.NOT_APPLICABLE_TYPE, null);
      }
      found.taxAmountDeferred++;
      return BgSaftGlEntriesAssembler.taxInformation(trim(row.taxType()), code);
    }

    /** Съкращава до {@code max} знака: по-дългото схемата отказва. {@code null} остава. */
    private String fit(String value, int max, BgSaftRef move, String what) {
      if (value == null || value.codePointCount(0, value.length()) <= max) {
        return value;
      }
      found.truncated.add(move == null ? BgSaftRef.text(what) : move.withLabel(what));
      return value.substring(0, value.offsetByCodePoints(0, max));
    }
  }

  /** Находките, събрани по вид — по една на запис (статия, сметка или данък), подредени. */
  private static final class Findings {
    private final Map<BgSaftRef, Integer> excluded = new TreeMap<>();
    private final Set<BgSaftRef> unmapped = new TreeSet<>();
    private final Map<BgSaftRef, Integer> dynamic = new TreeMap<>();
    private final Set<BgSaftRef> taxToConfirm = new TreeSet<>();
    private final Set<BgSaftRef> napPending = new TreeSet<>();
    private final Set<BgSaftRef> taxUnmapped = new TreeSet<>();
    private final Set<BgSaftRef> taxInvalid = new TreeSet<>();
    private final Set<BgSaftRef> unbalanced = new TreeSet<>();
    private final Set<BgSaftRef> unbalancedExcluded = new TreeSet<>();
    private final Set<BgSaftRef> otherPeriod = new TreeSet<>();
    private final Set<BgSaftRef> noReference = new LinkedHashSet<>();
    private final Set<BgSaftRef> noDescription = new LinkedHashSet<>();
    private final Set<BgSaftRef> noPeriod = new LinkedHashSet<>();
    private final Set<BgSaftRef> noEntryDate = new LinkedHashSet<>();
    private final Set<BgSaftRef> noCounter = new LinkedHashSet<>();
    private final Set<BgSaftRef> rounded = new LinkedHashSet<>();
    private final Set<BgSaftRef> truncated = new LinkedHashSet<>();
    private int taxAmountDeferred;

    private List<BgDeclarationFileFinding> toFindings(boolean empty, int droppedMoves) {
      List<BgDeclarationFileFinding> findings = new ArrayList<>();
      if (empty) {
        findings.add(
            BgDeclarationFileFinding.warning(
                "SAFT_GLE_EMPTY",
                "Няма осчетоводена статия в периода — подадена е нулевата структура по QA IV.2.6"
                    + " (с. 98–99): NumberOfEntries 0, една транзакция с нули, AccountID 998,"
                    + " 000/000000."));
      }
      BgSaftRef.error(
          findings,
          "SAFT_GLE_UNMAPPED",
          "Редове по сметки без код по сметкоплана на НАП (GL.24) — AccountID е празен и файлът не"
              + " минава схемата: ",
          unmapped);
      BgSaftRef.error(
          findings,
          "SAFT_GLE_UNBALANCED",
          "Статия, чиито редове във файла не се балансират — дебитът и кредитът на транзакцията"
              + " трябва да са равни: ",
          unbalanced);
      BgSaftRef.error(
          findings,
          "SAFT_GLE_UNBALANCED_EXCLUDED",
          "Статия с редове по сметки извън SAF-T (напр. 9000 „Начален баланс“ или 9010"
              + " „Заключителен баланс“ — откриването и закриването на годината в Axelor), която"
              + " без тях не се балансира — не се изпуска тихо; редовете ѝ да се прехвърлят по"
              + " сметки от сметкоплана на НАП или статията да не е в периода: ",
          unbalancedExcluded);
      BgSaftRef.error(
          findings,
          "SAFT_GLE_TAX_TO_CONFIRM",
          "Ред с данък без код в BgSafTaxMapping („да се потвърди“) и без документ на статията"
              + " (протокол, фактура), от който кодът да се изведе еднозначно — празен TaxCode НАП"
              + " отказва (QA IV.2.3, с. 95): ",
          taxToConfirm);
      BgSaftRef.error(
          findings, "SAFT_GLE_NAP_PENDING", "Ред с данък, чийто код чака НАП: ", napPending);
      if (!excluded.isEmpty()) {
        BgSaftRef.counted(
            findings,
            "SAFT_GLE_EXCLUDED_ACCOUNT_LINES",
            "Редове по сметка извън SAF-T (структурна или задбалансова) — не влизат в дневника"
                + (droppedMoves > 0
                    ? " (" + droppedMoves + " статии без нито един останал ред отпадат)"
                    : "")
                + ": ",
            excluded,
            "реда");
      }
      BgSaftRef.counted(
          findings,
          "SAFT_GLE_TAX_DYNAMIC_DEFAULTED",
          "Данък, чиято клетка се смята на ред — подаден е подразбиращият се код от мапинга;"
              + " истинският се изчислява с документите (G5): ",
          dynamic,
          "реда");
      BgSaftRef.warning(
          findings,
          "SAFT_GLE_TAX_UNMAPPED",
          "Данък без ред в мапинга към TAX-IMP — редовете му носят 000/000000 (S.TI.1/2): ",
          taxUnmapped);
      BgSaftRef.warning(
          findings,
          "SAFT_GLE_TAX_CODE_INVALID",
          "Мапингът дава код, който го няма в TAX-IMP — редовете носят 000/000000: ",
          taxInvalid);
      if (taxAmountDeferred > 0) {
        findings.add(
            BgDeclarationFileFinding.warning(
                "SAFT_GLE_TAX_AMOUNT_DEFERRED",
                "S.TI.6 TaxAmount е 0.00 на "
                    + taxAmountDeferred
                    + " данъчни реда — сумата на данъка по ред се смята с документите (G5)."));
      }
      BgSaftRef.warning(
          findings,
          "SAFT_GLE_OTHER_PERIOD",
          "Статия с дата в месеца на файла, но с период от друг месец (приключване, 13-и период)"
              + " — Period и PeriodYear (GL.10/11) са по периода ѝ, QA с. 46: ",
          otherPeriod);
      BgSaftRef.warning(
          findings,
          "SAFT_GLE_NO_REFERENCE",
          "Статия без номер — TransactionID (GL.9) е id на статията: ",
          noReference);
      BgSaftRef.warning(
          findings,
          "SAFT_GLE_NO_DESCRIPTION",
          "Статия без описание и без документ — Description (GL.15) е празен: ",
          noDescription);
      BgSaftRef.warning(
          findings,
          "SAFT_GLE_NO_PERIOD",
          "Статия без период — Period и PeriodYear (GL.10/11) са от датата на статията: ",
          noPeriod);
      BgSaftRef.warning(
          findings,
          "SAFT_GLE_NO_ENTRY_DATE",
          "Статия без дата на въвеждане — SystemEntryDate (GL.17) е датата на статията: ",
          noEntryDate);
      BgSaftRef.warning(
          findings,
          "SAFT_GLE_RECORD_ID_FROM_ID",
          "Статия с редове без номер — RecordID (GL.23) е id на реда: ",
          noCounter);
      BgSaftRef.warning(
          findings,
          "SAFT_GLE_ROUNDED",
          "Статия с редове с трети знак след запетаята, закръглени HALF_UP до два — оборотът по"
              + " сметка може да се размине със салдата с центове: ",
          rounded);
      BgSaftRef.warning(
          findings,
          "SAFT_GLE_TRUNCATED",
          "Стойност, по-дълга от допустимото в схемата — съкратена: ",
          truncated);
      return findings;
    }
  }

  /**
   * Нулевата структура за период без статии — QA IV.2.6 (с. 98–99), дословно: журнал без JournalID,
   * транзакция „1“ с периода на файла, дати 1900-01-01, CustomerID/SupplierID „0“, един ред по 998
   * с 0.00 и 000/000000. Валутата е EUR, както в нулевата фактура. Без месец на отчета Period и
   * PeriodYear са 0 — като в нулевите плащания на QA.
   */
  static SaftJournal emptyJournal(YearMonth reportMonth) {
    BigDecimal zero = BigDecimal.ZERO.setScale(SCALE);
    List<SaftTransaction> transactions = new ArrayList<>();
    transactions.add(
        new SaftTransaction()
            .transactionId("1")
            .period(reportMonth == null ? 0 : reportMonth.getMonthValue())
            .periodYear(reportMonth == null ? 0 : reportMonth.getYear())
            .transactionDate(BgSaftInvoicesAssembler.EMPTY_DATE)
            .description(" ")
            .systemEntryDate(BgSaftInvoicesAssembler.EMPTY_DATE)
            .glPostingDate(BgSaftInvoicesAssembler.EMPTY_DATE)
            .customerId(NO_PARTY)
            .supplierId(NO_PARTY)
            .lines(
                Collections.singletonList(
                    new SaftTransactionLine()
                        .recordId("1")
                        .accountId(BgSaftInvoicesAssembler.EMPTY_ACCOUNT)
                        .taxpayerAccountId("0")
                        .customerId(NO_PARTY)
                        .supplierId(NO_PARTY)
                        .description(" ")
                        .debit(true)
                        .amount(SaftAmount.eur(zero))
                        .addTaxInformation(
                            taxInformation(BgSaftTaxTableAssembler.NOT_APPLICABLE_TYPE, null)))));
    return new SaftJournal().transactions(transactions);
  }

  // ─────────────── помощници ───────────────

  /** Редовете в статията — по номер, после по id. */
  private static final Comparator<LineRow> LINE_ORDER =
      Comparator.comparing(
              (LineRow line) -> line.counter, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(line -> line.id);

  /** Статиите — по журнал (код, после id), дата, номер и id. Сортира Java, не колацията. */
  private static final Comparator<LineRow> MOVE_ORDER =
      Comparator.comparing(
              (LineRow line) -> line.journalCode, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(line -> line.journalId, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(line -> line.date, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(line -> line.reference, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(line -> line.moveId);

  private static BigDecimal nonNull(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String firstNonBlank(String first, String second) {
    String value = trim(first);
    return value != null ? value : trim(second);
  }

  private static String trim(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }
}

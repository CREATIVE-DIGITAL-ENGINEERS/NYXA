package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.saft.BgSaftGlAccountsAssembler.AccountRow;
import com.axelor.apps.bglocalization.service.saft.BgSaftGlAccountsAssembler.Resolution;
import com.axelor.apps.bglocalization.service.saft.BgSaftGlEntriesAssembler.LineRow;
import com.axelor.apps.bglocalization.service.saft.BgSaftPartiesAssembler.PartyRow;
import com.axelor.apps.bglocalization.service.saft.BgSaftPartiesAssembler.Side;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAmount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftPayment;
import com.axelor.apps.bglocalization.service.saft.dto.SaftPaymentLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftPaymentSection;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * SourceDocuments/Payments (SD.P.*) ОТ РЕДОВЕТЕ НА СТАТИИТЕ-ПЛАЩАНИЯ — чиста функция.
 *
 * <p><b>Нула заявки.</b> Редовете идват от {@link BgSaftPaymentsReader} (тоест от {@link
 * BgSaftMoveScope#PAYMENT_LINES} — статиите на дневника по разплащателен журнал или с режим на
 * плащане), а сметкопланът, мапингите и контрагентите — от G2. Всяко правило тук се проверява с
 * числа.
 *
 * <p><b>⚠️ ЕДНА СТАТИЯ — ЕДНО ПЛАЩАНЕ; РЕДОВЕТЕ СА ПАРИЧНИТЕ.</b> PaymentLine е ред по сметка от
 * група 50 на НАП (SD.P.20: „посочват се сметки от група 50 със съответния DebitCreditIndicator и
 * CustomerID/SupplierID“). Плащане без парични средства — метод 02 — няма такъв ред: при механизъм
 * 99 редът е разчетът с подотчетното лице (422, QA с. 64–65), при прихващане и бартер — редовете по
 * сметките на клиента и доставчика (QA с. 62–63, 66). Статия САМО по парични сметки е превод между
 * собствени сметки или обмяна на валута и НЕ е плащане (QA с. 61). Статия без нито един такъв ред
 * отпада с предупреждение.
 *
 * <p><b>⚠️ ИМА ЛИ ПАРИЧЕН РЕД, ТОЙ ПЕЧЕЛИ.</b> Прихващане и доплащане в една статия (Дт 401 150 /
 * Кт 411 100 / Кт 503 50) дава само паричния ред, с предупреждение: QA (с. 62–63) иска отделна
 * структура за всеки метод, а реда „401 с дебитен индикатор 150“ изрично НЕ иска — разделянето на
 * сумите между методите е да се потвърди.
 *
 * <p><b>⚠️ DebitCreditIndicator Е ПОСОКАТА НА ПАРИТЕ.</b> D е входящо (дебит на паричната сметка),
 * C — изходящо (SD.P.26, QA с. 65–66). Сумата е дебит минус кредит на реда, по модул, закръглена до
 * 2 знака HALF_UP, с находка при трети знак — същото като дневника, тоест сторно с разменени страни
 * излиза със знака на счетоводното записване. AmountStructure е в EUR (CurrencyCode и
 * CurrencyAmount са задължителни и за EUR).
 *
 * <p><b>⚠️ NumberOfEntries (SD.P.1) Е БРОЯТ НА РЕДОВЕТЕ</b> (PaymentLine) — книгата и QA (с. 65) го
 * казват изрично, за разлика от дневника, където е броят на транзакциите. TotalDebit/TotalCredit
 * (SD.P.2/3) — сборът на закръглените суми с индикатор D, съответно C.
 *
 * <p><b>⚠️ PaymentMethod (SD.P.10) И PaymentMechanism (SD.P.32) — ОТ {@code BgSafPaymentMapping} ПО
 * РЕЖИМА НА СТАТИЯТА</b>, сверени с Nom_PaymentMethod като ДВОЙКА. PaymentMethod е задължителен и
 * празният е ГРЕШКА (решение 7 от 14.10.2026) — за всяко плащане: без режим, с режим без ред в
 * мапинга, с изключен режим и с метод извън номенклатурата. „Изключен“ режим (INC_OTH/OUT_OTH,
 * „друго постъпление/плащане“) е режим, който Nom_PaymentMethod не описва; статията с него пак е
 * плащане (има паричен ред), тоест влиза в Payments и е ГРЕШКА — режимът да се смени с конкретен.
 * Механизмът е незадължителен: двойка извън номенклатурата не се пише, с предупреждение.
 *
 * <p><b>⚠️ МЕСЕЦ БЕЗ ПЛАЩАНИЯ</b> — нулевата структура по QA IV.2.6 (с. 97–98), дословно, вместо
 * грешка (решение 3).
 *
 * <p><b>⚠️ CustomerID / SupplierID — ПО БЕЛЕЖКАТА НА SD.P.22/23.</b> Плащане към клиент носи
 * идентификатора му в CustomerID и „0“ в SupplierID; към доставчик — обратното; несвързано с
 * контрагент — идентификатора на ПОДАВАЩИЯ в двете. Контрагентът на паричния ред е този на статията
 * — партньорът на реда ѝ по сметка от група 40/41, със същия идентификатор като в
 * Customers/Suppliers ({@link BgSaftPartiesAssembler#identify}); ред по сметка на контрагент
 * (прихващане) носи своя. Статия с няколко контрагента от една страна носи подаващия, с
 * предупреждение. Агрегираните продажби без фактура (отчет по чл. 119 ЗДДС, Z-отчетите на
 * фискалното устройство) нямат контрагент, тоест излизат с подаващия — точно каквото QA (II.4.2)
 * иска за агрегираните данни.
 *
 * <p><b>⚠️ TransactionID (SD.P.8) Е ТОЗИ НА ДНЕВНИКА</b> — номерът на статията, без него id-то ѝ,
 * съкратен до 70 знака по същото правило (QA с. 66). PaymentRefNo (SD.P.5) е същият номер до 35
 * знака: книгата иска номера по банково извлечение, който Axelor не води отделно, „или ако липсва
 * такъв — номера, генериран от информационната система“ — да се потвърди. TransactionDate (SD.P.9)
 * е датата на осчетоводяване на плащането ({@code Move.date}), не на документа. SourceDocumentID
 * (SD.P.19) е документът на статията ({@code Move.origin} — при плащане по фактура номерът ѝ).
 *
 * <p><b>НЕ ТУК:</b> TaxInformation по реда (SD.P.28) и PaymentDocumentTotals (SD.P.17) са
 * незадължителни и не се подават — данъчната логика е G5.
 */
public final class BgSaftPaymentsAssembler {

  /** SD.P.26. */
  public static final String DEBIT = "D";

  public static final String CREDIT = "C";

  /** Групата „Парични средства“ в сметкоплана на НАП (SD.P.20). */
  static final String CASH_GROUP = "50";

  /** Методът „Прихващане“ — плащане без парични средства. */
  static final String METHOD_OFFSET = "02";

  /** Механизмът „Подотчетни лица“. */
  static final String MECHANISM_ACCOUNTABLE = "99";

  /** Разчетите с подотчетни лица — паричният ред на плащането през тях (QA с. 64–65). */
  static final String ACCOUNTABLE_ACCOUNT = "422";

  private static final int SCALE = 2;

  /** SAFmiddle1textType, SAFmiddle2textType, SAFlongtextType. */
  private static final int MIDDLE1 = 35;

  private static final int MIDDLE2 = 70;
  private static final int LONG = 256;

  private BgSaftPaymentsAssembler() {}

  /** Ред от {@code BgSafPaymentMapping}. */
  public static final class MappingRow {
    private final String methodCode;
    private final String mechanismCode;
    private final boolean excluded;

    public MappingRow(String methodCode, String mechanismCode, boolean excluded) {
      this.methodCode = methodCode;
      this.mechanismCode = mechanismCode;
      this.excluded = excluded;
    }
  }

  /** Плащанията и находките по пътя. */
  public static final class Result {
    private final SaftPaymentSection payments;
    private final List<BgDeclarationFileFinding> findings;

    private Result(SaftPaymentSection payments, List<BgDeclarationFileFinding> findings) {
      this.payments = payments;
      this.findings = Collections.unmodifiableList(findings);
    }

    public SaftPaymentSection getPayments() {
      return payments;
    }

    public List<BgDeclarationFileFinding> getFindings() {
      return findings;
    }
  }

  /**
   * @param lines редовете на статиите-плащания в периода
   * @param chart сметкопланът на дружеството
   * @param accountMappings {@code BgSafAccountMapping} по код на сметката
   * @param partners партньорите по id (същите като за Customers/Suppliers)
   * @param paymentMappings {@code BgSafPaymentMapping} по код на режима
   * @param mechanisms Nom_PaymentMethod ({@link BgNraNomenclatures#paymentMechanisms()})
   * @param ownId идентификаторът на подаващия — „10“ + ЕИК
   */
  public static Result assemble(
      Collection<LineRow> lines,
      Collection<AccountRow> chart,
      Map<String, BgSaftGlAccountsAssembler.MappingRow> accountMappings,
      Map<Long, PartyRow> partners,
      Map<String, MappingRow> paymentMappings,
      Map<String, Set<String>> mechanisms,
      String ownId) {
    Context c =
        new Context(
            BgSaftGlAccountsAssembler.resolutions(chart, accountMappings),
            partners,
            paymentMappings,
            mechanisms,
            ownId);

    // Статиите — без редовете по изключени сметки, както в дневника.
    Map<Long, List<LineRow>> byMove = new LinkedHashMap<>();
    for (LineRow line : lines) {
      Resolution account = c.accounts.get(line.accountId());
      if (account != null && account.isExcluded()) {
        continue;
      }
      byMove.computeIfAbsent(line.moveId(), k -> new ArrayList<>()).add(line);
    }
    List<List<LineRow>> moves = new ArrayList<>(byMove.values());
    moves.forEach(move -> move.sort(LINE_ORDER));
    moves.sort(Comparator.comparing((List<LineRow> move) -> move.get(0), MOVE_ORDER));

    List<SaftPayment> payments = new ArrayList<>();
    for (List<LineRow> move : moves) {
      SaftPayment payment = payment(move, c);
      if (payment != null) {
        payments.add(payment);
      }
    }
    if (payments.isEmpty()) {
      payments.add(emptyPayment());
    }
    SaftPaymentSection section =
        new SaftPaymentSection()
            .numberOfEntries(c.lineCount)
            .totalDebit(c.totalDebit)
            .totalCredit(c.totalCredit)
            .payments(payments);
    return new Result(section, c.found.toFindings(c.lineCount == 0));
  }

  /**
   * Нулевата структура за период без плащания — QA IV.2.6 (с. 97–98), дословно: PaymentRefNo,
   * Period, PeriodYear, TransactionID, PaymentMethod и Description „0“, дата 1900-01-01, един ред
   * по 998 с CustomerID/SupplierID „0“ и 0.00 с индикатор D. Валутата е EUR.
   */
  static SaftPayment emptyPayment() {
    BigDecimal zero = BigDecimal.ZERO.setScale(SCALE);
    return new SaftPayment()
        .paymentRefNo("0")
        .period(0)
        .periodYear(0)
        .transactionId("0")
        .transactionDate(BgSaftInvoicesAssembler.EMPTY_DATE)
        .paymentMethod("0")
        .description("0")
        .addLine(
            new SaftPaymentLine()
                .lineNumber("1")
                .accountId(BgSaftInvoicesAssembler.EMPTY_ACCOUNT)
                .customerId(BgSaftGlEntriesAssembler.NO_PARTY)
                .supplierId(BgSaftGlEntriesAssembler.NO_PARTY)
                .debitCreditIndicator(DEBIT)
                .paymentLineAmount(SaftAmount.eur(zero)));
  }

  // ─────────────── едно плащане ───────────────

  /** Плащането на една статия; {@code null}, когато статията не е плащане. */
  private static SaftPayment payment(List<LineRow> rows, Context c) {
    LineRow head = rows.get(0);
    String reference = trim(head.reference());
    String label = reference == null ? "#" + head.moveId() : reference;
    BgSaftRef move = BgSaftRef.move(head.moveId(), label);

    List<LineRow> cash = new ArrayList<>();
    for (LineRow line : rows) {
      if (c.nraCode(line).startsWith(CASH_GROUP)) {
        cash.add(line);
      }
    }
    if (cash.size() == rows.size()) {
      c.found.internalTransfers.add(move);
      return null;
    }

    Method method = c.method(head.paymentModeCode());
    List<LineRow> means = cash;
    if (cash.isEmpty() && METHOD_OFFSET.equals(method.code)) {
      boolean accountable = MECHANISM_ACCOUNTABLE.equals(method.mechanism);
      means = new ArrayList<>();
      for (LineRow line : rows) {
        String nraCode = c.nraCode(line);
        if (accountable
            ? ACCOUNTABLE_ACCOUNT.equals(nraCode)
            : BgSaftPartiesAssembler.sideOf(nraCode) != null) {
          means.add(line);
        }
      }
    }
    if (means.isEmpty()) {
      c.found.noMeans.add(move);
      return null;
    }
    c.record(method, head.paymentModeCode(), move);

    if (reference == null) {
      reference = String.valueOf(head.moveId());
      c.found.noReference.add(move);
    }
    String description = firstNonBlank(head.moveDescription(), head.origin());
    if (description == null) {
      c.found.noDescription.add(move);
      description = "";
    }
    LocalDate period = head.periodFrom() != null ? head.periodFrom() : head.date();
    String origin = c.fit(trim(head.origin()), MIDDLE1, move, label + " SourceDocumentID");

    // Контрагентът на статията — от редовете по сметки на клиенти и доставчици, както в дневника.
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
    if (customers.size() > 1 || suppliers.size() > 1) {
      c.found.severalParties.add(move);
      customerId = c.ownId;
      supplierId = c.ownId;
    } else if (customers.isEmpty() && suppliers.isEmpty()) {
      customerId = c.ownId;
      supplierId = c.ownId;
    } else {
      customerId = customers.isEmpty() ? BgSaftGlEntriesAssembler.NO_PARTY : first(customers);
      supplierId = suppliers.isEmpty() ? BgSaftGlEntriesAssembler.NO_PARTY : first(suppliers);
    }
    if (!cash.isEmpty()
        && (METHOD_OFFSET.equals(method.code) || !customers.isEmpty() && !suppliers.isEmpty())) {
      c.found.mixed.add(move);
    }

    SaftPayment payment =
        new SaftPayment()
            .paymentRefNo(c.fit(reference, MIDDLE1, move, "PaymentRefNo " + label))
            .period(period.getMonthValue())
            .periodYear(period.getYear())
            .transactionId(c.fit(reference, MIDDLE2, move, "TransactionID " + label))
            .transactionDate(head.date())
            .paymentMethod(method.code)
            .description(c.fit(description, LONG, move, label + " Description"))
            .paymentMechanism(method.mechanism);
    for (LineRow line : means) {
      Party own = parties.get(line);
      payment.addLine(
          line(line, move, origin, c)
              .customerId(
                  own == null
                      ? customerId
                      : own.side == Side.CUSTOMER ? own.id : BgSaftGlEntriesAssembler.NO_PARTY)
              .supplierId(
                  own == null
                      ? supplierId
                      : own.side == Side.SUPPLIER ? own.id : BgSaftGlEntriesAssembler.NO_PARTY));
    }
    return payment;
  }

  private static SaftPaymentLine line(LineRow line, BgSaftRef move, String origin, Context c) {
    String label = move.getLabel();
    BigDecimal raw = nonNull(line.debit()).subtract(nonNull(line.credit()));
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
    c.lineCount++;
    Integer counter = line.counter();
    String description = trim(line.description());
    return new SaftPaymentLine()
        .lineNumber(counter != null && counter > 0 ? counter.toString() : null)
        .sourceDocumentId(origin)
        .accountId(c.nraCode(line))
        .description(
            description == null
                ? null
                : c.fit(description, LONG, move, label + "/" + counter + " Description"))
        .debitCreditIndicator(debit ? DEBIT : CREDIT)
        .paymentLineAmount(SaftAmount.eur(amount.abs()));
  }

  // ─────────────── методът на плащането ───────────────

  /** Как е решен методът — по него се избира находката. */
  private enum Status {
    OK,
    NO_MODE,
    UNMAPPED,
    EXCLUDED,
    METHOD_INVALID,
    MECHANISM_INVALID
  }

  /**
   * PaymentMethod (празен, когато не е установен) и PaymentMechanism ({@code null} — не се пише).
   */
  private static final class Method {
    private final Status status;
    private final String code;
    private final String mechanism;

    private Method(Status status, String code, String mechanism) {
      this.status = status;
      this.code = code;
      this.mechanism = mechanism;
    }
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
    private final Map<String, MappingRow> paymentMappings;
    private final Map<String, Set<String>> mechanisms;
    private final String ownId;
    private final Map<Long, String> partyIds = new HashMap<>();
    private final Findings found = new Findings();
    private long lineCount;
    private BigDecimal totalDebit = BigDecimal.ZERO.setScale(SCALE);
    private BigDecimal totalCredit = BigDecimal.ZERO.setScale(SCALE);

    private Context(
        Map<Long, Resolution> accounts,
        Map<Long, PartyRow> partners,
        Map<String, MappingRow> paymentMappings,
        Map<String, Set<String>> mechanisms,
        String ownId) {
      this.accounts = accounts;
      this.partners = partners;
      this.paymentMappings = paymentMappings;
      this.mechanisms = mechanisms;
      this.ownId = ownId;
    }

    /** AccountID на реда по НАП — навит като в GeneralLedgerAccounts; празен без код. */
    private String nraCode(LineRow line) {
      Resolution account = accounts.get(line.accountId());
      return account == null ? "" : account.getNraCode();
    }

    /** Методът и механизмът по режима на статията — без находка; тя е след решението „плащане“. */
    private Method method(String paymentModeCode) {
      String mode = trim(paymentModeCode);
      if (mode == null) {
        return new Method(Status.NO_MODE, "", null);
      }
      MappingRow mapping = paymentMappings.get(mode);
      if (mapping == null) {
        return new Method(Status.UNMAPPED, "", null);
      }
      if (mapping.excluded) {
        return new Method(Status.EXCLUDED, "", null);
      }
      String code = trim(mapping.methodCode);
      Set<String> allowed = code == null ? null : mechanisms.get(code);
      if (allowed == null) {
        return new Method(Status.METHOD_INVALID, "", null);
      }
      String mechanism = trim(mapping.mechanismCode);
      if (mechanism != null && !allowed.contains(mechanism)) {
        return new Method(Status.MECHANISM_INVALID, code, null);
      }
      return new Method(Status.OK, code, mechanism);
    }

    /** Находката за метода на едно включено плащане. */
    private void record(Method method, String paymentModeCode, BgSaftRef move) {
      String mode = trim(paymentModeCode);
      switch (method.status) {
        case NO_MODE:
          found.noMode.add(move);
          break;
        case UNMAPPED:
          found.unmapped.add(move.withLabel(move.getLabel() + " — режим " + mode));
          break;
        case EXCLUDED:
          found.excluded.add(move.withLabel(move.getLabel() + " — режим " + mode));
          break;
        case METHOD_INVALID:
          found.methodInvalid.add(
              move.withLabel(
                  move.getLabel()
                      + " — режим "
                      + mode
                      + " → „"
                      + paymentMappings.get(mode).methodCode
                      + "“"));
          break;
        case MECHANISM_INVALID:
          MappingRow mapping = paymentMappings.get(mode);
          found.mechanismInvalid.add(
              BgSaftRef.text(mode + " → " + mapping.methodCode + "/" + mapping.mechanismCode));
          break;
        default:
          break;
      }
    }

    /**
     * Контрагентът на ред по сметка от група 40/41 — със същия идентификатор като в
     * Customers/Suppliers. Ред по друга сметка или без прочетен партньор няма такъв.
     */
    private Party party(LineRow line) {
      Side side = BgSaftPartiesAssembler.sideOf(nraCode(line));
      PartyRow row = line.partnerId() == null ? null : partners.get(line.partnerId());
      if (side == null || row == null) {
        return null;
      }
      String id =
          partyIds.computeIfAbsent(
              line.partnerId(), k -> BgSaftPartiesAssembler.identify(row).getPartyId());
      return new Party(side, id);
    }

    /** Съкращава до {@code max} знака: по-дългото схемата отказва. {@code null} остава. */
    private String fit(String value, int max, BgSaftRef move, String what) {
      if (value == null || value.codePointCount(0, value.length()) <= max) {
        return value;
      }
      found.truncated.add(move.withLabel(what));
      return value.substring(0, value.offsetByCodePoints(0, max));
    }
  }

  /** Находките, събрани по вид — по една на статия (или на режим при механизма). */
  private static final class Findings {
    private final Set<BgSaftRef> noMode = new LinkedHashSet<>();
    private final Set<BgSaftRef> unmapped = new LinkedHashSet<>();
    private final Set<BgSaftRef> excluded = new LinkedHashSet<>();
    private final Set<BgSaftRef> methodInvalid = new LinkedHashSet<>();
    private final Set<BgSaftRef> mechanismInvalid = new TreeSet<>();
    private final Set<BgSaftRef> internalTransfers = new LinkedHashSet<>();
    private final Set<BgSaftRef> noMeans = new LinkedHashSet<>();
    private final Set<BgSaftRef> mixed = new LinkedHashSet<>();
    private final Set<BgSaftRef> severalParties = new LinkedHashSet<>();
    private final Set<BgSaftRef> noReference = new LinkedHashSet<>();
    private final Set<BgSaftRef> noDescription = new LinkedHashSet<>();
    private final Set<BgSaftRef> rounded = new LinkedHashSet<>();
    private final Set<BgSaftRef> truncated = new LinkedHashSet<>();

    private List<BgDeclarationFileFinding> toFindings(boolean empty) {
      List<BgDeclarationFileFinding> findings = new ArrayList<>();
      if (empty) {
        findings.add(
            BgDeclarationFileFinding.warning(
                "SAFT_PAY_EMPTY",
                "Няма плащане в периода — подадена е нулевата структура по QA IV.2.6 (с. 97–98):"
                    + " NumberOfEntries 0, едно Payment с „0“, 1900-01-01 и ред по 998 с 0.00."));
      }
      BgSaftRef.error(
          findings,
          "SAFT_PAY_METHOD_INVALID",
          "Мапингът дава метод на плащане извън Nom_PaymentMethod — PaymentMethod (SD.P.10) е"
              + " празен, а НАП го сверява с номенклатурата: ",
          methodInvalid);
      BgSaftRef.error(
          findings,
          "SAFT_PAY_NO_PAYMENT_MODE",
          "Плащане без режим на плащане — PaymentMethod (SD.P.10) е задължителен и не може да е"
              + " празен; да се избере режим: ",
          noMode);
      BgSaftRef.error(
          findings,
          "SAFT_PAY_METHOD_UNMAPPED",
          "Плащане с режим без ред в мапинга към Nom_PaymentMethod — PaymentMethod (SD.P.10) не"
              + " може да е празен; да се допълни BgSafPaymentMapping: ",
          unmapped);
      BgSaftRef.error(
          findings,
          "SAFT_PAY_METHOD_EXCLUDED",
          "Плащане с режим „друго“, който Nom_PaymentMethod не описва (INC_OTH/OUT_OTH) —"
              + " PaymentMethod (SD.P.10) не може да е празен; режимът да се смени с конкретен: ",
          excluded);
      BgSaftRef.warning(
          findings,
          "SAFT_PAY_MECHANISM_INVALID",
          "Механизъм, който не е двойка с метода си в Nom_PaymentMethod — PaymentMechanism"
              + " (SD.P.32) не се подава: ",
          mechanismInvalid);
      BgSaftRef.warning(
          findings,
          "SAFT_PAY_INTERNAL_TRANSFER",
          "Статия само по парични сметки (група 50) — превод между собствени сметки или обмяна на"
              + " валута, не плащане (QA с. 61); не влиза в Payments: ",
          internalTransfers);
      BgSaftRef.warning(
          findings,
          "SAFT_PAY_NO_MEANS_LINE",
          "Статия по разплащателен журнал или с режим на плащане без ред по парична сметка (група"
              + " 50), а при прихващане — без ред по 422 или по сметка на контрагент; не влиза в"
              + " Payments: ",
          noMeans);
      BgSaftRef.warning(
          findings,
          "SAFT_PAY_MIXED_SETTLEMENT",
          "Статия с паричен ред и с прихващане (режим с метод 02 или клиент и доставчик в една"
              + " статия) — описани са само паричните редове; QA (с. 62–63) иска отделна структура"
              + " за всеки метод, разделянето на сумите — да се потвърди: ",
          mixed);
      BgSaftRef.warning(
          findings,
          "SAFT_PAY_SEVERAL_PARTIES",
          "Плащане към няколко контрагента от една страна в една статия — CustomerID/SupplierID"
              + " на паричния ред е подаващият; разделяне по контрагент — да се потвърди: ",
          severalParties);
      BgSaftRef.warning(
          findings,
          "SAFT_PAY_NO_REFERENCE",
          "Плащане без номер на статията — TransactionID и PaymentRefNo (SD.P.8/5) са id на"
              + " статията: ",
          noReference);
      BgSaftRef.warning(
          findings,
          "SAFT_PAY_NO_DESCRIPTION",
          "Плащане без описание и без документ — Description (SD.P.11) е празен: ",
          noDescription);
      BgSaftRef.warning(
          findings,
          "SAFT_PAY_ROUNDED",
          "Плащане с редове с трети знак след запетаята, закръглени HALF_UP до два: ",
          rounded);
      BgSaftRef.warning(
          findings,
          "SAFT_PAY_TRUNCATED",
          "Стойност, по-дълга от допустимото в схемата — съкратена: ",
          truncated);
      return findings;
    }
  }

  // ─────────────── помощници ───────────────

  /** Редовете в статията — по номер, после по id. */
  private static final Comparator<LineRow> LINE_ORDER =
      Comparator.comparing(
              (LineRow line) -> line.counter(), Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing((LineRow line) -> line.id());

  /** Плащанията — по дата, номер и id. Сортира Java, не колацията. */
  private static final Comparator<LineRow> MOVE_ORDER =
      Comparator.comparing(
              (LineRow line) -> line.date(), Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(
              (LineRow line) -> line.reference(), Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing((LineRow line) -> line.moveId());

  private static String first(Set<String> values) {
    return values.iterator().next();
  }

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

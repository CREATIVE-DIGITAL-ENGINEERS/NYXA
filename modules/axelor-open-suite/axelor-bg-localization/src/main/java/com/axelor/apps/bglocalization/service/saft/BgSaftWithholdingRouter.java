package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.accounting.BgPayrollAccount;
import com.axelor.apps.bglocalization.service.accounting.BgPayrollPosting;
import com.axelor.apps.bglocalization.service.accounting.BgPayrollPostingEntry;
import com.axelor.apps.bglocalization.service.accounting.BgPayrollPostingPlan;
import com.axelor.apps.bglocalization.service.saft.BgSaftGlEntriesAssembler.LineRow;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAmount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxInformation;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import com.axelor.apps.bglocalization.service.zddfl.BgIncomeTaxRequest;
import com.axelor.apps.bglocalization.service.zddfl.BgIncomeTaxResolver;
import com.axelor.apps.bglocalization.service.zddfl.BgIncomeTaxResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * КОДОВЕТЕ 600 И 700 НА РЕДА ОТ ДНЕВНИКА (GL.33, G3b) — чиста функция.
 *
 * <p><b>Правилото на НАП.</b> Прил. 3 т. IV.8.2: в TaxInformation на реда „лицето подаващо файла,
 * посочва съответния данъчен код“, а кодовете за неприложимост се допускат само „когато
 * транзакцията не е свързана с начисляването на вид на данъци 600 … и 700“. QA (с. 51–56) уточнява
 * КЪДЕ: кодът за данък върху разходите е при начисляването на РАЗХОДА, не на дължимия данък — и в
 * примера стои на двата реда на разходната статия (401 и 60909); при гражданските договори 700500
 * стои „във всяка линия … отразяваща начисленото възнаграждение“; социалните разходи в натура под
 * лимита носят 600020 с TaxAmount „0,00“, а паричните, които са доход на лицето — 700500.
 *
 * <p><b>⚠️ ТРИ ИЗТОЧНИКА, ЕДИН ПРИОРИТЕТ.</b>
 *
 * <ol>
 *   <li><b>Ръчният код на реда</b> ({@code MoveLine.bgSaftTaxCode}), после <b>подразбирането на
 *       сметката</b> ({@code Account.bgSaftTaxCode}). Редът бие сметката, а и двете бият
 *       автоматиката. Само кодове от TAX-IMP вид 600/700 — друг код е ГРЕШКА и се игнорира.
 *   <li><b>Фишът</b> — статията ({@code BgPaySlip.bgPayrollMove}) се разчита с СЪЩИЯ {@link
 *       BgPayrollPostingPlan}, който я е построил: така се знае кое перо в кой ред на статията е
 *       влязло, въпреки че перата се сливат по сметка (всички 2xx са един ред Dr 6094).
 *   <li><b>Фактурата за покупка от физическо лице</b> — по {@code InvoiceLine.bgIncomeType} и
 *       картата {@code BgSafWithholdingMapping}. Axelor не пази реда на фактурата върху реда на
 *       статията, затова връзката е по СМЕТКА.
 * </ol>
 *
 * <p><b>⚠️ TaxAmount — ДАНЪКЪТ Е НА РАЗХОДНИЯ РЕД, НАСРЕЩНИЯТ НОСИ 0.00.</b> Примерът на QA (с.
 * 54–55) показва кода на двата реда, но не и сумата; данъкът на ДВАТА реда би се удвоил при всяко
 * сумиране. Да се потвърди с тестовата е-услуга на НАП. Данък за няколко разходни реда се дели по
 * най-големия остатък — същото правило като G5 (фактурите).
 *
 * <p><b>⚠️ РЕДЪТ НА САМИЯ ДАНЪК ОСТАВА 000.</b> Dr 4211 / Cr 454x и Dr 60602 / Cr 456 не са
 * разходът. Изключението (600040 при данъчна амортизация, QA с. 51–53 — „документа по чл. 6, ал. 3
 * от ЗСч, с който се начислява дължимия данък“) идва само с ръчен код на такъв документ.
 */
public final class BgSaftWithholdingRouter {

  static final String TYPE_EXPENSE = "600";
  static final String TYPE_SOURCE = "700";

  /** 600020 — данък върху социалните разходи, предоставени в натура (10 %). */
  static final String SOCIAL_IN_KIND = "600020";

  /** 600030 — социалните разходи в натура по ЗКПО чл. 210 ал. 1 (0 %). */
  static final String SOCIAL_ART_210 = "600030";

  /** 600010 — данък върху представителните разходи. */
  static final String REPRESENTATION = "600010";

  /** 700500 — авансовият данък, удържан от платеца (ЗДДФЛ). */
  static final String ADVANCE = "700500";

  /** 700520 — авансовият данък на морско лице (1 на сто, ЗДДФЛ чл. 42 ал. 4). */
  static final String SEAFARER = "700520";

  /**
   * Неприспаднатият ДДС като разход. Решение 9 (12.10.2026): ЗКПО чл. 211 — основата са
   * „начислените разходи“, а неприспаднатият ДДС е начислен разход за същата цел, тоест носи 600010
   * заедно с представителния разход. Законът не споменава ДДС изрично — да се потвърди.
   */
  static final String NON_DEDUCTIBLE_VAT = "6029";

  /**
   * Разходите за възнаграждения, по които се разпознава трудовото възнаграждение в плана: 604 (1xx
   * без БРЗ), 6041, 6042 и 6043 (обезщетенията по КТ).
   */
  static final Set<String> REMUNERATION_ACCOUNTS =
      new LinkedHashSet<>(
          Arrays.asList(
              BgPayrollAccount.EXPENSE_WAGE,
              BgPayrollAccount.EXPENSE_BASIC_WAGE,
              BgPayrollAccount.EXPENSE_ADDITIONAL_WAGE,
              BgPayrollAccount.EXPENSE_KT_COMPENSATION));

  /**
   * Перата 2xx в натура, обложени по ЗКПО → 600020. Капачките (200–205 по чл. 208, 210–211 по чл.
   * 209а) — и в рамките, и превишението; 261 (чл. 210 ал. 2) и 270 — изцяло.
   */
  static final Set<Integer> SOCIAL_TAXED_IN_KIND =
      new TreeSet<>(Arrays.asList(200, 201, 202, 203, 204, 205, 210, 211, 261, 270));

  /**
   * Перата, които НОСЯТ данъчната основа на 600020 — превишенията и изцяло облаганите. Перата „в
   * рамките на капачката“ носят кода с 0.00. ⚠️ Основата идва от фиша ({@code
   * bgSocialExpenseTaxBase}), не оттук: при неизпълнено условие тя е целият размер (ЗКПО чл. 213
   * ал. 3, чл. 214 ал. 3) — тези тегла само я разпределят.
   */
  static final Set<Integer> SOCIAL_TAX_BEARING =
      new TreeSet<>(Arrays.asList(201, 203, 205, 211, 261, 270));

  /** 260 и 262 → 600030: освобождаването е по ЗКПО чл. 210 ал. 1 (ал. 3 препраща към нея). */
  static final Set<Integer> SOCIAL_ART_210_LINES = new TreeSet<>(Arrays.asList(260, 262));

  /**
   * Социалните разходи в ПАРИ, доход на лицето → 700500 (QA с. 54; ЗКПО чл. 205): еднократните
   * помощи 220–225, помощта за лечение 250 и 271.
   */
  static final Set<Integer> SOCIAL_PERSONAL_INCOME =
      new TreeSet<>(Arrays.asList(220, 221, 222, 223, 224, 225, 250, 271));

  /**
   * Перата от 2xx, които НЕ са социален разход по ЗКПО чл. 204 ал. 1 т. 2 — облекло по КТ чл. 296,
   * храна по КТ чл. 285, задължителни застраховки (виж {@code
   * BgPaySlipLineType.bgIsCompanyExpenseTaxed}).
   */
  static final Set<Integer> NOT_SOCIAL_EXPENSE =
      new TreeSet<>(Arrays.asList(230, 231, 232, 240, 245));

  private static final int SCALE = 2;
  private static final BigDecimal CENT = new BigDecimal("0.01");

  private BgSaftWithholdingRouter() {}

  // ═══════════════════════════ ВХОДОВЕТЕ ═══════════════════════════

  /** Ред от {@code BgSafWithholdingMapping}: вид доход → код. */
  public static final class Mapping {
    public static final String CODE = "CODE";
    public static final String NONE = "NONE";
    public static final String AMBIGUOUS = "AMBIGUOUS";

    private final String safTaxCode;
    private final String rule;

    public Mapping(String safTaxCode, String rule) {
      this.safTaxCode = safTaxCode;
      this.rule = rule;
    }

    public String getSafTaxCode() {
      return safTaxCode;
    }

    public String getRule() {
      return rule;
    }
  }

  /** Статия от фиш: перата (замразените копия), видът осигурен и ЗКПО основата на фиша. */
  public static final class PayrollMove {
    private final String reference;
    private final List<BgPaySlipLine> lines;
    private final String insuredTypeCode;
    private final boolean seafarer;
    private BigDecimal socialExpenseTaxBase = BigDecimal.ZERO;

    public PayrollMove(
        String reference, List<BgPaySlipLine> lines, String insuredTypeCode, boolean seafarer) {
      this.reference = reference;
      this.lines = lines == null ? Collections.emptyList() : lines;
      this.insuredTypeCode = insuredTypeCode;
      this.seafarer = seafarer;
    }

    /** {@code BgPaySlip.bgSocialExpenseTaxBase} — месечната ЗКПО основа на социалните разходи. */
    public PayrollMove socialExpenseTaxBase(BigDecimal value) {
      this.socialExpenseTaxBase = value == null ? BigDecimal.ZERO : value;
      return this;
    }
  }

  /** Ред на фактура с вид доход — сметката, видът и брутото (без ДДС). */
  public static final class IncomeLine {
    private final String accountCode;
    private final String incomeTypeCode;
    private final String taxPattern;
    private final BigDecimal expenseRate;
    private final BigDecimal finalTaxRate;
    private final BigDecimal advanceTaxRate;
    private final boolean withheldByPayer;
    private final BigDecimal gross;

    public IncomeLine(
        String accountCode,
        String incomeTypeCode,
        String taxPattern,
        BigDecimal expenseRate,
        BigDecimal finalTaxRate,
        BigDecimal advanceTaxRate,
        boolean withheldByPayer,
        BigDecimal gross) {
      this.accountCode = accountCode;
      this.incomeTypeCode = incomeTypeCode;
      this.taxPattern = taxPattern;
      this.expenseRate = expenseRate;
      this.finalTaxRate = finalTaxRate;
      this.advanceTaxRate = advanceTaxRate;
      this.withheldByPayer = withheldByPayer;
      this.gross = gross;
    }
  }

  /**
   * Статия от фактура за покупка с поне един ред с вид доход.
   *
   * @param selfInsuredDeclared получателят е декларирал по ЗДДФЛ чл. 43 ал. 5 към датата на
   *     фактурата
   * @param foreign регистърът на доходите го води като чуждестранно лице ({@code isForeign})
   */
  public static final class InvoiceMove {
    private final String reference;
    private final boolean selfInsuredDeclared;
    private final boolean foreign;
    private final List<IncomeLine> lines;

    public InvoiceMove(
        String reference, boolean selfInsuredDeclared, boolean foreign, List<IncomeLine> lines) {
      this.reference = reference;
      this.selfInsuredDeclared = selfInsuredDeclared;
      this.foreign = foreign;
      this.lines = lines == null ? Collections.emptyList() : lines;
    }
  }

  /** Кодовете по ред и находките. */
  public static final class Result {
    private final Map<Long, List<SaftTaxInformation>> byLine;
    private final List<BgDeclarationFileFinding> findings;

    private Result(
        Map<Long, List<SaftTaxInformation>> byLine, List<BgDeclarationFileFinding> findings) {
      this.byLine = Collections.unmodifiableMap(byLine);
      this.findings = Collections.unmodifiableList(findings);
    }

    /** Празен резултат — без кодове и без находки. */
    public static Result empty() {
      return new Result(Collections.emptyMap(), Collections.emptyList());
    }

    /** Само редовете, които носят поне един код. */
    public Map<Long, List<SaftTaxInformation>> getByLine() {
      return byLine;
    }

    public List<SaftTaxInformation> of(Long lineId) {
      return byLine.getOrDefault(lineId, Collections.emptyList());
    }

    public List<BgDeclarationFileFinding> getFindings() {
      return findings;
    }
  }

  // ═══════════════════════════ ВХОДНАТА ТОЧКА ═══════════════════════════

  /**
   * @param lines редовете на дневника (същите като за GeneralLedgerEntries)
   * @param payroll статиите от фишове, по id на статията
   * @param invoices статиите от фактури за покупка с вид доход, по id на статията
   * @param mappings {@code BgSafWithholdingMapping} по код на вида доход
   * @param nomenclature TAX-IMP ({@link BgNraNomenclatures#taxCodes()})
   */
  public static Result route(
      Collection<LineRow> lines,
      Map<Long, PayrollMove> payroll,
      Map<Long, InvoiceMove> invoices,
      Map<String, Mapping> mappings,
      Map<String, BgNraNomenclatures.TaxCode> nomenclature) {
    Context c = new Context(mappings, nomenclature);
    Map<Long, List<LineRow>> byMove = new LinkedHashMap<>();
    for (LineRow line : lines) {
      byMove.computeIfAbsent(line.moveId(), k -> new ArrayList<>()).add(line);
    }
    for (Map.Entry<Long, List<LineRow>> move : byMove.entrySet()) {
      List<LineRow> rows = move.getValue();
      rows.sort(Comparator.comparing(LineRow::id));
      Map<Long, Map<String, BigDecimal>> coded = new HashMap<>();
      Map<Long, String> own = own(rows, c);
      PayrollMove slip = payroll.get(move.getKey());
      InvoiceMove invoice = invoices.get(move.getKey());
      if (slip != null) {
        payroll(rows, slip, coded, c);
      } else if (invoice != null) {
        invoice(rows, invoice, own, coded, c);
      }
      manual(rows, own, coded, c);
      for (LineRow row : rows) {
        Map<String, BigDecimal> codes = coded.get(row.id());
        if (codes == null || codes.isEmpty()) {
          continue;
        }
        List<SaftTaxInformation> out = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : codes.entrySet()) {
          out.add(
              new SaftTaxInformation()
                  .taxType(nomenclature.get(e.getKey()).taxType())
                  .taxCode(e.getKey())
                  .taxAmount(SaftAmount.eur(e.getValue().setScale(SCALE, RoundingMode.HALF_UP))));
        }
        c.byLine.put(row.id(), out);
      }
    }
    return new Result(c.byLine, c.findings());
  }

  // ═══════════════════════════ РЪЧЕН КОД И СМЕТКА ═══════════════════════════

  /** Кодът на реда: ръчният, после на сметката; само валидни 600/700, иначе ГРЕШКА. */
  private static Map<Long, String> own(List<LineRow> rows, Context c) {
    Map<Long, String> own = new HashMap<>();
    for (LineRow row : rows) {
      String code = trim(row.saftTaxCode());
      if (code == null) {
        code = trim(row.accountSaftTaxCode());
      }
      if (code == null) {
        continue;
      }
      BgNraNomenclatures.TaxCode tax = c.nomenclature.get(code);
      if (tax == null
          || !(TYPE_EXPENSE.equals(tax.taxType()) || TYPE_SOURCE.equals(tax.taxType()))) {
        c.invalid.add(BgSaftRef.move(row.moveId(), ref(row) + " / „" + code + "“"));
        continue;
      }
      own.put(row.id(), code);
    }
    return own;
  }

  /**
   * Редовете с собствен код носят САМО него, с данък по ставката от TAX-IMP върху сумата си. 6029
   * от същата страна до 600010 — същото. Насрещните редове (другата страна, без собствен код)
   * получават кодовете с 0.00 — „кодът на всички редове на разходната статия“ (QA с. 54–55).
   */
  private static void manual(
      List<LineRow> rows,
      Map<Long, String> own,
      Map<Long, Map<String, BigDecimal>> coded,
      Context c) {
    if (own.isEmpty()) {
      return;
    }
    Set<String> debitCodes = new TreeSet<>();
    Set<String> creditCodes = new TreeSet<>();
    for (LineRow row : rows) {
      String code = own.get(row.id());
      if (code == null) {
        continue;
      }
      Map<String, BigDecimal> only = new TreeMap<>();
      only.put(code, c.tax(code, amount(row).abs()));
      coded.put(row.id(), only);
      (isDebit(row) ? debitCodes : creditCodes).add(code);
    }
    for (LineRow row : rows) {
      if (own.containsKey(row.id())) {
        continue;
      }
      Set<String> sameSide = isDebit(row) ? debitCodes : creditCodes;
      if (startsWith(row.accountCode(), NON_DEDUCTIBLE_VAT) && sameSide.contains(REPRESENTATION)) {
        put(coded, row.id(), REPRESENTATION, c.tax(REPRESENTATION, amount(row).abs()));
        continue;
      }
      for (String code : isDebit(row) ? creditCodes : debitCodes) {
        coded.computeIfAbsent(row.id(), k -> new TreeMap<>()).putIfAbsent(code, zero());
      }
    }
  }

  // ═══════════════════════════ ФИШ ═══════════════════════════

  /** Вид на перото за G3b. */
  private enum Kind {
    REMUNERATION,
    SOCIAL_TAXED,
    SOCIAL_ART_210,
    NONE,
    UNKNOWN
  }

  private static void payroll(
      List<LineRow> rows, PayrollMove slip, Map<Long, Map<String, BigDecimal>> coded, Context c) {
    String where = slip.reference + " (" + ref(rows.get(0)) + ")";
    c.current = BgSaftRef.move(rows.get(0).moveId(), where);
    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(slip.lines, slip.insuredTypeCode);
    if (!plan.isPostable()) {
      c.planRefused.add(c.ref(where));
      return;
    }

    Set<Integer> taxable = new HashSet<>();
    BigDecimal withheld = BigDecimal.ZERO;
    for (BgPaySlipLine line : slip.lines) {
      if (line == null || !BgPaySlipResolver.isOwnPeriod(line)) {
        continue;
      }
      if (Boolean.TRUE.equals(line.getBgIsTaxable())) {
        taxable.add(line.getBgLineNumber());
      }
      if (line.getBgLineNumber() == BgPayrollPostingPlan.LINE_TAX
          && BgPaySlipResolver.DEBIT.equals(line.getBgSign())) {
        withheld = withheld.add(line.getBgAmount());
      }
    }

    // Перата → кодове → тегла по разходния ред и насрещните ключове.
    String remuneration = slip.seafarer ? SEAFARER : ADVANCE;
    Map<String, Map<String, BigDecimal>> weights = new TreeMap<>();
    Map<String, Set<String>> counterparts = new TreeMap<>();
    Set<String> unknown = new TreeSet<>();
    for (BgPayrollPostingEntry entry : plan.getEntries()) {
      Kind kind = kindOf(entry);
      if (kind == Kind.UNKNOWN) {
        unknown.add(String.valueOf(entry.getLineNumber()));
        continue;
      }
      String code;
      BigDecimal weight;
      switch (kind) {
        case REMUNERATION:
          code = remuneration;
          weight = taxable.contains(entry.getLineNumber()) ? entry.getAmount() : BigDecimal.ZERO;
          break;
        case SOCIAL_TAXED:
          code = SOCIAL_IN_KIND;
          weight =
              SOCIAL_TAX_BEARING.contains(entry.getLineNumber())
                  ? entry.getAmount()
                  : BigDecimal.ZERO;
          break;
        case SOCIAL_ART_210:
          code = SOCIAL_ART_210;
          weight = BigDecimal.ZERO;
          break;
        default:
          continue;
      }
      weights
          .computeIfAbsent(code, k -> new TreeMap<>())
          .merge(key(entry.getDebitAccount(), true), weight, BigDecimal::add);
      counterparts
          .computeIfAbsent(code, k -> new TreeSet<>())
          .add(key(entry.getCreditAccount(), false));
    }
    if (!unknown.isEmpty()) {
      c.unknownLines.add(c.ref(where + ": " + String.join(", ", unknown)));
      return;
    }

    Map<String, List<LineRow>> byKey = new HashMap<>();
    Map<String, BigDecimal> actual = new TreeMap<>();
    for (LineRow row : rows) {
      String k = key(row.accountCode(), isDebit(row));
      byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(row);
      actual.merge(k, amount(row).abs(), BigDecimal::add);
    }
    Map<String, BigDecimal> planned = new TreeMap<>();
    for (BgPayrollPosting posting : plan.getPostings()) {
      planned.merge(
          key(posting.getAccountCode(), posting.isDebit()), posting.getAmount(), BigDecimal::add);
    }
    if (!sameAmounts(planned, actual)) {
      c.drift.add(c.ref(where));
    }

    for (Map.Entry<String, Map<String, BigDecimal>> perCode : weights.entrySet()) {
      String code = perCode.getKey();
      BigDecimal total;
      if (SOCIAL_IN_KIND.equals(code)) {
        total = c.tax(code, slip.socialExpenseTaxBase);
      } else if (SOCIAL_ART_210.equals(code)) {
        total = zero();
      } else {
        total = withheld.setScale(SCALE, RoundingMode.HALF_UP);
      }
      // Разходният ред на всеки ключ — най-големият по сума, когато ключът има няколко реда.
      List<LineRow> targets = new ArrayList<>();
      List<BigDecimal> keyWeights = new ArrayList<>();
      for (Map.Entry<String, BigDecimal> w : perCode.getValue().entrySet()) {
        List<LineRow> candidates = byKey.get(w.getKey());
        if (candidates == null) {
          continue;
        }
        targets.add(largest(candidates));
        keyWeights.add(w.getValue());
      }
      List<BigDecimal> shares = allocate(keyWeights, total);
      for (int i = 0; i < targets.size(); i++) {
        put(coded, targets.get(i).id(), code, shares.get(i));
      }
      for (String counterpart : counterparts.get(code)) {
        for (LineRow row : byKey.getOrDefault(counterpart, Collections.emptyList())) {
          coded.computeIfAbsent(row.id(), k -> new TreeMap<>()).putIfAbsent(code, zero());
        }
      }
    }
  }

  private static Kind kindOf(BgPayrollPostingEntry entry) {
    int number = entry.getLineNumber();
    if (number / 100 == 2) {
      if (SOCIAL_TAXED_IN_KIND.contains(number)) {
        return Kind.SOCIAL_TAXED;
      }
      if (SOCIAL_ART_210_LINES.contains(number)) {
        return Kind.SOCIAL_ART_210;
      }
      if (SOCIAL_PERSONAL_INCOME.contains(number)) {
        return Kind.REMUNERATION;
      }
      if (NOT_SOCIAL_EXPENSE.contains(number)) {
        return Kind.NONE;
      }
      return Kind.UNKNOWN;
    }
    return REMUNERATION_ACCOUNTS.contains(entry.getDebitAccount()) ? Kind.REMUNERATION : Kind.NONE;
  }

  // ═══════════════════════════ ФАКТУРА ═══════════════════════════

  private static void invoice(
      List<LineRow> rows,
      InvoiceMove invoice,
      Map<Long, String> own,
      Map<Long, Map<String, BigDecimal>> coded,
      Context c) {
    String where = invoice.reference + " (" + ref(rows.get(0)) + ")";
    c.current = BgSaftRef.move(rows.get(0).moveId(), where);
    Map<String, List<LineRow>> expenses = new LinkedHashMap<>();
    for (LineRow row : rows) {
      if (isDebit(row) && trim(row.accountCode()) != null) {
        expenses.computeIfAbsent(row.accountCode().trim(), k -> new ArrayList<>()).add(row);
      }
    }

    Map<String, Map<String, BigDecimal>> taxByAccount = new LinkedHashMap<>();
    Set<String> codes = new TreeSet<>();
    for (IncomeLine line : invoice.lines) {
      Mapping mapping = c.mappings.get(line.incomeTypeCode);
      if (mapping == null) {
        c.unmapped.add(c.ref(where + " / " + line.incomeTypeCode));
        continue;
      }
      if (Mapping.NONE.equals(mapping.getRule())) {
        continue;
      }
      if (Mapping.AMBIGUOUS.equals(mapping.getRule())) {
        boolean manual = false;
        for (LineRow row : expenses.getOrDefault(line.accountCode, Collections.emptyList())) {
          manual |= own.containsKey(row.id());
        }
        if (!manual) {
          c.ambiguous.add(c.ref(where + " / " + line.incomeTypeCode));
        }
        continue;
      }
      if (invoice.selfInsuredDeclared
          && BgIncomeTaxResolver.PATTERN_BUSINESS.equals(line.taxPattern)) {
        c.selfInsured.add(c.ref(where));
        continue;
      }
      BgIncomeTaxResult result =
          BgIncomeTaxResolver.resolve(
              new BgIncomeTaxRequest(
                  line.taxPattern,
                  line.expenseRate,
                  line.finalTaxRate,
                  line.advanceTaxRate,
                  line.withheldByPayer,
                  line.gross,
                  BigDecimal.ZERO,
                  false));
      BigDecimal tax = zero();
      if (result.isResolved()) {
        tax = result.taxAmount.setScale(SCALE, RoundingMode.HALF_UP);
      } else {
        c.taxRefused.add(c.ref(where + " / " + line.incomeTypeCode + ": " + result.refusal));
      }
      if (expenses.get(line.accountCode) == null) {
        c.noExpenseLine.add(c.ref(where + " / " + line.accountCode));
        continue;
      }
      taxByAccount
          .computeIfAbsent(line.accountCode, k -> new TreeMap<>())
          .merge(mapping.getSafTaxCode(), tax, BigDecimal::add);
      codes.add(mapping.getSafTaxCode());
    }
    if (codes.isEmpty()) {
      return;
    }
    if (invoice.foreign) {
      c.foreign.add(c.ref(where));
    }

    for (Map.Entry<String, Map<String, BigDecimal>> account : taxByAccount.entrySet()) {
      List<LineRow> targets = expenses.get(account.getKey());
      List<BigDecimal> weights = new ArrayList<>();
      for (LineRow row : targets) {
        weights.add(amount(row).abs());
      }
      for (Map.Entry<String, BigDecimal> perCode : account.getValue().entrySet()) {
        List<BigDecimal> shares = allocate(weights, perCode.getValue());
        for (int i = 0; i < targets.size(); i++) {
          put(coded, targets.get(i).id(), perCode.getKey(), shares.get(i));
        }
      }
    }
    for (LineRow row : rows) {
      if (!isDebit(row)) {
        for (String code : codes) {
          coded.computeIfAbsent(row.id(), k -> new TreeMap<>()).putIfAbsent(code, zero());
        }
      }
    }
  }

  // ═══════════════════════════ РАЗПРЕДЕЛЯНЕТО ═══════════════════════════

  /**
   * Най-големият остатък — същото правило като G5: всеки дял е отрязан към нулата до стотинка, а
   * разликата до целта се раздава по стотинка на най-големия остатък, при равенство — по реда.
   * Нулеви тегла при ненулева цел → делът е по равно.
   */
  static List<BigDecimal> allocate(List<BigDecimal> weights, BigDecimal target) {
    List<BigDecimal> out = new ArrayList<>();
    if (weights.isEmpty()) {
      return out;
    }
    BigDecimal sum = BigDecimal.ZERO;
    for (BigDecimal w : weights) {
      sum = sum.add(w);
    }
    List<BigDecimal> effective = new ArrayList<>(weights);
    if (sum.signum() == 0) {
      effective.replaceAll(w -> BigDecimal.ONE);
      sum = BigDecimal.valueOf(weights.size());
    }
    List<BigDecimal> raw = new ArrayList<>();
    BigDecimal floor = BigDecimal.ZERO;
    for (BigDecimal w : effective) {
      BigDecimal r = target.multiply(w).divide(sum, 10, RoundingMode.HALF_UP);
      raw.add(r);
      BigDecimal f = r.setScale(SCALE, RoundingMode.DOWN);
      out.add(f);
      floor = floor.add(f);
    }
    int cents = target.subtract(floor).movePointRight(SCALE).intValue();
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < out.size(); i++) {
      order.add(i);
    }
    order.sort(
        Comparator.comparing((Integer i) -> raw.get(i).subtract(out.get(i)))
            .reversed()
            .thenComparing(i -> i));
    for (int i = 0; i < cents; i++) {
      int at = order.get(i % order.size());
      out.set(at, out.get(at).add(CENT));
    }
    return out;
  }

  // ═══════════════════════════ ПОМОЩНИ ═══════════════════════════

  private static final class Context {
    /** Статията на документа, който се разчита — към нея сочат находките. */
    private BgSaftRef current;

    private BgSaftRef ref(String text) {
      return current == null ? BgSaftRef.text(text) : current.withLabel(text);
    }

    private final Map<String, Mapping> mappings;
    private final Map<String, BgNraNomenclatures.TaxCode> nomenclature;
    private final Map<Long, List<SaftTaxInformation>> byLine = new LinkedHashMap<>();
    private final Set<BgSaftRef> invalid = new LinkedHashSet<>();
    private final Set<BgSaftRef> unknownLines = new LinkedHashSet<>();
    private final Set<BgSaftRef> planRefused = new LinkedHashSet<>();
    private final Set<BgSaftRef> drift = new LinkedHashSet<>();
    private final Set<BgSaftRef> unmapped = new LinkedHashSet<>();
    private final Set<BgSaftRef> ambiguous = new LinkedHashSet<>();
    private final Set<BgSaftRef> selfInsured = new LinkedHashSet<>();
    private final Set<BgSaftRef> foreign = new LinkedHashSet<>();
    private final Set<BgSaftRef> taxRefused = new LinkedHashSet<>();
    private final Set<BgSaftRef> noExpenseLine = new LinkedHashSet<>();

    private Context(
        Map<String, Mapping> mappings, Map<String, BgNraNomenclatures.TaxCode> nomenclature) {
      this.mappings = mappings;
      this.nomenclature = nomenclature;
    }

    /** Ставката от TAX-IMP × основата, HALF_UP до стотинка; код без ставка → 0.00 (решение 10). */
    private BigDecimal tax(String code, BigDecimal base) {
      BgNraNomenclatures.TaxCode row = nomenclature.get(code);
      String rate = row == null ? null : trim(row.rate());
      if (rate == null || base == null) {
        return zero();
      }
      try {
        return base.multiply(new BigDecimal(rate)).setScale(SCALE, RoundingMode.HALF_UP);
      } catch (NumberFormatException e) {
        return zero();
      }
    }

    private List<BgDeclarationFileFinding> findings() {
      List<BgDeclarationFileFinding> out = new ArrayList<>();
      error(
          out,
          "SAFT_GLE_WHT_CODE_INVALID",
          "Ръчен код или код на сметката извън TAX-IMP вид 600/700 — пренебрегнат, редът е без"
              + " него: ",
          invalid);
      error(
          out,
          "SAFT_GLE_WHT_PAYROLL_UNKNOWN_LINE",
          "Перо 2xx без класификация за данък върху разходите — статията на фиша остава без"
              + " кодове 600/700, вместо да получи код по догадка: ",
          unknownLines);
      error(
          out,
          "SAFT_GLE_WHT_INCOME_UNMAPPED",
          "Вид доход без ред в картата „Вид доход → данък при източника“ — кодът 700 не се"
              + " знае: ",
          unmapped);
      error(
          out,
          "SAFT_GLE_WHT_AMBIGUOUS",
          "Вид доход с два възможни кода по TAX-IMP (804: движимо 700260 или недвижимо 700300)"
              + " — сложи ръчен код на разходния ред: ",
          ambiguous);
      warning(
          out,
          "SAFT_GLE_WHT_PAYROLL_PLAN_REFUSED",
          "Статия на фиш, чийто постинг план днес отказва — редовете ѝ са без кодове 600/700: ",
          planRefused);
      warning(
          out,
          "SAFT_GLE_WHT_PAYROLL_DRIFT",
          "Статия на фиш, която се разминава с плана на фиша (редактирана след генерирането?) —"
              + " кодовете са по плана, провери ги: ",
          drift);
      warning(
          out,
          "SAFT_GLE_WHT_SELF_INSURED",
          "Получател, декларирал по ЗДДФЛ чл. 43 ал. 5 — платецът не удържа и не начислява"
              + " данък при източника, редовете остават 000/000000 (да се потвърди с НАП): ",
          selfInsured);
      warning(
          out,
          "SAFT_GLE_WHT_FOREIGN",
          "Чуждестранно лице — посочен е кодът по вътрешното право; облекчение по СИДДО не е"
              + " преценено (Прил. 3 т. IV.8.2.2): ",
          foreign);
      warning(
          out,
          "SAFT_GLE_WHT_TAX_REFUSED",
          "Данъкът по вида доход не се смята — TaxAmount е 0.00: ",
          taxRefused);
      warning(
          out,
          "SAFT_GLE_WHT_NO_EXPENSE_LINE",
          "Ред на фактурата с вид доход, чиято сметка няма разходен ред в статията — кодът не е"
              + " поставен: ",
          noExpenseLine);
      return out;
    }

    private static void error(
        List<BgDeclarationFileFinding> out, String code, String text, Set<BgSaftRef> items) {
      BgSaftRef.error(out, code, text, items);
    }

    private static void warning(
        List<BgDeclarationFileFinding> out, String code, String text, Set<BgSaftRef> items) {
      BgSaftRef.warning(out, code, text, items);
    }
  }

  private static void put(
      Map<Long, Map<String, BigDecimal>> coded, Long lineId, String code, BigDecimal amount) {
    coded.computeIfAbsent(lineId, k -> new TreeMap<>()).merge(code, amount, BigDecimal::add);
  }

  private static boolean sameAmounts(Map<String, BigDecimal> a, Map<String, BigDecimal> b) {
    if (!a.keySet().equals(b.keySet())) {
      return false;
    }
    for (Map.Entry<String, BigDecimal> e : a.entrySet()) {
      if (e.getValue().compareTo(b.get(e.getKey())) != 0) {
        return false;
      }
    }
    return true;
  }

  private static LineRow largest(List<LineRow> rows) {
    LineRow best = rows.get(0);
    for (LineRow row : rows) {
      if (amount(row).abs().compareTo(amount(best).abs()) > 0) {
        best = row;
      }
    }
    return best;
  }

  private static String key(String account, boolean debit) {
    return (account == null ? "" : account.trim()) + (debit ? "|D" : "|C");
  }

  /** Дебит минус кредит. */
  private static BigDecimal amount(LineRow row) {
    return nonNull(row.debit()).subtract(nonNull(row.credit()));
  }

  private static boolean isDebit(LineRow row) {
    return amount(row).signum() >= 0;
  }

  private static String ref(LineRow row) {
    String reference = trim(row.reference());
    return reference == null ? "#" + row.moveId() : reference;
  }

  private static boolean startsWith(String value, String prefix) {
    return value != null && value.trim().startsWith(prefix);
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(SCALE);
  }

  private static BigDecimal nonNull(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String trim(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }
}

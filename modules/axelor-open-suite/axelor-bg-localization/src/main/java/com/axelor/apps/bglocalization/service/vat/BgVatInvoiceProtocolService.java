package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.move.MoveCreateService;
import com.axelor.apps.account.service.move.MoveValidateService;
import com.axelor.apps.account.service.moveline.MoveLineCreateService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.CurrencyConversionLine;
import com.axelor.apps.base.db.repo.CurrencyConversionLineRepository;
import com.axelor.apps.bglocalization.db.repo.BgVatReturnRepository;
import com.axelor.apps.bglocalization.service.stock.BgStockMoveDates;
import com.axelor.apps.bglocalization.service.stock.BgStockMoveGuard;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatMoveOperationRouting;
import com.axelor.db.JPA;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;

/**
 * ПРОТОКОЛЪТ ПО ЧЛ. 117 ЗДДС ОТ ПОКУПНА ФАКТУРА С ОБРАТНО НАЧИСЛЯВАНЕ.
 *
 * <p>Протоколът е СЪЩИЯТ вид запис като ръчния: статия с {@code bgVatMoveVatOperation}, вид „09“,
 * ставка ({@code bgVatMoveVatRate}), редове Дт 4531 (или разход при „без право на кредит“) / Кт
 * 4532 и номер от поредицата {@code bgVatProtocol} при осчетоводяване. Затова дневниците и SAF-T го
 * четат без промяна. Към ръчния добавя три неща: изричната основа ({@code bgVatProtocolBase}),
 * основанието по т. 7 ({@code bgVatProtocolBasis}) и връзката към фактурата ({@code
 * Move.bgVatSourceInvoice}; НЕ {@code Move.invoice} — с нея колекторът би чел протокола като
 * фактура).
 *
 * <p><b>Защо 4531/4532 не удвояват книгата.</b> Покупната фактура с обратно начисляване пише и
 * двата си данъка по сметката за ДДС по покупки на СВОЯ данък (Axelor {@code
 * TaxInvoiceLine.getImputedAccount}; в BG_COA — 4531 за всяка двойка), тоест нетира ±ДДС по 4531.
 * Начисленият данък по 4532 и данъчният кредит по 4531 идват само от протокола — {@code
 * BgReverseChargeNettingSeedTest}.
 *
 * <p><b>Реквизитите по чл. 117 ал. 2</b> — от данните, без печатна форма (отделна задача): т. 1 —
 * номерът от поредицата и датата на статията; т. 2 — дружеството; т. 3 — редовете на фактурата
 * (през връзката); т. 4 — {@code Invoice.bgVatTaxEventDate}; т. 5 — {@code bgVatProtocolBase}; т. 6
 * — {@code bgVatMoveVatRate}; т. 7 — {@code bgVatProtocolBasis}; т. 8 — Кт 4532; т. 9 (и ППЗДДС чл.
 * 81 ал. 3) — ДДС номерът на доставчика и номерът и датата на фактурата му ({@code Move.partner},
 * {@code origin}, {@code originDate}); т. 10 — ИН на доставчика по Прил. № 2 част втора
 * (партньорът).
 *
 * <p><b>Валута.</b> Основата е в евро по последния курс на ЕЦБ с дата до изискуемостта (ЗДДС чл. 26
 * ал. 6) — {@link #exchangeRate}; без курс — ГРЕШКА.
 *
 * <p><b>Закъснял.</b> Издаден след D_ch + 15 — статията е с датата D_ch (дневниците за периода на
 * изискуемостта, ППЗДДС чл. 81 ал. 5), а {@code bgVatProtocolDate} — с датата на издаване ({@link
 * BgVatProtocolDates#place}).
 *
 * <p><b>Не се съставя</b> (видимо, никога тихо): известия (протоколът по чл. 117 ал. 4 — отделна
 * задача), авансови и балансови фактури, случаите-ГРЕШКИ на {@link BgVatInvoiceProtocolCase},
 * период на статията — затворен в счетоводството или с подадена декларация (при закъснял — с
 * указание за корекция по ЗДДС чл. 126 ал. 3 т. 2).
 *
 * <p><b>Веднъж.</b> Един протокол на (фактура, случай, ставка). Вече свързан — не се прави втори.
 * Заварен ръчен протокол за същата фактура (същото дружество и контрагент, {@code origin} = номерът
 * на фактурата на доставчика, същата операция) — свързва се с предупреждение, втори не се съставя.
 */
public class BgVatInvoiceProtocolService {

  /** „09 – Протокол или друг документ“. */
  public static final String NAP_DOCUMENT_PROTOCOL = "09";

  private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("yyyyMM");

  /**
   * Курс, по-стар от толкова дни преди изискуемостта — предупреждение (ЕЦБ публикува всеки работен
   * ден).
   */
  static final int STALE_RATE_DAYS = 7;

  /** Изходът на едно съставяне. */
  public static class Result {
    private final List<Move> created = new ArrayList<>();
    private final List<Move> linked = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public List<Move> getCreated() {
      return created;
    }

    public List<Move> getLinked() {
      return linked;
    }

    public List<String> getErrors() {
      return errors;
    }

    public List<String> getWarnings() {
      return warnings;
    }

    void addAll(Result other) {
      created.addAll(other.created);
      linked.addAll(other.linked);
      errors.addAll(other.errors);
      warnings.addAll(other.warnings);
    }
  }

  protected final MoveCreateService moveCreateService;
  protected final MoveLineCreateService moveLineCreateService;
  protected final MoveValidateService moveValidateService;
  protected final MoveRepository moveRepository;
  protected final InvoiceRepository invoiceRepository;
  protected final AccountRepository accountRepository;
  protected final BgVatReturnRepository vatReturnRepository;
  protected final CurrencyConversionLineRepository conversionLineRepository;

  @Inject
  public BgVatInvoiceProtocolService(
      MoveCreateService moveCreateService,
      MoveLineCreateService moveLineCreateService,
      MoveValidateService moveValidateService,
      MoveRepository moveRepository,
      InvoiceRepository invoiceRepository,
      AccountRepository accountRepository,
      BgVatReturnRepository vatReturnRepository,
      CurrencyConversionLineRepository conversionLineRepository) {
    this.moveCreateService = moveCreateService;
    this.moveLineCreateService = moveLineCreateService;
    this.moveValidateService = moveValidateService;
    this.moveRepository = moveRepository;
    this.invoiceRepository = invoiceRepository;
    this.accountRepository = accountRepository;
    this.vatReturnRepository = vatReturnRepository;
    this.conversionLineRepository = conversionLineRepository;
  }

  // ---------------------------------------------------------------------------
  // Публичните действия
  // ---------------------------------------------------------------------------

  /**
   * Протоколите на една фактура.
   *
   * @param requestedDate исканата дата на протокола; {@code null} — по подразбиране
   */
  @Transactional(rollbackOn = {Exception.class})
  public Result generate(Invoice invoice, LocalDate requestedDate) throws AxelorException {
    Result result = new Result();
    String ref = reference(invoice);
    String refusal = refusal(invoice);
    if (refusal != null) {
      result.getErrors().add(ref + ": " + refusal);
      return result;
    }

    BgVatInvoiceProtocolCase.Supplier supplier = BgVatInvoiceProtocolCase.supplierOf(invoice);
    LocalDate taxEvent = invoice.getBgVatTaxEventDate();
    if (taxEvent == null) {
      taxEvent = invoice.getOriginDate();
      if (taxEvent == null) {
        result
            .getErrors()
            .add(
                ref
                    + ": няма нито дата на данъчното събитие, нито дата на фактурата на доставчика —"
                    + " изискуемостта не може да се определи (ЗДДС чл. 117, ал. 2, т. 4)");
        return result;
      }
      result
          .getWarnings()
          .add(
              ref
                  + ": датата на данъчното събитие е празна — взета е датата на фактурата на"
                  + " доставчика "
                  + taxEvent
                  + " (ЗДДС чл. 117, ал. 2, т. 4)");
    }

    List<BgVatInvoiceProtocolPlan.Line> lines = new ArrayList<>();
    for (InvoiceLine line : lines(invoice)) {
      List<String> reverseCharge = BgVatInvoiceProtocolCase.reverseChargeTaxCodes(line);
      if (reverseCharge.isEmpty()) {
        String note =
            BgVatInvoiceProtocolCase.withoutReverseCharge(BgVatInvoiceProtocolCase.taxCodes(line));
        if (note != null) {
          result.getWarnings().add(ref + " (" + lineLabel(line) + "): " + note);
        }
        continue;
      }
      BgVatInvoiceProtocolCase kase = BgVatInvoiceProtocolCase.of(line, supplier, taxEvent);
      switch (kase.getOutcome()) {
        case ERROR:
          result.getErrors().add(ref + " (" + lineLabel(line) + "): " + kase.getMessage());
          break;
        case WARNING:
          result.getWarnings().add(ref + " (" + lineLabel(line) + "): " + kase.getMessage());
          break;
        case PROTOCOL:
          if (kase.getMessage() != null) {
            result.getWarnings().add(ref + " (" + lineLabel(line) + "): " + kase.getMessage());
          }
          lines.add(
              new BgVatInvoiceProtocolPlan.Line(
                  kase,
                  BgVatInvoiceProtocolCase.reverseChargeRate(line),
                  line.getExTaxTotal(),
                  line.getCompanyExTaxTotal(),
                  line.getBgVatCreditType(),
                  line.getAccount() == null ? null : line.getAccount().getId()));
          break;
        default:
          break;
      }
    }
    if (!result.getErrors().isEmpty()) {
      // Частичен протокол за част от редовете е по-лош от нито един: грешката е видима, а
      // дневникът продължава да блокира фактурата, докато не се поправи.
      return result;
    }

    List<BgVatInvoiceProtocolPlan.Group> groups = BgVatInvoiceProtocolPlan.groups(lines);
    if (groups.isEmpty()) {
      if (result.getWarnings().isEmpty()) {
        result.getWarnings().add(ref + ": фактурата няма редове с обратно начисляване");
      }
      return result;
    }

    List<Move> existing = protocolsOf(invoice);
    for (BgVatInvoiceProtocolPlan.Group group : groups) {
      result.addAll(generateGroup(invoice, group, taxEvent, requestedDate, existing));
    }
    return result;
  }

  /**
   * Пакетно — протоколите на всички осчетоводени покупни фактури на дружеството с дата в периода,
   * обратно начисляване и без протокол. Всяка фактура е в СВОЯ транзакция: грешка в една (затворен
   * период, липсваща сметка) не връща другите. ⚠️ Затова не вика {@link #generate} направо през
   * {@code this} с надежда за {@code @Transactional}: прихващането на Guice не минава през вътрешно
   * извикване.
   */
  public Result generateForPeriod(Company company, LocalDate from, LocalDate to) {
    return generateForIds(reverseChargeInvoiceIds(company, from, to));
  }

  /**
   * Пакетно — протоколите на избраните фактури, всяка в своя транзакция (виж {@link
   * #generateForPeriod}). Фактура без обратно начисляване дава само предупреждение.
   */
  public Result generateForIds(List<Long> ids) {
    Result total = new Result();
    for (Long id : ids) {
      Result one = new Result();
      try {
        JPA.runInTransaction(
            () -> {
              Invoice invoice = invoiceRepository.find(id);
              one.addAll(generateUnmanaged(invoice));
            });
      } catch (Exception e) {
        one.getCreated().clear();
        one.getLinked().clear();
        one.getErrors().add("Фактура #" + id + ": " + rootMessage(e));
      }
      total.addAll(one);
      JPA.clear();
    }
    return total;
  }

  private Result generateUnmanaged(Invoice invoice) {
    try {
      return generate(invoice, null);
    } catch (AxelorException e) {
      throw new IllegalStateException(reference(invoice) + ": " + e.getMessage(), e);
    }
  }

  private static String rootMessage(Throwable e) {
    Throwable cause = e;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
  }

  // ---------------------------------------------------------------------------
  // Една група — един протокол
  // ---------------------------------------------------------------------------

  protected Result generateGroup(
      Invoice invoice,
      BgVatInvoiceProtocolPlan.Group group,
      LocalDate taxEvent,
      LocalDate requestedDate,
      List<Move> existing)
      throws AxelorException {
    Result result = new Result();
    BgVatInvoiceProtocolCase kase = group.getCase();
    String ref =
        reference(invoice)
            + " ("
            + kase.getBasis()
            + ", "
            + group.getRatePercent().toPlainString()
            + " %)";

    for (Move move : existing) {
      if (BgVatInvoiceProtocolPlan.sameProtocol(
          group,
          move.getBgVatMoveVatOperation(),
          move.getBgVatMoveVatRate(),
          move.getBgVatProtocolBasis())) {
        result
            .getWarnings()
            .add(
                ref
                    + ": протоколът вече е съставен — "
                    + protocolLabel(move)
                    + "; втори не се прави");
        return result;
      }
    }

    LocalDate chargeability =
        BgVatProtocolDates.chargeability(kase.getOperation(), taxEvent, invoice.getOriginDate());
    BgVatProtocolRate rate = exchangeRate(invoice, chargeability);
    BigDecimal base = rate == null ? null : group.base(rate);
    BigDecimal tax = rate == null ? null : group.tax(rate);

    Move manual = manualProtocolFor(invoice, group);
    if (manual != null) {
      link(manual, invoice);
      result.getLinked().add(manual);
      result
          .getWarnings()
          .add(
              ref
                  + ": за фактурата вече има ръчен протокол "
                  + protocolLabel(manual)
                  + " — свързан е с нея, втори не се съставя"
                  + (rate == null ? "" : manualDifference(manual, group, base, tax)));
      return result;
    }

    if (rate == null) {
      result
          .getErrors()
          .add(
              ref
                  + ": няма курс на ЕЦБ за "
                  + invoice.getCurrency().getCodeISO()
                  + " с дата до "
                  + chargeability
                  + " — основата е в евро „по последния обменен курс, публикуван от Европейската"
                  + " централна банка към момента, в който данъкът става изискуем“ (ЗДДС чл. 26,"
                  + " ал. 6); въведете курса и съставете протокола отново");
      return result;
    }

    if (BgVatMoveOperationRouting.OPERATION_VOP.equals(kase.getOperation())
        && invoice.getOriginDate() == null) {
      result
          .getWarnings()
          .add(
              ref
                  + ": датата на фактурата на доставчика е празна — изискуемостта е "
                  + chargeability
                  + " (ЗДДС чл. 63, ал. 3); ако фактурата е издадена преди това, данъкът е"
                  + " изискуем на датата ѝ (чл. 63, ал. 4) — попълнете я");
    }
    if (BgVatMoveOperationRouting.OPERATION_VOP.equals(kase.getOperation())
        && invoice.getOriginDate() != null
        && invoice.getOriginDate().isBefore(taxEvent)) {
      result
          .getWarnings()
          .add(
              ref
                  + ": фактурата ("
                  + invoice.getOriginDate()
                  + ") е преди данъчното събитие ("
                  + taxEvent
                  + ") — изискуемостта е датата на фактурата (ЗДДС чл. 63, ал. 4), освен ако е"
                  + " издадена за плащане преди събитието (ал. 5); проверете");
    }
    BgVatProtocolDates.Placement placement =
        BgVatProtocolDates.place(chargeability, invoice.getInvoiceDate(), requestedDate);
    if (placement.getRefusal() != null) {
      result.getErrors().add(ref + ": " + placement.getRefusal());
      return result;
    }
    LocalDate date = placement.getProtocolDate();

    String periodRefusal = periodRefusal(invoice.getCompany(), placement);
    if (periodRefusal != null) {
      result.getErrors().add(ref + ": " + periodRefusal);
      return result;
    }
    if (placement.isLate()) {
      result
          .getWarnings()
          .add(
              ref
                  + ": протоколът е издаден на "
                  + date
                  + ", след срока по ЗДДС чл. 117, ал. 3 ("
                  + BgVatProtocolDates.deadline(chargeability)
                  + ") — дневниците са за периода на изискуемостта "
                  + chargeability.format(PERIOD)
                  + " (ППЗДДС чл. 81, ал. 5; ЗДДС чл. 73а, ал. 2), а не за периода на издаването");
    }
    if (!rate.isSame()) {
      BigDecimal byInvoice = group.companyBase();
      if (byInvoice.compareTo(base) != 0) {
        result
            .getWarnings()
            .add(
                ref
                    + ": основата по "
                    + rate.describe()
                    + " е "
                    + base.toPlainString()
                    + " EUR, а по курса на фактурата — "
                    + byInvoice.toPlainString()
                    + " EUR; протоколът е по ЕЦБ към изискуемостта (ЗДДС чл. 26, ал. 6)");
      }
      if (rate.getDate().isBefore(chargeability.minusDays(STALE_RATE_DAYS))) {
        result
            .getWarnings()
            .add(
                ref
                    + ": последният курс на ЕЦБ е от "
                    + rate.getDate()
                    + ", повече от "
                    + STALE_RATE_DAYS
                    + " дни преди изискуемостта "
                    + chargeability
                    + " — проверете дали курсовете са обновени");
      }
    }
    if (invoice.getOriginDate() != null && date.isBefore(invoice.getOriginDate())) {
      result
          .getWarnings()
          .add(
              ref
                  + ": протоколът ("
                  + date
                  + ") е преди фактурата на доставчика ("
                  + invoice.getOriginDate()
                  + ") — номерът и датата ѝ се посочват, „когато такава е издадена до датата на"
                  + " издаване на протокола“ (ЗДДС чл. 117, ал. 2, т. 9); проверете");
    }
    for (BgVatInvoiceProtocolPlan.Debit debit : group.debits(rate)) {
      if (debit.getCreditType() == null) {
        result
            .getWarnings()
            .add(
                ref
                    + ": ред без вид данъчен кредит — протоколът е с пълен кредит (к.10/к.11);"
                    + " попълнете вида на реда на фактурата");
        break;
      }
    }

    Move move = createProtocol(invoice, group, placement, rate, base, tax, result);
    if (move != null) {
      result.getCreated().add(move);
      if (move.getBgVatProtocolNumber() == null || move.getBgVatProtocolNumber().isEmpty()) {
        result
            .getWarnings()
            .add(
                ref
                    + ": протоколът няма номер — дружеството няма поредица „"
                    + BgVatProtocolNumbering.SEQUENCE_CODE
                    + "“ или статията не е осчетоводена окончателно; дневникът ще даде грешка до"
                    + " номера");
      }
    }
    return result;
  }

  /** Статията на протокола — създадена, осчетоводена и свързана с фактурата. */
  protected Move createProtocol(
      Invoice invoice,
      BgVatInvoiceProtocolPlan.Group group,
      BgVatProtocolDates.Placement placement,
      BgVatProtocolRate rate,
      BigDecimal base,
      BigDecimal tax,
      Result result)
      throws AxelorException {
    Company company = invoice.getCompany();
    AccountConfig config = company.getAccountConfig();
    Journal journal = config == null ? null : config.getBgVatCorrectionJournal();
    Account deductible = config == null ? null : config.getBgVatDeductibleAccount();
    Account charged = config == null ? null : config.getBgVatChargedAccount();
    if (journal == null || deductible == null || charged == null) {
      result
          .getErrors()
          .add(
              reference(invoice)
                  + ": липсва настройка в счетоводната конфигурация на дружеството — журнал за ДДС"
                  + " корекции и протоколи, сметка за ДДС по покупки (4531) и по продажби (4532)");
      return null;
    }
    BgVatInvoiceProtocolCase kase = group.getCase();
    String origin = invoice.getSupplierInvoiceNb();
    LocalDate date = placement.getMoveDate();
    String description = description(invoice, group, placement, rate, base, tax);

    Move move = newMove(journal, invoice, date, origin, description);
    move.setNapDocumentTypeSelect(NAP_DOCUMENT_PROTOCOL);
    move.setBgVatMoveVatOperation(kase.getOperation());
    move.setBgVatMoveVatRate(group.getRateFraction());
    move.setBgVatSupplyType(kase.getSupplyType());
    move.setBgVatSourceInvoice(invoice);
    move.setBgVatProtocolBase(base);
    move.setBgVatProtocolBasis(kase.getBasis());
    if (placement.isLate()) {
      move.setBgVatProtocolDate(placement.getProtocolDate());
    }

    int counter = 1;
    List<MoveLine> lines = new ArrayList<>();
    for (BgVatInvoiceProtocolPlan.Debit debit : group.debits(rate)) {
      Account account =
          debit.expenseAccountId() == null ? deductible : findAccount(debit.expenseAccountId());
      MoveLine line =
          newLine(move, account, debit.getAmount(), true, date, counter++, origin, description);
      line.setBgVatCreditType(debit.getCreditType());
      lines.add(line);
    }
    lines.add(newLine(move, charged, tax, false, date, counter, origin, description));
    // Данъчните редове по 4531/4532 — правото на кредит и самоначисленият данък от реда на
    // фактурата; без тях AOS не осчетоводява статията с BG_COA (виж BgVatAccountTaxLines).
    InvoiceLine source = sourceLine(invoice, group);
    BgVatAccountTaxLines.apply(
        lines,
        deductible,
        charged,
        BgVatAccountTaxLines.credit(source),
        BgVatAccountTaxLines.selfCharged(source));
    // ⚠️ ДОБАВЯ се към списъка: createMove вече е записала статията (виж BgArt79ProtocolService).
    lines.forEach(move::addMoveLineListItem);
    Move saved = save(move);
    account(saved);
    return saved;
  }

  /** Празната статия — {@code MoveCreateService.createMove}, която я и записва. */
  protected Move newMove(
      Journal journal, Invoice invoice, LocalDate date, String origin, String description)
      throws AxelorException {
    Company company = invoice.getCompany();
    return moveCreateService.createMove(
        journal,
        company,
        company.getCurrency(),
        invoice.getPartner(),
        date,
        invoice.getOriginDate(),
        null,
        invoice.getFiscalPosition(),
        MoveRepository.TECHNICAL_ORIGIN_AUTOMATIC,
        MoveRepository.FUNCTIONAL_ORIGIN_SALE,
        origin,
        description,
        null);
  }

  /** Един ред — {@code MoveLineCreateService.createMoveLine}, без контрагент (4531/4532). */
  protected MoveLine newLine(
      Move move,
      Account account,
      BigDecimal amount,
      boolean debit,
      LocalDate date,
      int counter,
      String origin,
      String description)
      throws AxelorException {
    return moveLineCreateService.createMoveLine(
        move, null, account, amount, debit, date, counter, origin, description);
  }

  /**
   * Връзката на заварен ръчен протокол с фактурата — насочен UPDATE само на връзката, както при
   * ръчния номер ({@code BgVatProtocolNumberEntryService}): протоколът е осчетоводен, пълният
   * {@code save} би минал през пазачите на периода, а „Поправки и добавки в протоколите не се
   * разрешават.“ (ЗДДС чл. 117а ал. 1) — сумите и номерът му не се пипат.
   */
  protected void link(Move manual, Invoice invoice) {
    JPA.em()
        .createQuery(
            "UPDATE Move self SET self.bgVatSourceInvoice = :invoice"
                + " WHERE self.id = :id AND self.bgVatSourceInvoice IS NULL")
        .setParameter("invoice", invoice)
        .setParameter("id", manual.getId())
        .executeUpdate();
    manual.setBgVatSourceInvoice(invoice);
  }

  protected Account findAccount(Long id) {
    return accountRepository.find(id);
  }

  protected Move save(Move move) {
    return moveRepository.save(move);
  }

  /**
   * Осчетоводяването — то дава номера ({@code BgMoveRepository} при запис на осчетоводена статия).
   * Протоколът е издаден в момента на съставянето си, затова не остава чернова.
   */
  protected void account(Move move) throws AxelorException {
    moveValidateService.accounting(move);
  }

  // ---------------------------------------------------------------------------
  // Проверките
  // ---------------------------------------------------------------------------

  /**
   * Защо от фактурата не се съставя протокол изобщо; {@code null}, когато може — {@link
   * BgVatInvoiceProtocolCase#invoiceRefusal}, същото правило като в дневника.
   */
  protected String refusal(Invoice invoice) {
    if (invoice == null) {
      return "няма фактура";
    }
    if (invoice.getOperationTypeSelect() != InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE
        && invoice.getOperationTypeSelect() != InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND) {
      return "протокол по чл. 117 се съставя само от покупна фактура";
    }
    if (invoice.getStatusSelect() != InvoiceRepository.STATUS_VENTILATED) {
      return "фактурата не е осчетоводена";
    }
    return BgVatInvoiceProtocolCase.invoiceRefusal(invoice);
  }

  /**
   * Подадена декларация или затворен период за ДАТАТА НА СТАТИЯТА — при закъснял протокол това е
   * периодът на изискуемостта. Никога тихо: указанието е корекцията. Подаденият период с отворена
   * коригираща ({@link BgVatFiledPeriods#hasOpenCorrection}) не спира протокола — той влиза в нея.
   */
  protected String periodRefusal(Company company, BgVatProtocolDates.Placement placement)
      throws AxelorException {
    LocalDate date = placement.getMoveDate();
    String yyyymm = date.format(PERIOD);
    String hint =
        placement.isLate()
            ? " Закъснелият протокол се отразява в периода на изискуемостта (ППЗДДС чл. 81, ал. 5)"
                + " — корекцията е по ЗДДС чл. 126, ал. 3, т. 2 (писмено уведомление до органа по"
                + " приходите); съставете протокола като ръчна статия по указанията му."
            : " Изберете друга дата в срока по ЗДДС чл. 117, ал. 3 или коригирайте по чл. 126"
                + " ЗДДС.";
    if (isFiled(company, yyyymm) && !hasOpenCorrection(company, yyyymm)) {
      return "ДДС декларацията за " + yyyymm + " е подадена — протоколът не се вписва тихо." + hint;
    }
    if (isClosed(company, date)) {
      return "счетоводният период на "
          + date
          + " е затворен, затваря се или не съществува — протоколът не се вписва тихо."
          + hint;
    }
    return null;
  }

  protected boolean isFiled(Company company, String yyyymm) {
    return BgVatFiledPeriods.isFiled(vatReturnRepository, company, yyyymm);
  }

  protected boolean hasOpenCorrection(Company company, String yyyymm) {
    return BgVatFiledPeriods.hasOpenCorrection(vatReturnRepository, company, yyyymm);
  }

  /**
   * Фискалният период за датата липсва, затваря се или е затворен — същият предикат като за
   * складовата дата ({@link BgStockMoveGuard#periodState}), не трета логика за периода.
   */
  protected boolean isClosed(Company company, LocalDate date) throws AxelorException {
    return BgStockMoveGuard.periodState(date, company) != BgStockMoveDates.PeriodState.OPEN;
  }

  // ---------------------------------------------------------------------------
  // Данните — изолирани, за да се подменят в теста (HSQLDB не изпълнява SELECT върху Move)
  // ---------------------------------------------------------------------------

  /** Филтърът на {@link #protocolsOf} — върху {@code Move}. */
  static final String PROTOCOLS_OF =
      "self.bgVatSourceInvoice = :invoice AND self.statusSelect != :canceled";

  /** Филтърът на {@link #manualProtocolFor} — върху {@code Move}. */
  static final String MANUAL_PROTOCOLS =
      "self.company = :company AND self.partner = :partner AND self.origin = :origin"
          + " AND self.bgVatMoveVatOperation = :operation"
          + " AND self.bgVatSourceInvoice IS NULL AND self.statusSelect != :canceled";

  /** Вече свързаните с фактурата протоколи, без отказаните. */
  protected List<Move> protocolsOf(Invoice invoice) {
    if (invoice.getId() == null) {
      return Collections.emptyList();
    }
    return moveRepository
        .all()
        .filter(PROTOCOLS_OF)
        .bind("invoice", invoice)
        .bind("canceled", MoveRepository.STATUS_CANCELED)
        .fetch();
  }

  /**
   * Заварен ръчен протокол за същата фактура: същото дружество и контрагент, {@code origin} =
   * номерът на фактурата на доставчика, операцията на групата, без връзка към фактура.
   */
  protected Move manualProtocolFor(Invoice invoice, BgVatInvoiceProtocolPlan.Group group) {
    String number = invoice.getSupplierInvoiceNb();
    if (number == null || number.trim().isEmpty() || invoice.getPartner() == null) {
      return null;
    }
    List<Move> candidates =
        moveRepository
            .all()
            .filter(MANUAL_PROTOCOLS)
            .bind("company", invoice.getCompany())
            .bind("partner", invoice.getPartner())
            .bind("origin", number.trim())
            .bind("operation", group.getCase().getOperation())
            .bind("canceled", MoveRepository.STATUS_CANCELED)
            .fetch();
    for (Move candidate : candidates) {
      if (BgVatInvoiceProtocolPlan.sameProtocol(
          group,
          candidate.getBgVatMoveVatOperation(),
          candidate.getBgVatMoveVatRate(),
          candidate.getBgVatProtocolBasis())) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * Покупните осчетоводени фактури на дружеството в периода (по {@code invoiceDate}) с ред с
   * обратно начисляване и без протокол — вече обработените не се изреждат отново. Само id-тата:
   * всяка фактура се зарежда в своята транзакция.
   */
  protected List<Long> reverseChargeInvoiceIds(Company company, LocalDate from, LocalDate to) {
    return JPA.em()
        .createQuery(
            "SELECT self.id FROM Invoice self WHERE self.company = :company"
                + " AND self.operationTypeSelect = :purchase AND self.statusSelect = :ventilated"
                + " AND self.invoiceDate >= :from AND self.invoiceDate <= :to"
                + " AND EXISTS (SELECT line.id FROM InvoiceLine line"
                + " WHERE line.invoice = self AND line.taxEquiv.reverseCharge = true)"
                + " AND NOT EXISTS (SELECT protocol.id FROM Move protocol"
                + " WHERE protocol.bgVatSourceInvoice = self AND protocol.statusSelect != :canceled)"
                + " ORDER BY self.invoiceDate, self.id",
            Long.class)
        .setParameter("company", company)
        .setParameter("purchase", InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE)
        .setParameter("ventilated", InvoiceRepository.STATUS_VENTILATED)
        .setParameter("from", from)
        .setParameter("to", to)
        .setParameter("canceled", MoveRepository.STATUS_CANCELED)
        .getResultList();
  }

  /**
   * Курсът на валутата на фактурата към евро: последният ред с дата до изискуемостта (ЗДДС чл. 26
   * ал. 6), пряк или обратен — по-новият от двата. Във валутата на дружеството — {@link
   * BgVatProtocolRate#SAME}. ⚠️ Да се потвърди: текстът е „към момента, в който данъкът става
   * изискуем“, а ЕЦБ публикува курса на деня следобед — курсът от самия ден на изискуемостта тук
   * важи.
   *
   * <p>⚠️ НЕ {@code CurrencyService.getCurrencyConversionRateAtDate}: той връща ПЪРВИЯ ред в
   * списъка, чиито дати покриват деня, а не последния публикуван.
   *
   * @return {@code null}, когато курс няма
   */
  protected BgVatProtocolRate exchangeRate(Invoice invoice, LocalDate date) {
    Currency from = invoice.getCurrency();
    Currency to = invoice.getCompany().getCurrency();
    if (from == null || to == null || from.equals(to)) {
      return BgVatProtocolRate.SAME;
    }
    CurrencyConversionLine direct = latest(from, to, date);
    CurrencyConversionLine inverse = latest(to, from, date);
    CurrencyConversionLine chosen;
    if (direct == null || inverse == null) {
      chosen = direct == null ? inverse : direct;
    } else {
      chosen = direct.getFromDate().isBefore(inverse.getFromDate()) ? inverse : direct;
    }
    if (chosen == null || chosen.getExchangeRate().signum() == 0) {
      return null;
    }
    return BgVatProtocolRate.of(chosen.getExchangeRate(), chosen == inverse, chosen.getFromDate())
        .currency(from.getCodeISO());
  }

  /** Последният ред на курса {@code start → end} с дата до {@code date}. */
  protected CurrencyConversionLine latest(Currency start, Currency end, LocalDate date) {
    return conversionLineRepository
        .all()
        .filter(
            "self.startCurrency = :start AND self.endCurrency = :end AND self.fromDate <= :date")
        .bind("start", start)
        .bind("end", end)
        .bind("date", date)
        .order("-fromDate")
        .fetchOne();
  }

  // ---------------------------------------------------------------------------
  // Помощни
  // ---------------------------------------------------------------------------

  /**
   * Първият ред на фактурата в групата на протокола — същият случай и същата ставка на обратното
   * начисляване; от него са данъчните редове на статията.
   */
  static InvoiceLine sourceLine(Invoice invoice, BgVatInvoiceProtocolPlan.Group group) {
    BgVatInvoiceProtocolCase.Supplier supplier = BgVatInvoiceProtocolCase.supplierOf(invoice);
    LocalDate taxEvent =
        invoice.getBgVatTaxEventDate() != null
            ? invoice.getBgVatTaxEventDate()
            : invoice.getOriginDate();
    for (InvoiceLine line : lines(invoice)) {
      if (BgVatInvoiceProtocolCase.reverseChargeTaxCodes(line).isEmpty()
          || BgVatInvoiceProtocolCase.reverseChargeRate(line).compareTo(group.getRatePercent())
              != 0) {
        continue;
      }
      BgVatInvoiceProtocolCase kase = BgVatInvoiceProtocolCase.of(line, supplier, taxEvent);
      if (kase.groupKey().equals(group.getCase().groupKey())) {
        return line;
      }
    }
    return null;
  }

  private static List<InvoiceLine> lines(Invoice invoice) {
    return invoice.getInvoiceLineList() == null
        ? Collections.emptyList()
        : invoice.getInvoiceLineList();
  }

  private static String manualDifference(
      Move manual, BgVatInvoiceProtocolPlan.Group group, BigDecimal base, BigDecimal tax) {
    BigDecimal manualTax = BigDecimal.ZERO;
    if (manual.getMoveLineList() != null) {
      for (MoveLine line : manual.getMoveLineList()) {
        if (line.getAccount() != null
            && line.getAccount().getCode() != null
            && line.getAccount().getCode().startsWith("4532")) {
          manualTax = manualTax.add(line.getCredit()).subtract(line.getDebit());
        }
      }
    }
    BigDecimal manualBase = manual.getBgVatProtocolBase();
    if (manualBase.signum() == 0 && group.getRateFraction().signum() != 0) {
      manualBase = manualTax.divide(group.getRateFraction(), 2, RoundingMode.HALF_UP);
    }
    if (manualBase.compareTo(base) == 0 && manualTax.compareTo(tax) == 0) {
      return "";
    }
    return "; ⚠️ различни суми: ръчният е основа "
        + manualBase.toPlainString()
        + " / данък "
        + manualTax.toPlainString()
        + ", по фактурата — "
        + base.toPlainString()
        + " / "
        + tax.toPlainString()
        + " (разлика в данъка "
        + tax.subtract(manualTax).toPlainString()
        + ")";
  }

  private static String description(
      Invoice invoice,
      BgVatInvoiceProtocolPlan.Group group,
      BgVatProtocolDates.Placement placement,
      BgVatProtocolRate rate,
      BigDecimal base,
      BigDecimal tax) {
    BgVatInvoiceProtocolCase kase = group.getCase();
    StringBuilder text = new StringBuilder("Протокол по ");
    text.append(kase.getIssuedUnder()).append(" — ").append(kase.getBasis());
    text.append("; фактура ").append(nvl(invoice.getSupplierInvoiceNb()));
    text.append(" от ").append(invoice.getOriginDate());
    text.append("; ДО ").append(base.toPlainString()).append(", ставка ");
    text.append(group.getRatePercent().toPlainString()).append(" %, данък ");
    text.append(tax.toPlainString());
    if (!rate.isSame()) {
      text.append("; ").append(rate.describe());
    }
    if (placement.isLate()) {
      text.append("; закъснял — издаден ").append(placement.getProtocolDate());
      text.append(", отразен в периода на изискуемостта (ППЗДДС чл. 81 ал. 5)");
    }
    return text.toString();
  }

  private static String protocolLabel(Move move) {
    String number = move.getBgVatProtocolNumber();
    return "№ "
        + (number == null || number.isEmpty()
            ? "(без номер, статия " + move.getReference() + ")"
            : number)
        + " от "
        + move.getDate();
  }

  private static String lineLabel(InvoiceLine line) {
    return line.getProductName() == null ? "ред" : "ред „" + line.getProductName() + "“";
  }

  static String reference(Invoice invoice) {
    if (invoice == null) {
      return "Фактура";
    }
    String number = invoice.getSupplierInvoiceNb();
    if (number == null || number.trim().isEmpty()) {
      number = invoice.getInvoiceId();
    }
    return "Фактура " + nvl(number);
  }

  private static String nvl(String value) {
    return value == null ? "" : value;
  }
}

package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoicePayment;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.Reconcile;
import com.axelor.apps.account.db.repo.InvoicePaymentRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.account.service.invoice.InvoiceService;
import com.axelor.apps.account.service.move.MoveCreateService;
import com.axelor.apps.account.service.move.MoveValidateService;
import com.axelor.apps.account.service.moveline.MoveLineCreateService;
import com.axelor.apps.account.service.reconcile.ReconcileService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.i18n.I18n;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;

/**
 * АВАНСОВАТА ФАКТУРА В СТРАНАТА — статиите и проверките (схема А, решение от 28.10.2026). Покупките
 * са огледални: Дт 4531 / Кт 402, приспадане с Кт 402, сума 0 — Дт 60x / Кт 402; окончателна от
 * доставчик без реда НЕ се спира (документът на доставчика решава) — само предупреждение.
 *
 * <ul>
 *   <li>Авансовата фактура с ДДС получава при осчетоводяване статия Дт 412 / Кт 4532 с датата си и
 *       {@code Move.invoice} = тя. Колекторът я чете като всяка фактура — основата и ДДС от {@code
 *       InvoiceLineTax}, периодът от {@code Move.date} (ЗДДС чл. 124 ал. 2). Статусът ѝ остава
 *       „валидирана“: само такива AOS предлага за приспадане. {@code Invoice.move} остава празно —
 *       AOS не очаква статия на авансова фактура (падежи, плащания).
 *   <li>Окончателната я приспада с ред за приспадане ({@link #deduct}) — познава се по {@code
 *       InvoiceLine.bgAdvanceDeduction} и {@code bgAdvanceInvoice}, текстът е само за печат; без
 *       реда окончателната не се осчетоводява ({@link #checkDeductions}), иначе ДДС-ът на аванса се
 *       начислява втори път. О.Д. на AOS за аванса не се прави ({@code
 *       BgMoveExcessPaymentService}); 412 се лентира тук ({@link #reconcile}) — така AOS вече не
 *       предлага аванса за следваща фактура.
 *   <li>Окончателна със сума 0 — статия Дт 412 / Кт 702 ({@link #createZeroFinalMove}): AOS не
 *       съставя статия при нулева сума.
 *   <li>Без статия при плащане на фактура ({@code generateMoveForInvoicePayment = false}) авансова
 *       с ДДС не се осчетоводява ({@link #requirePaymentMoves}).
 * </ul>
 */
public class BgVatAdvanceService {

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  /** Статията на авансова фактура — тази с {@code Move.invoice} = нея, неанулирана. */
  static final String VAT_MOVE =
      "self.invoice = :invoice AND self.statusSelect <> :canceled"
          + " AND self.functionalOriginSelect IN (:origins)";

  /**
   * Авансови фактури с ДДС на дружеството без ДДС статия — издадени, преди авансите да влизат в
   * дневниците. Само справка: ретроактивна статия би сменила подадени периоди.
   */
  public static final String WITHOUT_VAT_MOVE =
      "self.company = :company AND self.operationSubTypeSelect = 2"
          + " AND self.operationTypeSelect IN (1, 2, 3, 4) AND self.statusSelect = 2"
          + " AND self.companyTaxTotal <> 0"
          + " AND NOT EXISTS (SELECT m.id FROM Move m WHERE m.invoice = self"
          + " AND m.statusSelect <> 4)";

  /**
   * Платена авансова фактура с ДДС статия, която още никоя осчетоводена фактура не приспада ({@code
   * :op} — 3 продажба, 1 покупка).
   */
  static final String OPEN_ADVANCES =
      "self.company = :company AND self.partner = :partner"
          + " AND self.operationSubTypeSelect = 2 AND self.operationTypeSelect = :op"
          + " AND self.statusSelect = 2 AND self.amountPaid > 0"
          + " AND self.invoiceDate <= :date"
          + " AND EXISTS (SELECT m.id FROM Move m WHERE m.invoice = self AND m.statusSelect <> 4)"
          + " AND NOT EXISTS (SELECT f.id FROM Invoice f JOIN f.advancePaymentInvoiceSet a"
          + " WHERE a = self AND f.statusSelect = 3)";

  /**
   * Осчетоводените окончателни фактури на периода (по периода на отразяване, иначе по датата на
   * статията — {@link BgVatPeriodScope}; {@code :op} — 3 или 1).
   */
  static final String PERIOD_FINALS =
      "self.company = :company AND self.operationTypeSelect = :op"
          + " AND self.operationSubTypeSelect <> 2 AND self.statusSelect = 3"
          + " AND "
          + BgVatPeriodScope.invoices("self.move.date");

  /** Авансовите фактури от доставчик с ДДС статия в периода. */
  static final String PERIOD_PURCHASE_ADVANCES =
      "self.company = :company AND self.operationSubTypeSelect = 2"
          + " AND self.operationTypeSelect = 1 AND self.statusSelect = 2"
          + " AND "
          + BgVatPeriodScope.invoices("self.invoiceDate")
          + " AND EXISTS (SELECT m.id FROM Move m WHERE m.invoice = self AND m.statusSelect <> 4)";

  /** Авансовите фактури (на клиент и от доставчик) с ДДС статия в периода. */
  static final String PERIOD_ADVANCES =
      "self.company = :company AND self.operationSubTypeSelect = 2"
          + " AND self.operationTypeSelect IN (1, 3) AND self.statusSelect = 2"
          + " AND "
          + BgVatPeriodScope.invoices("self.invoiceDate")
          + " AND EXISTS (SELECT m.id FROM Move m WHERE m.invoice = self AND m.statusSelect <> 4)";

  /** Осчетоводените фактури с ред за приспадане на авансовата {@code :advance}. */
  static final String DEDUCTING_FINALS =
      "self.statusSelect = 3 AND self.operationSubTypeSelect <> 2"
          + " AND EXISTS (SELECT l.id FROM InvoiceLine l WHERE l.invoice = self"
          + " AND l.bgAdvanceDeduction = true AND l.bgAdvanceInvoice = :advance)";

  /** Старите авансови без ДДС статия в периода — {@link #periodWarnings}. */
  static final String PERIOD_WITHOUT_VAT_MOVE =
      WITHOUT_VAT_MOVE + " AND " + BgVatPeriodScope.invoices("self.invoiceDate");

  protected final MoveCreateService moveCreateService;
  protected final MoveLineCreateService moveLineCreateService;
  protected final MoveValidateService moveValidateService;
  protected final MoveRepository moveRepository;
  protected final InvoiceRepository invoiceRepository;
  protected final AccountConfigService accountConfigService;
  protected final ReconcileService reconcileService;
  protected final InvoiceService invoiceService;

  @Inject
  public BgVatAdvanceService(
      MoveCreateService moveCreateService,
      MoveLineCreateService moveLineCreateService,
      MoveValidateService moveValidateService,
      MoveRepository moveRepository,
      InvoiceRepository invoiceRepository,
      AccountConfigService accountConfigService,
      ReconcileService reconcileService,
      InvoiceService invoiceService) {
    this.moveCreateService = moveCreateService;
    this.moveLineCreateService = moveLineCreateService;
    this.moveValidateService = moveValidateService;
    this.moveRepository = moveRepository;
    this.invoiceRepository = invoiceRepository;
    this.accountConfigService = accountConfigService;
    this.reconcileService = reconcileService;
    this.invoiceService = invoiceService;
  }

  // ---------------------------------------------------------------------------
  // Авансовата фактура
  // ---------------------------------------------------------------------------

  /** ДДС статията на авансовата фактура; {@code null}, когато няма. */
  public Move vatMove(Invoice advance) {
    if (advance == null || advance.getId() == null) {
      return null;
    }
    return moveRepository
        .all()
        .filter(VAT_MOVE)
        .bind("invoice", advance)
        .bind("canceled", MoveRepository.STATUS_CANCELED)
        .bind(
            "origins",
            List.of(
                MoveRepository.FUNCTIONAL_ORIGIN_SALE, MoveRepository.FUNCTIONAL_ORIGIN_PURCHASE))
        .fetchOne();
  }

  /**
   * ДДС статията на авансова фактура (или КИ към нея) с ДДС: продажба — Дт 412 / Кт 4532, покупка —
   * Дт 4531 / Кт 402 (КИ — обратно), с датата на фактурата. Вече съставена — връща нея.
   */
  @Transactional(rollbackOn = {Exception.class})
  public Move createVatMove(Invoice advance) throws AxelorException {
    if (!BgVatAdvancePlan.needsVatMove(advance)) {
      return null;
    }
    requirePaymentMoves(advance);
    Move existing = vatMove(advance);
    if (existing != null) {
      return existing;
    }
    Company company = advance.getCompany();
    AccountConfig config = accountConfigService.getAccountConfig(company);
    boolean purchase = BgVatAdvancePlan.isPurchase(advance);
    Account advanceAccount = advanceAccount(config, purchase);
    Account vatAccount =
        purchase ? config.getBgVatDeductibleAccount() : config.getBgVatChargedAccount();
    if (vatAccount == null) {
      throw new AxelorException(
          advance,
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          (purchase
                  ? "Липсва сметката за ДДС по покупки (4531)"
                  : "Липсва сметката за ДДС по продажби (4532)")
              + " в счетоводната конфигурация на дружеството — ДДС-ът на авансовата фактура няма"
              + " къде да се отнесе.");
    }
    LocalDate date = advance.getInvoiceDate();
    String origin = advance.getInvoiceId();
    String description =
        (BgVatAdvancePlan.isRefund(advance)
                ? "ДДС по кредитно известие към авансова фактура № "
                : "ДДС по авансова фактура № ")
            + origin;
    Move move =
        moveCreateService.createMove(
            advance.getJournal(),
            company,
            company.getCurrency(),
            advance.getPartner(),
            date,
            advance.getOriginDate(),
            null,
            advance.getFiscalPosition(),
            MoveRepository.TECHNICAL_ORIGIN_AUTOMATIC,
            functionalOrigin(advance),
            origin,
            description,
            null);
    move.setInvoice(advance);
    boolean debit = BgVatAdvancePlan.advanceAccountIsDebit(advance);
    int counter = 1;
    List<MoveLine> lines = new ArrayList<>();
    lines.add(
        moveLineCreateService.createMoveLine(
            move,
            advance.getPartner(),
            advanceAccount,
            BgVatAdvancePlan.totalVat(advance),
            debit,
            date,
            counter++,
            origin,
            description));
    for (BgVatAdvancePlan.Rate rate : BgVatAdvancePlan.rates(advance)) {
      if (rate.getCompanyVat().signum() == 0) {
        continue;
      }
      MoveLine vatLine =
          moveLineCreateService.createMoveLine(
              move,
              null,
              vatAccount,
              rate.getCompanyVat(),
              !debit,
              date,
              counter++,
              origin,
              description);
      // Редът по 4531/4532 носи данъчния ред на ставката — както в статията на обикновена
      // фактура. Без него AOS отказва статия с произход продажба/покупка и сметка от тип „данък“
      // („Tax line must be filled when an account of type tax is selected“): с BG_COA (4532 е от
      // тип данък) всяка авансова фактура с ДДС падаше при осчетоводяване.
      if (rate.getTaxLine() != null) {
        vatLine.setTaxLineSet(
            new java.util.HashSet<>(java.util.Collections.singleton(rate.getTaxLine())));
      }
      lines.add(vatLine);
    }
    // ⚠️ ДОБАВЯ се към списъка: createMove вече е записала статията (виж BgArt79ProtocolService).
    lines.forEach(move::addMoveLineListItem);
    Move saved = save(move);
    account(saved);
    return saved;
  }

  /**
   * Авансова с ДДС иска статия при плащане на фактура: без нея плащането няма Кт 412 и ДДС статията
   * на аванса и приспадането в окончателната не се лентират. Вика се ПРЕДИ осчетоводяването на AOS.
   */
  public void requirePaymentMoves(Invoice advance) throws AxelorException {
    if (!BgVatAdvancePlan.needsVatMove(advance)) {
      return;
    }
    boolean generate =
        accountConfigService
            .getAccountConfig(advance.getCompany())
            .getGenerateMoveForInvoicePayment();
    if (BgVatAdvancePlan.refusedWithoutPaymentMoves(advance, generate)) {
      throw new AxelorException(
          advance,
          TraceBackRepository.CATEGORY_CONFIGURATION_ERROR,
          I18n.get(
              "Авансова фактура с ДДС изисква статия при плащане на фактура — в счетоводната"
                  + " конфигурация на дружеството е изключено „Генериране на статия за плащане по"
                  + " фактура“. Без статията на плащането (Кт 412) ДДС статията на аванса и"
                  + " приспадането в окончателната фактура не се лентират. Включете настройката,"
                  + " после осчетоводете фактурата."));
    }
  }

  /** Сметката за аванси: 412 (клиенти) или 402 (доставчици). */
  protected Account advanceAccount(AccountConfig config, boolean purchase) throws AxelorException {
    return purchase
        ? accountConfigService.getSupplierAdvancePaymentAccount(config)
        : accountConfigService.getAdvancePaymentAccount(config);
  }

  private static int functionalOrigin(Invoice invoice) {
    return BgVatAdvancePlan.isPurchase(invoice)
        ? MoveRepository.FUNCTIONAL_ORIGIN_PURCHASE
        : MoveRepository.FUNCTIONAL_ORIGIN_SALE;
  }

  protected Move save(Move move) {
    return moveRepository.save(move);
  }

  /** Осчетоводяването — статията е на издадения документ, не остава чернова. */
  protected void account(Move move) throws AxelorException {
    moveValidateService.accounting(move);
  }

  /**
   * Отказ да се анулира авансова фактура с ДДС статия: тя е издадена и ДДС-ът ѝ е деклариран —
   * поправя се с кредитно известие (ЗДДС чл. 115), не с „анулиране“ в програмата.
   */
  public void refuseCancel(Invoice invoice) throws AxelorException {
    if (!BgVatAdvancePlan.isAdvance(invoice)) {
      return;
    }
    Move move = vatMove(invoice);
    if (move != null) {
      throw new AxelorException(
          invoice,
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          BgVatAdvancePlan.isPurchase(invoice)
              ? "Авансовата фактура от доставчик № "
                  + invoice.getInvoiceId()
                  + " е в дневника за покупки със статия "
                  + Objects.toString(move.getReference(), "")
                  + ". Не се анулира — поправя се с кредитно известие от доставчика (ЗДДС чл. 115)."
              : "Авансовата фактура № "
                  + invoice.getInvoiceId()
                  + " е издадена и ДДС-ът ѝ е начислен със статия "
                  + Objects.toString(move.getReference(), "")
                  + ". Не се анулира — издайте кредитно известие към нея (ЗДДС чл. 115).");
    }
  }

  // ---------------------------------------------------------------------------
  // Окончателната фактура
  // ---------------------------------------------------------------------------

  /**
   * Окончателна продажба (не авансова) — само нея проверката спира без реда за приспадане.
   * Покупката не се спира: документът на доставчика решава (предупреждение при декларацията).
   */
  public static boolean isSalesFinal(Invoice invoice) {
    return invoice != null
        && invoice.getOperationTypeSelect() == InvoiceRepository.OPERATION_TYPE_CLIENT_SALE
        && invoice.getOperationSubTypeSelect() != InvoiceRepository.OPERATION_SUB_TYPE_ADVANCE;
  }

  /** Закачените авансови фактури, които имат ДДС статия — тях окончателната трябва да приспадне. */
  public List<Invoice> linkedWithVat(Invoice invoice) {
    if (invoice.getAdvancePaymentInvoiceSet() == null) {
      return Collections.emptyList();
    }
    return invoice.getAdvancePaymentInvoiceSet().stream()
        .filter(a -> vatMove(a) != null)
        .sorted(Comparator.comparing(a -> Objects.toString(a.getInvoiceId(), "")))
        .collect(Collectors.toList());
  }

  /**
   * „Приспадни авансите“: редовете за приспадане наново — по един на авансова и ставка, сметката за
   * аванси (412), продуктът „Аванс“ от конфигурацията, полетата {@code bgAdvanceDeduction} и {@code
   * bgAdvanceInvoice}; текстът — преведен, само за печат. После AOS пресмята данъците и тоталите.
   * Връзката с авансите остава избраната ({@code compute} я връща към подразбиращата се).
   *
   * @return броят добавени редове
   */
  @Transactional(rollbackOn = {Exception.class})
  public int deduct(Invoice invoice) throws AxelorException {
    if (!BgVatAdvancePlan.isFinal(invoice)) {
      throw new AxelorException(
          invoice,
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          "Авансите се приспадат само в окончателна фактура за продажба или покупка.");
    }
    if (invoice.getStatusSelect() != InvoiceRepository.STATUS_DRAFT) {
      throw new AxelorException(
          invoice,
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          "Авансите се приспадат само в чернова на фактурата.");
    }
    Set<Invoice> links =
        invoice.getAdvancePaymentInvoiceSet() == null
            ? new HashSet<>()
            : new HashSet<>(invoice.getAdvancePaymentInvoiceSet());
    if (invoice.getInvoiceLineList() != null) {
      invoice.getInvoiceLineList().removeIf(BgVatAdvancePlan::isDeduction);
    }
    AccountConfig config = accountConfigService.getAccountConfig(invoice.getCompany());
    Account account = advanceAccount(config, BgVatAdvancePlan.isPurchase(invoice));
    Product product = config.getAdvancePaymentProduct();
    int sequence =
        invoice.getInvoiceLineList() == null
            ? 0
            : invoice.getInvoiceLineList().stream()
                .mapToInt(InvoiceLine::getSequence)
                .max()
                .orElse(0);
    int added = 0;
    for (Invoice advance : linkedWithVat(invoice)) {
      String label =
          BgVatAdvancePlan.deductionLabel(advance, I18n.get(BgVatAdvancePlan.DEDUCTION_LABEL));
      for (BgVatAdvancePlan.Deduction d : BgVatAdvancePlan.deductions(advance)) {
        invoice.addInvoiceLineListItem(line(d, label, product, account, ++sequence));
        added++;
      }
    }
    invoiceService.compute(invoice);
    invoice.setAdvancePaymentInvoiceSet(links);
    invoiceRepository.save(invoice);
    return added;
  }

  static InvoiceLine line(
      BgVatAdvancePlan.Deduction d, String label, Product product, Account account, int sequence) {
    InvoiceLine line = new InvoiceLine();
    line.setSequence(sequence);
    line.setBgAdvanceDeduction(true);
    line.setBgAdvanceInvoice(d.getAdvance());
    line.setBgVatCreditType(d.getCreditType());
    line.setProduct(product);
    line.setUnit(product == null ? null : product.getUnit());
    line.setProductName(label);
    line.setQty(BigDecimal.ONE);
    line.setPrice(d.getBase());
    line.setPriceDiscounted(d.getBase());
    line.setInTaxPrice(d.getBase().add(d.getVat()));
    line.setExTaxTotal(d.getBase());
    line.setInTaxTotal(d.getBase().add(d.getVat()));
    line.setCompanyExTaxTotal(d.getCompanyBase());
    line.setCompanyInTaxTotal(d.getCompanyBase().add(d.getCompanyVat()));
    line.setTaxLineSet(
        d.getTaxLine() == null ? new HashSet<>() : new HashSet<>(Set.of(d.getTaxLine())));
    line.setAccount(account);
    return line;
  }

  /**
   * Окончателната не се осчетоводява, докато не приспадне с ред всяка закачена авансова фактура с
   * ДДС статия — иначе ДДС-ът на аванса се начислява втори път.
   */
  public void checkDeductions(Invoice invoice) throws AxelorException {
    if (!isSalesFinal(invoice)) {
      return;
    }
    List<String> missing = BgVatAdvancePlan.missingDeductions(invoice, linkedWithVat(invoice));
    if (!missing.isEmpty()) {
      throw new AxelorException(
          invoice,
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          "Закачените авансови фактури не са приспаднати с ред — натиснете „Приспадни авансите“"
              + " или махнете аванса от връзката. Иначе ДДС-ът на аванса се начислява втори път: "
              + String.join("; ", missing));
    }
  }

  /** Окончателна, която приспада аванс с ред, и сумата ѝ е 0 — статията ѝ е наша. */
  public boolean needsZeroFinalMove(Invoice invoice) {
    return BgVatAdvancePlan.isFinal(invoice)
        && invoice.getInTaxTotal().signum() == 0
        && BgVatAdvancePlan.hasDeductions(invoice);
  }

  /**
   * Окончателна със сума 0 — авансите покриват всичко: Дт 412 / Кт 702 за основата (покупка — Дт
   * 60x / Кт 402), по сметките на редовете. ДДС-ът е начислен с авансовите фактури. AOS не съставя
   * статия при нулева сума ({@code VentilateState.setMove}), а приходът трябва да се признае.
   */
  @Transactional(rollbackOn = {Exception.class})
  public Move createZeroFinalMove(Invoice invoice) throws AxelorException {
    Company company = invoice.getCompany();
    LocalDate date = invoice.getInvoiceDate();
    String origin = invoice.getInvoiceId();
    String description = "Фактура № " + origin + " — изцяло платена с аванс";
    Move move =
        moveCreateService.createMove(
            invoice.getJournal(),
            company,
            company.getCurrency(),
            invoice.getPartner(),
            date,
            invoice.getOriginDate(),
            null,
            invoice.getFiscalPosition(),
            MoveRepository.TECHNICAL_ORIGIN_AUTOMATIC,
            functionalOrigin(invoice),
            origin,
            description,
            null);
    move.setInvoice(invoice);
    int counter = 1;
    List<MoveLine> lines = new ArrayList<>();
    for (BgVatAdvancePlan.Posting p : BgVatAdvancePlan.zeroFinalPostings(invoice)) {
      lines.add(
          moveLineCreateService.createMoveLine(
              move,
              invoice.getPartner(),
              p.getAccount(),
              p.getAmount(),
              p.isDebit(),
              date,
              counter++,
              origin,
              description));
    }
    lines.forEach(move::addMoveLineListItem);
    Move saved = save(move);
    account(saved);
    invoice.setMove(saved);
    return saved;
  }

  /**
   * Лентиране на 412 (402): плащанията на приспаднатите авансови срещу ДДС статиите им и статията
   * на окончателната. Продажба — плащането е Кт 412, другите Дт; покупка — плащането е Дт 402.
   * Лентиран аванс AOS вече не предлага за следваща фактура ({@code
   * InvoiceServiceImpl.removeBecauseOfAmountRemaining}).
   */
  public void reconcile(Invoice invoice) throws AxelorException {
    List<Invoice> deducted = new ArrayList<>(BgVatAdvancePlan.deductedAdvances(invoice));
    deducted.removeIf(a -> vatMove(a) == null);
    if (deducted.isEmpty()) {
      return;
    }
    boolean purchase = BgVatAdvancePlan.isPurchase(invoice);
    Account account =
        advanceAccount(accountConfigService.getAccountConfig(invoice.getCompany()), purchase);
    List<MoveLine> payments = new ArrayList<>();
    List<MoveLine> others = new ArrayList<>();
    for (Invoice advance : deducted) {
      if (advance.getInvoicePaymentList() != null) {
        for (InvoicePayment payment : advance.getInvoicePaymentList()) {
          if (payment.getStatusSelect() == InvoicePaymentRepository.STATUS_VALIDATED
              && payment.getMove() != null) {
            payments.addAll(open(payment.getMove(), account, purchase));
          }
        }
      }
      others.addAll(open(vatMove(advance), account, !purchase));
    }
    if (invoice.getMove() != null) {
      others.addAll(open(invoice.getMove(), account, !purchase));
    }
    List<MoveLine> credits = purchase ? others : payments;
    List<MoveLine> debits = purchase ? payments : others;
    Map<MoveLine, BigDecimal> left = new IdentityHashMap<>();
    credits.forEach(c -> left.put(c, c.getAmountRemaining().abs()));
    debits.forEach(d -> left.put(d, d.getAmountRemaining().abs()));
    for (MoveLine debit : debits) {
      for (MoveLine credit : credits) {
        BigDecimal amount = left.get(debit).min(left.get(credit));
        if (amount.signum() <= 0) {
          continue;
        }
        Reconcile reconcile = reconcileService.createReconcile(debit, credit, amount, false);
        reconcileService.confirmReconcile(reconcile, false, false);
        left.put(debit, left.get(debit).subtract(amount));
        left.put(credit, left.get(credit).subtract(amount));
      }
    }
  }

  private static List<MoveLine> open(Move move, Account account, boolean debit) {
    if (move == null || move.getMoveLineList() == null) {
      return Collections.emptyList();
    }
    return move.getMoveLineList().stream()
        .filter(l -> account.equals(l.getAccount()))
        .filter(l -> (debit ? l.getDebit() : l.getCredit()).signum() > 0)
        .filter(l -> l.getAmountRemaining().signum() != 0)
        .collect(Collectors.toList());
  }

  /** Плащанията на приспаднатите с ред авансови — техните статии AOS не бива да приспада. */
  public Set<Move> deductedAdvancePaymentMoves(Invoice invoice) {
    Set<Move> moves = new LinkedHashSet<>();
    if (!BgVatAdvancePlan.isFinal(invoice)) {
      return moves;
    }
    for (Invoice advance : BgVatAdvancePlan.deductedAdvances(invoice)) {
      if (vatMove(advance) == null || advance.getInvoicePaymentList() == null) {
        continue;
      }
      for (InvoicePayment payment : advance.getInvoicePaymentList()) {
        if (payment.getMove() != null) {
          moves.add(payment.getMove());
        }
      }
    }
    return moves;
  }

  // ---------------------------------------------------------------------------
  // Предупрежденията при генериране на дневниците
  // ---------------------------------------------------------------------------

  /**
   * Предупрежденията за авансите в периода — показват се при генериране на ДДС декларацията:
   *
   * <ul>
   *   <li>авансови фактури с ДДС без ДДС статия (издадени преди поправката; от доставчик — и без
   *       право на кредит) — само справка;
   *   <li>окончателни фактури без връзка с платена авансова на същия контрагент — двойно облагане
   *       (двоен кредит)?
   *   <li>авансови от доставчик, неплатени до края на периода — кредитът възниква с плащането (ЗДДС
   *       чл. 68 ал. 1 т. 2 и ал. 2);
   *   <li>окончателни от доставчик със закачена авансова без ред за приспадане — двоен кредит?
   * </ul>
   */
  public List<String> periodWarnings(Company company, LocalDate from, LocalDate to) {
    List<String> warnings = new ArrayList<>();
    List<Invoice> old =
        BgVatPeriodScope.bind(
                invoiceRepository
                    .all()
                    .filter(PERIOD_WITHOUT_VAT_MOVE)
                    .bind("company", company)
                    .order("invoiceDate"),
                from,
                to)
            .fetch();
    if (!old.isEmpty()) {
      warnings.add(
          "Авансови фактури с ДДС без ДДС статия — не са в дневниците (издадени, преди авансите да"
              + " влизат в дневниците, или от доставчик без право на кредит; справка „Авансови"
              + " фактури без ДДС статия“): "
              + old.stream().map(BgVatAdvanceService::label).collect(Collectors.joining(", ")));
    }
    List<String> unlinkedSales =
        unlinked(company, from, to, InvoiceRepository.OPERATION_TYPE_CLIENT_SALE);
    if (!unlinkedSales.isEmpty()) {
      warnings.add(
          "Окончателни фактури без връзка с платена авансова фактура на същия клиент — ДДС-ът на"
              + " аванса е начислен с авансовата, проверете за двойно облагане: "
              + String.join("; ", unlinkedSales));
    }
    List<String> unlinkedPurchases =
        unlinked(company, from, to, InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    if (!unlinkedPurchases.isEmpty()) {
      warnings.add(
          "Окончателни фактури от доставчик без връзка с платена авансова фактура на същия"
              + " доставчик — кредитът по аванса е ползван с авансовата, проверете за двоен"
              + " данъчен кредит: "
              + String.join("; ", unlinkedPurchases));
    }
    List<Invoice> unpaid =
        BgVatPeriodScope.bind(
                invoiceRepository
                    .all()
                    .filter(PERIOD_PURCHASE_ADVANCES)
                    .bind("company", company)
                    .order("invoiceDate"),
                from,
                to)
            .fetch()
            .stream()
            .filter(a -> BgVatAdvancePlan.unpaidAt(a, to))
            .collect(Collectors.toList());
    if (!unpaid.isEmpty()) {
      warnings.add(
          "Авансови фактури от доставчик, неплатени до края на периода — правото на данъчен"
              + " кредит по аванс възниква с плащането (ЗДДС чл. 68 ал. 1 т. 2 и ал. 2); отразете"
              + " ги в периода на плащането: "
              + unpaid.stream().map(BgVatAdvanceService::label).collect(Collectors.joining(", ")));
    }
    List<String> notDeducted = new ArrayList<>();
    for (Invoice fin :
        periodFinals(company, from, to, InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE)) {
      List<String> missing = BgVatAdvancePlan.missingDeductions(fin, linkedWithVat(fin));
      if (!missing.isEmpty()) {
        notDeducted.add(label(fin) + " — " + String.join(", ", missing));
      }
    }
    if (!notDeducted.isEmpty()) {
      warnings.add(
          "Окончателни фактури от доставчик със закачена авансова без ред за приспадане — двоен"
              + " данъчен кредит? Документът на доставчика решава; ако той е приспаднал аванса,"
              + " натиснете „Приспадни авансите“: "
              + String.join("; ", notDeducted));
    }
    List<String> early = deductedBeforeReported(company, from, to);
    if (!early.isEmpty()) {
      warnings.add(
          "Авансова фактура, отразена в по-късен период от окончателната, която я приспада —"
              + " данъкът по аванса е намален, преди да е деклариран; проверете декларациите на"
              + " двата периода: "
              + String.join("; ", early));
    }
    return warnings;
  }

  /**
   * Приспаднатите аванси, отразени СЛЕД окончателната (решение Р11 от 02.11.2026 — предупреждение,
   * не отказ: пропуснатата авансова пак трябва да влезе в периода на установяване, ЗДДС чл. 126 ал.
   * 3 т. 1). Двете страни: окончателните на периода и авансовите на периода.
   */
  private List<String> deductedBeforeReported(Company company, LocalDate from, LocalDate to) {
    Set<String> early = new LinkedHashSet<>();
    for (int op :
        new int[] {
          InvoiceRepository.OPERATION_TYPE_CLIENT_SALE,
          InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE
        }) {
      for (Invoice fin : periodFinals(company, from, to, op)) {
        early.addAll(deductedBeforeReported(fin));
      }
    }
    List<Invoice> advances =
        BgVatPeriodScope.bind(
                invoiceRepository
                    .all()
                    .filter(PERIOD_ADVANCES)
                    .bind("company", company)
                    .order("invoiceDate"),
                from,
                to)
            .fetch();
    for (Invoice advance : advances) {
      for (Invoice fin :
          invoiceRepository.all().filter(DEDUCTING_FINALS).bind("advance", advance).fetch()) {
        if (reportedLater(advance, fin)) {
          early.add(earlyLabel(advance, fin));
        }
      }
    }
    return new ArrayList<>(early);
  }

  /** Авансовите, приспаднати с ред в {@code fin} и отразени след нея. */
  static List<String> deductedBeforeReported(Invoice fin) {
    List<String> early = new ArrayList<>();
    if (fin.getInvoiceLineList() == null) {
      return early;
    }
    for (InvoiceLine line : fin.getInvoiceLineList()) {
      Invoice advance = line.getBgAdvanceInvoice();
      if (BgVatAdvancePlan.isDeduction(line) && advance != null && reportedLater(advance, fin)) {
        early.add(earlyLabel(advance, fin));
      }
    }
    return early;
  }

  /** Авансовата е отразена в по-късен период от окончателната, която я приспада. */
  static boolean reportedLater(Invoice advance, Invoice fin) {
    String advancePeriod = BgVatReportingPeriod.effective(advance);
    String finalPeriod = BgVatReportingPeriod.effective(fin);
    return advancePeriod != null && finalPeriod != null && advancePeriod.compareTo(finalPeriod) > 0;
  }

  private static String earlyLabel(Invoice advance, Invoice fin) {
    return "авансова "
        + label(advance)
        + " ("
        + BgVatReportingPeriod.label(BgVatReportingPeriod.effective(advance))
        + ") — приспадната с "
        + label(fin)
        + " ("
        + BgVatReportingPeriod.label(BgVatReportingPeriod.effective(fin))
        + ")";
  }

  private List<Invoice> periodFinals(Company company, LocalDate from, LocalDate to, int op) {
    return BgVatPeriodScope.bind(
            invoiceRepository
                .all()
                .filter(PERIOD_FINALS)
                .bind("company", company)
                .bind("op", op)
                .order("invoiceDate"),
            from,
            to)
        .fetch();
  }

  /**
   * Окончателните на периода с неприспадната платена авансова на същия контрагент извън връзката.
   */
  private List<String> unlinked(Company company, LocalDate from, LocalDate to, int op) {
    List<String> unlinked = new ArrayList<>();
    for (Invoice fin : periodFinals(company, from, to, op)) {
      Set<Invoice> linked =
          fin.getAdvancePaymentInvoiceSet() == null
              ? Collections.emptySet()
              : fin.getAdvancePaymentInvoiceSet();
      List<Invoice> open =
          invoiceRepository
              .all()
              .filter(OPEN_ADVANCES)
              .bind("company", company)
              .bind("partner", fin.getPartner())
              .bind("op", op)
              .bind("date", fin.getInvoiceDate())
              .fetch()
              .stream()
              .filter(a -> !linked.contains(a))
              .collect(Collectors.toList());
      if (!open.isEmpty()) {
        unlinked.add(
            label(fin)
                + " — неприспадната платена авансова "
                + open.stream().map(BgVatAdvanceService::label).collect(Collectors.joining(", ")));
      }
    }
    return unlinked;
  }

  private static String label(Invoice invoice) {
    String date = invoice.getInvoiceDate() == null ? "" : DATE.format(invoice.getInvoiceDate());
    return "ф-ра № " + Objects.toString(invoice.getInvoiceId(), "") + " от " + date;
  }
}

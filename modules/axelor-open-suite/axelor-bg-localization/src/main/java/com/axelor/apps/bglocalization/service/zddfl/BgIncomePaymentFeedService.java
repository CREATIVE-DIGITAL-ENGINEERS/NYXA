package com.axelor.apps.bglocalization.service.zddfl;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoicePayment;
import com.axelor.apps.account.db.repo.InvoicePaymentRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.db.BgIndividualIncomePayment;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.repo.BgIndividualIncomePaymentRepository;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipRepository;
import com.axelor.apps.bglocalization.service.BgCivilContractResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * АВТО-ЗАХРАНВАНЕТО НА РЕГИСТЪРА — <b>РЪЧНО ДЕЙСТВИЕ, А НЕ СЪБИТИЕ</b>.
 *
 * <p><b>⚠️ БЕЗ НИТО ЕДИН HOOK ПО ЖИЗНЕНИЯ ЦИКЪЛ, И ТОВА Е РЕШЕНИЕ, НЕ ЕТАП.</b> Ред в регистъра се
 * създава от ОПЕРАТОР, който е избрал период и е натиснал бутон — не при потвърждаване на фиш и не
 * при плащане на фактура. Три довода, всеки измерен:
 *
 * <ol>
 *   <li><b>Датата по чл. 11 идва СЛЕД документа.</b> Фишът се потвърждава, преди да е платен;
 *       {@code bgPaidDate} се попълва после и от ръка. Hook при потвърждаване би създал ред без
 *       дата на придобиване — тоест ред, който не знае в кое тримесечие влиза.
 *   <li><b>Изходните документи се редактират.</b> Сгрешен фиш се ОТКАЗВА и се прави нов; фактура се
 *       сторнира. Hook пише при всяко от тези събития и оставя следа, която никой не сверява.
 *   <li><b>Прогонът е ПРЕГЛЕДИМ.</b> Той брои, назовава отказите и има пробен режим. Hook, който
 *       мълчаливо не е създал ред заради непопълнен тип идентификатор, се открива чак от НАП.
 * </ol>
 *
 * <p><b>═══ ⚠️ ИДЕМПОТЕНТНОСТТА Е НА РЕФЕРЕНЦИЯТА, НЕ НА ПЕРИОДА ═══</b>
 *
 * <p>Всеки ред, писан оттук, носи {@code sourceRef = "<Модел>#<id>"}. Повторният прогон НАМИРА своя
 * ред по тройката {@code (source, sourceRef, incomeType)} и го обновява. Обхватът (година,
 * тримесечие) е само ФИЛТЪР на обхода — той не участва в ключа. Затова прогон за цялата година и
 * прогон за второто тримесечие пипат едни и същи редове и не се сблъскват.
 *
 * <p>⚠️ Същата тройка е и {@code unique-constraint} на домейна. Услугата пази правилото при всеки
 * прогон; ограничението го пази и когато два прогона се засекат.
 *
 * <p><b>═══ ⚠️ КАКВО СЕ ОПРЕСНЯВА И КАКВО СЕ ПАЗИ ПРИ ПОВТОРЕН ПРОГОН ═══</b>
 *
 * <p>По образеца на {@code BgArt73Para6ReportService#carryOver}, но с ЕДНО СЪЩЕСТВЕНО различие:
 *
 * <ul>
 *   <li><b>ОПРЕСНЯВАТ СЕ</b> лицето, платецът, видът доход, брутото, ЗОВ и датата — и след тях
 *       {@link BgIndividualIncomePaymentService#computeInto} преизчислява разходите, основата,
 *       данъка и вида данък. Те са ИЗВЕДЕНИ от изходния документ; замразени, редът щеше да пази
 *       числа от поправен документ.
 *   <li><b>ПАЗИ СЕ код корекция.</b> Човек го е сложил на „1“ или „8“ по документ, който системата
 *       не вижда; върнат на „0“, коригиращата справка би станала основна.
 *   <li><b>ПРЕИЗВЕЖДА СЕ признакът по чл. 43 ал. 5</b> — и това НЕ е „пазене“. Той не е ръчно поле
 *       на реда, а следствие от датата на писмената декларация на ЛИЦЕТО (виж {@link
 *       BgIncomePaymentFeedMapper}). Замразен, той би задържал стар статус: декларация, подадена
 *       между двата прогона, нямаше да проработи, а заличена — нямаше да отпадне.
 *   <li><b>НЕ СЕ ПИПАТ</b> {@code isForeign} и {@code countryCode} — те нямат източник и остават
 *       такива, каквито човек ги е оставил.
 * </ul>
 *
 * <p><b>═══ ⚠️ РЪЧНО ВЪВЕДЕНИТЕ РЕДОВЕ СА НЕДОСЕГАЕМИ ПО УСТРОЙСТВО ═══</b>
 *
 * <p>Всяка заявка оттук иска {@code self.sourceRef = :ref}, а ръчният ред няма референция. Тоест
 * той не се намира, не се обновява и не се трие — не заради флаг, който някой може да вдигне, а
 * защото ключът, по който се търси, го няма върху него.
 */
@Singleton
public class BgIncomePaymentFeedService {

  /**
   * Партньорът е ФИЗИЧЕСКО ЛИЦЕ — {@code partner.partner.type.select}, стойност 2.
   *
   * <p>⚠️ НЕ Е {@code isContact}. Онзи флаг казва „това е контактно лице НА фирма“ — съвсем друго
   * твърдение, което е вярно и за представител на дружество, и невярно за свободна професия.
   */
  public static final int PARTNER_INDIVIDUAL = 2;

  protected final BgPaySlipRepository paySlipRepo;
  protected final BgIndividualIncomePaymentRepository paymentRepo;
  protected final InvoicePaymentRepository invoicePaymentRepo;
  protected final BgIndividualIncomePaymentService paymentService;

  @Inject
  public BgIncomePaymentFeedService(
      BgPaySlipRepository paySlipRepo,
      BgIndividualIncomePaymentRepository paymentRepo,
      InvoicePaymentRepository invoicePaymentRepo,
      BgIndividualIncomePaymentService paymentService) {
    this.paySlipRepo = paySlipRepo;
    this.paymentRepo = paymentRepo;
    this.invoicePaymentRepo = invoicePaymentRepo;
    this.paymentService = paymentService;
  }

  /**
   * ПУСКА ПРОГОНА за платец, година и (по избор) тримесечие.
   *
   * <p>⚠️ Тримесечието е ПО ИЗБОР, защото двете справки питат различно: чл. 73 е ГОДИШНА, чл. 55 е
   * ТРИМЕСЕЧНА. Заковано на тримесечие, действието би искало четири прогона за годишната справка;
   * заковано на година, то не би могло да се пусне пак само за поправеното тримесечие.
   *
   * @param dryRun при {@code true} нищо не се записва — само се брои и се назовава
   */
  @Transactional(rollbackOn = Exception.class)
  public BgIncomePaymentFeedBoundaries.Facts run(
      Company company, int taxYear, Integer quarter, boolean dryRun) {

    BgIncomePaymentFeedBoundaries.Facts facts = new BgIncomePaymentFeedBoundaries.Facts();
    facts.taxYear = taxYear;
    facts.quarter = quarter;
    facts.dryRun = dryRun;

    LocalDate from = BgIncomePaymentFeedMapper.firstDayOf(taxYear, quarter);
    LocalDate to = BgIncomePaymentFeedMapper.lastDayOf(taxYear, quarter);

    feedCivilContracts(company, from, to, facts, dryRun);
    feedInvoicePayments(company, from, to, facts, dryRun);

    facts.payrollWithoutPaidDate = countCivilSlipsWithoutPaidDate(company, from, to);
    facts.manualUntouched = countManualRows(company, from, to);
    return facts;
  }

  // ═══ ГРАЖДАНСКИЯТ ДОГОВОР → ШАБЛОН A ════════════════════════════════════════

  /**
   * ФИШОВЕТЕ ПО ГРАЖДАНСКИ ДОГОВОР ЗА ПЕРИОДА → редове на регистъра.
   *
   * <p><b>⚠️ ЕДИН ФИШ = ЕДИН РЕД.</b> Договорът носи ЕДИН вид доход ({@code
   * EmploymentContract.bgIncomeType}), тоест едно плащане по него не се разделя на кодове. Оттам и
   * защо референцията е на ФИША, а не на перо: перата на един фиш са едно плащане по един договор.
   */
  protected void feedCivilContracts(
      Company company,
      LocalDate from,
      LocalDate to,
      BgIncomePaymentFeedBoundaries.Facts facts,
      boolean dryRun) {

    for (BgPaySlip slip : civilSlips(company, from, to)) {
      facts.payrollScanned++;

      EmploymentContract contract = slip.getEmploymentContract();
      BgIncomeType type = contract == null ? null : contract.getBgIncomeType();
      Partner partner = partnerOf(contract);

      // ⚠️ Колона 11 е accruedTaxableTotal, а НЕ grossTotal — виж #incomeOf.
      BigDecimal gross = incomeOf(slip);
      BigDecimal insurance = BgPaySlipResolver.employeeContributions(slip.getBgPaySlipLineList());

      BgIncomePaymentFeedCandidate candidate =
          new BgIncomePaymentFeedCandidate(
              BgIncomePaymentFeedMapper.SOURCE_PAYROLL,
              BgIncomePaymentFeedMapper.refOf(
                  BgIncomePaymentFeedMapper.MODEL_PAY_SLIP, slip.getId()),
              slip.getBgPaidDate(),
              gross,
              insurance,
              partner != null,
              contract != null && contract.getPayCompany() != null,
              type == null ? null : type.getTaxPattern(),
              partner == null ? null : partner.getBgIdentifierType(),
              partner == null ? null : partner.getRegistrationCode(),
              partner == null ? null : partner.getBgSelfInsuredDeclaredOn());

      upsert(
          candidate,
          partner,
          contract == null ? null : contract.getPayCompany(),
          type,
          facts,
          dryRun);
    }
  }

  /**
   * КОЛОНА 11 ЗА ФИШ ПО ГРАЖДАНСКИ ДОГОВОР — облагаемите начисления за СОБСТВЕНИЯ период.
   *
   * <p><b>⚠️ НЕ Е {@code grossTotal}, И РАЗЛИКАТА Е ИЗМЕРИМА В ДВЕ ПОСОКИ.</b> {@code grossTotal}
   * събира начисленията И обезщетенията (9xx) и НЕ пита за периода на перото. Тоест: (1)
   * ретро-делта за друг месец би влязла в дохода на ТОЗИ, а тя вече е декларирана за своя; (2)
   * необлагаемо плащане по чл. 24 ал. 2 би вдигнало колона 11 — а тя иска дохода, който се облага.
   *
   * <p>⚠️ Същият агрегат пълни и колона 3 на справката по ал. 6 ({@code BgArt73Para6Aggregator}) —
   * нарочно едно четене за двата образеца, а не две определения за едно число.
   *
   * <p>⚠️ И заглавното {@code bgGrossTotal} не се чете: то се пише само в ЧЕРНОВА ({@code
   * BgPaySlipService#recompute} излиза веднага за потвърден фиш) и е единственото число на фиша,
   * което може да противоречи на перата.
   */
  protected BigDecimal incomeOf(BgPaySlip slip) {
    return BgPaySlipResolver.accruedTaxableTotal(slip.getBgPaySlipLineList());
  }

  /** Лицето зад договора — по идиома на {@code BgD1RecordBuilder} и справката по ал. 6. */
  protected Partner partnerOf(EmploymentContract contract) {
    Employee employee = contract == null ? null : contract.getEmployee();
    return employee == null ? null : employee.getContactPartner();
  }

  /**
   * Фишовете по граждански договор на платеца, изплатени в периода.
   *
   * <p>⚠️ Само ПОТВЪРДЕНИ и ИЗПЛАТЕНИ — по същия довод като справката по ал. 6: чернова е доход,
   * който още може да се промени, а отказан фиш не е изплатен.
   *
   * <p>⚠️ Коригиращите и заличаващите фишове НЕ влизат. Те носят делта за ДРУГ месец и своя
   * ретро-логика; ред от тях в регистъра би добавил същия доход втори път, с днешна дата. Корекция
   * в справката се подава с код корекция „1“ или „8“ върху вече съществуващия ред — ръчно, защото
   * решението кой ред се коригира е на човека.
   */
  protected List<BgPaySlip> civilSlips(Company company, LocalDate from, LocalDate to) {
    return paySlipRepo
        .all()
        .filter(
            "self.employmentContract.payCompany = :company "
                + "AND self.employmentContract.bgInsuredTypeCode = :civil "
                + "AND self.statusSelect IN (:confirmed, :paid) "
                + "AND self.bgIsCorrection = FALSE AND self.bgIsDeletion = FALSE "
                + "AND self.bgPaidDate >= :from AND self.bgPaidDate <= :to")
        .bind("company", company)
        .bind("civil", BgCivilContractResolver.INSURED_TYPE_CODE)
        .bind("confirmed", BgPaySlipResolver.CONFIRMED)
        .bind("paid", BgPaySlipResolver.PAID)
        .bind("from", from)
        .bind("to", to)
        .order("bgPaidDate")
        .order("id")
        .fetch();
  }

  /**
   * ФИШОВЕТЕ БЕЗ ДАТА НА ИЗПЛАЩАНЕ — БРОЯТ СЕ, А НЕ СЕ ВКЛЮЧВАТ.
   *
   * <p>⚠️ Обходът ги пропуска по устройство (той филтрира по {@code bgPaidDate}), тоест без този
   * брояч те щяха да изчезнат безшумно: прогонът щеше да каже „готово“ за период, от който липсва
   * половината доход. Периодът тук се чете от {@code period.fromDate} — единственото, което такъв
   * фиш носи.
   */
  protected int countCivilSlipsWithoutPaidDate(Company company, LocalDate from, LocalDate to) {
    return (int)
        paySlipRepo
            .all()
            .filter(
                "self.employmentContract.payCompany = :company "
                    + "AND self.employmentContract.bgInsuredTypeCode = :civil "
                    + "AND self.statusSelect IN (:confirmed, :paid) "
                    + "AND self.bgIsCorrection = FALSE AND self.bgIsDeletion = FALSE "
                    + "AND self.bgPaidDate IS NULL "
                    + "AND self.period.fromDate >= :from AND self.period.fromDate <= :to")
            .bind("company", company)
            .bind("civil", BgCivilContractResolver.INSURED_TYPE_CODE)
            .bind("confirmed", BgPaySlipResolver.CONFIRMED)
            .bind("paid", BgPaySlipResolver.PAID)
            .bind("from", from)
            .bind("to", to)
            .count();
  }

  // ═══ ФАКТУРИТЕ КЪМ ФИЗИЧЕСКИ ЛИЦА → ПО ВИД ДОХОД (A / B / E …) ══════════════

  /**
   * ПЛАЩАНИЯТА ПО ФАКТУРИ ОТ ФИЗИЧЕСКИ ЛИЦА ЗА ПЕРИОДА → редове на регистъра.
   *
   * <p><b>⚠️ НОСИТЕЛЯТ Е ПЛАЩАНЕТО, А НЕ ФАКТУРАТА — И ТОВА Е ЧЛ. 11, НЕ УДОБСТВО.</b> Ал. 1, т. 1
   * и 2: доходът се смята за придобит на датата на плащането в брой или на заверяването на
   * сметката. Ключирано на фактурата, издадена през декември и платена през януари фактура би
   * вкарала дохода в ГРЕШНАТА данъчна година — и то в справка, която се подава веднъж.
   *
   * <p><b>⚠️ И НАЕМЪТ НЕ Е ОТДЕЛЕН МОСТ.</b> Той е СЪЩИЯТ обход, различаван единствено по вида
   * доход на реда: кодове 401–403 носят шаблон B и оттам чл. 31 (10 на сто разходи) и чл. 44 ал. 4
   * (без приспадане на осигуровки). Трети обход за наем би бил трето копие на едно и също минаване
   * през плащанията, което утре се разминава с другите две.
   *
   * <p><b>⚠️ ЧАСТИЧНОТО ПЛАЩАНЕ ДАВА ЧАСТИЧЕН ДОХОД.</b> Придобитото е ПЛАТЕНОТО, не начисленото:
   * доходът се дели по дела на плащането в общата сума на фактурата. Взето цялото, първата вноска
   * по фактура на разсрочено плащане би обложила доход, който лицето още не е получило — и то в
   * тримесечие, което не е неговото.
   */
  protected void feedInvoicePayments(
      Company company,
      LocalDate from,
      LocalDate to,
      BgIncomePaymentFeedBoundaries.Facts facts,
      boolean dryRun) {

    for (InvoicePayment payment : invoicePayments(company, from, to)) {
      facts.invoiceScanned++;

      Invoice invoice = payment.getInvoice();
      Partner partner = invoice == null ? null : invoice.getPartner();
      BigDecimal share = shareOf(payment, invoice);

      if (share == null) {
        facts.invoicesWithoutTotal++;
        continue;
      }

      Map<BgIncomeType, BigDecimal> byType = incomeByType(invoice, facts);
      if (byType.isEmpty()) {
        // ⚠️ Фактура, на която НИТО ЕДИН ред не носи вид доход, е ЕДИН отказ, а не мълчание:
        // решателят го назовава като липсващ вид доход (той пита за шаблона преди всичко
        // останало). Ред без запис, който не се брои, е доход, който няма да влезе в справка,
        // подавана веднъж годишно.
        facts.refuse(
            BgIncomePaymentFeedMapper.decide(
                invoiceCandidate(payment, partner, invoice, null, BigDecimal.ZERO)));
        continue;
      }

      for (Map.Entry<BgIncomeType, BigDecimal> entry : byType.entrySet()) {
        BgIncomeType type = entry.getKey();
        BigDecimal gross = BgIncomePaymentFeedMapper.incomeFor(share, entry.getValue());

        upsert(
            invoiceCandidate(payment, partner, invoice, type, gross),
            partner,
            invoice == null ? null : invoice.getCompany(),
            type,
            facts,
            dryRun);
      }
    }
  }

  /** Кандидатът за един (плащане, вид доход). */
  protected BgIncomePaymentFeedCandidate invoiceCandidate(
      InvoicePayment payment,
      Partner partner,
      Invoice invoice,
      BgIncomeType type,
      BigDecimal gross) {
    return new BgIncomePaymentFeedCandidate(
        BgIncomePaymentFeedMapper.SOURCE_INVOICE,
        BgIncomePaymentFeedMapper.refOf(
            BgIncomePaymentFeedMapper.MODEL_INVOICE_PAYMENT, payment.getId()),
        payment.getPaymentDate(),
        gross,
        // ⚠️ ЗОВ по фактура са НУЛА, и това е стойност, не липса. Платецът по фактура не удържа
        // осигуровки: вноските на свободната професия са ЗА НЕЙНА СМЕТКА (КСО чл. 6 ал. 8) и тя ги
        // внася сама. Колона 12 на справката остава празна, а чл. 43 ал. 1 няма какво да извади.
        BigDecimal.ZERO,
        partner != null,
        invoice != null && invoice.getCompany() != null,
        type == null ? null : type.getTaxPattern(),
        partner == null ? null : partner.getBgIdentifierType(),
        partner == null ? null : partner.getRegistrationCode(),
        partner == null ? null : partner.getBgSelfInsuredDeclaredOn());
  }

  /**
   * Делът на плащането — тънък шев към {@link BgIncomePaymentFeedMapper#share}.
   *
   * <p>Правилото (и защо делителят е сумата С данък, а множителят — без) живее там, при останалите
   * чисти правила; тук стои само изваждането на двете числа от обектите.
   */
  protected BigDecimal shareOf(InvoicePayment payment, Invoice invoice) {
    return invoice == null
        ? null
        : BgIncomePaymentFeedMapper.share(payment.getAmount(), invoice.getInTaxTotal());
  }

  /**
   * РЕДОВЕТЕ НА ФАКТУРАТА, СЪБРАНИ ПО ВИД ДОХОД — сумите БЕЗ данък.
   *
   * <p>⚠️ Ред без вид доход се БРОИ и се пропуска, а не се приписва на съседния код. Една фактура
   * може да носи наем (шаблон B) и услуга (шаблон A); мълчаливо долепен, непопълненият ред би
   * получил чужд член, чужда разходна ставка и чужд код на образеца.
   */
  protected Map<BgIncomeType, BigDecimal> incomeByType(
      Invoice invoice, BgIncomePaymentFeedBoundaries.Facts facts) {
    Map<BgIncomeType, BigDecimal> out = new LinkedHashMap<>();
    if (invoice == null || invoice.getInvoiceLineList() == null) {
      return out;
    }
    for (InvoiceLine line : invoice.getInvoiceLineList()) {
      if (line == null) {
        continue;
      }
      BgIncomeType type = line.getBgIncomeType();
      if (type == null) {
        facts.invoiceLinesWithoutIncomeType++;
        continue;
      }
      // ⚠️ БЕЗ проверка за null: генерираният getter за decimal връща ZERO и за непопълнена
      // колона — виж BgDefaultedGetterTest. Ред с празна сума добавя нула.
      out.merge(type, line.getExTaxTotal(), BigDecimal::add);
    }
    return out;
  }

  /**
   * Плащанията по ВЕНТИЛИРАНИ ФАКТУРИ ЗА ПОКУПКА от ФИЗИЧЕСКИ ЛИЦА, извършени в периода.
   *
   * <p>⚠️ Само {@code OPERATION_TYPE_SUPPLIER_PURCHASE}. Кредитното известие ({@code
   * SUPPLIER_REFUND}) е ОБРАТНО движение и редът му в регистъра би бил отрицателно бруто — а
   * изплатен доход под нула не съществува ({@link BgIncomeTaxResolver} го отказва). Корекция на
   * вече подаден доход се прави с код корекция „1“ или „8“ върху съществуващия ред, ръчно, защото
   * кой ред се коригира е решение на човек. ОБЯВЕНА ГРАНИЦА.
   *
   * <p>⚠️ Само ВАЛИДИРАНИ плащания: чернова е намерение, отказано плащане не е плащане, а чл. 11
   * връзва придобиването с извършеното.
   */
  protected List<InvoicePayment> invoicePayments(Company company, LocalDate from, LocalDate to) {
    return invoicePaymentRepo
        .all()
        .filter(
            "self.invoice.company = :company "
                + "AND self.invoice.operationTypeSelect = :purchase "
                + "AND self.invoice.statusSelect = :ventilated "
                + "AND self.invoice.partner.partnerTypeSelect = :individual "
                + "AND self.statusSelect = :validated "
                + "AND self.paymentDate >= :from AND self.paymentDate <= :to")
        .bind("company", company)
        .bind("purchase", InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE)
        .bind("ventilated", InvoiceRepository.STATUS_VENTILATED)
        .bind("individual", PARTNER_INDIVIDUAL)
        .bind("validated", InvoicePaymentRepository.STATUS_VALIDATED)
        .bind("from", from)
        .bind("to", to)
        .order("paymentDate")
        .order("id")
        .fetch();
  }

  // ═══ UPSERT ═════════════════════════════════════════════════════════════════

  /**
   * НАМИРА СВОЯ РЕД ИЛИ СЪЗДАВА НОВ — и в двата случая преизчислява сумите.
   *
   * <p>⚠️ Отказът на КАНДИДАТА и отказът на СМЕТКАТА се броят поотделно. Първият значи „данните на
   * изходния документ не стигат“ (попълнимо), вторият — „каталогът не носи ставка за този шаблон“
   * или „това е трудов доход и не влиза в този регистър“ (друга поправка, на друго място).
   */
  protected void upsert(
      BgIncomePaymentFeedCandidate candidate,
      Partner partner,
      Company company,
      BgIncomeType type,
      BgIncomePaymentFeedBoundaries.Facts facts,
      boolean dryRun) {

    BgIncomePaymentFeedOutcome outcome = BgIncomePaymentFeedMapper.decide(candidate);
    if (!outcome.isAccepted()) {
      facts.refuse(outcome);
      return;
    }

    BgIndividualIncomePayment existing = findByRef(candidate.source, candidate.sourceRef, type);
    BgIndividualIncomePayment payment =
        existing == null ? new BgIndividualIncomePayment() : existing;

    if (existing == null) {
      payment.setSource(candidate.source);
      payment.setSourceRef(candidate.sourceRef);
      payment.setCorrectionCode(BgIndividualIncomePaymentService.CORRECTION_PRIMARY);
    } else if (!BgIndividualIncomePaymentService.CORRECTION_PRIMARY.equals(
        payment.getCorrectionCode())) {
      // ⚠️ ПАЗИ СЕ, НЕ СЕ ВРЪЩА НА „0“. Човек го е сложил по документ, който системата не вижда.
      facts.correctionCodeKept++;
    }

    // ⚠️ ОПРЕСНЯВАТ СЕ — те са изведени от изходния документ и той може да е поправен.
    payment.setPartner(partner);
    payment.setCompany(company);
    payment.setIncomeType(type);
    payment.setGrossAmount(candidate.grossAmount);
    payment.setInsuranceAmount(candidate.insuranceAmount);
    payment.setPaymentDate(candidate.paymentDate);
    payment.setIdentifierType(outcome.getIdentifierType());

    // ⚠️ ПРЕИЗВЕЖДА СЕ, НЕ СЕ ПАЗИ — виж класовия коментар и BgIncomePaymentFeedMapper.
    boolean before = Boolean.TRUE.equals(payment.getSelfInsuredDeclared());
    if (existing != null && before != outcome.isSelfInsuredDeclared()) {
      facts.selfInsuredChanged++;
    }
    payment.setSelfInsuredDeclared(outcome.isSelfInsuredDeclared());

    BgIncomeTaxResult computed = paymentService.computeInto(payment);
    if (!computed.isResolved()) {
      facts.refuseCompute(computed.refusal);
      return;
    }

    if (existing == null) {
      facts.created++;
    } else {
      facts.updated++;
    }
    if (!dryRun) {
      paymentRepo.save(payment);
    }
  }

  /**
   * СВОЯТ РЕД по тройката {@code (source, sourceRef, incomeType)}.
   *
   * <p>⚠️ Видът доход Е в ключа, защото един изходен документ носи повече от един код: плащане по
   * фактура покрива и наем (401), и услуга (3073) — два реда на образеца, и двата законни. Ключ
   * само по референция би обявил втория за дубликат на първия.
   */
  protected BgIndividualIncomePayment findByRef(String source, String ref, BgIncomeType type) {
    return paymentRepo
        .all()
        .filter("self.source = :source AND self.sourceRef = :ref AND self.incomeType = :incomeType")
        .bind("source", source)
        .bind("ref", ref)
        .bind("incomeType", type)
        .fetchOne();
  }

  /**
   * РЪЧНО ВЪВЕДЕНИТЕ РЕДОВЕ В ОБХВАТА — броят се, за да ЛИЧИ, че не са пипани.
   *
   * <p>⚠️ Признакът е ЛИПСВАЩА референция, а не стойността {@code MANUAL} на {@code source}. Двете
   * съвпадат днес, но първото е това, което прави реда недосегаем: заявките на захранването търсят
   * по {@code sourceRef} и ред без него не се намира от нито една от тях.
   */
  protected int countManualRows(Company company, LocalDate from, LocalDate to) {
    return (int)
        paymentRepo
            .all()
            .filter(
                "self.company = :company AND self.sourceRef IS NULL "
                    + "AND self.paymentDate >= :from AND self.paymentDate <= :to")
            .bind("company", company)
            .bind("from", from)
            .bind("to", to)
            .count();
  }
}

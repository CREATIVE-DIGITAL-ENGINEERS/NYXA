package com.axelor.apps.bglocalization.service.payment;

import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.PaymentMode;
import com.axelor.apps.account.db.repo.PaymentModeRepository;
import com.axelor.apps.bankpayment.db.BankOrder;
import com.axelor.apps.bankpayment.db.BankOrderFileFormat;
import com.axelor.apps.bankpayment.db.BankOrderLine;
import com.axelor.apps.bankpayment.db.BankOrderLineOrigin;
import com.axelor.apps.bankpayment.db.repo.BankOrderRepository;
import com.axelor.apps.bankpayment.service.bankorder.BankOrderCreateService;
import com.axelor.apps.bankpayment.service.bankorder.BankOrderLineService;
import com.axelor.apps.bankpayment.service.bankorder.BankOrderService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.repo.BankDetailsRepository;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipRepository;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ПОСЛЕДНАТА МИЛЯ НАВЪН: НЕТАТА НА ЕДИН ПЕРИОД → ЕДИН {@code BankOrder} → ЕДИН {@code pain.001}.
 *
 * <p><b>⚠️ ТОВА НЕ Е ПЛАЩАНЕ И НЕ СЕ ОПИТВА ДА БЪДЕ.</b> Услугата ражда ФАЙЛ и го закача към
 * нареждането. Нищо тук не се свързва с банка, не подава и не мести пари; фишът НЕ става „Изплатен“
 * — това си остава решение на оператора върху самия фиш, с дата, защото датата на изплащане носи
 * данъчни и осигурителни последици (виж {@code BgPaySlipService#pay}).
 *
 * <p><b>⚠️ И ЗАТОВА НЕ СЕ ВИКА {@code BankOrderValidationService#confirm}, КОЛКОТО И ДА Е ТО
 * ОЧЕВИДНАТА ВРАТА.</b> Освен файла, {@code confirm()} вика {@code generateMoves()} — тоест би
 * родило ВТОРА счетоводна статия за същите заплати, до онази, която фишът вече е направил при
 * изплащане ({@code BgPaySlipMoveService}). Оттук се вика само {@code
 * BankOrderService#generateFile}: файлът, нищо повече.
 *
 * <p><b>⚠️ И ВТОРАТА ВРАТА КЪМ СЪЩАТА ГРЕШКА Е ЗАКЛЮЧЕНА В ДАННИТЕ, А НЕ В КОМЕНТАР.</b>
 * Нареждането се ражда с {@code accountingTriggerSelect = ACCOUNTING_TRIGGER_NONE}, а {@code
 * BankOrderMoveServiceImpl#generateMoves} излиза веднага при тази стойност. Тоест дори човек
 * по-късно да натисне „Потвърди“ върху самото нареждане, втора статия НЕ се ражда. Коментар на това
 * място щеше да пази само от четящия го.
 *
 * <p><b>⚠️ СМЕТКАТА НА ЛИЦЕТО СЕ ЧЕТЕ ПРЕЗ ПАРТНЬОРА, А НЕ ПРЕЗ {@code Employee.bankDetails}.</b> И
 * това НЕ е избор на вкус, а две отделни причини. Първо: полето {@code Employee.bankDetails} се
 * декларира в {@code axelor-cash-management}, който зависи ОТ {@code axelor-human-resource} — тоест
 * стои НАДОЛУ по веригата от този модул и при компилация тук изобщо не съществува (в src-gen на
 * bg-localization {@code Employee} няма такъв getter). Второ, и по-важно: самият upstream не го
 * ползва за плащане на служител — {@code BankOrderCreateServiceHr} взима {@code
 * employee.getContactPartner()} и оставя {@code BankOrderLineService} да намери сметката по
 * подразбиране на партньора. Тук се върви по същия път.
 */
public class BgPayrollSepaService {

  protected BgPaySlipRepository paySlipRepository;
  protected BankOrderRepository bankOrderRepository;
  protected BankOrderCreateService bankOrderCreateService;
  protected BankOrderLineService bankOrderLineService;
  protected BankOrderService bankOrderService;
  protected BankDetailsRepository bankDetailsRepository;

  @Inject
  public BgPayrollSepaService(
      BgPaySlipRepository paySlipRepository,
      BankOrderRepository bankOrderRepository,
      BankOrderCreateService bankOrderCreateService,
      BankOrderLineService bankOrderLineService,
      BankOrderService bankOrderService,
      BankDetailsRepository bankDetailsRepository) {
    this.paySlipRepository = paySlipRepository;
    this.bankOrderRepository = bankOrderRepository;
    this.bankOrderCreateService = bankOrderCreateService;
    this.bankOrderLineService = bankOrderLineService;
    this.bankOrderService = bankOrderService;
    this.bankDetailsRepository = bankDetailsRepository;
  }

  /**
   * ЕДИН ПРОБЕГ.
   *
   * <p><b>⚠️ ВСИЧКО ИЛИ НИЩО, И ТОВА Е СЪЩЕСТВЕНО ИМЕННО ТУК.</b> Транзакцията се връща при всяко
   * изключение по пътя, тоест не остава нареждане без файл, нито фишове, вързани за нареждане,
   * което не е било генерирано. Половин пробег по плащане на заплати е точно състоянието, което
   * никой не забелязва, докато не свери банковото извлечение.
   */
  @Transactional(rollbackOn = {Exception.class})
  public BgPayrollSepaOutcome generate(Company company, Period period, LocalDate executionDate)
      throws AxelorException {

    refuse(
        company == null ? "Избери платец (дружество) — обходът и настройките са по него." : null);
    refuse(period == null ? "Избери период — един пробег покрива точно един месец." : null);

    // ═══ 1. НАСТРОЙКИТЕ НА ДРУЖЕСТВОТО ═══
    AccountConfig config = company.getAccountConfig();
    BankDetails senderBankDetails = config == null ? null : config.getBgPayrollBankDetails();
    PaymentMode paymentMode = config == null ? null : config.getBgPayrollPaymentMode();
    refuse(
        BgPayrollSepaRules.refuseMissingConfig(
            company.getName(), senderBankDetails != null, paymentMode != null));

    BankOrderFileFormat fileFormat = paymentMode.getBankOrderFileFormat();
    refuse(
        BgPayrollSepaRules.refuseFileFormat(
            paymentMode.getName(),
            fileFormat == null ? null : fileFormat.getOrderFileFormatSelect()));

    Currency currency = company.getCurrency();
    refuse(
        BgPayrollSepaRules.refuseCurrency(
            company.getName(), currency == null ? null : currency.getCodeISO()));

    // ═══ 2. ОБХВАТЪТ ═══
    List<BgPaySlip> slips = slipsToPay(company, period);
    int alreadyOrdered = countAlreadyOrdered(company, period);
    int notConfirmed = countNotConfirmed(company, period);

    Map<Long, BgPaySlip> slipsById = new LinkedHashMap<>();
    // ⚠️ ОДОБРЕНАТА СМЕТКА СЕ ПАЗИ, А НЕ СЕ ТЪРСИ ВТОРИ ПЪТ. Второ търсене значи, че редът във
    // файла може да тръгне по сметка, различна от онази, която правилото е проверило — а
    // проверката е единственото, което стои между човека и грешния IBAN.
    Map<Long, BankDetails> accountBySlip = new LinkedHashMap<>();
    List<BgPayrollSepaCandidate> candidates = new ArrayList<>();
    for (BgPaySlip slip : slips) {
      slipsById.put(slip.getId(), slip);
      candidates.add(candidateOf(slip, accountBySlip));
    }

    // ═══ 3. ОТКАЗИТЕ — ПОИМЕННО, ПРЕДИ ДА СЕ СТРОИ КАКВОТО И ДА Е ═══
    //
    // ⚠️ Отрицателното нето се пита ПЪРВО: то е дефект на ведомостта, а липсващата сметка е
    // попълнимо поле. Обърнат, редът би пратил човека да въвежда IBAN-и и чак после да открие, че
    // един фиш е сгрешен.
    refuse(BgPayrollSepaRules.refuseNegativeNet(candidates));
    refuse(BgPayrollSepaRules.refuseMissingIban(candidates));

    List<BgPayrollSepaCandidate> lines = BgPayrollSepaRules.payableLines(candidates);
    List<BgPayrollSepaCandidate> zeroNet = BgPayrollSepaRules.zeroNetLines(candidates);
    refuse(
        BgPayrollSepaRules.refuseNothingToPay(
            lines.size(), alreadyOrdered, notConfirmed, zeroNet.size()));

    // ═══ 4. НАРЕЖДАНЕТО ═══
    LocalDate date = executionDate == null ? LocalDate.now() : executionDate;
    String remittance = BgPayrollSepaRules.remittance(period.getFromDate());
    String label = remittance + " · " + company.getName();

    BankOrder bankOrder =
        bankOrderCreateService.createBankOrder(
            paymentMode,
            BankOrderRepository.PARTNER_TYPE_EMPLOYEE,
            date,
            company,
            senderBankDetails,
            currency,
            remittance,
            label,
            BankOrderRepository.TECHNICAL_ORIGIN_AUTOMATIC,
            BankOrderRepository.FUNCTIONAL_ORIGIN_MANUAL,
            PaymentModeRepository.ACCOUNTING_TRIGGER_NONE);

    for (BgPayrollSepaCandidate line : lines) {
      BgPaySlip slip = slipsById.get(line.getSlipId());
      // ⚠️ ОСНОВАНИЕТО Е САМО ЕТИКЕТЪТ, А РЕФЕРЕНЦИЯТА Е ПРАЗНА, И ТОВА Е ИЗБОР.
      // BankOrderFile00100103Service слепва двете в едно <Ustrd> през „ - “. Попълнени и двете,
      // лицето щеше да види в извлечението си вътрешния номер на фиша преди думата „заплата“ —
      // а точката се чете и от съдебен изпълнител при запор. Следата към фиша върви по другия
      // път: BankOrderLineOrigin точно отдолу и BgPaySlip.bgPaymentBankOrder.
      BankOrderLine bankOrderLine =
          bankOrderLineService.createBankOrderLine(
              fileFormat,
              null,
              partnerOf(slip),
              accountBySlip.get(line.getSlipId()),
              line.getNet(),
              currency,
              date,
              null,
              remittance,
              slip);
      nameOrigins(bankOrderLine, slip, period);
      bankOrder.addBankOrderLineListItem(bankOrderLine);
    }

    // ⚠️ ЕДИН ЗАПИС, И ТРИТЕ ЧИСЛА НА ЗАГЛАВНАТА ЧАСТ ИДВАТ ОТ НЕГО.
    // BankOrderManagementRepository#save вика generateSequence (пълни <MsgId> и <PmtInfId>),
    // setSequenceOnBankOrderLines (пълни <EndToEndId> на всеки ред) и updateTotalAmounts (пълни
    // <CtrlSum>) — проверено в самия hook. Повтаряне оттук би било второ място за сверяване на
    // същите числа.
    //
    // ⚠️ ЕДИНСТВЕНОТО, КОЕТО HOOK-ЪТ НЕ ПРАВИ, Е БРОЯТ НА РЕДОВЕТЕ, и то не е дребно: без него
    // <NbOfTxs> излиза 0 при пълен файл — тоест банката брои едно, а вижда друго, и отхвърля
    // цялото нареждане.
    bankOrderService.setNbOfLines(bankOrder);
    bankOrder = bankOrderRepository.save(bankOrder);

    // ═══ 5. ФАЙЛЪТ ═══
    File file;
    try {
      file = bankOrderService.generateFile(bankOrder);
    } catch (Exception e) {
      // ⚠️ ЛОВИ СЕ Exception, А НЕ ТРИТЕ ИМЕНУВАНИ ИЗКЛЮЧЕНИЯ, И ТОВА НЕ Е МЪРЗЕЛ.
      // BankOrderService#generateFile обявява JAXBException, но jakarta.xml.bind НЕ Е на
      // компилационния classpath на този модул: axelor-bank-payment я държи като своя зависимост,
      // не като `api`. Изброяването ѝ тук би поискало нова зависимост на ЦЕЛИЯ модул заради една
      // клауза catch.
      //
      // ⚠️ AxelorException СЕ ПРЕХВЪРЛЯ НЕПОКЪТНАТО: неговите съобщения вече назовават дружеството,
      // начина на плащане и липсващото поле. Увито, то би загубило точно това.
      if (e instanceof AxelorException) {
        throw (AxelorException) e;
      }
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          "%s",
          "Файлът не можа да бъде записан: "
              + e.getMessage()
              + ". Папката за експорт се чете от начина на плащане "
              + "(PaymentMode.bankOrderExportFolderPath) под директорията за експорт на данни.");
    }
    refuse(
        file == null
            ? "Генераторът не върна файл. Това се случва при нареждане без редове — виж горния "
                + "отказ, който трябваше да го е спрял по-рано."
            : null);

    // ⚠️ НЕСЪОТВЕТСТВИЕ СЪС СХЕМАТА Е ОТКАЗ, А НЕ ЗАБЕЛЕЖКА. Транзакцията се връща и след нея няма
    // нито нареждане, нито вързани фишове — тоест вторият пробег вижда същия обхват и поправката е
    // една. Показан като забележка, невалидният файл щеше да бъде качен от човек, който вече е
    // прочел „готово“, и да се върне от банката ден по-късно.
    //
    // ⚠️ А НЕПРОВЕРЕНОСТТА НЕ Е ОТКАЗ — тя е обявена. Виж BgSepaXsdValidator защо двете НЕ се
    // събират в един отговор: точно това сливане веднъж вече направи сверяването мълчаливо.
    BgSepaXsdValidator.Result verdict = BgSepaXsdValidator.validate(file);
    refuse(verdict.getProblems());
    String xsdNote = verdict.getNotCheckedReason();

    // ═══ 6. СЛЕДАТА ВЪРХУ ФИШОВЕТЕ ═══
    //
    // ⚠️ ПОСЛЕДНО, И ТО ВЪРХУ ВЕЧЕ ГЕНЕРИРАН ФАЙЛ. Вързан по-рано, фишът щеше да сочи нареждане,
    // което може и да не роди файл — тоест вторият пробег би го прескочил, а човекът да остане
    // неплатен. Точно обратното на това, което полето пази.
    for (BgPayrollSepaCandidate line : lines) {
      BgPaySlip slip = slipsById.get(line.getSlipId());
      slip.setBgPaymentBankOrder(bankOrder);
      paySlipRepository.save(slip);
    }

    return new BgPayrollSepaOutcome(
        bankOrder.getId(),
        bankOrder.getBankOrderSeq(),
        file.getName(),
        lines.size(),
        BgPayrollSepaRules.arithmeticTotal(lines),
        currency.getCodeISO(),
        bankOrder.getBankOrderDate(),
        remittance,
        senderBankDetails.getIban(),
        zeroNet,
        alreadyOrdered,
        notConfirmed,
        xsdNote);
  }

  /**
   * ИМЕ И ДАТА НА РЕДА-ПРОИЗХОД — иначе панелът „Произход“ на нареждането е празен.
   *
   * <p>⚠️ {@code BankOrderLineOriginService#computeRelatedToSelectName} познава пет модела
   * (фактура, вноска по погасителен план, вземане, падеж и разходен отчет) и връща {@code null} за
   * всичко останало. Тоест редът се записва вярно — клас и id, — но се показва без нито един четим
   * знак: от нареждането не може да се разбере ЧИЙ фиш е този ред, без да се отвори връзката.
   * Попълва се тук, вместо да се разширява чуждата услуга с шести случай.
   */
  protected void nameOrigins(BankOrderLine bankOrderLine, BgPaySlip slip, Period period) {
    if (bankOrderLine.getBankOrderLineOriginList() == null) {
      return;
    }
    for (BankOrderLineOrigin origin : bankOrderLine.getBankOrderLineOriginList()) {
      origin.setRelatedToSelectName(slip.getReference());
      origin.setRelatedToSelectDate(
          slip.getBgPaidDate() != null ? slip.getBgPaidDate() : period.getToDate());
    }
  }

  /**
   * ФИШОВЕТЕ, КОИТО ЧАКАТ ПЛАЩАНЕ.
   *
   * <p>⚠️ САМО „ПОТВЪРДЕН“. Черновата още се мени; „Изплатен“ вече е получил парите си по друг път
   * (каса, предходен файл); отказаният не се дължи. И трите се БРОЯТ отделно и се показват, вместо
   * да изчезнат — виж {@link #countNotConfirmed}.
   *
   * <p>⚠️ КОРИГИРАЩИТЕ И ЗАЛИЧАВАЩИТЕ НЕ ВЛИЗАТ, и това не е същият довод като при регистъра по
   * ЗДДФЛ. Ретро-фишът НЕ СЕ ИЗПЛАЩА ИЗОБЩО ({@code BgPaySlipResolver#refusePayRetro} го отказва
   * поименно): делтата му излиза с ТЕКУЩОТО нето, през перо 620 на текущия фиш. Ред за него тук
   * щеше да плати една и съща разлика два пъти.
   *
   * <p>⚠️ ФИШ БЕЗ ДОГОВОР (самоосигуряващо се лице) НЕ ВЛИЗА ПО УСТРОЙСТВО, защото филтърът минава
   * през {@code employmentContract.payCompany}. Това е верният изход, а не пропуск: СОЛ не получава
   * заплата от дружеството — той се осигурява сам и сам си тегли дохода.
   */
  protected List<BgPaySlip> slipsToPay(Company company, Period period) {
    return paySlipRepository
        .all()
        .filter(
            "self.employmentContract.payCompany = :company "
                + "AND self.period = :period "
                + "AND self.statusSelect = :confirmed "
                + "AND self.bgIsCorrection = FALSE AND self.bgIsDeletion = FALSE "
                + "AND self.bgPaymentBankOrder IS NULL")
        .bind("company", company)
        .bind("period", period)
        .bind("confirmed", BgPaySlipResolver.CONFIRMED)
        .order("reference")
        .order("id")
        .fetch();
  }

  /** Вече поискани с друг файл — пазачът срещу второ плащане. */
  protected int countAlreadyOrdered(Company company, Period period) {
    return (int)
        paySlipRepository
            .all()
            .filter(
                "self.employmentContract.payCompany = :company "
                    + "AND self.period = :period "
                    + "AND self.bgIsCorrection = FALSE AND self.bgIsDeletion = FALSE "
                    + "AND self.bgPaymentBankOrder IS NOT NULL")
            .bind("company", company)
            .bind("period", period)
            .count();
  }

  /** Непотвърдените — броят се, за да не изчезнат безшумно от доклада. */
  protected int countNotConfirmed(Company company, Period period) {
    return (int)
        paySlipRepository
            .all()
            .filter(
                "self.employmentContract.payCompany = :company "
                    + "AND self.period = :period "
                    + "AND self.statusSelect != :confirmed "
                    + "AND self.bgIsCorrection = FALSE AND self.bgIsDeletion = FALSE "
                    + "AND self.bgPaymentBankOrder IS NULL")
            .bind("company", company)
            .bind("period", period)
            .bind("confirmed", BgPaySlipResolver.CONFIRMED)
            .count();
  }

  /**
   * ЕДИН ФИШ → ЕДИН РЕД-КАНДИДАТ, с обявена причина, когато сметката не става.
   *
   * <p>⚠️ ЧЕТИРИТЕ ПРИЧИНИ СА РАЗДЕЛЕНИ, защото се поправят на четири различни екрана: лицето няма
   * партньор (картон на служителя), партньорът няма сметка по подразбиране (картон на партньора),
   * сметката е деактивирана (същият картон, друго поле), сметката е без IBAN (пак там). Слети в
   * „няма сметка“, отказът щеше да е верен и безполезен.
   */
  protected BgPayrollSepaCandidate candidateOf(
      BgPaySlip slip, Map<Long, BankDetails> approvedAccounts) {
    String person = personNameOf(slip);
    BigDecimal net = slip.getBgNetTotal();
    Partner partner = partnerOf(slip);
    if (partner == null) {
      return BgPayrollSepaCandidate.withoutAccount(
          slip.getId(),
          slip.getReference(),
          person,
          "лицето няма партньор (Employee.contactPartner). Платежната сметка виси на партньора, "
              + "тоест без него няма къде да се въведе IBAN.",
          net);
    }
    BankDetails bankDetails = bankDetailsRepository.findDefaultByPartner(partner);
    if (bankDetails == null) {
      return BgPayrollSepaCandidate.withoutAccount(
          slip.getId(),
          slip.getReference(),
          person,
          "партньорът „"
              + partner.getFullName()
              + "“ няма банкова сметка по подразбиране. Отбележи една от сметките му като "
              + "„По подразбиране“.",
          net);
    }
    if (!Boolean.TRUE.equals(bankDetails.getActive())) {
      return BgPayrollSepaCandidate.withoutAccount(
          slip.getId(),
          slip.getReference(),
          person,
          "сметката по подразбиране на „"
              + partner.getFullName()
              + "“ е ДЕАКТИВИРАНА. Активирай я или посочи друга като по подразбиране.",
          net);
    }
    String iban = bankDetails.getIban();
    if (iban == null || iban.trim().isEmpty()) {
      return BgPayrollSepaCandidate.withoutAccount(
          slip.getId(),
          slip.getReference(),
          person,
          "сметката по подразбиране на „" + partner.getFullName() + "“ е без IBAN.",
          net);
    }
    approvedAccounts.put(slip.getId(), bankDetails);
    return BgPayrollSepaCandidate.payable(
        slip.getId(), slip.getReference(), person, iban.trim(), net);
  }

  protected Partner partnerOf(BgPaySlip slip) {
    Employee employee = employeeOf(slip);
    return employee == null ? null : employee.getContactPartner();
  }

  protected String personNameOf(BgPaySlip slip) {
    Employee employee = employeeOf(slip);
    return employee == null ? null : employee.getName();
  }

  protected Employee employeeOf(BgPaySlip slip) {
    EmploymentContract contract = slip == null ? null : slip.getEmploymentContract();
    return contract == null ? null : contract.getEmployee();
  }

  /** Същият идиом като в {@code BgPaySlipService}: отказът е изречение, не код. */
  protected static void refuse(String message) throws AxelorException {
    if (message != null && !message.isEmpty()) {
      throw new AxelorException(TraceBackRepository.CATEGORY_INCONSISTENCY, "%s", message);
    }
  }
}

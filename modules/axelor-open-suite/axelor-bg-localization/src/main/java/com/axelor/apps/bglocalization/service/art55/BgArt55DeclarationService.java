package com.axelor.apps.bglocalization.service.art55;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgArt55Declaration;
import com.axelor.apps.bglocalization.db.BgDeclarant;
import com.axelor.apps.bglocalization.db.BgIndividualIncomePayment;
import com.axelor.apps.bglocalization.db.repo.BgArt55DeclarationRepository;
import com.axelor.apps.bglocalization.db.repo.BgIndividualIncomePaymentRepository;
import com.axelor.apps.bglocalization.service.BgDeclarantResolver;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationStatus;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationSubmissionService;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationSubmissionSpec;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * СТРОИТЕЛЯТ НА ДЕКЛАРАЦИЯТА ПО ЧЛ. 55 (ОБРАЗЕЦ 4001) — от регистъра на изплатените доходи до трите
 * реда на Част ІV, колона 3.
 *
 * <p><b>ЧЕТЕ КАКВОТО ВЕЧЕ Е В РЕГИСТЪРА.</b> Автоматичните захранвания — услугите 307x от
 * граждански договор, фактурите и наемите — са отделна фаза и НЕ се симулират тук.
 *
 * <p><b>⚠️ РАЗДЕЛЕНИЕТО СРЕЩУ {@link BgArt55Aggregator} НЕ Е КОЗМЕТИЧНО.</b> Там живее
 * разпределянето по редове — правилото, което образецът и чл. 65 ал. 13 диктуват — и то е проверимо
 * без Postgres. Тук живее всичко, което иска база: прочитът на регистъра, заглавната част и
 * закачането към транспорта.
 *
 * <p><b>⚠️ ОБР. 4001 СЕ ПОДАВА ПРЕЗ ПОРТАЛА, НЕ С ФАЙЛ — затова тук НЯМА файлова верига.</b> Чл. 56
 * ал. 2 иска подаване само по електронен път, но НАП не е публикувала машинна структура на записа
 * за този образец: подателят попълва формуляра на портала и получава входящ номер. Екранът изважда
 * числата, {@link BgArt55DeclarationValidator} казва какво не е наред ПРЕДИ преписването, а {@link
 * #attach} записва подаването с неговия входящ номер.
 *
 * <p>TODO: обр. 4001 = портално подаване; export да се добави при публикувана от НАП файлова
 * структура. Огледалната верига за справките по чл. 73 стои в {@code service/art73} и е готова да
 * бъде преизползвана.
 *
 * <p><b>⚠️ СРОКЪТ НЕ СПИРА СТРОЕНЕТО, И ТОВА Е НАРОЧНО.</b> Образецът, ДОСЛОВНО: „Ако вече сте
 * подали декларация ... но искате да направите корекции в нея, е необходимо да подадете нова
 * данъчна декларация, в която се съдържат всички данни за периода“. Отказ да се строи след срока би
 * направил невъзможно точно това.
 */
@Singleton
public class BgArt55DeclarationService {

  protected final BgArt55DeclarationRepository declarationRepo;
  protected final BgIndividualIncomePaymentRepository paymentRepo;
  protected final BgDeclarationSubmissionService submissionService;

  @Inject
  public BgArt55DeclarationService(
      BgArt55DeclarationRepository declarationRepo,
      BgIndividualIncomePaymentRepository paymentRepo,
      BgDeclarationSubmissionService submissionService) {
    this.declarationRepo = declarationRepo;
    this.paymentRepo = paymentRepo;
    this.submissionService = submissionService;
  }

  /**
   * СТРОИ ДЕКЛАРАЦИЯТА за платеца, данъчната година и тримесечието.
   *
   * <p>⚠️ Плащанията се четат по {@code paymentDate} — датата на ПРИДОБИВАНЕ на дохода по чл. 11 —
   * а не по полето {@code period} на реда. Периодът е свободно попълван от формата на регистъра и
   * може да не съвпада с датата; чл. 65 ал. 12 връзва задължението с ПРИДОБИВАНЕТО.
   */
  @Transactional(rollbackOn = Exception.class)
  public BgArt55Declaration build(Company company, int taxYear, int quarter) {
    BgArt55Declaration declaration = existing(company, taxYear, quarter);
    if (declaration == null) {
      declaration = new BgArt55Declaration();
      declaration.setCompany(company);
      declaration.setTaxYear(taxYear);
      declaration.setQuarter(quarter);
    }
    declaration.setStatusSelect(BgDeclarationStatus.GENERATED);
    declaration.setDueDate(dueDate(taxYear, quarter));
    freezePayer(declaration, company);
    freezeRepresentative(declaration, company);

    BgArt55Aggregator.Result result =
        BgArt55Aggregator.aggregate(paymentsOf(company, taxYear, quarter), taxYear, quarter);

    // ═══ ЧАСТ ІV, КОЛОНА 3 ═══
    declaration.setRow1AdvanceTax(result.getRow1());
    declaration.setRow2DividendTax(result.getRow2());
    declaration.setRow3OtherFinalTax(result.getRow3());

    declaration.setBgBoundaryReport(BgArt55Boundaries.report(facts(result, quarter, taxYear)));
    return declarationRepo.save(declaration);
  }

  /**
   * СРОКЪТ ЗА ПОДАВАНЕ — краят на месеца, СЛЕДВАЩ тримесечието.
   *
   * <p>Чл. 56 ал. 1: декларацията се подава „в сроковете за внасяне на дължимите данъци“; чл. 65
   * ал. 12 ги определя като „до края на месеца, следващ тримесечието на придобиване на дохода“.
   * Указанията към образеца повтарят: „до края на месеца, следващ тримесечието“.
   *
   * <p>⚠️ За ЧЕТВЪРТОТО тримесечие това е 31 ЯНУАРИ на СЛЕДВАЩАТА година — и точно тази дата
   * повтаря и чл. 65 ал. 13 за случая, в който лицето е поискало писмено удържане. Двете съвпадат,
   * тоест срокът е един независимо дали ред 1 е потиснат.
   *
   * <p>⚠️ Изчислен от края на тримесечието, а не от таблица с четири дати: таблицата би мълчала при
   * подадено тримесечие извън 1–4.
   */
  public static LocalDate dueDate(int taxYear, int quarter) {
    LocalDate monthAfterQuarter = LocalDate.of(taxYear, 1, 1).plusMonths(3L * quarter);
    return monthAfterQuarter.withDayOfMonth(monthAfterQuarter.lengthOfMonth());
  }

  /** Пренася измереното от агрегатора и добавя това, което иска база. */
  protected BgArt55Boundaries.Facts facts(
      BgArt55Aggregator.Result result, int quarter, int taxYear) {
    BgArt55Boundaries.Facts facts = new BgArt55Boundaries.Facts();
    facts.quarter = quarter;
    facts.periodRows = result.getPeriodRows();
    facts.row1Rows = result.getRow1Rows();
    facts.row2Rows = result.getRow2Rows();
    facts.row3Rows = result.getRow3Rows();
    facts.unusable = result.getUnusableCount();
    facts.employment = result.getEmploymentCount();
    facts.selfInsured = result.getSelfInsuredCount();
    facts.notWithheld = result.getNotWithheldCount();
    facts.q4SuppressedRows = result.getQ4SuppressedRows();
    facts.q4SuppressedAmount = result.getQ4SuppressedAmount();
    facts.dividendRows = result.getRow2Rows();
    facts.recipientPaidFinal = result.getRecipientPaidFinalCount();
    facts.withoutPayer = countWithoutPayer(taxYear, quarter);
    return facts;
  }

  /**
   * ОТВАРЯ ДЕКЛАРАЦИЯТА ЗА КОРЕКЦИЯ — забравя предишното подаване и я връща в чернова.
   *
   * <p>⚠️ Точно това позволява корекцията, която образецът иска ДОСЛОВНО: „необходимо е да подадете
   * НОВА данъчна декларация, в която се съдържат ВСИЧКИ данни за периода, а не само тези, които
   * променяте“ — тоест същият запис, построен наново.
   *
   * <p><b>⚠️ ЗАБРАВЯ ВХОДЯЩИЯ НОМЕР, НО НЕ ТРИЕ ПОДАВАНЕТО.</b> {@code NapSubmission} остава: то е
   * следата на това, което Е БИЛО подадено, и нищо по-късно не бива да пренаписва миналото. Тук
   * падат само полетата на ЕКРАНА — те описваха СТАРОТО подаване и оставени, биха твърдели, че НАП
   * е приела съдържание, което вече не съществува. Новото отбелязване ще посочи старото подаване
   * като {@code previousSubmission}.
   */
  @Transactional(rollbackOn = Exception.class)
  public BgArt55Declaration invalidateSubmission(BgArt55Declaration declarationCtx) {
    BgArt55Declaration declaration = declarationRepo.find(declarationCtx.getId());
    declaration.setNapIncomingNumber(null);
    declaration.setNapResponseDate(null);
    declaration.setNapResponseNote(null);
    declaration.setStatusSelect(BgDeclarationStatus.DRAFT);
    return declarationRepo.save(declaration);
  }

  /**
   * ОТБЕЛЯЗВА ДЕКЛАРАЦИЯТА КАТО ПОДАДЕНА през портала и я записва в одитната следа.
   *
   * <p><b>⚠️ НЕ ИСКА НАШ ФАЙЛ — но иска ВХОДЯЩ НОМЕР И ДАТА.</b> Виж {@code
   * BgArt73Para1ReportService#attach} за довода. ⚠️ Чл. 56 ал. 2 иска подаване САМО ПО ЕЛЕКТРОНЕН
   * ПЪТ, тоест портал или услуга — хартия няма; порталът връща входящ номер и точно той е следата.
   *
   * <p>⚠️ {@code NapSubmissionFile} НЕ се създава: Nyxa не произвежда файл за този образец.
   * Подаването носи входящия номер и нищо друго — точно колкото е станало.
   *
   * <p><b>⚠️ БЕЗ {@code serviceDocumentType} И БЕЗ {@code fileDocumentType}</b> — enum-ите са
   * фиксирани от договора на Declarations API и не познават образец 4001. Каналът е {@code
   * manual_portal} и по устройство НЕ извършва транспорт.
   *
   * <p>⚠️ {@code referenceDate} е СРОКЪТ, а не датата на подаване.
   */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission attach(
      BgArt55Declaration declaration, String incomingNumber, LocalDate incomingDate)
      throws IOException {

    BgDeclarationStatus.requireIncomingNumber(incomingNumber, incomingDate);

    BgDeclarationSubmissionSpec spec =
        new BgDeclarationSubmissionSpec()
            .setCompany(declaration.getCompany())
            .setName(
                "Декларация чл. 55 (обр. 4001) / "
                    + declaration.getTaxYear()
                    + " тримесечие "
                    + declaration.getQuarter())
            .setReferenceDate(
                declaration.getDueDate() == null
                    ? dueDate(declaration.getTaxYear(), declaration.getQuarter())
                    : declaration.getDueDate())
            .setTaxpayerPin(declaration.getPayerIdentifier())
            .setRelatedModel(BgArt55Declaration.class.getName())
            .setRelatedRecordId(declaration.getId())
            .setPreviousSubmission(declaration.getNapSubmission())
            .setIncomingNumber(incomingNumber.trim())
            .setIncomingDate(incomingDate);

    NapSubmission saved = submissionService.attach(spec);
    declaration.setNapSubmission(saved);
    declaration.setNapIncomingNumber(incomingNumber.trim());
    declaration.setNapResponseDate(incomingDate);
    declaration.setStatusSelect(BgDeclarationStatus.SUBMITTED);
    declarationRepo.save(declaration);
    return saved;
  }

  /**
   * РЪЧЕН ИЗХОД: НАП е ПРИЕЛА или ОТХВЪРЛИЛА декларацията.
   *
   * <p>⚠️ Иска входящ номер и дата — виж {@code BgArt73Para1ReportService#recordOutcome}.
   */
  @Transactional(rollbackOn = Exception.class)
  public BgArt55Declaration recordOutcome(
      BgArt55Declaration declarationCtx,
      boolean accepted,
      String incomingNumber,
      LocalDate responseDate,
      String note) {

    BgArt55Declaration declaration = declarationRepo.find(declarationCtx.getId());
    BgDeclarationStatus.requireManualOutcome(
        declaration.getNapSubmission() != null, incomingNumber, responseDate);
    declaration.setNapIncomingNumber(incomingNumber.trim());
    declaration.setNapResponseDate(responseDate);
    declaration.setNapResponseNote(note);
    declaration.setStatusSelect(
        accepted ? BgDeclarationStatus.ACCEPTED : BgDeclarationStatus.REJECTED);

    if (accepted) {
      submissionService.markAccepted(
          declaration.getNapSubmission(), incomingNumber.trim(), responseDate, note);
    } else {
      submissionService.markRejected(
          declaration.getNapSubmission(), incomingNumber.trim(), responseDate, note);
    }
    return declarationRepo.save(declaration);
  }

  /**
   * ЧАСТ ІІ, Т. 1 И Т. 3 — замразени на декларацията.
   *
   * <p>⚠️ Не се четат от {@code Company} при всеки прочит: подадена декларация трябва да показва
   * данните, С КОИТО Е БИЛА подадена.
   *
   * <p>⚠️ И се пишат само когато са ПРАЗНИ — повторното строене за корекция по образеца не бива да
   * изтрива ръчна поправка на наименованието.
   */
  protected void freezePayer(BgArt55Declaration declaration, Company company) {
    Partner partner = company == null ? null : company.getPartner();
    if (partner == null) {
      return;
    }
    if (declaration.getPayerIdentifier() == null) {
      declaration.setPayerIdentifier(trim(partner.getRegistrationCode()));
    }
    if (declaration.getPayerName() == null) {
      declaration.setPayerName(trim(partner.getName()));
    }
  }

  /**
   * ЧАСТ ІІ, Т. 7.1 И Т. 7.2 — ПРЕДСТАВЛЯВАЩИЯТ, предложен от декларатора на дружеството.
   *
   * <p>Същият запис {@code BgDeclarant}, който вече подписва Д1, Д6, Приложение № 9 и ДДС-стека.
   * Дотук обр. 4001 беше единствената справка, която искаше името да се НАБЕРЕ наново при всяко
   * тримесечие — а то стои в базата от години.
   *
   * <p>⚠️ ПРЕДЛОЖЕНИЕ, А НЕ ЗАКЛЮЧВАНЕ: полетата остават редактируеми и се пишат само когато са
   * празни, по същия довод като при {@link #freezePayer} — повторното строене за корекция не бива
   * да изтрива ръчно вписан пълномощник.
   */
  protected void freezeRepresentative(BgArt55Declaration declaration, Company company) {
    if (!representativeApplies(declaration.getPayerCodeType())) {
      return;
    }
    BgDeclarant declarant =
        chooseRepresentative(company == null ? null : company.getBgDeclarantList());
    if (declarant == null) {
      return;
    }
    if (declaration.getRepresentativeName() == null) {
      declaration.setRepresentativeName(trim(declarant.getDeclarantNames()));
    }
    if (declaration.getRepresentativeIdentifier() == null) {
      declaration.setRepresentativeIdentifier(trim(declarant.getDeclarantEgn()));
    }
  }

  /**
   * ДОПУСКА ЛИ ОБРАЗЕЦЪТ Т. 7 ЗА ТОЗИ ПОДАТЕЛ — чете се от кода по т. 2.
   *
   * <p>Образецът, ДОСЛОВНО: т. 7.1 и 7.2 „се попълват само когато декларацията се подава от
   * юридическо лице“. Юридическото лице е носителят на ЕИК, тоест код „1“.
   *
   * <p><b>⚠️ БЕЗ ТАЗИ ВРАТА ПРЕДЛОЖЕНИЕТО ПРОИЗВЕЖДА ГРЕШКА.</b> {@code Company} в базата може да
   * носи и ЕГН на самоосигуряващо се лице (код „2“). Попълнен там, представляващият пали {@code
   * ART55_REPRESENTATIVE_NOT_ALLOWED} — тоест удобството би създало блокиращо нарушение на празно
   * място. Вратата е същата, по която съди и {@link BgArt55DeclarationValidator}.
   */
  public static boolean representativeApplies(String payerCodeType) {
    return payerCodeType != null && "1".equals(payerCodeType.trim());
  }

  /**
   * КОЙ ДЕКЛАРАТОР СЕ ПРЕДЛАГА — вдигнатият „По подразбиране“, а при липса на флаг единственият.
   *
   * <p>Редът е този: {@link BgDeclarantResolver#defaults} връща вдигнатите, а {@code
   * BgCompanyManagementRepository} вече отказва запис на дружество с ДВА вдигнати флага, тоест „по
   * подразбиране“ е еднозначен, когато съществува.
   *
   * <p><b>⚠️ ПРИ НЕЯСНОТА СЕ ПРЕДЛАГА НИЩО, А НЕ ПЪРВИЯТ ПОРЕД.</b> Двама декларатора без вдигнат
   * флаг са въпрос, а не списък за подреждане. Образецът, ДОСЛОВНО: „За деклариране на неверни
   * данни се носи наказателна отговорност по Наказателния кодекс“ — под това се подписва човек с
   * име, и грешното име е по-лошо от празното поле, което валидаторът и без това ще поиска ({@code
   * ART55_REPRESENTATIVE_MISSING}).
   */
  public static BgDeclarant chooseRepresentative(List<BgDeclarant> declarants) {
    List<BgDeclarant> raised = BgDeclarantResolver.defaults(declarants);
    if (raised.size() == 1) {
      return raised.get(0);
    }
    if (!raised.isEmpty() || declarants == null) {
      return null;
    }
    BgDeclarant only = null;
    for (BgDeclarant declarant : declarants) {
      if (declarant == null) {
        continue;
      }
      if (only != null) {
        return null;
      }
      only = declarant;
    }
    return only;
  }

  /** Редовете на регистъра за платеца и тримесечието, в реда на въвеждането им. */
  protected List<BgIndividualIncomePayment> paymentsOf(Company company, int taxYear, int quarter) {
    return paymentRepo
        .all()
        .filter("self.company = :company AND self.paymentDate >= :from AND self.paymentDate <= :to")
        .bind("company", company)
        .bind("from", firstDayOf(taxYear, quarter))
        .bind("to", lastDayOf(taxYear, quarter))
        .order("paymentDate")
        .order("id")
        .fetch();
  }

  /**
   * РЕДОВЕТЕ ЗА ПЕРИОДА БЕЗ ПОСОЧЕН ПЛАТЕЦ.
   *
   * <p><b>⚠️ БРОЯТ СЕ, А НЕ СЕ ВКЛЮЧВАТ.</b> Полето {@code company} на регистъра не е задължително
   * заради вече въведените редове. Включени по подразбиране, те биха работили в инсталация с едно
   * дружество и биха декларирали ЧУЖД данък от името на подателя в инсталация с повече.
   */
  protected int countWithoutPayer(int taxYear, int quarter) {
    return (int)
        paymentRepo
            .all()
            .filter(
                "self.company IS NULL AND self.paymentDate >= :from AND self.paymentDate <= :to")
            .bind("from", firstDayOf(taxYear, quarter))
            .bind("to", lastDayOf(taxYear, quarter))
            .count();
  }

  /** Първият ден на тримесечието — 1 януари, 1 април, 1 юли или 1 октомври. */
  public static LocalDate firstDayOf(int taxYear, int quarter) {
    return LocalDate.of(taxYear, 1, 1).plusMonths(3L * (quarter - 1));
  }

  /** Последният ден на тримесечието — 31 март, 30 юни, 30 септември или 31 декември. */
  public static LocalDate lastDayOf(int taxYear, int quarter) {
    return firstDayOf(taxYear, quarter).plusMonths(3).minusDays(1);
  }

  protected BgArt55Declaration existing(Company company, int taxYear, int quarter) {
    return declarationRepo
        .all()
        .filter("self.company = :company AND self.taxYear = :year AND self.quarter = :quarter")
        .bind("company", company)
        .bind("year", taxYear)
        .bind("quarter", quarter)
        .fetchOne();
  }

  private static String trim(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }
}

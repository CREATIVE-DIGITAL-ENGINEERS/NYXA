package com.axelor.apps.bglocalization.service.art73;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgArt73Para6Annual;
import com.axelor.apps.bglocalization.db.BgArt73Para6Record;
import com.axelor.apps.bglocalization.db.BgArt73Para6Report;
import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.repo.BgArt73Para6ReportRepository;
import com.axelor.apps.bglocalization.db.repo.BgIncomeTypeRepository;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipRepository;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
import com.axelor.apps.bglocalization.service.art49.BgArt49MainEmployer;
import com.axelor.apps.bglocalization.service.art49.BgArt49Recalculation;
import com.axelor.apps.bglocalization.service.art49.BgArt49ReliefEntitlement;
import com.axelor.apps.bglocalization.service.art49.BgArt49Reliefs;
import com.axelor.apps.bglocalization.service.art49.BgArt49Result;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FileContent;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationPlannedFile;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationStatus;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationSubmissionService;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationSubmissionSpec;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.hr.db.repo.EmploymentContractRepository;
import com.axelor.apps.napsubmissions.common.artifact.Institution;
import com.axelor.apps.napsubmissions.common.artifact.NapArtifactService;
import com.axelor.apps.napsubmissions.common.artifact.RegulatoryFile;
import com.axelor.apps.napsubmissions.common.audit.NapAuditService;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactRepository;
import com.axelor.apps.napsubmissions.db.repo.NapAuditEventRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * СТРОИТЕЛЯТ НА СПРАВКАТА ПО ЧЛ. 73 АЛ. 6 — от фишовете на годината до записите на Част ІІ.
 *
 * <p><b>ЯДРОТО ПЛЮС ГОДИШНОТО ПРЕИЗЧИСЛЯВАНЕ.</b> Фаза 1 изведе трите числа, които фишът отговаря —
 * колони 3, 4 и 7 — плюс идентификацията по т. 1–6. Тази фаза добавя ВТОРАТА половина на Част ІІ:
 * колона 6 на редове 8.1, 8.2 и 8.3 и колони 17–22, тоест годишната данъчна основа по чл. 25 ал. 1
 * и 3, дължимият годишен данък по чл. 49 и изравняването до 31 януари. Аритметиката живее в {@link
 * BgArt49Recalculation}, правомощието — в {@link BgArt49MainEmployer}, а тук стои само прочитът от
 * базата и записването.
 *
 * <p><b>⚠️ Т. 7 ВЕЧЕ СЕ ИЗВЕЖДА, И ТОВА ЗАТВАРЯ ВТОРАТА ГРАНИЦА НА ФАЗА 1.</b> „Работодател по
 * основно трудово правоотношение към 31 декември“ се чете от трудовия договор ({@code
 * EmploymentContract.bgIsPrimaryContract} + {@code payCompany} + действие на датата), а не от фиша
 * — фишът наистина не го носи, но договорът го носи от самото начало. От този признак зависи кои
 * колони изобщо се попълват, затова той се пише на ВСЕКИ ред на лицето, а не само там, където се
 * ползва.
 *
 * <p><b>⚠️ ГРАЖДАНСКИТЕ ДОГОВОРИ НЕ СЕ ДЕКЛАРИРАТ ОТ ТУК, И ТОВА Е ЕДИНСТВЕНОТО СТЕСНЕНИЕ НА
 * ОБХВАТА.</b> Фиш с вид осигурен 14 се пропуска от {@link BgArt73Para6Aggregator#byCode} — доходът
 * по него е по чл. 29, а справката по ал. 6 е за трудовите правоотношения по § 1 т. 26. Мястото му
 * е регистърът {@code BgIndividualIncomePayment} и оттам справката по чл. 73 ал. 1; иначе той би
 * стигнал и до ДВАТА образеца. Наетите, ДУК-ът (код 10, § 1 т. 26 б. „з“) и морските лица (код 92 →
 * ред 103) остават непроменени — довода код по код виж в граница 4 на {@link
 * BgArt73Para6Aggregator}.
 *
 * <p><b>⚠️ ВЕЧЕ ГЕНЕРИРАНА СПРАВКА НЕ СЕ МИГРИРА, А СЕ ПРЕГЕНЕРИРА — И МИГРАЦИЯ НЕ Е ПИСАНА
 * НАРОЧНО.</b> {@link #build} е upsert по (дружество, година), а {@link #replace} чисти списъка на
 * място при {@code orphanRemoval}: второто пускане маха редовете по граждански договор само̀ и
 * запазва ръчно въведеното в колони 8–16 през {@link #carryOver}. Тоест поправката на заварена
 * справка е ЕДНО натискане на „Генерирай“, а не скрипт.
 *
 * <p><b>⚠️ ПОДАДЕНА СПРАВКА НЕ СЕ СТРОИ НАНОВО.</b> {@link #build}, {@link #generateFile} и {@link
 * #invalidateFile} отказват, докато справката е отбелязана като подадена: един документ — едно
 * подаване. Нова версия минава през {@link #withdraw} (отметката се оттегля с причина, подаването
 * остава в историята като оттеглено). ⚠️ Корекцията по чл. 73 ал. 8 (до 30 септември) на ИСТИНСКИ
 * подадена справка не е оттегляне — как се моделира, е отворен въпрос.
 *
 * <p><b>⚠️ СРОКЪТ НЕ СЕ ПАЗИ ОТ ТОЗИ КЛАС И ТОВА Е НАРОЧНО.</b> Справката се подава до 28 февруари,
 * а корекция — до 30 септември. Отказ да се СТРОИ след срока би направил невъзможно точно това,
 * което нормата позволява: коригиране на вече подадени данни. Срокът е въпрос на подаването, не на
 * извеждането.
 */
@Singleton
public class BgArt73Para6ReportService {

  /** Нашият тип идентификатор за ЕГН — същата стойност, която чете {@code BgD1RecordBuilder}. */
  private static final String OUR_ID_TYPE_EGN = "1";

  /** Т. 5 на образеца: „0“ за лице с ЕГН. */
  public static final String ID_TYPE_EGN = "0";

  /** Т. 5 на образеца: „1“ за лице с ЛН, ЛНЧ или Сл. № от регистъра на НАП. */
  public static final String ID_TYPE_OTHER = "1";

  /** Типът на съдържанието за {@code MetaFile} — заповедта: „Тип на файла – XML“. */
  private static final String MIME_XML = "application/xml";

  protected final BgArt73Para6ReportRepository reportRepo;
  protected final BgPaySlipRepository paySlipRepo;
  protected final BgIncomeTypeRepository incomeTypeRepo;
  protected final BgArt73Para6FileService fileService;
  protected final BgDeclarationSubmissionService submissionService;
  protected final NapArtifactService artifactService;
  protected final NapSubmissionRepository submissionRepo;
  protected final NapAuditService auditService;

  @Inject
  public BgArt73Para6ReportService(
      BgArt73Para6ReportRepository reportRepo,
      BgPaySlipRepository paySlipRepo,
      BgIncomeTypeRepository incomeTypeRepo,
      BgArt73Para6FileService fileService,
      BgDeclarationSubmissionService submissionService,
      NapArtifactService artifactService,
      NapSubmissionRepository submissionRepo,
      NapAuditService auditService) {
    this.reportRepo = reportRepo;
    this.paySlipRepo = paySlipRepo;
    this.incomeTypeRepo = incomeTypeRepo;
    this.fileService = fileService;
    this.submissionService = submissionService;
    this.artifactService = artifactService;
    this.submissionRepo = submissionRepo;
    this.auditService = auditService;
  }

  /**
   * СТРОИ СПРАВКАТА за дружеството и данъчната година.
   *
   * <p>⚠️ Фишовете се четат по ГОДИНАТА НА ПЕРИОДА, а не по дата на изплащане: и трите прочита в
   * агрегатора минават през {@code isOwnPeriod}, тоест сумите на фиша са тези за неговия месец.
   * Четвъртата граница в {@link BgArt73Para6Aggregator} описва какво следва от това през граница на
   * година.
   */
  @Transactional(rollbackOn = Exception.class)
  public BgArt73Para6Report build(Company company, int taxYear) {
    BgArt73Para6Report report = existing(company, taxYear);
    refuseIfFiled(report);
    if (report == null) {
      report = new BgArt73Para6Report();
      report.setCompany(company);
      report.setTaxYear(taxYear);
    }
    report.setStatusSelect("GENERATED");
    Partner partner = company == null ? null : company.getPartner();
    if (partner != null) {
      report.setEmployerIdentifier(trim(partner.getRegistrationCode()));
      report.setEmployerName(trim(partner.getName()));
    }

    List<BgArt73Para6Record> records = new ArrayList<>();
    List<BgArt73Para6Annual> annuals = new ArrayList<>();
    BgPaySlipService paySlipService = Beans.get(BgPaySlipService.class);

    // ⚠️ СТАВКАТА СЕ ЧЕТЕ ВЕДНЪЖ ЗА ЦЯЛАТА СПРАВКА, А НЕ НА ЛИЦЕ. Тя е една за годината (чл. 49 ал.
    // 1 гледа към 31 декември) и прочит на лице би дал N еднакви заявки. Липсва ли — виж по-долу.
    BigDecimal taxRate = paySlipService.annualIncomeTaxRate(taxYear);

    BgArt73Para6Boundaries.Facts facts = new BgArt73Para6Boundaries.Facts();
    facts.taxRateMissing = taxRate == null;

    // ⚠️ СУМИТЕ НА ОБЛЕКЧЕНИЯТА СЪЩО СЕ ЧЕТАТ ВЕДНЪЖ. Липсващите се назовават в границите поименно
    // — незаредена сума и „лицето няма право“ дават еднакъв данък и различна поправка.
    BgArt49ReliefEntitlement.Amounts amounts = reliefAmounts(paySlipService, taxYear);
    facts.missingReliefAmounts = missingReliefAmounts(amounts);

    // ⚠️ РЪЧНИТЕ ВХОДОВЕ НА ПРЕДИШНОТО ГЕНЕРИРАНЕ СЕ ПАЗЯТ. Документите по чл. 49 ал. 4 и колони
    // 9–14 се въвеждат от човек по документи; затрити при всяко генериране, те щяха да правят
    // повторното генериране опасно действие, а справката се генерира по няколко пъти преди
    // подаване.
    Map<Long, BgArt73Para6Annual> previousAnnuals = byEmployee(report.getAnnualList());
    Map<String, BgArt73Para6Record> previousRecords = byEmployeeAndCode(report.getRecordList());

    for (Map.Entry<Employee, List<BgPaySlip>> entry :
        slipsByEmployee(company, taxYear).entrySet()) {
      Employee employee = entry.getKey();
      Map<String, BgArt73Para6Line> byCode =
          BgArt73Para6Aggregator.byCode(entry.getValue(), paySlipService::isSeafarerSlip);

      boolean mainEmployer =
          BgArt49MainEmployer.recalculates(contractsOf(employee), company, taxYear);

      List<BgArt73Para6Record> mine = new ArrayList<>();
      for (BgArt73Para6Line line : byCode.values()) {
        BgArt73Para6Record record = record(report, employee, line, taxYear);
        if (record != null) {
          // Т. 7 — изведена от договора, не от фиша. Виж описанието на класа.
          record.setIsMainEmployer(mainEmployer);
          carryOver(previousRecords.get(recordKey(employee, line.getCode())), record);
          mine.add(record);
        }
      }
      records.addAll(mine);

      // ⚠️ БРОИ СЕ ЛИЦЕ С РЕДОВЕ, А НЕ ЛИЦЕ С ФИШОВЕ — точно каквото {@code Facts#people} обявява,
      // че значи („лица с редове в Част ІІ“). Двете не съвпадат заради стеснението по граница 4 на
      // BgArt73Para6Aggregator: лице САМО с фишове по граждански договор има фишове и няма нито
      // един ред. Броено при влизането, то би направило текста на границите неверен в най-четеното
      // си число — „преизчислени са 3 от 5 лица с редове в справката“ при четири лица с редове.
      if (!mine.isEmpty()) {
        facts.people++;
        if (mainEmployer) {
          facts.mainEmployerPeople++;
        }
      }

      // ⚠️ РЕД В annualList СЕ СЪЗДАВА САМО ЗА ЛИЦЕ С ОСНОВНО ТПО КЪМ 31 ДЕКЕМВРИ. За останалите
      // липсата на ред Е отговорът на чл. 49 ал. 1 — виж описанието на BgArt73Para6Annual.
      if (mainEmployer && !mine.isEmpty() && taxRate != null) {
        BgArt73Para6Annual previous =
            employee == null ? null : previousAnnuals.get(employee.getId());
        BgArt49Reliefs requested = requestedReliefs(employee, previous, amounts, taxYear);
        BgArt73Para6Annual current = annual(report, employee, mine, taxRate, requested);
        if (!current.getReliefDocumentsProvided()) {
          facts.withoutArt49Documents++;
        } else if (requested.total().compareTo(current.getReliefTotal()) > 0) {
          facts.cappedReliefs++;
        }
        annuals.add(current);
      }
    }
    replace(report.getRecordList(), records, report::setRecordList);
    replace(report.getAnnualList(), annuals, report::setAnnualList);
    report.setBgBoundaryReport(BgArt73Para6Boundaries.report(facts));
    return reportRepo.save(report);
  }

  /**
   * ЗАМЕНЯ СЪДЪРЖАНИЕТО НА СПИСЪКА, А НЕ САМИЯ СПИСЪК.
   *
   * <p><b>⚠️ И ТОВА НЕ Е СТИЛ.</b> Двата {@code one-to-many} се генерират с {@code cascade = ALL} и
   * {@code orphanRemoval = true}. Подменен ЦЕЛИЯТ списък на УПРАВЛЯВАН запис, Hibernate губи
   * връзката към стария {@code PersistentCollection} и хвърля „A collection with
   * cascade=all-delete-orphan was no longer referenced“ при flush — тоест повторното генериране на
   * ВЕЧЕ ЗАПИСАНА справка пада, а първото минава. Разликата между двата случая е дали редът вече е
   * бил в базата, което не се вижда от кода на строителя.
   *
   * <p>Изчистен и напълнен на място, същият списък остава управляван: старите редове се трият по
   * {@code orphanRemoval}, новите се записват по {@code cascade}.
   *
   * <p>⚠️ При НОВА справка списъкът е {@code null} и тогава сетърът е единственият път.
   */
  private static <T> void replace(
      List<T> existing, List<T> fresh, java.util.function.Consumer<List<T>> setter) {
    if (existing == null) {
      setter.accept(fresh);
      return;
    }
    existing.clear();
    existing.addAll(fresh);
  }

  /**
   * ГОДИШНОТО ПРЕИЗЧИСЛЯВАНЕ НА ЕДНО ЛИЦЕ — редове 8.1–8.3, колона 6 и колони 17–22.
   *
   * <p>⚠️ Чете се от вече построените ЗАПИСИ на лицето, а не от фишовете му: колона 5 (осигуровки,
   * внесени в чужбина) и редът по чл. 49 ал. 2 (доход от друг работодател) са ръчни входове на
   * записа и не съществуват на фиш. Доводът стои изцяло в {@link BgArt49Recalculation}.
   */
  protected BgArt73Para6Annual annual(
      BgArt73Para6Report report,
      Employee employee,
      List<BgArt73Para6Record> rows,
      BigDecimal taxRate,
      BgArt49Reliefs requested) {
    BgArt49Result result = BgArt49Recalculation.recalculate(rows, taxRate, requested);
    BgArt49Reliefs applied = result.getReliefs();

    BgArt73Para6Annual annual = new BgArt73Para6Annual();
    annual.setReport(report);
    annual.setEmployee(employee);
    annual.setReliefDocumentsProvided(applied.documentsProvided);
    annual.setReliefDisability(applied.disability);
    annual.setReliefVoluntaryPension(applied.voluntaryPension);
    annual.setReliefVoluntaryHealthLife(applied.voluntaryHealthLife);
    annual.setReliefServiceYears(applied.serviceYears);
    annual.setReliefDonationsCode01(applied.donationsCode01);
    annual.setReliefDonationsCode02(applied.donationsCode02);
    annual.setReliefDonationsCode03(applied.donationsCode03);
    annual.setReliefChildren(applied.children);
    annual.setReliefDisabledChildren(applied.disabledChildren);
    annual.setReliefTotal(result.getReliefTotal());
    annual.setBaseArt25Para1(result.getBaseArt25Para1());
    annual.setBaseArt25Para3(result.getBaseArt25Para3());
    annual.setBaseArt49(result.getBaseArt49());
    annual.setTaxableBase(result.getTaxableBase());
    annual.setAnnualTax(result.getAnnualTax());
    annual.setSettlement(result.getSettlement());
    annual.setToWithhold(result.getToWithhold());
    annual.setToRefund(result.getToRefund());
    annual.setTotalWithheld(result.getTotalWithheld());
    return annual;
  }

  /**
   * ПОИСКАНИТЕ облекчения на едно лице: изведеното от картона плюс ръчното от предишното
   * генериране.
   *
   * <p><b>⚠️ ИЗВЕДЕНОТО ЗАМЕСТВА, НЕИЗВЕДЕНОТО СЕ ПАЗИ.</b> Колони 8, 15 и 16 идват от картона на
   * лицето и се преизчисляват при всяко генериране — картонът е истината за тях. Колони 9–14 и
   * признакът по чл. 49 ал. 4 нямат източник в модела и се пазят от предишния запис. А когато
   * СУМАТА за годината не е заредена, {@link BgArt49ReliefEntitlement#derive} връща {@code null} и
   * тогава дори изведените колони се пазят: човек, въвел ги на ръка, не бива да ги губи при
   * следващото генериране.
   */
  protected BgArt49Reliefs requestedReliefs(
      Employee employee,
      BgArt73Para6Annual previous,
      BgArt49ReliefEntitlement.Amounts amounts,
      int taxYear) {
    BgArt49Reliefs derived = BgArt49ReliefEntitlement.derive(employee, amounts, taxYear);
    BgArt49Reliefs requested = new BgArt49Reliefs();

    requested.documentsProvided = previous != null && previous.getReliefDocumentsProvided();
    requested.disability =
        derived.disability != null
            ? derived.disability
            : previousValue(previous == null ? null : previous.getReliefDisability());
    requested.children =
        derived.children != null
            ? derived.children
            : previousValue(previous == null ? null : previous.getReliefChildren());
    requested.disabledChildren =
        derived.disabledChildren != null
            ? derived.disabledChildren
            : previousValue(previous == null ? null : previous.getReliefDisabledChildren());

    if (previous != null) {
      requested.voluntaryPension = previous.getReliefVoluntaryPension();
      requested.voluntaryHealthLife = previous.getReliefVoluntaryHealthLife();
      requested.serviceYears = previous.getReliefServiceYears();
      requested.donationsCode01 = previous.getReliefDonationsCode01();
      requested.donationsCode02 = previous.getReliefDonationsCode02();
      requested.donationsCode03 = previous.getReliefDonationsCode03();
    }
    return requested;
  }

  /** Сумите на годишните облекчения, в сила към 31 декември на данъчната година. */
  protected BgArt49ReliefEntitlement.Amounts reliefAmounts(
      BgPaySlipService paySlipService, int taxYear) {
    BgArt49ReliefEntitlement.Amounts amounts = new BgArt49ReliefEntitlement.Amounts();
    amounts.disabilityAnnual =
        paySlipService.annualParameter(BgArt49ReliefEntitlement.PARAM_DISABILITY_ANNUAL, taxYear);
    amounts.childOne =
        paySlipService.annualParameter(BgArt49ReliefEntitlement.PARAM_CHILD_ONE, taxYear);
    amounts.childTwo =
        paySlipService.annualParameter(BgArt49ReliefEntitlement.PARAM_CHILD_TWO, taxYear);
    amounts.childThreePlus =
        paySlipService.annualParameter(BgArt49ReliefEntitlement.PARAM_CHILD_THREE_PLUS, taxYear);
    amounts.childDisability =
        paySlipService.annualParameter(BgArt49ReliefEntitlement.PARAM_CHILD_DISABILITY, taxYear);
    return amounts;
  }

  /** Кодовете на НЕзаредените суми — назовават се в границите поименно, за да са зареждаеми. */
  protected static List<String> missingReliefAmounts(BgArt49ReliefEntitlement.Amounts amounts) {
    List<String> missing = new ArrayList<>();
    if (amounts.disabilityAnnual == null) {
      missing.add(BgArt49ReliefEntitlement.PARAM_DISABILITY_ANNUAL + " (чл. 18 ал. 1)");
    }
    if (amounts.childOne == null) {
      missing.add(BgArt49ReliefEntitlement.PARAM_CHILD_ONE + " (чл. 22в ал. 1 т. 1)");
    }
    if (amounts.childTwo == null) {
      missing.add(BgArt49ReliefEntitlement.PARAM_CHILD_TWO + " (чл. 22в ал. 1 т. 2)");
    }
    if (amounts.childThreePlus == null) {
      missing.add(BgArt49ReliefEntitlement.PARAM_CHILD_THREE_PLUS + " (чл. 22в ал. 1 т. 3)");
    }
    if (amounts.childDisability == null) {
      missing.add(BgArt49ReliefEntitlement.PARAM_CHILD_DISABILITY + " (чл. 22г ал. 1)");
    }
    return missing;
  }

  /**
   * ПРЕНАСЯ РЪЧНИТЕ КОЛОНИ НА ЗАПИСА при повторно генериране — колони 5 и 23.
   *
   * <p><b>⚠️ БЕЗ ТОВА ЦЯЛАТА ГОДИШНА ОСНОВА Е НЕВЯРНА ПРИ ВТОРОТО ГЕНЕРИРАНЕ.</b> Колона 5
   * (осигурителни вноски, внесени в чужбина) НАМАЛЯВА основата по чл. 25 ал. 1 наравно с колона 4,
   * но идва от документ на чуждестранна институция, не от фиш. Затрита при генериране, тя оставя
   * основата по-висока — и то мълчаливо, защото празна колона 5 е нормалното състояние за почти
   * всяко лице. Колона 23 (ЕИК на преобразуващото се предприятие) е ръчна по същата причина.
   */
  protected static void carryOver(BgArt73Para6Record previous, BgArt73Para6Record record) {
    if (previous == null) {
      return;
    }
    record.setInsuranceAbroad(previous.getInsuranceAbroad());
    record.setPredecessorIdentifier(previous.getPredecessorIdentifier());
  }

  /** Предишните годишни записи по идентификатор на лицето. */
  protected static Map<Long, BgArt73Para6Annual> byEmployee(List<BgArt73Para6Annual> annuals) {
    Map<Long, BgArt73Para6Annual> out = new LinkedHashMap<>();
    if (annuals != null) {
      for (BgArt73Para6Annual annual : annuals) {
        if (annual != null && annual.getEmployee() != null) {
          out.put(annual.getEmployee().getId(), annual);
        }
      }
    }
    return out;
  }

  /**
   * Предишните записи по „лице + код на дохода“.
   *
   * <p>⚠️ Ключът НЕ е уникален по устройство: указание т. 6 допуска НЯКОЛКО реда с един код за едно
   * лице, когато доходът идва от различни работодатели. Пренасянето хваща само първия и това е
   * приемливо днес — редът за друг работодател е ръчно добавен и няма да бъде пресъздаден от
   * генерирането изобщо. Записано, защото при затваряне на онази граница това място ще трябва да се
   * промени.
   */
  protected static Map<String, BgArt73Para6Record> byEmployeeAndCode(
      List<BgArt73Para6Record> records) {
    Map<String, BgArt73Para6Record> out = new LinkedHashMap<>();
    if (records != null) {
      for (BgArt73Para6Record record : records) {
        if (record == null || record.getIncomeType() == null) {
          continue;
        }
        out.putIfAbsent(recordKey(record.getEmployee(), record.getIncomeType().getCode()), record);
      }
    }
    return out;
  }

  protected static String recordKey(Employee employee, String code) {
    return (employee == null ? "?" : String.valueOf(employee.getId())) + "|" + code;
  }

  private static BigDecimal previousValue(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  /**
   * ТРУДОВИТЕ ДОГОВОРИ НА ЛИЦЕТО — всички, без филтър по дружество или по дата.
   *
   * <p><b>⚠️ ФИЛТРИРАНЕТО Е В {@link BgArt49MainEmployer}, А НЕ В ЗАЯВКАТА, И ТОВА Е РЕШЕНИЕ.</b>
   * Правилото на чл. 49 ал. 1 — това дружество, основен договор, действащ към 31 декември — е НОРМА
   * и трябва да може да се свери със свидетел с дати, без база. Сложено в JPQL-а, то щеше да се
   * проверява само от тест с Postgres, тоест на практика никога: базовите тестове в модула се
   * пропускат с обявена причина, когато база няма.
   */
  protected List<EmploymentContract> contractsOf(Employee employee) {
    if (employee == null) {
      return new ArrayList<>();
    }
    return Beans.get(EmploymentContractRepository.class)
        .all()
        .filter("self.employee = :e")
        .bind("e", employee)
        .fetch();
  }

  /** Един ред на т. 8 — идентификацията и трите изведени числа. */
  protected BgArt73Para6Record record(
      BgArt73Para6Report report, Employee employee, BgArt73Para6Line line, int taxYear) {
    BgIncomeType incomeType = incomeTypeFor(line.getCode(), taxYear);
    if (incomeType == null) {
      // ⚠️ НЕ СЕ СЪЗДАВА РЕД БЕЗ КОД. Липсващ ред в каталога значи, че App Install не е пускан за
      // тази година; ред с празен код би изглеждал като подаден доход без вид.
      return null;
    }
    BgArt73Para6Record record = new BgArt73Para6Record();
    record.setReport(report);
    record.setCorrectionCode("0");
    record.setEmployee(employee);
    record.setIncomeType(incomeType);

    Partner person = employee == null ? null : employee.getContactPartner();
    if (person != null) {
      record.setFirstName(trim(person.getFirstName()));
      record.setMiddleName(trim(person.getBgMiddleName()));
      record.setLastName(trim(person.getName()));
    }
    record.setIdentifier(employee == null ? null : trim(employee.getBgPersonalIdNumber()));
    record.setIdentifierType(employee == null ? null : idTypeOf(employee.getBgPersonalIdType()));

    // Колони 2.1 и 2.2 — платецът на дохода. Тук той е самият подател; отделен ред за ДРУГ
    // работодател по служебна бележка по чл. 45 ал. 1 е вход, който още няма (Фаза 2).
    record.setPayerIdentifier(report.getEmployerIdentifier());
    record.setPayerName(report.getEmployerName());

    record.setIncome(line.getIncome());
    record.setInsurance(line.getInsurance());
    record.setAdvanceTax(line.getAdvanceTax());
    // ⚠️ Т. 7 се вдига от build(), а НЕ тук, и това не е разсеяност. Признакът е за ЛИЦЕТО, не за
    // реда: той се чете веднъж от договорите и после се пише на всеки ред на човека. Прочетен тук,
    // той щеше да пуска по една заявка за всеки код на дохода на всяко лице — и, по-лошото, щеше да
    // допусне два реда на едно лице да отговорят различно на един и същ въпрос.
    return record;
  }

  /**
   * ЗАПИСАНАТА СПРАВКА → ФАЙЛ {@code SPR73_6.xml} по схемата {@code dec736}.
   *
   * <p><b>⚠️ ЧЕТЕ ЗАПИСАНОТО, НЕ АГРЕГИРА НАНОВО</b> — инвариантът „екран == файл“. Виж {@link
   * BgArt73Para6FileCollector}.
   *
   * <p>⚠️ Отказите падат в {@link BgArt73Para6SubmissionPlan} ПРЕДИ качване, тоест неуспял опит не
   * оставя нито файл на диска, нито ред в одитната следа.
   *
   * @throws IOException при знак, който CP1251 не представя
   * @throws IllegalStateException при блокираща находка или файл, който не минава схемата
   */
  @Transactional(rollbackOn = Exception.class)
  public BgArt73Para6Report generateFile(BgArt73Para6Report reportCtx) throws IOException {
    BgArt73Para6Report report = reportRepo.find(reportCtx.getId());
    refuseIfFiled(report);

    BgArt73Para6FileContent content = BgArt73Para6FileCollector.collect(report);
    BgDeclarationPlannedFile planned = BgArt73Para6SubmissionPlan.plan(fileService, content);

    // ⚠️ ПРЕЗ RegulatoryFile, А НЕ ПРЕЗ MetaFiles.upload — същата врата, през която минават
    // Приложение № 9 на НОИ и справката по ал. 1. Качването става ВЪТРЕ в услугата и отпечатъкът се
    // смята ВЕДНЪЖ, върху файла КАКТО Е ЗАПИСАН; извикващ, който качва сам и после иска артефакт,
    // държи за един миг файл, чийто отпечатък никой не е взел.
    NapArtifact artifact =
        artifactService.createFromFile(
            new RegulatoryFile(
                Institution.NAP, planned.getFileName(), MIME_XML, planned.getBytes()),
            NapArtifactRepository.TYPE_PAYLOAD,
            report.getCompany());

    report.setGeneratedArtifact(artifact);
    report.setGeneratedFile(artifact.getMetaFile());
    report.setFileGeneratedOn(LocalDateTime.now());
    report.setFileSha256(artifact.getSha256());
    report.setFileRecordCount(planned.getNumRecords());
    report.setStatusSelect(BgDeclarationStatus.GENERATED);
    return reportRepo.save(report);
  }

  /**
   * ОБЕЗСИЛВА генерирания файл — обратният преход към „Чернова“.
   *
   * <p>⚠️ Артефактът НЕ се трие: той е непроменима следа и може да е закачен към подаване. Тук се
   * изчиства само ПОСОЧВАНЕТО му от справката, тоест „този файл вече не е актуалният“.
   */
  @Transactional(rollbackOn = Exception.class)
  public BgArt73Para6Report invalidateFile(BgArt73Para6Report reportCtx) {
    BgArt73Para6Report report = reportRepo.find(reportCtx.getId());
    refuseIfFiled(report);
    report.setGeneratedFile(null);
    report.setGeneratedArtifact(null);
    report.setFileGeneratedOn(null);
    report.setFileSha256(null);
    report.setFileRecordCount(null);
    report.setStatusSelect(BgDeclarationStatus.DRAFT);
    return reportRepo.save(report);
  }

  /**
   * ОТБЕЛЯЗВА, ЧЕ СПРАВКАТА Е ПОДАДЕНА през портала.
   *
   * <p><b>⚠️ ПРЕЗ ОБЩАТА ВРАТА, И ВХОДЯЩИЯТ НОМЕР Е ЗАДЪЛЖИТЕЛЕН.</b> {@code NapSubmission},
   * построен НА РЪКА с „Подадена“ БЕЗ входящ номер и БЕЗ файл, е твърдение срещу празнота. Каналът
   * е {@code manual_portal} и по устройство не извършва транспорт, значи свидетелят на подаването е
   * НОМЕРЪТ, а не файлът — както при ал. 1. Второ копие на тези двайсет реда освен това би се
   * разминало с общата услуга при първата ѝ поправка.
   *
   * <p>⚠️ Файлът остава НЕЗАДЪЛЖИТЕЛЕН: човек може да е подал през портала със свой файл. Има ли
   * наш — подаването сочи ВЕЧЕ СЪЗДАДЕНИЯ артефакт, вместо да се роди втори за същите байтове.
   *
   * <p>⚠️ {@code referenceDate} е 28 ФЕВРУАРИ на следващата година — СРОКЪТ по чл. 73 ал. 6, а не
   * датата на подаване. Срещу него се чете „подадено ли е навреме“.
   *
   * @throws IllegalStateException когато липсва входящ номер или дата
   */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission attach(
      BgArt73Para6Report report, String incomingNumber, LocalDate incomingDate) throws IOException {

    BgDeclarationStatus.requireIncomingNumber(incomingNumber, incomingDate);

    Partner partner = report.getCompany() == null ? null : report.getCompany().getPartner();

    BgDeclarationSubmissionSpec spec =
        new BgDeclarationSubmissionSpec()
            .setCompany(report.getCompany())
            .setName("Справка чл. 73 ал. 6 / " + report.getTaxYear())
            .setReferenceDate(LocalDate.of(report.getTaxYear() + 1, 2, 28))
            .setTaxpayerPin(partner == null ? null : trim(partner.getRegistrationCode()))
            .setRelatedModel(BgArt73Para6Report.class.getName())
            .setRelatedRecordId(report.getId())
            .setPreviousSubmission(report.getNapSubmission())
            .setMetaFile(report.getGeneratedFile())
            .setArtifact(report.getGeneratedArtifact())
            .setFileName(
                report.getGeneratedFile() == null ? null : report.getGeneratedFile().getFileName())
            .setRecordCount(report.getFileRecordCount())
            .setIncomingNumber(incomingNumber.trim())
            .setIncomingDate(incomingDate);

    NapSubmission saved = submissionService.attach(spec);
    report.setNapSubmission(saved);
    report.setNapIncomingNumber(incomingNumber.trim());
    report.setNapResponseDate(incomingDate);
    report.setStatusSelect(BgDeclarationStatus.SUBMITTED);
    reportRepo.save(report);
    return saved;
  }

  /** Отказът на ново генериране след подаване — текстът го ползват и екранът, и свидетелят. */
  public static final String REGENERATION_LOCKED =
      "Справката е отбелязана като подадена (вх. № %s). Ново генериране е заключено: то би подменило"
          + " подаденото. За нова версия първо „Оттегли отметката“ — с причина.";

  /**
   * ЕДИН ДОКУМЕНТ — ЕДНО ПОДАВАНЕ: подадена справка не се строи, не се презаписва и файлът ѝ не се
   * обезсилва. Отхвърлена от НАП може — тя не е подаване, а опит.
   */
  protected void refuseIfFiled(BgArt73Para6Report report) {
    if (report == null) {
      return;
    }
    String status = report.getStatusSelect();
    if (BgDeclarationStatus.SUBMITTED.equals(status)
        || BgDeclarationStatus.ACCEPTED.equals(status)) {
      throw new IllegalStateException(
          String.format(
              I18n.get(REGENERATION_LOCKED),
              report.getNapIncomingNumber() == null ? "—" : report.getNapIncomingNumber()));
    }
  }

  /**
   * ОТТЕГЛЯ ОТМЕТКАТА „ПОДАДЕНА“ — грешно отбелязано (файлът не е стигнал до портала, сбъркан
   * номер).
   *
   * <p>⚠️ Подаването НЕ се трие: става {@code withdrawn} с причината и одитен запис — същото като
   * отмяната при Прил. 9. Справката се връща в „Генерирана“ (редовете са си там); следващото
   * отбелязване сочи оттегленото като предишно ({@code previousSubmission}).
   *
   * @throws IllegalStateException когато справката не е „Подадена“ или липсва причина
   */
  @Transactional(rollbackOn = Exception.class)
  public BgArt73Para6Report withdraw(BgArt73Para6Report reportCtx, String reason) {
    BgArt73Para6Report report = reportRepo.find(reportCtx.getId());
    if (report == null || !BgDeclarationStatus.SUBMITTED.equals(report.getStatusSelect())) {
      throw new IllegalStateException("Оттегля се само отметка „Подадена“.");
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalStateException(
          "Оттеглянето иска причина — защо отметката „Подадена“ е грешна. Тя остава в историята.");
    }
    NapSubmission submission = report.getNapSubmission();
    if (submission != null) {
      String previous = submission.getSubmissionStatusSelect();
      submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_WITHDRAWN);
      submission.setCorrectionReason(reason.trim());
      submissionRepo.save(submission);
      auditService.logHuman(
          null,
          submission,
          NapAuditEventRepository.EVENT_STATUS_CHANGE,
          previous,
          NapSubmissionRepository.STATUS_WITHDRAWN,
          "Отметката „Подадена“ е оттеглена: " + reason.trim());
    }
    report.setStatusSelect(BgDeclarationStatus.GENERATED);
    report.setNapIncomingNumber(null);
    report.setNapResponseDate(null);
    return reportRepo.save(report);
  }

  /**
   * Т. 5 на образеца от нашия тип идентификатор.
   *
   * <p><b>⚠️ ПРЕВОД, И НЕ СЪЩИЯТ КАТО ЗА Д1.</b> {@code BgD1RecordBuilder#idCodeOf} превежда в „0“
   * и „2“ по Наредба Н-13; тук кодовете са „0“ и „1“ по указание т. 4 към справката. Един общ
   * превод би дал „2“ в поле, което познава само две стойности. Празно се чете като ЕГН — същият
   * прочит като при Д1 и обичайният случай за български работодател.
   */
  public static String idTypeOf(String ourIdType) {
    String t = trim(ourIdType);
    return t == null || t.isEmpty() || OUR_ID_TYPE_EGN.equals(t) ? ID_TYPE_EGN : ID_TYPE_OTHER;
  }

  /**
   * Фишовете на годината, групирани по лице. Само потвърдени и платени.
   *
   * <p><b>⚠️ ПЛАТЕЦЪТ СЕ ЧЕТЕ ОТ ДОГОВОРА.</b> Носителят на дружеството е {@code
   * EmploymentContract.payCompany}.
   *
   * <p><b>⚠️ И ЗАЩО НЕ {@code BgPaySlip.company}.</b> То е дружеството на БРОЯЧА — записва се при
   * номерирането и е празно на заварените фишове, докато SQL-ът, който го попълва, не е пуснат.
   * Минала през него, заявката щеше (а) да изпусне заварените фишове на база, на която SQL-ът още
   * не е пуснат, и (б) да прибере фишовете на самоосигуряващите се, които днес нарочно остават
   * извън справката (долу). И двете са промяна на обхвата, не на носителя.
   *
   * <p><b>⚠️ И ОТТАМ ЕДНО СЛЕДСТВИЕ, КОЕТО СЕ ОБЯВЯВА, А НЕ СЕ ПРЕМЪЛЧАВА.</b> Фиш БЕЗ трудов
   * договор — тоест фишът на самоосигуряващото се лице, което носи {@code
   * bgSelfInsuredRegistration} вместо договор — не минава през този филтър и НЕ влиза в справката.
   * Това съвпада с обхвата на ал. 6 (трудови правоотношения по § 1 т. 26), но НЕ е избор на този
   * метод: {@link #employeeOf} нарочно чете лицето и от двата носителя. Признакът за код 102 (личен
   * труд на съдружник) е обявената липса на {@code BgArt73Para6Aggregator} и докато той не дойде,
   * самоосигуряващият се остава извън справката по същия довод, по който остава и днес.
   *
   * <p><b>⚠️ И ЗАЩО ГРАЖДАНСКИЯТ ДОГОВОР НЕ СЕ ИЗВАЖДА ТУК, А В АГРЕГАТОРА.</b> Изкушението е
   * очевидно — един ред JPQL до горните три. Заявката обаче отговаря КОИ ФИШОВЕ СА НА ТОВА
   * ДРУЖЕСТВО ЗА ТАЗИ ГОДИНА, а „кой ред на образеца носи този фиш“ се решава на едно място, заедно
   * с 101 и 103. Освен това {@code bgInsuredTypeCode} не е {@code required}: {@code &lt;&gt; '14'}
   * е НЕИЗВЕСТНО за {@code NULL} и щеше да изхвърли всеки договор без попълнен вид осигурен — тихо
   * и при зелен {@code BgQueryFieldTest}, който сверява имена на полета, а не стойности. Пълният
   * довод стои в граница 4 на {@link BgArt73Para6Aggregator}.
   */
  protected Map<Employee, List<BgPaySlip>> slipsByEmployee(Company company, int taxYear) {
    List<BgPaySlip> slips =
        paySlipRepo
            .all()
            .filter(
                "self.employmentContract.payCompany = :company "
                    + "AND self.statusSelect IN (:confirmed, :paid) "
                    + "AND self.period.fromDate >= :from AND self.period.fromDate <= :to")
            .bind("company", company)
            .bind("confirmed", BgPaySlipResolver.CONFIRMED)
            .bind("paid", BgPaySlipResolver.PAID)
            .bind("from", LocalDate.of(taxYear, 1, 1))
            .bind("to", LocalDate.of(taxYear, 12, 31))
            .fetch();

    Map<Employee, List<BgPaySlip>> out = new LinkedHashMap<>();
    for (BgPaySlip slip : slips) {
      Employee employee = employeeOf(slip);
      if (employee != null) {
        out.computeIfAbsent(employee, k -> new ArrayList<>()).add(slip);
      }
    }
    return out;
  }

  /** Лицето на фиша — от трудовия договор или от регистрацията на самоосигуряващото се лице. */
  protected static Employee employeeOf(BgPaySlip slip) {
    if (slip == null) {
      return null;
    }
    if (slip.getEmploymentContract() != null) {
      return slip.getEmploymentContract().getEmployee();
    }
    return slip.getBgSelfInsuredRegistration() == null
        ? null
        : slip.getBgSelfInsuredRegistration().getEmployee();
  }

  /** Редът от каталога за кода, в сила към 31 декември на данъчната година. */
  protected BgIncomeType incomeTypeFor(String code, int taxYear) {
    LocalDate date = LocalDate.of(taxYear, 12, 31);
    return incomeTypeRepo
        .all()
        .filter(
            "self.code = :c AND self.validFrom <= :d "
                + "AND (self.validTo IS NULL OR self.validTo >= :d)")
        .bind("c", code)
        .bind("d", date)
        .fetchOne();
  }

  protected BgArt73Para6Report existing(Company company, int taxYear) {
    return reportRepo
        .all()
        .filter("self.company = :company AND self.taxYear = :year")
        .bind("company", company)
        .bind("year", taxYear)
        .fetchOne();
  }

  private static String trim(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }
}

package com.axelor.apps.bglocalization.service.art73;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgArt73Para1Record;
import com.axelor.apps.bglocalization.db.BgArt73Para1Report;
import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.db.BgIndividualIncomePayment;
import com.axelor.apps.bglocalization.db.repo.BgArt73Para1ReportRepository;
import com.axelor.apps.bglocalization.db.repo.BgIndividualIncomePaymentRepository;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para1FileContent;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationPlannedFile;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationStatus;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationSubmissionService;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationSubmissionSpec;
import com.axelor.apps.napsubmissions.common.artifact.Institution;
import com.axelor.apps.napsubmissions.common.artifact.NapArtifactService;
import com.axelor.apps.napsubmissions.common.artifact.RegulatoryFile;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactRepository;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * СТРОИТЕЛЯТ НА СПРАВКАТА ПО ЧЛ. 73 АЛ. 1 (ОБРАЗЕЦ 5-2001) — от регистъра на изплатените доходи до
 * редовете на Част ІІ.
 *
 * <p><b>ЧЕТЕ КАКВОТО ВЕЧЕ Е В РЕГИСТЪРА.</b> Автоматичните захранвания — услугите 307x от
 * граждански договор, фактурите и AP, наемите — са отделна фаза и НЕ се симулират тук. Днешният
 * вход е ръчно въведеният ред от формата на регистъра.
 *
 * <p><b>⚠️ РАЗДЕЛЕНИЕТО СРЕЩУ {@link BgArt73Para1Aggregator} НЕ Е КОЗМЕТИЧНО.</b> Там живее
 * сливането — правилото, което указание т. 7 диктува — и то е проверимо без Postgres. Тук живее
 * всичко, което иска база: прочитът на регистъра, идентификацията на лицето (колони 4–8) и
 * закачането към транспорта. Слети, „две плащания по 1000 дават 2000“ би искало жива база, за да
 * бъде проверено.
 *
 * <p><b>⚠️ СРОКЪТ НЕ СЕ ПАЗИ ОТ ТОЗИ КЛАС И ТОВА Е НАРОЧНО.</b> Справката се подава до 28 февруари,
 * а корекция на данните за конкретно лице — до 30 септември. Отказ да се СТРОИ след срока би
 * направил невъзможно точно това, което указание т. 2 позволява. Срокът е въпрос на подаването, не
 * на извеждането.
 */
@Singleton
public class BgArt73Para1ReportService {

  /** Кодовете на дивидентите и ликвидационните дялове по чл. 38 ал. 1 — указание т. 4. */
  private static final List<String> DIVIDEND_CODES = Arrays.asList("814", "8141");

  /** Код 914 — стипендиите по чл. 13 ал. 1 т. 18; указание т. 12 ги изключва за училищата. */
  private static final String SCHOLARSHIP_CODE = "914";

  /** Шаблонът на доходите по чл. 33 — прехвърляне на права или имущество; указание т. 5. */
  private static final String PATTERN_TRANSFER = "D";

  /**
   * Тип идентификатор „3“ — чуждестранно лице без идентификатор; тогава колона 5 е задължителна.
   */
  private static final String ID_TYPE_NO_IDENTIFIER = "3";

  /** Типът на съдържанието за {@code MetaFile} — заповедта, т. 1: „Тип на файла – XML“. */
  private static final String MIME_XML = "application/xml";

  protected final BgArt73Para1ReportRepository reportRepo;
  protected final BgIndividualIncomePaymentRepository paymentRepo;
  protected final BgArt73Para1FileService fileService;
  protected final BgDeclarationSubmissionService submissionService;
  protected final NapArtifactService artifactService;

  @Inject
  public BgArt73Para1ReportService(
      BgArt73Para1ReportRepository reportRepo,
      BgIndividualIncomePaymentRepository paymentRepo,
      BgArt73Para1FileService fileService,
      BgDeclarationSubmissionService submissionService,
      NapArtifactService artifactService) {
    this.reportRepo = reportRepo;
    this.paymentRepo = paymentRepo;
    this.fileService = fileService;
    this.submissionService = submissionService;
    this.artifactService = artifactService;
  }

  /**
   * СТРОИ СПРАВКАТА за платеца и данъчната година.
   *
   * <p>⚠️ Плащанията се четат по ГОДИНАТА НА {@code paymentDate} — датата на придобиване по чл. 11
   * — а НЕ по тримесечния период за чл. 55. Периодът служи на друга декларация и плащане от края на
   * декември може да принадлежи на период, който вече е от следващата година.
   */
  @Transactional(rollbackOn = Exception.class)
  public BgArt73Para1Report build(Company company, int taxYear) {
    BgArt73Para1Report report = existing(company, taxYear);
    if (report == null) {
      report = new BgArt73Para1Report();
      report.setCompany(company);
      report.setTaxYear(taxYear);
    }
    report.setStatusSelect(BgDeclarationStatus.GENERATED);
    freezePayer(report, company);

    BgArt73Para1Aggregator.Result result =
        BgArt73Para1Aggregator.aggregate(paymentsOf(company, taxYear), taxYear);

    List<BgArt73Para1Record> records = new ArrayList<>();
    BgArt73Para1Boundaries.Facts facts = new BgArt73Para1Boundaries.Facts();
    facts.unusable = result.getUnusableCount();
    facts.otherForm = result.getOtherFormCount();
    facts.withoutPayer = countWithoutPayer(taxYear);

    for (BgArt73Para1Line line : result.getLines()) {
      records.add(record(report, line, facts));
    }
    facts.rows = records.size();

    // ⚠️ ЗАМЯНА, а не добавяне: при повторно строене за същата година старите редове трябва да
    // ИЗЧЕЗНАТ — долепени, коригираната справка би носела и старите, и новите числа. И замяна В
    // СЪЩИЯ списък, не нов: колекцията е all-delete-orphan и Hibernate отказва записана справка,
    // чийто списък е подменен („…no longer referenced by the owning entity instance“), а бутонът
    // работи само върху записана. Изчистените редове трие orphanRemoval (както в ал. 6).
    if (report.getRecordList() == null) {
      report.setRecordList(records);
    } else {
      report.getRecordList().clear();
      report.getRecordList().addAll(records);
    }
    report.setBgBoundaryReport(BgArt73Para1Boundaries.report(facts));
    return reportRepo.save(report);
  }

  /**
   * ЕДИН РЕД НА ЧАСТ ІІ — колони 1 до 15.
   *
   * <p>⚠️ Колони 2–9 се пълнят САМО за неcборните редове: указание т. 5 ги оставя празни за кодове
   * 5031, 8141 и 820. Агрегаторът вече е върнал {@code null} за лицето на такъв ред — тук просто
   * няма какво да се препише.
   */
  protected BgArt73Para1Record record(
      BgArt73Para1Report report, BgArt73Para1Line line, BgArt73Para1Boundaries.Facts facts) {
    BgArt73Para1Record record = new BgArt73Para1Record();
    record.setReport(report);
    record.setIncomeType(line.getIncomeType());
    record.setCorrectionCode(line.getCorrectionCode());
    record.setPartner(line.getPartner());
    record.setPaymentCount(line.getPaymentCount());

    if (!line.isAggregate()) {
      record.setIsForeign(line.isForeign());
      record.setIdentifierType(line.getIdentifierType());
      record.setCountryCode(line.isForeign() ? line.getCountryCode() : null);
      fillPerson(record, line);
    }

    // ⚠️ Заличаващият ред НЕ получава суми — указание т. 2 не изброява колони 11–15 сред
    // попълваните. Оставени неприсвоени, те стоят на нула в базата; кой ред носи суми се чете от
    // колона 1, не от сумите.
    if (line.carriesAmounts()) {
      record.setGrossAmount(line.getGrossAmount());
      record.setInsuranceAmount(line.getInsuranceAmount());
      record.setTaxAmount(line.getTaxAmount());
      record.setPersonCount(line.getPersonCount());
    }

    measure(record, line, facts);
    return record;
  }

  /**
   * КОЛОНИ 4 ДО 8 — идентификаторът, датата на раждане и трите имена, от ЛИЦЕТО.
   *
   * <p><b>⚠️ КОЛОНИ 4 И 5 СА ВЗАИМНОИЗКЛЮЧВАЩИ СЕ.</b> Указание т. 4, дословно: при тип
   * идентификатор „3“ (чуждестранно физическо лице без ЕГН, ЛН, ЛНЧ или Сл. №) „колона 4 НЕ се
   * попълва, а се попълва датата на раждане в колона 5“. Попълнени и двете, редът твърди, че лицето
   * има идентификатор — при тип „3“ то по определение няма.
   */
  protected void fillPerson(BgArt73Para1Record record, BgArt73Para1Line line) {
    Partner person = line.getPartner();
    if (person == null) {
      return;
    }
    record.setFirstName(trim(person.getFirstName()));
    record.setMiddleName(trim(person.getBgMiddleName()));
    record.setLastName(trim(person.getName()));

    if (ID_TYPE_NO_IDENTIFIER.equals(line.getIdentifierType())) {
      record.setBirthDate(person.getBgBirthDate());
    } else {
      record.setIdentifier(trim(person.getRegistrationCode()));
    }
  }

  /**
   * ИЗМЕРВА реда за границите — какво човек трябва да провери ръчно.
   *
   * <p>⚠️ Числата се събират ТУК, при строенето, а не се преброяват втори път при четене: втора
   * обиколка върху записаните редове би могла да даде друг отговор след ръчна редакция и текстът
   * щеше да говори за справка, каквато вече не е.
   */
  protected void measure(
      BgArt73Para1Record record, BgArt73Para1Line line, BgArt73Para1Boundaries.Facts facts) {
    BgIncomeType type = line.getIncomeType();
    String code = type == null ? null : type.getCode();

    if (DIVIDEND_CODES.contains(code)) {
      facts.dividendRows++;
    }
    if (type != null && PATTERN_TRANSFER.equals(type.getTaxPattern())) {
      facts.transferRows++;
    }
    if (SCHOLARSHIP_CODE.equals(code)) {
      facts.scholarshipRows++;
    }
    if (Boolean.TRUE.equals(record.getIsForeign())) {
      facts.foreignRows++;
    }
    if (line.hasIdentityDisagreement()) {
      facts.identityDisagreements++;
    }
    if (ID_TYPE_NO_IDENTIFIER.equals(record.getIdentifierType()) && record.getBirthDate() == null) {
      facts.missingBirthDates++;
    }
  }

  /**
   * ГЕНЕРИРА ФАЙЛА от ЗАПИСАНИТЕ редове и го прави сваляем.
   *
   * <p><b>⚠️ ЧЕТЕ ЗАПИСАНОТО, НЕ АГРЕГИРА НАНОВО</b> — инвариантът „екран == файл“; виж {@link
   * BgArt73Para1FileCollector}. {@link #build} и този метод са ДВЕ стъпки нарочно: първата извежда
   * редовете и ги дава за преглед, втората ги заковава в байтове.
   *
   * <p><b>⚠️ ФАЙЛЪТ СЕ РАЖДА ТУК, А НЕ ПРИ ЗАКАЧАНЕТО.</b> Човекът, който подава, отговаря по чл.
   * 82 ЗДДФЛ за верността на данните — тоест трябва да може да свали файла и да го погледне ПРЕДИ
   * да го качи. Роден вътре в подаването, той се вижда за пръв път след като одитната следа вече го
   * носи.
   *
   * <p>⚠️ Хвърля {@code BgMissingFileLayoutException}, докато физическият формат не е потвърден —
   * виж {@code BgArt73Para1FileLayout}. Отказът е ТВЪРД: файл по догадка се сваля и се качва, а
   * предупреждението остава на екрана.
   *
   * @throws IllegalStateException при блокираща находка на валидатора
   */
  @Transactional(rollbackOn = Exception.class)
  public BgArt73Para1Report generateFile(BgArt73Para1Report reportCtx) throws IOException {
    BgArt73Para1Report report = reportRepo.find(reportCtx.getId());
    BgArt73Para1FileContent content = BgArt73Para1FileCollector.collect(report);
    BgDeclarationPlannedFile planned = BgArt73Para1SubmissionPlan.plan(fileService, content);

    // ⚠️ ПРЕЗ RegulatoryFile, А НЕ ПРЕЗ MetaFiles.upload — същата врата, през която минава
    // Приложение № 9 на НОИ. Качването става ВЪТРЕ в услугата и отпечатъкът се смята ВЕДНЪЖ, върху
    // файла КАКТО Е ЗАПИСАН; извикващ, който качва сам и после иска артефакт, държи за един миг
    // файл, чийто отпечатък никой не е взел — прозорецът, в който повторен опит или откат оставя
    // сирак на диска.
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
   * ОБЕЗСИЛВА ГЕНЕРИРАНИЯ ФАЙЛ преди изрично пресъздаване.
   *
   * <p><b>⚠️ ЗАБРАВЯ ФАЙЛА, НО НЕ ГО ТРИЕ.</b> {@code MetaFile} и — ако вече е закачен — неговият
   * {@code NapArtifact} остават: те са одитната следа на това, което Е БИЛО подадено, и нищо
   * по-късно не бива да пренаписва миналото. Тук се къса само връзката „ТЕКУЩИЯТ файл на
   * справката“.
   *
   * <p>⚠️ Входящият номер и отговорът СЪЩО падат: те описваха СТАРИЯ файл. Оставени, те биха
   * твърдели, че НАП е приела съдържание, което вече не съществува.
   *
   * <p>⚠️ {@code napSubmission} НЕ се къса — подаването остава достижимо, а новото ще посочи него
   * като {@code previousSubmission}. Виж {@link #attach}.
   */
  @Transactional(rollbackOn = Exception.class)
  public BgArt73Para1Report invalidateFile(BgArt73Para1Report reportCtx) {
    BgArt73Para1Report report = reportRepo.find(reportCtx.getId());
    report.setGeneratedFile(null);
    report.setGeneratedArtifact(null);
    report.setFileGeneratedOn(null);
    report.setFileSha256(null);
    report.setFileRecordCount(null);
    report.setNapIncomingNumber(null);
    report.setNapResponseDate(null);
    report.setNapResponseNote(null);
    report.setStatusSelect(BgDeclarationStatus.DRAFT);
    return reportRepo.save(report);
  }

  /**
   * ОТБЕЛЯЗВА СПРАВКАТА КАТО ПОДАДЕНА през портала и я записва в одитната следа.
   *
   * <p><b>⚠️ НЕ ИСКА НАШ ФАЙЛ — но иска ВХОДЯЩ НОМЕР И ДАТА.</b> Това не е връщане към кухото
   * закачане: то създаваше подаване без файл И без номер, тоест „подадено“ срещу празнота. Докато
   * форматът на записа не дойде със заповед на НАП, човек подава през портала със СВОЙ файл — и
   * периодът пак трябва да има следа. Свидетелят е номерът, не файлът.
   *
   * <p>⚠️ Когато НИЕ сме генерирали файл, той се закача като {@code NapSubmissionFile}; когато няма
   * — ред НЕ се създава. Празен ред би твърдял подаден файл, какъвто няма. Дали подаването носи
   * нашия файл, се чете точно от присъствието на този ред.
   *
   * <p><b>⚠️ БЕЗ {@code serviceDocumentType} И БЕЗ {@code fileDocumentType}</b> — отказ да се
   * измисли стойност: enum-ите са фиксирани от договора на Declarations API и не познават справка
   * по чл. 73.
   *
   * <p>⚠️ Второ отбелязване след ИЗРИЧНО пресъздаване прави НОВО подаване и посочва старото като
   * {@code previousSubmission} — корекцията по указание т. 2 е нова справка с ВСИЧКИ данни, а не
   * редакция на подадената.
   *
   * <p>⚠️ {@code referenceDate} е 28 ФЕВРУАРИ на следващата година — СРОКЪТ по указание т. 2, а не
   * датата на подаване. Срещу него се чете „подадено ли е навреме“.
   */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission attach(
      BgArt73Para1Report report, String incomingNumber, LocalDate incomingDate) throws IOException {

    BgDeclarationStatus.requireIncomingNumber(incomingNumber, incomingDate);

    BgDeclarationSubmissionSpec spec =
        new BgDeclarationSubmissionSpec()
            .setCompany(report.getCompany())
            .setName("Справка чл. 73 ал. 1 / " + report.getTaxYear())
            .setReferenceDate(LocalDate.of(report.getTaxYear() + 1, 2, 28))
            .setTaxpayerPin(report.getPayerIdentifier())
            .setRelatedModel(BgArt73Para1Report.class.getName())
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

  /**
   * РЪЧЕН ИЗХОД: НАП е ПРИЕЛА или ОТХВЪРЛИЛА справката.
   *
   * <p><b>⚠️ РЪЧЕН, ЗАЩОТО КАНАЛЪТ Е РЪЧЕН.</b> {@code manual_portal} по устройство не извършва
   * транспорт и не може да върне отговор. Без тази врата „Приета“ и „Отхвърлена“ остават състояния,
   * които селекцията обещава, а нищо не достига.
   *
   * <p>⚠️ Иска входящ номер и дата: „прието“ без свидетел не отговаря на нито един въпрос, който
   * човек по-късно ще зададе на този запис.
   */
  @Transactional(rollbackOn = Exception.class)
  public BgArt73Para1Report recordOutcome(
      BgArt73Para1Report reportCtx,
      boolean accepted,
      String incomingNumber,
      LocalDate responseDate,
      String note) {

    BgArt73Para1Report report = reportRepo.find(reportCtx.getId());
    BgDeclarationStatus.requireManualOutcome(
        report.getNapSubmission() != null, incomingNumber, responseDate);
    report.setNapIncomingNumber(incomingNumber.trim());
    report.setNapResponseDate(responseDate);
    report.setNapResponseNote(note);
    report.setStatusSelect(accepted ? BgDeclarationStatus.ACCEPTED : BgDeclarationStatus.REJECTED);

    if (accepted) {
      submissionService.markAccepted(
          report.getNapSubmission(), incomingNumber.trim(), responseDate, note);
    } else {
      submissionService.markRejected(
          report.getNapSubmission(), incomingNumber.trim(), responseDate, note);
    }
    return reportRepo.save(report);
  }

  /**
   * ЧАСТ І, Т. 1 И Т. 2 — замразени на справката.
   *
   * <p>⚠️ Не се четат от {@code Company} при всеки прочит: подадена справка трябва да показва
   * данните, С КОИТО Е БИЛА подадена. Преименувано дружество не пренаписва миналото.
   *
   * <p>⚠️ И се пишат само когато са ПРАЗНИ — повторно строене на справката не бива да изтрива ръчна
   * поправка на наименованието.
   */
  protected void freezePayer(BgArt73Para1Report report, Company company) {
    Partner partner = company == null ? null : company.getPartner();
    if (partner == null) {
      return;
    }
    if (report.getPayerIdentifier() == null) {
      report.setPayerIdentifier(trim(partner.getRegistrationCode()));
    }
    if (report.getPayerName() == null) {
      report.setPayerName(trim(partner.getName()));
    }
  }

  /** Редовете на регистъра за платеца и годината, в реда на въвеждането им. */
  protected List<BgIndividualIncomePayment> paymentsOf(Company company, int taxYear) {
    return paymentRepo
        .all()
        .filter("self.company = :company AND self.paymentDate >= :from AND self.paymentDate <= :to")
        .bind("company", company)
        .bind("from", LocalDate.of(taxYear, 1, 1))
        .bind("to", LocalDate.of(taxYear, 12, 31))
        .order("paymentDate")
        .order("id")
        .fetch();
  }

  /**
   * РЕДОВЕТЕ ЗА ГОДИНАТА БЕЗ ПОСОЧЕН ПЛАТЕЦ.
   *
   * <p><b>⚠️ БРОЯТ СЕ, А НЕ СЕ ВКЛЮЧВАТ.</b> Полето {@code company} се появява СЛЕД Фаза 2 и ръчно
   * въведените дотогава редове го нямат. Включени по подразбиране, те биха работили в инсталация с
   * едно дружество и биха подали ЧУЖДИ доходи от името на подателя в инсталация с повече — а
   * разликата не се вижда от нито един ред на справката. Изхвърлени мълчаливо, те биха направили
   * справката празна без причина. Затова се броят и числото се назовава в границите.
   */
  protected int countWithoutPayer(int taxYear) {
    return (int)
        paymentRepo
            .all()
            .filter(
                "self.company IS NULL AND self.paymentDate >= :from AND self.paymentDate <= :to")
            .bind("from", LocalDate.of(taxYear, 1, 1))
            .bind("to", LocalDate.of(taxYear, 12, 31))
            .count();
  }

  protected BgArt73Para1Report existing(Company company, int taxYear) {
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

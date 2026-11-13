package com.axelor.apps.bglocalization.service.noi;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgApp9Export;
import com.axelor.apps.bglocalization.db.BgApp9ExportLine;
import com.axelor.apps.bglocalization.db.BgDeclarant;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.BgSickLeavePeriodLine;
import com.axelor.apps.bglocalization.db.repo.BgApp9DeclarationRepository;
import com.axelor.apps.bglocalization.db.repo.BgApp9ExportRepository;
import com.axelor.apps.bglocalization.service.payroll.BgSickLeavePeriodSlice;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.napsubmissions.common.artifact.Institution;
import com.axelor.apps.napsubmissions.common.artifact.NapArtifactService;
import com.axelor.apps.napsubmissions.common.artifact.RegulatoryFile;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactRepository;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.inject.Beans;
import com.axelor.studio.db.AppBgLocalization;
import com.axelor.studio.db.repo.AppBgLocalizationRepository;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ШЕВЪТ КЪМ БАЗАТА: емитерът и валидаторът са чисти функции, а тук се чете, записва и закача.
 *
 * <p><b>⚠️ РЕДЪТ Е ЗАДЪЛЖИТЕЛЕН И Е ТОЧНО ТОЗИ: липси → емисия → валидация → артефакт → статус.</b>
 * Всяка друга подредба води до едно и също: файл, който съществува, преди да е доказано, че е
 * годен. Артефактът се създава ПОСЛЕДЕН и САМО когато и двете проверки са минали — иначе базата
 * носи следа от опит, който не може да успее, и следващият човек намира прикачен файл, за който
 * никой не помни дали е бил валиден.
 *
 * <p><b>⚠️ И ЗАТОВА СТАТУСЪТ НЕ СЕ ВДИГА ПРИ ОТКАЗ.</b> При липси се записват само отчетите; {@code
 * statusSelect} остава там, където е било. Статус „генериран“ без файл е твърдение, което гридът
 * показва, а човек не може да провери.
 *
 * <p><b>⚠️ ФАЙЛЪТ ВЛИЗА ПРЕЗ ОБЩОТО ЯДРО, НЕ ПРЕЗ СВОЙ ПЪТ.</b> {@link
 * NapArtifactService#createFromFile} е същата врата, през която минават Д1 и Д6 — байтовете стават
 * MetaFile, отпечатъкът се смята ВЕДНЪЖ и редът се заключва. Дискриминаторът {@link
 * Institution#NOI} казва чий е файлът. Втори собствен път за качване тук би значел втора реализация
 * на неизменяемостта — и втора възможност тя да се разминае.
 *
 * <p><b>НУЛА МРЕЖА И НУЛА ПОДПИС.</b> Няма клиент към портала на НОИ, няма КЕП, няма извикване на
 * уеб-валидатора. Файлът се произвежда, проверява офлайн и закача; подаването е човешка стъпка.
 */
@Singleton
public class BgApp9ExportService {

  protected final NapArtifactService artifacts;
  protected final BgApp9Validator validator;

  @Inject
  public BgApp9ExportService(NapArtifactService artifacts, BgApp9Validator validator) {
    this.artifacts = artifacts;
    this.validator = validator;
  }

  /**
   * ЕДНО удостоверение → свой файл. Това стои зад бутона на формата.
   *
   * <p>Заглавната част се строи от ДРУЖЕСТВОТО на договора, а ТП на НОИ се чете от най-скорошния
   * износ на същото дружество — виж {@link #contextFor}.
   */
  @Transactional(rollbackOn = Exception.class)
  public BgApp9Emission generateForDeclaration(BgApp9Declaration declaration) throws IOException {
    String unvalidated =
        BgApp9DeclarationService.unvalidatedRefusal(
            declaration == null ? null : declaration.getSickLeave());
    if (unvalidated != null) {
      // Невалидиран лист не се изнася — същият текст като при създаването и опресняването.
      List<BgApp9Gap> gaps = new ArrayList<>(1);
      gaps.add(
          BgApp9Gap.of(
              BgApp9MandatoryPoints.label(declaration),
              "болничният лист не е валидиран",
              "PatientsChartNumber",
              unvalidated));
      BgApp9Emission refused = BgApp9Emission.refused(gaps);
      if (declaration != null) {
        declaration.setBgEmissionGaps(refused.gapReport());
        declaration.setBgLastAttempt(
            attempt(companyOf(declaration), "ОТКАЗ — болничният лист не е валидиран."));
      }
      return refused;
    }
    String submitted = refuseRegeneration(declaration);
    if (submitted != null) {
      // ⚠️ ОТКАЗ, А НЕ ПРЕЗАПИС, и нищо не се пипа — нито файлът, нито статусът, нито отчетите.
      // Без този пазач подаденото удостоверение би получило нов файл и би паднало на
      // „генерирано“, тоест подаденото би изчезнало без следа.
      List<BgApp9Gap> gaps = new ArrayList<>(1);
      gaps.add(
          BgApp9Gap.of(
              BgApp9MandatoryPoints.label(declaration),
              "подадено в НОИ",
              "CodeOperation",
              submitted));
      return BgApp9Emission.refused(gaps);
    }
    List<BgApp9Declaration> one = new ArrayList<>(1);
    one.add(declaration);

    BgApp9EmitterContext context = contextFor(companyOf(declaration), null);
    // ⚠️ Т. 26 се сверява с ДНЕШНИТЕ подадени удостоверения ПРЕДИ емисията — остаряла е отказ.
    BgApp9Gap staleP26 = staleFirstSubmission(declaration);
    BgApp9Emission emission =
        staleP26 != null
            ? BgApp9Emission.refused(new ArrayList<>(Collections.singletonList(staleP26)))
            : BgApp9Emitter.emit(context, one, 1);

    if (emission.isRefused()) {
      declaration.setBgEmissionGaps(withStaleFileWarning(emission, declaration.getBgNoiArtifact()));
      declaration.setBgValidationReport(null);
      declaration.setBgLastAttempt(
          attempt(
              companyOf(declaration),
              "ОТКАЗ — "
                  + emission.gapCount()
                  + " липси спират файла (виж „Какво липсва, за да е валидна“). Файл НЕ е"
                  + " закачен."));
      // ⚠️ Статусът НЕ мърда и нов файл НЕ се закача. Виж класовия javadoc.
      return emission;
    }

    List<BgApp9Finding> findings = validator.validate(emission.getXml(), one);
    declaration.setBgValidationReport(BgApp9Validator.report(findings));
    declaration.setBgEmissionGaps(null);

    if (BgApp9Validator.hasErrors(findings)) {
      // Валидацията е ВТОРАТА врата и тя също не пуска непълноценен файл нататък.
      BgApp9Emission refused = BgApp9Emission.refused(findingsAsGaps(findings, declaration));
      declaration.setBgLastAttempt(
          attempt(
              companyOf(declaration),
              "ОТКАЗ от проверката срещу схемата — "
                  + refused.gapCount()
                  + " грешки (виж „Проверки“). Файл НЕ е закачен."));
      return refused;
    }

    NapArtifact artifact =
        artifacts.createFromFile(
            new RegulatoryFile(
                Institution.NOI,
                emission.getFileName(),
                "text/xml",
                emission.getXml().getBytes(StandardCharsets.UTF_8)),
            NapArtifactRepository.TYPE_PAYLOAD,
            companyOf(declaration));

    declaration.setBgNoiArtifact(artifact);
    declaration.setBgGeneratedOn(LocalDateTime.now());
    // ⚠️ Подадено, коригирано или заличавано: подаденият файл остава в подаването
    // (NapSubmissionFile),
    // а тук сочи новият — корекцията. Статусът НЕ пада: подаването е факт, корекцията още не е.
    if (!STATUS_SUBMITTED.equals(declaration.getStatusSelect())) {
      declaration.setStatusSelect("GENERATED");
    }
    declaration.setBgLastAttempt(
        attempt(companyOf(declaration), "ГЕНЕРИРАН „" + emission.getFileName() + "“."));
    return emission;
  }

  /** Подадено в НОИ — със или без входящ номер (заварено). */
  static final String STATUS_SUBMITTED = "SUBMITTED";

  /**
   * ПАЗАЧЪТ СРЕЩУ ПРЕЗАПИСА НА ПОДАДЕНОТО — общ за двата пътя на генериране.
   *
   * <p>Подадено удостоверение се генерира наново САМО като „Корекция“ или „Заличаване“: тогава се
   * прави НОВ файл, а подаденият остава непокътнат в подаването си (SHA-256 и байтове). Без тях
   * новото генериране би подменило подадения файл с неподаден под същия статус.
   *
   * @return причината за отказ, или {@code null}
   */
  static String refuseRegeneration(BgApp9Declaration declaration) {
    if (declaration == null || !STATUS_SUBMITTED.equals(declaration.getStatusSelect())) {
      return null;
    }
    String type = declaration.getBgSubmissionTypeSelect();
    if ("CORRECT".equals(type) || "DELETE".equals(type)) {
      return null;
    }
    String which =
        declaration.getBgNoiSubmission() == null
            ? "подадено (заварено, без входящ №)"
            : "подадено в НОИ с вх. № " + declaration.getBgNoiSubmission().getNapIncomingNumber();
    return "удостоверението е "
        + which
        + ". Ново генериране би подменило подадения файл. За промяна изберете „Корекция“ или"
        + " „Заличаване“ във „Вид подаване“ — тогава се прави НОВ файл, а подаденият остава"
        + " непокътнат.";
  }

  /**
   * Влиза ли удостоверението в месечния файл — или защо не: невалидиран лист, или вече подадено без
   * „Корекция“/„Заличаване“. Едно място за пакета и за дневника.
   */
  static String refuseInclusion(BgApp9Declaration declaration) {
    String unvalidated =
        BgApp9DeclarationService.unvalidatedRefusal(
            declaration == null ? null : declaration.getSickLeave());
    return unvalidated != null ? unvalidated : refuseRegeneration(declaration);
  }

  /**
   * ЦЕЛИЯТ ИЗНОС — файлът с всички удостоверения в прозореца, плюс дневникът.
   *
   * <p>⚠️ ДНЕВНИКЪТ СЕ ПИШЕ И ПРИ ОТКАЗ. Описът трябва да показва КОЛКО болнични е имало в
   * прозореца, а не колко са влезли — иначе тихо изпуснатият лист изглежда като лист, който не е
   * съществувал.
   *
   * <p><b>⚠️ ЛИСТ С ЛИПСИ ОСТАВА ВЪН, НЕ СПИРА ЦЕЛИЯ ФАЙЛ.</b> Една липса в едно удостоверение не
   * бива да оставя без файл изрядните листове от същия месец. Листът с липси се изключва по същото
   * правило като черновата: причината е в дневника и в съобщението, удостоверението остава „не е
   * генерирано“ в „Прил. 9 за подаване“ и напомнянето продължава.
   *
   * <ul>
   *   <li>Липса в ЗАГЛАВНАТА част (адрес, декларатор, ТП на НОИ) засяга всеки документ — тя
   *       продължава да спира ЦЕЛИЯ файл.
   *   <li>Не остане ли след изключването нито едно удостоверение — отказ.
   *   <li>Втората врата (валидаторът) не се пипа: грешка там спира файла целия.
   * </ul>
   *
   * <p>⚠️ ЗАПИСЪТ Е ТУК, В СЪЩАТА ТРАНЗАКЦИЯ, а не в контролера — иначе всеки нов викач трябва да
   * помни да го прави.
   */
  @Transactional(rollbackOn = Exception.class)
  public BgApp9Emission generate(BgApp9Export export) throws IOException {
    BgApp9Emission emission = produce(export);
    if (export != null) {
      Beans.get(BgApp9ExportRepository.class).save(export);
    }
    return emission;
  }

  /** Тялото на {@link #generate} — без записа. */
  protected BgApp9Emission produce(BgApp9Export export) throws IOException {

    String exportSubmitted = refuseExportRegeneration(export);
    if (exportSubmitted != null) {
      // Подаденият износ не се пипа — нито отчетите, нито последният опит. Отказът е в съобщението.
      List<BgApp9Gap> gaps = new ArrayList<>(1);
      gaps.add(BgApp9Gap.header("подаден износ", "Pril9", exportSubmitted));
      return BgApp9Emission.refused(gaps);
    }

    List<BgApp9Declaration> window = declarationsIn(export);
    // ⚠️ ПОДАДЕНИТЕ НЕ ВЛИЗАТ ОТНОВО. Иначе подаденото би сочило файл, който никога не е подаван,
    // а новият файл би ги носил като повторно „Представяне“. В дневника остават, с причината.
    // ⚠️ И НЕВАЛИДИРАНИЯТ ЛИСТ НЕ ВЛИЗА — с причината в дневника, не тихо.
    List<BgApp9Declaration> candidates = new ArrayList<>();
    for (BgApp9Declaration d : window) {
      if (refuseInclusion(d) == null) {
        candidates.add(d);
      }
    }
    if (candidates.isEmpty() && !window.isEmpty()) {
      writeJournal(export, window, BgApp9Emission.refused(new ArrayList<BgApp9Gap>()));
      List<BgApp9Gap> gaps = new ArrayList<>(1);
      gaps.add(
          BgApp9Gap.header(
              "прозорецът",
              "NumberPril9",
              "нито едно удостоверение в прозореца не може да влезе: всички са вече подадени в"
                  + " НОИ или листовете им не са валидирани — причината за всяко е в дневника."));
      BgApp9Emission refused = BgApp9Emission.refused(gaps);
      recordRefusal(export, refused);
      return refused;
    }
    // Празен декларатор на износа = този по подразбиране на дружеството, ЗАПИСАН в износа: той е и
    // единственият, който ще подписва, тоест трябва да се вижда и да може да се смени изрично.
    if (export.getBgDeclarant() == null) {
      export.setBgDeclarant(declarantOf(export.getCompany()));
    }
    BgApp9EmitterContext context = contextFor(export.getCompany(), export);
    int sequence = export.getBgFileSequence() == 0 ? 1 : export.getBgFileSequence();
    // ⚠️ Т. 26 С ОСТАРЯЛА ПРИСЪДА НЕ ВЛИЗА — шумно, като лист с липси, а не с тихо преизчислена
    // стойност.
    List<BgApp9Gap> staleP26 = new ArrayList<>();
    List<BgApp9Declaration> fresh = new ArrayList<>();
    for (BgApp9Declaration d : candidates) {
      BgApp9Gap stale = staleFirstSubmission(d);
      if (stale == null) {
        fresh.add(d);
      } else {
        staleP26.add(stale);
      }
    }
    BgApp9Emission emission = BgApp9Emitter.emit(context, fresh, sequence);

    // ═══ ИЗКЛЮЧВАНЕТО ═══ Само когато ВСИЧКИ липси са в документите: една заглавна липса засяга
    // всеки лист и спира файла целия.
    List<BgApp9Declaration> declarations = fresh;
    List<BgApp9Gap> excluded = new ArrayList<>();
    if (!fresh.isEmpty() && emission.isRefused() && !hasFileLevelGap(emission)) {
      List<BgApp9Declaration> clean = new ArrayList<>();
      for (BgApp9Declaration d : fresh) {
        List<BgApp9Gap> own = gapsOf(emission, d);
        if (own.isEmpty()) {
          clean.add(d);
        } else {
          excluded.addAll(own);
        }
      }
      if (!clean.isEmpty() && clean.size() < fresh.size()) {
        declarations = clean;
        emission = BgApp9Emitter.emit(context, declarations, sequence).withExcluded(excluded);
      } else {
        // Нито едно не остава — отказ както досега, с липсите на всички.
        excluded.clear();
      }
    }
    // Остарялата т. 26 се прибавя към изключените — или към отказа, ако файл няма.
    if (!staleP26.isEmpty()) {
      if (fresh.isEmpty()) {
        List<BgApp9Gap> gaps = new ArrayList<>(BgApp9MandatoryPoints.ofHeader(context));
        gaps.addAll(staleP26);
        emission = BgApp9Emission.refused(gaps);
      } else if (emission.isRefused()) {
        List<BgApp9Gap> gaps = new ArrayList<>(emission.getGaps());
        gaps.addAll(staleP26);
        emission = BgApp9Emission.refused(gaps);
      } else {
        List<BgApp9Gap> all = new ArrayList<>(emission.getExcluded());
        all.addAll(staleP26);
        emission = emission.withExcluded(all);
      }
    }

    // ═══ ВТОРАТА ВРАТА — ПРЕДИ ДНЕВНИКА И ОТЧЕТИТЕ ═══ Отчетът „Файлът е генериран БЕЗ N…“,
    // дневникът и „влиза в файла“ върху удостоверенията се пишат СЛЕД проверката срещу схемата:
    // иначе при отказ от нея износът би твърдял, че файл има, а удостоверенията — че са в него.
    List<BgApp9Finding> findings = null;
    if (!emission.isRefused()) {
      findings = validator.validate(emission.getXml(), declarations);
      if (BgApp9Validator.hasErrors(findings)) {
        // Изключените остават изключени — с липсите си, но вече до отказ, не до файл.
        emission =
            BgApp9Emission.refused(findingsAsGaps(findings, null))
                .withExcluded(emission.getExcluded());
      }
    }

    writeJournal(export, window, emission);
    recordOnDeclarations(export, candidates, declarations, emission);

    if (emission.isRefused()) {
      export.setBgValidationReport(findings == null ? null : BgApp9Validator.report(findings));
      if (findings == null) {
        export.setBgEmissionGaps(withStaleFileWarning(emission, export.getBgNoiArtifact()));
        recordRefusal(export, emission);
      } else {
        export.setBgEmissionGaps(
            withStaleFileWarning(schemaRefusalReport(emission), export.getBgNoiArtifact()));
        export.setBgLastAttempt(
            attempt(
                export.getCompany(),
                "ОТКАЗ от проверката срещу схемата — "
                    + emission.gapCount()
                    + " грешки (виж „Проверки“). Файл НЕ е закачен."));
      }
      return emission;
    }

    // Изключените листове НЕ правят файла невалиден — техните липси стоят в отчета с ясен заглавен
    // ред, за да се виждат и след успеха. Пише се САМО тук: файлът е минал и двете врати.
    export.setBgValidationReport(BgApp9Validator.report(findings));
    export.setBgEmissionGaps(excludedReport(emission));

    NapArtifact artifact =
        artifacts.createFromFile(
            new RegulatoryFile(
                Institution.NOI,
                emission.getFileName(),
                "text/xml",
                emission.getXml().getBytes(StandardCharsets.UTF_8)),
            NapArtifactRepository.TYPE_PAYLOAD,
            export.getCompany());

    export.setBgNoiArtifact(artifact);
    export.setBgGeneratedOn(LocalDateTime.now());
    export.setStatusSelect("GENERATED");
    // Какво е излязло във файла — само за показ; генерирането НЕ го чете (единственият източник е
    // настройката). Стар ръчен флаг (напр. ЕИК-ът на дружеството) се заменя тук.
    export.setBgSourceFlag(context.source);
    // САМО влезлите във файла. Подадено удостоверение стига дотук единствено като корекция или
    // заличаване — тогава сочи новия файл, а статусът „подадено“ остава (подаденият файл е в
    // подаването си).
    for (BgApp9Declaration d : declarations) {
      d.setBgNoiArtifact(artifact);
      d.setBgGeneratedOn(export.getBgGeneratedOn());
      if (!STATUS_SUBMITTED.equals(d.getStatusSelect())) {
        d.setStatusSelect("GENERATED");
      }
    }
    export.setBgLastAttempt(
        attempt(
            export.getCompany(),
            "ГЕНЕРИРАН „"
                + emission.getFileName()
                + "“ с "
                + emission.getDocumentCount()
                + " удостоверения"
                + (emission.excludedDocuments().isEmpty()
                    ? "."
                    : "; изключени: "
                        + emission.excludedDocuments().size()
                        + " — "
                        + String.join("; ", emission.excludedDocuments())
                        + " (причините — в дневника и в „Какво липсва“).")));
    return emission;
  }

  /**
   * Т. 26 („първо представяне“) — ОСТАРЯЛА ЛИ Е спрямо ДНЕШНИТЕ подадени удостоверения на лицето.
   *
   * <p>Резолверът я записва при извеждането. Подаде ли се междувременно друго удостоверение на
   * същото лице, записаното „ДА — декларира сметка“ вече не е вярно: НОИ има декларирана сметка
   * (Приложение № 1, Pril9_Boln, BankAccountDeclared: „0 – декларира, 1 – има декларирана в НОИ
   * сметка … Задължително се декларира сметка при ПЪРВОТО представяне“). Стойността НЕ се
   * преизчислява тихо: отказът назовава удостоверението и бутона.
   *
   * <p>⚠️ Подадените (корекция/заличаване) не се проверяват — те остават замразени като подадения
   * файл. Празната т. 26 има свой отказ в {@link BgApp9MandatoryPoints}.
   *
   * @return липсата, или {@code null}, когато т. 26 е актуална
   */
  protected BgApp9Gap staleFirstSubmission(BgApp9Declaration declaration) {
    if (declaration == null || STATUS_SUBMITTED.equals(declaration.getStatusSelect())) {
      return null;
    }
    String stored = trim(declaration.getBgP26FirstSubmissionSelect());
    Employee employee = employeeOf(declaration);
    if (stored == null || employee == null) {
      return null;
    }
    boolean earlier =
        Boolean.TRUE.equals(
            Beans.get(BgApp9DeclarationService.class)
                .hasEarlierSubmittedApp9(employee, declaration.getSickLeave()));
    String expected = earlier ? BgApp9ObstoyatelstvaResolver.NO : BgApp9ObstoyatelstvaResolver.YES;
    if (expected.equals(stored)) {
      return null;
    }
    return BgApp9Gap.of(
            BgApp9MandatoryPoints.label(declaration),
            "т. 26 · първо представяне",
            "BankAccountDeclared",
            "присъдата е ОСТАРЯЛА: записано е „"
                + (BgApp9ObstoyatelstvaResolver.YES.equals(stored)
                    ? "ДА — лицето декларира сметка"
                    : "НЕ — има декларирана сметка")
                + "“, а днес за лицето "
                + (earlier
                    ? "вече има ПОДАДЕНО удостоверение по Прил. 9"
                    : "няма подадено удостоверение по Прил. 9")
                + ". Стойността не се преизчислява тихо — натиснете „Изведи/опресни"
                + " обстоятелствата“ на болничния лист и генерирайте отново.")
        .about(declaration);
  }

  /** Има ли липса в заглавната част — тя засяга всеки документ и спира файла целия. */
  static boolean hasFileLevelGap(BgApp9Emission emission) {
    for (BgApp9Gap gap : emission.getGaps()) {
      if (BgApp9Gap.FILE_LEVEL.equals(gap.document)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Отчетът след УСПЕШЕН износ — празен, или изключените листове с липсите им под ясен заглавен
   * ред.
   */
  static String excludedReport(BgApp9Emission emission) {
    if (emission.getExcluded().isEmpty()) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    sb.append("Файлът е генериран БЕЗ ")
        .append(emission.excludedDocuments().size())
        .append(" удостоверения — те остават „не е генерирано“ в „Прил. 9 за подаване“, докато")
        .append(" липсите им не бъдат поправени:");
    for (BgApp9Gap gap : emission.getExcluded()) {
      sb.append('\n').append(gap.asNote());
    }
    return sb.toString();
  }

  /** Последният опит на износа при отказ — отделно от файла: файл не се закача. */
  protected void recordRefusal(BgApp9Export export, BgApp9Emission refused) {
    export.setBgLastAttempt(
        attempt(
            export.getCompany(),
            "ОТКАЗ — "
                + refused.gapCount()
                + " липси спират файла (виж „Какво липсва, за да е валидна“). Файл НЕ е закачен."));
  }

  /**
   * РЕЗУЛТАТЪТ ОТ ОПИТА И ВЪРХУ УДОСТОВЕРЕНИЯТА, КОИТО СА СТИГНАЛИ ДО ЕМИТЕРА.
   *
   * <p>Месечният износ пише отчета на удостоверенията и при отказ, и при успех: иначе
   * удостоверение, влязло в готов файл, продължава да показва отказ от предишно единично
   * генериране. Отчетът на всяко от тях казва какво се е случило С НЕГО при ПОСЛЕДНИЯ опит.
   *
   * <p>⚠️ Подадените и невалидираните не стигат до емитера и НЕ се пипат — причината им е в
   * дневника.
   */
  protected void recordOnDeclarations(
      BgApp9Export export,
      List<BgApp9Declaration> candidates,
      List<BgApp9Declaration> included,
      BgApp9Emission emission) {
    String where = "износ „" + nz(export.getName()) + "“";
    List<BgApp9Gap> header = new ArrayList<>();
    for (BgApp9Gap gap : emission.getGaps()) {
      if (BgApp9Gap.FILE_LEVEL.equals(gap.document)) {
        header.add(gap);
      }
    }
    for (BgApp9Declaration d : candidates) {
      List<BgApp9Gap> own = new ArrayList<>(header);
      own.addAll(gapsOf(emission, d));
      own.addAll(excludedGapsOf(emission, d));
      d.setBgEmissionGaps(own.isEmpty() ? null : BgApp9Emission.refused(own).gapReport());
      String outcome;
      if (!emission.isRefused() && included.contains(d)) {
        // Пише се СЛЕД проверката срещу схемата — при отказ от нея емисията вече е отказ.
        outcome = "влиза в файла „" + emission.getFileName() + "“.";
      } else if (!emission.isRefused()) {
        outcome = "ИЗКЛЮЧЕНО от файла — " + own.size() + " липси (виж отчета).";
      } else {
        outcome = "ОТКАЗ — файлът не е генериран (" + own.size() + " липси за това удостоверение).";
      }
      d.setBgLastAttempt(attempt(export.getCompany(), where + ": " + outcome));
    }
  }

  /**
   * Един ред „последен опит“: кога, кой, какво.
   *
   * <p>⚠️ ЧАСЪТ Е МЕСТНИЯТ — в часовата зона на дружеството ({@code Company.timezone}), иначе
   * {@value #DEFAULT_ZONE}. Не часът на сървъра: той може да е в UTC, часове назад от часовника на
   * човека, който го чете.
   */
  static String attempt(Company company, String outcome) {
    User user = AuthUtils.getUser();
    return ZonedDateTime.now(zoneOf(company)).format(ATTEMPT_STAMP)
        + " · "
        + (user == null ? "система" : user.getName())
        + " · "
        + outcome;
  }

  private static final DateTimeFormatter ATTEMPT_STAMP =
      DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

  /** Зоната, когато дружеството няма своя — българската локализация. */
  static final String DEFAULT_ZONE = "Europe/Sofia";

  /** Часовата зона на дружеството, или {@link #DEFAULT_ZONE} при празна/непозната. */
  static ZoneId zoneOf(Company company) {
    String tz = company == null ? null : trim(company.getTimezone());
    if (tz != null) {
      try {
        return ZoneId.of(tz);
      } catch (java.time.DateTimeException ignored) {
        // непозната зона → българската, не UTC на сървъра
      }
    }
    return ZoneId.of(DEFAULT_ZONE);
  }

  /**
   * Подаден износ не се генерира наново: файлът му е отбелязан с входящ номер и смяната му би
   * оставила отметката върху байтове, които вече не са текущите. Новите листове отиват в НОВ износ.
   */
  static String refuseExportRegeneration(BgApp9Export export) {
    if (export == null || !STATUS_SUBMITTED.equals(export.getStatusSelect())) {
      return null;
    }
    return "износът е отбелязан като подаден в НОИ. Ново генериране би подменило подадения файл —"
        + " за нови листове направете нов износ, а за промяна на подадено удостоверение — корекция"
        + " от самото удостоверение.";
  }

  /**
   * ⚠️ СТАРИЯТ ФАЙЛ НЕ СЕ ТРИЕ, НО И НЕ СЕ ПРАВИ, ЧЕ Е ТЕКУЩИЯТ.
   *
   * <p>Артефактът е неизменяем и е следа какво Е БИЛО произведено — триенето му при неуспешен втори
   * опит би унищожило единственото доказателство за подаденото. Но и мълчанието е грешно: записът
   * би показвал закачен файл до списък с липси, и следващият читател няма как да разбере дали
   * файлът отговаря на днешното съдържание. Затова отчетът го КАЗВА, на първия ред.
   */
  static String withStaleFileWarning(BgApp9Emission emission, NapArtifact existing) {
    return withStaleFileWarning(emission.gapReport(), existing);
  }

  static String withStaleFileWarning(String report, NapArtifact existing) {
    if (existing == null) {
      return report;
    }
    return "⚠️ Закаченият файл („"
        + existing.getName()
        + "“) е от ПРЕДИШНО генериране и НЕ отговаря на текущото съдържание. Той се пази като следа"
        + " какво е било произведено; ново генериране ще го замени, след като липсите отдолу бъдат"
        + " поправени.\n\n"
        + report;
  }

  /**
   * Отчетът, когато ВТОРАТА врата (схемата) спре файла: файл НЯМА, грешките са в „Проверки“, а
   * изключените листове — ако има — стоят отделно с липсите си.
   */
  static String schemaRefusalReport(BgApp9Emission refused) {
    StringBuilder sb = new StringBuilder();
    sb.append("Файлът НЕ е генериран: проверката срещу схемата го спря — ")
        .append(refused.gapCount())
        .append(" грешки (виж „Проверки“). Файл не е закачен и нито едно удостоверение не е")
        .append(" отбелязано като влязло във файла.");
    for (BgApp9Gap gap : refused.getGaps()) {
      sb.append('\n').append(gap.asNote());
    }
    if (!refused.getExcluded().isEmpty()) {
      sb.append("\n\nОтделно ")
          .append(refused.excludedDocuments().size())
          .append(" удостоверения остават вън заради собствените си липси:");
      for (BgApp9Gap gap : refused.getExcluded()) {
        sb.append('\n').append(gap.asNote());
      }
    }
    return sb.toString();
  }

  /**
   * Удостоверенията в прозореца на износа.
   *
   * <p>⚠️ ПРОЗОРЕЦЪТ СЕ МЕРИ ПО {@code bgExportMonth}, КОГАТО Е ПОПЪЛНЕН, И ПО МЕСЕЦА НА
   * ПРЕДСТАВЯНЕ ({@code bgEmployerReceivedDate}) иначе — НПОПДОО чл. 12 т. 1 връзва подаването с
   * месеца на представянето, не с периода на листа. {@code fromDate} не е резерв: лист за края на
   * септември, представен на 2 октомври, би влязъл в септемврийския пакет, а срокът му е 10 ноември
   * — тиха грешка. Без двете дати листът НЕ влиза в пакет и стои в групата „няма дата на
   * представяне“ на „Прил. 9 за подаване“.
   */
  protected List<BgApp9Declaration> declarationsIn(BgApp9Export export) {
    if (export.getCompany() == null
        || export.getBgPeriodFrom() == null
        || export.getBgPeriodTo() == null) {
      return new ArrayList<>();
    }
    return Beans.get(BgApp9DeclarationRepository.class)
        .all()
        .filter(
            "self.sickLeave.employmentContract.payCompany = :company"
                + " AND self.statusSelect NOT IN ('CANCELLED')"
                + " AND ((self.sickLeave.bgExportMonth IS NOT NULL"
                + "       AND self.sickLeave.bgExportMonth BETWEEN :from AND :to)"
                + "   OR (self.sickLeave.bgExportMonth IS NULL"
                + "       AND self.sickLeave.bgEmployerReceivedDate BETWEEN :from AND :to))")
        .bind("company", export.getCompany())
        .bind("from", export.getBgPeriodFrom())
        .bind("to", export.getBgPeriodTo())
        .order("sickLeave.fromDate")
        .order("id")
        .fetch();
  }

  /** ДНЕВНИКЪТ — презаписва се изцяло при всяко генериране. */
  protected void writeJournal(
      BgApp9Export export, List<BgApp9Declaration> declarations, BgApp9Emission emission) {

    if (export.getBgExportLineList() == null) {
      export.setBgExportLineList(new ArrayList<BgApp9ExportLine>());
    }
    // ⚠️ Колекцията се МУТИРА, не се подменя — връзката е с orphanRemoval и подменен списък прави
    // старите редове „вече нереферирани“, при което Hibernate хвърля вместо да изтрие сираците.
    // Същият капан и същото решение като при BgSickLeaveService#writePeriodLines.
    export.getBgExportLineList().clear();

    for (BgApp9Declaration d : declarations) {
      BgSickLeave sick = d.getSickLeave();
      BgApp9ExportLine line = new BgApp9ExportLine();
      line.setBgApp9Declaration(d);
      line.setSickLeave(sick);
      line.setEmployee(employeeOf(d));
      line.setBgChartNumber(
          sick == null
              ? null
              : BgApp9ChartNumber.compose(
                  sick.getBgSeries(), sick.getBgYear(), sick.getBgDocumentNumber()));
      line.setBgLeaveFrom(sick == null ? null : sick.getFromDate());
      line.setBgLeaveTo(sick == null ? null : sick.getToDate());
      line.setBgDeclarationStatus(d.getStatusSelect());

      int[] days = daysByPayer(sick);
      line.setBgEmployerDays(Integer.valueOf(days[0]));
      line.setBgNoiDays(Integer.valueOf(days[1]));

      List<BgApp9Gap> own = gapsOf(emission, d);
      // Листът, изключен заради собствените си липси: файлът е готов, а той стои вън.
      List<BgApp9Gap> left = excludedGapsOf(emission, d);
      own.addAll(left);
      line.setBgGapCount(Integer.valueOf(own.size()));
      String excluded = refuseInclusion(d);
      line.setBgIncluded(
          Boolean.valueOf(!emission.isRefused() && excluded == null && left.isEmpty()));
      if (excluded != null) {
        // Подаденото и невалидираното остават в описа — с причината, че не са влезли.
        line.setBgNote("Не влиза в този файл: " + excluded);
      } else if (!left.isEmpty()) {
        StringBuilder sb = new StringBuilder("Не влиза в този файл — липси в удостоверението:");
        for (BgApp9Gap gap : left) {
          sb.append('\n').append(gap.asNote());
        }
        line.setBgNote(sb.toString());
      } else if (!own.isEmpty()) {
        StringBuilder sb = new StringBuilder();
        for (BgApp9Gap gap : own) {
          if (sb.length() > 0) {
            sb.append('\n');
          }
          sb.append(gap.asNote());
        }
        line.setBgNote(sb.toString());
      }
      export.addBgExportLineListItem(line);
    }
  }

  /**
   * Дните по платец за описа — СБОРОВЕТЕ на Ф0, всеки за себе си.
   *
   * <p>⚠️ Това НЕ е числото във файла. Т. 9 носи техния СБОР (виж {@link BgApp9Point9}); тук двете
   * стоят поотделно, защото описът отговаря на въпроса „колко плащаме ние и колко НОИ“.
   */
  static int[] daysByPayer(BgSickLeave sick) {
    int employer = 0;
    int noi = 0;
    if (sick != null && sick.getBgPeriodLineList() != null) {
      for (BgSickLeavePeriodLine line : sick.getBgPeriodLineList()) {
        if (line == null) {
          continue;
        }
        if (BgSickLeavePeriodSlice.PAYER_EMPLOYER.equals(line.getPayer())) {
          employer += line.getWorkDays();
        } else {
          noi += line.getWorkDays();
        }
      }
    }
    return new int[] {employer, noi};
  }

  /** Липсите на ЕДНО удостоверение, заради които то е оставено ВЪН от готовия файл. */
  static List<BgApp9Gap> excludedGapsOf(BgApp9Emission emission, BgApp9Declaration declaration) {
    List<BgApp9Gap> out = new ArrayList<>();
    for (BgApp9Gap gap : emission.getExcluded()) {
      if (gap.isAbout(declaration)) {
        out.add(gap);
      }
    }
    return out;
  }

  /**
   * Липсите, които се отнасят за ЕДНО удостоверение.
   *
   * <p>⚠️ ПО ID, НЕ ПО ЕТИКЕТА „лист № · име“ — виж {@link BgApp9Gap#declarationId}. При лице с два
   * договора етикетът е един за двете удостоверения и липсите на едното изключваха и изрядното.
   */
  static List<BgApp9Gap> gapsOf(BgApp9Emission emission, BgApp9Declaration declaration) {
    List<BgApp9Gap> out = new ArrayList<>();
    for (BgApp9Gap gap : emission.getGaps()) {
      if (gap.isAbout(declaration)) {
        out.add(gap);
      }
    }
    return out;
  }

  /**
   * ЗАГЛАВНАТА ЧАСТ от дружеството и декларатора.
   *
   * <p>⚠️ ТП НА НОИ И ФЛАГОВЕТЕ СЕ ЧЕТАТ ОТ ИЗНОСА, КОГАТО ИМА ИЗНОС. При бутона на едно
   * удостоверение износ няма — тогава се търси най-скорошният износ на СЪЩОТО дружество и
   * стойностите се вземат от него. Причината е практическа и е обявена: ТП на НОИ е свойство на
   * ОСИГУРИТЕЛЯ, което не се мени, а въвеждането му отново при всеки лист би било преписване. Ако
   * дружеството няма нито един износ, полето остава празно и емисията ОТКАЗВА с назована причина —
   * това е по-добре от подразбираща се стойност, която прилича на данни.
   */
  protected BgApp9EmitterContext contextFor(Company company, BgApp9Export export) {
    Partner partner = company == null ? null : company.getPartner();
    // ⚠️ АДРЕСЪТ НА ОСИГУРИТЕЛЯ Е Company.address — полето „Адрес“ на формата на дружеството,
    // единственият източник (същият като в SAF-T Header). Без резерв към партньора: тихото падане
    // от единия към другия прави невидимо кое е влязло във файла.
    Address address = company == null ? null : company.getAddress();
    BgApp9Place employerPlace = BgApp9Place.of(address, "осигурителя", BgApp9Place.EMPLOYER_WHERE);
    BgApp9Export header = export != null ? export : latestExportOf(company);
    // Деклараторът е НА ИЗНОСА (изричното поле), а без него — този по подразбиране на
    // дружеството.
    BgDeclarant declarant =
        header != null && header.getBgDeclarant() != null
            ? header.getBgDeclarant()
            : declarantOf(company);

    String bulstat = partner == null ? null : trim(partner.getRegistrationCode());

    String[] representative =
        BgApp9Emitter.splitNames(declarant == null ? null : declarant.getDeclarantNames());

    return BgApp9EmitterContext.builder()
        .flagDelegated(header != null && Boolean.TRUE.equals(header.getBgIsDelegated()) ? "1" : "0")
        .flagKasa(header != null && Boolean.TRUE.equals(header.getBgIsThroughKasa()) ? "1" : "0")
        .bulstatKasa(bulstat)
        .bulName(company == null ? null : company.getName())
        .bulstatMember(header == null ? "" : nz(header.getBgKasaMemberBulstat()))
        .nssiRo(
            header == null ? null : header.getNssiRoCode(),
            header == null ? null : header.getNssiRoName())
        // Областта, общината и населеното място — от града (ЕКАТТЕ), единственият четец.
        .address(
            employerPlace.region,
            employerPlace.municipality,
            employerPlace.settlement,
            BgApp9Emitter.zipOf(address),
            BgApp9Emitter.streetOf(address),
            partner == null ? null : partner.getFixedPhone(),
            partner == null ? null : partner.getMobilePhone(),
            BgApp9Emitter.emailOf(partner))
        .addressProblem(employerPlace.refusal)
        .representative(
            representative[0],
            representative[1],
            representative[2],
            // ⚠️ ДЛЪЖНОСТТА, не „Качество“: кодът R/A не е длъжност и XSD-то го отхвърля.
            declarant == null ? null : declarant.getDeclarantPosition(),
            declarant == null ? null : declarant.getDeclarantCity())
        .certificateDate(
            header != null && header.getBgCertificateDate() != null
                ? header.getBgCertificateDate()
                : LocalDate.now())
        .exportDate(
            header != null && header.getBgExportDate() != null
                ? header.getBgExportDate()
                : LocalDate.now())
        .savedAt(LocalDateTime.now())
        // ⚠️ „ФЛАГ НА ИЗТОЧНИК“ Е ЕИК-ЪТ НА РАЗРАБОТЧИКА НА СОФТУЕРА, НЕ НА ОСИГУРИТЕЛЯ.
        // Приложение № 1 (03/2024), лист Pril9_Boln, атрибут Source: „код по БУЛСТАТ на
        // разработчик на софтуера – 9, 11 или 13 цифри … „ePOPDOO_NSSI“ – софтуер на НОИ“;
        // Pril9.xsd
        // казва същото до елемента. ЕДИНСТВЕН ИЗТОЧНИК: настройката на инсталацията (същата като
        // поле 53 на Д1). Полето на износа не надделява — то само показва какво е излязло във
        // файла. Празно → назован отказ в BgApp9MandatoryPoints#ofHeader.
        .source(trim(softwareDeveloperEik()))
        .build();
  }

  /**
   * ЕИК-ът на разработчика на софтуера — настройката на ИНСТАЛАЦИЯТА ({@code
   * AppBgLocalization.bgSoftwareDeveloperEik}), една и съща за Прил. 9 ({@code Source}) и Д1 (поле
   * 53). Софтуерът е един за всички дружества, затова не е на дружеството.
   */
  protected String softwareDeveloperEik() {
    AppBgLocalization app = Beans.get(AppBgLocalizationRepository.class).all().fetchOne();
    return app == null ? null : app.getBgSoftwareDeveloperEik();
  }

  /**
   * Деклараторът ПО ПОДРАЗБИРАНЕ на дружеството ({@code isDefault}) — не първият по id: при двама
   * декларатори във файла трябва да влезе посоченият, а не произволно по-старият. Без декларатор по
   * подразбиране — {@code null}, и точките на длъжностното лице отказват поименно.
   */
  public BgDeclarant declarantOf(Company company) {
    if (company == null) {
      return null;
    }
    return Beans.get(com.axelor.apps.bglocalization.db.repo.BgDeclarantRepository.class)
        .all()
        .filter("self.company = :company AND self.isDefault = true")
        .bind("company", company)
        .fetchOne();
  }

  /**
   * Най-скорошният износ на дружеството — носителят на ТП на НОИ за бутона на едно удостоверение.
   */
  protected BgApp9Export latestExportOf(Company company) {
    if (company == null) {
      return null;
    }
    return Beans.get(BgApp9ExportRepository.class)
        .all()
        .filter("self.company = :company AND self.nssiRoCode IS NOT NULL")
        .bind("company", company)
        .order("-id")
        .fetchOne();
  }

  /**
   * Находките на валидатора, преведени в липси — за да има какво да прочете операторът, когато
   * ВТОРАТА врата спре файла.
   */
  static List<BgApp9Gap> findingsAsGaps(
      List<BgApp9Finding> findings, BgApp9Declaration declaration) {
    List<BgApp9Gap> out = new ArrayList<>();
    String doc =
        declaration == null ? BgApp9Gap.FILE_LEVEL : BgApp9MandatoryPoints.label(declaration);
    for (BgApp9Finding finding : findings) {
      if (finding.isError()) {
        out.add(BgApp9Gap.of(doc, finding.subject, finding.layer, finding.message));
      }
    }
    return out;
  }

  private static Company companyOf(BgApp9Declaration declaration) {
    if (declaration == null
        || declaration.getSickLeave() == null
        || declaration.getSickLeave().getEmploymentContract() == null) {
      return null;
    }
    return declaration.getSickLeave().getEmploymentContract().getPayCompany();
  }

  private static Employee employeeOf(BgApp9Declaration declaration) {
    if (declaration == null
        || declaration.getSickLeave() == null
        || declaration.getSickLeave().getEmploymentContract() == null) {
      return null;
    }
    return declaration.getSickLeave().getEmploymentContract().getEmployee();
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static boolean blank(String value) {
    return trim(value) == null;
  }

  private static String trim(String value) {
    if (value == null) {
      return null;
    }
    String t = value.trim();
    return t.isEmpty() ? null : t;
  }
}

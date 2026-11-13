package com.axelor.apps.bglocalization.service.noi;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgApp9Export;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.napsubmissions.common.audit.NapAuditService;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactRepository;
import com.axelor.apps.napsubmissions.db.repo.NapAuditEventRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionFileRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.Role;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ОТМЕТКАТА „ПОДАДЕНО В НОИ“ И НЕЙНАТА ОТМЯНА — един път и за месечния износ, и за единичното
 * генериране.
 *
 * <p><b>Единицата е ФАЙЛЪТ.</b> Порталът на НОИ дава ЕДИН входящ номер на ФАЙЛ, а файлът носи едно
 * или много удостоверения. Затова отметката е {@link NapSubmission} (услуга {@code noi_app9}, канал
 * {@code manual_portal}) с {@link NapSubmissionFile}, който сочи ТОЧНО този неизменяем {@link
 * NapArtifact} — и всяко удостоверение в него сочи активното си подаване ({@code bgNoiSubmission}),
 * а статусът му става SUBMITTED в същата транзакция. Т. 26 чете статуса и остава вярна.
 *
 * <p><b>⚠️ НИЩО НЕ СЕ ПОДАВА.</b> Това е ЗАПИС на човешко действие: подаването е с КЕП през портала
 * на НОИ. Входящият номер и датата от портала са задължителни — без тях „подадено“ е твърдение без
 * свидетел.
 *
 * <p><b>⚠️ ВСИЧКИ ОТКАЗИ СА ПРЕДИ ПЪРВИЯ ЗАПИС.</b> Отказана отметка не оставя нито подаване, нито
 * одит, нито сменен статус.
 *
 * <p><b>Права.</b> Право за запис по Прил. 9 ({@code Capabilities.NOI_WRITE}) или администратор — и
 * дружеството на файла трябва да е АКТИВНОТО дружество на потребителя. Проверява го услугата, не
 * само екранът: REST и бъдещи партиди минават покрай екрана.
 */
@Singleton
public class BgApp9SubmissionService {

  static final String STATUS_SUBMITTED = "SUBMITTED";
  static final String STATUS_GENERATED = "GENERATED";
  static final String STATUS_RESOLVED = "RESOLVED";

  /** Статусите, при които файлът СЕ СМЯТА за стигнал до НОИ. */
  static final List<String> ACTIVE_STATUSES =
      Collections.unmodifiableList(
          Arrays.asList(
              NapSubmissionRepository.STATUS_SUBMITTED,
              NapSubmissionRepository.STATUS_ACCEPTED,
              NapSubmissionRepository.STATUS_CORRECTION_REQUIRED));

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  protected final NapSubmissionRepository submissionRepo;
  protected final NapSubmissionFileRepository submissionFileRepo;
  protected final NapAuditService auditService;

  @Inject
  public BgApp9SubmissionService(
      NapSubmissionRepository submissionRepo,
      NapSubmissionFileRepository submissionFileRepo,
      NapAuditService auditService) {
    this.submissionRepo = submissionRepo;
    this.submissionFileRepo = submissionFileRepo;
    this.auditService = auditService;
  }

  // ═══ ОТМЕТКАТА ═══════════════════════════════════════════════════════════

  /**
   * ОТБЕЛЯЗВА, ЧЕ ФАЙЛЪТ Е ПОДАДЕН В НОИ.
   *
   * @param artifact точно генерираният файл ({@code bgNoiArtifact} на износа или удостоверението)
   * @param incomingNumber входящият номер, който порталът на НОИ е дал на файла
   * @param portalDate датата на подаването в портала — НЕ днешната: човек въвежда номера дни
   *     по-късно
   * @param actor кой отбелязва
   * @param today днешната дата — шев за свидетелите
   * @throws IllegalStateException с назована причина; тогава НИЩО не е записано — отказите са ПРЕДИ
   *     транзакцията, а записът е в {@link #writeMark}
   */
  public NapSubmission markSubmitted(
      NapArtifact artifact,
      String incomingNumber,
      LocalDate portalDate,
      User actor,
      LocalDate today) {

    if (artifact == null) {
      throw new IllegalStateException(
          "Няма генериран файл — няма какво да е подадено. Първо натиснете „Генерирай“.");
    }
    if (artifact.getInstitutionSelect() != null
        && !NapArtifactRepository.INSTITUTION_NOI.equals(artifact.getInstitutionSelect())) {
      throw new IllegalStateException(
          "Файлът „" + artifact.getName() + "“ не е за НОИ — отметката е само за Приложение № 9.");
    }
    String number = trim(incomingNumber);
    if (number == null) {
      throw new IllegalStateException(
          "Входящият номер от портала на НОИ е задължителен: без него „подадено“ е твърдение без"
              + " свидетел.");
    }
    if (portalDate == null) {
      throw new IllegalStateException(
          "Датата на подаване в портала на НОИ е задължителна — от нея се вижда дали е навреме.");
    }
    if (today != null && portalDate.isAfter(today)) {
      throw new IllegalStateException(
          "Датата на подаване " + DAY.format(portalDate) + " е в бъдещето.");
    }
    refuseUnauthorized(actor);

    List<BgApp9Declaration> covered = declarationsOf(artifact);
    if (covered.isEmpty()) {
      throw new IllegalStateException(
          "Файлът „"
              + artifact.getName()
              + "“ не е текущият файл на нито едно удостоверение — няма какво да се отбележи.");
    }
    Company company = companyOf(covered);
    refuseForeignCompany(actor, company);

    NapSubmission existing = activeSubmissionFor(artifact);
    if (existing != null) {
      throw new IllegalStateException(
          "Файлът „"
              + artifact.getName()
              + "“ вече е отбелязан като подаден (вх. № "
              + existing.getNapIncomingNumber()
              + "). Втора отметка за същия файл би дала два входящи номера на едни и същи байтове.");
    }

    // ═══ ОТКАЗИТЕ СВЪРШИХА — ОТТУК СЕ ПИШЕ, В ЕДНА ТРАНЗАКЦИЯ ═══
    return writeMark(artifact, number, portalDate, actor, covered, company);
  }

  /** Записът на отметката — подаване, файл, връзки, статуси и одит, всичко или нищо. */
  @Transactional(rollbackOn = Exception.class)
  protected NapSubmission writeMark(
      NapArtifact artifact,
      String number,
      LocalDate portalDate,
      User actor,
      List<BgApp9Declaration> covered,
      Company company) {
    List<BgApp9Export> exports = exportsOf(artifact);
    NapSubmission previous = previousOf(covered);

    NapSubmission submission = new NapSubmission();
    submission.setServiceCodeSelect(NapSubmissionRepository.SERVICE_NOI_APP9);
    submission.setChannelSelect(NapSubmissionRepository.CHANNEL_MANUAL_PORTAL);
    // Порталът на НОИ е производственият канал; „test“ би твърдял, че подаването е било проба.
    submission.setEnvironmentSelect(NapSubmissionRepository.ENVIRONMENT_PROD);
    submission.setCompany(company);
    submission.setReferenceDate(portalDate);
    submission.setName("Прил. 9 · " + artifact.getName() + " · вх. № " + number);
    // ⚠️ ПЪРВО ЧЕРНОВА, ПОСЛЕ ФАЙЛЪТ, ЧАК ТОГАВА „ПОДАДЕНО“ — в ЕДНА транзакция.
    // RegulatoryImmutabilityGuard отказва файл към заключено подаване, а заключено е всяко извън
    // draft/signing — и проверява ЗАПИСАНИЯ статус. Записано направо като „подадено“, подаването
    // би заключило собствения си файл и всяка отметка би паднала („Cannot add a file to a signed
    // submission“). Защитата не се разхлабва: файлът влиза, докато подаването е чернова, а
    // извън транзакцията черновата не се вижда никога — или всичко, или нищо.
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_DRAFT);
    submission.setNapIncomingNumber(number);
    // Моментът на подаването е датата от портала; кой е въвел — е отделен въпрос (submittedBy).
    submission.setSubmittedOn(portalDate.atStartOfDay());
    submission.setSubmittedBy(actor);
    submission.setPreviousSubmission(previous);
    if (previous != null) {
      submission.setCorrectionReason(
          "Корекция/заличаване на подаване вх. № " + previous.getNapIncomingNumber());
    }
    if (exports.size() == 1) {
      submission.setRelatedModel(BgApp9Export.class.getName());
      submission.setRelatedRecordId(exports.get(0).getId());
    } else if (covered.size() == 1) {
      submission.setRelatedModel(BgApp9Declaration.class.getName());
      submission.setRelatedRecordId(covered.get(0).getId());
    }
    submission = submissionRepo.save(submission);

    NapSubmissionFile file = new NapSubmissionFile();
    file.setSubmission(submission);
    file.setPayloadArtifact(artifact);
    file.setSequence(0);
    file.setSubmittedFileName(artifact.getName());
    file.setFileTypeSelect("xml");
    file.setFileSize(artifact.getByteLength());
    file.setNumRecords(covered.size());
    file.setFileStatusSelect(NapSubmissionFileRepository.FILE_STATUS_PENDING);
    submissionFileRepo.save(file);

    // Файлът е вътре — едва сега подаването става „подадено“ и се заключва.
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SUBMITTED);
    submission = submissionRepo.save(submission);

    for (BgApp9Declaration declaration : covered) {
      declaration.setBgNoiSubmission(submission);
      declaration.setStatusSelect(STATUS_SUBMITTED);
      // Групата в „Прил. 9 за подаване“ — веднага, не чак при следващ запис на листа.
      restamp(declaration);
    }
    for (BgApp9Export export : exports) {
      export.setStatusSelect(STATUS_SUBMITTED);
    }

    auditService.log(
        null,
        submission,
        NapAuditEventRepository.EVENT_MANUAL_SUBMISSION,
        NapSubmissionRepository.STATUS_DRAFT,
        NapSubmissionRepository.STATUS_SUBMITTED,
        NapAuditEventRepository.ACTOR_HUMAN,
        actor,
        "НОИ · Приложение № 9 · вх. № "
            + number
            + " от "
            + DAY.format(portalDate)
            + " · файл "
            + artifact.getName()
            + " · SHA-256 "
            + artifact.getSha256()
            + " · удостоверения: "
            + covered.size()
            + (previous == null ? "" : " · заменя вх. № " + previous.getNapIncomingNumber()));
    return submission;
  }

  // ═══ ОТМЯНАТА ════════════════════════════════════════════════════════════

  /**
   * ОТМЕНЯ ОТМЕТКАТА — само с причина.
   *
   * <p>Подаването НЕ се трие: статусът става {@code withdrawn}, а причината и потребителят остават
   * в одита. Удостоверенията се връщат към предишното активно подаване (ако отменената е била
   * корекция), иначе в „генерирано“ — и срокът им се вижда отново.
   */
  public NapSubmission withdraw(NapSubmission submission, String reason, User actor) {
    if (submission == null
        || !NapSubmissionRepository.SERVICE_NOI_APP9.equals(submission.getServiceCodeSelect())) {
      throw new IllegalStateException("Отменя се само отметка „подадено в НОИ“ (Приложение № 9).");
    }
    if (!ACTIVE_STATUSES.contains(submission.getSubmissionStatusSelect())) {
      throw new IllegalStateException(
          "Подаването не е активно (статус „"
              + submission.getSubmissionStatusSelect()
              + "“) — няма отметка за отменяне.");
    }
    String why = trim(reason);
    if (why == null) {
      throw new IllegalStateException(
          "Отмяната иска причина — тя остава в одита заедно с това кой и кога е отменил.");
    }
    refuseUnauthorized(actor);
    refuseForeignCompany(actor, submission.getCompany());
    return writeWithdrawal(submission, why, actor);
  }

  /** Записът на отмяната — всичко или нищо. Отказите са в {@link #withdraw}, преди транзакцията. */
  @Transactional(rollbackOn = Exception.class)
  protected NapSubmission writeWithdrawal(NapSubmission submission, String why, User actor) {
    NapSubmission previous = submission.getPreviousSubmission();
    boolean previousActive =
        previous != null && ACTIVE_STATUSES.contains(previous.getSubmissionStatusSelect());

    List<BgApp9Declaration> linked =
        JPA.all(BgApp9Declaration.class)
            .filter("self.bgNoiSubmission = :submission")
            .bind("submission", submission)
            .fetch();
    for (BgApp9Declaration declaration : linked) {
      if (previousActive) {
        declaration.setBgNoiSubmission(previous);
        declaration.setStatusSelect(STATUS_SUBMITTED);
      } else {
        declaration.setBgNoiSubmission(null);
        declaration.setStatusSelect(
            declaration.getBgNoiArtifact() != null ? STATUS_GENERATED : STATUS_RESOLVED);
      }
      // Отменената отметка връща листа в „Дължи се“ веднага.
      restamp(declaration);
    }
    NapArtifact artifact = artifactOf(submission);
    if (artifact != null) {
      for (BgApp9Export export : exportsOf(artifact)) {
        if (STATUS_SUBMITTED.equals(export.getStatusSelect())) {
          export.setStatusSelect(STATUS_GENERATED);
        }
      }
    }

    String before = submission.getSubmissionStatusSelect();
    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_WITHDRAWN);
    submissionRepo.save(submission);
    auditService.log(
        null,
        submission,
        NapAuditEventRepository.EVENT_STATUS_CHANGE,
        before,
        NapSubmissionRepository.STATUS_WITHDRAWN,
        NapAuditEventRepository.ACTOR_HUMAN,
        actor,
        "Отметката „подадено в НОИ“ (вх. № "
            + submission.getNapIncomingNumber()
            + ") е отменена. Причина: "
            + why);
    return submission;
  }

  /**
   * Групата и срокът на листа след смяна на подаването.
   *
   * <p>⚠️ САМО ГРУПАТА, НЕ И РЕЗОЛВЕРЪТ. {@code BgApp9DeclarationService#resolveInto} презаписва и
   * изведените т. 1–26 — а т. 26 („първо подаване“) брои ДРУГИТЕ подадени удостоверения на лицето.
   * Пуснат тук, той би обърнал т. 26 на два листа на един човек в един файл и подаденото
   * удостоверение щеше да се разминава с подадения файл. Присъдата за т. 9 се опреснява там, където
   * т. 9 се мени — при „Изведи т. 9 от потвърдения фиш“.
   */
  protected void restamp(BgApp9Declaration declaration) {
    if (declaration == null || declaration.getSickLeave() == null) {
      return;
    }
    // Обратната страна на one-to-one не се пълни сама в паметта — а фактите четат през нея.
    declaration.getSickLeave().setBgApp9Declaration(declaration);
    Beans.get(BgApp9DeadlineService.class).restamp(declaration.getSickLeave());
  }

  // ═══ ЧЕТЕНЕТО ════════════════════════════════════════════════════════════

  /** Активното подаване на файла, или {@code null}. */
  public NapSubmission activeSubmissionFor(NapArtifact artifact) {
    if (artifact == null || artifact.getId() == null) {
      return null;
    }
    List<NapSubmission> found =
        JPA.em()
            .createQuery(
                "SELECT f.submission FROM NapSubmissionFile f"
                    + " WHERE f.payloadArtifact = :artifact"
                    + " AND f.submission.serviceCodeSelect = :service"
                    + " AND f.submission.submissionStatusSelect IN :active"
                    + " ORDER BY f.submission.id DESC",
                NapSubmission.class)
            .setParameter("artifact", artifact)
            .setParameter("service", NapSubmissionRepository.SERVICE_NOI_APP9)
            .setParameter("active", ACTIVE_STATUSES)
            .setMaxResults(1)
            .getResultList();
    return found.isEmpty() ? null : found.get(0);
  }

  /** Удостоверенията, чийто ТЕКУЩ файл е този — те влизат в отметката. */
  protected List<BgApp9Declaration> declarationsOf(NapArtifact artifact) {
    if (artifact.getId() == null) {
      return Collections.emptyList();
    }
    return JPA.all(BgApp9Declaration.class)
        .filter("self.bgNoiArtifact = :artifact")
        .bind("artifact", artifact)
        .order("id")
        .fetch();
  }

  protected List<BgApp9Export> exportsOf(NapArtifact artifact) {
    if (artifact.getId() == null) {
      return Collections.emptyList();
    }
    return JPA.all(BgApp9Export.class)
        .filter("self.bgNoiArtifact = :artifact")
        .bind("artifact", artifact)
        .order("id")
        .fetch();
  }

  /** Файлът на подаването — първият (и единствен) ред. */
  protected NapArtifact artifactOf(NapSubmission submission) {
    NapSubmissionFile file =
        JPA.all(NapSubmissionFile.class)
            .filter("self.submission = :submission")
            .bind("submission", submission)
            .order("sequence")
            .fetchOne();
    return file == null ? null : file.getPayloadArtifact();
  }

  /**
   * Предишното активно подаване, което тази отметка заменя — само ако е ЕДНО за всички
   * удостоверения във файла. Корекция на удостоверения от различни подавания не се връзва към едно
   * от тях наслука.
   */
  static NapSubmission previousOf(List<BgApp9Declaration> covered) {
    Set<NapSubmission> earlier = new LinkedHashSet<>();
    for (BgApp9Declaration declaration : covered) {
      NapSubmission active = declaration.getBgNoiSubmission();
      if (active != null && ACTIVE_STATUSES.contains(active.getSubmissionStatusSelect())) {
        earlier.add(active);
      }
    }
    return earlier.size() == 1 ? earlier.iterator().next() : null;
  }

  /** Дружеството на файла. Един файл = един ЕИК (изискване за прием на НОИ). */
  static Company companyOf(List<BgApp9Declaration> covered) {
    Company company = null;
    for (BgApp9Declaration declaration : covered) {
      BgSickLeave sick = declaration.getSickLeave();
      EmploymentContract contract = sick == null ? null : sick.getEmploymentContract();
      Company own = contract == null ? null : contract.getPayCompany();
      if (own == null) {
        throw new IllegalStateException(
            "Удостоверение без дружество (лист без договор) — не се знае чий е файлът.");
      }
      if (company != null && !sameId(company, own)) {
        throw new IllegalStateException(
            "Файлът носи удостоверения на ДВЕ дружества — НОИ приема файл само с един ЕИК.");
      }
      company = own;
    }
    return company;
  }

  /** Право за запис по Прил. 9 — {@code Capabilities.NOI_WRITE}, или администратор. */
  static void refuseUnauthorized(User actor) {
    if (actor == null) {
      throw new IllegalStateException("Няма потребител — не може да се каже кой отбелязва.");
    }
    if (AuthUtils.isAdmin(actor)) {
      return;
    }
    for (String role : Capabilities.NOI_WRITE) {
      if (hasRole(actor, role)) {
        return;
      }
    }
    throw new IllegalStateException(
        "Нямате право да отбелязвате подаване към НОИ: нужна е роля за запис по Прил. 9 ("
            + String.join(", ", new java.util.TreeSet<>(Capabilities.NOI_WRITE))
            + ") или администратор.");
  }

  /**
   * Дружеството трябва да е АКТИВНОТО дружество на потребителя — без изключение и за
   * администратора. Активното дружество е изолирана среда; другите фирми от профила не стигат.
   */
  static void refuseForeignCompany(User actor, Company company) {
    if (company != null && company.getId() != null) {
      Set<Long> active = BgApp9DueScope.activeCompanyIds(actor);
      if (active.contains(company.getId())) {
        return;
      }
    }
    throw new IllegalStateException(
        "Дружеството „"
            + (company == null ? "?" : company.getName())
            + "“ не е активното ви дружество — отметката се отказва. Превключете активното"
            + " дружество (Предпочитания › Активно дружество).");
  }

  static boolean hasRole(User user, String roleName) {
    if (user.getRoles() != null) {
      for (Role role : user.getRoles()) {
        if (role != null && roleName.equals(role.getName())) {
          return true;
        }
      }
    }
    if (user.getGroup() != null && user.getGroup().getRoles() != null) {
      for (Role role : user.getGroup().getRoles()) {
        if (role != null && roleName.equals(role.getName())) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean sameId(Company a, Company b) {
    return a == b || (a.getId() != null && a.getId().equals(b.getId()));
  }

  private static String trim(String value) {
    if (value == null) {
      return null;
    }
    String t = value.trim();
    return t.isEmpty() ? null : t;
  }
}

package com.axelor.apps.bglocalization.service.etz;

import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.bglocalization.db.BgContractTermination;
import com.axelor.apps.bglocalization.db.repo.BgContractTerminationRepository;
import com.axelor.apps.bglocalization.service.BgCompensationResolver;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.napsubmissions.common.submission.NapFilingLock;
import com.axelor.apps.napsubmissions.common.submission.NapFilingLockedException;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.services.declarations.common.ServiceDocumentType;
import com.axelor.db.JPA;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.persistence.FlushModeType;

/**
 * ЕТЗ ЗА ПРЕКРАТЯВАНЕ НА ТРУДОВ ДОГОВОР (тип 3) — записът за прекратяването, срокът и отказите.
 *
 * <p><b>Закачалката е {@link BgContractTermination}, не договорът.</b> „Един документ — едно
 * подаване“ ({@link NapFilingLock}) заключва по {@code relatedModel} + {@code relatedRecordId};
 * закачено за договора, прекратяването би било заключено от подаденото сключване.
 *
 * <p><b>⚠️ СРОКЪТ Е СЕДЕМ ДНИ ОТ ПРЕКРАТЯВАНЕТО</b> — чл. 62 ал. 3 КТ („в седемдневен срок от
 * неговото прекратяване“) и чл. 10 ал. 4 т. 1 НВРЗ. Календарни дни, без пренасяне при неработен ден
 * — същото правило като при сключването. След срока — предупреждение по чл. 62 ал. 4, не отказ.
 *
 * <p><b>⚠️ ГЕНЕРИРАНЕТО ОТКАЗВА ВИНАГИ.</b> т. 25 (използван отпуск) и т. 30 (признат стаж) са
 * задължителни при тип 3 и вид 01, а още не се изчисляват. Отказът изрежда и всичко останало, което
 * липсва (основание, дата …), за да се попълни още сега.
 */
@Singleton
public class BgEtzTerminationService {

  /** чл. 62 ал. 3 КТ — „в седемдневен срок от неговото прекратяване“. */
  public static final int DAYS_TO_FILE = 7;

  public static final String NOT_COMPUTED = "т. 25/30/31 още не се изчисляват";

  /** След подаден ЕТЗ за прекратяване датата и основанието не се пипат. */
  public static final String FILED_LOCKED =
      "Подаден е ЕТЗ за прекратяване – промяната става с корекция";

  public static final String DATE_ONLY_VIA_RECORD =
      "Датата на прекратяване се сменя само през записа за прекратяване (ЕТЗ — прекратяване)";

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  protected final BgContractTerminationRepository terminationRepo;
  protected final NapSubmissionRepository submissionRepo;
  protected final BgEtzExportService exportService;
  protected final AppBaseService appBaseService;

  @Inject
  public BgEtzTerminationService(
      BgContractTerminationRepository terminationRepo,
      NapSubmissionRepository submissionRepo,
      BgEtzExportService exportService,
      AppBaseService appBaseService) {
    this.terminationRepo = terminationRepo;
    this.submissionRepo = submissionRepo;
    this.exportService = exportService;
    this.appBaseService = appBaseService;
  }

  /** Крайният ден по чл. 62 ал. 3 — датата на прекратяване + 7 календарни дни. */
  public static LocalDate dueDate(BgContractTermination termination) {
    LocalDate date = termination == null ? null : termination.getTerminationDate();
    return date == null ? null : date.plusDays(DAYS_TO_FILE);
  }

  /** Записът за прекратяването на договора — или {@code null}. */
  public BgContractTermination record(EmploymentContract contract) {
    return contract == null || contract.getId() == null ? null : recordOf(contract.getId());
  }

  /**
   * Записът по id на договора, без да изпразва контекста: заявката е с {@code COMMIT}, тоест не
   * flush-ва договора, който в момента се записва.
   */
  public static BgContractTermination recordOf(Long contractId) {
    List<BgContractTermination> rows =
        JPA.em()
            .createQuery(
                "SELECT t FROM BgContractTermination t WHERE t.employmentContract.id = :c",
                BgContractTermination.class)
            .setParameter("c", contractId)
            .setFlushMode(FlushModeType.COMMIT)
            .getResultList();
    return rows.isEmpty() ? null : rows.get(0);
  }

  /**
   * Създава записа за прекратяването. Датата идва от {@link
   * BgCompensationResolver#terminationDateOf} — изричната, а при срочен договор без нея уговореният
   * край — и се записва в договора (виж {@code BgContractTerminationManagementRepository}).
   */
  @Transactional(rollbackOn = Exception.class)
  public BgContractTermination create(EmploymentContract contract) {
    BgContractTermination existing = record(contract);
    if (existing != null) {
      throw new BgEtzExportService.BgEtzRefusalException(
          "Записът за прекратяване вече има — датата е "
              + DAY.format(existing.getTerminationDate())
              + ". Един договор — едно прекратяване.");
    }
    LocalDate date = BgCompensationResolver.terminationDateOf(contract);
    if (date == null) {
      throw new BgEtzExportService.BgEtzRefusalException(
          "Договорът няма нито дата на прекратяване, нито уговорен край — попълнете: "
              + BgEtzScreens.of(BgEtz62Field.ENDDATE)
              + " (или „Крайна дата“ при срочен договор).");
    }
    BgContractTermination termination = new BgContractTermination();
    termination.setEmploymentContract(contract);
    termination.setTerminationDate(date);
    termination.setFromAgreedEnd(contract.getBgTerminationDate() == null);
    return terminationRepo.save(termination);
  }

  /** Подаванията за прекратяването, най-новото първо. */
  public List<NapSubmission> submissions(BgContractTermination termination) {
    if (termination == null || termination.getId() == null) {
      return new ArrayList<>();
    }
    return submissionRepo
        .all()
        .filter(
            "self.relatedModel = :model AND self.relatedRecordId = :id"
                + " AND self.serviceDocumentTypeSelect = :type")
        .bind("model", BgContractTermination.class.getName())
        .bind("id", termination.getId())
        .bind("type", ServiceDocumentType.DEC_ETZ62.name())
        .order("-id")
        .fetch();
  }

  /** Подаденото (по който и да е път) — или {@code null}. */
  public NapSubmission filed(BgContractTermination termination) {
    for (NapSubmission submission : submissions(termination)) {
      if (NapFilingLock.FILED_STATUSES.contains(submission.getSubmissionStatusSelect())) {
        return submission;
      }
    }
    return null;
  }

  /** Последната чернова — или {@code null}. */
  public NapSubmission latestDraft(BgContractTermination termination) {
    for (NapSubmission submission : submissions(termination)) {
      if (NapSubmissionRepository.STATUS_DRAFT.equals(submission.getSubmissionStatusSelect())) {
        return submission;
      }
    }
    return null;
  }

  /**
   * Има ли подаден ЕТЗ за прекратяването с този id — пазачите на договора, на записа и на речника
   * питат ТОВА, едно място.
   */
  public static boolean isFiled(Long terminationId) {
    if (terminationId == null) {
      return false;
    }
    Long count =
        JPA.em()
            .createQuery(
                "SELECT COUNT(s) FROM NapSubmission s WHERE s.relatedModel = :m"
                    + " AND s.relatedRecordId = :id AND s.submissionStatusSelect IN :filed",
                Long.class)
            .setParameter("m", BgContractTermination.class.getName())
            .setParameter("id", terminationId)
            .setParameter("filed", NapFilingLock.FILED_STATUSES)
            .setFlushMode(FlushModeType.COMMIT)
            .getSingleResult();
    return count != null && count > 0;
  }

  /**
   * „Генерирай ЕТЗ (прекратяване)“. ВИНАГИ отказ: т. 25/30/31 още не се изчисляват. Отказът изрежда
   * и останалите пречки (основание, дата …) с полето и екрана.
   *
   * @throws NapFilingLockedException при вече подадено прекратяване
   * @throws BgEtzExportService.BgEtzRefusalException винаги иначе
   */
  public NapSubmission generate(EmploymentContract contract) {
    BgContractTermination termination = record(contract);
    if (termination == null) {
      throw new BgEtzExportService.BgEtzRefusalException(
          "Няма запис за прекратяване — натиснете първо „Създай запис за прекратяване“.");
    }
    NapSubmission filed = filed(termination);
    if (filed != null) {
      throw new NapFilingLockedException(
          String.format(
              "%s: ЕТЗ за прекратяването вече е подадено — „%s“, вх. № %s. Един документ — едно"
                  + " подаване.",
              BgEtzConclusionService.REGENERATION_LOCKED,
              nz(filed.getName()),
              nz(filed.getNapIncomingNumber())));
    }
    List<BgEtzGap> gaps = new ArrayList<>();
    exportService.preview(contract, BgEtzEventKind.TERMINATION, gaps, null);
    // т. 25 и т. 30 — пречки „няма носител“ от резолвера; за човека те са ЕДНО нещо: още не се
    // изчисляват. Останалите пречки се показват както при сключването.
    List<BgEtzGap> others = new ArrayList<>();
    for (BgEtzGap gap : gaps) {
      if (!isNotComputed(gap)) {
        others.add(gap);
      }
    }
    StringBuilder text = new StringBuilder("ЕТЗ файл за прекратяване НЕ се произвежда:\n");
    if (!others.isEmpty()) {
      text.append(BgEtzScreens.describe(others)).append('\n');
    }
    text.append("• ")
        .append(NOT_COMPUTED)
        .append(" — използваният отпуск (т. 25) и стажът (т. 30, т. 31) идват с изчислението.");
    throw new BgEtzExportService.BgEtzRefusalException(text.toString());
  }

  private static boolean isNotComputed(BgEtzGap gap) {
    String where = gap.getWhere();
    return gap.getKind() == BgEtzGap.Kind.NO_DATA_SOURCE
            && (BgEtz62Field.USEDVACATION.label().equals(where)
                || BgEtz62Field.EXPERIENCE.label().equals(where))
        || startsWith(gap.getDetail(), BgEtz62Field.USEDVACATION.label())
        || startsWith(gap.getDetail(), BgEtz62Field.EXPERIENCE.label());
  }

  private static boolean startsWith(String text, String label) {
    return text != null && text.startsWith(label);
  }

  /** Какво да покаже панелът „ЕТЗ — прекратяване“ — изчислено, нищо не се записва. */
  public Status status(EmploymentContract contract) {
    Status status = new Status();
    status.termination = record(contract);
    if (status.termination == null) {
      status.text =
          "Няма запис за прекратяване. Датата идва от „Дата на действително прекратяване“, а при"
              + " срочен договор без нея — от уговорения край.";
      return status;
    }
    status.dueDate = dueDate(status.termination);
    status.filed = filed(status.termination);
    status.draft = status.filed == null ? latestDraft(status.termination) : null;
    LocalDate today = appBaseService.getTodayDate(contract.getPayCompany());
    status.overdue =
        status.filed == null && status.dueDate != null && today.isAfter(status.dueDate);
    status.text = text(status);
    return status;
  }

  protected String text(Status status) {
    BgContractTermination t = status.termination;
    StringBuilder sb =
        new StringBuilder("Дата на прекратяване: ").append(DAY.format(t.getTerminationDate()));
    if (Boolean.TRUE.equals(t.getFromAgreedEnd())) {
      sb.append(" — уговореният край на срочния договор (изрична дата на прекратяване няма)");
    }
    sb.append(". ");
    if (status.filed != null) {
      sb.append("Подадено в портала на НАП")
          .append(
              status.filed.getSubmittedOn() == null
                  ? ""
                  : " на " + DAY.format(status.filed.getSubmittedOn().toLocalDate()))
          .append(", вх. № ")
          .append(nz(status.filed.getNapIncomingNumber()))
          .append('.');
      return sb.toString();
    }
    sb.append("Подай до ")
        .append(DAY.format(status.dueDate))
        .append(" — 7 дни от прекратяването (чл. 62 ал. 3 КТ).");
    sb.append(
        status.draft == null
            ? " " + NOT_COMPUTED + " — генерирането е спряно."
            : " Чернова: „"
                + nz(status.draft.getName())
                + "“ — свалете файла, подайте го в портала и запишете вх. №.");
    return sb.toString();
  }

  private static String nz(String value) {
    return value == null || value.isBlank() ? "—" : value;
  }

  /** Състоянието на ЕТЗ за прекратяването, за екрана. */
  public static final class Status {
    BgContractTermination termination;
    LocalDate dueDate;
    NapSubmission filed;
    NapSubmission draft;
    boolean overdue;
    String text;

    public BgContractTermination getTermination() {
      return termination;
    }

    public LocalDate getDueDate() {
      return dueDate;
    }

    public NapSubmission getFiled() {
      return filed;
    }

    public NapSubmission getDraft() {
      return draft;
    }

    public boolean isOverdue() {
      return overdue;
    }

    public String getText() {
      return text;
    }
  }
}

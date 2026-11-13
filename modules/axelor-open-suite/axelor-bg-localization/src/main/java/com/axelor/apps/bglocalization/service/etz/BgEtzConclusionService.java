package com.axelor.apps.bglocalization.service.etz;

import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.napsubmissions.common.submission.NapFilingLock;
import com.axelor.apps.napsubmissions.common.submission.NapFilingLockedException;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.services.declarations.common.ServiceDocumentType;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * ЕТЗ ЗА СКЛЮЧВАНЕ НА ТРУДОВ ДОГОВОР (тип 1) — един договор, един файл, едно подаване през портала.
 *
 * <p><b>Пътят е този от #331.</b> „Генерирай“ прави подаване-чернова {@code DEC_ETZ62}, закачено за
 * договора ({@code relatedModel} + {@code relatedRecordId}); човекът сваля файла, качва го в
 * портала на НАП със своя КЕП или ПИК и записва входящия номер и датата от портала — през {@code
 * ManualSubmissionService}, под {@code NapFilingGate}. Заключването и неизменността са правилата на
 * #334 ({@code NapSubmissionEvidence}) и {@link NapFilingLock} — тук второ правило няма.
 *
 * <p><b>⚠️ СРОКЪТ Е ТРИ ДНИ ОТ СКЛЮЧВАНЕТО (чл. 62 ал. 3 КТ)</b> — от датата на подписване, не от
 * началото на работа. След него вписване става само след влязло в сила предписание на инспекцията
 * по труда (ал. 4): затова закъснението е предупреждение, а не отказ — Nyxa не знае дали
 * предписанието го има. Отделно чл. 63 ал. 1–2 иска копие от вписването ПРЕДИ постъпването: при
 * начало на работа преди края на трите дни реалният краен ден е по-ранен.
 */
@Singleton
public class BgEtzConclusionService {

  /** чл. 62 ал. 3 КТ — „в тридневен срок от сключването“. */
  public static final int DAYS_TO_FILE = 3;

  public static final String REGENERATION_LOCKED = "Ново генериране е заключено";

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  protected final NapSubmissionRepository submissionRepo;
  protected final BgEtzExportService exportService;
  protected final AppBaseService appBaseService;

  @Inject
  public BgEtzConclusionService(
      NapSubmissionRepository submissionRepo,
      BgEtzExportService exportService,
      AppBaseService appBaseService) {
    this.submissionRepo = submissionRepo;
    this.exportService = exportService;
    this.appBaseService = appBaseService;
  }

  /** Крайният ден по чл. 62 ал. 3 — или {@code null}, докато договорът няма дата на подписване. */
  public static LocalDate dueDate(EmploymentContract contract) {
    LocalDate signed = contract == null ? null : contract.getSignatureDate();
    return signed == null ? null : signed.plusDays(DAYS_TO_FILE);
  }

  /**
   * Подаванията за сключването на този договор, най-новото първо.
   *
   * <p>⚠️ Ключът е договорът + {@code DEC_ETZ62}. Прекратяването е също {@code DEC_ETZ62}; то
   * трябва да получи СВОЯ закачалка — иначе и {@link NapFilingLock}, и тази заявка биха го смесили
   * със сключването.
   */
  public List<NapSubmission> submissions(EmploymentContract contract) {
    return submissionRepo
        .all()
        .filter(
            "self.relatedModel = :model AND self.relatedRecordId = :id"
                + " AND self.serviceDocumentTypeSelect = :type")
        .bind("model", EmploymentContract.class.getName())
        .bind("id", contract.getId())
        .bind("type", ServiceDocumentType.DEC_ETZ62.name())
        .order("-id")
        .fetch();
  }

  /** Подаденото (по който и да е път) — или {@code null}. */
  public NapSubmission filed(EmploymentContract contract) {
    for (NapSubmission submission : submissions(contract)) {
      if (NapFilingLock.FILED_STATUSES.contains(submission.getSubmissionStatusSelect())) {
        return submission;
      }
    }
    return null;
  }

  /** Последната чернова — или {@code null}. */
  public NapSubmission latestDraft(EmploymentContract contract) {
    for (NapSubmission submission : submissions(contract)) {
      if (NapSubmissionRepository.STATUS_DRAFT.equals(submission.getSubmissionStatusSelect())) {
        return submission;
      }
    }
    return null;
  }

  /**
   * Файлът и подаването-чернова. Подадено ли е вече — отказ: един документ, едно подаване.
   *
   * @throws NapFilingLockedException при вече подадено сключване
   * @throws BgEtzExportService.BgEtzRefusalException при липсващ реквизит — назовава полето и
   *     екрана
   */
  public NapSubmission generate(EmploymentContract contract) {
    NapSubmission filed = filed(contract);
    if (filed != null) {
      throw new NapFilingLockedException(
          String.format(
              "%s: ЕТЗ за сключването вече е подадено — „%s“, вх. № %s. Един документ — едно"
                  + " подаване. Поправка на вписан запис е корекция (код 1), не нов файл.",
              REGENERATION_LOCKED, nz(filed.getName()), nz(filed.getNapIncomingNumber())));
    }
    return exportService.export(contract, BgEtzEventKind.CONCLUSION, contract.getSignatureDate());
  }

  /** Какво да покаже панелът „ЕТЗ“ на договора — изчислено, нищо не се записва. */
  public Status status(EmploymentContract contract) {
    Status status = new Status();
    status.dueDate = dueDate(contract);
    status.filed = filed(contract);
    status.draft = status.filed == null ? latestDraft(contract) : null;
    LocalDate today = appBaseService.getTodayDate(contract.getPayCompany());
    status.overdue =
        status.filed == null && status.dueDate != null && today.isAfter(status.dueDate);
    status.text = text(contract, status);
    return status;
  }

  protected String text(EmploymentContract contract, Status status) {
    StringBuilder sb = new StringBuilder();
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
    if (status.dueDate == null) {
      return "Срокът тече от датата на подписване — попълнете „Дата на подписване“.";
    }
    sb.append("Подай до ")
        .append(DAY.format(status.dueDate))
        .append(" — 3 дни от сключването (чл. 62 ал. 3 КТ).");
    LocalDate start = contract.getStartDate();
    if (start != null && !start.isAfter(status.dueDate)) {
      sb.append(" Постъпва на ")
          .append(DAY.format(start))
          .append(": копие от вписването се дава ПРЕДИ постъпването (чл. 63 ал. 1–2 КТ).");
    }
    sb.append(
        status.draft == null
            ? " Файлът още не е генериран."
            : " Чернова: „"
                + nz(status.draft.getName())
                + "“ — свалете файла, подайте го в портала и запишете вх. №.");
    return sb.toString();
  }

  /** Предупреждението при изтекъл срок — чл. 62 ал. 4 КТ. */
  public static String overdueWarning(LocalDate dueDate) {
    return "⚠️ Срокът по чл. 62 ал. 3 КТ изтече на "
        + DAY.format(dueDate)
        + ". След него вписване се извършва само след влязло в сила задължително предписание на"
        + " инспекцията по труда (чл. 62 ал. 4 КТ).";
  }

  private static String nz(String value) {
    return value == null || value.isBlank() ? "—" : value;
  }

  /** Състоянието на ЕТЗ за сключването, за екрана. */
  public static final class Status {
    LocalDate dueDate;
    NapSubmission filed;
    NapSubmission draft;
    boolean overdue;
    String text;

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

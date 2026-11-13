package com.axelor.apps.napsubmissions.common.submission;

import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.services.declarations.common.ServiceDocumentType;
import com.axelor.i18n.I18n;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.List;
import java.util.Set;

/**
 * ЕДИН ДОКУМЕНТ — ЕДНО ПОДАВАНЕ (решение 17–18.10.2026): между РЕДОВЕТЕ на един документ.
 *
 * <p>Всеки документ има два пътя: ВИНАГИ ръчният (файлът се изтегля, човек го качва в портала със
 * своя КЕП и въвежда тук входящия номер) и — само където API-то на НАП го позволява — подаване по
 * API. За Д1/Д6 всяко „Закачи“ ражда нов ред; без този пазач два реда за една декларация щяха да
 * тръгнат по двата пътя. Затова: друг ред за същия документ ({@code relatedModel} + {@code
 * relatedRecordId}), който вече е подаден, заключва и ръчния път, и API-то.
 *
 * <p><b>Самият ред не е тук.</b> Подаденият ред е доказателство и какво от него още може да се пише
 * решава правилото на #334 ({@code NapSubmissionEvidence}); действията го питат преди записа през
 * {@link NapFilingEvidenceCheck}, без второ правило.
 *
 * <p><b>Какво НЕ заключва:</b> отхвърлено от органа, техническа грешка без номер от НАП и отменена
 * отметка ({@code withdrawn}) — това не са подавания, а опити. Нова версия на подаден документ е
 * въпрос на самия документ (коригираща Д1; оттеглена отметка при чл. 73(6) и Прил. 9).
 */
@Singleton
public class NapFilingLock {

  /** Състоянията, в които документът е ПОДАДЕН (или може да е, при неясен изход). */
  public static final List<String> FILED_STATUSES =
      List.of(
          NapSubmissionRepository.STATUS_SUBMITTING,
          NapSubmissionRepository.STATUS_SUBMITTED,
          NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN,
          NapSubmissionRepository.STATUS_ACCEPTED,
          NapSubmissionRepository.STATUS_CORRECTION_REQUIRED);

  public static final String MANUAL_LOCKED = "Manual recording is locked";
  public static final String API_LOCKED = "API submission is locked";
  private static final String ONE_FILING = ": one document — one submission.";

  /**
   * ЕТЗ се подава САМО през портала (решение 02.11.2026). API-то на НАП иска детачен PKCS#7 със
   * сертификата на подаващия, а в Nyxa няма КЕП; порталът приема и ПИК (НВРЗ чл. 9 ал. 2). Кодът на
   * API пътя остава — отказва се самият канал, за всеки вход към него.
   */
  public static final Set<String> MANUAL_ONLY =
      Set.of(ServiceDocumentType.DEC_ETZ62.name(), ServiceDocumentType.DEC_ETZ123.name());

  public static final String MANUAL_ONLY_REFUSAL =
      "Submitting from Nyxa is without a QES — download the file and upload it to the NAP portal"
          + " (QES or PIC)";

  protected final NapSubmissionRepository submissionRepo;

  @Inject
  public NapFilingLock(NapSubmissionRepository submissionRepo) {
    this.submissionRepo = submissionRepo;
  }

  /** Преди ръчното отбелязване „подадено в портала“. */
  public void requireManualAllowed(NapSubmission submission) {
    refuseFiledSibling(submission, MANUAL_LOCKED);
  }

  /** Преди подаване по API (общият канал и Declarations API). */
  public void requireApiAllowed(NapSubmission submission) {
    refuseManualOnly(submission);
    refuseFiledSibling(submission, API_LOCKED);
  }

  /**
   * Видът се подава само през портала — каналът API отказва, преди подпис, проверка и мрежа.
   * Статичен, за да го пита и „Подпиши и изпрати“ преди проверката за заключен избор.
   */
  public static void refuseManualOnly(NapSubmission submission) {
    String type = submission == null ? null : submission.getServiceDocumentTypeSelect();
    if (type != null && MANUAL_ONLY.contains(type)) {
      throw new NapFilingLockedException(I18n.get(MANUAL_ONLY_REFUSAL));
    }
  }

  protected void refuseFiledSibling(NapSubmission submission, String lock) {
    NapSubmission other = filedSibling(submission);
    if (other == null) {
      return;
    }
    boolean api = NapSubmissionRepository.CHANNEL_API.equals(other.getChannelSelect());
    throw new NapFilingLockedException(
        String.format(
            I18n.get(
                "There is already a submission “%s” for the same document (%s, incoming no. %s)."
                    + " %s%s"),
            nullSafe(other.getName()),
            api ? I18n.get("via the API") : I18n.get("manually through the portal"),
            nullSafe(api ? other.getEntryNumber() : other.getNapIncomingNumber()),
            I18n.get(lock),
            I18n.get(ONE_FILING)));
  }

  /** Друг ред за същия документ, който вече е подаден (по който и да е път). */
  protected NapSubmission filedSibling(NapSubmission submission) {
    if (isBlank(submission.getRelatedModel())
        || submission.getRelatedRecordId() == null
        || submission.getRelatedRecordId() == 0L) {
      return null;
    }
    return submissionRepo
        .all()
        .filter(
            "self.relatedModel = :model AND self.relatedRecordId = :record"
                + " AND self.id != :self AND self.submissionStatusSelect IN :statuses")
        .bind("model", submission.getRelatedModel())
        .bind("record", submission.getRelatedRecordId())
        .bind("self", submission.getId() == null ? 0L : submission.getId())
        .bind("statuses", FILED_STATUSES)
        .order("id")
        .fetchOne();
  }

  protected static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  protected static String nullSafe(String value) {
    return value == null || value.isBlank() ? "—" : value;
  }
}

package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgD1Declaration;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.napsubmissions.common.audit.NapAuditService;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.repo.NapAuditEventRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.i18n.I18n;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

/**
 * „Избери генерираните Д1/Д6 → създай подаване-чернова → ЗАКЛЮЧИ“ — операторският път на третото
 * меню.
 *
 * <p><b>⚠️ НЕ ПРОИЗВЕЖДА БАЙТОВЕ И НЕ ПОДАВА.</b> Байтовете идват от вече ГЕНЕРИРАНИТЕ декларации
 * (през {@link BgD1SubmissionService#attach}, който ги прави непроменими {@link NapArtifact});
 * подписът и подаването остават човешки стъпки в портала на НАП със собствен КЕП. Тази услуга
 * добавя точно две неща над закачането: СЕРВЪРНА проверка на избора (състояние, период, историческо
 * внасяне, едно дружество) и ЗАКЛЮЧВАНЕ на подаването в {@code STATUS_LOCKED}, от който нататък
 * идентичността, периодът и файловете са замразени от {@code RegulatoryImmutabilityGuard} — не само
 * readonly в изгледа.
 *
 * <p><b>⚠️ ЗАКЛЮЧВАНЕТО Е ПРЕХОД draft → locked В ЕДНА ТРАНЗАКЦИЯ.</b> Пазачът чете ПРЕДИШНОТО
 * състояние (draft, незаключено) и допуска прехода; оттам всеки следващ запис вижда вече заключено
 * подаване и отказва промяна на избора/периода/файловете.
 */
@Singleton
public class BgD1D6FilingService {

  protected final BgD1SubmissionService submissionService;
  protected final NapSubmissionRepository submissionRepo;
  protected final NapAuditService auditService;

  @Inject
  public BgD1D6FilingService(
      BgD1SubmissionService submissionService,
      NapSubmissionRepository submissionRepo,
      NapAuditService auditService) {
    this.submissionService = submissionService;
    this.submissionRepo = submissionRepo;
    this.auditService = auditService;
  }

  /**
   * Създава подаване-чернова за избраните декларации и веднага го ЗАКЛЮЧВА.
   *
   * @param d1 избраната Декларация обр. 1 (винаги задължителна за допустимите комбинации)
   * @param d6 избраната Декларация обр. 6, или {@code null} за подаване само с Д1
   * @param reportingYear година от екрана (0/negative = без проверка на периода)
   * @param fromMonth „от месец“ (1–12, 0 = без проверка)
   * @param toMonth „до месец“ (1–12, 0 = без проверка)
   * @param sourceFlag поле 53 на Д1 — ЕИК на разработчика на софтуера
   * @throws IllegalStateException при невалиден избор (с текст, годен за екрана)
   */
  @Transactional(rollbackOn = Exception.class)
  public NapSubmission createAndLock(
      BgD1Declaration d1,
      BgD6Declaration d6,
      int reportingYear,
      int fromMonth,
      int toMonth,
      String sourceFlag)
      throws IOException {

    requireGeneratedD1(d1, reportingYear, fromMonth, toMonth);
    if (d6 != null) {
      requireGeneratedD6(d6, d1, reportingYear, fromMonth, toMonth);
    }

    NapSubmission submission = submissionService.attach(d1, d6, sourceFlag);

    auditService.logHuman(
        null,
        submission,
        NapAuditEventRepository.EVENT_ARTIFACT_ATTACHED,
        null,
        NapSubmissionRepository.STATUS_DRAFT,
        selectionDetails(submission));

    submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_LOCKED);
    submission = submissionRepo.save(submission);

    auditService.logHuman(
        null,
        submission,
        NapAuditEventRepository.EVENT_STATUS_CHANGE,
        NapSubmissionRepository.STATUS_DRAFT,
        NapSubmissionRepository.STATUS_LOCKED,
        "Заключено за подаване: изборът, периодът и файловете са замразени. Подписът и подаването"
            + " стават в портала на НАП със собствен КЕП; входящият номер се въвежда в „Ръчен"
            + " портал“.");

    return submission;
  }

  /** Декларация обр. 1 — задължителна, генерирана, нова (не историческа), в периода. */
  protected void requireGeneratedD1(
      BgD1Declaration d1, int reportingYear, int fromMonth, int toMonth) {
    if (d1 == null) {
      throw new IllegalStateException("No Form 1 declaration is selected.");
    }
    if (!"generated".equals(d1.getStatusSelect())) {
      throw new IllegalStateException(
          "The selected Form 1 declaration is not in the “Generated” status — only a generated"
              + " declaration carries file bytes to attach.");
    }
    if (Boolean.TRUE.equals(d1.getBgIsHistoricalImport())) {
      throw new IllegalStateException(
          "The selected Form 1 declaration is a historical import (migration) — it has already been"
              + " filed and does not go through this submission.");
    }
    requirePeriodInRange(
        "Form 1 declaration", d1.getBgDataPeriod(), reportingYear, fromMonth, toMonth);
  }

  /** Декларация обр. 6 — генерирана, нова, СЪЩОТО дружество и СЪЩИЯ месец като Д1. */
  protected void requireGeneratedD6(
      BgD6Declaration d6, BgD1Declaration d1, int reportingYear, int fromMonth, int toMonth) {
    if (!"generated".equals(d6.getStatusSelect())) {
      throw new IllegalStateException(
          "The selected Form 6 declaration is not in the “Generated” status.");
    }
    if (Boolean.TRUE.equals(d6.getBgIsHistoricalImport())) {
      throw new IllegalStateException(
          "The selected Form 6 declaration is a historical import (migration) — it does not go"
              + " through this submission.");
    }
    if (!sameCompany(d1.getCompany(), d6.getCompany())) {
      throw new IllegalStateException(
          "The Form 1 and Form 6 declarations are for DIFFERENT companies — one submission carries"
              + " one employer.");
    }
    if (!samePeriod(d1.getBgDataPeriod(), d6.getBgPeriod())) {
      throw new IllegalStateException(
          "The Form 1 and Form 6 declarations are for DIFFERENT months. One submission (DEC_1_6)"
              + " carries ONE month; select D1 and D6 for the same period.");
    }
    requirePeriodInRange("Form 6 declaration", d6.getBgPeriod(), reportingYear, fromMonth, toMonth);
  }

  /**
   * Периодът на декларацията да е в избрания прозорец (година + от–до месец). Непопълнена година
   * (0) означава „без проверка“ — прозорецът е само удобство за филтриране, не допълнителен отказ.
   */
  protected void requirePeriodInRange(
      String what, Period period, int reportingYear, int fromMonth, int toMonth) {
    if (reportingYear <= 0) {
      return;
    }
    LocalDate start = period == null ? null : period.getFromDate();
    if (start == null) {
      throw new IllegalStateException(
          String.format(
              I18n.get(
                  "The %s has no data period — it cannot be checked against the selected month."),
              I18n.get(what)));
    }
    if (start.getYear() != reportingYear) {
      throw new IllegalStateException(
          String.format(
              I18n.get("The %s is for %d, but the selected year is %d."),
              I18n.get(what),
              start.getYear(),
              reportingYear));
    }
    int month = start.getMonthValue();
    int lo = fromMonth <= 0 ? 1 : fromMonth;
    int hi = toMonth <= 0 ? 12 : toMonth;
    if (month < lo || month > hi) {
      throw new IllegalStateException(
          String.format(
              I18n.get("The %s is for month %02d, outside the selected window %02d–%02d."),
              I18n.get(what),
              month,
              lo,
              hi));
    }
  }

  /** Файловете на подаването с типа, името и SHA-256 — за одита. БЕЗ тайни и БЕЗ съдържание. */
  protected String selectionDetails(NapSubmission submission) {
    StringBuilder sb = new StringBuilder("Избрани и закачени файлове (точните байтове от Nyxa):");
    List<NapSubmissionFile> files = submission.getSubmissionFileList();
    if (files != null) {
      for (NapSubmissionFile file : files) {
        NapArtifact payload = file.getPayloadArtifact();
        sb.append("\n • ")
            .append(file.getFileDocumentTypeSelect())
            .append(" · ")
            .append(file.getSubmittedFileName());
        if (payload != null) {
          sb.append(" · SHA-256 ").append(payload.getSha256());
        }
      }
    }
    return sb.toString();
  }

  private static boolean sameCompany(Company a, Company b) {
    return a != null && b != null && a.getId() != null && a.getId().equals(b.getId());
  }

  private static boolean samePeriod(Period a, Period b) {
    return a != null && b != null && a.getId() != null && a.getId().equals(b.getId());
  }
}

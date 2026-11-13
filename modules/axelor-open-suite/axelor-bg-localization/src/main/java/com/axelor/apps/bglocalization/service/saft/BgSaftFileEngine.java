package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXsd;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.repo.SaftReportRepository;
import com.axelor.apps.napsubmissions.services.saft.SaftCheckResult;
import com.axelor.apps.napsubmissions.services.saft.SaftFileEngine;
import com.axelor.db.JPA;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.persistence.TypedQuery;

/**
 * ПРОВЕРКАТА И ГЕНЕРИРАНЕТО НА SAF-T ФАЙЛА ЗА ЕКРАНА (G6b; годишният и при поискване — T1) —
 * генераторът на G6a плюс правилата на екрана.
 *
 * <ul>
 *   <li><b>Винаги пълното генериране</b> ({@link BgSaftGenerationService#generate}): XSD-то е над
 *       самите байтове, тоест „Провери“ без байтове не би било истинска проверка. Байтовете се
 *       пазят само при „Генерирай“.
 *   <li><b>S.H.1 и XSD-то — по датата на генериране</b>, от една таблица ({@link
 *       BgSaftXsd#VERSIONS}): 1.0.2 до 31.10.2026, 1.0.3 от 01.11.2026.
 *   <li><b>DAYBOOK → ГРЕШКА</b> ({@link BgSaftDaybookCheck}), в този слой — генераторът не се пипа.
 *       Само за месечния: дневникът е само в него.
 *   <li><b>Какво трябва на всеки вид</b> ({@link #scope}): месечният — месецът; годишният —
 *       годината; при поискване — двете дати и номерът на искането (ключът на заключването при
 *       подаване, {@code SaftPeriodLock}).
 * </ul>
 */
@Singleton
public class BgSaftFileEngine extends SaftFileEngine {

  public static final String NO_PERIOD = "SAFT_G6B_NO_PERIOD";
  public static final String NO_YEAR = "SAFT_T1_NO_YEAR";
  public static final String NO_DATES = "SAFT_T1_NO_DATES";
  public static final String DATES_REVERSED = "SAFT_T1_DATES_REVERSED";
  public static final String NO_REQUEST = "SAFT_T1_NO_REQUEST";
  public static final String UNKNOWN_KIND = "SAFT_T1_UNKNOWN_KIND";

  protected final BgSaftGenerationService generation;

  @Inject
  public BgSaftFileEngine(BgSaftGenerationService generation) {
    this.generation = generation;
  }

  @Override
  public SaftCheckResult run(SaftReport report, LocalDate today, LocalDateTime lastGenerated) {
    String version = BgSaftXsd.forDate(today).getAuditFileVersion();
    List<SaftCheckResult.Finding> scope = scope(report);
    if (!scope.isEmpty()) {
      return SaftCheckResult.findingsOnly(scope, version);
    }
    BgSaftGenerationService.Generated generated = generation.generate(report, today, lastGenerated);
    return result(generated, monthly(report) ? daybookMoves(report) : List.of(), today);
  }

  /** Отчетът има ли това, за което е файлът: месеца, годината или датите и искането. */
  static List<SaftCheckResult.Finding> scope(SaftReport report) {
    List<SaftCheckResult.Finding> findings = new ArrayList<>();
    if (report.getCompany() == null) {
      findings.add(SaftCheckResult.Finding.error(NO_PERIOD, "Отчетът е без дружество."));
      return findings;
    }
    String type = report.getReportTypeSelect();
    if (SaftReportRepository.REPORT_TYPE_MONTHLY.equals(type)) {
      if (report.getPeriod() == null) {
        findings.add(
            SaftCheckResult.Finding.error(
                NO_PERIOD, "Изберете месеца — периодът на отчета е празен."));
      }
    } else if (SaftReportRepository.REPORT_TYPE_ANNUAL.equals(type)) {
      // Празната година идва като 0 (генерираният getter на Axelor).
      int year = report.getReportYear();
      if (year < 1000 || year > 9999) {
        findings.add(
            SaftCheckResult.Finding.error(
                NO_YEAR, "Въведете календарната година на годишния файл (четири цифри)."));
      }
    } else if (SaftReportRepository.REPORT_TYPE_ON_DEMAND.equals(type)) {
      if (report.getFromDate() == null || report.getToDate() == null) {
        findings.add(
            SaftCheckResult.Finding.error(
                NO_DATES, "Въведете началото и края на поискания период (от искането на НАП)."));
      } else if (report.getToDate().isBefore(report.getFromDate())) {
        findings.add(
            SaftCheckResult.Finding.error(
                DATES_REVERSED, "Краят на поискания период е преди началото му."));
      }
      if (report.getNapRequestNumber() == null || report.getNapRequestNumber().isBlank()) {
        findings.add(
            SaftCheckResult.Finding.error(
                NO_REQUEST,
                "Въведете номера на искането от НАП — по него се пази подаденото (едно искане"
                    + " може да е на два файла за два подпериода, QA II.4.5)."));
      }
    } else {
      findings.add(
          SaftCheckResult.Finding.error(
              UNKNOWN_KIND, "Непознат вид на SAF-T отчета: „" + type + "“."));
    }
    return findings;
  }

  static boolean monthly(SaftReport report) {
    return SaftReportRepository.REPORT_TYPE_MONTHLY.equals(report.getReportTypeSelect());
  }

  /**
   * Чистата част: находките на генератора + DAYBOOK; файлът — само ако генераторът го е произвел и
   * DAYBOOK статии няма. S.H.1 на готовия файл се чете от самите байтове.
   */
  static SaftCheckResult result(
      BgSaftGenerationService.Generated generated,
      Collection<BgSaftDaybookCheck.Row> daybook,
      LocalDate today) {
    List<SaftCheckResult.Finding> findings = new ArrayList<>();
    for (BgDeclarationFileFinding f :
        BgSaftDaybookCheck.combine(generated.getFindings(), daybook)) {
      findings.add(
          new SaftCheckResult.Finding(
              f.isBlocking() ? SaftCheckResult.SEVERITY_ERROR : SaftCheckResult.SEVERITY_WARNING,
              f.getCode(),
              f.getMessage(),
              f.getTargetModel(),
              f.getTargetId()));
    }
    if (generated.isGenerated() && daybook.isEmpty()) {
      return SaftCheckResult.file(
          findings,
          generated.getFileName(),
          generated.getBytes(),
          generated.getSha256(),
          BgSaftSubmissionVersion.auditFileVersion(generated.getBytes()));
    }
    return SaftCheckResult.findingsOnly(findings, BgSaftXsd.forDate(today).getAuditFileVersion());
  }

  /** Статиите в DAYBOOK с дата в месеца на отчета. */
  protected List<BgSaftDaybookCheck.Row> daybookMoves(SaftReport report) {
    TypedQuery<Object[]> query =
        JPA.em().createQuery(BgSaftDaybookCheck.DAYBOOK_MOVES, Object[].class);
    query.setParameter("companyId", report.getCompany().getId());
    query.setParameter("fromDate", report.getPeriod().getFromDate());
    query.setParameter("toDate", report.getPeriod().getToDate());
    List<BgSaftDaybookCheck.Row> rows = new ArrayList<>();
    for (Object[] r : query.getResultList()) {
      rows.add(new BgSaftDaybookCheck.Row(((Number) r[0]).longValue(), (String) r[1]));
    }
    return rows;
  }
}

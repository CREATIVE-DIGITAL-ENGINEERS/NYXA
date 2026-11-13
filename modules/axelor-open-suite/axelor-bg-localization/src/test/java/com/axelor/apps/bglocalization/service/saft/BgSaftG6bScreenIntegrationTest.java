package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.JournalType;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXsd;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAuditFile;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.napsubmissions.common.immutability.RegulatoryImmutabilityException;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapProcessingResult;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.SaftValidationIssue;
import com.axelor.apps.napsubmissions.db.repo.NapProcessingResultRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.db.repo.SaftReportRepository;
import com.axelor.apps.napsubmissions.services.saft.SaftPeriodLock;
import com.axelor.apps.napsubmissions.services.saft.SaftReportWorkflowService;
import com.axelor.apps.napsubmissions.web.SaftReportController;
import com.axelor.db.JPA;
import com.axelor.db.JpaSecurity;
import com.axelor.db.JpaSupport;
import com.axelor.db.Model;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.rpc.Request;
import com.axelor.rpc.Resource;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.util.Modules;
import com.google.inject.util.Types;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * МЕСЕЧНИЯТ SAF-T ФАЙЛ ОТ ЕКРАНА (G6b) — по истинските контролери и услуги, без тестова транзакция,
 * с ролите от каталога ({@link BgRoleLogin}).
 *
 * <p><b>Генераторът е истинският, входът — не.</b> HSQLDB на класа няма сметкоплан, кодове по НАП и
 * статии за цял месец, затова {@link FixtureGeneration} подава на истинското {@code generate()}
 * чистия април на {@link BgSaftComposeTest} (с датата на генериране от екрана). Всичко след това е
 * производственото: XSD-то над байтовете, DAYBOOK от базата, NapArtifact, подаването и пазачите на
 * подаванията и на G6b.
 *
 * <p>Данните са измислени (без реални ЕИК, имена, адреси).
 *
 * <p>⚠️ {@code @Tag("integration")}: пуска се през {@code integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgSaftG6bScreenIntegrationTest.Wiring.class)
@Tag("integration")
class BgSaftG6bScreenIntegrationTest extends JpaSupport {

  /** Производственото опроводяване, с подменен вход на генератора. */
  public static class Wiring extends AbstractModule {
    @Override
    protected void configure() {
      install(
          Modules.override(new BgLocalizationProductionWiringTestModule())
              .with(
                  binder ->
                      binder.bind(BgSaftGenerationService.class).to(FixtureGeneration.class)));
    }
  }

  /**
   * Истинското {@code generate()} над чистия април; {@link #broken} дава ЕИК на клон (ГРЕШКА).
   * Годишният и при поискване (T1): истинската основа (Header, име, сроковете) от вида, годината и
   * датите на ОТЧЕТА, над минималните секции на F0 — съдържанието е на пистите A и S.
   */
  public static class FixtureGeneration extends BgSaftGenerationService {
    static volatile boolean broken;

    @Inject
    public FixtureGeneration(
        BgSaftAuditFileService auditFileService,
        BgSaftAnnualOnDemandService annualOnDemandService) {
      super(auditFileService, annualOnDemandService);
    }

    @Override
    public Generated generate(SaftReport report, LocalDate createdOn, LocalDateTime last) {
      if (!SaftReportRepository.REPORT_TYPE_MONTHLY.equals(report.getReportTypeSelect())) {
        BgSaftAuditFileService.Inputs clean = BgSaftComposeTest.clean();
        BgSaftAnnualOnDemandService.Inputs in = new BgSaftAnnualOnDemandService.Inputs();
        in.company = clean.company;
        in.app = clean.app;
        in.ibanRefusal = clean.ibanRefusal;
        in.selection = BgSaftSelection.of(report);
        in.createdOn = createdOn;
        if (SaftReportRepository.REPORT_TYPE_ANNUAL.equals(report.getReportTypeSelect())) {
          SaftAuditFile f = BgSaftFixtures.minimalAnnual();
          in.content =
              BgSaftAnnualOnDemandService.Content.annual(
                  f.getMasterFilesAnnual(), f.getAssetTransactions(), List.of());
        } else {
          SaftAuditFile f = BgSaftFixtures.minimalOnDemand();
          in.content =
              BgSaftAnnualOnDemandService.Content.onDemand(
                  f.getMasterFilesOnDemand(), f.getMovementOfGoods(), List.of());
        }
        return generate(BgSaftAnnualOnDemandService.compose(in));
      }
      BgSaftAuditFileService.Inputs in = BgSaftComposeTest.clean();
      in.createdOn = createdOn;
      if (broken) {
        in.company.getPartner().setRegistrationCode("1234567890001");
      }
      return generate(BgSaftAuditFileService.compose(in));
    }
  }

  private static final String NOT_ALLOWED = "You are not allowed to ";
  private static final String NAP_WRITE = "accounting.nap.write";
  private static final String NAP_READ = "accounting.nap.read";
  private static final LocalDate OCT_31 = LocalDate.of(2026, 10, 31);
  private static final LocalDate NOV_01 = LocalDate.of(2026, 11, 1);
  private static final LocalDate PORTAL_DATE = LocalDate.of(2026, 10, 15);

  private Long companyId;
  private Long otherCompanyId;
  private Long reportId;
  private Long otherReportId;
  private Long periodId;

  @BeforeEach
  void scene() {
    FixtureGeneration.broken = false;
    String tag = String.valueOf(System.nanoTime());
    JPA.runInTransaction(
        () -> {
          Company company = company("SAF-T А " + tag, "SA" + tag);
          companyId = company.getId();
          Company other = company("SAF-T Б " + tag, "SB" + tag);
          otherCompanyId = other.getId();
          Period period = period(company, "A" + tag);
          periodId = period.getId();
          reportId = report(company, period, "SAFT-А-" + tag).getId();
          otherReportId = report(other, period(other, "B" + tag), "SAFT-Б-" + tag).getId();
        });
  }

  @AfterEach
  void logout() {
    FixtureGeneration.broken = false;
    BgRoleLogin.logout();
  }

  // ═══ ПРОВЕРИ И ГЕНЕРИРАЙ ═════════════════════════════════════════════════

  @Test
  @DisplayName(
      "accounting.nap.write: „Провери“ → READY с находките, файл няма; „Генерирай“ → неизменен"
          + " файл на дружеството, S.H.1 по днешната дата")
  void checkThenGenerate() {
    BgRoleLogin.login(company(), NAP_WRITE);
    String checked = press(reportId, null, controller()::validate);
    assertFalse(checked.contains("error"), checked);
    SaftReport afterCheck = report(reportId);
    assertEquals(SaftReportRepository.LIFECYCLE_READY, afterCheck.getLifecycleStatusSelect());
    assertEquals(SaftReportRepository.READINESS_READY, afterCheck.getReadinessStatusSelect());
    assertNull(afterCheck.getGeneratedArtifact(), "проверката не пише файл");
    assertEquals(0, artifacts(reportId), "проверката не пише файл");
    assertTrue(issues(reportId).size() > 0, "предупрежденията на чистия април са записани");
    assertTrue(issues(reportId).stream().noneMatch(i -> Boolean.TRUE.equals(i.getBlocking())));

    String generated = press(reportId, null, controller()::generate);
    assertFalse(generated.contains("error"), generated);
    SaftReport report = report(reportId);
    assertEquals(SaftReportRepository.LIFECYCLE_GENERATED, report.getLifecycleStatusSelect());
    NapArtifact artifact = artifact(report.getGeneratedArtifact().getId());
    assertEquals(companyId, artifact.getCompany().getId(), "файлът е на дружеството");
    assertEquals(reportId, artifact.getSaftReport().getId());
    assertEquals("123456789_04_2026.xml", artifact.getName(), "името по Прил. 3 т. I.2");
    byte[] bytes = bytes(artifact);
    assertEquals(
        BgSaftGenerationService.sha256(bytes), artifact.getSha256(), "SHA-256 на байтовете");
    String today = BgSaftXsd.forDate(todayOf()).getAuditFileVersion();
    assertEquals(today, BgSaftSubmissionVersion.auditFileVersion(bytes), "S.H.1 по днешната дата");
    assertEquals(today, report.getSchemaVersion());
  }

  @Test
  @DisplayName("Версията по дата: 31.10 → 1.0.2, 01.11 → 1.0.3; файл от 31.10 не се сваля на 01.11")
  void versionByDate() throws Exception {
    BgRoleLogin.login(company(), NAP_WRITE);
    SaftReportWorkflowService service = Beans.get(SaftReportWorkflowService.class);

    service.generate(managed(reportId), OCT_31);
    NapArtifact october = artifact(report(reportId).getGeneratedArtifact().getId());
    assertEquals("1.0.2", BgSaftSubmissionVersion.auditFileVersion(bytes(october)));
    assertEquals("1.0.2", report(reportId).getSchemaVersion());
    assertNotNull(service.fileForPortal(managed(reportId), OCT_31), "същия ден — сваля се");
    AxelorException stale =
        assertThrows(AxelorException.class, () -> service.fileForPortal(managed(reportId), NOV_01));
    assertTrue(stale.getMessage().contains("1.0.3"), stale.getMessage());

    service.generate(managed(reportId), NOV_01);
    NapArtifact november = artifact(report(reportId).getGeneratedArtifact().getId());
    assertEquals("1.0.3", BgSaftSubmissionVersion.auditFileVersion(bytes(november)));
    assertEquals("1.0.3", report(reportId).getSchemaVersion());
    assertEquals(november.getId(), service.fileForPortal(managed(reportId), NOV_01).getId());
  }

  @Test
  @DisplayName(
      "ГРЕШКА → „Генерирай“ отказва: файл няма, отчетът е FAILED, грешката е записана; „Провери“"
          + " не дава READY")
  void errorRefusesGeneration() {
    FixtureGeneration.broken = true;
    BgRoleLogin.login(company(), NAP_WRITE);
    String text = press(reportId, null, controller()::generate);
    assertTrue(text.contains("грешки") && text.contains("Файл не е генериран"), text);
    SaftReport report = report(reportId);
    assertEquals(SaftReportRepository.LIFECYCLE_FAILED, report.getLifecycleStatusSelect());
    assertNull(report.getGeneratedArtifact());
    assertEquals(0, artifacts(reportId));
    assertTrue(
        issues(reportId).stream()
            .anyMatch(
                i ->
                    Boolean.TRUE.equals(i.getBlocking())
                        && "SAFT_FILE_NAME_BRANCH_EIK".equals(i.getSectionFieldId())),
        "грешката е записана");

    press(reportId, null, controller()::validate);
    assertEquals(
        SaftReportRepository.READINESS_INCOMPLETE, report(reportId).getReadinessStatusSelect());
    assertEquals(
        SaftReportRepository.LIFECYCLE_FAILED, report(reportId).getLifecycleStatusSelect());
  }

  @Test
  @DisplayName("Статия в DAYBOOK в месеца → ГРЕШКА с линк към статията; файл няма")
  void daybookMoveIsAnError() {
    Long moveId = daybookMove(companyId, LocalDate.of(2026, 10, 10), "ДН-" + reportId);
    BgRoleLogin.login(company(), NAP_WRITE);

    press(reportId, null, controller()::validate);
    assertEquals(
        SaftReportRepository.LIFECYCLE_FAILED, report(reportId).getLifecycleStatusSelect());
    SaftValidationIssue issue =
        issues(reportId).stream()
            .filter(i -> BgSaftDaybookCheck.CODE.equals(i.getSectionFieldId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("няма ГРЕШКА за DAYBOOK: " + issues(reportId)));
    assertTrue(issue.getBlocking());
    assertEquals(BgSaftRef.MOVE, issue.getTargetModel(), "линк към статията");
    assertEquals(moveId, issue.getTargetRecordId());

    String text = press(reportId, null, controller()::generate);
    assertTrue(text.contains("Файл не е генериран"), text);
    assertEquals(0, artifacts(reportId));
  }

  @Test
  @DisplayName("DAYBOOK на друго дружество или в друг месец не спира файла")
  void foreignDaybookDoesNotCount() {
    daybookMove(otherCompanyId, LocalDate.of(2026, 10, 10), "ДН-Б-" + reportId);
    daybookMove(companyId, LocalDate.of(2026, 11, 3), "ДН-11-" + reportId);
    BgRoleLogin.login(company(), NAP_WRITE);
    press(reportId, null, controller()::generate);
    assertEquals(
        SaftReportRepository.LIFECYCLE_GENERATED, report(reportId).getLifecycleStatusSelect());
  }

  @Test
  @DisplayName("Ново генериране = нов артефакт; старият остава същият, не се трие и не се подменя")
  void regenerationKeepsTheOldFile() {
    BgRoleLogin.login(company(), NAP_WRITE);
    press(reportId, null, controller()::generate);
    NapArtifact first = artifact(report(reportId).getGeneratedArtifact().getId());
    String firstSha = first.getSha256();
    Long firstFile = first.getMetaFile().getId();

    press(reportId, null, controller()::generate);
    NapArtifact second = artifact(report(reportId).getGeneratedArtifact().getId());
    assertTrue(second.getId() > first.getId(), "нов артефакт");
    assertEquals(2, artifacts(reportId), "старият остава");
    NapArtifact again = artifact(first.getId());
    assertEquals(firstSha, again.getSha256());
    assertEquals(firstFile, again.getMetaFile().getId());

    RuntimeException refused =
        refusedWrite(() -> JPA.em().find(NapArtifact.class, first.getId()).setSha256("0"));
    assertNotNull(refused, "SHA-256 на генерирания файл не се подменя");
    assertEquals(firstSha, artifact(first.getId()).getSha256());
  }

  // ═══ ПОДАДЕН → ПРИЕТ / ОТХВЪРЛЕН ═════════════════════════════════════════

  @Test
  @DisplayName(
      "Генериран → Подаден → Отхвърлен → нов опит → Подаден → Приет; приетият заключва месеца")
  void theFilingStates() {
    BgRoleLogin.login(company(), NAP_WRITE);
    press(reportId, null, controller()::generate);
    Long firstFile = report(reportId).getGeneratedArtifact().getId();

    assertFalse(filing("30TST000000101").contains("error"));
    NapSubmission first = onlySubmission(NapSubmissionRepository.STATUS_SUBMITTED);
    assertEquals(NapSubmissionRepository.CHANNEL_MANUAL_PORTAL, first.getChannelSelect());
    assertEquals("30TST000000101", first.getNapIncomingNumber());
    assertEquals(PORTAL_DATE, first.getSubmittedOn().toLocalDate(), "датата от портала");
    assertEquals(firstFile, fileOf(first.getId()), "подаден е генерираният файл");
    assertEquals(periodId, first.getPeriod().getId());

    // Чака изход: нов файл и ново подаване — отказ.
    String pending = press(reportId, null, controller()::generate);
    assertTrue(pending.contains("чака изход"), pending);
    assertEquals(1, artifacts(reportId), "нищо не е генерирано");
    assertTrue(filing("30TST000000199").contains("чака изход"));
    assertEquals(1, submissions(reportId));

    // Отхвърлен: иска причина; после нов опит.
    String noReason = press(reportId, null, controller()::recordRejected);
    assertTrue(noReason.contains("причината"), noReason);
    Map<String, Object> reason = new HashMap<>();
    reason.put("rejectionReasonInput", "IBANNumber S.BA.1");
    press(reportId, reason, controller()::recordRejected);
    assertEquals(
        NapSubmissionRepository.STATUS_REJECTED,
        submission(first.getId()).getSubmissionStatusSelect());

    press(reportId, null, controller()::generate);
    Long secondFile = report(reportId).getGeneratedArtifact().getId();
    assertFalse(secondFile.equals(firstFile), "нов файл след отхвърлянето");
    assertFalse(filing("30TST000000102").contains("error"));
    assertEquals(2, submissions(reportId));
    NapSubmission second = latestSubmission(reportId);
    assertEquals(2, second.getAttemptNumber());
    assertEquals(secondFile, fileOf(second.getId()));

    // Приет: заключва месеца.
    press(reportId, null, controller()::recordAccepted);
    assertEquals(
        NapSubmissionRepository.STATUS_ACCEPTED,
        submission(second.getId()).getSubmissionStatusSelect());
    String locked = press(reportId, null, controller()::generate);
    assertTrue(locked.contains("приет от НАП") && locked.contains("30TST000000102"), locked);
    assertTrue(filing("30TST000000103").contains("приет от НАП"));
    Map<String, Object> correction = new HashMap<>();
    correction.put("correctionReasonInput", "поправка");
    String corrected = press(reportId, correction, controller()::createCorrection);
    assertTrue(
        corrected.contains("Коригиращ файл") && corrected.contains("приет от НАП"), corrected);
    assertEquals(2, submissions(reportId));
    assertEquals(2, artifacts(reportId));
  }

  @Test
  @DisplayName(
      "Генериран файл, после „Провери“ с ГРЕШКА → FAILED: старият файл не се подава, докато не се"
          + " генерира наново")
  void aFailedRecheckBlocksTheFiling() {
    BgRoleLogin.login(company(), NAP_WRITE);
    press(reportId, null, controller()::generate);
    assertEquals(
        SaftReportRepository.LIFECYCLE_GENERATED, report(reportId).getLifecycleStatusSelect());

    FixtureGeneration.broken = true;
    press(reportId, null, controller()::validate);
    assertEquals(
        SaftReportRepository.LIFECYCLE_FAILED, report(reportId).getLifecycleStatusSelect());
    assertNotNull(report(reportId).getGeneratedArtifact(), "старият файл остава в историята");

    String text = filing("30TST000000401");
    assertTrue(text.contains("Only a generated SAF-T file can be submitted"), text);
    assertEquals(0, submissions(reportId), "нищо не е подадено");
  }

  @Test
  @DisplayName("Коригиращ файл за неприет месец — отказ: „генерирайте наново“")
  void correctionOfAnOpenMonthIsRefused() {
    BgRoleLogin.login(company(), NAP_WRITE);
    String text = press(reportId, null, controller()::createCorrection);
    assertTrue(text.contains("генерирайте файла наново"), text);
    assertEquals(SaftReportRepository.LIFECYCLE_DRAFT, report(reportId).getLifecycleStatusSelect());
  }

  @Test
  @DisplayName(
      "Протоколът на НАП (errsaft): грешките стават находки с връзка към опита; следващата"
          + " проверка не ги трие")
  void theProtocolIsReadIntoFindings() {
    BgRoleLogin.login(company(), NAP_WRITE);
    press(reportId, null, controller()::generate);
    filing("30TST000000201");
    Long submissionId = latestSubmission(reportId).getId();

    Long protocol =
        upload(
            "30TST000000201_result.xml",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><errsaft>"
                + "<error><err_number>1</err_number><err_code>IBANNumber S.BA.1</err_code>"
                + "<err_descr>Невалиден IBAN</err_descr></error>"
                + "<error><err_number>2</err_number><err_code>CustomerID MF.C.3</err_code>"
                + "<err_descr>Некоректен идентификатор</err_descr></error></errsaft>");
    Map<String, Object> extra = new HashMap<>();
    extra.put("protocolFile", Map.of("id", protocol));
    String text = press(reportId, extra, controller()::attachProtocol);
    assertTrue(text.contains("2 грешки"), text);
    assertEquals(2, protocolIssues(submissionId));
    NapProcessingResult result =
        fresh(
            em ->
                em.createQuery(
                        "SELECT r FROM NapProcessingResult r WHERE r.submission.id = :s",
                        NapProcessingResult.class)
                    .setParameter("s", submissionId)
                    .getSingleResult());
    assertEquals(NapProcessingResultRepository.OUTCOME_REJECTED, result.getDerivedOutcomeSelect());
    assertEquals(
        NapSubmissionRepository.STATUS_SUBMITTED,
        submission(submissionId).getSubmissionStatusSelect(),
        "статусът го отбелязва човекът");

    press(reportId, null, controller()::validate);
    assertEquals(2, protocolIssues(submissionId), "проверката не трие протокола");
  }

  // ═══ РОЛИ ═══════════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "accounting.nap.read: проверка, генериране, подаване, изход — шумен отказ, нищо не е"
          + " записано; „Свали за портала“ минава")
  void readRoleOnlyViewsAndDownloads() {
    BgRoleLogin.login(company(), NAP_WRITE);
    press(reportId, null, controller()::generate);
    filing("30TST000000301");
    BgRoleLogin.logout();

    BgRoleLogin.login(company(), NAP_READ);
    SaftReportController c = controller();
    for (Object[] action :
        new Object[][] {
          {"check a SAF-T report", (BiConsumer<ActionRequest, ActionResponse>) c::validate},
          {"generate a SAF-T file", (BiConsumer<ActionRequest, ActionResponse>) c::generate},
          {
            "record a manual НАП submission",
            (BiConsumer<ActionRequest, ActionResponse>) c::recordFiling
          },
          {"record an НАП outcome", (BiConsumer<ActionRequest, ActionResponse>) c::recordAccepted},
          {"record an НАП outcome", (BiConsumer<ActionRequest, ActionResponse>) c::recordRejected},
          {"attach an НАП response", (BiConsumer<ActionRequest, ActionResponse>) c::attachProtocol},
          {
            "correct a SAF-T report",
            (BiConsumer<ActionRequest, ActionResponse>) c::createCorrection
          }
        }) {
      Integer before = version(SaftReport.class, reportId);
      @SuppressWarnings("unchecked")
      BiConsumer<ActionRequest, ActionResponse> call =
          (BiConsumer<ActionRequest, ActionResponse>) action[1];
      Map<String, Object> extra = new HashMap<>();
      extra.put("incomingNumberInput", "НАП-ОТКАЗ");
      extra.put("portalDateInput", PORTAL_DATE.toString());
      extra.put("rejectionReasonInput", "опит");
      String text = press(reportId, extra, call);
      assertTrue(
          text.contains(NOT_ALLOWED + action[0]) && text.contains(NAP_WRITE),
          NAP_READ + " → шумен отказ за „" + action[0] + "“: " + text);
      assertEquals(before, version(SaftReport.class, reportId), "отчетът е непокътнат");
    }
    assertEquals(1, artifacts(reportId));
    assertEquals(1, submissions(reportId));
    assertEquals(
        NapSubmissionRepository.STATUS_SUBMITTED,
        latestSubmission(reportId).getSubmissionStatusSelect());

    String download = press(reportId, null, c::download);
    assertTrue(download.contains("content/download"), "сваля: " + download);
  }

  @Test
  @DisplayName("hr.payroll.write — отказ (SAF-T е на НАП); all.write и client.admin минават")
  void theFilingCapabilityOfTheKind() {
    BgRoleLogin.login(company(), "hr.payroll.write");
    String refused = press(reportId, null, controller()::generate);
    assertTrue(refused.contains(NOT_ALLOWED + "generate a SAF-T file"), refused);
    assertEquals(0, artifacts(reportId));
    BgRoleLogin.logout();

    BgRoleLogin.login(company(), "all.write");
    assertFalse(press(reportId, null, controller()::generate).contains(NOT_ALLOWED));
    assertEquals(1, artifacts(reportId));
    BgRoleLogin.logout();

    BgRoleLogin.login(company(), "client.admin");
    assertFalse(press(reportId, null, controller()::generate).contains(NOT_ALLOWED));
    assertEquals(2, artifacts(reportId));
  }

  // ═══ ДРУЖЕСТВО А / Б ════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "all.write с А и Б, активна А: отчетът на Б не се вижда, не се проверява, не се генерира;"
          + " нов отчет за Б — отказ")
  void companyAcannotTouchB() {
    BgRoleLogin.login(company(), Set.of(otherCompany()), "all.write");
    JpaSecurity security = Beans.get(JpaSecurity.class);
    assertTrue(security.isPermitted(JpaSecurity.CAN_READ, SaftReport.class, reportId));
    assertFalse(
        security.isPermitted(JpaSecurity.CAN_READ, SaftReport.class, otherReportId),
        "отчетът на Б не се вижда");

    Integer before = version(SaftReport.class, otherReportId);
    for (BiConsumer<ActionRequest, ActionResponse> action :
        List.<BiConsumer<ActionRequest, ActionResponse>>of(
            controller()::validate, controller()::generate, controller()::download)) {
      String text = press(otherReportId, null, action);
      assertTrue(text.contains("active company"), "отказ за Б: " + text);
    }
    assertEquals(before, version(SaftReport.class, otherReportId), "Б е непокътнат");
    assertEquals(0, artifacts(otherReportId));

    Throwable forB = create(SaftReport.class, newReport(otherCompanyId));
    assertNotNull(forB, "нов отчет за Б — отказ");
    assertTrue(
        chain(forB).contains("само за активното дружество"), "отказът е от пазача на G6b: " + forB);
    assertNull(create(SaftReport.class, newReport(companyId)), "за активната А — минава");
  }

  // ═══ READY НИКОГА ПРЯКО ═════════════════════════════════════════════════

  @Test
  @DisplayName(
      "READY никога пряко: REST запис на статуса, готовността и файла и масово обновяване —"
          + " отказ; работният поток пак минава")
  void readyIsNeverWrittenDirectly() {
    BgRoleLogin.login(company(), NAP_WRITE);
    assertRefusal(save(SaftReport.class, reportId, "lifecycleStatusSelect", "ready"));
    assertRefusal(save(SaftReport.class, reportId, "readinessStatusSelect", "ready"));
    assertRefusal(save(SaftReport.class, reportId, "lifecycleStatusSelect", "generated"));
    assertRefusal(massUpdate(SaftReport.class, "lifecycleStatusSelect", "ready", reportId));
    assertEquals(SaftReportRepository.LIFECYCLE_DRAFT, report(reportId).getLifecycleStatusSelect());

    // Генерираният файл не се подменя с по-стар артефакт.
    press(reportId, null, controller()::generate);
    Long older = report(reportId).getGeneratedArtifact().getId();
    press(reportId, null, controller()::generate);
    Long generated = report(reportId).getGeneratedArtifact().getId();
    assertRefusal(save(SaftReport.class, reportId, "generatedArtifact", ref(older)));
    assertEquals(generated, report(reportId).getGeneratedArtifact().getId());

    // Името (не статус) — свободно.
    assertNull(save(SaftReport.class, reportId, "name", "SAFT-преименуван"));
  }

  @Test
  @DisplayName("Подаване в technical_error и signed не се трие")
  void technicalErrorAndSignedAreNotDeleted() {
    Long[] ids = new Long[2];
    JPA.runInTransaction(
        () -> {
          ids[0] = saftSubmission(NapSubmissionRepository.STATUS_TECHNICAL_ERROR).getId();
          ids[1] = saftSubmission(NapSubmissionRepository.STATUS_SIGNED).getId();
        });
    BgRoleLogin.login(company(), NAP_WRITE);
    for (Long id : ids) {
      assertNotNull(remove(NapSubmission.class, id), "триенето е отказ");
      assertNotNull(submission(id), "редът е там");
    }
  }

  // ─────────────── помощни ───────────────

  // ═══ ГОДИШНИЯТ И ПРИ ПОИСКВАНЕ (T1) ════════════════════════════════════════

  @Test
  @DisplayName(
      "Годишен 2025: файл ЕИК_2025.xml на дружеството → подаден → приет; приетият заключва"
          + " ГОДИНАТА за всеки отчет на дружеството; друга година, месецът и дружество Б — свободни")
  void theAnnualFileLocksTheYear() {
    Long annual = persisted(annualReport(companyId, 2025, "ГОД-1"));
    Long again = persisted(annualReport(companyId, 2025, "ГОД-2"));
    Long other = persisted(annualReport(companyId, 2024, "ГОД-3"));
    Long forB = persisted(annualReport(otherCompanyId, 2025, "ГОД-Б"));
    BgRoleLogin.login(company(), NAP_WRITE);
    String generated = press(annual, null, controller()::generate);
    assertFalse(generated.contains("error"), generated);
    SaftReport report = report(annual);
    assertEquals(SaftReportRepository.LIFECYCLE_GENERATED, report.getLifecycleStatusSelect());
    NapArtifact artifact = artifact(report.getGeneratedArtifact().getId());
    assertEquals("123456789_2025.xml", artifact.getName(), "името по Прил. 3 т. I.2");
    assertEquals(companyId, artifact.getCompany().getId(), "файлът е на дружеството");
    assertTrue(new String(bytes(artifact), StandardCharsets.UTF_8).contains("MasterFilesAnnual"));

    assertFalse(filing(annual, "30TST000000301").contains("error"));
    NapSubmission submission = latestSubmission(annual);
    assertNull(submission.getPeriod(), "годишният няма месец");
    press(annual, null, controller()::recordAccepted);

    // Втори отчет за същата година — генериране и подаване се отказват.
    String locked = press(again, null, controller()::generate);
    assertTrue(
        locked.contains("Годишният файл за 2025")
            && locked.contains("приет от НАП")
            && locked.contains("30TST000000301"),
        locked);
    assertEquals(0, artifacts(again));
    assertTrue(filing(again, "30TST000000302").contains("приет от НАП"));

    // Друга година, месецът на същото дружество и годината на Б — свободни.
    assertFalse(press(other, null, controller()::generate).contains("error"));
    assertFalse(press(reportId, null, controller()::generate).contains("error"), "месецът");
    assertNull(
        Beans.get(SaftPeriodLock.class).refusal(managed(forB)), "годината на Б не е заключена");
  }

  @Test
  @DisplayName(
      "При поискване: искане ИСК-1 01.07–15.07 прието → 16.07–31.07 по същото искане минава (QA"
          + " с. 20), застъпване се отказва, ново искане за същите дати минава")
  void theOnDemandFileLocksTheRequest() {
    Long first = persisted(onDemandReport(companyId, "ИСК-1", july(1), july(15), "ПП-1"));
    Long second = persisted(onDemandReport(companyId, " иск-1 ", july(16), july(31), "ПП-2"));
    Long overlap = persisted(onDemandReport(companyId, "ИСК-1", july(10), july(20), "ПП-3"));
    Long newRequest = persisted(onDemandReport(companyId, "ИСК-2", july(1), july(15), "ПП-4"));
    BgRoleLogin.login(company(), NAP_WRITE);
    assertFalse(press(first, null, controller()::generate).contains("error"));
    assertEquals(
        "123456789_01_07_2026_15_07_2026.xml",
        artifact(report(first).getGeneratedArtifact().getId()).getName());
    assertFalse(filing(first, "30TST000000401").contains("error"));
    press(first, null, controller()::recordAccepted);

    assertFalse(press(second, null, controller()::generate).contains("error"), "вторият подпериод");

    String locked = press(overlap, null, controller()::generate);
    assertTrue(
        locked.contains("01.07.2026 – 15.07.2026")
            && locked.contains("ИСК-1")
            && locked.contains("приет от НАП"),
        locked);

    assertFalse(press(newRequest, null, controller()::generate).contains("error"), "ново искане");
  }

  @Test
  @DisplayName(
      "Годишен без година, при поискване без искане — ГРЕШКА при „Провери“, файл няма;"
          + " годината не се сменя след генериране")
  void whatEachKindNeeds() {
    Long noYear = persisted(annualReport(companyId, null, "ГОД-0"));
    Long noRequest = persisted(onDemandReport(companyId, null, july(1), july(15), "ПП-0"));
    Long annual = persisted(annualReport(companyId, 2025, "ГОД-Ф"));
    BgRoleLogin.login(company(), NAP_WRITE);
    press(noYear, null, controller()::validate);
    assertTrue(
        issues(noYear).stream()
            .anyMatch(i -> BgSaftFileEngine.NO_YEAR.equals(i.getSectionFieldId())),
        "NO_YEAR");
    assertEquals(SaftReportRepository.LIFECYCLE_FAILED, report(noYear).getLifecycleStatusSelect());

    press(noRequest, null, controller()::validate);
    assertTrue(
        issues(noRequest).stream()
            .anyMatch(i -> BgSaftFileEngine.NO_REQUEST.equals(i.getSectionFieldId())));

    press(annual, null, controller()::generate);
    assertNotNull(report(annual).getGeneratedArtifact());
    assertRefusal(save(SaftReport.class, annual, "reportYear", 2024));
    assertEquals(2025, report(annual).getReportYear());
  }

  private static SaftReportController controller() {
    return Beans.get(SaftReportController.class);
  }

  private String filing(String number) {
    return filing(reportId, number);
  }

  private static String filing(Long id, String number) {
    Map<String, Object> extra = new HashMap<>();
    extra.put("incomingNumberInput", number);
    extra.put("portalDateInput", PORTAL_DATE.toString());
    return press(id, extra, controller()::recordFiling);
  }

  private static LocalDate july(int day) {
    return LocalDate.of(2026, 7, day);
  }

  /** Записва отчета в собствена транзакция и връща id-то му. */
  private static Long persisted(SaftReport report) {
    JPA.runInTransaction(() -> JPA.em().persist(report));
    Long id = report.getId();
    JPA.clear();
    return id;
  }

  private static SaftReport annualReport(Long companyId, Integer year, String name) {
    SaftReport report = new SaftReport();
    report.setName(name + " " + System.nanoTime());
    report.setCompany(JPA.em().find(Company.class, companyId));
    report.setReportTypeSelect(SaftReportRepository.REPORT_TYPE_ANNUAL);
    report.setReportYear(year);
    return report;
  }

  private static SaftReport onDemandReport(
      Long companyId, String request, LocalDate from, LocalDate to, String name) {
    SaftReport report = new SaftReport();
    report.setName(name + " " + System.nanoTime());
    report.setCompany(JPA.em().find(Company.class, companyId));
    report.setReportTypeSelect(SaftReportRepository.REPORT_TYPE_ON_DEMAND);
    report.setNapRequestNumber(request);
    report.setNapRequestDate(to.plusDays(5));
    report.setFromDate(from);
    report.setToDate(to);
    return report;
  }

  private static String press(
      Long id, Map<String, Object> extra, BiConsumer<ActionRequest, ActionResponse> action) {
    ActionResponse response = new ActionResponse();
    assertFalse(JPA.em().getTransaction().isActive(), "действието тръгва БЕЗ транзакция");
    JPA.clear();
    action.accept(request(id, extra), response);
    JPA.clear();
    return String.valueOf(response.getData());
  }

  private static ActionRequest request(Long id, Map<String, Object> extra) {
    Map<String, Object> context = new HashMap<>();
    context.put("id", id);
    context.put("version", version(SaftReport.class, id));
    context.put("_model", SaftReport.class.getName());
    if (extra != null) {
      context.putAll(extra);
    }
    Map<String, Object> data = new HashMap<>();
    data.put("context", context);
    ActionRequest request = new ActionRequest();
    request.setModel(SaftReport.class.getName());
    request.setData(data);
    return request;
  }

  private Company company() {
    return JPA.em().find(Company.class, companyId);
  }

  private Company otherCompany() {
    return JPA.em().find(Company.class, otherCompanyId);
  }

  private LocalDate todayOf() {
    return Beans.get(AppBaseService.class).getTodayDate(company());
  }

  private static Company company(String name, String code) {
    Company company = new Company();
    company.setName(name);
    company.setCode(code);
    JPA.em().persist(company);
    return company;
  }

  private static Period period(Company company, String tag) {
    Year year = new Year();
    year.setName("2026 " + tag);
    year.setCode("Y" + tag);
    year.setFromDate(LocalDate.of(2026, 1, 1));
    year.setToDate(LocalDate.of(2026, 12, 31));
    year.setCompany(company);
    year.setTypeSelect(1);
    JPA.em().persist(year);
    Period period = new Period();
    period.setName("10/2026 " + tag);
    period.setYear(year);
    period.setFromDate(LocalDate.of(2026, 10, 1));
    period.setToDate(LocalDate.of(2026, 10, 31));
    period.setStatusSelect(PeriodRepository.STATUS_OPENED);
    JPA.em().persist(period);
    return period;
  }

  private static SaftReport report(Company company, Period period, String name) {
    SaftReport report = new SaftReport();
    report.setName(name);
    report.setCompany(company);
    report.setPeriod(period);
    report.setReportTypeSelect(SaftReportRepository.REPORT_TYPE_MONTHLY);
    JPA.em().persist(report);
    return report;
  }

  private Map<String, Object> newReport(Long company) {
    Map<String, Object> data = new HashMap<>();
    data.put("name", "SAFT-нов-" + System.nanoTime());
    data.put("company", ref(company));
    data.put("reportTypeSelect", SaftReportRepository.REPORT_TYPE_MONTHLY);
    return data;
  }

  /** Статия в DAYBOOK — без потребител, както я оставя заварената база. */
  private static Long daybookMove(Long companyId, LocalDate date, String reference) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          Company company = JPA.em().find(Company.class, companyId);
          JournalType type = new JournalType();
          type.setName("Тип " + reference);
          type.setCode("T" + System.nanoTime());
          JPA.em().persist(type);
          Journal journal = new Journal();
          journal.setName("Дневник " + reference);
          journal.setCode("J" + (System.nanoTime() % 100000));
          journal.setCompany(company);
          journal.setJournalType(type);
          JPA.em().persist(journal);
          Period period =
              JPA.em()
                  .createQuery(
                      "SELECT p FROM Period p WHERE p.year.company.id = :c ORDER BY p.id",
                      Period.class)
                  .setParameter("c", companyId)
                  .setMaxResults(1)
                  .getSingleResult();
          Move move = new Move();
          move.setCompany(company);
          move.setJournal(journal);
          move.setPeriod(period);
          move.setDate(date);
          move.setReference(reference);
          move.setTechnicalOriginSelect(MoveRepository.TECHNICAL_ORIGIN_ENTRY);
          move.setStatusSelect(MoveRepository.STATUS_DAYBOOK);
          JPA.em().persist(move);
          id[0] = move.getId();
        });
    return id[0];
  }

  private NapSubmission saftSubmission(String status) {
    NapSubmission s = new NapSubmission();
    s.setName("SAF-T " + status + " " + System.nanoTime());
    s.setCompany(JPA.em().find(Company.class, companyId));
    s.setServiceCodeSelect(NapSubmissionRepository.SERVICE_SAFT);
    s.setChannelSelect(NapSubmissionRepository.CHANNEL_MANUAL_PORTAL);
    s.setSubmissionStatusSelect(status);
    JPA.em().persist(s);
    return s;
  }

  /** Файл, качен от влезлия потребител (G6: само собствен файл). */
  private static Long upload(String name, String content) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          try {
            MetaFile file =
                Beans.get(MetaFiles.class)
                    .upload(
                        new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), name);
            id[0] = file.getId();
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
    return id[0];
  }

  private static SaftReport managed(Long id) {
    JPA.clear();
    return Beans.get(SaftReportRepository.class).find(id);
  }

  private static SaftReport report(Long id) {
    return fresh(
        em -> {
          SaftReport r = em.find(SaftReport.class, id);
          if (r.getGeneratedArtifact() != null) {
            r.getGeneratedArtifact().getId();
          }
          return r;
        });
  }

  private static NapArtifact artifact(Long id) {
    return fresh(
        em -> {
          NapArtifact a = em.find(NapArtifact.class, id);
          a.getCompany().getId();
          a.getSaftReport().getId();
          a.getMetaFile().getFilePath();
          return a;
        });
  }

  private static byte[] bytes(NapArtifact artifact) {
    try {
      return Files.readAllBytes(MetaFiles.getPath(artifact.getMetaFile()));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static long artifacts(Long reportId) {
    return fresh(
        em ->
            em.createQuery(
                    "SELECT COUNT(a) FROM NapArtifact a WHERE a.saftReport.id = :r", Long.class)
                .setParameter("r", reportId)
                .getSingleResult());
  }

  private static long submissions(Long reportId) {
    return fresh(
        em ->
            em.createQuery(
                    "SELECT COUNT(s) FROM NapSubmission s WHERE s.saftReport.id = :r", Long.class)
                .setParameter("r", reportId)
                .getSingleResult());
  }

  private NapSubmission onlySubmission(String status) {
    assertEquals(1, submissions(reportId));
    NapSubmission s = latestSubmission(reportId);
    assertEquals(status, s.getSubmissionStatusSelect());
    return s;
  }

  private static NapSubmission latestSubmission(Long reportId) {
    return fresh(
        em -> {
          NapSubmission s =
              em.createQuery(
                      "SELECT s FROM NapSubmission s WHERE s.saftReport.id = :r ORDER BY s.id DESC",
                      NapSubmission.class)
                  .setParameter("r", reportId)
                  .setMaxResults(1)
                  .getSingleResult();
          if (s.getPeriod() != null) {
            s.getPeriod().getId();
          }
          return s;
        });
  }

  private static NapSubmission submission(Long id) {
    return fresh(em -> em.find(NapSubmission.class, id));
  }

  private static Long fileOf(Long submissionId) {
    return fresh(
        em ->
            em.createQuery(
                    "SELECT f.payloadArtifact.id FROM NapSubmissionFile f"
                        + " WHERE f.submission.id = :s",
                    Long.class)
                .setParameter("s", submissionId)
                .getSingleResult());
  }

  private static List<SaftValidationIssue> issues(Long reportId) {
    return fresh(
        em ->
            em.createQuery(
                    "SELECT i FROM SaftValidationIssue i WHERE i.saftReport.id = :r",
                    SaftValidationIssue.class)
                .setParameter("r", reportId)
                .getResultList());
  }

  private static long protocolIssues(Long submissionId) {
    return fresh(
        em ->
            em.createQuery(
                    "SELECT COUNT(i) FROM SaftValidationIssue i WHERE i.targetModel = :m"
                        + " AND i.targetRecordId = :s",
                    Long.class)
                .setParameter("m", NapSubmission.class.getName())
                .setParameter("s", submissionId)
                .getSingleResult());
  }

  private static Integer version(Class<? extends Model> model, Long id) {
    return fresh(em -> em.find(model, id).getVersion());
  }

  /** Запис в собствена транзакция; връща отказа или null, ако е минал. */
  private static RuntimeException refusedWrite(Runnable write) {
    try {
      JPA.runInTransaction(write);
      return null;
    } catch (RuntimeException e) {
      return e;
    } finally {
      JPA.em().clear();
    }
  }

  private static <T> T fresh(Function<EntityManager, T> read) {
    EntityManager em = JPA.em().getEntityManagerFactory().createEntityManager();
    try {
      return read.apply(em);
    } finally {
      em.close();
    }
  }

  // ─────────────── REST (истинските Resource.save / updateMass / remove) ───────────────

  @SuppressWarnings("unchecked")
  private static <T extends Model> Resource<T> rest(Class<T> model) {
    return (Resource<T>)
        Beans.get(Injector.class)
            .getInstance(Key.get(Types.newParameterizedType(Resource.class, model)));
  }

  private interface Call {
    void run() throws Exception;
  }

  private static Throwable call(Call call) {
    JPA.clear();
    try {
      call.run();
      return null;
    } catch (Throwable t) {
      return t;
    } finally {
      JPA.clear();
    }
  }

  private static <T extends Model> Throwable save(
      Class<T> model, Long id, String field, Object value) {
    Map<String, Object> data = new HashMap<>();
    data.put("id", id);
    data.put("version", version(model, id));
    data.put(field, value);
    return create(model, data);
  }

  private static <T extends Model> Throwable create(Class<T> model, Map<String, Object> data) {
    Request request = new Request();
    request.setModel(model.getName());
    request.setData(data);
    return call(() -> rest(model).save(request));
  }

  private static <T extends Model> Throwable remove(Class<T> model, Long id) {
    Map<String, Object> data = new HashMap<>();
    data.put("version", version(model, id));
    Request request = new Request();
    request.setModel(model.getName());
    request.setData(data);
    return call(() -> rest(model).remove(id, request));
  }

  private static <T extends Model> Throwable massUpdate(
      Class<T> model, String field, Object value, Long... ids) {
    Map<String, Object> values = new HashMap<>();
    values.put(field, value);
    List<Object> records = new ArrayList<>();
    records.add(values);
    Map<String, Object> context = new HashMap<>();
    context.put("_model", model.getName());
    context.put("__ids__", List.of(ids));
    Map<String, Object> data = new HashMap<>();
    data.put("_domain", "self.id IN (:__ids__)");
    data.put("_domainContext", context);
    Request request = new Request();
    request.setModel(model.getName());
    request.setRecords(records);
    request.setData(data);
    return call(() -> rest(model).updateMass(request));
  }

  /** Връзка по id — изменима карта: {@code Resource.save} я чисти при проверката на правата. */
  private static Map<String, Object> ref(Long id) {
    Map<String, Object> ref = new HashMap<>();
    ref.put("id", id);
    return ref;
  }

  private static String chain(Throwable t) {
    StringBuilder text = new StringBuilder();
    for (Throwable c = t; c != null; c = c.getCause()) {
      text.append(c).append(" | ");
    }
    return text.toString();
  }

  private static void assertRefusal(Throwable t) {
    assertNotNull(t, "заявката мина");
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof RegulatoryImmutabilityException) {
        return;
      }
    }
    throw new AssertionError("отказът не е от пазача на SAF-T отчета, а: " + t, t);
  }
}

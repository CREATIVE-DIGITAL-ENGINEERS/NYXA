package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgArt73Para6Report;
import com.axelor.apps.bglocalization.db.BgD1Declaration;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.d1.BgD6Point;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationStatus;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.bglocalization.web.BgArt73Para6ReportController;
import com.axelor.apps.bglocalization.web.BgD6DeclarationController;
import com.axelor.apps.napsubmissions.common.artifact.Institution;
import com.axelor.apps.napsubmissions.common.artifact.NapArtifactService;
import com.axelor.apps.napsubmissions.common.artifact.RegulatoryFile;
import com.axelor.apps.napsubmissions.common.submission.NapFilingGate;
import com.axelor.apps.napsubmissions.common.submission.NapSubmissionClient;
import com.axelor.apps.napsubmissions.common.submission.NapSubmissionResult;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.UserQesProfile;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionFileRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsApiClient;
import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsResultRequest;
import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsResultResponse;
import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsSubmissionService;
import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsSubmitRequest;
import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsSubmitResponse;
import com.axelor.apps.napsubmissions.services.declarations.common.FileDocumentType;
import com.axelor.apps.napsubmissions.services.declarations.common.ServiceDocumentType;
import com.axelor.apps.napsubmissions.web.NapSubmissionController;
import com.axelor.auth.AuthUtils;
import com.axelor.db.JPA;
import com.axelor.db.JpaSecurity;
import com.axelor.db.JpaSupport;
import com.axelor.db.Model;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import com.google.inject.AbstractModule;
import com.google.inject.util.Modules;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
 * ПОДАВАНИЯ БЕЗ КЕП В NYXA — по истинските контролери, без тестова транзакция, с ролите от каталога
 * ({@link BgRoleLogin}).
 *
 * <ul>
 *   <li><b>Гейт по вида на документа:</b> Д1/Д6 и чл. 73(6) — {@code hr.payroll.write}; Прил. 9 —
 *       {@code hr.noi.write}; {@code all.write} ги включва. С {@code .read} — само преглед и
 *       изтегляне: всяко действие е шумен отказ и нищо не е записано.
 *   <li><b>Един документ — едно подаване:</b> подадено ръчно заключва пътя по API и обратно;
 *       отпечатъкът (SHA-256) на подаденото, кой и кога се пазят.
 *   <li><b>Подпис в Nyxa няма:</b> „Подпиши“ е шумен отказ, нищо не стига до QES.
 *   <li><b>Изолация:</b> активна А не вижда и не подава файл/подаване на Б, дори Б да е в
 *       companySet.
 * </ul>
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgFilingGatesTest.Wiring.class)
@Tag("integration")
class BgFilingGatesTest extends JpaSupport {

  /**
   * Производственото опроводяване, с ЕДНА подмяна: клиентът на Declarations API е стъб, който пази
   * заявката и връща номер — границата на API-то се стига, нищо не тръгва към НАП.
   */
  public static class Wiring extends AbstractModule {
    @Override
    protected void configure() {
      install(
          Modules.override(new BgLocalizationProductionWiringTestModule())
              .with(
                  binder -> {
                    binder.bind(DeclarationsApiClient.class).toInstance(API);
                    binder.bind(NapSubmissionClient.class).toInstance(CLIENT);
                  }));
    }
  }

  static final long STUB_DOCUMENT_ID = 4242L;

  /** Стъбът на API-то: пази изпратеното, връща номер; резултат не дава. */
  static final class StubApi implements DeclarationsApiClient {
    final List<DeclarationsSubmitRequest> sent = new ArrayList<>();

    @Override
    public DeclarationsSubmitResponse submit(DeclarationsSubmitRequest request) {
      sent.add(request);
      DeclarationsSubmitResponse response = new DeclarationsSubmitResponse();
      response.documentId = STUB_DOCUMENT_ID;
      response.entryNumber = "30E000004242";
      response.entryDate = "2026-10-15";
      return response;
    }

    @Override
    public DeclarationsResultResponse getResult(DeclarationsResultRequest request) {
      throw new AssertionError("резултат не се иска в тези тестове");
    }
  }

  static final StubApi API = new StubApi();

  /** Стъбът на общия клиент (SAF-T и др.): само брои повикванията. */
  static final class StubClient implements NapSubmissionClient {
    int calls;

    @Override
    public NapSubmissionResult submit(NapSubmission submission) {
      calls++;
      return NapSubmissionResult.notConfigured("стъб");
    }

    @Override
    public NapSubmissionResult getStatus(NapSubmission submission) {
      calls++;
      return NapSubmissionResult.notConfigured("стъб");
    }
  }

  static final StubClient CLIENT = new StubClient();

  private static final String NOT_ALLOWED = "You are not allowed to ";
  private static final String PAYROLL_WRITE = "hr.payroll.write";
  private static final String PAYROLL_READ = "hr.payroll.read";
  private static final String NOI_WRITE = "hr.noi.write";
  private static final String NAP_WRITE = "accounting.nap.write";
  private static final String NAP_READ = "accounting.nap.read";

  private static final String LOCK_FIRST = "Lock the selection first";
  private static final String API_LOCKED = "API submission is locked";
  private static final String MANUAL_LOCKED = "Manual recording is locked";
  private static final String REGENERATION_LOCKED = "Ново генериране е заключено";
  private static final String EVIDENCE = "is evidence";

  private static final LocalDate PORTAL_DATE = LocalDate.of(2026, 10, 15);

  private Long companyId;
  private Long otherCompanyId;
  private Long periodId;
  private Long d1Id;
  private Long d6Id;
  private Long d1SubmissionId;
  private Long d1SiblingId;
  private Long otherSubmissionId;
  private Long app9SubmissionId;
  private Long art73Id;
  private String d1Sha;

  @BeforeEach
  void scene() {
    String tag = String.valueOf(System.nanoTime());
    JPA.runInTransaction(
        () -> {
          Company company = company("Подаване А " + tag, "PA" + tag);
          companyId = company.getId();
          Company other = company("Подаване Б " + tag, "PB" + tag);
          otherCompanyId = other.getId();
          Period period = period(company, "A" + tag);
          periodId = period.getId();
          Period otherPeriod = period(other, "B" + tag);

          BgD1Declaration d1 = new BgD1Declaration();
          d1.setCompany(company);
          d1.setBgDataPeriod(period);
          d1.setStatusSelect("generated");
          JPA.em().persist(d1);
          d1Id = d1.getId();

          BgD6Declaration d6 = new BgD6Declaration();
          d6.setCompany(company);
          d6.setBgPeriod(period);
          d6.setStatusSelect("generated");
          JPA.em().persist(d6);
          d6Id = d6.getId();

          NapArtifact payload = payload("EMPL2021.TXT", "Д1 " + tag, company);
          d1Sha = payload.getSha256();
          d1SubmissionId = d1Submission(company, period, d1Id, payload, "draft").getId();
          d1SiblingId =
              d1Submission(
                      company,
                      period,
                      d1Id,
                      payload("EMPL2021.TXT", "Д1' " + tag, company),
                      "draft")
                  .getId();

          BgD1Declaration otherD1 = new BgD1Declaration();
          otherD1.setCompany(other);
          otherD1.setBgDataPeriod(otherPeriod);
          otherD1.setStatusSelect("generated");
          JPA.em().persist(otherD1);
          otherSubmissionId =
              d1Submission(
                      other,
                      otherPeriod,
                      otherD1.getId(),
                      payload("EMPL2021.TXT", "Д1 Б " + tag, other),
                      "draft")
                  .getId();

          NapSubmission app9 = new NapSubmission();
          app9.setName("Прил. 9 " + tag);
          app9.setCompany(company);
          app9.setServiceCodeSelect(NapSubmissionRepository.SERVICE_NOI_APP9);
          app9.setChannelSelect(NapSubmissionRepository.CHANNEL_MANUAL_PORTAL);
          app9.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SUBMITTED);
          app9.setNapIncomingNumber("НОИ-" + tag);
          JPA.em().persist(app9);
          app9SubmissionId = app9.getId();

          BgArt73Para6Report report = new BgArt73Para6Report();
          report.setCompany(company);
          report.setTaxYear(2025);
          report.setStatusSelect(BgDeclarationStatus.DRAFT);
          JPA.em().persist(report);
          art73Id = report.getId();
        });
  }

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  // ═══ ГЕЙТЪТ ПО ВИДА НА ДОКУМЕНТА ═════════════════════════════════════════

  @Test
  @DisplayName(
      "Д1/Д6 ръчно: hr.payroll.write подава — вх. №, датата от портала, кой, SHA-256 на файла")
  void payrollWriteRecordsTheManualFiling() {
    BgRoleLogin.login(company(), PAYROLL_WRITE);
    Long user = AuthUtils.getUser().getId();
    String text = manual(d1SubmissionId, "НАП-Д1-1");
    assertFalse(text.contains(NOT_ALLOWED), PAYROLL_WRITE + " минава гейта: " + text);

    NapSubmission s = submission(d1SubmissionId);
    assertEquals(NapSubmissionRepository.STATUS_SUBMITTED, s.getSubmissionStatusSelect(), text);
    assertEquals(NapSubmissionRepository.CHANNEL_MANUAL_PORTAL, s.getChannelSelect());
    assertEquals("НАП-Д1-1", s.getNapIncomingNumber());
    assertEquals(PORTAL_DATE, s.getSubmittedOn().toLocalDate(), "датата е от портала, не „сега“");
    assertEquals(user, s.getSubmittedBy().getId(), "кой е въвел");
    assertEquals(d1Sha, shaOf(d1SubmissionId), "SHA-256 на подадения файл — този на артефакта");
  }

  @Test
  @DisplayName("Д1/Д6 ръчно: all.write също подава")
  void allWriteRecordsTheManualFiling() {
    BgRoleLogin.loginWrite(company());
    String text = manual(d1SubmissionId, "НАП-Д1-2");
    assertFalse(text.contains(NOT_ALLOWED), "all.write минава гейта: " + text);
    assertEquals(
        NapSubmissionRepository.STATUS_SUBMITTED,
        submission(d1SubmissionId).getSubmissionStatusSelect(),
        text);
  }

  @Test
  @DisplayName(
      "Д1/Д6 с hr.payroll.read: ръчно, по API, резултат, приет/отхвърлен — шумен отказ, нищо")
  void payrollReadIsRefusedEverything() {
    NapSubmissionController c = Beans.get(NapSubmissionController.class);
    refusedFor(
        PAYROLL_READ, "record a manual NAP submission", PAYROLL_WRITE, c::recordManualSubmission);
    refusedFor(PAYROLL_READ, "submit a declaration to NAP", PAYROLL_WRITE, c::signAndSubmit);
    refusedFor(PAYROLL_READ, "fetch the NAP result", PAYROLL_WRITE, c::fetchNapResult);
    refusedFor(PAYROLL_READ, "record a NAP outcome", PAYROLL_WRITE, c::recordAccepted);
    refusedFor(PAYROLL_READ, "record a NAP outcome", PAYROLL_WRITE, c::recordRejected);
    assertEquals(
        NapSubmissionRepository.STATUS_DRAFT,
        submission(d1SubmissionId).getSubmissionStatusSelect());
  }

  @Test
  @DisplayName("Д1/Д6: accounting.nap.write и hr.noi.write — отказ (Д1/Д6 са на ТРЗ)")
  void otherCapabilitiesAreRefusedOnD1() {
    NapSubmissionController c = Beans.get(NapSubmissionController.class);
    refusedFor(
        NAP_WRITE, "record a manual NAP submission", PAYROLL_WRITE, c::recordManualSubmission);
    refusedFor(
        NOI_WRITE, "record a manual NAP submission", PAYROLL_WRITE, c::recordManualSubmission);
  }

  @Test
  @DisplayName("Прил. 9 в общия контролер: hr.noi.write минава; hr.payroll.write — отказ")
  void app9IsTheNoiCapability() {
    NapSubmissionController c = Beans.get(NapSubmissionController.class);
    BgRoleLogin.login(company(), PAYROLL_WRITE);
    Integer before = version(NapSubmission.class, app9SubmissionId);
    String refused = press(NapSubmission.class, app9SubmissionId, null, c::recordAccepted);
    assertTrue(
        refused.contains(NOT_ALLOWED + "record a NAP outcome") && refused.contains(NOI_WRITE),
        PAYROLL_WRITE + " → отказ за Прил. 9: " + refused);
    assertEquals(before, version(NapSubmission.class, app9SubmissionId), "непокътнато");

    BgRoleLogin.logout();
    BgRoleLogin.login(company(), NOI_WRITE);
    String ok = press(NapSubmission.class, app9SubmissionId, null, c::recordAccepted);
    assertFalse(ok.contains(NOT_ALLOWED), NOI_WRITE + " минава: " + ok);
    assertEquals(
        NapSubmissionRepository.STATUS_ACCEPTED,
        submission(app9SubmissionId).getSubmissionStatusSelect(),
        ok);
  }

  // ═══ ЕДИН ДОКУМЕНТ — ЕДНО ПОДАВАНЕ ═══════════════════════════════════════

  @Test
  @DisplayName("Подадено ръчно → пътят по API е заключен (и за същия ред, и за сестринския)")
  void manualFilingLocksTheApiPath() {
    NapSubmissionController c = Beans.get(NapSubmissionController.class);
    BgRoleLogin.loginWrite(company());
    manual(d1SubmissionId, "НАП-Д1-3");
    assertEquals(
        NapSubmissionRepository.STATUS_SUBMITTED,
        submission(d1SubmissionId).getSubmissionStatusSelect());

    // Самият ред е доказателство (#334); пътят по API го отказва още в бутона — преди изобщо да се
    // отвори подписваща страница.
    API.sent.clear();
    Integer sameBefore = version(NapSubmission.class, d1SubmissionId);
    String same = press(NapSubmission.class, d1SubmissionId, null, c::signAndSubmit);
    assertTrue(same.contains("error"), "същият ред: отказ: " + same);
    assertTrue(API.sent.isEmpty(), "до API-то нищо не е тръгнало: " + same);
    assertEquals(sameBefore, version(NapSubmission.class, d1SubmissionId), "редът е непокътнат");
    NapSubmission after = submission(d1SubmissionId);
    assertEquals(NapSubmissionRepository.CHANNEL_MANUAL_PORTAL, after.getChannelSelect());
    assertEquals(NapSubmissionRepository.STATUS_SUBMITTED, after.getSubmissionStatusSelect());

    // Сестрата — друг ред за СЪЩИЯ документ, „подписан“ по стария път: пак заключено.
    JPA.runInTransaction(
        () ->
            JPA.em()
                .find(NapSubmission.class, d1SiblingId)
                .setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SIGNED));
    // Файлът на сестрата е в базата, но редът в паметта е от подготовката — с празен списък от
    // файлове. Бутонът проверява първо файловете, затова се чете наново, както при истинска заявка.
    JPA.em().clear();
    Integer before = version(NapSubmission.class, d1SiblingId);
    String sibling = press(NapSubmission.class, d1SiblingId, null, c::signAndSubmit);
    assertTrue(sibling.contains(API_LOCKED), "сестринският ред: " + sibling);
    assertEquals(before, version(NapSubmission.class, d1SiblingId), "нищо не е тръгнало");
  }

  @Test
  @DisplayName("Подадено по API → ръчното отбелязване е заключено (и за същия ред, и за сестрата)")
  void apiFilingLocksTheManualPath() {
    JPA.runInTransaction(
        () -> {
          NapSubmission s = JPA.em().find(NapSubmission.class, d1SubmissionId);
          s.setChannelSelect(NapSubmissionRepository.CHANNEL_API);
          s.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SUBMITTED);
          s.setDocumentId(424242L);
          s.setEntryNumber("30E000000042");
        });
    BgRoleLogin.loginWrite(company());

    Integer before = version(NapSubmission.class, d1SubmissionId);
    String same = manual(d1SubmissionId, "НАП-ПРЕПИС");
    assertTrue(
        same.contains(EVIDENCE) && same.contains("channel"),
        "същият ред: отказ на български по правилото на #334, назовава полето: " + same);
    NapSubmission after = submission(d1SubmissionId);
    assertEquals(before, version(NapSubmission.class, d1SubmissionId), "нищо не е записано");
    assertEquals(NapSubmissionRepository.CHANNEL_API, after.getChannelSelect());
    assertNull(after.getNapIncomingNumber(), "API-подаването не е презаписано");

    Integer siblingBefore = version(NapSubmission.class, d1SiblingId);
    String sibling = manual(d1SiblingId, "НАП-ВТОРО");
    assertTrue(sibling.contains(MANUAL_LOCKED), "сестринският ред: " + sibling);
    assertEquals(siblingBefore, version(NapSubmission.class, d1SiblingId), "нищо не е записано");
  }

  // ═══ ПОДПИС В NYXA НЯМА ═════════════════════════════════════════════════

  @Test
  @DisplayName("„Подпиши и изпрати“ преди заключване → отказ; до QES и до API-то нищо не стига")
  void signingIsRefused() {
    NapSubmissionController c = Beans.get(NapSubmissionController.class);
    BgRoleLogin.loginWrite(company());
    API.sent.clear();
    Integer before = version(NapSubmission.class, d1SubmissionId);
    // Подаването е чернова (незаключено): подписването иска първо заключен избор.
    String sign = press(NapSubmission.class, d1SubmissionId, null, c::signAndSubmit);
    assertTrue(sign.contains(LOCK_FIRST), "подпис и подаване: " + sign);
    assertTrue(API.sent.isEmpty(), "до API-то нищо не е тръгнало: " + API.sent.size());
    assertEquals(before, version(NapSubmission.class, d1SubmissionId), "подаването е непокътнато");
    NapSubmissionFile file =
        fresh(
            em ->
                em.createQuery(
                        "SELECT f FROM NapSubmissionFile f WHERE f.submission.id = :s",
                        NapSubmissionFile.class)
                    .setParameter("s", d1SubmissionId)
                    .getSingleResult());
    assertEquals(
        NapSubmissionFileRepository.QES_STATUS_NOT_STARTED, file.getQesSigningStatusSelect());
  }

  // ═══ ИЗОЛАЦИЯТА ══════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "hr.payroll.read с А и Б, активна А: вижда подаването и изтегля файла на А; на Б — не")
  void readSeesOnlyTheActiveCompany() {
    BgRoleLogin.login(company(), Set.of(otherCompany()), PAYROLL_READ);
    JpaSecurity security = Beans.get(JpaSecurity.class);
    assertTrue(
        security.isPermitted(JpaSecurity.CAN_READ, NapArtifact.class, artifactOf(d1SubmissionId)),
        "файлът на А (NapArtifact.company) се изтегля");
    assertFalse(
        security.isPermitted(
            JpaSecurity.CAN_READ, NapArtifact.class, artifactOf(otherSubmissionId)),
        "файлът на Б — НЕ");
    assertTrue(
        security.isPermitted(JpaSecurity.CAN_READ, NapSubmission.class, d1SubmissionId),
        "подаването на А се вижда");
    assertFalse(
        security.isPermitted(JpaSecurity.CAN_READ, NapSubmission.class, otherSubmissionId),
        "подаването на Б — НЕ");
  }

  /**
   * ⚠️ С all.read, не с hr.payroll.read: каталогът на ТРЗ не чете файловете на подаването
   * (прегледът на #298 ограничи промяната до записа върху самото подаване). Изолацията на ФАЙЛА е
   * същото условие по дружеството на подаването.
   */
  @Test
  @DisplayName("all.read с А и Б, активна А: вижда файла на подаването на А; на Б — не")
  void filesAreOnlyTheActiveCompany() {
    BgRoleLogin.login(company(), Set.of(otherCompany()), "all.read");
    JpaSecurity security = Beans.get(JpaSecurity.class);
    assertTrue(
        security.isPermitted(JpaSecurity.CAN_READ, NapSubmissionFile.class, fileOf(d1SubmissionId)),
        "файлът на А се вижда (за изтегляне)");
    assertFalse(
        security.isPermitted(
            JpaSecurity.CAN_READ, NapSubmissionFile.class, fileOf(otherSubmissionId)),
        "файлът на Б — НЕ");
  }

  // ═══ ЧЕТИРИТЕ ДЕЙСТВИЯ ОТ ПРЕГЛЕДА НА #298 ═══════════════════════════════

  @Test
  @DisplayName(
      "hr.payroll.read и accounting.nap.read: подпиши и изпрати, резултат — отказват, нищо не е"
          + " записано, до API-то нищо не стига")
  void theFourActionsRefuseReadRoles() {
    NapSubmissionController c = Beans.get(NapSubmissionController.class);
    signedLegacy(d1SubmissionId);
    API.sent.clear();
    for (String role : new String[] {PAYROLL_READ, NAP_READ}) {
      BgRoleLogin.logout();
      BgRoleLogin.login(company(), role);
      Integer before = version(NapSubmission.class, d1SubmissionId);
      String submit = press(NapSubmission.class, d1SubmissionId, null, c::signAndSubmit);
      assertTrue(
          submit.contains(NOT_ALLOWED + "submit a declaration to NAP")
              && submit.contains(PAYROLL_WRITE),
          role + ": подпиши и изпрати → отказ, който назовава hr.payroll.write: " + submit);
      String fetch = press(NapSubmission.class, d1SubmissionId, null, c::fetchNapResult);
      assertTrue(
          fetch.contains(NOT_ALLOWED + "fetch the NAP result") && fetch.contains(PAYROLL_WRITE),
          role + ": резултат → отказ: " + fetch);
      assertEquals(before, version(NapSubmission.class, d1SubmissionId), role + ": непокътнато");
    }
    assertTrue(API.sent.isEmpty(), "до API-то на НАП не е стигнало нищо: " + API.sent.size());
  }

  @Test
  @DisplayName(
      "hr.payroll.write: „Подпиши и изпрати“ минава гейта, метода и собствения профил и спира"
          + " чак на липсващия USB КЕП на сървъра — подписваща страница няма, до API-то нищо")
  void payrollWriteReachesTheServerSigningCheck() {
    NapSubmissionController c = Beans.get(NapSubmissionController.class);
    // Методът трябва да е избран — иначе бутонът пита кой, вместо да отвори страница. Избира се
    // ПРЕДИ подписа: в „signed“ методът вече е доказателство и не се сменя.
    JPA.runInTransaction(
        () ->
            JPA.em()
                .find(NapSubmission.class, d1SubmissionId)
                .setQesSigningMethodSelect(NapSubmissionRepository.QES_METHOD_LOCAL_USB));
    // Подписва се само със СОБСТВЕНИЯ профил, а подаване на друг подписващ не се
    // преприсвоява — затова подписалият по стария път е самият влязъл с hr.payroll.write.
    Long signer = BgRoleLogin.createUser(company(), "Подписал по стария път", PAYROLL_WRITE);
    signedLegacy(d1SubmissionId, signer);
    API.sent.clear();
    BgRoleLogin.loginExisting(JPA.em().find(com.axelor.auth.db.User.class, signer).getCode());
    Integer before = version(NapSubmission.class, d1SubmissionId);

    String text = press(NapSubmission.class, d1SubmissionId, null, c::signAndSubmit);
    assertFalse(text.contains(NOT_ALLOWED), PAYROLL_WRITE + " минава гейта: " + text);
    // В тестовата среда няма keystore на услугата (nyxa.nap.biss.keystore-path): бутонът казва
    // точно това, ПРЕДИ да отвори подписваща страница — липсата не се заобикаля.
    assertTrue(text.contains("USB КЕП не е конфигуриран на сървъра"), "отказ за keystore: " + text);
    assertFalse(text.contains("nyxa/nap/browser-signing/page"), "страница не се отваря: " + text);
    // ⚠️ Подписването и ЕДНОТО подаване стават само в подписващата страница. Самият бутон не
    // подписва и не подава — тук е и доказателството, че затвореният стар път през съхранен подпис
    // наистина не тръгва.
    assertTrue(API.sent.isEmpty(), "до API-то на НАП не е тръгнало нищо: " + API.sent.size());
    NapSubmission after = submission(d1SubmissionId);
    // documentId е <long> — „няма номер“ е 0 или null, както го чете самият бутон.
    assertFalse(DeclarationsSubmissionService.hasDocumentId(after), "няма номер от НАП: " + text);
    assertEquals(NapSubmissionRepository.STATUS_SIGNED, after.getSubmissionStatusSelect(), text);
    assertEquals(before, version(NapSubmission.class, d1SubmissionId), "редът е непокътнат");
    assertEquals(d1Sha, shaOf(d1SubmissionId), "и файлът зад подаването е същият");
  }

  // ═══ ПОДАДЕНОТО Е ДОКАЗАТЕЛСТВО (#334) — ПРЕДИ ЗАПИСА, НА БЪЛГАРСКИ ═══════

  @Test
  @DisplayName(
      "След ръчно отбелязване: смяна на файла или на SHA-256 му — отказ (#327, #334), нищо не мърда")
  void theFiledFileAndItsShaAreFrozen() {
    BgRoleLogin.loginWrite(company());
    manual(d1SubmissionId, "НАП-Д1-5");
    assertEquals(
        NapSubmissionRepository.STATUS_SUBMITTED,
        submission(d1SubmissionId).getSubmissionStatusSelect());
    Long fileId = fileOf(d1SubmissionId);
    Long artifactId = artifactOf(d1SubmissionId);
    Long otherArtifact = artifactOf(d1SiblingId);

    RuntimeException swap =
        refusedWrite(
            () ->
                JPA.em()
                    .find(NapSubmissionFile.class, fileId)
                    .setPayloadArtifact(JPA.em().find(NapArtifact.class, otherArtifact)));
    assertNotNull(swap, "смяна на файла на подаденото — отказ");
    RuntimeException sha =
        refusedWrite(() -> JPA.em().find(NapArtifact.class, artifactId).setSha256("0".repeat(64)));
    assertNotNull(sha, "смяна на SHA-256 на подадения файл — отказ");

    assertEquals(artifactId, artifactOf(d1SubmissionId), "файлът е същият");
    assertEquals(d1Sha, shaOf(d1SubmissionId), "SHA-256 е същият");
  }

  @Test
  @DisplayName(
      "След ръчно отбелязване: поправка на входящия номер (същата дата, същият човек) — позволена"
          + " (#334)")
  void theIncomingNumberMayBeCorrectedAfterFiling() {
    BgRoleLogin.loginWrite(company());
    manual(d1SubmissionId, "НАП-ГРЕШЕН");
    String text = manual(d1SubmissionId, "НАП-ВЕРЕН");
    assertFalse(text.contains("доказателство"), "поправката минава: " + text);
    assertEquals("НАП-ВЕРЕН", submission(d1SubmissionId).getNapIncomingNumber());
  }

  @Test
  @DisplayName(
      "Общият API път върху подадено: отказ на български ПРЕДИ клиента — клиентът не е викан")
  void theGenericApiPathRefusesBeforeTheClient() {
    Long[] saft = new Long[1];
    JPA.runInTransaction(
        () -> {
          NapSubmission s = new NapSubmission();
          s.setName("SAF-T ръчно");
          s.setCompany(company());
          s.setServiceCodeSelect(NapSubmissionRepository.SERVICE_SAFT);
          s.setChannelSelect(NapSubmissionRepository.CHANNEL_MANUAL_PORTAL);
          s.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SUBMITTED);
          s.setNapIncomingNumber("НАП-SAFT-1");
          JPA.em().persist(s);
          saft[0] = s.getId();
        });
    CLIENT.calls = 0;
    BgRoleLogin.loginWrite(company());
    Integer before = version(NapSubmission.class, saft[0]);
    String text =
        press(
            NapSubmission.class,
            saft[0],
            null,
            Beans.get(NapSubmissionController.class)::submitThroughApi);
    assertTrue(text.contains(EVIDENCE) && text.contains("channel"), "отказ на български: " + text);
    assertEquals(0, CLIENT.calls, "клиентът на API-то не е викан");
    assertEquals(before, version(NapSubmission.class, saft[0]), "нищо не е записано");
  }

  @Test
  @DisplayName("hr.payroll.write с А и Б, активна А: не подава в Б — отказ, Б непокътнато")
  void writeCannotFileForTheOtherCompany() {
    BgRoleLogin.login(company(), Set.of(otherCompany()), PAYROLL_WRITE);
    Integer before = version(NapSubmission.class, otherSubmissionId);
    String text = manual(otherSubmissionId, "НАП-Б");
    assertTrue(
        text.contains("not authorized") || text.contains("active company"), "отказ за Б: " + text);
    assertEquals(before, version(NapSubmission.class, otherSubmissionId), "Б е непокътнато");
    assertNull(submission(otherSubmissionId).getNapIncomingNumber());
  }

  // ═══ НОВО ГЕНЕРИРАНЕ СЛЕД ПОДАВАНЕ ═══════════════════════════════════════

  @Test
  @DisplayName("Д6: при подадена Д1/Д6 за периода новото генериране е отказ; Д6 непокътната")
  void d6RegenerationAfterFilingIsRefused() {
    JPA.runInTransaction(
        () ->
            JPA.em()
                .find(NapSubmission.class, d1SubmissionId)
                .setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SUBMITTED));
    BgRoleLogin.login(company(), PAYROLL_WRITE);
    Integer before = version(BgD6Declaration.class, d6Id);
    String text =
        press(
            BgD6Declaration.class,
            d6Id,
            null,
            Beans.get(BgD6DeclarationController.class)::generate);
    assertTrue(text.contains(REGENERATION_LOCKED), "отказ: " + text);
    assertEquals(before, version(BgD6Declaration.class, d6Id), "Д6 е непокътната");
  }

  @Test
  @DisplayName("Д6 без вид плащане 5/6: генерирането е отказ с подсказката; Д6 непокътната")
  void d6WithoutPaymentKindIsRefused() {
    BgRoleLogin.login(company(), PAYROLL_WRITE);
    Integer before = version(BgD6Declaration.class, d6Id);
    String text =
        press(
            BgD6Declaration.class,
            d6Id,
            null,
            Beans.get(BgD6DeclarationController.class)::generate);
    assertTrue(
        text.contains(BgD6Point.PAYMENT_KIND_HINT),
        "⚠️ 5 или 6 избира човек — фишът не е вързан с плащането. Без избор генерирането спира"
            + " със същия текст като подсказката на полето: "
            + text);
    assertEquals(before, version(BgD6Declaration.class, d6Id), "Д6 е непокътната");
  }

  @Test
  @DisplayName("чл. 73(6): подадена справка — „Генерирай“, „Генерирай файла“, „Обезсили“ са отказ")
  void art73RegenerationAfterFilingIsRefused() {
    JPA.runInTransaction(
        () -> {
          BgArt73Para6Report r = JPA.em().find(BgArt73Para6Report.class, art73Id);
          NapSubmission s = JPA.em().find(NapSubmission.class, app9SubmissionId);
          r.setStatusSelect(BgDeclarationStatus.SUBMITTED);
          r.setNapIncomingNumber("НАП-73-1");
          r.setNapSubmission(s);
        });
    BgArt73Para6ReportController c = Beans.get(BgArt73Para6ReportController.class);
    BgRoleLogin.login(company(), PAYROLL_WRITE);
    Integer before = version(BgArt73Para6Report.class, art73Id);
    String generate = press(BgArt73Para6Report.class, art73Id, null, c::generate);
    assertTrue(generate.contains(REGENERATION_LOCKED), "Генерирай: " + generate);
    String file = press(BgArt73Para6Report.class, art73Id, null, c::generateFile);
    assertTrue(file.contains(REGENERATION_LOCKED), "Генерирай файла: " + file);
    String invalidate = press(BgArt73Para6Report.class, art73Id, null, c::invalidateFile);
    assertTrue(invalidate.contains(REGENERATION_LOCKED), "Обезсили: " + invalidate);
    BgArt73Para6Report after = fresh(em -> em.find(BgArt73Para6Report.class, art73Id));
    assertEquals(before, after.getVersion(), "справката е непокътната");
    assertEquals(BgDeclarationStatus.SUBMITTED, after.getStatusSelect());
  }

  // ═══ ЧЛ. 73(6): ОТТЕГЛЯНЕТО — ЕДИНСТВЕНИЯТ ПЪТ КЪМ НОВА ВЕРСИЯ ════════════

  @Test
  @DisplayName(
      "чл. 73(6) „Оттегли отметката“: .read — отказ; без причина — отказ; с причина — оттеглено,"
          + " справката отново се генерира")
  void art73WithdrawIsTheWayToANewVersion() {
    Long[] submission = new Long[1];
    JPA.runInTransaction(
        () -> {
          NapSubmission s = new NapSubmission();
          s.setName("Справка чл. 73 ал. 6 / 2025");
          s.setCompany(company());
          s.setServiceCodeSelect(NapSubmissionRepository.SERVICE_DECLARATIONS);
          s.setChannelSelect(NapSubmissionRepository.CHANNEL_MANUAL_PORTAL);
          s.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SUBMITTED);
          s.setNapIncomingNumber("НАП-73-2");
          s.setRelatedModel(BgArt73Para6Report.class.getName());
          s.setRelatedRecordId(art73Id);
          JPA.em().persist(s);
          submission[0] = s.getId();
          BgArt73Para6Report r = JPA.em().find(BgArt73Para6Report.class, art73Id);
          r.setStatusSelect(BgDeclarationStatus.SUBMITTED);
          r.setNapIncomingNumber("НАП-73-2");
          r.setNapSubmission(s);
        });
    BgArt73Para6ReportController c = Beans.get(BgArt73Para6ReportController.class);
    Map<String, Object> reason = new HashMap<>();
    reason.put("withdrawReason", "файлът не е стигнал до портала");

    BgRoleLogin.login(company(), PAYROLL_READ);
    Integer before = version(BgArt73Para6Report.class, art73Id);
    String read = press(BgArt73Para6Report.class, art73Id, reason, c::withdraw);
    assertTrue(
        read.contains(NOT_ALLOWED + "withdraw an art. 73(6) filing")
            && read.contains(PAYROLL_WRITE),
        ".read → отказ: " + read);
    assertEquals(before, version(BgArt73Para6Report.class, art73Id), "непокътната");

    BgRoleLogin.logout();
    BgRoleLogin.login(company(), PAYROLL_WRITE);
    String noReason = press(BgArt73Para6Report.class, art73Id, null, c::withdraw);
    assertTrue(noReason.contains("иска причина"), "без причина: " + noReason);
    assertEquals(before, version(BgArt73Para6Report.class, art73Id), "непокътната");

    String ok = press(BgArt73Para6Report.class, art73Id, reason, c::withdraw);
    assertFalse(ok.contains(NOT_ALLOWED), "с причина минава: " + ok);
    BgArt73Para6Report after = fresh(em -> em.find(BgArt73Para6Report.class, art73Id));
    assertEquals(BgDeclarationStatus.GENERATED, after.getStatusSelect(), ok);
    assertNull(after.getNapIncomingNumber(), "номерът на оттеглената отметка не стои на справката");
    NapSubmission withdrawn = submission(submission[0]);
    assertEquals(NapSubmissionRepository.STATUS_WITHDRAWN, withdrawn.getSubmissionStatusSelect());
    assertEquals("файлът не е стигнал до портала", withdrawn.getCorrectionReason());
    assertEquals("НАП-73-2", withdrawn.getNapIncomingNumber(), "историята пази номера");

    String again = press(BgArt73Para6Report.class, art73Id, null, c::generate);
    assertFalse(again.contains(REGENERATION_LOCKED), "след оттегляне се генерира: " + again);
  }

  // ═══ ВИДЪТ НА ПОДАВАНЕТО Е НЕИЗМЕНИМ ═════════════════════════════════════

  @Test
  @DisplayName("Видът и документът на подаването не се сменят — гейтът по вид не се заобикаля")
  void theKindOfASubmissionCannotBeRelabelled() {
    BgRoleLogin.loginWrite(company());
    NapSubmissionRepository repo = Beans.get(NapSubmissionRepository.class);
    String[] refused = new String[3];
    String[][] changes = {
      {"serviceCodeSelect", NapSubmissionRepository.SERVICE_NOI_APP9},
      {"serviceDocumentTypeSelect", ServiceDocumentType.DEC_ETZ62.name()},
      {"relatedModel", "com.axelor.apps.napsubmissions.db.SaftReport"}
    };
    for (int i = 0; i < changes.length; i++) {
      String[] change = changes[i];
      try {
        JPA.runInTransaction(
            () -> {
              NapSubmission s = repo.find(d1SubmissionId);
              if ("serviceCodeSelect".equals(change[0])) {
                s.setServiceCodeSelect(change[1]);
              } else if ("serviceDocumentTypeSelect".equals(change[0])) {
                s.setServiceDocumentTypeSelect(change[1]);
              } else {
                s.setRelatedModel(change[1]);
              }
              repo.save(s);
            });
      } catch (RuntimeException e) {
        refused[i] = String.valueOf(e.getMessage());
      } finally {
        // Отказаният запис оставя изменения обект в контекста — следващият опит чете от базата.
        JPA.em().clear();
      }
      assertNotNull(refused[i], change[0] + " → отказ");
    }
    NapSubmission after = submission(d1SubmissionId);
    assertEquals(NapSubmissionRepository.SERVICE_DECLARATIONS, after.getServiceCodeSelect());
    assertEquals(ServiceDocumentType.DEC_1_6.name(), after.getServiceDocumentTypeSelect());
    assertEquals(BgD1Declaration.class.getName(), after.getRelatedModel());
  }

  @Test
  @DisplayName("Гейтът познава справката по чл. 73(6) по името на класа — то е същото")
  void theArt73ModelNameMatchesTheClass() {
    assertEquals(BgArt73Para6Report.class.getName(), NapFilingGate.ART73_PARA6_MODEL);
    NapSubmission s = new NapSubmission();
    s.setServiceCodeSelect(NapSubmissionRepository.SERVICE_DECLARATIONS);
    s.setRelatedModel(BgArt73Para6Report.class.getName());
    assertTrue(NapFilingGate.capabilityFor(s).contains(PAYROLL_WRITE), "чл. 73(6) е на ТРЗ");
    assertFalse(NapFilingGate.capabilityFor(s).contains(NAP_WRITE), "не на НАП");
  }

  // ═══ ПОМОЩНИЦИТЕ ═════════════════════════════════════════════════════════

  /** Ръчният път: вх. № и дата от портала, както ги изпраща формата. */
  private String manual(Long submissionId, String incomingNumber) {
    Map<String, Object> extra = new HashMap<>();
    extra.put("napIncomingNumber", incomingNumber);
    extra.put("portalSubmittedDate", PORTAL_DATE.toString());
    return press(
        NapSubmission.class,
        submissionId,
        extra,
        Beans.get(NapSubmissionController.class)::recordManualSubmission);
  }

  private void refusedFor(
      String role,
      String actionName,
      String requiredRole,
      BiConsumer<ActionRequest, ActionResponse> action) {
    BgRoleLogin.logout();
    BgRoleLogin.login(company(), role);
    Integer before = version(NapSubmission.class, d1SubmissionId);
    Map<String, Object> extra = new HashMap<>();
    extra.put("napIncomingNumber", "НАП-ОТКАЗ");
    extra.put("portalSubmittedDate", PORTAL_DATE.toString());
    extra.put("actionReason", "опит");
    String text = press(NapSubmission.class, d1SubmissionId, extra, action);
    assertTrue(
        text.contains(NOT_ALLOWED + actionName) && text.contains(requiredRole),
        role
            + " → шумен отказ, който назовава „"
            + actionName
            + "“ и "
            + requiredRole
            + ": "
            + text);
    assertEquals(before, version(NapSubmission.class, d1SubmissionId), "непокътнато: " + text);
    BgRoleLogin.logout();
  }

  private static String press(
      Class<? extends Model> model,
      Long id,
      Map<String, Object> extra,
      BiConsumer<ActionRequest, ActionResponse> action) {
    ActionResponse response = new ActionResponse();
    assertFalse(JPA.em().getTransaction().isActive(), "действието тръгва БЕЗ транзакция");
    action.accept(request(model, id, extra), response);
    return String.valueOf(response.getData());
  }

  private static ActionRequest request(
      Class<? extends Model> model, Long id, Map<String, Object> extra) {
    Map<String, Object> context = new HashMap<>();
    context.put("id", id);
    context.put("version", version(model, id));
    context.put("_model", model.getName());
    if (extra != null) {
      context.putAll(extra);
    }
    Map<String, Object> data = new HashMap<>();
    data.put("context", context);
    ActionRequest request = new ActionRequest();
    request.setModel(model.getName());
    request.setData(data);
    return request;
  }

  private static NapSubmission d1Submission(
      Company company, Period period, Long d1Id, NapArtifact payload, String status) {
    NapSubmission s = new NapSubmission();
    s.setName("Д1/Д6 " + company.getCode() + " " + payload.getId());
    s.setCompany(company);
    s.setPeriod(period);
    s.setServiceCodeSelect(NapSubmissionRepository.SERVICE_DECLARATIONS);
    s.setServiceDocumentTypeSelect(ServiceDocumentType.DEC_1_6.name());
    s.setChannelSelect(NapSubmissionRepository.CHANNEL_API);
    s.setSubmissionStatusSelect(status);
    s.setRelatedModel(BgD1Declaration.class.getName());
    s.setRelatedRecordId(d1Id);
    JPA.em().persist(s);
    NapSubmissionFile file = new NapSubmissionFile();
    file.setSubmission(s);
    file.setPayloadArtifact(payload);
    file.setSequence(0);
    file.setSubmittedFileName(payload.getOriginalFileName());
    JPA.em().persist(file);
    return s;
  }

  /**
   * Редът — „подписан“ по СТАРИЯ път (преди решението): файлът с отделен подпис и сертификат,
   * отпечатъците съвпадат, профилът на подписалия е записан. Само така проверката на
   * доказателствата пуска заявката до API-то; подпис в Nyxa вече няма.
   */
  private void signedLegacy(Long submissionId) {
    signedLegacy(submissionId, BgRoleLogin.createUser(company(), "Подписал по стария път"));
  }

  /** Същото, с профила на дадения потребител — когато той ще натисне бутона. */
  private void signedLegacy(Long submissionId, Long signer) {
    JPA.runInTransaction(
        () -> {
          UserQesProfile profile = new UserQesProfile();
          profile.setUser(JPA.em().find(com.axelor.auth.db.User.class, signer));
          profile.setProviderIdentifier("legacy");
          profile.setExternalProfileRef("legacy-" + signer);
          profile.setSignerPin("7501011234");
          profile.setSignerPinTypeSelect("IND_EGN");
          profile.setEnabled(Boolean.TRUE);
          JPA.em().persist(profile);

          NapSubmission s = JPA.em().find(NapSubmission.class, submissionId);
          s.setTaxpayerPin("831919536");
          s.setTaxpayerPinTypeSelect("BUS_BULSTAT");
          s.setQesSignerProfile(profile);
          // Артефактите — ПРЕДИ файловете: създаването им флъшва, а подписан файл (#334) вече не
          // сменя подписа си.
          NapArtifact cert = payload("signer.cer", "сертификат " + signer, s.getCompany());
          List<NapSubmissionFile> files =
              JPA.em()
                  .createQuery(
                      "SELECT f FROM NapSubmissionFile f WHERE f.submission.id = :s",
                      NapSubmissionFile.class)
                  .setParameter("s", submissionId)
                  .getResultList();
          Map<Long, NapArtifact> signatures = new HashMap<>();
          for (NapSubmissionFile file : files) {
            signatures.put(
                file.getId(), payload("EMPL2021.TXT.p7s", "pkcs7 " + file.getId(), s.getCompany()));
          }
          for (NapSubmissionFile file : files) {
            file.setFileDocumentTypeSelect(FileDocumentType.DEC_1.name());
            file.setFileTypeSelect("txt");
            file.setNumRecords(1);
            file.setQesRequestedSha256(file.getPayloadArtifact().getSha256());
            file.setSignatureArtifact(signatures.get(file.getId()));
            file.setSignerCertificateArtifact(cert);
            file.setSignerCertificateSha256(cert.getSha256());
            file.setQesSigningStatusSelect(NapSubmissionFileRepository.QES_STATUS_COMPLETED);
          }
        });
    // Статусът — в ОТДЕЛНА транзакция: слушателят на #334 съди за файла по статуса на подаването,
    // а подписаните файлове на подписано подаване вече не се пипат.
    JPA.runInTransaction(
        () ->
            JPA.em()
                .find(NapSubmission.class, submissionId)
                .setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SIGNED));
    JPA.em().clear();
  }

  /** Файлът — на дружеството, за което е (NapArtifact.company, както при генерирането). */
  private static NapArtifact payload(String name, String content, Company company) {
    try {
      return Beans.get(NapArtifactService.class)
          .createFromFile(
              new RegulatoryFile(
                  Institution.NAP, name, "text/plain", content.getBytes(StandardCharsets.UTF_8)),
              NapArtifactRepository.TYPE_PAYLOAD,
              company);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private Company company() {
    return JPA.em().find(Company.class, companyId);
  }

  private Company otherCompany() {
    return JPA.em().find(Company.class, otherCompanyId);
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
    year.setTypeSelect(2);
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

  private static NapSubmission submission(Long id) {
    return fresh(em -> em.find(NapSubmission.class, id));
  }

  private static Long artifactOf(Long submissionId) {
    return fresh(
        em ->
            em.createQuery(
                    "SELECT f.payloadArtifact.id FROM NapSubmissionFile f WHERE f.submission.id = :s",
                    Long.class)
                .setParameter("s", submissionId)
                .getSingleResult());
  }

  private static String shaOf(Long submissionId) {
    return fresh(em -> em.find(NapArtifact.class, artifactOf(submissionId))).getSha256();
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

  private static Long fileOf(Long submissionId) {
    return fresh(
        em ->
            em.createQuery(
                    "SELECT f.id FROM NapSubmissionFile f WHERE f.submission.id = :s", Long.class)
                .setParameter("s", submissionId)
                .getSingleResult());
  }

  private static Integer version(Class<? extends Model> model, Long id) {
    return fresh(em -> em.find(model, id).getVersion());
  }

  private static <T> T fresh(Function<EntityManager, T> read) {
    EntityManager em = JPA.em().getEntityManagerFactory().createEntityManager();
    try {
      return read.apply(em);
    } finally {
      em.close();
    }
  }
}

package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.noi.BgApp9SubmissionService;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationSubmissionService;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.napsubmissions.common.artifact.Institution;
import com.axelor.apps.napsubmissions.common.artifact.NapArtifactService;
import com.axelor.apps.napsubmissions.common.artifact.RegulatoryFile;
import com.axelor.apps.napsubmissions.common.immutability.RegulatoryImmutabilityException;
import com.axelor.apps.napsubmissions.common.submission.ManualSubmissionService;
import com.axelor.apps.napsubmissions.common.submission.NapSubmissionWorkflowService;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionFileRepository;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsSubmissionService;
import com.axelor.auth.AuthUtils;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.db.Model;
import com.axelor.inject.Beans;
import com.axelor.rpc.Request;
import com.axelor.rpc.Resource;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.util.Types;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ПОДАДЕНОТО ПОДАВАНЕ Е ДОКАЗАТЕЛСТВО — NapSubmission и NapSubmissionFile не се пренаписват и не се
 * трият през /ws/rest, през UI и през услугите, и за admin.
 *
 * <p>Пътищата на REST са ИСТИНСКИТЕ: {@link Resource#save} (flush ПРЕДИ {@code repository.save}),
 * {@link Resource#remove(long, Request)} и {@code removeAll}, {@link Resource#updateMass} (масов
 * JPQL, без PreUpdate) — от инжектора, с проверките на правата. Отказът трябва да е от
 * НЕИЗМЕНИМОСТТА ({@link RegulatoryImmutabilityException} във веригата на причините), не какъв да
 * е.
 *
 * <p>Позволеното след подаване (статус и резултат, входящ номер, протокол) минава — през REST и
 * през истинските услуги на процеса. Черновата се редактира и трие както досега.
 *
 * <p>Данните са измислени (без реални ЕИК, имена, адреси); базата е HSQLDB на класа.
 *
 * <p>⚠️ {@code @Tag("integration")}: пуска се през {@code integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgNapSubmissionImmutableRestTest extends JpaSupport {

  private String tag;
  private Long companyA;
  private Long companyB;
  private Long payload;
  private Long otherPayload;

  /** Подадено ръчно, с входящ номер, с един файл. */
  private Long filed;

  private Long filedFile;

  /** Прието, без файл. */
  private Long accepted;

  /** Чернова с един файл. */
  private Long draft;

  private Long draftFile;

  @BeforeEach
  void scene() {
    tag = String.valueOf(System.nanoTime());
    JPA.runInTransaction(
        () -> {
          try {
            Company a = company("А" + tag);
            companyA = a.getId();
            companyB = company("Б" + tag).getId();
            NapArtifactService service = Beans.get(NapArtifactService.class);
            NapArtifact p = service.createFromFile(file("EMPL-" + tag + ".TXT"), payloadType(), a);
            payload = p.getId();
            otherPayload =
                service.createFromFile(file("forged-" + tag + ".TXT"), payloadType(), a).getId();

            // Както го прави процесът (Прил. 9, декларациите): чернова → файл → „подадено“.
            NapSubmission s = submission(a, "Подадено " + tag);
            NapSubmissionFile f = submissionFile(s, p);
            NapSubmission d = submission(a, "Чернова " + tag);
            NapSubmissionFile df = submissionFile(d, p);
            JPA.em().flush();
            s.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_SUBMITTED);
            s.setNapIncomingNumber("ТЕСТ-" + tag);
            s.setSubmittedOn(LocalDateTime.of(2026, 10, 1, 0, 0));

            NapSubmission acc = submission(a, "Прието " + tag);
            acc.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_ACCEPTED);
            acc.setNapIncomingNumber("ТЕСТ-П-" + tag);
            JPA.em().flush();

            filed = s.getId();
            filedFile = f.getId();
            draft = d.getId();
            draftFile = df.getId();
            accepted = acc.getId();
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
    JPA.clear();
    BgRoleLogin.loginAdmin(JPA.em().find(Company.class, companyA));
  }

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  // --- сцена ----------------------------------------------------------------------------------

  private static String payloadType() {
    return NapArtifactRepository.TYPE_PAYLOAD;
  }

  private static Company company(String name) {
    Company company = new Company();
    company.setName("Фирма " + name);
    company.setCode("SI" + name);
    JPA.em().persist(company);
    return company;
  }

  private static RegulatoryFile file(String name) {
    return new RegulatoryFile(Institution.NAP, name, null, name.getBytes(StandardCharsets.UTF_8));
  }

  private static NapSubmission submission(Company company, String name) {
    NapSubmission submission = new NapSubmission();
    submission.setCompany(company);
    submission.setName(name);
    submission.setServiceCodeSelect(NapSubmissionRepository.SERVICE_DECLARATIONS);
    submission.setChannelSelect(NapSubmissionRepository.CHANNEL_MANUAL_PORTAL);
    submission.setTaxpayerPin("000000000");
    submission.setReferenceDate(LocalDate.of(2026, 9, 1));
    JPA.em().persist(submission);
    return submission;
  }

  private static NapSubmissionFile submissionFile(NapSubmission submission, NapArtifact payload) {
    NapSubmissionFile file = new NapSubmissionFile();
    file.setSubmission(submission);
    file.setPayloadArtifact(payload);
    file.setSubmittedFileName(payload.getName());
    file.setFileSize(payload.getByteLength());
    file.setQesRequestedSha256(payload.getSha256());
    file.setSignerCertificateSha256("c".repeat(64));
    file.setFileStatusSelect(NapSubmissionFileRepository.FILE_STATUS_PENDING);
    JPA.em().persist(file);
    return file;
  }

  // --- REST, както го праща клиентът --------------------------------------------------------

  private static Map<String, Object> ref(Long id) {
    Map<String, Object> ref = new HashMap<>();
    ref.put("id", id);
    return ref;
  }

  @SuppressWarnings("unchecked")
  private static <T extends Model> Resource<T> rest(Class<T> model) {
    return (Resource<T>)
        Beans.get(Injector.class)
            .getInstance(Key.get(Types.newParameterizedType(Resource.class, model)));
  }

  private interface Call {
    void run() throws Exception;
  }

  /** Изпълнява заявката с чист контекст; връща грешката или {@code null}. */
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

  private static Integer version(Class<? extends Model> model, Long id) {
    Integer version = JPA.em().find(model, id).getVersion();
    JPA.clear();
    return version;
  }

  /** {@code POST /ws/rest/<model>/<id>} с едно поле. */
  private static <T extends Model> Throwable save(
      Class<T> model, Long id, String field, Object value) {
    Map<String, Object> data = new HashMap<>();
    data.put("id", id);
    data.put("version", version(model, id));
    data.put(field, value);
    return create(model, data);
  }

  /** {@code POST /ws/rest/<model>} — запис по данните (нов, ако няма id). */
  private static <T extends Model> Throwable create(Class<T> model, Map<String, Object> data) {
    Request request = new Request();
    request.setModel(model.getName());
    request.setData(data);
    return call(() -> rest(model).save(request));
  }

  /** {@code DELETE /ws/rest/<model>/<id>}. */
  private static <T extends Model> Throwable remove(Class<T> model, Long id) {
    Map<String, Object> data = new HashMap<>();
    data.put("version", version(model, id));
    Request request = new Request();
    request.setModel(model.getName());
    request.setData(data);
    return call(() -> rest(model).remove(id, request));
  }

  /** {@code POST /ws/rest/<model>/removeAll} — масово триене. */
  private static <T extends Model> Throwable removeAll(Class<T> model, Long... ids) {
    List<Object> records = new ArrayList<>();
    for (Long id : ids) {
      Map<String, Object> record = ref(id);
      record.put("version", version(model, id));
      records.add(record);
    }
    Request request = new Request();
    request.setModel(model.getName());
    request.setRecords(records);
    return call(() -> rest(model).remove(request));
  }

  /**
   * {@code POST /ws/rest/<model>/updateMass} — както от решетката (axelor-front {@code grid.tsx}):
   * стойностите в {@code records[0]}, филтърът „избраните“ в {@code data}.
   */
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

  private static void assertRefusal(Throwable t, String token) {
    assertNotNull(t, "заявката мина — " + token);
    for (Throwable c = t; c != null; c = c.getCause()) {
      if (c instanceof RegulatoryImmutabilityException) {
        assertTrue(c.getMessage().contains(token), c.getMessage());
        return;
      }
    }
    throw new AssertionError("отказът не е от неизменимостта („" + token + "“), а: " + t, t);
  }

  private static void assertPassed(Throwable t, String what) {
    if (t != null) {
      throw new AssertionError(what + " — отказано: " + t, t);
    }
  }

  private static NapSubmission submission(Long id) {
    return JPA.em().find(NapSubmission.class, id);
  }

  private static NapSubmissionFile submissionFile(Long id) {
    return JPA.em().find(NapSubmissionFile.class, id);
  }

  private static long filesOf(Long submissionId) {
    return JPA.all(NapSubmissionFile.class)
        .filter("self.submission.id = :id")
        .bind("id", submissionId)
        .count();
  }

  // --- триене ---------------------------------------------------------------------------------

  @Test
  @DisplayName("Admin трие подадено подаване през /ws/rest → отказ; подаването и файлът остават")
  void adminCannotDeleteASubmittedSubmission() {
    assertRefusal(remove(NapSubmission.class, filed), "submitted НАП submission");
    assertNotNull(submission(filed));
    assertEquals(1, filesOf(filed));
  }

  @Test
  @DisplayName(
      "Admin трие масово (removeAll) подадено, прието и чернова → отказ; нищо не е изтрито")
  void adminCannotDeleteSubmittedSubmissionsInBulk() {
    assertRefusal(
        removeAll(NapSubmission.class, accepted, filed, draft), "submitted НАП submission");
    assertNotNull(submission(accepted));
    assertNotNull(submission(filed));
    assertNotNull(submission(draft), "отказът е за цялата заявка");
  }

  @Test
  @DisplayName("Admin трие файл на подадено подаване (един и масово) → отказ")
  void adminCannotDeleteAFileOfASubmittedSubmission() {
    // ⚠️ Триенето на файл го пази и репозиторито (Resource.remove минава през него) —
    // тук е за да не се изгуби; общата дума на двата отказа е „submission“.
    assertRefusal(remove(NapSubmissionFile.class, filedFile), "submission");
    assertRefusal(removeAll(NapSubmissionFile.class, filedFile, draftFile), "submission");
    assertNotNull(submissionFile(filedFile));
    assertNotNull(submissionFile(draftFile));
  }

  // --- файлът на подаденото -------------------------------------------------------------------

  @Test
  @DisplayName("Admin подменя файла (payloadArtifact) на подаден файл през REST → отказ")
  void adminCannotSwapTheFileOfASubmittedFile() {
    assertRefusal(
        save(NapSubmissionFile.class, filedFile, "payloadArtifact", ref(otherPayload)),
        "payloadArtifact");
    assertEquals(payload, submissionFile(filedFile).getPayloadArtifact().getId());
  }

  @Test
  @DisplayName("Admin сменя SHA-256 на подаден файл (поискан и на сертификата) през REST → отказ")
  void adminCannotRewriteTheSha256OfASubmittedFile() {
    String requested = submissionFile(filedFile).getQesRequestedSha256();
    JPA.clear();
    assertRefusal(
        save(NapSubmissionFile.class, filedFile, "qesRequestedSha256", "0".repeat(64)),
        "qesRequestedSha256");
    assertRefusal(
        save(NapSubmissionFile.class, filedFile, "signerCertificateSha256", "0".repeat(64)),
        "signerCertificateSha256");
    assertEquals(requested, submissionFile(filedFile).getQesRequestedSha256());
    assertEquals("c".repeat(64), submissionFile(filedFile).getSignerCertificateSha256());
  }

  @Test
  @DisplayName("Admin сменя SHA-256 на самия подаден файл (NapArtifact) през REST → отказ")
  void adminCannotRewriteTheSha256OfTheSubmittedPayload() {
    assertRefusal(save(NapArtifact.class, payload, "sha256", "0".repeat(64)), "sha256");
  }

  @Test
  @DisplayName("Admin добавя файл към подадено подаване през REST → отказ")
  void adminCannotAddAFileToASubmittedSubmission() {
    Map<String, Object> data = new HashMap<>();
    data.put("submission", ref(filed));
    data.put("payloadArtifact", ref(otherPayload));
    data.put("submittedFileName", "добавен.TXT");
    assertRefusal(create(NapSubmissionFile.class, data), "submitted НАП submission");
    assertEquals(1, filesOf(filed));
  }

  @Test
  @DisplayName("Admin мести файл на чернова в подадено подаване през REST → отказ")
  void adminCannotMoveAFileIntoASubmittedSubmission() {
    assertRefusal(
        save(NapSubmissionFile.class, draftFile, "submission", ref(filed)),
        "submitted НАП submission");
    assertEquals(1, filesOf(filed));
  }

  // --- самото подаване ------------------------------------------------------------------------

  @Test
  @DisplayName("Admin пренаписва подадено подаване през REST (дата, ЕИК, канал, дружество) → отказ")
  void adminCannotRewriteASubmittedSubmission() {
    assertRefusal(save(NapSubmission.class, filed, "referenceDate", "2026-01-01"), "referenceDate");
    assertRefusal(save(NapSubmission.class, filed, "taxpayerPin", "111111111"), "taxpayerPin");
    assertRefusal(
        save(NapSubmission.class, filed, "channelSelect", NapSubmissionRepository.CHANNEL_API),
        "channelSelect");
    assertRefusal(save(NapSubmission.class, filed, "company", ref(companyB)), "company");
    NapSubmission s = submission(filed);
    assertEquals(LocalDate.of(2026, 9, 1), s.getReferenceDate());
    assertEquals("000000000", s.getTaxpayerPin());
    assertEquals(companyA, s.getCompany().getId());
  }

  @Test
  @DisplayName("Admin връща подадено подаване в чернова през REST → отказ")
  void adminCannotReturnASubmittedSubmissionToDraft() {
    assertRefusal(
        save(
            NapSubmission.class,
            filed,
            "submissionStatusSelect",
            NapSubmissionRepository.STATUS_DRAFT),
        "submissionStatusSelect");
    assertEquals(
        NapSubmissionRepository.STATUS_SUBMITTED, submission(filed).getSubmissionStatusSelect());
  }

  @Test
  @DisplayName("Услуга сменя момента на подаване на зареден запис без save → отказ при flush")
  void aServiceCannotRewriteTheMomentOfSubmissionEitherAtFlush() {
    Throwable t =
        call(
            () ->
                JPA.runInTransaction(
                    () -> submission(filed).setSubmittedOn(LocalDateTime.of(2026, 10, 20, 0, 0))));
    assertRefusal(t, "submittedOn");
    assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0), submission(filed).getSubmittedOn());
  }

  // --- масово обновяване ----------------------------------------------------------------------

  @Test
  @DisplayName("Admin обновява масово подадено подаване (updateMass) → отказ")
  void adminCannotMassUpdateASubmittedSubmission() {
    assertRefusal(
        massUpdate(NapSubmission.class, "taxpayerPin", "111111111", filed, draft),
        "submitted НАП submissions");
    assertEquals("000000000", submission(filed).getTaxpayerPin());
    assertEquals("000000000", submission(draft).getTaxpayerPin(), "отказът е за цялата заявка");
    assertRefusal(
        massUpdate(
            NapSubmission.class,
            "submissionStatusSelect",
            NapSubmissionRepository.STATUS_DRAFT,
            accepted),
        "status");
    assertEquals(
        NapSubmissionRepository.STATUS_ACCEPTED, submission(accepted).getSubmissionStatusSelect());
  }

  @Test
  @DisplayName("Admin обновява масово файл на подадено подаване (updateMass) → отказ")
  void adminCannotMassUpdateAFileOfASubmittedSubmission() {
    String name = submissionFile(filedFile).getSubmittedFileName();
    JPA.clear();
    assertRefusal(
        massUpdate(NapSubmissionFile.class, "submittedFileName", "друго.TXT", filedFile),
        "submitted НАП submission");
    assertEquals(name, submissionFile(filedFile).getSubmittedFileName());
  }

  @Test
  @DisplayName("Admin обновява масово SHA-256 на подаден файл (NapArtifact, updateMass) → отказ")
  void adminCannotMassUpdateTheSha256OfTheSubmittedPayload() {
    String before = JPA.em().find(NapArtifact.class, payload).getSha256();
    JPA.clear();
    assertRefusal(massUpdate(NapArtifact.class, "sha256", "0".repeat(64), payload), "sha256");
    assertEquals(before, JPA.em().find(NapArtifact.class, payload).getSha256());
  }

  // --- позволеното след подаване --------------------------------------------------------------

  @Test
  @DisplayName("Резултатът и входящият номер след подаване: през REST — минават")
  void theOutcomeIsWrittenThroughRest() {
    assertPassed(
        save(NapSubmission.class, filed, "napIncomingNumber", "ТЕСТ-2-" + tag), "входящ номер");
    assertPassed(save(NapSubmission.class, filed, "externalStatus", "accepted"), "външен статус");
    assertPassed(save(NapSubmission.class, filed, "responseMeta", "протокол № 1"), "протокол");
    assertPassed(save(NapSubmission.class, filed, "errorMessage", "ред 3"), "грешка");
    assertPassed(save(NapSubmission.class, filed, "correctionReason", "бележка"), "бележка");
    assertPassed(
        save(
            NapSubmission.class,
            filed,
            "submissionStatusSelect",
            NapSubmissionRepository.STATUS_ACCEPTED),
        "статус");
    assertPassed(
        save(
            NapSubmissionFile.class,
            filedFile,
            "fileStatusSelect",
            NapSubmissionFileRepository.FILE_STATUS_ACCEPTED),
        "изход за файла");
    NapSubmission s = submission(filed);
    assertEquals("ТЕСТ-2-" + tag, s.getNapIncomingNumber());
    assertEquals("протокол № 1", s.getResponseMeta());
    assertEquals(NapSubmissionRepository.STATUS_ACCEPTED, s.getSubmissionStatusSelect());
    assertEquals(
        NapSubmissionFileRepository.FILE_STATUS_ACCEPTED,
        submissionFile(filedFile).getFileStatusSelect());
  }

  @Test
  @DisplayName("Процесът след подаване: прието, отхвърлено, корекция, отмяна (Прил. 9) — минават")
  void theProcessStillWritesTheOutcome() throws Exception {
    // НАП — ръчно: прието с номер и дата на отговора (декларации по ЗДДФЛ)
    Beans.get(BgDeclarationSubmissionService.class)
        .markAccepted(submission(filed), "ТЕСТ-3-" + tag, LocalDate.of(2026, 10, 2), "протокол");
    JPA.clear();
    assertEquals(
        NapSubmissionRepository.STATUS_ACCEPTED, submission(filed).getSubmissionStatusSelect());
    JPA.clear();

    // НАП — „иска корекция“ след прието
    Beans.get(ManualSubmissionService.class).recordCorrectionRequired(submission(accepted), "x");
    JPA.clear();
    assertEquals(
        NapSubmissionRepository.STATUS_CORRECTION_REQUIRED,
        submission(accepted).getSubmissionStatusSelect());
    JPA.clear();

    // общият поток: прието (SAF-T/ръчно)
    Long rejected = persisted(NapSubmissionRepository.STATUS_SUBMITTED, "ТЕСТ-4-" + tag, null);
    Beans.get(NapSubmissionWorkflowService.class).recordRejected(submission(rejected), "грешка");
    JPA.clear();
    assertEquals(
        NapSubmissionRepository.STATUS_REJECTED, submission(rejected).getSubmissionStatusSelect());
    JPA.clear();

    // API: неизвестен изход, решен ръчно с бележка
    Long unknown = persisted(NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN, null, null);
    Beans.get(DeclarationsSubmissionService.class)
        .manuallyResolveOutcomeUnknown(
            submission(unknown), NapSubmissionRepository.STATUS_SUBMITTED, "сверено в портала");
    JPA.clear();
    assertEquals(
        NapSubmissionRepository.STATUS_SUBMITTED, submission(unknown).getSubmissionStatusSelect());
    JPA.clear();

    // повторно, ръчно, след техническа грешка без номер: каналът, моментът и кой — пишат се
    Long technical = persisted(NapSubmissionRepository.STATUS_TECHNICAL_ERROR, null, null);
    Beans.get(ManualSubmissionService.class)
        .recordManualSubmission(
            submission(technical), "ТЕСТ-5-" + tag, LocalDate.now().minusDays(1));
    JPA.clear();
    NapSubmission resent = submission(technical);
    assertEquals(NapSubmissionRepository.STATUS_SUBMITTED, resent.getSubmissionStatusSelect());
    assertNotNull(resent.getSubmittedOn());
    JPA.clear();

    // НОИ, Прил. 9: отмяна на отметката, с причина — статусът става „отменена“, нищо не се трие
    Long app9 =
        persisted(
            NapSubmissionRepository.STATUS_SUBMITTED,
            "ТЕСТ-6-" + tag,
            NapSubmissionRepository.SERVICE_NOI_APP9);
    Beans.get(BgApp9SubmissionService.class)
        .withdraw(submission(app9), "грешен файл", AuthUtils.getUser());
    JPA.clear();
    assertEquals(
        NapSubmissionRepository.STATUS_WITHDRAWN, submission(app9).getSubmissionStatusSelect());
  }

  /** Подаване в даден статус — създадено направо (създаването не е промяна). */
  private Long persisted(String status, String number, String service) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          NapSubmission s = submission(JPA.em().find(Company.class, companyA), status + tag);
          s.setSubmissionStatusSelect(status);
          s.setNapIncomingNumber(number);
          if (service != null) {
            s.setServiceCodeSelect(service);
          }
          JPA.em().flush();
          id[0] = s.getId();
        });
    JPA.clear();
    return id[0];
  }

  // --- черновата — както досега ---------------------------------------------------------------

  @Test
  @DisplayName("Чернова: редакция, масово обновяване, нов файл, триене на файл и на подаването")
  void aDraftIsEditedAndDeletedAsBefore() {
    assertPassed(save(NapSubmission.class, draft, "referenceDate", "2026-01-01"), "дата");
    assertPassed(save(NapSubmission.class, draft, "company", ref(companyB)), "дружество");
    assertPassed(
        massUpdate(NapSubmission.class, "taxpayerPin", "222222222", draft), "масово обновяване");
    assertPassed(
        save(NapSubmissionFile.class, draftFile, "payloadArtifact", ref(otherPayload)),
        "смяна на файла");
    Map<String, Object> data = new HashMap<>();
    data.put("submission", ref(draft));
    data.put("payloadArtifact", ref(payload));
    data.put("submittedFileName", "втори.TXT");
    assertPassed(create(NapSubmissionFile.class, data), "нов файл");
    assertEquals(2, filesOf(draft));
    JPA.clear();
    NapSubmission d = submission(draft);
    assertEquals(LocalDate.of(2026, 1, 1), d.getReferenceDate());
    assertEquals(companyB, d.getCompany().getId());
    assertEquals("222222222", d.getTaxpayerPin());
    JPA.clear();

    assertPassed(remove(NapSubmissionFile.class, draftFile), "триене на файл");
    assertEquals(1, filesOf(draft));
    assertPassed(remove(NapSubmission.class, draft), "триене на черновата");
    assertNull(submission(draft));
    assertEquals(0, filesOf(draft));
  }
}

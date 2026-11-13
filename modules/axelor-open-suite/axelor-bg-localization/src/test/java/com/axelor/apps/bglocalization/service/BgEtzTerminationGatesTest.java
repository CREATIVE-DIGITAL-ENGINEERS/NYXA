package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgContractTermination;
import com.axelor.apps.bglocalization.db.BgNkid;
import com.axelor.apps.bglocalization.db.BgNkpd;
import com.axelor.apps.bglocalization.db.repo.BgContractTerminationRepository;
import com.axelor.apps.bglocalization.service.etz.BgEtzEmitter;
import com.axelor.apps.bglocalization.service.etz.BgEtzEventKind;
import com.axelor.apps.bglocalization.service.etz.BgEtzExportService;
import com.axelor.apps.bglocalization.service.etz.BgEtzFacts;
import com.axelor.apps.bglocalization.service.etz.BgEtzFile;
import com.axelor.apps.bglocalization.service.etz.BgEtzGap;
import com.axelor.apps.bglocalization.service.etz.BgEtzResolver;
import com.axelor.apps.bglocalization.service.etz.BgEtzSubmissionService;
import com.axelor.apps.bglocalization.service.etz.BgEtzTerminationInput;
import com.axelor.apps.bglocalization.service.etz.BgEtzTerminationService;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.bglocalization.testsupport.BgTestApp;
import com.axelor.apps.bglocalization.web.BgEtzController;
import com.axelor.apps.bglocalization.web.BgEtzTerminationController;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.hr.db.EndOfContractReason;
import com.axelor.apps.hr.db.repo.EmploymentContractRepository;
import com.axelor.apps.hr.db.repo.EndOfContractReasonRepository;
import com.axelor.apps.napsubmissions.common.submission.NapFilingLock;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.web.NapSubmissionController;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.db.Model;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
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
 * ЕТЗ ЗА ПРЕКРАТЯВАНЕ — свидетелите по РЕАЛНИЯ път: контролерът, гейтът, хранилищата, с роли {@code
 * all.write} / {@code all.read}.
 *
 * <p>Данните са ИЗМИСЛЕНИ: „Нюкса Тест ЕООД“, лицето P0043 с изчислен (несъществуващ) ЕГН.
 *
 * <p>⚠️ Генерирането за прекратяване отказва, докато т. 25/30/31 не се изчисляват. Подаденото
 * прекратяване за свидетелите на заключването се ПРАВИ ОТ ИСТИНСКИ ФАЙЛ: записът е сглобен с
 * изричните т. 25/30 (мястото на бъдещото изчисление), закачен за записа за прекратяване и
 * отбелязан през истинския „Запиши ръчно подаване“.
 *
 * <p>⚠️ КОНТЕКСТЪТ НЕ СЕ ЧИСТИ — както в {@link BgEtzFilingGatesTest}: Partner носи @Formula, която
 * HSQLDB не изпълнява. Отказаният запис се връща на ръка в паметта.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgFilingGatesTest.Wiring.class)
@Tag("integration")
class BgEtzTerminationGatesTest extends JpaSupport {

  private static final String NOT_ALLOWED = "You are not allowed to ";
  private static final String ACTIVE_COMPANY = "active company";
  private static final String ALL_WRITE = "all.write";
  private static final String ALL_READ = "all.read";
  private static final String EIK = "831919536";
  private static final String EGN = "8501010006";
  // ⚠️ И ДВЕТЕ В МИНАЛОТО: ManualSubmissionService отказва дата от портала в бъдещето.
  private static final LocalDate TERMINATED = LocalDate.of(2026, 10, 20);
  private static final LocalDate PORTAL_DATE = LocalDate.of(2026, 10, 24);

  private Long companyId;
  private Long otherCompanyId;
  private Long contractId;
  private Long reasonId;
  private Long otherReasonId;
  private Long preEuroContractId;

  @BeforeEach
  void scene() {
    BgRoleLogin.createUser(null, "роли за ЕТЗ прекратяване", ALL_WRITE, ALL_READ);
    String tag = String.valueOf(System.nanoTime());
    JPA.runInTransaction(
        () -> {
          Company company = company("Нюкса Тест ЕООД " + tag, "NT" + tag, EIK);
          companyId = company.getId();
          otherCompanyId = company("Нюкса Друго ЕООД " + tag, "ND" + tag, "121817309").getId();
          BgTestApp.ensureAppBase();
          EndOfContractReason reason = reason("Съкращаване " + tag, "328_1_2");
          reasonId = reason.getId();
          otherReasonId = reason("Взаимно съгласие " + tag, "325_1_1").getId();
          EmploymentContract contract =
              contract(company, "P0043 " + tag, LocalDate.of(2026, 3, 2), "68_1_1");
          contract.setEndOfContractReason(reason);
          contract.setBgTerminationDate(TERMINATED);
          contractId = contract.getId();
          preEuroContractId =
              contract(company, "P0043 сключен 2025 " + tag, LocalDate.of(2025, 12, 20), "67")
                  .getId();
        });
  }

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  // ═══ ТАБЛИЦАТА ═══════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "Чиста база: таблицата bglocalization_bg_contract_termination и уникалното по договор ги"
          + " прави ddl=update")
  void theTableIsCreated() {
    Number tables =
        (Number)
            JPA.em()
                .createNativeQuery(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES"
                        + " WHERE UPPER(TABLE_NAME) = 'BGLOCALIZATION_BG_CONTRACT_TERMINATION'")
                .getSingleResult();
    assertEquals(1, tables.intValue(), "таблицата на записа за прекратяване");
    Number uniques =
        (Number)
            JPA.em()
                .createNativeQuery(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS"
                        + " WHERE UPPER(CONSTRAINT_NAME) = 'UK_BG_CONTRACT_TERMINATION_CONTRACT'")
                .getSingleResult();
    assertEquals(1, uniques.intValue(), "един договор — един запис");
  }

  // ═══ ЗАПИСЪТ И ДАТАТА ════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "all.write: запис от „Дата на действително прекратяване“; „Подай до“ = +7 календарни дни;"
          + " генерирането отказва открито „т. 25/30/31 още не се изчисляват“ и не записва нищо")
  void theRecordCarriesTheDateAndTheDeadline() {
    BgRoleLogin.login(company(), ALL_WRITE);
    String created = press(EmploymentContract.class, contractId, null, end()::create);
    assertFalse(created.contains("error=") || created.contains("info="), created);
    BgContractTermination t = termination();
    assertNotNull(t, "записът е създаден");
    assertEquals(TERMINATED, t.getTerminationDate());
    assertFalse(Boolean.TRUE.equals(t.getFromAgreedEnd()));

    String status = statusText();
    assertTrue(status.contains("Подай до 27.10.2026"), "20.10 + 7 дни: " + status);

    String generated = press(EmploymentContract.class, contractId, null, end()::generate);
    assertTrue(generated.contains(BgEtzTerminationService.NOT_COMPUTED), generated);
    assertTrue(submissions(t.getId()).isEmpty(), "нищо не е записано");
  }

  @Test
  @DisplayName(
      "Срочен договор без изрична дата: записът взима уговорения край, казва го изрично и го"
          + " записва в договора")
  void theAgreedEndIsShownExplicitly() {
    LocalDate agreedEnd = LocalDate.of(2026, 12, 31);
    JPA.runInTransaction(
        () -> {
          EmploymentContract c = JPA.em().find(EmploymentContract.class, contractId);
          c.setBgTerminationDate(null);
          c.setEndDate(agreedEnd);
        });
    BgRoleLogin.login(company(), ALL_WRITE);
    String created = press(EmploymentContract.class, contractId, null, end()::create);
    assertTrue(created.contains("уговорения край"), created);
    BgContractTermination t = termination();
    assertEquals(agreedEnd, t.getTerminationDate());
    assertTrue(Boolean.TRUE.equals(t.getFromAgreedEnd()));
    assertEquals(agreedEnd, contractDate(), "датата е записана в договора");
    assertTrue(statusText().contains("уговореният край"), "панелът го казва");
  }

  @Test
  @DisplayName("Има ли запис, датата в договора се сменя САМО през него")
  void theDateChangesOnlyThroughTheRecord() {
    BgRoleLogin.login(company(), ALL_WRITE);
    press(EmploymentContract.class, contractId, null, end()::create);
    // През записа — минава и стига до договора.
    JPA.runInTransaction(
        () -> {
          BgContractTermination t = termination();
          t.setTerminationDate(TERMINATED.plusDays(2));
          Beans.get(BgContractTerminationRepository.class).save(t);
        });
    assertEquals(TERMINATED.plusDays(2), contractDate());

    // ⚠️ Отказаният запис — ПОСЛЕДЕН: върнатата транзакция изпразва контекста (HSQLDB и Partner).
    RuntimeException refused = changeContractDate(TERMINATED.plusDays(5));
    assertNotNull(refused, "отказ");
    assertTrue(
        refused.getMessage().contains(BgEtzTerminationService.DATE_ONLY_VIA_RECORD),
        refused.getMessage());
    assertEquals(TERMINATED.plusDays(2), contractDate(), "непокътната");
  }

  // ═══ ОТКАЗИТЕ ════════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "Липсващо основание: генерирането назовава т. 27 и екрана, на който се попълва — и т."
          + " 25/30/31")
  void aMissingGroundIsNamed() {
    JPA.runInTransaction(
        () -> JPA.em().find(EmploymentContract.class, contractId).setEndOfContractReason(null));
    BgRoleLogin.login(company(), ALL_WRITE);
    press(EmploymentContract.class, contractId, null, end()::create);
    String text = press(EmploymentContract.class, contractId, null, end()::generate);
    assertTrue(text.contains("т. 27 (Основание за прекратяване)"), text);
    assertTrue(text.contains("„Основание (член)“"), "екранът: " + text);
    assertTrue(text.contains(BgEtzTerminationService.NOT_COMPUTED), text);
  }

  @Test
  @DisplayName("all.write с А и Б, активна Б: записът и генерирането за договор на А — отказ")
  void anotherActiveCompanyIsRefused() {
    BgRoleLogin.login(company(), ALL_WRITE);
    press(EmploymentContract.class, contractId, null, end()::create);
    BgRoleLogin.logout();
    BgRoleLogin.login(otherCompany(), Set.of(company()), ALL_WRITE);
    String generated = press(EmploymentContract.class, contractId, null, end()::generate);
    assertTrue(generated.contains(ACTIVE_COMPANY), "генериране: " + generated);
    String created = press(EmploymentContract.class, preEuroContractId, null, end()::create);
    assertTrue(created.contains(ACTIVE_COMPANY), "запис: " + created);
    assertNull(BgEtzTerminationService.recordOf(preEuroContractId), "нищо не е създадено");
  }

  @Test
  @DisplayName(
      "all.read: нито запис, нито генериране — отказ, който назовава hr.payroll.write; вижда"
          + " панела")
  void readOnlyCannotGenerate() {
    BgRoleLogin.login(company(), ALL_READ);
    String created = press(EmploymentContract.class, contractId, null, end()::create);
    assertTrue(
        created.contains(NOT_ALLOWED + "create the termination record")
            && created.contains("hr.payroll.write"),
        created);
    String generated = press(EmploymentContract.class, contractId, null, end()::generate);
    assertTrue(
        generated.contains(NOT_ALLOWED + "generate an ETZ termination record")
            && generated.contains("hr.payroll.write"),
        generated);
    assertNull(BgEtzTerminationService.recordOf(contractId));
    String panel = press(EmploymentContract.class, contractId, null, end()::load);
    assertTrue(panel.contains("bgEtzEndPanel"), panel);
    assertFalse(panel.contains("error="), panel);
  }

  @Test
  @DisplayName(
      "1в по реалния път на сключването: договор, подписан на 20.12.2025, с т. 18 → „Събитието е"
          + " преди 01.01.2026 – подай ръчно в портала на НАП“; нищо не е записано")
  void anEventBeforeTheEuroIsRefused() {
    BgRoleLogin.login(company(), ALL_WRITE);
    String text =
        press(
            EmploymentContract.class,
            preEuroContractId,
            null,
            Beans.get(BgEtzController.class)::generate);
    assertTrue(text.contains(BgEtzExportService.BEFORE_EURO_REFUSAL), text);
    assertTrue(
        fresh(
                em ->
                    em.createQuery(
                            "SELECT s FROM NapSubmission s WHERE s.relatedModel = :m"
                                + " AND s.relatedRecordId = :id",
                            NapSubmission.class)
                        .setParameter("m", EmploymentContract.class.getName())
                        .setParameter("id", preEuroContractId)
                        .getResultList())
            .isEmpty(),
        "нищо не е записано");
  }

  // ═══ ПОДАДЕНОТО ПРЕКРАТЯВАНЕ ═════════════════════════════════════════════

  @Test
  @DisplayName(
      "Подаден ЕТЗ за прекратяване (истински файл → „Запиши ръчно подаване“): файлът носи т. 26 и"
          + " т. 27; датата, основанието в договора и в речника — отказ „…промяната става с"
          + " корекция“")
  void afterFilingTheDateAndTheGroundAreLocked() {
    BgRoleLogin.login(company(), ALL_WRITE);
    press(EmploymentContract.class, contractId, null, end()::create);
    Long draftId = draft();

    // Свалянето с all.read — файлът, с датата и основанието.
    BgRoleLogin.logout();
    BgRoleLogin.login(company(), ALL_READ);
    String download = press(EmploymentContract.class, contractId, null, end()::download);
    assertTrue(download.contains("/content/download"), download);
    String recordAsRead = manual("НАП-ЧЕТЕНЕ");
    assertTrue(recordAsRead.contains("hr.payroll.write"), "all.read не подава: " + recordAsRead);
    BgRoleLogin.logout();

    BgRoleLogin.login(company(), ALL_WRITE);
    String recorded = manual("НАП-ЕТЗ-КРАЙ-1");
    // Отказ през TraceBackService идва като info=, не като error= — и двете са провал тук.
    assertFalse(recorded.contains("error=") || recorded.contains("info="), recorded);
    NapSubmission filed = fresh(em -> em.find(NapSubmission.class, draftId));
    assertEquals(NapSubmissionRepository.STATUS_SUBMITTED, filed.getSubmissionStatusSelect());
    // Закачалката е класът на записа + неговото id — не договорът и не низ от вида „Модел#id“.
    assertEquals(BgContractTermination.class.getName(), filed.getRelatedModel());
    assertEquals(termination().getId(), filed.getRelatedRecordId());

    // Датата в договора.
    RuntimeException date = changeContractDate(TERMINATED.plusDays(3));
    assertNotNull(date, "датата — отказ");
    assertEquals(BgEtzTerminationService.FILED_LOCKED, date.getMessage());
    // Основанието в договора.
    RuntimeException ground = changeContractReason(otherReasonId);
    assertNotNull(ground, "основанието — отказ");
    assertEquals(BgEtzTerminationService.FILED_LOCKED, ground.getMessage());
    // Датата през записа.
    RuntimeException viaRecord = changeRecordDate(TERMINATED.plusDays(4));
    assertNotNull(viaRecord, "и през записа — отказ");
    assertEquals(BgEtzTerminationService.FILED_LOCKED, viaRecord.getMessage());
    // Основанието в речника, сочено от договора.
    RuntimeException dictionary = changeReasonGround("328_1_3");
    assertNotNull(dictionary, "речникът — отказ");
    assertEquals(BgEtzTerminationService.FILED_LOCKED, dictionary.getMessage());

    assertEquals(TERMINATED, contractDate(), "датата е непокътната");
  }

  @Test
  @DisplayName("Канал API за ЕТЗ за прекратяване: „Подпиши и изпрати“ — отказ „без КЕП …“")
  void theApiChannelIsRefused() {
    BgRoleLogin.login(company(), ALL_WRITE);
    press(EmploymentContract.class, contractId, null, end()::create);
    Long draftId = draft();
    Integer before = version(NapSubmission.class, draftId);
    String sign =
        press(
            NapSubmission.class,
            draftId,
            null,
            Beans.get(NapSubmissionController.class)::signAndSubmit);
    assertTrue(sign.contains(NapFilingLock.MANUAL_ONLY_REFUSAL), sign);
    assertEquals(before, version(NapSubmission.class, draftId), "непокътнато");
  }

  // ═══ ПОМОЩНИЦИТЕ ═════════════════════════════════════════════════════════

  private static BgEtzTerminationController end() {
    return Beans.get(BgEtzTerminationController.class);
  }

  private BgContractTermination termination() {
    return BgEtzTerminationService.recordOf(contractId);
  }

  private String statusText() {
    EmploymentContract contract = JPA.em().find(EmploymentContract.class, contractId);
    return Beans.get(BgEtzTerminationService.class).status(contract).getText();
  }

  /**
   * Чернова за прекратяването от ИСТИНСКИ запис: резолверът с изричните т. 25/30 (мястото на
   * бъдещото изчисление), емитерът и закачане за записа за прекратяване.
   */
  private Long draft() {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          EmploymentContract contract = JPA.em().find(EmploymentContract.class, contractId);
          List<BgEtzGap> gaps = new ArrayList<>();
          BgEtzFile file =
              new BgEtzResolver()
                  .resolve(
                      BgEtzFacts.of(contract),
                      BgEtzEventKind.TERMINATION,
                      gaps,
                      new BgEtzTerminationInput()
                          .setUsedVacationDays(15)
                          .setRecognisedExperience("000818"));
          assertTrue(gaps.isEmpty(), String.valueOf(gaps));
          byte[] bytes = new BgEtzEmitter().emit(file);
          String xml = new String(bytes, java.nio.charset.Charset.forName("windows-1251"));
          assertTrue(xml.contains("<enddate>2026-10-20</enddate>"), "т. 26: " + xml);
          assertTrue(xml.contains("<endreason>029</endreason>"), "т. 27: " + xml);
          BgContractTermination t = termination();
          try {
            id[0] =
                Beans.get(BgEtzSubmissionService.class)
                    .attach(
                        contract,
                        BgEtzEventKind.TERMINATION,
                        bytes,
                        t.getTerminationDate(),
                        BgContractTermination.class,
                        t.getId())
                    .getId();
          } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
          }
        });
    return id[0];
  }

  private String manual(String incomingNumber) {
    Map<String, Object> extra = new HashMap<>();
    extra.put("bgEtzEndIncomingNumber", incomingNumber);
    extra.put("bgEtzEndPortalDate", PORTAL_DATE.toString());
    return press(EmploymentContract.class, contractId, extra, end()::recordManual);
  }

  private RuntimeException changeContractDate(LocalDate date) {
    EmploymentContract c = JPA.em().find(EmploymentContract.class, contractId);
    LocalDate before = c.getBgTerminationDate();
    return refused(
        () -> {
          c.setBgTerminationDate(date);
          Beans.get(EmploymentContractRepository.class).save(c);
        },
        () -> c.setBgTerminationDate(before));
  }

  private RuntimeException changeContractReason(Long reason) {
    EmploymentContract c = JPA.em().find(EmploymentContract.class, contractId);
    EndOfContractReason before = c.getEndOfContractReason();
    return refused(
        () -> {
          c.setEndOfContractReason(JPA.em().find(EndOfContractReason.class, reason));
          Beans.get(EmploymentContractRepository.class).save(c);
        },
        () -> c.setEndOfContractReason(before));
  }

  private RuntimeException changeRecordDate(LocalDate date) {
    BgContractTermination t = termination();
    LocalDate before = t.getTerminationDate();
    return refused(
        () -> {
          t.setTerminationDate(date);
          Beans.get(BgContractTerminationRepository.class).save(t);
        },
        () -> t.setTerminationDate(before));
  }

  private RuntimeException changeReasonGround(String ground) {
    EndOfContractReason r = JPA.em().find(EndOfContractReason.class, reasonId);
    String before = r.getBgTerminationGround();
    return refused(
        () -> {
          r.setBgTerminationGround(ground);
          Beans.get(EndOfContractReasonRepository.class).save(r);
        },
        () -> r.setBgTerminationGround(before));
  }

  /** Запис в собствена транзакция; при отказ стойността се връща в паметта — контекстът остава. */
  private static RuntimeException refused(Runnable write, Runnable restore) {
    try {
      JPA.runInTransaction(write);
      return null;
    } catch (RuntimeException e) {
      restore.run();
      Throwable cause = e;
      while (cause.getCause() != null && !(cause instanceof javax.validation.ValidationException)) {
        cause = cause.getCause();
      }
      return cause instanceof RuntimeException ? (RuntimeException) cause : e;
    }
  }

  private LocalDate contractDate() {
    return fresh(em -> em.find(EmploymentContract.class, contractId).getBgTerminationDate());
  }

  private static List<NapSubmission> submissions(Long terminationId) {
    return fresh(
        em ->
            em.createQuery(
                    "SELECT s FROM NapSubmission s WHERE s.relatedModel = :m"
                        + " AND s.relatedRecordId = :id",
                    NapSubmission.class)
                .setParameter("m", BgContractTermination.class.getName())
                .setParameter("id", terminationId)
                .getResultList());
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

  private Company company() {
    return JPA.em().find(Company.class, companyId);
  }

  private Company otherCompany() {
    return JPA.em().find(Company.class, otherCompanyId);
  }

  private static Company company(String name, String code, String eik) {
    Company company = new Company();
    company.setName(name);
    company.setCode(code);
    company.setBgNkid(nkid("62.01"));
    JPA.em().persist(company);
    Partner partner = new Partner();
    partner.setName(name);
    partner.setRegistrationCode(eik);
    partner.setCompany(company);
    JPA.em().persist(partner);
    company.setPartner(partner);
    return company;
  }

  private static EndOfContractReason reason(String name, String ground) {
    EndOfContractReason reason = new EndOfContractReason();
    reason.setReason(name);
    reason.setBgLegalFamily("KT");
    reason.setBgTerminationGround(ground);
    JPA.em().persist(reason);
    return reason;
  }

  /** Договор с измисленото лице P0043 — срочен по чл. 68 ал. 1 т. 1 или безсрочен. */
  private static EmploymentContract contract(
      Company company, String personTag, LocalDate signed, String type) {
    Partner person = new Partner();
    person.setFirstName("Нюкса");
    person.setBgMiddleName("Тестова");
    person.setName("Иванова");
    person.setCompany(company);
    JPA.em().persist(person);
    Employee employee = new Employee();
    employee.setName(personTag);
    employee.setContactPartner(person);
    employee.setBgPersonalIdNumber(EGN);
    employee.setBgPersonalIdType("1");
    JPA.em().persist(employee);

    EmploymentContract contract = new EmploymentContract();
    contract.setEmployee(employee);
    contract.setPayCompany(company);
    contract.setBgContractType(type);
    contract.setSignatureDate(signed);
    contract.setStartDate(signed.plusDays(7));
    contract.setBgNkpd(nkpd("24136001"));
    contract.setBgBaseSalary(new BigDecimal("1500.00"));
    contract.setBgHoursPerDay(new BigDecimal("8"));
    contract.setBgAnnualLeaveDays(new BigDecimal("20"));
    contract.setBgWorkplaceEkatte("68134");
    JPA.em().persist(contract);
    return contract;
  }

  private static BgNkid nkid(String code) {
    List<BgNkid> found =
        JPA.em()
            .createQuery("SELECT n FROM BgNkid n WHERE n.code = :c", BgNkid.class)
            .setParameter("c", code)
            .getResultList();
    if (!found.isEmpty()) {
      return found.get(0);
    }
    BgNkid nkid = new BgNkid();
    nkid.setCode(code);
    nkid.setName("Компютърно програмиране");
    JPA.em().persist(nkid);
    return nkid;
  }

  private static BgNkpd nkpd(String code) {
    List<BgNkpd> found =
        JPA.em()
            .createQuery("SELECT n FROM BgNkpd n WHERE n.code = :c", BgNkpd.class)
            .setParameter("c", code)
            .getResultList();
    if (!found.isEmpty()) {
      return found.get(0);
    }
    BgNkpd nkpd = new BgNkpd();
    nkpd.setCode(code);
    nkpd.setName("Програмист");
    JPA.em().persist(nkpd);
    return nkpd;
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

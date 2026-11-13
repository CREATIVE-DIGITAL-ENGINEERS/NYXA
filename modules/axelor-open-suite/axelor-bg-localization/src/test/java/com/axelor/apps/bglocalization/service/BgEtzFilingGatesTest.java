package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgNkid;
import com.axelor.apps.bglocalization.db.BgNkpd;
import com.axelor.apps.bglocalization.service.etz.BgEtzValidator;
import com.axelor.apps.bglocalization.service.etz.BgEtzXsd;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.bglocalization.testsupport.BgTestApp;
import com.axelor.apps.bglocalization.web.BgEtzController;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.napsubmissions.common.submission.NapFilingLock;
import com.axelor.apps.napsubmissions.common.submission.NapFilingLockedException;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsNapSubmitService;
import com.axelor.apps.napsubmissions.services.declarations.common.ServiceDocumentType;
import com.axelor.apps.napsubmissions.web.NapSubmissionController;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.db.Model;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.Collections;
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
 * ЕТЗ ЗА СКЛЮЧВАНЕ ПО РЕАЛНИЯ ПЪТ — истинските контролери, ролите от каталога ({@link
 * BgRoleLogin}), без тестова транзакция около действията, стъб само на API-то на НАП.
 *
 * <p>Данните са ИЗМИСЛЕНИ: „Нюкса Тест ЕООД“, лицето P0041 с изчислен (несъществуващ) ЕГН. КИД е
 * във вида на сийда („62.01“), договорът е нов — без основание за прекратяване. Точно тези две неща
 * решават дали сключването пада на т. 1 и т. 21.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgFilingGatesTest.Wiring.class)
@Tag("integration")
class BgEtzFilingGatesTest extends JpaSupport {

  private static final String NOT_ALLOWED = "You are not allowed to ";
  private static final String ACTIVE_COMPANY = "active company";
  private static final String PAYROLL_WRITE = "hr.payroll.write";
  private static final String PAYROLL_READ = "hr.payroll.read";
  private static final String GENERATE = "generate an ETZ record";
  private static final String DOWNLOAD = "download the ETZ file";
  private static final String EIK = "831919536";
  private static final String EGN = "8001010008";
  private static final LocalDate SIGNED = LocalDate.of(2026, 10, 12);
  private static final LocalDate PORTAL_DATE = LocalDate.of(2026, 10, 14);

  private Long companyId;
  private Long otherCompanyId;
  private Long contractId;

  @BeforeEach
  void scene() {
    // ⚠️ КОНТЕКСТЪТ НЕ СЕ ЧИСТИ — нито тук, нито след действие. Partner носи @Formula за
    // PostgreSQL, която HSQLDB не изпълнява; изчистен контекст значи договорът да се зареди наново
    // и
    // flush-ът да прочете EmploymentContract.fullName → Employee.name → Partner. Ролите от каталога
    // се зареждат ПРЕДИ сцената, защото вносът им (CSVImporter) чисти контекста.
    BgRoleLogin.createUser(
        null,
        "роли за ЕТЗ",
        PAYROLL_WRITE,
        PAYROLL_READ,
        "all.read",
        "accounting.nap.write",
        "accounting.nap.read",
        "hr.read");
    String tag = String.valueOf(System.nanoTime());
    JPA.runInTransaction(
        () -> {
          Company company = company("Нюкса Тест ЕООД " + tag, "NT" + tag, EIK);
          companyId = company.getId();
          otherCompanyId = company("Нюкса Друго ЕООД " + tag, "ND" + tag, "121817309").getId();
          BgTestApp.ensureAppBase();
          contractId = contract(company, "P0041 " + tag).getId();
        });
  }

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  // ═══ ФАЙЛЪТ ══════════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "hr.payroll.write: договор → ETZ<ЕИК>.XML в WINDOWS-1251, минава XSD; чернова DEC_ETZ62,"
          + " ръчен портал, без тестова среда, закачена за договора")
  void theContractBecomesAValidFile() throws Exception {
    BgRoleLogin.login(company(), PAYROLL_WRITE);
    String text = press(EmploymentContract.class, contractId, null, controller()::generate);
    assertFalse(text.contains("error="), "генерирането минава: " + text);

    List<NapSubmission> all = submissions();
    assertEquals(1, all.size(), text);
    NapSubmission s = all.get(0);
    assertEquals(ServiceDocumentType.DEC_ETZ62.name(), s.getServiceDocumentTypeSelect());
    assertEquals(NapSubmissionRepository.STATUS_DRAFT, s.getSubmissionStatusSelect());
    assertEquals(NapSubmissionRepository.CHANNEL_MANUAL_PORTAL, s.getChannelSelect());
    assertNull(s.getEnvironmentSelect(), "тестова среда няма — подаването е в портала");
    assertEquals(EmploymentContract.class.getName(), s.getRelatedModel());
    assertEquals(contractId, s.getRelatedRecordId());
    assertEquals(companyId, s.getCompany().getId());

    MetaFile file = payload(s.getId());
    assertEquals("ETZ" + EIK + ".XML", file.getFileName());
    byte[] bytes = Files.readAllBytes(MetaFiles.getPath(file));
    String xml = new String(bytes, Charset.forName("windows-1251"));
    assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"WINDOWS-1251\"?>"), xml);
    assertTrue(xml.contains("<employtype>01</employtype>"), "т. 1 от вида договор: " + xml);
    assertTrue(xml.contains("<codekid>6201</codekid>"), "т. 21 без точката: " + xml);
    assertTrue(xml.contains("<firstname>Нюкса</firstname>"), "кирилица в 1251: " + xml);
    assertEquals(
        List.of(),
        new BgEtzValidator().validate(bytes, BgEtzXsd.Schema.EMPLOY),
        "0 грешки срещу закрепената схема");
  }

  @Test
  @DisplayName(
      "Липсващи реквизити (ЕКАТТЕ, НКПД, КИД, работно време, основание) — шумен отказ, който"
          + " назовава всяко поле и екрана му; нищо не е записано")
  void missingRequisitesAreNamedWithTheirScreen() {
    JPA.runInTransaction(
        () -> {
          EmploymentContract c = JPA.em().find(EmploymentContract.class, contractId);
          c.setBgWorkplaceEkatte(null);
          c.setBgNkpd(null);
          c.setBgHoursPerDay(BigDecimal.ZERO);
          c.setBgContractType("68");
          c.getPayCompany().setBgNkid(null);
        });
    BgRoleLogin.login(company(), PAYROLL_WRITE);
    String text = press(EmploymentContract.class, contractId, null, controller()::generate);

    assertTrue(text.contains("error="), "отказ, не бележка: " + text);
    for (String expected :
        new String[] {
          "т. 22 (Код по ЕКАТТЕ)",
          "„Код по ЕКАТТЕ (място на работа)“",
          "т. 19 (Код по НКПД)",
          "Трудов договор › Правоотношение › „Код по НКПД“",
          "т. 21 (Код по КИД)",
          "Дружество › Българска локализация › „Код по НКИД“",
          "т. 23 (Код за работно време)",
          "„Договорени часове на ден“",
          "т. 12 (Основание на договора/акта)",
          "„Вид договор“"
        }) {
      assertTrue(text.contains(expected), "назовава „" + expected + "“: " + text);
    }
    assertTrue(submissions().isEmpty(), "нищо не е записано: " + text);
  }

  // ═══ РЪЧНОТО ПОДАВАНЕ ═══════════════════════════════════════════════════

  @Test
  @DisplayName(
      "Ръчно подаване от договора: вх. № и датата от портала; редът е доказателство (#334);"
          + " ново генериране — отказ")
  void theManualFilingLocksTheRecord() {
    BgRoleLogin.login(company(), PAYROLL_WRITE);
    press(EmploymentContract.class, contractId, null, controller()::generate);
    Long id = submissions().get(0).getId();

    String text = manual("НАП-ЕТЗ-1");
    assertFalse(text.contains("error="), "ръчното подаване минава: " + text);
    NapSubmission s = submission(id);
    assertEquals(NapSubmissionRepository.STATUS_SUBMITTED, s.getSubmissionStatusSelect(), text);
    assertEquals("НАП-ЕТЗ-1", s.getNapIncomingNumber());
    assertEquals(PORTAL_DATE, s.getSubmittedOn().toLocalDate(), "датата е от портала");

    String again = press(EmploymentContract.class, contractId, null, controller()::generate);
    assertTrue(again.contains("Ново генериране е заключено"), "ново генериране: " + again);
    assertEquals(1, submissions().size(), "нов файл не е създаден");

    // Последно: отказаният запис връща транзакцията (виж бележката в scene()).
    RuntimeException relabel =
        refusedWrite(
            () ->
                JPA.em()
                    .find(NapSubmission.class, id)
                    .setChannelSelect(NapSubmissionRepository.CHANNEL_API));
    assertNotNull(relabel, "каналът на подаденото не се сменя (#334)");
    assertEquals(NapSubmissionRepository.CHANNEL_MANUAL_PORTAL, submission(id).getChannelSelect());
  }

  @Test
  @DisplayName(
      "Второ подаване за същия договор: две чернови, едната подадена → другата е заключена;"
          + " един договор — едно подаване")
  void aSecondFilingForTheSameContractIsRefused() {
    BgRoleLogin.login(company(), PAYROLL_WRITE);
    press(EmploymentContract.class, contractId, null, controller()::generate);
    press(EmploymentContract.class, contractId, null, controller()::generate);
    List<NapSubmission> drafts = submissions();
    assertEquals(2, drafts.size(), "две чернови преди подаване са допустими");
    Long older = drafts.get(1).getId();
    Long newer = drafts.get(0).getId();

    Map<String, Object> extra = new HashMap<>();
    extra.put("napIncomingNumber", "НАП-ЕТЗ-СТАРА");
    extra.put("portalSubmittedDate", PORTAL_DATE.toString());
    String first =
        press(
            NapSubmission.class,
            older,
            extra,
            Beans.get(NapSubmissionController.class)::recordManualSubmission);
    assertFalse(first.contains("error="), "първото минава: " + first);

    Integer before = version(NapSubmission.class, newer);
    String second = manual("НАП-ЕТЗ-НОВА");
    assertTrue(second.contains(NapFilingLock.MANUAL_LOCKED), "второто — отказ: " + second);
    assertEquals(before, version(NapSubmission.class, newer), "нищо не е записано");
    assertNull(submission(newer).getNapIncomingNumber());
  }

  // ═══ ГЕЙТЪТ ══════════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "all.read и accounting.nap.write без hr.payroll.write: генериране и ръчно подаване — шумен"
          + " отказ, който назовава hr.payroll.write; нищо не е записано")
  void otherRolesAreRefused() {
    for (String role : new String[] {"all.read", "accounting.nap.write"}) {
      BgRoleLogin.login(company(), role);
      String text = press(EmploymentContract.class, contractId, null, controller()::generate);
      assertTrue(
          text.contains(NOT_ALLOWED + GENERATE) && text.contains(PAYROLL_WRITE),
          role + " → отказ за генериране: " + text);
      assertTrue(submissions().isEmpty(), role + ": нищо не е създадено");
      BgRoleLogin.logout();
    }

    // Черновата — от ТРЗ; после accounting.nap.write опитва ОБЩИЯ ръчен път (гейтът по вида).
    BgRoleLogin.login(company(), PAYROLL_WRITE);
    press(EmploymentContract.class, contractId, null, controller()::generate);
    Long id = submissions().get(0).getId();
    BgRoleLogin.logout();
    BgRoleLogin.login(company(), "accounting.nap.write");
    Map<String, Object> extra = new HashMap<>();
    extra.put("napIncomingNumber", "НАП-ЧУЖДО");
    extra.put("portalSubmittedDate", PORTAL_DATE.toString());
    Integer before = version(NapSubmission.class, id);
    String text =
        press(
            NapSubmission.class,
            id,
            extra,
            Beans.get(NapSubmissionController.class)::recordManualSubmission);
    assertTrue(
        text.contains(NOT_ALLOWED + "record a manual NAP submission")
            && text.contains(PAYROLL_WRITE),
        "ЕТЗ е на ТРЗ, не на НАП: " + text);
    assertEquals(before, version(NapSubmission.class, id), "непокътнато");
  }

  @Test
  @DisplayName(
      "hr.payroll.write с А и Б, активна Б: ЕТЗ за договор на А — отказ по активното дружество")
  void anotherActiveCompanyIsRefused() {
    BgRoleLogin.login(otherCompany(), Set.of(company()), PAYROLL_WRITE);
    String text = press(EmploymentContract.class, contractId, null, controller()::generate);
    assertTrue(text.contains(ACTIVE_COMPANY), "отказ по активното дружество: " + text);
    assertTrue(submissions().isEmpty(), "нищо не е създадено");
  }

  @Test
  @DisplayName(
      "„Свали файла“: hr.read + accounting.nap.read (четат договора и подаването, без ТРЗ) — отказ,"
          + " който назовава hr.payroll.read; hr.payroll.read и all.read — файлът")
  void theDownloadNeedsPayrollRead() {
    BgRoleLogin.login(company(), PAYROLL_WRITE);
    press(EmploymentContract.class, contractId, null, controller()::generate);
    BgRoleLogin.logout();

    BgRoleLogin.login(company(), "hr.read", "accounting.nap.read");
    String refused = press(EmploymentContract.class, contractId, null, controller()::download);
    assertTrue(
        refused.contains(NOT_ALLOWED + DOWNLOAD) && refused.contains(PAYROLL_READ),
        "без hr.payroll.read — отказ по ролята: " + refused);
    assertFalse(refused.contains("/content/download"), "файлът не е даден: " + refused);
    BgRoleLogin.logout();

    for (String role : new String[] {PAYROLL_READ, "all.read"}) {
      BgRoleLogin.login(company(), role);
      String text = press(EmploymentContract.class, contractId, null, controller()::download);
      assertTrue(text.contains("/content/download"), role + " → файлът: " + text);
      assertTrue(text.contains("ETZ" + EIK + ".XML"), role + " → името на файла: " + text);
      BgRoleLogin.logout();
    }
  }

  // ═══ КАНАЛЪТ API ═════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "Канал API за ЕТЗ: „Подпиши и изпрати“, общият API път и Declarations API — шумен отказ"
          + " „без КЕП … КЕП или ПИК“; до API-то нищо не стига")
  void theApiChannelIsRefused() {
    BgRoleLogin.login(company(), PAYROLL_WRITE);
    press(EmploymentContract.class, contractId, null, controller()::generate);
    Long id = submissions().get(0).getId();
    BgFilingGatesTest.API.sent.clear();
    Integer before = version(NapSubmission.class, id);
    NapSubmissionController c = Beans.get(NapSubmissionController.class);

    String sign = press(NapSubmission.class, id, null, c::signAndSubmit);
    assertTrue(sign.contains(NapFilingLock.MANUAL_ONLY_REFUSAL), "подпиши и изпрати: " + sign);
    String api = press(NapSubmission.class, id, null, c::submitThroughApi);
    assertTrue(api.contains(NapFilingLock.MANUAL_ONLY_REFUSAL), "общият API път: " + api);
    NapFilingLockedException direct = null;
    try {
      Beans.get(DeclarationsNapSubmitService.class)
          .submitSignedTransient(submission(id), Collections.emptyMap());
    } catch (NapFilingLockedException e) {
      direct = e;
    } catch (Exception e) {
      throw new AssertionError("друг отказ вместо канала: " + e, e);
    }
    assertNotNull(direct, "Declarations API — отказ преди подпис и мрежа");
    assertEquals(NapFilingLock.MANUAL_ONLY_REFUSAL, direct.getMessage());

    assertTrue(BgFilingGatesTest.API.sent.isEmpty(), "до API-то нищо не е тръгнало");
    assertEquals(before, version(NapSubmission.class, id), "подаването е непокътнато");
  }

  // ═══ ПОМОЩНИЦИТЕ ═════════════════════════════════════════════════════════

  private static BgEtzController controller() {
    return Beans.get(BgEtzController.class);
  }

  /** Ръчното подаване от панела на договора: вх. № и дата, както ги изпраща формата. */
  private String manual(String incomingNumber) {
    Map<String, Object> extra = new HashMap<>();
    extra.put("bgEtzIncomingNumber", incomingNumber);
    extra.put("bgEtzPortalDate", PORTAL_DATE.toString());
    return press(EmploymentContract.class, contractId, extra, controller()::recordManual);
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

  /** Подаванията за сключването на договора, най-новото първо. */
  private List<NapSubmission> submissions() {
    return fresh(
        em ->
            em.createQuery(
                    "SELECT s FROM NapSubmission s WHERE s.relatedModel = :m"
                        + " AND s.relatedRecordId = :id ORDER BY s.id DESC",
                    NapSubmission.class)
                .setParameter("m", EmploymentContract.class.getName())
                .setParameter("id", contractId)
                .getResultList());
  }

  private static MetaFile payload(Long submissionId) {
    return fresh(
        em ->
            em.createQuery(
                    "SELECT f.payloadArtifact.metaFile FROM NapSubmissionFile f"
                        + " WHERE f.submission.id = :s",
                    MetaFile.class)
                .setParameter("s", submissionId)
                .getSingleResult());
  }

  private Company company() {
    return JPA.em().find(Company.class, companyId);
  }

  private Company otherCompany() {
    return JPA.em().find(Company.class, otherCompanyId);
  }

  /** Дружество с партньор (ЕИК по БУЛСТАТ) и КИД във вида на сийда — „62.01“. */
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

  /** Нов безсрочен договор по чл. 67 — без основание за прекратяване, както в живота. */
  private static EmploymentContract contract(Company company, String personTag) {
    Partner person = new Partner();
    person.setFirstName("Нюкса");
    person.setBgMiddleName("Тестова");
    person.setName("Петрова");
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
    contract.setBgContractType("67");
    contract.setSignatureDate(SIGNED);
    contract.setStartDate(SIGNED.plusDays(7));
    contract.setBgNkpd(nkpd("24136001"));
    contract.setBgBaseSalary(new BigDecimal("1500.00"));
    contract.setBgHoursPerDay(new BigDecimal("8"));
    contract.setBgAnnualLeaveDays(new BigDecimal("20"));
    contract.setBgWorkplaceEkatte("68134");
    JPA.em().persist(contract);
    return contract;
  }

  /** Речниковият ред — веднъж на схема (кодът е уникален). */
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

  private static NapSubmission submission(Long id) {
    return fresh(em -> em.find(NapSubmission.class, id));
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

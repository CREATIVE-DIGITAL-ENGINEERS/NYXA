package com.axelor.apps.bglocalization.service;

import static com.axelor.apps.bglocalization.testsupport.BgRoleLogin.READ_ROLE;
import static com.axelor.apps.bglocalization.testsupport.BgRoleLogin.WRITE_ROLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgAbsenceType;
import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgDeclarant;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.noi.BgApp9DueScope;
import com.axelor.apps.bglocalization.service.noi.BgApp9ExportService;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInputBuilder;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.bglocalization.web.BgApp9DeclarationController;
import com.axelor.apps.bglocalization.web.BgApp9DueController;
import com.axelor.apps.bglocalization.web.BgApp9Point9FromSlipController;
import com.axelor.apps.bglocalization.web.BgPaySlipController;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.db.Model;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.time.LocalDate;
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
 * ТРЗ ПОД nyxa.security — по истинските контролери, без тестова транзакция, с ролите от каталога
 * ({@link BgRoleLogin}): админ write = {@code all.write}, админ read = {@code all.read}. Гейтовете
 * са по способност, не по име на роля.
 *
 * <ul>
 *   <li><b>Записите:</b> all.write минава гейта; all.read получава шумен отказ, който назовава
 *       действието, и записът е НЕПОКЪТНАТ (версията в базата).
 *   <li><b>Изолация:</b> „Прил. 9 за подаване“ вижда и опреснява само АКТИВНОТО дружество; без
 *       право за запис отварянето НЕ опреснява.
 * </ul>
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgTrzSecurityGatesTest extends JpaSupport {

  private static final String NOT_ALLOWED = "You are not allowed to ";

  private static final String EGN_ONE = "9999999901";
  private static final String EGN_TWO = "9999999902";

  private Long companyId;
  private Long otherCompanyId;
  private Long slipId;
  private Long sheetId;
  private Long otherSheetId;
  private Long declarationId;
  private Long submissionId;

  @BeforeEach
  void scene() {
    String tag = String.valueOf(System.nanoTime());
    JPA.runInTransaction(
        () -> {
          Company company = company("Сигурност А " + tag, "SA" + tag);
          companyId = company.getId();
          Company other = company("Сигурност Б " + tag, "SB" + tag);
          otherCompanyId = other.getId();
          EmploymentContract contract = contract(company);
          Period period = period(company, tag);

          BgPaySlip slip = new BgPaySlip();
          slip.setReference("FISH-SEC-" + tag);
          slip.setStatusSelect(BgPaySlipResolver.DRAFT);
          slip.setPeriod(period);
          slip.setEmploymentContract(contract);
          JPA.em().persist(slip);
          slipId = slip.getId();

          BgAbsenceType type = new BgAbsenceType();
          type.setCode("sick_sec_" + tag);
          type.setName("Болничен " + tag);
          type.setCategory(BgPayrollInputBuilder.ABSENCE_CATEGORY_SICK);
          type.setPayer("employer");
          type.setActive(true);
          JPA.em().persist(type);
          sheetId = sheet(contract, type, "A" + tag).getId();
          otherSheetId = sheet(contract(other), type, "B" + tag).getId();

          BgApp9Declaration declaration = new BgApp9Declaration();
          declaration.setSickLeave(JPA.em().find(BgSickLeave.class, sheetId));
          JPA.em().persist(declaration);
          declarationId = declaration.getId();

          NapSubmission submission = new NapSubmission();
          submission.setName("Подаване " + tag);
          submission.setCompany(company);
          submission.setServiceCodeSelect(NapSubmissionRepository.SERVICE_NOI_APP9);
          submission.setChannelSelect(NapSubmissionRepository.CHANNEL_MANUAL_PORTAL);
          submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_DRAFT);
          JPA.em().persist(submission);
          submissionId = submission.getId();
        });
  }

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  // ═══ ЗАПИСИТЕ: all.write минава, all.read — отказ ════════════════════════

  @Test
  @DisplayName("Прил. 9 „Отбележи като подадено“: all.write минава; all.read — отказ")
  void markCertificateSubmitted() {
    BgApp9DeclarationController c = Beans.get(BgApp9DeclarationController.class);
    Long before = count(NapSubmission.class);
    passes(BgApp9Declaration.class, declarationId, c::markSubmitted);
    refused(
        "mark an App. 9 certificate as submitted",
        BgApp9Declaration.class,
        declarationId,
        c::markSubmitted);
    assertEquals(before, count(NapSubmission.class), "подаване не е създадено");
  }

  @Test
  @DisplayName("Прил. 9 отмяна на подаването: all.write минава; all.read — отказ")
  void withdrawSubmission() {
    BgApp9DueController c = Beans.get(BgApp9DueController.class);
    passes(NapSubmission.class, submissionId, c::withdrawSubmission);
    refused(
        "withdraw an App. 9 submission", NapSubmission.class, submissionId, c::withdrawSubmission);
  }

  @Test
  @DisplayName("Прил. 9 „Опресни сроковете“: all.write минава; all.read — отказ")
  void refreshDeadlines() {
    BgApp9DueController c = Beans.get(BgApp9DueController.class);
    String ok = passes(BgSickLeave.class, sheetId, c::refreshDeadlines);
    assertTrue(ok.contains("Сроковете са опреснени"), "услугата е стигната: " + ok);
    refused("refresh App. 9 deadlines", BgSickLeave.class, sheetId, c::refreshDeadlines);
  }

  @Test
  @DisplayName("Прил. 9 „Изведи т. 9“: all.write минава; all.read — отказ")
  void derivePoint9() {
    BgApp9Point9FromSlipController c = Beans.get(BgApp9Point9FromSlipController.class);
    String ok = passes(BgSickLeave.class, sheetId, c::derive);
    assertTrue(ok.contains("Т. 9"), "услугата е стигната: " + ok);
    refused("derive point 9 of App. 9", BgSickLeave.class, sheetId, c::derive);
  }

  @Test
  @DisplayName("„Преизчисли“ на фиша: all.write записва; all.read — отказ, нищо")
  void recalculatePaySlip() {
    BgPaySlipController c = Beans.get(BgPaySlipController.class);
    refused("recalculate a pay slip", BgPaySlip.class, slipId, c::recalculate);
    assertNull(fresh(em -> em.find(BgPaySlip.class, slipId)).getBgCalcFingerprint());
    passes(BgPaySlip.class, slipId, c::recalculate);
    assertNotNull(
        fresh(em -> em.find(BgPaySlip.class, slipId)).getBgCalcFingerprint(),
        "с ролята фишът е пресметнат и записан");
  }

  // ═══ ПРИЛ. 9: ДЕКЛАРАТОРЪТ ПО ПОДРАЗБИРАНЕ ═════════════════════════════

  @Test
  @DisplayName("Прил. 9: деклараторът по подразбиране е isDefault, НЕ първият по id")
  void theApp9DefaultDeclarantIsTheMarkedOne() {
    Long[] ids = new Long[2];
    JPA.runInTransaction(
        () -> {
          Company company = JPA.em().find(Company.class, companyId);
          ids[0] = declarant(company, "Първи По Ред", EGN_ONE, false).getId();
          ids[1] = declarant(company, "Посочен По Подразбиране", EGN_TWO, true).getId();
        });
    assertTrue(ids[0] < ids[1], "по-старият е по-малкият id");
    BgDeclarant chosen =
        Beans.get(BgApp9ExportService.class).declarantOf(JPA.em().find(Company.class, companyId));
    assertNotNull(chosen, "има декларатор по подразбиране");
    assertEquals(ids[1], chosen.getId(), "isDefault, не първият по id");
  }

  // ═══ ИЗОЛАЦИЯТА: САМО АКТИВНОТО ДРУЖЕСТВО ═══════════════════════════════

  @Test
  @DisplayName(
      "Прил. 9 за подаване: потребител с А и Б — активна А вижда/опреснява само А; Б — само Б")
  void theDueListIsTheActiveCompanyOnly() {
    Company a = JPA.em().find(Company.class, companyId);
    Company b = JPA.em().find(Company.class, otherCompanyId);

    BgRoleLogin.loginWrite(a, Set.of(b));
    Map<String, Object> view = openDueList();
    String domain = String.valueOf(view.get("domain"));
    assertTrue(domain.contains("IN (" + companyId + ")"), "активна А — само А: " + domain);
    assertFalse(domain.contains(String.valueOf(otherCompanyId)), "Б не е в обхвата: " + domain);
    assertNotNull(refreshedOn(companyId), "сроковете на А са опреснени");
    assertNull(refreshedOn(otherCompanyId), "на Б — НЕ: опресняването е само за активното");

    BgRoleLogin.logout();
    BgRoleLogin.loginWrite(b, Set.of(a));
    domain = String.valueOf(openDueList().get("domain"));
    assertTrue(domain.contains("IN (" + otherCompanyId + ")"), "активна Б — само Б: " + domain);
    assertFalse(domain.contains("IN (" + companyId + ")"), "А не е в обхвата: " + domain);
  }

  @Test
  @DisplayName("Прил. 9 за подаване без право за запис: отваря се БЕЗ опресняване, с бележка")
  void openingWithoutTheWriteRoleDoesNotWrite() {
    Company a = JPA.em().find(Company.class, companyId);
    BgRoleLogin.loginRead(a);
    Integer before = version(BgSickLeave.class, sheetId);
    ActionResponse response = new ActionResponse();
    Beans.get(BgApp9DueController.class).openDueList(request(null, null), response);
    String text = String.valueOf(response.getData());
    assertTrue(text.contains("още не са опреснявани"), "казва, че не е опреснявано: " + text);
    assertNull(refreshedOn(companyId), "нищо не е отбелязано");
    assertEquals(before, version(BgSickLeave.class, sheetId), "листът не е пипнат");
  }

  @Test
  @DisplayName("Прил. 9 за подаване без активно дружество → шумен отказ")
  void noActiveCompanyIsRefused() {
    BgRoleLogin.loginWrite(null);
    ActionResponse response = new ActionResponse();
    Beans.get(BgApp9DueController.class).openDueList(request(null, null), response);
    assertTrue(
        String.valueOf(response.getData()).contains(BgApp9DueScope.NO_ACTIVE_COMPANY),
        "" + response.getData());
  }

  // ═══ ДВЕТЕ ПРОВЕРКИ НА ЗАПИСИТЕ ══════════════════════════════════════════

  private String passes(
      Class<? extends Model> model, Long id, BiConsumer<ActionRequest, ActionResponse> action) {
    BgRoleLogin.logout();
    BgRoleLogin.loginWrite(company());
    String text = String.valueOf(press(model, id, action).getData());
    assertFalse(text.contains(NOT_ALLOWED), WRITE_ROLE + " минава гейта: " + text);
    assertFalse(text.contains("Not logged in"), WRITE_ROLE + " е вписан: " + text);
    return text;
  }

  private void refused(
      String actionName,
      Class<? extends Model> model,
      Long id,
      BiConsumer<ActionRequest, ActionResponse> action) {
    BgRoleLogin.logout();
    BgRoleLogin.loginRead(company());
    Integer before = version(model, id);
    String text = String.valueOf(press(model, id, action).getData());
    assertTrue(
        text.contains(NOT_ALLOWED + actionName) && text.contains(WRITE_ROLE),
        READ_ROLE
            + " → шумен отказ, който назовава „"
            + actionName
            + "“ и "
            + WRITE_ROLE
            + ": "
            + text);
    assertEquals(before, version(model, id), "записът е непокътнат: " + text);
  }

  // ═══ ПОМОЩНИЦИТЕ ═════════════════════════════════════════════════════════

  private Map<String, Object> openDueList() {
    ActionResponse response = new ActionResponse();
    Beans.get(BgApp9DueController.class).openDueList(request(null, null), response);
    return viewOf(response);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> viewOf(ActionResponse response) {
    for (Object item : (List<Object>) response.getData()) {
      if (item instanceof Map && ((Map<String, Object>) item).containsKey("view")) {
        return (Map<String, Object>) ((Map<String, Object>) item).get("view");
      }
    }
    throw new AssertionError("няма изглед: " + response.getData());
  }

  private static ActionResponse press(
      Class<? extends Model> model, Long id, BiConsumer<ActionRequest, ActionResponse> action) {
    ActionResponse response = new ActionResponse();
    assertFalse(JPA.em().getTransaction().isActive(), "действието тръгва БЕЗ транзакция");
    action.accept(request(model, id), response);
    return response;
  }

  private static ActionRequest request(Class<? extends Model> model, Long id) {
    Map<String, Object> context = new HashMap<>();
    if (model != null) {
      context.put("id", id);
      context.put("version", version(model, id));
      context.put("_model", model.getName());
    }
    Map<String, Object> data = new HashMap<>();
    data.put("context", context);
    ActionRequest request = new ActionRequest();
    if (model != null) {
      request.setModel(model.getName());
    }
    request.setData(data);
    return request;
  }

  private Company company() {
    return JPA.em().find(Company.class, companyId);
  }

  private static Company company(String name, String code) {
    Company company = new Company();
    company.setName(name);
    company.setCode(code);
    JPA.em().persist(company);
    return company;
  }

  private static EmploymentContract contract(Company company) {
    EmploymentContract contract = new EmploymentContract();
    contract.setPayCompany(company);
    JPA.em().persist(contract);
    return contract;
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

  private static BgSickLeave sheet(EmploymentContract contract, BgAbsenceType type, String tag) {
    BgSickLeave sheet = new BgSickLeave();
    sheet.setEmploymentContract(contract);
    sheet.setBgAbsenceType(type);
    sheet.setFromDate(LocalDate.of(2026, 10, 12));
    sheet.setToDate(LocalDate.of(2026, 10, 16));
    sheet.setBgDocumentNumber("Е2026" + tag.substring(Math.max(0, tag.length() - 7)));
    sheet.setStatusSelect(BgPayrollInputBuilder.SICK_LEAVE_VALIDATED);
    JPA.em().persist(sheet);
    return sheet;
  }

  private static BgDeclarant declarant(
      Company company, String names, String egn, boolean isDefault) {
    BgDeclarant d = new BgDeclarant();
    d.setCompany(company);
    d.setDeclarantNames(names);
    d.setDeclarantEgn(egn);
    d.setIsDefault(isDefault);
    JPA.em().persist(d);
    return d;
  }

  private static Object refreshedOn(Long companyId) {
    return fresh(em -> em.find(Company.class, companyId)).getBgApp9DeadlinesRefreshedOn();
  }

  private static Integer version(Class<? extends Model> model, Long id) {
    return fresh(em -> em.find(model, id).getVersion());
  }

  private static Long count(Class<? extends Model> model) {
    return fresh(
        em ->
            em.createQuery("SELECT COUNT(x) FROM " + model.getSimpleName() + " x", Long.class)
                .getSingleResult());
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

package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.BgTerminationCompensation;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.bglocalization.testsupport.BgTestApp;
import com.axelor.apps.bglocalization.web.BgCompensationController;
import com.axelor.apps.bglocalization.web.BgTerminationCompensationController;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.hr.db.EndOfContractReason;
import com.axelor.apps.hr.db.repo.EmploymentContractRepository;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ПРАВО ПРЕДИ ПАРИ — през истинските контролери, без тестова транзакция около извикването.
 *
 * <p>Договорите се записват през {@link EmploymentContractRepository#save} — реалният път, по който
 * правото се ПРЕДЛАГА ({@code BgEmploymentContractManagementRepository} → {@code
 * BgTerminationCompensationService#propose}). Вход с ролите от каталога: {@code all.write} пише,
 * {@code all.read} — отказ.
 *
 * <p>Видът в теста е „чл. 222 ал. 1“ по форма (поражда се при 328/1 т. 1, възниква при
 * прекратяване), с код от теста — кодът в каталога е уникален.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgCompensationEntitlementTransactionTest extends JpaSupport {

  private static final LocalDate TERMINATED = LocalDate.of(2026, 9, 30);
  private static final BigDecimal AMOUNT = new BigDecimal("2000.00");

  private Long companyId;
  private Long otherCompanyId;
  private Long typeId;
  private String tag;

  @BeforeEach
  void scene() {
    tag = String.valueOf(System.nanoTime());
    JPA.runInTransaction(
        () -> {
          companyId = company("Обезщетения А " + tag, "OA" + tag).getId();
          otherCompanyId = company("Обезщетения Б " + tag, "OB" + tag).getId();
          BgCompensationType type = new BgCompensationType();
          type.setCode("comp_222_1_" + tag);
          type.setName("Оставане без работа " + tag);
          type.setLegalBasis("чл. 222 ал. 1 КТ");
          type.setLegalFamily("KT");
          type.setBgDirection("EMPLOYER_TO_EMPLOYEE");
          type.setBaseKind(BgCompensationResolver.BTV_MONTHS);
          type.setBaseMonths(BigDecimal.ONE);
          type.setBgBaseVariesBy("NONE");
          type.setTriggerGrounds("328_1_1,328_1_2");
          type.setArisesOnTermination(true);
          type.setIsAmountAdjustable(true);
          type.setActive(true);
          JPA.em().persist(type);
          typeId = type.getId();
        });
    JPA.clear();
  }

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  // ═══ ОТКАЗИТЕ ═══

  @Test
  @DisplayName("222/1 при основание 325/1 т. 1 (не го поражда) → „Потвърди“ отказва; остава DRAFT")
  void withoutAnEntitlementTheDocumentIsRefused() {
    Long contractId = terminatedContract(companyId, "325_1_1");
    assertTrue(entitlements(contractId).isEmpty(), "основанието не поражда право за този вид");
    Long docId = manualDocument(contractId);
    BgRoleLogin.loginWrite(company(companyId));
    String text = press(docId, Beans.get(BgCompensationController.class)::confirm);
    assertTrue(
        text.contains("възниква при прекратяване") && text.contains("„Създай документ“"),
        "шумен отказ, който казва откъде се прави документът: " + text);
    assertEquals(BgCompensationResolver.DRAFT, status(docId), "остава чернова");
  }

  @Test
  @DisplayName("без id (незаписан документ от контекста) → отказ, нищо не е записано")
  void withoutAnIdNothingIsConfirmed() {
    Long contractId = terminatedContract(companyId, "325_1_1");
    long before = countDocuments();
    BgRoleLogin.loginWrite(company(companyId));
    Map<String, Object> context = new HashMap<>();
    Map<String, Object> contract = new HashMap<>();
    contract.put("id", contractId);
    Map<String, Object> type = new HashMap<>();
    type.put("id", typeId);
    context.put("employmentContract", contract);
    context.put("bgCompensationType", type);
    context.put("bgEventDate", TERMINATED.toString());
    context.put("bgFinalAmount", AMOUNT);
    context.put("statusSelect", BgCompensationResolver.DRAFT);
    ActionResponse response = new ActionResponse();
    Beans.get(BgCompensationController.class).confirm(request(context), response);
    String text = String.valueOf(response.getData());
    assertTrue(text.contains("ЗАПИСАН документ"), "отказ: " + text);
    assertEquals(before, countDocuments(), "нищо не е записано");
  }

  @Test
  @DisplayName("документ на друго дружество (Б в профила, активно А) → отказ, остава DRAFT")
  void aDocumentOfAnotherCompanyIsRefused() {
    Long contractId = terminatedContract(otherCompanyId, "328_1_1");
    Long docId = manualDocument(contractId);
    BgRoleLogin.loginWrite(company(companyId), Set.of(company(otherCompanyId)));
    String text = press(docId, Beans.get(BgCompensationController.class)::confirm);
    assertTrue(
        text.contains("not authorized") || text.contains("active company"), "отказ: " + text);
    assertEquals(BgCompensationResolver.DRAFT, status(docId), "остава чернова");
  }

  @Test
  @DisplayName("all.read → шумен отказ, който назовава действието; остава DRAFT")
  void theReadRoleIsRefused() {
    Long contractId = terminatedContract(companyId, "328_1_1");
    Long docId = manualDocument(contractId);
    BgRoleLogin.loginRead(company(companyId));
    String text = press(docId, Beans.get(BgCompensationController.class)::confirm);
    assertTrue(
        text.contains("change the status of a compensation")
            && text.contains(BgRoleLogin.WRITE_ROLE),
        "отказът назовава действието и способността: " + text);
    assertEquals(BgCompensationResolver.DRAFT, status(docId), "остава чернова");
  }

  // ═══ ПЪТЯТ, КОЙТО МИНАВА ═══

  @Test
  @DisplayName(
      "право → потвърдено → „Създай документ“ → „Потвърди“ = CONFIRMED; втори документ — отказ")
  void fromAConfirmedEntitlementTheDocumentIsConfirmed() {
    Long contractId = terminatedContract(companyId, "328_1_1");
    List<BgTerminationCompensation> rights = entitlements(contractId);
    assertEquals(1, rights.size(), "основанието 328/1 т. 1 поражда право: " + rights);
    Long rightId = rights.get(0).getId();
    JPA.runInTransaction(
        () ->
            JPA.em()
                .find(BgTerminationCompensation.class, rightId)
                .setStatus(BgCompensationResolver.ENTITLEMENT_CONFIRMED));
    JPA.clear();

    BgRoleLogin.loginWrite(company(companyId));
    BgTerminationCompensationController rc = Beans.get(BgTerminationCompensationController.class);
    String created = pressRight(rightId, rc::createDocument);
    assertFalse(created.contains("error"), "документът е създаден: " + created);
    BgCompensation doc = documentOf(rightId);
    assertNotNull(doc, "документ, свързан с правото");
    assertEquals(BgCompensationResolver.DRAFT, doc.getStatusSelect());
    assertEquals(TERMINATED, doc.getBgEventDate(), "датата на основанието е от правото");
    assertEquals("328_1_1", doc.getBgTerminationGround(), "основанието е от договора");

    Long docId = doc.getId();
    JPA.runInTransaction(
        () -> {
          BgCompensation d = JPA.em().find(BgCompensation.class, docId);
          d.setBgBaseMonthGross(AMOUNT);
          Beans.get(BgCompensationService.class).recompute(d);
        });
    JPA.clear();
    String confirmed = press(docId, Beans.get(BgCompensationController.class)::confirm);
    assertEquals(BgCompensationResolver.CONFIRMED, status(docId), "потвърден: " + confirmed);

    String second = pressRight(rightId, rc::createDocument);
    assertTrue(second.contains("вече има документ в работа"), "втори документ — отказ: " + second);
  }

  // ═══ ПОМОЩНИЦИТЕ ═══

  /** Прекратен основен договор, записан през РЕПОЗИТОРИЯТА — тя предлага правото. */
  private Long terminatedContract(Long company, String ground) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          EndOfContractReason reason = new EndOfContractReason();
          reason.setReason("Основание " + ground + " " + tag);
          reason.setBgLegalFamily("KT");
          reason.setBgTerminationGround(ground);
          JPA.em().persist(reason);
          EmploymentContract contract = new EmploymentContract();
          contract.setRef("EC-" + tag + "-" + ground + "-" + company);
          contract.setPayCompany(JPA.em().find(Company.class, company));
          contract.setBgIsPrimaryContract(true);
          contract.setStartDate(LocalDate.of(2024, 3, 1));
          contract.setBgTerminationDate(TERMINATED);
          contract.setEndOfContractReason(reason);
          id[0] = Beans.get(EmploymentContractRepository.class).save(contract).getId();
        });
    JPA.clear();
    return id[0];
  }

  /** Документ, въведен РЪЧНО — без връзка към право, с готова сума. */
  private Long manualDocument(Long contractId) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          BgCompensation doc = new BgCompensation();
          doc.setReference("OBEZ-T-" + tag + "-" + contractId);
          doc.setStatusSelect(BgCompensationResolver.DRAFT);
          doc.setEmploymentContract(JPA.em().find(EmploymentContract.class, contractId));
          doc.setBgCompensationType(JPA.em().find(BgCompensationType.class, typeId));
          doc.setBgEventDate(TERMINATED);
          doc.setBgBaseKind(BgCompensationResolver.BTV_MONTHS);
          doc.setBgQuantity(BigDecimal.ONE);
          doc.setBgBaseMonthGross(AMOUNT);
          doc.setBgComputedAmount(AMOUNT);
          doc.setBgFinalAmount(AMOUNT);
          JPA.em().persist(doc);
          id[0] = doc.getId();
        });
    JPA.clear();
    return id[0];
  }

  private static Company company(String name, String code) {
    Company company = new Company();
    company.setName(name);
    company.setCode(code);
    JPA.em().persist(company);
    // Документът иска номер от редицата на дружеството (създава я BgTrzCompanySequenceListener
    // при persist), а тя чете AppBase.
    BgTestApp.ensureAppBase();
    return company;
  }

  private static Company company(Long id) {
    return JPA.em().find(Company.class, id);
  }

  private static String press(Long docId, BiConsumer<ActionRequest, ActionResponse> action) {
    BgCompensation doc = JPA.em().find(BgCompensation.class, docId);
    Map<String, Object> context = new HashMap<>();
    context.put("id", docId);
    context.put("bgFinalAmount", doc.getBgFinalAmount());
    context.put("note", doc.getNote());
    JPA.clear();
    ActionResponse response = new ActionResponse();
    assertFalse(JPA.em().getTransaction().isActive(), "действието тръгва БЕЗ транзакция");
    action.accept(request(context), response);
    JPA.clear();
    return String.valueOf(response.getData());
  }

  private static String pressRight(Long rightId, BiConsumer<ActionRequest, ActionResponse> action) {
    Map<String, Object> context = new HashMap<>();
    context.put("id", rightId);
    Map<String, Object> data = new HashMap<>();
    data.put("context", context);
    ActionRequest request = new ActionRequest();
    request.setModel(BgTerminationCompensation.class.getName());
    request.setData(data);
    ActionResponse response = new ActionResponse();
    action.accept(request, response);
    JPA.clear();
    return String.valueOf(response.getData());
  }

  private static ActionRequest request(Map<String, Object> context) {
    Map<String, Object> data = new HashMap<>();
    data.put("context", context);
    ActionRequest request = new ActionRequest();
    request.setModel(BgCompensation.class.getName());
    request.setData(data);
    return request;
  }

  private static String status(Long docId) {
    JPA.clear();
    return JPA.em().find(BgCompensation.class, docId).getStatusSelect();
  }

  private static long countDocuments() {
    return JPA.em()
        .createQuery("SELECT COUNT(d) FROM BgCompensation d", Long.class)
        .getSingleResult();
  }

  private List<BgTerminationCompensation> entitlements(Long contractId) {
    return JPA.em()
        .createQuery(
            "SELECT r FROM BgTerminationCompensation r WHERE r.employmentContract.id = :c"
                + " AND r.bgCompensationType.id = :t",
            BgTerminationCompensation.class)
        .setParameter("c", contractId)
        .setParameter("t", typeId)
        .getResultList();
  }

  private static BgCompensation documentOf(Long rightId) {
    JPA.clear();
    List<BgCompensation> docs =
        JPA.em()
            .createQuery(
                "SELECT d FROM BgCompensation d WHERE d.bgTerminationCompensation.id = :r",
                BgCompensation.class)
            .setParameter("r", rightId)
            .getResultList();
    return docs.isEmpty() ? null : docs.get(0);
  }
}

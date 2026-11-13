package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipRegimeChange;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.d1.BgD1ContributionAggregator;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRegime;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.bglocalization.web.BgPaySlipController;
import com.axelor.apps.hr.db.EmploymentContract;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * РЕЖИМЪТ В КОПИЕ НА ЗАВАРЕН ФИШ ПРЕЗ ИСТИНСКИЯ КОНТРОЛЕР И ИСТИНСКОТО ХРАНИЛИЩЕ.
 *
 * <p>Пресмятането на копие без режим отказва с изброени полета и не записва нищо; „Попълни от
 * текущите данни“ попълва; записът на копието минава през {@code
 * BgPaySlipManagementRepository#save}, който пише одита — при попълването и при ръчна промяна.
 *
 * <p>⚠️ {@code @Tag("integration")}: пуска се през {@code integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgPaySlipRegimeAuditPersistenceTest extends JpaSupport {

  private Long copyId;
  private Long companyId;

  /**
   * ⚠️ С роля от каталога (all.write) в дружеството на фиша: записът минава през пазача на
   * активното дружество в репозитория — без вписан потребител фиш не се записва.
   */
  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  @BeforeEach
  void scene() {
    sceneData();
    BgRoleLogin.logout();
    BgRoleLogin.loginWrite(JPA.em().find(Company.class, companyId));
  }

  private void sceneData() {
    String tag = String.valueOf(System.nanoTime());
    JPA.runInTransaction(
        () -> {
          Company company = new Company();
          company.setName("Фирма " + tag);
          company.setCode("R" + tag);
          JPA.em().persist(company);
          companyId = company.getId();
          EmploymentContract contract = new EmploymentContract();
          contract.setPayCompany(company);
          contract.setBgInsuredTypeCode("01");
          contract.setBgWorkTimeMode("1");
          contract.setBgHoursPerDay(new BigDecimal("8.00"));
          JPA.em().persist(contract);
          Year year = new Year();
          year.setName("Заплатна 2026 " + tag);
          year.setCode("PY-" + tag);
          year.setFromDate(LocalDate.of(2026, 1, 1));
          year.setToDate(LocalDate.of(2026, 12, 31));
          year.setTypeSelect(2);
          JPA.em().persist(year);
          Period period = new Period();
          period.setName("03/2026 " + tag);
          period.setYear(year);
          period.setFromDate(LocalDate.of(2026, 3, 1));
          period.setToDate(LocalDate.of(2026, 3, 31));
          JPA.em().persist(period);
          // Заварен оригинал: потвърден ПРЕДИ замразяването — без режим.
          BgPaySlip original = new BgPaySlip();
          original.setReference("FISH-2026-0011-" + tag);
          original.setStatusSelect(BgPaySlipResolver.CONFIRMED);
          original.setEmploymentContract(contract);
          original.setPeriod(period);
          JPA.em().persist(original);
          BgPaySlip copy = new BgPaySlip();
          copy.setReference("FISH-2026-0016-" + tag);
          copy.setStatusSelect(BgPaySlipResolver.DRAFT);
          copy.setEmploymentContract(contract);
          copy.setPeriod(period);
          copy.setBgIsCorrection(true);
          copy.setBgCorrectionOf(original);
          JPA.em().persist(copy);
          copyId = copy.getId();
          // „Само здравно“ за сметка на лицето — на оригинала и преписано в копието.
          healthOnly(original, "2.10", "26.22");
          healthOnly(copy, "2.10", "26.22");
        });
    JPA.clear();
  }

  private static void healthOnly(BgPaySlip slip, String amount, String base) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setPaySlip(slip);
    line.setBgLineNumber(517);
    line.setName("ЗО само здравно (за сметка на лицето)");
    line.setBgSign(BgPaySlipResolver.DEBIT);
    line.setBgHealthBaseKind(BgD1ContributionAggregator.BASE_HEALTH_ONLY);
    line.setBgAmount(new BigDecimal(amount));
    line.setBgQuantity(new BigDecimal(base));
    JPA.em().persist(line);
  }

  @Test
  @DisplayName("ръчна поправка на „само здравно“ в копието → редът е в одита в базата")
  void aManualHealthOnlyCorrectionIsAuditedInTheDatabase() {
    JPA.runInTransaction(
        () -> {
          BgPaySlip copy = JPA.em().find(BgPaySlip.class, copyId);
          for (BgPaySlipLine line : copy.getBgPaySlipLineList()) {
            line.setBgAmount(new BigDecimal("1.05"));
            line.setBgQuantity(new BigDecimal("13.11"));
          }
          Beans.get(BgPaySlipRepository.class).save(copy);
        });
    JPA.clear();
    BgPaySlip copy = JPA.em().find(BgPaySlip.class, copyId);
    BgPaySlipRegimeChange amount = null;
    for (BgPaySlipRegimeChange row : copy.getBgRegimeChangeList()) {
      if ("healthOnly.DEBIT.amount".equals(row.getFieldName())) {
        amount = row;
      }
    }
    assertNotNull(amount, "поправката е в одита: " + copy.getBgRegimeChangeList());
    assertEquals("2,10", amount.getOldValue());
    assertEquals("1,05", amount.getNewValue());
    assertEquals(BgPaySlipRegimeAudit.MANUAL, amount.getSource());
    assertNotNull(amount.getChangedOn(), "кога");
    assertTrue(
        copy.getBgRegimeChangeSummary().contains("„Само здравно“ е поправено на ръка"),
        copy.getBgRegimeChangeSummary());
  }

  private ActionResponse call(String method) {
    Map<String, Object> context = new HashMap<>();
    context.put("id", copyId);
    Map<String, Object> data = new HashMap<>();
    data.put("context", context);
    ActionRequest request = new ActionRequest();
    request.setData(data);
    request.setModel(BgPaySlip.class.getName());
    ActionResponse response = new ActionResponse();
    BgPaySlipController controller = Beans.get(BgPaySlipController.class);
    if ("calculate".equals(method)) {
      controller.calculate(request, response);
    } else {
      controller.fillRegime(request, response);
    }
    JPA.clear();
    return response;
  }

  @Test
  @DisplayName("копие без режим: пресмятането отказва с изброени полета и не записва нищо")
  void aLegacyCopyRefusesThroughTheController() {
    ActionResponse response = call("calculate");
    String data = String.valueOf(response.getData());
    assertTrue(data.contains("Липсват: вид осигурен, категория труд"), data);
    assertTrue(data.contains("Попълни от текущите данни"), data);
    assertNull(JPA.em().find(BgPaySlip.class, copyId).getBgRegimeFrozenAt(), "нищо не е записано");
  }

  @Test
  @DisplayName("попълване и ръчна промяна → одитът е в базата: поле, старо, ново, кога, как")
  void fillingAndChangingTheRegimeIsAuditedInTheDatabase() {
    // „Попълни“ връща стойностите на екрана, без да пише.
    ActionResponse filled = call("fillRegime");
    Object values = ((Map<?, ?>) ((List<?>) filled.getData()).get(0)).get("values");
    assertNotNull(values, "„Попълни“ връща стойности, не отказ: " + filled.getData());
    assertEquals(
        "01", ((BgPaySlip) values).getBgRegimeInsuredTypeCode(), "екранът получава попълнения вид");
    assertNull(JPA.em().find(BgPaySlip.class, copyId).getBgRegimeFrozenAt(), "още не е записано");

    // Записът на формата: попълненото + следата, през истинското хранилище.
    JPA.runInTransaction(
        () -> {
          BgPaySlip copy = JPA.em().find(BgPaySlip.class, copyId);
          try {
            Beans.get(BgPaySlipService.class).fillRegimeFromCurrent(copy, null);
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
          Beans.get(BgPaySlipRepository.class).save(copy);
        });
    JPA.clear();
    BgPaySlip copy = JPA.em().find(BgPaySlip.class, copyId);
    assertNotNull(copy.getBgRegimeFrozenAt());
    assertEquals(BgPayrollRegime.FILLED, copy.getBgRegimeSource());
    assertEquals("01", copy.getBgRegimeInsuredTypeCode());
    List<BgPaySlipRegimeChange> rows = copy.getBgRegimeChangeList();
    assertEquals(BgPayrollRegime.fieldsFor(copy).size(), rows.size(), "по ред на поле, без двойни");
    for (BgPaySlipRegimeChange row : rows) {
      assertEquals(BgPaySlipRegimeAudit.FILLED, row.getSource(), row.getFieldName());
      assertNotNull(row.getChangedOn(), row.getFieldName());
    }

    // Ръчна промяна: ТЕЛК отметнат в копието и записан.
    JPA.runInTransaction(
        () -> {
          BgPaySlip c = JPA.em().find(BgPaySlip.class, copyId);
          c.setBgRegimeDisabilityRelief(true);
          Beans.get(BgPaySlipRepository.class).save(c);
        });
    JPA.clear();
    copy = JPA.em().find(BgPaySlip.class, copyId);
    BgPaySlipRegimeChange manual = null;
    for (BgPaySlipRegimeChange row : copy.getBgRegimeChangeList()) {
      if (BgPaySlipRegimeAudit.MANUAL.equals(row.getSource())) {
        manual = row;
      }
    }
    assertNotNull(manual, "ръчната промяна е в одита");
    assertEquals("bgRegimeDisabilityRelief", manual.getFieldName());
    assertEquals("не", manual.getOldValue());
    assertEquals("да", manual.getNewValue());
    assertNotNull(manual.getChangedOn());
    assertTrue(
        copy.getBgRegimeChangeSummary().contains("облекчение по ТЕЛК не → да"),
        copy.getBgRegimeChangeSummary());
  }
}

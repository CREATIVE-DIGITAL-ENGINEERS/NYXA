package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.AdjustHistory;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.payroll.BgPaySlipCancelTxTestModule.FailingPeriodSave;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * „Откажи“ на коригиращо копие ПРЕЗ ИСТИНСКИЯ КОНТРОЛЕР: без причина — нищо; с причина — копието е
 * отказано, периодът е CLOSED, следата е затворена и „Отрази пристигнал болничен“ отново вижда
 * оригинала свободен; провал по средата — нищо.
 *
 * <p>Без тестова транзакция около извикването, по образеца на {@code
 * BgPaySlipCalculateTransactionTest}. ⚠️ {@code @Tag("integration")}: {@code ./gradlew build} НЕ го
 * пуска — пуска се през {@code integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgPaySlipCancelTxTestModule.class)
@Tag("integration")
class BgPaySlipRetroCancelTransactionTest extends JpaSupport {

  private static final String REASON = "Листът е отразен в грешния фиш";

  /** Идентификаторите на сцената: оригинал, копие, период, следа. */
  private Long originalId;

  private Long copyId;
  private Long periodId;
  private Long historyId;
  private Long companyId;

  @BeforeEach
  void scene() {
    FailingPeriodSave.failOnPeriodSave = false;
    String tag = String.valueOf(System.nanoTime());
    JPA.runInTransaction(
        () -> {
          Year year = new Year();
          year.setName("Заплатна 2026 " + tag);
          year.setCode("PY-" + tag);
          year.setFromDate(LocalDate.of(2026, 1, 1));
          year.setToDate(LocalDate.of(2026, 12, 31));
          year.setTypeSelect(2);
          JPA.em().persist(year);
          Period period = new Period();
          period.setName("09/2026_NT " + tag);
          period.setYear(year);
          period.setFromDate(LocalDate.of(2026, 9, 1));
          period.setToDate(LocalDate.of(2026, 9, 30));
          period.setStatusSelect(PeriodRepository.STATUS_ADJUSTING);
          JPA.em().persist(period);
          // ⚠️ Дружество и договор: правата на hr.payroll.write върху BgPaySlip са по
          // employmentContract.payCompany = активното дружество (nx_permission.csv).
          Company company = new Company();
          company.setName("Фирма " + tag);
          company.setCode("C" + tag);
          JPA.em().persist(company);
          companyId = company.getId();
          EmploymentContract contract = new EmploymentContract();
          contract.setPayCompany(company);
          JPA.em().persist(contract);
          BgPaySlip original = new BgPaySlip();
          original.setReference("FISH-2026-0001-" + tag);
          original.setStatusSelect(BgPaySlipResolver.CONFIRMED);
          original.setPeriod(period);
          original.setEmploymentContract(contract);
          JPA.em().persist(original);
          BgPaySlip copy = new BgPaySlip();
          copy.setReference("FISH-2026-0015-" + tag);
          copy.setStatusSelect(BgPaySlipResolver.DRAFT);
          copy.setPeriod(period);
          copy.setEmploymentContract(contract);
          copy.setBgIsCorrection(true);
          copy.setBgCorrectionOf(original);
          JPA.em().persist(copy);
          AdjustHistory history = new AdjustHistory();
          history.setPeriod(period);
          history.setStartDate(LocalDateTime.now());
          JPA.em().persist(history);
          originalId = original.getId();
          copyId = copy.getId();
          periodId = period.getId();
          historyId = history.getId();
        });
    JPA.clear();
    // „Откажи“ минава през CapabilityGate. Най-тясната роля по каталога на ролите, не
    // администратор.
    BgRoleLogin.login(JPA.em().find(Company.class, companyId), BgRoleLogin.PAYROLL_WRITE);
  }

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  @Test
  @DisplayName("без роля → шумен отказ, който назовава hr.payroll.write; нищо не е записано")
  void withoutTheRoleNothingIsWritten() {
    BgRoleLogin.logout();
    BgRoleLogin.login(JPA.em().find(Company.class, companyId)); // същото дружество, без роля
    ActionResponse response = cancel(REASON);
    String text = String.valueOf(response.getData());
    assertTrue(
        text.contains("change the status of a pay slip")
            && text.contains(BgRoleLogin.PAYROLL_WRITE),
        "отказът назовава действието и способността по каталога на ролите: " + text);
    assertUntouched(response);
  }

  private ActionResponse cancel(String reason) {
    Map<String, Object> context = new HashMap<>();
    context.put("id", copyId);
    if (reason != null) {
      context.put("bgCancelReasonInput", reason);
    }
    Map<String, Object> data = new HashMap<>();
    data.put("context", context);
    ActionRequest request = new ActionRequest();
    request.setData(data);
    request.setModel(BgPaySlip.class.getName());
    ActionResponse response = new ActionResponse();
    Beans.get(BgPaySlipController.class).cancel(request, response);
    JPA.clear();
    return response;
  }

  private void assertUntouched(ActionResponse response) {
    BgPaySlip copy = JPA.em().find(BgPaySlip.class, copyId);
    assertEquals(BgPaySlipResolver.DRAFT, copy.getStatusSelect(), "копието остава чернова");
    assertNull(copy.getBgCancelReason(), "няма записана причина: " + response.getData());
    assertEquals(
        PeriodRepository.STATUS_ADJUSTING,
        (int) JPA.em().find(Period.class, periodId).getStatusSelect(),
        "периодът остава под корекция");
    assertNull(
        JPA.em().find(AdjustHistory.class, historyId).getEndDate(),
        "следата на корекцията остава отворена");
  }

  @Test
  @DisplayName("без причина → ясен отказ на екрана, нищо не е записано")
  void withoutAReasonNothingIsWritten() {
    ActionResponse response = cancel(null);
    assertTrue(
        String.valueOf(response.getData()).contains("Отказът иска причина."),
        "отказът стига до екрана: " + response.getData());
    assertUntouched(response);
  }

  @Test
  @DisplayName("с причина → отказано, CLOSED, следата затворена, отразяването е свободно")
  void withAReasonThePeriodClosesAndTheArrivalIsFreeAgain() {
    ActionResponse response = cancel("  " + REASON + "  ");

    BgPaySlip copy = JPA.em().find(BgPaySlip.class, copyId);
    assertEquals(BgPaySlipResolver.CANCELLED, copy.getStatusSelect(), "" + response.getData());
    assertEquals(REASON, copy.getBgCancelReason(), "причината е във фиша");
    Period period = JPA.em().find(Period.class, periodId);
    assertEquals(PeriodRepository.STATUS_CLOSED, (int) period.getStatusSelect(), "CLOSED");
    assertNotNull(
        JPA.em().find(AdjustHistory.class, historyId).getEndDate(), "следата е затворена");

    // „Отрази пристигнал болничен“: истинският читател на неотказаните копия и пазачът на периода.
    BgPaySlip original = JPA.em().find(BgPaySlip.class, originalId);
    assertTrue(
        Beans.get(BgSickNoteArrivalService.class).existingRetroOf(original).isEmpty(),
        "отказаното копие не брои — refuseExistingRetro мълчи");
    assertNull(BgPaySlipResolver.refuseRetroPeriod(period, false), "нова корекция е разрешена");
  }

  @Test
  @DisplayName("провал при записа на периода → нито отказ, нито причина, нито следа")
  void aFailureInTheMiddleWritesNothing() {
    FailingPeriodSave.failOnPeriodSave = true;
    ActionResponse response = cancel(REASON);
    assertTrue(
        String.valueOf(response.getData()).contains("провал при записа на периода"),
        "провалът се вижда: " + response.getData());
    assertUntouched(response);
  }
}

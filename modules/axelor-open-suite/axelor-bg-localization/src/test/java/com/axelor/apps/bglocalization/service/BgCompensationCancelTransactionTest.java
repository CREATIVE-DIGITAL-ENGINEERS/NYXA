package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.service.BgCompensationCancelTxTestModule.FailingAfterSave;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.bglocalization.web.BgCompensationController;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * „Откажи“ на обезщетение ПРЕЗ ИСТИНСКИЯ КОНТРОЛЕР: без причина — нищо; с причина — отказано и
 * причината е в документа; провал след записа — нищо.
 *
 * <p>Без тестова транзакция около извикването, по образеца на {@code
 * BgPaySlipCalculateTransactionTest}. ⚠️ {@code @Tag("integration")}: пуска се през {@code
 * integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgCompensationCancelTxTestModule.class)
@Tag("integration")
class BgCompensationCancelTransactionTest extends JpaSupport {

  private static final String REASON = "Документът е издаден по грешен договор";

  private Long docId;
  private Long companyId;

  @BeforeEach
  void scene() {
    FailingAfterSave.failAfterSave = false;
    String tag = String.valueOf(System.nanoTime());
    JPA.runInTransaction(
        () -> {
          Company company = new Company();
          company.setName("Фирма " + tag);
          company.setCode("C" + tag);
          JPA.em().persist(company);
          companyId = company.getId();
          EmploymentContract contract = new EmploymentContract();
          contract.setPayCompany(company);
          JPA.em().persist(contract);
          BgCompensationType type = new BgCompensationType();
          type.setCode("comp_" + tag);
          type.setName("Обезщетение " + tag);
          type.setLegalBasis("чл. 222 ал. 1 КТ");
          type.setBgDirection("EMPLOYER_TO_EMPLOYEE");
          type.setBgBaseVariesBy("NONE");
          JPA.em().persist(type);
          BgCompensation doc = new BgCompensation();
          doc.setReference("OBEZ-2026-" + tag);
          doc.setStatusSelect(BgCompensationResolver.CONFIRMED);
          doc.setEmploymentContract(contract);
          doc.setBgCompensationType(type);
          doc.setBgEventDate(LocalDate.of(2026, 9, 30));
          JPA.em().persist(doc);
          docId = doc.getId();
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

  private ActionResponse cancel(String reason) {
    Map<String, Object> context = new HashMap<>();
    context.put("id", docId);
    if (reason != null) {
      context.put("bgCancelReasonInput", reason);
    }
    Map<String, Object> data = new HashMap<>();
    data.put("context", context);
    ActionRequest request = new ActionRequest();
    request.setData(data);
    request.setModel(BgCompensation.class.getName());
    ActionResponse response = new ActionResponse();
    Beans.get(BgCompensationController.class).cancel(request, response);
    JPA.clear();
    return response;
  }

  private void assertUntouched(ActionResponse response) {
    BgCompensation doc = JPA.em().find(BgCompensation.class, docId);
    assertEquals(BgCompensationResolver.CONFIRMED, doc.getStatusSelect(), "" + response.getData());
    assertNull(doc.getBgCancelReason(), "няма записана причина");
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
  @DisplayName("с причина → отказано, причината е в документа")
  void withAReasonTheDocumentIsCancelled() {
    ActionResponse response = cancel("  " + REASON + "  ");
    BgCompensation doc = JPA.em().find(BgCompensation.class, docId);
    assertEquals(BgCompensationResolver.CANCELLED, doc.getStatusSelect(), "" + response.getData());
    assertEquals(REASON, doc.getBgCancelReason());
  }

  @Test
  @DisplayName("без роля → шумен отказ, който назовава hr.payroll.write; нищо не е записано")
  void withoutTheRoleNothingIsWritten() {
    BgRoleLogin.logout();
    BgRoleLogin.login(JPA.em().find(Company.class, companyId)); // същото дружество, без роля
    ActionResponse response = cancel(REASON);
    String text = String.valueOf(response.getData());
    assertTrue(
        text.contains("change the status of a compensation")
            && text.contains(BgRoleLogin.PAYROLL_WRITE),
        "отказът назовава действието и способността по каталога на ролите: " + text);
    assertUntouched(response);
  }

  @Test
  @DisplayName("провал след записа на документа → нито отказ, нито причина")
  void aFailureAfterTheSaveWritesNothing() {
    FailingAfterSave.failAfterSave = true;
    ActionResponse response = cancel(REASON);
    assertTrue(
        String.valueOf(response.getData()).contains("провал след записа на документа"),
        "провалът се вижда: " + response.getData());
    assertUntouched(response);
  }
}

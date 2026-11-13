/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 *
 * Employee form controller for BG personal-id validation.
 */
package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.service.BgEgnValidationService;
import com.axelor.apps.bglocalization.service.BgEmployeeCompletionService;
import com.axelor.apps.hr.db.Employee;
import com.axelor.auth.AuthUtils;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;

public class EmployeeBgController {

  /**
   * The "Завършете" button of the staged Employee form.
   *
   * <p>⚠️ REPLACES the stock {@code action-employee-record-finish-sbs}, whose whole body was {@code
   * stepByStepSelect = 5}. Not a behaviour change for the operator -- the record ends up in exactly
   * the same state -- but it makes the rule a SERVICE the Microinvest importer can call too, so an
   * imported employee is finished by the same code that finishes a hand-entered one instead of a
   * second copy of the rule that will drift. The button keeps its {@code validate,…,save} chain:
   * this only sets values on the form, the form's own save persists them.
   */
  public void finishStepByStep(ActionRequest request, ActionResponse response) {
    Employee employee = request.getContext().asType(Employee.class);
    Company company = AuthUtils.getUser() == null ? null : AuthUtils.getUser().getActiveCompany();
    Beans.get(BgEmployeeCompletionService.class).completeInMemory(employee, company);
    response.setValues(employee);
  }

  /**
   * onSave check for {@code bgPersonalIdNumber}. Empty value is skipped (not every record has an
   * EGN/LNCh yet). On a checksum mismatch a confirmable warning is raised (not an error), so the
   * user may still save.
   */
  public void validatePersonalId(ActionRequest request, ActionResponse response) {
    Employee employee = request.getContext().asType(Employee.class);

    String number = employee.getBgPersonalIdNumber();
    if (number == null || number.trim().isEmpty()) {
      return;
    }

    String type = employee.getBgPersonalIdType();
    BgEgnValidationService validationService = Beans.get(BgEgnValidationService.class);

    // bgPersonalIdType (bg.personal.id.type.select): "1" = ЕГН, "2" = ЛНЧ
    // (defaults to ЕГН when unset).
    boolean valid =
        "2".equals(type)
            ? validationService.validateLnch(number)
            : validationService.validateEgn(number);

    if (!valid) {
      response.setAlert(
          I18n.get("Невалидно ЕГН/ЛНЧ — проверете контролната цифра. Запис въпреки това?"));
    }
  }
}

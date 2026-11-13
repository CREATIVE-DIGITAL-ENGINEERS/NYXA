package com.axelor.apps.bglocalization.web;

import com.axelor.apps.bankpayment.db.BankOrder;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgPayrollSepaWizard;
import com.axelor.apps.bglocalization.service.payment.BgPayrollSepaOutcome;
import com.axelor.apps.bglocalization.service.payment.BgPayrollSepaService;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;
import java.time.LocalDate;

/**
 * ЕКРАНЪТ НА ПЛАЩАНЕТО НА НЕТАТА — един бутон, който ражда ЕДИН файл за ЕДИН период.
 *
 * <p>Тънък слой нарочно: нито едно правило не живее тук. Виж {@code BgPayrollSepaRules} за
 * отказите, {@code BgPayrollSepaService} за обхода и строежа, {@code BgSepaXsdValidator} за
 * сверяването със схемата.
 *
 * <p><b>⚠️ РАБОТИ ВЪРХУ КОНТЕКСТА НА ФОРМАТА, БЕЗ {@code save}</b> — wizard-ът е преходен, по
 * образеца на {@code BgIncomePaymentFeedController}.
 *
 * <p><b>⚠️ БУТОНЪТ НЕ ВИСИ НА {@code pay()} НА ФИША, И ТОВА Е РЕШЕНИЕ НА ПРОДУКТА.</b> Файлът се
 * ГЕНЕРИРА нарочно, преглежда се и се качва от човек. Закачен за изплащането, той щеше да се ражда
 * по един на всеки фиш — тоест толкова файла, колкото души, всеки с по един ред — а банката чака
 * един файл на ведомост.
 */
@Singleton
public class BgPayrollSepaController {

  /** Подразбирания при отваряне: активното дружество и днешна дата на изпълнение. */
  public void prepare(ActionRequest request, ActionResponse response) {
    BgPayrollSepaWizard form = request.getContext().asType(BgPayrollSepaWizard.class);
    if (form.getExecutionDate() == null) {
      response.setValue("executionDate", LocalDate.now());
    }
  }

  /**
   * ПУСКА ПРОБЕГА.
   *
   * <p>⚠️ ОТКАЗЪТ НЕ СЕ ГЪЛТА И НЕ СЕ СВЕЖДА ДО ИЗВЕСТИЕ. Поименният списък с лица без платежна
   * сметка е ДЪЛЪГ по устройство и е единственото, което казва на човека какво да поправи; {@code
   * TraceBackService#trace} го показва цял, в диалог, който не се затваря със случайно щракване.
   *
   * <p>⚠️ И ПОЛЕТО „Резултат и граници“ СЕ ЧИСТИ ПРИ ОТКАЗ. Оставен там, докладът от предишен
   * УСПЕШЕН пробег би стоял под червеното съобщение и би изглеждал като резултат от този.
   */
  public void generate(ActionRequest request, ActionResponse response) {
    try {
      BgPayrollSepaWizard form = request.getContext().asType(BgPayrollSepaWizard.class);
      CapabilityGate.requireRole("generate a salary payment file", Capabilities.PAYROLL_WRITE);
      CapabilityGate.requireActiveCompany(form.getCompany());
      BgPayrollSepaOutcome outcome =
          Beans.get(BgPayrollSepaService.class)
              .generate(form.getCompany(), form.getPeriod(), form.getExecutionDate());

      response.setValue("resultSummary", outcome.report());
      response.setNotify(I18n.get(outcome.notice()));
      response.setView(
          ActionView.define("Платежно нареждане " + outcome.getBankOrderSeq())
              .model(BankOrder.class.getName())
              .add("form", "bank-order-form")
              .add("grid", "bank-order-grid")
              .context("_showRecord", outcome.getBankOrderId())
              .map());
    } catch (Exception e) {
      response.setValue("resultSummary", null);
      TraceBackService.trace(response, e);
    }
  }
}

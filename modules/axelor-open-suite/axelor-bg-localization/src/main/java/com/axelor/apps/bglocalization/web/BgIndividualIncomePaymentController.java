package com.axelor.apps.bglocalization.web;

import com.axelor.apps.bglocalization.db.BgIndividualIncomePayment;
import com.axelor.apps.bglocalization.service.zddfl.BgIncomeTaxResult;
import com.axelor.apps.bglocalization.service.zddfl.BgIndividualIncomePaymentService;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

/**
 * Изчислението на формата за изплатен доход на физическо лице.
 *
 * <p>Тънък слой нарочно — нито едно данъчно правило не живее тук. Виж {@code BgIncomeTaxResolver}.
 */
@Singleton
public class BgIndividualIncomePaymentController {

  /** Подразбирания при нов ред. */
  public void prepare(ActionRequest request, ActionResponse response) {
    BgIndividualIncomePayment payment =
        request.getContext().asType(BgIndividualIncomePayment.class);
    Beans.get(BgIndividualIncomePaymentService.class).prepareNew(payment);
    response.setValue("source", payment.getSource());
    response.setValue("taxKind", payment.getTaxKind());
  }

  /**
   * Преизчисляване при смяна на вид доход, бруто, ЗОВ, декларацията по чл. 43 ал. 5 или датата.
   *
   * <p>⚠️ При ОТКАЗ сумите не се пипат и причината се показва като предупреждение. Тихо оставени
   * стари числа до нов вид доход са по-опасни от празни полета: те изглеждат сметнати.
   */
  public void compute(ActionRequest request, ActionResponse response) {
    BgIndividualIncomePayment payment =
        request.getContext().asType(BgIndividualIncomePayment.class);
    BgIndividualIncomePaymentService service = Beans.get(BgIndividualIncomePaymentService.class);

    BgIncomeTaxResult result = service.computeInto(payment);

    if (!result.isResolved()) {
      response.setAlert(I18n.get(result.refusal));
      return;
    }

    response.setValue("expenseAmount", payment.getExpenseAmount());
    response.setValue("taxableBase", payment.getTaxableBase());
    response.setValue("taxAmount", payment.getTaxAmount());
    response.setValue("taxKind", payment.getTaxKind());

    // ⚠️ Двете съобщения се СЪБИРАТ, а не се изключват. Обяснението на нулата и предупреждението за
    // датата са различни неща и едно плащане може да иска и двете — показано само едното, другото
    // изчезва без следа.
    StringBuilder message = new StringBuilder();
    if (result.note != null) {
      message.append(result.note);
    }
    String validity = service.validityWarning(payment);
    if (validity != null) {
      if (message.length() > 0) {
        message.append("\n\n");
      }
      message.append("⚠️ ").append(validity);
    }
    if (message.length() > 0) {
      response.setNotify(message.toString());
    }
  }
}

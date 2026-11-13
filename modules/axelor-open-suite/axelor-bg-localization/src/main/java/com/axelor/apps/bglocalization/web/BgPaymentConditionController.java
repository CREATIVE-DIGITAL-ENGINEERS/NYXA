package com.axelor.apps.bglocalization.web;

import com.axelor.apps.account.db.PaymentCondition;
import com.axelor.apps.account.db.PaymentConditionLine;
import com.axelor.db.JPA;
import com.axelor.i18n.I18n;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Пази формата за условия за плащане от суровата грешка на базата при изтриване на ред, който вече
 * е закачен за осчетоводена фактура.
 *
 * <p>Контекст: {@code PaymentConditionLine} има външен ключ от {@code account_invoice_term} ({@code
 * payment_condition_line}). Всяка осчетоводена фактура ражда фактурен срок, който сочи реда, по
 * който е изчислен. Махне ли се такъв ред от формата и се запише, Hibernate издава DELETE, който се
 * блъска във {@code fk_h5mbdyvvo2e3ua5cjs76wuq2q} и до клиента стига гол {@code PSQLException}.
 *
 * <p>Затова проверяваме преди запис: ако ред, който носи фактурни срокове, липсва в подадения
 * списък (т.е. потребителят го е махнал), спираме записа с разбираемо съобщение вместо да оставим
 * базата да гръмне. Редът остава, защото осчетоводените фактури имат право да го сочат.
 */
@Singleton
public class BgPaymentConditionController {

  /**
   * Спира записа, ако потребителят се опитва да махне ред, който е закачен за фактурен срок.
   *
   * <p>⚠️ Виси на onSave след стоковите проверки. Нов запис (id == null) няма какво да пази.
   */
  public void checkRemovedLines(ActionRequest request, ActionResponse response) {
    PaymentCondition paymentCondition = request.getContext().asType(PaymentCondition.class);
    if (paymentCondition.getId() == null) {
      return;
    }

    Set<Long> keptLineIds = new HashSet<>();
    List<PaymentConditionLine> lines = paymentCondition.getPaymentConditionLineList();
    if (lines != null) {
      for (PaymentConditionLine line : lines) {
        if (line.getId() != null) {
          keptLineIds.add(line.getId());
        }
      }
    }

    List<Long> referencedLineIds =
        JPA.em()
            .createQuery(
                "SELECT DISTINCT it.paymentConditionLine.id FROM InvoiceTerm it"
                    + " WHERE it.paymentConditionLine.paymentCondition.id = :pcId",
                Long.class)
            .setParameter("pcId", paymentCondition.getId())
            .getResultList();

    for (Long referencedLineId : referencedLineIds) {
      if (!keptLineIds.contains(referencedLineId)) {
        response.setError(
            I18n.get(
                "This payment term cannot be edited because the changes would apply to already"
                    + " posted invoices."));
        return;
      }
    }
  }
}

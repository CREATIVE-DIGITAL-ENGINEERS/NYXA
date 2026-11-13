package com.axelor.apps.bglocalization.web;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.service.vat.BgVatAdvanceService;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;

/** „Приспадни авансите“ — редовете за приспадане в чернова на окончателната фактура. */
public class BgVatAdvanceController {

  public void deduct(ActionRequest request, ActionResponse response) {
    try {
      Invoice context = request.getContext().asType(Invoice.class);
      if (context.getId() == null) {
        response.setError("Запишете фактурата, преди да приспаднете авансите.");
        return;
      }
      CapabilityGate.requireWrite(Invoice.class, context.getId());
      Invoice invoice = Beans.get(InvoiceRepository.class).find(context.getId());
      int added = Beans.get(BgVatAdvanceService.class).deduct(invoice);
      response.setInfo(
          added == 0
              ? "Няма закачени авансови фактури с ДДС статия — няма какво да се приспадне."
              : "Добавени редове за приспадане на аванс: " + added);
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }
}

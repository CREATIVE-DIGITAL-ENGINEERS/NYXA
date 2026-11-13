package com.axelor.apps.bglocalization.web;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.service.vat.BgVatProtocolPrintService;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.inject.Beans;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

/** „Печат на протокола“ на статията на протокол по чл. 117 ЗДДС. */
@Singleton
public class BgVatProtocolPrintController {

  /**
   * ⚠️ ЧЕТЕ СЕ ОТ БАЗАТА, А НЕ ОТ КОНТЕКСТА — както фишът ({@code BgPaySlipController#print}):
   * протоколът е осчетоводен, а листът трябва да носи записаното, не редакция от екрана.
   */
  public void print(ActionRequest request, ActionResponse response) {
    try {
      Move context = request.getContext().asType(Move.class);
      if (context.getId() == null) {
        response.setInfo("Запишете статията, преди да печатате протокола.");
        return;
      }
      CapabilityGate.requireRead(Move.class, context.getId());
      Move move = Beans.get(MoveRepository.class).find(context.getId());
      String fileLink = Beans.get(BgVatProtocolPrintService.class).print(move);
      response.setView(
          ActionView.define("Протокол по чл. 117 ЗДДС")
              .add("html", fileLink)
              .param("download", "false")
              .map());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }
}

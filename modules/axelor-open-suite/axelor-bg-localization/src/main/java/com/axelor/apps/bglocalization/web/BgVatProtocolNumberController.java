package com.axelor.apps.bglocalization.web;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgVatProtocolNumberEntry;
import com.axelor.apps.bglocalization.service.vat.BgVatProtocolNumberEntryService;
import com.axelor.apps.bglocalization.service.vat.BgVatProtocolNumbering;
import com.axelor.auth.AuthUtils;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.rpc.Context;
import com.google.inject.Singleton;
import java.time.LocalDateTime;

/** Номерът на реално издаден ДДС протокол на заварена осчетоводена статия — диалогът. */
@Singleton
public class BgVatProtocolNumberController {

  /** Отваря диалога от статията — само когато номерът изобщо може да се въведе. */
  public void open(ActionRequest request, ActionResponse response) {
    try {
      Move move =
          Beans.get(MoveRepository.class).find(request.getContext().asType(Move.class).getId());
      // Номерът тук още не е въведен — пита се всичко освен него.
      String refusal = BgVatProtocolNumbering.entryRefusal(move, "-");
      if (refusal != null) {
        response.setError(I18n.get(refusal));
        return;
      }
      response.setView(
          ActionView.define("Номер на издадения ДДС протокол")
              .model(BgVatProtocolNumberEntry.class.getName())
              .add("form", "bg-vat-protocol-number-entry-form")
              .param("popup", "true")
              .param("show-toolbar", "false")
              .param("show-confirm", "false")
              .param("popup-save", "false")
              .context("_moveId", move.getId())
              .map());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** „Запиши номера“ в диалога. */
  public void record(ActionRequest request, ActionResponse response) {
    try {
      Context context = request.getContext();
      Object moveId = context.get("_moveId");
      Move move =
          moveId == null
              ? null
              : Beans.get(MoveRepository.class).find(Long.valueOf(moveId.toString()));
      BgVatProtocolNumberEntry entry = context.asType(BgVatProtocolNumberEntry.class);
      Beans.get(BgVatProtocolNumberEntryService.class)
          .record(
              move,
              entry.getProtocolNumber(),
              entry.getNote(),
              AuthUtils.getUser(),
              LocalDateTime.now());
      response.setNotify(I18n.get("Номерът на протокола е записан."));
      response.setCanClose(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }
}

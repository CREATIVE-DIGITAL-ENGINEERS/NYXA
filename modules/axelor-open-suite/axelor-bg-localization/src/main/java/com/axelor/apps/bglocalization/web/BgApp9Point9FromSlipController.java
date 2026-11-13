package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.repo.BgSickLeaveRepository;
import com.axelor.apps.bglocalization.service.noi.BgApp9Point9FromSlip;
import com.axelor.apps.bglocalization.service.noi.BgApp9Point9FromSlipService;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.auth.AuthUtils;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;
import org.apache.shiro.authz.UnauthorizedException;

/**
 * „Изведи т. 9 от потвърдения фиш“ на болничния лист — видимостта и самото действие. Правилата са в
 * {@link BgApp9Point9FromSlip}, четенето и записът — в {@link BgApp9Point9FromSlipService}.
 */
@Singleton
public class BgApp9Point9FromSlipController {

  /**
   * onLoad: панелът с бутона се показва само когато действието може да мине — включително за ТОЗИ
   * потребител: без {@code hr.noi.write} бутонът би водил само до отказ.
   */
  public void attrs(ActionRequest request, ActionResponse response) {
    try {
      if (!mayWrite()) {
        response.setAttr("point9FromSlipPanel", "hidden", true);
        return;
      }
      BgSickLeave context = request.getContext().asType(BgSickLeave.class);
      BgSickLeave sick =
          context.getId() == null
              ? null
              : Beans.get(BgSickLeaveRepository.class).find(context.getId());
      response.setAttr(
          "point9FromSlipPanel",
          "hidden",
          !Beans.get(BgApp9Point9FromSlipService.class).isOffered(sick));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** Има ли потребителят право за запис по Прил. 9 — без да отказва (за видимостта на панела). */
  protected boolean mayWrite() {
    try {
      CapabilityGate.requireRole("derive point 9 of App. 9", Capabilities.NOI_WRITE);
      return true;
    } catch (UnauthorizedException e) {
      return false;
    }
  }

  /** Бутонът: извежда и записва — или изрежда отказите поименно. */
  public void derive(ActionRequest request, ActionResponse response) {
    try {
      BgSickLeave context = request.getContext().asType(BgSickLeave.class);
      if (context.getId() == null) {
        response.setError(I18n.get("Запишете болничния лист, преди да изведете т. 9."));
        return;
      }
      // Прил. 9 — Capabilities.NOI_WRITE. Извеждането ЗАПИСВА т. 9 върху листа.
      CapabilityGate.requireRole("derive point 9 of App. 9", Capabilities.NOI_WRITE);
      CapabilityGate.requireWrite(BgSickLeave.class, context.getId());
      BgApp9Point9FromSlip.Plan plan =
          Beans.get(BgApp9Point9FromSlipService.class).derive(context.getId(), AuthUtils.getUser());
      if (plan.isRefused()) {
        response.setError(
            "Т. 9 НЕ Е изведена — нищо не е записано:\n\n• " + String.join("\n• ", plan.refusals));
        return;
      }
      response.setInfo(
          "Т. 9 е изведена от потвърдените фишове ("
              + plan.slices.size()
              + " реда):\n"
              + plan.audit
              + "\n\nДните са замразените на фиша, не са сметнати наново. Кой, кога и от кой фиш —"
              + " в панела „Дни по месеци (Приложение № 9, т. 9)“.");
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }
}

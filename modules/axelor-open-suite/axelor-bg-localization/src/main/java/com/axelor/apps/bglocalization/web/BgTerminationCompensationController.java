package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgTerminationCompensation;
import com.axelor.apps.bglocalization.db.repo.BgTerminationCompensationRepository;
import com.axelor.apps.bglocalization.service.BgCompensationService;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

/**
 * Бутоните на правото при прекратяване.
 *
 * <p>Тънък слой: правилата са в {@code BgCompensationResolver}, записът — в {@link
 * BgCompensationService}.
 */
@Singleton
public class BgTerminationCompensationController {

  /**
   * „Създай документ“: от ПОТВЪРДЕНОТО право — чернова на документа, свързана с него, и формата ѝ.
   *
   * <p>Гейт: способност PAYROLL_WRITE; правото се чете от базата и трябва да се вижда; дружеството
   * на договора е активното; потребителят може да СЪЗДАВА документ.
   */
  public void createDocument(ActionRequest request, ActionResponse response) {
    try {
      Long id = request.getContext().asType(BgTerminationCompensation.class).getId();
      if (id == null) {
        response.setError(I18n.get("Документ се прави от ЗАПИСАНО право — запишете го първо."));
        return;
      }
      CapabilityGate.requireRole("create a compensation document", Capabilities.PAYROLL_WRITE);
      CapabilityGate.requireRead(BgTerminationCompensation.class, id);
      BgTerminationCompensation right =
          Beans.get(BgTerminationCompensationRepository.class).find(id);
      if (right == null) {
        response.setError(I18n.get("Правото не е намерено."));
        return;
      }
      EmploymentContract contract = right.getEmploymentContract();
      CapabilityGate.requireActiveCompany(contract == null ? null : contract.getPayCompany());
      CapabilityGate.requireWrite(BgCompensation.class, null);

      BgCompensation doc = Beans.get(BgCompensationService.class).createFromEntitlement(right);
      response.setView(
          ActionView.define("Обезщетение")
              .model(BgCompensation.class.getName())
              .add("form", "bg-compensation-form")
              .add("grid", "bg-compensation-grid")
              .context("_showRecord", String.valueOf(doc.getId()))
              .map());
    } catch (AxelorException e) {
      response.setError(I18n.get(e.getMessage()));
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }
}

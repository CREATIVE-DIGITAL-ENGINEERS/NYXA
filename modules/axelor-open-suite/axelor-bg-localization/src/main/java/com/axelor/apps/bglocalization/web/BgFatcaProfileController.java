package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgFatcaProfile;
import com.axelor.apps.bglocalization.service.fatca.BgFatcaGiin;
import com.axelor.apps.bglocalization.service.fatca.BgFatcaStatus;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

/**
 * FATCA статусът на дружеството — проверка при запис.
 *
 * <p>GIIN е задължителен само за финансова институция за отчитане; когато е попълнен, трябва да е
 * във формата {@code XXXXXX.XXXXX.XX.XXX}. Пази се с главни букви.
 */
@Singleton
public class BgFatcaProfileController {

  public void validate(ActionRequest request, ActionResponse response) {
    try {
      BgFatcaProfile form = request.getContext().asType(BgFatcaProfile.class);
      CapabilityGate.requireRole("save the FATCA status of a company", Capabilities.NAP_WRITE);
      CapabilityGate.requireActiveCompany(form.getCompany());
      String giin = BgFatcaGiin.normalize(form.getGiin());
      if (giin == null) {
        if (BgFatcaStatus.REPORTING_FI.equals(form.getStatusSelect())) {
          response.setError("Финансовата институция за отчитане трябва да има GIIN.");
        }
        return;
      }
      if (!BgFatcaGiin.isValid(giin)) {
        response.setError("GIIN „" + giin + "“ не е във формата XXXXXX.XXXXX.XX.XXX (19 знака).");
        return;
      }
      response.setValue("giin", giin);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }
}

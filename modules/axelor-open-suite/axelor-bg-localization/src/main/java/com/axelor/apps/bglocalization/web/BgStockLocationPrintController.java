package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.PrintingTemplate;
import com.axelor.apps.base.service.user.UserService;
import com.axelor.apps.bglocalization.service.stock.BgStockPrintTemplates;
import com.axelor.apps.stock.db.StockLocation;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.rpc.Context;
import java.util.List;
import java.util.Map;

/**
 * Съветникът „Печат“ на складово място ({@code stock-location-print-wizard-form}, axelor-stock):
 * шаблонът се предлага сам — този от конфигурацията на склада на дружеството, а при празна —
 * „StockLocation PDF“ ({@link BgStockPrintTemplates}). Без него бутонът „Печат“ стои заключен. Само
 * чете — нищо не записва.
 */
public class BgStockLocationPrintController {

  public void defaultTemplate(ActionRequest request, ActionResponse response) {
    Context context = request.getContext();
    if (context.get("$stockLocationPrintTemplate") != null
        || context.get("stockLocationPrintTemplate") != null) {
      return;
    }
    PrintingTemplate template =
        BgStockPrintTemplates.forStockLocationWizard(
            BgStockPrintTemplates.configOf(company(context)), BgStockPrintTemplates::find);
    if (template != null) {
      response.setValue("$stockLocationPrintTemplate", template);
    }
  }

  /**
   * Дружеството на складовото място: от формата ({@code _stockLocation}), от първото избрано в
   * списъка ({@code _ids}), иначе активното дружество на потребителя.
   */
  @SuppressWarnings("unchecked")
  static Company company(Context context) {
    Long id = null;
    Object parent = context.get("_stockLocation");
    if (parent instanceof Map && ((Map<String, Object>) parent).get("id") != null) {
      id = Long.valueOf(((Map<String, Object>) parent).get("id").toString());
    } else if (context.get("_ids") instanceof List && !((List<?>) context.get("_ids")).isEmpty()) {
      id = Long.valueOf(((List<?>) context.get("_ids")).get(0).toString());
    }
    StockLocation location = id == null ? null : JPA.find(StockLocation.class, id);
    if (location != null && location.getCompany() != null) {
      return location.getCompany();
    }
    return Beans.get(UserService.class).getUserActiveCompany();
  }
}

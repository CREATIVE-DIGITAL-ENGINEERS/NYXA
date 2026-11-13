package com.axelor.apps.aiocr.web;

import com.axelor.apps.aiocr.service.ocr.BgCityPostcodeService;
import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.City;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;

/**
 * Drives the Address.zip readonly/validation behavior from the selected City's exact postcode
 * dataset (see {@link BgCityPostcodeService}), wired onto the stock {@code
 * incl-address-main-panel-form} by {@code Address-postcode.xml}. A City with no validation rows is
 * left fully editable and unvalidated (existing behavior preserved).
 */
@Singleton
public class BgAddressPostcodeController {

  public void updateZipReadonly(ActionRequest request, ActionResponse response) {
    Address address = request.getContext().asType(Address.class);
    City city = address.getCity();
    boolean readonly =
        city != null && Beans.get(BgCityPostcodeService.class).isSinglePostcode(city);
    response.setAttr("zip", "readonly", readonly);
  }

  public void validateZip(ActionRequest request, ActionResponse response) {
    Address address = request.getContext().asType(Address.class);
    City city = address.getCity();
    String zip = address.getZip();
    if (city == null || zip == null || zip.trim().isEmpty()) {
      return;
    }
    if (!Beans.get(BgCityPostcodeService.class).isZipValid(city, zip.trim())) {
      response.setError(String.format(I18n.get("Invalid postcode for %s."), city.getName()));
    }
  }
}

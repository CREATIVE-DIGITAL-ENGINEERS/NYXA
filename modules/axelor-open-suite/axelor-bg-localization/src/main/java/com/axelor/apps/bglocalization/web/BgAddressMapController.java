package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.repo.AddressRepository;
import com.axelor.apps.base.exceptions.BaseExceptionMessage;
import com.axelor.apps.base.service.MapService;
import com.axelor.apps.base.service.address.AddressService;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.service.geocode.BgAddressGeocodeService;
import com.axelor.apps.bglocalization.service.geocode.GeocodeResult;
import com.axelor.auth.AuthUtils;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Replaces the stock {@code action-base-address-method-view-map} (same action name, distinct {@code
 * id} -- see {@code Address-map.xml}; this is the module-priority override mechanism already used
 * elsewhere in this codebase, e.g. {@code axelor-account}'s {@code action-period-group-close}, not
 * an edit to stock {@code AddressController} or {@code Address.xml}).
 *
 * <p>Only Bulgarian addresses get the new tiered/validated flow ({@link BgAddressGeocodeService}).
 * Every other country falls through to EXACTLY the same calls stock {@code AddressController
 * .viewMap} made ({@code AddressService.getOrUpdateLatLong} + {@code MapService.getMapUrl}), so
 * behavior for the rest of the app is unchanged.
 */
public class BgAddressMapController {

  private static final String BG_ALPHA2 = "BG";

  /**
   * Ordinary Map button -- reuses a cached safe result; only geocodes if nothing safe is cached.
   */
  public void viewMap(ActionRequest request, ActionResponse response) {
    resolveAndShow(request, response, false);
  }

  /**
   * Admin-only "Refresh map location": discards whatever is cached and resolves again from scratch.
   * The button is admin-only in the view ({@code showIf}), but that is a UI hint, not a control --
   * the authorization is re-checked here, the only place that actually matters.
   */
  public void refreshMap(ActionRequest request, ActionResponse response) {
    if (!AuthUtils.isAdmin(AuthUtils.getUser())) {
      response.setError(I18n.get("You are not allowed to refresh the map location."));
      return;
    }
    resolveAndShow(request, response, true);
  }

  private void resolveAndShow(
      ActionRequest request, ActionResponse response, boolean forceRefresh) {
    try {
      Address address = request.getContext().asType(Address.class);
      address = Beans.get(AddressRepository.class).find(address.getId());

      boolean isBulgaria =
          address.getCountry() != null
              && BG_ALPHA2.equalsIgnoreCase(address.getCountry().getAlpha2Code());

      if (!isBulgaria) {
        showStockMap(address, response);
        return;
      }

      GeocodeResult result =
          Beans.get(BgAddressGeocodeService.class).resolve(address, forceRefresh);
      if (result.isSafe()) {
        response.setView(
            mapView(result.getLatitude(), result.getLongitude(), address.getFullName()));
      } else {
        response.setInfo(
            result.getMessage() != null
                ? result.getMessage()
                : I18n.get("Location could not be resolved safely."));
      }
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Otherwise the same behavior stock {@code AddressController.viewMap} had -- see {@link
   * #mapView}.
   */
  private void showStockMap(Address address, ActionResponse response) throws Exception {
    var latLong = Beans.get(AddressService.class).getOrUpdateLatLong(address);
    if (latLong.isPresent()) {
      response.setView(
          mapView(latLong.get().getLeft(), latLong.get().getRight(), address.getFullName()));
    } else {
      response.setInfo(
          String.format(I18n.get(BaseExceptionMessage.ADDRESS_5), address.getFullName()));
    }
  }

  /**
   * Same shape stock {@code AddressController.viewMap} returns, plus one addition: {@code
   * params.target=_blank}, an existing, already-supported axelor-front mechanism (see {@code
   * ActionExecutor#openView} in axelor-front, which already used it for report-download links) --
   * it opens the map as a real, separate browser tab instead of another entry in the SPA's own tab
   * bar. Every tab the SPA opens stays mounted in the DOM (CSS-hidden, not unmounted) until the
   * user explicitly closes it, and {@code @axelor/ui}'s DndProvider (used by the DMS/tree/gantt
   * views commonly embedded in a Partner/Supplier form's mail panel) creates a new react-dnd
   * HTML5Backend on every mount with no check for one already existing elsewhere in the DOM -- so
   * leaving a Map tab open and then opening another Supplier record (a second same-shaped form,
   * second DndProvider) throws "Cannot have two HTML5 backends at the same time". A real browser
   * tab has its own separate window/DOM, so this never happens there. This is a stock frontend
   * characteristic, not something the BG geocoding change introduced -- it would surface any time
   * two Partner/Supplier-family tabs end up open at once, with or without ever touching Map.
   */
  private Map<String, Object> mapView(
      java.math.BigDecimal latitude, java.math.BigDecimal longitude, String title) {
    MapService mapService = Beans.get(MapService.class);
    Map<String, Object> mapView = new HashMap<>();
    mapView.put("title", "Map");
    mapView.put("resource", mapService.getMapUrl(Pair.of(latitude, longitude), title));
    mapView.put("viewType", "html");
    mapView.put("params", Map.of("target", "_blank"));
    return mapView;
  }
}

package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.PartnerAddress;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.service.MapService;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.service.geocode.BgAddressGeocodeService;
import com.axelor.db.JpaSecurity;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.studio.db.repo.AppBaseRepository;
import java.util.Collections;
import java.util.List;

/**
 * Replaces the stock {@code action-partner-method-show-on-map} (same action name, distinct {@code
 * id} -- the same module-priority override mechanism {@code Address-map.xml} already uses for
 * {@code action-base-address-method-view-map}; not an edit to stock {@code Partner.xml} or {@code
 * PartnerController}).
 *
 * <p>Stock {@code PartnerController.showPartnerOnMap} just opens {@code osm-objs/index.html}, which
 * fetches {@code GET /ws/map/partner/{id}}. That endpoint ({@code MapRest.getPartner}) only ever
 * returns an address whose {@code Address.isValidLatLong} is already {@code true} -- it never
 * geocodes anything itself. For a Bulgarian partner whose addresses were never resolved through
 * {@link BgAddressGeocodeService} (the only thing that safely sets that flag for BG addresses), the
 * endpoint returns an empty {@code data} array and the stock map falls back to its hardcoded
 * Carignano, Italy view. This controller resolves every uncached Bulgarian address on the partner
 * first (reusing the same shared service and cache {@code BgAddressMapController} uses), and only
 * then opens the map -- and only if at least one address actually has a usable location.
 *
 * <p>The {@code Beans.get(...)} calls are behind protected seams so {@code
 * BgPartnerMapControllerTest} can override them directly (no mocking framework, no Guice, no DB --
 * same style as {@code BgFixedAssetRealizationSettingsControllerTest}).
 */
public class BgPartnerMapController {

  private static final String BG_ALPHA2 = "BG";

  public void showPartnerOnMap(ActionRequest request, ActionResponse response) {
    try {
      Long partnerId = request.getContext().asType(Partner.class).getId();

      if (!isPermitted(partnerId)) {
        response.setError(I18n.get("You are not allowed to access this partner."));
        return;
      }

      Partner partner = findPartner(partnerId);
      if (partner == null) {
        response.setInfo(I18n.get("Partner not found."));
        return;
      }

      List<PartnerAddress> partnerAddressList =
          partner.getPartnerAddressList() != null
              ? partner.getPartnerAddressList()
              : Collections.emptyList();

      for (PartnerAddress partnerAddress : partnerAddressList) {
        Address address = partnerAddress.getAddress();
        if (address == null || address.getIsValidLatLong()) {
          continue;
        }
        if (isBulgarian(address)) {
          geocode(address);
        }
      }

      boolean hasResolvedLocation =
          partnerAddressList.stream()
              .map(PartnerAddress::getAddress)
              .anyMatch(address -> address != null && address.getIsValidLatLong());

      if (!hasResolvedLocation) {
        response.setInfo(
            I18n.get("No safe location could be resolved for this partner's addresses."));
        return;
      }

      // params.target=_blank opens this as a real browser tab instead of another entry in the
      // SPA's own tab bar -- see BgAddressMapController.mapView's javadoc for why: every SPA tab
      // stays mounted (CSS-hidden, not unmounted) until explicitly closed, and a second
      // Partner/Supplier-family tab's DMS/mail panel mounting its own react-dnd DndProvider while
      // this one is still around throws "Cannot have two HTML5 backends at the same time".
      response.setView(
          ActionView.define(partner.getFullName())
              .add("html", mapUri(partner))
              .param("target", "_blank")
              .map());
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** Standard record-level read check -- covers both permission groups and company scope. */
  protected boolean isPermitted(Long partnerId) {
    return Beans.get(JpaSecurity.class).isPermitted(JpaSecurity.CAN_READ, Partner.class, partnerId);
  }

  protected Partner findPartner(Long partnerId) {
    return Beans.get(PartnerRepository.class).find(partnerId);
  }

  protected boolean isBulgarian(Address address) {
    return address.getCountry() != null
        && BG_ALPHA2.equalsIgnoreCase(address.getCountry().getAlpha2Code());
  }

  /** Resolves and persists a safe coordinate for {@code address}, if one can be found. */
  protected void geocode(Address address) {
    Beans.get(BgAddressGeocodeService.class).resolve(address, false);
  }

  protected String mapUri(Partner partner) {
    MapService mapService = Beans.get(MapService.class);
    boolean useGoogle =
        Beans.get(AppBaseService.class).getAppBase().getMapApiSelect()
            == AppBaseRepository.MAP_API_GOOGLE;
    return useGoogle
        ? mapService.getMapURI("partner", partner.getId())
        : mapService.getOsmMapURI("partner", partner.getId());
  }
}

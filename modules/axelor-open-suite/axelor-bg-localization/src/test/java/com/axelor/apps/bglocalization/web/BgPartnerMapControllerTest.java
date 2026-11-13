package com.axelor.apps.bglocalization.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.PartnerAddress;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BgPartnerMapController}. No mocking framework, no Guice, no DB (matching
 * this module's established style, see {@code BgFixedAssetRealizationSettingsControllerTest}):
 * {@code Beans.get(...)} calls are behind protected seams the test overrides directly.
 *
 * <p>Proves the fix for BUG 2 (Supplier/Partner header "Map" opening the stock osm-objs view with
 * empty data, which falls back to its hardcoded Carignano, Italy coordinates): the controller must
 * resolve every uncached Bulgarian address first, and must never open the map when none resolves.
 */
class BgPartnerMapControllerTest {

  private static class TestController extends BgPartnerMapController {
    boolean permitted = true;
    Partner partnerToReturn;
    final List<Address> geocodedAddresses = new ArrayList<>();

    /** What {@link #geocode} does to an address, simulating BgAddressGeocodeService.resolve. */
    boolean geocodeSucceeds = true;

    String mapUriToReturn = "osm-objs/index.html?object=partner&id=1";

    @Override
    protected boolean isPermitted(Long partnerId) {
      return permitted;
    }

    @Override
    protected Partner findPartner(Long partnerId) {
      return partnerToReturn;
    }

    @Override
    protected void geocode(Address address) {
      geocodedAddresses.add(address);
      address.setIsValidLatLong(geocodeSucceeds);
    }

    @Override
    protected String mapUri(Partner partner) {
      return mapUriToReturn;
    }
  }

  private static Country bulgaria() {
    Country country = new Country();
    country.setAlpha2Code("BG");
    return country;
  }

  private static Address bgAddress(boolean alreadyValid) {
    Address address = new Address();
    address.setCountry(bulgaria());
    address.setIsValidLatLong(alreadyValid);
    return address;
  }

  private static Partner partnerWith(Address... addresses) {
    Partner partner = new Partner();
    partner.setId(75L);
    partner.setFullName("P0001 - СИ ТИ ДИ ЕООД");
    for (Address address : addresses) {
      PartnerAddress partnerAddress = new PartnerAddress();
      partnerAddress.setAddress(address);
      partner.addPartnerAddressListItem(partnerAddress);
    }
    return partner;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> payload(ActionResponse response) {
    Object data = response.getData();
    if (!(data instanceof List) || ((List<Object>) data).isEmpty()) {
      return Map.of();
    }
    Object first = ((List<Object>) data).get(0);
    return first instanceof Map ? (Map<String, Object>) first : Map.of();
  }

  @SuppressWarnings("unchecked")
  private static String message(ActionResponse response, String key) {
    Object entry = payload(response).get(key);
    return entry instanceof Map ? (String) ((Map<String, Object>) entry).get("message") : null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> params(ActionResponse response) {
    Object view = payload(response).get("view");
    Object params = view instanceof Map ? ((Map<String, Object>) view).get("params") : null;
    return params instanceof Map ? (Map<String, Object>) params : Map.of();
  }

  @Test
  void unauthorisedAccessIsRejectedAndNeverLooksAtThePartner() {
    TestController controller = new TestController();
    controller.permitted = false;
    ActionResponse response = new ActionResponse();

    controller.showPartnerOnMap(request(75L), response);

    assertEquals("You are not allowed to access this partner.", message(response, "error"));
    assertNull(payload(response).get("view"));
    assertTrue(controller.geocodedAddresses.isEmpty());
  }

  @Test
  void uncachedBulgarianAddressIsResolvedBeforeTheMapOpens() {
    TestController controller = new TestController();
    Address address = bgAddress(false);
    controller.partnerToReturn = partnerWith(address);
    controller.geocodeSucceeds = true;
    ActionResponse response = new ActionResponse();

    controller.showPartnerOnMap(request(75L), response);

    assertEquals(List.of(address), controller.geocodedAddresses);
    assertTrue(payload(response).containsKey("view"), "map must open once a location resolves");
    assertNull(message(response, "info"));
    // Opens as a real browser tab (params.target=_blank), not another entry in the SPA's own tab
    // bar -- see BgAddressMapController.mapView's javadoc for why: a second Partner/Supplier-family
    // tab's own DndProvider mounting while this one's mail/DMS panel is still around (SPA tabs stay
    // mounted, CSS-hidden, until explicitly closed) throws "Cannot have two HTML5 backends".
    assertEquals("_blank", params(response).get("target"));
  }

  @Test
  void alreadyCachedAddressIsNeverPassedToGeocodeAgain() {
    TestController controller = new TestController();
    Address address = bgAddress(true); // already has a safe cached location
    controller.partnerToReturn = partnerWith(address);
    ActionResponse response = new ActionResponse();

    controller.showPartnerOnMap(request(75L), response);

    assertTrue(controller.geocodedAddresses.isEmpty(), "cached address must not be re-resolved");
    assertTrue(payload(response).containsKey("view"));
  }

  @Test
  void noResolvableAddressRefusesToOpenAnEmptyMap() {
    TestController controller = new TestController();
    Address address = bgAddress(false);
    controller.partnerToReturn = partnerWith(address);
    controller.geocodeSucceeds = false; // simulates GeocodeResult.none(...)
    ActionResponse response = new ActionResponse();

    controller.showPartnerOnMap(request(75L), response);

    assertEquals(List.of(address), controller.geocodedAddresses);
    assertNull(payload(response).get("view"), "must never open osm-objs with nothing to show");
    assertEquals(
        "No safe location could be resolved for this partner's addresses.",
        message(response, "info"));
  }

  @Test
  void partnerWithNoAddressesRefusesToOpenAnEmptyMap() {
    TestController controller = new TestController();
    controller.partnerToReturn = partnerWith();
    ActionResponse response = new ActionResponse();

    controller.showPartnerOnMap(request(75L), response);

    assertNull(payload(response).get("view"));
    assertTrue(controller.geocodedAddresses.isEmpty());
  }

  @Test
  void nonBulgarianAddressIsNeverSentToTheGeocoder() {
    TestController controller = new TestController();
    Address foreignAddress = new Address(); // no country set at all
    foreignAddress.setIsValidLatLong(false);
    controller.partnerToReturn = partnerWith(foreignAddress);
    ActionResponse response = new ActionResponse();

    controller.showPartnerOnMap(request(75L), response);

    assertTrue(controller.geocodedAddresses.isEmpty());
    // Stock behavior preserved: no safe-location guard applies to non-BG data this controller
    // never touched, so the map still refuses to open on nothing -- but never via
    // BgAddressGeocodeService.
    assertNull(payload(response).get("view"));
  }

  private static ActionRequest request(Long partnerId) {
    ActionRequest request = new ActionRequest();
    Map<String, Object> context = new HashMap<>();
    context.put("id", partnerId);
    Map<String, Object> data = new HashMap<>();
    data.put("context", context);
    request.setData(data);
    request.setModel(Partner.class.getName());
    return request;
  }
}

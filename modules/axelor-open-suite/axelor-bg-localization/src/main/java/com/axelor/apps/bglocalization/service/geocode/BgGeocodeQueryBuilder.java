package com.axelor.apps.bglocalization.service.geocode;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.City;
import com.axelor.apps.base.db.Country;
import com.axelor.common.StringUtils;

/**
 * Builds the free-text query sent to the geocoder from the STRUCTURED, useful parts of an Address
 * only -- street (+ number, + block when present in the free text), district/neighborhood, the
 * Address's OWN postcode (not the City's default), City name and Country name. Entrance/floor/
 * apartment are stripped (see BgAddressTextSanitizer): they add no map precision and commonly
 * confuse geocoders.
 *
 * <p>Deliberately separate from {@code AddressServiceImpl.computeFullName()} / {@code
 * AddressTemplateServiceImpl} -- this class has no effect on Address.fullName or
 * Address.formattedFullName, which stay exactly as before for display purposes elsewhere.
 */
public final class BgGeocodeQueryBuilder {

  private BgGeocodeQueryBuilder() {}

  /**
   * The structured geocoding query for a full-address attempt, or {@code null} if the address does
   * not have at least a City and Country to anchor the search (never send a bare/ambiguous query).
   */
  public static String buildAddressQuery(Address address) {
    if (address == null || address.getCity() == null || address.getCountry() == null) {
      return null;
    }
    City city = address.getCity();
    Country country = address.getCountry();

    StringBuilder sb = new StringBuilder();
    String street = BgAddressTextSanitizer.stripUnitDetails(address.getStreetName());
    if (StringUtils.notBlank(street)) {
      sb.append(street);
    }

    String district = address.getDistrictName();
    if (StringUtils.notBlank(district) && !containsIgnoreCase(street, district)) {
      appendComma(sb, district.trim());
    }

    String zip = address.getZip();
    String cityLine = (StringUtils.notBlank(zip) ? zip.trim() + " " : "") + safe(city.getName());
    appendComma(sb, cityLine.trim());

    appendComma(sb, safe(country.getName()));

    return sb.length() == 0 ? null : sb.toString();
  }

  /**
   * The City-only query used for the one-time centroid resolution (see BgCityCentroidService) --
   * deliberately just City + Country, nothing that could bias it toward a specific building.
   */
  public static String buildCityOnlyQuery(City city, Country country) {
    if (city == null || country == null || StringUtils.isBlank(city.getName())) {
      return null;
    }
    return city.getName().trim() + ", " + safe(country.getName());
  }

  private static void appendComma(StringBuilder sb, String part) {
    if (StringUtils.isBlank(part)) {
      return;
    }
    if (sb.length() > 0) {
      sb.append(", ");
    }
    sb.append(part);
  }

  private static boolean containsIgnoreCase(String haystack, String needle) {
    if (StringUtils.isBlank(haystack) || StringUtils.isBlank(needle)) {
      return false;
    }
    return haystack.toLowerCase().contains(needle.toLowerCase());
  }

  private static String safe(String s) {
    return s == null ? "" : s.trim();
  }
}

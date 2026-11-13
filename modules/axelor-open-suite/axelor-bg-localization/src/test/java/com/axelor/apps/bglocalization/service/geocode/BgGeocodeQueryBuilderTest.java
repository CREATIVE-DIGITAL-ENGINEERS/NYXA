package com.axelor.apps.bglocalization.service.geocode;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.City;
import com.axelor.apps.base.db.Country;
import org.junit.jupiter.api.Test;

class BgGeocodeQueryBuilderTest {

  private City sofia() {
    City city = new City();
    city.setName("София");
    city.setNameEn("Sofia");
    return city;
  }

  private Country bulgaria() {
    Country country = new Country();
    country.setName("България");
    country.setAlpha2Code("BG");
    return country;
  }

  @Test
  void queryIncludesCityActualAddressPostcodeAndCountry() {
    Address address = new Address();
    address.setStreetName("БУЛ.ВАСИЛ ЛЕВСКИ 170");
    address.setZip("4003"); // deliberately different from any City default zip
    address.setCity(sofia());
    address.setCountry(bulgaria());

    String query = BgGeocodeQueryBuilder.buildAddressQuery(address);

    assertTrue(query.contains("4003"), "must use the Address's own postcode, not a City default");
    assertTrue(query.contains("София"));
    assertTrue(query.contains("България"));
    assertTrue(query.contains("БУЛ.ВАСИЛ ЛЕВСКИ 170"));
  }

  @Test
  void entranceFloorApartmentAreExcludedFromTheQuery() {
    Address address = new Address();
    address.setStreetName("ж.к. Дружба 2, бл. 419, вх. Б, ет. 8, ап. 54");
    address.setZip("1000");
    address.setCity(sofia());
    address.setCountry(bulgaria());

    String query = BgGeocodeQueryBuilder.buildAddressQuery(address);

    assertTrue(query.contains("бл. 419"), "block must survive");
    assertTrue(!query.toLowerCase().contains("вх.") && !query.toLowerCase().contains("вход"));
    assertTrue(!query.toLowerCase().contains("ет."));
    assertTrue(!query.toLowerCase().contains("ап."));
  }

  @Test
  void noCityOrNoCountry_returnsNull_neverSendsAnAmbiguousQuery() {
    Address noCity = new Address();
    noCity.setStreetName("some street");
    noCity.setCountry(bulgaria());
    assertNull(BgGeocodeQueryBuilder.buildAddressQuery(noCity));

    Address noCountry = new Address();
    noCountry.setStreetName("some street");
    noCountry.setCity(sofia());
    assertNull(BgGeocodeQueryBuilder.buildAddressQuery(noCountry));
  }

  @Test
  void cityOnlyQuery_isJustCityAndCountry() {
    String query = BgGeocodeQueryBuilder.buildCityOnlyQuery(sofia(), bulgaria());
    assertTrue(query.contains("София"));
    assertTrue(query.contains("България"));
  }
}

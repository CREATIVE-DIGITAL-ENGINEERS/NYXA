package com.axelor.apps.bglocalization.service.saft.dto;

/** AddressStructure — общият адрес (фирма, контрагент, фактура). */
public final class SaftAddress {

  private String streetName;

  private String number;

  private String additionalAddressDetail;

  private String building;

  /** Задължителен. */
  private String city;

  private String postalCode;

  private String region;

  /** Задължителен; ISO 3166-1 alpha-2, точно два знака. */
  private String country;

  /** Незадължителен: StreetAddress, PostalAddress, BillingAddress… */
  private String addressType;

  public String getStreetName() {
    return streetName;
  }

  public SaftAddress streetName(String value) {
    this.streetName = value;
    return this;
  }

  public String getNumber() {
    return number;
  }

  public SaftAddress number(String value) {
    this.number = value;
    return this;
  }

  public String getAdditionalAddressDetail() {
    return additionalAddressDetail;
  }

  public SaftAddress additionalAddressDetail(String value) {
    this.additionalAddressDetail = value;
    return this;
  }

  public String getBuilding() {
    return building;
  }

  public SaftAddress building(String value) {
    this.building = value;
    return this;
  }

  public String getCity() {
    return city;
  }

  public SaftAddress city(String value) {
    this.city = value;
    return this;
  }

  public String getPostalCode() {
    return postalCode;
  }

  public SaftAddress postalCode(String value) {
    this.postalCode = value;
    return this;
  }

  public String getRegion() {
    return region;
  }

  public SaftAddress region(String value) {
    this.region = value;
    return this;
  }

  public String getCountry() {
    return country;
  }

  public SaftAddress country(String value) {
    this.country = value;
    return this;
  }

  public String getAddressType() {
    return addressType;
  }

  public SaftAddress addressType(String value) {
    this.addressType = value;
    return this;
  }
}

package com.axelor.apps.bglocalization.service.saft.dto;

/**
 * Доставчикът на актива (MF.A.5: SupplierName, SupplierID, PostalAddress) или доставчикът/клиентът
 * на транзакцията (SD.AT.8: SupplierCustomerName, SupplierCustomerID, PostalAddress) — една и съща
 * тройка; имената на елементите избира writer-ът по мястото.
 *
 * <p>Книгата (MF.A.5): за актив, изграден от самото лице, и за заварен актив без данни за доставчик
 * се подават данните на задълженото лице — това решава извличането, не writer-ът.
 */
public final class SaftAssetCounterparty {

  /** MF.A.10 / SD.AT.11 — до 70 знака. */
  private String name;

  /** MF.A.11 / SD.AT.12 — до 35 знака. */
  private String id;

  /** MF.A.12 / SD.AT.13 — AddressStructure. */
  private SaftAddress postalAddress;

  public String getName() {
    return name;
  }

  public SaftAssetCounterparty name(String value) {
    this.name = value;
    return this;
  }

  public String getId() {
    return id;
  }

  public SaftAssetCounterparty id(String value) {
    this.id = value;
    return this;
  }

  public SaftAddress getPostalAddress() {
    return postalAddress;
  }

  public SaftAssetCounterparty postalAddress(SaftAddress value) {
    this.postalAddress = value;
    return this;
  }
}

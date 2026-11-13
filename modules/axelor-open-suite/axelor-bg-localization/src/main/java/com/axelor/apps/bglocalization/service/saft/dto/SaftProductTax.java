package com.axelor.apps.bglocalization.service.saft.dto;

/** Данък на продукт (Product/Tax). */
public final class SaftProductTax {

  private String taxType;

  private String taxCode;

  public String getTaxType() {
    return taxType;
  }

  public SaftProductTax taxType(String value) {
    this.taxType = value;
    return this;
  }

  public String getTaxCode() {
    return taxCode;
  }

  public SaftProductTax taxCode(String value) {
    this.taxCode = value;
    return this;
  }
}

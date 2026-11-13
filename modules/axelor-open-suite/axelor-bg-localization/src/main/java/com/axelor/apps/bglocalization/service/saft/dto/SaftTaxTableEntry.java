package com.axelor.apps.bglocalization.service.saft.dto;

import java.util.ArrayList;
import java.util.List;

/** Ред от TaxTable — един TaxType с кодовете си. */
public final class SaftTaxTableEntry {

  private String taxType;

  private String description;

  /** Поне един. */
  private final List<SaftTaxCodeDetail> taxCodeDetails = new ArrayList<>();

  public String getTaxType() {
    return taxType;
  }

  public SaftTaxTableEntry taxType(String value) {
    this.taxType = value;
    return this;
  }

  public String getDescription() {
    return description;
  }

  public SaftTaxTableEntry description(String value) {
    this.description = value;
    return this;
  }

  public List<SaftTaxCodeDetail> getTaxCodeDetails() {
    return taxCodeDetails;
  }

  public SaftTaxTableEntry addTaxCodeDetail(SaftTaxCodeDetail value) {
    this.taxCodeDetails.add(value);
    return this;
  }
}

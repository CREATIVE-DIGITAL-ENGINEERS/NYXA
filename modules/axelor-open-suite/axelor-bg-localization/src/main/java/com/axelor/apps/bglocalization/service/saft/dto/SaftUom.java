package com.axelor.apps.bglocalization.service.saft.dto;

/** Ред от UOMTable. */
public final class SaftUom {

  private String unitOfMeasure;

  private String description;

  public String getUnitOfMeasure() {
    return unitOfMeasure;
  }

  public SaftUom unitOfMeasure(String value) {
    this.unitOfMeasure = value;
    return this;
  }

  public String getDescription() {
    return description;
  }

  public SaftUom description(String value) {
    this.description = value;
    return this;
  }
}

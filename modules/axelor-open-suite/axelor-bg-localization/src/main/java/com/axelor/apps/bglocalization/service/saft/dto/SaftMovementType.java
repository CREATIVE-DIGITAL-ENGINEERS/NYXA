package com.axelor.apps.bglocalization.service.saft.dto;

/** Ред от MovementTypeTable — видът от номенклатурата Stock movement и наименованието му. */
public final class SaftMovementType {

  /** MF.MT.2. */
  private String movementType;

  /** MF.MT.3. */
  private String description;

  public String getMovementType() {
    return movementType;
  }

  public SaftMovementType movementType(String value) {
    this.movementType = value;
    return this;
  }

  public String getDescription() {
    return description;
  }

  public SaftMovementType description(String value) {
    this.description = value;
    return this;
  }
}

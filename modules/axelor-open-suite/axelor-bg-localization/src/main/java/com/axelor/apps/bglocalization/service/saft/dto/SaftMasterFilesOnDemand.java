package com.axelor.apps.bglocalization.service.saft.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * MasterFilesOnDemand — таблиците на файла при поискване (наличности и движение на запаси).
 *
 * <p>TaxTable е незадължителна (Прил. 3 т. III.3 и схемата) и празна НЕ се пише (Прил. 3 т. I.1).
 * Останалите четири искат поне един ред. UOMTable и Products са същите елементи като в месечния
 * файл.
 */
public final class SaftMasterFilesOnDemand {

  /** 2.5 TaxTable — незадължителна; празна не се пише. */
  private final List<SaftTaxTableEntry> taxTableEntries = new ArrayList<>();

  /** 2.8 MovementTypeTable — поне един ред. */
  private final List<SaftMovementType> movementTypes = new ArrayList<>();

  /** 2.6 UOMTable — поне един ред. */
  private final List<SaftUom> uoms = new ArrayList<>();

  /** 2.9 Products — поне един ред. */
  private final List<SaftProduct> products = new ArrayList<>();

  /** 2.10 PhysicalStock — поне един ред. */
  private final List<SaftPhysicalStockEntry> physicalStock = new ArrayList<>();

  public List<SaftTaxTableEntry> getTaxTableEntries() {
    return taxTableEntries;
  }

  public SaftMasterFilesOnDemand addTaxTableEntry(SaftTaxTableEntry value) {
    this.taxTableEntries.add(value);
    return this;
  }

  public List<SaftMovementType> getMovementTypes() {
    return movementTypes;
  }

  public SaftMasterFilesOnDemand addMovementType(SaftMovementType value) {
    this.movementTypes.add(value);
    return this;
  }

  public List<SaftUom> getUoms() {
    return uoms;
  }

  public SaftMasterFilesOnDemand addUom(SaftUom value) {
    this.uoms.add(value);
    return this;
  }

  public List<SaftProduct> getProducts() {
    return products;
  }

  public SaftMasterFilesOnDemand addProduct(SaftProduct value) {
    this.products.add(value);
    return this;
  }

  public List<SaftPhysicalStockEntry> getPhysicalStock() {
    return physicalStock;
  }

  public SaftMasterFilesOnDemand addPhysicalStockEntry(SaftPhysicalStockEntry value) {
    this.physicalStock.add(value);
    return this;
  }
}

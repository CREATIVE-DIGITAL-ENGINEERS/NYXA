package com.axelor.apps.bglocalization.service.saft.dto;

import java.math.BigDecimal;

/**
 * PhysicalStockEntry — само задължителните елементи. Незадължителните (MF.PS.3 LocationID, MF.PS.5
 * StockAccountNo, MF.PS.7 ProductStatus, MF.PS.17 StockCharacteristics) добавя пистата на склада.
 */
public final class SaftPhysicalStockEntry {

  /** MF.PS.2. */
  private String warehouseId;

  /** MF.PS.4. */
  private String productCode;

  /** MF.PS.6 — код по Nom_Inventory_Types. */
  private String productType;

  /** MF.PS.8 — код по NC8_TARIC. */
  private String stockAccountCommodityCode;

  /** MF.PS.9. */
  private String ownerId;

  /** MF.PS.10. */
  private String uomPhysicalStock;

  /** MF.PS.11. */
  private BigDecimal uomToUomBaseConversionFactor;

  /** MF.PS.12. */
  private BigDecimal unitPriceBegin;

  /** MF.PS.12.1. */
  private BigDecimal unitPriceEnd;

  /** MF.PS.13. */
  private BigDecimal openingStockQuantity;

  /** MF.PS.14. */
  private BigDecimal openingStockValue;

  /** MF.PS.15. */
  private BigDecimal closingStockQuantity;

  /** MF.PS.16. */
  private BigDecimal closingStockValue;

  public String getWarehouseId() {
    return warehouseId;
  }

  public SaftPhysicalStockEntry warehouseId(String value) {
    this.warehouseId = value;
    return this;
  }

  public String getProductCode() {
    return productCode;
  }

  public SaftPhysicalStockEntry productCode(String value) {
    this.productCode = value;
    return this;
  }

  public String getProductType() {
    return productType;
  }

  public SaftPhysicalStockEntry productType(String value) {
    this.productType = value;
    return this;
  }

  public String getStockAccountCommodityCode() {
    return stockAccountCommodityCode;
  }

  public SaftPhysicalStockEntry stockAccountCommodityCode(String value) {
    this.stockAccountCommodityCode = value;
    return this;
  }

  public String getOwnerId() {
    return ownerId;
  }

  public SaftPhysicalStockEntry ownerId(String value) {
    this.ownerId = value;
    return this;
  }

  public String getUomPhysicalStock() {
    return uomPhysicalStock;
  }

  public SaftPhysicalStockEntry uomPhysicalStock(String value) {
    this.uomPhysicalStock = value;
    return this;
  }

  public BigDecimal getUomToUomBaseConversionFactor() {
    return uomToUomBaseConversionFactor;
  }

  public SaftPhysicalStockEntry uomToUomBaseConversionFactor(BigDecimal value) {
    this.uomToUomBaseConversionFactor = value;
    return this;
  }

  public BigDecimal getUnitPriceBegin() {
    return unitPriceBegin;
  }

  public SaftPhysicalStockEntry unitPriceBegin(BigDecimal value) {
    this.unitPriceBegin = value;
    return this;
  }

  public BigDecimal getUnitPriceEnd() {
    return unitPriceEnd;
  }

  public SaftPhysicalStockEntry unitPriceEnd(BigDecimal value) {
    this.unitPriceEnd = value;
    return this;
  }

  public BigDecimal getOpeningStockQuantity() {
    return openingStockQuantity;
  }

  public SaftPhysicalStockEntry openingStockQuantity(BigDecimal value) {
    this.openingStockQuantity = value;
    return this;
  }

  public BigDecimal getOpeningStockValue() {
    return openingStockValue;
  }

  public SaftPhysicalStockEntry openingStockValue(BigDecimal value) {
    this.openingStockValue = value;
    return this;
  }

  public BigDecimal getClosingStockQuantity() {
    return closingStockQuantity;
  }

  public SaftPhysicalStockEntry closingStockQuantity(BigDecimal value) {
    this.closingStockQuantity = value;
    return this;
  }

  public BigDecimal getClosingStockValue() {
    return closingStockValue;
  }

  public SaftPhysicalStockEntry closingStockValue(BigDecimal value) {
    this.closingStockValue = value;
    return this;
  }
}

package com.axelor.apps.bglocalization.service.saft.dto;

import java.math.BigDecimal;

/**
 * StockMovementLine — само задължителните елементи. Незадължителните (SD.MG.23–24 ShipTo/ShipFrom,
 * SD.MG.26, SD.MG.32–33) добавя пистата на склада.
 */
public final class SaftStockMovementLine {

  /** SD.MG.18. */
  private String lineNumber;

  /** SD.MG.19. */
  private String accountId;

  /** SD.MG.20. */
  private String transactionId;

  /** SD.MG.21. */
  private String customerId;

  /** SD.MG.22. */
  private String supplierId;

  /** SD.MG.25. */
  private String productCode;

  /** SD.MG.27 — със знак: „+“ при влизане, „-“ при излизане. */
  private BigDecimal quantity;

  /** SD.MG.28. */
  private String unitOfMeasure;

  /** SD.MG.29. */
  private BigDecimal uomToUomPhysicalStockConversionFactor;

  /** SD.MG.30. */
  private BigDecimal bookValue;

  /** SD.MG.31. */
  private String movementSubType;

  public String getLineNumber() {
    return lineNumber;
  }

  public SaftStockMovementLine lineNumber(String value) {
    this.lineNumber = value;
    return this;
  }

  public String getAccountId() {
    return accountId;
  }

  public SaftStockMovementLine accountId(String value) {
    this.accountId = value;
    return this;
  }

  public String getTransactionId() {
    return transactionId;
  }

  public SaftStockMovementLine transactionId(String value) {
    this.transactionId = value;
    return this;
  }

  public String getCustomerId() {
    return customerId;
  }

  public SaftStockMovementLine customerId(String value) {
    this.customerId = value;
    return this;
  }

  public String getSupplierId() {
    return supplierId;
  }

  public SaftStockMovementLine supplierId(String value) {
    this.supplierId = value;
    return this;
  }

  public String getProductCode() {
    return productCode;
  }

  public SaftStockMovementLine productCode(String value) {
    this.productCode = value;
    return this;
  }

  public BigDecimal getQuantity() {
    return quantity;
  }

  public SaftStockMovementLine quantity(BigDecimal value) {
    this.quantity = value;
    return this;
  }

  public String getUnitOfMeasure() {
    return unitOfMeasure;
  }

  public SaftStockMovementLine unitOfMeasure(String value) {
    this.unitOfMeasure = value;
    return this;
  }

  public BigDecimal getUomToUomPhysicalStockConversionFactor() {
    return uomToUomPhysicalStockConversionFactor;
  }

  public SaftStockMovementLine uomToUomPhysicalStockConversionFactor(BigDecimal value) {
    this.uomToUomPhysicalStockConversionFactor = value;
    return this;
  }

  public BigDecimal getBookValue() {
    return bookValue;
  }

  public SaftStockMovementLine bookValue(BigDecimal value) {
    this.bookValue = value;
    return this;
  }

  public String getMovementSubType() {
    return movementSubType;
  }

  public SaftStockMovementLine movementSubType(String value) {
    this.movementSubType = value;
    return this;
  }
}

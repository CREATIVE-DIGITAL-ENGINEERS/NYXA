package com.axelor.apps.bglocalization.service.saft.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** InvoiceLine на фактура за продажба или покупка. */
public final class SaftInvoiceLine {

  private String lineNumber;

  private String accountId;

  private String goodsServicesId;

  private String productCode;

  private String productDescription;

  private BigDecimal quantity;

  private String invoiceUom;

  private BigDecimal uomToUomBaseConversionFactor;

  private BigDecimal unitPrice;

  private LocalDate taxPointDate;

  private String description;

  private SaftAmount invoiceLineAmount;

  /** D / C. */
  private String debitCreditIndicator;

  /** Поне един. */
  private final List<SaftTaxInformation> taxInformation = new ArrayList<>();

  public String getLineNumber() {
    return lineNumber;
  }

  public SaftInvoiceLine lineNumber(String value) {
    this.lineNumber = value;
    return this;
  }

  public String getAccountId() {
    return accountId;
  }

  public SaftInvoiceLine accountId(String value) {
    this.accountId = value;
    return this;
  }

  public String getGoodsServicesId() {
    return goodsServicesId;
  }

  public SaftInvoiceLine goodsServicesId(String value) {
    this.goodsServicesId = value;
    return this;
  }

  public String getProductCode() {
    return productCode;
  }

  public SaftInvoiceLine productCode(String value) {
    this.productCode = value;
    return this;
  }

  public String getProductDescription() {
    return productDescription;
  }

  public SaftInvoiceLine productDescription(String value) {
    this.productDescription = value;
    return this;
  }

  public BigDecimal getQuantity() {
    return quantity;
  }

  public SaftInvoiceLine quantity(BigDecimal value) {
    this.quantity = value;
    return this;
  }

  public String getInvoiceUom() {
    return invoiceUom;
  }

  public SaftInvoiceLine invoiceUom(String value) {
    this.invoiceUom = value;
    return this;
  }

  public BigDecimal getUomToUomBaseConversionFactor() {
    return uomToUomBaseConversionFactor;
  }

  public SaftInvoiceLine uomToUomBaseConversionFactor(BigDecimal value) {
    this.uomToUomBaseConversionFactor = value;
    return this;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public SaftInvoiceLine unitPrice(BigDecimal value) {
    this.unitPrice = value;
    return this;
  }

  public LocalDate getTaxPointDate() {
    return taxPointDate;
  }

  public SaftInvoiceLine taxPointDate(LocalDate value) {
    this.taxPointDate = value;
    return this;
  }

  public String getDescription() {
    return description;
  }

  public SaftInvoiceLine description(String value) {
    this.description = value;
    return this;
  }

  public SaftAmount getInvoiceLineAmount() {
    return invoiceLineAmount;
  }

  public SaftInvoiceLine invoiceLineAmount(SaftAmount value) {
    this.invoiceLineAmount = value;
    return this;
  }

  public String getDebitCreditIndicator() {
    return debitCreditIndicator;
  }

  public SaftInvoiceLine debitCreditIndicator(String value) {
    this.debitCreditIndicator = value;
    return this;
  }

  public List<SaftTaxInformation> getTaxInformation() {
    return taxInformation;
  }

  public SaftInvoiceLine addTaxInformation(SaftTaxInformation value) {
    this.taxInformation.add(value);
    return this;
  }
}

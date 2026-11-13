package com.axelor.apps.bglocalization.service.vat.dto;

import java.math.BigDecimal;

/**
 * One per-VAT-rate bucket of a document (base + VAT for a single rate/tax code).
 *
 * <p>Populated later (WS-2) from {@code Invoice.invoiceLineTaxList} (exTaxBase / taxTotal /
 * taxLine.value / taxLine.tax.code, company-currency variants for BGN), or from {@code
 * MoveLine.taxLineSet / taxCode / taxRate / taxAmount} for non-invoice moves.
 */
public class BgVatAmountDto {

  private String taxCode;
  private BigDecimal vatRatePercent;
  private BigDecimal base;
  private BigDecimal vatAmount;

  /**
   * Target NAP column for this amount. Intentionally null in WS-1: the legal column mapping (PPZDDS
   * Appendix 10/13) is NOT confirmed and must not be guessed. Filled in a later WS.
   */
  private String napColumn;

  public String getTaxCode() {
    return taxCode;
  }

  public void setTaxCode(String taxCode) {
    this.taxCode = taxCode;
  }

  public BigDecimal getVatRatePercent() {
    return vatRatePercent;
  }

  public void setVatRatePercent(BigDecimal vatRatePercent) {
    this.vatRatePercent = vatRatePercent;
  }

  public BigDecimal getBase() {
    return base;
  }

  public void setBase(BigDecimal base) {
    this.base = base;
  }

  public BigDecimal getVatAmount() {
    return vatAmount;
  }

  public void setVatAmount(BigDecimal vatAmount) {
    this.vatAmount = vatAmount;
  }

  public String getNapColumn() {
    return napColumn;
  }

  public void setNapColumn(String napColumn) {
    this.napColumn = napColumn;
  }
}

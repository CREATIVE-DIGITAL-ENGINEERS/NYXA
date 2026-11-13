package com.axelor.apps.bglocalization.service.saft.dto;

import java.math.BigDecimal;

/**
 * TaxInformationStructure — данъкът на ред (S.TI.*).
 *
 * <p>⚠️ ПРАЗЕН {@code taxCode} Е ИЗЯВЛЕНИЕ, НЕ ПРОПУСК: десетте данъка „да се потвърди“ от {@code
 * BgSafTaxMapping} пътуват с празен код и с предупреждение от извличането. Схемата го приема
 * ({@code SAFcodeType} няма минимална дължина); {@code null} обаче е забравено поле и writer-ът го
 * отказва.
 */
public final class SaftTaxInformation {

  /** TaxType по TAX-IMP (днес „100“). */
  private String taxType;

  /** TaxCode по TAX-IMP. */
  private String taxCode;

  /** Незадължителна ставка. */
  private BigDecimal taxPercentage;

  /** Незадължителна данъчна основа. */
  private BigDecimal taxBase;

  /** Сумата на данъка. */
  private SaftAmount taxAmount;

  public String getTaxType() {
    return taxType;
  }

  public SaftTaxInformation taxType(String value) {
    this.taxType = value;
    return this;
  }

  public String getTaxCode() {
    return taxCode;
  }

  public SaftTaxInformation taxCode(String value) {
    this.taxCode = value;
    return this;
  }

  public BigDecimal getTaxPercentage() {
    return taxPercentage;
  }

  public SaftTaxInformation taxPercentage(BigDecimal value) {
    this.taxPercentage = value;
    return this;
  }

  public BigDecimal getTaxBase() {
    return taxBase;
  }

  public SaftTaxInformation taxBase(BigDecimal value) {
    this.taxBase = value;
    return this;
  }

  public SaftAmount getTaxAmount() {
    return taxAmount;
  }

  public SaftTaxInformation taxAmount(SaftAmount value) {
    this.taxAmount = value;
    return this;
  }
}

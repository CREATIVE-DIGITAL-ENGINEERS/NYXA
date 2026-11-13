package com.axelor.apps.bglocalization.service.saft.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** TaxCodeDetails на ред от TaxTable. */
public final class SaftTaxCodeDetail {

  private String taxCode;

  private LocalDate effectiveDate;

  private LocalDate expirationDate;

  private String description;

  private BigDecimal taxPercentage;

  private BigDecimal baseRate;

  /** Схемата допуска само BG. */
  private String country;

  public String getTaxCode() {
    return taxCode;
  }

  public SaftTaxCodeDetail taxCode(String value) {
    this.taxCode = value;
    return this;
  }

  public LocalDate getEffectiveDate() {
    return effectiveDate;
  }

  public SaftTaxCodeDetail effectiveDate(LocalDate value) {
    this.effectiveDate = value;
    return this;
  }

  public LocalDate getExpirationDate() {
    return expirationDate;
  }

  public SaftTaxCodeDetail expirationDate(LocalDate value) {
    this.expirationDate = value;
    return this;
  }

  public String getDescription() {
    return description;
  }

  public SaftTaxCodeDetail description(String value) {
    this.description = value;
    return this;
  }

  public BigDecimal getTaxPercentage() {
    return taxPercentage;
  }

  public SaftTaxCodeDetail taxPercentage(BigDecimal value) {
    this.taxPercentage = value;
    return this;
  }

  public BigDecimal getBaseRate() {
    return baseRate;
  }

  public SaftTaxCodeDetail baseRate(BigDecimal value) {
    this.baseRate = value;
    return this;
  }

  public String getCountry() {
    return country;
  }

  public SaftTaxCodeDetail country(String value) {
    this.country = value;
    return this;
  }
}

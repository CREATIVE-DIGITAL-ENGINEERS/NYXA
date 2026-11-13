package com.axelor.apps.bglocalization.service.saft.dto;

import java.math.BigDecimal;

/**
 * ValuationDAP (MF.A.14, MF.A.35–48) — активът по данъчния амортизационен план (ЗКПО) за годината.
 *
 * <p>Месеците (MF.A.41–44) са текст по схемата (SAFmiddle1textType, до 35 знака): по книгата „цяло
 * десетично число и текст“ — месецът и, по желание, обстоятелството. Какво точно — решава
 * извличането.
 *
 * <p>Актив само в САП, не в ДАП, и категория 0 (книгата, MF.A.9 и MF.A.36) — всички елементи „0“:
 * {@link #zero()}.
 */
public final class SaftValuationDap {

  /** MF.A.35 — по книгата: Материални / Нематериални / Финансови. */
  private String valuationClass;

  /** MF.A.36 — 0 (не е в ДАП) или I–VII като 1–7 (чл. 55 ал. 1 ЗКПО). */
  private Integer categoryTaxDepreciable;

  /** MF.A.37 — данъчна амортизируема стойност. */
  private BigDecimal taxDepreciableValue;

  /** MF.A.38. */
  private BigDecimal accruedTaxDepreciation;

  /** MF.A.39 — данъчна стойност към 01.01. */
  private BigDecimal taxValueAsset;

  /** MF.A.40 — годишна норма в % (xs:decimal). */
  private BigDecimal annualTaxDepreciationRate;

  /** MF.A.41. */
  private String monthChangeAssetValue;

  /** MF.A.42. */
  private String monthSuspensionResumptionAccrual;

  /** MF.A.43. */
  private String monthWriteOffAccounting;

  /** MF.A.44. */
  private String monthWriteOffTax;

  /** MF.A.45 — nonNegativeInteger. */
  private Integer numberMonthsDepreciationDuring;

  /** MF.A.46 — годишната данъчна амортизация. */
  private BigDecimal depreciationForPeriod;

  /** MF.A.47. */
  private BigDecimal accumulatedDepreciation;

  /** MF.A.48 — данъчна стойност към края на периода. */
  private BigDecimal taxValueEndPeriod;

  /**
   * Активът не е в ДАП — категория 0 и всички елементи „0“ (книгата, MF.A.36: „В този случай в
   * елементи от MF.A.36 до MF.A.48 посочете стойност "0"“; MF.A.9 — и класификацията).
   */
  public static SaftValuationDap zero() {
    BigDecimal z = new BigDecimal("0.00");
    return new SaftValuationDap()
        .valuationClass("0")
        .categoryTaxDepreciable(0)
        .taxDepreciableValue(z)
        .accruedTaxDepreciation(z)
        .taxValueAsset(z)
        .annualTaxDepreciationRate(BigDecimal.ZERO)
        .monthChangeAssetValue("0")
        .monthSuspensionResumptionAccrual("0")
        .monthWriteOffAccounting("0")
        .monthWriteOffTax("0")
        .numberMonthsDepreciationDuring(0)
        .depreciationForPeriod(z)
        .accumulatedDepreciation(z)
        .taxValueEndPeriod(z);
  }

  public String getValuationClass() {
    return valuationClass;
  }

  public SaftValuationDap valuationClass(String value) {
    this.valuationClass = value;
    return this;
  }

  public Integer getCategoryTaxDepreciable() {
    return categoryTaxDepreciable;
  }

  public SaftValuationDap categoryTaxDepreciable(Integer value) {
    this.categoryTaxDepreciable = value;
    return this;
  }

  public BigDecimal getTaxDepreciableValue() {
    return taxDepreciableValue;
  }

  public SaftValuationDap taxDepreciableValue(BigDecimal value) {
    this.taxDepreciableValue = value;
    return this;
  }

  public BigDecimal getAccruedTaxDepreciation() {
    return accruedTaxDepreciation;
  }

  public SaftValuationDap accruedTaxDepreciation(BigDecimal value) {
    this.accruedTaxDepreciation = value;
    return this;
  }

  public BigDecimal getTaxValueAsset() {
    return taxValueAsset;
  }

  public SaftValuationDap taxValueAsset(BigDecimal value) {
    this.taxValueAsset = value;
    return this;
  }

  public BigDecimal getAnnualTaxDepreciationRate() {
    return annualTaxDepreciationRate;
  }

  public SaftValuationDap annualTaxDepreciationRate(BigDecimal value) {
    this.annualTaxDepreciationRate = value;
    return this;
  }

  public String getMonthChangeAssetValue() {
    return monthChangeAssetValue;
  }

  public SaftValuationDap monthChangeAssetValue(String value) {
    this.monthChangeAssetValue = value;
    return this;
  }

  public String getMonthSuspensionResumptionAccrual() {
    return monthSuspensionResumptionAccrual;
  }

  public SaftValuationDap monthSuspensionResumptionAccrual(String value) {
    this.monthSuspensionResumptionAccrual = value;
    return this;
  }

  public String getMonthWriteOffAccounting() {
    return monthWriteOffAccounting;
  }

  public SaftValuationDap monthWriteOffAccounting(String value) {
    this.monthWriteOffAccounting = value;
    return this;
  }

  public String getMonthWriteOffTax() {
    return monthWriteOffTax;
  }

  public SaftValuationDap monthWriteOffTax(String value) {
    this.monthWriteOffTax = value;
    return this;
  }

  public Integer getNumberMonthsDepreciationDuring() {
    return numberMonthsDepreciationDuring;
  }

  public SaftValuationDap numberMonthsDepreciationDuring(Integer value) {
    this.numberMonthsDepreciationDuring = value;
    return this;
  }

  public BigDecimal getDepreciationForPeriod() {
    return depreciationForPeriod;
  }

  public SaftValuationDap depreciationForPeriod(BigDecimal value) {
    this.depreciationForPeriod = value;
    return this;
  }

  public BigDecimal getAccumulatedDepreciation() {
    return accumulatedDepreciation;
  }

  public SaftValuationDap accumulatedDepreciation(BigDecimal value) {
    this.accumulatedDepreciation = value;
    return this;
  }

  public BigDecimal getTaxValueEndPeriod() {
    return taxValueEndPeriod;
  }

  public SaftValuationDap taxValueEndPeriod(BigDecimal value) {
    this.taxValueEndPeriod = value;
    return this;
  }
}

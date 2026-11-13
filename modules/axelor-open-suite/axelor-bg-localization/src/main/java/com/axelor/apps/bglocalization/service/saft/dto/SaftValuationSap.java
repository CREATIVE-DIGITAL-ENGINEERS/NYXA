package com.axelor.apps.bglocalization.service.saft.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * ValuationSAP (MF.A.13, MF.A.15–34) — активът по счетоводния амортизационен план за годината.
 *
 * <p>Полезният живот е choice: точно едно от {@link #getAssetLifeYear()} и {@link
 * #getAssetLifeMonth()} (MF.A.19/20); {@link #getDepreciationPercentage()} е месечна или годишна
 * норма според него (MF.A.26).
 *
 * <p>Актив само в ДАП, не в САП (под прага на счетоводната политика) — всички елементи „0“
 * (книгата, MF.A.9): {@link #zero()}.
 */
public final class SaftValuationSap {

  /** MF.A.15 — ValuationClass, до 35 знака. */
  private String valuationClass;

  /** MF.A.16. */
  private BigDecimal acquisitionAndProductionCostsBegin;

  /** MF.A.17. */
  private BigDecimal acquisitionAndProductionCostsEnd;

  /** MF.A.18 — InvestmentSupport. */
  private BigDecimal investmentSupport;

  /** MF.A.19 — полезен живот в години; или това, или {@link #assetLifeMonth}. */
  private Integer assetLifeYear;

  /** MF.A.20 — полезен живот в месеци; или това, или {@link #assetLifeYear}. */
  private Integer assetLifeMonth;

  /** MF.A.21 — AssetAddition. */
  private BigDecimal assetAddition;

  /** MF.A.22 — Transfers. */
  private BigDecimal transfers;

  /** MF.A.23 — AssetDisposal. */
  private BigDecimal assetDisposal;

  /** MF.A.24 — BookValueBegin. */
  private BigDecimal bookValueBegin;

  /** MF.A.25 — DepreciationMethod, до 35 знака. */
  private String depreciationMethod;

  /** MF.A.26 — DepreciationPercentage (xs:decimal, не сума). */
  private BigDecimal depreciationPercentage;

  /** MF.A.27. */
  private BigDecimal depreciationForPeriod;

  /** MF.A.28 — AppreciationForPeriod. */
  private BigDecimal appreciationForPeriod;

  /** MF.A.29/32 — поне една; без извънредна → {@link SaftExtraordinaryDepreciation#none()}. */
  private final List<SaftExtraordinaryDepreciation> extraordinaryDepreciations = new ArrayList<>();

  /** MF.A.30. */
  private BigDecimal accumulatedDepreciation;

  /** MF.A.31 — BookValueEnd. */
  private BigDecimal bookValueEnd;

  /**
   * Активът не е в счетоводния план — всички елементи „0“ (книгата, MF.A.9: „в елементите от
   * подструктура ValuationSAP се посочва стойност "0"“). Полезният живот — в години.
   */
  public static SaftValuationSap zero() {
    BigDecimal z = new BigDecimal("0.00");
    return new SaftValuationSap()
        .valuationClass("0")
        .acquisitionAndProductionCostsBegin(z)
        .acquisitionAndProductionCostsEnd(z)
        .investmentSupport(z)
        .assetLifeYear(0)
        .assetAddition(z)
        .transfers(z)
        .assetDisposal(z)
        .bookValueBegin(z)
        .depreciationMethod("0")
        .depreciationPercentage(BigDecimal.ZERO)
        .depreciationForPeriod(z)
        .appreciationForPeriod(z)
        .addExtraordinaryDepreciation(new SaftExtraordinaryDepreciation().method("0").amount(z))
        .accumulatedDepreciation(z)
        .bookValueEnd(z);
  }

  public String getValuationClass() {
    return valuationClass;
  }

  public SaftValuationSap valuationClass(String value) {
    this.valuationClass = value;
    return this;
  }

  public BigDecimal getAcquisitionAndProductionCostsBegin() {
    return acquisitionAndProductionCostsBegin;
  }

  public SaftValuationSap acquisitionAndProductionCostsBegin(BigDecimal value) {
    this.acquisitionAndProductionCostsBegin = value;
    return this;
  }

  public BigDecimal getAcquisitionAndProductionCostsEnd() {
    return acquisitionAndProductionCostsEnd;
  }

  public SaftValuationSap acquisitionAndProductionCostsEnd(BigDecimal value) {
    this.acquisitionAndProductionCostsEnd = value;
    return this;
  }

  public BigDecimal getInvestmentSupport() {
    return investmentSupport;
  }

  public SaftValuationSap investmentSupport(BigDecimal value) {
    this.investmentSupport = value;
    return this;
  }

  public Integer getAssetLifeYear() {
    return assetLifeYear;
  }

  public SaftValuationSap assetLifeYear(Integer value) {
    this.assetLifeYear = value;
    return this;
  }

  public Integer getAssetLifeMonth() {
    return assetLifeMonth;
  }

  public SaftValuationSap assetLifeMonth(Integer value) {
    this.assetLifeMonth = value;
    return this;
  }

  public BigDecimal getAssetAddition() {
    return assetAddition;
  }

  public SaftValuationSap assetAddition(BigDecimal value) {
    this.assetAddition = value;
    return this;
  }

  public BigDecimal getTransfers() {
    return transfers;
  }

  public SaftValuationSap transfers(BigDecimal value) {
    this.transfers = value;
    return this;
  }

  public BigDecimal getAssetDisposal() {
    return assetDisposal;
  }

  public SaftValuationSap assetDisposal(BigDecimal value) {
    this.assetDisposal = value;
    return this;
  }

  public BigDecimal getBookValueBegin() {
    return bookValueBegin;
  }

  public SaftValuationSap bookValueBegin(BigDecimal value) {
    this.bookValueBegin = value;
    return this;
  }

  public String getDepreciationMethod() {
    return depreciationMethod;
  }

  public SaftValuationSap depreciationMethod(String value) {
    this.depreciationMethod = value;
    return this;
  }

  public BigDecimal getDepreciationPercentage() {
    return depreciationPercentage;
  }

  public SaftValuationSap depreciationPercentage(BigDecimal value) {
    this.depreciationPercentage = value;
    return this;
  }

  public BigDecimal getDepreciationForPeriod() {
    return depreciationForPeriod;
  }

  public SaftValuationSap depreciationForPeriod(BigDecimal value) {
    this.depreciationForPeriod = value;
    return this;
  }

  public BigDecimal getAppreciationForPeriod() {
    return appreciationForPeriod;
  }

  public SaftValuationSap appreciationForPeriod(BigDecimal value) {
    this.appreciationForPeriod = value;
    return this;
  }

  public List<SaftExtraordinaryDepreciation> getExtraordinaryDepreciations() {
    return extraordinaryDepreciations;
  }

  public SaftValuationSap addExtraordinaryDepreciation(SaftExtraordinaryDepreciation value) {
    this.extraordinaryDepreciations.add(value);
    return this;
  }

  public BigDecimal getAccumulatedDepreciation() {
    return accumulatedDepreciation;
  }

  public SaftValuationSap accumulatedDepreciation(BigDecimal value) {
    this.accumulatedDepreciation = value;
    return this;
  }

  public BigDecimal getBookValueEnd() {
    return bookValueEnd;
  }

  public SaftValuationSap bookValueEnd(BigDecimal value) {
    this.bookValueEnd = value;
    return this;
  }
}

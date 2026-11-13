package com.axelor.apps.bglocalization.service.saft.dto;

import java.math.BigDecimal;

/**
 * NonDepreciableAssetDetails (MF.A.9.1, MF.A.49–54) — неамортизируем актив: земя, незавършен,
 * инвестиционен имот по справедлива стойност, финансов, държан за продажба (книгата, MF.A.4 и
 * MF.A.9.1). Вместо Valuations, никога заедно с тях.
 *
 * <p>⚠️ Структурата е от XSD 1.0.3 (в сила от 01.11.2026, З-ЦУ-30-1797/13.10.2026). Схемата 1.0.2 я
 * НЯМА — файл с нея, генериран преди 01.11.2026, валидаторът отказва.
 */
public final class SaftNonDepreciableAsset {

  /** MF.A.49 — NDAAcquisitionAndProductionCostsBegin. */
  private BigDecimal acquisitionAndProductionCostsBegin;

  /** MF.A.50 — NDAAcquisitionAndProductionCostsEnd. */
  private BigDecimal acquisitionAndProductionCostsEnd;

  /** MF.A.51 — NDAInvestmentSupport. */
  private BigDecimal investmentSupport;

  /** MF.A.52 — NDABookValueBegin. */
  private BigDecimal bookValueBegin;

  /** MF.A.53 — RevaluationForPeriod. */
  private BigDecimal revaluationForPeriod;

  /** MF.A.54 — NDABookValueEnd. */
  private BigDecimal bookValueEnd;

  public BigDecimal getAcquisitionAndProductionCostsBegin() {
    return acquisitionAndProductionCostsBegin;
  }

  public SaftNonDepreciableAsset acquisitionAndProductionCostsBegin(BigDecimal value) {
    this.acquisitionAndProductionCostsBegin = value;
    return this;
  }

  public BigDecimal getAcquisitionAndProductionCostsEnd() {
    return acquisitionAndProductionCostsEnd;
  }

  public SaftNonDepreciableAsset acquisitionAndProductionCostsEnd(BigDecimal value) {
    this.acquisitionAndProductionCostsEnd = value;
    return this;
  }

  public BigDecimal getInvestmentSupport() {
    return investmentSupport;
  }

  public SaftNonDepreciableAsset investmentSupport(BigDecimal value) {
    this.investmentSupport = value;
    return this;
  }

  public BigDecimal getBookValueBegin() {
    return bookValueBegin;
  }

  public SaftNonDepreciableAsset bookValueBegin(BigDecimal value) {
    this.bookValueBegin = value;
    return this;
  }

  public BigDecimal getRevaluationForPeriod() {
    return revaluationForPeriod;
  }

  public SaftNonDepreciableAsset revaluationForPeriod(BigDecimal value) {
    this.revaluationForPeriod = value;
    return this;
  }

  public BigDecimal getBookValueEnd() {
    return bookValueEnd;
  }

  public SaftNonDepreciableAsset bookValueEnd(BigDecimal value) {
    this.bookValueEnd = value;
    return this;
  }
}

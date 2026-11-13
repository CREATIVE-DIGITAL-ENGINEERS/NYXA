package com.axelor.apps.bglocalization.service.saft.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Продукт от MasterFiles.
 *
 * <p>⚠️ {@code productCommodityCode} е задължителен като всяко задължително поле: при {@code null}
 * writer-ът спира. „0“ за стока без приложим код и „00000000“ за услуга (MF.P.6) решава {@code
 * BgSaftProductsAssembler}.
 */
public final class SaftProduct {

  private String productCode;

  /** 01 стока / 02 услуга. */
  private String goodsServicesId;

  private String description;

  /** NC8_TARIC, „0“ или „00000000“; виж бележката на класа. */
  private String productCommodityCode;

  private String uomBase;

  /** Кодът по НАП. */
  private String uomStandard;

  private BigDecimal uomToUomBaseConversionFactor;

  private final List<SaftProductTax> taxes = new ArrayList<>();

  public String getProductCode() {
    return productCode;
  }

  public SaftProduct productCode(String value) {
    this.productCode = value;
    return this;
  }

  public String getGoodsServicesId() {
    return goodsServicesId;
  }

  public SaftProduct goodsServicesId(String value) {
    this.goodsServicesId = value;
    return this;
  }

  public String getDescription() {
    return description;
  }

  public SaftProduct description(String value) {
    this.description = value;
    return this;
  }

  public String getProductCommodityCode() {
    return productCommodityCode;
  }

  public SaftProduct productCommodityCode(String value) {
    this.productCommodityCode = value;
    return this;
  }

  public String getUomBase() {
    return uomBase;
  }

  public SaftProduct uomBase(String value) {
    this.uomBase = value;
    return this;
  }

  public String getUomStandard() {
    return uomStandard;
  }

  public SaftProduct uomStandard(String value) {
    this.uomStandard = value;
    return this;
  }

  public BigDecimal getUomToUomBaseConversionFactor() {
    return uomToUomBaseConversionFactor;
  }

  public SaftProduct uomToUomBaseConversionFactor(BigDecimal value) {
    this.uomToUomBaseConversionFactor = value;
    return this;
  }

  public List<SaftProductTax> getTaxes() {
    return taxes;
  }

  public SaftProduct addTax(SaftProductTax value) {
    this.taxes.add(value);
    return this;
  }
}

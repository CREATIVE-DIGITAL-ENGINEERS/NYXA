package com.axelor.apps.aiocr.service.ocr.dto;

import java.math.BigDecimal;

/** One extracted invoice line from the OCR service. Plain carrier, no logic. */
public class OcrExtractLine {

  private String description;
  private String productCode;
  private String unit;
  private BigDecimal qty;
  private BigDecimal unitPrice;
  private BigDecimal netAmount;
  private BigDecimal grossAmount;
  private BigDecimal vatRate;
  private BigDecimal vatAmount;

  /** Set only when the DOCUMENT stated a rate or a per-line VAT amount — never when derived. */
  private boolean vatEvidenceExplicit;

  public String getProductCode() {
    return productCode;
  }

  public void setProductCode(String productCode) {
    this.productCode = productCode;
  }

  public String getUnit() {
    return unit;
  }

  public void setUnit(String unit) {
    this.unit = unit;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public BigDecimal getQty() {
    return qty;
  }

  public void setQty(BigDecimal qty) {
    this.qty = qty;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public void setUnitPrice(BigDecimal unitPrice) {
    this.unitPrice = unitPrice;
  }

  public BigDecimal getNetAmount() {
    return netAmount;
  }

  public void setNetAmount(BigDecimal netAmount) {
    this.netAmount = netAmount;
  }

  public BigDecimal getGrossAmount() {
    return grossAmount;
  }

  public void setGrossAmount(BigDecimal grossAmount) {
    this.grossAmount = grossAmount;
  }

  public BigDecimal getVatRate() {
    return vatRate;
  }

  /**
   * Records a VAT rate the DOCUMENT stated — and marks this line as carrying explicit VAT evidence.
   *
   * <p>The distinction matters because a rate of zero means two completely different things. A line
   * the document actually prints as 0% is an exempt/zero-rated supply and must stay that way. A
   * line whose rate was never read is unknown, and the normalisation below used to turn it into an
   * explicit 0.00 — after which nothing downstream could tell the two apart, and a 20% invoice was
   * booked with no VAT at all. Use {@link #setDerivedVatRate} for anything computed.
   */
  public void setVatRate(BigDecimal vatRate) {
    this.vatRate = vatRate;
    if (vatRate != null) {
      this.vatEvidenceExplicit = true;
    }
  }

  /** A rate this system worked out rather than read. Never counts as evidence. */
  public void setDerivedVatRate(BigDecimal vatRate) {
    this.vatRate = vatRate;
  }

  public BigDecimal getVatAmount() {
    return vatAmount;
  }

  /** A per-line VAT amount the document stated. Explicit evidence, like a stated rate. */
  public void setVatAmount(BigDecimal vatAmount) {
    this.vatAmount = vatAmount;
    if (vatAmount != null) {
      this.vatEvidenceExplicit = true;
    }
  }

  /** A VAT amount this system worked out rather than read. Never counts as evidence. */
  public void setDerivedVatAmount(BigDecimal vatAmount) {
    this.vatAmount = vatAmount;
  }

  /**
   * True when the document itself stated this line's VAT — a rate, an amount, or both.
   *
   * <p>Only lines where this is false may have a rate inferred from the document summary.
   */
  public boolean isVatEvidenceExplicit() {
    return vatEvidenceExplicit;
  }
}

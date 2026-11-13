package com.axelor.apps.bglocalization.service.saft.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * AssetTransaction (SD.AT.2–18) — една транзакция с актив за годината.
 *
 * <p>Незадължителните (SD.AT.6, SD.AT.8, SD.AT.15, SD.AT.16) при {@code null} не се пишат изобщо —
 * транзакция без тях дава същите байтове като преди тях.
 */
public final class SaftAssetTransaction {

  /** SD.AT.3. */
  private String assetTransactionId;

  /** SD.AT.4. */
  private String assetId;

  /** SD.AT.5 — код по AssetMovementTypes. */
  private String assetTransactionType;

  /** SD.AT.6 — незадължителен, до 256 знака. */
  private String description;

  /** SD.AT.7. */
  private LocalDate assetTransactionDate;

  /** SD.AT.8 — незадължителен: доставчикът при придобиване, клиентът при продажба. */
  private SaftAssetCounterparty supplierCustomer;

  /** SD.AT.9 — TransactionID от GeneralLedgerEntries. */
  private String transactionId;

  /** SD.AT.15 — незадължителен, до 35 знака. */
  private String assetValuationType;

  /** SD.AT.16 — незадължителен. */
  private BigDecimal acquisitionAndProductionCostsOnTransaction;

  /** SD.AT.17 — в AssetTransactionValuations/AssetTransactionValuation. */
  private BigDecimal bookValueOnTransaction;

  /** SD.AT.18 — в AssetTransactionValuations/AssetTransactionValuation. */
  private BigDecimal assetTransactionAmount;

  public String getAssetTransactionId() {
    return assetTransactionId;
  }

  public SaftAssetTransaction assetTransactionId(String value) {
    this.assetTransactionId = value;
    return this;
  }

  public String getAssetId() {
    return assetId;
  }

  public SaftAssetTransaction assetId(String value) {
    this.assetId = value;
    return this;
  }

  public String getAssetTransactionType() {
    return assetTransactionType;
  }

  public SaftAssetTransaction assetTransactionType(String value) {
    this.assetTransactionType = value;
    return this;
  }

  public String getDescription() {
    return description;
  }

  public SaftAssetTransaction description(String value) {
    this.description = value;
    return this;
  }

  public SaftAssetCounterparty getSupplierCustomer() {
    return supplierCustomer;
  }

  public SaftAssetTransaction supplierCustomer(SaftAssetCounterparty value) {
    this.supplierCustomer = value;
    return this;
  }

  public String getAssetValuationType() {
    return assetValuationType;
  }

  public SaftAssetTransaction assetValuationType(String value) {
    this.assetValuationType = value;
    return this;
  }

  public BigDecimal getAcquisitionAndProductionCostsOnTransaction() {
    return acquisitionAndProductionCostsOnTransaction;
  }

  public SaftAssetTransaction acquisitionAndProductionCostsOnTransaction(BigDecimal value) {
    this.acquisitionAndProductionCostsOnTransaction = value;
    return this;
  }

  public LocalDate getAssetTransactionDate() {
    return assetTransactionDate;
  }

  public SaftAssetTransaction assetTransactionDate(LocalDate value) {
    this.assetTransactionDate = value;
    return this;
  }

  public String getTransactionId() {
    return transactionId;
  }

  public SaftAssetTransaction transactionId(String value) {
    this.transactionId = value;
    return this;
  }

  public BigDecimal getBookValueOnTransaction() {
    return bookValueOnTransaction;
  }

  public SaftAssetTransaction bookValueOnTransaction(BigDecimal value) {
    this.bookValueOnTransaction = value;
    return this;
  }

  public BigDecimal getAssetTransactionAmount() {
    return assetTransactionAmount;
  }

  public SaftAssetTransaction assetTransactionAmount(BigDecimal value) {
    this.assetTransactionAmount = value;
    return this;
  }
}

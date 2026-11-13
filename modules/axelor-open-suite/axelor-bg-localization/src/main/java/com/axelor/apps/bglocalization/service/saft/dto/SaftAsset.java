package com.axelor.apps.bglocalization.service.saft.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Asset (MF.A.1–9.1) — един дълготраен актив в годишния файл.
 *
 * <p><b>⚠️ ОЦЕНКАТА Е CHOICE.</b> Амортизируем актив носи Valuations — поне една {@link
 * SaftValuationSap} И поне една {@link SaftValuationDap} (MF.A.9); неамортизируем — само {@link
 * SaftNonDepreciableAsset} (MF.A.9.1). Двете заедно или нито едното writer-ът отказва.
 *
 * <p>Книгата (MF.A.9): актив, заведен в ДАП повече от веднъж, се подава в няколко Asset със същия
 * AssetID — тоест AssetID НЕ е уникален в списъка и writer-ът не го проверява.
 *
 * <p>StartUpDate (MF.A.8) е незадължителен по схемата 1.0.3, но книгата го иска при Valuations —
 * writer-ът го иска там.
 */
public final class SaftAsset {

  /** MF.A.2 — инвентарният номер (SAF-T A1), до 35 знака. */
  private String assetId;

  /** MF.A.3 — сметката по SAFAccountType. */
  private String accountId;

  /** MF.A.4 — до 256 знака; за неамортизируем — видът му. */
  private String description;

  /** MF.A.5 — поне един. */
  private final List<SaftAssetCounterparty> suppliers = new ArrayList<>();

  /** MF.A.6 — незадължителен. */
  private LocalDate purchaseOrderDate;

  /** MF.A.7. */
  private LocalDate dateOfAcquisition;

  /** MF.A.8 — задължителен при Valuations (книгата). */
  private LocalDate startUpDate;

  /** MF.A.13 — ValuationSAP в Valuations. */
  private final List<SaftValuationSap> valuationsSap = new ArrayList<>();

  /** MF.A.14 — ValuationDAP в Valuations. */
  private final List<SaftValuationDap> valuationsDap = new ArrayList<>();

  /** MF.A.9.1 — вместо Valuations. */
  private SaftNonDepreciableAsset nonDepreciable;

  public String getAssetId() {
    return assetId;
  }

  public SaftAsset assetId(String value) {
    this.assetId = value;
    return this;
  }

  public String getAccountId() {
    return accountId;
  }

  public SaftAsset accountId(String value) {
    this.accountId = value;
    return this;
  }

  public String getDescription() {
    return description;
  }

  public SaftAsset description(String value) {
    this.description = value;
    return this;
  }

  public List<SaftAssetCounterparty> getSuppliers() {
    return suppliers;
  }

  public SaftAsset addSupplier(SaftAssetCounterparty value) {
    this.suppliers.add(value);
    return this;
  }

  public LocalDate getPurchaseOrderDate() {
    return purchaseOrderDate;
  }

  public SaftAsset purchaseOrderDate(LocalDate value) {
    this.purchaseOrderDate = value;
    return this;
  }

  public LocalDate getDateOfAcquisition() {
    return dateOfAcquisition;
  }

  public SaftAsset dateOfAcquisition(LocalDate value) {
    this.dateOfAcquisition = value;
    return this;
  }

  public LocalDate getStartUpDate() {
    return startUpDate;
  }

  public SaftAsset startUpDate(LocalDate value) {
    this.startUpDate = value;
    return this;
  }

  public List<SaftValuationSap> getValuationsSap() {
    return valuationsSap;
  }

  public SaftAsset addValuationSap(SaftValuationSap value) {
    this.valuationsSap.add(value);
    return this;
  }

  public List<SaftValuationDap> getValuationsDap() {
    return valuationsDap;
  }

  public SaftAsset addValuationDap(SaftValuationDap value) {
    this.valuationsDap.add(value);
    return this;
  }

  public SaftNonDepreciableAsset getNonDepreciable() {
    return nonDepreciable;
  }

  public SaftAsset nonDepreciable(SaftNonDepreciableAsset value) {
    this.nonDepreciable = value;
    return this;
  }
}

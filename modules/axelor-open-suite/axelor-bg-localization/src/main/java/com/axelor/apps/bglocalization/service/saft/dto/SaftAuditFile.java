package com.axelor.apps.bglocalization.service.saft.dto;

/**
 * Одитният файл SAF-T — коренът, който {@code BgSaftXmlWriter} пише.
 *
 * <p>⚠️ ТОВА Е МОДЕЛЪТ, НЕ ИЗВЛИЧАНЕТО. Тук няма нито заявка, нито правило; кой ред откъде идва,
 * решават извличанията (G2 и следващите). Всяко поле носи пътя си в схемата.
 *
 * <p><b>⚠️ ВИДЪТ ИЗБИРА КЛОНА НА КОРЕНА.</b> Схемата е Header плюс ЕДИН от трите клона: месечен
 * (MasterFilesMonthly, GeneralLedgerEntries, SourceDocumentsMonthly), годишен (MasterFilesAnnual,
 * AssetTransactions) или при поискване (MasterFilesOnDemand, MovementOfGoods). Секциите на другите
 * два вида остават {@code null}; попълнена чужда секция спира writer-а, вместо да изчезне тихо.
 * Видът по подразбиране е месечният — така е бил файлът преди годишния и при поискване.
 */
public final class SaftAuditFile {

  private SaftFileKind kind = SaftFileKind.MONTHLY;

  private SaftHeader header;

  private SaftMasterFiles masterFiles;

  private SaftGeneralLedger generalLedger;

  private SaftSourceDocuments sourceDocuments;

  private SaftMasterFilesAnnual masterFilesAnnual;

  /** SourceDocumentsAnnual/AssetTransactions. */
  private SaftAssetTransactions assetTransactions;

  private SaftMasterFilesOnDemand masterFilesOnDemand;

  /** SourceDocumentsOnDemand/MovementOfGoods. */
  private SaftMovementOfGoods movementOfGoods;

  public SaftFileKind getKind() {
    return kind;
  }

  public SaftAuditFile kind(SaftFileKind value) {
    this.kind = value;
    return this;
  }

  public SaftHeader getHeader() {
    return header;
  }

  public SaftAuditFile header(SaftHeader value) {
    this.header = value;
    return this;
  }

  public SaftMasterFiles getMasterFiles() {
    return masterFiles;
  }

  public SaftAuditFile masterFiles(SaftMasterFiles value) {
    this.masterFiles = value;
    return this;
  }

  public SaftGeneralLedger getGeneralLedger() {
    return generalLedger;
  }

  public SaftAuditFile generalLedger(SaftGeneralLedger value) {
    this.generalLedger = value;
    return this;
  }

  public SaftSourceDocuments getSourceDocuments() {
    return sourceDocuments;
  }

  public SaftAuditFile sourceDocuments(SaftSourceDocuments value) {
    this.sourceDocuments = value;
    return this;
  }

  public SaftMasterFilesAnnual getMasterFilesAnnual() {
    return masterFilesAnnual;
  }

  public SaftAuditFile masterFilesAnnual(SaftMasterFilesAnnual value) {
    this.masterFilesAnnual = value;
    return this;
  }

  public SaftAssetTransactions getAssetTransactions() {
    return assetTransactions;
  }

  public SaftAuditFile assetTransactions(SaftAssetTransactions value) {
    this.assetTransactions = value;
    return this;
  }

  public SaftMasterFilesOnDemand getMasterFilesOnDemand() {
    return masterFilesOnDemand;
  }

  public SaftAuditFile masterFilesOnDemand(SaftMasterFilesOnDemand value) {
    this.masterFilesOnDemand = value;
    return this;
  }

  public SaftMovementOfGoods getMovementOfGoods() {
    return movementOfGoods;
  }

  public SaftAuditFile movementOfGoods(SaftMovementOfGoods value) {
    this.movementOfGoods = value;
    return this;
  }
}

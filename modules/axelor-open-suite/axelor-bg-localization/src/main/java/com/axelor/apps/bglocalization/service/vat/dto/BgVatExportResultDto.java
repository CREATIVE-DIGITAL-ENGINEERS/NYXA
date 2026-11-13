package com.axelor.apps.bglocalization.service.vat.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Outcome of an export run — carries the human-facing summary, totals, warnings and errors. */
public class BgVatExportResultDto {

  private boolean success;
  private String message;

  // Echoed request context (for the preview summary)
  private String reportType;
  private String companyName;
  private String periodLabel;

  // Aggregates (placeholders in WS-1)
  private int documentsFound;
  private BigDecimal totalBase = BigDecimal.ZERO;
  private BigDecimal totalVat = BigDecimal.ZERO;
  private BigDecimal totalGross = BigDecimal.ZERO;

  private final List<String> warnings = new ArrayList<>();
  private final List<BgVatValidationErrorDto> validationErrors = new ArrayList<>();

  /** Human-readable one-line summaries of the first collected documents (dry-run preview). */
  private final List<String> sampleDocuments = new ArrayList<>();

  /** Name of the produced file (PRODAGBI.TXT, ...). Null when no file was generated. */
  private String generatedFileName;

  /**
   * Persisted MetaFile id/version of the generated file, so the controller can offer a download.
   */
  private Long metaFileId;

  private Integer metaFileVersion;

  /** All NAP files produced by a (possibly multi-scope / "all") production run. */
  private final List<BgVatGeneratedFileDto> generatedFiles = new ArrayList<>();

  /**
   * Diary rows from the SAME collector pass that produced the TXT files, exposed so the controller
   * can persist them as a snapshot (report == TXT — single source of rows). Empty unless a
   * production sales/purchase scope ran.
   */
  private final List<BgVatSalesRowDto> salesRows = new ArrayList<>();

  private final List<BgVatPurchaseRowDto> purchaseRows = new ArrayList<>();

  /** Защо статии и документи с ДДС не са в дневниците — за съобщението след „Генерирай“. */
  private BgVatExclusionsDto exclusions = new BgVatExclusionsDto();

  public BgVatExclusionsDto getExclusions() {
    return exclusions;
  }

  public void setExclusions(BgVatExclusionsDto exclusions) {
    this.exclusions = exclusions;
  }

  public List<BgVatGeneratedFileDto> getGeneratedFiles() {
    return generatedFiles;
  }

  public List<BgVatSalesRowDto> getSalesRows() {
    return salesRows;
  }

  public List<BgVatPurchaseRowDto> getPurchaseRows() {
    return purchaseRows;
  }

  public boolean isSuccess() {
    return success;
  }

  public void setSuccess(boolean success) {
    this.success = success;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getReportType() {
    return reportType;
  }

  public void setReportType(String reportType) {
    this.reportType = reportType;
  }

  public String getCompanyName() {
    return companyName;
  }

  public void setCompanyName(String companyName) {
    this.companyName = companyName;
  }

  public String getPeriodLabel() {
    return periodLabel;
  }

  public void setPeriodLabel(String periodLabel) {
    this.periodLabel = periodLabel;
  }

  public int getDocumentsFound() {
    return documentsFound;
  }

  public void setDocumentsFound(int documentsFound) {
    this.documentsFound = documentsFound;
  }

  public BigDecimal getTotalBase() {
    return totalBase;
  }

  public void setTotalBase(BigDecimal totalBase) {
    this.totalBase = totalBase;
  }

  public BigDecimal getTotalVat() {
    return totalVat;
  }

  public void setTotalVat(BigDecimal totalVat) {
    this.totalVat = totalVat;
  }

  public BigDecimal getTotalGross() {
    return totalGross;
  }

  public void setTotalGross(BigDecimal totalGross) {
    this.totalGross = totalGross;
  }

  public List<String> getWarnings() {
    return warnings;
  }

  public List<BgVatValidationErrorDto> getValidationErrors() {
    return validationErrors;
  }

  public List<String> getSampleDocuments() {
    return sampleDocuments;
  }

  public String getGeneratedFileName() {
    return generatedFileName;
  }

  public void setGeneratedFileName(String generatedFileName) {
    this.generatedFileName = generatedFileName;
  }

  public Long getMetaFileId() {
    return metaFileId;
  }

  public void setMetaFileId(Long metaFileId) {
    this.metaFileId = metaFileId;
  }

  public Integer getMetaFileVersion() {
    return metaFileVersion;
  }

  public void setMetaFileVersion(Integer metaFileVersion) {
    this.metaFileVersion = metaFileVersion;
  }
}

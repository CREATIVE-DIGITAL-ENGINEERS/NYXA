package com.axelor.apps.bglocalization.service.vat.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure, in-memory description of a prepared VAT package: the four prepared files plus package-level
 * readiness, the zero-declaration flag, declaration summary totals and a human-readable summary.
 * The persistence layer ({@code BgVatPackageService}) maps this onto JPA entities; keeping it a
 * plain value object makes the preparation logic unit-testable without a database.
 */
public class BgVatPreparedPackage {

  public static final String READINESS_READY = "ready";
  public static final String READINESS_WARNINGS = "warnings";
  public static final String READINESS_ERRORS = "errors";

  private final List<BgVatPreparedFile> files = new ArrayList<>();

  private boolean zeroDeclaration;
  private String readiness = READINESS_READY;
  private int documentsFound;
  private int errorCount;
  private int warningCount;
  private BigDecimal totalBase = BigDecimal.ZERO;
  private BigDecimal totalVat = BigDecimal.ZERO;
  private BigDecimal totalGross = BigDecimal.ZERO;
  private BigDecimal k50 = BigDecimal.ZERO;
  private BigDecimal k60 = BigDecimal.ZERO;
  private String summary = "";
  private String reportType;
  private String periodLabel;
  private String companyName;

  public List<BgVatPreparedFile> getFiles() {
    return files;
  }

  public boolean isZeroDeclaration() {
    return zeroDeclaration;
  }

  public void setZeroDeclaration(boolean zeroDeclaration) {
    this.zeroDeclaration = zeroDeclaration;
  }

  public String getReadiness() {
    return readiness;
  }

  public void setReadiness(String readiness) {
    this.readiness = readiness;
  }

  public int getDocumentsFound() {
    return documentsFound;
  }

  public void setDocumentsFound(int documentsFound) {
    this.documentsFound = documentsFound;
  }

  public int getErrorCount() {
    return errorCount;
  }

  public void setErrorCount(int errorCount) {
    this.errorCount = errorCount;
  }

  public int getWarningCount() {
    return warningCount;
  }

  public void setWarningCount(int warningCount) {
    this.warningCount = warningCount;
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

  public BigDecimal getK50() {
    return k50;
  }

  public void setK50(BigDecimal k50) {
    this.k50 = k50;
  }

  public BigDecimal getK60() {
    return k60;
  }

  public void setK60(BigDecimal k60) {
    this.k60 = k60;
  }

  public String getSummary() {
    return summary;
  }

  public void setSummary(String summary) {
    this.summary = summary;
  }

  public String getReportType() {
    return reportType;
  }

  public void setReportType(String reportType) {
    this.reportType = reportType;
  }

  public String getPeriodLabel() {
    return periodLabel;
  }

  public void setPeriodLabel(String periodLabel) {
    this.periodLabel = periodLabel;
  }

  public String getCompanyName() {
    return companyName;
  }

  public void setCompanyName(String companyName) {
    this.companyName = companyName;
  }
}
